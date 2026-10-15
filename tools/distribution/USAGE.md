# AzScript 发行包使用说明

中文 | [English](USAGE.en.md)

本目录可整体移动到另一位置使用。编译器生成 ABD，独立解释器默认调用 `main`（`0x0fff0000`）；C++ 与 Java 宿主使用相同 ABD 文件。相对输入、输出路径按终端当前目录解析，`#include` 先按包含它的源文件所在目录解析，再依次查找各 `-I` 目录，最后查找本包的 `stdlib/`（见[编译并运行](#编译并运行)）。

从源码仓库导出时执行 `python3 tools/export_distribution.py /path/to/azscript --offline`，将目标路径替换为要交付的目录。`--offline` 要求本机已有构建依赖缓存。默认附带 Java 运行环境；加 `--system-java` 可缩小发行包，改用目标机器已安装的 Java 17+。

发行包包含当前构建系统与 CPU 架构的原生库，不是跨平台二进制合集。macOS 为 `.dylib`，Linux 为 `.so`，Windows 为 `.dll`；换系统或架构应在目标系统重新导出。C++ 库还须匹配宿主的编译器 ABI、标准库及运行库。保留发行包目录结构，不要混用不同构建的头文件、JAR 与原生库。

## 编译并运行

在发行包根目录执行：

```sh
./compile.sh examples/hello.azs
./run.sh examples/hello.exec.abd
./compile.sh examples/classes.azs -o classes.exec.abd
./run.sh classes.exec.abd
```

Windows 命令提示符使用：

```bat
compile.cmd examples\hello.azs
run.cmd examples\hello.exec.abd
```

默认在源文件旁边生成三份产物：`hello.exec.abd` 可执行文件、`hello.ast.json` 可读语法树和 `hello.exec.json` 降级后的指令树。`-o` 指定 ABD 输出位置，`--ast` 和 `--exec-json` 可分别指定两个 JSON 的位置。`hello.azs` 输出 `Hello, AzScript!` 和主函数返回值 `42`。解释器将非 `void` 返回值打印到标准输出，成功执行的进程退出码为 `0`，并不把脚本的整数返回值当作进程退出码。执行失败返回非零值并向标准错误输出诊断。

编译错误以 `文件:行:列: error: 消息` 写到标准错误，位置指向原始文件，包括被包含的头文件和宏的使用位置；随后的 `note:` 行给出 include 链。彼此独立的错误会一并报告，默认最多 20 个；`--max-errors N` 修改上限（0 表示不限）。编译失败时最后输出汇总行，例如 `Error: compilation failed: 2 errors`，退出码为 `1`，不生成任何产物。退出码 `2` 表示内部编译器错误，即编译器自身的缺陷；`--debug-compiler` 会在报告中附上 Java 堆栈。选项可以写在源文件路径之前或之后；`--` 结束选项，用于以 `-` 开头的源文件路径。格式详见[语言说明](LANGUAGE.md#诊断)。

警告（例如把赋值用作条件、不可达代码）的格式为 `文件:行:列: warning: 消息 [-W<名称>]`，退出码仍为 `0`，照常生成产物。`-Wall` 开启全部警告，`-W<名称>` 开启单个警告（例如默认关闭的 `-Wunused-variable`），`-Wno-<名称>` 关闭单个警告，`-w` 关闭全部警告，`-Werror` 把警告变为错误并使编译失败。警告列表见[警告](LANGUAGE.md#警告)。

`#include` 先在所在源文件的目录中查找，再依次查找每个 `-I dir` / `--include-dir dir`，最后查找本包的 `stdlib/`，所以任意位置的调用方都可以写 `#include "math.include.azs"`。include 必须位于源文件目录或上述目录之内：绝对路径、`../../private.azs` 这类离开这些目录的路径以及指向外部的符号链接都会被拒绝，且不会读取该文件。`--unrestricted-includes` 解除这一限制。找不到 include 时列出全部查找过的路径。include 选项只用于源码编译，不用于 `compile-json`。

使用 `-o output/demo.abd` 或 `-o output/demo.exec.abd` 时，两个 JSON 默认分别为 `output/demo.ast.json` 和 `output/demo.exec.json`；显式 `--ast`、`--exec-json` 各自覆盖默认位置。

常规导出包含 `runtime/` 下的 Java 运行环境，编译脚本优先使用它；若导出时选择不带运行环境，需要安装 Java 17 或更新版本，脚本通过 `JAVA_HOME` 或 `PATH` 查找 Java。使用编译器不需要 Gradle、源码目录或网络下载。原生解释器不依赖 Java。

例如为三份产物分别指定位置：

```sh
./compile.sh examples/hello.azs -o hello.exec.abd \
    --ast hello.ast.json --exec-json hello.exec.json
```

AST 包含 extern 优先的名称绑定；普通定义的独立 ID 由 body namespace 与 metadata.position/name 保存。宿主按名称调用时应使用同次编译的 AST，hint 库的定义 ID 还须结合实际挂载 namespace。仅按固定 `main` ID 运行时无需部署这两份 JSON。ABD 是二进制指令树，不是加密格式。

新编译器输出 exec v10：控制指令使用数字 opcode，固定记录按顺序写入裸 ABD stack。`exec.json` 保留字段名便于检查，其 `c` 为数字；它不是二进制文件的 Map 布局。解释器仅接受 v10；旧 ABD 必须从源码或可读 AST 重新编译。完整字段顺序和 opcode 见 [二进制格式](EXEC_FORMAT.md)。

`-g` 在执行文件中加入调试记录：源文件名、函数名和源码位置。调试记录默认关闭，不改变指令和行为，但会把函数名、类名和文件名写入 ABD，不希望携带这些信息的构建不要使用 `-g`。JNI 快照只能恢复到模块字节相同的构建中，因此 `-g` 构建与不加 `-g` 的构建不能共用快照。

编译脚本也接受完整 CLI 命令：`./compile.sh compile-json hello.ast.json -o restored.abd` 从 AST 重建 ABD，并默认生成 `restored.exec.json`，不覆盖输入 AST。`./compile.sh --help` 查看参数；`pack` 和 `unpack` 分别打包与解包文件。

独立程序可直接调用 `bin/azscript-run`（Windows 为 `bin\azscript-run.exe`），也可通过 `run.sh`/`run.cmd` 调用。可选第二个参数是十六进制或十进制函数 ID；该入口只调用无参数函数。可重复使用 `--insert path` 装配库；装配后自动 flush、按依赖顺序执行 onload，再调用入口，最后关闭脚本。库被拒绝时报告 `AzScript error: Cannot insert <path>: ...`；错误中的函数 ID 以十六进制显示，例如 `0x0fff0001`。独立编译的普通模块在默认 namespace 冲突时的处理见 [hint 链接](HINT_LINKING.md)。

### 运行错误

使用 `./compile.sh -g program.azs` 保留源码位置。执行失败时退出码为 1，标准错误输出为：

```text
AzScript error: Division by zero
  at divide (program.azs:2:14)
  at main (program.azs:5:12)
```

未加 `-g` 时帧显示为 `  at function 0x0fff0002`。原生回调显示为 `  at native function 0x12340001`，内置函数不增加原生帧。调用帧从出错函数向外排列，最多保留 64 帧，截断时以 `  ... N more frames` 结束。行和 UTF-16 列从 1 开始。清理保留首个错误及其调用帧，详见[运行错误与调试信息](LANGUAGE.md#运行错误与调试信息)。

## 使用数学库

调用方包含 `stdlib/math.include.azs` 中的 extern 声明：本包的 `stdlib/` 默认在查找范围内，任意位置都可写 `#include "math.include.azs"`；也可按调用源文件所在目录填写相对路径，例如 `examples/` 中使用 `#include "../stdlib/math.include.azs"`。实现 `stdlib/math.azs` 使用 `AZSCRIPT_MATH` hint 独立编译，不能 include 到调用方。发行包附带预编译的 `stdlib/math.exec.abd` 及配套 AST、exec JSON；修改实现后重新编译会默认更新这三份产物：

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

回归示例成功返回 `0`。只运行调用方会因缺少数学库而链接失败。浮点接口要求 `double`，整数接口要求 `int`，`math_pow` 接收 `(double, int)`；例如使用 `math_sqrt(9.0)`，不要传整数 `9`。函数、定义域和精度说明见 [数学库文档](../stdlib/MATH.md)。

C++ 使用 `insert_script`，Java 使用 `insertScript(new File("stdlib/math.exec.abd"))` 装配，然后 `flush()`。`namespace_for_hint("AZSCRIPT_MATH")` / `namespaceForHint("AZSCRIPT_MATH")` 返回实际 namespace。公开低位编号在共享头文件中固定，例如 `math_pi` 为 `0002`；宿主调用 ID 应使用 `(namespace << 16) | 0x0002`，不能硬编码头文件的假定 namespace `4d41`。

## 使用容器库

调用方包含 `stdlib/containers.include.azs`，可写 `#include "containers.include.azs"`，在 `examples/` 中也可写 `#include "../stdlib/containers.include.azs"`。实现 `stdlib/containers.azs` 以 `AZSCRIPT_CONTAINERS` 独立编译。发行包附带 `stdlib/containers.exec.abd` 及配套 JSON：

```sh
./compile.sh stdlib/containers.azs
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

回归示例成功返回 `0`。`List<T>` 是双向链表，`Stack<T>` 和 `Queue<T>` 持有一份链表，两端为 O(1)。`Vector<T>` 是连续数组，`Set<T>` 和 `Map<K, V>` 是有序平坦表；类值键要先设置比较器。用 `new` 创建并用 `delete` 销毁。假定 namespace `c071` 不是运行时地址。操作、默认构造和编号见 [容器库文档](../stdlib/CONTAINERS.md)。

## 嵌入 C++

头文件位于 `include/`，静态库和动态库位于 `lib/`；Windows 动态库的 DLL 位于 `bin/`，导入库位于 `lib/`。导出的 CMake 包自动处理头文件、C++20 与传递链接依赖：

```cmake
find_package(AzScript CONFIG REQUIRED)
target_link_libraries(your_app PRIVATE AzScript::Runtime)
```

`AzScript::Runtime` 将解释器静态链接进宿主；改成 `AzScript::RuntimeShared` 可使用动态解释器。静态链接仍依赖目标系统的 C++ 运行库。共享方式需同时部署本包对应原生库，确保操作系统能在运行时找到它们；Windows 可以将本包 `bin/` 加入宿主进程的 `PATH`。

包含 `<azscript/runtime.hpp>` 后，使用带长度的入口读取 ABD，然后调用主函数：

```cpp
auto script = azertian::load_script(bytes.data(), bytes.size());
// 可在这里 insert_script 其他模块。
script->flush();
auto result = script->invoke(0x0fff0000);
script->destroy();
```

C++ 地址类型为 `azertian::address`，其 `.value` 保存无符号 64 位地址；`variable(address)` 与整数 `variable(int)` 是不同的值类型。地址参数须显式构造，例如 `azertian::address{bits}`，不能用整数替代。类指针也使用地址类型，单个字段仍占一个 slot。字面量对象以 `OBJECT_VALUE`（8）传递，宿主调用得到的字面量对象由宿主持有、不再运行析构；可用 `azscript/runtime.hpp` 中的 `azertian::blocks` API（`create/resize/length/address_of`）直接使用 slot 块。

`load_script` 只装载；`flush()` 链接依赖并执行尚未初始化模块的加载钩子，成功后才能 invoke。`destroy()` 执行销毁钩子并清理脚本，允许重复调用。宿主应在正常和异常退出时都显式关闭脚本。不要用无长度的旧指针入口读取外部文件。完整文件读取、异常处理及预算设置见 `examples/cpp/main.cpp`。

按原 C++ 异常类型捕获，在 `destroy()` 或再次进入运行时之前调用 `azertian::describe_error(error)`。它返回 `what()` 加匹配的调用帧，不含 CLI 前缀和末尾换行。`last_error_trace()` 返回含 `frames` 和 `omitted_frames` 的 `error_trace`；`format_error_trace(trace)` 格式化调用帧，`clear_error_trace()` 清空记录。记录是线程局部的，每次进入（包括回调重入）都重新开始收集。调用栈 API 改变了公开 C++ ABI，宿主和插件须用本包配套的头文件及库重新构建。

使用 `script->insert_script(bytes.data(), bytes.size())` 追加数字 exec 模块时，每个函数保存模块自己的全局 offset；即使两个模块都使用 `-1` 作为第一个全局变量，也分别访问各自的槽。参数、局部变量和对象的块级析构保持独立。固定 namespace 的模块须避免 ID 冲突；独立库使用 `#namespace_hint NAME`，由运行时自动分配 namespace。成功 insert 后须再次 flush；通过 `namespace_for_hint("NAME")` 查询实际位置。数字变量格式与类型信息见 [语言文档](LANGUAGE.md#insert-与模块全局偏移)。

在发行包根目录编译并执行 C++ 示例，需要 CMake 3.20+ 和兼容本包的 C++20 工具链：

```sh
cmake -S examples/cpp -B cpp-build -DCMAKE_PREFIX_PATH="$PWD"
cmake --build cpp-build --config Release
./cpp-build/azscript-host examples/hello.exec.abd
```

Windows 命令提示符：

```bat
cmake -S examples\cpp -B cpp-build -DCMAKE_PREFIX_PATH="%CD%"
cmake --build cpp-build --config Release
cpp-build\Release\azscript-host.exe examples\hello.exec.abd
```

最后一行对应 Visual Studio 等多配置生成器；单配置生成器输出到 `cpp-build\azscript-host.exe`。本示例默认静态链接，不需要复制解释器 DLL。

## 嵌入 Java / JNI

Java 桥位于 `java/abdJavaInvoker.jar`，无第三方 JAR 运行依赖。一起部署 JNI 库 `libabdJ.dylib` / `libabdJ.so` / `abdJ.dll` 及其配套解释器动态库；Unix 库保留在 `lib/`，Windows DLL 保留在 `bin/`。JVM、JNI 库必须使用相同 CPU 架构。

`examples/java/RunScript.java` 通过发行包根目录定位本机 JNI 库，使用 `AbdInvoker.loadScript`、`flush()`、`invoke(0x0fff0000)` 和 `close()`。默认包内运行环境支持直接执行单文件 Java 示例：

```sh
./runtime/bin/java --class-path java/abdJavaInvoker.jar \
    examples/java/RunScript.java "$PWD" examples/hello.exec.abd
```

Windows 命令提示符：

```bat
set "PATH=%CD%\bin;%PATH%"
runtime\bin\java.exe --class-path java\abdJavaInvoker.jar examples\java\RunScript.java "%CD%" examples\hello.exec.abd
```

使用 `--system-java` 导出时，用本机 JDK 17+ 的 `java` 替换上述 `runtime/bin/java`。集成进项目时，也可正常用 JDK 编译、运行，Unix 示例为：

```sh
javac -cp java/abdJavaInvoker.jar -d java-build examples/java/RunScript.java
java -cp "java-build:java/abdJavaInvoker.jar" RunScript "$PWD" examples/hello.exec.abd
```

Windows 命令提示符中的类路径分隔符是分号：

```bat
set "PATH=%CD%\bin;%PATH%"
javac -cp java\abdJavaInvoker.jar -d java-build examples\java\RunScript.java
java -cp "java-build;java\abdJavaInvoker.jar" RunScript "%CD%" examples\hello.exec.abd
```

应用也可直接在首次 JNI 调用前设置 `System.setProperty("azertia.native.library", absoluteLibraryPath)`，或使用 `-Djava.library.path` 查找 `abdJ`。后一种方式仍须保证操作系统能找到它依赖的解释器库。

原生错误保留现有 Java 异常映射，消息可能含多行 UTF-8 调用帧。Java 回调抛出的 throwable 保留原对象身份和消息。每次 JNI 入口（包括重入）都重新开始收集调用帧。

Java `invoke` 支持 `Integer`、`Float`、`Double`、`Boolean`、`String`、`azertia.Address` 和 `null`，不会把 `Long` 隐式截断为 `int`。外部函数使用相同 ID 注册回调，并与脚本完整签名精确匹配：

```java
AbdInvoker.registerJfunction(0x12340001,
    values -> (Integer) values[0] + (Integer) values[1]);
```

对应源码为 `extern int host_add(int, int):0x12340001;`。内置函数无需宿主注册。Java 桥每进程只维护一个活动脚本，串行化调用，支持同线程回调重入；不要在回调中等待另一个调用该运行时的线程。类指针和 `address` 跨宿主边界使用独立的 `azertia.Address`；字面量对象不跨越 JNI 边界，返回它的函数由 Java 调用时报错（该值先被析构），应改为返回指针。`Address.of(long bits)` 与 `bits()` 完整保留无符号 64 位地址，`Address.NULL` 表示地址零，与表示 `void` 的 Java `null` 不同；`Integer` 和 `Long` 不能隐式代替地址。地址只是运行时引用，宿主不能将其视为独立拥有的 Java 对象。`close()` 同时释放脚本与回调注册，宜放在 `finally` 中。

类指针实参及 extern 结果仍是受信任的地址值；成员访问检查对象存储和边界，不验证名义类身份。脚本原始内存接口及 JNI 原始写入/释放不能访问对象存储。字段及 `delete` 应通过带类型的脚本包装函数操作；`address(p)` 不绕过限制，也不转移所有权。堆地址复用后的旧别名和自动对象逃逸仍是已知限制。

## 装配多文件库

```sh
./compile.sh examples/multifile/point.azs
./compile.sh examples/multifile/main.azs
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd
```

Java 对应流程是 `loadScript(mainFile)`、依次 `insertScript(libraryFile)`、`flush()`，随后 invoke。`namespaceForHint("POINT_LIB")` 返回实际 namespace；公开函数的完整 ID 是 `(namespace << 16) | localId`。相同假定 namespace 在不同模块可以引用不同库。缺库或签名错误使 flush 原子失败，补齐依赖可重试；onload 运行失败则必须关闭重建。执行或回调期间不能 insert、flush 或替换快照。

JNI v9 快照仅保存和恢复初始化完成的空闲脚本（含堆对象字段中的字面量对象）；必须有相同的有序模块字节、实际 namespace 与全局布局。恢复不重新执行 onload。旧执行文件和快照不再支持。语法、类外实现和生命周期细节见 [多文件库与 hint 链接](HINT_LINKING.md)。

## 已签名的外部动态库

`load_extern_library("xxx")` 在 macOS 加载 `xxx.dylib`，在 Windows 加载 `xxx.dll`，在其他系统加载 `xxx.so`，并要求同目录存在 `xxx.signature`。动态库在 `azscript_load_extern` 里调用与嵌入式 C++ 宿主相同的 `registerExecutor`。要用这个功能，C++ 宿主需要链接 `AzScript::RuntimeShared`；静态 `AzScript::Runtime` 与插件不是同一份注册表。

发行包根目录提供 `compile_extern_lib.sh` 和 `sign_extern_lib.sh`（Windows 为对应 `.cmd`）。签名工具是 `bin/azscript-sign-extern`。插件头文件是 `include/azscript/extern_library.hpp`。独立解释器从环境变量 `AZSCRIPT_TRUSTED_KEY` 以及当前目录、可执行文件目录、共享库目录中的 `trusted_key.pem` 读取公钥。嵌入式宿主调用 `add_trusted_public_key_pem` 或 `AbdInvoker.addTrustedPublicKey`，不会自动读取这些位置。细节见 [外部动态库](EXTERN_LIBRARY.md)。

## 包内容与进一步阅读

- `compile.sh` / `compile.cmd`：源码编译入口，依赖随包的 `compiler/lib/` JAR。
- `compile_extern_lib.sh` / `compile_extern_lib.cmd`：把插件源码编成当前平台的动态库。
- `sign_extern_lib.sh` / `sign_extern_lib.cmd`、`bin/azscript-sign-extern`：生成 P-256 密钥并对动态库签名。
- `run.sh` / `run.cmd`、`bin/azscript-run`：独立 ABD 执行入口。
- `include/`、`lib/`、`lib/cmake/AzScript/`：C++ 接口、原生库与 CMake 包。
- `java/abdJavaInvoker.jar`：JNI 高层及兼容低层 Java API。
- `runtime/`：默认包含的 Java 运行环境；无运行环境导出时省略。
- `examples/`：基础脚本、类脚本及 C++ / Java 嵌入示例。
- `stdlib/`：数学库与容器库的共享声明、实现源码、预编译 ABD、配套 AST / exec JSON 与 API 说明。

初次写脚本见 [基本语法](QUICKSTART.md)，完整语义见 [语言文档](LANGUAGE.md)，数学函数及精度边界见 [数学库说明](../stdlib/MATH.md)，链表、连续数组、集合和映射见 [容器库说明](../stdlib/CONTAINERS.md)。
