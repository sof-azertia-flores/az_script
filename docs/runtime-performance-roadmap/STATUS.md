# 运行时性能重构实施记录

基线：`c2b572d6b36ca521c5faf2658adc296e403f47f1`。原实施分支：`codex/runtime-performance`；暂停检查点分支：`codex/runtime-performance-handoff`（`ad26db9`）；恢复后的继续分支：`claude/runtime-performance-handoff-5mdt6h`。本文件记录实际完成和待验证内容；完整目标见 [PLAN.md](PLAN.md)。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| R00 | 完成 | Temurin 21.0.12.1+1、CMake 3.31.10、GCC 14.2；独立冻结源码，完整验证退出 0，输出 `build/runtime-perf/baseline-validation.log` |
| R01 | 测量已暂停 | Release smoke 已通过；正式 7 轮采样完成 5 轮及第 6 轮 10 条，共 310 条观察，退出 130；无正式评分 |
| R02 | 普通验收通过 | 几何增长、first-fit 和分配失败回滚定向通过，完整普通验证通过；完整 sanitizer 待运行 |
| R03 | 基础设施验证通过 | 23 案例 / 145 对独立进程观察，5 组完整预算扫描；故意错误候选被拒绝并缩小。54 opcode 已登记，28 静态出现，不代表完整 VM 覆盖 |
| R04 | 普通验收通过 | 类型化访问与消费者迁移、运行时 ABI 1、插件预注册检查完成；ABI 29 项检查通过，发行搬迁待运行 |
| R05 | 普通完整验收通过 | Value 内联标量，145 对差分与全量普通验证通过；完整 sanitizer 和发行验证仍待闭合 |
| R06–R08 | 标量 VM 已接入构建并通过定向及独立进程差分 | VM 源码与 9 个定向测试、2 个 host 已加入 CMake；见下文“恢复后的集成”。R06 的生产 `blocks/heepalloc` 身份迁移仍未开始 |
| R09 | 标量 CALL/宿主桥/Session 通过；默认 workspace 定为 128 MiB | 37 案例 / 825 对独立进程观察（冻结 `019c440` 参考及同构建树后端）全部字段一致；workspace 边界见 [STORAGE_DESIGN.md](STORAGE_DESIGN.md) 第 9 节。宿主可配置项待 VM 成为宿主可选后端时加入 |
| R10 | 编译器 cleanup 节点已接入标量执行循环 | 见下文“R10 编译器清理接入”。CleanupActions 析构 continuation、语句临时量仍依赖 R11 对象模型 |
| R11–R28 | 尚未完成 | 按依赖顺序执行 |

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
