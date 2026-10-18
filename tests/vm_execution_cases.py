"""Specified source fixtures for the incremental scalar/script-call VM gate.

These sources use the real compiler, including debug data and lifecycle lowering.
Malformed hand-written wire operands (such as return used as a call argument)
belong to the separate VM instruction tests, not this source-language inventory.
"""
from dataclasses import dataclass

from runtime_differential import Case, typed


@dataclass
class ExecutionCase(Case):
    stage: str = 'scalar'
    trace_names: tuple = ()
    # Optional source lines of the trace frames, innermost first (0 = header).
    trace_lines: tuple = ()
    # These are inserted after the primary module in the listed order. The
    # independent reference adapter supports --insert without altering its VM.
    modules: tuple = ()


def event_list(values):
    return [{'callback': 1, 'arguments': [typed(0, value)]} for value in values]


def case(name, source, result=None, *, tag=0, stage='scalar', error='', globals=None,
         trace=(), options=(), sweep=True, modules=(), events=(), raw_events=None, category='',
         remaining=None, teardown_events=None, raw_teardown_events=None, teardown_error=None,
         teardown_heap=None, stdout=None, partial=False, lines=()):
    # events lists the int arguments of each event(int) callback, in order.
    return ExecutionCase(name=name, source=source, expected_return=typed(tag, result),
                         expected_error=error, expected_globals=[] if globals is None else globals,
                         expected_events=raw_events if raw_events is not None else event_list(events),
                         expected_stdout='' if stdout is None else None, expected_stdout_text=stdout,
                         stage=stage, trace_names=trace, trace_lines=tuple(lines),
                         options=list(options), sweep=sweep, modules=modules,
                         expected_error_category=category, expected_remaining=remaining,
                         expected_teardown_events=raw_teardown_events if raw_teardown_events is not None else
                         (None if teardown_events is None else event_list(teardown_events)),
                         expected_teardown_error=teardown_error, expected_teardown_heap=teardown_heap,
                         partial_events=partial)


EVENT = 'extern int event(int):6dff0001;\n'
MARK = EVENT + 'class Mark { int n; Mark(int v){n=v;event(v);} ~Mark(){event(0-n);} }\n'


def object_cases():
    """Class values, heap objects, ownership transfer and destructor order."""
    def objects(name, source, result=None, **options):
        return case(name, source, result, stage='objects', **options)
    return [
        objects('scope-destruction-order', MARK + 'int main(){ {Mark a(1); {Mark b(2);}} return 12;}\n', 12,
                events=(1, 2, -2, -1)),
        objects('value-copy-independent', 'class Cell { int n; Cell(int v){n=v;} }\n'
                'int main(){Cell original(5);Cell copy=original;copy.n=copy.n+1;return original.n*10+copy.n;}\n', 56),
        objects('return-moves-local', MARK + 'Mark make(int v):0011{Mark m(v);return m;}\n'
                'int main(){Mark x=make(3);return x.n;}\n', 3, events=(3, -3)),
        objects('temporary-argument', MARK + 'Mark make(int v):0011{Mark m(v);return m;}\n'
                'int read(Mark m):0012{return m.n;}\nint main(){int r=read(make(4));event(100);return r;}\n', 4,
                events=(4, -4, 100)),
        objects('copied-argument', MARK + 'int read(Mark m):0012{m.n=m.n+1;return m.n;}\n'
                'int main(){Mark a(6);int r=read(a);return r*10+a.n;}\n', 76, events=(6, -7, -6)),
        objects('manual-heap-object', MARK + 'int main(){Mark* p=new Mark(7);int v=p.n;delete p;event(50);return v;}\n', 7,
                events=(7, -7, 50)),
        objects('inheritance-order', EVENT + 'class A { int a; A(){event(1);} ~A(){event(-1);} }\n'
                'class B : A { int b; B(){event(2);} ~B(){event(-2);} }\n'
                'int main(){ {B value;} return 0;}\n', 0, events=(1, 2, -2, -1)),
        objects('field-construction-rollback', EVENT + 'class Part { int n; Part(int v){n=v;event(v);} ~Part(){event(0-n);} }\n'
                'class Whole { Part a(1); Part b(2); Whole(){event(3);int zero=0;int bad=1/zero;} }\n'
                'int main(){Whole w;return 0;}\n', error='Division by zero', events=(1, 2, 3, -2, -1)),
        objects('destructor-error-at-scope-exit', EVENT + 'class Bad { int n; ~Bad(){event(9);int zero=0;int x=1/zero;} }\n'
                'int main(){ {Bad value;} return 1;}\n', error='Division by zero', events=(9,)),
        objects('first-error-kept', EVENT + 'class Bad { int n; ~Bad(){event(9);int zero=0;int x=1/zero;} }\n'
                'int main(){Bad value;return 2147483647+1;}\n', error='Integer overflow', events=(9,)),
        objects('virtual-dispatch', 'class Base { int n; virtual int f(){return 1;} int run(){return f();} }\n'
                'class Child : Base { override int f(){return Base::f()+10;} }\n'
                'int main(){Base* p=new Child();int value=p.run();delete p;return value;}\n', 11),
        objects('virtual-alternating', 'class Base { int n; virtual int f(int v){return v+1;} }\n'
                'class First : Base { override int f(int v){return v+3;} }\nclass Second : Base { override int f(int v){return v+5;} }\n'
                'int main(){Base* a=new First();Base* b=new Second();int t=0;int i=0;'
                'while(i<4){Base* c=a;if(i%2!=0){c=b;}t=t+c.f(i);i=i+1;}delete b;delete a;return t;}\n', 22),
        objects('loop-objects-break', MARK + 'int main(){int i=0;while(true){Mark m(i);if(i==2)break;i=i+1;}return i;}\n', 2,
                events=(0, 0, 1, -1, 2, -2)),
        objects('raw-heap-scope', 'int main(){address p=alloc(2);mem_get(p)=5;mem_get(p+1)=6;'
                'var a=mem_get(p);var b=mem_get(p+1);return int(a)*10+int(b);}\n', 56),
        objects('raw-heap-send-up', 'address up():0011{address p=alloc(1);mem_get(p)=4;mem_send_up(p);return p;}\n'
                'int main(){address q=up();var v=mem_get(q);return int(v);}\n', 4),
        objects('raw-heap-free-and-owner', 'int main(){address p=alloc(1);boolean freed=mem_free(p);'
                'address q=alloc(1);make_free(q);mem_get(q)=3;var v=mem_get(q);int r=int(v);mem_free(q);'
                'if(freed){return r;}return 0;}\n', 3),
        # Destructors run as nested invocations during scope cleanup. They must
        # observe the suspended frame's globals and publish their own writes.
        objects('destructor-reads-global', EVENT + 'int total=0;\nclass R { int n; ~R(){event(total);} }\n'
                'int main(){total=3;{R a;} return total;}\n', 3, events=(3,), globals=[typed(0, 3)]),
        objects('destructor-writes-global', 'int total=0;\nclass R { int n; R(int v){n=v;} ~R(){total=total+n;} }\n'
                'int main(){total=1;{R a(5);} total=total*10;return total;}\n', 60, globals=[typed(0, 60)]),
        objects('destructor-host-reentry', 'extern int reenter(int,int):6dff0004;\nint total=0;\n'
                'int bump(int v):0011{total=total+v;return total;}\n'
                'class R { int n; R(int v){n=v;} ~R(){reenter(268369937,n);} }\n'
                'int main(){total=1;{R a(5);} return total;}\n', 6, globals=[typed(0, 6)],
                raw_events=[{'callback': 4, 'arguments': [typed(0, 268369937), typed(0, 5)]},
                            {'reentry_return': typed(0, 6)}]),
        # A member assignment's operands end with it: a target whose container
        # the value's call deleted abandons the stored object, so its result
        # has expired when the declaration copies it.
        objects('assignment-into-deleted-container', MARK + 'class H { Mark f(1); }\n'
                'Mark kill(H* x):0011{delete x;Mark m(7);return m;}\n'
                'int main(){H* h=new H();Mark x=(h.f=kill(h));return x.n;}\n',
                error='Object value has expired', events=(1, -1, 7), trace=('main',)),
        objects('method-on-local', 'class Counter { int n; Counter(){n=0;} void add(int v){n=n+v;} int get(){return n;} }\n'
                'int main(){Counter c;int i=0;while(i<3){c.add(i);i=i+1;}return c.get();}\n', 3),
    ]


def cases(stage='all'):
    result = [
        case('zero-loop', 'int main(){int n=7;for(int i=0;i<0;i=i+1){n=n+100;}return n;}\n', 7),
        case('break-continue', 'int main(){int n=0;for(int i=0;i<6;i=i+1){if(i==2)continue;if(i==5)break;n=n+i;}return n;}\n', 8),
        case('nested-scope', 'int main(){int n=2;{int n=7;n=n+3;}return n;}\n', 2),
        case('return-from-loop', 'int main(){int i=0;while(i<3){int n=i+1;if(n==2)return n;i=i+1;}return 9;}\n', 2),
        case('short-circuit', 'int main(){int n=0;boolean a=false&&((n=1)==1);boolean b=true||((n=2)==2);return n;}\n', 0),
        case('select-snapshot', 'int main(){int n=3;return (true?n:(n=8))+(n=4);}\n', 7),
        case('binary-snapshot', 'int main(){int n=7;return n+(n=11);}\n', 18),
        case('unicode-string', 'string main(){string s="A😀";return s+"!";}\n', 'A😀!'.encode().hex(), tag=1),
        case('mixed-numeric', 'double main(){double n=1.5;float m=float(2);return n+m;}\n', '400c000000000000', tag=3),
        case('float-negative-zero', 'float main(){return -0.0f;}\n', '80000000', tag=2),
        case('address-offset', 'address main(){address p=null;p=p+3;return p+4;}\n', '0000000000000007', tag=7),
        case('void-entry', 'void main(){int n=1;n=n+1;}\n', None, tag=5),
        case('comparisons', 'boolean main(){return !(3<2)&&("x"=="x");}\n', True, tag=4),
        case('bit-pattern', 'int main(){return (-1>>>1)^((3<<2)&15);}\n', 2147483635),
        case('overflow', 'int main(){return 2147483647+1;}\n', error='Integer overflow', trace=('main',)),
        case('divide-zero', 'int main(){\nint n=0;return 5/n;\n}\n', error='Division by zero', trace=('main',)),
        case('bad-shift', 'int main(){int n=32;return 1<<n;}\n', error='Shift count must be between 0 and 31, got 32', trace=('main',)),
        case('global-lifecycle', 'int n=3;\nvoid __script_onload(){n=n+2;}\nvoid __script_pre_destroy(){n=0;}\nint main(){n=n+4;return n;}\n',
             9, globals=[typed(0, 9)]),
        case('mutable-global-tag', 'int n=1;\ndouble main(){return n+2;}\n', '4012000000000000', tag=3,
             globals=[typed(3, '4004000000000000')], options=['--set-global', '0=d:4004000000000000']),
        case('recursion', 'int factorial(int n):0011{if(n<=1)return 1;return n*factorial(n-1);}\nint main(){return factorial(5);}\n',
             120, stage='calls'),
        case('mutual-recursion', 'boolean even(int n):0011{if(n==0)return true;return odd(n-1);}\nboolean odd(int n):0012{if(n==0)return false;return even(n-1);}\nboolean main(){return even(5);}\n',
             False, tag=4, stage='calls'),
        case('argument-snapshots', 'int n=1;\nint combine(int a,int b,int c):0011{return 100*a+10*b+c;}\nint main(){return combine(n,n=2,n=3);}\n',
             123, globals=[typed(0, 3)], stage='calls'),
        case('argument-order', 'int n=0;\nint mark(int a):0011{n=n*10+a;return a;}\nint combine(int a,int b,int c):0012{return 100*a+10*b+c;}\nint main(){int value=combine(mark(1),mark(2),mark(3));return value+n;}\n',
             246, globals=[typed(0, 123)], stage='calls'),
        case('parameter-copy', 'int change(int n):0011{n=9;return n;}\nint main(){int n=4;return change(n)+n;}\n', 13, stage='calls'),
        case('nested-error-trace', 'int deep(int a):0011{return 10/a;}\nint mid(int a):0012{return deep(a-1)+1;}\nint main(){return mid(1);}\n',
             error='Division by zero', trace=('deep', 'mid', 'main'), stage='calls'),
        case('return-widening', 'float widen(int n):0011{return n;}\nfloat main(){return widen(16777217);}\n',
             '4b800000', tag=2, stage='calls'),
        case('bad-return', 'int narrow(value):0011{return value;}\nint main(){return narrow(1.5);}\n',
             error='Function return type mismatch: expected int, got double', trace=('narrow', 'main'), stage='calls'),
        # Typed source cannot pass a dynamic value to a typed parameter; the
        # runtime CALL type check after argument effects is covered by the
        # hand-written scalar_vm_tests case call-type-after-side-effect.
        case('argument-error-after-effects', 'int n=0;\nint consume(int a,int b):0011{return a+b;}\nint change():0012{n=9;return n;}\nint main(){int zero=0;return consume(change(),10/zero);}\n',
             error='Division by zero', globals=[typed(0, 9)], trace=('main',), stage='calls'),
        case('entry-argument-type', 'int consume(int n):0011{return n;}\nint main(){return 0;}\n',
             error='Script function 0x0fff0011 argument 1 type mismatch: expected int, got double', stage='calls',
             options=['--function', '0x0fff0011', '--arg', 'd:3ff0000000000000']),
        case('entry-argument-count', 'int consume(int n):0011{return n;}\nint main(){return 0;}\n',
             error='Script function 0x0fff0011 argument count mismatch: expected 1, got 0', stage='calls',
             options=['--function', '0x0fff0011']),
        case('call-depth', 'int recur(int n):0011{if(n==0)return 0;return recur(n-1)+1;}\nint main(){return recur(9);}\n',
             error='Script call depth limit exceeded', stage='calls', options=['--call-depth', '4']),
        case('void-call', 'void discard(int n):0011{int m=n+1;}\nint main(){discard(3);return 8;}\n', 8, stage='calls'),
        case('hint-global-offset', '#assume_hint VM_SCALAR_LIB 3456\nextern int update(int):34560002;\nint local=7;\nint main(){return update(local)+local;}\n',
             19, globals=[typed(0, 7), typed(0, 12)], stage='calls', modules=(
                 '#namespace_hint VM_SCALAR_LIB\nint other=5;\nint update(int n):0002{other=other+n;return other;}\n',)),
    ]
    for count in (0, 1, 4, 16):
        parameters = ','.join(f'int p{i}' for i in range(count))
        arguments = ','.join(str(i + 1) for i in range(count))
        expression = '+'.join(f'p{i}' for i in range(count)) if count else '42'
        result.append(case(f'arity-{count}', f'int target({parameters}):0011{{return {expression};}}\nint main(){{return target({arguments});}}\n',
                           sum(range(1, count + 1)) if count else 42, stage='calls'))
    result.extend(object_cases())
    if stage in ('ownership', 'all'):
        from conformance import cases as conformance_cases
        result.extend(conformance_cases())
    return [item for item in result if stage == 'all' or item.stage == stage]
