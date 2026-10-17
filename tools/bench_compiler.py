#!/usr/bin/env python3
"""Times the compiler on generated large programs; an opt-in benchmark, not part of the validation run.
Run tools/build_and_test.py first: the benchmark uses the compiler classpath it writes. Each program is
compiled by a fresh `azertia.Main compile`, so the times include JVM startup. Inputs and outputs stay
under build/bench. --scale multiplies every program size, e.g. 0.125 for a quick run.
"""
import argparse
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'


def locals_program(n):
    body = ''.join(f'  int v{i} = acc + {i % 7}; acc = v{i} % 1000;\n' for i in range(n))
    return 'int main() {\n  int acc = 0;\n' + body + '  return acc;\n}\n'


def statements_program(n):
    body = ''.join(f'  acc = (acc + {i % 7}) % 1000;\n' for i in range(n))
    return 'int main() {\n  int acc = 0;\n' + body + '  return acc;\n}\n'


def functions_program(n):
    lines = ['int f0(int a, int b) { return a + b; }\n']
    lines += [f'int f{i}(int a, int b) {{ int c = a + b * {i % 7}; if (c > 10) {{ c = c - 1; }} '
              f'while (c > 100) {{ c = c / 2; }} return c + f{i - 1}(a, b); }}\n' for i in range(1, n)]
    return ''.join(lines) + f'int main() {{ return f{n - 1}(1, 2) % 100; }}\n'


def globals_program(n):
    return (''.join(f'#gvar g{i}\n' for i in range(n)) + 'int main() {\n'
            + ''.join(f'  g{i} = {i % 7};\n' for i in range(n)) + '  return 0;\n}\n')


def classes_program(n):
    declarations = ''.join(f'class C{i} {{ int x; C{i}(int v) {{ x = v; }} int get() {{ return x; }} }}\n' for i in range(n))
    uses = ''.join(f'  C{i} c{i}({i % 7}); total = total + c{i}.get();\n' for i in range(n))
    return declarations + 'int main() {\n  int total = 0;\n' + uses + '  return total;\n}\n'


def fields_program(n):
    fields = ''.join(f'  int f{i};\n' for i in range(n))
    uses = ''.join(f'  w.f{i} = {i % 7}; total = total + w.f{i};\n' for i in range(n))
    return 'class Wide {\n' + fields + '  Wide() { }\n}\nint main() {\n  Wide w;\n  int total = 0;\n' + uses + '  return total;\n}\n'


def externs_program(n):
    declarations = ''.join(f'extern int host{i}(int):1234{i:04x};\n' for i in range(n))
    callers = ''.join(f'int use{i}(int v) {{ return host{i}(v); }}\n' for i in range(n))
    uses = ''.join(f'  total = total + use{i}({i % 7});\n' for i in range(n))
    return declarations + callers + 'int main() {\n  int total = 0;\n' + uses + '  return total;\n}\n'


def parameters_program(n):
    return 'int f(' + ', '.join(f'int p{i}' for i in range(n)) + ') { return p0; }\nint main() { return 0; }\n'


PROGRAMS = [
    ('locals', locals_program, (2_000, 8_000, 32_000)),
    ('statements', statements_program, (5_000, 20_000)),
    ('functions', functions_program, (1_000, 5_000, 20_000)),
    ('globals', globals_program, (2_000, 16_000)),
    ('classes', classes_program, (250, 2_000)),
    ('fields', fields_program, (1_000, 8_000)),
    ('externs', externs_program, (1_000, 8_000)),
    ('parameters', parameters_program, (8_000, 64_000)),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--scale', type=float, default=1.0, help='multiply every program size (default 1)')
    parser.add_argument('--java', default='java', help='the java launcher (default: java on PATH)')
    args = parser.parse_args()
    if args.scale <= 0:
        parser.error('--scale must be positive')
    classpath_file = BUILD / 'java/classpath.txt'
    if not classpath_file.is_file():
        sys.exit('Missing build/java/classpath.txt; run tools/build_and_test.py first')
    classpath = classpath_file.read_text(encoding='utf-8').strip()
    bench = BUILD / 'bench'
    bench.mkdir(parents=True, exist_ok=True)
    print(f'{"program":<12}{"size":>8}{"seconds":>10}')
    for name, generate, sizes in PROGRAMS:
        for size in sizes:
            size = max(1, round(size * args.scale))
            source = bench / f'{name}{size}.azs'
            source.write_text(generate(size), encoding='utf-8')
            start = time.perf_counter()
            result = subprocess.run([args.java, '-cp', classpath, 'azertia.Main', 'compile', source,
                                     '-o', bench / f'{name}{size}.exec.abd'], capture_output=True, text=True)
            elapsed = time.perf_counter() - start
            if result.returncode != 0:
                sys.exit(f'{source.name} failed to compile:\n{result.stderr}')
            print(f'{name:<12}{size:>8}{elapsed:>10.2f}', flush=True)


if __name__ == '__main__':
    main()
