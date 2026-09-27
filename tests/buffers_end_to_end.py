#!/usr/bin/env python3
"""Owning buffers, class operator lowering and default comparison regressions."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = 0x0FFF0000
SINGLETON = '''<T> buffer<T> singleton(T value){buffer<T> result;result.reserve(1);
    result.push(value);return result;}
'''
INDEX = '''class Index<T>{buffer<T> values;
    T operator[](int key){return values.get(key);}
    void operator[]=(int key,T value){values.set(key,value);}
    T operator()(int key){return values.get(key);}}
'''
BOUNDED = '''class Base<T>{T value;T operator[](int key){return value;}
    void operator[]=(int key,T next){value=next;}T operator()(){return value;}
    int operator+(int n){return n+1;}}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--only', help='Select case names containing this substring')
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='buffers-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def selected(name):
            return args.only is None or args.only in name

        def command(argv):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=30)

        def compile_case(name, source, valid=True):
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast, executable = [work / (name + ext) for ext in ('.abd', '.ast.json', '.exec.json')]
            result = command(compiler + ['compile', path, '-o', abd, '--ast', ast, '--exec-json', executable])
            if not valid:
                assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'Exception in thread' not in result.stderr and 'StackOverflow' not in result.stderr, (name, result.stderr)
                return None
            assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            rebuilt = work / (name + '.roundtrip.abd')
            result = command(compiler + ['compile-json', ast, '-o', rebuilt])
            assert result.returncode == 0 and not result.stderr, (name, 'AST rebuild', result.stdout, result.stderr)
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, 'AST roundtrip differs')
            tree = json.loads(executable.read_text(encoding='utf-8'))
            assert tree['exec-version'] == 9, (name, tree.get('exec-version'))
            assert 'classes' not in tree and 'operators' not in tree, name
            return abd, json.loads(ast.read_text(encoding='utf-8')), tree

        def run_case(name, artifact, output='', error=False, libraries=()):
            nonlocal checks
            if not args.compile_only:
                argv = [args.runner, artifact[0]]
                for library in libraries:
                    argv += ['--insert', library[0]]
                result = command(argv)
                assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
                if error:
                    assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                    if isinstance(error, str):
                        assert error.lower() in result.stderr.lower(), (name, error, result.stderr)
                else:
                    assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            checks += 1

        def execute(name, source, output='', error=False):
            if not selected(name):
                return None
            artifact = compile_case(name, source)
            run_case(name, artifact, output, error)
            return artifact

        simple = execute('buffer-basic', '''void main(){buffer<int> values;print(values.length());print(values.capacity());
            values.reserve(4);print(values.length());print(values.capacity()>=4);values.push(7);values.push(9);
            print(values.get(0));values.set(1,8);print(values.get(1));values.resize(4);print(values.get(3));
            values.resize(1);print(values.length());values.resize(0);print(values.length());}''',
            '0\n0\n0\ntrue\n7\n8\n0\n1\n0\n')
        if simple:
            assert 'classes' not in simple[1] and len(simple[2]['f']) == 1, 'Buffer storage must not generate element classes or factories'
            checks += 1
        execute('buffer-scalar-defaults', '''void main(){buffer<string> s;buffer<bool> b;buffer<float> f;
            buffer<double> d;buffer<address> a;s.reserve(1);b.reserve(1);f.reserve(1);d.reserve(1);a.reserve(1);
            s.resize(1);b.resize(1);f.resize(1);d.resize(1);a.resize(1);
            print(s.get(0)=="");print(b.get(0)==false);print(f.get(0)==0.0f);print(d.get(0)==0.0);print(a.get(0)==null);}''',
            'true\ntrue\ntrue\ntrue\ntrue\n')
        execute('buffer-independent-copy', '''void main(){buffer<int> a;a.reserve(2);a.push(3);buffer<int> b=a;
            b.set(0,9);b.push(5);print(a.get(0));print(a.length());print(b.get(0));print(b.length());
            a=b;b.set(0,7);print(a.get(0));}''', '3\n1\n9\n2\n9\n')
        execute('buffer-nested-values', '''void main(){buffer<buffer<int>> rows;rows.reserve(2);rows.resize(2);
            print(rows.get(0).length());buffer<int> row;row.reserve(1);row.push(3);rows.set(0,row);row.set(0,8);
            print(rows.get(0).get(0));buffer<buffer<int>> copy=rows;buffer<int> edit=copy.get(0);edit.set(0,6);
            copy.set(0,edit);print(rows.get(0).get(0));print(copy.get(0).get(0));print(copy.get(1).length());}''',
            '0\n3\n3\n6\n0\n')
        execute('buffer-class-field-copy', '''class Store{buffer<int> values;}
            Store clone(Store value){return value;}void main(){Store a;a.values.reserve(1);a.values.push(4);
            Store b=clone(a);b.values.set(0,7);print(a.values.get(0));print(b.values.get(0));}''', '4\n7\n')
        execute('buffer-generic-return', SINGLETON + '''void main(){buffer<int> n=singleton(5);
            buffer<string> s=singleton("yes");print(n.get(0));print(s.get(0));}''', '5\nyes\n')
        execute('buffer-generic-value-context', '''<T> T identity(T value){return value;}
            class Holder<T>{T value;}
            void main(){Holder<buffer<int>> holder;holder.value.reserve(1);holder.value.push(4);
            buffer<int> copy=identity(holder.value);copy.set(0,9);print(holder.value.get(0));print(copy.get(0));
            Holder<buffer<buffer<string>>> nested;print(nested.value.length());}''', '4\n9\n0\n')
        execute('buffer-recursive-class', '''class Tree{int value;buffer<Tree> children;}
            void main(){Tree root;root.value=3;root.children.reserve(1);root.children.push(root);
            Tree copy=root;Tree child=copy.children.get(0);child.value=8;copy.children.set(0,child);
            print(root.children.get(0).value);print(copy.children.get(0).value);
            print(copy.children.get(0).children.length());}''', '3\n8\n0\n')
        execute('buffer-no-default-transfer', SINGLETON + '''class Item{int value;Item(int n){value=n;}}
            void main(){buffer<Item> empty;empty.reserve(2);print(empty.length());Item source(6);
            buffer<Item> values=singleton(source);Item copy=values.get(0);copy.value=9;
            print(values.get(0).value);print(copy.value);}''', '0\n6\n9\n')
        execute('buffer-pointer-nonowning', '''class Item{int value=4;~Item(){print("drop");}}
            void main(){Item* p=new Item();{buffer<Item*> pointers;pointers.reserve(2);pointers.resize(1);
            print(pointers.get(0)==null);pointers.push(p);buffer<Item*> copy=pointers;copy.resize(0);}
            print(p.value);delete p;}''', 'true\n4\ndrop\n')
        execute('buffer-default-construction', '''#gvar made
            int next(){made=made+1;return made;}
            class Item{int value;Item(){value=next();}}
            void main(){made=0;buffer<Item> items;items.reserve(3);print(made);items.resize(2);print(made);
            print(items.get(0).value);print(items.get(1).value);items.reserve(5);print(made);
            items.resize(1);items.resize(3);print(made);}''', '0\n2\n1\n2\n2\n4\n')
        execute('buffer-borrowed-field-mutation', INDEX + '''void main(){Index<int> a;a.values.reserve(1);
            a.values.push(3);a[0]=7;print(a[0]);print(a(0));Index<int> b=a;b[0]=9;print(a[0]);print(b[0]);}''',
            '7\n7\n7\n9\n')
        execute('operator-arithmetic', '''class Number{int value;Number(int n){value=n;}
            Number operator+(Number rhs){Number result(value+rhs.value);return result;}
            Number operator-(Number rhs){Number result(value-rhs.value);return result;}
            Number operator*(Number rhs){Number result(value*rhs.value);return result;}
            Number operator/(Number rhs){Number result(value/rhs.value);return result;}}
            void main(){Number a(12);Number b(4);print((a+b).value);print((a-b).value);
            print((a*b).value);print((a/b).value);}''', '16\n8\n48\n3\n')
        execute('operator-call-chaining', '''class Callable{int base=7;int operator()(int n){return base+n;}}
            Callable create(){Callable value;return value;}int f(int n){return 99;}
            void main(){Callable f;print(f(3));print((f)(4));print(create()(5));}''', '10\n11\n12\n')
        execute('operator-call-implicit-field', '''class Callable{int value=6;int operator()(){return value;}}
            class Holder{Callable callable;int read(){return callable();}}
            void main(){Holder holder;print(holder.read());}''', '6\n')
        execute('operator-inheritance', '''class Base<T>{T value;T operator[](int key){return value;}
            void operator[]=(int key,T next){value=next;}}
            class Child<T>:Base<T>{int extra;}
            void main(){Child<int> c;c[0]=8;print(c[0]);}''', '8\n')
        execute('operator-value-bound', BOUNDED + '''class Child:Base<int>{string extra;}
            <T extends Base<int>> int read(T value){return value[0]+value()+(value+4);}
            void main(){Child value;value.value=3;print(read(value));}''', '11\n')
        execute('operator-pointer-bound', BOUNDED + '''class Child:Base<int>{string extra;}
            <T extends Base<int>*> int read(T value){value[0]=7;return value[0]+value();}
            void main(){Child* value();print(read(value));print(value.value);}''', '14\n7\n')
        execute('operator-inherited-buffer-field', '''class Base<T>{buffer<T> values;
            T operator[](int n){return values.get(n);}void operator[]=(int n,T value){values.set(n,value);}}
            class Child<A,B>:Base<buffer<B>>{A own;}
            void main(){Child<string,int> value;buffer<int> row;row.reserve(1);row.push(4);
            value.values.reserve(1);value.values.push(row);buffer<int> copy=value[0];copy.set(0,7);
            value[0]=copy;print(value[0].get(0));print(row.get(0));}''', '7\n4\n')
        execute('operator-inherited-getter-own-setter', '''class Base<T>{T value;T operator[](int n){return value;}}
            class Child<A,B>:Base<B>{A own;void operator[]=(int n,B next){value=next;}}
            void main(){Child<string,int> value;value[0]=8;print(value[0]);}''', '8\n')
        execute('getter-pointer-remains-writable', '''class Item{int value;buffer<int> data;}
            ''' + INDEX + '''void main(){Item* item();Index<Item*> values;values.values.reserve(1);values.values.push(item);
            values[0].value=7;values[0].data.reserve(1);values[0].data.push(9);
            print(item.value);print(item.data.get(0));}''', '7\n9\n')
        execute('operator-evaluation-order', '''#gvar order
            class Item{int value;int operator[](int key){return value;}void operator[]=(int key,int next){value=next;}}
            Item* receiver(Item* value){order=order*10+1;return value;}
            int key(){order=order*10+2;return 0;}int next(){order=order*10+3;return 7;}
            void main(){Item* item();order=0;receiver(item)[key()]=next();print(order);
            order=0;print(receiver(item)[key()]);print(order);}''', '123\n7\n12\n')
        execute('buffer-evaluation-order', '''#gvar order
            buffer<int> create(){order=order*10+1;buffer<int> value;value.reserve(1);value.push(8);return value;}
            int key(){order=order*10+2;return 0;}
            void main(){order=0;print(create().get(key()));print(order);}''', '8\n12\n')
        execute('value-compare-primitives', '''<T> int compare(T a,T b){return value_compare<T>(a,b);}
            void main(){print(compare(2,9)<0);print(compare(9,2)>0);print(compare(2,2)==0);
            print(compare("a","b")<0);print(compare("a\\0b","a\\0c")<0);print(compare("","x")<0);
            print(compare(false,true)<0);print(compare(1.0f,2.0f)<0);print(compare(-0.0,0.0)==0);
            address p=alloc(1);print(compare(p,p)==0);print(value_compare<address>(null,p)<0);mem_free(p);}''',
            'true\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\ntrue\n')
        execute('value-compare-pointer-identity', '''class Item{int value;}
            void main(){Item* a();Item* b();print(value_compare<Item*>(a,a)==0);
            print(value_compare<Item*>(a,b)!=0);print(value_compare<Item*>(null,a)<0);}''', 'true\ntrue\ntrue\n')
        execute('custom-funcid-comparator', '''#namespace 1234
            class Item{int value;}
            int compare(Item a,Item b):0042{return value_compare<int>(a.value,b.value);}
            <T> int dispatch(int id,T a,T b){return reflect_invoke_function<int>(id,a,b);}
            void main(){Item a;Item b;a.value=3;b.value=7;print(dispatch<Item>(305397826,a,b)<0);}''', 'true\n')
        execute('buffer-reflection-return', '''#namespace 1234
            buffer<int> create():0042{buffer<int> result;result.reserve(1);result.push(8);return result;}
            void main(){buffer<int> values=reflect_invoke_function<buffer<int>>(305397826);print(values.get(0));}''', '8\n')

        for name, body in {
            'push-capacity': 'buffer<int> a;a.push(1);',
            'resize-capacity': 'buffer<int> a;a.resize(1);',
            'get-bounds': 'buffer<int> a;a.get(0);',
            'set-bounds': 'buffer<int> a;a.set(0,1);',
            'negative-reserve': 'buffer<int> a;a.reserve(-1);',
            'negative-resize': 'buffer<int> a;a.resize(-1);',
            'negative-index': 'buffer<int> a;a.reserve(1);a.push(1);a.get(-1);',
        }.items():
            execute('error-' + name, 'void main(){' + body + '}', error=True)
        execute('error-generic-default', '''class Item{int value;Item(int n){value=n;}}
            <T> void grow(){buffer<T> values;values.reserve(1);values.resize(1);}
            void main(){grow<Item>();}''', error=True)
        execute('error-concrete-default', '''class Item{int value;Item(int n){value=n;}}
            void main(){buffer<Item> values;values.reserve(2);values.resize(1);}''', error=True)
        execute('buffer-no-default-shrink', '''class Item{int value;Item(int n){value=n;}}
            void main(){buffer<Item> values;values.reserve(2);Item first(1);Item second(2);
            values.push(first);values.push(second);values.resize(1);print(values.get(0).value);
            values.resize(0);print(values.length());}''', '1\n0\n')
        execute('error-generic-compare', '''class Item{int value;}
            <T> int compare(T a,T b){return value_compare<T>(a,b);}
            void main(){Item a;Item b;compare(a,b);}''', error=True)
        execute('error-buffer-constructor', '''#gvar made
            class Item{int value;Item(){made=made+1;if(made==2){int bad=1/0;}}~Item(){print("drop");}}
            void main(){made=0;buffer<Item> values;values.reserve(3);values.resize(3);}''', 'drop\n', error=True)
        execute('error-buffer-destructor', '''class Item{int value;~Item(){print("drop");int bad=1/0;}}
            void main(){buffer<Item> values;values.reserve(2);values.resize(2);}''', 'drop\ndrop\n', error=True)

        invalid = {
            'raw-buffer': 'void main(){buffer b;}',
            'buffer-void': 'void main(){buffer<void> b;}',
            'buffer-pointer': 'void main(){buffer<int>* b;}',
            'buffer-inheritance': 'class Bad:buffer<int>{int value;}',
            'new-buffer': 'void main(){new buffer<int>();}',
            'delete-buffer': 'void main(){buffer<int> b;delete b;}',
            'buffer-invariant': 'void main(){buffer<int> a;buffer<double> b=a;}',
            'buffer-untyped-storage': 'void main(){buffer<int> a;var b=a;}',
            'buffer-raw-return': 'buffer<int> read(address p){return mem_get(p);}void main(){}',
            'buffer-raw-write': 'void main(){buffer<int> a;address p=alloc(1);mem_get(p)=a;}',
            'wrong-element': 'void main(){buffer<int> a;a.reserve(1);a.push("bad");}',
            'wrong-index': 'void main(){buffer<int> a;a.get("bad");}',
            'buffer-index-syntax': 'void main(){buffer<int> a;a[0]=1;}',
            'compare-missing-type': 'void main(){value_compare(1,2);}',
            'compare-mismatched-type': 'void main(){value_compare<int>(1,"bad");}',
            'compare-class': 'class Item{int value;}void main(){Item a;Item b;value_compare<Item>(a,b);}',
            'compare-buffer': 'void main(){buffer<int> a;value_compare<buffer<int>>(a,a);}',
            'unbounded-operator': '<T> T add(T a,T b){return a+b;}void main(){}',
            'generic-operator': 'class Callable<T>{T state;<U> U operator()(U value){return value;}}void main(){}',
            'unary-class-operator': 'class Item{int value;Item operator+(Item rhs){return rhs;}}void main(){Item a;+a;}',
            'missing-index-getter': 'class Item{int value;void operator[]=(int key,int next){value=next;}}void main(){}',
            'inconsistent-index-types': 'class Item{int value;int operator[](int key){return value;}void operator[]=(string key,int next){value=next;}}void main(){}',
            'index-compound': INDEX + 'void main(){Index<int> a;a[0]+=1;}',
            'nested-getter-write': INDEX + 'void main(){Index<Index<int>> a;a[0][0]=1;}',
            'getter-field-write': 'class Item{int value;}' + INDEX + 'void main(){Index<Item> a;a[0].value=1;}',
            'buffer-getter-field-write': 'class Item{int value;}void main(){buffer<Item> a;a.get(0).value=1;}',
            'buffer-getter-owned-field-write': 'class Item{buffer<int> values;}void main(){buffer<Item> a;a.get(0).values.reserve(1);}',
            'generic-bound-getter-write': BOUNDED + 'class Index<T>{T value;T operator[](int n){return value;}}'
                                          '<T extends Base<int>> void bad(Index<T> value){value[0][0]=1;}void main(){}',
            'inherited-index-type-conflict': 'class Base<T>{T value;T operator[](int n){return value;}}'
                                            'class Child<A,B>:Base<B>{A own;void operator[]=(int n,A next){own=next;}}void main(){}',
            'extern-operator-return-conflict': 'class Item{int value;extern int operator[](int):0fff0002;}'
                                               'string Item::operator[](int key):0002{return "bad";}void main(){}',
            'extern-operator-parameter-conflict': 'class Item{int value;extern int operator[](int):0fff0002;}'
                                                  'int Item::operator[](string key):0002{return 1;}void main(){}',
            'extern-operator-buffer-conflict': 'class Item{int value;extern buffer<int> operator[](int):0fff0002;}'
                                               'buffer<string> Item::operator[](int key):0002{buffer<string> value;return value;}void main(){}',
        }
        for name, source in invalid.items():
            name = 'invalid-' + name
            if selected(name):
                compile_case(name, source, valid=False)
                checks += 1

        if selected('cross-hint-operators'):
            (work / 'index.include.azs').write_text('''#assume_hint BUFFER_INDEX 3210
                class Index<T>{buffer<T> values;extern Index():32100002;
                extern T operator[](int key):32100003;extern void operator[]=(int key,T value):32100004;
                extern T operator()(int key):32100005;}
            ''', encoding='utf-8')
            library = compile_case('index-library', '''#namespace_hint BUFFER_INDEX
                #include "index.include.azs"
                Index<T>::Index():0002{}
                T Index<T>::operator[](int key):0003{return values.get(key);}
                void Index<T>::operator[]=(int key,T value):0004{values.set(key,value);}
                T Index<T>::operator()(int key):0005{return values.get(key);}
            ''')
            client = compile_case('index-client', '''#include "index.include.azs"
                void main(){Index<int> a;a.values.reserve(1);a.values.push(3);Index<int> copy=a;
                copy[0]=9;print(a[0]);print(copy(0));}
            ''')
            run_case('cross-hint-operators', client, '3\n9\n', libraries=[library])
            run_case('cross-hint-operators-reversed', library, '3\n9\n', libraries=[client])

        if args.host and not args.compile_only and selected('buffer-host-cleanup'):
            for name, source in {
                'success': 'void main(){buffer<buffer<int>> values;values.reserve(2);values.resize(2);}',
                'failure': 'class Item{int value;~Item(){int bad=1/0;}}void main(){buffer<Item> values;values.reserve(2);values.resize(2);}',
            }.items():
                artifact = compile_case('buffer-host-' + name, source)
                operation = 'call' if name == 'success' else 'reject-call'
                result = command([args.host, artifact[0], 'flush', operation, MAIN, '0', 'heap-empty'])
                assert result.returncode == 0 and result.stdout.splitlines()[-1] == 'empty' and not result.stderr, (name, result.stdout, result.stderr)
                checks += 1

        if args.library and not args.compile_only and selected('buffer-jni-calls'):
            assert args.bridge, '--library requires --bridge'
            artifact = compile_case('buffer-jni', '''#namespace 1234
                int process(int value):0042{buffer<int> a;a.reserve(1);a.push(value);buffer<int> b=a;
                b.set(0,value+1);return a.get(0)+b.get(0);}
                void fail():0043{buffer<int> a;a.get(0);}
            ''')
            java = work / 'BufferBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class BufferBridge {
 public static void main(String[] args) throws Exception {
  try {
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   for(int value=0;value<32;value++) {
    if(!Integer.valueOf(value*2+1).equals(AbdInvoker.invoke(0x12340042,value)))throw new AssertionError("buffer copy");
   }
   try{AbdInvoker.invoke(0x12340043);throw new AssertionError("bounds accepted");}
   catch(IndexOutOfBoundsException expected){}
   if(!Integer.valueOf(15).equals(AbdInvoker.invoke(0x12340042,7)))throw new AssertionError("error cleanup");
   System.out.println("BUFFER_JNI_OK");
  }finally{AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            java_classpath = os.pathsep.join((str(work), str(args.bridge), args.classpath))
            result = command(['javac', '--release', '17', '-cp', java_classpath, '-d', work, java])
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            result = command(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                              '-cp', java_classpath, 'BufferBridge', artifact[0]])
            assert result.returncode == 0 and result.stdout == 'BUFFER_JNI_OK\n' and not result.stderr, (result.stdout, result.stderr)
            checks += 1

        if args.library and not args.compile_only and selected('buffer-snapshot'):
            assert args.bridge, '--library requires --bridge'
            result = command([sys.executable, ROOT / 'tests/buffers_snapshot.py', '--classpath', args.classpath,
                              '--bridge', args.bridge, '--library', args.library])
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            print(result.stdout, end='')
            checks += 1

    assert checks > 0, 'No cases selected'
    print(f'Buffer/operator checks passed: {checks} (AST roundtrip, storage, copies, defaults, operators, comparison and cleanup).')


if __name__ == '__main__':
    main()
