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

CLI 的 `compile-json` 接受原有的可读代码树；`pack` 和 `unpack` 打包、解包文件。所有路径由参数传入，选项可以写在输入路径之前或之后；`--` 结束选项，其后的路径可以以 `-` 开头。include 先在所在文件的目录中查找，再依次查找每个 `-I dir` / `--include-dir dir`；`./azscript` 包装脚本和发行包的 `compile.sh` / `compile.cmd` 最后查找标准库目录，所以任意位置的调用方都可以写 `#include "math.include.azs"`。该目录由单一路径的系统属性 `azscript.stdlib` 指定，包装脚本会设置它；未设置时发行包的启动器使用包内的 `stdlib/`，不是目录的属性值会被忽略。`-I` 目录不存在、为空或不是目录属于用法错误，输出 `Error: ...` 并以退出码 1 结束。除非指定 `--unrestricted-includes`，include 必须位于主文件目录或上述目录之内，见[预处理](#字符串注释与预处理)。`compile-json` 不接受 include 选项。编译输出不会覆盖输入源码，包括被包含的文件。编译错误和警告的格式见[诊断](#诊断)。

`-g`（适用于 `compile` 和 `compile-json`）在执行文件中加入可选调试记录：源文件名、每个函数的显示名及声明位置，以及运算符、调用和语句的源码位置。调试信息默认关闭，因此默认执行文件不含函数名、类名或文件名。`-g` 不改变指令和行为，可读 AST 在有无 `-g` 时相同；格式见 [Exec 格式的调试记录一节](../docs/EXEC_FORMAT.md)。Java 中使用 `Compiler.compile(tree, diagnostics, true)` 或 `Compiler.compile(tree, true)` 生成调试记录。

```java
var script = new azertia.script.GeneraterJson.AzScript();
var diagnostics = new azertia.script.Diagnostics();
try {
    script.execute(java.nio.file.Path.of("example.azs"), diagnostics);
    var jsonTree = script.toObj();
    var instructions = azertia.script.Compiler.compile(jsonTree, diagnostics);
    diagnostics.warnings().forEach(warning -> System.err.println(warning.format()));
    byte[] executable = instructions.toValue().toAbdFormat();
} catch (azertia.script.CompileException error) {
    error.diagnostics().forEach(diagnostic -> System.err.println(diagnostic.format()));
}
```

`execute(String)` 也可使用，其 include 基目录和 include 根目录是进程工作目录；`execute(String, Path)` 改用给定目录，编译不可信文本的嵌入方应传入专用目录。`AzScript.includeDirectories` 和 `AzScript.unrestrictedIncludes` 对应 `-I` 和 `--unrestricted-includes`，在多次 execute 之间保留；`includedFiles()` 列出上一次 execute 所 include 文件的真实路径。`includeDirectories` 中的条目不存在、为空或不是目录，或 `execute(String, Path)` 的基目录不存在或不是目录时，在记录任何诊断之前抛出普通的 `IllegalArgumentException`，而不是 `CompileException`。每次 execute 清空上一次程序的函数、预处理属性和元数据。编译器实例不应由多个线程同时使用。

纯脚本基础数学库 [`stdlib/math.azs`](stdlib/math.azs) 使用 `#namespace_hint AZSCRIPT_MATH` 独立编译。调用方只 include [`stdlib/math.include.azs`](stdlib/math.include.azs) 中的类型声明（使用相对路径，或借助 `-I` 及包装脚本的默认标准库目录按文件名 include）；运行时插入库 ABD 并 `flush()`，独立解释器可用 `--insert math.exec.abd` 自动完成链接。头文件的 `4d41` 为假定 namespace，实际位置由运行时分配。浮点 API 接收 `double`，整数 API 接收 `int`，`math_pow` 为 `(double, int)`；extern 参数类型须精确匹配。编译和接入步骤、API、int32 输入域、取整边界及精度约定见 [`stdlib/MATH.md`](stdlib/MATH.md)。

容器库 [`stdlib/containers.azs`](stdlib/containers.azs) 同样独立编译，hint 为 `AZSCRIPT_CONTAINERS`。调用方 include [`stdlib/containers.include.azs`](stdlib/containers.include.azs)。`List<T>`、`Stack<T>` 和 `Queue<T>` 仍把元素放在链表节点字段中，两端保持 O(1)。`Vector<T>`、`Set<T>` 和 `Map<K, V>` 使用连续 `buffer`；`Set` 和 `Map` 默认用 `value_compare`，类值键要另设普通比较函数。假定 namespace 是 `c071`。操作、所有权和编号见 [`stdlib/CONTAINERS.md`](stdlib/CONTAINERS.md)。

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

函数返回类型支持 `void/int/string/float/double/boolean/address` 和已声明的类名，`bool` 是 `boolean` 的别名。支持前向引用、递归与任意嵌套函数调用；普通函数位于顶层，成员方法可在类体或使用 `ClassName::method` 在类外定义。参数可以写 `a,b` 或 `int a,int b`，对象参数须显式写类名（`C p` 按值复制，`C * p` 传指针）。局部变量也可写 `int x = 2`；带显式基础类型的局部变量必须同时给出初始化值。显式参数和局部类型参与编译期检查；无类型参数、`var`、`def` 和 `#gvar` 全局保持动态类型，带类型的全局与带类型的局部变量一样检查。函数返回类型还会由解释器验证。

脚本返回值必须符合原有的声明类型可赋值规则；数值返回另外只允许以下隐式扩宽：

| 声明的返回类型 | 接受的数值结果类型 |
| --- | --- |
| `int` | `int` |
| `float` | `int`、`float` |
| `double` | `int`、`float`、`double` |

int 转 float 可能舍入，与 `float(x)` 一致；例如 float 函数返回 int 值 16777217 时得到 16777216。float/double 转 int、double 转 float 均不隐式收窄，即使当前值恰好能表示也不例外。需要时写 `return int(value);` 或 `return float(value);`，明确请求受检查的[显式转换](#显式转换)，例如：

```c
float widen(int value) { return value; }
int truncate(double value) { return int(value); }
float narrow(double value) { return float(value); }
```

已知的收窄返回在编译时报错，例如 `return expected int, got double; narrowing requires an explicit cast such as int(x)`。动态 `any` 结果按真实运行时类型检查：int 函数返回 double 时报告 `Function return type mismatch: expected int, got double`。运行时同样只允许精确匹配或数值扩宽，不会静默截断动态返回值。类指针仍遵守原有的可赋值和所有权规则；带类型 extern 回调签名仍须精确匹配，不因此增加数值转换。

块 `{}` 创建词法作用域，允许内层同名变量遮蔽外层；同一作用域重复声明报错。局部编号在整个函数内唯一，避免先前子块和兄弟块编号冲突。变量必须先声明，访问已结束作用域内的变量会编译失败。全局变量见[下文](#全局变量)。函数参数按值传递，脚本函数调用会检查参数数量。

非 hint 程序保持 `__script_onload = 0`、`main = 0x0fff0000`、`__script_pre_destroy = 1`；钩子必须是无参数 `void` 函数。普通函数默认位于 `0xfff`，`#namespace 1234` 支持 1～4 位十六进制（源码中的 namespace 和函数 ID 可带 `0x` 前缀及任意个前导零，如 `#namespace 0x0005151`、`:00000000abcd`；AST 始终写成不带前缀的十六进制，`compile-json` 只接受不超过字段宽度的纯十六进制数字，namespace 为 4 位，完整 ID 为 8 位），`#namespace fff` 显式写出默认 namespace，生成的编号与省略时相同，但仍计入唯一的 namespace 指令。namespace `0000` 保留给生命周期钩子和 hint 库占位，`0abd` 保留给运行时内置函数，`#namespace` 使用这两个值时编译报错；`compile-json` 同样拒绝 `function-namespace` 为 0 的非 hint AST，以及 0000 中除生命周期函数 0/1 以外的普通定义。函数定义可写 `int add(int a,int b):0003 {return a+b;}` 固定低16位编号；先预留显式编号再分配自动编号，固定入口不能用后缀改变编号。自动编号可能随源码改变，宿主应使用同次编译的 AST 映射。需要稳定的公开 ID 时，只在共享头文件的 extern 声明中写一次完整 ID；本模块（hint 库为其自身假定的 namespace）中的实现省略编号即可继承该编号，见 [hint 链接](../docs/HINT_LINKING.md)。

`#namespace_hint NAME` 声明独立库；库内普通函数（包括 `main`）以0000占位，0/1保留给生命周期，自动编号从2开始。`#assume_hint NAME abcd` 为调用声明假定 namespace，运行时在 `flush()` 中重定位。一个编译单元及其有效 include 最多包含一个 namespace 或 namespace_hint 指令。完整规则及可运行类库示例见 [多文件库与 hint 链接](../docs/HINT_LINKING.md)。

二十个内置函数无需声明即可直接调用：

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
| `str_length` | `int(string)` | `0x0abd000b` |
| `str_byte_length` | `int(string)` | `0x0abd000c` |
| `str_substring` | `string(string, int, int)` | `0x0abd000d` |
| `str_char_at` | `string(string, int)` | `0x0abd000e` |
| `str_code_at` | `int(string, int)` | `0x0abd000f` |
| `str_from_code` | `string(int)` | `0x0abd0010` |
| `str_index_of` | `int(string, string[, int])` | `0x0abd0011` |
| `str_starts_with` | `boolean(string, string)` | `0x0abd0012` |
| `str_ends_with` | `boolean(string, string)` | `0x0abd0013` |

这些名称和 `0xabd` ID 是保留的，不能声明为脚本函数或外部函数；内置函数不会写入 AST 的 `abstract` 或 `extern-signatures`。

`load_extern_library("xxx")` 按当前系统把 `xxx` 解析成 `xxx.dylib`、`xxx.dll` 或 `xxx.so`，并读取同目录的 `xxx.signature`。签名通过后，运行时调用动态库导出的 `azscript_load_extern`。库内使用与 C++ 宿主相同的 `registerExecutor` 注册外部函数；脚本仍用编译期 `extern` 声明携带签名。它不装载 hint 模块，也不调用 `flush`。查找规则、公钥和插件写法见 [外部动态库](../docs/EXTERN_LIBRARY.md)。

外部函数统一写作 `extern int custom_call(string, double):12340001;`，参数名可省略，类型必须明确；零参数写 `extern void notify():12340002;`，`void` 不能作为参数类型。ID 支持完整32位十六进制位模式，也支持 `0x` 前缀。旧 `#extern` 不再接受。声明可绑定宿主函数或 hint 库；同名 extern 与定义签名一致时可共存，调用优先绑定 extern。宿主必须用相同 ID 注册；保留 namespace 0000、0abd、0fff 及活动脚本 namespace 不能被宿主占用。

外部函数调用采用精确类型契约，不进行数值隐式转换：声明为 `double` 的参数不能接收 `int` 或 `float`。编译器会检查参数数量和能确定的表达式类型，并把外部函数返回类型继续用于嵌套调用检查；无法确定类型的动态值用于带签名参数时也会拒绝，需先通过显式类型声明让契约清楚。ABD 携带同一签名，解释器会在调用回调前再次检查真实参数，并在回调返回后立即检查真实返回类型，因此错误的宿主实现不会把不匹配值带入后续脚本。内置函数由编译器预声明，不能由脚本函数、`extern` 或手写 AST 覆盖；直接调用宿主函数须使用带签名的 `extern`；按 ID 反射调用的规则见下文。

### 全局变量

```text
class Node { int value; Node(int v) { value = v; } }
int total;                      // 初始为 0
string label = "items";
Node * head = new Node(total + 1);
#gvar legacy                    // 无类型（any）
```

顶层的 `T name;` 或 `T name = expression;` 声明带类型的全局变量。T 可以是标量类型、`address` 或类指针（如 `Node *`、`Box<int> *`）。带类型全局在每次使用时都像带类型的局部变量一样检查，因此 `head.value` 可以使用，`head = 3` 编译失败。类的值和 buffer 不能作为全局（`Global g cannot hold a class value or buffer; declare a pointer`），`void`、`extern` 以及带类型参数的声明也不行。`Node * g(1);` 报告 `Global objects cannot be automatic; use = new Node(...)`；返回指针的函数（如 `Node * make(int v) { ... }`）照常解析。带类型全局不能与函数、内置函数、类或其他全局同名，也不能使用生命周期钩子的名称（`__script_onload`、`__script_pre_destroy`）。

带类型全局在模块 flush 时于 onload（函数 0，即 `__script_onload`；模块没有时由编译器添加）中初始化：先把每个带类型全局设为其类型的默认值（`0`、`0.0f`、`0.0`、`false`、`""`，address 和指针为 `null`），再按声明顺序执行显式初始化表达式，最后执行 onload 正文。初始化表达式可以调用函数、读取任何全局；在它之后声明的全局仍是默认值。初始化表达式的错误会指明所属全局，如 `in global total: Initializer for global total expected int, got string`。初始化表达式在 onload 内执行，归这次调用所有的存储不会比它活得更久：`alloc()` 得到的块或返回给初始化表达式的自动对象在 onload 返回时释放，全局随之悬空；需要比 onload 活得更久的对象请用 `new`；`make_free` 和 `mem_send_up` 只可保留原始分配，不能处理对象存储。模块的带类型全局在其自身 onload 开始前为 void；其他模块可以通过循环依赖观察到这一点，也可以在更早初始化的模块动态调用本模块时观察到（例如在其 onload 中使用 `reflect_invoke_function`）。此时读取它，在运行时检查类型之处（例如由函数返回）会报错，但 `print`、字符串拼接以及复制到无类型变量会静默接受 void（打印为 `null`）。

`#gvar name` 仍是无类型写法：这类全局是动态的 `any`：不能保存类指针、类值、buffer 或类型参数值，不能访问成员，在赋值前为 void，通常在 onload 中赋值。AST 先列出 `#gvar` 名称（compile-json 拒绝出现在带类型条目之后的名称），它们占用最前面的全局槽，带类型全局按声明顺序随后。

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

`class` 只能在顶层声明。字段必须标注基础类型、类名（字面量对象）、`类名 *`（指针）或 `buffer<T>`，按声明顺序各占一个 slot；没有隐藏对象头。包含继承字段在内仍然零字段的类被拒绝。构造方法与类同名且没有返回类型；不声明构造时合成无参构造，声明带参构造后不会额外合成无参构造。析构为可选的 `~ClassName()`，无参数且不能返回值或直接调用。所有成员公开，支持单继承；不支持多继承、虚函数、动态派发、普通函数重载、静态成员或嵌套类。

对象有三种写法：

| 写法 | 含义 |
| --- | --- |
| `C x;`、`C x();`、`C x(args);`、`C x = expr;` | 字面量对象：存放在变量自己的 slot 块中，是一个值 |
| `C * x(args);`、`C * x();` | 自动指针对象：分配在公共堆上，由声明所在代码块销毁；无参也要写 `()` |
| `C * x = new C(args);` | 手动对象：离开代码块不会销毁，须 `delete` |
| `C * x;`、`C * x = null;` | 空指针：不创建对象，之后可以赋值为手动对象或其他指针 |

字面量对象按值使用：初始化、赋值、传参和返回都会复制全部 slots，字段中的字面量对象递归复制，指针字段只复制地址。每个副本都是独立对象，到期时各自运行析构。赋值 `a = b` 原地逐字段复制，`a` 的地址不变。指针 `C * y = x;`、指针赋值和传参只复制地址，对象变量重赋值不改变先前创建对象的清理责任。单写 `C * p;` 与 `C * p = null;` 完全相同，声明一个空指针，不构造对象，也不负责清理之后赋给它的对象。

值类型 `C` 与指针类型 `C *` 是不同的类型，不做隐式转换；`null` 只能赋给指针或 address。字面量对象不能直接比较，算术需声明运算符，也不能放进无类型位置：`var`、`def`、`#gvar` 全局、无类型参数、`print` 以及 `mem_get(p) = …` 都会编译失败；需要长期保存的对象请使用指针。类按名称区分：字面量对象只能赋给同一个类，字段布局相同的其他类也不行；派生类的值不能赋给父类的值（不做切片），上转型只用于指针。两种对象都用 `.` 访问成员。只有带显式类类型的变量、参数、字段及返回值能访问成员：`var p = new C();` 声明本身就会被拒绝，需写 `C * p = new C();`。类指针（包括 `C (*)` 借用）不能进入 `var`、`def`、`#gvar`、无类型参数或原始 `mem_get` 存储；诊断例如 `cannot hold a class pointer (P *); declare it as P * or convert explicitly with address(p)`。仍允许把类指针传给 `print` 或反射可变参数，但 print 原有的类值、buffer 和类型参数值限制不变；反射可变参数继续按原规则接受非 void 值。

参数可以写成 `C (*) p`（`C(*) p` 亦可），表示这个函数同时接受 `C *` 指针和 `C` 字面量对象：

```c
int read(Point (*) p) { if (p == null) { return 0; } return p.x; }
void bump(Point (*) p) { p.x = p.x + 100; }

Point a(1);
Point * b = new Point(2);
read(a); read(b); read(null);   // 三种调用都合法
bump(a);                        // a.x 变为 101：字面量对象按地址借给函数，不复制
```

在函数内部 `p` 的类型就是 `C *`，可以为 `null`，也可以重新赋值。传入字面量对象时，编译器取它的地址传入，不复制，函数对它的修改调用方可见；临时对象（如 `read(make(4))`）在所在语句结束时销毁，调用期间始终有效。实参的类型规则与 `C *` 参数相同：可以传同一个类或派生类的指针或字面量对象；字段布局相同的其他类不行，基类字面量对象也不能传给派生类的 `(*)` 参数。`(*)` 只能用于类类型的函数参数（包括构造方法、成员方法和 `extern` 声明），不能用于局部变量、字段、返回类型或 `C * (*)` 这样的组合。它与 `C *` 参数有相同的 ABI（address），`extern` 声明与定义的签名须写法一致。函数若保存了这个指针，字面量对象到期后再使用它会报 `Invalid or expired object address`；对借来的字面量对象执行 `delete` 也会在运行时报错。

脚本拿不到字面量对象的存储地址，例外只有两个：类方法中的 `this`，以及 `C (*)` 参数借到的地址，二者的类型都是 `C *`。方法或函数可以返回或保存这个地址，但字面量对象到期后，再经它访问会报 `Invalid or expired object address`。字面量对象的地址也是普通 address：它的最高位标记内部存储，同样参与比较和偏移运算；存储编号永不复用，过期地址不会指向后来创建的对象。

每层类先为标量、address 和指针字段设置默认值：`int` 为 `0`、`float` 为 `0.0f`、`double` 为 `0.0`、布尔为 `false`、字符串为空串、address 和指针为 `null`。然后按本层声明顺序：字面量对象字段用 `C f(args);` 或 `C f = expr;` 构造，没有初始化时调用零参构造（其类没有零参构造则编译报错）；其他字段执行显式初始化表达式。最后执行本层构造正文；继承时父类先完成这些步骤。全部初始化代码位于实际构造实现的前缀，使用实现模块的全局和调用绑定；字段初始化中的名称按 `this`、成员、全局解析，不受构造参数遮蔽。类不能直接或间接以字面量对象包含自身（请改用指针字段），经泛型字面量字段（如 `class A<T>{B<T> b;}` 与 `class B<T>{A<T> a;}`）形成的包含环同样拒绝，字面量对象嵌套最多 64 层。指针字段不拥有目标对象，销毁外层对象时不会处理它们，需要时在析构内显式 `delete`。

成员通过 `obj.field`、`obj.method(args)` 或 `this.member` 访问，支持连续访问和调用。类内优先查找局部变量和参数，然后查找成员，最后查找全局。接收对象及实参从左到右各求值一次。成员赋值支持 `=`，成员 `+=` 等复合赋值暂不支持。指针可与同一个类或具有父子继承关系的指针以及 `null` 做相等比较，不参与地址算术，整数 `0` 不能隐式作为对象。`null` 运行时编码为独立 address 类型的零值；成员访问先验证对象基地址，再计算字段偏移，空指针即使访问非零偏移字段也会报错。

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

`alloc/mem_get/mem_free/make_free/mem_send_up` 只操作原始分配存储。不能通过这些接口读取、写入、释放对象存储或改变其所有权来绕过生命周期规则：`mem_get`、`mem_free`、`make_free`、`mem_send_up` 拒绝对象起点、内部地址及字面量/视图地址，构造期间也不例外。对象应使用带类型的成员访问和 `delete`。构造、析构及方法仍是普通函数，首个隐式参数为 C *（运行时是 address）。编译期按类的名义类型检查；运行时成员访问检查对象存储与边界，不检查类身份。跨模块使用共享类声明及 extern 成员，见[多文件类库](../docs/HINT_LINKING.md)。[类回归示例](examples/classes-regressions.azs) 返回 0。

## 地址与底层内存

`address` 是独立的无符号 64 位类型，范围为 0 到 `18446744073709551615`。它可用于局部变量、字段、参数和返回类型，空地址为 `null`。显式 address 局部变量须初始化；address 字段默认是 null。`int` 仍是有符号 32 位，普通整数字面量、分配数量、字段偏移、变量槽编号和函数 ID 不会因此变成地址。地址与整数、浮点及布尔值之间没有隐式转换，整数 `0` 不能代替 null。显式转换同样拒绝 address 与数值互转：`address(a)` 保留已有 address，`string(a)` 将它格式化为无符号十进制；`address(p)` 也可显式擦除类指针、`C (*)` 借用或具有类指针上界的类型参数，不改变地址位模式或所有权。不能转换裸 null 表达式，详见[显式转换](#显式转换)。

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

类指针在编译期仍有自己的类类型，不可与原始 address 直接互相赋值，也不能用原始 address 访问成员或执行 delete。需要原始地址数据时可显式用 `address(p)` 擦除指针，字面量仍是 object 值。这是单向擦除，不能把 address/any 强转回类指针，也不会开放通过原始内存接口访问对象槽。对象返回仍通过 `ro` 转移实际拥有的对象。每个 address 字段只占一个 slot，继承的字段前缀布局不变。

完整示例见 [`examples/address-regressions.azs`](examples/address-regressions.azs)，展示按地址遍历 slots、存储地址值及显式移交原始分配，成功返回 `0`。

### 对象存储与已知限制

`address(p)` 可以作为原始地址数据保存，但不会开放其背后的对象。`mem_get(address(p))`（包括通过它赋值）报告 `mem_get cannot access object storage; use typed member access`；`mem_free`、`make_free`、`mem_send_up` 使用相同格式及各自函数名。类分配在构造代码执行前就已标记，因此构造期间擦除 `this` 也无法绕过检查。带类型的成员访问要求对象存储；普通 `alloc()` 区间会报告 `Object member access requires object storage`。

extern 返回的类指针及 C++/Java 宿主传入的类指针仍是受信任的声明。边界检查 address 标签，成员访问要求活动对象存储并检查偏移，但不会证明名义类身份或验证全部字段。宿主应通过类型匹配的脚本包装函数取得指针，不应通过原始内存接口拼装类对象。JNI 原始写入及 `memFree` 同样拒绝对象存储。

这些检查不构成完整的堆内存安全。堆地址不记录它属于哪一次分配：地址被另一个对象复用后，旧别名可能指向新对象，因此不能普遍防止 delete 后使用或重复 delete。删除不会清空别名。自动对象仍可能通过保留的别名或字段逃逸，应限制在其拥有者的生命周期内；正常的直接返回所有权转移不意味着所有逃逸都安全。这些检查不能用作沙箱或指针生命周期证明。

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

写作 `class Child : Base` 或 `class Child : public Base`。父类可以在源码后面声明，继承环、未知父类和多个父类会报编译错误。继承环、没有字段的类和以字面量对象包含自身等布局错误由编译器在类声明处报告（如 `main.azs:1:7: error: in class A: Inheritance cycle involving A`），源码编译与 `compile-json` 的报告相同。布局递归保留父类全部 slots 作为前缀，再按声明顺序追加子类自己的字段；继承至少一个字段的子类可以不声明新字段，不增加对象头或隐藏 slots。父类方法接收同一个对象起始地址，因此无需调整地址。

子类可直接使用继承的字段和方法；同名成员由子类遮蔽。成员绑定取决于表达式的静态类类型：子类对象或指针调用子类同名方法，父类指针调用父类方法；父类方法正文中的名称继续按父类解析。当前没有虚函数或运行时方法选择。

在类函数（方法、构造、析构或字段初始化）中，限定调用 `Base::method(args)` 对 `this` 调用 `Base` 中可见的同名方法；即使当前类用同名方法或字段遮蔽了它，也按静态绑定选择该方法，例如 `int describe() { return Point::describe() * 100 + tag; }`。`Base` 必须是当前类或其父类，书写时不带类型实参，类型实参来自继承链（如 `class IntBox : Box<int>` 中写 `Box::get()`）；泛型方法可显式给出类型实参，如 `Box::echo<string>("e")`。语句也可以以这种调用开头。编译器拒绝以下情况：在类函数之外使用（`A::f() calls are only available inside class functions`）、所写的类既不是当前类也不是其父类（`A is not B or one of its base classes`）、未知方法或字段（`Unknown method: A.h`、`A.x is a field, not a method`）、构造与析构函数、运算符方法、带接收对象的写法（如 `this.A::f()`）、类名带类型实参（`Name a base class without type arguments in A::method(...)`），以及不是类名的名称。

子类指针可用于父类指针变量、参数和返回值，地址与所有权不变；相等比较也允许这种继承关系。父类指针不能隐式变成有额外字段的子类指针。子类的字面量对象不能赋给父类的字面量对象（不做切片）。没有继承关系的类不能互相转换，字段布局完全相同也不行；没有自身字段的子类同样是另一个类，父类指针不能隐式转为它。

构造先执行父类构造，再为本层全部字段设置默认值、按本层声明顺序执行字段初始化表达式，最后执行本层构造正文。不写父类初始化列表时隐式调用 `Base()`；若父类没有无参构造，会在编译时报错。可用 `Child(int n) : Base(n + 1) { ... }` 显式传参，实参使用子类构造参数并从左到右各求值一次。本层字段初始化仍按成员、实现模块全局解析，不被同名构造参数遮蔽。构造函数编号先于父类初始化列表，例如 `Child::Child(int n):0002 : Base(n) { ... }`。

完成对象的析构从最派生类逐级执行到根父类，每层析构正文之后销毁该层的字面量字段；子类未声明析构时仍会调用父类析构，提前 `return` 或析构错误也不会跳过父类。自动回收、字面量对象到期及通过父类指针 `delete` 都使用创建时登记的完整析构链，这项对象清理规则不引入虚方法派发。构造失败只销毁已经成功完成构造的父类部分，不调用失败层级的析构；保留原始错误并释放对象内存。执行预算耗尽时仍保证底层释放，用户析构是否能完整运行受原有预算限制。

跨 hint 库继承采用相同共享声明：父类和子类分别声明 extern 构造、方法、析构，类外实现可以在不同模块中。继承关系和字段顺序保留在可读 AST，执行文件只包含原有地址、字段偏移和普通函数引用；`flush()` 将跨库构造及析构调用一并链接。可运行回归示例见 [`examples/inheritance-regressions.azs`](examples/inheritance-regressions.azs)。

## 表达式与流程

优先级从低到高：

| 运算符 | 结合性 |
| --- | --- |
| `= += -= *= /= %= &= \|= ^= <<= >>= >>>=` | 右结合 |
| `?:` | 右结合，只计算选中的分支 |
| `\|\|` | 左结合，短路 |
| `&&` | 左结合，短路 |
| `\|` | 左结合 |
| `^` | 左结合 |
| `&` | 左结合 |
| `== !=` | 左结合 |
| `< <= > >=` | 左结合 |
| `<< >> >>>` | 左结合 |
| `+ -` | 左结合 |
| `* / %` | 左结合 |
| 一元 `+ - ! ~` | 右结合 |
| 括号、字面量、变量、函数调用 | 最高 |

例如 `20-3-2` 得到 `15`，`100/5/2` 得到 `10`，`2+3*(4+1)` 得到 `17`。赋值 `a=b=3` 从右边执行；`&&` 和 `||` 不计算被短路的右侧表达式。

`if`、`while`、`for` 的条件（包括函数式写法）、`?:` 的条件，以及 `!`、`&&`、`||` 的操作数必须是 boolean。数字、字符串、address 和 void 都不自动转为真假，应显式写 `n != 0` 或 `p != null`。已知的非布尔操作数在编译时拒绝，例如 `if condition must be boolean, got int`。动态 `var`、`def` 或无类型参数在实际求值时检查，真实类型为 int 时报告 `Condition must be boolean, got int`。短路规则不变：左侧布尔值已决定结果时，不计算右侧。

相等运算 `==` / `!=` 接受以下操作数配对，结果为 boolean：

| 操作数配对 | 比较规则 |
| --- | --- |
| `int`、`float`、`double` 的任意组合 | 数值相等，允许混合数值类型。 |
| `boolean` 与 `boolean`；`string` 与 `string` | 同类型的值相等。 |
| `address` 与 `address` 或 `null` | 比较无符号地址值，null 是地址零。 |
| 同类或具有继承关系的类指针；类指针与 `null` | 比较指针身份；无关类指针、原始 address 与类指针的配对被拒绝。 |
| `null` 与 `null` | 相等。 |
| 动态 `any` 与另一个动态值或非类标量 | 延迟到运行时检查，真实值必须符合上面的兼容配对。 |

其他已知配对在编译时拒绝，例如 `Equality requires compatible operand types, got string and int`。动态的不兼容配对在运行时报错，不再返回 false/true。布尔不是数字，整数零不是 null。类值和 buffer 没有通用相等运算，应显式比较字段或元素；无界类型参数也不会获得相等运算。静态类型为 void 的操作数不能比较。运行时的两个 void 值相等（`eq` 为 true，`ne` 为 false）；尚未赋值的动态变量也适用这一规则。

```text
// 原有函数式控制语法
while (i < 10, { i += 1; });
if (i == 10, { print("yes"); }, { print("no"); });

// 新增常见块语法
while (i > 0) { i -= 1; }
if (i == 0) { return 1; } else { return 2; }
```

原有内存写入语法 `mem_get(pointer) = value`得到支持；指针表达式只计算一次。为避免复杂指针表达式中的副作用重复执行，`mem_get(pointer) += value` 等复合赋值会明确拒绝。可以先把指针存进变量，再写 `mem_get(p) = mem_get(p) + value`。其他函数调用不能作为赋值左侧。

`return` 可以从多层 if/while/for/switch 中立即退出函数。`break` 退出最内层循环或 switch；`continue` 跳过最近外围循环当前迭代的剩余语句，即使位于 switch 内也如此。break 要求处于循环或 switch 中，continue 要求外围有循环，两者都不能跨函数。退出经过的块仍正常清理其中的对象。`while` 的 continue 重新检查条件，`for` 的 continue 先执行步进表达式，再检查条件；break、return 和运行错误都不执行步进。

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

非 void 函数的每条正常退出路径都必须返回值：if 需要两个分支都返回；while/for 只有条件为字面量 true（for 也可以省略条件）且循环体内没有属于它的 break 时才视为不会正常结束，否则编译器报告 `non-void function can reach the end of its body without returning a value`。语句以分号结束；紧邻块结尾的最后一条语句可以省略分号。函数定义尾部的分号可选。没有对象字面量、闭包或异常处理语法；遇到这些语法会拒绝编译。字符串、buffer 和提供下标运算符的类支持下标访问。

整数是有符号 32 位，编译期拒绝超范围整数字面量。小数与科学记数法字面量默认生成 double；加 `f`/`F` 后缀生成 float，例如 `1.5f`、`5f`、`2e3f`。两种浮点字面量都会拒绝溢出及下溢为零。数值参与 `print` 或字符串 `+` 拼接时使用最短可往返表示（同 Java/JavaScript 的思路）：`0.1+0.2` 得到 `0.30000000000000004`，`123456789.0` 得到 `123456789`，`1e20` 得到 `1e+20`，float 按 float 精度输出（`0.1f` 得到 `0.1`），负零得到 `-0`。`n / 0` 在除数只能运行时确定时会正常编译；执行时，int、float、double 以及浮点正零或负零都统一抛出 `Division by zero`，不会产生 Infinity 或 NaN。整数算术溢出同样在运行时报告；位移遵循下述位模式规则。

### switch

`switch (值) { ... }` 是语句，不是表达式。它对 int 或 string 值求值一次，再选择匹配的字面量标签。其他类型（包括动态 `any`）被拒绝；应显式转换动态值，例如 `switch (int(value))` 或 `switch (string(value))`。

```c
string action = "save";
switch (action) {
    case "save":
    case "write":
        print("saved");
        break;
    default:
        print("unknown");
        break;
    case "quit":
        print("bye"); // 最后一个分段隐式 break
}
```

int switch 的 case 标签必须是 int 字面量（包括负整数），string switch 则必须是 string 字面量；表达式、变量、类型不匹配、重复标签及多个 default 均被拒绝。连续标签共享一个分段。`default` 可放在任意位置，只在所有 case 都不匹配时执行。没有匹配的 case 或 default 时，继续执行 switch 后面的语句。首个标签之前不能有语句，switch 之外也不能使用 `case`/`default`。

不允许贯穿执行。每个非末尾分段都必须通过 `break`、`return`、适用的 `continue` 或其他不能正常结束的路径离开；存在到达下一个分段的路径就会报错。最后一个分段允许正常结束，并隐式补上 break。每个分段都有独立的局部变量/对象作用域，连续标签共享该作用域，离开时照常执行清理。

`break` 退出最内层循环或 switch。switch 中的 `continue` 继续最近的外围循环，并执行对应的 for 步进；没有外围循环的 switch 不能使用 `continue`。嵌套 switch 也保持这个目标规则。带 default 且所有分段都返回的 switch 可满足非 void 函数的返回要求；没有 default 时仍有未匹配路径。

选择通过比较完成，不使用跳转表；消耗的执行步数与匹配前检查的标签数量成正比，没有匹配时检查所有标签。不新增运行时 opcode。

### 位运算与移位

`&`、`|`、`^` 和一元 `~` 分别是按位与、或、异或及取反。`<<` 左移，`>>` 带符号扩展地右移，`>>>` 右移并补零。操作数（包括移位量）必须是 int；已知的 float、double、boolean、string 和 address 操作数在编译时拒绝，动态操作数在运行时也必须实际为 int。这些运算从左到右各求值一次，不短路。

运算作用于 32 位位模式，结果仍为 signed int32。左移丢弃移出的高位、低位补零；这种回绕不报告算术溢出。例如 `1 << 31` 为 -2147483648，`-8 >> 1` 为 -4，`-1 >>> 1` 为 2147483647，`~0` 为 -1。每次移位的数量必须为 0…31（含端点）：非法字面量在编译时拒绝，非法动态值在运行时抛错，例如数量为 32 时报告 `Shift count must be between 0 and 31, got 32`。移位量不会按 32 取模。

移位优先级低于加减、高于比较。相等比较优先于按位 `&`，随后依次是 `^`、`|`；三者均优先于 `&&`、`||`。位测试应加括号写成 `(flags & mask) == 0`；`flags & mask == 0` 会尝试对 boolean 做 `&`，因此被拒绝并提示为比较添加括号。

移位符号必须在预处理后的文本中相邻：`a >> b` 是移位，`a > > b` 不是，会被拒绝。类型语法中的泛型结束符仍单独解释，因此 `buffer<buffer<int>>` 及泛型调用后接移位仍然有效。简单变量还支持 `&=`、`|=`、`^=`、`<<=`、`>>=`、`>>>=`；成员、下标和 `mem_get` 不是复合赋值目标。一元 `~x` 降为 `bit_xor(x, -1)`，没有单独的一元 opcode。

### 条件表达式

`条件 ? 真值 : 假值` 对 boolean 条件求值一次，再只对选中的分支求值一次。跳过的分支没有副作用，但两个分支都要通过类型检查。`?:` 的优先级高于赋值、低于 `||`，并且右结合：`a ? b : c ? d : e` 等同于 `a ? b : (c ? d : e)`。

```c
boolean ready = true;
double value = ready ? 1 : 2.5; // double 1
int divisor = 0;
int safe = ready ? 7 : 10 / divisor; // 7，未选中的除法不运行
```

两个分支必须有共同类型：

| 分支类型 | 结果类型 |
| --- | --- |
| 数值类型 | 按 double、float、int 取最宽类型；选中的较窄分支转换到该类型。 |
| 相同的非 void 类型 | 保持该类型，包括同类的类值或相同 buffer 类型。 |
| 父类和子类指针 | 父类指针类型。 |
| 指针或 address 与 `null` | 对应的指针或 address 类型；具有指针上界的类型参数 `T` 与 `null` 配对时保持 `T`。 |

数值提升遵循显式转换的舍入规则，因此 int 转 float 可能舍入。void 分支、无关类指针、不同类值类型，以及 string/int 等不兼容标量配对均被拒绝。即使类有继承关系，类值也不会隐式切片为父类值。两个动态 `any` 分支保持 `any`；`any` 与其他已知类型的配对被拒绝，必要时应显式转换动态操作数。

选中的分支在当前外围作用域中执行；条件选择不会像函数式 `if` 一样为分支建立新作用域。拥有的字面量临时值按原规则存活到语句边界，自动指针对象保持外围块的生命周期，原有所有权与清理规则继续适用。写入检查递归检查两个分支：如果 `items[0]` 是 getter 返回的类值副本，`(ready ? items[0] : local).field = 1` 会被拒绝，即使该分支运行时不会选中。应先读到明确类型的局部变量，修改后再写回。

`:` 两侧可不加空格：`ready?1:2`、`ready?true:false` 和 `ready?1:1e5` 均有效；表达式中的字面量仍遵守正常数值检查。这不改变 `:0002`、`:1e5` 等十六进制函数编号后缀、构造函数的父类初始化列表或 `Base::method()` 限定调用。

### 显式转换

`int(x)`、`float(x)`、`double(x)`、`string(x)` 和 `address(x)` 恰好接受一个表达式，只求值一次。它们是保留的编译器内置形式，不能由用户函数覆盖。支持的源类型/目标类型配对如下：

| 源类型 | `int` | `float` | `double` | `string` | `address` |
| --- | --- | --- | --- | --- | --- |
| `int`、`float` 或 `double` | 向零截断，再检查 int32 范围 | 舍入为 float 并检查溢出 | 转为 double | 现有的最短可往返数值文本 | 拒绝 |
| `string` | 解析十进制整数 | 按 float 解析十进制数 | 按 double 解析十进制数 | 恒等 | 拒绝 |
| `address` | 拒绝 | 拒绝 | 拒绝 | 无符号十进制文本 | 恒等 |
| 类指针、`C (*)` 借用或指针上界 `T` | 拒绝 | 拒绝 | 拒绝 | 拒绝 | 显式擦除，不改变所有权 |

已知的 boolean、void、类值、buffer、null，以及没有类指针上界的类型参数来源被拒绝。类指针及指针上界类型参数只允许上表中的显式 address 擦除，不生成 opcode 49；值上界和无界 T 仍被拒绝。没有 boolean、void、类、buffer 或类型参数转换目标。`int(addressValue)`、`address(7)`、`address("7")` 仍不支持。`bool(x)` / `boolean(x)` 报告 `Boolean conversions are not supported; write a comparison`；C 风格 `(int)x` 报告 `C-style casts are not supported; write int(x)`。

数值转 int 先向零截断，再检查范围：`int(2147483647.9)` 为 2147483647，`int(-2.9)` 为 -2。浮点转换可能损失精度（`float(16777217)` 为 16777216），允许次正规数和下溢为零，但拒绝溢出或非有限结果。这种下溢规则适用于数值及字符串转换；上文的源码字面量范围检查仍是另一套规则。

整数字符串必须符合 `[+-]?[0-9]+`，不能含小数点或指数。float/double 字符串允许可选正负号、可带小数点的十进制数字（至少一位），以及可选的 `e`/`E` 指数（可带正负号，至少一位数字）。解析与区域设置无关，小数点固定为 `.`。空白、十六进制、`f` 后缀、NaN/Infinity、嵌入 NUL 和尾部多余文字都会被拒绝。例如 `int("+7")`、`double("2.5e-3")` 成功，`int("1e2")`、`float(" 1")` 失败。即使参数是字符串字面量，内容和范围也在运行时检查。

静态已知的不支持配对在编译时报告 `Cannot convert X to Y`；动态 `any` 参数按真实运行时标签检查。静态已知的同类型转换是恒等操作，不生成转换 opcode；`any` 总是生成 opcode 49，即使真实类型已与目标相同。运行时错误消息例如：

```text
Cannot convert double to int: value is out of range
Cannot convert string " 1" to int: invalid decimal integer
Cannot convert string "1f" to float: invalid decimal number
Cannot convert string "1e400" to double: value is out of range
```

错误中引用的字符串最多显示前 64 个 Unicode 标量字符，截断时在引号内追加 `...`。引号和反斜杠会转义；非 ASCII 字符及 NUL 使用 ASCII 形式的 UTF-16 转义，例如 `\ud83d\ude00` 和 `\u0000`，保证消息可以安全地经过 JNI。

## 字符串、注释与预处理

字符串可用单双引号。支持 `\\`、`\"`、`\'`、`\n`、`\r`、`\t`、`\b`、`\f`、`\0`、`\uXXXX`；`\uXXXX` 产生的 UTF-16 代理项必须成对书写（如 `\uD83D\uDE00`），孤立代理项在编译时报错；字符串中的空格、逗号、括号、分号和花括号完整保留。字符串不能跨越实际换行。支持 `//` 行注释和 `/* ... */` 块注释，括号、字符串、转义或注释未闭合时给出错误位置。

### 字符串函数

字符串下标、长度、搜索位置和子串端点按从零开始的 Unicode 码点计数，不按 UTF-8 字节、UTF-16 代码单元或字素簇计数：BMP 之外的 emoji 算一个码点，基础字母加组合附加符号算两个。只有 `byte_length` 统计 UTF-8 字节数。匹配精确且区分大小写，不进行 Unicode 规范化。嵌入 NUL 是普通码点，不会终止字符串。

| 字符串形式 | 等价内置函数 | 结果 |
| --- | --- | --- |
| `s.length()` | `str_length(s)` | 码点数量。 |
| `s.byte_length()` | `str_byte_length(s)` | UTF-8 字节数。 |
| `s.substring(begin, end)` | `str_substring(s, begin, end)` | 半开区间 `[begin, end)` 的字符串。 |
| `s.char_at(i)` 或 `s[i]` | `str_char_at(s, i)` | 含一个码点的字符串。 |
| `s.code_at(i)` | `str_code_at(s, i)` | int 表示的 Unicode 标量值。 |
| `s.index_of(needle[, from])` | `str_index_of(s, needle[, from])` | from 及之后首个匹配的码点下标，未找到为 -1；from 默认是 0。 |
| `s.contains(needle)` | `str_index_of(s, needle, 0) >= 0` | 是否包含 needle。 |
| `s.starts_with(prefix)` | `str_starts_with(s, prefix)` | 是否匹配前缀。 |
| `s.ends_with(suffix)` | `str_ends_with(s, suffix)` | 是否匹配后缀。 |
| `str_from_code(code)` | 同名函数；没有接收对象形式 | 含一个 Unicode 标量值的字符串。 |

```c
string text = "A😀中";
print(text.length());         // 3
print(text.byte_length());    // 8
print(text[1]);               // 😀
print(text.substring(1, 3));  // 😀中
print(text.index_of("中"));    // 2
print(str_from_code(128512) == text[1]); // true
```

`char_at`、`code_at` 及下标访问要求 `0 <= i < length`。子串要求 `0 <= begin <= end <= length`，空区间产生空串。搜索起点要求 `0 <= from <= length`。空 needle 在 from 处匹配，包括 length 处；空前缀、后缀总是匹配。`str_from_code` 接受 0…0x10ffff，但排除代理项 0xd800…0xdfff；码值 0 产生 NUL。

下标和码值必须为 int，字符串参数必须为 string，void 实参被拒绝。接收对象和实参从左到右各求值一次。方法名只提供表中的 snake_case 形式；`contains` 只有方法形式，不占用独立内置 ID。双参数 `str_index_of(s, needle)` 和单参数 `s.index_of(needle)` 都在编译时补上显式的零起点。字符串方法和下标直接绑定保留的字符串内置函数，不受同名类方法影响。

字符串不可变。`s[i] = value` 报告 `Strings are immutable; cannot assign through an index`；应构造新字符串，再给变量赋值。所有字符串函数都拒绝宿主传入的非法 UTF-8，包括 `byte_length` 和 needle 为空的搜索。下标/范围错误使用 `std::out_of_range`（JNI 为 `IndexOutOfBoundsException`），例如 `String index 5 is outside length 3` 或 `String substring start 2 is after end 1`。非法 UTF-8 或标量值使用 `std::invalid_argument`（JNI 为 `IllegalArgumentException`），消息为 `Invalid UTF-8 string` 或 `Invalid Unicode code point: 55296`。运行时调用栈保留这些异常类型。

### 预处理

支持以下预处理指令，可在行前缩进：

```text
#include "relative/path.azs"
#pragma once
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

`#include "path"` 先查找所在文件的目录，再依次查找每个 include 目录（`-I` 或 `AzScript.includeDirectories`），使用找到的第一个普通文件；同名目录不会中止查找。包装脚本的标准库目录排在所有 `-I` 目录之后。include 根目录是主文件所在目录（字符串源码为其基目录）和各 include 目录，include 必须位于其中之一。绝对路径（`Absolute include path "/etc/passwd" is not allowed`）以及离开所有根目录的路径（`Include "../x.azs" escapes the allowed include roots (...)`）不经文件系统即被拒绝。根目录内的符号链接会被跟随；目标位于所有根目录之外的 include 被拒绝且不会被读取，但错误信息仍可能反映该目标是否存在。`..` 按词法解析，先于符号链接；经符号链接给出的 include 目录也包含按该链接写法拼出的路径。根目录下整个子树中的文件都可以被 include，其内容也可能出现在诊断中，所以编译不可信源码时，根目录中不应有私密内容。`--unrestricted-includes`（`AzScript.unrestrictedIncludes`）允许绝对路径和根目录之外的路径。找不到 include 时列出全部查找过的路径，例如 `Include not found: "x.azs" (searched /work/app/x.azs, /work/lib/x.azs)`，并说明有几个位于根目录之外的候选路径未被查找。路径写在双引号中，以 `/` 分隔；旧的无引号写法 `#include lib/x.azs` 以该行其余部分为路径，不支持 `#include <x.azs>`。

`#setattr` 用于条件，不替换代码；`#define` 是标识符级文本替换，不替换字符串、注释、数值字面量内部或其他标识符的子串；紧贴数字的标识符字符（如 `5N`、`0x10`、`2e`）属于该数值字面量，不会展开，而由词法分析报告非法后缀。不支持带参数的宏：`#define F(x) ...` 报告 `Function-like macros are not supported: F; write a function, or put a space before '(' for an object-like macro`，`(` 前有空格的 `#define F (x)` 则是值为 `(x)` 的普通宏。include 循环、宏循环、超出 64 层展开、单行（含注释）在宏展开前或展开后超过 1,048,576 个字符（`Source line exceeds 1048576 characters` / `Macro expansion exceeds 1048576 characters on one line`）、不匹配条件等会报错。未识别的预处理指令会立即报错，包括未启用的条件分支；普通说明文字应使用 `//` 或 `/* ... */`。宏展开采用文本语义，复杂表达式请写 `#define VALUE (1 + 2)`。

`#pragma once` 使所在文件在一次编译中只展开一次：之后再 include 同一文件时不产生任何内容，也不计入 include 次数。同一文件按解析符号链接后的真实路径判断，与 include 的写法无关。该检查先于 include 循环检查，所以带 `#pragma once` 的文件经其他文件再 include 自身时直接跳过；文件在执行到 `#pragma once` 之前 include 自身仍是循环。未启用条件分支中的 `#pragma once` 不生效。只支持 `once`，在启用的分支中其他名称或其后多余的文字会报错；与 C 相同，未启用的分支不检查 pragma；字符串源码不能被 include，其中的 `#pragma once` 没有效果。`#ifndef` include guard 照常可用。

源文件（主文件和被包含文件）必须是普通文件：目录、FIFO 和设备文件不会被打开。源文件须为合法 UTF-8，非法字节报告其所在的行列。同一文件在一次编译中只读取一次，再次 include 时复用已读取的文本。预处理有以下上限：

- 单个源文件最多 16 MiB（16,777,216 字节），一次编译读取的不同源文件合计最多 64 MiB；
- include 最多嵌套 64 层，一次编译最多展开 4096 次 include，被 `#pragma once` 跳过的不计；重复 include 的头文件应使用 `#pragma once`，因为 include guard 既不减少展开次数，也不能避免每次 include 时重新扫描头文件；
- 预处理后的文本（含全部 include 和宏展开）最多 16,777,216 个字符，不计最后一行的换行符，所以不含 include 和宏的最大源文件不会超出此上限；
- 整个程序最多 4,194,304 个词法记号。

源文件开头的 UTF-8 BOM 会被忽略，不影响首行指令。`return`、`if`、`else`、`while`、`for`、`switch`、`case`、`default`、`break`、`continue`、`def`、`var`、`class`、`new`、`this`、`null`、`delete`、`extern`、`public/private/protected/virtual`、`any`、所有类型名以及 `true/false` 是保留字，不能声明为类、字段、方法、类型参数、函数、参数、变量、`#gvar`、`#define` 或 `extern` 的名字，报告为 `'any' is a reserved word and cannot be used as a name`。标识符的字符沿用 Java 标识符规则中基本多文种平面（BMP）的字符（字母、数字、`_`、`$` 等，不能以数字开头），但控制字符和格式字符（如零宽空格 U+200B、双向覆盖 U+202E）不能出现在标识符中，以免两个名字看起来相同，源码中报告为 `Unexpected character: U+202E`；不在文件开头的 U+FEFF 同样如此报告。诊断信息把这类字符打印为转义形式，如 `Invalid field name: x\u202e`。`compile-json` 对手写 AST 中的类、字段、方法、类型参数、函数、参数、局部变量和全局变量名应用同一套规则，例如 `in class P: 'any' is a reserved word and cannot be used as a field name`；以 `#` 开头的局部变量名留给编译器生成的变量。

嵌套上限以 ABD 的 128 层容器嵌套为准：函数体从第 4 层开始，每个二元/一元运算、每个调用参数、每条语句和每个块各占一层，所以一个表达式大约可以串联 120 个运算符，或嵌套约 60 层块。超限时编译器报告文件、行列和所在函数，例如 `main.azs:2:8: error: in function main: Expression nesting exceeds the ABD limit of 128 levels; split the expression or statement`。泛型类型及指针的类型表达式嵌套最多 64 层，手写 AST 中的类型字符串也遵守此限制。语法递归和 JSON 容器另有 320 层防护上限，用于保护宿主，超限时报告最内层所在的语句或字段，或 JSON 行列；循环引用的 AST 会提前拒绝。错误后同一进程可以继续编译其他程序。

### 诊断

编译错误采用 gcc/clang 格式 `文件:行:列: error: [in 上下文: ]消息`，输出到标准错误。经过 `#include` 和宏展开后，位置仍指向原始文件、行和列：

```text
inc/lib.azs:6:3: error: in function broken: Initializer for s expected string, got int
main.azs:2:1: note: in file included from here
Error: compilation failed: 1 error
```

- 被包含文件中的错误之后，每层外围 `#include` 各有一行 `note: in file included from here`，由内向外排列。
- 宏展开产生的记号上的词法或语法错误报告在宏的使用位置，随后一行 `note: in expansion of macro 'NAME' defined here` 指向其 `#define`；`#define` 位于另一文件时，还附上该文件的 include 链。展开代码中的语义错误报告在宏的使用位置，不附此注释。展开之后的记号保持各自的列号。
- 预处理错误指向指令的 `#`；未闭合的条件报告在其起始指令，未结束的注释报告在注释开始处。找不到主文件时提示 `Cannot open source file "x.azs": file not found`，找不到 include 文件时提示 `Include not found: "x.azs" (searched /work/x.azs)`，其中查找过的路径为绝对路径，存在但无法打开的 include（例如符号链接循环）提示 `Cannot open include file "x.azs": <原因>`，非普通文件提示 `Source file "x.azs" is not a regular file`，非法 UTF-8 提示 `Source file "x.azs" is not valid UTF-8 (line 3, column 7)`；include 循环会列出相关文件。源文件和被包含文件的名称不能含有控制字符或双向格式字符，因为诊断会输出这些名称（`Invalid source file name` / `Invalid include file name`）。
- 上下文指出外围声明：`function main`、`function C::m`、构造与析构函数的 `function C::C` 和 `function C::~C`、`function C::operator[]`、`initializer of class P`、`class P` 或 `global g`。没有源码位置的错误（例如手写 AST 的格式错误）只给出输入文件名：`compile` 为源文件，`compile-json` 为 JSON 文件。
- 从源码编译时，重复的函数、类、函数 ID 以及冲突的 extern 声明附带 `previous definition is here` 或 `previous declaration is here` 注释；同一头文件被读取两次造成的重复定义还会提示添加 `#pragma once` 或 include guard。函数 ID 按 8 位十六进制显示，例如 `0x0fff0005`。
- 非 void 函数可能不经 `return` 走到末尾时，错误报告在其结束的右花括号处。

文件名对主文件使用其文件名，对被包含文件使用相对于包含它的第一个 include 根目录（先主文件目录，再依次为各 include 目录）、以 `/` 分隔的路径，所以与项目或发行包所在的位置无关。若更靠前的根目录中有同名的另一个文件，或该名称已属于另一个文件，则改用相对主文件目录的路径（例如 `../lib/util.azs`），因此名称不随 include 顺序变化；位于所有根目录之外的文件（仅 `--unrestricted-includes` 允许）同样如此命名。这个名称也被占用或不存在相对路径时，最后才使用绝对路径。`execute(String)` 使用 `<source>`，单独编译的表达式使用 `<expression>`。这些名称保存在 AST 的 `sources` 表中，所以同一程序的 `compile` 与 `compile-json` 输出完全相同的带位置诊断。`compile-json` 将格式错误的 JSON 报告在其行列，没有 `sources` 表的 AST 以 JSON 文件名命名。

#### 多个错误与退出码

一次编译会一并报告彼此独立的错误。编译分阶段进行：预处理、词法分析、语法分析、语法分析后的基类初始化、extern 声明与函数 ID 检查、AST 结构检查、全局声明（类布局、extern、abstract 映射和全局变量）以及函数体。在一个阶段内，编译器出错后会恢复：预处理器从下一条指令或下一行继续，语法分析从下一个声明、类成员或语句继续，检查器从下一个类、全局项或函数继续。某一阶段报告了错误时编译随即结束，后续阶段不会处理有误的输入；例如存在语法错误时，程序其他位置的类型错误不会报告。每个阶段内的错误按源码顺序列出，被包含文件的错误位于其 `#include` 处。语法、AST、include 与宏的嵌套及长度上限，include 次数、源文件读取总量、预处理文本长度和记号数量上限，以及内部编译器错误，会立即停止编译。

默认最多报告 20 个错误；`--max-errors N` 修改上限，`0` 表示不限，`1` 表示在第一个错误处停止。若之后还有错误，列表以 `note: too many errors emitted (limit 20); stopping` 结尾。CLI 随后输出汇总行，例如 `Error: compilation failed: 2 errors`，不写出任何输出文件。退出码：成功为 0，程序或命令行有错误为 1，内部编译器错误为 2；未知选项等用法错误输出 `Error: 消息`。

内部编译器错误指编译器自身而非程序的故障，例如编译从源码解析的程序时出现意外的 Java 异常。其文本为 `internal compiler error: StackOverflowError`（异常及其消息），随后是 `note: this is a compiler bug; rerun with --debug-compiler for a stack trace`，最后的汇总行为 `Error: compilation failed: internal compiler error`。`--debug-compiler` 还会输出 Java 堆栈。

`compile-json` 先检查 AST 的结构：必需字段、编译器读取的 JSON 类型、位置、`sources` 表以及数值范围。每处违例都针对 JSON 文件报告并给出 JSON 指针，例如 `tree.json: error: Malformed script JSON at /body/fff/_funcfff0000/metadata/position: expected an integer, got "abc"`。此类 AST 引起的意外故障报告为最近带位置节点处的 `Malformed script JSON near main.azs:3:10`，退出码为 1；`--debug-compiler` 输出底层异常。

Java 调用方得到同样诊断的 `azertia.script.CompileException`，它是 `IllegalArgumentException`，`diagnostics()` 列出各条诊断，消息即其文本，`internal()` 表示是否为内部编译器错误。`AzScript.execute(...)` 与 `Compiler.compile(...)` 默认最多报告 20 个错误。请像本文开头的示例那样向两个步骤传入同一个 `azertia.script.Diagnostics`：只有这样，已解析程序上的意外故障才归为内部编译器错误；以其他实例编译的 AST 按外部提供的 JSON 处理。修改上限或[警告](#警告)时配置该实例，例如 `new Diagnostics().maxErrors(0)`。一个实例只服务一次编译；`Diagnostics.failFast()` 在第一个错误处停止。通过 `Compiler.compileExpression` 单独编译的表达式在第一个错误处停止。

#### 警告

警告指出能够编译、但很可能有误的代码。其格式与错误相同，严重级别为 `warning:`，末尾给出控制它的选项；警告从不改变编译产物：

```text
main.azs:4:9: warning: in function main: assignment used as a condition; use '==' to compare, or wrap the assignment in parentheses [-Wassign-in-condition]
```

| 警告 | 默认 | 报告对象 |
|---|---|---|
| `assign-in-condition` | 开启 | 直接写作 `if`、`while` 或 `for` 条件的普通 `=` 赋值，例如 `if (done = check())`，位置为其 `=`。确需赋值时加括号表明意图：`if ((done = check()))`。 |
| `unreachable-code` | 开启 | `return`、`break`、`continue`、各分支都会离开的 `if`/`else`、带 default 且各分段都返回的 switch，或不含 `break` 的 `while (true)`/`for (;;)` 循环之后的第一条语句；每个块只报告一次。与错误相同，赋值或 `++`/`--` 语句的位置为其运算符。 |
| `unused-variable` | 关闭 | 声明后从未使用的局部变量；读取或赋值都算使用。参数、以 `_` 开头的名字，以及为构造和析构而声明的对象（`C c;`、`C * p(args);`、buffer 和类型参数的值）不在此列。 |

`-Wall` 开启全部警告，`-W<名称>` 开启、`-Wno-<名称>` 关闭单个警告；指名某个警告的选项无论写在 `-Wall` 之前还是之后都优先，同一警告以最后一个选项为准。`-w` 关闭全部警告。`-Werror` 把已开启的警告作为错误报告，例如 `main.azs:4:9: error: in function main: ... [-Werror=assign-in-condition]`，编译随之失败。未知的警告名称属于用法错误（`Error: Unknown warning option: -Wxyz`）。

编译成功时警告写到标准错误，退出码为 0。编译失败时警告与错误按源码顺序一并列出，并计入汇总行，例如 `Error: compilation failed: 1 error, 1 warning`。这些检查在 AST 上进行，所以 `compile` 与 `compile-json` 报告相同的警告；没有源码位置的 AST 节点（例如手写 AST 中的节点）和编译器生成的代码从不产生警告。Java 调用方配置 `Diagnostics` 实例，例如 `new Diagnostics().allWarnings().warning("unreachable-code", false).warningsAsErrors(true)`，并在编译后读取 `diagnostics.warnings()`；`Diagnostics.WARNINGS` 列出全部警告。

## 运行错误与调试信息

编译时加 `-g` 可让运行错误包含函数名和源码位置；`compile` 和 `compile-json` 均默认不携带调试信息。独立解释器把消息和调用帧写到标准错误，退出码为 1：

```text
AzScript error: Division by zero
  at divide (main.azs:2:14)
  at main (main.azs:5:12)
```

调用帧从出错函数向外排列到调用方。没有调试信息时，脚本帧显示为 `  at function 0x0fff0002`。原生回调显示为 `  at native function 0x12340001`；`0x0abd` namespace 的内置函数不增加原生帧。最多保留 64 帧，其余以 `  ... N more frames` 报告。行列从 1 开始，列按 UTF-16 代码单元计数，因此补充平面的 Unicode 字符占两列。被包含文件保留自己的源码位置。

C++ 异常保留原类型和 `what()` 消息。`azertian::describe_error(error)` 返回 `what()` 加上匹配的调用帧，不含 CLI 的 `AzScript error: ` 前缀和末尾换行；必须在 `destroy()` 或再次进入运行时之前调用。`last_error_trace()` 返回含 `frames` 和 `omitted_frames` 的 `error_trace`；`format_error_trace(trace)` 只格式化调用帧，`clear_error_trace()` 清空正在收集及已发布的记录。记录是线程局部的，每次进入运行时（包括回调重入）都重新开始收集。清理保留原错误及其调用帧，后续析构错误不会替换它或向它追加调用帧；若清理是最先失败的操作，则保留其中的首个错误。

JNI 将原生错误消息和调用帧按 UTF-8 转为 Java 字符串，不改变异常映射。Java 回调抛出的 throwable 仍以同一个对象返回，不改写其消息。调用栈支持改变了公开 C++ ABI，C++ 宿主和原生插件须用配套头文件及库重新构建。

## 泛型

泛型类、函数及成员方法使用类型参数，共享一份编译后的正文；不同类型实参不分配新的公开函数 ID。类型参数代表完整类型，可传基础类型、`address`、类值、类指针、参数化类和 `buffer<T>`，不接受 `void`、`any` 或参数专用的 `C(*)`。

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

函数类型参数写在返回类型前面；调用既可显式指定 `identity<int>(3)`，也可根据实参推断。推断递归匹配参数化类型，不依据接收返回值的变量类型，不从 `null` 单独推断类型，也不为不一致的实参寻找共同父类；不能唯一确定时须显式指定。类必须写出实参，不支持 raw type 或菱形 `<>`。参数化类型具有不变性：必须是同一个泛型声明及相同类型实参，未用于字段的参数也不能忽略。非泛型类同样按名称区分，指针上转型继续有效。

上界写作 `<T extends Base>`（类值）或 `<T extends Base*>`（类指针），按声明的继承关系检查。上界可以使用更早声明的类型参数，如 `<A, B extends Box<A>*>`；首版不支持通配符、多上界、循环上界或以类型参数自身作为上界。无界 T 仅能存储、赋值、传参和返回；上界允许访问其保证存在的字段和方法，但不能把任意 Base 赋给 T，也不能把未知派生类值切片为 Base 值。未知 T 不支持算术、比较、delete、`T()`、`new T()`，也不能追加 `*` 或 `(*)`；需要指针时用 `C*` 作为实参。

`T local = expression;` 必须初始化。`T` 字段则保留具体类型的默认规则：标量先置零、false 或空串，address 和类指针先置 null；实际为类值时按字段顺序执行显式初始化或默认构造。先完成父类，再处理子类，字段初始化仍不读取构造参数。没有无参构造的类型可以参与纯传递操作；只有需要默认构造时才失败：当前单元或共享声明足以判断的情况编译报错，跨库正文未知的情况在实际构造时运行报错并清理。显式类值初始化不要求额外的无参构造。

继承写作 `class Child<A,B> : Base<B>`。类外实现写作 `T Box<T>::get()`，类参数自动进入作用域且名称须与声明一致；泛型方法写作 `<U> U Box<T>::echo(U value)`。extern 写作 `extern <U> U identity(U value):abcd0002;`；声明与定义按参数位置及上界匹配，允许类型参数名称不同。构造和析构使用所属类的类型参数，不声明额外类型参数。hint 库继续在类外实现，见 [泛型共享库](../docs/HINT_LINKING.md)。

执行时仅携带不可变的类型操作上下文：ABI 标签、返回类别、可选默认工厂及其绑定上下文，不包含类名或字段布局，不是新的 AZS 值类型，也不占用户参数或字段 slots。类参数在前、方法参数在后，`this` 仍在普通参数槽 0。对象登记保留析构所需上下文，复制、移动、返回、delete、错误清理及快照恢复都保留正确绑定。`T=address` 普通返回不转移对象所有权；`T=C*` 按对象返回规则处理，`T=C` 按现有类值规则复制或移交。

## 拥有型内联缓冲区

`buffer<T>` 是编译器内建的拥有型值，可用于局部变量、字段、参数、返回值及泛型实参。必须明确写出元素类型；不变性要求元素类型完全相同。默认值是长度和容量均为零的空缓冲区，不要求 T 有无参构造。不能声明 `buffer<T>*`、继承 buffer，或对它使用 new/delete；也不能放进 var/def 或原始 mem_get 存储。

```cpp
buffer<int> values;
values.reserve(4);
values.push(7);
values.resize(3);             // 新增两个零值
values.set(1, 9);
int first = values.get(0);
buffer<int> copy = values;    // 独立的深复制，保留容量
copy.set(0, 11);
```

成员 `length()` 和 `capacity()` 返回 int；`reserve(int)`、`set(int,T)`、`push(T)`、`resize(int)` 返回 void；`get(int)` 按值返回 T。它们借用接收者而不复制整个缓冲区，接收者和实参从左到右各求值一次。reserve 只在请求容量大于当前容量时扩容到请求值，不构造空余元素，也不倍增。push 和增长的 resize 要求容量已经足够；负数、越界和容量不足报运行错误。此版本内建 buffer 仅通过 get/set 访问元素，下标语法用于下面的类运算符。

复制、传参和普通返回遵守拥有值规则，临时值和当前调用拥有的返回值可移交。类字段中的 buffer、多层 `buffer<buffer<T>>` 及 buffer 中的类值都深复制；类指针元素只复制地址，不拥有目标。无无参构造的类型仍可 reserve、push 已有值和缩短；只有 resize 实际增长时才要求默认构造能力。

底层按容量分配连续 slots。标量、address、类指针和 buffer 元素的步长为 1；类值步长是含继承字段的直接字段数 N，第 i 个元素占 `[i*N,(i+1)*N)`。类字段里的嵌套值仍独立存储，不承诺 C++ 原生结构体的字节布局。每个类元素具有独立对象视图 ID；扩容、删除元素及整体替换使相关旧地址失效，不公开可用基址算出所有元素的指针。reserve 迁移不调用用户构造/析构，也不修复字段里保存的旧地址。

reserve 失败保留旧存储；push 失败保留长度；resize 增长失败逆序回滚本次新增元素。缩短和整体销毁逆序清理，析构失败仍回收其余元素，保留原错误或首个清理错误。set 使用逐字段赋值的基本异常保证。整体赋值先准备候选值，再清理旧内容并切换；旧析构报错仍完成切换后传播错误；若析构回调已销毁目标对象，则清理候选并停止，不写回失效存储。操作期间禁止重入修改，销毁请求延迟到操作退出，并使外层操作停止；执行预算耗尽也会完成必要底层释放。每块容量乘步长不得超过 1,048,576 slots，拥有值嵌套最多 64 层。

## 默认比较与类运算符

`value_compare<T>(left,right)` 要求一个显式类型实参，返回 -1、0 或 1。int 比较不通过相减；float/double 拒绝非有限值且正负零相等；string 按无符号 UTF-8 字节字典序比较，包含内嵌零字节；bool 为 false < true；address 和类指针按无符号地址比较。具体类值和 buffer 不提供默认比较，已知类型时报编译错误；泛型 T 可以传给它，在实际类型不支持时才报运行错误。自定义顺序可用普通 AZS 比较函数和 `reflect_invoke_function<int>(compareId,left,right)`，沿用反射边界，类值实参仍按值传递。

```cpp
class Number {
    int value;
    Number(int n) { value = n; }
    Number operator+(Number rhs) {
        Number result(value + rhs.value);
        return result;
    }
    int operator[](string key) { return value; }
    void operator[]=(string key, int next) { value = next; }
    int operator()(int scale) { return value * scale; }
}
```

支持二元 `+ - * /`、下标 getter `operator[]`、setter `operator[]=` 和调用 `operator()`，每类每种入口只能有一个签名。声明可以是 extern，也可在类外实现，如 `int Number::operator()(int scale) { return value * scale; }`；可使用所属类的泛型参数，运算符自身不能再声明方法类型参数。它们全部降为带隐式 this 的普通成员调用，exec 不保存运算符表。

算术只查找左侧类值的入口；下标和调用也接受类指针。遵守现有继承及静态成员查找，无右侧反向查找、隐式转换或普通函数重载。getter 恰好一个键参数，键类型不限；setter 可省略，否则须与 getter 使用相同键类型、第二个参数与 getter 返回类型相同且返回 void。`x[key] = value` 依次求值接收者、键、右值，各一次；下标复合赋值不支持。

getter 返回类值或 buffer 时得到副本，不能通过该副本继续写入，例如 `matrix[i][j] = x`、`items[i].field = x` 或 `items.get(i).field = x`。条件接收对象的两个分支也都要通过这一检查。应先读到明确类型的局部变量，修改后通过 setter/set 写回；getter 返回指针时继续按正常指针规则访问。无界 T 仍不开放任意算术或调用，只有已知参数化类或上界提供对应入口时才能解析。

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

可读 AST 保持 `metadata/ext/abstract/global-variable/body` 结构，并为带签名的宿主函数增加 `extern-signatures`。`global-variable` 先以字符串列出 `#gvar` 名称，再以带位置的 `{"name", "type", "initializer"?}` 对象列出每个带类型全局；`compile-json` 按与源码相同的规则检查并初始化它们。类程序额外保存类定义、字段顺序和成员表达式等编译信息；`compile-json` 会重新执行类型检查与降级。限定的父类调用写作 `{"t":"ctrl","call":"base-call","param":["Base","method",args...]}`，可附带 `type-args`。AST 还保存模块 hint、assume、外部声明、定义名称与实际定义编号；调用绑定与定义位置分开，支持完全相同的 ABD 往返。`abstract` 是 extern 优先的源码名称绑定：有 extern 时保存声明 ID，否则保存定义 ID；hint 库的本地定义仍使用 0000 占位，实际调用的 self-assume 地址在降级时生成。函数定义的独立位置由 `body` 的 namespace 和 `metadata.position/name` 保存；每个签名另保存 `return-type` 与 `param-types`。表达式对象有 `t: "ctrl" | "call"`、`call`、`param`，块是数组。

可读 switch AST 使用 `{"t":"ctrl","call":"switch","param":[value,sections]}`，sections 是数组。每个分段为 `{"t":"ctrl","call":"case","labels":[literal,...],"param":[body]}`，body 是语句数组，可附带 `default: true`；只有 default 的分段不带 case 标签。解析器保留各个标签，编译时把相邻的空分段与后续正文合并。`compile-json` 重新检查标签、作用域、跳转及贯穿路径。降级先对值求值一次，再使用一次性循环、同级 if 分段和平衡的短路标签比较；无论 default 在源码何处，都最后处理。隐藏局部变量保持源码 break/continue 的目标不变。

条件表达式写作 `{"t":"ctrl","call":"select","param":[condition,thenValue,elseValue]}`，恰好包含三个表达式参数。`compile-json` 重新检查布尔条件和分支共同类型，在降为 opcode 50 前插入所需的数值转换。

根节点的 `sources` 表对文件的每次实际展开的包含各有一项，例如 `{"name": "inc/lib.azs", "included-from": {"source": 0, "line": 2, "column": 1}}`；第 0 项是主文件，include 总是指向更早的项，被 `#pragma once` 跳过的 include 没有对应项。表达式对象、函数元数据、类、字段和 extern 签名带有原始文件中的 `_line/_column`，以及所在 `sources` 项的下标 `_file`（为 0 时省略）。函数元数据还以 `_end_line/_end_column` 记录结束的右花括号，加括号的赋值带有 `_parenthesized: true`。字面值和块不带位置，诊断使用最近的带位置祖先。这些字段用于诊断，以及在 `-g` 时生成 exec 调试记录，不写入 ABD 指令。compile-json 输入中的 `sources` 可以省略，此时所有位置都属于以输入文件命名的同一文件。位置均为正整数，`_file` 必须是表中的有效下标；该表最多 4097 项，即主文件加 4096 次 include 展开；`sources` 的每项命名一个非空、不超过 4096 个字符且不含控制字符或双向格式字符的文件，include 链最多 64 层。compile-json 在编译前检查这些结构，见[多个错误与退出码](#多个错误与退出码)。

可读类 AST 保存 `operators` 映射及方法的 `operator-kind`，下标读写和后缀调用使用 `index`、`index-set`、`invoke` 节点，空 buffer 使用 `buffer-new`。compile-json 重新检查类型和降级，保持 ABD 字节一致。

新生成的 exec/ABD 使用 `exec-version: 10`，与源码元数据的 `version` 分开。所有控制指令的 `c` 都为数字；可读 AST 仍保留源码名称、类型和操作名。下表是 exec JSON 检查视图，实际 ABD 的字段顺序及类型见 [Exec v10 二进制格式](../docs/EXEC_FORMAT.md)。

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
| 默认值 `c=37`（context_default） | `context`；默认标量、空 buffer 或调用绑定的类值工厂 |
| 泛型返回 `c=38`（return_typed） | `v`, `context`；检查实际标签并按类别返回 |
| 类型检查 `c=39`（check_type） | `v`, `context`；检查标签，表达式只求值一次 |
| 空 buffer `c=40`（buffer_new） | `context` 元素操作上下文 |
| buffer 操作 `c=41`（buffer_op） | `op`, `v`, `args`；操作 0…6 为 length/capacity/reserve/get/set/push/resize |
| 默认比较 `c=42`（value_compare） | `context`, `v1`, `v2` |
| 位运算/移位 `c=43…48` | `v1`, `v2`；依次为 bit_and/bit_or/bit_xor/shl/shr/ushr，操作数和结果均为 int |
| 显式转换 `c=49`（convert） | `type` 为目标 0/1/2/3/7，`v` 为源表达式 |
| 条件选择 `c=50`（select） | `v` 为条件，`then` 为真分支，`else` 为假分支 |

根、函数、签名和表达式记录都使用裸 `AbdStack`；每个字段本身有长度边界，固定记录不保存键名和已知的类型标签。函数列表、参数类型列表、参数表达式和块内语句也都是裸 stack。常量的实际类型不固定，使用单元素 `AbdArray` 保留 int/float/double/bool/string/void/address 标签；扩展元数据仍使用 `AbdMap`。JSON 中的裸常量和块数组在二进制中分别使用 opcode 0 和 1。Java 调用 `Compiler.compile()` 得到的 `ExecProgram` 可继续作为 `AcsObject` 检查，`toValue()` 输出新 wire 格式；从执行文件恢复检查视图使用 `ExecCodec.decode()`，不再直接把根当作 `AcsObject` 解码。

数字变量的编号规则：`gvs` 为全局变量数量；`-1` 指向模块的第一个全局变量，`-2` 指向第二个，以此类推。函数参数占 `0…param-count-1`，局部变量与编译器临时变量随后连续编号；每个函数单独编号，类方法的隐式 `this` 位于参数槽 `0`。变量编号不是 `alloc` 返回的堆地址，裸整数表达式仍为常量；例如 `{"t":0,"c":3,"v":0}` 才表示读取第一个参数/局部槽。

函数检查视图包含 `id/return-type/script/param-count/param-types/local-count/hidden-count/entry-kind`，使用 `-g` 时另含 `debug` 记录；根的 `debug-files` 列出调试文件，无 `-g` 时为空。`local-count` 只计局部和临时槽，不包含参数。每次调用创建独立槽数组，递归和宿主重入不共享局部值。块退出仍按原规则清理对象，并清空该块声明的局部槽，循环下次进入时重新初始化。全局槽总数及单次调用的参数加局部槽数量均不能超过 1,048,576；无效编号、跨越所属模块全局范围及未声明/已退出作用域的访问会被拒绝。

未声明类型的脚本参数在元数据中使用内部 `any` 类型；无界类型参数也擦除为 any，但只有泛型隐藏上下文非空时才允许 any 返回和 extern any 参数。`entry-kind` 为 0 表示普通入口，1 表示内部入口；上下文不占变量槽。AST 额外保存 `type-parameters`、上界、参数化类型字符串和显式 `type-args`。`param-types` 和 `return-type` 保留供直接 C++/JNI 调用的类型检查；根的 `extern-signatures` 保存宿主边界签名。解释器仅接受 exec v10 裸 stack 文件，不再读取 v9、v8 等旧版本或旧 Map 文件；源码或可读 AST 应重新编译。不识别的版本、opcode、额外/缺失字段、错误字段宽度或超过 128 层的结构在加载时拒绝。JNI 快照版本与 exec 版本独立，本次为 v9。

### insert 与模块全局偏移

`script::insert_script(bytes, length)` 追加数字格式模块时，将追加前的全局槽数量保存到该模块每个 `ofunction::global_offset`，并记录模块自己的 `global_count`。访问负数变量 `v` 时，实际全局下标为 `global_offset + (-int64(v) - 1)`。例如已有两个全局槽，插入模块的 `-1` 就访问下标 `2`；原模块的 `-1` 仍访问下标 `0`。offset 由加载器决定，不由编译产物预设。

执行时使用被调用函数保存的 offset，普通函数、构造、析构、内部创建函数和生命周期钩子都遵守这一规则。后续插入模块不会改变已有 offset。局部变量不应用全局偏移；块级对象所有权及返回转移不受影响。固定模块的普通函数 ID 冲突拒绝插入，错误写出十六进制 ID、已定义它的模块及解决办法（见 [hint 链接](../docs/HINT_LINKING.md)中的“独立编译的普通模块”）；hint 模块由加载器选择未占用的实际 namespace，生命周期钩子按模块单独保存。

装载及 insert 只装配，不执行 onload。宿主必须在调用之前显式 `flush()`；成功 insert 会使 `setup=false`，需要再次 flush。链接先完整验证再原子修正调用和析构引用，失败可补库重试；依赖先初始化，循环依赖内按装载顺序。onload 失败后只能关闭重建。关闭按已成功初始化顺序的逆序执行 pre-destroy，再清理全局对象。

类指针在 ABD 中擦除为 address，类型编号为 7；字面量对象的类型编号为 8；int32 仍为 0，动态 any 仍为 6。地址常量使用 `0xce200b` 标签、8 字节无符号小端值，exec JSON 视图为 `{"address":"无符号十进制"}`；例如 null 是 `{"address":"0"}`。只有对象返回指令按实际所有权转移对象，普通 address 或整数返回不转移。JNI v9 快照保存有序模块身份（原始字节、实际 namespace、hint、全局布局）、全局槽、堆（含字段中的字面量对象）和对象清理责任，仅可在已完成初始化且无执行中的状态保存和恢复。恢复先完整校验再原子替换，不重链接或调用旧对象析构；旧 exec 和快照不兼容，须重新编译全部模块；旧源码中用 int 保存的堆地址须改为 address。

## 修复验证与打包

`examples/parser-regressions.azs` 同时展示旧解析器容易拆错的字符串、嵌套调用、左结合减除、混合优先级、递归及控制流。运行后依次输出带转义的字符串、`15`、`10`、`17`、`120`、`26`、`false`、`true`，主函数返回 `146`；CLI runner 还会显示返回值及析构阶段的 `done`。迁移后的 `tse.azs` 也纳入编译与 ABD 往返测试。

归档修复了 `AchievePack.files` 值类型注解、空文件、前导 `00/ff` 字节丢失。新归档通过 `metadata.format=2` 标记字节封装；仍能读取结构有效的旧归档，但旧版打包时已经丢失的前导字节无法恢复。打包拒绝符号链接和目标包含自身，解包拒绝路径穿越、目录中的符号链接及覆盖已有文件。旧解释器/旧归档读取程序不能保证理解新扩展。
