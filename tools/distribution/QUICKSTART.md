# AzScript 基本语法

源文件使用 UTF-8 和 `.azs` 后缀。下面的程序可以保存为 `hello.azs`，在发行包根目录运行 `./compile.sh hello.azs`，默认得到 `hello.exec.abd`、`hello.ast.json` 和 `hello.exec.json`，再运行 `./run.sh hello.exec.abd`。Windows 使用 `compile.cmd` 和 `run.cmd`。

```c
int add(int left, int right) { return left + right; }

int main() {
    int answer = add(20, 22);
    print("answer=" + answer);
    return answer;
}
```

## 变量、函数与控制流

基础类型为 `int`、`float`、`double`、`boolean`（也可写 `bool`）、`string`、`address`；函数还可以返回 `void`。`int` 是有符号 32 位整数，`address` 是独立的无符号 64 位 slot 地址，空地址写 `null`；二者不隐式转换。小数默认是 `double`，`1.5f` 是 `float`。整数溢出和除零会报错。显式基础类型的局部变量必须初始化；`var` 和 `def` 声明动态变量。

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

函数支持前向调用和递归。参数可省略基础类型；对象参数必须写类名，才能访问其成员。块 `{}` 创建独立作用域，变量先声明再使用；内层可以遮蔽外层变量。非 `void` 函数的每条正常退出路径都必须返回值。支持 `if/else`、`while`、`for`、`break`、`continue` 和 `return`，不支持闭包或异常捕获。

`for (int i = 0; i < 5; i++) { ... }` 将初始化变量限制在整个循环作用域内；初始化、条件和步进均可省略，空条件表示 true。`continue` 清理当前迭代的对象，再执行 for 步进并检查条件；在 while 中则直接重新检查条件。`break` 和 `return` 不执行步进。`++/--` 只支持简单数字或 address 变量的独立语句或 for 步进，不能作为返回值或实参。

支持 `+ - * / %`、`== != < <= > >=`、`&& || !`、赋值和变量的 `+= -= *= /= %=`。乘除先于加减，比较先于逻辑运算；`&&`、`||` 短路求值。字符串可用单引号或双引号，支持 `\n`、`\t`、`\\`、`\"`、`\'`、`\uXXXX` 等转义。注释使用 `//` 或 `/* ... */`。

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

字段须显式标注类型，每个字段占一个 slot。数字默认是零、布尔是 `false`、字符串为空串、address 和指针字段是 `null`；字面量对象字段（如 `Point pos(1);`）内嵌一个完整对象，未写实参时调用无参构造。构造方法与类同名、无返回类型；未声明构造时有默认无参构造，声明带参构造后不会另加无参构造。析构写作 `~Point()`，不能直接调用。类连同继承字段至少有一个字段，成员均公开，支持单继承，不支持多继承、虚函数、重载、静态成员或嵌套类。

`Point a(3);`（或 `Point a;`、`Point a();`）创建字面量对象：它是一个值，赋值、传参和返回都会复制全部字段，每个副本到期时各自析构；返回局部字面量对象直接交给调用方，不额外复制。`Point * p(3);` 创建离块自动销毁的指针对象，无参也要写 `Point * p();`。`Point * b = new Point(5);` 创建需要显式 `delete b;` 的手动对象。指针赋值、别名与传参只复制地址；指针字段不拥有目标对象。同一块中的对象按创建逆序析构，未绑定变量的临时对象在语句结束时析构。`delete` 只接受指针，`delete null;` 无操作，删除自动指针对象或使用失效地址会报错。脚本只能在方法中通过 `this`（类型 `Point *`）拿到字面量对象的地址，对象到期后再经它访问会报错。字面量对象不能与 `null` 比较、不能存进 `var` 或全局变量。

访问成员使用 `object.field`、`object.method()`、`this.field`。接收对象和实参从左到右各求值一次。类内先找局部变量和参数，再找成员，最后找全局变量。成员赋值支持 `=`，暂不支持成员 `+=`。指针支持结构等价的赋值及相等比较，也能与 `null` 比较；整数 `0` 不能代替 `null`。对象须写显式类类型，`var object = new Point(1); object.get();` 不能编译，应写 `Point * object = new Point(1);`。

类继承写作 `class Child : Base` 或 `class Child : public Base`。父类字段位于对象 slots 前部，子类字段追加在后；继承方法直接使用同一个对象地址。子类指针可赋给父类指针变量、参数或返回值（字面量对象不做切片），父类方法的调用按静态类型绑定；同名字段和方法可由子类遮蔽，没有虚函数派发。

```cpp
class TaggedPoint : Point {
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    ~TaggedPoint() { print("tag " + tag); }
}
```

省略 `: Point(value)` 时调用父类无参构造，父类不支持无参构造则编译报错。父类完成构造后，再初始化子类字段并执行子类构造正文。析构按子类、父类顺序执行；即使通过 `Point *` 删除 `new TaggedPoint(...)`，也会执行完整析构链。构造失败清理已完成构造的父类部分，不调用失败层级的析构。示例 `examples/inheritance-regressions.azs` 成功返回 `0`。

## include、宿主入口与内存

`#include` 相对于写下该指令的源文件解析；可以在发行包的 `examples/` 目录下使用数学库：

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

浮点 API 的实参必须为 `double`，如 `3.0`；整数 API 必须传 `int`。整数表达式可乘 `1.0` 后传给浮点 API。动态变量须先绑定到显式类型的局部变量。库以 `AZSCRIPT_MATH` hint 链接，头文件中的假定 namespace `4d41` 不代表实际运行地址。

全局变量由 `#gvar name` 声明，通常在 `void __script_onload()` 中赋值；`void __script_pre_destroy()` 在显式关闭脚本时执行。非 hint 程序的 `main` 固定为 `0x0fff0000`，这两个生命周期钩子分别为 `0` 和 `1`，均不能带参数。普通定义可用 `int function():0003 {return 1;}` 固定低16位编号；自动编号随源码改变；宿主需要按名字调用时，应保存与 ABD 同次生成的 `--ast`，读取其中的 `abstract` 映射。

宿主函数写完整签名，例如 `extern int host_add(int, int):0x12340001;`；宿主必须注册相同 ID 与对应参数和返回值。独立解释器没有应用专属的宿主回调，使用此类脚本时应嵌入 C++ 或 Java 宿主。

内置 `print(value)`、`getDepth()`、`alloc(count)`、`mem_get(pointer)`、`mem_free(pointer)`、`make_free(pointer)`、`mem_send_up(pointer)` 无需声明。`alloc(int)` 返回 address，后四个内存接口接收 address。例如 `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`，分配数量和偏移仍是 int。地址支持 `address + int`、`int + address`、`address - int`，以及地址间的无符号相等和大小比较；溢出和下溢报错，空地址使用 `p == null` 判断。地址不能与普通整数互相转换或作相等比较，也不能直接作为 if 条件。类指针仍保留静态类类型与继承规则，只在执行文件中擦除为 address；通常通过构造和 delete 管理，原始内存释放不会调用用户析构。

本发行包使用 exec v7 和 JNI 快照 v7，不读取旧执行文件或快照。所有模块须重新编译；旧代码中的 `int p = alloc(...)` 须改为 `address p = alloc(...)`，依赖引用语义的 `Point p(...)` 须改为 `Point * p(...)`，`Point p = new Point(...)` 改为 `Point * p = new Point(...)`。64 位地址目前仍表示 slot 位置，既有堆容量和生命周期规则不变。

更多预处理、类型、生命周期、内存边界与 AST/ABD 说明见 [完整语言文档](LANGUAGE.md)，编译与嵌入步骤见 [使用文档](USAGE.md)，数学函数见 [数学库说明](../stdlib/MATH.md)。

## 多文件库

头文件使用 `#assume_hint MY_LIB abcd` 和 `extern int answer():abcd0002;` 声明调用；实现文件写 `#namespace_hint MY_LIB` 和 `int answer():0002 {return 42;}`。两者独立编译后，使用 `./run.sh main.exec.abd --insert library.exec.abd` 装配。C++ / JNI 宿主须在装配后显式 flush。

类库在共享头文件中声明 extern 构造、方法及析构，实现写在类外，如 `Point::Point(int value):0002 {this.x=value;}`。类型按有序字段结构比较，类名和字段名不影响等价。完整可运行例子位于 `examples/multifile/`，见 [多文件库文档](HINT_LINKING.md)。
