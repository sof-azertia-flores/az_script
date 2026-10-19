# C++ API and ABI migration

[中文](RUNTIME_ABI.md) | English

The AzScript C++ package version is **0.3.0**, with runtime ABI **1**. This identity is independent of script `metadata.version`, exec v10, and JNI snapshot v10. Rebuild C++ hosts, the JNI bridge, and plugins with matching headers and native libraries. High-level Java method signatures remain unchanged.

## Typed value access

Read through `variable::type_tag()` and `get<T>()`; write through the existing `setValue()` methods:

```cpp
if (value->type_tag() == azertian::INT_VALUE) {
    int copy = value->get<int>();
    value->setValue(copy + 1);
}
azertian::address pointer = addressValue->get<azertian::address>();
```

`get<T>()` accepts `int`, `azertian::address`, `float`, `double`, `bool`, `std::string`, and `std::shared_ptr<azertian::slot_block>`, returning a read-only reference. An incompatible tag or absent payload throws `std::invalid_argument`. The reference expires when the variable is assigned or destroyed; copy scalars or strings before retaining them across calls. Void has no payload; inspect its tag instead.

Migrate casts, dereferences, and direct writes through `variable::value`, as well as direct reads or writes of `variable::type`. The migration introduces accessors before switching to inline Value; raw fields are not a supported compatibility interface. To modify a real global or heap slot, call `setValue()` on that slot rather than a copy. Callback arguments remain evaluated snapshots. Copying a C++ object handle does not implement script object value assignment; script ownership and cleanup rules remain in force.

## ABI identity and packaging

`<azscript/runtime.hpp>` includes the ABI check automatically. `AZSCRIPT_RUNTIME_ABI` and `azertian::RUNTIME_ABI_VERSION` identify the headers; the C export `azscript_runtime_abi()` identifies the linked library. Headers reference the versioned symbol `azscript_require_runtime_abi_1`: a missing symbol fails linking or loading, while an incorrect reported ABI raises `AzScript runtime ABI mismatch` during host initialization. Hosts can also call `azertian::require_runtime_abi()` explicitly.

Shared libraries use a distinct basename so old hosts requesting the unversioned `libabdInvoker.so` cannot silently load the new layout:

| Platform | ABI 1 link/load identity |
| --- | --- |
| Linux | Link `libabdInvoker-1.so`; SONAME `libabdInvoker-1.so.1` |
| macOS | Link `libabdInvoker-1.dylib`; install name `@rpath/libabdInvoker-1.1.dylib` |
| Windows | `abdInvoker-1.dll`, MSVC import library `abdInvoker-1.lib`; MinGW may add a `lib` prefix |

Packages provide no compatibility alias under the old basename. Do not rename new libraries to old names or mix versions of headers and libraries. CMake targets remain `AzScript::Runtime` / `AzScript::RuntimeShared`. `find_package(AzScript 0.3 CONFIG REQUIRED)` uses same-minor compatibility: requests for 0.2 or 0.4 do not accept the 0.3 package. CMake configuration exposes `AzScript_RUNTIME_ABI`.

Dynamic plugins define their registration entry with `AZSCRIPT_EXTERN_ENTRY`, which also exports the expected ABI. The loader checks it after signature verification and mapping, before calling the registration entry. Old plugins without this export are explicitly rejected; rebuild and re-sign them. Signatures still cover the entire binary, with an unchanged signature format. See [external libraries](EXTERN_LIBRARY.en.md).

Future breaking C++ layout or calling-convention changes must update the ABI constant, versioned guard symbol, shared-library basename/SOVERSION, compatible CMake package version, and plugin build tools together. Changing only the displayed project version is insufficient.
