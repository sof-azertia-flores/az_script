# exec v11 实施记录

本文件记录 exec v11（O3 及配套更新）各阶段实际完成的内容和验证证据；设计见 [DESIGN.md](DESIGN.md)，阶段与验收见 [PLAN.md](PLAN.md)。未运行或未通过的检查明确写出，不沿用历史结论。

用户于 2026-10-22 暂停后又要求恢复，补齐剩余验证与 O3 性能测试。改动和日志已保留，未提交或推送；前次暂停点见 [HANDOFF.md](HANDOFF.md)。前次统一 quick 已通过 79 个定向案例、460 个完整语料案例及前置套件，但在差分工具自检的 opcode 库存校验处失败（未登记 `54 seq`），不能标为通过。本次已补齐 54–60 并通过自检：24 项检查、61 个 opcode 全登记；随后重建共享产物并执行完整普通验证，结果见下方续作记录。

本轮在原快照优化上增加实参后缀写入分析、seq 局部清理和封闭标量调用的字段缓存保留。424 Java、20 CTest、统一 quick、ASan/UBSan 全语料及变化产物七模式预算补验通过。原有 19 负载的 38 份字节码和 Release 运行库完全不变，保留原 O3/O2 均值 0.874 / 0.530；四个新增定向负载另比较本轮与上轮 O3，实测见文末和 O3 文档。未提交、推送或发行。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| V0 | 完成 | 设计、计划与本记录 |
| V1 | 普通与 sanitizer 完整验证通过 | 见下文 V1 及续作记录 |
| V2 | 已实现，性能验收仍有缺口 | ABI 2、活跃 slot 与流水线测量见下文；多局部最坏编译时间未达到约 1 秒目标 |
| V3 | 已实现并通过普通/sanitizer 集成验证 | 新构造、私有入口、调试链；原生 1,325 检查 × 七 JIT 模式 |
| V4 | 已实现 | O3/关闭选项、显式 O2 阶段集合、分档验证工具；旧产物字节检查通过 |
| V5 | 安全子集已验证 | 表达式位置的直线标量内联、槽复用；复杂控制流候选待解决 seq 契约冲突 |
| V6 | 首批专用化已验证 | 有界常量克隆、完整上下文键与固定 int/bool 泛型具体化 |
| V7 | 安全子集已验证 | 字段缓存、直接/守卫去虚化、保留存储生命周期的字段标量化；分配消除暂不开放 |
| V8 | 已实现并通过普通/sanitizer 集成验证 | 证明后发射 add/minus/multiply/shl；无证明路径仍有检查 |
| V9 | 已实现，性能已测，目标未全达成 | 无退出 int32 MIR、树/VM/七模式原始构造差分通过 |
| V10 | 不在本轮 | 发布收尾 |

## V0：设计与计划（2026-10-21）

用户询问 O3 的可行性后决定：O3 及需要格式变化的优化放入下一个大版本 exec v11，同时包含内联、专用化与对象优化，彻底删除 `getDepth`；先完成完整计划，再由 Claude 自行实施。开发分支 `exec_v11_main`，每个大的更新合入该分支，开发期不改 exec 版本号。

依据：三份只读调查（`getDepth` 与 exec 版本的全部位置；VM/JIT 中新 opcode、虚调用、trace 与规模上限涉及的代码；O2 编译器基础设施）。主要结论已写入 DESIGN 第 1 节和第 4–7 节。

## V1：删除 `getDepth`（2026-10-22）

- **编译器：** 内建表删除 `getDepth`，内建数量 20→19；`0x0abd0001` 永久保留，调用、声明或 extern 均报错，名称 `getDepth` 不再保留（可作为普通函数名）。
- **运行时：** 删除 `depth_function` 与 `ownership::depth`；内建表第 1 项保留为空，`builtin(0x0abd0001)` 为空，链接与 `getFunction` 报 `Unknown function id: 0x0abd0001`；`core/ids.h` 枚举改名 `retired_get_depth`，编号与 `static_assert` 不变。
- **VM：** `ExecutionCallbacks::invoke_external` 不再接收 `lexical_depth`；标量帧的外部调用不再伪造作用域链，只建一个以基础环境为父、带调用者模块 global 范围的帧根作用域（宿主按负编号读取 global 仍限定在调用者模块，与树执行器一致）。
- **测试：** 删除只观察深度数值的用例（`scopes.py` 的 a12、a12g、a12h、a12i，`language_ops` 的 select-current-scope 与 JNI `currentScope`，`compiler_optimization_end_to_end` 的 inline-depth，`compact_exec` 的 independent-select-current-scope）；a12b–a12f 与 `assignment.py` 的 ce11/ce11b 仍检查析构次序、回滚或调用计数，改为记录原深度对应的常量，期望序列不变。O2 测试原用 `getDepth` 作为作用域观察者阻止优化，改用未知宿主函数。`lifecycle_fuzz.py` 删除 `getDepth` 原子（生成摘要随之改变）；`conformance_mutants.py` 删除 `depth-counts-callers`，其余变异体仍覆盖树与 VM；VM 变异体由同一构建的树/VM 对比判定，`--f0-work` 改为可选（只报告）。新增回归：`runtime-tests` 检查保留 ID 不解析，`compact_exec` 检查手写调用 `0x0abd0001` 的 exec 链接失败。
- **文档：** LANGUAGE、README、QUICKSTART 删除 `getDepth` 并说明作用域结构不可观察；O2 说明与 SEMANTICS 表注明已删除；历史路线图记录不改写。

**验证工具（同一提交）：** `tests/vm_execution_end_to_end.py --jobs`（默认 CPU 数）并行运行案例，重放时每个已保存预算单独运行，清单与报告仍按夹具次序；`tools/backend_alignment.py --jobs` 并行运行套件；`build_and_test.py --quick` 供阶段之间使用。本机（12 核）scalar 阶段重放 720 个进程：串行 9.1 s，并行 1.6 s，报告与串行逐字段相同；人为篡改的候选在并行下按首个差异失败。

**实际运行的检查（macOS arm64，Debug，含 LLVM JIT 模块）：**

- 完整 `build_and_test.py --offline --jit`（修改测试工具之前，日志 `build/v11/v1-normal.log`）：375 项 Java 测试、ctest 19/19（含真实 JNI）、全部跨语言套件、O2 套件（56 案例 / 989 观察，七种 JIT 模式）、O1/O2 编译语料、树/VM 多预算差分 460 案例 / 55,451 对独立进程、七种 JIT 模式重放全部通过。用户要求缩短验证后，在运行 stress+llvm 下的 O1 语料与七轮后端对齐之前停止。
- 并行后端对齐（stress+llvm，`--jobs 4`）：1,375/1,375 次调用一致，墙钟 283 s。
- `build_and_test.py --offline --jit --quick`（`build/v11/v1-quick.log`）：375 项 Java 测试、ctest 19/19 与全部跨语言套件通过；O2 套件（56 案例 / 564 观察，stress 与 stress+llvm）通过；在 O1/O2 编译语料一步因用户要求暂停而停止。
- **注意：** 上述 LLVM 模式的结果使用了 `build/native` 中遗留的旧 `libazscript-jit.dylib`（macOS 模块后缀问题，见 HANDOFF），需用修复后的构建重跑。
- **未运行：** sanitizer 构建、`conformance_mutants.py`、其余六种模式的后端对齐。按用户 2026-10-22 的安排，完整普通验证与 sanitizer 在 V1–V9 全部完成后统一运行。

## V2：JIT 活跃性、ABI 与规模测量（2026-10-22 续作）

局部活跃性只缩减恢复和退出时的数据装入/写回；unknown/orphan 引用读取仍保守视为全部局部活跃，不在重新声明或作用域退出处 kill，以免遗漏仍指向旧 cell 的引用。ABI 2 保存每个退出点需要写回的 slot 表。没有精确标签的临时寄存器仍初始化为 ABSENT，不能把未知类型误当作可省略的值。

Release + LLVM 18.1.8 的独占测量使用旧 `build/v2-bench-base` 与新 `build/v2-release`。chain64 eager 的编译耗时从 1.067 秒降到 0.049 秒，stress 从 3.510 秒降到 0.121 秒；新 chain512 为 3,073 MIR / 869 恢复单元 / 0.800 秒，statements256 为 3,620 / 2,871 / 0.359 秒。操作数上限由 2,048 提到 4,096，恢复单元上限仍为 8,192，LLVM 超过 1,024 操作使用 O1 流水线。

这没有达成“最坏约 1 秒”的完整目标。48 个活跃局部、5,202 恢复单元约 0.91–1.03 秒，64 个局部、8,978 单元超过 3 秒，80 个局部在 5 秒超时；现有规模上限不是墙钟截止时间。macOS 没有 valgrind，未运行 callgrind，不能声称其门槛通过。原始报告为 `build/v11/jit-compile-{base,v2}.json` 及 `jit-compile-{locals,shapes}-{o1,o2}.json`。

JIT 定向检查通过：analysis 153、operations 74,984、tier 895。冻结 V1 树参考的 scalar/calls 38 案例、1,146 个预算进程在七模式共重放 8,022 次，全部一致、零 violations。证据 `build/v11/v2-results.md`、`v2-v9-replays.log`；不把这份子集记录称作全量七模式验收。

## V3、V9：新增构造与运行时（2026-10-22）

Java/C++ 编解码、树解释器、VM lowering/验证/执行已支持 seq、私有 entry-kind 2、无检查 int32 运算、method guard 和可选内联调试表。旧记录与 opcode 0–53 保留原格式，exec 版本仍为 10。无检查运算仍校验类型，按 uint32 位模式回绕；shl 使用低五位，编译器只对已证明原移位数在 0–31 的源码发射。JIT 只有精确 int 标签进入无退出的 binary_i32 MIR，其他类型退回 VM 保留错误报告。

私有入口限同模块直接调用；宿主 getFunction/hasFunction、公开 bound helper、反射、跨模块、方法表、析构及上下文工厂均不能暴露或绑定它。内部函数索引与公开索引分开，失败链接不发布部分状态，快照绑定也检查私有入口。内联元数据验证索引和无环调用链，保留 64 帧上限；合成帧的数值 ID 使用承载函数 ID，跨档位按名称/顺序/位置比较，同产物后端比较仍含 ID。`-g` 只控制输出调试元数据，同一档位的执行指令完全相同。

独立 Debug 原生九套件通过。最终私有缓存成员入口修复后，exec-v11 测试共 1,325 项，在七种 JIT 模式全部通过。eager+llvm 编译 819 个函数、原生进入 1,134 次；stress+llvm 进入 18,597 次，所有模式 violations 为零。证据 `build/v11/runtime-results.md`、`runtime-private-member-jit.log`。独立 wire/JNI 检查还通过 405 个编解码样本及 opaque snapshot 检查，见 `compact-directed.log`；本次续作已重建共享运行库，统一结果见后续记录。

## V4–V8：编译器与语义验证（2026-10-22 续作）

O3 当前实现与保守回退条件见 [O3.md](../compiler-optimization/O3.md)。新增优化不会通过枚举自动进入 O2。最终编译器对 60 组示例（O0/O1/O2，各带与不带调试）核对冻结编译器，ABD 逐字节相同。关闭全部八个新阶段时重新启用旧内联器的组合问题已修复；独立编译器 415/415 JUnit 通过，日志为 `build/v11/compiler-java/all-junit-switches.log`。共享编译器重建后，其 126 个 class payload 与最终独立 `compiler-switches.jar` 完全相同。

已把整程序差分工具扩展到 O0/O1/O2/O3；每个 O3 样本额外比较带/不带 `-g` 的完整执行结构。跨档位保留命名脚本帧的完整序列，只忽略合成帧无法还原的数值 ID；同一产物的树/VM/JIT 仍逐字段比较。并发语料按案例/档位写独立 JIT stats，避免多个进程覆盖或交错同一文件。

首批 O3 定向 15 案例、180 观察、984 预算检查通过（`build/v11/o3-sra-directed.json`）。生命周期 seeds 0–199 的 O3 树/VM 及 O0/O3 对比通过：18,111 个预算点、19,071 个参考进程、18,111 个候选进程，无拒绝或差异（`build/v11/o3-fuzz-200/`）。这是修改过程中的证据，不能替代随后修复后的最终重跑。

完整语料发现并修复了两项真实缺陷：消除字面量对象分配会改变可观察的 next_block 与后续地址；无类型 `def(x)` 缺少 declared-type 时，一些 O3 阶段错误地直接读取字段。对象优化现在保留原分配、构造和清理，避免同时改变自动堆容量或地址编号；字段仅在整区域不逃逸、构造/析构可证明安全时放入独立标量槽。不能用资源差异的许可掩盖宿主后来读取堆容量而产生的普通返回值差异。

随后的 460 案例语料中 456 个通过，另外四个暴露内联与析构回调观察错误栈的时机差异。普通失败调用先发布帧，成功调用清除已发布错误栈；最终展开调试链不足以恢复这些中途观察。修复后只在封闭标量调用者中展开 seq，own-g13 全部 11 个案例 / 66 次观察通过。新增成功调用清栈用例也发现旧 O1/O2 已有同类缺陷：冻结旧 jar 与当前 O0/O1/O2 的 ABD、树/VM 完整观察各自相同，见 `build/v11/trace-review/success-clear-baseline-result.json`。为维持旧档位产物契约，两个新增发布栈用例只比较 O0/O3，并在定向报告 `case_levels` 中明确范围；不把它们算作 O1/O2 通过。

## 尚未完成的验收与范围

所有改动保留在工作区，未提交或推送。V10 不在本轮，版本号、发行与旧样本未机械升级。复杂 if/while/多 return 被调者与严格 seq 的设计冲突尚未解决；一般析构体的正常和错误出口展开、真正的分配消除，以及完整泛型形态仍不能标为完成。

依用户恢复指令，本次将剩余检查提升为完整普通验证与顺序 sanitizer，再执行独占 Release 性能测量；实际完成状态见下面的续作记录。性能目标、O3 导致的原生规模拒绝、全量七模式后端对齐与 conformance mutants 均按实际报告验收。

## 暂停后续作验证（2026-10-22–23）

已补齐 `docs/runtime-performance-roadmap/SEMANTICS.md` 的 54–60 库存；差分工具自检通过 24 项检查，61 个 opcode 全登记。重建共享编译器、Debug/JNI 与 Release 运行库后，完整 `build_and_test.py --offline --jit` 退出 0，日志为 `build/v11/o3-resumed-normal.log`。415 项 Java、20/20 CTest（含真实 JNI）、Java/C++ ABD 逐字节往返、全部跨语言套件通过。O0–O3 定向为 79 案例 / 2,238 观察 / 4,230 预算检查，宿主堆为 8 案例 / 576 比较，完整编译语料为 460 案例 / 10,120 观察（七 JIT 模式）。

默认 O0 产物的完整树/VM 预算扫描为 460 案例 / 55,451 对独立进程；七种 JIT 模式各重放同一批 55,451 个输入与预算，均通过且零 violations。额外 O0/O1 LLVM stress 检查为 460 案例 / 1,840 观察。七种后端对齐各 1,379/1,379 调用一致、零拒绝或不匹配。O3 的预算覆盖由上述定向与下述生命周期扫描单列，不把默认 O0 的扫描写成 O3 全语料预算扫描。普通编译器 JAR 与关键报告备份在 `build/v11/resumed-normal-artifacts/`；随后顺序完成完整 `--offline --sanitize`，退出 0，日志 `build/v11/o3-resumed-sanitize.log`。415 Java、19/19 CTest、79 定向案例 / 1,596 观察 / 2,820 预算检查、8 堆案例 / 384 比较、460 编译语料 / 7,360 观察均通过；默认 O0 的 55,451 对预算扫描及四种 MIR JIT 模式各 55,451 重放、两种后端对齐各 1,379 调用均通过，无 ASan/UBSan 报告。sanitizer 不包含 JNI，JNI 由普通构建实际验证。

首次 sanitizer 驱动在 Java 阶段暴露既有诊断测试的不稳定性：`Thread` 的 16 KiB 栈提示不能保证触发栈溢出，已预热的解析器可能成功处理 150 层括号。用例改为独立 `-Xint -Xss256k` JVM、300 层合法括号，并保留退出码、普通/调试诊断及无输出文件的断言。35 项 Diagnostics 测试连续三轮通过；只改测试，编译器与运行时未变。首次失败日志保存在 `build/v11/o3-resumed-sanitize-first.log`，完整 sanitizer 重跑通过。两次完整运行的生产 Java 类内容相同，逐类核对见 `build/v11/resumed-compiler-class-identity.json`；此结论早于下述泛型位置修复，后者单列补验。

最终 O3 生命周期 seeds 0–199 已通过：18,111 个预算点、19,071 个参考进程与 18,111 个候选进程，零拒绝、差异、宿主或生成器故障。跨档位普通比较 185 个通过，另 15 个资源受限案例单独登记；不把资源受限结果算作普通语义全等。报告 `build/v11/o3-resumed-fuzz-200/report.json`，日志 `o3-resumed-fuzz-200.log`。

安装后的 SDK 实际搬迁到另一路径，静态与动态 `find_package(AzScript)` C++ 消费者均成功编译并执行两份 O3 产物；真实 JVM 以 `-Xcheck:jni` 执行同两份产物，无警告。共六次观察通过，样本实际含 opcode 54/57/60 与两个私有函数。报告 `build/v11/installed-consumer-final/report.json`，完整命令脚本 `build/v11/verify_installed_consumer.py`；未导出完整发行包。

生命周期负对照工具新增 `--source workspace`，快照当前已跟踪及未忽略的新源码并保存逐文件摘要，以免使用旧 HEAD 冒充本次源码验证；默认 HEAD 行为保留。本次两个未修改控制版本均通过，16/16 个变异体全部被捕获，报告为 `build/v11/o3-resumed-mutants/report.json`。


## 性能发现后的编译器补验（2026-10-23）

首轮独占 19 负载性能发现 `generic_identity` 的 O3/O2 耗时比为 VM 1.415、LLVM 配置 9.042。冗余泛型类型检查被删除时，其子调用没有自身位置，导致后续 seq 内联保守跳过。`ExecSpecializer` 现在优先保留子节点位置，缺失时继承被移除检查的位置。回归修复前失败，修复后全部 416 项 Java 测试通过，日志为 `build/v11/generic-fix-{regression-first,java}.log`。这是完整普通/sanitizer 驱动之后唯一的生产源码变化，运行时未变，源码差异证据为 `build/v11/generic-fix-source-delta.json`。

最终编译器重新执行全语料：普通 460 案例 / 10,120 观察，sanitizer 460 案例 / 7,360 观察均通过。含模块共 470 份输入，每档 470 份 O0/O1/O2/O3 产物与完整驱动时全部字节相同，见 `build/v11/generic-fixed-corpus-identity.json`。200 个 O3 生命周期样本也从同一可读 AST 重编译，全部字节相同，保留已完成的完整预算观察；见 `build/v11/generic-fixed-fuzz-identity/report.json`。

普通 79 定向案例 / 2,238 观察 / 4,230 预算检查，sanitizer 79 案例 / 1,596 观察 / 2,820 预算检查重跑均通过。新增三个泛型调用位置回归单列 O0/O3：循环、实参失败、被调函数体失败，在普通七模式通过 33 观察 / 234 预算检查，在 sanitizer 四模式通过 24 / 156；均覆盖源码/AST、带与不带 -g 的指令一致性和完整错误栈。搬迁后的静态/动态 C++ 与真实 JNI 六次观察重新通过，报告 `build/v11/generic-fixed-installed/report.json`。最终补验并不把旧完整驱动标成新编译器直接全跑。


## 最终性能与本轮结论（2026-10-23）

最终独占 Release 的 19 负载、7 轮 × 5 批、532 个计时进程全部核对返回值。相同运行库下 O3/O2 耗时几何均值为 VM 0.927、LLVM 配置 0.679，总体回退门槛通过且未新增原生规模拒绝；六类负载在两个模式中仅 4/12 目标达标，状态为 `targets-unmet`。对象/虚调用帧可能仍在 VM 执行。`generic_identity` 最终为 1.112 / 1.655，独立 7 × 5 复测为 1.109 / 1.652，剩余回退仍需解决。首轮 1.415 / 9.042 的报告也保留，不把位置修复写成全部性能回退已消失。

生产上限下 O2/O3 的四种形态、四个规模、两种 LLVM 模式各两次，共每档 64 次测量，均为 46 次原生成功和 18 次规模拒绝，拒绝位置相同，零 violations/编译器错误，无超时或不匹配。成功编译最大耗时约 0.357 秒；形态常量可能折叠，V2 最坏编译时间目标仍未证明完成。callgrind 因本机无 valgrind 未运行。

本轮请求的库存修复、剩余普通/sanitizer 验证和 O3 性能测试已执行完成，不能据此将 V1–V9 的所有实现与性能目标标为完成。具体数字、源文件/实际加载库摘要、原始报告指针和补验边界见 [验证索引](../compiler-optimization/O3-validation.json)、[性能记录](../compiler-optimization/O3-performance.json) 及 [O3 说明](../compiler-optimization/O3.md)。发行文档资源列表已包含两份 O3 JSON，24 项导出工具测试与文档链接检查通过；未重新导出发行包、升级版本、提交或推送。


## 参数快照优化与最终补验（2026-10-23）

本轮唯一生产改动为 `ExecSequenceInliner.java`：同型单参数直返保留实参表达式；未写入的字面量参数直接替换，全部实参为稳定叶子时复用只读变量参数。写入参数仍独立快照，复杂实参保留必要的从左到右求值与错误边界；调用者封闭、类型、源码位置及代价上限条件继续生效。新增四项 Java 和十项跨引擎回归覆盖别名、参数写入、求值顺序、未使用实参、错误栈及预算边界。

统一 quick 的 main 使用 `build/v11/o3-followup/integration/` 独立报告目录及既有 native 缓存，420 Java、20/20 CTest、全部前置跨语言/JNI 套件、92 定向 / 1,324 观察 / 2,712 预算检查、8 堆案例 / 256 比较、460 语料 / 5,520 观察通过；默认 O0 全预算及两个 stress 模式各 55,451 比较、LLVM stress 对齐 1,379/1,379 通过。最终冻结编译器与集成编译器的 126 个类内容相同。没有重跑完整七模式普通驱动或完整 sanitizer 驱动；旧完整证据仍保留。

既有 ASan/UBSan 运行库用最终编译器重跑 460 语料 / 7,360 观察，另通过新增十例 80 / 492；普通七模式新增十例为 110 / 738。四档各 470 份输入中，O0/O1/O2 全部字节一致，O3 仅 arity-1/4/16、nested-error-trace、parameter-copy 五个案例改变，已补全 77 对 O3 全预算扫描与七模式各 77 重放。策略模式在这五例中因调用次数低而没有编译，新增热循环样本另行确认各适用模式的原生执行。生命周期 200 个 O3 产物仍字节相同，复用原 18,111 预算点；搬迁 C++/JNI 六次观察重跑通过。

最终独占 19 负载、7×5 测量的耗时比为 VM 0.874、LLVM 配置 0.530，总体门槛通过、无新增规模拒绝，6/12 目标达标。泛型 identity 为 1.000 / 1.004，此前明显回退已消除。1/4/16 参数调用的 VM 比值为 0.707 / 0.628 / 0.533；全部达到 ≤0.8。对象/虚调用目标和 V2 最坏编译时间仍有缺口。日志、完整命令及冻结编译器在 `build/v11/o3-followup/`，可持久查看的证据见 [验证索引](../compiler-optimization/O3-validation.json) 与 [性能记录](../compiler-optimization/O3-performance.json)。所有修改未提交或推送，未改用户的 decompiler 工作。


## 后续局部清理与字段缓存（2026-10-23）

本轮生产改动仅在编译器：实参后缀写入分析、seq 内常量/简单复制传播及无用临时槽删除，以及封闭标量调用的字段缓存保留。新分析沿用原有类型、封闭调用和增长上限，不扩大运行时或执行格式。分支合并的字段事实、一般临时槽活跃性分配及复杂控制流内联仍未开放。

424 Java、20/20 CTest 和统一 `--offline --jit --quick` 通过。统一入口在独立输出目录复用 native 构建缓存，包含全部前置跨语言与真实 JNI、101 个四档定向 / 1,432 次观察 / 3,288 项预算检查、460 语料 / 5,520 观察、默认 O0 树/VM 与两个 stress 模式各 55,451 次比较及 LLVM stress 后端对齐 1,379/1,379。现有 ASan/UBSan 运行库另通过 460 语料 / 7,360 观察、8 堆案例 / 384 比较及新增九例 144 观察 / 900 预算检查；普通七模式九例为 198 / 1,350。没有重复此前两套完整驱动。

O0/O1/O2 每档 470 份输入与上轮完全相同，O3 467/470 相同；变化的三个案例补全 O3 全预算 22 对进程与七模式各 22 次严格重放。策略模式对这些低调用次数案例不触发原生编译，新混合实参热循环另确认所有适用模式的实际原生进入。200 个生命周期样本仍全部字节一致，保留原 18,111 个预算点证据。冻结编译器与集成编译器的 127 个类内容相同。

下表是四个新增定向负载的“本轮 O3 / 上轮 O3”耗时比，使用相同 Release 运行库，独占执行 7 轮 × 5 批、参数 4,096，每次检查独立结果。它们用于衡量新增形态，不能代表一般程序或与下方 O3/O2 总均值直接相乘。

| 定向负载 | VM 新 O3/旧 O3 | LLVM 配置新 O3/旧 O3 |
| --- | --- | --- |
| `constant_local` | 0.839 | 0.702 |
| `mixed_arguments` | 0.924 | 0.829 |
| `mixed_closed_call` | 0.944 | 0.922 |
| `object_closed_cache` | 0.876 | 0.871 |

原有 19 负载的 38 份 O2/O3 字节码以及被测 Release 运行库、LLVM 模块和宿主均逐字节不变，沿用上一轮 VM 0.874 / LLVM 配置 0.530 的 O3/O2 测量，不将其写成本轮重新测量。原始观察、编译器摘要、产物一致性与新增定向报告见 [性能记录](../compiler-optimization/O3-performance.json) 和 [验证索引](../compiler-optimization/O3-validation.json)。
