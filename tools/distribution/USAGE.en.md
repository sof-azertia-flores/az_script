# Using the AzScript Distribution

[中文](USAGE.md) | English

Move this directory as a unit. The compiler produces ABD; the standalone interpreter defaults to main (0x0fff0000). C++ and Java hosts use the same ABD. Relative input/output paths resolve against the terminal working directory; includes resolve against their containing source file.

Export from source with `python3 tools/export_distribution.py /path/to/azscript --offline`, replacing the destination. Offline mode requires cached dependencies. A Java runtime is bundled by default; `--system-java` reduces package size and requires installed Java 17+ on the recipient.

Native libraries target the build system and CPU, not multiple platforms: .dylib on macOS, .so on Linux, .dll on Windows. Re-export on each target system/architecture. C++ compiler ABI, standard library, and runtime must match the host. Preserve layout and do not mix headers, JARs, or libraries from different builds.

## Compile and run

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

With `-o output/demo.abd` or `-o output/demo.exec.abd`, JSON defaults are output/demo.ast.json and output/demo.exec.json, individually overridden by explicit flags.

Compiler launchers prefer bundled runtime/. Without it, install Java 17+ and use JAVA_HOME or PATH. Compilation needs no Gradle, source checkout, or downloads. The native interpreter needs no Java.

```sh
./compile.sh examples/hello.azs -o hello.exec.abd \
    --ast hello.ast.json --exec-json hello.exec.json
```

AST names bind with extern priority; independent definition IDs live in body namespaces and metadata.position/name. Name-based host calls need matching AST output; hint definitions also require the actual mounted namespace. Fixed-main execution needs no debug JSON deployment. ABD is a binary instruction tree, not encryption.

Exec v9 uses numeric opcodes and raw ordered ABD stacks. Exec JSON retains field names for inspection with numeric c; it is not the binary Map layout. Only v9 is accepted; recompile older ABD from source/readable AST. See [binary format](EXEC_FORMAT.en.md).

Launchers also accept full CLI commands: `./compile.sh compile-json hello.ast.json -o restored.abd` rebuilds ABD and defaults restored.exec.json without overwriting the input AST. Use `./compile.sh --help`; pack/unpack archive/extract files.

Call bin/azscript-run (Windows bin\azscript-run.exe) directly or through run.sh/run.cmd. An optional second argument is a hex/decimal function ID, for parameterless functions only. Repeated --insert path assembles libraries, then automatically flushes, runs dependency-ordered onload, executes entry, and closes.

## Math library

Clients include declarations from stdlib/math.include.azs, e.g. `#include "../stdlib/math.include.azs"` under examples/. Implementation stdlib/math.azs compiles independently with AZSCRIPT_MATH; do not include it in clients. Precompiled ABD and matching AST/exec JSON are bundled. Recompilation updates all three:

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

The regression returns 0; running the client alone fails linking. Floating APIs require double, integer APIs int, and math_pow `(double, int)`. Use math_sqrt(9.0), not integer 9. See [math documentation](../stdlib/MATH.en.md) for functions, domains, and accuracy.

C++ insert_script or Java insertScript(new File("stdlib/math.exec.abd")) assembles the library, followed by flush. namespace_for_hint("AZSCRIPT_MATH") / namespaceForHint("AZSCRIPT_MATH") returns the actual namespace. Public low numbers are fixed in the header (math_pi is 0002); host IDs are `(namespace << 16) | 0x0002`, never hardcoded assumed 4d41.

## Container library

Clients include stdlib/containers.include.azs, for example `#include "../stdlib/containers.include.azs"` under examples/. Implementation stdlib/containers.azs compiles independently as AZSCRIPT_CONTAINERS. The package ships stdlib/containers.exec.abd and both JSON views:

```sh
./compile.sh stdlib/containers.azs
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

The regression returns 0. `List<T>` is a doubly linked list; `Stack<T>` and `Queue<T>` each own one list. Create them with new and destroy them with delete. Assumed namespace c071 is not the runtime address. See [the container guide](../stdlib/CONTAINERS.en.md) for operations, default construction, and IDs.

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

Invoke accepts Integer, Float, Double, Boolean, String, azertia.Address, and null without truncating Long to int. Register matching IDs and exact script signatures:

```java
AbdInvoker.registerJfunction(0x12340001,
    values -> (Integer) values[0] + (Integer) values[1]);
```

The script declares `extern int host_add(int, int):0x12340001;`. Built-ins need no registration. The bridge maintains one active script per process, serializes calls, and permits same-thread reentry. Do not wait in a callback for another thread calling this runtime. Class pointers/address cross as azertia.Address; literals cannot cross JNI and returned literals fail after destruction. Return pointers instead. Address.of(long bits)/bits() retain all unsigned 64 bits. Address.NULL is address zero, distinct from void's Java null; Integer/Long cannot replace addresses implicitly. An address is a runtime reference, not an independently owned Java object. Close releases script and callback registrations; place it in finally.

## Multi-file assembly

```sh
./compile.sh examples/multifile/point.azs
./compile.sh examples/multifile/main.azs
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd
```

Java calls loadScript(mainFile), insertScript for each library, flush, then invoke. namespaceForHint("POINT_LIB") supplies the actual namespace; public IDs are `(namespace << 16) | localId`. Identical assumed namespaces in different modules may identify different libraries. Missing libraries/signature errors fail flush atomically and permit retry after dependencies are added. Onload failure requires close/recreation. Insert, flush, and snapshot replacement are forbidden during execution/callbacks.

JNI v9 snapshots require initialized idle scripts, including literals in heap fields, and identical ordered module bytes, actual namespaces, and global layouts. Restore does not rerun onload. Old executables/snapshots are unsupported. See [hint linking](HINT_LINKING.en.md) for syntax, out-of-class implementations, and lifetimes.

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
