"""Group D: destruction chains (cleanup spec section 12 group D, D1-D14 with
verifier correction 2), virtual tables across construction and destruction,
destructors that create and destroy objects, fields that read dropped siblings
or use expired field addresses, heap/literal container visibility during the
native field sweep (verifier addition 12), the destructor context of a base
destructor called from a cleanup step (verifier addition 18), the lifecycle
tick inventory and budget stopping points (verifier addition 17), and the
literal-versus-heap differences of a destruction.

Case names start with "own-dst" so that --only own-dst selects exactly this
group ("own-d" also matches names containing "teardown-d").

Expectations are derived from the tree-interpreter specification and the
interpreter/compiler source, not from observed output:
- A compiled destructor is cleanup(on_error=false, body=[#check-this,
  #set-methods, user statements], final=SEQ(drop last own owned field .. drop
  first, call Base.cleanupDestructor(this))) (Compiler.java:2832-2837,
  2869-2883). SEQ is a balanced tree of cleanup(on_error=false) nodes, so every
  step runs and the first error wins. When final is empty the body block runs
  on the destructor root (unwrapped). A class without a destructor but with
  owned fields gets a synthesized C::<dtor>; one without either binds its
  base's cleanup destructor (Compiler.java:2468-2470, 2489).
- Each step of final runs through scoped_execute in its own scope (library.cpp:
  843-849), so a drop passes that step scope as the field destructor's caller
  (library.cpp:948-953) and a base-destructor call node runs in it, making it
  the base destructor root's caller (library.cpp:2084-2133, verify addition 18).
  mem_send_up targets transfer_parent: the caller if set, else the lexical
  parent (heepalloc.cpp:466-469, 512-523); set_owner refuses a closing scope
  (heepalloc.cpp:125-132).
- finalize_into: destroying, destructor, then the native sweep of owned slots
  last first, then dead/methods reset (blocks.cpp:240-258). destroy_registered:
  destroying=true, destructor, finalize_storage of the heap slots last first,
  objects.erase, free (heepalloc.cpp:350-382).
- Synthesized nodes (#drop, #cleanup, SEQ, #check-this, the base-destructor
  call) carry no position, #set-methods restores its position on normal exit,
  and blocks carry none, so a failure inside final reports the enclosing
  destructor at its header (verify cleanup correction 2); a synthesized
  destructor's header is its class declaration (Compiler.java:3291-3296). A
  body failure keeps the failing node's position, also when final fails later
  (cleanup_expression had_failure, library.cpp:261-268, 862-875).
- Every constructor/destructor body installs its own class's method table first
  (Compiler.java:2798-2801), so virtual calls in ~C use C's table and in ~B use
  B's; an empty table clears the binding (heepalloc.cpp:269-270, 1015-1026).
  After the chain a literal keeps the last table until it is dead
  (blocks.cpp:254), a heap object until free (heepalloc.cpp:276-282).
Debug display names: C::~C for explicit and synthesized destructors
(Compiler.java:3303-3317, contextName :824).

Trace lines. The driver compares frame lines only, so every frame whose
position is specified has its competing positions (failing node, destructor
header, class declaration, last body statement, main's header/return/delete)
on different source lines; line_of() finds them in the source text.

Step counts (remaining, probe remaining_steps) come from the compiled lowering
(compiler exec JSON shapes) and the tick rules of library.cpp: every executed
node ticks once on entry (constants, variables, oa, member access, binary
operators, calls, vd/vs/m, drop, ob, ro/r, new_block, block_address), a block
ticks once in execute_body, cleanup once inside its try (:860), set_methods
1 + 1 per entry (:1015-1018), while once on entry and once per body
(:2219-2232). Host calls, builtins, GUARD, drivers and copies tick nothing;
copy_block charges each copied block's slot count (blocks.cpp:74-79, 177).
The main budget is reset to max_steps (1000000) at main's GUARD (library.cpp:776).
Costs of the shared chain classes F, B, G, C (no constructors written):
  F::F / G::G 10 (root 1, #check-this 2, #set-methods 2, n = 0: m 1 + member 3 + const 1)
  <value F> / <value G> 21 (root 1, vd+new_block 2, ctor call 3 + 10, ob 3, ro 2)
  B::B 32 (cleanup 1, block 1, 2, 2, bf = <value F>: m 1 + member 3 + call 1 + 21)
  C::C 75 (root 1, 2, flag vd 2, cleanup 1, block 1, vs flag = eq(B::B, void)
          1+1+1+1+32+1, nested block 1 + 2 + 2 + cg = <value G> 26)
  <value C> 86; `C c;` 88 (vd 1 + call 1 + 86); <new C> 85 (root 1,
          object_alloc vd 3, ctor call 2 + 75, ob 2, ro 2)
  ~F / ~G 7 (root 1, 2, 2, event call 1 + const 1); ~B 19 (cleanup 1, body 7,
          drop 4 + ~F 7); ~C 41 (cleanup 1, body 7, SEQ 1, drop 4 + ~G 7,
          base call 2 + ~B 19); each #drop is drop 1 + member 1 + oa 1 + var 1.
"""
from vm_execution_cases import case, event_list
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000


def program(head, *rows):
    return head + '\n'.join(rows) + '\n'


def line_of(source, needle):
    """1-based source line of the unique occurrence of needle (an independent source fact)."""
    index = source.find(needle)
    if index < 0 or source.find(needle, index + 1) >= 0:
        raise AssertionError(f'line marker {needle!r} must occur exactly once')
    return source.count('\n', 0, index) + 1


def own(name, source, result=None, **options):
    return case(name, source, result, stage='ownership', **options)


def block_decimal(block_id, offset=0):
    """Decimal form of a literal object address, bit63 | id<<24 | offset (blocks.h:36-38)."""
    return str((1 << 63) | (block_id << 24) | offset)


def probe(value, active_calls, remaining=None):
    event = {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active_calls}
    if remaining is not None:
        event['remaining_steps'] = remaining
    return event


# Spec section 12 group D classes: F{~F -1}, B{F bf; ~B -2}, G{~G -3}, C:B{G cg; ~C -4}.
CHAIN = (
    'class F { int n; ~F(){ event(-1); } }',
    'class B { F bf; ~B(){ event(-2); } }',
    'class G { int n; ~G(){ event(-3); } }',
    'class C : B { G cg; ~C(){ event(-4); } }',
)
# The same C with its destructor header and body on separate lines (identical
# lowering, so the same costs), for cases that pin ~C's frame line.
C_LINES = 'class C : B {\n  G cg;\n  ~C(){\n    event(-4);\n  }\n}'

# A field destructor that fails after emitting -3 (failing node on its own line).
G8 = 'class G8 {\n  int n;\n  ~G8(){\n    event(-3);\n    int z = 0;\n    int q8 = 1 / z;\n  }\n}'

# Literal field K with a self() accessor (its this is the field block's address).
K_CLASS = 'class K { int n; K(int v){ n = v; } K * self(){ return this; } ~K(){ event(0 - n); } }'

# Verify addition 18: ~A18 is unwrapped (no owned fields, no base), so its body
# runs on its root, whose transfer_parent is its caller. ~B18 is wrapped because
# its base has a cleanup destructor; its body block's transfer_parent is its
# lexical parent, ~B18's root. Each prints its identifying field, then the address.
A18 = ('class A18 {\n  int n;\n  ~A18(){\n    address p = alloc(1);\n    mem_send_up(p);\n'
       '    print(n);\n    print(p);\n  }\n}')
B18 = ('class B18 : A18 {\n  int k;\n  ~B18(){\n    address r = alloc(1);\n    mem_send_up(r);\n'
       '    print(k);\n    print(r);\n  }\n}')

# Verify addition 12: P12 (dropped second) stores a new K12 into the already
# dropped field k, so only the native sweep after the chain finalizes it.
BOX12 = (
    'class Base12 { int tag; virtual int who(){ return 1; } ~Base12(){ event(-100); } }',
    'class K12 {\n  int n;\n  Base12 * up;\n  K12(int v, Base12 * b){ n = v; up = b; }\n  ~K12(){\n'
    '    event(0 - n);\n    event(up.tag * 10 + up.who());\n    if (n == 8) {\n      delete up;\n    }\n'
    '    event(77);\n  }\n}',
    'K12 mkK12(int v, Base12 * b){ K12 t(v, b); return t; }',
    'class P12 { int n; Box12 * up; ~P12(){ event(1); up.k = mkK12(8, up); event(2); } }',
    'class Box12 : Base12 { P12 p; K12 k(3, null); Box12(){ tag = 4; p.up = this; k.up = this; } '
    'override int who(){ return 2; } ~Box12(){ event(9); } }',
)

# Verify addition 12 through check_object: reflect_invoke_function with a class
# pointer result makes the compiler emit check_object after the call
# (compiler/LANGUAGE.md:748-752). ident is 0x0fff0f15: default namespace 0x0fff,
# explicit local id 0x0f15, outside the compiler's sequential 0x0fff0001.. range.
# check_object rejects a destroying heap object (heepalloc.cpp:241-242) and a
# literal that is not live (:233-236).
IDENT_ID = 0x0fff0f15   # 268373781
BOX15 = (
    'class Base15 { int tag; ~Base15(){ event(-100); } }',
    'Base15 * ident(Base15 * x):0f15{ return x; }',
    'class K15 {\n  int n;\n  Base15 * up;\n  K15(int v, Base15 * b){ n = v; up = b; }\n  ~K15(){\n'
    '    event(0 - n);\n    if (n == 8) {\n'
    f'      Base15 * c = reflect_invoke_function<Base15 *>({IDENT_ID}, up);\n'
    '      event(c.tag);\n    }\n    event(77 + n);\n  }\n}',
    'K15 mkK15(int v, Base15 * b){ K15 t(v, b); return t; }',
    'class P15 { int n; Box15 * up; ~P15(){ event(1); up.k = mkK15(8, up); event(2); } }',
    'class Box15 : Base15 { P15 p; K15 k(3, null); Box15(){ tag = 4; p.up = this; k.up = this; } '
    '~Box15(){ event(9); } }',
)


def cases():
    result = []

    # D1, T3: a literal C : B runs ~C body, its field cg, then ~B body and bf.
    # Steps: root 1, block 1 + `C c;` 88 + ~C 41, event 2, return 2 = 135
    # (module docstring; addition 17 inventory: ~C = cleanup 1 + body 7 + SEQ 1
    # + drop 4 + ~G 7 + base call 2 + ~B 19).
    result.append(own('own-dst1-literal-chain-order', program(
        EVENT, *CHAIN,
        'int main(){',
        '  {',
        '    C c;',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-4, -3, -2, -1, 5), remaining=FULL - 135))

    # D1 on a copy: materializing c for f copies the block and its nested field
    # blocks with their destructors (copy_block sets destructor and methods
    # last, blocks.cpp:163-207), so the parameter runs the full chain at f's
    # root cleanup, and c runs its own chain at the block's exit. The copy
    # charges 4 (C has 2 slots, its F and G field blocks 1 each; blocks.cpp:177)
    # and the store into x moves it (blocks.cpp:607-611, no further charge).
    # Steps: root 1, block 1, `C c;` 88, f(c) 50 (call 1, var 1, charge 4,
    # f root 1 + event 2, x's ~C 41), event(8) 2, c's ~C 41, return 2 = 185.
    result.append(own('own-dst1b-by-value-copy-runs-full-chain', program(
        EVENT, *CHAIN,
        'void f(C x){ event(7); }',
        'int main(){',
        '  {',
        '    C c;',
        '    f(c);',
        '    event(8);',
        '  }',
        '  return 0;',
        '}'), 0, events=(7, -4, -3, -2, -1, 8, -4, -3, -2, -1), remaining=FULL - 185))

    # D2: delete through a base pointer runs the full chain bound at creation
    # (LANGUAGE.md inheritance section: the chain does not depend on virtual
    # methods). ND's base has no destructor, so ND's final drops only cg.
    result.append(own('own-dst2-manual-delete-through-base-pointer', program(
        EVENT, *CHAIN,
        'class NB { int n; }',
        'class ND : NB { G cg; ~ND(){ event(-4); } }',
        'int main(){',
        '  B * p = new C();',
        '  delete p;',
        '  event(5);',
        '  NB * q = new ND();',
        '  delete q;',
        '  return 0;',
        '}'), 0, events=(-4, -3, -2, -1, 5, -4, -3)))

    # D3: an automatic C is destroyed at its scope exit with the same chain.
    result.append(own('own-dst3-automatic-chain-at-scope-exit', program(
        EVENT, *CHAIN,
        'int main(){',
        '  {',
        '    C * p();',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(5, -4, -3, -2, -1, 6)))

    # D4, D5: C2 has a literal field and no destructor, so C2::<dtor> is
    # synthesized (drop cg, then B's chain); C3 has no owned field and binds B's
    # cleanup destructor directly (Compiler.java:2468-2470, 2489). The binding
    # is checked by the step count: a C3 wrapper would add at least its
    # cleanup, body and base-call ticks.
    # Steps: root 1; { C2 a; } 128 = block 1 + `C2 a;` 88 (C2::C2 has C::C's
    # shape, 75) + C2::<dtor> 39 (cleanup 1, body block 1 + 2 + 2, SEQ 1, drop 4
    # + ~G 7, base call 2 + ~B 19); event 2; { C3 b; } 87 = block 1 + `C3 b;` 67
    # (vd 1, call 1, <value C3> 65 = root 1, vd+new_block 2, ctor call 3 +
    # C3::C3 54, ob 3, ro 2; C3::C3 = root 1, 2, flag 2, cleanup 1, block 1,
    # vs flag 37, nested block 1 + 2 + 2 + k = 0 5) + ~B 19; event 2; return 2
    # = 222.
    result.append(own('own-dst4-synthesized-and-inherited-destructors', program(
        EVENT, *CHAIN,
        'class C2 : B { G cg; }',
        'class C3 : B { int k; }',
        'int main(){',
        '  { C2 a; }',
        '  event(5);',
        '  { C3 b; }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -1, 5, -2, -1, 6), remaining=FULL - 222))

    # D4/D5 at depth three: cleanupDestructor is inherited through levels
    # without owned fields or destructors (D4b binds C2's synthesized one), and
    # a synthesized level calls its base's synthesized destructor after its own
    # drop (D4c: g2, then C2's cg, then B's chain) (Compiler.java:2489, 2832-2836).
    # The bindings are checked by the step count (a D4b wrapper would add its
    # cleanup, body and base-call ticks). Steps: root 1; { D4 a; } 152 = block 1
    # + `D4 a;` 110 (<value D4> 108: D4::D4 97 = root 1, 2, flag 2, cleanup 1,
    # block 1, vs flag 59 around C3::C3 54, nested block 31 with cg) + ~D4 41
    # (~C's shape, base ~B); event 2; { D4b b; } 150 = block 1 + 110 (D4b::D4b
    # 97: vs flag 80 around C2::C2 75, nested block 10 with z = 0) + C2::<dtor>
    # 39; event 2; { D4c c; } 191 = block 1 + 131 (D4c::D4c 118: vs flag 80,
    # nested block 31 with g2) + D4c::<dtor> 59 (cleanup 1, body 5, SEQ 1, drop 4
    # + ~G2 7, base call 2 + C2::<dtor> 39); event 2; return 2 = 502.
    result.append(own('own-dst4b-deeper-synthesized-and-inherited-chains', program(
        EVENT, *CHAIN,
        'class G2 { int n; ~G2(){ event(-6); } }',
        'class C2 : B { G cg; }',
        'class C3 : B { int k; }',
        'class D4 : C3 { G cg; ~D4(){ event(-5); } }',
        'class D4b : C2 { int z; }',
        'class D4c : C2 { G2 g2; }',
        'int main(){',
        '  { D4 a; }',
        '  event(5);',
        '  { D4b b; }',
        '  event(6);',
        '  { D4c c; }',
        '  event(7);',
        '  return 0;',
        '}'), 0, events=(-5, -3, -2, -1, 5, -3, -2, -1, 6, -6, -3, -2, -1, 7), remaining=FULL - 502))

    # D6: return inside the derived and the base destructor bodies stops only the
    # body; cleanup_expression clears and restores the control flags around
    # final, so drops and the base call still run (library.cpp:852-880).
    result.append(own('own-dst6-return-in-destructor-bodies', program(
        EVENT, *CHAIN[:1], *CHAIN[2:3],
        'class B6 { F bf; int k; ~B6(){ event(-2); if (k == 0) { return; } event(98); } }',
        'class C6 : B6 {',
        '  G cg;',
        '  ~C6(){',
        '    event(-4);',
        '    if (cg.n == 0) { return; }',
        '    event(99);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    C6 c;',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-4, -3, -2, -1, 5)))

    # D7: the derived body fails after -4; final still drops cg and runs ~B
    # with bf. ~C7 keeps the failing node's position (L2/L3); c is in main's
    # root and main returned, so main is at its return (L4).
    source = program(
        EVENT, *CHAIN[:3],
        'class C7 : B {',
        '  G cg;',
        '  ~C7(){',
        '    event(-4);',
        '    int z = 0;',
        '    int q = 1 / z;',
        '    event(99);',
        '  }',
        '}',
        'int main(){',
        '  C7 c;',
        '  return 0;',
        '}')
    result.append(own('own-dst7-derived-body-error-keeps-chain', source, events=(-4, -3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('C7::~C7', 'main'),
                      lines=(line_of(source, 'int q = 1 / z;'), line_of(source, 'return 0;'))))

    # D7 variant: the base body fails after the derived destructor completed;
    # bf is still dropped by ~B7's final. ~B7 keeps its failing node (own line);
    # the base-destructor call node has no position, so ~C7b is reported at its
    # header (verify cleanup correction 2); c lives in a block directly in
    # main's body, so main is at its header (verify cleanup correction 1).
    source = program(
        EVENT, *CHAIN[:1], *CHAIN[2:3],
        'class B7 {',
        '  F bf;',
        '  ~B7(){',
        '    event(-2);',
        '    int z = 0;',
        '    int q7 = 1 / z;',
        '    event(98);',
        '  }',
        '}',
        'class C7b : B7 {',
        '  G cg;',
        '  ~C7b(){',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    C7b c;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst7b-base-body-error-after-derived', source, events=(-4, -3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('B7::~B7', 'C7b::~C7b', 'main'),
                      lines=(line_of(source, 'int q7 = 1 / z;'), line_of(source, '~C7b(){'),
                             line_of(source, 'int main(){'))))

    # Cleanup verify addition 2 / cleanup_expression's had_failure branch
    # (library.cpp:862-875): the body fails first (division, runtime_error);
    # final still runs every step, and both later failures are discarded: the
    # field's overflow (overflow_error) and the base body's alloc(-1)
    # ("Invalid allocation size", invalid_argument, heepalloc.cpp:184). Each
    # failure site has its own message, so the message alone names the winner.
    # ~CX stays at the body's failing node, and no frame of the later failures
    # (~GO, ~BX) survives (pending_error discards their frames, library.cpp:201).
    source = program(
        EVENT, *CHAIN[:1],
        'class BX {',
        '  F bf;',
        '  ~BX(){',
        '    event(-2);',
        '    address bad = alloc(0 - 1);',
        '  }',
        '}',
        'class GO {',
        '  int n;',
        '  ~GO(){',
        '    event(-3);',
        '    int big = 2147483647;',
        '    int over = big + 1;',
        '  }',
        '}',
        'class CX : BX {',
        '  GO cg;',
        '  ~CX(){',
        '    event(-4);',
        '    int z = 0;',
        '    int q = 7 / z;',
        '    event(99);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    CX c;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst7c-body-error-wins-over-final-errors', source, events=(-4, -3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('CX::~CX', 'main'),
                      lines=(line_of(source, 'int q = 7 / z;'), line_of(source, 'int main(){'))))

    # A failure in the base class's own field drop, two levels down: ~FX keeps
    # its failing node; ~BY failed in its final (drop, no position) and ~CY in
    # its final (base call, no position), so both are at their headers
    # (correction 2); c is in main's root after return 0, so main is at the
    # return (L4).
    source = program(
        EVENT,
        'class FX {',
        '  int n;',
        '  ~FX(){',
        '    event(-1);',
        '    int z = 0;',
        '    int qx = 1 / z;',
        '  }',
        '}',
        'class BY {',
        '  FX bf;',
        '  ~BY(){',
        '    event(-2);',
        '  }',
        '}',
        CHAIN[2],
        'class CY : BY {',
        '  G cg;',
        '  ~CY(){',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  CY c;',
        '  return 0;',
        '}')
    result.append(own('own-dst7d-base-field-error-two-header-frames', source, events=(-4, -3, -2, -1),
                      error='Division by zero', category='runtime_error',
                      trace=('FX::~FX', 'BY::~BY', 'CY::~CY', 'main'),
                      lines=(line_of(source, 'int qx = 1 / z;'), line_of(source, '~BY(){'),
                             line_of(source, '~CY(){'), line_of(source, 'return 0;'))))

    # D8 with verify cleanup correction 2: ~G8 fails during ~C8's drop step;
    # the chain still completes, and ~C8 is at its header, not at the drop
    # (which has no position) nor at its last body statement.
    source = program(
        EVENT, *CHAIN[:2], G8,
        'class C8 : B {',
        '  G8 cg;',
        '  ~C8(){',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  C8 c;',
        '  return 0;',
        '}')
    result.append(own('own-dst8-field-destructor-error-trace-at-header', source, events=(-4, -3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('G8::~G8', 'C8::~C8', 'main'),
                      lines=(line_of(source, 'int q8 = 1 / z;'), line_of(source, '~C8(){'),
                             line_of(source, 'return 0;'))))

    # D8 / correction 2, synthesized destructor: C8s::~C8s has the class
    # declaration as its header (Compiler.java:2644, 3294).
    source = program(
        EVENT, *CHAIN[:2], G8,
        'class C8s : B { G8 cg; }',
        'int main(){',
        '  {',
        '    C8s c;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst8b-synthesized-destructor-header-is-class', source, events=(-3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('G8::~G8', 'C8s::~C8s', 'main'),
                      lines=(line_of(source, 'int q8 = 1 / z;'), line_of(source, 'class C8s '),
                             line_of(source, 'int main(){'))))

    # D8, heap automatic object: the same chain and trace through
    # destroy_registered at the block's release. The storage is still freed at
    # that release (free runs after the captured error, heepalloc.cpp:377-381):
    # the heap observed after main, before teardown, is empty. (Teardown alone
    # could not show this: script destroy frees every registered object.)
    source = program(
        EVENT, *CHAIN[:2], G8,
        'class C8 : B {',
        '  G8 cg;',
        '  ~C8(){',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    C8 * p();',
        '  }',
        '  return 0;',
        '}')
    item = own('own-dst8c-heap-field-destructor-error', source, events=(-4, -3, -2, -1),
               error='Division by zero', category='runtime_error', trace=('G8::~G8', 'C8::~C8', 'main'),
               lines=(line_of(source, 'int q8 = 1 / z;'), line_of(source, '~C8(){'), line_of(source, 'int main(){')))
    # semantic_checks compares state.heap (after main, before destroy) when set.
    item.expected_heap = []
    result.append(item)

    # D9, G10: fields are dropped last declared first, so b's overflow is the
    # first error; a's later division error is discarded and the original
    # exception class (overflow_error) survives.
    source = program(
        EVENT,
        'class TD {',
        '  int n;',
        '  ~TD(){',
        '    event(-10);',
        '    int z = 0;',
        '    int qd = 1 / z;',
        '  }',
        '}',
        'class TO {',
        '  int n;',
        '  ~TO(){',
        '    event(-20);',
        '    int big = 2147483647;',
        '    int qo = big + 1;',
        '  }',
        '}',
        'class W9 {',
        '  TD a;',
        '  TO b;',
        '  ~W9(){',
        '    event(-9);',
        '  }',
        '}',
        'int main(){',
        '  W9 w;',
        '  return 0;',
        '}')
    result.append(own('own-dst9-later-declared-field-error-wins', source, events=(-9, -20, -10),
                      error='Integer overflow', category='overflow_error', trace=('TO::~TO', 'W9::~W9', 'main'),
                      lines=(line_of(source, 'int qo = big + 1;'), line_of(source, '~W9(){'),
                             line_of(source, 'return 0;'))))

    # D10, section 10: objects created in a wrapped destructor belong to its
    # body block and die at the block's exit, LIFO (t, then the automatic h),
    # before the field drops and the base chain; a manual object deleted in the
    # body runs its destructor at the delete; a temporary dies at its statement end.
    result.append(own('own-dst10-destructor-creates-and-destroys-objects', program(
        PRELUDE, *CHAIN,
        'class C10 : B {',
        '  G cg;',
        '  ~C10(){',
        '    M t(7);',
        '    M * h(8);',
        '    M * k = new M(9);',
        '    event(-4);',
        '    delete k;',
        '    event(mk(6).n);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    C10 c;',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-4, -9, 6, -6, -8, -7, -3, -2, -1, 5)))

    # D10 variants: an unwrapped destructor's objects live in its root and die
    # at root cleanup LIFO; a destructor that recursively creates objects of its
    # own class in a nested block destroys each at that block's exit; a
    # destructor deletes the manual object its pointer field refers to.
    result.append(own('own-dst10b-unwrapped-recursive-and-owning-destructors', program(
        PRELUDE,
        'class U10 { int n; ~U10(){ M t(7); M * h(8); event(1); } }',
        'class R10 { int n; R10(int v){ n = v; } ~R10(){ event(0 - n); if (n > 1) { R10 r(n - 1); event(n * 10); } } }',
        'class O10 { M * child; O10(int v){ child = new M(v); } ~O10(){ event(2); delete child; event(3); } }',
        'int main(){',
        '  { U10 u; }',
        '  { R10 r(3); }',
        '  { O10 o(4); }',
        '  return 0;',
        '}'), 0, events=(1, -8, -7, -3, 30, -2, 20, -1, 2, -4, 3)))

    # Section 10 "assign a new value into own literal field" / J8: in-place
    # assign_block never runs the old value's destructor (no -1); the temporary
    # (n = 5) dies at its statement end; the field keeps its block, is changed
    # to 6 afterwards, and is dropped once with those contents (-6).
    result.append(own('own-dst10c-destructor-reassigns-own-field-in-place', program(
        PRELUDE,
        'class Z { M m(1); ~Z(){ m = mk(5); m.n = m.n + 1; event(9); } }',
        'int main(){',
        '  {',
        '    Z z;',
        '  }',
        '  return 0;',
        '}'), 0, events=(-5, 9, -6)))

    # Verify addition 25 ("a destructor that reassigns a sibling's in-place
    # field and then drops it"): q is dropped first and assigns into the still
    # live sibling a through the container pointer, in place; the temporary
    # (n = 5) dies at the statement end, a is then changed to 6, and a is later
    # dropped once with n = 6 (no -1 from its old value).
    result.append(own('own-dst10d-field-destructor-reassigns-sibling-in-place', program(
        PRELUDE,
        'class Q { int n; Box10 * up; ~Q(){ up.a = mk(5); up.a.n = up.a.n + 1; event(9); } }',
        'class Box10 { M a(1); Q q; Box10(){ q.up = this; } }',
        'int main(){',
        '  {',
        '    Box10 b;',
        '  }',
        '  return 0;',
        '}'), 0, events=(-5, 9, -6)))

    # D10 with an error: t belongs to ~CW's body block and fails at that
    # block's cleanup (after event(-4)); cleanup_expression captures it as the
    # body failure and final still drops cg and runs ~B with bf. Blocks carry
    # no position and every body statement restored its position on normal
    # exit, so ~CW is at its header, not at its last statement
    # (correction 1/2); main is at its header (block in main's body).
    source = program(
        EVENT, *CHAIN[:3],
        'class BadT {',
        '  int n;',
        '  BadT(int v){ n = v; }',
        '  ~BadT(){',
        '    event(100 + n);',
        '    int z = 0;',
        '    int qt = n / z;',
        '  }',
        '}',
        'class CW : B {',
        '  G cg;',
        '  ~CW(){',
        '    BadT t(1);',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    CW c;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst10e-body-block-local-error-keeps-final', source, events=(-4, 101, -3, -2, -1),
                      error='Division by zero', category='runtime_error', trace=('BadT::~BadT', 'CW::~CW', 'main'),
                      lines=(line_of(source, 'int qt = n / z;'), line_of(source, '~CW(){'),
                             line_of(source, 'int main(){'))))

    # Ownership verify correction 18: type-parameter fields are dropped like
    # literal fields (Compiler.java:2869-2876), last declared first after the
    # body; a class with type arguments gets a synthesized destructor
    # (Compiler.java:2468), whose drop of a scalar T field is a no-op
    # (blocks.cpp:665-673). Release is LIFO: q, p, then h.
    result.append(own('own-dst19-type-parameter-fields-dropped', program(
        EVENT,
        'class GN { int n; ~GN(){ event(-30 - n); } }',
        'class Holder<T> { T first; int k; T second; ~Holder(){ event(1); } }',
        'class Plain<T> { T only; }',
        'int main(){',
        '  {',
        '    Holder<GN> h;',
        '    h.second.n = 2;',
        '    Plain<GN> p;',
        '    p.only.n = 5;',
        '    Plain<int> q;',
        '    event(3);',
        '  }',
        '  return 0;',
        '}'), 0, events=(3, -35, 1, -32, -30)))

    # D11, section 6.5: each constructor and destructor installs its own class's
    # table, so this.who() reports the level being constructed or destroyed:
    # constructors 1, 2, 3; destructors 3, 2, 1 (as 13, 12, 11), for a literal
    # and for a manual object deleted through a base pointer.
    result.append(own('own-dst11-virtual-tables-follow-construction-and-destruction', program(
        EVENT,
        'class VB {',
        '  int n;',
        '  VB(){ event(this.who()); }',
        '  virtual int who(){ return 1; }',
        '  ~VB(){ event(10 + this.who()); }',
        '}',
        'class VC : VB {',
        '  VC(){ event(this.who()); }',
        '  override int who(){ return 2; }',
        '  ~VC(){ event(10 + this.who()); }',
        '}',
        'class VD : VC {',
        '  VD(){ event(this.who()); }',
        '  override int who(){ return 3; }',
        '  ~VD(){ event(10 + this.who()); }',
        '}',
        'int main(){',
        '  { VD v; }',
        '  event(0);',
        '  VB * p = new VD();',
        '  delete p;',
        '  return 0;',
        '}'), 0, events=(1, 2, 3, 13, 12, 11, 0, 1, 2, 3, 13, 12, 11)))

    # D11 / section 6.5 with a base that has no virtual methods: ~NB's
    # #set-methods installs NB's empty table, which clears the binding
    # (heepalloc.cpp:269-270). cg (n = 20) is dropped by ~DV while DV's table is
    # installed (who() = 7); bf (n = 10) is dropped by ~NB after the clear, so
    # its virtual call fails with "Object has no virtual method table"
    # (heepalloc.cpp:296). ~NB and ~DV failed in their finals, so both are at
    # their headers; main is at its header.
    source = program(
        EVENT,
        'class W {',
        '  int n;',
        '  DV * up;',
        '  ~W(){',
        '    event(n);',
        '    event(up.who());',
        '    event(n + 1);',
        '  }',
        '}',
        'class NB {',
        '  W bf;',
        '  ~NB(){',
        '    event(-2);',
        '  }',
        '}',
        'class DV : NB {',
        '  W cg;',
        '  DV(){ bf.n = 10; cg.n = 20; bf.up = this; cg.up = this; }',
        '  virtual int who(){ return 7; }',
        '  ~DV(){',
        '    event(-4);',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    DV d;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst11b-nonvirtual-base-clears-method-table', source, events=(-4, 20, 7, 21, -2, 10),
                      error='Object has no virtual method table', category='runtime_error',
                      trace=('W::~W', 'NB::~NB', 'DV::~DV', 'main'),
                      lines=(line_of(source, 'event(up.who());'), line_of(source, '~NB(){'),
                             line_of(source, '~DV(){'), line_of(source, 'int main(){'))))

    # D12: a saved this of a literal that has expired cannot dispatch:
    # method_literal maps the failed resolve to the virtual-call error
    # (heepalloc.cpp:252-257).
    vclass = 'class VE { int n; virtual int who(){ return 1; } VE * self(){ return this; } }'
    source = program(
        EVENT, vclass,
        'int main(){',
        '  VE * p = null;',
        '  {',
        '    VE v;',
        '    p = v.self();',
        '    event(p.who());',
        '  }',
        '  int r = p.who();',
        '  return r;',
        '}')
    result.append(own('own-dst12-virtual-call-on-expired-literal', source, events=(1,),
                      error='Virtual call requires a live object start', category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'int r = p.who();'),)))

    # D12, heap: after delete the start is no longer an allocation, which
    # check_method_heap rejects with the same message (heepalloc.cpp:262-266).
    source = program(
        EVENT, vclass,
        'int main(){',
        '  VE * q = new VE();',
        '  event(q.who());',
        '  delete q;',
        '  int r = q.who();',
        '  return r;',
        '}')
    result.append(own('own-dst12b-virtual-call-on-deleted-heap-object', source, events=(1,),
                      error='Virtual call requires a live object start', category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'int r = q.who();'),)))

    # D13, section 10: drops run late, k, early. late's destructor still reads
    # k (5); early's runs after k was dropped, so up.k is void and the member
    # access on it fails in block_address (library.cpp:935-940). The error is
    # captured and the base destructor still runs; Box13 is at its header.
    p13 = 'class P13 {\n  int n;\n  Box13 * up;\n  ~P13(){\n    event(n);\n    event(up.k.n);\n    event(n + 1);\n  }\n}'
    box13 = ('class Box13 : Base13 {\n  P13 early;\n  K k(5);\n  P13 late;\n'
             '  Box13(){ early.n = 10; late.n = 20; early.up = this; late.up = this; }\n'
             '  ~Box13(){\n    event(9);\n  }\n}')
    source = program(
        EVENT, K_CLASS,
        'class Base13 { int t; ~Base13(){ event(-100); } }',
        p13, box13,
        'int main(){',
        '  {',
        '    Box13 b;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst13-field-reads-dropped-sibling', source, events=(9, 20, 5, 21, -5, 10, -100),
                      error='Object address requires a class value', category='runtime_error',
                      trace=('P13::~P13', 'Box13::~Box13', 'main'),
                      lines=(line_of(source, 'event(up.k.n);'), line_of(source, '~Box13(){'),
                             line_of(source, 'int main(){'))))

    # D13, heap container: the drops run on the heap slots with the same order
    # and the same void-sibling failure (the spec says heap and literal behave
    # alike, cleanup spec section 10); main is at the delete statement.
    source = program(
        EVENT, K_CLASS,
        'class Base13 { int t; ~Base13(){ event(-100); } }',
        p13, box13,
        'int main(){',
        '  Box13 * b = new Box13();',
        '  delete b;',
        '  return 0;',
        '}')
    result.append(own('own-dst13b-heap-field-reads-dropped-sibling', source, events=(9, 20, 5, 21, -5, 10, -100),
                      error='Object address requires a class value', category='runtime_error',
                      trace=('P13::~P13', 'Box13::~Box13', 'main'),
                      lines=(line_of(source, 'event(up.k.n);'), line_of(source, '~Box13(){'),
                             line_of(source, 'delete b;'))))

    # D14: the base destructor uses the saved address of the derived field k,
    # which Der14's final already finalized (dead), so resolve fails
    # (blocks.cpp:507-513, out_of_range). Ids: d is block 1 (<value Der14>
    # new_block), k is block 2 (K's value factory, moved into the field).
    base14 = ('class Base14 {\n  int t;\n  K * saved;\n  ~Base14(){\n    event(-100);\n'
              '    event(saved.n);\n    event(-101);\n  }\n}')
    der14 = 'class Der14 : Base14 {\n  K k(7);\n  Der14(){ saved = k.self(); }\n  ~Der14(){\n    event(-200);\n  }\n}'
    source = program(
        EVENT, K_CLASS, base14, der14,
        'int main(){',
        '  {',
        '    Der14 d;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst14-base-uses-expired-derived-field-address', source, events=(-200, -7, -100),
                      error='Invalid or expired object address: ' + block_decimal(2), category='out_of_range',
                      trace=('Base14::~Base14', 'Der14::~Der14', 'main'),
                      lines=(line_of(source, 'event(saved.n);'), line_of(source, '~Der14(){'),
                             line_of(source, 'int main(){'))))

    # D14, heap container: the object is on the heap, so k is the first block
    # (id 1); the frame of main is at the delete statement.
    source = program(
        EVENT, K_CLASS, base14, der14,
        'int main(){',
        '  Base14 * p = new Der14();',
        '  delete p;',
        '  return 0;',
        '}')
    result.append(own('own-dst14b-heap-base-uses-expired-derived-field-address', source, events=(-200, -7, -100),
                      error='Invalid or expired object address: ' + block_decimal(1), category='out_of_range',
                      trace=('Base14::~Base14', 'Der14::~Der14', 'main'),
                      lines=(line_of(source, 'event(saved.n);'), line_of(source, '~Der14(){'),
                             line_of(source, 'delete p;'))))

    # Verify addition 12, heap: Box12's chain is ~Box12 (9), drop k (k(3) sees
    # Box12's table: 4*10+2), drop p (~P12 stores K12(8) into the dropped k),
    # ~Base12 (-100). destroy_registered's finalize_storage then runs ~K12(8)
    # natively while the container is still destroying (heepalloc.cpp:377-379):
    # member access works, the last table is Base12's (4*10+1), and delete of
    # the container fails with "Object is being destroyed" (heepalloc.cpp:26).
    # The sweep runs from destroy_registered, so no Box12 frame is in the trace.
    source = program(
        EVENT, *BOX12,
        'int main(){',
        '  Box12 * b = new Box12();',
        '  delete b;',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-dst15-heap-native-field-sweep-sees-destroying-container', source,
                      events=(9, -3, 42, 77, 1, 2, -100, -8, 41),
                      error='Object is being destroyed', category='runtime_error', trace=('K12::~K12', 'main'),
                      lines=(line_of(source, 'delete up;'), line_of(source, 'delete b;'))))

    # Verify addition 12, literal: the same chain; finalize_into's native sweep
    # (blocks.cpp:250-253) runs ~K12(8) while the literal container is
    # destroying, still resolvable with its last (Base12) table; delete of a
    # literal reports the literal message first (heepalloc.cpp:387).
    source = program(
        EVENT, *BOX12,
        'int main(){',
        '  {',
        '    Box12 b;',
        '  }',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-dst15b-literal-native-field-sweep-sees-destroying-container', source,
                      events=(9, -3, 42, 77, 1, 2, -100, -8, 41),
                      error='delete requires a manual object; a literal object ends with its variable',
                      category='runtime_error', trace=('K12::~K12', 'main'),
                      lines=(line_of(source, 'delete up;'), line_of(source, 'int main(){'))))

    # Verify addition 12, check_object(container) from the native sweep (cleanup
    # spec 6.6 table): the chain is ~Box15 (9), drop k (K15(3): -3, 80), drop p
    # (~P15: 1, store K15(8) into the dropped k, 2), ~Base15 (-100). The heap
    # sweep then runs ~K15(8) (-8) while the container is still registered and
    # destroying, so check_object of the reflective result fails with "object
    # is being destroyed" (heepalloc.cpp:241-242) before event(c.tag) and
    # event(85). ident succeeded and builtins add no frame, so the trace is
    # ~K15 at the reflective statement and main at the delete.
    reflect_marker = 'Base15 * c = reflect_invoke_function'
    source = program(
        EVENT, *BOX15,
        'int main(){',
        '  Box15 * b = new Box15();',
        '  delete b;',
        '  return 0;',
        '}')
    result.append(own('own-dst15c-heap-check-object-sees-destroying-container', source,
                      events=(9, -3, 80, 1, 2, -100, -8),
                      error='Object check failed: object is being destroyed', category='runtime_error',
                      trace=('K15::~K15', 'main'),
                      lines=(line_of(source, reflect_marker), line_of(source, 'delete b;'))))

    # Same for a literal container: during finalize_into's sweep the block
    # still resolves but is destroying, not live, so check_object reports
    # "address is not a live class object start" (heepalloc.cpp:233-236; a
    # live literal would pass and a dead one would report "literal object has
    # expired", :231-232). main is at its header (block in main's body).
    source = program(
        EVENT, *BOX15,
        'int main(){',
        '  {',
        '    Box15 b;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst15d-literal-check-object-sees-destroying-container', source,
                      events=(9, -3, 80, 1, 2, -100, -8),
                      error='Object check failed: address is not a live class object start',
                      category='runtime_error', trace=('K15::~K15', 'main'),
                      lines=(line_of(source, reflect_marker), line_of(source, 'int main(){'))))

    # Verify addition 18 with first-fit (heap starts empty; slot 0 reserved).
    # H18's synthesized destructor drops w, y, x, u, each in its own step scope;
    # every destructor prints its identifying field before the address, so the
    # output names the drop order. w (4) / u (1): ~A18 is the field destructor;
    # its caller is the drop step scope, so mem_send_up succeeds and the step
    # frees the allocation before the next step: p = 1 each time. y (k 30, n 3)
    # / x (k 20, n 2): ~B18's body sends r = 1 up to ~B18's root (held through
    # final); ~B18's base call runs ~A18 with the base step scope as caller, so
    # p = 2 is sent up there and freed before H18's next step. Every allocation
    # is gone before main allocates again (1). This case does not tell the base
    # step scope from ~B18's own caller (both live here); own-dst16e/f do.
    result.append(own('own-dst16-base-destructor-caller-is-step-scope', program(
        EVENT, A18, B18,
        'class H18 { A18 u; B18 x; B18 y; A18 w; '
        'H18(){ u.n = 1; x.n = 2; x.k = 20; y.n = 3; y.k = 30; w.n = 4; } }',
        'int main(){',
        '  {',
        '    H18 h;',
        '  }',
        '  print(alloc(1));',
        '  return 0;',
        '}'), 0, stdout='4\n1\n30\n1\n3\n2\n20\n1\n2\n2\n1\n1\n1\n'))

    # Contrast for addition 18 (J5a): the same unwrapped ~A18 reached by a
    # block's release has the closing block as its caller, so set_owner refuses
    # the transfer (heepalloc.cpp:128). mem_send_up is a builtin: no frame;
    # ~A18 is at the mem_send_up statement, main at its header; nothing printed.
    source = program(
        EVENT, A18,
        'int main(){',
        '  {',
        '    A18 a;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst16b-literal-destructor-send-up-into-closing-scope', source,
                      error='Cannot transfer an allocation into a closing scope', category='runtime_error',
                      trace=('A18::~A18', 'main'),
                      lines=(line_of(source, 'mem_send_up(p);'), line_of(source, 'int main(){'))))

    # Same for an automatic heap object released by the closing block
    # (heap release_owned passes the closing scope, heepalloc.cpp:394-399).
    source = program(
        EVENT, A18,
        'int main(){',
        '  {',
        '    A18 * a();',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst16c-automatic-destructor-send-up-into-closing-scope', source,
                      error='Cannot transfer an allocation into a closing scope', category='runtime_error',
                      trace=('A18::~A18', 'main'),
                      lines=(line_of(source, 'mem_send_up(p);'), line_of(source, 'int main(){'))))

    # Literal vs heap: delete runs the destructor with the deleting block as
    # caller, which is live, so the transfer succeeds. m occupies 1, so p = 2
    # is owned by the block; free(m) reopens 1 for the block's alloc (1). The
    # block's exit frees both 1 and 2, so main's alloc(2) gets 1; had p been
    # sent to a longer-lived scope, slot 2 would still be held and first fit
    # (heepalloc.cpp:182-212) would give 3.
    result.append(own('own-dst16d-delete-destructor-sends-up-into-live-scope', program(
        EVENT, A18,
        'int main(){',
        '  {',
        '    A18 * m = new A18();',
        '    m.n = 5;',
        '    delete m;',
        '    print(alloc(1));',
        '  }',
        '  print(alloc(2));',
        '  return 0;',
        '}'), 0, stdout='5\n2\n1\n1\n'))

    # Addition 18 where the derived destructor's own caller is closing: ~B18 of
    # a literal released by its block gets the closing block as caller, but its
    # body block sends r up to ~B18's root (transfer_parent of a block scope is
    # its parent), and its base call runs ~A18 from the final's step scope,
    # which is live, so ~A18's send-up succeeds (p = 2, freed with the step).
    # A base call that reused ~B18's caller would fail with "Cannot transfer an
    # allocation into a closing scope". main's alloc(2) gets 1: both freed.
    result.append(own('own-dst16e-literal-derived-base-send-up-at-closing-scope', program(
        EVENT, A18, B18,
        'int main(){',
        '  {',
        '    B18 b;',
        '    b.k = 6;',
        '    b.n = 7;',
        '  }',
        '  print(alloc(2));',
        '  return 0;',
        '}'), 0, stdout='6\n1\n7\n2\n1\n'))

    # Same for an automatic B18 (object_alloc(2): slots 1-2) released by the
    # closing block (destroy_registered passes the closing scope): r = 3, p = 4;
    # after the release the object range and both allocations are free, so
    # main's alloc(4) gets 1.
    result.append(own('own-dst16f-automatic-derived-base-send-up-at-closing-scope', program(
        EVENT, A18, B18,
        'int main(){',
        '  {',
        '    B18 * b();',
        '    b.k = 6;',
        '    b.n = 7;',
        '  }',
        '  print(alloc(4));',
        '  return 0;',
        '}'), 0, stdout='6\n3\n7\n4\n1\n'))

    # Ownership 6.5 / spec section 3: each destructor adds one active call; a
    # field dropped in a destructor and the base destructor called from final
    # run one level deeper than it, and probe adds its own level. The same for
    # a literal released by a block and a heap object deleted from main.
    # remaining_steps follow the addition 17 inventory (PF/PB/PG/PC have the
    # F/B/G/C shapes, probe costs what event costs): root 1 + block 1 + `PC c;`
    # 88 leave 999910; ~PC cleanup 1 + body 1 + 2 + 2 + probe 2 -> 999902; SEQ
    # 1 + drop 4 + ~PG 7 -> 999890; base call 2 + ~PB cleanup 1 + body 1 + 2 +
    # 2 + probe 2 -> 999880; drop 4 + ~PF 7 -> 999869; probe(0) 2 -> 999867;
    # `PB * p = new PC();` 87 (vd 1, call 1, <new PC> 85) and `delete p` 2
    # (od 1, var 1) -> 999778, then the same chain offsets (-8, -12, -10, -11)
    # -> 999770, 999758, 999748, 999737; probe(0) -> 999735; return 2 -> 999733.
    # The terminal-shape sweep strips remaining_steps everywhere (also inside
    # probe events), so this case is swept.
    result.append(own('own-dst17-chain-active-calls', program(
        EVENT + PROBE,
        'class PF { int n; ~PF(){ probe(1); } }',
        'class PB { PF bf; ~PB(){ probe(2); } }',
        'class PG { int n; ~PG(){ probe(3); } }',
        'class PC : PB { PG cg; ~PC(){ probe(4); } }',
        'int main(){',
        '  { PC c; }',
        '  probe(0);',
        '  PB * p = new PC();',
        '  delete p;',
        '  probe(0);',
        '  return 0;',
        '}'), 0, partial=True, remaining=999733, raw_events=[
            probe(4, 3, 999902), probe(3, 4, 999890), probe(2, 4, 999880), probe(1, 5, 999869),
            probe(0, 2, 999867),
            probe(4, 3, 999770), probe(3, 4, 999758), probe(2, 4, 999748), probe(1, 5, 999737),
            probe(0, 2, 999735)]))

    # Literal vs heap first fit (K1): a heap object's range stays allocated
    # while its destructor runs and is freed after it, so y's destructor gets 5
    # and x's gets y's start 3; literal objects take no heap, so theirs get 1.
    result.append(own('own-dst18-literal-vs-heap-range-during-destructor', program(
        EVENT,
        'class AL { int a; int b; ~AL(){ print(alloc(2)); } }',
        'int main(){',
        '  {',
        '    AL * x();',
        '    AL * y();',
        '  }',
        '  {',
        '    AL u;',
        '    AL v;',
        '  }',
        '  return 0;',
        '}'), 0, stdout='5\n3\n1\n1\n'))

    # Budget stopping point inside the chain (addition 17 inventory, cleanup
    # spec section 4 exhaustion). The burn loop leaves exactly 20 steps when ~C
    # starts: root 1, `int i = 0;` 2, block 1, `C c;` 88, while 9 * 111098 + 4,
    # probe(0) 2 = 999980, so probe sees 20. ~C then spends cleanup 1, body 7
    # (-4 emitted), SEQ 1, drop 4 and ~G 7 (-3 emitted: its event call and
    # constant take the last two steps), and the base call's own tick fails.
    # ~B never runs (no -2); bf is left to the native sweep, whose ~F is entered
    # but fails at its first tick (no -1). The base call has no position, so ~C
    # is at its header; main at its header (block). Not swept: the loop needs
    # ~1M steps, beyond the sweep cap.
    source = program(
        EVENT + PROBE, *CHAIN[:3], C_LINES,
        'int main(){',
        '  int i = 0;',
        '  {',
        '    C c;',
        '    while (i < 111098) { i = i + 1; }',
        '    probe(0);',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst20-budget-stops-at-base-destructor-call', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(0, 2, 20), *event_list((-4, -3))], partial=True, remaining=0,
                      trace=('C::~C', 'main'), lines=(line_of(source, '~C(){'), line_of(source, 'int main(){')),
                      sweep=False))

    # Addition 17: with exactly one step left at a wrapped destructor, the
    # cleanup tick (inside its try, library.cpp:860) succeeds, the body block's
    # tick fails, every final node then fails its own tick, and both fields are
    # left to the native sweep, whose destructors are entered and fail at their
    # first tick: no destructor event at all. Steps before ~C: root 1, i 2,
    # block 1, `C c;` 88, while 9 * 111100 + 4, probe(-i) 3 (call, neg, var) =
    # 999999. ~C is at its header (the body's first tick has no position), main
    # at its header. Not swept: the loop needs ~1M steps.
    source = program(
        EVENT + PROBE, *CHAIN[:3], C_LINES,
        'int main(){',
        '  int i = 0;',
        '  {',
        '    C c;',
        '    while (i < 111100) { i = i + 1; }',
        '    probe(-i);',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-dst20b-one-step-left-at-wrapped-destructor', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(-111100, 2, 1)], partial=True, remaining=0,
                      trace=('C::~C', 'main'), lines=(line_of(source, '~C(){'), line_of(source, 'int main(){')),
                      sweep=False))

    return result
