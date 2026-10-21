# 第三方组件

中文 | [English](THIRD_PARTY.en.md)

编译器包含 Google Gson 2.11.0（Maven 坐标 `com.google.code.gson:gson:2.11.0`），用于 JSON 编解码。项目来源为 [google/gson](https://github.com/google/gson)，许可证为 Apache License 2.0，完整文本随发行包保存在 [licenses/Gson-LICENSE.txt](licenses/Gson-LICENSE.txt)。该文本原样取自 [Gson 2.11.0 发布标签中的 LICENSE](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE)，未添加或推测额外的 NOTICE 内容。

以 `--jit` 导出的发行包（`manifest.json` 中 `jit` 不为空）另含 JIT 模块 `lib/libazscript-jit.so`（macOS 为 `.dylib`），其中静态链接 LLVM 18.1.8。项目来源为 [llvm/llvm-project](https://github.com/llvm/llvm-project)，许可证为 Apache License 2.0 with LLVM Exceptions，完整文本保存在 [licenses/LLVM-LICENSE.txt](licenses/LLVM-LICENSE.txt)，原样取自 `llvm-project-18.1.8.src.tar.xz`（SHA-256 `0b58557a6d32ceee97c8d533a59b9212d87e0fc4d2833924eb6c611247db2f2a`）中的 `llvm/LICENSE.TXT`。不含 JIT 的发行包不包含 LLVM 代码。

默认发行包使用导出机器上的 JDK 和 `jlink` 生成 `runtime/` Java 运行环境。其原始 `runtime/legal/` 许可证及第三方声明和 `runtime/release` 版本信息随生成结果保留；实际发行商、版本和条款以这些文件为准。选择 `--system-java` 时不附带该运行环境，而是使用接收机器已安装的 Java。

这些第三方组件的许可证各自适用，不替代 AzScript 自身或使用 AzScript 的宿主应用的许可证。
