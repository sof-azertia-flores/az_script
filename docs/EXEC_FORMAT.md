# Exec v10 二进制格式

中文 | [English](EXEC_FORMAT.en.md)

这是执行文件的布局版本，独立于源码 `metadata.version`。可读 AST 保留源码名称，exec JSON 是检查视图；下述固定结构在 ABD 中使用裸 stack，不保存字段名或逐字段类型标签。整数使用 int32、小端四字节；address 使用独立 uint64、小端八字节，空地址为 0；函数 ID 按完整 uint32 位模式解释，布尔为一字节 0/1，字符串为 UTF-8。每个字段仍是标准 ABD frame：int32 非负长度加原始 payload。

文件本身是一个 ABD frame，其 payload 为固定顺序的根 stack：

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

`debug_files` 与 `FunctionDebug` 是可选调试记录，见[调试记录](#调试记录-g)；除非使用 `-g` 编译，否则它们为空或不存在。

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

`if`/`wi`/`select` 的条件，以及 `and`/`or`/`not` 中实际求值的每个操作数，必须为 BOOLEAN_VALUE（4）。数值零/非零与 void 不再表示真假；类型错误时抛出 `Condition must be boolean, got <type>`。`and`/`or` 仍从左到右短路，被跳过的右操作数在运行时不求值也不检查。源码编译还会拒绝静态已知的非布尔操作数，包括降级后的 `for` 条件。

`eq`/`ne` 对混合 int/float/double 做数值比较，对两个 boolean 或两个 string 做值比较，对两个 address 比较 uint64 值。null 和类指针擦除为 address；源码编译另外要求类指针类型兼容。其他混合类型会抛错，不再直接产生不等/相等；address 与非 address 的配对仍走地址专用错误。OBJECT_VALUE（类值和 buffer）没有通用相等运算。运行时的两个 void 值（包括尚未赋值的动态变量）比较时，`eq` 为 true，`ne` 为 false；编译器拒绝静态类型为 void 的操作数。这些检查只收紧 exec v10 的执行语义，不改变二进制布局或 opcode 编号。

`cont` 结束当前最内层循环的本轮执行，各层块先完成对象清理，再由循环消费跳转标记。`for` 在编译期降为块与 `wi`，没有独立 opcode：初始化只执行一次，首次跳过步进，后续轮次先步进再判断条件，循环正文具有独立作用域。因此 `continue` 会执行步进，`break` 和 `return` 不执行步进。

源码 `switch` 没有专用 opcode：先把值保存到带作用域的临时变量，再降为一次性 `wi`，其中各分段是同级 `if`，标签用平衡的 `or` 树组合 `eq` 比较，default 最后处理。只有选中分段会执行。编译器生成的跳转标记使 `break` 仍定位到源码最近的循环/switch，使 `continue` 定位到源码最近的循环，并照常执行块清理。标签比较计入普通指令预算，选择开销为 O(已检查标签数)。wire 格式与 exec v10 版本不变。

`cleanup` 是编译器内部清理指令，检查视图 JSON 的字段为 `v`（正文）、`val`（清理）、`on-error`。先执行正文及其作用域清理；`on_error=true` 时只在失败后执行 finalizer，false 时无论正常结束、跳转或错误都执行。finalizer 不替换正文已有的返回值或跳转；已有错误优先保留，否则传播清理错误。继承构造用它回滚已完成的父类部分，析构用它保证父类随后执行。两个子节点都是普通表达式，类名、继承关系及布局不进入 exec 指令。清理仍受调用和指令预算约束，底层内存释放保持原有兜底保证。不认识新 opcode 的解释器会明确拒绝相应程序。

## 封闭的对象存储

编译器内部 `object_alloc`（`0x0abd0014`，`address(int)`）使用普通 CALL 记录，在构造之前立即标记分配。源码不能通过名称调用它，也不增加 opcode。`oa` 要求已标记的对象存储或活动的字面量/视图地址，并检查边界；普通原始分配不能充当带类型对象。

原始 `mem_get`、`mem_free`、`make_free`、`mem_send_up` 拒绝已标记的起点/内部地址及字面量/视图地址，包括正在构造的对象。错误为 `<name> cannot access object storage; use typed member access`。JNI 原始 put/free 同样拒绝它们。解码器把编译器生成的 `mem_get(oa(...))` 识别为内部带类型成员访问，仍可用于 `m`/`drop` 目标；独立的原始 `mem_get` 不因此获得对象访问能力。

构造完成（`ob`/`register_object`）先登记生命周期状态，再解除手动对象的自动拥有者；登记失败时仍保留清理责任，手动工厂不会对对象存储调用原始 `make_free`。释放/销毁清除对象标记，快照恢复从已验证的登记对象区间重建标记，不增加快照字段或版本。exec 保持 v10。存储标记不编码名义类身份，也不能防止堆地址复用后的旧别名。

## 字面量对象（opcode 32–35）

字面量对象保存在 slot 块中：块由一个存储变量拥有，赋值、传参、返回时按运行时规则移动或复制全部 slots，拥有者的生命周期结束时运行已登记的析构并使地址失效。编译器只在自身降级中产生以下指令，源码不能直接书写：

- `new_block` 创建 `size`（1…1048576）个空 slot 的块，作为当前语句的临时对象值，尚无析构。
- `block_address` 求值为对象值后返回其存储地址，块已到期时报错。地址的最高位为 1，其余位为 `id << 24 | offset`；id 永不复用，因此过期地址永远不会指向后来的对象。`oa`、`ob` 支持这类地址；原始 `mem_get` 拒绝它们，编译器生成的带类型成员访问使用内部成员路径。`ob` 只为块登记析构，`manual` 必须为 false；`od` 与 `mem_free`/`make_free`/`mem_send_up` 不管理块存储。
- `mv` 读取局部或参数槽（非负），把其中拥有的块转为当前帧的临时对象，供工厂向构造转交已复制的实参。
- `drop` 的目标与 `m` 相同（变量或 `mem_get` 调用），立即运行并结束该存储所拥有块的析构，用于析构正文之后销毁本类字面量字段。

运行时存储规则：写入一个已拥有类块的存储时原地逐 slot 赋值，buffer 整体赋值遵守下述替换规则；写入临时块时移动；写入其他块时深复制（嵌套块递归复制，块嵌套最多 64 层）。`r`/`ro` 返回块时，本调用的局部或参数直接移交给调用方帧，其余块复制后交出；函数调用结果与参数实参按同一规则成为调用方的临时对象。每条语句结束、`if`/`while` 条件求值后销毁本帧未绑定的临时对象。

## 泛型操作上下文（opcode 36–39）

`hidden_count` 是函数接收的操作上下文数量，独立于显式参数和变量槽。`entry_kind` 为 0 表示普通入口，1 表示编译器内部入口。检查视图 JSON 使用 `hidden-count`、`entry-kind`，解码时即使为零也会输出。生命周期钩子的隐藏上下文数量必须为零。公开 C++/JNI 调用不提供隐藏上下文，宿主须调用包裹泛型实现的普通非泛型函数；反射也仅接受隐藏上下文为零的普通入口。泛型 extern 须链接到脚本实现，不能由原生或 Java 回调实现。

上下文只描述 ABI 操作，不包含类名、类布局、约束或原生指针。每个上下文使用裸 stack：

```text
Reference = [ 0, index:int ]
Fixed     = [ 1, abi:int, kind:int, width:int,
              has_factory:bool, [factory_id:int], contexts:Stack<Context>,
              has_placement:bool, [placement_id:int], placement_contexts:Stack<Context>,
              has_element:bool, [element:Context] ]
```

检查视图 JSON 的引用为 `{"ref":0}`；固定上下文包含 `abi`、`kind`、`width`、`contexts`、`placement-contexts`，另有可选 `factory`、`placement`、`element`。引用索引须小于所属函数 hidden_count，内嵌引用同样检查。kind 0 对应 scalar ABI 0–5，kind 1 原始 address ABI 7，kind 2 类指针 ABI 7，kind 3 类值 ABI 8，kind 4 buffer ABI 8；ABI 6 不合法。void 仅用于反射返回，不能成为元素或泛型实参。除类值外 width 均为 1；类值为含继承字段的直接 slot 数（1…1048576），不保存字段布局或类名。

只有 kind 3 允许工厂或原地构造入口，各 ID 缺省时对应子列表必须为空。工厂是无显式参数、返回 object 的脚本函数；原地构造是内部 `void(address)` 脚本函数，各自的隐藏数量须匹配绑定的子上下文。两者都可缺省，仅请求缺失的默认构造操作时运行失败。kind 4 必须有一个 element 上下文，width 为 1，无工厂或原地构造入口。建立此上下文或空 buffer 不默认构造元素。

CALL 根据当前帧绑定 `contexts` 后调用目标，上下文数量须与目标隐藏数量相等。`ob` 将已绑定上下文与析构一起捕获，使后续析构不依赖创建帧；没有析构时列表必须为空。`context_abi` 返回绑定的 ABI 编号；`context_default` 产生基础类型零值/空值、空地址、调用工厂创建对象或建立空 buffer。`return_typed` 检查绑定 ABI，并按对象值或类指针分别执行返回及所有权转移，原始 address 保持普通地址返回语义；`check_type` 检查实际值的 ABI 后返回该值。后两者的检查视图字段为 `v`、`context`，前两者为 `context`。

上下文记录及子列表 stack 均计入 128 层物理 ABD 限制，隐藏数量和上下文列表继续受槽数量上限约束。链接除擦除签名外还检查隐藏数量和入口种类，仅重定位明确的工厂/函数引用。绑定后的上下文是不可变运行时数据。

JNI 快照 v9 递归保存对象已绑定的析构上下文，包括 ABI、kind、可选默认工厂 ID 和子上下文，不保存帧引用或原生指针。恢复在替换状态前完整检查有序模块身份、上下文种类和深度、各工厂的签名及隐藏数量、析构的 `void(address)` 签名及隐藏数量；替换不调用旧析构，也不重新初始化。包括 v8 在内的旧快照直接拒绝。

## 缓冲区与比较（opcode 40–42）

`buffer_new` 用已绑定的元素上下文创建空的拥有型 OBJECT_VALUE，检查视图 JSON 字段为 `context`。`buffer_op` 的检查视图字段为 `op`、`v`、`args`；操作 0…6 分别是 length、capacity、reserve、get、set、push、resize，实参数量依次为 0、0、1、1、2、1、1。先借用并求值一次接收者，再从左到右求值并保存实参。get 产生值副本。buffer 不是可由原始地址/调整块大小接口操作的普通类块，这些接口不能绕过其生命周期规则。

每个 buffer 拥有 capacity × stride 的连续 slots。类元素是覆盖 N 个直接 slots 的独立对象视图；其余元素各占一个 slot，嵌套拥有值独立分配。只有成功初始化的元素计入 length。扩容提交移动 slots，不执行用户代码，并使旧视图 ID 失效；缩短及清理逆序销毁。复制保留容量并递归复制拥有值。buffer 赋值先准备候选，再销毁并替换旧值，即使旧清理抛错也完成切换；若回调销毁目标则清理候选并停止。元素 set 保留逐字段赋值的基本异常保证。操作保活存储，拒绝重入修改；销毁请求延迟到操作退出，继续前检测失效。批量工作计入执行预算，错误路径保留已有错误并完成底层释放。

`value_compare` 的检查视图字段为 `context`、`v1`、`v2`，结果恰为 -1/0/1。支持非 void 标量、address 和类指针上下文，拒绝类值/buffer 和非有限浮点数；字符串按无符号 UTF-8 字节比较，正负零相等。两个操作数从左到右各求值一次。类运算符降为普通 CALL，执行文件不保存运算符元数据。

快照 v9 增加 width、原地构造绑定和递归元素上下文。buffer 对象记录另含 `buffer:true`、`element`、`length`、`capacity`，`slots` 仅编码存活的逻辑元素。类元素使用对象视图 ID 及析构/上下文/字段记录，因此底层字段只保存一次，不编码空余容量。恢复先预检容量预算，在临时状态中校验长度/容量/步长、标签、唯一 ID、嵌套和全部函数绑定，用新 ID 重建连续存储与视图并改写已保存的地址，全部通过后才替换旧状态；操作中和过渡状态不能保存。一次快照内全部 buffer 的 capacity × stride 累计不得超过 1,048,576 slots，超出此持久化预算的保存或恢复在分配存储前失败。运行时每块容量和 64 MiB 文件上限仍适用。状态替换不执行旧状态的用户析构。

## 位运算与移位（opcode 43–48）

每条指令严格为 `[opcode:int, left:E, right:E]`：43 `bit_and`、44 `bit_or`、45 `bit_xor`、46 `shl`、47 `shr`、48 `ushr`。检查 JSON 用 `v1`、`v2` 表示操作数。两侧从左到右各求值一次，且必须为 INT_VALUE（0），移位量也不例外；不进行数值或布尔转换。结果同样为 int32。

运算采用 uint32 位模式，再把结果解释为 signed int32。`shl` 丢弃高位并补零，`shr` 进行符号扩展，`ushr` 补零。移位量必须为 0…31（含端点），不做掩码截断；非法数量抛出 `std::runtime_error`（JNI 映射为 `IllegalStateException`），例如 `Shift count must be between 0 and 31, got 32`。移位回绕不触发算术溢出。源码 `~x` 降为 `bit_xor(x, -1)`；复合赋值降为对应二元运算后的普通变量赋值。这些 opcode 扩展 exec v10，不改变版本号、根记录或函数布局；消费方须支持到 opcode 48。

## 显式转换（opcode 49）

记录严格为 `[49, type:int, value:E]`，目标类型为 int=0、string=1、float=2、double=3 或 address=7；读取方拒绝其他目标以及缺失/多余字段。检查 JSON 使用 `type`、`v`。源值只求值一次，再执行转换。静态已知的同类型源码转换只保留源表达式；动态 any 源总是生成此指令，这五种目标在运行时遇到同标签值时均为恒等转换。

数值标签可以转为 int/float/double 或 string。字符串可解析为十进制 int/float/double，或保持 string 恒等。address 只能转为无符号十进制 string 或保持 address 恒等。boolean、void 和 object 标签被拒绝。类指针和 null 在 exec 中擦除为 address，因此源码编译只允许显式的类指针转 address 擦除（也包括指针上界 T），不生成 opcode 49，其他类指针/null 转换仍被拒绝；opcode 49 不能恢复源码的类身份。不支持 int/address 互转或从字符串解析 address。

数值转 int 先向零截断，再检查 int32 范围。float/double 转换拒绝溢出，允许次正规数及下溢为零。解析与区域设置无关：整数字符串只允许可选正负号和十进制数字；浮点字符串可含小数点与指数，不允许空白、十六进制、后缀、NaN/Infinity、嵌入 NUL 或尾部多余文字。错误格式为 `Cannot convert X to Y`，按需附加非法十进制格式或超范围原因；引用的字符串转义为 ASCII，并在 64 个 Unicode 标量后截断。源码转换配对表和示例见语言说明的显式转换一节。显式转换扩展 exec v10，不改变根记录或函数布局。

## 条件选择（opcode 50）

记录严格为 `[50, condition:E, then:E, else:E]`，三个子表达式都必填，缺失或多余字段均被拒绝。检查 JSON 为 `{"t":0,"c":50,"v":condition,"then":thenValue,"else":elseValue}`。调试记录遍历子节点时依次访问 condition、then、else。

`select` 对条件求值一次并要求 boolean，然后只对选中的分支求值一次并返回其值。非布尔条件会报错，例如实际标签为 int 时报告 `Condition must be boolean, got int`。求值使用当前作用域，不采用 opcode 27 `if` 的分支作用域，也不新增清理边界。选中的拥有临时值和自动指针对象继续遵守原有语句/块生命周期。

源码编译器检查两个分支是否具有兼容的非 void 共同类型，并以 opcode 49 `convert` 包装较窄的数值分支。指针兼容性和指针上界类型参数与 null 的配对在编译期检查；`select` 本身不转换选中值，也不恢复已擦除的类类型。exec 保持 v10，根记录和函数记录不变；消费方须支持 opcode 50。

## 返回值检查

脚本函数边界的数值结果必须与声明的返回标签精确匹配，或只从 int（0）扩宽到 float（2）/double（3）、从 float（2）扩宽到 double（3）。int 转 float 可按显式转换的相同规则舍入。double 结果不能满足 int 或 float 返回，float 结果不能满足 int 返回，即使当前值恰好能表示也不例外。手写 exec 和编译脚本中的动态类型结果同样受检查。类型不匹配时抛出 `Function return type mismatch: expected int, got double`，其中类型名对应真实的预期和实际类型。

有意收窄时，必须在返回前显式执行 `convert` 表达式，先完成其截断、范围、舍入和下溢检查，再以转换后的标签匹配声明。原生回调签名仍要求实参/结果标签精确匹配；对象所有权和指针返回规则继续生效。返回检查只改变语义：exec 保持 v10，不增加 opcode 或记录字段。

## 字符串内置函数

字符串操作使用普通 CALL 记录（opcode 2）及下列保留 ID；不增加 extern 签名、新 opcode 或格式版本。

| 名称 | 签名 | 函数 ID |
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

实参标签必须精确匹配 string/int。运行时 `str_index_of` 接受两个或三个参数，缺省 from 为零；源码编译总是补齐第三个参数。字符串方法降为相同 ID，`s[i]` 降为 `str_char_at(s, i)`，`s.contains(n)` 降为 `str_index_of(s, n, 0) >= 0`，没有 contains ID。接收对象和实参按顺序各求值一次。

所有字符串实参都必须是合法 UTF-8，字节长度及空 needle/前缀/后缀也不例外；NUL 合法。下标、长度、子串端点和搜索结果按 Unicode 码点计数，只有 `str_byte_length` 统计字节数。`char_at` 返回含一个码点的字符串，`code_at` 返回其 int 标量值。子串使用 `[begin, end)`，要求 `0 <= begin <= end <= length`；char/code 下标要求 `0 <= index < length`，搜索起点要求 `0 <= from <= length`。空 needle 返回 from，未找到返回 -1，空前缀/后缀匹配。`str_from_code` 拒绝负数、代理项和大于 0x10ffff 的值。越界使用 `std::out_of_range`，非法 UTF-8/标量值使用 `std::invalid_argument`。字符串仍不可变，方法和下标不增加存储或对象 ABI。

## 类型编号与常量

函数签名与 `declared_type` 的类型编号为 int=0、string=1、float=2、double=3、boolean=4、void=5、any=6、address=7、object=8。参数和变量不能为 void；未标注类型的脚本参数/变量使用 any。仅当 `hidden_count > 0` 时允许 any 返回和 any 外部参数，用于泛型擦除；源码 extern 声明仍须明确标注类型。检查视图 JSON 可省略 any 的 `declared-type`。类指针、`C (*)` 参数、隐式 this 以及指针工厂的返回值擦除为 address（`C (*)` 实参为字面量对象时，调用方先用 `block_address` 取地址）；字面量对象的参数、返回、变量类型为 object。地址不会放宽 int 参数的类型检查。这些编号与 ABD 标量标签属于不同的编码层；对象值没有常量表示。

常量的 ABD 标签为 int=`0x3`、string=`0x1`、float=`0xce867`、double=`0xce1066`、boolean=`0xd00`、address=`0xce200b`；void 为 `0xce200a` 标签的单字节 `ff`。浮点常量必须有限，字符串须为有效 UTF-8，布尔编码严格为 0/1。地址 payload 必须恰好 8 字节，完整保留无符号高位；例如最大地址为八个 `ff`。exec JSON 中地址常量表示为 `{"address":"18446744073709551615"}`，空地址为 `{"address":"0"}`，二进制仍使用 opcode 0 加带标签的单元素数组，不添加地址专用 opcode。常量不接受集合或其他 byte array。

`oa` 的基地址、`ob/od/ro` 的对象值、内置内存接口的指针及 `alloc` 的结果要求 address。字段偏移、分配数量、函数 ID、类型号和变量槽号仍为四字节整数。`add/minus` 对 address 和 int32 执行经 uint64 边界检查的偏移运算；比较两个地址时使用无符号顺序，不把地址转成 double 或 int32。普通 `r` 不触发对象所有权转移。

一元正号已在编译期消除，没有独立 opcode。原来所有 `c` 字符串均由表中整数替代；检查视图 JSON 的调用 `t=1` 和块数组表示保留，二进制统一通过 opcode 0/1/2 区分常量、块和调用。

变量编号规则：参数和局部槽为连续非负数，全局为 -1、-2……。每个加载函数保存所属模块的全局偏移，实际下标为 `global_offset + (-int64(slot) - 1)`。文件不保存宿主加载时才知道的 offset。参数、外部函数类型和块级生命周期规则保持不变；返回值遵守上面的检查。

读取器只接受此 v10 裸 stack 格式，通过首个 frame 的 magic 和紧随其后的版本识别。包括 v9、v8 在内的旧执行版本、旧 Map 执行文件和未知版本直接拒绝，错误信息给出期望版本，已有源码或可读 AST 必须重新编译。原有 64 MiB 文件上限、128 层 ABD 嵌套限制、变量槽和调用预算继续适用；紧凑格式不得绕过嵌套检查。JNI 快照版本独立于 exec 版本，仍为 9；JNI 仅接受 v9 快照，拒绝模块不是 exec v10 的快照；地址标量、堆分配起点、对象登记和自动清理顺序均保留完整 uint64 值；长度、数量和函数 ID 仍用既有整数表示。快照把堆和全局中的字面量对象保存为 `{object, has destructor, [destructor], destructor contexts, slots}` 映射，恢复时分配新 id 并改写指向已保存对象的地址；指向未保存对象的地址保持原值，其 id 被永久停用。脚本字节作为不透明数据保存及校验，不再读取旧快照。

物理容器深度从根 stack 的第 1 层开始计算，函数列表第 2 层、函数记录第 3 层、函数体第 4 层。嵌套表达式各加一层，块内语句列表、调用实参列表以及常量的单元素数组各另加一层。扩展元数据从第 2 层开始计算内部 Map/Array 嵌套。调试文件名位于第 3 层，函数调试记录位于第 4 层，其字段位于第 5 层，位置整数位于第 6 层。裸 stack 不会绕过 128 层上限。

## 调试记录（`-g`）

调试信息可选，默认不生成。`compile -g`、`compile-json -g`（Java API 为 `Compiler.compile(tree, true)`）填写 `debug_files`，并为每个函数生成一条调试记录；不加 `-g` 时 `debug_files` 为空，所有 `has_debug` 为 false。调试记录不具有执行语义：执行、链接、重定位、预算和反射均不使用它，`flush()` 不改写任何调试字段，同一函数在有无 `-g` 时的指令完全相同。解释器校验这些记录并随函数保存。

```text
FunctionDebug = [ name:string, file:int, line:int, column:int, locations:Stack<int> ]
```

- `debug_files` 按顺序保存可读 AST `sources` 表中互不相同的名称，即诊断中显示的文件标签：相对所属 include 根目录、以 `/` 分隔的路径，主文件为不含目录的文件名，源码字符串为 `<source>`。各项非空且互不相同，最多 1,048,576 项。
- `name` 是函数的显示名：普通函数为源码名称，方法为 `C::m`，构造和析构为 `C::C`、`C::~C`，运算符为 `C::operator+`（`-`、`*`、`/`、`[]`、`[]=`、`()` 同理），编译器生成的工厂为 `<new C>`、`<scoped C>`、`<value C>` 或 `<placement C>`。
- `file`、`line`、`column` 指向函数声明，编译器生成的类函数指向类声明。`file` 是 `debug_files` 的下标；line 与 column 同为 0（未知）或同为正数。
- `locations` 是由 `(node, file, line, column)` 四元组平铺而成的 int stack。node 按线路字段顺序对 `body` 中的表达式 stack 做先序编号：函数体为 0 号，每个常量、块、调用和 opcode 表达式进入时取下一个编号；上下文及其他字段不编号。node 严格递增且小于函数体的节点数，`file` 是 `debug_files` 的下标，line 与 column 从 1 开始（column 与编译诊断一致，按 UTF-16 码元计数）。没有条目的节点使用最近的有位置祖先节点的位置。编译器在运算符、调用和语句的源码记号处记录位置，不记录常量和变量读取，并省略与最近有位置祖先位置相同的节点。

读取器拒绝以下情况：名称或文件为空、不是合法 UTF-8，或包含 C0、DEL、C1 控制字符、U+2028/U+2029 或双向格式字符（U+061C、U+200E、U+200F、U+202A–U+202E、U+2066–U+2069）；文件重复；`has_debug` 为 true 却没有恰好一条记录；记录字段数不为五；locations 不是完整的四元组；node 未递增或超出函数体；文件下标超出 `debug_files`；位置的行或列小于 1；声明位置的行、列只有一个为 0。编译器把显示名中的此类字符替换为 U+FFFD，因此源码名称不会生成无效记录。

检查视图的根总是包含 `debug-files`（字符串数组，无 `-g` 时为空）。有调试记录的函数另含 `debug`：`{"name":…, "file":…, "line":…, "column":…, "locations":["node:file:line:column", …]}`，每个位置使用规范十进制。

调试记录会把函数名、类名和文件名写入执行文件，且每个位置占 32 字节；不希望发行包携带这些信息时不要使用 `-g`。JNI 快照逐字节比较模块，因此 `-g` 构建保存的快照不能恢复到不加 `-g` 编译的同一程序中，反之亦然。


## 模块链接

根记录恰好十一个字段。`namespace_hint` 为空字符串表示固定 namespace 模块；非空则是大小写敏感的 hint 名称。检查视图 JSON 对应键为 `namespace-hint`（字符串）、`assume-hints`（`{"hint":名称,"namespace":整数}` 数组）。每条 assumption 的 namespace 必须在 1…65535，排除 `0x0abd` 和 `0x0fff`；hint 与 namespace 一一对应，不得重复冲突。hint 名为 ASCII 标识符（字母或下划线开头）；非空 namespace_hint 必须在 assumptions 中包含自身。

hint 模块只定义占位 namespace 0000 下的函数，0/1 为模块初始化/销毁钩子，其余普通函数在装载时迁移。固定 namespace 模块的普通函数不得占用自身 assume 的 namespace。类名及布局不进入指令，签名保持擦除后的基础类型。使用 `-g` 时调试记录携带显示名（可能含类名），但执行和链接都不读取它。

`flush()` 按所属模块的 assume 表重定位 CALL 的函数 ID、ob 的析构 ID 及上下文中的默认工厂和原地构造 ID（含元素上下文），并校验所有 hint 导入对应的函数及签名。普通整数、堆地址和变量槽不重定位。消除 assumption 只发生在完整链接验证成功之后。has_destructor=false 时没有析构 ID 字段；true 时保留完整32位位模式，包括 `0xffffffff`，不再使用 -1 哨兵。析构仍须指向合法的 `void(address)` 脚本函数，生命周期、main 和内置保留 ID 规则继续生效。

旧 exec v9 及更早版本、旧 Map 执行文件和旧 JNI 快照不再读取。所有库和宿主应配套更新。
