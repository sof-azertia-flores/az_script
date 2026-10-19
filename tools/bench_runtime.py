#!/usr/bin/env python3
"""Opt-in Release runtime benchmark; independent processes and frozen workloads.

See benchmarks/runtime/README.md. This is not part of the correctness test run.
An existing classpath and native benchmark hosts are required; no builds occur
implicitly. --prepare-host creates a Release host against a source tree, using
the same harness even when that tree predates the benchmark target.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SUITE = ROOT / 'benchmarks/runtime'
HOST_SOURCE = ROOT / 'tests/runtime_benchmark_host.cpp'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def write_json(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    temporary.replace(path)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def capture(command, timeout=120):
    """Keep failed and timed-out observations, including partial diagnostics."""
    start = time.perf_counter()
    try:
        completed = subprocess.run(list(map(str, command)), capture_output=True,
                                   text=True, encoding='utf-8', errors='replace', timeout=timeout)
        result = {'exit_code': completed.returncode, 'stdout': completed.stdout,
                  'stderr': completed.stderr, 'timeout': False}
    except subprocess.TimeoutExpired as error:
        def decoded(value):
            return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else value or ''
        result = {'exit_code': None, 'stdout': decoded(error.stdout), 'stderr': decoded(error.stderr),
                  'timeout': True}
    except OSError as error:
        result = {'exit_code': None, 'stdout': '', 'stderr': str(error), 'timeout': False}
    result.update(command=list(map(str, command)), process_wall_seconds=time.perf_counter() - start)
    return result


def version(command):
    result = capture(command, 20)
    return {key: result[key] for key in ('command', 'exit_code', 'stdout', 'stderr')}


def build_metadata(host):
    """Record actual build cache/commands and shared libraries, not just the runner."""
    result = {'path': str(host), 'sha256': digest(host)}
    build = next((parent for parent in host.parents if (parent / 'CMakeCache.txt').is_file()), None)
    if build is None:
        result['build_metadata'] = 'unavailable; use a CMake build directory for auditable flags'
        return result
    cache = build / 'CMakeCache.txt'
    relevant = {}
    for line in cache.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith(('#', '//')) or '=' not in line:
            continue
        name, value = line.split('=', 1)
        key = name.split(':')[0]
        if key.startswith(('CMAKE_CXX_', 'CMAKE_BUILD_TYPE', 'CMAKE_EXE_LINKER_FLAGS',
                           'CMAKE_SHARED_LINKER_FLAGS', 'AZSCRIPT_')):
            relevant[key] = value
    result.update(build_dir=str(build), cmake_cache_sha256=digest(cache), cmake_cache=relevant)
    commands = build / 'compile_commands.json'
    if commands.is_file():
        result['compile_commands_sha256'] = digest(commands)
        result['compile_commands'] = json.loads(commands.read_text(encoding='utf-8'))
    runtime_names = ('libabdInvoker.so', 'libabdInvoker.dylib', 'abdInvoker.dll',
                     'libabdInvoker-1.so', 'libabdInvoker-1.dylib', 'abdInvoker-1.dll')
    result['runtime_libraries'] = [{'path': str(path), 'sha256': digest(path)}
                                   for name in runtime_names for path in sorted(build.rglob(name))]
    return result


def source_metadata(path, revision):
    result = {'path': str(path), 'declared_revision': revision}
    # An archive inside another repository must not accidentally inherit its HEAD.
    if (path / '.git').exists():
        head = capture(['git', '-C', path, 'rev-parse', 'HEAD'])
        status = capture(['git', '-C', path, 'status', '--short'])
        diff = capture(['git', '-C', path, 'diff', '--binary', 'HEAD'])
        result.update(head=head['stdout'].strip(), status=status['stdout'],
                      tracked_diff_sha256=hashlib.sha256(diff['stdout'].encode()).hexdigest())
    return result


def build_comparison_reasons(metadata):
    """Different or unrecorded optimization flags cannot earn an aggregate score."""
    reasons = []
    candidate = metadata['candidate'].get('cmake_cache')
    baseline = metadata.get('baseline', {}).get('cmake_cache')
    if candidate is None or baseline is None:
        return ['CMake build flags are unavailable for one or both hosts']
    keys = ('CMAKE_CXX_COMPILER', 'CMAKE_BUILD_TYPE', 'CMAKE_CXX_FLAGS', 'CMAKE_CXX_FLAGS_RELEASE',
            'CMAKE_EXE_LINKER_FLAGS', 'CMAKE_EXE_LINKER_FLAGS_RELEASE',
            'CMAKE_SHARED_LINKER_FLAGS', 'CMAKE_SHARED_LINKER_FLAGS_RELEASE', 'AZSCRIPT_SANITIZE')
    for key in keys:
        if candidate.get(key, '') != baseline.get(key, ''):
            reasons.append(f'Build settings differ: {key}')
    if candidate.get('CMAKE_BUILD_TYPE') != 'Release' or baseline.get('CMAKE_BUILD_TYPE') != 'Release':
        reasons.append('Both build caches must specify Release')
    if any(cache.get('AZSCRIPT_SANITIZE', 'OFF') != 'OFF' for cache in (candidate, baseline)):
        reasons.append('Sanitizer builds do not qualify for performance scoring')
    return reasons


def machine_metadata():
    result = {'platform': platform.platform(), 'machine': platform.machine(),
              'python': sys.version, 'cpu_count': os.cpu_count(), 'environment': {}}
    if hasattr(os, 'sched_getaffinity'):
        result['cpu_affinity'] = sorted(os.sched_getaffinity(0))
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LD_LIBRARY_PATH',
                'DYLD_LIBRARY_PATH', 'OMP_NUM_THREADS'):
        if key in os.environ:
            result['environment'][key] = os.environ[key]
    for name in ('cpu.max', 'memory.max', 'cpuset.cpus.effective'):
        path = Path('/sys/fs/cgroup') / name
        if path.is_file():
            result[name] = path.read_text().strip()
    return result


def prepare_host(source, output, jobs, cmake):
    """Build identical harness source against an archive without changing it."""
    output.mkdir(parents=True, exist_ok=True)
    project = output / 'harness-project'
    project.mkdir(exist_ok=True)
    def cmake_path(path):
        value = str(path.resolve()).replace('\\', '/')
        if any(ch in value for ch in ('"', ';', '\n', '$')):
            raise ValueError('Unsupported character in CMake source/output path')
        return value
    (project / 'CMakeLists.txt').write_text(
        'cmake_minimum_required(VERSION 3.20)\nproject(RuntimeBenchmark LANGUAGES CXX)\n'
        'set(BUILD_TESTING OFF CACHE BOOL "" FORCE)\n'
        'set(AZSCRIPT_BUILD_JNI OFF CACHE BOOL "" FORCE)\n'
        'set(AZSCRIPT_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)\n'
        f'add_subdirectory("{cmake_path(source)}" runtime)\n'
        f'add_executable(runtime-benchmark-host "{cmake_path(HOST_SOURCE)}")\n'
        'target_link_libraries(runtime-benchmark-host PRIVATE abdInvoker)\n'
        'target_compile_definitions(runtime-benchmark-host PRIVATE '
        'AZSCRIPT_BENCHMARK_BUILD_TYPE="${CMAKE_BUILD_TYPE}")\n', encoding='utf-8')
    native = output / 'native'
    commands = [[cmake, '-S', project, '-B', native, '-DCMAKE_BUILD_TYPE=Release',
                 '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON'],
                [cmake, '--build', native, '--config', 'Release', '--target', 'runtime-benchmark-host',
                 '--parallel', str(jobs)]]
    for command in commands:
        print('+', ' '.join(map(str, command)), flush=True)
        subprocess.run(list(map(str, command)), check=True)
    print(native / ('runtime-benchmark-host.exe' if os.name == 'nt' else 'runtime-benchmark-host'))


def checksum(expected, calls):
    value = 14695981039346656037
    encoded = (expected & 0xffffffff).to_bytes(4, 'little')
    for _ in range(calls):
        for byte in encoded:
            value = ((value ^ byte) * 1099511628211) & 0xffffffffffffffff
    return str(value)


def parse_observation(result, point, calls, batches, manifest, allow_debug):
    if result['exit_code'] != 0 or result['timeout']:
        return None, 'host failed or timed out'
    try:
        data = json.loads(result['stdout'])
        if not data['ok'] or data['schema_version'] != 1:
            raise ValueError('host reported failure or unknown schema')
        if data['first_result'] != point['expected'] or data['expected'] != point['expected']:
            raise ValueError('independent result oracle mismatch')
        if data['argument'] != point['iterations'] or data['calls_per_batch'] != calls:
            raise ValueError('host did not use frozen workload')
        if (data['max_steps'], data['max_call_depth'], data['max_stack_bytes']) != (
                manifest['max_steps'], manifest['max_call_depth'], manifest['max_stack_bytes']):
            raise ValueError('host limits differ from the manifest')
        if not allow_debug and (data['build_type'] != 'Release' or not data['ndebug']):
            raise ValueError('host must be a Release build (use --allow-debug only for smoke checks)')
        if result['stderr']:
            raise ValueError('unexpected host stderr; see raw observation')
        if len(data['batches']) != batches:
            raise ValueError('wrong batch count')
        expected_hash = checksum(point['expected'], calls)
        for batch in data['batches']:
            if batch['checksum'] != expected_hash:
                raise ValueError('batch checksum mismatch')
            for key in ('wall_seconds', 'cpu_seconds'):
                value = batch['timing'][key]
                if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                    raise ValueError('invalid batch timing')
            if batch['timing']['wall_seconds'] <= 0:
                raise ValueError('batch duration must be positive')
        return data, None
    except (KeyError, TypeError, ValueError) as error:
        return None, str(error)


def command_for(host, point, calls, batches, args, manifest):
    return [host, '--script', point['abd_path'], '--function', manifest['function_id'],
            '--argument', point['iterations'], '--expected', point['expected'], '--calls', calls,
            '--warmup', args.warmup, '--batches', batches, '--max-steps', manifest['max_steps'],
            '--max-depth', manifest['max_call_depth'], '--max-stack-bytes', manifest['max_stack_bytes']]


def summary_for(observations, points, manifest, paired, score_eligible):
    summary = {'cases': {}, 'score_eligible': score_eligible, 'core_speedup': None}
    for point in points:
        key = point['id']
        record = {}
        for engine in ('baseline', 'candidate') if paired else ('candidate',):
            samples = [item for item in observations if item['point'] == key and item['engine'] == engine]
            valid = [item for item in samples if item.get('observation')]
            if len(valid) != len(samples) or not valid:
                record[engine] = {'failed': True, 'samples': len(samples)}
                continue
            per_run = [statistics.median(batch['timing']['wall_seconds'] for batch in item['observation']['batches'])
                       / item['observation']['calls_per_batch'] for item in valid]
            phases = {phase: statistics.median(item['observation']['phases'][phase]['wall_seconds'] for item in valid)
                      for phase in ('file_read', 'load', 'flush', 'first_invoke', 'cold_to_first_invoke', 'warmup', 'destroy')}
            record[engine] = {'seconds_per_call': statistics.median(per_run),
                              'run_seconds_per_call': per_run, 'minimum': min(per_run), 'maximum': max(per_run),
                              'median_absolute_deviation': statistics.median(abs(x - statistics.median(per_run)) for x in per_run),
                              'median_phase_wall_seconds': phases,
                              'median_process_wall_seconds': statistics.median(item['process_wall_seconds'] for item in valid),
                              'max_peak_rss_bytes': max(item['observation']['peak_rss_bytes'] for item in valid)}
        if paired and not any(record[engine].get('failed') for engine in ('baseline', 'candidate')):
            ratios = [a / b for a, b in zip(record['baseline']['run_seconds_per_call'], record['candidate']['run_seconds_per_call'])]
            record['paired_speedups'] = ratios
            record['speedup'] = statistics.median(ratios)
        summary['cases'][key] = record
    if paired and score_eligible:
        groups = {}
        for group, definition in manifest['groups'].items():
            keys = [point['id'] for point in points if point['group'] == group and point['size'] in manifest['scored_sizes']]
            if not keys or any('speedup' not in summary['cases'][key] for key in keys):
                return summary
            groups[group] = math.exp(statistics.mean(math.log(summary['cases'][key]['speedup']) for key in keys))
        summary['group_speedups'] = groups
        summary['core_speedup'] = math.exp(sum(manifest['groups'][group]['weight'] * math.log(value) for group, value in groups.items()))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', help='compiler classpath from build/java/classpath.txt')
    parser.add_argument('--host', '--runner', dest='host', type=Path, help='runtime-benchmark-host; not azscript-run')
    parser.add_argument('--baseline-host', type=Path, help='same benchmark harness built against the frozen runtime')
    parser.add_argument('--candidate-source', type=Path, default=ROOT)
    parser.add_argument('--baseline-source', type=Path)
    parser.add_argument('--candidate-revision')
    parser.add_argument('--baseline-revision')
    parser.add_argument('--reference-role', choices=('original', 'heap-growth-only'), default='original')
    parser.add_argument('--out', type=Path, default=ROOT / 'build/runtime-perf/R01')
    parser.add_argument('--calibration', type=Path, help='reuse an existing frozen.json; candidate never calibrates paired runs')
    parser.add_argument('--rounds', type=int, default=7)
    parser.add_argument('--batches', type=int, default=5)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--target-seconds', type=float, default=0.25)
    parser.add_argument('--timeout', type=float, default=120)
    parser.add_argument('--cases', help='comma-separated smoke subset; disables the adoption score')
    parser.add_argument('--sizes', help='comma-separated smoke subset; disables the adoption score')
    parser.add_argument('--allow-debug', action='store_true', help='smoke only; disables performance scoring')
    parser.add_argument('--java', default='java')
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--prepare-host', type=Path, help='only build the same harness against this source tree')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if args.prepare_host:
        if args.jobs < 1:
            parser.error('--jobs must be positive')
        prepare_host(args.prepare_host.resolve(), args.out.resolve(), args.jobs, args.cmake)
        return 0
    if not args.classpath or not args.host:
        parser.error('--classpath and --host are required for measurements')
    if args.rounds < 1 or not 1 <= args.batches <= 1000 or not 0 <= args.warmup <= 1000000 or not math.isfinite(args.target_seconds) or args.target_seconds <= 0 or args.timeout <= 0:
        parser.error('invalid sample counts, target, or timeout')
    args.host = args.host.resolve()
    if args.baseline_host:
        args.baseline_host = args.baseline_host.resolve()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'observations.jsonl').exists():
        parser.error('output already contains observations; select a fresh --out directory')
    manifest = json.loads((SUITE / 'manifest.json').read_text(encoding='utf-8'))
    spec = importlib.util.spec_from_file_location('runtime_workloads', SUITE / 'generate.py')
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    case_groups = {case: group for group, entry in manifest['groups'].items() for case in entry['cases']}
    cases = list(case_groups) if not args.cases else args.cases.split(',')
    sizes = list(manifest['sizes']) if not args.sizes else args.sizes.split(',')
    if len(set(cases)) != len(cases) or len(set(sizes)) != len(sizes) or any(case not in case_groups for case in cases) or any(size not in manifest['sizes'] for size in sizes):
        parser.error('unknown or duplicate case/size')
    identity = {'manifest_sha256': digest(SUITE / 'manifest.json'), 'generator_sha256': digest(SUITE / 'generate.py'),
                'host_source_sha256': digest(HOST_SOURCE), 'driver_sha256': digest(__file__)}
    metadata = {'started_at': utc_now(), 'manifest': manifest, 'identity': identity,
                'machine': machine_metadata(), 'java': version([args.java, '-version']),
                'cmake': version([args.cmake, '--version']), 'candidate': build_metadata(args.host),
                'candidate_source': source_metadata(args.candidate_source.resolve(), args.candidate_revision),
                'reference_role': args.reference_role,
                'settings': {'rounds': args.rounds, 'batches': args.batches, 'warmup': args.warmup,
                             'target_seconds': args.target_seconds, 'timeout': args.timeout, 'allow_debug': args.allow_debug},
                'compiler_classpath': [{'path': path, 'sha256': digest(path) if Path(path).is_file() else None}
                                       for path in args.classpath.split(os.pathsep)]}
    if args.baseline_host:
        metadata['baseline'] = build_metadata(args.baseline_host)
        if args.baseline_source:
            metadata['baseline_source'] = source_metadata(args.baseline_source.resolve(), args.baseline_revision)
        else:
            metadata['baseline_source'] = {'declared_revision': args.baseline_revision}
    write_json(output / 'metadata.json', metadata)
    inputs = output / 'inputs'
    inputs.mkdir(exist_ok=True)
    points = []
    compilation = []
    for case in cases:
        source = inputs / f'{case}.azs'
        source.write_text(generator.source(case), encoding='utf-8')
        binary = inputs / f'{case}.exec.abd'
        result = capture([args.java, '-cp', args.classpath, 'azertia.Main', 'compile', source, '-o', binary], args.timeout)
        compilation.append(result)
        write_json(output / 'compilation.json', compilation)
        if result['exit_code'] != 0 or not binary.is_file():
            raise RuntimeError(f'Compilation failed for {case}; see {output / "compilation.json"}')
        for size in sizes:
            n = manifest['sizes'][size]
            points.append({'id': f'{case}/{size}', 'case': case, 'group': case_groups[case], 'size': size,
                           'iterations': n, 'expected': generator.expected(case, n), 'abd_path': str(binary),
                           'source_sha256': digest(source), 'abd_sha256': digest(binary)})
    # Paths are not identity: moving the same inputs must not change calibration.
    point_identity = [{key: value for key, value in point.items() if key != 'abd_path'} for point in points]
    workload_hash = hashlib.sha256(canonical({'identity': identity, 'points': point_identity,
                                             'warmup': args.warmup, 'target_seconds': args.target_seconds})).hexdigest()
    reference = args.baseline_host or args.host
    reference_meta = metadata.get('baseline', metadata['candidate'])
    reference_hash = hashlib.sha256(canonical(reference_meta)).hexdigest()
    calibration_path = args.calibration.resolve() if args.calibration else output / 'frozen.json'
    if args.calibration:
        frozen = json.loads(calibration_path.read_text(encoding='utf-8'))
        calls_by_point = frozen.get('calls', {})
        if (frozen.get('schema_version') != 1 or frozen.get('complete') is not True or
                set(calls_by_point) != {point['id'] for point in points} or
                any(type(count) is not int or not 1 <= count <= manifest['calibration']['max_calls_per_batch']
                    for count in calls_by_point.values())):
            raise RuntimeError('Frozen calibration is incomplete or has invalid call counts; candidate timing was not started')
        if frozen['workload_hash'] != workload_hash or frozen['reference_hash'] != reference_hash or frozen['reference_role'] != args.reference_role:
            raise RuntimeError('Frozen calibration does not match workloads/reference/role; do not recalibrate on the candidate')
    else:
        frozen = {'schema_version': 1, 'complete': False, 'created_at': utc_now(), 'workload_hash': workload_hash,
                  'reference_hash': reference_hash, 'reference_role': args.reference_role,
                  'reference': reference_meta, 'points': point_identity, 'calls': {}, 'probes': []}
        for point in points:
            calls = 1
            for attempt in range(4):
                result = capture(command_for(reference, point, calls, 1, args, manifest), args.timeout)
                observation, error = parse_observation(result, point, calls, 1, manifest, args.allow_debug)
                result.update(point=point['id'], attempt=attempt, observation=observation, validation_error=error)
                frozen['probes'].append(result)
                write_json(calibration_path, frozen)
                if error:
                    raise RuntimeError(f'Calibration failed for {point["id"]}: {error}; see {calibration_path}')
                elapsed = observation['batches'][0]['timing']['wall_seconds']
                if elapsed >= args.target_seconds * 0.9:
                    break
                proposed = max(calls + 1, math.ceil(calls * args.target_seconds / elapsed))
                calls = min(manifest['calibration']['max_calls_per_batch'], proposed)
            frozen['calls'][point['id']] = calls
            write_json(calibration_path, frozen)
            print(f'calibrated {point["id"]}: {calls} calls/batch', flush=True)
        frozen['complete'] = True
        write_json(calibration_path, frozen)
    # Publish the complete immutable workload before candidate timing starts.
    write_json(output / 'workload.json', {'identity': identity, 'workload_hash': workload_hash,
                                        'calibration_path': str(calibration_path), 'calibration_sha256': digest(calibration_path),
                                        'points': points, 'calls': frozen['calls']})
    observations = []
    failed = False
    with (output / 'observations.jsonl').open('w', encoding='utf-8') as log:
        for round_index in range(args.rounds):
            for point in points:
                engines = [('candidate', args.host)]
                if args.baseline_host:
                    engines.insert(0, ('baseline', args.baseline_host))
                    if round_index % 2:
                        engines.reverse()
                for engine, host in engines:
                    calls = frozen['calls'][point['id']]
                    result = capture(command_for(host, point, calls, args.batches, args, manifest), args.timeout)
                    observation, error = parse_observation(result, point, calls, args.batches, manifest, args.allow_debug)
                    result.update(round=round_index, point=point['id'], engine=engine,
                                  observation=observation, validation_error=error)
                    observations.append(result)
                    log.write(json.dumps(result, ensure_ascii=False) + '\n'); log.flush()
                    failed |= bool(error)
                    print(f'round {round_index + 1}/{args.rounds} {point["id"]} {engine}: {error or "ok"}', flush=True)
    # Debug, smoke and incomplete results cannot accidentally earn a gate score.
    score_reasons = build_comparison_reasons(metadata) if args.baseline_host else ['No baseline host supplied']
    if failed:
        score_reasons.append('At least one observation failed')
    protocol = manifest['calibration']
    if (args.allow_debug or args.cases or args.sizes or args.rounds < protocol['rounds'] or
            args.batches < protocol['batches'] or args.warmup < protocol['warmup_calls'] or
            args.target_seconds < protocol['target_batch_seconds']):
        score_reasons.append('Smoke/debug/subset settings do not meet the full sampling protocol')
    score_eligible = not score_reasons
    summary = summary_for(observations, points, manifest, bool(args.baseline_host), score_eligible)
    summary.update(completed_at=utc_now(), failed=failed, reference_role=args.reference_role,
                   score_disabled_reasons=score_reasons,
                   adoption_reference=args.reference_role == manifest['adoption_reference_role'],
                   note='Timing evidence only; correctness, sanitizer, host and release gates are independent.')
    write_json(output / 'summary.json', summary)
    print(f'Results: {output / "summary.json"}', flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'Runtime benchmark failed: {error}', file=sys.stderr)
        sys.exit(1)
