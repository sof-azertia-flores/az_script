# AzScript Compiler and Language Guide

[中文](LANGUAGE.md) | English

The compiler was rewritten around the original code-tree format. Source passes through preprocessing and lexing into a recursive-descent JSON AST, then scope, function-ID, argument-count, and declared-type resolution produces ABD for the C++ interpreter. The original `GeneraterJson.AzScript`, `getExpression`, and `Compiler.compile` entry points remain.

## Compilation and embedding

From the repository root, use the unified build entry point:

```sh
python3 tools/build_and_test.py --offline
./azscript compile compiler/examples/parser-regressions.azs \
  -o build/example.abd --ast build/example.ast.json --exec-json build/example.exec.json
./build/native/interpreter/azscript-run build/example.abd
```

Offline builds use locally cached dependencies. Omit `--offline` if initial dependencies are missing.

The CLI's `compile-json` accepts the readable code tree; `pack` and `unpack` archive and extract files. All paths are arguments. Includes resolve relative to the file containing them. Compiler output never overwrites source input.

```java
var script = new azertia.script.GeneraterJson.AzScript();
script.execute(java.nio.file.Path.of("example.azs"));
var jsonTree = script.toObj();
var instructions = azertia.script.Compiler.compile(jsonTree);
byte[] executable = instructions.toValue().toAbdFormat();
```

`execute(String)` is also available, using the process working directory as its include base. Each execution clears the previous program's functions, preprocessor attributes, and metadata. Do not share a compiler instance concurrently between threads.

Pure-script [`stdlib/math.azs`](stdlib/math.azs) compiles independently with `#namespace_hint AZSCRIPT_MATH`. Clients include only typed declarations in [`stdlib/math.include.azs`](stdlib/math.include.azs). Insert its ABD and flush at runtime; standalone `--insert math.exec.abd` links automatically. Header namespace `4d41` is assumed; the runtime assigns its actual location. Floating APIs take double, integer APIs int, and math_pow `(double, int)`; extern types must match exactly. See [the math guide](stdlib/MATH.en.md) for compilation, integration, APIs, int32 domains, rounding boundaries, and accuracy.

[`stdlib/containers.azs`](stdlib/containers.azs) is the same kind of library, hinted `AZSCRIPT_CONTAINERS`. Clients include [`stdlib/containers.include.azs`](stdlib/containers.include.azs). `List<T>`, `Stack<T>`, and `Queue<T>` share one generic body. Elements live in linked-node fields because a type parameter cannot be stored with `mem_get`. The assumed namespace is `c071`. See [the container guide](stdlib/CONTAINERS.en.md) for operations, ownership, and IDs.

## Functions, variables, and scopes

```text
extern int host_add(int, int):0xccf0001;
#namespace 123
#gvar total

void __script_onload() { total = 0; }
int add(a, b) { return a + b; }
int main() {
    def(x);           // Original declaration syntax
    x = 2;
    def(y, 3);        // Declaration with initialization
    var z = add(x,y); // Equivalent readable spelling
    { var x = 100; print(x); }
    return z;        // Original return(z); is also supported
}
void __script_pre_destroy() { print("done"); }
```

Return types are `void/int/string/float/double/boolean/address` or a declared class; `bool` aliases boolean. Forward calls, recursion, and arbitrarily nested calls are supported within nesting limits. Ordinary functions are top-level; members may be inline or defined as `ClassName::method`. Parameters may be `a,b` or `int a,int b`; object parameters need explicit class types (`C p` copies by value; `C * p` passes a pointer). Locals may use `int x = 2`; explicitly typed primitive locals require initialization. Explicit local/parameter types participate in compile-time checks; untyped parameters, var, def, and globals remain dynamic. The interpreter also validates return types.

Blocks create lexical scopes and allow inner names to shadow outer names; duplicate declarations in one scope fail. Local IDs are unique across a function, avoiding sibling/earlier-child collisions. Declare before use; expired-scope access fails compilation. Declare globals with `#gvar`, normally initializing them in onload. Parameters pass by value; script calls check argument counts.

Non-hint programs retain `__script_onload = 0`, `main = 0x0fff0000`, and `__script_pre_destroy = 1`; hooks must be parameterless void functions. Ordinary functions default to namespace `0xfff`. `#namespace 1234` accepts one to four hex digits. `int add(int a,int b):0003 {return a+b;}` fixes the low 16-bit number; explicit numbers are reserved before automatic allocation. Fixed entry IDs cannot be changed by suffix. Automatic IDs may change with source; hosts should use the AST map from the same compilation.

`#namespace_hint NAME` declares an independent library. Ordinary library functions, including main, use placeholder 0000; 0/1 are reserved for lifecycle and automatic numbering starts at 2. `#assume_hint NAME abcd` supplies a call namespace relocated by flush. A compilation unit including active includes may have at most one namespace or namespace_hint directive. See [multi-file libraries and hint linking](../docs/HINT_LINKING.en.md).

Eleven built-ins require no declaration:

| Name | Signature | ID |
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

These names and `0xabd` IDs are reserved and cannot be script or extern declarations. Built-ins do not appear in AST abstract or extern-signatures.

`load_extern_library("xxx")` resolves the platform's xxx.dylib/.dll/.so and adjacent xxx.signature. After verification it calls the exported `azscript_load_extern`. Libraries use C++ host `registerExecutor`; scripts still carry compile-time extern signatures. This neither loads a hint module nor calls flush. See [external libraries](../docs/EXTERN_LIBRARY.en.md) for search rules, keys, and implementation.

External declarations use `extern int custom_call(string, double):12340001;`. Parameter names are optional, types mandatory; zero arguments use `extern void notify():12340002;`, never a void parameter. IDs accept full 32-bit hex patterns and optional `0x`. Old `#extern` is rejected. Externs may bind hosts or hint libraries. A same-name extern and definition may coexist with matching signatures; calls prefer the extern. Hosts must register the same ID and cannot take namespaces 0000/0abd/0fff or active script namespaces.

Extern contracts require exact types without implicit numeric conversion: double accepts neither int nor float. The compiler checks counts and known types and propagates extern result types through nested calls. Dynamic values of unknown type are rejected for parameters with declared types; use explicit declarations to establish their types. ABD carries the same signature. The interpreter validates actual arguments before callbacks and results immediately afterward, preventing mismatched host values from entering further script execution. Neither source declarations nor hand-authored AST may override built-ins; named host calls require extern declarations with complete signatures; ID-based reflection follows the rules below.

## Classes, literal objects, and pointers

```cpp
class Point {
    int x;
    Point * next; // Non-owning pointer field, null by default

    Point(int value) { this.x = value; }
    int get() { return x; }
    Point * self() { return this; }
    ~Point() { print(x); }
}

Point create(int value) {
    Point p(value);
    return p; // Transfer this local literal object without copying
}

void main() {
    Point a = create(3);
    Point b = a;                    // Independent copy of every slot
    b.x = 4;
    Point * scoped(5);              // Automatically destroyed at block exit
    Point * manual = new Point(6);
    delete manual;                  // Prints 6
}                                   // Prints 5, 4, 3
```

Classes are top-level only. Fields require a primitive, class (literal value), or `Class *` (pointer) type and occupy one slot each in declaration order, without a hidden header. Classes with zero total fields including inherited fields fail. Constructors share the class name and omit return types. No constructor declaration synthesizes a zero-argument constructor; declaring a parameterized constructor does not add one. Optional `~ClassName()` destructors take no arguments, return no value, and cannot be called directly. All members are public. Single inheritance is supported; multiple inheritance, virtual dispatch, overloads, static members, and nested classes are not.

| Syntax | Meaning |
| --- | --- |
| `C x;`, `C x();`, `C x(args);`, `C x = expr;` | Literal object: a value in the variable's own slot block |
| `C * x(args);`, `C * x();` | Automatic pointer object on the shared heap, destroyed by the declaring block; zero arguments still require `()` |
| `C * x = new C(args);` | Manual object requiring delete, unaffected by block exit |
| `C * x;`, `C * x = null;` | Null pointer without object creation; may later refer to another pointer/manual object |

Literal initialization, assignment, arguments, and returns copy all slots, recursively copying literal fields but only addresses for pointer fields. Each copy is independent and has its own destructor. `a = b` assigns fields in place without changing a's address. Pointer initialization `C * y = x`, assignment, and arguments copy only addresses. Reassignment does not change cleanup responsibility for previously created objects. `C * p;` is exactly `C * p = null;`: no construction or responsibility for future assigned objects.

Value C and pointer C * are distinct without implicit conversion. Null assigns only to pointers/address. Literal objects cannot be compared, used in arithmetic, or stored in untyped positions: var, def, #gvar globals, untyped parameters, print, and `mem_get(p) = …` reject them. Use pointers for long-lived storage. Structurally equivalent literal values can be assigned only with matching cleanup destructors. Derived values cannot be assigned to base values (no slicing); upcasts apply only to pointers. Both use `.` for members. Only explicitly class-typed variables, parameters, fields, and results support member access: `var p = new C(); p.method();` fails because p is dynamic any; use `C * p`.

`C (*) p` (also `C(*) p`) accepts both C * and literal C arguments:

```c
int read(Point (*) p) { if (p == null) { return 0; } return p.x; }
void bump(Point (*) p) { p.x = p.x + 100; }

Point a(1);
Point * b = new Point(2);
read(a); read(b); read(null); // All three are valid
bump(a);                     // a.x becomes 101; borrowed by address, not copied
```

Inside the function p is C *, nullable and reassignable. For literal arguments the compiler passes their address without copying, so mutations are visible to the caller. Temporaries such as `read(make(4))` stay alive through the call and die at statement end. Compatibility follows C *: derived pointers/literals and structurally equivalent classes are accepted; a base literal cannot satisfy a derived (*) parameter. This syntax is only for class function parameters, including constructors, methods, and externs—not locals, fields, returns, or combinations such as `C * (*)`. Its ABI equals C * (address), but extern declarations and definitions must use matching signature spellings. Retaining the pointer beyond a literal's lifetime produces `Invalid or expired object address`; deleting a borrowed literal also fails at runtime.

Scripts can obtain a literal's storage address only through method this or a borrowed C (*) parameter, both typed C *. Methods/functions may return or retain it, but access after expiration fails. Underlying literal addresses are ordinary address values with the high bit marking internal storage; they support address comparisons and offsets at that level. Storage IDs are never reused, so expired addresses cannot point to later objects.

At each inheritance level, scalar/address/pointer defaults are set first: int 0, float 0.0f, double 0.0, boolean false, empty string, and null addresses/pointers. Then declaration order constructs literal fields (`C f(args);` or `C f = expr;`, defaulting to a required zero-argument constructor) and evaluates other explicit initializers. Finally the constructor body runs; bases complete these steps first. All initialization prefixes the actual constructor implementation and uses its module's globals/call bindings. Initializer names resolve this, members, then globals, unaffected by constructor parameters. Classes cannot contain themselves directly/indirectly by value; use pointers. Literal nesting is limited to 64 levels. Pointer fields are non-owning and require explicit delete in a destructor if desired.

Members use `obj.field`, `obj.method(args)`, or `this.member`, with chained access/calls. Inside classes, locals/parameters precede members, then globals. Receivers and explicit arguments evaluate left to right exactly once. Member assignment supports =, not compound += etc. Pointers compare for equality with structurally equivalent or inheritance-related pointers and null, but do not support address arithmetic. Integer 0 is not implicitly an object. Null is runtime address zero. Member access validates the object base before adding offsets, so null fails even for nonzero-offset fields.

Lifetime rules:

- Literal and automatic-pointer objects in one block are destroyed in reverse creation order, including normal exit, return, break, continue, and errors.
- Destruction follows C++ order: current destructor body → current literal fields in reverse order → base destructor body → base fields. Literal fields without an explicit destructor trigger a synthesized field-cleanup destructor.
- Unbound temporaries such as `make().x` die at statement end; if/while condition temporaries die immediately after condition evaluation.
- By-value parameters are caller-object copies and die when the call ends.
- Returning this function's owned local/parameter directly transfers it without copying or premature local destruction. Returning fields, globals, or borrowed values copies first.
- Returning an automatic-pointer object owned by this function transfers only the directly returned object to the actual calling block. Returning caller-owned objects or this leaves ownership unchanged. A returned new object remains manual.
- Failed construction does not run that object's destructor but destroys completed literal fields. If the direct base completed, its and its ancestors' destructors run; cleanup errors do not replace the original construction error.

`delete expression;` takes a pointer or null and destroys only new-created objects, including through aliases. Delete null does nothing. Automatic pointers, expired addresses, and objects being destroyed cause runtime errors; deleting literal values is a compile-time error. Other aliases are not cleared. Shared-heap addresses can be reused without generation tracking; avoid dangling heap pointers.

Destructor errors still release the current object and continue other cleanup. Existing errors win; otherwise the first destructor error propagates. Instruction/depth budgets also constrain destructors: exhaustion may prevent completing user code, but storage is reclaimed. Explicit close runs destruction hooks, cleans automatic objects owned by global scope, then unloads functions. Undeleted new objects receive no user destructor, but their slots/records are released with the script, avoiding leaks across repeated load/destroy cycles.

Literal objects do not cross JNI: Java calls returning them fail after destruction, and Java cannot pass or read them; use pointers. C++ hosts own returned literal handles without later user destructor execution; addresses expire when the last handle is released. C++ can directly use `blocks::create`, `blocks::resize`, `blocks::length`, and `blocks::address_of`. Growth preserves existing addresses; shrinking invalidates out-of-range access.

`alloc/mem_free/make_free/mem_send_up` remain low-level memory APIs with address pointers/results and int counts. Raw release removes object records without user destruction; use delete for objects. These functions do not manage literal storage: mem_free returns false, make_free/mem_send_up throw. Constructors, destructors, and methods are ordinary functions with implicit first C * (runtime address). Structural equivalence compares ordered field types including inherited fields, ignoring names and methods; values and pointers compare separately, recursive pointer targets use visited type pairs. Runtime stores/checks no class structure. Use shared class declarations and extern members across modules; see [multi-file libraries](../docs/HINT_LINKING.en.md). [Class regressions](examples/classes-regressions.azs) return 0.

## Addresses and low-level memory

`address` is an independent uint64 type ranging from 0 to `18446744073709551615`, usable in locals, fields, parameters, and returns. Null is address zero. Explicit address locals require initialization; fields default to null. Int stays signed 32-bit; integer literals, allocation counts, offsets, variable IDs, and function IDs do not become addresses. No implicit conversions exist with integers, floating values, or booleans; integer 0 cannot replace null.

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

Raw address operations are address + int, int + address, and address - int, returning address; offsets may be negative. Overflow/underflow across the full uint64 range throws. Addresses support == != < <= > >= with unsigned ordering; null only supports equality. Address+address, address-address, int-address, floating offsets, multiplication/division/modulo, numeric negation, and direct conditions are unsupported. Test null with `p != null`. Simple address variables also support +=, -=, and standalone/for-step ++/--. Print and string concatenation use unsigned decimal.

Arithmetic only computes a value; reads/writes validate live allocation ranges. The 64-bit representation does not enlarge the 1,048,576-slot heap or extend lifetimes. Raw allocation, ownership promotion, and manual release retain their rules. mem_get/mem_free/make_free/mem_send_up accept address only, rejecting int. Ordinary address returns use r without automatic ownership promotion; use mem_send_up for explicit raw-allocation transfer.

Class pointers keep distinct structural types during compilation: no direct assignment to/from raw address, raw-address member access, or raw-address delete. Only executable generation erases class pointers/this to address; literals remain object values. Object returns use ro to transfer actual ownership. Each address field occupies one slot and inherited prefixes remain unchanged.

[Address regressions](examples/address-regressions.azs) show slot traversal, stored addresses, and explicit raw-allocation transfer, returning 0.

## Single inheritance

```cpp
class Point {
    int x;
    Point(int value) { x = value; }
    int get() { return x; }
    ~Point() { print("point"); }
}

class TaggedPoint : public Point { // public is optional
    int tag;
    TaggedPoint(int value, int label) : Point(value) { tag = label; }
    int total() { return get() + tag; }
    ~TaggedPoint() { print("tagged"); }
}

void main() {
    TaggedPoint * item(3, 4);
    Point * base = item;
    base.x = 5;
    print(item.total()); // 9; base and derived access the same storage
    Point * manual = new TaggedPoint(6, 7);
    delete manual; // tagged, point
    TaggedPoint literal(1, 2);
} // Both literal and item are destroyed in tagged, point order
```

Use `class Child : Base` or `class Child : public Base`. Forward-declared-in-source bases work; cycles, unknown bases, and multiple bases fail. Layout recursively keeps all base slots as a prefix and appends own fields in order. A derived class inheriting at least one field needs no new fields. There is no hidden header. Base methods receive the same starting address with no adjustment.

Derived classes directly access inherited members and may shadow names. Binding follows the expression's static class: derived values/pointers call derived methods, base pointers call base methods, and names inside base bodies retain base resolution. There is no virtual dispatch or `Base::method()` expression to select shadowed members; bind a base pointer first.

Derived pointers convert to base pointer variables, parameters, and results without address/ownership changes; equality also accepts this relationship. Base pointers do not implicitly become derived pointers with extra fields. Derived literal values cannot be assigned to base literals (no slicing). Unrelated classes still need complete structural equivalence, not merely a matching prefix. A complete inherited structure may match a flat declaration of the same fields.

Construct the base first, then default current fields, execute current initializers in order, then the current body. Omitting a base initializer calls Base(), which must exist. `Child(int n) : Base(n + 1) { ... }` passes explicit arguments using derived constructor parameters, evaluated left to right once. Field initializers still resolve members and implementation globals, unaffected by parameter shadowing. Explicit constructor IDs precede base initializers: `Child::Child(int n):0002 : Base(n) { ... }`.

Completed objects destruct from most-derived to root, disposing of each level's literal fields after its body. Missing derived destructors, early returns, and errors do not skip base cleanup. Automatic cleanup, literal expiration, and deletion through base pointers all use the complete chain registered at creation; this is not virtual method dispatch. Failed construction cleans only successfully completed bases, not the failed level, preserving the original error and releasing storage. Budget exhaustion still releases storage but may prevent full user destruction.

Cross-hint inheritance uses shared declarations with extern constructors/methods/destructors; base and derived implementations may be separate modules. Readable AST retains inheritance and field order; exec contains addresses, offsets, and ordinary references only. Flush links constructors and destructors across modules. See [inheritance regressions](examples/inheritance-regressions.azs).

## Expressions and control flow

Precedence from lowest to highest:

| Operators | Associativity |
| --- | --- |
| `= += -= *= /= %=` | Right |
| `\|\|` | Left, short-circuit |
| `&&` | Left, short-circuit |
| `== !=` | Left |
| `< <= > >=` | Left |
| `+ -` | Left |
| `* / %` | Left |
| Unary `+ - !` | Right |
| Parentheses, literals, variables, calls | Highest |

Thus `20-3-2` is 15, `100/5/2` is 10, and `2+3*(4+1)` is 17. `a=b=3` assigns from the right. Short-circuited right operands of &&/|| are not evaluated.

```text
// Original functional control syntax
while (i < 10, { i += 1; });
if (i == 10, { print("yes"); }, { print("no"); });

// Conventional block syntax
while (i > 0) { i -= 1; }
if (i == 0) { return 1; } else { return 2; }
```

`mem_get(pointer) = value` is supported and evaluates its pointer once. Compound memory assignments such as += are rejected to prevent duplicate side effects. Store the pointer first, then write `mem_get(p) = mem_get(p) + value`. Other calls cannot be assignment targets.

Return exits through nested if/while/for blocks. Break exits the innermost loop; continue skips the rest of its iteration. Both require a loop body and cannot cross functions. Exited blocks still clean their objects. While continue rechecks its condition; for continue runs its step before the condition. Break, return, and errors skip the step.

```c
int total = 0;
for (int i = 0; i < 6; i++) {
    if (i % 2 == 0) { continue; }
    total += i;
}
print(total); // 9
```

`for (initializer; condition; step) statement` lowers to a block and while with no runtime for instruction. All three parts are optional; omitted conditions are true and `for (;;) { ... }` is infinite. Initialization runs once and may declare one local or contain one expression. Its variables/objects belong to the entire loop scope and expire on exit. Each body iteration has its own scope, destroyed before the step. The step is one expression; comma expressions and multiple declarations are unsupported.

`i++`, `++i`, `i--`, `--i` are standalone statements or for steps on simple numeric/address variables only. They cannot appear in returns, arguments, or other expressions, so prefix/postfix result differences do not apply. Members and mem_get targets do not support them. Integer overflow, address bounds, and dynamic values that are neither numbers nor addresses fail at runtime.

Every normal exit of a nonvoid function must return. Both if branches must return. While/for counts as nonterminating only with literal true/nonzero numeric conditions (or omitted for condition) and no break belonging to that loop; otherwise compilation reports `non-void function can reach the end of its body without returning a value`. Statements end in semicolons, optional immediately before block end; function-definition trailing semicolons are optional. Array subscripts, object-literal expression syntax, closures, and exception handling are unsupported and rejected.

Integers are signed 32-bit; out-of-range literals fail compilation. Decimal/scientific literals default to double; f/F produces float, e.g. 1.5f, 5f, 2e3f. Both reject overflow and underflow to zero. Numeric print/string concatenation uses shortest round-trippable text: `0.1+0.2` → `0.30000000000000004`, `123456789.0` → `123456789`, `1e20` → `1e+20`; float uses its own precision (0.1f → 0.1), and negative zero prints -0. Runtime-only zero divisors compile but int/float/double division, including positive/negative floating zero, throws `Division by zero`, never Infinity/NaN. Integer overflow also fails at runtime.

## Strings, comments, and preprocessing

Strings use single or double quotes and support `\\`, `\"`, `\'`, `\n`, `\r`, `\t`, `\b`, `\f`, `\0`, and `\uXXXX`. UTF-16 surrogates must be paired (e.g. `\uD83D\uDE00`); isolated surrogates fail compilation. Spaces, commas, parentheses, semicolons, and braces inside strings are preserved. Strings cannot span physical newlines. Comments use // or /* ... */. Unclosed parentheses, strings, escapes, and comments report positions.

Supported preprocessor directives may be indented:

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
// Active code
#else
// Inactive code
#fi
#endif
#ifndef disabled
// Active code
#fi
#undef LIMIT
```

Setattr affects conditions without substitution. Define substitutes whole identifiers, not strings, comments, numeric literals, or substrings of identifiers. Identifier characters adjacent to digits (5N, 0x10, 2e) belong to that numeric token and are not expanded; the lexer diagnoses invalid suffixes. Parameterized macros are unsupported. Include/macro cycles, expansion beyond 64 levels, expanded lines over 1,048,576 characters, and unmatched conditions fail. Unknown directives fail even in inactive branches; use comments for prose. Macro substitution is textual; parenthesize complex values, e.g. `#define VALUE (1 + 2)`.

A leading UTF-8 BOM is ignored. Reserved words include return, if, else, while, for, break, continue, def, var, class, new, this, null, delete, extern, public/private/protected/virtual, all type names, and true/false; none may name functions, parameters, variables, globals, macros, or externs.

ABD's 128-level container bound governs nesting. Function bodies start at level 4; expression nodes, arguments, statements, and blocks consume levels, allowing roughly 120 chained operators or 60 nested blocks. Excess depth reports function and line/column, e.g. `In function main:2:8: Expression nesting exceeds the ABD limit of 128 levels`. Generic and pointer type expressions have a separate 64-level nesting limit, including type strings in handwritten ASTs. Parser recursion and JSON containers have an additional 320-level guard; cyclic ASTs are rejected early. Compilation may continue with other programs in the same process after errors.

Syntax errors include line/column; symbol errors also name the function. After includes/macros, syntax positions refer to combined source rather than exact original include locations. Preprocessor errors additionally identify the processing directory and line.

## Generics

Generic classes, functions, and methods share one compiled body. Different type arguments do not allocate new public function IDs. A type parameter denotes a complete type: primitive, address, class value, class pointer, or parameterized class. `void`, `any`, and parameter-only `C(*)` are not type arguments.

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

Function type parameters precede the return type. Calls can supply `identity<int>(3)` or infer types from arguments, recursively matching parameterized types. Inference does not use the destination variable's type, infer from null alone, or find common ancestors for conflicting arguments; specify types when inference is ambiguous. Classes require explicit arguments: no raw types or diamond `<>`. Applications are invariant, requiring the same generic declaration and identical type arguments, including unused parameters. Existing structural equivalence for non-generic classes and pointer upcasts remain available.

A single class bound is written `<T extends Base>` for values or `<T extends Base*>` for pointers, checked through declared inheritance. Bounds may reference earlier parameters, for example `<A, B extends Box<A>*>`. Wildcards, multiple bounds, circular bounds, and using a bare type parameter as a bound are not supported. Unbounded T supports storage, assignment, arguments, and return. A bound exposes its fields/methods but does not permit assigning any Base to T or slicing an unknown derived value into Base. Unknown T does not support arithmetic, comparison, delete, `T()`, `new T()`, or appended `*`/`(*)`; pass `C*` as the complete type argument instead.

A local `T local = expression;` requires an initializer. T fields retain concrete default semantics: initialize scalars to zero, false, or an empty string, and addresses/pointers to null first; construct class-value fields or evaluate explicit initializers in declaration order. The base completes before the child, and field initializers still cannot read constructor parameters. Types without a zero-argument constructor may participate in pure forwarding. Required default construction fails at compile time when the current unit/shared declaration makes it known, or at the actual operation when an independently compiled body was unknown, with normal cleanup. An explicit class-value initializer does not require an additional default constructor.

Inheritance can use `class Child<A,B> : Base<B>`. Out-of-class definitions use `T Box<T>::get()`, with the owner's declared parameter names in scope; generic methods use `<U> U Box<T>::echo(U value)`. An extern is `extern <U> U identity(U value):abcd0002;`. Declarations and definitions match parameters positionally and check bounds, allowing different parameter names. Constructors/destructors use their class parameters and cannot declare additional parameters. Hint libraries still implement members outside the class; see [generic shared libraries](../docs/HINT_LINKING.en.md).

Execution carries immutable operation contexts containing an ABI tag, return category, and optional default factory with captured contexts. These contain no class names/layouts, introduce no AZS value type, and occupy no user parameter or field slots. Class contexts precede method contexts, while this remains ordinary parameter slot 0. Object records retain destructor contexts across copy, move, return, delete, error cleanup, and snapshot restoration. Returning `T=address` does not transfer object ownership; `T=C*` follows object-return rules, and `T=C` follows existing value copy/move rules.

## Function-ID reflection and hint queries

```cpp
#namespace 1234
int increment(int value):0002 { return value + 1; }
void main() {
    int value = reflect_invoke_function<int>(305397762, 4);
    print(value); // 5; 305397762 has the bit pattern 0x12340002
    print(reflect_hint_loaded("OPTIONAL_LIB"));
}
```

`reflect_invoke_function<R>(int funcid, ...params)` accepts a concrete primitive, address, class pointer, class value, or void result type; R may also be the current generic body's bound T. IDs use the actual mounted namespace and complete int32 bit pattern, including negative IDs. Ordinary integers are never assume-relocated. Receivers/arguments are evaluated once, left to right, and invocation preserves the current caller, budget, and error cleanup. Ordinary non-generic member methods can be invoked by supplying this explicitly as a pointer.

Targets with signatures use existing argument validation. Host callbacks without extern declarations are also callable, but the host must validate their arguments. The actual returned tag is always checked, including void, without additional numeric conversion. Class pointers can only be checked as address and class values as object: reflection cannot validate class layouts or generic identity. An incorrect class annotation does not acquire runtime structural checks.

Public reflection rejects functions and generic-class methods requiring hidden contexts, constructors, destructors, lifecycle hooks, internal factories, and helpers. Expose a normal wrapper that fixes concrete type arguments before calling generic code; its ordinary parameters/results may include `Box<int>`. Ordinary C++/JNI invoke also rejects unbound generic entries. Creation/deletion wrappers must use object syntax or delete, never directly call lifecycle bodies.

`reflect_get_hint_namespace(string hint)` returns the mounted namespace number and throws for a missing/empty name. `reflect_hint_loaded(string hint)` returns false for missing/empty names. Names are case-sensitive. Mounted does not guarantee that onload completed: an onload hook can query another mounted module before its initialization. String queries and dynamic calls do not add dependency edges; use `#assume_hint` when initialization order matters. Combine a namespace and low 16-bit number without signed multiplication overflow as follows:

```cpp
int functionId(int ns, int position) {
    if (ns >= 32768) ns = ns - 65536;
    return ns * 65536 + position; // position must be 0..65535
}
```

## AST and ABD contract

Readable AST retains metadata/ext/abstract/global-variable/body, plus extern-signatures. Class programs retain definitions, ordered fields, member expressions, and other compile-time data; compile-json reruns checks/lowering. Hint, assumes, extern declarations, definition names, and actual definition numbers are retained for byte-identical roundtrips. Abstract maps source names with extern priority: declaration ID when present, otherwise definition ID. Hint definitions retain placeholder 0000; self-assume call addresses are produced during lowering. Independent definition positions live in body namespaces and metadata.position/name. Signatures store return-type and param-types. Expression objects use `t: "ctrl" | "call"`, call, param; blocks are arrays. _line/_column serve diagnostics only, never ABD instructions.

Generated exec/ABD uses `exec-version: 8`, independent of source version. All control c fields are numeric; readable AST keeps source names/types/operations. Below is the exec JSON inspection view; binary field order/types are in [Exec v8](../docs/EXEC_FORMAT.en.md).

| Exec JSON instruction | Fields |
| --- | --- |
| Call `t=1` | id, param, contexts; binary opcode 2 |
| Read `t=0,c=3` (v) | v: integer slot |
| Declare c=4 (vd) | v, optional declared-type and val |
| Assign c=5 (vs) | v, val |
| Memory assign c=6 (m; AST mov) | v1 target, v2 value; target only variable or built-in mem_get |
| Return c=7 (r) | Optional r |
| Object return c=8 (ro) | r; transfer directly returned owned pointer object; move/copy literal to caller |
| Object address c=9 (oa) | v base expression, nonnegative offset |
| Construction complete c=10 (ob) | v address, optional destructor ID (0xffffffff is valid), manual boolean, bound destructor contexts |
| Delete c=11 (od) | v address; manual objects only, zero no-op, literals fail |
| Binary c=12…24 | v1, v2: add/minus/multiply/divide/mod/gt/lt/eq/ne/ge/le/and/or |
| Unary c=25/26 (not/neg) | v |
| Conditional c=27 (if) | v, val, optional else |
| Loop c=28 (wi) | v, val |
| Break c=29 (brk) | No fields |
| Continue c=30 (cont) | No fields |
| Cleanup c=31 | v body, val finalizer, on-error boolean; true only on failure, false always, preserving prior errors/returns/jumps |
| New literal storage c=32 (new_block) | size; temporary object with no destructor yet |
| Literal address c=33 (block_address) | v object expression; fails if expired |
| Move object c=34 (mv) | v local/parameter slot; internal forwarding from parameter to constructor without recopying |
| End object c=35 (drop) | v variable/mem_get target; destruct and end its owned object |
| Context ABI c=36 (context_abi) | context; actual ABI number |
| Default value c=37 (context_default) | context; scalar default or bound value factory |
| Generic return c=38 (return_typed) | v, context; validate tag and return according to category |
| Type check c=39 (check_type) | v, context; validate tag, evaluating the value once |

Root, functions, signatures, expressions, function/type/argument/statement lists use raw AbdStack. Fields retain length boundaries without names or known-type tags. Dynamic constants use single-element AbdArray preserving int/float/double/bool/string/void/address; extension metadata uses AbdMap. JSON constants and block arrays use binary opcodes 0 and 1. `Compiler.compile()` returns ExecProgram, still inspectable as AcsObject; toValue emits the wire format. Use ExecCodec.decode to recover the inspection view, not direct root AcsObject decoding.

Global gvs is a count; -1 names the first module global, -2 the second. Parameters occupy 0…param-count-1; locals and compiler temporaries follow consecutively per function. Implicit this is parameter slot 0. IDs are not alloc addresses, and bare integers remain constants; `{"t":0,"c":3,"v":0}` reads the first parameter/local.

Function views contain id/return-type/script/param-count/param-types/local-count/hidden-count/entry-kind. Local-count excludes parameters. Every call allocates an independent frame, including recursion/reentry. Exited blocks clean objects and clear declared slots, reinitializing on the next iteration. Total globals and per-call parameter+local counts cannot exceed 1,048,576. Invalid IDs, globals outside the owning module, undeclared or expired-scope access are rejected.

Untyped script parameters and unbounded type parameters use internal any. Only functions with hidden generic contexts may return any or declare extern any parameters. Entry-kind 0 denotes ordinary entries and 1 internal entries; contexts occupy no variable slots. AST additionally retains type-parameters, bounds, parameterized type strings, and explicit type-args. Param-types/return-type enable direct C++/JNI checks; root extern-signatures hold boundary contracts. The interpreter accepts only exec v8 raw stacks, rejecting old v7/Map files. Recompile source/AST. Unknown versions/opcodes, extra/missing fields, wrong widths, and nesting beyond 128 fail on load. JNI snapshot versioning is independent, currently v8.

### Insert and module global offsets

When inserting a numeric module, `script::insert_script(bytes, length)` records the prior global count as each of its `ofunction::global_offset`, plus its own global_count. Negative v resolves to `global_offset + (-int64(v) - 1)`. With two existing globals, inserted -1 is index 2 while original -1 remains index 0. The loader determines offsets, not compiled output.

Calls use the callee's offset for ordinary functions, constructors, destructors, factories, and lifecycle hooks. Later inserts never change existing offsets. Locals receive no global offset; block ownership/return transfer are unchanged. Conflicting ordinary fixed IDs reject insertion; hint modules receive available namespaces and keep lifecycle hooks separately per module.

Load/insert only assemble and never run onload. Hosts must explicitly flush before calling; successful insert sets setup=false and requires another flush. Linking fully validates before atomically rewriting calls/destructors; missing dependencies can be added before retry. Dependencies initialize first, cycles in load order. Onload failure requires close/recreate. Close runs pre-destroy in reverse successful initialization order, then cleans globals.

Class pointers erase to address type 7; literals are 8, int32 is 0, any is 6. Address constants use tag 0xce200b and eight unsigned little-endian bytes; JSON is `{"address":"unsigned decimal"}`, null `{"address":"0"}`. Only object-return instructions transfer pointer-object ownership; ordinary address/int returns do not. JNI v8 snapshots store ordered module identity (bytes, namespace, hint, global layout), globals, heap including literal fields, and cleanup ownership, only when initialized and idle. Restore fully validates then atomically replaces state without relinking or old destructors. Old exec/snapshots are incompatible; recompile all modules and change int heap-address variables to address.

## Regression validation and archives

`examples/parser-regressions.azs` exercises strings, nested calls, left-associative subtraction/division, mixed precedence, recursion, and control flow. It prints an escaped string, 15, 10, 17, 120, 26, false, true; main returns 146. The runner also prints the result and destruction-stage done. Migrated tse.azs is included in compilation/ABD roundtrips.

Archive fixes cover AchievePack.files value annotations, empty files, and lost leading 00/ff bytes. New archives mark byte wrapping with metadata.format=2. Structurally valid old archives remain readable, but already lost leading bytes cannot be recovered. Packing rejects symlinks and destinations contained in their own input; extraction rejects traversal, directory symlinks, and overwriting existing files. Older interpreters/archive readers may not understand new extensions.
