#!/usr/bin/env python3
"""Compare O0/O1 across the specified VM corpus at ample budget, on both engines.

Cases are independent; --jobs runs that many at once and the report lists
them in fixture order.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess

from runtime_differential import checked_observation, differences
from vm_execution_cases import cases
from vm_execution_end_to_end import compile_source
from compiler_o2_end_to_end import semantic, jit_summary

ROOT = Path(__file__).resolve().parents[1]


def neutral(value):
    # Optimization may change budget exhaustion locations. Other error traces,
    # callback depth, object identities and cleanup observations remain exact.
    if isinstance(value, dict):
        if value.get('message') == 'Script execution step limit exceeded':
            return {key: neutral(item) for key, item in value.items() if key not in ('trace', 'described')}
        return {key: neutral(item) for key, item in value.items() if key not in ('engine', 'remaining_steps')}
    if isinstance(value, list):
        return [neutral(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--only')
    parser.add_argument('--o2', action='store_true', help='also compare O2 with its documented inline-frame contract')
    parser.add_argument('--o3', action='store_true', help='also compare O3, including all debug trace frames')
    parser.add_argument('--jit-modes', default='', help='replay the O2/O3 artifacts in these AZSCRIPT_JIT modes')
    parser.add_argument('--jobs', type=int, default=os.cpu_count() or 1, help='cases run concurrently')
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be at least 1')
    args.work.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)
    modes = [mode for mode in args.jit_modes.split(',') if mode]
    if modes and not (args.o2 or args.o3):
        parser.error('--jit-modes requires --o2 or --o3')
    # Explicit replay modes need an unmodified VM reference. Otherwise retain
    # the existing JIT driver's environment (including its statistics file).
    if modes:
        for key in list(env):
            if key.startswith('AZSCRIPT_JIT'):
                env.pop(key)
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    selected_levels = ['O0', 'O1'] + (['O2'] if args.o2 else []) + (['O3'] if args.o3 else [])
    report = {'status': 'running', 'cases': [], 'failures': [], 'observations': 0, 'budget_position_relaxed_cases': [],
              'levels': selected_levels}
    report_path = args.work / 'report.json'
    def run_case(item):
        # Returns the observation count and whether the budget-position
        # relaxation applied; any difference raises.
        count, relaxed = 0, False
        levels = []
        for level in selected_levels:
            folder = args.work / item.name / level
            folder.mkdir(parents=True)
            binary = compile_source(compiler, item.source, item.name, folder, env, '-' + level)
            inserted = [compile_source(compiler, source, f'module-{i}', folder, env, '-' + level)
                        for i, source in enumerate(item.modules)]
            observations = []
            for engine in ('tree', 'vm'):
                command = [str(args.host), binary['path'], '--engine', engine, '--steps', '1000000', *item.options]
                for module in inserted:
                    command += ['--insert', module['path']]
                result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=30)
                observed = checked_observation(result)
                assert observed['engine'] in (engine, 'unloaded'), observed
                if observed['error'] and observed['error']['message'] == 'Script execution step limit exceeded':
                    assert observed['remaining_steps'] == 0, observed
                    relaxed = True
                (folder / f'{engine}.json').write_text(json.dumps(observed, indent=2) + '\n')
                observations.append(observed)
                count += 1
            engine_difference = differences({k:v for k,v in observations[0].items() if k != 'engine'},
                                            {k:v for k,v in observations[1].items() if k != 'engine'})
            assert not engine_difference, (item.name, level, engine_difference)
            if level in ('O2', 'O3'):
                for mode in modes:
                    stats = folder / (mode.replace(':', '-').replace('+', '-') + '.stats')
                    result = subprocess.run(command, cwd=ROOT,
                                            env={**env, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats.resolve())},
                                            capture_output=True, timeout=90)
                    observed = checked_observation(result)
                    changed = differences(observations[1], observed)
                    assert not changed, (item.name, level, mode, changed)
                    count += 1
            levels.append(observations[0])
        changed = differences(neutral(levels[0]), neutral(levels[1]))
        assert not changed, (item.name, 'O0/O1', changed)
        for level, observation in zip(selected_levels[2:], levels[2:]):
            changed = differences(semantic(levels[0], preserve_frames=level == 'O3'),
                                  semantic(observation, preserve_frames=level == 'O3'))
            assert not changed, (item.name, 'O0/' + level, changed)
        return count, relaxed

    try:
        selected = [item for item in cases('all') if not args.only or args.only in item.name]
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(run_case, item) for item in selected]
            results = []
            try:
                for item, future in zip(selected, futures):
                    try:
                        results.append(future.result())
                    except Exception as error:
                        report['failures'].append({'case': item.name, 'error': str(error)})
                        results.append(None)
            except BaseException:
                for future in futures:
                    future.cancel()
                raise
        for item, result in zip(selected, results):
            if result is None:
                continue
            count, relaxed = result
            report['observations'] += count
            if relaxed:
                report['budget_position_relaxed_cases'].append(item.name)
            report['cases'].append(item.name)
        assert selected, 'No fixtures selected'
        # Each process writes its own statistics file. Assemble in fixture and
        # level order after the workers finish, avoiding concurrent appends.
        for mode in modes:
            filename = mode.replace(':', '-').replace('+', '-') + '.stats'
            with (args.work / filename).open('wb') as target:
                for item in selected:
                    for level in selected_levels:
                        path = args.work / item.name / level / filename
                        if path.exists():
                            target.write(path.read_bytes())
        report['jit'] = {mode: jit_summary(mode, args.work / (mode.replace(':', '-').replace('+', '-') + '.stats'))
                         for mode in modes}
        if report['failures']:
            raise AssertionError(f'{len(report["failures"])} corpus failures: '
                                 + ', '.join(item['case'] for item in report['failures'][:10]))
        report['status'] = 'passed'
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        raise
    finally:
        report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Compiler corpus: {len(report["cases"])} cases, {report["observations"]} observations; {report_path}')


if __name__ == '__main__':
    main()
