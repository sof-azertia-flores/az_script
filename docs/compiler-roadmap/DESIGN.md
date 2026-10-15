> Detailed design notes for the suspended compiler-improvement effort (generated during planning). Where this file disagrees with [STATUS.en.md](STATUS.en.md), STATUS wins. In particular: debug info is opt-in with `-g` (there is no `--strip-debug`), the internal-error stack flag is `--debug-compiler`, there is no stable-ID tooling and no default-namespace warning, and heap memory-safety bugs are out of scope.

# Unified implementation plan: AzScript compiler improvements (branch `claude/confident-galileo-fic3s1`)

This merges the 7 workstream designs and applies every critique. Where a critique and its design disagree, the critique wins unless this plan says otherwise. Code citations are the ones the designs and critiques verified. I re-checked these myself:
- `KEYWORDS`/`TYPES` (GeneraterJson.java:15-17);
- `BUILTINS` 0x0abd0000..0a (Compiler.java:29-40);
- the dense `ExecOpcodes.NAMES` 0..42 (ExecOpcodes.java:8-31);
- the colon-glue lexer path (GeneraterJson.java:456-462);
- `assignable`/`equivalent` (Compiler.java:286-331);
- context encoding (ExecCodec.java:105-143);
- the Main option loop (Main.java:30-43) and the launcher's `arguments()` early return (launcher/Compile.java:46-58);
- JUnit auto-discovery versus explicit Python suite registration (tools/build_and_test.py:96-112, :120-170);
- `truth()` (library.cpp:141).

---

## 0. Ground rules

- **Exactly one exec bump, v9 to v10, in C13.** Every earlier commit leaves the wire format untouched. Every later commit appends opcodes or built-ins *inside* v10 and updates every reader, writer, registry range and doc together. No compatibility branches.
- **JNI snapshot version.** It stays 9 until C24 (virtual methods). C24 bumps it to 10 once, because only that commit changes snapshot records. Project version, source `metadata.version` (stays 2) and the exec and snapshot versions are tracked separately.
- **Validation sets.** Each commit runs the sets it lists. Unset `JAVA_TOOL_OPTIONS` (stderr-sensitive suites):
  - **V0**: `git diff --cached --check`
  - **V1**: `env -u JAVA_TOOL_OPTIONS python3 tools/build_and_test.py --offline`
  - **V2**: `env -u JAVA_TOOL_OPTIONS python3 tools/build_and_test.py --offline --sanitize`. Run it after V1, never concurrently (shared `build/java/`).
  - **V3**: `env -u JAVA_TOOL_OPTIONS python3 tools/export_distribution.py build/dist-check --offline` (add `--force` on reruns). Only this runs `tools/test_distribution.py`, the launcher, `compile.sh`, relocation and installed-header consumer checks.
- **New Python suites** must be registered in `tools/build_and_test.py` next to the existing `run(...)` calls (:120-170), with `--bridge/--library` when `jni`. JUnit classes are found automatically (:105-110).
- **New compiler code** throws plain `new IllegalArgumentException` (exact class) or `CompileException`. Any other exception class is classified as an internal error (C02).
- **Every syntax or AST change** in C09 and later also updates `AstShape` (C02), `canCompleteNormally`/`containsBreak`, the warning checks (C03) and `SourceRules` (C09) where relevant.
- **Docs.** Every doc edit lands in both `X.md` (Chinese, primary) and `X.en.md`. Keep the exporter anchors verbatim: `在仓库根目录` / `CLI 的 \`compile-json\`` and `From the repository root` / ``The CLI's `compile-json` `` (tools/export_distribution.py:104-105, :116-117). New relative links must resolve both in the repo and in the relocated package (tests/test_export_distribution.py:101-130).

---

## 1. Cross-workstream resolutions

| # | Conflict | Resolution |
|---|---|---|
| R1 | Diagnostics infrastructure: `SourceMap`/`sources` (diagnostics), `source-files` (preprocessor, line table), `In function F (label:L:C)` (preprocessor), `In class X:L:C` (type_safety) | **C01 owns one model.** `Diagnostic`/`Diagnostics`/`CompileException`/`SourceMap`. Format: `file:line:col: severity: [in <context>: ]message`, with notes on following lines. All workstreams emit through it; there are no string prefixes. Contexts: `function main`, `function C::m`, `function C::C`, `function C::~C`, `function C::operator+`, `initializer of class P`, `class P`, `global g`. |
| R2 | Source-map memory: per-newline int arrays (O(lines)) | Use a **segment table**: one record `(firstOutputLine, unit, firstSourceLine)` at each include entry and return, O(includes). Macro column segments are stored sparsely, only for lines with expansions. The lexer maps positions incrementally; error lookup binary-searches. |
| R3 | AST file table: per-inclusion unit vs per-file | AST root `sources: [{name, included-from?:{source,line,column}}]` has **one entry per inclusion instance (unit)**, and `_file` indexes units, so diagnostics can print include chains. Exec `debug_files` (C13) is **deduplicated by name**; the compiler maps unit index to file index. An absent `_file` always means 0; there is no inherited per-function default (line-table critique). |
| R4 | File naming: main-relative vs root-relative vs absolute | A label is the path relative to the first allowed root containing the file (main dir, then `-I` dirs, then default stdlib), with `/` separators; the main file is its bare name. On a label collision between distinct real files, fall back to the main-relative path. If `relativize` fails (different roots), use the normalized absolute path. String sources use `<source>`, `getExpression` uses `<expression>`. The **CLI prints stored labels in both `compile` and `compile-json`**, so their outputs are identical. Filesystem candidates in include errors are printed as absolute paths. |
| R5 | Cast syntax: C-style `(address) p` (type_safety) vs functional (language_ops) vs parenthesized expressions vs `Point (*)` | **Functional only**: `int(x)`, `float(x)`, `double(x)`, `string(x)`, `address(x)`. `(T)x` gets a targeted parser hint. `boolean(x)`/`bool(x)` get a targeted error. There is **no conversion to class pointers in v10**: downcasts and `address`/`any` to `C*` are rejected, because there is no runtime class identity. That closes the unsound "width-only" check the critique found. `Point (*)` remains a parameter-type form only; `declarationAhead()` treats only `TYPE(` (conversions) and `Class::` (base calls) as expression starts. |
| R6 | `int`↔`address` conversions | **Rejected in both directions**, statically and at runtime (type_safety wins over language_ops). `string(addr)` is allowed. `address(p)` for class pointers is the erasure escape hatch and emits no node. |
| R7 | Generics `<...>` vs shifts `<< >> >>>` | The lexer keeps emitting single `<`/`>`. The parser recognizes shifts from **adjacent** tokens (same line, no gap), so all generic closers are untouched; `a > > b` is not a shift. `callTypeArgumentsAhead` aborts on any token other than known type names, `<`, `>`, `,`, `*`. |
| R8 | Ternary `?:`, `case L:` and `:id` colon tokens | The colon-glue path keeps the raw token text (IDs read `token.text()`), but attaches numeric or boolean literals when a non-strict, side-effect-free number scan covers the whole run. A raw digit token used in an expression is re-scanned strictly, so it gets the normal lexer diagnostic. `::` is a separate two-char token (unaffected). |
| R9 | `virtual` keyword | Already reserved. Becomes a member **prefix modifier** with `override` and `final` (both newly reserved, C24). `: virtual Base` stays rejected (GeneraterJson.java:911). Top-level and out-of-class uses are rejected with a message. Bodiless virtual members get "abstract methods are not supported". |
| R10 | `switch` vs else-if flattening vs depth spilling | Switch has its own flat lowering: one-shot `while(true)`, one sibling `if` per section whose condition is a **balanced `||` tree** of label comparisons, default last. It introduces the shared **`SyntheticLoop` descriptor** on `LogicEnvironment` (`breakFlag`, `continueFlag`, `outerLoopDepth`). The C27 else-if ladder reuses it through compile-time break/continue retargeting, which replaces the post-hoc tree rewriting. Switch is statement-only. Its scrutinee `vd` is an ordinary statement, so the C25 spilling covers it. |
| R11 | Type rules vs casts | Explicit conversions are the only escape hatches: `int(x)` for narrowing returns (C18), `address(p)` for class pointers into untyped positions (C22), and `any`-sourced conversions through the runtime `convert` opcode, whose same-type identity matches the static rules. There is no numeric-to-boolean conversion (write a comparison). |
| R12 | Virtual-method layout vs nominal typing | Method tables live outside the slots (per-object metadata like destructor ids), so layouts, widths and strides do not change. C10 makes **all** classes nominal, which closes the false "no slicing" premise and is load-bearing for statically binding literal receivers. Only derived-to-base pointer conversion remains implicit. `check_object` is never used for dispatch. |
| R13 | Single bump and opcode numbering | C13 bumps (line table). Opcodes are dense and appended in landing order: **43-48 bitwise/shift (C16), 49 convert (C17), 50 select (C19), 51 check_object (C23), 52 set_methods, 53 call_virtual (C24)**. "Unknown opcode" sentinels are derived from the registry from C13 on (tests/compact_exec_end_to_end.py:633, tests/test_export_distribution.py:42). |
| R14 | Depth model vs new opcodes; linearizer ordering | The limits workstream lands **last** (C25-C27), so `ExecShape` is written once with roles for 0..53 and fails closed for unknown opcodes. Earlier features must not rely on spilling. The linearizer runs before `normalizeContexts` and before debug-record emission, and registers positions for every record it synthesizes. |
| R15 | `TreeLimits.LimitExceeded` vs `NestingException` | One class, `TreeLimits.LimitExceeded` (C01). It carries the outermost positioned statement plus the innermost node. Message texts stay the same. |
| R16 | Launcher drift per flag | C01 adds `Main.parseCompileArguments(List<String>)`, a public record: options may appear anywhere, the first non-option operand is the source, and value options and flags come from one table. `tools/distribution/launcher/Compile.java` uses it to find the source and the explicit outputs, so later flags need no launcher edit. |
| R17 | Hex function ids (diagnostics C5, preprocessor D, line table C3) | One commit, C04, for runtime text (`0x%08x`). The compiler uses `Diagnostic.hexId` (C01), including ExecCodec.java:83/:86. |

---

## 2. Format and registry summary (v10)

**Exec root**, 11 fields; field 11 is new: `[..., assumptions, debug_files:Stack<string>]`.

**Function record**: `[id, ret, param_count, local_count, param_types, body, hidden_count, entry_kind, has_debug:bool, [FunctionDebug]]`.
- `FunctionDebug = [name:string, file:int, line:int, column:int, locations:Stack<int>]`.
- `locations` is a **flat int stack of quads** `(node, file, line, column)`, which keeps the "fixed structures are bare AbdStack" contract (critique). Nodes are numbered pre-order over expression stacks in wire field order (body = 0); context records get no number. Nodes are strictly increasing.
- Inspection JSON: root `debug-files`; per function `debug:{name,file,line,column,locations:["n:f:l:c",...]}` (compact strings, avoiding Gson pretty-printing blow-up).
- The existing "Debug JSON" wording becomes "exec inspection JSON". Debug info is never semantic.

**Opcodes**

| Op | Name | Wire |
|---|---|---|
| 43-48 | `bit_and`, `bit_or`, `bit_xor`, `shl`, `shr`, `ushr` | `[op, left:E, right:E]` |
| 49 | `convert` | `[49, type:int∈{0,1,2,3,7}, value:E]` |
| 50 | `select` | `[50, cond:E, then:E, else:E]` |
| 51 | `check_object` | `[51, value:E, context:Context, slot_types:Stack<int>]` |
| 52 | `set_methods` | `[52, address:E, entries:Stack<[function_id:int, contexts:Stack<Context>]>]` |
| 53 | `call_virtual` | `[53, slot:int, return_type:int, args:Es]` |

`-1 INTERNAL_SEQUENCE` is compiler-internal and never encoded.

**Built-ins (0x0abd…)**
- `0b` str_length, `0c` str_byte_length, `0d` str_substring, `0e` str_char_at, `0f` str_code_at, `10` str_from_code, `11` str_index_of, `12` str_starts_with, `13` str_ends_with (C21).
- `14` `object_alloc`: compiler-internal, not callable by name from source (C22).

**v10 semantic tightenings** (documented in EXEC_FORMAT):
- conditions (`if`/`wi`/`not`/`and`/`or`/`select`) must be boolean;
- `eq`/`ne` on two non-numeric values of different tags throws;
- return conversion is exact or widening;
- raw `mem_get` rejects object storage.

**JNI snapshot v10 (C24)**: every heap-object entry and literal or view record carries a required `methods` array. Buffer records must have it empty.

---

## 3. Shared infrastructure

| Item | Introduced | Used by |
|---|---|---|
| `Diagnostic`, `Diagnostics`, `CompileException`, `SourceMap`, `Diagnostic.hexId` | C01 | all |
| `CompilationState` on the root `LogicEnvironment`: diagnostics, source units, `IdentityHashMap` sets for synthesized nodes, diagnostic labels, `virtualCalls`, positions (`IdentityHashMap<AcsElement,int[]>`). No new ThreadLocal; the 13 existing ones (TYPE_QUERY :182 included) stay. | C01 (+C13, C15, C24) | all |
| `position()`, `copyPosition()`, `relocate()` | C01 | C11, C13, C20, C25-C27 |
| `Main.parseCompileArguments` | C01 | launcher, all CLI flags |
| `AstShape` | C02 | every AST-key-adding commit |
| Warning registry | C03 | C19, C20 |
| `function_id_text` (C++) | C04 (internal), public in C14 | C13, C14 |
| `SourceRules` (`KEYWORDS`, `RESERVED_WORDS`, hint regex) | C09 | C11, C20, C24 |
| `commonType(a, b, strict)` | C10 | `branchType`, C19 ternary |
| `declarationAhead()` | C12 | C17 |
| `SyntheticLoop` | C20 | C27 |
| `pending_error`, `run()`/`at_location` | C14 | every new runtime node class from C15 on |
| `ExecShape`, `AbdLinearizer`, `FunctionTables` | C25 | C26, C27 |

---

## 4. Commit sequence

### Phase A: foundations (exec stays v9)

**C01 feat(diagnostics): locate compiler diagnostics in their original file through includes and macros**
- **New files.** In `compiler/src/main/java/azertia/script/`:
  - `Diagnostic.java`: severity, code, message, `Position(file,line,col)`, context, notes, internal flag, cause; `format()`; `hexId`.
  - `Diagnostics.java`: collector. In C01 it behaves fail-fast.
  - `CompileException.java`: extends `IllegalArgumentException`; `getMessage()` joins the error lines.
  - `SourceMap.java`: units, segment table, sparse macro segments, `resolve`, `includeNotes`, `macroNotes`, `relativeName` with fallback, `toJson`/`fromJson`.
- **`GeneraterJson.java`, preprocessing.**
  - `preprocess` (:96-218) writes into a shared builder plus the SourceMap and keeps one output line per input line.
  - `Conditional` gains a line; the `Macro(value, unit, line)` record replaces string values (:66, :124-127, :194, :286-289).
  - `expand()` records segments at depth 0.
  - Directive errors are located at `#`. Macro-expansion errors on code lines (:207-211, :287, :293) are now located.
  - Cycle text lists unit labels. Messages: `Cannot open include file "x": file not found`, `Source file "x" is not valid UTF-8` (MalformedInputException at :82, :151).
  - The `Preprocessor <dir>:` prefix is removed (:203-216).
  - Every IAE catch that can see a nested `CompileException` rethrows it first (:203, :567, :584, :896, :1038).
  - `toPureSource` clears all state and returns the builder text.
- **`GeneraterJson.java`, lexer and parser.**
  - `Token(text, literal, unit, line, column, identifier, macro)` has **no defaulting constructor**. The float-literal node at :482 and the base-initializer token at :774 are mapped. `classLocation` (:733-736) uses an `IdentityHashMap<JsonObject,Token>` side map.
  - `operation()` (:512-518) adds `_file` (omitted when 0).
  - Function metadata gains `_file/_end_line/_end_column`. Classes, fields and extern signatures carry positions.
  - New root `sources` array. `_parenthesized:true` on parenthesized assignment nodes (`primary` :1331).
  - Directive positions are recorded for `#namespace`/`#namespace_hint`/`#assume_hint`/`#gvar`.
  - Post-parse errors are located:
    - `assignFunctionIds` (:852-889) at the `AzFunction` position;
    - extern conflicts (:794-807) at the second declaration, with a "previous declaration" note;
    - the assume overlap (:670-671) at its directive.
  - Duplicate function, class and ID errors (:871, :901, :973) use `hexId` plus a "previous definition is here" note. When both definitions come from the same file and position via two inclusions, the message adds "the header was included twice; add an include guard".
- **`TreeLimits.java`.** `LimitExceeded` uses lenient anchor extraction. `validateJsonText` reports line and column.
- **`Compiler.java`.**
  - `CompilationState` on the root environment; `position`, `copyPosition`, `relocate` replace the catch sites (:415-420, :449-452, :714-718, :1948-1950) and every `startsWith("In function ")`.
  - The global loops are anchored: metadata :1823, externs :1848, abstract :1874, globals :1881, `readClasses`/`prepareLayout` at `info.source` (not the outer-loop class), `addClassFactories` :1516-1546.
  - Missing return (:1931-1933) is reported at `_end_line/_end_column` with "non-void function can reach the end of its body without returning a value".
  - `for` lowering copies the *condition's* position onto the synthesized `not`/`if` (:836).
  - `Field` gains `file`; `hexId` at :1831 and :1862.
  - `compile()` runs `TreeLimits.validate` first; `LimitExceeded` becomes a located error.
- **`ExecCodec.java:83/:86`**: hex IDs.
- **`Main.java`.** Shared `parseCompileArguments`; prints diagnostics, then `Error: compilation failed: N error(s)[, M warning(s)]`. Gson "line L column C" becomes `file:L:C: error: Invalid JSON: …`, with a file-level fallback.
- **Launcher (`Compile.java`)** uses the shared parser and rethrows `CompileException` formatted.
- **Tests.**
  - New `DiagnosticsTest`:
    - probes d1, d2, d5;
    - nested include notes, innermost first;
    - macro column plus note;
    - lexer errors carry the file;
    - preprocessor errors located (unclosed `#ifdef` at its own line, comment start, cycle, missing include);
    - formerly unlocated errors: AST nesting (2000-term chain), "Unregistered class function", missing return, `Unknown type: T` via compile-json;
    - a float literal, a field initializer and a base initializer inside a header carry `_file`;
    - a cyclic JsonObject passed to `Compiler.compile` gets a located "AST contains a cycle" with no hang;
    - AST roundtrip keeps positions with byte-identical ABD and no input mutation;
    - located post-parse errors;
    - hex IDs.
  - `CompilerTest` location assertions :176, :501, :552, :718, :887, :932, :1185.
  - `tests/end_to_end.py`: an include case, and compile vs compile-json identical stderr.
- **Docs.**
  - LANGUAGE: replace the include-limitation and diagnostics paragraph (zh :313/:315, en :311/:313); AST contract (zh :422, en :420): `sources`, `_file`, `_end_*`, `_parenthesized`; CLI and Java API snippet (en :20-26) gain `CompileException`.
  - README: remove the limitation (zh :217, en :211).
  - USAGE: error-output description (:29-46).
- **Validation**: V0, V1, V3.

**C02 feat(diagnostics): report multiple errors, validate AST shape, classify internal errors**
- **Recovery.**
  - Parser panic mode per top-level declaration, class member and block statement. Paren and brace depth are tracked separately; recovery always advances at least one token; a second error at the same token index is suppressed. Nesting, limit, include-depth and cap errors are fatal. Parser state is restored in `finally` (:614, :699, :960, :1066, :1071, :1086, :1115, :1139, :1201, :1257).
  - Lexer and preprocessor recover per directive: a failed `#ifdef` pushes an inactive conditional.
  - Semantic recovery happens **only per function body** (`compileProgram` :1931), **per global item** and **per class**. There is no per-statement semantic recovery and no poisoned bindings, because `requireKnownExact` rejects ANY (:353-355) and layout caches are not transactional (:1337-1338, :1389-1415).
  - `checkpoint()` runs between the `readClasses` sub-loops (:1453, :1462, :1471, :1482, :1484, :1486), inside `addClassFactories` before :1552, and after each global loop.
  - Diagnostics are sorted stably within a phase by (unit, line, col).
  - The cap defaults to 20 (`--max-errors N`, 0 = unlimited).
- **New `AstShape.java`**, run after `TreeLimits`. It is iterative and reports JSON-pointer paths. It checks:
  - all metadata, body, class and extern shapes, plus node shapes in scripts, initializers and base-args;
  - `sources`: unit 0 has no `included-from`; `included-from.source` is lower than its own index; chain length ≤ 64; names non-empty, without control/C1/bidi characters, at most 4096 chars;
  - numeric primitives parse through `getAsBigDecimal` (covers `1e20000` at :163/:1738);
  - `compound`/`_parenthesized` are literally `true`;
  - param 0 of `member-set`/`member`/`index` is an object (:606, :1017, :538);
  - `_line/_column/_end_*` are positive ints and `_file` is in range, **regardless of debug flags**.
- **Classification.**
  - `Diagnostics.userError` accepts non-internal `CompileException`, exact `IllegalArgumentException` and `LimitExceeded`.
  - It is provenance-aware. For an in-process parser AST, any other `RuntimeException` or `StackOverflowError` is `internal compiler error: <SimpleName>: <msg>` plus a bug note, exit **2**. For external JSON (compile-json, library API) it is `Malformed script JSON near <file:L:C>`, exit **1**. Details and the stack trace appear only with `--debug`.
  - `AzScript.execute` is wrapped the same way. The `Malformed script JSON: required metadata…` fallback (:1769-1772) is removed.
- **API.** `Compiler.compile(JsonObject, Diagnostics)`; `AzScript.execute(Path|String, Diagnostics)`. A bare `new LogicEnvironment()` stays fail-fast (CompilerTest :517).
- **Tests.**
  - `DiagnosticsTest`: probe d3 (two functions); `parserPanicModeResyncs`, including an unbalanced `(` followed by an independent error; class barriers (missing ctor plus a second bad class, no internal error); `errorCap`; nested-include error with `--max-errors 1` giving exactly one error with its note chain; `compileJsonInputErrors` covering every scratchpad `out/bad-*.json`, `_line:"x"`, `_file` out of range, a cyclic `included-from`, and `1e20000` with exit 1; `internalErrorClassification`.
  - `CompilerTest` forged-tree tests (:674-705, :727, :838-841, :913; not :1098) use `assertRejected` (no internal error).
  - `Main.run` tests for exit codes 0/1/2 and `--max-errors`.
- **Docs.** LANGUAGE diagnostics subsection (multiple errors, cap, exit codes, `--debug`, internal errors); USAGE.
- **Validation**: V0, V1.

**C03 feat(diagnostics): compiler warnings and -W options**
- **Warnings** live in `Diagnostics.WARNINGS`:
  - `assign-in-condition` (on): a top-level `varset` (non-compound), `member-set`, `index-set` or `mov` in an if/while/for condition without `_parenthesized`;
  - `unreachable-code` (on): first source statement after a non-completing one, once per block;
  - `unused-variable` (off).
- **Synthesized nodes never warn**, using the `CompilationState` identity set filled by `node()`/`internal()`; unpositioned forged statements never warn either (loops_end_to_end.py:159-168).
- **Flags**: `-Wall`, `-W<code>`, `-Wno-<code>`, `-w`, `-Werror` (`[-Werror=code]`). An unknown code is a usage error.
- **Tests.**
  - `DiagnosticsTest.warnings`;
  - `examplesAndStdlibAreWarningClean`: compiler/examples, multifile, stdlib, tools/distribution/examples, abdJavaInvoker/tse.azs. This guards buffers_end_to_end.py:57,60 and generics_end_to_end.py:219,233, which require empty stderr;
  - CLI tests;
  - test_distribution: a trailing `-Wall` keeps the default outputs.
- **Docs.** LANGUAGE warnings; USAGE.
- **Validation**: V0, V1, V3.

**C04 fix(runtime): hexadecimal function ids and actionable module-assembly errors**
- **`interpreter/library.cpp`.**
  - `function_id_text` (`0x%08x` of the uint32 pattern) in the anonymous namespace (:44). Used at :88-96, :274-278, :824, :862, :954, :960, :1036, :1079-1114.
  - Exact format: `Duplicate function id: 0x0fff0001 (namespace 0x0fff, position 0x0001) is already defined by module #0 (ordinary); <remedy>`. Module ordinals are 0-based, primary = #0.
  - Remedies:
    - main collision;
    - default-namespace collision: "give each module its own #namespace away from the low range hint libraries take, insert fixed-namespace modules before hint libraries, or use #namespace_hint";
    - the :1032 fixed-vs-hint conflict names the hint and the hex namespace (lowest-free allocation, :1018).
- **`interpreter/test.cpp:46`**: wrap only `insert_script` with `Cannot insert <path>: `.
- **`abdJavaInvoker/.../Caller.java:33`**: hex id.
- **Tests.**
  - `runtime_tests.cpp`: hex messages; upgrade :997; default-ns duplicate; main duplicate; fixed-vs-hint in both insertion orders.
  - `hint_linking_end_to_end.py`: probes m1/m2 give `Cannot insert`, `0x0fff0001`, `#namespace`; the library with `#namespace 5151` links.
- **Docs.** HINT_LINKING: new "Separately compiled ordinary modules" subsection, including insertion order.
- **Validation**: V0, V1.

**C05 feat(preprocessor): bounded source reading, #pragma once and preprocessing limits**
- **`readSource(realPath)`** applies to the main file **and** includes: `Files.isRegularFile`, a bounded stream read (at most `MAX_SOURCE_BYTES+1`), a `CodingErrorAction.REPORT` UTF-8 decoder, and a per-compilation cache by real path.
- **New limits in `TreeLimits`**:
  - `MAX_SOURCE_BYTES` = 16 MiB;
  - `MAX_PREPROCESSED_CHARS` = 16 Mi;
  - `MAX_INCLUDES` = 4096 expansions (`#pragma once`-skipped includes don't count);
  - `MAX_TOKENS` = 4 Mi (bounds Token memory, per the preprocessor critique);
  - `MAX_SOURCE_LABEL_CHARS` = 4096.
- **`#pragma once`**: added to the directive set (:18-20). Only `once` is accepted. The once-check runs before the cycle check.
- **Function-like macros**: `#define F(x)` gets `Function-like macros are not supported: F; write a function, or put a space before '(' for an object-like macro`.
- **The duplicate-definition hint** now mentions `#pragma once`.
- **Tests.** New `PreprocessorTest`: pragma-once cases (including an inactive branch and a self-include); a 2^13-expansion chain gives `Include count exceeds 4096`; a guarded header included 4000 times passes; preprocessed-size and token caps; a non-regular main (directory, or FIFO with `assumeTrue`); the non-UTF-8 message; function-like macros; `toPureSource` called twice.
- **Docs.** LANGUAGE preprocessing section (zh ~:285-315, en ~:279-313): limits, `#pragma once`, macro diagnostic.
- **Validation**: V0, V1.

**C06 feat(preprocessor): include search paths and an include-root policy**
- **`AzScript`.** `includeDirectories`, `unrestrictedIncludes`, `includedFiles()`, and a new `execute(String source, Path baseDirectory)` overload.
  - Roots are the main dir (or base/CWD), then the `-I` dirs, then the default stdlib dir.
  - Search order: the including file's directory, then `-I` in order, then the default. The **first existing regular file** wins.
  - Paths are checked lexically before probing, and the real path is checked again (symlink escape).
  - Absolute include text is rejected unless unrestricted.
  - Messages: `Include not found: "x" (searched …; N candidates outside the allowed include roots were not searched)`; the escape and absolute messages name `-I`/`--unrestricted-includes`.
  - Quoted syntax is strict, angle includes are rejected, and the unquoted form is kept.
  - Labels follow R4.
- **`Main`.** `-I/--include-dir`, `--unrestricted-includes` (compile only; compile-json rejects them). The default dir comes from the **single-path** property `azscript.stdlib`. New `Main.run(args,out,err,defaultIncludeDirs)` overload. The output-alias check also covers included files.
- **Launcher** derives `<package>/stdlib` from `launcher.jar`'s code source, so `compile.sh`/`compile.cmd` are unchanged. The repo `./azscript` wrapper passes `-Dazscript.stdlib=<repo>/compiler/stdlib`.
- **Migrations.**
  - `tests/math_library.py` (:225, :256, :269-270, :294, :305, plus compile helpers :38, :192, ~:313) and `tests/containers_library.py` (:183, :200, :245, :122, :231) switch to `#include "<name>.include.azs"` plus `-I ROOT/compiler/stdlib`.
- **Tests.**
  - `PreprocessorTest` policy cases: search order; absolute include without leaking a marker; traversal; symlink escape; missing or irregular target; syntax; `-I`-root labels.
  - New `compiler/src/test/java/azertia/MainTest.java`: options anywhere; `--unrestricted-includes` last; missing `-I` value; compile-json rejects include options; defaults overload; output aliasing an included file.
  - `tests/end_to_end.py` include CLI cases.
  - `tools/test_distribution.py`: a client outside `examples/` includes `math.include.azs` with no `-I`; `-I "dir with spaces"` keeps the three outputs; absolute rejected and then accepted with the flag; relocated rebuild stays byte-identical.
- **Docs.**
  - LANGUAGE: CLI (:20), `execute(String)` (:30), preprocessing (policy, lexical `..`, the whole subtree of every root is readable, embedders should use `execute(String, Path)`);
  - README: :54/:58, :77, :96-124;
  - USAGE: :5, :50-66; QUICKSTART: ~:93-96; MATH and CONTAINERS guides.
- **Validation**: V0, V1, V3.

**C07 fix(compiler): reject reserved namespaces 0000 and 0abd**
- `#namespace` rejects 0 and 0abd with the design's messages. **`fff` is accepted** as an explicit spelling of the default.
- Compile-json rejects non-hint `function-namespace` 0 and non-hint namespace-0 definitions other than ids 0/1.
- **Tests.** `CompilerTest` invalid list (:229-232) and compile-json cases; `#namespace 0` added to hint_linking `bad_sources`.
- **Docs.** LANGUAGE :60-62 (also: IDs are written once in shared headers and implementations inherit them, HINT_LINKING :15); README :215/:209; HINT_LINKING declarations; abdJavaInvoker/README :50.
- **Validation**: V0, V1.

**C08 perf(compiler): O(1) symbol tables and linear global phases**
- **Profile the 20k-function probe first.**
- `LogicEnvironment`: a per-scope `HashMap`, iterative lookups and a cached `root()`; the public fields are kept.
- Statics: `BUILTIN_NAMES`, reserved sets, operator sets (:41-43, :1000, :1006, :1290; GeneraterJson :500, :1205); a precompiled integer `Pattern` (:164, :177).
- Lookup structures: `ClassInfo.fieldOffsets` (:600); an inverted `abstractIds` (:1832); parser `globalNames` and a class-name set (:158, :898, :900); `assignFunctionIds` precomputed sets (:884); `TYPE_QUERY` maps pooled (:180-191).
- ExecCodec `KeySpec` constants (:54-61); size without cloning (:65-72).
- `abdJava` gains `AbdValue.size()` and `AbdSimpleStack` writes directly; no wire change.
- **Tests.** New `CompilerScalingTest`:
  - deterministic `CompilerMetrics`: 8x input gives at most 10x work;
  - timeouts set at 5x the measured post-fix time, with metrics read inside the timed block.
- **Tools.** Opt-in `tools/bench_compiler.py` (not registered).
- **Docs.** abdJava README (`size()`).
- **Validation**: V0, V1 (includes the AbdRegression byte fixtures).

**C09 refactor(compiler): one source of naming rules; compiler-authoritative class layout validation**
- **New `SourceRules.java`.**
  - `KEYWORDS` = statement keywords plus `any`; used by `Parser.name()`, because type names are read through `name()` (:611-613, :1006).
  - `RESERVED_WORDS` = `KEYWORDS ∪ TYPES ∪ {true,false}`; used by `identifier()`, `declaredName()` and `Compiler.validateSourceName` (:1286-1293). The latter now also covers functions, parameters, globals and source locals.
  - A shared identifier and hint regex. AST hex stays strict (:1756-1759). ExecCodec keeps its `Invalid exec:` wrapping.
- **Parser** loses `validateClassLayouts`/`valueNesting` (:737-790), keeping only the direct base-initializer check. The compiler checks layout with C01 locations, including generic value-field cycles.
- **Tests.**
  - `TypeSafetyTest` (new): `any` rejected everywhere with a location; `int x; double f(int a)` still parse; located class errors (cycle, empty class, self-value, generic `A<T>{B<T> b;}`/`B<T>{A<T> a;}`);
  - a Java/C++ hint-rule parity test (C++ `valid_hint`, library.cpp:114-118, in runtime_tests);
  - update any assertion on old parser-located layout messages.
- **Docs.** Reserved words: LANGUAGE (zh :311, en :309), README (zh :211, en :205).
- **Validation**: V0, V1.

**C10 feat!(types): nominal class typing**
- **Delete `equivalent()`** (:308-328). `assignable` uses identity for values (no slicing) and the base chain for pointers only.
- **New `commonType(a, b, strict)`**:
  - identical → that type;
  - both numeric → the arithmetic type;
  - null with pointer/(*)/address → the other;
  - subclass pair → base pointer;
  - otherwise ANY, unless a class, owned value or type parameter is involved, in which case it is an error.
  - `branchType` (:259-264) uses it, so pointers cannot be laundered through an expression-if.
- The index getter/setter check (:1534) and `sameSignature` (:1775-1787) use identity. `GeneraterJson.sameSourceType` (:824-838) becomes string equality after `renameType`.
- **Tests.**
  - Rewritten: `CompilerTest` :1052-1062, :1083-1091 (long nominal chain), :1093-1100 (inverted to `assertThrows`), :1232-1233 (moved to invalid);
  - moved to bad sources: address_end_to_end.py:114-116, inheritance_end_to_end.py:137-145;
  - hint_linking_end_to_end.py:123-131 becomes a shared-header success case;
  - `TypeSafetyTest` nominal cases: fieldless derived literal → base literal rejected; Seconds/Meters (probe c5); A* to field-less A2* rejected; related equality accepted.
- **Docs.**
  - LANGUAGE classes, inheritance and generics: remove the structural wording;
  - HINT_LINKING :5, :17, :25, :29, :110: nominal **within one compilation unit**; across modules the shared declaration is the unchecked contract;
  - QUICKSTART :77, :134.
- **Validation**: V0, V1.

**C11 feat(lang): typed global declarations**
- **Syntax**: top-level `T name;` or `T name = expr;` with T a scalar, `address` or class pointer.
  - An automatic global is recognized only when `)` is followed by `;` (reuse the `fieldConstruction()` lookahead, :629-640).
  - AST: object entries in `global-variable` after the legacy strings.
- **Compiler.** Globals are resolved **after `TYPE_SCOPE.set(Map.of())`** (stale scope at :1854/:1894). Defaults, then initializers, are **spliced into the top-level statement list** of the id-0 onload, which is synthesized when absent, through trusted `#global-initialize` nodes in context `global g`.
- **Tests.**
  - `TypeSafetyTest` typed globals: defaults and order; prologue before the user onload; rejections (`P g;`, buffer, extern, `<T>`, void, duplicate, function name, `P * g(1);`); functions returning `P *` still parse; compile-json byte identity; a hint module with `Box<int>* b`; a generic extern followed by a global typed `T`;
  - new `tests/type_safety_end_to_end.py` (registered);
  - a JNI snapshot of typed pointer globals in run_jni_tests.
- **Docs.** LANGUAGE globals. State that `alloc()` or transferred automatic objects in initializers die when onload exits, and that void-window reads in cyclic initialization do not always fail. QUICKSTART :120; AST `global-variable`.
- **Validation**: V0, V1.

**C12 feat(classes): qualified base-method calls `Base::m(args)`**
- **Parser.** `primary` (:1318-1351): `Class::member(args)` becomes ctrl `base-call`. New `declarationAhead()` at :1109, :1150, :1358.
- **Compiler.** `baseCall` handles ancestor lookup, shadowed non-virtual members, `type-args`, and the errors.
- **Tests.** inheritance_end_to_end shadowed-call case; error cases (outside a class, non-ancestor, unknown method, `Base<int>::f()`).
- **Docs.** LANGUAGE :220/:226; README :83.
- **Validation**: V0, V1.

### Phase B: exec v10

**C13 feat!(exec): exec v10 with optional per-function debug records (the single bump)**
- **`ExecCodec.java`.**
  - `VERSION=10`. Root field 11 `debug_files`; function `has_debug` plus a `FunctionDebug` record.
  - The numbering wrapper around `expression()`; `locations(view, globals, positions)`; strict validation per §2.
  - Name and file character policy (both sanitized by the compiler, rejected by readers): reject C0, DEL, C1 (U+0080-009F), U+2028/2029, bidi controls and unpaired surrogates. File names must be unique and at most `MAX_SLOTS`.
- **`Compiler.java`.**
  - `compile(JsonObject, boolean debugInfo)`; `recordPosition` (objects and arrays only, not scalars or opcode-3 reads; `putIfAbsent`).
  - `debug-files` deduplicated from `sources` units. Debug names: `C::C`, `C::~C`, `C::operator+`, `<new C>`/`<scoped C>`/`<value C>`/`<placement C>`. Synthesized functions use the class position.
  - Emitted after `normalizeContexts` (:1954).
- **`Main`/launcher**: `--strip-debug`. The launcher needs no change because it uses the shared parser.
- **Interpreter.**
  - `EXEC_FORMAT_VERSION=10` in library.h; checks at library.cpp:804/:876: `Unsupported exec version N; expected exec v10`.
  - The decoder numbers nodes at entry of `parse_raw_expression` and reads debug before parsing the body (:850-856). Leftover entries are rejected. `ofunction::debug` and `expression::location` are stored.
- **JNI** (`abdjni/lib.cpp:554-575`). Snapshot restore first checks each embedded module's exec version: `Snapshot modules use exec vN; expected v10`. The snapshot version stays 9.
- **Fixtures and literals.**
  - `CompilerTest` :87 and **:785-789** (11 root fields; 9 or 10 function fields; both modes tested); `BufferCodecTest` :33; `GenericContextCodecTest` :25; `AddressCodecTest` (add `debug-files`).
  - `GenericCompilerTest` :40 and `CompilerTest` :1032: class names absent from stripped output and from instruction subtrees.
  - `tests/*.py` version asserts: address :52, buffers :63, buffers_snapshot :70/:90, math :175, containers :149, hint_linking :42, numeric_slots :33, compact :158/:441-442/:533.
  - compact_exec writer and independent reader (:146-160, :249-300, :437-465) with independent pre-order numbering; add 9 to the "previous-version" rejection list (:600).
  - runtime_tests `rmodule`/`rfn` and the fixture encoder (:118-125, :240-262).
  - run_jni_tests :45-47, :347.
  - tools/test_distribution.py :18 and :365 messages (its exec reader handles the 11-field root and debug records).
  - export manifest (tools/export_distribution.py:235); test_export_distribution :50 regex derived from `ExecCodec.VERSION`, plus a check that `library.h EXEC_FORMAT_VERSION == ExecCodec.VERSION`.
  - Sentinels derived from the registry (compact :633, test_export :42).
- **Tests.**
  - New `DebugInfoTest`: probe e1 positions map to `divide` and `call` nodes; strip mode; compile-json roundtrip with classes, operators, generics, `for`, compound assignment and synthesized factories and destructors; every malformed-debug error path.
  - compact_exec malformed-debug mutations through `azscript-run`.
  - runtime_tests malformed debug, with insert leaving state unchanged.
  - A JNI snapshot from a debug build fails to restore into a stripped build.
- **Docs**: all in this commit, zh and en.
  - EXEC_FORMAT: v10 layout, numbering, validation, non-semantic statement, `--strip-debug`, amended class-name contract, the renamed inspection JSON.
  - LANGUAGE :420/:424/:462 (exec-version 10; `_line/_column` now also feed debug records; Java API).
  - README v10 mention and privacy note (en :212: exec carries function and file names unless stripped).
  - USAGE :42/:173; QUICKSTART :126; HINT_LINKING :5/:108; EXTERN_LIBRARY :103; abdJavaInvoker/README :69.
- **Validation**: V0, V1, V2, V3.

**C14 feat(runtime): script stack traces for runtime errors**
- **`library.h`**: `trace_frame`/`error_trace`, `last_error_trace()`, `format_error_trace()`, `describe_error(const std::exception&)`, `clear_error_trace()` (clears both the active and the published trace), public `function_id_text`, `pending_error`, `source_position`, `function_debug`.
- **`library.cpp`.**
  - `run()`/`at_location` at **all 23** `->execute(` sites. `code->location` is applied in `invoke_bound` before `execute_body` (:1230); the nested `execute_body` (:1245) is covered too.
  - `cleanup_expression` saves and restores the location. `return_location` is set by a shared "mark returned" helper used by `returnExpression` **and opcode 38** (`context_expression` :426-435).
  - `invoke_bound` notes frames during unwinding with `throw;` (C++ exception types and JNI mapping unchanged). `checked_external_function` adds native frames for non-0xabd callbacks and discards the active trace on normal return.
  - `entry_scope` **clears the active trace at every entry**, nested included, and publishes on exceptional exit (move when outermost, copy when nested). It covers load, insert, flush, invoke, destroy, `ofunction::invoke`, `invoke_bound_function` and `checked_external_function::invoke`.
  - Thread-local state is a trivially destructible `thread_local` pointer; publishing is `noexcept`.
  - `describe_error` appends frames only when `typeid` and `what()` match the published record.
  - Format: frames joined by `\n`, no trailing newline, `  at name (file:L:C)`, `  at function 0x…`, `  at native function 0x…`, `  ... N more frames` (cap 64). Columns are 1-based UTF-16 units.
- **`pending_error`** replaces every raw `std::exception_ptr`: library.cpp :284-329, :1181-1199, :1396-1417; blocks.cpp :191-203, :240-258, :335-352, :399-448, :579-599, :669-691, :709-710; heepalloc.cpp :233-265. `blocks::finalize_storage` takes `pending_error&` (blocks.h:77-78). A grep gate in V1 ensures no raw `exception_ptr` remains outside `pending_error`.
- **Hosts.**
  - `interpreter/test.cpp`, `examples/embedded.cpp`, `tools/distribution/examples/cpp/main.cpp`: call `describe_error` before `destroy`.
  - `abdjni/lib.cpp`: `boundary()` clears the trace. The message goes through the UTF-8 `toJava` path, with `ExceptionClear` before the `ThrowNew` fallback. `JavaException` identity is untouched. The swallow at :701 also clears.
- **Tests.**
  - New `tests/runtime_trace_end_to_end.py` (registered), exact stderr:
    - the probe e1 three-frame trace; overflow; step limit; recursion cap;
    - class frames; a generic `return_typed` frame;
    - a destructor failing during unwinding (first error's frames only);
    - onload and pre_destroy failures; a hint library frame; stripped output; an include-file frame.
  - JNI: exception types preserved, Java callback identity kept, no stale trace.
  - runtime_tests: types and traces; first-error purity on paths (a)-(f) from the design; **nested host re-entry where a swallowed failure is followed by a second failure**; another thread does not disturb the trace.
  - `JniRegression.java:164`: exact message plus trace lines.
- **Docs.** LANGUAGE "Runtime errors and debug info"; README C++ embedding API; USAGE/QUICKSTART output; abdJavaInvoker/README (multi-line messages); EXTERN_LIBRARY (native frames; rebuild plugins).
- **Validation**: V0, V1, V2, V3 (public header change: cmake/runtime.hpp.in, include/azscript/runtime.hpp).

### Phase C: type rules and language features within v10

**C15 feat!(types): boolean conditions and compatible equality**
- **Static.** `conditionType` accepts BOOLEAN (or ANY) only. This covers if, while, for, the functional forms, `!`, `&&` and `||`. Message: `if condition must be boolean, got int`. A root diagnostic-label map gives the `for condition` label. `isTrueLiteral` stays numeric-tolerant only so that the condition error is reported first.
- **Static equality.** `requireComparable` implements the pair table and reports `Equality requires compatible operand types, got string and int`.
- **Runtime.**
  - `truth()` (library.cpp:141) accepts only BOOLEAN; otherwise `std::runtime_error("Condition must be boolean, got int")`.
  - `eq`/`ne` throw for non-numeric values with different tags. This check runs **after** the address branch (:158-179), so the `address operands` messages (address_end_to_end.py:199-200) stay. `void==void` stays true.
- **Tests.**
  - Updated: `CompilerTest` :421 (now throws) and :582 (`while(true)`); loops_end_to_end.py:128 (`1/0==0`) and :136 (`must be boolean`); runtime_tests numeric conditions at :966, :1048, :1049, :1107, :1108, :1124, :1152, :1265, :1269 use `BoolAbdValue`.
  - New: `TypeSafetyTest` condition and equality cases (probe s2); runtime_tests for `truth`/`eq`; type_safety_end_to_end with a dynamic `def(x,1)` condition.
- **Docs.** LANGUAGE condition rule (remove "nonzero numeric") and equality table; EXEC_FORMAT v10 semantics.
- **Validation**: V0, V1, V2.

**C16 feat(lang): bitwise and shift operators (opcodes 43-48)**
- **Lexer and parser.** Tokens `& | ^ ?` (`?` is reserved for C19) and `&= |= ^=`. Adjacency-based `<< >> >>> <<= >>= >>>=`. `PRECEDENCE` becomes `|| < && < | < ^ < & < == != < relational < shifts < + - < * / %`. Unary `~` lowers to `bit_xor(x, -1)`. Compound assignment uses the full operator text (:1227).
- **Compiler.** Operands must be int or any. Boolean operands get the hint "parenthesize comparisons, e.g. (a & b) == 0". Literal shift counts must be 0..31.
- **Runtime.** `bitwise()` on uint32 bit patterns. A bad shift count throws **`std::runtime_error`** (Java IllegalStateException).
- **Registry.** ExecOpcodes/ExecCodec 43-48; `parse_raw_expression`; compact_exec `OP_NAMES`, encoder, reader and bounds (:333, :538); test_distribution `CONTROL_OPCODES=range(3,49)` plus the feature probe; test_export acceptance.
- **Tests.** New `LanguageOpsTest` (precedence; generic closers next to shifts; `a > > b` rejected; diagnostics); new `tests/language_ops_end_to_end.py` (registered); runtime_tests boundaries.
- **Docs.** LANGUAGE precedence table (en :232-246) and an operators section; EXEC_FORMAT rows and semantics; README summary.
- **Validation**: V0, V1, V2, V3.

**C17 feat(lang): explicit conversions (opcode 49 `convert`)**
- **Parser.** Conversion names via `declarationAhead()` (`int(x);` is an expression statement). `callTypeArgumentsAhead` tightened (:1300-1311). A `(TYPE)` followed by an expression start gives `C-style casts are not supported; write int(x)`.
- **Compiler.** `compileConversion` follows the matrix in §5. Identity on a known type emits no node. `any` sources always emit `convert`. `int`↔`address` and class/buffer/null/type-parameter sources are rejected. Conversion names are reserved in `isBuiltinName` (:41-43).
- **Runtime `convert_value`.**
  - Same-tag identity for all five targets.
  - Portable float parsing: grammar check, then `strtod_l`/`_strtod_l` with the C locale or a classic-locale `istringstream`; `strtof` for float. `from_chars` only behind a feature test.
  - **Underflow to zero or subnormal is allowed** for both numeric and string sources; overflow is an error.
  - Quoted user text is ASCII-escaped and truncated on whole characters (JNI modified-UTF-8 safety).
- **Registry**: opcode 49 everywhere.
- **Tests.** `LanguageOpsTest` conversions; runtime_tests matrix (`2147483647.9`, `'+7'`, `' 1'`, `'1e400'`, float `16777217`, `int(any int)`, `string(any string)`); a JNI message with an emoji and NUL.
- **Docs.** LANGUAGE "Explicit conversions" (matrix and errors), :169 address section; QUICKSTART :124; EXEC_FORMAT `convert`.
- **Validation**: V0, V1, V2, V3.

**C18 feat!(types): checked return conversion (widening only)**
- **Static.** `returnCompatible` accepts `assignable` or the widenings int→float/double and float→double. Narrowing reports `return expected int, got double; narrowing requires an explicit cast such as int(x)`.
- **Runtime.** `convert_return` (library.cpp:469-490) uses the same rule and reports `Function return type mismatch: expected int, got double`.
- **Stdlib** (`compiler/stdlib/math.azs`). New `math__domain_error_int`, used at :24, :63, :158, :192, :210, :217, :225, :245, :279, :290. `math_trunc` returns `int(whole)` behind its existing domain guard (:190-207).
- **Tests.** `TypeSafetyTest` returns (probes s1, s4); runtime_tests `convert_return`; math_library.py outputs unchanged ("Division by zero").
- **Docs.** LANGUAGE return rule; EXEC_FORMAT; MATH guide only if its wording mentions truncation.
- **Validation**: V0, V1, V2.

**C19 feat(lang): conditional expression `?:` (opcode 50 `select`)**
- **Lexer.** The colon-path fix per R8; `numberToken(strict=false)` restores offset, line and column on failure.
- **Parser.** `conditional()` sits **inside the existing assignment recursion guard** (no extra level). It is right-associative.
- **Compiler.**
  - The condition must be boolean (shared helper). Arms are compiled in the current environment; types come from `commonType(strict)`, and the narrower numeric arm is wrapped in `convert`. Void arms are rejected.
  - `T` with a pointer bound plus `null` behaves like pointer plus null.
  - **`requireWritableReceiver` recurses into ternary arms** (:1011-1020).
  - The warnings check the ternary condition.
- **Runtime.** `select_expression` evaluates the condition, then one arm, in the current scope.
- **Tests.**
  - `LanguageOpsTest` colon-ID regressions (`:0002`, `:12340001`, `:1e5`, `:000a`, `:4d410008`, `:1e999`, `: Base(1)`); typing; `(c ? v[0] : w).x = 1` rejected.
  - runtime_tests: laziness; an owned literal or automatic pointer from an arm survives until statement or block end.
  - e2e cases.
- **Docs.** LANGUAGE "Conditional expressions"; EXEC_FORMAT `select`; readable-AST nodes.
- **Validation**: V0, V1, V2, V3.

**C20 feat(lang): `switch` statement**
- **Parser.** Keywords `switch`, `case`, `default` (in `SourceRules.KEYWORDS`). AST: `switch` → `case` sections with labels and `default:true`. Errors for a statement before the first label and for `case`/`default` outside a switch. `break` may appear in a loop or switch.
- **Compiler.** The scrutinee must be int or string. For `any`: `… convert it, e.g. switch (int(value))`.
  - Labels must be literals of the scrutinee type; duplicates (case or default) are located errors.
  - C#-style rule: a non-final section that can complete normally is an error. The final section gets an implicit break.
  - Lowering per R10. `#switch-loop` is in the VOID list. `continue` is rewritten only when `SyntheticLoop.outerLoopDepth > 0`; otherwise it is located at the `continue`.
  - `canCompleteNormally`/`containsBreak`/`containsContinue` understand switches. Hidden names are unique per function.
- **Tests.** `LanguageOpsTest` (shape, lowering, every error, missing-return analysis, nested switch with continue); e2e (string and int scrutinees, default in the middle, loops); a test_distribution probe.
- **Docs.** LANGUAGE "switch" (no fall-through, scopes, tick cost O(labels until match)); reserved words.
- **Validation**: V0, V1, V3.

**C21 feat(lang): string functions, methods and indexing (built-ins 0x0abd000b-13)**
- **Compiler.** `BUILTINS` additions; a `STRING_METHODS` table. Calls are emitted directly as `{t:1,id}`, so a class method with the same name cannot hijack them; `rejectVoidValue` applies to arguments.
  - `s[i]` maps to `char_at`. `s[i]=` reports `Strings are immutable…`. `receiverClass` gets a string message (:1242-1247).
- **Runtime.** `string_function` in internelFunctions.cpp works on code points. UTF-8 helpers live in the **non-installed** `interpreter/utf8_internal.h`. Index errors are `std::out_of_range`; bad UTF-8 is `std::invalid_argument`.
- **Tests.** `LanguageOpsTest`; runtime_tests (ASCII, CJK, emoji, NUL, invalid host UTF-8); JNI (non-BMP result, IndexOutOfBoundsException); an e2e example `compiler/examples/operators-regressions.azs` (returns 0) run by language_ops_end_to_end.
- **Docs.** LANGUAGE "String functions"; built-ins table grows from 11 to 20 (en :64-80); remove "array subscripts unsupported" (en :275); README :77; QUICKSTART :124.
- **Validation**: V0, V1, V2, V3.

**C22 feat!(types): class pointers stay typed; object storage is closed to raw `mem_get`**
- **Compiler.**
  - `rejectUntypedObject` (:1212-1215) also rejects pointer and `(*)` types. That covers `var`/`def`, untyped parameters, `#gvar`, and `mem_get` stores. Message: `… cannot hold a class pointer (P *); declare it as P * or convert explicitly with address(p)`.
  - Call arguments to ANY parameters (:471-472) follow the same rule, except `print` and the variadic arguments of `reflect_invoke_function`.
  - `#allocate` (:656-664) lowers to the internal `object_alloc` (0x0abd0014).
- **Runtime.**
  - The heap tags ranges allocated by `object_alloc`; free, destroy and `clearHeap` clear the tags. Snapshot restore re-tags registered objects (no snapshot field).
  - **`f_get` (heepalloc.cpp:401-403)** rejects tagged ranges and literal-block addresses with `std::runtime_error("mem_get cannot access object storage; use typed member access")`.
  - The decoder turns `CALL 0x0abd0006(oa(...))` into a library.cpp-local member-access node that bypasses the guard and is accepted as a `mov`/`drop` target (library.cpp:694-699, :712-716). library.h is unchanged.
  - `mem_free` keeps raw release.
- **Tests.**
  - Updated: address_end_to_end.py:147-148 (`address raw = address(new C());` plus the runtime guard error; `mem_free` separately); classes_end_to_end.py:91; generics jni-generic (:298-305) uses a typed global plus `address(saved)`; runtime_tests raw reads (:561-562, :574-575, :646, :1396-1400, :1405-1433) wrapped in `object_address` or turned into rejection cases; run_jni_tests :460 (`oa`); buffers_snapshot.py:41.
  - New: `TypeSafetyTest` untyped cases; probes o4/o5; the constructor `address(this)` laundering repro; runtime guard on block start, interior and in-construction allocations.
- **Docs.** LANGUAGE untyped policy (:127, :143) and the addresses section; EXEC_FORMAT literal-objects paragraph and built-in 0x0abd0014; QUICKSTART :124. Extern returns and host-supplied `C*` arguments are documented as **trusted** (`oa` still bounds-checks), with one JNI test.
- **Validation**: V0, V1, V2, V3.

**C23 feat(types): checked reflection results (opcode 51 `check_object`)**
- **Emission.** Only around `reflect_invoke_function<R>` results where R is a class pointer, class value, buffer or type parameter (Compiler :1123-1132). `slot_types` are the ABI tags of the direct fields (6 = unchecked, e.g. `T` fields).
- **Semantics.**
  - Pointer: null, or the start of a registered, not-destroying object (or a live non-buffer literal block) with at least `|slot_types|` slots, each slot tag matching. Value: width equals the count, plus tags. Buffer: element contexts match. A reference context checks liveness for its bound kind.
  - Message: `Object check failed: …`. Never emitted for dispatch or construction.
- **Registry**: 51.
- **Tests.** Codec roundtrip and rejections; runtime_tests (raw alloc, freed object, too small, wrong slot tag, destroying, block, buffer); generics_end_to_end probe c7 (heap ends empty); `GenericCompilerTest` emits one `check_object`.
- **Docs.** EXEC_FORMAT "Checked references"; LANGUAGE reflection (liveness plus slot tags; class identity is **not** checked; pointer-field targets are not verified).
- **Validation**: V0, V1, V2, V3.

**C24 feat(lang): virtual methods (opcodes 52-53) and JNI snapshot v10**
- **Parser.** Prefix modifiers `virtual`/`override`/`final` (plus `extern`). Their presence forces a method parse, bypassing `fieldConstruction()`. AST arrays `virtual-methods`/`override-methods`/`final-methods` appear only when non-empty, so non-virtual ASTs stay byte-identical. Override structure errors are located.
- **Compiler.**
  - `ClassInfo.vtable` and slots are built in `finishLayout`; signature checks in `validateVirtualMethods` (covariant pointer returns allowed).
  - Dispatch is decided in **`bindCall`** (:1082-1084, so method names that match builtins are covered) and in `memberCall` for pointer, `(*)`, `this` and type-parameter receivers. Value receivers, `final` slots and `Base::m` bind statically.
  - Call sites are tracked in `CompilationState.virtualCalls`; there is no JSON marker. `#set-methods` runs after `#check-this` in constructors (inside the protected body for derived classes) and at destructor entry. Its context depth is checked at compile time.
- **Runtime.**
  - Method tables live in a heap map and in `slot_block::methods`. Copies propagate them; `assign_block` keeps the target's table.
  - `set_methods` requires that the executing script owns the object; it **charges steps per entry** and caches context-free tables per node.
  - `call_virtual` follows CALL semantics: null, expired, no-table and foreign-script errors; slot bound; return-tag check.
  - `flush` validates entries: an ordinary script function with an address first parameter and a matching hidden count.
  - `discard_script_objects` erases owned tables; both `restore` overloads swap the map. `object_records()` stays pure; the orphan check lives in the JNI snapshot path; `method_table_count()` is exposed for tests.
- **JNI.** Required `methods` on object and literal/view records; **snapshot version 10** (lib.cpp:504/:554); run_jni_tests hand-written records (:247-260, :369-374) plus a "missing methods" corruption case; manifest `jniSnapshotVersion` 10.
- **Tests.**
  - New `VirtualMethodTest` and `VirtualCodecTest`; runtime_tests `virtual_tests()`.
  - New `tests/virtual_methods_end_to_end.py` (registered): probe g1 prints 100; constructor and destructor phases; cross-hint dispatch in both insert orders; buffers and Vector; error cleanup; JNI snapshot; `compiler/examples/virtual-regressions.azs` as the documented example, also listed in test_distribution (:145-147).
  - numeric_module_host also checks `method_table_count()==0`.
- **Docs.**
  - LANGUAGE "Virtual methods" (zh and en; rewrite :116, :220, :226), exec JSON table, readable-AST keys;
  - README :81-83; EXEC_FORMAT 52-53 and the snapshot; HINT_LINKING (header slot-order contract);
  - QUICKSTART :73/:79; USAGE :173; abdJavaInvoker/README :69-71;
  - the AGENTS core-contract wording only if the user approves (§7).
- **Validation**: V0, V1, V2, V3 (blocks.h is public through cmake/runtime.hpp.in).

### Phase D: limits (no format change)

**C25 feat(limits): exact ABD measurement and statement-level spilling**
- **New `ExecShape.java`.** Roles and level rules for opcodes 0..53 and −1. It fails closed. A per-opcode boundary test compares it with the codec's depth failure.
- **New `AbdLinearizer.java`**, per function after compile and before `normalizeContexts` and debug emission.
  - Spills at statement level: initializers, returns, expression statements, if conditions, and for conditions and steps (which sit inside the iteration block).
  - **Cuts inside lazy operands (`and`/`or` right side, `select` arms, expression-if) or inside while conditions are rejected** with a located reason ("store this operand / condition in a local variable first").
  - Owning captures are dropped right after the statement. Returns with captures become `vd r = E'; drops; return mv/v r`.
  - The order-insensitive rule for consuming owning reads excludes statements containing calls, `m` on `mem_get`, mutating `buffer_op` (2/4/5/6), or `vs`/`m`/`mv`/`drop` on the same slot.
  - `m` targets: the `mem_get` address operand is captured when the value is cut.
  - `owningResult` by opcode: 32, 34, 40 always; 41 op 3 with an owned element type; 39 inherits; calls and `call_virtual` per `FunctionTables`. Unknown opcodes count as owning.
  - New records inherit the split position in the positions map.
- **`withDepth`** no longer throws at 128. Its guard is `2×TreeLimits.MAX_NESTING` (640): `Expression nesting exceeds the compiler limit of 640 levels`.
- **The public `compileExpression`** runs `TreeLimits.validate`, then linearization or a sequence rejection, then `requireFits`. The 34 internal sites call a private `compileNode`.
- **Test-only limit**: system property `azscript.internal.abdLimit` (32..128), read by the Compiler. It is not in help or the launcher.
- **Tests.**
  - `CompilerTest` flips: :544-556 (131 operands and `id(`×70 compile; replaced by a located true-nesting failure), **:708-714** (`.next`×40 compiles). :878-891 stays failing.
  - `tests/end_to_end.py:88`: 130 operands move to success (`130\n`).
  - New `DepthLimitsTest`; new `tests/limits_end_to_end.py` (registered; also under `--sanitize`).
  - Differential run: each example at limits 96, 64, 48, skipping infeasible ones with the reason. Splitting must be exercised across the corpus. Outputs must be identical.
- **Docs.** LANGUAGE :56 and :313 nesting paragraphs: exact rule, rejected lazy and while splits, postfix chains are true nesting, `getDepth()` returns implementation-defined scope depth. EXEC_FORMAT :109/:137 context counting. README :210/:204.
- **Validation**: V0, V1, V2.

**C26 feat(limits): flat operator chains (`chain` AST above 128 operands)**
- **Parser.** `binary()` collects one precedence level. At 128 operands or fewer it builds today's nested JSON, so every program that compiles today keeps byte-identical AST and ABD. Above that it emits `chain` with per-operator positions.
- **Compiler.** All-`&&` or all-`||` chains are balanced. String `+` chains are balanced only when every operand is a literal, a string local or parameter, or a call with a checked string return. Everything else is left-folded through `#precompiled` placeholders (kept in `FunctionTables`, not `trustedNodes`) in 64-operand segments using `INTERNAL_SEQUENCE`. Strict AST schema checks apply.
- **Tests.** `CompilerTest` :505-513 (the 2000-term chain compiles); `DepthLimitsTest` chain cases; limits e2e (10k strings, mixed `1+2+"x"`, overflow mid-chain, float fold).
- **Docs.** LANGUAGE AST contract (`chain`).
- **Validation**: V0, V1, V2.

**C27 feat(limits): flat else-if ladders (`if-chain` above 128 conditions)**
- **Parser.** Collects `else if` iteratively. At 128 or fewer conditions it builds today's nested nodes.
- **Compiler.** Break-out lowering through `SyntheticLoop`: break and continue flags are spliced as two statements, not wrapped in a block. `canCompleteNormally` handles `if-chain`.
- **Tests.** `DepthLimitsTest` (200 and 10k conditions; nested loops; the budget case); limits e2e.
- **Docs.** LANGUAGE AST (`if-chain`); README limits.
- **Validation**: V0, V1, V2.

**C28 docs: record validation for the compiler-improvement stage**
- VALIDATION.md and VALIDATION.en.md get a dated, scoped entry with actual V1, V2 and V3 results: scaling metrics and timings (locals 2k/8k/32k, 20k statements and functions, 10k chain and ladder) and the differential run. Final probe sweep: every scratchpad probe is encoded as a regression somewhere in C01-C27.
- **Validation**: V0, V1, V2, V3.

---

## 5. Final design decisions

| Area | Syntax and semantics | Errors and messages |
|---|---|---|
| Diagnostic line | `file:L:C: error|warning|note: [in <ctx>: ]msg[ [-Wcode]]`. Notes: `in file included from here`, `in expansion of macro 'X' defined here`, `previous definition is here`. File names are stored labels (R4). | Summary `Error: compilation failed: N error(s)[, M warning(s)]` / `… internal compiler error`; exit codes 0/1/2 |
| Recovery | Parser: per declaration, member and statement. Semantics: per function, class and global item. Cap 20. | `note: too many errors emitted (limit 20); stopping` |
| Warnings | `assign-in-condition` (on), `unreachable-code` (on), `unused-variable` (off) | `-Werror` gives `[-Werror=code]`; an unknown code is a usage error |
| Includes | Search: including dir, `-I`, default stdlib. Roots enforced. Absolute paths rejected unless `--unrestricted-includes`. `#pragma once`. Limits 16 MiB/file, 16 Mi chars, 4096 includes, 4 Mi tokens, 64 levels. | `Include not found: "x" (searched …)`, `… escapes the allowed include roots (…)`, `Absolute include path "…" is not allowed; …` |
| Namespaces | `#namespace 0`/`0abd` rejected; `fff` accepted | `Namespace 0000 is reserved …` |
| Reserved words | Statement keywords plus `any`, `switch`, `case`, `default`, `override`, `final`, and type names; `str_*` and conversion names reserved as built-ins | `'x' is a reserved word …` |
| Typing | Nominal classes; derived-to-base pointers implicit only; no value slicing; `commonType` for branches and ternary | `… expected A, got B` |
| Untyped positions | Never hold class pointers, `(*)`, values, buffers or `T`. `print` and reflection variadics are exempt. `address(p)` erases. | `… cannot hold a class pointer (P *); declare it as P * or convert explicitly with address(p)` |
| Raw memory | Raw `mem_get` rejects literal blocks and compiler-allocated object storage (constructors included); member access is unaffected | `mem_get cannot access object storage; use typed member access` |
| Conditions and equality | Boolean only (static and runtime). Equality on numerics, identical primitives, null/pointer/address pairs, related pointers, and `any` with non-class values. | `if condition must be boolean, got int`; `Equality requires compatible operand types, got string and int` |
| Returns | Exact, or widening int→float/double and float→double | `…; narrowing requires an explicit cast such as int(x)`; runtime `Function return type mismatch` |
| Typed globals | `T g;` / `T g = e;` (scalar, address, `C*`); `#gvar` stays untyped; initialized in onload (defaults first) | `Global g cannot hold a class value or buffer; declare a pointer`, `Global objects cannot be automatic; use = new P(...)` |
| Bitwise | `& | ^ ~ << >> >>>` and compound forms on int32; shift count 0..31; `>>` arithmetic, `>>>` logical | `Shift count must be between 0 and 31, got 32` |
| Conversions | `int/float/double/string/address(x)`. Truncation is range-checked; strings parse with the literal grammar; identity for same-tag `any`; `int`↔`address`, boolean and class conversions are rejected. | `Cannot convert X to Y…`; `C-style casts are not supported; write int(x)` |
| Ternary | `c ? a : b`, right-associative, boolean condition, lazy arms in the current scope, numeric promotion through `convert` | `Conditional expression arms have incompatible types: X and Y` |
| switch | int or string scrutinee; literal labels; stacked labels; default anywhere; no fall-through; `break` exits the switch, `continue` the enclosing loop | `Switch section can fall through …`, `Duplicate case label 3` |
| Strings | Code points: `length`, `byte_length`, `substring(a,b)` (half-open), `char_at`, `code_at`, `index_of(n[,from])`, `contains`, `starts_with`, `ends_with`, `s[i]`; immutable | `String index 5 is outside length 3` (IndexOutOfBounds in Java) |
| Virtual | `virtual`/`override`/`final` member prefixes; `override` required; covariant pointer returns allowed. Calls through pointers, `this` and `T` dispatch. Constructor and destructor phases follow C++. `Base::m()` binds statically. | `Method C.m overrides virtual method B.m and must be declared override`; runtime `Object has no virtual method table…` |
| Stack traces | `AzScript error: msg` then `  at f (file:L:C)`, innermost first, cap 64. The C++ API is thread-local. JNI messages carry the trace; exception types are unchanged. | `  at function 0x…` (stripped), `  at native function 0x…` |
| CLI | Options anywhere: `-o`, `--ast`, `--exec-json`, `-I/--include-dir`, `--unrestricted-includes`, `--strip-debug`, `-Wall`, `-W<x>`, `-Wno-<x>`, `-w`, `-Werror`, `--max-errors N`, `--debug`. Properties: `azscript.stdlib` (default include dir), `azscript.internal.abdLimit` (tests only). | `Missing value for -o`, `Unknown option: …` |
| Limits | Exact ABD measurement; statement spilling; `chain`/`if-chain` only above 128; compiler nesting guard 640; JSON and AST guard 320, now located | `… statements are nested too deeply here; move inner blocks into a function` |

---

## 6. Risks and mitigations

- **Stricter runtime semantics break hand-written exec and legacy dynamic code** (boolean truth, throwing equality, no return narrowing, raw guard). v10 requires recompiling everything anyway. Every in-repo fixture is enumerated in C15, C18 and C22.
- **The `pending_error` conversion might miss a site**, so a swallowed cleanup error contaminates traces. The parameter-type change makes the compiler flag the sites; add a grep gate and the per-path regressions in C14; run V2.
- **Opcode, version and fixture drift across 12 format-touching commits.** Registry-derived sentinels, `library.h`/`ExecCodec` version parity, and V3 on every opcode commit.
- **Linearizer correctness** (order, ownership, environment). Lazy and while rewrites are excluded, ownership is classified by opcode and fails closed, there is a differential run across limits, and V2. Programs that compile today keep identical trees at limit 128.
- **Error paths of split statements**: hidden owning captures die at block cleanup, not at statement end. This affects only statements that could not compile before; it is documented and tested (each destructor runs once, the first error is kept).
- **Default-on warnings and empty-stderr suites.** The C03 warning-clean corpus test covers this.
- **Merge hotspots**: `GeneraterJson` lexer and preprocessor, `Compiler` catch sites, `library.cpp`. The strict commit order handles it, and C01 removes the token-constructor default so missed sites fail to compile.
- **Exec size and privacy** (+20-50% with debug info; names embedded). Mitigated by `--strip-debug` and docs. The limits suite measures size.
- **Public C++ ABI changes** (C14, C24). V3 consumer checks; docs say hosts and plugins must be rebuilt.
- **Platform**: macOS float parsing (portable path), MinGW TLS (trivially destructible), Windows include paths (`getRoot()`, `/`-normalized labels; only Linux is exercised here).
- **Residual soundness gaps (documented)**: no runtime class identity, so reflection can still return a different class with matching slot tags. Extern and host-supplied object pointers are trusted. Dangling pointers are open (§7).

---

## 7. Open user decisions

Genuinely open:
1. **Memory-safety probes are not covered by any workstream.** Use after delete with address reuse (c1, c4), double delete (c3), and automatic objects escaping into heap fields, returned aliases or globals (o1, o2, o3; typed pointer globals make o3 easier). Add a follow-up workstream (heap generation checks or quarantine plus escape analysis), or declare these out of scope?
2. **AGENTS.md / AGENTS.en.md core contract.** C24 (per-object method tables) and C13 (debug names in exec) contradict "the runtime uses object addresses and ordinary functions", and C24 relies on that wording. Approve a wording update in those commits?

Defaults decided by this plan (reversible; confirm):
3. Strict runtime checks for dynamic `any` values (conditions, equality, returns).
4. Default-on warnings `assign-in-condition` and `unreachable-code`.
5. Debug info on by default, with stdlib exec built with debug info.
6. `int`↔`address` conversions rejected.
7. No abstract methods, no class-pointer downcasts and no `--keep-ids` in v10. Adding them later needs a new format bump.

### Critical Files for Implementation
- compiler/src/main/java/azertia/script/Compiler.java
- compiler/src/main/java/azertia/script/GeneraterJson.java
- compiler/src/main/java/azertia/script/ExecCodec.java
- interpreter/library.cpp
- abdjni/lib.cpp