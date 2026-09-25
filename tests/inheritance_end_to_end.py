#!/usr/bin/env python3
"""Single-inheritance source/AST/ABD, cleanup, library and JNI regressions."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='inheritance-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def compile_case(source, name, valid=True):
            src = work / (name + '.azs')
            src.write_text(source, encoding='utf-8')
            abd, ast, executable = (work / (name + suffix)
                                    for suffix in ('.abd', '.ast.json', '.exec.json'))
            result = subprocess.run(compiler + ['compile', str(src), '-o', str(abd),
                                    '--ast', str(ast), '--exec-json', str(executable)],
                                    cwd=ROOT, capture_output=True, text=True, timeout=30)
            if not valid:
                assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'Exception in thread' not in result.stderr, (name, result.stderr)
                return None, None
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            roundtrip = work / (name + '.roundtrip.abd')
            again = subprocess.run(compiler + ['compile-json', str(ast), '-o', str(roundtrip)],
                                   cwd=ROOT, capture_output=True, text=True, timeout=30)
            assert again.returncode == 0, (name, again.stdout, again.stderr)
            assert abd.read_bytes() == roundtrip.read_bytes(), (name, 'AST roundtrip differs')
            tree = json.loads(executable.read_text(encoding='utf-8'))
            assert 'classes' not in tree and 'class-types' not in tree, (name, tree.keys())
            for function in tree['f']:
                assert all(type(t) is int for t in function['param-types']), (name, function)
            return abd, tree

        def run_case(name, abd, output='', error=None, libraries=()):
            nonlocal checks
            command = [str(args.runner), str(abd)]
            for library in libraries:
                command += ['--insert', str(library)]
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=20)
            assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
            if error is None:
                assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            else:
                assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                if error is not True:
                    assert error.lower() in result.stderr.lower(), (name, result.stderr, error)
            checks += 1

        def execute(name, source, output='', error=None):
            abd, tree = compile_case(source, name)
            run_case(name, abd, output, error)
            return tree

        execute('prefix-methods', '''class Base{int x=3;int get(){return x;}void set(int n){x=n;}}
            class Child:Base{int y=7;int sum(){return get()+y;}}
            void main(){Child * c();print(c.get());print(c.sum());c.set(9);print(c.x);print(c.y);
            Base* b=c;b.x=11;print(c.sum());}''', '3\n10\n9\n7\n18\n')
        execute('public-base', '''class Base{int x=4;}class Child:public Base{int y=6;}
            void main(){Child c;print(c.x+c.y);}''', '10\n')
        execute('commented-underscored-base', '''class Base_Type{int x;Base_Type(int n){x=n;}}
            class Child_Type : /* layout */ Base_Type{int y;
            Child_Type(int n) : /* constructor */ Base_Type(n){}}
            void main(){Child_Type c(7);print(c.x);}''', '7\n')
        execute('fieldless-child', '''class Base{int x=8;~Base(){print(x);}}class Child:Base{}
            void main(){Child c;print(c.x);}''', '8\n8\n')
        execute('forward-base', '''class Child:Base{int y=2;}class Base{int x=1;}
            void main(){Child c;print(c.x+c.y);}''', '3\n')
        execute('default-chain', '''class A{int a=1;A(){print("A");}~A(){print("~A");}}
            class B:A{int b=2;~B(){print("~B");}}class C:B{int c=3;}
            void main(){C c;print(c.a+c.b+c.c);}''', 'A\n6\n~B\n~A\n')
        execute('constructor-order', '''int emit(int n){print(n);return n;}
            class A{int a=emit(1);A(){print(2);}~A(){print(6);}}
            class B:A{int b=emit(3);B(){print(4);}~B(){print(5);}}
            void main(){B b;}''', '1\n2\n3\n4\n5\n6\n')
        execute('explicit-base-arguments', '''#gvar counter
            int next(){counter+=1;return counter;}
            class A{int x;A(int first,int second){x=first*10+second;}}
            class B:A{int y;B(int n):A(next(),next()){y=n;}}
            void main(){counter=0;B b(next());print(b.x);print(b.y);print(counter);}''', '23\n1\n3\n')
        execute('base-argument-this', '''class A{int x;A(int value){x=value;}}
            class B:A{int y=7;B(int value):A(value+1){y=value;}}
            void main(){B b(4);print(b.x);print(b.y);}''', '5\n4\n')
        execute('own-defaults-before-initializers', '''class A{int a=9;}
            class B:A{int first=later+a;int later=4;}
            void main(){B b;print(b.first);print(b.later);}''', '9\n4\n')
        execute('field-shadowing', '''class A{int x=2;int read(){return x;}}
            class B:A{int x=8;int own(){return x;}}
            void main(){B * b();A* a=b;print(b.x);print(a.x);print(b.read());print(b.own());
            b.x=9;a.x=3;print(b.read());print(b.own());}''', '8\n2\n2\n8\n3\n9\n')
        execute('field-method-cross-hiding', '''class A{int x=3;int value(){return 4;}}
            class B:A{int value=9;int x(){return 8;}}
            void main(){B * b();A* a=b;print(b.x());print(a.x);print(b.value);print(a.value());}''', '8\n3\n9\n4\n')
        execute('static-method-binding', '''class A{int x;int value(){return 1;}int call(){return value();}}
            class B:A{int y;int value(){return 2;}int own(){return value();}}
            void main(){B * b();A* a=b;print(b.value());print(a.value());print(b.call());print(b.own());}''', '2\n1\n1\n2\n')
        execute('inherited-recursion', '''class A{int x=1;int sum(int n){if(n==0){return x;}return x+sum(n-1);}}
            class B:A{int x=10;}
            void main(){B b;print(b.sum(3));}''', '4\n')
        execute('upcast-assignment-parameter-return', '''class A{int x=5;}
            class B:A{int y=7;}
            int read(A* a){return a.x;}A* asBase(B* b){return b;}
            void main(){B * b();A* a=null;a=b;print(read(b));print(asBase(b).x);print(a==b);}''', '5\n5\ntrue\n')
        execute('upcast-owned-return', '''class A{int x=3;~A(){print("A");}}
            class B:A{int y=7;~B(){print("B");}}
            A* make(){B * b();return b;}A* forward(){return make();}
            void main(){A* a=forward();print(a.x);}''', '3\nB\nA\n')
        execute('upcast-borrowed-return', '''class A{int x=3;~A(){print("A");}}
            class B:A{int y;~B(){print("B");}}
            A* borrow(B* b){return b;}
            void main(){B * b();{A* a=borrow(b);}print(b.x);}''', '3\nB\nA\n')
        execute('upcast-manual-return', '''class A{int x=3;~A(){print("A");}}
            class B:A{int y;~B(){print("B");}}
            A* make(){return new B();}void main(){A* a=make();print(a.x);delete a;}''', '3\nB\nA\n')
        execute('base-alias-delete', '''class A{int x=3;~A(){print(x);}}
            class B:A{int y=7;~B(){print(y);}}
            void main(){B* b=new B();A* a=b;delete a;}''', '7\n3\n')
        execute('base-alias-delete-automatic', '''class A{int x=3;~A(){print(x);}}
            class B:A{int y=7;~B(){print(y);}}
            void main(){B * b();A* a=b;delete a;}''', '7\n3\n', error=True)
        execute('most-derived-destructor-structural-alias', '''class A{int x=3;~A(){print("A");}}
            class B:A{int y=7;~B(){print("B");}}class C{int first;int second;~C(){print("wrong");}}
            void main(){B* b=new B();C* c=b;delete c;}''', 'B\nA\n')
        execute('structural-flat-equivalence', '''class A{int x=3;}class B:A{double y=4.5;}
            class C{int first;double second;}
            void main(){B b;C c=b;print(c.first);print(c.second);}''', '3\n4.5\n')
        execute('recursive-structural-equivalence', '''class A{int x;A* next;}
            class B:A{int y;}class C{int renamed;D* next;int end;}class D{int renamed;D* next;}
            void main(){B b;C c=b;print(c.next==null);}''', 'true\n')
        execute('inherited-self-return', '''class A{int x=5;A* self(){return this;}}
            class B:A{int y=7;}
            void main(){B b;print(b.self().x);}''', '5\n')
        execute('receiver-once', '''#gvar count
            class A{int x=5;int get(){return x;}}class B:A{int y=7;}
            B* select(B* b){count+=1;return b;}
            void main(){count=0;B * b();print(select(b).get());select(b).x=8;print(count);print(b.x);}''', '5\n2\n8\n')
        execute('destructor-early-return', '''class A{int x;~A(){print("A");}}
            class B:A{int y;~B(){print("B");return;}}
            void main(){B b;}''', 'B\nA\n')
        execute('destructor-error-chain', '''class A{int x;~A(){print("A");int bad=2147483647+1;}}
            class B:A{int y;~B(){print("B");int bad=1/0;}}
            void main(){B b;}''', 'B\nA\n', error='Division by zero')
        execute('original-error-through-destructors', '''class A{int x;~A(){print("A");int bad=1/0;}}
            class B:A{int y;~B(){print("B");int bad=1/0;}}
            void main(){B b;int bad=2147483647+1;}''', 'B\nA\n', error='Integer overflow')
        execute('manual-destructor-error-chain', '''class A{int x;~A(){print("A");}}
            class B:A{int y;~B(){print("B");int bad=1/0;}}
            void main(){A* a=new B();delete a;}''', 'B\nA\n', error='Division by zero')
        execute('destructor-locals-before-parent', '''class Marker{int x;~Marker(){print("local");}}
            class A{int x;~A(){print("A");}}class B:A{int y;~B(){Marker m;print("B");}}
            void main(){B b;}''', 'B\nlocal\nA\n')
        execute('base-constructor-failure', '''class A{int x;A(){print("A");int bad=1/0;}~A(){print("~A");}}
            class B:A{int y;B(){print("B");}~B(){print("~B");}}
            void main(){B b;}''', 'A\n', error='Division by zero')
        execute('derived-constructor-failure', '''class A{int x;A(){print("A");}~A(){print("~A");}}
            class B:A{int y;B(){print("B");int bad=1/0;}~B(){print("~B");}}
            void main(){B b;}''', 'A\nB\n~A\n', error='Division by zero')
        execute('derived-initializer-failure', '''class A{int x;~A(){print("A");}}
            class B:A{int y=1/0;~B(){print("B");}}
            void main(){B* b=new B();}''', 'A\n', error='Division by zero')
        execute('intermediate-constructor-failure', '''class A{int x;~A(){print("A");}}
            class B:A{int y;B(){print("B");int bad=1/0;}~B(){print("~B");}}
            class C:B{int z;~C(){print("C");}}
            void main(){C c;}''', 'B\nA\n', error='Division by zero')
        execute('constructor-rollback-error-preserves-cause', '''class A{int x;~A(){print("A");int bad=1/0;}}
            class B:A{int y;B(){int bad=2147483647+1;}~B(){print("B");}}
            void main(){B b;}''', 'A\n', error='Integer overflow')
        execute('base-argument-temporary-cleanup-failure', '''class T{int x=7;~T(){print("T");int bad=1/0;}}
            T* make(){T * t();return t;}
            class A{int x;A(T* t){print(t.x);}~A(){print("A");}}
            class B:A{int y;B():A(make()){print("body");}~B(){print("B");}}
            void main(){B b;}''', '7\nbody\nT\nA\n', error='Division by zero')
        execute('constructor-early-return', '''class A{int x=3;~A(){print("A");}}
            class B:A{int y=7;B(){return;}~B(){print("B");}}
            void main(){B b;print(b.x+b.y);}''', '10\nB\nA\n')
        execute('null-inherited-member', '''class A{int x;}class B:A{int y;}
            void main(){B* b=null;print(b.x);}''', error=True)
        execute('null-inherited-method', '''class A{int x;int get(){return 3;}}class B:A{int y;}
            void main(){B* b=null;print(b.get());}''', error=True)
        execute('base-alias-use-after-delete', '''class A{int x;}class B:A{int y;}
            void main(){B* b=new B();A* a=b;delete a;print(b.y);}''', error=True)

        layout = execute('exact-prefix-layout', '''class A{int x;double y;}
            class B:A{string z;}class C:B{boolean q;}
            void main(){A a;B b;C c;}''')
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
        inspect(layout)
        assert allocations and {tuple(params) for params in allocations} == {(2,), (3,), (4,)}, allocations
        # Explicitly retain source inheritance metadata in the exported AST.
        ast = json.loads((work / 'exact-prefix-layout.ast.json').read_text(encoding='utf-8'))
        assert any(isinstance(value, dict) and value.get('base') == 'A'
                   for value in (ast.get('classes', {}).values()
                                 if isinstance(ast.get('classes'), dict) else ast.get('classes', []))), ast.get('classes')

        bad_sources = {
            'unknown-base': 'class C:Missing{int x;}void main(){}',
            'self-base': 'class C:C{int x;}void main(){}',
            'cyclic-bases': 'class A:B{int x;}class B:C{int y;}class C:A{int z;}void main(){}',
            'multiple-bases': 'class A{int x;}class B{int y;}class C:A,B{int z;}void main(){}',
            'private-base': 'class A{int x;}class B:private A{int y;}void main(){}',
            'protected-base': 'class A{int x;}class B:protected A{int y;}void main(){}',
            'virtual-base': 'class A{int x;}class B:virtual A{int y;}void main(){}',
            'no-default-base': 'class A{int x;A(int n){x=n;}}class B:A{int y;}void main(){B b;}',
            'wrong-base-argument': 'class A{int x;A(int n){x=n;}}class B:A{int y;B():A("x"){}}void main(){}',
            'wrong-base-arity': 'class A{int x;A(int n){x=n;}}class B:A{int y;B():A(){}}void main(){}',
            'initializer-nonbase': 'class A{int x;}class B:A{int y;B():B(){}}void main(){}',
            'initializer-without-base': 'class A{int x;A():A(){}}void main(){}',
            'implicit-downcast': 'class A{int x;}class B:A{int y;}void main(){A a;B b=a;}',
            'argument-downcast': 'class A{int x;}class B:A{int y;}void take(B b){}void main(){A a;take(a);}',
            'return-downcast': 'class A{int x;}class B:A{int y;}B bad(A a){return a;}void main(){}',
            'inherited-self-downcast': 'class A{int x;A* self(){return this;}}class B:A{int y;}void main(){B b;B* c=b.self();}',
            'unrelated-prefix': 'class A{int x;}class B{int x;int y;}void main(){B b;A a=b;}',
            'hidden-field-wrong-type': 'class A{int x;}class B:A{string x;}void main(){B b;b.x=7;}',
            'hidden-method-wrong-signature': 'class A{int x;int f(int n){return n;}}class B:A{int y;int f(){return 2;}}void main(){B b;b.f(3);}',
            'duplicate-derived-field': 'class A{int x;}class B:A{int y;int y;}void main(){}',
            'dynamic-derived-member': 'class A{int x;}class B:A{int y;}void main(){var b=new B();print(b.x);}',
        }
        for name, source in bad_sources.items():
            compile_case(source, 'invalid-' + name, valid=False)
            checks += 1

        execute('documented-example', (ROOT / 'compiler/examples/inheritance-regressions.azs').read_text(encoding='utf-8'), '0\n')

        (work / 'base.include.azs').write_text('''#ifndef BASE_INCLUDED
#define BASE_INCLUDED 1
#assume_hint INHERITANCE_BASE b001
class Base{
 int seed=baseSeed();
 extern Base(int seed):b0010002;
 extern int get():b0010003;
 extern ~Base():b0010004;
}
#fi
''', encoding='utf-8')
        (work / 'child.include.azs').write_text('''#ifndef CHILD_INCLUDED
#define CHILD_INCLUDED 1
#include "base.include.azs"
#assume_hint INHERITANCE_CHILD c001
class Child:Base{
 int extra=childSeed();
 extern Child(int seed):c0010002;
 extern int sum():c0010003;
 extern ~Child():c0010004;
}
#fi
''', encoding='utf-8')
        base, _ = compile_case('''#namespace_hint INHERITANCE_BASE
            #include "base.include.azs"
            #gvar seed
            void __script_onload(){seed=10;}
            int baseSeed():0005{return seed;}
            Base::Base(int seed):0002{this.seed=this.seed+seed;}
            int Base::get():0003{return seed;}
            Base::~Base():0004{print("base:"+seed);}
            int readBase(Base* b):0006{return b.get();}''', 'base')
        child, _ = compile_case('''#namespace_hint INHERITANCE_CHILD
            #include "child.include.azs"
            #gvar seed
            void __script_onload(){seed=100;}
            int childSeed():0005{return seed;}
            Child::Child(int seed):0002 : Base(seed+1){extra=extra+seed;if(seed<0){int bad=1/0;}}
            int Child::sum():0003{return get()+extra;}
            Child::~Child():0004{print("child:"+extra);if(extra==199){int bad=1/0;}}
            Base* produce():0006{Child * c(2);return c;}
            Base* manual():0007{return new Child(3);}''', 'child')
        client, _ = compile_case('''#include "child.include.azs"
            #gvar seed
            extern Base* produce():c0010006;
            extern int readBase(Base* b):b0010006;
            extern Base* manual():c0010007;
            void main(){seed=1000;{Child * c(4);Base* b=c;print(b.get());print(c.sum());print(readBase(c));}
            {Base* a=produce();print(a.get());}Base* m=manual();print(m.get());delete m;print(seed);}''', 'client')
        expected = '15\n119\n15\nchild:104\nbase:15\n13\nchild:102\nbase:13\n14\nchild:103\nbase:14\n1000\n'
        run_case('cross-hint-base-first', client, expected, libraries=(base, child))
        run_case('cross-hint-child-first', client, expected, libraries=(child, base))

        failed_construction, _ = compile_case('''#include "child.include.azs"
            void main(){Child c(-2);}''', 'cross-hint-failed-construction')
        run_case('cross-hint-constructor-rollback', failed_construction, 'base:9\n',
                 error='Division by zero', libraries=(child, base))
        failed_destructor, _ = compile_case('''#include "child.include.azs"
            void main(){Base* b=new Child(99);delete b;}''', 'cross-hint-failed-destructor')
        run_case('cross-hint-destructor-finally', failed_destructor, 'child:199\nbase:110\n',
                 error='Division by zero', libraries=(child, base))

        if args.host:
            heap_program, _ = compile_case('''#namespace 5151
                class Base{int x;~Base(){}}
                class BadConstructor:Base{int y;BadConstructor(){int bad=1/0;}}
                class BadDestructor:Base{int y;~BadDestructor(){int bad=1/0;}}
                class ConstructorLoop:Base{int y;ConstructorLoop(){while(true){}}}
                class DestructorLoop:Base{int y;~DestructorLoop(){while(true){}}}
                class Temporary{int x;~Temporary(){int bad=1/0;}}
                Temporary* make(){Temporary * t();return t;}
                class TemporaryBase{int x;TemporaryBase(Temporary* t){}~TemporaryBase(){}}
                class TemporaryChild:TemporaryBase{int y;TemporaryChild():TemporaryBase(make()){}}
                void constructorError():0002{BadConstructor * c();}
                void destructorError():0003{BadDestructor * c();}
                void manualDestructorError():0004{Base* b=new BadDestructor();delete b;}
                void constructorBudget():0005{ConstructorLoop * c();}
                void destructorBudget():0006{Base * other();DestructorLoop * c();}
                void bodyBudget():0007{BadDestructor * c();while(true){}}
                void baseArgumentTemporaryError():0008{TemporaryChild * c();}
                void good():0009{Base * b();}''', 'heap-reclamation')
            commands = [str(args.host), str(heap_program), 'flush', 'heap-empty', 'step-limit', '500']
            expected_heap = ['empty']
            # Repeat against one live runtime: shutdown must not hide a leaked allocation.
            for _ in range(3):
                for local in range(2, 9):
                    commands += ['reject-call', hex(0x51510000 + local), '0', 'heap-empty']
                    expected_heap += ['rejected', 'empty']
                    checks += 1
            commands += ['call', '0x51510009', '0', 'heap-empty']
            expected_heap += ['null', 'empty']
            result = subprocess.run(commands, cwd=ROOT, capture_output=True, text=True, timeout=30)
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            assert result.stdout.splitlines() == expected_heap, (result.stdout, expected_heap)

        if args.library:
            assert args.bridge, '--library requires --bridge'
            binary, _ = compile_case('''#include "child.include.azs"
                Base* create(){return new Child(6);}
                Base* createAuto(){Child * c(7);return c;}
                int read(Base* b){return b.get();}
                void release(Base* b){delete b;}''', 'jni-client')
            names = json.loads((work / 'jni-client.ast.json').read_text(encoding='utf-8'))['abstract']
            java = work / 'InheritanceSourceBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class InheritanceSourceBridge {
 public static void main(String[] args) throws Exception {
  try {
   AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.insertScript(new File(args[1]));
   AbdInvoker.insertScript(new File(args[2]));
   AbdInvoker.flush();
   int create=Integer.parseUnsignedInt(args[3],16), read=Integer.parseUnsignedInt(args[4],16);
   int release=Integer.parseUnsignedInt(args[5],16), auto=Integer.parseUnsignedInt(args[6],16);
   Object p=AbdInvoker.invoke(create), a=AbdInvoker.invoke(auto);
   if(!Integer.valueOf(17).equals(AbdInvoker.invoke(read,p)))throw new AssertionError("base read");
   if(!Integer.valueOf(18).equals(AbdInvoker.invoke(read,a)))throw new AssertionError("auto base read");
   File snapshot=new File(args[7]);
   if(!AbdInvoker.saveStatus(snapshot))throw new AssertionError("save");
   AbdInvoker.invoke(release,p);
   AbdInvoker.loadStatus(snapshot);
   if(!Integer.valueOf(17).equals(AbdInvoker.invoke(read,p)))throw new AssertionError("restored read");
   AbdInvoker.invoke(release,p);
   System.out.println("INHERITANCE_JNI_OK");
  } finally { AbdInvoker.close(); }
 }
}
''', encoding='utf-8')
            subprocess.run(['javac', '--release', '17', '-cp', str(args.bridge), '-d', str(work), str(java)],
                           cwd=ROOT, check=True, timeout=30)
            result = subprocess.run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                                     '-cp', os.pathsep.join((str(work), str(args.bridge))), 'InheritanceSourceBridge',
                                     str(binary), str(child), str(base), names['create'], names['read'],
                                     names['release'], names['createAuto'], str(work / 'snapshot.abd')],
                                    cwd=ROOT, capture_output=True, text=True, timeout=30)
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            # Java and native stdout use separate buffers; validate their output independently.
            assert result.stdout.count('INHERITANCE_JNI_OK\n') == 1, result.stdout
            assert result.stdout.replace('INHERITANCE_JNI_OK\n', '') == 'child:106\nbase:17\nchild:106\nbase:17\nchild:107\nbase:18\n', result.stdout
            checks += 1
    print(f'Inheritance end-to-end checks passed: {checks} (AST/ABD roundtrip, exact prefix layouts, cleanup and independent libraries).')


if __name__ == '__main__':
    main()
