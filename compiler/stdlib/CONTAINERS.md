# AzScript 基本容器库

中文 | [English](CONTAINERS.en.md)

[`containers.azs`](containers.azs) 是独立编译的纯 AzScript 库，声明 `#namespace_hint AZSCRIPT_CONTAINERS`。调用方只包含 [`containers.include.azs`](containers.include.azs)。该头文件使用 `#assume_hint AZSCRIPT_CONTAINERS c071` 和完整类型的 `extern` 声明。`List<T>`、`Stack<T>`、`Queue<T>`、`Vector<T>`、`Set<T>`、`Map<K, V>` 各只有一份函数正文，不同类型实参共用同一低 16 位编号。链表负责两端 O(1)；`Vector`、`Set` 和 `Map` 使用连续 `buffer`。

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

`List`、`Stack`、`Queue` 把元素放在 `Node<T>` 的 `value` 字段里，节点用 `prev` / `next` 串成双向链表。按下标访问会从较近的一端走到目标。中间插入和删除也要沿链表移动，耗时与长度成正比。两端的插入和弹出是 O(1)，所以队列从头部离开仍是 O(1)。`list_sort_int`、`list_sort_double`、`list_sort_address` 是稳定的插入排序，比较次数与长度的平方成正比。

`Vector<T>`、`Set<T>` 和 `Map<K, V>` 把元素放在拥有型 `buffer` 的连续 slot 里。`Vector` 按下标读写是 O(1)。`Set` 和 `Map` 是有序平坦表，二分查找，插入和删除要搬移后面的元素，耗时与长度成正比。没有通用哈希，所以这不是哈希表，也不把 `buffer` 放进 `mem_get`。

`Node<T>` 和 `Entry<K, V>` 出现在头文件里，因为字段类型必须和实现一致。它们是库的链接单元。调用方通过 `List`、`Stack`、`Queue`、`Vector`、`Set`、`Map` 使用它们。

无界 `T` 不能 `new T()`，也不能直接写 `<` 或 `==`。链表的排序、查找和计数因此仍是具体类型的普通函数，反射可以直接调用这些函数。泛型方法带有类型上下文，反射不能直接调用。

`Set` 和 `Map` 在比较器编号为 `0` 时调用 `value_compare<T>`。它支持 `int`、`float`、`double`、`string`（无符号 UTF-8 字节序，含内嵌零字节）、`boolean`（`false` < `true`）、`address` 和类指针，返回 `-1`、`0` 或 `1`。具体类值和 `buffer` 没有默认比较。泛型正文可以写 `value_compare`，实际类型不支持时运行失败，报告 `This type requires a custom comparison function`。类值键要先 `set_order`，传入普通比较函数的完整运行时函数 ID。比较器必须是无隐藏上下文的普通函数，不能是泛型方法或闭包。整数不做 assume 重定位。

类值作为链表节点字段，或作为 `Entry` 的 `key` / `value` 时，会先按该类型默认构造。没有无参构造的类值不能放进 `List`、`Stack`、`Queue`，也不能作为 `Map` 的键或值。`Vector` 和 `Set` 的 `push_back` / `insert` 写入已经构造好的值，不要求无参构造；`Vector::resize` 变长时仍要默认构造。标量、`address` 和类指针没有这个要求。类指针只保存地址，析构容器不会 `delete` 它指向的对象。

## 所有权

用 `new` 创建容器，用 `delete` 销毁。`List` 的析构会 `delete` 全部节点。某个元素的析构失败时，尚未释放的节点仍会继续 `delete`，并保留最先出现的那个错误；连续失败的次数受调用深度预算限制。`Stack` 和 `Queue` 各自持有一份 `List`，析构时释放它。`clone` 返回新的 `List<T> *`，由调用方 `delete`。

按值复制 `List`、`Stack`、`Queue` 会复制节点指针，两份析构会重复 `delete`。公开用法是指针：

```c
List<int> * xs = new List<int>();
delete xs;
```

按值复制 `Vector`、`Set`、`Map` 会深复制元素，两份容器各自拥有存储。下标、`at` 和 `get` 返回类值时得到副本，不能写 `xs[i].field = ...`。先改局部副本，再 `set` 或 `operator[]=` 写回。`Vector`、`Set`、`Map` 同样用 `new` 创建、用 `delete` 销毁；析构会销毁 `buffer` 里的元素。

## 操作

`push_back` 和 `push_front` 把参数写入新节点。`resize(n)` 在变长时追加默认节点：`int` 为 `0`，`double` 为 `0.0`，`string` 为空串，`boolean` 为 `false`，`address` 和类指针为 `null`，类值调用无参构造。缩短时从尾部删除。`clear` 删掉全部节点。`insert` 的下标可以等于当前长度，表示插到末尾。`reverse` 只改指针。`swap` 交换 `head`、`tail` 和 `count`。

栈是 `push` / `top` / `pop`，对应链表的尾端。队列是 `push` / `front` / `pop`，从尾端进入、从头端离开。

`list_find_*` 找不到时返回 `-1`。`list_count_*` 按 `==` 计数。字符串和布尔没有序比较运算符，所以链表只有查找和计数。`Set<string>` 和 `Set<boolean>` 可以排序，因为它们走 `value_compare`。

### Vector

```c
Vector<int> * xs = new Vector<int>();
xs.push_back(3);
xs[0] = 1;
int first = xs[0];
delete xs;
```

容量不够时先 `reserve`：空则为 4，否则翻倍；翻倍会溢出 int32 时改为恰好需要的长度。公开的 `reserve(n)` 只扩到 `n`，不翻倍。`push_back` 自己扩容。中间 `insert` 不调用会默认构造的增长 `resize`：先保证容量，再把末尾元素的副本接到后面，向后挪，最后写入新值。`erase` 向前挪之后缩短。`clear` 把长度变成 0，不缩小容量。`operator[]` 和 `operator[]=` 就是 `at` 和 `set`。

### Set 与 Map

相等元素：`Set::insert` 保留原来的值，`Map::put` 替换值。`Set::erase` 和 `Map::erase` 找不到时什么也不做。`Map::get` 和 `operator[]` 读缺失键时报 `Division by zero`；`operator[]=` 写缺失键则插入。`at` 按下标取已排序元素。

`set_order(int id)` 保存比较器的完整函数 ID。容器非空时按新顺序做稳定插入排序：只有比较结果大于 0 才移动，相等元素保持原来的相对顺序。`id` 为 `0` 时回到 `value_compare`。

空容器取端点、弹出、按下标越界、负的 `resize` 或 `reserve`，以及把 `null` 传给 `swap` 或具体类型的排序、查找、计数，都会触发 `Division by zero`。这是前置条件失败，不是可捕获异常。

## 编号

| 类型 | 编号 | 成员 |
| --- | --- | --- |
| `Node<T>` | `0002`–`0003` | 构造、析构 |
| `List<T>` | `0004`–`0016` | 构造、析构、`size`、`empty`、`push_back`、`push_front`、`pop_back`、`pop_front`、`front`、`back`、`at`、`set`、`insert`、`erase`、`clear`、`resize`、`reverse`、`clone`、`swap` |
| `Stack<T>` | `0017`–`001d` | 构造、析构、`push`、`pop`、`top`、`size`、`empty` |
| `Queue<T>` | `001e`–`0024` | 构造、析构、`push`、`pop`、`front`、`size`、`empty` |
| 普通函数 | `0025`–`0031` | `list_sort_int`、`list_sort_double`、`list_sort_address`，以及 int、double、string、boolean、address 的 `list_find_*` 与 `list_count_*` |
| `Entry<K, V>` | `0038`–`0039` | 构造、析构 |
| `Vector<T>` | `0040`–`0051` | 构造、析构、`size`、`empty`、`capacity`、`reserve`、`push_back`、`pop_back`、`resize`、`clear`、`at`、`set`、`front`、`back`、`insert`、`erase`、`operator[]`、`operator[]=` |
| `Set<T>` | `0052`–`005a` | 构造、析构、`size`、`empty`、`insert`、`erase`、`contains`、`at`、`set_order` |
| `Map<K, V>` | `005b`–`0065` | 构造、析构、`size`、`empty`、`put`、`get`、`contains`、`erase`、`set_order`、`operator[]`、`operator[]=` |
