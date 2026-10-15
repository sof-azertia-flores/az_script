> Completeness review of DESIGN.md. Corrections adopted by the final plan are summarized in [STATUS.en.md](STATUS.en.md); items 3 and 4 (stable-ID tooling, default-namespace warning) were declined by the user.

## Completeness critique of the unified plan

I checked the plan against the code at HEAD 76fef14 on `claude/confident-galileo-fic3s1` and the earlier review's handback, which is the source of the problem list. That handback is in the subagent transcript `agent-abb069cae6d0ce136.jsonl`.

### 1. Coverage table

| # | Problem in the user's scope | Status | Where in the plan / gap |
|---|---|---|---|
| T-a | Untyped param accepts a class pointer; `mem_get` returns `any`; no runtime check on typed reads (o4/o5) | **Partial** | C22 closes untyped params/var/#gvar/raw stores and adds the `f_get` guard. Residuals: `mem_free`/`make_free`/`mem_send_up(address(p))` still release or re-own object storage. `f_free` only rejects literal blocks (heepalloc.cpp:342-346, :364-381), and `heap::free` drops the object record without running its destructor (heepalloc.cpp:195-203). JNI `ACputMem*`/`memFree` write or free object slots with any type (abdjni/lib.cpp:734-780). `oa` accepts any allocation start (heepalloc.cpp:214-220), so freed storage reused as a raw block is read as a typed field. The runtime parses `vd` declared-type and then discards it (library.cpp:685-686 vs :1257-1268). |
| T-b | `var raw = p; mem_get(raw)` | Covered | C22 |
| T-c | `reflect_invoke_function<P*>` laundering (c7) | Covered (documented residual: no class identity) | C23 |
| T-d | `return 3.7` from an int function (s1, fz f28) | Covered | C18; today only `returnCompatible` (Compiler.java:286-288) and the runtime (library.cpp:469-490) check it |
| T-e | `"abc"==5`, `true==1`, numeric conditions (s2, f43/f44) | Covered | C15 |
| T-f | Structural class typing incl. base→derived (c5) | Covered | C10. Generic applications are cached per key (Compiler.java:1333-1337), so identity works for them. |
| T-g | Parser/compiler drift: `class any` | Covered | C09 |
| | Reserved names | Covered | C09 |
| | Hint validation | Covered | C09 parity test |
| | **Hex parsing** | **Partial** | C09 only says "AST hex stays strict". Two implementations remain: `GeneraterJson.hexadecimal` (:392-399, accepts `0x` and unlimited leading zeros) and `Compiler.hexId` (:1756-1759). Reserved-namespace checks also differ between parser :167/:179 and compiler :1804/:1813. |
| T-h | Untyped `#gvar` (o3) | Covered | C11 + C22 |
| D1 | Wrong file/line through includes (d1/d2/d5) | Covered | C01 |
| D2 | Missing locations (semantic, lexer :408, preprocessor :204/:215-216, AST 320, `Unknown type`, `Unregistered class function` :1540, missing return :1933) | Covered | C01 |
| D3 | Stop at first error (d3) | Covered | C02 |
| D4 | No warnings (`if (x = 5)`) | Covered | C03. Once C15 lands this probe becomes a type error, see §2.9. |
| D5 | Generic "Malformed script JSON" (:1769-1772) | Covered | C02 |
| D6 | compile-json raw Java messages | Covered | C01 (Gson) and C02 (AstShape) |
| D7 | Decimal IDs | Covered | C01 (compiler), C04 (library.cpp:88-96, :274-278, :824, :862, :954, :960, :1036, :1079-1114; Caller.java:33) |
| L1 | 130-string concatenation | Covered | C25 spilling; C26 above 128 operands |
| L2 | Else-if ladder of 120 | **Partial** | C27 keeps nested nodes up to 128 conditions, but the nested form already overflows at about 120: each `else if` adds one `if` record (library.cpp:793-795), the limit is 128 with 3 levels above the body (TreeLimits.java), elif100 compiles and elif130 does not. Ladders of about 120-128, or shorter ones inside loops, still fail. 200 is covered. |
| L3 | 320 AST guard without location | Covered | C01 |
| L4 | Quadratic locals | Covered | C08 (Compiler.java:121-145) |
| L5 | 20k functions | **Partial** | C08 says "profile first". No hotspot or target is named, and the time is outside `LogicEnvironment`. |
| P1 | Absolute and `../` includes, content leak (p2/p3) | Covered | C06 |
| P2 | `#pragma once` (p4) | Covered | C05 |
| P3 | Missing-include message | Covered | C01/C06 |
| P4 | Object-like macros only | **Partial / decision** | C05 only improves the error; function-like macros are not implemented. The user's "fix ALL" has not been confirmed for this. |
| P5 | Default 0xfff namespace collisions (m1/m2/m3) | **Partial** | C04 makes the runtime message actionable, but nothing detects the collision at compile time. |
| P6 | Hand-assigned IDs (88 in containers.include.azs, 52 in math.include.azs) | **Missing** | Docs only (C07) |
| P7 | Shifting automatic IDs | **Missing** | Positions are sequential in parse order (GeneraterJson.java:879-887) and factories take the lowest free positions (Compiler.java:1552-1558). §7.7 wrongly says `--keep-ids` needs a format bump; it is compiler-only. |
| P8 | `#namespace 0` accepted (m4, f26) | Covered | C07 |
| F1-F6 | switch, ternary, bitwise/shift, casts, strings, virtual methods (incl. g1 generic bounds) | Covered | C20, C19, C16, C17 (functional casts, no downcasts), C21, C24 (dispatch through `T`) |
| R1 | function:line in errors, stack, JNI/Java message, strip flag | Covered | C13, C14 |
| R2 | Single exec bump | Covered | C13 |
| R3 | All hard-coded version sites | **Partial** | See §2.7 |
| X1 | Each commit buildable and testable on its own | **Partial** | C09/C10 ordering break (§2.8); C03/C15 test churn |
| X2 | Chinese + English docs | Mostly | Several snapshot sites missing in C24 (§2.7) |
| X3 | Sanitizer for interpreter changes | Mostly | C04 lacks V2. JNI is never sanitized (build_and_test.py:90). |
| X4 | Java/C++ byte identity | **Partial** | AbdRegression runs in V1, but the AGENTS rule "source → AST → compile-json → ABD byte-identical" is only stated for C01, C11 and C13. |
| X5 | No compatibility layer | Covered | — |

### 2. Concrete additions and corrections

1. **C22, close the remaining raw-memory paths** (interpreter/heepalloc.cpp, abdjni/lib.cpp).
   - Reject object-tagged ranges in `f_free`, `f_make_free` and `f_send_up`: "use delete".
   - Reject them in JNI `Caller20.memFree` and `Caller220.ACputMem*`. The alternative is to document these JNI calls as trusted and test that.
   - Make `oa` (`heap::object_address`) require an object-tagged allocation or a literal block. This catches "free, then raw reuse, then typed read".
   - Tests: runtime_tests; run_jni_tests (put/free on an object raises IllegalArgumentException); address_end_to_end (`mem_free(address(p))` is rejected).

2. **C27 threshold** (compiler/src/main/java/azertia/script/Compiler.java, `if` case at :841-850). Let the compiler choose: emit the nested `if` when the remaining ABD budget allows, and the flat `SyntheticLoop` form when it does not. Do this both for `if-chain` and for an existing nested else-if shape. This keeps AST and ABD byte-identical for every program that compiles today and fixes ladders of about 120-128, including ladders nested in loops. The parser's `if-chain` above 128 is still needed for the 320 guard. Add `DepthLimitsTest` cases for 121, 125, 128, and 110 inside three loops.

3. **New commit after C07 for P6/P7, with no format change.**
   - `compile --emit-header FILE`: write `extern` and class declarations with the assigned full IDs.
   - `--id-lock FILE`: a JSON map from name to position that is reused across builds. Removed names stay reserved and new names take fresh positions. Without the flag, output stays byte-identical.
   - A warning `unstable-function-id` for main-less modules in a fixed namespace whose functions use automatic IDs.
   - Affected files: Main.java, GeneraterJson.java `assignFunctionIds` (:839-893), Compiler.java `addClassFactories` (:1552-1558), HINT_LINKING.md/.en.md, MainTest, hint_linking_end_to_end.py.
   - Fix §7.7 so it no longer says keep-ids needs a format bump.

4. **P5, compile-time warning.** Add `default-namespace` to the C03 registry: a module with no `main` that defines non-lifecycle functions in 0x0fff gets the warning. The m2 probe is the regression. Any test library modules that have no `#namespace` must be adjusted.

5. **P4.** Add an open decision #8: implement simple non-variadic function-like macros (no `#`/`##`, existing depth and size caps) in C05, or keep the C05 diagnostic only.

6. **C09 hex and namespace rules.** Add `SourceRules.hex(text, digits)` and `SourceRules.reservedNamespace(ns, context)`, used by GeneraterJson.java:166/:178/:1038 and Compiler.java:1756/:1804/:1813/:1824/:1850. Add a parity test, e.g. `#namespace 0x0005151` and `:00000000abcd`. Also reject `Character.isIdentifierIgnorable` and Cf characters such as U+202E in identifiers. Today GeneraterJson.java:368-375/:493-494 and Compiler.java:1286-1293 accept them, and they would reach C13 debug names.

7. **Version sites the plan omits.**
   - **C13 (exec v10):**
     - ExecCodec.java:13 (doc comment) and :392 ("expected exec v9 AZSCRIPT header").
     - EXEC_FORMAT.md/.en.md:141 ("exactly ten fields").
     - LANGUAGE.md:465 and LANGUAGE.en.md:463.
     - README.md:226 and README.en.md:220.
     - compact_exec_end_to_end.py:2/:249 docstrings; library.cpp:491 comment.
     - Opcode ranges for every opcode commit: compact_exec_end_to_end.py:333, :538 (`<= 42`) and :552; test_distribution.py:364 `set(range(36,43))`; test_export_distribution.py:42 `range(36,43)`, which must grow for the context-bearing 51/52. The 43 sentinel is at :47, not :42.
   - **C24 (snapshot v10):**
     - run_jni_tests.py:188, :248, :255, :370 and the asserts at :499-:526. Add 9 to the rejected versions at :316-322 and :391.
     - buffers_snapshot.py:2, :114, :124; compact_exec_end_to_end.py:746.
     - abdjni/lib.cpp:504/:554-555. Keep the exact code shape, because test_export_distribution.py:74-80 scrapes it with a regex.
     - LANGUAGE.md:465/:475 and LANGUAGE.en.md:463/:473; README.md:226 and README.en.md:220 (the snapshot sentence).
     - EXEC_FORMAT :111/:121/:135; HINT_LINKING :108/:140; QUICKSTART :126; USAGE.md:175 and USAGE.en.md:173; abdJavaInvoker README :69/:71.
   - **Grep gate:** in C13 and C24, outside VALIDATION*, `grep -rnE "\bv9\b|exec-version'?\]?\s*==?\s*9|snapshot version.{0,8}\b9\b"` should match nothing except the rejection lists.

8. **Ordering bug between C09 and C10.** `validateClassLayouts()` returns the layouts that feed `validateDuplicateExterns` and the structural `sameSourceType` (GeneraterJson.java:701-702, :791-838). C09 deletes the former while C10 is the commit that makes `sameSourceType` string equality, so C09 does not compile or behave on its own. Either swap C09 and C10, or keep a layout-only helper (no errors) in C09 until C10.

9. **C03/C15 churn.** The `assign-in-condition` test should use boolean targets (`boolean b; if (b = f())`). With `int`, C15 turns it into an error. Expect inline sources in tests/*.py that assert `not result.stderr` (e.g. buffers_end_to_end.py:57/:60, classes_end_to_end.py:61) to need edits for `unreachable-code`; the file-corpus test does not cover inline sources.

10. **Validation sets.**
    - Add V3 to C02 (exit codes 0/1/2, summary line, new flags through the launcher), and to C15 and C18 (runtime semantics of the packaged examples and stdlib).
    - Add V2 to C04 (library.cpp insert/flush failure paths).
    - Note that the JNI parts of C13, C14 and C24 get no ASan coverage.

11. **Byte identity per feature.**
    - Every new e2e suite (type_safety, language_ops, runtime_trace, virtual_methods, limits) should, for each case, compile → `--ast` → compile-json and assert identical ABD, following buffers_end_to_end.py:52-61.
    - C01 must state whether `sources` is required in compile-json input. Forged ASTs (loops_end_to_end.py:159-168, scratchpad `out/bad-*.json`) lack it, so define a default label or update those tests.

12. **Smaller items.**
    - Update `Main.run` help (Main.java:26) and the launcher help for every new flag (C02, C03, C06, C13).
    - C16: compute shift adjacency from preprocessed-text offsets, not source-mapped columns, because macro tokens share a mapped column.
    - C19: add colon-glue cases `c ? 1 : 1.5`, `: 2e+3`, `: 2f` and `: true`. The glue run (GeneraterJson.java:456-462) stops at `.`/`+`, so "number scan covers the run" is ambiguous. Document why `ifExpression` cannot be reused: it calls `scoped_execute` per arm (library.cpp:1316), which ends arm temporaries. AGENTS requires this justification for new runtime extensions.
    - C21: say how `contains` and `index_of(n[, from])` lower onto 0x0b..0x13. `builtin()` indexes a dense vector by `id & 0xffff` (internelFunctions.cpp:76-83), so 0x14 needs 0x0b..0x13 present.
    - C25: also compare `ExecShape` with the C++ `logical_depth` rule (library.cpp:664, opcode 2 params at +2), not only with the Java codec.

### 3. Unverifiable or risky

- The 20k-function hotspot is unknown, so the 8x input / 10x work target may not hold without a named fix.
- The project version (CMakeLists.txt:2, build.gradle:3, 0.2.0) is not decided despite a public C++ ABI break in C13, C14 and C24. Add it as an open decision.
- Exec v10 records no global declared types. Typed globals (C11) are therefore compile-time only, and snapshot restore and hosts cannot validate them. Adding a `global_types` field now is cheap; later it needs another bump. This is optional.
- C01 and C25 are very large; regressions in the shared catch sites are likely. I counted 23 `->execute(` occurrences in library.cpp, matching C14.
- Memory-safety probes (c1-c4, o1-o2, f32) remain out of scope (§7.1). Typed pointer globals make the o3 escape easier.

### Critical Files for Implementation
- compiler/src/main/java/azertia/script/Compiler.java
- compiler/src/main/java/azertia/script/GeneraterJson.java
- interpreter/library.cpp
- interpreter/heepalloc.cpp
- abdjni/lib.cpp