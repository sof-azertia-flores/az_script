# JIT 实施记录

本文件记录 JIT 各阶段实际完成的内容和验证证据；设计见 [DESIGN.md](DESIGN.md)，阶段与验收见 [PLAN.md](PLAN.md)。未运行或未通过的检查明确写出，不沿用历史结论。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| J0 | 完成 | 设计、计划与本记录 |
| J1 | 已提交，完整验证待运行 | 见下文“J1：VM 钩子与探针层” |
| J2–J8 | 尚未开始 | J2 抽象解释进行中 |

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
