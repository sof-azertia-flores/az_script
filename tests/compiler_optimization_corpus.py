#!/usr/bin/env python3
"""Compare O0/O1 across the specified VM corpus at ample budget, on both engines."""
import argparse
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
    parser.add_argument('--jit-modes', default='', help='replay the O2 artifact in these AZSCRIPT_JIT modes')
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)
    modes = [mode for mode in args.jit_modes.split(',') if mode]
    if modes and not args.o2:
        parser.error('--jit-modes requires --o2')
    # Explicit replay modes need an unmodified VM reference. Otherwise retain
    # the existing JIT driver's environment (including its statistics file).
    if modes:
        for key in list(env):
            if key.startswith('AZSCRIPT_JIT'):
                env.pop(key)
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    report = {'status': 'running', 'cases': [], 'observations': 0, 'budget_position_relaxed_cases': []}
    report_path = args.work / 'report.json'
    try:
        for item in cases('all'):
            if args.only and args.only not in item.name:
                continue
            levels = []
            for level in (('O0', 'O1', 'O2') if args.o2 else ('O0', 'O1')):
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
                        if item.name not in report['budget_position_relaxed_cases']:
                            report['budget_position_relaxed_cases'].append(item.name)
                    (folder / f'{engine}.json').write_text(json.dumps(observed, indent=2) + '\n')
                    observations.append(observed)
                    report['observations'] += 1
                engine_difference = differences({k:v for k,v in observations[0].items() if k != 'engine'},
                                                {k:v for k,v in observations[1].items() if k != 'engine'})
                assert not engine_difference, (item.name, level, engine_difference)
                if level == 'O2':
                    for mode in modes:
                        stats = args.work / (mode.replace(':', '-').replace('+', '-') + '.stats')
                        result = subprocess.run(command, cwd=ROOT,
                                                env={**env, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats.resolve())},
                                                capture_output=True, timeout=90)
                        observed = checked_observation(result)
                        changed = differences(observations[1], observed)
                        assert not changed, (item.name, level, mode, changed)
                        report['observations'] += 1
                levels.append(observations[0])
            changed = differences(neutral(levels[0]), neutral(levels[1]))
            assert not changed, (item.name, 'O0/O1', changed)
            if args.o2:
                changed = differences(semantic(levels[0]), semantic(levels[2]))
                assert not changed, (item.name, 'O0/O2', changed)
            report['cases'].append(item.name)
            report_path.write_text(json.dumps(report, indent=2) + '\n')
        assert report['cases'], 'No fixtures selected'
        report['jit'] = {mode: jit_summary(mode, args.work / (mode.replace(':', '-').replace('+', '-') + '.stats'))
                         for mode in modes}
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
