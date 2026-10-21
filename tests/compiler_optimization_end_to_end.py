#!/usr/bin/env python3
"""O0/O1 semantic observations, AST identity and bounded execution on both engines."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CASES = (
    ('loop', 'int main(){int sum=0;for(int i=0;i<100;i++){sum+=(2+3)*(4+5);}return sum;}', 4500, '', None),
    ('inline-order', 'int value(int n){print(n);return n;}int bits(int a,int b){return a&b;}int main(){return bits(value(3),value(6));}', 2, '3\n6\n', None),
    ('inline-error-frame', 'int add(int a,int b){return a+b;}int main(){return add(2147483647,1);}', None, '', 'Integer overflow'),
    ('loop-motion', 'int run(int n){int sum=0;for(int i=0;i<100;i++){sum+=(n&7);}return sum;}int main(){return run(11);}', 300, '', None),
    ('zero-loop-trap', 'int run(int n){int sum=0;for(int i=0;i<0;i++){sum+=1/n;}return sum;}int main(){return run(0);}', 0, '', None),
    ('loop-alias-call', 'int n=3;int change(){n++;return n;}int main(){int sum=0;for(int i=0;i<3;i++){sum+=(n&7);change();}return sum;}', 12, '', None),
    ('virtual-dispatch', 'class A{int x=2+3;virtual int get(){return x;}}class B:A{override int get(){return x+1;}}class C:A{override final int get(){return x+2;}}int main(){A* a=new B();A* b=new C();int value=a.get()+b.get();delete a;delete b;return value;}', 13, '', None),
    ('propagation', 'int main(){int a=2+3;int b=a;int c=b*4;return c+7;}', 27, '', None),
    ('loop-variant', 'int main(){int x=1;int sum=0;while(x<5){sum+=x;x++;}return sum+x;}', 15, '', None),
    ('branch-join', 'int choose(boolean b){int x=2;if(b){x=7;}else{x=7;}return x+3;}int main(){return choose(true)+choose(false);}', 20, '', None),
    ('parameter-copy', 'int f(int a){int b=a;a=9;return b+a;}int main(){return f(3);}', 12, '', None),
    ('invariant-local', 'int main(){int mask=7;int sum=0;for(int i=0;i<20;i++){sum+=(mask&i);}return sum;}', 62, '', None),
    ('copy-snapshot', 'int main(){int a=3;int b=a;a=9;return b+a;}', 12, '', None),
    ('global-call', 'int value=3;void change(){value=9;}int main(){int a=value;change();return a+value;}', 12, '', None),
    ('loop-condition', 'int main(){int x=0;while((x=x+1)<5){}return x;}', 5, '', None),
    ('bits', 'int main(){print(1<<31);print(-1>>>1);return (-7/3)+(-7%3);}', -3, '-2147483648\n2147483647\n', None),
    ('short-circuit', 'boolean effect(){print("effect");return true;}int main(){print((2<3)&&effect());print(false&&effect());print(true||(1/0==0));return 2+3;}',
     5, 'effect\ntrue\nfalse\ntrue\n', None),
    ('effects', 'int effect(int n){print(n);return n;}int main(){return effect(1+2)*0+effect(2*3);}', 6, '3\n6\n', None),
    ('objects', 'class Item{int n;Item(int v){n=v;}~Item(){print(n);}}int main(){Item a(2+3);{Item b(3*4);}return 1+2;}',
     3, '12\n5\n', None),
    ('generic-buffer', '<T> T identity(T value){return value;}int main(){buffer<int> b;b.reserve(1+1);b.push(identity<int>(2+3));return b.get(0)+(3*4);}',
     17, '', None),
    ('reflection', '#namespace 1234\nclass Item{int n=3+4;~Item(){print(n);}}Item* make():0042{Item* value();return value;}'
     'int main(){address p=reflect_invoke_function<address>(305397826);print("after");return 1+2;}',
     3, 'after\n7\n', None),
    ('overflow', 'int main(){print(2+3);return (2147483647+1)-1;}', None, '5\n', 'Integer overflow'),
    ('divide-overflow', 'int main(){return (-2147483648)/-1;}', None, '', 'Integer overflow'),
    ('negate-overflow', 'int main(){return -(-2147483647-1);}', None, '', 'Integer overflow'),
    ('divide-zero', 'int main(){print(2*3);return 7/(2-2);}', None, '6\n', 'Division by zero'),
    ('shift-range', 'int main(){return 1<<(16+16);}', None, '', 'Shift count must be between 0 and 31'),
    ('error-cleanup', 'class Item{int n=3+4;~Item(){print(n);}}int main(){Item a;return 1/(2-2);}',
     None, '7\n', 'Division by zero'),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / 'build/compiler-optimization.json')
    args = parser.parse_args()
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)

    def command(argv):
        result = subprocess.run(list(map(str, argv)), cwd=ROOT, env=env, capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, (argv, result.stdout, result.stderr)
        return result

    java = ['java', '-cp', args.classpath, 'azertia.Main']
    report = {'cases': {}, 'budget_checks': 0}
    with tempfile.TemporaryDirectory(prefix='compiler-optimization-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def compile_case(name, source, level):
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast = work / f'{name}.{level}.abd', work / f'{name}.{level}.json'
            command(java + ['compile', path, '-g', level, '-o', abd, '--ast', ast])
            rebuilt = work / f'{name}.{level}.rebuilt.abd'
            command(java + ['compile-json', ast, '-g', level, '-o', rebuilt])
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, level, 'AST roundtrip')
            return abd

        for name, source, returned, stdout, error in CASES:
            observations = []
            metrics = {}
            for level in ('-O0', '-O1'):
                abd = compile_case(name, source, level)
                metrics[level] = {'abd_bytes': abd.stat().st_size, 'steps': {}}
                engines = []
                for engine in ('tree', 'vm'):
                    observed = json.loads(command([args.host, abd, '--engine', engine, '--steps', '1000000']).stdout)
                    assert observed.pop('engine') == engine
                    assert bytes.fromhex(observed['stdout_hex']).decode('utf-8') == stdout, (name, level, engine, observed)
                    if error is None:
                        assert observed['error'] is None and observed['return']['value'] == returned, (name, observed)
                    else:
                        assert error in observed['error']['message'], (name, observed)
                    engines.append(observed)
                    metrics[level]['steps'][engine] = 1000000 - observed['remaining_steps']
                assert engines[0] == engines[1], (name, level, 'engine difference', engines)
                semantic = dict(engines[0])
                semantic.pop('remaining_steps')  # Optimized programs may perform less work.
                observations.append(semantic)
            assert observations[0] == observations[1], (name, 'optimization changed semantics', observations)
            report['cases'][name] = metrics

        # Termination is the purpose of the budget. These checks do not require
        # an optimizer to preserve the original exhaustion source position.
        for name, source in (
                ('empty-loop', 'void main(){while(1+1==2){}}'),
                ('recursive', 'void recurse(){recurse();}void main(){recurse();}')):
            for level in ('-O0', '-O1'):
                abd = compile_case(name, source, level)
                for engine in ('tree', 'vm'):
                    result = json.loads(command([args.host, abd, '--engine', engine, '--steps', '100', '--call-depth', '0']).stdout)
                    assert 'step limit' in result['error']['message'].lower(), (name, level, engine, result)
                    assert result['remaining_steps'] == 0, result
                    report['budget_checks'] += 1
    loop = report['cases']['loop']
    assert loop['-O1']['abd_bytes'] < loop['-O0']['abd_bytes']
    assert loop['-O1']['steps']['vm'] < loop['-O0']['steps']['vm']
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Compiler optimization: {len(CASES)} O0/O1 cases on both engines; {report["budget_checks"]} termination checks; {args.report}')


if __name__ == '__main__':
    main()
