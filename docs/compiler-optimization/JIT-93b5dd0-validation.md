# JIT `93b5dd0` 联合验收阻塞

2026-10-20，O2 的联合验收发现 LLVM `stress` 模式的既有运行时问题。
使用实施 O2 前保存的 `1e0926d` 编译器、默认 O0，可单独复现；
联合工作树的运行时源码与 `93b5dd0` 完全一致。
O2 分支未修改执行器，修复及运行时回归测试交由 JIT 工作维护。

## 最小复现

```azscript
int f(int a) {
    if (a == 0) { return 1; }
    int x = a + 1;
    return x;
}
int main() { return f(0); }
```

保存为 `build/jit-early-return.azs`，使用带 LLVM 模块的原生构建：

```sh
java -cp "$(cat build/java/classpath.txt)" azertia.Main compile \
  build/jit-early-return.azs -O0 -o build/jit-early-return.abd
AZSCRIPT_EXPERIMENTAL_VM_BACKEND=1 AZSCRIPT_JIT=stress+llvm \
  build/native/interpreter/azscript-run build/jit-early-return.abd
```

期望输出 `1`，退出码 0。实际 `SIGABRT`（Python 返回 -6，shell 返回 134），
标准错误为 `terminate called without an active exception`。
同一产物在 `off`、`stress`（可移植 MIR）、`eager+llvm`、`on:3+llvm` 下均返回 `1`。
`double` 参数、提前返回 `boolean` 的同类代码也能复现；仅有条件局部声明或零次循环的
两个对照程序均正常。同一旧 O0 产物在 AArch64 / QEMU 的 LLVM stress 下也触发
目标程序 `SIGABRT`，不是 x86_64 特有现象。

诊断调用栈的关键路径：

```text
TierFrame::set_local_shadow
jit::write_back
jit::NativeCode::run
jit::Tier::reached
ScalarExecutor::State::reach_bound
```

`interpreter/vm/execution.cpp:1072` 的 `cell == no_index` 分支终止进程：
退出写回把不存在的局部槽当成了有效值。静态检查发现 LLVM 的
`entry_and_resumes` 使用 `store_tag` 恢复入口标签；当分析结果只有一种可能值类型时，
`store_tag` 会将传入标签替换为该类型常量。**待 JIT 侧确认**：对于尚未声明的可选局部槽，
这可能把实际的 `AZJIT_ABSENT` 改成有效标签，使 `write_back` 尝试写回不存在的槽。
不要通过放宽比较或直接忽略写回来掩盖错误。

## 验证范围与证据

- 七种 JIT 模式的完整 VM 预算重放各 55,460 次均通过；O2 的 54 案例定向差分和
  463 案例完整优化语料也通过。它们尚未覆盖上述具体提前返回形状。
- 六种模式的完整后端对齐各 1,376 次通过。LLVM `stress` 对齐有 11 个异常观察：
  数学库 6 个、容器库 2 个、虚方法及 limits 各 1 个异常退出，另有端到端套件 1 个超时。
- 使用旧编译器单独重跑数学库，仍有同样 6 个异常退出（34 次调用中 28 次一致），
  已排除 O2 编译变换及多模式并行执行是必要触发条件。
- 另一个超时涉及 `int main(){return 1+1+...;}` 的 100 项连加，原套件上限 10 秒；
  直接重放得到正确结果 `100`，LLVM stress 耗时约 15.7 秒，超过原 10 秒等待上限；
  eager LLVM 约 0.116 秒、纯 VM 约 0.010 秒。此时其他验证仍运行，这不是正式性能数据，
  只用于确认它属于等待上限问题，不能与局部槽异常混为同一个原因。

原始完整失败报告：联合工作树
`build/runtime-perf/alignment-stress-llvm/report.json`。
旧编译器数学库重放：
`build/runtime-perf/alignment-stress-llvm-baseline-math/report.json`。
最小复现、逐模式结果与诊断回溯：O2 工作树的
`build/o2-jit-baseline-repro/{early-return.azs,small.log,trace.log}`。

在修复提交完成相应重放前，不能宣告 JIT 联合验收全部通过。
