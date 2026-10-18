"""Group G: errors, traces, budget and call depth (stage "ownership").

Covers cleanup spec section 12 groups G (G1-G10 with the cleanup verifier's
position corrections 1, 3 and 4), H (H1-H5, plus H7 through the observation
host's re-entry callback) and I (the max_call_depth table for a delete issued
from a frame at depth a), cleanup verifier additions 5 (flush/destroy guard
level, max_call_depth 1, shared phase budgets), 8 (error classes), 14 (ro fails
before mark_returned), 17 (lifecycle tick inventory, wrapped destructors under
exhaustion, #set-methods with a vtable) and 21 (unknown extern), and probe()
samples taken inside destructors on normal exit and during error unwinding in
the invoke, flush and destroy phases.

Names: every case starts with "own-g", so `--only own-g` selects exactly this
group. own-g<N> are spec G cases and verifier additions, own-gh<N> spec H
(budget), own-gi<N> the spec I table. Spec ids behind the own-g numbers that
are not spec G ids (names are kept stable): own-g9 = verifier addition 21
(spec G9 itself, a module compiled without -g, is not reachable here because
the driver always compiles with -g); own-g11* = addition 5 (guard levels and
max_call_depth); own-g12* = addition 5 phase budgets; own-g13* and own-g8b =
probes during unwinding (spec 2.2); own-g14 = addition 14; own-g15 = addition 5
/ spec 7 flush failure.

Expectations are derived from the specification and the interpreter source, not
from observed output:
- First error: every cleanup site owns a pending_error; the chronologically
  first exception wins with its original dynamic type (library.cpp:183-216,
  826-842). A later failure's frames are discarded at capture (library.cpp:201).
- Trace frames: invoke_bound notes its frame in its catch (library.cpp:2131) at
  frame.location, or at the header when the location is 0 or the frame does not
  exist yet (library.cpp:115-128). at_location does not restore on exceptional
  exit (library.cpp:256-269); blocks and synthesized lifecycle nodes carry no
  position (verify cleanup corrections 1 and 2); after a normal body,
  location = return_location (library.cpp:2128). checked_external_function adds
  a native frame {name '', line 0, function = callee id} for every non-0x0abd id
  on any failure inside its try, including its own guard (library.cpp:791-822);
  builtins (0x0abd) add none. At most 64 frames are kept, the rest counted as
  omitted (library.cpp:108-114). Where a frame's position could share a line
  with its function header, the failing statement is put on its own line.
- Published trace, as sampled by probe(): each failing entry publishes a copy of
  the active frames (library.cpp:79-107); every successful entry clears active
  and published (library.cpp:99-101, 177-182); entering an entry clears only
  active (library.cpp:98). So after a run fails, a pre-destroy hook whose first
  call is probe() still sees the final record (function ids and omitted count
  included). During
  unwinding, the first callback made after a capture still sees the captured
  failure's frames and later ones in the same cleanup see none, until the error
  leaves another frame and is published again with that frame added
  (library.cpp:2131 then 100-107). [AMBIG] cleanup spec 2.2 "Intermediate
  states": whether a host may rely on this mid-run published record is not
  specified; every expectation that samples it during unwinding is marked so.
- Guard (library.cpp:771-780): active_calls >= max_call_depth fails before the
  frame exists; the budget is reset only when active_calls == 0. The host's
  script::invoke has no guard (library.cpp:1768-1773), so main runs at 1 and a
  callback made by main at 2; flush and destroy each hold one outer guard
  (library.cpp:2044, 2063), so onloads, hooks and baseEnv destructors run at 2,
  and all onloads (all hooks plus baseEnv cleanup) share one budget. Modules
  initialize primary first when independent (library.cpp:1983-1991); hooks run
  in reverse initialization order (library.cpp:2064).
- Ticks (library.cpp:448-453): every executed node ticks once on entry, a block
  once in execute_body, a while once on entry and once per body, a cleanup node
  inside its try (library.cpp:860), set_methods once plus once per vtable entry
  (library.cpp:1015-1018); member access is a node, not a call (library.cpp:909-
  917, 1339); drivers never tick; copy_block charges the slot count and sets the
  budget to 0 when it cannot (blocks.cpp:74-79).
Costs used below (counted from the -g exec lowering, Compiler.java:2652-2884):
  M a(v) / P a(v) 30 (vd 1, call 1, constant 1, <value P> 27 with P::P 15),
  ~M 11, ~P (probe of a field) 9, ~Bad 17, `P * q(v)` 29 (<scoped P> 26),
  `while (i < N) { i = i + 1; }` 9N + 4, `int j = 0;` 2, `if (i > 0) { }`
  (true) 5, `int q = 1 / z;` 4, a call with one constant/var arg 2, a
  destructor's #check-this 2 and #set-methods 2 + |vtable|, a #drop 4.
Trace names: methods are "C::C" / "C::~C", factories "<value C>" (header at the
class declaration); a native frame has the empty name.
"""
from vm_execution_cases import case
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000
EVENT_ID = 0x6dff0001
HOSTFAIL_ID = 0x6dff0002
REENTRY_ID = 0x0fff0011

# The prelude's Bad keeps header and division on one line; cases that assert
# Bad's own frame position use this layout instead (same nodes and ticks).
_BAD_ONE_LINE = 'class Bad { int n; Bad(int v){ n = v; } ~Bad(){ event(100+n); int z = 0; int q = 1/z; } }\n'
BAD_ML = ('class Bad { int n; Bad(int v){ n = v; }\n'
          '  ~Bad(){ event(100 + n); int z = 0;\n'
          '    int boom = 1 / z; } }\n')
if PRELUDE.count(_BAD_ONE_LINE) != 1:
    raise AssertionError('conformance PRELUDE no longer defines the one-line Bad')
PRELUDE_ML = PRELUDE.replace(_BAD_ONE_LINE, BAD_ML)
BOOM = 'int boom = 1 / z;'
MKB = 'Bad mkb(int v){ Bad t(v); return t; }\n'
# Each destructor emits its own number, then fails with a different message
# and exception class (B1: runtime_error, B2: overflow_error) on its own line.
B12 = ('class B1 { int n;\n'
       '  ~B1(){ event(1); int z = 0;\n'
       '    int q1 = 1 / z; } }\n'
       'class B2 { int n;\n'
       '  ~B2(){ event(2); int big = 2147483647;\n'
       '    int q2 = big + 1; } }\n')
B1_FAIL, B2_FAIL = 'int q1 = 1 / z;', 'int q2 = big + 1;'
# A destructor that samples call depth, budget and the published trace.
P_CLASS = 'class P { int n; P(int v){ n = v; } ~P(){ probe(n); } }\n'
# Offset of a raw heap address from null (address never converts to int).
OFF = 'int off(address x){ address base = null; int k = 0; while (base + k != x) { k = k + 1; } return k; }\n'
# A hook whose first call samples the still-published final record of the run.
HOOK_PROBE = 'void __script_pre_destroy(){ probe(0); }\n'


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


def plain(value, callback=1):
    return {'callback': callback, 'arguments': [typed(0, value)]}


def frame(name, line, native=False):
    return {'name': name, 'native': native, 'line': line}


def native(function):
    """A native frame as probe() reports it: no name, no position, the callee's id."""
    return {'name': '', 'native': True, 'line': 0, 'function': function}


def final_frames(names, lines, function=EVENT_ID):
    """Expected trace frames in probe() form; every '' entry is a native frame of `function`."""
    if len(names) != len(lines):
        raise AssertionError('frame names and lines differ in length')
    return [native(function) if name == '' else frame(name, line) for name, line in zip(names, lines)]


def probe(value, active, remaining=None, published=None, omitted=0):
    event = {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active}
    if remaining is not None:
        event['remaining_steps'] = remaining
    if published is not None:
        event['published_trace'] = {'frames': list(published), 'omitted': omitted}
    return event


def final_record(names, lines, function=EVENT_ID):
    """HOOK_PROBE's event: destroy's guard resets the budget, the hook's root
    block, probe's call and its constant tick (3); destroy 1, hook 2, probe 3;
    nothing has cleared the published record of the failed run yet."""
    return probe(0, 3, FULL - 3, final_frames(names, lines, function))


def trace_cases():
    """G1-G10 and the position corrections."""
    result = []

    # G1, G10, addition 8: the overflow in main is the first error; Bad's later
    # division is discarded with its frames, and the overflow_error class
    # survives the cleanup. Steps: root 1, b 30, return (r, add, two constants)
    # 4, ~Bad 17 = 52.
    source = program(
        PRELUDE,
        'int main(){',
        '  Bad b(1);',
        '  return 2147483647 + 1;',
        '}')
    result.append(own('own-g1-first-error-class-kept-over-destructor-error', source,
                      error='Integer overflow', category='overflow_error', events=(101,),
                      trace=('main',), lines=(line_of(source, 'return 2147483647 + 1;'),),
                      remaining=FULL - 52))

    # G2: y (declared later) is destroyed first, so its overflow is the first
    # error and x's division is discarded. Root cleanup after a return reports
    # main at the return (spec 2.3). ~B2 is at its overflow, not its header.
    # Steps: root 1, x 23, y 23 (factory 21 with the synthesized ctor's default
    # field init), return 2, ~B2 13, ~B1 13 = 75.
    source = program(
        EVENT + B12,
        'int main(){',
        '  B1 x;',
        '  B2 y;',
        '  return 0;',
        '}')
    result.append(own('own-g2-later-declared-destructor-error-wins', source,
                      error='Integer overflow', category='overflow_error', events=(2, 1),
                      trace=('B2::~B2', 'main'), lines=(line_of(source, B2_FAIL), line_of(source, 'return 0;')),
                      remaining=FULL - 75))

    # G2 without a return: return_location stays 0, so main is at its header.
    source = program(
        EVENT + B12,
        'void main(){',
        '  B1 x;',
        '  B2 y;',
        '}')
    result.append(own('own-g2b-void-main-root-cleanup-error-at-header', source,
                      error='Integer overflow', category='overflow_error', events=(2, 1),
                      trace=('B2::~B2', 'main'),
                      lines=(line_of(source, B2_FAIL), line_of(source, 'void main(){'))))

    # G2 reversed / addition 8: the class follows the chronologically first
    # error, here B1's runtime_error; B2's overflow is discarded.
    source = program(
        EVENT + B12,
        'int main(){',
        '  B2 y;',
        '  B1 x;',
        '  return 0;',
        '}')
    result.append(own('own-g2c-error-class-follows-first-error', source,
                      error='Division by zero', category='runtime_error', events=(1, 2),
                      trace=('B1::~B1', 'main'), lines=(line_of(source, B1_FAIL), line_of(source, 'return 0;'))))

    # G3: a void callee without return reports its header after a root-cleanup
    # failure; main is at its call node. event(5) never runs.
    source = program(
        EVENT + B12,
        'void f(){',
        '  B1 x;',
        '}',
        'int main(){',
        '  f();',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-g3-void-callee-root-cleanup-error-at-header', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'f', 'main'),
                      lines=(line_of(source, B1_FAIL), line_of(source, 'void f(){'), line_of(source, '  f();'))))

    # G3: with a return, the root cleanup failure reports the return.
    source = program(
        EVENT + B12,
        'int g(){',
        '  B1 x;',
        '  return 3;',
        '}',
        'int main(){',
        '  int r = g();',
        '  event(r);',
        '  return 0;',
        '}')
    result.append(own('own-g3b-int-callee-root-cleanup-error-at-return', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'g', 'main'),
                      lines=(line_of(source, B1_FAIL), line_of(source, 'return 3;'),
                             line_of(source, 'int r = g();'))))

    # Correction 1 (innermost positioned node still active): the loop body block
    # has no position, but its cleanup fails while the while statement is still
    # running, so f is reported at the while, not at its header or return.
    source = program(
        EVENT + B12,
        'int f(){',
        '  int i = 0;',
        '  while (i < 3) {',
        '    B1 x;',
        '    i = i + 1;',
        '  }',
        '  return i;',
        '}',
        'int main(){',
        '  int r = f();',
        '  return r;',
        '}')
    result.append(own('own-g3c-loop-body-cleanup-error-at-while', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'f', 'main'),
                      lines=(line_of(source, B1_FAIL), line_of(source, 'while (i < 3) {'),
                             line_of(source, 'int r = f();'))))

    # G4 with correction 1: a block directly in main's body carries no position,
    # so its cleanup failure reports main at its header; event(5) never runs.
    source = program(
        EVENT + B12,
        'int main(){',
        '  {',
        '    B1 x;',
        '  }',
        '  event(5);',
        '  return 0;',
        '}')
    result.append(own('own-g4-inner-block-cleanup-error-at-header', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'main'), lines=(line_of(source, B1_FAIL), line_of(source, 'int main(){'))))

    # Correction 1 continued: return inside an if block marks the frame returned
    # and its own at_location restores; the block's cleanup then fails while the
    # if statement is active (location = return_location only runs after a
    # normal body, library.cpp:2124-2128), so f is reported at the if.
    source = program(
        EVENT + B12,
        'int f(int k){',
        '  if (k == 1) {',
        '    B1 x;',
        '    return 3;',
        '  }',
        '  return 4;',
        '}',
        'int main(){',
        '  int r = f(1);',
        '  return r;',
        '}')
    result.append(own('own-g4b-return-in-if-block-cleanup-error-at-if', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'f', 'main'),
                      lines=(line_of(source, B1_FAIL), line_of(source, 'if (k == 1) {'),
                             line_of(source, 'int r = f(1);'))))

    # Correction 1's own example: a return inside a bare block directly in the
    # function body. The return's at_location restored to 0 (the block has no
    # position) and the block's cleanup fails before location = return_location
    # (library.cpp:2128), so f is at its header, not at the return (contrast
    # g3b: root cleanup after a return; g14: ro fails inside the return).
    source = program(
        EVENT + B12,
        'int f(){',
        '  {',
        '    B1 x;',
        '    return 3;',
        '  }',
        '}',
        'int main(){',
        '  int r = f();',
        '  event(r);',
        '  return r;',
        '}')
    result.append(own('own-g4c-return-in-bare-block-cleanup-error-at-header', source,
                      error='Division by zero', category='runtime_error', events=(1,),
                      trace=('B1::~B1', 'f', 'main'),
                      lines=(line_of(source, B1_FAIL), line_of(source, 'int f(){'),
                             line_of(source, 'int r = f();'))))

    # G5 with correction 4: a non-block if body runs through scoped_execute with
    # its own statement scope (library.cpp:843-849), so the temporary dies at the
    # end of the body and main is reported at the body, not at the if.
    source = program(
        PRELUDE_ML + MKB,
        'int main(){',
        '  int k = 1;',
        '  if (k == 1)',
        '    event(mkb(5).n);',
        '  event(6);',
        '  return 0;',
        '}')
    result.append(own('own-g5-if-body-temporary-error-at-body', source,
                      error='Division by zero', category='runtime_error', events=(5, 105),
                      trace=('Bad::~Bad', 'main'),
                      lines=(line_of(source, BOOM), line_of(source, 'event(mkb(5).n);'))))

    # Correction 4 for a non-block while body: the global gi is assigned 6
    # before the body's statement scope destroys the temporary (the store runs
    # inside the statement, finalize_temporaries after it), so the globals show
    # the completed assignment; main is reported at the body statement.
    source = program(
        PRELUDE_ML + MKB + 'int gi;\n',
        'int main(){',
        '  while (gi < 1)',
        '    gi = gi + mkb(6).n;',
        '  event(gi);',
        '  return 0;',
        '}')
    result.append(own('own-g5b-while-body-temporary-error-at-body', source,
                      error='Division by zero', category='runtime_error', events=(106,),
                      globals=[typed(0, 6)], trace=('Bad::~Bad', 'main'),
                      lines=(line_of(source, BOOM), line_of(source, 'gi = gi + mkb(6).n;'))))

    # Correction 4, first bullet / spec 2.3 row 3: an ordinary statement's
    # temporaries are finalized by execute_body's statement_scope while its
    # at_location(e->location) is still set (library.cpp:2136-2140), so main is
    # at the statement's top node (the declaration's line), not at the call
    # that created the temporary (line 3) or the enclosing call (line 2). The
    # statement itself completed: twice(5) returned and q was declared.
    source = program(
        PRELUDE_ML + MKB + 'int twice(int v){ return v * 2; }\n',
        'int main(){',
        '  int q =',
        '    twice(',
        '      mkb(5).n);',
        '  event(q);',
        '  return 0;',
        '}')
    result.append(own('own-g5d-statement-temporary-error-at-statement-top-line', source,
                      error='Division by zero', category='runtime_error', events=(105,),
                      trace=('Bad::~Bad', 'main'),
                      lines=(line_of(source, BOOM), line_of(source, '  int q ='))))

    # Correction 4, second bullet: if-condition temporaries end inside
    # condition(), which holds at_location(test->location) (library.cpp:2211-
    # 2216), so main is at the condition expression's line, not at the if; the
    # body never runs.
    source = program(
        PRELUDE_ML + MKB,
        'int main(){',
        '  if (',
        '    mkb(5).n == 5) {',
        '    event(1);',
        '  }',
        '  event(2);',
        '  return 0;',
        '}')
    result.append(own('own-g5e-if-condition-temporary-error-at-condition', source,
                      error='Division by zero', category='runtime_error', events=(105,),
                      trace=('Bad::~Bad', 'main'),
                      lines=(line_of(source, BOOM), line_of(source, 'mkb(5).n == 5) {'))))

    # Correction 4, second bullet, while: the first condition's temporary
    # (mkb(5)) fails inside condition() before the first body, so main is at
    # the condition's line, not at the while; i is never incremented.
    source = program(
        PRELUDE_ML + MKB,
        'int main(){',
        '  int i = 0;',
        '  while (',
        '    mkb(i + 5).n < 6) {',
        '    i = i + 1;',
        '  }',
        '  event(i);',
        '  return 0;',
        '}')
    result.append(own('own-g5f-while-condition-temporary-error-at-condition', source,
                      error='Division by zero', category='runtime_error', events=(105,),
                      trace=('Bad::~Bad', 'main'),
                      lines=(line_of(source, BOOM), line_of(source, 'mkb(i + 5).n < 6) {'))))

    # Correction 3 (spec 2.3 "in a callee -> F's call node"): fail() is an
    # argument on its own line; main is reported at fail(, not at the statement
    # (contrast g5d). fail itself is at its division, not its header.
    source = program(
        PRELUDE,
        'int fail(){',
        '  int z = 0;',
        '  return 1 / z;',
        '}',
        'int twice(int v){ return v * 2; }',
        'int main(){',
        '  M a(1);',
        '  int q = twice(',
        '    fail());',
        '  return q;',
        '}')
    result.append(own('own-g5c-callee-error-reports-caller-call-node', source,
                      error='Division by zero', category='runtime_error', events=(-1,),
                      trace=('fail', 'main'),
                      lines=(line_of(source, 'return 1 / z;'), line_of(source, '    fail());'))))

    # G6: a host callback that throws inside a destructor. The callback records
    # its event before throwing; checked_external_function adds the native frame
    # (name '', line 0, function 0x6dff0002), then ~H is reported at the
    # hostfail call. The rest of ~H never runs. The hook's probe pins the final
    # record including the native frame's callee id.
    source = program(
        EVENT + PROBE + 'extern int hostfail(int):6dff0002;\n' + HOOK_PROBE,
        'class H { int n; H(int v){ n = v; }',
        '  ~H(){ event(n);',
        '    hostfail(n);',
        '    event(0 - n); } }',
        'int main(){',
        '  H h(4);',
        '  return 0;',
        '}')
    names = ('', 'H::~H', 'main')
    lines = (0, line_of(source, 'hostfail(n);'), line_of(source, 'return 0;'))
    result.append(own('own-g6-host-failure-in-destructor-native-frame', source,
                      error='differential host failure', category='runtime_error',
                      raw_events=[plain(4), plain(4, callback=2)], trace=names, lines=lines,
                      raw_teardown_events=[final_record(names, lines, HOSTFAIL_ID)], partial=True))

    # G7: a depth failure at event() inside a destructor still adds the native
    # frame for 0x6dff0001. --call-depth 3 lets main construct a (factory 2,
    # N::N 3). f runs at 2, its parameter copy's ~N at 3, and event's guard fails
    # (3 >= 3), so x (n = 2) emits nothing. main's own a is destroyed at 2 and
    # its event at 3 succeeds (-1). f has no return, so it is at its header.
    # The hook runs at 2 and its probe at 3, inside the limit.
    source = program(
        EVENT + PROBE + HOOK_PROBE,
        'class N { int n; N(int v){ n = v; }',
        '  ~N(){',
        '    event(0 - n); } }',
        'void f(N x){',
        '  x.n = x.n + 1;',
        '}',
        'int main(){',
        '  N a(1);',
        '  f(a);',
        '  return 0;',
        '}')
    names = ('', 'N::~N', 'f', 'main')
    lines = (0, line_of(source, 'event(0 - n);'), line_of(source, 'void f(N x){'), line_of(source, '  f(a);'))
    result.append(own('own-g7-depth-failure-at-event-in-destructor-native-frame', source,
                      error='Script call depth limit exceeded', category='runtime_error', events=(-1,),
                      options=['--call-depth', '3'], trace=names, lines=lines,
                      raw_teardown_events=[final_record(names, lines)], partial=True))

    # G8: rec(64) fails 65 rec frames deep (depth 66, so --call-depth 70). Only
    # 64 frames are kept: rec(0) at the division and rec(1..63) at the recursive
    # call; rec(64) and main are counted as omitted (2), which the hook's probe
    # reads from the final record (trace=/lines= cover only the kept frames).
    source = program(
        PROBE + HOOK_PROBE,
        'int rec(int k){',
        '  if (k == 0) { return 1 / k; }',
        '  return rec(k - 1) + 1;',
        '}',
        'int main(){',
        '  int q = rec(64);',
        '  return q;',
        '}')
    div_line = line_of(source, 'return 1 / k;')
    rec_line = line_of(source, 'return rec(k - 1) + 1;')
    rec_frames = [frame('rec', div_line)] + [frame('rec', rec_line)] * 63
    result.append(own('own-g8-trace-cap-64-frames-and-omitted-count', source,
                      error='Division by zero', category='runtime_error', options=['--call-depth', '70'],
                      trace=('rec',) * 64, lines=(div_line,) + (rec_line,) * 63,
                      raw_teardown_events=[probe(0, 3, FULL - 3, rec_frames, omitted=2)], partial=True))

    # G8 probe part [AMBIG] cleanup spec 2.2 intermediate states: ~P runs in
    # main's root cleanup (active_calls 3 inside probe) before main's frame is
    # noted, so the published trace it samples has the 64 rec frames and
    # omitted 1 (rec(64)). The final trace is the same capped 64 frames.
    source = program(
        PROBE + P_CLASS,
        'int rec(int k){',
        '  if (k == 0) { return 1 / k; }',
        '  return rec(k - 1) + 1;',
        '}',
        'int main(){',
        '  P a(7);',
        '  int q = rec(64);',
        '  return q;',
        '}')
    div_line = line_of(source, 'return 1 / k;')
    rec_line = line_of(source, 'return rec(k - 1) + 1;')
    rec_frames = [frame('rec', div_line)] + [frame('rec', rec_line)] * 63
    result.append(own('own-g8b-probe-in-cleanup-sees-64-published-frames', source,
                      error='Division by zero', category='runtime_error', options=['--call-depth', '70'],
                      raw_events=[probe(7, 3, published=rec_frames, omitted=1)], partial=True,
                      trace=('rec',) * 64, lines=(div_line,) + (rec_line,) * 63))

    # Addition 21: an extern the host does not provide fails in getFunction
    # before a checked_external_function exists, so no native frame is added;
    # the destructor frame is at the call (its own line, not the header).
    source = program(
        PRELUDE + 'extern int nothere(int):6dff0009;\n',
        'class U { int n;',
        '  ~U(){',
        '    nothere(n); } }',
        'int main(){',
        '  U u;',
        '  return 0;',
        '}')
    result.append(own('own-g9-unknown-extern-in-destructor-no-native-frame', source,
                      error='Unknown function id: 0x6dff0009', category='runtime_error',
                      trace=('U::~U', 'main'),
                      lines=(line_of(source, 'nothere(n);'), line_of(source, 'return 0;'))))

    # Addition 8, out_of_range: q borrows a's address; after a's block exits the
    # member access fails resolving the expired block id 1 (blocks.cpp:507-513).
    # Bad's later division (runtime_error) does not replace it.
    source = program(
        PRELUDE,
        'class T { int n; T(int v){ n = v; } T * self(){ return this; } ~T(){ event(0 - n); } }',
        'int main(){',
        '  T * q = null;',
        '  {',
        '    T a(1);',
        '    q = a.self();',
        '  }',
        '  Bad b(2);',
        '  int k = q.n;',
        '  return k;',
        '}')
    expired = (1 << 63) | (1 << 24)
    result.append(own('own-g10-out-of-range-class-kept-over-destructor-error', source,
                      error=f'Invalid or expired object address: {expired}', category='out_of_range',
                      events=(-1, 102), trace=('main',), lines=(line_of(source, 'int k = q.n;'),)))

    # Addition 8, invalid_argument from a builtin (heepalloc.cpp:184): builtins
    # add no frame, and Bad's division does not replace the class.
    source = program(
        PRELUDE,
        'int main(){',
        '  Bad b(1);',
        '  int n = 0 - 1;',
        '  address p = alloc(n);',
        '  return 0;',
        '}')
    result.append(own('own-g10b-invalid-argument-class-kept-over-destructor-error', source,
                      error='Invalid allocation size', category='invalid_argument', events=(101,),
                      trace=('main',), lines=(line_of(source, 'address p = alloc(n);'),)))

    # Addition 14: ro fails (heepalloc.cpp:405) before mark_returned, inside the
    # return node, so f keeps the return's position even though the enclosing
    # block's cleanup then runs Bad's failing destructor (compare g4c, where a
    # completed return inside a bare block reports the header).
    source = program(
        PRELUDE,
        'M * f(){',
        '  {',
        '    Bad b(1);',
        '    M * p = new M(2);',
        '    delete p;',
        '    return p;',
        '  }',
        '}',
        'int main(){',
        '  M * q = f();',
        '  return 0;',
        '}')
    result.append(own('own-g14-ro-failure-before-mark-returned', source,
                      error='Object return requires a live allocation', category='runtime_error',
                      events=(-2, 101), trace=('f', 'main'),
                      lines=(line_of(source, '    return p;'), line_of(source, 'M * q = f();'))))

    # Verifier addition 5 / spec 7 flush failure (library.cpp:2042-2052): a
    # destructor failure while onload's root scope closes is a flush error
    # (phase flush; onload has no return, so it is at its header). The failed
    # onload never registers the hook (library.cpp:2045-2049) and main never
    # runs, so teardown emits nothing.
    source = program(
        PRELUDE_ML,
        'void __script_onload(){',
        '  Bad b(1);',
        '}',
        'void __script_pre_destroy(){ event(9); }',
        'int main(){ event(3); return 0; }')
    result.append(own('own-g15-onload-destructor-failure-is-flush-error', source,
                      error='Division by zero', category='runtime_error', events=(101,),
                      trace=('Bad::~Bad', '__script_onload'),
                      lines=(line_of(source, BOOM), line_of(source, 'void __script_onload(){')), teardown_events=()))
    return result


def depth_cases():
    """Verifier addition 5 (guard levels at flush/destroy, max_call_depth 1) and
    the cleanup spec I table."""
    result = []
    depth = 'Script call depth limit exceeded'

    # Verifier addition 5, max_call_depth 1: main runs at 1 (script::invoke has
    # no guard), so its first callback fails at the guard (1 >= 1) with the
    # native frame. (A hook probe cannot pin the frame's callee id here: the
    # hook itself would fail at entry; own-g7 and own-gi2 pin it.)
    source = program(
        EVENT,
        'int main(){',
        '  event(1);',
        '  return 0;',
        '}')
    result.append(own('own-g11-depth-one-main-cannot-call', source,
                      error=depth, category='runtime_error',
                      options=['--call-depth', '1'], trace=('', 'main'),
                      lines=(0, line_of(source, 'event(1);'))))

    # Verifier addition 5, max_call_depth 1: destroy passes its own guard
    # (0 >= 1 is false) and the hook fails at entry (1 >= 1); nothing is
    # emitted and the depth error is destroy's error. No typed globals, so no
    # onload and flush succeeds.
    # spec correction: addition 5 also lists "every baseEnv destructor fails at
    # entry" for max_call_depth 1. That part is not reachable from source:
    # baseEnv only owns objects that main (or a hook) created through factory
    # and builtin calls, and with max_call_depth 1 every call made by main fails
    # its guard (active_calls 1 >= 1, interpreter/library.cpp:774), while
    # max_call_depth is fixed before flush (tests/observation_host.cpp observe).
    # It needs a host that lowers the limit before destroy (cleanup spec L4).
    # own-g11g shows the baseEnv destructor level at a reachable limit.
    source = program(
        EVENT,
        'void __script_pre_destroy(){ event(7); }',
        'int main(){',
        '  return 4;',
        '}')
    result.append(own('own-g11b-depth-one-hook-fails-at-entry', source, 4,
                      options=['--call-depth', '1'], teardown_events=(),
                      teardown_error=depth))

    # Verifier addition 5: flush holds one guard, so onload runs at 2 and its
    # event at 3 fails with --call-depth 2 (own-g11d shows that a callback made
    # by main, at 2, passes the same limit). The failed onload never registers
    # the pre-destroy hook (library.cpp:2045-2049), so teardown emits nothing.
    source = program(
        EVENT,
        'void __script_onload(){',
        '  event(1);',
        '}',
        'void __script_pre_destroy(){ event(2); }',
        'int main(){',
        '  event(3);',
        '  return 0;',
        '}')
    result.append(own('own-g11c-onload-runs-one-level-deeper', source,
                      error=depth, category='runtime_error',
                      options=['--call-depth', '2'], trace=('', '__script_onload'),
                      lines=(0, line_of(source, '  event(1);')), teardown_events=()))

    # Verifier addition 5: destroy holds one guard, so the hook's event runs at
    # 3 and fails with --call-depth 2, while main's event at 2 succeeds.
    source = program(
        EVENT,
        'void __script_pre_destroy(){',
        '  event(2);',
        '}',
        'int main(){',
        '  event(3);',
        '  return 0;',
        '}')
    result.append(own('own-g11d-hook-runs-one-level-deeper', source, 0, events=(3,),
                      options=['--call-depth', '2'], teardown_events=(),
                      teardown_error=depth))

    # Verifier addition 5, positive control at --call-depth 3: onload's and the
    # hook's events run at 3.
    source = program(
        EVENT,
        'void __script_onload(){ event(1); }',
        'void __script_pre_destroy(){ event(2); }',
        'int main(){',
        '  event(3);',
        '  return 0;',
        '}')
    result.append(own('own-g11e-depth-three-lifecycle-hooks-succeed', source, 0, events=(1, 3),
                      options=['--call-depth', '3'], teardown_events=(2,)))

    # Addition 5 / spec 3 / ownership 6.5: a builtin (0x0abd) call takes a depth
    # level through checked_external_function's guard (library.cpp:799) but
    # adds no native frame (library.cpp:816-818). At --call-depth 1 main's
    # alloc fails 1 >= 1 and the trace is main alone, at the alloc statement.
    source = program(
        '',
        'int main(){',
        '  address p = alloc(1);',
        '  return 0;',
        '}')
    result.append(own('own-g11f-depth-one-builtin-fails-without-native-frame', source,
                      error=depth, category='runtime_error', options=['--call-depth', '1'],
                      trace=('main',), lines=(line_of(source, 'address p = alloc(1);'),)))

    # Addition 5 / verifier correction 24 through behaviour: baseEnv destructors
    # run under destroy's guard. h(v) calls event, so from main h runs at 2 and
    # event at 3 (passes --call-depth 3). R * p(5) is built at 2 (factory) and 3
    # (R::R). At destroy, ~R runs at 2, its h at 3 and event's guard fails
    # (3 >= 3): no teardown event, the depth error is destroy's error, and the
    # storage is still released (teardown heap empty). Without destroy's guard
    # ~R would run at 1 and its event at 3 would pass.
    def base_depth(name, limit, teardown):
        source = program(
            EVENT,
            'void h(int v){',
            '  event(v);',
            '}',
            'class R { int n; R(int v){ n = v; }',
            '  ~R(){',
            '    h(n); } }',
            'R * main(){',
            '  h(1);',
            '  R * p(5);',
            '  return p;',
            '}')
        return own(name, source, '0000000000000001', tag=7, events=(1,), options=['--call-depth', str(limit)],
                   **teardown)
    result.append(base_depth('own-g11g-base-env-destructor-call-fails-at-depth-three', 3,
                             {'teardown_events': (), 'teardown_error': depth}))
    # Positive control: one more level lets ~R's h emit 5 at destroy.
    result.append(base_depth('own-g11h-base-env-destructor-call-succeeds-at-depth-four', 4,
                             {'teardown_events': (5,)}))

    # Cleanup spec I: a manual C : B (fields F bf, G cg) is constructed from main
    # (deepest construction call F::F at 6) and deleted by del(p, 0), which runs
    # at a = 8. ~C runs at a+1, ~G (drop) and ~B (base call) at a+2, ~F (drop
    # in ~B) at a+3, and each event one level deeper. Destructor bodies sit on
    # their own lines, so a header differs from the event line. The hook (at 2,
    # its calls at 3, inside every limit used here) first reads the final
    # record, then allocates one slot: first fit 1 shows that the deleted C
    # (two slots at 1) was freed even when its destructors failed.
    chain = program(
        EVENT + PROBE + OFF,
        'void __script_pre_destroy(){ probe(0); address x = alloc(1); event(off(x)); }',
        'class F { int k; ~F(){',
        '  event(-1); } }',
        'class B { F bf;',
        '  ~B(){',
        '    event(-2); } }',
        'class G { int k; ~G(){',
        '  event(-3); } }',
        'class C : B { G cg;',
        '  ~C(){',
        '    event(-4); } }',
        'void del(C * p, int k){',
        '  if (k == 0) {',
        '    delete p;',
        '  } else {',
        '    del(p, k - 1);',
        '  }',
        '}',
        'int main(){',
        '  C * p = new C();',
        '  del(p, 6);',
        '  return 0;',
        '}')
    f_event, b_header, g_event = (line_of(chain, text) for text in ('event(-1);', '~B(){', 'event(-3);'))
    c_header, c_event = line_of(chain, '~C(){'), line_of(chain, 'event(-4);')
    callers = (line_of(chain, 'delete p;'),) + (line_of(chain, 'del(p, k - 1);'),) * 6 + \
        (line_of(chain, 'del(p, 6);'),)
    dels = ('del',) * 7 + ('main',)

    def chain_case(name, source, limit, names=(), lines=(), events=()):
        failure = {'error': depth, 'category': 'runtime_error', 'trace': names, 'lines': lines} if names else {}
        record = final_record(names, lines) if names else probe(0, 3, FULL - 3, [])
        return own(name, source, None if names else 0, options=['--call-depth', str(limit)], events=events,
                   raw_teardown_events=[record, plain(1)], partial=True, **failure)

    # max = a: ~C fails at entry (header); the native field sweep's ~G and ~F
    # also fail at entry. Nothing is emitted and the object is still freed.
    # spec correction: the cleanup spec I table's first row reads "<= a". Only
    # max == a is reachable: the deleting frame itself runs at active_calls a,
    # so with max < a its own call already fails its guard
    # (interpreter/library.cpp:774) and no delete happens.
    result.append(chain_case('own-gi1-delete-depth-a-destructor-fails-at-entry', chain, 8,
                             ('C::~C',) + dels, (c_header,) + callers))
    # max = a+1: ~C's own event fails; drop(cg) and the base call fail at entry,
    # and the native sweep's ~F (entered at a+1) fails at its event.
    result.append(chain_case('own-gi2-delete-depth-a1-event-in-destructor-fails', chain, 9,
                             ('', 'C::~C') + dels, (0, c_event) + callers))
    # max = a+2: -4; ~G's event is the first error (later: ~B's event, ~F at
    # entry). The failure in ~C's final reports ~C at its header (correction 2).
    result.append(chain_case('own-gi3-delete-depth-a2-field-destructor-event-fails', chain, 10,
                             ('', 'G::~G', 'C::~C') + dels, (0, g_event, c_header) + callers, events=(-4,)))
    # max = a+3: -4, -3, -2; ~F's event fails inside ~B's drop, so ~B and ~C are
    # both at their headers.
    result.append(chain_case('own-gi4-delete-depth-a3-base-field-destructor-event-fails', chain, 11,
                             ('', 'F::~F', 'B::~B', 'C::~C') + dels, (0, f_event, b_header, c_header) + callers,
                             events=(-4, -3, -2)))
    # max = a+4: the whole chain runs; main succeeded, so nothing is published.
    result.append(chain_case('own-gi5-delete-depth-a4-chain-completes', chain, 12, events=(-4, -3, -2, -1)))

    # Addition 25 (depth versus scope): nested blocks around the delete do not
    # change active_calls, so a+1 behaves exactly like own-gi2.
    nested = chain.replace('    delete p;\n', '    { { delete p; } }\n')
    result.append(chain_case('own-gi6-delete-in-nested-blocks-depth-is-not-scope', nested, 9,
                             ('', 'C::~C') + dels, (0, c_event, line_of(nested, '{ { delete p; } }')) + callers[1:]))
    return result


def probe_cases():
    """probe() inside destructors: active_calls from the guard rules and the
    published trace on normal exit and during error unwinding."""
    result = []
    empty = []

    # Probe on normal exit (addition 5 guard levels). onload at 2 (flush guard),
    # main at 1, a's destructor at 2, the hook and the baseEnv destructor of q
    # (returned to the host, so owned by baseEnv) at 2; each probe adds one.
    # Budgets: flush and main and destroy each reset it (active_calls 0). main:
    # root 1, probe 2, a 30, q 29, return 2 = 64, then ~P 9. destroy: hook 3,
    # then ~P 9.
    source = program(
        PROBE + P_CLASS,
        'void __script_onload(){ probe(1); }',
        'void __script_pre_destroy(){ probe(4); }',
        'P * main(){',
        '  probe(2);',
        '  P a(3);',
        '  P * q(5);',
        '  return q;',
        '}')
    result.append(own('own-g12-probe-active-calls-flush-main-destroy', source, '0000000000000001', tag=7,
                      raw_events=[probe(1, 3, FULL - 3, empty), probe(2, 2, FULL - 3, empty),
                                  probe(3, 3, FULL - 73, empty)], partial=True, remaining=FULL - 73,
                      raw_teardown_events=[probe(4, 3, FULL - 3, empty), probe(5, 3, FULL - 12, empty)]))

    # Addition 5 / verifier correction 24, two modules: independent modules
    # initialize primary first (ready set by lowest module index,
    # library.cpp:1983-1991) under one flush guard, so the library's onload
    # continues the primary's budget (3 ticks each: FULL-3, then FULL-6). main
    # resets it (FULL-3, at 2). Hooks run in reverse initialization order
    # (library's 20, then the primary's 10) under one destroy guard and share
    # its budget the same way; all lifecycle probes are at 3. main's remaining:
    # root 1, probe 2, return 2.
    library = ('#namespace_hint GE_BUDGET\n' + PROBE +
               'void __script_onload(){ probe(2); }\n'
               'void __script_pre_destroy(){ probe(20); }\n')
    source = program(
        PROBE,
        'void __script_onload(){ probe(1); }',
        'void __script_pre_destroy(){ probe(10); }',
        'int main(){',
        '  probe(3);',
        '  return 0;',
        '}')
    result.append(own('own-g12b-modules-share-flush-and-destroy-budgets', source, 0, modules=(library,),
                      raw_events=[probe(1, 3, FULL - 3, empty), probe(2, 3, FULL - 6, empty),
                                  probe(3, 2, FULL - 3, empty)], partial=True, remaining=FULL - 5,
                      raw_teardown_events=[probe(20, 3, FULL - 3, empty), probe(10, 3, FULL - 6, empty)]))

    # Probe during unwinding (spec 2.2 intermediate states), main's own node: no
    # entry failed, so nothing is published; destructors still run at 2 (probe
    # 3). Steps: root 1, a 30, b 30, z 2, q 4 = 67, then ~P(b) 9 and ~P(a) 9.
    source = program(
        PROBE + P_CLASS,
        'int main(){',
        '  P a(1);',
        '  P b(2);',
        '  int z = 0;',
        '  int q = 1 / z;',
        '  return q;',
        '}')
    result.append(own('own-g13-probe-unwind-own-node-error-publishes-nothing', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(2, 3, FULL - 76, empty), probe(1, 3, FULL - 85, empty)],
                      partial=True, remaining=FULL - 85, trace=('main',),
                      lines=(line_of(source, 'int q = 1 / z;'),)))

    # [AMBIG] cleanup spec 2.2 intermediate states. Callee error: fail's frame
    # was published when it left fail's entries; the first callback in the
    # first destructor still sees it, and its successful return clears the
    # published record, so every later probe sees nothing.
    source = program(
        PROBE,
        'class P2 { int n; P2(int v){ n = v; } ~P2(){ probe(n); probe(n + 10); } }',
        'int fail(int v){',
        '  int z = 0;',
        '  return v / z;',
        '}',
        'int main(){',
        '  P2 a(1);',
        '  P2 b(2);',
        '  int q = fail(7);',
        '  return q;',
        '}')
    fail_frame = frame('fail', line_of(source, 'return v / z;'))
    result.append(own('own-g13b-probe-unwind-callee-trace-published-until-first-success', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(2, 3, published=[fail_frame]), probe(12, 3, published=empty),
                                  probe(1, 3, published=empty), probe(11, 3, published=empty)],
                      partial=True, trace=('fail', 'main'),
                      lines=(fail_frame['line'], line_of(source, 'int q = fail(7);'))))

    # [AMBIG] cleanup spec 2.2 (with the spec 2.1 first-error rule): a discarded
    # destructor error is still published while cleanup goes on. main's
    # division is first, b's ~Bad then fails (its frame published through its
    # entries), and p's probe samples [Bad::~Bad at its division]. The final
    # trace is main's.
    source = program(
        PRELUDE_ML + P_CLASS,
        'int main(){',
        '  P p(1);',
        '  Bad b(2);',
        '  int z = 0;',
        '  int q = 1 / z;',
        '  return q;',
        '}')
    result.append(own('own-g13c-probe-sees-discarded-destructor-trace', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[plain(102), probe(1, 3, published=[frame('Bad::~Bad', line_of(source, BOOM))])],
                      partial=True, trace=('main',), lines=(line_of(source, 'int q = 1 / z;'),)))

    # [AMBIG] cleanup spec 2.2, G6 variant: the host failure inside ~H is
    # discarded, but the following destructor's probe samples its published
    # [native 0x6dff0002, H::~H].
    source = program(
        PRELUDE + 'extern int hostfail(int):6dff0002;\n' + P_CLASS,
        'class H { int n; H(int v){ n = v; }',
        '  ~H(){ event(n);',
        '    hostfail(n); } }',
        'int main(){',
        '  P p(1);',
        '  H h(4);',
        '  int z = 0;',
        '  int q = 1 / z;',
        '  return q;',
        '}')
    result.append(own('own-g13d-probe-sees-discarded-host-failure-trace', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[plain(4), plain(4, callback=2),
                                  probe(1, 3, published=[native(HOSTFAIL_ID),
                                                         frame('H::~H', line_of(source, 'hostfail(n); } }'))])],
                      partial=True, trace=('main',), lines=(line_of(source, 'int q = 1 / z;'),)))

    # D1 chain during unwinding (ownership 6.5 depths): ~QC at 2 (probe 3), ~QG
    # dropped and ~QB called from ~QC at 3 (probe 4), ~QF dropped from ~QB at 4
    # (probe 5). [AMBIG] cleanup spec 2.2: only the first callback sees fail's
    # published frame.
    source = program(
        PROBE,
        'class QF { int k; ~QF(){ probe(1); } }',
        'class QB { QF bf; ~QB(){ probe(2); } }',
        'class QG { int k; ~QG(){ probe(3); } }',
        'class QC : QB { QG cg; ~QC(){ probe(4); } }',
        'int fail(){',
        '  int z = 0;',
        '  return 1 / z;',
        '}',
        'int main(){',
        '  QC c;',
        '  int q = fail();',
        '  return q;',
        '}')
    fail_frame = frame('fail', line_of(source, 'return 1 / z;'))
    result.append(own('own-g13e-probe-chain-depths-during-unwinding', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(4, 3, published=[fail_frame]), probe(3, 4, published=empty),
                                  probe(2, 4, published=empty), probe(1, 5, published=empty)],
                      partial=True, trace=('fail', 'main'),
                      lines=(fail_frame['line'], line_of(source, 'int q = fail();'))))

    # Statement temporary during unwinding (library.cpp:826-834): mkP(5) is
    # exported into main's temporaries, fail() throws, and finalize_temporaries
    # runs ~P at 2 (probe 3); [AMBIG] cleanup spec 2.2: fail's frame is still
    # published.
    source = program(
        PROBE + P_CLASS,
        'P mkP(int v){ P t(v); return t; }',
        'int fail(){',
        '  int z = 0;',
        '  return 1 / z;',
        '}',
        'int main(){',
        '  int q = mkP(5).n + fail();',
        '  return q;',
        '}')
    fail_frame = frame('fail', line_of(source, 'return 1 / z;'))
    result.append(own('own-g13f-probe-statement-temporary-during-unwinding', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(5, 3, published=[fail_frame])], partial=True,
                      trace=('fail', 'main'),
                      lines=(fail_frame['line'], line_of(source, 'int q = mkP(5).n + fail();'))))

    # Unwinding across two frames: f's root cleanup runs ~P(1) at 3 (probe 4)
    # before f's frame exists in any record (nothing published); after f leaves
    # its entries, main's cleanup runs ~P(2) at 2 (probe 3) and, [AMBIG]
    # cleanup spec 2.2, samples [f].
    source = program(
        PROBE + P_CLASS,
        'int f(){',
        '  P a(1);',
        '  int z = 0;',
        '  return 1 / z;',
        '}',
        'int main(){',
        '  P b(2);',
        '  int q = f();',
        '  return q;',
        '}')
    f_frame = frame('f', line_of(source, 'return 1 / z;'))
    result.append(own('own-g13g-probe-callee-cleanup-then-caller-cleanup', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(1, 4, published=empty), probe(2, 3, published=[f_frame])],
                      partial=True, trace=('f', 'main'),
                      lines=(f_frame['line'], line_of(source, 'int q = f();'))))

    # [AMBIG] cleanup spec 2.2, levels of one frame: the inner block's cleanup
    # runs ~P(2) at 2 (probe 3), which samples [fail] and clears the record by
    # returning; the block's catch then raises the first error again
    # (library.cpp:2131), which publishes [fail] once more, so the root's ~P(1)
    # samples it too.
    source = program(
        PROBE + P_CLASS,
        'int fail(){',
        '  int z = 0;',
        '  return 1 / z;',
        '}',
        'int main(){',
        '  P a(1);',
        '  {',
        '    P b(2);',
        '    int q = fail();',
        '  }',
        '  return 0;',
        '}')
    fail_frame = frame('fail', line_of(source, 'return 1 / z;'))
    result.append(own('own-g13k-probe-each-scope-level-sees-republished-error', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(2, 3, published=[fail_frame]), probe(1, 3, published=[fail_frame])],
                      partial=True, trace=('fail', 'main'),
                      lines=(fail_frame['line'], line_of(source, 'int q = fail();'))))

    # Depth-limit unwinding (ownership 6.5) at decreasing depth (--call-depth
    # 6): rec(k) runs at k+2 and constructs a(k) through <value P> (k+3) and
    # P::P (k+4); in rec(3) P::P's guard fails before its frame exists (P::P at
    # its header, the constructor's line; the factory at its header, the class
    # line). rec(2)'s a dies at 5 (probe 6). [AMBIG] cleanup spec 2.2: it sees
    # [P::P, <value P>, rec(3)]; that probe's success clears the record, but the
    # error then leaves rec(2): its catch notes rec(2) at the recursive call
    # (library.cpp:2131) and entry_scope::run publishes the grown copy again
    # (library.cpp:100-107) before rec(1)'s statement scope captures it. So each
    # lower level's probe (at 5, then 4) sees one more rec frame; only probes
    # within one cleanup see the clearing (own-g13b).
    source = program(
        PROBE,
        'class P { int n;',
        '  P(int v){ n = v; }',
        '  ~P(){ probe(n); } }',
        'int rec(int k){',
        '  P a(k);',
        '  return rec(k + 1);',
        '}',
        'int main(){',
        '  int r = rec(0);',
        '  return r;',
        '}')
    class_line = line_of(source, 'class P {')
    ctor_line = line_of(source, 'P(int v){')
    a_line = line_of(source, 'P a(k);')
    ret_line = line_of(source, 'return rec(k + 1);')
    rec3 = [frame('P::P', ctor_line), frame('<value P>', class_line), frame('rec', a_line)]
    result.append(own('own-g13h-probe-depth-limit-unwinding-decreasing-depth', source,
                      error='Script call depth limit exceeded', category='runtime_error',
                      options=['--call-depth', '6'],
                      raw_events=[probe(2, 6, published=rec3), probe(1, 5, published=rec3 + [frame('rec', ret_line)]),
                                  probe(0, 4, published=rec3 + [frame('rec', ret_line)] * 2)],
                      partial=True, trace=('P::P', '<value P>', 'rec', 'rec', 'rec', 'rec', 'main'),
                      lines=(ctor_line, class_line, a_line, ret_line, ret_line, ret_line,
                             line_of(source, 'int r = rec(0);'))))

    # Destroy-phase unwinding (library.cpp:2063-2069, verifier correction 24):
    # the hook fails at its own division; destroy captures it and still runs
    # baseEnv cleanup under the same guard, so q's ~P runs at 2 (probe 3) and
    # shares the hook's budget: destroy resets FULL, hook root 1 + z 2 +
    # division 4 = 7, ~P 9 -> FULL-16. The hook's error is destroy's error and
    # the storage is released. [AMBIG] cleanup spec 2.2: the hook's frame was
    # published when it left its entries (copies, entries > 1), and nothing
    # succeeded since, so the probe samples [__script_pre_destroy at the
    # division].
    source = program(
        PROBE + P_CLASS,
        'void __script_pre_destroy(){',
        '  int z = 0;',
        '  int q = 1 / z;',
        '}',
        'P * main(){',
        '  P * p(5);',
        '  return p;',
        '}')
    hook_frame = frame('__script_pre_destroy', line_of(source, 'int q = 1 / z;'))
    result.append(own('own-g13i-probe-destroy-unwinding-after-hook-error', source, '0000000000000001', tag=7,
                      raw_teardown_events=[probe(5, 3, FULL - 16, [hook_frame])], partial=True,
                      teardown_error='Division by zero'))

    # Flush-phase unwinding: onload fails at its own node while a is alive, so
    # its root cleanup runs ~P at 3 (flush 1, onload 2), probe 4. One flush
    # budget: onload root 1, a 30, z 2, division 4 = 37, ~P 9 -> FULL-46, which
    # is also the remaining budget after the failed flush. No entry failed
    # before the probe, so nothing is published. The failed onload never
    # registers the hook and main never runs.
    source = program(
        PROBE + P_CLASS,
        'void __script_onload(){',
        '  P a(1);',
        '  int z = 0;',
        '  int q = 1 / z;',
        '}',
        'void __script_pre_destroy(){ probe(9); }',
        'int main(){ probe(3); return 0; }')
    result.append(own('own-g13j-probe-flush-unwinding-onload-own-node-error', source,
                      error='Division by zero', category='runtime_error',
                      raw_events=[probe(1, 4, FULL - 46, empty)], partial=True, remaining=FULL - 46,
                      trace=('__script_onload',), lines=(line_of(source, 'int q = 1 / z;'),),
                      teardown_events=()))
    return result


def budget_cases():
    """H1-H5, H7 and addition 17 (tick inventory, wrapped destructors)."""
    result = []
    step = 'Script execution step limit exceeded'

    # H1. The loop never ends, so every budget in the sweep ends in the same
    # exhaustion after its last stopping point (construction of a, p, q) and
    # the sweep converges. No destructor emits: each is entered and fails at
    # its first tick. The automatic p (heap start 1) is freed by main's root
    # cleanup; the manual q (start 2) stays until destroy discards it without a
    # destructor. The hook (fresh budget) observes the layout: first fit gives
    # 1, then 3.
    source = program(
        PRELUDE + OFF,
        'void __script_pre_destroy(){ address x = alloc(1); address y = alloc(1); event(off(x)); event(off(y)); }',
        'int main(){',
        '  M a(1);',
        '  M * p(2);',
        '  M * q = new M(3);',
        '  int k = 0;',
        '  while (k == 0) { }',
        '  return k;',
        '}')
    result.append(own('own-gh1-exhaustion-literal-auto-manual-live', source, error=step, category='runtime_error',
                      remaining=0, trace=('main',), lines=(line_of(source, 'while (k == 0) { }'),),
                      teardown_events=(1, 3)))

    # H2 (the loop never ends, so the sweep converges). Only ~L's earlier event
    # is emitted (2, not a's -1); a's ~M then fails at its first tick. ~L is at
    # its loop (its own line), main at its return (root cleanup).
    source = program(
        PRELUDE,
        'class L { int n; L(int v){ n = v; }',
        '  ~L(){ event(n); int k = 0;',
        '    while (k == 0) { }',
        '    event(0 - n); } }',
        'int main(){',
        '  M a(1);',
        '  L b(2);',
        '  return 0;',
        '}')
    result.append(own('own-gh2-exhaustion-in-first-destructor', source, error=step, category='runtime_error',
                      events=(2,), remaining=0, trace=('L::~L', 'main'),
                      lines=(line_of(source, 'while (k == 0) { }'), line_of(source, 'return 0;'))))

    # H1 at flush time (spec 4 "Exhaustion"): exhaustion inside the only onload
    # (shared onload budgets are covered by own-g12b). a's ~M is entered and
    # fails at its first tick; the hook is never registered (it would emit 9);
    # destroy gets a fresh budget but has nothing to run. The loop never ends,
    # so the sweep converges.
    source = program(
        PRELUDE,
        'void __script_onload(){',
        '  M a(1);',
        '  int k = 0;',
        '  while (k == 0) { }',
        '}',
        'void __script_pre_destroy(){ event(9); }',
        'int main(){ event(3); return 0; }')
    result.append(own('own-gh9-exhaustion-during-flush', source, error=step, category='runtime_error',
                      remaining=0, trace=('__script_onload',), lines=(line_of(source, 'while (k == 0) { }'),),
                      teardown_events=()))

    # D1 classes for the budget sweeps (spec section 12 D).
    d1 = ('class F { int k; ~F(){ event(-1); } }\n'
          'class B { F bf; ~B(){ event(-2); } }\n'
          'class G { int k; ~G(){ event(-3); } }\n'
          'class C : B { G cg; ~C(){ event(-4); } }\n')
    # H3: full sweep of D1. C c costs 88: vd 1, call 1, <value C> 86 = root 1 +
    # vd/new_block 2 + ctor call 3 (call, block_address, v) + C::C 75 + bind 3 +
    # return 2. C::C 75 = root 1 + #check-this 2 + flag vd 2 + cleanup 1 +
    # protected block 1 + #base-completed 5 + B::B 32 + phase block 1 +
    # #check-this/#set-methods 4 + G field 26, and B::B 32 = cleanup 1 + block 1
    # + 4 + F field 26 (its body is a cleanup node: no root tick). ~C 41 =
    # cleanup 1 + body 7 + SEQ 1 + drop 4 + ~G 7 + base call 2 + ~B 19 (cleanup
    # 1 + body 7 + drop 4 + ~F 7). Total root 1 + 88 + return 2 + 41 = 132.
    result.append(own('own-gh3-literal-chain-budget-sweep', program(
        EVENT + d1,
        'int main(){',
        '  C c;',
        '  return 0;',
        '}'), 0, events=(-4, -3, -2, -1), remaining=FULL - 132))

    # H3 for the heap path: <new C> 85 = root 1 + object_alloc 3 + ctor 2 + 75 +
    # ob 2 + ro 2; `C * p = new C();` 87 (vd 1, call 1), delete 2 + ~C 41;
    # total root 1 + 87 + 43 + return 2 = 133.
    result.append(own('own-gh3b-heap-chain-delete-budget-sweep', program(
        EVENT + d1,
        'int main(){',
        '  C * p = new C();',
        '  delete p;',
        '  return 0;',
        '}'), 0, events=(-4, -3, -2, -1), remaining=FULL - 133))

    # H4 with cleanup verifier addition 4: the copy of b overruns at its
    # materialize charge. W has 30 int slots (copy charge 30); W a(v) costs 175
    # (W::W 160 with 30 default field inits). Before probe: root 1, a 175,
    # b 175, i 2, while 9*111066+4, probe 2 -> 47 left. Then vd 1, call 1,
    # var 1, charge 30 (14 left), var 1, and the charge of 30 fails with 13
    # left: the budget becomes 0, mark() and use() never run, and the earlier
    # copy and both locals fail at their first tick (no events). Not swept:
    # the burn loop must finish to reach the charge, which needs far more steps
    # than the sweep cap.
    fields = ' '.join(f'int f{i};' for i in range(30))
    source = program(
        PRELUDE + f'class W {{ {fields} W(int v){{ f0 = v; }} ~W(){{ event(0 - f0); }} }}\n'
        'int mark(){ event(99); return 0; }\n'
        'int use(W x, W y, int k){ event(k); return k; }\n',
        'int main(){',
        '  W a(1);',
        '  W b(2);',
        '  int i = 0;',
        '  while (i < 111066) { i = i + 1; }',
        '  probe(i);',
        '  int r = use(a, b, mark());',
        '  return r;',
        '}')
    result.append(own('own-gh4-argument-copy-charge-exhausts-budget', source, error=step, category='runtime_error',
                      raw_events=[probe(111066, 2, 47)], partial=True, remaining=0, trace=('main',),
                      lines=(line_of(source, 'int r = use(a, b, mark());'),), sweep=False))

    # H5: the looping hook exhausts destroy's budget (one guard covers hooks and
    # baseEnv cleanup), so p, owned by baseEnv after main returned it, gets no
    # event, destroy reports the step limit and the teardown heap is empty
    # (spec H5). The empty heap does not show which path released p: storage
    # left in baseEnv is discarded by ~environment anyway (heepalloc.cpp:426-436).
    source = program(
        PRELUDE,
        'void __script_pre_destroy(){',
        '  event(7);',
        '  int k = 0;',
        '  while (k == 0) { }',
        '}',
        'M * main(){',
        '  M * p(5);',
        '  return p;',
        '}')
    result.append(own('own-gh5-looping-hook-starves-base-destructors', source, '0000000000000001', tag=7,
                      teardown_events=(7,), teardown_error=step))

    # H7 (host re-entry through the observation host's callback 4): the
    # re-entered bump runs at 4 (main 1, ~R 2, callback 3) and shares the
    # budget (no reset while active_calls > 0); its probe is at 5. Steps: main
    # root 1, a 30, return 2; ~R 11 before the callback; bump body 3 before its
    # probe, then return 4; ~R's event 2 -> 53 in all.
    source = program(
        PRELUDE + 'extern int reenter(int,int):6dff0004;\n',
        'int bump(int v):0011{ probe(v); return v + 1; }',
        f'class R {{ int n; R(int v){{ n = v; }} ~R(){{ int r = reenter({REENTRY_ID}, n); event(r); }} }}',
        'int main(){',
        '  R a(5);',
        '  return 0;',
        '}')
    result.append(own('own-gh7-destructor-reentry-shares-budget', source, 0,
                      raw_events=[{'callback': 4, 'arguments': [typed(0, REENTRY_ID), typed(0, 5)]},
                                  probe(5, 5, FULL - 47, []), {'reentry_return': typed(0, 6)}, plain(6)],
                      partial=True, remaining=FULL - 53))

    # Addition 17: exactly R steps left when the wrapped ~W is entered at main's
    # root cleanup. Not swept: the burn loop that places the budget needs far
    # more steps than the sweep cap. W's construction costs 45 (vd 1, call 1,
    # <value W> 43 with W::W 32), so before probe: root 1, w 45, i 2, while
    # 9N+4, pads, probe 2; then return 2.
    def wrapped(iterations, pads):
        return program(
            PROBE + EVENT + 'class F { int k; ~F(){ event(-1); } }\n',
            'class W { F f;',
            '  ~W(){',
            '    event(-2); } }',
            'int main(){',
            '  W w;',
            '  int i = 0;',
            f'  while (i < {iterations}) {{ i = i + 1; }}',
            *pads,
            '  probe(i);',
            '  return 0;',
            '}')
    # 1 step: the cleanup tick succeeds, the body block's tick fails (location 0,
    # header), the drop in final fails its own tick, and the native sweep's ~F
    # fails at its first tick. 111104 iterations and pads 2 + 5 leave 3 at the
    # probe.
    source = wrapped(111104, ['  int j0 = 0;', '  if (i > 0) { }'])
    result.append(own('own-gh8-wrapped-destructor-one-step-left', source, error=step, category='runtime_error',
                      raw_events=[probe(111104, 2, 3)], partial=True, remaining=0, trace=('W::~W', 'main'),
                      lines=(line_of(source, '~W(){'), line_of(source, 'return 0;')), sweep=False))
    # 7 steps: cleanup 1, block 1, #check-this 2, #set-methods 2 and event's call
    # tick 1 succeed; its constant fails, so ~W is at the event line. 111103
    # iterations and five 2-step pads leave 9 at the probe.
    source = wrapped(111103, [f'  int j{k} = 0;' for k in range(5)])
    result.append(own('own-gh8b-wrapped-destructor-seven-steps-left', source, error=step, category='runtime_error',
                      raw_events=[probe(111103, 2, 9)], partial=True, remaining=0, trace=('W::~W', 'main'),
                      lines=(line_of(source, 'event(-2);'), line_of(source, 'return 0;')), sweep=False))
    # 8 steps: the body completes (-2), the drop's own tick fails with the body
    # already done, so cleanup_expression keeps the final's location (0, the
    # header, library.cpp:870-875); ~F (native sweep) fails at its first tick.
    source = wrapped(111104, [])
    result.append(own('own-gh8c-wrapped-destructor-body-completes-drop-starves', source, error=step,
                      category='runtime_error', raw_events=[probe(111104, 2, 10), plain(-2)], partial=True,
                      remaining=0, trace=('W::~W', 'main'),
                      lines=(line_of(source, '~W(){'), line_of(source, 'return 0;')), sweep=False))

    # Addition 17, #set-methods = 2 + |vtable| (library.cpp:1015-1018; the
    # compiler emits it in every destructor, Compiler.java:2799-2801). main
    # returns p, so baseEnv destroys it under destroy's guard with a fresh
    # budget. Wrapped ~V1 (1 virtual method) at 2: cleanup 1, block 1,
    # #check-this 2, #set-methods 3, probe's call and constant 2 -> FULL-9 at
    # 3. Its final drops f: drop 4, then the unwrapped ~VF (3 virtual methods)
    # at 3: block 1, #check-this 2, #set-methods 5, call 2 -> FULL-23 at 4.
    source = program(
        PROBE,
        'class VF { int k; virtual int a(){ return 1; } virtual int b(){ return 2; } virtual int c(){ return 3; }',
        '  ~VF(){',
        '    probe(3); } }',
        'class V1 { VF f; virtual int who(){ return 1; }',
        '  ~V1(){',
        '    probe(1); } }',
        'V1 * main(){',
        '  V1 * p();',
        '  return p;',
        '}')
    result.append(own('own-gh8d-virtual-destructors-set-methods-ticks', source, '0000000000000001', tag=7,
                      raw_teardown_events=[probe(1, 3, FULL - 9, []), probe(3, 4, FULL - 23, [])], partial=True))
    return result


def cases():
    return trace_cases() + depth_cases() + probe_cases() + budget_cases()
