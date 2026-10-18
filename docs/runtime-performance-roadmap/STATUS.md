# 运行时性能重构实施记录

基线：`c2b572d6b36ca521c5faf2658adc296e403f47f1`。原实施分支：`codex/runtime-performance`；暂停检查点分支：`codex/runtime-performance-handoff`（`ad26db9`）；Claude 接续分支：`claude/runtime-performance-handoff-5mdt6h`；本次从其 `816a67a` 恢复到 `codex/runtime-performance-continue`。本文件记录实际完成和待验证内容；完整目标见 [PLAN.md](PLAN.md)。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| R00 | 完成 | Temurin 21.0.12.1+1、CMake 3.31.10、GCC 14.2；独立冻结源码，完整验证退出 0，输出 `build/runtime-perf/baseline-validation.log` |
| R01 | 测量已暂停 | Release smoke 已通过；正式 7 轮采样完成 5 轮及第 6 轮 10 条，共 310 条观察，退出 130；无正式评分 |
| R02 | 普通验收通过 | 几何增长、first-fit 和分配失败回滚定向通过，完整普通验证通过；完整 sanitizer 待运行 |
| R03 | 基础设施验证通过 | 23 案例 / 145 对独立进程观察，5 组完整预算扫描；故意错误候选被拒绝并缩小。54 opcode 已登记，28 静态出现，不代表完整 VM 覆盖 |
| R04 | 普通验收通过 | 类型化访问与消费者迁移、运行时 ABI 1、插件预注册检查完成；ABI 29 项检查通过，发行搬迁待运行 |
| R05 | 普通完整验收通过 | Value 内联标量，145 对差分与全量普通验证通过；完整 sanitizer 和发行验证仍待闭合 |
| R06–R08 | 标量 VM 已接入构建并通过定向及独立进程差分 | VM 源码与 9 个定向测试、2 个 host 已加入 CMake；见下文“恢复后的集成”。生产 `blocks/heepalloc` 所有权身份迁移见下方 S2.1 记录 |
| R09 | 标量 CALL/宿主桥/Session 通过；默认 workspace 定为 128 MiB | 37 案例 / 825 对独立进程观察（冻结 `019c440` 参考及同构建树后端）全部字段一致；workspace 边界见 [STORAGE_DESIGN.md](STORAGE_DESIGN.md) 第 9 节。宿主可配置项待 VM 成为宿主可选后端时加入 |
| R10 | 编译器 cleanup 节点已接入标量执行循环 | 见下文“R10 编译器清理接入”。CleanupActions 析构 continuation、语句临时量仍依赖 R11 对象模型 |
| R11 | R11a 完成：非泛型对象、heap、继承、虚调用由 VM 执行；析构仍经新调用根重入 | 见下文“R11a 对象帧”。R11b 按 [ENGINE_SPLIT.md](ENGINE_SPLIT.md) 的 S0–S2 进行（证据工具 → trace/准入提取 → 身份、语句协议、生命周期状态机、VM continuation），未完成前不能标为 R11 完成 |
| 执行器解耦 | 已决定，进行中 | 目标是树组件可整体删除：`core/`、`tree/`、`vm/` 组件及边界检查、无树构建门禁、宿主 API 去除树类型（ABI 2）；不做多库拆分。见下文“执行器重叠审查与解耦决策” |
| R12–R28 | 尚未完成 | R12 在 S3 核心整理之后进行；泛型、buffer、反射、插件在 VM 准备阶段明确拒绝 |

## S0.3：所有权/析构一致性语料与差异登记（2026-10-18）

以 R11b 一致性规格（树执行器的析构/所有权语义，经对抗复核）为依据，新增 `tests/conformance/` 七组语料，作为 `vm_execution_end_to_end.py` 的 `ownership` 阶段：作用域 55、临时量 60、赋值 64、析构 43、heap 87、错误与 trace 63、teardown 31，共 403 案例。每个期望值都先从规格和源码推出，再对照 F0，不从观察结果抄录；除 12 个注明理由的案例外都做完整步数预算扫描。比较协议 2 的全部字段，包括事件、`remaining_steps`、探针记录的 `active_calls` 与已发布 trace、trace 帧行号、下一个块 ID、first-fit 起点、owned 列表和 teardown。每组语料由独立审阅者复核期望值和遗漏场景，审阅意见已逐条处理；teardown 组的审阅结论为接受，仅采纳了一条加强区分度的修改，其余建议主要依赖 VM 尚未支持的 buffer，留作后续语料工作。

- **差异登记表** `tests/runtime_differences.{json,py}`：封闭的三类（`native_stack`、`vm_workspace_limit`、`transitional_vm_refusal`），每条登记须被机械证明：原生栈一类用 1 GiB 线程栈重跑参考端，工作区一类用更大工作区重跑 VM，拒绝一类要求 VM 在 flush 时报出登记的原文。没有表现出差异的登记视为过期并使运行失败。当前 6 条 VM 过渡拒绝覆盖 29 个案例（泛型上下文、buffer、`check_object`、对象签名宿主回调），各自注明取消它的步骤；套件级拒绝 49 条记录按消息与次数核对。
- **逐字节双跑** `tools/backend_alignment.py`：每个端到端套件在强制 VM 时再运行一次，输出须逐字节一致，或为登记表中按次数列出的拒绝；它取代回退到树后端的 `tools/vm_suite_triage.py`（已删除）。`build_and_test.py` 在 VM 差分之后运行它及两个工具的自检。
- **负对照** `tools/conformance_mutants.py`：每个变异体是当前运行时加一处生命周期错误（成员先于析构体、临时量先进先出、owned 列表先进先出、析构调用者总是合成、每个析构重置步数、后错覆盖首错、复制不计费、heap 成员先于析构体、返回值总是复制、teardown 不持有调用守卫、heap 析构调用者总是合成、`getDepth` 沿调用者计数），各自构建观察 host，语料自身期望及 F0 回放都必须识别它。锚点须在源码中恰好出现一次，代码移动后锚点随之更新。在 `482157d` 上未修改的对照先通过，12 个变异体全部被两者识别（`build/runtime-perf/s03-mutants-1/report.json`）。
- `tools/callgrind_gate.py`：参考与候选 Release 构建的每调用指令数对照（1 次与 6 次调用之差），用于纯重构步骤的 +0.5% 门禁。
- **生命周期模糊测试** `tests/lifecycle_fuzz.py`：每个种子只由 `random.Random(seed)` 生成一个小程序（字面对象与指针字段、三层以内继承、会失败的析构、循环与 switch 中的对象、自动/手动 heap、宿主重入、全局量与生命周期钩子、小深度上限），F0 与候选在独立进程中以完整预算和预算扫描运行，逐字段比较；不一致时保存程序与观察，`--shrink` 缩小到最小程序。`build_and_test.py` 运行其自检（24 个种子编译、宿主确定性及 VM 拒绝分类）。种子 0–199 在当前分支的树与 VM 上全部一致（各 17,570 个预算点）。

F0 参考记录 403 案例 / 52,822 次独立进程，全部期望通过；当前分支的树与 VM 回放全部一致（各 52,822 次）。语料复核过程中发现并修复一个 VM 缺陷：赋值表达式结束时 VM 没有释放操作数，容器已被删除的目标因此没有放弃新存入的对象（见 `fix(vm): end a store's operands with the assignment`）。

## S2.3 首批：可暂停的块析构遍历（2026-10-18）

基于 `e8ca1ef`，新增 `core/lifecycle` 的 `BlockFinalizer`，替换 `blocks.cpp` 中递归的 `finalize_into`。`advance()` 在需要脚本析构调用时暂停，调用方用 `call_returned()` 或 catch 内的 `call_failed()` 恢复；完成后 `finish()` 可重新抛出首错，`abandon()` 和状态机析构函数只完成原生释放。原生驱动仍使用原有 `invoke_bound_function` 分派，不改变准入、参数校验、步数和 trace 路径。

- 对合法对象图使用最多 65 个帧的固定工作栈，只构造实际使用的帧，没有遍历时的容量增长或分配。脚本调用准备与 `pending_error` 的错误 trace 捕获仍可能分配，零分配检查不包含它们。父对象先运行析构体，随后按逆序处理成员；成员存储与块在暂停期间保持强引用，子遍历完成后立即释放对应帧。
- 临时值解除登记、`destroying/dead` 状态、方法表清理、buffer 活动操作延迟清理及 slab 释放顺序保持。复制回滚、赋值替换、drop、临时值和存储清理的块遍历均进入这一状态机。
- 新增独立 `lifecycle-tests`：161 项检查覆盖暂停/应答协议、重入、逆序和嵌套清理、回调期间移除父存储、首个非标准异常、取消清理及活动 buffer。65 层遍历在禁止所有普通 new 分配时完成；原生驱动的同脚本/外部上下文调用准备分配失败后仍清理后续成员。故意改成正序遍历的负对照退出 1，报告 `members must start in reverse slot order`。
- 定向 16/16 CTest 和原有 6,284 项运行时检查通过。树、VM 各 57 案例 / 2,310 次 F0 回放通过，证据在 `build/s23/f0-{tree,vm}/`。
- 最终普通全量通过：330 项 Java 测试、16/16 CTest（6,284 项运行时检查、161 项生命周期检查及 3,433 项真实 JNI 断言）、全部跨语言套件，以及 57 案例 / 2,310 参考与 2,310 候选进程差分。日志 `build/s23/normal.log`。
- 最终 ASan/UBSan 全量通过：15/15 CTest、全部跨语言套件及 57 案例 / 2,310 参考与 2,310 候选进程差分，无 sanitizer 报告。日志 `build/s23/sanitize.log`。两个完整命令为 `python3 tools/build_and_test.py --offline` 与随后附加 `--sanitize`，均清除三个 Java 选项环境变量；源码摘要与 `build/s23/source-manifest.json` 一致。未重新运行发行导出。

Release 诊断独立运行，参考为上一提交 `e8ca1ef` 的冻结 Release 构建（`build/resume/s22-release/`），候选为 `build/s23/release/`；相同 GCC 14.2，10 个案例 × 中/大规模，7 轮 × 5 批，50 ms 参考校准，每引擎 280 个计时进程，全部 oracle/校验和正确。案例配对比值的组内几何平均如下，数字为参考耗时 / 候选耗时：

| 相对 e8ca1ef | 标量循环 | 函数调用 | 对象 | 虚调用 |
| --- | ---: | ---: | ---: | ---: |
| 树 | 0.996× | 1.002× | 1.020× | 0.996× |
| VM | 0.974× | 1.006× | 0.992× | 1.006× |

VM 标量组约增加 2.6% 耗时，对象组约增加 0.8%；其余组变化较小。所有测量点峰值 RSS 比率小于 1.008。这是相对上一批的短批次诊断，`score_eligible=false`；不替代 250 ms 全规模采用采样及未运行的 callgrind 门禁，也不能据此关闭此前 F0 对象性能差距。原始证据 `build/s23/perf-{tree,vm}/`，汇总 `perf-summary.json`。

这只是 S2.3 的块遍历部分。`destroy_registered`、作用域/临时值列表的外层驱动、store 提交及复制驱动尚未成为可暂停状态机；普通 `blocks::abandon` 的无脚本释放也仍有递归路径。VM 仍通过原生驱动调用析构，尚未接入循环内 continuation，同脚本嵌套根没有消除，不能标记 S2.3 或 R11b 完成。新核心头仍暂时依赖对象模型适配头，组件边界拆分留到后续阶段。固定工作栈将来放入 VM 帧时须计入 workspace，本轮原生栈存储没有被宣称为 VM 预算内记录。

## S2.1：作用域与临时值的所有权身份（2026-10-18）

从 GitHub 的实际分支 `claude/runtime-performance-handoff-5mdt6h` 拉取到 `816a67a`。新环境重建 Temurin 21.0.12.1+1、CMake 3.31.10，使用 GCC 14.2；后续证据均为本机重新运行，未沿用 Claude 的构建产物。

- 新增 `core/ownership`：分段、稳定地址的 `ScopeRecord` / `TemporaryRecord`，以域、槽下标、代数组成的 ID 解析。环境与函数帧持有 RAII lease，退休后旧 ID 无法解析；复用槽位不能重新授权旧所有者。
- `slot_block::owner_env`、`temp_frame` 与 `heap::owners` 的环境指针已分别替换成 `ScopeId`、`TempListId + index`、`address → ScopeId`。临时值移出按条目下标留下空洞，保留语句水位，免去逆向线性查找。JNI 的自动释放权限和快照恢复所有者同步迁移。
- 树引擎使用每脚本 arena；VM 对象帧和宿主边界适配作用域使用每 Session arena，记录页及目录计入现有 workspace。预算由共享所有者持有，宿主保留环境超过 Session 生命周期时不会留下预算悬空引用。
- `defect_fix / buffer-owner-retired`：缓冲区替换的析构回调可以释放原作用域并立即复用其槽位。发布替换值前重新解析捕获的 ID；原作用域已退休时清理候选并报告 `Buffer owner ended during assignment`；已有析构错误仍优先。回归覆盖正常退休与先前错误两条路径。

本步仍使用 `environment` / `function_frame` 适配器和 `variable*` 的直接存储所有者；所有权列表的 `std::vector` backing 尚未纳入 VM workspace，须随 S2.4 的原生对象记录迁移。记录页计费不代表对象执行的全部原生内存已受限。S2.2 的首批共享协议见下一节；S2.3 可恢复生命周期状态机和 S2.4–S2.7 VM continuation 尚未完成；同脚本析构仍会建立嵌套根，不能标记 R11b 完成。

已验证：

- 运行时 6,277 项检查通过，包括两类记录的冷分配与跨页增长各两个实际 `bad_alloc` 注入点（共 8 个），预算回滚、旧 ID 保持有效、失败后重试、代数/域隔离、临时值水位及宿主延长预算生命周期。
- F0 使用 `ab83ab1` 的 `git archive`（运行时等于 `97968ba`），57 案例 / 2,310 份参考观察。候选树、候选 VM 各 2,310 次独立进程回放全部字段一致，路径 `build/resume/f0-observations/`、`f0-vs-tree/`、`f0-vs-vm/`。
- F0 的原始 `blocks.cpp` 加 ASan 的定向复现报告在 `store` 中访问已释放的作用域（`build/resume/f0-owner-retired.log`）；候选正常完成。另将候选跨回调作用域有效性检查移除，动态库负对照退出 -11，被新增回归检出。
- CMake 安装后，独立静态/动态 C++ 消费者均编译、运行通过，包含新头文件的传递安装检查；产物 `build/resume/install/`、`consumer-build/`。未执行完整发行导出。

最终整体验收与性能结果见下一节；本步单独的普通、ASan/UBSan 全量也已运行通过（`build/resume/ownership-full.log`、`ownership-sanitize.log`）。

## S2.2 首批共享语句协议（2026-10-18）

新增 `core/statement`，两个引擎共享语句临时值水位、参数绑定与局部声明的提交顺序。水位携带 `TempListId`，临时值清理在每次析构回调后重新解析 ID，过期水位不能清理后来复用的帧。树的正常语句退出与 VM 的正常/异常展开使用同一清理入口；首错与 trace 的逐层重新发布顺序保留。变量声明校验仍在求值前，提交阶段不重新校验，以保留手写 exec 的初始化器重声明行为。

没有对象临时值的语句仍直接比较帧内水位，避免在标量循环里增加注册表查找。已登记对象的虚调用继续直接使用对象记录中的脚本归属，仅在对象登记缺失时查询作用域所有者；保留 F0 的惰性查询路径，避免给虚调用增加无用查找。已有共享的 `blocks::store/materialize/export_result/adopt_result/move_out` 继续承担对象复制、移动和返回规则，尚未改成可暂停操作。`statement` 的登记与清理驱动仍使用环境适配器；这一步不代表核心组件已经摆脱树类型，后续须随 S2.3/S2.4 迁移。

- 新增内外层语句水位、移动后空洞、过期帧槽复用检查，另覆盖空作用域释放未绑定块和堆分配的旧行为；把内层清理水位故意改为零的负对照退出 1，明确报出外层值被提前清理（`build/resume/statement-negative.log`）。
- 最终候选树、VM 各 57 案例 / 2,310 次 F0 回放通过；证据 `build/resume/validated-f0-vs-tree/`、`validated-f0-vs-vm/`。
- 最终普通全量通过：330 项 Java 测试、15/15 CTest、6,284 项运行时检查，真实 JVM 3,433 项 JNI 断言、四线程 800 次调用、1,000 模块链接，以及所有跨语言/对象/泛型/缓冲区/继承/虚调用/边界/ABI/exec 套件和 57 案例双引擎差分。日志 `build/resume/final-normal.log`；普通验证在 `build/resume/s22-source/` 副本运行，89 个原生源码/头文件/CMake 文件的 SHA-256 与当前分支一致（`s22-source-manifest.json`），Java 源码未修改。
- 最终完整 ASan/UBSan 全量通过：14/14 CTest、6,284 项运行时检查、全部跨语言套件，以及 57 案例 / 2,310 参考与 2,310 候选进程的双引擎差分，无 AddressSanitizer 或 UndefinedBehaviorSanitizer 报告。命令 `env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS python3 tools/build_and_test.py --offline --sanitize`，日志 `build/resume/s22-sanitize.log`；与普通验证顺序执行，sanitizer 不含 JNI。

Release 性能诊断在普通验证结束后、最终 sanitizer 开始前单独运行，期间没有并行编译或测试：相同 GCC 14.2 Release 配置，10 个案例 × 中/大两种规模，每种引擎 7 轮 × 5 批，交替启动参考/候选独立进程；参考端校准约 50 ms 批次。每引擎 280 次计时进程均满足独立 oracle 和校验和。下表是案例配对比值的组内几何平均，数字为 **F0 耗时 / 候选耗时**，大于 1 表示候选更快：

| 同引擎 F0 对照 | 标量循环 | 函数调用 | 对象 | 虚调用 |
| --- | ---: | ---: | ---: | ---: |
| VM | 1.111× | 1.071× | 0.956× | 0.974× |
| 树 | 1.188× | 1.080× | 1.007× | 1.078× |

这是 F0 至当前候选的累计变化，包含 Claude 此前的 trace/准入、值操作和核心整理，不能全部归因于本轮。VM 对象组耗时仍约增加 4.6%，虚调用组约增加 2.7%，对象性能目标尚未达成。两种引擎所有测量点的峰值 RSS 比率均小于 1.008。完整原始记录位于 `build/resume/final-perf-{vm,tree}/`，汇总 `final-perf-summary.json`。

本次使用 50 ms 短批次和中/大规模子集，工具的 `score_eligible=false`，没有计算采用分数；未完成既定 250 ms 采用采样及 callgrind 逐指令门禁（本环境未安装 callgrind）。因此这里只关闭功能验证，不能宣布 R11b 性能验收完成。下一步仍是生命周期状态机、VM 原生对象帧和循环内析构调度。

## 执行器解耦首批：S0.2、S1.1–S1.5、S3.1–S3.3（2026-10-17）

依 [ENGINE_SPLIT.md](ENGINE_SPLIT.md) 顺序执行；S3.1–S3.3 是不依赖 R11b 的纯重构，提前完成。每步都以冻结参考 F0（`97968ba` 运行时，`build/runtime-perf/f0-source/`）回放：候选树和候选 VM 分别对 F0 逐字段比较（scalar 399、calls 426、objects 1485 个候选进程，全部一致），并对上一提交或 F0 的 Release 构建做 callgrind 每调用指令对照。各提交还通过了普通与 ASan/UBSan 全量验证。

| 步骤 | 提交 | 内容 | 验证要点 |
| --- | --- | --- | --- |
| S0.2 | `ab83ab1` | 观察协议 2：`tests/observation_host.cpp` 引擎中立（`--engine tree\|vm`、`--stack-bytes`、`--thread-stack-bytes`），回调 5 在运行中记录 `active_calls`、`remaining_steps` 和已发布 trace；状态含对象登记、owned 列表、下一个块 ID 与 first-fit 探针 | F0 三组参考记录一次后回放 |
| S1.1 | `3c6da58` | `core/trace`：两个引擎共用的 trace 帧记录与发布 | F0 回放；callgrind 发现 `FunctionRecord` 缩小使每条指令多一次乘法，由 S1.5 修正 |
| S1.2 | `b055525` | VM 修正：嵌套帧精确复现树每个入口的 trace 状态（进入时清空、成功清除已发布、失败发布副本、逐层重新发布） | t1–t8 探针程序（嵌套失败、宿主重入、析构失败）树与 VM 一致 |
| S1.3 | `777cceb` | `core/admission`：唯一的调用准入（逻辑深度、原生栈、计数）与实参检查；`core/types` 类型名 | F0 回放、limits 套件 |
| S1.4 | `8148d7b` | `core/ids`、`core/limits`：函数 ID 布局、内建表、槽上限（原有 4 处定义、3 处重复） | F0 回放 |
| S1.5 | `43a1b6b` | VM 每条指令不再索引函数表（`FrameRecord::end`） | callgrind 回到 F0 以下 |
| S3.1 | `cc87336` | `core/value_ops`：值运算、转换、返回转换、格式化只有一份；树的字符串键分派等变为薄包装 | 9 个套件双跑 623 次调用 0 差异；callgrind 树 −4%～−13%，VM −0.4%～−1.2% |
| S3.2 | `3be0d65` | `core/exec`：唯一的校验型 exec v10 读取器输出中立模型，检查及其顺序、消息与树装载器相同；树从模型建树（装载时核对补丁表与 `exec::references`），VM 从同一模型 lowering；删除 `vm/source_decode` | `exec-model-tests` 408 项；compact_exec 415 次调用 |
| S3.3 | `99330ce` | `core/link`：唯一的链接计划（假定命名空间、外部签名、引用、onload 顺序），树与 VM 应用同一计划；`function_signature` 移入 `core/signature.h` | hint_linking、extern、generics、compact_exec 499 次调用 0 差异 |
| — | `58a8546` | `Value` 标量拷贝与析构保持内联 | callgrind 对 F0：VM −2.7%～−3.7%，树 −4.2%～−13.0% |
| — | `816a67a` | 代数身份的存储原语（`StorageId`、`SegmentedPool`、`WorkspaceBudget`、`WorkspaceVector`）移入 `core/storage.h`、`core/workspace.h`，供 S2.1 的所有权记录使用 | ctest 15/15、VM 的 F0 回放 |

`core/` 现有 `trace`、`admission`、`types`、`ids`、`limits`、`value`、`value_ops`、`exec`、`format`、`signature`、`link`、`storage`、`workspace`。安装的 detail 头新增 `core/{debug,errors,format,limits,signature,types,value}.h`，`vm/value.h` 不再安装；属于未发布的运行时 ABI 1 内部变动。

## 执行器重叠审查与解耦决策（2026-10-17）

起点 `97968ba`。六个分区（loader、对象存储、内建/插件、VM、公开 API、构建/发行）逐文件核对后，结论是新 VM 与树执行器**不是**两个独立项目：同一源码清单编进同一个库，二者双向包含；VM 依赖树的校验、链接、入口和对象所有权记录，析构仍经树入口重入；值运算、exec 解码、签名检查、准入、trace 记录等存在重复实现；公开头文件暴露树内部结构，插件和发行写死 `libabdInvoker-1`。

随后三份独立架构方案经两位评审打分。用户确认的目标是让较慢的树执行器在 VM 证明可靠后可以干净删除，而不是长期双发行；结论记录在 [ENGINE_SPLIT.md](ENGINE_SPLIT.md)：同一运行库内分为 `core/`（宿主 API、校验型读取器与模型、链接器、唯一对象模型及可恢复生命周期状态机、trace、内建、插件、快照）、`tree/`、`vm/` 三个组件，以边界检查、无树构建门禁和不含树类型的宿主 API（ABI 2，R12 之后）证明树可删除。树执行器的析构/所有权语义已整理为一致性规格并经对抗复核，作为 S0.3 语料和 R11b 实现的依据。

本节只记录结论，尚无对应运行时改动；各步骤完成后在本文件分别记录实现、验证、性能测量和发行状态。

## 暂停与交接

按用户要求暂停，完整交接见 [HANDOFF.md](HANDOFF.md)。暂停检查点 `ad26db9` 的 VM 接入尚未加入 CMake；恢复后的集成与验证记录见下一节。“已运行的定向检查（R01–R05）”中的通过结果仍属于各自明确的较早源码阶段。

## 恢复后的集成（2026-10-17）

工具链：OpenJDK 21.0.12.1+1、CMake 3.28.3、GCC 13.3.0、Python 3.13.16；4 CPU、15 GiB 内存。依赖 jar 按 `tools/build_and_test.py` 的固定 SHA-256 下载校验。没有沿用交接文档中的本机 `build/` 证据；冻结参考由 `git archive 019c440` 重新生成到 `build/runtime-perf/r05-source/`。

集成改动与实际发现：

- `interpreter/CMakeLists.txt` 把 `vm/{storage,frames,program,lowering,source_decode,loader,operations,execution,session,host_bridge,cleanup}.cpp` 加入运行库，按交接表增加 9 个 ctest 目标及 `vm-loader-host`、`vm-execution-host`；另增 `vm-execution-tree-host`（同一适配器、不选择 VM）作为统一构建中的树后端参考。
- 首次编译只有 `tests/vm_call_tests.cpp` 一处 initializer_list 赋值错误。`scalar-vm-tests` 的 return-as-call-argument 夹具使用 `int == void`，与 main 已有的相等类型规则冲突（旧执行器和 VM 同样报错）；改为只比较 void==void 并保留“return 作为实参是 flow 状态，被调函数以 void 实参运行”的目的。
- 真实编译器把生命周期钩子标为内部入口（`entry_kind=1`），executor 原先拒绝所有内部入口，导致含 onload 的真实模块全部在 flush 时被拒绝；Session 单测因手写钩子用 `entry_kind=0` 未发现。现只允许无上下文的生命周期内部入口，构造/析构/工厂形状的内部入口仍在准备阶段拒绝；Session 测试钩子改用编译器形式并新增拒绝回归。
- `tests/vm_execution_cases.py` 中两条夹具与当前语言不符：`address(3)` 已被显式转换规则拒绝，改由 `null` 偏移得到同一地址；动态值传给 `int` 形参已在编译期拒绝，运行时实参类型检查改由手写 wire 测试覆盖，源码夹具改为“实参求值副作用后报错”。
- `tools/build_and_test.py` 在全部原有检查后运行 `vm_program_end_to_end.py` 和 `vm_execution_end_to_end.py`（VM 对同构建树后端）。

实际结果（Debug）：

- `ctest`：普通构建 15/15 通过（含真实 JNI）；ASan/UBSan 构建（无 JNI）14/14 通过。
- 独立进程差分：37 个 scalar/calls 案例、825 个参考进程 / 825 个候选进程，协议全部字段（返回、事件、stdout、global/堆状态、错误、trace、剩余预算、faulted、teardown）一致，含逐预算扫描；参考为冻结 `019c440` 源码构建的 `AZSCRIPT_VM_REFERENCE_ONLY` host，另与同一构建的树后端 host 重复一次，均通过。
- `vm_program_end_to_end.py`：6 次 debug source/AST/ABD 往返、6 次多模块 lower/verify 通过（不执行 VM）。
- 默认 workspace 测量与 128 MiB 定稿依据见 [STORAGE_DESIGN.md](STORAGE_DESIGN.md) 第 9 节；`vm-call-tests` 断言默认值容纳最密合法帧和默认深度的大局部头递归，并拒绝更深递归而不留下部分帧。
- 完整普通验证 `python3 tools/build_and_test.py --offline`：退出 0，日志 `build/full-normal.log` 末尾为 `Build and all selected tests passed`；包含真实 JNI、插件、快照、ABI 29 项及上述两个 VM 门禁。

限制：VM 仍只能经迁移入口 `vm::select_backend` 选择，生产默认仍是树执行器；对象、泛型、buffer、虚调用、分配/反射/插件内建在准备阶段明确拒绝，不回退。R05 冻结源码的完整 sanitizer、发行搬迁及正式 7 轮性能采样仍未运行。

## R10 编译器清理接入

wire op 31（`cleanup`）的 `cleanup_enter/leave/end` 由标量 executor 执行，复用 `CompilerCleanupStack`：

- 每帧按 cleanup-enter PC 数在发布帧前预留 continuation 容量（`FrameLayout::cleanups`），进入区域与失败恢复都不再分配。
- 失败时在同一 dispatch 循环内恢复：弹出区域所属帧之上的帧（逐帧报告 trace、释放存储），所属帧回到进入时的 scope/statement/loop 水位，再进入 finalizer；不递归调用 C++ invoke。finalizer 完成后在原最内层位置重新抛出首个错误；finalizer 自身失败时丢弃后续错误、保留首错及其 trace。
- return/break/continue 与 result、location、return_location 在 finalizer 前保存清除、之后恢复；`on_error=true` 且正文成功时跳过 finalizer。
- 宿主重入的每个调用根只使用自己进入后建立的区域；外层暂停帧的区域不会被内层失败消耗。

验证：`scalar-vm-tests` 新增 17 个 cleanup 差分（正常/on-error、正文/finalizer 失败、首错优先、flow 保存、嵌套、作用域与循环水位、callee 失败回到调用者区域），均对旧树执行器逐预算扫描比较错误、位置、剩余预算、返回值和 global；`vm-call-tests` 新增宿主重入隔离 3 组。对无重抛、无作用域回收、无位置恢复、无循环深度恢复及区域跨调用根 5 种故意错误实现逐一确认测试失败。普通与 ASan/UBSan 定向测试通过。

未完成：`CleanupActions`（析构/工厂/比较器的脚本 continuation）、语句临时量与对象所有权清理需要 R11 对象模型；编译器只在构造/析构包装中生成 cleanup 节点，源码级差分要等对象执行接入后才能覆盖。

## 标量 VM 与树后端的首次 Release 对照

目的：在对象路径接入前，确认寄存器 VM 对循环与调用的方向和量级，用于定位 R17 热点。**不是**计划第 4 节的采用评分：只选 scalar 与 calls 两组（子集不计核心分数），参考是同一 Release 构建中的树后端（隔离后端变化），不是冻结的 heap-growth-only 参考；VM 尚不能运行对象与虚调用组。

方法：`build/runtime-perf/release`（GCC 13.3.0 Release，无 JNI）中的 `runtime-benchmark-host`（tree）与 `runtime-benchmark-vm-host`（vm，默认 128 MiB workspace）；`tools/bench_runtime.py --cases integer_loop,branch_loop,call_0,call_1,call_4,call_16`，参考端校准 250 ms 批次，7 轮 × 5 批、交替顺序的独立进程，期间没有其他编译或测试。全部观察的结果与独立 oracle/校验和一致。原始数据 `build/runtime-perf/vm-vs-tree-release-1/`（不入库）。

| case | tree µs/call | vm µs/call | 中位提速 | 逐轮配对范围 |
| --- | --- | --- | --- | --- |
| integer_loop medium / large | 1285.5 / 10435.7 | 732.1 / 5552.8 | 1.86× / 1.91× | 1.41–1.91 / 1.68–2.40 |
| branch_loop medium / large | 1560.5 / 13171.6 | 793.2 / 6400.5 | 1.95× / 2.01× | 1.11–2.28 / 1.82–2.48 |
| call_0 medium / large | 1384.8 / 10119.2 | 608.1 / 4618.3 | 2.23× / 2.20× | 2.03–2.76 / 1.95–2.38 |
| call_1 medium / large | 1884.6 / 15136.2 | 826.3 / 6272.5 | 2.32× / 2.29× | 2.01–2.99 / 2.20–2.97 |
| call_4 medium / large | 2472.2 / 20583.6 | 1112.4 / 9012.9 | 2.19× / 2.33× | 2.00–2.59 / 1.93–2.46 |
| call_16 medium / large | 5875.3 / 47034.2 | 2397.5 / 19177.3 | 2.49× / 2.34× | 2.19–2.58 / 1.74–2.56 |

medium/large 几何平均：scalar 1.93×，calls 2.30×；small 规模为 1.98–2.29×。峰值 RSS 两者相同（约 22.4–22.8 MiB 进程高水位，不是 VM 工作区）。large 整数循环仍约 1.36 µs/迭代，说明独立 tick 指令、引用寄存器读取和通用 Value 运算仍有可观成本；按计划这些留给 R17 的受检 fast path，不在此提前改变预算或检查。

## R05 Value 在未优化构建中的回退及修复

首次完整 sanitizer 验证在 `tests/end_to_end.py` 的 `void main(){while(true){}}`（默认 1,000,000 步）上超过该用例的 10 秒进程超时。同一编译源码的实测（Debug，同机）：

| 运行库 | 普通 Debug | ASan/UBSan | Release |
| --- | --- | --- | --- |
| `c2b572d`（main） | 1.29 s | 6.12 s | 0.12–0.20 s |
| `019c440`（R05） / 本分支修复前 | 3.26 s / 3.07 s | 11.0 s | 0.12–0.24 s |
| 本分支修复后 | 1.32 s | 6.16 s | 0.12–0.15 s |

callgrind 显示新增指令几乎全部来自 `vm::Value` 内部 `std::variant` 的拷贝、移动、析构访问表；-O0 下这些模板不会内联，每次树执行器的值复制都要经过多层调用。Release 不受影响，因此 R05 的 Release 基准没有暴露它。修复把存储改为手写 tagged union：同样的 8 种取值、同样的无异常拷贝/移动、赋值先复制/移动再释放旧值（源值可能由当前对象间接持有），`sizeof(Value)` 仍为 24、`sizeof(variable)` 仍为 64。这是安装的 detail 头文件中的内部布局变化；运行时 ABI 1 尚未发布，未改变编号。

## 当前分支完整验证（`50bd4f8`）

- `python3 tools/build_and_test.py --offline`：退出 0（`build/full-normal-3.log`），含真实 JNI、插件、快照、ABI 29 项、VM lowering 桥及 825/825 VM 差分。
- `python3 tools/build_and_test.py --offline --sanitize`：退出 0（`build/full-sanitize-2.log`），ASan/UBSan、无 JNI，VM 差分同样 825/825。两次顺序运行，共用 `build/java/`。
- 这是 R02/R04/R05 改动首次在完整 sanitizer 下通过，但验证对象是当前分支源码，不是冻结的 `r05-source/`。未运行：发行导出搬迁（`tools/export_distribution.py`）以及按冻结 heap-growth-only 参考的正式性能采样。

## R11a 后的四组 Release 对照

同一方法（同一 Release 构建的树后端作参考、参考端校准 250 ms、7 轮 × 5 批交替独立进程、无其他负载），对象与虚调用组现在也由 VM 执行，因此四组都能计分；它仍不是计划的 heap-growth-only 采用评分。原始数据 `build/runtime-perf/vm-vs-tree-release-3/`。

| 组（medium/large 几何平均） | VM / 树 |
| --- | --- |
| scalar | 2.44× |
| calls | 2.60× |
| objects | 1.30× |
| virtual | 1.49× |
| 核心分数（四组各 25%） | 1.87× |

scalar 与 calls 的每一轮配对比值都大于 1.4；objects/virtual 的个别配对低于 1（最低 0.83），对象帧仍使用树运行时的 variable、environment 与 shared_ptr 记录，是 R17 的主要热点。

过程中发现并修复的回退：首次接入对象帧后（`vm-vs-tree-release-2`），VM 的标量/调用比值降到 1.61×/1.86×，与 `50bd4f8` 的同机 A/B 显示 VM 自身慢约 10–15%（寄存器增加 variable 引用后，每次写寄存器先清空再赋值两次）。改为只在原寄存器是引用时清空，并给 `Value` 拷贝/移动赋值增加“两侧都不含引用计数取值”时的直接复制路径后，同一 A/B 中 VM 比 `50bd4f8` 快约 20–30%。该 `Value` 路径也被树执行器使用。

## R11a 对象帧

设计：对象 payload 仍是 `slot_block`，heap 对象仍在 `heap::`。准备阶段把含对象 opcode、对象签名、heap 内建（alloc/object_alloc/mem_free/make_free/mem_send_up/mem_get）、owned return 或内部对象入口调用（构造、析构、工厂）的函数，以及**静态调用图上它们的全部调用者**归为对象帧：`ro` 与 `mem_send_up` 把所有权转入调用者作用域，`mem_free` 沿调用者链查找所有者，所以调用者也需要作用域记录。纯标量函数保留原快速路径。

对象帧使用树运行时自身的所有权记录：每帧一个 `function_frame`（局部在 `slots` 中、语句临时量），每个 VM 作用域一个 `environment`（parent 为外层作用域，函数根的 caller 为调用点作用域）。寄存器增加第三种引用：树运行时 variable（对象局部、字段、heap 槽，含 `mem_get` 结果）。各 opcode 按树执行器的同一顺序调用 `blocks::`/`heap::` 帮助函数（store、materialize、export_result、move_out、drop、register/delete、set_methods），因此复制、移动、借用、owned return、析构顺序、构造失败回滚与地址失效都与树执行器共享实现。普通 CALL 和虚调用仍在同一 dispatch 循环中压入显式帧；虚调用按方法表的函数 ID 选择 VM 函数，并在调用者中检查声明的返回类型。

错误路径：对象帧离开时先保存在途错误，按内到外结束语句临时量和作用域（可能运行析构），恢复 trace 后再报告本帧，与 `invoke_bound` 的 with_cleanup 顺序一致；cleanup 区域恢复时先由区域捕获首错，再结束正文内的作用域和临时量。

尚未 continuation 化：`blocks::`/`heap::` 帮助函数内部的析构仍通过 `invoke_bound_function` 进入新的 Session 调用根（受原生栈保护和逻辑调用深度约束），这不满足计划中 R10/R11 “析构使用 continuation、不递归”的终态，记为 R11b。为此这些帮助函数被 Session 的运行时括号包围：嵌套调用开始时才发布外层 VM 的 global，嵌套调用发生过才在之后重新导入（共享槽已含脚本写入，重新导入用于恢复宿主侧变化与本调用最终发布）。对象帧的所有权记录由树运行时分配，**尚未计入 VM workspace**。

仍明确拒绝（准备阶段，不回退）：隐藏上下文的泛型函数/调用/析构/方法、context/buffer/value_compare/check_object 指令、`load_extern_library`、`reflect_invoke_function`、对象签名的宿主回调。手写 wire 才能出现的两处新增差异：对象值写入 global 或标量帧槽会以 `invalid_argument` 拒绝（树执行器会复制进 global）；宿主回调返回对象值被拒绝。

迁移验收入口：`AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1` 时 `script::flush()` 在首次初始化前选择 VM（默认 workspace），覆盖 CLI、C++ 宿主与 JNI；仅用于迁移验收，生产默认仍为树执行器。`tools/vm_suite_triage.py` 用包装 runner 先以 VM 运行各端到端套件，只有 VM 在准备阶段明确拒绝时才对该次调用改用树后端并记录。

验证（Debug）：

- 独立进程差分新增 20 个 `objects` 阶段源码夹具（作用域析构顺序、值复制、返回移动、临时实参、复制实参、手动 heap 对象、继承构造/析构顺序、字段构造失败回滚、作用域退出析构失败、首错保留、虚调用及交替 receiver、循环 break 的对象、原始 heap 作用域/所有权上送/释放、局部方法、析构读写 global、析构中宿主重入）。全部 57 个案例 2310/2310 对进程观察逐字段一致（含逐预算扫描、heap 槽、对象登记、方法表数、析构事件），ASan/UBSan 下重放同样通过。去掉嵌套调用前发布或之后重新导入两种故意错误实现都被夹具识别。
- 强制 VM 直接运行（含真实 JNI）：math、end_to_end、classes、literal objects、loops、address、numeric slots、hint linking 套件全部通过。
- `vm_suite_triage.py`（无 JNI）：全部 19 个套件退出 0；1151 次调用由 VM 执行并满足套件预期，214 次因泛型/buffer/反射/插件被拒绝后改用树后端。ASan 构建下对象相关套件同样全部通过、无 sanitizer 报告。
- 普通与 ASan/UBSan 构建的 ctest 全部通过。
- 在 `0cf525d` 上顺序运行完整验证：`build_and_test.py --offline` 退出 0（`build/full-normal-4.log`），`--offline --sanitize` 退出 0（`build/full-sanitize-3.log`）；两者都包含 57 个案例、2310/2310 对 VM 差分。

## 下一步

1. R11b：析构、工厂、placement 等帮助函数内的脚本调用改为 `CleanupActions` continuation，并按 [STORAGE_DESIGN.md](STORAGE_DESIGN.md) 第 3 节迁移 owner/owner_env/temp_frame 身份；对象帧的所有权记录计入 workspace。
2. R12 起接入泛型上下文、buffer、比较器与反射，使 triage 中的拒绝数降为 0；R16 前不切换默认后端。R17 处理对象帧仍沿用树运行时记录带来的开销。
3. 宿主可配置的 VM workspace 上限（C++ 及 Java getter/setter）与 VM 成为宿主可选后端同时加入。

## 环境

每个 shell 激活 `/workspace/azscript-toolchain/activate.sh`。Java 验证清除 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS` 和 `_JAVA_OPTIONS`，避免环境横幅污染有意检查的 stderr。普通和 sanitizer 全量测试顺序运行。性能测量使用独立 Release 构建，并避开其他编译/验证负载。

可重用云环境配置草稿已保存，尚未发布。工具安装按原配置固定摘要校验；本轮没有引入额外服务或密钥。

## 结论限制

当前尚无候选性能提速结论。R02 的增长计数与真实脚本吞吐测量分别记录；新的访问接口本身不证明寄存器 VM 已完成。

## 已运行的定向检查（R01–R05）

- Release 基准工具：10 个 case × 3 个规模，60 次独立进程正确性 smoke 通过；Debug 候选同样通过。smoke 不计算采用分数。
- `ctest --test-dir build/native --output-on-failure`：6/6 通过，包括 runtime、堆增长、类型化访问、Value 及真实 JNI。
- Value：实际 `sizeof(Value)=24`、`sizeof(variable)=64`；旧 variable 是 56 字节。标量 payload 被内联，但不能称 wrapper 本身缩小。10,000 次标量存储/拷贝循环为 0 次分配，单次 `deepCopy` 仍有 1 次 shared_ptr variable 外壳分配。
- 堆范围表实测：1,000/4,000/20,000 次连续分配的扩容次数分别 11/13/16，累计搬移 1,023/4,095/32,767；5 个真实 `bad_alloc` 注入点均保持已有状态。
- 同一堆回归链接单独的旧实现诊断副本：1,000 次分配扩容 1,000 次、搬移 499,500 条，被增长界限拒绝。此副本仅增加容量观察，冻结计时库未改动。
- 差分证据：`build/runtime-perf/r03-candidate-r05/report.json`；冻结基线自对照和故意错误候选分别保存在 `r03-self-baseline-complete/` 与 `r03-negative-control/`。
- 初次集成发现测试中的三处旧字段读取和发行文档未复制新 ABI 文档；已修复，并重新启动统一完整验证。

仅修复堆增长的第二对照源码固定为基线 SHA 加 `heap-growth-only.patch`；补丁 SHA-256 为 `391fb83eaece31e3607d6a513062dc2e8636e7860f0b8d787adb8af49cddd62d`。所有原始结果保存在忽略目录 `build/runtime-perf/`，后续报告须明确实际构建身份。

R01/R03 基础设施提交 `f8ab415`；R02/R04/R05 生产基础提交 `019c440df889881caca6b4c405b4533fd9d0bdca`，对应源码再次冻结在 `build/runtime-perf/r05-source/`。ABI 专项实际 29 项检查通过。

R05 完整普通验证已退出 0，日志 `build/runtime-perf/candidate-validation.log`，末尾为 `Build and all selected tests passed`；包括真实 JNI、插件、快照和新增 ABI 29 项检查。后续生产编辑已解除冻结，R05 sanitizer/发行/性能证据将继续引用不可变 `r05-source/`，不与新 VM 工作混用。

R07/R08 独立审查修复并回归三项内部问题：CFG 实际作用域嵌套超过线性文本深度引起的容量越界；无 tick 循环绕过执行预算；普通分支进入 finalizer 时缺少 cleanup continuation。以上检查证明这些具体路径已修复，不代表全部 VM 对象和宿主语义已覆盖。

性能采样已按暂停要求终止，无遗留 host；已完成观察无正确性失败，但不足规定 7 轮。新源码与交接文档作为 WIP 检查点提交，未继续实现、构建或推送。
