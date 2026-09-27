# Java Embedding API

[中文](README.md) | English

Requires JDK 17+, CMake 3.20+, and a C++20 compiler. The bridge JAR has no third-party runtime dependencies. It uses the adjacent `abdjni` and `interpreter` sources; do not mix headers or binaries from different versions.

Build from the repository root:

```sh
cmake -S . -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
ctest --test-dir build/native --output-on-failure
cd abdJavaInvoker
./build.sh
```

On macOS, the JDK is discovered through `java_home`; `JAVA_HOME` can also be set. Standalone `build.sh` needs only a local JDK; the original Gradle project also supports `./gradlew jar`. CTest runs JNI regressions in a real JVM with `-Xcheck:jni`; the required Java/Python tools run locally without Maven downloads.

Set `-Djava.library.path=/absolute/path/build/native/abdjni` to locate `abdJ`, or specify the file with `-Dazertia.native.library=/absolute/path/libabdJ.dylib`. Windows uses `abdJ.dll`, Linux `libabdJ.so`. The associated `abdInvoker` shared library must also be loadable by the system.

```java
int hostAdd = 0x12340001;
AbdInvoker.registerJfunction(hostAdd,
    values -> (Integer) values[0] + (Integer) values[1]);
try {
    AbdInvoker.loadScript(new File("program.exec.abd"));
    // insertScript(libraryFile) can be called here.
    AbdInvoker.flush();
    Object result = AbdInvoker.invoke(hostAdd, 20, 22); // Integer 42
    AbdInvoker.saveStatus(new File("state.abd"));
    AbdInvoker.loadStatus(new File("state.abd"));
} finally {
    AbdInvoker.close();
}
```

The script must declare a complete signature with the same ID:

```c
extern int host_add(int, int):0x12340001;
int main() { return host_add(20, 22); }
```

Signature types map exactly to Java objects: `int` → `Integer`, `float` → `Float`, `double` → `Double`, `boolean` → `Boolean`, `string` → `String`, `address` and class pointers → `azertia.Address`, and `void` → `null`. Literal objects (type 8) do not cross the Java boundary: calling a function returning one fails after destroying that value; they also cannot be passed or read from Java. Use pointers instead. Calls do not automatically convert numeric types. The compiler checks statically known arguments; the native runtime checks actual arguments before callbacks and actual results afterward. Count/type mismatches surface as `IllegalStateException`; callbacks never receive arguments violating the signature.

The bridge encodes/decodes `Integer`, `Float`, `Double`, `Boolean`, `String`, `Address`, and `null`, without implicitly truncating unsupported objects such as `Long`. External functions with declared signatures cannot have `void` parameters, so Java `null` cannot be an argument to them. Only callbacks declared to return `void` may return `null` in calls with declared signatures. Untyped script parameters may still hold this empty value. Strings use UTF-8 and support Chinese text, supplementary-plane characters, empty strings, and embedded NUL. Calls require no manual heap-slot allocation or release. Script functions 0 and 1 are the load and unload hooks; namespace `0x0abd` is reserved for built-ins. `registerJfunction` rejects namespaces `0`, `0xfff`, and `0xabd` with `IllegalArgumentException`. Missing IDs in the script's `#namespace` are unknown functions, not Java callback fallbacks.

`AbdInvoker.addTrustedPublicKey(String)` and `addTrustedPublicKey(File)` provide PEM public keys for `load_extern_library`; `clearTrustedPublicKeys()` clears them. Java hosts do not automatically read `AZSCRIPT_TRUSTED_KEY` or `trusted_key.pem`. Dynamic libraries use C++ `registerExecutor` in the same process and share namespace rules with `registerJfunction`.

Use explicit `:0003` numbering for stable ordinary-function IDs; automatic IDs may change with source. AST `abstract` stores source-name bindings with extern priority: when a same-name extern and definition coexist, it points to the extern. Read local definition locations from the `body` namespace and `metadata.position/name`. Hint libraries use definition namespace 0000 as a placeholder. Query `namespaceForHint` and combine that actual namespace with the library's public low 16-bit number. Do not apply the current module's namespace to every abstract entry; imported functions belong to their respective libraries. Deploy matching AST and ABD files.

Generic script functions retain one erased implementation and receive hidden operation contexts from compiled script callers. `AbdInvoker.invoke` cannot supply these contexts and rejects a generic entry directly. Export an ordinary non-generic wrapper with concrete parameter and return types, then invoke that wrapper's ID from Java:

```c
<T> T identity(T value) { return value; }
int integer_identity(int value):0003 { return identity<int>(value); }
```

The wrapper uses the existing Java value mapping. Generic extern declarations must resolve to script implementations; Java callbacks cannot implement them. Script reflection also targets ordinary entries with zero hidden contexts, so use the same wrapper rather than a constructor, destructor, factory, or generic entry.

The original `JfuncExecutor` with `void run(Object[])` remains supported and returns `null` to scripts. `registerJfunction(int, Function<Object[],Object>)` can return any supported value. Java callback exceptions return to the original caller. Unknown callbacks, type errors, file errors, and invalid addresses become Java exceptions; C++ exceptions never cross JNI.

Each process uses one shared script/heap. `AbdInvoker` serializes multithreaded calls and permits callbacks to invoke again on the same thread. A callback must not wait for another thread that will call the runtime, or it will wait on its own runtime lock. Pure host-callback reentry also has a depth limit. Multiple independent script instances are not implemented.

`destroyScript()` is repeatable and releases script-owned state while preserving independently allocated host memory and callback registrations for reload. `close()` also releases callback JVM global references; call it before host exit or class-loader unloading. Cleanup continues even if the unload hook throws. `unregisterJfunction(id)` removes one registration. Destruction, insertion, flush, and snapshot restoration are forbidden during execution or callbacks; `saveStatus` returns `false` from a callback. `loadScript` and `insertScript(File)` only assemble modules and require explicit `flush()` afterward. `namespaceForHint(String)` returns the mounted namespace. Initialization callbacks may invoke linked functions. Failed link validation permits adding dependencies and retrying; failed onload faults the script, requiring close and reload.

Class objects and `address` travel through independent immutable `azertia.Address`, never `Integer` or `Long`. `Address.of(long bits)` preserves the entire unsigned 64-bit pattern, `bits()` returns raw bits, and `toString()` uses unsigned decimal. Thus `Address.of(-1L)` represents `18446744073709551615`. `Address.NULL` is address zero, distinct from Java `null` representing `void`. High-bit addresses may be passed and stored, but memory access requires an actual live allocation. Automatic objects returned to a host become owned by the script's global scope and are destroyed on explicit close. Returned `new` objects still require calling script `delete` before close. Member references do not own other objects; raw `memFree` frees memory without invoking user destructors.

`saveStatus` writes v9 snapshots, storing globals as an array in runtime slot order to match exec v9 numeric variable IDs. Snapshots also contain all heap slots (including literal objects and their addresses in fields), allocation intervals, object addresses, destructors, creation modes, automatic cleanup order, and bound destructor operation contexts with recursive default-factory references. Restored literal objects receive new addresses and references to them are rewritten. `globalMemories2Str()` displays global slot numbers such as `-1` and `-2`. Independent address tag `0xce200b` carries exactly eight little-endian bytes. Global values, heap slots, allocation/object bases, and ownership lists preserve all 64 address bits; counts, lengths, variable numbers, and function IDs remain 32-bit. Do not mix old JARs, JNI libraries, or executables.

`loadStatus` validates ordered module bytes, actual namespaces, hints, global layouts, global-array counts and encoded values, object records, allocation bases, destructor signatures and hidden counts, context ABI/kind/depth, and each default factory's signature and hidden arity before atomic state replacement. Contexts are serialized as data; no native pointers or expired invocation frames are retained. Corrupt files cannot leave partially restored globals or heap. Replacement does not run old object destructors. Only v9 snapshots are accepted; old and unversioned files are rejected.

Saving writes a temporary file in the same directory and then renames it over the target; failure preserves the original. Scripts and snapshots are limited to 64 MiB. Only initialized idle states can be saved/restored; oversized saves fail explicitly. Restoration does not relink or reinitialize. Onload failure preserves the original error and cleans the current scope; closing cleans remaining script objects while retaining independently allocated host memory.

`azertia.jni.Caller*` is the low-level API. Memory-address parameters, address returns from `memAlloc`/`call`, and callback slot-address arrays use Java `long`, `long`, and `long[]`, carrying unsigned raw bits. `getMemAddress(long pointer)` and `ACputMemAddress(long pointer, long bits)` read/write addresses; `getMemInt` and integer `ACputMem` still handle integers only. Callers must initialize, manage allocations, prevent multistep operations from interleaving with other threads' calls/destruction/restoration, and recheck retained pointers after restoration. Address 0 is an empty result and cannot be dereferenced. `memAlloc(0)` returns 0; `memFree(0)` does nothing. `memFree` accepts live allocation bases with no automatic owner or with the script global scope as owner; other active script scopes' allocations are rejected with `IllegalArgumentException`. It performs raw release without destructors. Low-level callback arguments are temporary borrows and must not be freed by the caller. Prefer `AbdInvoker` for new integrations.

Run the JNI regression separately:

```sh
python3 tests/run_jni_tests.py \
  --library ../build/native/abdjni/libabdJ.dylib \
  --work-dir ../build/jni-tests
```

ABD bytecode and snapshots are data encodings, not encryption or protection against decompilation. Sensitive logic needs separate protection through host distribution, keys, and trust boundaries.

Owning `buffer<T>` values remain script OBJECT_VALUE values and cannot cross JNI directly. Use ordinary wrappers taking supported scalars or Address. Buffer snapshots save live elements once, with element contexts, direct slot width, placement bindings, capacity/length, and view IDs. Restoring rebuilds continuous storage and remaps saved addresses without running user destructors. Old buffer-element addresses also expire on growth/removal/replacement. Raw block resizing cannot modify buffer storage or element views. A snapshot’s cumulative buffer capacity times stride may not exceed 1,048,576 slots; this persistence budget is checked before slab allocation, and saving an oversized state fails explicitly.
