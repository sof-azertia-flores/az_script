# AzScript Revised Edition

[中文](README.md) | English

This edition rebuilds the compiler's lexer, recursive-descent parser, symbol resolution, and code-tree generation around the original code-tree/ABD instruction format, and fixes the data models, C++ interpreter, and JNI. The original workspace's six directories were left unchanged; these files form the independent revised edition.

Git manages all modules together, with main as the default branch. See [version control](docs/VERSION_CONTROL.en.md) for tracked files, branches, commits, release tags, and remote backups.

Generic classes, functions, and methods share compiled bodies, with type inference, a single extends bound, and parameterized inheritance. Compile-time checking and hidden operation contexts preserve defaults and object cleanup. `reflect_invoke_function<R>` calls ordinary entries by actual function ID; hint queries expose mount status and namespace. Expose generics to reflection or hosts through ordinary wrappers with fixed type arguments. See the [language guide](compiler/LANGUAGE.en.md) and [cross-module examples](docs/HINT_LINKING.en.md).

## Build and run

Requires JDK 17+, CMake 3.20+, a C++20 compiler, and Python 3.9+.

```sh
python3 tools/build_and_test.py
./azscript compile compiler/examples/parser-regressions.azs \
  -o build/demo.exec.abd --ast build/demo.ast.json --exec-json build/demo.exec.json
./build/native/interpreter/azscript-run build/demo.exec.abd
```

The regression script returns 146 and its destruction hook prints done. Optional --ast saves the readable tree and --exec-json the lowered instruction tree for inspection. `-g` adds debug records (function and file names, source positions) to the executable; they are off by default.

Builds reuse Gradle/Maven caches and download missing pinned dependencies from Maven Central with SHA-256 verification. All output, downloads, and temporary tests live under build/. With populated caches:

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

Sanitize enables AddressSanitizer/UndefinedBehaviorSanitizer for native data, interpreter, and compilation/execution tests. JNI uses the ordinary build and JVM -Xcheck:jni. Use --skip-tests for build-only or --skip-jni to omit JNI.

`CompilerScalingTest` checks that compile work grows linearly with program size. It compares deterministic operation counts of generated programs with ones eight times larger: many locals, statements, functions, globals, classes, fields, externs or parameters. After a build, `python3 tools/bench_compiler.py` times the compiler on such programs, up to 32,000 locals in one scope or 20,000 functions; it is an opt-in benchmark outside the validation run.

The three Java modules also form a root Gradle multiproject build (`gradle build`); the Python entry point is authoritative for full cross-language validation. Original module Gradle wrappers remain historical files; prefer the root project or unified entry point.

## Export a complete distribution

```sh
python3 tools/export_distribution.py /path/to/azscript --offline
cd /path/to/azscript
./compile.sh examples/hello.azs
./run.sh examples/hello.exec.abd
```

Compile.sh xxx.azs defaults to xxx.exec.abd, xxx.ast.json, and xxx.exec.json beside the source. Either -o out/demo.abd or -o out/demo.exec.abd defaults JSON paths to out/demo.ast.json and out/demo.exec.json, individually overridable with --ast/--exec-json. Windows uses compile.cmd/run.cmd.

The package includes the complete compiler/dependencies, jlink Java runtime, standalone interpreter defaulting to main (0x0fff0000), static/shared C++ libraries and public headers, relocatable `find_package(AzScript CONFIG REQUIRED)`, JNI JAR and platform .dylib/.so/.dll, syntax/usage guides, math library, and C++/Java examples. The interpreter links RuntimeShared so signed plugins share its function registry; running ABD needs no Java. Embedded C++ hosts may use Runtime or RuntimeShared, but only the latter shares plugin registrations.

Export builds Release independently, installs to a temporary directory, relocates it, and validates compilation, both JSONs, execution, C++ SDK, and JNI before publication. Manifest.json records platform, tools, Java version, checks, and per-file SHA-256. Failure preserves the prior distribution. Nonempty targets are rejected unless --force replaces an identified valid export. --system-java omits bundled Java and requires recipient Java 17+. Omit --offline for verified Gson downloads when uncached.

Artifacts target the current system, architecture, and compatible native ABI; export separately on each platform. Options such as --cmake-arg=-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 adjust native configuration, but JDK/toolchain compatibility still applies. See [usage](tools/distribution/USAGE.en.md) and [basic syntax](tools/distribution/QUICKSTART.en.md).

## How compilation works

1. Preprocess includes, macros, and conditions, recording each line's original file and line, so diagnostics read `file:line:column: error: ...` inside included files and at macro uses. Includes are searched beside the including file, then in `-I` directories, and must stay inside those roots.
2. Lex identifiers, numbers, strings, operators, and comments, preserving punctuation inside strings.
3. Parse precedence, associativity, nested calls, blocks, and control flow recursively.
4. Validate symbols, scopes, signatures, argument types/counts, duplicates, and returns, then generate JSON/ABD instructions.

See [the language guide](compiler/LANGUAGE.en.md). Original def(x), return(x), functional while/if blocks, and mem_get(pointer)=value remain supported, alongside conventional if/else/while, int/string switch, initialization, unary operators, comparisons, short-circuit logic, int32 bitwise/shift operators, conditional expressions `?:`, and variable compound assignment.

```c
#namespace 123
extern int host_add(int, int):0xccf0001;

int factorial(n) {
    if (n <= 1) { return 1; }
    return n * factorial(n - 1);
}

int main() {
    def(answer, factorial(5));
    print("answer=" + answer);
    return answer;
}
```

Print, getDepth, mem_free, alloc, make_free, mem_send_up, mem_get, and load_extern_library are compiler-provided built-ins requiring no include/declaration. The `str_*` [string functions](compiler/LANGUAGE.en.md#string-functions) also need no declaration and provide code-point methods/indexing. `load_extern_library("xxx")` loads the platform's signed .so/.dylib/.dll; libraries register functions through the existing C++ API. Standalone runners read keys from AZSCRIPT_TRUSTED_KEY or trusted_key.pem; embedded hosts provide keys through C++/Java APIs. See [external libraries](docs/EXTERN_LIBRARY.en.md). Includes resolve relative to the including source file first, then in each `-I` directory and the wrappers' standard library directory; absolute paths and paths leaving those roots need `--unrestricted-includes` (see [preprocessing](compiler/LANGUAGE.en.md#strings-comments-and-preprocessing)).

Address is independent unsigned 64-bit slot addressing with null as zero. Example: `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`. Int32 offsets support checked addition/subtraction. Object references use this runtime type while static class checks/inheritance remain. Addresses differ from slot IDs, counts, and function IDs, which retain 32-bit representations.

Classes support fields, constructors, methods, and destructors. `Point p(3);` is a literal value: all slots copy on assignment/arguments/returns, each copy destructs, and addresses expire with variables. `Point * p(3);` is an automatic pointer object (zero arguments still require parentheses). `Point * p = new Point(3);` is manual and needs delete. `Point * q;` is null. Point (*) parameters accept pointers and borrow literal addresses without copying. Only method this and Point (*) borrows expose literal addresses, both as Point *. Each field takes one slot; literal fields embed complete objects. Methods lower to ordinary functions with an implicit address argument. See [classes](compiler/LANGUAGE.en.md#classes-literal-objects-and-pointers); [class regressions](compiler/examples/classes-regressions.azs) return 0.

For loops lower to blocks/while. Continue cleans iteration locals and, in for, runs the step first. Public single inheritance keeps base slots first and appends derived slots; methods receive the same address. Binding is static, and `Base::method(args)` calls a shadowed base method on `this`; construction is base-to-derived, destruction derived-to-base; failed derived construction cleans completed bases. See [loops](compiler/examples/loops-regressions.azs) and [inheritance](compiler/examples/inheritance-regressions.azs).

Readable AST can also be compiled directly:

```sh
./azscript compile-json build/demo.ast.json -o build/from-json.exec.abd
```

## Script math library

[`compiler/stdlib/math.azs`](compiler/stdlib/math.azs) is an independent AZSCRIPT_MATH hint library providing constants, absolute/range operations, integer division/modulo, gcd/lcm, factorial/Fibonacci, integer powers, roots, rounding, angle conversion, trigonometry, exponentials/logarithms, and approximate comparison. No JNI or host registration is needed; clients include typed declarations only:

```c
#include "../stdlib/math.include.azs"

int main() {
    return math_round(math_hypot(3.0, 4.0)); // 5
}
```

Elsewhere, a client writes `#include "math.include.azs"`, and likewise for the other standard library headers: the `./azscript` wrapper and the distribution's `compile.sh` / `compile.cmd` search the standard library directory after every `-I` directory. Without a wrapper, pass `-I compiler/stdlib`; the `../stdlib/` path from `examples/` needs it too, since it leaves the example's directory.

Compile library and client separately, then assemble:

```sh
./azscript compile compiler/stdlib/math.azs -o build/math.exec.abd
./azscript compile compiler/examples/math-regressions.azs -o build/math-regressions.exec.abd
./build/native/interpreter/azscript-run build/math-regressions.exec.abd --insert build/math.exec.abd
```

Distributions include stdlib/math.exec.abd and both JSONs. Floating APIs require double, integer APIs int; multiply integer expressions by 1.0 for floating calls. Hosts must flush after assembly and query namespace_for_hint("AZSCRIPT_MATH") / namespaceForHint("AZSCRIPT_MATH") for the actual namespace.

See [math API/domains/accuracy](compiler/stdlib/MATH.en.md). [Math regressions](compiler/examples/math-regressions.azs) return 0. Transcendentals are script-level iterative approximations; use extern host implementations when full-range system-library accuracy is required.

## Script containers

[`compiler/stdlib/containers.azs`](compiler/stdlib/containers.azs) provides a generic doubly linked `List<T>`, plus `Stack<T>` and `Queue<T>` built on it. `Vector<T>` is a contiguous array. `Set<T>` and `Map<K, V>` are flat tables ordered by `value_compare`; class-value keys need an ordinary comparator first. Clients include the shared declarations only. List sort and find remain ordinary functions of concrete types, because an unbounded type parameter cannot be compared directly:

```c
#include "../stdlib/containers.include.azs"

int main() {
    List<int> * xs = new List<int>();
    xs.push_back(3);
    xs.push_back(1);
    list_sort_int(xs);
    int first = xs.front();
    delete xs;
    return first;
}
```

```sh
./azscript compile compiler/stdlib/containers.azs -o build/containers.exec.abd
./azscript compile compiler/examples/containers-regressions.azs -o build/containers-regressions.exec.abd
./build/native/interpreter/azscript-run build/containers-regressions.exec.abd --insert build/containers.exec.abd
```

The regression returns `0`. List elements live in node fields, so indexing walks the list, and class values need a zero-argument constructor. `Vector` and `Set` can store an existing class value. Subscripting returns a copy. As with the math header, a client outside `examples/` writes `#include "containers.include.azs"`, and a direct compiler call needs `-I compiler/stdlib`. See [`compiler/stdlib/CONTAINERS.en.md`](compiler/stdlib/CONTAINERS.en.md).

## Principal fixes

| Layer | Fixes |
| --- | --- |
| Compiler | Nested syntax, quotes/escapes/comments, precedence/associativity, forward calls, recursion, lexical scopes, symbol/argument diagnostics |
| ABD model | Type fall-through, Map updates, floating conversion, deep copying, invalid frees, length/index checks, preservation of empty/leading-zero Java byte arrays |
| C++ runtime | Return inside loops, caller-local leakage, argument evaluation, mixed arithmetic, mutated constants, exception cleanup |
| Heap | Independent uint64 addresses with reserved zero, size/live-range/free checks, clearing reused storage, automatic scope release, explicit ownership promotion |
| JNI | UTF-8, empty/NUL/supplementary text, JavaVM/global references, exception boundaries, argument/result/callback temporary cleanup |
| Host API | Returning callbacks, same-thread reentry, serialized concurrency, close/reload, validated atomic snapshots and temporary-file writes |
| Build | Missing submodules, duplicated inconsistent C++ declarations, hardcoded JDK paths, private remote ABD dependencies |

## C++ embedding

Add the source directory to host CMake and link the static target:

```cmake
set(AZSCRIPT_BUILD_JNI OFF CACHE BOOL "" FORCE)
add_subdirectory(path/to/azscript)
target_link_libraries(your_app PRIVATE abdInvokero)
```

```cpp
#include <azscript/runtime.hpp>

auto script = azertian::load_script(bytes.data(), bytes.size());
script->max_steps = 200000;
script->max_call_depth = 128;
script->flush();
auto result = script->invoke(0x0fff0000); // main
script->destroy();
```

See [examples/embedded.cpp](examples/embedded.cpp), target azscript-embed. Always bound external input by length. Load only mounts; configure budgets, register hosts, insert libraries, then flush to link/initialize. Onload host callbacks may call linked functions.

Insert_script appends modules and requires another flush on success. Exec globals are -1, -2, etc.; parameters/locals consecutive nonnegative slots; global declarations store counts only. Each function's global_offset makes negative v resolve as `global_offset + (-v - 1)`, isolating independently compiled globals even with identical names/IDs. Call frames remain separate and block cleanup unchanged. Fixed function IDs must not collide (see [hint linking](docs/HINT_LINKING.en.md) when separately compiled ordinary modules collide in the default namespace); hint libraries receive runtime namespaces. See [insert offsets](compiler/LANGUAGE.en.md#insert-and-module-global-offsets).

Execution throws standard C++ exceptions. Destroy is idempotent, runs destruction hooks, and releases globals. C++ object destruction releases owned storage but does not automatically run user hooks.

In a catch handler, call `azertian::describe_error(error)` before `destroy()` or another runtime entry. It preserves the exception's type and `what()`, returning that message plus script frames without the `AzScript error: ` CLI prefix or a trailing newline. `last_error_trace()` returns an `error_trace` with `frames` and `omitted_frames`; `format_error_trace(trace)` formats its frame lines, and `clear_error_trace()` clears the trace. Collection is thread-local and starts afresh on each entry, including callback reentry; cleanup retains only the first error's trace. Compile with `-g` for names and 1-based file/line/UTF-16-column positions; otherwise frames use hex function IDs. See [runtime errors](compiler/LANGUAGE.en.md#runtime-errors-and-debug-info) for native frames and the 64-frame limit. The public C++ ABI has changed; rebuild hosts and plugins against matching headers and libraries.

## Java embedding

Use build/java/abdJavaInvoker.jar with libabdJ and libabdInvoker (.dylib on macOS, .so on Linux). See [Java integration](abdJavaInvoker/README.en.md) for library paths.

```java
System.setProperty("azertia.native.library", "/absolute/path/libabdJ.dylib");
AbdInvoker.loadScript(new File("demo.exec.abd"));
try {
    AbdInvoker.flush();
    Object result = AbdInvoker.invoke(0x0fff0000);
} finally {
    AbdInvoker.close();
}
```

Non-library main is 0x0fff0000; ordinary definitions may fix low IDs with :0003, or omit the number and inherit the full ID of an extern in the module's own namespace declared in a shared header. Libraries use namespace_hint; clients use assume_hint and extern. Load all modules, explicitly flush to link/initialize, and flush again after insertion. See [multi-file libraries](docs/HINT_LINKING.en.md) for examples/out-of-class implementations and the [Java guide](abdJavaInvoker/README.en.md) for registration, returning callbacks, and snapshots.

## Defined semantics and limits

- Conditions, including `?:`, and `!`/`&&`/`||` require booleans, including for dynamic values at runtime. Equality permits mixed numeric types, same-type booleans/strings, addresses/null, and compatible class pointers; incompatible pairs fail rather than comparing unequal. See [equality rules](compiler/LANGUAGE.en.md#expressions-and-control-flow).
- Integers are signed 32-bit; division truncates toward zero and arithmetic overflow/zero division fail. Numeric promotion is double > float > int; booleans are not implicit numbers.
- Int-only `& | ^ ~ << >> >>>` operate on 32-bit patterns; left shift wraps, `>>` sign-extends, and `>>>` zero-fills. Counts must be 0…31, and simple variables support the compound forms. Parenthesize bit tests as `(flags & mask) != 0`. See [bitwise operators](compiler/LANGUAGE.en.md#bitwise-and-shift-operators).
- Explicit `int/float/double/string/address(x)` conversions follow a checked matrix: numeric conversions, strict decimal string parsing, numeric/address-to-string formatting, and address identity. Class pointers and pointer-bound T explicitly erase with `address(p)` without changing ownership; other class conversions, int/address pairs, boolean conversions, and C-style casts are unsupported. int truncates before its range check; floating conversion permits underflow but rejects overflow. See [explicit conversions](compiler/LANGUAGE.en.md#explicit-conversions).
- `condition ? a : b` evaluates one arm in the enclosing scope, with a common result type and numeric promotion. It is right-associative between assignment and `||`; both arms must type-check. See [conditional expressions](compiler/LANGUAGE.en.md#conditional-expressions).
- `switch` evaluates an int/string value once, matches literal labels, and has no fall-through. Stacked labels share a section; default may appear anywhere. Each section has its own scope. See [switch](compiler/LANGUAGE.en.md#switch).
- Strings are immutable UTF-8 values. `s.length()`, `s[i]`, half-open `substring(begin, end)`, and searches use Unicode code points; `byte_length()` counts bytes. NUL is valid, invalid UTF-8 is rejected, and bounds errors map to Java IndexOutOfBoundsException.
- Decimals default to double; 1.5f, 5f, and 2e3f are float.
- Fully typed extern declarations require explicit types; old #extern is removed. Actual arguments/results match exactly without int/float/double conversions. Namespace accepts 1–4 hex digits and rejects the reserved 0000 and 0abd; libraries use namespace_hint/assume_hint.
- Parameters pass by value, evaluated left to right. Functions access only own locals and globals. Each block/loop iteration has its own scope; for initializer scope ends with the loop.
- Return crosses current-function blocks; break exits the innermost loop/switch, while continue targets the nearest loop and performs cleanup. Declared numeric returns require an exact type or widening int→float/double or float→double; int→float may round. Narrowing needs an explicit checked `int(x)` or `float(x)` conversion, and dynamic return types are checked at runtime.
- ABD nesting is limited to 128 levels, checking logical expressions and physical wire containers. Bodies start at level 4; argument/statement lists and constant wrappers add levels (roughly 120 chained operators or 60 nested blocks). Parser/JSON guards are 320 levels; cyclic trees fail. Defaults are 1,000,000 steps and depth 256 per top-level call, 64 MiB script files, and 1,048,576 heap slots including reserved zero. Host callback elapsed time is not budgeted.
- Control-flow/declaration/class keywords, including switch/case/default, public/private/protected/virtual, any, types, and true/false are reserved identifiers, and identifiers cannot contain control or format characters such as U+200B or U+202E; source and compile-json share these naming rules. Every normal nonvoid exit must return; constant-true while/for without a belonging break is nonterminating.
- Numeric strings use shortest roundtrip forms: 0.30000000000000004, 123456789, 1e+20, and -0 for the documented examples.
- Address and int32 do not implicitly convert. Alloc(int) returns address; memory APIs take it. Address±int and int+address are checked across uint64; address equality/order is unsigned. Class pointers do not support raw arithmetic. Addresses remain logical indices with the same heap limit; bounds/freed access fail, but reused heap addresses cannot identify stale references.
- Raw allocation storage is separate from object storage: script raw memory APIs and JNI raw writes/free reject object starts, interiors, and literal/view addresses. Class pointers stay explicitly typed; `address(p)` erases type but grants no raw field access. Use typed members and delete. See [object storage and known limitations](compiler/LANGUAGE.en.md#object-storage-and-known-limitations) for trusted host pointers, stale aliases, double delete, and automatic-object escapes.
- Automatically owned raw allocations may be freed only from their owning scope chain (lexical parents/callers). Reentrant functions in other active scopes cannot free them. Java memFree permits ownerless or globally promoted blocks. Make_free removes this restriction.
- Namespaces 0, 0xfff, and 0xabd are reserved. 0 holds lifecycle hooks and hint-library placeholders and 0xabd runtime built-ins, so `#namespace` rejects both, and compile-json rejects them as an ordinary module's namespace (hint libraries keep their 0000 placeholders); 0xfff is the default namespace of ordinary programs and may be written explicitly. Hosts cannot register in these three or in active script namespaces. Hint mounts allocate available positions. Function IDs retain all 32 bits.
- Heap/external registry remain process-shared. Java maintains one active script, serializes calls, and allows same-thread callback reentry. Callbacks must not wait for another runtime-calling thread. Multiple C++ scripts still share resources and are not independent security sandboxes.
- Collection literals, closures, and a complete static type system are not implemented. See the language guide for annotation semantics and the diagnostic format.
- ABD is a recoverable binary instruction tree, not encryption or guaranteed decompilation protection. Numeric variable slots hide source names only in exec; AST retains them. By default an exec carries no function, class or file names; `-g` adds debug records with those names and source positions, so leave it out of releases that should not carry them. Sources, readable ASTs and exec inspection JSON may be omitted from releases, but secrets must not rely solely on this format.

## Data compatibility

Address tag 0xce200b has exactly eight uint64 little-endian bytes: C++ azertian::address, Java ABD AcsAddress, JNI azertia.Address, all distinct from integers. Existing data scalars/containers remain readable. Raw-byte tag 0xce200a prevents Java BigInteger conversion from losing leading zeros; older C++ readers need upgrading. Already lost zeros and historically indistinguishable reflective null/empty values cannot be recovered. See [C++ ABD](abdC/README.en.md) and [Java ABD](abdJava/README.en.md).

New operations, else, initialization, parameter types, and extern signatures require matching interpreters; recompile source. A runtime cannot recover intended expressions from trees misparsed by historical compilers.

Class pointers/this/raw pointers use address type 7; literal values object type 8; int remains int32. Existing oa/ob/od/ro retain lifetime behavior; new_block/block_address/mv/drop implement literals. Exec v10 uses numeric variables/opcodes and raw fixed stacks, retaining typed containers only for dynamic constants/metadata, and carries optional `-g` debug records. See [Exec v10](docs/EXEC_FORMAT.en.md). Only v10 is accepted; recompile older source/AST. Migrate reference-style C x(...) to C * x(...), C y = new C() to C * y = new C(), and reference parameters/fields/returns to C *. JNI accepts only v9 snapshots with address scalars, allocations, object records, cleanup order, globals, module identity, and literal fields.

The compiler also provides owning `buffer<T>` with contiguous direct element slots, deep copies, automatic cleanup, and checked reserve/get/set/push/resize operations. `value_compare<T>` orders basic values; class `+ - * / [] []= ()` declarations lower to ordinary method calls. See the [language guide](compiler/LANGUAGE.en.md). `List`, `Stack`, and `Queue` stay linked lists. `Vector`, `Set`, and `Map` use these primitives for contiguous storage and the default order.

## Validation

The unified suite covers compiler/archive regressions, Java ABD, C++ data/runtime, bidirectional byte comparisons, source→JSON→ABD→C++ execution, and the same compilation through JNI in a JVM. JNI -Xcheck:jni covers four-thread calls, reentry/errors, scalars, and snapshot restore.

Original-sha256.json records checksums of the original six directories to verify they were not modified during repair. See [validation records](VALIDATION.en.md) for actual results.
