#!/usr/bin/env python3
"""Virtual dispatch, phase/lifetime rules, source/AST/ABD identity and real JNI snapshots."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = 0x0FFF0000
BASE = '''class Base{int field=7;virtual int value(){return field;}int through(){return value();}Base* self(){return this;}}
class Child:Base{override int value(){return Base::value()+100;}}
'''


def walk(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from walk(item)
    elif isinstance(value, list):
        for item in value:
            yield from walk(item)


def opcodes(value):
    return {node['c'] for node in walk(value) if node.get('t') == 0 and type(node.get('c')) is int}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--only', help='Run cases whose names contain this text')
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='virtual-methods-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def selected(name):
            return args.only is None or args.only in name

        def command(argv, timeout=30):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)

        def compile_case(name, source, valid=True):
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast, inspection = (work / (name + suffix) for suffix in ('.abd', '.ast.json', '.exec.json'))
            result = command(compiler + ['compile', path, '-o', abd, '--ast', ast, '--exec-json', inspection, '-I', ROOT / 'compiler/stdlib'])
            if not valid:
                assert result.returncode == 1 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'internal compiler error' not in result.stderr and 'Exception in thread' not in result.stderr, (name, result.stderr)
                assert path.name in result.stderr, (name, 'diagnostic must retain source location', result.stderr)
                return None
            assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            rebuilt = work / (name + '.rebuilt.abd')
            result = command(compiler + ['compile-json', ast, '-o', rebuilt])
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, 'source/AST/ABD identity')
            tree = json.loads(inspection.read_text(encoding='utf-8'))
            assert tree['exec-version'] == 10 and 'classes' not in tree, name
            return abd, json.loads(ast.read_text(encoding='utf-8')), tree

        def run_case(name, artifact, output='', error=None, libraries=()):
            nonlocal checks
            if not args.compile_only:
                argv = [args.runner, artifact[0]]
                for library in libraries:
                    argv += ['--insert', library[0]]
                result = command(argv)
                assert result.stdout == output, (name, result.stdout, output, result.stderr)
                if error is None:
                    assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
                else:
                    assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                    if isinstance(error, str):
                        assert error.lower() in result.stderr.lower(), (name, error, result.stderr)
            checks += 1

        def execute(name, source, output='', error=None, clean=False):
            nonlocal checks
            if not selected(name):
                return None
            artifact = compile_case(name, source)
            run_case(name, artifact, output, error)
            if clean and args.host and not args.compile_only:
                operation = 'reject-call' if error else 'call'
                result = command([args.host, artifact[0], 'flush', operation, MAIN, '0', 'heap-empty'])
                expected = output + ('rejected\n' if error else 'null\n') + 'empty\n'
                assert result.returncode == 0 and result.stdout == expected and not result.stderr, (name, result.stdout, expected, result.stderr)
                checks += 1
            return artifact

        g1 = execute('g1-generic-pointer-bound', '''class Base{int field;virtual int value(){return 1;}}
            class Child:Base{override int value(){return 100;}}
            <T extends Base*> int invoke(T p){return p.value();}
            void main(){Base* p=new Child();print(invoke(p));delete p;}''', '100\n', clean=True)
        if g1:
            assert {52, 53} <= opcodes(g1[2]), opcodes(g1[2])
            assert any('virtual-methods' in node for node in walk(g1[1])), g1[1]
            assert any('override-methods' in node for node in walk(g1[1])), g1[1]
            # Dispatch is a compiler decision; readable calls remain ordinary AST calls.
            assert not any(node.get('call') in ('call_virtual', 'set_methods') for node in walk(g1[1]))
            checks += 1
        execute('pointer-this-and-base-qualified', BASE + '''void main(){Child* c=new Child();Base* b=c;
            print(c.value());print(b.value());print(b.through());delete b;}''', '107\n107\n107\n', clean=True)
        execute('nonvirtual-hiding-preserved', '''class Base{int field;int value(){return 1;}int through(){return value();}}
            class Child:Base{int value(){return 2;}}
            void main(){Child* c=new Child();Base* b=c;print(c.value());print(b.value());print(b.through());delete b;}''', '2\n1\n1\n', clean=True)
        execute('virtual-override-final-combination', '''class Base{int field;virtual int value(){return 1;}}
            class Child:Base{virtual override final int value(){return 2;}}class Grand:Child{}
            void main(){Base* p=new Grand();print(p.value());delete p;}''', '2\n', clean=True)
        execute('three-constructor-destructor-phases', '''class Base{int field;virtual int kind(){return 1;}
            Base(){print(kind());}~Base(){print(kind());}}
            class Child:Base{override int kind(){return 2;}Child(){print(kind());}~Child(){print(kind());}}
            class Grand:Child{override int kind(){return 3;}Grand(){print(kind());}~Grand(){print(kind());}}
            void main(){Grand* p();Base* b=p;print(b.kind());}''', '1\n2\n3\n3\n3\n2\n1\n', clean=True)
        execute('builtin-name-member-dispatch', '''class Base{int field;virtual int length(){return 1;}int through(){return length();}}
            class Child:Base{override int length(){return 9;}}
            void main(){Base* p=new Child();print(p.length());print(p.through());delete p;}''', '9\n9\n', clean=True)
        execute('receiver-arguments-once-in-order', '''#gvar order
            class Base{int field;virtual int apply(int n){return n;}}
            class Child:Base{override int apply(int n){order=order*10+3;return n;}}
            Base* receiver(Base* p){order=order*10+1;return p;}int next(){order=order*10+2;return 7;}
            void main(){order=0;Base* p=new Child();print(receiver(p).apply(next()));print(order);delete p;}''', '7\n123\n', clean=True)
        execute('covariant-pointer-return', '''class Base{int field;virtual Base* copy(){return new Base();}virtual int kind(){return 1;}}
            class Child:Base{override Child* copy(){return new Child();}override int kind(){return 2;}}
            void main(){Base* p=new Child();Base* q=p.copy();print(q.kind());delete q;delete p;}''', '2\n', clean=True)
        execute('generic-class-captured-context', '''class Base<T>{T field;virtual T value(){return field;}T through(){return value();}}
            class Child<T>:Base<T>{override T value(){return field;}}
            void main(){Base<string>* s=new Child<string>();s.field="context";print(s.value());print(s.through());delete s;
            Base<int>* n=new Child<int>();n.field=42;print(n.value());delete n;}''', 'context\ncontext\n42\n', clean=True)
        static = execute('value-and-final-static-binding', '''class Base{int field;virtual int value(){return 1;}}
            class Child:Base{override final int value(){return 2;}}
            int plain(Base value){return value.value();}int closed(Child* p){return p.value();}
            void main(){Base value;print(plain(value));Child* p=new Child();print(closed(p));delete p;}''', '1\n2\n', clean=True)
        if static:
            for name in ('plain', 'closed'):
                ident = int(static[1]['abstract'][name], 16)
                body = next(fn['script'] for fn in static[2]['f'] if fn['id'] == ident)
                assert 53 not in opcodes(body), (name, body)
            checks += 1
        execute('value-bound-generic-static', '''class Base{int field;virtual int value(){return 1;}}
            class Child:Base{override int value(){return 2;}}
            <T extends Base> int invoke(T p){return p.value();}
            void main(){Child value;print(invoke(value));}''', '1\n', clean=True)
        execute('literal-copy-and-assignment-tables', BASE + '''int indirect(Base* p){return p.value();}
            void main(){Child a;a.field=8;Child b=a;print(indirect(b.self()));a.field=9;b=a;
            print(indirect(b.self()));print(indirect(a.self()));}''', '108\n109\n109\n', clean=True)
        execute('buffer-view-tables', BASE + '''int indirect(Base* p){return p.value();}
            void main(){buffer<Child> values;values.reserve(3);values.resize(2);
            print(indirect(values.get(0).self()));buffer<Child> copy=values;values.resize(0);
            print(indirect(copy.get(1).self()));copy.reserve(5);print(indirect(copy.get(0).self()));}''', '107\n107\n107\n', clean=True)
        execute('virtual-owning-value-return', '''class Item{int x=8;~Item(){print("drop");}}
            class Base{int field;virtual Item result(){Item x;return x;}}
            class Child:Base{override Item result(){Item x;x.x=9;return x;}}
            void main(){Base* p=new Child();{Item value=p.result();print(value.x);}delete p;}''', '9\ndrop\n', clean=True)
        execute('virtual-owned-pointer-return', '''class Item{int x=8;~Item(){print("drop");}}
            class Base{int field;virtual Item* result(){Item* p();return p;}}
            class Child:Base{override Item* result(){Item* p();p.x=9;return p;}}
            void main(){Base* p=new Child();{Item* value=p.result();print(value.x);}delete p;}''', '9\ndrop\n', clean=True)
        execute('virtual-body-error-cleanup', '''class Base{int field;virtual int fail(){return 1;}~Base(){print("base");}}
            class Child:Base{override int fail(){int zero=0;return 1/zero;}~Child(){print("child");}}
            void main(){Child* p();print(p.fail());}''', 'child\nbase\n', error='Division by zero', clean=True)
        execute('virtual-cleanup-keeps-first-error', '''class Base{int field;virtual int fail(){return 1;}~Base(){print("base");int zero=0;int bad=1/zero;}}
            class Child:Base{override int fail(){int max=2147483647;return max+1;}~Child(){print("child");}}
            void main(){Child* p();print(p.fail());}''', 'child\nbase\n', error='Integer overflow', clean=True)
        execute('virtual-derived-constructor-rollback', '''class Base{int field;virtual int kind(){return 1;}~Base(){print(kind());}}
            class Child:Base{override int kind(){return 2;}Child(){print(kind());int zero=0;int bad=1/zero;}}
            void main(){Child* p();}''', '2\n1\n', error='Division by zero', clean=True)
        execute('virtual-constructor-owned-member-rollback', '''class Base{int field;virtual int kind(){return 1;}~Base(){print(kind());}}
            class Member{Base* owner;int tag;~Member(){print(tag);print(owner.kind());}}
            class Child:Base{Member first;Member second;override int kind(){return 2;}Child(){
            first.owner=this;first.tag=7;second.owner=this;second.tag=8;print(kind());int zero=0;int bad=1/zero;}}
            void main(){Child* p();}''', '2\n8\n2\n7\n2\n1\n', error='Division by zero', clean=True)
        members = '''class Base{int field;virtual int kind(){return 1;}~Base(){print(kind());}}
            class Member{Base* owner;int tag;~Member(){print(tag);print(owner.kind());
            if(tag==8){int max=2147483647;int bad=max+1;}if(tag==7){int zero=0;int bad=1/zero;}}}
            class Child:Base{Member first;Member second;override int kind(){return 2;}Child(){
            first.owner=this;first.tag=7;second.owner=this;second.tag=8;}~Child(){BODY}}
            void main(){Child* p();}'''
        execute('virtual-throwing-member-keeps-cleaning', members.replace('BODY', 'print("child");'),
                'child\n8\n2\n7\n2\n1\n', error='Integer overflow', clean=True)
        execute('virtual-destructor-body-error-retained', members.replace('BODY', 'print("child");int zero=0;int bad=1/zero;'),
                'child\n8\n2\n7\n2\n1\n', error='Division by zero', clean=True)
        execute('virtual-base-argument-temporary-cleanup-failure', '''class Temporary{int x=7;~Temporary(){print("T");int zero=0;int bad=1/zero;}}
            Temporary* make(){Temporary* p();return p;}
            class Base{int field;virtual int kind(){return 1;}Base(Temporary* p){print(p.x);}~Base(){print(kind());}}
            class Member{Base* owner;int tag;~Member(){print(tag);print(owner.kind());}}
            class Child:Base{Member first;Member second;override int kind(){return 2;}Child():Base(make()){
            first.owner=this;first.tag=7;second.owner=this;second.tag=8;print("body");}}
            void main(){Child* p();}''', '7\nbody\nT\n8\n2\n7\n2\n1\n', error='Division by zero', clean=True)
        execute('virtual-literal-base-argument-cleanup-failure', '''class Argument{int n=7;~Argument(){print("T");int zero=0;int bad=1/zero;}}
            Argument makeValue(){Argument value;return value;}
            class Base{int field;virtual int kind(){return 1;}Base(Argument (*) p){print(p.n);}~Base(){print(kind());}}
            class Child:Base{override int kind(){return 2;}Child():Base(makeValue()){print("wrong");}}
            void main(){Child* p();}''', '7\nT\n1\n', error='Division by zero', clean=True)
        execute('virtual-empty-base-phase-clears-derived-table', '''class Base{int field;~Base(){print(probe());}}
            class Child:Base{virtual int kind(){return 2;}}Child* saved=null;
            int probe(){return saved.kind();}void main(){saved=new Child();delete saved;}''', error='Object has no virtual method table', clean=True)
        execute('virtual-null', BASE + 'void main(){Base* p=null;print(p.value());}', error='Virtual call requires a non-null object', clean=True)
        execute('virtual-expired', BASE + 'void main(){Base* p=new Child();delete p;print(p.value());}', error='Virtual call requires a live object start', clean=True)

        invalid = {
            'missing-override': 'class A{int x;virtual int f(){return 1;}}class B:A{int f(){return 2;}}',
            'override-without-base': 'class A{int x;override int f(){return 1;}}',
            'override-nonvirtual': 'class A{int x;int f(){return 1;}}class B:A{override int f(){return 2;}}',
            'final-overridden': 'class A{int x;virtual final int f(){return 1;}}class B:A{override int f(){return 2;}}',
            'final-needs-virtual': 'class A{int x;final int f(){return 1;}}',
            'final-cannot-hide-override': 'class A{int x;virtual int f(){return 1;}}class B:A{final int f(){return 2;}}',
            'changed-parameter': 'class A{int x;virtual int f(int n){return n;}}class B:A{override int f(string n){return 2;}}',
            'changed-numeric-return': 'class A{int x;virtual double f(){return 1.0;}}class B:A{override int f(){return 2;}}',
            'unrelated-pointer-return': 'class X{int x;}class Y{int y;}class A{int x;virtual X* f(){return null;}}class B:A{override Y* f(){return null;}}',
            'abstract': 'class A{int x;virtual int f();}',
            'constructor': 'class A{int x;virtual A(){}}',
            'destructor': 'class A{int x;virtual ~A(){}}',
            'operator': 'class A{int x;virtual int operator()(int n){return n;}}',
            'generic-method': 'class A{int x;virtual <T> T f(T value){return value;}}',
            'top-level': 'virtual int f(){return 1;}',
            'out-of-class': 'class A{int x;extern virtual int f():12340002;}virtual int A::f():0002{return 1;}',
            'duplicate-modifier': 'class A{int x;virtual virtual int f(){return 1;}}',
        }
        for label, source in invalid.items():
            name = 'invalid-' + label
            if selected(name):
                compile_case(name, source + 'void main(){}', valid=False)
                checks += 1

        if selected('ordinary-ast-unmodified'):
            ordinary = compile_case('ordinary-ast-unmodified', 'class Plain{int x=7;int get(){return x;}}void main(){Plain p;print(p.get());}')
            assert not any(any(key in node for key in ('virtual-methods', 'override-methods', 'final-methods')) for node in walk(ordinary[1]))
            run_case('ordinary-ast-unmodified', ordinary, '7\n')
        if selected('ast-virtual-modifier-validation'):
            artifact = compile_case('ast-virtual-base', BASE + 'void main(){}')
            for key in ('virtual-methods', 'override-methods'):
                tree = json.loads(json.dumps(artifact[1]))
                target = next(node for node in walk(tree) if key in node)
                target[key] = [42]
                path = work / ('invalid-' + key + '.json')
                path.write_text(json.dumps(tree), encoding='utf-8')
                result = command(compiler + ['compile-json', path, '-o', work / 'invalid-modifier.abd'])
                assert result.returncode == 1 and 'Error:' in result.stderr and 'Exception in thread' not in result.stderr, (key, result.stderr)
                checks += 1

        if selected('cross-hint-virtual'):
            header = '''#ifndef VIRTUAL_BASE_INCLUDED
                #define VIRTUAL_BASE_INCLUDED 1
                #assume_hint VIRTUAL_BASE 2345
                class Base<T>{T field;extern Base():23450002;extern virtual T value():23450003;
                    extern T through():23450004;extern ~Base():23450005;}
                #fi
            '''
            (work / 'virtual.include.azs').write_text(header, encoding='utf-8')
            library = compile_case('virtual-library', '''#namespace_hint VIRTUAL_BASE
                #include "virtual.include.azs"
                Base<T>::Base():0002{}T Base<T>::value():0003{return field;}
                T Base<T>::through():0004{return value();}Base<T>::~Base():0005{print("base");}
            ''')
            client = compile_case('virtual-client', '''#include "virtual.include.azs"
                class Child:Base<int>{override int value(){return field+100;}}
                int main(){Base<int>* p=new Child();p.field=7;print(p.through());delete p;return 42;}
            ''')
            run_case('cross-hint-virtual', client, '107\nbase\n42\n', libraries=[library])
            if args.host and not args.compile_only:
                for initial, inserted in ((client, library), (library, client)):
                    result = command([args.host, initial[0], 'insert', inserted[0], 'flush', 'call', MAIN, '0', 'heap-empty'])
                    assert result.returncode == 0 and result.stdout == '107\nbase\n42\nempty\n' and not result.stderr, (result.stdout, result.stderr)
                    checks += 1

        if selected('vector-virtual'):
            library = compile_case('virtual-containers', (ROOT / 'compiler/stdlib/containers.azs').read_text(encoding='utf-8'))
            client = compile_case('virtual-vector', '#include "containers.include.azs"\n' + BASE + '''int indirect(Base* p){return p.value();}
                void main(){Vector<Child> values;values.reserve(3);values.resize(2);print(indirect(values.at(0).self()));
                Vector<Child> copy=values;values.clear();print(indirect(copy.at(1).self()));copy.reserve(5);
                print(indirect(copy.at(0).self()));}''')
            run_case('vector-virtual', client, '107\n107\n107\n', libraries=[library])
            if args.host and not args.compile_only:
                result = command([args.host, client[0], 'insert', library[0], 'flush', 'call', MAIN, '0', 'heap-empty'])
                assert result.returncode == 0 and result.stdout == '107\n107\n107\nnull\nempty\n' and not result.stderr, (result.stdout, result.stderr)
                checks += 1

        if args.library and selected('jni-virtual-snapshot'):
            assert args.bridge, '--library requires --bridge'
            artifact = compile_case('jni-virtual-snapshot', '#namespace 1234\n' + BASE + '''
                class Generic<T>{T field;virtual T read(){return field;}}
                class GenericChild<T>:Generic<T>{override T read(){return field;}}
                Base* saved=null;Generic<string>* saved_text=null;
                void __script_onload(){saved=new Child();saved_text=new GenericChild<string>();saved_text.field="context";}
                int heap_value():0042{return saved.value();}
                string generic_value():0043{return saved_text.read();}
                void change():0045{saved.field=19;saved_text.field="changed";}
                void release():0046{delete saved;delete saved_text;saved=null;saved_text=null;}
                void main(){}
            ''')
            if not args.compile_only:
                source = work / 'VirtualSnapshot.java'
                source.write_text('''import azertia.AbdInvoker;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.io.File;
import java.nio.file.Files;
public class VirtualSnapshot {
 static Object call(int n){return AbdInvoker.invoke(0x12340000+n);}
 static void equal(Object value,Object expected){if(!expected.equals(value))throw new AssertionError(value+" != "+expected);}
 static void state(int heap,String text){equal(call(0x42),heap);equal(call(0x43),text);}
 public static void main(String[] args)throws Exception {
  File saved=new File(args[1]),bad=new File(args[1]+".bad");
  try {
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();state(107,"context");
   if(!AbdInvoker.saveStatus(saved))throw new AssertionError("save");
   byte[] original=Files.readAllBytes(saved.toPath());AcsObject root=new AcsObject(AbdValue.fromAbd(original));
   equal(root.getAsInt("snapshot version"),10);
   AcsArray objects=root.getAsAcsArray("objects");
   if(objects.acsa.isEmpty())throw new AssertionError("heap object missing");
   AcsObject object=(AcsObject)objects.acsa.get(0);
   if(object.getAsAcsArray("methods").acsa.isEmpty())throw new AssertionError("virtual table missing");
   call(0x45);state(119,"changed");
   object.mmp.remove("methods");Files.write(bad.toPath(),root.toValue().toAbdFormat());
   try {AbdInvoker.loadStatus(bad);throw new AssertionError("missing methods accepted");}catch(IllegalArgumentException expected){}
   state(119,"changed");
   AbdInvoker.loadStatus(saved);state(107,"context");
   call(0x45);AbdInvoker.loadStatus(saved);state(107,"context");
   call(0x46);System.out.println("Virtual JNI snapshot passed");
  } finally {AbdInvoker.close();}
 }
}''', encoding='utf-8')
                classpath = os.pathsep.join((str(args.bridge.resolve()), args.classpath))
                result = command(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', work, source])
                assert result.returncode == 0, result.stderr
                result = command(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                                  '-cp', os.pathsep.join((str(work), classpath)), 'VirtualSnapshot', artifact[0], work / 'virtual.snapshot.abd'], timeout=60)
                assert result.returncode == 0 and result.stdout == 'Virtual JNI snapshot passed\n' and not result.stderr, (result.stdout, result.stderr)
                assert 'WARNING in native method' not in result.stdout + result.stderr
                checks += 1

        print(f'Virtual methods: {checks} source/AST, dispatch, cleanup, hint and host checks passed')


if __name__ == '__main__':
    main()
