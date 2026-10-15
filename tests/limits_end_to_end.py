#!/usr/bin/env python3
"""Exact ABD-budget spilling, semantic differentials and compiler limit regressions."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
LIMITS = (128, 96, 64, 48)


def nodes(value):
    pending = [value]
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            yield value
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)


def metrics(inspection):
    return {'locals': sum(fn['local-count'] for fn in inspection['f']),
            'defines': sum(node.get('t') == 0 and node.get('c') == 4 for node in nodes(inspection)),
            'moves': sum(node.get('t') == 0 and node.get('c') == 34 for node in nodes(inspection)),
            'drops': sum(node.get('t') == 0 and node.get('c') == 35 for node in nodes(inspection))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    parser.add_argument('--only', help='Select generated regression names or example paths containing this text')
    parser.add_argument('--report', type=Path, default=ROOT / 'build/limits-differential.json')
    args = parser.parse_args()
    records = []
    checks = 0
    spills = {limit: [] for limit in LIMITS[1:]}
    with tempfile.TemporaryDirectory(prefix='limits-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        cache = {}

        def selected(name):
            return args.only is None or args.only in name

        def command(argv, timeout=90):
            return subprocess.run(list(map(str, argv)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)

        def compile_case(name, path, limit=128, *, debug=False, allow_depth_failure=False):
            key = (path.resolve(), limit, debug)
            if key in cache:
                return cache[key]
            tag = re.sub(r'[^A-Za-z0-9_.-]', '_', name) + f'.{limit}' + ('.debug' if debug else '')
            abd, ast, executable = (work / (tag + suffix) for suffix in ('.abd', '.ast.json', '.exec.json'))
            java = ['java', '-Xmx1g', *([f'-Dazscript.internal.abdLimit={limit}'] if limit != 128 else []), '-cp', args.classpath, 'azertia.Main']
            started = time.monotonic()
            result = command(java + ['compile', path, '-o', abd, '--ast', ast, '--exec-json', executable,
                                     '-I', ROOT / 'compiler/stdlib', '-I', ROOT / 'compiler/examples',
                                     *(['-g'] if debug else [])])
            elapsed = time.monotonic() - started
            if result.returncode != 0:
                assert allow_depth_failure and limit < 128, (name, limit, result.stdout, result.stderr)
                assert result.returncode == 1 and 'Error:' in result.stderr and path.name in result.stderr, (name, limit, result.stderr)
                assert 'Exception in thread' not in result.stderr and 'StackOverflow' not in result.stderr, (name, limit, result.stderr)
                assert any(word in result.stderr.lower() for word in ('depth', 'nesting', 'operand', 'condition', 'budget')), (name, limit, result.stderr)
                entry = {'name': name, 'limit': limit, 'status': 'infeasible', 'reason': result.stderr.strip(), 'compile_seconds': elapsed}
                records.append(entry)
                answer = (None, entry)
                cache[key] = answer
                return answer
            roundtrip = work / (tag + '.rebuilt.abd')
            result = command(java + ['compile-json', ast, '-o', roundtrip, *(['-g'] if debug else [])])
            assert result.returncode == 0, (name, limit, 'AST rebuild', result.stdout, result.stderr)
            assert abd.read_bytes() == roundtrip.read_bytes(), (name, limit, 'source/AST/ABD identity')
            tree = json.loads(executable.read_text(encoding='utf-8'))
            assert tree['exec-version'] == 10, (name, tree)
            assert all(0 <= node['c'] <= 53 for node in nodes(tree) if node.get('t') == 0 and type(node.get('c')) is int), (name, 'internal sequence escaped')
            entry = {'name': name, 'limit': limit, 'status': 'compiled', 'abd_bytes': abd.stat().st_size,
                     'compile_seconds': elapsed, **metrics(tree)}
            records.append(entry)
            artifact = {'abd': abd, 'ast': json.loads(ast.read_text(encoding='utf-8')), 'inspection': tree, 'metrics': entry}
            answer = (artifact, entry)
            cache[key] = answer
            return answer

        def run_case(name, artifact, libraries=()):
            command_line = [args.runner, artifact['abd']]
            for library in libraries:
                command_line += ['--insert', library['abd']]
            started = time.monotonic()
            result = command(command_line)
            artifact['metrics']['run_seconds'] = time.monotonic() - started
            return result

        def differential(name, source, expected=None, error=None, *, debug=False, require_spill=False):
            nonlocal checks
            if not selected(name):
                return
            path = work / (name + '.azs')
            path.write_text(source, encoding='utf-8')
            baseline, _ = compile_case(name, path, debug=debug)
            result = run_case(name, baseline)
            if error:
                assert result.returncode != 0 and error.lower() in result.stderr.lower(), (name, result.stdout, result.stderr)
            else:
                assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            if expected is not None:
                assert result.stdout == expected, (name, result.stdout, expected)
            signature = (result.returncode == 0, result.stdout, result.stderr)
            baseline['metrics']['status'] = 'passed'
            for limit in LIMITS[1:]:
                artifact, _ = compile_case(name, path, limit, debug=debug)
                result = run_case(name, artifact)
                assert (result.returncode == 0, result.stdout, result.stderr) == signature, (name, limit, result.stdout, result.stderr, signature)
                artifact['metrics']['status'] = 'passed'
                grew = artifact['metrics']['locals'] > baseline['metrics']['locals'] and artifact['metrics']['defines'] > baseline['metrics']['defines']
                artifact['metrics']['extra_locals'] = artifact['metrics']['locals'] - baseline['metrics']['locals']
                if grew:
                    spills[limit].append(name)
                if require_spill:
                    assert grew, (name, limit, 'the forced budget did not exercise spilling', baseline['metrics'], artifact['metrics'])
                checks += 1
            checks += 1
            return baseline


        def lifetime_differential(name, source, expected=None, error=None):
            """A cut may be rejected if existing opcodes cannot preserve lifetime/identity."""
            nonlocal checks
            if not selected(name):
                return
            path = work / (name + '.azs');path.write_text(source, encoding='utf-8')
            baseline, _ = compile_case(name, path)
            result = run_case(name, baseline)
            if error:
                assert result.returncode != 0 and error.lower() in result.stderr.lower(), (name, result.stdout, result.stderr)
            else:
                assert result.returncode == 0 and not result.stderr, (name, result.stdout, result.stderr)
            if expected is not None:
                assert result.stdout == expected, (name, result.stdout, expected)
            signature = (result.returncode == 0, result.stdout, result.stderr)
            baseline['metrics']['status'] = 'passed'
            for limit in LIMITS[1:]:
                artifact, entry = compile_case(name, path, limit, allow_depth_failure=True)
                if artifact is None:
                    assert 'store this operand in a local variable first' in entry['reason'], (name, limit, entry['reason'])
                    assert 'this cut would change object lifetime or copying' in entry['reason'], (name, limit, entry['reason'])
                else:
                    result = run_case(name, artifact)
                    assert (result.returncode == 0, result.stdout, result.stderr) == signature, (name, limit, result.stdout, result.stderr, signature)
                    entry['status'] = 'passed'
                    entry['extra_locals'] = entry['locals'] - baseline['metrics']['locals']
                checks += 1
            checks += 1

        # The canary is below the flat-chain threshold and fits at128. Every
        # forced budget must introduce extra defines, proving the property is used.
        additive = '+'.join(['n'] * 110)
        differential('spill-canary', f'int main(){{int n=1;return {additive};}}', '110\n', require_spill=True)
        calls = '+'.join(['next()'] * 100)
        differential('eager-call-order', '#gvar seen\nint next(){seen=seen+1;return seen;}' +
                     f'int main(){{seen=0;int value={calls};print(seen);return value;}}', '100\n5050\n')
        nested = 'id(' * 70 + '7' + ')' * 70
        differential('nested-eager-calls', f'int id(int n){{return n;}}int main(){{return {nested};}}', '7\n')
        chain = '.next' * 70
        differential('typed-postfix-chain', 'class Node{Node* next;int value=7;}' +
                     f'int main(){{Node* p=new Node();p.next=p;int value=p{chain}.value;delete p;return value;}}', '7\n')
        differential('store-target-captured-once', '#gvar calls\naddress saved=null;address target(){calls+=1;return saved;}' +
                     f'int main(){{calls=0;saved=alloc(1);int n=1;mem_get(target())={additive};int result=int(mem_get(saved));print(calls);mem_free(saved);return result;}}', '1\n110\n')
        differential('typed-second-field-target-once', '#gvar seen\nclass Pair{int first=17;int second=0;}'
                     'Pair* target(Pair* p){seen+=1;return p;}' +
                     f'int main(){{seen=0;Pair* p=new Pair();int n=1;target(p).second={additive};print(seen);print(p.first);int result=p.second;delete p;return result;}}', '1\n17\n110\n')
        differential('raw-target-check-before-pure-rhs-error', f'void main(){{address p=null;int n=1;int zero=0;mem_get(p)=({additive})/zero;}}',
                     error='Invalid or freed heap pointer')
        differential('typed-target-check-before-pure-rhs-error', 'class Pair{int first;int second;}' +
                     f'void main(){{Pair* p=null;int n=1;int zero=0;p.second=({additive})/zero;}}', error='Invalid or freed object address')
        zeros = '+'.join(['zero'] * 100)
        differential('for-condition-and-step', f'int main(){{int zero=0;int seen=0;for(int i=0;({zeros})+i<2;i=i+({zeros})+1){{seen+=1;}}return seen;}}', '2\n')
        differential('automatic-pointer-scope-lifetime', 'class Item{int value=7;~Item(){print("drop");}}'
                     'Item* make(){Item* p();return p;}int read(Item* p){return p.value;}' +
                     f'void main(){{int n=0;int value=read(make())+{additive};print(value);print("after");}}')
        differential('literal-object-statement-lifetime', 'class V{int n=7;~V(){print("V");}}'
                     'V makeValue(){V value;return value;}' +
                     f'int main(){{int n=1;int result=makeValue().n+{additive};print("after");return result;}}', 'V\nafter\n117\n')
        differential('literal-receiver-before-later-call', 'class V{int n=7;~V(){print("V");}}'
                     'V makeValue(){V value;return value;}int effect(){print("effect");return 1;}' +
                     f'int main(){{int n=1;int result=makeValue().n+{additive}+effect();return result;}}', 'effect\nV\n118\n')
        # BUFFER_GET creates the returned P before the retained source buffer
        # is dropped. Its element must die before main can use the saved copy.
        owned_return_index = '+'.join(['zero'] * 110)
        owned_buffer_return = ('buffer<P> makeValues(){buffer<P> values;values.reserve(1);values.resize(1);return values;}' +
                               f'P deep(){{int zero=0;return makeValues().get({owned_return_index});}}' +
                               'void main(){drops=0;P value=deep();print(value.n);print("after");}')
        for name, source_failure, copy_failure, output, error in (
                ('owned-return-capture', '', '', 'P\n7\nafter\nP\n', None),
                ('owned-return-capture-cleanup-error', 'if(drops==1){int zero=0;int bad=1/zero;}', '', 'P\nP\n', 'Division by zero'),
                ('owned-return-capture-first-error', 'if(drops==1){int zero=0;int bad=1/zero;}',
                 'if(drops==2){int maximum=2147483647;int bad=maximum+1;}', 'P\nP\n', 'Division by zero')):
            declarations = '#gvar drops\nclass P{int n=7;~P(){print("P");drops+=1;' + source_failure + copy_failure + '}}'
            differential(name, declarations + owned_buffer_return, output, error, require_spill=True)
        # A fresh value on an original materializing CALL edge is consumed by
        # the callee once, then the owned result survives the capture cleanup.
        differential('owned-return-capture-materializing-argument',
                     'class V{int n=7;~V(){print("V");}}class P{int n;P(int value){n=value;}~P(){print("P");}}'
                     'V makeV(){V value;return value;}P choose(V value,int tail){P result(value.n+tail);return result;}' +
                     f'P deep(){{int n=1;return choose(makeV(),{additive});}}'
                     'void main(){P value=deep();print(value.n);print("after");}', 'V\n117\nafter\nP\n', require_spill=True)
        # Retaining a receiver across a later unproved OBJECT-returning script
        # call may require the supported conservative lifetime/copy rejection.
        lifetime_differential('owned-return-capture-retained-field-guard',
                     'class V{int n=7;~V(){print("V");}}class P{int n;P(int value){n=value;}~P(){print("P");}}'
                     'V makeV(){V value;return value;}P makeP(int n){P value(n);return value;}' +
                     f'P deep(){{int n=1;return makeP(makeV().n+{additive});}}'
                     'void main(){P value=deep();print(value.n);print("after");}', 'V\n117\nafter\nP\n')
        lifetime_differential('retained-literal-before-later-failure', 'class V{int n=7;~V(){print("V");}}'
                     'class P{int n;~P(){print("P");}}V makeV(){V value;return value;}P* makeP(){P* p();return p;}'
                     'int fail(P* p){int zero=0;return 1/zero;}' +
                     f'void main(){{int n=1;int result=makeV().n+{additive}+fail(makeP());}}', 'V\nP\n', error='Division by zero')
        lifetime_differential('retained-literal-before-scalar-scope-transfer', 'address saved=null;'
                     'class V{int n=7;~V(){print(mem_get(saved));}}V makeV(){V value;return value;}'
                     'int transferThenFail(){saved=alloc(1);mem_get(saved)=7;mem_send_up(saved);int zero=0;return 1/zero;}' +
                     f'void main(){{int n=1;int result=makeV().n+{additive}+transferThenFail();}}', '7\n', error='Division by zero')
        lifetime_differential('literal-borrowed-argument-lifetime', 'class V{int n=7;~V(){print("V");}}'
                     'V makeValue(){V value;return value;}int borrow(V (*) value,int tail){return value.n+tail;}' +
                     f'int main(){{int n=1;int result=borrow(makeValue(),{additive});print("after");return result;}}', 'V\nafter\n117\n')
        nested_generic = 'identity<T>(' * 24 + 'b' + ')' * 24
        # Unknown T arithmetic is forbidden at compile time. A materializing
        # comparison is the supported source edge for borrowed T copy semantics.
        lifetime_differential('borrowed-generic-comparison-before-type-error',
                     'class P{int tag;P(int n){tag=n;}~P(){print(tag);if(tag==1){int max=2147483647;int bad=max+1;}}}'
                     '<T> T identity(T value){return value;}' +
                     f'<T> int probe(T a,T b){{return value_compare<T>(a,{nested_generic});}}'
                     'void main(){P left(1);P right(2);probe(left,right);}', error='This type requires a custom comparison function')
        lifetime_differential('generic-comparison-caller-snapshots', 'class P{int tag;P(int n){tag=n;}~P(){print(tag);}}'
                     'int effect(){print("wrong");return 1;}' +
                     f'<T> int compare(T a,T b){{int n=1;return value_compare<T>(a,b)+{additive}+effect();}}'
                     'void main(){P left(1);P right(2);compare(left,right);}', error='This type requires a custom comparison function')
        lifetime_differential('buffer-mutation-receiver-identity', 'int main(){buffer<int> values;int zero=0;int n=1;'
                     f'values.reserve({zeros}+1);values.push({additive});values.set(0,{additive}+1);'
                     'print(values.length());print(values.capacity());return values.get(0);}', '1\n1\n111\n')
        lifetime_differential('buffer-resize-receiver-identity', 'int main(){buffer<int> values;values.reserve(2);int zero=0;'
                     f'values.resize({zeros}+1);print(values.length());values.set(0,17);return values.get(0);}}', '1\n17\n')
        lifetime_differential('class-assignment-result-capture', 'class P{int value;P(int n){value=n;}~P(){print(value);}}'
                     'P makeValue(){P value(7);return value;}int consume(P value,int tail){return value.value+tail;}' +
                     f'void main(){{P target(1);int zero=0;print(consume(target=makeValue(),{zeros}));print("after");}}')
        differential('spilled-error-debug-position', f'int main(){{int n=1;int value={additive};int zero=0;return value/zero;}}',
                     error='Division by zero', debug=True)

        # Above128 operands the parser must retain a flat chain. These cases
        # distinguish safe string/boolean balancing from numeric left folds.
        for count in (128, 129):
            artifact = differential(f'chain-threshold-{count}', 'int main(){int n=1;return ' + '+'.join(['n'] * count) + ';}', f'{count}\n')
            if artifact:
                chains = [node for node in nodes(artifact['ast']) if node.get('call') == 'chain']
                assert bool(chains) == (count > 128), (count, chains)
                if chains:
                    assert len(chains[0]['operators']) == len(chains[0]['param']) - 1 == count - 1, chains[0]
                checks += 1
        differential('chain-10k-string-literals', 'string main(){return ' + '+'.join(['"x"'] * 10000) + ';}', 'x' * 10000 + '\n')
        differential('chain-10k-string-locals', 'int main(){string unit="x";string value=' + '+'.join(['unit'] * 10000) + ';return value.length();}', '10000\n')
        differential('chain-10k-checked-string-calls', '#gvar seen\nstring part(){seen+=1;return "x";}' +
                     'int main(){seen=0;string value=' + '+'.join(['part()'] * 10000) + ';print(seen);return value.length();}', '10000\n10000\n')
        differential('chain-10k-mixed-plus', 'string main(){return ' + '+'.join(['1', '2', '"x"'] + ['0'] * 9997) + ';}', '3x' + '0' * 9997 + '\n')
        differential('chain-10k-mid-overflow', 'int next(){print("next");return 1;}int main(){return ' +
                     '+'.join(['0'] * 5000 + ['2147483647'] + ['next()'] * 4999) + ';}', 'next\n', error='Integer overflow')
        differential('chain-10k-float-left-fold', 'float main(){return ' +
                     '+'.join(['1e20f', '-1e20f', '3.0f'] + ['0.0f'] * 9997) + ';}', '3\n')
        differential('chain-10k-float-rounded-before-widening', 'double main(){return ' +
                     '+'.join(['16777216.0f', '1.0f', '-16777216.0f', '0.0'] + ['0.0'] * 9996) + ';}', '0\n')
        for operation, condition, answer in [('&&', 'n<5000', 'false'), ('||', 'n==5000', 'true')]:
            name = 'chain-10k-' + ('and' if operation == '&&' else 'or')
            differential(name, '#gvar seen\nboolean term(int n){seen+=1;return ' + condition + ';}' +
                         'boolean main(){seen=0;boolean value=' + operation.join(f'term({i})' for i in range(10000)) +
                         ';print(seen);return value;}', '5001\n' + answer + '\n')
        if selected('chain-ast-schema'):
            path = work / 'chain-ast-schema.azs'
            path.write_text('int main(){int n=1;return ' + '+'.join(['n'] * 129) + ';}', encoding='utf-8')
            artifact, _ = compile_case('chain-ast-schema', path)
            mutations = ('missing-operator', 'extra-operator', 'operator-not-object', 'unknown-operator', 'operators-not-array', 'operands-not-array', 'invalid-operator-position')
            for mutation in mutations:
                tree = json.loads(json.dumps(artifact['ast']))
                chain = next(node for node in nodes(tree) if node.get('call') == 'chain')
                if mutation == 'missing-operator': chain['operators'].pop()
                elif mutation == 'extra-operator': chain['operators'].append(dict(chain['operators'][0]))
                elif mutation == 'operator-not-object': chain['operators'][0] = 'add'
                elif mutation == 'unknown-operator': chain['operators'][0]['call'] = 'varset'
                elif mutation == 'operators-not-array': chain['operators'] = 'add'
                elif mutation == 'operands-not-array': chain['param'] = {'wrong': 1}
                else: chain['operators'][0]['_line'] = 'wrong'
                invalid = work / ('chain-' + mutation + '.json');invalid.write_text(json.dumps(tree), encoding='utf-8')
                result = command(['java', '-Xmx1g', '-cp', args.classpath, 'azertia.Main', 'compile-json', invalid, '-o', work / 'chain-invalid.abd'])
                assert result.returncode == 1 and 'Error:' in result.stderr and 'Exception in thread' not in result.stderr, (mutation, result.stderr)
                checks += 1

        # These cuts would change evaluation timing. Their rejection must identify
        # the original source and explain how the user can express the local.
        protected = {
            'lazy-right': f'boolean main(){{int n=1;return false&&(({additive})>0);}}',
            'select-arm': f'int main(){{int n=1;boolean choose=false;return choose?({additive}):0;}}',
            'while-condition': f'int main(){{int n=1;while(({additive})<0){{break;}}return 0;}}',
            'effectful-raw-store-rhs': 'int effect(){return 1;}' +
                f'int main(){{address p=alloc(1);int n=1;mem_get(p)={additive}+effect();int result=int(mem_get(p));mem_free(p);return result;}}',
            'effectful-typed-store-rhs': 'class Pair{int first;int second;}int effect(){return 1;}' +
                f'int main(){{Pair* p=new Pair();int n=1;p.second={additive}+effect();int result=p.second;delete p;return result;}}',
        }
        for name, source in protected.items():
            name = 'protected-' + name
            if not selected(name):
                continue
            path = work / (name + '.azs');path.write_text(source, encoding='utf-8')
            baseline, _ = compile_case(name, path)
            result = run_case(name, baseline)
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            for limit in LIMITS[1:]:
                artifact, entry = compile_case(name, path, limit, allow_depth_failure=True)
                assert artifact is None, (name, limit, 'protected expression was cut')
                assert 'local variable first' in entry['reason'].lower(), (name, limit, entry['reason'])
                if 'store-rhs' in name:
                    assert 'store RHS in a local variable first' in entry['reason'], (name, limit, entry['reason'])
                checks += 1

        if selected('true-nesting-rejected'):
            path = work / 'true-nesting-rejected.azs'
            path.write_text('int id(int n){return n;}int main(){return ' + 'id(' * 650 + '1' + ')' * 650 + ';}', encoding='utf-8')
            result = command(['java', '-Xmx1g', '-cp', args.classpath, 'azertia.Main', 'compile', path, '-o', work / 'too-deep.abd'])
            assert result.returncode == 1 and path.name in result.stderr and 'nesting' in result.stderr.lower(), result.stderr
            assert 'Exception in thread' not in result.stderr and 'StackOverflow' not in result.stderr, result.stderr
            checks += 1

        if selected('parser-stack-guard'):
            path = work / 'parser-stack-guard.azs'
            path.write_text('int main(){return ' + '(' * 2000 + '1' + ')' * 2000 + ';}', encoding='utf-8')
            result = command(['java', '-Xint', '-Xss512k', '-Xmx1g', '-cp', args.classpath,
                              'azertia.Main', 'compile', path, '-o', work / 'parser-stack-guard.abd'])
            assert result.returncode == 1 and path.name in result.stderr and 'Syntax nesting exceeds 320 levels' in result.stderr, result.stderr
            assert 'StackOverflow' not in result.stderr and 'internal compiler error' not in result.stderr, result.stderr
            checks += 1

        dependency_paths = {
            'containers-buffer.azs': [ROOT / 'compiler/stdlib/containers.azs'],
            'containers-regressions.azs': [ROOT / 'compiler/stdlib/containers.azs'],
            'math-regressions.azs': [ROOT / 'compiler/stdlib/math.azs'],
            'generic-box-consumer.azs': [ROOT / 'compiler/examples/generic-box.azs'],
            'multifile/main.azs': [ROOT / 'compiler/examples/multifile/point.azs'],
        }
        for path in sorted((ROOT / 'compiler/examples').rglob('*.azs')):
            relative = path.relative_to(ROOT / 'compiler/examples').as_posix()
            name = 'example-' + relative
            if not selected(name):
                continue
            text = path.read_text(encoding='utf-8')
            standalone = bool(re.search(r'\bmain\s*\(', text))
            if not standalone:
                reason = ('declaration-only include unit; exercised by its clients' if path.name.endswith('.include.azs')
                          else 'library unit has no main entry point; exercised through linked clients')
                records.extend({'name': name, 'limit': limit, 'status': 'not-standalone', 'reason': reason} for limit in LIMITS)
                continue
            baseline = None
            for limit in LIMITS:
                artifact, entry = compile_case(name, path, limit, allow_depth_failure=True)
                if artifact is None:
                    print(f'Limits differential: {relative} at{limit} infeasible: {entry["reason"]}', flush=True)
                    continue
                libraries = []
                for dependency in dependency_paths.get(relative, []):
                    library, failed = compile_case('dependency-' + dependency.stem, dependency, limit, allow_depth_failure=True)
                    if library is None:
                        entry.update(status='infeasible', reason=f'Linked dependency {dependency.name}: {failed["reason"]}')
                        break
                    libraries.append(library)
                if entry['status'] == 'infeasible':
                    print(f'Limits differential: {relative} at{limit} infeasible: {entry["reason"]}', flush=True)
                    continue
                result = run_case(name, artifact, libraries)
                assert result.returncode == 0 and not result.stderr, (relative, limit, result.stdout, result.stderr)
                signature = (result.returncode, result.stdout, result.stderr)
                if limit == 128:
                    baseline = (signature, artifact['metrics'].copy())
                else:
                    assert baseline is not None and signature == baseline[0], (relative, limit, signature, baseline)
                    if artifact['metrics']['locals'] > baseline[1]['locals'] and artifact['metrics']['defines'] > baseline[1]['defines']:
                        spills[limit].append(name)
                entry['status'] = 'passed'
                checks += 1

        if args.only is None:
            assert all(spills.values()), ('Spilling must occur in the differential corpus at every forced limit', spills)
        report = {'test_limits': LIMITS, 'checks': checks, 'spilling_exercised': spills, 'cases': records}
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'Limits end-to-end: {checks} checks passed; detailed feasible/infeasible reasons and timings: {args.report}')


if __name__ == '__main__':
    main()
