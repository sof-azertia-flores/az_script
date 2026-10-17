# AzScript 基本语法

中文 | [English](QUICKSTART.en.md)

源文件使用 UTF-8 和 `.azs` 后缀。下面的程序可以保存为 `hello.azs`，在发行包根目录运行 `./compile.sh hello.azs`，默认得到 `hello.exec.abd`、`hello.ast.json` 和 `hello.exec.json`，再运行 `./run.sh hello.exec.abd`。Windows 使用 `compile.cmd` 和 `run.cmd`。

```c
int add(int left, int right) { return left + right; }

int main() {
    int answer = add(20, 22);
    print("answer=" + answer);
    return answer;
}
```

## 运行错误

使用 `./compile.sh -g hello.azs` 可携带函数名和源码位置，`-g` 默认关闭。运行失败时退出码为 1，并向标准错误输出，例如：

```text
AzScript error: Division by zero
  at divide (hello.azs:2:14)
  at main (hello.azs:5:12)
```

未加 `-g` 时帧显示为 `  at function 0x0fff0002`。原生回调使用 `  at native function 0x12340001`，内置函数不增加原生帧。出错函数排在前面，随后是调用方；最多保留 64 帧，其余以 `  ... N more frames` 报告。行和 UTF-16 列从 1 开始。宿主异常处理见[运行错误](LANGUAGE.md#运行错误与调试信息)。

## 变量、函数与控制流

基础类型为 `int`、`float`、`double`、`boolean`（也可写 `bool`）、`string`、`address`；函数还可以返回 `void`。`int` 是有符号 32 位整数，`address` 是独立的无符号 64 位 slot 地址，空地址写 `null`；二者不隐式转换。小数默认是 `double`，`1.5f` 是 `float`。整数算术溢出和除零会报错。显式基础类型的局部变量必须初始化；`var` 和 `def` 声明动态变量。

```c
int factorial(int n) {
    if (n <= 1) { return 1; }
    return n * factorial(n - 1);
}

void main() {
    var total = 0;
    def(oldStyle, 2);
    int i = 1;
    while (i <= 5) {
        total += factorial(i);
        if (total > 100) { break; }
        i += 1;
    }
    print(total);
}
```

函数支持前向调用和递归。参数可省略基础类型；对象参数必须写类名，才能访问其成员。块 `{}` 创建独立作用域，变量先声明再使用；内层可以遮蔽外层变量。非 `void` 函数的每条正常退出路径都必须返回值。支持 `if/else`、`while`、`for`、`switch`、`break`、`continue` 和 `return`，不支持闭包或异常捕获。

`for (int i = 0; i < 5; i++) { ... }` 将初始化变量限制在整个循环作用域内；初始化、条件和步进均可省略，空条件表示 true。`continue` 清理当前迭代的对象，再执行 for 步进并检查条件；在 while 中则直接重新检查条件。`break` 和 `return` 不执行步进。`++/--` 只支持简单数字或 address 变量的独立语句或 for 步进，不能作为返回值或实参。

支持算术 `+ - * / %`、比较 `== != < <= > >=`、布尔 `&& || !`、仅用于 int 的位运算 `& | ^ ~`、移位 `<< >> >>>`、条件选择 `?:` 和赋值。简单变量支持 `+= -= *= /= %= &= |= ^= <<= >>= >>>=`。优先级从高到低为：一元、乘除取余、加减、移位、大小比较、相等比较、`&`、`^`、`|`、`&&`、`||`、条件选择 `?:`、赋值；`&&`/`||` 短路，`?:` 只计算一个分支。移位采用 32 位位模式：左移回绕，`>>` 扩展符号，`>>>` 补零；数量超出 0…31 会报错，不取模。位测试应写成 `(flags & mask) != 0`。移位符号必须相邻（`a > > b` 无效），`buffer<buffer<int>>` 等嵌套泛型结束符仍然有效，详见[位运算](LANGUAGE.md#位运算与移位)。字符串可用单引号或双引号，支持 `\n`、`\t`、`\\`、`\"`、`\'`、`\uXXXX` 等转义。注释使用 `//` 或 `/* ... */`。

### switch

```c
int mode = 2;
switch (mode) {
    case 1:
    case 2:
        print("ready");
        break;
    default:
        print("unknown");
}
```

int/string 值只求值一次，case 标签必须是同类型字面量。连续标签共享有独立作用域的分段。不允许贯穿：非末尾分段必须显式离开，最后一个分段隐式 break。`default` 可在任意位置，只处理未匹配的值。break 退出最近的 switch/循环，continue 仍指向外围循环。动态值必须先显式转换再用于 switch，详见 [switch](LANGUAGE.md#switch)。

### 条件表达式

```c
boolean ready = true;
double value = ready ? 1 : 2.5; // double 1
```

`条件 ? a : b` 要求布尔条件，只计算一个分支，并且右结合；`a ? b : c ? d : e` 等同于 `a ? b : (c ? d : e)`。两个分支的类型必须兼容：数值按 double、float、int 取最宽类型，父类/子类指针取父类类型，指针可以与 null 配对。条件选择使用外围作用域，保持原有临时值和对象的生命周期。完整类型及写入规则见[条件表达式](LANGUAGE.md#条件表达式)。

### 显式转换

使用 `int(x)`、`float(x)`、`double(x)` 做数值转换或严格十进制字符串解析；`string(x)` 接受数值、address 或 string。`address(x)` 保留已有 address，或显式擦除类指针、`C (*)` 借用及指针上界 T，不改变所有权。每种形式恰好接受一个参数，只求值一次。布尔、类值、buffer、裸 null 及无界/值上界类型参数不能转换；其他类指针转换及整数/字符串转 address 均不支持。

`int(2.9)` 为 2，`int(2147483647.9)` 为 2147483647，`int("+7")` 为 7。数值转 int 先向零截断再检查范围。浮点转换可能舍入、下溢为零或次正规数，但溢出会失败。整数字符串只允许可选正负号和十进制数字；float/double 字符串还允许小数点和指数。解析不受区域设置影响，小数点固定为 `.`，拒绝空格、十六进制、后缀、NaN/Infinity 和尾部多余文字，即使参数是字符串字面量也如此。使用 `int(x)` 而不是 `(int)x`，使用比较而不是 `bool(x)`；详见[转换配对表](LANGUAGE.md#显式转换)。

## 字符串函数

```c
string text = "A😀中";
print(text.length());        // 3 个码点
print(text.byte_length());   // 8 个 UTF-8 字节
print(text[1]);              // 😀
print(text.substring(1, 3)); // 😀中
print(text.contains("中"));  // true
```

方法还包括 `char_at(i)`、`code_at(i)`、`index_of(needle[, from])`、`starts_with(prefix)` 和 `ends_with(suffix)`。下标从零开始，按 Unicode 码点计数，子串不包含 end 端点。搜索未找到返回 -1，from 默认为 0。空 needle 在 from 处匹配，空前缀/后缀总是匹配。`str_from_code(128512)` 构造 "😀"，NUL 同样合法。

字符串不可变：应把新字符串赋给变量，而不是给 `text[i]` 赋值。非法下标、子串范围会报错，Unicode 代理项码值和非法 UTF-8 被拒绝。对应的 `str_*` 内置函数不需要 include 或声明；方法名只支持 snake_case 形式。签名和边界规则见[字符串函数](LANGUAGE.md#字符串函数)。

## 类、对象与析构

```cpp
class Point {
    int x;
    Point * next;
    Point(int value) { this.x = value; }
    int get() { return x; }
    ~Point() { print("destroy " + x); }
}

Point create() {
    Point point(3);
    return point;
}

void main() {
    Point a = create();       // 字面量对象
    Point copy = a;           // 复制全部字段
    copy.x = 7;
    print(a.get());           // 3
    Point * scoped(4);        // 离块自动销毁的指针对象
    Point * b = new Point(5); // 手动对象
    delete b;
}
```

字段须显式标注类型，每个字段占一个 slot。数字默认是零、布尔是 `false`、字符串为空串、address 和指针字段是 `null`；字面量对象字段（如 `Point pos(1);`）内嵌一个完整对象，未写实参时调用无参构造。构造方法与类同名、无返回类型；未声明构造时有默认无参构造，声明带参构造后不会另加无参构造。析构写作 `~Point()`，不能直接调用。类连同继承字段至少有一个字段，成员均公开，支持单继承和虚方法，不支持多继承、重载、静态成员或嵌套类。

`Point a(3);`（或 `Point a;`、`Point a();`）创建字面量对象：它是一个值，赋值、传参和返回都会复制全部字段，每个副本到期时各自析构；返回局部字面量对象直接交给调用方，不额外复制。`Point * p(3);` 创建离块自动销毁的指针对象，无参也要写 `Point * p();`。`Point * b = new Point(5);` 创建需要显式 `delete b;` 的手动对象；单写 `Point * q;` 是空指针，等同 `Point * q = null;`。参数写成 `Point (*) p` 时函数同时接受 `Point *` 和字面量 `Point`：字面量对象按地址借入，不复制，函数内 `p` 就是 `Point *`。指针赋值、别名与传参只复制地址；指针字段不拥有目标对象。同一块中的对象按创建逆序析构，未绑定变量的临时对象在语句结束时析构。`delete` 只接受指针，`delete null;` 无操作，删除自动指针对象或使用失效地址会报错。脚本只能通过方法中的 `this` 或 `Point (*)` 参数（类型均为 `Point *`）拿到字面量对象的地址，对象到期后再经它访问会报错。字面量对象不能与 `null` 比较、不能存进 `var` 或全局变量。

访问成员使用 `object.field`、`object.method()`、`this.field`。接收对象和实参从左到右各求值一次。类内先找局部变量和参数，再找成员，最后找全局变量。成员赋值支持 `=`，暂不支持成员 `+=`。类按名称区分：指针只能在同一个类之间赋值和相等比较，子类指针可以转为父类指针，字段布局相同的其他类不能互换；指针也能与 `null` 比较；整数 `0` 不能代替 `null`。对象须写显式类类型，`var object = new Point(1);` 声明本身被拒绝，应写成 `Point * object = new Point(1);`。类指针不能进入 var/def、#gvar、无类型参数或原始内存存储；应保留明确类型，或在需要地址数据时使用 `address(object)`。

类继承写作 `class Child : Base` 或 `class Child : public Base`。父类字段位于对象 slots 前部，子类字段追加在后；继承方法直接使用同一个对象地址。子类指针可赋给父类指针变量、参数或返回值（字面量对象不做切片），非虚方法调用按静态类型绑定，同名字段和方法可由子类遮蔽；在类函数中可用 `Base::method(args)` 对 `this` 调用被遮蔽的父类方法。

```cpp
class TaggedPoint : Point {
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    ~TaggedPoint() { print("tag " + tag); }
}
```

省略 `: Point(value)` 时调用父类无参构造，父类不支持无参构造则编译报错。父类完成构造后，再初始化子类字段并执行子类构造正文。析构按子类、父类顺序执行；即使通过 `Point *` 删除 `new TaggedPoint(...)`，也会执行完整析构链。构造失败先逆序清理已完成的拥有型字段，再清理已完成的父类，不调用失败类自身的析构正文。字段清理失败也不会跳过其余字段/父类，或替换原错误及其调用栈。示例 `examples/inheritance-regressions.azs` 成功返回 `0`。

父类可声明 `virtual int score() { ... }`，子类用 `override int score() { ... }` 覆盖。指向该子类的 `Base *` 调用会选择子类实现；字面量值调用和 `Base::score()` 仍静态绑定。`virtual final`/`override final` 禁止进一步覆盖，构造/析构调用使用当前类的方法表。表不占用户字段，复制和快照保留绑定。修饰符写在普通非泛型成员方法声明上，详见 [虚方法](LANGUAGE.md#虚方法)。

## include、宿主入口与内存

`#include` 先相对于写下该指令的源文件解析，再查找各 `-I` 目录和发行包的 `stdlib/`；除非指定 `--unrestricted-includes`，include 不能离开这些目录。在发行包的 `examples/` 目录下，数学库头文件是 `../stdlib/math.include.azs`；在任意位置也可直接写 `#include "math.include.azs"`：

```c
#include "../stdlib/math.include.azs"

int main() { return math_round(math_hypot(3.0, 4.0)); }
```

调用方只 include 声明。数学库作为独立 ABD 装配，发行包已附带 `stdlib/math.exec.abd`；修改库源码后可重新编译。下面的回归示例成功返回 `0`：

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

浮点 API 的实参必须为 `double`，如 `3.0`；整数 API 必须传 `int`。可使用 `double(value)` 将整数或动态数值实参转为浮点 API 所需类型。库以 `AZSCRIPT_MATH` hint 链接，头文件中的假定 namespace `4d41` 不代表实际运行地址。

容器库同样只 include 声明。`List<int> * xs = new List<int>();` 创建链表，`delete xs;` 释放节点。`Vector<int> * xs = new Vector<int>();` 是连续数组，`Set` 和 `Map` 按默认比较保持有序。发行包附带 `stdlib/containers.exec.abd`：

```sh
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

该示例成功返回 `0`。假定 namespace `c071` 不是实际地址。说明见 [容器库](../stdlib/CONTAINERS.md)。

带类型的全局在顶层声明：`int total;`、`string label = "a";` 或 `Point * head = new Point(1);`（可用标量、address 和类指针；类的值、buffer 及 `Point * g(1);` 会被拒绝）。它们与带类型的局部变量一样检查，在模块 flush 时于 onload 中初始化：先设默认值，再按声明顺序执行初始化表达式，最后执行 `void __script_onload()` 正文，缺少时由编译器添加。初始化表达式在 onload 内执行，它们保存的 `alloc()` 块或自动对象在 onload 返回时失效，请用 `new`。`#gvar name` 声明无类型全局，通常在 onload 中赋值；`void __script_pre_destroy()` 在显式关闭脚本时执行。非 hint 程序的 `main` 固定为 `0x0fff0000`，这两个生命周期钩子分别为 `0` 和 `1`，均不能带参数。普通定义可用 `int function():0003 {return 1;}` 固定低16位编号，也可省略编号，从共享头文件中本模块 namespace 的 extern 声明继承完整 ID；自动编号随源码改变；宿主需要按名字调用时，应保存与 ABD 同次生成的 `--ast`，读取其中的 `abstract` 映射。

宿主函数写完整签名，例如 `extern int host_add(int, int):0x12340001;`；宿主必须注册相同 ID 与对应参数和返回值。独立解释器没有应用专属的宿主回调，使用此类脚本时应嵌入 C++ 或 Java 宿主。

内置 `print(value)`、`getDepth()`、`alloc(count)`、`mem_get(pointer)`、`mem_free(pointer)`、`make_free(pointer)`、`mem_send_up(pointer)` 和 `load_extern_library(name)` 无需声明。`load_extern_library` 加载当前系统的已签名动态库，见 [外部动态库](EXTERN_LIBRARY.md)。`alloc(int)` 返回 address，后四个内存接口接收 address。例如 `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`，分配数量和偏移仍是 int。地址支持 `address + int`、`int + address`、`address - int`，以及地址间的无符号相等和大小比较；溢出和下溢报错，空地址使用 `p == null` 判断。地址仍不能与普通整数互相转换或作相等比较，也不能直接作为 if 条件；`address(a)` 保留 address，`address(p)` 擦除类指针且不改变所有权，`string(a)` 格式化地址的无符号值。类指针仍保留静态类类型与继承规则，只在执行文件中擦除为 address；通常通过构造和 delete 管理，原始 mem_get/free/make_free/send_up 和 JNI 原始写入/释放拒绝对象存储，擦除为 address 后也不例外；对象使用带类型成员及 delete。宿主指针仍受信任，旧别名、地址复用和自动对象逃逸仍需由调用方避免，见[已知限制](LANGUAGE.md#对象存储与已知限制)。

本发行包使用 exec v10 和 JNI 快照 v10，不读取旧执行文件或快照。所有模块须重新编译；编译时加 `-g` 可在执行文件中写入函数名和源码位置，默认执行文件不含这些名称；旧代码中的 `int p = alloc(...)` 须改为 `address p = alloc(...)`，依赖引用语义的 `Point p(...)` 须改为 `Point * p(...)`，`Point p = new Point(...)` 改为 `Point * p = new Point(...)`。64 位地址目前仍表示 slot 位置，既有堆容量和生命周期规则不变。

更多预处理、类型、生命周期、内存边界与 AST/ABD 说明见 [完整语言文档](LANGUAGE.md)，编译与嵌入步骤见 [使用文档](USAGE.md)，数学函数见 [数学库说明](../stdlib/MATH.md)，容器见 [容器库说明](../stdlib/CONTAINERS.md)。

## 多文件库

头文件使用 `#assume_hint MY_LIB abcd` 和 `extern int answer():abcd0002;` 声明调用；实现文件写 `#namespace_hint MY_LIB` 和 `int answer():0002 {return 42;}`。两者独立编译后，使用 `./run.sh main.exec.abd --insert library.exec.abd` 装配。C++ / JNI 宿主须在装配后显式 flush。

类库在共享头文件中声明 extern 构造、方法及析构，实现写在类外，如 `Point::Point(int value):0002 {this.x=value;}`。编译单元内类按名称区分；执行文件不含类名，跨模块时这份共享声明就是不受检查的约定，各模块应 include 同一份头文件。完整可运行例子位于 `examples/multifile/`，见 [多文件库文档](HINT_LINKING.md)。
