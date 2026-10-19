# 编译期优化实施记录

基线：`main` 的 `90dc825`，含反射作用域修复 `187f498`。分支：`codex/compiler-optimization`。计划见 [PLAN.md](PLAN.md)。

## 当前交付状态

C01–C07 已按下述保守范围实现并完成验收。默认 O0，显式 O1 开启编译优化。下方另保留 C01 历史记录，最终结论以 C07 为准。

- **C02**：新增类型化 `ExecIr`，记录值类型与声明槽类型、槽身份、源码位置、执行边及保守效果集合；结构化区域 CFG 表示分支、循环、返回和异常边。惰性表达式与显式清理保持不透明区域，其效果不会被当成无条件执行。所有 v10 opcode 都有分类；未知别名、泛型或对象操作保守标记可能调用、清理和抛错。IR 不改 wire 格式。
- **C03**：传播已求值的 int/bool 常量；在没有宿主/别名/所有权影响、参数类型已验证的标量函数中传播复制。分支取事实交集；循环先清除所有可能写入的事实，得到保守的循环不动点。移除已证明冗余的同类型常量转换和无引用、常量初始化的编译器临时声明；不删除用户变量声明或可能抛错的死结果。
- **C04**：树/VM 的工作单位不变，精确一致性仍比原计划的 5% 容差更强。VM 在无调用/清理/控制跳转的标量指令段中一次验证最多 64 个 tick、256 条内部指令；余额仍随每个实际 tick 递减。余额不足时使用旧逐点检查；错误发生时保留准确余额和源位置，宿主重入不能获得新的预算。新表按每条 VM 指令 8 字节计入 workspace（另有容器容量开销），大程序仍受既有 workspace 上限约束。批处理可由内部 `ExecutionOptions.batch_budget` 关闭，用于对照。
- **C05**：最多 4 参数、表达式深度 8、每函数最多 256 个展开点的标量叶函数内联；参数必须按原顺序恰好出现一次，实际类型由值生产者证明，不仅依据槽的名义类型。动态可能溢出的算术、getDepth、调用、所有权操作均保留调用。固定 int/bool 上下文的泛型 identity 在调用点专用化，不创建额外函数版本。静态虚调用沿用并验证主分支已有的 final 方法/值对象规则，没有对一般基类指针作新的假设。
- **C06**：提供对象身份、逃逸、未知发布及清理/重入的区域摘要，作为移动边界；不删除可观察的对象分配、复制或析构。循环外提仅用于孤立标量函数的不会报错的 int/bool 位运算、比较和布尔运算；依赖槽必须在循环外已声明且整个循环中不被写入。每函数最多增加 64 个临时槽；可能在零次循环前报错的除法、溢出算术、未知调用和堆读取不外提。
- `-O1` 启用上述编译阶段，默认继续 O0。`--no-fold`、`--no-propagation`、`--no-inline`、`--no-loop-motion` 独立关闭阶段；Java 支持 `STANDARD` 或精确 pass 集合。

### 计费与深度接口的实施决定

本轮保留既有表达式/循环/方法项工作单位，减少检查次数；没有引入按字符串字节数重新计费的规则。原生内建继续受既有容量、原生栈及调用链预算约束；阻塞宿主回调仍由宿主负责取消。这使跨引擎计量可继续逐字段比较，无需放宽误差来掩盖差异。

C++ `max_call_depth` 默认从 256 改为 0（关闭逻辑层数上限），Java/JNI 接受 0；正数保留为宿主显式选用的额外限制。计步、原生栈保护、VM workspace、当前调用数量和 trace 均保留。快照不保存执行限制，因此快照 v10 不改；恢复仍使用宿主当前配置。CLI 增加 `--max-steps`、`--max-call-depth`、`--max-stack-bytes`，在 flush 前应用。O1 可以减少物理调用，因此可选深度上限的失败位置不保证与 O0 一致。

### C07 最终验收

以下验证均在 Linux x86_64 上按顺序完成，退出码均为 0。新增 Java 优化测试共 20 项；exec 与 JNI 快照仍为 v10。

| 检查 | 普通构建 | ASan/UBSan 构建 |
| --- | ---: | ---: |
| Java 测试 | 350 | 350 |
| CTest | 16/16，含真实 JNI | 15/15，不含 JNI |
| 定向 O0/O1 × tree/VM 案例 | 28 | 28 |
| 无逻辑深度上限的预算终止检查 | 8 | 8 |
| 全语料 O0/O1 对照 | 463 案例 / 1,852 次观察 | 463 案例 / 1,852 次观察 |
| 树/VM 多预算差分 | 463 案例 / 55,460 对独立进程 | 463 案例 / 55,460 对独立进程 |
| 19 套端到端双引擎对齐 | 1,369 次，拒绝/差异均为 0 | 1,369 次，拒绝/差异均为 0 |

全部跨语言检查、源码→AST→ABD 字节一致性及 Java/C++ ABD 往返通过；sanitizer 无 ASan/UBSan 报告。原生批处理回归另外对成功和除零程序逐一比较 190 个预算下的批量/逐点路径，检查返回值、错误、余额与源位置。最后仅修正了 `vm_call_tests.cpp` 的“默认 256 层”旧注释和输出标签，没有改变测试行为或生产代码。

跨优化级别仅对预算耗尽错误放宽 trace/描述中的位置比较；普通错误、事件、对象状态与清理仍严格比较。同一产物的树/VM 观察，包括余额与位置，仍严格逐字段一致。两份优化语料报告均有 15 个预算耗尽案例适用位置比较规则，这不表示每个案例的位置都发生变化。

全新发行导出通过 30 项搬迁/消费者检查，包括两种万项链、静态/动态 C++、内置 Java 和真实 JNI；清单中的 **189 个文件**均重新核对大小与 SHA-256。发行目录为 `build/compiler-optimization-distribution`。

激活工具链并清除 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS` 后，实际顺序执行：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py build/compiler-optimization-distribution --offline
```

日志为 `build/compiler-optimization-final-normal.log`、`build/compiler-optimization-final-sanitize.log`、`build/compiler-optimization-distribution.log`。优化语料报告为 `build/compiler-optimization-corpus/report.json` 及 `build/compiler-optimization-corpus-sanitize/report.json`；多预算和对齐报告为 `build/runtime-perf/` 下 `vm-execution`、`vm-execution-sanitize`、`alignment`、`alignment-sanitize` 各目录中的 `report.json`。

### C07 Release 结果与默认策略

全部计时与构建、测试分开执行。覆盖标准 manifest 的 10 个负载和 4 个诊断负载，tree/VM 各比较当前 O0、当前 O1、main 原生库 O0。参数为 4,096，每进程预热 3 次，按 O0 校准约 250 ms 一批，7 轮轮换顺序、每轮 5 批，共 **588 个计时进程**；每批校验结果。下表加速比均为配对批耗时中位数比的几何平均，**大于 1 表示分母方案更快**。

这 5 个负载的 O0/O1 产物发生变化：

| 负载 | ABD 字节 O0 → O1 | tree O0/O1 | VM O0/O1 |
| --- | ---: | ---: | ---: |
| `call_0` | 844 → 848 | 1.516× | 1.420× |
| `constant_loop` | 854 → 722 | 1.567× | 1.306× |
| `loop_invariant` | 754 → 807 | 1.138× | 1.094× |
| `generic_identity` | 1,049 → 891 | 1.880× | 3.638× |
| `object_constants` | 2,399 → 2,267 | 1.065× | 1.087× |

其余 9 个标准负载的 O0/O1 ABD **逐字节相同**，不能把它们的时间波动归为编译优化收益。内联和外提也不保证缩小产物：零参数调用增加 4 字节，循环外提增加 53 字节；展开次数及临时槽数量受上述上限约束。

同一 O0 产物的新旧原生库对照如下，包含收益与回退：

| 负载 | tree：main O0 / 新 O0 | VM：main O0 / 新 O0 |
| --- | ---: | ---: |
| `integer_loop` | 1.004× | 1.170× |
| `call_0` | 0.997× | 1.094× |
| `branch_loop` | 1.010× | 1.170× |
| `call_1` | 0.995× | 1.120× |
| `call_4` | 1.002× | 1.120× |
| `call_16` | 0.956× | 1.079× |
| `object_fields` | 1.037× | 0.970× |
| `object_copy` | 0.990× | 0.979× |
| `virtual_monomorphic` | 0.997× | 0.970× |
| `virtual_alternating` | 1.002× | 0.963× |
| `constant_loop` | 0.945× | 1.249× |
| `loop_invariant` | 0.968× | 1.176× |
| `generic_identity` | 1.019× | 0.932× |
| `object_constants` | 0.999× | 0.968× |

新 VM 对整数/分支循环约快 17%，单/四参数调用约快 12%。标准对象字段、复制和虚调用的新 O0 耗时约增加 2.1%–3.8%；未专用化的泛型 identity 约增加 7.3%。tree 的常量循环新 O0 耗时约增加 5.8%。这是本机本轮观测，不把小幅差异直接当作跨平台结论，也不隐去回退。

叠加编译和原生变化后，相对 main O0，VM 零参数调用约 **1.553×**、常量循环 **1.631×**、泛型 identity **3.390×**。标准对象/虚调用没有获得新的编译收益；对象常量初始化的 O1 收益仅证明其常量计算被减少，没有消除对象分配、复制或析构。

1,000 个生成的标量函数（另有 main），7 轮交替独立 JVM 编译：整进程耗时中位数为 O0 **0.641 s**、O1 **0.745 s**，约增加 **16.2%**；包含启动、解析和 I/O，不是 pass 的独立耗时。ABD 从 **298,218** 降为 **166,218** 字节；两级产物均由 tree/VM 验证返回值 52。

**默认保持 O0，显式 `-O1` 开启优化。** 收益依赖代码形态，O1 有编译开销且并非所有负载受益；O0 默认也不意味着原生变化没有上述回退。各编译阶段可独立关闭，用于定位或回退。此次单一规模采样属于诊断，`score_eligible=false`，不产生正式整体采用评分，也不切换默认执行器。

证据：`build/compiler-optimization-perf-scalar/report.json`（84 个计时进程）和 `build/compiler-optimization-perf-remaining/report.json`（504 个）；编译开销在后者的 `compile_cost`。报告含逐批数据及测量时的源码、编译器、ABD、宿主和运行库摘要；对应 ABD、宿主、运行库与保存的采样脚本均核对通过。标量测量后只修正了对象分配的 IR 类型标记，后续重建也改变了编译器 JAR 摘要；其余测量与最终源码/编译器摘要完全匹配。测试后的最终编译器重新编译整数循环/零参数调用，四个产物与先前测量逐字节一致；核验记录为 `build/compiler-optimization-perf-scalar/final-artifact-check/report.json`。

### 性能重现与边界

`tools/bench_compiler_optimization.py` 可复现上述采样，`--only` 选择负载；`--baseline` 指向保留旧 Release 宿主和库的目录，工具显式绑定其动态库目录。计时统一设置 256 层上限，使新旧运行库使用相同配置；负载不会触及上限。完整负载命令示例：

```sh
python3 tools/bench_compiler_optimization.py \
  --classpath "$(cat build/java/classpath.txt)" \
  --native build/compiler-optimization-final-release \
  --baseline build/compiler-optimization-baseline \
  --work build/compiler-optimization-perf-rerun \
  --compile-functions 1000
```

基线是 main `90dc825` 的 Release 原生库，摘要保存在 `build/compiler-optimization-baseline/identity.json`。本轮未引入 JIT、跨函数完整 SSA、通用泛型版本缓存或对象分配消除；C02–C06 的完成范围就是上文列出的保守实现。`build/compiler-optimization-benchmark-smoke` 只验证测量工具功能，因与测试并行，其耗时不作性能证据。

## C01：整数与布尔常量折叠

- 新增 `ExecOptimizer`，在语义检查、深度降级和上下文规范化之后、debug 编号之前执行。复用 `ExecShape` 的表达式边定义，避免把槽 ID、泛型上下文、方法表等元数据当成表达式优化。
- `compile`、`compile-json` 支持 `-O0/-O1`，最后一个选项生效；默认 O0。Java 新重载接受 `Compiler.Optimization`，原有重载保持原行为。
- O1 折叠常量整数的加减乘除、余数、比较、位运算/移位，以及布尔非、与、或、相等比较。只折叠不会产生运行时错误的结果；用 int64 中间结果检查 int32 范围，除零、非法移位和中间溢出保留为运行时节点。
- 不传播变量、不消除有副作用的操作、不外提循环计算、不删除对象或清理节点；浮点和字符串运算暂未折叠。可读 AST 不变，同级别的 AST 重编译保持 ABD 一致，debug 开关不改变指令。
- 本批没有改动原生预算实现、调用深度 API 或 JIT。逻辑步数随优化后的代码减少；减少检查点和取消深度限制的迁移在 C04。

## C01 阶段验证（历史记录）

- 新增 8 个 Java 测试，覆盖 int32 边界、位模式、运行时错误保留、语义检查、默认输出、AST/debug/ABD 往返、泛型/对象元数据及 CLI 选项。
- 新增 `compiler_optimization_end_to_end.py` 并接入统一验证：13 个案例分别运行 O0/O1 × tree/VM，比较返回值、stdout、完整错误/源位置、全局和堆状态、对象身份探针及 teardown；仅优化级别之间允许余额不同。同一产物的两引擎观察仍逐字段一致。
- 死循环和递归各在两种优化级别、两种引擎运行，8 项检查验证预算耗尽和余额归零。
- Linux x86_64 完整普通验证退出码为 0：338 项 Java 测试、16/16 CTest（含真实 JNI）、全部跨语言套件及 Java/C++ ABD 往返通过。树/VM 差分 463 案例、55,460 对独立进程全部一致；最后 19 套件、1,365 次对齐运行无拒绝或差异。
- 命令：激活工具链后，清除 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`，运行 `python3 tools/build_and_test.py --offline`。日志：`build/compiler-optimization-normal.log`；差分及对齐报告：`build/runtime-perf/vm-execution/report.json`、`build/runtime-perf/alignment/report.json`。
- 本批没有修改原生内存、析构或清理实现；未运行 sanitizer，未重新导出发行包。

## C01 阶段收益证据（历史记录）

定向循环每轮计算 `(2+3)*(4+5)`，运行 100 轮：

| 指标 | O0 | O1 |
| --- | ---: | ---: |
| ABD 大小（含 debug） | 1,342 字节 | 1,146 字节 |
| 执行步数（tree 与 VM 相同） | 2,624 | 2,024 |

这是 C01 阶段记录的产物大小和工作量变化，不是耗时加速比例。`build/compiler-optimization.json` 已由最终 C01–C07 验收更新，当前结果应按上方最终验收记录解读。

现有运行时 manifest 的 10 个标准基准在 O0/O1 下 ABD **逐字节相同**：它们没有此 pass 可折叠的整数/布尔常量运算。不能把常量密集微基准的收益推广到这些负载。对照产物摘要：`build/compiler-optimization-bench/artifacts.json`。

### Release 诊断测量

完整普通验证结束后独立采样；同一主分支运行库、同一引擎比较 O0/O1。CMake Release、GCC 14.2.0，参数 4,096，每个进程预热 3 次，O0 校准到约 250 ms 一批，7 轮交替级别顺序，每轮每级别 5 批，所有运行均检查返回结果。

| 引擎 / 负载 | O0 每调用中位数 | O1 每调用中位数 | 配对加速比 |
| --- | ---: | ---: | ---: |
| tree / 常量循环 | 6.286 ms | 4.272 ms | 1.427× |
| VM / 常量循环 | 2.691 ms | 2.020 ms | 1.355× |
| tree / 原有整数循环（相同字节码） | 6.209 ms | 6.092 ms | 1.022× |
| VM / 原有整数循环（相同字节码） | 2.850 ms | 2.864 ms | 1.005× |

加速比是各轮 O0/O1 批耗时中位数之比的几何平均，所以不等于表中合并样本中位数直接相除。相同字节码的对照差异属于测量波动；这些结果只证明常量密集微基准的收益，不是整个项目的性能分数，也不是 VM 对 tree 的比较。常量循环不含 debug 的 ABD 从 854 降至 722 字节。

证据：`build/compiler-optimization-bench/measurements.json`，含逐批数据、产物/宿主/运行库摘要、基线和 CMake 配置；工作目录内 `run.py --prepare` 生成产物，`run.py --measure` 执行上述采样，原始日志为 `measure.log`。这套两负载诊断不计入正式性能采用评分。

### 编译开销

1,000 个标量函数各含 `(2+3)*(4+5)`，7 轮交替 O0/O1，以独立 JVM 进程编译。包含 JVM 启动、解析和输出 I/O 的总耗时中位数为 O0 **0.632 s**、O1 **0.614 s**。这不是优化 pass 的独立计时，不能把约 17 ms 差异认定为编译加速。两级产物均在 tree/VM 验证返回值 52。

证据及重现脚本：`build/compiler-optimization-bench/compile-cost.json`、`compile_cost.py`。所有计时均在完整测试和原生构建结束后顺序执行。
