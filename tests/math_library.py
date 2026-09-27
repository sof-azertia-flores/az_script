#!/usr/bin/env python3
"""Exercise the pure AzScript math library through compiler and native runtime."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HINT = 'AZSCRIPT_MATH'
ALIAS = 0x4d41
# These public low IDs are the ABI shared by math.include.azs and math.azs.
PUBLIC_NAMES = '''pi tau e ln2 epsilon abs abs_int sign min max clamp min_int
max_int clamp_int square cube mean lerp to_radians to_degrees is_close
approximately_equal pow int_pow sqrt trunc floor ceil round fraction gcd
positive_mod floor_div ceil_div is_even is_odd lcm factorial fibonacci hypot
sin cos tan exp log log2 log10 log_base exp2 sinh cosh tanh'''.split()
PUBLIC_IDS = {f'math_{name}': index for index, name in enumerate(PUBLIC_NAMES, 2)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    if (args.bridge is None) != (args.library is None):
        parser.error('--bridge and --library must be provided together')

    runner = args.runner.resolve()
    library = (ROOT / 'compiler/stdlib/math.azs').resolve()
    header = (ROOT / 'compiler/stdlib/math.include.azs').resolve()
    example = ROOT / 'compiler/examples/math-regressions.azs'
    regression_checks = example.read_text(encoding='utf-8').count(
        'failures += math_test_check('
    )
    assert regression_checks == 93

    def compile_source(source, output, inspect=False):
        command = ['java', '-cp', args.classpath, 'azertia.Main', 'compile', source,
                   '-o', output]
        if inspect:
            ast = output.with_suffix('.ast.json')
            executable = output.with_suffix('.exec.json')
            command += ['--ast', ast, '--exec-json', executable]
        result = subprocess.run(
            command,
            cwd=ROOT, capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)
        if inspect:
            roundtrip = output.with_suffix('.roundtrip.abd')
            result = subprocess.run(
                ['java', '-cp', args.classpath, 'azertia.Main', 'compile-json',
                 ast, '-o', roundtrip],
                cwd=ROOT, capture_output=True, text=True, timeout=30,
            )
            assert result.returncode == 0, result.stdout + result.stderr
            assert output.read_bytes() == roundtrip.read_bytes(), source
            return json.loads(ast.read_text()), json.loads(executable.read_text())

    def execute(binary, timeout=30, modules=None, entry=None):
        if modules is None:
            modules = [math_binary]
        return subprocess.run(
            [runner, binary, *([] if entry is None else [entry]),
             *[part for module in modules for part in ('--insert', module)]],
            cwd=ROOT, capture_output=True, text=True,
            timeout=timeout,
        )

    comparisons = []

    def literal(value):
        return repr(float(value))

    def close(expression, expected, relative='1e-11', absolute='1e-12'):
        comparisons.append(
            f'math_is_close({expression},{literal(expected)},'
            f'{relative},{absolute})'
        )

    for value in [5e-324, 1e-300, 0.25, 0.5, 1, 2, 10, 1e100, 1e300]:
        close(f'math_sqrt({literal(value)})', math.sqrt(value), '1e-12', '0.0')

    for value in [-1000, -100, -10, -math.pi, -1, -0.1, 0,
                  0.1, 1, math.pi, 10, 100, 1000]:
        close(f'math_sin({literal(value)})', math.sin(value), '1e-10', '1e-10')
        close(f'math_cos({literal(value)})', math.cos(value), '1e-10', '1e-10')
    for value, tolerance in [
        (-1e9, '1e-6'), (1e9, '1e-6'),
        (-1e11, '5e-6'), (1e11, '5e-6'),
        (-1e12, '5e-5'), (1e12, '5e-5'),
    ]:
        close(f'math_sin({literal(value)})', math.sin(value), tolerance, tolerance)
        close(f'math_cos({literal(value)})', math.cos(value), tolerance, tolerance)

    for value in [-745, -744, -700, -100, -10, -1, -0.1, 0,
                  0.1, 1, 10, 100, 700, 709, 709.782712893384]:
        close(f'math_exp({literal(value)})', math.exp(value), '1e-11', '0.0')
    for value in [5e-324, 1e-300, 1e-100, 0.1, 0.5, 1, 2, 10, 1e100, 1e300]:
        close(f'math_log({literal(value)})', math.log(value), '1e-11', '1e-11')

    for first, second in [
        (3, 4), (1e-300, 1e-300), (3e200, 4e200), (1e308, 1e-308),
    ]:
        close(
            f'math_hypot({literal(first)},{literal(second)})',
            math.hypot(first, second), '1e-12', '0.0',
        )

    close('math_to_degrees(1e306)', math.degrees(1e306), '1e-12', '0.0')
    close('math_to_radians(1e308)', math.radians(1e308), '1e-12', '0.0')
    comparisons.append('math_to_degrees(5e-324)==2.8e-322')
    comparisons.append('math_to_radians(2.47e-322)==5e-324')
    close(
        'math_lerp(1e308,1e-308,0.9999999999999999)',
        1e308 * (1.0 - 0.9999999999999999)
        + 1e-308 * 0.9999999999999999,
        '1e-12', '0.0',
    )

    for value in [-710, -10, -1, -0.1, -1e-10, 0,
                  1e-10, 0.1, 1, 10, 710]:
        close(f'math_sinh({literal(value)})', math.sinh(value), '1e-10', '1e-12')
        close(f'math_cosh({literal(value)})', math.cosh(value), '1e-10', '1e-12')
        close(f'math_tanh({literal(value)})', math.tanh(value), '1e-10', '1e-12')

    invalid_calls = [
        ('math_sqrt(-1.0)', 'Division by zero'),
        ('math_log(0.0)', 'Division by zero'),
        ('math_pow(0.0,-1)', 'Division by zero'),
        ('math_clamp(1.0,2.0,0.0)', 'Division by zero'),
        ('math_is_close(1.0,1.0,-1.0,0.0)', 'Division by zero'),
        ('math_abs_int(-2147483648)', 'Division by zero'),
        ('math_int_pow(2,31)', 'Integer overflow'),
        ('math_int_pow(46341,2)', 'Integer overflow'),
        ('math_factorial(13)', 'Division by zero'),
        ('math_fibonacci(47)', 'Division by zero'),
        ('math_gcd(-2147483648,1)', 'Division by zero'),
        ('math_lcm(46341,46342)', 'Integer overflow'),
        ('math_positive_mod(1,0)', 'Division by zero'),
        ('math_floor_div(-2147483648,-1)', 'Integer overflow'),
        ('math_floor(2147483648.0)', 'Division by zero'),
        ('math_ceil(-2147483649.0)', 'Division by zero'),
        ('math_round(2147483647.5)', 'Division by zero'),
        ('math_tan(math_pi()/2.0)', 'Division by zero'),
        ('math_sin(10000000000000.0)', 'Division by zero'),
        ('math_exp(710.0)', 'Division by zero'),
        ('math_exp2(1024.0)', 'Division by zero'),
        ('math_log_base(8.0,1.0)', 'Division by zero'),
        ('math_sinh(711.0)', 'Division by zero'),
    ]
    invalid_types = [
        'math_factorial(3.5)',
        'math_sqrt(4)',
        'math_sqrt(4.0f)',
        'math_pow(2.0,3.0)',
        'math_gcd(4.0,2)',
        'math_sqrt(dynamicValue)',
    ]

    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='math-', dir=ROOT / 'build') as temp:
        work = Path(temp)

        math_binary = work / 'math.abd'
        math_ast, math_exec = compile_source(library, math_binary, inspect=True)
        assert math_exec['exec-version'] == 8
        assert math_exec['namespace-hint'] == HINT
        assert math_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        assert all(function['id'] >> 16 == 0 for function in math_exec['f'])
        definitions = {
            function['metadata']['name']: function
            for group in math_ast['body'].values() for function in group.values()
        }
        header_ids = {
            name: int(value, 16)
            for name, value in re.findall(
                r'extern\s+\w+\s+(math_\w+)\([^)]*\)\s*:\s*(?:0x)?([0-9a-fA-F]+)',
                header.read_text(),
            )
        }
        assert header_ids == {name: ALIAS << 16 | local for name, local in PUBLIC_IDS.items()}
        for name, local in PUBLIC_IDS.items():
            assert definitions[name]['metadata']['position'] == local, name
            assert int(math_ast['abstract'][name], 16) == (ALIAS << 16 | local), name
            signature = next(item for item in math_exec['f'] if item['id'] == local)
            assert 6 not in signature['param-types'], name
        assert len(definitions) > len(PUBLIC_IDS)  # Private helpers remain inside the library.

        regression_binary = work / 'regressions.abd'
        regression_ast, regression_exec = compile_source(example, regression_binary, inspect=True)
        assert regression_exec['namespace-hint'] == ''
        assert regression_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        assert {
            function['metadata']['name']
            for group in regression_ast['body'].values() for function in group.values()
        } == {'main', 'math_test_check'}
        missing = execute(regression_binary, modules=[])
        assert missing.returncode != 0 and HINT in missing.stderr, (missing.stdout, missing.stderr)
        result = execute(regression_binary)
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))
        occupied_source = work / 'other-library.azs'
        occupied_source.write_text(
            '#namespace_hint OTHER_LIBRARY\nint value():0002{return 1;}\n',
            encoding='utf-8',
        )
        occupied_binary = work / 'other-library.abd'
        compile_source(occupied_source, occupied_binary)
        for modules in ([occupied_binary, math_binary], [math_binary, occupied_binary]):
            result = execute(regression_binary, modules=modules)
            assert result.returncode == 0 and result.stdout == '0\n', (result.stdout, result.stderr)

        second_source = work / 'second-consumer.azs'
        second_source.write_text(
            '#namespace 1234\n'
            f'#include "{header.as_posix()}"\n'
            'double second_value():0002{\n'
            'int input=9;double value=input*1.0;int count=9;\n'
            'if(math_sqrt(count*1.0)!=3.0){return -1.0;}\n'
            'return math_sqrt(value)+math_pow(2.0,3);}\n',
            encoding='utf-8',
        )
        second_binary = work / 'second-consumer.abd'
        _, second_exec = compile_source(second_source, second_binary, inspect=True)
        assert len(second_exec['f']) == 1 and second_exec['f'][0]['id'] == 0x12340002
        assert second_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        for modules in ([second_binary, math_binary], [math_binary, second_binary]):
            result = execute(regression_binary, modules=modules)
            assert result.returncode == 0 and result.stdout == '0\n', (result.stdout, result.stderr)
            result = execute(regression_binary, modules=modules, entry='0x12340002')
            assert result.returncode == 0 and result.stdout == '11\n', (result.stdout, result.stderr)

        incompatible_source = work / 'incompatible-import.azs'
        incompatible_source.write_text(
            '#assume_hint AZSCRIPT_MATH 4d41\n'
            'extern int math_sqrt(int):4d41001a;\n'
            'int main(){return math_sqrt(4);}\n',
            encoding='utf-8',
        )
        incompatible_binary = work / 'incompatible-import.abd'
        compile_source(incompatible_source, incompatible_binary)
        result = execute(incompatible_binary)
        assert result.returncode != 0 and 'signature' in result.stderr.lower(), (result.stdout, result.stderr)

        signed_zero_source = work / 'signed-zero.azs'
        signed_zero_source.write_text(
            f'#include "{header.as_posix()}"\n'
            'double main(){return math_abs(-0.0);}\n',
            encoding='utf-8',
        )
        signed_zero_binary = work / 'signed-zero.abd'
        compile_source(signed_zero_source, signed_zero_binary)
        result = execute(signed_zero_binary)
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))

        library_path = header.as_posix()
        differential_source = work / 'differential.azs'
        differential_lines = [
            f'#include "{library_path}"',
            f'#include "{library_path}"',
            'int math_test_check(value){if(value){return 0;}return 1;}',
            'int main(){var failures=0;',
        ]
        differential_lines.extend(
            f'failures+=math_test_check({condition});'
            for condition in comparisons
        )
        differential_lines.extend(['return failures;', '}'])
        differential_source.write_text(
            '\n'.join(differential_lines), encoding='utf-8'
        )
        differential_binary = work / 'differential.abd'
        _, differential_exec = compile_source(differential_source, differential_binary, inspect=True)
        assert differential_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        assert len(differential_exec['f']) == 2
        result = execute(differential_binary, timeout=60)
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))

        invalid_source = work / 'invalid.azs'
        invalid_binary = work / 'invalid.abd'
        for call, expected_error in invalid_calls:
            invalid_source.write_text(
                f'#include "{library_path}"\n'
                f'double main(){{{call};return 0.0;}}\n',
                encoding='utf-8',
            )
            compile_source(invalid_source, invalid_binary)
            result = execute(invalid_binary)
            if result.returncode == 0 or expected_error not in result.stderr:
                raise AssertionError((call, result.stdout, result.stderr))

        for call in invalid_types:
            invalid_source.write_text(
                f'#include "{library_path}"\n'
                f'double main(){{var dynamicValue=4.0;{call};return 0.0;}}\n',
                encoding='utf-8',
            )
            result = subprocess.run(
                ['java', '-cp', args.classpath, 'azertia.Main', 'compile',
                 invalid_source, '-o', work / 'invalid-type.abd'],
                cwd=ROOT, capture_output=True, text=True, timeout=30,
            )
            assert result.returncode != 0, call
            assert 'expected' in result.stderr, (call, result.stdout, result.stderr)
            assert ('got' in result.stderr or 'unknown type' in result.stderr), (call, result.stdout, result.stderr)

        if args.library:
            java_source = work / 'MathLibraryBridgeSmoke.java'
            java_source.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class MathLibraryBridgeSmoke {
 private static String secondConsumer;
 private static void mount(String path, String library, boolean libraryFirst) {
  AbdInvoker.loadScript(new File(libraryFirst ? library : path));
  try {
   AbdInvoker.invoke(0x0fff0000);
   throw new AssertionError("invocation before flush was accepted");
  } catch (RuntimeException expected) { }
  AbdInvoker.insertScript(new File(libraryFirst ? path : library));
  AbdInvoker.insertScript(new File(secondConsumer));
  AbdInvoker.flush();
  AbdInvoker.flush();
  int namespace=AbdInvoker.namespaceForHint("AZSCRIPT_MATH");
  if (namespace<=0 || namespace==0x4d41) throw new AssertionError(namespace);
  int sqrt=(namespace<<16)|0x001a;
  if (!Double.valueOf(2.0).equals(AbdInvoker.invoke(sqrt,4.0)))
   throw new AssertionError("direct library invocation failed");
  if (!Double.valueOf(11.0).equals(AbdInvoker.invoke(0x12340002)))
   throw new AssertionError("second consumer could not share the library");
  for (Object invalid : new Object[]{Integer.valueOf(4),Float.valueOf(4.0f)}) {
   try {
    AbdInvoker.invoke(sqrt,invalid);
    throw new AssertionError("untyped argument was accepted");
   } catch (RuntimeException expected) { }
  }
 }
 private static void invoke(String path, String library, boolean expectError, boolean libraryFirst) {
  mount(path,library,libraryFirst);
  try {
   Object result=AbdInvoker.invoke(0x0fff0000);
   if (expectError) throw new AssertionError("expected arithmetic error");
   if (!Integer.valueOf(0).equals(result)) throw new AssertionError(result);
  } catch (RuntimeException error) {
   if (!expectError) throw error;
   if (error.getMessage()==null || !error.getMessage().contains("Division by zero"))
    throw new AssertionError(error);
  } finally { AbdInvoker.close(); }
 }
 private static void invokePositiveZero(String path, String library) {
  mount(path,library,false);
  try {
   Object result=AbdInvoker.invoke(0x0fff0000);
   if (!(result instanceof Double) ||
       Double.doubleToRawLongBits((Double)result)!=0L)
    throw new AssertionError(result);
  } finally { AbdInvoker.close(); }
 }
 public static void main(String[] args) {
  secondConsumer=args[6];
  invoke(args[0],args[4],false,false);
  invoke(args[1],args[4],false,true);
  invoke(args[2],args[4],true,false);
  invokePositiveZero(args[3],args[4]);
  invoke(args[0],args[4],false,true);
  AbdInvoker.loadScript(new File(args[5]));
  AbdInvoker.insertScript(new File(args[4]));
  try {
   AbdInvoker.flush();
   throw new AssertionError("incompatible library signature was accepted");
  } catch (RuntimeException expected) { }
  finally { AbdInvoker.close(); }
 }
}
''', encoding='utf-8')
            bridge = args.bridge.resolve()
            subprocess.run(
                ['javac', '--release', '17', '-cp', bridge, '-d', work,
                 java_source],
                check=True, timeout=30,
            )
            result = subprocess.run(
                ['java', '-Xcheck:jni',
                 '-Dazertia.native.library=' + str(args.library.resolve()),
                 '-cp', os.pathsep.join([str(work), str(bridge)]),
                 'MathLibraryBridgeSmoke', regression_binary,
                 differential_binary, invalid_binary, signed_zero_binary,
                 math_binary, incompatible_binary, second_binary],
                cwd=ROOT, capture_output=True, text=True, timeout=60,
            )
            if result.returncode != 0 or result.stderr:
                raise AssertionError((result.stdout, result.stderr))

    print(
        'Math library checks passed: '
        f'{regression_checks} regression assertions, '
        f'{len(comparisons)} numerical comparisons, '
        f'{len(invalid_calls)} runtime error cases, '
        f'{len(invalid_types)} type errors, signed zero, independent hint linking'
        + (', JNI execution' if args.library else '')
    )


if __name__ == '__main__':
    main()
