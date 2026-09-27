# AzScript basic containers

[中文](CONTAINERS.md) | English

[`containers.azs`](containers.azs) is an independently compiled AzScript library with `#namespace_hint AZSCRIPT_CONTAINERS`. Clients include only [`containers.include.azs`](containers.include.azs), which assumes namespace `c071` and declares fully typed `extern` members. `List<T>`, `Stack<T>`, and `Queue<T>` each compile to one body, so every type argument shares the same low 16-bit IDs.

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

Each element lives in a `Node<T>` `value` field. `prev` and `next` form a doubly linked list. A type parameter cannot be stored with `mem_get`, so there is no contiguous slot array. Indexing walks from the nearer end. Insert, erase, and end operations also walk the list, so they take time proportional to the length. `list_sort_int`, `list_sort_double`, and `list_sort_address` are stable insertion sorts and compare a quadratic number of elements.

`Node<T>` is in the header because `List<T>` field types must match the implementation. Treat it as the link cell and use `List`, `Stack`, and `Queue`.

An unbounded `T` cannot be compared and cannot be constructed with `new T()`. Sort, find, and count are therefore ordinary functions of concrete types, and reflection can call those functions. Generic methods carry a type context, so reflection cannot call them directly.

When `T` is a class value, the node field default-constructs it. A class value without a zero-argument constructor cannot be stored. Scalars, `address`, and class pointers have no such requirement. A class pointer is only an address; destroying the node does not `delete` the object.

## Ownership

Create a container with `new` and destroy it with `delete`. The `List` destructor deletes every node. `Stack` and `Queue` each own one `List` and delete it. `clone` returns a new `List<T> *` that the caller deletes.

Copying a container by value copies the node pointers, so both destructors would `delete` the same nodes. Use pointers:

```c
List<int> * xs = new List<int>();
delete xs;
```

## Operations

`push_back` and `push_front` store the argument in a new node. `resize(n)` appends default nodes when growing: `0`, `0.0`, `""`, `false`, or `null` for `int`, `double`, `string`, `boolean`, `address`, and class pointers. Class values use their zero-argument constructor. Shrinking deletes from the tail. `clear` deletes every node. `insert` accepts an index equal to the current length, which appends. `reverse` rewires pointers. `swap` exchanges `head`, `tail`, and `count`.

A stack is `push` / `top` / `pop` at the tail. A queue is `push` / `front` / `pop`, entering at the tail and leaving at the head.

`list_find_*` returns `-1` when the value is absent. `list_count_*` counts with `==`. Strings and booleans have no ordering operators, so they only have find and count.

Reading an end, popping, indexing out of range, passing a negative `resize`, or passing `null` to `swap` or to a concrete sort, find, or count raises `Division by zero`. That is a failed precondition, not a catchable exception.

## IDs

| Type | IDs | Members |
| --- | --- | --- |
| `Node<T>` | `0002`–`0003` | constructor, destructor |
| `List<T>` | `0004`–`0016` | constructor, destructor, `size`, `empty`, `push_back`, `push_front`, `pop_back`, `pop_front`, `front`, `back`, `at`, `set`, `insert`, `erase`, `clear`, `resize`, `reverse`, `clone`, `swap` |
| `Stack<T>` | `0017`–`001d` | constructor, destructor, `push`, `pop`, `top`, `size`, `empty` |
| `Queue<T>` | `001e`–`0024` | constructor, destructor, `push`, `pop`, `front`, `size`, `empty` |
| Functions | `0025`–`0031` | `list_sort_int`, `list_sort_double`, `list_sort_address`, then `list_find_*` and `list_count_*` for int, double, string, boolean, and address |
