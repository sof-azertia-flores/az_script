# R06 稳定存储身份设计审查

状态：R06 的独立身份池和 workspace 计费原语已实现；尚未迁移生产身份，后续需要与 R07–R10 的帧、工作区和清理契约共同定稿。日期：2026-10-17（Asia/Singapore）。

## 1. 现有身份不是一根 owner 指针

| 现有字段/索引 | 当前含义 | 对应的新身份 |
| --- | --- | --- |
| `slot_block::owner` | 唯一负责这个类值生命周期的存储变量；变量复制不会复制这一责任 | `SlotId` |
| `slot_block::owner_env` | 局部类值所在的清理序列；与自动堆对象交错析构 | `ScopeId` |
| `slot_block::temp_frame` | 无存储所有者的对象临时量，登记到某个函数帧的语句水位后 | `FrameId` + 临时登记位置 |
| `variable::container` | 字段所属的外层对象，用于嵌套深度、祖先关系、销毁期间保护 | 独立对象身份/对象句柄；不能简单替换成 `ScopeId` |
| `heap::owners[address]` | 自动分配的清理责任归属，决定是否允许 free/send_up/返回提升 | `ScopeId` |
| `frame->slots[index] == nullptr` | 当前词法作用域中未声明；不等于已声明的 void | `SlotIndex` 的未声明哨兵或独立声明状态 |
| `environment::parent` / `caller` | 词法父级与动态调用者，具有不同权限和深度语义 | 分开的 `ScopeId` 链 |

`blocks.cpp` 中的 `materialize`、`export_result`、`move_out`、`store`、`release_owned` 会组合以上字段。只替换 `owner` 会留下失效的清理登记或把借用误判成返回转移。

目前共享所有权外壳保证 `variable`、`environment`、`function_frame` 的物理地址稳定；buffer 扩容则显式迁移 payload、修复 owner，并使旧 class view 地址失效。新池既要允许扩容，也必须继续执行这些语言生命周期规则。

## 2. 建议的最小契约

### ID 与定位

定义不同的强类型 `SlotId`、`FrameId`、`ScopeId`；零值无效，类型间不允许隐式转换。ID 必须包含存储域以及可回收槽的代数，或由一个唯一的全局身份域发放。不能让两个独立池的 `{index=0, generation=1}` 被当成同一个身份。

建议第一版保留显式的 64 位 `ArenaId`，其内部 ID 使用 32 位索引和 32 位 generation。跨回调保留的完整 ID 是 16 字节；同一帧内已验证的局部索引可用 32 位 `SlotIndex`，不需要每条算术指令查询全局注册表。ArenaId 不复用；generation 达到最大值时永远退役该条目，不能回绕。

16 字节不是必须永久保持的 ABI，ID 是内部表示。只有证明单一身份域以及跨脚本调用约束后，才压缩为 8 字节；不能为了紧凑而依赖“调用方总是传对池”。

`try_resolve(id) noexcept` 仅在域、索引、代数和存活状态均有效时返回记录；`resolve(id)` 是需要错误报告的入口。热循环从当前已验证帧的索引表定位值。所有宿主、continuation、析构和回调恢复边界重新验证持久 ID。

### 分段存储

池使用固定大小页，例如每页 256 个记录，页目录可以增长，记录页不移动。记录回收使用页内/记录内 free-list 链；`retire` 不得为了扩充 free-list 再分配内存。

槽记录至少保存 `Value`、generation、存活/声明状态和对象 container 关联。逻辑局部槽编号到池槽的映射属于 Frame；作用域声明列表属于 Scope，避免在每个纯标量 Slot 重复保存完整 ScopeId。

临时寄存器通常只需要 `Value`，不需要稳定 SlotId。只有变量、对象字段、宿主可写引用及拥有类值生命周期的存储需要 Slot。不要把每个表达式结果重新包装为有身份的堆对象。

同一个局部编号在下一次循环体声明时是新的槽生命周期，必须取得不同的完整 SlotId；函数递归复用同样的局部编号也不能共享 SlotId。scope 结束时先完成清理及回调观察，再解绑本次声明。若求值寄存器仍持有内部别名，最后一个别名释放时才退役物理槽，期间重新声明使用其他 SlotId。

R08 已发现必须保留别名的合法手写 wire：`if(true, select(eq(vd(slot0,5),void), v(slot0),0))`。非 block 的 if body 创建作用域，条件声明 slot0，select 返回其引用；body 退出后旧执行器的 shared_ptr 仍允许外层 `add(...,2)` 得到 7。执行器采用有界 Cell 数组（局部绑定和寄存器引用计数）保存内部别名；通用池不增加 pin 接口，也不允许已 retired 的 ID 再次 resolve。宿主可写句柄的声明寿命和内部求值别名是不同契约，后续边界适配器不能将两者混同。

### 帧和作用域

Frame 至少持有函数身份、PC、槽映射/寄存器区、返回目的地、全局 offset/count、泛型上下文、根作用域、当前作用域、临时量水位、trace 位置和控制转移状态。

Scope 独立持有 lexical parent、dynamic caller、FrameId、closing 状态、声明槽列表和有顺序的 owned allocation/object 登记。函数根的 lexical parent 仍是所属脚本的全局作用域；不能指向调用者局部作用域。`mem_send_up` 和 raw free 的权限链保留当前“有 caller 先走 caller，否则走 parent”的规则。`getDepth` 保留词法 parent 计数，不能替换为调用栈高度。

Frame 的生命周期包含清理 continuation，不在执行 RETURN 指令时立即结束。析构和宿主回调期间，closing Scope 的局部变量仍能被合法观察；closing 表示不能接受新的所有权转入，不表示已经失效。

### 宿主持有的引用

宿主值快照拥有自己的 Value；可写 `SlotRef` 则解析实际 SlotId。作用域结束、对象字段销毁或快照替换后，旧 SlotRef 必须明确失效，不得落到复用位置的新值。

这是 C++ API 的可观察契约，应与 R04/R05 的 ABI 迁移一并说明。不要默默把目前可保留的 `shared_ptr<variable>` 改成指向池内存但不验证 generation 的别名指针。过渡适配器可以同时保存 ID 和存储域弱引用；每次读写都解析，不能缓存跨回调的 `Slot*`。

## 3. 实施切分与依赖

1. **R06a：独立池基础。** 定义 ID、页面、声明/退役、异常安全的 reservation/commit 以及布局报告。只依赖 R05 Value，不接入生产 owner。测扩容、复用、域隔离、代数耗尽和分配失败回滚。
2. **R06b：Scope/Frame 身份桥。** 旧 environment/function_frame 持有对应 ID。先迁移 `heap::owners` 和 `slot_block::owner_env`，再迁移 `temp_frame`；注册表只保存身份而非裸地址。保持现有清理向量的顺序。此阶段允许继续使用旧帧对象执行脚本。
3. **R06c：Slot 身份桥。** `variable` 持有稳定 SlotId，类值 `owner` 比较改成 SlotId；复制构造产生新存储身份，赋值保留目标身份，payload swap 不交换身份或 container。对象字段和 buffer view 的存储迁移必须同步更新 owner。
4. **R06d：真正的分段槽存储。** Frame 使用局部编号到池槽的映射；宿主适配器仅在边界创建。不能在旧 variable 中保存一份 Value、池中再保存一份 Value。明确唯一实际存储以后才让 VM 寄存器执行器引用这些槽。
5. **R06e：原子替换和清理审计。** 快照先在独立候选域完成解析和验证，提交后才退役旧域；失败保持旧 ID 可用。旧快照析构器不运行这一行为维持。检查构造失败、返回、scope 退出、宿主重入及资源耗尽。

R06b/c 的过渡 ID 仅用于消除地址身份依赖，不能作为“函数帧已改成连续 VM 帧”或“局部存储不再逐变量分配”的完成证据。R06d 需要与 R09 的帧创建、R10 的清理状态机共享接口，避免两套不同生命周期。

在 R06a 前应由新 VM 主实现确认四项：Arena 的归属与寿命；同一执行会话跨脚本重入如何共享 workspace；寄存器/有身份槽的分界；closing 和 retired 两个状态何时切换。未确认前，独立池测试可以推进，生产身份不宜切换。

## 4. 必须保留的生命周期反例

| 场景 | 容易引入的错误 | 必须验证的结果 |
| --- | --- | --- |
| 循环每轮声明同一 local index，宿主保留前一轮 SlotRef | 仅用 frame + local index 作为身份 | 旧引用失效，新一轮不受旧引用写入影响 |
| if 的非 block body 在表达式内声明局部并返回其别名 | scope 退出立即回收被外层表达式引用的槽 | 解绑局部编号；寄存器别名仍能读到 5，外层加 2 得到 7；最后别名释放后退役 |
| 深递归或宿主重入使页目录、Frame 容器扩容 | 保存 vector 元素裸指针跨调用 | 父帧槽、return destination 和临时量登记仍指向原记录 |
| 同一个宿主回调重新进入脚本的另一根调用 | 把动态 caller 一律设为正在执行的帧 | 不能释放另一个根调用自动拥有的 raw allocation；现有权限失败保持 |
| 返回本帧的类值 local/parameter | 先退役槽，再转移对象 owner/temp_frame | 对象只移动一次，调用者语句结束才析构，不增加拷贝 |
| 返回外层借用的对象 | “返回值总是转移所有权” | 外层所有权保留；类值需要按当前规则复制，自动指针借用不被提升 |
| 析构期间宿主访问正在清理的局部变量 | closing 时立刻使整个 Scope 不可读 | 必要的清理观察仍有效；所有析构完成后才退役声明槽 |
| buffer reserve 迁移 class view 字段 | 为保持 SlotId 稳定而错误地保持旧脚本 view 地址有效 | 原 view 地址失效；新 view 继承方法表/析构上下文；内部 owner 指向新字段槽 |
| buffer 赋值析构旧元素时，回调销毁外层 owner | 仅在操作开始验证 target SlotId | 回调后重新验证 target/祖先；安全回收准备好的 replacement，报告 owner ended |
| 临时对象转入局部或调用者 | 从 temporaries 中 erase，导致语句 mark 下标变化 | 原登记置空或使用稳定序号；后续临时量都按正确水位清理 |
| 首个异常之后析构再失败，或 workspace 已耗尽 | 回收路径需要新向量/新异常列表才能继续 | 首错/trace 保留；无需用户代码的存储回收继续；无预算赠送 |
| 成功与失败的 snapshot restore | 提前改变当前域/generation 或保留旧域引用可写 | 失败不改变活动身份；成功一次提交，旧可写句柄明确失效 |
| 分配新作用域/清理登记失败 | 先发布 owner，再申请清理向量空间 | 老状态保持，候选值回收；不存在有 owner 却无清理责任的对象 |
| `mem_get` 写入目标先求值，RHS 释放该堆分配后才写回 | 把旧 shared_ptr 目标改成只保存可复用的脚本 address | 不能写到复用地址的新分配；具体宿主/堆别名保留语义在接入时与基线逐例对照 |

现有可复用回归：`runtime_tests.cpp` 的 buffer reserve/owner-ended 组、numeric 局部 scope 组、自动分配权限组、literal local return 组；真实 JNI 的 buffer/snapshot、类值与虚函数恢复用例。新增池单测只能验证身份机制，不能替代这些语言层差分。

## 5. 布局与 workspace 验收

独立原语测试当前测得 `sizeof(Value)=24`，SlotPool Cell 为 40 字节，三种 ID 各 16 字节。完整 1,048,576 槽池加目录的 backing capacity 为 41,975,808 字节；这一数字不包含执行器寄存器、局部映射、Frame、Scope、清理记录或重入，因此不能作为完整 VM 默认 64 MiB 已足够的证据。后续布局报告还必须输出这些额外记录和向量元素的实际大小。

workspace 记实际 backing capacity：

`槽页 + 页目录 capacity + Frame/Scope 页 + local 索引 capacity + registers capacity + temporaries/cleanup/continuation capacity + 暂停的重入工作区`。

页内空位和闲置缓存页同样占用额度；分配前按实际增长量检查溢出和预算，发布前预留清理登记。不能只按 active slots 计费。降低预算或开始新会话时如何处理已保留缓存页也要固定规则，不能借缓存绕过上限。

合法函数头允许参数数加局部数达到 1,048,576。测试应至少覆盖：只声明/读取最后一槽；完整 32k locals；多帧与回调累计；大参数快照；进入作用域后预算不足以及清理仍能完成。大头部但稀疏声明不应强制构造一百万个共享所有权 variable。64 MiB 只是候选默认值，必须测得所有布局和真实峰值后再定稿。

持久对象/buffer payload 与执行 workspace 的归属需要分开记账；不能把这次执行结束后仍然由宿主持有的对象页误回收，也不能把暂停帧挪到“对象内存”分类而逃避 workspace 限额。

## 6. 当前原语实现范围

`interpreter/vm/storage.h/.cpp` 提供强类型 ID、分段通用池、`SlotPool<Value>`、可用于实际帧/作用域记录的 `FramePool<T>` / `ScopePool<T>`，以及共享 WorkspaceBudget。池在页面和页目录增长前预留完整额度；目录替换时旧数组与新数组并存的瞬时空间也计费。分配/构造失败恢复原 size、capacity、预算和身份序列。回收 free-list 存于记录本身，不分配新内存；代数耗尽后永久退役该索引。

`tests/vm_storage_tests.cpp` 使用可注入失败的 memory_resource 检查这些性质，同时实际填满 32k 和合法最大槽范围。它没有调用脚本析构，没有切换旧环境/对象的 owner 字段；这些仍是生产身份迁移的未完成工作。

## 7. R09 帧与调用准备接口

`workspace.h` 提供按实际 backing capacity 计费的 `WorkspaceVector<T>`；共享寄存器、local 索引、声明列表和 scope mark 用 offset 表示范围，不能在增长后继续使用旧指针。元素移动和销毁必须 noexcept。增长同时计旧、新数组空间；退栈 truncate 不分配，也不把仍保留的 capacity 从预算中扣除。

`frames.h/.cpp` 提供 FrameLayout、FrameRecord、FrameStack 和 CallDepthAccounting。FrameStack 使用分段 FramePool 保持记录地址稳定，另用已计费的 ID 数组保存调用顺序。FrameRecord 保存 caller FrameId、返回寄存器、PC、作用域身份、各工作区范围、控制流状态和结果/源码位置。Frame 的 cleanup 完成后才 pop；pop 不是执行析构和用户清理的替代品。

调用计数与物理脚本帧数量分开。旧执行器的每次脚本、checked host、builtin 调用占一层；flush/destroy 外层还有一个不对应脚本帧的 guard，因此 onload 至少从 active_calls=2 执行。顶层 script::invoke 没有这样的额外 guard。只有 active_calls 从 0 进入时才把 remaining_steps 初始化为 max_steps；嵌套调用和预算已经耗尽后的清理都不能重置它。

CallDepthAccounting 返回可移动、不可复制的 Admission。脚本 FrameRecord 可以持有它；host 调用和生命周期外层可直接持有 Admission，不创建假脚本帧。Native entry adapter 先检查逻辑深度，再进入原生栈保护，最后取得 Admission；普通脚本 CALL 只由执行循环压帧，不增加 C++ 递归。getDepth 仍检查词法 scope parent，不能读取 active_calls 或 FrameStack::size()。

R09 接入要求是：CALL 使用已经 materialize 的寄存器参数，保存 caller PC/return register，压入子帧后继续同一个 dispatch loop；function_end 先按返回位置进行类型转换，再完成清理，最后把 Value 放回调用者寄存器。失败时帧和 Admission 必须一直保留到其必要清理完成。函数的 global offset/count 从不可变 FunctionRecord 取得，不能沿用调用者的模块范围。

宿主重入保存当前 FrameStack mark，在标记上方运行新的调用；新的公开调用根不自动继承暂停帧为 script caller。外层 host Admission 和 workspace 在重入期间保持有效。宿主获得的参数 span 不能指向可能被重入增长的共享寄存器或参数数组；可为每个 host 调用准备一个独立、已计费、在该调用期间不增长的 WorkspaceVector 快照，嵌套 host 调用使用另一份。

`tests/vm_frame_tests.cpp` 当前验证帧存储、调用计数和 workspace 原语；实际 FrameRecord 为 200 字节，20,000 个帧记录和 ID 栈的 backing capacity 为 4,721,072 字节，不含寄存器和其他执行数据。该原语测试没有执行脚本 CALL，不能单独作为 R09 完成证据。


## 8. R09 标量 CALL 接入范围

`execution.cpp` 现在使用 FrameStack 和单一指令循环执行普通脚本 CALL。调用者和被调用者各自拥有寄存器、局部索引、声明序列和 scope mark 的工作区范围；参数读取已经 materialize 的调用者寄存器，再放入独立的被调用者槽。CALL 不递归进入 C++ `invoke`。`function_end` 先按 return_location 转换返回值，再清理被调用者并将值写回 caller FrameId 指定的寄存器。全局索引始终按各自 FunctionRecord 的模块范围解释。

Frame、寄存器和索引数组扩容均计入 WorkspaceBudget；局部 Cell free-list 按实际别名需要增长，已回收的 Cell 和 Slot 页保留并继续计费。准备阶段缓存每个函数的 FrameLayout，普通调用不再次扫描函数指令。新 `peak_bytes()` 记录已批准的 capacity/reservation 高水位，包含新旧数组在增长期间重叠的空间；容量申请之后发生 allocator 失败并回滚时，历史高水位仍保留，越过预算而未批准的申请不会抬高它。

`ExecutionOptions` 把共享 CallDepthAccounting 与宿主 callbacks 传入。已有公开调用 guard 的入口设 `admit_entry=false`；内部脚本调用始终获取独立 Admission。外部 callback 自行执行旧的 lookup、参数/返回检查、native guard 和逻辑计数。未知动态目标保留到 CALL 时才解析，因此参数副作用先发生。callback 的 lexical_depth 等于 `2 + scope_count`：函数根和全局环境共两层，递归调用不会增加这个值。

失败时 callback 依次接收最内层到最外层脚本位置；深度/参数/空间错误发生在帧发布前时，也报告被调用函数的 header 位置。宿主重入只清理自己的 FrameStack mark 以上的帧，因此宿主捕获内层异常后外层调用能够继续。每个正在执行的外部调用拥有独立且已计费的 Value 参数快照，重入增长其他数组不会使参数 span 失效。

定向测试入口为 `tests/vm_call_tests.cpp`（20k 普通递归、逻辑深度、重入、词法深度、完整 workspace、失败恢复、32k 密集局部与合法 MAX 稀疏最后槽）和 `interpreter/scalar_vm_tests.cpp`（旧树执行器逐步数差分，包括递归、参数错误前副作用、return-as-call-argument 和动态未知调用）。宿主生产适配、对象/泛型、清理 continuation 与完整差分仍需分别验收；这部分标量实现不能代表全部 R09/R10 已完成。

## 9. 默认 workspace 定稿依据

测量日期：2026-10-17（UTC）。来源为 `tests/vm_call_tests.cpp`、`interpreter/vm_session_tests.cpp` 的实际输出和一次独立 O2 测量程序（Session + 真实 v10 加载器）；数字为 `WorkspaceBudget` 计费的 backing capacity，`peak` 含增长期间新旧数组并存的空间。

| 场景 | used | peak | 说明 |
| --- | --- | --- | --- |
| 1,048,576 局部头，只声明并读取最后一槽 | 8,454,328 | 8,454,328 | executor 级；Session 级同序 |
| 32,768 局部全部声明 | 2,415,768 | 2,415,768 | Session 级为 2,415,816 |
| 1,048,576 局部全部声明（最密合法帧） | 75,585,688 | 75,585,688 | 约 72.1 MiB；无初始化声明的 v10 模块为 34,603,248 字节，低于 64 MiB exec 上限，真实加载器路径测得 75,585,784 |
| 1 参数小函数递归到默认深度 256 | 152,648 | 182,856 | Session 级 |
| 1,024 局部头递归到深度 256 | 2,249,800 | 2,768,968 | Session 级 |
| 32,767 局部头递归到深度 256 | 67,270,704 | 84,042,800 | peak 约 80.1 MiB，来自寄存器/索引数组的几何增长 |

结论：64 MiB 候选值不足以容纳最密合法单帧（72.1 MiB）及大局部头在默认调用深度的峰值（80.1 MiB），不能定为默认。`vm::default_workspace_bytes` 定为 128 MiB，覆盖上述合法边界并保留约 40% 余量。它仍是独立资源限制：只提高 `max_call_depth` 不会提高 workspace，`vm-call-tests` 用深度 1,024 的同一递归验证在发布新帧前以 `VM workspace limit exceeded` 拒绝，且不留下 admission 或局部槽。

旧树执行器没有对应上限；同一 32,767 局部头递归到深度 256 时，旧帧仅 `shared_ptr` 槽向量就约 128 MiB。新上限造成的拒绝属于计划登记的新资源契约差异，不伪造旧失败。宿主可配置项（C++ 配置及 Java getter/setter）在 VM 成为宿主可选后端时与文档一并加入；当前只有迁移入口 `vm::select_backend(script&, workspace_limit)` 及测试 host 的 `--workspace-bytes`。
