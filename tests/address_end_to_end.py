#!/usr/bin/env python3
"""Distinct uint64 addresses across source, AST, exec, heap and module calls."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from compact_exec_end_to_end import (Address, MAIN, constant, frame, function,
                                    module, returning, stack)


ROOT = Path(__file__).resolve().parents[1]
HIGH = 0x8000000000000001
MAXIMUM = 2**64 - 1
LIBRARY_NAMESPACE = 0x2345
HIGH_ID = (LIBRARY_NAMESPACE << 16) | 2
MAXIMUM_ID = (LIBRARY_NAMESPACE << 16) | 3
IMPORTS = 'extern address high():23450002; extern address maximum():23450003;\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--host', type=Path)
    args = parser.parse_args()
    compiler = ['java', '-cp', args.classpath, 'azertia.Main']
    checks = 0
    with tempfile.TemporaryDirectory(prefix='address-', dir=ROOT / 'build') as directory:
        work = Path(directory)

        def compile_case(source, name, valid=True):
            src = work / (name + '.azs')
            src.write_text(source, encoding='utf-8')
            abd, ast, executable = (work / (name + suffix)
                                    for suffix in ('.abd', '.ast.json', '.exec.json'))
            result = subprocess.run(compiler + ['compile', str(src), '-o', str(abd),
                                    '--ast', str(ast), '--exec-json', str(executable)],
                                    cwd=ROOT, capture_output=True, text=True, timeout=30)
            if not valid:
                assert result.returncode != 0 and 'Error:' in result.stderr, (name, result.stdout, result.stderr)
                assert 'Exception in thread' not in result.stderr, (name, result.stderr)
                return None, None
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            roundtrip = work / (name + '.roundtrip.abd')
            again = subprocess.run(compiler + ['compile-json', str(ast), '-o', str(roundtrip)],
                                   cwd=ROOT, capture_output=True, text=True, timeout=30)
            assert again.returncode == 0, (name, again.stdout, again.stderr)
            assert abd.read_bytes() == roundtrip.read_bytes(), (name, 'AST roundtrip differs')
            tree = json.loads(executable.read_text(encoding='utf-8'))
            assert tree['exec-version'] == 7, (name, tree)
            return abd, tree

        def run_case(name, abd, output='', error=None, libraries=()):
            nonlocal checks
            command = [str(args.runner), str(abd)]
            for library in libraries:
                command += ['--insert', str(library)]
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=20)
            assert result.stdout == output, (name, 'stdout', result.stdout, output, result.stderr)
            if error is None:
                assert result.returncode == 0 and not result.stderr, (name, result.stderr)
            else:
                assert result.returncode != 0 and 'AzScript error:' in result.stderr, (name, result.stderr)
                if error is not True:
                    assert error.lower() in result.stderr.lower(), (name, result.stderr, error)
            checks += 1

        def execute(name, source, output='', error=None, libraries=()):
            abd, tree = compile_case(source, name)
            run_case(name, abd, output, error, libraries)
            return abd, tree

        execute('null-address', '''address identity(address p){return p;}
            address empty(){return null;}
            void main(){address p=null;print(p);print(p==null);print(null==identity(p));
            print(empty()==p);print("address="+p);}''', '0\ntrue\ntrue\ntrue\naddress=0\n')
        execute('integer-offsets', '''void main(){address start=null;
            address a=start+9;address b=2+a;address c=b-4;address d=c+(-2);
            print(a);print(b);print(c);print(d);print(start-(-3));
            print(d<a);print(d<=d);print(b>a);print(a>=a);print(a!=d);}''',
            '9\n11\n7\n5\n3\ntrue\ntrue\ntrue\ntrue\ntrue\n')
        execute('assignment-and-increment', '''void main(){address p=null;p+=4;p-=1;
            print(p);p++;print(p);p++;print(p);p--;print(p);p--;print(p);}''',
            '3\n4\n5\n4\n3\n')
        execute('minimum-int-offset', '''void main(){address p=null;
            print(p-(-2147483648));print(p+2147483647+1);}''', '2147483648\n2147483648\n')
        execute('for-slots-and-continue', '''int read(address p){return mem_get(p);}
            void main(){address start=alloc(4);int n=1;
            for(address p=start;p<start+4;p++){mem_get(p)=n;n++;}
            int sum=0;for(address p=start;p<start+4;p++){
                if(p==start+1){continue;}sum+=read(p);}
            print(sum);print(mem_get(start+1));}''', '8\n2\n')
        execute('addresses-stored-in-slots', '''address read(address slot){return mem_get(slot);}
            void main(){address data=alloc(2);address refs=alloc(2);
            mem_get(data)=41;mem_get(data+1)=42;mem_get(refs)=data;mem_get(refs+1)=null;
            address alias=read(refs);print(mem_get(alias));print(mem_get(alias+1));
            print(mem_get(refs+1)==null);mem_get(refs)=alias+1;print(mem_get(mem_get(refs)));}''',
            '41\n42\ntrue\n42\n')
        execute('address-field-defaults', '''class Holder{address pointer;Holder* next;}
            void main(){Holder h;print(h.pointer);print(h.pointer==null);print(h.next==null);
            address p=alloc(1);mem_get(p)=7;h.pointer=p;print(mem_get(h.pointer));}''',
            '0\ntrue\ntrue\n7\n')
        execute('address-field-initialization-order', '''address offset(int n){address p=null;return p+n;}class Holder{
            address first=later+3;address later=offset(8);}
            void main(){Holder h;print(h.first);print(h.later);}''', '3\n8\n')
        execute('address-inheritance-layout', '''class Base{address pointer;int value=5;
            address get(){return pointer;}}
            class Child:Base{address next;int extra=7;}
            void main(){Child * c();c.pointer=alloc(2);c.next=c.pointer+1;
            mem_get(c.get())=11;mem_get(c.next)=13;Base* b=c;
            print(mem_get(b.get()));print(mem_get(c.next));print(b.value+c.extra);}''', '11\n13\n12\n')
        execute('structural-address-fields', '''class A{address p;A* next;}
            class B{address renamed;B* link;}
            address offset(){address p=null;return p+4;}void main(){A a;a.p=offset();B b=a;print(b.renamed);print(b.link==null);}''', '4\ntrue\n')
        execute('raw-return-transfer', '''address create(){address p=alloc(2);mem_get(p)=7;
            mem_get(p+1)=9;mem_send_up(p);return p;}
            address forward(){address p=create();mem_send_up(p);return p;}
            void main(){address p=forward();print(mem_get(p)+mem_get(p+1));}''', '16\n')
        execute('raw-return-without-transfer', '''address create(){address p=alloc(1);return p;}
            void main(){address p=create();print(mem_get(p));}''', error='Invalid or freed heap pointer')
        execute('manual-memory-return', '''address create(){address p=alloc(1);mem_get(p)=17;
            make_free(p);return p;}
            void main(){address p=create();print(mem_get(p));print(mem_free(p));}''', '17\ntrue\n')
        execute('borrowed-raw-address', '''address borrow(address p){return p;}
            void main(){address p=alloc(1);mem_get(p)=19;{address q=borrow(p);print(mem_get(q));}
            print(mem_get(p));}''', '19\n19\n')
        execute('zero-allocation', '''void main(){address p=alloc(0);print(p==null);print(mem_free(p));}''', 'true\nfalse\n')
        execute('null-dereference', 'void main(){address p=null;print(mem_get(p));}', error='Invalid or freed heap pointer')
        execute('address-underflow', 'void main(){address p=null;print(p-1);}', error='Address underflow')
        execute('negative-offset-underflow', 'void main(){address p=null;print(-1+p);}', error='Address underflow')
        execute('decrement-underflow', 'void main(){address p=null;p--;}', error='Address underflow')
        execute('freed-address', '''void main(){address p=alloc(1);mem_free(p);print(mem_get(p));}''', error='Invalid or freed heap pointer')
        execute('dynamic-address-copy', '''void main(){var p=alloc(1);var q=p;mem_get(q)=23;
            print(mem_get(p));print(q+1);print(q==p);}''', '23\n2\ntrue\n')
        execute('dynamic-address-return', '''address identity(){var p=null;return p;}
            void main(){address p=null;print(identity()==p);}''', 'true\n')
        execute('dynamic-null-equality', '''void main(){var p=null;print(p==null);print(null==p);
            var q=alloc(1);print(q!=null);print(null!=q);}''', 'true\ntrue\ntrue\ntrue\n')
        execute('dynamic-unary-plus-numeric', '''void main(){var zero=-0.0;
            print(+zero);var integer=7;print(+integer);}''', '-0\n7\n')
        execute('dynamic-unary-plus-once', '''#gvar evaluations
            address next(address p){evaluations+=1;return p;}
            void main(){evaluations=0;address p=alloc(1);mem_get(p)=-0.0;
            print(+mem_get(next(p)));print(evaluations);}''', '-0\n1\n')
        execute('dynamic-class-reference-erases-to-address', '''class C{int x=17;}
            void main(){var raw=new C();print(mem_get(raw));print(mem_free(raw));}''', '17\ntrue\n')

        bad_sources = {
            'dynamic-address-argument': 'address take(address p){return p;}void main(){var n=null;take(n);}',
            'dynamic-int-argument': 'int take(int p){return p;}void main(){var n=1;take(n);}',
            'dynamic-address-initializer': 'void main(){address slot=alloc(1);mem_get(slot)=null;address p=mem_get(slot);}',
            'int-initializer': 'void main(){address p=1;}',
            'zero-is-not-null': 'void main(){address p=0;}',
            'address-to-int': 'void main(){int n=alloc(1);}',
            'null-to-int': 'void main(){int n=null;}',
            'assign-int': 'void main(){address p=null;p=1;}',
            'int-argument': 'void take(address p){}void main(){take(1);}',
            'address-to-int-argument': 'void take(int p){}void main(){take(alloc(1));}',
            'int-return': 'address bad(){return 1;}void main(){}',
            'address-return-as-int': 'int bad(){return alloc(1);}void main(){}',
            'int-memory-read': 'void main(){mem_get(1);}',
            'int-memory-free': 'void main(){mem_free(1);}',
            'int-memory-detach': 'void main(){make_free(1);}',
            'int-memory-transfer': 'void main(){mem_send_up(1);}',
            'address-size': 'void main(){alloc(null);}',
            'address-equals-int': 'void main(){address p=null;print(p==0);}',
            'int-equals-address': 'void main(){address p=null;print(0==p);}',
            'address-order-int': 'void main(){address p=null;print(p<1);}',
            'float-offset': 'void main(){address p=null;print(p+1.0);}',
            'address-plus-address': 'void main(){address p=null;print(p+p);}',
            'address-minus-address': 'void main(){address p=null;print(p-p);}',
            'int-minus-address': 'void main(){address p=null;print(1-p);}',
            'address-multiply': 'void main(){address p=null;print(p*2);}',
            'address-negate': 'void main(){address p=null;print(-p);}',
            'address-condition': 'void main(){address p=null;if(p){print(1);}}',
            'address-member': 'void main(){address p=null;print(p.x);}',
            'address-delete': 'void main(){address p=alloc(1);delete p;}',
            'class-to-raw': 'class C{int x;}void main(){C c;address p=c;}',
            'raw-to-class': 'class C{int x;}void main(){address p=alloc(1);C c=p;}',
            'class-memory-builtin': 'class C{int x;}void main(){C c;mem_get(c);}',
            'class-offset': 'class C{int x;}void main(){C c;print(c+1);}',
            'class-equals-raw': 'class C{int x;}void main(){C c;address p=null;print(c==p);}',
            'address-int-layout': 'class A{address p;}class B{int n;}void main(){A a;B b=a;}',
            'address-class-layout': 'class A{address p;}class B{B* next;}void main(){A a;B b=a;}',
            'address-reserved-class': 'class address{int n;}void main(){}',
        }
        for name, source in bad_sources.items():
            compile_case(source, 'invalid-' + name, valid=False)
            checks += 1

        dynamic_sources = {
            'int-as-address-return': ('address bad(){var n=1;return n;}void main(){bad();}', 'return type mismatch'),
            'address-as-int-return': ('int bad(){var p=alloc(1);return p;}void main(){bad();}', 'return type mismatch'),
            'int-as-memory-address': ('void main(){var n=1;mem_get(n);}', 'address argument'),
            'address-as-size': ('void main(){var p=null;alloc(p);}', 'int argument'),
            'unary-plus-address': ('void main(){var p=null;print(+p);}', 'address operands'),
            'mixed-equality': ('void main(){var p=null;var n=0;print(p==n);}', 'address operands'),
            'mixed-order': ('void main(){var p=null;var n=1;print(p<n);}', 'address operands'),
            'address-difference': ('void main(){var a=null;var b=null;print(a-b);}', 'address operands'),
            'float-offset': ('void main(){var p=null;var n=1.0;print(p+n);}', 'address operands'),
        }
        for name, (source, error) in dynamic_sources.items():
            execute('dynamic-' + name, source, error=error)

        # Values above signed 32- and 64-bit ranges come from a separately encoded
        # module, so these checks do not depend on the compiler's scalar writer.
        library = work / 'independent-address-library.abd'
        library.write_bytes(frame(stack(*module([
            function(returning(constant(Address(HIGH))), HIGH_ID, return_type=7),
            function(returning(constant(Address(MAXIMUM))), MAXIMUM_ID, return_type=7),
        ]))))
        execute('independent-high-addresses', IMPORTS + '''void main(){address p=high();address m=maximum();
            print(p);print(m);print(p+3);print(2+p);print(p-3);print(m-1);
            print(m>p);print(m==maximum());print("high="+m);}''',
            f'{HIGH}\n{MAXIMUM}\n{HIGH+3}\n{HIGH+2}\n{HIGH-3}\n{MAXIMUM-1}\ntrue\ntrue\nhigh={MAXIMUM}\n', libraries=(library,))
        execute('high-address-in-memory', IMPORTS + '''address read(address slot){return mem_get(slot);}
            void main(){address slot=alloc(1);
            mem_get(slot)=maximum();address copy=read(slot);print(copy);print(copy==maximum());}''',
            f'{MAXIMUM}\ntrue\n', libraries=(library,))
        for name, body, message in [
            ('overflow-add', 'print(maximum()+1);', 'Address overflow'),
            ('overflow-negative-subtract', 'print(maximum()-(-1));', 'Address overflow'),
            ('overflow-increment', 'address p=maximum();p++;', 'Address overflow'),
            # Bit 63 marks literal-object storage; no object has id 0, so the address never resolves.
            ('high-address-access', 'address p=alloc(1);mem_get(p)=17;print(mem_get(high()));', 'Invalid or expired object address'),
        ]:
            execute(name, IMPORTS + 'void main(){' + body + '}', error=message, libraries=(library,))
        execute('high-address-free', IMPORTS + 'void main(){print(mem_free(maximum()));}', 'false\n', libraries=(library,))

        # Structural type erasure still distinguishes object/raw-address values
        # from ordinary integer values in every runtime function signature.
        _, layout = compile_case('''class C{address p;C* next;C(address p){this.p=p;}
            address get(){return p;}C* self(){return this;}~C(){}}
            address echo(address p){return p;}void main(){C c(null);print(c.self().get());}''', 'signature-erasure')
        assert any(f['return-type'] == 7 for f in layout['f']), layout
        assert all(t in (0, 1, 2, 3, 4, 6, 7) for f in layout['f'] for t in f['param-types']), layout
        assert any(f['param-types'] == [7, 7] for f in layout['f']), layout
        assert all(f['param-types'][0] == 7 for f in layout['f'] if len(f['param-types']) == 2), layout
        checks += 1

        example, _ = execute('documented-example', (ROOT / 'compiler/examples/address-regressions.azs').read_text(encoding='utf-8'), '0\n')
        if args.host:
            result = subprocess.run([str(args.host), str(example), 'flush', 'call', str(MAIN), '0', 'heap-empty'],
                                    cwd=ROOT, capture_output=True, text=True, timeout=20)
            assert result.returncode == 0 and result.stdout == '0\nempty\n' and not result.stderr, (result.stdout, result.stderr)
            checks += 1
            cleanup, _ = compile_case('''class C{address p;C(){p=alloc(1);mem_send_up(p);}~C(){print("cleanup");}}
                void main(){C c;address p=alloc(2);mem_get(p)=p+1;var n=0;print(p==n);}''', 'error-cleanup')
            result = subprocess.run([str(args.host), str(cleanup), 'flush', 'reject-call', str(MAIN), '0', 'heap-empty'],
                                    cwd=ROOT, capture_output=True, text=True, timeout=20)
            assert result.returncode == 0 and result.stdout == 'cleanup\nrejected\nempty\n' and not result.stderr, (result.stdout, result.stderr)
            checks += 1
    print(f'Address end-to-end checks passed: {checks} (source/AST roundtrip, independent uint64 wire, strict types and heap cleanup).')


if __name__ == '__main__':
    main()
