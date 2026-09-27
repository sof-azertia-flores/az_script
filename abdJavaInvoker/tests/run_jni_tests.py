#!/usr/bin/env python3
"""Dependency-free real JVM/JNI regression suite; fixtures use the documented ABD wire format."""
import argparse
import copy
from dataclasses import dataclass
from pathlib import Path
import shutil
import struct
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tests'))
from compact_exec_end_to_end import encode_compact_module


@dataclass(frozen=True)
class Address:
    bits: int


def frame(payload):
    return struct.pack('<i', len(payload)) + payload


def encode(value):
    if isinstance(value, Address):
        return 0xCE200B, struct.pack('<Q', value.bits)
    if isinstance(value, bool):
        return 0xD00, bytes([value])
    if isinstance(value, int):
        return 3, struct.pack('<i', value)
    if isinstance(value, str):
        return 1, value.encode('utf-8')
    if isinstance(value, bytes):
        return 0xCE2009, value
    if isinstance(value, list):
        parts = [encode(item) for item in value]
        return 0xAD, b''.join(frame(struct.pack('<i', kind)) + frame(payload) for kind, payload in parts)
    if isinstance(value, dict):
        parts = [(key.encode('utf-8'), *encode(item)) for key, item in value.items()]
        return 2, b''.join(frame(key) + frame(struct.pack('<i', kind)) + frame(payload) for key, kind, payload in parts)
    raise TypeError(type(value))


def write_abd(path, value):
    path.write_bytes(encode_compact_module(value) if isinstance(value, dict) and 'f' in value
                     else frame(encode(value)[1]))


def read_abd(path):
    """Decode the small subset used in snapshot metadata assertions."""
    def frames(payload):
        at = 0
        while at < len(payload):
            length, = struct.unpack_from('<i', payload, at)
            assert 0 <= length <= len(payload) - at - 4
            at += 4
            yield payload[at:at + length]
            at += length

    def decode(kind, payload):
        if kind == 0xCE200B:
            assert len(payload) == 8
            return Address(struct.unpack('<Q', payload)[0])
        if kind == 3:
            return struct.unpack('<i', payload)[0]
        if kind in (0xCE867, 0xCE1066):
            return struct.unpack('<f' if kind == 0xCE867 else '<d', payload)[0]
        if kind == 0xD00:
            assert payload in (b'\0', b'\1')
            return payload == b'\1'
        if kind == 1:
            return payload.decode('utf-8')
        if kind in (0xCE2009, 0xCE200A):
            return payload
        parts = iter(frames(payload))
        if kind == 0xAD:
            return [decode(struct.unpack('<i', tag)[0], next(parts)) for tag in parts]
        if kind == 2:
            return {key.decode('utf-8'): decode(struct.unpack('<i', next(parts))[0], next(parts)) for key in parts}
        raise AssertionError(f'Unexpected ABD kind {kind}')

    payloads = list(frames(path.read_bytes()))
    assert len(payloads) == 1
    return decode(2, payloads[0])


def var(name):
    return {'t': 0, 'c': 'v', 'v': name}


def ret(value):
    return {'t': 0, 'c': 'r', 'r': value}


def function(ident, kind, body, params=0):
    return {'id': ident + 100, 'return-type': kind, 'param-count': params, 'script': body}


def check_main_entry(args, classes, work):
    """Run the packaged host example in fresh JVMs, including signed ID aliases."""
    fixture = work / 'main-entry.exec.abd'
    write_abd(fixture, {'gvs': 1, 'f': [
        {'id': 0, 'return-type': 5, 'param-count': 0,
         'script': [{'t': 0, 'c': 'vs', 'v': -1, 'val': 42}]},
        {'id': 0x0FFF0000, 'return-type': 0, 'param-count': 0, 'script': [ret(var(-1))]},
        {'id': -0x80000000, 'return-type': 0, 'param-count': 0, 'script': [ret(43)]},
        {'id': -1, 'return-type': 0, 'param-count': 0, 'script': [ret(44)]},
    ]})
    command = [args.java, '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
               '-cp', str(classes), 'azertia.Main']
    checks = 0

    def invoke(arguments):
        nonlocal checks
        checks += 1
        result = subprocess.run(command + arguments, capture_output=True, text=True, timeout=15)
        assert 'WARNING in native method' not in result.stdout + result.stderr, 'JNI checker reported a warning'
        return result

    for ident, expected in [(None, ''), ('0x0fff0000', '42'), ('268369920', '42'),
                            ('0x80000000', '43'), ('2147483648', '43'),
                            ('-2147483648', '43'), ('-0x80000000', '43'),
                            ('0xffffffff', '44'), ('0XFFFFFFFF', '44'),
                            ('4294967295', '44'), ('-1', '44'), ('-0x1', '44')]:
        arguments = [str(fixture)] + ([] if ident is None else [ident])
        result = invoke(arguments)
        assert result.returncode == 0, f'Main rejected function ID {ident}: {result.stderr}'
        assert result.stdout.strip() == expected, f'Main returned the wrong function result for {ident}: {result.stdout!r}'

    for ident in ('0x100000000', '4294967296', '-2147483649', '-0x80000001',
                  '0x10000000000000000', '9223372036854775808', '-9223372036854775809',
                  'not-a-number', '0x'):
        result = invoke([str(fixture), ident])
        assert result.returncode != 0, f'Main accepted invalid function ID {ident}'
        assert 'IllegalArgumentException' in result.stderr and 'function id' in result.stderr.lower(), result.stderr
        assert not result.stdout, f'Main executed an invalid function ID {ident}'
    for arguments in ([], [str(fixture), '0x0fff0000', 'extra']):
        result = invoke(arguments)
        assert result.returncode != 0 and 'Usage: Main' in result.stderr, result.stderr
        assert not result.stdout, 'Main executed despite an invalid argument count'
    print(f'Java Main entry: {checks} fresh-process cases passed.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--library', required=True, type=Path)
    parser.add_argument('--work-dir', required=True, type=Path)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--javac', default=shutil.which('javac'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = {'gvs': ['counter', 'empty'], 'f': [
        function(i + 1, kind, [ret(var('__func_param0'))], 1)
        for i, kind in enumerate([0, 1, 2, 3, 4])
    ] + [
        {'id': 112, 'return-type': 7, 'param-count': 1, 'param-types': [7],
         'script': [ret(var('__func_param0'))]},
        function(6, 5, [{'t': 0, 'c': 'r'}], 0),
        function(7, 0, [{'t': 0, 'c': 'vs', 'v': 'counter', 'val': var('__func_param0')}, ret(var('counter'))], 1),
        function(8, 0, [ret(var('counter'))]),
        function(9, 0, [ret({'t': 1, 'id': 0x12340001, 'param': [20, 22]})]),
        function(10, 0, [ret(42)]),
        # p = alloc(1); mem_get(p) = 7; host(p); return mem_get(p): the block is
        # scheduled for automatic release, so Java must not be able to free it.
        function(11, 0, [
            {'t': 0, 'c': 'vd', 'v': 'p', 'val': {'t': 1, 'id': 0x0ABD0003, 'param': [1]}},
            {'t': 0, 'c': 'm', 'v1': {'t': 1, 'id': 0x0ABD0006, 'param': [var('p')]}, 'v2': 7},
            {'t': 1, 'id': 0x12340007, 'param': [var('p')]},
            ret({'t': 1, 'id': 0x0ABD0006, 'param': [var('p')]}),
        ]),
    ]}
    write_abd(work / 'fixture.exec.abd', source)
    source['ext'] = {'fixture': 'different bytecode'}
    write_abd(work / 'other.exec.abd', source)
    (work / 'broken.exec.abd').write_bytes(b'\xff\xff\xff\x7f')
    write_abd(work / 'invalid.snapshot.abd', {'snapshot version': 2})
    def manifest(name, count, offset=0, hint='', namespace=None):
        entry = {'bytes': (work / name).read_bytes(), 'has namespace': namespace is not None,
                 'global offset': offset, 'global count': count, 'hint': hint}
        if namespace is not None:
            entry['namespace'] = namespace
        return entry

    write_abd(work / 'overlap.snapshot.abd', {
        'snapshot version': 9, 'module manifest': [manifest('fixture.exec.abd', 2)],
        'variable global': [91, bytes([255])], 'global owned allocations': [], 'objects': [],
        'length heap': 3, 'heap': [bytes([255]), 0, 0],
        'heap allocation': [{'begin position': Address(1), 'length': 2}, {'begin position': Address(2), 'length': 1}]})
    write_abd(work / 'legacy.snapshot.abd', {
        'variable global': {'counter': 91, 'empty': '--------SAVE-----NULL-PTR'},
        'length heap': 1, 'heap': [bytes([255])],
        'heap allocation': [{'begin position': Address(0), 'length': 1}]})
    write_abd(work / 'v2.snapshot.abd', {
        'snapshot version': 2, 'script bytes': (work / 'fixture.exec.abd').read_bytes(),
        'variable global': {'counter': 92, 'empty': bytes([255])},
        'global owned allocations': [], 'length heap': 1, 'heap': [bytes([255])],
        'heap allocation': []})
    write_abd(work / 'v3.snapshot.abd', {
        'snapshot version': 3, 'script bytes': (work / 'fixture.exec.abd').read_bytes(),
        'variable global': {'counter': 93, 'empty': bytes([255])},
        'global owned allocations': [], 'length heap': 1, 'heap': [bytes([255])],
        'heap allocation': [], 'objects': []})

    def call(ident, *params):
        return {'t': 1, 'id': ident, 'param': list(params)}

    def object_function(ident, kind, body, params=()):
        return {'id': ident, 'return-type': kind, 'param-count': len(params),
                'param-types': list(params), 'script': body}

    def object_factory(manual):
        body = [
            {'t': 0, 'c': 'vd', 'v': 'p', 'val': call(0x0ABD0003, 1)},
            {'t': 0, 'c': 'm', 'v1': call(0x0ABD0006, var('p')), 'v2': var('__func_param0')},
            {'t': 0, 'c': 'ob', 'v': var('p'), 'destructor': 205, 'manual': manual}]
        if manual:
            body.append(call(0x0ABD0004, var('p')))
        return body + [{'t': 0, 'c': 'ro', 'r': var('p')}]

    checked_address = {'t': 0, 'c': 'oa', 'v': var('__func_param0'), 'offset': 0}
    object_fixture = {'gvs': ['counter'], 'f': [
        object_function(0, 5, [{'t': 0, 'c': 'vs', 'v': 'counter', 'val': 7}]),
        object_function(201, 7, object_factory(False), [0]),
        object_function(202, 7, object_factory(True), [0]),
        object_function(203, 5, [{'t': 0, 'c': 'od', 'v': var('__func_param0')}], [7]),
        object_function(204, 0, [ret(call(0x0ABD0006, checked_address))], [7]),
        object_function(205, 5, [call(0x34560004, call(0x0ABD0006, checked_address))], [7]),
        object_function(206, 5, [{'t': 0, 'c': 'vs', 'v': 'counter', 'val': var('__func_param0')}], [0]),
        object_function(207, 0, [ret(var('counter'))]),
        object_function(208, 7, object_factory(False)[:-1] + [call(0x0ABD0004, var('p')),
                        {'t': 0, 'c': 'ro', 'r': var('p')}], [0]),
        object_function(209, 0, [ret(0)], [0]),
        object_function(210, 5, [], [1]),
        object_function(211, 5, []),
        object_function(213, 5, [call(201, 1), call(201, 2)]),
        object_function(214, 5, [call(201, 1), call(201, 2), call(0x34560005)]),
        object_function(215, 5, [call(201, 1), call(201, 2), {'t': 0, 'c': 'divide', 'v1': 1, 'v2': 0}]),
    ]}
    write_abd(work / 'objects.exec.abd', object_fixture)
    failed_load = copy.deepcopy(object_fixture)
    failed_load['f'][0]['script'] = [call(202, 91), call(202, 92),
                                    {'t': 0, 'c': 'divide', 'v1': 1, 'v2': 0}]
    write_abd(work / 'objects.load-failure.exec.abd', failed_load)
    object_snapshot = {
        'snapshot version': 9, 'module manifest': [manifest('objects.exec.abd', 1)],
        'variable global': [66], 'global owned allocations': [Address(2), Address(1)],
        'length heap': 4, 'heap': [bytes([255]), 11, 22, 33],
        'heap allocation': [{'begin position': Address(i), 'length': 1} for i in (1, 2, 3)],
        'objects': [{'begin position': Address(i), 'has destructor': True, 'destructor': 205, 'destructor contexts': [], 'manual': i == 3} for i in (1, 2, 3)]}
    write_abd(work / 'objects.valid.snapshot.abd', object_snapshot)
    object_empty = copy.deepcopy(object_snapshot)
    object_empty.update({'snapshot version': 9, 'variable global': [67],
                      'global owned allocations': [], 'length heap': 1,
                      'heap': [bytes([255])], 'heap allocation': [], 'objects': []})
    write_abd(work / 'objects.empty.snapshot.abd', object_empty)
    without_destructor = copy.deepcopy(object_snapshot)
    without_destructor['objects'][2]['has destructor'] = False
    del without_destructor['objects'][2]['destructor']
    write_abd(work / 'objects.no-destructor.snapshot.abd', without_destructor)
    bad_objects = []
    for field, invalid in [('begin position', Address(0)), ('begin position', Address(4)),
                           ('begin position', Address(0xffffffffffffffff)), ('begin position', 1), ('destructor', -2),
                           ('destructor', 999), ('destructor', 0x34560004),
                           ('destructor', 209), ('destructor', 210), ('destructor', 211),
                           ('manual', True), ('manual', 1)]:
        candidate = copy.deepcopy(object_snapshot)
        candidate['objects'][0][field] = invalid
        bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['objects'].append(candidate['objects'][0].copy())
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['global owned allocations'].append(Address(3))
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['global owned allocations'].append(Address(4))
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['global owned allocations'] = [Address(2), Address(1), Address(1)]
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['global owned allocations'] = [2, 1]
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['heap allocation'][0]['begin position'] = 1
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['heap allocation'][0]['begin position'] = Address(0xffffffffffffffff)
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    del candidate['objects']
    bad_objects.append(candidate)
    for field, invalid in [('has destructor', 1), ('has destructor', False), ('destructor', None)]:
        candidate = copy.deepcopy(object_snapshot)
        if invalid is None:
            del candidate['objects'][0][field]
        else:
            candidate['objects'][0][field] = invalid
        bad_objects.append(candidate)
    for field, invalid in [('bytes', b'bad'), ('has namespace', True), ('namespace', 0),
                           ('global offset', 1), ('global count', 2), ('hint', 'different')]:
        candidate = copy.deepcopy(object_snapshot)
        candidate['module manifest'][0][field] = invalid
        bad_objects.append(candidate)
    for manifest_value in ([], [17], {}, None):
        candidate = copy.deepcopy(object_snapshot)
        if manifest_value is None:
            del candidate['module manifest']
        else:
            candidate['module manifest'] = manifest_value
        bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['snapshot version'] = 4
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['snapshot version'] = 7
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    candidate['snapshot version'] = 8
    bad_objects.append(candidate)
    candidate = copy.deepcopy(object_snapshot)
    del candidate['objects'][0]['destructor contexts']
    bad_objects.append(candidate)
    for contexts in (1, [17], [{'abi': 6, 'kind': 0, 'width': 1, 'has factory': False, 'contexts': [], 'has placement': False, 'placement contexts': [], 'has element': False}],
                     [{'abi': 0, 'kind': 0, 'width': 1, 'has factory': False, 'contexts': [], 'has placement': False, 'placement contexts': [], 'has element': False}]):
        candidate = copy.deepcopy(object_snapshot)
        candidate['objects'][0]['destructor contexts'] = contexts
        bad_objects.append(candidate)
    for i, candidate in enumerate(bad_objects):
        write_abd(work / f'objects.invalid-{i}.snapshot.abd', candidate)

    # Globals use negative slot ids and local/parameter variables non-negative
    # ids. write_abd emits only fixed-record exec v9 for all program fixtures.
    def numeric_expression(value):
        if isinstance(value, list):
            return [numeric_expression(item) for item in value]
        if isinstance(value, dict):
            converted = {key: numeric_expression(item) for key, item in value.items()}
            if value.get('c') in ('v', 'vd', 'vs'):
                converted['v'] = {'counter': -1, '__func_param0': 0, 'p': 1}[value['v']]
            return converted
        return value

    numeric_fixture = {'exec-version': 9, 'gvs': 5, 'f': []}
    for original in object_fixture['f']:
        converted = copy.deepcopy(original)
        converted['local-count'] = 1 if original['id'] in (201, 202, 208) else 0
        converted['script'] = numeric_expression(original['script'])
        numeric_fixture['f'].append(converted)
    numeric_fixture['f'][0]['script'] = [
        {'t': 0, 'c': 'vs', 'v': -1, 'val': 7},
        {'t': 0, 'c': 'vs', 'v': -2, 'val': 'numeric\0中文🐈'},
        {'t': 0, 'c': 'vs', 'v': -3, 'val': True},
        {'t': 0, 'c': 'vs', 'v': -4, 'val': call(201, 11)},
        call(0x0ABD0005, var(-4)),
        {'t': 0, 'c': 'vs', 'v': -5, 'val': call(202, 33)}]
    for ident, kind, index in [(220, 1, -2), (222, 4, -3), (224, 7, -4), (225, 7, -5)]:
        numeric_fixture['f'].append({**object_function(ident, kind, [ret(var(index))]), 'local-count': 0})
    for ident, kind, index in [(221, 1, -2), (223, 4, -3)]:
        numeric_fixture['f'].append({**object_function(ident, kind, [
            {'t': 0, 'c': 'vs', 'v': index, 'val': var(0)}, ret(var(index))], [kind]), 'local-count': 0})
    write_abd(work / 'numeric.exec.abd', numeric_fixture)
    numeric_other = copy.deepcopy(numeric_fixture)
    numeric_other['ext'] = {'fixture': 'different numeric bytecode'}
    write_abd(work / 'numeric.other.exec.abd', numeric_other)
    numeric_snapshot = {
        'snapshot version': 9, 'module manifest': [manifest('numeric.exec.abd', 5)],
        'variable global': [66, 'restored', False, Address(1), Address(2)], 'global owned allocations': [Address(1)],
        'length heap': 3, 'heap': [bytes([255]), 11, 33],
        'heap allocation': [{'begin position': Address(i), 'length': 1} for i in (1, 2)],
        'objects': [{'begin position': Address(i), 'has destructor': True, 'destructor': 205, 'destructor contexts': [], 'manual': i == 2} for i in (1, 2)]}
    write_abd(work / 'numeric.valid.snapshot.abd', numeric_snapshot)
    invalid_globals = [[], [66, 'restored', False, 1], [66, 'restored', False, 1, 2, 3],
                       {'one': 66, 'two': 'restored', 'three': False, 'four': 1, 'five': 2},
                       [{}, 'restored', False, 1, 2], [66, [], False, 1, 2],
                       [66, 'restored', bytes([0]), 1, 2]]
    for i, globals_value in enumerate(invalid_globals):
        candidate = copy.deepcopy(numeric_snapshot)
        candidate['variable global'] = globals_value
        write_abd(work / f'numeric.invalid-{i}.snapshot.abd', candidate)
    candidate = copy.deepcopy(numeric_snapshot)
    candidate['objects'][0]['destructor'] = 999
    write_abd(work / 'numeric.invalid-7.snapshot.abd', candidate)
    for version in (0, 2, 3, 4, 5, 6):
        candidate = copy.deepcopy(numeric_snapshot)
        candidate['variable global'] = {'g' + str(i): item for i, item in enumerate(candidate['variable global'])}
        if version:
            candidate['snapshot version'] = version
        else:
            del candidate['snapshot version']
        write_abd(work / f'numeric.named-v{version}.snapshot.abd', candidate)

    def lifecycle_fixture(function_id, callback_id):
        return {'f': [function(10, 0, [ret(42)]),
                      {'id': function_id, 'return-type': 5, 'param-count': 0,
                       'script': [{'t': 1, 'id': callback_id, 'param': []}]}]}
    write_abd(work / 'onclose.exec.abd', lifecycle_fixture(1, 0x34560001))
    write_abd(work / 'onload.exec.abd', lifecycle_fixture(0, 0x34560002))
    write_abd(work / 'onload-invoke.exec.abd', lifecycle_fixture(0, 0x34560003))
    # Independently encoded multi-module consumers and relocatable libraries.
    def signature(ident, kind, params=()):
        return {'id': ident, 'return-type': kind, 'param-types': list(params)}

    alias = 0x5DDD0000
    self_alias = 0x5EEE0000
    event = 0x34560010
    def hinted_factory(manual):
        body = [
            {'t': 0, 'c': 'vd', 'v': 1, 'val': call(0x0ABD0003, 1)},
            {'t': 0, 'c': 'm', 'v1': call(0x0ABD0006, var(1)), 'v2': var(0)},
            {'t': 0, 'c': 'ob', 'v': var(1), 'destructor': self_alias + 5, 'manual': manual}]
        if manual:
            body.append(call(0x0ABD0004, var(1)))
        return body + [{'t': 0, 'c': 'ro', 'r': var(1)}]

    hint_library = {'gvs': 1, 'namespace-hint': 'PointLibrary',
        'assume-hints': [{'hint': 'PointLibrary', 'namespace': self_alias >> 16}],
        'extern-signatures': [signature(self_alias + 5, 5, [7])], 'f': [
            object_function(0, 5, [{'t': 0, 'c': 'vs', 'v': -1, 'val': 42}, call(event, 10)]),
            object_function(1, 5, [call(event, 20)]),
            object_function(2, 0, [ret(var(-1))]),
            {**object_function(3, 7, hinted_factory(False), [0]), 'local-count': 1},
            {**object_function(4, 7, hinted_factory(True), [0]), 'local-count': 1},
            object_function(5, 5, [call(0x34560004, call(0x0ABD0006,
                {'t': 0, 'c': 'oa', 'v': var(0), 'offset': 0}))], [7]),
            object_function(6, 5, [{'t': 0, 'c': 'vs', 'v': -1, 'val': var(0)}], [0]),
            object_function(7, 0, [ret(call(0x0ABD0006,
                {'t': 0, 'c': 'oa', 'v': var(0), 'offset': 0}))], [7]),
            object_function(8, 5, [{'t': 0, 'c': 'od', 'v': var(0)}], [7])
        ]}
    hint_consumer = {'gvs': 3, 'assume-hints': [{'hint': 'PointLibrary', 'namespace': alias >> 16}],
        'extern-signatures': [signature(alias + 2, 0), signature(alias + 3, 7, [0]),
                              signature(alias + 4, 7, [0]), signature(alias + 7, 0, [7]),
                              signature(alias + 8, 5, [7])],
        'f': [object_function(0, 5, [
            {'t': 0, 'c': 'vs', 'v': -1, 'val': call(alias + 2)},
            {'t': 0, 'c': 'vs', 'v': -2, 'val': call(alias + 3, 11)},
            call(0x0ABD0005, var(-2)),
            {'t': 0, 'c': 'vs', 'v': -3, 'val': call(alias + 4, 33)}, call(event, 100)]),
            object_function(1, 5, [call(event, 110)]),
            object_function(0x12000002, 0, [ret(var(-1))]),
            object_function(0x12000003, 7, [ret(var(-2))]),
            object_function(0x12000004, 7, [ret(var(-3))]),
            object_function(0x12000005, 0, [ret(call(alias + 7, var(0)))], [7]),
            object_function(0x12000006, 5, [call(alias + 8, var(0))], [7])]}
    hint_extra = {'gvs': 1, 'namespace-hint': 'ExtraLibrary',
        'assume-hints': [{'hint': 'ExtraLibrary', 'namespace': 0x5EEE}], 'f': [
        object_function(0, 5, [{'t': 0, 'c': 'vs', 'v': -1, 'val': 88}, call(event, 200)]),
        object_function(1, 5, [call(event, 210)]), object_function(2, 0, [ret(var(-1))])]}
    write_abd(work / 'hints.consumer.exec.abd', hint_consumer)
    write_abd(work / 'hints.library.exec.abd', hint_library)
    write_abd(work / 'hints.extra.exec.abd', hint_extra)
    modified_library = copy.deepcopy(hint_library)
    modified_library['ext'] = {'identity': 'same schema, different original bytes'}
    write_abd(work / 'hints.changed-library.exec.abd', modified_library)
    high_destructor = {'gvs': 0, 'f': [
        object_function(-1, 5, [call(0x34560004, call(0x0ABD0006, var(0)))], [7]),
        {**object_function(0x7FFF0002, 7, [
            {'t': 0, 'c': 'vd', 'v': 0, 'val': call(0x0ABD0003, 1)},
            {'t': 0, 'c': 'm', 'v1': call(0x0ABD0006, var(0)), 'v2': 55},
            {'t': 0, 'c': 'ob', 'v': var(0), 'destructor': -1, 'manual': False},
            {'t': 0, 'c': 'ro', 'r': var(0)}]), 'local-count': 1}]}
    write_abd(work / 'high-destructor.exec.abd', high_destructor)
    # A deep dependency graph is independent of the expression nesting limit.
    # Resolve it on a small JVM stack so a recursive native SCC walk fails here.
    deep_count = 1000
    deep_modules = work / 'deep-modules'
    deep_modules.mkdir(exist_ok=True)
    for index in range(deep_count):
        assumptions = [{'hint': f'Deep{index}', 'namespace': 0x5554}]
        if index + 1 < deep_count:
            assumptions.append({'hint': f'Deep{index + 1}', 'namespace': 0x5555})
        write_abd(deep_modules / f'{index}.exec.abd', {
            'gvs': 0, 'f': [], 'namespace-hint': f'Deep{index}', 'assume-hints': assumptions})
    classes = work / 'classes'
    classes.mkdir(exist_ok=True)
    files = sorted(str(p) for directory in ['src/main/java', 'src/test/java'] for p in (root / directory).rglob('*.java'))
    subprocess.run([args.javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), *files], check=True)
    check_main_entry(args, classes, work)
    completed = subprocess.run([args.java, '-Xcheck:jni', '-ea', '-Dazertia.native.library=' + str(args.library.resolve()),
                               '-cp', str(classes), 'azertia.JniRegression', str(work)],
                              capture_output=True, text=True, timeout=90)
    print(completed.stdout, end='')
    print(completed.stderr, end='')
    completed.check_returncode()
    assert 'WARNING in native method' not in completed.stdout + completed.stderr, 'JNI checker reported a warning'
    deep = subprocess.run([args.java, '-Xss256k', '-Xcheck:jni', '-ea',
                           '-Dazertia.native.library=' + str(args.library.resolve()), '-cp', str(classes),
                           'azertia.JniDeepLinkRegression', str(deep_modules), str(deep_count)],
                          capture_output=True, text=True, timeout=30)
    print(deep.stdout, end='')
    print(deep.stderr, end='')
    deep.check_returncode()
    assert 'WARNING in native method' not in deep.stdout + deep.stderr, 'JNI checker reported a warning'
    scalars = read_abd(work / '快照 🐈.abd')
    assert scalars['snapshot version'] == 9
    assert Address(0xfedcba9876543210) in scalars['heap']
    assert Address(0) in scalars['heap']
    assert all(isinstance(entry['begin position'], Address) for entry in scalars['heap allocation'])
    snapshot = read_abd(work / 'objects.saved.snapshot.abd')
    assert snapshot['snapshot version'] == 9
    assert snapshot['module manifest'] == [manifest('objects.exec.abd', 1)]
    assert snapshot['variable global'] == [7]
    assert len(snapshot['objects']) == 3
    assert sorted(item['manual'] for item in snapshot['objects']) == [False, False, True]
    assert all(item['destructor'] == 205 and item['destructor contexts'] == [] for item in snapshot['objects'])
    assert len(snapshot['global owned allocations']) == 2
    assert set(snapshot['global owned allocations']) == {
        item['begin position'] for item in snapshot['objects'] if not item['manual']}
    detached = read_abd(work / 'objects.detached.snapshot.abd')
    assert detached['snapshot version'] == 9
    assert len(detached['objects']) == 1 and detached['objects'][0]['manual'] is False
    assert detached['global owned allocations'] == []
    numeric = read_abd(work / 'numeric.saved.snapshot.abd')
    assert numeric['snapshot version'] == 9
    assert numeric['module manifest'] == [manifest('numeric.exec.abd', 5)]
    assert len(numeric['variable global']) == 5
    assert numeric['variable global'][:3] == [7, 'numeric\0中文🐈', True]
    assert len(numeric['objects']) == 2
    assert numeric['global owned allocations'] == [numeric['variable global'][3]]
    assert {item['begin position'] for item in numeric['objects']} == set(numeric['variable global'][3:])
    hints = read_abd(work / 'hints.all.snapshot.abd')
    assert hints['snapshot version'] == 9
    assert hints['module manifest'] == [manifest('hints.consumer.exec.abd', 3),
        manifest('hints.library.exec.abd', 1, 3, 'PointLibrary', 1),
        manifest('hints.extra.exec.abd', 1, 4, 'ExtraLibrary', 2)]
    assert all(item['has destructor'] and item['destructor'] == 0x00010005 for item in hints['objects'])
    high = read_abd(work / 'high-destructor.snapshot.abd')
    assert high['objects'][0]['has destructor'] is True
    assert high['objects'][0]['destructor'] == -1



if __name__ == '__main__':
    main()
