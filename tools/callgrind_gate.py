#!/usr/bin/env python3
"""Deterministic per-call instruction counts of the runtime benchmark cases.

Runs the reference and candidate benchmark hosts (runtime-benchmark-host for
the tree engine, runtime-benchmark-vm-host for the VM) under callgrind with two
call counts and reports the steady per-call instruction difference, which
excludes load, flush and process start. Used for the pure-refactor gates
(callgrind +0.5% per case) of docs/runtime-performance-roadmap/ENGINE_SPLIT.md.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'benchmarks/runtime'))
import generate  # noqa: E402

HOSTS = {'tree': 'runtime-benchmark-host', 'vm': 'runtime-benchmark-vm-host'}


def instructions(host, binary, size, expected, calls, output):
    command = ['valgrind', '--tool=callgrind', f'--callgrind-out-file={output}', str(host),
               '--script', str(binary), '--argument', str(size), '--expected', str(expected),
               '--calls', str(calls), '--batches', '1', '--warmup', '0']
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{host} failed: {result.stderr[-2000:]}')
    match = re.search(r'^summary:\s+(\d+)', Path(output).read_text(), re.MULTILINE)
    if not match:
        raise RuntimeError(f'no callgrind summary in {output}')
    return int(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True, help='Release build directory of the reference')
    parser.add_argument('--candidate', type=Path, required=True, help='Release build directory of the candidate')
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--work', type=Path, required=True, help='new directory')
    parser.add_argument('--cases', default='integer_loop,branch_loop,call_0,call_1,call_16')
    parser.add_argument('--engines', default='tree,vm')
    parser.add_argument('--size', type=int, default=512)
    parser.add_argument('--limit', type=float, default=0.5, help='allowed per-call increase in percent')
    args = parser.parse_args()
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    report = {'size': args.size, 'limit_percent': args.limit, 'cases': []}
    failed = False
    for case in args.cases.split(','):
        source = work / f'{case}.azs'; source.write_text(generate.source(case), encoding='utf-8')
        binary = work / f'{case}.abd'
        subprocess.run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', str(source), '-o', str(binary)],
                       check=True, capture_output=True, env=env)
        for engine in args.engines.split(','):
            counts = {}
            for side, root in (('reference', args.reference), ('candidate', args.candidate)):
                host = root / 'interpreter' / HOSTS[engine]
                expected = generate.expected(case, args.size)
                one = instructions(host, binary, args.size, expected, 1, work / f'{case}.{engine}.{side}.1.out')
                six = instructions(host, binary, args.size, expected, 6, work / f'{case}.{engine}.{side}.6.out')
                counts[side] = (six - one) / 5
            change = 100.0 * (counts['candidate'] - counts['reference']) / counts['reference']
            entry = {'case': case, 'engine': engine, 'reference_per_call': counts['reference'],
                     'candidate_per_call': counts['candidate'], 'change_percent': round(change, 3)}
            report['cases'].append(entry)
            failed |= change > args.limit
            print(f'{case:>14} {engine:>4}: {counts["reference"]:>12.0f} -> {counts["candidate"]:>12.0f} '
                  f'per call ({change:+.3f}%)', flush=True)
    (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if failed:
        print(f'Per-call instruction increase above {args.limit}% in at least one case', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
