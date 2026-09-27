# AzScript basic containers

[中文](CONTAINERS.md) | English

[`containers.azs`](containers.azs) is an independently compiled AzScript library with `#namespace_hint AZSCRIPT_CONTAINERS`. Clients include only [`containers.include.azs`](containers.include.azs), which assumes namespace `c071` and declares fully typed `extern` members. `List<T>`, `Stack<T>`, `Queue<T>`, `Vector<T>`, `Set<T>`, and `Map<K, V>` each compile to one body, so every type argument shares the same low 16-bit IDs. The linked containers keep O(1) ends. `Vector`, `Set`, and `Map` use a contiguous `buffer`.

```c
#include "../stdlib/containers.include.azs"

int main() {
    List<int> * xs = new List<int>();
    xs.push_back(3);
    xs.push_back(1);
    list_sort_int(xs);
    int first = xs.front();
    delete xs;
    return first;
}
```

The header guard is `AZSCRIPT_CONTAINERS_INCLUDED`. Do not include the implementation. [`../examples/containers-regressions.azs`](../examples/containers-regressions.azs) returns `0` on success.

## Build and link

```sh
./azscript compile compiler/stdlib/containers.azs -o build/containers.exec.abd \
    --ast build/containers.ast.json --exec-json build/containers.exec.json
./azscript compile compiler/examples/containers-regressions.azs -o build/containers-regressions.exec.abd
./build/native/interpreter/azscript-run build/containers-regressions.exec.abd --insert build/containers.exec.abd
```

Distributions ship precompiled `stdlib/containers.exec.abd` plus both JSON views. After editing the sources, rebuild from the package root:

```sh
./compile.sh stdlib/containers.azs
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

`c071` is only the compile-time alias. Hosts call `namespace_for_hint("AZSCRIPT_CONTAINERS")` or `namespaceForHint("AZSCRIPT_CONTAINERS")` and combine that namespace with the low 16 bits. A missing library reports `AZSCRIPT_CONTAINERS`.

## Storage

`List`, `Stack`, and `Queue` store each element in a `Node<T>` `value` field. `prev` and `next` form a doubly linked list. Indexing walks from the nearer end. Inserting or erasing in the middle also walks the list, so that takes time proportional to the length. Insert and pop at either end are O(1), so a queue still leaves from the head in O(1). `list_sort_int`, `list_sort_double`, and `list_sort_address` are stable insertion sorts and compare a quadratic number of elements.

`Vector<T>`, `Set<T>`, and `Map<K, V>` store elements in a contiguous owning `buffer`. Vector indexing is O(1). `Set` and `Map` are sorted flat tables: binary search, with insert and erase shifting the tail in time proportional to the length. There is no generic hash, so these are not hash tables, and a `buffer` is not stored with `mem_get`.

`Node<T>` and `Entry<K, V>` are in the header because field types must match the implementation. Treat them as link records and use `List`, `Stack`, `Queue`, `Vector`, `Set`, and `Map`.

An unbounded `T` cannot be constructed with `new T()` and has no `<` or `==`. List sort, find, and count therefore stay ordinary functions of concrete types, and reflection can call those functions. Generic methods carry a type context, so reflection cannot call them directly.

`Set` and `Map` call `value_compare<T>` when the comparator id is `0`. That orders `int`, `float`, `double`, `string` (unsigned UTF-8 bytes, including an embedded NUL), `boolean` (`false` < `true`), `address`, and class pointers, and returns `-1`, `0`, or `1`. Concrete class values and buffers have no default order. A generic body may call `value_compare`; an unsupported runtime type fails with `This type requires a custom comparison function`. Class-value keys need `set_order` first, passing the full runtime id of an ordinary comparator. The comparator must be a non-generic function without a hidden context. It cannot be a generic method or a closure. Integers are not assume-relocated.

A class value stored in a list node, or in an `Entry` `key` or `value`, is default-constructed. A class value without a zero-argument constructor cannot go in `List`, `Stack`, or `Queue`, and cannot be a `Map` key or value. `Vector` and `Set` `push_back` / `insert` copy an existing value and do not need that constructor. Growing `Vector::resize` still does. Scalars, `address`, and class pointers have no such requirement. A class pointer is only an address; destroying the container does not `delete` the object.

## Ownership

Create a container with `new` and destroy it with `delete`. The `List` destructor deletes every node. If an element destructor fails, the nodes not yet released are still deleted, and the first error is kept. A run of failing element destructors is limited by the call-depth budget. `Stack` and `Queue` each own one `List` and delete it. `clone` returns a new `List<T> *` that the caller deletes.

Copying a `List`, `Stack`, or `Queue` by value copies the node pointers, so both destructors would `delete` the same nodes. Use pointers:

```c
List<int> * xs = new List<int>();
delete xs;
```

Copying a `Vector`, `Set`, or `Map` by value deep-copies the elements, so each container owns its storage. Subscript, `at`, and `get` return a copy of a class value, so `xs[i].field = ...` does not change the container. Edit a local copy and write it back with `set` or `operator[]=`. Create these containers with `new` and destroy them with `delete`; the destructor destroys the buffered elements.

## Operations

`push_back` and `push_front` store the argument in a new node. `resize(n)` appends default nodes when growing: `0`, `0.0`, `""`, `false`, or `null` for `int`, `double`, `string`, `boolean`, `address`, and class pointers. Class values use their zero-argument constructor. Shrinking deletes from the tail. `clear` deletes every node. `insert` accepts an index equal to the current length, which appends. `reverse` rewires pointers. `swap` exchanges `head`, `tail`, and `count`.

A stack is `push` / `top` / `pop` at the tail. A queue is `push` / `front` / `pop`, entering at the tail and leaving at the head.

`list_find_*` returns `-1` when the value is absent. `list_count_*` counts with `==`. Strings and booleans have no ordering operators, so the list only has find and count. `Set<string>` and `Set<boolean>` can be ordered because they use `value_compare`.

### Vector

```c
Vector<int> * xs = new Vector<int>();
xs.push_back(3);
xs[0] = 1;
int first = xs[0];
delete xs;
```

Growth calls `reserve` first: an empty vector becomes 4, otherwise the capacity doubles. Doubling that would overflow int32 uses the exact needed length. Public `reserve(n)` grows to exactly `n` and does not double. `push_back` grows itself. A middle `insert` does not use a growing `resize`, which would default-construct: it reserves, pushes a copy of the last element, shifts backward, then writes the new value. `erase` shifts forward and shrinks. `clear` sets the length to 0 and keeps the capacity. `operator[]` and `operator[]=` are `at` and `set`.

### Set and Map

Equal elements: `Set::insert` keeps the original value, and `Map::put` replaces the value. `Set::erase` and `Map::erase` do nothing when the element is absent. `Map::get` and `operator[]` raise `Division by zero` for a missing key. `operator[]=` inserts a missing key. `at` reads a sorted element by index.

`set_order(int id)` stores the comparator's full function id. A non-empty container is reordered with a stable insertion sort: an element moves only when the comparison is greater than 0, so equal elements keep their relative order. After sorting, a run of elements that compare equal collapses to one entry. `Set` keeps the earlier element, matching `insert`. `Map` keeps the last entry, matching `put`. Later lookup, insert, and erase use that single entry, so a second equivalent key is not left behind. An id of `0` selects `value_compare`.

Reading an end, popping, indexing out of range, passing a negative `resize` or `reserve`, or passing `null` to `swap` or to a concrete sort, find, or count raises `Division by zero`. That is a failed precondition, not a catchable exception.

## IDs

| Type | IDs | Members |
| --- | --- | --- |
| `Node<T>` | `0002`–`0003` | constructor, destructor |
| `List<T>` | `0004`–`0016` | constructor, destructor, `size`, `empty`, `push_back`, `push_front`, `pop_back`, `pop_front`, `front`, `back`, `at`, `set`, `insert`, `erase`, `clear`, `resize`, `reverse`, `clone`, `swap` |
| `Stack<T>` | `0017`–`001d` | constructor, destructor, `push`, `pop`, `top`, `size`, `empty` |
| `Queue<T>` | `001e`–`0024` | constructor, destructor, `push`, `pop`, `front`, `size`, `empty` |
| Functions | `0025`–`0031` | `list_sort_int`, `list_sort_double`, `list_sort_address`, then `list_find_*` and `list_count_*` for int, double, string, boolean, and address |
| `Entry<K, V>` | `0038`–`0039` | constructor, destructor |
| `Vector<T>` | `0040`–`0051` | constructor, destructor, `size`, `empty`, `capacity`, `reserve`, `push_back`, `pop_back`, `resize`, `clear`, `at`, `set`, `front`, `back`, `insert`, `erase`, `operator[]`, `operator[]=` |
| `Set<T>` | `0052`–`005a` | constructor, destructor, `size`, `empty`, `insert`, `erase`, `contains`, `at`, `set_order` |
| `Map<K, V>` | `005b`–`0065` | constructor, destructor, `size`, `empty`, `put`, `get`, `contains`, `erase`, `set_order`, `operator[]`, `operator[]=` |
