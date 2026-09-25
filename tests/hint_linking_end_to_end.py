#!/usr/bin/env python3
"""Compile independent libraries, link them, and exercise class boundaries."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = '0x0fff0000'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    with tempfile.TemporaryDirectory(prefix='hints-', dir=ROOT / 'build') as temp:
        work = Path(temp)

        def run(command, success=True):
            result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=45)
            assert (result.returncode == 0) == success, (command, result.stdout, result.stderr)
            return result

        def compile_module(name, source=None):
            path = work / (name + '.azs')
            if source is not None:
                path.write_text(source, encoding='utf-8')
            abd, ast, executable = (work / (name + ext) for ext in ('.abd', '.ast.json', '.exec.json'))
            run(compiler + ['compile', path, '-o', abd, '--ast', ast, '--exec-json', executable])
            roundtrip = work / (name + '.roundtrip.abd')
            run(compiler + ['compile-json', ast, '-o', roundtrip])
            assert abd.read_bytes() == roundtrip.read_bytes(), name
            tree = json.loads(executable.read_text())
            assert tree['exec-version'] == 7
            return abd, tree

        def execute(abd, libraries, expected):
            result = run([args.runner, abd, *[part for lib in libraries for part in ('--insert', lib)]])
            assert result.stdout.splitlines() == expected, (result.stdout, result.stderr)

        for path in (ROOT / 'compiler/examples/multifile').glob('*.azs'):
            shutil.copy2(path, work / path.name)
        client, client_exec = compile_module('main')
        point, point_exec = compile_module('point')
        assert point_exec['namespace-hint'] == 'POINT_LIB'
        assert all((f['id'] & 0xffff0000) == 0 for f in point_exec['f'])
        assert all((f['id'] & 0xffff0000) != 0xaddd0000 for f in client_exec['f'])
        # Class names, layout and type identities do not survive in exec metadata.
        for program in (client_exec, point_exec):
            assert 'classes' not in program and 'class-types' not in program
            for function in program['f']:
                assert all(type(t) is int for t in function['param-types'])
        expected = ['library-load', 'main-load', '42', '44', 'point:42', 'point:41', '42']
        execute(client, [point], expected)
        result = run([args.host, client, 'setup', 'reject-call', MAIN, 0, 'reject-flush',
                      'setup', 'insert', point, 'setup', 'flush', 'setup', 'flush',
                      'reject-insert', point, 'setup', 'call', MAIN, 0])
        assert result.stdout.splitlines() == [
            'pending', 'rejected', 'rejected', 'pending', 'pending',
            'library-load', 'main-load', 'ready', 'rejected', 'ready', *expected[2:]], result.stdout

        returns, _ = compile_module('return_library', (work / 'point.azs').read_text() + '''
            Point* produce():0006{Point * p(3);return p;}
            Point* manual():0007{return new Point(4);}''')
        returning, _ = compile_module('return_client', '''#include "point.include.azs"
            extern Point* produce():addd0006;
            extern Point* manual():addd0007;
            Point* forward(){return produce();}
            int main(){Point* p=forward();print(p.get());Point* m=manual();print(m.get());delete m;return 0;}''')
        execute(returning, [returns], ['library-load', '44', '46', 'point:42', 'point:41', '0'])

        # Alias 2222 has different meanings in root and Y; linking is module-local.
        root, _ = compile_module('aliases', '''#assume_hint X 2222
            #assume_hint Y 3333
            extern int x():22220002;
            extern int y():33330002;
            int main(){return x()+y();}''')
        x, _ = compile_module('x', '#namespace_hint X\nint value():0002{return 10;}')
        y, _ = compile_module('y', '''#namespace_hint Y
            #assume_hint Z 2222
            extern int z():22220002;
            int value():0002{return z()+1;}''')
        z, _ = compile_module('z', '#namespace_hint Z\nint value():0002{return 20;}')
        for libraries in ([x, y, z], [z, y, x], [y, x, z]):
            execute(root, libraries, ['31'])
        result = run([args.host, root, 'insert', x, 'insert', y, 'reject-flush',
                      'insert', z, 'flush', 'call', MAIN, 0, 'flush', 'call', MAIN, 0])
        assert result.stdout.splitlines() == ['rejected', '31', '31'], result.stdout

        cycle, _ = compile_module('cycle', '''#assume_hint A 4444
            extern int a(int):44440003;
            void __script_onload(){print("root");}
            int main(){return a(2);}''')
        a, _ = compile_module('cycle_a', '''#namespace_hint A
            #assume_hint B 2222
            extern int b(int):22220003;
            #gvar value
            void __script_onload(){value=1;print("A");}
            int sum(int n):0003{if(n==0){return value;}return value+b(n-1);}''')
        b, _ = compile_module('cycle_b', '''#namespace_hint B
            #assume_hint A 3333
            extern int a(int):33330003;
            #gvar value
            void __script_onload(){value=2;print("B");}
            int sum(int n):0003{if(n==0){return value;}return value+a(n-1);}''')
        execute(cycle, [a, b], ['A', 'B', 'root', '4'])
        execute(cycle, [b, a], ['B', 'A', 'root', '4'])

        equivalent, _ = compile_module('equivalent', '''
            class A{int first; B* next;}
            class B{double value; A* link;}
            class C{int renamed; D* child;}
            class D{double renamed; C* ref;}
            int accept(C value){return value.renamed;}
            C convert(A value){return value;}
            int main(){A a;a.first=7;C c=a;return accept(a)+convert(a).renamed;}''')
        execute(equivalent, [], ['14'])
        initialization, _ = compile_module('init_scope', '''
            class C{int x=7;int y=x;C(int x){this.x=x;}}
            int main(){C c(100);return c.y;}''')
        execute(initialization, [], ['7'])
        temporary, _ = compile_module('init_temporary', '''
            class Marker{int x;~Marker(){print("temporary");}}
            Marker make(){Marker m;return m;}
            class C{Marker ref=make();C(){print("body");}}
            int main(){C c;return 0;}''')
        execute(temporary, [], ['body', 'temporary', '0'])
        high, _ = compile_module('high', '''#namespace ffff
            int value():ffff{return 7;}
            int main(){return value();}''')
        execute(high, [], ['7'])
        high_destructor, _ = compile_module('high_destructor', '''#namespace ffff
            class C{int x;extern ~C():ffffffff;}
            C::~C():ffff{print("high-dtor");}
            int main(){C c;return 7;}''')
        execute(high_destructor, [], ['high-dtor', '7'])

        # An extern takes call-binding priority even when a same-name definition exists.
        priority, _ = compile_module('priority', '''#assume_hint X 2222
            extern int value():22220002;
            int value(){return 99;}
            int main(){return value();}''')
        execute(priority, [x], ['10'])
        self_impl, _ = compile_module('self_impl', '''#namespace_hint SELF
            #assume_hint SELF 9999
            extern int value(int):99990009;
            int value(int n){if(n==0){return 5;}return value(n-1);}
            int main(){return value(3);}''')
        result = run([args.host, self_impl, 'flush', 'hint-call', 'SELF', 9, 1, 2])
        assert result.stdout.splitlines() == ['5'], result.stdout
        reserved_missing, _ = compile_module('reserved_missing', 'extern int missing():0fff0010;')
        result = run([args.host, reserved_missing, 'reject-flush'])
        assert result.stdout.splitlines() == ['rejected'], result.stdout

        bad_sources = {
            'old-extern': '#extern int a() 0x12340002\nvoid main(){}',
            'double-namespace': '#namespace 1234\n#namespace_hint A\nvoid main(){}',
            'double-hint': '#namespace_hint A\n#namespace_hint A\nint f(){return 1;}',
            'alias-conflict': '#assume_hint A 1234\n#assume_hint B 1234\nvoid main(){}',
            'hint-conflict': '#assume_hint A 1234\n#assume_hint A 1235\nvoid main(){}',
            'inline-library': '#namespace_hint A\nclass C{int x;C(){}}',
            'reserved-local': '#namespace_hint A\nint f():0001{return 1;}',
            'signature-conflict': 'extern int f(int):12340002;int f(double n){return 1;}',
            'unequal-layout': 'class A{int x;}class B{double x;}void main(){A a;B b=a;}',
        }
        for name, source in bad_sources.items():
            path = work / (name + '.azs')
            path.write_text(source)
            run(compiler + ['compile', path, '-o', work / (name + '.abd')], success=False)

        if args.bridge and args.library:
            snapshot_program, _ = compile_module('snapshot_client', '''#include "point.include.azs"
                Point* createAuto(){Point * p(3);return p;}
                Point* createManual(){return new Point(5);}
                int read(Point* p){return p.get();}
                void release(Point* p){delete p;}''')
            ast = json.loads((work / 'snapshot_client.ast.json').read_text())
            ids = [int(ast['abstract'][name], 16) for name in ('createAuto', 'createManual', 'read', 'release')]
            java = work / 'HintSourceSnapshot.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class HintSourceSnapshot {
 public static void main(String[] args) throws Exception {
  File program=new File(args[0]), library=new File(args[1]), saved=new File(args[2]);
  int autoId=Integer.parseUnsignedInt(args[3]), manualId=Integer.parseUnsignedInt(args[4]);
  int read=Integer.parseUnsignedInt(args[5]), release=Integer.parseUnsignedInt(args[6]);
  try {
   AbdInvoker.loadScript(program);
   AbdInvoker.insertScript(library);
   AbdInvoker.flush();
   int namespace=AbdInvoker.namespaceForHint("POINT_LIB");
   if(namespace<=0)throw new AssertionError("namespace");
   Object automatic=AbdInvoker.invoke(autoId), manual=AbdInvoker.invoke(manualId);
   if(!AbdInvoker.saveStatus(saved))throw new AssertionError("save");
   AbdInvoker.invoke(release,manual);
   AbdInvoker.loadStatus(saved);
   if(!Integer.valueOf(44).equals(AbdInvoker.invoke(read,automatic)))throw new AssertionError("auto restore");
   if(!Integer.valueOf(47).equals(AbdInvoker.invoke(read,manual)))throw new AssertionError("manual restore");
   AbdInvoker.invoke(release,manual);
   AbdInvoker.flush();
  } finally {AbdInvoker.close();}
 }
}''', encoding='utf-8')
            run(['javac', '--release', '17', '-cp', args.bridge, '-d', work, java])
            result = run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                          '-cp', os.pathsep.join((str(work), str(args.bridge))), 'HintSourceSnapshot',
                          snapshot_program, point, work / 'hints.snapshot.abd', *ids])
            assert result.stdout.splitlines() == ['library-load', 'point:42', 'point:42', 'point:41'], result.stdout
            assert 'WARNING in native method' not in result.stdout + result.stderr
        print('Hint linking: source/AST/ABD roundtrips, extern class factories, implementation-local '
              'initializers, dependency cycles, alias isolation, atomic retry, readiness, structural '
              'types, full-width IDs and grammar rejection passed.')


if __name__ == '__main__':
    main()
