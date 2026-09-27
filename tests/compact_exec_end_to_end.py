#!/usr/bin/env python3
"""Independent exec-v8 wire checks, malformed inputs, source execution and JNI."""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
MAIN = 0x0FFF0000
INT, STRING, BOOL, FLOAT, DOUBLE, BYTES = 3, 1, 0xD00, 0xCE867, 0xCE1066, 0xCE200A
ADDRESS = 0xCE200B
ARRAY, MAP = 0xAD, 2
OP_NAMES = {3: 'v', 4: 'vd', 5: 'vs', 6: 'm', 7: 'r', 8: 'ro', 9: 'oa',
            10: 'ob', 11: 'od', 12: 'add', 13: 'minus', 14: 'multiply',
            15: 'divide', 16: 'mod', 17: 'gt', 18: 'lt', 19: 'eq', 20: 'ne',
            21: 'ge', 22: 'le', 23: 'and', 24: 'or', 25: 'not', 26: 'neg',
            27: 'if', 28: 'wi', 29: 'brk', 30: 'cont', 31: 'cleanup',
            32: 'new_block', 33: 'block_address', 34: 'mv', 35: 'drop',
            36: 'context_abi', 37: 'context_default', 38: 'return_typed', 39: 'check_type'}


def integer(value):
    return struct.pack('<i', value)


def frame(payload):
    return integer(len(payload)) + payload


def stack(*payloads):
    return b''.join(frame(payload) for payload in payloads)


def frames(payload):
    result, offset = [], 0
    while offset < len(payload):
        assert len(payload) - offset >= 4, 'truncated frame length'
        length, = struct.unpack_from('<i', payload, offset)
        offset += 4
        assert 0 <= length <= len(payload) - offset, 'invalid frame length'
        result.append(payload[offset:offset + length])
        offset += length
    return result


def int_value(payload):
    assert len(payload) == 4, 'int32 field width'
    return struct.unpack('<i', payload)[0]


def bool_value(payload):
    assert payload in (b'\0', b'\1'), 'boolean field width/value'
    return payload == b'\1'


@dataclass(frozen=True)
class Address:
    value: int

    def __post_init__(self):
        assert 0 <= self.value < 2**64, 'uint64 address range'


@dataclass(frozen=True)
class Scalar:
    tag: int
    payload: bytes

    def value(self):
        if self.tag == ADDRESS:
            assert len(self.payload) == 8, 'address width'
            return Address(struct.unpack('<Q', self.payload)[0])
        if self.tag == INT:
            return int_value(self.payload)
        if self.tag == BOOL:
            return bool_value(self.payload)
        if self.tag == STRING:
            return self.payload.decode('utf-8')
        if self.tag in (FLOAT, DOUBLE):
            assert len(self.payload) == (4 if self.tag == FLOAT else 8)
            return struct.unpack('<f' if self.tag == FLOAT else '<d', self.payload)[0]
        assert self.tag in (BYTES, 0xCE2009)
        return self.payload


def scalar(value):
    if isinstance(value, Address):
        return Scalar(ADDRESS, struct.pack('<Q', value.value))
    if isinstance(value, bool):
        return Scalar(BOOL, bytes([value]))
    if isinstance(value, int):
        return Scalar(INT, integer(value))
    if isinstance(value, str):
        return Scalar(STRING, value.encode('utf-8'))
    if isinstance(value, bytes):
        return Scalar(BYTES, value)
    raise TypeError(type(value))


def typed_encode(value):
    if not isinstance(value, (Scalar, dict, list)):
        value = scalar(value)
    if isinstance(value, Scalar):
        return value.tag, value.payload
    if isinstance(value, list):
        entries = [typed_encode(item) for item in value]
        return ARRAY, b''.join(stack(integer(tag), payload) for tag, payload in entries)
    entries = [(key, *typed_encode(item)) for key, item in value.items()]
    return MAP, b''.join(stack(key.encode('utf-8'), integer(tag), payload) for key, tag, payload in entries)


def typed_decode(tag, payload):
    parts = frames(payload) if tag in (ARRAY, MAP) else None
    if tag == ARRAY:
        assert len(parts) % 2 == 0
        return [typed_decode(int_value(parts[i]), parts[i + 1]) for i in range(0, len(parts), 2)]
    if tag == MAP:
        assert len(parts) % 3 == 0
        result = {}
        for i in range(0, len(parts), 3):
            key = parts[i].decode('utf-8')
            assert key not in result
            result[key] = typed_decode(int_value(parts[i + 1]), parts[i + 2])
        return result
    result = Scalar(tag, payload)
    result.value()  # Validate scalar width and value while preserving its exact type.
    return result


def expression(opcode, *fields):
    return stack(integer(opcode), *fields)


def constant(value):
    return expression(0, typed_encode([value])[1])


def block(*expressions):
    return expression(1, stack(*expressions))


def returning(value=None):
    return expression(7, b'\0') if value is None else expression(7, b'\1', value)


def function(body, ident=MAIN, return_type=0, params=(), locals_count=0, hidden_count=0, entry_kind=0):
    return stack(integer(ident), integer(return_type), integer(len(params)),
                 integer(locals_count), stack(*(integer(kind) for kind in params)), body, integer(hidden_count), integer(entry_kind))


def module(functions, globals_count=0, signatures=()):
    return [b'AZSCRIPT', integer(8), integer(2), b'independent wire fixture',
            integer(globals_count), typed_encode({})[1], stack(*signatures), stack(*functions), b'', b'']


def encode_context(value):
    if 'ref' in value:
        assert set(value) == {'ref'}
        return stack(integer(0), integer(value['ref']))
    fields = [integer(1), integer(value['abi']), integer(value['kind']), bytes(['factory' in value])]
    if 'factory' in value:
        fields.append(integer(value['factory']))
    fields.append(encode_contexts(value.get('contexts', [])))
    return stack(*fields)


def encode_contexts(values):
    return stack(*(encode_context(item) for item in values))


def encode_compact_expression(value):
    """Test-side writer shared with the independent real-JNI fixtures."""
    if isinstance(value, list):
        return block(*(encode_compact_expression(item) for item in value))
    if not isinstance(value, dict):
        return constant(value)
    if set(value) == {'address'}:
        return constant(Address(int(value['address'])))
    if value.get('t') == 1:
        return expression(2, integer(value['id']), stack(*(encode_compact_expression(item) for item in value['param'])),
                          encode_contexts(value.get('contexts', [])))
    name = value['c']
    opcode = name if isinstance(name, int) else {name: opcode for opcode, name in OP_NAMES.items()}[name]
    child = lambda key: encode_compact_expression(value[key])
    if opcode == 3:
        fields = [integer(value['v'])]
    elif opcode == 4:
        fields = [integer(value['v']), integer(value.get('declared-type', 6)), bytes(['val' in value])]
        if 'val' in value:
            fields.append(child('val'))
    elif opcode == 5:
        fields = [integer(value['v']), child('val')]
    elif opcode == 6 or 12 <= opcode <= 24:
        fields = [child('v1'), child('v2')]
    elif opcode == 7:
        fields = [bytes(['r' in value])] + ([child('r')] if 'r' in value else [])
    elif opcode in (8, 11, 25, 26):
        fields = [child('r' if opcode == 8 else 'v')]
    elif opcode == 9:
        fields = [child('v'), integer(value['offset'])]
    elif opcode == 10:
        fields = [child('v'), bytes(['destructor' in value])]
        if 'destructor' in value:
            fields.append(integer(value['destructor']))
        fields.append(bytes([value['manual']]))
        fields.append(encode_contexts(value.get('contexts', [])))
    elif opcode == 27:
        fields = [child('v'), child('val'), bytes(['else' in value])]
        if 'else' in value:
            fields.append(child('else'))
    elif opcode == 28:
        fields = [child('v'), child('val')]
    elif opcode == 31:
        fields = [child('v'), child('val'), bytes([value['on-error']])]
    elif opcode == 32:
        fields = [integer(value['size'])]
    elif opcode in (33, 35):
        fields = [child('v')]
    elif opcode == 34:
        fields = [integer(value['v'])]
    elif opcode in (36, 37):
        fields = [encode_context(value['context'])]
    elif opcode in (38, 39):
        fields = [child('v'), encode_context(value['context'])]
    else:
        assert opcode in (29, 30)
        fields = []
    return expression(opcode, *fields)


def encode_compact_module(program):
    """Encode fixtures expressed as readable dictionaries into only v8 wire."""
    global_names = program.get('gvs', [])
    numeric = isinstance(global_names, int)
    global_count = global_names if numeric else len(global_names)
    functions = []
    for entry in program['f']:
        params = entry.get('param-types', [6] * entry.get('param-count', 0))
        local_names = {}

        def lower(value):
            if isinstance(value, list):
                return [lower(item) for item in value]
            if not isinstance(value, dict):
                return value
            result = {}
            # Declarations publish their numeric slot before lowering references.
            if not numeric and value.get('c') == 'vd':
                local_names[value['v']] = len(params) + len(local_names)
            for key, item in value.items():
                if not numeric and key == 'v' and value.get('c') in ('v', 'vd', 'vs', 'mv'):
                    if item.startswith('__func_param'):
                        result[key] = int(item.removeprefix('__func_param'))
                    elif item in local_names:
                        result[key] = local_names[item]
                    else:
                        result[key] = -1 - global_names.index(item)
                else:
                    result[key] = lower(item)
            return result

        body = lower(entry['script'])
        functions.append(function(encode_compact_expression(body), entry['id'], entry['return-type'], params,
                                  entry.get('local-count', len(local_names)),
                                  entry.get('hidden-count', 0), entry.get('entry-kind', 0)))
    signatures = [stack(integer(entry['id']), integer(entry['return-type']),
                        stack(*(integer(kind) for kind in entry['param-types'])),
                        integer(entry.get('hidden-count', 0)), integer(entry.get('entry-kind', 0)))
                  for entry in program.get('extern-signatures', [])]
    fields = module(functions, global_count, signatures)
    fields[2] = integer(program.get('version', 2))
    fields[3] = program.get('author', '').encode('utf-8')
    fields[5] = typed_encode(program.get('ext', {}))[1]
    fields[8] = program.get('namespace-hint', '').encode('utf-8')
    fields[9] = stack(*(stack(item['hint'].encode('utf-8'), integer(item['namespace']))
                        for item in program.get('assume-hints', [])))
    return frame(stack(*fields))


class WireReader:
    """Decode fixed records without consulting runtime/compiler implementation."""
    def __init__(self):
        self.opcodes = set()
        self.scalars = []
        self.optional = {'initializer': set(), 'return': set(), 'else': set()}

    def context(self, payload):
        fields = frames(payload)
        variant = int_value(fields[0])
        if variant == 0:
            assert len(fields) == 2
            return {'ref': int_value(fields[1])}
        assert variant == 1 and len(fields) >= 5
        present = bool_value(fields[3])
        assert len(fields) == 5 + present
        result = {'abi': int_value(fields[1]), 'kind': int_value(fields[2]),
                  'contexts': self.contexts(fields[-1])}
        if present:
            result['factory'] = int_value(fields[4])
        return result

    def contexts(self, payload):
        return [self.context(item) for item in frames(payload)]

    def expression(self, payload):
        fields = frames(payload)
        assert fields
        opcode = int_value(fields.pop(0))
        assert 0 <= opcode <= 39, opcode
        self.opcodes.add(opcode)
        if opcode == 0:
            assert len(fields) == 1
            values = typed_decode(ARRAY, fields[0])
            assert len(values) == 1 and isinstance(values[0], Scalar)
            if values[0].tag == BYTES:
                assert values[0].payload == b'\xff'
            self.scalars.append(values[0])
            return values[0]
        if opcode == 1:
            assert len(fields) == 1
            return [self.expression(item) for item in frames(fields[0])]
        if opcode == 2:
            assert len(fields) == 3
            return {'t': 1, 'id': int_value(fields[0]),
                    'param': [self.expression(item) for item in frames(fields[1])],
                    'contexts': self.contexts(fields[2])}
        result = {'t': 0, 'c': OP_NAMES[opcode]}
        if opcode == 3:
            assert len(fields) == 1
            result['v'] = int_value(fields[0])
        elif opcode == 4:
            assert len(fields) >= 3
            present = bool_value(fields[2])
            self.optional['initializer'].add(present)
            assert len(fields) == 3 + present
            result['v'] = int_value(fields[0])
            kind = int_value(fields[1])
            assert kind in (0, 1, 2, 3, 4, 6, 7, 8)
            if kind != 6:
                result['declared-type'] = kind
            if present:
                result['val'] = self.expression(fields[3])
        elif opcode == 5:
            assert len(fields) == 2
            result.update(v=int_value(fields[0]), val=self.expression(fields[1]))
        elif opcode == 6 or 12 <= opcode <= 24:
            assert len(fields) == 2
            result.update(v1=self.expression(fields[0]), v2=self.expression(fields[1]))
        elif opcode == 7:
            assert fields
            present = bool_value(fields[0])
            self.optional['return'].add(present)
            assert len(fields) == 1 + present
            if present:
                result['r'] = self.expression(fields[1])
        elif opcode in (8, 11, 25, 26):
            assert len(fields) == 1
            result['r' if opcode == 8 else 'v'] = self.expression(fields[0])
        elif opcode == 9:
            assert len(fields) == 2
            result.update(v=self.expression(fields[0]), offset=int_value(fields[1]))
        elif opcode == 10:
            assert len(fields) >= 3
            present = bool_value(fields[1])
            assert len(fields) == 4 + present
            result.update(v=self.expression(fields[0]), manual=bool_value(fields[-2]),
                          contexts=self.contexts(fields[-1]))
            if present:
                result['destructor'] = int_value(fields[2])
        elif opcode == 27:
            assert len(fields) >= 3
            present = bool_value(fields[2])
            self.optional['else'].add(present)
            assert len(fields) == 3 + present
            result.update(v=self.expression(fields[0]), val=self.expression(fields[1]))
            if present:
                result['else'] = self.expression(fields[3])
        elif opcode == 28:
            assert len(fields) == 2
            result.update(v=self.expression(fields[0]), val=self.expression(fields[1]))
        elif opcode == 31:
            assert len(fields) == 3
            result.update(v=self.expression(fields[0]), val=self.expression(fields[1]))
            result['on-error'] = bool_value(fields[2])
        elif opcode == 32:
            assert len(fields) == 1
            result['size'] = int_value(fields[0])
            assert result['size'] >= 1
        elif opcode in (33, 35):
            assert len(fields) == 1
            result['v'] = self.expression(fields[0])
        elif opcode == 34:
            assert len(fields) == 1
            result['v'] = int_value(fields[0])
            assert result['v'] >= 0
        elif opcode in (36, 37):
            assert len(fields) == 1
            result['context'] = self.context(fields[0])
        elif opcode in (38, 39):
            assert len(fields) == 2
            result.update(v=self.expression(fields[0]), context=self.context(fields[1]))
        else:
            assert opcode in (29, 30) and not fields
        return result

    def read(self, data):
        file_fields = frames(data)
        assert len(file_fields) == 1, 'file must contain one root frame'
        fields = frames(file_fields[0])
        assert len(fields) == 10 and fields[0] == b'AZSCRIPT'
        assert int_value(fields[1]) == 8
        legacy = {'exec-version': 8, 'version': int_value(fields[2]),
                  'author': fields[3].decode('utf-8'), 'gvs': int_value(fields[4]),
                  'ext': typed_decode(MAP, fields[5]), 'extern-signatures': [], 'f': [],
                  'namespace-hint': fields[8].decode('utf-8'), 'assume-hints': []}
        for payload in frames(fields[9]):
            assume = frames(payload)
            assert len(assume) == 2
            legacy['assume-hints'].append({'hint': assume[0].decode('utf-8'), 'namespace': int_value(assume[1])})
        for payload in frames(fields[6]):
            signature = frames(payload)
            assert len(signature) == 5
            legacy['extern-signatures'].append({'id': int_value(signature[0]), 'return-type': int_value(signature[1]),
                'param-types': [int_value(item) for item in frames(signature[2])],
                'hidden-count': int_value(signature[3]), 'entry-kind': int_value(signature[4])})
        for payload in frames(fields[7]):
            entry = frames(payload)
            assert len(entry) == 8
            parameters = [int_value(item) for item in frames(entry[4])]
            assert int_value(entry[2]) == len(parameters)
            legacy['f'].append({'id': int_value(entry[0]), 'return-type': int_value(entry[1]),
                'param-count': int_value(entry[2]), 'local-count': int_value(entry[3]),
                'param-types': parameters, 'script': self.expression(entry[5]),
                'hidden-count': int_value(entry[6]), 'entry-kind': int_value(entry[7])})
        return legacy


SOURCE = '''#author Compact wire source
#gvar count
extern int unused_host(int, string, float, double, boolean):0x12340001;
class Box {
    int value;
    Box(int n) { value=n; }
    Box * self() { return this; }
    int get() { return value; }
    ~Box() { count+=value; }
}
class Pair {
    Box first(0);
    Pair(Box b) { first=b; }
}
void empty() { return; }
int main() {
    count=0;
    var uninitialized;
    int a=9; int b=4;
    float f=1.25f; double d=2.5; string text="wire";
    int total=a+b+a-b+a*b+a/b+a%b;
    boolean comparisons=a>b && b<a || a==b || a!=b || a>=b || b<=a;
    print(-a); print(!comparisons); print(f); print(d); print(text);
    address pointer=alloc(1); mem_get(pointer)=41; print(mem_get(pointer)); mem_free(pointer);
    if(false) { print("unreachable"); } else { print("else"); }
    if(true) { empty(); }
    int i=0; while(i<3) { i+=1; if(i==2) { break; } }
    Box automatic(3); Box * manual=new Box(5); manual.value=6; delete manual;
    print(automatic.self().get());
    Pair pair(automatic);
    int skipped=0; int j=0; while(j<3) { j+=1; if(j==2) { continue; } skipped+=1; }
    return total+i+count+skipped;
}
'''
EXPECTED = '-9\nfalse\n1.25\n2.5\nwire\n41\nelse\n3\n70\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', required=True, type=Path)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='compact-exec-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)

        def run(command, expected=None, timeout=30):
            result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)
            assert result.returncode == 0, (command, result.stdout, result.stderr)
            assert 'WARNING in native method' not in result.stdout + result.stderr
            if expected is not None:
                assert result.stdout == expected, (command, result.stdout, expected, result.stderr)
            return result

        def compile_source(name, source):
            src, abd, ast, executable = [work / (name + suffix) for suffix in ('.azs', '.abd', '.ast.json', '.exec.json')]
            src.write_text(source, encoding='utf-8')
            compiler = ['java', '-cp', args.classpath, 'azertia.Main']
            run(compiler + ['compile', src, '-o', abd, '--ast', ast, '--exec-json', executable])
            roundtrip = work / (name + '.roundtrip.abd')
            run(compiler + ['compile-json', ast, '-o', roundtrip])
            assert abd.read_bytes() == roundtrip.read_bytes(), 'readable AST roundtrip'
            debug = json.loads(executable.read_text(encoding='utf-8'))
            assert debug['exec-version'] == 8

            def inspect(value):
                if isinstance(value, dict):
                    if value.get('t') == 0 and 'c' in value:
                        assert type(value['c']) is int and 3 <= value['c'] <= 39, value
                    for item in value.values():
                        inspect(item)
                elif isinstance(value, list):
                    for item in value:
                        inspect(item)
            for item in debug['f']:
                inspect(item['script'])
            return abd, json.loads(ast.read_text(encoding='utf-8')), debug

        abd, ast, debug = compile_source('coverage', SOURCE)
        reader = WireReader()
        legacy = reader.read(abd.read_bytes())
        assert encode_compact_module(legacy) == abd.read_bytes(), 'independent fixed-record reconstruction'
        assert reader.opcodes == set(range(36)), ('missing opcodes', set(range(36)) - reader.opcodes)
        assert {item.tag for item in reader.scalars} >= {INT, STRING, FLOAT, DOUBLE, BOOL}
        assert Scalar(FLOAT, struct.pack('<f', 1.25)) in reader.scalars
        assert Scalar(DOUBLE, struct.pack('<d', 2.5)) in reader.scalars
        assert all(values == {False, True} for values in reader.optional.values()), reader.optional
        assert legacy['gvs'] == debug['gvs'] == 1
        assert legacy['author'] == 'Compact wire source'
        assert legacy['version'] == ast['metadata']['version']
        assert legacy['extern-signatures'] == [{'id': 0x12340001, 'return-type': 0, 'param-types': [0, 1, 2, 3, 4],
                                                   'hidden-count': 0, 'entry-kind': 0}]
        assert [(f['id'], f['param-count'], f['local-count']) for f in legacy['f']] == [
            (f['id'], f['param-count'], f['local-count']) for f in debug['f']]
        # Parsing every instruction as a fixed record proves keys/tags are not
        # embedded in the instruction structures, even when string literals exist.
        run([args.runner, abd], EXPECTED)
        old_wire = frame(typed_encode(legacy)[1])
        old_path = work / 'coverage.legacy-map.abd'
        old_path.write_bytes(old_wire)
        old_result = subprocess.run([str(args.runner), str(old_path)], capture_output=True, text=True, timeout=10)
        assert old_result.returncode == 1 and old_result.stderr.startswith('AzScript error:'), old_result
        if args.host:
            run([args.host, abd, 'flush', 'span', MAIN], '0:1\n')

        minimum = module([function(block(returning(constant(42))))])
        checks = 0

        def execute_wire(name, root_fields=None, data=None, expected=None, invalid=False):
            nonlocal checks
            path = work / (name + '.abd')
            path.write_bytes(data if data is not None else frame(stack(*(root_fields or minimum))))
            result = subprocess.run([str(args.runner), str(path)], capture_output=True, text=True, timeout=10)
            if invalid:
                assert result.returncode == 1 and result.stderr.startswith('AzScript error:'), (name, result.returncode, result.stdout, result.stderr)
                assert not any(text in result.stderr for text in ('AddressSanitizer', 'UndefinedBehaviorSanitizer', 'runtime error:')), (name, result.stderr)
            else:
                assert result.returncode == 0 and result.stdout == expected and not result.stderr, (name, result.stdout, result.stderr)
            checks += 1

        execute_wire('independent-minimum', expected='42\n')
        for name, value, kind, expected in [('float', Scalar(FLOAT, struct.pack('<f', 1.25)), 2, '1.25\n'),
                ('double', Scalar(DOUBLE, struct.pack('<d', 2.5)), 3, '2.5\n'),
                ('string', '中文\0wire', 1, '中文\0wire\n'), ('bool', True, 4, 'true\n'),
                ('address', Address(2**64-1), 7, '18446744073709551615\n'),
                ('void', b'\xff', 5, '')]:
            execute_wire('independent-' + name, module([function(block(returning(constant(value))), return_type=kind)]), expected=expected)
        execute_wire('independent-void-return', module([function(block(returning()), return_type=5)]), expected='')

        mutations = [('root-missing', minimum[:-1]), ('root-extra', minimum + [b''])]
        for index, value, label in [(0, b'AZSCRIPX', 'magic'), (1, integer(5), 'version'), (1, integer(6), 'address-only-version'), (1, integer(7), 'previous-version'),
                (1, b'\4', 'version-width'), (2, b'\2', 'source-version-width'),
                (4, integer(-1), 'negative-globals'), (4, integer(2**31 - 1), 'huge-globals'),
                (4, b'\0', 'global-width'), (5, b'\1', 'extension-frame'),
                (3, b'\xff', 'author-utf8')]:
            changed = minimum.copy()
            changed[index] = value
            mutations.append((label, changed))
        valid_function = frames(frames(minimum[7])[0])
        bad_functions = [('function-missing', valid_function[:-1]), ('function-extra', valid_function + [b''])]
        for index, value, label in [(0, b'\1', 'function-id-width'), (1, integer(9), 'return-type'),
                (2, integer(-1), 'negative-params'), (3, integer(-1), 'negative-locals'),
                (4, stack(b'\0'), 'parameter-type-width'), (4, stack(integer(0)), 'parameter-count'),
                (3, b'\0', 'local-width')]:
            entry = valid_function.copy()
            entry[index] = value
            bad_functions.append((label, entry))
        for label, entry in bad_functions:
            changed = minimum.copy()
            changed[7] = stack(stack(*entry))
            mutations.append((label, changed))
        for label, signature in [('signature-missing', stack(integer(0x12340001), integer(0))),
                ('signature-extra', stack(integer(0x12340001), integer(0), b'', integer(0), integer(0), b'')),
                ('signature-bad-type', stack(integer(0x12340001), integer(0), stack(integer(5)), integer(0), integer(0))),
                ('signature-width', stack(b'\1', integer(0), b'', integer(0), integer(0)))]:
            changed = minimum.copy()
            changed[6] = stack(signature)
            mutations.append((label, changed))
        for label, changed in mutations:
            execute_wire(label, changed, invalid=True)

        bad_expressions = {
            'empty-expression': b'', 'opcode-width': stack(b'\0'),
            'unknown-opcode': expression(40), 'negative-opcode': expression(-1),
            'new-block-missing': expression(32), 'new-block-empty': expression(32, integer(0)),
            'new-block-width': expression(32, b'\0'), 'block-address-extra': expression(33, constant(0), constant(0)),
            'object-move-global': expression(34, integer(-1)), 'object-move-outside': expression(34, integer(0)),
            'drop-constant-target': expression(35, constant(0)),
            'constant-missing': expression(0), 'constant-empty': expression(0, b''),
            'constant-multiple': expression(0, typed_encode([1, 2])[1]),
            'constant-map': expression(0, typed_encode([{}])[1]),
            'constant-bool': expression(0, stack(integer(BOOL), b'\2')),
            'constant-address-short': expression(0, stack(integer(ADDRESS), b'1234')),
            'constant-address-long': expression(0, stack(integer(ADDRESS), b'123456789')),
            'constant-float-width': expression(0, stack(integer(FLOAT), b'123')),
            'constant-double-width': expression(0, stack(integer(DOUBLE), b'1234')),
            'constant-float-nan': expression(0, stack(integer(FLOAT), struct.pack('<f', float('nan')))),
            'constant-double-infinity': expression(0, stack(integer(DOUBLE), struct.pack('<d', float('inf')))),
            'constant-string-utf8': expression(0, stack(integer(STRING), b'\xff')),
            'constant-legacy-bytes': expression(0, stack(integer(0xCE2009), b'\xff')),
            'constant-void-marker': expression(0, stack(integer(BYTES), b'\0')),
            'constant-unknown-tag': expression(0, stack(integer(999), b'')),
            'block-extra': expression(1, b'', b''), 'call-missing': expression(2, integer(MAIN)),
            'global-outside': expression(3, integer(-1)), 'global-intmin': expression(3, integer(-2147483648)),
            'local-outside': expression(3, integer(0)), 'variable-width': expression(3, b'\0'),
            'declare-global': expression(4, integer(-1), integer(0), b'\0'),
            'declare-flag': expression(4, integer(0), integer(0), b'\2'),
            'declare-missing-value': expression(4, integer(0), integer(0), b'\1'),
            'assign-missing': expression(5, integer(0)), 'move-missing': expression(6, constant(0)),
            'return-missing': expression(7), 'return-flag': expression(7, b'\2'),
            'return-flag-width': expression(7, integer(1)),
            'return-unexpected-value': expression(7, b'\0', constant(1)),
            'return-missing-value': expression(7, b'\1'),
            'object-return-extra': expression(8, constant(0), constant(0)),
            'address-offset-width': expression(9, constant(0), b'\0'),
            'bind-manual-flag': expression(10, constant(0), b'\0', b'\2', b''),
            'delete-extra': expression(11, constant(0), constant(0)),
            'binary-missing': expression(12, constant(1)),
            'binary-extra': expression(24, constant(True), constant(False), constant(True)),
            'unary-extra': expression(25, constant(True), constant(False)),
            'if-flag': expression(27, constant(True), block(), b'\2'),
            'if-missing-else': expression(27, constant(True), block(), b'\1'),
            'if-unexpected-else': expression(27, constant(True), block(), b'\0', block()),
            'while-extra': expression(28, constant(False), block(), block()),
            'break-extra': expression(29, constant(1)),
        }
        for label, body in bad_expressions.items():
            execute_wire(label, module([function(body)]), invalid=True)
        complete = frame(stack(*minimum))
        execute_wire('file-trailing', data=complete + b'\0', invalid=True)
        execute_wire('file-extra-frame', data=complete + frame(b''), invalid=True)
        execute_wire('file-truncated', data=complete[:-1], invalid=True)
        execute_wire('file-negative-length', data=integer(-1), invalid=True)
        deep = constant(True)
        for _ in range(100):
            deep = expression(25, deep)
        execute_wire('depth-within-limit', module([function(returning(deep), return_type=4)]), expected='true\n')
        for _ in range(28):
            deep = expression(25, deep)
        execute_wire('depth-128', module([function(returning(deep), return_type=4)]), invalid=True)

        if args.library:
            assert args.bridge, '--library requires --bridge'
            jni_source = '''#author Compact JNI
#gvar counter
extern void record(int):0x12340002;
class Mark { int id; Mark(){ id=0; } }
class Box { int value; Mark mark; Box(int n){value=n;mark.id=n;} ~Box(){record(value);} }
int set(int value){counter=value;return counter;}
int get(){return counter;}
Box * make(int value){Box * b(value);return b;}
int inspect(Box * b){return b.value+b.mark.id-b.value;}
void main(){}
'''
            native, tree, _ = compile_source('jni', jni_source)
            other, _, _ = compile_source('jni-other', jni_source.replace('Compact JNI', 'Different JNI'))
            WireReader().read(native.read_bytes())
            java = work / 'CompactSnapshot.java'
            java.write_text('''import azertia.AbdInvoker;
import java.io.File;
import java.util.*;
public class CompactSnapshot {
 public static void main(String[] args) {
  List<Integer> destroyed=new ArrayList<>();
  AbdInvoker.registerJfunction(0x12340002, values -> {destroyed.add((Integer)values[0]);return null;});
  int set=Integer.parseInt(args[3]), get=Integer.parseInt(args[4]);
  int make=Integer.parseInt(args[5]), inspect=Integer.parseInt(args[6]);
  File saved=new File(args[1]);
  try {
   AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.flush();
   if(!Integer.valueOf(41).equals(AbdInvoker.invoke(set,41))) throw new AssertionError("set");
   Object pointer=AbdInvoker.invoke(make,7);
   if(!AbdInvoker.saveStatus(saved)) throw new AssertionError("save");
   AbdInvoker.invoke(set,99); AbdInvoker.invoke(make,9);
   AbdInvoker.loadStatus(saved);
   if(!destroyed.isEmpty()) throw new AssertionError("restore called old destructor");
   if(!Integer.valueOf(41).equals(AbdInvoker.invoke(get))) throw new AssertionError("global restore");
   if(!Integer.valueOf(7).equals(AbdInvoker.invoke(inspect,pointer))) throw new AssertionError("object restore");
   AbdInvoker.destroyScript();
   if(!destroyed.equals(List.of(7))) throw new AssertionError(destroyed);
   AbdInvoker.loadScript(new File(args[2]));
   AbdInvoker.flush();
   try {AbdInvoker.loadStatus(saved);throw new AssertionError("different opaque code accepted");}
   catch(IllegalArgumentException expected) {}
   System.out.println("Compact JNI snapshot passed");
  } finally {AbdInvoker.close();}
 }
}''', encoding='utf-8')
            run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, java])
            saved = work / 'opaque.snapshot.abd'
            ids = [int(tree['abstract'][name], 16) for name in ('set', 'get', 'make', 'inspect')]
            run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                 '-cp', os.pathsep.join((str(work), str(args.bridge))), 'CompactSnapshot', native, saved, other, *ids],
                'Compact JNI snapshot passed\n')
            snapshot = typed_decode(MAP, frames(saved.read_bytes())[0])
            assert snapshot['snapshot version'].value() == 8
            assert snapshot['module manifest'][0]['bytes'].payload == native.read_bytes()
            assert [item.value() for item in snapshot['variable global']] == [41]
            assert len(snapshot['objects']) == 1
            # The pointer object's literal-object field is saved as a slot block with its address.
            assert any(isinstance(item, dict) and 'object' in item and len(item['slots']) == 1 for item in snapshot['heap'])

        compact_size = len(abd.read_bytes())
        print(f'Compact exec: all 36 baseline opcodes, typed constants, source/AST roundtrip, legacy-format rejection; '
              f'{checks} independent wire cases passed. Same source: compact={compact_size} bytes, '
              f'legacy-map={len(old_wire)} bytes ({compact_size / len(old_wire):.1%}).' +
              (' JNI opaque-byte snapshot passed.' if args.library else ''))


if __name__ == '__main__':
    main()
