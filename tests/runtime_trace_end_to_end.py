#!/usr/bin/env python3
"""Exercise compiled debug records through native and real-JVM error reports."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def column(source, line, token, occurrence=0):
    text = source.splitlines()[line - 1]
    start = -1
    for _ in range(occurrence + 1):
        start = text.index(token, start + 1)
    return len(text[:start].encode('utf-16-le')) // 2 + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    checks = 0

    def run(command, *, cwd=ROOT):
        return subprocess.run(list(map(str, command)), cwd=cwd, capture_output=True,
                              text=True, encoding='utf-8', timeout=60)

    with tempfile.TemporaryDirectory(prefix='trace-e2e-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)

        def compile_case(name, source, debug=True):
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            binary = work / (name + '.exec.abd')
            ast = work / (name + '.ast.json')
            inspection = work / (name + '.exec.json')
            flags = ['-g'] if debug else []
            command = ['java', '-cp', args.classpath, 'azertia.Main']
            result = run([*command, 'compile', path, '-o', binary, '--ast', ast,
                          '--exec-json', inspection, *flags])
            assert result.returncode == 0, (result.stdout, result.stderr)
            restored = work / (name + '.from-json.abd')
            result = run([*command, 'compile-json', ast, '-o', restored, *flags])
            assert result.returncode == 0, (result.stdout, result.stderr)
            assert binary.read_bytes() == restored.read_bytes(), name
            return binary, json.loads(inspection.read_text(encoding='utf-8'))

        def frame(name, file, line, col):
            return f'  at {name} ({file}:{line}:{col})'

        def check(binary, message, frames, *, stdout='', insert=()):
            nonlocal checks
            command = [args.runner, binary]
            for module in insert:
                command.extend(['--insert', module])
            result = run(command)
            expected = 'AzScript error: ' + message + '\n' + '\n'.join(frames) + '\n'
            assert result.returncode == 1 and result.stdout == stdout and result.stderr == expected, (
                binary.name, result.returncode, result.stdout, result.stderr, expected)
            checks += 1
            return expected

        source = ('int deep(int a):0001 { return 10 / a; }\n'
                  'int mid(int a):0002 { return deep(a - 1) + 1; }\n'
                  'int main(){return mid(1);}\n')
        binary, _ = compile_case('trace', source)
        frames = [frame('deep', 'trace.azs', 1, column(source, 1, '/')),
                  frame('mid', 'trace.azs', 2, column(source, 2, 'deep(')),
                  frame('main', 'trace.azs', 3, column(source, 3, 'mid('))]
        debug_message = check(binary, 'Division by zero', frames)
        plain, _ = compile_case('stripped', source, debug=False)
        check(plain, 'Division by zero', ['  at function 0x0fff0001',
                                        '  at function 0x0fff0002',
                                        '  at function 0x0fff0000'])

        overflow = 'int main(){return 2147483647 + 1;}\n'
        binary, _ = compile_case('overflow', overflow)
        check(binary, 'Integer overflow', [frame('main', 'overflow.azs', 1, column(overflow, 1, '+'))])

        class_source = ('class Box{int n;Box(int value){n=value;}int get(){return 10 / n;}}\n'
                        'int main(){Box box(0);return box.get();}\n')
        binary, _ = compile_case('class', class_source)
        check(binary, 'Division by zero', [frame('Box::get', 'class.azs', 1, column(class_source, 1, '/')),
                                           frame('main', 'class.azs', 2, column(class_source, 2, 'get('))])

        generic = ('class P{int x;}\n'
                   'void kill(P * p){delete p;}\n'
                   '<T extends P*> T dead(T p){kill(p);return p;}\n'
                   'int main(){P * p=new P();dead(p);return 0;}\n')
        binary, _ = compile_case('generic', generic)
        check(binary, 'Object return requires a live allocation', [
            frame('dead', 'generic.azs', 3, column(generic, 3, 'return ')),
            frame('main', 'generic.azs', 4, column(generic, 4, 'dead('))])

        cleanup = ('class Bad {int n; ~Bad(){int ignored=2147483647 + 1;}}\n'
                   'int fail(){Bad value;return 1 / 0;}\n'
                   'int main(){return fail();}\n')
        binary, _ = compile_case('cleanup', cleanup)
        check(binary, 'Division by zero', [frame('fail', 'cleanup.azs', 2, column(cleanup, 2, '/')),
                                           frame('main', 'cleanup.azs', 3, column(cleanup, 3, 'fail('))])

        for hook, name, stdout in [('__script_onload', 'onload', ''),
                                   ('__script_pre_destroy', 'destroy', '0\n')]:
            lifecycle = f'void {hook}(){{int ignored=1 / 0;}}\nint main(){{return 0;}}\n'
            binary, _ = compile_case(name, lifecycle)
            check(binary, 'Division by zero', [frame(hook, name + '.azs', 1,
                                                     column(lifecycle, 1, '/'))], stdout=stdout)

        included = work / 'inc'
        included.mkdir()
        included_source = 'int included(){\n  return 1 / 0;\n}\n'
        (included / 'helper.azs').write_text(included_source, encoding='utf-8')
        caller = '#include "inc/helper.azs"\nint main(){return included();}\n'
        binary, _ = compile_case('included', caller)
        check(binary, 'Division by zero', [frame('included', 'inc/helper.azs', 2,
                                                column(included_source, 2, '/')),
                                           frame('main', 'included.azs', 2, column(caller, 2, 'included('))])

        libsource = '#namespace_hint TRACE_LIBRARY\nint library_fail():0002{return 1 / 0;}\n'
        library, _ = compile_case('library', libsource)
        client = ('#assume_hint TRACE_LIBRARY 5151\n'
                  'extern int library_fail():51510002;\n'
                  'int main(){return library_fail();}\n')
        binary, _ = compile_case('client', client)
        check(binary, 'Division by zero', [frame('library_fail', 'library.azs', 2,
                                                column(libsource, 2, '/')),
                                           frame('main', 'client.azs', 3, column(client, 3, 'library_fail('))],
              insert=[library])

        # A supplementary character before an operator counts as two UTF-16 units.
        unicode_source = 'int main(){string text="😀";return 1 / 0;}\n'
        unicode_binary, _ = compile_case('中文😀', unicode_source)
        unicode_message = check(unicode_binary, 'Division by zero', [
            frame('main', '中文😀.azs', 1, column(unicode_source, 1, '/'))])

        for name, text, message in [
                ('steps', 'void main(){while(true){}}\n', 'Script execution step limit exceeded'),
                ('recursion', 'int recurse(){return recurse();}\nint main(){return recurse();}\n',
                 'Script call depth limit exceeded')]:
            binary, _ = compile_case(name, text)
            result = run([args.runner, binary])
            assert result.returncode == 1 and result.stdout == '', (result.stdout, result.stderr)
            lines = result.stderr.splitlines()
            assert lines[0] == 'AzScript error: ' + message, result.stderr
            if name == 'steps':
                assert len(lines) == 2 and re.fullmatch(r'  at main \(steps.azs:1:[1-9][0-9]*\)', lines[1]), result.stderr
            else:
                assert len(lines) == 66 and lines[-1].startswith('  ... ') and lines[-1].endswith(' more frames'), result.stderr
                assert all(re.fullmatch(r'  at recurse \(recursion.azs:1:[1-9][0-9]*\)', line)
                           for line in lines[1:65]), result.stderr
            checks += 1

        if args.bridge and args.library:
            java = work / 'TraceHost.java'
            java.write_text('''
import azertia.AbdInvoker;
import java.io.File;
public class TraceHost {
    public static void main(String[] args) throws Exception {
        System.setProperty("azertia.native.library", args[0]);
        boolean caught = false;
        try {
            AbdInvoker.loadScript(new File(args[1]));
            AbdInvoker.flush();
            AbdInvoker.invoke(0x0fff0000);
        } catch (IllegalStateException error) {
            caught = true;
            if (!error.getMessage().equals(args[2])) throw new AssertionError(error.getMessage());
        } finally {
            AbdInvoker.close();
        }
        if (!caught) throw new AssertionError("Expected a script failure");
    }
}
''', encoding='utf-8')
            compiled = run(['javac', '--release', '17', '-cp', args.bridge, '-d', work, java])
            assert compiled.returncode == 0, (compiled.stdout, compiled.stderr)
            for binary, message in [(work / 'trace.exec.abd', debug_message),
                                    (unicode_binary, unicode_message)]:
                result = run(['java', '-Xcheck:jni', '-cp', str(work) + os.pathsep + str(args.bridge),
                              'TraceHost', args.library, binary,
                              message.removeprefix('AzScript error: ').removesuffix('\n')])
                assert result.returncode == 0 and result.stdout == '' and result.stderr == '', (
                    result.stdout, result.stderr)
                checks += 1

    print(f'Runtime trace end-to-end checks passed: {checks} (AST/ABD identity, cleanup, lifecycle, modules and UTF-8).')


if __name__ == '__main__':
    main()
