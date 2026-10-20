# AzScript Compiler and Language Guide

[中文](LANGUAGE.md) | English

The compiler was rewritten around the original code-tree format. Source passes through preprocessing and lexing into a recursive-descent JSON AST, then scope, function-ID, argument-count, and declared-type resolution produces ABD for the C++ interpreter. The original `GeneraterJson.AzScript`, `getExpression`, and `Compiler.compile` entry points remain.

## Compilation and embedding

`compile` and `compile-json` accept `-O0` (default), `-O1` and `-O2`; the last option wins. After semantic validation, O1 applies integer/boolean constant folding, conservative scalar propagation, non-trapping scalar leaf inlining, inline specialization of fixed scalar generic contexts, and total loop-invariant expression hoisting. Overflow, division by zero and invalid shifts remain runtime errors. Floating-point, string and observable object lifetime operations are not algebraically eliminated. The readable AST is unchanged; use identical optimization options when recompiling it to obtain identical ABD bytes. Debug numbering follows optimization.

`--no-fold`, `--no-propagation`, `--no-inline` and `--no-loop-motion` independently disable those passes. Propagation and inlining may still evaluate the constants they expose. Java callers can select `Compiler.Optimization.STANDARD`, or pass an exact `Set<Compiler.OptimizationPass>` to `Compiler.compile(ast, diagnostics, debugInfo, passes)`. `CONSTANTS` enables only folding; existing overloads retain O0.

O2 (`Compiler.Optimization.ADVANCED` in Java) adds scalar analysis across branches and loop backedges, common expression reuse, unreachable branch and dead scalar write elimination, bounded straight-line scalar function inlining, and loop motion justified by integer ranges. Inlined arguments are evaluated once in their original order; potentially failing operations keep their source locations. `--no-cfg-opt`, `--no-cse` and `--no-dce` disable control-flow pruning, common expression reuse and dead code elimination. Existing propagation, inline and loop-motion switches also control their O2 stages.

O2 preserves results, types, effects, object lifetimes, ordinary error categories and failing source locations. Cross-level comparisons allow shorter error stacks and different resource-exhaustion positions; `getDepth()` behavior remains unchanged. Unknown calls, reflection, cleanup and functions beyond analysis limits are treated conservatively. O2 still emits exec v10 and does not select VM/JIT or change JIT thresholds. Same-artifact backend equivalence is unchanged. See the [O2 boundaries and limits](../docs/compiler-optimization/O2.md).

Optimization reduces executed expressions and budget consumption. `max_steps` prevents runaway execution; optimization levels need not exhaust it at the same source position. Both engines retain the same work-unit charges for a given artifact. In scalar regions without calls, cleanup or control transfers, the VM can validate at most 64 steps together while still charging each executed tick; insufficient budget takes the per-tick path. The default `max_call_depth=0` disables the logical call-depth cap; positive values explicitly enable it. Native-stack and workspace limits remain active. O1 can remove physical calls, so optional depth caps and resource-exhaustion positions are outside O0/O1 equivalence; `getDepth()` and ordinary error traces remain protected.

From the repository root, use the unified build entry point:

```sh
python3 tools/build_and_test.py --offline
./azscript compile compiler/examples/parser-regressions.azs \
  -o build/example.abd --ast build/example.ast.json --exec-json build/example.exec.json
./build/native/interpreter/azscript-run build/example.abd
```

Offline builds use locally cached dependencies. Omit `--offline` if initial dependencies are missing.

The CLI's `compile-json` accepts the readable code tree; `pack` and `unpack` archive and extract files. All paths are arguments, and options may precede or follow the input path; `--` ends the options, so a following path may begin with `-`. Includes are searched in the directory of the file containing them, then in each `-I dir` / `--include-dir dir` in order; the `./azscript` wrapper and the distribution's `compile.sh` / `compile.cmd` search the standard library directory last, so a client anywhere can write `#include "math.include.azs"`. That directory is named by the single-path system property `azscript.stdlib`, which the wrapper sets; the distribution's launcher otherwise uses the package's `stdlib/`, and a value that is not a directory is ignored. A missing, empty or non-directory `-I` directory is a usage error, printed as `Error: ...` with exit status 1. Includes must stay inside the main file's directory or one of those directories unless `--unrestricted-includes` is given; see [preprocessing](#strings-comments-and-preprocessing). `compile-json` takes no include options. Compiler output never overwrites source input, included files among them. Compile errors and warnings are reported as described in [Diagnostics](#diagnostics).

`-g` (for `compile` and `compile-json`) adds optional debug records to the executable: the source file names, each function's display name and declaration position, and the source positions of its operators, calls and statements. Debug info is off by default, so the default executable carries no function, class or file names. `-g` never changes instructions or behavior, and the readable AST is the same with or without it; see [debug records in the exec format](../docs/EXEC_FORMAT.en.md). In Java, `Compiler.compile(tree, diagnostics, true)` or `Compiler.compile(tree, true)` adds them.

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

`execute(String)` is also available, using the process working directory as its include base and include root; `execute(String, Path)` uses the given directory instead, and embedders compiling untrusted text should pass a dedicated one. `AzScript.includeDirectories` and `AzScript.unrestrictedIncludes` correspond to `-I` and `--unrestricted-includes` and are kept across executions; `includedFiles()` lists the real paths of the files the last execution included. A missing, empty or non-directory entry of `includeDirectories`, or a missing or non-directory `execute(String, Path)` base directory, throws a plain `IllegalArgumentException` before any diagnostic is recorded, not a `CompileException`. Each execution clears the previous program's functions, preprocessor attributes, and metadata. Do not share a compiler instance concurrently between threads.

Pure-script [`stdlib/math.azs`](stdlib/math.azs) compiles independently with `#namespace_hint AZSCRIPT_MATH`. Clients include only typed declarations in [`stdlib/math.include.azs`](stdlib/math.include.azs). Insert its ABD and flush at runtime; standalone `--insert math.exec.abd` links automatically. Header namespace `4d41` is assumed; the runtime assigns its actual location. Floating APIs take double, integer APIs int, and math_pow `(double, int)`; extern types must match exactly. See [the math guide](stdlib/MATH.en.md) for compilation, integration, APIs, int32 domains, rounding boundaries, and accuracy.

[`stdlib/containers.azs`](stdlib/containers.azs) is the same kind of library, hinted `AZSCRIPT_CONTAINERS`. Clients include [`stdlib/containers.include.azs`](stdlib/containers.include.azs). `List<T>`, `Stack<T>`, and `Queue<T>` still store elements in linked nodes and keep O(1) ends. `Vector<T>`, `Set<T>`, and `Map<K, V>` use a contiguous `buffer`. `Set` and `Map` default to `value_compare`; class-value keys need an ordinary comparator. The assumed namespace is `c071`. See [the container guide](stdlib/CONTAINERS.en.md) for operations, ownership, and IDs.

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

Return types are `void/int/string/float/double/boolean/address` or a declared class; `bool` aliases boolean. Forward calls, recursion, and arbitrarily nested calls are supported within nesting limits. Ordinary functions are top-level; members may be inline or defined as `ClassName::method`. Parameters may be `a,b` or `int a,int b`; object parameters need explicit class types (`C p` copies by value; `C * p` passes a pointer). Locals may use `int x = 2`; explicitly typed primitive locals require initialization. Explicit local/parameter types participate in compile-time checks; untyped parameters, var, def, and `#gvar` globals remain dynamic; typed globals are checked like typed locals. The interpreter also validates return types.

Script return values must match their declared type under the existing assignability rules; numeric returns additionally allow only these implicit widenings:

| Declared return type | Accepted numeric result types |
| --- | --- |
| `int` | `int` |
| `float` | `int`, `float` |
| `double` | `int`, `float`, `double` |

`int` to `float` may round, just like `float(x)`; for example, returning int 16777217 from a float function yields 16777216. No implicit float/double-to-int or double-to-float narrowing is permitted, even when the particular value fits. Write `return int(value);` or `return float(value);` to request a checked [explicit conversion](#explicit-conversions). For example:

```c
float widen(int value) { return value; }
int truncate(double value) { return int(value); }
float narrow(double value) { return float(value); }
```

A known narrowing return fails compilation, for example `return expected int, got double; narrowing requires an explicit cast such as int(x)`. A dynamic `any` result is checked against its actual runtime type: returning a double from an int function fails with `Function return type mismatch: expected int, got double`. The same exact-or-widening numeric rule applies at runtime; it never silently truncates a dynamic return. Existing class-pointer assignability and ownership rules still apply. Typed extern callback signatures remain exact and do not gain numeric conversion.

Blocks create lexical scopes and allow inner names to shadow outer names; duplicate declarations in one scope fail. Local IDs are unique across a function, avoiding sibling/earlier-child collisions. Declare before use; expired-scope access fails compilation. Globals are described [below](#global-variables). Parameters pass by value; script calls check argument counts.

Non-hint programs retain `__script_onload = 0`, `main = 0x0fff0000`, and `__script_pre_destroy = 1`; hooks must be parameterless void functions. Ordinary functions default to namespace `0xfff`. `#namespace 1234` accepts one to four hex digits (in source, namespaces and function IDs may have a `0x` prefix and any number of leading zeros, e.g. `#namespace 0x0005151` or `:00000000abcd`; the AST always writes plain hex digits, and `compile-json` accepts only plain hex digits within the field width, 4 for a namespace and 8 for a full ID); `#namespace fff` names the default namespace explicitly and gives the same IDs as omitting it, but still counts as the unit's single namespace directive. Namespace `0000` is reserved for lifecycle hooks and hint-library placeholders and `0abd` for runtime built-ins, so `#namespace` rejects both. `compile-json` likewise rejects a non-hint AST whose `function-namespace` is 0, and ordinary definitions in 0000 other than the lifecycle functions 0 and 1. `int add(int a,int b):0003 {return a+b;}` fixes the low 16-bit number; explicit numbers are reserved before automatic allocation. Fixed entry IDs cannot be changed by suffix. Automatic IDs may change with source; hosts should use the AST map from the same compilation. For stable public IDs, write each full ID once in a shared header's extern declaration; implementations in the module's own namespace (a hint library's self-assumed one) omit the number and inherit it. See [hint linking](../docs/HINT_LINKING.en.md).

`#namespace_hint NAME` declares an independent library. Ordinary library functions, including main, use placeholder 0000; 0/1 are reserved for lifecycle and automatic numbering starts at 2. `#assume_hint NAME abcd` supplies a call namespace relocated by flush. A compilation unit including active includes may have at most one namespace or namespace_hint directive. See [multi-file libraries and hint linking](../docs/HINT_LINKING.en.md).

Twenty built-ins require no declaration:

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
| `str_length` | `int(string)` | `0x0abd000b` |
| `str_byte_length` | `int(string)` | `0x0abd000c` |
| `str_substring` | `string(string, int, int)` | `0x0abd000d` |
| `str_char_at` | `string(string, int)` | `0x0abd000e` |
| `str_code_at` | `int(string, int)` | `0x0abd000f` |
| `str_from_code` | `string(int)` | `0x0abd0010` |
| `str_index_of` | `int(string, string[, int])` | `0x0abd0011` |
| `str_starts_with` | `boolean(string, string)` | `0x0abd0012` |
| `str_ends_with` | `boolean(string, string)` | `0x0abd0013` |

These names and `0xabd` IDs are reserved and cannot be script or extern declarations. Built-ins do not appear in AST abstract or extern-signatures.

`load_extern_library("xxx")` resolves the platform's xxx.dylib/.dll/.so and adjacent xxx.signature. After verification it calls the exported `azscript_load_extern`. Libraries use C++ host `registerExecutor`; scripts still carry compile-time extern signatures. This neither loads a hint module nor calls flush. See [external libraries](../docs/EXTERN_LIBRARY.en.md) for search rules, keys, and implementation.

External declarations use `extern int custom_call(string, double):12340001;`. Parameter names are optional, types mandatory; zero arguments use `extern void notify():12340002;`, never a void parameter. IDs accept full 32-bit hex patterns and optional `0x`. Old `#extern` is rejected. Externs may bind hosts or hint libraries. A same-name extern and definition may coexist with matching signatures; calls prefer the extern. Hosts must register the same ID and cannot take namespaces 0000/0abd/0fff or active script namespaces.

Extern contracts require exact types without implicit numeric conversion: double accepts neither int nor float. The compiler checks counts and known types and propagates extern result types through nested calls. Dynamic values of unknown type are rejected for parameters with declared types; use explicit declarations to establish their types. ABD carries the same signature. The interpreter validates actual arguments before callbacks and results immediately afterward, preventing mismatched host values from entering further script execution. Neither source declarations nor hand-authored AST may override built-ins; named host calls require extern declarations with complete signatures; ID-based reflection follows the rules below.

### Global variables

```text
class Node { int value; Node(int v) { value = v; } }
int total;                      // starts at 0
string label = "items";
Node * head = new Node(total + 1);
#gvar legacy                    // untyped (any)
```

A top-level declaration `T name;` or `T name = expression;` declares a typed global. T is a scalar type, `address`, or a class pointer such as `Node *` or `Box<int> *`. A typed global is checked like a typed local wherever it is used, so `head.value` works and `head = 3` fails to compile. Class values and buffers cannot be globals (`Global g cannot hold a class value or buffer; declare a pointer`), and neither can `void`, `extern` or a declaration with type parameters. `Node * g(1);` is rejected with `Global objects cannot be automatic; use = new Node(...)`; a function returning a pointer, such as `Node * make(int v) { ... }`, still parses. A typed global cannot share its name with a function, a built-in function, a class or another global, and cannot be named after a lifecycle hook (`__script_onload`, `__script_pre_destroy`).

Typed globals are initialized when the module is flushed, in its onload (function 0, `__script_onload`; the compiler adds one if the module has none). First every typed global gets its type's default: `0`, `0.0f`, `0.0`, `false`, `""`, or `null` for address and pointers. Then the explicit initializers run in declaration order, then the onload body. An initializer may call functions and read any global; a global declared later still holds its default. Initializer errors name the global, e.g. `in global total: Initializer for global total expected int, got string`. The initializers run inside onload, so storage owned by that call does not outlive it: an `alloc()` block or an automatic object returned to an initializer is released when onload returns, which leaves the global dangling. Use `new` for an object that must outlive onload; `make_free` and `mem_send_up` can retain only raw allocations, not object storage. A module's typed globals are void until its own onload starts. Another module can observe this through a cyclic module dependency, or when a module that initializes earlier calls into this one dynamically, for example with `reflect_invoke_function` from its onload. Reading such a global then fails where a type is checked at runtime, for example when a function returns it, but `print`, string concatenation and copies into untyped variables accept the void silently (it prints as `null`).

`#gvar name` remains the untyped form. Such a global is dynamic (`any`): it cannot hold class pointers, class values, buffers, or type-parameter values, gives no member access, and stays void until assigned, normally in onload. The AST lists `#gvar` names first (compile-json rejects a name after a typed entry); they take the first global slots, and typed globals follow in declaration order.

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

Classes are top-level only. Fields require a primitive, class (literal value), `Class *` (pointer), or `buffer<T>` type and occupy one slot each in declaration order, without a hidden header. Classes with zero total fields including inherited fields fail. Constructors share the class name and omit return types. No constructor declaration synthesizes a zero-argument constructor; declaring a parameterized constructor does not add one. Optional `~ClassName()` destructors take no arguments, return no value, and cannot be called directly. All members are public. Single inheritance and virtual methods are supported; multiple inheritance, ordinary function overloads, static members, and nested classes are not.

| Syntax | Meaning |
| --- | --- |
| `C x;`, `C x();`, `C x(args);`, `C x = expr;` | Literal object: a value in the variable's own slot block |
| `C * x(args);`, `C * x();` | Automatic pointer object on the shared heap, destroyed by the declaring block; zero arguments still require `()` |
| `C * x = new C(args);` | Manual object requiring delete, unaffected by block exit |
| `C * x;`, `C * x = null;` | Null pointer without object creation; may later refer to another pointer/manual object |

Literal initialization, assignment, arguments, and returns copy all slots, recursively copying literal fields but only addresses for pointer fields. Each copy is independent and has its own destructor. `a = b` assigns fields in place without changing a's address. Pointer initialization `C * y = x`, assignment, and arguments copy only addresses. Reassignment does not change cleanup responsibility for previously created objects. `C * p;` is exactly `C * p = null;`: no construction or responsibility for future assigned objects.

Value C and pointer C * are distinct without implicit conversion. Null assigns only to pointers/address. Literal objects cannot be directly compared, require declared operators for arithmetic, and cannot be stored in untyped positions: var, def, #gvar globals, untyped parameters, print, and `mem_get(p) = …` reject them. Use pointers for long-lived storage. Classes are nominal: a literal value assigns only to its own class, not to another class with the same field layout. Derived values cannot be assigned to base values (no slicing); upcasts apply only to pointers. Both use `.` for members. Only explicitly class-typed variables, parameters, fields, and results support member access: `var p = new C();` itself is rejected; use `C * p = new C();`. Class pointers, including `C (*)` borrows, cannot enter `var`, `def`, `#gvar`, untyped parameters, or raw `mem_get` stores. For example, the diagnostic says `cannot hold a class pointer (P *); declare it as P * or convert explicitly with address(p)`. Passing class pointers to `print` or reflection variadic arguments remains allowed; this does not relax print's existing rejection of class values, buffers, and type-parameter values. Reflection variadics continue accepting non-void values under their existing rules.

`C (*) p` (also `C(*) p`) accepts both C * and literal C arguments:

```c
int read(Point (*) p) { if (p == null) { return 0; } return p.x; }
void bump(Point (*) p) { p.x = p.x + 100; }

Point a(1);
Point * b = new Point(2);
read(a); read(b); read(null); // All three are valid
bump(a);                     // a.x becomes 101; borrowed by address, not copied
```

Inside the function p is C *, nullable and reassignable. For literal arguments the compiler passes their address without copying, so mutations are visible to the caller. Temporaries such as `read(make(4))` stay alive through the call and die at statement end. Compatibility follows C *: pointers/literals of the same or a derived class are accepted; another class with the same field layout is not, and a base literal cannot satisfy a derived (*) parameter. This syntax is only for class function parameters, including constructors, methods, and externs—not locals, fields, returns, or combinations such as `C * (*)`. Its ABI equals C * (address), but extern declarations and definitions must use matching signature spellings. Retaining the pointer beyond a literal's lifetime produces `Invalid or expired object address`; deleting a borrowed literal also fails at runtime.

Scripts can obtain a literal's storage address only through method this or a borrowed C (*) parameter, both typed C *. Methods/functions may return or retain it, but access after expiration fails. Underlying literal addresses are ordinary address values with the high bit marking internal storage; they support address comparisons and offsets at that level. Storage IDs are never reused, so expired addresses cannot point to later objects.

At each inheritance level, scalar/address/pointer defaults are set first: int 0, float 0.0f, double 0.0, boolean false, empty string, and null addresses/pointers. Then declaration order constructs literal fields (`C f(args);` or `C f = expr;`, defaulting to a required zero-argument constructor) and evaluates other explicit initializers. Finally the constructor body runs; bases complete these steps first. All initialization prefixes the actual constructor implementation and uses its module's globals/call bindings. Initializer names resolve this, members, then globals, unaffected by constructor parameters. Classes cannot contain themselves directly/indirectly by value; use pointers. Containment cycles through generic literal fields, such as `class A<T>{B<T> b;}` with `class B<T>{A<T> a;}`, are rejected too. Literal nesting is limited to 64 levels. Pointer fields are non-owning and require explicit delete in a destructor if desired.

Members use `obj.field`, `obj.method(args)`, or `this.member`, with chained access/calls. Inside classes, locals/parameters precede members, then globals. Receivers and explicit arguments evaluate left to right exactly once. Member assignment supports =, not compound += etc. Pointers compare for equality with pointers of the same or an inheritance-related class and null, but do not support address arithmetic. Integer 0 is not implicitly an object. Null is runtime address zero. Member access validates the object base before adding offsets, so null fails even for nonzero-offset fields.

Lifetime rules:

- Literal and automatic-pointer objects in one block are destroyed in reverse creation order, including normal exit, return, break, continue, and errors.
- Destruction follows C++ order: current destructor body → current literal fields in reverse order → base destructor body → base fields. Literal fields without an explicit destructor trigger a synthesized field-cleanup destructor.
- Unbound temporaries such as `make().x` die at statement end; if/while condition temporaries die immediately after condition evaluation.
- By-value parameters are caller-object copies and die when the call ends.
- Returning this function's owned local/parameter directly transfers it without copying or premature local destruction. Returning fields, globals, or borrowed values copies first.
- Returning an automatic-pointer object owned by this function transfers only the directly returned object to the actual calling block. Returning caller-owned objects or this leaves ownership unchanged. A returned new object remains manual.
- Failed construction skips the failed class's destructor body. It destroys that class's completed owning fields in reverse order, then cleans the completed base and its ancestors. A throwing field cleanup does not skip the other fields or the base; the original construction error and its trace remain primary.

`delete expression;` takes a pointer or null and destroys only new-created objects, including through aliases. Delete null does nothing. Automatic pointers, expired addresses, and objects being destroyed cause runtime errors; deleting literal values is a compile-time error. Other aliases are not cleared. Shared-heap addresses can be reused without generation tracking; avoid dangling heap pointers.

Destructor errors still release the current object and continue other cleanup. Existing errors win; otherwise the first destructor error propagates. Instruction, depth and native stack budgets also constrain destructors: exhaustion may prevent completing user code, but storage is reclaimed. Explicit close runs destruction hooks, cleans automatic objects owned by global scope, then unloads functions. Undeleted new objects receive no user destructor, but their slots/records are released with the script, avoiding leaks across repeated load/destroy cycles.

Literal objects do not cross JNI: Java calls returning them fail after destruction, and Java cannot pass or read them; use pointers. C++ hosts own returned literal handles without later user destructor execution; addresses expire when the last handle is released. C++ can directly use `blocks::create`, `blocks::resize`, `blocks::length`, and `blocks::address_of`. Growth preserves existing addresses; shrinking invalidates out-of-range access.

`alloc/mem_get/mem_free/make_free/mem_send_up` operate only on raw allocation storage. Reading, writing, releasing, or changing ownership through these APIs cannot bypass object lifetime rules: `mem_get`, `mem_free`, `make_free`, and `mem_send_up` reject object starts, interiors, and literal/view addresses, including during construction. Use typed members and `delete` for objects. Constructors, destructors, and methods remain ordinary functions with implicit first C * (runtime address). Classes are nominal at compile time; runtime member access checks object storage and bounds, not class identity. Use shared class declarations and extern members across modules; see [multi-file libraries](../docs/HINT_LINKING.en.md). [Class regressions](examples/classes-regressions.azs) return 0.

## Addresses and low-level memory

`address` is an independent uint64 type ranging from 0 to `18446744073709551615`, usable in locals, fields, parameters, and returns. Null is address zero. Explicit address locals require initialization; fields default to null. Int stays signed 32-bit; integer literals, allocation counts, offsets, variable IDs, and function IDs do not become addresses. No implicit conversions exist with integers, floating values, or booleans; integer 0 cannot replace null. Explicit conversion also rejects address/numeric pairs: `address(a)` preserves an existing address, while `string(a)` formats it as unsigned decimal. `address(p)` also explicitly erases a class pointer, a `C (*)` borrow, or a type parameter with a class-pointer bound, without changing its bits or ownership. A bare null expression is not a conversion source; see [explicit conversions](#explicit-conversions).

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

Class pointers keep their class types during compilation: no direct assignment to/from raw address, raw-address member access, or raw-address delete. Explicit `address(p)` erases a pointer when raw address data is needed; literals remain object values. This is one-way erasure, not a cast back from address/any to a class pointer, and it grants no access to object slots through raw memory APIs. Object returns use ro to transfer actual ownership. Each address field occupies one slot and inherited prefixes remain unchanged.

[Address regressions](examples/address-regressions.azs) show slot traversal, stored addresses, and explicit raw-allocation transfer, returning 0.

### Object storage and known limitations

`address(p)` may be stored as raw address data, but does not expose the object behind it. `mem_get(address(p))`, including assignment through it, fails with `mem_get cannot access object storage; use typed member access`; `mem_free`, `make_free`, and `mem_send_up` use the same message with their own names. Class allocations are tagged before constructor code runs, so erasing `this` during construction does not evade the checks. Typed member access requires object storage; a raw `alloc()` range fails with `Object member access requires object storage`.

Extern class-pointer results and class-pointer arguments supplied by C++/Java hosts remain trusted declarations. Their address tag is checked; member access requires live object storage and checks offsets, but does not establish nominal class identity or verify all fields. Hosts should return pointers obtained from matching typed script wrappers rather than construct class objects through raw memory APIs. JNI raw writes and `memFree` reject object storage as well.

These checks are not complete heap memory safety. Heap addresses have no generation tracking: after an address is reused for another object, a stale alias may refer to that object, so use after delete and double delete are not generally prevented. Deletion does not clear aliases. Automatic objects can still escape through retained aliases or fields; keep them within their owning lifetime. Normal direct-return ownership transfer does not make every escape safe. Do not rely on these checks as a sandbox or as a proof of pointer lifetime.

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

Use `class Child : Base` or `class Child : public Base`. Forward-declared-in-source bases work; cycles, unknown bases, and multiple bases fail. Layout errors, such as an inheritance cycle, a class without fields, or a class containing itself by value, are reported by the compiler at the class declaration (e.g. `main.azs:1:7: error: in class A: Inheritance cycle involving A`), identically for source and `compile-json`. Layout recursively keeps all base slots as a prefix and appends own fields in order. A derived class inheriting at least one field needs no new fields. There is no hidden header. Base methods receive the same starting address with no adjustment.

Derived classes directly access inherited members and may shadow names. Non-virtual methods bind to the expression's static class; names inside base bodies retain base resolution. Declared virtual methods support pointer dispatch as described below.

Inside a class function (method, constructor, destructor or field initializer), the qualified call `Base::method(args)` calls the method visible in `Base` on `this`, binding statically even when the current class shadows it with a method or field: `int describe() { return Point::describe() * 100 + tag; }`. `Base` is the current class or one of its bases and is named without type arguments, which come from the inheritance chain (`Box::get()` inside `class IntBox : Box<int>`); a generic method may take explicit type arguments, as in `Box::echo<string>("e")`. A statement may start with such a call. The compiler rejects a qualified call outside class functions (`A::f() calls are only available inside class functions`), a class that is not the current class or a base (`A is not B or one of its base classes`), an unknown method or a field (`Unknown method: A.h`, `A.x is a field, not a method`), constructors and destructors, operator methods, a receiver such as `this.A::f()`, type arguments on the class (`Name a base class without type arguments in A::method(...)`), and a name that is not a class.

Derived pointers convert to base pointer variables, parameters, and results without address/ownership changes; equality also accepts this relationship. Base pointers do not implicitly become derived pointers with extra fields. Derived literal values cannot be assigned to base literals (no slicing). Unrelated classes never convert, even with identical field layouts. A derived class without fields of its own is still a different class, so a base pointer does not implicitly become one.

Construct the base first, then default current fields, execute current initializers in order, then the current body. Omitting a base initializer calls Base(), which must exist. `Child(int n) : Base(n + 1) { ... }` passes explicit arguments using derived constructor parameters, evaluated left to right once. Field initializers still resolve members and implementation globals, unaffected by parameter shadowing. Explicit constructor IDs precede base initializers: `Child::Child(int n):0002 : Base(n) { ... }`.

Completed objects destruct from most-derived to root, disposing of each level's literal fields after its body. Missing derived destructors, early returns, and errors do not skip base cleanup. Automatic cleanup, literal expiration, and deletion through base pointers all use the complete chain registered at creation; this cleanup chain is independent of virtual method declarations. Failed construction first cleans the failed class's completed owning fields in reverse order, then its successfully completed base chain; it skips the failed class's destructor body. A field cleanup error does not skip later cleanup or replace the original error and trace; storage is still released. Budget exhaustion still releases storage but may prevent full user destruction.

Cross-hint inheritance uses shared declarations with extern constructors/methods/destructors; base and derived implementations may be separate modules. Readable AST retains inheritance and field order; exec contains addresses, offsets, function references, and method bindings without nominal class names. Flush links constructors, destructors, and virtual method bindings across modules. See [inheritance regressions](examples/inheritance-regressions.azs).

### Virtual methods

```cpp
class Base {
    int value;
    Base(int n) { value = n; }
    virtual int score() { return value; }
}
class Child : Base {
    Child(int n) : Base(n) {}
    override int score() { return Base::score() + 100; }
}
void main() {
    Base * p = new Child(7);
    print(p.score()); // 107
    delete p;
}
```

`virtual` declares a virtual slot. Replacing an inherited virtual method requires `override`; `virtual override` is also allowed and reuses that slot. `virtual final` or `override final` prevents further overriding. `final` cannot make an ordinary method virtual or bypass the required `override`. Parameters must match exactly; a concrete class-pointer result may narrow covariantly to a derived pointer, while generic result type parameters must match exactly. An override of a non-virtual or missing method is rejected.

These modifiers apply only to ordinary member methods without method type parameters. Constructors, destructors, operators, top-level functions, and out-of-class definitions reject them. Abstract methods are unsupported: a non-extern declaration needs a body. Shared headers may declare `extern virtual int score():12340002;` inside an ordinary `class`; the out-of-class definition uses plain `int Base::score() { ... }` without repeating modifiers. All modules must agree on the shared declarations and slot order; see [hint linking](../docs/HINT_LINKING.en.md).

Calls through class pointers, `C (*)` borrows, `this`, and a type parameter with a class-pointer bound select the current object's method binding. Literal-value receivers, value-bound type parameters, statically known final slots, and `Base::method(...)` use static binding. Non-virtual calls retain their existing behavior. Receiver and arguments evaluate left to right once. A null, expired, foreign-script, or unbound receiver fails; modules mounted in the same script share its ownership boundary.

Construction and destruction use the current class's table: a virtual call during the base constructor calls the base implementation; derived construction installs the derived table, and destruction switches tables as it proceeds back through the bases. Method tables live outside user slots, so field offsets and buffer strides do not change. Copies, including buffer element views, retain bindings and bound generic contexts; fieldwise assignment keeps the destination's table. This does not add nominal class checks or remove the heap-lifetime limitations above. See [virtual regressions](examples/virtual-regressions.azs).

## Expressions and control flow

Precedence from lowest to highest:

| Operators | Associativity |
| --- | --- |
| `= += -= *= /= %= &= \|= ^= <<= >>= >>>=` | Right |
| `?:` | Right, one selected arm |
| `\|\|` | Left, short-circuit |
| `&&` | Left, short-circuit |
| `\|` | Left |
| `^` | Left |
| `&` | Left |
| `== !=` | Left |
| `< <= > >=` | Left |
| `<< >> >>>` | Left |
| `+ -` | Left |
| `* / %` | Left |
| Unary `+ - ! ~` | Right |
| Parentheses, literals, variables, calls | Highest |

Thus `20-3-2` is 15, `100/5/2` is 10, and `2+3*(4+1)` is 17. `a=b=3` assigns from the right. Short-circuited right operands of &&/|| are not evaluated.

Conditions in `if`, `while`, and `for`, including functional syntax, the condition of `?:`, and operands of `!`, `&&`, and `||` must be boolean. There is no numeric, string, address, or void truthiness: use `n != 0` or `p != null` explicitly. Known non-boolean operands fail compilation, e.g. `if condition must be boolean, got int`. Dynamic `var`, `def`, or untyped parameter values are checked when evaluated and fail with `Condition must be boolean, got int` when their actual type is int. Short-circuiting still skips the right operand when the left boolean determines the result.

Equality `==` / `!=` accepts these operand pairs and returns boolean:

| Operand pair | Comparison |
| --- | --- |
| Any mixture of `int`, `float`, and `double` | Numeric equality, including mixed numeric types. |
| `boolean` and `boolean`; `string` and `string` | Same-type value equality. |
| `address` and `address` or `null` | Equality of unsigned address values; null is address zero. |
| Class pointers of the same or an inheritance-related class; a class pointer and `null` | Pointer identity; unrelated class pointers and raw-address/class-pointer pairs are rejected. |
| `null` and `null` | Equal. |
| Dynamic `any` with another dynamic value or a non-class scalar | Deferred to runtime; actual values must form a compatible pair above. |

Other known pairs fail compilation, e.g. `Equality requires compatible operand types, got string and int`. Dynamic incompatible pairs fail at runtime instead of returning false/true. Booleans are not numbers, and integer zero is not null. Class values and buffers have no generic equality; compare their fields or elements explicitly. Unbounded type parameters do not gain an equality operation. Statically void operands cannot be compared. At runtime, two void values are equal (`eq` true, `ne` false); this also applies to dynamic variables that have not received a value.

```text
// Original functional control syntax
while (i < 10, { i += 1; });
if (i == 10, { print("yes"); }, { print("no"); });

// Conventional block syntax
while (i > 0) { i -= 1; }
if (i == 0) { return 1; } else { return 2; }
```

`mem_get(pointer) = value` is supported and evaluates its pointer once. Compound memory assignments such as += are rejected to prevent duplicate side effects. Store the pointer first, then write `mem_get(p) = mem_get(p) + value`. Other calls cannot be assignment targets.

Return exits through nested if/while/for/switch blocks. Break exits the innermost loop or switch; continue skips the rest of the nearest enclosing loop iteration, even from inside a switch. Break requires a loop or switch, and continue requires a loop; neither can cross functions. Exited blocks still clean their objects. While continue rechecks its condition; for continue runs its step before the condition. Break, return, and errors skip the step.

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

Every normal exit of a nonvoid function must return. Both if branches must return. While/for counts as nonterminating only with literal true conditions (or an omitted for condition) and no break belonging to that loop; otherwise compilation reports `non-void function can reach the end of its body without returning a value`. Statements end in semicolons, optional immediately before block end; function-definition trailing semicolons are optional. Object-literal expression syntax, closures, and exception handling are unsupported and rejected. Indexing is available for strings, buffers, and classes with an index operator.

Integers are signed 32-bit; out-of-range literals fail compilation. Decimal/scientific literals default to double; f/F produces float, e.g. 1.5f, 5f, 2e3f. Both reject overflow and underflow to zero. Numeric print/string concatenation uses shortest round-trippable text: `0.1+0.2` → `0.30000000000000004`, `123456789.0` → `123456789`, `1e20` → `1e+20`; float uses its own precision (0.1f → 0.1), and negative zero prints -0. Runtime-only zero divisors compile but int/float/double division, including positive/negative floating zero, throws `Division by zero`, never Infinity/NaN. Arithmetic integer overflow also fails at runtime; bitwise shifts use the bit-pattern rules below.

### switch

`switch (value) { ... }` is a statement, not an expression. It evaluates its int or string value once and selects a matching literal label. Other types, including dynamic `any`, are rejected; convert a dynamic value explicitly, for example `switch (int(value))` or `switch (string(value))`.

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
        print("bye"); // final section has an implicit break
}
```

Case labels must be int literals (including negative integers) for an int switch or string literals for a string switch; expressions, variables, mismatched types, duplicate labels, and multiple defaults are rejected. Stacked labels share one section. `default` may appear anywhere and runs only when no case label matches. Without a matching case or default, execution continues after the switch. Statements before the first label and `case`/`default` outside a switch are errors.

There is no fall-through. Every non-final section must leave via `break`, `return`, an applicable `continue`, or another path that cannot complete normally; a branch that can reach the next section is an error. The final section may finish normally and gets an implicit break. Each section has its own local/object scope; stacked labels share that scope, and leaving it performs normal cleanup.

`break` exits the innermost enclosing loop or switch. `continue` inside a switch continues the nearest enclosing loop, including its for-step; a switch without an enclosing loop cannot use `continue`. Nested switches preserve this targeting. A switch with a default and every section returning can satisfy a nonvoid function's return requirement; without a default, an unmatched path remains.

Selection uses comparisons rather than a jump table and consumes execution steps proportional to the labels checked before a match, or all labels when none matches. No new runtime opcode is introduced.

### Bitwise and shift operators

`&`, `|`, `^`, and unary `~` are bitwise AND, OR, XOR, and complement. `<<` shifts left, `>>` shifts right with sign extension, and `>>>` shifts right with zero fill. Operands, including shift counts, must be int; known float, double, boolean, string, and address operands fail compilation, and dynamic operands must actually be int at runtime. These operators evaluate their operands once, left to right, without short-circuiting.

Operations use 32-bit bit patterns and return signed int32. Left shift discards high bits and fills low bits with zero; this wrap does not raise arithmetic overflow. For example, `1 << 31` is -2147483648, `-8 >> 1` is -4, `-1 >>> 1` is 2147483647, and `~0` is -1. Every shift count must be 0…31, inclusive: invalid literal counts fail compilation, and invalid dynamic counts throw `Shift count must be between 0 and 31, got 32` (for count 32). Counts are never masked modulo 32.

Shift operators bind less tightly than addition/subtraction and more tightly than comparisons. Equality binds more tightly than bitwise `&`, then `^`, then `|`; all three bind more tightly than `&&` and `||`. Parenthesize a bit test as `(flags & mask) == 0`; `flags & mask == 0` attempts to apply `&` to a boolean and is rejected with a hint to parenthesize the comparison.

Shift characters must be adjacent in the preprocessed text: `a >> b` is a shift, but `a > > b` is not and is rejected. Generic type closers remain separate in type syntax, so `buffer<buffer<int>>` and generic calls followed by shifts remain valid. Simple variables also support `&=`, `|=`, `^=`, `<<=`, `>>=`, and `>>>=`; members, subscripts, and `mem_get` are not compound-assignment targets. Unary `~x` lowers to `bit_xor(x, -1)` and introduces no separate unary opcode.

### Conditional expressions

`condition ? thenValue : elseValue` evaluates a boolean condition once, then evaluates only the selected arm once. The skipped arm has no side effects, but both arms are type-checked. `?:` binds more tightly than assignment and less tightly than `||`, and associates to the right: `a ? b : c ? d : e` means `a ? b : (c ? d : e)`.

```c
boolean ready = true;
double value = ready ? 1 : 2.5; // double 1
int divisor = 0;
int safe = ready ? 7 : 10 / divisor; // 7; the unused division does not run
```

The arms must have a common type:

| Arm types | Result type |
| --- | --- |
| Numeric types | Widest of double, float, int; a selected narrower arm is converted to that type. |
| Same non-void type | That type, including same-class values or the same buffer type. |
| Base and derived class pointers | The base pointer type. |
| Pointer or address with `null` | The pointer or address type; a pointer-bound type parameter `T` with `null` retains `T`. |

Numeric promotion follows explicit-conversion rounding rules, so int-to-float may round. Void arms, unrelated class pointers, differing class-value types, and incompatible scalar pairs such as string/int are rejected. Related class values do not implicitly slice to a base value. Two dynamic `any` arms retain `any`; pairing `any` with a different known type is rejected, so convert the dynamic operand explicitly when needed.

The selected arm runs in the enclosing scope; selection creates no branch scope like functional `if`. Owned literal temporaries remain alive through the usual statement boundary, and automatic pointer objects retain the surrounding block lifetime. Existing ownership and cleanup rules still apply. Write checks recurse into both arms: `(ready ? items[0] : local).field = 1` is rejected when `items[0]` is a getter-produced class-value copy, even when that arm would not run. Read the copy into a typed local, modify it, and write it back instead.

Spaces around `:` are optional: `ready?1:2`, `ready?true:false`, and `ready?1:1e5` are valid. Expression literals still receive normal numeric checks. This does not change hexadecimal function-ID suffixes such as `:0002` or `:1e5`, constructor base initializers, or qualified `Base::method()` calls.

### Explicit conversions

`int(x)`, `float(x)`, `double(x)`, `string(x)`, and `address(x)` take exactly one expression and evaluate it once. They are reserved compiler built-ins, not user-overridable functions. These are the supported source/target pairs:

| Source | `int` | `float` | `double` | `string` | `address` |
| --- | --- | --- | --- | --- | --- |
| `int`, `float`, or `double` | Truncate toward zero, then check int32 range | Round to float; check overflow | Convert to double | Existing shortest round-trip numeric text | Rejected |
| `string` | Parse decimal integer | Parse decimal number as float | Parse decimal number as double | Identity | Rejected |
| `address` | Rejected | Rejected | Rejected | Unsigned decimal text | Identity |
| Class pointer, `C (*)` borrow, or pointer-bound `T` | Rejected | Rejected | Rejected | Rejected | Explicit erasure; no ownership change |

Known boolean, void, class-value, buffer, null, and type-parameter sources without a class-pointer bound are rejected. Class pointers and pointer-bound type parameters support only the explicit address erasure above, with no opcode 49; value-bound and unbounded T remain rejected. There are no boolean, void, class, buffer, or type-parameter conversion targets. `int(addressValue)`, `address(7)`, and `address("7")` remain unsupported. `bool(x)` / `boolean(x)` report `Boolean conversions are not supported; write a comparison`. A C-style `(int)x` reports `C-style casts are not supported; write int(x)`.

Numeric-to-int truncation happens before the range check: `int(2147483647.9)` is 2147483647 and `int(-2.9)` is -2. Floating conversions may lose precision (`float(16777217)` is 16777216), allow subnormals and underflow to zero, and reject overflow or non-finite results. These underflow rules apply to conversions from both numbers and strings; the source-literal range checks described above remain separate.

Integer strings must match `[+-]?[0-9]+`, with no decimal point or exponent. Float/double strings allow an optional sign, decimal digits with an optional point (at least one digit), and an optional `e`/`E` exponent with an optional sign and at least one digit. Parsing is locale-independent and uses `.` as the decimal point. Whitespace, hex, `f` suffixes, NaN/Infinity, embedded NUL, and trailing text are rejected. For example, `int("+7")` and `double("2.5e-3")` succeed; `int("1e2")` and `float(" 1")` fail. String contents and range are checked at runtime even when the argument is a string literal.

Unsupported known type pairs fail compilation with `Cannot convert X to Y`; dynamic `any` arguments are checked using their actual runtime tag. A known same-type conversion is an identity and emits no conversion opcode, while `any` always emits opcode 49, including when its actual type already matches. Runtime error messages include:

```text
Cannot convert double to int: value is out of range
Cannot convert string " 1" to int: invalid decimal integer
Cannot convert string "1f" to float: invalid decimal number
Cannot convert string "1e400" to double: value is out of range
```

Quoted string values in errors are limited to the first 64 Unicode scalar characters, followed by `...` inside the quotes if truncated. Quotes/backslashes are escaped; non-ASCII characters and NUL use ASCII UTF-16 escapes, such as `\ud83d\ude00` and `\u0000`, so messages remain safe to carry through JNI.

## Strings, comments, and preprocessing

Strings use single or double quotes and support `\\`, `\"`, `\'`, `\n`, `\r`, `\t`, `\b`, `\f`, `\0`, and `\uXXXX`. UTF-16 surrogates must be paired (e.g. `\uD83D\uDE00`); isolated surrogates fail compilation. Spaces, commas, parentheses, semicolons, and braces inside strings are preserved. Strings cannot span physical newlines. Comments use // or /* ... */. Unclosed parentheses, strings, escapes, and comments report positions.

### String functions

String indices, lengths, search positions, and substring endpoints count Unicode code points, starting at zero. They do not count UTF-8 bytes, UTF-16 code units, or grapheme clusters: an emoji outside the BMP counts as one, while a base letter plus a combining mark counts as two. Only `byte_length` counts UTF-8 bytes. Matching is exact and case-sensitive, with no Unicode normalization. Embedded NUL is an ordinary code point and does not terminate a string.

| String form | Equivalent built-in | Result |
| --- | --- | --- |
| `s.length()` | `str_length(s)` | Code-point count. |
| `s.byte_length()` | `str_byte_length(s)` | UTF-8 byte count. |
| `s.substring(begin, end)` | `str_substring(s, begin, end)` | String over the half-open range `[begin, end)`. |
| `s.char_at(i)` or `s[i]` | `str_char_at(s, i)` | One-code-point string. |
| `s.code_at(i)` | `str_code_at(s, i)` | Unicode scalar value as int. |
| `s.index_of(needle[, from])` | `str_index_of(s, needle[, from])` | First matching code-point index at or after from, or -1; from defaults to 0. |
| `s.contains(needle)` | `str_index_of(s, needle, 0) >= 0` | Whether the needle occurs. |
| `s.starts_with(prefix)` | `str_starts_with(s, prefix)` | Whether the prefix matches. |
| `s.ends_with(suffix)` | `str_ends_with(s, suffix)` | Whether the suffix matches. |
| `str_from_code(code)` | Same function; no receiver form | String containing one Unicode scalar value. |

```c
string text = "A😀中";
print(text.length());         // 3
print(text.byte_length());    // 8
print(text[1]);               // 😀
print(text.substring(1, 3));  // 😀中
print(text.index_of("中"));    // 2
print(str_from_code(128512) == text[1]); // true
```

`char_at`, `code_at`, and indexing require `0 <= i < length`. Substrings require `0 <= begin <= end <= length`; an empty range yields an empty string. Search starts require `0 <= from <= length`. An empty needle matches at from, including at length; empty prefixes and suffixes always match. `str_from_code` accepts 0…0x10ffff except surrogate values 0xd800…0xdfff; code 0 produces NUL.

Indices and code values must be int, string arguments must be string, and void arguments are rejected. Receivers and arguments evaluate once, left to right. Only the snake_case method names above are provided; `contains` is method-only and uses no separate built-in ID. Both two-argument `str_index_of(s, needle)` and one-argument `s.index_of(needle)` compile with an explicit zero start. String methods and indexing bind directly to the reserved string built-ins, unaffected by similarly named class methods.

Strings are immutable. `s[i] = value` fails with `Strings are immutable; cannot assign through an index`; construct a new string and assign the variable instead. Bad host-provided UTF-8 is rejected by every string function, including `byte_length` and searches with an empty needle. Index/range errors use `std::out_of_range` (JNI `IndexOutOfBoundsException`), such as `String index 5 is outside length 3` or `String substring start 2 is after end 1`. Invalid UTF-8 and invalid scalar values use `std::invalid_argument` (JNI `IllegalArgumentException`), with `Invalid UTF-8 string` or `Invalid Unicode code point: 55296`. Runtime traces retain these exception types.

### Preprocessing

Supported preprocessor directives may be indented:

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

`#include "path"` searches the directory of the including file, then each include directory (`-I`, or `AzScript.includeDirectories`) in order, and uses the first regular file found; a directory of that name does not end the search. The wrappers' standard library directory follows every `-I` directory. The include roots are the main file's directory, or the base directory of a string source, and the include directories, and an include must stay inside one of them. Absolute paths (`Absolute include path "/etc/passwd" is not allowed`) and paths that lead out of every root (`Include "../x.azs" escapes the allowed include roots (...)`) are rejected without looking at the file system. Symbolic links inside a root are followed, and an include whose target lies outside every root is rejected without being read, although the error can show whether the target exists. `..` is resolved lexically, before symbolic links; an include directory given through a symbolic link also contains the paths spelled through that link. Every file in the subtree of a root can be included and may then appear in diagnostics, so compile untrusted source only with roots that hold nothing private. `--unrestricted-includes` (`AzScript.unrestrictedIncludes`) allows absolute paths and paths outside the roots. A missing include lists every searched path, e.g. `Include not found: "x.azs" (searched /work/app/x.azs, /work/lib/x.azs)`, and says how many candidates outside the roots were not searched. Write the path in double quotes, with `/` separators; the older unquoted form `#include lib/x.azs` takes the rest of the line, and `#include <x.azs>` is rejected.

Setattr affects conditions without substitution. Define substitutes whole identifiers, not strings, comments, numeric literals, or substrings of identifiers. Identifier characters adjacent to digits (5N, 0x10, 2e) belong to that numeric token and are not expanded; the lexer diagnoses invalid suffixes. Parameterized macros are unsupported: `#define F(x) ...` reports `Function-like macros are not supported: F; write a function, or put a space before '(' for an object-like macro`, while `#define F (x)`, with a space before `(`, is an ordinary macro whose value is `(x)`. Include/macro cycles, expansion beyond 64 levels, lines over 1,048,576 characters, comments included, before or after macro expansion (`Source line exceeds 1048576 characters` / `Macro expansion exceeds 1048576 characters on one line`), and unmatched conditions fail. Unknown directives fail even in inactive branches; use comments for prose. Macro substitution is textual; parenthesize complex values, e.g. `#define VALUE (1 + 2)`.

`#pragma once` expands its file once per compilation: a later include of the same file produces nothing and does not count toward the include limit. Files are identified by their real path with symbolic links resolved, however the include spells them. The check precedes the include-cycle check, so a `#pragma once` file that is included back through another file is skipped; a file that includes itself before reaching its `#pragma once` is still a cycle. A `#pragma once` in an inactive conditional branch has no effect. Only `once` is supported; in an active branch any other name or trailing text is an error, while, as in C, an inactive branch does not check the pragma. A string source cannot be included, so a `#pragma once` in it has no effect. `#ifndef` include guards keep working.

Source files, main and included, must be regular files: directories, FIFOs and devices are never opened. They must be valid UTF-8; an invalid byte is reported with its line and column. Each file is read at most once per compilation, and repeated includes reuse its text. Preprocessing has these limits:

- 16 MiB (16,777,216 bytes) per source file, and 64 MiB for all distinct source files one compilation reads;
- 64 levels of include nesting and 4096 include expansions per compilation, not counting includes skipped by `#pragma once`; headers included repeatedly should use `#pragma once`, since an include guard neither reduces the count nor avoids scanning the header again at every include;
- 16,777,216 characters of preprocessed text, with every include and macro expanded, not counting the newline that ends the last line, so a source file of the largest size without includes or macros stays within it;
- 4,194,304 lexical tokens per program.

A leading UTF-8 BOM is ignored. Reserved words include return, if, else, while, for, switch, case, default, break, continue, def, var, class, new, this, null, delete, extern, public/private/protected/virtual, override, final, any, all type names, and true/false; none may name classes, fields, methods, type parameters, functions, parameters, variables, globals, macros, or externs (`'any' is a reserved word and cannot be used as a name`). Identifier characters follow the Java identifier rules for characters of the Basic Multilingual Plane (letters, digits, `_`, `$` and the like, not starting with a digit), except that control and format characters, such as the zero-width space U+200B or the bidirectional override U+202E, are never part of one, so two names cannot look alike; in source they are reported as `Unexpected character: U+202E`, and so is a U+FEFF anywhere but at the start of a file. Diagnostics print such characters as escapes, e.g. `Invalid field name: x\u202e`. `compile-json` applies the same rules to class, field, method, type-parameter, function, parameter, local, and global names in a handwritten AST, e.g. `in class P: 'any' is a reserved word and cannot be used as a field name`; local names starting with `#` are left to compiler-generated variables.

### Expression size and nesting

The executable remains limited to 128 physical ABD container levels. The compiler measures the encoded shape, including expression records, argument/statement lists, constant wrappers, operation contexts and their child lists. A function body begins at level 4. Source operator count alone is therefore not a depth limit: when safe, the compiler saves intermediate results in hidden locals and emits shallower statements.

Splitting is available for eligible eager parts of initializers, returns, expression statements, if conditions, and the per-iteration condition/step of a for loop; each cut must preserve evaluation, copying, and lifetime rules. Owning captures are released after the original statement, and a return preserves its result before releasing them. An if condition is checked and its owned temporaries are released before either branch; `?:` and boolean-expression temporaries retain their ordinary expression/statement lifetime.

A borrowed owning value or dynamic any value can be captured only where the original operation already materializes that argument/value: an ordinary or virtual call parameter, buffer argument, or comparison operand. Capturing a borrowed buffer receiver is rejected because copying it would change alias identity. A scalar prefix cannot move owning native/extern arguments or comparison/buffer snapshots into an earlier statement. Retaining a fresh owned receiver across later script calls requires a transitive proof that those functions and their callees preserve scope and lifetime; a scalar return type alone is insufficient. `mem_send_up`, reflection/library loading, unknown host or virtual calls, unproved class/member/buffer/generic operations, and values whose any type is unknown prevent that proof. A later call that might return an automatic pointer also makes the cut unsafe. Such cases report `Expression nesting exceeds the ABD limit of 128 levels; this cut would change object lifetime or copying; store this operand in a local variable first`.

For raw-memory or typed-member assignment, an RHS that needs splitting must be free of calls and mutations. Otherwise the diagnostic ends `; store RHS in a local variable first`: the available instructions cannot retain the original storage reference across arbitrary RHS effects. A pure RHS can be split after capturing and validating the target; typed member access retains its object base and original field offset, without becoming raw memory access.

A cut inside a lazy operand (the right side of `&&`/`||`, either `?:` arm, or a functional-if arm) or a while condition cannot be moved before its enclosing statement. If such a cut is needed, the compiler reports the source position and asks for a local variable. The diagnostic starts `Expression nesting exceeds the ABD limit of 128 levels`, followed by `; store this operand in a local variable first` or `; store this condition in a local variable first`. For a lazy arm, put the calculation inside the selected branch; for a while condition, compute it inside the loop before an explicit break check so it is reevaluated each iteration. Deep postfix/member/call chains and genuinely nested syntax still consume depth; automatic splitting is not an unlimited nesting guarantee.

Generic and pointer type expressions retain a separate 64-level limit. Parser recursion and JSON containers have a 320-level guard; compilation has a 640-level expression-recursion guard. Cyclic ASTs are rejected. Errors remain located and do not prevent later compilations in the same process. The Java `Compiler.compileExpression` API also validates the tree and encoded depth. It can lower a statement array, but a standalone expression needing sibling statements reports `Expression requires statement-level spilling; store this operand in a local variable first`; place such work in a statement array or function.

`getDepth()` reports implementation-defined runtime scope depth, not source brace nesting; compiler-generated scopes may affect it.

### Long operator chains

A binary chain at one precedence level with more than 128 operands uses a flat readable `chain` node. At 128 operands or fewer, the parser keeps the existing nested AST. Parentheses, precedence changes, and postfix/member/call nesting still form separate expressions and retain their nesting limits.

All-`&&` and all-`||` chains are balanced while preserving left-to-right short-circuit evaluation. Each operand is checked as boolean at its original checking operator before later effects, preserving error positions without introducing a statement scope. A string `+` chain is balanced only when every operand is a string literal, a string local/parameter, or a call with a checked string result. Other chains retain their left fold in bounded segments: numeric rounding, intermediate overflow, mixed numeric/string addition, calls, and side effects keep their original order. For example, the numeric prefix of `1 + 2 + "x"` still produces `"3x"`; balancing must not turn it into `"12x"`. Operators and operands are not evaluated more than once.

The flat AST reduces compiler nesting; it adds no executable opcode or format version. Its lowered output must still fit the physical ABD limit and obey all statement-splitting restrictions above. Left-fold segments pass through the same safety checks; an owning overloaded-operator result that would need an unsafe chunk capture is rejected with the lifetime/copying diagnostic. Ordinary expressions that already fit retain their existing AST and ABD shapes.

### Long else-if ladders

Statement `if (...) { ... } else if (...) { ... } ... else { ... }` ladders test conditions left to right until the first match, execute that one body, and skip later conditions and bodies. The final else is optional. Every condition must be boolean; branch scopes and normal cleanup remain unchanged. A branch's return, break, or continue retains its original source target, including inside surrounding loops and switches.

The parser collects ladders iteratively. More than 128 conditions use a flat readable `if-chain`; at 128 or fewer it preserves the existing nested AST. Compilation first tries ordinary nested ifs and their supported cuts at the actual remaining ABD depth. If they still do not fit, it attempts flat lowering for the whole ladder, subject to the scope checks below. This also applies to shorter ladders inside loops or other nested statements: 128 is an AST representation threshold, not a guarantee that 128 nested executable ifs fit.

Flat lowering keeps the first predicate's evaluation and boolean check in the original containing scope when it can emit that check beside the ladder. Its literal temporaries end before the selected branch, while automatically returned pointers retain their original owner. Later predicates and moved bodies require a transitive proof that their operations and called helpers preserve scope and lifetime. Scalar return types alone are insufficient: `mem_send_up`, unsafe indirect helpers, reflection/library loading, unknown host/virtual calls, unproved class/member/buffer/generic work, and unknown any values prevent this proof. Calls in later predicates must not introduce address, any, or object results. An unbraced first predicate also requires proof when sibling emission cannot retain its exact scope.

If these checks fail, compilation reports `Flattening this if ladder would change object lifetime or scope; store this operand in a local variable first`. More than 128 branches does not guarantee successful compilation. Make lifetimes explicit with appropriately scoped locals or restructure the ladder; computations belonging to skipped branches must remain conditional.

Flat lowering applies only to statements. Value-producing functional-if expressions keep their expression semantics and nested representation when they fit; a ladder that cannot fit safely reports a located limit error rather than becoming a void statement. Each condition and body still obeys the splitting restrictions: a flat ladder does not permit unsafe owning captures or cuts inside lazy operands. Truly nested blocks and expressions retain their limits. No new opcode, exec version, or snapshot version is introduced.

### Diagnostics

Compile errors use the gcc/clang layout `file:line:column: error: [in context: ]message` and go to stderr. Positions always name the original file, line, and column, also after `#include` and macro expansion:

```text
inc/lib.azs:6:3: error: in function broken: Initializer for s expected string, got int
main.azs:2:1: note: in file included from here
Error: compilation failed: 1 error
```

- An error inside an included file is followed by one `note: in file included from here` line per enclosing `#include`, innermost first.
- A lexical or syntax error at a token produced by a macro is reported at the macro's use, followed by `note: in expansion of macro 'NAME' defined here` at its `#define` and, when the `#define` is in another file, that file's include chain. Semantic errors in expanded code are reported at the macro's use without this note. Tokens after an expansion keep their own columns.
- Preprocessor errors point at the directive's `#`; an unclosed condition is reported at its opening directive and an unterminated comment where it starts. A missing main file reads `Cannot open source file "x.azs": file not found` and a missing include `Include not found: "x.azs" (searched /work/x.azs)`, with the searched paths absolute, an include that exists but cannot be opened, such as a symbolic link loop, `Cannot open include file "x.azs": <reason>`, files that are not regular read `Source file "x.azs" is not a regular file`, and invalid UTF-8 reads `Source file "x.azs" is not valid UTF-8 (line 3, column 7)`; include cycles list the files involved. Source and include file names may not contain control or bidirectional formatting characters, since diagnostics print them (`Invalid source file name` / `Invalid include file name`).
- The context names the enclosing declaration: `function main`, `function C::m`, `function C::C` and `function C::~C` for constructors and destructors, `function C::operator[]`, `initializer of class P`, `class P`, or `global g`. Errors without a source position, such as a malformed AST field, name only the input file: the source for `compile`, the JSON file for `compile-json`.
- When compiling from source, duplicate functions, classes, function IDs, and conflicting extern declarations add a `previous definition is here` or `previous declaration is here` note; a definition read twice from the same header adds a hint to use `#pragma once` or an include guard. Function IDs are printed as eight hexadecimal digits, e.g. `0x0fff0005`.
- A function that can fall off its end without returning a value is reported at its closing brace.

File names are the main file's name and, for an included file, its path relative to the first include root containing it (the main file's directory, then the include directories in order), with `/` separators, so they do not depend on where a project or package is located. When an earlier root holds another file of that name, or the name already belongs to another file, the path relative to the main file's directory is used instead, e.g. `../lib/util.azs`, so names do not depend on the include order; a file outside every root, which only `--unrestricted-includes` allows, is named that way too. The absolute path is the last resort, when that name is taken as well or no relative path exists. `execute(String)` uses `<source>` and standalone expressions use `<expression>`. They are stored in the AST `sources` table, so `compile` and `compile-json` of the same program print identical positioned diagnostics. `compile-json` reports malformed JSON at its line and column and names an AST without a `sources` table after the JSON file.

#### Multiple errors and exit status

One compilation reports independent errors together. Compilation runs in phases: preprocessing, lexing, parsing, post-parse checks of base-class initializers, extern declarations and function IDs, the AST shape check, the global declarations (class layouts, externs, abstract mappings and globals), and function bodies. Within a phase the compiler recovers after an error: the preprocessor at the next directive or line, the parser at the next declaration, class member or statement, and the checker at the next class, global item or function. A phase that reported errors ends the compilation, so later phases never see broken input; for example, a syntax error suppresses type errors elsewhere in the program. Errors are listed in source order within each phase, an included file's errors at its `#include`. The syntax, AST, include and macro nesting and size limits, the limits on include expansions, source bytes read, preprocessed text and tokens, and internal compiler errors stop the compilation at once.

At most 20 errors are reported by default; `--max-errors N` changes the limit, `0` removes it, and `1` stops at the first error. When more errors follow, the list ends with `note: too many errors emitted (limit 20); stopping`. The CLI then prints the summary, for example `Error: compilation failed: 2 errors`, and writes no output files. The exit status is 0 on success, 1 for errors in the program or in the command line, and 2 for an internal compiler error; usage errors such as an unknown option print `Error: message`.

An internal compiler error is a failure of the compiler itself rather than of the program, for example an unexpected Java exception while compiling a program parsed from source. It reads `internal compiler error: StackOverflowError` (the exception and its message), is followed by `note: this is a compiler bug; rerun with --debug-compiler for a stack trace`, and ends with the summary `Error: compilation failed: internal compiler error`. `--debug-compiler` also prints the Java stack trace.

`compile-json` first checks the AST's shape: required fields, the JSON types the compiler reads, positions, the `sources` table and number ranges. Each violation is reported against the JSON file with its JSON pointer, e.g. `tree.json: error: Malformed script JSON at /body/fff/_funcfff0000/metadata/position: expected an integer, got "abc"`. An unexpected failure on such an AST is reported as `Malformed script JSON near main.azs:3:10` at the nearest positioned node with exit status 1; `--debug-compiler` prints the underlying exception.

Java callers receive the same diagnostics as `azertia.script.CompileException`, an `IllegalArgumentException` whose `diagnostics()` lists them, whose message is their text, and whose `internal()` reports an internal compiler error. `AzScript.execute(...)` and `Compiler.compile(...)` report up to 20 errors by default. Pass one `azertia.script.Diagnostics` to both steps, as in the example at the top of this guide: only then is an unexpected failure on the parsed program an internal compiler error, while an AST compiled with another collector is treated as supplied JSON. Configure that collector to change the limit, e.g. `new Diagnostics().maxErrors(0)`, or the [warnings](#warnings). An instance serves one compilation; `Diagnostics.failFast()` stops at the first error. Standalone expressions compiled with `Compiler.compileExpression` stop at the first error.

#### Warnings

Warnings point at code that compiles but is probably a mistake. They use the same layout with `warning:` and end with the option that controls them; they never change the compiled output:

```text
main.azs:4:9: warning: in function main: assignment used as a condition; use '==' to compare, or wrap the assignment in parentheses [-Wassign-in-condition]
```

| Warning | Default | Reported for |
|---|---|---|
| `assign-in-condition` | on | A plain `=` assignment written directly as the condition of `if`, `while` or `for`, e.g. `if (done = check())`, at its `=`. Parentheses mark an intended assignment: `if ((done = check()))`. |
| `unreachable-code` | on | The first statement after `return`, `break`, `continue`, an `if`/`else` whose branches all leave, a switch with default whose sections all return, or a `while (true)`/`for (;;)` loop without `break`; once per block. As in errors, an assignment or `++`/`--` statement is located at its operator. |
| `unused-variable` | off | A local variable that is never used after its declaration; reading or assigning it counts as a use. Parameters, names starting with `_`, and objects declared for their construction and destruction (`C c;`, `C * p(args);`, buffers and type-parameter values) are exempt. |

`-Wall` enables every warning, `-W<name>` enables one and `-Wno-<name>` disables one; an option naming a warning wins over `-Wall` in either order, and the last option for the same warning wins. `-w` suppresses all warnings. `-Werror` reports enabled warnings as errors, e.g. `main.azs:4:9: error: in function main: ... [-Werror=assign-in-condition]`, so the compilation fails. An unknown warning name is a usage error (`Error: Unknown warning option: -Wxyz`).

A successful compilation prints its warnings to stderr and exits with status 0. A failed compilation lists warnings among its errors in source order and counts them in the summary, e.g. `Error: compilation failed: 1 error, 1 warning`. The checks run on the AST, so `compile` and `compile-json` report the same warnings; AST nodes without a source position, such as those of a handwritten AST, and code the compiler generates never warn. In Java, configure the `Diagnostics` collector, e.g. `new Diagnostics().allWarnings().warning("unreachable-code", false).warningsAsErrors(true)`, and read `diagnostics.warnings()` after compiling; `Diagnostics.WARNINGS` lists the warnings.

## Runtime errors and debug info

Compile with `-g` to include function names and source positions in runtime errors; debug info is opt-in for both `compile` and `compile-json`. The standalone interpreter writes the message and frames to stderr and exits with status 1:

```text
AzScript error: Division by zero
  at divide (main.azs:2:14)
  at main (main.azs:5:12)
```

Frames run from the failing function outward to its callers. Without debug info, a script frame reads `  at function 0x0fff0002`. Native callbacks use `  at native function 0x12340001`; built-ins in namespace `0x0abd` add no native frame. At most 64 frames are retained; any remainder is reported as `  ... N more frames`. Lines and columns start at 1; columns count UTF-16 code units, so a supplementary Unicode character occupies two columns. Included files retain their own source positions.

C++ exceptions keep their original types and `what()` messages. `azertian::describe_error(error)` returns `what()` plus the matching trace, without the CLI's `AzScript error: ` prefix or a trailing newline. Call it before `destroy()` or another runtime entry. `last_error_trace()` returns an `error_trace` containing `frames` and `omitted_frames`; `format_error_trace(trace)` formats only the frame lines, and `clear_error_trace()` clears the active and published trace. Trace state is thread-local, with fresh collection at each runtime entry, including callback reentry. Cleanup preserves the original error and its frames; a later destructor error cannot replace or add frames to it. When cleanup is the first failure, its first error is retained.

JNI carries native error messages and trace lines as UTF-8 into Java strings without changing the exception mapping. A throwable raised by a Java callback is returned as the same throwable object, without rewriting its message. Trace support changes the public C++ ABI; rebuild C++ hosts and native plugins with matching headers and libraries.

## Generics

Generic classes, functions, and methods share one compiled body. Different type arguments do not allocate new public function IDs. A type parameter denotes a complete type: primitive, address, class value, class pointer, parameterized class, or `buffer<T>`. `void`, `any`, and parameter-only `C(*)` are not type arguments.

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

Function type parameters precede the return type. Calls can supply `identity<int>(3)` or infer types from arguments, recursively matching parameterized types. Inference does not use the destination variable's type, infer from null alone, or find common ancestors for conflicting arguments; specify types when inference is ambiguous. Classes require explicit arguments: no raw types or diamond `<>`. Applications are invariant, requiring the same generic declaration and identical type arguments, including unused parameters. Non-generic classes are likewise nominal, and pointer upcasts remain available.

A single class bound is written `<T extends Base>` for values or `<T extends Base*>` for pointers, checked through declared inheritance. Bounds may reference earlier parameters, for example `<A, B extends Box<A>*>`. Wildcards, multiple bounds, circular bounds, and using a bare type parameter as a bound are not supported. Unbounded T supports storage, assignment, arguments, and return. A bound exposes its fields/methods but does not permit assigning any Base to T or slicing an unknown derived value into Base. Unknown T does not support arithmetic, comparison, delete, `T()`, `new T()`, or appended `*`/`(*)`; pass `C*` as the complete type argument instead.

A local `T local = expression;` requires an initializer. T fields retain concrete default semantics: initialize scalars to zero, false, or an empty string, and addresses/pointers to null first; construct class-value fields or evaluate explicit initializers in declaration order. The base completes before the child, and field initializers still cannot read constructor parameters. Types without a zero-argument constructor may participate in pure forwarding. Required default construction fails at compile time when the current unit/shared declaration makes it known, or at the actual operation when an independently compiled body was unknown, with normal cleanup. An explicit class-value initializer does not require an additional default constructor.

Inheritance can use `class Child<A,B> : Base<B>`. Out-of-class definitions use `T Box<T>::get()`, with the owner's declared parameter names in scope; generic methods use `<U> U Box<T>::echo(U value)`. An extern is `extern <U> U identity(U value):abcd0002;`. Declarations and definitions match parameters positionally and check bounds, allowing different parameter names. Constructors/destructors use their class parameters and cannot declare additional parameters. Hint libraries still implement members outside the class; see [generic shared libraries](../docs/HINT_LINKING.en.md).

Execution carries immutable operation contexts containing an ABI tag, return category, and optional default factory with captured contexts. These contain no class names/layouts, introduce no AZS value type, and occupy no user parameter or field slots. Class contexts precede method contexts, while this remains ordinary parameter slot 0. Object records retain destructor contexts across copy, move, return, delete, error cleanup, and snapshot restoration. Returning `T=address` does not transfer object ownership; `T=C*` follows object-return rules, and `T=C` follows existing value copy/move rules.

## Owning inline buffers

`buffer<T>` is a compiler-built-in owning value for locals, fields, parameters, returns, and generic arguments. Its element type must be explicit and is invariant. The default is an empty buffer with length and capacity zero, without requiring a default constructor for T. Buffer pointers, inheritance from buffer, new/delete, and storage through var/def or raw mem_get are unsupported.

```cpp
buffer<int> values;
values.reserve(4);
values.push(7);
values.resize(3);             // Add two zero values
values.set(1, 9);
int first = values.get(0);
buffer<int> copy = values;    // Independent deep copy, preserving capacity
copy.set(0, 11);
```

`length()` and `capacity()` return int; `reserve(int)`, `set(int,T)`, `push(T)`, and `resize(int)` return void; `get(int)` returns T by value. These operations borrow the receiver without copying the whole buffer. Receiver and arguments evaluate once each, left to right. reserve grows only when the requested capacity is larger, to exactly that capacity, without constructing spare elements or doubling. push and growing resize require sufficient existing capacity. Negative arguments, invalid indices, and insufficient capacity are runtime errors. Built-in buffers use get/set in this version; bracket syntax applies to class operators below.

Copies, arguments, and returns use owning-value rules; temporaries and values owned by the returning call can transfer. Buffer fields, nested `buffer<buffer<T>>`, and class elements deep-copy; pointer elements copy addresses without owning their targets. Types without a default constructor still support reserve, push of existing values, and shrinking. A default constructor is needed only when resize actually grows.

Storage is a contiguous capacity-sized slot array. Scalars, address, pointers, and buffer elements have stride 1; a class value has stride N, its direct field count including inherited fields, with element i occupying `[i*N,(i+1)*N)`. Nested value fields retain separate storage. This is not a C++ native struct byte-layout guarantee. Each class element has an independent object-view ID. Growth, removal, and whole replacement expire affected old addresses; no exposed base permits deriving all element pointers. reserve migrates storage without user constructors/destructors and does not repair old addresses retained in fields.

A failed reserve preserves the old storage; failed push preserves length; failed resize growth rolls back newly added elements in reverse order. Shrinking and destruction clean in reverse order even after destructor failure, retaining the original error or first cleanup error. set has the basic exception guarantee of fieldwise assignment. Whole-buffer assignment prepares a candidate, cleans the old content, then switches; it still switches before propagating an old destructor error. If a destructor callback destroys the destination, the candidate is cleaned and no expired storage is written. Operations reject reentrant mutation, defer destruction until operation exit, and stop when invalidated. Budget exhaustion still releases underlying storage. Capacity times stride is limited to 1,048,576 slots per block; owning-value nesting is limited to 64 levels.

## Default comparison and class operators

`value_compare<T>(left,right)` requires one explicit type argument and returns -1, 0, or 1. Integer comparison does not subtract; float/double reject nonfinite values and treat signed zeros equally. Strings compare lexicographically by unsigned UTF-8 bytes, including embedded NUL. Boolean ordering is false < true; address and class pointers use unsigned address order. Class values and buffers have no default comparison: known uses fail compilation; a generic T is allowed but unsupported concrete types fail at execution. Custom ordering uses ordinary AZS comparator functions through `reflect_invoke_function<int>(compareId,left,right)`, retaining reflection boundaries and by-value class arguments.

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

Supported entries are binary `+ - * /`, index getter `operator[]`, setter `operator[]=`, and invocation `operator()`, with one signature per kind per class. They support extern declarations and out-of-class implementations such as `int Number::operator()(int scale) { return value * scale; }`. They can use class type parameters but cannot declare their own method type parameters. All lower to ordinary methods with implicit this; exec contains no operator table.

Arithmetic looks up only the left class value; indexing and invocation also accept class pointers. Existing inheritance and static lookup apply, without reverse lookup, implicit conversion, or ordinary function overloading. The getter takes exactly one key of any supported type. An optional setter must use the same key type, the getter's return type as its second parameter, and void return. `x[key] = value` evaluates receiver, key, and value once each in that order. Index compound assignment is unsupported.

A getter returning a class value or buffer produces a copy. Writing through that copy, such as `matrix[i][j] = x`, `items[i].field = x`, or `items.get(i).field = x`, is rejected. Read into an explicitly typed local, modify it, and write back with the setter/set. The same restriction checks both arms of a conditional receiver. Pointer results follow ordinary pointer rules. Arbitrary arithmetic and invocation remain unavailable on unbounded T; a known parameterized class or upper bound must supply the operator.

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

`reflect_invoke_function<R>(int funcid, ...params)` accepts a concrete primitive, address, class pointer, class value, buffer, or void result type; R may also be the current generic body's bound T. IDs use the actual mounted namespace and complete int32 bit pattern, including negative IDs. Ordinary integers are never assume-relocated. Receivers/arguments are evaluated once, left to right, and invocation preserves the current caller, budget, and error cleanup. Ordinary non-generic member methods can be invoked by supplying this explicitly as a pointer.

Targets with signatures use existing argument validation. Host callbacks without extern declarations are also callable, but the host must validate their arguments. The actual returned tag is always checked, including void, without additional numeric conversion. For class-pointer, class-value, buffer, and type-parameter result annotations, the compiler additionally emits `check_object` after the call; failures begin with `Object check failed:`.

A concrete class-pointer result must be null or the start of a live registered object that is not being destroyed, or a live non-buffer literal/view. It must have enough direct slots for the annotated class, with the expected tags in that prefix. A concrete class-value result must be live, have exactly the declared direct-slot count, and match those tags. Tag 6 means unchecked, such as a generic direct field. Buffer results compare element ABI, kind, width, and recursively nested element contexts with bounded depth; factory/placement IDs are not compared.

For `R=T`, the compiler uses the current reference context and no direct-slot tag vector because T's fields are unknown. Runtime checks the resolved kind and liveness; a class value must match its bound context width. For a pointer, only null/object-start/liveness is checked: pointer-context width is the address ABI width (1), not the unknown pointee layout, and no direct-field tags are available. None of these checks proves nominal class identity or checks the objects targeted by pointer fields. A different class with matching checked structure can still pass; generic factory identities are not verified. Ordinary extern results and host-supplied class pointers keep the trusted contract described above. These reflection-result checks do not apply to construction or method dispatch.

Public reflection rejects functions and generic-class methods requiring hidden contexts, constructors, destructors, lifecycle hooks, internal factories, and helpers. Expose a normal wrapper that fixes concrete type arguments before calling generic code; its ordinary parameters/results may include `Box<int>`. Ordinary C++/JNI invoke also rejects unbound generic entries. Creation/deletion wrappers must use object syntax or delete, never directly call lifecycle bodies.

`reflect_get_hint_namespace(string hint)` returns the mounted namespace number and throws for a missing/empty name. `reflect_hint_loaded(string hint)` returns false for missing/empty names. Names are case-sensitive. Mounted does not guarantee that onload completed: an onload hook can query another mounted module before its initialization. String queries and dynamic calls do not add dependency edges; use `#assume_hint` when initialization order matters. Combine a namespace and low 16-bit number without signed multiplication overflow as follows:

```cpp
int functionId(int ns, int position) {
    if (ns >= 32768) ns = ns - 65536;
    return ns * 65536 + position; // position must be 0..65535
}
```

## AST and ABD contract

Readable AST retains metadata/ext/abstract/global-variable/body, plus extern-signatures. `global-variable` lists the `#gvar` names as strings, then each typed global as `{"name", "type", "initializer"?}` with its position; compile-json checks and initializes them like source. Class programs retain definitions, ordered fields, member expressions, and other compile-time data; compile-json reruns checks/lowering. A qualified base call is `{"t":"ctrl","call":"base-call","param":["Base","method",args...]}` with optional `type-args`. Hint, assumes, extern declarations, definition names, and actual definition numbers are retained for byte-identical roundtrips. Abstract maps source names with extern priority: declaration ID when present, otherwise definition ID. Hint definitions retain placeholder 0000; self-assume call addresses are produced during lowering. Independent definition positions live in body namespaces and metadata.position/name. Signatures store return-type and param-types. Expression objects use `t: "ctrl" | "call"`, call, param; blocks are arrays.

Readable chains use `{"t":"ctrl","call":"chain","param":[operand,...],"operators":[{"call":"add","_line":1,"_column":3},...]}`. `param` contains operands in source order and `operators` has exactly one fewer entry, each naming the canonical binary operation (`add`, `minus`, `multiply`, `cmp`, `and`, etc.) and carrying that operator's normal `_file/_line/_column` position metadata. This shape requires more than 128 operands at a single precedence level. `chain` names an AST operation, not a reserved source word; an ordinary function may still be named `chain`. All operators must belong to the same canonical precedence group. Operator positions are optional in handwritten ASTs and are validated when supplied. `compile-json` checks the node, operator names/counts and positions before repeating type checking and lowering. Internal segment placeholders and sequence records do not enter the source AST or executable wire format.

Readable ladders use `{"t":"ctrl","call":"if-chain","param":[{"t":"ctrl","call":"if","param":[condition,body]},...],"else":body}`. Each positioned branch is an ordinary `if` node with exactly its condition and body; the optional final `else` belongs to the outer `if-chain`. This flat form requires more than 128 conditions; shorter source ladders retain nested AST nodes even when their executable lowering becomes flat. `compile-json` checks the shape and boolean conditions, then repeats branch-scope, jump, missing-return, and depth-aware lowering checks. A handwritten nested ladder can receive the same flat executable lowering without changing its source AST.

Readable switch AST uses `{"t":"ctrl","call":"switch","param":[value,sections]}`, where sections is an array. Each section is `{"t":"ctrl","call":"case","labels":[literal,...],"param":[body]}` with a statement-array body and optional `default: true`; a default-only section has no case labels. The parser retains individual labels; compilation groups adjacent empty sections with the following body. compile-json repeats label, scope, jump, and fall-through checks. Lowering evaluates the value once, then uses a one-shot loop with sibling if sections and balanced short-circuit label comparisons; default is checked last regardless of its source position. Hidden locals keep source break/continue targets intact.

A conditional expression is `{"t":"ctrl","call":"select","param":[condition,thenValue,elseValue]}` with exactly three expression parameters. compile-json repeats the boolean-condition and common-arm-type checks, then inserts any numeric conversions before lowering to opcode 50.

The root `sources` table has one entry per expanded inclusion of a file, `{"name": "inc/lib.azs", "included-from": {"source": 0, "line": 2, "column": 1}}`; entry 0 is the main file, an include always names an earlier entry, and an include skipped by `#pragma once` has no entry. Expression objects, function metadata, classes, fields, and extern signatures carry `_line/_column` in their original file and `_file`, the index of their `sources` entry (omitted for 0). Function metadata also records the closing brace as `_end_line/_end_column`, and a parenthesized assignment carries `_parenthesized: true`. Literal values and blocks carry no position; diagnostics use the nearest positioned ancestor. These fields serve diagnostics and, with `-g`, the exec debug records; they never reach ABD instructions. `sources` is optional in compile-json input; without it, all positions belong to one file named after the input. Positions are positive integers and `_file` must index the table; the table has at most 4097 entries, the main file and 4096 include expansions; a `sources` entry names a non-empty file of at most 4096 characters without control or bidirectional formatting characters, and an include chain is at most 64 levels deep. compile-json checks this shape before compiling, see [Multiple errors and exit status](#multiple-errors-and-exit-status).

Readable class ASTs optionally carry `virtual-methods`, `override-methods`, and `final-methods` arrays of bare names matching the `methods` keys, e.g. `"virtual-methods":["score"]`. Empty arrays are omitted; duplicate entries and non-method names are rejected. Calls need no extra AST marker, and non-virtual programs keep their existing AST shape.

Readable class ASTs retain an `operators` mapping and each method’s `operator-kind`. Index read/write and suffix invocation use `index`, `index-set`, and `invoke` nodes; empty buffers use `buffer-new`. Type checking and lowering rerun during compile-json, preserving byte-identical ABD output.

Generated exec/ABD uses `exec-version: 10`, independent of source version. All control c fields are numeric; readable AST keeps source names/types/operations. Below is the exec JSON inspection view; binary field order/types are in [Exec v10](../docs/EXEC_FORMAT.en.md).

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
| Default value c=37 (context_default) | context; scalar default, empty buffer, or bound value factory |
| Generic return c=38 (return_typed) | v, context; validate tag and return according to category |
| Type check c=39 (check_type) | v, context; validate tag, evaluating the value once |
| Empty buffer c=40 (buffer_new) | context: element operations |
| Buffer operation c=41 (buffer_op) | op, v, args; operations 0–6 are length/capacity/reserve/get/set/push/resize |
| Default comparison c=42 (value_compare) | context, v1, v2 |
| Bitwise/shift c=43…48 | v1, v2: bit_and/bit_or/bit_xor/shl/shr/ushr; int operands/results |
| Explicit conversion c=49 (convert) | type: target 0/1/2/3/7, v: source expression |
| Conditional selection c=50 (select) | v: condition, then: true arm, else: false arm |
| Checked reference c=51 (check_object) | v: result expression, context, slot-types: direct ABI tags; 6 unchecked |
| Method bindings c=52 (set_methods) | v: receiver address, entries: [{id, contexts}] |
| Virtual call c=53 (call_virtual) | slot: method slot, type: result ABI, param: receiver then arguments |

Root, functions, signatures, expressions, function/type/argument/statement lists use raw AbdStack. Fields retain length boundaries without names or known-type tags. Dynamic constants use single-element AbdArray preserving int/float/double/bool/string/void/address; extension metadata uses AbdMap. JSON constants and block arrays use binary opcodes 0 and 1. `Compiler.compile()` returns ExecProgram, still inspectable as AcsObject; toValue emits the wire format. Use ExecCodec.decode to recover the inspection view, not direct root AcsObject decoding.

Global gvs is a count; -1 names the first module global, -2 the second. Parameters occupy 0…param-count-1; locals and compiler temporaries follow consecutively per function. Implicit this is parameter slot 0. IDs are not alloc addresses, and bare integers remain constants; `{"t":0,"c":3,"v":0}` reads the first parameter/local.

Function views contain id/return-type/script/param-count/param-types/local-count/hidden-count/entry-kind, and with `-g` a `debug` record; the root lists the `debug-files`, empty without `-g`. Local-count excludes parameters. Every call allocates an independent frame, including recursion/reentry. Exited blocks clean objects and clear declared slots, reinitializing on the next iteration. Total globals and per-call parameter+local counts cannot exceed 1,048,576. Invalid IDs, globals outside the owning module, undeclared or expired-scope access are rejected.

Untyped script parameters and unbounded type parameters use internal any. Only functions with hidden generic contexts may return any or declare extern any parameters. Entry-kind 0 denotes ordinary entries and 1 internal entries; contexts occupy no variable slots. AST additionally retains type-parameters, bounds, parameterized type strings, and explicit type-args. Param-types/return-type enable direct C++/JNI checks; root extern-signatures hold boundary contracts. The interpreter accepts only exec v10 raw stacks, rejecting v9 and older versions and Map files. Recompile source/AST. Unknown versions/opcodes, extra/missing fields, wrong widths, and nesting beyond 128 fail on load. JNI snapshot versioning is independent, currently v10.

### Insert and module global offsets

When inserting a numeric module, `script::insert_script(bytes, length)` records the prior global count as each of its `ofunction::global_offset`, plus its own global_count. Negative v resolves to `global_offset + (-int64(v) - 1)`. With two existing globals, inserted -1 is index 2 while original -1 remains index 0. The loader determines offsets, not compiled output.

Calls use the callee's offset for ordinary functions, constructors, destructors, factories, and lifecycle hooks. Later inserts never change existing offsets. Locals receive no global offset; block ownership/return transfer are unchanged. Conflicting ordinary fixed IDs reject insertion; the error names the hex ID, the module that already defines it, and a remedy (see "Separately compiled ordinary modules" in [hint linking](../docs/HINT_LINKING.en.md)). Hint modules receive available namespaces and keep lifecycle hooks separately per module.

Load/insert only assemble and never run onload. Hosts must explicitly flush before calling; successful insert sets setup=false and requires another flush. Linking fully validates before atomically rewriting calls/destructors; missing dependencies can be added before retry. Dependencies initialize first, cycles in load order. Onload failure requires close/recreate. Close runs pre-destroy in reverse successful initialization order, then cleans globals.

Class pointers erase to address type 7; literals are 8, int32 is 0, any is 6. Address constants use tag 0xce200b and eight unsigned little-endian bytes; JSON is `{"address":"unsigned decimal"}`, null `{"address":"0"}`. Only object-return instructions transfer pointer-object ownership; ordinary address/int returns do not. JNI v10 snapshots store ordered module identity (bytes, namespace, hint, global layout), globals, heap including literal fields, and cleanup ownership, only when initialized and idle. Restore fully validates then atomically replaces state without relinking or old destructors. Old exec/snapshots are incompatible; recompile all modules and change int heap-address variables to address.

## Regression validation and archives

`examples/parser-regressions.azs` exercises strings, nested calls, left-associative subtraction/division, mixed precedence, recursion, and control flow. It prints an escaped string, 15, 10, 17, 120, 26, false, true; main returns 146. The runner also prints the result and destruction-stage done. Migrated tse.azs is included in compilation/ABD roundtrips.

Archive fixes cover AchievePack.files value annotations, empty files, and lost leading 00/ff bytes. New archives mark byte wrapping with metadata.format=2. Structurally valid old archives remain readable, but already lost leading bytes cannot be recovered. Packing rejects symlinks and destinations contained in their own input; extraction rejects traversal, directory symlinks, and overwriting existing files. Older interpreters/archive readers may not understand new extensions.
