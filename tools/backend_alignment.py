#!/usr/bin/env python3
"""Run every end-to-end suite with each runner invocation executed by both engines.

Wrapper runners (azscript-run, numeric-module-host) run each invocation on the
tree engine, which the suite judges as usual, and again with
AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1. The two runs must agree byte for byte on
exit status, stdout and stderr. The only accepted difference is an explicit VM
refusal while preparing the program, and each (suite, refusal message) pair
must be listed with its exact count in tests/runtime_differences.json
("suite_refusals"); an unlisted refusal, a changed count or any other
difference fails. JNI invocations are not dual-run: the JVM loads one engine.

With --jit MODE[,MODE...] each invocation also runs on the VM once per mode
with AZSCRIPT_JIT=MODE; each such run must be byte-identical to the plain VM
run, refusals included, since the JIT tier is part of the VM engine.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'tests/runtime_differences.json'
REFUSALS = ('VM backend does not support', 'Scalar VM does not support')
SUITES = ('math_library', 'containers_library', 'end_to_end', 'classes_end_to_end',
          'literal_objects_end_to_end', 'generics_end_to_end', 'buffers_end_to_end',
          'loops_end_to_end', 'inheritance_end_to_end', 'virtual_methods_end_to_end',
          'limits_end_to_end', 'type_safety_end_to_end', 'runtime_trace_end_to_end',
          'language_ops_end_to_end', 'address_end_to_end', 'numeric_slots_end_to_end',
          'compact_exec_end_to_end', 'hint_linking_end_to_end', 'extern_library_end_to_end')


def wrapper(real, runner, modes=()):
    # Every run reads /dev/null; the suite receives the tree run's output.
    real = shlex.quote(str(real))
    third = ''.join(f'AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1 AZSCRIPT_JIT={shlex.quote(mode)} {real} "$@" </dev/null '
                    f'>"$d/jit{index}.out" 2>"$d/jit{index}.err"; echo $? > "$d/jit{index}.rc"\n'
                    for index, mode in enumerate(modes))
    return f'''#!/bin/bash
mkdir -p "$AZSCRIPT_ALIGNMENT_RECORDS/{runner}"
d=$(mktemp -d "$AZSCRIPT_ALIGNMENT_RECORDS/{runner}/run.XXXXXXXX")
printf '%s\\0' "$@" > "$d/args"
{real} "$@" </dev/null >"$d/tree.out" 2>"$d/tree.err"; echo $? > "$d/tree.rc"
AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1 {real} "$@" </dev/null >"$d/vm.out" 2>"$d/vm.err"; echo $? > "$d/vm.rc"
{third}cat "$d/tree.out"; cat "$d/tree.err" >&2; exit "$(cat "$d/tree.rc")"
'''


def refusal(stderr):
    for line in stderr.decode('utf-8', 'replace').splitlines():
        if any(text in line for text in REFUSALS):
            return line.strip()
    return None


def collect(records, runner, modes):
    result = []
    for folder in sorted(records.iterdir()):
        sides = {}
        for side in ('tree', 'vm', *(f'jit{index}' for index in range(len(modes)))):
            # A run the suite's own timeout interrupted leaves no exit code.
            if not (folder / f'{side}.rc').exists():
                sides[side] = {'rc': -1, 'out': b'', 'err': b'(no exit status: the invocation was interrupted)'}
                continue
            sides[side] = {'rc': int((folder / f'{side}.rc').read_text()),
                           'out': (folder / f'{side}.out').read_bytes(), 'err': (folder / f'{side}.err').read_bytes()}
        arguments = (folder / 'args').read_bytes().split(b'\0')[:-1]
        result.append({'runner': runner, 'folder': str(folder), 'args': [a.decode('utf-8', 'replace') for a in arguments],
                       **sides})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, default=ROOT / 'build/native', help='CMake build directory')
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--work', type=Path, required=True, help='new directory for wrappers and records')
    parser.add_argument('--only', help='comma-separated suite names (refusal counts are checked for these only)')
    parser.add_argument('--suggest', type=Path, help='write observed refusals as registry entries for review')
    parser.add_argument('--jit', help='also run each invocation on the VM with AZSCRIPT_JIT set to each of these comma-separated modes')
    args = parser.parse_args()
    modes = args.jit.split(',') if args.jit else []
    native = args.native.resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    interpreter = work / 'interpreter'
    interpreter.mkdir()
    registry = json.loads(REGISTRY.read_text(encoding='utf-8'))
    expected = {}
    for entry in registry.get('suite_refusals', []):
        key = (entry['suite'], entry['message'])
        if key in expected or not entry.get('removed_by') or entry['count'] <= 0:
            raise SystemExit(f'invalid suite refusal entry: {entry!r}')
        expected[key] = entry['count']
    runners = ('azscript-run', 'numeric-module-host')
    for name in runners:
        path = interpreter / name
        path.write_text(wrapper(native / 'interpreter' / name, name, modes), encoding='utf-8')
        path.chmod(0o755)
    signer = native / 'interpreter/azscript-sign-extern'
    run, host = interpreter / 'azscript-run', interpreter / 'numeric-module-host'
    arguments = {
        'math_library': ['--runner', run], 'containers_library': ['--runner', run],
        'end_to_end': ['--runner', run, '--bridge', ROOT / 'build/java/abdJavaInvoker.jar'],
        'loops_end_to_end': ['--runner', run], 'limits_end_to_end': ['--runner', run, '--host', host],
        'address_end_to_end': ['--runner', run, '--host', host], 'numeric_slots_end_to_end': ['--host', host],
        'extern_library_end_to_end': ['--runner', run, '--signer', signer, '--libdir', native / 'interpreter'],
    }
    for name in ('classes_end_to_end', 'type_safety_end_to_end', 'runtime_trace_end_to_end', 'language_ops_end_to_end'):
        arguments[name] = ['--runner', run]
    for name in ('literal_objects_end_to_end', 'generics_end_to_end', 'buffers_end_to_end', 'inheritance_end_to_end',
                 'virtual_methods_end_to_end', 'compact_exec_end_to_end', 'hint_linking_end_to_end'):
        arguments[name] = ['--runner', run, '--host', host]
    selected = args.only.split(',') if args.only else list(SUITES)
    unknown = sorted(set(selected) - set(SUITES))
    if unknown:
        raise SystemExit(f'unknown suites: {unknown}')
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND', 'AZSCRIPT_JIT'):
        env.pop(key, None)
    report = {'native': str(native), 'suites': {}, 'failures': []}
    for name in selected:
        suite_records = work / 'records' / name
        suite_records.mkdir(parents=True)
        command = [sys.executable, ROOT / f'tests/{name}.py', '--classpath', args.classpath, *arguments[name]]
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env={**env, 'AZSCRIPT_ALIGNMENT_RECORDS': str(suite_records)},
                                capture_output=True, text=True)
        (work / f'{name}.out').write_text(result.stdout + '\n--- stderr ---\n' + result.stderr, encoding='utf-8')
        invocations = [item for runner in runners if (suite_records / runner).exists()
                       for item in collect(suite_records / runner, runner, modes)]
        refused, mismatched = {}, []
        for item in invocations:
            tree, vm = item['tree'], item['vm']
            differing = [(mode, item[f'jit{index}']) for index, mode in enumerate(modes)
                         if (item[f'jit{index}']['rc'], item[f'jit{index}']['out'], item[f'jit{index}']['err']) != (vm['rc'], vm['out'], vm['err'])]
            if differing:
                mode, jit = differing[0]
                mismatched.append({'runner': item['runner'], 'args': item['args'], 'records': item['folder'], 'jit_mode': mode,
                                   'jit_exit': [vm['rc'], jit['rc']], 'stdout_equal': vm['out'] == jit['out'],
                                   'stderr': [vm['err'][:400].decode('utf-8', 'replace'), jit['err'][:400].decode('utf-8', 'replace')]})
                continue
            if (tree['rc'], tree['out'], tree['err']) == (vm['rc'], vm['out'], vm['err']):
                continue
            message = refusal(vm['err']) if vm['rc'] else None
            if message and not refusal(tree['err']):
                refused[message] = refused.get(message, 0) + 1
                continue
            mismatched.append({'runner': item['runner'], 'args': item['args'], 'records': item['folder'],
                               'exit': [tree['rc'], vm['rc']], 'stdout_equal': tree['out'] == vm['out'],
                               'stderr': [tree['err'][:400].decode('utf-8', 'replace'), vm['err'][:400].decode('utf-8', 'replace')]})
        listed = {message: count for (suite, message), count in expected.items() if suite == name}
        problems = []
        if result.returncode:
            problems.append(f'suite exited {result.returncode}')
        if mismatched:
            problems.append(f'{len(mismatched)} invocations differ between engines')
        for message in sorted(set(refused) | set(listed)):
            if refused.get(message, 0) != listed.get(message, 0):
                problems.append(f'refusal count for {message!r}: observed {refused.get(message, 0)}, listed {listed.get(message, 0)}')
        report['suites'][name] = {'exit': result.returncode, 'invocations': len(invocations),
                                  'identical': len(invocations) - len(mismatched) - sum(refused.values()),
                                  'refusals': refused, 'mismatches': mismatched, 'problems': problems}
        report['failures'] += [f'{name}: {problem}' for problem in problems]
        print(f'{name}: exit={result.returncode} invocations={len(invocations)} '
              f'refused={sum(refused.values())} mismatched={len(mismatched)}', flush=True)
    (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if args.suggest:
        suggestion = [{'suite': suite, 'message': message, 'count': count, 'removed_by': 'TODO'}
                      for suite, item in report['suites'].items() for message, count in sorted(item['refusals'].items())]
        args.suggest.write_text(json.dumps(suggestion, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    total = sum(item['invocations'] for item in report['suites'].values())
    identical = sum(item['identical'] for item in report['suites'].values())
    sides = f'both engines and AZSCRIPT_JIT={args.jit}' if args.jit else 'both engines'
    print(f'Backend alignment: {identical}/{total} invocations identical on {sides}; report {work / "report.json"}')
    for failure in report['failures']:
        print(f'  FAILED {failure}', file=sys.stderr)
    return 1 if report['failures'] else 0


if __name__ == '__main__':
    sys.exit(main())
