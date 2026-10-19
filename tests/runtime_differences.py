"""Closed registry of permitted engine differences (docs/runtime-performance-roadmap/ENGINE_SPLIT.md, section 7).

A registered case passes only when its difference is validated mechanically:
- native_stack: the reference fails with the native-stack limit and the VM
  does not; re-running the reference on a 1 GiB thread stack with
  max_stack_bytes 0 must reproduce the VM observation exactly.
- vm_workspace_limit: the VM fails with the workspace limit; re-running it
  with the entry's larger workspace must reproduce the reference exactly.
- transitional_vm_refusal: the VM refuses the program while preparing it at
  flush with exactly the registered message, naming the step that removes it.
An entry whose case shows no difference is stale and fails the run, so the
registry cannot outlive the behaviour it describes.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'tests/runtime_differences.json'
KINDS = {'native_stack', 'vm_workspace_limit', 'transitional_vm_refusal'}
NATIVE_STACK = 'Script native stack limit exceeded'
WORKSPACE = 'VM workspace limit exceeded'
LARGE_STACK = 1 << 30


class Registry:
    def __init__(self, entries):
        self.entries = entries
        self.by_case = {}
        for entry in entries:
            for name in entry['cases']:
                if name in self.by_case:
                    raise ValueError(f'case {name} is registered twice')
                self.by_case[name] = entry
        self.used = set()

    def lookup(self, case, engine):
        entry = self.by_case.get(case)
        return entry if entry and entry['engine'] == engine else None

    def validate(self, entry, case, reference, candidate, mismatch, rerun, compare):
        """Raise unless the registered difference is proven.

        rerun(side, extra) re-observes one side with extra host arguments;
        compare(reference_like, candidate_like) returns the engine-aware mismatch list.
        """
        if not mismatch:
            return False
        kind = entry['kind']
        if kind == 'transitional_vm_refusal':
            error = candidate.get('error') or {}
            if candidate.get('phase') != 'flush' or error.get('message') != entry['message']:
                raise AssertionError(f'{case}: registered refusal {entry["id"]} expected flush failure '
                                     f'{entry["message"]!r}, got phase {candidate.get("phase")!r} error {error!r}')
            if (reference.get('error') or {}).get('message') == entry['message']:
                raise AssertionError(f'{case}: the reference reports the registered VM refusal itself')
        elif kind == 'native_stack':
            if (reference.get('error') or {}).get('message') != NATIVE_STACK:
                raise AssertionError(f'{case}: native_stack entry but the reference did not hit the native stack limit')
            again = rerun('reference', ['--thread-stack-bytes', str(LARGE_STACK), '--stack-bytes', '0'])
            problems = compare(again, candidate)
            if problems:
                raise AssertionError(f'{case}: reference on a large stack does not reproduce the VM: {problems[:8]}')
        elif kind == 'vm_workspace_limit':
            if (candidate.get('error') or {}).get('message') != WORKSPACE:
                raise AssertionError(f'{case}: vm_workspace_limit entry but the VM did not hit its workspace limit')
            again = rerun('candidate', ['--workspace-bytes', str(entry['workspace_bytes'])])
            problems = compare(reference, again)
            if problems:
                raise AssertionError(f'{case}: VM with a larger workspace does not reproduce the reference: {problems[:8]}')
        self.used.add(entry['id'])
        return True

    def stale(self, selected_cases, engine):
        """Entries for cases run here that never showed their difference."""
        return sorted(entry['id'] for entry in self.entries
                      if entry['engine'] == engine and entry['id'] not in self.used
                      and any(name in selected_cases for name in entry['cases']))


def load(path=REGISTRY):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if data.get('version') != 1 or not isinstance(data.get('entries'), list):
        raise ValueError('unsupported runtime difference registry')
    ids = set()
    for entry in data['entries']:
        missing = {'id', 'kind', 'engine', 'cases', 'reason'} - set(entry)
        if missing or entry['kind'] not in KINDS or not entry['cases'] or entry['id'] in ids:
            raise ValueError(f'invalid registry entry: {entry!r}')
        if entry['kind'] == 'transitional_vm_refusal' and not (entry.get('message') and entry.get('removed_by')):
            raise ValueError(f'refusal entry {entry["id"]} needs message and removed_by')
        if entry['kind'] == 'vm_workspace_limit' and not entry.get('workspace_bytes'):
            raise ValueError(f'workspace entry {entry["id"]} needs workspace_bytes')
        ids.add(entry['id'])
    return Registry(data['entries'])


def self_test():
    """Negative controls: every validator must reject a difference it cannot prove."""
    def observation(**fields):
        value = {'engine': 'tree', 'phase': 'complete', 'error': None, 'return': 1}
        value.update(fields); return value

    def compare(left, right):
        keys = (set(left) | set(right)) - {'engine'}
        return [key for key in sorted(keys) if left.get(key) != right.get(key)]
    refusal = {'id': 'r', 'kind': 'transitional_vm_refusal', 'engine': 'vm', 'cases': ['x'],
               'message': 'VM backend does not support buffers', 'removed_by': 'S4.3', 'reason': 'test'}
    stack = {'id': 's', 'kind': 'native_stack', 'engine': 'vm', 'cases': ['y'], 'reason': 'test'}
    workspace = {'id': 'w', 'kind': 'vm_workspace_limit', 'engine': 'vm', 'cases': ['z'],
                 'workspace_bytes': 1 << 30, 'reason': 'test'}
    registry = Registry([refusal, stack, workspace])
    tree = observation()
    refused = observation(engine='vm', phase='flush', error={'message': refusal['message']}, **{'return': None})
    assert registry.validate(refusal, 'x', tree, refused, compare(tree, refused), None, compare)
    checks = 1
    for bad in [observation(engine='vm', phase='invoke', error={'message': refusal['message']}),
                observation(engine='vm', phase='flush', error={'message': 'other'})]:
        try: registry.validate(refusal, 'x', tree, bad, compare(tree, bad), None, compare)
        except AssertionError: checks += 1
        else: raise AssertionError('refusal validator accepted a wrong refusal')
    assert not registry.validate(refusal, 'x', tree, tree, [], None, compare)  # no difference: unused
    stack_reference = observation(phase='invoke', error={'message': NATIVE_STACK}, **{'return': None})
    vm_success = observation(engine='vm')
    assert registry.validate(stack, 'y', stack_reference, vm_success, compare(stack_reference, vm_success),
                             lambda side, extra: observation(), compare)
    checks += 1
    for reproduced in [stack_reference, observation(**{'return': 2})]:
        try: registry.validate(stack, 'y', stack_reference, vm_success, compare(stack_reference, vm_success),
                               lambda side, extra, r=reproduced: r, compare)
        except AssertionError: checks += 1
        else: raise AssertionError('native_stack validator accepted an unreproduced difference')
    try: registry.validate(stack, 'y', tree, vm_success, ['return'], lambda side, extra: tree, compare)
    except AssertionError: checks += 1
    else: raise AssertionError('native_stack validator accepted a reference without the stack failure')
    vm_limited = observation(engine='vm', phase='invoke', error={'message': WORKSPACE}, **{'return': None})
    assert registry.validate(workspace, 'z', tree, vm_limited, compare(tree, vm_limited),
                             lambda side, extra: observation(engine='vm'), compare)
    checks += 1
    try: registry.validate(workspace, 'z', tree, vm_limited, compare(tree, vm_limited),
                           lambda side, extra: vm_limited, compare)
    except AssertionError: checks += 1
    else: raise AssertionError('workspace validator accepted an unreproduced difference')
    stale = Registry([dict(refusal, id='stale', cases=['q'])])
    assert stale.stale({'q'}, 'vm') == ['stale'] and stale.stale({'other'}, 'vm') == []
    checks += 1
    for broken in [{'version': 1, 'entries': [dict(refusal, kind='silent')]},
                   {'version': 1, 'entries': [dict(refusal, message='')]},
                   {'version': 1, 'entries': [refusal, dict(refusal, id='r2')]}]:
        path = ROOT / 'build/tmp/runtime-differences-self-test.json'
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(broken), encoding='utf-8')
        try: load(path)
        except ValueError: checks += 1
        else: raise AssertionError(f'registry accepted {broken!r}')
    load()
    print(f'Runtime difference registry self-checks passed: {checks}')


if __name__ == '__main__':
    import sys
    if sys.argv[1:] != ['--self-test']:
        raise SystemExit('usage: runtime_differences.py --self-test')
    self_test()
