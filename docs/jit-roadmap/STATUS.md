# JIT 实施记录

本文件记录 JIT 各阶段实际完成的内容和验证证据；设计见 [DESIGN.md](DESIGN.md)，阶段与验收见 [PLAN.md](PLAN.md)。未运行或未通过的检查明确写出，不沿用历史结论。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| J0 | 完成 | 设计、计划与本记录 |
| J1 | 已提交，完整验证待运行 | 见下文“J1：VM 钩子与探针层” |
| J2 | 已提交 | 见下文“J2–J3：分析与可移植原生层” |
| J3 | 已提交，完整验证待运行 | 全部 463 个端到端案例在 eager 与 stress 下与树执行器一致 |
| J4–J8 | J5 代码已写，LLVM 构建进行中 | |

## J0：设计（2026-10-19）

用户要求基于现有 VM 执行器设计 JIT，并选择 x86-64 与 AArch64、LLVM ORC、第一个里程碑即含优化层、单独的 JIT 模块、固定版本 LLVM 源码构建、在本环境用 qemu 验证 AArch64。三份只读调查（VM 执行器、运行时约束、构建与测试设施）和一次设计评审核对了源码；评审纠正了五处初稿假设：寄存器引用使 cell 的回收时机可观察（分配/保留/释放必须在 VM 原指令处按原次序进行）；global 必须留在内存中；宿主调用结果与 `ANY` 形参需要类型守卫；观察协议拒绝未知字段，统计须走旁路；帧入口并不预留全部 workspace（cell、槽池与宿主快照仍会增长）。

## J1：VM 钩子与探针层（2026-10-19）

| 提交 | 内容 |
| --- | --- |
| `build: scan the tree engine's sources for raw exception storage` | 目录调整把树执行器移到 `interpreter/tree/` 后，错误存储检查不再扫描这些文件；补上 |
| `feat(vm): consult the JIT tier at frame bounds and loop back-edges` | `FrameRecord::end` 改为层选择的界限：函数结尾，或冷路径 `reach_bound` 查询层的更早位置（真正结尾处仍抛出原 `logic_error`）；层要求时报告循环回边。`AZSCRIPT_JIT` 在创建 VM 会话时选择 `off` 或仅供测试的 `probe[:seed]`（按种子伪随机移动界限，不得改变任何观察） |

验证（仅定向，完整验证待运行）：

- `ctest` 16/16 通过；`jit-tier-tests` 检查配置解析、界限范围与种子确定性；标量差分在每个预算扫描点再用两个探针执行器对照树执行器。
- 反向对照：让钩子在探针模式跳过 tick 后，标量差分立即失败（`integer probe 1 budget=0: error source differs`）。
- `AZSCRIPT_JIT=probe:3` 下 `vm_execution_end_to_end --stage calls`：18 案例、426 对独立进程一致；无效的 `AZSCRIPT_JIT` 值在 flush 时被拒绝。
- callgrind（Release，层关闭，相对 `0df5547`）：integer_loop +0.013%、branch_loop +0.007%、call_0 +0.097%、call_1 +0.104%、call_16 +0.034%、object_fields −0.074%、object_copy −0.034%、virtual_monomorphic −0.115%、virtual_alternating −0.089%，均在 +0.5% 门槛内。
- `build_and_test.py` 新增两项门禁：以 `AZSCRIPT_JIT=probe:7` 重放树参考的 `vm_execution_end_to_end`；`backend_alignment.py --jit probe:11` 第三次运行每个调用，须与 VM 逐字节一致。

与设计的差异：J1 未加入入口/回边计数器——探针层不需要，计数器随 J6 的 `on` 策略加入。

## J2–J3：分析与可移植原生层（2026-10-19）

| 提交 | 内容 |
| --- | --- |
| `feat(jit): analyze scalar functions for the JIT tier` | 对每个已验证标量函数做抽象解释：寄存器为值（及可能标签）、指向局部当前 cell 的引用、global 引用、孤立引用或未知；局部为已声明、未声明或可能已声明（附声明深度）；语句、循环与作用域深度静态已知。结果标签由 `value_ops` 本身对代表值采样得到；不含字符串的寄存器/局部可放入影子字。对象帧、生命周期函数、清理区域、超大函数及汇合点两侧声明深度不同的局部不可编译 |
| `feat(jit): run scalar functions natively on the portable backend` | MIR 构建器与校验器、退出点、`FrameAccess`（执行器实现，每个结构操作与 VM 指令相同）、`value_ops` 快路径与通用路径、可移植解释后端；`AZSCRIPT_JIT=eager|stress`；统计旁路 `AZSCRIPT_JIT_STATS`；`build_and_test` 以 probe/eager/stress 重放树参考并要求原生代码确实运行，`backend_alignment --jit` 接受多个模式 |

实现中修正的设计假设：声明列表与作用域标记不是静态的——语句可在块内后续声明执行前经 `branch_flow` 离开该块，汇合点两侧声明不同。因此局部改为三态，`declared[]`、作用域标记、`locals[]` 和 cell 的分配/释放由辅助函数在 VM 原指令处直写内存，退出只写回仍有影子的值、标志、步数、语句/循环深度和 pc。影子标签 `absent` 表示该槽以 VM 内存为准。

验证（定向；完整 `build_and_test` 与 sanitizer 待运行）：

- `ctest` 18/18；`jit-analysis-tests` 45 项；`jit-operation-tests` 63,284 项（每个二元/一元运算与转换 × 每对标签 × 边界值，结果位与失败位置均与 `value_ops` 一致）。
- 标量差分在每个预算扫描点再以 eager 与 stress 执行器对照树：18,856 次原生进入、63,233 个 MIR 操作，0 次标签越界。
- `vm_execution_end_to_end` 全部阶段（scalar/calls/objects/ownership）：树参考 463 案例 / 55,460 个独立进程；以同一参考重放：
  - eager：55,460 进程一致；17,323 次函数编译，119,625 次原生进入，4,817,681 个 MIR 操作，111,555 次 STEP、8,070 次 REDO，0 次拒绝，0 次标签越界。
  - stress：55,460 进程一致；4,169,807 次原生进入（每条指令一次，4,050,182 次 YIELD），9,862,077 个操作，0 次标签越界。
  - 不可编译原因：对象帧 335,515、生命周期函数 8,153（按会话计）。
- 解释后端只用于正确性与 sanitizer，不追求速度；性能来自 J5 的 LLVM 后端。
