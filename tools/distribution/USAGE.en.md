# Using the AzScript Distribution

[中文](USAGE.md) | English

Move this directory as a unit. The compiler produces ABD; the standalone interpreter defaults to main (0x0fff0000). C++ and Java hosts use the same ABD. Relative input/output paths resolve against the terminal working directory; includes resolve against their containing source file, then each `-I` directory, then the package's stdlib/ (see [compile and run](#compile-and-run)).

Export from source with `python3 tools/export_distribution.py /path/to/azscript --offline`, replacing the destination. Offline mode requires cached dependencies. A Java runtime is bundled by default; `--system-java` reduces package size and requires installed Java 17+ on the recipient.

Native libraries target the build system and CPU, not multiple platforms: .dylib on macOS, .so on Linux, .dll on Windows. Re-export on each target system/architecture. C++ compiler ABI, standard library, and runtime must match the host. Preserve layout and do not mix headers, JARs, or libraries from different builds.

## Compile and run

`compile.sh` / `compile.cmd` and `compile-json` accept `-O0` (default), `-O1` and `-O2`; the last level wins. O2 extends scalar propagation, common expression and dead code elimination, inlining and safe loop motion. It does not enable JIT. See the [language guide](LANGUAGE.en.md#compilation-and-embedding) for switches and semantic boundaries. Use the same optimization options when recompiling the AST.

From the distribution root:

```sh
./compile.sh examples/hello.azs
./run.sh examples/hello.exec.abd
./compile.sh examples/classes.azs -o classes.exec.abd
./run.sh classes.exec.abd
```

Windows command prompt:

```bat
compile.cmd examples\hello.azs
run.cmd examples\hello.exec.abd
```

Three outputs default beside the source: hello.exec.abd executable, hello.ast.json readable syntax tree, and hello.exec.json lowered instructions. -o chooses ABD output; --ast and --exec-json override JSON paths. Hello prints `Hello, AzScript!` and main result 42. The interpreter prints nonvoid results to stdout; successful process exit is 0, not the script's integer return value. Failure returns nonzero and diagnostics go to stderr.

Compile errors go to stderr as `file:line:column: error: message`, positioned in the original file, including included headers and macro uses; following `note:` lines give the include chain. Independent errors are reported together, at most 20 by default; `--max-errors N` changes the limit (0 for none). A failed compilation ends with a summary such as `Error: compilation failed: 2 errors`, exits with status 1, and writes no outputs. Status 2 means an internal compiler error, a bug of the compiler itself; `--debug-compiler` adds its Java stack trace to the report. Options may precede or follow the source path; `--` ends the options, for a source path that begins with `-`. See [the language guide](LANGUAGE.en.md#diagnostics) for the format.

Warnings, such as an assignment used as a condition or unreachable code, read `file:line:column: warning: message [-W<name>]`; they keep exit status 0 and the outputs. `-Wall` enables all warnings, `-W<name>` enables one (for example `-Wunused-variable`, which is off by default), `-Wno-<name>` disables one, `-w` suppresses them, and `-Werror` turns them into errors that fail the compilation. See [warnings](LANGUAGE.en.md#warnings) for the list.

Includes are searched in the directory of the including file, then in each `-I dir` / `--include-dir dir` in order, then in the package's stdlib/, so a client anywhere can write `#include "math.include.azs"`. An include must stay inside the source file's directory or one of these directories: absolute paths, paths that lead out of them such as `../../private.azs`, and symbolic links pointing outside are rejected without reading the file. `--unrestricted-includes` lifts this restriction. A missing include lists every searched path. Include options apply to source compilation only, not to `compile-json`.

With `-o output/demo.abd` or `-o output/demo.exec.abd`, JSON defaults are output/demo.ast.json and output/demo.exec.json, individually overridden by explicit flags.

Compiler launchers prefer bundled runtime/. Without it, install Java 17+ and use JAVA_HOME or PATH. Compilation needs no Gradle, source checkout, or downloads. The native interpreter needs no Java.

```sh
./compile.sh examples/hello.azs -o hello.exec.abd \
    --ast hello.ast.json --exec-json hello.exec.json
```

AST names bind with extern priority; independent definition IDs live in body namespaces and metadata.position/name. Name-based host calls need matching AST output; hint definitions also require the actual mounted namespace. Fixed-main execution needs neither JSON file deployed. ABD is a binary instruction tree, not encryption.

Exec v10 uses numeric opcodes and raw ordered ABD stacks. Exec JSON retains field names for inspection with numeric c; it is not the binary Map layout. Only v10 is accepted; recompile older ABD from source/readable AST. See [binary format](EXEC_FORMAT.en.md).

`-g` adds debug records to the executable: source file names, function names and source positions. They are off by default and never change instructions or behavior, but they put function, class and file names into the ABD, so leave `-g` out of builds that should not carry them. A JNI snapshot can be restored only into a build with the same module bytes, so a `-g` build and a build without `-g` do not share snapshots.

Launchers also accept full CLI commands: `./compile.sh compile-json hello.ast.json -o restored.abd` rebuilds ABD and defaults restored.exec.json without overwriting the input AST. Use `./compile.sh --help`; pack/unpack archive/extract files.

Call bin/azscript-run (Windows bin\azscript-run.exe) directly or through run.sh/run.cmd. An optional second argument is a hex/decimal function ID, for parameterless functions only. Repeated --insert path assembles libraries, then automatically flushes, runs dependency-ordered onload, executes entry, and closes. A rejected library is reported as `AzScript error: Cannot insert <path>: ...`; function IDs in errors are printed in hex, such as `0x0fff0001`. See [hint linking](HINT_LINKING.en.md) for separately compiled ordinary modules that collide in the default namespace.

The runner uses the classic tree interpreter by default. `--backend vm` (short form `--vm`) selects the new register VM, and `--backend tree` (short form `--tree`) selects the tree interpreter explicitly; `--backend=vm` is also accepted. The option may appear anywhere among the arguments, for example:

```sh
./run.sh examples/hello.exec.abd --vm
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd --backend vm
```

The VM uses a 128 MiB default workspace. A program it cannot run is rejected while it is prepared, with no fallback to the tree interpreter. Unknown values or conflicting options return a nonzero exit status.

`--jit` (that is, `--jit=on`) enables the JIT tier on the VM: once a function's entries and loop back-edges reach a threshold (1000 by default), scalar functions run as native code, with results, errors, traces and remaining steps identical to the VM; functions that use objects, generics and similar features stay interpreted by the VM. `--jit` implies `--vm` and conflicts with `--tree`; `--jit=on:N` sets the threshold to N and `--jit=off` turns the tier off. The native code is compiled by `lib/libazscript-jit.so` (`.dylib` on macOS), which only packages exported with `--jit` contain; see the `jit` field of `manifest.json`. Without the module, `--jit` runs on the VM as usual, while `--jit=require` (or `require:N`) reports `The LLVM JIT backend is unavailable` and returns a nonzero exit status. Windows packages have no JIT.

```sh
./run.sh long-loop.exec.abd --jit --max-steps unlimited
./run.sh long-loop.exec.abd --jit=require
```

By default each top-level entry (onload, the entry function, destructors) may execute at most 1,000,000 steps; exceeding it reports `Script execution step limit exceeded`. `--max-steps N` (or `--max-steps=N`) changes the limit to the positive decimal integer N, and `--max-steps unlimited` removes the practical limit. Both backends honor it:

```sh
./run.sh long-loop.exec.abd --max-steps 100000000
./run.sh long-loop.exec.abd --vm --max-steps unlimited
```

Zero, negative, non-decimal, or out-of-range 64-bit values are rejected. Without a limit, an infinite loop never stops on its own.

### Runtime errors

Use `./compile.sh -g program.azs` for source locations. Execution errors exit with status 1 and print to stderr:

```text
AzScript error: Division by zero
  at divide (program.azs:2:14)
  at main (program.azs:5:12)
```

Without `-g`, frames read `  at function 0x0fff0002`. Native callbacks read `  at native function 0x12340001`; built-ins add no native frame. Frames list the failing function first, retain at most 64 entries, and end with `  ... N more frames` when truncated. Lines and UTF-16 columns start at 1. Cleanup preserves the first error and its trace. See [runtime errors and debug info](LANGUAGE.en.md#runtime-errors-and-debug-info).

## Math library

Clients include declarations from stdlib/math.include.azs, by name as `#include "math.include.azs"` from anywhere, since the package's stdlib/ is searched by default, or by relative path, e.g. `#include "../stdlib/math.include.azs"` under examples/. Implementation stdlib/math.azs compiles independently with AZSCRIPT_MATH; do not include it in clients. Precompiled ABD and matching AST/exec JSON are bundled. Recompilation updates all three:

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

The regression returns 0; running the client alone fails linking. Floating APIs require double, integer APIs int, and math_pow `(double, int)`. Use math_sqrt(9.0), not integer 9. See [math documentation](../stdlib/MATH.en.md) for functions, domains, and accuracy.

C++ insert_script or Java insertScript(new File("stdlib/math.exec.abd")) assembles the library, followed by flush. namespace_for_hint("AZSCRIPT_MATH") / namespaceForHint("AZSCRIPT_MATH") returns the actual namespace. Public low numbers are fixed in the header (math_pi is 0002); host IDs are `(namespace << 16) | 0x0002`, never hardcoded assumed 4d41.

## Container library

Clients include stdlib/containers.include.azs, by name as `#include "containers.include.azs"` or, under examples/, as `#include "../stdlib/containers.include.azs"`. Implementation stdlib/containers.azs compiles independently as AZSCRIPT_CONTAINERS. The package ships stdlib/containers.exec.abd and both JSON views:

```sh
./compile.sh stdlib/containers.azs
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

The regression returns 0. `List<T>` is a doubly linked list; `Stack<T>` and `Queue<T>` each own one list and keep O(1) ends. `Vector<T>` is a contiguous array. `Set<T>` and `Map<K, V>` are sorted flat tables; class-value keys need a comparator first. Create them with new and destroy them with delete. Assumed namespace c071 is not the runtime address. See [the container guide](../stdlib/CONTAINERS.en.md) for operations, default construction, and IDs.

## C++ embedding

Headers are in include/, static/shared libraries in lib/. Windows DLLs are in bin/, import libraries in lib/. The exported CMake package supplies includes, C++20, and transitive dependencies:

```cmake
find_package(AzScript CONFIG REQUIRED)
target_link_libraries(your_app PRIVATE AzScript::Runtime)
```

Runtime links statically; RuntimeShared selects the shared interpreter. Static linkage still depends on the system C++ runtime. Shared consumers must deploy matching native libraries where the OS can find them; Windows hosts can add bin/ to PATH.

Include `<azscript/runtime.hpp>`, load ABD through a bounded entry, then invoke main:

```cpp
auto script = azertian::load_script(bytes.data(), bytes.size());
// Other modules can be inserted here.
script->flush();
auto result = script->invoke(0x0fff0000);
script->destroy();
```

C++ azertian::address stores uint64 in .value. Variable(address) differs from variable(int); explicitly construct address arguments, e.g. azertian::address{bits}. Class pointers also use address; fields still occupy one slot. Literal values use OBJECT_VALUE (8); returned literals are host-owned without later user destructors. Public azertian::blocks create/resize/length/address_of APIs directly manage slot blocks.

Load only mounts code. Flush links and initializes pending modules before invoke. Destroy runs destruction hooks and cleanup and is repeatable. Explicitly close on normal and exceptional exits. Do not use the old unbounded pointer API for external files. See examples/cpp/main.cpp for bounded reading, errors, and budgets.

Catch the original C++ exception type and call `azertian::describe_error(error)` before `destroy()` or another runtime entry. It returns `what()` plus matching frames, without the CLI prefix or a trailing newline. `last_error_trace()` returns an `error_trace` containing `frames` and `omitted_frames`; `format_error_trace(trace)` formats the frames and `clear_error_trace()` clears them. Collection is thread-local and restarts at each entry, including callback reentry. Trace APIs and virtual-method storage change the public C++ ABI: rebuild hosts and plugins against this package's matching headers and libraries.

Insert_script records each module's global offset per function, so two -1 globals refer to separate slots. Parameter/local frames and block destructors remain independent. Fixed namespaces must avoid ID collisions; namespace_hint libraries receive runtime namespaces. Successful insertion requires another flush. Query namespace_for_hint("NAME"). See [global offsets](LANGUAGE.en.md#insert-and-module-global-offsets).

Build/run the C++ example with CMake 3.20+ and a compatible C++20 toolchain:

```sh
cmake -S examples/cpp -B cpp-build -DCMAKE_PREFIX_PATH="$PWD"
cmake --build cpp-build --config Release
./cpp-build/azscript-host examples/hello.exec.abd
```

Windows command prompt:

```bat
cmake -S examples\cpp -B cpp-build -DCMAKE_PREFIX_PATH="%CD%"
cmake --build cpp-build --config Release
cpp-build\Release\azscript-host.exe examples\hello.exec.abd
```

The final path assumes a multiconfiguration generator such as Visual Studio; single-configuration generators use cpp-build\azscript-host.exe. This example defaults to static linkage and needs no interpreter DLL copy.

## Java / JNI embedding

java/abdJavaInvoker.jar has no third-party JAR runtime dependencies. Deploy libabdJ.dylib / libabdJ.so / abdJ.dll with the matching interpreter library. Unix libraries stay in lib/; Windows DLLs in bin/. JVM and JNI CPU architectures must match.

examples/java/RunScript.java locates JNI from the distribution root and uses loadScript, flush, invoke(0x0fff0000), and close. The bundled runtime supports Java source-file execution:

```sh
./runtime/bin/java --class-path java/abdJavaInvoker.jar \
    examples/java/RunScript.java "$PWD" examples/hello.exec.abd
```

Windows:

```bat
set "PATH=%CD%\bin;%PATH%"
runtime\bin\java.exe --class-path java\abdJavaInvoker.jar examples\java\RunScript.java "%CD%" examples\hello.exec.abd
```

For --system-java exports, replace runtime/bin/java with installed JDK 17+ java. Ordinary compile/run integration is also supported on Unix:

```sh
javac -cp java/abdJavaInvoker.jar -d java-build examples/java/RunScript.java
java -cp "java-build:java/abdJavaInvoker.jar" RunScript "$PWD" examples/hello.exec.abd
```

Windows uses semicolon classpath separators:

```bat
set "PATH=%CD%\bin;%PATH%"
javac -cp java\abdJavaInvoker.jar -d java-build examples\java\RunScript.java
java -cp "java-build;java\abdJavaInvoker.jar" RunScript "%CD%" examples\hello.exec.abd
```

Before the first JNI call, applications may set `System.setProperty("azertia.native.library", absoluteLibraryPath)` or use -Djava.library.path to find abdJ. The OS must still locate its interpreter dependency.

Native failures retain their Java exception mapping; their messages can contain multiple UTF-8 trace lines. Java callback throwables keep their original object identity and message. Each JNI entry, including reentry, starts fresh trace collection.

Invoke accepts Integer, Float, Double, Boolean, String, azertia.Address, and null without truncating Long to int. Register matching IDs and exact script signatures:

```java
AbdInvoker.registerJfunction(0x12340001,
    values -> (Integer) values[0] + (Integer) values[1]);
```

The script declares `extern int host_add(int, int):0x12340001;`. Built-ins need no registration. The bridge maintains one active script per process, serializes calls, and permits same-thread reentry. Do not wait in a callback for another thread calling this runtime. Class pointers/address cross as azertia.Address; literals cannot cross JNI and returned literals fail after destruction. Return pointers instead. Address.of(long bits)/bits() retain all unsigned 64 bits. Address.NULL is address zero, distinct from void's Java null; Integer/Long cannot replace addresses implicitly. An address is a runtime reference, not an independently owned Java object. Close releases script and callback registrations; place it in finally.

Class-pointer arguments and extern results remain trusted address values; member access checks object storage and bounds, not nominal class identity. Script raw memory APIs and JNI raw writes/free cannot access object storage. Use typed script wrappers for fields and `delete`; `address(p)` does not bypass these restrictions or transfer ownership. Stale aliases after heap reuse and escaped automatic objects remain known limitations.

## Multi-file assembly

```sh
./compile.sh examples/multifile/point.azs
./compile.sh examples/multifile/main.azs
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd
```

Java calls loadScript(mainFile), insertScript for each library, flush, then invoke. namespaceForHint("POINT_LIB") supplies the actual namespace; public IDs are `(namespace << 16) | localId`. Identical assumed namespaces in different modules may identify different libraries. Missing libraries/signature errors fail flush atomically and permit retry after dependencies are added. Onload failure requires close/recreation. Insert, flush, and snapshot replacement are forbidden during execution/callbacks.

JNI v10 snapshots require initialized idle scripts, including literals in heap fields, and identical ordered module bytes, actual namespaces, and global layouts. Restore does not rerun onload. Old executables/snapshots are unsupported. See [hint linking](HINT_LINKING.en.md) for syntax, out-of-class implementations, and lifetimes. Snapshot v10 preserves virtual bindings and their generic contexts through required `methods:[{id,contexts}]` arrays on every heap-object and literal/view record; buffers use `[]`.

## Signed external libraries

`load_extern_library("xxx")` loads xxx.dylib on macOS, xxx.dll on Windows, or xxx.so elsewhere, requiring adjacent xxx.signature. The exported azscript_load_extern calls the same registerExecutor as embedded C++ hosts. Hosts using this feature must link RuntimeShared; static Runtime and plugins do not share a registry.

Root compile_extern_lib.sh and sign_extern_lib.sh (or .cmd) wrap platform compilation and bin/azscript-sign-extern. The plugin header is include/azscript/extern_library.hpp. Standalone interpreters read public keys from AZSCRIPT_TRUSTED_KEY and trusted_key.pem in current/executable/shared-library directories. Embedded hosts explicitly call add_trusted_public_key_pem or AbdInvoker.addTrustedPublicKey and do not scan those locations. See [external libraries](EXTERN_LIBRARY.en.md).

## Package contents and further reading

- compile.sh / compile.cmd: source compiler using compiler/lib/ JARs.
- compile_extern_lib.sh / .cmd: native plugin compilation.
- sign_extern_lib.sh / .cmd and bin/azscript-sign-extern: P-256 key generation/signing.
- run.sh / run.cmd and bin/azscript-run: standalone ABD execution.
- include/, lib/, lib/cmake/AzScript/: C++ API, native libraries, CMake package.
- java/abdJavaInvoker.jar: high-level JNI and compatible low-level Java APIs.
- runtime/: default Java runtime, omitted by runtime-free exports.
- examples/: basic/class scripts and C++/Java embedding examples.
- stdlib/: math and container declarations, source, precompiled ABD, matching JSONs, API guides.

Start with [basic syntax](QUICKSTART.en.md), then the [language guide](LANGUAGE.en.md), the [math guide](../stdlib/MATH.en.md), and the [container guide](../stdlib/CONTAINERS.en.md).
