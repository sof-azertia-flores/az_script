# AzScript 基础数学库

[`math.azs`](math.azs) 是独立编译的纯 AzScript 库，声明 `#namespace_hint AZSCRIPT_MATH`，不依赖其他脚本库、JNI 或宿主回调。调用方只包含 [`math.include.azs`](math.include.azs)；该头文件使用 `#assume_hint AZSCRIPT_MATH 4d41` 和完整类型的 `extern` 声明，不把实现复制到调用方。公开函数使用 `math_` 前缀，`math__` 前缀保留给内部实现。

相对于 `examples/` 中的调用脚本包含头文件：

```c
#include "../stdlib/math.include.azs"

double distance(double x, double y) {
    return math_hypot(x, y);
}

int main() {
    return math_floor(math_sqrt(81.0));
}
```

头文件带有 `AZSCRIPT_MATH_INCLUDED` include guard，同一次编译中可重复包含。实现文件只作为库独立编译，不应被调用方 include。完整可执行示例是 [`../examples/math-regressions.azs`](../examples/math-regressions.azs)，成功时返回 `0`。

## 编译与装配

在源码仓库根目录分别编译库和调用方，再装配执行：

```sh
./azscript compile compiler/stdlib/math.azs -o build/math.exec.abd \
    --ast build/math.ast.json --exec-json build/math.exec.json
./azscript compile compiler/examples/math-regressions.azs -o build/math-regressions.exec.abd
./build/native/interpreter/azscript-run build/math-regressions.exec.abd --insert build/math.exec.abd
```

发行包在 `stdlib/` 中附带预编译的 `math.exec.abd` 及配套 `math.ast.json`、`math.exec.json`，可以直接装配。修改库源码后，在发行包根目录重新编译：

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

`compile.sh` 默认同步输出 ABD 和两份 JSON；Windows 使用 `compile.cmd`、`run.cmd`。独立解释器装配后自动 `flush()`。未装载数学库时，调用方的链接会报告缺失 `AZSCRIPT_MATH`，不能只运行调用方 ABD。

C++ 和 JNI 宿主须先装配，再显式链接。以下 C++ 中 `client_bytes` 和 `math_bytes` 分别为两份 ABD 的字节数组：

```cpp
auto script = azertian::load_script(client_bytes.data(), client_bytes.size());
script->insert_script(math_bytes.data(), math_bytes.size());
script->flush();
auto math_namespace = script->namespace_for_hint("AZSCRIPT_MATH");
auto result = script->invoke(0x0fff0000);
script->destroy();
```

Java 对应流程如下；原生库加载与异常关闭的完整说明见发行包使用文档：

```java
AbdInvoker.loadScript(new File("math-regressions.exec.abd"));
try {
    AbdInvoker.insertScript(new File("math.exec.abd"));
    AbdInvoker.flush();
    int mathNamespace = AbdInvoker.namespaceForHint("AZSCRIPT_MATH");
    double pi = (Double) AbdInvoker.invoke((mathNamespace << 16) | 0x0002);
    Object result = AbdInvoker.invoke(0x0fff0000);
} finally {
    AbdInvoker.close();
}
```

`4d41` 只是编译期假定地址。运行时会分配可用 namespace，宿主不得直接调用 `0x4d410002`。公开函数的低 16 位编号由头文件固定，例如 `math_pi()` 为 `0002`；完整 ID 由查询到的实际 namespace 与该编号组成。内部函数编号不属于公开接口，同名 hint 库不能重复装载。

## API

| 分类 | 函数 | 返回与说明 |
| --- | --- | --- |
| 常量 | `math_pi()`、`math_tau()`、`math_e()`、`math_ln2()`、`math_epsilon()` | `double`；epsilon 是 binary64 的 `2^-52` |
| 基础数值 | `math_abs(x)`、`math_sign(x)`、`math_min(a,b)`、`math_max(a,b)`、`math_clamp(x,lo,hi)` | 除 sign 返回 `int` 外均返回 `double` |
| 常用计算 | `math_square(x)`、`math_cube(x)`、`math_mean(a,b)`、`math_lerp(a,b,t)`、`math_hypot(a,b)` | `double`；hypot 使用缩放避免中间平方溢出 |
| 比较 | `math_is_close(a,b,relTol,absTol)`、`math_approximately_equal(a,b)` | `boolean`；同时使用相对和绝对容差 |
| 整数比较 | `math_abs_int`、`math_min_int`、`math_max_int`、`math_clamp_int` | checked `int` |
| 整数数学 | `math_gcd`、`math_lcm`、`math_positive_mod`、`math_floor_div`、`math_ceil_div` | checked `int` |
| 整数判断 | `math_is_even(value)`、`math_is_odd(value)` | `boolean` |
| 数列 | `math_factorial(n)`、`math_fibonacci(n)` | checked `int` |
| 幂与根 | `math_pow(base,intExponent)`、`math_int_pow(intBase,intExponent)`、`math_sqrt(x)` | 前者与 sqrt 返回 `double`，int_pow 返回 checked `int` |
| 取整 | `math_trunc`、`math_floor`、`math_ceil`、`math_round`、`math_fraction` | 前四个返回 `int`；fraction 返回 `[0,1)` 的 `double` |
| 角度 | `math_to_radians(degrees)`、`math_to_degrees(radians)` | `double` |
| 三角函数 | `math_sin`、`math_cos`、`math_tan` | 输入为弧度，返回 `double` |
| 指数 | `math_exp(x)`、`math_exp2(x)` | `double` |
| 对数 | `math_log(x)`、`math_log2(x)`、`math_log10(x)`、`math_log_base(x,base)` | `double` |
| 双曲函数 | `math_sinh(x)`、`math_cosh(x)`、`math_tanh(x)` | `double` |

`math_round` 的中点向远离零的方向舍入。`math_fraction(x)` 定义为 `x - floor(x)`，所以负数的结果也位于 `[0,1)`。`math_positive_mod(value, modulus)` 要求正模数，因此结果也总在 `[0,modulus)`。

`math_pow` 只处理整数指数，但支持整个 int32 指数范围及负指数。`math_int_pow` 只接受非负指数，并让运行时检查 int32 中间结果和最终结果。两者都定义 `0^0 = 1`。

浮点 API 的参数均为 `double`；整数 API 的参数均为 `int`，`math_pow(double base, int exponent)` 混合使用两者。`extern` 调用要求实际参数类型精确匹配，不会把 `int` 或 `float` 自动转为 `double`。因此写 `math_sqrt(9.0)`，整数表达式可先乘以 `1.0`，例如 `math_sqrt(count * 1.0)`。`var` / `def` 保持动态类型，不能作为这些有类型的 extern 实参；参与运算后也不会推断出固定类型。调用方应从声明处使用明确类型，例如 `int count = 9; double value = count * 1.0;`，再调用 `math_sqrt(value)`。

## 输入域与失败方式

AzScript 当前没有 `throw` 或可携带错误的返回类型。库在检测到定义域错误时主动触发受控的算术错误，解释器会报告 `Division by zero`；自然发生的 int32 溢出会报告 `Integer overflow`。因此调用方应把以下条件当作 API 前置条件：

- 名称中含 `int` 的函数，以及 gcd、lcm、mod、div、factorial、fibonacci、even/odd 接收实际 `int`；`math_pow` 的指数也必须为 `int`。签名在编译和链接时校验，宿主直接调用时仍须传入正确类型。
- `math_abs_int`、非零的 gcd/lcm 输入不能是 `-2147483648`，因为其正值不能用 int32 表示。`math_lcm` 的结果也必须落在 int32 范围。
- `math_factorial` 接受整数 `0..12`；`math_fibonacci` 接受整数 `0..46`。
- `math_positive_mod` 的 modulus 必须大于零；两个整除函数的除数不能为零。
- `math_pow(0, negative)` 无定义；`math_int_pow` 的指数必须非负。乘法溢出或非有限浮点结果仍由运行时拒绝。
- `math_sqrt(x)` 要求 `x >= 0`；对数要求 `x > 0`；任意底对数还要求 `base > 0 && base != 1`。
- `math_trunc` 的有效域是 `-2147483649 < x < 2147483648`；floor 是 `-2147483648 <= x < 2147483648`；ceil 是 `-2147483649 < x <= 2147483647`；round 是 `-2147483648.5 < x < 2147483647.5`。fraction 继承 floor 的范围。
- `math_clamp` 要求 `lower <= upper`；`math_is_close` 的两个容差都必须非负。
- 三角函数限制为 `abs(angle) <= 1e12`。超过该范围时，binary64 输入已经丢失大量低位，脚本内约角既昂贵也会给出误导结果。tan 在 cosine 接近零时也会报定义域错误。
- `math_exp` 支持到约 `709.782712893384`，超过 finite double 上界时报错；足够小、结果舍入为零时返回 `0.0`。`math_exp2(x)` 要求 `x < 1024`，在 `x <= -1075` 时返回零。
- sinh/cosh 对大幅值先除去 `ln(2)` 再求指数，因此有限结果可延伸到 `abs(x)` 约 `710.475860073944`；再大时会报非有限结果。tanh 对大幅值直接稳定地返回 `-1.0` 或 `1.0`。

所有公开数值 API 的调用契约都要求 finite 输入。当前运行时会拒绝产生非有限结果的算术，但 `min`、`max`、`sign` 等只比较或原样返回的路径无法统一识别宿主传入的 NaN/Infinity，因此库不承诺这些输入的行为。

## 精度和运行成本

sqrt 使用最多 1100 次 Newton 迭代，覆盖 normal 和 subnormal double；pow 使用二进制平方，循环次数最多 32。hypot 先缩放，避免 `a*a + b*b` 的无意义中间溢出。

sin/cos 使用区间约化和 Taylor 递推，exp/log 使用以 2 为底的区间约化与级数。这些函数适合脚本中的普通计算，但不承诺像系统数学库那样对整个 binary64 范围正确舍入。角度绝对值不超过 `1000` 时回归使用 `1e-10` 绝对/相对容差；到 `1e12` 的约角误差可能接近 `5e-5`。检查结果时应使用 `math_is_close`，不应对超越函数使用 `==`。

库函数仍是普通脚本函数，消耗同一次调用的执行步数和调用深度预算。头文件只提供声明，不占用调用方的函数定义编号；运行时链接将调用重定位到库的实际 namespace。数学库升级时应成套更新共享头文件与 ABD，避免接口签名或编号错配。
