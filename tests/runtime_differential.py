#!/usr/bin/env python3
"""Compare independent runtime host processes, preserving failures and exact observations.

The same test adapter must be linked separately against the frozen and candidate
runtimes. This is a semantic experiment, never the timing baseline. No runtime
address, error, trace, float bit pattern or budget field is ignored.
"""
import argparse
import copy
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import random
import struct
import subprocess
import sys
import tempfile
import time

import compact_exec_end_to_end as wire

ROOT = Path(__file__).resolve().parents[1]
HOST = 0x6DFF0000
DEFAULT_STEPS = 1_000_000
PROTOCOL = 2
OBSERVATION_KEYS = {'protocol', 'engine', 'phase', 'return', 'error', 'events', 'stdout_hex',
                    'state', 'remaining_steps', 'faulted', 'teardown'}
ADAPTER = ROOT / 'tests/observation_host.cpp'


class HostFailure(RuntimeError):
    def __init__(self, message, stdout=b'', stderr=b'', returncode=None):
        super().__init__(message)
        self.details = {'stdout_hex': stdout.hex(), 'stderr_hex': stderr.hex(), 'returncode': returncode}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def differences(left, right, path='$'):
    """Strict JSON comparison: bool/int, missing keys and list order all matter."""
    if type(left) is not type(right):
        return [f'{path}: type {type(left).__name__} != {type(right).__name__}']
    if isinstance(left, dict):
        result = []
        for key in sorted(left.keys() | right.keys()):
            if key not in left or key not in right:
                result.append(f'{path}.{key}: missing from one observation')
            else:
                result.extend(differences(left[key], right[key], f'{path}.{key}'))
        return result
    if isinstance(left, list):
        if len(left) != len(right):
            return [f'{path}: length {len(left)} != {len(right)}']
        return [problem for i, (a, b) in enumerate(zip(left, right))
                for problem in differences(a, b, f'{path}[{i}]')]
    return [] if left == right else [f'{path}: {left!r} != {right!r}']


def engine_neutral(observation):
    """Every field except the engine the runtime reported; engines are checked separately."""
    return {key: value for key, value in observation.items() if key != 'engine'}


def compare_engines(reference, candidate, reference_engine, candidate_engine):
    """Strict comparison of two observations from explicitly selected engines."""
    result = []
    for label, observation, expected in (('reference', reference, reference_engine),
                                         ('candidate', candidate, candidate_engine)):
        if observation.get('engine') not in (expected, 'unloaded'):
            result.append(f'$.engine: {label} reported {observation.get("engine")!r}, expected {expected!r}')
    return result + differences(engine_neutral(reference), engine_neutral(candidate))


def checked_observation(result):
    if result.returncode != 0 or result.stderr:
        raise RuntimeError(f'host infrastructure failure: exit={result.returncode}, stderr={result.stderr!r}')
    value = json.loads(result.stdout)
    if not isinstance(value, dict) or set(value) != OBSERVATION_KEYS or value.get('protocol') != PROTOCOL:
        raise RuntimeError('host returned an unsupported or incomplete observation')
    return value


def execute(command, timeout):
    # No shell, and no process-global runtime state can cross this boundary.
    try:
        result = subprocess.run(list(map(str, command)), capture_output=True, timeout=timeout, cwd=ROOT)
    except subprocess.TimeoutExpired as error:
        raise HostFailure(f'host timeout after {timeout}s', error.stdout or b'', error.stderr or b'') from error
    try:
        decoded = subprocess.CompletedProcess(result.args, result.returncode,
            result.stdout.decode('utf-8'), result.stderr.decode('utf-8'))
        return checked_observation(decoded)
    except (RuntimeError, UnicodeError, ValueError) as error:
        raise HostFailure(str(error), result.stdout, result.stderr, result.returncode) from error


def typed(tag, value):
    return {'tag': tag, 'value': value}


def call(ident, *args):
    return wire.expression(2, wire.integer(ident), wire.stack(*args), wire.stack())


def variable(slot):
    return wire.expression(3, wire.integer(slot))


def assign(slot, value):
    return wire.expression(5, wire.integer(slot), value)


def signature(ident, params, returns=0):
    return wire.stack(wire.integer(ident), wire.integer(returns),
                      wire.stack(*(wire.integer(kind) for kind in params)), wire.integer(0), wire.integer(0))


def module(body, *, returns=0, globals_count=0, locals_count=0, signatures=(), functions=()):
    return wire.frame(wire.stack(*wire.module(
        [wire.function(body, return_type=returns, locals_count=locals_count), *functions],
        globals_count=globals_count, signatures=signatures)))


@dataclass
class Case:
    name: str
    binary: bytes = b''
    source: str = ''
    expected_return: object = None
    expected_error: str = ''
    expected_globals: object = None
    expected_events: object = None
    expected_stdout: object = None
    expected_heap: object = None
    options: list = field(default_factory=list)
    sweep: bool = False
    seed: object = None
    recipe: object = None
    # Optional exact expectations for lifecycle conformance cases. When
    # partial_events is set, each expected event is compared only on its keys.
    expected_error_category: str = ''
    expected_remaining: object = None
    expected_teardown_events: object = None
    expected_teardown_error: object = None
    expected_teardown_heap: object = None
    expected_stdout_text: object = None
    partial_events: bool = False


def generated(seed, recipe=None):
    rng = random.Random(seed)
    if recipe is None:
        recipe = [(rng.choice(['add', 'xor', 'subtract']), rng.randint(0, 31)) for _ in range(8)]
    value = seed % 97
    statements = [assign(-1, wire.constant(value)), assign(-2, wire.constant(0))]
    events = []
    for operation, operand in recipe:
        opcode = {'add': 12, 'subtract': 13, 'xor': 45}[operation]
        value = value + operand if operation == 'add' else value - operand if operation == 'subtract' else value ^ operand
        statements.extend([assign(-1, wire.expression(opcode, variable(-1), wire.constant(operand))),
                           call(HOST + 1, variable(-1))])
        events.append({'callback': 1, 'arguments': [typed(0, value)]})
    statements.append(wire.returning(variable(-1)))
    return Case(f'seed-{seed}', module(wire.block(*statements), globals_count=2,
                signatures=[signature(HOST + 1, [0])]), expected_return=typed(0, value),
                expected_globals=[typed(0, value), typed(0, 0)], expected_events=events,
                seed=seed, recipe=recipe)


def cases(seeds, sources):
    result = []
    for name, scalar, tag, value in [
            ('int', -2147483648, 0, -2147483648),
            ('string', 'A\0😀', 1, 'A\0😀'.encode().hex()),
            ('float-negative-zero', wire.Scalar(wire.FLOAT, struct.pack('<f', -0.0)), 2, '80000000'),
            ('double-negative-zero', wire.Scalar(wire.DOUBLE, struct.pack('<d', -0.0)), 3, '8000000000000000'),
            ('boolean', True, 4, True),
            ('address-high-bits', wire.Address(0xfedcba9876543210), 7, 'fedcba9876543210')]:
        result.append(Case(name, module(wire.block(wire.returning(wire.constant(scalar))), returns=tag),
                           expected_return=typed(tag, value), sweep=name == 'int'))
    result.append(Case('void', module(wire.block(wire.returning()), returns=5), expected_return=typed(5, None)))
    printed = 'hello\0😀'
    result.append(Case('stdout-bytes', module(wire.block(call(0x0abd0000, wire.constant(printed)),
        wire.returning(wire.constant(42)))), expected_return=typed(0, 42), expected_stdout=(printed + '\n').encode().hex()))
    onload = wire.function(wire.block(assign(-1, call(0x0abd0003, wire.constant(2))),
        call(0x0abd0004, variable(-1)), wire.expression(6, call(0x0abd0006, variable(-1)), wire.constant(17)),
        wire.returning()), ident=0, return_type=5)
    destroy = wire.function(wire.block(call(0x0abd0002, variable(-1)), wire.returning()), ident=1, return_type=5)
    result.append(Case('visible-heap-and-hooks', module(wire.block(wire.returning(wire.constant(4))),
        globals_count=1, functions=[onload, destroy]), expected_return=typed(0, 4),
        expected_globals=[typed(7, '0000000000000001')],
        expected_heap=[{'address': '0000000000000001', 'length': 2, 'slots': [typed(0, 17), typed(5, None)]}]))
    result.append(Case('division-error', module(wire.block(wire.returning(
        wire.expression(15, wire.constant(4), wire.constant(0))))), expected_error='Division by zero'))
    result.append(Case('declared-int-dynamic-double', module(wire.block(
        wire.expression(4, wire.integer(0), wire.integer(0), b'\1', wire.constant(1)),
        assign(0, wire.constant(wire.Scalar(wire.DOUBLE, struct.pack('<d', 2.5)))),
        wire.returning(wire.expression(12, variable(0), wire.constant(1)))), returns=3, locals_count=1),
        expected_return=typed(3, '400c000000000000')))
    result.append(Case('host-changed-global-tag', module(wire.block(wire.returning(
        wire.expression(12, variable(-1), wire.constant(2)))), returns=3, globals_count=1),
        expected_return=typed(3, '4012000000000000'), options=['--set-global', '0=d:4004000000000000']))
    # ADD snapshots the lhs before the callback mutates its underlying global.
    result.append(Case('rhs-mutates-lhs', module(wire.block(assign(-1, wire.constant(7)),
        wire.returning(wire.expression(12, variable(-1), call(HOST + 3, wire.constant(0), wire.constant(11))))),
        globals_count=1, signatures=[signature(HOST + 3, [0, 0])]), expected_return=typed(0, 18),
        expected_globals=[typed(0, 11)], sweep=True))
    result.append(Case('short-circuit-skips-host-error', module(wire.block(wire.returning(
        wire.expression(23, wire.constant(False), wire.expression(19, call(HOST + 2, wire.constant(0)), wire.constant(0))))),
        returns=4, signatures=[signature(HOST + 2, [0])]), expected_return=typed(4, False), expected_events=[], sweep=True))
    result.append(Case('host-error', module(wire.block(wire.returning(call(HOST + 2, wire.constant(41)))),
        signatures=[signature(HOST + 2, [0])]), expected_error='differential host failure'))
    reentry_id = wire.MAIN + 16
    result.append(Case('host-reentry', module(wire.block(wire.returning(
        call(HOST + 4, wire.constant(reentry_id), wire.constant(9)))),
        signatures=[signature(HOST + 4, [0, 0])], functions=[wire.function(wire.block(wire.returning(
            wire.expression(12, variable(0), wire.constant(3)))), ident=reentry_id, params=[0])]),
        expected_return=typed(0, 12), sweep=True))
    result.extend(generated(seed) for seed in seeds)
    if sources:
        emit = 'extern int event(int):6dff0001;\n'
        result.extend([
            Case('source-cleanup', source=emit +
                 'class Mark { int n; Mark(int v){n=v;event(v);} ~Mark(){event(0-n);} }\n'
                 'int main(){ {Mark a(1); {Mark b(2);}} return 12;}\n', expected_return=typed(0, 12),
                 expected_events=[{'callback': 1, 'arguments': [typed(0, i)]} for i in [1, 2, -2, -1]], sweep=True),
            Case('source-first-error', source=emit +
                 'class Bad { int n; ~Bad(){event(9);int ignored=1/0;} }\n'
                 'int main(){Bad value;return 2147483647+1;}\n', expected_error='Integer overflow'),
            Case('source-virtual', source=
                 'class Base { int n; virtual int f(){return 1;} int run(){return f();} }\n'
                 'class Child:Base { override int f(){return Base::f()+10;} }\n'
                 'int main(){Base* p=new Child();int value=p.run();delete p;return value;}\n', expected_return=typed(0, 11)),
            Case('source-control', source=emit +
                 'int main(){int n=0;for(int i=0;i<6;i=i+1){if(i==2)continue;if(i==5)break;n=n+i;} '
                 'event(n);return n;}\n', expected_return=typed(0, 8)),
        ])
    return result


def subset_differences(expected, actual, path='$'):
    """Like differences(), but dictionaries in expected constrain only their own keys."""
    if isinstance(expected, dict) and isinstance(actual, dict):
        result = []
        for key, value in expected.items():
            if key not in actual:
                result.append(f'{path}.{key}: missing')
            else:
                result.extend(subset_differences(value, actual[key], f'{path}.{key}'))
        return result
    if isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            return [f'{path}: length {len(expected)} != {len(actual)}']
        return [problem for i, (a, b) in enumerate(zip(expected, actual))
                for problem in subset_differences(a, b, f'{path}[{i}]')]
    return differences(expected, actual, path)


def semantic_checks(case, observation):
    if case.expected_error_category and (observation['error'] is None or
                                         observation['error']['category'] != case.expected_error_category):
        raise AssertionError(f'{case.name}: expected error category {case.expected_error_category!r}, '
                             f'got {observation["error"]!r}')
    if case.expected_error:
        if observation['error'] is None or observation['error']['message'] != case.expected_error:
            raise AssertionError(f'{case.name}: expected error {case.expected_error!r}, got {observation["error"]!r}')
    else:
        if observation['phase'] != 'complete' or observation['error'] is not None:
            raise AssertionError(f'{case.name}: expected successful completion, got {observation["error"]!r}')
        if differences(case.expected_return, observation['return']):
            raise AssertionError(f'{case.name}: wrong specified return: {observation["return"]!r}')
    if case.expected_globals is not None and differences(case.expected_globals, observation['state']['globals']):
        raise AssertionError(f'{case.name}: wrong specified globals')
    compare = subset_differences if case.partial_events else differences
    if case.expected_events is not None and compare(case.expected_events, observation['events']):
        raise AssertionError(f'{case.name}: wrong specified events: {observation["events"]!r}')
    if case.expected_remaining is not None and observation['remaining_steps'] != case.expected_remaining:
        raise AssertionError(f'{case.name}: expected remaining_steps {case.expected_remaining}, '
                             f'got {observation["remaining_steps"]}')
    if case.expected_stdout_text is not None and bytes.fromhex(observation['stdout_hex']) != case.expected_stdout_text.encode():
        raise AssertionError(f'{case.name}: wrong specified stdout: {bytes.fromhex(observation["stdout_hex"])!r}')
    if case.expected_stdout is not None and case.expected_stdout != observation['stdout_hex']:
        raise AssertionError(f'{case.name}: wrong specified stdout bytes')
    if case.expected_heap is not None and differences(case.expected_heap, observation['state']['heap']):
        raise AssertionError(f'{case.name}: wrong specified heap: {observation["state"]["heap"]!r}')
    teardown = observation['teardown']
    if case.expected_teardown_events is not None and compare(case.expected_teardown_events, teardown['events']):
        raise AssertionError(f'{case.name}: wrong specified teardown events: {teardown["events"]!r}')
    if case.expected_teardown_error is None:
        if teardown['error'] is not None:
            raise AssertionError(f'{case.name}: unexpected teardown error {teardown["error"]!r}')
    elif teardown['error'] is None or teardown['error']['message'] != case.expected_teardown_error:
        raise AssertionError(f'{case.name}: expected teardown error {case.expected_teardown_error!r}, got {teardown["error"]!r}')
    if differences(case.expected_teardown_heap or [], teardown['state']['heap']):
        raise AssertionError(f'{case.name}: wrong teardown heap {teardown["state"]["heap"]!r}')


def self_test():
    inventory = (ROOT / 'docs/runtime-performance-roadmap/SEMANTICS.md').read_text(encoding='utf-8')
    for opcode, name in wire.REGISTRY.items():
        assert f'| {opcode} | {name} |' in inventory, f'undocumented opcode: {opcode} {name}'
    state = {'globals': [typed(0, 1)], 'heap': [{'address': '0000000000000001'}], 'object_records': 1,
             'objects': [{'address': '0000000000000001', 'manual': True, 'destructor': 5, 'contexts': 0, 'methods': 0}],
             'method_tables': 0, 'owned': ['0000000000000001'],
             'probe': {'next_block': '8000000003000000', 'first_fit': '0000000000000002'}}
    base = {'protocol': PROTOCOL, 'engine': 'tree', 'phase': 'complete', 'return': typed(3, '8000000000000000'),
            'error': None, 'events': [{'n': 1}, {'callback': 5, 'arguments': [typed(0, 1)], 'active_calls': 2,
                                                 'remaining_steps': 9, 'published_trace': {'frames': [], 'omitted': 0}}],
            'stdout_hex': '00ff', 'state': state, 'remaining_steps': 7, 'faulted': False,
            'teardown': {'error': None, 'state': copy.deepcopy(state)}}
    assert not differences(base, copy.deepcopy(base))
    wrong = []
    for key, replacement in [('return', typed(3, '0000000000000000')), ('return', typed(2, '80000000')),
                             ('events', list(reversed(base['events']))), ('stdout_hex', '00fe'),
                             ('remaining_steps', 8), ('faulted', 0), ('phase', 'invoke'),
                             ('state', {'globals': [typed(0, 2)], 'heap': []}),
                             ('error', {'message': 'wrong', 'trace': {'frames': []}}),
                             ('teardown', {'error': {'message': 'wrong'}}), ('engine', 'vm')]:
        value = copy.deepcopy(base); value[key] = replacement; wrong.append(value)
    # Protocol-2 probes: each field alone must be detected.
    for path, replacement in [(('events', 1, 'active_calls'), 3), (('events', 1, 'remaining_steps'), 8),
                              (('events', 1, 'published_trace', 'frames'), [{'function': 1}]),
                              (('state', 'probe', 'next_block'), '8000000004000000'),
                              (('state', 'probe', 'first_fit'), '0000000000000003'),
                              (('state', 'owned'), []), (('state', 'objects', 0, 'manual'), False),
                              (('teardown', 'state', 'probe', 'first_fit'), '0000000000000001')]:
        value = copy.deepcopy(base); target = value
        for step in path[:-1]: target = target[step]
        target[path[-1]] = replacement; wrong.append(value)
    vm_base = copy.deepcopy(base); vm_base['engine'] = 'vm'
    assert not compare_engines(base, vm_base, 'tree', 'vm')
    assert compare_engines(base, copy.deepcopy(base), 'tree', 'vm'), 'a tree run accepted as a VM run'
    assert compare_engines(base, vm_base, 'vm', 'vm')
    deleted = copy.deepcopy(base); del deleted['remaining_steps']; wrong.append(deleted)
    trace_a = {'message': 'same', 'trace': {'frames': [{'function': 3, 'line': 1}]}}
    trace_b = copy.deepcopy(trace_a); trace_b['trace']['frames'][0]['line'] = 2
    assert differences(trace_a, trace_b)
    address_a = typed(7, '0000000000000001'); address_b = typed(7, '0000000000000002')
    assert differences(address_a, address_b)
    for altered in wrong:
        assert differences(base, altered), altered
    # Exercise real process output parsing and strict comparison with deliberately
    # wrong child processes, not only an in-memory equality helper.
    for altered in [base, *wrong[:-1]]:
        command = [sys.executable, '-c', 'import sys; print(sys.argv[1])', json.dumps(altered)]
        observed = execute(command, 5)
        assert bool(differences(base, observed)) == (altered is not base)
    try:
        execute([sys.executable, '-c', 'raise SystemExit(2)'], 5)
    except RuntimeError:
        pass
    else:
        raise AssertionError('nonzero child exit was accepted as a runtime observation')
    print(f'Runtime differential harness self-checks passed: {len(wrong) + 4}; {len(wire.REGISTRY)} opcodes inventoried')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-host', type=Path)
    parser.add_argument('--candidate-host', type=Path)
    parser.add_argument('--baseline-library', type=Path, help='record the separately built runtime library hash')
    parser.add_argument('--candidate-library', type=Path, help='record the separately built runtime library hash')
    parser.add_argument('--classpath', help='also compile source/debug/lifecycle fixtures with this classpath')
    parser.add_argument('--work', type=Path, help='new artifact directory; retained on success and failure')
    parser.add_argument('--seed', type=int, action='append', help='repeatable; default 7, 91, 20261017')
    parser.add_argument('--budget-max', type=int, default=512, help='largest enumerated budget; capped cases are reported incomplete')
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('--baseline-revision', default='unspecified')
    parser.add_argument('--candidate-revision', default='unspecified')
    parser.add_argument('--baseline-engine', choices=['tree', 'vm'], default='tree')
    parser.add_argument('--candidate-engine', choices=['tree', 'vm'], default='tree')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--shrink-attempts', type=int, default=24)
    args = parser.parse_args()
    if args.self_test:
        self_test(); return
    if not args.baseline_host or not args.candidate_host:
        parser.error('--baseline-host and --candidate-host are required unless --self-test is used')
    if args.budget_max < 1 or args.timeout <= 0 or args.shrink_attempts < 0:
        parser.error('invalid budget, timeout or shrink limit')
    hosts = [args.baseline_host.resolve(), args.candidate_host.resolve()]
    for host in hosts:
        if not host.is_file(): parser.error(f'host does not exist: {host}')
    if hosts[0] == hosts[1] and args.baseline_engine == args.candidate_engine:
        parser.error('baseline and candidate must be distinct executables or engines')
    engines = [args.baseline_engine, args.candidate_engine]
    build = ROOT / 'build/runtime-perf'; build.mkdir(parents=True, exist_ok=True)
    work = args.work.resolve() if args.work else Path(tempfile.mkdtemp(prefix='r03-', dir=build))
    if args.work:
        work.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    all_cases = cases(args.seed if args.seed is not None else [7, 91, 20261017], bool(args.classpath))
    manifest = {'protocol': PROTOCOL, 'engines': engines, 'seed': args.seed if args.seed is not None else [7, 91, 20261017], 'created_unix': time.time(),
                'baseline': {'path': str(hosts[0]), 'sha256': digest(hosts[0]), 'revision': args.baseline_revision},
                'candidate': {'path': str(hosts[1]), 'sha256': digest(hosts[1]), 'revision': args.candidate_revision},
                'adapter_sha256': digest(ADAPTER),
                'harness_sha256': digest(__file__), 'cases': []}
    for label, library in [('baseline', args.baseline_library), ('candidate', args.candidate_library)]:
        manifest[label]['runtime_library'] = ({'path': str(library.resolve()), 'sha256': digest(library)}
                                             if library else {'status': 'not supplied; see external build manifest'})
    manifest['compiler_inputs'] = [{'path': entry, 'sha256': digest(entry)} for entry in (args.classpath or '').split(os.pathsep)
                                  if entry and Path(entry).is_file()]
    write_json(work / 'manifest.json', manifest)
    checks = 0; static_opcodes = set(); incomplete = []
    report = {'status': 'running', 'checks': 0, 'cases': [], 'failures': [], 'incomplete_budget_sweeps': incomplete}

    def compile_case(case, folder):
        folder.mkdir(parents=True, exist_ok=True)
        binary = folder / (case.name + '.abd')
        if case.source:
            source = folder / (case.name + '.azs'); source.write_text(case.source, encoding='utf-8')
            command = ['java', '-cp', args.classpath, 'azertia.Main', 'compile', str(source), '-g', '-o', str(binary)]
            result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', env=env, timeout=60, cwd=ROOT)
            write_json(folder / 'compile.json', {'command': command, 'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
            if result.returncode: raise RuntimeError(f'fixture compilation failed: {case.name}: {result.stderr}')
        else:
            binary.write_bytes(case.binary)
        return binary

    def pair(case, binary, steps, folder):
        results = []
        for label, host, engine in zip(['baseline', 'candidate'], hosts, engines):
            command = [str(host), str(binary), '--steps', str(steps), '--engine', engine, *case.options]
            # Commands are retained verbatim; shlex rendering is for convenient reproduction only.
            try:
                value = execute(command, args.timeout)
            except Exception as error:
                write_json(folder / f'{steps}.{label}.failure.json', {'command': command, 'error': str(error),
                           **getattr(error, 'details', {})})
                raise
            write_json(folder / f'{steps}.{label}.json', {'command': command, 'observation': value})
            results.append(value)
        return results

    def shrink(case, steps, folder):
        if case.recipe is None or not args.shrink_attempts: return None
        recipe = list(case.recipe); attempts = 0; width = max(1, len(recipe) // 2)
        while width and attempts < args.shrink_attempts:
            reduced = False
            for start in range(0, len(recipe), width):
                if attempts >= args.shrink_attempts: break
                candidate_recipe = recipe[:start] + recipe[start + width:]
                candidate = generated(case.seed, candidate_recipe)
                attempt = folder / f'attempt-{attempts}'; attempts += 1
                binary = compile_case(candidate, attempt)
                observed = pair(candidate, binary, steps, attempt)
                if compare_engines(*observed, *engines):
                    recipe = candidate_recipe; reduced = True; break
            if not reduced: width //= 2
        minimized = generated(case.seed, recipe); binary = compile_case(minimized, folder / 'minimal')
        write_json(folder / 'recipe.json', {'seed': case.seed, 'recipe': recipe, 'steps': steps, 'attempts': attempts,
            'commands': [[str(host), str(binary), '--steps', str(steps), '--engine', engine, *case.options]
                         for host, engine in zip(hosts, engines)]})
        return str(binary)

    try:
        for case in all_cases:
            folder = work / case.name; binary = compile_case(case, folder)
            reader = wire.WireReader(); reader.read(binary.read_bytes()); static_opcodes.update(reader.opcodes)
            manifest['cases'].append({'name': case.name, 'seed': case.seed, 'recipe': case.recipe,
                'binary': str(binary), 'sha256': digest(binary), 'options': case.options,
                'wire_opcodes_present': sorted(reader.opcodes), 'sweep': case.sweep})
            write_json(work / 'manifest.json', manifest)
            observations = pair(case, binary, DEFAULT_STEPS, folder)
            mismatch = compare_engines(*observations, *engines)
            if mismatch:
                minimized = shrink(case, DEFAULT_STEPS, folder / 'shrink')
                raise AssertionError(json.dumps({'case': case.name, 'steps': DEFAULT_STEPS, 'differences': mismatch, 'minimal': minimized}))
            for observation in observations: semantic_checks(case, observation)
            checks += 1
            used = DEFAULT_STEPS - observations[0]['remaining_steps']
            sweep_end = 0
            if case.sweep:
                if used + 1 > args.budget_max:
                    incomplete.append({'case': case.name, 'needed': used + 1, 'cap': args.budget_max})
                sweep_end = min(used + 1, args.budget_max)
                for steps in range(1, sweep_end + 1):
                    result = pair(case, binary, steps, folder)
                    mismatch = compare_engines(*result, *engines)
                    if mismatch:
                        raise AssertionError(json.dumps({'case': case.name, 'steps': steps, 'differences': mismatch}))
                    checks += 1
            report['cases'].append({'name': case.name, 'steps_used': used, 'budgets_checked': [1, sweep_end] if sweep_end else [],
                                     'status': 'passed'})
            report['checks'] = checks; write_json(work / 'report.json', report)
        report['status'] = 'passed' if not incomplete else 'incomplete'
        report['wire_opcodes_present'] = sorted(static_opcodes)
        report['wire_opcodes_not_present'] = sorted(set(wire.REGISTRY) - static_opcodes)
        report['coverage_note'] = 'Static wire presence is not execution/branch coverage; see SEMANTICS.md.'
        write_json(work / 'report.json', report)
        print(f'Runtime differential: {checks} independent-process pairs, {len(all_cases)} cases; {report["status"]}. Artifacts: {work}')
        if incomplete: raise SystemExit(1)
    except Exception as error:
        report['status'] = 'failed'; report['checks'] = checks
        report['failures'].append(str(error)); write_json(work / 'report.json', report)
        print(f'Runtime differential failed; all artifacts retained at {work}: {error}', file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == '__main__':
    main()
