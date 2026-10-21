#!/usr/bin/env python3
"""O2 v10 compatibility, semantic observations and optional JIT execution."""
import argparse
import copy
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile

from compiler_optimization_end_to_end import CASES as O1_CASES
from runtime_differential import checked_observation, compare_engines, differences

ROOT = Path(__file__).resolve().parents[1]


def generated_flow_case(first=0, count=48):
    """Independent bounded integer oracle for joins, scopes and loop transfers."""
    rng = random.Random(20261020)
    functions, invocations, expected = [], [], []
    for index in range(48):
        n, seed, initial, factor, salt = (rng.randrange(64), rng.randrange(16),
                                         rng.randrange(16), rng.randrange(1, 8), rng.randrange(16))
        functions.append(f'''int flow{index}(int n){{
            int x=(n&31)+{seed};int y={initial};int i=0;
            while(i<(n&7)){{
                int old=x;int a=(x&15)*{factor};int b=(x&15)*{factor};
                if((i&1)==0){{x=(a+b+y)%127;}}else{{x=(old+y+{salt})%127;}}
                {{int discarded=(y&7)*{factor};}}
                int z=(y&7)*{factor};y=(y+z)%127;
                if(i==2){{i+=1;continue;}}
                if(i==4&&(n&8)!=0){{break;}}
                y=(y+(n&7))%127;i+=1;
            }}
            return x*128+y;
        }}''')
        x, y, i = (n & 31) + seed, initial, 0
        while i < (n & 7):
            a = (x & 15) * factor
            x = (2 * a + y) % 127 if i % 2 == 0 else (x + y + salt) % 127
            y = (y + (y & 7) * factor) % 127
            if i == 2:
                i += 1
                continue
            if i == 4 and (n & 8) != 0:
                break
            y = (y + (n & 7)) % 127
            i += 1
        expected.append(str(x * 128 + y))
        invocations.append(f'print(flow{index}({n}));')
    selected = slice(first, first + count)
    name = 'o2-generated-flow' + (f'-{first}' if first else '')
    return (name, '\n'.join(functions[selected]) + 'int main(){' + ''.join(invocations[selected]) + 'return 0;}',
            0, '\n'.join(expected[selected]) + '\n', None)


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
    ('o2-range-division', 'int f(int n,int count){int s=0;int i=0;while(i<count){int v=((n&7)+1)*3/((n&3)+1);s+=v;i+=1;}return s;}int main(){return f(11,10)+f(0,0);}', 30, '', None),
    ('o2-range-reset', 'int f(int n){int i=0;int s=0;while(i<10){int v=(n&7)+1;v+=i;s+=v;i+=1;}return s;}int main(){return f(3);}', 85, '', None),
    ('o2-range-guard-division', 'int f(int n){if(n>=0&&n<=100){int i=0;int s=0;while(i<10){int v=n*n/3;s+=v;i+=1;}return s;}return 0;}int main(){return f(11)+f(-1);}', 400, '', None),
    ('o2-zero-trip-overflow', 'int f(int n){int i=0;int s=0;while(i<0){s+=n+1;i+=1;}return s;}int main(){return f(2147483647);}', 0, '', None),
    ('o2-conditional-trap', 'int f(int n){return (n+1>2147483647)?1:2;}int main(){return f(2147483647);}', None, '', 'Integer overflow'),
    ('o2-cse-dominator', 'int f(int n,boolean b){int a=n*7;if(b){return n*7;}else{return a;}}int main(){return f(9,true)+f(8,false);}', 119, '', None),
    ('o2-unused-storage', 'int f(int n){int a=0;int unused=n&7;a=n;return a;}int main(){return f(9);}', 9, '', None),
    ('o2-cse-backedge', 'int f(int n){int a=7/n;while(7/n>0){n=0;}return a;}int main(){return f(1);}', None, '', 'Division by zero'),
    ('o2-cse-loop-storage', 'int f(int n){int a=n*7;int i=0;while(i<2&&n*7>0){a=0;i++;}return i;}int main(){return f(1);}', 2, '', None),
    ('o2-cse-loop-stable', 'int f(int n){int a=7/n;int i=0;while(i<2&&7/n>0){int b=7/n;i++;}return a+i;}int main(){return f(1);}', 9, '', None),
    # O3 inlining through seq: operands, early returns, loops, conditions,
    # lazy operands, nested frames, written parameters, depth and callers
    # holding objects. With -g, O3 frames must equal O0's.
    ('o3-operand', 'int f(int x){return x*3+1;}int main(){int t=0;for(int i=0;i<5;i++){t=t+f(i)*2;}return t;}', 70, '', None),
    ('o3-early-return', 'int sign(int x){if(x<0){return -1;}if(x==0){return 0;}return 1;}'
     'int main(){return sign(-5)*100+sign(0)*10+sign(7);}', -99, '', None),
    ('o3-loop-callee', 'int sum(int n){int s=0;int i=0;while(i<n){if(i==5){break;}s+=i;i++;}return s;}'
     'int main(){return sum(3)+sum(10);}', 13, '', None),
    ('o3-condition', 'boolean even(int x){return x%2==0;}int main(){int c=0;int i=0;while(even(i)||i<5){c+=1;i++;}return c;}', 5, '', None),
    ('o3-lazy', 'int f(int x){return 10/x;}int main(){int a=0;boolean b=(a!=0)&&(f(a)>1);return b?1:2;}', 2, '', None),
    ('o3-nested-error', 'int g(int x){\n return 100/x;\n}\nint f(int x){\n return g(x-1)+1;\n}\nint main(){\n return f(1)*2;\n}',
     None, '', 'Division by zero'),
    ('o3-written-param', 'int f(int x){x=x*2;return x+1;}int main(){int y=4;int r=f(y);return r*10+y;}', 94, '', None),
    ('o3-depth', 'int f6(int x){return x+1;}int f5(int x){return f6(x)+1;}int f4(int x){return f5(x)+1;}'
     'int f3(int x){return f4(x)+1;}int f2(int x){return f3(x)+1;}int f1(int x){return f2(x)+1;}int main(){return f1(0);}', 6, '', None),
    ('o3-object-caller', 'class P{int v;P(int n){v=n;}~P(){}}int f(int x){return x+1;}int main(){P p(3);int r=f(p.v);return r;}', 4, '', None),
    ('o3-overflow-loop', 'int f(int x){\n return x*65536;\n}\nint main(){int s=0;for(int i=0;i<100000;i+=30000){s=f(i);}return s;}',
     None, '', 'Integer overflow'),
    ('o3-sibling-slots', 'int f(int a,int b){int c=a*b;return c-a;}int main(){int x=f(3,4)+f(5,6)+f(7,8);return x;}', 9+25+49, '', None),
    # O3 specialization: constant arguments and fixed generic contexts get
    # private clones whose frames show the original.
    ('o3-constant-clone', 'int work(int x,boolean fast,int mode){\n if(fast){return x+1;}\n int s=0;int i=0;\n while(i<x){if(mode==1){s+=i*2;}else{if(mode==2){s+=i*3;}else{s+=i;}}i++;}\n return s/(mode-3);\n}\n'
     'int main(){int a=work(5,true,0);int b=0;for(int k=0;k<10;k++){b+=work(k+20,false,2);}return a+b;}', 6+sum((k + 19) * (k + 20) // 2 * 3 // -1 for k in range(10)), '', None),
    ('o3-constant-clone-error', 'int work(int x,int mode){\n int s=x;\n if(mode==1){s=s*2;}\n return s/(mode-3);\n}\nint main(){\n return work(4,1)+work(4,3);\n}', None, '', 'Division by zero'),
    ('o3-generic-clone', '<T> T pick(boolean c,T a,T b){if(c){return a;}return b;}<T> int count(T x,int n){int s=0;for(int i=0;i<n;i++){s+=i;}return s;}'
     'int main(){int x=pick(true,3,4);boolean y=pick(false,true,false);int z=count(5,10)+count(true,3);return y?0:x+z;}', 51, '', None),
    ('o3-virtual-class-caller', 'class B{int m;virtual int v(int n){return n+1;}}class C:B{override int v(int n){return n+2;}}'
     'int f(int x){return x*2;}int main(){B* b=new C();int r=f(3)+b.v(4);delete b;return r;}', 12, '', None),
    generated_flow_case(),
)


def semantic(observation):
    """Only cross-level comparison relaxes budget and inline-frame identity."""
    value = copy.deepcopy(observation)
    value.pop('engine', None)
    value.pop('remaining_steps', None)

    def errors(item):
        if isinstance(item, dict):
            # Host events and snapshots may report the remaining budget too.
            # Only cross-level comparison ignores it; backend comparison
            # always uses the original observation.
            item.pop('remaining_steps', None)
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


def framed(observation):
    """O3 with -g restores inlined frames: across levels only the budget may differ."""
    value = copy.deepcopy(observation)
    value.pop('engine', None)
    value.pop('remaining_steps', None)

    def errors(item):
        if isinstance(item, dict):
            item.pop('remaining_steps', None)
            if {'category', 'message', 'trace', 'described'} <= item.keys() \
                    and item['message'] == 'Script execution step limit exceeded':
                item.pop('trace'); item.pop('described')
            for child in item.values():
                errors(child)
        elif isinstance(item, list):
            for child in item:
                errors(child)
    errors(value)
    return value


def jit_summary(mode, stats):
    sessions = [json.loads(line) for line in stats.read_text().splitlines()] if stats.exists() else []
    # The runtime deliberately emits no statistics for probe: it varies VM
    # dispatch boundaries but never compiles. Its observations are still
    # compared exactly on every replay below.
    if mode.startswith('probe'):
        assert not sessions, (mode, 'probe unexpectedly emitted compilation statistics')
        return {'entries': 0, 'compiled': 0, 'sessions': 0}
    assert sessions, (mode, 'missing JIT statistics')
    entries = sum(item['entries'] for item in sessions)
    assert entries > 0, (mode, 'no compiled execution')
    assert not sum(item['violations'] for item in sessions), (mode, 'invalid type analysis')
    assert not any(reason.startswith('compiler error') for item in sessions for reason in item['refusals'])
    return {'entries': entries, 'compiled': sum(item['compiled'] for item in sessions),
            'sessions': len(sessions)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--baseline-classpath', help='frozen pre-O2 compiler, to check O0/O1 ABD identity')
    parser.add_argument('--baseline-host', type=Path, help='frozen v10 runtime, to check O2 compatibility')
    parser.add_argument('--o3', action='store_true', help='also run O3 (needs an exec v11 runtime) with the O2 checks')
    parser.add_argument('--jit-modes', default='', help='comma-separated existing AZSCRIPT_JIT modes')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--only', help='run cases whose names contain this text')
    parser.add_argument('--flow-batch-size', type=int, choices=(8, 48), default=48,
                        help='split the 48-function oracle into smaller compilation batches for emulation')
    args = parser.parse_args()
    env = {k: v for k, v in os.environ.items() if k not in
           ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND')
           and not k.startswith('AZSCRIPT_JIT')}
    report = {'cases': [], 'observations': 0, 'budget_checks': 0, 'jit': {}, 'jit_cases': [],
              'flow_batch_size': args.flow_batch_size}
    selected_cases = CASES[:-1] + tuple(generated_flow_case(first, args.flow_batch_size)
                                        for first in range(0, 48, args.flow_batch_size))

    def run(command, extra=None, timeout=90):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env={**env, **(extra or {})},
                                capture_output=True, text=True, timeout=timeout)
        assert result.returncode == 0, (command, result.stdout, result.stderr)
        return result

    with tempfile.TemporaryDirectory(prefix='o2-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        modes = [mode for mode in args.jit_modes.split(',') if mode]
        for name, source, returned, stdout, error in selected_cases:
            if args.only and args.only not in name:
                continue
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            levels = []
            variants = [('O0', []), ('O1', []), ('O2', [])]
            if name.startswith('o2-'):
                variants.append(('O2', ['--no-inline']))
            if args.o3:
                variants.append(('O3', []))
            for index, (level, options) in enumerate(variants):
                abd, ast = work / f'{name}-{index}.abd', work / f'{name}-{index}.json'
                java = ['java', '-cp', args.classpath, 'azertia.Main']
                run(java + ['compile', path, '-g', '-' + level, *options, '-o', abd, '--ast', ast])
                rebuilt = work / 'rebuilt.abd'
                run(java + ['compile-json', ast, '-g', '-' + level, *options, '-o', rebuilt])
                assert abd.read_bytes() == rebuilt.read_bytes(), (name, level, 'AST identity')
                if args.baseline_classpath and level not in ('O2', 'O3'):
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
                if level == 'O0':
                    reference_frames = framed(observations[0])
                elif level == 'O3':
                    assert not differences(reference_frames, framed(observations[0])), \
                        (name, 'O0/O3 frames', differences(reference_frames, framed(observations[0])))
                if level in ('O2', 'O3'):
                    # O3 may use exec v11 constructs, which a v10 runtime refuses.
                    if args.baseline_host and level == 'O2':
                        for engine in ('tree', 'vm'):
                            old = checked_observation(run([args.baseline_host, abd, '--engine', engine, '--steps', '1000000']))
                            assert not compare_engines(observations[0], old, 'tree', engine), (name, 'v10 compatibility', old)
                    for mode in modes:
                        stats = work / (mode.replace(':', '-').replace('+', '-') + '.stats')
                        offset = stats.stat().st_size if stats.exists() else 0
                        # This oracle invokes 48 distinct functions. LLVM
                        # compilation (especially under emulation) needs a
                        # larger wall-clock allowance than individual cases.
                        generated = name.startswith('o2-generated-flow')
                        if generated:
                            print(f'{level} generated flow: {mode}, options={options}', flush=True)
                        result = checked_observation(run([args.host, abd, '--engine', 'vm', '--steps', '1000000'],
                                {'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)},
                                timeout=600 if generated else 90))
                        assert not compare_engines(observations[1], result, 'vm', 'vm'), (name, mode, result)
                        sessions = []
                        if stats.exists():
                            with stats.open('rb') as stream:
                                stream.seek(offset)
                                sessions = [json.loads(line) for line in stream if line.strip()]
                        if (name in ('loop', 'o2-range-loop', 'o2-range-guard', 'o2-range-division',
                                     'o2-range-guard-division', 'o2-inline-loop') or generated) and not mode.startswith('probe'):
                            assert sum(item['entries'] for item in sessions) > 0, (name, mode, 'qualifying loop never compiled')
                        report['jit_cases'].append({'case': name, 'options': options, 'mode': mode, 'sessions': sessions})
                        report['observations'] += 1
                    if not options and name in ('o2-inline-repeat', 'o2-inline-overflow', 'o2-range-loop', 'o2-range-division',
                                                'o2-cse-backedge', 'o2-loop-join', 'o2-generated-flow'):
                        # Exercise optimized shapes on either side of the VM's
                        # batch boundary, including traps and native tier-up.
                        for budget in (1, 7, 31, 63, 64, 65, 127, 255):
                            command = [args.host, abd, '--steps', str(budget)]
                            tree = checked_observation(run(command + ['--engine', 'tree']))
                            vm = checked_observation(run(command + ['--engine', 'vm']))
                            assert not compare_engines(tree, vm, 'tree', 'vm'), (name, budget, tree, vm)
                            report['budget_checks'] += 2
                            for mode in modes:
                                replay = checked_observation(run(command + ['--engine', 'vm'], {'AZSCRIPT_JIT': mode}))
                                assert not compare_engines(vm, replay, 'vm', 'vm'), (name, budget, mode, vm, replay)
                                report['budget_checks'] += 1
            assert not differences(levels[0], levels[1]), (name, 'O0/O1', differences(levels[0], levels[1]))
            for observed in levels[2:]:
                assert not differences(levels[0], observed), (name, 'O0/O2', differences(levels[0], observed))
            report['cases'].append(name)
        assert report['cases'], 'No fixtures selected'
        for source in ('void main(){while(true){}}', 'void f(){f();}void main(){f();}'):
            path = work / 'termination.azs'; path.write_text(source)
            abd = work / 'termination.abd'
            run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-O2', '-o', abd])
            for engine in ('tree', 'vm'):
                result = checked_observation(run([args.host, abd, '--engine', engine, '--steps', '100', '--call-depth', '0']))
                assert result['remaining_steps'] == 0 and 'step limit' in result['error']['message'], result
                report['budget_checks'] += 1
                if engine == 'vm':
                    for mode in modes:
                        replay = checked_observation(run([args.host, abd, '--engine', 'vm', '--steps', '100', '--call-depth', '0'],
                                                         {'AZSCRIPT_JIT': mode}))
                        assert not compare_engines(result, replay, 'vm', 'vm'), (mode, 'budget termination', replay)
                        report['budget_checks'] += 1
        for mode in modes:
            stats = work / (mode.replace(':', '-').replace('+', '-') + '.stats')
            report['jit'][mode] = jit_summary(mode, stats)
    report['status'] = 'passed'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f'O2: {len(report["cases"])} cases, {report["observations"]} observations, '
          f'{report["budget_checks"]} budget checks; {args.report}')


if __name__ == '__main__':
    main()
