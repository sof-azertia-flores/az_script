#!/usr/bin/env python3
"""Compile real v10 modules into verified VM programs; this does not execute the VM.

The host uses the existing loader/flush as the validation authority, then decodes,
relocates, lowers and verifies the new program. Silent lifecycle hooks therefore
still run in the existing interpreter. VM execution equivalence is a later gate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STDLIB = ROOT / 'compiler/stdlib'

# These are the shared generic/virtual examples in generics_end_to_end.py and
# virtual_methods_end_to_end.py, with silent hooks and per-module global slots.
GENERIC_HEADER = '''#assume_hint VM_GENERIC_BOX 2345
class Box<T>{T value;extern Box():23450002;extern T get():23450003;
    extern ~Box():23450004;}
extern <U> U echo(U):23450005;
'''
GENERIC_LIBRARY = '''#namespace_hint VM_GENERIC_BOX
#include "box.include.azs"
int library_seed=3;
Box<T>::Box():0002{}T Box<T>::get():0003{return value;}
Box<T>::~Box():0004{T copy=echo<T>(value);}
<V> V echo(V value):0005{return value;}
void __script_onload(){library_seed=4;}
void __script_pre_destroy(){library_seed=0;}
'''
GENERIC_CLIENT = '''#include "box.include.azs"
int client_seed=5;
void __script_onload(){client_seed=6;}
void __script_pre_destroy(){client_seed=0;}
int main(){Box<int> n;n.value=4;Box<string>* text=new Box<string>();
    text.value="library";string copied=echo(text.get());int result=n.get();
    delete text;return result+client_seed;}
'''
VIRTUAL_HEADER = '''#assume_hint VM_VIRTUAL_BASE 2345
class Base<T>{T field;extern Base():23450002;extern virtual T value():23450003;
    extern T through():23450004;extern ~Base():23450005;}
'''
VIRTUAL_LIBRARY = '''#namespace_hint VM_VIRTUAL_BASE
#include "virtual.include.azs"
int library_seed=7;
Base<T>::Base():0002{}T Base<T>::value():0003{return field;}
T Base<T>::through():0004{return value();}Base<T>::~Base():0005{}
void __script_onload(){library_seed=8;}
void __script_pre_destroy(){library_seed=0;}
'''
VIRTUAL_CLIENT = '''#include "virtual.include.azs"
class Child:Base<int>{override int value(){return field+100;}}
int client_seed=9;
void __script_onload(){client_seed=10;}
void __script_pre_destroy(){client_seed=0;}
int main(){Base<int>* p=new Child();p.field=7;int result=p.through();delete p;return result;}
'''
VECTOR_CLIENT = '''#include "containers.include.azs"
class Base{int field=7;virtual int value(){return field;}Base* self(){return this;}}
class Child:Base{override int value(){return Base::value()+100;}}
int indirect(Base* p){return p.value();}
int client_seed=11;
void __script_onload(){client_seed=12;}
void __script_pre_destroy(){client_seed=0;}
int main(){Vector<Child> values;values.reserve(3);values.resize(2);
    int result=indirect(values.at(0).self());Vector<Child> copy=values;
    values.clear();copy.reserve(5);return result+indirect(copy.at(1).self());}
'''


def command(argv):
    result = subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True,
                            text=True, timeout=90)
    if result.returncode or result.stderr:
        raise AssertionError((list(map(str, argv)), result.returncode, result.stdout, result.stderr))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--host', required=True, type=Path, help='vm-loader-host executable')
    parser.add_argument('--report', type=Path, help='Save the lower/verify evidence as JSON')
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    records = []
    with tempfile.TemporaryDirectory(prefix='vm-program-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        (work / 'box.include.azs').write_text(GENERIC_HEADER, encoding='utf-8')
        (work / 'virtual.include.azs').write_text(VIRTUAL_HEADER, encoding='utf-8')

        def compile_case(name, source):
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            abd, ast, inspection, rebuilt = (work / (name + suffix) for suffix in
                                            ('.abd', '.ast.json', '.exec.json', '.rebuilt.abd'))
            command(compiler + ['compile', path, '-g', '-I', STDLIB, '-o', abd,
                                '--ast', ast, '--exec-json', inspection])
            command(compiler + ['compile-json', ast, '-g', '-o', rebuilt])
            assert abd.read_bytes() == rebuilt.read_bytes(), (name, 'source/AST/ABD identity')
            executable = json.loads(inspection.read_text(encoding='utf-8'))
            assert executable['exec-version'] == 10 and executable['debug-files'], name
            assert executable['f'] and all('debug' in item for item in executable['f']), name
            return abd, executable

        cases = (
            ('hint-generic', GENERIC_LIBRARY, GENERIC_CLIENT),
            ('hint-virtual', VIRTUAL_LIBRARY, VIRTUAL_CLIENT),
            ('stdlib-vector-virtual', (STDLIB / 'containers.azs').read_text(encoding='utf-8'), VECTOR_CLIENT),
        )
        for name, library_source, client_source in cases:
            library = compile_case(name + '-library', library_source)
            client = compile_case(name + '-client', client_source)
            artifacts = [library, client]
            expected_functions = sum(len(item[1]['f']) for item in artifacts)
            expected_public = sum(function['id'] not in (0, 1)
                                  for item in artifacts for function in item[1]['f'])
            previous = None
            for order, ordered in (('client-first', [client, library]), ('library-first', [library, client])):
                result = command([args.host.resolve(), *(item[0] for item in ordered)])
                counts = json.loads(result.stdout)
                assert counts['modules'] == 2, (name, order, counts)
                assert counts['functions'] == expected_functions, (name, order, counts)
                assert counts['public_functions'] == expected_public, (name, order, counts)
                assert counts['instructions'] > expected_functions, (name, order, counts)
                if previous is not None:
                    assert counts == previous, (name, 'load order changes program counts', counts, previous)
                previous = counts
                records.append({'case': name, 'order': order, 'stage': 'lower-and-verify',
                                'vm_executed': False, 'counts': counts,
                                'module_sha256': [hashlib.sha256(item[0].read_bytes()).hexdigest() for item in ordered]})
    report = {'stage': 'lower-and-verify', 'vm_executed': False,
              'source_ast_abd_roundtrips': 6, 'independent_host_processes': len(records), 'cases': records}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('VM program bridge: 6 debug source/AST/ABD roundtrips and 6 multi-module lower/verify checks passed; VM execution not tested.')


if __name__ == '__main__':
    main()
