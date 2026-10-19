# JIT 实施计划

日期：2026-10-19（UTC）。适用分支：`claude/runtime-performance-handoff-5mdt6h`。设计见 [DESIGN.md](DESIGN.md)，实际进度和验证证据见 [STATUS.md](STATUS.md)；三者冲突时以设计为准并同步修订本计划。

每个阶段由一个或多个完整提交组成，提交前运行定向回归，阶段交付前运行本文第 2 节的全部验收；未完成的检查在 STATUS 中写明，不沿用历史结论。起点：执行器目录调整（`0df5547`）之后。

## 1. 阶段

| 阶段 | 内容 |
| --- | --- |
| J0 | 设计、计划与状态文档（本目录） |
| J1 | 只加 VM 钩子：层槽、`end` 阈值、计数器、冷路径 `tier_dispatch`；仅供测试的探针层按固定种子扰动阈值，不含原生代码 |
| J2 | 抽象解释（寄存器种类与标签、局部声明与次序、作用域标记、活跃性、影子表示）与 `jit-analysis-tests`；可编译性原因随 J3 的统计旁路输出 |
| J3 | MIR、构建器与校验器、退出映射、辅助函数、解释后端：标量值、控制标志与剩余步数放入影子，cell 分配/引用/释放经辅助函数按 VM 原次序直写，未内联的运算经 `value_ops` 辅助函数，其余指令交 VM 单步执行；eager/stress 模式；统计旁路 |
| J4 | 优化：更多内联快路径、块预算合并、虚引用（省去引用计数辅助调用）、global 守卫与去优化重编 |
| J5 | 固定版本 LLVM 构建、模块、装载器、ORC 宿主、MIR→IR（x86-64） |
| J6 | `on` 策略、OSR、去优化重编调优、代码量上限 |
| J7 | AArch64：交叉工具链、qemu 运行、AArch64 代码生成修正 |
| J8 | 对象帧；之后是快速调用、内联与虚调用缓存 |

## 2. 每个阶段的验收

- 每个阶段顺序运行 `python3 tools/build_and_test.py --offline` 和 `--offline --sanitize`；sanitizer 构建使用完全插桩的解释后端。
- **多方一致。** tree / vm / vm+interp / vm+llvm，各自在 `eager` 与 `stress` 模式下，在 `tools/backend_alignment.py`、`tests/vm_execution_end_to_end.py`（全部阶段、完整预算扫描）和 `tests/lifecycle_fuzz.py`（种子 0–199）中逐字节一致；JNI 回归在 `AZSCRIPT_JIT=eager` 下运行。
- **Workspace。** 经统计旁路比较 vm 与 vm+jit 的 used/peak；在池页和 cell 向量增长边界附近扫描 `--workspace-bytes`。
- **操作网格。** 每个运算 × 标签组合，以及 INT_MIN/−1、%−1、±0、NaN、FLT_MAX 附近、移位 −1/31/32、地址回绕，解释后端与 LLVM 后端均与 `value_ops` 对照。
- **升层扫描。** 阈值取 1..K 时观察结果相同。
- **JIT 变异体。** 在 `tools/conformance_mutants.py` 中以引擎 `jit` 登记，由同一构建的 vm 与 vm+jit 实时比较判定：tick 少扣一次、未加守卫的 `srem`、以 f32 计算、有序 `ne`、未加守卫的 global 标签、跨 `STEP` 缓存 global、覆盖寄存器而不释放、释放次序错误、先释放后分配、缺少引用物化、过期的 `end`，全部必须被捕获。
- **防止空转。** 门槛运行使用 `require` 模式；eager 模式下按语料检查原生执行指令占比的下限。
- **性能。** `AZSCRIPT_JIT=off` 时 VM 的 callgrind 增幅不超过 0.5%；新增 `vm-jit` 宿主逐项报告；`tools/bench_runtime.py` 计入编译时间测量墙钟；目标（integer_loop/branch_loop 至少为 VM 的 10 倍）及实际结果记入 STATUS。
- **AArch64。** 在 qemu 下运行 ctest、一致性子集和操作网格。


## 3. 与运行时性能计划的关系

- JIT 是 VM 引擎的一层：关闭时 VM 行为与性能不变（callgrind 门槛），不新增允许差异，也不影响树执行器的删除计划。
- 运行时性能计划中尚未完成的 R13–R28 与 S3.4/S5.x 照常进行；它们修改 VM 时，JIT 的钩子（[DESIGN.md](DESIGN.md) 第 5 节所列四处）与辅助函数一并调整，并重新运行本计划的验收。
