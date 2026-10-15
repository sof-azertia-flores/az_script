#!/usr/bin/env python3
"""Bitwise, shift and conversion source/AST/ABD parity, errors and real JNI."""
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

        # Known-type identities stay as their original expression, while an
        # unknown source always keeps opcode 49 and validates its runtime tag.
        identity, _ = execute('conversion-known-identity', '''void main(){
            int i=7;float f=1.25f;double d=2.5;string s="text";address p=alloc(1);
            print(int(i));print(float(f));print(double(d));print(string(s));
            print(address(p)==p);mem_free(p);
        }''', '7\n1.25\n2.5\ntext\ntrue\n')
        assert 49 not in set(controls(identity[1])), 'Known identities must not emit convert'
        unknown, _ = execute('conversion-any-identity', r'''void main(){
            def(i,7);def(f,1.25f);def(d,2.5);def(s,"a\u0000😀");
            address p=alloc(1);def(raw,p);
            print(int(i)==7);print(float(f)==1.25f);print(double(d)==2.5);
            print(string(s)=="a\u0000😀");print(address(raw)==p);mem_free(p);
        }''', 'true\ntrue\ntrue\ntrue\ntrue\n')

        def conversion_targets(value):
            if isinstance(value, dict):
                if value.get('t') == 0 and value.get('c') == 49:
                    yield value['type']
                for child in value.values():
                    yield from conversion_targets(child)
            elif isinstance(value, list):
                for child in value:
                    yield from conversion_targets(child)

        assert set(conversion_targets(unknown[1])) == {0, 1, 2, 3, 7}, unknown[1]
        checks += 2
        execute('conversion-numeric-matrix', '''void main(){
            int i=7;float f=1.75f;double d=-2.75;
            print(int(i));print(int(f));print(int(d));
            print(float(i)==7.0f);print(float(f)==1.75f);print(float(d)==-2.75f);
            print(double(i)==7.0);print(double(f)==1.75);print(double(d)==-2.75);
            print(int(2147483647.9));print(int(-2147483648.9));
            print(float(16777217)==16777216.0f);
        }''', '7\n1\n-2\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\n2147483647\n-2147483648\ntrue\n')
        execute('conversion-strings', '''void main(){
            print(int("+7"));print(int("-0008"));print(int("2147483647"));print(int("-2147483648"));
            print(float("1.25e1")==12.5f);print(double(".5e+1")==5.0);print(double("1.")==1.0);
            print(string(7));print(string(1.25f));print(string(2.5));
            address p=alloc(1);print(string(p)==(""+p));print(address(p)==p);mem_free(p);
        }''', '7\n-8\n2147483647\n-2147483648\ntrue\ntrue\ntrue\n7\n1.25\n2.5\ntrue\ntrue\n')
        execute('conversion-underflow', '''void main(){
            print(float(1e-300)==0.0f);print(float("1e-1000")==0.0f);
            print(double("1e-1000")==0.0);print(float("1e-45")>0.0f);print(double("1e-320")>0.0);
        }''', 'true\ntrue\ntrue\ntrue\ntrue\n')
        execute('conversion-evaluation-once', '''int calls;
            double next(){calls+=1;return 3.75;}
            void main(){print(int(next()));print(calls);int(next());print(calls);}
        ''', '3\n1\n2\n')
        for label, expression in [('integer-high', 'int(2147483648.0)'), ('integer-low', 'int(-2147483649.0)'),
                                  ('float-high', 'float(1e40)'), ('integer-string', 'int("2147483648")'),
                                  ('float-string', 'float("1e40")'), ('double-string', 'double("1e400")')]:
            execute('conversion-overflow-' + label, f'void main(){{print({expression});}}', error='value is out of range')
        for target in ('int', 'float', 'double'):
            for index, value in enumerate(['', '+', ' 1', '1 ', '0x10', '1f', 'nan', 'inf', '1junk']):
                literal = json.dumps(value, ensure_ascii=False)
                execute(f'conversion-lexical-{target}-{index}', f'void main(){{print({target}({literal}));}}',
                        error='Cannot convert string')
        for value in ('1.0', '1e2'):
            execute('conversion-integer-grammar-' + value, f'void main(){{print(int({json.dumps(value)}));}}',
                    error='invalid decimal integer')
        for label, body in [
                ('address-from-int', 'address(1);'), ('address-from-float', 'address(1.0f);'),
                ('address-from-double', 'address(1.0);'), ('address-from-string', 'address("1");'),
                ('int-from-address', 'address p=alloc(1);int(p);'),
                ('float-from-address', 'address p=alloc(1);float(p);'),
                ('double-from-address', 'address p=alloc(1);double(p);'),
                ('null', 'address(null);'), ('boolean-int', 'int(true);'),
                ('boolean-string', 'string(false);'), ('boolean-address', 'address(false);'),
                ('buffer', 'buffer<int> values;string(values);')]:
            compile_case('conversion-static-' + label, 'void main(){' + body + '}', error='Cannot convert')
        compile_case('conversion-static-void', 'void empty(){}void main(){int(empty());}', error='Cannot convert')
        compile_case('conversion-static-class', 'class P{int n;}void main(){P p;int(p);}', error='Cannot convert')
        compile_case('conversion-static-pointer', 'class P{int n;}void main(){P* p();address(p);}', error='Cannot convert')
        compile_case('conversion-static-generic', '<T> int asInt(T value){return int(value);}void main(){}', error='Cannot convert')
        for label, expression in [('c-style-int', '(int)3.5'), ('c-style-double', '(double)7')]:
            compile_case('conversion-' + label, 'void main(){print(' + expression + ');}', error='C-style casts are not supported')
        for label, expression in [('missing', 'int()'), ('extra', 'float(1,2)')]:
            compile_case('conversion-arity-' + label, 'void main(){' + expression + ';}', error='')
        execute('conversion-located', 'int main(){\n  return int("bad");\n}\n',
                error='Cannot convert string "bad" to int: invalid decimal integer\n  at main (conversion-located.azs:2:10)', debug=True)
        for label, body in [('boolean', 'def(x,true);print(int(x));'),
                            ('int-address', 'def(x,1);print(address(x));'),
                            ('string-address', 'def(x,"1");print(address(x));'),
                            ('address-int', 'address p=alloc(1);def(x,p);print(int(x));')]:
            execute('conversion-dynamic-' + label, 'void main(){' + body + '}', error='Cannot convert')
        escaped = '1\0😀"\\\n'
        _, escaped_error = execute('conversion-escaped-error', f'int main(){{return int({json.dumps(escaped, ensure_ascii=False)});}}',
                                   error='Cannot convert string')
        assert escaped_error.stderr.isascii() and '\x00' not in escaped_error.stderr, escaped_error.stderr
        assert all(marker in escaped_error.stderr for marker in ('\\u0000', '\\ud83d\\ude00', '\\"', '\\\\', '\\u000a')), escaped_error.stderr
        assert len(escaped_error.stderr.splitlines()) == 2, escaped_error.stderr
        # The 64-character preview keeps the final supplementary character as a
        # complete surrogate pair and omits the following character.
        preview = '1' * 63 + '😀' + 'x'
        execute('conversion-preview-boundary', f'int main(){{return int({json.dumps(preview, ensure_ascii=False)});}}',
                error='Cannot convert string "' + '1' * 63 + '\\ud83d\\ude00..." to int')
        checks += 2

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
            conversion_binary, _ = compile_case('conversion-jni', '''#namespace 5151
                int asInt(double value):0010{return int(value);}
                float asFloat(int value):0011{return float(value);}
                double asDouble(string value):0012{return double(value);}
                string identity(string value):0013{return string(value);}
                int parseInt(string value):0014{return int(value);}
                float parseFloat(string value):0015{return float(value);}
                double parseDouble(string value):0016{return double(value);}
                string formatInt(int value):0017{return string(value);}
                int badBoolean():0018{def(value,true);return int(value);}
            ''')
            conversion_java = work / 'ConversionHost.java'
            conversion_java.write_text(r'''import azertia.AbdInvoker;
import java.io.File;
public class ConversionHost {
 static void expect(int local,Object value,Object... arguments){
  Object actual=AbdInvoker.invoke(0x51510000|local,arguments);
  if(!value.equals(actual))throw new AssertionError(local+": "+actual);
 }
 static void reject(int local,String prefix,Object... arguments){
  try{AbdInvoker.invoke(0x51510000|local,arguments);throw new AssertionError("conversion accepted");}
  catch(IllegalStateException error){
   String message=error.getMessage();
   if(!message.startsWith(prefix)||!message.endsWith("\n  at function "+String.format("0x%08x",0x51510000|local)))throw new AssertionError(message);
  }
 }
 public static void main(String[] args){
  try{
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   expect(0x10,Integer.valueOf(2147483647),2147483647.9);
   expect(0x10,Integer.valueOf(Integer.MIN_VALUE),-2147483648.9);
   expect(0x11,Float.valueOf(16777216.0f),16777217);
   expect(0x12,Double.valueOf(1.25),"1.25");
   expect(0x14,Integer.valueOf(7),"+7");expect(0x17,"42",42);
   String input="123"+(char)0+"😀";
   expect(0x13,input,input);
   expect(0x15,Float.valueOf(0.0f),"1e-1000");
   expect(0x16,Double.valueOf(0.0),"1e-1000");
   reject(0x10,"Cannot convert double to int: value is out of range",2147483648.0);
   reject(0x15,"Cannot convert string", "1e40");
   reject(0x16,"Cannot convert string", "1e400");
   reject(0x18,"Cannot convert boolean to int");
   try{AbdInvoker.invoke(0x51510014,input);throw new AssertionError("NUL/emoji accepted");}
   catch(IllegalStateException error){
    String expected=args[1]+"\n  at function 0x51510014";
    if(!expected.equals(error.getMessage()))throw new AssertionError(error.getMessage());
   }
   expect(0x14,Integer.valueOf(7),"7");
  }finally{AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            compiled = run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, conversion_java])
            assert compiled.returncode == 0 and not compiled.stderr, (compiled.stdout, compiled.stderr)
            expected_escaped = 'Cannot convert string "123\\u0000\\ud83d\\ude00" to int: invalid decimal integer'
            result = run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                          '-cp', os.pathsep.join((str(work), str(args.bridge))), 'ConversionHost', conversion_binary, expected_escaped])
            assert result.returncode == 0 and not result.stdout and not result.stderr, (result.stdout, result.stderr)
            checks += 1

    print(f'Language operators end-to-end checks passed: {checks} (source/AST/ABD identity, bitwise, shifts, conversions, order and errors).')


if __name__ == '__main__':
    main()
