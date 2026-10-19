#!/usr/bin/env python3
"""Collect/replay independent-process execution observations of two engines.

Reference-only collection is explicitly not a VM pass. Hosts implement
observation protocol 2 (tests/observation_host.cpp) and are told which engine to
run; each observation reports the engine the runtime actually used, and a run
reporting another engine fails. Saved ABD bytes, commands, binary hashes and
complete observations are retained so later stages replay the same inputs
without rebuilding the reference or changing semantic comparisons.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import compact_exec_end_to_end as wire
import runtime_differences
from runtime_differential import (ADAPTER, DEFAULT_STEPS, PROTOCOL, HostFailure, compare_engines, digest, execute,
                                  semantic_checks, write_json)
from vm_execution_cases import cases

ROOT = Path(__file__).resolve().parents[1]


def terminal_shape(observation):
    # Used only to discover the end of a budget sweep in the reference process.
    # Candidate comparisons always include remaining_steps without filtering.
    # Probe callbacks record the budget too, so those copies are dropped here.
    def strip(value):
        if isinstance(value, dict):
            return {key: strip(item) for key, item in value.items() if key != 'remaining_steps'}
        if isinstance(value, list):
            return [strip(item) for item in value]
        return value
    return strip(copy.deepcopy(observation))


def identity(path, revision, library=None):
    result = {'path': str(path), 'sha256': digest(path), 'revision': revision}
    if library:
        result['runtime_library'] = {'path': str(library.resolve()), 'sha256': digest(library)}
    return result


def observed(host, engine, binary, inserted, options, extra, budget, destination, timeout):
    command = [str(host), str(binary), '--steps', str(budget), '--engine', engine, *options]
    for module in inserted:
        command += ['--insert', str(module)]
    command += extra
    try:
        value = execute(command, timeout)
    except HostFailure as error:
        write_json(destination, {'command': command, 'failure': str(error), **error.details})
        raise
    write_json(destination, {'command': command, 'observation': value})
    return value


def compile_source(compiler, source, name, folder, env):
    path = folder / (name + '.azs')
    path.write_text(source, encoding='utf-8')
    binary, ast, inspection, rebuilt = (folder / (name + suffix) for suffix in
                                      ('.abd', '.ast.json', '.exec.json', '.rebuilt.abd'))
    commands = [compiler + ['compile', str(path), '-g', '-o', str(binary), '--ast', str(ast), '--exec-json', str(inspection)],
                compiler + ['compile-json', str(ast), '-g', '-o', str(rebuilt)]]
    for index, command in enumerate(commands):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                                encoding='utf-8', env=env, timeout=90)
        write_json(folder / f'{name}.compile-{index}.json', {'command': command, 'exit': result.returncode,
                                                          'stdout': result.stdout, 'stderr': result.stderr})
        if result.returncode or result.stderr:
            raise AssertionError(f'{name}: compiler failed: {result.stderr}')
    if binary.read_bytes() != rebuilt.read_bytes():
        raise AssertionError(f'{name}: source/AST/ABD identity changed')
    executable = json.loads(inspection.read_text(encoding='utf-8'))
    if not executable['debug-files'] or not all('debug' in item for item in executable['f']):
        raise AssertionError(f'{name}: debug records missing')
    reader = wire.WireReader(); reader.read(binary.read_bytes())
    return {'path': str(binary), 'sha256': digest(binary), 'source_sha256': digest(path),
            'wire_opcodes_present': sorted(reader.opcodes), 'source_ast_abd_equal': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', help='required when collecting a new reference')
    parser.add_argument('--reference-host', type=Path)
    parser.add_argument('--reference-library', type=Path)
    parser.add_argument('--reference-revision', default='unspecified')
    parser.add_argument('--reference-engine', choices=['tree', 'vm'], default='tree')
    parser.add_argument('--reference-work', type=Path, help='replay an existing completed reference collection')
    parser.add_argument('--candidate-host', type=Path)
    parser.add_argument('--candidate-library', type=Path)
    parser.add_argument('--candidate-revision', default='unspecified')
    parser.add_argument('--candidate-engine', choices=['tree', 'vm'], default='vm')
    parser.add_argument('--candidate-arg', action='append', default=[], help='extra host argument; use = for leading --')
    parser.add_argument('--stage', choices=['scalar', 'calls', 'objects', 'ownership', 'all'], default='all')
    parser.add_argument('--only', help='select fixture names containing this text during collection')
    parser.add_argument('--work', type=Path, help='new directory, retained on success and failure')
    parser.add_argument('--budget-max', type=int, default=1024, help='positive sweep cap; incomplete sweeps fail')
    parser.add_argument('--timeout', type=float, default=20)
    args = parser.parse_args()
    if args.reference_work:
        if not args.candidate_host or args.reference_host or args.classpath or args.only:
            parser.error('--reference-work requires --candidate-host and excludes new reference compilation options')
    elif not args.reference_host or not args.classpath:
        parser.error('new reference collection requires --reference-host and --classpath')
    if args.budget_max < 2 or args.timeout <= 0:
        parser.error('budget-max must be at least 2 and timeout must be positive')
    for host in (args.reference_host, args.candidate_host):
        if host and not host.is_file():
            parser.error(f'host does not exist: {host}')
    if (args.reference_host and args.candidate_host and args.reference_host.resolve() == args.candidate_host.resolve()
            and args.reference_engine == args.candidate_engine):
        parser.error('reference/candidate must be distinct executables or engines')
    root = ROOT / 'build/runtime-perf'; root.mkdir(parents=True, exist_ok=True)
    work = args.work.resolve() if args.work else Path(tempfile.mkdtemp(prefix='vm-execution-', dir=root))
    if args.work:
        work.mkdir(parents=True, exist_ok=False)
    report = {'status': 'running', 'reference_only': args.candidate_host is None,
              'independent_candidate_processes': 0, 'independent_reference_processes': 0,
              'registered_differences': {}, 'cases': [], 'failures': []}
    registry = runtime_differences.load()
    manifest = {'protocol': PROTOCOL, 'created_unix': time.time(), 'harness_sha256': digest(__file__),
                'fixtures_sha256': digest(ROOT / 'tests/vm_execution_cases.py'),
                'adapter_sha256': digest(ADAPTER), 'cases': []}
    if args.candidate_host:
        args.candidate_host = args.candidate_host.resolve()
        manifest['candidate'] = identity(args.candidate_host, args.candidate_revision, args.candidate_library)
        manifest['candidate']['engine'] = args.candidate_engine
        manifest['candidate_arguments'] = args.candidate_arg

    def compare(record, budget, reference):
        folder = work / record['name']; folder.mkdir(exist_ok=True)
        candidate = observed(args.candidate_host, args.candidate_engine, record['binary']['path'],
                             [item['path'] for item in record['inserted']], record['options'], args.candidate_arg,
                             budget, folder / f'{budget}.candidate.json', args.timeout)
        report['independent_candidate_processes'] += 1
        mismatch = compare_engines(reference, candidate, reference_engine, args.candidate_engine)
        entry = registry.lookup(record['name'], args.candidate_engine)
        if entry is None:
            if mismatch:
                raise AssertionError(json.dumps({'case': record['name'], 'budget': budget, 'differences': mismatch}))
            return

        def rerun(side, extra):
            host = args.candidate_host if side == 'candidate' else reference_host
            engine = args.candidate_engine if side == 'candidate' else reference_engine
            more = args.candidate_arg if side == 'candidate' else []
            return observed(host, engine, record['binary']['path'], [item['path'] for item in record['inserted']],
                            record['options'], [*more, *extra], budget, folder / f'{budget}.{side}.validator.json',
                            args.timeout)
        if registry.validate(entry, record['name'], reference, candidate, mismatch, rerun,
                             lambda left, right: compare_engines(left, right, reference_engine, args.candidate_engine)):
            counts = report['registered_differences']
            counts[entry['id']] = counts.get(entry['id'], 0) + 1

    try:
        if args.reference_work:
            reference_root = args.reference_work.resolve()
            prior = json.loads((reference_root / 'report.json').read_text(encoding='utf-8'))
            original = json.loads((reference_root / 'manifest.json').read_text(encoding='utf-8'))
            if prior['status'] not in ('reference-collected', 'passed') or not original.get('cases'):
                raise AssertionError('reference collection was not complete')
            manifest['reference_collection'] = {'path': str(reference_root),
                                                'manifest_sha256': digest(reference_root / 'manifest.json')}
            manifest['reference'] = original['reference']
            reference_engine = original['reference'].get('engine', 'tree')
            reference_host = Path(original['reference']['path'])
            if str(args.candidate_host) == original['reference']['path'] and reference_engine == args.candidate_engine:
                raise AssertionError('candidate must be a distinct executable or engine')
            for record in original['cases']:
                if args.stage != 'all' and record['stage'] != args.stage:
                    continue
                for artifact in [record['binary'], *record['inserted']]:
                    if digest(artifact['path']) != artifact['sha256']:
                        raise AssertionError('saved module changed: ' + artifact['path'])
                manifest['cases'].append(record)
                write_json(work / 'manifest.json', manifest)
                for budget in record['budgets']:
                    saved = reference_root / record['name'] / f'{budget}.reference.json'
                    if digest(saved) != record['reference_observation_sha256'][str(budget)]:
                        raise AssertionError('saved reference observation changed: ' + str(saved))
                    reference = json.loads(saved.read_text(encoding='utf-8'))['observation']
                    compare(record, budget, reference)
                report['cases'].append({'name': record['name'], 'stage': record['stage'],
                                        'budgets': record['budgets'], 'status': 'passed'})
                write_json(work / 'report.json', report)
        else:
            args.reference_host = args.reference_host.resolve()
            manifest['reference'] = identity(args.reference_host, args.reference_revision, args.reference_library)
            manifest['reference']['engine'] = reference_engine = args.reference_engine
            reference_host = args.reference_host
            manifest['compiler_inputs'] = [{'path': path, 'sha256': digest(path)}
                                          for path in args.classpath.split(os.pathsep) if Path(path).is_file()]
            env = os.environ.copy()
            for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
                env.pop(key, None)
            compiler = ['java', '-cp', args.classpath, 'azertia.Main']
            selected = [item for item in cases(args.stage) if args.only is None or args.only in item.name]
            if not selected:
                raise AssertionError('no fixtures selected')
            for item in selected:
                folder = work / item.name; folder.mkdir()
                record = {'name': item.name, 'stage': item.stage, 'options': item.options, 'budgets': [],
                          'reference_observation_sha256': {}, 'inserted': []}
                record['binary'] = compile_source(compiler, item.source, item.name, folder, env)
                for index, module in enumerate(item.modules):
                    record['inserted'].append(compile_source(compiler, module, f'{item.name}-module-{index}', folder, env))
                manifest['cases'].append(record); write_json(work / 'manifest.json', manifest)

                def collect(budget):
                    destination = folder / f'{budget}.reference.json'
                    value = observed(args.reference_host, args.reference_engine, record['binary']['path'],
                                     [entry['path'] for entry in record['inserted']], item.options, [],
                                     budget, destination, args.timeout)
                    report['independent_reference_processes'] += 1
                    if value['engine'] not in (args.reference_engine, 'unloaded'):
                        raise AssertionError(f'{item.name}: reference ran on {value["engine"]!r}, expected {args.reference_engine!r}')
                    record['budgets'].append(budget)
                    record['reference_observation_sha256'][str(budget)] = digest(destination)
                    if args.candidate_host:
                        compare(record, budget, value)
                    return value

                full = collect(DEFAULT_STEPS)
                semantic_checks(item, full)
                if item.trace_names:
                    names = tuple(frame['name'] for frame in full['error']['trace']['frames'])
                    if names != item.trace_names:
                        raise AssertionError((item.name, 'specified error frame order', names, item.trace_names))
                if item.trace_lines:
                    lines = tuple(frame['line'] for frame in full['error']['trace']['frames'])
                    if lines != item.trace_lines:
                        raise AssertionError((item.name, 'specified error frame lines', lines, item.trace_lines))
                matched = 0
                if item.sweep:
                    for budget in range(1, args.budget_max + 1):
                        value = collect(budget)
                        matched = matched + 1 if terminal_shape(value) == terminal_shape(full) else 0
                        # Includes every stopping point plus an extra successful
                        # budget, also when flush costs more than the main body.
                        if matched >= 2:
                            break
                    else:
                        raise AssertionError(f'{item.name}: incomplete budget sweep (cap {args.budget_max})')
                record['expected'] = {'return': item.expected_return, 'error': item.expected_error,
                                      'globals': item.expected_globals, 'trace_names': item.trace_names}
                write_json(work / 'manifest.json', manifest)
                report['cases'].append({'name': item.name, 'stage': item.stage, 'budgets': record['budgets'],
                                        'status': 'passed' if args.candidate_host else 'reference-collected'})
                write_json(work / 'report.json', report)
        if not manifest['cases']:
            raise AssertionError('no fixtures selected from reference collection')
        if args.candidate_host:
            stale = registry.stale({record['name'] for record in manifest['cases']}, args.candidate_engine)
            if stale:
                raise AssertionError(f'stale runtime difference entries (no difference observed): {stale}')
        report['status'] = 'passed' if args.candidate_host else 'reference-collected'
        report['coverage_note'] = 'Independent process observations include every protocol field; static opcode presence is not execution coverage.'
        write_json(work / 'manifest.json', manifest); write_json(work / 'report.json', report)
        print(f'VM execution: {report["status"]}; {len(report["cases"])} cases, '
              f'{report["independent_reference_processes"]} reference / {report["independent_candidate_processes"]} candidate processes. Artifacts: {work}')
    except Exception as error:
        report['status'] = 'failed'; report['failures'].append(str(error)); write_json(work / 'report.json', report)
        print(f'VM execution failed; artifacts retained at {work}: {error}', file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == '__main__':
    main()
