# 运行时性能重构交接

暂停时间：2026-10-17（UTC）。用户要求暂停开发、写交接并提交到新分支。

> 恢复状态：工作已在 `claude/runtime-performance-handoff-5mdt6h` 继续。下文“构建缺口”及恢复顺序第 2–5 步已完成，第 6 步完成了 R10 编译器清理与 R11a 对象帧，实际结果与发现记录在 [STATUS.md](STATUS.md)；本文件其余内容保留暂停时的交接原文。

交接分支：`codex/runtime-performance-handoff`，从 `codex/runtime-performance` 的 `019c440` 创建。此提交是 **WIP 检查点，不是可发布版本**。已停止开发代理和性能采样；没有启动后续编译、sanitizer 或发行导出。未推送此分支。

## 首先阅读

- [PLAN.md](PLAN.md)：R00–R28 的详细实施、语义和验收要求。
- [STATUS.md](STATUS.md)：已有验证证据；注意区分已冻结的 R05 与当前未验证的 VM 接入。
- [SEMANTICS.md](SEMANTICS.md)：54 个 wire opcode 与可观察行为清单。
- [STORAGE_DESIGN.md](STORAGE_DESIGN.md)：稳定存储身份、工作区和帧设计。
- 仓库根目录 `AGENTS.md`、`README.md` 及 `docs/EXEC_FORMAT.md`。

用户优先级是循环、对象和函数调用速度，接受较大架构调整。外部 exec v10、JNI snapshot v10 暂时保持。迁移期树执行器仍是默认，新 VM 必须明确拒绝未实现功能，不能静默回退。

## Git 边界

| 提交 | 内容与验证边界 |
| --- | --- |
| `c2b572d6b36ca521c5faf2658adc296e403f47f1` | 已确认的原始 main，独立完整普通验证通过 |
| `bbce8ea` | 实施计划与验收标准 |
| `f8ab415` | 可复现基准和独立进程差分基础设施 |
| `019c440df889881caca6b4c405b4533fd9d0bdca` | 几何堆扩容、类型化宿主访问、ABI 1、内联 Value；完整普通验证通过 |
| 本交接提交 | R06–R10 原语及 VM 接入的未完成源码、测试、交接记录；尚未整体编译 |

**当前检查点有明确构建缺口：`library.cpp` 已调用 Session，但 `interpreter/CMakeLists.txt` 尚未加入新的 VM 实现文件和测试目标，直接按现有 CMake 构建预计会缺少链接符号。** 用户暂停时尚未进行这一步集成；不要把已有 `build/native` 二进制视为本提交的构建结果。恢复工作的第一步是补齐构建声明，再解决实际编译/测试结果。

不丢弃当前源码来恢复旧构建；需要可靠对照时使用冻结的 `019c440` 源码。

## 已验证的基础改动（R01–R05）

- `variable` 的旧公开 `type/value` 已移除；通过 `type_tag()`、只读 `get<T>()`、`object_handle()` 等接口访问。仓库运行时、JNI、测试和插件消费者已迁移。
- `vm::Value` 内联基本类型，共享不可变字符串。实测 `sizeof(Value)=24`，`sizeof(variable)=64`，旧 variable 是 56 字节；不能声称 wrapper 缩小。旧树仍有 `shared_ptr<variable>` 外壳分配。
- 10,000 次标量存储/复制检查为零分配；一次旧 `deepCopy` 仍分配一个 wrapper。
- 堆范围表改为几何扩容：1,000/4,000/20,000 次分配分别扩容 11/13/16 次；5 个真实分配失败注入点保持原状态。单独旧实现负对照被增长界限拒绝。
- Runtime ABI 为 1，项目版本 0.3.0，共享库 basename `abdInvoker-1`；exec/snapshot 版本不变。插件签名验证后、注册前检查 ABI。
- 原始基线和 R05 的完整普通验证均退出 0。R05 包含真实 JVM/JNI、插件、快照、安装消费相关检查及新增 ABI 29 项检查。
- R03：23 案例、145 对独立进程观察；5 组完整步骤预算扫描，比较值位模式、全局、堆、回调、stdout、错误/trace、剩余步骤和销毁。故意错误候选被识别并缩小。
- R05 的**完整 sanitizer 和发行搬迁尚未运行**；定向通过不能替代它们。

## 当前 VM 源码与验证边界

| 文件 | 已实现内容 | 暂停时的实际验证状态 |
| --- | --- | --- |
| `vm/storage.{h,cpp}`、`workspace.h` | 分段 SlotPool，带 arena/index/generation 的身份；按实际 backing capacity 计费、失败回滚、无分配退休 | 较早版本普通/ASan 定向通过；最后新增 peak 计数及测试尚未重跑 |
| `vm/program.*`、`lowering.*` | 24 字节内部指令、函数/常量/上下文表、54 opcode lowering、CFG verifier、延迟动态调用 | 最后 R07 定向普通/ASan/UBSan 通过 |
| `vm/source_decode.*`、`loader.*` | 从已验证 v10 模块解码并降级；hint 重定位、模块全局和生命周期独立索引 | source bridge 398 项含旧实现/lowering oracle；独立 sanitizer 343 项；6 组真实源码/AST/ABD/模块装载验证通过 |
| `vm/operations.*` | Value 算术、位运算、转换、格式化及返回转换 | 104 项边界检查及 100,000 次零分配运算通过普通/ASan |
| `vm/execution.*` | 标量循环/控制流、引用寄存器、局部别名、显式脚本 CALL/RETURN、宿主回调接口 | **R08 旧版** 4,257 项普通/ASan/UBSan 通过，100k 热循环零分配；**加入 R09 CALL 后的当前文件尚未编译** |
| `vm/frames.*` | 稳定 FrameStack、工作区 offset ranges、独立调用深度计数 | 较早原语版本 48 项普通/ASan/UBSan 通过；与当前 executor 的集成尚未验证 |
| `vm/cleanup.*` | 编译器 cleanup continuation、首错/flow 保存恢复、可暂停的清理 action driver | 严格独立编译通过；测试曾因缩进 warning 失败，已改换行但未重跑；尚未接执行循环或真实对象 driver |
| `vm/session.*`、`host_bridge.*`、`library.{h,cpp}` | 实验后端选择、flush 事务、生命周期索引绑定、Value/宿主边界、全局别名映射、trace、标量宿主重入桥 | **新增接入代码全部尚未编译/运行** |

上述路径均相对于 `interpreter/`。新测试还包括 `vm_session_tests.cpp`、`tests/vm_call_tests.cpp`、`tests/vm_execution_{host.cpp,cases.py,end_to_end.py}`，目前只是已写入源码，不是通过结果。

已修复并复测的 R07/R08 内部问题：

1. CFG 实际作用域嵌套可以超过指令文本的线性深度；改用安全容量上界并加运行检查。
2. 无 `tick` 的循环原先可绕过预算；verifier 现拒绝这类 CFG。
3. 普通跳转进入 finalizer 原先可缺少 continuation；现验证 finalizer 身份。
4. `M/drop` 的目标限制与旧 parser 对齐；非引用 store 有运行时拒绝。尚未完成全部寄存器种类的数据流证明。

## 新接入的关键契约和待查点

- `vm::select_backend(script&, workspace_limit)` 必须在首次初始化前选择。工作区大小目前由实验调用方显式传入，**64 MiB 默认值尚未定案**。测试暂用 256 MiB。
- `script::flush()` 构建独立候选 Session，完成所有函数索引检查和分配后才与已有 Session 交换。生命周期严格按 `compiled.modules[i]` 绑定；不同零全局模块可有相同 global offset，不能据 offset 区分 hook。
- `ofunction::invoke_bound()` 保留状态、上下文、旧入口 call guard 和参数验证，再进入 Session。VM 内部普通 CALL 使用 FrameStack 和执行循环，不递归调用 C++ invoke；宿主重入仍经过原生栈 guard。
- `ExecutionOptions{calls, callbacks, admit_entry=false}` 防止最外层重复计数。内部脚本调用始终单独 admission。`getDepth` 是 `2 + 当前逻辑 scope_count`，不等于调用栈深度。
- Session 的 globals 按宿主 `variable*` 身份映射到 SlotId。合并/拆分别名、替换 wrapper 后需保持旧寄存器所引用的存储；外层调用结束前保留旧 binding。
- 宿主回调前 publish、后 refresh，包括失败路径。若宿主放入对象导致部分 VM 拒绝，不能再次用旧标量覆盖宿主状态。当前有 `export_allowed` 标志保护；重入、失败导入和旧 binding 还需实际测试。
- 外部回调通过旧 checked host boundary 保留签名、原生栈和 native trace。VM 逐帧从内到外报告脚本 trace，Session 仅在 executor 未报告时补一帧，ofunction 避免重复。
- 当前桥仅覆盖标量参数/结果、globals、词法深度和标量重入。**尚未实现宿主读取/保留 VM 局部槽的完整环境镜像**；不能称作 R14 完成。
- 分配、反射、插件加载内建以及静态对象/泛型宿主签名会在 preparation 显式拒绝；动态/ANY 宿主返回对象只能运行时拒绝。对象、buffer、generic、virtual 执行未接入。
- CleanupActions 当前适用于按尾部 watermark 逆序清理。`mem_send_up` 等所有权提升需要可转移的 action 身份/作用域列表；真实析构、buffer、比较器及工厂的脚本调用不能借旧 helper 递归执行。
- 原有 `blocks/heepalloc` 的 raw owner/environment/frame 指针尚未迁移，R06 不能整体标完成。
- `pending_error` 沿用原有 trace 行为；不能把预留 cleanup 工作区记录后的无分配误称为整个错误/trace 路径绝对零分配。

## 性能采样暂停状态

R05 对“仅堆扩容修复”的 Release 对照，按固定 4 组权重、30 个点、7 轮、每轮 5 batches 进行。用户要求暂停时已停止：**完成 5 轮，第 6 轮保留了 10 条进程观察，总计 310 条；退出码 130，未完成 7 轮，不能给出正式性能分数或提速结论。**

本地保留：

```text
build/runtime-perf/R05-vs-R02-release.log
build/runtime-perf/R05-vs-R02-release/observations.jsonl
build/runtime-perf/R05-vs-R02-release/frozen.json
build/runtime-perf/R05-vs-R02-release/metadata.json
build/runtime-perf/R05-vs-R02-release/quiet-window.json
build/runtime-perf/R05-benchmark-build-verification.json
```

冻结候选确为 `019c440`，两个 host 使用各自 Release 库；校准由参考端完成。已完成观察的正确性检查未失败。恢复后保留这些不完整结果，使用新的输出目录重新完整采样；不要补拼成未声明的新统计协议。计时期间暂停其他编译/测试。

## 恢复工作的顺序

1. 读本交接和实际 diff，不直接依据历史“通过”结论执行发布。
2. 补齐 `interpreter/CMakeLists.txt`：runtime sources 增加 `vm/{storage,frames,program,lowering,source_decode,loader,operations,execution,session,host_bridge,cleanup}.cpp`；按下表添加测试。不要把测试 `.cpp` 编入 runtime。
3. 在普通构建中先编译并运行新原语、scalar、call、session 定向测试；处理当前代码的真实编译和行为问题。再跑对应 sanitizer。
4. 用 `tests/vm_execution_end_to_end.py` 做 scalar、calls 两阶段独立进程差分。测试 host 在 flush 前选择 VM；参考 host 可用 `AZSCRIPT_VM_REFERENCE_ONLY` 宏链接冻结旧库。支持先采集 reference，再 `--reference-work` 重放。严格比较协议全部字段，不能过滤剩余预算或 trace。
5. 运行真实最大槽头 1,048,576、最后合法槽、32,768 实际局部和递归工作区失败恢复用例，记录 `vm::workspace_usage()`。据完整 backing/增长峰值决定工作区策略，不能仅根据稀疏最大头测试设置 64 MiB。
6. 接入 R10 cleanup 执行，再按计划处理 R11–R18 对象/泛型/虚调用/宿主/JNI/快照与默认切换；R19–R28 编译器 IR 等尚未开始。
7. 独立完成冻结 R05 的完整 sanitizer、发行搬迁及重新完整性能测量，再记录基础改动结论。它们不证明新 VM 已通过全量验收。

建议 CMake 测试映射：

| 目标 | 源码 |
| --- | --- |
| `vm-storage-tests` | `tests/vm_storage_tests.cpp` |
| `vm-frame-tests` | `tests/vm_frame_tests.cpp` |
| `vm-program-tests` | `interpreter/vm/program_tests.cpp` |
| `source-decode-tests` | `interpreter/vm/source_decode_tests.cpp`；启用 `AZSCRIPT_SOURCE_BRIDGE_LEGACY_ORACLE`、`AZSCRIPT_SOURCE_BRIDGE_LOWERING_ORACLE` |
| `operations-tests` | `interpreter/operations_tests.cpp` |
| `scalar-vm-tests` | `interpreter/scalar_vm_tests.cpp` |
| `vm-call-tests` | `tests/vm_call_tests.cpp` |
| `vm-session-tests` | `interpreter/vm_session_tests.cpp` |
| `vm-cleanup-tests` | `interpreter/vm/cleanup_tests.cpp` |
| `vm-loader-host` | `tests/vm_loader_host.cpp` |
| `vm-execution-host` | `tests/vm_execution_host.cpp` |

## 环境和本地证据

仓库 `/workspace/az_script`；每个 shell 激活：

```sh
source /workspace/azscript-toolchain/activate.sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS python3 tools/build_and_test.py --offline
```

Temurin 21.0.12.1+1、CMake 3.31.10、GCC 14.2、Python 3.12；4 CPU quota、32 GiB 内存。Java 环境横幅会干扰 stderr 断言，必须清除上述变量。同一源码目录普通/sanitizer 共用 `build/java`，必须顺序执行。

完成普通验证后，内存/清理改动要求：

```sh
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS python3 tools/build_and_test.py --offline --sanitize
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS python3 tools/export_distribution.py dist/azscript --offline
```

以上是恢复后的待执行命令，不是本检查点已经执行的结果。

主要冻结源码和证据：

```text
build/runtime-perf/baseline-source/       # c2b572d 原版
build/runtime-perf/heap-growth-source/    # 原版 + 单独几何扩容修复
build/runtime-perf/r05-source/            # 019c440；deps 已复制，可离线验证
build/runtime-perf/baseline-validation.log
build/runtime-perf/candidate-validation.log
build/runtime-perf/r03-candidate-r05/report.json
build/runtime-perf/r03-self-baseline-complete/
build/runtime-perf/r03-negative-control/
build/runtime-perf/vm-program-end-to-end.json
build/runtime-perf/audit/
```

第二对照 patch SHA-256：`391fb83eaece31e3607d6a513062dc2e8636e7860f0b8d787adb8af49cddd62d`。`build/` 按仓库规则忽略，**这些二进制、日志和冻结目录没有进入 Git**；换机器需要按提交/脚本重新生成，不得宣称它们随 checkpoint 分发。

云环境 onboarding 已按 `cloud-environment-onboarding:setup` 执行并保存配置草稿；草稿尚未发布。当前暂停不进行配置发布、GitHub 推送或 PR 操作。
