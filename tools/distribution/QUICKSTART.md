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

基础类型为 `int`、`float`、`double`、`boolean`（也可写 `bool`）、`string`；函数还可以返回 `void`。`int` 是有符号 32 位整数，小数默认是 `double`，`1.5f` 是 `float`。整数溢出和除零会报错。显式基础类型的局部变量必须初始化；`var` 和 `def` 声明动态变量。

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

函数支持前向调用和递归。参数可省略基础类型；对象参数必须写类名，才能访问其成员。块 `{}` 创建独立作用域，变量先声明再使用；内层可以遮蔽外层变量。非 `void` 函数的每条正常退出路径都必须返回值。支持 `if/else`、`while`、`break` 和 `return`，不支持 `for`、`continue`、闭包或异常捕获。

支持 `+ - * / %`、`== != < <= > >=`、`&& || !`、赋值和变量的 `+= -= *= /= %=`。乘除先于加减，比较先于逻辑运算；`&&`、`||` 短路求值。字符串可用单引号或双引号，支持 `\n`、`\t`、`\\`、`\"`、`\'`、`\uXXXX` 等转义。注释使用 `//` 或 `/* ... */`。

## 类、对象与析构

```cpp
class Point {
    int x;
    Point next;
    Point(int value) { this.x = value; }
    int get() { return x; }
    ~Point() { print("destroy " + x); }
}

Point create() {
    Point point(3);
    return point;
}

void main() {
    Point a = create();
    Point alias = a;
    alias.x = 7;
    print(a.get());
    Point b = new Point(5);
    delete b;
}
```

字段须显式标注类型，每个字段占一个 slot。数字默认是零、布尔是 `false`、字符串为空串、类字段是 `null`。构造方法与类同名、无返回类型；未声明构造时有默认无参构造，声明带参构造后不会另加无参构造。析构写作 `~Point()`，不能直接调用。类至少有一个字段，成员均公开，不支持继承、重载、静态成员或嵌套类。

`Point a(3);` 创建当前块拥有的自动对象，离开块时按构造完成的逆序析构。返回局部自动对象会将它交给调用代码块。`Point b = new Point(5);` 创建需要显式 `delete b;` 的手动对象。赋值、别名与传参只复制引用；类成员引用也不拥有另一个对象，销毁外层对象不会自动销毁其成员指向的对象。`delete null;` 无操作，删除自动对象或使用失效地址会报错。

访问成员使用 `object.field`、`object.method()`、`this.field`。接收对象和实参从左到右各求值一次。类内先找局部变量和参数，再找成员，最后找全局变量。成员赋值支持 `=`，暂不支持成员 `+=`。类引用支持结构等价的赋值及相等比较，也能与 `null` 比较；整数 `0` 不能代替 `null`。对象须写显式类类型，`var object = new Point(1); object.get();` 不能编译。

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

内置 `print(value)`、`getDepth()`、`alloc(count)`、`mem_get(address)`、`mem_free(address)`、`make_free(address)`、`mem_send_up(address)` 无需声明。低层内存写入使用 `mem_get(address) = value;`。类对象通常通过构造和 `delete` 管理，原始内存释放不会调用用户析构。

更多预处理、类型、生命周期、内存边界与 AST/ABD 说明见 [完整语言文档](LANGUAGE.md)，编译与嵌入步骤见 [使用文档](USAGE.md)，数学函数见 [数学库说明](../stdlib/MATH.md)。

## 多文件库

头文件使用 `#assume_hint MY_LIB abcd` 和 `extern int answer():abcd0002;` 声明调用；实现文件写 `#namespace_hint MY_LIB` 和 `int answer():0002 {return 42;}`。两者独立编译后，使用 `./run.sh main.exec.abd --insert library.exec.abd` 装配。C++ / JNI 宿主须在装配后显式 flush。

类库在共享头文件中声明 extern 构造、方法及析构，实现写在类外，如 `Point::Point(int value):0002 {this.x=value;}`。类型按有序字段结构比较，类名和字段名不影响等价。完整可运行例子位于 `examples/multifile/`，见 [多文件库文档](HINT_LINKING.md)。
