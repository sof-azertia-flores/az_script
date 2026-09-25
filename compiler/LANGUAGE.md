# AzScript 编译器与语言说明

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

函数返回类型支持 `void/int/string/float/double/boolean` 和已声明的类名，`bool` 是 `boolean` 的别名。支持前向引用、递归与任意嵌套函数调用；普通函数位于顶层，成员方法可在类体或使用 `ClassName::method` 在类外定义。参数可以写 `a,b` 或 `int a,int b`，对象参数须显式写类名。局部变量也可写 `int x = 2`；带显式基础类型的局部变量必须同时给出初始化值。显式参数和局部类型参与编译期检查；无类型参数、`var`、`def` 和全局变量保持动态类型。函数返回类型还会由解释器验证。

块 `{}` 创建词法作用域，允许内层同名变量遮蔽外层；同一作用域重复声明报错。局部编号在整个函数内唯一，避免先前子块和兄弟块编号冲突。变量必须先声明，访问已结束作用域内的变量会编译失败。全局变量用 `#gvar` 声明，通常在 `__script_onload` 中初始化。函数参数按值传递，脚本函数调用会检查参数数量。

非 hint 程序保持 `__script_onload = 0`、`main = 0x0fff0000`、`__script_pre_destroy = 1`；钩子必须是无参数 `void` 函数。普通函数默认位于 `0xfff`，`#namespace 1234` 支持 1～4 位十六进制。函数定义可写 `int add(int a,int b):0003 {return a+b;}` 固定低16位编号；先预留显式编号再分配自动编号，固定入口不能用后缀改变编号。自动编号可能随源码改变，宿主应使用同次编译的 AST 映射。

`#namespace_hint NAME` 声明独立库；库内普通函数（包括 `main`）以0000占位，0/1保留给生命周期，自动编号从2开始。`#assume_hint NAME abcd` 为调用声明假定 namespace，运行时在 `flush()` 中重定位。一个编译单元及其有效 include 最多包含一个 namespace 或 namespace_hint 指令。完整规则及可运行类库示例见 [多文件库与 hint 链接](../docs/HINT_LINKING.md)。

七个内置函数无需声明即可直接调用：

| 名称 | 签名 | ID |
| --- | --- | --- |
| `print` | `void(any)` | `0x0abd0000` |
| `getDepth` | `int()` | `0x0abd0001` |
| `mem_free` | `boolean(int)` | `0x0abd0002` |
| `alloc` | `int(int)` | `0x0abd0003` |
| `make_free` | `void(int)` | `0x0abd0004` |
| `mem_send_up` | `void(int)` | `0x0abd0005` |
| `mem_get` | `any(int)` | `0x0abd0006` |

这些名称和 `0xabd` ID 是保留的，不能声明为脚本函数或外部函数；内置函数不会写入 AST 的 `abstract` 或 `extern-signatures`。

外部函数统一写作 `extern int custom_call(string, double):12340001;`，参数名可省略，类型必须明确；零参数写 `extern void notify():12340002;`，`void` 不能作为参数类型。ID 支持完整32位十六进制位模式，也支持 `0x` 前缀。旧 `#extern` 不再接受。声明可绑定宿主函数或 hint 库；同名 extern 与定义签名一致时可共存，调用优先绑定 extern。宿主必须用相同 ID 注册；保留 namespace 0000、0abd、0fff 及活动脚本 namespace 不能被宿主占用。

外部函数调用采用精确类型契约，不进行数值隐式转换：声明为 `double` 的参数不能接收 `int` 或 `float`。编译器会检查参数数量和能确定的表达式类型，并把外部函数返回类型继续用于嵌套调用检查；无法确定类型的动态值用于带签名参数时也会拒绝，需先通过显式类型声明让契约清楚。ABD 携带同一签名，解释器会在调用回调前再次检查真实参数，并在回调返回后立即检查真实返回类型，因此错误的宿主实现不会把不匹配值带入后续脚本。内置函数由编译器预声明，不能由脚本函数、`extern` 或手写 AST 覆盖；宿主函数仍必须使用带签名的 `extern`。

## 类、对象引用与析构

```cpp
class Point {
    int x;
    Point next; // 非拥有引用，默认 null

    Point(int value) { this.x = value; }
    int get() { return x; }
    ~Point() { print(x); }
}

Point create() {
    Point p(3);
    return p; // 转移到调用所在代码块
}

void main() {
    Point a = create();
    Point b = new Point(5);
    delete b;
} // 输出 5、3：先显式删除 b，再自动销毁 a
```

`class` 只能在顶层声明。字段必须标注基础类型或类名，按声明顺序各占一个 slot；对象保存为连续 slots 的起始地址，没有隐藏对象头。零字段类被拒绝。构造方法与类同名且没有返回类型；不声明构造时合成无参构造，声明带参构造后不会额外合成无参构造。析构为可选的 `~ClassName()`，无参数且不能返回值或直接调用。所有成员公开，本版不支持继承、多态、重载、静态成员或嵌套类。

`C x;`、`C x();` 和 `C x(args);` 创建由当前代码块管理的自动对象。`new C(args)` 创建手动对象，离开代码块不会自动销毁。`C y = x;`、赋值和传参仅复制引用；对象变量重赋值不改变先前创建对象的清理责任。只有带显式类类型的变量、参数、字段及返回值能访问成员；`var p = new C(); p.method();` 会因 `p` 的类型是动态 `any` 而失败，需写 `C p = new C();`。

所有字段先获得对应默认值：`int` 为 `0`、`float` 为 `0.0f`、`double` 为 `0.0`、布尔为 `false`、字符串为空串、类引用为 `null`。然后按声明顺序执行显式字段初始化表达式，最后执行构造正文。全部初始化代码位于实际构造实现的前缀，使用实现模块的全局和调用绑定；字段初始化中的名称按 `this`、成员、全局解析，不受构造参数遮蔽。类字段声明 `C child;` 只创建空引用，不自动构造子对象；可写 `C child = new C();`。这些引用不拥有成员对象，返回或销毁外层对象均不递归处理它们；如需释放，须在析构内显式 `delete child;`。

成员通过 `obj.field`、`obj.method(args)` 或 `this.member` 访问，支持连续访问和调用。类内优先查找局部变量和参数，然后查找成员，最后查找全局。接收对象及实参从左到右各求值一次。成员赋值支持 `=`，成员 `+=` 等复合赋值暂不支持。类引用只与结构等价的类引用或 `null` 做相等比较，不参与地址算术，整数 `0` 不能隐式作为对象。`null` 运行时编码为地址 `0`；成员访问先验证对象基地址，再计算字段偏移，空引用即使访问非零偏移字段也会报错。

自动对象按构造完成的逆序销毁，普通离块、`return`、`break` 和运行错误退出均执行清理。返回当前函数拥有的自动对象时，仅将直接返回对象的所有权移交给实际调用代码块；返回调用者的对象或 `this` 不改变原所有权。`return new C();` 的结果仍需手动删除。嵌套对象、局部变量中的其他别名和字段引用不随返回延长生命周期。

`delete expression;` 只接受静态类引用或 `null`，只能销毁由 `new` 创建的对象；通过别名删除同样有效。`delete null;` 无操作，删除自动对象、已失效地址或正在析构的对象报运行错误。删除后其他别名不会自动置空。地址仍是普通整数下标，地址复用不提供世代识别，程序必须避免使用悬空引用。

构造失败只释放该对象自身的 slots，不执行其析构；字段中另行创建的手动对象仍由程序管理。析构失败也会释放当前对象，并继续清理其他自动对象；已有运行错误时保留原错误，否则传播首个析构错误。析构受原有步数和调用深度限制，资源预算耗尽时不能保证执行完整用户析构，但底层 slots 仍会回收。脚本显式关闭时先运行销毁钩子，再清理全局作用域接管的自动对象，最后卸载函数；未删除的 `new` 对象不补调用户析构，但其 slots 与对象登记随脚本一起释放，宿主反复加载、销毁脚本不会累积泄漏。

旧 `alloc/mem_free/make_free/mem_send_up` 仍是底层内存接口。原始释放会注销对象登记，但不调用用户析构；对象语法应使用 `delete`。构造、析构和成员方法均是普通脚本函数，第一个隐式参数为 `int` 对象地址，类结构类型只供编译器使用。类赋值、传参、返回和声明签名按字段类型及顺序比较结构，忽略类名、字段名和方法；引用字段递归比较，循环结构通过已比较类型对终止。运行时不保存或检查类结构。跨模块使用共享类声明及 extern 成员，示例见 [多文件类库](../docs/HINT_LINKING.md)。可执行示例 [`examples/classes-regressions.azs`](examples/classes-regressions.azs) 成功返回 `0`。

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

`return` 可以从多层 if/while 中立即退出函数。`break` 退出当前最内层 `while`，只能写在循环体内。非 void 函数的每条控制路径都必须以 `return` 结束：`if` 需要两个分支都返回，`while` 只有在条件是字面量 `true`（或非零数值）且循环体内没有属于它的 `break` 时才视为不会正常结束，否则编译器报告 `non-void function can reach the end of its body without returning a value`。语句以分号结束；紧邻块结尾的最后一条语句可以省略分号。函数定义尾部的分号可选。没有 `for/continue`、数组下标、对象字面量、闭包或异常处理语法；遇到这些语法会拒绝编译。

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

源文件开头的 UTF-8 BOM 会被忽略，不影响首行指令。`return`、`if`、`else`、`while`、`break`、`def`、`var`、`class`、`new`、`this`、`null`、`delete`、所有类型名以及 `true/false` 是保留字，不能声明为函数、参数、变量、`#gvar`、`#define` 或 `extern` 的名字。

嵌套上限以 ABD 的 128 层容器嵌套为准：函数体从第 4 层开始，每个二元/一元运算、每个调用参数、每条语句和每个块各占一层，所以一个表达式大约可以串联 120 个运算符，或嵌套约 60 层块。超限时编译器报告所在函数和行列，例如 `In function main:2:8: Expression nesting exceeds the ABD limit of 128 levels`。语法递归和 JSON 容器另有 320 层防护上限，用于保护宿主；循环引用的 AST 会提前拒绝。错误后同一进程可以继续编译其他程序。

语法错误包含行列，符号错误包含函数名及行列。经过 include 或宏展开后，语法诊断的行列目前对应合并后的源码，并非精确映射回每个 include 文件；预处理本身的错误另包含处理目录与行号。

## AST 与 ABD 契约

可读 AST 保持 `metadata/ext/abstract/global-variable/body` 结构，并为带签名的宿主函数增加 `extern-signatures`。类程序额外保存类定义、字段顺序和成员表达式等编译信息；`compile-json` 会重新执行类型检查与降级。AST 还保存模块 hint、assume、外部声明、定义名称与实际定义编号；调用绑定与定义位置分开，支持完全相同的 ABD 往返。`abstract` 是 extern 优先的源码名称绑定：有 extern 时保存声明 ID，否则保存定义 ID；hint 库的本地定义仍使用 0000 占位，实际调用的 self-assume 地址在降级时生成。函数定义的独立位置由 `body` 的 namespace 和 `metadata.position/name` 保存；每个签名另保存 `return-type` 与 `param-types`。表达式对象有 `t: "ctrl" | "call"`、`call`、`param`，块是数组；`_line/_column` 仅用于诊断，不写入 ABD 指令。

新生成的 exec/ABD 使用 `exec-version: 5`，与源码元数据的 `version` 分开。所有控制指令的 `c` 都为数字；可读 AST 仍保留源码名称、类型和操作名。下表是 exec JSON 检查视图，实际 ABD 的字段顺序及类型见 [Exec v5 二进制格式](../docs/EXEC_FORMAT.md)。

| exec JSON 指令 | 字段 |
| --- | --- |
| 函数调用 `t=1` | `id`, `param`，二进制 opcode 2 |
| 读取 `t=0,c=3`（v） | `v` 整数槽号 |
| 声明 `c=4`（vd） | `v`，可选 `declared-type` 和 `val` |
| 赋值 `c=5`（vs） | `v`, `val` |
| 内存赋值 `c=6`（m，可读 AST 为 mov） | `v1` 左值、`v2` 值；左值仅允许变量或内置 mem_get 调用 |
| 返回 `c=7`（r） | 可选 `r` |
| 对象返回 `c=8`（ro） | `r`，返回前按实际所有权转移直接返回对象 |
| 对象地址 `c=9`（oa） | `v` 基地址表达式、`offset` 非负整数 |
| 构造完成 `c=10`（ob） | `v` 地址表达式、可选 `destructor` 析构函数 ID（无析构时省略，`0xffffffff` 是有效 ID）、`manual` 布尔值 |
| 对象删除 `c=11`（od） | `v` 地址表达式；只允许手动对象，0 无操作 |
| 二元 `c=12…24` | `v1`, `v2`；依次为 add/minus/multiply/divide/mod/gt/lt/eq/ne/ge/le/and/or |
| 一元 `c=25/26`（not/neg） | `v` |
| 条件 `c=27`（if） | `v`, `val`，可选 `else` |
| 循环 `c=28`（wi） | `v`, `val` |
| 退出循环 `c=29`（brk） | 无字段 |

根、函数、签名和表达式记录都使用裸 `AbdStack`；每个字段本身有长度边界，固定记录不保存键名和已知的类型标签。函数列表、参数类型列表、参数表达式和块内语句也都是裸 stack。常量的实际类型不固定，使用单元素 `AbdArray` 保留 int/float/double/bool/string/void 标签；扩展元数据仍使用 `AbdMap`。JSON 中的裸常量和块数组在二进制中分别使用 opcode 0 和 1。Java 调用 `Compiler.compile()` 得到的 `ExecProgram` 可继续作为 `AcsObject` 检查，`toValue()` 输出新 wire 格式；从执行文件恢复检查视图使用 `ExecCodec.decode()`，不再直接把根当作 `AcsObject` 解码。

数字变量的编号规则：`gvs` 为全局变量数量；`-1` 指向模块的第一个全局变量，`-2` 指向第二个，以此类推。函数参数占 `0…param-count-1`，局部变量与编译器临时变量随后连续编号；每个函数单独编号，类方法的隐式 `this` 位于参数槽 `0`。变量编号不是 `alloc` 返回的堆地址，裸整数表达式仍为常量；例如 `{"t":0,"c":3,"v":0}` 才表示读取第一个参数/局部槽。

函数检查视图包含 `id/return-type/script/param-count/param-types/local-count`；`local-count` 只计局部和临时槽，不包含参数。每次调用创建独立槽数组，递归和宿主重入不共享局部值。块退出仍按原规则清理对象，并清空该块声明的局部槽，循环下次进入时重新初始化。全局槽总数及单次调用的参数加局部槽数量均不能超过 1,048,576；无效编号、跨越所属模块全局范围及未声明/已退出作用域的访问会被拒绝。

未声明类型的脚本参数在元数据中使用内部 `any` 类型。`param-types` 和 `return-type` 保留供直接 C++/JNI 调用的类型检查；根的 `extern-signatures` 保存宿主边界签名。解释器仅接受 exec v5 裸 stack 文件，不再读取旧 Map 文件；源码或可读 AST 应重新编译。不识别的版本、opcode、额外/缺失字段、错误字段宽度或超过 128 层的结构在加载时拒绝。JNI 快照版本与 exec 版本独立，本次为 v5。

### insert 与模块全局偏移

`script::insert_script(bytes, length)` 追加数字格式模块时，将追加前的全局槽数量保存到该模块每个 `ofunction::global_offset`，并记录模块自己的 `global_count`。访问负数变量 `v` 时，实际全局下标为 `global_offset + (-int64(v) - 1)`。例如已有两个全局槽，插入模块的 `-1` 就访问下标 `2`；原模块的 `-1` 仍访问下标 `0`。offset 由加载器决定，不由编译产物预设。

执行时使用被调用函数保存的 offset，普通函数、构造、析构、内部创建函数和生命周期钩子都遵守这一规则。后续插入模块不会改变已有 offset。局部变量不应用全局偏移；块级对象所有权及返回转移不受影响。固定模块的普通函数 ID 冲突拒绝插入；hint 模块由加载器选择未占用的实际 namespace，生命周期钩子按模块单独保存。

装载及 insert 只装配，不执行 onload。宿主必须在调用之前显式 `flush()`；成功 insert 会使 `setup=false`，需要再次 flush。链接先完整验证再原子修正调用和析构引用，失败可补库重试；依赖先初始化，循环依赖内按装载顺序。onload 失败后只能关闭重建。关闭按已成功初始化顺序的逆序执行 pre-destroy，再清理全局对象。

类引用在 ABD 中仍为 int。只有对象返回指令按实际所有权转移对象，普通整数返回不转移。JNI v5 快照保存有序模块身份（原始字节、实际 namespace、hint、全局布局）、全局槽、堆和对象清理责任，仅可在已完成初始化且无执行中的状态保存和恢复。恢复先完整校验再原子替换，不重链接或调用旧对象析构；旧 exec 和快照不兼容。

## 修复验证与打包

`examples/parser-regressions.azs` 同时展示旧解析器容易拆错的字符串、嵌套调用、左结合减除、混合优先级、递归及控制流。运行后依次输出带转义的字符串、`15`、`10`、`17`、`120`、`26`、`false`、`true`，主函数返回 `146`；CLI runner 还会显示返回值及析构阶段的 `done`。迁移后的 `tse.azs` 也纳入编译与 ABD 往返测试。

归档修复了 `AchievePack.files` 值类型注解、空文件、前导 `00/ff` 字节丢失。新归档通过 `metadata.format=2` 标记字节封装；仍能读取结构有效的旧归档，但旧版打包时已经丢失的前导字节无法恢复。打包拒绝符号链接和目标包含自身，解包拒绝路径穿越、目录中的符号链接及覆盖已有文件。旧解释器/旧归档读取程序不能保证理解新扩展。
