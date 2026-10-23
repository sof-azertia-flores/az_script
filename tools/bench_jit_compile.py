#!/usr/bin/env python3
"""Measure how long the LLVM JIT backend takes to compile functions of growing size.

Each shape is a generated script whose main function grows with N. It runs on
the VM under eager+llvm (resume points only at entries and loop heads) and
stress+llvm (a resume point at every reachable pc) with the native size caps
lifted (AZSCRIPT_JIT_NATIVE_LIMITS), and the statistics side channel reports
the slowest native compilation with its MIR operations and resume cells
(resume points times slots, including absent initializers). The result guides native_operation_limit and
native_resume_limit in interpreter/jit/tier.cpp. Run it after a build with the
JIT module; it is a measurement, not part of the validation run.
"""
import argparse
import json
import os
import platform
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def chain(n):
    # One long expression: a register per term.
    return 'int main(){int x=1;return ' + '+'.join(['x'] * n) + ';}'


def statements(n):
    # Straight-line statements over a few locals.
    body = ''.join(f'a=a+{i % 7};b=b^a;' for i in range(n // 2))
    return 'int main(){int a=0;int b=0;' + body + 'return a+b;}'


def locals_(n):
    # Many locals, each declared, then summed.
    declared = ''.join(f'int v{i}={i % 5};' for i in range(n))
    total = '+'.join(f'v{i}' for i in range(n)) or '0'
    return 'int main(){' + declared + 'return ' + total + ';}'


def loop(n):
    # A loop whose body grows: every resume point is inside it.
    body = ''.join(f'a=a+{i % 3};' for i in range(n))
    return 'int main(){int a=0;for(int i=0;i<3;i++){' + body + '}return a;}'


SHAPES = {'chain': chain, 'statements': statements, 'locals': locals_, 'loop': loop}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--native', type=Path, default=ROOT / 'build/native')
    parser.add_argument('--sizes', default='250,500,1000,2000,4000')
    parser.add_argument('--shapes', default=','.join(SHAPES))
    parser.add_argument('--modes', default='eager+llvm,stress+llvm')
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--optimization', choices=('O0', 'O1', 'O2', 'O3'), default='O0')
    parser.add_argument('--native-limits', default='1000000000,1000000000000',
                        help='measurement caps, or default to keep the production caps')
    parser.add_argument('--o2-limit', type=int, help='override the LLVM O2 operation threshold (0 selects O1)')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    runner = args.native / 'interpreter/azscript-run'
    env = {key: value for key, value in os.environ.items()
           if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS') and not key.startswith('AZSCRIPT_JIT')}
    if args.repeats < 1 or args.timeout <= 0 or (args.o2_limit is not None and not 0 <= args.o2_limit <= 0xffffffff):
        parser.error('repeats and timeout must be positive')
    rows = []
    args.report.parent.mkdir(parents=True, exist_ok=True)
    def save():
        args.report.write_text(json.dumps({'native': str(args.native), 'platform': platform.platform(),
                                          'optimization': args.optimization, 'repeats': args.repeats,
                                          'native_limits': args.native_limits, 'o2_limit': args.o2_limit,
                                          'rows': rows}, indent=2) + '\n', encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='jit-compile-', dir=ROOT / 'build') as scratch:
        scratch = Path(scratch)
        for shape in args.shapes.split(','):
            for n in map(int, args.sizes.split(',')):
                source = scratch / f'{shape}-{n}.azs'
                binary = source.with_suffix('.abd')
                source.write_text(SHAPES[shape](n), encoding='utf-8')
                compiled = subprocess.run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', str(source), '-' + args.optimization, '-o', str(binary)],
                                          capture_output=True, text=True, env=env)
                if compiled.returncode:
                    rows.append({'shape': shape, 'n': n, 'status': 'compile failed', 'stderr': compiled.stderr[-400:]})
                    save()
                    continue
                reference = subprocess.run([str(runner), str(binary), '--backend', 'vm'], capture_output=True, text=True,
                                           timeout=args.timeout, env={**env, 'AZSCRIPT_JIT': 'off'})
                for mode, repeat in ((mode, repeat) for mode in args.modes.split(',') for repeat in range(args.repeats)):
                    stats = scratch / f'{shape}-{n}-{mode}-{repeat}.stats'
                    limits = {} if args.native_limits == 'default' else {'AZSCRIPT_JIT_NATIVE_LIMITS': args.native_limits}
                    if args.o2_limit is not None:limits['AZSCRIPT_JIT_LLVM_O2_LIMIT'] = str(args.o2_limit)
                    started = time.monotonic()
                    try:
                        result = subprocess.run([str(runner), str(binary), '--backend', 'vm'], capture_output=True, text=True,
                                                timeout=args.timeout,
                                                env={**env, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats),
                                                     **limits})
                    except subprocess.TimeoutExpired:
                        rows.append({'shape': shape, 'n': n, 'mode': mode, 'repeat': repeat, 'status': 'timeout', 'seconds': args.timeout})
                        save()
                        print(f'{shape:10} n={n:6} {mode:12} timeout after {args.timeout:.0f} s', flush=True)
                        continue
                    wall = time.monotonic() - started
                    sessions = [json.loads(line) for line in stats.read_text(encoding='utf-8').splitlines()] if stats.exists() else []
                    slowest = max(sessions, key=lambda item: item.get('compile_max_ns', 0), default={})
                    matches = (result.returncode, result.stdout, result.stderr) == (reference.returncode, reference.stdout, reference.stderr)
                    native_compiled = sum(item.get('compiled', 0) for item in sessions)
                    violations = sum(item.get('violations', 0) for item in sessions)
                    compiler_errors = sorted({reason for item in sessions for reason in item.get('refusals', {})
                                              if reason.startswith('compiler error')})
                    status = 'mismatch' if not matches else ('ok' if native_compiled else 'refused')
                    if violations or compiler_errors:
                        status = 'analysis violation' if violations else 'compiler error'
                    row = {'shape': shape, 'n': n, 'mode': mode, 'repeat': repeat, 'status': status,
                           'wall_seconds': round(wall, 3), 'compile_seconds': slowest.get('compile_max_ns', 0) / 1e9,
                           'operations': slowest.get('compile_max_operations', 0),
                           'resume_cells': slowest.get('compile_max_resume_cells', 0),
                           'compiled': native_compiled, 'entries': sum(item.get('entries', 0) for item in sessions),
                           'violations': violations, 'compiler_errors': compiler_errors,
                           'refusals': slowest.get('refusals', {})}
                    rows.append(row)
                    save()
                    print(f'{shape:10} n={n:6} {mode:12} {row["compile_seconds"]:8.3f} s  ops={row["operations"]:6} '
                          f'resume_cells={row["resume_cells"]:9}  {row["status"]}', flush=True)
    save()
    print(f'report {args.report}')


if __name__ == '__main__':
    sys.exit(main())
