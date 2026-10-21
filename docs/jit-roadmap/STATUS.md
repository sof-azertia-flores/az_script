# JIT 实施记录

本文件记录 JIT 各阶段实际完成的内容和验证证据；设计见 [DESIGN.md](DESIGN.md)，阶段与验收见 [PLAN.md](PLAN.md)。未运行或未通过的检查明确写出，不沿用历史结论。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| J0 | 完成 | 设计、计划与本记录 |
| J1 | 完成 | 见下文“J1：VM 钩子与探针层”；完整验证见“合并 main 与完整验证” |
| J2 | 完成 | 见下文“J2–J3：分析与可移植原生层” |
| J3 | 完成 | 端到端语料在 eager 与 stress 下与树执行器一致；完整验证见“合并 main 与完整验证” |
| J4 | 两轮已提交，进行中 | 见下文“J4 第一轮”与“J4 第二轮”；callgrind 下 integer_loop 为 VM 的 17.2 倍 |
| J5 | 完成 | 见下文“J5：LLVM ORC 后端”；端到端语料与后端对齐在 eager+llvm、stress+llvm 下一致 |
| J6 | 完成 | 见下文“J6：升层策略”；阈值 1–4 的全语料重放一致 |
| J7 | 已提交 | 见下文“J7：AArch64（qemu 模拟）”；ctest 18/18 与 scalar/calls/objects 语料在模拟器下一致 |
| J8 | 尚未开始 | |

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

## J5：LLVM ORC 后端（2026-10-19）

| 提交 | 内容 |
| --- | --- |
| `feat(jit): compile MIR with LLVM ORC in a separate module` | `jit/abi.h` 是运行时与 `libazscript-jit` 之间唯一的 C 接口（MIR 为纯数据，运行时只经辅助函数表出现；双方核对 ABI 版本与布局摘要）。`tools/build_llvm.py` 构建固定的 LLVM 18.1.8（源码 SHA-256 `0b58557a…2f2a`，固定时已用 LLVM 发布密钥 474E22316ABF4785A88C6E8EA2C794A986419D8A 校验签名），只含 X86/AArch64 后端、静态库、开启 RTTI；`AZSCRIPT_JIT=AUTO` 在固定前缀存在时构建模块（sanitizer 与 Windows 构建不构建）。模块静态链接 LLVM，只导出 `azscript_jit_backend_v1`，运行时从自身所在目录以 `RTLD_LOCAL` 装载。代码生成：每个槽一对 alloca（mem2reg 后为 SSA），恢复点按分析标签装入影子（单一标签时为常量），两个 int 的加减乘除余与比较内联（溢出与除零转为退出），其余经 `value_ops` 辅助函数；O2 管线、`verifyModule`；JITLink 以 W^X 映射代码，每个函数一个 JITDylib，随会话释放。`AZSCRIPT_JIT_BACKEND=llvm` 或 `eager+llvm` 选择它，模块缺失时会话准备失败；`build_and_test --jit` 先构建 LLVM，模块存在时门禁加入 llvm 模式并拒绝编译失败与标签越界 |

验证（定向；完整 `build_and_test --jit` 与 sanitizer 待运行）：

- 模块构建：Release LLVM 安装 223 MB，模块（Debug 构建、未剥离）72 MB；`nm -D` 只有 `azscript_jit_backend_v1`。
- 标量差分在每个预算扫描点另以 eager+llvm 与 stress+llvm 执行器对照树：53,535 项检查通过，256 次函数编译无一失败；首次运行发现两类缺陷并已修复：只在分析证明不可达的路径上越过最后一个操作的分支（改为 trap 块），以及统计中未转义的拒绝原因。
- `vm_execution_end_to_end` 以树参考重放全部 463 案例 / 55,460 个独立进程：eager+llvm 与 stress+llvm 均一致；17,323 次 LLVM 编译无拒绝，进入/退出计数与解释后端完全相同，stress 下 4,169,807 次原生进入，0 次标签越界。
- 初步性能（Release，机器同时运行其它验证，仅作参考；n=4096，每调用墙钟）：

| 案例 | VM | eager+llvm | 倍数 |
| --- | --- | --- | --- |
| integer_loop | 2,990.5 µs | 335.1 µs | 8.9× |
| branch_loop | 3,620.9 µs | 408.4 µs | 8.9× |
| call_1 | 4,378.1 µs | 2,983.2 µs | 1.5× |
| call_16 | 12,667.2 µs | 7,870.0 µs | 1.6× |

首次调用含编译，约 80–170 ms。调用仍经调度器（STEP），循环中每次局部载入仍调用引用辅助函数；这些是 J4 的优化对象。

## J4 第一轮：折叠与只传活跃值（2026-10-19）

| 提交 | 内容 |
| --- | --- |
| `perf(jit): fold snapshots of loads and move only live values` | 载入一个必定已声明的局部后立即快照到同一寄存器（每个二元运算的左操作数）折叠为影子复制：VM 在绑定仍持有 cell 时保留又释放它，只有寄存器旧内容的释放（若是引用）需要执行；进入只装入恢复点活跃的寄存器（及已绑定局部），退出只写回该 pc 活跃的寄存器——VM 在写入之前不会读其它寄存器；活跃性把种类未知的寄存器也视为可能的值；激活记录复用同一缓冲区（原生运行不会嵌套）；`AZSCRIPT_JIT_DUMP` 输出 MIR；`callgrind_gate.py` 增加 `jit` 引擎（VM 宿主加 `AZSCRIPT_JIT=eager+llvm`，`--smc-check=all-non-file`） |

stress 模式下每个 pc 都是块首，不做折叠，因此 stress 覆盖未折叠路径与每个 pc 的活跃值传递，eager 覆盖折叠。

验证（定向）：`ctest` 18/18；标量差分（interp/llvm × eager/stress）53,535 项；`vm_execution_end_to_end` 以树参考重放 463 案例 / 55,460 进程，eager、stress、eager+llvm、stress+llvm 四种全部一致，0 次拒绝、0 次标签越界、0 次编译失败（解释后端 MIR 操作数由 4,817,681 降到 4,480,350）。

callgrind（Release，n=512，每调用指令数，同一构建中 VM 与 eager+llvm 对比）：

| 案例 | VM | JIT | 倍数 |
| --- | --- | --- | --- |
| integer_loop | 4,889,899 | 284,636 | 17.2× |
| branch_loop | 5,767,565 | 384,541 | 15.0× |
| call_0 | 4,736,318 | 2,349,080 | 2.0× |
| call_1 | 6,241,613 | 2,828,761 | 2.2× |
| call_16 | 19,068,555 | 9,019,511 | 2.1× |

调用仍经调度器：每次调用两次原生进入与退出及 VM 的帧建立与清理；循环中剩余开销主要是存储目标与二元右操作数的引用辅助函数调用及作用域辅助函数。

## J6：升层策略（2026-10-19）

| 提交 | 内容 |
| --- | --- |
| `feat(jit): tier up hot functions under AZSCRIPT_JIT=on` | `on[:阈值]` 在函数的入口与循环回边计数达到阈值（默认 1000）时编译，此后从下一次入口或循环头（栈上替换：帧本来就是 VM 的，只需恢复点）原生运行；`on` 默认用 LLVM 后端，模块不可用时 VM 不变并在统计中记录原因，`require` 则使会话准备失败；`+interp` 可选可移植后端。每个会话最多编译 2^22 个 MIR 操作，超出部分保持解释。`build_and_test` 加入 `on:2+interp` 与（有模块时）`on:3+llvm` |

不需要“去优化后重编”：分析是可靠的，标签来自程序本身而非运行时推测（global 与宿主结果按 `top` 处理），因此没有会失败的推测；REDO 只在错误和预算不足时出现。

验证（定向）：`ctest` 18/18；标量差分加入 `on:2` 与 `on:3+llvm` 执行器，预算扫描跨越升层（68,601 项）；`on` 在模块缺失时以 VM 完成并记录 `module unavailable`，`require` 在 flush 时失败。以树参考重放 463 案例 / 55,460 进程，阈值扫描全部一致、0 次拒绝、0 次标签越界、0 次编译失败：

| 模式 | 编译函数 | 原生进入 | STEP | REDO |
| --- | --- | --- | --- | --- |
| on:1+llvm | 17,323 | 119,625 | 111,555 | 8,070 |
| on:2+interp | 12,004 | 81,371 | 75,255 | 6,116 |
| on:3+llvm | 8,713 | 68,639 | 63,315 | 5,324 |
| on:4+llvm | 7,193 | 60,663 | 55,994 | 4,669 |

## J7：AArch64（qemu 模拟）（2026-10-20）

| 提交 | 内容 |
| --- | --- |
| `test: build and test the JIT for AArch64 under qemu` | `cmake/toolchains/aarch64-linux-gnu.cmake`（GNU 交叉工具链，`CMAKE_CROSSCOMPILING_EMULATOR` 为 `qemu-aarch64 -L /usr/aarch64-linux-gnu`）；`tools/emulated_test.py` 以模拟器包装交叉构建的观察宿主，收集树参考、比较 VM，并以每个 JIT 模式重放；每个模式在所有阶段合计必须有原生代码运行 |

环境：本容器经 apt 安装 `qemu-user` 与 `g++-aarch64-linux-gnu`（含 `/usr/aarch64-linux-gnu` sysroot）；未注册 binfmt，因此需显式包装。固定版本 LLVM 以主机的 `llvm-tblgen` 交叉构建到 `build/deps/llvm-18.1.8-aarch64-linux-gnu/`（220 MB）。

验证（模拟，Debug 构建，JNI 关闭）：

- ctest 18/18 在 qemu 下通过；其中标量差分的 LLVM 执行器在 AArch64 上编译 384 次（6 个执行器 × 64 个函数），完整预算扫描与树一致，0 次标签越界；`jit-operation-tests` 63,284 项通过。
- `tools/emulated_test.py`（scalar/calls/objects，树参考在模拟器上收集）：

| 阶段 | 进程 | eager | stress | eager+llvm | stress+llvm | on:2+llvm |
| --- | --- | --- | --- | --- | --- | --- |
| scalar | 399 | 1,233 | 67,438 | 1,233 | 67,438 | 158 |
| calls | 426 | 1,587 | 24,435 | 1,587 | 24,435 | 442 |
| objects | 1,606 | 39 | 183 | 39 | 183 | 0 |

（表中为原生进入次数；VM 与全部模式结果与树参考逐字段一致，0 次标签越界、0 次编译失败。objects 阶段在 on:2 下没有函数变热，覆盖检查因此改为按模式跨阶段合计。）

未覆盖：所有权阶段与 19 个端到端套件未在模拟器下运行；arm64 JNI；真实 arm64 硬件与 macOS arm64（MAP_JIT）。

## J4 第二轮：影子直接传递与原生规模上限（2026-10-20）

| 提交 | 内容 |
| --- | --- |
| `perf(jit): move shadows between the frame and native code directly` | 进入与退出时寄存器和局部的值以影子形式直接由 VM 的 `Value` 构造或写回（`FrameAccess` 的 `register_shadow`/`set_register_shadow`/`local_shadow`/`set_local_shadow`），不再经中间 `Value` 复制 |
| `fix(jit): keep functions too large for LLVM on the VM` | LLVM 编译时间随函数规模超线性增长（O2 下 1,500 个 MIR 操作约 0.4 s、3,000 个 1.6 s、6,000 个 8 s、12,000 个 46 s），`limits_end_to_end` 中 10,000 项的字符串链在 eager+llvm 下超时；超过 2,048 个操作的函数留给 VM，统计记录 `too large for native code`。可移植后端不受此限 |

验证（定向）：`ctest` 18/18；以树参考重放 463 案例 / 55,460 进程，eager、stress、eager+llvm、stress+llvm 全部一致。包含这两项提交的完整验证（`build_and_test.py --offline --jit` 与 `--sanitize`）在提交本记录时仍在运行，结果另行记录。

callgrind（Release，n=512，eager+llvm 每调用指令数，相对第一轮）：

| 案例 | 第一轮 | 第二轮 | 变化 |
| --- | --- | --- | --- |
| integer_loop | 284,636 | 284,619 | 0.0% |
| branch_loop | 384,541 | 384,473 | 0.0% |
| call_0 | 2,349,080 | 2,256,306 | −3.9% |
| call_1 | 2,828,761 | 2,703,396 | −4.4% |
| call_16 | 9,019,511 | 8,440,796 | −6.4% |

## 合并 main 与完整验证（2026-10-21）

| 提交 | 内容 |
| --- | --- |
| `Merge main into the JIT branch` | main 带来编译期优化（`-O1`）、VM 批量预算检查、`azscript-run` 的后端与步数选项及默认不限逻辑调用深度。VM 的构造与调度循环同时保留层和预算批次。批次在直线段内不检查层的界限，可能越过阈值；层本来在任意 pc 都可被查询，DESIGN 已注明 |
| `test: run the optimized compiler corpus under the JIT` | `build_and_test` 以 stress（有模块时为 stress+llvm）再运行一次 O1 编译语料，覆盖、编译错误与标签检查与重放相同（`check_jit_stats`）。合并前手工试过 stress、eager+llvm、stress+llvm、on:2+llvm 四种模式，全部一致 |
| `docs: mention the JIT tier and the --jit build in the README` | README 说明默认关闭的 JIT 层、`AZSCRIPT_JIT=on` 与 `--jit` 构建；发行包暂不含模块 |
| `fix(jit): load the tags of maybe-declared locals on LLVM resume` | 声明可能被 break、continue 或 return 跳过的局部，在之后的恢复点“可能已声明”，进入时可能缺失。LLVM 恢复块在分析只允许一种标签时用常量代替实际标签，使缺失的局部变成存在，退出写回到没有 cell 的局部而终止进程。现在只有进入时必定存在的寄存器沿用推定标签。新增语料案例 `flow-skips-declaration`：旧构建在 stress+llvm 下终止，修复后全部模式在完整预算扫描下与树一致 |
| `test: run each alignment invocation's engines concurrently` | 对齐包装器原先在套件自己的单次超时内依次运行树、VM 和 JIT。sanitizer 下 `end_to_end` 的 `while(true){}` 运行到 1,000,000 步上限：树约 5.0 s、VM 1.4 s、stress 6.5 s，超过 10 s。各次运行互不依赖，现在并行，超时只需覆盖最慢的一次 |
| `fix(jit): count resume entries in the native size cap` | 每个恢复点装入全部 slot；stress 下每个 pc 都是恢复点，LLVM 编译时间随恢复点数×slot 数超线性增长：100 项的 `1+…+1`（103 个寄存器、405 个恢复点、1,719 个操作，未超过操作上限）编译 16.5 s，超过 `end_to_end` 的 10 s。`fits_native` 另限恢复点×slot 不超过 8,192（约 1.4 s），超出者留给 VM，`jit-tier-tests` 检查两个上限 |

两项缺陷都由完整验证中的后端对齐发现（stress+llvm 下 math、containers、virtual_methods、limits 套件终止；随后是上述编译超时），此前的定向重放语料中没有对应的代码形状。

完整验证（`c77020b`，普通构建，`python3 tools/build_and_test.py --offline`，固定版本 LLVM 已在前缀中）：

- 全部普通套件、`ctest` 19/19、O1 编译套件（28 个案例；语料 464 个案例 / 1,856 个观察）。
- VM 对树：464 个案例 / 55,781 个独立进程（含完整预算扫描）一致。
- 以树参考重放，全部一致、0 次标签越界、0 次编译失败：

| 模式 | 编译函数 | 原生进入 |
| --- | --- | --- |
| probe:7 | — | — |
| eager | 17,960 | 121,570 |
| stress | 17,960 | 4,387,233 |
| on:2+interp | 12,283 | 81,848 |
| eager+llvm | 17,960 | 121,570 |
| stress+llvm | 17,781 | 4,324,349 |
| on:3+llvm | 8,971 | 69,095 |
| O1 语料，stress+llvm | 363 | 57,071 |

- 后端对齐：probe:11、eager、stress、on:2+interp、eager+llvm、stress+llvm、on:3+llvm 七种模式各 1,376/1,376 次调用逐字节一致。

sanitizer（`--sanitize`，不构建 JIT 模块）：完整运行在合并头 `8c577c8` 上，除 stress 对齐因上述超时 1,359/1,360 外全部通过，其中包括 VM 对树（55,460 个进程）及 probe:7、eager、stress、on:2+interp 四种重放；改用并行包装器后重跑的 stress 与 on:2+interp 对齐各 1,376/1,376。此后的两项修复只改 LLVM 模块与原生规模上限（无模块时不生效），因此在 `c77020b` 上只重新构建 sanitizer 并运行 `ctest`（18/18），未再完整运行 sanitizer 语料（不含新增的 `flow-skips-declaration`）。

## 发行包中的 JIT（2026-10-21）

用户询问如何在发行包中启用 JIT，并选择“导出时以 `--jit` 显式包含”和“运行器增加选项”。此前发行包是否含模块取决于 `build/deps` 中是否恰好有固定版本 LLVM（CMake `AUTO`），既无文档也无验证；运行时只能设置环境变量。

| 提交 | 内容 |
| --- | --- |
| `feat(distribution): ship the JIT with --jit and enable it with run --jit` | `azscript-run --jit[=on|require|off]`（`on:N`、`require:N` 设阈值），隐含 `--vm`，与 `--tree` 冲突，优先于继承的 `AZSCRIPT_JIT`；`require` 缺少模块时的错误改为 `The LLVM JIT backend is unavailable: …`。`export_distribution.py --jit` 构建固定版本 LLVM 并要求包内有模块，否则以 `AZSCRIPT_JIT=OFF` 构建；`manifest.json` 增加 `jit`；第三方说明增加 LLVM，附 `licenses/LLVM-LICENSE.txt`（原样取自已校验的源码包，`.gitattributes` 对许可证文本不做空白检查）。`test_distribution.py` 按包内是否有模块检查 `--jit`、`--jit=on:2`、`--jit=require` 及错误选项，并清除继承的 JIT 环境变量 |
