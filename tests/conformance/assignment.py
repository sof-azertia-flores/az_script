"""Group C: assignment, copy and construction failure (stage "ownership").

Covers cleanup spec section 12 groups C (C1-C6) and E (E1-E10) with the
verifier's corrections 22, 25 and 26 and additions 1, 7, 9 and 10, plus the
ownership spec sections 3.2 (store), 3.3 (assign_block) and 3.4 (declaration and
bind_local) with the ownership verifier's corrections 6, 7, 9 and 17 and
additions 1 and 9. Expectations are derived from the specification and the
source:
- store / assign_block / copy_block / charge: interpreter/blocks.cpp:74-79,
  163-228, 554-612; bind_local blocks.cpp:613-618; variable::clear
  library.cpp:2246-2251 (a replaced owning value is abandoned);
- vd / vs / m: interpreter/library.cpp:2157-2172, 2208 (m evaluates its target
  before the value and returns a deepCopy alias); statement_scope
  library.cpp:826-834; environment::cleanup library.cpp:2289-2310;
- heap register / destroy / free / first fit: interpreter/heepalloc.cpp:182-212,
  302-316, 339-382;
- buffers: blocks.cpp:173-190 (copy charges capacity*width and refuses a buffer
  in an active operation), 395-450 (buffer_operation), library.cpp:1092-1106;
- factory, constructor and destructor lowering:
  compiler/src/main/java/azertia/script/Compiler.java:2652-2679 (factories),
  2789-2867 (#check-this, #set-methods, field initializers, destructor
  #cleanup, base-less constructor rollback, derived constructor with or without
  the #base-constructed flag), 2868-2884 (field drops, cleanup sequence).

Step counts are derived by counting ticking nodes of the compiled lowering
(every executed node ticks once on entry; a block ticks once in execute_body; a
while ticks once on entry and once per body; copy_block charges the slot count
of each copied block, or capacity*width for a buffer, blocks.cpp:177). Costs
seen by the caller:
  M a(v)      30 = vd 1 + call 1 + const 1 + <value M> 27 (M::M body 15)
  mk(v)       35 = call 1 + const 1 + body 33
  ~M          11; ~Bad 17 (fails at its division after all of its ticks)
  <value C>   11 + C::C for a constructor without arguments
  C::C        block 1 + #check-this 2 + #set-methods 2 + 5 per int field
              + (m 1 + member 3 + <factory call>) per owned field, +1 for the
              #cleanup wrapper when owned fields exist
  ~C synthesized: #cleanup 1 + guarded block 5 + 4 per #drop (+1 per extra
              cleanup-sequence node) + the dropped destructors
  `event(a.n)` 5 (call, member, oa, block_address, var); `a = b` 2 (vs, var);
  `a.n = 5` 6; `x.f.n = 5` 9; `addr(x)` call 1 + block_address 1 + var 1 + body 3.
Trace frames of synthesized factories are named "<value C>", "<scoped C>" and
"<new C>" and sit at the class declaration (verifier addition 7); a frame whose
position is its header reports the header's line. The line expected for each
checked frame holds no other position of that frame (its header included), so
a wrongly reported node cannot match by line alone; the prelude's single-line
Bad and M are therefore replaced by the multi-line Fail and Q where their
frames are checked.
Events that two objects could emit carry distinct values. The prelude occupies
source lines 1-6.

Cases the VM refuses at flush (buffers) are kept: they pin the tree reference
and need a transitional registry entry (tests/runtime_differences.json).

Not covered here, with the reason:
- 3.2 step 1 "Null variable", 3.4 vd slot errors: [host/wire].
- 3.3 "Buffer assignment requires owning storage" (verifier correction 7),
  "Object assignment requires the same layout" and "Cannot assign an object
  into itself": compiled assignment is same-class and a class cannot contain
  itself.
- 3.2 step 7 "Object nesting exceeds 64 levels": the compiler rejects literal
  nesting over 64 and heap slots restart the depth (addition 18).
- 3.3 "assign_block keeps the target's method table": same-class assignment
  means the same table, so it is not observable.
- Host-supplied object arguments, cross-script copy charges and uncharged
  host-created blocks (cleanup N1, verifier correction 27): [host].
- E7 heap length and E8 object-record count: not visible to case()
  expectations; E7 checks the first-fit start, E8 the absent destructor and the
  failing delete.
- Construction failure through ::<placement> (buffer resize defaults), buffer
  replacement after the owner ended, and 3.2 step 2 on a pending (active
  operation) buffer: buffers group. The destroying-buffer form is own-c13c.
"""
from vm_execution_cases import case, event_list
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000

# Address helpers. A literal object's address is bit63 | id<<24 | offset
# (interpreter/blocks.h:36-38), so adding 16777216 advances the block id by one.
# addr() borrows a literal through C (*): no copy, no new id (LANGUAGE.md "C (*) p").
ADDR = ('address addr(M (*) p){ return address(p); }\n'
        'int delta(address a, address b){ int k = 0; while(b + 16777216 * k != a){ k = k + 1; } return k; }\n')
# Multi-line twin of the prelude's Bad: the failing division is alone on its
# line, apart from the header and the declaration holding it, so a frame
# reported at the header or at the declaration cannot pass for the division.
FAIL = ('class Fail { int n; Fail(int v){ n = v; }\n'
        '  ~Fail(){ event(100+n); int fz = 0; int fq =\n'
        '    1/fz; } }\n'
        'Fail mkf(int v){ Fail t(v); return t; }\n')
FAIL_MARK = '1/fz; } }'


def block_address(block_id, offset=0):
    """Literal object address encoding, blocks.h:36-38."""
    return f'{(1 << 63) | (block_id << 24) | offset:016x}'


def heap_address(start):
    return f'{start:016x}'


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


def ev(value):
    return {'callback': 1, 'arguments': [typed(0, value)]}


def probe(value, active, remaining=None):
    event = {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active}
    if remaining is not None:
        event['remaining_steps'] = remaining
    return event


def assignment_cases():
    result = []

    # C1 / verifier addition 10. a = b assigns in place through assign_block,
    # which never charges for the target's own layout and runs no destructor for
    # a's old value (no -1). b is changed afterwards, so the exit order b (-3)
    # then a (-2) is visible. Steps: root 1, a 30, b 30, a = b 2 (vs, var; no
    # charge), event 5, b.n = 3 6, return 2, ~M b 11, ~M a 11 = 98.
    result.append(own('own-c1-assign-in-place-no-charge', program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  M b(2);',
        '  a = b;',
        '  event(a.n);',
        '  b.n = 3;',
        '  return 0;',
        '}'), 0, events=(2, -3, -2), remaining=FULL - 98))

    # C1 / ownership 3.3. The target keeps its identity (address) and is an
    # independent value afterwards: changing b does not reach a.
    result.append(own('own-c1b-assign-keeps-address-independent', program(
        PRELUDE + ADDR,
        'int main(){',
        '  M a(1);',
        '  M b(2);',
        '  address before = addr(a);',
        '  a = b;',
        '  if (addr(a) == before) { event(1); }',
        '  b.n = 9;',
        '  event(a.n);',
        '  return 0;',
        '}'), 0, events=(1, 2, -9, -2)))

    # Verifier addition 10 with a 5-slot class (no destructor): assignment is
    # charged nothing, a declaration copy is charged 5 (the slot count).
    # W5 a = 43 (vd 1, call 1, <value W5> 41 = 11 + W5::W5 30). X: root 1, a 43,
    # b 43, a = b 2, return 2 = 91. Y: root 1, a 43, b 43, W5 c = b 7 (vd 1,
    # var 1, charge 5), return 2 = 96.
    w5 = 'class W5 { int a; int b; int c; int d; int e; }\n'
    result.append(own('own-c1c-assign-wide-layout-charges-nothing', program(
        PRELUDE + w5,
        'int main(){',
        '  W5 a;',
        '  W5 b;',
        '  a = b;',
        '  return 0;',
        '}'), 0, remaining=FULL - 91))
    result.append(own('own-c3-declaration-copy-charges-slot-count', program(
        PRELUDE + w5,
        'int main(){',
        '  W5 a;',
        '  W5 b;',
        '  W5 c = b;',
        '  return 0;',
        '}'), 0, remaining=FULL - 96))

    # Ownership 3.3 per-slot rule: an owned nested field of the target is
    # assigned in place recursively (no charge, no destructor: no -1 for x's old
    # field value). H x = 57 (vd 1, call 1, <value H> 55 with H::H 44), ~H 21
    # (#cleanup 1, block 1, #check-this 2, #set-methods 2, #drop 4, ~M 11).
    # X: root 1, x 57, y 57, x.f.n = 1 9, x = y 2, y.f.n = 9 9, return 2, two ~H
    # 42 = 179; exit y (-9) then x (-7, y's value at the assignment).
    # Y (copy): H z = y charges 2 (H) + 1 (its field M) = 3: root 1, x 57, y 57,
    # y.f.n = 8 9, vd 1 + var 1 + charge 3, z.f.n = 9 9, return 2, three ~H 63
    # = 203; exit z (-9), y (-8), x (-7).
    h7 = 'class H { M f(7); int k; }\n'
    result.append(own('own-c1d-nested-assign-in-place-no-charge', program(
        PRELUDE + h7,
        'int main(){',
        '  H x;',
        '  H y;',
        '  x.f.n = 1;',
        '  x = y;',
        '  y.f.n = 9;',
        '  return 0;',
        '}'), 0, events=(-9, -7), remaining=FULL - 179))
    result.append(own('own-c3b-nested-declaration-copy-charges-each-block', program(
        PRELUDE + h7,
        'int main(){',
        '  H x;',
        '  H y;',
        '  y.f.n = 8;',
        '  H z = y;',
        '  z.f.n = 9;',
        '  return 0;',
        '}'), 0, events=(-9, -8, -7), remaining=FULL - 203))

    # C3. M b = a copies (charge 1): two independent objects, two destructors,
    # the copy first (declared later). Steps: root 1, a 30, b 3, b.n = 5 6
    # (m, member, oa, block_address, var, const), two events 10, return 2,
    # two ~M 22 = 74.
    result.append(own('own-c3c-declaration-copy-independent', program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  M b = a;',
        '  b.n = 5;',
        '  event(a.n);',
        '  event(b.n);',
        '  return 0;',
        '}'), 0, events=(1, 5, -5, -1), remaining=FULL - 74))

    # Ownership 3.3 with a nested owned field: values are copied member-wise into
    # the existing nested block, which keeps its id (x.f is still base+1). y is
    # changed afterwards, so the exit order y (-6) then x (-9) is visible.
    result.append(own('own-c3d-nested-assign-keeps-nested-identity', program(
        PRELUDE + ADDR + h7,
        'address haddr(H (*) p){ return address(p); }',
        'int main(){',
        '  H x;',
        '  H y;',
        '  y.f.n = 9;',
        '  y.k = 4;',
        '  address base = haddr(x);',
        '  x = y;',
        '  event(delta(addr(x.f), base));',
        '  event(x.f.n);',
        '  event(x.k);',
        '  y.f.n = 6;',
        '  return 0;',
        '}'), 0, events=(1, 9, 4, -6, -9)))

    # Copy rollback (cleanup verifier addition 25, last bullet), swept: the
    # declaration copy charges X 2, then per nested block A 1, Y 2, B 1, C 1, each
    # before its id. The sweep stops at every charge, so both engines are
    # compared on every mid-copy failure; there the rollback destructors are
    # entered with no budget left, so this case compares them only engine to
    # engine. own-c3f pins the ids of such a failure independently and own-c3g
    # observes the rollback destructors with budget left. At full budget each
    # object is destroyed own fields last first, y (c, b) then a, the copy z
    # (its k values set to 1) before x.
    result.append(own('own-c3e-nested-copy-rollback-sweep', program(
        PRELUDE,
        'class A { int k; ~A(){ event(-10 - k); } }',
        'class B { int k; ~B(){ event(-20 - k); } }',
        'class C { int k; ~C(){ event(-30 - k); } }',
        'class Y { B b; C c; }',
        'class X { A a; Y y; }',
        'int main(){',
        '  X x;',
        '  X z = x;',
        '  z.a.k = 1;',
        '  z.y.b.k = 1;',
        '  z.y.c.k = 1;',
        '  return 0;',
        '}'), 0, events=(-31, -21, -11, -30, -20, -10)))

    # Verifier addition 17 / cleanup addition 9 at a nested charge: the copy of
    # X fails at C's charge after X, A, Y and B were charged and took ids; the
    # failed charge consumes no id. Not swept: the program burns ~1M steps so
    # the failing charge lands at the default budget (the sweep cap is 1024).
    # X x = 119 (vd 1, call 1, <value X> 117 = 11 + X::X 106; X::X = #cleanup 1
    # + block 1 + 2 + 2 + a 26 + y 74; <value A> 21, <value Y> 69 = 11 + Y::Y 58).
    # Before probe(i): root 1, x 119, gbase = xaddr(x) 7, int i 2, while 9N+4
    # with N 111095, one pad 2, probe call 2 -> probe sees 8. X z = x then ticks
    # vd 1 + var 1 and charges X 2, A 1, Y 2, B 1 (leaving 0); C's charge of 1
    # fails at the declaration. Ids: x 1, x.a 2, x.y 3, x.y.b 4, x.y.c 5, then
    # X' 6, A' 7, Y' 8, B' 9 (no id for C'); the host's post-run state probe
    # takes 10 and the pre-destroy hook's t (fresh budget) takes 11: delta 10.
    # No destructor emits during main: the budget is 0 (cleanup spec section 4).
    source = program(
        PRELUDE + ADDR,
        'address gbase = null;',
        'class A { int k; ~A(){ event(-1); } }',
        'class B { int k; ~B(){ event(-2); } }',
        'class C { int k; ~C(){ event(-3); } }',
        'class Y { B b; C c; }',
        'class X { A a; Y y; }',
        'address xaddr(X (*) p){ return address(p); }',
        'void __script_pre_destroy(){ M t(50); event(delta(addr(t), gbase)); }',
        'int main(){',
        '  X x;',
        '  gbase = xaddr(x);',
        '  int i = 0;',
        '  while (i < 111095) { i = i + 1; }',
        '  int j0 = 0;',
        '  probe(i);',
        '  X z = x;',
        '  event(1);',
        '  return 0;',
        '}')
    result.append(own('own-c3f-mid-copy-charge-failure-consumes-completed-ids', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(111095, 2, 8)], partial=True, remaining=0,
                      trace=('main',), lines=(line_of(source, 'X z = x;'),),
                      globals=[typed(7, block_address(1))], teardown_events=(10, -50), sweep=False))

    # Ownership spec A9 / section 3.1: copy rollback runs the destructors of the
    # completed nested copies, last slot first. The only source-reachable copy
    # failure that leaves budget is a buffer in an active operation
    # (blocks.cpp:176): ~E runs inside q.resize(0) and copies gw.x, whose c.q is
    # that buffer. A and B destructors emit their block id offset from w. Ids:
    # l 1, w 2, x 3, x.a 4, x.y 5, x.y.b 6, x.y.c 7, x.y.c.q 8, reserve's
    # candidate 9, mke's E 10 (relocated into view 11); the copy takes X' 12,
    # A' 13, Y' 14, B' 15, C' 16 and fails at q before q's charge and id.
    # Rollback: C' has nothing, Y' finalizes B' (200 + 13), X' finalizes A'
    # (100 + 11). ~E fails before event(9); the error propagates out of the
    # resize statement, then w is destroyed: B (204), A (102), and last l,
    # whose T takes id 17 (gnext). Tree only: the VM refuses buffers at flush.
    source = program(
        PRELUDE + ADDR,
        'address gbase = null;',
        'address gnext = null;',
        'W * gw = null;',
        'class T { int n; T * self(){ return this; } }',
        'class L { int n; ~L(){ T t; gnext = address(t.self()); } }',
        'class A { int k; ~A(){ event(100 + delta(address(this), gbase)); } }',
        'class B { int k; ~B(){ event(200 + delta(address(this), gbase)); } }',
        'class E { int k;',
        '  ~E(){',
        '    X z =',
        '      gw.x;',
        '    event(9); } }',
        'E mke(){ E t; return t; }',
        'class C { int k; buffer<E> q; }',
        'class Y { B b; C c; }',
        'class X { A a; Y y; }',
        'class W { int z; X x; W * self(){ return this; } }',
        'int main(){',
        '  L l;',
        '  W w;',
        '  gw = w.self();',
        '  gbase = address(gw);',
        '  w.x.y.c.q.reserve(1);',
        '  w.x.y.c.q.push(mke());',
        '  w.x.y.c.q',
        '    .resize(0);',
        '  event(5);',
        '  return 0;',
        '}')
    # E::~E is at its declaration (the store fails), main at the resize node.
    result.append(own('own-c3g-copy-rollback-runs-completed-nested-destructors', source,
                      error='Cannot copy a buffer during an active operation', category='runtime_error',
                      events=(213, 111, 204, 102), trace=('E::~E', 'main'),
                      lines=(line_of(source, 'X z ='), line_of(source, '.resize(0);')),
                      globals=[typed(7, block_address(2)), typed(7, block_address(17)), typed(7, block_address(2))],
                      teardown_events=()))

    # C2 / ownership addition 7. Assigning a temporary into an owning local is
    # in place; the temporary dies at the statement end and a's old value never
    # runs a destructor (no -1). Steps: root 1, a 30, a = mk(2) 36 (vs 1 + mk 35),
    # ~M temporary 11, event 5, a.n = 6 6, return 2, ~M a 11 = 102.
    result.append(own('own-c2-assign-temporary-in-place', program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  a = mk(2);',
        '  event(a.n);',
        '  a.n = 6;',
        '  return 0;',
        '}'), 0, events=(-2, 2, -6), remaining=FULL - 102))

    # Verifier correction 25 (T2). A fresh declaration moves the exported result
    # (no statement-end destructor); assigning the same call into an owning r is
    # in place and the exported a dies at the statement end (-80), while r's
    # old value (90) never runs a destructor. Inside f the inner scope dies
    # first (p+30), then the automatic object (p+20), then the parameter (p).
    result.append(own('own-c2b-t2-fresh-declaration-moves-assignment-in-place', program(
        PRELUDE,
        'M f(M p){ M a(p.n * 10); M * h(p.n + 20); { M inner(p.n + 30); return a; } }',
        'int main(){',
        '  M r = f(mk(9));',
        '  event(100);',
        '  r = f(mk(8));',
        '  event(r.n);',
        '  r.n = 7;',
        '  return 0;',
        '}'), 0, events=(-39, -29, -9, 100, -38, -28, -8, -80, 80, -7)))

    # Ownership 8.13. a = id(a): a is copied for the parameter (materialize),
    # id returns its parameter (moved into main's temporaries), the store
    # assigns in place (a keeps its address) and the temporary dies at the
    # statement end with the modified value (-2).
    result.append(own('own-c2c-assign-result-of-copied-argument', program(
        PRELUDE + ADDR,
        'M id(M x){ x.n = x.n + 1; return x; }',
        'int main(){',
        '  M a(1);',
        '  address before = addr(a);',
        '  a = id(a);',
        '  if (addr(a) == before) { event(1); }',
        '  event(a.n);',
        '  a.n = 5;',
        '  return 0;',
        '}'), 0, events=(-2, 1, 2, -5)))

    # C4 / ownership 3.2 step 3. a = a is the same variable: store returns, no
    # copy, no charge, no destructor. Steps: root 1, a 30, a = a 2, event 5,
    # return 2, ~M 11 = 51.
    result.append(own('own-c4-self-assignment-is-noop', program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  a = a;',
        '  event(a.n);',
        '  return 0;',
        '}'), 0, events=(1, -1), remaining=FULL - 51))

    # C4 variants. a = (a = b): the inner vs returns an alias of a's block, so
    # the outer store sees the target's own block (blocks.cpp:566) and returns.
    # o.inner = o.inner: m returns the same slot variable for both sides
    # (blocks.cpp:558). Neither copies nor charges. Steps: root 1, a 30, b 30,
    # a = (a = b) 3, O o 52 (vd 1, call 1, <value O> 50 = 11 + O::O 39),
    # o.inner = o.inner 9 (m 1 + two member accesses 4 + 4), event(a.n) 5,
    # event(o.inner.n) 8, b.n = 3 6, return 2, ~O 21, ~M b 11, ~M a 11 = 189.
    # Exit order: o (its synthesized destructor drops inner), b, a.
    result.append(own('own-c4b-alias-and-field-self-assignment', program(
        PRELUDE,
        'class O { M inner(4); }',
        'int main(){',
        '  M a(1);',
        '  M b(2);',
        '  a = (a = b);',
        '  O o;',
        '  o.inner = o.inner;',
        '  event(a.n);',
        '  event(o.inner.n);',
        '  b.n = 3;',
        '  return 0;',
        '}'), 0, events=(2, 4, -4, -3, -2), remaining=FULL - 189))

    # C5. Assigning a temporary into a literal field is in place for both a
    # literal container and an automatic heap container: each temporary dies at
    # its statement end, both fields keep their addresses (1, 2), the fields
    # hold the new values (3 + 5), and the containers' synthesized destructors
    # later destroy those field objects once (heap object first, it was
    # transferred into main's scope after o). No -1 ever.
    result.append(own('own-c5-field-assign-temporary-in-place', program(
        PRELUDE + ADDR,
        'class O { M inner(1); }',
        'int main(){',
        '  O o;',
        '  O * q();',
        '  address before = addr(o.inner);',
        '  address qbefore = addr(q.inner);',
        '  o.inner = mk(3);',
        '  q.inner = mk(5);',
        '  if (addr(o.inner) == before) { event(1); }',
        '  if (addr(q.inner) == qbefore) { event(2); }',
        '  event(o.inner.n + q.inner.n);',
        '  o.inner.n = 6;',
        '  q.inner.n = 7;',
        '  return 0;',
        '}'), 0, events=(-3, -5, 1, 2, 8, -7, -6)))

    # Ownership addition 7. Reassigning an already-initialized field in the
    # constructor body is in place: the temporary dies at the constructor
    # statement's end, the field's original value never runs a destructor.
    result.append(own('own-c5b-constructor-body-reassigns-field-in-place', program(
        PRELUDE,
        'class H { M f(1); H(){ f = mk(2); event(f.n); } }',
        'int main(){',
        '  H h;',
        '  event(h.f.n);',
        '  h.f.n = 4;',
        '  return 0;',
        '}'), 0, events=(-2, 2, 2, -4)))

    # Ownership addition 3 / 3.2 step 7. Field initializers store into void
    # fields: a factory result is moved (no new id), another field is copied
    # (new id). Ids: h base, a base+1, copy b base+2, mk(3) base+3 (moved into
    # c), d base+4. Exit: d, then h's destructor drops c, b, a (a changed to 5).
    result.append(own('own-c5c-field-initializers-move-and-copy-ids', program(
        PRELUDE + ADDR,
        'class H2 { M a(1); M b = a; M c = mk(3); }',
        'address h2addr(H2 (*) p){ return address(p); }',
        'int main(){',
        '  H2 h;',
        '  address base = h2addr(h);',
        '  M d = mk(4);',
        '  event(delta(addr(h.a), base));',
        '  event(delta(addr(h.b), base));',
        '  event(delta(addr(h.c), base));',
        '  event(delta(addr(d), base));',
        '  event(h.b.n);',
        '  h.a.n = 5;',
        '  return 0;',
        '}'), 0, events=(1, 2, 3, 4, 1, -4, -3, -1, -5)))

    # C6 / verifier correction 26. m evaluates the target slot first; kill then
    # deletes the container (-1 from H's synthesized destructor) and frees it.
    # h was the last allocation, so the slot variable leaves the heap; the
    # result is moved into the orphaned variable and abandoned when the
    # assignment releases it: no destructor (-7) ever, no error.
    result.append(own('own-c6-assign-into-deleted-container-abandoned', program(
        PRELUDE,
        'class H { M f(1); }',
        'M kill(H * x){ delete x; return mk(7); }',
        'int main(){',
        '  H * h = new H();',
        '  h.f = kill(h);',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-1, 5), teardown_events=()))

    # Correction 26, second layout: keep lies after h, so the orphaned variable
    # stays in the heap slot vector outside any allocation until r's allocation
    # reuses that start (first fit, address 1) and installs a fresh variable; the
    # moved M(7) is abandoned there without its destructor.
    result.append(own('own-c6b-assign-into-deleted-container-reused-start', program(
        PRELUDE,
        'class H { M f(1); }',
        'M kill(H * x){ delete x; return mk(7); }',
        'address main(){',
        '  H * h = new H();',
        '  M * keep(5);',
        '  h.f = kill(h);',
        '  M * r(8);',
        '  event(6);',
        '  return address(r);',
        '}'), heap_address(1), tag=7, events=(-1, 6, -8, -5), teardown_events=()))

    # m evaluates its target before the value (library.cpp:2208): the target
    # slot is a's field even though the value expression retargets gp to b.
    # The exported temporary is assigned in place into a.f and dies at the
    # statement end (-7); a.f (changed to 8) and b.f die with their deletes.
    result.append(own('own-c6c-member-target-evaluated-before-value', program(
        PRELUDE,
        'H * gp = null;',
        'class H { M f(1); }',
        'M retarget(H * other){ gp = other; return mk(7); }',
        'int main(){',
        '  H * a = new H();',
        '  H * b = new H();',
        '  gp = a;',
        '  gp.f = retarget(b);',
        '  event(a.f.n);',
        '  event(b.f.n);',
        '  a.f.n = 8;',
        '  delete a;',
        '  delete b;',
        '  return 0;',
        '}'), 0, events=(-7, 7, 1, -8, -1), globals=[typed(7, heap_address(2))]))

    # Correction 26 layout 1 with the assignment's result used (ownership 3.2
    # step 5 / section 4: a value use of an expired block). m returns a deepCopy
    # alias of the orphaned slot variable (library.cpp:2208); when m releases
    # that variable, variable::clear abandons the moved M(7) (library.cpp:2249),
    # so the alias holds a dead block. Declaring from it fails in store's
    # require_usable (blocks.cpp:563) with "Object value has expired" at the
    # declaration (the assignment and kill( sit on the next line, and their
    # nodes completed); nothing else was created or bound, so -1 (the delete)
    # is the only event and nothing is left for teardown.
    kill = 'class H { M f(1); }\nM kill(H * x){ delete x; return mk(7); }\n'
    source = program(
        PRELUDE + kill,
        'int main(){',
        '  H * h = new H();',
        '  M x = (',
        '    h.f = kill(h));',
        '  event(x.n);',
        '  return 0;',
        '}')
    result.append(own('own-c6d-expired-assignment-result-declaration', source, error='Object value has expired',
                      category='runtime_error', events=(-1,), trace=('main',),
                      lines=(line_of(source, 'M x = ('),), teardown_events=()))
    # The same alias read through a member: block_address calls require_usable
    # (blocks.cpp:497-498) inside the .n access, before event is called.
    source = program(
        PRELUDE + kill,
        'int main(){',
        '  H * h = new H();',
        '  event((h.f = kill(h))',
        '    .n);',
        '  return 0;',
        '}')
    result.append(own('own-c6e-expired-assignment-result-member-read', source, error='Object value has expired',
                      category='runtime_error', events=(-1,), trace=('main',),
                      lines=(line_of(source, '.n);'),), teardown_events=()))
    # The same alias as a by-value argument: materialize calls require_usable
    # (blocks.cpp:622) in the take( call node before take is entered, so take
    # adds no frame.
    source = program(
        PRELUDE + kill,
        'void take(M m){ event(m.n); }',
        'int main(){',
        '  H * h = new H();',
        '  take(',
        '    h.f = kill(h));',
        '  return 0;',
        '}')
    result.append(own('own-c6f-expired-assignment-result-argument', source, error='Object value has expired',
                      category='runtime_error', events=(-1,), trace=('main',),
                      lines=(line_of(source, 'take(\n'),), teardown_events=()))

    # Ownership 3.2 / 3.4 id sequence. A declaration copy takes a new id, a
    # declaration from a temporary moves it, an assignment takes none: a base,
    # b base+1 (copy), c base+2 (mk's object moved), b = c in place, d base+3.
    # c is changed afterwards so that each object's exit event is distinct.
    result.append(own('own-c7-store-move-copy-ids', program(
        PRELUDE + ADDR,
        'int main(){',
        '  M a(1);',
        '  address base = addr(a);',
        '  M b = a;',
        '  M c = mk(3);',
        '  b = c;',
        '  M d = mk(4);',
        '  event(delta(addr(b), base));',
        '  event(delta(addr(c), base));',
        '  event(delta(addr(d), base));',
        '  event(b.n);',
        '  c.n = 6;',
        '  return 0;',
        '}'), 0, events=(1, 2, 3, 3, -4, -6, -3, -1)))

    # Ownership 3.1 / 3.2: a declaration copy of a nested object takes ids parent
    # first (z base+2, z.f base+3), before the next object (base+4).
    result.append(own('own-c7b-nested-copy-ids-parent-first', program(
        PRELUDE + ADDR + h7,
        'address haddr(H (*) p){ return address(p); }',
        'int main(){',
        '  H y;',
        '  address base = haddr(y);',
        '  H z = y;',
        '  M t = mk(1);',
        '  event(delta(haddr(z), base));',
        '  event(delta(addr(z.f), base));',
        '  event(delta(addr(t), base));',
        '  z.f.n = 8;',
        '  return 0;',
        '}'), 0, events=(2, 3, 4, -1, -8, -7)))

    # Ownership 3.2 step 7: a field of a temporary is owned by its slot, so a
    # declaration from it copies (new id) and the temporary still dies at the
    # statement end (its destructor drops its own f: -3). Ids: mk(0) base (dies
    # at once: event 0), mkH's h base+1, h.f base+2, c base+3, d base+4.
    result.append(own('own-c7c-declaration-from-temporary-field-copies', program(
        PRELUDE + ADDR + h7,
        'H mkH(int v){ H h; h.f.n = v; return h; }',
        'int main(){',
        '  address base = addr(mk(0));',
        '  M c = mkH(3).f;',
        '  M d = mk(9);',
        '  event(delta(addr(c), base));',
        '  event(delta(addr(d), base));',
        '  event(c.n);',
        '  c.n = 5;',
        '  return 0;',
        '}'), 0, events=(0, -3, 3, 4, 3, -9, -5)))

    # Ownership addition 1: T x = a = b copies. The assignment returns an alias of
    # a's block, which a still owns, so the declaration copies it (new id
    # base+2) and x is independent of a.
    result.append(own('own-c7d-declaration-from-assignment-alias-copies', program(
        PRELUDE + ADDR,
        'int main(){',
        '  M a(1);',
        '  M b(2);',
        '  address base = addr(a);',
        '  M x = (a = b);',
        '  x.n = 7;',
        '  event(delta(addr(x), base));',
        '  event(a.n);',
        '  b.n = 3;',
        '  return 0;',
        '}'), 0, events=(2, 2, -7, -3, -2)))

    # Ownership addition 1: return a = b exports a's own block. vs returns an
    # alias whose block a owns with owner_env in f's root, so export case A
    # moves it (blocks.cpp:644-646): r has a's address, a's value is b's (2), and
    # f's cleanup releases only b (-2); a's moved block is r's (-2 at exit).
    result.append(own('own-c7e-return-assignment-moves-target-block', program(
        PRELUDE + ADDR,
        'address ga = null;',
        'M f(){ M a(1); M b(2); ga = addr(a); return a = b; }',
        'int main(){',
        '  M r = f();',
        '  if (addr(r) == ga) { event(1); }',
        '  event(r.n);',
        '  r.n = 5;',
        '  return 0;',
        '}'), 0, events=(-2, 1, 2, -5), globals=[typed(7, block_address(1))]))

    # Ownership 3.4. A local is bound after its initializer completes. The
    # automatic object created while evaluating b's initializer is transferred
    # into main's root first, so b (changed to 3) dies before it (-2), then a;
    # pick's parameter (the copy of a) was moved out by the return and has no
    # destructor in pick (no extra -1).
    result.append(own('own-c8-initializer-auto-registered-before-local', program(
        PRELUDE,
        'M pick(M x, M * p){ return x; }',
        'int main(){',
        '  M a(1);',
        '  M b = pick(a, mkp(2));',
        '  event(b.n);',
        '  b.n = 3;',
        '  return 0;',
        '}'), 0, events=(1, -3, -2, -1)))

    # Verifier addition 9: a copy charge equal to the remaining budget succeeds
    # and leaves 0; the next tick fails. The charge comes before make_block, so a
    # failed charge consumes no id. Not swept: the program burns ~1M steps so the
    # copy is charged at the default budget (the sweep cap is 1024 steps).
    # Steps before probe(i): root 1, W3 w 33, gbase = waddr(w) 7 (vs, call,
    # block_address, var, body 3), int i 2, while 9N+4, pad 2 per int j, probe
    # call 2. Then W3 c = w ticks vd 1 + var 1 and charges 3.
    # Success: N 111104 and 5 pads -> probe sees 1000000-49-999936-10 = 5; the
    # charge meets exactly 3 and leaves 0; event(1) fails at its call tick.
    # Failure: N 111105 and 1 pad -> probe sees 4; the charge of 3 meets 2 and
    # fails at the declaration; c is not declared.
    # Ids, observed by the pre-destroy hook (fresh budget at destroy): w is id 1;
    # the success copy takes id 2; the host's post-run state probe takes one id
    # (tests/observation_host.cpp probes()); the hook's t then takes 4 (success)
    # or 3 (failed charge: no id), so delta is 3 or 2.
    budget_head = PRELUDE + ADDR + (
        'address gbase = null;\n'
        'class W3 { int a; int b; int c; }\n'
        'address waddr(W3 (*) p){ return address(p); }\n'
        'void __script_pre_destroy(){ M t(50); event(delta(addr(t), gbase)); }\n')

    def budget_program(iterations, pads):
        return program(
            budget_head,
            'int main(){',
            '  W3 w;',
            '  gbase = waddr(w);',
            '  int i = 0;',
            f'  while (i < {iterations}) {{ i = i + 1; }}',
            *[f'  int j{k} = 0;' for k in range(pads)],
            '  probe(i);',
            '  W3 c = w;',
            '  event(1);',
            '  return 0;',
            '}')
    source = budget_program(111104, 5)
    result.append(own('own-c9-copy-charge-equal-to-remaining-succeeds', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(111104, 2, 5)], partial=True, remaining=0,
                      trace=('main',), lines=(line_of(source, 'event(1);'),),
                      globals=[typed(7, block_address(1))], teardown_events=(3, -50), sweep=False))
    source = budget_program(111105, 1)
    result.append(own('own-c9b-copy-charge-over-remaining-consumes-no-id', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(111105, 2, 4)], partial=True, remaining=0,
                      trace=('main',), lines=(line_of(source, 'W3 c = w;'),),
                      globals=[typed(7, block_address(1))], teardown_events=(2, -50), sweep=False))

    # Verifier addition 9: a buffer with capacity 0 is charged 0, so its copy
    # succeeds with the budget already at 0 and the next tick fails. Not swept
    # (burns ~1M steps). Steps before probe(i): root 1, buffer e 2 (vd,
    # buffer_new), int i 2, while 9*111109+4, 3 pads 6, probe 2 -> probe sees 2;
    # vd and var then consume the last two steps and the zero charge passes.
    source = program(
        PRELUDE,
        'int main(){',
        '  buffer<int> e;',
        '  int i = 0;',
        '  while (i < 111109) { i = i + 1; }',
        '  int j0 = 0;',
        '  int j1 = 0;',
        '  int j2 = 0;',
        '  probe(i);',
        '  buffer<int> c = e;',
        '  event(1);',
        '  return 0;',
        '}')
    result.append(own('own-c9c-zero-capacity-buffer-copy-free-at-exhausted-budget', source,
                      error='Script execution step limit exceeded', category='runtime_error',
                      raw_events=[probe(111109, 2, 2)], partial=True, remaining=0,
                      trace=('main',), lines=(line_of(source, 'event(1);'),), sweep=False))

    # Ownership 3.2 step 6, buffer branch (blocks.cpp:567-600) with ownership
    # verifier correction 6. Every EM destructor emits -(n + 100 * its block id
    # offset from anchor), so each event names its object. Ids: anchor 1, a 2,
    # a.reserve 3, mke(1) 4 relocated into view 5 (push moves the materialized
    # temporary without a destructor), mke(2) 6 / view 7, b 8, b.reserve 9,
    # mke(3) 10 / view 11. a = b copies b first (buffer 12, view 13), then
    # finalizes a's old elements last first (view 7: -602, view 5: -401, whose
    # T t takes id 14, after the copy's ids), and installs the copy at a's
    # original cleanup position: at exit b (view 11: -1003) dies before the
    # new a (view 13: -1203); a replacement appended to main's list would die
    # first. Tree only: the VM refuses buffers at flush.
    result.append(own('own-c11-buffer-assignment-replaces-elements', program(
        PRELUDE + ADDR,
        'address gbase = null;',
        'address gseen = null;',
        'class T { int n; T * self(){ return this; } }',
        'class EM { int n; EM(int v){ n = v; }',
        '  ~EM(){ event(0 - n - 100 * delta(address(this), gbase));',
        '    if (n == 1) { T t; gseen = address(t.self()); } } }',
        'EM mke(int v){ EM t(v); return t; }',
        'int main(){',
        '  T anchor;',
        '  gbase = address(anchor.self());',
        '  buffer<EM> a;',
        '  a.reserve(2);',
        '  a.push(mke(1));',
        '  a.push(mke(2));',
        '  buffer<EM> b;',
        '  b.reserve(1);',
        '  b.push(mke(3));',
        '  a = b;',
        '  event(a.length());',
        '  return 0;',
        '}'), 0, events=(-602, -401, 1, -1003, -1203),
        globals=[typed(7, block_address(1)), typed(7, block_address(14))]))

    # Ownership 3.3 per-slot rule "object source, non-owning target slot": copy
    # with charge and a new id. Reached when a destroying container's field was
    # already dropped: while w is destroyed, In's destructor drops f (-1) and
    # calls IB's destructor, which assigns a new In into the destroying w.in. The
    # nested f slot is void, so the temporary's f (id 5) is copied into it as
    # id 6 (gcopy) with a charge of 1; the temporary dies at reassign's
    # statement end (its f -5, its IB -20 - k with k 0); reassign then sets
    # w.in.k = 2 and the copy's n = 6, so the outer IB destructor emits -22 and
    # finalize_into's native sweep destroys the copied field with its
    # destructor (-6) (blocks.cpp:250-253). Ids: w 1, w.in 2, w.in.f 3, mkIn's
    # t 4, t.f 5, the copy 6.
    # Steps: W w 100 (<value W> 98 = 11 + W::W 87; W::W = #cleanup 1 + block 1
    # + 2 + 2 + z 5 + in 76; <value In> 71 = 11 + In::In 60), gw = w.self() 9,
    # event(gw.in.f.n) 10, return 2, root 1 -> 122. ~W 227 = #cleanup 1 + 5 +
    # #drop 4 + In::~In 206 + native ~M 11; In::~In = 13 + ~M 11 + ~IB 182;
    # ~IB(once 0) = 19 + reassign 163; reassign = block 1 + gw.in = mkIn(5) 92
    # (m 1, member 3, call 1, const 1, mkIn body 85, charge 1) + temporary
    # In::~In 39 (13 + ~M 11 + ~IB(once 1) 15) + gw.in.k = 2 8 + gw.in.f.n = 6
    # 11 + gcopy = addr(gw.in.f) 12. Total 349.
    result.append(own('own-c10-assign-into-destroying-container-copies-dropped-field', program(
        PRELUDE + ADDR,
        'int once = 0;',
        'W * gw = null;',
        'address gcopy = null;',
        'class IB { int k; ~IB(){ if (once == 0) { once = 1; reassign(); } event(-20 - k); } }',
        'class In : IB { M f(1); }',
        'class W { int z; In in; W * self(){ return this; } }',
        'In mkIn(int v){ In t; t.f.n = v; return t; }',
        'void reassign(){ gw.in = mkIn(5); gw.in.k = 2; gw.in.f.n = 6; gcopy = addr(gw.in.f); }',
        'int main(){',
        '  W w;',
        '  gw = w.self();',
        '  event(gw.in.f.n);',
        '  return 0;',
        '}'), 0, events=(1, -1, -5, -20, -22, -6), remaining=FULL - 349,
        globals=[typed(0, 1), typed(7, block_address(1)), typed(7, block_address(6))]))

    # Ownership 3.2 step 4 from source (not wire-only): a dropped field is void,
    # so in ~P (run by H's synthesized destructor after it dropped f: -1)
    # m = gw.h.f stores a void source over the owning local m. copy_from
    # replaces the value and variable::clear abandons M(3) without its
    # destructor (blocks.cpp:562; library.cpp:2249): no -3, not at ~P's exit
    # (the owned entry is stale) and not at teardown.
    result.append(own('own-c12-void-source-over-owning-local-abandons', program(
        PRELUDE,
        'W * gw = null;',
        'class P { int k; ~P(){ M m(3); m = gw.h.f; event(77); } }',
        'class H { P p; M f(1); }',
        'class W { int z; H h; W * self(){ return this; } }',
        'int main(){',
        '  W w;',
        '  gw = w.self();',
        '  return 0;',
        '}'), 0, events=(-1, 77), globals=[typed(7, block_address(1))], teardown_events=()))

    # Ownership 3.3 per-slot scalar branch from source (blocks.cpp:218): in
    # ~B0 (run by D's synthesized destructor after it dropped f: -1) other.d =
    # gw.d assigns in place from a block whose f slot is void, so other.d.f's
    # M(5) is abandoned without its destructor: no -5, also not when other is
    # destroyed at ~B0's exit (its f slot is void by then).
    result.append(own('own-c12b-void-field-assigned-in-place-abandons-nested', program(
        PRELUDE,
        'int once = 0;',
        'W * gw = null;',
        'class B0 { int k; ~B0(){ if (once == 0) { once = 1; W other; other.d.f.n = 5; other.d = gw.d; event(55); } } }',
        'class D : B0 { M f(1); }',
        'class W { int z; D d; W * self(){ return this; } }',
        'int main(){',
        '  W w;',
        '  gw = w.self();',
        '  return 0;',
        '}'), 0, events=(-1, 55), globals=[typed(0, 1), typed(7, block_address(1))], teardown_events=()))

    # Ownership 3.4 / cleanup verifier addition 1 reached from source (contrary
    # to ownership verifier correction 9): M m = gw.h.f declares m from a void
    # source, so bind_local binds nothing; m = mk(5) then moves the temporary
    # into the unbound m (blocks.cpp:604-611, owner_env null). ~P's root cleanup
    # releases its owned list first (first: -4) and finalizes m in the leftovers
    # step afterwards (-5), not in LIFO order (library.cpp:2294-2304).
    result.append(own('own-c12c-never-bound-local-finalized-after-bound-locals', program(
        PRELUDE,
        'W * gw = null;',
        'class P { int k; ~P(){ M first(4); M m = gw.h.f; m = mk(5); event(77); } }',
        'class H { P p; M f(1); }',
        'class W { int z; H h; W * self(){ return this; } }',
        'int main(){',
        '  W w;',
        '  gw = w.self();',
        '  return 0;',
        '}'), 0, events=(-1, 77, -4, -5), globals=[typed(7, block_address(1))], teardown_events=()))

    # Ownership 3.3 "no rollback" from source: x = y assigns slot by slot; slot
    # a is copied (2), then slot b's buffer replacement (ownership verifier
    # correction 6) finalizes x's old element, whose destructor fails, and the
    # error leaves assign_block before slot c (still 1). L reads the partial
    # state at main's unwinding. FE destructors emit 100 * (block id offset
    # from x) + n. Ids: x 1, x.b 2, reserve 3, mkfe(1) 4 / view 5, y 6, y.b 7,
    # reserve 8, mkfe(2) 9 / view 10, l 11, replacement 12 / view 13. Events:
    # old view 5 (401, its error is the first), ~L (2, 1), y's view 10 (902),
    # x's replacement view 13 (1202). Tree only: the VM refuses buffers.
    source = program(
        PRELUDE + ADDR,
        'HB * gx = null;',
        'address gbase = null;',
        'class FE { int n; FE(int v){ n = v; }',
        '  ~FE(){ event(100 * delta(address(this), gbase) + n); int fz = 0; int fq =',
        '    1/fz; } }',
        'FE mkfe(int v){ FE t(v); return t; }',
        'class HB { int a; buffer<FE> b; int c; HB * self(){ return this; } }',
        'class L { int n; ~L(){ event(gx.a); event(gx.c); } }',
        'int main(){',
        '  HB x;',
        '  gbase = address(x.self());',
        '  x.a = 1; x.c = 1; x.b.reserve(1); x.b.push(mkfe(1));',
        '  HB y;',
        '  y.a = 2; y.c = 2; y.b.reserve(1); y.b.push(mkfe(2));',
        '  gx = x.self();',
        '  L l;',
        '  x = y;',
        '  event(9);',
        '  return 0;',
        '}')
    result.append(own('own-c13-partial-assignment-kept-after-buffer-field-error', source,
                      error='Division by zero', category='runtime_error', events=(401, 2, 1, 902, 1202),
                      trace=('FE::~FE', 'main'), lines=(line_of(source, '1/fz; } }'), line_of(source, 'x = y;')),
                      globals=[typed(7, block_address(1)), typed(7, block_address(1))], teardown_events=()))

    # Ownership verifier correction 7: in-place assignment of an object with a
    # nested buffer field routes that field to store's buffer replacement, which
    # copies (charge capacity*width = 7, one new buffer id). Steps: HB x 29 (vd 1,
    # call 1, <value HB> 27 = 11 + HB::HB 16: #cleanup 1, block 1, 2, 2, a 5,
    # b = buffer_new 5), x.b.reserve(7) 13 (op 1, member 4, const 1, charge 7),
    # HB y 29, y.b.reserve(7) 13, x = y 9 (vs 1, var 1, charge 7), M t(1) 30,
    # gt = addr(t) 7, return 2, root 1, exit t 11, y 10 and x 10 (~HB: #cleanup
    # 1, 5, #drop 4) = 164. Ids: x 1, x.b 2, its reserve 3, y 4, y.b 5, its
    # reserve 6, the replacement 7, t 8. Tree only: the VM refuses buffers.
    result.append(own('own-c13b-nested-buffer-field-assignment-charges-capacity', program(
        PRELUDE + ADDR,
        'address gt = null;',
        'class HB { int a; buffer<int> b; }',
        'int main(){',
        '  HB x;',
        '  x.b.reserve(7);',
        '  HB y;',
        '  y.b.reserve(7);',
        '  x = y;',
        '  M t(1);',
        '  gt = addr(t);',
        '  return 0;',
        '}'), 0, events=(-1,), remaining=FULL - 164, globals=[typed(7, block_address(8))]))

    # Ownership 3.2 step 2 (blocks.cpp:557): an element destructor that runs
    # while x.b is being finalized (HB's synthesized destructor drops b) assigns
    # into x.b. The target holds a destroying buffer, so require_buffer raises
    # "Buffer is unavailable" before anything is copied. Trace: E::~E at the
    # assignment, HB::~HB at its header (the class declaration; the drop has no
    # position), main at its return (root cleanup after return). Tree only: the
    # VM refuses buffers at flush.
    source = program(
        PRELUDE,
        'HB * gx = null;',
        'class E { int k;',
        '  ~E(){ event(5); buffer<E> t;',
        '    gx.b',
        '      = t; } }',
        'E mke(){ E t; return t; }',
        'class HB { int a; buffer<E> b;',
        '  HB * self(){ return this; } }',
        'int main(){',
        '  HB x;',
        '  gx = x.self();',
        '  x.b.reserve(1);',
        '  x.b.push(mke());',
        '  return 0;',
        '}')
    result.append(own('own-c13c-assign-into-destroying-buffer-unavailable', source,
                      error='Buffer is unavailable', category='runtime_error', events=(5,),
                      trace=('E::~E', 'HB::~HB', 'main'),
                      lines=(line_of(source, '= t; } }'), line_of(source, 'class HB {'), line_of(source, 'return 0;')),
                      globals=[typed(7, block_address(1))], teardown_events=()))
    return result


def construction_cases():
    result = []

    # E1 / verifier addition 7. A base-less constructor with literal fields is
    # wrapped in #cleanup(on_error): the completed fields are dropped last first;
    # ob never ran, so the class's own destructor (99) never runs. Trace: the
    # constructor at its division, the value factory at the class declaration,
    # main at the declaration.
    source = program(
        PRELUDE,
        'class R {',
        '  M a(1);',
        '  M b(2);',
        '  R(){ int z = 0;',
        '    int q =',
        '      1 / z; }',
        '  ~R(){ event(99); }',
        '}',
        'int main(){',
        '  R r;',
        '  return 0;',
        '}')
    result.append(own('own-ce1-ctor-body-failure-drops-fields-no-destructor', source, error='Division by zero',
                      category='runtime_error', events=(-2, -1), trace=('R::R', '<value R>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'class R {'),
                             line_of(source, 'R r;'))))

    # E2. The second field's initializer fails before b's factory runs: only a
    # is dropped. The synthesized constructor's frame sits at the boom( call of
    # field b's initializer (whose own node takes the field position,
    # Compiler.java:2817-2823, on the line before); boom at its division.
    source = program(
        PRELUDE,
        'int boom(){ int z = 0;',
        '  return',
        '    1 / z; }',
        'class R2 {',
        '  M a(1);',
        '  M b(',
        '    boom());',
        '  ~R2(){ event(99); }',
        '}',
        'int main(){',
        '  R2 r;',
        '  return 0;',
        '}')
    result.append(own('own-ce2-field-initializer-failure-drops-earlier-field', source, error='Division by zero',
                      category='runtime_error', events=(-1,), trace=('boom', 'R2::R2', '<value R2>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'boom());'),
                             line_of(source, 'class R2 {'), line_of(source, 'R2 r;'))))

    # Addition 7, nested factories: a field's own construction fails; In has no
    # owned fields (nothing to roll back), Out drops its completed field a.
    source = program(
        PRELUDE,
        'class In { int k;',
        '  In(){ int z = 0;',
        '    int q =',
        '      1 / z; } }',
        'class Out { M a(1);',
        '  In in;',
        '  ~Out(){ event(99); } }',
        'int main(){',
        '  Out o;',
        '  return 0;',
        '}')
    result.append(own('own-ce2b-nested-field-construction-failure-trace', source, error='Division by zero',
                      category='runtime_error', events=(-1,),
                      trace=('In::In', '<value In>', 'Out::Out', '<value Out>', 'main'),
                      lines=(line_of(source, '1 / z; } }'), line_of(source, 'class In {'),
                             line_of(source, 'In in;'), line_of(source, 'class Out {'),
                             line_of(source, 'Out o;'))))

    # E3 (swept, verifier suggestion). Derived constructor body failure after the
    # base completed: own fields last first (g: -3), then the base's cleanup
    # destructor (~B body -2, then its field bf -1). D's own destructor never runs.
    chain = ('class F { int k; ~F(){ event(-1); } }\n'
             'class B { F bf; ~B(){ event(-2); } }\n'
             'class G { int k; ~G(){ event(-3); } }\n')
    source = program(
        PRELUDE + chain,
        'class D : B {',
        '  G g;',
        '  D(){ int z = 0;',
        '    int q =',
        '      1 / z; }',
        '  ~D(){ event(99); }',
        '}',
        'int main(){',
        '  D d;',
        '  return 0;',
        '}')
    result.append(own('own-ce3-derived-body-failure-rolls-back-base', source, error='Division by zero',
                      category='runtime_error', events=(-3, -2, -1), trace=('D::D', '<value D>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'class D : B {'),
                             line_of(source, 'D d;'))))

    # E3 variant: the derived class's second own field fails to initialize; the
    # unconstructed m2 is void (its drop is a no-op), g and the base roll back.
    source = program(
        PRELUDE + chain,
        'int boom(){ int z = 0;',
        '  return',
        '    1 / z; }',
        'class D2 : B {',
        '  G g;',
        '  M m2(',
        '    boom());',
        '  ~D2(){ event(99); }',
        '}',
        'int main(){',
        '  D2 d;',
        '  return 0;',
        '}')
    result.append(own('own-ce3b-derived-field-initializer-failure', source, error='Division by zero',
                      category='runtime_error', events=(-3, -2, -1), trace=('boom', 'D2::D2', '<value D2>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'boom());'),
                             line_of(source, 'class D2 : B {'), line_of(source, 'D2 d;'))))

    # E4. The base constructor fails: it drops its own completed field (-1); the
    # #base-constructed flag is still false, so the derived constructor rolls
    # nothing back (no -2, -3). D::D sits at the base call, which carries the
    # constructor's position (Compiler.java:2850).
    source = program(
        PRELUDE,
        'class F { int k; ~F(){ event(-1); } }',
        'class B {',
        '  F bf;',
        '  B(){ int z = 0;',
        '    int q =',
        '      1 / z; }',
        '  ~B(){ event(-2); }',
        '}',
        'class G { int k; ~G(){ event(-3); } }',
        'class D : B {',
        '  G g;',
        '  D() : B() {',
        '    event(5); }',
        '  ~D(){ event(99); }',
        '}',
        'int main(){',
        '  D d;',
        '  return 0;',
        '}')
    result.append(own('own-ce4-base-ctor-failure-drops-only-base-fields', source, error='Division by zero',
                      category='runtime_error', events=(-1,), trace=('B::B', 'D::D', '<value D>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'D() : B()'),
                             line_of(source, 'class D : B {'), line_of(source, 'D d;'))))

    # E5 / ownership correction 17. The flag is stored before the base-argument
    # statement's temporaries die (Compiler.java:1211-1221), so when ~Fail fails
    # at that statement's end (107) the base is still rolled back (-2, -1).
    # D5::D5 is at the base-call statement, which carries the constructor's
    # position (its header line), not the mkf( call on the next line.
    source = program(
        PRELUDE + FAIL,
        'class F { int k; ~F(){ event(-1); } }',
        'class B5 { F bf; int k; B5(int v){ k = v; } ~B5(){ event(-2); } }',
        'class D5 : B5 {',
        '  int j;',
        '  D5() : B5(',
        '    mkf(7).n) { event(5); }',
        '}',
        'int main(){',
        '  D5 d;',
        '  return 0;',
        '}')
    result.append(own('own-ce5-base-argument-temporary-fails-after-flag', source, error='Division by zero',
                      category='runtime_error', events=(107, -2, -1),
                      trace=('Fail::~Fail', 'D5::D5', '<value D5>', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'D5() : B5('), line_of(source, 'class D5 : B5 {'),
                             line_of(source, 'D5 d;'))))

    # E5b (classes completed per verifier correction 31). B6's parameter is a
    # copy of D6's parameter (B6 changes its copy to 7); ~Fail fails when
    # B6::B6's root releases it (107), after B6's body completed, so the base
    # call fails and the flag stays false: ~B6 never runs (no -2). D6's own
    # parameter dies next (105, error kept). The factory then finalizes the
    # unbound block natively, last slot first, which reaches bf (-1). B6::B6
    # failed in its root cleanup with no return, so it is reported at its
    # header, a line of its own; D6::D6 at its base call (constructor header).
    e5b = ('class F { int k; ~F(){ event(-1); } }\n'
           'class B6 { F bf; int k;\n'
           '  B6(Fail b){\n'
           '    k = b.n; b.n = 7; }\n'
           '  ~B6(){ event(-2); } }\n'
           'class D6 : B6 {\n'
           '  int j;\n'
           '  D6(Fail b) : B6(b) {\n'
           '    event(5); }\n'
           '}\n')
    source = program(
        PRELUDE + FAIL + e5b,
        'int main(){',
        '  D6 d(',
        '    mkf(5));',
        '  return 0;',
        '}')
    result.append(own('own-ce5b-base-call-cleanup-fails-flag-unset-native-field-sweep', source,
                      error='Division by zero', category='runtime_error', events=(107, 105, -1),
                      trace=('Fail::~Fail', 'B6::B6', 'D6::D6', '<value D6>', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'B6(Fail b){'), line_of(source, 'D6(Fail b)'),
                             line_of(source, 'class D6 : B6 {'), line_of(source, 'D6 d('))))

    # E5b on the heap: the automatic factory's root releases the unregistered
    # allocation; destroy_registered finalizes its value slots last first
    # (heepalloc.cpp:352-356), which runs bf's destructor (-1), then frees it.
    source = program(
        PRELUDE + FAIL + e5b,
        'int main(){',
        '  D6 * p(',
        '    mkf(5));',
        '  return 0;',
        '}')
    result.append(own('own-ce5c-heap-derived-flag-unset-native-field-sweep', source,
                      error='Division by zero', category='runtime_error', events=(107, 105, -1),
                      trace=('Fail::~Fail', 'B6::B6', 'D6::D6', '<scoped D6>', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'B6(Fail b){'), line_of(source, 'D6(Fail b)'),
                             line_of(source, 'class D6 : B6 {'), line_of(source, 'D6 * p('))))

    # E6 / cleanup G10. The rollback continues past a field destructor failure
    # (102 then its division error is dropped); the constructor's overflow stays
    # the propagated error with its original type and frame.
    source = program(
        PRELUDE,
        'class R6 {',
        '  M a(1);',
        '  Bad b(2);',
        '  M c(3);',
        '  R6(){ int big = 2147483647;',
        '    int q =',
        '      big + 1; }',
        '}',
        'int main(){',
        '  R6 r;',
        '  return 0;',
        '}')
    result.append(own('own-ce6-rollback-continues-after-field-destructor-error', source, error='Integer overflow',
                      category='overflow_error', events=(-3, 102, -1), trace=('R6::R6', '<value R6>', 'main'),
                      lines=(line_of(source, 'big + 1; }'), line_of(source, 'class R6 {'),
                             line_of(source, 'R6 r;'))))

    # Ownership verifier correction 17 / correction 18 (balanced #cleanup
    # sequence): a derived constructor's rollback after the base completed
    # continues past a failing own-field destructor. Own fields last first: g
    # (101, its division error dropped), m (-3), then the base's cleanup
    # destructor (~B -2, its field bf -1). The body's overflow stays the error.
    source = program(
        PRELUDE,
        'class F { int k; ~F(){ event(-1); } }',
        'class B { F bf; ~B(){ event(-2); } }',
        'class D7 : B {',
        '  M m(3);',
        '  Bad g(1);',
        '  D7(){ int big = 2147483647;',
        '    int q =',
        '      big + 1; }',
        '  ~D7(){ event(99); }',
        '}',
        'int main(){',
        '  D7 d;',
        '  return 0;',
        '}')
    result.append(own('own-ce13-derived-rollback-continues-past-field-destructor-error', source,
                      error='Integer overflow', category='overflow_error', events=(101, -3, -2, -1),
                      trace=('D7::D7', '<value D7>', 'main'),
                      lines=(line_of(source, 'big + 1; }'), line_of(source, 'class D7 : B {'),
                             line_of(source, 'D7 d;'))))

    # The same rollback with a failing base destructor body: m (-3), then ~B8
    # emits -2 and fails, and its #cleanup wrapper still drops bm (-9, set by
    # B8's constructor). The overflow stays the error.
    source = program(
        PRELUDE,
        'class B8 { M bm(4);',
        '  B8(int v){ bm.n = v; }',
        '  ~B8(){ event(-2); int z = 0;',
        '    int q =',
        '      1 / z; } }',
        'class D8 : B8 {',
        '  M m(3);',
        '  D8() : B8(9) { int big = 2147483647;',
        '    int q =',
        '      big + 1; }',
        '}',
        'int main(){',
        '  D8 d;',
        '  return 0;',
        '}')
    result.append(own('own-ce13b-derived-rollback-continues-past-base-destructor-error', source,
                      error='Integer overflow', category='overflow_error', events=(-3, -2, -9),
                      trace=('D8::D8', '<value D8>', 'main'),
                      lines=(line_of(source, 'big + 1; }'), line_of(source, 'class D8 : B8 {'),
                             line_of(source, 'D8 d;'))))

    # E7 / verifier correction 29. An automatic object whose constructor fails:
    # its completed field is dropped (-1), its own destructor never runs, and
    # the unregistered allocation is freed by the factory's root. The manual k
    # occupies heap address 1, so F7 was at 2; ~L (run while main unwinds)
    # allocates 2 slots and first fit reuses start 2. k is never deleted and is
    # discarded at destroy without a destructor.
    source = program(
        PRELUDE,
        'address gfail = null;',
        'address greuse = null;',
        'class F7 { M a(1); int b;',
        '  F7(){ gfail = address(this); int z = 0;',
        '    int q =',
        '      1 / z; }',
        '  ~F7(){ event(99); } }',
        'class L { int n; ~L(){ greuse = alloc(2); } }',
        'int main(){',
        '  L l;',
        '  M * k = new M(4);',
        '  F7 * p();',
        '  return 0;',
        '}')
    result.append(own('own-ce7-automatic-ctor-failure-frees-start', source, error='Division by zero',
                      category='runtime_error', events=(-1,),
                      globals=[typed(7, heap_address(2)), typed(7, heap_address(2))],
                      trace=('F7::F7', '<scoped F7>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'class F7 {'),
                             line_of(source, 'F7 * p();')), teardown_events=()))

    # E8. A manual object whose constructor fails is never registered (ob runs
    # only after the constructor), so its destructor (99) never runs, not on
    # the failure, not at main's unwinding and not at teardown, and a later
    # delete of the saved pointer raises "delete requires a live object"
    # (heepalloc.cpp:388-389): event(2) never runs, and that later error is
    # dropped behind the first one.
    source = program(
        PRELUDE,
        'F8 * gp = null;',
        'class F8 { int a;',
        '  F8(){ gp = this; int z = 0;',
        '    int q =',
        '      1 / z; }',
        '  ~F8(){ event(99); } }',
        'class L8 { int n; ~L8(){ event(1); delete gp; event(2); } }',
        'int main(){',
        '  L8 l;',
        '  F8 * p =',
        '    new F8();',
        '  return 0;',
        '}')
    result.append(own('own-ce8-manual-ctor-failure-registers-nothing', source, error='Division by zero',
                      category='runtime_error', events=(1,), globals=[typed(7, heap_address(1))],
                      trace=('F8::F8', '<new F8>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'class F8 {'),
                             line_of(source, 'new F8();')), teardown_events=()))

    # E9. A failed literal construction still consumes its block id: l is id 1,
    # the failed x id 2 (address(this) inside its constructor), and the object
    # created in ~L9 while main unwinds is id 3.
    t_class = 'class T { int n; T(int v){ n = v; } T * self(){ return this; } }\n'
    l9 = 'class L9 { int n; ~L9(){ T t(5); gnext = address(t.self()); } }\n'
    source = program(
        PRELUDE + 'address gfail = null;\naddress gnext = null;\n' + t_class + l9,
        'class T9 { int n;',
        '  T9(int v){ n = v; gfail = address(this); int z = 0;',
        '    int q =',
        '      1 / z; } }',
        'int main(){',
        '  L9 l;',
        '  T9 x(2);',
        '  return 0;',
        '}')
    result.append(own('own-ce9-failed-literal-ctor-consumes-id', source, error='Division by zero',
                      category='runtime_error', globals=[typed(7, block_address(2)), typed(7, block_address(3))],
                      trace=('T9::T9', '<value T9>', 'main'),
                      lines=(line_of(source, '1 / z; } }'), line_of(source, 'class T9 {'),
                             line_of(source, 'T9 x(2);'))))

    # E9 with a completed field: x is id 2, its field a id 3 (dropped, -1), and
    # the next object is id 4.
    source = program(
        PRELUDE + 'address gfail = null;\naddress gnext = null;\n' + t_class + l9,
        'class T9b { M a(1); int n;',
        '  T9b(){ gfail = address(this); int z = 0;',
        '    int q =',
        '      1 / z; } }',
        'int main(){',
        '  L9 l;',
        '  T9b x;',
        '  return 0;',
        '}')
    result.append(own('own-ce9b-failed-ctor-completed-field-ids', source, error='Division by zero',
                      category='runtime_error', events=(-1,),
                      globals=[typed(7, block_address(2)), typed(7, block_address(4))],
                      trace=('T9b::T9b', '<value T9b>', 'main'),
                      lines=(line_of(source, '1 / z; } }'), line_of(source, 'class T9b {'),
                             line_of(source, 'T9b x;'))))

    # The argument class of E10: its destructor probes active_calls, which
    # tells where the parameter dies: in the constructor's root (main 1,
    # factory 2, constructor 3, ~PA 4, probe 5) or, wrongly, in the factory's
    # root (4). Section 8 factories: the factory forwards a by-value argument
    # with #move, so the constructor's parameter owns it.
    pa = ('class PA { int n; PA(int v){ n = v; } ~PA(){ probe(0-n); } }\n'
          'PA mkpa(int v){ PA t(v); return t; }\n')

    # E10. The by-value constructor parameter (moved in from the factory) dies at
    # the constructor's root cleanup (active_calls 5), after the #cleanup
    # rollback dropped f (-1).
    source = program(
        PRELUDE + pa,
        'class R8 {',
        '  M f(1);',
        '  R8(PA m){ int z = 0;',
        '    int q =',
        '      1 / z; }',
        '}',
        'int main(){',
        '  R8 r(',
        '    mkpa(4));',
        '  return 0;',
        '}')
    result.append(own('own-ce10-ctor-param-destroyed-after-field-rollback', source, error='Division by zero',
                      category='runtime_error', raw_events=[ev(-1), probe(-4, 5)], partial=True,
                      trace=('R8::R8', '<value R8>', 'main'),
                      lines=(line_of(source, '1 / z; }'), line_of(source, 'class R8 {'),
                             line_of(source, 'R8 r('))))

    # Section 8 factories on success: main's copy of a (materialize) is moved on
    # with #move and, changed to 6 by the constructor, dies when the
    # constructor exits (active_calls 5), before ob binds the destructor and
    # before the declaration completes (before event 2). At main's exit r
    # (99), then a (probe -5 at active_calls 3: main 1, ~PA 2, probe 3).
    result.append(own('own-ce10b-ctor-param-dies-at-ctor-exit-on-success', program(
        PRELUDE + pa,
        'class R9 { int k; R9(PA m){ k = m.n; m.n = 6; event(1); } ~R9(){ event(99); } }',
        'int main(){',
        '  PA a(5);',
        '  R9 r(a);',
        '  event(2);',
        '  return 0;',
        '}'), 0, raw_events=[ev(1), probe(-6, 5), ev(2), ev(99), probe(-5, 3)], partial=True))

    # Construction failure by call depth (ownership 6.5, addition 4 of the
    # ownership verifier: a guard failure adds the callee frame at its header).
    # --call-depth 6: <value R> runs at 2, R::R at 3, field factories at 4 and
    # Q::Q at 5; nest(10), nest(9), nest(8) run at 4..6 and the call of nest(7)
    # fails at its guard, so that frame is at nest's header line while the
    # three callers are at the nest(j) call node and R::R at its nest(10) call
    # node, each alone on its line. The rollback drops run ~Q at 4 and event at
    # 5, within the limit, so both fields emit.
    nest = ('int nest(int k){\n'
            '  if (k == 0) { return 0; }\n'
            '  int j = k - 1;\n'
            '  int r =\n'
            '    nest(j);\n'
            '  return r + 1; }\n')
    q_class = ('class Q { int n;\n'
               '  Q(int v){\n'
               '    n = v; }\n'
               '  ~Q(){ event(0-n); } }\n')
    source = program(
        PRELUDE + nest + q_class,
        'class R {',
        '  Q a(1);',
        '  Q b(2);',
        '  R(){',
        '    int q =',
        '      nest(10); }',
        '}',
        'int main(){',
        '  R r;',
        '  return 0;',
        '}')
    nest_header = line_of(source, 'int nest(int k){')
    nest_call = line_of(source, 'nest(j);')
    result.append(own('own-ce12-ctor-body-depth-failure-rolls-back-fields', source,
                      error='Script call depth limit exceeded', category='runtime_error', events=(-2, -1),
                      options=['--call-depth', '6'],
                      trace=('nest', 'nest', 'nest', 'nest', 'R::R', '<value R>', 'main'),
                      lines=(nest_header, nest_call, nest_call, nest_call, line_of(source, 'nest(10); }'),
                             line_of(source, 'class R {'), line_of(source, 'R r;'))))
    # --call-depth 4: the first field's Q::Q call fails at its guard, so Q::Q is
    # at its header (the constructor line, not the class line or the body
    # line), nothing was constructed and nothing is rolled back.
    result.append(own('own-ce12b-field-ctor-depth-failure-before-frame', source,
                      error='Script call depth limit exceeded', category='runtime_error', events=(),
                      options=['--call-depth', '4'],
                      trace=('Q::Q', '<value Q>', 'R::R', '<value R>', 'main'),
                      lines=(line_of(source, 'Q(int v){'), line_of(source, 'class Q {'), line_of(source, 'Q a(1);'),
                             line_of(source, 'class R {'), line_of(source, 'R r;'))))

    # Verifier correction 22, unflagged shape: no own owned fields and a base
    # without cleanup destructor give [#check-this, base call, phase] with phase
    # a nested block, so getDepth is 3 in the body (the flagged shape gives 4,
    # own-ce11b). The failing base-argument temporary's own error (101)
    # propagates; this shape has nothing it could roll back, and ok (no
    # destructor anywhere) emits nothing at exit. ND::ND is at the base call
    # (constructor header line), not at the mkf( call on the next line.
    source = program(
        PRELUDE + FAIL,
        'class NB { int k; NB(int v){ k = v; } }',
        'class ND : NB { int j;',
        '  ND(int v) : NB(',
        '    v == 0 ? mkf(1).n : v) { event(getDepth()); } }',
        'int main(){',
        '  ND ok(5);',
        '  ND bad(0);',
        '  return 0;',
        '}')
    result.append(own('own-ce11-unflagged-derived-shape-base-argument-failure', source, error='Division by zero',
                      category='runtime_error', events=(3, 101), trace=('Fail::~Fail', 'ND::ND', '<value ND>', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'ND(int v)'), line_of(source, 'class ND : NB {'),
                             line_of(source, 'ND bad(0);'))))

    # Correction 22, flagged shape: the base has a destructor, so the protected
    # body and the phase are nested blocks (getDepth 4). The failing argument
    # temporary (102) leaves bad's base rolled back (FB(2): -2), and ok is
    # destroyed through the base's cleanup destructor at main's exit (FB(5): -5).
    source = program(
        PRELUDE + FAIL,
        'class FB { int k; FB(int v){ k = v; } ~FB(){ event(0 - k); } }',
        'class FD : FB { int j;',
        '  FD(int v) : FB(',
        '    v == 0 ? mkf(2).n : v) { event(getDepth()); } }',
        'int main(){',
        '  FD ok(5);',
        '  FD bad(0);',
        '  return 0;',
        '}')
    result.append(own('own-ce11b-flagged-derived-shape-depth-and-rollback', source, error='Division by zero',
                      category='runtime_error', events=(4, 102, -2, -5),
                      trace=('Fail::~Fail', 'FD::FD', '<value FD>', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'FD(int v)'), line_of(source, 'class FD : FB {'),
                             line_of(source, 'FD bad(0);'))))
    return result


def cases():
    return assignment_cases() + construction_cases()
