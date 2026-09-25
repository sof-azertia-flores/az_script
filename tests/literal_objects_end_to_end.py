#!/usr/bin/env python3
"""Source/AST/ABD/runtime regressions for literal (value) objects and pointer declarations."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
MAIN = 0x0FFF0000
POINT = '''class Point {
    int x;
    Point(int value) { x = value; }
    int get() { return x; }
    Point * self() { return this; }
    ~Point() { print("~" + x); }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    checks = 0
    with tempfile.TemporaryDirectory(prefix='literal-objects-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        compiler = ['java', '-cp', args.classpath, 'azertia.Main']

        def compile_case(source, name, valid=True):
            src, abd, ast, executable = [work / (name + suffix) for suffix in ('.azs', '.abd', '.ast.json', '.exec.json')]
            src.write_text(source, encoding='utf-8')
            result = subprocess.run(compiler + ['compile', str(src), '-o', str(abd), '--ast', str(ast),
                                    '--exec-json', str(executable)], cwd=ROOT, capture_output=True, text=True, timeout=25)
            if not valid:
                assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'Exception in thread' not in result.stderr, (name, result.stderr)
                return result.stderr
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            again = work / (name + '.roundtrip.abd')
            rebuilt = subprocess.run(compiler + ['compile-json', str(ast), '-o', str(again)],
                                     cwd=ROOT, capture_output=True, text=True, timeout=25)
            assert rebuilt.returncode == 0 and abd.read_bytes() == again.read_bytes(), (name, 'AST roundtrip', rebuilt.stderr)
            return abd, json.loads(ast.read_text(encoding='utf-8')), json.loads(executable.read_text(encoding='utf-8'))

        def execute(name, source, output='', error=None):
            nonlocal checks
            abd, ast, executable = compile_case(source, name)
            result = subprocess.run([str(args.runner), str(abd)], cwd=ROOT, capture_output=True, text=True, timeout=15)
            assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
            if error is None:
                assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            else:
                assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                if error is not True:
                    assert error.lower() in result.stderr.lower(), (name, result.stderr, error)
            checks += 1
            return abd, ast, executable

        # Construction forms and value copies.
        execute('declaration-forms', '''class P{int x=7;~P(){print(x);}}
            void main(){P a;P b();a.x=1;b.x=2;print(a.x+b.x);}''', '3\n2\n1\n')
        execute('copy-each-destroyed', POINT + '''void main(){Point a(1);Point b=a;b.x=2;print(a.get());print(b.get());}''',
                '1\n2\n~2\n~1\n')
        execute('assignment-in-place', POINT + '''void main(){Point a(1);Point * p=a.self();Point b(9);a=b;
            print(p.x);print(p==a.self());}''', '9\ntrue\n~9\n~9\n')
        execute('by-value-parameter', POINT + '''int bump(Point p){p.x=p.x+10;return p.get();}
            void main(){Point a(1);print(bump(a));print(a.get());}''', '~11\n11\n1\n~1\n')
        execute('return-moves-local', POINT + '''Point make(int v){Point p(v);return p;}
            void main(){Point a=make(4);print(a.get());}''', '4\n~4\n')
        execute('return-moves-parameter', POINT + '''Point pass(Point p){return p;}
            void main(){Point a(5);Point b=pass(a);print(b.get());}''', '5\n~5\n~5\n')
        execute('return-field-copies', POINT + '''class Box{Point inner(3);Point read(){return inner;}}
            void main(){Box b;Point c=b.read();c.x=8;print(b.inner.get());}''', '3\n~8\n~3\n')
        execute('pointer-field-copied-shallowly', POINT + '''class Link{int id;Point * target;}
            void main(){Point * shared(6);Link a;a.target=shared;Link b=a;b.target.x=7;print(a.target.x);}''', '7\n~7\n')
        # Members, bases and C++ destruction order.
        execute('member-order', '''class Tag{int id;Tag(int v){id=v;print("+"+id);}~Tag(){print("-"+id);}}
            class Pair{Tag first(1);int n=5;Tag second(2);Pair(){print("body");}~Pair(){print("~Pair");}}
            void main(){Pair p;}''', '+1\n+2\nbody\n~Pair\n-2\n-1\n')
        execute('inherited-member-order', '''class Tag{int id;Tag(int v){id=v;}~Tag(){print("-"+id);}}
            class Base{Tag b(1);~Base(){print("~Base");}}
            class Child:Base{Tag c(2);~Child(){print("~Child");}}
            void main(){Child c;Base * asBase=new Child();delete asBase;}''', '~Child\n-2\n~Base\n-1\n~Child\n-2\n~Base\n-1\n')
        execute('members-without-own-destructor', '''class Tag{int id;Tag(int v){id=v;}~Tag(){print("-"+id);}}
            class Holder{Tag a(1);Tag b(2);}void main(){Holder * h=new Holder();delete h;print("after");}''', '-2\n-1\nafter\n')
        execute('literal-inside-pointer-object', '''class Tag{int id;Tag(int v){id=v;}~Tag(){print("-"+id);}}
            class Box{Tag tag(4);~Box(){print("~Box "+tag.id);}}
            void main(){Box * b();b.tag.id=5;print(b.tag.id);}''', '5\n~Box 5\n-5\n')
        execute('construction-failure-destroys-members', '''class Tag{int id;Tag(int v){id=v;}~Tag(){print("-"+id);}}
            class Bad{Tag done(1);Bad(){int z=1/0;}~Bad(){print("~Bad");}}void main(){Bad b;}''',
                '-1\n', error='Division by zero')
        execute('value-constructor-arguments', POINT + '''class Line{Point a(0);Point b(0);Line(Point from,Point to){a=from;b=to;}}
            void main(){Point s(1);Point e(2);Line l(s,e);print(l.a.get()+l.b.get());}''', '~2\n~1\n3\n~2\n~1\n~2\n~1\n')
        # Temporaries and expired addresses.
        execute('statement-temporaries', POINT + '''Point make(int v){Point p(v);return p;}
            void main(){print(make(3).get());print("next");}''', '3\n~3\nnext\n')
        execute('condition-temporaries', POINT + '''Point make(int v){Point p(v);return p;}
            void main(){int i=0;while(make(i).get()<2){print("body");i+=1;}}''', '~0\nbody\n~1\nbody\n~2\n')
        execute('expired-this', POINT + '''Point * escape(){Point local(4);return local.self();}
            void main(){Point * p=escape();print(p.x);}''', '~4\n', error='expired')
        execute('expired-member', POINT + '''class Box{Point inner(3);}
            void main(){Point * p=null;{Box b;p=b.inner.self();}print(p.get());}''', '~3\n', error='expired')
        execute('pointer-auto-object', POINT + '''void main(){Point * a(1);Point * b=a;b.x=2;print(a.get());}''', '2\n~2\n')
        execute('bare-pointer-is-empty', POINT + '''void main(){Point * p;print(p==null);p=new Point(2);print(p.get());
            delete p;for(Point * i;i==null;){i=new Point(3);delete i;}print("done");}''', 'true\n2\n~2\n~3\ndone\n')
        execute('bare-pointer-dereference', POINT + '''void main(){Point * p;print("before");print(p.get());}''',
                'before\n', error=True)
        execute('pointer-manual-object', POINT + '''void main(){Point * m=new Point(3);Point * alias=m;delete alias;print("done");}''',
                '~3\ndone\n')
        execute('documented-example', (ROOT / 'compiler/examples/classes-regressions.azs').read_text(encoding='utf-8'), '0\n')
        _, _, shape = execute('abi-types', POINT + '''Point echo(Point p){return p;}Point * where(Point * p){return p;}
            void main(){Point a(1);Point b=echo(a);Point * c=where(b.self());}''', '~1\n~1\n')
        types = {tuple(f['param-types']) + (f['return-type'],) for f in shape['f']}
        # Literal objects cross calls as object values (8); pointers erase to address (7).
        assert (8, 8) in types and (7, 7) in types, types
        checks += 1

        bad_sources = {
            'null-literal': POINT + 'void main(){Point p=null;}',
            'delete-literal': POINT + 'void main(){Point p(1);delete p;}',
            'compare-literals': POINT + 'void main(){Point a(1);Point b(2);print(a==b);}',
            'slice-derived': POINT + 'class Tagged:Point{int t;Tagged():Point(1){}}void main(){Tagged t;Point p=t;}',
            'pointer-to-primitive': 'void main(){int * p=null;}',
            'pointer-field-arguments': POINT + 'class Box{Point * p(1);}void main(){}',
            'primitive-field-arguments': 'class Box{int x(1);}void main(){}',
            'self-containment': 'class Node{int v;Node next;}void main(){}',
            'mutual-containment': 'class A{B b;}class B{A a;}void main(){}',
            'untyped-var': POINT + 'void main(){Point a(1);var b=a;}',
            'untyped-def': POINT + 'void main(){Point a(1);def(b,a);}',
            'global-object': POINT + '#gvar g\nvoid main(){Point a(1);g=a;}',
            'print-object': POINT + 'void main(){Point a(1);print(a);}',
            'raw-memory-object': POINT + 'void main(){Point a(1);address p=alloc(1);mem_get(p)=a;}',
            'untyped-parameter': POINT + 'void take(x){}void main(){Point a(1);take(a);}',
            'value-from-this': 'class P{int x;P copy(){return this;}}void main(){}',
            'pointer-to-value': POINT + 'void main(){Point * p=new Point(1);Point v=p;}',
            'value-to-pointer': POINT + 'void main(){Point v(1);Point * p=v;}',
            'missing-default-member': POINT + 'class Box{Point inner;}void main(){}',
            'object-arithmetic': POINT + 'void main(){Point a(1);print(a+1);}',
        }
        for name, source in bad_sources.items():
            compile_case(source, 'invalid-' + name, valid=False)
            checks += 1

        if args.host:
            abd, _, _ = compile_case(POINT + '''class Box{Point inner(1);Point * owner;}
                Point make(int v){Point p(v);return p;}
                void main(){Box b;Box copy=b;Point t=make(2);b.inner=t;Box * h();Box * m=new Box();delete m;}''', 'host-heap')
            result = subprocess.run([str(args.host), str(abd), 'flush', 'call', str(MAIN), '0', 'heap-empty'],
                                    cwd=ROOT, capture_output=True, text=True, timeout=20)
            assert result.returncode == 0 and result.stdout.splitlines()[-1] == 'empty' and not result.stderr, (result.stdout, result.stderr)
            checks += 1

        if args.library:
            assert args.bridge, '--library requires --bridge'
            binary, ast, _ = compile_case(POINT + '''Point makeValue(){Point p(4);return p;}
                Point * makePointer(){return new Point(5);}int read(Point * p){return p.get();}
                void release(Point * p){delete p;}''', 'jni-literal')
            names = ast['abstract']
            java = work / 'LiteralBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class LiteralBridge {
 public static void main(String[] args) throws Exception {
  try {
   AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.flush();
   int value=Integer.parseUnsignedInt(args[1],16), pointer=Integer.parseUnsignedInt(args[2],16);
   int read=Integer.parseUnsignedInt(args[3],16), release=Integer.parseUnsignedInt(args[4],16);
   try { AbdInvoker.invoke(value); throw new AssertionError("literal object crossed the JNI boundary"); }
   catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("literal object")) throw expected; }
   Object p=AbdInvoker.invoke(pointer);
   if (!Integer.valueOf(5).equals(AbdInvoker.invoke(read,p))) throw new AssertionError("pointer read");
   AbdInvoker.invoke(release,p);
   System.out.println("LITERAL_JNI_OK");
  } finally { AbdInvoker.close(); }
 }
}
''', encoding='utf-8')
            subprocess.run(['javac', '--release', '17', '-cp', str(args.bridge), '-d', str(work), str(java)],
                           cwd=ROOT, check=True, timeout=30)
            result = subprocess.run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                                     '-cp', os.pathsep.join((str(work), str(args.bridge))), 'LiteralBridge', str(binary),
                                     names['makeValue'], names['makePointer'], names['read'], names['release']],
                                    cwd=ROOT, capture_output=True, text=True, timeout=30)
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            assert result.stdout.count('LITERAL_JNI_OK\n') == 1, result.stdout
            assert result.stdout.replace('LITERAL_JNI_OK\n', '') == '~4\n~5\n', result.stdout
            checks += 1
    print(f'Literal object checks passed: {checks} (value copies, destruction order, expiring addresses, '
          f'pointer declarations, AST roundtrip and error paths).')


if __name__ == '__main__':
    main()
