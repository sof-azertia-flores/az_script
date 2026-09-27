# AzScript 基本容器库

中文 | [English](CONTAINERS.en.md)

[`containers.azs`](containers.azs) 是独立编译的纯 AzScript 库，声明 `#namespace_hint AZSCRIPT_CONTAINERS`。调用方只包含 [`containers.include.azs`](containers.include.azs)。该头文件使用 `#assume_hint AZSCRIPT_CONTAINERS c071` 和完整类型的 `extern` 声明。`List<T>`、`Stack<T>`、`Queue<T>` 各只有一份函数正文，不同类型实参共用同一低 16 位编号。

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

头文件带有 `AZSCRIPT_CONTAINERS_INCLUDED` include guard。实现文件不要被调用方 include。可执行示例是 [`../examples/containers-regressions.azs`](../examples/containers-regressions.azs)，成功时返回 `0`。

## 编译与装配

```sh
./azscript compile compiler/stdlib/containers.azs -o build/containers.exec.abd \
    --ast build/containers.ast.json --exec-json build/containers.exec.json
./azscript compile compiler/examples/containers-regressions.azs -o build/containers-regressions.exec.abd
./build/native/interpreter/azscript-run build/containers-regressions.exec.abd --insert build/containers.exec.abd
```

发行包在 `stdlib/` 中附带预编译的 `containers.exec.abd` 及配套 JSON。修改源码后在发行包根目录重新编译：

```sh
./compile.sh stdlib/containers.azs
./compile.sh examples/containers-regressions.azs
./run.sh examples/containers-regressions.exec.abd --insert stdlib/containers.exec.abd
```

`c071` 只是编译期假定地址。宿主用 `namespace_for_hint("AZSCRIPT_CONTAINERS")` 或 `namespaceForHint("AZSCRIPT_CONTAINERS")` 取得实际 namespace，再与低 16 位编号组成调用 ID。未装载本库时，链接会报告缺失 `AZSCRIPT_CONTAINERS`。

## 存储

元素放在 `Node<T>` 的 `value` 字段里，节点用 `prev` / `next` 串成双向链表。类型参数不能写入 `mem_get`，所以这里没有连续的 slot 数组。按下标访问会从较近的一端走到目标，插入、删除和两端弹出也会沿链表移动，耗时与长度成正比。`list_sort_int`、`list_sort_double`、`list_sort_address` 是稳定的插入排序，比较次数与长度的平方成正比。

`Node<T>` 出现在头文件里，因为 `List<T>` 的字段类型必须和实现一致。它是库的链接单元，调用方应通过 `List`、`Stack`、`Queue` 使用它。

无界 `T` 不能比较、不能 `new T()`。排序、查找和计数因此写成具体类型的普通函数，反射可以直接调用这些函数。泛型方法带有类型上下文，反射不能直接调用。

类值作为 `T` 时，节点字段会先按该类型默认构造。没有无参构造的类值不能放进这些容器。标量、`address` 和类指针没有这个要求。类指针只保存地址，析构节点不会 `delete` 它指向的对象。

## 所有权

用 `new` 创建容器，用 `delete` 销毁。`List` 的析构会 `delete` 全部节点。`Stack` 和 `Queue` 各自持有一份 `List`，析构时释放它。`clone` 返回新的 `List<T> *`，由调用方 `delete`。

按值复制容器会复制节点指针，两份析构会重复 `delete`。公开用法是指针：

```c
List<int> * xs = new List<int>();
delete xs;
```

## 操作

`push_back` 和 `push_front` 把参数写入新节点。`resize(n)` 在变长时追加默认节点：`int` 为 `0`，`double` 为 `0.0`，`string` 为空串，`boolean` 为 `false`，`address` 和类指针为 `null`，类值调用无参构造。缩短时从尾部删除。`clear` 删掉全部节点。`insert` 的下标可以等于当前长度，表示插到末尾。`reverse` 只改指针。`swap` 交换 `head`、`tail` 和 `count`。

栈是 `push` / `top` / `pop`，对应链表的尾端。队列是 `push` / `front` / `pop`，从尾端进入、从头端离开。

`list_find_*` 找不到时返回 `-1`。`list_count_*` 按 `==` 计数。字符串和布尔没有序比较，所以只有查找和计数。

空容器取端点、弹出、按下标越界、负的 `resize`，以及把 `null` 传给 `swap` 或具体类型的排序、查找、计数，都会触发 `Division by zero`。这是前置条件失败，不是可捕获异常。

## 编号

| 类型 | 编号 | 成员 |
| --- | --- | --- |
| `Node<T>` | `0002`–`0003` | 构造、析构 |
| `List<T>` | `0004`–`0016` | 构造、析构、`size`、`empty`、`push_back`、`push_front`、`pop_back`、`pop_front`、`front`、`back`、`at`、`set`、`insert`、`erase`、`clear`、`resize`、`reverse`、`clone`、`swap` |
| `Stack<T>` | `0017`–`001d` | 构造、析构、`push`、`pop`、`top`、`size`、`empty` |
| `Queue<T>` | `001e`–`0024` | 构造、析构、`push`、`pop`、`front`、`size`、`empty` |
| 普通函数 | `0025`–`0031` | `list_sort_int`、`list_sort_double`、`list_sort_address`，以及 int、double、string、boolean、address 的 `list_find_*` 与 `list_count_*` |
