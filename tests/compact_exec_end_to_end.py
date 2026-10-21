#!/usr/bin/env python3
"""Independent exec-v10 wire checks, debug records, malformed inputs, source execution and JNI."""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
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
            36: 'context_abi', 37: 'context_default', 38: 'return_typed', 39: 'check_type',
            40: 'buffer_new', 41: 'buffer_op', 42: 'value_compare',
            43: 'bit_and', 44: 'bit_or', 45: 'bit_xor', 46: 'shl', 47: 'shr', 48: 'ushr', 49: 'convert', 50: 'select', 51: 'check_object', 52: 'set_methods', 53: 'call_virtual',
            54: 'seq', 55: 'wrap_add', 56: 'wrap_minus', 57: 'wrap_multiply', 58: 'guard_method'}
EXEC_VERSION = 10


def registry_opcodes():
    """Read the compiler's dense opcode registry, so a new opcode cannot hide behind a stale local table."""
    registry = (ROOT / 'compiler/src/main/java/azertia/script/ExecOpcodes.java').read_text(encoding='utf-8')
    names = re.findall(r'"([^"\\]+)"', re.search(r'String\[\]\s+NAMES\s*=\s*\{([^}]+)\}', registry).group(1))
    assert names[:3] == ['constant', 'block', 'call'], names[:3]
    return dict(enumerate(names))


REGISTRY = registry_opcodes()
assert OP_NAMES == {code: name for code, name in REGISTRY.items() if code >= 3}, \
    'Update OP_NAMES and the independent reader when the compiler opcode registry changes'
# The registry is dense, so the first unassigned opcode follows the last one.
LAST_OPCODE = max(REGISTRY)


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


def debug_record(name, file=0, line=0, column=0, locations=()):
    """[name, file, line, column, flat (node, file, line, column) int stack]."""
    return stack(name.encode('utf-8') if isinstance(name, str) else name, integer(file), integer(line), integer(column),
                 stack(*(integer(part) for quad in locations for part in quad)))


def function(body, ident=MAIN, return_type=0, params=(), locals_count=0, hidden_count=0, entry_kind=0, debug=None):
    fields = [integer(ident), integer(return_type), integer(len(params)), integer(locals_count),
              stack(*(integer(kind) for kind in params)), body, integer(hidden_count), integer(entry_kind),
              bytes([debug is not None])]
    if debug is not None:
        fields.append(debug)
    return stack(*fields)


def module(functions, globals_count=0, signatures=(), debug_files=()):
    return [b'AZSCRIPT', integer(EXEC_VERSION), integer(2), b'independent wire fixture',
            integer(globals_count), typed_encode({})[1], stack(*signatures), stack(*functions), b'', b'',
            stack(*(name.encode('utf-8') for name in debug_files))]


def encode_context(value):
    if 'ref' in value:
        assert set(value) == {'ref'}
        return stack(integer(0), integer(value['ref']))
    fields = [integer(1), integer(value['abi']), integer(value['kind']), integer(value.get('width', 1)), bytes(['factory' in value])]
    if 'factory' in value:
        fields.append(integer(value['factory']))
    fields.append(encode_contexts(value.get('contexts', [])))
    fields.append(bytes(['placement' in value]))
    if 'placement' in value:
        fields.append(integer(value['placement']))
    fields.append(encode_contexts(value.get('placement-contexts', [])))
    fields.append(bytes(['element' in value]))
    if 'element' in value:
        fields.append(encode_context(value['element']))
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
    elif opcode == 6 or 12 <= opcode <= 24 or 43 <= opcode <= 48 or 55 <= opcode <= 57:
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
    elif opcode in (36, 37, 40):
        fields = [encode_context(value['context'])]
    elif opcode in (38, 39):
        fields = [child('v'), encode_context(value['context'])]
    elif opcode == 41:
        fields = [integer(value['op']), child('v'), stack(*(encode_compact_expression(arg) for arg in value['args']))]
    elif opcode == 42:
        fields = [encode_context(value['context']), child('v1'), child('v2')]
    elif opcode == 49:
        assert value['type'] in (0, 1, 2, 3, 7)
        fields = [integer(value['type']), child('v')]
    elif opcode == 50:
        fields = [child('v'), child('then'), child('else')]
    elif opcode == 51:
        fields = [child('v'), encode_context(value['context']), stack(*(integer(kind) for kind in value['slot-types']))]
    elif opcode == 52:
        entries = [stack(integer(entry['id']), encode_contexts(entry.get('contexts', []))) for entry in value['entries']]
        fields = [child('v'), stack(*entries)]
    elif opcode == 53:
        fields = [integer(value['slot']), integer(value['type']), stack(*(encode_compact_expression(arg) for arg in value['param']))]
    elif opcode == 54:
        fields = [stack(*(encode_compact_expression(item) for item in value['statements'])), child('v')]
    elif opcode == 58:
        fields = [child('v'), integer(value['slot']), integer(value['id'])]
    else:
        assert opcode in (29, 30)
        fields = []
    return expression(opcode, *fields)


def encode_compact_module(program):
    """Encode fixtures expressed as readable dictionaries into only exec v10 wire."""
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
        debug = entry.get('debug')
        if debug is not None:
            debug = debug_record(debug['name'], debug['file'], debug['line'], debug['column'],
                                 [tuple(int(part) for part in item.split(':')) for item in debug['locations']])
        functions.append(function(encode_compact_expression(body), entry['id'], entry['return-type'], params,
                                  entry.get('local-count', len(local_names)),
                                  entry.get('hidden-count', 0), entry.get('entry-kind', 0), debug))
    signatures = [stack(integer(entry['id']), integer(entry['return-type']),
                        stack(*(integer(kind) for kind in entry['param-types'])),
                        integer(entry.get('hidden-count', 0)), integer(entry.get('entry-kind', 0)))
                  for entry in program.get('extern-signatures', [])]
    fields = module(functions, global_count, signatures, program.get('debug-files', []))
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
        # Expression stacks of the function being read, in pre-order (contexts take no number).
        self.nodes = []

    def context(self, payload):
        fields = frames(payload)
        variant = int_value(fields[0])
        if variant == 0:
            assert len(fields) == 2
            return {'ref': int_value(fields[1])}
        assert variant == 1 and len(fields) >= 9
        result = {'abi': int_value(fields[1]), 'kind': int_value(fields[2]), 'width': int_value(fields[3])}
        position = 5
        if bool_value(fields[4]):
            result['factory'] = int_value(fields[position]); position += 1
        result['contexts'] = self.contexts(fields[position]); position += 1
        present = bool_value(fields[position]); position += 1
        if present:
            result['placement'] = int_value(fields[position]); position += 1
        result['placement-contexts'] = self.contexts(fields[position]); position += 1
        present = bool_value(fields[position]); position += 1
        if present:
            result['element'] = self.context(fields[position]); position += 1
        assert position == len(fields)
        return result

    def contexts(self, payload):
        return [self.context(item) for item in frames(payload)]

    def expression(self, payload):
        node = len(self.nodes)
        self.nodes.append(None)
        result = self.expression_body(payload)
        self.nodes[node] = result
        return result

    def expression_body(self, payload):
        fields = frames(payload)
        assert fields
        opcode = int_value(fields.pop(0))
        assert 0 <= opcode <= LAST_OPCODE, opcode
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
        elif opcode == 6 or 12 <= opcode <= 24 or 43 <= opcode <= 48 or 55 <= opcode <= 57:
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
        elif opcode in (36, 37, 40):
            assert len(fields) == 1
            result['context'] = self.context(fields[0])
        elif opcode in (38, 39):
            assert len(fields) == 2
            result.update(v=self.expression(fields[0]), context=self.context(fields[1]))
        elif opcode == 41:
            assert len(fields) == 3
            result.update(op=int_value(fields[0]), v=self.expression(fields[1]), args=[self.expression(arg) for arg in frames(fields[2])])
        elif opcode == 42:
            assert len(fields) == 3
            result.update(context=self.context(fields[0]), v1=self.expression(fields[1]), v2=self.expression(fields[2]))
        elif opcode == 49:
            assert len(fields) == 2
            kind = int_value(fields[0])
            assert kind in (0, 1, 2, 3, 7)
            result.update(type=kind, v=self.expression(fields[1]))
        elif opcode == 50:
            assert len(fields) == 3
            result.update(v=self.expression(fields[0]), then=self.expression(fields[1]), **{'else': self.expression(fields[2])})
        elif opcode == 51:
            assert len(fields) == 3
            kinds = [int_value(kind) for kind in frames(fields[2])]
            assert all(0 <= kind <= 8 and kind != 5 for kind in kinds)
            context = self.context(fields[1])
            if context.get('kind') == 3:
                assert len(kinds) == context['width']
            result.update(v=self.expression(fields[0]), context=context, **{'slot-types': kinds})
        elif opcode == 52:
            assert len(fields) == 2
            entries = []
            for payload in frames(fields[1]):
                entry = frames(payload)
                assert len(entry) == 2
                entries.append({'id': int_value(entry[0]), 'contexts': self.contexts(entry[1])})
            result.update(v=self.expression(fields[0]), entries=entries)
        elif opcode == 53:
            assert len(fields) == 3
            slot, kind = int_value(fields[0]), int_value(fields[1])
            assert slot >= 0 and 0 <= kind <= 8
            result.update(slot=slot, type=kind, param=[self.expression(arg) for arg in frames(fields[2])])
        elif opcode == 54:
            assert len(fields) == 2
            result.update(statements=[self.expression(item) for item in frames(fields[0])], v=self.expression(fields[1]))
        elif opcode == 58:
            assert len(fields) == 3
            slot = int_value(fields[1])
            assert slot >= 0
            result.update(v=self.expression(fields[0]), slot=slot, id=int_value(fields[2]))
        else:
            assert opcode in (29, 30) and not fields
        return result

    @staticmethod
    def debug_text(payload):
        text = payload.decode('utf-8')
        assert text and not any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F or c in '\u2028\u2029\u061c\u200e\u200f'
                                or 0x202A <= ord(c) <= 0x202E or 0x2066 <= ord(c) <= 0x2069 for c in text), text
        return text

    def debug(self, payload, files):
        fields = frames(payload)
        assert len(fields) == 5
        name = self.debug_text(fields[0])
        file, line, column = (int_value(field) for field in fields[1:4])
        assert 0 <= file < len(files) and line >= 0 and column >= 0 and (line == 0) == (column == 0)
        values = [int_value(item) for item in frames(fields[4])]
        assert len(values) % 4 == 0
        locations = []
        for i in range(0, len(values), 4):
            node, at_file, at_line, at_column = values[i:i + 4]
            assert (not locations or node > int(locations[-1].split(':')[0])) and 0 <= node < len(self.nodes)
            assert 0 <= at_file < len(files) and at_line >= 1 and at_column >= 1
            locations.append(f'{node}:{at_file}:{at_line}:{at_column}')
        return {'name': name, 'file': file, 'line': line, 'column': column, 'locations': locations}

    def read(self, data):
        file_fields = frames(data)
        assert len(file_fields) == 1, 'file must contain one root frame'
        fields = frames(file_fields[0])
        assert len(fields) == 11 and fields[0] == b'AZSCRIPT'
        assert int_value(fields[1]) == EXEC_VERSION
        files = [self.debug_text(item) for item in frames(fields[10])]
        assert len(set(files)) == len(files)
        legacy = {'exec-version': EXEC_VERSION, 'version': int_value(fields[2]),
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
        self.functions = []
        for payload in frames(fields[7]):
            entry = frames(payload)
            assert len(entry) in (9, 10)
            parameters = [int_value(item) for item in frames(entry[4])]
            assert int_value(entry[2]) == len(parameters)
            self.nodes = []
            value = {'id': int_value(entry[0]), 'return-type': int_value(entry[1]),
                'param-count': int_value(entry[2]), 'local-count': int_value(entry[3]),
                'param-types': parameters, 'script': self.expression(entry[5]),
                'hidden-count': int_value(entry[6]), 'entry-kind': int_value(entry[7])}
            assert len(entry) == 9 + bool_value(entry[8])
            if len(entry) == 10:
                value['debug'] = self.debug(entry[9], files)
            self.functions.append((value, self.nodes))
            legacy['f'].append(value)
        legacy['debug-files'] = files
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

        def compile_source(name, source, options=()):
            src, abd, ast, executable = [work / (name + suffix) for suffix in ('.azs', '.abd', '.ast.json', '.exec.json')]
            src.write_text(source, encoding='utf-8')
            compiler = ['java', '-cp', args.classpath, 'azertia.Main']
            run(compiler + ['compile', src, '-o', abd, '--ast', ast, '--exec-json', executable, *options])
            roundtrip = work / (name + '.roundtrip.abd')
            run(compiler + ['compile-json', ast, '-o', roundtrip, *options])
            assert abd.read_bytes() == roundtrip.read_bytes(), 'readable AST roundtrip'
            debug = json.loads(executable.read_text(encoding='utf-8'))
            assert debug['exec-version'] == EXEC_VERSION
            assert ('-g' in options) == bool(debug['debug-files']) == all('debug' in item for item in debug['f'])

            def inspect(value):
                if isinstance(value, dict):
                    if value.get('t') == 0 and 'c' in value:
                        assert type(value['c']) is int and 3 <= value['c'] <= LAST_OPCODE, value
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
        baseline_opcodes = set(range(36)) | {52}  # Nonvirtual constructor/destructor phases still clear their method table.
        assert reader.opcodes == baseline_opcodes, ('baseline opcodes', baseline_opcodes - reader.opcodes, reader.opcodes - baseline_opcodes)
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
        assert legacy['debug-files'] == [] and not any('debug' in f for f in legacy['f'])

        # -g appends debug records; instructions and behavior stay the same.
        located, _, located_json = compile_source('coverage-debug', SOURCE, ['-g'])
        located_reader = WireReader()
        located_legacy = located_reader.read(located.read_bytes())
        assert encode_compact_module(located_legacy) == located.read_bytes(), 'independent debug record reconstruction'
        assert located_legacy['debug-files'] == located_json['debug-files'] == ['coverage-debug.azs']
        assert [f['debug'] for f in located_legacy['f']] == [f['debug'] for f in located_json['f']]
        assert [{k: v for k, v in f.items() if k != 'debug'} for f in located_legacy['f']] == legacy['f']
        names = {f['debug']['name'] for f in located_legacy['f']}
        assert {'main', 'empty', 'Box::Box', 'Box::~Box', 'Box::self', 'Box::get', 'Pair::Pair', '<scoped Box>'} <= names, names
        division_line = next(number for number, text in enumerate(SOURCE.split('\n'), 1) if 'a/b' in text)
        division_column = SOURCE.split('\n')[division_line - 1].index('a/b') + 2
        main_function, main_nodes = next(item for item in located_reader.functions if item[0]['id'] == MAIN)
        divisions = [main_nodes[int(item.split(':')[0])] for item in main_function['debug']['locations']
                     if item.split(':')[2:] == [str(division_line), str(division_column)]]
        assert any(isinstance(node, dict) and node.get('c') == 'divide' for node in divisions), divisions
        run([args.runner, located], EXPECTED)
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

        # New operators have an independent compiler/wire roundtrip in addition
        # to the unchanged baseline opcode corpus above.
        bitwise_source = '''int main(){int a=12;int b=10;
            print(1 << 31);print(-a >> 1);print(-a >>> 1);
            return (a & b) + (a | b) + (a ^ b) + (~a);}'''
        bitwise, _, _ = compile_source('bitwise-coverage', bitwise_source)
        bitwise_reader = WireReader()
        bitwise_view = bitwise_reader.read(bitwise.read_bytes())
        assert set(range(43, 49)) <= bitwise_reader.opcodes, bitwise_reader.opcodes
        assert encode_compact_module(bitwise_view) == bitwise.read_bytes(), 'independent bitwise reconstruction'
        run([args.runner, bitwise], '-2147483648\n-6\n2147483642\n15\n')

        conversion_source = '''int main(){address p=alloc(1);def(raw,p);
            print(address(raw)==p);print(string(p)==(""+p));
            print(float("1.25"));print(double("2.5"));mem_free(p);return int("42");}'''
        converted, _, _ = compile_source('conversion-coverage', conversion_source)
        conversion_reader = WireReader()
        conversion_view = conversion_reader.read(converted.read_bytes())
        assert 49 in conversion_reader.opcodes, conversion_reader.opcodes
        assert encode_compact_module(conversion_view) == converted.read_bytes(), 'independent conversion reconstruction'
        run([args.runner, converted], 'true\ntrue\n1.25\n2.5\n42\n')

        selection_source = '''int main(){boolean flag=true;
            return flag ? (false ? 1 : 42) : 1/0;}'''
        selected, _, _ = compile_source('select-coverage', selection_source)
        selection_reader = WireReader()
        selection_view = selection_reader.read(selected.read_bytes())
        assert 50 in selection_reader.opcodes, selection_reader.opcodes
        assert encode_compact_module(selection_view) == selected.read_bytes(), 'independent select reconstruction'
        run([args.runner, selected], '42\n')

        # A grouped 160-label section must stay shallow enough for exec v10:
        # the switch lowering uses a balanced disjunction of comparisons.
        labels = ''.join(f'case {value}:' for value in range(160))
        switch_source = 'int main(){int result=0;switch(159){' + labels + 'result=42;break;default:result=0;}return result;}'
        switched, _, switched_json = compile_source('switch-coverage', switch_source)
        switch_reader = WireReader()
        switch_view = switch_reader.read(switched.read_bytes())
        assert {19, 27, 28, 29} <= switch_reader.opcodes, switch_reader.opcodes
        assert switched_json['exec-version'] == 10 and max(switch_reader.opcodes) <= 50
        assert encode_compact_module(switch_view) == switched.read_bytes(), 'independent lowered-switch reconstruction'
        run([args.runner, switched], '42\n')

        checked_source = '''#namespace 1234
            class Item{int value=7;}Item* produce():0070{return new Item();}
            int main(){Item* item=reflect_invoke_function<Item*>(305397872);int result=item.value;delete item;return result;}'''
        checked, _, _ = compile_source('checked-reflection-coverage', checked_source)
        checked_reader = WireReader();checked_view = checked_reader.read(checked.read_bytes())
        assert 51 in checked_reader.opcodes, checked_reader.opcodes
        assert encode_compact_module(checked_view) == checked.read_bytes(), 'independent checked-reference reconstruction'
        run([args.runner, checked], '7\n')

        virtual_source = '''class Base{int value=7;virtual int read(){return value;}}
            class Child:Base{override int read(){return Base::read()+100;}}
            <T extends Base*> int through(T p){return p.read();}
            int main(){Base* p=new Child();int result=through(p);delete p;return result;}'''
        virtual_abd, _, _ = compile_source('virtual-coverage', virtual_source)
        virtual_reader = WireReader();virtual_view = virtual_reader.read(virtual_abd.read_bytes())
        assert {52, 53} <= virtual_reader.opcodes, virtual_reader.opcodes
        assert encode_compact_module(virtual_view) == virtual_abd.read_bytes(), 'independent virtual reconstruction'
        run([args.runner, virtual_abd], '107\n')

        minimum = module([function(block(returning(constant(42))))])
        checks = 0

        def execute_wire(name, root_fields=None, data=None, expected=None, invalid=False, error=None):
            nonlocal checks
            path = work / (name + '.abd')
            path.write_bytes(data if data is not None else frame(stack(*(root_fields or minimum))))
            result = subprocess.run([str(args.runner), str(path)], capture_output=True, text=True, timeout=10)
            if invalid:
                assert result.returncode == 1 and result.stderr.startswith('AzScript error:'), (name, result.returncode, result.stdout, result.stderr)
                assert not any(text in result.stderr for text in ('AddressSanitizer', 'UndefinedBehaviorSanitizer', 'runtime error:')), (name, result.stderr)
                if error is not None:
                    assert error in result.stderr, (name, error, result.stderr)
            else:
                assert result.returncode == 0 and result.stdout == expected and not result.stderr, (name, result.stdout, result.stderr)
            checks += 1

        if args.host:
            # Structural specimens are loaded and flushed without invocation;
            # their types need only be valid wire/link metadata, not arithmetic.
            boundary_id, factory_id, generic_id, plain_id = 0x34560002, 0x34560003, 0x34560004, 0x34560005
            scalar_context = {'abi': 0, 'kind': 0}
            deep_context = scalar_context
            for _ in range(59):
                deep_context = {'abi': 8, 'kind': 3, 'width': 1, 'factory': factory_id, 'contexts': [deep_context]}
            auxiliary = [function(expression(32, integer(1)), factory_id, return_type=8, hidden_count=1, entry_kind=1),
                         function(constant(0), generic_id, params=[7], hidden_count=1),
                         function(constant(0), plain_id, params=[7])]
            entry_empty = stack(integer(plain_id), encode_contexts([]))
            entry_fixed = stack(integer(generic_id), encode_contexts([deep_context]))
            specimens = [
                ('scalar-payload', constant(1), 1),
                ('call-argument-list', expression(2, integer(plain_id), stack(constant(Address(0))), stack()), 3),
                ('fixed-context-empty-lists', expression(36, encode_context(scalar_context)), 2),
                ('factory-context-list', expression(36, encode_context(deep_context)), 120),
                ('call-context-list', expression(2, integer(generic_id), stack(constant(Address(0))), encode_contexts([deep_context])), 121),
                ('method-entry-empty-contexts', expression(52, constant(Address(0)), stack(entry_empty)), 3),
                ('method-entry-fixed-context', expression(52, constant(Address(0)), stack(entry_fixed)), 123),
                ('virtual-argument-list', expression(53, integer(0), integer(0), stack(constant(Address(0)))), 3),
            ]
            for label, specimen, height in specimens:
                for deepest in (128, 129):
                    body = specimen
                    for _ in range(deepest - 4 - height):
                        body = expression(26, body)
                    binary = work / f'depth-boundary-{label}-{deepest}.abd'
                    binary.write_bytes(frame(stack(*module([function(body, boundary_id, return_type=5, params=[7], hidden_count=1), *auxiliary]))))
                    result = subprocess.run([str(args.host), str(binary), 'flush'], cwd=ROOT, capture_output=True, text=True, timeout=10)
                    if deepest == 128:
                        assert result.returncode == 0 and not result.stdout and not result.stderr, (label, deepest, result.stdout, result.stderr)
                    else:
                        assert result.returncode != 0 and 'nesting' in result.stderr.lower(), (label, deepest, result.stdout, result.stderr)
                    checks += 1

        execute_wire('independent-minimum', expected='42\n')
        for name, value, kind, expected in [('float', Scalar(FLOAT, struct.pack('<f', 1.25)), 2, '1.25\n'),
                ('double', Scalar(DOUBLE, struct.pack('<d', 2.5)), 3, '2.5\n'),
                ('string', '中文\0wire', 1, '中文\0wire\n'), ('bool', True, 4, 'true\n'),
                ('address', Address(2**64-1), 7, '18446744073709551615\n'),
                ('void', b'\xff', 5, '')]:
            execute_wire('independent-' + name, module([function(block(returning(constant(value))), return_type=kind)]), expected=expected)
        execute_wire('independent-void-return', module([function(block(returning()), return_type=5)]), expected='')

        bitwise_cases = [(43, 12, 10, 8), (43, -1, -2147483648, -2147483648),
                         (44, 12, 10, 14), (44, -2147483648, 1, -2147483647),
                         (45, 12, 10, 6), (45, -1, -2147483648, 2147483647),
                         (46, 1, 31, -2147483648), (46, 2147483647, 1, -2),
                         (47, -8, 2, -2), (47, -1, 31, -1),
                         (48, -8, 2, 1073741822), (48, -1, 31, 1), (48, -1, 0, -1)]
        for index, (opcode, left, right, expected) in enumerate(bitwise_cases):
            body = returning(expression(opcode, constant(left), constant(right)))
            fields = module([function(body)])
            payload = frame(stack(*fields))
            parsed = WireReader().read(payload)
            assert encode_compact_module(parsed) == payload, ('bitwise independent roundtrip', opcode)
            execute_wire('independent-bitwise-' + str(index), fields, expected=str(expected) + '\n')
        for opcode in range(43, 49):
            for side, operands in [('left', (True, 1)), ('right', (1, 'text'))]:
                body = returning(expression(opcode, *(constant(value) for value in operands)))
                execute_wire(f'bitwise-type-{opcode}-{side}', module([function(body)]),
                             invalid=True, error='operands must be int')
        for opcode in range(46, 49):
            for count in (-2147483648, -1, 32, 2147483647):
                body = returning(expression(opcode, constant(-1), constant(count)))
                execute_wire(f'shift-count-{opcode}-{count}', module([function(body)]), invalid=True,
                             error=f'Shift count must be between 0 and 31, got {count}')

        # The expected result is checked only after check_type validates the
        # exact ABI tag; numeric equality alone would accept an incorrect tag.
        def f32(value):
            return Scalar(FLOAT, struct.pack('<f', value))

        def f64(value):
            return Scalar(DOUBLE, struct.pack('<d', value))

        conversion_cases = [(0, 7, 7), (0, f32(3.75), 3), (0, f64(-3.75), -3),
                            (0, f64(2147483647.9), 2147483647), (0, f64(-2147483648.9), -2147483648),
                            (0, '+7', 7), (0, '-2147483648', -2147483648),
                            (2, 16777217, f32(16777216)), (2, f32(1.25), f32(1.25)),
                            (2, f64(1.25), f32(1.25)), (2, '1.25e1', f32(12.5)),
                            (2, f64(1e-300), f32(0)), (2, '1e-1000', f32(0)), (2, '1e-45', f32(1e-45)),
                            (3, 7, f64(7)), (3, f32(1.25), f64(1.25)), (3, f64(1.25), f64(1.25)),
                            (3, '.5e+1', f64(5)), (3, '1e-1000', f64(0)), (3, '1e-320', f64(1e-320)),
                            (1, 7, '7'), (1, f32(1.25), '1.25'), (1, f64(2.5), '2.5'),
                            (1, Address(2**64-1), '18446744073709551615'), (1, 'a\0😀', 'a\0😀'),
                            (7, Address(2**64-1), Address(2**64-1))]
        for index, (target, value, expected) in enumerate(conversion_cases):
            converted_value = expression(49, integer(target), constant(value))
            context = encode_context({'abi': target, 'kind': 1 if target == 7 else 0})
            checked_value = expression(39, converted_value, context)
            equal = expression(19, checked_value, constant(expected))
            fields = module([function(returning(equal), return_type=4)])
            payload = frame(stack(*fields))
            view = WireReader().read(payload)
            assert encode_compact_module(view) == payload, ('conversion independent roundtrip', target)
            execute_wire('independent-conversion-' + str(index), fields, expected='true\n')
        for target in (0, 1, 2, 3, 7):
            for label, value in [('boolean', True), ('void', b'\xff')]:
                body = returning(expression(49, integer(target), constant(value)))
                execute_wire(f'conversion-{target}-{label}', module([function(body, return_type=target)]),
                             invalid=True, error='Cannot convert')
        for target, value in [(7, 0), (7, '1'), (0, Address(1)), (2, Address(1)), (3, Address(1)),
                              (0, f64(2147483648)), (0, f64(-2147483649)), (2, f64(1e40)),
                              (0, '2147483648'), (2, '1e40'), (3, '1e400')]:
            body = returning(expression(49, integer(target), constant(value)))
            execute_wire(f'conversion-invalid-{target}-{len(str(value))}-{checks}',
                         module([function(body, return_type=target)]), invalid=True, error='Cannot convert')
        for target in (0, 2, 3):
            for index, value in enumerate(['', '+', ' 1', '1 ', '0x10', '1f', 'nan', 'inf', '1junk', '1\0😀']):
                body = returning(expression(49, integer(target), constant(value)))
                execute_wire(f'conversion-lexical-{target}-{index}', module([function(body, return_type=target)]),
                             invalid=True, error='Cannot convert string')

        dead_error = expression(15, constant(1), constant(0))
        for name, condition, yes, no, expected in [
                ('true', constant(True), constant(42), dead_error, '42\n'),
                ('false', constant(False), dead_error, constant(43), '43\n'),
                ('right-associated', constant(False), constant(1),
                 expression(50, constant(True), constant(2), constant(3)), '2\n')]:
            fields = module([function(returning(expression(50, condition, yes, no)))])
            payload = frame(stack(*fields))
            parsed = WireReader().read(payload)
            assert encode_compact_module(parsed) == payload, ('select independent roundtrip', name)
            execute_wire('independent-select-' + name, fields, expected=expected)
        for index, condition in enumerate([0, 1, Scalar(DOUBLE, struct.pack('<d', 1.0)), 'yes', Address(1), b'\xff']):
            body = returning(expression(50, constant(condition), constant(2), constant(3)))
            execute_wire('select-condition-' + str(index), module([function(body)]), invalid=True,
                         error='Condition must be boolean')

        # exec v11 sequences run their statements in the enclosing scope and
        # yield the value; their statement temporaries end per statement.
        local = expression(3, integer(0))
        declare = expression(4, integer(0), integer(0), b'\1', constant(1))
        printed = lambda text: expression(2, integer(0x0abd0000), stack(constant(text)), stack())
        loop = expression(28, expression(18, local, constant(5)),
                          block(expression(5, integer(0), expression(12, local, constant(1))),
                                expression(27, expression(19, local, constant(3)), block(expression(29)), b'\0')))
        nested_declaration = block(expression(4, integer(1), integer(0), b'\1', constant(40)),
                                   expression(5, integer(0), expression(12, local, expression(3, integer(1)))))
        for name, statements, value, expected in [
                ('order', [printed('a'), expression(5, integer(0), constant(41))], expression(12, local, constant(1)), 'a\n42\n'),
                ('empty', [], constant(7), '7\n'),
                ('loop', [loop], local, '3\n'),
                ('nested-block', [nested_declaration], local, '41\n'),
                ('nested', [expression(5, integer(0), expression(54, stack(printed('b')), constant(9)))],
                 expression(13, local, constant(2)), 'b\n7\n')]:
            sequence = expression(54, stack(*statements), value)
            body = block(declare, returning(expression(12, sequence, constant(0))))
            fields = module([function(body, locals_count=2)])
            payload = frame(stack(*fields))
            assert encode_compact_module(WireReader().read(payload)) == payload, ('sequence independent roundtrip', name)
            execute_wire('sequence-' + name, fields, expected=expected)
        for name, statement, error in [
                ('declaration', declare, 'declare a variable'),
                ('break', expression(29), 'break or continue'),
                ('return', returning(constant(1)), 'cannot return'),
                ('new-block', block(expression(32, integer(1))), 'own, release or clean up')]:
            body = block(declare, expression(28, constant(True), block(returning(expression(54, stack(statement), local)))))
            execute_wire('sequence-reject-' + name, module([function(body, locals_count=1)]), invalid=True, error=error)
        # Private functions (entry kind 2) are reachable by their module's direct calls only.
        helper = 0x0FFFFF00
        call_helper = expression(2, integer(helper), stack(constant(41)), stack())
        fields = module([function(returning(call_helper)),
                         function(returning(expression(12, expression(3, integer(0)), constant(1))), helper, params=[0], entry_kind=2)])
        payload = frame(stack(*fields))
        assert encode_compact_module(WireReader().read(payload)) == payload, 'private function independent roundtrip'
        execute_wire('private-function-call', fields, expected='42\n')
        execute_wire('private-main', module([function(returning(constant(1)), entry_kind=2)]), invalid=True,
                     error='cannot be private')
        execute_wire('entry-kind-3', module([function(returning(constant(1))),
                                             function(returning(constant(1)), helper, entry_kind=3)]), invalid=True,
                     error='Invalid function generic ABI')
        # Wrapping arithmetic wraps int32 and otherwise behaves as the checked operator.
        for opcode, left, right, kind, expected in [
                (55, 2147483647, 1, 0, '-2147483648'), (56, -2147483648, 1, 0, '2147483647'),
                (57, 65536, 65536, 0, '0'), (57, -2147483648, -1, 0, '-2147483648'), (55, 2, 3, 0, '5'),
                (55, 'a', 'b', 1, 'ab'), (57, f64(1.5), f64(2), 3, '3')]:
            body = returning(expression(opcode, constant(left), constant(right)))
            fields = module([function(body, return_type=kind)])
            payload = frame(stack(*fields))
            assert encode_compact_module(WireReader().read(payload)) == payload, ('wrapping independent roundtrip', opcode)
            execute_wire(f'wrapping-{opcode}-{checks}', fields, expected=expected + '\n')
        for opcode, left, right, kind, error in [(55, f32(3e38), f32(3e38), 2, 'Float overflow'), (56, 'a', 'b', 1, ''),
                                                 (57, True, 1, 0, '')]:
            body = returning(expression(opcode, constant(left), constant(right)))
            execute_wire(f'wrapping-error-{opcode}-{checks}', module([function(body, return_type=kind)]), invalid=True,
                         error=error or None)

        # Builtins retain the ordinary CALL wire record and strict UTF-8
        # constants, including NUL and a supplementary Unicode scalar.
        unicode_text = 'A中😀\0Z'
        string_calls = [(0x0abd000b, [unicode_text], 0, '5\n'),
                        (0x0abd000c, [unicode_text], 0, '10\n'),
                        (0x0abd000d, [unicode_text, 1, 4], 1, '中😀\0\n'),
                        (0x0abd000e, [unicode_text, 2], 1, '😀\n'),
                        (0x0abd000f, [unicode_text, 2], 0, '128512\n'),
                        (0x0abd0010, [128512], 1, '😀\n'), (0x0abd0010, [0], 1, '\0\n'),
                        (0x0abd0011, [unicode_text, '😀'], 0, '2\n'),
                        (0x0abd0011, [unicode_text, '😀', 3], 0, '-1\n'),
                        (0x0abd0011, [unicode_text, '', 5], 0, '5\n'),
                        (0x0abd0012, [unicode_text, 'A中'], 4, 'true\n'),
                        (0x0abd0013, [unicode_text, '\0Z'], 4, 'true\n')]
        for index, (ident, arguments, kind, expected) in enumerate(string_calls):
            call = expression(2, integer(ident), stack(*(constant(value) for value in arguments)), stack())
            fields = module([function(returning(call), return_type=kind)])
            payload = frame(stack(*fields))
            parsed = WireReader().read(payload)
            assert encode_compact_module(parsed) == payload, ('Unicode builtin wire roundtrip', ident)
            execute_wire('independent-string-' + str(index), fields, expected=expected)
        for index, (ident, arguments, error) in enumerate([
                (0x0abd000e, [unicode_text, -1], 'String index -1 is outside length 5'),
                (0x0abd000f, [unicode_text, 5], 'String index 5 is outside length 5'),
                (0x0abd000d, [unicode_text, 4, 3], 'String substring start 4 is after end 3'),
                (0x0abd000d, [unicode_text, 0, 6], 'String index 6 is outside length 5'),
                (0x0abd0011, [unicode_text, '', 6], 'String index 6 is outside length 5'),
                (0x0abd0010, [-1], 'Invalid Unicode code point: -1'),
                (0x0abd0010, [55296], 'Invalid Unicode code point: 55296'),
                (0x0abd0010, [1114112], 'Invalid Unicode code point: 1114112'),
                (0x0abd000b, [1], 'str_length argument 1 must be string'),
                (0x0abd000e, [unicode_text, True], 'str_char_at argument 2 must be int'),
                (0x0abd0011, [unicode_text], 'str_index_of requires 2 or 3 arguments'),
                (0x0abd0012, [unicode_text, '', 'extra'], 'str_starts_with requires 2 arguments')]):
            call = expression(2, integer(ident), stack(*(constant(value) for value in arguments)), stack())
            execute_wire('string-invalid-' + str(index), module([function(returning(call), return_type=0)]),
                         invalid=True, error=error)

        def wire_call(ident, *arguments):
            return expression(2, integer(ident), stack(*arguments), stack())

        pointer = expression(3, integer(0))
        allocation = expression(4, integer(0), integer(7), b'\1', wire_call(0x0abd0014, constant(2)))
        member = lambda offset: expression(9, pointer, integer(offset))
        field = lambda offset: wire_call(0x0abd0006, member(offset))
        bind_manual = expression(10, pointer, b'\0', b'\1', stack())
        object_body = block(allocation, expression(6, field(0), constant(41)), bind_manual,
                            expression(4, integer(1), integer(0), b'\1', field(0)),
                            expression(11, pointer), returning(expression(3, integer(1))))
        object_module = module([function(object_body, locals_count=2)])
        object_bytes = frame(stack(*object_module))
        assert encode_compact_module(WireReader().read(object_bytes)) == object_bytes, 'independent tagged-object/member reconstruction'
        execute_wire('object-allocator-member-access', object_module, expected='41\n')
        for ident, name in [(0x0abd0006, 'mem_get'), (0x0abd0002, 'mem_free'),
                            (0x0abd0004, 'make_free'), (0x0abd0005, 'mem_send_up')]:
            for offset in (0, 1):
                raw_pointer = expression(12, pointer, constant(offset))
                body = block(allocation, wire_call(ident, raw_pointer), returning(constant(0)))
                execute_wire(f'object-construction-guard-{name}-{offset}', module([function(body, locals_count=1)]),
                             invalid=True, error=name + ' cannot access object storage; use typed member access')
        raw_allocation = expression(4, integer(0), integer(7), b'\1', wire_call(0x0abd0003, constant(1)))
        execute_wire('object-address-rejects-raw-allocation', module([function(block(raw_allocation, returning(field(0))), locals_count=1)]),
                     invalid=True, error='Object member access requires object storage')
        escaped_member = expression(4, integer(1), integer(7), b'\1', member(1))
        body = block(allocation, escaped_member, returning(wire_call(0x0abd0006, expression(3, integer(1)))))
        execute_wire('object-member-address-cannot-launder-raw-read', module([function(body, locals_count=2)]),
                     invalid=True, error='mem_get cannot access object storage; use typed member access')
        literal = expression(4, integer(0), integer(8), b'\1', expression(32, integer(1)))
        literal_pointer = expression(33, pointer)
        execute_wire('literal-block-rejects-raw-read', module([function(block(literal, returning(wire_call(0x0abd0006, literal_pointer))), locals_count=1)]),
                     invalid=True, error='mem_get cannot access object storage; use typed member access')
        for label, arguments in [('missing', []), ('extra', [constant(1), constant(2)]),
                                 ('wrong-type', [constant(True)]), ('negative', [constant(-1)])]:
            execute_wire('object-allocator-' + label, module([function(returning(wire_call(0x0abd0014, *arguments)), return_type=7)]), invalid=True)

        def set_methods(value, entries):
            return expression(52, value, stack(*(stack(integer(ident), encode_contexts(contexts)) for ident, contexts in entries)))

        def virtual(slot, kind, *arguments):
            return expression(53, integer(slot), integer(kind), stack(*arguments))

        method_id = 0x12340071
        method = function(returning(expression(12, expression(3, integer(1)), constant(100))), method_id, params=[7, 0])
        attach = set_methods(pointer, [(method_id, [])])
        save_answer = expression(4, integer(1), integer(0), b'\1', virtual(0, 0, pointer, constant(7)))
        body = block(allocation, bind_manual, attach, save_answer, expression(11, pointer), returning(expression(3, integer(1))))
        fields = module([function(body, locals_count=2), method])
        payload = frame(stack(*fields));assert encode_compact_module(WireReader().read(payload)) == payload
        execute_wire('virtual-method-dispatch', fields, expected='107\n')
        literal_method = function(returning(constant(101)), method_id, params=[7])
        body = block(literal, set_methods(literal_pointer, [(method_id, [])]), returning(virtual(0, 0, literal_pointer)))
        execute_wire('virtual-literal-dispatch', module([function(body, locals_count=1), literal_method]), expected='101\n')
        contextual = function(returning(expression(36, encode_context({'ref': 0}))), method_id, params=[7], hidden_count=1)
        body = block(allocation, bind_manual, set_methods(pointer, [(method_id, [{'abi': 0, 'kind': 0}])]),
                     expression(4, integer(1), integer(0), b'\1', virtual(0, 0, pointer)), expression(11, pointer), returning(expression(3, integer(1))))
        execute_wire('virtual-captured-context', module([function(body, locals_count=2), contextual]), expected='0\n')
        for label, setup, invocation in [
                ('null', [], virtual(0, 0, constant(Address(0)), constant(1))),
                ('no-table', [allocation, bind_manual], virtual(0, 0, pointer, constant(1))),
                ('cleared-table', [allocation, bind_manual, attach, set_methods(pointer, [])], virtual(0, 0, pointer, constant(1))),
                ('freed', [allocation, bind_manual, attach, expression(11, pointer)], virtual(0, 0, pointer, constant(1))),
                ('slot-out-of-range', [allocation, bind_manual, attach], virtual(1, 0, pointer, constant(1))),
                ('return-type', [allocation, bind_manual, attach], virtual(0, 1, pointer, constant(1))),
                ('missing-this', [], virtual(0, 0)),
                ('wrong-this', [], virtual(0, 0, constant(7))),
                ('raw-storage', [raw_allocation], set_methods(pointer, [(method_id, [])]))]:
            body = block(*setup, invocation)
            execute_wire('virtual-' + label, module([function(body, return_type=5, locals_count=1), method]), invalid=True)
        # guard_method is true exactly when call_virtual would enter the named
        # context-free method, and false, never an error, otherwise.
        def guard(value, slot=0, ident=method_id):
            return expression(58, value, integer(slot), integer(ident))

        other_id = method_id + 2
        other = function(returning(constant(5)), other_id, params=[7, 0])
        for label, setup, check, expected in [
                ('bound', [allocation, bind_manual, attach], guard(pointer), 'true'),
                ('literal', [literal, set_methods(literal_pointer, [(method_id, [])])], guard(literal_pointer), 'true'),
                ('other-method', [allocation, bind_manual, set_methods(pointer, [(other_id, [])])], guard(pointer), 'false'),
                ('null', [], guard(constant(Address(0))), 'false'),
                ('no-table', [allocation, bind_manual], guard(pointer), 'false'),
                ('cleared-table', [allocation, bind_manual, attach, set_methods(pointer, [])], guard(pointer), 'false'),
                ('freed', [allocation, bind_manual, attach, expression(11, pointer)], guard(pointer), 'false'),
                ('slot-out-of-range', [allocation, bind_manual, attach], guard(pointer, 1), 'false'),
                ('wrong-this', [], guard(constant(7)), 'false'),
                ('raw-storage', [raw_allocation], guard(pointer), 'false'),
                ('interior', [allocation, bind_manual, attach], guard(expression(12, pointer, constant(1))), 'false')]:
            body = block(*setup, returning(check))
            fields = module([function(body, return_type=4, locals_count=1), method, other])
            payload = frame(stack(*fields))
            assert encode_compact_module(WireReader().read(payload)) == payload, ('guard independent roundtrip', label)
            execute_wire('guard-method-' + label, fields, expected=expected + '\n')
        contextual_guard = block(allocation, bind_manual, set_methods(pointer, [(method_id, [{'abi': 0, 'kind': 0}])]),
                                 returning(guard(pointer)))
        execute_wire('guard-method-captured-context', module([function(contextual_guard, return_type=4, locals_count=1), contextual]),
                     invalid=True, error='Generic context count does not match function')
        execute_wire('guard-method-missing-target', module([function(returning(guard(constant(Address(0)), 0, method_id + 1)), return_type=4)]),
                     invalid=True, error='Referenced script function is missing')
        for label, target, captured in [
                ('missing-target', method_id + 1, []),
                ('native-target', 0x0abd0000, []),
                ('hidden-mismatch', method_id, [{'abi': 0, 'kind': 0}])]:
            body = block(allocation, bind_manual, set_methods(pointer, [(target, captured)]))
            execute_wire('virtual-binding-' + label, module([function(body, return_type=5, locals_count=1), method]), invalid=True)
        for label, target in [('non-address-first', function(returning(constant(1)), method_id, params=[0])),
                              ('missing-first', function(returning(constant(1)), method_id)),
                              ('internal-target', function(returning(constant(1)), method_id, params=[7], entry_kind=1))]:
            body = block(allocation, bind_manual, attach)
            execute_wire('virtual-binding-' + label, module([function(body, return_type=5, locals_count=1), target]), invalid=True)

        def checked_reference(value, context, kinds):
            return expression(51, value, encode_context(context), stack(*(integer(kind) for kind in kinds)))

        pointer_context = {'abi': 7, 'kind': 2, 'width': 1}
        null_check = checked_reference(constant(Address(0)), pointer_context, [0, 1])
        execute_wire('check-object-null-pointer', module([function(returning(null_check), return_type=7)]), expected='0\n')
        tagged_setup = [allocation, expression(6, field(0), constant(7)), expression(6, field(1), constant('field')), bind_manual]
        good_check = checked_reference(pointer, pointer_context, [0, 1])
        preserve = expression(4, integer(1), integer(4), b'\1', expression(19, good_check, pointer))
        body = block(*tagged_setup, preserve, expression(11, pointer), returning(expression(3, integer(1))))
        fields = module([function(body, return_type=4, locals_count=2)])
        payload = frame(stack(*fields));assert encode_compact_module(WireReader().read(payload)) == payload
        execute_wire('check-object-registered-pointer', fields, expected='true\n')
        for label, setup, context, kinds in [
                ('raw', [raw_allocation], pointer_context, [0, 1]),
                ('unregistered', tagged_setup[:-1], pointer_context, [0, 1]),
                ('wrong-tag', tagged_setup, pointer_context, [1, 0]),
                ('too-small', tagged_setup, pointer_context, [0, 1, 0]),
                ('freed', tagged_setup + [expression(11, pointer)], pointer_context, [0, 1])]:
            body = block(*setup, returning(checked_reference(pointer, context, kinds)))
            execute_wire('check-object-' + label, module([function(body, return_type=7, locals_count=1)]),
                         invalid=True, error='Object check failed:')
        value_context = {'abi': 8, 'kind': 3, 'width': 1}
        live_value = expression(4, integer(0), integer(8), b'\1', expression(32, integer(1)))
        live_member = wire_call(0x0abd0006, expression(9, expression(33, pointer), integer(0)))
        body = block(live_value, expression(6, live_member, constant(17)),
                     checked_reference(pointer, value_context, [0]), returning(live_member))
        execute_wire('check-object-literal-value', module([function(body, locals_count=1)]), expected='17\n')
        for label, context, kinds in [('value-width', {'abi': 8, 'kind': 3, 'width': 2}, [0, 0]),
                                      ('value-tag', value_context, [1])]:
            body = block(live_value, expression(6, live_member, constant(17)), checked_reference(pointer, context, kinds))
            execute_wire('check-object-' + label, module([function(body, return_type=5, locals_count=1)]), invalid=True, error='Object check failed:')
        buffer_context = {'abi': 8, 'kind': 4, 'width': 1, 'element': {'abi': 0, 'kind': 0}}
        new_buffer = expression(40, encode_context({'abi': 0, 'kind': 0}))
        good_buffer = checked_reference(new_buffer, buffer_context, [])
        execute_wire('check-object-buffer', module([function(block(good_buffer, returning()), return_type=5)]), expected='')
        wrong_buffer = {**buffer_context, 'element': {'abi': 1, 'kind': 0}}
        execute_wire('check-object-buffer-context', module([function(block(checked_reference(new_buffer, wrong_buffer, [])), return_type=5)]),
                     invalid=True, error='Object check failed:')

        mutations = [('root-missing', minimum[:-1]), ('root-extra', minimum + [b''])]
        for index, value, label in [(0, b'AZSCRIPX', 'magic'), (1, integer(5), 'version'), (1, integer(6), 'address-only-version'), (1, integer(7), 'literal-only-version'), (1, integer(8), 'v8-version'), (1, integer(9), 'previous-version'),
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
        # Debug files and records: [has_debug, [name, file, line, column, quads]], nodes 0 block, 1 return, 2 constant.
        files = stack(b'f.azs')
        good_debug = debug_record('main', 0, 1, 5, [(1, 0, 1, 7)])
        changed = minimum.copy()
        changed[7] = stack(function(block(returning(constant(42))), debug=good_debug))
        changed[10] = files
        execute_wire('debug-valid', changed, expected='42\n')
        for label, debug_files, record in [
                ('debug-file-empty', stack(b''), None), ('debug-file-control', stack(b'a\nb'), None),
                ('debug-file-c1', stack('a\u0085'.encode('utf-8')), None), ('debug-file-bidi', stack('a\u202e'.encode('utf-8')), None),
                ('debug-file-duplicate', stack(b'a', b'a'), None), ('debug-file-utf8', stack(b'\xff'), None),
                ('debug-file-width', b'\0', None),
                ('debug-without-files', b'', good_debug),
                ('debug-record-short', files, stack(b'main', integer(0), integer(1), integer(5))),
                ('debug-record-long', files, stack(b'main', integer(0), integer(1), integer(5), b'', b'')),
                ('debug-quads-partial', files, stack(b'main', integer(0), integer(1), integer(5), stack(integer(1), integer(0), integer(1)))),
                ('debug-node-count', files, debug_record('main', 0, 1, 5, [(3, 0, 1, 1)])),
                ('debug-node-decreasing', files, debug_record('main', 0, 1, 5, [(2, 0, 1, 1), (1, 0, 1, 1)])),
                ('debug-node-repeated', files, debug_record('main', 0, 1, 5, [(1, 0, 1, 1), (1, 0, 1, 2)])),
                ('debug-node-negative', files, debug_record('main', 0, 1, 5, [(-1, 0, 1, 1)])),
                ('debug-location-file', files, debug_record('main', 0, 1, 5, [(1, 1, 1, 1)])),
                ('debug-location-line', files, debug_record('main', 0, 1, 5, [(1, 0, 0, 1)])),
                ('debug-location-column', files, debug_record('main', 0, 1, 5, [(1, 0, 1, 0)])),
                ('debug-header-file', files, debug_record('main', 1, 1, 5)),
                ('debug-header-partial', files, debug_record('main', 0, 0, 1)),
                ('debug-header-negative', files, debug_record('main', 0, -1, -1)),
                ('debug-name-empty', files, debug_record('')),
                ('debug-name-control', files, debug_record('a\x1b')),
                ('debug-name-utf8', files, debug_record(b'\xc0\x80'))]:
            changed = minimum.copy()
            changed[10] = debug_files
            if record is not None:
                changed[7] = stack(function(block(returning(constant(42))), debug=record))
            mutations.append((label, changed))
        record_fields = frames(function(block(returning(constant(42))), debug=good_debug))
        for label, entry in [('debug-flag-without-record', record_fields[:9]),
                             ('debug-record-without-flag', record_fields[:8] + [b'\0'] + record_fields[9:]),
                             ('debug-flag-width', record_fields[:8] + [b'\2'])]:
            changed = minimum.copy()
            changed[7] = stack(stack(*entry))
            changed[10] = files
            mutations.append((label, changed))
        for label, changed in mutations:
            execute_wire(label, changed, invalid=True)

        bad_expressions = {
            'empty-expression': b'', 'opcode-width': stack(b'\0'),
            'unknown-opcode': expression(LAST_OPCODE + 1), 'negative-opcode': expression(-1),
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
        for opcode in range(43, 49):
            for label, operands in [('empty', ()), ('missing', (constant(1),)),
                                    ('extra', (constant(1), constant(2), constant(3))),
                                    ('malformed-child', (stack(b'\0'), constant(1)))]:
                bad_expressions[f'bitwise-{opcode}-{label}'] = expression(opcode, *operands)
        for label, fields in [('missing', ()), ('target-only', (integer(0),)),
                              ('target-width', (b'\0', constant(1))),
                              ('extra', (integer(0), constant(1), constant(2))),
                              ('malformed-child', (integer(0), stack(b'\0')))]:
            bad_expressions['conversion-' + label] = expression(49, *fields)
        for target in (-1, 4, 5, 6, 8, 2147483647):
            bad_expressions[f'conversion-target-{target}'] = expression(49, integer(target), constant(1))
        for label, fields in [('missing', ()), ('condition-only', (constant(True),)),
                              ('missing-else', (constant(True), constant(1))),
                              ('extra', (constant(True), constant(1), constant(2), constant(3))),
                              ('condition-child', (stack(b'\0'), constant(1), constant(2))),
                              ('then-child', (constant(False), stack(b'\0'), constant(2))),
                              ('else-child', (constant(True), constant(1), stack(b'\0')))]:
            bad_expressions['select-' + label] = expression(50, *fields)
        valid_check_context = encode_context({'abi': 7, 'kind': 2, 'width': 1})
        for label, fields in [('missing', ()), ('value-only', (constant(Address(0)),)),
                              ('missing-slots', (constant(Address(0)), valid_check_context)),
                              ('extra', (constant(Address(0)), valid_check_context, stack(integer(0)), constant(1))),
                              ('slot-width', (constant(Address(0)), valid_check_context, stack(b'\0'))),
                              ('slot-negative', (constant(Address(0)), valid_check_context, stack(integer(-1)))),
                              ('slot-unknown', (constant(Address(0)), valid_check_context, stack(integer(99)))),
                              ('slot-void', (constant(Address(0)), valid_check_context, stack(integer(5)))),
                              ('malformed-value', (stack(b'\0'), valid_check_context, stack(integer(0))))]:
            bad_expressions['check-object-' + label] = expression(51, *fields)
        for count in (0, 1, 3):
            context = encode_context({'abi': 8, 'kind': 3, 'width': 2})
            bad_expressions[f'check-object-value-slot-count-{count}'] = expression(51, constant(Address(0)), context, stack(*(integer(0) for _ in range(count))))
        good_entry = stack(integer(0x12340071), encode_contexts([]))
        for label, fields in [('missing', ()), ('address-only', (constant(Address(0)),)),
                              ('extra', (constant(Address(0)), stack(good_entry), constant(1))),
                              ('entry-missing-contexts', (constant(Address(0)), stack(stack(integer(0x12340071))))),
                              ('entry-extra', (constant(Address(0)), stack(stack(integer(0x12340071), b'', b'')))),
                              ('id-width', (constant(Address(0)), stack(stack(b'\0', b'')))),
                              ('bad-context', (constant(Address(0)), stack(stack(integer(0x12340071), stack(stack(integer(9))))))),
                              ('bad-child', (stack(b'\0'), stack(good_entry)))]:
            bad_expressions['set-methods-' + label] = expression(52, *fields)
        for label, fields in [('missing', ()), ('slot-only', (integer(0),)),
                              ('missing-args', (integer(0), integer(0))),
                              ('extra', (integer(0), integer(0), b'', b'')),
                              ('slot-width', (b'\0', integer(0), b'')),
                              ('negative-slot', (integer(-1), integer(0), b'')),
                              ('type-width', (integer(0), b'\0', b'')),
                              ('unknown-type', (integer(0), integer(99), b'')),
                              ('bad-argument', (integer(0), integer(0), stack(stack(b'\0'))))]:
            bad_expressions['call-virtual-' + label] = expression(53, *fields)
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
            # The same program with debug records is other module bytes, so a snapshot cannot cross builds either way.
            located_jni, _, _ = compile_source('jni-debug', jni_source, ['-g'])
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
   AbdInvoker.destroyScript();
   AbdInvoker.loadScript(new File(args[7]));
   AbdInvoker.flush();
   try {AbdInvoker.loadStatus(saved);throw new AssertionError("snapshot restored into a build with debug records");}
   catch(IllegalArgumentException expected) {
    if(!expected.getMessage().contains("Snapshot module manifest differs")) throw new AssertionError(expected.getMessage());
   }
   File debugSaved=new File(args[8]);
   if(!AbdInvoker.saveStatus(debugSaved)) throw new AssertionError("save from the build with debug records");
   AbdInvoker.destroyScript();
   AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.flush();
   try {AbdInvoker.loadStatus(debugSaved);throw new AssertionError("debug snapshot restored into a build without debug records");}
   catch(IllegalArgumentException expected) {
    if(!expected.getMessage().contains("Snapshot module manifest differs")) throw new AssertionError(expected.getMessage());
   }
   System.out.println("Compact JNI snapshot passed");
  } finally {AbdInvoker.close();}
 }
}''', encoding='utf-8')
            run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', args.bridge, '-d', work, java])
            saved = work / 'opaque.snapshot.abd'
            ids = [int(tree['abstract'][name], 16) for name in ('set', 'get', 'make', 'inspect')]
            run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                 '-cp', os.pathsep.join((str(work), str(args.bridge))), 'CompactSnapshot', native, saved, other, *ids, located_jni,
                 work / 'debug.snapshot.abd'],
                'Compact JNI snapshot passed\n')
            snapshot = typed_decode(MAP, frames(saved.read_bytes())[0])
            assert snapshot['snapshot version'].value() == 10
            assert snapshot['module manifest'][0]['bytes'].payload == native.read_bytes()
            assert [item.value() for item in snapshot['variable global']] == [41]
            assert len(snapshot['objects']) == 1
            assert all(item['methods'] == [] for item in snapshot['objects'])
            # The pointer object's literal-object field is saved as a slot block with its address.
            assert any(isinstance(item, dict) and 'object' in item and len(item['slots']) == 1 for item in snapshot['heap'])

        compact_size = len(abd.read_bytes())
        print(f'Compact exec: all 36 baseline opcodes, typed constants, source/AST roundtrip, legacy-format rejection; '
              f'{checks} independent wire cases passed. Same source: compact={compact_size} bytes, '
              f'legacy-map={len(old_wire)} bytes ({compact_size / len(old_wire):.1%}).' +
              (' JNI opaque-byte snapshot passed.' if args.library else ''))


if __name__ == '__main__':
    main()
