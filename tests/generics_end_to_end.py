#!/usr/bin/env python3
"""Shared generic bodies, hidden type operations, reflection and lifetime regressions."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = 0x0FFF0000
REFLECT_TARGET = 0x12340042
IDENTITY = '<T> T identity(T value){return value;}\n'
BOX = '''class Box<T>{T value;T get(){return value;}void set(T next){value=next;}}
'''
POINT = '''class Point{int x;Point(int n){x=n;}int get(){return x;}~Point(){print("~"+x);}}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--only', help='Run cases whose names contain this text')
    parser.add_argument('--compile-only', action='store_true', help='Check compiler/AST cases without executing ABD')
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='generics-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def selected(name):
            return args.only is None or args.only in name

        def command(argv, timeout=30):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)

        def compile_case(name, source, valid=True):
            source_path = work / (name + '.azs')
            source_path.write_text(source, encoding='utf-8')
            abd, ast, executable = [work / (name + extension) for extension in ('.abd', '.ast.json', '.exec.json')]
            result = command(compiler + ['compile', source_path, '-o', abd, '--ast', ast, '--exec-json', executable])
            if not valid:
                assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'Exception in thread' not in result.stderr, (name, result.stderr)
                return None
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            roundtrip = work / (name + '.roundtrip.abd')
            rebuilt = command(compiler + ['compile-json', ast, '-o', roundtrip])
            assert rebuilt.returncode == 0, (name, 'AST rebuild', rebuilt.stdout, rebuilt.stderr)
            assert abd.read_bytes() == roundtrip.read_bytes(), (name, 'AST roundtrip differs')
            tree = json.loads(executable.read_text(encoding='utf-8'))
            assert 'classes' not in tree and 'class-types' not in tree, (name, tree.keys())
            assert all(type(t) is int for fn in tree['f'] for t in fn['param-types']), name
            return abd, json.loads(ast.read_text(encoding='utf-8')), tree

        def run_case(name, artifact, output='', error=None, libraries=()):
            nonlocal checks
            if not args.compile_only:
                argv = [args.runner, artifact[0]]
                for library in libraries:
                    argv += ['--insert', library[0]]
                result = command(argv)
                assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
                if error is None:
                    assert result.returncode == 0 and not result.stderr, (name, result.stderr)
                else:
                    assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                    if isinstance(error, str):
                        assert error.lower() in result.stderr.lower(), (name, error, result.stderr)
            checks += 1

        def execute(name, source, output='', error=None):
            if not selected(name):
                return None
            artifact = compile_case(name, source)
            run_case(name, artifact, output, error)
            return artifact

        scalar = execute('scalar-inference', IDENTITY + '''void main(){
            print(identity(7));print(identity<string>("hello"));print(identity(1.5f));
            print(identity<double>(2.5));print(identity(true));address p=identity(alloc(1));
            print(mem_free(p));}''', '7\nhello\n1.5\n2.5\ntrue\ntrue\n')
        if scalar:
            identity_id = int(scalar[1]['abstract']['identity'], 16)
            bodies = [fn for fn in scalar[2]['f'] if fn['id'] == identity_id]
            assert len(bodies) == 1 and bodies[0]['hidden-count'] == 1, bodies
            assert bodies[0]['param-count'] == 1, bodies
            assert len(scalar[2]['f']) == 2, 'A scalar-only generic function must not create specialized bodies'
            checks += 1
        execute('box-defaults', BOX + '''void main(){Box<int> i;Box<string> s;Box<bool> b;Box<address> a;
            print(i.value);print(s.value=="");print(b.value);print(a.value==null);
            i.set(9);s.set("ok");print(i.get());print(s.get());}''', '0\ntrue\nfalse\ntrue\n9\nok\n')
        execute('nested-box-default', BOX + '''void main(){Box<Box<int>> b;print(b.value.value);
            b.value.set(8);Box<Box<int>> copy=b;copy.value.value=3;print(b.value.value);print(copy.value.value);}''', '0\n8\n3\n')
        execute('two-phase-scalar-default', '''class Defaults<T>{T first=last;T last;}
            void main(){Defaults<int> a;Defaults<string> b;print(a.first);print(b.first=="");}''', '0\ntrue\n')
        execute('two-phase-value-default', '''#gvar counter
            int next(){counter=counter+1;return counter;}int middle(){print("middle");return 0;}
            class Tag{int id=next();Tag(){print("+"+id);}~Tag(){print("-"+id);}}
            class Pair<T>{T first;int marker=middle();T second;}
            void main(){counter=0;Pair<Tag> p;print("body");}''', '+1\nmiddle\n+2\nbody\n-2\n-1\n')
        execute('value-inference-copy', IDENTITY + POINT + '''void main(){Point a(7);Point b=identity(a);
            b.x=9;print(a.x);print(b.x);}''', '7\n9\n~9\n~7\n')
        execute('pointer-inference-and-null', IDENTITY + POINT + '''void main(){Point* p=new Point(4);
            Point* q=identity(p);print(q.x);Point* n=identity<Point*>(null);print(n==null);delete q;}''', '4\ntrue\n~4\n')
        execute('generic-flexible-parameter', BOX + '''<T> T read(Box<T>(*) box){return box.value;}
            void main(){Box<int> b;b.value=5;Box<string>* p=new Box<string>();p.value="yes";
            print(read(b));print(read(p));delete p;}''', '5\nyes\n')
        execute('method-own-parameters', '''class Pair<T>{T value;<U> U echo(U input){return input;}
            T same(T input){return input;}}
            void main(){Pair<int> p;print(p.echo("method"));print(p.echo<bool>(true));print(p.same(6));}''', 'method\ntrue\n6\n')
        execute('recursive-generic-function', '''<T> T recurse(T value,int count){if(count==0)return value;
            return recurse<T>(value,count-1);}void main(){print(recurse("end",4));}''', 'end\n')
        execute('generic-inheritance-context', '''class Base<T>{T value;T read(){return value;}~Base(){print("base");}}
            class Child<A,B>:Base<B>{A own;~Child(){print("child");}}
            void main(){Child<string,int> c;c.own="own";c.value=11;print(c.read());print(c.own);
            Base<int>* p=new Child<string,int>();p.value=12;print(p.read());delete p;}''',
                '11\nown\n12\nchild\nbase\nchild\nbase\n')
        execute('bounded-pointer-members', '''class Base{int value=3;int get(){return value;}}
            class Child:Base{int extra=4;}
            <T extends Base*> int read(T value){return value.get();}
            void main(){Child* p();print(read(p));}''', '3\n')
        execute('bounded-value-members', '''class Base{int value=3;int get(){return value;}}
            class Child:Base{int extra=4;}
            <T extends Base> int read(T value){return value.get();}
            void main(){Child c;print(read(c));}''', '3\n')
        execute('no-default-pure-transfer', IDENTITY + POINT + '''void main(){Point p(5);
            Point q=identity<Point>(p);print(q.x);}''', '5\n~5\n~5\n')
        execute('generic-constructor-failure', '''class Tag{int x;Tag(){print("tag");}~Tag(){print("drop");}}
            class Bad<T>{T item;Bad(){int zero=0;int fail=1/zero;}~Bad(){print("wrong");}}
            void main(){Bad<Tag> value;}''', 'tag\ndrop\n', error='Division by zero')
        execute('generic-destructor-failure', '''class Tag{int x;~Tag(){print("tag");}}
            class Bad<T>{T item;~Bad(){print("bad");int zero=0;int fail=1/zero;}}
            void main(){Tag other;Bad<Tag> value;}''', 'bad\ntag\ntag\n', error='Division by zero')

        reflection = '#namespace 1234\n' + IDENTITY + POINT + '''
            Point* make():0042 {Point* p(9);return p;}
            <T> T invoke(int id){return reflect_invoke_function<T>(id);}
        '''
        execute('reflection-pointer-return', reflection + f'''void main(){{Point* p=invoke<Point*>({REFLECT_TARGET});
            print(p.x);print("after");}}''', '9\nafter\n~9\n')
        execute('reflection-address-return', reflection + f'''void main(){{address p=invoke<address>({REFLECT_TARGET});
            print(p!=null);}}''', '~9\ntrue\n')
        execute('reflection-value-return', '#namespace 1234\n' + POINT + f'''
            Point make():0042{{Point p(8);return p;}}
            <T> T invoke(int id){{return reflect_invoke_function<T>(id);}}
            void main(){{Point p=invoke<Point>({REFLECT_TARGET});print(p.x);}}''', '8\n~8\n')
        execute('reflection-left-to-right', f'''#namespace 1234
            #gvar order
            int target(){{order=order*10+1;return {REFLECT_TARGET};}}
            int arg(int n){{order=order*10+n;return n;}}
            int sum(int a,int b):0042{{return a+b;}}
            void main(){{order=0;print(reflect_invoke_function<int>(target(),arg(2),arg(3)));print(order);}}''', '5\n123\n')
        execute('reflection-void', f'''#namespace 1234
            void ping(int n):0042{{print(n);}}
            void main(){{reflect_invoke_function<void>({REFLECT_TARGET},4);}}''', '4\n')
        execute('reflection-high-id', '''#namespace ffff
            int high():ffff{return 6;}void main(){print(reflect_invoke_function<int>(-1));}''', '6\n')
        execute('reflection-return-mismatch', f'''#namespace 1234
            int value():0042{{return 7;}}void main(){{string bad=reflect_invoke_function<string>({REFLECT_TARGET});}}''', error=True)
        execute('reflection-argument-mismatch', f'''#namespace 1234
            int value(int n):0042{{return n;}}void main(){{reflect_invoke_function<int>({REFLECT_TARGET},"bad");}}''', error=True)
        execute('reflection-missing-id', 'void main(){reflect_invoke_function<void>(123456789);}', error=True)
        execute('reflection-hidden-target-rejected', f'''#namespace 1234
            <T> T value(T n):0042{{return n;}}void main(){{reflect_invoke_function<int>({REFLECT_TARGET},4);}}''', error=True)
        execute('reflection-constructor-rejected', f'''#namespace 1234
            class Item{{int x;Item():0042{{}}}}void main(){{reflect_invoke_function<void>({REFLECT_TARGET},null);}}''', error=True)
        execute('reflection-destructor-rejected', f'''#namespace 1234
            class Item{{int x;~Item():0042{{}}}}void main(){{reflect_invoke_function<void>({REFLECT_TARGET},null);}}''', error=True)
        execute('reflection-lifecycle-rejected', 'void __script_onload(){}void main(){reflect_invoke_function<void>(0);}', error=True)
        execute('hint-not-loaded', '''void main(){print(reflect_hint_loaded("MISSING"));print(reflect_hint_loaded(""));}''', 'false\nfalse\n')
        execute('hint-missing-namespace', 'void main(){reflect_get_hint_namespace("MISSING");}', error=True)

        invalid = {
            'argument-conflict': IDENTITY + 'void main(){identity<int>("bad");}',
            'inference-conflict': '<T> T first(T a,T b){return a;}void main(){first(1,"bad");}',
            'null-inference': IDENTITY + 'void main(){identity(null);}',
            'raw-class': BOX + 'void main(){Box b;}',
            'void-type-argument': BOX + 'void main(){Box<void> b;}',
            'phantom-invariance': 'class Tag<T>{int value;}void main(){Tag<int>* a=null;Tag<string>* b=a;}',
            'box-invariance': BOX + 'void main(){Box<int>* a=null;Box<string>* b=a;}',
            'missing-default': POINT + BOX + 'void main(){Box<Point> b;}',
            'uninitialized-local': '<T> void f(){T local;}void main(){f<int>();}',
            'unbounded-arithmetic': '<T> T add(T a,T b){return a+b;}void main(){add(1,2);}',
            'unbounded-member': '<T> int get(T value){return value.x;}void main(){}',
            'new-type-parameter': '<T> T create(){return new T();}void main(){}',
            'pointer-to-type-parameter': '<T> void f(T* value){}void main(){}',
            'bound-wrong-representation': 'class Base{int x;}<T extends Base*> T id(T x){return x;}void main(){Base b;id(b);}',
            'bound-wrong-layout': 'class Base{int x;}class Other{string x;}<T extends Base*> T id(T x){return x;}void main(){Other* p=null;id(p);}',
            'primitive-bound': '<T extends int> T id(T x){return x;}void main(){}',
            'forward-bound-cache': 'class Early<T extends Guard<int>*>{T value;}class Base{int x;}'
                                   'class Guard<U extends Base*>{U value;}void main(){Guard<int>* bad=null;}',
            'self-bound': 'class Node<T extends Node<T>*>{T next;}void main(){}',
            'unused-parameter-inference': '<T> int f(){return 1;}void main(){f();}',
            'reflection-missing-type': 'void main(){reflect_invoke_function(1);}',
            'reflection-extra-types': 'void main(){reflect_invoke_function<int,string>(1);}',
        }
        for name, source in invalid.items():
            name = 'invalid-' + name
            if selected(name):
                compile_case(name, source, valid=False)
                checks += 1

        if selected('ast-type-nesting-limit'):
            artifact = compile_case('ast-type-base', BOX + 'void main(){Box<int>* value=null;}')
            ast = artifact[1]
            main_function = next(fn for namespace in ast['body'].values() for fn in namespace.values()
                                 if fn['metadata']['name'] == 'main')
            for nesting in (63, 64, 8000):
                main_function['script'][0]['declared-type'] = 'Box<' * nesting + 'int' + '>' * nesting + '*'
                nested = work / 'ast-type-nesting.json'
                nested.write_text(json.dumps(ast), encoding='utf-8')
                result = command(compiler + ['compile-json', nested, '-o', work / 'ast-type-nesting.abd'])
                if nesting == 63:
                    assert result.returncode == 0 and not result.stderr, result.stderr[:1000]
                else:
                    assert result.returncode != 0 and 'Type nesting exceeds 64' in result.stderr, result.stderr[:1000]
                    assert 'Exception in thread' not in result.stderr and 'StackOverflow' not in result.stderr, result.stderr[:1000]
                checks += 1

        if selected('long-inheritance-small-stack'):
            source = ''.join(f'class C{i}:C{i+1}{{}}' for i in range(1000))
            source += 'class C1000{int value;}void main(){}'
            path = work / 'long-inheritance.azs'
            path.write_text(source, encoding='utf-8')
            # A small JVM stack makes accidental recursive layout walks fail deterministically.
            small_stack_compiler = ['java', '-Xss256k', '-cp', args.classpath, 'azertia.Main']
            result = command(small_stack_compiler + ['compile', path, '-o', work / 'long-inheritance.abd'])
            assert result.returncode == 0 and not result.stderr, result.stderr[:1000]
            checks += 1

        if selected('cross-hint-generic'):
            header = '''#ifndef GENERIC_BOX_INCLUDED
                #define GENERIC_BOX_INCLUDED 1
                #assume_hint GENERIC_BOX 2345
                class Box<T>{T value;extern Box():23450002;extern T get():23450003;
                    extern ~Box():23450004;}
                extern <U> U echo(U):23450005;
                #fi
            '''
            (work / 'box.include.azs').write_text(header, encoding='utf-8')
            library = compile_case('generic-library', '''#namespace_hint GENERIC_BOX
                #include "box.include.azs"
                Box<T>::Box():0002{}T Box<T>::get():0003{return value;}
                Box<T>::~Box():0004{T copy=echo<T>(value);print("drop box");}
                <V> V echo(V value):0005{return value;}
                int number():0006{return 17;}
                void __script_onload(){print(reflect_hint_loaded("GENERIC_BOX"));}
            ''')
            client = compile_case('generic-client', '''#include "box.include.azs"
                void main(){Box<int> n;n.value=4;Box<string>* text=new Box<string>();text.value="library";
                print(n.get());print(echo(text.get()));print(reflect_hint_loaded("GENERIC_BOX"));
                print(reflect_hint_loaded("generic_box"));int ns=reflect_get_hint_namespace("GENERIC_BOX");
                print(reflect_invoke_function<int>(ns*65536+6));delete text;}
            ''')
            run_case('cross-hint-generic', client, 'true\n4\nlibrary\ntrue\nfalse\n17\ndrop box\ndrop box\n', libraries=[library])
            run_case('cross-hint-generic-reversed', library, 'true\n4\nlibrary\ntrue\nfalse\n17\ndrop box\ndrop box\n', libraries=[client])

        if selected('cross-hint-default-error'):
            library = compile_case('default-library', '''#namespace_hint DEFAULT_LIB
                class Holder<T>{T value;}
                <T> void needDefault(T input):0002{Holder<T> local;}
            ''')
            client = compile_case('default-client', POINT + '''#assume_hint DEFAULT_LIB 4567
                extern <T> void needDefault(T):45670002;
                void main(){Point p(3);needDefault(p);}
            ''')
            run_case('cross-hint-default-error', client, '~3\n~3\n', error=True, libraries=[library])

        if args.host and not args.compile_only and selected('host-cleanup'):
            for name, source in {
                'success': POINT + IDENTITY + 'void main(){Point* p(4);Point* q=identity(p);print(q.x);}',
                'failure': '''class Tag{int x;}class Bad<T>{T value;Bad(){int zero=0;int n=1/zero;}}
                    void main(){Bad<Tag>* p();}''',
            }.items():
                artifact = compile_case('host-' + name, source)
                operation = 'call' if name == 'success' else 'reject-call'
                result = command([args.host, artifact[0], 'flush', operation, str(MAIN), '0', 'heap-empty'])
                assert result.returncode == 0 and result.stdout.splitlines()[-1] == 'empty' and not result.stderr, (name, result.stdout, result.stderr)
                checks += 1

        if args.library and not args.compile_only and selected('jni-generic-snapshot'):
            assert args.bridge, '--library requires --bridge'
            (work / 'cell.include.azs').write_text('''#assume_hint SNAPSHOT_CELL 3210
                class Cell<T>{T value;extern Cell():32100002;extern ~Cell():32100003;}
            ''', encoding='utf-8')
            library = compile_case('jni-cell-library', '''#namespace_hint SNAPSHOT_CELL
                #include "cell.include.azs"
                <T> T identity(T value){return value;}
                class Holder<T>{T item;}
                Cell<T>::Cell():0002{}
                Cell<T>::~Cell():0003{Holder<T> temporary;T copy=identity(value);print("drop");}
            ''')
            artifact = compile_case('jni-generic', '#namespace 1234\n#gvar saved\n' + BOX + '''
                #include "cell.include.azs"
                void __script_onload(){saved=new Cell<Box<int>>();}
                address savedAddress():0010{return saved;}
                Cell<Box<int>>* cell(){return reflect_invoke_function<Cell<Box<int>>*>(305397776);}
                int read(){return cell().value.value;}
                void mutate(int n){cell().value.value=n;}
                void release(){delete cell();saved=null;}
                int host(){return reflect_invoke_function<int>(591724545,7);}
            ''')
            names = artifact[1]['abstract']
            java = work / 'GenericBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.io.File;
import java.nio.file.Files;
public class GenericBridge {
 public static void main(String[] args) throws Exception {
  AbdInvoker.registerJfunction(0x23450001, values -> (Integer)values[0] + 5);
  try {
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.insertScript(new File(args[2]));AbdInvoker.flush();
   int read=Integer.parseUnsignedInt(args[3],16), mutate=Integer.parseUnsignedInt(args[4],16);
   int release=Integer.parseUnsignedInt(args[5],16), host=Integer.parseUnsignedInt(args[6],16);
   if (!Integer.valueOf(12).equals(AbdInvoker.invoke(host))) throw new AssertionError("undeclared host reflection");
   AbdInvoker.invoke(mutate,9);
   if (!AbdInvoker.saveStatus(new File(args[1]))) throw new AssertionError("snapshot save");
   AbdInvoker.invoke(mutate,3);
   byte[] saved=Files.readAllBytes(new File(args[1]).toPath());
   File bad=new File(args[1]+".corrupt");
   for(int mode=0;mode<8;mode++) {
    AcsObject root=new AcsObject(AbdValue.fromAbd(saved));
    AcsObject object=(AcsObject)root.getAsAcsArray("objects").acsa.get(0);
    AcsObject context=(AcsObject)object.getAsAcsArray("destructor contexts").acsa.get(0);
    switch(mode) {
     case 0 -> root.put("snapshot version",7);
     case 1 -> context.put("abi",6);
     case 2 -> context.put("kind",2);
     case 3 -> context.put("factory",0x12340077);
     case 4 -> context.put("contexts",new AcsArray());
     case 5 -> context.put("has factory",false);
     case 6 -> object.put("destructor contexts",new AcsArray());
     case 7 -> {
      AcsObject manifest=(AcsObject)root.getAsAcsArray("module manifest").acsa.get(0);
      byte[] bytes=manifest.getAsBytes("bytes");bytes[bytes.length-1]^=1;manifest.put("bytes",bytes);
     }
    }
    Files.write(bad.toPath(),root.toValue().toAbdFormat());
    try {AbdInvoker.loadStatus(bad);throw new AssertionError("corrupt context accepted: "+mode);}
    catch(IllegalArgumentException expected) {}
    if(!Integer.valueOf(3).equals(AbdInvoker.invoke(read)))throw new AssertionError("failed restore changed state: "+mode);
   }
   AbdInvoker.loadStatus(new File(args[1]));
   if (!Integer.valueOf(9).equals(AbdInvoker.invoke(read))) throw new AssertionError("snapshot state");
   AbdInvoker.invoke(release);System.out.println("GENERIC_JNI_OK");
  } finally {AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            java_classpath = os.pathsep.join((str(work), str(args.bridge), args.classpath))
            built = command(['javac', '--release', '17', '-cp', java_classpath, '-d', work, java])
            assert built.returncode == 0, (built.stdout, built.stderr)
            result = command(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                              '-cp', java_classpath, 'GenericBridge', artifact[0],
                              work / 'generic.snapshot', library[0], names['read'], names['mutate'], names['release'], names['host']])
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            assert result.stdout.count('GENERIC_JNI_OK\n') == 1, result.stdout
            assert result.stdout.replace('GENERIC_JNI_OK\n', '') == 'drop\n', result.stdout
            checks += 9

    assert checks > 0, 'No cases selected'
    print(f'Generic/reflection checks passed: {checks} (AST roundtrip, shared bodies, defaults, bounds, '
          'hint modules, ownership and error paths).')


if __name__ == '__main__':
    main()
