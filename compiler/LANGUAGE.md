# AzScript 编译器与语言说明

中文 | [English](LANGUAGE.en.md)

这里的编译器按原有代码树格式重写。源码先经过预处理和词法分析，再由递归下降解析器构造 JSON AST，最后解析作用域、函数 ID、参数数量和已声明的类型，生成供 C++ 解释器运行的 ABD。原来的 `GeneraterJson.AzScript`、`getExpression` 和 `Compiler.compile` 入口保留。

## 编译与嵌入

在仓库根目录使用统一构建入口：

```sh
python3 tools/build_and_test.py --offline
./azscript compile compiler/examples/parser-regressions.azs \
  -o build/example.abd --ast build/example.ast.json --exec-json build/example.exec.json
./build/native/interpreter/azscript-run build/example.abd
```

离线构建使用本机已有依赖缓存；首次缺少依赖时去掉 `--offline`。

CLI 的 `compile-json` 接受原有的可读代码树；`pack` 和 `unpack` 打包、解包文件。所有路径由参数传入。源码中的 include 总是相对于当前被包含文件的目录解析。编译输出不会覆盖输入源码。

```java
var script = new azertia.script.GeneraterJson.AzScript();
script.execute(java.nio.file.Path.of("example.azs"));
var jsonTree = script.toObj();
var instructions = azertia.script.Compiler.compile(jsonTree);
byte[] executable = instructions.toValue().toAbdFormat();
```

`execute(String)` 也可使用，其 include 基目录是进程工作目录。每次 execute 清空上一次程序的函数、预处理属性和元数据。编译器实例不应由多个线程同时使用。

纯脚本基础数学库 [`stdlib/math.azs`](stdlib/math.azs) 使用 `#namespace_hint AZSCRIPT_MATH` 独立编译。调用方只通过相对路径 include [`stdlib/math.include.azs`](stdlib/math.include.azs) 中的类型声明；运行时插入库 ABD 并 `flush()`，独立解释器可用 `--insert math.exec.abd` 自动完成链接。头文件的 `4d41` 为假定 namespace，实际位置由运行时分配。浮点 API 接收 `double`，整数 API 接收 `int`，`math_pow` 为 `(double, int)`；extern 参数类型须精确匹配。编译和接入步骤、API、int32 输入域、取整边界及精度约定见 [`stdlib/MATH.md`](stdlib/MATH.md)。

## 函数、变量与作用域

```text
extern int host_add(int, int):0xccf0001;
#namespace 123
#gvar total

void __script_onload() { total = 0; }
int add(a, b) { return a + b; }
int main() {
    def(x);           // 原语法：声明
    x = 2;
    def(y, 3);        // 声明并初始化
    var z = add(x,y); // 等价的可读写法
    { var x = 100; print(x); }
    return z;        // 原语法 return(z); 仍受支持
}
void __script_pre_destroy() { print("done"); }
```

函数返回类型支持 `void/int/string/float/double/boolean/address` 和已声明的类名，`bool` 是 `boolean` 的别名。支持前向引用、递归与任意嵌套函数调用；普通函数位于顶层，成员方法可在类体或使用 `ClassName::method` 在类外定义。参数可以写 `a,b` 或 `int a,int b`，对象参数须显式写类名（`C p` 按值复制，`C * p` 传指针）。局部变量也可写 `int x = 2`；带显式基础类型的局部变量必须同时给出初始化值。显式参数和局部类型参与编译期检查；无类型参数、`var`、`def` 和全局变量保持动态类型。函数返回类型还会由解释器验证。

块 `{}` 创建词法作用域，允许内层同名变量遮蔽外层；同一作用域重复声明报错。局部编号在整个函数内唯一，避免先前子块和兄弟块编号冲突。变量必须先声明，访问已结束作用域内的变量会编译失败。全局变量用 `#gvar` 声明，通常在 `__script_onload` 中初始化。函数参数按值传递，脚本函数调用会检查参数数量。

非 hint 程序保持 `__script_onload = 0`、`main = 0x0fff0000`、`__script_pre_destroy = 1`；钩子必须是无参数 `void` 函数。普通函数默认位于 `0xfff`，`#namespace 1234` 支持 1～4 位十六进制。函数定义可写 `int add(int a,int b):0003 {return a+b;}` 固定低16位编号；先预留显式编号再分配自动编号，固定入口不能用后缀改变编号。自动编号可能随源码改变，宿主应使用同次编译的 AST 映射。

`#namespace_hint NAME` 声明独立库；库内普通函数（包括 `main`）以0000占位，0/1保留给生命周期，自动编号从2开始。`#assume_hint NAME abcd` 为调用声明假定 namespace，运行时在 `flush()` 中重定位。一个编译单元及其有效 include 最多包含一个 namespace 或 namespace_hint 指令。完整规则及可运行类库示例见 [多文件库与 hint 链接](../docs/HINT_LINKING.md)。

十一个内置函数无需声明即可直接调用：

| 名称 | 签名 | ID |
| --- | --- | --- |
| `print` | `void(any)` | `0x0abd0000` |
| `getDepth` | `int()` | `0x0abd0001` |
| `mem_free` | `boolean(address)` | `0x0abd0002` |
| `alloc` | `address(int)` | `0x0abd0003` |
| `make_free` | `void(address)` | `0x0abd0004` |
| `mem_send_up` | `void(address)` | `0x0abd0005` |
| `mem_get` | `any(address)` | `0x0abd0006` |
| `load_extern_library` | `void(string)` | `0x0abd0007` |
| `reflect_invoke_function<R>` | `R(int, ...)` | `0x0abd0008` |
| `reflect_get_hint_namespace` | `int(string)` | `0x0abd0009` |
| `reflect_hint_loaded` | `boolean(string)` | `0x0abd000a` |

这些名称和 `0xabd` ID 是保留的，不能声明为脚本函数或外部函数；内置函数不会写入 AST 的 `abstract` 或 `extern-signatures`。

`load_extern_library("xxx")` 按当前系统把 `xxx` 解析成 `xxx.dylib`、`xxx.dll` 或 `xxx.so`，并读取同目录的 `xxx.signature`。签名通过后，运行时调用动态库导出的 `azscript_load_extern`。库内使用与 C++ 宿主相同的 `registerExecutor` 注册外部函数；脚本仍用编译期 `extern` 声明携带签名。它不装载 hint 模块，也不调用 `flush`。查找规则、公钥和插件写法见 [外部动态库](../docs/EXTERN_LIBRARY.md)。

外部函数统一写作 `extern int custom_call(string, double):12340001;`，参数名可省略，类型必须明确；零参数写 `extern void notify():12340002;`，`void` 不能作为参数类型。ID 支持完整32位十六进制位模式，也支持 `0x` 前缀。旧 `#extern` 不再接受。声明可绑定宿主函数或 hint 库；同名 extern 与定义签名一致时可共存，调用优先绑定 extern。宿主必须用相同 ID 注册；保留 namespace 0000、0abd、0fff 及活动脚本 namespace 不能被宿主占用。

外部函数调用采用精确类型契约，不进行数值隐式转换：声明为 `double` 的参数不能接收 `int` 或 `float`。编译器会检查参数数量和能确定的表达式类型，并把外部函数返回类型继续用于嵌套调用检查；无法确定类型的动态值用于带签名参数时也会拒绝，需先通过显式类型声明让契约清楚。ABD 携带同一签名，解释器会在调用回调前再次检查真实参数，并在回调返回后立即检查真实返回类型，因此错误的宿主实现不会把不匹配值带入后续脚本。内置函数由编译器预声明，不能由脚本函数、`extern` 或手写 AST 覆盖；直接调用宿主函数须使用带签名的 `extern`；按 ID 反射调用的规则见下文。

## 类、字面量对象与指针

```cpp
class Point {
    int x;
    Point * next; // 指针字段，默认 null，不拥有目标对象

    Point(int value) { this.x = value; }
    int get() { return x; }
    Point * self() { return this; }
    ~Point() { print(x); }
}

Point create(int value) {
    Point p(value);
    return p; // 字面量对象移交给调用方，不复制
}

void main() {
    Point a = create(3);            // 字面量对象
    Point b = a;                    // 复制全部 slots，b 是独立对象
    b.x = 4;
    Point * scoped(5);              // 自动指针对象，离开代码块时销毁
    Point * manual = new Point(6);
    delete manual;                  // 输出 6
}                                   // 依次输出 5、4、3
```

`class` 只能在顶层声明。字段必须标注基础类型、类名（字面量对象）或 `类名 *`（指针），按声明顺序各占一个 slot；没有隐藏对象头。包含继承字段在内仍然零字段的类被拒绝。构造方法与类同名且没有返回类型；不声明构造时合成无参构造，声明带参构造后不会额外合成无参构造。析构为可选的 `~ClassName()`，无参数且不能返回值或直接调用。所有成员公开，支持单继承；不支持多继承、虚函数、动态派发、重载、静态成员或嵌套类。

对象有三种写法：

| 写法 | 含义 |
| --- | --- |
| `C x;`、`C x();`、`C x(args);`、`C x = expr;` | 字面量对象：存放在变量自己的 slot 块中，是一个值 |
| `C * x(args);`、`C * x();` | 自动指针对象：分配在公共堆上，由声明所在代码块销毁；无参也要写 `()` |
| `C * x = new C(args);` | 手动对象：离开代码块不会销毁，须 `delete` |
| `C * x;`、`C * x = null;` | 空指针：不创建对象，之后可以赋值为手动对象或其他指针 |

字面量对象按值使用：初始化、赋值、传参和返回都会复制全部 slots，字段中的字面量对象递归复制，指针字段只复制地址。每个副本都是独立对象，到期时各自运行析构。赋值 `a = b` 原地逐字段复制，`a` 的地址不变。指针 `C * y = x;`、指针赋值和传参只复制地址，对象变量重赋值不改变先前创建对象的清理责任。单写 `C * p;` 与 `C * p = null;` 完全相同，声明一个空指针，不构造对象，也不负责清理之后赋给它的对象。

值类型 `C` 与指针类型 `C *` 是不同的类型，不做隐式转换；`null` 只能赋给指针或 address。字面量对象不能比较、不能参与算术，也不能放进无类型位置：`var`、`def`、`#gvar` 全局、无类型参数、`print` 以及 `mem_get(p) = …` 都会编译失败；需要长期保存的对象请使用指针。结构等价的字面量对象之间可以赋值，但两者的清理析构必须相同；派生类的值不能赋给父类的值（不做切片），上转型只用于指针。两种对象都用 `.` 访问成员。只有带显式类类型的变量、参数、字段及返回值能访问成员：`var p = new C(); p.method();` 会因 `p` 是动态 `any` 而失败，需写 `C * p = new C();`。

参数可以写成 `C (*) p`（`C(*) p` 亦可），表示这个函数同时接受 `C *` 指针和 `C` 字面量对象：

```c
int read(Point (*) p) { if (p == null) { return 0; } return p.x; }
void bump(Point (*) p) { p.x = p.x + 100; }

Point a(1);
Point * b = new Point(2);
read(a); read(b); read(null);   // 三种调用都合法
bump(a);                        // a.x 变为 101：字面量对象按地址借给函数，不复制
```

在函数内部 `p` 的类型就是 `C *`，可以为 `null`，也可以重新赋值。传入字面量对象时，编译器取它的地址传入，不复制，函数对它的修改调用方可见；临时对象（如 `read(make(4))`）在所在语句结束时销毁，调用期间始终有效。实参的类型规则与 `C *` 参数相同：可以传派生类的指针或字面量对象，以及结构等价的类；基类字面量对象不能传给派生类的 `(*)` 参数。`(*)` 只能用于类类型的函数参数（包括构造方法、成员方法和 `extern` 声明），不能用于局部变量、字段、返回类型或 `C * (*)` 这样的组合。它与 `C *` 参数有相同的 ABI（address），`extern` 声明与定义的签名须写法一致。函数若保存了这个指针，字面量对象到期后再使用它会报 `Invalid or expired object address`；对借来的字面量对象执行 `delete` 也会在运行时报错。

脚本拿不到字面量对象的存储地址，例外只有两个：类方法中的 `this`，以及 `C (*)` 参数借到的地址，二者的类型都是 `C *`。方法或函数可以返回或保存这个地址，但字面量对象到期后，再经它访问会报 `Invalid or expired object address`。字面量对象的地址也是普通 address：它的最高位标记内部存储，同样参与比较和偏移运算；存储编号永不复用，过期地址不会指向后来创建的对象。

每层类先为标量、address 和指针字段设置默认值：`int` 为 `0`、`float` 为 `0.0f`、`double` 为 `0.0`、布尔为 `false`、字符串为空串、address 和指针为 `null`。然后按本层声明顺序：字面量对象字段用 `C f(args);` 或 `C f = expr;` 构造，没有初始化时调用零参构造（其类没有零参构造则编译报错）；其他字段执行显式初始化表达式。最后执行本层构造正文；继承时父类先完成这些步骤。全部初始化代码位于实际构造实现的前缀，使用实现模块的全局和调用绑定；字段初始化中的名称按 `this`、成员、全局解析，不受构造参数遮蔽。类不能直接或间接以字面量对象包含自身（请改用指针字段），字面量对象嵌套最多 64 层。指针字段不拥有目标对象，销毁外层对象时不会处理它们，需要时在析构内显式 `delete`。

成员通过 `obj.field`、`obj.method(args)` 或 `this.member` 访问，支持连续访问和调用。类内优先查找局部变量和参数，然后查找成员，最后查找全局。接收对象及实参从左到右各求值一次。成员赋值支持 `=`，成员 `+=` 等复合赋值暂不支持。指针可与结构等价、具有父子继承关系的指针或 `null` 做相等比较，不参与地址算术，整数 `0` 不能隐式作为对象。`null` 运行时编码为独立 address 类型的零值；成员访问先验证对象基地址，再计算字段偏移，空指针即使访问非零偏移字段也会报错。

生命周期规则：

- 同一代码块中的字面量对象与自动指针对象按创建的逆序销毁；普通离块、`return`、`break`、`continue` 和运行错误退出都执行清理。
- 一个对象的析构顺序与 C++ 相同：本类析构正文 → 本类字面量字段（逆序）→ 父类析构正文 → 父类字段。本类有字面量字段但没有写析构时，编译器生成只负责销毁这些字段的析构。
- 没有绑定到变量的临时对象（例如 `make().x`）在所在语句结束时销毁；`if`、`while` 条件中产生的临时对象在条件求值后立即销毁。
- 按值参数是调用方对象的副本，随调用结束销毁。
- 返回本函数拥有的局部或参数时直接移交给调用方，不复制，局部也不会先析构；返回字段、全局或借来的对象时先复制一份再交出。
- 返回当前函数拥有的自动指针对象时，仅将直接返回对象的所有权移交给实际调用代码块；返回调用者的对象或 `this` 不改变原所有权。`return new C();` 的结果仍需手动删除。
- 构造失败时不运行该对象自身的析构，已经构造完成的字面量字段会被销毁；如果直接父类已经构造成功，则先执行该父类及其祖先的析构，清理错误不覆盖原构造错误。

`delete expression;` 只接受指针或 `null`，只能销毁由 `new` 创建的对象；通过别名删除同样有效。`delete null;` 无操作，删除自动指针对象、已失效地址或正在析构的对象报运行错误，删除字面量对象在编译时即被拒绝。删除后其他别名不会自动置空。公共堆的地址会被复用且不提供世代识别，程序必须避免使用悬空的堆指针。

析构失败也会释放当前对象，并继续清理其他对象；已有运行错误时保留原错误，否则传播首个析构错误。析构受原有步数和调用深度限制，资源预算耗尽时不能保证执行完整用户析构，但底层存储仍会回收。脚本显式关闭时先运行销毁钩子，再清理全局作用域接管的自动对象，最后卸载函数；未删除的 `new` 对象不补调用户析构，但其 slots 与对象登记随脚本一起释放，宿主反复加载、销毁脚本不会累积泄漏。

字面量对象不跨越 JNI 边界：Java 调用返回字面量对象的函数会报错（该值先被销毁），也不能把它作为参数传入或从 Java 读取；请改用指针。C++ 宿主调用得到的字面量对象由宿主持有，不再运行析构，最后一个句柄释放时其地址报废。C++ 宿主还可以用 `blocks::create`、`blocks::resize`、`blocks::length` 和 `blocks::address_of` 直接使用 slot 块：延伸后已有地址仍然有效，缩短后超出长度的地址报错。

`alloc/mem_free/make_free/mem_send_up` 仍是底层内存接口，指针参数及分配结果均为 address，分配数量仍为 int。原始释放会注销对象登记，但不调用用户析构；对象语法应使用 `delete`。这些函数不管理字面量对象的存储：`mem_free` 对它返回 `false`，`make_free`、`mem_send_up` 报错。构造、析构和成员方法均是普通脚本函数，第一个隐式参数为 `C *`（运行时是 address），类结构类型只供编译器使用。完整结构的等价规则适用于赋值、传参、返回和声明签名：按包含继承字段在内的字段类型及顺序比较，忽略类名、字段名和方法；字面量对象与指针分别比较，指针字段递归比较，循环结构通过已比较类型对终止。运行时不保存或检查类结构。跨模块使用共享类声明及 extern 成员，示例见 [多文件类库](../docs/HINT_LINKING.md)。可执行示例 [`examples/classes-regressions.azs`](examples/classes-regressions.azs) 成功返回 `0`。

## 地址与底层内存

`address` 是独立的无符号 64 位类型，范围为 0 到 `18446744073709551615`。它可用于局部变量、字段、参数和返回类型，空地址为 `null`。显式 address 局部变量须初始化；address 字段默认是 null。`int` 仍是有符号 32 位，普通整数字面量、分配数量、字段偏移、变量槽编号和函数 ID 不会因此变成地址。地址与整数、浮点及布尔值之间没有隐式转换，整数 `0` 不能代替 null。

```cpp
void main() {
    address p = alloc(2);
    mem_get(p) = 10;
    address next = p + 1;
    mem_get(next) = 20;
    print(mem_get(p) + mem_get(next)); // 30
    mem_free(p);
}
```

原始地址支持 `address + int`、`int + address` 和 `address - int`，结果仍是 address；偏移可以为负数。加减检查完整 uint64 边界，溢出或下溢报运行错误。地址之间支持 `== != < <= > >=`，大小比较按无符号值进行；与 null 仅进行相等比较。不支持两个地址相加或相减、`int - address`、浮点偏移、乘除取模、数值取反或直接把地址作为条件；判断空地址使用 `p != null`。简单 address 变量也支持 `+=`、`-=` 和独立语句或 for 步进中的 `++/--`。`print(p)` 和字符串拼接输出无符号十进制。

运算只计算地址值，实际读写还会验证当前分配的活跃范围；64 位地址不扩大当前 1,048,576 slots 的堆上限，也不会自动建立或延长对象生命周期。原始分配、提升所有权和手动释放仍按既有规则执行。`mem_get`、`mem_free`、`make_free`、`mem_send_up` 只接收 address，传入 int 会被拒绝。普通 address 返回沿用 `r`，不隐式提升自动分配的所有权；底层跨层传递继续使用 `mem_send_up`。

类指针在编译期仍有自己的结构类型，不可与原始 address 直接互相赋值，也不能用原始 address 访问成员或执行 delete。只有生成执行文件时，类指针和隐式 this 才统一擦除为运行时 address，字面量对象则以运行时对象值传递；对象返回仍通过 `ro` 转移实际拥有的对象。每个 address 字段只占一个 slot，继承的字段前缀布局不变。

完整示例见 [`examples/address-regressions.azs`](examples/address-regressions.azs)，展示按地址遍历 slots、存储地址值及显式移交原始分配，成功返回 `0`。

## 单继承

```cpp
class Point {
    int x;
    Point(int value) { x = value; }
    int get() { return x; }
    ~Point() { print("point"); }
}

class TaggedPoint : public Point { // 也可省略 public
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    int total() { return get() + tag; }
    ~TaggedPoint() { print("tagged"); }
}

void main() {
    TaggedPoint * item(3, 4);
    Point * base = item;
    base.x = 5;
    print(item.total()); // 9；父类方法和子类访问的是同一段对象内存
    Point * manual = new TaggedPoint(6, 7);
    delete manual; // 输出 tagged、point
    TaggedPoint literal(1, 2); // 字面量子类对象同样逐级析构
} // literal 与 item 都按 tagged、point 的顺序析构
```

写作 `class Child : Base` 或 `class Child : public Base`。父类可以在源码后面声明，继承环、未知父类和多个父类会报编译错误。布局递归保留父类全部 slots 作为前缀，再按声明顺序追加子类自己的字段；继承至少一个字段的子类可以不声明新字段，不增加对象头或隐藏 slots。父类方法接收同一个对象起始地址，因此无需调整地址。

子类可直接使用继承的字段和方法；同名成员由子类遮蔽。成员绑定取决于表达式的静态类类型：子类对象或指针调用子类同名方法，父类指针调用父类方法；父类方法正文中的名称继续按父类解析。当前没有虚函数或运行时方法选择，也没有用于显式选择被遮蔽成员的 `Base::method()` 表达式；可以先绑定父类指针再访问。

子类指针可用于父类指针变量、参数和返回值，地址与所有权不变；相等比较也允许这种继承关系。父类指针不能隐式变成有额外字段的子类指针。子类的字面量对象不能赋给父类的字面量对象（不做切片）。没有继承关系的类仍必须完整结构等价，只有相同前缀不足以赋值；继承后的完整结构也可与相同字段布局的平铺声明等价。

构造先执行父类构造，再为本层全部字段设置默认值、按本层声明顺序执行字段初始化表达式，最后执行本层构造正文。不写父类初始化列表时隐式调用 `Base()`；若父类没有无参构造，会在编译时报错。可用 `Child(int n) : Base(n + 1) { ... }` 显式传参，实参使用子类构造参数并从左到右各求值一次。本层字段初始化仍按成员、实现模块全局解析，不被同名构造参数遮蔽。构造函数编号先于父类初始化列表，例如 `Child::Child(int n):0002 : Base(n) { ... }`。

完成对象的析构从最派生类逐级执行到根父类，每层析构正文之后销毁该层的字面量字段；子类未声明析构时仍会调用父类析构，提前 `return` 或析构错误也不会跳过父类。自动回收、字面量对象到期及通过父类指针 `delete` 都使用创建时登记的完整析构链，这项对象清理规则不引入虚方法派发。构造失败只销毁已经成功完成构造的父类部分，不调用失败层级的析构；保留原始错误并释放对象内存。执行预算耗尽时仍保证底层释放，用户析构是否能完整运行受原有预算限制。

跨 hint 库继承采用相同共享声明：父类和子类分别声明 extern 构造、方法、析构，类外实现可以在不同模块中。继承关系和字段顺序保留在可读 AST，执行文件只包含原有地址、字段偏移和普通函数引用；`flush()` 将跨库构造及析构调用一并链接。可运行回归示例见 [`examples/inheritance-regressions.azs`](examples/inheritance-regressions.azs)。

## 表达式与流程

优先级从低到高：

| 运算符 | 结合性 |
| --- | --- |
| `= += -= *= /= %=` | 右结合 |
| `||` | 左结合，短路 |
| `&&` | 左结合，短路 |
| `== !=` | 左结合 |
| `< <= > >=` | 左结合 |
| `+ -` | 左结合 |
| `* / %` | 左结合 |
| 一元 `+ - !` | 右结合 |
| 括号、字面量、变量、函数调用 | 最高 |

例如 `20-3-2` 得到 `15`，`100/5/2` 得到 `10`，`2+3*(4+1)` 得到 `17`。赋值 `a=b=3` 从右边执行；`&&` 和 `||` 不计算被短路的右侧表达式。

```text
// 原有函数式控制语法
while (i < 10, { i += 1; });
if (i == 10, { print("yes"); }, { print("no"); });

// 新增常见块语法
while (i > 0) { i -= 1; }
if (i == 0) { return 1; } else { return 2; }
```

原有内存写入语法 `mem_get(pointer) = value`得到支持；指针表达式只计算一次。为避免复杂指针表达式中的副作用重复执行，`mem_get(pointer) += value` 等复合赋值会明确拒绝。可以先把指针存进变量，再写 `mem_get(p) = mem_get(p) + value`。其他函数调用不能作为赋值左侧。

`return` 可以从多层 if/while/for 中立即退出函数。`break` 退出最内层循环，`continue` 跳过最内层循环当前迭代的剩余语句，两者只能写在循环体内，不能跨函数。退出经过的块仍正常清理其中的对象。`while` 的 continue 重新检查条件，`for` 的 continue 先执行步进表达式，再检查条件；break、return 和运行错误都不执行步进。

```c
int total = 0;
for (int i = 0; i < 6; i++) {
    if (i % 2 == 0) { continue; }
    total += i;
}
print(total); // 9
```

`for (初始化; 条件; 步进) 语句` 是编译期语法糖，降为普通块和 while，不新增运行时 for 指令。三段均可省略，省略条件等同于 true，`for (;;) { ... }` 为无限循环。初始化只运行一次，可声明一个局部变量或写一个表达式；变量和初始化创建的对象属于整个 for 的独立作用域，循环退出后不可访问。每次循环体有自己的作用域，其中的对象在步进之前析构。步进为单个表达式，不支持逗号表达式或一次声明多个变量。

`i++`、`++i`、`i--`、`--i` 可用作独立语句或 for 步进，只对简单数字或 address 变量进行加减一，不能嵌入返回值、实参或其他表达式，因此不区分前缀/后缀的取值结果。成员或 mem_get 左值仍不支持这种复合修改。整型溢出、地址越界，以及动态变量的非数字且非 address 值会报运行错误。

非 void 函数的每条正常退出路径都必须返回值：if 需要两个分支都返回；while/for 只有条件为字面量 true（或非零数值，for 也可以省略条件）且循环体内没有属于它的 break 时才视为不会正常结束，否则编译器报告 `non-void function can reach the end of its body without returning a value`。语句以分号结束；紧邻块结尾的最后一条语句可以省略分号。函数定义尾部的分号可选。没有数组下标、对象字面量、闭包或异常处理语法；遇到这些语法会拒绝编译。

整数是有符号 32 位，编译期拒绝超范围整数字面量。小数与科学记数法字面量默认生成 double；加 `f`/`F` 后缀生成 float，例如 `1.5f`、`5f`、`2e3f`。两种浮点字面量都会拒绝溢出及下溢为零。数值参与 `print` 或字符串 `+` 拼接时使用最短可往返表示（同 Java/JavaScript 的思路）：`0.1+0.2` 得到 `0.30000000000000004`，`123456789.0` 得到 `123456789`，`1e20` 得到 `1e+20`，float 按 float 精度输出（`0.1f` 得到 `0.1`），负零得到 `-0`。`n / 0` 在除数只能运行时确定时会正常编译；执行时，int、float、double 以及浮点正零或负零都统一抛出 `Division by zero`，不会产生 Infinity 或 NaN。整数溢出同样在运行时报告。

## 字符串、注释与预处理

字符串可用单双引号。支持 `\\`、`\"`、`\'`、`\n`、`\r`、`\t`、`\b`、`\f`、`\0`、`\uXXXX`；`\uXXXX` 产生的 UTF-16 代理项必须成对书写（如 `\uD83D\uDE00`），孤立代理项在编译时报错；字符串中的空格、逗号、括号、分号和花括号完整保留。字符串不能跨越实际换行。支持 `//` 行注释和 `/* ... */` 块注释，括号、字符串、转义或注释未闭合时给出错误位置。

支持以下预处理指令，可在行前缩进：

```text
#include "relative/path.azs"
extern int host_add(int, int):0xccf0001;
#namespace 123
#gvar variable_name
#author Author Name
#setmeta key value with spaces
#setattr enabled yes
#define LIMIT 10
#ifdef enabled
#if_equals enabled yes
// 生效代码
#else
// 不生效代码
#fi
#endif
#ifndef disabled
// 生效代码
#fi
#undef LIMIT
```

`#setattr` 用于条件，不替换代码；`#define` 是标识符级文本替换，不替换字符串、注释、数值字面量内部或其他标识符的子串；紧贴数字的标识符字符（如 `5N`、`0x10`、`2e`）属于该数值字面量，不会展开，而由词法分析报告非法后缀。不支持带参数的宏。include 循环、宏循环、超出 64 层展开、单行宏展开超过 1,048,576 个字符、不匹配条件等会报错。未识别的预处理指令会立即报错，包括未启用的条件分支；普通说明文字应使用 `//` 或 `/* ... */`。宏展开采用文本语义，复杂表达式请写 `#define VALUE (1 + 2)`。

源文件开头的 UTF-8 BOM 会被忽略，不影响首行指令。`return`、`if`、`else`、`while`、`for`、`break`、`continue`、`def`、`var`、`class`、`new`、`this`、`null`、`delete`、`extern`、`public/private/protected/virtual`、所有类型名以及 `true/false` 是保留字，不能声明为函数、参数、变量、`#gvar`、`#define` 或 `extern` 的名字。

嵌套上限以 ABD 的 128 层容器嵌套为准：函数体从第 4 层开始，每个二元/一元运算、每个调用参数、每条语句和每个块各占一层，所以一个表达式大约可以串联 120 个运算符，或嵌套约 60 层块。超限时编译器报告所在函数和行列，例如 `In function main:2:8: Expression nesting exceeds the ABD limit of 128 levels`。泛型类型及指针的类型表达式嵌套最多 64 层，手写 AST 中的类型字符串也遵守此限制。语法递归和 JSON 容器另有 320 层防护上限，用于保护宿主；循环引用的 AST 会提前拒绝。错误后同一进程可以继续编译其他程序。

语法错误包含行列，符号错误包含函数名及行列。经过 include 或宏展开后，语法诊断的行列目前对应合并后的源码，并非精确映射回每个 include 文件；预处理本身的错误另包含处理目录与行号。

## 泛型

泛型类、函数及成员方法使用类型参数，共享一份编译后的正文；不同类型实参不分配新的公开函数 ID。类型参数代表完整类型，可传基础类型、`address`、类值、类指针和参数化类，不接受 `void`、`any` 或参数专用的 `C(*)`。

```cpp
class Box<T> {
    T value;
    Box(T value) { this.value = value; }
    T get() { return this.value; }
    <U> U echo(U value) { return value; }
}
<T> T identity(T value) { return value; }
void main() {
    Box<int> box(3);
    int first = identity(box.get());
    string second = box.echo<string>("hello");
}
```

函数类型参数写在返回类型前面；调用既可显式指定 `identity<int>(3)`，也可根据实参推断。推断递归匹配参数化类型，不依据接收返回值的变量类型，不从 `null` 单独推断类型，也不为不一致的实参寻找共同父类；不能唯一确定时须显式指定。类必须写出实参，不支持 raw type 或菱形 `<>`。参数化类型具有不变性：必须是同一个泛型声明及相同类型实参，未用于字段的参数也不能忽略。原有非泛型类的结构等价和指针上转型继续有效。

上界写作 `<T extends Base>`（类值）或 `<T extends Base*>`（类指针），按声明的继承关系检查。上界可以使用更早声明的类型参数，如 `<A, B extends Box<A>*>`；首版不支持通配符、多上界、循环上界或以类型参数自身作为上界。无界 T 仅能存储、赋值、传参和返回；上界允许访问其保证存在的字段和方法，但不能把任意 Base 赋给 T，也不能把未知派生类值切片为 Base 值。未知 T 不支持算术、比较、delete、`T()`、`new T()`，也不能追加 `*` 或 `(*)`；需要指针时用 `C*` 作为实参。

`T local = expression;` 必须初始化。`T` 字段则保留具体类型的默认规则：标量先置零、false 或空串，address 和类指针先置 null；实际为类值时按字段顺序执行显式初始化或默认构造。先完成父类，再处理子类，字段初始化仍不读取构造参数。没有无参构造的类型可以参与纯传递操作；只有需要默认构造时才失败：当前单元或共享声明足以判断的情况编译报错，跨库正文未知的情况在实际构造时运行报错并清理。显式类值初始化不要求额外的无参构造。

继承写作 `class Child<A,B> : Base<B>`。类外实现写作 `T Box<T>::get()`，类参数自动进入作用域且名称须与声明一致；泛型方法写作 `<U> U Box<T>::echo(U value)`。extern 写作 `extern <U> U identity(U value):abcd0002;`；声明与定义按参数位置及上界匹配，允许类型参数名称不同。构造和析构使用所属类的类型参数，不声明额外类型参数。hint 库继续在类外实现，见 [泛型共享库](../docs/HINT_LINKING.md)。

执行时仅携带不可变的类型操作上下文：ABI 标签、返回类别、可选默认工厂及其绑定上下文，不包含类名或字段布局，不是新的 AZS 值类型，也不占用户参数或字段 slots。类参数在前、方法参数在后，`this` 仍在普通参数槽 0。对象登记保留析构所需上下文，复制、移动、返回、delete、错误清理及快照恢复都保留正确绑定。`T=address` 普通返回不转移对象所有权；`T=C*` 按对象返回规则处理，`T=C` 按现有类值规则复制或移交。

## 按函数 ID 反射与 hint 查询

```cpp
#namespace 1234
int increment(int value):0002 { return value + 1; }
void main() {
    int value = reflect_invoke_function<int>(305397762, 4);
    print(value); // 5，305397762 的位模式为 0x12340002
    print(reflect_hint_loaded("OPTIONAL_LIB"));
}
```

`reflect_invoke_function<R>(int funcid, ...params)` 的 R 可为明确基础类型、address、类指针、类值或 void，也可以是在当前泛型正文中已绑定的 T。函数 ID 使用实际挂载 namespace 的完整 int32 位模式，负数也有效，不对普通整数应用 assume 重定位。接收对象和实参从左到右各求值一次，动态调用沿用当前调用环境、预算及错误清理。非泛型普通成员方法可调用，调用方显式传入 this 指针。

有签名的目标按现有契约校验实参；没有 extern 的宿主回调也允许反射，但须由宿主检查参数。始终校验实际返回标签，包含 void，不额外做数值转换。类指针只能检查 address 标签，类值只能检查 object 标签，不能据此验证类布局或泛型身份；错误的类返回声明不会获得运行时结构检查。

反射拒绝直接调用带隐藏上下文的泛型函数/泛型类方法，以及构造、析构、生命周期钩子、内部工厂和辅助函数。需要动态调用泛型实现时，定义普通包装函数，在正文中固定类型实参后调用；包装函数可以接收或返回 `Box<int>` 等已具体化类型。C++/JNI 普通 invoke 也不接受未绑定泛型入口。构造或删除对象的包装函数须使用对象语法或 delete，不能直接调用生命周期正文。

`reflect_get_hint_namespace(string hint)` 返回已挂载的 namespace 数字，名称不存在或为空时运行报错；`reflect_hint_loaded(string hint)` 在不存在或为空时返回 false。名称区分大小写。“已挂载”不保证 onload 已完成；onload 中也能查询已装载的其他库。字符串查询和动态调用不新增链接依赖，需要初始化顺序时仍声明 `#assume_hint`。将 namespace 和低 16 位编号组合为 int32 时，可以避免乘法溢出：

```cpp
int functionId(int ns, int position) {
    if (ns >= 32768) ns = ns - 65536;
    return ns * 65536 + position; // position 为 0..65535
}
```

## AST 与 ABD 契约

可读 AST 保持 `metadata/ext/abstract/global-variable/body` 结构，并为带签名的宿主函数增加 `extern-signatures`。类程序额外保存类定义、字段顺序和成员表达式等编译信息；`compile-json` 会重新执行类型检查与降级。AST 还保存模块 hint、assume、外部声明、定义名称与实际定义编号；调用绑定与定义位置分开，支持完全相同的 ABD 往返。`abstract` 是 extern 优先的源码名称绑定：有 extern 时保存声明 ID，否则保存定义 ID；hint 库的本地定义仍使用 0000 占位，实际调用的 self-assume 地址在降级时生成。函数定义的独立位置由 `body` 的 namespace 和 `metadata.position/name` 保存；每个签名另保存 `return-type` 与 `param-types`。表达式对象有 `t: "ctrl" | "call"`、`call`、`param`，块是数组；`_line/_column` 仅用于诊断，不写入 ABD 指令。

新生成的 exec/ABD 使用 `exec-version: 8`，与源码元数据的 `version` 分开。所有控制指令的 `c` 都为数字；可读 AST 仍保留源码名称、类型和操作名。下表是 exec JSON 检查视图，实际 ABD 的字段顺序及类型见 [Exec v8 二进制格式](../docs/EXEC_FORMAT.md)。

| exec JSON 指令 | 字段 |
| --- | --- |
| 函数调用 `t=1` | `id`, `param`, `contexts`，二进制 opcode 2 |
| 读取 `t=0,c=3`（v） | `v` 整数槽号 |
| 声明 `c=4`（vd） | `v`，可选 `declared-type` 和 `val` |
| 赋值 `c=5`（vs） | `v`, `val` |
| 内存赋值 `c=6`（m，可读 AST 为 mov） | `v1` 左值、`v2` 值；左值仅允许变量或内置 mem_get 调用 |
| 返回 `c=7`（r） | 可选 `r` |
| 对象返回 `c=8`（ro） | `r`，返回前按实际所有权转移直接返回的指针对象；字面量对象移交或复制给调用方 |
| 对象地址 `c=9`（oa） | `v` 基地址表达式、`offset` 非负整数 |
| 构造完成 `c=10`（ob） | `v` 地址表达式、可选 `destructor` 析构函数 ID（无析构时省略，`0xffffffff` 是有效 ID）、`manual` 布尔值、析构绑定 `contexts` |
| 对象删除 `c=11`（od） | `v` 地址表达式；只允许手动对象，0 无操作，字面量对象报错 |
| 二元 `c=12…24` | `v1`, `v2`；依次为 add/minus/multiply/divide/mod/gt/lt/eq/ne/ge/le/and/or |
| 一元 `c=25/26`（not/neg） | `v` |
| 条件 `c=27`（if） | `v`, `val`，可选 `else` |
| 循环 `c=28`（wi） | `v`, `val` |
| 退出循环 `c=29`（brk） | 无字段 |
| 继续循环 `c=30`（cont） | 无字段 |
| 内部清理 `c=31`（cleanup） | `v` 正文、`val` 清理表达式、`on-error` 布尔值；true 仅失败时清理，false 总是清理，保留原错误及返回/跳转状态 |
| 新建字面量存储 `c=32`（new_block） | `size` slot 数量；产生尚无析构的临时对象值 |
| 字面量地址 `c=33`（block_address） | `v` 对象值表达式；得到其存储地址，对象已到期时报错 |
| 移出对象 `c=34`（mv） | `v` 局部或参数槽号；编译器内部用于把参数中的对象转交构造，不再复制 |
| 结束对象 `c=35`（drop） | `v` 变量或 `mem_get` 目标；运行该存储中对象的析构并结束它 |
| 上下文 ABI `c=36`（context_abi） | `context`；返回实际 ABI 编号 |
| 默认值 `c=37`（context_default） | `context`；默认标量或调用绑定的类值工厂 |
| 泛型返回 `c=38`（return_typed） | `v`, `context`；检查实际标签并按类别返回 |
| 类型检查 `c=39`（check_type） | `v`, `context`；检查标签，表达式只求值一次 |

根、函数、签名和表达式记录都使用裸 `AbdStack`；每个字段本身有长度边界，固定记录不保存键名和已知的类型标签。函数列表、参数类型列表、参数表达式和块内语句也都是裸 stack。常量的实际类型不固定，使用单元素 `AbdArray` 保留 int/float/double/bool/string/void/address 标签；扩展元数据仍使用 `AbdMap`。JSON 中的裸常量和块数组在二进制中分别使用 opcode 0 和 1。Java 调用 `Compiler.compile()` 得到的 `ExecProgram` 可继续作为 `AcsObject` 检查，`toValue()` 输出新 wire 格式；从执行文件恢复检查视图使用 `ExecCodec.decode()`，不再直接把根当作 `AcsObject` 解码。

数字变量的编号规则：`gvs` 为全局变量数量；`-1` 指向模块的第一个全局变量，`-2` 指向第二个，以此类推。函数参数占 `0…param-count-1`，局部变量与编译器临时变量随后连续编号；每个函数单独编号，类方法的隐式 `this` 位于参数槽 `0`。变量编号不是 `alloc` 返回的堆地址，裸整数表达式仍为常量；例如 `{"t":0,"c":3,"v":0}` 才表示读取第一个参数/局部槽。

函数检查视图包含 `id/return-type/script/param-count/param-types/local-count/hidden-count/entry-kind`；`local-count` 只计局部和临时槽，不包含参数。每次调用创建独立槽数组，递归和宿主重入不共享局部值。块退出仍按原规则清理对象，并清空该块声明的局部槽，循环下次进入时重新初始化。全局槽总数及单次调用的参数加局部槽数量均不能超过 1,048,576；无效编号、跨越所属模块全局范围及未声明/已退出作用域的访问会被拒绝。

未声明类型的脚本参数在元数据中使用内部 `any` 类型；无界类型参数也擦除为 any，但只有泛型隐藏上下文非空时才允许 any 返回和 extern any 参数。`entry-kind` 为 0 表示普通入口，1 表示内部入口；上下文不占变量槽。AST 额外保存 `type-parameters`、上界、参数化类型字符串和显式 `type-args`。`param-types` 和 `return-type` 保留供直接 C++/JNI 调用的类型检查；根的 `extern-signatures` 保存宿主边界签名。解释器仅接受 exec v8 裸 stack 文件，不再读取 v7 等旧版本或旧 Map 文件；源码或可读 AST 应重新编译。不识别的版本、opcode、额外/缺失字段、错误字段宽度或超过 128 层的结构在加载时拒绝。JNI 快照版本与 exec 版本独立，本次为 v8。

### insert 与模块全局偏移

`script::insert_script(bytes, length)` 追加数字格式模块时，将追加前的全局槽数量保存到该模块每个 `ofunction::global_offset`，并记录模块自己的 `global_count`。访问负数变量 `v` 时，实际全局下标为 `global_offset + (-int64(v) - 1)`。例如已有两个全局槽，插入模块的 `-1` 就访问下标 `2`；原模块的 `-1` 仍访问下标 `0`。offset 由加载器决定，不由编译产物预设。

执行时使用被调用函数保存的 offset，普通函数、构造、析构、内部创建函数和生命周期钩子都遵守这一规则。后续插入模块不会改变已有 offset。局部变量不应用全局偏移；块级对象所有权及返回转移不受影响。固定模块的普通函数 ID 冲突拒绝插入；hint 模块由加载器选择未占用的实际 namespace，生命周期钩子按模块单独保存。

装载及 insert 只装配，不执行 onload。宿主必须在调用之前显式 `flush()`；成功 insert 会使 `setup=false`，需要再次 flush。链接先完整验证再原子修正调用和析构引用，失败可补库重试；依赖先初始化，循环依赖内按装载顺序。onload 失败后只能关闭重建。关闭按已成功初始化顺序的逆序执行 pre-destroy，再清理全局对象。

类指针在 ABD 中擦除为 address，类型编号为 7；字面量对象的类型编号为 8；int32 仍为 0，动态 any 仍为 6。地址常量使用 `0xce200b` 标签、8 字节无符号小端值，exec JSON 视图为 `{"address":"无符号十进制"}`；例如 null 是 `{"address":"0"}`。只有对象返回指令按实际所有权转移对象，普通 address 或整数返回不转移。JNI v8 快照保存有序模块身份（原始字节、实际 namespace、hint、全局布局）、全局槽、堆（含字段中的字面量对象）和对象清理责任，仅可在已完成初始化且无执行中的状态保存和恢复。恢复先完整校验再原子替换，不重链接或调用旧对象析构；旧 exec 和快照不兼容，须重新编译全部模块；旧源码中用 int 保存的堆地址须改为 address。

## 修复验证与打包

`examples/parser-regressions.azs` 同时展示旧解析器容易拆错的字符串、嵌套调用、左结合减除、混合优先级、递归及控制流。运行后依次输出带转义的字符串、`15`、`10`、`17`、`120`、`26`、`false`、`true`，主函数返回 `146`；CLI runner 还会显示返回值及析构阶段的 `done`。迁移后的 `tse.azs` 也纳入编译与 ABD 往返测试。

归档修复了 `AchievePack.files` 值类型注解、空文件、前导 `00/ff` 字节丢失。新归档通过 `metadata.format=2` 标记字节封装；仍能读取结构有效的旧归档，但旧版打包时已经丢失的前导字节无法恢复。打包拒绝符号链接和目标包含自身，解包拒绝路径穿越、目录中的符号链接及覆盖已有文件。旧解释器/旧归档读取程序不能保证理解新扩展。
