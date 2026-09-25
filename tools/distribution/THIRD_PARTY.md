# 第三方组件

编译器包含 Google Gson 2.11.0（Maven 坐标 `com.google.code.gson:gson:2.11.0`），用于 JSON 编解码。项目来源为 [google/gson](https://github.com/google/gson)，许可证为 Apache License 2.0，完整文本随发行包保存在 [licenses/Gson-LICENSE.txt](licenses/Gson-LICENSE.txt)。该文本原样取自 [Gson 2.11.0 发布标签中的 LICENSE](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE)，未添加或推测额外的 NOTICE 内容。

默认发行包使用导出机器上的 JDK 和 `jlink` 生成 `runtime/` Java 运行环境。其原始 `runtime/legal/` 许可证及第三方声明和 `runtime/release` 版本信息随生成结果保留；实际发行商、版本和条款以这些文件为准。选择 `--system-java` 时不附带该运行环境，而是使用接收机器已安装的 Java。

这些第三方组件的许可证各自适用，不替代 AzScript 自身或使用 AzScript 的宿主应用的许可证。
