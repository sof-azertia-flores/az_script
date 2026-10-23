# exec v11 开发交接

更新：2026-10-23。本轮局部清理与字段缓存优化已完成，当前为 `exec_v11_main` 未提交、未暂存工作区；用户 decompiler 修改保留。生产改动仅 `ExecSequenceInliner.java`、新增 `ExecSequenceCleanup.java` 和 `ExecObjectOptimizer.java`，实现实参后缀写入分析、seq 内常量与复制传播/无用临时槽删除、封闭标量调用的字段缓存保留。空 seq 保留作为内联调用点锚点。

424 Java、20 CTest、统一 quick、ASan/UBSan 全语料及变化产物七模式预算补验通过。原有 19 负载的 38 份字节码和 Release 运行库完全不变，保留原 O3/O2 均值 0.874 / 0.530；四个新增定向负载另比较本轮与上轮 O3，详见 O3 文档与性能索引。

本轮统一 quick 通过 101 个四档定向 / 1,432 观察 / 3,288 预算，460 普通语料 / 5,520 观察、8 堆案例 / 256 比较，默认 O0 全预算与两种 stress 各 55,451 次比较及 LLVM stress 对齐 1,379/1,379。ASan/UBSan 另用现有运行库通过 460 语料 / 7,360 观察、8 堆案例 / 384 比较、新九例 144 / 900；普通七模式九例为 198 / 1,350。O0/O1/O2 每档 470 份产物不变，O3 三份变化补全预算 22 对进程与七模式各 22 次重放，200 生命周期样本字节不变。没有重复此前完整普通/sanitizer 驱动。

全部本轮日志、脚本、产物与冻结编译器在 `build/v11/o3-local/`；`compiler-before.jar` 是上一轮版本，`compiler-final.jar` 与本轮 `integration/java/compiler.jar` 的 127 个类内容一致。定向性能在 `paired-performance/report.json`，基线为上一轮 O3；`bench-artifacts/report.json` 证明原 19 负载 O2/O3 与运行库不变。此前参数快照优化的全组报告仍在 `build/v11/o3-followup/performance-final/report.json`。最新索引见 [O3 验证](../compiler-optimization/O3-validation.json)、[O3 性能](../compiler-optimization/O3-performance.json) 和 [STATUS](STATUS.md)。所有会话已结束，无后台任务。

## 暂停点

以下记录上次暂停时的历史状态；当前完成与待办以本文件开头及 STATUS 为准。

统一 `--offline --jit --quick` 的日志为 `build/v11/o3-quick.log`。已通过 414 Java 测试、20/20 CTest（含真实 JNI）、全部前置跨语言套件、79 个 O0–O3 定向案例 / 1,168 次观察 / 1,880 项预算检查、8 个宿主堆案例 / 256 次比较，以及完整 460 案例 / 5,520 次编译语料观察。随后 `tests/runtime_differential.py --self-test` 因语义库存表未登记 `54 seq` 失败；尚未执行后面的完整预算扫描、两种 stress 重放与 LLVM stress 后端对齐。恢复时先补齐该库存的新 opcode 54–60，再从实际失败处续跑，不能把 quick 写成成功。

暂停前还有两个较晚的修复，尚未进入共享 `build/java` / `build/native` / `build/v2-release`：编译器 AGGRESSIVE 初始集合移除旧两种内联器，避免关闭全部八个新阶段时重新启用旧内联；独立 `compiler-switches.jar` 已通过 415 JUnit、60 组旧档位基线，8 组默认 O3 产物修复前后字节相同。另修复宿主缓存 `ofunction` 后经公开 `invoke_bound` 调私有函数的绕过，独立 `build/v11-native` 的 runtime/exec-v11/vm-session 三套通过；七模式各 1,325 检查全部通过且零 violations，详见 `build/v11/runtime-results.md`。恢复后须重建共享运行库和 Java，进行对应补验，不能使用旧 Release 做最终性能或安装消费验证。

Release O2/O3 的 19 负载、7 轮 × 5 批性能尚未运行。`tools/bench_compiler_o2.py` 已补实际运行库/LLVM 哈希、缺失 LLVM 的显式失败、总体门槛与六类 O3 目标分开的状态。最终 installed CMake 静态/动态与 JNI 消费验证仅准备，未执行。输出计划在 `build/v11/installed-consumer-final/`，待最新 Release/JNI 构建后再做。最终 200 seeds O3 生命周期重跑、完整普通与 sanitizer 也尚未执行。

## 已接通的路径

V1 删除 getDepth 的原有工作已保留。V2 已完成 ABI 2、局部活跃 slot 的恢复/写回、LLVM 流水线测量及操作数上限 4,096；恢复单元仍限 8,192，多活跃局部的最坏编译时间目标未完成。macOS MODULE 的 `.dylib` 后缀已修复，后续测量与原生重放使用实际重建的模块。

V3/V9 的 opcode 54–60、私有入口及内联调试链已贯通 Java/C++ 编解码、树、VM 和适用的 JIT 路径。私有函数只在同模块直接调用中可见，公开 bound helper 也不能绕过访问控制。exec 版本保持 10，旧 wire 样本未改，V10 发行收尾不在本轮。

V4–V8 的 O3 实现集中在 `ExecO3`、`ExecSequenceInliner`、`ExecSpecializer`、`ExecObjectOptimizer`、`ExecScalarReplacement` 与 `ExecOptimizationCost`。具体支持和回退范围见 [O3.md](../compiler-optimization/O3.md)。O3 内部始终保留源位置和内联来源，只在不带 -g 的最终输出时去掉元数据，确保 -g 不改变执行指令。

## 本轮发现的语义边界

删除字面量对象分配会改变全脚本 next_block，后续公开地址可观察；删除自动对象分配也可能改变宿主预留堆尾的收缩。对象优化因此保留原分配、构造、复制和清理，只把已证明安全的字段读写/纯 getter 改成局部槽。分配消除需要额外的全局观察证明，不能只凭单个地址不逃逸。此限制已补入 DESIGN。

无类型 `def(x)` 可能没有 declared-type，值也可能是 void；O3 候选、槽压缩和对象优化必须保守处理，不能 NPE 或擅自用 int 0 替代。完整语料已捕获这类错误，需保留新增回归。

严格 seq 只允许局部赋值与调用，禁止嵌套控制流。DESIGN 6.1 的一般 if/while/多 return 被调者尚与 4.1 冲突，因此当前只展开直线标量候选；一般析构展开也未完成。不得把保守子集写成全部 V5/V7 已验收。

调用者也必须封闭：失败调用发布错误栈、成功调用清除已发布错误栈的时机，可由析构回调观察。全语料发现四处差异后已收紧 seq；不能仅凭被调者无错误就重新开放对象/宿主调用者。旧 O1/O2 的成功调用清栈缺陷由冻结旧 jar 复现，当前保留其原产物，新增两项发布栈用例显式只比较 O0/O3。

## 验证与下一次接手

本轮完成点以文件开头和两份 O3 JSON 的 `local_cleanup_followup` 为准。后续可评估分支合并的字段缓存事实、临时槽活跃性复用；本轮没有扩大到一般 CFG/SSA 或对象原生 JIT。一般控制流内联仍须先解决严格 seq 契约，分配消除仍须保留地址、堆长度和生命周期的可观察行为。用户明确不要求补齐所有性能目标。

本机无 valgrind，callgrind 未运行；V2 最坏约一秒目标仍未证明。V10 版本、二进制样本与发行收尾不在本轮。全部源码修改保留，历史 LLVM license 副本仍在 `build/backup/LLVM-LICENSE.untracked.txt`。
