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

## Variables, functions, and control flow

Basic types are int, float, double, boolean (also bool), string, and address; functions may also return void. Int is signed 32-bit; address is an independent unsigned 64-bit slot address with null as its empty value. They do not implicitly convert. Decimals default to double; 1.5f is float. Integer overflow and division by zero are errors. Explicit primitive locals require initialization; var and def declare dynamic variables.

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

Functions support forward calls and recursion. Primitive parameter types may be omitted, but object parameters need explicit class names for member access. Blocks create scopes; declare variables before use, with inner shadowing allowed. Every normal exit from a nonvoid function must return. If/else, while, for, break, continue, and return are supported; closures and exception catching are not.

`for (int i = 0; i < 5; i++) { ... }` scopes i to the loop. Initializer, condition, and step are optional; an omitted condition is true. Continue cleans the current iteration's objects, then runs a for step and checks its condition; while continue directly rechecks the condition. Break and return skip the step. ++/-- work only on simple numeric/address variables as standalone statements or for steps, never results or arguments.

Operators include + - * / %, == != < <= > >=, && || !, assignment, and variable += -= *= /= %=. Multiplication/division precede addition/subtraction; comparison precedes logic. &&/|| short-circuit. Strings use single/double quotes and escapes including `\n`, `\t`, `\\`, `\"`, `\'`, and `\uXXXX`. Comments use // or /* ... */.

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

Access members with object.field, object.method(), or this.field. Receiver/arguments evaluate left to right once. Lookup prefers locals/parameters, then members, then globals. Member assignment supports =, not +=. Structurally equivalent pointers support assignment/equality and comparison to null; integer 0 is not null. Explicit class types are required: `var object = new Point(1); object.get();` fails; write `Point * object`.

Inheritance uses `class Child : Base` or `class Child : public Base`. Base slots come first, derived slots follow; inherited methods use the same address. Derived pointers convert to base pointers in variables, parameters, and returns, with no literal slicing. Calls bind statically, names may be shadowed, and there is no virtual dispatch.

```cpp
class TaggedPoint : Point {
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    ~TaggedPoint() { print("tag " + tag); }
}
```

Omitting `: Point(value)` calls the base default constructor, failing compilation if none exists. The base completes before derived initialization/body. Destruction is derived-to-base, including deletion of new TaggedPoint through Point *. Failed construction cleans completed bases without destructing the failed level. `examples/inheritance-regressions.azs` returns 0.

## Includes, host entry points, and memory

Includes resolve relative to the containing source file. Under distribution examples/:

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

Floating APIs require double, e.g. 3.0; integer APIs require int. Multiply integer expressions by 1.0 for floating APIs. Bind dynamic variables to explicitly typed locals first. The library links through AZSCRIPT_MATH; assumed namespace 4d41 is not its actual runtime address.

The container library is also declarations-only. `List<int> * xs = new List<int>();` creates a list, and `delete xs;` releases its nodes. `Vector<int> * xs = new Vector<int>();` is a contiguous array. `Set` and `Map` stay ordered by the default comparison. The package includes stdlib/containers.exec.abd:

```sh
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

That example returns 0. Assumed namespace c071 is not the runtime address. See [containers](../stdlib/CONTAINERS.en.md).

Declare globals with #gvar name, usually assigning in parameterless `void __script_onload()`. `void __script_pre_destroy()` runs on explicit close. Non-hint main is fixed at 0x0fff0000 and hooks at 0/1. Ordinary definitions can fix low numbers with `int function():0003 {return 1;}`. Automatic numbers change with source; name-based host calls need the matching --ast abstract map.

Host declarations require complete signatures, e.g. `extern int host_add(int, int):0x12340001;`, matching host IDs, arguments, and results. The standalone interpreter has no application-specific callbacks; embed C++ or Java for these scripts.

Built-ins print, getDepth, alloc, mem_get, mem_free, make_free, mem_send_up, and load_extern_library need no declaration. [External libraries](EXTERN_LIBRARY.en.md) describes signed native loading. Alloc(int) returns address; mem_get/mem_free/make_free/mem_send_up accept address. For example: `address p = alloc(2); mem_get(p + 1) = 42; mem_free(p);`. Counts/offsets remain int. Addresses support address+int, int+address, address-int, unsigned equality/order, with checked overflow/underflow. Test null with p == null. No integer conversions/equality or direct if conditions exist. Class pointers retain static class/inheritance rules until runtime erasure; normally manage them with constructors/delete. Raw release runs no user destructor.

This distribution uses exec v9 and JNI snapshot v9 only. Recompile all modules. Migrate `int p = alloc(...)` to address; reference-semantics `Point p(...)` to `Point * p(...)`; and `Point p = new Point(...)` to `Point * p = new Point(...)`. Addresses still represent slot positions; capacity/lifetime rules are unchanged.

See the [full language guide](LANGUAGE.en.md), [usage guide](USAGE.en.md), [math guide](../stdlib/MATH.en.md), and [container guide](../stdlib/CONTAINERS.en.md) for further preprocessing, types, lifetimes, memory limits, AST/ABD, compilation, and embedding.

## Multi-file libraries

Headers declare `#assume_hint MY_LIB abcd` and `extern int answer():abcd0002;`; implementation files use `#namespace_hint MY_LIB` and `int answer():0002 {return 42;}`. Compile independently and assemble with `./run.sh main.exec.abd --insert library.exec.abd`. C++/JNI hosts explicitly flush after assembly.

Class headers declare extern constructors/methods/destructors, implemented outside classes, e.g. `Point::Point(int value):0002 {this.x=value;}`. Types compare by ordered field structure, independent of class/field names. Runnable examples are under examples/multifile/; see [hint linking](HINT_LINKING.en.md).
