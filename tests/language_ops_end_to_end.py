#!/usr/bin/env python3
"""Bitwise and shift source/AST/ABD parity, evaluation order, errors and real JNI."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BITWISE_OPCODES = {43: 'bit_and', 44: 'bit_or', 45: 'bit_xor', 46: 'shl', 47: 'shr', 48: 'ushr'}


def controls(value):
    if isinstance(value, dict):
        if value.get('t') == 0 and 'c' in value:
            yield value['c']
        for child in value.values():
            yield from controls(child)
    elif isinstance(value, list):
        for child in value:
            yield from controls(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='language-ops-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def run(command):
            return subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True,
                                  text=True, encoding='utf-8', timeout=60)

        def compile_case(name, source, *, error=None, debug=False):
            nonlocal checks
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast, inspection = [work / (name + suffix) for suffix in ('.abd', '.ast.json', '.exec.json')]
            flags = ['-g'] if debug else []
            result = run([*compiler, 'compile', path, '-o', abd, '--ast', ast, '--exec-json', inspection, *flags])
            if error is not None:
                assert result.returncode == 1 and not result.stdout, (name, result.stdout, result.stderr)
                assert 'internal compiler error' not in result.stderr.lower(), (name, result.stderr)
                assert 'error:' in result.stderr, (name, result.stderr)
                if error:
                    assert error in result.stderr, (name, error, result.stderr)
                assert not any(output.exists() for output in (abd, ast, inspection)), (name, 'failed compile wrote outputs')
                checks += 1
                return None
            assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            rebuilt = work / (name + '.roundtrip.abd')
            result = run([*compiler, 'compile-json', ast, '-o', rebuilt, *flags])
            assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, 'source -> readable AST -> ABD differs')
            view = json.loads(inspection.read_text(encoding='utf-8'))
            assert view['exec-version'] == 10, view['exec-version']
            checks += 1
            return abd, view

        def execute(name, source, expected='', *, error=None, debug=False):
            nonlocal checks
            artifact = compile_case(name, source, debug=debug)
            result = run([args.runner, artifact[0]])
            assert result.stdout == expected, (name, result.stdout, expected, result.stderr)
            if error is None:
                assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            else:
                assert result.returncode == 1 and result.stderr.startswith('AzScript error: '), (name, result.stderr)
                assert error in result.stderr, (name, error, result.stderr)
                assert not any(marker in result.stderr for marker in ('AddressSanitizer', 'UndefinedBehaviorSanitizer', 'runtime error:'))
            checks += 1
            return artifact, result

        coverage, _ = execute('bitwise', '''void main(){
            int a=12;int b=10;int sign=-2147483647-1;
            print(a & b);print(a | b);print(a ^ b);print(~a);
            print(sign | 1);print(-1 ^ sign);
            print(1 << 31);print(-8 >> 2);print(-1 >>> 31);
        }''', '8\n14\n6\n-13\n-2147483647\n2147483647\n-2147483648\n-2\n1\n')
        assert set(BITWISE_OPCODES) <= set(controls(coverage[1])), 'The source corpus must emit every new wire opcode'
        checks += 1
        execute('precedence', '''void main(){
            print(1 | 2 ^ 3 & 4);print(1 << 2 + 1);print(32 >> 1 + 2);print(1 + 2 << 2);
            print((8 & 3) == 0);print(1 << 3 < 9);print(true || false && (1 & 1) == 1);
        }''', '3\n8\n4\n12\ntrue\ntrue\ntrue\n')
        execute('compound', '''void main(){
            int a=60;a &= 15;print(a);a |= 3;print(a);a ^= 6;print(a);
            a <<= 2;print(a);a >>= 1;print(a);a >>>= 1;print(a);
        }''', '12\n15\n9\n36\n18\n9\n')
        execute('shift-boundaries', '''void main(){
            int minimum=-2147483647-1;
            print(1 << 0);print(1 << 31);print(2147483647 << 1);
            print(-1 >> 31);print(-1 >>> 0);print(-1 >>> 31);
            print(minimum >> 31);print(minimum >>> 31);print(-8 >>> 2);
        }''', '1\n-2147483648\n-2\n-1\n-1\n1\n-1\n1\n1073741822\n')
        execute('evaluation-order', '''int trace;
            int next(int n){trace=trace*10+n;return n;}
            int value=8;int mutate(){value=1;return 1;}
            void main(){
                print(next(1) << next(2));print(trace);
                trace=0;int result=12;result ^= next(3);print(result);print(trace);
                trace=0;result >>>= next(1);print(result);print(trace);
                print(value << mutate());print(value);
            }''', '4\n12\n15\n3\n7\n1\n16\n1\n')
        execute('left-failure', '''int left(){print("left");return 1/0;}
            int right(){print("right");return 2;}
            void main(){print(left() | right());}''', 'left\n', error='Division by zero')
        execute('right-count-failure', '''int left(){print("left");return 7;}
            int right(){print("right");return 32;}
            void main(){print(left() >>> right());}''', 'left\nright\n',
                error='Shift count must be between 0 and 31, got 32')
        execute('generic-closers', '''class Box<T>{T value;}
            void main(){Box<Box<int>> box;box.value.value=3;
                buffer<Box<int>> values;values.reserve(1);Box<int> value;value.value=5;values.push(value);
                print(box.value.value << 2);print(values.get(0).value >> 1);}''', '12\n2\n')
        execute('macro-shift', '#define SHIFT <<\n#define COUNT 3\nvoid main(){print(1 SHIFT COUNT);}\n', '8\n')
        execute('dynamic-int', '''void main(){def(a,-1);def(b,31);
            print(a & b);print(a | b);print(a ^ b);print(~a);
            print(a << b);print(a >> b);print(a >>> b);}''', '31\n-1\n-32\n0\n-2147483648\n-1\n1\n')

        for symbol in ('&', '|', '^', '<<', '>>', '>>>'):
            label = {'&': 'and', '|': 'or', '^': 'xor', '<<': 'shl', '>>': 'shr', '>>>': 'ushr'}[symbol]
            for side, expression in [('left', f'true {symbol} 1'), ('right', f'1 {symbol} false')]:
                compile_case(f'boolean-{label}-{side}', f'int main(){{return {expression};}}',
                             error='parenthesize comparisons')
            for kind, expression in [('double', f'1.5 {symbol} 1'), ('string', f'1 {symbol} "text"'),
                                     ('address', f'alloc(1) {symbol} 1')]:
                compile_case(f'type-{label}-{kind}', f'int main(){{return {expression};}}', error='must be int')
            for side, expression in [('left', f'x {symbol} 1'), ('right', f'1 {symbol} x')]:
                execute(f'dynamic-type-{label}-{side}', f'void main(){{def(x,true);print({expression});}}',
                        error='operands must be int')
        compile_case('boolean-complement', 'int main(){return ~true;}', error='parenthesize comparisons')
        compile_case('comparison-precedence-hint', 'int main(){return 3 & 1 == 1;}', error='parenthesize comparisons')
        for label, expression in [('spaced-right', '8 > > 1'), ('spaced-left', '1 < < 2'),
                                  ('comment-right', '8 >/* gap */> 1'), ('comment-left', '1 </* gap */< 2')]:
            compile_case(label, f'int main(){{return {expression};}}', error='')
        compile_case('macro-separated-shift', '#define GT >\nint main(){return 8 GT > 1;}\n', error='')
        for label, symbol in [('shl', '<<'), ('shr', '>>'), ('ushr', '>>>')]:
            for count in (-1, 32):
                compile_case(f'literal-{label}-{count}', f'int main(){{return 1 {symbol} {count};}}',
                             error='Shift count must be between 0 and 31')
                execute(f'dynamic-count-{label}-{count}', f'int main(){{int count={count};return 1 {symbol} count;}}',
                        error=f'Shift count must be between 0 and 31, got {count}')
        execute('located-shift', 'int main(){\n  int count=32;\n  return 1 << count;\n}\n',
                error='Shift count must be between 0 and 31, got 32\n  at main (located-shift.azs:3:12)', debug=True)

        if args.library:
            assert args.bridge, '--library requires --bridge'
            binary, _ = compile_case('jni', '''#namespace 5151
                int band(int a,int b):0002{return a & b;}
                int bor(int a,int b):0003{return a | b;}
                int bxor(int a,int b):0004{return a ^ b;}
                int left(int a,int b):0005{return a << b;}
                int right(int a,int b):0006{return a >> b;}
                int unsignedRight(int a,int b):0007{return a >>> b;}
                int badType():0008{def(x,true);return x & 1;}
            ''')
            java = work / 'LanguageOpsHost.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class LanguageOpsHost {
 static void expect(int local,int a,int b,int value){
  Object actual=AbdInvoker.invoke(0x51510000|local,a,b);
  if(!Integer.valueOf(value).equals(actual))throw new AssertionError(local+": "+actual);
 }
 static void rejectCount(int local,int count){
  try{AbdInvoker.invoke(0x51510000|local,-1,count);throw new AssertionError("count accepted");}
  catch(IllegalStateException error){
   String expected="Shift count must be between 0 and 31, got "+count+"\\n  at function "+String.format("0x%08x",0x51510000|local);
   if(!expected.equals(error.getMessage()))throw new AssertionError(error.getMessage());
  }
 }
 public static void main(String[] args){
  try{
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   expect(2,12,10,8);expect(3,12,10,14);expect(4,12,10,6);
   expect(5,1,31,Integer.MIN_VALUE);expect(6,-8,2,-2);expect(7,-1,31,1);
   for(int local=5;local<=7;local++){rejectCount(local,-1);expect(2,12,10,8);rejectCount(local,32);}
   try{AbdInvoker.invoke(0x51510008);throw new AssertionError("boolean accepted");}
   catch(IllegalStateException error){
    if(!error.getMessage().equals("bit_and operands must be int, got boolean and int\\n  at function 0x51510008"))throw new AssertionError(error.getMessage());
   }
  }finally{AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            compiled = run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, java])
            assert compiled.returncode == 0 and not compiled.stderr, (compiled.stdout, compiled.stderr)
            result = run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                          '-cp', os.pathsep.join((str(work), str(args.bridge))), 'LanguageOpsHost', binary])
            assert result.returncode == 0 and not result.stdout and not result.stderr, (result.stdout, result.stderr)
            checks += 1

    print(f'Language operators end-to-end checks passed: {checks} (source/AST/ABD identity, bitwise, shifts, order and errors).')


if __name__ == '__main__':
    main()
