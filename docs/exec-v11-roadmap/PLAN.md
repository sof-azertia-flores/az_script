# exec v11 实施计划：O3 与配套更新

日期：2026-10-21（UTC）。开发分支：`exec_v11_main`（起点为 main `4376b1f` 加发行包 JIT 选项 `2c487c7`）。设计见 [DESIGN.md](DESIGN.md)，进度与验证证据见 [STATUS.md](STATUS.md)；三者冲突时以设计为准并同步修订本计划。

每个阶段由一个或多个完整提交组成，提交前运行定向回归；阶段完成后合入 `exec_v11_main`。阶段内可改变 wire 格式与 ABI，exec 版本号保持 10，直到第 V10 阶段。未完成的检查在 STATUS 中写明，不沿用历史结论。

## 1. 阶段

| 阶段 | 内容 | 退出条件 |
| --- | --- | --- |
| V0 | 设计、计划与状态文档（本目录）；相关路线图的指针 | 文档检查 |
| V1 | 删除 `getDepth`：编译器内建表、运行时实现与 `ownership::depth`、VM 伪造作用域链（`lexical_depth`）；所有权语料与模糊测试改写；文档 | 普通完整验证与 sanitizer；一致性变异体仍全部捕获 |
| V2 | JIT 规模：只写回/装入活跃 slot（ABI 增加退出活跃集与局部活跃性）、按规模选择 LLVM 流水线；重新测量编译时间并提高上限 | 七种 JIT 模式重放与对齐；编译时间测量记录；callgrind 无回退 |
| V3 | 格式增补：`seq`、`entry_kind=2` 私有函数、无检查整数运算、`guard_method`、内联调试记录；Java/C++ 读写端、树、VM lowering/校验/执行、JIT 分析、文档 | 每个构造的编解码、手写 exec 的双引擎差分和错误路径；旧 v10 样本不变 |
| V4 | O3 档位骨架：显式 O2 阶段集合，`Optimization.AGGRESSIVE` / `-O3` / `--no-*`；验证与性能工具支持任意档位对比；O3 暂等同 O2 | O0/O1/O2 产物逐字节不变；O3 档位贯通各工具 |
| V5 | O3-a：经 `seq` 的任意位置内联、扩大的被调者与调用者范围、统一代价模型、槽压缩 | 定向用例、语料 O0/O3 对比与 JIT 重放、性能门槛 |
| V6 | O3-b：常量参数与泛型上下文专用化（私有函数） | 同上；版本上限与 flush 可见性测试 |
| V7 | O3-c：逃逸分析、对象标量替换、字段缓存、去虚化（直接与守卫） | 同上，另加 sanitizer 与 `lifecycle_fuzz` O0/O3 对比 |
| V8 | 区间证明的无检查运算发射 | 定向溢出边界用例、语料对比、性能 |
| V9 | JIT 消费 v11 构造（无检查运算无退出），以 O3 产物重新测量规模上限 | 七种模式重放、对齐、性能 |
| V10 | 发布收尾（不在本轮）：exec 版本 11、读写端与样本、文档、发行验收、合入 main | 见 DESIGN 第 9 节 |

V1、V2 互不依赖；V5–V8 依赖 V3、V4；V9 依赖 V2 与 V8。

## 2. 验收

- **同一产物：** 树/VM/JIT（probe、eager、stress、on:2+interp、eager+llvm、stress+llvm、on:3+llvm）逐字节一致：`tests/vm_execution_end_to_end.py` 完整预算扫描、`tools/backend_alignment.py`、O3 语料重放。
- **跨档位：** O0 对 O3 按 DESIGN 第 2 节比较；带 `-g` 时 trace 帧与位置相同。
- **兼容：** O0/O1/O2 产物在 V1 之外的阶段逐字节不变（V1 只影响使用 `getDepth` 的程序），并可在 main 的 v10 运行库执行。
- **性能：** 扩展后的 `tools/bench_compiler_o2.py`，Release 构建，7 轮 × 5 批；几何平均回退不超过 5%，不得新增原生规模拒绝；VM 与 LLVM 分别报告。目标：调用负载（`call_1/4/16`）O3/O2 耗时不超过 0.8，对象负载（`object_fields`、`object_copy`）不超过 0.8，`virtual_monomorphic` 不超过 0.9。
- **规模：** 验证随改动而定。小改动与文档只做定向检查。用户于 2026-10-22 调整：阶段之间只运行定向回归与 `python3 tools/build_and_test.py --offline --jit --quick`（全部套件，JIT 重放、O2 与对齐只用 stress 模式）；完整的 `--offline --jit` 与 `--sanitize` 只在整个 V1–V9 任务结束前运行一次。各阶段的退出条件中“普通完整验证与 sanitizer”据此推迟到最后，STATUS 记录每个阶段实际运行的检查。

## 3. 与其他计划的关系

- 编译优化（[../compiler-optimization/PLAN.md](../compiler-optimization/PLAN.md)）：O0–O2 的阶段与契约不变；O3 只在本计划中实施。
- JIT（[../jit-roadmap/PLAN.md](../jit-roadmap/PLAN.md)）：本计划的 V2、V9 是 JIT 规模与 v11 支持；J4 后续优化与 J8 对象帧仍按 JIT 计划进行，J8 完成后 `guard_method` 才进入原生代码。
- 运行时性能计划（[../runtime-performance-roadmap/PLAN.md](../runtime-performance-roadmap/PLAN.md)）：VM 的修改同样保持树与 VM 一致，不新增允许差异。
