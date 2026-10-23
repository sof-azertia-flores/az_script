"""Group A: scope and frame exit (cleanup spec section 12 group A, timelines T1/T2,
and owner-scope rules for automatic objects).

Expectations are derived from the tree-interpreter specification and the
interpreter/compiler source, not from observed output:
- environment::cleanup releases owned_pointer last first (library.cpp:2289-2310);
  literal locals are pushed by bind_local at the end of their vd (library.cpp:2157-2168,
  blocks.cpp:613-618), parameters at call entry (library.cpp:2115-2121), and automatic
  objects when ro runs (heepalloc.cpp:400-425), onto the env that executed the call node.
- Scope structure itself is not observable (getDepth was removed in exec v11);
  scopes show only through object lifetimes. The function body block runs on the
  root (library.cpp:2125); nested blocks and non-block if/while bodies get a scope
  (library.cpp:843-849, 2143-2146); conditions run on the enclosing env
  (library.cpp:2211-2216); #cleanup-wrapped constructor/destructor bodies and the
  derived-constructor phase add scopes (Compiler.java:2832-2866).
- for is lowered to {init; #for-first; while(true){ if(first)..else step; if(!cond) break;
  { body } }} (Compiler.java:1407-1425), so a block body sits four scopes below the
  enclosing env, the condition runs on the iteration scope and the step is the
  non-block else body, with its own scoped_execute scope.
- switch is lowered to {vd value; [vd continue-flag]; while(true){ if(label) {section} ...
  {default} break; } [if(continue-flag) continue] } (Compiler.java:1696-1724): the
  value is evaluated in the wrapper block, each section is a block inside the
  synthetic loop's iteration block, and a continue in a section sets the flag and
  breaks; the wrapper relays it to the enclosing loop.
- Trace positions follow cleanup spec 2.3 with verify corrections 1, 3 and 4; every
  position expectation puts the expected node on a line of its own, because the
  driver compares frame lines only.
"""
from vm_execution_cases import case
from conformance import PRELUDE, EVENT, PROBE
from runtime_differential import typed

# A literal class whose destructor emits 100+n and then fails with Division by zero.
# Unlike the prelude's Bad (one line), its destructor header and its failing
# division are on different lines, so a trace line tells the failing node
# (cleanup spec L1) from the header.
FAIL_CLASS = ('class Fail {\n'
              '  int n;\n'
              '  Fail(int v){ n = v; }\n'
              '  ~Fail(){\n'
              '    event(100 + n);\n'
              '    int zero = 0;\n'
              '    int q =\n'
              '      1 / zero;\n'
              '  }\n'
              '}\n'
              'Fail mkF(int v){ Fail t(v); return t; }\n')
FAIL_MARK = '1 / zero;'

# A literal class whose address exposes its block id (bit63 | id<<24).
T_CLASS = ('class T { int n; T(int v){ n = v; } T * self(){ return this; } ~T(){ event(0-n); } }\n'
           'T mkT(int v){ T t(v); return t; }\n')
# Records n*10+2 in its constructor and the negation in its destructor.
U_CLASS = ('class U { int n; U(int v){ n = v; event(n * 10 + 2); } '
           '~U(){ event(0 - (n * 10 + 2)); } }\n')


def block_address(block_id, offset=0):
    """Literal object address encoding, blocks.h:36-38."""
    return f'{(1 << 63) | (block_id << 24) | offset:016x}'


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


def cases():
    result = []

    # A1, T1: one scope owns literal locals and automatic objects; release is
    # owned_pointer LIFO regardless of kind.
    result.append(own('own-a1-scope-lifo-mixed-kinds', program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  M * p(2);',
        '  M b(3);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -1)))

    # T1 / ownership 3.4: a local is bound after its initializer completes, so an
    # automatic object transferred into the same scope while the initializer runs
    # is released after the local although it was created first.
    result.append(own('own-a1b-initializer-auto-released-after-local', program(
        PRELUDE,
        'int main(){',
        '  M a = mk(mkp(1).n + 1);',
        '  event(a.n);',
        '  return 0;',
        '}'), 0, events=(2, -2, -1)))

    # A2, T1, section 8 "parameters die after locals": parameters are pushed at
    # call entry, so they are released after every root local and every automatic
    # object transferred into the root (mkp(5) is a discarded call-site object).
    result.append(own('own-a1c-params-after-locals-and-autos', program(
        PRELUDE,
        'void f(M x, M y){',
        '  M a(3);',
        '  M * p(4);',
        '  mkp(5);',
        '  M b(6);',
        '}',
        'int main(){',
        '  f(mk(1), mk(2));',
        '  event(7);',
        '  return 0;',
        '}'), 0, events=(-6, -5, -4, -3, -2, -1, 7)))

    # A2: temporaries passed by value are moved into the parameters, not copied:
    # mkT(1) and mkT(2) consume block ids 1 and 2 (blocks.cpp:27-33, 59-69) and are
    # moved by store (blocks.cpp:607-611), so y still has id 2 (the returned address).
    result.append(own('own-a2-temporary-args-moved-not-copied', program(
        EVENT + T_CLASS,
        'address f(T x, T y){',
        '  T l(9);',
        '  return address(y.self());',
        '}',
        'address main(){',
        '  address r = f(mkT(1), mkT(2));',
        '  event(10);',
        '  return r;',
        '}'), block_address(2), tag=7, events=(-9, -2, -1, 10)))

    # Section 8 #11/#12: g(a, a) materializes two copies in argument order (ids 2
    # and 3), the callee's changes stay in the copies, and the second parameter
    # dies first. The caller's a is unchanged and dies at its own scope exit.
    result.append(own('own-a2b-copied-args-ids-and-param-order', program(
        EVENT + T_CLASS,
        'address g(T p, T q){',
        '  p.n = 10;',
        '  q.n = 20;',
        '  return address(q.self());',
        '}',
        'address main(){',
        '  T a(1);',
        '  address r = g(a, a);',
        '  event(a.n);',
        '  return r;',
        '}'), block_address(3), tag=7, events=(-20, -10, 1, -1)))

    # A3: nested blocks release on their own exit, innermost first.
    result.append(own('own-a3-nested-block-exit', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    M a(1);',
        '    {',
        '      M b(2);',
        '      event(100);',
        '    }',
        '    event(200);',
        '  }',
        '  return 0;',
        '}'), 0, events=(100, -2, 200, -1)))

    # A4: every for iteration has its own body scope, released when the iteration
    # ends (its order against the step is checked by own-a17e).
    result.append(own('own-a4-for-iteration-scope', program(
        PRELUDE,
        'int main(){',
        '  for (int i = 1; i <= 3; i++) {',
        '    M a(i);',
        '    event(10 * i);',
        '  }',
        '  return 0;',
        '}'), 0, events=(10, -1, 20, -2, 30, -3)))

    # A4 edge: an object declared by the for initializer belongs to the for's own
    # scope and is released once, after the last iteration, before the next statement.
    result.append(own('own-a4b-for-init-object-spans-loop', program(
        PRELUDE,
        'int main(){',
        '  int i = 0;',
        '  for (M o(7); i < 2; i++) {',
        '    M a(i + 1);',
        '    event(10 + i);',
        '  }',
        '  event(50);',
        '  return 0;',
        '}'), 0, events=(10, -1, 11, -2, -7, 50)))

    # A5: return from a block inside a while body. Iteration 0 releases d(40) and
    # c(30) normally. At i=1 the return value is computed first (event 11, cleanup
    # spec 7 teardown step 1), then each enclosing scope cleans up innermost first
    # (d(41), then the iteration's c(31)), then root locals b, a, and the parameter
    # last (pushed at call entry, library.cpp:2115-2121); main then sees 100 + 11.
    result.append(own('own-a5-return-from-nested-loop-scopes', program(
        PRELUDE,
        'int f(M p){',
        '  M a(1);',
        '  M b(2);',
        '  int i = 0;',
        '  while (i < 5) {',
        '    M c(30 + i);',
        '    {',
        '      M d(40 + i);',
        '      if (i == 1) {',
        '        return event(i + 10);',
        '      }',
        '    }',
        '    i = i + 1;',
        '  }',
        '  return 0;',
        '}',
        'int main(){',
        '  int r = f(mk(7));',
        '  event(100 + r);',
        '  return r;',
        '}'), 11, events=(-40, -30, 11, -41, -31, -2, -1, -7, 111)))

    # A5 variant: return through a for body, its iteration scope and the for's
    # own scope (initializer object o), then the root.
    result.append(own('own-a5b-return-from-for-scopes', program(
        PRELUDE,
        'int f(){',
        '  M a(1);',
        '  int i = 0;',
        '  for (M o(2); i < 5; i++) {',
        '    M b(3 + i);',
        '    if (i == 1) {',
        '      return 50 + i;',
        '    }',
        '  }',
        '  return 0;',
        '}',
        'int main(){',
        '  return f();',
        '}'), 51, events=(-3, -4, -2, -1)))

    # A5 variant through a switch inside a while (switch lowering in the module
    # docstring). The switch value's mkp(60 + i) runs in the wrapper block, so the
    # wrapper owns that automatic object (Rule E, heepalloc.cpp:400-425). i=0: the
    # case 0 section releases s at its break, the wrapper releases -60, then event 70
    # and the body's w(10). i=1: the default section returns; the return value is
    # computed first (51), then the section (t(31)), the synthetic loop's iteration
    # scope (owns nothing), the wrapper (-61), the while body (w(11)) and f's root
    # (a) clean up in that order; main then sees 100 + 51.
    result.append(own('own-a5c-return-from-switch-section-in-loop', program(
        PRELUDE,
        'int f(){',
        '  M a(1);',
        '  int i = 0;',
        '  while (i < 3) {',
        '    M w(10 + i);',
        '    switch (mkp(60 + i).n - 60) {',
        '      case 0:',
        '        M s(20);',
        '        break;',
        '      default:',
        '        M t(30 + i);',
        '        return event(50 + i);',
        '    }',
        '    event(70 + i);',
        '    i = i + 1;',
        '  }',
        '  return 0;',
        '}',
        'int main(){',
        '  int r = f();',
        '  event(100 + r);',
        '  return r;',
        '}'), 51, events=(-20, -60, 70, -10, 51, -31, -61, -11, -1, 151)))

    # Return from a non-block if body: the return runs in the body's scoped_execute
    # scope (library.cpp:843-849), so mkp(5)'s object is transferred to that scope
    # (root.caller of mkp is the env that ran the call), not to main the way a
    # returned pointer would be. Checked: the return value is computed first (5),
    # the object dies inside g (-5) before g's local a and its parameter p, and before
    # main continues (105). LIFO makes "owned by the body scope" and "owned by g's
    # root" give the same order after a return; own-a17c checks the body scope with a
    # following statement.
    result.append(own('own-a5d-return-in-non-block-if-body-auto-dies-in-callee', program(
        PRELUDE,
        'int g(M p, boolean c){',
        '  M a(1);',
        '  if (c) return event(mkp(5).n);',
        '  return 0;',
        '}',
        'int main(){',
        '  int r = g(mk(9), true);',
        '  event(100 + r);',
        '  return r;',
        '}'), 5, events=(5, -5, -1, -9, 105)))

    # A6 (the draft leaves the emitter of 10 unspecified; it is the body's event at
    # i=0): continue at i=1 and break at i=2 still release that iteration's object.
    result.append(own('own-a6-for-continue-break', program(
        PRELUDE,
        'int main(){',
        '  for (int i = 0; i < 5; i++) {',
        '    M a(i + 1);',
        '    if (i == 1) continue;',
        '    if (i == 2) break;',
        '    event(10 * (i + 1));',
        '  }',
        '  return 0;',
        '}'), 0, events=(10, -1, -2, -3)))

    # A6 variant: continue and break leave several nested scopes of a while body;
    # every scope passed is cleaned, innermost first, before the loop acts on the flag.
    result.append(own('own-a6b-while-continue-break-nested', program(
        PRELUDE,
        'int main(){',
        '  int i = 0;',
        '  while (i < 5) {',
        '    M a(i + 1);',
        '    {',
        '      M b(10 + i);',
        '      if (i == 1) { i = i + 1; continue; }',
        '      if (i == 2) { M c(30); break; }',
        '    }',
        '    event(20 + i);',
        '    i = i + 1;',
        '  }',
        '  event(99);',
        '  return i;',
        '}'), 2, events=(-10, 20, -1, -11, -2, -30, -12, -3, 99)))

    # Ownership verify addition 5: break through nested blocks of a while(true)
    # body cleans the iteration scope before the loop consumes the flag.
    result.append(own('own-a6c-break-through-nested-blocks', program(
        PRELUDE,
        'int main(){',
        '  int i = 0;',
        '  while (true) {',
        '    M a(1);',
        '    {',
        '      M b(2);',
        '      if (i == 0) {',
        '        M c(3);',
        '        break;',
        '      }',
        '    }',
        '  }',
        '  event(4);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -1, 4)))

    # A6 edge (LANGUAGE.md switch: every section has its own object scope; a
    # continue in a switch targets the enclosing loop and runs the for step):
    # break leaves the section scope, continue is relayed through the synthetic
    # switch loop (Compiler.java:1696-1720) after the section scope is cleaned and
    # before the iteration scope, and the last section ends with an implicit break.
    result.append(own('own-a6d-switch-section-scopes', program(
        PRELUDE,
        'int main(){',
        '  for (int i = 0; i < 3; i++) {',
        '    M a(10 + i);',
        '    switch (i) {',
        '      case 0:',
        '        M b(20);',
        '        event(1);',
        '        break;',
        '      case 1:',
        '        M c(21);',
        '        continue;',
        '      default:',
        '        M d(22);',
        '        event(3);',
        '    }',
        '    event(100 + i);',
        '  }',
        '  return 0;',
        '}'), 0, events=(1, -20, 100, -10, -21, -11, 3, -22, 102, -12)))

    # A6 edge: a continue in a switch inside a while. The relay 'if(flag) continue'
    # (Compiler.java:1721-1723) targets the while, which has no step, so the next
    # thing after the iteration's cleanup is the while condition (event 200 + i).
    # i=1: section b(20) is released when the section's break leaves it, the wrapper
    # sets continue, event(101) is skipped, and the body releases a(10). i=2: break
    # leaves the section only, so event 102 runs. i=3: the default section ends with
    # the implicit break (Compiler.java:1726-1730).
    result.append(own('own-a6e-while-switch-continue-relay', program(
        PRELUDE,
        'int main(){',
        '  int i = 0;',
        '  while (event(200 + i) > 0 && i < 3) {',
        '    M a(10 + i);',
        '    i = i + 1;',
        '    switch (i) {',
        '      case 1:',
        '        M b(20);',
        '        continue;',
        '      case 2:',
        '        M c(21);',
        '        break;',
        '      default:',
        '        M d(22);',
        '        event(3);',
        '    }',
        '    event(100 + i);',
        '  }',
        '  event(99);',
        '  return i;',
        '}'), 3, events=(200, -20, -10, 201, -21, 102, -11, 202, 3, -22, 103, -12, 203, 99)))

    # A7: a body error runs full cleanup with destructors; the trace has the main
    # frame at the failing division (location not restored on unwinding,
    # library.cpp:261-268), which is on its own line, not at the declaration.
    source = program(
        PRELUDE,
        'int main(){',
        '  M a(1);',
        '  M b(2);',
        '  int z = 0;',
        '  int q =',
        '    1 / z;',
        '  return 0;',
        '}')
    result.append(own('own-a7-error-unwinds-scope', source, events=(-2, -1), error='Division by zero',
                      category='runtime_error', trace=('main',), lines=(line_of(source, '1 / z;'),)))

    # A7 variant: an error inside nested loop scopes unwinds every scope,
    # innermost first, up to the root; main is reported at the division's own line.
    # (T5's dropped destructor errors are checked by own-a7e.)
    source = program(
        PRELUDE,
        'int main(){',
        '  M o(9);',
        '  int z = 0;',
        '  int i = 0;',
        '  while (i < 3) {',
        '    M a(i + 1);',
        '    {',
        '      M b(10 + i);',
        '      if (i == 1) {',
        '        int q =',
        '          1 / z;',
        '      }',
        '    }',
        '    i = i + 1;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-a7b-error-in-loop-unwinds-all-scopes', source, events=(-10, -1, -11, -2, -9),
                      error='Division by zero', category='runtime_error', trace=('main',),
                      lines=(line_of(source, '1 / z;'),)))

    # T1 / vd rule: a failing initializer declares nothing (mk is never called),
    # but the automatic object its argument created already belongs to the block
    # and is released, after nothing else and before the earlier local a. The
    # frame is at the failing division (L1), on a line of its own.
    source = program(
        PRELUDE,
        'int main(){',
        '  int z = 0;',
        '  {',
        '    M a(1);',
        '    M b = mk(mkp(2).n +',
        '      1 / z);',
        '    event(5);',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-a7c-failed-initializer-declares-nothing', source, events=(-2, -1),
                      error='Division by zero', category='runtime_error', trace=('main',),
                      lines=(line_of(source, '1 / z);'),)))

    # Verify cleanup correction 4: a while condition's temporaries die in the
    # condition's statement scope (library.cpp:2211-2216), so a destructor failure
    # there leaves main at the condition expression's position, not at the while
    # (inner nodes were restored on normal exit, the condition's at_location is
    # still active). ~Fail is reported at its division (L1). The error then unwinds
    # main's root (-1). The condition is on its own line, apart from 'while ('.
    source = program(
        PRELUDE + FAIL_CLASS,
        'int main(){',
        '  M a(1);',
        '  int i = 0;',
        '  while (',
        '    mkF(i + 1).n + i < 3) {',
        '    i = i + 1;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-a7d-while-condition-temporary-error-at-condition', source, events=(101, -1),
                      error='Division by zero', category='runtime_error', trace=('Fail::~Fail', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'mkF(i + 1).n + i < 3) {'))))

    # T5 (cleanup spec 9) across nested loop scopes: the overflow in the innermost
    # block is the first error. Unwinding still runs every destructor: Fail(5) emits
    # 105 and fails, but its Division by zero and its frame are discarded
    # (pending_error, library.cpp:183-216); then b(11), a(2) and o. The overflow_error
    # class and main's frame at the overflow line survive.
    source = program(
        PRELUDE + FAIL_CLASS,
        'int main(){',
        '  M o(9);',
        '  int i = 0;',
        '  while (i < 3) {',
        '    M a(i + 1);',
        '    {',
        '      M b(10 + i);',
        '      if (i == 1) {',
        '        Fail f(5);',
        '        int q =',
        '          2147483647 + i;',
        '      }',
        '    }',
        '    i = i + 1;',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-a7e-unwinding-drops-destructor-error-in-nested-scopes', source,
                      events=(-10, -1, 105, -11, -2, -9), error='Integer overflow', category='overflow_error',
                      trace=('main',), lines=(line_of(source, '2147483647 + i;'),)))

    # A8 (first part): an automatic object returned by mkp into a block sits
    # between the locals declared before and after it.
    result.append(own('own-a8-auto-pointer-between-locals', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    M a(1);',
        '    M * q = mkp(2);',
        '    M b(3);',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -1, 5)))

    # A8 (second part): the inner block owns the object it called mkp in.
    result.append(own('own-a8b-auto-pointer-inner-block', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    {',
        '      M * q = mkp(2);',
        '    }',
        '    event(5);',
        '  }',
        '  return 0;',
        '}'), 0, events=(-2, 5)))

    # A9: an automatic object created in an if condition belongs to the env that
    # runs the if (the outer block), not to the condition or the branch.
    result.append(own('own-a9-if-condition-auto-owned-by-enclosing', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    if (mkp(2) != null) {',
        '      event(5);',
        '    }',
        '    event(6);',
        '  }',
        '  event(7);',
        '  return 0;',
        '}'), 0, events=(5, 6, -2, 7)))

    # A9 edge: an else-if is a non-block if as the else body, so it runs in its
    # own scoped_execute scope (library.cpp:2217, 843-849). Its condition's
    # automatic object belongs to that scope and dies when the else body ends;
    # the first condition's object belongs to main's root and dies at exit.
    result.append(own('own-a9b-else-if-condition-auto-owned-by-else-scope', program(
        PRELUDE,
        'int main(){',
        '  if (mkp(4) == null) { event(0); } else if (mkp(5) != null) { event(6); }',
        '  event(7);',
        '  return 0;',
        '}'), 0, events=(6, -5, 7, -4)))

    # A10: ro from a nested scope finds the holder by walking the same-frame
    # lexical chain and transfers to the call-site scope (main's block).
    result.append(own('own-a10-return-auto-from-nested-scope', program(
        PRELUDE,
        'M * f(){',
        '  {',
        '    M * p(1);',
        '    return p;',
        '  }',
        '}',
        'int main(){',
        '  {',
        '    M * q = f();',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(5, -1, 6)))

    # A11, section 8 #7: returning a borrowed pointer transfers nothing; the
    # object stays with the outer block that created it.
    result.append(own('own-a11-borrowed-pointer-return', program(
        PRELUDE,
        'M * g(M * x){',
        '  return x;',
        '}',
        'int main(){',
        '  {',
        '    M * a(1);',
        '    {',
        '      M * b = g(a);',
        '    }',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(5, -1, 6)))

    # Section 8 #6: an automatic object returned through two calls ends up in
    # the outermost call-site scope.
    result.append(own('own-a11b-auto-pointer-chain', program(
        PRELUDE,
        'M * chain1(int v){',
        '  return mkp(v);',
        '}',
        'M * chain2(int v){',
        '  return chain1(v + 1);',
        '}',
        'int main(){',
        '  {',
        '    M * q = chain2(3);',
        '    event(q.n);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(4, -4, 6)))

    # Section 8 #6 (host variant), ownership 3.16: script::invoke passes baseEnv as
    # the caller (library.cpp:1768-1774), so an automatic object returned to the
    # host is transferred to baseEnv, survives the call, and is released by
    # destroy's baseEnv cleanup. It is the first heap allocation (start 1).
    result.append(own('own-a11g-auto-pointer-returned-to-host-owned-by-base', program(
        PRELUDE,
        'M * chain1(int v){',
        '  return mkp(v);',
        '}',
        'M * chain2(int v):0040{',
        '  return chain1(v + 1);',
        '}',
        'int main(){',
        '  return 0;',
        '}'), '0000000000000001', tag=7, events=(), teardown_events=(-4,),
        options=['--function', '0x0fff0040', '--arg', 'i:3']))

    # Section 8 #6 through reflection: reflect_invoke_function calls the target
    # from the scope executing the reflected call (internelFunctions.cpp:39-60,
    # function::invoke with that environment), so the automatic object the
    # target returns belongs to that scope and ends with it: the inner block
    # first, then main's root after event 3. main uses no object itself.
    result.append(own('own-a11h-reflected-auto-pointer-owned-by-calling-scope', program(
        PRELUDE,
        'M * made(int v):0f16{',
        '  return mkp(v);',
        '}',
        'int main(){',
        '  {',
        '    address p = reflect_invoke_function<address>(268373782, 7);',
        '    event(1);',
        '  }',
        '  event(2);',
        '  address q = reflect_invoke_function<address>(268373782, 8);',
        '  event(3);',
        '  return 0;',
        '}'), 0, events=(1, -7, 2, 3, -8)))

    # Section 8 #10, ownership verify addition 15: the pointer result is
    # transferred to the call-site block at ro, before g's root cleanup fails, so
    # the block releases it while unwinding, before main's root local.
    # Trace: ~Fail at its division (L1), g at its return (return_location, L4),
    # main at the g( call node (verify cleanup correction 3), which is on its own
    # line, apart from the 'M * p =' declaration statement.
    source = program(
        PRELUDE + FAIL_CLASS,
        'M * g(){',
        '  Fail bad(2);',
        '  M * out(6);',
        '  return out;',
        '}',
        'int main(){',
        '  M keep(1);',
        '  {',
        '    M * p =',
        '      g();',
        '    event(5);',
        '  }',
        '  return 0;',
        '}')
    result.append(own('own-a11c-pointer-result-kept-by-call-site-block', source, events=(102, -6, -1),
                      error='Division by zero', category='runtime_error', trace=('Fail::~Fail', 'g', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'return out;'), line_of(source, 'g();'))))

    # Section 8 #9: the exported literal is already a temporary of main's frame
    # when f's root cleanup fails; it dies at main's statement end, then main's local.
    # main is reported at the f( call node, on its own line apart from 'event('
    # (verify cleanup correction 3).
    source = program(
        PRELUDE + FAIL_CLASS,
        'M f(){',
        '  Fail bad(2);',
        '  M out(5);',
        '  return out;',
        '}',
        'int main(){',
        '  M a(1);',
        '  event(',
        '    f().n);',
        '  return 0;',
        '}')
    result.append(own('own-a11d-literal-result-survives-callee-failure', source, events=(102, -5, -1),
                      error='Division by zero', category='runtime_error', trace=('Fail::~Fail', 'f', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'return out;'), line_of(source, 'f().n);'))))

    # Verify cleanup correction 1: blocks carry no position, and location =
    # return_location runs only after the body completes normally, so an inner
    # block's cleanup failure after return reports f at its header. main is at the
    # f( call node, on its own line.
    source = program(
        PRELUDE + FAIL_CLASS,
        'int f(){',
        '  {',
        '    Fail x(1);',
        '    return 3;',
        '  }',
        '}',
        'int main(){',
        '  int r =',
        '    f();',
        '  return r;',
        '}')
    result.append(own('own-a11e-inner-block-cleanup-error-at-header', source, events=(101,),
                      error='Division by zero', category='runtime_error', trace=('Fail::~Fail', 'f', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'int f(){'), line_of(source, 'f();'))))

    # Contrast for correction 1: a root-scope failure after return reports the
    # return (L4); main is at the g( call node, on its own line.
    source = program(
        PRELUDE + FAIL_CLASS,
        'int g(){',
        '  Fail y(2);',
        '  return 4;',
        '}',
        'int main(){',
        '  int r =',
        '    g();',
        '  return r;',
        '}')
    result.append(own('own-a11f-root-cleanup-error-at-return', source, events=(102,),
                      error='Division by zero', category='runtime_error', trace=('Fail::~Fail', 'g', 'main'),
                      lines=(line_of(source, FAIL_MARK), line_of(source, 'return 4;'), line_of(source, 'g();'))))

    # A12, ownership section 5: an unwrapped constructor runs before the rest of
    # the declaring block and its destructor, including a nested block inside it,
    # when that block exits. A12's "no fields" cannot be written (every class
    # needs a field, Compiler.java:2487; verify cleanup correction 30), so V has
    # only a scalar field.
    result.append(own('own-a12b-unwrapped-ctor-dtor', program(
        EVENT,
        'class V { int n; V(int v){ n = v; event(n * 10 + 2); } '
        '~V(){ { event(1003); } event(0 - (n * 10 + 2)); } }',
        'int main(){',
        '  {',
        '    {',
        '      V u(1);',
        '      event(4);',
        '    }',
        '  }',
        '  return 0;',
        '}'), 0, events=(12, 4, 1003, -12)))

    # A12 (with a literal field), ownership section 5: a base-less class with an
    # owned field wraps both constructor and destructor bodies in #cleanup. The
    # field initializer runs before the constructor body, and the field's
    # destructor event follows the destructor body.
    result.append(own('own-a12c-wrapped-ctor-dtor-literal-field', program(
        PRELUDE,
        'class W {',
        '  M f(3);',
        '  int k;',
        '  W(){ k = f.n; event(k); }',
        '  ~W(){ { event(1004); } event(13); }',
        '}',
        'int main(){',
        '  {',
        '    W w;',
        '    event(50);',
        '  }',
        '  return 0;',
        '}'), 0, events=(3, 50, 1004, 13, -3)))

    # Verify ownership correction 16: derived constructors/destructors run base
    # constructor first and base destructor last. B's events carry 100 * b so the
    # base parts of d (b = 1) and e (b = 2) are told apart; E's owned field ends
    # after ~E and before ~B. (Each event keeps the scope depth the case recorded
    # while getDepth existed, so the sequence is unchanged.)
    result.append(own('own-a12d-derived-ctor-dtor', program(
        PRELUDE,
        'class B { int b; B(int v){ b = v; event(100 * b + 12); } '
        '~B(){ event(100 * b + 22); } }',
        'class D : B { int z; D(int v) : B(v) { event(34); } ~D(){ event(43); } }',
        'class E : B { M f(7); E(int v) : B(v) { event(54); } ~E(){ event(63); } }',
        'class N { int n; N(int v){ n = v; event(72); } }',
        'class Q : N { int m; Q(int v) : N(v) { event(83); } ~Q(){ event(92); } }',
        'int main(){',
        '  {',
        '    D d(1);',
        '    E e(2);',
        '    Q q(3);',
        '  }',
        '  event(999);',
        '  return 0;',
        '}'), 0, events=(112, 34, 212, 54, 72, 83, 92, 63, -7, 222, 43, 122, 999)))

    # Section 8 #30: objects created inside a destructor belong to the
    # destructor's own scopes and are released LIFO when it exits.
    result.append(own('own-a12e-objects-created-in-destructor', program(
        EVENT + U_CLASS,
        'class Z { int n; Z(int v){ n = v; } ~Z(){ U inside(101); U * a(201); event(2); } }',
        'int main(){',
        '  {',
        '    Z z(1);',
        '  }',
        '  event(5);',
        '  return 0;',
        '}'), 0, events=(1012, 2012, 2, -2012, -1012, 5)))

    # Ownership sections 5/6.5: a destructor at scope exit runs at the cleanup
    # initiator's active_calls + 1 (probe adds its own level), whether the
    # object ends at a block, at a callee's root, or as a statement temporary.
    # main runs at active_calls 1 (script::invoke has
    # no guard, library.cpp:1768-1773). Swept: the driver's terminal_shape drops the
    # probes' remaining_steps.
    result.append(own('own-a12f-destructor-active-calls', program(
        EVENT + PROBE,
        'class PR { int n; PR(int v){ n = v; } ~PR(){ probe(n * 10 + 2); } }',
        'PR mkPR(int v){ PR t(v); return t; }',
        'void f(){ PR b(2); }',
        'int main(){',
        '  {',
        '    PR a(1);',
        '  }',
        '  f();',
        '  event(mkPR(3).n);',
        '  probe(2);',
        '  return 0;',
        '}'), 0, partial=True, raw_events=[
            {'callback': 5, 'arguments': [typed(0, 12)], 'active_calls': 3},
            {'callback': 5, 'arguments': [typed(0, 22)], 'active_calls': 4},
            {'callback': 1, 'arguments': [typed(0, 3)]},
            {'callback': 5, 'arguments': [typed(0, 32)], 'active_calls': 3},
            {'callback': 5, 'arguments': [typed(0, 2)], 'active_calls': 2}]))

    # Section 8 #4/#5: a discarded literal result dies at its statement end; a
    # discarded automatic result stays with the call-site scope, LIFO with locals.
    result.append(own('own-a13-discarded-results-literal-vs-auto', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    M a(1);',
        '    mk(4);',
        '    event(5);',
        '    mkp(6);',
        '    event(7);',
        '    M b(2);',
        '    event(8);',
        '  }',
        '  event(9);',
        '  return 0;',
        '}'), 0, events=(-4, 5, 7, 8, -2, -6, -1, 9)))

    # Section 8 #1 (verify ownership correction 22): a literal returned from a
    # nested scope is moved out, not copied, and is not destroyed by that scope;
    # c keeps the constructed id 1 and dies once, at main's exit.
    result.append(own('own-a14-return-literal-from-nested-scope', program(
        EVENT + T_CLASS,
        'T f(){',
        '  {',
        '    T r(3);',
        '    return r;',
        '  }',
        '}',
        'address main(){',
        '  T c = f();',
        '  event(c.n);',
        '  return address(c.self());',
        '}'), block_address(1), tag=7, events=(3, -3)))

    # Section 8 #2/#11: a returned parameter is moved: no destructor at callee
    # exit and no new id (d keeps the argument copy's id 2, the returned address;
    # e's single -15 shows its parameter was moved too). The caller's a is unchanged.
    result.append(own('own-a14b-return-parameter-moves', program(
        EVENT + T_CLASS,
        'T pass(T p){',
        '  p.n = p.n + 10;',
        '  event(p.n);',
        '  return p;',
        '}',
        'address main(){',
        '  T a(1);',
        '  T d = pass(a);',
        '  T e = pass(mkT(5));',
        '  event(a.n);',
        '  return address(d.self());',
        '}'), block_address(2), tag=7, events=(11, 15, 1, -15, -11, -1)))

    # Section 8 #11: a by-value parameter's destructor runs after every callee
    # local and sees the callee's modification.
    result.append(own('own-a14c-by-value-param-dies-after-locals', program(
        PRELUDE,
        'void bv(M p){',
        '  M l(9);',
        '  p.n = p.n + 100;',
        '}',
        'int main(){',
        '  M a(1);',
        '  bv(a);',
        '  event(a.n);',
        '  return 0;',
        '}'), 0, events=(-9, -101, 1, -1)))

    # T2: return a exports a into main's frame, the inner scope releases 3, f's
    # root releases the automatic 2 and then the parameter 9; a fresh vd moves
    # the result into r, which dies at main's exit.
    T2_CALLEE = ('M f(M p){',
                 '  M a(1);',
                 '  M * h(2);',
                 '  {',
                 '    M inner(3);',
                 '    return a;',
                 '  }',
                 '}')
    result.append(own('own-a15-t2-fresh-declaration-moves-result', program(
        PRELUDE, *T2_CALLEE,
        'int main(){',
        '  M r = f(mk(9));',
        '  event(100 + r.n);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -9, 101, -1)))

    # spec correction: T2's "the caller's store moves the result into r" holds only
    # for a fresh declaration (verify cleanup correction 25). With r already owning
    # an object, r = f(...) is vs, which assigns in place (blocks.cpp:564-601,
    # library.cpp:2169-2172); the returned temporary dies at the statement end
    # (-1), r keeps its own block with the copied n = 1 and its old value gets no
    # destructor (-7 never occurs). r is then changed to 41, so its exit event -41
    # is told apart from the temporary's -1.
    result.append(own('own-a15b-t2-assign-into-owner-in-place', program(
        PRELUDE, *T2_CALLEE,
        'int main(){',
        '  M r(7);',
        '  r = f(mk(9));',
        '  r.n = r.n + 40;',
        '  event(100 + r.n);',
        '  return 0;',
        '}'), 0, events=(-3, -2, -9, -1, 141, -41)))

    # Ownership verify addition 5: the return statement's own temporaries die in
    # its statement scope, before the enclosing block and root locals.
    result.append(own('own-a16-return-temporary-dies-before-locals', program(
        PRELUDE,
        'int f(){',
        '  M a(1);',
        '  {',
        '    M b(2);',
        '    return mk(3).n;',
        '  }',
        '}',
        'int main(){',
        '  int r = f();',
        '  event(r);',
        '  return r;',
        '}'), 3, events=(-3, -2, -1, 3)))

    # Verify cleanup addition 2: automatic objects returned from calls in a while
    # condition belong to the env running the while and accumulate until it exits.
    result.append(own('own-a17-while-condition-autos-accumulate', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    int i = 1;',
        '    while (mkp(i) != null && i < 4) {',
        '      i = i + 1;',
        '    }',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(5, -4, -3, -2, -1, 6)))

    # Verify cleanup addition 2: an automatic object returned into a call argument
    # is not statement-scoped; it lives until the call site's scope exits.
    result.append(own('own-a17b-argument-auto-lives-to-scope-exit', program(
        PRELUDE,
        'int main(){',
        '  {',
        '    event(mkp(1).n);',
        '    event(5);',
        '  }',
        '  event(6);',
        '  return 0;',
        '}'), 0, events=(1, 5, -1, 6)))

    # Verify cleanup addition 2: in a non-block if or while body the call site is
    # the body's scoped_execute scope, which ends with the body; a block body owns
    # it until the block ends; an if condition at the root hands it to the root.
    result.append(own('own-a17c-body-scope-owns-autos', program(
        PRELUDE,
        'int main(){',
        '  boolean yes = true;',
        '  if (mkp(9) != null) { event(10); }',
        '  if (yes) event(mkp(4).n);',
        '  event(5);',
        '  if (yes) { event(mkp(7).n); event(8); }',
        '  int i = 0;',
        '  while (i < 3) i = i + mkp(i + 1).n;',
        '  event(100 + i);',
        '  return 0;',
        '}'), 0, events=(10, 4, -4, 5, 7, 8, -7, -1, -2, 103, -9)))

    # Verify cleanup addition 2, for loops: unlike a while condition (own-a17), a
    # for condition runs as 'if(!cond) break' inside the iteration scope
    # (Compiler.java:1414-1424), so its automatic object is released at the end of
    # its own iteration, after the body block's b; the object of the last, failing
    # test (3) is released when the break leaves that iteration scope, before event 50.
    result.append(own('own-a17d-for-condition-autos-released-per-iteration', program(
        PRELUDE,
        'int main(){',
        '  for (int i = 0; mkp(i + 1) != null && i < 2; i++) {',
        '    M b(10 + i);',
        '    event(20 + i);',
        '  }',
        '  event(50);',
        '  return 0;',
        '}'), 0, events=(20, -10, -1, 21, -11, -2, -3, 50)))

    # Verify cleanup addition 2, for loops: the step is the non-block else of
    # if(#for-first), so it runs in its own scoped_execute scope (library.cpp:843-849)
    # and its automatic object is released right after the step: after the previous
    # body's b and before the next body (or the loop exit).
    result.append(own('own-a17e-for-step-autos-released-after-step', program(
        PRELUDE,
        'int main(){',
        '  for (int i = 0; i < 2; i = mkp(i + 1).n) {',
        '    M b(10 + i);',
        '    event(20 + i);',
        '  }',
        '  event(50);',
        '  return 0;',
        '}'), 0, events=(20, -10, -1, 21, -11, -2, 50)))

    return result
