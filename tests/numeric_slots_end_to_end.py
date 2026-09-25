#!/usr/bin/env python3
"""Compile independent modules and verify numeric slots and insertion offsets."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='numeric-modules-', dir=ROOT / 'build') as temp:
        work = Path(temp)
        compiler = ['java', '-cp', args.classpath, 'azertia.Main']

        def run(command):
            result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=30)
            assert result.returncode == 0, (command, result.stdout, result.stderr)
            return result.stdout.splitlines()

        def compile_module(name, source, global_count):
            src = work / (name + '.azs')
            src.write_text(source, encoding='utf-8')
            abd, ast, executable = (work / (name + suffix) for suffix in ('.abd', '.ast.json', '.exec.json'))
            run(compiler + ['compile', src, '-o', abd, '--ast', ast, '--exec-json', executable])
            tree = json.loads(ast.read_text(encoding='utf-8'))
            code = json.loads(executable.read_text(encoding='utf-8'))
            assert code['exec-version'] == 6 and code['gvs'] == global_count
            found_globals = set()
            for function in code['f']:
                assert function['local-count'] >= 0
                frame_size = function['param-count'] + function['local-count']

                def inspect(value):
                    if isinstance(value, dict):
                        if value.get('t') == 0 and value.get('c') in (3, 4, 5):
                            slot = value['v']
                            assert type(slot) is int, value
                            if slot < 0:
                                assert value['c'] != 4 and -slot <= global_count, value
                                found_globals.add(slot)
                            else:
                                assert slot < frame_size, (value, function)
                        for item in value.values():
                            inspect(item)
                    elif isinstance(value, list):
                        for item in value:
                            inspect(item)

                inspect(function['script'])
            assert found_globals == set(range(-global_count, 0))
            restored = work / (name + '.roundtrip.abd')
            run(compiler + ['compile-json', ast, '-o', restored])
            assert restored.read_bytes() == abd.read_bytes(), name
            return abd, {key: int(value, 16) for key, value in tree['abstract'].items()}, code

        base, a, _ = compile_module('base', '''#namespace 120
            #gvar counter
            void __script_onload(){counter=10;}
            int base_value(){return counter;}
            void __script_pre_destroy(){print("base:"+counter);}
        ''', 1)
        second, b, bcode = compile_module('second', '''#namespace 121
            #gvar counter
            #gvar other
            class Box {int value;Box(int n){value=n;}~Box(){counter+=value;}}
            void __script_onload(){counter=20;other=3;}
            int tick(int n){if(n<=0){return counter;}Box b(n);return tick(n-1);}
            int second_value(){return counter*100+other;}
            void __script_pre_destroy(){print("second:"+counter);}
        ''', 2)
        third, c, _ = compile_module('third', '''#namespace 122
            #gvar counter
            void __script_onload(){counter=30;}
            int change(int value){var before=counter;{var value=value+before;counter=value;}return counter;}
            int loop(){int total=0;int i=0;while(i<3){int temp=i;total+=temp;i+=1;}return total;}
            void __script_pre_destroy(){print("third:"+counter);}
        ''', 1)
        command = [args.host, base, 'flush', 'span', a['base_value'], 'call', a['base_value'], 0,
                   'insert', second, 'flush', 'span', b['second_value'], 'call', b['second_value'], 0,
                   'call', b['tick'], 1, 3, 'call', b['second_value'], 0,
                   'call', a['base_value'], 0, 'insert', third, 'flush', 'span', c['change'],
                   'call', c['change'], 1, 7, 'call', c['loop'], 0,
                   'call', a['base_value'], 0, 'call', b['second_value'], 0,
                   'globals', 'reject-insert', second, 'globals', 'call', c['change'], 1, 1]
        lines = run(command)
        expected = ['0:1', '10', '1:2', '2003', '20', '2603', '10', '3:1', '37', '3',
                    '10', '2603', '4', 'rejected', '4', '38']
        assert lines[:len(expected)] == expected, lines
        # All shutdown hooks keep the offset of the module that defined them.
        assert sorted(lines[len(expected):]) == ['base:10', 'second:26', 'third:38'], lines

        # Hidden class factory functions receive the same base as ordinary methods.
        factory_ids = [function['id'] for function in bcode['f']
                       if function['id'] not in b.values()]
        assert len(factory_ids) == 2
        lines = run([args.host, base, 'insert', second, 'flush',
                     *[argument for ident in factory_ids for argument in ('span', ident)]])
        assert lines[:2] == ['1:2', '1:2'], lines
        print('Numeric slots: 3 source/AST/ABD layouts and roundtrips; '
              'independent global offsets, insertion rejection, recursion, class cleanup, '
              'loop/shadow scopes, lifecycle hooks and hidden factories passed.')


if __name__ == '__main__':
    main()
