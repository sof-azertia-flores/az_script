# C++ API 与 ABI 迁移

中文 | [English](RUNTIME_ABI.en.md)

AzScript C++ 包版本为 **0.3.0**，运行时 ABI 为 **1**。这是独立于脚本 `metadata.version`、exec v10 和 JNI snapshot v10 的编号。现有 C++ 宿主、JNI 桥和插件必须与同一套头文件和原生运行库一起重编译。Java 高层方法签名保持不变。

## 类型化值访问

读取值使用 `variable::type_tag()` 与 `get<T>()`，写入使用已有的 `setValue()`：

```cpp
if (value->type_tag() == azertian::INT_VALUE) {
    int copy = value->get<int>();
    value->setValue(copy + 1);
}
azertian::address pointer = addressValue->get<azertian::address>();
```

`get<T>()` 支持 `int`、`azertian::address`、`float`、`double`、`bool`、`std::string` 和 `std::shared_ptr<azertian::slot_block>`，返回只读引用；实际标签不匹配或没有 payload 时抛出 `std::invalid_argument`。引用在当前变量被写入或销毁后失效；需跨调用保留标量或字符串时先复制。void 没有 payload，使用标签判断。

原先对 `variable::value` 的转换、解引用和直接写入，以及对 `variable::type` 的直接读写，都必须迁移。迁移顺序先接入访问器，再内联 Value；裸指针字段不是保留的兼容接口。写真实全局或堆槽时对该槽执行 `setValue()`，不能只修改副本。回调参数仍是求值后的快照。对象值的 C++ 句柄复制不等于脚本对象按值赋值，脚本对象的所有权与清理规则保持。

## ABI 识别与打包

`<azscript/runtime.hpp>` 自动包含 ABI 检查。`AZSCRIPT_RUNTIME_ABI` 和 `azertian::RUNTIME_ABI_VERSION` 是头文件期望值；C 导出 `azscript_runtime_abi()` 返回实际运行库值。头文件还引用版本化符号 `azscript_require_runtime_abi_1`：缺失时链接或加载失败，实际 ABI 值不一致时在宿主初始化期间报告 `AzScript runtime ABI mismatch`。宿主也可显式调用 `azertian::require_runtime_abi()`。

共享库使用独立 basename，避免旧无版本 `libabdInvoker.so` 宿主误加载新布局：

| 平台 | ABI 1 链接/加载身份 |
| --- | --- |
| Linux | 链接 `libabdInvoker-1.so`，SONAME `libabdInvoker-1.so.1` |
| macOS | 链接 `libabdInvoker-1.dylib`，安装名 `@rpath/libabdInvoker-1.1.dylib` |
| Windows | `abdInvoker-1.dll`，MSVC 导入库 `abdInvoker-1.lib`；MinGW 可带 `lib` 前缀 |

发行包不提供旧 basename 的兼容别名。不要把新库重命名为旧库，或混合多个版本的头文件与库。CMake 目标仍是 `AzScript::Runtime` / `AzScript::RuntimeShared`；`find_package(AzScript 0.3 CONFIG REQUIRED)` 使用同次版本兼容规则，0.2 与 0.4 请求不会接受 0.3 包。`AzScript_RUNTIME_ABI` 在 CMake 配置中可读。

动态插件用 `AZSCRIPT_EXTERN_ENTRY` 定义注册入口，该宏同时导出期望 ABI。加载器在验签、映射后、调用注册入口前检查它。旧插件没有此导出时明确拒绝；重编译后重新签名即可迁移。签名仍覆盖整个二进制文件，签名格式没有变化。详见 [外部动态库](EXTERN_LIBRARY.md)。

未来破坏 C++ 布局或调用约定时，同时更新 ABI 常量、版本化检查符号、共享库 basename/SOVERSION、CMake 包兼容版本及插件构建工具。不能只修改项目显示版本。
