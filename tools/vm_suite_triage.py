#!/usr/bin/env python3
"""Migration triage: run the end-to-end suites with the experimental VM first.

Each suite gets wrapper runners (azscript-run, numeric-module-host) that set
AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1. Only when the VM explicitly refuses a
program while preparing ("VM backend does not support", "Scalar VM does not
support") does the wrapper rerun that invocation on the default tree backend
and record it. Every other invocation is judged on the VM by the suite's own
expectations. JNI checks are skipped: a refused program cannot be retried
inside the JVM. Not part of build_and_test.py; the counts measure how much of
the suites still needs later migration stages.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REFUSALS = ('VM backend does not support', 'Scalar VM does not support')
SUITES = ('math_library', 'containers_library', 'end_to_end', 'classes_end_to_end',
          'literal_objects_end_to_end', 'generics_end_to_end', 'buffers_end_to_end',
          'loops_end_to_end', 'inheritance_end_to_end', 'virtual_methods_end_to_end',
          'limits_end_to_end', 'type_safety_end_to_end', 'runtime_trace_end_to_end',
          'language_ops_end_to_end', 'address_end_to_end', 'numeric_slots_end_to_end',
          'compact_exec_end_to_end', 'hint_linking_end_to_end', 'extern_library_end_to_end')


def wrapper(real, log):
    # The VM run's output is only replayed when it was not a refusal.
    patterns = ' '.join('-e ' + shlex.quote(text) for text in REFUSALS)
    return f'''#!/bin/bash
out=$(mktemp); err=$(mktemp)
AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1 {shlex.quote(str(real))} "$@" >"$out" 2>"$err"; rc=$?
if grep -q {patterns} "$err"; then
  printf 'refused\\t%s\\n' "$(head -n 1 "$err")" >> {shlex.quote(str(log))}
  rm -f "$out" "$err"; exec {shlex.quote(str(real))} "$@"
fi
printf 'vm\\n' >> {shlex.quote(str(log))}
cat "$out"; cat "$err" >&2; rm -f "$out" "$err"; exit $rc
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, default=ROOT / 'build/native', help='CMake build directory')
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--work', type=Path, required=True, help='new directory for wrappers and logs')
    parser.add_argument('--only', help='comma-separated suite names')
    args = parser.parse_args()
    native = args.native.resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    interpreter = work / 'interpreter'
    interpreter.mkdir()
    for name in ('azscript-run', 'numeric-module-host'):
        path = interpreter / name
        path.write_text(wrapper(native / 'interpreter' / name, work / f'{name}.log'), encoding='utf-8')
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
    selected = args.only.split(',') if args.only else SUITES
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)
    report = {'native': str(native), 'suites': {}}
    failed = False
    for name in selected:
        before = {log: (work / log).read_text().splitlines() if (work / log).exists() else []
                  for log in ('azscript-run.log', 'numeric-module-host.log')}
        command = [sys.executable, ROOT / f'tests/{name}.py', '--classpath', args.classpath, *arguments[name]]
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env, capture_output=True, text=True)
        (work / f'{name}.out').write_text(result.stdout + '\n--- stderr ---\n' + result.stderr, encoding='utf-8')
        lines = []
        for log, old in before.items():
            path = work / log
            lines += (path.read_text().splitlines() if path.exists() else [])[len(old):]
        vm = sum(line == 'vm' for line in lines)
        refused = [line.split('\t', 1)[1] for line in lines if line.startswith('refused\t')]
        report['suites'][name] = {'exit': result.returncode, 'vm_invocations': vm, 'refused_invocations': len(refused),
                                  'refusals': sorted(set(refused))}
        failed |= result.returncode != 0
        print(f'{name}: exit={result.returncode} vm={vm} refused={len(refused)}', flush=True)
    (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    total_vm = sum(item['vm_invocations'] for item in report['suites'].values())
    total_refused = sum(item['refused_invocations'] for item in report['suites'].values())
    print(f'VM-first triage: {total_vm} VM / {total_refused} refused invocations; report {work / "report.json"}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
