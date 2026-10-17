#!/usr/bin/env python3
"""Language operators, conversions, control flow and Unicode strings through source/AST/ABD and JNI."""
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
        execute('conversion-pointer-erasure', 'class P{int n;}void main(){P* p();print(address(p)==address(p));}', 'true\n')
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

        selected, _ = execute('select-precedence', '''void main(){
            print(false ? 1 : true ? 2 : 3);print(true ? false ? 4 : 5 : 6);
            print(false || true ? 8 : 9);print((1 & 1)==1 ? 4 : 5);
            int value=0;value=false ? 10 : 20;print(value);
            print(false ? (value=3) : (value=4));print(value);print((false ? 1 : 2)+3);
            print((false?1:1e5) == 100000.0);print(false?true:false);
        }''', '2\n5\n8\n4\n20\n4\n4\n5\ntrue\nfalse\n')
        assert 50 in set(controls(selected[1])), 'The conditional corpus must emit select'
        select_ast = json.loads((work / 'select-precedence.ast.json').read_text(encoding='utf-8'))

        def source_selections(value):
            if isinstance(value, dict):
                if value.get('t') == 'ctrl' and value.get('call') == 'select':
                    yield value
                for child in value.values():
                    yield from source_selections(child)
            elif isinstance(value, list):
                for child in value:
                    yield from source_selections(child)

        assert all(len(node['param']) == 3 for node in source_selections(select_ast)), select_ast
        checks += 2
        execute('select-lazy-order', '''int trace;
            boolean condition(){trace=trace*10+1;return true;}
            int arm(int n){trace=trace*10+n;return n;}
            void main(){
                print(condition()?arm(2):arm(3));print(trace);
                trace=0;print(false?arm(4):arm(5));print(trace);
                print(true?7:1/0);print(false?1/0:9);
            }''', '2\n12\n5\n5\n7\n9\n')
        execute('select-current-scope', '''void main(){
            int depth=getDepth();print((true?getDepth():getDepth())==depth);
            {int nested=getDepth();print((false?getDepth():getDepth())==nested);}
        }''', 'true\ntrue\n')
        promoted, _ = execute('select-numeric-promotion', '''void main(){
            float f=true?16777217:1.25f;double d=true?7:2.5;double e=true?1.25f:2.5;
            print(f==16777216.0f);print(d==7.0);print(e==1.25);
            print((false?7:1.25f)==1.25f);print((false?7:2.5)==2.5);print((false?1.25f:2.5)==2.5);
        }''', 'true\ntrue\ntrue\ntrue\ntrue\ntrue\n')

        def selection_nodes(value):
            if isinstance(value, dict):
                if value.get('t') == 0 and value.get('c') == 50:
                    yield value
                for child in value.values():
                    yield from selection_nodes(child)
            elif isinstance(value, list):
                for child in value:
                    yield from selection_nodes(child)

        promotion_nodes = list(selection_nodes(promoted[1]))
        assert promotion_nodes and all({'v', 'then', 'else'} <= node.keys() for node in promotion_nodes)
        arm_conversion_types = {arm['type'] for node in promotion_nodes for arm in (node['then'], node['else'])
                                if isinstance(arm, dict) and arm.get('t') == 0 and arm.get('c') == 49}
        assert arm_conversion_types == {2, 3}, arm_conversion_types
        checks += 1
        execute('select-promoted-dynamic-value', 'void main(){def(value,true?7:1.25f);print(value&1);}',
                error='bit_and operands must be int, got float and int')
        execute('select-pointer-common-type', '''class Base{int x;}class Child:Base{int y;}
            Base* choose(boolean flag,Child* child,Base* base){return flag?child:base;}
            <T extends Base*> T maybe(boolean flag,T value){return flag?value:null;}
            <T extends Base*> T reverse(boolean flag,T value){return flag?null:value;}
            void main(){Base* base=new Base();Child* child=new Child();base.x=4;child.x=9;child.y=10;
                print(choose(true,child,base).x);print(choose(false,child,base).x);
                print(maybe<Child*>(true,child).y);print(maybe<Child*>(false,child)==null);
                print(reverse<Child*>(false,child).y);print(reverse<Child*>(true,child)==null);
                (true?child:child).x=11;print(child.x);delete child;delete base;
            }''', '9\n4\n10\ntrue\n10\ntrue\n11\n')
        tag = '''class Tag{int n;Tag(int value){n=value;print("+"+n);}~Tag(){print("-"+n);}}
            Tag value(int n){Tag result(n);return result;}
            Tag* automatic(int n){Tag* result(n);return result;}
        '''
        execute('select-owned-literal', tag + '''void main(){
            Tag chosen=true?value(1):value(2);print(chosen.n);print("end");
        }''', '+1\n1\nend\n-1\n')
        execute('select-statement-temporary', tag + '''void main(){
            print((false?value(3):value(4)).n);print("next");
        }''', '+4\n4\n-4\nnext\n')
        execute('select-automatic-pointer', tag + '''void main(){
            Tag* chosen=true?automatic(5):automatic(6);print(chosen.n);print("end");
        }''', '+5\n5\nend\n-5\n')
        execute('select-unselected-destructor', '''class Tag{int n;Tag(int value){n=value;}
            ~Tag(){print("drop "+n);if(n==2){int bad=1/0;}}}
            Tag value(int n){Tag result(n);return result;}
            void main(){print((true?value(1):value(2)).n);print("next");}
        ''', '1\ndrop 1\nnext\n')
        execute('select-destructor-error', '''class Tag{int n;Tag(int value){n=value;}
            ~Tag(){print("drop "+n);int bad=1/0;}}
            Tag value(int n){Tag result(n);return result;}
            void main(){print((true?value(3):value(4)).n);print("unreachable");}
        ''', '3\ndrop 3\n', error='Division by zero')
        _, first_error = execute('select-first-error-cleanup', '''class Tag{int n;Tag(int value){n=value;}
            ~Tag(){print("drop "+n);int bad=2147483647+1;}}
            Tag value(int n){Tag result(n);return result;}
            int fail(Tag owned){return 1/0;}
            void main(){print(fail(true?value(7):value(8)));}
        ''', 'drop 7\n', error='Division by zero', debug=True)
        assert 'Tag::~Tag' not in first_error.stderr and 'Integer overflow' not in first_error.stderr, first_error.stderr
        checks += 1
        colon, _ = execute('select-colon-ids', '''#namespace 1234
            extern int colonFull():12340001;int colonFull(){return 3;}
            int colonLocal():0002{return 2;}
            int colonExponent():1e5{return 4;}
            int colonLetter():000a{return 5;}
            extern int colonMath():4d410008;
            extern int colonHuge():1e999;
            void main(){print(colonLocal()+colonFull()+colonExponent()+colonLetter());
                print((false?1:1e5)==100000.0);print((false?1:1.5)==1.5);print((false?1:0010)==10);}
        ''', '14\ntrue\ntrue\ntrue\n')
        colon_ast = json.loads((work / 'select-colon-ids.ast.json').read_text(encoding='utf-8'))
        for name, ident in [('colonLocal', 0x12340002), ('colonFull', 0x12340001),
                            ('colonExponent', 0x123401e5), ('colonLetter', 0x1234000a),
                            ('colonMath', 0x4d410008), ('colonHuge', 0x0001e999)]:
            assert int(colon_ast['abstract'][name], 16) == ident, (name, colon_ast['abstract'][name])
        checks += 1
        execute('select-base-initializer-colon', '''class Base{int x;Base(int n){x=n;}}
            class Child:Base{int y;Child():0003 : Base(1){y=2;}}
            class Bare:Base{int y;Bare(): Base(2){y=3;}}
            void main(){Child value;Bare other;print(value.x+value.y);print(other.x+other.y);}
        ''', '3\n5\n')
        macro_select = '#define CHOOSE ?\nint main(){def(value,1);return value CHOOSE 2:3;}\n'
        macro_column = macro_select.splitlines()[1].index('CHOOSE') + 1
        execute('select-macro-position', macro_select,
                error=f'Condition must be boolean, got int\n  at main (select-macro-position.azs:2:{macro_column})', debug=True)
        for label, source, expected in [
                ('condition', 'int main(){return 1?2:3;}', 'must be boolean'),
                ('incompatible', 'void main(){print(true?1:"text");}', 'incompatible types'),
                ('void', 'void empty(){}void main(){true?empty():empty();}', 'cannot use void'),
                ('missing-colon', 'int main(){return true?1;}', ''),
                ('missing-arm', 'int main(){return true?1:;}', ''),
                ('pointer-narrow', 'class Base{int x;}class Child:Base{int y;}void main(){Base* b=new Base();Child* c=new Child();Child* bad=true?c:b;}', ''),
                ('readonly-arm', 'class P{int x;}class List{P item;P operator[](int index){return item;}}void main(){List values;P local;(true?values[0]:local).x=1;}', '')]:
            compile_case('select-invalid-' + label, source, error=expected)
        execute('select-dynamic-condition', 'void main(){def(flag,1);print(flag?2:3);}',
                error='Condition must be boolean, got int')

        switched, _ = execute('switch-default-middle', '''int choose(int value){
            switch(value){case 0:return 10;default:return 99;case 2:return 20;}
        }
        void main(){print(choose(0));print(choose(2));print(choose(8));}
        ''', '10\n20\n99\n')
        switch_ast = json.loads((work / 'switch-default-middle.ast.json').read_text(encoding='utf-8'))

        def source_switches(value):
            if isinstance(value, dict):
                if value.get('t') == 'ctrl' and value.get('call') == 'switch':
                    yield value
                for child in value.values():
                    yield from source_switches(child)
            elif isinstance(value, list):
                for child in value:
                    yield from source_switches(child)

        switch_nodes = list(source_switches(switch_ast))
        assert switch_nodes and all(len(node['param']) == 2 for node in switch_nodes), switch_ast
        sections = switch_nodes[0]['param'][1]
        assert all(section.get('t') == 'ctrl' and section.get('call') == 'case' and len(section['param']) == 1 for section in sections)
        assert sections[0]['labels'] == [0] and sections[1].get('default') is True and sections[2]['labels'] == [2], sections
        assert switched[1]['exec-version'] == 10 and max(controls(switched[1])) <= 50
        checks += 1
        execute('switch-int-group-and-final', '''void run(int n){
            switch(n){case -1:case 2:print("group");break;case 3:print("three");break;default:print("default");}
            print("after");}
            void main(){run(-1);run(2);run(3);run(8);}
        ''', 'group\nafter\ngroup\nafter\nthree\nafter\ndefault\nafter\n')
        execute('switch-string', r'''int choose(string value){switch(value){
            case "a":return 1;default:return 9;case "a\u0000😀":return 2;case "":return 3;}}
            void main(){print(choose("a"));print(choose("a\u0000😀"));print(choose(""));print(choose("absent"));}
        ''', '1\n2\n3\n9\n')
        execute('switch-evaluate-once', '''int calls;
            int next(){calls+=1;return 2;}
            void main(){switch(next()){case 1:print(10);break;case 2:print(20);break;default:print(99);}print(calls);}
        ''', '20\n1\n')
        execute('switch-while-control', '''void main(){int i=0;int total=0;
            while(i<5){i+=1;switch(i){case 2:continue;case 4:break;default:total+=i;break;}total+=10;}
            print(total);}
        ''', '49\n')
        execute('switch-for-continue', '''void main(){int steps=0;int total=0;
            for(int i=0;i<4;i+=1){switch(i){case 1:continue;case 2:break;default:total+=i;break;}steps+=1;}
            print(steps);print(total);}
        ''', '3\n3\n')
        execute('switch-nested-continue', '''void main(){int i=0;int total=0;
            while(i<3){i+=1;switch(i){case 1:{switch(i){case 1:continue;default:break;}break;}
                default:total+=i;break;}total+=10;}
            print(total);}
        ''', '25\n')
        execute('switch-inner-loop', '''void main(){int total=0;
            switch(1){case 1:{int i=0;while(i<3){i+=1;if(i==2)continue;total+=i;}break;}default:total=99;}
            print(total);switch(1){case 1:switch(2){case 2:print(2);break;default:print(99);}print(3);break;default:print(99);}}
        ''', '4\n2\n3\n')
        execute('switch-scope-cleanup', '''class Tag{int n;Tag(int value){n=value;print("+"+n);}~Tag(){print("-"+n);}}
            void main(){int i=0;while(i<3){i+=1;switch(i){
                case 1:{Tag value(i);continue;}
                case 2:{Tag value(i);break;}
                default:{Tag value(i);}}
                print("after "+i);}}
        ''', '+1\n-1\n+2\n-2\nafter 2\n+3\n-3\nafter 3\n')
        execute('switch-missing-return-success', '''int choose(int n){switch(n){
            case 0:switch(n){case 0:return 4;default:return 5;}
            default:return 6;}}
            void main(){print(choose(0));print(choose(1));}
        ''', '4\n6\n')
        for label, source, expected in [
                ('before-label', 'void main(){switch(1){print(1);case 1:break;}}', ''),
                ('case-outside', 'void main(){case 1:break;}', ''),
                ('default-outside', 'void main(){default:print(1);}', ''),
                ('fallthrough', 'void main(){switch(1){case 1:print(1);case 2:print(2);}}', ''),
                ('conditional-fallthrough', 'void run(boolean flag){switch(1){case 1:if(flag)break;case 2:print(2);}}void main(){}', ''),
                ('duplicate-int', 'void main(){switch(1){case 1:break;case 1:break;}}', ''),
                ('duplicate-string', 'void main(){switch("x"){case "x":break;case "x":break;}}', ''),
                ('duplicate-default', 'void main(){switch(1){default:break;default:break;}}', ''),
                ('wrong-label-int', 'void main(){switch(1){case "x":break;}}', ''),
                ('wrong-label-string', 'void main(){switch("x"){case 1:break;}}', ''),
                ('nonliteral', 'void main(){int label=1;switch(1){case label:break;}}', ''),
                ('computed-label', 'void main(){switch(1){case 1+1:break;}}', ''),
                ('boolean', 'void main(){switch(true){default:break;}}', ''),
                ('double', 'void main(){switch(1.0){default:break;}}', ''),
                ('address', 'void main(){address p=alloc(1);switch(p){default:break;}}', ''),
                ('any', 'void main(){def(value,1);switch(value){default:break;}}', 'switch (int(value))'),
                ('continue-without-loop', 'void main(){switch(1){case 1:continue;}}', ''),
                ('missing-return-no-default', 'int choose(int n){switch(n){case 1:return 2;}}void main(){}', 'without returning'),
                ('missing-return-break', 'int choose(int n){switch(n){case 1:break;default:return 2;}}void main(){}', 'without returning'),
                ('expression', 'int main(){return switch(1){default:break;};}', '')]:
            compile_case('switch-invalid-' + label, source, error=expected)

        strings, _ = execute('strings-builtins', r'''void main(){string text="A中😀\u0000Z";
            print(str_length(text));print(str_byte_length(text));print(str_substring(text,1,3));
            print(str_char_at(text,2));print(str_code_at(text,2));print(str_from_code(128512));
            print(str_index_of(text,"😀"));print(str_starts_with(text,"A中"));print(str_ends_with(text,"\u0000Z"));
        }''', '5\n10\n中😀\n😀\n128512\n😀\n2\ntrue\ntrue\n')

        def call_ids(value):
            if isinstance(value, dict):
                if value.get('t') == 1:
                    yield value['id']
                for child in value.values():
                    yield from call_ids(child)
            elif isinstance(value, list):
                for child in value:
                    yield from call_ids(child)

        assert set(range(0x0abd000b, 0x0abd0014)) <= set(call_ids(strings[1])), strings[1]
        checks += 1
        execute('strings-methods-and-index', r'''void main(){string text="A中😀\u0000Z";
            print(text.length());print(text.byte_length());print(text.substring(1,3));
            print(text.char_at(2));print(text.code_at(2));print(text[2]);print(text[2].length());
            print(text.index_of("😀"));print(text.index_of("😀",3));print(text.contains("😀"));print(text.contains("absent"));
            print(text.starts_with(""));print(text.ends_with(""));print(text.index_of("",5));
            print(text.substring(5,5)=="");print(text[3]=="\u0000");print(str_from_code(0)=="\u0000");
        }''', '5\n10\n中😀\n😀\n128512\n😀\n1\n2\n-1\ntrue\nfalse\ntrue\ntrue\n5\ntrue\ntrue\ntrue\n')
        execute('strings-empty-and-scalars', r'''void main(){string empty="";
            print(empty.length());print(empty.byte_length());print("["+empty.substring(0,0)+"]");
            print(empty.index_of(""));print(empty.starts_with(""));print(empty.ends_with(""));
            print(str_length("e\u0301"));print(str_byte_length("e\u0301"));
            print(str_char_at("e\u0301",1)=="\u0301");
            print(str_code_at(str_from_code(1114111),0)==1114111);
        }''', '0\n0\n[]\n0\ntrue\ntrue\n2\n3\ntrue\ntrue\n')
        execute('strings-call-order', '''int trace;
            string receiver(){trace=trace*10+1;return "abcd";}
            int begin(){trace=trace*10+2;return 1;}
            int end(){trace=trace*10+3;return 3;}
            string needle(){trace=trace*10+4;return "bc";}
            void main(){print(receiver().substring(begin(),end()));print(trace);
                trace=0;print(receiver()[begin()]);print(trace);
                trace=0;print(receiver().contains(needle()));print(trace);
                trace=0;print(receiver().index_of(needle(),begin()));print(trace);}
        ''', 'bc\n123\nb\n12\ntrue\n14\n1\n142\n')
        execute('strings-class-name-shadow', '''class Shadow{int n;
            int str_length(string value){return 99;}
            string str_char_at(string value,int index){return "wrong";}
            int str_index_of(string value,string needle,int from){return -1;}
            int length(){return 88;}
            int read(){string value="abc";return value.length();}
            string first(){string value="中";return value[0];}
            boolean found(){string value="abc";return value.contains("b");}}
            void main(){Shadow value;print(value.read());print(value.first());print(value.found());print(value.length());}
        ''', '3\n中\ntrue\n88\n')
        text_literal = json.dumps('A中😀\0Z', ensure_ascii=False)
        for label, expression, expected in [
                ('char-negative', 'text.char_at(-1)', 'String index -1 is outside length 5'),
                ('char-end', 'text[5]', 'String index 5 is outside length 5'),
                ('code-end', 'text.code_at(5)', 'String index 5 is outside length 5'),
                ('substring-negative', 'text.substring(-1,2)', 'String index -1 is outside length 5'),
                ('substring-end', 'text.substring(0,6)', 'String index 6 is outside length 5'),
                ('substring-order', 'text.substring(4,3)', 'String substring start 4 is after end 3'),
                ('index-from-negative', 'text.index_of("",-1)', 'String index -1 is outside length 5'),
                ('index-from-end', 'text.index_of("",6)', 'String index 6 is outside length 5')]:
            execute('strings-bounds-' + label, f'void main(){{string text={text_literal};print({expression});}}', error=expected)
        execute('strings-empty-index', 'void main(){print(""[0]);}', error='String index 0 is outside length 0')
        for code in (-1, 55296, 57343, 1114112):
            execute('strings-invalid-code-' + str(code), f'void main(){{print(str_from_code({code}));}}',
                    error=f'Invalid Unicode code point: {code}')
        for label, source, expected in [
                ('immutable', 'void main(){string text="abc";text[0]="z";}', 'Strings are immutable'),
                ('bad-index-type', 'void main(){print("abc"[true]);}', ''),
                ('bad-length-type', 'void main(){print(str_length(1));}', ''),
                ('bad-code-type', 'void main(){print(str_from_code(1.5));}', ''),
                ('void-argument', 'void empty(){}void main(){print(str_length(empty()));}', ''),
                ('bad-arity', 'void main(){print(str_substring("abc",0));}', ''),
                ('unknown-method', 'void main(){string text="abc";text.unknown();}', '')]:
            compile_case('strings-invalid-' + label, source, error=expected)
        execute('operators-documented-example', (ROOT / 'compiler/examples/operators-regressions.azs').read_text(encoding='utf-8'), '0\n')

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
            selection_binary, _ = compile_case('select-jni', '''#namespace 5151
                float selectedFloat(boolean flag):0050{return flag?16777217:1.25f;}
                double selectedDoubleInt(boolean flag):0051{return flag?7:2.5;}
                double selectedDoubleFloat(boolean flag):0052{return flag?1.25f:2.5;}
                int lazy(boolean flag):0053{return flag?7:1/0;}
                int dynamicCondition():0054{def(flag,1);return flag?2:3;}
                int promotedOperand(boolean flag):0055{def(value,flag?1:2.5f);return value&1;}
                int currentScope():0056{int depth=getDepth();return true?getDepth()-depth:1000;}
            ''')
            selection_java = work / 'SelectionHost.java'
            selection_java.write_text(r'''import azertia.AbdInvoker;
import java.io.File;
public class SelectionHost {
 static void expect(int local,Object expected,Object... arguments){
  Object actual=AbdInvoker.invoke(0x51510000|local,arguments);
  if(actual==null||actual.getClass()!=expected.getClass()||!expected.equals(actual))throw new AssertionError(local+": "+actual);
 }
 static void reject(int local,String message,Object... arguments){
  try{AbdInvoker.invoke(0x51510000|local,arguments);throw new AssertionError("selection accepted");}
  catch(IllegalStateException error){
   String expected=message+"\n  at function "+String.format("0x%08x",0x51510000|local);
   if(error.getClass()!=IllegalStateException.class||!expected.equals(error.getMessage()))throw new AssertionError(error.getMessage());
  }
 }
 public static void main(String[] args){
  try{
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   expect(0x50,Float.valueOf(16777216.0f),true);expect(0x50,Float.valueOf(1.25f),false);
   expect(0x51,Double.valueOf(7.0),true);expect(0x51,Double.valueOf(2.5),false);
   expect(0x52,Double.valueOf(1.25),true);expect(0x52,Double.valueOf(2.5),false);
   expect(0x53,Integer.valueOf(7),true);
   reject(0x53,"Division by zero",false);
   reject(0x54,"Condition must be boolean, got int");
   reject(0x55,"bit_and operands must be int, got float and int",true);
   reject(0x55,"bit_and operands must be int, got float and int",false);
   expect(0x56,Integer.valueOf(0));
  }finally{AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            compiled = run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, selection_java])
            assert compiled.returncode == 0 and not compiled.stderr, (compiled.stdout, compiled.stderr)
            result = run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                          '-cp', os.pathsep.join((str(work), str(args.bridge))), 'SelectionHost', selection_binary])
            assert result.returncode == 0 and not result.stdout and not result.stderr, (result.stdout, result.stderr)
            checks += 1
            string_binary, _ = compile_case('strings-jni', '''#namespace 5151
                int length(string value):0060{return value.length();}
                int bytes(string value):0061{return value.byte_length();}
                string substring(string value,int begin,int end):0062{return value.substring(begin,end);}
                string charAt(string value,int index):0063{return value[index];}
                int codeAt(string value,int index):0064{return value.code_at(index);}
                string fromCode(int code):0065{return str_from_code(code);}
                int indexOf(string value,string needle,int from):0066{return value.index_of(needle,from);}
                boolean starts(string value,string prefix):0067{return value.starts_with(prefix);}
                boolean ends(string value,string suffix):0068{return value.ends_with(suffix);}
                boolean contains(string value,string needle):0069{return value.contains(needle);}
            ''')
            string_java = work / 'UnicodeStringsHost.java'
            string_java.write_text(r'''import azertia.AbdInvoker;
import java.io.File;
public class UnicodeStringsHost {
 static void expect(int local,Object expected,Object... arguments){
  Object actual=AbdInvoker.invoke(0x51510000|local,arguments);
  if(actual==null||actual.getClass()!=expected.getClass()||!expected.equals(actual))throw new AssertionError(local+": "+actual);
 }
 static void reject(int local,Class<? extends RuntimeException> type,String message,Object... arguments){
  try{AbdInvoker.invoke(0x51510000|local,arguments);throw new AssertionError("string operation accepted");}
  catch(RuntimeException error){
   String expected=message+"\n  at function "+String.format("0x%08x",0x51510000|local);
   if(error.getClass()!=type||!expected.equals(error.getMessage()))throw new AssertionError(error.getMessage(),error);
  }
 }
 public static void main(String[] args){
  try{
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   String value="A中😀"+(char)0+"Z";
   expect(0x60,Integer.valueOf(5),value);expect(0x61,Integer.valueOf(10),value);
   expect(0x62,"中😀"+(char)0,value,1,4);expect(0x63,"😀",value,2);
   expect(0x63,String.valueOf((char)0),value,3);expect(0x64,Integer.valueOf(128512),value,2);
   expect(0x65,"😀",128512);expect(0x65,String.valueOf((char)0),0);
   expect(0x66,Integer.valueOf(2),value,"😀",0);expect(0x66,Integer.valueOf(5),value,"",5);
   expect(0x67,Boolean.TRUE,value,"A中");expect(0x68,Boolean.TRUE,value,String.valueOf((char)0)+"Z");
   expect(0x69,Boolean.TRUE,value,"😀");expect(0x69,Boolean.FALSE,value,"absent");
   reject(0x63,IndexOutOfBoundsException.class,"String index -1 is outside length 5",value,-1);
   reject(0x64,IndexOutOfBoundsException.class,"String index 5 is outside length 5",value,5);
   reject(0x62,IndexOutOfBoundsException.class,"String substring start 4 is after end 3",value,4,3);
   reject(0x66,IndexOutOfBoundsException.class,"String index 6 is outside length 5",value,"",6);
   reject(0x65,IllegalArgumentException.class,"Invalid Unicode code point: 55296",55296);
   reject(0x65,IllegalArgumentException.class,"Invalid Unicode code point: 1114112",1114112);
   expect(0x63,"😀",value,2);
  }finally{AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            compiled = run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, string_java])
            assert compiled.returncode == 0 and not compiled.stderr, (compiled.stdout, compiled.stderr)
            result = run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                          '-cp', os.pathsep.join((str(work), str(args.bridge))), 'UnicodeStringsHost', string_binary])
            assert result.returncode == 0 and not result.stdout and not result.stderr, (result.stdout, result.stderr)
            checks += 1

    print(f'Language operators end-to-end checks passed: {checks} (source/AST/ABD identity, bitwise, shifts, conversions, selection, switch, Unicode strings, lifetimes and errors).')


if __name__ == '__main__':
    main()
