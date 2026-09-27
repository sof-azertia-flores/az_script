# External Dynamic Libraries

[中文](EXTERN_LIBRARY.md) | English

`load_extern_library(name)` is a built-in with signature `void(string)` and ID `0x0abd0007`. It opens a signed native library for the current operating system and calls its `azscript_load_extern` entry point. The library registers external functions through the existing C++ host API; it introduces neither a separate plugin ABI nor a script hint module.

```c
extern int add(int, int):0x12340001;
int main() {
    load_extern_library("demo");
    return add(20, 22);
}
```

Names may contain directories. `plugins/demo` searches only that directory. A name without a directory searches the current directory, `AZSCRIPT_EXTERN_PATH` (`:`-separated on Unix, `;` on Windows), then the process executable's directory. A final `.so`, `.dylib`, or `.dll` suffix is removed and replaced with the platform suffix:

| Platform | Library | Signature |
| --- | --- | --- |
| macOS | `demo.dylib` | `demo.signature` |
| Windows | `demo.dll` | `demo.signature` |
| Other | `demo.so` | `demo.signature` |

The runtime reads the entire library (at most 64 MiB), verifies it against configured public keys, then maps those verified bytes. Linux uses `memfd` or a private file referenced only by its descriptor, preventing path replacement after verification. Loading the same canonical path again returns immediately. Libraries are never unloaded, keeping registered function objects valid.

Plugins may depend on shared `libabdInvoker` (`abdInvoker.dll` on Windows), C/C++ runtimes, and the system loader. The host loads these dependencies before opening plugins, preventing a plugin RPATH from substituting an unsigned dependency. Other dynamic dependencies are rejected.

`azscript_load_extern` must call `registerExecutor` before returning. Registration must reach the host process's runtime instance; linking another copy causes failure. Namespace rules match direct hosts: do not occupy `0`, `0xfff`, `0xabd`, or namespaces mounted by active scripts.

Script `extern` declarations still define parameter and return types. Native libraries provide implementations for the corresponding IDs. `load_extern_library` executes during a script call, without `insert_script` or `flush`.

## Public keys

Only PEM `BEGIN PUBLIC KEY` (SubjectPublicKeyInfo) on curve P-256 is accepted; private keys are rejected. `xxx.signature` is a DER-encoded ECDSA-P256 signature of the SHA-256 digest of the library bytes.

Embedded hosts supply public keys explicitly:

```cpp
azertian::add_trusted_public_key_pem(pemText);
azertian::clear_trusted_public_keys();
```

```java
AbdInvoker.addTrustedPublicKey(pemText);
AbdInvoker.addTrustedPublicKey(new File("trusted_key.pem"));
AbdInvoker.clearTrustedPublicKeys();
```

Neither embedded path reads environment variables. At startup, standalone `azscript-run` combines:

- `AZSCRIPT_TRUSTED_KEY`: PEM text or a PEM file path.
- `trusted_key.pem` in the current, executable, and shared-runtime directories.

Verification with any trusted key is sufficient. With no keys, `load_extern_library` fails. Store public keys, not private keys, in `trusted_key.pem`.

## Writing a library

Include only `azscript/extern_library.hpp`; it includes public `runtime.hpp`, exposing `executor`, `function`, `variable`, `registerExecutor`, and `unregisterExecutor`.

```cpp
#include <azscript/extern_library.hpp>

class Add final : public azertian::function {
public:
    int return_type() override { return azertian::INT_VALUE; }
    std::shared_ptr<azertian::variable> invoke(
        std::shared_ptr<azertian::environment>,
        std::vector<std::shared_ptr<azertian::variable>> args) override {
        int value = *static_cast<int*>(args.at(0)->value) + *static_cast<int*>(args.at(1)->value);
        return std::make_shared<azertian::variable>(value);
    }
};

class Plugin final : public azertian::executor {
public:
    int namespace_name() override { return 0x1234; }
    std::shared_ptr<azertian::function> getiFunction(int id) override {
        if (id == 0x12340001) return std::make_shared<Add>();
        return nullptr;
    }
};

AZSCRIPT_EXTERN_ENTRY {
    azertian::registerExecutor(std::make_shared<Plugin>());
}
```

Link shared `abdInvoker` (CMake target `AzScript::RuntimeShared`). Static `AzScript::Runtime` has a separate registry which plugin `registerExecutor` calls will not reach. Java JNI already links the shared library.

From the distribution root:

```sh
./compile_extern_lib.sh -o demo demo.cpp
./sign_extern_lib.sh --generate-key private.pem trusted_key.pem
./sign_extern_lib.sh private.pem demo.so
```

Windows uses `compile_extern_lib.cmd` and `sign_extern_lib.cmd`. The signer is `bin/azscript-sign-extern`; shell scripts locate it. `compile_extern_lib.sh` uses the system C++ compiler with `include/` and shared libraries in `lib/`.

In a source checkout, `AZSCRIPT_INCLUDE`, `AZSCRIPT_LIBDIR`, and `AZSCRIPT_SIGN_EXTERN` can override paths. Otherwise scripts try the distribution layout, then repository `build/native` or `build/sanitize`.

## Owning buffers at the host boundary

Exec v9 represents `buffer<T>` using OBJECT_VALUE with a distinct storage category; it is not an address or a normal resizable class block. Rebuild C++ integrations and plugins against the matching runtime. Raw block resize/address APIs cannot resize a buffer or an inline element view. Script wrappers using get/set/push/resize preserve element ownership and type checks; JNI does not directly marshal OBJECT_VALUE. Element pointers retained from this expire on growth, removal, or whole-buffer replacement.

Class operators lower to ordinary methods and need no native operator ABI. Default scalar comparison uses value_compare; custom comparators may be ordinary AZS functions dispatched through existing reflection. Reflection still rejects internal or unbound generic entries, so expose ordinary wrappers when needed.
