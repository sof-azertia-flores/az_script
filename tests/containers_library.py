#!/usr/bin/env python3
"""Exercise the generic AzScript container library through compiler and runtime."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HINT = 'AZSCRIPT_CONTAINERS'
ALIAS = 0xc071
# Low IDs shared by containers.include.azs and containers.azs.
PUBLIC_IDS = {
    'Node::<ctor>': 0x0002,
    'Node::<dtor>': 0x0003,
    'List::<ctor>': 0x0004,
    'List::<dtor>': 0x0005,
    'List::size': 0x0006,
    'List::empty': 0x0007,
    'List::push_back': 0x0008,
    'List::push_front': 0x0009,
    'List::pop_back': 0x000a,
    'List::pop_front': 0x000b,
    'List::front': 0x000c,
    'List::back': 0x000d,
    'List::at': 0x000e,
    'List::set': 0x000f,
    'List::insert': 0x0010,
    'List::erase': 0x0011,
    'List::clear': 0x0012,
    'List::resize': 0x0013,
    'List::reverse': 0x0014,
    'List::clone': 0x0015,
    'List::swap': 0x0016,
    'Stack::<ctor>': 0x0017,
    'Stack::<dtor>': 0x0018,
    'Stack::push': 0x0019,
    'Stack::pop': 0x001a,
    'Stack::top': 0x001b,
    'Stack::size': 0x001c,
    'Stack::empty': 0x001d,
    'Queue::<ctor>': 0x001e,
    'Queue::<dtor>': 0x001f,
    'Queue::push': 0x0020,
    'Queue::pop': 0x0021,
    'Queue::front': 0x0022,
    'Queue::size': 0x0023,
    'Queue::empty': 0x0024,
    'list_sort_int': 0x0025,
    'list_sort_double': 0x0026,
    'list_sort_address': 0x0027,
    'list_find_int': 0x0028,
    'list_find_double': 0x0029,
    'list_find_string': 0x002a,
    'list_find_bool': 0x002b,
    'list_find_address': 0x002c,
    'list_count_int': 0x002d,
    'list_count_double': 0x002e,
    'list_count_string': 0x002f,
    'list_count_bool': 0x0030,
    'list_count_address': 0x0031,
    'Entry::<ctor>': 0x0038,
    'Entry::<dtor>': 0x0039,
    'Vector::<ctor>': 0x0040,
    'Vector::<dtor>': 0x0041,
    'Vector::size': 0x0042,
    'Vector::empty': 0x0043,
    'Vector::capacity': 0x0044,
    'Vector::reserve': 0x0045,
    'Vector::push_back': 0x0046,
    'Vector::pop_back': 0x0047,
    'Vector::resize': 0x0048,
    'Vector::clear': 0x0049,
    'Vector::at': 0x004a,
    'Vector::set': 0x004b,
    'Vector::front': 0x004c,
    'Vector::back': 0x004d,
    'Vector::insert': 0x004e,
    'Vector::erase': 0x004f,
    'Vector::<op:index-get>': 0x0050,
    'Vector::<op:index-set>': 0x0051,
    'Set::<ctor>': 0x0052,
    'Set::<dtor>': 0x0053,
    'Set::size': 0x0054,
    'Set::empty': 0x0055,
    'Set::insert': 0x0056,
    'Set::erase': 0x0057,
    'Set::contains': 0x0058,
    'Set::at': 0x0059,
    'Set::set_order': 0x005a,
    'Map::<ctor>': 0x005b,
    'Map::<dtor>': 0x005c,
    'Map::size': 0x005d,
    'Map::empty': 0x005e,
    'Map::put': 0x005f,
    'Map::get': 0x0060,
    'Map::contains': 0x0061,
    'Map::erase': 0x0062,
    'Map::set_order': 0x0063,
    'Map::<op:index-get>': 0x0064,
    'Map::<op:index-set>': 0x0065,
}


def expected_hidden(name):
    if name.startswith('list_'):
        return 0
    if name.startswith('Map::') or name.startswith('Entry::'):
        return 2
    return 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    args = parser.parse_args()
    runner = args.runner.resolve()
    library = (ROOT / 'compiler/stdlib/containers.azs').resolve()
    header = (ROOT / 'compiler/stdlib/containers.include.azs').resolve()
    example = ROOT / 'compiler/examples/containers-regressions.azs'
    regression_checks = example.read_text(encoding='utf-8').count('failures += containers_check(')
    assert regression_checks == 35

    def compile_source(source, output, inspect=False):
        command = ['java', '-cp', args.classpath, 'azertia.Main', 'compile', source, '-o', output]
        if inspect:
            command += ['--ast', output.with_suffix('.ast.json'), '--exec-json', output.with_suffix('.exec.json')]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=30)
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)
        if inspect:
            return (json.loads(output.with_suffix('.ast.json').read_text(encoding='utf-8')),
                    json.loads(output.with_suffix('.exec.json').read_text(encoding='utf-8')))
        return None

    def execute(binary, modules):
        return subprocess.run(
            [runner, binary, *[part for module in modules for part in ('--insert', module)]],
            cwd=ROOT, capture_output=True, text=True, timeout=30,
        )

    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='containers-', dir=ROOT / 'build') as temp:
        work = Path(temp)
        library_binary = work / 'containers.abd'
        library_ast, library_exec = compile_source(library, library_binary, inspect=True)
        assert library_exec['exec-version'] == 9
        assert library_exec['namespace-hint'] == HINT
        assert library_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        assert all(function['id'] >> 16 == 0 for function in library_exec['f'])
        definitions = {
            function['metadata']['name']: function
            for group in library_ast['body'].values() for function in group.values()
        }
        header_text = header.read_text(encoding='utf-8')
        for name, local in PUBLIC_IDS.items():
            assert definitions[name]['metadata']['position'] == local, name
            assert int(library_ast['abstract'][name], 16) == (ALIAS << 16 | local), name
            assert f'{ALIAS << 16 | local:08x}' in header_text.lower() or f'{local:04x}' in header_text, name
            signature = next(item for item in library_exec['f'] if item['id'] == local)
            assert signature['hidden-count'] == expected_hidden(name), name
        assert 'containers__bounds' in definitions
        assert definitions['containers__bounds']['metadata']['position'] not in PUBLIC_IDS.values()

        regression_binary = work / 'regressions.abd'
        regression_ast, regression_exec = compile_source(example, regression_binary, inspect=True)
        assert regression_exec['namespace-hint'] == ''
        assert regression_exec['assume-hints'] == [{'hint': HINT, 'namespace': ALIAS}]
        assert {
            function['metadata']['name']
            for group in regression_ast['body'].values() for function in group.values()
        } == {'main', 'containers_check', 'Point::<ctor>'}
        missing = execute(regression_binary, [])
        assert missing.returncode != 0 and HINT in missing.stderr, (missing.stdout, missing.stderr)
        result = execute(regression_binary, [library_binary])
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))

        empty_source = work / 'empty-pop.azs'
        empty_source.write_text(
            f'#include "{header.as_posix()}"\n'
            'void main(){List<int>* xs=new List<int>();xs.pop_back();delete xs;}\n',
            encoding='utf-8',
        )
        empty_binary = work / 'empty-pop.abd'
        compile_source(empty_source, empty_binary)
        result = execute(empty_binary, [library_binary])
        if result.returncode == 0 or 'Division by zero' not in result.stderr:
            raise AssertionError((result.stdout, result.stderr))

        assert definitions['NodeRest::<dtor>']['metadata']['position'] == 0x0037
        assert 'c0710037' not in header_text

        # ids 1 and 2 fail. The tail (id 3) must still be destroyed, then the locals.
        bomb_source = work / 'element-dtor.azs'
        bomb_source.write_text(
            '#gvar armed\n'
            f'#include "{header.as_posix()}"\n'
            'class Bomb {\n'
            '    int id;\n'
            '    ~Bomb() {\n'
            '        if (armed == 0) { return; }\n'
            '        print(id);\n'
            '        if (id == 1 || id == 2) { int broken = 1 / 0; }\n'
            '    }\n'
            '}\n'
            'void main() {\n'
            '    armed = 0;\n'
            '    List<Bomb> * xs = new List<Bomb>();\n'
            '    Bomb a; a.id = 0;\n'
            '    Bomb b; b.id = 1;\n'
            '    Bomb c; c.id = 2;\n'
            '    Bomb d; d.id = 3;\n'
            '    xs.push_back(a);\n'
            '    xs.push_back(b);\n'
            '    xs.push_back(c);\n'
            '    xs.push_back(d);\n'
            '    armed = 1;\n'
            '    delete xs;\n'
            '}\n',
            encoding='utf-8',
        )
        bomb_binary = work / 'element-dtor.abd'
        compile_source(bomb_source, bomb_binary)
        result = execute(bomb_binary, [library_binary])
        if result.returncode == 0 or 'Division by zero' not in result.stderr or result.stdout != '0\n1\n2\n3\n3\n2\n1\n0\n':
            raise AssertionError((result.stdout, result.stderr))

        buffer_example = ROOT / 'compiler/examples/containers-buffer.azs'
        buffer_binary = work / 'buffer.abd'
        compile_source(buffer_example, buffer_binary)
        result = execute(buffer_binary, [library_binary])
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))

        def run_source(name, source):
            path = work / name
            path.write_text(source, encoding='utf-8')
            binary = work / (name + '.abd')
            compile_source(path, binary)
            return execute(binary, [library_binary])

        header_include = f'#include "{header.as_posix()}"\n'
        for name, body in {
            'vector-empty-front.azs': 'void main(){Vector<int>* xs=new Vector<int>();xs.front();delete xs;}\n',
            'vector-empty-pop.azs': 'void main(){Vector<int>* xs=new Vector<int>();xs.pop_back();delete xs;}\n',
            'vector-negative-resize.azs': 'void main(){Vector<int>* xs=new Vector<int>();xs.resize(-1);delete xs;}\n',
            'map-missing-get.azs': 'void main(){Map<int,int>* xs=new Map<int,int>();xs.put(1,2);int missing=xs.get(0);delete xs;}\n',
            'map-missing-index.azs': 'void main(){Map<int,int>* xs=new Map<int,int>();xs.put(1,2);int missing=xs[0];delete xs;}\n',
        }.items():
            result = run_source(name, header_include + body)
            if result.returncode == 0 or 'Division by zero' not in result.stderr:
                raise AssertionError((name, result.stdout, result.stderr))

        result = run_source(
            'class-compare.azs',
            header_include +
            'class Point { int x; }\n'
            'void main(){Set<Point>* xs=new Set<Point>();Point a;a.x=1;Point b;b.x=2;'
            'xs.insert(a);xs.insert(b);delete xs;}\n',
        )
        if result.returncode == 0 or 'This type requires a custom comparison function' not in result.stderr:
            raise AssertionError((result.stdout, result.stderr))

        # 0x1234<<16 | 0x0042 == 305397826, 0x0043 == 305397827, 0x0044 == 305397828, 0x0045 == 305397829.
        result = run_source(
            'class-order.azs',
            '#namespace 1234\n' + header_include +
            'class Point { int x; Point(int n){x=n;} }\n'
            'int compare_point(Point a, Point b):0042 { return value_compare<int>(a.x, b.x); }\n'
            'int compare_point_rev(Point a, Point b):0043 { return value_compare<int>(b.x, a.x); }\n'
            'int compare_int_rev(int a, int b):0044 { return value_compare<int>(b, a); }\n'
            'int compare_parity(int a, int b):0045 { return value_compare<int>(a % 2, b % 2); }\n'
            'int main(){\n'
            '    Set<Point>* xs=new Set<Point>();\n'
            '    xs.set_order(305397826);\n'
            '    Point a(2); Point b(1); Point c(3); Point d(2);\n'
            '    xs.insert(a); xs.insert(b); xs.insert(c); xs.insert(d);\n'
            '    if (xs.size()!=3 || xs.at(0).x!=1 || xs.at(1).x!=2 || xs.at(2).x!=3) { delete xs; return 1; }\n'
            '    xs.set_order(305397827);\n'
            '    if (xs.at(0).x!=3 || xs.at(1).x!=2 || xs.at(2).x!=1) { delete xs; return 2; }\n'
            '    delete xs;\n'
            '    Map<int,int>* ys=new Map<int,int>();\n'
            '    ys.put(1,10); ys.put(3,30); ys.put(2,20);\n'
            '    ys.set_order(305397828);\n'
            '    ys.put(0,5);\n'
            '    if (ys.size()!=4 || ys.get(0)!=5 || ys.get(1)!=10 || ys.get(2)!=20 || ys.get(3)!=30) { delete ys; return 3; }\n'
            '    delete ys;\n'
            '    Set<int>* parity=new Set<int>();\n'
            '    parity.insert(1); parity.insert(2); parity.insert(3); parity.insert(4);\n'
            '    parity.set_order(305397829);\n'
            '    if (parity.size()!=2 || parity.at(0)!=2 || parity.at(1)!=1 || parity.contains(4)==false) { delete parity; return 4; }\n'
            '    parity.erase(4);\n'
            '    if (parity.size()!=1 || parity.contains(2) || parity.at(0)!=1) { delete parity; return 5; }\n'
            '    delete parity;\n'
            '    Map<int,int>* grouped=new Map<int,int>();\n'
            '    grouped.put(1,10); grouped.put(2,20); grouped.put(3,30); grouped.put(4,40);\n'
            '    grouped.set_order(305397829);\n'
            '    if (grouped.size()!=2 || grouped.get(2)!=40 || grouped.get(4)!=40 || grouped.get(1)!=30) { delete grouped; return 6; }\n'
            '    grouped.erase(2);\n'
            '    if (grouped.size()!=1 || grouped.contains(4) || grouped.get(3)!=30) { delete grouped; return 7; }\n'
            '    delete grouped;\n'
            '    return 0;\n'
            '}\n',
        )
        if result.returncode != 0 or result.stdout != '0\n':
            raise AssertionError((result.stdout, result.stderr))

    print(f'Container library checks passed: {regression_checks}')


if __name__ == '__main__':
    main()
