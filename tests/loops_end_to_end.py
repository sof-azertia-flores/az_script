#!/usr/bin/env python3
"""For/continue lowering, scope cleanup, diagnostics and independent wire checks."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

from compact_exec_end_to_end import (WireReader, block, constant, expression, frame,
                                    function, module, returning, stack)

ROOT = Path(__file__).resolve().parents[1]
TRACKER = '''class Tracker {
    int value;
    Tracker(int n) { value = n; }
    ~Tracker() { print(value); }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    args = parser.parse_args()
    command = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    valid_binaries = []
    with tempfile.TemporaryDirectory(prefix='loops-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def run(argv):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True,
                                  text=True, timeout=25)

        def compile_case(name, source, error=None):
            nonlocal checks
            source_path, binary = work / (name + '.azs'), work / (name + '.abd')
            ast, instructions = work / (name + '.ast.json'), work / (name + '.exec.json')
            source_path.write_text(source, encoding='utf-8')
            result = run(command + ['compile', source_path, '-o', binary,
                                    '--ast', ast, '--exec-json', instructions])
            if error:
                assert result.returncode != 0 and error in result.stderr, (name, result.stderr)
                assert 'Exception in thread' not in result.stderr, (name, result.stderr)
                checks += 1
                return
            assert result.returncode == 0, (name, result.stderr)
            roundtrip = work / (name + '.roundtrip.abd')
            again = run(command + ['compile-json', ast, '-o', roundtrip])
            assert again.returncode == 0, (name, again.stderr)
            assert binary.read_bytes() == roundtrip.read_bytes(), (name, 'AST roundtrip')
            reader = WireReader()
            reader.read(binary.read_bytes())
            valid_binaries.append(binary)
            return binary, json.loads(ast.read_text()), json.loads(instructions.read_text()), reader

        def execute(name, source, output='', error=None):
            nonlocal checks
            compiled = compile_case(name, source)
            result = run([args.runner, compiled[0]])
            assert result.stdout == output, (name, result.stdout, output, result.stderr)
            if error:
                assert result.returncode != 0 and error in result.stderr, (name, result.stderr)
            else:
                assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            checks += 1
            return compiled

        simple = execute('for-basic', '''int main(){int sum=0;
            for(int i=0;i<5;i+=1){sum+=i;}return sum;}''', '10\n')
        assert simple[1]['body'], 'readable AST must preserve functions'
        execute('for-empty', 'int main(){int n=0;for(;;){n+=1;if(n==3)break;}return n;}', '3\n')
        execute('for-empty-body', 'int main(){int n=0;for(;n<4;n+=1){}return n;}', '4\n')
        execute('for-empty-semicolon', 'int main(){int n=0;for(;n<4;n++);return n;}', '4\n')
        execute('while-empty-semicolon', 'int main(){int n=0;while((n+=1)<3);return n;}', '3\n')
        execute('for-false', '''int main(){int n=0;for(n=2;false;n+=10){n+=100;}return n;}''', '2\n')
        execute('for-shadow', '''int main(){int i=20;int sum=0;
            for(int i=0;i<3;i+=1)sum+=i;return sum+i;}''', '23\n')
        execute('for-def-init', 'int main(){int n=0;for(def(i,0);i<3;i+=1)n+=1;return n;}', '3\n')
        execute('for-var-init', 'int main(){int n=0;for(var i=0;i<3;i+=1)n+=1;return n;}', '3\n')
        continued = execute('for-continue', '''int main(){int n=0;
            for(int i=0;i<6;i++){if(i%2==0)continue;n+=i;}return n;}''', '9\n')
        assert 30 in continued[3].opcodes and 28 in continued[3].opcodes
        execute('while-continue', '''int main(){int n=0;int i=0;
            while(i<5){i++;if(i<3)continue;n+=i;}return n;}''', '12\n')
        execute('nested-continue', '''int main(){int n=0;
            for(int i=0;i<3;++i){for(int j=0;j<4;++j){if(j==1)continue;
            if(j==3)break;n+=1;}if(i<2)continue;n+=10;}return n;}''', '16\n')
        execute('nested-while', '''int main(){int n=0;for(int i=0;i<3;i++){
            int j=0;while(j<3){j++;if(j==2)continue;n+=1;}}return n;}''', '6\n')
        execute('update-prefix-postfix', '''int main(){int i=3;++i;i++;--i;i--;return i;}''', '3\n')
        execute('update-numeric', '''void main(){double d=1.5;float f=2.5f;
            d++;--f;print(d);print(f);}''', '2.5\n1.5\n')
        execute('update-global', '#gvar i\nint main(){i=1;i++;return i;}', '2\n')
        execute('update-overflow', 'void main(){int i=2147483647;i++;}', error='Integer overflow')
        execute('update-dynamic-string', 'void main(){var i="x";i++;}', error='Unsupported operands')
        execute('for-step-order', '''#gvar steps
            int next(int i){steps+=1;print(100+i);return i+1;}
            ''' + TRACKER + '''void main(){steps=0;for(int i=0;i<3;i=next(i)){
            Tracker body(i);if(i<2)continue;}print(steps);}''', '0\n100\n1\n101\n2\n102\n3\n')
        execute('continue-nested-cleanup', TRACKER + '''void main(){
            for(int i=0;i<2;i++){Tracker outer(10+i);{Tracker inner(i);continue;}}
            print(99);}''', '0\n10\n1\n11\n99\n')
        execute('for-init-cleanup', TRACKER + '''void main(){for(Tracker init(8);true;){
            Tracker body(4);break;}print(99);}''', '4\n8\n99\n')
        execute('for-init-false-cleanup', TRACKER + '''void main(){for(Tracker init(8);false;){print(0);}print(99);}''', '8\n99\n')
        execute('for-return-skip-step', TRACKER + '''int next(){print(99);return 0;}
            int f(){for(Tracker init(8);true;next()){Tracker body(4);return 3;}}
            void main(){print(f());}''', '4\n8\n3\n')
        execute('for-break-skip-step', 'void main(){for(int i=0;true;print(99)){break;}print(1);}', '1\n')
        execute('for-continue-then-return', '''int f(){for(int i=0;;i++){if(i<2)continue;return i;}}
            int main(){return f();}''', '2\n')
        execute('for-condition-order', '''#gvar c
            boolean condition(int i){c+=1;print(i);return i<2;}
            void main(){c=0;for(int i=0;condition(i);i++){continue;}print(c);}''', '0\n1\n2\n3\n')
        execute('for-body-shadow-step', 'int main(){int count=0;for(int i=0;i<3;i++){int i=40;count+=i;}return count;}', '120\n')
        execute('for-redeclare-each-time', 'int main(){int n=0;for(int i=0;i<3;i++){int local=i;n+=local;continue;}return n;}', '3\n')
        execute('continue-destructor-error', '''class Bad{int n;~Bad(){print(8);int failure=1/0;}}
            ''' + TRACKER + '''void main(){for(Tracker init(1);true;print(99)){Bad b;continue;}}''', '8\n1\n', 'Division by zero')
        execute('for-step-error', TRACKER + '''void main(){for(Tracker init(1);true;1/0){Tracker body(2);continue;}}''', '2\n1\n', 'Division by zero')
        execute('continue-budget', 'void main(){for(;;)continue;}', error='step limit exceeded')
        execute('return-for-object', TRACKER + '''Tracker make(){for(int i=0;;i++){Tracker t(i);if(i==0)continue;return t;}}
            void main(){Tracker value=make();print(value.value);}''', '0\n1\n1\n')
        execute('return-for-init-object', TRACKER + '''Tracker make(){for(Tracker t(8);;){return t;}}
            void main(){Tracker value=make();print(value.value);}''', '8\n8\n')
        execute('for-condition-error', TRACKER + '''void main(){for(Tracker init(1);1/0;){print(99);}}''', '1\n', 'Division by zero')
        execute('loop-example', (ROOT / 'compiler/examples/loops-regressions.azs').read_text(), '9\n')

        rejected = {
            'continue-outside': ('void main(){continue;}', 'inside a loop'),
            'continue-function-boundary': ('void f(){continue;}void main(){while(true){f();break;}}', 'inside a loop'),
            'for-init-outside-scope': ('void main(){for(int i=0;i<1;i++){}print(i);}', 'Unknown variable: i'),
            'for-body-outside-step': ('void main(){for(int i=0;i<1;j++){int j=1;}}', 'Unknown variable: j'),
            'for-string-condition': ('void main(){for(;"x";)break;}', 'boolean or numeric'),
            'for-void-condition': ('void main(){for(;print(1);)break;}', 'void'),
            'for-partial-return': ('int main(){for(int i=0;i<1;i++){return 1;}}', 'reach the end'),
            'for-own-break': ('int main(){for(;;){if(true)break;}}', 'reach the end'),
            'continue-reserved': ('void continue(){}void main(){}', 'reserved'),
            'for-reserved': ('void main(){int for=1;}', 'reserved'),
            'continue-value': ('void main(){int x=continue;}', 'cannot be used in an expression'),
            'update-value': ('int main(){int i=0;return i++;}', "Expected ';'"),
            'update-call': ('void main(){print(1)++;}', 'requires a simple variable'),
            'update-member': ('class C{int i;}void main(){C c;c.i++;}', 'requires a simple variable'),
            'update-implicit-member': ('class C{int i;void f(){i++;}}void main(){}', 'Compound assignment to a member'),
            'update-string': ('void main(){string s="x";s++;}', 'must be numeric'),
            'update-reference': ('class C{int i;}void main(){C c;c++;}', 'must be numeric'),
            'for-missing-semicolon': ('void main(){for(int i=0 i<3;i++){} }', "Expected ';'"),
            'for-multiple-init': ('void main(){for(int i=0,j=0;i<3;i++){} }', "Expected ';'"),
        }
        for name, (source, error) in rejected.items():
            compile_case(name, source, error)
        # The inner loop's break does not make the outer endless loop fall through.
        compile_case('for-nested-break-return', 'int f(){for(;;){while(true){break;}return 3;}}void main(){}')

        forged = simple[1]
        main_function = next(item for namespace in forged['body'].values() for item in namespace.values() if item['metadata']['name'] == 'main')
        for name, node, error in [
            ('ast-continue-outside', {'t': 'ctrl', 'call': 'continue', 'param': []}, 'inside a loop'),
            ('ast-continue-arguments', {'t': 'ctrl', 'call': 'continue', 'param': [1]}, 'expects 0'),
            ('ast-for-arity', {'t': 'ctrl', 'call': 'for', 'param': []}, 'expects 4'),
        ]:
            main_function['script'] = [node, {'t': 'ctrl', 'call': 'return', 'param': [0]}]
            path = work / (name + '.json')
            path.write_text(json.dumps(forged))
            result = run(command + ['compile-json', path, '-o', work / (name + '.abd')])
            assert result.returncode != 0 and error in result.stderr, (name, result.stderr)
            checks += 1

        malformed = work / 'continue-extra.abd'
        malformed.write_bytes(frame(stack(*module([function(block(expression(30, constant(1)), returning(constant(0))))]))))
        result = run([args.runner, malformed])
        assert result.returncode != 0, result
        outside = work / 'continue-outside.abd'
        outside.write_bytes(frame(stack(*module([function(block(expression(30), returning(constant(0))))]))))
        result = run([args.runner, outside])
        assert result.returncode != 0 and 'Continue outside loop' in result.stderr, result
        checks += 2

        reader = work / 'LoopWireCheck.java'
        reader.write_text('''import azertia.binary.AbdValue;
import azertia.script.ExecCodec;
import java.nio.file.*;
import java.util.Arrays;
public class LoopWireCheck {
 public static void main(String[] args) throws Exception {
  try { ExecCodec.decode(AbdValue.fromAbd(Files.readAllBytes(Path.of(args[0]))));
   throw new AssertionError("extra continue payload accepted");
  } catch (IllegalArgumentException expected) {}
  for (int i=1;i<args.length;i++) {
   byte[] bytes=Files.readAllBytes(Path.of(args[i]));
   byte[] again=ExecCodec.decode(AbdValue.fromAbd(bytes)).toValue().toAbdFormat();
   if(!Arrays.equals(bytes,again)) throw new AssertionError("wire roundtrip: "+args[i]);
  }
 }
}''', encoding='utf-8')
        result = run(['javac', '-cp', args.classpath, '-d', work, reader])
        assert result.returncode == 0, result.stderr
        result = run(['java', '-cp', str(work) + os.pathsep + args.classpath,
                      'LoopWireCheck', malformed, *valid_binaries])
        assert result.returncode == 0, result.stderr
        checks += 1
    print(f'Loop regressions passed: {checks} source/runtime/AST/wire checks, including cleanup and continue.')


if __name__ == '__main__':
    main()
