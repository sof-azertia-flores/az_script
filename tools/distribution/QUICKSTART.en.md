# AzScript Basic Syntax

[中文](QUICKSTART.md) | English

Source files use UTF-8 and `.azs`. Save this program as hello.azs and run `./compile.sh hello.azs` from the distribution root. It produces hello.exec.abd, hello.ast.json, and hello.exec.json by default. Then run `./run.sh hello.exec.abd`. Windows uses compile.cmd and run.cmd.

```c
int add(int left, int right) { return left + right; }

int main() {
    int answer = add(20, 22);
    print("answer=" + answer);
    return answer;
}
```

## Runtime errors

Compile with `./compile.sh -g hello.azs` to include function names and source locations; `-g` is off by default. A runtime failure exits with status 1 and writes to stderr, for example:

```text
AzScript error: Division by zero
  at divide (hello.azs:2:14)
  at main (hello.azs:5:12)
```

Without `-g`, a frame reads `  at function 0x0fff0002`. Native callbacks use `  at native function 0x12340001`; built-ins add no native frame. The failing function comes first, followed by callers, with at most 64 frames and `  ... N more frames` for any remainder. Lines and UTF-16 columns start at 1. See [runtime errors](LANGUAGE.en.md#runtime-errors-and-debug-info) for host exception handling.

## Variables, functions, and control flow

Basic types are int, float, double, boolean (also bool), string, and address; functions may also return void. Int is signed 32-bit; address is an independent unsigned 64-bit slot address with null as its empty value. They do not implicitly convert. Decimals default to double; 1.5f is float. Arithmetic integer overflow and division by zero are errors. Explicit primitive locals require initialization; var and def declare dynamic variables.

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

Functions support forward calls and recursion. Primitive parameter types may be omitted, but object parameters need explicit class names for member access. Blocks create scopes; declare variables before use, with inner shadowing allowed. Every normal exit from a nonvoid function must return. If/else, while, for, switch, break, continue, and return are supported; closures and exception catching are not.

`for (int i = 0; i < 5; i++) { ... }` scopes i to the loop. Initializer, condition, and step are optional; an omitted condition is true. Continue cleans the current iteration's objects, then runs a for step and checks its condition; while continue directly rechecks the condition. Break and return skip the step. ++/-- work only on simple numeric/address variables as standalone statements or for steps, never results or arguments.

Operators include arithmetic `+ - * / %`, comparison `== != < <= > >=`, boolean `&& || !`, int-only bitwise `& | ^ ~`, shifts `<< >> >>>`, conditional `?:`, and assignment. Simple variables support `+= -= *= /= %= &= |= ^= <<= >>= >>>=`. From higher to lower precedence: unary, multiply/divide/remainder, add/subtract, shifts, ordering, equality, `&`, `^`, `|`, `&&`, `||`, conditional `?:`, assignment. `&&`/`||` short-circuit; `?:` evaluates one arm. Shifts use 32-bit patterns: left shift wraps, `>>` sign-extends, `>>>` zero-fills; counts outside 0…31 fail instead of wrapping. Write `(flags & mask) != 0` for a bit test. Shift characters must touch (`a > > b` is invalid); nested generic closers such as `buffer<buffer<int>>` still work. See [bitwise operators](LANGUAGE.en.md#bitwise-and-shift-operators). Strings use single/double quotes and escapes including `\n`, `\t`, `\\`, `\"`, `\'`, and `\uXXXX`. Comments use // or /* ... */.

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

The int/string value is evaluated once; case labels must be matching-type literals. Stacked labels share a scoped section. There is no fall-through: non-final sections must leave explicitly, and the final section has an implicit break. `default` may appear anywhere and handles only unmatched values. Break exits the nearest switch/loop; continue still targets an enclosing loop. Convert dynamic values explicitly before switching. See [switch](LANGUAGE.en.md#switch).

### Conditional expressions

```c
boolean ready = true;
double value = ready ? 1 : 2.5; // double 1
```

`condition ? a : b` requires a boolean condition and evaluates only one arm. It is right-associative; `a ? b : c ? d : e` means `a ? b : (c ? d : e)`. Both arms need compatible types: numeric types promote to the widest of double, float, int; related base/derived pointers choose the base, and a pointer may pair with null. Selection uses the surrounding scope and preserves ordinary temporary/object lifetimes. See [conditional expressions](LANGUAGE.en.md#conditional-expressions) for the full type and write rules.

### Explicit conversions

Use `int(x)`, `float(x)`, or `double(x)` for numeric conversion or strict decimal string parsing; `string(x)` accepts numeric, address, or string values. `address(x)` is only identity for an existing address. Each takes one argument, evaluated once. Booleans, class values/pointers, buffers, null, and type parameters cannot be converted, and integers/strings cannot become addresses.

`int(2.9)` is 2, `int(2147483647.9)` is 2147483647, and `int("+7")` is 7. Numeric-to-int truncates toward zero before checking its range. Floating conversion can round and underflow to zero or a subnormal, but overflow fails. Integer strings allow only an optional sign and decimal digits; float/double strings also allow a point and exponent. Parsing uses `.` regardless of locale and rejects spaces, hex, suffixes, NaN/Infinity, and trailing text, even for literal string arguments. Use `int(x)`, not `(int)x`, and a comparison instead of `bool(x)`. See the [conversion matrix](LANGUAGE.en.md#explicit-conversions).

## Classes, objects, and destruction

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
    Point a = create();       // Literal value
    Point copy = a;           // Copy all fields
    copy.x = 7;
    print(a.get());           // 3
    Point * scoped(4);        // Automatic pointer object
    Point * b = new Point(5); // Manual object
    delete b;
}
```

Fields require explicit types and each occupies one slot. Numbers default to zero, booleans false, strings empty, addresses/pointers null. Literal fields such as `Point pos(1);` embed complete objects; omitted arguments call the default constructor. Constructors share the class name and omit a return type. No constructor declaration supplies a default constructor; a parameterized declaration suppresses that default. Destructors use `~Point()` and cannot be called directly. A class needs at least one own/inherited field. All members are public; single inheritance is supported, but multiple inheritance, virtual methods, overloads, static members, and nested classes are not.

`Point a(3);`, `Point a;`, and `Point a();` create literal values. Assignment, arguments, and returns copy all fields; each copy destructs independently. Returning an owned local transfers directly without an extra copy. `Point * p(3);` creates an automatic pointer object; zero arguments require `Point * p();`. `Point * b = new Point(5);` requires explicit delete. `Point * q;` equals a null-pointer declaration. A `Point (*) p` parameter accepts either a pointer or a literal, borrowing the latter's address without copying; p is Point * inside the function. Pointer assignment/aliases/arguments copy only addresses; pointer fields are non-owning. Block objects destruct in reverse creation order; unbound temporaries at statement end. Delete accepts only pointers; delete null is a no-op. Deleting automatic pointer objects or using expired addresses fails. Only this in methods and Point (*) borrows expose literal addresses, which fail after expiration. Literal values cannot compare to null or occupy var/globals.

Access members with object.field, object.method(), or this.field. Receiver/arguments evaluate left to right once. Lookup prefers locals/parameters, then members, then globals. Member assignment supports =, not +=. Classes are nominal: pointers assign and compare only within one class, a derived pointer converts to a base pointer, and another class with the same field layout is not interchangeable; pointers also compare to null; integer 0 is not null. Explicit class types are required: `var object = new Point(1); object.get();` fails; write `Point * object`.

Inheritance uses `class Child : Base` or `class Child : public Base`. Base slots come first, derived slots follow; inherited methods use the same address. Derived pointers convert to base pointers in variables, parameters, and returns, with no literal slicing. Calls bind statically, names may be shadowed, and there is no virtual dispatch; inside class functions, `Base::method(args)` calls the base method on `this` even when it is shadowed.

```cpp
class TaggedPoint : Point {
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    ~TaggedPoint() { print("tag " + tag); }
}
```

Omitting `: Point(value)` calls the base default constructor, failing compilation if none exists. The base completes before derived initialization/body. Destruction is derived-to-base, including deletion of new TaggedPoint through Point *. Failed construction cleans completed bases without destructing the failed level. `examples/inheritance-regressions.azs` returns 0.

## Includes, host entry points, and memory

Includes resolve relative to the containing source file, then in each `-I` directory and the package's stdlib/, and must stay inside those directories unless `--unrestricted-includes` is given. Under distribution examples/ the math header is `../stdlib/math.include.azs`; from anywhere, `#include "math.include.azs"` also works:

```c
#include "../stdlib/math.include.azs"

int main() { return math_round(math_hypot(3.0, 4.0)); }
```

Clients include declarations only. The independently assembled math ABD is bundled at stdlib/math.exec.abd and can be rebuilt. This regression returns 0:

```sh
./compile.sh stdlib/math.azs
./compile.sh examples/math-regressions.azs
./run.sh examples/math-regressions.exec.abd --insert stdlib/math.exec.abd
```

Floating APIs require double, e.g. 3.0; integer APIs require int. Use `double(value)` to convert integer or dynamic numeric arguments for floating APIs. The library links through AZSCRIPT_MATH; assumed namespace 4d41 is not its actual runtime address.

The container library is also declarations-only. `List<int> * xs = new List<int>();` creates a list, and `delete xs;` releases its nodes. `Vector<int> * xs = new Vector<int>();` is a contiguous array. `Set` and `Map` stay ordered by the default comparison. The package includes stdlib/containers.exec.abd:

```sh
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

That example returns 0. Assumed namespace c071 is not the runtime address. See [containers](../stdlib/CONTAINERS.en.md).

Declare typed globals at the top level: `int total;`, `string label = "a";` or `Point * head = new Point(1);` (scalars, address and class pointers; class values, buffers and `Point * g(1);` are rejected). They are checked like typed locals and initialized in onload when the module is flushed: defaults first, then initializers in declaration order, then the body of `void __script_onload()`, which the compiler adds if missing. Initializers run inside onload, so `alloc()` blocks or automatic objects they keep die when it returns; use `new`. `#gvar name` declares an untyped global, usually assigned in onload. `void __script_pre_destroy()` runs on explicit close. Non-hint main is fixed at 0x0fff0000 and hooks at 0/1. Ordinary definitions can fix low numbers with `int function():0003 {return 1;}`, or omit the number and inherit the full ID of an extern in the module's own namespace declared in a shared header. Automatic numbers change with source; name-based host calls need the matching --ast abstract map.

Host declarations require complete signatures, e.g. `extern int host_add(int, int):0x12340001;`, matching host IDs, arguments, and results. The standalone interpreter has no application-specific callbacks; embed C++ or Java for these scripts.

Built-ins print, getDepth, alloc, mem_get, mem_free, make_free, mem_send_up, and load_extern_library need no declaration. [External libraries](EXTERN_LIBRARY.en.md) describes signed native loading. Alloc(int) returns address; mem_get/mem_free/make_free/mem_send_up accept address. For example: `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`. Counts/offsets remain int. Addresses support address+int, int+address, address-int, unsigned equality/order, with checked overflow/underflow. Test null with p == null. Address/integer conversions and equality remain unsupported, as do direct address conditions; `address(a)` only preserves an address and `string(a)` formats its unsigned value. Class pointers retain static class/inheritance rules until runtime erasure; normally manage them with constructors/delete. Raw release runs no user destructor.

This distribution uses exec v10 and JNI snapshot v9 only. Recompile all modules. Add `-g` when compiling to include function names and source positions in the executable; executables carry no names by default. Migrate `int p = alloc(...)` to address; reference-semantics `Point p(...)` to `Point * p(...)`; and `Point p = new Point(...)` to `Point * p = new Point(...)`. Addresses still represent slot positions; capacity/lifetime rules are unchanged.

See the [full language guide](LANGUAGE.en.md), [usage guide](USAGE.en.md), [math guide](../stdlib/MATH.en.md), and [container guide](../stdlib/CONTAINERS.en.md) for further preprocessing, types, lifetimes, memory limits, AST/ABD, compilation, and embedding.

## Multi-file libraries

Headers declare `#assume_hint MY_LIB abcd` and `extern int answer():abcd0002;`; implementation files use `#namespace_hint MY_LIB` and `int answer():0002 {return 42;}`. Compile independently and assemble with `./run.sh main.exec.abd --insert library.exec.abd`. C++/JNI hosts explicitly flush after assembly.

Class headers declare extern constructors/methods/destructors, implemented outside classes, e.g. `Point::Point(int value):0002 {this.x=value;}`. Within a compilation unit classes are nominal; executables carry no class names, so across modules the shared declaration is an unchecked contract and every module should include the same header. Runnable examples are under examples/multifile/; see [hint linking](HINT_LINKING.en.md).
