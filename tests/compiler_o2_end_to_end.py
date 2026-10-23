#!/usr/bin/env python3
"""Optimization-level compatibility, semantic observations and JIT execution."""
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
    generated_flow_case(),
)


O3_CASES = (
    ('o3-local-mixed-loop', 'int other(int n){if(n>0)return n+1;return 1;}int pair(int a,int b){return a*10+b;}int bench(int n){int total=0;for(int i=0;i<n;i++){total+=pair(i,other(i));}return total;}int main(){return bench(32);}', 5488, '', None),
    ('o3-local-copy-chain', 'int fold(int x){int a=x+2;int b=a;return b*3;}int main(){return fold(4);}', 18, '', None),
    ('o3-local-copy-write', 'int pair(int x,int y){int a=x;x=y+1;return a*10+x;}int main(){int n=3;return pair(n,n+2)*10+n;}', 363, '', None),
    ('o3-local-unused-divide', 'int fail(int x){int unused=9/x;return 7;}int main(){return fail(0);}', None, '', 'Division by zero'),
    ('o3-local-unused-overflow', 'int fail(int x){int unused=x+1;return 7;}int main(){return fail(2147483647);}', None, '', 'Integer overflow'),
    ('o3-local-lazy-divide', 'int choose(int x,boolean b){int a=x;return b?a:1/x;}int main(){return choose(0,true);}', 0, '', None),
    ('o3-local-lazy-error', 'int choose(int x,boolean b){int a=x;return b?a:1/x;}int main(){return choose(0,false);}', None, '', 'Division by zero'),
    ('o3-local-field-closed-call', 'class Cell{double value=7.0;}int pure(int n){return n+1;}int main(){Cell cell;int a=int(cell.value);int c=pure(a);int b=int(cell.value);return a+b+c;}', 22, '', None),
    ('o3-local-field-mutation', 'class Cell{double value=7.0;void change(){value=9.0;}}int main(){Cell cell;int a=int(cell.value);cell.change();int b=int(cell.value);return a+b;}', 16, '', None),
    ('o3-inline-snapshot-loop', 'int sum(int a,int b,int c){return a+b+c;}int bench(int n){int total=0;for(int i=0;i<n;i++){total+=sum(i,i,7);}return total;}int main(){return bench(32);}', 1216, '', None),
    ('o3-inline-snapshot-forward-expression', 'int identity(int x){return x;}int add(int n){return n+1;}int main(){int x=3;return identity(add(x))*10+x;}', 43, '', None),
    ('o3-inline-snapshot-readonly-alias', 'int sum(int a,int b,int c){return a+b+c;}int main(){int x=3;return sum(x,x,7)*10+x;}', 133, '', None),
    ('o3-inline-snapshot-written-parameter', 'int sum(int a,int b){a=a+1;return a+b;}int main(){int x=3;return sum(x,x)*10+x;}', 73, '', None),
    ('o3-inline-snapshot-later-write', 'int x=3;int step(){x+=1;return x-1;}int pair(int a,int b){return a*10+b;}int main(){return pair(x,step())*10+x;}', 334, '', None),
    ('o3-inline-snapshot-earlier-write', 'int x=3;int step(){x+=1;return x-1;}int pair(int a,int b){return a*10+b;}int main(){return pair(step(),x)*10+x;}', 344, '', None),
    ('o3-inline-snapshot-unused-error', 'int first(int a,int b){return a;}int main(){int z=0;return first(7,9/z);}', None, '', 'Division by zero'),
    ('o3-inline-snapshot-unused-write', 'int x=0;int step(){x+=1;return x-1;}int first(int a,int b){return a;}int main(){return first(7,step())*10+x;}', 71, '', None),
    ('o3-inline-snapshot-body-error', 'int divide(int a,int b){return a/b;}int main(){int a=3;int b=0;return divide(a,b);}', None, '', 'Division by zero'),
    ('o3-inline-snapshot-body-overflow', 'int sum(int a,int b){return a+b;}int main(){int a=2147483647;int b=1;return sum(a,b);}', None, '', 'Integer overflow'),
    ('o3-nested-call', 'int f(int x){int a=x&31;return a*3;}int main(){return 2+f(7)*f(3);}', 191, '', None),
    ('o3-lazy-call', 'int f(int x){return 9/x;}int main(){return false?f(0):f(3);}', 3, '', None),
    ('o3-loop-condition', 'int next(int x){return x+1;}int main(){int i=0;while(next(i)<8){i++;}return i;}', 7, '', None),
    ('o3-call-argument-order', 'int n=0;int step(){n+=1;return n;}int f(int a,int b){return a*10+b;}int main(){return 3+f(step(),step())+n*100;}', 215, '', None),
    ('o3-nested-origin', 'int f(int x){\n return 9/x;\n}\nint g(int x){return 1+f(x);}\nint main(){return 2+g(0);}', None, '', 'Division by zero'),
    ('o3-recursion', 'int sum(int n){if(n==0){return 0;}return n+sum(n-1);}int main(){return 1+sum(10);}', 56, '', None),
    ('o3-specialize-constant', 'int f(int x,int mode){if(mode==0){return x+1;}if(mode==1){return x*3;}return x-1;}int main(){return f(7,1)+f(4,0);}', 26, '', None),
    ('o3-specialize-boolean', 'int f(int x,boolean square){if(square){return x*x;}return x+1;}int main(){return f(7,true)+f(4,false);}', 54, '', None),
    ('o3-range-boundary', 'int f(int n){if(n>=0&&n<=46340){return n*n;}return n+1;}int main(){return f(46340);}', 2147395600, '', None),
    ('o3-range-overflow', 'int f(int n){if(n>=0&&n<=46340){return n*n;}return n+1;}int main(){return f(2147483647);}', None, '', 'Integer overflow'),
    ('o3-field-cache', 'class Cell{double value=7.0;}int main(){Cell cell;int a=int(cell.value);int b=int(cell.value);return a+b;}', 14, '', None),
    ('o3-field-cache-call', 'class Cell{double value=7.0;void change(){value=9.0;}}int main(){Cell cell;int a=int(cell.value);cell.change();int b=int(cell.value);return a+b;}', 16, '', None),
    ('o3-field-cache-alias', 'class Cell{double value=7.0;}int main(){Cell * p=new Cell();Cell * q=p;int a=int(p.value);q.value=9.0;int b=int(p.value);delete p;return a+b;}', 16, '', None),
    ('o3-virtual-guard', 'class Cell{int value=7;virtual int read(int n){return value+n;}}int main(){Cell * c=new Cell();int n=c.read(3);delete c;return n;}', 10, '', None),
    ('o3-virtual-override', 'class Base{int value=7;virtual int read(){return value;}}class Child:Base{override int read(){return 9;}}int main(){Base * c=new Child();int n=c.read();delete c;return n;}', 9, '', None),
    ('o3-sixteen-arguments', 'int sum(int a,int b,int c,int d,int e,int f,int g,int h,int i,int j,int k,int l,int m,int n,int o,int p){return a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p;}int main(){return 3+sum(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16);}', 139, '', None),
    ('o3-scalar-pointer-loop', 'class Cell{int value=3;int read(){return value;}}int main(){int n=0;for(int i=0;i<5;i++){Cell * c();c.value=i;if(i==1){continue;}n+=c.read();if(i==3){break;}}return n;}', 5, '', None),
    ('o3-scalar-pointer-error', 'class Cell{int value=3;}int main(){Cell * c();int n=c.value;return n/(n-3);}', None, '', 'Division by zero'),
    ('o3-literal-copy-storage', 'class Cell{int value=3;}int main(){Cell a;Cell b=a;b.value=8;return a.value*10+b.value;}', 38, '', None),
    ('o3-inline-argument-trace', 'int leaf(int x,int y){return x+y;}int middle(int n){return leaf(1,9/n);}int main(){return middle(0);}', None, '', 'Division by zero'),
    ('o3-generic-scalar-context', '<T> T identity(T value){return value;}int main(){int x=identity<int>(7);return identity<boolean>(true)?x:0;}', 7, '', None),
    ('o3-generic-location-loop', '<T>T identity(T value){return value;}int bench(int n):0002{int total=0;int i=0;while(i<n){total+=identity<int>(i&127);i=i+1;}return total;}int main(){return bench(512);}', 32512, '', None),
    ('o3-generic-location-argument-error', '<T>T identity(T value){return value;}int fail(int n){return 1/n;}int main(){return identity<int>(fail(0));}', None, '', 'Division by zero'),
    ('o3-generic-location-body-error', '<T>T identity(T value){return value;}int fail(int n){return 1/identity<int>(n);}int main(){return false?identity<int>(2):fail(0);}', None, '', 'Division by zero'),
    ('o3-inline-published-trace-clear', 'extern int probe(int):6dff0005;int scalar(int x){return x&7;}int helper(){int n=scalar(1);return probe(n);}class P{int n;~P(){helper();}}int fail(int n){while(n>1){n--;}return 1/(n-1);}int main(){P p;return fail(1);}', None, '', 'Division by zero'),
    ('o3-inline-closed-published', 'extern int probe(int):6dff0005;class P{int n;~P(){probe(1);}}int leaf(int n){return 9/n;}int middle(int n){return leaf(n)+1;}int main(){P p;return middle(0);}', None, '', 'Division by zero'),
)


def semantic(observation, preserve_frames=False):
    """Only cross-level comparison relaxes budget and inline-frame identity."""
    value = copy.deepcopy(observation)
    value.pop('engine', None)
    value.pop('remaining_steps', None)

    def displayed_frames(frames):
        return [{key: field for key, field in frame.items()
                 if key != 'function' or frame['native'] or not frame.get('name')}
                for frame in frames]

    def errors(item):
        if isinstance(item, dict):
            # Host events and snapshots may report the remaining budget too.
            # Only cross-level comparison ignores it; backend comparison
            # always uses the original observation.
            item.pop('remaining_steps', None)
            if preserve_frames and {'frames', 'omitted'} <= item.keys():
                # A destructor's host callback can observe a published partial
                # trace before final unwinding. Preserve its exact frame list,
                # using the same display identity as the final error trace.
                item['frames'] = displayed_frames(item['frames'])
                item.pop('omitted')
            if {'category', 'message', 'trace', 'described'} <= item.keys():
                frames = item.pop('trace')['frames']
                item.pop('described')
                if item['message'] != 'Script execution step limit exceeded':
                    if preserve_frames:
                        # Specialized/inline frames carry original display metadata,
                        # while compiler-private function IDs may differ. Native
                        # and unnamed script IDs still identify observable frames.
                        item['frames'] = displayed_frames(frames)
                    else:
                        script = next((frame for frame in frames if not frame['native']), None)
                        item['origin'] = {key: script[key] for key in ('file', 'line', 'column')} if script else None
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
    parser.add_argument('--baseline-levels', default='O0,O1', help='levels expected byte-identical to the frozen compiler')
    parser.add_argument('--baseline-host', type=Path, help='frozen v10 runtime, to check O2 compatibility')
    parser.add_argument('--levels', default='O0,O1,O2', help='comma-separated levels; first must be O0')
    parser.add_argument('--jit-modes', default='', help='comma-separated existing AZSCRIPT_JIT modes')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--only', help='run cases whose names contain this text')
    parser.add_argument('--flow-batch-size', type=int, choices=(8, 48), default=48,
                        help='split the 48-function oracle into smaller compilation batches for emulation')
    args = parser.parse_args()
    selected_levels = args.levels.split(',')
    baseline_levels = args.baseline_levels.split(',')
    if (len(selected_levels) < 2 or selected_levels[0] != 'O0'
            or len(set(selected_levels)) != len(selected_levels)
            or not set(selected_levels + baseline_levels) <= {'O0', 'O1', 'O2', 'O3'}):
        parser.error('--levels must contain O0 followed by distinct valid optimization levels')
    env = {k: v for k, v in os.environ.items() if k not in
           ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND')
           and not k.startswith('AZSCRIPT_JIT')}
    report = {'cases': [], 'case_levels': {}, 'observations': 0, 'budget_checks': 0, 'jit': {}, 'jit_cases': [],
              'flow_batch_size': args.flow_batch_size, 'levels': selected_levels}
    selected_cases = CASES[:-1] + tuple(generated_flow_case(first, args.flow_batch_size)
                                        for first in range(0, 48, args.flow_batch_size))
    if 'O3' in selected_levels:
        selected_cases += O3_CASES

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
            # These regressions specify O3's new full published-trace contract.
            # The frozen O1/O2 inliners already lose the successful-call clear
            # boundary; changing their artifacts is outside this v11 stage.
            o3_trace = name in ('o3-inline-published-trace-clear', 'o3-inline-closed-published')
            case_levels = [level for level in selected_levels if not o3_trace or level in ('O0', 'O3')]
            report['case_levels'][name] = case_levels
            variants = [(level, []) for level in case_levels]
            if name.startswith('o2-'):
                variants += [(level, ['--no-inline']) for level in selected_levels if level in ('O2', 'O3')]
            for index, (level, options) in enumerate(variants):
                abd, ast = work / f'{name}-{index}.abd', work / f'{name}-{index}.json'
                inspection = work / f'{name}-{index}.exec.json'
                java = ['java', '-cp', args.classpath, 'azertia.Main']
                run(java + ['compile', path, '-g', '-' + level, *options, '-o', abd, '--ast', ast,
                            '--exec-json', inspection])
                rebuilt = work / 'rebuilt.abd'
                run(java + ['compile-json', ast, '-g', '-' + level, *options, '-o', rebuilt])
                assert abd.read_bytes() == rebuilt.read_bytes(), (name, level, 'AST identity')
                if level == 'O3':
                    plain_inspection = work / 'plain.exec.json'
                    run(java + ['compile-json', ast, '-' + level, *options, '-o', rebuilt,
                                '--exec-json', plain_inspection])
                    located = json.loads(inspection.read_text())
                    plain = json.loads(plain_inspection.read_text())
                    for program in (located, plain):
                        program.pop('debug-files', None)
                        for function in program['f']:
                            function.pop('debug', None)
                    assert located == plain, (name, level, 'debug information changed executable instructions')
                if args.baseline_classpath and level in baseline_levels and not options:
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
                levels.append((level, observations[0]))
                if args.baseline_host and level != 'O3':
                    for engine in ('tree', 'vm'):
                        old = checked_observation(run([args.baseline_host, abd, '--engine', engine, '--steps', '1000000']))
                        assert not compare_engines(observations[0], old, 'tree', engine), (name, level, 'v10 compatibility', old)
                if level in ('O2', 'O3'):
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
                        report['jit_cases'].append({'case': name, 'level': level, 'options': options, 'mode': mode, 'sessions': sessions})
                        report['observations'] += 1
                    if not options and (name.startswith('o3-') or name in ('o2-inline-repeat', 'o2-inline-overflow', 'o2-range-loop', 'o2-range-division',
                                                'o2-cse-backedge', 'o2-loop-join', 'o2-generated-flow')):
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
            for level, observed in levels[1:]:
                changed = differences(semantic(levels[0][1], preserve_frames=level == 'O3'),
                                      semantic(observed, preserve_frames=level == 'O3'))
                assert not changed, (name, 'O0/' + level, changed)
            report['cases'].append(name)
        assert report['cases'], 'No fixtures selected'
        for level, source in ((level, source) for level in selected_levels if level != 'O0'
                             for source in ('void main(){while(true){}}', 'void f(){f();}void main(){f();}')):
            path = work / 'termination.azs'; path.write_text(source)
            abd = work / 'termination.abd'
            run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-' + level, '-o', abd])
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
    print(f'{args.levels}: {len(report["cases"])} cases, {report["observations"]} observations, '
          f'{report["budget_checks"]} budget checks; {args.report}')


if __name__ == '__main__':
    main()
