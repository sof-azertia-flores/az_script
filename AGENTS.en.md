# AzScript Development Guide

[中文](AGENTS.md) | English

This guide applies to the entire repository. When a task explicitly changes an existing contract, update the implementation, tests, and documentation together. This guide does not override task requirements.

## Project structure

- `compiler/`: Java preprocessor, lexer and recursive-descent parser, readable AST, type checking, and exec/ABD generation. `compiler/stdlib/` contains independently compiled script libraries and shared declarations.
- `abdJava/`, `abdC/`: Java and C++ ABD models and codecs; their cross-language formats must agree.
- `interpreter/`: C++ execution engine, slot heap, object lifetimes, module loading, and hint linking.
- `abdJavaInvoker/`, `abdjni/`: Java host API and native JNI bridge, including callbacks and snapshots.
- `include/`, `cmake/`, `examples/`: public C++ interfaces, installation and consumer configuration, and host examples.
- `tests/`, `tools/`: cross-module regressions, unified builds, distribution export, and relocation checks. Modules also contain their own unit tests.

All modules use sources from this repository. Do not restore historical submodules or dependencies on old local binaries. Read the [README](README.en.md), then consult the [language guide](compiler/LANGUAGE.en.md), [execution format](docs/EXEC_FORMAT.en.md), and [hint linking guide](docs/HINT_LINKING.en.md) as appropriate.

## Development principles

Before editing, inspect `git status --short`, relevant diffs, and actual call paths. Protect uncommitted work. Keep changes within the task, follow nearby code style, and avoid unrelated refactoring or formatting.

Prefer compiler lowering for syntactic sugar. Runtime extensions should serve explicit execution semantics. For changes across layers, check the compiler, ABD codecs, interpreter, and C++/JNI host interfaces; do not update only a producer or consumer. Add regressions reproducing defects and cover both normal behavior and relevant error paths for new features.

Check behavior against the current implementation, tests, and contract documentation together. The [validation log](VALIDATION.en.md) covers historical stages; old format versions, interfaces, and test results are not automatically current facts.

## Core contracts

- `address` is an independent uint64 address with 0 as null; ordinary integers are int32. Address offsets check overflow and underflow without implicit integer conversions. Allocation counts, field offsets, variable slot numbers, and address values differ; function IDs retain all 32 bits.
- Classes are handled at compile time; the runtime uses object addresses and ordinary functions. Inheritance preserves the base-field prefix and appends derived fields. Automatic cleanup belongs to the created address, not a reassignable variable. Object returns and ordinary address returns have different ownership semantics. Normal exits, control-flow jumps, and errors must perform required cleanup while preserving existing errors.
- Loading and insertion require explicit `flush()` for linking and initialization. Module global offsets and assume bindings remain isolated. Failed linking must not leave partial relocation; snapshots must be fully validated before atomic replacement. State-change restrictions during execution and callbacks must not be bypassed.
- Fixed exec structures use raw `AbdStack`; dynamic values and extension metadata use suitable typed containers. Preserve strict field counts, widths, and types, plus limits on file size, AST/ABD nesting, heap capacity, and execution budgets. Current source and format documentation define the precise layouts and limits.

Distinguish the project version, source `metadata.version`, exec format version, and JNI snapshot version; do not mechanically synchronize them. Coordinate breaking format changes across readers, writers, version checks, fixtures, and documentation. Old executable files and snapshots are not currently supported; do not independently add compatibility branches.

## Build and validation

See the README for toolchains. The unified entry point currently requires JDK 17+, CMake 3.20+, C++20, and Python 3.9+. Run full validation from the repository root:

```sh
python3 tools/build_and_test.py --offline
```

If dependencies are missing from the cache, omit `--offline` to download pinned versions with digest verification. A partial Gradle build does not replace full cross-language validation.

For memory, destructor, or interpreter cleanup changes, also run:

```sh
python3 tools/build_and_test.py --offline --sanitize
```

The sanitizer build excludes JNI; JNI changes still require real-JVM validation in the ordinary build. Ordinary and sanitizer builds share `build/java/` and must run sequentially in one workspace to avoid overwriting or cleaning each other's output.

During development, run relevant targeted regressions first. Before code delivery, run full offline validation and applicable sanitizer checks. Compiler or format changes require byte-identical source → readable AST → `compile-json` → ABD results and Java/C++ ABD fixture roundtrips. Lifetime changes must also cover error cleanup. Record actual results and incomplete checks; do not reuse historical pass claims.

For documentation-only changes, check paths, commands, links, and `git diff --check`; a full rebuild is unnecessary. Check new unstaged files too, since ordinary `git diff` excludes them.

## Documentation and distributions

Update the language guide, examples, and host integration documentation for syntax or interface changes, and format documentation for binary contract changes. Standard-library changes require checking shared declarations, implementation, and API documentation. When recording validation, update VALIDATION with an explicit scope.

Keep build, cache, and temporary output under `build/`. Distributions default to `dist/` or a user-selected directory. Distribution tasks use the unified exporter and acceptance checks:

```sh
python3 tools/export_distribution.py dist/azscript --offline
```

Handle nonempty destinations according to exporter rules; `--force` only replaces a valid existing AzScript distribution. Export includes an independent Release build and relocation validation. Public-header or library-dependency changes require CMake installation and real C++/JNI consumer checks. A distribution represents only the platform and architecture actually built. Ordinary changes do not require re-exporting.

Preserve the distribution compiler's default ABD, AST JSON, and exec JSON outputs. Do not manually modify generated libraries, JARs, or executables instead of fixing source; regenerate through build and export tools.

## Git

Follow [.gitignore](.gitignore) and the [version-control guide](docs/VERSION_CONTROL.en.md). Track source, tests, documentation, Gradle wrappers, and dedicated binary fixtures; retain the intentionally tracked `abdC/tests/cpp-fixture.abd`. When a format change requires updating it, verify Java/C++ byte identity.

Do not commit build directories, caches, distributions, or temporary artifacts. Ignore rules do not exclude arbitrarily named `.abd` files: put custom compiler output under `build/` and inspect new files before committing.

Inspect and stage only the actual task scope; each commit should express one complete change. Review staged diffs and run `git diff --cached --check` before committing. Create branches, commit, merge, tag, and push only as authorized for the current task; documentation examples are not automatic publication instructions. Do not discard existing work or rewrite history without authorization.
