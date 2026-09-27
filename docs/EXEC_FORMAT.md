# Exec v8 二进制格式

中文 | [English](EXEC_FORMAT.en.md)

这是执行文件的布局版本，独立于源码 `metadata.version`。可读 AST 保留源码名称，exec JSON 是检查视图；下述固定结构在 ABD 中使用裸 stack，不保存字段名或逐字段类型标签。整数使用 int32、小端四字节；address 使用独立 uint64、小端八字节，空地址为 0；函数 ID 按完整 uint32 位模式解释，布尔为一字节 0/1，字符串为 UTF-8。每个字段仍是标准 ABD frame：int32 非负长度加原始 payload。

文件本身是一个 ABD frame，其 payload 为固定顺序的根 stack：

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

stack 的成员数量由其 frame 边界确定；固定记录必须恰好包含规定字段，拒绝缺失、额外或宽度错误的字段。扩展元数据的键不固定，保留 `AbdMap`。不同类型的常量使用仅含一个值的 `AbdArray`，保留既有 scalar 标签和精确值类型。函数、签名、参数类型表、表达式列表均不使用 `AbdArray`/`AbdMap` 包装。

每个表达式也是裸 stack，第一项固定为 int32 opcode，其后字段如下。`E` 表示表达式 stack，`Es` 表示仅含表达式 stack 的裸 stack。`has_*` 为布尔值，为 false 时对应可选字段必须省略。

| Opcode | 原指令 | 后续字段 |
| --- | --- | --- |
| 0 | constant | `value:AbdArray`，恰好一个 scalar |
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
| 29 | brk | 无 |
| 30 | cont | 无 |
| 31 | cleanup | `body:E, finalizer:E, on_error:bool` |
| 32 | new_block | `size:int` |
| 33 | block_address | `value:E` |
| 34 | mv | `slot:int` |
| 35 | drop | `target:E` |
| 36 | context_abi | `context:Context` |
| 37 | context_default | `context:Context` |
| 38 | return_typed | `value:E, context:Context` |
| 39 | check_type | `value:E, context:Context` |

`cont` 结束当前最内层循环的本轮执行，各层块先完成对象清理，再由循环消费跳转标记。`for` 在编译期降为块与 `wi`，没有独立 opcode：初始化只执行一次，首次跳过步进，后续轮次先步进再判断条件，循环正文具有独立作用域。因此 `continue` 会执行步进，`break` 和 `return` 不执行步进。

`cleanup` 是编译器内部清理指令，debug JSON 的字段为 `v`（正文）、`val`（清理）、`on-error`。先执行正文及其作用域清理；`on_error=true` 时只在失败后执行 finalizer，false 时无论正常结束、跳转或错误都执行。finalizer 不替换正文已有的返回值或跳转；已有错误优先保留，否则传播清理错误。继承构造用它回滚已完成的父类部分，析构用它保证父类随后执行。两个子节点都是普通表达式，类名、继承关系及布局不进入 exec。清理仍受调用和指令预算约束，底层内存释放保持原有兜底保证。不认识新 opcode 的解释器会明确拒绝相应程序。

## 字面量对象（opcode 32–35）

字面量对象保存在 slot 块中：块由一个存储变量拥有，赋值、传参、返回时按运行时规则移动或复制全部 slots，拥有者的生命周期结束时运行已登记的析构并使地址失效。编译器只在自身降级中产生以下指令，源码不能直接书写：

- `new_block` 创建 `size`（1…1048576）个空 slot 的块，作为当前语句的临时对象值，尚无析构。
- `block_address` 求值为对象值后返回其存储地址，块已到期时报错。地址的最高位为 1，其余位为 `id << 24 | offset`；id 永不复用，因此过期地址永远不会指向后来的对象。`oa`、`mem_get` 与 `ob` 均接受这类地址：`ob` 只为块登记析构，`manual` 必须为 false；`od` 与 `mem_free`/`make_free`/`mem_send_up` 不管理块存储。
- `mv` 读取局部或参数槽（非负），把其中拥有的块转为当前帧的临时对象，供工厂向构造转交已复制的实参。
- `drop` 的目标与 `m` 相同（变量或 `mem_get` 调用），立即运行并结束该存储所拥有块的析构，用于析构正文之后销毁本类字面量字段。

运行时存储规则：写入一个已拥有块的存储时原地逐 slot 赋值；写入临时块时移动；写入其他块时深复制（嵌套块递归复制，块嵌套最多 64 层）。`r`/`ro` 返回块时，本调用的局部或参数直接移交给调用方帧，其余块复制后交出；函数调用结果与参数实参按同一规则成为调用方的临时对象。每条语句结束、`if`/`while` 条件求值后销毁本帧未绑定的临时对象。

## 泛型操作上下文（opcode 36–39）

`hidden_count` 是函数接收的操作上下文数量，独立于显式参数和变量槽。`entry_kind` 为 0 表示普通入口，1 表示编译器内部入口。debug JSON 使用 `hidden-count`、`entry-kind`，解码时即使为零也会输出。生命周期钩子的隐藏上下文数量必须为零。公开 C++/JNI 调用不提供隐藏上下文，宿主须调用包裹泛型实现的普通非泛型函数；反射也仅接受隐藏上下文为零的普通入口。泛型 extern 须链接到脚本实现，不能由原生或 Java 回调实现。

上下文只描述 ABI 操作，不包含类名、类布局、约束或原生指针。每个上下文使用裸 stack：

```text
Reference = [ 0, index:int ]
Fixed     = [ 1, abi:int, kind:int, has_factory:bool,
              [factory_id:int], contexts:Stack<Context> ]
```

debug JSON 分别为 `{"ref":0}` 和 `{"abi":8,"kind":3,"factory":id,"contexts":[...]}`。引用索引须小于所属函数的 `hidden_count`；固定上下文的子上下文内嵌引用也遵守此规则。kind 0 对应 scalar ABI 0–5，kind 1 对应原始 address ABI 7，kind 2 对应类指针 ABI 7，kind 3 对应字面量对象 ABI 8；ABI 6 不能作为上下文。void 用于反射返回，不是合法泛型实参。只有 kind 3 可含工厂，没有工厂时子上下文必须为空。工厂返回 object、没有显式参数，隐藏数量须与子上下文数量一致。没有工厂的对象上下文可传递，但请求默认值时报错。

CALL 根据当前帧绑定 `contexts` 后调用目标，上下文数量须与目标隐藏数量相等。`ob` 将已绑定上下文与析构一起捕获，使后续析构不依赖创建帧；没有析构时列表必须为空。`context_abi` 返回绑定的 ABI 编号；`context_default` 产生基础类型零值/空值、空地址或调用工厂创建对象。`return_typed` 检查绑定 ABI，并按对象值或类指针分别执行返回及所有权转移，原始 address 保持普通地址返回语义；`check_type` 检查实际值的 ABI 后返回该值。后两者 debug 字段为 `v`、`context`，前两者为 `context`。

上下文记录及子列表 stack 均计入 128 层物理 ABD 限制，隐藏数量和上下文列表继续受槽数量上限约束。链接除擦除签名外还检查隐藏数量和入口种类，仅重定位明确的工厂/函数引用。绑定后的上下文是不可变运行时数据。

JNI 快照 v8 递归保存对象已绑定的析构上下文，包括 ABI、kind、可选默认工厂 ID 和子上下文，不保存帧引用或原生指针。恢复在替换状态前完整检查有序模块身份、上下文种类和深度、各工厂的签名及隐藏数量、析构的 `void(address)` 签名及隐藏数量；替换不调用旧析构，也不重新初始化。包括 v7 在内的旧快照直接拒绝。

## 类型编号与常量

函数签名与 `declared_type` 的类型编号为 int=0、string=1、float=2、double=3、boolean=4、void=5、any=6、address=7、object=8。参数和变量不能为 void；未标注类型的脚本参数/变量使用 any。仅当 `hidden_count > 0` 时允许 any 返回和 any 外部参数，用于泛型擦除；源码 extern 声明仍须明确标注类型。debug JSON 可省略 any 的 `declared-type`。类指针、`C (*)` 参数、隐式 this 以及指针工厂的返回值擦除为 address（`C (*)` 实参为字面量对象时，调用方先用 `block_address` 取地址）；字面量对象的参数、返回、变量类型为 object。地址不会放宽 int 参数的类型检查。这些编号与 ABD 标量标签属于不同的编码层；对象值没有常量表示。

常量的 ABD 标签为 int=`0x3`、string=`0x1`、float=`0xce867`、double=`0xce1066`、boolean=`0xd00`、address=`0xce200b`；void 为 `0xce200a` 标签的单字节 `ff`。浮点常量必须有限，字符串须为有效 UTF-8，布尔编码严格为 0/1。地址 payload 必须恰好 8 字节，完整保留无符号高位；例如最大地址为八个 `ff`。exec JSON 中地址常量表示为 `{"address":"18446744073709551615"}`，空地址为 `{"address":"0"}`，二进制仍使用 opcode 0 加带标签的单元素数组，不添加地址专用 opcode。常量不接受集合或其他 byte array。

`oa` 的基地址、`ob/od/ro` 的对象值、内置内存接口的指针及 `alloc` 的结果要求 address。字段偏移、分配数量、函数 ID、类型号和变量槽号仍为四字节整数。`add/minus` 对 address 和 int32 执行经 uint64 边界检查的偏移运算；比较两个地址时使用无符号顺序，不把地址转成 double 或 int32。普通 `r` 不触发对象所有权转移。

一元正号已在编译期消除，没有独立 opcode。原来所有 `c` 字符串均由表中整数替代；debug JSON 的调用 `t=1` 和块数组表示保留，二进制统一通过 opcode 0/1/2 区分常量、块和调用。

变量编号规则：参数和局部槽为连续非负数，全局为 -1、-2……。每个加载函数保存所属模块的全局偏移，实际下标为 `global_offset + (-int64(slot) - 1)`。文件不保存宿主加载时才知道的 offset。参数、返回值、外部函数类型和块级生命周期规则保持不变。

读取器只接受此 v8 裸 stack 格式，通过首个 frame 的 magic 和紧随其后的版本识别。包括 v7 在内的旧执行版本、旧 Map 执行文件和未知版本直接拒绝，已有源码或可读 AST 必须重新编译。原有 64 MiB 文件上限、128 层 ABD 嵌套限制、变量槽和调用预算继续适用；紧凑格式不得绕过嵌套检查。JNI 快照仅接受 v8，地址标量、堆分配起点、对象登记和自动清理顺序均保留完整 uint64 值；长度、数量和函数 ID 仍用既有整数表示。快照把堆和全局中的字面量对象保存为 `{object, has destructor, [destructor], destructor contexts, slots}` 映射，恢复时分配新 id 并改写指向已保存对象的地址；指向未保存对象的地址保持原值，其 id 被永久停用。脚本字节作为不透明数据保存及校验，不再读取旧快照。

物理容器深度从根 stack 的第 1 层开始计算，函数列表第 2 层、函数记录第 3 层、函数体第 4 层。嵌套表达式各加一层，块内语句列表、调用实参列表以及常量的单元素数组各另加一层。扩展元数据从第 2 层开始计算内部 Map/Array 嵌套；裸 stack 不会绕过 128 层上限。


## 模块链接

根记录恰好十个字段。`namespace_hint` 为空字符串表示固定 namespace 模块；非空则是大小写敏感的 hint 名称。debug JSON 对应键为 `namespace-hint`（字符串）、`assume-hints`（`{"hint":名称,"namespace":整数}` 数组）。每条 assumption 的 namespace 必须在 1…65535，排除 `0x0abd` 和 `0x0fff`；hint 与 namespace 一一对应，不得重复冲突。hint 名为 ASCII 标识符（字母或下划线开头）；非空 namespace_hint 必须在 assumptions 中包含自身。

hint 模块只定义占位 namespace 0000 下的函数，0/1 为模块初始化/销毁钩子，其余普通函数在装载时迁移。固定 namespace 模块的普通函数不得占用自身 assume 的 namespace。类名及布局不进入本格式，签名保持擦除后的基础类型。

`flush()` 按所属模块的 assume 表重定位 CALL 的函数 ID、ob 的析构 ID 及上下文中的默认工厂 ID，并校验所有 hint 导入对应的函数及签名。普通整数、堆地址和变量槽不重定位。消除 assumption 只发生在完整链接验证成功之后。has_destructor=false 时没有析构 ID 字段；true 时保留完整32位位模式，包括 `0xffffffff`，不再使用 -1 哨兵。析构仍须指向合法的 `void(address)` 脚本函数，生命周期、main 和内置保留 ID 规则继续生效。

旧 exec v7 及更早版本、旧 Map 执行文件和旧 JNI 快照不再读取。所有库和宿主应配套更新。
