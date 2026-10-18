"""Group L/M: script destroy and buffer finalization (stage "ownership").

Covers cleanup spec section 12 group L (L1-L6: pre-destroy hooks in reverse
initialization order across inserted modules, hook errors, failed onloads,
baseEnv-owned objects destroyed at teardown, manual objects discarded without
destructors, make_free'd allocations surviving destroy), the destroy guard
(cleanup verifier correction 24 and addition 5: one guard covers the hooks, the
baseEnv cleanup and the global finalization, so they share one budget and run at
active_calls 2) and group M (M1-M5: buffer finalization order, shrinking resize
with throwing elements, buffer assignment, the owner ending during an element
callback, reserve).

Expectations are derived from the specification and the interpreter source, not
from observed output:
- flush (interpreter/library.cpp:1890-2052): a module depends on every module it
  assumes a hint of or whose functions it imports; components initialize in
  dependency order, independent ones in load order (primary first, then the
  --insert modules in order). Each module's pre-destroy hook is registered right
  after its onload completed (library.cpp:2045-2049), so a failed onload
  registers neither its own hook nor any later module's. One call_guard covers
  all onloads (library.cpp:2044).
- destroy (library.cpp:2055-2076): one call_guard(baseEnv) resets the budget
  (active_calls 0) and spans the hooks in reverse registration order, each in
  its own try, then baseEnv->cleanup (owned_pointer LIFO; baseEnv has no frame)
  and the global finalization; the first captured error is rethrown after
  discard_script_objects frees every remaining registered object of the script
  without destructors (heepalloc.cpp:438-450). Hooks and baseEnv destructors
  therefore run at active_calls 2 and a host callback made by them at 3
  (call_guard library.cpp:771-780; checked_external_function guards its target,
  library.cpp:799). A destructor started by baseEnv cleanup gets baseEnv as its
  context (same script, blocks.cpp:229-238 / heepalloc.cpp:364-373).
- The observation host (tests/observation_host.cpp observe) records events from
  load onward (so onload events are main-phase events), runs destroy with a
  fresh event list, and checks the heap after destroy. max_steps and
  max_call_depth are fixed before flush and apply to flush, main and destroy.
- Host entry: script::invoke passes baseEnv as the caller and has no guard
  (library.cpp:1768-1773). An automatic pointer returned by such a root is
  transferred to root.caller = baseEnv (heepalloc.cpp:400-425) and destroyed at
  destroy; a literal returned to the host has no destination and is host-held,
  so no destructor ever runs (blocks.cpp:636-654). Callback 4 re-enters through
  script::invoke as well, so objects returned by re-entered roots are also
  baseEnv-owned, appended in return order.
- Raw allocations: alloc is owned by the call-site scope; make_free leaves the
  allocation unowned, so destroy never frees it; mem_send_up from a root whose
  caller is baseEnv hands it to baseEnv, freed at destroy (heepalloc.cpp:477-523).
- Buffers (blocks.cpp:122-460): class elements are view blocks owned by the
  buffer's per-element storage; finalize_into walks the slots from capacity-1
  down (unused slots are void), each view running its destructor. push relocates
  a materialized temporary into the view without a copy or destructor. Shrinking
  resize sets length first, then clears every element last first and rethrows
  the first error. Buffer replacement in store copies the source first, then
  finalizes the old elements, publishes the copy when the target and its
  ancestors survived (keeping the owned_pointer position) and rethrows any
  element error afterwards; otherwise it fails with "Buffer owner ended during
  assignment" and finalizes the copy. length()/capacity() never check the
  active operation. An operation whose buffer was finalized meanwhile defers
  that finalization (pending_finalize) and fails with "Buffer owner ended
  during an active operation" before finishing the deferred finalization.
  reserve moves elements into new views (new ids) with no user code; old view
  addresses expire. Block ids start at 1 in each fresh process and every
  make_block consumes one (blocks.cpp:27-33, 59-69).
Step costs (counted from the -g exec lowering; same inventory as errors.py):
  a block 1, `int i = 0;` 2, a call with one constant/variable argument 2,
  `while (i < N) { i = i + 1; }` 9N + 4, `P a(v)` 30, ~M 11, ~P (probe of a
  field) 9, a wrapped destructor adds its cleanup node 1, `drop` 4.
Prelude lines: 1 event, 2 probe, 3 M, 4 mk, 5 mkp, 6 Bad.
"""
from vm_execution_cases import case, event_list
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000
STEP = 'Script execution step limit exceeded'
DEPTH = 'Script call depth limit exceeded'
# Callback 4 re-enters script::invoke and returns the callee's result; declared
# with an address result so a re-entered root can hand back an object pointer.
REENTER = 'extern address reenter(int,int):6dff0004;\n'
# Script function ids chosen outside the compiler's sequential range.
ID_F01 = 0x0fff0f01   # 268373761
ID_F02 = 0x0fff0f02   # 268373762
ID_F03 = 0x0fff0f03   # 268373763
HOLD = 'M * hold(int v):0f01 { M * p(v); return p; }\n'
HOLD_BAD = 'Bad * holdbad(int v):0f02 { Bad * p(v); return p; }\n'
VOID = typed(5, None)
# A class whose destructor fails only for large values (runtime_error).
Q_CLASS = ('class Q { int n; Q(int v){ n = v; } ~Q(){ event(0 - n); if (n > 100) { int z = 0; int r = 1 / z; } } }\n'
           'Q mq(int v){ Q t(v); return t; }\n')
P_CLASS = 'class P { int n; P(int v){ n = v; } ~P(){ probe(n); } }\n'


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


def block_hex(block_id, offset=0):
    return f'{(1 << 63) | (block_id << 24) | offset:016x}'


def pointer(start):
    return typed(7, heap_address(start))


def ev(value):
    return event_list([value])[0]


def reentry(function_id, value):
    return {'callback': 4, 'arguments': [typed(0, function_id), typed(0, value)]}


def reentry_returned(value):
    return {'reentry_return': value}


def probe(value, active, remaining=None):
    event = {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active}
    if remaining is not None:
        event['remaining_steps'] = remaining
    return event


def raw_range(start, *slots):
    """One heap entry: a raw allocation with the given slot values."""
    return {'address': heap_address(start), 'length': len(slots), 'slots': list(slots)}


def obj(identity, *slots, length=None, capacity=None):
    """Observation form of a literal object value (tests/observation_host.cpp value())."""
    payload = {'id': identity, 'state': 0, 'slots': list(slots)}
    if length is not None:
        payload['length'] = length
        payload['capacity'] = capacity
    return typed(8, payload)


def module(hint, *rows):
    return program(f'#namespace_hint {hint}\n' + EVENT, *rows)


# Inserted library modules (hint libraries keep their own lifecycle hooks).
LIB_A = module('TD_LIB_A',
               'void __script_onload(){ event(2); }',
               'void __script_pre_destroy(){ event(-20); }')
LIB_HOOK_ONLY = module('TD_HOOK_ONLY',
                       'void __script_pre_destroy(){ event(-20); }')
LIB_HOOK_DIV = module('TD_HOOK_DIV',
                      'void __script_pre_destroy(){ event(-20); int z = 0; int q = 1 / z; }')
LIB_HOOK_OVF = module('TD_HOOK_OVF',
                      'void __script_pre_destroy(){ event(-20); int big = 2147483647; int q = big + 1; }')
LIB_ONLOAD_FAILS = module('TD_ONLOAD_FAILS',
                          'void __script_onload(){ event(2); int z = 0; int q = 1 / z; }',
                          'void __script_pre_destroy(){ event(-20); }')
# A depends on B (assumed hint plus an import used by A's onload).
LIB_DEP_A = program('#namespace_hint TD_DEP_A\n#assume_hint TD_DEP_B 4242\n' + EVENT +
                    'extern int bget():42420002;\n',
                    'void __script_onload(){ event(20 + bget()); }',
                    'void __script_pre_destroy(){ event(-2); }')
LIB_DEP_B = module('TD_DEP_B',
                   'int bval = 0;',
                   'int bget():0002{ return bval; }',
                   'void __script_onload(){ bval = 7; event(30); }',
                   'void __script_pre_destroy(){ event(-3); }')
LIB_LV = module('TD_LV',
                'int lv = 0;',
                'int lget():0002{ return lv; }',
                'void __script_onload(){ lv = 5; event(2); }',
                'void __script_pre_destroy(){ event(-20); }')


def hook_cases():
    """L1-L4: hook order, hook errors, failed onloads, depth at destroy."""
    result = []

    # L1 (+ L5, manual objects). Independent modules initialize in load order
    # (primary: 1, then the inserted library: 2), so destroy runs the library's
    # hook first (-20), then the primary's (-10, and g is still alive: 50).
    # baseEnv cleanup then destroys p, which main returned to the host (start 2,
    # after g at 1): -5. g is manual and is discarded with no event; the heap is
    # empty afterwards.
    result.append(own('own-l1a-hooks-reverse-load-order-then-base-objects', program(
        PRELUDE,
        'M * g;',
        'void __script_onload(){ event(1); }',
        'void __script_pre_destroy(){ event(-10); event(g.n); }',
        'M * main(){',
        '  g = new M(50);',
        '  event(3);',
        '  M * p(5);',
        '  return p;',
        '}'), heap_address(2), tag=7, events=(1, 2, 3), globals=[pointer(1)], modules=(LIB_A,),
        teardown_events=(-20, -10, 50, -5)))

    # L1, reverse *initialization* order, not reverse load order. Load order is
    # primary (0), A (1), B (2); A depends on B, so the order is 0, 2, 1 (ready
    # set ties by lowest module index, library.cpp:1983-1991). A's onload reads
    # B's global after B initialized it (20 + 7). Hooks: A, B, primary.
    # Globals: primary none, A none, B's bval.
    result.append(own('own-l1b-hooks-reverse-dependency-order', program(
        EVENT,
        'void __script_onload(){ event(10); }',
        'void __script_pre_destroy(){ event(-1); }',
        'int main(){',
        '  event(40);',
        '  return 0;',
        '}'), 0, events=(10, 30, 27, 40), globals=[typed(0, 7)], modules=(LIB_DEP_A, LIB_DEP_B),
        teardown_events=(-2, -3, -1)))

    # L1/L5 with several baseEnv-owned objects: each re-entered root returns an
    # automatic pointer to baseEnv (starts 2 and 3-4), main returns 5. baseEnv
    # cleanup is LIFO after the hook: -5, then W (its body 104, then its field
    # f: -9), then -3. The manual g (start 1) is discarded silently.
    result.append(own('own-l1c-base-owned-objects-lifo-after-hook', program(
        PRELUDE + REENTER,
        'class W { M f(9); int k; W(int v){ k = v; } ~W(){ event(100 + k); } }',
        'M * g;',
        HOLD + 'W * holdw(int v):0f03 { W * p(v); return p; }',
        'void __script_pre_destroy(){ event(-10); }',
        'M * main(){',
        '  g = new M(50);',
        f'  reenter({ID_F01}, 3);',
        f'  reenter({ID_F03}, 4);',
        '  M * p(5);',
        '  return p;',
        '}'), heap_address(5), tag=7, globals=[pointer(1)],
        raw_events=[reentry(ID_F01, 3), reentry_returned(pointer(2)),
                    reentry(ID_F03, 4), reentry_returned(pointer(3))],
        teardown_events=(-10, -5, 104, -9, -3)))

    # L1 at flush: typed global initializers run in onload's root (declaration
    # order, then the body). ga's automatic object (start 1) belongs to onload's
    # root and dies when onload returns (after the body's event 1); gm (start 2)
    # is manual; gr (start 3) is sent up from the root to baseEnv. Teardown has
    # no hook: baseEnv frees gr, gm is discarded, no events.
    result.append(own('own-l1d-onload-initializer-objects-and-manual-global', program(
        PRELUDE,
        'M * ga = mkp(9);',
        'M * gm = new M(8);',
        'address gr;',
        'void __script_onload(){ gr = alloc(1); mem_send_up(gr); event(1); }',
        'int main(){',
        '  event(gm.n);',
        '  return 0;',
        '}'), 0, events=(1, -9, 8), globals=[pointer(1), pointer(2), pointer(3)], teardown_events=()))

    # L1 ordering inside destroy: objects a hook creates belong to the hook's
    # root (mkp's ro transfers to the call-site scope, after t is bound), so
    # they die LIFO at the hook's exit (-4, -6), before baseEnv's p (-5). t is
    # declared first: were mkp's object handed to baseEnv (the hook's caller)
    # instead, the hook would end only t (-6) and baseEnv would then end the
    # pointer before p (-4, -5).
    result.append(own('own-l1e-hook-objects-die-before-base-objects', program(
        PRELUDE,
        'void __script_pre_destroy(){ M t(6); M * q = mkp(4); event(1); }',
        'M * main(){',
        '  M * p(5);',
        '  return p;',
        '}'), heap_address(1), tag=7, teardown_events=(1, -4, -6, -5)))

    # L2: the first hook to run (the library's, registered last) fails; the
    # primary hook and the baseEnv destructor still run, and the hook's error is
    # destroy's error.
    result.append(own('own-l2a-first-hook-error-later-hook-and-objects-run', program(
        PRELUDE,
        'void __script_pre_destroy(){ event(-10); }',
        'M * main(){',
        '  M * p(5);',
        '  return p;',
        '}'), heap_address(1), tag=7, modules=(LIB_HOOK_DIV,),
        teardown_events=(-20, -10, -5), teardown_error='Division by zero'))

    # L2 first-error rule across teardown: the library hook's overflow comes
    # first; the primary hook's division and the baseEnv Bad's division are
    # discarded (both still ran).
    result.append(own('own-l2b-first-teardown-error-wins', program(
        PRELUDE,
        'void __script_pre_destroy(){ event(-10); int z = 0; int q = 1 / z; }',
        'Bad * main(){',
        '  Bad * p(2);',
        '  return p;',
        '}'), heap_address(1), tag=7, modules=(LIB_HOOK_OVF,),
        teardown_events=(-20, -10, 102), teardown_error='Integer overflow'))

    # L2 for baseEnv cleanup: owned [1 M, 2 Bad, 3 M] is released LIFO; Bad's
    # failure is captured and the remaining object is still destroyed.
    result.append(own('own-l2c-base-destructor-error-rest-still-destroyed', program(
        PRELUDE + REENTER + HOLD + HOLD_BAD,
        'M * main(){',
        f'  reenter({ID_F01}, 1);',
        f'  reenter({ID_F02}, 2);',
        '  M * p(3);',
        '  return p;',
        '}'), heap_address(3), tag=7,
        raw_events=[reentry(ID_F01, 1), reentry_returned(pointer(1)),
                    reentry(ID_F02, 2), reentry_returned(pointer(2))],
        teardown_events=(-3, 102, -1), teardown_error='Division by zero'))

    # L3: load order primary, library; the primary's onload completed (hook
    # registered), the library's fails (its hook is never registered). main
    # never runs (flush error); destroy runs only the primary hook.
    result.append(own('own-l3a-failed-onload-later-module-hook-skipped', program(
        EVENT,
        'void __script_onload(){ event(1); }',
        'void __script_pre_destroy(){ event(-10); }',
        'int main(){',
        '  event(3);',
        '  return 0;',
        '}'), error='Division by zero', category='runtime_error', events=(1, 2),
        modules=(LIB_ONLOAD_FAILS,), trace=('__script_onload',), lines=(3,), teardown_events=(-10,)))

    # L3 with a dependency: the primary assumes the library, so the library
    # initializes first (its hook registered); the primary's onload then fails
    # after reading the library's global (5 + 10); its local a is destroyed
    # while onload unwinds. Only the library's hook runs at destroy.
    source = program(
        '#assume_hint TD_LV 4343\n' + PRELUDE + 'extern int lget():43430002;\n',
        'void __script_onload(){',
        '  M a(7);',
        '  event(lget() + 10);',
        '  int z = 0;',
        '  int q = 1 / z;',
        '}',
        'void __script_pre_destroy(){ event(-10); }',
        'int main(){',
        '  event(3);',
        '  return 0;',
        '}')
    result.append(own('own-l3b-failed-onload-dependency-hook-runs', source,
                      error='Division by zero', category='runtime_error', events=(2, 15, -7),
                      globals=[typed(0, 5)], modules=(LIB_LV,), trace=('__script_onload',),
                      lines=(line_of(source, 'int q = 1 / z;'),), teardown_events=(-20,)))

    # L4 (source-reachable form; the host-only max_call_depth 0 is skipped).
    # With --call-depth 2 main (1) may call alloc, mem_send_up and event (2),
    # but every hook runs at 2 and its event call fails its guard (2 >= 2)
    # before the callback: no teardown events. The second hook still runs and
    # fails the same way (first error kept), and the raw allocation main sent
    # up to baseEnv is still released.
    result.append(own('own-l4-depth-two-every-hook-fails-storage-released', program(
        EVENT,
        'void __script_pre_destroy(){ event(-10); }',
        'int main(){',
        '  address r = alloc(1);',
        '  mem_send_up(r);',
        '  event(3);',
        '  return 0;',
        '}'), 0, events=(3,), options=['--call-depth', '2'], modules=(LIB_HOOK_ONLY,),
        teardown_events=(), teardown_error=DEPTH))
    return result


def host_cases():
    """L5 and L6: results returned to the host and raw allocations at destroy."""
    result = []

    # L5: an automatic pointer returned to the host is owned by baseEnv and
    # destroyed at destroy.
    result.append(own('own-l5a-auto-pointer-returned-to-host-destroyed-at-destroy', program(
        PRELUDE,
        'M * main(){',
        '  M * p(5);',
        '  event(1);',
        '  return p;',
        '}'), heap_address(1), tag=7, events=(1,), teardown_events=(-5,)))

    # L5: a literal returned to the host is host-held: no destructor at main's
    # exit, none at destroy (the host drops it without user code).
    result.append(own('own-l5b-literal-returned-to-host-never-destroyed', program(
        PRELUDE,
        'M main(){',
        '  M t(7);',
        '  event(1);',
        '  return t;',
        '}'), obj(0, typed(0, 7))['value'], tag=8, events=(1,), teardown_events=()))

    # L5 with a buffer of class elements inside the returned literal: neither
    # the container nor its element view is ever destroyed (push relocated the
    # temporary, so no -4 at the statement either).
    result.append(own('own-l5c-literal-with-buffer-returned-to-host-never-destroyed', program(
        PRELUDE,
        'class WB { buffer<M> b; int k; }',
        'WB main(){',
        '  WB w;',
        '  w.b.reserve(1);',
        '  w.b.push(mk(4));',
        '  return w;',
        '}'), obj(0, obj(1, obj(2, typed(0, 4)), length=1, capacity=1), typed(0, 0))['value'], tag=8,
        teardown_events=()))

    # L6: a is freed when main's root closes; b was make_free'd (unowned) and
    # survives destroy; c was sent up to baseEnv and is freed at destroy.
    result.append(own('own-l6a-make-free-survives-destroy', program(
        EVENT,
        'int main(){',
        '  address a = alloc(1);',
        '  address b = alloc(2);',
        '  mem_get(b) = 5;',
        '  make_free(b);',
        '  address c = alloc(1);',
        '  mem_get(c) = 6;',
        '  mem_send_up(c);',
        '  return 0;',
        '}'), 0, teardown_events=(), teardown_heap=[raw_range(2, typed(0, 5), VOID)]))

    # L6 contrast: any scope may mem_free an unowned allocation, including a
    # hook; the second free finds no allocation start and returns false.
    result.append(own('own-l6b-hook-frees-make-free-allocation', program(
        EVENT,
        'address keep;',
        'void __script_pre_destroy(){',
        '  if (mem_free(keep)) { event(1); }',
        '  if (mem_free(keep)) { event(2); }',
        '  event(3);',
        '}',
        'int main(){',
        '  keep = alloc(1);',
        '  make_free(keep);',
        '  return 0;',
        '}'), 0, globals=[pointer(1)], teardown_events=(1, 3)))
    return result


def guard_cases():
    """Cleanup verifier correction 24: one guard and one budget for all of destroy."""
    result = []

    # The hook runs at 2 (its probe at 3); its local t dies at the hook's exit
    # at 3 (probe 4); baseEnv's w runs at 2 (probe 3) and drops its field f at 3
    # (probe 4). The budget is reset once by destroy's guard and shared:
    # hook body 1 + probe call 2 -> FULL-3; P t(2) 30; ~P 9 -> FULL-42;
    # ~PW: cleanup 1 + block 1 + check-this 2 + set-methods 2 + probe call 1 +
    # member k 3 -> FULL-52; drop 4 + ~P 9 -> FULL-65.
    result.append(own('own-l7a-teardown-depth-and-shared-budget', program(
        PRELUDE + P_CLASS,
        'class PW { P f(9); int k; PW(int v){ k = v; } ~PW(){ probe(k); } }',
        'void __script_pre_destroy(){',
        '  probe(1);',
        '  P t(2);',
        '}',
        'PW * main(){',
        '  PW * w(3);',
        '  return w;',
        '}'), heap_address(1), tag=7, partial=True,
        raw_teardown_events=[probe(1, 3, FULL - 3), probe(2, 4, FULL - 42),
                             probe(3, 3, FULL - 52), probe(9, 4, FULL - 65)]))

    # The hook burns all but 21 steps (body 1 + i 2 + 9 * 111108 + 4 =
    # 999979). baseEnv owns [1, 2]: ~M(2) costs 11 and emits -2; ~M(1) is
    # entered with 10 left and fails at its 11th tick, before its event. The
    # heap is still emptied. Not swept: the hook must consume the default
    # budget, which is far above the sweep cap.
    result.append(own('own-l7b-hook-budget-starves-later-base-destructor', program(
        PRELUDE + REENTER + HOLD,
        'void __script_pre_destroy(){',
        '  int i = 0;',
        '  while (i < 111108) { i = i + 1; }',
        '}',
        'M * main(){',
        f'  reenter({ID_F01}, 1);',
        '  M * p(2);',
        '  return p;',
        '}'), heap_address(2), tag=7,
        raw_events=[reentry(ID_F01, 1), reentry_returned(pointer(1))],
        teardown_events=(-2,), teardown_error=STEP, sweep=False))
    return result


def buffer_cases():
    """M1-M5: buffer finalization order."""
    result = []

    # M1: elements are finalized from the last slot down; the unused fourth
    # slot is void. push relocated each temporary (no -n at the statement).
    result.append(own('own-m1a-buffer-elements-last-first', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    buffer<M> b;',
        '    b.reserve(4);',
        '    b.push(mk(1));',
        '    b.push(mk(2));',
        '    b.push(mk(3));',
        '    event(b.length());',
        '  }',
        '  event(9);',
        '  return 0;',
        '}'), 0, events=(3, -3, -2, -1, 9)))

    # M1 for a buffer field: get() returns a copy that dies at its statement
    # end (-2); at exit ~W runs (50), then its dropped field's elements, last
    # first.
    result.append(own('own-m1b-buffer-field-elements-after-container', program(
        PRELUDE,
        'class W { buffer<M> b; int k; ~W(){ event(50); } }',
        'int main(){',
        '  W w;',
        '  w.b.reserve(2);',
        '  w.b.push(mk(1));',
        '  w.b.push(mk(2));',
        '  event(w.b.get(1).n);',
        '  return 0;',
        '}'), 0, events=(2, -2, 50, -2, -1)))

    # M1 by value: the parameter is a copy destroyed at the callee's exit, the
    # caller's buffer at main's exit.
    result.append(own('own-m1c-buffer-parameter-copy-destroyed-at-callee-exit', program(
        PRELUDE,
        'int count(buffer<M> x){ event(x.length()); return x.length(); }',
        'int main(){',
        '  buffer<M> b;',
        '  b.reserve(2);',
        '  b.push(mk(1));',
        '  b.push(mk(2));',
        '  int c = count(b);',
        '  event(c);',
        '  return 0;',
        '}'), 0, events=(2, -2, -1, 2, -2, -1)))

    # M1 + L1: a buffer inside a manual object is discarded with the object at
    # destroy: no element destructor runs.
    result.append(own('own-m1d-buffer-in-manual-object-discarded-at-destroy', program(
        PRELUDE,
        'class H { buffer<M> b; }',
        'H * g;',
        'int main(){',
        '  g = new H();',
        '  g.b.reserve(2);',
        '  g.b.push(mk(1));',
        '  g.b.push(mk(2));',
        '  event(g.b.length());',
        '  return 0;',
        '}'), 0, events=(2,), globals=[pointer(1)], teardown_events=()))

    # M1 + L5: a buffer inside an automatic object returned to the host is
    # finalized at destroy by the synthesized destructor's drop: slot 2 is
    # void, then -2, -1.
    result.append(own('own-m1e-buffer-in-base-owned-object-destroyed-at-destroy', program(
        PRELUDE,
        'class H { buffer<M> b; int k; }',
        'H * main(){',
        '  H * h();',
        '  h.b.reserve(3);',
        '  h.b.push(mk(1));',
        '  h.b.push(mk(2));',
        '  return h;',
        '}'), heap_address(1), tag=7, teardown_events=(-2, -1)))

    # M2: resize(1) clears index 2 then 1; both fail, the first error wins;
    # the remaining element 0 dies when main unwinds.
    source = program(
        PRELUDE + Q_CLASS,
        'int main(){',
        '  buffer<Q> b;',
        '  b.reserve(3);',
        '  b.push(mq(1));',
        '  b.push(mq(102));',
        '  b.push(mq(103));',
        '  b.resize(1);',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-m2a-shrinking-resize-destroys-every-element', source,
                      error='Division by zero', category='runtime_error', events=(-103, -102, -1),
                      trace=('Q::~Q', 'main'),
                      lines=(line_of(source, 'class Q {'), line_of(source, 'b.resize(1);'))))

    # M2: length is already reduced when the element destructors run (they read
    # it through the heap container: 1), and the first error keeps its class
    # (overflow from element 3; element 2's division is discarded). g is manual,
    # so element 0 is discarded at destroy without a destructor.
    result.append(own('own-m2b-shrinking-resize-length-reduced-first-error-type', program(
        PRELUDE,
        'class T2 { int n; T2(int v){ n = v; } ~T2(){ event(100 + n); event(g.b.length()); '
        'if (n == 3) { int big = 2147483647; int q = big + n; } int z = 0; int r = 1 / z; } }',
        'class H2 { buffer<T2> b; }',
        'H2 * g;',
        'T2 mt(int v){ T2 t(v); return t; }',
        'int main(){',
        '  g = new H2();',
        '  g.b.reserve(3);',
        '  g.b.push(mt(1));',
        '  g.b.push(mt(2));',
        '  g.b.push(mt(3));',
        '  g.b.resize(1);',
        '  return 0;',
        '}'), error='Integer overflow', category='overflow_error', events=(103, 1, 102, 1),
        globals=[pointer(1)], teardown_events=()))

    # Spec 5.10 growth rollback (blocks.cpp:428-434): the third placement
    # fails before its destructor is bound (no -3); the elements created by
    # this resize are cleared last first (-2, -1). Trace: the constructor at
    # its division, the placement wrapper at the class header, main at the
    # resize.
    source = program(
        PRELUDE,
        'int made = 0;',
        'class G3 { int n; G3(){ made = made + 1; n = made; if (n == 3) { int z = 0; int r = 1 / z; } } '
        '~G3(){ event(0 - n); } }',
        'int main(){',
        '  buffer<G3> b;',
        '  b.reserve(4);',
        '  b.resize(4);',
        '  event(9);',
        '  return 0;',
        '}')
    g3 = line_of(source, 'class G3 {')
    result.append(own('own-m2c-resize-growth-rollback-last-first', source,
                      error='Division by zero', category='runtime_error', events=(-2, -1),
                      globals=[typed(0, 3)], trace=('G3::G3', '<placement G3>', 'main'),
                      lines=(g3, g3, line_of(source, 'b.resize(4);'))))

    # M3: a = b copies b first, then destroys a's old elements last first
    # (-2, -1); the copy keeps a's cleanup position. set() assigns in place, so
    # only the temporary dies (-6). Exit: b (-5), then a (-6).
    result.append(own('own-m3a-buffer-assignment-old-elements-after-copy', program(
        PRELUDE,
        'int main(){',
        '  buffer<M> a;',
        '  a.reserve(2);',
        '  a.push(mk(1));',
        '  a.push(mk(2));',
        '  buffer<M> b;',
        '  b.reserve(1);',
        '  b.push(mk(5));',
        '  a = b;',
        '  event(a.length());',
        '  a.set(0, mk(6));',
        '  return 0;',
        '}'), 0, events=(-2, -1, 1, -6, -5, -6)))

    # M3: the old element's error is raised after the copy was published: when
    # main unwinds, h (declared last) sees two elements (2) and destroys the
    # copied ones (-3, -2), then src (-3, -2).
    source = program(
        PRELUDE + Q_CLASS,
        'class HQ { buffer<Q> b; ~HQ(){ event(b.length()); } }',
        'int main(){',
        '  buffer<Q> src;',
        '  src.reserve(2);',
        '  src.push(mq(2));',
        '  src.push(mq(3));',
        '  HQ h;',
        '  h.b.reserve(1);',
        '  h.b.push(mq(101));',
        '  h.b = src;',
        '  event(7);',
        '  return 0;',
        '}')
    result.append(own('own-m3b-old-element-error-after-assignment-published', source,
                      error='Division by zero', category='runtime_error', events=(-101, 2, -3, -2, -3, -2),
                      trace=('Q::~Q', 'main'),
                      lines=(line_of(source, 'class Q {'), line_of(source, 'h.b = src;'))))

    # M3: the old element's destructor deletes the container (-50, then ~HZ 99;
    # its drop of b is a no-op on the destroying buffer and voids the slot), so
    # the target no longer holds the old buffer: the copy is destroyed (-7) and
    # the store fails. src dies while main unwinds (-7).
    source = program(
        PRELUDE,
        'class Z { int n; Z(int v){ n = v; } ~Z(){ event(0 - n); if (n == 50) { killz(); } } }',
        'class HZ { buffer<Z> b; ~HZ(){ event(99); } }',
        'HZ * gz;',
        'void killz(){ delete gz; }',
        'Z mz(int v){ Z t(v); return t; }',
        'int main(){',
        '  gz = new HZ();',
        '  gz.b.reserve(1);',
        '  gz.b.push(mz(50));',
        '  buffer<Z> src;',
        '  src.reserve(1);',
        '  src.push(mz(7));',
        '  gz.b = src;',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-m3c-buffer-owner-ended-during-assignment', source,
                      error='Buffer owner ended during assignment', category='runtime_error',
                      events=(-50, 99, -7, -7), globals=[pointer(1)], trace=('main',),
                      lines=(line_of(source, 'gz.b = src;'),)))

    # M4: the second element's constructor deletes the container. ~HE (99)
    # drops b, whose finalization is deferred (active operation). The element
    # completes (2) and gets its destructor bound, then the liveness check
    # fails, the element is rolled back with its destructor (-2), and the
    # deferred finalization destroys element 1 (-1).
    source = program(
        PRELUDE,
        'int made = 0;',
        'class E { int n; E(){ made = made + 1; n = made; if (n == 2) { killg(); } } ~E(){ event(0 - n); } }',
        'class HE { buffer<E> b; ~HE(){ event(99); } }',
        'HE * g;',
        'void killg(){ event(1); delete g; event(2); }',
        'int main(){',
        '  g = new HE();',
        '  g.b.reserve(2);',
        '  g.b.resize(1);',
        '  g.b.resize(2);',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-m4-owner-deleted-during-element-construction', source,
                      error='Buffer owner ended during an active operation', category='runtime_error',
                      events=(1, 99, 2, -2, -1), globals=[typed(0, 2), pointer(1)], trace=('main',),
                      lines=(line_of(source, 'g.b.resize(2);'),)))

    # M5: reserve moves the element into a new view with no constructor (70)
    # or destructor (-7) call; the address saved by the constructor (view id 3:
    # buffer 1, first reserve candidate 2, view 3) expires. The live element
    # dies while main unwinds.
    source = program(
        PRELUDE,
        'class E5 { int n; E5(){ n = 7; event(70); saved = this; } ~E5(){ event(0 - n); } }',
        'E5 * saved;',
        'int main(){',
        '  buffer<E5> b;',
        '  b.reserve(1);',
        '  b.resize(1);',
        '  event(saved.n);',
        '  b.reserve(4);',
        '  event(b.length());',
        '  return saved.n;',
        '}')
    result.append(own('own-m5-reserve-expires-views-without-lifecycle', source,
                      error='Invalid or expired object address: ' + block_decimal(3), category='out_of_range',
                      events=(70, 7, 1, -7), globals=[typed(7, block_hex(3))], trace=('main',),
                      lines=(line_of(source, 'return saved.n;'),)))
    return result


def cases():
    return hook_cases() + host_cases() + guard_cases() + buffer_cases()
