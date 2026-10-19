#!/usr/bin/env python3
"""Diagnostic O0/O1 Release comparison; run without concurrent builds/tests.

Uses the runtime host's internal timers and result oracle. Optional baseline
hosts measure native changes independently of compiler optimization. This is
not the complete runtime adoption score (one workload size, extra microcases).
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import statistics
import platform
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('workloads', ROOT / 'benchmarks/runtime/generate.py')
workloads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workloads)


def source(name):
    if name == 'constant_loop':
        return workloads.wrap('total = total + (2+3)*(4+5);')
    if name == 'loop_invariant':
        return workloads.wrap('total = total + (n & 127);')
    if name == 'generic_identity':
        return '<T> T identity(T value){return value;}\n' + workloads.wrap('total = total + identity<int>(i & 127);')
    if name == 'object_constants':
        return 'class Cell{int value=(2+3)*(4+5);}\n' + workloads.wrap('Cell cell;total = total + cell.value;')
    return workloads.source(name)


def expected(name, n):
    if name in ('constant_loop', 'object_constants'):
        return 45 * n
    if name == 'loop_invariant':
        return (n & 127) * n
    if name == 'generic_identity':
        return sum(i & 127 for i in range(n))
    return workloads.expected(name, n)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--native', type=Path, required=True, help='Release CMake directory')
    parser.add_argument('--baseline', type=Path, help='optional prior Release directory with interpreter hosts/libraries')
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--rounds', type=int, default=7)
    parser.add_argument('--batches', type=int, default=5)
    parser.add_argument('--target-seconds', type=float, default=.25)
    parser.add_argument('--argument', type=int, default=4096)
    parser.add_argument('--compile-functions', type=int, default=0, help='also time a generated scalar-function module; includes JVM startup and output I/O')
    parser.add_argument('--only', help='comma-separated workload names')
    args = parser.parse_args()
    if args.rounds < 1 or args.batches < 1 or args.target_seconds <= 0 or args.argument < 1 or args.compile_functions < 0:
        parser.error('sampling settings must be positive')
    args.work.mkdir(parents=True, exist_ok=False)
    (args.work / "harness.py").write_bytes(Path(__file__).read_bytes())
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)

    def run(command, runtime=None):
        current = env.copy()
        if runtime:
            # Copied ELF hosts may retain a RUNPATH to their original build.
            # Pin their actual library directory, and record those file hashes.
            variable = 'DYLD_LIBRARY_PATH' if os.uname().sysname == 'Darwin' else 'LD_LIBRARY_PATH'
            current[variable] = str(runtime.resolve() / 'interpreter')
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=current,
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError((command, result.stdout, result.stderr))
        return result.stdout

    manifest = json.loads((ROOT / 'benchmarks/runtime/manifest.json').read_text())
    names = [name for group in manifest['groups'].values() for name in group['cases']]
    names += ['constant_loop', 'loop_invariant', 'generic_identity', 'object_constants']
    if args.only:
        selected = set(args.only.split(','))
        if not selected <= set(names):
            parser.error('unknown workload in --only')
        names = [name for name in names if name in selected]
    report = {'status': 'running', 'score_eligible': False, 'settings': vars(args).copy(),
              'revision': run(['git', 'rev-parse', 'HEAD']).strip(), 'artifacts': {}, 'hosts': {},
              'platform': platform.platform(), 'harness_sha256': digest(Path(__file__)),
              'sources': {str(path.relative_to(ROOT)): digest(path) for directory in ('compiler/src/main/java', 'interpreter')
                          for path in sorted((ROOT / directory).rglob('*')) if path.suffix in ('.java', '.cpp', '.h')},
              'compiler': {path: digest(Path(path)) for path in args.classpath.split(os.pathsep) if Path(path).is_file()},
              'records': [], 'summary': {}}
    report['settings'] = {key: str(value) if isinstance(value, Path) else value for key, value in report['settings'].items()}
    output = args.work / 'report.json'

    def save():
        output.write_text(json.dumps(report, indent=2) + '\n')

    try:
        for name in names:
            path = args.work / f'{name}.azs'
            path.write_text(source(name), encoding='utf-8')
            report['artifacts'][name] = {}
            for level in ('O0', 'O1'):
                binary = args.work / f'{name}.{level}.abd'
                run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-' + level, '-o', binary])
                report['artifacts'][name][level] = {'bytes': binary.stat().st_size, 'sha256': digest(binary)}
        for engine in ('tree', 'vm'):
            host_name = 'runtime-benchmark-host' if engine == 'tree' else 'runtime-benchmark-vm-host'
            variants = {'O0': (args.native, 'O0'), 'O1': (args.native, 'O1')}
            if args.baseline:
                variants['baseline-O0'] = (args.baseline, 'O0')
            for label, (runtime, _) in variants.items():
                host = runtime / 'interpreter' / host_name
                report['hosts'][engine + '/' + label] = {'path': str(host.resolve()), 'sha256': digest(host),
                    'libraries': {str(p.resolve()): digest(p) for p in (runtime / 'interpreter').glob('*abdInvoker*') if p.is_file()}}
            report['summary'][engine] = {}
            for name in names:
                def measure(label, calls, batches):
                    runtime, level = variants[label]
                    value = json.loads(run([runtime / 'interpreter' / host_name, '--script', args.work / f'{name}.{level}.abd',
                        '--argument', args.argument, '--expected', expected(name, args.argument),
                        '--calls', calls, '--batches', batches, '--warmup', '3', '--max-depth', '256'], runtime))
                    assert value['ok'] and value['backend'] == engine and value['build_type'] == 'Release', value
                    return value
                calibrated = measure('O0', 1, 1)
                calls = max(1, min(1000000, math.ceil(args.target_seconds / calibrated['batches'][0]['timing']['wall_seconds'])))
                samples = {label: [] for label in variants}
                paired = []
                labels = list(variants)
                for round_number in range(args.rounds):
                    order = labels[round_number % len(labels):] + labels[:round_number % len(labels)]
                    if round_number % 2:
                        order.reverse()
                    medians = {}
                    for label in order:
                        value = measure(label, calls, args.batches)
                        report['records'].append({'engine': engine, 'case': name, 'round': round_number, 'variant': label, 'observation': value})
                        timings = [batch['timing']['wall_seconds'] / calls for batch in value['batches']]
                        samples[label].extend(timings)
                        medians[label] = statistics.median(timings)
                    paired.append(medians)
                pairs = [('O0', 'O1')]
                if args.baseline:
                    pairs += [('baseline-O0', 'O0'), ('baseline-O0', 'O1')]
                ratios = {left + '/' + right: math.exp(statistics.mean(math.log(pair[left] / pair[right]) for pair in paired))
                          for left, right in pairs}
                report['summary'][engine][name] = {'calls_per_batch': calls, 'paired_speedups': ratios,
                    'median_seconds_per_call': {label: statistics.median(values) for label, values in samples.items()},
                    'round_medians': paired}
                save()
                print(engine, name, json.dumps(ratios), flush=True)
        if args.compile_functions:
            path = args.work / 'compile-cost.azs'
            path.write_text('\n'.join(f'int f{i}(int n){{return n+(2+3)*(4+5);}}' for i in range(args.compile_functions))
                            + f'\nint main(int n){{return f{args.compile_functions-1}(n);}}\n')
            records, samples = [], {'O0': [], 'O1': []}
            for round_number in range(args.rounds):
                for level in (('O0', 'O1') if round_number % 2 == 0 else ('O1', 'O0')):
                    binary = args.work / f'compile-cost.{level}.abd'
                    start = time.perf_counter()
                    run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-' + level, '-o', binary])
                    elapsed = time.perf_counter() - start
                    samples[level].append(elapsed)
                    records.append({'round': round_number, 'level': level, 'wall_seconds': elapsed, 'abd_bytes': binary.stat().st_size})
            for engine in ('tree', 'vm'):
                host = args.native / 'interpreter' / ('runtime-benchmark-host' if engine == 'tree' else 'runtime-benchmark-vm-host')
                for level in ('O0', 'O1'):
                    value = json.loads(run([host, '--script', args.work / f'compile-cost.{level}.abd',
                        '--function', '268369920', '--argument', '7', '--expected', '52', '--calls', '1', '--batches', '1', '--warmup', '0'], args.native))
                    assert value['ok'] and value['backend'] == engine, value
            report['compile_cost'] = {'functions': args.compile_functions, 'includes_jvm_startup_parsing_and_writing': True,
                                      'records': records, 'median_seconds': {level: statistics.median(values) for level, values in samples.items()}}
            print('compile_cost', json.dumps(report['compile_cost']['median_seconds']), flush=True)
        report['status'] = 'passed'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
