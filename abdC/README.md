# ABD C++ 数据模型

本目录与 `../abdJava` 使用同一份 ABD 线格式。`library.h` 和
`azertianBinaryDataValues.h` 是解释器与 JNI 应共同包含的权威头文件；
请同时重新编译这些组件，不要链接旧对象文件或旧版拷贝头。

## 嵌入与安全读取

```cpp
#include "library.h"
#include "azertianBinaryDataValues.h"

auto payload = azertian::AbdValue::fromBytes(bytes, byteCount);
auto map = std::make_shared<azertian::AbdMap>(
    std::make_shared<azertian::AbdStack>(payload));
```

`fromBytes` 默认要求整个缓冲区恰好是一个帧；不合法输入抛出
`std::invalid_argument`。解析拼接流时可传第三参数 `false`，此时读取首帧，
下一个帧的位置为 `payload->size + 4`。接口在拷贝或分配前验证长度。

`AbdValue(data, size)` 的二参数构造函数始终接受**不含前缀的 payload**。
旧 `AbdValue(pointer)` 无法获知缓冲区真实大小，仅为源码兼容保留并标记
弃用；不要用它读取文件、JNI 缓冲区或网络数据。

`deepCopy()` 返回的裸指针归调用方所有，应立即交给 `std::unique_ptr` 或
`std::shared_ptr`。`AbdValue`、`ByteArrayValue` 的普通拷贝也拥有独立字节。
数组索引错误抛 `std::out_of_range`，`map.get` 对不存在的键返回 `nullptr`。
Map 的同名 `put` 更新原值。容器不接受空智能指针。

## 线格式

帧 = 4 字节非负 int32 little-endian payload 长度 + payload。
Stack payload 是拼接帧。Map 的条目是 name/type/value 三个帧；Array
的条目是 type/value 两个帧。所有 type 帧的 payload 恰为 4 字节。

| 类型 | 标签 | payload |
|---|---:|---|
| String | `1` | UTF-8 字节；保留内嵌 NUL 和空字符串 |
| Map | `2` | name/type/value 帧序列 |
| int32 | `3` | 4 字节 little-endian，保留负数 |
| Array | `0xad` | type/value 帧序列 |
| bool | `0x0d00` | `00` 或 `01` |
| float | `0xce867` | IEEE 754 binary32 little-endian |
| double | `0xce1066` | IEEE 754 binary64 little-endian |
| 历史 BigInteger/raw | `0xce2009` | Java BigInteger 为 big-endian 二进制补码；C++ 原样保留字节 |
| 新 raw bytes | `0xce200a` | 原样字节，可为空且可含前导零 |
| address | `0xce200b` | 恰好 8 字节 uint64 little-endian，0 为空地址 |

`address.h` 定义独立的 `azertian::address` 值类型，原始无符号值通过
`.value` 访问，与 `int32` 不发生隐式转换。`AddressAbdValue(address(...))`
编码该值；从 ABD 解码会严格检查 8 字节宽度，完整保留最高位和
`18446744073709551615`，不把地址解释为有符号整数。

旧标签和合法数据仍可读取。Java 原 `put(key, byte[])` 经 BigInteger
转换，会不可逆丢弃前导零，新版改用 `0xce200a`；旧程序不理解新标签，
所以发送这种类型时需要两端均升级。C++ `ByteArrayValue` 的默认标签仍是
`0xce2009`，第三参数传 `0xce200a` 可明确表示新字节类型。

Java 专用 wrapped object 标签 `0xface` 没有跨语言对象实例化语义，C++
明确拒绝。未知标签、重复键、缺失帧、截断标量、无效 bool、尾部垃圾和
超过 128 层的嵌套均被拒绝。过去对这些损坏数据的静默跳过不再保留。

## 验证

CMake 生成 `abd_regression`，CTest 执行它。测试覆盖标量/复合结构、
Map 更新、深拷贝、所有截断点、畸形类型/长度、循环结构，以及 10,000 个
定种子损坏输入。可通过根构建脚本启用 AddressSanitizer/UBSan。

`abd_regression --write PATH` 生成跨语言样本；`--read PATH` 验证其
内容和重新编码后的逐字节一致性。Java 的
`azertia.binary.AbdRegression` 提供同样参数。`tests/cpp-fixture.abd`
是包含数值和地址极值、NaN payload、负零、中文/NUL、二进制和嵌套结构的样本。
