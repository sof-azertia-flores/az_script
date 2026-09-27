# Multi-File Libraries and Runtime Hint Linking

[中文](HINT_LINKING.md) | English

AzScript libraries compile independently, receive a namespace when loaded, and link through explicit `flush()`. Classes remain compile-time structures: executables contain slot addresses, ordinary function references, and destructor IDs, but no class names, field names, or layout tables. Shared declarations define the cross-module structural contract. See [Exec v9](EXEC_FORMAT.en.md) for the binary contract.

## Declarations and numbering

`#namespace` accepts one to four hexadecimal digits. A compilation unit, including active includes, may contain at most one `#namespace` or `#namespace_hint`. `#namespace_hint NAME` declares a case-sensitive ASCII identifier: a letter or underscore followed by letters, digits, or underscores. `#assume_hint NAME abcd` binds a compile-time assumed namespace, excluding 0000, 0abd, 0fff, and this module's fixed function namespace. Identical assumptions merge; the same name with different namespaces, or the same namespace with different names, is rejected.

`extern int a(int n):abcd0003;` declares a complete 32-bit function address. Parameter names may be omitted, but return and parameter types must be explicit. Old `#extern` is removed; header guards use `#define`. `int a():0003 { ... }` fixes a definition's low 16-bit number. An optional `0x` prefix is supported: up to four digits for definitions, eight for externs. Explicit numbers and numbers occupied by self-import declarations are reserved before allocating automatic and internal-function numbers.

Ordinary hint-library functions initially occupy namespace 0000. Numbers 0000/0001 remain reserved for `__script_onload` / `__script_pre_destroy`; automatic ordinary numbering starts at 0002. Library main is ordinary. Non-hint main stays at `0x0fff0000`, lifecycle hooks at 0/1; suffixes cannot change these fixed IDs.

Each hint library assumes itself. An explicit self-assumption wins; otherwise the lowest available namespace alias is chosen, avoiding reserved, fixed-function, explicit-assume, and fixed-extern namespaces. Internal calls, recursion, and factory calls use this self-assumption. Call lookup prefers externs. Same-name externs and definitions may coexist with matching signatures; definition positions and call bindings are separate. Self-import declarations and implementations must use the same low number; an omitted definition number inherits the declaration's. Identical extern declarations merge; signature or ID conflicts fail.

## Out-of-class implementations and structural types

In hint libraries, class bodies contain fields and extern members only; explicit method bodies go outside the class. Non-hint programs also retain inline method bodies. Constructors/destructors omit return types: `extern C(int n):addd0002;` / `C::C(int n):0002 { ... }`, and `extern ~C():addd0003;` / `C::~C():0003 { ... }`. Ordinary members use `extern int get():addd0004;` / `int C::get():0004 { ... }`. Out-of-class implementations must match declared members, signatures, and self-library numbers.

Clients and implementations include the same class declaration. Each creating module generates three creation factories per class (automatic pointer, manual pointer, literal object): allocate exactly the required slots (private storage for literals), call the constructor, register an optional destructor, and transfer the object. Classes with a zero-argument constructor also have a placement helper that initializes an already allocated buffer element view without allocating its storage again. Field defaults, literal-field construction, and ordered initializers are prefixed to the actual constructor implementation. They resolve this, members, and implementation-module globals, unaffected by explicit constructor-parameter shadowing. Declaration consumers do not parse these initializers. The prefix creates no extra cleanup block; temporaries live at least to their statement end. Failed construction registers no object and runs no destructor for it, but destroys completed literal fields. For a class with literal fields and no declared destructor, each creating module generates a destructor that disposes of those fields only.

No constructor declaration means a synthesized local default constructor. Any constructor, including extern, suppresses replacement/default synthesis. Pointer fields are non-owning. Automatic-pointer cleanup stays bound to the creation address. Literal fields embed complete objects and die with their containing object.

For non-generic classes, compile-time structural equivalence compares field types and order, ignoring class/field names, methods, and initializers. Values and pointers are compared separately. Pointer-field targets compare recursively; visited type pairs terminate self/cyclic comparisons. Equivalence applies to assignment, arguments, returns, and declaration/definition signatures. Int, raw address, literal objects, and class pointers are distinct compile-time types; address fields match only address fields. Class pointers, `C (*)`, and implicit this erase to address (type 7); literal parameters/returns use object (type 8). The runtime does not validate cross-module class structures; shared declarations are the contract. Addresses are uint64, each field still occupies one slot, and inheritance preserves base prefixes. IDs, namespaces, and offsets do not widen. Extern address signatures do not match int; update clients, libraries, and hosts together.

## Shared generic declarations and implementations

Generic classes and functions still compile independently, with one function body per definition. Shared headers retain type parameters, bounds, and complete signatures; declarations and implementations match generic parameters by position. Generic class applications are invariant: `Box<int>` and `Box<string>` are not interchangeable, and applications of different generic declarations are not structurally equivalent. Put function type parameters after `extern`:

```cpp
#assume_hint GENERIC_BOX_LIB beef
class Box<T> {
    T value;
    extern Box():beef0002;
    extern void set(T next):beef0003;
    extern T get():beef0004;
    extern ~Box():beef0005;
}
extern <T> T echo(T value):beef0006;
```

The implementation uses `#namespace_hint GENERIC_BOX_LIB` and includes this header. Out-of-class definitions spell the class with its type parameters; ordinary functions may declare their own parameters:

```cpp
Box<T>::Box():0002 {}
void Box<T>::set(T next):0003 { value = next; }
T Box<T>::get():0004 { return value; }
Box<T>::~Box():0005 { print("box destroyed"); }
<T> T echo(T value):0006 { return value; }
```

Clients can declare `Box<int> number;` or `Box<string>* text = new Box<string>();`, and call `echo(42)` or explicitly `echo<int>(42)`. Fields of type T retain default initialization: scalars become zero, false, or an empty string; addresses and class pointers become null; literal objects use their zero-argument constructor. Explicitly initialized fields and code that only copies, passes, or returns existing objects do not require a default constructor. Local T variables need an explicit initializer; `T()` and `new T()` are unsupported.

Executables contain no generic type names or class layouts. Shared implementations receive separate hidden contexts describing actual value types and default construction operations. A method's first ordinary parameter is still this; hidden contexts consume neither parameter slots nor local slots. Parameters and returns directly declared T erase to any; known class pointers and literal objects still erase to address and object. Linking checks context counts, entry kinds, and erased signatures. Ordinary host invocation cannot omit generic contexts; expose a non-generic wrapper with concrete types instead.

Construction completion captures the contexts needed by destruction. Copies, moves, cross-module returns, and snapshots preserve them. Default-factory and destructor references relocate with CALL according to their defining module's assumptions. Clients and libraries must use the same shared declarations and be recompiled together; runtime linking does not validate class layouts or generic nominal types.

## Querying hints and invoking runtime IDs from AZS

`reflect_hint_loaded("NAME")` reports whether the current script has mounted the case-sensitive hint; missing or empty names return false. `reflect_get_hint_namespace("NAME")` returns its actual 16-bit namespace number and raises a runtime error for missing or empty names. Mounted does not mean onload has run: during initialization, a mounted module awaiting its initialization also reports true. Ordinary AZS execution still requires successful flush.

`reflect_invoke_function<R>(id, args...)` takes a runtime int32 function ID, preserving all high bits and negative bit patterns. It evaluates the ID and arguments once each, from left to right, and checks the actual runtime return type against R. The caller remains responsible for class layout and concrete generic type agreement. Reflection does not relocate assumed namespaces inside integers or infer initialization dependencies from hint strings. Use `#assume_hint` when initialization order matters; presence queries cannot bypass a missing-library link error introduced by that directive.

A generic function body may use `reflect_invoke_function<T>(...)`, obtaining its expected result type from the current T context. The target itself must be an ordinary, non-generic entry: raw constructors, destructors, internal factories, type-operation helpers, and targets requiring generic contexts are rejected. A method on a generic class needs class contexts even without its own type parameters. Expose it through a non-generic wrapper:

```cpp
Box<int> makeIntBox(int value):0007 {
    Box<int> result;
    result.set(value);
    return result;
}
```

Clients query the actual namespace and combine it with the wrapper's fixed low number. Source numeric expressions use decimal int32 values; this helper also avoids overflow when the namespace's high bit is set:

```cpp
int mountedFunctionId(int ns, int position) {
    if (ns >= 32768) ns = ns - 65536;
    return ns * 65536 + position;
}

void main() {
    int ns = reflect_get_hint_namespace("GENERIC_BOX_LIB");
    Box<int> box = reflect_invoke_function<Box<int>>(mountedFunctionId(ns, 7), 42);
    print(box.get());
}
```

The calling block owns a returned literal object. Automatic class-pointer returns transfer cleanup along the actual call chain; ordinary address returns do not, and new objects still require delete. Reflection does not automatically borrow a literal argument as the address expected by `C (*)`; pass a class pointer or use a non-generic adapter. Host callbacks without extern declarations may be invoked reflectively and must validate their arguments. Targets with signatures retain argument checks; all targets have their actual return value checked and retain existing budgets and error cleanup.

## Assembly and lifetime

One hint identifies one ABD; duplicate loading fails. The loader chooses the lowest available actual namespace, excluding 0000, 0abd, 0fff, existing script namespaces, declared fixed host namespaces, and registered host executors. Existing mounts remain stable. Later host registration cannot take active script namespaces.

`load_script(bytes,length)` only mounts code. `script::setup` starts false and becomes false after successful insertion. APIs include `flush()`, `namespace_for_hint(name)`, and `hint_loaded(name)`. Assumptions, externs, and references are module-local; identical placeholder addresses across modules are not conflated. Parse/load conflicts leave existing scripts unchanged.

Flush resolves all assumptions and validates targets and erased signatures including hidden context counts and entry kinds, before atomically committing call/destructor/default-factory relocations and imports. Failure runs no onload and removes no partial assumptions, allowing missing libraries to be added before retry. Ordinary execution, including direct function entry points, requires setup completion.

Dependencies initialize first after linking, ignoring self-edges. Cyclic groups use load order; independent groups use first-load order. Each onload runs once; repeated flush does not reinitialize. Initialization callbacks may call linked functions. Insert, flush, and snapshot replacement are forbidden during execution/callbacks. Only complete successful initialization sets setup=true.

Onload failure preserves the original error, cleans its scope, and faults the script. Execution, insertion, and flush are then forbidden; close and recreate it. Closing runs pre-destroy for successfully initialized modules in reverse initialization order and cleans existing objects. Closing is allowed before flush or after an unflushed insert. Undeleted new objects receive underlying storage release only, without user destructors.

JNI exposes `insertScript(File)`, `flush()`, and `namespaceForHint(String)`. Java/C++ hosts load all libraries, flush, then invoke. The standalone interpreter accepts repeated `--insert path` and an optional entry ID; it flushes assembled modules before entry execution.

## Snapshots and validation

JNI v9 snapshots require initialized stable state without active calls. Literal objects inside heap fields and their addresses are saved; restoration assigns new IDs and rewrites references. Captured generic destructor contexts, including default-factory IDs and child contexts, are saved as data and checked against the assembled functions before restoration. No context points into an expired call frame. Address scalars, allocation bases, object records, and cleanup order use independent uint64 addresses; allocation counts and IDs remain 32-bit. Identity includes ordered original module bytes, actual namespaces, hints, and global offsets/counts; differing assemblies are rejected. Restore validates fully before atomic replacement, without relinking, initialization, or old-state destructors. The 64 MiB limit remains; oversized saves fail explicitly. Old exec and snapshots are incompatible.

Validation covers high-bit IDs, forward/recursive calls, cross-module alias reuse, retry after missing libraries, mismatched signatures and link atomicity, cyclic initialization, out-of-class constructors/destructors, structural types, cross-library returns, JNI callbacks, and multimodule snapshots. Run `python3 tools/build_and_test.py --offline`, then `--sanitize`, and export/validate a full distribution.

## Running the bundled example

From the distribution root, compile library and client separately, then assemble:

```sh
./compile.sh examples/multifile/point.azs
./compile.sh examples/multifile/main.azs
./run.sh examples/multifile/main.exec.abd --insert examples/multifile/point.exec.abd
```

Shared `point.include.azs` declares only the class and assumed addresses. `point.azs` implements the constructor, methods, and destructor, using a private global for field initialization. `main.azs` creates literal and manual-pointer objects. Output is `library-load`, `main-load`, `42`, `44`, `point:42`, `point:41`, `42`, in that order.

The generic example consists of a [shared header](../compiler/examples/generic-box.include.azs), [library implementation](../compiler/examples/generic-box.azs), and [client](../compiler/examples/generic-box-consumer.azs). Run it from a distribution:

```sh
./compile.sh examples/generic-box.azs
./compile.sh examples/generic-box-consumer.azs
./run.sh examples/generic-box-consumer.exec.abd --insert examples/generic-box.exec.abd
```

Output is `true`, `false`, `0`, `21`, `library`, `42`, `7`, followed by four lines of `box destroyed`. It demonstrates shared generic bodies, two field types, defaults, reflection through concrete wrappers, and cleanup of four objects. The [single-file example](../compiler/examples/generics-reflection.azs) also shows a generic member method and reflection inside a generic function.

## Buffers and operator entries

Class operator declarations are shared like ordinary extern methods, including class type arguments. Implement them outside the class in hint libraries, for example `T Box<T>::operator[](int index):0003 { ... }`. Getter and setter use separate IDs; no runtime operator table is emitted. Concrete buffer and class arguments retain source type checks but both erase to OBJECT_VALUE. A generic comparator may dispatch an ordinary script function with reflect_invoke_function, without native callbacks or initialization dependencies.

Operation contexts now carry direct slot width, an optional internal placement-constructor ID and child contexts, and a buffer element context. flush recursively validates and atomically relocates both factory and placement references; hidden context counts and `void(address)` placement signatures must match. Buffer growth can therefore initialize class elements using the implementation module’s globals and constructor prefix. Empty buffers and copies do not require a default constructor.

Snapshot v9 saves buffer length/capacity, element contexts, live element/view IDs and captured destructors. It stores fields once, rebuilds continuous slabs/views, and remaps saved addresses before atomic replacement. In addition to the file/depth limits, cumulative buffer capacity times stride per snapshot is limited to 1,048,576 slots; save or restore beyond this budget fails. Host-held addresses must be reacquired after restoration or buffer growth. JNI still cannot directly pass OBJECT_VALUE; expose ordinary wrappers that consume and return supported host values.
