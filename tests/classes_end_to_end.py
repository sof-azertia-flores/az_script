#!/usr/bin/env python3
"""Source/AST/ABD/runtime regressions for class pointers and object lifetimes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
POINT = '''class Point {
    int x;
    Point(int value) { this.x = value; }
    int get() { return this.x; }
    ~Point() { print(this.x); }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    checks = 0
    with tempfile.TemporaryDirectory(prefix='classes-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def compile_case(source, name, valid=True):
            src = work / (name + '.azs')
            src.write_text(source, encoding='utf-8')
            abd = work / (name + '.abd')
            ast = work / (name + '.ast.json')
            instructions = work / (name + '.exec.json')
            command = ['java', '-cp', args.classpath, 'azertia.Main']
            result = subprocess.run(command + ['compile', str(src), '-o', str(abd),
                                    '--ast', str(ast), '--exec-json', str(instructions)],
                                    cwd=ROOT, capture_output=True, text=True, timeout=25)
            if valid:
                assert result.returncode == 0, (name, result.stdout, result.stderr)
                recompiled = work / (name + '.roundtrip.abd')
                again = subprocess.run(command + ['compile-json', str(ast), '-o', str(recompiled)],
                                       cwd=ROOT, capture_output=True, text=True, timeout=25)
                assert again.returncode == 0, (name, again.stdout, again.stderr)
                assert abd.read_bytes() == recompiled.read_bytes(), (name, 'AST roundtrip differs')
                return abd, json.loads(instructions.read_text(encoding='utf-8'))
            assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
            assert 'Exception in thread' not in result.stderr, (name, result.stderr)
            return None, None

        def execute(name, source, output='', error=None):
            nonlocal checks
            abd, instructions = compile_case(source, name)
            result = subprocess.run([str(args.runner), str(abd)], cwd=ROOT,
                                    capture_output=True, text=True, timeout=15)
            assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
            if error is None:
                assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            else:
                assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                if error is not True:
                    assert error.lower() in result.stderr.lower(), (name, result.stderr, error)
            checks += 1
            return instructions

        # Reference and ownership behaviour uses pointer objects: "C * x(...)" is
        # destroyed with its block, "new" objects need delete. Literal objects
        # (plain "C x(...)") have their own suite.
        execute('example', POINT + '''Point* makePoint(){Point * p(3);return p;}
            void main(){Point* a=makePoint();Point* b=new Point(5);delete b;}''', '5\n3\n')
        execute('automatic-forms', '''class P{int x=7;~P(){print(x);}}
            void main(){P * a();P * b();print(a.x+b.x);}''', '14\n7\n7\n')
        execute('aliases', POINT + '''void main(){Point * a(1);Point* b=a;b.x=4;print(a.get());}''', '4\n4\n')
        execute('delete-alias', POINT + '''void main(){Point* a=new Point(6);Point* b=a;delete b;}''', '6\n')
        execute('nested-blocks', POINT + '''void main(){Point * a(1);{Point * b(2);Point * c(3);}print(9);}''', '3\n2\n9\n1\n')
        execute('loop-break', POINT + '''void main(){int i=0;while(i<4){Point * p(i);i+=1;if(i==2){break;}}}''', '0\n1\n')
        execute('implicit-block', POINT + '''void main(){if(true) Point * p(7);print(9);}''', '7\n9\n')
        execute('nested-return', POINT + '''Point* create(){Point * outer(1);{Point * result(2);while(true){return result;}}}
            void main(){Point* p=create();print(p.x);}''', '1\n2\n2\n')
        execute('forwarded-return', POINT + '''Point* make(){Point * p(8);return p;}
            Point* again(){return make();}void main(){Point* a=again();print(a.x);}''', '8\n8\n')
        execute('borrowed-return', POINT + '''Point* identity(Point* p){return p;}
            void main(){Point * outer(4);{Point* alias=identity(outer);}print(outer.x);}''', '4\n4\n')
        execute('manual-return', POINT + '''Point* make(){return new Point(5);}
            void main(){Point* p=make();{Point* alias=p;}print(p.x);delete p;}''', '5\n5\n')
        execute('manual-survives-block', POINT + '''void main(){Point* p=null;{p=new Point(2);}print(p.x);delete p;}''', '2\n2\n')
        execute('manual-not-auto-destroyed', POINT + '''void main(){Point* p=new Point(2);}''')
        execute('raw-free-unregisters', POINT + '''void main(){var raw=new Point(8);mem_free(raw);}''')
        execute('reassigned-binding', POINT + '''void main(){Point* p=new Point(2);{Point * local(1);local=p;}print(p.x);delete p;}''', '1\n2\n2\n')
        execute('null-and-equality', POINT + '''void main(){Point* p=null;delete p;delete null;
            Point * a(1);Point* b=a;print(p==null);print(a==b);print(a!=null);}''', 'true\ntrue\ntrue\n1\n')
        execute('fields-and-types', '''class All {int i;float f;double d;boolean b;string s;All* next;}
            int takeInt(int x){return x;}float takeFloat(float x){return x;}
            double takeDouble(double x){return x;}boolean takeBool(boolean x){return x;}
            string takeString(string x){return x;}
            void main(){All a;print(takeInt(a.i));print(takeFloat(a.f));print(takeDouble(a.d));
            print(takeBool(a.b));print("["+takeString(a.s)+"]");print(a.next==null);}''', '0\n0\n0\nfalse\n[]\ntrue\n')
        execute('field-init-order', '''class P{int first=later+1;int later=10;}
            void main(){P p;print(p.first);print(p.later);}''', '1\n10\n')
        execute('member-name-resolution', '''#gvar x
            class P{int x=3;int value(int x){return x+this.x;}int other(){return x;}}
            void main(){x=100;P p;print(p.value(4));print(p.other());}''', '7\n3\n')
        execute('recursive-method', '''class P{int unused;int fact(int n){if(n<=1){return 1;}return n*fact(n-1);}}
            void main(){P p;print(p.fact(5));}''', '120\n')
        execute('qualified-method-names', '''class A{int n;int main(){return 2;}}
            class B{int n;int main(){return 3;}}
            void main(){A a;B b;print(a.main()+b.main());}''', '5\n')
        execute('members-named-like-builtins', '''class C{int x=4;
            int alloc(int n){return n+100;}int mem_get(int n){return n+200;}
            void make_free(int n){print(n);}}
            void main(){C a;C* b=new C();print(a.x);print(b.alloc(1));print(b.mem_get(1));delete b;}''', '4\n101\n201\n')
        execute('receiver-evaluated-once', '''#gvar count
            class P{int x=1;P* self(){return this;}int get(){return x;}}
            P* select(P* p){count+=1;return p;}
            void main(){count=0;P * p();select(p).x=9;print(select(p).self().get());print(count);}''', '9\n2\n')
        execute('constructor-borrows-this', '''class P{int x;P(){self().x=6;}
            P* self(){return this;}int get(){return x;}}
            void main(){P p;print(p.get());}''', '6\n')
        execute('destructor-borrows-this', '''class P{int x=6;P* self(){return this;}
            ~P(){print(self().x);}}void main(){P automatic;P* manual=new P();delete manual;}''', '6\n6\n')
        execute('argument-order', '''#gvar n
            class P{int x;P(int first,int second){x=first*10+second;}int sum(int a,int b){return a*10+b;}}
            int next(){n+=1;return n;}
            void main(){n=0;P p(next(),next());print(p.x);print(p.sum(next(),next()));}''', '12\n34\n')
        execute('forward-class-field', '''class Box{Point* p=new Point(7);}
            ''' + POINT + '''void main(){Box b;print(b.p.get());delete b.p;}''', '7\n7\n')
        execute('nonowning-field', POINT + '''class Box{Point* p;Box(Point* value){p=value;}~Box(){print(99);}}
            void main(){Point* p=new Point(3);{Box b(p);}print(p.x);delete p;}''', '99\n3\n3\n')
        execute('explicit-child-delete', POINT + '''class Box{Point* p=new Point(3);~Box(){delete p;}}
            void main(){Box b;}''', '3\n')
        execute('return-null', POINT + '''Point* empty(){return null;}void main(){Point* p=empty();print(p==null);}''', 'true\n')
        execute('global-cleanup', POINT + '''Point* main(){Point * p(7);return p;}
            void __script_pre_destroy(){print("hook");}''', '1\nhook\n7\n')
        execute('null-field-access', POINT + '''void main(){Point* p=null;print(p.x);}''', error=True)
        execute('null-nonzero-offset', '''class P{int first;int second;}
            void main(){P live;P* empty=null;print(empty.second);}''', error=True)
        execute('null-method-call', '''class P{int unused;int get(){return 7;}}
            void main(){P* p=null;print(p.get());}''', error=True)
        execute('delete-automatic', POINT + '''void main(){Point * p(8);delete p;}''', '8\n', error=True)
        execute('double-delete', POINT + '''void main(){Point* p=new Point(8);delete p;delete p;}''', '8\n', error=True)
        execute('use-after-delete', POINT + '''void main(){Point* p=new Point(8);delete p;print(p.x);}''', '8\n', error=True)
        execute('nonowning-return', POINT + '''class Box{Point* p;Box(Point* value){p=value;}~Box(){print(99);}}
            Box* make(){Point * child(8);Box * result(child);return result;}
            void main(){Box* b=make();print(b.p.get());}''', '8\n99\n', error=True)
        execute('constructor-failure', '''class P{int x;P(){print("ctor");int fail=1/0;}~P(){print("dtor");}}
            void main(){P * p();}''', 'ctor\n', error='Division by zero')
        execute('initializer-failure', '''class P{int x=1/0;~P(){print("dtor");}}
            void main(){P* p=new P();}''', error='Division by zero')
        execute('error-unwind', POINT + '''void main(){Point * a(1);{Point * b(2);int fail=1/0;}}''', '2\n1\n', error='Division by zero')
        execute('destructor-failure', POINT + '''class Bad{int x;~Bad(){print("bad");int fail=1/0;}}
            void main(){Point * p(1);Bad * b();}''', 'bad\n1\n', error='Division by zero')
        execute('preserve-original-error', POINT + '''class Bad{int x;~Bad(){print("bad");int fail=1/0;}}
            void main(){Point * p(1);Bad * b();int fail=2147483647+1;}''', 'bad\n1\n', error='Integer overflow')
        execute('delete-destructor-failure', '''class Bad{int x;~Bad(){print("bad");int fail=1/0;}}
            void main(){Bad* p=new Bad();delete p;}''', 'bad\n', error='Division by zero')
        execute('recursive-delete', '''class Bad{int x;~Bad(){print("bad");delete this;}}
            void main(){Bad* p=new Bad();delete p;}''', 'bad\n', error=True)

        shape = execute('layout', '''class Pair{int x;int y;}
            void main(){Pair a;Pair* b=new Pair();delete b;}''')
        allocations = []
        def inspect(value):
            if isinstance(value, dict):
                if value.get('t') == 1 and value.get('id') == 0x0ABD0003:
                    allocations.append(value['param'])
                for child in value.values():
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)
        inspect(shape)
        assert allocations and all(params == [2] for params in allocations), allocations
        execute('documented-example', (ROOT / 'compiler/examples/classes-regressions.azs').read_text(encoding='utf-8'), '0\n')

        bad_sources = {
            'empty': 'class P{} void main(){}',
            'unknown-class': 'void main(){Missing p;}',
            'duplicate-field': 'class P{int x;int x;} void main(){}',
            'duplicate-method': 'class P{int x;void f(){}void f(){}} void main(){}',
            'duplicate-constructor': 'class P{int x;P(){}P(int x){}} void main(){}',
            'missing-default-constructor': POINT + 'void main(){Point p;}',
            'wrong-constructor-argument': POINT + 'void main(){Point p("x");}',
            'unknown-field': POINT + 'void main(){Point p(1);print(p.other);}',
            'unknown-method': POINT + 'void main(){Point p(1);p.other();}',
            'wrong-field-type': POINT + 'void main(){Point p(1);p.x="x";}',
            'wrong-class-type': 'class A{int x;}class B{double x;}void main(){A a;B b=a;}',
            'integer-is-not-object': POINT + 'void main(){Point p=0;}',
            'object-is-not-integer': POINT + 'void main(){Point p(1);int x=p;}',
            'dynamic-var': POINT + 'void main(){var p=new Point(1);p.get();}',
            'dynamic-def': POINT + 'void main(){def(p,new Point(1));p.get();}',
            'this-outside-class': 'void main(){print(this);}',
            'this-reassignment': 'class P{int x;void bad(){this=null;}}void main(){}',
            'compound-member': POINT + 'void main(){Point p(1);p.x+=1;}',
            'destructor-parameters': 'class P{int x;~P(int n){}}void main(){}',
            'constructor-value-return': 'class P{int x;P(){return 1;}}void main(){}',
            'object-arithmetic': POINT + 'void main(){Point p(1);print(p+1);}',
            'delete-number': 'void main(){delete 1;}',
        }
        for name, source in bad_sources.items():
            compile_case(source, 'invalid-' + name, valid=False)
            checks += 1
        if args.library:
            assert args.bridge, '--library requires --bridge'
            binary, _ = compile_case(POINT + '''Point* main(){return new Point(41);}
                int read(Point* p){return p.get();}void release(Point* p){delete p;}''', 'jni-source')
            names = json.loads((work / 'jni-source.ast.json').read_text(encoding='utf-8'))['abstract']
            java = work / 'ClassSourceBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class ClassSourceBridge {
 public static void main(String[] args) throws Exception {
  AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.flush();
  int read=Integer.parseUnsignedInt(args[1],16), release=Integer.parseUnsignedInt(args[2],16);
  File saved=new File(args[3]);
  try {
   Object pointer=AbdInvoker.invoke(0x0fff0000);
   if (!Integer.valueOf(41).equals(AbdInvoker.invoke(read,pointer))) throw new AssertionError("class read");
   if (!AbdInvoker.saveStatus(saved)) throw new AssertionError("class snapshot save");
   AbdInvoker.invoke(release,pointer);
   AbdInvoker.loadStatus(saved);
   if (!Integer.valueOf(41).equals(AbdInvoker.invoke(read,pointer))) throw new AssertionError("restored class read");
   AbdInvoker.invoke(release,pointer);
   System.out.println("CLASS_JNI_OK");
  } finally { AbdInvoker.close(); }
 }
}
''', encoding='utf-8')
            subprocess.run(['javac', '--release', '17', '-cp', str(args.bridge), '-d', str(work), str(java)],
                           cwd=ROOT, check=True, timeout=25)
            result = subprocess.run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library),
                                     '-cp', os.pathsep.join([str(work), str(args.bridge)]), 'ClassSourceBridge',
                                     str(binary), names['read'], names['release'], str(work / 'class.snapshot.abd')],
                                    cwd=ROOT, capture_output=True, text=True, timeout=25)
            assert result.returncode == 0 and not result.stderr and 'CLASS_JNI_OK' in result.stdout, (result.stdout, result.stderr)
            checks += 1
    print(f'Class end-to-end checks passed: {checks} (all valid sources also passed AST/ABD roundtrip).')


if __name__ == '__main__':
    main()
