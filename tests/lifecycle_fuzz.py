#!/usr/bin/env python3
"""Seeded lifecycle fuzzer: random object-lifecycle programs, frozen reference versus candidate.

Each seed deterministically generates one small AzScript program from
random.Random(seed) alone (no global random state, time or hash order). The
programs combine classes with literal-object and pointer fields, inheritance
chains of up to three levels, explicit and synthesized destructors that emit
event()/probe(), sometimes fail (division by zero, integer overflow, host
failure, a failing Bad field) or call alloc/mem_send_up/mem_free/
make_free/delete, functions returning locals, parameters, fields and
temporaries by value, statement and condition temporaries, objects in loops
with break/continue/return, switch sections, nested blocks, automatic and
manual heap objects, raw allocations, expired this-addresses, host re-entry
through callback 4 into a function that creates and destroys objects, globals,
lifecycle hooks, small call-depth limits and errors raised at random points.

Each program is compiled once (source -> AST -> compile-json -> ABD identity
is checked by vm_execution_end_to_end.compile_source). The reference host (F0,
engine tree) and the candidate host (--candidate-engine) run the same exec in
independent processes at the full budget and over a budget sweep, and every
protocol-2 observation field is compared exactly as
tests/vm_execution_end_to_end.py does (runtime_differential.compare_engines;
remaining_steps and probe fields included). The sweep covers budgets
1..T+1, where T is the smallest budget whose reference observation has the
full run's terminal shape, when T+1 <= --sweep-max; otherwise a deterministic
sample that includes both boundaries, the budget positions of every probe and
evenly spread and seeded points.

A VM refusal at flush whose message starts with 'VM backend does not support'
is counted as refused (per message, flagged when tests/runtime_differences.json
names the message); it is neither a pass nor a mismatch. Any other difference,
including a host crash or timeout, is a mismatch: the program source, exec,
budgets, observations and differing paths are written under the work
directory's mismatches/ folder, and --shrink greedily removes top-level items,
class members and statements (and unwraps compound statements) while the
mismatch persists, writing the minimal program. The exit status is non-zero
on any mismatch, host failure or generated program the compiler rejects (a
generator bug).

The comparison infrastructure (vm_execution_end_to_end, runtime_differential,
runtime_differences, conformance) is imported from this directory; --infra
selects another directory.
"""
import argparse
import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import random
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time

HERE = Path(__file__).resolve().parent
INFRA_FILES = ('vm_execution_end_to_end.py', 'runtime_differential.py', 'runtime_differences.py',
               'runtime_differences.json', 'vm_execution_cases.py', 'conformance/__init__.py')


def _infra_directory(argv):
    explicit = None
    for index, value in enumerate(argv):
        if value == '--infra' and index + 1 < len(argv):
            explicit = argv[index + 1]
        elif value.startswith('--infra='):
            explicit = value.split('=', 1)[1]
    candidate = Path(explicit) if explicit else HERE
    if all((candidate / name).is_file() for name in INFRA_FILES):
        return candidate.resolve()
    raise SystemExit(f'lifecycle_fuzz: comparison infrastructure not found in {candidate} (use --infra)')


INFRA = _infra_directory(sys.argv[1:])
sys.path.insert(0, str(INFRA))

from conformance import EVENT, PROBE, PRELUDE  # noqa: E402
import runtime_differences  # noqa: E402
from runtime_differential import (DEFAULT_STEPS, PROTOCOL, HostFailure, compare_engines,  # noqa: E402
                                  digest, execute, typed, write_json)
from vm_execution_end_to_end import compile_source, terminal_shape  # noqa: E402

INFRA_ROOT = INFRA.parent
REFUSAL = 'VM backend does not support'
REENTRY_ID = 0x0fff0f01
INF = 1000
F0_HOST = INFRA_ROOT / 'build/runtime-perf/f0-source/build/native/interpreter/observation-host'
# PRELUDE (shared with the conformance corpus) declares event/probe and the classes
# M (dtor event(-n)) and Bad (dtor event(100+n) then divides by zero), mk and mkp.
EXTERNS = ('extern int fail(int):6dff0002;\n'
           'extern int setg(int,int):6dff0003;\n'
           'extern int reenter(int,int):6dff0004;\n')
assert EVENT in PRELUDE and PROBE in PRELUDE
# Site markers: event(MARK_BASE + index) statements placed at generated sites, so the
# full-budget reference observation shows which lifecycle features actually ran.
MARK_BASE = 7_000_000
MARKERS = ('delete-completed', 'wrong-delete-completed', 'destructor-delete-completed',
           'reentry-from-destructor-completed', 'reentry-from-constructor-completed', 'reentry-target-completed',
           'send-up-in-unwrapped-destructor-reached', 'failure-gate-opened-in-destructor',
           'failure-gate-opened-in-constructor', 'failure-gate-opened-in-function-body',
           'double-failure-unwind-reached', 'expired-address-used', 'main-first-quarter-done', 'main-half-done',
           'main-three-quarters-done', 'main-end-reached')


# ---------------------------------------------------------------------------
# Program representation: a block is a list of Items; an Item's parts mix
# header/joint strings with child blocks. Shrinking removes Items from blocks
# or replaces a compound Item with one of its child blocks.

class Item:
    __slots__ = ('parts', 'kind', 'fixed', 'name')

    def __init__(self, parts, kind='stmt', fixed=False, name=None):
        self.parts = list(parts)
        self.kind = kind
        self.fixed = fixed
        self.name = name


UNWRAPPABLE = {'block', 'if', 'loop', 'switch'}


def stmt(text, name=None, fixed=False):
    return Item([text], 'stmt', fixed, name)


def render(block, indent=0, out=None):
    out = [] if out is None else out
    for item in block:
        for part in item.parts:
            if isinstance(part, list):
                render(part, indent + 1, out)
            else:
                out.extend('  ' * indent + text for text in part.rstrip('\n').split('\n'))
    return out


class Program:
    def __init__(self, seed, items, options, features):
        self.seed = seed
        self.items = items
        self.options = list(options)
        self.features = features

    @property
    def source(self):
        return '\n'.join(render(self.items)) + '\n'

    def with_items(self, items):
        return Program(self.seed, items, self.options, self.features)


def blocks_of(items):
    """Every block with its path; a path is a tuple of (item index, part index)."""
    def walk(block, path):
        yield path, block
        for index, item in enumerate(block):
            for part_index, part in enumerate(item.parts):
                if isinstance(part, list):
                    yield from walk(part, path + ((index, part_index),))
    yield from walk(items, ())


def block_at(items, path):
    block = items
    for index, part in path:
        block = block[index].parts[part]
    return block


def word(names):
    return re.compile(r'\b(?:' + '|'.join(re.escape(name) for name in sorted(names)) + r')\b')


def referencing(items, pattern):
    """The outermost item whose own text (not its child blocks) matches pattern, with its block and index."""
    for _, block in blocks_of(items):
        for index, item in enumerate(block):
            if any(isinstance(part, str) and pattern.search(part) for part in item.parts):
                return block, index, item
    return None


def cascade(items, path, index):
    """Removes a named item and, transitively, every item that names something removed; None when a
    fixed item would have to go."""
    trial = copy.deepcopy(items)
    block = block_at(trial, path)
    names = {block[index].name}
    del block[index]
    while True:
        hit = referencing(trial, word(names))
        if hit is None:
            return trial
        block, position, item = hit
        if item.fixed or item.kind == 'prelude':
            return None
        del block[position]
        if item.name:
            names.add(item.name)


def matching_paren(text, start):
    depth = 0
    for position in range(start, len(text)):
        if text[position] == '(':
            depth += 1
        elif text[position] == ')':
            depth -= 1
            if depth == 0:
                return position
    return None


BINARY = (' ? ', ' || ', ' && ', ' == ', ' != ', ' <= ', ' >= ', ' < ', ' > ', ' + ', ' - ', ' * ', ' / ', ' % ')


def operands(content):
    """Top-level operands of a parenthesized expression: both sides of its first binary operator, or the
    branches of a conditional."""
    depth = 0
    for position in range(len(content)):
        char = content[position]
        depth += char == '('
        depth -= char == ')'
        if depth:
            continue
        for operator in BINARY:
            if content.startswith(operator, position):
                left, right = content[:position], content[position + len(operator):]
                if operator == ' ? ':
                    split = operands(right)
                    return [part for part in split if part] if ' : ' in right else []
                return [left, right]
        if content.startswith(' : ', position):
            return [content[:position], content[position + 3:]]
    return []


def simplifications(text, kind, part):
    """Shorter variants of one header or statement text: constant conditions, calls and arguments replaced
    by 0, and parenthesized operations replaced by one operand. The compiler rejects invalid ones."""
    result = []
    if kind == 'if' and part == 0 and text.startswith('if (') and text.endswith(') {'):
        result += [f'if ({value}) {{' for value in ('true', 'false')]
    for match in re.finditer(r'[A-Za-z_]\w*\(|\(', text):
        start = match.start()
        opening = match.end() - 1
        close = matching_paren(text, opening)
        if close is None:
            continue
        content = text[opening + 1:close]
        if match.group() == '(':
            if start and (text[start - 1].isalnum() or text[start - 1] == '_'):
                continue
            result += [text[:start] + operand + text[close + 1:] for operand in operands(content)]
            continue
        if match.group()[:-1] in ('if', 'while', 'for', 'switch'):
            continue
        if content.strip() and not re.fullmatch(r'-?\d+|true|false|null', content.strip()) and ',' not in content:
            result.append(text[:opening + 1] + '0' + text[close:])
        end = close + 1
        member = re.match(r'\.\w+', text[end:])
        if member:
            end += member.end()
        if start > 0:
            result.append(text[:start] + '0' + text[end:])
    seen, unique = set(), []
    for value in result:
        if len(value) < len(text) and value not in seen:
            seen.add(value)
            unique.append(value)
    return unique


def shrink_operations(items):
    """Removals first, then cascades, unwraps and finally text simplifications; each strictly shortens the
    source, so greedy shrinking terminates."""
    operations = []
    for path, block in blocks_of(items):
        for index in reversed(range(len(block))):
            if not block[index].fixed:
                operations.append(('remove', path, index, None))
    source = '\n'.join(render(items))
    for path, block in blocks_of(items):
        for index, item in enumerate(block):
            if not item.fixed and item.name and len(word([item.name]).findall(source)) > 1:
                operations.append(('cascade', path, index, None))
    for path, block in blocks_of(items):
        for index, item in enumerate(block):
            if item.kind in UNWRAPPABLE and not item.fixed:
                operations.extend(('unwrap', path, index, part) for part, value in enumerate(item.parts)
                                  if isinstance(value, list))
    for path, block in blocks_of(items):
        for index, item in enumerate(block):
            if item.kind in ('prelude', 'class', 'func', 'main'):
                continue
            for part, value in enumerate(item.parts):
                if isinstance(value, str) and not (item.kind == 'member' and part == 0):
                    operations.extend(('rewrite', path, index, (part, text))
                                      for text in simplifications(value, item.kind, part))
    return operations


def apply_operation(items, operation):
    """(trial items or None when the operation cannot apply, the item it removed or changed)."""
    kind, path, index, part = operation
    if kind == 'cascade':
        return cascade(items, path, index), block_at(items, path)[index]
    trial = copy.deepcopy(items)
    block = block_at(trial, path)
    removed = block[index]
    if kind == 'rewrite':
        removed.parts[part[0]] = part[1]
        return trial, removed
    block[index:index + 1] = [] if kind == 'remove' else removed.parts[part]
    return trial, removed


def unresolvable(operation, removed, trial):
    """A removed declaration still named elsewhere cannot compile; such trials are skipped unrun."""
    return (operation[0] == 'remove' and removed.name is not None and
            word([removed.name]).search(trial.source) is not None)


def shrink(program, fails, limit, log=None):
    """Greedy reduction: keep every removal, cascade, unwrap or simplification after which fails(program)
    still holds."""
    attempts = accepted = position = 0
    changed = False
    current = program
    while attempts < limit:
        operations = shrink_operations(current.items)
        if position >= len(operations):
            if not changed:
                break
            position, changed = 0, False
            continue
        items, removed = apply_operation(current.items, operations[position])
        trial = current.with_items(items) if items is not None else None
        if trial is None or unresolvable(operations[position], removed, trial):
            position += 1
            continue
        attempts += 1
        if fails(trial):
            current, changed = trial, True
            accepted += 1
            if log:
                log(f'shrink: accepted {operations[position][0]} ({len(trial.source)} bytes, attempt {attempts})')
        else:
            position += 1
    return current, attempts, accepted


# ---------------------------------------------------------------------------
# Generator

class Cls:
    def __init__(self, name, level, base, ctor, prelude=False):
        self.name, self.level, self.base, self.ctor = name, level, base, ctor
        self.own_ints, self.own_lits, self.own_ptrs = [], [], []
        self.who = base.who if base else False
        self.depth = base.depth + 1 if base else 1
        self.prelude = prelude
        self.members = None
        self.has_self = False
        self.explicit_dtor = False

    def ints(self):
        return (self.base.ints() if self.base else []) + self.own_ints

    def lits(self):
        return (self.base.lits() if self.base else []) + self.own_lits

    def ptrs(self):
        return (self.base.ptrs() if self.base else []) + self.own_ptrs

    def ancestors(self):
        result, cls = [], self
        while cls:
            result.append(cls)
            cls = cls.base
        return result


class Var:
    __slots__ = ('name', 'kind', 'cls', 'loop', 'dead', 'fixed', 'maybe_null', 'target')

    def __init__(self, name, kind, cls=None, loop=0, fixed=False, maybe_null=False, target=None, dead=False):
        self.name, self.kind, self.cls, self.loop = name, kind, cls, loop
        self.fixed, self.maybe_null, self.target, self.dead = fixed, maybe_null, target, dead


class Fn:
    """Generation context of one function body."""
    def __init__(self, level, ret, budget, allow_re, fns=(), params=(), rets=True):
        self.level, self.ret, self.budget, self.allow_re = level, ret, budget, allow_re
        self.fns = list(fns)
        self.scopes = [list(params)]
        self.loop = self.breakable = 0
        self.rets = rets

    def visible(self):
        return [var for scope in self.scopes for var in scope]

    def declare(self, var):
        self.scopes[-1].append(var)
        return var


class Generator:
    MAX_DEPTH = 3

    def __init__(self, seed):
        self.seed = seed
        self.rng = random.Random(seed)
        self.serial = 0
        self.helpers = {}
        self.features = {}
        self.m = Cls('M', 0, None, 'int', prelude=True)
        self.m.own_ints.append('n')
        self.bad = Cls('Bad', 0, None, 'int', prelude=True)
        self.bad.own_ints.append('n')
        self.classes = [self.m, self.bad]
        self.has_re = False
        self.re_level = 0
        self.g0 = False
        self.gp = None
        self.int_fns, self.ret_fns, self.retp_fns = [], [], []

    # -- utilities
    def feature(self, name):
        self.features[name] = self.features.get(name, 0) + 1

    def fresh(self, prefix):
        self.serial += 1
        return f'{prefix}{self.serial}'

    def chance(self, p):
        return self.rng.random() < p

    def small(self):
        return self.rng.choice((0, 1, 1, 2, 2, 3, 4))

    def pick(self, options):
        """Weighted choice among thunks; a thunk returning None is not applicable here."""
        options = [option for option in options if option[0] > 0]
        while options:
            point = self.rng.random() * sum(weight for weight, _ in options)
            index = len(options) - 1
            for position, (weight, _) in enumerate(options):
                point -= weight
                if point < 0:
                    index = position
                    break
            result = options[index][1]()
            if result is not None:
                return result
            del options[index]
        return None

    def some_class(self, level, bad=0.1):
        pool = [cls for cls in self.classes if cls.level < level and (bad or cls is not self.bad)]
        if not pool:
            return None
        weights = [bad if cls is self.bad else 1.0 if cls is self.m else 2.5 for cls in pool]
        return self.rng.choices(pool, weights)[0]

    def args(self, cls, value):
        return f'({value})' if cls.ctor == 'int' else '()'

    def literal_decl(self, cls, name, value):
        if cls.ctor == 'int':
            return f'{cls.name} {name}({value});'
        return f'{cls.name} {name};' if self.chance(0.5) else f'{cls.name} {name}();'

    # -- lazily emitted helpers (one per kind and class, created on first use)
    def helper(self, key, name, build):
        if key not in self.helpers:
            self.helpers[key] = None
            self.helpers[key] = build(name)
        return name

    def factory(self, cls):
        """By-value factory: returns a local (moved), sometimes from a nested scope or after a copy."""
        if cls is self.m:
            return 'mk'

        def build(name):
            decl = lambda var: stmt(self.literal_decl(cls, var, 'v'))
            variant = self.rng.randrange(4)
            self.feature(f'return-local-variant-{variant}')
            body = [decl('t'), stmt('t.n = v;')]
            if variant == 0:
                body.append(stmt('return t;', fixed=True))
            elif variant == 1:
                body[1:1] = [decl('u'), stmt('u.n = 0 - v;')]
                body.append(stmt('return t;', fixed=True))
            elif variant == 2:
                body.append(Item(['{', [stmt(f'{cls.name} u = t;'), stmt('u.n = v + 1;'),
                                        stmt('return u;', fixed=True)], '}'], 'block', fixed=True))
            else:
                body += [stmt(f'{cls.name} u = t;'), stmt('return u;', fixed=True)]
            return Item([f'{cls.name} {name}(int v) {{', body, '}'], 'func', name=name)
        return self.helper(('mk', cls.name), f'mk{cls.name}', build)

    def auto_factory(self, cls):
        """Returns an automatic pointer object: ownership moves to the calling block."""
        if cls is self.m:
            return 'mkp'

        def build(name):
            body = [stmt(f'{cls.name} * p{self.args(cls, "v")};'), stmt('p.n = v;')]
            if self.chance(0.4):
                body.insert(1, stmt(f'{cls.name} * o{self.args(cls, "v + 1")};'))
            body.append(stmt('return p;', fixed=True))
            return Item([f'{cls.name} * {name}(int v) {{', body, '}'], 'func', name=name)
        return self.helper(('mkp', cls.name), f'mkp{cls.name}', build)

    def manual_factory(self, cls):
        def build(name):
            body = [stmt(f'{cls.name} * p = new {cls.name}{self.args(cls, "v")};'), stmt('p.n = v;'),
                    stmt('return p;', fixed=True)]
            return Item([f'{cls.name} * {name}(int v) {{', body, '}'], 'func', name=name)
        return self.helper(('mkn', cls.name), f'mkn{cls.name}', build)

    def use_helper(self, cls):
        """By-value parameter: a copy destroyed when the callee returns."""
        def build(name):
            body = [stmt('event(x.n);'), stmt('x.n = x.n + 1;'), stmt('return x.n;', fixed=True)]
            return Item([f'int {name}({cls.name} x) {{', body, '}'], 'func', name=name)
        return self.helper(('use', cls.name), f'use{cls.name}', build)

    def peek_helper(self, cls):
        def build(name):
            body = [Item(['if (x == null) {', [stmt('return -1;', fixed=True)], '}'], 'if'),
                    stmt('return x.n;', fixed=True)]
            return Item([f'int {name}({cls.name} (*) x) {{', body, '}'], 'func', name=name)
        return self.helper(('peek', cls.name), f'peek{cls.name}', build)

    def pass_helper(self, cls):
        """Returns its by-value parameter (moved, not copied)."""
        def build(name):
            body = [stmt('x.n = x.n + 1;'), stmt('return x;', fixed=True)]
            return Item([f'{cls.name} {name}({cls.name} x) {{', body, '}'], 'func', name=name)
        return self.helper(('pass', cls.name), f'pass{cls.name}', build)

    def field_getter(self, owner, field, field_cls):
        """Returns a field: copied before it is handed out."""
        def build(name):
            body = [stmt(f'return h.{field};', fixed=True)]
            return Item([f'{field_cls.name} {name}({owner.name} (*) h) {{', body, '}'], 'func', name=name)
        return self.helper(('get', owner.name, field), f'get{owner.name}{field}', build)

    def recursion_helper(self, cls):
        def build(name):
            body = [stmt(self.literal_decl(cls, 't', 'd')), stmt('t.n = d;'),
                    Item(['if (d <= 0) {', [stmt('probe(d);'), stmt('return t.n;', fixed=True)], '}'], 'if'),
                    stmt(f'return {name}(d - 1) + t.n;', fixed=True)]
            return Item([f'int {name}(int d) {{', body, '}'], 'func', name=name)
        return self.helper(('rec', cls.name), f'rec{cls.name}', build)

    def self_method(self, cls):
        if not cls.has_self:
            cls.has_self = True
            cls.members.append(stmt(f'{cls.name} * self(){{ return this; }}'))

    # -- classes
    def make_class(self, index):
        rng, level = self.rng, index + 1
        bases = [cls for cls in self.classes if cls.depth < 3]
        base = None
        if self.chance(0.55):
            base = rng.choices(bases, [0.15 if cls is self.bad else 1.0 if cls is self.m else 2.5 for cls in bases])[0]
        if base is None:
            ctor = rng.choice(('int', 'int', 'int', 'zero', 'none'))
        elif base.ctor == 'int':
            ctor = 'int'
        else:
            ctor = rng.choice(('int', 'zero', 'none'))
        cls = Cls(f'K{index}', level, base, ctor)
        members = []
        cls.members = members
        if base is None:
            cls.own_ints.append('n')
            members.append(stmt('int n;', fixed=True))
        else:
            self.feature(f'inheritance-depth-{cls.depth}')
            if base is self.bad:
                self.feature('failing-base-destructor')
            if self.chance(0.6):
                cls.own_ints.append(f'm{index}')
                members.append(stmt(f'int m{index};', name=f'm{index}'))
        for slot in range(rng.choice((0, 0, 1, 1, 1, 2))):
            field_cls = self.some_class(level, bad=0.08)
            name = f'lf{index}{slot}'
            self.feature('literal-field')
            if field_cls is self.bad:
                self.feature('failing-literal-field')
            if self.chance(0.4):
                text = f'{field_cls.name} {name} = {self.factory(field_cls)}({self.small() + 1});'
                self.feature('field-initialized-from-temporary')
            elif field_cls.ctor == 'int':
                text = f'{field_cls.name} {name}({self.small() + 1});'
            else:
                text = f'{field_cls.name} {name};'
            cls.own_lits.append((name, field_cls))
            members.append(stmt(text, name=name))
        if self.chance(0.5):
            target = rng.choice([item for item in self.classes if item is not self.bad] + [cls])
            name = f'pf{index}'
            cls.own_ptrs.append((name, target))
            members.append(stmt(f'{target.name} * {name};', name=name))
        if base is None and self.chance(0.6):
            cls.who = True
            members.append(stmt(f'virtual int who(){{ return {level * 10 + 1}; }}'))
        elif base is not None and base.who and self.chance(0.8):
            self.feature('virtual-override')
            body = (f'return {level * 10 + 2};' if self.chance(0.5) else f'return {base.name}::who() + {level * 10};')
            members.append(stmt(f'override int who(){{ {body} }}'))
        if ctor in ('int', 'zero'):
            param = 'v' if ctor == 'int' else None
            body = []
            if base is None:
                body.append(stmt('n = v;' if param else f'n = {self.small()};'))
            elif cls.own_ints and param:
                body.append(stmt(f'{cls.own_ints[0]} = v;'))
            for _ in range(rng.choice((0, 0, 1, 1, 2))):
                body.extend(self.member_statement(cls, True, param))
            init = ''
            if base is not None and base.ctor == 'int':
                argument = 'v'
                choice = rng.random()
                if choice < 0.2:
                    temp_cls = self.some_class(level, bad=0)
                    argument = f'{self.factory(temp_cls)}(v).n'
                    self.feature('temporary-in-base-initializer')
                elif choice < 0.6:
                    argument = f'v + {self.small()}'
                init = f' : {base.name}({argument})'
            header = f'{cls.name}(int v){init} {{' if param else f'{cls.name}(){init} {{'
            members.append(Item([header, body, '}'], 'member'))
        else:
            self.feature('synthesized-constructor')
        if self.chance(0.65):
            cls.explicit_dtor = True
            self.feature('explicit-destructor')
            body = []
            for _ in range(rng.choice((1, 1, 2, 2, 3))):
                body.extend(self.member_statement(cls, False, None))
            members.append(Item([f'~{cls.name}() {{', body, '}'], 'member'))
        elif cls.own_lits:
            self.feature('synthesized-destructor')
        elif base is not None:
            self.feature('inherited-destructor')
        heading = f'class {cls.name}' + (f' : {base.name}' if base else '') + ' {'
        self.classes.append(cls)
        return Item([heading, members, '}'], 'class', name=cls.name)

    def member_statement(self, cls, constructor, param):
        """One statement of a constructor or destructor body; may only use lower-level classes."""
        rng, level = self.rng, cls.level
        lits, ptrs = cls.lits(), cls.ptrs()
        where = 'constructor' if constructor else 'destructor'

        def tagged():
            tag = level * 100 if constructor else -level * 100
            return [stmt(f'event({tag} + n);' if constructor else f'event({tag} - n);')]

        def probe():
            self.feature(f'probe-in-{where}')
            return [stmt(rng.choice(('probe(n);', 'event(n);')))]

        def nested():
            other = self.some_class(level)
            name = self.fresh('t')
            self.feature(f'object-created-in-{where}')
            decl = (self.literal_decl(other, name, 'n') if self.chance(0.6)
                    else f'{other.name} * {name}{self.args(other, "n")};')
            return [Item(['{', [stmt(decl, name=name), stmt(f'event({name}.n);')], '}'], 'block')]

        def temporary():
            other = self.some_class(level)
            self.feature(f'temporary-in-{where}')
            return [stmt(f'event({self.factory(other)}(n).n);')]

        def raw():
            name = self.fresh('t')
            tail = rng.choice((f'mem_send_up({name});', f'event(mem_free({name}) ? 1 : 0);', f'make_free({name});',
                               f'mem_get({name}) = n;', None))
            self.feature(f'raw-memory-in-{where}')
            items = [stmt(f'address {name} = alloc({rng.randint(1, 2)});', name=name)]
            if tail:
                items.append(stmt(tail))
            return items

        def failure():
            kind = rng.randrange(4)
            if kind == 0:
                name = self.fresh('z')
                body = [stmt(f'int {name} = 0;', name=name), stmt(f'event(1 / {name});')]
            elif kind == 1:
                body = [stmt('event(2147483647 + n);')]
            elif kind == 2:
                body = [stmt('fail(n);')]
            else:
                name = self.fresh('t')
                body = [stmt(f'Bad {name}(n);')]
            self.feature(f'failure-in-{where}')
            condition = rng.choice((f'n == {self.small()}', f'n == {self.small()}', f'n > {rng.randint(3, 6)}',
                                    'n < 0'))
            return [Item([f'if ({condition}) {{', body, '}'], 'if')]

        def early_return():
            self.feature(f'early-return-in-{where}')
            return [Item([f'if (n == {self.small()}) {{', [stmt('return;')], '}'], 'if')]

        def reentry():
            self.feature(f'reentry-in-{where}')
            return [stmt(f'event(reenter({REENTRY_ID}, n));')]

        def delete_field():
            name = rng.choice(ptrs)[0]
            self.feature('delete-pointer-field-in-destructor')
            return [Item([f'if ({name} != null) {{', [stmt(f'delete {name};')], '}'], 'if')]

        def delete_this():
            name = self.fresh('t')
            self.feature('delete-this-in-destructor')
            return [Item([f'if (n == {self.small()}) {{', [stmt(f'{cls.name} * {name} = this;', name=name),
                                                       stmt(f'delete {name};')], '}'], 'if')]

        return self.pick([
            (6, tagged),
            (2, probe),
            (2 if cls.who else 0, lambda: [stmt('event(this.who());')]),
            (2 if lits else 0, lambda: [stmt(f'event({rng.choice(lits)[0]}.n);')]),
            (1 if lits else 0, lambda: [stmt(f'{rng.choice(lits)[0]}.n = n + {self.small()};')]),
            (2 if ptrs and not constructor else 0, delete_field),
            (3, nested),
            (2, temporary),
            (1.5, raw),
            (2, failure),
            (1, early_return),
            (2.5 if self.has_re and self.re_level <= level else 0, reentry),
            (0.5, lambda: [stmt('print(n);')]),
            (0.4 if not constructor else 0, delete_this),
            (1 if param else 0, lambda: [stmt(f'event({param});')]),
        ])

    # -- expressions
    def live_objects(self, fn, allow_null=False):
        return [var for var in fn.visible() if var.kind in ('lit', 'auto', 'manual', 'ptr') and not var.dead
                and (allow_null or not var.maybe_null)]

    def pick_var(self, fn, kind, cls=None):
        pool = [var for var in fn.visible() if var.kind == kind and (cls is None or var.cls is cls) and not var.dead]
        return self.rng.choice(pool).name if pool else None

    def member_read(self, fn):
        objects = self.live_objects(fn)
        if not objects:
            return None
        var = self.rng.choice(objects)
        lits = var.cls.lits()
        if lits and self.chance(0.3):
            return f'{var.name}.{self.rng.choice(lits)[0]}.n'
        return f'{var.name}.{self.rng.choice(var.cls.ints())}'

    def usable(self, fn, cls):
        return cls is not None and cls.level < fn.level

    def int_atom(self, fn):
        return self.pick([
            (3, lambda: str(self.small())),
            (3, lambda: self.pick_var(fn, 'int')),
            (4, lambda: self.member_read(fn)),
            (0.6 if self.g0 else 0, lambda: 'g0'),
        ])

    def int_expr(self, fn, depth=0):
        rng = self.rng
        if depth >= 2:
            return self.int_atom(fn)

        def temporary():
            cls = self.some_class(fn.level, bad=0.05)
            if cls is None:
                return None
            self.feature('statement-temporary')
            return f'{self.factory(cls)}({self.int_expr(fn, depth + 1)}).n'

        def who():
            pool = [var for var in self.live_objects(fn) if var.cls.who]
            if not pool:
                return None
            self.feature('virtual-call')
            return f'{rng.choice(pool).name}.who()'

        def use():
            pool = [var for var in fn.visible() if var.kind == 'lit' and not var.dead]
            if pool and self.chance(0.6):
                var = rng.choice(pool)
                self.feature('by-value-argument-copy')
                return f'{self.use_helper(var.cls)}({var.name})'
            cls = self.some_class(fn.level, bad=0.05)
            if cls is None:
                return None
            self.feature('temporary-by-value-argument')
            return f'{self.use_helper(cls)}({self.factory(cls)}({self.int_expr(fn, depth + 1)}))'

        def peek():
            pool = self.live_objects(fn, allow_null=True)
            if not pool:
                return None
            var = rng.choice(pool)
            self.feature('borrowed-argument')
            return f'{self.peek_helper(rng.choice(var.cls.ancestors()))}({var.name})'

        def recursion():
            cls = self.some_class(fn.level, bad=0)
            if cls is None:
                return None
            self.feature('recursion')
            return f'{self.recursion_helper(cls)}({rng.randint(0, 4)})'

        def returned_temporary():
            if fn.level < INF or not fn.rets or not self.ret_fns:
                return None
            name, cls, param = rng.choice(self.ret_fns)
            self.feature('returned-object-temporary')
            return f'{self.call_ret(fn, name, cls, param, depth + 1)}.n'

        return self.pick([
            (6, lambda: self.int_atom(fn)),
            (3, temporary),
            (1.5, who),
            (1.5, use),
            (1, peek),
            (3, lambda: f'({self.int_expr(fn, depth + 1)} {rng.choice("+-")} {self.int_expr(fn, depth + 1)})'),
            (1, lambda: f'({self.int_expr(fn, depth + 1)} * {rng.randint(2, 3)})'),
            (0.7, lambda: f'probe({self.int_expr(fn, depth + 1)})'),
            (1 if fn.allow_re and self.has_re else 0,
             lambda: f'reenter({REENTRY_ID}, {self.int_expr(fn, depth + 1)})'),
            (1 if fn.fns else 0, lambda: f'{rng.choice(fn.fns)}({self.int_expr(fn, depth + 1)})'),
            (0.8, lambda: f'({self.condition(fn, depth + 1)} ? {self.int_expr(fn, depth + 1)} : '
                          f'{self.int_expr(fn, depth + 1)})'),
            (0.6, recursion),
            (0.8, returned_temporary),
        ])

    def condition(self, fn, depth=0):
        rng = self.rng

        def null_check():
            pool = [var for var in fn.visible() if var.kind in ('auto', 'manual', 'ptr') and not var.dead]
            if not pool:
                return None
            return f'{rng.choice(pool).name} {rng.choice(("!=", "=="))} null'

        return self.pick([
            (5, lambda: f'{self.int_expr(fn, depth + 1)} {rng.choice(("<", "<=", "==", "!=", ">", ">="))} '
                        f'{self.small()}'),
            (1, null_check),
            (1 if depth < 1 else 0, lambda: f'({self.condition(fn, depth + 1)}) {rng.choice(("&&", "||"))} '
                                            f'({self.condition(fn, depth + 1)})'),
            (0.5 if depth < 1 else 0, lambda: f'!({self.condition(fn, depth + 1)})'),
        ])

    def call_ret(self, fn, name, cls, param, depth):
        if param:
            return f'{name}({self.int_expr(fn, depth)}, {self.object_value(fn, cls, depth)})'
        return f'{name}({self.int_expr(fn, depth)})'

    def object_value(self, fn, cls, depth=0):
        """An expression of the literal class cls."""
        rng = self.rng

        def field_copy():
            pool = [(var, name) for var in self.live_objects(fn) for name, field_cls in var.cls.lits()
                    if field_cls is cls]
            if not pool:
                return None
            var, name = rng.choice(pool)
            if self.chance(0.5):
                self.feature('field-copy')
                return f'{var.name}.{name}'
            self.feature('returned-field-copy')
            return f'{self.field_getter(var.cls, name, cls)}({var.name})'

        def passed():
            var = self.pick_var(fn, 'lit', cls)
            if var is None:
                return None
            self.feature('returned-parameter')
            return f'{self.pass_helper(cls)}({var})'

        def returned():
            if fn.level < INF or not fn.rets:
                return None
            pool = [item for item in self.ret_fns if item[1] is cls]
            if not pool:
                return None
            name, _, param = rng.choice(pool)
            self.feature('returned-object-from-random-body')
            return self.call_ret(fn, name, cls, param, depth + 1)

        return self.pick([
            (3, lambda: f'{self.factory(cls)}({self.int_expr(fn, depth + 1)})'),
            (2, lambda: self.pick_var(fn, 'lit', cls)),
            (1.5 if depth < 1 else 0, passed),
            (2.5, field_copy),
            (1.5 if depth < 1 else 0, returned),
        ])

    def pointer_value(self, fn, cls):
        """An expression for a C * return: automatic, manual, alias or null."""
        rng = self.rng
        pool = [var for var in fn.visible() if var.kind in ('auto', 'manual', 'ptr') and not var.dead
                and cls in var.cls.ancestors()]
        return self.pick([
            (3, lambda: rng.choice(pool).name if pool else None),
            (1, lambda: f'new {cls.name}{self.args(cls, self.int_expr(fn, 1))}'),
            (1, lambda: f'{self.auto_factory(cls)}({self.int_expr(fn, 1)})'),
            (0.5, lambda: 'null'),
        ])

    # -- statements
    def jump(self, fn):
        def returning():
            kind = fn.ret[0]
            self.feature('return-from-nested-scope')
            if kind == 'int':
                return stmt(f'return {self.int_expr(fn)};')
            if kind == 'void':
                return stmt('return;')
            if kind == 'obj':
                return stmt(f'return {self.object_value(fn, fn.ret[1])};')
            return stmt(f'return {self.pointer_value(fn, fn.ret[1])};')

        def jumping(word):
            self.feature(word)
            return stmt(word + ';')

        return self.pick([
            (2, returning),
            (2 if fn.breakable else 0, lambda: jumping('break')),
            (2 if fn.loop else 0, lambda: jumping('continue')),
        ])

    def block(self, fn, depth, allow_term, most, jump_chance=0.0):
        items, terminated = [], False
        fn.scopes.append([])
        for _ in range(self.rng.randint(1, most)):
            if fn.budget <= 0:
                break
            produced, ended = self.statement(fn, depth, allow_term)
            items.extend(produced)
            if ended:
                terminated = True
                break
        if not terminated and allow_term and self.chance(jump_chance):
            items.append(self.jump(fn))
            terminated = True
        fn.scopes.pop()
        return items, terminated

    def statement(self, fn, depth, allow_term):
        rng = self.rng
        fn.budget -= 1
        nested = depth < self.MAX_DEPTH and fn.budget > 1
        ended = []

        def usable_class(bad=0.1):
            return self.some_class(fn.level, bad=bad)

        def decl_int():
            name = self.fresh('x')
            text = f'int {name} = {self.int_expr(fn)};'
            fn.declare(Var(name, 'int', loop=fn.loop))
            return [stmt(text, name=name)]

        def decl_literal():
            cls = usable_class(bad=0.06)
            if cls is None:
                return None
            name = self.fresh('a')
            if cls is self.bad:
                self.feature('failing-literal-local')
            if self.chance(0.45):
                text = self.literal_decl(cls, name, self.int_expr(fn))
            else:
                text = f'{cls.name} {name} = {self.object_value(fn, cls)};'
            fn.declare(Var(name, 'lit', cls, fn.loop))
            self.feature('literal-object')
            return [stmt(text, name=name)]

        def decl_automatic():
            cls = usable_class(bad=0.1)
            if cls is None:
                return None
            name = self.fresh('p')
            if fn.level >= INF and fn.rets and self.retp_fns and self.chance(0.2):
                function, ret_cls = rng.choice(self.retp_fns)
                text = f'{ret_cls.name} * {name} = {function}({self.int_expr(fn)});'
                fn.declare(Var(name, 'ptr', ret_cls, fn.loop, maybe_null=True))
                self.feature('returned-pointer')
                return [stmt(text, name=name)]
            if self.chance(0.6):
                text = f'{cls.name} * {name}{self.args(cls, self.int_expr(fn))};'
                self.feature('automatic-object')
            else:
                text = f'{cls.name} * {name} = {self.auto_factory(cls)}({self.int_expr(fn)});'
                self.feature('returned-automatic-object')
            fn.declare(Var(name, 'auto', cls, fn.loop))
            return [stmt(text, name=name)]

        def decl_manual():
            cls = usable_class(bad=0.1)
            if cls is None:
                return None
            name = self.fresh('h')
            static = rng.choice(cls.ancestors())
            if self.chance(0.7):
                text = f'{static.name} * {name} = new {cls.name}{self.args(cls, self.int_expr(fn))};'
            else:
                text = f'{static.name} * {name} = {self.manual_factory(cls)}({self.int_expr(fn)});'
            if static is not cls:
                self.feature('base-pointer-to-derived-object')
            fn.declare(Var(name, 'manual', static, fn.loop))
            self.feature('manual-object')
            return [stmt(text, name=name)]

        def delete():
            pool = [var for var in fn.visible() if not var.dead and var.loop == fn.loop and
                    (var.kind == 'manual' or (var.kind == 'ptr' and var.target and var.target.kind == 'manual'
                                              and not var.target.dead and var.target.loop == fn.loop))]
            if pool and self.chance(0.9):
                var = rng.choice(pool)
                var.dead = True
                if var.target:
                    var.target.dead = True
                self.feature('delete')
                return [stmt(f'delete {var.name};')]
            wrong = [var for var in fn.visible() if var.kind in ('auto', 'manual', 'ptr')]
            if not wrong:
                return None
            var = rng.choice(wrong)
            self.feature('delete-error-or-null')
            return [stmt(f'delete {var.name};')]

        def alias():
            pool = [var for var in fn.visible() if var.kind in ('auto', 'manual', 'ptr') and not var.dead]
            literals = [var for var in fn.visible() if var.kind == 'lit' and not var.dead and not var.cls.prelude
                        and not var.fixed]
            name = self.fresh('q')
            if literals and self.chance(0.4):
                var = rng.choice(literals)
                self.self_method(var.cls)
                fn.declare(Var(name, 'ptr', var.cls, fn.loop, target=var))
                self.feature('literal-this-alias')
                return [stmt(f'{var.cls.name} * {name} = {var.name}.self();', name=name)]
            if not pool:
                cls = usable_class(bad=0)
                if cls is None:
                    return None
                fn.declare(Var(name, 'ptr', cls, fn.loop, maybe_null=True))
                return [stmt(f'{cls.name} * {name} = null;', name=name)]
            var = rng.choice(pool)
            static = rng.choice(var.cls.ancestors())
            fn.declare(Var(name, 'ptr', static, fn.loop, maybe_null=var.maybe_null, target=var))
            self.feature('pointer-alias')
            return [stmt(f'{static.name} * {name} = {var.name};', name=name)]

        def assign_int():
            pool = [var for var in fn.visible() if var.kind == 'int' and not var.fixed]
            if not pool:
                return None
            var = rng.choice(pool)
            return [stmt(f'{var.name} = {var.name} + {self.int_expr(fn)};' if self.chance(0.5)
                         else f'{var.name} = {self.int_expr(fn)};')]

        def assign_object():
            objects = self.live_objects(fn)
            literals = [var for var in objects if var.kind == 'lit' and not var.fixed]
            with_fields = [(var, name, cls) for var in objects for name, cls in var.cls.lits()]
            with_pointers = [(var, name, cls) for var in objects for name, cls in var.cls.ptrs()]
            writable = [var for var in objects if not var.fixed]

            def literal():
                var = rng.choice(literals)
                self.feature('literal-assignment')
                return [stmt(f'{var.name} = {self.object_value(fn, var.cls)};')]

            def field():
                var, name, cls = rng.choice(with_fields)
                self.feature('field-assignment')
                return [stmt(f'{var.name}.{name} = {self.object_value(fn, cls)};')]

            def link():
                # Pointer fields do not own; a destructor may delete what they point to.
                var, name, cls = rng.choice(with_pointers)
                targets = [item for item in fn.visible() if item.kind in ('auto', 'manual', 'ptr')
                           and not item.dead and cls in item.cls.ancestors()]
                self.feature('pointer-field-link')
                value = rng.choice(targets).name if targets and self.chance(0.85) else 'null'
                return [stmt(f'{var.name}.{name} = {value};')]

            def member():
                var = rng.choice(writable)
                return [stmt(f'{var.name}.{rng.choice(var.cls.ints())} = {self.int_expr(fn)};')]

            return self.pick([(3 if literals else 0, literal), (2.5 if with_fields else 0, field),
                              (3 if with_pointers else 0, link), (1 if writable else 0, member)])

        def reassign_pointer():
            pool = [var for var in fn.visible() if var.kind in ('manual', 'ptr', 'auto') and var.loop == fn.loop]
            if not pool:
                return None
            var = rng.choice(pool)
            targets = [item for item in fn.visible() if item.kind in ('auto', 'manual', 'ptr') and item is not var
                       and not item.dead and var.cls in item.cls.ancestors()]
            if var.kind == 'auto':
                if not targets:
                    return None
                # Cleanup stays bound to the creation address, not to the reassigned variable.
                target = rng.choice(targets)
                var.kind, var.target, var.maybe_null = 'ptr', target, target.maybe_null
                self.feature('automatic-variable-reassigned')
                return [stmt(f'{var.name} = {target.name};')]
            if var.kind == 'manual' or not targets or self.chance(0.3):
                derived = [cls for cls in self.classes if var.cls in cls.ancestors() and cls.level < fn.level]
                if not derived:
                    return None
                cls = rng.choice(derived)
                var.dead = False
                var.maybe_null = False
                if var.kind == 'ptr':
                    var.kind, var.target = 'manual', None
                self.feature('pointer-reassigned-to-new-object')
                return [stmt(f'{var.name} = new {cls.name}{self.args(cls, self.int_expr(fn))};')]
            target = rng.choice(targets)
            var.target, var.dead, var.maybe_null = target, False, target.maybe_null
            return [stmt(f'{var.name} = {target.name};')]

        def failure():
            kind = rng.randrange(5)
            self.feature('failure-in-function-body')
            if kind == 0:
                return [Item([f'if ({self.condition(fn)}) {{', [stmt(f'fail({self.small()});')], '}'], 'if')]
            if kind == 1:
                name = self.fresh('z')
                text = f'int {name} = {self.int_expr(fn)};'
                fn.declare(Var(name, 'int', loop=fn.loop))
                return [stmt(text, name=name), stmt(f'event({self.small() + 1} / {name});')]
            if kind == 2:
                header = f'if ({self.condition(fn)}) {{'
                return [Item([header, [stmt(f'event(2147483647 + {self.int_expr(fn)});')], '}'], 'if')]
            if kind == 3 and self.bad.level < fn.level:
                name = self.fresh('a')
                text = f'Bad {name}({self.int_expr(fn)});'
                fn.declare(Var(name, 'lit', self.bad, fn.loop))
                self.feature('failing-literal-local')
                return [stmt(text, name=name)]
            return [Item([f'if ({self.condition(fn)}) {{', [stmt(f'event(1 / {self.small() * 0});')], '}'], 'if')]

        def raw():
            pool = [var for var in fn.visible() if var.kind == 'addr']
            if pool and self.chance(0.55):
                var = rng.choice(pool)
                self.feature('raw-memory-operation')
                return [stmt(rng.choice((f'mem_send_up({var.name});', f'event(mem_free({var.name}) ? 1 : 0);',
                                         f'make_free({var.name});', f'mem_get({var.name}) = {self.int_expr(fn)};')))]
            name = self.fresh('r')
            fn.declare(Var(name, 'addr', loop=fn.loop))
            self.feature('raw-allocation')
            items = [stmt(f'address {name} = alloc({rng.randint(1, 3)});', name=name)]
            if self.chance(0.6):
                items.append(stmt(rng.choice((f'mem_send_up({name});', f'mem_get({name}) = {self.int_expr(fn)};'))))
            return items

        def escape():
            pool = [cls for cls in self.classes if not cls.prelude and cls.level < fn.level]
            if not pool or not nested:
                return None
            cls = rng.choice(pool)
            self.self_method(cls)
            outer, inner = self.fresh('e'), self.fresh('a')
            fn.declare(Var(outer, 'ptr', cls, fn.loop, dead=True))
            self.feature('expired-this-address')
            use = stmt(f'event({outer}.n);')
            tail = Item([f'if ({self.condition(fn)}) {{', [use], '}'], 'if') if self.chance(0.6) else use
            return [stmt(f'{cls.name} * {outer} = null;', name=outer),
                    Item(['{', [stmt(self.literal_decl(cls, inner, self.small()), name=inner),
                                stmt(f'{outer} = {inner}.self();'), stmt(f'event({outer}.n);')], '}'], 'block'),
                    tail]

        def global_operation():
            options = []
            if self.g0:
                options += [(1, lambda: [stmt(f'g0 = g0 + {self.int_expr(fn)};')]),
                            (1, lambda: [stmt(f'setg(0, {self.int_expr(fn)});')])]
            if self.gp and self.gp.level < fn.level:
                gp = self.gp
                options += [(1, lambda: [stmt(f'gp = new {gp.name}{self.args(gp, self.int_expr(fn))};')]),
                            (1, lambda: [Item(['if (gp != null) {', [stmt('delete gp;'), stmt('gp = null;')], '}'],
                                              'if')]),
                            (1, lambda: [Item(['if (gp != null) {', [stmt('event(gp.n);')], '}'], 'if')])]
            if not options:
                return None
            self.feature('global-state')
            return self.pick(options)

        def guarded():
            pool = [var for var in fn.visible() if var.kind in ('ptr', 'manual', 'auto') and not var.dead]
            if not pool:
                return None
            var = rng.choice(pool)
            return [Item([f'if ({var.name} != null) {{', [stmt(f'event({var.name}.{rng.choice(var.cls.ints())});')],
                          '}'], 'if')]

        def loop_exit():
            # break leaves the innermost loop or switch, continue the innermost loop.
            if not fn.breakable:
                return None
            body = self.block(fn, depth + 1, False, 2)[0] if nested and self.chance(0.4) else []
            word = 'continue' if fn.loop and self.chance(0.45) else 'break'
            self.feature(word)
            return [Item([f'if ({self.condition(fn)}) {{', body + [stmt(word + ';')], '}'], 'if')]

        def plain_block():
            items, terminated = self.block(fn, depth + 1, allow_term, 4, jump_chance=0.12)
            self.feature('nested-block')
            if terminated:
                ended.append(True)
            return [Item(['{', items, '}'], 'block')]

        def if_statement():
            then, terminated = self.block(fn, depth + 1, True, 3, jump_chance=0.35)
            parts = [f'if ({self.condition(fn)}) {{', then]
            if self.chance(0.4):
                otherwise, _ = self.block(fn, depth + 1, not terminated, 3, jump_chance=0.3)
                parts += ['} else {', otherwise]
            return [Item(parts + ['}'], 'if')]

        def for_statement():
            name = self.fresh('i')
            count = rng.randint(1, 3)
            limit = str(count)
            if self.chance(0.25):
                cls = usable_class(bad=0)
                if cls is not None:
                    limit = f'{self.factory(cls)}({count}).n'
                    self.feature('temporary-in-loop-condition')
            header = f'for (int {name} = 0; {name} < {limit}; {name}++) {{'
            counter = Var(name, 'int', loop=fn.loop + 1, fixed=True)
            cls = usable_class(bad=0) if self.chance(0.2) else None
            if cls is not None:
                # The for-init object lives in the for scope across all iterations.
                name = self.fresh('a')
                init = self.literal_decl(cls, name, self.small())[:-1]
                header = f'for ({init}; {name}.n < {count}; {name}.n = {name}.n + 1) {{'
                counter = Var(name, 'lit', cls, fn.loop + 1, fixed=True)
                self.feature('object-in-for-initializer')
            fn.scopes.append([counter])
            fn.loop += 1
            fn.breakable += 1
            body, _ = self.block(fn, depth + 1, False, 4)
            fn.loop -= 1
            fn.breakable -= 1
            fn.scopes.pop()
            self.feature('for-loop')
            return [Item([header, body, '}'], 'loop')]

        def while_statement():
            name = self.fresh('w')
            count = rng.randint(1, 3)
            condition = f'{name} < {count}'
            if self.chance(0.25):
                cls = usable_class(bad=0)
                if cls is not None:
                    condition += f' && {self.factory(cls)}({name}).n >= 0'
                    self.feature('temporary-in-loop-condition')
            counter = Var(name, 'int', loop=fn.loop, fixed=True)
            fn.loop += 1
            fn.breakable += 1
            fn.scopes.append([])
            body, _ = self.block(fn, depth + 1, False, 4)
            fn.scopes.pop()
            fn.loop -= 1
            fn.breakable -= 1
            fn.declare(counter)
            self.feature('while-loop')
            return [stmt(f'int {name} = 0;', name=name),
                    Item([f'while ({condition}) {{', [stmt(f'{name} = {name} + 1;', fixed=True)] + body, '}'], 'loop')]

        def switch_statement():
            labels = rng.choice(((['case 0:'], ['case 1:'], ['default:']), (['case 0:', 'case 2:'], ['default:']),
                                 (['case 1:'], ['case 0:', 'case -1:']), (['default:'], ['case 1:'])))
            parts = [f'switch ({self.int_expr(fn)} % 3) {{']
            fn.breakable += 1
            for index, section in enumerate(labels):
                body, _ = self.block(fn, depth + 1, False, 3)
                if index < len(labels) - 1 or self.chance(0.5):
                    body.append(stmt('break;', fixed=True))
                parts += list(section)
                parts.append(body)
            fn.breakable -= 1
            self.feature('switch')
            return [Item(parts + ['}'], 'switch')]

        options = [
            (2, decl_int),
            (6, decl_literal),
            (4, decl_automatic),
            (3, decl_manual),
            (6, delete),
            (1.5, alias),
            (5, lambda: [stmt(f'event({self.int_expr(fn)});')]),
            (1.5, lambda: [stmt(f'probe({self.int_expr(fn)});')]),
            (0.4, lambda: [stmt(f'print({self.int_expr(fn)});')]),
            (1.5, assign_int),
            (4, assign_object),
            (1, reassign_pointer),
            (1.2, failure),
            (1.5 if fn.allow_re and self.has_re else 0,
             lambda: [stmt(f'event(reenter({REENTRY_ID}, {self.int_expr(fn)}));')]),
            (2, raw),
            (1, escape),
            (1, global_operation),
            (0.8, guarded),
            (3 if fn.breakable else 0, loop_exit),
            (3 if nested else 0, plain_block),
            (4 if nested else 0, if_statement),
            (2.5 if nested else 0, for_statement),
            (1.5 if nested else 0, while_statement),
            (1.2 if nested else 0, switch_statement),
        ]
        items = self.pick(options)
        return items, bool(ended)

    def function(self, header, fn, most, final, kind='func', name=None, fixed=False):
        body, terminated = self.block(fn, 0, True, most, jump_chance=0.04)
        if not terminated and final is not None:
            body.append(stmt(final(), fixed=True))
        return Item([header, body, '}'], kind, fixed=fixed, name=name)

    # -- whole program
    def program(self):
        rng = self.rng
        count = rng.randint(1, 4)
        self.has_re = self.chance(0.55)
        # Destructors of classes at or above re_level may re-enter; the target only uses lower classes.
        self.re_level = rng.randint(1, min(2, count + 1)) if self.chance(0.7) else rng.randint(1, count + 1)
        options = []
        if self.chance(0.12):
            options = ['--call-depth', str(rng.randint(3, 10))]
            self.feature('small-call-depth')
        class_items = [self.make_class(index) for index in range(count)]
        global_items = []
        if self.chance(0.35):
            self.g0 = True
            cls = self.some_class(INF, bad=0)
            global_items.append(stmt('int g0;' if self.chance(0.5) else f'int g0 = {self.factory(cls)}(2).n;',
                                     fixed=True))
        if self.chance(0.3):
            self.gp = rng.choice([cls for cls in self.classes if cls is not self.bad])
            text = (f'{self.gp.name} * gp = null;' if self.chance(0.5) else
                    f'{self.gp.name} * gp = new {self.gp.name}{self.args(self.gp, self.small())};')
            global_items.append(stmt(text, fixed=True))
            self.feature('global-object-pointer')
        functions = []
        if self.has_re:
            fn = Fn(self.re_level, ('int',), rng.randint(2, 6), False, rets=False)
            target = self.some_class(self.re_level, bad=0)
            item = self.function(f'int reent(int v):0f01 {{', fn, 4, lambda: f'return v + {self.int_expr(fn)};',
                                 name='reent')
            # The re-entered function always creates and destroys at least one object.
            first = (self.literal_decl(target, 'ra', 'v') if self.chance(0.6)
                     else f'{target.name} * ra{self.args(target, "v")};')
            item.parts[1][0:0] = [stmt(first, name='ra'), stmt('event(ra.n);')]
            functions.append(item)
            self.feature('host-reentry-target')
        for index in range(rng.choice((0, 0, 1, 2))):
            name = f'fn{index}'
            fn = Fn(INF, ('int',), rng.randint(3, 8), self.has_re, fns=self.int_fns, rets=False,
                    params=[Var('v', 'int')])
            functions.append(self.function(f'int {name}(int v) {{', fn, 5, lambda: f'return {self.int_expr(fn)};',
                                           name=name))
            self.int_fns.append(name)
        for index in range(rng.choice((0, 1, 1, 2))):
            cls = self.some_class(INF, bad=0)
            name = f'ret{index}'
            param = self.chance(0.4)
            params = [Var('v', 'int')] + ([Var('x', 'lit', cls)] if param else [])
            fn = Fn(INF, ('obj', cls), rng.randint(2, 6), self.has_re, fns=self.int_fns, params=params, rets=False)
            signature = f'int v, {cls.name} x' if param else 'int v'
            functions.append(self.function(f'{cls.name} {name}({signature}) {{', fn, 4,
                                           lambda: f'return {self.object_value(fn, cls)};', name=name))
            self.ret_fns.append((name, cls, param))
            self.feature('object-returning-function')
        if self.chance(0.4):
            cls = self.some_class(INF, bad=0)
            name = 'retp0'
            fn = Fn(INF, ('ptr', cls), rng.randint(2, 5), self.has_re, fns=self.int_fns, params=[Var('v', 'int')],
                    rets=False)
            functions.append(self.function(f'{cls.name} * {name}(int v) {{', fn, 4,
                                           lambda: f'return {self.pointer_value(fn, cls)};', name=name))
            self.retp_fns.append((name, cls))
            self.feature('pointer-returning-function')
        hooks = []
        if self.chance(0.15):
            fn = Fn(INF, ('void',), rng.randint(1, 4), False, fns=self.int_fns)
            hooks.append(self.function('void __script_onload() {', fn, 3, None, name='__script_onload'))
            self.feature('onload-hook')
        if self.chance(0.25):
            fn = Fn(INF, ('void',), rng.randint(1, 4), self.chance(0.3), fns=self.int_fns)
            hooks.append(self.function('void __script_pre_destroy() {', fn, 3, None, name='__script_pre_destroy'))
            self.feature('pre-destroy-hook')
        shape = rng.random()
        returned = self.some_class(INF, bad=0)
        if shape < 0.1:
            # The host keeps a returned literal object; no destructor runs for it.
            fn = Fn(INF, ('obj', returned), rng.randint(6, 24), self.has_re, fns=self.int_fns)
            header, final = f'{returned.name} main() {{', lambda: f'return {self.object_value(fn, returned)};'
            self.feature('main-returns-object')
        elif shape < 0.2:
            # An automatic object returned to the host entry is owned by the global scope until teardown.
            fn = Fn(INF, ('ptr', returned), rng.randint(6, 24), self.has_re, fns=self.int_fns)
            header, final = f'{returned.name} * main() {{', lambda: f'return {self.pointer_value(fn, returned)};'
            self.feature('main-returns-pointer')
        else:
            fn = Fn(INF, ('int',), rng.randint(6, 24), self.has_re, fns=self.int_fns)
            header, final = 'int main() {', lambda: f'return {self.int_expr(fn)};'
        main = self.function(header, fn, 12, final, kind='main', fixed=True)
        helpers = [item for item in self.helpers.values()]
        items = ([Item([PRELUDE + EXTERNS], 'prelude', fixed=True)] + global_items + class_items + helpers +
                 functions + hooks + [main])
        return Program(self.seed, items, options, dict(sorted(self.features.items())))


def generate(seed):
    return Generator(seed).program()


def generation_digest(seeds):
    hasher = hashlib.sha256()
    for seed in seeds:
        program = generate(seed)
        hasher.update(json.dumps([seed, program.source, program.options]).encode())
    return hasher.hexdigest()


# ---------------------------------------------------------------------------
# Running and comparing

class CompileFailure(Exception):
    pass


def classify(reference, candidate, engine, allow_refusal=True):
    """('equal'|'refused'|'mismatch', detail) for two observations of one exec and budget."""
    problems = compare_engines(reference, candidate, 'tree', engine)
    if not problems:
        return 'equal', []
    message = str((candidate.get('error') or {}).get('message', ''))
    reference_message = str((reference.get('error') or {}).get('message', ''))
    if (allow_refusal and engine == 'vm' and candidate.get('phase') == 'flush' and message.startswith(REFUSAL)
            and not reference_message.startswith(REFUSAL)):
        return 'refused', message
    return 'mismatch', problems


def budget_points(seed, threshold, full, sweep_max, samples):
    """Budgets compared for one program: all of 1..T+1, or a deterministic sample with both boundaries."""
    end = threshold + 1
    if end <= sweep_max:
        return list(range(1, end + 1)), 'full'
    points = set(range(1, min(end, 8) + 1)) | set(range(max(1, threshold - 6), end + 1))
    used = DEFAULT_STEPS - full['remaining_steps']
    points |= {used - 1, used, used + 1}
    for event in full['events'] + full['teardown']['events']:
        if 'remaining_steps' in event:
            position = DEFAULT_STEPS - event['remaining_steps']
            points |= {position - 1, position, position + 1}
    spread = max(2, samples // 2)
    points |= {max(1, threshold * index // spread) for index in range(1, spread)}
    rng = random.Random(seed * 1_000_003 + 17)
    target = len({point for point in points if 1 <= point <= end}) + samples - spread
    while len({point for point in points if 1 <= point <= end}) < min(target, end):
        points.add(rng.randint(1, end))
    return sorted(point for point in points if 1 <= point <= end), 'sampled'


class Fuzzer:
    def __init__(self, args, work):
        self.args = args
        self.work = work
        self.lock = threading.Lock()
        self.processes = {'reference': 0, 'candidate': 0}
        self.env = os.environ.copy()
        for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            self.env.pop(key, None)
        self.compiler = ['java', '-cp', args.classpath, 'azertia.Main']

    def log(self, text):
        if not self.args.quiet:
            with self.lock:
                print(text, flush=True)

    def compile(self, program, folder, name='program', optimization=None):
        folder.mkdir(parents=True, exist_ok=True)
        try:
            record = compile_source(self.compiler, program.source, name, folder, self.env,
                                    '-' + (optimization or self.args.optimization))
        except (AssertionError, subprocess.SubprocessError, OSError) as error:
            raise CompileFailure(str(error)) from error
        write_json(folder / f'{name}.options.json', {'host_options': program.options, 'seed': program.seed})
        return Path(record['path'])

    def command(self, side, binary, options, budget):
        host, engine = ((self.args.reference_host, 'tree') if side == 'reference'
                        else (self.args.candidate_host, self.args.candidate_engine))
        return [str(host), str(binary), '--steps', str(budget), '--engine', engine, *options]

    def observe(self, side, binary, options, budget):
        command = self.command(side, binary, options, budget)
        with self.lock:
            self.processes[side] += 1
        try:
            return execute(command, self.args.timeout)
        except HostFailure as error:
            return {'host_failure': str(error), **error.details}

    def compare_at(self, binary, options, budget, reference=None, allow_refusal=False):
        reference = reference or self.observe('reference', binary, options, budget)
        candidate = self.observe('candidate', binary, options, budget)
        if 'host_failure' in reference or 'host_failure' in candidate:
            return 'host-failure', [f'host failure: reference={reference.get("host_failure")!r} '
                                    f'candidate={candidate.get("host_failure")!r}'], reference, candidate
        status, detail = classify(reference, candidate, self.args.candidate_engine, allow_refusal)
        return status, detail, reference, candidate

    def threshold(self, binary, options, full):
        """Smallest budget with the full run's terminal shape (reference only), and its observations."""
        shape = terminal_shape(full)
        cache = {}

        def matches(budget):
            if budget not in cache:
                cache[budget] = self.observe('reference', binary, options, budget)
            return 'host_failure' not in cache[budget] and terminal_shape(cache[budget]) == shape
        high = max(1, DEFAULT_STEPS - full['remaining_steps'])
        while not matches(high):
            if high >= DEFAULT_STEPS:
                return DEFAULT_STEPS, cache
            high = min(DEFAULT_STEPS, high * 2)
        low = 0
        while high - low > 1:
            middle = (low + high) // 2
            if matches(middle):
                high = middle
            else:
                low = middle
        return high, cache

    def sweep(self, seed, binary, options, full, record_limit=None):
        """Compare every sweep budget; returns (mismatching points, budgets, mode, threshold)."""
        threshold, cache = self.threshold(binary, options, full)
        budgets, mode = budget_points(seed, threshold, full, self.args.sweep_max, self.args.sweep_samples)
        found = []
        for budget in budgets:
            status, detail, reference, candidate = self.compare_at(binary, options, budget, cache.get(budget))
            if status != 'equal':
                found.append((budget, status, detail, reference, candidate))
                if record_limit and len(found) >= record_limit:
                    break
        return found, budgets, mode, threshold

    def run_seed(self, seed):
        try:
            return self.run_seed_checked(seed)
        except Exception as error:  # a tool defect must not hide the other seeds
            self.log(f'seed {seed}: INTERNAL ERROR {error!r}')
            return {'seed': seed, 'status': 'internal-error', 'error': repr(error)}

    def run_seed_checked(self, seed):
        program = generate(seed)
        folder = self.work / f'seed-{seed}'
        result = {'seed': seed, 'host_options': program.options, 'features': program.features}
        try:
            binary = self.compile(program, folder)
        except CompileFailure as error:
            result.update(status='generator-compile-failure', error=str(error),
                          source=str(folder / 'program.azs'))
            write_json(folder / 'result.json', result)
            self.log(f'seed {seed}: GENERATOR COMPILE FAILURE: {str(error)[:300]}')
            return result
        result['exec'] = str(binary)
        status, detail, full, candidate = self.compare_at(binary, program.options, DEFAULT_STEPS,
                                                          allow_refusal=True)
        result['outcome'] = (full.get('error') or {}).get('message') if 'host_failure' not in full else 'host failure'
        if 'host_failure' not in full:
            result['steps'] = DEFAULT_STEPS - full['remaining_steps']
            result['teardown_error'] = (full['teardown']['error'] or {}).get('message')
        if status == 'refused':
            result.update(status='refused', message=detail)
            write_json(folder / 'result.json', result)
            return result
        found = [] if status == 'equal' else [(DEFAULT_STEPS, status, detail, full, candidate)]
        if self.args.compare_optimization and 'host_failure' not in full:
            # Budget sweeps always compare the same artifact exactly. Across
            # levels only the ample-budget run is comparable, and resource
            # exhaustion is explicitly outside the optimization contract.
            from compiler_o2_end_to_end import semantic
            baseline = self.compile(program, folder / self.args.compare_optimization,
                                    optimization=self.args.compare_optimization)
            previous = self.observe('reference', baseline, program.options, DEFAULT_STEPS)
            write_json(folder / 'optimization-baseline.json', previous)
            write_json(folder / 'optimization-candidate.json', full)
            if 'host_failure' in previous:
                changed = ['baseline host failure: ' + str(previous['host_failure'])]
                found.append((DEFAULT_STEPS, 'host-failure', changed, previous, full))
                result['optimization_comparison'] = 'host-failure'
            else:
                resource_messages = ('Script execution step limit exceeded', 'Script call depth limit exceeded',
                                     'Script native stack limit exceeded')
                limited = any(any(message in str((value.get('error') or {}).get('message', ''))
                                  for message in resource_messages) for value in (previous, full))
                if limited:
                    result['optimization_comparison'] = 'resource-limited'
                else:
                    from runtime_differential import differences
                    changed = differences(
                        semantic(previous, preserve_frames=True), semantic(full, preserve_frames=True))
                    result['optimization_comparison'] = 'mismatch' if changed else 'passed'
                    if changed:
                        found.append((DEFAULT_STEPS, 'mismatch', changed, previous, full))
        if 'host_failure' not in full:
            more, budgets, mode, threshold = self.sweep(seed, binary, program.options, full)
            found += more
            result.update(budgets_compared=len(budgets), sweep=mode, threshold=threshold,
                          budget_range=[budgets[0], budgets[-1]])
        if not found:
            result['status'] = 'passed'
            write_json(folder / 'result.json', result)
            return result
        result['status'] = 'host-failure' if any(item[1] == 'host-failure' for item in found) else 'mismatch'
        result['mismatching_budgets'] = [item[0] for item in found]
        result['mismatch_dir'] = str(self.record_mismatch(seed, program, binary, found))
        write_json(folder / 'result.json', result)
        self.log(f'seed {seed}: MISMATCH at budgets {result["mismatching_budgets"][:12]}; {result["mismatch_dir"]}')
        if self.args.shrink and result.get('optimization_comparison') not in ('mismatch', 'host-failure'):
            result['shrunk'] = self.shrink_seed(seed, program, found)
            write_json(folder / 'result.json', result)
        return result

    def record_mismatch(self, seed, program, binary, found, folder=None):
        folder = folder or self.work / 'mismatches' / f'seed-{seed}'
        folder.mkdir(parents=True, exist_ok=True)
        (folder / 'program.azs').write_text(program.source, encoding='utf-8')
        points = []
        for index, (budget, status, detail, reference, candidate) in enumerate(found):
            point = {'budget': budget, 'status': status, 'difference_count': len(detail),
                     'differences': detail[:60]}
            if index < 6:
                for side, value in (('reference', reference), ('candidate', candidate)):
                    path = folder / f'{budget}.{side}.json'
                    write_json(path, {'command': self.command(side, binary, program.options, budget),
                                      'observation': value})
                    point[side] = str(path)
            points.append(point)
        write_json(folder / 'mismatch.json', {
            'seed': seed, 'exec': str(binary), 'exec_json': str(binary.with_suffix('.exec.json')),
            'source': str(folder / 'program.azs'), 'host_options': program.options,
            'reference_host': str(self.args.reference_host), 'candidate_host': str(self.args.candidate_host),
            'candidate_engine': self.args.candidate_engine, 'budgets': [item[0] for item in found],
            'points': points})
        return folder

    def shrink_seed(self, seed, program, found):
        folder = self.work / 'mismatches' / f'seed-{seed}' / 'shrink'
        folder.mkdir(parents=True, exist_ok=True)
        at_full = any(item[0] == DEFAULT_STEPS for item in found)
        counter = [0]

        def evaluate(trial, binary):
            status, _, full, _ = self.compare_at(binary, trial.options, DEFAULT_STEPS, allow_refusal=True)
            if status in ('mismatch', 'host-failure'):
                return True
            if at_full or status != 'equal' or 'host_failure' in full:
                return False
            return bool(self.sweep(seed, binary, trial.options, full, record_limit=1)[0])

        def fails(trial):
            # Only accepted reduction steps are kept on disk.
            counter[0] += 1
            attempt = folder / f'attempt-{counter[0]}'
            try:
                kept = evaluate(trial, self.compile(trial, attempt))
            except CompileFailure:
                kept = False
            if not kept:
                shutil.rmtree(attempt, ignore_errors=True)
            return kept
        minimal, attempts, accepted = shrink(program, fails, self.args.shrink_attempts,
                                             lambda text: self.log(f'seed {seed}: {text}'))
        final = folder / 'minimal'
        binary = self.compile(minimal, final)
        status, detail, full, candidate = self.compare_at(binary, minimal.options, DEFAULT_STEPS, allow_refusal=True)
        points = [] if status == 'equal' else [(DEFAULT_STEPS, status, detail, full, candidate)]
        if not points and 'host_failure' not in full:
            points = self.sweep(seed, binary, minimal.options, full, record_limit=3)[0]
        self.record_mismatch(seed, minimal, binary, points, final)
        (folder / 'minimal.azs').write_text(minimal.source, encoding='utf-8')
        summary = {'source': str(folder / 'minimal.azs'), 'exec': str(binary), 'attempts': attempts,
                   'accepted': accepted, 'bytes': [len(program.source), len(minimal.source)],
                   'budgets': [item[0] for item in points], 'mode': 'full budget' if at_full else 'budget sweep',
                   'still_mismatching': bool(points)}
        write_json(folder / 'shrink.json', summary)
        self.log(f'seed {seed}: minimal program ({len(minimal.source)} bytes, {attempts} attempts) '
                 f'{folder / "minimal.azs"}')
        return summary


def known_refusals():
    data = json.loads((INFRA / 'runtime_differences.json').read_text(encoding='utf-8'))
    runtime_differences.load(INFRA / 'runtime_differences.json')
    known = {entry['message'] for entry in data['entries'] if entry['kind'] == 'transitional_vm_refusal'}
    known |= {item['message'].removeprefix('AzScript error: ') for item in data.get('suite_refusals', [])}
    return known


def summarize(results, fuzzer, started):
    report = {'status': 'running', 'programs': len(results), 'elapsed_seconds': round(time.time() - started, 1),
              'independent_reference_processes': fuzzer.processes['reference'],
              'independent_candidate_processes': fuzzer.processes['candidate'],
              'passed': 0, 'refused': {}, 'mismatches': [], 'host_failures': [], 'generator_failures': [],
              'internal_errors': [], 'budget_points_compared': 0, 'sweeps': {'full': 0, 'sampled': 0}, 'outcomes': {},
              'teardown_errors': {}, 'feature_programs': {}, 'optimization_comparisons': {}}
    known = known_refusals()
    steps = []
    for result in results:
        comparison = result.get('optimization_comparison')
        if comparison:
            report['optimization_comparisons'][comparison] = report['optimization_comparisons'].get(comparison, 0) + 1
        for name in result.get('features', {}):
            report['feature_programs'][name] = report['feature_programs'].get(name, 0) + 1
        status = result['status']
        if status == 'generator-compile-failure':
            report['generator_failures'].append({key: result[key] for key in ('seed', 'error', 'source')})
            continue
        if status == 'internal-error':
            report['internal_errors'].append(result)
            continue
        outcome = result.get('outcome') or 'success'
        report['outcomes'][outcome] = report['outcomes'].get(outcome, 0) + 1
        if result.get('teardown_error'):
            errors = report['teardown_errors']
            errors[result['teardown_error']] = errors.get(result['teardown_error'], 0) + 1
        if 'steps' in result:
            steps.append(result['steps'])
        if status != 'refused':
            report['budget_points_compared'] += result.get('budgets_compared', 0) + 1
        if result.get('sweep'):
            report['sweeps'][result['sweep']] += 1
        if status == 'passed':
            report['passed'] += 1
        elif status == 'refused':
            entry = report['refused'].setdefault(result['message'], {
                'programs': 0, 'registered': result['message'] in known, 'seeds': []})
            entry['programs'] += 1
            entry['seeds'].append(result['seed'])
        else:
            summary = {key: result.get(key) for key in
                       ('seed', 'mismatching_budgets', 'mismatch_dir', 'exec', 'shrunk')}
            report['host_failures' if status == 'host-failure' else 'mismatches'].append(summary)
    if steps:
        report['full_run_steps'] = {'min': min(steps), 'median': statistics.median(steps), 'max': max(steps)}
    report['feature_programs'] = dict(sorted(report['feature_programs'].items()))
    report['outcomes'] = dict(sorted(report['outcomes'].items(), key=lambda item: -item[1]))
    failed = (report['mismatches'] or report['host_failures'] or report['generator_failures'] or
              report['internal_errors'])
    report['status'] = 'failed' if failed else 'passed'
    return report


# ---------------------------------------------------------------------------
# Self-test

def self_test(args):
    checks = 0
    seeds = range(args.self_test_seeds)
    first = [generate(seed) for seed in seeds]
    second = [generate(seed) for seed in seeds]
    assert ([(p.source, p.options) for p in first] == [(p.source, p.options) for p in second]), \
        'nondeterministic generation'
    assert len({p.source for p in first}) == len(first), 'duplicate programs for distinct seeds'
    checks += 2
    # A fresh interpreter with another hash seed must produce the same programs.
    env = dict(os.environ, PYTHONHASHSEED='12345')
    output = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--infra', str(INFRA), '--digest',
                             '--seed-start', '0', '--seeds', str(len(seeds))], capture_output=True, text=True,
                            env=env, timeout=300)
    assert output.returncode == 0 and output.stdout.strip() == generation_digest(seeds), output.stderr
    checks += 1
    features = {}
    for program in (generate(seed) for seed in range(200)):
        for name in program.features:
            features[name] = features.get(name, 0) + 1
    required = ['inheritance-depth-3', 'explicit-destructor', 'synthesized-destructor', 'literal-field',
                'failure-in-destructor', 'probe-in-destructor', 'raw-memory-in-destructor', 'reentry-in-destructor',
                'object-created-in-destructor', 'delete-pointer-field-in-destructor', 'temporary-in-destructor',
                'statement-temporary', 'by-value-argument-copy', 'returned-parameter', 'field-copy',
                'returned-field-copy', 'returned-object-from-random-body', 'returned-automatic-object',
                'manual-object', 'delete', 'automatic-object', 'for-loop', 'while-loop', 'switch', 'break',
                'continue', 'return-from-nested-scope', 'host-reentry-target', 'expired-this-address',
                'failure-in-function-body', 'raw-allocation', 'pre-destroy-hook', 'small-call-depth',
                'virtual-override', 'recursion', 'temporary-in-loop-condition', 'base-pointer-to-derived-object']
    missing = [name for name in required if not features.get(name)]
    assert not missing, f'features never generated in seeds 0..199: {missing}'
    checks += 1

    # Shrinker mechanics on a text predicate (no compiler): the result keeps the
    # property and no single removal keeps it any more.
    program = next(p for p in (generate(seed) for seed in range(100))
                   if 'delete ' in p.source and 'event(reenter' in p.source)

    def predicate(trial):
        return 'delete ' in trial.source and 'event(reenter' in trial.source
    minimal, attempts, accepted = shrink(program, predicate, 100000)
    assert predicate(minimal) and len(minimal.source) < len(program.source) and accepted
    for operation in shrink_operations(minimal.items):
        if operation[0] == 'remove':
            items, removed = apply_operation(minimal.items, operation)
            trial = minimal.with_items(items)
            assert unresolvable(operation, removed, trial) or not predicate(trial), \
                'shrunk program is not removal-minimal'
    checks += 1

    # Budget sampling: deterministic, inside 1..T+1, both boundaries included.
    fake = {'remaining_steps': DEFAULT_STEPS - 900, 'events': [{'remaining_steps': DEFAULT_STEPS - 400}],
            'teardown': {'events': []}}
    sample, mode = budget_points(7, 1000, fake, 256, 48)
    assert mode == 'sampled' and sample == budget_points(7, 1000, fake, 256, 48)[0]
    assert {1, 1000, 1001, 399, 400, 401, 899, 900, 901} <= set(sample) and max(sample) == 1001 and min(sample) == 1
    assert budget_points(7, 40, fake, 256, 48) == (list(range(1, 42)), 'full')
    checks += 1

    # Classification: identical, planted difference, VM refusal rules.
    base = {'protocol': PROTOCOL, 'engine': 'tree', 'phase': 'complete', 'return': typed(0, 1), 'error': None,
            'events': [{'callback': 1, 'arguments': [typed(0, -1)]}], 'stdout_hex': '',
            'state': {'globals': [], 'heap': []}, 'remaining_steps': 5, 'faulted': False,
            'teardown': {'error': None, 'events': [], 'stdout_hex': '', 'state': {'globals': [], 'heap': []}}}
    real = None
    if args.classpath and args.reference_host:
        fuzzer = Fuzzer(args, args.work)
        failures = []

        def compiles(seed):
            try:
                fuzzer.compile(generate(seed), args.work / f'compile-{seed}')
                return None
            except CompileFailure as error:
                return seed, str(error)[:400]
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            failures = [item for item in pool.map(compiles, seeds) if item]
        assert not failures, f'generated programs rejected by the compiler: {failures}'
        checks += 1
        binary = Path(args.work / 'compile-0' / 'program.abd')
        real = fuzzer.observe('reference', binary, generate(0).options, DEFAULT_STEPS)
        assert 'host_failure' not in real, real
        again = fuzzer.observe('reference', binary, generate(0).options, DEFAULT_STEPS)
        assert classify(real, again, 'tree')[0] == 'equal', 'reference host is not deterministic'
        checks += 1
        if args.candidate_host and args.candidate_engine == 'vm':
            # A real flush refusal (buffers) must classify as refused with a registered message.
            source = PRELUDE + 'int main(){ buffer<M> a; a.reserve(1); a.push(mk(1)); return a.length(); }\n'
            refusal = Program(-1, [Item([source], 'prelude', fixed=True)], [], {})
            binary = fuzzer.compile(refusal, args.work / 'refusal')
            status, detail, _, _ = fuzzer.compare_at(binary, [], DEFAULT_STEPS, allow_refusal=True)
            if status == 'refused':
                assert detail in known_refusals(), f'unregistered refusal message: {detail!r}'
                checks += 1
            elif status == 'equal':
                print('lifecycle_fuzz self-test: the candidate VM runs buffers; live refusal check not exercised')
            else:
                raise AssertionError(f'buffer program: {status} {detail[:8]}')
    else:
        print('lifecycle_fuzz self-test: no --classpath/--reference-host; compile and live checks skipped')
    for observation in [item for item in (base, real) if item]:
        assert classify(observation, copy.deepcopy(observation), 'tree') == ('equal', [])
        mutations = []
        for path, value in [(('remaining_steps',), observation['remaining_steps'] + 1),
                            (('faulted',), not observation['faulted']),
                            (('phase',), 'flush' if observation['phase'] != 'flush' else 'invoke'),
                            (('stdout_hex',), observation['stdout_hex'] + 'ff'),
                            (('teardown', 'error'), {'message': 'planted'}), (('engine',), 'vm')]:
            mutated = copy.deepcopy(observation)
            target = mutated
            for step in path[:-1]:
                target = target[step]
            target[path[-1]] = value
            mutations.append(mutated)
        if observation['events']:
            mutated = copy.deepcopy(observation)
            mutated['events'][0]['planted'] = 1
            mutations.append(mutated)
            mutated = copy.deepcopy(observation)
            mutated['events'].append(copy.deepcopy(mutated['events'][0]))
            mutations.append(mutated)
            if len(observation['events']) > 1:
                mutated = copy.deepcopy(observation)
                mutated['events'][0], mutated['events'][1] = mutated['events'][1], mutated['events'][0]
                if mutated['events'] != observation['events']:
                    mutations.append(mutated)
        probes = [index for index, event in enumerate(observation['events']) if 'remaining_steps' in event]
        if probes:
            mutated = copy.deepcopy(observation)
            mutated['events'][probes[0]]['remaining_steps'] += 1
            mutations.append(mutated)
            mutated = copy.deepcopy(observation)
            mutated['events'][probes[0]]['active_calls'] += 1
            mutations.append(mutated)
        if observation['error'] and observation['error']['trace']['frames']:
            for key in ('line', 'column'):
                mutated = copy.deepcopy(observation)
                mutated['error']['trace']['frames'][0][key] += 1
                mutations.append(mutated)
        for key in ('heap', 'globals'):
            mutated = copy.deepcopy(observation)
            mutated['state'][key] = mutated['state'][key] + [typed(0, 0)]
            mutations.append(mutated)
        for mutated in mutations:
            assert mutated != observation, 'a planted mutation changed nothing'
            status, detail = classify(observation, mutated, 'tree')
            assert status == 'mismatch' and detail, ('planted difference not detected', mutated)
            checks += 1
        refused = copy.deepcopy(observation)
        refused.update(engine='vm', phase='flush', error={'message': REFUSAL + ': bind_context buffer_new'})
        assert classify(observation, refused, 'vm') == ('refused', REFUSAL + ': bind_context buffer_new')
        assert classify(observation, refused, 'vm', allow_refusal=False)[0] == 'mismatch'
        assert classify(observation, refused, 'tree')[0] == 'mismatch'
        late = copy.deepcopy(refused)
        late['phase'] = 'invoke'
        assert classify(observation, late, 'vm')[0] == 'mismatch'
        other = copy.deepcopy(refused)
        other['error'] = {'message': 'Scalar VM does not support something'}
        assert classify(observation, other, 'vm')[0] == 'mismatch'
        checks += 5
    print(f'lifecycle_fuzz self-test passed: {checks} checks; generation digest {generation_digest(seeds)[:16]} '
          f'for seeds 0..{len(seeds) - 1}')


# ---------------------------------------------------------------------------

def default_classpath():
    for root in (HERE.parent, INFRA_ROOT):
        path = root / 'build/java/classpath.txt'
        if path.is_file():
            return path.read_text(encoding='utf-8').strip()
    return None


def new_work_directory(prefix):
    base = INFRA_ROOT / 'build/runtime-perf'
    base.mkdir(parents=True, exist_ok=True)
    for index in range(1, 100000):
        path = base / f'{prefix}-{index}'
        try:
            path.mkdir()
            return path
        except FileExistsError:
            continue
    raise SystemExit('no free work directory name')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--infra', type=Path, help='directory holding the comparison infrastructure modules')
    parser.add_argument('--seed-start', type=int, default=0)
    parser.add_argument('--seeds', type=int, default=50, help='number of consecutive seeds')
    parser.add_argument('--reference-host', type=Path, default=F0_HOST if F0_HOST.is_file() else None,
                        help='frozen reference observation host (always engine tree)')
    parser.add_argument('--candidate-host', type=Path)
    parser.add_argument('--candidate-engine', choices=['tree', 'vm'], default='tree')
    parser.add_argument('--classpath', default=None, help='compiler classpath (default: build/java/classpath.txt)')
    parser.add_argument('--optimization', choices=('O0', 'O1', 'O2', 'O3'), default='O0',
                        help='level of the artifact used by both engines and all budget sweeps')
    parser.add_argument('--compare-optimization', choices=('O0', 'O1', 'O2', 'O3'),
                        help='also compare ample-budget tree observations against this level')
    parser.add_argument('--work', type=Path, help='new directory (default build/runtime-perf/fuzz-<engine>-<n>)')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--sweep-max', type=int, default=256, help='full budget sweep when T+1 is at most this')
    parser.add_argument('--sweep-samples', type=int, default=48, help='extra budgets sampled for longer programs')
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('--shrink', action='store_true', help='shrink every mismatching program')
    parser.add_argument('--shrink-attempts', type=int, default=400)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--self-test-seeds', type=int, default=24)
    parser.add_argument('--print-source', type=int, metavar='SEED', help='print one generated program and exit')
    parser.add_argument('--digest', action='store_true', help='print the generation digest of the seed range')
    parser.add_argument('--quiet', action='store_true')
    args = parser.parse_args()
    if args.compare_optimization == args.optimization:
        parser.error('--compare-optimization must differ from --optimization')
    if args.print_source is not None:
        program = generate(args.print_source)
        print(program.source, end='')
        print(f'// host options: {program.options}\n// features: {program.features}', file=sys.stderr)
        return
    if args.digest:
        print(generation_digest(range(args.seed_start, args.seed_start + args.seeds)))
        return
    args.classpath = args.classpath or default_classpath()
    if args.jobs < 1 or args.seeds < 1 or args.timeout <= 0 or args.sweep_max < 1 or args.sweep_samples < 2:
        parser.error('invalid jobs, seeds, timeout or sweep settings')
    if args.reference_host:
        args.reference_host = args.reference_host.resolve()
        if not args.reference_host.is_file():
            parser.error(f'reference host does not exist: {args.reference_host}')
    if args.self_test:
        if args.candidate_host:
            args.candidate_host = args.candidate_host.resolve()
        args.work = args.work.resolve() if args.work else new_work_directory('fuzz-selftest')
        args.work.mkdir(parents=True, exist_ok=True)
        self_test(args)
        return
    if not args.candidate_host or not args.reference_host or not args.classpath:
        parser.error('--candidate-host, --reference-host and a classpath are required')
    args.candidate_host = args.candidate_host.resolve()
    if not args.candidate_host.is_file():
        parser.error(f'candidate host does not exist: {args.candidate_host}')
    if args.candidate_host == args.reference_host and args.candidate_engine == 'tree':
        parser.error('reference and candidate must be distinct executables or engines')
    if args.work:
        args.work = args.work.resolve()
        args.work.mkdir(parents=True, exist_ok=False)
    else:
        args.work = new_work_directory(f'fuzz-{args.candidate_engine}')
    started = time.time()
    fuzzer = Fuzzer(args, args.work)
    seeds = list(range(args.seed_start, args.seed_start + args.seeds))
    write_json(args.work / 'manifest.json', {
        'tool': {'path': str(Path(__file__).resolve()), 'sha256': digest(__file__)}, 'infra': str(INFRA),
        'protocol': PROTOCOL, 'seeds': [seeds[0], seeds[-1]], 'generation_digest': generation_digest(seeds),
        'reference': {'path': str(args.reference_host), 'sha256': digest(args.reference_host), 'engine': 'tree'},
        'candidate': {'path': str(args.candidate_host), 'sha256': digest(args.candidate_host),
                      'engine': args.candidate_engine},
        'compiler_inputs': [{'path': entry, 'sha256': digest(entry)} for entry in args.classpath.split(os.pathsep)
                            if entry and Path(entry).is_file()],
        'settings': {key: value for key, value in vars(args).items()
                     if key in ('jobs', 'sweep_max', 'sweep_samples', 'timeout', 'shrink', 'shrink_attempts',
                                'optimization', 'compare_optimization')},
        'started_unix': started})
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(fuzzer.run_seed, seeds))
    report = summarize(results, fuzzer, started)
    write_json(args.work / 'report.json', report)
    refused = sum(entry['programs'] for entry in report['refused'].values())
    print(f'lifecycle fuzz ({args.candidate_engine}): {report["status"]}; seeds {seeds[0]}..{seeds[-1]}: '
          f'{report["passed"]} passed, {len(report["mismatches"])} mismatches, {refused} refused, '
          f'{len(report["host_failures"])} host failures, {len(report["generator_failures"])} generator failures; '
          f'{report["budget_points_compared"]} budget points, {report["independent_reference_processes"]} reference / '
          f'{report["independent_candidate_processes"]} candidate processes, {report["elapsed_seconds"]}s. '
          f'Artifacts: {args.work}')
    for message, entry in report['refused'].items():
        print(f'  refused x{entry["programs"]} ({"registered" if entry["registered"] else "unregistered"}): {message}')
    if report['status'] != 'passed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
