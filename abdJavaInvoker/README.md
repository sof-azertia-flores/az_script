# Java 嵌入入口

中文 | [English](README.en.md)

需要 JDK 17+、CMake 3.20+ 和支持 C++20 的编译器。桥接 JAR 无第三方运行依赖，使用当前目录相邻的 `abdjni` 与 `interpreter`，避免把不同版本的头文件或旧二进制混用。

从仓库根目录构建：

```sh
cmake -S . -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
ctest --test-dir build/native --output-on-failure
cd abdJavaInvoker
./build.sh
```

macOS 自动通过 `java_home` 发现 JDK；也可以设置 `JAVA_HOME`。独立 `build.sh` 只需本地 JDK；原 Gradle 工程也可用 `./gradlew jar` 构建。JNI 回归由 CTest 调用真实 JVM，使用 `-Xcheck:jni`；测试所需 Java/Python 均在本地执行，不下载 Maven 包。

启动宿主时通过 `-Djava.library.path=/absolute/path/build/native/abdjni` 找到 `abdJ`，或者使用 `-Dazertia.native.library=/absolute/path/libabdJ.dylib` 明确指定文件。Windows 对应 `abdJ.dll`，Linux 对应 `libabdJ.so`；关联的 `abdInvoker` 动态库也必须能被系统加载。

```java
int hostAdd = 0x12340001;
AbdInvoker.registerJfunction(hostAdd,
    values -> (Integer) values[0] + (Integer) values[1]);
try {
    AbdInvoker.loadScript(new File("program.exec.abd"));
    // 可在这里调用 insertScript(libraryFile)。
    AbdInvoker.flush();
    Object result = AbdInvoker.invoke(hostAdd, 20, 22); // Integer 42
    AbdInvoker.saveStatus(new File("state.abd"));
    AbdInvoker.loadStatus(new File("state.abd"));
} finally {
    AbdInvoker.close();
}
```

脚本必须用相同 ID 声明完整签名，例如：

```c
extern int host_add(int, int):0x12340001;
int main() { return host_add(20, 22); }
```

签名中的类型与 Java 对象一一对应：`int`→`Integer`、`float`→`Float`、`double`→`Double`、`boolean`→`Boolean`、`string`→`String`、`address` 和类指针→`azertia.Address`、`void`→`null`。字面量对象（类型编号 8）不跨越 Java 边界：返回它的函数被 Java 调用时报错（该值先被析构），也不能作为参数传入或读取，请改用指针。调用不会在数值类型之间自动转换。编译器检查可静态确定的参数；native 运行时在进入回调前检查实际参数，并在回调返回后检查实际结果。数量或类型不一致会作为 `IllegalStateException` 返回 Java，回调不会收到不符合签名的参数。

Java 桥可以编解码 `Integer`、`Float`、`Double`、`Boolean`、`String`、`Address` 和 `null`；不隐式截断 `Long` 等不支持的对象。带签名的外部函数不允许 `void` 参数，因此 Java `null` 不能作为它的实参；在带签名调用中，只有声明 `void` 返回的回调可以返回 `null`。无类型脚本参数仍可保存这个空值。字符串统一 UTF-8，支持中文、补充平面字符、空串和内嵌 NUL。函数调用不需要手动分配或释放堆槽。脚本函数 `0` 为加载入口，`1` 为卸载入口；`0x0abd` 命名空间保留给内置函数。`registerJfunction` 不能使用命名空间 `0`、`0xfff` 或 `0xabd`（`IllegalArgumentException`），脚本 `#namespace` 所在命名空间中不存在的 ID 会作为未知函数报错，不会转到 Java 回调。

`AbdInvoker.addTrustedPublicKey(String)` 和 `addTrustedPublicKey(File)` 把 PEM 公钥交给 `load_extern_library`。`clearTrustedPublicKeys()` 清空这组公钥。Java 宿主不会自动读取 `AZSCRIPT_TRUSTED_KEY` 或 `trusted_key.pem`。动态库注册的是同一进程里的 C++ `registerExecutor`，与 `registerJfunction` 共用命名空间规则。

普通函数可用显式 `:0003` 编号，否则 ID 会随源码变化。AST 的 `abstract` 保存 extern 优先的源码名称绑定，同名 extern 与本地定义共存时，它指向 extern；本地定义位置应读取 `body` 中的 namespace 和 `metadata.position/name`。hint 库的定义 namespace 是0000占位，宿主通过 `namespaceForHint` 查询该库实际 namespace，再与其公开低16位编号组合。不能把本模块 namespace 套到所有 abstract 项；跨库导入须使用其对应库的位置。AST 与 ABD 必须成对部署。

泛型脚本函数只保留一份擦除后的实现，由编译后的脚本调用传入隐藏操作上下文。`AbdInvoker.invoke` 无法提供这些上下文，直接调用泛型入口会报错。应导出参数和返回类型明确的普通非泛型包装函数，再从 Java 调用其 ID：

```c
<T> T identity(T value) { return value; }
int integer_identity(int value):0003 { return identity<int>(value); }
```

包装函数继续使用现有 Java 值映射。泛型 extern 必须链接到脚本实现，不能交给 Java 回调实现。脚本反射也只接受隐藏上下文为零的普通入口，因此同样通过包装函数调用，不能直接调用构造、析构、工厂或泛型入口。

原有 `JfuncExecutor` 的 `void run(Object[])` 继续支持，在脚本中返回 `null`。新的 `registerJfunction(int, Function<Object[],Object>)` 可以返回任一种支持的值。回调抛出的 Java 异常传回最初调用方；未知回调、类型错误、文件错误和非法地址均转换为 Java 异常，C++ 异常不会越过 JNI。

每个进程使用一个共享脚本/堆。`AbdInvoker` 串行处理多线程调用，回调可在同一线程再次调用 `invoke`。回调不得等待另一个将调用该运行时的线程，否则会等待自己持有的运行时锁。纯宿主回调重入也有深度上限。多个相互独立的脚本实例尚未实现。

`destroyScript()` 可重复调用，会释放脚本拥有的状态，保留调用者单独分配的宿主内存；保留回调注册以便重新加载。`close()` 还会释放回调的 JVM 全局引用；应在宿主退出或类加载器卸载前调用，即使卸载函数抛错也会清理。`unregisterJfunction(id)` 移除单个注册。脚本运行或回调期间不能销毁、insert、flush 或恢复快照；`saveStatus` 在回调中返回 `false`。`loadScript` 和 `insertScript(File)` 只装配，之后必须显式 `flush()`；通过 `namespaceForHint(String)` 查询库的实际 namespace。初始化回调可以 invoke 已链接函数。链接检查失败可补库重试；onload 失败则脚本进入故障态，只能关闭后重新加载。

类对象和 `address` 使用独立的不可变 `azertia.Address` 传递，不能用 `Integer` 或 `Long` 代替。`Address.of(long bits)` 保留全部无符号 64 位地址位模式，`bits()` 返回原始位，`toString()` 使用无符号十进制；例如 `Address.of(-1L)` 表示 `18446744073709551615`。`Address.NULL` 是地址零，与表示 `void` 的 Java `null` 不同。高位地址可以作为地址值传递和保存；只有实际活动分配内的地址能够读写内存。脚本返回给宿主的自动对象由脚本全局作用域接管，在显式关闭脚本时析构；返回的 `new` 对象仍需在关闭前调用脚本中的 `delete`。成员引用不拥有其他对象，原始 `memFree` 只释放内存、不调用用户析构。

`saveStatus` 写入 v9 快照，按运行时槽顺序将全局变量保存为数组，与 exec v9 的数字变量编号对应。快照同时保存每个堆槽（含字段中的字面量对象及其地址）、分配区间、对象地址、析构函数、创建方式、自动回收顺序及已绑定的析构操作上下文（递归保存默认工厂引用）；恢复时字面量对象获得新地址，指向它们的地址随之改写。`globalMemories2Str()` 显示 `-1`、`-2` 等全局槽编号。地址值使用独立标量标签 `0xce200b` 和固定 8 字节小端负载；全局值、堆槽、分配起点、对象起点和所有权列表均保留地址的全部 64 位。槽数量、长度、变量编号和函数 ID 仍为 32 位整数。旧 JAR、JNI 库和执行文件不能混用。

`loadStatus` 检查有序模块原始字节、实际 namespace、hint、全局布局、全局数组数量及编码值、对象登记、分配起点、析构签名和隐藏数量、上下文 ABI/kind/深度及默认工厂的签名和隐藏数量，全部通过后原子替换状态；损坏文件不会留下部分恢复的全局变量或堆，替换本身不执行旧对象析构。上下文保存为数据，不保存原生指针或已退出调用帧。只接受 v9 快照，旧版本和无版本文件会被拒绝。

保存先写同目录暂存文件，再通过重命名替换目标，写入失败保留原文件。脚本与快照上限均为 64 MiB。快照仅能保存和恢复已初始化的空闲状态；超限保存明确失败。恢复不重新链接或执行初始化。onload 失败时保留原错误并清理当前作用域；关闭会清理剩余脚本对象，保留宿主独立分配的内存。

`azertia.jni.Caller*` 是低层 API。内存地址参数、`memAlloc`/`call` 的地址返回值以及回调槽地址数组现在分别是 Java `long`、`long` 和 `long[]`；它们承载无符号地址的原始位模式。`getMemAddress(long pointer)` 和 `ACputMemAddress(long pointer, long bits)` 专门读取和写入地址值，`getMemInt`/整数 `ACputMem` 仍只处理整数。调用方需先初始化、管理自身分配、确保低层多步操作不与其他线程的调用/销毁/恢复交错，并在恢复后重新核对持有的指针。地址 `0` 表示空结果，不能读写；`memAlloc(0)` 返回 `0`，`memFree(0)` 无操作。`memFree` 接受无自动回收所有者或由脚本全局作用域拥有的活动分配起点；其他正在运行的脚本作用域拥有的块会以 `IllegalArgumentException` 拒绝。它是原始释放接口，不调用对象析构。低层回调参数是临时借用，调用方不得主动释放。新集成优先使用 `AbdInvoker`。

单独执行 JNI 回归：

```sh
python3 tests/run_jni_tests.py \
  --library ../build/native/abdjni/libabdJ.dylib \
  --work-dir ../build/jni-tests
```

ABD 字节码与快照是数据编码，没有提供加密或不可反编译保证。若脚本含敏感逻辑，需要在宿主的分发、密钥及信任边界中另行设计保护。

拥有型 `buffer<T>` 保持脚本 OBJECT_VALUE，不能直接跨 JNI 传递；应提供接收受支持标量或 Address 的普通包装函数。buffer 快照仅编码一次存活元素，并保存元素上下文、直接 slot 数、原地构造绑定、容量/长度和视图 ID。恢复重建连续存储、改写已保存的地址，不执行用户析构；元素旧地址在扩容、删除或整体替换后也会失效。原始块调整接口不能修改 buffer 存储或元素视图。一次快照累计 buffer 容量乘步长不得超过 1,048,576 slots；该持久化预算在分配存储前检查，保存超限状态明确失败。
