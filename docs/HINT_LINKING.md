# 多文件库与 hint 链接

中文 | [English](HINT_LINKING.en.md)

本版本通过独立编译和显式链接装配多文件程序。编译器保留可读 AST；执行文件中的类名、字段名和类布局均已擦除，解释器仅处理数字函数 ID、slots 和析构地址。二进制契约见 [Exec v8](EXEC_FORMAT.md)。

## 声明与函数编号

一个编译单元及其有效 include 内容只能出现一次 `#namespace` 或 `#namespace_hint`。namespace 支持 1～4 位十六进制；hint 名称为大小写敏感的 ASCII 标识符（字母或下划线开头，后接字母、数字或下划线）。`#assume_hint NAME abcd` 建立编译期地址别名，相同声明合并，同名不同地址或同地址不同名称拒绝。别名排除 0000、0abd、0fff 和本模块固定函数 namespace。

`extern int a(int n):addd0003;` 声明完整32位函数地址，参数名可省略，返回类型及参数类型必须明确。旧 `#extern` 已删除，头文件保护使用 `#define`。`int a():0003 { ... }` 给定义指定低16位编号。编号支持可选 `0x` 前缀；定义最多四位、extern最多八位。全部显式编号及自身导入声明占用的编号先保留，再分配普通自动编号和内部函数编号。

hint 库的普通函数先放在 0000 namespace，0000/0001 始终保留给 `__script_onload` / `__script_pre_destroy`，自动普通函数从0002开始。库中 main 不再特殊。非hint程序的 main 仍为 0x0fff0000，生命周期函数仍为0/1；这些固定入口不能用后缀改变编号。

每个hint库自动assume自身，显式自身assume优先，否则按namespace从小到大选一个可用别名，避开保留、固定函数、显式assume和固定extern所占namespace。本库内部调用（含递归及内部工厂调用）通过自身assume输出。调用查找优先extern；同名extern和本地定义签名一致时合法，定义位置与调用绑定独立。指向自身hint的声明与实现必须使用相同低位编号，定义省略编号时从声明取得。完全重复的extern声明可合并，编号或签名冲突则拒绝。

## 类外实现与结构类型

hint库内的class只能写字段和extern成员声明，显式方法体写在类外。非hint程序仍可使用类内方法体，也可使用类外实现。构造、析构不写返回类型，语法分别是 `extern C(int n):addd0002;` / `C::C(int n):0002 { ... }` 和 `extern ~C():addd0003;` / `C::~C():0003 { ... }`。普通成员为 `extern int get():addd0004;` / `int C::get():0004 { ... }`。类外实现必须对应已声明成员，签名及其自身库中的编号一致。

调用方和实现库include相同类声明。创建方为每个类生成三个内部工厂（自动指针、手动指针、字面量对象）：分配准确slots（字面量对象分配自身存储）、调用构造、登记可选析构地址、移交对象。全部字段默认值、字面量字段的构造及按序初始化表达式放到实际构造定义的编译前缀，使用this、成员及实现模块全局，不受显式构造参数遮蔽。声明消费方不解析这些初始化表达式。前缀不产生额外清理块，临时对象至少活到所在语句结束。构造失败不登记对象，也不调用其析构，已构造的字面量字段会被销毁。有字面量字段却没有声明析构的类，由每个创建对象的模块各自生成只销毁这些字段的析构。

未声明任何构造时合成本地无参构造；声明任意构造（包括extern）后不合成替代实现或额外无参构造。指针字段是非拥有引用，自动指针对象的回收责任仍绑定创建地址；字面量字段内嵌完整对象，随外层对象销毁。

非泛型类在编译期按字段类型及顺序比较类结构，忽略类名、字段名、方法和初始化表达式；字面量对象与指针分别比较。指针字段递归比较所指结构，已比较的类型对用于终止自引用和循环比较。结构等价适用于赋值、传参、返回和声明/实现签名；int、原始 address、字面量对象和类指针是不同的编译期类型；address 字段只与 address 字段等价，不与 int 或类指针字段等价。运行时类指针、`C (*)` 参数和隐式 this 擦除为 address（类型编号 7），字面量对象的参数与返回使用对象值（类型编号 8）；运行时不验证跨模块类结构，共享声明是模块间的约定。地址值为 uint64，字段仍各占一个 slot，继承保留父类字段前缀；函数 ID、namespace 和字段偏移不变宽。extern 的 address 签名与 int 不匹配，全部调用方、库和宿主需配套更新。

## 泛型共享声明与类外实现

泛型类和函数仍然独立编译，每个定义只生成一份函数正文。共享头文件保留类型参数、约束和完整签名，声明与实现按参数位置匹配泛型参数。泛型类保持不变性：`Box<int>` 和 `Box<string>` 不是可互换的类型，泛型类应用也不参与不同声明之间的结构等价。泛型函数声明在 `extern` 后写类型参数：

```cpp
#assume_hint GENERIC_BOX_LIB beef
class Box<T> {
    T value;
    extern Box():beef0002;
    extern void set(T next):beef0003;
    extern T get():beef0004;
    extern ~Box():beef0005;
}
extern <T> T echo(T value):beef0006;
```

实现文件使用 `#namespace_hint GENERIC_BOX_LIB` 并 include 同一头文件。类外实现中的类名写完整类型参数，普通函数仍可使用自己的类型参数：

```cpp
Box<T>::Box():0002 {}
void Box<T>::set(T next):0003 { value = next; }
T Box<T>::get():0004 { return value; }
Box<T>::~Box():0005 { print("box destroyed"); }
<T> T echo(T value):0006 { return value; }
```

使用方可直接声明 `Box<int> number;` 或 `Box<string>* text = new Box<string>();`，也可调用 `echo(42)` 或显式写 `echo<int>(42)`。`T` 字段保留默认初始化：基础类型为零、false 或空字符串，address 和类指针为 null，字面量对象执行其无参构造。带显式初始化的字段以及仅复制、传参和返回已有对象的代码不要求该类型有无参构造。局部 `T` 变量必须显式初始化；`T()` 和 `new T()` 不支持。

执行文件不保存泛型类型名或类布局。共享实现通过独立的隐藏上下文取得实际值类型和默认构造操作；类方法的第一个普通参数仍是 this，额外上下文不占参数或局部 slots。直接使用 `T` 的参数及返回擦除为 any；已知类指针和字面量对象仍分别擦除为 address 和 object。链接检查隐藏上下文数量、入口种类及擦除签名，普通宿主调用不能手工省略泛型上下文，需通过具体类型的普通包装函数接入。

完成对象构造时同时捕获析构需要的上下文；对象复制、移动、跨模块返回和快照恢复均保留它。默认工厂及析构引用与 CALL 一起按所属模块 assume 重定位。调用方与库必须使用同一版共享声明并全部重新编译；运行时不会补做类布局或泛型名义类型检查。

## 从 AZS 查询 hint 并按 ID 调用

`reflect_hint_loaded("NAME")` 判断当前脚本是否已经挂载这个大小写敏感的 hint；缺失或空字符串返回 false。`reflect_get_hint_namespace("NAME")` 返回其实际的 16 位 namespace 数字，缺失或空字符串报运行错误。挂载不表示 onload 已经执行；初始化期间，已挂载但尚未初始化的模块也会返回 true。普通 AZS 执行仍须先成功 flush。

`reflect_invoke_function<R>(id, args...)` 接受运行时 int32 函数 ID，包括完整高位和负数位模式。ID 和实参从左到右各求值一次，返回结果按 R 的实际运行时类型检查；类结构和具体泛型实参仍由调用方保证。反射不会把普通整数中的 assume 地址重定位，也不会根据 hint 字符串建立初始化依赖。需要先初始化某库时仍使用 `#assume_hint`；包含该指令的模块不能以存在性查询绕过缺库链接错误。

泛型函数正文可以使用 `reflect_invoke_function<T>(...)`，预期返回类型来自当前 T 的上下文。反射目标本身只能是普通、非泛型入口：原始构造、析构、内部工厂、类型操作辅助函数和带泛型上下文的目标均被拒绝。泛型类中的方法即使没有自己的类型参数，也需要类上下文，应通过普通包装函数暴露：

```cpp
Box<int> makeIntBox(int value):0007 {
    Box<int> result;
    result.set(value);
    return result;
}
```

调用方取得实际 namespace 后，用固定低位编号调用包装函数。源码数值表达式使用十进制 int32；以下写法在 namespace 的高位为 1 时也不溢出：

```cpp
int mountedFunctionId(int ns, int position) {
    if (ns >= 32768) ns = ns - 65536;
    return ns * 65536 + position;
}

void main() {
    int ns = reflect_get_hint_namespace("GENERIC_BOX_LIB");
    Box<int> box = reflect_invoke_function<Box<int>>(mountedFunctionId(ns, 7), 42);
    print(box.get());
}
```

返回的字面量对象由调用块管理；自动类指针的返回按实际调用链转移清理责任，普通 address 返回不转移，new 对象仍需 delete。反射不会把字面量实参自动转换成 `C (*)` 的借用地址；需要地址参数时传类指针，或通过普通包装函数完成适配。无 extern 的宿主函数允许反射调用，由宿主验证参数；有签名的目标保留参数检查，所有目标都检查实际返回值并遵守原有预算和错误清理。

## 装配与生命周期

一个hint只对应一个ABD，重复装载同hint拒绝。装载时从小到大选择实际namespace，排除0000、0abd、0fff、已有脚本namespace、已声明固定宿主namespace以及已注册宿主executor；已有挂载位置不改变。后注册宿主也不能占用活动脚本namespace。

`load_script(bytes,length)` 只装载，`script::setup` 初始false；成功insert后为false。提供 `flush()`、`namespace_for_hint(name)` 和 `hint_loaded(name)`。assume、extern和函数引用按模块保存，不把不同模块中的相同占位地址混在一起。解析或装载冲突失败不修改已有脚本。

flush先解析全部assume并校验目标函数与擦除后的签名，包括隐藏上下文数量和入口种类，验证完成后统一提交调用、析构及默认工厂地址修正和导入绑定；验证失败不执行任何onload、不清除部分assume，可补库后重试。普通执行要求setup完成，直接函数调用入口也受检查。

链接完成后依赖模块先初始化，自身边忽略；循环依赖组内按装载顺序，独立组按首次装载顺序确定顺序。onload只执行一次，重复flush不会重跑已初始化模块。初始化回调允许调用已链接函数；执行或回调中禁止insert、flush和快照替换。全部初始化成功才设置setup=true。

onload失败保留原错误并清理当前作用域，脚本进入故障态，禁止继续执行、insert或flush，必须关闭并重建。关闭按成功初始化顺序的逆序执行这些模块的pre-destroy，并清理已有对象；未flush或插入后未再次flush也可关闭。new对象未delete时仍只作底层释放兜底，不补调用户析构。

JNI增加 `insertScript(File)`、`flush()`、`namespaceForHint(String)`。Java和C++宿主都应先装载全部库，再flush，再invoke。独立解释器支持可重复 `--insert path`，保留可选入口ID；它装配后flush再执行入口。

## 快照与验证

JNI v8快照只保存已完成初始化、无运行中调用的稳定状态，堆对象字段中的字面量对象连同其地址一起保存，恢复时重新编号并改写指向它们的地址。泛型析构捕获的上下文以数据保存，包含默认工厂 ID 及其子上下文；恢复前按已装配函数检查，不引用已结束的调用帧。地址标量、分配起点、对象登记及自动回收顺序使用独立 uint64 地址，分配数量和函数 ID 保持 32 位。身份包括有序模块原始字节、实际namespace、hint和全局offset/count；不同装配身份必须拒绝。恢复先验证再原子替换，不重新链接、不执行初始化或被替换对象的析构。文件仍受64MiB上限，过大的快照保存明确失败。旧exec和快照不兼容。

验证覆盖高位函数ID、前向/递归调用、别名跨模块复用、缺库后重试、签名不符及链接原子性、循环依赖初始化、类外构造/析构、结构类型、跨库对象返回、JNI回调和多模块快照。运行 `python3 tools/build_and_test.py --offline` 与 `--sanitize`，再导出并验收完整发行包。

## 运行随附示例

发行包根目录中分别编译库和调用方，再装配运行：

```sh
./compile.sh examples/multifile/point.azs
./compile.sh examples/multifile/main.azs
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd
```

共享 `point.include.azs` 只声明类与假定地址。`point.azs` 实现构造、方法和析构，并通过私有全局生成字段初值；`main.azs` 创建字面量对象和手动指针对象。输出依次为 `library-load`、`main-load`、`42`、`44`、`point:42`、`point:41`、`42`。

泛型示例使用 [共享头文件](../compiler/examples/generic-box.include.azs)、[实现库](../compiler/examples/generic-box.azs)和[调用方](../compiler/examples/generic-box-consumer.azs)。发行包中运行：

```sh
./compile.sh examples/generic-box.azs
./compile.sh examples/generic-box-consumer.azs
./run.sh examples/generic-box-consumer.exec.abd --insert examples/generic-box.exec.abd
```

输出依次为 `true`、`false`、`0`、`21`、`library`、`42`、`7`，随后四行 `box destroyed`。它同时演示共享泛型正文、两种字段类型、默认值、普通包装入口反射和四个对象的清理。[单文件示例](../compiler/examples/generics-reflection.azs)另演示泛型成员方法以及泛型函数内的反射。
