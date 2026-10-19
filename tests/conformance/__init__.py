"""Ownership and lifecycle conformance corpus (stage "ownership").

Every case states its expected observable behaviour independently of any
engine: destructor event order, results, errors, trace frames, budgets and
teardown. The expectations come from the verified tree-interpreter
specification recorded for R11b (destructor order, first-error and trace
rules, budget and depth accounting, owner transfer). The tree engine must meet
them, and the VM is compared against the tree field by field.

Groups live in sibling modules; each exposes cases(). Case names start with
"own-" and a group letter so that --only can select a group.
"""
import importlib

# Shared prelude. event() records one int; probe() also records active_calls,
# remaining_steps and the published trace while it runs (observation protocol 2).
EVENT = 'extern int event(int):6dff0001;\n'
PROBE = 'extern int probe(int):6dff0005;\n'
PRELUDE = (EVENT + PROBE +
           'class M { int n; M(int v){ n = v; } ~M(){ event(0-n); } }\n'
           'M mk(int v){ M t(v); return t; }\n'
           'M * mkp(int v){ M * p(v); return p; }\n'
           'class Bad { int n; Bad(int v){ n = v; } ~Bad(){ event(100+n); int z = 0; int q = 1/z; } }\n')

GROUPS = ('scopes', 'temporaries', 'assignment', 'destruction', 'heap', 'errors', 'teardown')


def cases():
    result = []
    for group in GROUPS:
        try:
            module = importlib.import_module(f'conformance.{group}')
        except ModuleNotFoundError as error:
            if error.name != f'conformance.{group}':
                raise
            continue
        result.extend(module.cases())
    names = [item.name for item in result]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise AssertionError(f'duplicate conformance case names: {duplicates}')
    for item in result:
        if item.stage != 'ownership' or not item.name.startswith('own-'):
            raise AssertionError(f'conformance case {item.name} must use stage ownership and an own- name')
    return result
