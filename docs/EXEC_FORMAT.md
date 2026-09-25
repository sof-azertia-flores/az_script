# Exec v6 二进制格式

这是执行文件的布局版本，独立于源码 `metadata.version`。可读 AST 保留源码名称，exec JSON 是检查视图；下述固定结构在 ABD 中使用裸 stack，不保存字段名或逐字段类型标签。整数使用 int32、小端四字节；address 使用独立 uint64、小端八字节，空地址为 0；函数 ID 按完整 uint32 位模式解释，布尔为一字节 0/1，字符串为 UTF-8。每个字段仍是标准 ABD frame：int32 非负长度加原始 payload。

文件本身是一个 ABD frame，其 payload 为固定顺序的根 stack：

```text
[ magic="AZSCRIPT", exec_version=6, source_version:int, author:string,
  global_count:int, extensions:AbdMap,
  external_signatures:Stack<Signature>, functions:Stack<Function>,
  namespace_hint:string, assumptions:Stack<Assumption> ]

Assumption = [ hint:string, namespace:int ]

Signature = [ function_id:int, return_type:int, parameter_types:Stack<int> ]
Function  = [ function_id:int, return_type:int, parameter_count:int,
              local_count:int, parameter_types:Stack<int>, body:Expression ]
```

stack 的成员数量由其 frame 边界确定；固定记录必须恰好包含规定字段，拒绝缺失、额外或宽度错误的字段。扩展元数据的键不固定，保留 `AbdMap`。不同类型的常量使用仅含一个值的 `AbdArray`，保留既有 scalar 标签和精确值类型。函数、签名、参数类型表、表达式列表均不使用 `AbdArray`/`AbdMap` 包装。

每个表达式也是裸 stack，第一项固定为 int32 opcode，其后字段如下。`E` 表示表达式 stack，`Es` 表示仅含表达式 stack 的裸 stack。`has_*` 为布尔值，为 false 时对应可选字段必须省略。

| Opcode | 原指令 | 后续字段 |
| --- | --- | --- |
| 0 | constant | `value:AbdArray`，恰好一个 scalar |
| 1 | block | `statements:Es` |
| 2 | call | `function_id:int, arguments:Es` |
| 3 | v | `slot:int` |
| 4 | vd | `slot:int, declared_type:int, has_initializer:bool, [initializer:E]` |
| 5 | vs | `slot:int, value:E` |
| 6 | m | `target:E, value:E` |
| 7 | r | `has_value:bool, [value:E]` |
| 8 | ro | `value:E` |
| 9 | oa | `base:E, offset:int` |
| 10 | ob | `address:E, has_destructor:bool, [destructor_id:int], manual:bool` |
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

`cont` 结束当前最内层循环的本轮执行，各层块先完成对象清理，再由循环消费跳转标记。`for` 在编译期降为块与 `wi`，没有独立 opcode：初始化只执行一次，首次跳过步进，后续轮次先步进再判断条件，循环正文具有独立作用域。因此 `continue` 会执行步进，`break` 和 `return` 不执行步进。

`cleanup` 是编译器内部清理指令，debug JSON 的字段为 `v`（正文）、`val`（清理）、`on-error`。先执行正文及其作用域清理；`on_error=true` 时只在失败后执行 finalizer，false 时无论正常结束、跳转或错误都执行。finalizer 不替换正文已有的返回值或跳转；已有错误优先保留，否则传播清理错误。继承构造用它回滚已完成的父类部分，析构用它保证父类随后执行。两个子节点都是普通表达式，类名、继承关系及布局不进入 exec。清理仍受调用和指令预算约束，底层内存释放保持原有兜底保证。不认识新 opcode 的解释器会明确拒绝相应程序。

函数签名与 `declared_type` 的类型编号为 int=0、string=1、float=2、double=3、boolean=4、void=5、any=6、address=7。参数和变量不能为 void，返回类型不能为 any；未标注类型的脚本参数/变量使用 any，外部函数参数必须有明确类型。debug JSON 可省略 any 的 `declared-type`。类引用、隐式 this 和构造工厂返回值擦除为 address，不再使用 int；地址不会放宽 int 参数的类型检查。这些编号与 ABD 标量标签属于不同的编码层。

常量的 ABD 标签为 int=`0x3`、string=`0x1`、float=`0xce867`、double=`0xce1066`、boolean=`0xd00`、address=`0xce200b`；void 为 `0xce200a` 标签的单字节 `ff`。浮点常量必须有限，字符串须为有效 UTF-8，布尔编码严格为 0/1。地址 payload 必须恰好 8 字节，完整保留无符号高位；例如最大地址为八个 `ff`。exec JSON 中地址常量表示为 `{"address":"18446744073709551615"}`，空地址为 `{"address":"0"}`，二进制仍使用 opcode 0 加带标签的单元素数组，不添加地址专用 opcode。常量不接受集合或其他 byte array。

`oa` 的基地址、`ob/od/ro` 的对象值、内置内存接口的指针及 `alloc` 的结果要求 address。字段偏移、分配数量、函数 ID、类型号和变量槽号仍为四字节整数。`add/minus` 对 address 和 int32 执行经 uint64 边界检查的偏移运算；比较两个地址时使用无符号顺序，不把地址转成 double 或 int32。普通 `r` 不触发对象所有权转移。

一元正号已在编译期消除，没有独立 opcode。原来所有 `c` 字符串均由表中整数替代；debug JSON 的调用 `t=1` 和块数组表示保留，二进制统一通过 opcode 0/1/2 区分常量、块和调用。

变量编号规则：参数和局部槽为连续非负数，全局为 -1、-2……。每个加载函数保存所属模块的全局偏移，实际下标为 `global_offset + (-int64(slot) - 1)`。文件不保存宿主加载时才知道的 offset。参数、返回值、外部函数类型和块级生命周期规则保持不变。

读取器只接受此 v6 裸 stack 格式，通过首个 frame 的 magic 和紧随其后的版本识别。包括 v5 在内的旧执行版本、旧 Map 执行文件和未知版本直接拒绝，已有源码或可读 AST 必须重新编译。原有 64 MiB 文件上限、128 层 ABD 嵌套限制、变量槽和调用预算继续适用；紧凑格式不得绕过嵌套检查。JNI 快照仅接受 v6，地址标量、堆分配起点、对象登记和自动清理顺序均保留完整 uint64 值；长度、数量和函数 ID 仍用既有整数表示。脚本字节作为不透明数据保存及校验，不再读取旧快照。

物理容器深度从根 stack 的第 1 层开始计算，函数列表第 2 层、函数记录第 3 层、函数体第 4 层。嵌套表达式各加一层，块内语句列表、调用实参列表以及常量的单元素数组各另加一层。扩展元数据从第 2 层开始计算内部 Map/Array 嵌套；裸 stack 不会绕过 128 层上限。


## 模块链接

根记录恰好十个字段。`namespace_hint` 为空字符串表示固定 namespace 模块；非空则是大小写敏感的 hint 名称。debug JSON 对应键为 `namespace-hint`（字符串）、`assume-hints`（`{"hint":名称,"namespace":整数}` 数组）。每条 assumption 的 namespace 必须在 1…65535，排除 `0x0abd` 和 `0x0fff`；hint 与 namespace 一一对应，不得重复冲突。hint 名为 ASCII 标识符（字母或下划线开头）；非空 namespace_hint 必须在 assumptions 中包含自身。

hint 模块只定义占位 namespace 0000 下的函数，0/1 为模块初始化/销毁钩子，其余普通函数在装载时迁移。固定 namespace 模块的普通函数不得占用自身 assume 的 namespace。类名及布局不进入本格式，签名保持擦除后的基础类型。

`flush()` 按所属模块的 assume 表重定位 CALL 的函数 ID 和 ob 的析构 ID，并校验所有 hint 导入对应的函数及签名。普通整数、堆地址和变量槽不重定位。消除 assumption 只发生在完整链接验证成功之后。has_destructor=false 时没有析构 ID 字段；true 时保留完整32位位模式，包括 `0xffffffff`，不再使用 -1 哨兵。析构仍须指向合法的 `void(address)` 普通脚本函数，生命周期、main 和内置保留 ID 规则继续生效。

旧 exec v5 及更早版本、旧 Map 执行文件和旧 JNI 快照不再读取。所有库和宿主应配套更新。
