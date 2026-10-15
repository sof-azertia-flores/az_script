# Exec v10 Binary Format

[中文](EXEC_FORMAT.md) | English

This executable layout version is independent of source `metadata.version`. The readable AST retains source names; exec JSON is an inspection view. Fixed structures below use raw ABD stacks without field names or per-field type tags. Integers are four-byte little-endian int32; addresses are independent eight-byte little-endian uint64 values with 0 as null. Function IDs use the full uint32 bit pattern, booleans are one byte (0/1), and strings are UTF-8. Every field remains a standard ABD frame: a nonnegative int32 length followed by raw payload.

The file itself is one ABD frame whose payload is a root stack in this exact order:

```text
[ magic="AZSCRIPT", exec_version=10, source_version:int, author:string,
  global_count:int, extensions:AbdMap,
  external_signatures:Stack<Signature>, functions:Stack<Function>,
  namespace_hint:string, assumptions:Stack<Assumption>,
  debug_files:Stack<string> ]

Assumption = [ hint:string, namespace:int ]

Signature = [ function_id:int, return_type:int, parameter_types:Stack<int>,
              hidden_count:int, entry_kind:int ]
Function  = [ function_id:int, return_type:int, parameter_count:int,
              local_count:int, parameter_types:Stack<int>, body:Expression,
              hidden_count:int, entry_kind:int, has_debug:bool, [debug:FunctionDebug] ]
```

`debug_files` and `FunctionDebug` are the optional debug records described under [Debug records](#debug-records--g); they are empty or absent unless the program is compiled with `-g`.

Frame boundaries determine stack membership. Fixed records must contain exactly the specified fields; missing, extra, or incorrectly sized fields are rejected. Extension metadata retains `AbdMap` because its keys vary. Constants use a single-value `AbdArray` to retain scalar tags and exact types. Functions, signatures, parameter-type lists, and expression lists have no `AbdArray`/`AbdMap` wrapper.

Each expression is also a raw stack, beginning with an int32 opcode. `E` denotes an expression stack; `Es` denotes a raw stack containing only expression stacks. Boolean `has_*` fields require the corresponding optional field to be absent when false.

| Opcode | Original instruction | Following fields |
| --- | --- | --- |
| 0 | constant | `value:AbdArray`, exactly one scalar |
| 1 | block | `statements:Es` |
| 2 | call | `function_id:int, arguments:Es, contexts:Stack<Context>` |
| 3 | v | `slot:int` |
| 4 | vd | `slot:int, declared_type:int, has_initializer:bool, [initializer:E]` |
| 5 | vs | `slot:int, value:E` |
| 6 | m | `target:E, value:E` |
| 7 | r | `has_value:bool, [value:E]` |
| 8 | ro | `value:E` |
| 9 | oa | `base:E, offset:int` |
| 10 | ob | `address:E, has_destructor:bool, [destructor_id:int], manual:bool, contexts:Stack<Context>` |
| 11 | od | `address:E` |
| 12 | add | `left:E, right:E` |
| 13 | minus | `left:E, right:E` |
| 14 | multiply | `left:E, right:E` |
| 15 | divide | `left:E, right:E` |
| 16 | mod | `left:E, right:E` |
| 17 | gt | `left:E, right:E` |
| 18 | lt | `left:E, right:E` |
| 19 | eq | `left:E, right:E` |
| 20 | ne | `left:E, right:E` |
| 21 | ge | `left:E, right:E` |
| 22 | le | `left:E, right:E` |
| 23 | and | `left:E, right:E` |
| 24 | or | `left:E, right:E` |
| 25 | not | `value:E` |
| 26 | neg | `value:E` |
| 27 | if | `condition:E, then:E, has_else:bool, [else:E]` |
| 28 | wi | `condition:E, body:E` |
| 29 | brk | None |
| 30 | cont | None |
| 31 | cleanup | `body:E, finalizer:E, on_error:bool` |
| 32 | new_block | `size:int` |
| 33 | block_address | `value:E` |
| 34 | mv | `slot:int` |
| 35 | drop | `target:E` |
| 36 | context_abi | `context:Context` |
| 37 | context_default | `context:Context` |
| 38 | return_typed | `value:E, context:Context` |
| 39 | check_type | `value:E, context:Context` |
| 40 | buffer_new | `element:Context` |
| 41 | buffer_op | `operation:int, receiver:E, arguments:Es` |
| 42 | value_compare | `context:Context, left:E, right:E` |
| 43 | bit_and | `left:E, right:E` |
| 44 | bit_or | `left:E, right:E` |
| 45 | bit_xor | `left:E, right:E` |
| 46 | shl | `left:E, right:E` |
| 47 | shr | `left:E, right:E` |
| 48 | ushr | `left:E, right:E` |
| 49 | convert | `type:int, value:E` |
| 50 | select | `condition:E, then:E, else:E` |
| 51 | check_object | `value:E, context:Context, slot_types:Stack<int>` |
| 52 | set_methods | `address:E, entries:Stack<[function_id:int, contexts:Stack<Context>]>` |
| 53 | call_virtual | `slot:int, return_type:int, args:Es` |

`if`/`wi`/`select` conditions and every evaluated operand of `and`/`or`/`not` must have BOOLEAN_VALUE (4). Numeric zero/nonzero and void are not truth values; a wrong tag throws `Condition must be boolean, got <type>`. `and`/`or` remain left-to-right and short-circuit, so a skipped right operand is not evaluated or checked at runtime. Source compilation also rejects statically known non-boolean operands, including the lowered `for` condition.

`eq`/`ne` compare mixed int/float/double values numerically, two booleans or two strings by value, and two addresses by their uint64 values. Null and class pointers erase to address; source compilation separately requires compatible class-pointer types. Other mixed tags throw rather than producing unequal/equal. The address-specific rejection still applies to address/non-address pairs. OBJECT_VALUE operands (class values and buffers) have no generic equality. For two runtime void values, `eq` is true and `ne` false, including values read from uninitialized dynamic variables; statically void operands are rejected by the compiler. These checks tighten execution semantics within exec v10; the wire layout and opcode numbers do not change.

`cont` ends the current iteration of the innermost loop. Each exited block cleans its objects before the loop consumes the jump. `for` lowers to blocks and `wi`, with no separate opcode: initialize once, skip the step on the first iteration, then step before subsequent condition checks. The loop body has its own scope. Consequently `continue` runs the step; `break` and `return` do not.

Source `switch` has no dedicated opcode. It lowers to a scoped temporary for the value and a one-shot `wi` containing sibling `if` sections, with balanced `or` trees of `eq` label comparisons and default last. Only the selected section executes. Compiler-generated jump flags preserve the nearest source loop/switch target for `break` and the nearest source loop for `continue`; ordinary block cleanup still applies. Label comparisons consume the normal instruction budget, giving O(labels checked) selection cost. The wire format and exec v10 version do not change.

`cleanup` is compiler-internal. The inspection JSON uses `v` for the body, `val` for cleanup, and `on-error`. The body and its scope cleanup run first. With `on_error=true`, the finalizer runs only on failure; otherwise it runs after normal completion, jumps, or errors. It does not replace an existing return value or jump. An existing error takes precedence; otherwise a cleanup error propagates. Inheritance constructors use nested cleanup wrappers to destroy completed owning fields in reverse order before rolling back the completed base portion. A throwing field cleanup still runs the remaining field and base cleanup, preserving the first error and its trace. Destructors likewise use cleanup wrappers to ensure subsequent cleanup. Both children are ordinary expressions; class names, inheritance, and layouts do not enter exec instructions. Cleanup remains subject to instruction/call budgets while underlying storage release retains its fallback guarantee. Interpreters reject unknown opcodes explicitly.

## Closed object storage

Compiler-internal `object_alloc` (`0x0abd0014`, `address(int)`) uses a normal CALL record and tags its allocation immediately, before construction. It is not callable by a source name and does not add an opcode. `oa` requires tagged object storage or a live literal/view address and checks bounds; ordinary raw allocations cannot be used as typed objects.

Raw `mem_get`, `mem_free`, `make_free`, and `mem_send_up` reject tagged starts/interiors and literal/view addresses, including objects still being constructed. The error is `<name> cannot access object storage; use typed member access`. JNI raw put/free rejects them too. The decoder recognizes compiler-generated `mem_get(oa(...))` as private typed member access, which remains usable as an `m`/`drop` target; this does not make a standalone raw `mem_get` safe for objects.

Construction completion (`ob`/`register_object`) registers lifecycle state before a manual object's automatic owner is detached. Failed registration therefore retains cleanup responsibility; a manual factory does not call raw `make_free` on object storage. Free/destroy clears object tags, and snapshot restoration reconstructs them from validated registered object ranges, without a dedicated storage-tag field. Exec remains v10; method bindings use the snapshot v10 records below. Storage tags do not encode nominal class identity or prevent stale aliases after heap-address reuse.

## Literal objects (opcodes 32–35)

Literal objects live in slot blocks owned by storage variables. Assignment, arguments, and returns move or copy all slots according to runtime rules. At the owner's lifetime end, the registered destructor runs and addresses expire. Only compiler lowering produces these instructions; source cannot spell them directly:

- `new_block` creates `size` (1…1048576) empty slots as a temporary object value for the current statement, initially without a destructor.
- `block_address` evaluates an object value and returns its storage address, rejecting expired blocks. The high bit is 1; remaining bits are `id << 24 | offset`. IDs are never reused, so expired addresses never refer to later objects. `oa` and `ob` accept these addresses; raw `mem_get` rejects them, while compiler-generated typed member access uses the private member path. For blocks, `ob` only registers a destructor and requires `manual=false`. `od` and `mem_free`/`make_free`/`mem_send_up` do not manage block storage.
- `mv` reads a nonnegative local/parameter slot and converts its owned block into a temporary in the current frame, allowing factories to forward already copied arguments to constructors.
- `drop` takes the same target as `m` (a variable or `mem_get` call), immediately runs the owned block's destructor and ends it, disposing of this class's literal fields after its destructor body.

Storage rules: assigning into storage that already owns a class block assigns slot by slot in place; buffer assignment uses the replacement rule below; storing a temporary block moves it; storing other blocks deep-copies them, recursively copying nested blocks up to 64 levels. For blocks returned by `r`/`ro`, this call's locals and parameters transfer directly to the caller frame; other blocks are copied first. Call results and argument values become temporaries according to the same rules. Each statement end and each `if`/`while` condition evaluation destroys unbound temporaries in the current frame.

## Generic operation contexts (opcodes 36–39)

`hidden_count` is the number of operation contexts a function receives, separate from visible arguments and frame slots. `entry_kind` is 0 for an ordinary entry or 1 for a compiler-internal entry. The inspection JSON uses `hidden-count` and `entry-kind`; decoders emit both even when zero. Lifecycle hooks require zero hidden contexts. Public C++/JNI invocation cannot supply hidden contexts: expose an ordinary non-generic wrapper around a generic implementation. Reflection accepts only ordinary entries with zero hidden contexts. Generic externs resolve to script implementations, not native or Java callbacks.

A context describes only ABI behavior, never a class name, layout, bound, or native pointer. Each context is a raw stack:

```text
Reference = [ 0, index:int ]
Fixed     = [ 1, abi:int, kind:int, width:int,
              has_factory:bool, [factory_id:int], contexts:Stack<Context>,
              has_placement:bool, [placement_id:int], placement_contexts:Stack<Context>,
              has_element:bool, [element:Context] ]
```

The inspection JSON uses `{"ref":0}` for references. Fixed contexts contain `abi`, `kind`, `width`, `contexts`, and `placement-contexts`, plus optional `factory`, `placement`, and `element`. References must be below the enclosing hidden count, including nested references. Kind 0 covers scalar ABI 0–5, kind 1 raw address ABI 7, kind 2 class pointer ABI 7, kind 3 class value ABI 8, and kind 4 buffer ABI 8. ABI 6 is invalid. Void is only for reflection results, never an element or generic argument. Width is 1 except for class values, where it is the direct slot count including inherited fields (1…1048576). Contexts store no field layout or class name.

Only kind 3 permits factories or placement constructors; each absent ID requires its corresponding child list to be empty. A factory is a script function returning object without visible parameters; a placement constructor is an internal `void(address)` script function. Each receives exactly its supplied hidden child contexts. Either entry may be absent; requesting an unavailable default operation fails at execution. Kind 4 requires exactly one element context, width 1, and no factory or placement entry. Creating this context or an empty buffer does not default-construct elements.

CALL binds its `contexts` against the current frame before invoking the target; their number must match the target's hidden count. `ob` captures its contexts alongside the destructor, so later destruction does not depend on the creating frame. Without a destructor the list must be empty. `context_abi` returns the bound ABI number. `context_default` produces the scalar zero/empty value, null address, a fresh object from the bound factory, or an empty buffer. `return_typed` checks the bound ABI and applies object-value return or class-pointer ownership transfer as appropriate; raw address returns retain ordinary address semantics. `check_type` validates a value against the bound ABI and returns it. The last two inspection nodes use `v` and `context`; the first two use `context`.

Every context record and child-list stack counts toward the same 128-level physical ABD limit. Hidden counts and context lists retain the slot-count bounds. Link validation checks hidden counts and entry kinds in addition to erased signatures, and relocates only explicit factory/function references. Bound contexts are immutable runtime data.

JNI snapshot v10 saves each object's bound destructor contexts recursively, including ABI, kind, optional default-factory ID, and child contexts. No frame references or native pointers are saved. Restoration validates the ordered module identity, context kinds/depth, each factory signature and hidden arity, and the destructor's `void(address)` signature and hidden arity before replacing any state. It does not run old destructors or rerun initialization. Previous snapshots, including v9 and v8, are rejected.

## Buffer storage and comparison (opcodes 40–42)

`buffer_new` creates an empty owning OBJECT_VALUE with the bound element context; the inspection JSON uses `context`. `buffer_op` uses inspection fields `op`, `v`, `args`: operations 0–6 are length, capacity, reserve, get, set, push, resize, requiring 0, 0, 1, 1, 2, 1, 1 arguments respectively. The receiver is borrowed and evaluated once before the arguments, which are materialized left to right. get produces a value copy. Buffers are not class blocks for raw address/resize APIs; these APIs cannot bypass their lifecycle rules.

Each buffer owns a contiguous capacity × stride slab. Class elements are separately identified object views over N direct slots; other elements occupy one slot, with nested owning values separately allocated. Only successfully initialized elements count toward length. Capacity changes transfer slots without executing user code and expire old view IDs; shrink and cleanup destroy in reverse order. Copies preserve capacity and recursively copy owned values. Buffer assignment prepares a candidate before destroying and replacing the old buffer, and still installs it when old cleanup throws. If a callback ends the destination’s lifetime, it cleans the candidate and stops. Element set retains fieldwise assignment's basic guarantee. Busy operations retain storage; reentrant mutation fails and destruction is deferred until the operation leaves, with invalidation detected before continuing. Bulk work consumes execution budget; failures retain original errors and complete underlying release.

`value_compare` uses inspection fields `context`, `v1`, `v2`, produces exactly -1/0/1, and supports scalar non-void, address, and pointer contexts. It rejects class/buffer contexts and nonfinite floating values, compares strings as unsigned UTF-8 bytes, and treats signed zero equally. Both operands evaluate once left to right. Operators declared on classes lower to ordinary CALL nodes, with no operator-specific executable metadata.

Snapshot v10 carries width, placement bindings, and recursive element contexts. Buffer object records additionally contain `buffer:true`, `element`, `length`, `capacity`; `slots` encodes only live logical elements. A class element uses its object-view ID and destructor/context/field record, so slab fields are encoded once, without serializing spare capacity. Restoration prechecks the capacity budget, validates length/capacity/stride, tags, unique IDs, nesting, and all function bindings while building temporary slabs and views with fresh IDs, and remaps saved addresses. Only fully validated temporary state replaces the old state. Busy/transitional states cannot be saved. Across all buffers, a single snapshot's cumulative capacity × stride may not exceed 1,048,576 slots: saving or restoring beyond this persistence budget fails before allocating slabs. The runtime's per-block capacity bound and the 64 MiB file limit also apply. State replacement never destroys the old state through user code.

## Bitwise and shifts (opcodes 43–48)

Each instruction is exactly `[opcode:int, left:E, right:E]`: 43 `bit_and`, 44 `bit_or`, 45 `bit_xor`, 46 `shl`, 47 `shr`, and 48 `ushr`. The inspection JSON uses `v1` and `v2` for the operands. Both evaluate once, left to right, and must be INT_VALUE (0), including shift counts; no numeric or boolean conversion applies. The result is also int32.

Operations use uint32 bit patterns, then interpret the result as signed int32. `shl` discards high bits and fills with zero, `shr` sign-extends, and `ushr` fills with zero. Counts must be 0…31, inclusive, without masking; an invalid count throws `std::runtime_error` (JNI `IllegalStateException`), for example `Shift count must be between 0 and 31, got 32`. Shift wrap does not trigger arithmetic overflow. Source `~x` lowers to `bit_xor(x, -1)`; compound assignments lower to ordinary variable assignment of the corresponding binary operation. These opcodes extend exec v10 without changing its version or root/function layout; consumers must support opcodes through 48.

## Explicit conversion (opcode 49)

The record is exactly `[49, type:int, value:E]`, where the target type is int=0, string=1, float=2, double=3, or address=7; readers reject all other targets and missing/extra fields. The inspection JSON uses `type` and `v`. The value evaluates once before conversion. A statically known same-type source conversion emits only its source expression; a dynamic any source always emits this instruction, and same-tag runtime conversion is identity for these five targets.

Numeric tags convert to int/float/double or string. Strings parse as decimal int/float/double or retain string identity. Address converts only to unsigned-decimal string or address identity. Boolean, void, and object tags are rejected. Class pointers and null are erased addresses in exec, so source compilation permits only explicit class-pointer-to-address erasure (also for pointer-bound T), emitted without opcode 49, and rejects other class-pointer/null conversions; opcode 49 does not recover source class identity. int/address conversion and string-to-address parsing are unsupported.

Numeric-to-int truncates toward zero before checking int32 range. Float/double conversions reject overflow and permit subnormals and underflow to zero. Parsing is locale-independent: integer strings require an optional sign and decimal digits only; floating strings permit a decimal point and exponent, with no whitespace, hex, suffix, NaN/Infinity, embedded NUL, or trailing text. Errors use `Cannot convert X to Y` with an invalid-decimal or out-of-range reason as appropriate; quoted strings are ASCII-escaped and truncated after 64 Unicode scalars. The language guide gives the source conversion matrix and examples. Explicit conversion extends exec v10 without changing the root or function layout.

## Conditional selection (opcode 50)

The record is exactly `[50, condition:E, then:E, else:E]`. All three child expressions are required; missing or extra fields are rejected. Inspection JSON is `{"t":0,"c":50,"v":condition,"then":thenValue,"else":elseValue}`. Child traversal for debug records follows condition, then, else order.

`select` evaluates the condition once and requires a boolean, then evaluates only the selected arm once and returns its value. A non-boolean condition fails with `Condition must be boolean, got int` when its tag is int. Evaluation uses the current scope rather than the branch scopes of opcode 27 `if`; selection adds no cleanup boundary. Selected owned temporaries and automatic pointer objects retain their ordinary statement/block lifetimes.

The source compiler checks both arms for a compatible non-void common type and inserts opcode 49 `convert` around narrower numeric arms. Pointer compatibility and pointer-bound type parameters with null are compile-time checks; `select` itself does not convert the selected value or recover erased class types. Exec stays v10, with unchanged root/function records; consumers must support opcode 50.

## Checked references (opcode 51)

The exact record is `[51, value:E, context:Context, slot_types:Stack<int>]`; inspection JSON is `{"t":0,"c":51,"v":value,"context":context,"slot-types":[tags...]}`. `slot_types` contains direct-field ABI tags, with 6 meaning unchecked. The value is evaluated once and returned unchanged after validation. Source compilation emits this only around `reflect_invoke_function<R>` results for class pointers, class values, buffers, or type parameters; it is not a construction or dispatch check.

A pointer accepts null or the start of a live registered, non-destroying object or a live non-buffer literal/view. It requires at least as many direct slots as the supplied vector and checks that prefix's non-6 tags. A concrete class value requires live non-buffer object storage of exactly the declared width and a full matching direct-slot vector. Raw allocations, expired values, interior pointers, destroying objects, and mismatched widths/tags fail with `Object check failed: ...`.

For a buffer, element contexts must match by ABI, kind, width, and recursively nested element context under the depth bound. Factory/placement IDs and nominal class identity are not compared. `R=T` emits a reference context with an empty slot-types vector: after binding, runtime checks the concrete kind and liveness. A bound class value must match its kind-3 context width; a bound pointer only checks null/object-start/liveness, because kind-2 width remains the address ABI width 1 and contains no pointee layout. Unknown T has no direct-field tags to check. Pointer-field targets are never recursively validated. Matching storage shape therefore does not establish class identity or complete memory safety.

The context uses the existing reference/fixed encoding and participates in its depth and hidden-context checks. Exec stays v10; no root or function fields change, and consumers must support opcode 51.

## Virtual method tables (opcodes 52–53)

```text
[52, address:E, entries:Stack<[function_id:int, contexts:Stack<Context>]>]
[53, slot:int, return_type:int, args:Es]
```

Inspection JSON is `{"t":0,"c":52,"v":address,"entries":[{"id":functionId,"contexts":[]}]}` and `{"t":0,"c":53,"slot":slot,"type":returnType,"param":[receiver,args...]}`. Contexts use the existing context encoding. The receiver is the first address argument; all arguments evaluate once left to right before lookup/invocation. The bound table supplies the target function and hidden contexts. A result ABI other than any (6) is checked exactly; a generic any result receives its separate `check_type` check.

`set_methods` requires storage owned by the executing script, including its objects still being constructed or destroyed; modules within one assembled script share that owner. An empty list clears the table. Installing bindings charges steps per entry. `call_virtual` follows ordinary CALL argument, budget, cleanup, and error rules; null, expired, missing-table, foreign-script, and out-of-range-slot receivers fail. Tables are installed for each constructor/destructor phase. They live in separate heap bookkeeping or literal/view storage, not user slots: layout and stride are unchanged. Copying preserves bindings; fieldwise assignment retains the destination table. Destruction removes tables and restoration replaces them atomically.

Flush relocates every method function ID and function reference recursively within its contexts under the defining module's assumptions; method slot numbers are not relocatable IDs. Each entry must name an ordinary script function whose first parameter is address and whose hidden count matches the supplied contexts. Native/Java bindings cannot supply entries. These checks do not verify nominal class identity or agreement between independently compiled header slot orders. These opcodes extend exec v10 without changing its version, root, or function record.

## JNI snapshot v10 method records

Every heap-object entry and literal/view record requires `methods:[{"id":functionId,"contexts":[context,...]}]`, with contexts encoded as the existing serialized context maps. `[]` means no method table and is required on buffer records; class element views may carry bindings. Bound contexts are data, never native pointers or references to expired frames.

Saving rejects a heap method table without a corresponding registered object. Restoration validates required fields, ordinary script function bindings, the address-first parameter and hidden-context count, and recursive context bindings before atomic replacement. Foreign callback entries and missing `methods` fields are rejected. Only snapshot v10 is accepted, independently of exec v10; old v9 and unversioned snapshots are unsupported. The public C++ storage ABI changed: rebuild hosts and plugins with matching headers and runtime libraries.

## Return-value checks

At a script function boundary, a numeric result must exactly match the declared return tag or widen from int (0) to float (2)/double (3), or from float (2) to double (3). int-to-float conversion may round under the same rules as explicit conversion. A double result cannot satisfy an int or float return, and a float result cannot satisfy an int return, even if the value happens to fit. This also checks dynamically typed results in hand-written exec and compiled scripts. A mismatch throws `Function return type mismatch: expected int, got double`, with the actual expected/received type names.

Intentional narrowing requires an explicit `convert` expression before the return. Its truncation, range, rounding, and underflow checks run first; the resulting tag then matches the declaration. Native callback signatures still require exact argument/result tags; object ownership and pointer-return rules remain in force. These return checks change semantics only: exec stays v10, with no new opcode or record field.

## String built-ins

String operations use ordinary CALL records (opcode 2) with reserved IDs below; no extern signature, new opcode, or format version is introduced.

| Name | Signature | Function ID |
| --- | --- | --- |
| `str_length` | `int(string)` | `0x0abd000b` |
| `str_byte_length` | `int(string)` | `0x0abd000c` |
| `str_substring` | `string(string, int, int)` | `0x0abd000d` |
| `str_char_at` | `string(string, int)` | `0x0abd000e` |
| `str_code_at` | `int(string, int)` | `0x0abd000f` |
| `str_from_code` | `string(int)` | `0x0abd0010` |
| `str_index_of` | `int(string, string[, int])` | `0x0abd0011` |
| `str_starts_with` | `boolean(string, string)` | `0x0abd0012` |
| `str_ends_with` | `boolean(string, string)` | `0x0abd0013` |

These take exact string/int argument tags. `str_index_of` accepts two or three arguments at runtime, defaulting from to zero; source compilation always supplies the third argument. String methods lower to these same IDs, `s[i]` to `str_char_at(s, i)`, and `s.contains(n)` to `str_index_of(s, n, 0) >= 0`. There is no contains ID. Receivers and arguments evaluate once in order.

All string arguments must be valid UTF-8, including for byte length and empty-needle/prefix/suffix cases; NUL is valid. Indices, lengths, substring endpoints, and search results count Unicode code points, except `str_byte_length` counts bytes. `char_at` returns a one-code-point string and `code_at` its int scalar. Substrings use `[begin, end)` with `0 <= begin <= end <= length`; char/code indices require `0 <= index < length`, and search starts require `0 <= from <= length`. Empty needles return from, absence returns -1, and empty prefixes/suffixes match. `str_from_code` excludes negative values, surrogates, and values above 0x10ffff. Bounds errors are `std::out_of_range`; invalid UTF-8/scalars are `std::invalid_argument`. Strings remain immutable; methods and indexing add no storage or object ABI.

## Type numbers and constants

Signature and `declared_type` numbers are int=0, string=1, float=2, double=3, boolean=4, void=5, any=6, address=7, object=8. Parameters and variables cannot be void. Untyped script parameters/variables use any. An any return or any external parameter is permitted only when `hidden_count > 0`, for generic erasure; source extern declarations still require explicit types. The inspection JSON may omit `declared-type` for any. Class pointers, `C (*)` parameters, implicit this, and pointer-factory returns erase to address; callers apply `block_address` to literal-object arguments for `C (*)`. Literal parameters, returns, and variables use object. Addresses do not loosen int checks. These numbers are distinct from ABD scalar tags; object values have no constant encoding.

Constant tags are int=`0x3`, string=`0x1`, float=`0xce867`, double=`0xce1066`, boolean=`0xd00`, address=`0xce200b`; void is a one-byte `ff` payload tagged `0xce200a`. Floating constants must be finite, strings valid UTF-8, and booleans exactly 0/1. Address payloads must contain exactly eight bytes and preserve all unsigned bits; the maximum address is eight `ff` bytes. Exec JSON represents addresses as `{"address":"18446744073709551615"}` and null as `{"address":"0"}`. Binary encoding remains opcode 0 plus a tagged single-element array, without an address-specific opcode. Collections and other byte arrays are not constants.

The base of `oa`, pointer-object values for `ob/od/ro`, built-in memory pointers, and `alloc` results use address. Field offsets, allocation counts, function IDs, type numbers, and variable slots remain four-byte integers. `add/minus` apply uint64-bound-checked offsets between addresses and int32. Address comparisons use unsigned order without double/int32 conversion. Ordinary `r` does not transfer pointer-object ownership.

Unary plus is eliminated at compile time and has no opcode. All old string `c` values are replaced by these integers. The inspection JSON retains call `t=1` and block arrays; binary uses opcodes 0/1/2 for constants/blocks/calls.

Parameters and locals have consecutive nonnegative slot IDs; globals are -1, -2, etc. Each loaded function stores its module's global offset. The actual index is `global_offset + (-int64(slot) - 1)`; the executable cannot store this host-load-time offset. Argument, external-type, and block-lifetime rules are unchanged; return values follow the checks above.

Readers accept only v10 raw stacks, identified by the first frame's magic and following version. Old versions including v9 and v8, Map executables, and unknown versions are rejected with the expected version; recompile source or readable AST. The 64 MiB file limit, 128-level ABD nesting limit, variable-slot limits, and call budgets still apply. Compact encoding cannot bypass nesting checks. The JNI snapshot version is independent of the exec version and is 10; JNI accepts only v10 snapshots, rejects snapshots whose modules are not exec v10, and retains uint64 address scalars, allocation bases, object records, and cleanup order; lengths, counts, and function IDs remain integers. Snapshots encode literal objects in heap/globals as `{object, has destructor, [destructor], destructor contexts, methods, slots}` maps. Restoration allocates new IDs and rewrites pointers to saved objects; addresses to unsaved objects remain unchanged and their IDs are permanently retired. Script bytes are stored and validated opaquely; old snapshots are not read.

Physical container depth starts at root stack level 1, function list level 2, function record level 3, and body level 4. Each nested expression adds a level; block statement lists, call argument lists, and single-element constant arrays each add another. Extension metadata counts internal Map/Array nesting from level 2. Debug file names are at level 3; a function debug record is at level 4, its fields at level 5 and its location integers at level 6. Raw stacks do not bypass the 128-level bound.

The compiler's depth measurement follows this physical shape for every opcode and context role, including method-entry stacks and their context lists. Unknown opcodes fail measurement. When safe, statement-level splitting emits ordinary declarations, assignments, reads and drops before context normalization and debug numbering. Every synthesized record retains the source position of the split. The compiler-internal sequence marker `-1` is never encoded; readers accept only registered executable opcodes. Splitting cannot bypass the 128-level limit, flatten recursive context data, or move a lazy operand/while condition out of its execution point. Ownership-sensitive captures are rejected rather than changing copying or temporary lifetime. Retaining owners across known scalar script calls requires a transitive scope-effect proof; unknown/virtual/reflection calls and unproved any or owned operations cannot pass merely because a result ABI is scalar. IF condition captures are checked and released before branch entry; select/boolean expressions retain ordinary temporary lifetime. A memory-assignment RHS needing cuts must be pure: calls or mutations cause a located RHS-local diagnostic. Pure RHS splitting prevalidates the captured target and preserves the decoder-recognized typed `mem_get(oa(base, offset))` shape and original offset. These are conservative compiler restrictions; runtime storage behavior, wire fields, opcodes, and versions do not change.

## Debug records (`-g`)

Debug info is optional and off by default. `compile -g` and `compile-json -g` (Java API `Compiler.compile(tree, true)`) fill `debug_files` and give every function one debug record; without `-g`, `debug_files` is empty and every `has_debug` is false. Debug records are never semantic: execution, linking, relocation, budgets and reflection ignore them, `flush()` rewrites no debug field, and a function's instructions are identical with and without `-g`. The interpreter validates the records and keeps them with each function.

```text
FunctionDebug = [ name:string, file:int, line:int, column:int, locations:Stack<int> ]
```

- `debug_files` holds the distinct names of the readable AST's `sources` table in order. They are the labels diagnostics print: paths relative to their include root with `/` separators, the main file's bare name, or `<source>` for source text. Entries are nonempty and pairwise distinct, at most 1,048,576.
- `name` is the display name of the function: the source name of a function, `C::m` for a method, `C::C` and `C::~C` for constructors and destructors, `C::operator+` (likewise `-`, `*`, `/`, `[]`, `[]=` and `()`) for operators, and `<new C>`, `<scoped C>`, `<value C>` or `<placement C>` for compiler-generated factories.
- `file`, `line` and `column` locate the declaration, the class declaration for a generated class function. `file` indexes `debug_files`; line and column are both 0 (unknown) or both positive.
- `locations` is a flat int stack of `(node, file, line, column)` quads. Nodes number the expression stacks of `body` in pre-order and wire field order: the body is node 0, and every constant, block, call and opcode expression takes the next number when it is entered; contexts and other fields take none. Nodes strictly increase and stay below the body's node count, `file` indexes `debug_files`, and line and column are 1-based (columns count UTF-16 code units, like compiler diagnostics). A node without an entry has the position of its nearest located ancestor. The compiler locates operators, calls and statements at their source token, not constants or variable reads, and leaves out a node whose position equals that of its nearest located ancestor.

Readers reject names and files that are empty or malformed UTF-8 or contain C0, DEL or C1 controls, U+2028/U+2029 or bidirectional formatting characters (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069); duplicate files; a `has_debug` flag without exactly one record; records without exactly five fields; location stacks that are not whole quads; nodes that do not increase or lie outside the body; file indexes outside `debug_files`; location lines or columns below 1; and header positions with only one of line and column zero. The compiler replaces such characters in display names with U+FFFD, so source names never produce an invalid record.

The inspection view always has the root key `debug-files` (an array of strings, empty without `-g`). A function with a record has `debug`: `{"name":…, "file":…, "line":…, "column":…, "locations":["node:file:line:column", …]}`, each location in canonical decimal.

Debug records put function, class and file names into the executable and add 32 bytes per location; leave out `-g` for releases that should not carry them. A JNI snapshot compares module bytes exactly, so a snapshot saved with a `-g` build cannot be restored into the same program compiled without `-g`, or the other way round.

## Module linking

The root has exactly eleven fields. An empty `namespace_hint` denotes a fixed-namespace module; otherwise it is a case-sensitive hint. The inspection JSON uses `namespace-hint` (string) and `assume-hints` (an array of `{"hint":name,"namespace":integer}`). Assumption namespaces must be 1…65535 excluding `0x0abd` and `0x0fff`; hints and namespaces form a one-to-one mapping without duplicate conflicts. Hint names are ASCII identifiers beginning with a letter or underscore. A nonempty namespace_hint must include itself in assumptions.

Hint modules define functions only under placeholder namespace 0000. IDs 0/1 are per-module initialization/destruction hooks; other definitions relocate on load. Ordinary definitions in fixed-namespace modules cannot occupy their own assumed namespaces. Class names and layouts do not appear in instructions; signatures retain erased basic types. With `-g`, debug records carry display names, which can name classes, but nothing reads them for execution or linking.

`flush()` relocates CALL function IDs, ob destructor IDs, and default-factory/placement-constructor IDs recursively inside contexts using the owning module's assume table, validating all hint imports and signatures. It does not relocate ordinary integers, heap addresses, or variable slots. Assumptions are removed only after complete link validation succeeds. With has_destructor=false there is no destructor-ID field; otherwise all 32 bits are retained, including `0xffffffff`, with no -1 sentinel. Destructors must still name valid `void(address)` script functions. Lifecycle, main, and built-in reserved-ID rules remain in force.

Exec v9 and earlier, old Map executables, and old JNI snapshots are unsupported. Update libraries and hosts together.
