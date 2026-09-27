# Inline Buffers, Default Comparison, and Class Operators (2026-09-27)

[中文](VALIDATION.md) | English

Implemented owning `buffer<T>` with contiguous direct slots, independently identified class-element views, deep copies, nested values, empty defaults, explicit capacity management, and reverse cleanup. Generic contexts now carry direct slot width, placement bindings, and element contexts. Added `value_compare<T>` and class `+ - * / [] []= ()`, lowered to ordinary member calls with generics, inheritance, externs, and out-of-class implementations. Exec and JNI snapshots are v9 and reject older formats; source metadata versioning and the existing AZS container implementations are unchanged.

Sequential `python3 tools/build_and_test.py --offline` and `python3 tools/build_and_test.py --offline --sanitize` both exited 0 without ASan/UBSan reports. Java unit tests passed 107/107. The new buffer/operator suite passed 79 ordinary-build checks and 77 sanitizer checks without JNI. Real-JVM validation passed 2654 assertions, 800 calls across four threads, and 1000-module linking on a small stack. Buffer snapshots cover continuous storage, view-address remapping, nested/empty values, post-restore construction/destruction, and atomic rejection of 22 corrupted states.

The final native suite contains 736 checks covering actual contiguous slot segments, expired views after growth, independent copies, construction rollback, destructor failure, budget exhaustion, and reentrant destruction. Whole-buffer assignment cleans the old value before switching. If a destructor callback destroys the literal or heap parent, it cleans the candidate and retains the original error. The final raw-slot guard prevents bypassing buffer length and element-state checks while preserving class-view field access; independent ordinary and ASan/UBSan verification passed. After rebuilding the final guard, native/JNI tests and both ordinary/sanitized buffer end-to-end suites passed again.

Source → AST → ABD byte identity, Java/C++ data roundtrips, 95 independent wire cases, and existing class, generics, inheritance, loop, hint, math, container, and signed-native-library regressions passed. Type queries cache within one query to prevent exponential recursion on long flat operator expressions. Existing depth/file limits remain covered. Snapshot preflight additionally bounds cumulative buffer capacity times stride, with explicit failure when saving or restoring oversized states.

Updated Chinese and English language, execution-format, hint, C++/JNI integration, and distribution usage guides. After CMake installation and SDK relocation, both static and shared C++ consumers exercised the new features and returned 42. New public headers compile independently, and dynamic loading was verified against the relocated directory. All 143 local project-document links, 12 distribution-template regressions, `git diff --check`, and whitespace checks on new files passed. No commit, push, or distribution export was performed.

Logs: `build/buffer-full-final.log`, `build/buffer-sanitize.log`, `build/buffer-final-guard.log`; C++ installation/relocation results are in `build/buffer-install-validation/`.

# Generics and Runtime Reflection (2026-09-27)

Implemented shared-body generic classes, functions, member methods, and parameterized inheritance, including explicit type arguments, argument inference, single class bounds, invariance, out-of-class definitions, and generic extern matching. Hidden operation contexts preserve concrete defaults, default factories, and return categories. Destructor bindings survive copy, move, cross-library returns, error cleanup, and snapshot restoration. Added invocation by actual function ID and two hint queries, checking actual return tags and rejecting unbound generic or internal reflection targets. Exec and JNI snapshots are now v8 and reject older versions; source metadata versioning is unchanged.

Sequential `python3 tools/build_and_test.py --offline` and `python3 tools/build_and_test.py --offline --sanitize` both exited 0 with no ASan/UBSan reports. All 95 Java unit tests and 625 native runtime checks passed. The new generic/reflection end-to-end suite passed 71 ordinary-build checks and 62 sanitizer checks without JNI. Real-JVM validation in the ordinary build passed 2648 assertions, 800 calls from four worker threads, and linking 1000 modules on a small stack. Additional JNI cases cover generic-object snapshots and atomic rejection of eight corrupted context variants.

Source → readable AST → ABD byte identity, Java/C++ ABD fixture roundtrips, and 94 independent execution-format cases passed. Regressions cover shared function IDs, scalar/object defaults, recursive and cross-library generic calls, distinct ownership behavior for raw-address/object returns, construction rollback, destructor errors, budgets, host callbacks, reflection boundaries, and retry after failed linking. Added regressions for forward-bound cache validation, the 64-level limit for type strings in handwritten ASTs, and long inheritance chains on a small stack. Existing class, loop, inheritance, address, math, hint, and signed-native-library suites continue to pass.

Updated Chinese and English language, execution-format, hint, JNI, and distribution guides, with standalone generic/reflection and independently compiled generic-library examples. All 123 local repository Markdown file links resolve; 12 export-tool regressions verify package links and commands. `git diff --check` and whitespace checks for new files passed. No commit, push, or distribution re-export was performed. Logs: `build/generics-validation.log`, `build/generics-sanitize.log`.

# Bilingual Documentation and Distribution Export (2026-09-27)

All 15 project Markdown documents now have sibling `.en.md` English editions and two-way language links. The original third-party English license is unchanged. Translations cover current APIs, formats, language/math guides, host integration, development, distribution usage, and historical validation. Each pair's heading and code-block counts were checked; example code is unchanged except translated comments. Source links, English tables, whitespace in untracked files, and `git diff --check` were verified.

Distributions now carry nine document pairs. The English language guide uses package commands and relocated links. An export regression checks language navigation, local links, and package commands; all 12 `python3 tests/test_export_distribution.py` checks passed. Full `python3 tools/build_and_test.py --offline` exited 0, including real JNI and AST/ABD roundtrips. This change only affects documentation and its export/acceptance logic; sanitizer was not rerun.

`python3 tools/export_distribution.py build/bilingual-distribution --offline --force` produced a macOS arm64 bilingual acceptance package. All 17 relocation checks passed; 159 manifest file sizes/SHA-256 digests matched; 82 local package links and their used heading anchors were valid. Existing dist/ was untouched. Logs: build/bilingual-validation.log, build/bilingual-export-unit.log, build/bilingual-export-final.log. Check report: build/bilingual-docs-check.json.

# Signed Native Library Update Merge (2026-09-27)

Local main fast-forwarded from 7ff1d0a to remote 4508642, adding signed native loading, trusted-public-key APIs, and plugin compilation/signing tools. Exec and JNI snapshots remain v7. Existing C (*) implementation, tests, documentation, deletion of compiler/std.azs, and untracked AGENTS.md were preserved. Original patch additions/deletions were checked individually; no text conflicts occurred.

Actual builds exposed three integration issues, now fixed: a duplicate macOS path-query declaration rejected by Clang; native tests hardcoding .so despite macOS suffix normalization; and distribution acceptance still assuming a static interpreter. Standalone execution now carries its shared runtime so plugins and hosts share one registry. Acceptance relocates a minimal bin/lib layout and runs without Java/compiler artifacts.

`python3 tools/build_and_test.py --offline` and sequential `python3 tools/build_and_test.py --offline --sanitize` both exited 0 without ASan/UBSan reports. Java 72/72, native runtime 563 checks, and real JNI in the ordinary build passed. Java/C++ ABD fixtures were byte-identical; source/AST/ABD, lifecycle, and hint regressions passed. Signed-library end-to-end counts were 16 ordinary and 14 sanitizer; sanitizer excludes JNI.

`python3 tools/export_distribution.py build/remote-merge-distribution --offline` produced an independent acceptance package. All 17 relocation checks and sizes/SHA-256 for 150 manifest files passed, including static/shared C++ and real JNI consumers. Separate development/package plugin rebuilds verified C (*) with literal, manual-pointer, automatic-pointer, and temporary objects: visible mutations, once-only argument evaluation, one initialization on repeated loading, complete destruction, and byte-identical AST roundtrips. Temporary private keys were removed. Tested on macOS arm64; existing dist/ was untouched.

Logs: build/remote-merge-validation.log, build/remote-merge-sanitize.log, build/remote-merge-export.log. Combination reports: build/flexible-signed-plugin-qhtn77la/report.json and build/flexible-signed-package-1uh1orfx/report.json. Pre-merge backup: build/remote-merge-backup-20260927-082819/.

# C (*) Parameters (2026-09-25)

Parameters may use C (*) p or C(*) p to accept both C * and literal C; inside, p is C *. Literal arguments use block_address at the call site, without copying, making mutations caller-visible. Temporaries remain alive during calls and die at statement end. Pointer compatibility rules accept derived/structurally equivalent classes and null. Ordinary functions, constructors, methods, and externs support this address/type-7 ABI. Locals, fields, return types, for declarations, and C * (*) reject it. Internal class type numbering now allocates three numbers per class: value, pointer, flexible. Exec/snapshot stay v7.

Offline and sequential sanitizer builds exited 0 with no ASan/UBSan reports. Java 72/72, runtime 551, and three CTest entries passed. Literal tests increased to 61 (60 without JNI), covering:

- Literal/manual/automatic/temporary/derived/null arguments.
- Constructors/methods, including implicit-this calls.
- Retained borrowed addresses failing after original expiration.
- Delete rejection for borrowed literals.
- Eight compile-time rejection cases.

Hint tests added cross-module `extern int measure(Point (*))`. Unit tests verify AST P(*), exec type 7, and block_address only around literal arguments, plus no-space syntax, out-of-class extern members, structurally equivalent flexible extern declarations, and rejection of mismatched */(*) signatures.

A pre-commit adversarial review covered parsing, types, lifetimes, and tests/docs through actual compilation/execution. No code defect was found. Documentation corrections fixed read(null)'s null dereference, claims that this was the sole literal-address access, and missing (*) in HINT_LINKING erasure lists. The above tests were also added.

No dist/ re-export. Logs: build/flexible-params-validation.log and build/flexible-params-sanitize.log.

# Literal Objects and Slot Blocks (2026-09-25)

Runtime object type 8 was added: independent slot blocks, resizable through C++, deep-copied as a whole. Addresses encode `bit63 | id<<24 | offset`; scripts cannot distinguish them from shared-heap addresses. IDs never repeat; expired blocks invalidate addresses with `Invalid or expired object address`. Point a(1,2), Point a, and Point a() now declare literals; assignment copies fields in place and every copy destructs independently. Automatic pointers use Point * a(1,2), requiring () even without arguments. Manual objects use Point * m = new Point(1,2). Point * p is exactly a null declaration, without construction or cleanup for later assignments. Values/pointers follow C++ distinctions for parameters, returns, and fields. At this stage this (C*) was the only script access to block addresses. Null/delete are pointer-only; literals cannot occupy untyped positions, compare, or slice.

Literal/automatic-pointer cleanup interleaves in reverse creation order: own destructor → own value fields in reverse → base. Unbound temporaries die at statement end; if/while condition temporaries immediately after evaluation. Owned local/parameter returns transfer directly; other returns copy. Failed construction skips its own destructor but destroys completed fields. Heap value fields destruct on delete/automatic release; raw mem_free only expires them. Raw APIs do not manage blocks. Exec v7 adds opcodes 32–35 (new_block/block_address/mv/drop) and type 8. Snapshot v7 recursively saves value fields and assigns restored objects new IDs; already expired addresses remain permanently invalid. Literals do not cross Java.

Full offline build exited 0: Java 71/71, runtime 551, ABD/runtime/JNI CTest all passed, and Java/C++ bytes matched. The new 47-case literal suite covers declarations, null pointers/dereference failures, copies/destruction, parameters/returns/fields/nesting/inheritance, expired this, temporaries, compile rejections, source/AST/ABD identity, empty heap/blocks after host calls, JNI rejection, and snapshots. Migrated suites passed: classes 70, loops 60, inheritance 89, addresses 83, math, numeric slots, compact exec (36 opcodes, 93 independent wire cases, JNI snapshots), hints, and 72 compiler/native cases. Sequential sanitizer also exited 0 without reports; no-JNI counts were literals 46, classes 69, inheritance 88, compiler/native 67; address 83 and loop 60 unchanged.

Dist/azscript-macos-arm64 was refreshed to exec/snapshot v7. All 17 relocation checks and 143 manifest files passed, including new detail/interpreter/blocks.h. Export fixed hardcoded v6 metadata and the check allowing only opcodes 3–31 (inheritance uses 33). Tested on macOS arm64.

Logs: build/bare-pointer-validation.log, build/bare-pointer-sanitize.log, build/literal-objects-export.log; before null-pointer declarations, build/literal-objects-validation.log and build/literal-objects-sanitize.log. Syntax: compiler/LANGUAGE.md (English: compiler/LANGUAGE.en.md), classes section. Format: docs/EXEC_FORMAT.md (English: docs/EXEC_FORMAT.en.md).

# Independent 64-bit Address Type (2026-09-25)

Source address/runtime type 7 distinguishes addresses from int32. Object references, this, allocation bases, ownership, and memory APIs use uint64 with null zero; counts/offsets/variable IDs/function IDs remain integers. Int32 offsets check full uint64 bounds; address comparisons are unsigned. Structural types, inherited prefixes, and lifetimes remain; ordinary address returns do not promote ownership.

ABD tag 0xce200b carries exactly eight little-endian bytes. The 466-byte Java/C++ fixture matched. C++ uses azertian::address, Java ABD AcsAddress, high-level JNI azertia.Address, and low-level long raw bits. Exec/snapshots moved to v6 and rejected older formats. Snapshots still validate module identity/allocations/objects before atomic replacement. JSON addresses use decimal-string objects to avoid JavaScript integer precision loss.

Offline and final sanitizer builds passed with the new suite. Java 69/69, runtime 491, real JVM 2603 assertions, four-thread 800 calls, and small-stack linking of 1000 modules passed. All 83 address cases passed normally and under sanitizer: static/dynamic rejection, null comparisons, stored addresses, increment/for traversal, explicit ownership transfer, fields/inheritance, independent high-bit wire modules, overflow/underflow, and cleanup. Review fixed wrongly rejected dynamic-null comparison and unary plus bypassing numeric checks; negative zero, types, and once-only evaluation have regressions. Existing math/class/loop/inheritance/offset/hint/JNI cases passed without sanitizer reports.

The macOS arm64 distribution moved to v6 and passed 17 relocation checks with address headers, Java wrapper, source examples, and docs. Example compilation, default JSONs, AST byte identity, and standalone execution passed. All 142 file sizes/digests and 25 local documentation links passed.

Logs: build/address-validation.log, build/address-sanitize.log, build/address-source-validation.log, build/address-export.log. Example: compiler/examples/address-regressions.azs.

# For, Continue, and Public Single Inheritance (2026-09-25)

For remains in readable AST and lowers to a block/while. Initialization runs once; continue cleans iteration objects before stepping/checking. Simple-variable prefix/postfix ++/-- work as statements/steps without expression-result semantics. Exec remained v5, adding operand-free opcode 30 (continue) and fixed opcode 31 (internal cleanup); unknown opcodes fail explicitly.

Inheritance allocates exact slots with the full base prefix followed by derived fields. Members bind statically, allow shadowing, and support derived-to-base references. Bases construct first; field initializers use implementation members/globals. Destruction is derived-to-base. Cleanup handles early returns, runtime errors, and temporary-destructor errors, including base-initializer argument temporaries. Completion markers roll back only completed bases. Hint constructors/methods/destructors still relocate through flush; exec contains no names/inheritance/layout tables.

Offline/sanitizer both exited 0. Loops added 60 cases; inheritance 89 ordinary (with JNI), 88 sanitizer. Inheritance includes 21 error/budget calls on one script, checking empty heap/records immediately and subsequent successful reuse. Cross-library inheritance, constructor rollback, full chains, and JNI manual/automatic snapshots passed. Java 64/64 includes precise new-opcode wire roundtrips and malformed inheritance AST rejection. Existing math/source/classes/offset/linking/wire/C++/JNI passed with no sanitizer reports.

The macOS arm64 distribution passed 16 relocated checks, adding loop/inheritance source, JSONs, AST identity, and standalone execution. It retained compiler/Java runtime, C++ SDK, JNI, and independent math. See compiler/LANGUAGE.md, compiler/examples/loops-regressions.azs, compiler/examples/inheritance-regressions.azs, and docs/EXEC_FORMAT.md.

Logs: build/loops-inheritance-validation.log, build/loops-inheritance-sanitize.log, build/loops-inheritance-junit-final.log, build/loops-inheritance-export.log.

# Math Library as an Independent Hint Module (2026-09-25)

Math split into compiler/stdlib/math.include.azs declarations and math.azs implementation. Hint AZSCRIPT_MATH uses assumed 4d41; 52 public functions fix low IDs 0002–0035, and two helpers are private. Algorithms/domains/accuracy remain; floating parameters are double, integer APIs int, and math_pow (double,int), with exact extern checks.

Regression clients include declarations only and contain two definitions (main and local test), versus 54 in the library. Both source/AST/ABD roundtrips match. Independent ABI checks cover repeated includes, missing library, erased-signature mismatch, two consumers sharing a library, varying/library-first load order, repeated flush, and JNI actual-namespace calls with wrong-type rejection.

Full offline validation passed. Math retained 93 assertions, 114 comparisons, 23 runtime errors, and negative-zero checks. Dynamic factorial(3.5) became a compile-time type case; six type rejections are covered. Real JNI passed without warnings. The updated math suite also passed using the existing ASan/UBSan interpreter, with no C++ runtime change.

```sh
python3 tests/math_library.py --classpath "$(cat build/java/classpath.txt)" \
  --runner build/sanitize/interpreter/azscript-run
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

The refreshed package includes math ABD/AST/exec JSON/header and a standardLibraries manifest index. Fourteen relocated checks passed, including byte-identical rebuild, declaration-only client, and assembly. All 138 file sizes/digests and local links matched. Artifacts: build/math.* and build/math-regressions.*.

Logs: build/math-hint-validation.log, build/math-hint-sanitize.log, build/math-hint-export.log. API contract: compiler/stdlib/MATH.md (English: MATH.en.md in the same directory).

# Multi-File Libraries, Hint Linking, and Out-of-Class Implementations (2026-09-25)

At this stage exec and snapshots were v5 only. Added independent hints, module-local assumes, ordinary externs, explicit numbering, out-of-class definitions, structural types, constructor-prefix field initialization, and explicit flush. Class names/layouts erase; IDs retain all 32 bits and 0xffffffff no longer means no destructor. Earlier compatibility/load descriptions below are historical, not current semantics.

All exited 0:

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

JUnit 63/63 (60 compiler, 3 archive), runtime 428, JNI 2526 assertions and four-thread 800 calls passed. Linking 1000 dependencies on a 256 KiB thread stack passed without JNI warnings. Structural tests cover 3000 mutually referencing classes using iterative comparison; linking graphs are also iterative to avoid exhausting host stacks on valid chains.

Source 72 and class 70 cases passed including AST byte identity and JNI. Math 93/114/24 plus negative zero passed. Three-module global offsets, all 30 opcodes, and 82 independent wire cases passed. Sanitizer source/class counts were 67/69; multilibrary tests passed without reports. JNI was separately checked in the ordinary real JVM.

New tests/hint_linking_end_to_end.py covers shared class declarations, consumer factories, private-global initializers, constructor shadowing, initializer temporaries, chained cross-library automatic returns, manual returns, reverse destruction, alias reuse, load orders, cycles, retry, repeated flush, structural equivalence, and high-bit function/destructor IDs. JNI saves/restores source-generated multimodule objects. Native/JNI tests cover atomic link failure, erased mismatches, init failure, unflushed close, cached/direct callback execution gates, duplicate hints, host conflicts, snapshot identity mismatch, and oversized 64 MiB save preserving old files.

The refreshed macOS arm64 package passed 13 relocation checks: default triple outputs, independent class libraries, then-standalone runner without shared libraries, static/shared C++, real JNI. All 134 file sizes/digests and 21 links matched; both manifest versions were 5. Shared declarations/examples: compiler/examples/multifile/ (package examples/multifile/). Build/demo.* and build/classes-regressions.* were regenerated/run as v5.

Logs: build/hints-validation.log, build/hints-sanitize-validation.log, build/hints-export.log. Contracts: docs/HINT_LINKING.md and docs/EXEC_FORMAT.md. Linux/Windows require validation on their own systems.

# Defect-Fix Validation (2026-09-25)

Seven defects were reproduced with minimal scripts/C++ hosts, then covered by regressions:

- Isolated UTF-16 \uXXXX surrogates such as `"\ud800"` previously silently became ?. Lexer/compile-json now reject them; pairs work.
- Macro expansion inside numeric tokens changed 5N to 55 after #define N 5, 0x10 to 07 with macro x10, and 2e to 29 with macro e. Adjacent identifier characters now remain in the number for invalid-suffix diagnostics.
- Automatic C x(args) errors lost positions and exposed C::<scoped>. They now identify C constructor with line/column, as does new C(...).
- Method argument diagnostics counted implicit this: c.m(1,2) changed from expects 2/got 3 to expects 1/got 2; the first explicit argument is number 1.
- Unknown field types, void method parameters, and reserved class names now include locations consistently.
- Undeleted new objects leaked in the process heap after C++ destroy across load/destroy cycles. Storage/records now release without user destructors. JNI already cleared the heap and is unchanged.
- Allocation lookup was linear; alloc copied/sorted the full table, producing quadratic cost. At 40,000 live objects, allocation took 8.7 s and five access passes 17.8 s. Sorted tables, binary search, and skipping gap-free prefixes reduce this to about 0.1/0.08 s in Debug, preserving first-fit reuse order.

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

Both exited 0. JUnit 53/53 adds three cases for surrogates, numeric macros, and class diagnostics. Runtime 356 adds 6000 random differential operations against naive first-fit, 20,000 sequential allocations/hole reuse, and undeleted-object release without destructors. Existing source/classes/math/numeric/compact/JNI passed; sanitizer reported nothing. No distribution re-export.

# Exec v4 Raw Stacks and Numeric Opcodes (2026-09-24)

At this stage all 27 control strings became integers, totaling 30 opcodes with constant/block/call. Root/functions/signatures/expressions/lists used fixed-order raw stacks; constants retained single-element typed arrays, extensions typed maps. The interpreter created native nodes directly, without rebuilding Maps/Arrays. Old string variables, exec v3 Map loading, old node constructors, and string execution paths were removed; JNI accepted v4 snapshots only. Earlier compatibility statements below are historical.

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

All exited 0. JUnit 50/50 (47 compiler, 3 archive), runtime 350, JNI 2334 assertions and four-thread 800 calls passed. Source/classes were 72/70 ordinary, 67/69 sanitizer without reports. Math 93/114/24, negative zero, JNI, and three-module numeric/offset AST roundtrips passed.

Independent wire tests cover all 30 opcodes, field order, scalar types, both optional states, 82 valid/invalid encodings, and JNI opaque-byte snapshots. Source/AST/ABD, Java decode/re-encode, and independent Python decode/re-encode matched. Unknown versions/old Maps fail, with coverage for missing/extra fields, widths, booleans, UTF-8, nonfinite floats, invalid variables/types, and >128 nesting. Migrating runtime/JNI fixtures retained lifecycle, callback, memory restoration, and error-cleanup coverage.

The same compact_exec_end_to_end.py source encoded to 4702 bytes versus 14504 for equivalent legacy Maps: 32.4% of prior size, about 67.6% smaller. This measures one fixture's file size, not execution speed; legacy encoding is test-only for comparison/rejection.

The refreshed macOS arm64 distribution included compiler, runner, C++/JNI SDK, runtime, and format docs. Twelve relocation checks passed, with default triple outputs, 130 matching manifest files, and valid links. Build/demo.* and build/classes-regressions.* were regenerated/run as v4. Linux/Windows remain unverified on hardware.

Logs: build/compact-validation.log, build/compact-sanitize-validation.log, build/compact-export.log. Specification: docs/EXEC_FORMAT.md.

# Numeric Variables and Insert Global Offsets (2026-09-24)

Compiler output became exec-version 3 with integer gvs/slot IDs; AST kept names. Parameters start at 0, locals follow, globals at -1. Functions carry global_offset/global_count; globals decode negative IDs then add offset. At that stage old string executables remained readable. JNI wrote v4 global arrays while retaining v2/v3/unversioned compatibility for old scripts.

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

All exited 0: JUnit 46/46 (43 compiler, 3 archive), runtime 250, JNI 2329 assertions and 800 concurrent calls. Source 72/67 and classes 70/69 ordinary/sanitizer, math, and cross-language ABD passed. No sanitizer reports or -Xcheck:jni warnings.

New numeric_slots_end_to_end.py and numeric_module_host.cpp compile/AST-roundtrip three independent modules and insert via public C++. Same-name globals have offsets 0, 1, 3; recursion, shadowing, loop reentry, destructors, factories, and close hooks use their own globals. Failed duplicate insertion preserves counts/behavior. Runtime adds bidirectional cross-module calls, mixed legacy loading, invalid bounds/types/large counts; JNI checks snapshot restore and atomic malformed-array rejection.

Logs: build/numeric-slots-validation.log, build/numeric-slots-sanitize-validation.log, build/numeric-slots-export.log. The macOS arm64 distribution passed 12 relocation checks with matching compiler/runtime/SDK; build/classes-regressions.ast.json/.exec.json/.exec.abd were regenerated.

Numeric exec required its matching interpreter. Insert ID-conflict rules and the then-existing behavior of not rolling back completed writes after load-hook failure remained unchanged.

# Distribution Export Validation (2026-09-24)

New tools/export_distribution.py generated dist/azscript-macos-arm64 with Java runtime, compiler, main runner, static/shared C++ SDK, JNI JAR/dylib, docs, and examples. A --system-java package also tested installed-Java lookup.

Both passed 12 tools/test_distribution.py checks after relocation to a path with spaces and invocation from a third directory. Triple outputs, explicit paths, error codes, AST byte identity, destructors, separately copied runner, real CMake static/shared hosts, and -Xcheck:jni host passed. Bundled compilation worked without Java on PATH and with invalid JAVA_HOME.

Eleven tests/test_export_distribution.py regressions cover nonempty targets, invalid manifests, source-directory protection, restoration after replacement failure, and preserving old backups during concurrent target occupation; these joined the unified suite. All 129 file digests and local links matched. Installed CMake/Mach-O paths did not reference source/build directories.

Full offline validation passed language/ABD/runtime/JNI, including 70 class and 72 existing source cases. Logs: build/export-distribution.log, build/export-system-java.log, build/distribution-regressions.log. Sanitizer was not rerun in this section; preceding class results appear below.

Linux/Windows rules, suffixes, and launchers exist but were not tested on those systems. Native packages must match OS/architecture/C++ ABI; the macOS package is not portable to other operating systems.

# Class Syntax Validation (2026-09-24)

The macOS workspace implemented classes, reference semantics, construction/destruction, automatic-return ownership, and JNI v3 object snapshots. Both commands exited 0:

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

- JUnit 43/43: compiler 40, archive 3.
- Native runtime 198; ordinary CTest 3/3, sanitizer CTest 2/2.
- JNI 2210 assertions and four-thread 800 calls, no -Xcheck:jni warnings.
- Class end-to-end 70 ordinary (including compiled-source JNI execution/save/restore/delete), 69 native sanitizer; all valid sources passed byte-identical AST recompilation.
- Existing source 72 ordinary/67 sanitizer; math 93 assertions, 114 comparisons, 24 error boundaries, positive-zero checks, and bidirectional ABD identity passed.

Class coverage includes one-slot fields, implicit this, nominal types, no dynamic class inference, forward references, defaults/initializer order, once-only receivers/arguments, aliases, automatic/manual/null objects, reverse destruction, nested returns, and unchanged borrowed ownership. It also covers constructor/destructor errors, original-error precedence, budget exhaustion, destructor reentry, raw-release deregistration, selective cleanup after load failure, and members named alloc/mem_get/make_free not interfering with lowering.

JNI covers atomic restore, then-supported legacy formats, automatic objects outliving their original scopes, destruction order, and continued destruction after a Java callback throws while restoring the same original exception object.

Inspectable outputs:

- build/classes-validation.log: full offline log.
- build/classes-sanitize-validation.log: full sanitizer log, no reports.
- build/native/Testing/Temporary/LastTest.log: native/JNI details.
- compiler/examples/classes-regressions.azs: runnable example returning 0.
- build/classes-regressions.ast.json, .exec.json, .exec.abd: generated example artifacts.

Linux/Windows and Gradle were not tested. At this stage objects still used integer addresses without generations; member references were non-owning and new required delete. These were the then-defined boundaries in compiler/LANGUAGE.md.

# Earlier Validation Records

The following historical counts and original-file checks do not claim a new run.

Environment: macOS arm64, AppleClang 21, JDK 21.0.5 (all Java compiled with --release 17), CMake, Python 3. Linux, Windows, and integration into the user's larger project were not actually run.

## Passed checks

| Check | Result |
| --- | --- |
| Unified offline build | Exit 0 |
| Compiler/archive JUnit | 33/33 |
| Java ABD/reflection regressions | Passed |
| C++ ABD | Passed, including 10,000 corrupt mutations |
| C++ interpreter | 135 checks |
| CTest data/runtime/JNI | 3/3 |
| Real JNI/JVM -Xcheck:jni | 2078 assertions, four workers/800 calls |
| Source→compiler→ABD→C++, and Java/JNI | 72 integrations |
| Pure-script math | 93 assertions, 114 Python math comparisons, 24 error boundaries, positive-zero bits; full native and JNI load/numeric/error/reload paths |
| Java↔C++ data | Every field and independent/re-encoded byte matched |
| ASan/UBSan native CTest | 2/2, no reports |
| Static azscript-embed | Host integer 146, destruction hook done |
| python3 tools/verify_originals.py | 292 originals byte-for-byte unchanged |

## Key regressions

- 20-3-2 → 15, 100/5/2 → 10, 2+3*(4+1) → 17.
- 5e0/2 and 5./2 → 2.5; 1.5f retains float; -0.0 retains its bits.
- Quoted strings preserve quotes, backslashes, commas, brackets, semicolons, comment markers, Chinese/NUL/supplementary characters.
- Trailing macro // comments, inactive multiline comments, no numeric-token macro expansion, total expansion limits, relative/cyclic includes.
- Namespace IDs, then-current #extern with complete signatures to real Java callbacks, fixed main.
- Seven then-existing built-ins with fixed names; reserved names/0xabd cannot be overridden by source/AST; unknown directives fail.
- All #extern scalar signatures, exact types/counts, nested result inference, rejection of unknown dynamic values and old signatureless syntax, duplicate/reserved IDs.
- Hand-authored ABD/direct host calls still validate signatures; bad arguments never reach callbacks and bad Java results immediately raise IllegalStateException.
- Math int32 extremes, subnormal-to-max-finite doubles, exp(-745..709.78), ±1e12 angle reduction, stable mean/lerp/hypot, positive-zero bits, rounding/domain boundaries, repeated guards.
- Recursion/forward calls, short-circuiting, nested loops/break/return, single-statement scopes, left-to-right by-value arguments.
- Recompiled abdJavaInvoker/tse.azs prints heap 0:25 through 24:33554432, then printed and predestroy2.
- Controlled failures for illegal characters, missing brackets, unknown names, argument counts, assignments, returns, and bad JSON.
- 2000-level parentheses/unary/assignment/JSON and cyclic AST reject normally; the host can compile again.
- All numeric positive/negative-zero division reports Division by zero, including JNI mapping. Overflow, infinite loops/recursion, corrupt ABD, and invalid heap access fail controllably.
- JNI close/onload/onclose errors, returning/reentrant/throwing callbacks, zero arguments, malformed snapshots, atomic restore; onload callbacks may invoke.
- Reentrant callbacks cannot free suspended scopes' automatic allocations; errors leave no residue. Caller-chain release and release after make_free remain valid; Java memFree rejects script-scope blocks.
- Shortest roundtrip numeric text: 0.30000000000000004, 123456789, 0.1, -0, 1e+20.
- 100 chained operations and 40 nested ifs compile; 130 operations report located ABD-limit errors. Reserved names fail, BOM works, missing nonvoid returns fail compilation.
- Hosts cannot bind 0/0xfff; #extern cannot use script namespaces; missing IDs there never fall through to hosts.
- Archive file/directory name conflicts and empty data entries reject without writing any files.

## Logs and inspectable outputs

- build/native/Testing/Temporary/LastTest.log: C++/JNI details.
- build/demo.ast.json: readable example tree.
- build/demo.exec.json and build/demo.exec.abd: instructions/binary.

The local Gradle native-platform library could not initialize on macOS arm64, so that path was not validated. The passing unified entry point directly uses javac, jar, JUnit, CMake, and a real JVM with SHA-256-verified dependencies, without a Gradle daemon. Gradle remains an alternative configuration.

These tests cover implemented functionality and discovered regressions, not proof of no defects. Historical boundaries include shared heap, integer-address reuse, and unbudgeted host callback time; consult the current README for current semantics.
