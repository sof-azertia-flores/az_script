#!/usr/bin/env python3
"""O1/O2 result-checked VM/LLVM measurements. Run without other builds or tests."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import time

from bench_compiler_optimization import workloads

ROOT = Path(__file__).resolve().parents[1]
CASES = ('integer_loop', 'branch_loop', 'call_0', 'call_1', 'call_4', 'call_16',
         'repeated_expression', 'scalar_call', 'range_loop', 'object_fields',
         'object_copy', 'virtual_monomorphic', 'virtual_alternating')


def source(name):
    if name == 'repeated_expression':
        return workloads.wrap('int a=(i&127)*7;int b=(i&127)*7;total+=a+b;')
    if name == 'scalar_call':
        return ('int value(int x){int a=x&127;return a*a+a;}\n'
                + workloads.wrap('int v=value(i);total+=v;'))
    if name == 'range_loop':
        return workloads.wrap('total+=((n&7)+1)*3;')
    return workloads.source(name)


def expected(name, n):
    if name == 'repeated_expression':
        return sum((i & 127) * 14 for i in range(n))
    if name == 'scalar_call':
        return sum((i & 127) ** 2 + (i & 127) for i in range(n))
    if name == 'range_loop':
        return ((n & 7) + 1) * 3 * n
    return workloads.expected(name, n)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--native', type=Path, required=True, help='Release CMake build')
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--modes', default='off', help='off,on:3+llvm (LLVM must be built)')
    parser.add_argument('--rounds', type=int, default=7)
    parser.add_argument('--batches', type=int, default=5)
    parser.add_argument('--target-seconds', type=float, default=.1)
    parser.add_argument('--argument', type=int, default=4096)
    parser.add_argument('--only', default=','.join(CASES))
    parser.add_argument('--runtime-revision', default='', help='commit used to build the measured native host')
    args = parser.parse_args()
    if min(args.rounds, args.batches, args.argument, args.target_seconds) <= 0:
        parser.error('sampling parameters must be positive')
    names, modes = args.only.split(','), args.modes.split(',')
    if not set(names) <= set(CASES):
        parser.error('unknown workload')
    args.native = args.native.resolve()
    args.work = args.work.resolve()
    args.work.mkdir(parents=True, exist_ok=False)
    env = {key: value for key, value in os.environ.items()
           if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                          'AZSCRIPT_EXPERIMENTAL_VM_BACKEND') and not key.startswith('AZSCRIPT_JIT')}
    env['DYLD_LIBRARY_PATH' if platform.system() == 'Darwin' else 'LD_LIBRARY_PATH'] = str(args.native / 'interpreter')
    host = args.native / 'interpreter/runtime-benchmark-vm-host'
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    report = {'status': 'running', 'platform': platform.platform(),
              'compiler_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
              'settings': {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
              'host_sha256': digest(host), 'harness_sha256': digest(Path(__file__)),
              'compiler_sha256': {path: digest(Path(path)) for path in args.classpath.split(os.pathsep)},
              'artifacts': {}, 'compile_records': [], 'records': [], 'summary': {}, 'regressions': []}
    (args.work / 'harness.py').write_bytes(Path(__file__).read_bytes())

    def save():
        (args.work / 'report.json').write_text(json.dumps(report, indent=2) + '\n')

    def run(command, extra=None):
        value = subprocess.run(list(map(str, command)), cwd=ROOT, env={**env, **(extra or {})},
                               capture_output=True, text=True, timeout=180)
        if value.returncode:
            raise RuntimeError((command, value.stdout, value.stderr))
        return value.stdout

    def measure(name, level, mode, calls, batches, label):
        stats = args.work / f'{name}-{level}-{mode.replace(":", "-")}-{label}.stats'
        value = json.loads(run([host, '--script', args.work / f'{name}-{level}.abd',
                               '--argument', args.argument, '--expected', expected(name, args.argument),
                               '--calls', calls, '--batches', batches, '--warmup', 3],
                              {'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)}))
        assert value['ok'] and value['backend'] == 'vm' and value['build_type'] == 'Release', value
        sessions = [json.loads(line) for line in stats.read_text().splitlines()] if stats.exists() else []
        if mode != 'off':
            assert sessions, (mode, 'missing JIT statistics')
            assert not sum(item['violations'] for item in sessions), sessions
            assert not any(reason.startswith('compiler error') for item in sessions for reason in item['refusals']), sessions
            if not name.startswith(('object_', 'virtual_')):
                assert sum(item['entries'] for item in sessions) > 0, (name, level, mode, 'no compiled execution')
        return value, sessions

    try:
        # Compile separately from runtime measurements; include JVM startup and
        # output I/O consistently in both levels' reported compiler cost.
        for name in names:
            path = args.work / f'{name}.azs'
            path.write_text(source(name))
            report['artifacts'][name] = {}
            for round_number in range(args.rounds):
                for level in (('O1', 'O2') if round_number % 2 == 0 else ('O2', 'O1')):
                    binary, code = args.work / f'{name}-{level}.abd', args.work / f'{name}-{level}.json'
                    start = time.perf_counter()
                    run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-' + level,
                         '-o', binary, '--exec-json', code])
                    report['compile_records'].append({'case': name, 'level': level, 'round': round_number,
                                                      'seconds': time.perf_counter() - start})
                    functions = json.loads(code.read_text())['f']
                    report['artifacts'][name][level] = {
                        'bytes': binary.stat().st_size, 'sha256': digest(binary),
                        'local_slots': sum(function['local-count'] for function in functions),
                        'function_slots': {str(function['id']): function['local-count'] for function in functions}}
        report['compile_summary'] = {
            name: {level: statistics.median(record['seconds'] for record in report['compile_records']
                                            if record['case'] == name and record['level'] == level)
                   for level in ('O1', 'O2')} for name in names}
        for name in names:
            calls = {}
            for mode in modes:
                calibrated, _ = measure(name, 'O1', mode, 1, 1, 'calibration')
                calls[mode] = max(1, min(1000000, math.ceil(
                    args.target_seconds / calibrated['batches'][0]['timing']['wall_seconds'])))
            variants = [(level, mode) for mode in modes for level in ('O1', 'O2')]
            medians = {mode: [] for mode in modes}
            for round_number in range(args.rounds):
                order = variants[round_number % len(variants):] + variants[:round_number % len(variants)]
                if round_number % 2:
                    order.reverse()
                paired = {mode: {} for mode in modes}
                for level, mode in order:
                    value, sessions = measure(name, level, mode, calls[mode], args.batches, str(round_number))
                    report['records'].append({'case': name, 'level': level, 'mode': mode, 'round': round_number,
                                              'observation': value, 'jit': sessions})
                    paired[mode][level] = statistics.median(batch['timing']['wall_seconds'] / calls[mode]
                                                           for batch in value['batches'])
                for mode in modes:
                    medians[mode].append(paired[mode])
                save()
            report['summary'][name] = {}
            for mode in modes:
                ratio = math.exp(statistics.mean(math.log(pair['O2'] / pair['O1']) for pair in medians[mode]))
                selected = [record for record in report['records'] if record['case'] == name and record['mode'] == mode]
                report['summary'][name][mode] = {
                    'O2_over_O1_hot': ratio, 'round_medians': medians[mode], 'calls_per_batch': calls[mode],
                    'cold_seconds': {level: statistics.median(
                        record['observation']['phases']['cold_to_first_invoke']['wall_seconds']
                        for record in selected if record['level'] == level) for level in ('O1', 'O2')}}
                if ratio > 1.05:
                    report['regressions'].append({'case': name, 'mode': mode, 'O2_over_O1': ratio})
                refusals = {level: sum(session['refusals'].get('too large for native code', 0)
                                       for record in selected if record['level'] == level for session in record['jit'])
                            for level in ('O1', 'O2')}
                assert refusals['O2'] <= refusals['O1'], (name, mode, 'O2 lost native compilation eligibility', refusals)
                print(name, mode, f'O2/O1 hot: {ratio:.3f}', flush=True)
            save()
        report['status'] = 'regression-review' if report['regressions'] else 'passed'
    except Exception as error:
        report['status'], report['error'] = 'failed', str(error)
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
