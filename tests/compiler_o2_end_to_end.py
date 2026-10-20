#!/usr/bin/env python3
"""O2 v10 compatibility, semantic observations and optional JIT execution."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile

from compiler_optimization_end_to_end import CASES as O1_CASES
from runtime_differential import checked_observation, compare_engines, differences

ROOT = Path(__file__).resolve().parents[1]
CASES = O1_CASES + (
    ('o2-cse', 'int f(int n){int a=n*7;int b=n*7;return a+b;}int main(){return f(9);}', 126, '', None),
    ('o2-cse-scope', 'int f(int n){{int a=n*7;}int b=n*7;return b;}int main(){return f(9);}', 63, '', None),
    ('o2-cse-overwrite', 'int f(int n){int a=n*7;a=4;int b=n*7;return a+b;}int main(){return f(9);}', 67, '', None),
    ('o2-cse-input', 'int f(int n){int a=n*7;n=4;int b=n*7;return a+b;}int main(){return f(9);}', 91, '', None),
    ('o2-cse-error', 'int f(int n){int a=1/n;int b=1/n;return a+b;}int main(){return f(0);}', None, '', 'Division by zero'),
    ('o2-dead-trap', 'int f(int n){int a=0;a=1/n;return n;}int main(){return f(0);}', None, '', 'Division by zero'),
    ('o2-negative-bits', 'int f(int n,int m){int a=n&m;if(a<0){return 9;}return 4;}int main(){return f(-3,-7);}', 9, '', None),
    ('o2-loop-join', 'int f(int n){int i=0;int a=7;while(i<n){if(i==3){a=9;}i+=1;}return a;}int main(){return f(0)+f(6);}', 16, '', None),
    ('o2-contradiction', 'int f(int n){if(n<5){if(n>10){return 1/0;}return n;}return 7;}int main(){return f(3)+f(8);}', 10, '', None),
    ('o2-snapshot', 'int f(int n){int a=n;n=9;int b=a+1;return b+n;}int main(){return f(2);}', 12, '', None),
    ('o2-inline-repeat', 'int f(int x){int y=x*x;return y+x;}int main(){int x=7;return f(x+1);}', 72, '', None),
    ('o2-inline-order', 'int f(int x,int y){int a=y-x;return a+x;}int main(){return f(3,9);}', 9, '', None),
    ('o2-inline-arg-trap', 'int f(int x,int y){return x+y;}int main(){return f(1/0,2147483647+1);}', None, '', 'Division by zero'),
    ('o2-inline-origin', 'int f(int x){\n return 1/x;\n}\nint main(){return f(0);}', None, '', 'Division by zero'),
    ('o2-inline-overflow', 'int f(int x){\n int y=x+1;\n return y;\n}\nint main(){return f(2147483647);}', None, '', 'Integer overflow'),
    ('o2-inline-loop', 'int f(int x){return (x&7)+(x&3);}int run(int n){int s=0;for(int i=0;i<n;i++){s+=f(i);}return s;}int main(){return run(8);}', 40, '', None),
    ('o2-range-loop', 'int f(int n){int s=0;int i=0;while(i<10){s+=((n&7)+1)*3;i+=1;}return s;}int main(){return f(11);}', 120, '', None),
    ('o2-range-guard', 'int f(int n){if(n>=0&&n<=100){int i=0;int s=0;while(i<10){s+=n*n;i+=1;}return s;}return 0;}int main(){return f(11)+f(-1);}', 1210, '', None),
    ('o2-zero-trip-overflow', 'int f(int n){int i=0;int s=0;while(i<0){s+=n+1;i+=1;}return s;}int main(){return f(2147483647);}', 0, '', None),
    ('o2-conditional-trap', 'int f(int n){return (n+1>2147483647)?1:2;}int main(){return f(2147483647);}', None, '', 'Integer overflow'),
)


def semantic(observation):
    """Only cross-level comparison relaxes budget and inline-frame identity."""
    value = copy.deepcopy(observation)
    value.pop('engine', None)
    value.pop('remaining_steps', None)

    def errors(item):
        if isinstance(item, dict):
            if {'category', 'message', 'trace', 'described'} <= item.keys():
                frames = item.pop('trace')['frames']
                item.pop('described')
                if item['message'] != 'Script execution step limit exceeded':
                    script = next((frame for frame in frames if not frame['native']), None)
                    item['origin'] = {key: script[key] for key in ('file', 'line', 'column')} if script else None
            for child in item.values():
                errors(child)
        elif isinstance(item, list):
            for child in item:
                errors(child)
    errors(value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--baseline-classpath', help='frozen pre-O2 compiler, to check O0/O1 ABD identity')
    parser.add_argument('--baseline-host', type=Path, help='frozen v10 runtime, to check O2 compatibility')
    parser.add_argument('--jit-modes', default='', help='comma-separated existing AZSCRIPT_JIT modes')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    env = {k: v for k, v in os.environ.items() if k not in
           ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND')
           and not k.startswith('AZSCRIPT_JIT')}
    report = {'cases': [], 'observations': 0, 'budget_checks': 0, 'jit': {}}

    def run(command, extra=None):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env={**env, **(extra or {})},
                                capture_output=True, text=True, timeout=90)
        assert result.returncode == 0, (command, result.stdout, result.stderr)
        return result

    with tempfile.TemporaryDirectory(prefix='o2-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        modes = [mode for mode in args.jit_modes.split(',') if mode]
        for name, source, returned, stdout, error in CASES:
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            levels = []
            for level in ('O0', 'O1', 'O2'):
                abd, ast = work / f'{name}-{level}.abd', work / f'{name}-{level}.json'
                java = ['java', '-cp', args.classpath, 'azertia.Main']
                run(java + ['compile', path, '-g', '-' + level, '-o', abd, '--ast', ast])
                rebuilt = work / 'rebuilt.abd'
                run(java + ['compile-json', ast, '-g', '-' + level, '-o', rebuilt])
                assert abd.read_bytes() == rebuilt.read_bytes(), (name, level, 'AST identity')
                if args.baseline_classpath and level != 'O2':
                    run(['java', '-cp', args.baseline_classpath, 'azertia.Main', 'compile', path,
                         '-g', '-' + level, '-o', rebuilt])
                    assert abd.read_bytes() == rebuilt.read_bytes(), (name, level, 'old compiler identity')
                observations = []
                for engine in ('tree', 'vm'):
                    result = checked_observation(run([args.host, abd, '--engine', engine, '--steps', '1000000']))
                    assert bytes.fromhex(result['stdout_hex']).decode() == stdout, (name, level, engine, result)
                    if error is None:
                        assert result['error'] is None and result['return']['value'] == returned, (name, level, result)
                    else:
                        assert error in result['error']['message'], (name, level, result)
                    observations.append(result)
                    report['observations'] += 1
                assert not compare_engines(*observations, 'tree', 'vm'), (name, level, observations)
                levels.append(semantic(observations[0]))
                if level == 'O2':
                    if args.baseline_host:
                        for engine in ('tree', 'vm'):
                            old = checked_observation(run([args.baseline_host, abd, '--engine', engine, '--steps', '1000000']))
                            assert not compare_engines(observations[0], old, 'tree', engine), (name, 'v10 compatibility', old)
                    for mode in modes:
                        stats = work / (mode.replace(':', '-').replace('+', '-') + '.stats')
                        result = checked_observation(run([args.host, abd, '--engine', 'vm', '--steps', '1000000'],
                                {'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)}))
                        assert not compare_engines(observations[1], result, 'vm', 'vm'), (name, mode, result)
                        report['observations'] += 1
            assert not differences(levels[0], levels[1]), (name, 'O0/O1', differences(levels[0], levels[1]))
            assert not differences(levels[0], levels[2]), (name, 'O0/O2', differences(levels[0], levels[2]))
            report['cases'].append(name)
        for source in ('void main(){while(true){}}', 'void f(){f();}void main(){f();}'):
            path = work / 'termination.azs'; path.write_text(source)
            abd = work / 'termination.abd'
            run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-O2', '-o', abd])
            for engine in ('tree', 'vm'):
                result = checked_observation(run([args.host, abd, '--engine', engine, '--steps', '100', '--call-depth', '0']))
                assert result['remaining_steps'] == 0 and 'step limit' in result['error']['message'], result
                report['budget_checks'] += 1
        for mode in modes:
            stats = work / (mode.replace(':', '-').replace('+', '-') + '.stats')
            sessions = [json.loads(line) for line in stats.read_text().splitlines()] if stats.exists() else []
            assert sum(item['entries'] for item in sessions) > 0, (mode, 'no compiled execution')
            assert not sum(item['violations'] for item in sessions), (mode, 'invalid type analysis')
            assert not any(reason.startswith('compiler error') for item in sessions for reason in item['refusals'])
            report['jit'][mode] = {'entries': sum(item['entries'] for item in sessions),
                                   'compiled': sum(item['compiled'] for item in sessions)}
    report['status'] = 'passed'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f'O2: {len(report["cases"])} cases, {report["observations"]} observations, '
          f'{report["budget_checks"]} termination checks; {args.report}')


if __name__ == '__main__':
    main()
