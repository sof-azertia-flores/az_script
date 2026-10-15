#!/usr/bin/env python3
"""Typed global declarations: initialization order, pointer globals, hint libraries and JNI snapshots."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
POINT = '''class P{int x;P(int n){x=n;}~P(){print("~P "+x);}}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    parser.add_argument('--only', help='Run cases whose names contain this text')
    parser.add_argument('--compile-only', action='store_true', help='Check compiler/AST cases without executing ABD')
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='type-safety-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def selected(name):
            return args.only is None or args.only in name

        def command(argv, timeout=30):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)

        def compile_case(name, source, error=None):
            nonlocal checks
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast, executable = [work / (name + extension) for extension in ('.abd', '.ast.json', '.exec.json')]
            result = command(compiler + ['compile', path, '-o', abd, '--ast', ast, '--exec-json', executable])
            if error is not None:
                assert result.returncode == 1 and 'internal compiler error' not in result.stderr, (name, result.stdout, result.stderr)
                assert error in result.stderr, (name, error, result.stderr)
                checks += 1
                return None
            assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            # Source -> AST -> compile-json gives the same ABD bytes.
            rebuilt = work / (name + '.roundtrip.abd')
            result = command(compiler + ['compile-json', ast, '-o', rebuilt])
            assert result.returncode == 0 and not result.stderr, (name, 'AST rebuild', result.stdout, result.stderr)
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, 'AST roundtrip differs')
            return abd, json.loads(ast.read_text(encoding='utf-8')), json.loads(executable.read_text(encoding='utf-8'))

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
                    assert error.lower() in result.stderr.lower(), (name, error, result.stderr)
            checks += 1

        def execute(name, source, output='', error=None):
            if not selected(name):
                return None
            artifact = compile_case(name, source)
            run_case(name, artifact, output, error)
            return artifact

        # Conditions reject dynamic non-booleans at the point of use; equality
        # permits numeric promotion but rejects incompatible scalar tags.
        execute('boolean-conditions', 'boolean main(){return true && !false || false;}', 'true\n')
        execute('numeric-equality', 'void main(){print(1==1.0);print(1.0f==1.0);print(2!=2.0f);}', 'true\ntrue\nfalse\n')
        execute('dynamic-void-equality', 'void main(){def(a);def(b);print(a==b);print(a!=b);}', 'true\nfalse\n')
        execute('scalar-equality', 'void main(){print("x"=="x");print(true!=false);print(null==null);}', 'true\ntrue\ntrue\n')
        for name, body in [
                ('if', 'if(value)print(99);'),
                ('while', 'while(value)print(99);'),
                ('for', 'for(;value;)print(99);'),
                ('not', 'print(!value);'),
                ('and-left', 'print(value && true);'),
                ('and-right', 'print(true && value);'),
                ('or-left', 'print(value || false);'),
                ('or-right', 'print(false || value);')]:
            execute('dynamic-boolean-' + name, 'void main(){def(value,1);' + body + '}',
                    error='Condition must be boolean, got int')
        for name, value, other in [('string-int', '"1"', '1'), ('bool-int', 'true', '1'),
                                    ('string-bool', '"true"', 'true')]:
            for operator in ('==', '!='):
                execute('dynamic-equality-' + name + ('-eq' if operator == '==' else '-ne'),
                        'boolean main(){def(value,' + value + ');return value' + operator + other + ';}',
                        error='Equality requires compatible operand types')
        execute('boolean-short-circuit', 'void main(){def(value,1);print(false && value);print(true || value);}',
                'false\ntrue\n')
        if selected('static-boolean-errors'):
            for name, source in [('if', 'void main(){if(1)print(99);}'),
                                 ('while', 'void main(){while(0)print(99);}'),
                                 ('for', 'void main(){for(;1;)print(99);}'),
                                 ('not', 'boolean main(){return !1;}'),
                                 ('and', 'boolean main(){return true && 1;}'),
                                 ('or', 'boolean main(){return 1 || false;}')]:
                compile_case('static-boolean-' + name, source, 'must be boolean')
            for name, source in [('string-int', 'boolean main(){return "1"==1;}'),
                                 ('bool-int', 'boolean main(){return true!=1;}')]:
                compile_case('static-equality-' + name, source, 'Equality requires compatible operand types')

        # Every typed global gets its default first, so an initializer can read a later global
        # (c reads d's 0); the initializers then run in order, before the onload body.
        order = execute('typed-global-order', '''int trace(string label, int value){print(label);return value;}
            int a = trace("init a", 1);
            int b = trace("init b " + a, a + 1);
            int c = d + 10;
            int d = trace("init d", 4);
            string s;
            double ratio = 0.5;
            boolean on;
            address raw;
            void __script_onload(){print("onload " + a + " " + b + " " + c + " " + d + " [" + s + "] " + ratio + " " + on + " " + (raw == null));}
            void main(){print("main " + (a + b + c + d));}''',
            'init a\ninit b 1\ninit d\nonload 1 2 10 4 [] 0.5 false true\nmain 17\n')
        if order:
            onload = [function for function in order[2]['f'] if function['id'] == 0]
            assert len(onload) == 1 and onload[0]['return-type'] == 5, 'Typed globals need a synthesized onload'
            assert order[2]['gvs'] == 8, order[2]['gvs']
            checks += 1

        # A pointer global holds a manual object across calls; untyped #gvar globals keep the first slots.
        execute('typed-pointer-global', '#gvar legacy\n' + POINT + '''class Q : P{int y;Q(int n):P(n){y=n*2;}}
            P * head = new Q(3);
            int calls;
            void touch(){calls=calls+1;head.x=head.x+calls;}
            void __script_onload(){legacy="untyped";}
            void __script_pre_destroy(){delete head;}
            void main(){touch();touch();print(head.x);print(calls);print(legacy);}''',
            '6\n2\nuntyped\n~P 6\n')

        # Initializer errors are located at the global and name it as their context.
        if selected('typed-global-errors'):
            compile_case('typed-global-value', POINT + 'P g;\nvoid main(){}\n',
                         'typed-global-value.azs:2:1: error: in global g: Global g cannot hold a class value or buffer; declare a pointer')
            compile_case('typed-global-automatic', POINT + 'P * g(1);\nvoid main(){}\n',
                         'typed-global-automatic.azs:2:1: error: Global objects cannot be automatic; use = new P(...)')
            # Each initializer recovers on its own: a bad one hides neither the next nor the onload body.
            compile_case('typed-global-initializer', 'int n = "x";\nint m = "y";\nvoid __script_onload(){int k = "z";}\nvoid main(){}\n',
                         'typed-global-initializer.azs:1:1: error: in global n: Initializer for global n expected int, got string\n'
                         'typed-global-initializer.azs:2:1: error: in global m: Initializer for global m expected int, got string\n'
                         'typed-global-initializer.azs:3:24: error: in function __script_onload: Initializer for k expected int, got string\n')

        # A hint library keeps a generic pointer global, used through its extern declarations.
        if selected('typed-global-hint'):
            (work / 'counter.include.azs').write_text('''#assume_hint TYPED_COUNTER 3456
                class Box<T>{T value;}
                extern int counter_next():34560002;
            ''', encoding='utf-8')
            library = compile_case('typed-global-library', '''#namespace_hint TYPED_COUNTER
                #include "counter.include.azs"
                Box<int>* box = new Box<int>();
                int counter_next():0002{box.value=box.value+1;return box.value;}
                void __script_pre_destroy(){delete box;}
            ''')
            client = compile_case('typed-global-client', '''#include "counter.include.azs"
                int first = counter_next();
                void main(){print(first);print(counter_next());}
            ''')
            run_case('typed-global-hint', client, '1\n2\n', libraries=[library])
            # In a dependency cycle a module's typed globals are void until its own onload starts;
            # a typed read then fails at runtime instead of returning a value.
            library_a = compile_case('typed-global-cycle-a', '''#namespace_hint CYCLE_A
                #assume_hint CYCLE_B 4321
                extern int cycle_b():43210002;
                int cycle_a():0002{return 1;}
                void __script_onload(){print("A onload");print(cycle_b());}
            ''')
            library_b = compile_case('typed-global-cycle-b', '''#namespace_hint CYCLE_B
                #assume_hint CYCLE_A 1234
                extern int cycle_a():12340002;
                int b_value = 7;
                int cycle_b():0002{return b_value;}
            ''')
            run_case('typed-global-cycle', client, 'A onload\n', 'Function return type mismatch',
                     libraries=[library, library_a, library_b])

        if args.library and not args.compile_only and selected('typed-global-jni-snapshot'):
            assert args.bridge, '--library requires --bridge'
            artifact = compile_case('typed-global-jni', '#namespace 1234\n' + POINT + '''int count = 1;
                P * head = new P(5);
                void replace(int n){P* old=head;head=new P(n);delete old;count=count+1;}
                int read(){return head.x;}
                int total(){return count;}
                void __script_pre_destroy(){delete head;}
            ''')
            names = artifact[1]['abstract']
            java = work / 'TypedGlobalsBridge.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class TypedGlobalsBridge {
 static void expect(int id,int value,String what){
  Object actual=AbdInvoker.invoke(id);
  if(!Integer.valueOf(value).equals(actual))throw new AssertionError(what+": "+actual);
 }
 public static void main(String[] args) throws Exception {
  try {
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   int replace=Integer.parseUnsignedInt(args[2],16),read=Integer.parseUnsignedInt(args[3],16),total=Integer.parseUnsignedInt(args[4],16);
   expect(read,5,"initializer");expect(total,1,"scalar initializer");
   AbdInvoker.invoke(replace,9);
   if(!AbdInvoker.saveStatus(new File(args[1])))throw new AssertionError("snapshot save");
   AbdInvoker.invoke(replace,3);
   expect(read,3,"after replace");expect(total,3,"after replace");
   AbdInvoker.loadStatus(new File(args[1]));
   expect(read,9,"restored pointer global");expect(total,2,"restored scalar global");
   System.out.println("TYPED_GLOBALS_JNI_OK");
  } finally {AbdInvoker.close();}
 }
}
''', encoding='utf-8')
            java_classpath = os.pathsep.join((str(work), str(args.bridge), args.classpath))
            built = command(['javac', '--release', '17', '-cp', java_classpath, '-d', work, java])
            assert built.returncode == 0, (built.stdout, built.stderr)
            result = command(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                              '-cp', java_classpath, 'TypedGlobalsBridge', artifact[0], work / 'typed.snapshot',
                              names['replace'], names['read'], names['total']])
            assert result.returncode == 0 and not result.stderr, (result.stdout, result.stderr)
            assert result.stdout.count('TYPED_GLOBALS_JNI_OK\n') == 1, result.stdout
            # The replaced objects are destroyed once each; close deletes the restored one.
            assert result.stdout.replace('TYPED_GLOBALS_JNI_OK\n', '') == '~P 5\n~P 9\n~P 9\n', result.stdout
            checks += 1

    assert checks > 0, 'No cases selected'
    print(f'Type safety checks passed: {checks} (boolean conditions, equality, typed globals, AST roundtrip, hint libraries and snapshots).')


if __name__ == '__main__':
    main()
