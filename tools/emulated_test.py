#!/usr/bin/env python3
"""Run the engine comparison corpus on a cross-built runtime under an emulator.

The cross build (for example cmake -DCMAKE_TOOLCHAIN_FILE=cmake/toolchains/
aarch64-linux-gnu.cmake -DAZSCRIPT_BUILD_JNI=OFF) is tested where its
architecture is not native: ctest runs through CMAKE_CROSSCOMPILING_EMULATOR,
and this tool wraps the cross-built observation host in the emulator, collects
a tree reference for the selected corpus stages, compares the VM with it and
replays that reference against every JIT mode. The Java compiler runs on the
host. JNI needs a JVM of the target architecture and is not covered.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, **kwargs):
    print('+', ' '.join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), cwd=ROOT, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True, help='cross build directory')
    parser.add_argument('--emulator', default='qemu-aarch64 -L /usr/aarch64-linux-gnu')
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--work', type=Path, required=True, help='new directory')
    parser.add_argument('--stages', default='scalar,calls,objects')
    parser.add_argument('--modes', default='eager,stress,eager+llvm,stress+llvm,on:2+llvm')
    parser.add_argument('--timeout', type=float, default=120)
    args = parser.parse_args()
    build = args.build.resolve()
    host = build / 'interpreter' / 'observation-host'
    if not host.is_file():
        raise SystemExit(f'missing cross-built host: {host}')
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    # The JIT module is found beside the real host binary, not the wrapper.
    wrapper = work / 'observation-host'
    wrapper.write_text(f'#!/bin/bash\nexec {args.emulator} {shlex.quote(str(host))} "$@"\n', encoding='utf-8')
    wrapper.chmod(0o755)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_JIT', 'AZSCRIPT_JIT_BACKEND'):
        env.pop(key, None)
    failures = []
    reports = {}
    entries_by_mode = {mode: 0 for mode in args.modes.split(',')}
    for stage in args.stages.split(','):
        reference = work / f'{stage}-reference'
        result = run([sys.executable, ROOT / 'tests/vm_execution_end_to_end.py', '--classpath', args.classpath,
                      '--reference-host', wrapper, '--reference-engine', 'tree', '--candidate-host', wrapper,
                      '--candidate-engine', 'vm', '--stage', stage, '--timeout', args.timeout, '--work', reference], env=env)
        reports[f'{stage} vm'] = result.returncode
        if result.returncode:
            failures.append(f'{stage}: vm differs from the tree reference')
            continue
        for mode in args.modes.split(','):
            stats = work / f'{stage}-{mode.replace(":", "").replace("+", "-")}.stats'
            replay = run([sys.executable, ROOT / 'tests/vm_execution_end_to_end.py', '--reference-work', reference,
                          '--candidate-host', wrapper, '--candidate-engine', 'vm', '--timeout', args.timeout,
                          '--work', stats.with_suffix('')], env={**env, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)})
            sessions = [json.loads(line) for line in stats.read_text(encoding='utf-8').splitlines()] if stats.exists() else []
            entries = sum(item['entries'] for item in sessions)
            violations = sum(item['violations'] for item in sessions)
            errors = sorted({reason for item in sessions for reason in item['refusals'] if reason.startswith('compiler error')})
            reports[f'{stage} {mode}'] = {'exit': replay.returncode, 'entries': entries, 'violations': violations, 'errors': errors[:3]}
            entries_by_mode[mode] += entries
            if replay.returncode or violations or errors:
                failures.append(f'{stage} {mode}: exit {replay.returncode}, {violations} violations, compiler errors {errors[:3]}')
    # A stage may compile nothing under a threshold; a mode that never ran
    # native code anywhere proved nothing.
    for mode, entries in entries_by_mode.items():
        if not entries:
            failures.append(f'{mode}: no native code ran in any stage')
    (work / 'report.json').write_text(json.dumps({'build': str(build), 'emulator': args.emulator, 'results': reports,
                                                 'failures': failures}, indent=2) + '\n', encoding='utf-8')
    for name, value in reports.items():
        print(f'{name}: {value}', flush=True)
    for failure in failures:
        print(f'  FAILED {failure}', file=sys.stderr)
    print('Emulated test ' + ('failed' if failures else 'passed') + f'; report {work / "report.json"}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
