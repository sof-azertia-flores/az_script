# Third-Party Components

[中文](THIRD_PARTY.md) | English

The compiler includes Google Gson 2.11.0 (`com.google.code.gson:gson:2.11.0`) for JSON encoding and decoding. Its source is [google/gson](https://github.com/google/gson), licensed under Apache License 2.0. The complete license is shipped as [licenses/Gson-LICENSE.txt](licenses/Gson-LICENSE.txt), copied unchanged from the [Gson 2.11.0 release LICENSE](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE). No additional NOTICE content has been added or inferred.

A package exported with `--jit` (`jit` is not null in `manifest.json`) also contains the JIT module `lib/libazscript-jit.so` (`.dylib` on macOS), which statically links LLVM 18.1.8. Its source is [llvm/llvm-project](https://github.com/llvm/llvm-project), licensed under the Apache License 2.0 with LLVM Exceptions. The complete license is shipped as [licenses/LLVM-LICENSE.txt](licenses/LLVM-LICENSE.txt), copied unchanged from `llvm/LICENSE.TXT` in `llvm-project-18.1.8.src.tar.xz` (SHA-256 `0b58557a6d32ceee97c8d533a59b9212d87e0fc4d2833924eb6c611247db2f2a`). Packages without the JIT contain no LLVM code.

By default, the exporter uses its machine's JDK and `jlink` to create the `runtime/` Java runtime. Original licenses and third-party notices under `runtime/legal/`, and version information in `runtime/release`, are preserved. Those files determine the actual vendor, version, and terms. With `--system-java`, no Java runtime is bundled; the recipient uses an installed Java runtime.

Each third-party component retains its own license. These licenses do not replace the license of AzScript or a host application using AzScript.
