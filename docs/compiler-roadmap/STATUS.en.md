# Compiler improvement: status and remaining work

[中文](STATUS.md) | English

This branch (`claude/confident-galileo-fic3s1`) carries a planned series of 28 commits. They fix the compiler problems found in a repository review, add new language features, and add runtime line numbers. Work has resumed through C19; the next item is C20.

- [DESIGN.md](DESIGN.md): the detailed per-commit design, with file:line citations.
- [COMPLETENESS.md](COMPLETENESS.md): the review of that design.

**This file wins wherever they disagree.**

## Decisions made by the user

- **Scope:** fix all four problem groups: type-safety holes, diagnostics, limits and scaling, and the preprocessor and modules.
  - Add switch, `?:`, bitwise and shift operators, explicit conversions, string functions and virtual methods.
  - Add a runtime line table across every layer.
  - Do not restructure `Compiler.java` beyond what these changes need.
- **Debug info is opt-in with `-g`.** The default exec has no debug records. There is no `--strip-debug`.
- **Heap memory safety is out of scope for this round.** This covers use after delete with address reuse, double delete, and automatic objects escaping. Document these as Known limitations, planned for C22.
- **Function IDs:** documentation and better runtime collision messages only. No `--emit-header`/`--id-lock` and no default-namespace warning.
- **AGENTS.md/AGENTS.en.md:** update the core-contract wording where the changes require it. Debug info was done in C13; method tables come in C24.
- **Function-like macros:** reject them with a clear diagnostic only; they are not implemented.

## Done (each passed review plus its validation sets before commit)

| # | Commit | Summary |
|---|---|---|
| C01 | 732d3a7 | Source map. Diagnostics read `file:L:C: error: ...` with include and macro notes. Shared CLI argument parser; `--` ends options. |
| C02 | f0ef208 | Several errors per run (`--max-errors`), parser panic-mode recovery. `AstShape` validates compile-json input. Internal errors exit 2 (`--debug-compiler`). |
| C03 | 4af96e8 | Warnings `assign-in-condition`, `unreachable-code` and `unused-variable` (off by default). Options `-Wall`, `-W<x>`, `-Wno-<x>`, `-w`, `-Werror`. |
| C04 | 61ef61a | Runtime function IDs print as `0x%08x`. Duplicate-ID errors name the namespace, the module and a remedy. |
| C05 | 2ddfee1 | Bounded strict-UTF-8 source reading, `#pragma once`, preprocessing limits, a function-like macro diagnostic. |
| C06 | a8a26b3 | Include roots, `-I/--include-dir`, `--unrestricted-includes`, the `azscript.stdlib` default include directory. |
| C07 | e39c4a0 | `#namespace 0` and `0abd` are rejected. |
| C08 | 00d7bce | Constant-time symbol tables. `CompilerScalingTest` uses deterministic work counters. Opt-in `tools/bench_compiler.py`. |
| C09 | 8edfd20 | `SourceRules`: one place for reserved words, identifiers, hex and namespace rules. Class layout errors are reported by the compiler only. |
| C10 | 9ee79aa | Nominal class typing; `commonType()`. |
| C11 | 86471cc | Typed globals `T g;` / `T g = e;`, initialized in the onload. |
| C12 | cc4b022 | Qualified base calls `Base::m(args)`. |
| C13 | 5af1331 | **Exec v10**, the only format bump: optional per-function debug records with `-g`, and root `debug_files`. |
| C14 | 0b50e08 | Script/native stack traces; trace-preserving cleanup; UTF-8 JNI messages with original exception types and callback identity. V0–V3 passed on Linux x86_64. |
| C15 | 80cd387 | Boolean-only conditions and compatible equality, enforced in compiler and runtime. V0–V3 passed on Linux x86_64. |

| C16 | 704a0a8 | Int32 bitwise and shift operators, adjacent preprocessed-token shifts and checked counts. V0–V3 passed on Linux x86_64. |

| C17 | 262302f | Checked scalar conversions with strict locale-independent decimal parsing; opcode 49. V0–V3 passed on Linux x86_64. |

| C18 | 1deec3d | Exact or widening-only returns; explicit narrowing in the math library. V0–V3 passed on Linux x86_64. |

| C19 | this commit | Lazy ternary expressions in the current scope, numeric promotion and colon-ID/literal lexing; opcode50. V0–V3 passed on Linux x86_64. |

## Remaining

Each item gives its validation sets (V0–V3 are defined under "How to resume"). Details for item Cxx are in the section **Cxx** of [DESIGN.md](DESIGN.md).

1. **C20 switch** (V0, V1, V3). Lowered flat through a `SyntheticLoop` descriptor, no new opcode. No fall-through.
2. **C21 String functions** (V0–V3). Dense built-ins 0x0abd000b–13 with code-point semantics. Supports `s.length()` and `s[i]`.
3. **C22 Typed class pointers and closed object storage** (V0–V3).
   - `object_alloc` 0x0abd0014; the heap tags object ranges.
   - `mem_get`/`mem_free`/`make_free`/`mem_send_up` and JNI raw put/free reject object storage.
   - `oa` requires object storage.
   - Add the Known limitations docs.
4. **C23 `check_object`** (V0–V3). Opcode 51, applied to `reflect_invoke_function<R>` results.
5. **C24 Virtual methods** (V0–V3).
    - Keywords `virtual`, `override` and `final`; opcodes 52 `set_methods` and 53 `call_virtual`. Method tables live outside the slots.
    - **JNI snapshot v10**: update every snapshot-version site listed in COMPLETENESS §2.7.
    - Update the AGENTS wording.
6. **C25 Exact ABD measurement and statement-level spilling** (V0, V1, V2). `ExecShape` and `AbdLinearizer`. Differential runs at internal limits 96, 64 and 48.
7. **C26 Flat operator chains above 128 operands** (V0, V1, V2).
8. **C27 Flat else-if ladders** (V0, V1, V2). Choose nested or flat lowering from the remaining depth budget; this covers ladders of about 120–128 branches and ladders inside loops.
9. **C28 Validation record** (V0–V3). Add an entry to VALIDATION.md/.en.md with real results.

### Follow-ups found while implementing C01–C13

- `compile-json` reads its JSON input with `Files.readString`, with no regular-file or size check (`Main.readTree`). A FIFO hangs and `/dev/zero` runs out of memory.
- A stray `}` that closes a function early causes cascading errors, capped at 20.
- Typed globals are checked at compile time only, because exec v10 records no global types.
- Compiling 20,000 functions still needs about 512–640 MiB of heap.
- Only Linux x86_64 was exercised. The Windows and macOS path, launcher and float-parsing code is untested.
- Hint-assumption conflicts use `Map.containsValue`, which is a quadratic scan.
- Operator methods cannot be called through `Base::op`.

## How to resume

- **Validation sets.** Always run with `env -u JAVA_TOOL_OPTIONS`, because the tests require empty stderr. Run them one after another, never in parallel.
  - V0: `git diff --check`, plus a review of untracked files.
  - V1: `python3 tools/build_and_test.py --offline`
  - V2: `python3 tools/build_and_test.py --offline --sanitize`
  - V3: `python3 tools/export_distribution.py build/dist-check --offline [--force]`
- **Per commit:**
  1. Implement it from its DESIGN section, adjusted by this file.
  2. Run targeted tests, then its validation sets.
  3. Review the diff adversarially for correctness, missed sites and paired zh/en docs.
  4. Fix what the review finds.
  5. Commit. One commit per item, in the order above.
- **Opcode numbering is dense, in landing order:** 43–48 bitwise, 49 convert, 50 select, 51 check_object, 52 set_methods, 53 call_virtual.
- Every commit that adds AST keys must also update `AstShape`, the warnings, `SourceRules`, and the help text in `Main` and the launcher.
