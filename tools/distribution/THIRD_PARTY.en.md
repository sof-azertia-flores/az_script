# Third-Party Components

[中文](THIRD_PARTY.md) | English

The compiler includes Google Gson 2.11.0 (`com.google.code.gson:gson:2.11.0`) for JSON encoding and decoding. Its source is [google/gson](https://github.com/google/gson), licensed under Apache License 2.0. The complete license is shipped as [licenses/Gson-LICENSE.txt](licenses/Gson-LICENSE.txt), copied unchanged from the [Gson 2.11.0 release LICENSE](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE). No additional NOTICE content has been added or inferred.

By default, the exporter uses its machine's JDK and `jlink` to create the `runtime/` Java runtime. Original licenses and third-party notices under `runtime/legal/`, and version information in `runtime/release`, are preserved. Those files determine the actual vendor, version, and terms. With `--system-java`, no Java runtime is bundled; the recipient uses an installed Java runtime.

Each third-party component retains its own license. These licenses do not replace the license of AzScript or a host application using AzScript.
