"""Group H: heap objects, delete and raw memory ownership (stage "ownership").

Covers cleanup spec section 12 groups F (F1-F8 with verifier correction 28),
J (J1-J8: reentrancy and ownership inside destructors, mem_send_up J5 a/b/c,
the mem_free permission walk J6), K (K1-K3 heap layout and addresses, verifier
correction 29), the ownership spec sections 3.13-3.14 (alloc errors, builtin
argument types, mem_free results, make_free in the current scope only,
mem_send_up check order from verifier correction 14, return_object promotion
to the call-site scope, delete check order) and the ownership spec section 8
scenarios that involve heap allocations.

Every case name starts with "own-heap-", so `--only own-heap-` selects exactly
this group (errors.py uses "own-h<digit>" for its budget cases).

mem_send_up from a delete-initiated destructor into the live deleting scope
(the od row of ownership verify addition 14) is covered by destruction.py
own-dst16d-delete-destructor-sends-up-into-live-scope and is not repeated here.

Expectations are derived from the tree-interpreter specification and the
interpreter/compiler source, not from observed output:
- First fit from slot 1 (slot 0 reserved); every observation is a fresh
  process, so the first allocation is 1 (heepalloc.cpp:182-212). free erases the
  range, owner, record, object tag and method table (heepalloc.cpp:307-316).
  Heap addresses are reused without a generation (LANGUAGE.md "对象存储与已知限制").
- delete check order: null no-op, block address, not registered, destroying,
  automatic, then destroy_registered (heepalloc.cpp:384-393).
- Member access on a pointer: block -> blocks::member_address; a heap start
  that is not object storage -> "Object member access requires object storage";
  anything else -> "Invalid or freed object address: N" (heepalloc.cpp:328-338).
- alloc/mem_free/make_free/mem_send_up/mem_get builtins (heepalloc.cpp:451-523):
  the argument type is checked first ("alloc requires one int argument",
  "Memory function requires one address argument", both invalid_argument,
  heepalloc.cpp:453-460); the compiler accepts a `var` argument, so a var
  holding the wrong runtime type reaches these checks. The allocation is owned
  by the call-site env; raw functions then reject object storage (block-tagged
  addresses always, heap addresses only inside a currently allocated range
  tagged by object_alloc, verifier correction 12; object_alloc tags the range
  before the constructor runs, heepalloc.cpp:213-224); mem_free walks
  transfer_parent (caller, else parent) from the current env; make_free needs
  the current env's list; mem_send_up: argument type, object storage, parent,
  current-list membership, then set_owner (closing check), popping the push on
  failure (verify correction 14).
- return_object (heepalloc.cpp:400-425): raw/registered/manual/borrowed rules;
  the owner becomes the root's caller, i.e. the call-site scope.
- A destructor's root caller is the env passed by the initiator: the closing
  scope at scope exit, the statement env for statement temporaries, the
  deleting env for od, baseEnv at destroy (blocks.cpp:229-238,
  heepalloc.cpp:364-373; ownership verify addition 14).
- Rule E (ownership spec section 2): a builtin call receives the innermost env
  running it: an if/while condition runs in the env running the if/while; a
  non-block if/while body runs in its own scoped_execute scope
  (library.cpp:843-849), so its allocations die with that body.
- Host reentry (callback 0x6dff0004) goes through script::invoke with baseEnv as
  the caller and no guard of its own (library.cpp:1768-1773), so the reentered
  root's transfer_parent is baseEnv and it runs two levels above its caller.
  Declared `extern address reenter`, the typed signature accepts an address
  result (library.cpp:805-808), so a reentered root can return an object.
- Builtins (0x0abd....) add no trace frame; host functions add a native frame
  with an empty name and no position (library.cpp:816-818). Factory frames are
  "<new C>", "<scoped C>", "<value C>" at the class declaration; a frame whose
  failure happened during an inner block's cleanup reports its header; root
  cleanup after a return reports the return (cleanup verify corrections 1-2).
  Classes and functions whose frames appear in a trace are written over several
  lines, so the header line (the `C(`, `~C(`, function or `class C` line, from
  the -g debug records) differs from the failing node's line and the line
  checks tell them apart.
Step counts (remaining) come from the compiled lowering: every executed node
ticks once (constants and variables included, the member-access opcode once),
a block ticks once, a while once on entry and once per body, a select once plus
its condition and chosen arm. Prelude costs: <new M>/<scoped M> factory body 26,
so `M * p = new M(v);` is 29; ~M 11; `delete p` 2 + destructor;
slot(a) costs call 1 + argument + 13 + 11 * result.
Event values are tagged (10 + ..., 20 + ..., an object's own number) wherever
two emitters could otherwise produce the same value.
"""
from vm_execution_cases import case, event_list
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000
REENTER = 'extern int reenter(int,int):6dff0004;\n'
# Same callback with an address result, so a reentered root can return a pointer.
REENTER_ADDRESS = 'extern address reenter(int,int):6dff0004;\n'
# Script function ids chosen outside the compiler's sequential range (0x0fff0000+n).
ID_F01 = 0x0fff0f01   # 268373761
ID_F02 = 0x0fff0f02   # 268373762
# slot(a) returns the heap slot index of a raw address (address arithmetic only;
# address has no conversion to int). Cost: 13 + 11 * result inside the callee.
SLOT = 'int slot(address a){ address z = null; int k = 0; while(z + k != a){ k = k + 1; } return k; }\n'
K_CLASS = 'class K { int n; K(int v){ n = v; } K * self(){ return this; } ~K(){ event(0 - n); } }\n'
STORAGE = '{} cannot access object storage; use typed member access'
ADDRESS_ARGUMENT = 'Memory function requires one address argument'


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


def heap_address(start):
    return f'{start:016x}'


def block_decimal(block_id, offset=0):
    """Decimal form of a literal object address, bit63 | id<<24 | offset (blocks.h:36-38)."""
    return str((1 << 63) | (block_id << 24) | offset)


def ev(value):
    return event_list([value])[0]


def probe(value, active_calls):
    return {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active_calls}


def reentry(function_id, value):
    return {'callback': 4, 'arguments': [typed(0, function_id), typed(0, value)]}


def returned(value):
    return {'reentry_return': typed(0, value)}


def returned_pointer(start):
    return {'reentry_return': typed(7, heap_address(start))}


def raw_range(start, *slots):
    """One teardown heap entry: a raw allocation with the given slot values."""
    return {'address': heap_address(start), 'length': len(slots), 'slots': list(slots)}


VOID = typed(5, None)


def delete_cases():
    result = []

    # F1. delete runs ~M and frees start 1 (no reuse), so a member access
    # through the alias finds no allocation: out_of_range with the pointer value.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  M * alias = p;',
                     '  delete p;',
                     '  event(5);',
                     '  return alias.n;',
                     '}')
    result.append(own('own-heap-f1-delete-manual-then-freed-member-access', source,
                      error='Invalid or freed object address: 1', category='out_of_range', events=(-1, 5),
                      trace=('main',), lines=(line_of(source, 'return alias.n;'),)))

    # F1/K3 + correction 28 (F4 with reuse). q = new M(7) reuses start 1, so the
    # old alias reads the new object and deleting the alias deletes q without
    # error; nothing is left for destroy (no teardown event, empty heap). Steps:
    # root 1, p 29, alias 2, delete p 13, q 29, event 4, delete alias 13,
    # event 2, return 2 = 95.
    result.append(own('own-heap-f1b-reused-start-alias-reads-and-deletes-new-object', program(
        PRELUDE,
        'int main(){',
        '  M * p = new M(1);',
        '  M * alias = p;',
        '  delete p;',
        '  M * q = new M(7);',
        '  event(alias.n);',
        '  delete alias;',
        '  event(8);',
        '  return 0;',
        '}'), 0, events=(-1, 7, -7, 8), remaining=FULL - 95, teardown_events=()))

    # Correction 28: start 1 reused by a raw alloc, so typed member access
    # through the old alias is refused as non-object storage (runtime_error).
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  M * alias = p;',
                     '  delete p;',
                     '  address r = alloc(1);',
                     '  mem_get(r) = 9;',
                     '  return alias.n;',
                     '}')
    result.append(own('own-heap-f1c-reused-by-raw-alloc-member-access', source,
                      error='Object member access requires object storage', category='runtime_error',
                      events=(-1,), trace=('main',), lines=(line_of(source, 'return alias.n;'),)))

    # Correction 28: p2's old start 2 now lies inside the raw range 1..2.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  M * p2 = new M(2);',
                     '  delete p;',
                     '  delete p2;',
                     '  address r = alloc(2);',
                     '  return p2.n;',
                     '}')
    result.append(own('own-heap-f1d-old-start-inside-new-allocation', source,
                      error='Invalid or freed object address: 2', category='out_of_range', events=(-1, -2),
                      trace=('main',), lines=(line_of(source, 'return p2.n;'),)))

    # Ownership spec section 4 / LANGUAGE.md: the base is validated before the
    # field offset, so a null pointer fails even for a non-zero field.
    source = program(PRELUDE,
                     'class P2 { int a; int b; }',
                     'int main(){',
                     '  P2 * p;',
                     '  return p.b;',
                     '}')
    result.append(own('own-heap-f1e-null-pointer-member-access', source,
                      error='Invalid or freed object address: 0', category='out_of_range',
                      trace=('main',), lines=(line_of(source, 'return p.b;'),)))

    # Use after delete inside a method: delete this frees start 1 at once, so the
    # method's next member access finds no allocation. Sd::die is reported at
    # that access (its own line, not the `void die(){` header), main at the call.
    source = program(PRELUDE,
                     'class Sd {',
                     '  int n;',
                     '  Sd(int v){ n = v; }',
                     '  void die(){',
                     '    delete this;',
                     '    event(n);',
                     '  }',
                     '  ~Sd(){ event(0 - n); }',
                     '}',
                     'int main(){',
                     '  Sd * p = new Sd(4);',
                     '  p.die();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f1f-method-deletes-this-then-reads-field', source,
                      error='Invalid or freed object address: 1', category='out_of_range', events=(-4,),
                      trace=('Sd::die', 'main'), lines=(line_of(source, 'event(n);'), line_of(source, 'p.die();'))))

    # F2, ownership scenario 31: deleting an automatic object fails, and the
    # object is still destroyed by main's root cleanup before the error surfaces.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p(1);',
                     '  event(5);',
                     '  delete p;',
                     '  event(6);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f2-delete-automatic-destroyed-at-scope-exit', source,
                      error='Cannot delete an automatic object', category='runtime_error', events=(5, -1),
                      trace=('main',), lines=(line_of(source, 'delete p;'),)))

    # F2 through a callee: a borrowed automatic pointer is still automatic; the
    # callee frame is at its delete (not its header line), main at the call.
    source = program(PRELUDE,
                     'void kill(M * x){',
                     '  delete x;',
                     '}',
                     'int main(){',
                     '  M * p(1);',
                     '  kill(p);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f2b-delete-borrowed-automatic-in-callee', source,
                      error='Cannot delete an automatic object', category='runtime_error', events=(-1,),
                      trace=('kill', 'main'), lines=(line_of(source, 'delete x;'), line_of(source, 'kill(p);'))))

    # F3: delete of null (variable, literal, explicit) is a no-op. Steps: root 1,
    # p 2 (vd + null constant), three deletes 2 each, q 2, return 2 = 13.
    result.append(own('own-heap-f3-delete-null-noop', program(
        PRELUDE,
        'int main(){',
        '  M * p;',
        '  delete p;',
        '  delete null;',
        '  M * q = null;',
        '  delete q;',
        '  return 3;',
        '}'), 3, remaining=FULL - 13))

    # F4: second delete of the same start with no reuse.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  M * alias = p;',
                     '  delete p;',
                     '  event(5);',
                     '  delete alias;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f4-double-delete', source,
                      error='delete requires a live object', category='runtime_error', events=(-1, 5),
                      trace=('main',), lines=(line_of(source, 'delete alias;'),)))

    # Correction 28 / delete check order: the start is now a raw allocation,
    # which is not a registered object.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  delete p;',
                     '  address r = alloc(1);',
                     '  delete p; // again',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f4b-delete-start-reused-by-raw-alloc', source,
                      error='delete requires a live object', category='runtime_error', events=(-1,),
                      trace=('main',), lines=(line_of(source, '// again'),)))

    # Correction 28 / delete check order: the start is now a registered
    # automatic object, so the stale delete reports it as automatic; q is still
    # destroyed at main's root cleanup.
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  delete p;',
                     '  M * q(2);',
                     '  event(5);',
                     '  delete p; // again',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f4c-delete-start-reused-by-automatic', source,
                      error='Cannot delete an automatic object', category='runtime_error', events=(-1, 5, -2),
                      trace=('main',), lines=(line_of(source, '// again'),)))

    # F5: self-delete inside the destructor of a manual object. The destroying
    # check fails; destroy_registered still sweeps and frees the range once, so
    # the pre-destroy hook's alloc(1) gets start 1 again (101). ~S is reported at
    # its `delete this;` line, not at its `~S(){` header.
    s_class = program('',
                      'class S {',
                      '  int n;',
                      '  S(int v){ n = v; }',
                      '  ~S(){',
                      '    event(n);',
                      '    delete this;',
                      '    event(99);',
                      '  }',
                      '}')
    source = program(PRELUDE + SLOT + s_class,
                     'void __script_pre_destroy(){ event(100 + slot(alloc(1))); }',
                     'int main(){',
                     '  S * p = new S(7);',
                     '  delete p;',
                     '  event(5);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5-self-delete-manual-in-destructor', source,
                      error='Object is being destroyed', category='runtime_error', events=(7,),
                      trace=('S::~S', 'main'), lines=(line_of(source, 'delete this;'), line_of(source, 'delete p;')),
                      teardown_events=(101,)))

    # F5 / delete check order: for an automatic object the destroying check
    # comes before the automatic check. Root cleanup after return: main @ return.
    source = program(PRELUDE + s_class,
                     'int main(){',
                     '  S * p(7);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5b-self-delete-automatic-reports-destroying-first', source,
                      error='Object is being destroyed', category='runtime_error', events=(7,),
                      trace=('S::~S', 'main'), lines=(line_of(source, 'delete this;'), line_of(source, 'return 0;'))))

    # F5 / spec section 10: a literal's this is a block address, rejected first.
    # Inner-block cleanup failure reports main at its header (verify correction 1).
    source = program(PRELUDE + s_class,
                     'int main(){',
                     '  { S a(7); }',
                     '  event(5);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5c-self-delete-literal-in-destructor', source,
                      error='delete requires a manual object; a literal object ends with its variable',
                      category='runtime_error', events=(7,), trace=('S::~S', 'main'),
                      lines=(line_of(source, 'delete this;'), line_of(source, 'int main(){'))))

    # F5 through a cycle: ~Cy(a) deletes b, whose destructor deletes a while a is
    # destroying. Each destroy_registered still sweeps and frees its range, so
    # both errors unwind with the first one; the hook's alloc(4) gets 1 (a was
    # 1..2, b 3..4), i.e. 101. Both ~Cy frames sit at their `delete other;`.
    source = program(PRELUDE + SLOT,
                     'class Cy {',
                     '  int n;',
                     '  Cy * other;',
                     '  Cy(int v){ n = v; }',
                     '  ~Cy(){',
                     '    event(n);',
                     '    delete other;',
                     '    event(0 - n);',
                     '  }',
                     '}',
                     'void __script_pre_destroy(){ event(100 + slot(alloc(4))); }',
                     'int main(){',
                     '  Cy * a = new Cy(1);',
                     '  Cy * b = new Cy(2);',
                     '  a.other = b;',
                     '  b.other = a;',
                     '  delete a;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5f-delete-cycle-reports-destroying', source,
                      error='Object is being destroyed', category='runtime_error', events=(1, 2),
                      trace=('Cy::~Cy', 'Cy::~Cy', 'main'),
                      lines=(line_of(source, 'delete other;'), line_of(source, 'delete other;'),
                             line_of(source, 'delete a;')),
                      teardown_events=(101,)))

    # Verifier addition 16: delete this inside a manual object's constructor
    # (before ob registers it) -> not registered. Q::Q is at its delete, the
    # factory at the class header, main at the declaration. The unregistered
    # range is released without ~Q: no 9 in main or at teardown, and the hook's
    # alloc(1) gets start 1 again (101).
    q_class = program('',
                      'class Q {',
                      '  int n;',
                      '  Q(){',
                      '    event(1);',
                      '    delete this;',
                      '    event(2);',
                      '  }',
                      '  ~Q(){ event(9); }',
                      '}')
    source = program(PRELUDE + SLOT + q_class,
                     'void __script_pre_destroy(){ event(100 + slot(alloc(1))); }',
                     'int main(){',
                     '  Q * p = new Q();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5d-delete-this-in-manual-constructor', source,
                      error='delete requires a live object', category='runtime_error', events=(1,),
                      trace=('Q::Q', '<new Q>', 'main'),
                      lines=(line_of(source, 'delete this;'), line_of(source, 'class Q {'),
                             line_of(source, 'Q * p = new Q();')),
                      teardown_events=(101,)))

    # Verifier addition 16, literal: the block message, before registration
    # matters. The failed literal never gets ~Q (no 9, also not at teardown).
    source = program(PRELUDE + q_class,
                     'int main(){',
                     '  Q a;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f5e-delete-this-in-literal-constructor', source,
                      error='delete requires a manual object; a literal object ends with its variable',
                      category='runtime_error', events=(1,), trace=('Q::Q', '<value Q>', 'main'),
                      lines=(line_of(source, 'delete this;'), line_of(source, 'class Q {'), line_of(source, 'Q a;')),
                      teardown_events=()))

    # F6, ownership scenario 29: ~Out deletes its manual peer; the nested
    # destruction completes, including its free, before ~Out continues, so
    # ~Out's alloc(1) reuses the peer's start 3 (o occupies 1..2): 13 (a peer
    # still held would push it to 4: 14); main then reuses 1 (21).
    result.append(own('own-heap-f6-destructor-deletes-manual-peer-first', program(
        PRELUDE + SLOT,
        'class In { int n; In(int v){ n = v; } ~In(){ event(0 - n); } }',
        'class Out { int n; In * peer; Out(int v){ n = v; peer = new In(3); } '
        '~Out(){ delete peer; event(10 + slot(alloc(1))); event(5); } }',
        'int main(){',
        '  Out * o = new Out(1);',
        '  delete o;',
        '  event(20 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(-3, 13, 5, 21)))

    # F6 nested: a destructor chain through manual next pointers; each nested
    # destroy_registered finishes before its deleter continues; all three 2-slot
    # ranges are free afterwards (alloc(6) gets 1: 101).
    result.append(own('own-heap-f6b-recursive-delete-list', program(
        PRELUDE + SLOT,
        'class Node { int v; Node * next; Node(int x, Node * n){ v = x; next = n; } '
        '~Node(){ event(v); delete next; event(0 - v); } }',
        'int main(){',
        '  Node * c = new Node(3, null);',
        '  Node * b = new Node(2, c);',
        '  Node * a = new Node(1, b);',
        '  delete a;',
        '  event(100 + slot(alloc(6)));',
        '  return 0;',
        '}'), 0, events=(1, 2, 3, -3, -2, -1, 101)))

    # Spec section 10: deleting another automatic object from a destructor is
    # refused; the automatic object still dies at main's root cleanup (-7).
    source = program(PRELUDE,
                     'class Out2 {',
                     '  int n;',
                     '  M * peer;',
                     '  ~Out2(){',
                     '    event(1);',
                     '    delete peer;',
                     '    event(2);',
                     '  }',
                     '}',
                     'int main(){',
                     '  M * a(7);',
                     '  Out2 * o = new Out2();',
                     '  o.peer = a;',
                     '  delete o;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f6c-destructor-deletes-automatic-peer', source,
                      error='Cannot delete an automatic object', category='runtime_error', events=(1, -7),
                      trace=('Out2::~Out2', 'main'), lines=(line_of(source, 'delete peer;'), line_of(source, 'delete o;'))))

    # F7: a C (*) parameter borrows the literal's block address.
    source = program(PRELUDE,
                     'void kill(M (*) p){',
                     '  delete p;',
                     '}',
                     'int main(){',
                     '  M a(1);',
                     '  kill(a);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f7-delete-borrowed-literal-parameter', source,
                      error='delete requires a manual object; a literal object ends with its variable',
                      category='runtime_error', events=(-1,), trace=('kill', 'main'),
                      lines=(line_of(source, 'delete p;'), line_of(source, 'kill(a);'))))

    # Delete check order: a block address is rejected before any liveness check,
    # so an expired literal address gives the block message, not "live object".
    source = program(PRELUDE + K_CLASS,
                     'int main(){',
                     '  K * q = null;',
                     '  { K a(1); q = a.self(); }',
                     '  delete q;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f7b-delete-expired-literal-address', source,
                      error='delete requires a manual object; a literal object ends with its variable',
                      category='runtime_error', events=(-1,), trace=('main',), lines=(line_of(source, 'delete q;'),)))

    # F8: raw memory functions reject object storage, also inside destructors.
    # Automatic object released by an inner block: main @ header.
    source = program(PRELUDE,
                     'class R8 {',
                     '  int n;',
                     '  ~R8(){',
                     '    event(1);',
                     '    boolean b = mem_free(address(this));',
                     '    event(2);',
                     '  }',
                     '}',
                     'int main(){',
                     '  { R8 * p(); }',
                     '  event(5);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f8-mem-free-this-in-destructor', source,
                      error=STORAGE.format('mem_free'), category='runtime_error',
                      events=(1,), trace=('R8::~R8', 'main'),
                      lines=(line_of(source, 'boolean b = mem_free(address(this));'), line_of(source, 'int main(){'))))

    # F8 for make_free on a literal's this (block-tagged address).
    source = program(PRELUDE,
                     'class R8b {',
                     '  int n;',
                     '  ~R8b(){',
                     '    event(1);',
                     '    make_free(address(this));',
                     '    event(2);',
                     '  }',
                     '}',
                     'int main(){',
                     '  R8b a;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f8b-make-free-literal-this-in-destructor', source,
                      error=STORAGE.format('make_free'), category='runtime_error',
                      events=(1,), trace=('R8b::~R8b', 'main'),
                      lines=(line_of(source, 'make_free(address(this));'), line_of(source, 'return 0;'))))

    # F8 for mem_send_up on a manual object's this, during delete from main.
    source = program(PRELUDE,
                     'class R8c {',
                     '  int n;',
                     '  ~R8c(){',
                     '    event(1);',
                     '    mem_send_up(address(this));',
                     '    event(2);',
                     '  }',
                     '}',
                     'int main(){',
                     '  R8c * p = new R8c();',
                     '  delete p;',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f8c-mem-send-up-manual-this-in-destructor', source,
                      error=STORAGE.format('mem_send_up'),
                      category='runtime_error', events=(1,), trace=('R8c::~R8c', 'main'),
                      lines=(line_of(source, 'mem_send_up(address(this));'), line_of(source, 'delete p;'))))

    # Ownership 3.14: an interior address of a live object allocation is object
    # storage too. p is manual, so destroy discards it without ~P3: no 9 in main
    # or at teardown (discard_script_objects, heepalloc.cpp:438-450).
    source = program(PRELUDE,
                     'class P3 { int a; int b; ~P3(){ event(9); } }',
                     'int main(){',
                     '  P3 * p = new P3();',
                     '  address x = address(p) + 1;',
                     '  var v = mem_get(x);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f8d-mem-get-object-interior', source,
                      error=STORAGE.format('mem_get'), category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'var v = mem_get(x);'),), teardown_events=()))

    # Verifier correction 12: block-tagged addresses count as object storage
    # even after the literal expired.
    source = program(PRELUDE + K_CLASS,
                     'int main(){',
                     '  address x = null;',
                     '  { K a(1); x = address(a.self()); }',
                     '  boolean b = mem_free(x);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f8e-mem-free-expired-block-address', source,
                      error=STORAGE.format('mem_free'), category='runtime_error',
                      events=(-1,), trace=('main',), lines=(line_of(source, 'boolean b = mem_free(x);'),)))

    # Verifier correction 12: a freed heap object's address is no longer object
    # storage: mem_free returns false (10); once a raw alloc reuses the start
    # (owned by main's root, reachable by the walk) mem_free frees that
    # allocation (21), so the next alloc(1) gets 1 again (31).
    result.append(own('own-heap-f8f-mem-free-freed-object-address', program(
        PRELUDE + SLOT,
        'int main(){',
        '  M * p = new M(1);',
        '  address a = address(p);',
        '  delete p;',
        '  event(10 + (mem_free(a) ? 1 : 0));',
        '  address r = alloc(1);',
        '  mem_get(r) = 5;',
        '  event(20 + (mem_free(a) ? 1 : 0));',
        '  event(30 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(-1, 10, 21, 31)))

    # LANGUAGE.md:218/245 (class storage is tagged before the constructor code
    # runs; construction is no exception) and heepalloc.cpp:213-224:
    # object_alloc tags the range before the constructor runs and before ob
    # registers it, so raw functions on this already refuse inside the
    # constructor. Frames: the constructor at the call, the factory
    # at the class header, main at the declaration. The unregistered range is
    # released without ~C (no 9; the hook's alloc(1) gets 1 again: 101).
    source = program(PRELUDE + SLOT,
                     'class C1 {',
                     '  int n;',
                     '  C1(){',
                     '    event(1);',
                     '    boolean b = mem_free(address(this));',
                     '    event(2);',
                     '  }',
                     '  ~C1(){ event(9); }',
                     '}',
                     'void __script_pre_destroy(){ event(100 + slot(alloc(1))); }',
                     'int main(){',
                     '  C1 * p = new C1();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f9-mem-free-this-in-manual-constructor', source,
                      error=STORAGE.format('mem_free'), category='runtime_error', events=(1,),
                      trace=('C1::C1', '<new C1>', 'main'),
                      lines=(line_of(source, 'boolean b = mem_free(address(this));'), line_of(source, 'class C1 {'),
                             line_of(source, 'C1 * p = new C1();')),
                      teardown_events=(101,)))

    # Same for make_free in an automatic object's constructor (<scoped C2>): the
    # object-storage check precedes make_free's ownership check.
    source = program(PRELUDE,
                     'class C2 {',
                     '  int n;',
                     '  C2(){',
                     '    event(1);',
                     '    make_free(address(this));',
                     '    event(2);',
                     '  }',
                     '  ~C2(){ event(9); }',
                     '}',
                     'int main(){',
                     '  C2 * p();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f9b-make-free-this-in-automatic-constructor', source,
                      error=STORAGE.format('make_free'), category='runtime_error', events=(1,),
                      trace=('C2::C2', '<scoped C2>', 'main'),
                      lines=(line_of(source, 'make_free(address(this));'), line_of(source, 'class C2 {'),
                             line_of(source, 'C2 * p();')),
                      teardown_events=()))

    # And mem_send_up of an interior address of this (the range 1..2 is tagged
    # as a whole, heepalloc.cpp:219-224).
    source = program(PRELUDE,
                     'class C3 {',
                     '  int a;',
                     '  int b;',
                     '  C3(){',
                     '    event(1);',
                     '    mem_send_up(address(this) + 1);',
                     '    event(2);',
                     '  }',
                     '  ~C3(){ event(9); }',
                     '}',
                     'int main(){',
                     '  C3 * p = new C3();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-f9c-mem-send-up-interior-this-in-manual-constructor', source,
                      error=STORAGE.format('mem_send_up'), category='runtime_error', events=(1,),
                      trace=('C3::C3', '<new C3>', 'main'),
                      lines=(line_of(source, 'mem_send_up(address(this) + 1);'), line_of(source, 'class C3 {'),
                             line_of(source, 'C3 * p = new C3();')),
                      teardown_events=()))
    return result


def destructor_ownership_cases():
    result = []

    # J1: a destructor reads and writes its own fields (heap manual, literal,
    # automatic).
    result.append(own('own-heap-j1-destructor-reads-writes-own-fields', program(
        PRELUDE,
        'class W { int n; int m; W(int v){ n = v; } ~W(){ m = n * 2; event(m); n = m + 1; event(n); } }',
        'int main(){',
        '  W * p = new W(3);',
        '  delete p;',
        '  { W a(5); }',
        '  W * q(7);',
        '  return 0;',
        '}'), 0, events=(6, 7, 10, 11, 14, 15)))

    # J2 (reachable through the observation host's reentry callback): ~RR runs
    # at active_calls 2, its probe at 3; reenter's guard makes 3, the reentered
    # root 4 (script::invoke adds none) and its probe 5. The reentered root's
    # transfer_parent is baseEnv, so its mem_send_up(alloc(1)) = 2 (RR still
    # holds 1) becomes global (ownership scenario 19) and is checked twice:
    # main's later alloc(2) skips the one-slot gap at 1 and the held 2 and gets
    # 3 (1 if 2 had been freed when bump returned), and the pre-destroy hook's
    # alloc(2) gets 3 again (103; main's 3..4 died with main). destroy then
    # frees 2 (empty teardown heap).
    result.append(own('own-heap-j2-destructor-host-reentry-depth-and-base-owned-allocation', program(
        REENTER + PRELUDE + SLOT,
        f'int bump(int v):0f01{{ probe(v); address a = alloc(1); mem_send_up(a); return v + slot(a); }}',
        f'class RR {{ int n; ~RR(){{ probe(1); event(reenter({ID_F01}, 5)); }} }}',
        'void __script_pre_destroy(){ event(100 + slot(alloc(2))); }',
        'int main(){',
        '  { RR * p(); }',
        '  probe(2);',
        '  event(slot(alloc(2)));',
        '  return 0;',
        '}'), 0, partial=True, raw_events=[
            probe(1, 3), reentry(ID_F01, 5), probe(5, 5), returned(7), ev(7), probe(2, 2), ev(3)],
        teardown_events=(103,)))

    # J2, second half: an automatic object returned to the reentry callback from
    # inside a destructor. hold runs as a reentered root whose caller is baseEnv;
    # its `return p;` (ro) moves the automatic M (start 2: the destroying RA
    # still holds 1) to baseEnv (ownership 3.13 ro step 9, 3.16). It therefore
    # survives the block and main: main's alloc(2) gets slot 3 (13; slot 1 had
    # M been freed), the hook's alloc(2) gets slot 3 (103), and destroy's
    # baseEnv cleanup runs ~M after the hook (-4).
    result.append(own('own-heap-j2b-reentry-returned-automatic-owned-by-base', program(
        REENTER_ADDRESS + PRELUDE + SLOT,
        'address ga;',
        'M * hold(int v):0f01{ M * p(v); return p; }',
        f'class RA {{ int n; RA(int v){{ n = v; }} ~RA(){{ ga = reenter({ID_F01}, n); }} }}',
        'void __script_pre_destroy(){ event(100 + slot(alloc(2))); }',
        'int main(){',
        '  { RA * r(4); }',
        '  event(10 + slot(alloc(2)));',
        '  return 0;',
        '}'), 0, raw_events=[reentry(ID_F01, 4), returned_pointer(2), ev(13)],
        globals=[typed(7, heap_address(2))], teardown_events=(103, -4)))

    # J3 (first half): reentry during destroy fails in script::invoke; the hook
    # stops there (no 2), destroy completes and rethrows. The other half
    # (destroy during a call) needs a C++ host and is skipped.
    result.append(own('own-heap-j3-reentry-during-destroy', program(
        REENTER + PRELUDE,
        'int bump(int v):0f01{ event(50); return v + 1; }',
        f'void __script_pre_destroy(){{ event(1); reenter({ID_F01}, 5); event(2); }}',
        'int main(){ return 0; }'), 0,
        raw_teardown_events=[ev(1), reentry(ID_F01, 5)], teardown_error='Script is being destroyed'))

    # J4: alloc in a destructor is owned by the destructor's current scope.
    # ~A4 is wrapped (owned field f): its body block owns t = 2..3 (p is 1): 22,
    # and frees it before final runs, so ~F4 (unwrapped, owned by its root) gets
    # 2 again: 12 (14 had t lived until ~A4's root exit); main reuses 1: 31.
    result.append(own('own-heap-j4-destructor-allocations-owned-by-current-scope', program(
        PRELUDE + SLOT,
        'class F4 { int n; ~F4(){ event(10 + slot(alloc(1))); } }',
        'class A4 { F4 f; ~A4(){ address t = alloc(2); event(20 + slot(t)); } }',
        'int main(){',
        '  A4 * p = new A4();',
        '  delete p;',
        '  event(30 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(22, 12, 31)))

    # J5a: unwrapped destructor of an automatic object released by main's root
    # cleanup: the caller is closing, set_owner refuses; cleanup continues (x).
    # y = 2 (x is 1), so ~S5's alloc gets 3 (13). ~S5 is at its mem_send_up line.
    s5_fail = program('',
                      'class S5 {',
                      '  int n;',
                      '  ~S5(){',
                      '    address a = alloc(1);',
                      '    event(10 + slot(a));',
                      '    mem_send_up(a);',
                      '    event(99);',
                      '  }',
                      '}')
    source = program(PRELUDE + SLOT + s5_fail,
                     'int main(){',
                     '  M * x(1);',
                     '  S5 * y();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-j5a-send-up-from-scope-exit-destructor-fails', source,
                      error='Cannot transfer an allocation into a closing scope', category='runtime_error',
                      events=(13, -1), trace=('S5::~S5', 'main'),
                      lines=(line_of(source, 'mem_send_up(a);'), line_of(source, 'return 0;'))))

    # J5b: the same kind of destructor run for a statement temporary has the
    # (live) block env as caller: the allocation 1 moves into the block (11) and
    # lives until the block exits (the block's alloc gets 2: 22; after the block
    # 1 is free: 31).
    result.append(own('own-heap-j5b-send-up-from-statement-temporary-destructor', program(
        PRELUDE + SLOT,
        'class S5 { int n; S5(int v){ n = v; } ~S5(){ address a = alloc(1); mem_send_up(a); event(10 + slot(a)); } }',
        'S5 mk5(int v){ S5 t(v); return t; }',
        'int main(){',
        '  {',
        '    event(mk5(7).n);',
        '    event(20 + slot(alloc(1)));',
        '  }',
        '  event(30 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(7, 11, 22, 31)))

    # J5c: wrapped ~Q5 (owned field and a base with a destructor): its body block
    # sends a = 1 up to ~Q5's root (31), which holds it through the field drop
    # (~P5 gets 2: 22) and the base destructor (~B5 gets 2: 12), and frees it
    # when ~Q5 returns (main gets 1: 41).
    result.append(own('own-heap-j5c-send-up-in-wrapped-destructor-held-by-root', program(
        PRELUDE + SLOT,
        'class P5 { int n; ~P5(){ event(20 + slot(alloc(1))); } }',
        'class B5 { int k; ~B5(){ event(10 + slot(alloc(1))); } }',
        'class Q5 : B5 { P5 p; ~Q5(){ address a = alloc(1); mem_send_up(a); event(30 + slot(a)); } }',
        'int main(){',
        '  { Q5 q; }',
        '  event(40 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(31, 22, 12, 41)))

    # J5a at destroy: an automatic object returned to the host entry is owned by
    # baseEnv (ownership scenario 6); destroy's baseEnv cleanup runs ~S5 with the
    # closing baseEnv as caller (its alloc gets 2: 12), so the transfer fails.
    result.append(own('own-heap-j5d-send-up-from-teardown-destructor-fails', program(
        PRELUDE + SLOT + s5_fail,
        'S5 * entry():0f02{ S5 * p(); return p; }',
        'int main(){ return 0; }'), heap_address(1), tag=7,
        options=['--function', hex(ID_F02)],
        teardown_events=(12,), teardown_error='Cannot transfer an allocation into a closing scope'))

    # J6 + verifier addition 25: ~Q6 frees an address it holds and reports
    # n + result, so each object is identified (q 10, q2 20, q3 30).
    # Block 1: r before q -> the closing block owns r, the walk reaches it
    #   through ~Q6's caller: 11; entry erased, no double free (main gets 1: 101).
    # Block 2: r2 after q2 -> r2 is released first, so mem_free(r2) is false: 20;
    #   main's root holds 1, so its alloc gets 2: 202.
    # Block 3: r3 = 3 owned by main's root, reached through the block's parent:
    #   31; main's alloc gets 3 again: 303.
    result.append(own('own-heap-j6-destructor-frees-closing-and-outer-scope-allocations', program(
        PRELUDE + SLOT,
        'class Q6 { int n; address r; Q6(int k, address a){ n = k; r = a; } ~Q6(){ event(n + (mem_free(r) ? 1 : 0)); } }',
        'int main(){',
        '  {',
        '    address r = alloc(1);',
        '    Q6 q(10, r);',
        '  }',
        '  event(100 + slot(alloc(1)));',
        '  {',
        '    Q6 q2(20, null);',
        '    address r2 = alloc(1);',
        '    q2.r = r2;',
        '  }',
        '  event(200 + slot(alloc(1)));',
        '  address r3 = alloc(1);',
        '  { Q6 q3(30, r3); }',
        '  event(300 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(11, 101, 20, 202, 31, 303)))

    # J7, ownership scenario 22 (reachable through the reentry callback): the
    # reentered root's chain is baseEnv only, so main's allocation cannot be
    # freed there. Frames: the reentered function (at its mem_free line, not its
    # header), the host callback's native frame (no name, no position), main.
    source = program(REENTER + PRELUDE,
                     'address g;',
                     'int freer(int v):0f01{',
                     '  event(mem_free(g) ? 1 : 0);',
                     '  return v;',
                     '}',
                     'int main(){',
                     '  g = alloc(1);',
                     f'  event(reenter({ID_F01}, 5));',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-j7-reentry-cannot-free-suspended-allocation', source,
                      error='Allocation 1 is scheduled for automatic release by another active scope and cannot be freed here',
                      category='runtime_error', raw_events=[reentry(ID_F01, 5)],
                      globals=[typed(7, heap_address(1))], trace=('freer', '', 'main'),
                      lines=(line_of(source, 'event(mem_free(g)'), 0, line_of(source, 'event(reenter('))))

    # Ownership scenario 19: mem_send_up at main's root and at a reentered root
    # both go to baseEnv (their caller); they survive main (main's own alloc gets
    # 3: 13), the pre-destroy hook still sees 1 and 2 taken (3: 23), destroy
    # frees them (empty teardown heap).
    result.append(own('own-heap-j7b-root-send-up-reaches-base-scope', program(
        REENTER + PRELUDE + SLOT,
        'int keep(int v):0f01{ address a = alloc(1); mem_send_up(a); return slot(a); }',
        'void __script_pre_destroy(){ event(20 + slot(alloc(1))); }',
        'int main(){',
        '  address m = alloc(1);',
        '  mem_send_up(m);',
        f'  event(reenter({ID_F01}, 0));',
        '  event(10 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, raw_events=[reentry(ID_F01, 0), returned(2), ev(2), ev(13)], teardown_events=(23,)))

    # Contrast to J7: the mem_free walk of a reentered root ends at baseEnv
    # (heepalloc.cpp:489-504), so it may free an allocation main sent up to
    # baseEnv: freer returns 10 + 1; the range is gone (main's alloc gets 1: 21).
    result.append(own('own-heap-j7c-reentry-frees-base-owned-allocation', program(
        REENTER + PRELUDE + SLOT,
        'address g;',
        'int freer(int v):0f01{ return v + (mem_free(g) ? 1 : 0); }',
        'int main(){',
        '  g = alloc(1);',
        '  mem_send_up(g);',
        f'  event(reenter({ID_F01}, 10));',
        '  event(20 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, raw_events=[reentry(ID_F01, 10), returned(11), ev(11), ev(21)],
        globals=[typed(7, heap_address(1))]))

    # J8 on a heap object: assigning a temporary into the own literal field is
    # in place (the old M(1) never gets a destructor: no -1); the temporary dies
    # at statement end (-5); the field, bumped to 6 afterwards, is dropped
    # with n = 6 (-6).
    result.append(own('own-heap-j8-destructor-assigns-own-field-in-place', program(
        PRELUDE,
        'class Z { M m(1); ~Z(){ m = mk(5); m.n = m.n + 1; } }',
        'int main(){',
        '  Z * z = new Z();',
        '  delete z;',
        '  event(7);',
        '  return 0;',
        '}'), 0, events=(-5, -6, 7)))

    # Ownership scenario 30 + spec section 10: objects created in a destructor
    # die in its own scope (LIFO: b, then a); a make_free'd raw allocation made
    # there (3: 13) outlives the destructor and destroy (spec 11.7: teardown heap
    # holds it); main reuses p's start 1 (21).
    result.append(own('own-heap-j9-destructor-creates-objects-and-unowned-allocation', program(
        PRELUDE + SLOT,
        'class C9 { int n; ~C9(){ M * a(2); M b(3); address t = alloc(1); make_free(t); event(10 + slot(t)); } }',
        'int main(){',
        '  C9 * p = new C9();',
        '  delete p;',
        '  event(20 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(13, -3, -2, 21), teardown_heap=[raw_range(3, VOID)]))
    return result


def layout_cases():
    result = []

    # K1 (root variant): x = 1..2, y = 3..4, main's alloc(1) = 5. Root cleanup
    # LIFO frees 5 first, so ~AL(y) gets 5 again (y still holds 3..4): 2*10+5,
    # then ~AL(x) gets y's freed start 3: 1*10+3. (x first would give 15, 21.)
    result.append(own('own-heap-k1-scope-exit-first-fit-in-destructors', program(
        PRELUDE + SLOT,
        'class AL { int a; int b; AL(int v){ a = v; } ~AL(){ event(a * 10 + slot(alloc(2))); } }',
        'int main(){',
        '  AL * x(1);',
        '  AL * y(2);',
        '  event(slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(5, 25, 13)))

    # K1 / ownership 6.6: first fit takes the lowest gap that fits.
    result.append(own('own-heap-k1b-first-fit-gap-reuse', program(
        PRELUDE,
        'int main(){',
        '  M * a = new M(1);',
        '  M * b = new M(2);',
        '  M * c = new M(3);',
        '  delete b;',
        '  M * d(4);',
        '  print(address(d));',
        '  address r = alloc(2);',
        '  print(r);',
        '  delete a;',
        '  address s = alloc(2);',
        '  print(s);',
        '  address t = alloc(1);',
        '  print(t);',
        '  delete c;',
        '  print(alloc(1));',
        '  return 0;',
        '}'), 0, events=(-2, -1, -3, -4), stdout='2\n4\n6\n1\n3\n'))

    # K2, ownership scenario 32: the first block of the process has id 1.
    source = program(PRELUDE + K_CLASS,
                     'int main(){',
                     '  K * q = null;',
                     '  { K a(1); q = a.self(); }',
                     '  return q.n;',
                     '}')
    result.append(own('own-heap-k2-expired-literal-address', source,
                      error='Invalid or expired object address: ' + block_decimal(1), category='out_of_range',
                      events=(-1,), trace=('main',), lines=(line_of(source, 'return q.n;'),)))

    # Verifier correction 29 / E7: a failed automatic construction frees its
    # range 2..3 (r = 1 and the constructor's t = 4 are unowned and survive
    # everything). The pre-destroy hook sees the gap: alloc(3) does not fit it
    # (5: 15), alloc(2) reuses the failed start (2: 22). No ~FB (no 9).
    source = program(PRELUDE + SLOT,
                     'class FB {',
                     '  int a;',
                     '  int b;',
                     '  FB(){',
                     '    address t = alloc(1);',
                     '    make_free(t);',
                     '    int z = 0;',
                     '    int w = 1 / z;',
                     '  }',
                     '  ~FB(){ event(9); }',
                     '}',
                     'void __script_pre_destroy(){ event(10 + slot(alloc(3))); event(20 + slot(alloc(2))); }',
                     'int main(){',
                     '  address r = alloc(1);',
                     '  make_free(r);',
                     '  FB * f();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-k3-failed-construction-range-reused-by-fit', source,
                      error='Division by zero', category='runtime_error',
                      trace=('FB::FB', '<scoped FB>', 'main'),
                      lines=(line_of(source, 'int w = 1 / z;'), line_of(source, 'class FB {'), line_of(source, 'FB * f();')),
                      teardown_events=(15, 22), teardown_heap=[raw_range(1, VOID), raw_range(4, VOID)]))

    # LANGUAGE.md: pointer fields do not own their target. Hold has no
    # destructor; the manual M objects survive delete h and the block (only 5 in
    # main) and are discarded at destroy without ~M (no teardown event).
    result.append(own('own-heap-k4-pointer-field-does-not-own', program(
        PRELUDE,
        'class Hold { int n; M * m; }',
        'int main(){',
        '  Hold * h = new Hold();',
        '  h.m = new M(1);',
        '  delete h;',
        '  { Hold w; w.m = new M(2); }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(5,), teardown_events=()))

    # LANGUAGE.md escape note: an automatic pointer kept in a global dangles
    # after its block (no reuse: freed start 1).
    source = program(PRELUDE,
                     'M * g;',
                     'int main(){',
                     '  { M * p(1); g = p; }',
                     '  return g.n;',
                     '}')
    result.append(own('own-heap-k5-escaped-automatic-pointer-dangles', source,
                      error='Invalid or freed object address: 1', category='out_of_range', events=(-1,),
                      globals=[typed(7, heap_address(1))], trace=('main',), lines=(line_of(source, 'return g.n;'),)))

    # Cleanup spec H1 / ownership scenario 38, budget half: exhaustion in main's
    # loop. Root cleanup still frees r (3) and the automatic a (2), whose ~M is
    # entered but fails at its first tick (no -2 in main); the manual g (1)
    # stays. destroy's guard resets the budget, so the hook's alloc(2) finds
    # 2..3 free (12; 13 had a's range still been held, 14 had r's) and ~M of g
    # runs in full (-1). Error at the loop (any of its nodes, one line).
    # Swept: every budget exhausts somewhere, and terminal_shape ignores
    # remaining_steps, so the sweep ends once two consecutive budgets fail at
    # the loop position the full budget reports, with the same teardown.
    source = program(PRELUDE + SLOT,
                     'M * g;',
                     'void __script_pre_destroy(){ event(10 + slot(alloc(2))); delete g; }',
                     'int main(){',
                     '  g = new M(1);',
                     '  M * a(2);',
                     '  address r = alloc(1);',
                     '  int i = 0;',
                     '  while (i >= 0) { i = 0; }',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-k7-budget-exhaustion-frees-scope-storage', source,
                      error='Script execution step limit exceeded', category='runtime_error', remaining=0,
                      globals=[typed(7, heap_address(1))], trace=('main',), lines=(line_of(source, 'while (i >= 0)'),),
                      teardown_events=(12, -1)))

    # Spec section 7 DESTROY: an undeleted manual object is discarded without
    # its destructor (no teardown event, heap empty after teardown) ...
    result.append(own('own-heap-k6-manual-global-discarded-at-destroy', program(
        PRELUDE,
        'M * g;',
        'int main(){ g = new M(1); return g.n; }'), 1,
        globals=[typed(7, heap_address(1))], teardown_events=()))

    # ... unless a pre-destroy hook deletes it (destructor runs at teardown).
    result.append(own('own-heap-k6b-manual-global-deleted-by-hook', program(
        PRELUDE,
        'M * g;',
        'void __script_pre_destroy(){ delete g; event(5); }',
        'int main(){ g = new M(1); return g.n; }'), 1,
        globals=[typed(7, heap_address(1))], teardown_events=(-1, 5)))
    return result


def raw_memory_cases():
    result = []

    # Ownership 3.13: alloc size errors are invalid_argument; builtins add no frame.
    source = program(PRELUDE,
                     'int main(){',
                     '  int n = 0 - 1;',
                     '  address p = alloc(n);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o1-alloc-negative-size', source,
                      error='Invalid allocation size', category='invalid_argument',
                      trace=('main',), lines=(line_of(source, 'address p = alloc(n);'),)))

    source = program(PRELUDE,
                     'int main(){',
                     '  int n = 1048576;',
                     '  address p = alloc(n);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o1b-alloc-size-limit', source,
                      error='Invalid allocation size', category='invalid_argument',
                      trace=('main',), lines=(line_of(source, 'address p = alloc(n);'),)))

    # 2 + 1048575 > max_slots (1048576): refused before any slot is created.
    source = program(PRELUDE,
                     'int main(){',
                     '  address a = alloc(1);',
                     '  int n = 1048575;',
                     '  address p = alloc(n);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o1c-heap-capacity-exceeded', source,
                      error='Heap capacity exceeded', category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'address p = alloc(n);'),)))

    # alloc(0) is the null address with no ownership: it is null (11), mem_free
    # is false (20), no slot is consumed (31), and make_free finds nothing in
    # the current scope.
    source = program(PRELUDE + SLOT,
                     'int main(){',
                     '  address p = alloc(0);',
                     '  event(10 + (p == null ? 1 : 0));',
                     '  event(20 + (mem_free(p) ? 1 : 0));',
                     '  event(30 + slot(alloc(1)));',
                     '  make_free(p);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o1d-alloc-zero-is-unowned-null', source,
                      error='Allocation is not owned by current scope', category='runtime_error', events=(11, 20, 31),
                      trace=('main',), lines=(line_of(source, 'make_free(p);'),)))

    # Ownership 3.14 mem_free: interior of an owned range (no owner at that key)
    # false; own allocation true; again false; null false; garbage false; the
    # range is gone (next alloc gets 1). The emitters are main's statements in
    # a fixed order. Steps: root 1, p 3, interior 7, two frees 5 each, z 2,
    # null 5, garbage 7, slot 28, return 2 = 65.
    result.append(own('own-heap-o2-mem-free-results', program(
        PRELUDE + SLOT,
        'int main(){',
        '  address p = alloc(2);',
        '  event(mem_free(p + 1) ? 1 : 0);',
        '  event(mem_free(p) ? 1 : 0);',
        '  event(mem_free(p) ? 1 : 0);',
        '  address z = null;',
        '  event(mem_free(z) ? 1 : 0);',
        '  event(mem_free(z + 1000) ? 1 : 0);',
        '  event(slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(0, 1, 0, 0, 0, 1), remaining=FULL - 65))

    # Ownership scenarios 20/21: the walk covers lexical parents and callers:
    # an inner block frees main's p (11, then 20); fr2 -> fr frees s owned by the
    # calling block (31); fr frees q owned by main's root through the block (41);
    # everything is free again (1, 2, 3).
    result.append(own('own-heap-o3-mem-free-walks-lexical-and-caller-chain', program(
        PRELUDE + SLOT,
        'boolean fr(address a){ return mem_free(a); }',
        'boolean fr2(address a){ return fr(a); }',
        'int main(){',
        '  address p = alloc(1);',
        '  address q = alloc(1);',
        '  { event(10 + (mem_free(p) ? 1 : 0)); event(20 + (mem_free(p) ? 1 : 0)); }',
        '  { address s = alloc(1); event(30 + (fr2(s) ? 1 : 0)); event(40 + (fr(q) ? 1 : 0)); }',
        '  event(50 + slot(alloc(1)));',
        '  event(60 + slot(alloc(1)));',
        '  event(70 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(11, 20, 31, 41, 51, 62, 73)))

    # Ownership scenario 23: make_free only looks at the current env's list.
    source = program(PRELUDE,
                     'int main(){',
                     '  address p = alloc(1);',
                     '  { make_free(p); }',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o4-make-free-requires-current-scope', source,
                      error='Allocation is not owned by current scope', category='runtime_error',
                      trace=('main',), lines=(line_of(source, '{ make_free(p); }'),)))

    # Ownership scenario 24 + spec 11.7: a make_free'd allocation outlives its
    # call (main's alloc gets 2), keeps its value (42), may be freed by any
    # scope (true), and survives destroy (a = 1 with 42 remains after teardown).
    leak = 'address leak(int v){ address a = alloc(1); mem_get(a) = v; make_free(a); return a; }'
    result.append(own('own-heap-o4b-make-free-outlives-call-and-destroy', program(
        PRELUDE + SLOT, leak,
        'int main(){',
        '  address a = leak(42);',
        '  event(slot(alloc(1)));',
        '  var v = mem_get(a);',
        '  event(int(v));',
        '  address b = leak(43);',
        '  { event(mem_free(b) ? 1 : 0); }',
        '  event(slot(b));',
        '  return 0;',
        '}'), 0, events=(2, 42, 1, 3), teardown_heap=[raw_range(1, typed(0, 42))]))

    # Ownership 3.14: an unowned allocation is in no scope's list.
    source = program(PRELUDE, leak,
                     'int main(){',
                     '  address a = leak(7);',
                     '  mem_send_up(a);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o4c-send-up-after-make-free-fails', source,
                      error='Allocation is not owned by current scope', category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'mem_send_up(a);'),),
                      teardown_heap=[raw_range(1, typed(0, 7))]))

    # Ownership scenario 16: mem_send_up at a callee root moves the allocation to
    # the call site's innermost scope (a = 1: 11; the block's alloc gets 2: 22),
    # which frees it at its exit (31).
    result.append(own('own-heap-o5-send-up-from-callee-root-to-call-site', program(
        PRELUDE + SLOT,
        'address up(){ address p = alloc(1); mem_send_up(p); return p; }',
        'int main(){',
        '  {',
        '    address a = up();',
        '    event(10 + slot(a));',
        '    event(20 + slot(alloc(1)));',
        '  }',
        '  event(30 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(11, 22, 31)))

    # Ownership scenario 17: in a nested scope the target is the lexical parent
    # (the callee's root: nest's own alloc gets 2: 12), freed at the callee's
    # exit (nest returns slot(keep) = 1: 21); the caller then reuses 1 (31).
    result.append(own('own-heap-o5b-send-up-nested-scope-to-parent', program(
        PRELUDE + SLOT,
        'int nest(){ address keep = null; { address p = alloc(1); mem_send_up(p); keep = p; } '
        'event(10 + slot(alloc(1))); return slot(keep); }',
        'int main(){',
        '  event(20 + nest());',
        '  event(30 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(12, 21, 31)))

    # Ownership scenario 18: after one transfer the block no longer owns it.
    source = program(PRELUDE,
                     'int main(){',
                     '  {',
                     '    address p = alloc(1);',
                     '    mem_send_up(p);',
                     '    mem_send_up(p); // again',
                     '  }',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o5c-second-send-up-not-owned', source,
                      error='Allocation is not owned by current scope', category='runtime_error',
                      trace=('main',), lines=(line_of(source, '// again'),)))

    # Successive transfers climb one scope each: inner -> middle -> root, so the
    # middle block's alloc gets 2 (12) and its exit leaves p (1) allocated, so
    # main's alloc gets 2 (22).
    result.append(own('own-heap-o5d-send-up-chain-through-scopes', program(
        PRELUDE + SLOT,
        'int main(){',
        '  address p = null;',
        '  {',
        '    {',
        '      p = alloc(1);',
        '      mem_send_up(p);',
        '    }',
        '    mem_send_up(p);',
        '    event(10 + slot(alloc(1)));',
        '  }',
        '  event(20 + slot(alloc(1)));',
        '  return 0;',
        '}'), 0, events=(12, 22)))

    # Verifier correction 14 order: the object-storage check precedes the
    # ownership check (the manual object is owned by no scope). p is discarded
    # at destroy without ~M (no teardown event).
    source = program(PRELUDE,
                     'int main(){',
                     '  M * p = new M(1);',
                     '  { mem_send_up(address(p)); }',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o5e-send-up-object-storage-checked-first', source,
                      error=STORAGE.format('mem_send_up'),
                      category='runtime_error', trace=('main',), lines=(line_of(source, 'mem_send_up('),),
                      teardown_events=()))

    # Correction 14 order: main's root has a parent target (baseEnv), so null
    # reaches the ownership check.
    source = program(PRELUDE,
                     'int main(){',
                     '  address z = null;',
                     '  mem_send_up(z);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o5f-send-up-null-not-owned', source,
                      error='Allocation is not owned by current scope', category='runtime_error',
                      trace=('main',), lines=(line_of(source, 'mem_send_up(z);'),)))

    # Ownership scenario 8: ro of a freed start fails at the return node, before
    # mark_returned (verify addition 14): gone is reported at its `return d;`
    # line, not at its header line.
    source = program(PRELUDE,
                     'M * gone(){',
                     '  M * d = new M(1);',
                     '  delete d;',
                     '  return d;',
                     '}',
                     'int main(){',
                     '  M * q = gone();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o6-return-deleted-manual-pointer', source,
                      error='Object return requires a live allocation', category='runtime_error', events=(-1,),
                      trace=('gone', 'main'), lines=(line_of(source, 'return d;'), line_of(source, 'M * q = gone();'))))

    # Ownership verify addition 21: the freed start is reused by a raw alloc
    # owned by this frame, so ro finds an unregistered start with a holder; the
    # error sits at the return node.
    # spec correction: ownership spec 9 A12 and verify correction 24 say this
    # error needs hand-written exec; a stale pointer whose start was reused by
    # a raw alloc of the returning frame reaches it from source
    # (interpreter/heepalloc.cpp:404-414: is_allocation_start true, no record,
    # owner_of == the frame root, so holder is set).
    source = program(PRELUDE,
                     'M * reused(){',
                     '  M * s = new M(1);',
                     '  delete s;',
                     '  address r = alloc(1);',
                     '  return s;',
                     '}',
                     'int main(){',
                     '  M * q = reused();',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o6b-return-reused-raw-start-before-construction', source,
                      error='Cannot return an object before construction is complete', category='runtime_error',
                      events=(-1,), trace=('reused', 'main'),
                      lines=(line_of(source, 'return s;'), line_of(source, 'M * q = reused();'))))

    # Addition 21: the freed start now holds the frame's automatic q, so ro of
    # the stale p transfers q to the call-site block: q dies after event(4).
    result.append(own('own-heap-o6c-return-reused-start-transfers-new-automatic', program(
        PRELUDE,
        'M * swap(){ M * p = new M(1); delete p; M * q(2); event(3); return p; }',
        'int main(){',
        '  {',
        '    M * r = swap();',
        '    event(4);',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-1, 3, 4, -2, 5)))

    # LANGUAGE.md: `return new C();` stays manual; never destroyed by a scope
    # (no -1 in main), discarded at destroy without its destructor.
    result.append(own('own-heap-o6d-returned-new-stays-manual', program(
        PRELUDE,
        'M * mkn(int v){ return new M(v); }',
        'int main(){',
        '  { M * p = mkn(1); event(p.n); }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(1, 5), teardown_events=()))

    # Ownership 3.13 ro step 6: returning this of an automatic object owned by
    # another frame's scope is borrowed: no promotion into the inner block.
    result.append(own('own-heap-o6e-return-this-of-automatic-no-transfer', program(
        PRELUDE + K_CLASS,
        'int main(){',
        '  {',
        '    K * a(1);',
        '    { K * b = a.self(); event(b.n); }',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(1, 5, -1, 6)))

    # LANGUAGE.md "普通 address 返回沿用 r": an address return does not promote
    # the allocation; it is freed at the callee's exit.
    source = program(PRELUDE,
                     'address dangling(){ address p = alloc(1); mem_get(p) = 7; return p; }',
                     'int main(){',
                     '  address q = dangling();',
                     '  var v = mem_get(q);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o7-address-return-does-not-promote', source,
                      error='Invalid or freed heap pointer: 1', category='out_of_range',
                      trace=('main',), lines=(line_of(source, 'var v = mem_get(q);'),)))

    # Rule E: a while condition runs in the enclosing env, so its allocations
    # accumulate there (1, 2, 3), while body allocations die with each body.
    result.append(own('own-heap-o8-while-condition-allocations-accumulate', program(
        PRELUDE + SLOT,
        'int main(){',
        '  int i = 0;',
        '  while (slot(alloc(1)) < 3) { address t = alloc(1); event(slot(t)); i = i + 1; }',
        '  event(slot(alloc(1)));',
        '  return i;',
        '}'), 2, events=(2, 3, 4)))

    # Rule E for if: the condition's alloc(1) = 1 is owned by the block running
    # the if, the non-block body `a = alloc(2)` (2..3) by the body's own
    # scoped_execute scope, which frees it when the body ends. So the block's
    # alloc(1) gets slot 2 (12). Had the body allocation stayed with the block
    # it would get 4; had the condition's died with the condition, 1 (or 3 with
    # the body's kept by the block). The block's exit frees 1 and 2, so
    # alloc(3) after it gets 1 (21).
    result.append(own('own-heap-o12-if-condition-and-non-block-body-allocations', program(
        PRELUDE + SLOT,
        'int main(){',
        '  address a = null;',
        '  {',
        '    if (slot(alloc(1)) == 1) a = alloc(2);',
        '    event(10 + slot(alloc(1)));',
        '  }',
        '  event(20 + slot(alloc(3)));',
        '  return 0;',
        '}'), 0, events=(12, 21)))

    # A non-block while body runs in a fresh scoped_execute scope per iteration,
    # so each body's alloc(1) gets 1 again: note sees 10*i + 1 = 1, 11, 21
    # (1, 12, 23 had they accumulated in the while's env); afterwards alloc(2)
    # gets 1 (101).
    result.append(own('own-heap-o12b-non-block-while-body-allocations-die-per-iteration', program(
        PRELUDE + SLOT,
        'int note(int v){ event(v); return 1; }',
        'int main(){',
        '  int i = 0;',
        '  while (i < 3) i = i + note(10 * i + slot(alloc(1)));',
        '  event(100 + slot(alloc(2)));',
        '  return i;',
        '}'), 3, events=(1, 11, 21, 101)))

    # LANGUAGE.md globals / ownership verify addition 11: global initializers and
    # the onload body run in onload's root, whose caller is baseEnv. The
    # automatic object (1) and raw allocation ga (2) die when onload returns
    # (LIFO: 2, then ~M of 1 during flush: -1); a root mem_send_up (3) goes to
    # baseEnv and lives until destroy. main's alloc(2) therefore gets slot 1
    # (11; slot 4 had ga stayed allocated), and the hook's alloc(3) skips the
    # 1..2 gap (slot 4: 24). main's 1..2 range reuses g's start as a raw range, so g.n reports
    # non-object storage (correction 28).
    source = program(PRELUDE + SLOT,
                     'M * g = mkp(1);',
                     'address ga = alloc(1);',
                     'address keep;',
                     'void __script_onload(){ keep = alloc(1); mem_send_up(keep); }',
                     'void __script_pre_destroy(){ event(20 + slot(alloc(3))); }',
                     'int main(){',
                     '  event(10 + slot(alloc(2)));',
                     '  return g.n;',
                     '}')
    result.append(own('own-heap-o10-onload-allocations-and-send-up', source,
                      error='Object member access requires object storage', category='runtime_error', events=(-1, 11),
                      globals=[typed(7, heap_address(1)), typed(7, heap_address(2)), typed(7, heap_address(3))],
                      trace=('main',), lines=(line_of(source, 'return g.n;'),), teardown_events=(24,)))

    # Ownership scenario 38 / cleanup G7: with max_call_depth 3, ~M9 (deleted
    # from kill, so at active_calls 3) fails at its event call, which adds the
    # host's native frame. ~M9 is at its event line, kill at its delete line
    # (both below their headers). The range is still freed: the pre-destroy
    # hook (at 2, alloc at 3) gets start 1 again (101).
    source = program(PRELUDE,
                     'class M9 {',
                     '  int n;',
                     '  M9(int v){ n = v; }',
                     '  ~M9(){',
                     '    event(0 - n);',
                     '  }',
                     '}',
                     'void kill(M9 * p){',
                     '  delete p;',
                     '}',
                     'void __script_pre_destroy(){ address z = null; event(alloc(1) == z + 1 ? 101 : 100); }',
                     'int main(){',
                     '  M9 * p = new M9(1);',
                     '  kill(p);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-o9-destructor-depth-failure-still-frees', source,
                      error='Script call depth limit exceeded', category='runtime_error',
                      trace=('', 'M9::~M9', 'kill', 'main'),
                      lines=(0, line_of(source, 'event(0 - n);'), line_of(source, 'delete p;'), line_of(source, 'kill(p);')),
                      options=['--call-depth', '3'], teardown_events=(101,)))
    return result


def argument_type_cases():
    """Builtin argument types (heepalloc.cpp:453-460, ownership 3.13-3.14,
    verify correction 14 step 1): the compiler accepts `var` arguments, so the
    runtime type check is reachable from source. invalid_argument, no builtin
    frame; main is at the call. A var holding the right runtime type passes."""
    result = []

    # alloc: a var holding an int is accepted (1..2: event 1); a var holding an
    # address is refused. main's root still frees 1..2 (empty teardown heap).
    source = program(PRELUDE + SLOT,
                     'int main(){',
                     '  var n = 2;',
                     '  address p = alloc(n);',
                     '  event(slot(p));',
                     '  var w = p;',
                     '  address q = alloc(w);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-a1-alloc-var-argument-type', source,
                      error='alloc requires one int argument', category='invalid_argument', events=(1,),
                      trace=('main',), lines=(line_of(source, 'address q = alloc(w);'),)))

    # mem_free: a var holding main's own address frees it (true: 11); a var
    # holding an int is refused before any ownership lookup.
    source = program(PRELUDE,
                     'int main(){',
                     '  var a = alloc(1);',
                     '  event(10 + (mem_free(a) ? 1 : 0));',
                     '  var v = 1;',
                     '  boolean b = mem_free(v);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-a2-mem-free-var-argument-type', source,
                      error=ADDRESS_ARGUMENT, category='invalid_argument', events=(11,),
                      trace=('main',), lines=(line_of(source, 'boolean b = mem_free(v);'),)))

    # make_free: a var holding void (copied from a fresh raw slot) is refused.
    source = program(PRELUDE,
                     'int main(){',
                     '  address r = alloc(1);',
                     '  var v = mem_get(r);',
                     '  make_free(v);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-a3-make-free-var-argument-type', source,
                      error=ADDRESS_ARGUMENT, category='invalid_argument',
                      trace=('main',), lines=(line_of(source, 'make_free(v);'),)))

    # mem_send_up, correction 14 step 1: a var holding main's address is sent up
    # to baseEnv (the hook's alloc(1) then gets 2: 12, and destroy frees 1); a
    # var holding an int fails the argument check before the ownership check,
    # which it would also fail ("Allocation is not owned by current scope").
    source = program(PRELUDE + SLOT,
                     'void __script_pre_destroy(){ event(10 + slot(alloc(1))); }',
                     'int main(){',
                     '  var a = alloc(1);',
                     '  mem_send_up(a);',
                     '  var v = 5;',
                     '  mem_send_up(v);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-a4-send-up-var-argument-checked-first', source,
                      error=ADDRESS_ARGUMENT, category='invalid_argument',
                      trace=('main',), lines=(line_of(source, 'mem_send_up(v);'),), teardown_events=(12,)))

    # mem_get: a var holding an int is refused.
    source = program(PRELUDE,
                     'int main(){',
                     '  var v = 7;',
                     '  var x = mem_get(v);',
                     '  return 0;',
                     '}')
    result.append(own('own-heap-a5-mem-get-var-argument-type', source,
                      error=ADDRESS_ARGUMENT, category='invalid_argument',
                      trace=('main',), lines=(line_of(source, 'var x = mem_get(v);'),)))
    return result


def cases():
    return delete_cases() + destructor_ownership_cases() + layout_cases() + raw_memory_cases() + argument_type_cases()
