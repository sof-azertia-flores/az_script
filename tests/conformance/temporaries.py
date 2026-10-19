"""Group B: statement temporaries and argument materialization (stage "ownership").

Expectations are derived from the R11b tree specification (cleanup spec group B,
verifier additions 3, 4 and 13, ownership spec sections 3.5-3.7, 3.15 and 3.16,
cleanup verifier correction 12) and from the interpreter source:
- statement_scope / condition: interpreter/library.cpp:826-834, 2211-2216;
- call argument materialization: library.cpp:2147-2155, blocks.cpp:619-629
  (also for host callees, which receive the materialized values);
- host-held results adopted by the calling node: library.cpp:2153, blocks.cpp:630-635;
- parameter binding: library.cpp:2115-2121, blocks.cpp:604-611;
- object export: blocks.cpp:636-654; ro of automatic objects: heepalloc.cpp:400-425;
- select arms share the enclosing statement: library.cpp:690-700;
- for: a block [init, #for-first, while(true) [if(#for-first) ... else step,
  if(!cond) break, [body]]] (compiler Compiler.java:1407-1425); switch: a block
  whose first statement is vd #switch-value = expr (Compiler.java:1700-1720).

Step counts used for remaining_steps were derived by counting ticking nodes in the
compiled lowering (every executed node ticks once; a block ticks once in
execute_body; while ticks once on entry and once per body; copy_block charges the
slot count of each copied block; host calls and GUARD tick nothing). Prelude
costs, as seen by the caller:
  M a(v)          30 = vd 1 + call 1 + const 1 + <value M> 27 (ctor M::M 15)
  mk(v)           35 = call 1 + const 1 + body 33
  mk body         33 (a host re-entry runs only the body)
  ~M              11 (event is a host call and ticks nothing itself)
  ~Bad            17 (fails at the division after all its ticks)
  x.n / x.n = c   4 / 6 for a literal x (member, oa, block_address, var; m, const)
  poke(a)         11 = call 1 + block_address 1 + var 1 + body 8
The prelude occupies source lines 1-6 (M on line 3, Bad on line 6).

Trace lines. The harness checks frame lines only, so every case that names the
node a frame is reported at puts the competing nodes (statement, call, operator,
member access, function header) on different lines; line_of() finds them in the
source text. The prelude's Bad is a single line, so a ~Bad frame is pinned to
line 6 only (its node within that line is compared by the engine comparison).
"""
from vm_execution_cases import case, event_list
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

FULL = 1000000
BAD_LINE = 6
# Address helpers. A literal object's address is bit63 | id<<24 | offset
# (interpreter/blocks.h:36-38), so adding 16777216 advances the block id by one.
# addr() borrows a literal through C (*): no copy, no new id (LANGUAGE.md "C (*) p").
ADDR = ('address addr(M (*) p){ return address(p); }\n'
        'int delta(address a, address b){ int k = 0; while(b + 16777216 * k != a){ k = k + 1; } return k; }\n')
MKB = 'Bad mkb(int v){ Bad t(v); return t; }\n'
OVF = ('class Ovf { int n; Ovf(int v){ n = v; } ~Ovf(){ event(200+n); int q = 2147483647 + n; } }\n'
       'Ovf mko(int v){ Ovf t(v); return t; }\n')
# poke changes a after an earlier argument was snapshotted (C (*): borrowed, no copy).
POKE = 'int poke(M (*) p){ p.n = 9; return 0; }\n'
# Host re-entry (observation callback 4, tests/observation_host.cpp:196-198): the
# host calls script::invoke(id, [v]) and returns its result unchanged. script::invoke
# runs on baseEnv (library.cpp:1768-1774), so mkr's export has no destination and the
# result is host-held (blocks.cpp:650-653) until the calling node adopts it.
REENTRY_ID = 0x0fff0011   # 268369937, mkr's explicit id
REENTER = 'extern M reenter(int,int):6dff0004;\n'
MKR = 'M mkr(int v):0011{ M t(v); return t; }\n'
# The prelude without probe(), so callback 5 can be declared with an object
# parameter (one signature per extern id). M is then on line 2.
NOPROBE = PRELUDE.replace(PROBE, '')


def own(name, source, result=None, **options):
    return case(name, source, result, stage='ownership', **options)


def program(*rows):
    return ''.join(row + '\n' for row in rows)


def line_of(source, needle):
    """1-based line of the unique occurrence of needle (a fact of the source text)."""
    index = source.find(needle)
    if index < 0 or source.find(needle, index + 1) >= 0:
        raise AssertionError(f'{needle!r} must occur exactly once')
    return source.count('\n', 0, index) + 1


def probe(value, active, remaining=None):
    event = {'callback': 5, 'arguments': [typed(0, value)], 'active_calls': active}
    if remaining is not None:
        event['remaining_steps'] = remaining
    return event


def plain(value):
    return event_list([value])[0]


def m_value(n, ref=0):
    # An M block as the observation host prints it: structural id, state live (0), slot n.
    return {'tag': 8, 'value': {'id': ref, 'state': 0, 'slots': [typed(0, n)]}}


def reentry(value):
    return [{'callback': 4, 'arguments': [typed(0, REENTRY_ID), typed(0, value)]},
            {'reentry_return': m_value(value)}]


def cases():
    result = [
        # B1. A member of a temporary: the temporary lives until the statement
        # ends, after event(1). Steps: root 1 + statement 39 (event 1, member
        # access 1, oa 1, block_address 1, mk 35) + ~M 11 + return 2 = 53.
        own('own-b1-member-of-temporary', PRELUDE + 'int main(){ event(mk(1).n); return 0; }\n', 0,
            events=(1, -1), remaining=FULL - 53),
        # B2. Two temporaries of one statement are destroyed last-created first.
        own('own-b2-two-temporaries-lifo', PRELUDE + 'int main(){ event(mk(1).n + mk(2).n); return 0; }\n', 0,
            events=(3, -2, -1)),
        # B3 / condition temporaries die before the branch runs (library.cpp:2211-2218),
        # for both branches. The non-block body's own temporary dies when the body
        # ends (4, -4); b4c shows that such a body scope ends with each execution.
        own('own-b3-if-condition-temporary-dies-before-branch', PRELUDE +
            'int main(){\n'
            '  if (mk(1).n == 1) { event(5); } else { event(6); }\n'
            '  if (mk(2).n == 1) { event(5); } else { event(6); }\n'
            '  if (mk(3).n == 3) event(mk(4).n);\n'
            '  return 0; }\n', 0, events=(-1, 5, -2, 6, -3, 4, -4)),
        # An else-if is an if in the else branch (a ladder of 2 is not flattened,
        # Compiler.java:1454, GeneraterJson.java:2092): the first condition's
        # temporary dies before the else branch runs, the second before its body.
        own('own-b3b-else-if-condition-temporaries', PRELUDE +
            'int main(){\n'
            '  if (mk(1).n == 2) { event(5); } else if (mk(2).n == 2) { event(6); }\n'
            '  return 0; }\n', 0, events=(-1, -2, 6)),
        # A switch value is stored by vd #switch-value, the first statement of the
        # switch block, so its temporary dies before any case body (-1 before 5).
        own('own-b3c-switch-value-temporary-dies-before-case-body', PRELUDE +
            'int main(){\n'
            '  switch (mk(1).n) { case 1: event(5); break; default: event(6); }\n'
            '  event(7);\n'
            '  return 0; }\n', 0, events=(-1, 5, 7)),
        # B4. While-condition temporaries die after each evaluation, before the body
        # and also on the final, false evaluation.
        own('own-b4-while-condition-temporaries', PRELUDE +
            'int main(){ int i = 1; while (mk(i).n < 3) { event(10 * i); i = i + 1; } return i; }\n', 3,
            events=(-1, 10, -2, 20, -3)),
        # A for condition is the test of 'if (!cond) break;' and the step is the
        # else branch of 'if (#for-first)', both inside while(true) (module docstring):
        # the condition's temporary dies before the body or the exit, and the
        # step's temporary (-5) dies at the end of the step, before the next test.
        own('own-b4b-for-condition-and-step-temporaries', PRELUDE +
            'int main(){ int t = 0; for (int i = 0; mk(i + 1).n < 3; i = i + mk(5).n - 4) { event(10 + i); t = t + 1; } return t; }\n',
            2, events=(-1, 10, -5, -2, 11, -5, -3)),
        # A non-block while body runs in its own scoped_execute scope with a
        # statement scope (library.cpp:843-849), once per iteration: each
        # iteration's temporary dies at that iteration's end (-11 before -12), not
        # LIFO at the end of the while statement.
        own('own-b4c-non-block-while-body-temporaries-per-iteration', PRELUDE +
            'int main(){\n'
            '  int i = 10;\n'
            '  while (i < 12) i = mk(i + 1).n;\n'
            '  event(7);\n'
            '  return i; }\n', 12, events=(-11, -12, 7)),
    ]

    # B5. The statement fails after creating a temporary: event is never called,
    # the temporary is still destroyed, and the error keeps its position at the
    # division, not at the + node or the statement.
    source = PRELUDE + program(
        'int main(){',
        '  int z = 0;',
        '  event(mk(1).n',
        '    + 1',
        '    / z);',
        '  return 0; }')
    result.append(own('own-b5-error-after-temporary', source, error='Division by zero', category='runtime_error',
                      events=(-1,), trace=('main',), lines=(line_of(source, '/ z'),)))

    # Ownership 8.14 with a failing temporary destructor: the statement's own error
    # (overflow at the + node) wins with its class and trace; ~Bad still runs
    # (101) and its division error and frames are discarded (library.cpp:187-202),
    # so the trace has main only.
    source = PRELUDE + MKB + program(
        'int main(){',
        '  event(mkb(1).n',
        '    + 2147483647);',
        '  return 0; }')
    result.append(own('own-b5b-statement-error-wins-over-temporary-destructor-error', source,
                      error='Integer overflow', category='overflow_error', events=(101,),
                      trace=('main',), lines=(line_of(source, '+ 2147483647'),)))

    result += [
        # B6 / ownership 3.5, 3.15. A temporary argument of this frame is shared by
        # materialize and moved into the parameter: no copy, no new id. mk(7) is the
        # base id (it dies at its declaration's end, -7); mk(5) is base+1 and the
        # parameter keeps that id. The parameter dies when f returns, before
        # event(1) consumes f's result.
        own('own-b6-temporary-argument-moves-into-parameter', PRELUDE + ADDR +
            'int f(M x, address base){ event(x.n); return delta(addr(x), base); }\n'
            'int main(){ address base = addr(mk(7)); event(f(mk(5), base)); return 0; }\n', 0,
            events=(-7, 5, -5, 1)),
        # B7 / 3.15. An owned local argument is copied (charge 1 = M's slot count)
        # into a caller temporary, moved into the parameter, destroyed at f's exit
        # with f's change (-3); a is unchanged (-1 at main's exit).
        # Steps: root 1, M a(1) 30, probe 2 -> 999967 inside probe(1); f(a) is
        # call 1 + var 1 + charge 1 + body 12 (block 1, event(x.n) 5, x.n = 3 6)
        # + ~M 11 = 26, probe(2) 2 -> 999939; then event 2, return 2, ~M 11 -> 999924.
        own('own-b7-local-argument-copied-and-charged', PRELUDE +
            'void f(M x){ event(x.n); x.n = 3; }\n'
            'int main(){ M a(1); probe(1); f(a); probe(2); event(7); return 0; }\n', 0,
            raw_events=[probe(1, 2, 999967), plain(1), plain(-3), probe(2, 2, 999939), plain(7), plain(-1)],
            partial=True, remaining=999924),
        # The same path without probes: root 1, a 30, f(a) 26, event 2, return 2, ~M 11.
        own('own-b7b-local-argument-copy-sweep', PRELUDE +
            'void f(M x){ event(x.n); x.n = 3; }\n'
            'int main(){ M a(1); f(a); event(7); return 0; }\n', 0, events=(1, -3, 7, -1), remaining=FULL - 72),
    ]

    # spec correction: cleanup spec B8 ("f(a) at the depth limit -> no events") is
    # not reachable as written from main. M a(1) calls the <value M> factory,
    # which calls M::M (compiler/src/main/java/azertia/script/Compiler.java:2673),
    # and each script call checks active_calls >= max_call_depth
    # (interpreter/library.cpp:774), so constructing a needs depth >= 3 while a
    # call made from main fails only at depth 1.
    # B8 (adapted), max_call_depth 3: g2 runs at active_calls 3 and f's guard fails
    # before f's frame exists, so f is reported at its header (not its body line);
    # g2 and g1 are reported at their call nodes (not their headers), main at g1(.
    # The copy made for f (g2's statement temporary) and g2's parameter are
    # destroyed while g2 is still active, so ~M fails at entry (0 ticks); g1's
    # parameter is destroyed at depth 2 and ~M fails at its event call (11 ticks);
    # only main's own a reaches event(-1). Steps: root 1, a 30, three
    # call/var/charge triples 9, the execute_body ticks of g1 and g2 2, g1's
    # parameter ~M 11, main's a ~M 11 = 64.
    source = PRELUDE + program(
        'void f(M x){',
        '  event(x.n); }',
        'void g2(M y){',
        '  f(y); }',
        'void g1(M y){',
        '  g2(y); }',
        'int main(){ M a(1);',
        '  g1(a);',
        '  event(7); return 0; }')
    result.append(own('own-b8-depth-limit-before-callee-frame', source, error='Script call depth limit exceeded',
                      category='runtime_error', events=(-1,), options=['--call-depth', '3'],
                      trace=('f', 'g2', 'g1', 'main'), remaining=FULL - 64,
                      lines=(line_of(source, 'void f('), line_of(source, '  f(y)'), line_of(source, '  g2(y)'),
                             line_of(source, '  g1(a)'))))

    # B9 with the verifier's trace correction 3. r is exported into main's
    # temporaries at the return, before g's cleanup; ~Bad then fails, and r is
    # destroyed at the end of main's statement (after 101; a local r would die
    # before b). Trace: ~Bad (line 6), g at its return (not its header), main at
    # the g( call node, not at the statement or the .n member node.
    source = PRELUDE + program(
        'M g(){ Bad b(1); M r(5);',
        '  return r; }',
        'int main(){',
        '  event(',
        '    g()',
        '      .n);',
        '  return 0; }')
    result.append(own('own-b9-callee-cleanup-fails-after-export', source, error='Division by zero',
                      category='runtime_error', events=(101, -5), trace=('Bad::~Bad', 'g', 'main'),
                      lines=(BAD_LINE, line_of(source, '  return r;'), line_of(source, '    g()'))))

    # Ownership 8.10. A pointer result whose callee cleanup fails: ro has already
    # moved the automatic object into the call site's scope (heepalloc.cpp:420-424),
    # here the inner block, so it is destroyed when that block unwinds: -6 before
    # the block's c (-3, LIFO: the object was pushed after c), then main's a.
    source = PRELUDE + program(
        'M * gp(){ Bad b(1); M * p(6);',
        '  return p; }',
        'int main(){',
        '  M a(1);',
        '  { M c(3);',
        '    event(',
        '      gp()',
        '        .n); }',
        '  return 0; }')
    result.append(own('own-b9b-pointer-result-owned-by-call-site-block-when-cleanup-fails', source,
                      error='Division by zero', category='runtime_error', events=(101, -6, -3, -1),
                      trace=('Bad::~Bad', 'gp', 'main'),
                      lines=(BAD_LINE, line_of(source, '  return p;'), line_of(source, '      gp()'))))

    # Verifier addition 3. An argument fails after earlier arguments were
    # materialized: the shared temporary mk(1) and the copy of a (n = 4, taken
    # before poke set a.n = 9) are already in main's temporaries; they die (LIFO:
    # copy -4, then -1) at the statement end, before the inner block's c (-3) and
    # main's a (-9). main is reported at the / node.
    source = PRELUDE + program(
        'int three(M x, M y, int k){ return x.n + y.n + k; }',
        POKE.rstrip('\n'),
        'int main(){',
        '  M a(4); int z = 0;',
        '  { M c(3);',
        '    three(mk(1), a,',
        '      poke(a)',
        '      / z); }',
        '  return 0; }')
    result.append(own('own-b10-argument-failure-after-materialized-arguments', source, error='Division by zero',
                      category='runtime_error', events=(-4, -1, -3, -9), trace=('main',),
                      lines=(line_of(source, '/ z'),)))

    result += [
        # Verifier addition 4 (swept). The sweep passes the budget at which the copy
        # of w (charge 3) or of a (charge 1) overruns; both engines are compared at
        # every budget. Full run: root 1, a 30, w 33 (vd 1 + call 1 + factory 11 +
        # W::W 20), statement 30 (event 1, two 1, var 1, charge 1, var 1, charge 3,
        # body 11, ~M of x 11; W has no destructor), return 2, ~M of a 11 = 107.
        own('own-b11-materialize-charge-sweep', PRELUDE +
            'class W { int a; int b; int c; }\n'
            'int two(M x, W y){ return x.n + y.a; }\n'
            'int main(){ M a(1); W w; event(two(a, w)); return 0; }\n', 0,
            events=(-1, 1, -1), remaining=FULL - 107),
        # Verifier addition 4 (exact). Not swept: the 1000-slot copy of w must overrun
        # at the full default budget, so the program burns ~1M steps; every smaller
        # budget stops earlier in the loop, and the sweep cap is 1024 budgets. Steps
        # before probe(0): root 1, a 30, w 5018 (vd 1 + call 1 + factory 11 + W::W
        # 5005), i 2, while 9K+4, probe 2 -> 1000000 - 5057 - 9*110493 = 506. Then
        # event 1, two 1, var 1, copy of a charge 1, var 1 leave 501 < 1000: the
        # charge sets the budget to 0 and throws at the two( node (line 13). The copy
        # of a and main's a are destroyed but their destructors fail at their first
        # tick (no events after the probe).
        own('own-b11b-materialize-charge-exhausts-budget', PRELUDE +
            'class W { ' + ' '.join(f'int f{i};' for i in range(1000)) + ' }\n'
            'int two(M x, W y){ return x.n + y.f0; }\n'
            'int main(){ M a(1); W w; int i = 0;\n'
            '  while (i < 110493) { i = i + 1; }\n'
            '  probe(0);\n'
            '  event(\n'
            '    two(a, w));\n'
            '  return 0; }\n', error='Script execution step limit exceeded', category='runtime_error',
            raw_events=[probe(0, 2, 506)], partial=True, remaining=0, trace=('main',), lines=(13,), sweep=False),
        # Verifier addition 13 / ownership 3.7 case A. Returning a parameter moves
        # it: r keeps mk(1)'s id (base+1), and mk(2) is base+2, so id() consumed no
        # id. A returned parameter dies at the caller's statement end (-3).
        own('own-b13-export-parameter-moves', PRELUDE + ADDR +
            'M id(M x){ return x; }\n'
            'int main(){\n'
            '  address base = addr(mk(7));\n'
            '  M r = id(mk(1));\n'
            '  event(delta(addr(r), base));\n'
            '  M s = mk(2);\n'
            '  event(delta(addr(s), base));\n'
            '  event(id(mk(3)).n);\n'
            '  return 0; }\n', 0, events=(-7, 1, 2, 3, -3, -2, -1)),
        # spec correction: verifier addition 13 lists "a global" among exports that
        # copy, but source cannot declare a literal-object global ("Global g cannot
        # hold a class value or buffer; declare a pointer",
        # compiler/src/main/java/azertia/script/Compiler.java:3207), so only the
        # field / this-member copy is covered here; the global case is [wire].
        # Verifier addition 13 / 3.7 case C. Returning this's field copies it: one
        # new id (h is k, its field k+1, the copy k+2, the next mk k+3) and one
        # charged step. Steps: root 1, H h 52 (vd 1 + call 1 + factory 11 + H::H 39),
        # probe 2 -> 999945; M c = h.get() is vd 1, call 1, block_address 1, var 1,
        # get body 7, charge 1 = 12; probe 2 -> 999931. At exit d, c, then h's
        # synthesized destructor drops f.
        own('own-b13b-export-field-copies', PRELUDE + ADDR +
            'class H { M f(7); M get(){ return f; } }\n'
            'address haddr(H (*) p){ return address(p); }\n'
            'int main(){\n'
            '  H h;\n'
            '  probe(1);\n'
            '  M c = h.get();\n'
            '  probe(2);\n'
            '  c.n = 8;\n'
            '  event(delta(addr(c), haddr(h)));\n'
            '  event(h.f.n);\n'
            '  event(c.n);\n'
            '  M d = mk(9);\n'
            '  event(delta(addr(d), haddr(h)));\n'
            '  return 0; }\n', 0,
            raw_events=[probe(1, 2, 999945), probe(2, 2, 999931), plain(2), plain(7), plain(8), plain(3),
                        plain(-9), plain(-8), plain(-7)], partial=True),
        # The same field copy without probes.
        own('own-b13d-export-field-copy-ids-sweep', PRELUDE + ADDR +
            'class H { M f(7); M get(){ return f; } }\n'
            'address haddr(H (*) p){ return address(p); }\n'
            'int main(){ H h; M c = h.get(); c.n = 8; event(delta(addr(c), haddr(h))); event(h.f.n); event(c.n); return 0; }\n',
            0, events=(2, 7, 8, -8, -7)),
        # Ownership 3.7 case B. Returning an unowned temporary of this frame moves it:
        # r keeps mk(1)'s id, so fwd consumed no id.
        own('own-b13e-export-temporary-moves', PRELUDE + ADDR +
            'M fwd(int v){ return mk(v); }\n'
            'int main(){\n'
            '  address base = addr(mk(7));\n'
            '  M r = fwd(1);\n'
            '  event(delta(addr(r), base));\n'
            '  M s = mk(2);\n'
            '  event(delta(addr(s), base));\n'
            '  return 0; }\n', 0, events=(-7, 1, 2, -2, -1)),
        # Ownership 8.1 (verified e16). A local of a nested scope is moved by the
        # return: c has r's id, one destructor at main's exit.
        own('own-b13c-export-nested-scope-local-moves', PRELUDE + ADDR +
            'M nest(int v){ { M r(v); return r; } }\n'
            'int main(){\n'
            '  address base = addr(mk(7));\n'
            '  M c = nest(3);\n'
            '  event(delta(addr(c), base));\n'
            '  event(c.n);\n'
            '  return 0; }\n', 0, events=(-7, 1, 3, -3)),
        # Select arms share the statement's temporary lifetime (library.cpp:698-700):
        # only the chosen arm runs and its temporary dies at the statement end; in
        # an if condition it dies before the branch.
        own('own-b14-select-arm-temporaries', PRELUDE +
            'int main(){\n'
            '  int k = 1;\n'
            '  event(k == 1 ? mk(1).n : mk(2).n);\n'
            '  event(k == 2 ? mk(3).n : mk(4).n);\n'
            '  if ((k == 1 ? mk(5).n : mk(6).n) == 5) { event(7); }\n'
            '  return 0; }\n', 0, events=(1, -1, 4, -4, -5, 7)),
        # Ownership addition 1. return c ? a : b moves the chosen local (select
        # returns the variable itself, export case A); the other local dies in the
        # callee, the moved one at the caller's statement end. A copy would emit
        # the chosen local's destructor inside pick, before event.
        own('own-b15-select-return-moves-chosen-local', PRELUDE +
            'M pick(int k){ M a(k * 10 + 1); M b(k * 10 + 2); return k == 1 ? a : b; }\n'
            'int main(){ event(pick(1).n); event(pick(2).n); return 0; }\n', 0,
            events=(-12, 11, -11, -21, 22, -22)),
        # Ownership addition 1. return a = b assigns in place and returns an alias of
        # a's block, which a still owns, so export case A moves a's block (n = 2):
        # b dies in asg, a's block at the caller's statement end. The second call
        # pins which block moved: mk(7) is k, a is k+1, b k+2, a copy would be k+3.
        own('own-b15b-return-assignment-moves-target', PRELUDE + ADDR +
            'M asg(){ M a(1); M b(2); return a = b; }\n'
            'int main(){\n'
            '  event(asg().n);\n'
            '  address base = addr(mk(7));\n'
            '  event(delta(addr(asg()), base));\n'
            '  return 0; }\n', 0, events=(-2, 2, -2, -7, -2, 1, -2)),
        # A select of two temporaries initializes by move (no statement-end
        # destructor); a select of owned locals initializes by copy.
        own('own-b16-select-initializer-move-and-copy', PRELUDE +
            'int main(){\n'
            '  int k = 1;\n'
            '  M a(5);\n'
            '  M c = k == 1 ? mk(1) : mk(2);\n'
            '  event(10);\n'
            '  M d = k == 1 ? a : c;\n'
            '  d.n = 6;\n'
            '  event(a.n);\n'
            '  return 0; }\n', 0, events=(10, 5, -6, -1, -5)),
        # Ownership addition 5. The return statement's own temporaries die in its
        # statement scope, before the callee's locals.
        own('own-b17-return-statement-temporary', PRELUDE +
            'int f(){ M a(1); return mk(2).n; }\n'
            'int main(){ event(f()); return 0; }\n', 0, events=(-2, -1, 2)),
        # Ownership 8.4 / 8.5 (owned return into a discarded call result). A
        # discarded literal result dies at its statement end; a discarded automatic
        # pointer, also through a chain of returns, is owned by the call-site block
        # and dies in LIFO order with that block's locals.
        own('own-b18-discarded-results', PRELUDE +
            'M * chain(int v){ return mkp(v); }\n'
            'int main(){\n'
            '  {\n'
            '    M a(1);\n'
            '    mk(2);\n'
            '    event(5);\n'
            '    mkp(3);\n'
            '    chain(8);\n'
            '    M b(4);\n'
            '    event(6);\n'
            '  }\n'
            '  event(7);\n'
            '  return 0; }\n', 0, events=(-2, 5, 6, -4, -8, -3, -1, 7)),
        # Cleanup verifier addition 2. An automatic object created in a call argument
        # is not statement-scoped: it belongs to the caller's current block.
        own('own-b19-automatic-argument-lives-to-scope-exit', PRELUDE +
            'int peek(M * p){ return p.n; }\n'
            'int main(){\n'
            '  { event(peek(mkp(7))); event(8); }\n'
            '  event(9);\n'
            '  return 0; }\n', 0, events=(7, 8, -7, 9)),
        # Cleanup verifier addition 2. A while has no scope of its own: automatic
        # objects from its condition belong to the scope executing the while, here
        # the enclosing block, so they outlive the loop (event(5) first) and die
        # LIFO when that block exits (-0 is 0).
        own('own-b20-while-condition-automatic-objects-accumulate', PRELUDE +
            'int main(){\n'
            '  { int i = 0; while (mkp(i) != null && i < 3) { i = i + 1; } event(5); }\n'
            '  event(6);\n'
            '  return 0; }\n', 0, events=(5, -3, -2, -1, 0, 6)),
        # Cleanup verifier addition 2. A non-block if or while body runs in its own
        # scoped_execute scope (library.cpp:843-849), which owns the automatic
        # objects created there and ends with the body (each iteration).
        own('own-b20b-non-block-body-automatic-objects', PRELUDE +
            'int peek(M * p){ return p.n; }\n'
            'int main(){\n'
            '  int k = 1;\n'
            '  if (k == 1) mkp(5);\n'
            '  event(6);\n'
            '  int i = 0;\n'
            '  while (i < 2) i = i + 1 + 0 * peek(mkp(10 + i));\n'
            '  event(7);\n'
            '  return 0; }\n', 0, events=(-5, 6, -10, -11, 7)),
    ]

    # Cleanup verifier correction 4. A while-condition temporary whose destructor
    # fails reports main at the condition expression (its > node), not at the
    # while, the mkb( call or the .n member node.
    source = PRELUDE + MKB + program(
        'int main(){',
        '  while (',
        '    mkb(1).n',
        '      > 5) { event(7); }',
        '  return 0; }')
    result.append(own('own-b21-while-condition-destructor-error-position', source, error='Division by zero',
                      category='runtime_error', events=(101,), trace=('Bad::~Bad', 'main'),
                      lines=(BAD_LINE, line_of(source, '> 5'))))

    # Correction 4 for a for condition: the lowered 'if (!cond) break;' copies the
    # condition's position (Compiler.java:1417-1419), so main is at the > node, not
    # at the for, the mkb( call or the step. The body never runs.
    source = PRELUDE + MKB + program(
        'int main(){',
        '  for (int i = 0;',
        '    mkb(2).n',
        '      > i;',
        '    i = i + 1) { event(7); }',
        '  return 0; }')
    result.append(own('own-b21b-for-condition-destructor-error-position', source, error='Division by zero',
                      category='runtime_error', events=(102,), trace=('Bad::~Bad', 'main'),
                      lines=(BAD_LINE, line_of(source, '> i;'))))

    # Correction 4, suggested scenario: a condition that throws while its
    # temporary is live. The temporary still dies in the condition's statement
    # scope (-1), and the error keeps the failing / node, not the condition's >.
    source = PRELUDE + program(
        'int main(){',
        '  int z = 0;',
        '  while (',
        '    mk(1).n',
        '      / z',
        '      > 0) { event(7); }',
        '  return 0; }')
    result.append(own('own-b21c-condition-error-with-live-condition-temporary', source, error='Division by zero',
                      category='runtime_error', events=(-1,), trace=('main',), lines=(line_of(source, '/ z'),)))

    # Correction 4 for an if condition.
    source = PRELUDE + MKB + program(
        'int main(){',
        '  if (',
        '    mkb(2).n',
        '      > 5) { event(7); }',
        '  return 0; }')
    result.append(own('own-b22-if-condition-destructor-error-position', source, error='Division by zero',
                      category='runtime_error', events=(102,), trace=('Bad::~Bad', 'main'),
                      lines=(BAD_LINE, line_of(source, '> 5'))))

    result += [
        # Correction 4 for a statement temporary: main is reported at the
        # statement's top node (event( on line 9), restored after the arguments
        # (mkb( and .n are on line 10).
        own('own-b23-statement-temporary-destructor-error-position', PRELUDE + MKB +
            'int main(){\n'
            '  event(\n'
            '    mkb(3).n);\n'
            '  return 0; }\n', error='Division by zero', category='runtime_error', events=(3, 103),
            trace=('Bad::~Bad', 'main'), lines=(BAD_LINE, 9)),
        # Ownership 3.5. Each owned argument is snapshotted before the next argument
        # is evaluated: x sees n = 1, poke then sets a.n = 9, and y sees 9.
        # Parameters die last first when pair returns: y with pair's change (-3),
        # then x (-1); a keeps 9.
        own('own-b24-argument-snapshot-before-next-argument', PRELUDE + POKE +
            'int pair(M x, int k, M y){ int r = x.n * 100 + y.n * 10 + k; y.n = 3; return r; }\n'
            'int main(){ M a(1); event(pair(a, poke(a), a)); event(a.n); return 0; }\n', 0,
            events=(-3, -1, 190, 9, -9)),
        # Ownership 3.5. Field arguments are owned by their slot, so they are copied,
        # both for a literal's field (7) and a heap object's field (8); the copies
        # die with take's change (-17, -18) and the fields keep 7 and 8. At exit the
        # automatic object (pushed after h) is destroyed first (-8, then -7).
        own('own-b24b-field-arguments-copied', PRELUDE +
            'class H { M f(7); }\n'
            'void take(M x){ event(x.n); x.n = x.n + 10; }\n'
            'int main(){ H h; H * p(); p.f.n = 8; take(h.f); take(p.f); event(h.f.n); event(p.f.n); return 0; }\n', 0,
            events=(7, -17, 8, -18, 7, 8, -8, -7)),
        # Ownership 3.1 copy_block. Copying an argument with a literal field takes
        # ids parent first: h is k, h.f k+1, the copy k+2, its field k+3. The copy's
        # field dies with take2's change (-4) when take2 returns; h's at exit (-7).
        own('own-b24c-nested-argument-copy-ids', PRELUDE + ADDR +
            'class H { M f(7); }\n'
            'address haddr(H (*) p){ return address(p); }\n'
            'int take2(H x, address base){ int r = delta(haddr(x), base) * 10 + delta(addr(x.f), base); x.f.n = 4; return r; }\n'
            'int main(){ H h; event(take2(h, haddr(h))); return 0; }\n', 0, events=(-4, 23, -7)),
        # Ownership 3.15 / 8.12. Copy ids are taken at argument evaluation, in
        # argument order (copy of a base+1 before mk(2) base+2; then base+3, base+4);
        # parameter 2 dies before parameter 1 (ids marks each with 50 or 60).
        own('own-b25-argument-copy-ids-in-order', PRELUDE + ADDR +
            'int ids(M x, M y, address base){ int r = delta(addr(x), base) * 10 + delta(addr(y), base); '
            'x.n = 50 + x.n; y.n = 60 + y.n; return r; }\n'
            'int main(){\n'
            '  M a(1);\n'
            '  event(ids(a, mk(2), addr(a)));\n'
            '  event(ids(a, a, addr(a)));\n'
            '  return 0; }\n', 0, events=(-62, -51, 12, -61, -51, 34, -1)),
        # Ownership 8.11. The by-value parameter is a copy: the caller's a is
        # unchanged, and the copy's destructor sees the modification after the
        # callee's local.
        own('own-b26-by-value-parameter-modified', PRELUDE +
            'void bump(M x){ M l(9); x.n = x.n + 100; }\n'
            'int main(){ M a(1); bump(a); event(a.n); return 0; }\n', 0, events=(-9, -101, 1, -1)),
        # Ownership 3.7 export destination. main's caller is the host (baseEnv has no
        # frame), so the exported r is host-held: no destructor at any point,
        # including teardown.
        own('own-b27-object-result-to-host-is-host-held', PRELUDE +
            'M main(){ M r(5); M s(6); return r; }\n',
            {'id': 0, 'state': 0, 'slots': [typed(0, 5)]}, tag=8, events=(-6,), teardown_events=()),
        # Ownership 3.7. With a host caller, an exported result whose callee cleanup
        # then fails is dropped with the frame, without a destructor; main is at
        # its return (line 8), not its header (line 7).
        own('own-b27b-host-result-dropped-when-cleanup-fails', PRELUDE +
            'M main(){ Bad b(1); M r(5);\n'
            '  return r; }\n', error='Division by zero', category='runtime_error', events=(101,),
            trace=('Bad::~Bad', 'main'), lines=(BAD_LINE, 8), teardown_events=()),
        # LANGUAGE.md "C (*) p": a borrowed temporary stays valid during the call
        # and dies at the statement end.
        own('own-b29-borrowed-temporary-lives-through-call', PRELUDE +
            'int rd(M (*) p){ return p.n; }\n'
            'int main(){ event(rd(mk(4))); event(5); return 0; }\n', 0, events=(4, -4, 5)),
        # Ownership 8.32. A borrowed address of a temporary kept past its statement
        # has expired. mk(4) is the first block of the process (id 1), so the
        # address is 2^63 + 2^24.
        own('own-b29b-borrowed-temporary-expires', PRELUDE +
            'M * keep(M (*) p){ return p; }\n'
            'int main(){ M * q = keep(mk(4)); event(1); event(q.n); return 0; }\n',
            error='Invalid or expired object address: 9223372036871553024', category='out_of_range',
            events=(-4, 1), trace=('main',)),
        # Destructor call depth by initiator (cleanup spec section 3: a destructor run
        # by a native driver runs at the initiator's active_calls + 1, probe adds 1);
        # ~P marks its object with 100 + n. ~P is 11 (block 1, check-this 2,
        # set-methods 2, probe(100 + n) 6). Steps: root 1 + probe statement 39 ->
        # 999960; ~P 11 -> 999949; if 41 and ~P 11 -> 999897; branch block 1 +
        # probe 2 -> 999894; take(mkP(3)) 36 + take body 3 -> 999855; take's
        # parameter ~P 11 -> 999844; return 2 -> 999842.
        own('own-b30-temporary-destructor-depth-and-budget', PRELUDE +
            'class P { int n; P(int v){ n = v; } ~P(){ probe(100 + n); } }\n'
            'P mkP(int v){ P t(v); return t; }\n'
            'void take(P x){ probe(10); }\n'
            'int main(){\n'
            '  probe(mkP(1).n);\n'
            '  if (mkP(2).n == 2) { probe(20); }\n'
            '  take(mkP(3));\n'
            '  return 0; }\n', 0,
            raw_events=[probe(1, 2, 999960), probe(101, 3, 999949), probe(102, 3, 999897), probe(20, 2, 999894),
                        probe(10, 3, 999855), probe(103, 4, 999844)], partial=True, remaining=999842),
        # The same paths without probes (event costs the same as probe).
        own('own-b30b-temporary-destructor-initiators-sweep', PRELUDE +
            'class P { int n; P(int v){ n = v; } ~P(){ event(100 + n); } }\n'
            'P mkP(int v){ P t(v); return t; }\n'
            'void take(P x){ event(10); }\n'
            'int main(){\n'
            '  event(mkP(1).n);\n'
            '  if (mkP(2).n == 2) { event(20); }\n'
            '  take(mkP(3));\n'
            '  return 0; }\n', 0, events=(1, 101, 102, 20, 10, 103), remaining=999842),
    ]

    # First error among statement temporaries: they are destroyed LIFO, so the
    # last-created temporary's error is the first one raised and wins; the
    # earlier temporary still runs its destructor (event 201 / 102). main is at
    # the statement's event( node, not at either temporary's call.
    source = PRELUDE + MKB + OVF + program(
        'int main(){',
        '  event(',
        '    mko(1).n',
        '    + mkb(2).n);',
        '  return 0; }')
    result.append(own('own-b31-temporary-destructor-errors-first-wins', source, error='Division by zero',
                      category='runtime_error', events=(3, 102, 201), trace=('Bad::~Bad', 'main'),
                      lines=(BAD_LINE, line_of(source, '  event(\n'))))
    source = PRELUDE + MKB + OVF + program(
        'int main(){',
        '  event(',
        '    mkb(2).n',
        '    + mko(1).n);',
        '  return 0; }')
    result.append(own('own-b31b-temporary-destructor-errors-first-wins-reversed', source, error='Integer overflow',
                      category='overflow_error', events=(3, 201, 102), trace=('Ovf::~Ovf', 'main'),
                      lines=(line_of(source, 'class Ovf'), line_of(source, '  event(\n'))))

    # Ownership addition 10 edge: a statement-end temporary whose destructor
    # exceeds the depth limit makes an otherwise successful statement fail.
    # max_call_depth 4: mkd/factory/ctor fit, ~D -> h1 -> h2 run at 2..4 and
    # event's own guard fails (native frame, empty name, no position). h2, h1 and
    # ~D are reported at their call nodes, not their headers; main at the statement.
    source = EVENT + program(
        'void h2(int v){',
        '  event(v); }',
        'void h1(int v){',
        '  h2(v); }',
        'class D { int n; D(int v){ n = v; }',
        '  ~D(){',
        '    h1(0 - n); } }',
        'D mkd(int v){ D t(v); return t; }',
        'int main(){',
        '  event(',
        '    mkd(1).n);',
        '  event(2);',
        '  return 0; }')
    result.append(own('own-b32-temporary-destructor-depth-failure', source, error='Script call depth limit exceeded',
                      category='runtime_error', events=(1,), options=['--call-depth', '4'],
                      trace=('', 'h2', 'h1', 'D::~D', 'main'),
                      lines=(0, line_of(source, 'event(v)'), line_of(source, 'h2(v);'), line_of(source, 'h1(0 - n)'),
                             line_of(source, '  event(\n'))))

    # Virtual-call arguments are materialized at call_virtual (library.cpp:1039-1041):
    # an owned local is copied (its parameter dies with take's change, -31), a
    # temporary moved (one destructor, -32), and each owned argument is
    # snapshotted before the next one: x sees 1, y sees poke's 9 (a VM that copied
    # at parameter binding instead would give 990). pair's parameters die y (-4,
    # pair's change) then x (-1); a keeps 9.
    result.append(own('own-b33-virtual-call-argument-materialization', PRELUDE + POKE + program(
        'class V { int k; virtual int take(M x){ int r = x.n + k; x.n = 30 + x.n; return r; }',
        '  virtual int pair(M x, int j, M y){ int r = x.n * 100 + y.n * 10 + j; y.n = 4; return r; } }',
        'int main(){',
        '  M a(1);',
        '  V * v();',
        '  event(v.take(a));',
        '  event(v.take(mk(2)));',
        '  event(v.pair(a, poke(a), a));',
        '  return 0; }'), 0, events=(-31, 1, -32, 2, -4, -1, 190, -9)))

    # A null receiver is rejected only after every argument was evaluated and
    # materialized (library.cpp:1040-1042, heepalloc.cpp:286): the copy of a
    # (taken before poke) dies at the statement end (-1), then a (-9). main is at
    # the call_virtual node (the take token), not at event( or poke(.
    source = PRELUDE + POKE + program(
        'class V { int k; virtual int take(M x, int j){ return x.n + j; } }',
        'int main(){',
        '  M a(1);',
        '  V * v = null;',
        '  event(',
        '    v.take(a,',
        '      poke(a)));',
        '  return 0; }')
    result.append(own('own-b33b-virtual-call-null-receiver-after-materialized-arguments', source,
                      error='Virtual call requires a non-null object', category='runtime_error', events=(-1, -9),
                      trace=('main',), lines=(line_of(source, 'v.take(a,'),)))

    # Ownership 3.6 adopt_result / 3.16, cleanup verifier correction 12. A host
    # callback returns a host-held block (the re-entered mkr exported it with no
    # destination); the calling node adopts it into main's temporaries, so it is
    # destroyed, with its destructor, at the end of main's statement (-5 after 5).
    # Steps: root 1; statement 7 (event 1, member 1, oa 1, block_address 1,
    # reenter 1, two constants 2); mkr body 33 (the host invokes it directly, and
    # active_calls is 2, so no budget reset); ~M 11; event 2; return 2 = 56.
    result.append(own('own-b34-host-result-member-of-adopted-temporary', PRELUDE + REENTER + MKR + program(
        'int main(){',
        '  event(reenter(268369937, 5).n);',
        '  event(9);',
        '  return 0; }'), 0, raw_events=[*reentry(5), plain(5), plain(-5), plain(9)], remaining=FULL - 56))
    # The adopted host result is an unowned temporary of main's frame, so the
    # parameter store moves it (one destructor, with take's change: -50; a copy
    # would add -5 at the statement end). Steps: root 1, statement 4 (take 1,
    # reenter 1, constants 2), mkr 33, take body 12 (block 1, event 5, x.n = 50 6),
    # x's ~M 11, event 2, return 2 = 65.
    result.append(own('own-b34b-host-result-moves-into-parameter', PRELUDE + REENTER + MKR + program(
        'void take(M x){ event(x.n); x.n = 50; }',
        'int main(){',
        '  take(reenter(268369937, 5));',
        '  event(9);',
        '  return 0; }'), 0, raw_events=[*reentry(5), plain(5), plain(-50), plain(9)], remaining=FULL - 65))
    # A declaration moves the adopted result into the local: no statement-end
    # destructor, one -6 at main's exit. Steps: root 1, vd statement 4, mkr 33,
    # event(k.n) 5, event 2, return 2, k's ~M 11 = 58.
    result.append(own('own-b34c-host-result-moves-into-local', PRELUDE + REENTER + MKR + program(
        'int main(){',
        '  M k = reenter(268369937, 6);',
        '  event(k.n);',
        '  event(9);',
        '  return 0; }'), 0, raw_events=[*reentry(6), plain(6), plain(9), plain(-6)], remaining=FULL - 58))
    # A discarded host result is adopted too and dies at its statement end (-7),
    # before the next statement and before a. Steps: root 1, a 30, statement 3,
    # mkr 33, ~M 11, event 2, return 2, a's ~M 11 = 93.
    result.append(own('own-b34d-discarded-host-result-dies-at-statement-end', PRELUDE + REENTER + MKR + program(
        'int main(){',
        '  M a(1);',
        '  reenter(268369937, 7);',
        '  event(9);',
        '  return 0; }'), 0, raw_events=[*reentry(7), plain(-7), plain(9), plain(-1)], remaining=FULL - 93))

    # Ownership 3.5 / 3.16, verifier addition 3 at a host call. Host callees get
    # materialized arguments too: the shared temporary mk(2) and a copy of a (n = 1,
    # taken before poke set a.n = 9) are main's temporaries. Callback 2 records
    # them and throws; checked_external_function adds a native frame (empty name,
    # line 0); the statement end destroys the copy (-1) then mk(2)'s temporary
    # (-2), and main's exit destroys a (-9). main is at the hfail( call node, not
    # at poke(. Steps: root 1, a 30, event 2, hfail 1 + mk(2) 35 + var 1 + charge 1
    # + poke 11, two ~M 22, a's ~M 11 = 115.
    source = PRELUDE + 'extern int hfail(M, M, int):6dff0002;\n' + POKE + program(
        'int main(){',
        '  M a(1);',
        '  event(5);',
        '  hfail(mk(2), a,',
        '    poke(a));',
        '  event(6);',
        '  return 0; }')
    result.append(own('own-b35-host-call-arguments-destroyed-after-host-failure', source,
                      error='differential host failure', category='runtime_error',
                      raw_events=[plain(5), {'callback': 2, 'arguments': [m_value(2, 0), m_value(1, 1), typed(0, 0)]},
                                  plain(-1), plain(-2), plain(-9)],
                      trace=('', 'main'), lines=(0, line_of(source, 'hfail(mk(2)')), remaining=FULL - 115))

    # The same materialization for successful host calls (callback 5 records its
    # arguments, call depth and budget and returns the first argument): the copy
    # of a (charged 1) and the shared mk(2) temporary (no charge, one destructor)
    # live until the statement ends, after the host returned and after event(7);
    # they die LIFO (-2, -1), and a, changed afterwards, dies at exit (-8).
    # Steps: root 1, a 30 -> 999969; event 1, add 1, pm 1, const 1, var 1, charge 1
    # -> 999963 in the first callback; pm 1, const 1, mk(2) 35 -> 999926 in the
    # second; two ~M 22, a.n = 8 6, event 2, return 2, a's ~M 11 -> 999883.
    result.append(own('own-b35b-host-call-object-arguments-copied-and-shared',
                      NOPROBE + 'extern int pm(int, M):6dff0005;\n' + program(
                          'int main(){',
                          '  M a(1);',
                          '  event(pm(3, a) + pm(4, mk(2)));',
                          '  a.n = 8;',
                          '  event(9);',
                          '  return 0; }'), 0,
                      raw_events=[{'callback': 5, 'arguments': [typed(0, 3), m_value(1)], 'active_calls': 2,
                                   'remaining_steps': 999963},
                                  {'callback': 5, 'arguments': [typed(0, 4), m_value(2)], 'active_calls': 2,
                                   'remaining_steps': 999926},
                                  plain(7), plain(-2), plain(-1), plain(9), plain(-8)],
                      partial=True, remaining=999883))
    return result
