# Exec v8 Binary Format

[中文](EXEC_FORMAT.md) | English

This executable layout version is independent of source `metadata.version`. The readable AST retains source names; exec JSON is an inspection view. Fixed structures below use raw ABD stacks without field names or per-field type tags. Integers are four-byte little-endian int32; addresses are independent eight-byte little-endian uint64 values with 0 as null. Function IDs use the full uint32 bit pattern, booleans are one byte (0/1), and strings are UTF-8. Every field remains a standard ABD frame: a nonnegative int32 length followed by raw payload.

The file itself is one ABD frame whose payload is a root stack in this exact order:

```text
[ magic="AZSCRIPT", exec_version=8, source_version:int, author:string,
  global_count:int, extensions:AbdMap,
  external_signatures:Stack<Signature>, functions:Stack<Function>,
  namespace_hint:string, assumptions:Stack<Assumption> ]

Assumption = [ hint:string, namespace:int ]

Signature = [ function_id:int, return_type:int, parameter_types:Stack<int>,
              hidden_count:int, entry_kind:int ]
Function  = [ function_id:int, return_type:int, parameter_count:int,
              local_count:int, parameter_types:Stack<int>, body:Expression,
              hidden_count:int, entry_kind:int ]
```

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

`cont` ends the current iteration of the innermost loop. Each exited block cleans its objects before the loop consumes the jump. `for` lowers to blocks and `wi`, with no separate opcode: initialize once, skip the step on the first iteration, then step before subsequent condition checks. The loop body has its own scope. Consequently `continue` runs the step; `break` and `return` do not.

`cleanup` is compiler-internal. Debug JSON uses `v` for the body, `val` for cleanup, and `on-error`. The body and its scope cleanup run first. With `on_error=true`, the finalizer runs only on failure; otherwise it runs after normal completion, jumps, or errors. It does not replace an existing return value or jump. An existing error takes precedence; otherwise a cleanup error propagates. Inheritance constructors use it to roll back a completed base portion; destructors use it to ensure subsequent base cleanup. Both children are ordinary expressions; class names, inheritance, and layouts do not enter exec. Cleanup remains subject to instruction/call budgets while underlying storage release retains its fallback guarantee. Interpreters reject unknown opcodes explicitly.

## Literal objects (opcodes 32–35)

Literal objects live in slot blocks owned by storage variables. Assignment, arguments, and returns move or copy all slots according to runtime rules. At the owner's lifetime end, the registered destructor runs and addresses expire. Only compiler lowering produces these instructions; source cannot spell them directly:

- `new_block` creates `size` (1…1048576) empty slots as a temporary object value for the current statement, initially without a destructor.
- `block_address` evaluates an object value and returns its storage address, rejecting expired blocks. The high bit is 1; remaining bits are `id << 24 | offset`. IDs are never reused, so expired addresses never refer to later objects. `oa`, `mem_get`, and `ob` accept these addresses. For blocks, `ob` only registers a destructor and requires `manual=false`. `od` and `mem_free`/`make_free`/`mem_send_up` do not manage block storage.
- `mv` reads a nonnegative local/parameter slot and converts its owned block into a temporary in the current frame, allowing factories to forward already copied arguments to constructors.
- `drop` takes the same target as `m` (a variable or `mem_get` call), immediately runs the owned block's destructor and ends it, disposing of this class's literal fields after its destructor body.

Storage rules: assigning into storage that already owns a block assigns slot by slot in place; storing a temporary block moves it; storing other blocks deep-copies them, recursively copying nested blocks up to 64 levels. For blocks returned by `r`/`ro`, this call's locals and parameters transfer directly to the caller frame; other blocks are copied first. Call results and argument values become temporaries according to the same rules. Each statement end and each `if`/`while` condition evaluation destroys unbound temporaries in the current frame.

## Generic operation contexts (opcodes 36–39)

`hidden_count` is the number of operation contexts a function receives, separate from visible arguments and frame slots. `entry_kind` is 0 for an ordinary entry or 1 for a compiler-internal entry. Debug JSON uses `hidden-count` and `entry-kind`; decoders emit both even when zero. Lifecycle hooks require zero hidden contexts. Public C++/JNI invocation cannot supply hidden contexts: expose an ordinary non-generic wrapper around a generic implementation. Reflection accepts only ordinary entries with zero hidden contexts. Generic externs resolve to script implementations, not native or Java callbacks.

A context describes only ABI behavior, never a class name, layout, bound, or native pointer. Each context is a raw stack:

```text
Reference = [ 0, index:int ]
Fixed     = [ 1, abi:int, kind:int, has_factory:bool,
              [factory_id:int], contexts:Stack<Context> ]
```

Debug JSON represents these as `{"ref":0}` or `{"abi":8,"kind":3,"factory":id,"contexts":[...]}`. References address the enclosing function's hidden contexts and must be below `hidden_count`, including references nested within fixed contexts. Kind 0 covers scalar ABI 0–5, kind 1 raw address ABI 7, kind 2 class pointer ABI 7, and kind 3 literal object ABI 8. ABI 6 is not a context. Void is used for reflection results, not a generic type argument. Only kind 3 may name a factory; a context without a factory must have an empty child list. Factories return object, take no visible parameters, and require exactly the supplied child contexts. An object context without a factory can travel through calls, but requesting its default value fails.

CALL binds its `contexts` against the current frame before invoking the target; their number must match the target's hidden count. `ob` captures its contexts alongside the destructor, so later destruction does not depend on the creating frame. Without a destructor the list must be empty. `context_abi` returns the bound ABI number. `context_default` produces the scalar zero/empty value, null address, or a fresh object from the bound factory. `return_typed` checks the bound ABI and applies object-value return or class-pointer ownership transfer as appropriate; raw address returns retain ordinary address semantics. `check_type` validates a value against the bound ABI and returns it. The last two debug nodes use `v` and `context`; the first two use `context`.

Every context record and child-list stack counts toward the same 128-level physical ABD limit. Hidden counts and context lists retain the slot-count bounds. Link validation checks hidden counts and entry kinds in addition to erased signatures, and relocates only explicit factory/function references. Bound contexts are immutable runtime data.

JNI snapshot v8 saves each object's bound destructor contexts recursively, including ABI, kind, optional default-factory ID, and child contexts. No frame references or native pointers are saved. Restoration validates the ordered module identity, context kinds/depth, each factory signature and hidden arity, and the destructor's `void(address)` signature and hidden arity before replacing any state. It does not run old destructors or rerun initialization. Previous snapshots, including v7, are rejected.

## Type numbers and constants

Signature and `declared_type` numbers are int=0, string=1, float=2, double=3, boolean=4, void=5, any=6, address=7, object=8. Parameters and variables cannot be void. Untyped script parameters/variables use any. An any return or any external parameter is permitted only when `hidden_count > 0`, for generic erasure; source extern declarations still require explicit types. Debug JSON may omit `declared-type` for any. Class pointers, `C (*)` parameters, implicit this, and pointer-factory returns erase to address; callers apply `block_address` to literal-object arguments for `C (*)`. Literal parameters, returns, and variables use object. Addresses do not loosen int checks. These numbers are distinct from ABD scalar tags; object values have no constant encoding.

Constant tags are int=`0x3`, string=`0x1`, float=`0xce867`, double=`0xce1066`, boolean=`0xd00`, address=`0xce200b`; void is a one-byte `ff` payload tagged `0xce200a`. Floating constants must be finite, strings valid UTF-8, and booleans exactly 0/1. Address payloads must contain exactly eight bytes and preserve all unsigned bits; the maximum address is eight `ff` bytes. Exec JSON represents addresses as `{"address":"18446744073709551615"}` and null as `{"address":"0"}`. Binary encoding remains opcode 0 plus a tagged single-element array, without an address-specific opcode. Collections and other byte arrays are not constants.

The base of `oa`, pointer-object values for `ob/od/ro`, built-in memory pointers, and `alloc` results use address. Field offsets, allocation counts, function IDs, type numbers, and variable slots remain four-byte integers. `add/minus` apply uint64-bound-checked offsets between addresses and int32. Address comparisons use unsigned order without double/int32 conversion. Ordinary `r` does not transfer pointer-object ownership.

Unary plus is eliminated at compile time and has no opcode. All old string `c` values are replaced by these integers. Debug JSON retains call `t=1` and block arrays; binary uses opcodes 0/1/2 for constants/blocks/calls.

Parameters and locals have consecutive nonnegative slot IDs; globals are -1, -2, etc. Each loaded function stores its module's global offset. The actual index is `global_offset + (-int64(slot) - 1)`; the executable cannot store this host-load-time offset. Argument, return, external-type, and block-lifetime rules are unchanged.

Readers accept only v8 raw stacks, identified by the first frame's magic and following version. Old versions including v7, Map executables, and unknown versions are rejected; recompile source or readable AST. The 64 MiB file limit, 128-level ABD nesting limit, variable-slot limits, and call budgets still apply. Compact encoding cannot bypass nesting checks. JNI accepts only v8 snapshots, retaining uint64 address scalars, allocation bases, object records, and cleanup order; lengths, counts, and function IDs remain integers. Snapshots encode literal objects in heap/globals as `{object, has destructor, [destructor], destructor contexts, slots}` maps. Restoration allocates new IDs and rewrites pointers to saved objects; addresses to unsaved objects remain unchanged and their IDs are permanently retired. Script bytes are stored and validated opaquely; old snapshots are not read.

Physical container depth starts at root stack level 1, function list level 2, function record level 3, and body level 4. Each nested expression adds a level; block statement lists, call argument lists, and single-element constant arrays each add another. Extension metadata counts internal Map/Array nesting from level 2. Raw stacks do not bypass the 128-level bound.

## Module linking

The root has exactly ten fields. An empty `namespace_hint` denotes a fixed-namespace module; otherwise it is a case-sensitive hint. Debug JSON uses `namespace-hint` (string) and `assume-hints` (an array of `{"hint":name,"namespace":integer}`). Assumption namespaces must be 1…65535 excluding `0x0abd` and `0x0fff`; hints and namespaces form a one-to-one mapping without duplicate conflicts. Hint names are ASCII identifiers beginning with a letter or underscore. A nonempty namespace_hint must include itself in assumptions.

Hint modules define functions only under placeholder namespace 0000. IDs 0/1 are per-module initialization/destruction hooks; other definitions relocate on load. Ordinary definitions in fixed-namespace modules cannot occupy their own assumed namespaces. Class names/layouts do not appear; signatures retain erased basic types.

`flush()` relocates CALL function IDs, ob destructor IDs, and default-factory IDs inside contexts using the owning module's assume table, validating all hint imports and signatures. It does not relocate ordinary integers, heap addresses, or variable slots. Assumptions are removed only after complete link validation succeeds. With has_destructor=false there is no destructor-ID field; otherwise all 32 bits are retained, including `0xffffffff`, with no -1 sentinel. Destructors must still name valid `void(address)` script functions. Lifecycle, main, and built-in reserved-ID rules remain in force.

Exec v7 and earlier, old Map executables, and old JNI snapshots are unsupported. Update libraries and hosts together.
