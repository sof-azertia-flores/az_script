# ABD Java 数据模型

本模块以 Java 17 编译，依赖 Gson 2.11.0。跨语言帧格式、标签和 C++
集成方式见 `../abdC/README.md`。Gradle 的 `check` 会运行不依赖 JUnit 的
`azertia.binary.AbdRegression`，亦可由根脚本离线编译和运行。

## 读取与类型

`AbdValue.fromAbd(bytes)` 读取单个完整帧并拒绝尾部垃圾。
`fromAbd(bytes, offset, length)` 读取限定区间内的首帧，适合拼接帧。
`getAsAss()` 逐帧检查边界，不再为每个条目复制整个剩余输入。
`AbdValue` 构造函数和 `getData()` 都复制字节以避免隐式外部修改。

`AcsObject` 保持插入顺序；对象和数组共享类型解码，未知标签和重复键
不会静默消失。浮点使用 little-endian，与 C++ 一致，保留负零及 NaN
payload。`AcsByteArray` / `put(key, byte[])` 使用新标签 `0xce200a`，
支持空数组和前导零；旧 `0xce2009` 仍按 BigInteger 读取，并在数值没有
变动时原样保留输入字节。已经被旧版 BigInteger 转换丢弃的字节无法恢复。

`AcsAddress` 使用独立的 `0xce200b` 标签和恰好 8 字节 little-endian
无符号值。`new AcsAddress(long)` 保留 Java long 的全部位；String 和
BigInteger 构造函数要求值在 0 到 `18446744073709551615` 范围内。
`rawBits()` 返回原始 long 位模式，`toUnsignedString()` 和 `toBigInteger()`
提供无符号读取；`AcsObject.getAsAddress(key)` 保留地址类型。JSON 视图使用
`{"address":"18446744073709551615"}`，避免与 int32 或普通字符串混淆。

## Java 反射结构

`AsStructIO` 是另一层 Java 对象映射，不等同于 `AcsObject`。
历史编码已保留：int little-endian，其他多字节 Java 原生类型 big-endian。
标注字段按父类到子类声明顺序排列，`@AsColum(order=N)` 指定从 1 开始
的精确位置，其他字段填空位；重复/越界序号明确报错。Map/List 接口字段
可读取为 `LinkedHashMap`/`ArrayList`；字段数量和标量长度严格检查。

旧结构格式没有 null 标记，null 与空字符串、空字节、空集合均曾写成
零长度。新版优先保留合法空字符串/字节/集合；可空数字与非空结构的
零长度仍读为 null。旧文件无法恢复这个区别。需要区分这些状态时，
显式增加一个 `hasValue` 字段；不要依赖含糊的旧编码。

原格式没有 Number 子类信息，声明为 `Number` 的字段只支持 Integer；
请为 long/float/double/BigInteger 声明具体字段类型。`AcsObject` 使用
显式标签，适合跨语言数值和需要精确类型的脚本数据。

`compatMode=true` 及 `AcsWrappedObject` 保留 Java 对象反射/Serializable
兼容能力，只应用于可信应用对象。它们可能实例化输入所声明的类，不能
把此兼容路径当成不可信脚本的沙箱；读取同时遵守 JVM 序列化过滤器并
限制深度/对象数量/数组大小。普通跨语言数据应使用基础 ACS 类型。

回归测试包含 15 字段跨语言样本、全部帧截断点、损坏长度/类型、循环与
超深嵌套、原始字节、结构字段顺序、BigInteger、接口集合、空字符串、
反射及显式 Serializable 兼容路径。测试 main 支持 `--write PATH` 和
`--read PATH`，读取后验证每个字段并要求重新编码的字节完全一致。
