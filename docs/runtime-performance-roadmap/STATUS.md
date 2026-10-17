# 运行时性能重构实施记录

基线：`c2b572d6b36ca521c5faf2658adc296e403f47f1`。原实施分支：`codex/runtime-performance`；暂停检查点分支：`codex/runtime-performance-handoff`。本文件记录实际完成和待验证内容；完整目标见 [PLAN.md](PLAN.md)。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| R00 | 完成 | Temurin 21.0.12.1+1、CMake 3.31.10、GCC 14.2；独立冻结源码，完整验证退出 0，输出 `build/runtime-perf/baseline-validation.log` |
| R01 | 测量已暂停 | Release smoke 已通过；正式 7 轮采样完成 5 轮及第 6 轮 10 条，共 310 条观察，退出 130；无正式评分 |
| R02 | 普通验收通过 | 几何增长、first-fit 和分配失败回滚定向通过，完整普通验证通过；完整 sanitizer 待运行 |
| R03 | 基础设施验证通过 | 23 案例 / 145 对独立进程观察，5 组完整预算扫描；故意错误候选被拒绝并缩小。54 opcode 已登记，28 静态出现，不代表完整 VM 覆盖 |
| R04 | 普通验收通过 | 类型化访问与消费者迁移、运行时 ABI 1、插件预注册检查完成；ABI 29 项检查通过，发行搬迁待运行 |
| R05 | 普通完整验收通过 | Value 内联标量，145 对差分与全量普通验证通过；完整 sanitizer 和发行验证仍待闭合 |
| R06–R08 | 原语已验证，入口接入待验证 | R07 和较早 R08 的 4257 个定向检查通过普通/ASan/UBSan；加入 R09 后的 executor、Session 与实际入口尚未编译 |
| R09–R10 | 暂停于集成阶段 | 显式 CALL、宿主桥、清理原语及新测试已写；未完成编译和语义验收；清理尚未接入执行循环 |
| R11–R28 | 尚未完成 | 按依赖顺序执行 |

## 暂停与交接

按用户要求暂停，完整交接见 [HANDOFF.md](HANDOFF.md)。当前 VM 接入尚未加入 CMake，新增 Session/CALL/宿主桥尚未编译；下面的通过结果属于各自明确的较早源码阶段，不代表本检查点整体可构建。

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
