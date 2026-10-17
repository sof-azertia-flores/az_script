# 双执行器共存与源码拆分决策

日期：2026-10-17（UTC）。适用分支：`claude/runtime-performance-handoff-5mdt6h`，起点 `97968ba`。本文件是用户提出“新 VM 与旧解释器是否重叠、去掉不必要重叠、两个执行器作为可二选一的库同时发行一段时间”后的可行性研究结论，修订 [PLAN.md](PLAN.md) 中 R11 之后的顺序和 R18 的含义。实际进度仍以 [STATUS.md](STATUS.md) 为准。

**状态划分：** S0–S4（证据工具、R11b 所需的核心提取、R11b、核心整理、R12）按本文件实施，因为它们直接服务“先 R11b 再 R12”及严格行为一致；S5（运行时 ABI 2、拆分为三个库、双引擎发行）是可行性结论和建议方案，**须经用户在 R12 之后确认才实施**，在此之前两个执行器仍在同一个库中。

## 1. 现状：不是两个独立项目

审查结论（逐文件核对源码行号）：

- **同一份源码清单。** `interpreter/CMakeLists.txt` 把树执行器（`library.cpp`、`internelFunctions.cpp`、`heepalloc.cpp`、`blocks.cpp`）、基础设施（`p256.cpp`、`extern_library.cpp`、`runtime_abi.cpp`）和全部 `vm/*.cpp` 编进同一个 `abdInvoker-1` 及静态 `abdInvokero`。
- **双向依赖。** `library.h` 因 `variable` 的载荷包含 `vm/value.h`，`script` 持有 `vm_session_`，`ofunction` 持有 `vm_function_index_`，`library.cpp` 包含 `vm/session.h`；反过来 `vm/execution.cpp`、`session.cpp`、`host_bridge.cpp`、`loader.cpp`、`cleanup.h` 包含树的头文件。
- **VM 不能独立装载和进入。** exec 校验只在构建表达式树的同一遍中进行（`parse_raw_module`/`parse_raw_expression`），`vm/source_decode.cpp` 只读取已被树接受的字节；链接、onload/pre-destroy 顺序、`call_guard`、trace 发布和每个公共入口都经 `ofunction::invoke_bound`。
- **R11a 对象帧借用树记录。** VM 对象帧为每个作用域创建 `environment`/`function_frame`/`variable`，析构经 `invoke_bound_function` 重新进入一个嵌套 VM 调用根；标量宿主调用为每次调用伪造 environment 链。
- **重复实现。** 值运算与转换（`library.cpp` 约 300 行与 `vm/operations.cpp`）、exec 解码（校验版与非校验版）、引用发现与重定位、签名/上下文/UTF-8 检查、实参校验、调用准入、trace 帧记录、槽上限常量（4 处）及堆内建 ID 表（3 处）。
- **公开头文件就是树的内部结构**：表达式类、`ofunction::code`、`environment`、`function_frame` 和 VM 迁移钩子都随 `runtime.hpp` 安装；插件、JNI、发行脚本和加载器白名单写死 `libabdInvoker-1`；堆、对象块注册表、executor 注册表、`live_scripts`、trace 注册表是库内进程级静态状态。

## 2. 目标结构

VM 重建的是“怎样执行代码”。exec 格式、链接器、对象/堆模型和宿主 API 是可观察契约，两份实现会长期威胁一致性，因此只共享这些契约，不共享任何求值代码。

| 组件 | 共享库 / 静态库 | 源码 | 内容 |
| --- | --- | --- | --- |
| 核心 | `abdInvoker-2` / `abdInvokerCore` | `interpreter/core/` | 宿主 API、校验型 exec v10 读取器及中立模块模型、链接器、脚本生命周期与引擎分派、调用准入、trace、宿主调用边界、全部内建、唯一对象模型（存储、代数检查的所有者身份、语句协议、可恢复生命周期状态机）、global 存储、snapshot v10 编解码、插件加载与 ABI 身份 |
| 树引擎 | `abdInvokerTree-2` / `abdInvokerTree` | `interpreter/tree/` | 从模型构建表达式树并递归求值；原生驱动生命周期状态机 |
| VM 引擎 | `abdInvokerVM-2` / `abdInvokerVM` | `interpreter/vm/` | lowering、只检查自身 IR 的 verifier、dispatch 循环、显式帧、VM 作用域记录、continuation 驱动和 workspace |
| 签名算法 | `azscript_crypto`（内部静态） | `interpreter/crypto/` | P-256；签名工具不再链接整个运行库 |

规则：一个源文件只属于一个组件；tree 与 vm 互不包含，core 不包含二者；由 `tools/check_component_boundaries.py` 检查 include 和重复定义。

选择方式：

- C++ 链接 `AzScript::RuntimeTree[Shared]` 或 `AzScript::RuntimeVM[Shared]`；`AzScript::Runtime[Shared]` 是默认引擎的别名。
- 独立解释器 `azscript-run`（树）与 `azscript-run-vm`；JNI 为 `abdJ-tree` 与 `abdJ-vm`，Java 用 `-Dazertia.runtime=tree|vm` 选择。
- **每个进程只用一个引擎**：CMake 兼容属性、链接守卫、核心首次绑定检查及 JNI 单库加载四层拒绝混用。
- 插件只链接核心库，一个签名二进制在两个引擎下都可用；依赖引擎库的插件被明确拒绝。
- 共存期默认引擎为树，达到 R18 的覆盖和性能门槛后再切换；删除树引擎是之后单独决定的步骤，届时只需删除 `interpreter/tree/` 及其目标。

## 3. 共享核心与重复逻辑的唯一归属

| 当前重复 | 唯一归属 |
| --- | --- |
| 值运算、转换、返回转换、字符串格式化 | `core/value_ops`（热路径为头文件内联，各引擎隐藏编译） |
| exec 解码与校验、引用发现、节点编号、`mem_get(oa)` 规则 | `core` 校验型读取器输出中立模型；树从模型建树，VM 从模型 lowering；删除 `vm/source_decode` |
| 链接目标签名、上下文布局、析构签名检查 | `core/linker` 与共享谓词，各调用点保留原有消息 |
| `validate_arguments`、`call_guard`/`CallDepthAccounting` | `core` 准入：原生模式（含原生栈检查）与循环内模式 |
| `note_script_frame`/`vm::record_script_frame` 及 trace 状态 | `core/trace` |
| 槽上限、内建 ID 表（含 `0x0abd0014`）、namespace 工具 | `core` 常量与枚举 |
| 树的作用域/语句所有权序列与 VM 对象帧的复刻 | `core` 语句协议（`bind_parameter`、`declare_local`、`assign`、`materialize_argument`、`return_value`、`adopt_call_result`、`new_block`、`move_out`） |
| 递归析构驱动（`finalize_into`、`destroy_registered`、`environment::cleanup` 等） | `core` 可恢复生命周期状态机 |
| 两个差分 host、两个 benchmark host | 每个 host 一份源码，按引擎分别链接 |

## 4. R11b 方向

- **对象模型只有一份。** 块 ID、first-fit、地址失效、方法表和快照记录都可观察且不做归一化，因此拒绝第二套 VM 堆；R11b 改为引擎原生的是作用域/帧/临时量记录及“重新执行脚本”的驱动方式。
- **所有者身份**：`slot_block::owner_env` 改为 `ScopeId`，`temp_frame` 改为 `TempListId` 加稳定条目下标，`heap::owners` 改为 `address → ScopeId`；ID 含域、下标与代数（[STORAGE_DESIGN.md](STORAGE_DESIGN.md) 第 2–3 节 R06b），注册表不再保存裸地址。树使用每脚本一个不限额的核心 arena，VM 每个程序会话使用计入 workspace 的 arena。`slot_block::owner` 暂保持 `variable*`，VM 的对象局部量使用池化 `variable` 单元。
- **语句协议**：上表中的核心函数由两个引擎按相同顺序调用，块 ID、复制/移动判定和步数扣费只有一个来源。
- **生命周期状态机**：每种状态机逐阶段转写现有代码（`finalize_block`、`destroy_heap_object`、`finalize_storage`、`finalize_temporaries`、`end_scope`、`copy_rollback`、`store_replace`、`drop`、`release_owned`、`delete_object`，R12 再加 buffer 操作），用有界显式工作栈，不在 C++ 栈上递归对象图。接口为 `advance()`→完成或请求调用、`call_returned`、在 catch 内调用的 `call_failed`（保留首错及 trace）、`finish` 和不执行脚本的 `abandon`。块的强引用持有区间与现有递归代码相同。
- **树原生驱动**：每次请求的调用走与今天 `invoke_bound_function` 完全相同的核心分派（两层 entry、`getFunction`、`invoke_bound` 前缀、带原生栈检查的准入、实参校验），递归和每入口 trace 行为不变。
- **VM 循环内驱动**：会执行脚本的指令在帧内预留的槽位启动状态机；同脚本请求成为循环内帧，执行与树相同的入口检查和 trace 状态（`entries`/`sequence`/`published_sequence`），不检查原生栈；外脚本请求仍作为原生嵌套根。三个 continuation 提交完成后，调试计数器须证明语料中没有同脚本嵌套根，再删除 `ObjectHost` 发布括号和未使用的 `CleanupActions` 骨架。
- **宿主发起的清理**：`destroy` 持有 `call_guard(baseEnv)`，其析构在 `active_calls>=2` 运行且不重置步数；JNI 字面量结果 finalize 与宿主 `blocks::finalize` 无锚点，深度 0 准入的每个析构都会重置预算。两种情况都用夹具固定。
- **普通同脚本 VM CALL 不增加检查**，只保留深度准入；trace 清理位只在冷路径置位，并入 `function_end` 已测试的帧标志。

## 5. 顺序

依用户“先 R11b，再 R12”：

| 阶段 | 内容 | 类型 |
| --- | --- | --- |
| S0.1 | 本文件及 PLAN/STATUS/HANDOFF 修订 | 文档 |
| S0.2 | 观察协议 2、引擎中立观察 host、冻结参考 F0 | 测试 |
| S0.3 | 所有权/析构一致性语料、差异登记表、逐字节双跑工具及负对照 | 测试 |
| S1.1–S1.4 | trace 模块、VM 每入口 trace 状态修正、统一准入与调用规划、共享 ID/上限/谓词 | 纯重构；S1.2 为 VM 修正 |
| S2.1–S2.8 | **R11b**：代数身份 → 语句协议 → 生命周期状态机（树原生驱动）→ VM 原生对象记录与循环内内建 → 作用域/语句/函数退出及展开的 continuation → 存储/物化/返回 continuation → drop/delete/release continuation 并删除嵌套根骨架 → 文档 | 纯重构与 VM 修正 |
| S3.1–S3.4 | 统一值运算、校验型读取器与模型、核心链接器与引擎接口、同一库内的组件目标拆分 | 纯重构 |
| S4.1–S4.6 | **R12**：泛型上下文、buffer（先树原生再 VM continuation）、默认工厂/`value_compare`/`check_object`、反射与插件加载、全部套件双引擎运行 | VM 修正 |
| S5.1–S5.5 | 运行时 ABI 2 宿主 API（项目 0.4.0）、三个 DSO 与引擎选择、隐藏可见性与导出白名单、双引擎发行、验证记录 | API/打包 |

每个代码提交都通过完整普通验证和随后的 sanitizer 验证；任一阶段边界都保持可构建、可验证，可在边界暂停。

## 6. 验证

- **F0**：S0.2 提交的运行时等于 `97968ba`，`git archive` 到 `build/runtime-perf/f0-source/`，构建 Debug、Release、ASan（含 JNI 的普通构建）。参考观察记录一次后回放，重录只用于确认确定性。
- **每步三组独立进程对照**：候选树对 F0、候选 VM 对 F0、候选 VM 对候选树；只做第三组不够，因为共享核心的漂移会让两个引擎同向变化。返回标签与位、stdout、宿主事件与回调内探针、构造/析构事件、global、堆槽与分配起点、下一个块 ID 与 first-fit 探针、对象登记与方法表数量、错误类别/消息/完整 trace、失败阶段、`faulted`、`remaining_steps`、teardown 及引擎标识全部不经归一化比较。小案例从预算 1 扫描到成功扣费数 +1。
- **语料**：现有 57 案例；S0.3 一致性语料；R12 语料在 VM 支持前先从 F0 记录；exec 变异语料与运算×标签表；带缩小的种子程序；全部 `*_end_to_end.py` 按引擎各跑一次，外加逐字节双跑；真实 JVM 的 JNI；四个方向的快照矩阵。
- **生命周期专项**：S2.3/S4.2 的旧递归 helper 对状态机 A/B（独立进程，可比较绝对块 ID 和 first-fit）、嵌套根计数器、每个 arena/工作栈/记录增长点的 `bad_alloc` 注入（不能出现有 owner 而无清理登记的对象）、workspace 边界复测。
- **负对照**：每个新门禁附带故意错误的变体（trace 探针、准入、顺序、调用者作用域、步数重置、引用顺序、导出白名单、链接守卫），必须被检出。
- **性能门禁**：纯重构步骤 callgrind 每案例最多 +0.5% 且解释每处增加；Release 7 轮×5 批交替独立进程，VM 标量/调用每步 ±3%，S2.4–S2.7 后对象与虚调用须改善，树相对 F0 每组不超过 5%，RSS 增加超过 10% 须说明。

## 7. 允许的差异（封闭清单）

| 编号 | 差异 | 机械验证 |
| --- | --- | --- |
| D1 `native_stack` | 树以 `Script native stack limit exceeded` 失败而 VM 成功；R11b 后扩展到深析构链和循环内内建，R12 后扩展到 placement、工厂和反射 | 在 1 GiB 栈线程、`max_stack_bytes=0` 下重跑 F0 或候选树，必须完整复现 VM 观察；无登记或过期登记均失败 |
| D2 `vm_workspace_limit` | VM 以 `VM workspace limit exceeded` 失败而树无此限制（默认 128 MiB，C++/Java 可配置） | 以更大上限重跑 VM 必须复现树；清理中耗尽须保留首错并完成原生释放 |
| D3 `transitional_vm_refusal` | R12 完成前 VM 对未支持功能明确拒绝 | 每条注明移除它的步骤；S5.2 前须为零，VM 库发行时不得含任何 D3 |

另登记两类两个引擎相同、但与 F0 不同的变化：`api_migration`（ABI 2 的有意 API 变化，附新旧行为夹具）和 `defect_fix`（只在有复现时修复，例如 `default_element` 对已失效脚本的空指针、`store` 跨析构持有的裸 `owner_env`）。畸形 exec 消息、已发布 trace 状态与 `describe_error`、`getDepth`、`destroy` 与深度 0 的步数核算、实参校验消息、快照字节均不是差异。

## 8. 研究结论中的建议（用户可否决；S5 部分须用户确认后实施）

1. 拓扑采用“核心 + 两个引擎 DSO”，而不是两个导出相同符号的独立库：进程级状态只有一份，插件在所有平台引擎中立，将来删除树引擎只需删除组件。
2. 沿用 `abdInvoker` 命名：`abdInvoker-2`、`abdInvokerTree-2`、`abdInvokerVM-2`，归档 `abdInvoker{Core,Tree,VM}`，`abdJ-tree`/`abdJ-vm`，`azscript-run`/`azscript-run-vm`。
3. 运行时 ABI 2 与 CMake 包 0.4.0 在 R12 之后的 S5.1 一次完成；此前对已安装 detail 头的修改属于未发布的 ABI 1 内部变动，不单独发版。
4. 一进程一引擎；共存期默认树。
5. R11b 改写树的递归驱动为共享状态机，使析构顺序只有一个来源；风险由 F0、A/B 和变异测试控制。
6. 所有者身份采用代数检查的 ID，不用带序号的裸指针。
7. VM 的 trace 状态精确模拟，不登记为差异；普通同脚本 CALL 不增加检查。
8. 快照编解码移入核心，v10 字节不变，跨引擎可移植作为测试保证；快照接口保持为 SPI。
9. VM 库只在 R12 完成且 D3 为零后进入发行包，不提前发布“实验版”。
10. 隐藏可见性，以 `AZSCRIPT_API`/`AZSCRIPT_ENGINE_API` 和导出白名单替代 `WINDOWS_EXPORT_ALL_SYMBOLS`。
11. 两个库都提供 `max_vm_workspace_bytes`（树忽略），保持 API 相同。
12. 静态运行库宿主仍不能加载插件，保持并记录现状。

仍需用户确认：S3 核心整理放在 R11b 与 R12 之间是否符合“先 R11b 再 R12”；D1 扩展范围与 D2 默认值；共存期内新功能是否必须同一提交进入两个引擎、双发行的退出条件；ABI 2 公开接口收窄范围（`environment` 退出宿主回调 ABI、不再安装 detail 头、`call_context` 不暴露模块相对 global）；首个双引擎发行包是否只覆盖已实际验证的 Linux。
