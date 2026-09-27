# AzScript Basic Math Library

[中文](MATH.md) | English

[`math.azs`](math.azs) is an independently compiled pure-AzScript library declaring `#namespace_hint AZSCRIPT_MATH`, with no dependencies on other script libraries, JNI, or host callbacks. Clients include only [`math.include.azs`](math.include.azs), which contains `#assume_hint AZSCRIPT_MATH 4d41` and fully typed `extern` declarations, not implementation copies. Public functions use `math_`; `math__` is reserved for implementation details.

From a client under `examples/`, include the header by relative path:

```c
#include "../stdlib/math.include.azs"

double distance(double x, double y) {
    return math_hypot(x, y);
}

int main() {
    return math_floor(math_sqrt(81.0));
}
```

The `AZSCRIPT_MATH_INCLUDED` guard allows repeated inclusion in one compilation. Compile the implementation separately; clients should not include it. The full runnable example [`../examples/math-regressions.azs`](../examples/math-regressions.azs) returns 0 on success.

## Compilation and assembly

From the repository root, compile the library and client separately, then assemble and run:

```sh
./azscript compile compiler/stdlib/math.azs -o build/math.exec.abd \
    --ast build/math.ast.json --exec-json build/math.exec.json
./azscript compile compiler/examples/math-regressions.azs -o build/math-regressions.exec.abd
./build/native/interpreter/azscript-run build/math-regressions.exec.abd --insert build/math.exec.abd
```

Distributions include precompiled `stdlib/math.exec.abd` with matching `math.ast.json` and `math.exec.json`. After changing library source, rebuild from the distribution root:

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

`compile.sh` produces ABD and both JSON files by default; Windows uses `compile.cmd` and `run.cmd`. The standalone interpreter flushes automatically after assembly. Running only the client ABD fails linking with a missing `AZSCRIPT_MATH` dependency.

C++ and JNI hosts must assemble first and explicitly link. Here `client_bytes` and `math_bytes` contain the two ABD files:

```cpp
auto script = azertian::load_script(client_bytes.data(), client_bytes.size());
script->insert_script(math_bytes.data(), math_bytes.size());
script->flush();
auto math_namespace = script->namespace_for_hint("AZSCRIPT_MATH");
auto result = script->invoke(0x0fff0000);
script->destroy();
```

The Java equivalent follows. See the distribution usage guide for native loading and exception-safe closing:

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

`4d41` is only a compile-time assumption. The runtime selects a free namespace; hosts must not directly invoke `0x4d410002`. The header fixes each public low 16-bit number, such as `0002` for `math_pi()`. Combine it with the queried actual namespace. Internal numbers are not public API; duplicate loading of the same hint is prohibited.

## API

| Category | Functions | Return and notes |
| --- | --- | --- |
| Constants | `math_pi()`, `math_tau()`, `math_e()`, `math_ln2()`, `math_epsilon()` | `double`; epsilon is binary64 `2^-52` |
| Basic values | `math_abs(x)`, `math_sign(x)`, `math_min(a,b)`, `math_max(a,b)`, `math_clamp(x,lo,hi)` | `double`, except sign returns `int` |
| Common calculations | `math_square(x)`, `math_cube(x)`, `math_mean(a,b)`, `math_lerp(a,b,t)`, `math_hypot(a,b)` | `double`; hypot scales to avoid intermediate square overflow |
| Comparison | `math_is_close(a,b,relTol,absTol)`, `math_approximately_equal(a,b)` | `boolean`; relative and absolute tolerances |
| Integer comparison | `math_abs_int`, `math_min_int`, `math_max_int`, `math_clamp_int` | Checked `int` |
| Integer math | `math_gcd`, `math_lcm`, `math_positive_mod`, `math_floor_div`, `math_ceil_div` | Checked `int` |
| Integer predicates | `math_is_even(value)`, `math_is_odd(value)` | `boolean` |
| Sequences | `math_factorial(n)`, `math_fibonacci(n)` | Checked `int` |
| Powers and roots | `math_pow(base,intExponent)`, `math_int_pow(intBase,intExponent)`, `math_sqrt(x)` | pow/sqrt return `double`; int_pow returns checked `int` |
| Rounding | `math_trunc`, `math_floor`, `math_ceil`, `math_round`, `math_fraction` | First four return `int`; fraction returns `double` in `[0,1)` |
| Angles | `math_to_radians(degrees)`, `math_to_degrees(radians)` | `double` |
| Trigonometry | `math_sin`, `math_cos`, `math_tan` | Radian input, `double` result |
| Exponentials | `math_exp(x)`, `math_exp2(x)` | `double` |
| Logarithms | `math_log(x)`, `math_log2(x)`, `math_log10(x)`, `math_log_base(x,base)` | `double` |
| Hyperbolic | `math_sinh(x)`, `math_cosh(x)`, `math_tanh(x)` | `double` |

`math_round` rounds ties away from zero. `math_fraction(x)` is `x - floor(x)`, so negative inputs also produce `[0,1)`. `math_positive_mod(value, modulus)` requires a positive modulus and returns `[0,modulus)`.

`math_pow` handles integer exponents only, including negative exponents and the entire int32 range. `math_int_pow` accepts only nonnegative exponents; the runtime checks intermediate and final int32 values. Both define `0^0 = 1`.

Floating APIs take `double`; integer APIs take `int`; `math_pow(double base, int exponent)` mixes them. Extern calls require exact argument types and do not convert int/float to double. Write `math_sqrt(9.0)` or multiply an integer expression by `1.0`, as in `math_sqrt(count * 1.0)`. `var`/`def` remain dynamic and cannot serve as these typed extern arguments; arithmetic does not infer a fixed type for them. Declare explicit types, for example `int count = 9; double value = count * 1.0;`, then call `math_sqrt(value)`.

## Domains and failures

AzScript has no `throw` syntax or error-carrying return type. Detected domain errors deliberately trigger controlled arithmetic failure, reported as `Division by zero`; natural int32 overflow reports `Integer overflow`. Treat the following as API preconditions:

- Functions with `int` in their names, and gcd/lcm/mod/div/factorial/fibonacci/even/odd, require actual int arguments, as does the exponent of `math_pow`. Signatures are checked during compilation and linking; direct host calls still require correct types.
- `math_abs_int` and nonzero gcd/lcm inputs cannot be `-2147483648`, whose positive value is outside int32. The lcm result must also fit int32.
- `math_factorial` accepts 0..12; `math_fibonacci` accepts 0..46.
- `math_positive_mod` requires modulus > 0; both integer-division functions require nonzero divisors.
- `math_pow(0, negative)` is undefined. `math_int_pow` requires a nonnegative exponent. Multiplication overflow and nonfinite floating results remain runtime errors.
- `math_sqrt(x)` requires x >= 0. Logarithms require x > 0; arbitrary-base log also requires base > 0 and base != 1.
- Trunc's domain is `-2147483649 < x < 2147483648`; floor's is `-2147483648 <= x < 2147483648`; ceil's is `-2147483649 < x <= 2147483647`; round's is `-2147483648.5 < x < 2147483647.5`. Fraction inherits floor's range.
- Clamp requires lower <= upper; both is_close tolerances must be nonnegative.
- Trigonometric inputs require `abs(angle) <= 1e12`. Beyond this, binary64 input has lost substantial low-bit precision, and script range reduction is costly and misleading. Tan also rejects cosine near zero.
- `math_exp` supports inputs up to approximately `709.782712893384` and rejects results beyond finite double. Sufficiently small results round to `0.0`. `math_exp2(x)` requires x < 1024 and returns zero for x <= -1075.
- Sinh/cosh subtract ln(2) before exponentiation for large magnitudes, extending finite results to approximately `abs(x) = 710.475860073944`; larger values fail as nonfinite results. Tanh stably returns -1.0 or 1.0 for large magnitudes.

All public numeric APIs require finite inputs. The runtime rejects arithmetic producing nonfinite results, but comparison/pass-through paths such as min/max/sign cannot uniformly detect host-provided NaN/Infinity; behavior for these inputs is unspecified.

## Accuracy and execution cost

Sqrt uses at most 1100 Newton iterations, covering normal and subnormal doubles. Pow uses exponentiation by squaring with at most 32 iterations. Hypot scales first to avoid unnecessary overflow in `a*a + b*b`.

Sin/cos use range reduction and Taylor recurrences; exp/log use base-2 range reduction and series. They suit ordinary script calculations but do not promise correctly rounded results across all binary64 values like a system math library might. Regressions use absolute/relative tolerance `1e-10` for angles up to 1000; reduction error near 1e12 can approach `5e-5`. Compare transcendental results with `math_is_close`, not `==`.

Library functions are ordinary script functions consuming the same call's instruction and depth budgets. The declaration-only header consumes no client definition IDs. Runtime linking relocates calls to the library's actual namespace. Upgrade the shared header and ABD together to avoid signature or ID mismatches.
