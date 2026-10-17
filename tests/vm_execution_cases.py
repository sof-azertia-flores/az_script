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
    # These are inserted after the primary module in the listed order. The
    # independent reference adapter supports --insert without altering its VM.
    modules: tuple = ()


def case(name, source, result=None, *, tag=0, stage='scalar', error='', globals=None,
         trace=(), options=(), sweep=True, modules=()):
    return ExecutionCase(name=name, source=source, expected_return=typed(tag, result),
                         expected_error=error, expected_globals=[] if globals is None else globals,
                         expected_events=[], expected_stdout='', stage=stage,
                         trace_names=trace, options=list(options), sweep=sweep, modules=modules)


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
        case('address-offset', 'address main(){address p=address(3);return p+4;}\n', '0000000000000007', tag=7),
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
        case('argument-type-after-effects', 'int n=0;\nint consume(int a,int b):0011{return a+b;}\nint change():0012{n=9;return n;}\nint main(){def(value,"bad");return consume(value,change());}\n',
             error='Script function 0x0fff0011 argument 1 type mismatch: expected int, got string',
             globals=[typed(0, 9)], stage='calls'),
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
    return [item for item in result if stage == 'all' or item.stage == stage]
