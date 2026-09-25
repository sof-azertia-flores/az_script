# AzScript 修复版

这一版以原来的代码树/ABD 指令格式为基础，**重新实现编译器的词法分析、递归下降语法分析、符号解析和代码树生成**，并修复了配套数据模型、C++ 解释器和 JNI。原工作区的六个目录保持原样；这里的文件是独立修复版。

本目录使用 Git 统一管理各模块，默认分支为 `main`。跟踪范围、分支与提交流程、发行标签和远程备份说明见 [版本管理文档](docs/VERSION_CONTROL.md)。

## 构建与运行

需要 JDK 17 或更新版本、CMake 3.20 或更新版本、C++20 编译器、Python 3.9 或更新版本。

在本目录执行：

```sh
python3 tools/build_and_test.py
./azscript compile compiler/examples/parser-regressions.azs \
  -o build/demo.exec.abd --ast build/demo.ast.json --exec-json build/demo.exec.json
./build/native/interpreter/azscript-run build/demo.exec.abd
```

测试脚本最终返回 `146`，随后执行销毁钩子输出 `done`。`--ast` 保存可读代码树，`--exec-json` 保存降级后的指令树，方便核对拆解结果；二者是可选的调试输出。

构建会复用现有 Gradle/Maven 缓存，缺少依赖时从 Maven Central 下载并校验固定 SHA-256。所有构建产物、下载和临时测试文件都放在本目录的 `build/` 内。已有缓存时可完全离线构建：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

`--sanitize` 对原生数据层、解释器和编译执行链启用 AddressSanitizer/UndefinedBehaviorSanitizer；JNI 使用普通构建配合 JVM 的 `-Xcheck:jni` 验证。只构建可使用 `--skip-tests`；不需要 JNI 可使用 `--skip-jni`。

三个 Java 模块也配置为根 Gradle 多模块工程，可以执行 `gradle build`；完整跨语言验证以以上 Python 入口为准。原始模块的旧 Gradle wrapper 保留作历史文件，建议使用根工程或统一入口。

## 导出完整发行包

将所有可交付产物导出到指定目录：

```sh
python3 tools/export_distribution.py /path/to/azscript --offline
cd /path/to/azscript
./compile.sh examples/hello.azs
./run.sh examples/hello.exec.abd
```

`compile.sh xxx.azs` 默认在源码旁同时生成 `xxx.exec.abd`、`xxx.ast.json` 和 `xxx.exec.json`。指定 `-o out/demo.abd` 或 `-o out/demo.exec.abd` 后，两份 JSON 默认变为 `out/demo.ast.json`、`out/demo.exec.json`，也能通过 `--ast` / `--exec-json` 单独指定。Windows 使用 `compile.cmd` 和 `run.cmd`。

发行包包含完整编译器及依赖、由 `jlink` 生成的 Java 运行环境、默认调用 `main`（`0x0fff0000`）的独立解释器、C++ 静态/动态库与公开头文件、可迁移的 `find_package(AzScript CONFIG REQUIRED)` 配置、JNI JAR 和本系统的 `.dylib` / `.so` / `.dll`，以及语法文档、使用文档、数学库和 C++ / Java 接入示例。解释器静态链接 AzScript 运行库，运行 ABD 无需 Java；C++ 宿主可链接 `AzScript::Runtime` 或 `AzScript::RuntimeShared`。

导出使用独立 Release 构建和临时安装目录，实际搬迁目录后验证编译、两个 JSON、独立执行、C++ SDK 和 JNI，再发布到目标目录。`manifest.json` 保存平台、工具链、Java 版本、验收结果及逐文件 SHA-256。失败时保留上一次发行包。非空目录默认拒绝覆盖；`--force` 仅允许替换带有效发行包标识的已有导出。`--system-java` 可省略内置运行环境，此时使用者需自行提供 Java 17+；离线缓存未准备好时去掉 `--offline` 下载经过校验的 Gson。

产物只针对当前系统、架构及兼容的原生 ABI；需在各目标平台分别导出。可通过 `--cmake-arg=-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0` 等参数调整原生构建配置，实际兼容性还受使用的 JDK 与工具链约束。详细说明与发行包模板位于 [tools/distribution/USAGE.md](tools/distribution/USAGE.md)，基本语法见 [tools/distribution/QUICKSTART.md](tools/distribution/QUICKSTART.md)。

## 编译器现在怎样工作

1. 预处理相对于当前源文件的 `#include`、宏和条件指令。
2. 词法分析将标识符、数值、字符串、运算符和注释分离，字符串内部的逗号、括号、分号不会被当成语法分隔符。
3. 递归下降解析明确处理优先级、左右结合性、嵌套调用、代码块和控制流。
4. 校验变量和函数、作用域、函数签名、参数类型与数量、重复定义和返回形式，再生成 JSON/ABD 指令树。

完整语法见 [compiler/LANGUAGE.md](compiler/LANGUAGE.md)。保留了原来的 `def(x)`、`return(x)`、`while(condition,{...})`、`if(condition,{...})` 和 `mem_get(pointer)=value` 写法；支持普通 `if/else/while`、变量初始化、一元运算、比较、短路逻辑和变量复合赋值。

```c
#namespace 123
extern int host_add(int, int):0xccf0001;

int factorial(n) {
    if (n <= 1) { return 1; }
    return n * factorial(n - 1);
}

int main() {
    def(answer, factorial(5));
    print("answer=" + answer);
    return answer;
}
```

内置函数 `print`、`getDepth`、`mem_free`、`alloc`、`make_free`、`mem_send_up` 和 `mem_get` 由编译器直接提供，不需要 include 或声明。include 仍按源文件所在目录解析。

`address` 是独立的无符号 64 位地址类型，表示 slot 位置，空地址写作 `null`。例如 `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`。地址支持与 int32 偏移加减并检查越界；对象引用也使用该运行时类型，类成员的静态类型检查和继承规则保持不变。地址值、变量槽编号、分配数量和函数 ID 是不同概念，后面三者仍使用既有 32 位表示。

支持 `class`、成员变量、构造方法、成员方法和 `~ClassName()` 析构。对象都是引用：`Point p(3);` 创建离块自动析构的对象，`Point p = new Point(3);` 创建需要 `delete p;` 的对象。返回局部自动对象会将其所有权交给调用代码块；成员对象引用不递归转移或释放。每个字段占一个 slot，方法均编译成带隐式对象地址参数的普通函数，解释器只扩展生命周期、清理和地址校验。完整语义见 [类、对象引用与析构](compiler/LANGUAGE.md#类对象引用与析构)，[类回归示例](compiler/examples/classes-regressions.azs) 成功返回 `0`。

`for (int i=0; i<10; i++)` 在编译层降为块和 `while`；`continue` 清理当前轮局部对象后进入下一轮，在 `for` 中会先执行步进。`class Child : Base` 支持公开单继承：父类字段占前面的 slots，子类字段追加，父类方法直接接收同一个对象地址。方法按静态类型绑定，构造从父到子，析构从子到父；子类构造失败会清理已完成的父类部分。示例见 [循环](compiler/examples/loops-regressions.azs) 和 [继承](compiler/examples/inheritance-regressions.azs)。

已有可读代码树也可以直接编译：

```sh
./azscript compile-json build/demo.ast.json -o build/from-json.exec.abd
```

## 脚本数学库

[`compiler/stdlib/math.azs`](compiler/stdlib/math.azs) 是使用 `AZSCRIPT_MATH` hint 的独立脚本库，包含常量、绝对值与区间、整数除法和模、gcd/lcm、阶乘与 Fibonacci、整数幂、平方根、取整、角度换算、三角函数、指数/对数及近似比较。它不依赖 JNI 或宿主注册函数；调用方只包含类型明确的共享声明：

```c
#include "../stdlib/math.include.azs"

int main() {
    return math_round(math_hypot(3.0, 4.0)); // 5
}
```

分别编译库与调用方后装配执行：

```sh
./azscript compile compiler/stdlib/math.azs -o build/math.exec.abd
./azscript compile compiler/examples/math-regressions.azs -o build/math-regressions.exec.abd
./build/native/interpreter/azscript-run build/math-regressions.exec.abd --insert build/math.exec.abd
```

发行包已附带 `stdlib/math.exec.abd` 和配套两份 JSON。浮点 API 要求 `double` 实参，整数 API 要求 `int`；如需把整数表达式传给浮点 API，可使用 `value * 1.0`。宿主装配库后须显式 `flush()`，通过 `namespace_for_hint("AZSCRIPT_MATH")` 或 JNI 的 `namespaceForHint("AZSCRIPT_MATH")` 查询实际地址。

完整 API、定义域、错误和精度约定见 [`compiler/stdlib/MATH.md`](compiler/stdlib/MATH.md)。可执行回归样例是 [`compiler/examples/math-regressions.azs`](compiler/examples/math-regressions.azs)，成功时返回 `0`。超越函数是脚本级迭代近似；需要系统数学库的全范围精度时，仍应通过 `extern` 接入宿主实现。

## 主要修复

| 层 | 修复内容 |
| --- | --- |
| 编译器 | 正确拆分嵌套代码、引号与转义、注释、优先级和结合性；函数前向调用、递归、词法作用域；未知符号和参数错误有诊断 |
| ABD 数据模型 | 修复类型解析穿透、Map 更新无效、浮点转换、深拷贝、错误释放、长度和下标检查；Java byte[] 不再丢前导零或空数组 |
| C++ 运行时 | 修复循环内 return、调用者局部变量泄露、参数求值时机、混合数值运算、常量被改写、异常路径的内存清理 |
| 堆内存 | 独立 uint64 地址类型，0 为保留空地址；分配长度、活跃范围和释放检查；复用地址清空；块/函数退出自动释放，显式提升所有权仍可使用 |
| JNI | 正确的 UTF-8、空串、NUL 和 emoji；JavaVM/全局引用管理；每个 JNI 入口捕获 C++ 异常；参数、返回和回调临时内存可靠释放 |
| 宿主接口 | 带返回值的 Java 回调、同线程重入、串行并发调用、关闭/重载；快照校验完成后才替换状态，写入采用临时文件提交 |
| 构建 | 消除缺失子模块路径、重复且不一致的 C++ 类型声明、硬编码 JDK 路径和远程私有 ABD 依赖 |

## 嵌入 C++

在宿主 CMake 中添加本目录并链接静态目标：

```cmake
set(AZSCRIPT_BUILD_JNI OFF CACHE BOOL "" FORCE)
add_subdirectory(path/to/azscript)
target_link_libraries(your_app PRIVATE abdInvokero)
```

```cpp
#include <azscript/runtime.hpp>

auto script = azertian::load_script(bytes.data(), bytes.size());
script->max_steps = 200000;
script->max_call_depth = 128;
script->flush();
auto result = script->invoke(0x0fff0000); // main
script->destroy();
```

完整示例在 [examples/embedded.cpp](examples/embedded.cpp)，构建目标 `azscript-embed`。外部数据应始终调用带长度的入口。`load_script` 只装载；设置预算、登记宿主及插入库后调用 `flush()`，该过程链接函数并初始化模块。onload 期间宿主回调可以调用已链接函数。

`script->insert_script(bytes.data(), bytes.size())` 可追加模块，成功后须再次 flush。新 exec 将全局变量编码为 `-1、-2…`，参数和局部变量编码为连续非负槽号；全局声明仅保存数量。每个加载的函数记录自己的 `global_offset`，负数引用按 `global_offset + (-v - 1)` 定位，因此多个独立编译的模块可以拥有同名、同编号全局变量。局部帧按调用独立分配，块级析构不变。固定 namespace 的函数 ID 须互不冲突，hint 库由运行时分配实际 namespace；详细格式见 [执行格式与 insert 偏移](compiler/LANGUAGE.md#insert-与模块全局偏移)。

执行失败抛出标准 C++ 异常，调用者可以捕获；`destroy()` 幂等，执行销毁钩子后释放全局状态。析构时释放拥有的内存，但不自动运行用户钩子。

## 嵌入 Java

使用 `build/java/abdJavaInvoker.jar`，同时部署 `libabdJ`、`libabdInvoker`（macOS 后缀 `.dylib`，Linux 为 `.so`）。也可以按 `abdJavaInvoker/README.md` 选择加载路径。

```java
System.setProperty("azertia.native.library", "/absolute/path/libabdJ.dylib");
AbdInvoker.loadScript(new File("demo.exec.abd"));
try {
    AbdInvoker.flush();
    Object result = AbdInvoker.invoke(0x0fff0000);
} finally {
    AbdInvoker.close();
}
```

非库程序的 `main` 固定为 `0x0fff0000`；普通定义可通过 `:0003` 固定低16位编号。独立库用 `#namespace_hint`，调用方用 `#assume_hint` 和 `extern` 声明。宿主先加载全部模块，再显式 `flush()` 链接与初始化；成功 insert 后须再次 flush。完整示例与类外实现见 [多文件库文档](docs/HINT_LINKING.md)。

宿主函数注册、带返回值的回调和快照示例见 [abdJavaInvoker/README.md](abdJavaInvoker/README.md)。

## 明确的语义与边界

- 整数是有符号 32 位；整数除法向零截断，溢出和除零报错。数值提升按 `double > float > int`；布尔值不隐式参与数值运算。
- 小数默认是 double；`1.5f`、`5f`、`2e3f` 是 float 字面量。
- `extern int host_add(int, int):0xccf0001;` 以完整签名绑定宿主函数；类型必填，旧 `#extern` 指令已删除。参数和回调返回值按实际类型精确匹配，`int`、`float`、`double` 之间不隐式转换。`#namespace 1234` 支持1～4位；独立库用 `#namespace_hint` 声明，`#assume_hint` 绑定调用。
- 函数按值传参，参数从左到右求值；函数只能访问自己的局部变量和全局变量。块有独立作用域，循环每次创建新的块作用域。`for` 初始化变量的作用域包含整个循环，循环结束后不可见。
- `return` 穿透当前函数内的循环和条件块；`break` 退出最内层循环，`continue` 结束最内层循环的本轮执行，均执行必要的块清理。返回值按声明类型检查；有限且可表示的数值转换允许，无法表示的转换报错。
- 编译器按 ABD 的 128 层容器嵌套限制检查函数体：同时检查逻辑表达式和实际 wire 容器深度，函数体从第 4 层开始；调用实参列表、块内语句列表和常量封装各另占一层，超限时报告函数名和行列（大约 120 个串联运算符或 60 层嵌套块）。语法递归和 JSON 容器另有 320 层防护上限，循环代码树会报错。默认每次顶层脚本调用最多执行 1,000,000 步、嵌套 256 层；脚本文件最多 64 MiB，堆最多 1,048,576 个槽（含保留槽 0）。宿主回调本身的执行时间不受指令预算控制。
- `return`、`if`、`else`、`while`、`for`、`break`、`continue`、`def`、`var`、`class`、`new`、`this`、`null`、`delete`、`extern`、`public/private/protected/virtual`、类型名和 `true/false` 是保留字，不能作为函数、参数、变量、全局变量或宏的名字。非 void 函数的正常退出路径都必须 `return`；条件恒真的 while/for 且体内没有属于它的 `break` 时，视为不会正常结束。
- 数值转字符串（`print` 和 `+` 拼接）使用最短可往返表示：`0.1+0.2` 得到 `0.30000000000000004`，`123456789.0` 得到 `123456789`，`1e20` 得到 `1e+20`，负零保留为 `-0`。
- `address` 与 int32 不隐式转换，`alloc(int)` 返回 address，内存接口接收 address。支持 `address + int`、`int + address`、`address - int`，以及地址间的无符号相等和大小比较；超出 uint64 范围报错。类引用不参与原始地址算术。堆地址仍是进程内逻辑下标，64 位表示不扩大当前堆上限；越界和已经释放的地址会拒绝，地址复用后无法识别旧引用，仍须避免悬空引用。
- 由作用域自动回收的分配只能从拥有它的作用域链（词法父级和调用者）释放：`mem_free` 从其他活动作用域（例如宿主回调重入的脚本函数）释放它会报错，Java `memFree` 只允许无自动回收所有者或已提升到全局的块。`make_free` 之后的分配不再受此限制。
- 命名空间 `0`、`0xfff` 和 `0xabd` 是保留空间，宿主注册也不能占用活动脚本 namespace。hint 库自动分配可用位置，函数 ID 按完整32位位模式处理。
- 当前堆和外部函数注册表仍是进程共享资源。Java 提供一个活动脚本并串行执行调用；支持同线程回调重入。回调不应等待另一个会再次调用同一运行时的线程。C++ 使用多个脚本时也须管理共享堆和注册表，不能视作独立安全沙箱。
- 不实现集合字面量、闭包或完整静态类型系统。类型注解的具体作用和 include 后诊断行号的限制见语法文档。
- **ABD 是可还原的二进制指令树，并不是加密，也不能保证防反编译。** 变量使用数字槽号，源码名称留在 AST 中；发布时可省略源码和调试 JSON，但核心秘密仍不应只依赖脚本格式隐藏。

## 数据兼容

ABD 地址标量新增标签 `0xce200b`，payload 恰为 8 字节 uint64 小端；C++ 使用 `azertian::address`，Java ABD 使用 `AcsAddress`，JNI 使用独立 `azertia.Address`，都与整数区分。原有数据标量和容器标签继续可读；原始字节标签 `0xce200a` 修复 Java byte[] 经过 BigInteger 时丢失前导零的问题。旧 C++ 库不能读取这个新标签，应一起升级。旧数据已经丢失的前导零、以及反射结构中历史上未区分的 null/空值，不能从旧字节中恢复。详见 [abdC/README.md](abdC/README.md) 和 [abdJava/README.md](abdJava/README.md)。

新增运算指令、else、变量初始化、参数类型及外部函数签名字段需要配套新版解释器，建议源码重新编译。历史编译器若已把表达式拆错，新运行时无法从错误指令树还原原意。

对象引用、隐式 `this` 和底层内存指针统一使用 address（运行时类型编号 7），普通整数仍为 int32。已有 `oa/ob/od/ro` 对象指令保持生命周期语义。新 exec v6 使用数字变量与数字 opcode，固定结构直接使用裸 `AbdStack`，仅动态常量和扩展元数据保留带类型的容器。exec JSON 是带字段名的检查视图；完整布局见 [Exec v6 格式](docs/EXEC_FORMAT.md)。解释器只接受 exec v6；包括 v5 在内的旧执行文件均须从源码或可读 AST 重新编译，旧源码中声明为 int 的内存指针须改为 address。JNI 快照只接受 v6，使用独立地址值保存堆分配、对象登记和自动清理顺序，同时保留全局槽及模块身份；旧快照不再受支持。

## 验证

统一入口覆盖编译器与归档回归、Java ABD 回归、C++ 数据模型和运行时、双向逐字节格式比较、源码→JSON→ABD→C++ 的真实执行、以及同一编译结果经 JNI 在 JVM 内运行。JNI 单独开启 `-Xcheck:jni` 检查，包含四线程调用、回调重入/异常、所有标量类型和快照恢复。

`original-sha256.json` 记录原始六个目录的文件校验值，可用于确认原文件没有被修复过程改写。具体本次验证结果见 [VALIDATION.md](VALIDATION.md)。
