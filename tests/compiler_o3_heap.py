#!/usr/bin/env python3
"""O3 field replacement preserves externally observable allocation/lifecycle effects."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CELL = 'class A{int x;A(int n){x=n;}int get(){return x;}~A(){}}'
ADDRESS = 'class B{int x;B *addr(){return this;}}'
CASES = {
    'auto-tail': CELL + 'int main(){A *a(3);a.x=4;return a.get();}',
    'auto-error-tail': CELL + 'int main(){A *a(3);int zero=0;return a.x/zero;}',
    'literal-tail': CELL + 'int main(){A a(3);a.x=4;return a.get();}',
    'literal-copy-tail': CELL + 'int main(){A a(3);a.x=9;A b=a;A c=b;a.x=2;b.x=7;return a.x+b.x+c.x;}',
    'literal-copy-next-address': CELL + ADDRESS +
        'int f(){A a(3);a.x=7;A b=a;return b.x;}address main(){int n=f();B b;return address(b.addr());}',
    'auto-next-address': CELL + ADDRESS +
        'int f(){A *a(3);return a.x;}address main(){int n=f();B b;return address(b.addr());}',
    'auto-first-fit-hole': CELL + 'int f(){A *a(3);return a.x;}address main(){'
        'address left=alloc(2);address hole=alloc(2);address right=alloc(2);mem_free(hole);'
        'int n=f();address next=alloc(2);mem_free(next);mem_free(left);mem_free(right);return next;}',
    'loop-copy-cleanup': CELL + 'int main(){int total=0;for(int i=0;i<5;i++){A a(i);A b=a;'
        'if(i==1){continue;}if(i==4){break;}total=total+b.x;}return total;}',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--jit-modes', default='')
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith('AZSCRIPT_JIT') and key not in
           ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND')}

    def command(argv, mode='off'):
        result = subprocess.run(list(map(str, argv)), cwd=ROOT, env=env | {'AZSCRIPT_JIT': mode},
                                capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, (argv, result.stdout, result.stderr)
        return result.stdout

    variants = [('tree', 'off'), ('vm', 'off')]
    variants += [('vm', mode) for mode in args.jit_modes.split(',') if mode and mode != 'off']
    checks = 0
    for name, source in CASES.items():
        path = args.work / (name + '.azs')
        path.write_text(source, encoding='utf-8')
        for debug in (False, True):
            suffix = '.debug' if debug else ''
            binaries = []
            for level in ('O0', 'O3'):
                binary = args.work / f'{name}.{level}{suffix}.abd'
                command(['java', '-cp', args.classpath, 'azertia.Main', 'compile', path,
                         '-' + level, *(['-g'] if debug else []), '-o', binary])
                binaries.append(binary)
            for tail in (1, 100):
                expected = json.loads(command([args.host, binaries[0], 'tree', tail]))
                assert expected['before_length'] == tail, expected
                if name == 'auto-error-tail':
                    assert 'Division by zero' in expected['error'], expected
                else:
                    assert expected['error'] is None, expected
                for binary in binaries:
                    for engine, mode in variants:
                        actual = json.loads(command([args.host, binary, engine, tail], mode))
                        assert actual == expected, (name, debug, tail, binary, engine, mode, expected, actual)
                        checks += 1
    report = {'cases': len(CASES), 'checks': checks, 'heap_lengths': [1, 100],
              'debug': [False, True], 'variants': variants, 'status': 'passed'}
    (args.work / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'O3 heap observation: {len(CASES)} cases, {checks} comparisons passed')


if __name__ == '__main__':
    main()
