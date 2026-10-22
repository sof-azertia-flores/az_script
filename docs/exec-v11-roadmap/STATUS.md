# exec v11 实施记录

本文件记录 exec v11（O3 及配套更新）各阶段实际完成的内容和验证证据；设计见 [DESIGN.md](DESIGN.md)，阶段与验收见 [PLAN.md](PLAN.md)。未运行或未通过的检查明确写出，不沿用历史结论。

| 阶段 | 当前状态 | 证据或下一步 |
| --- | --- | --- |
| V0 | 完成 | 设计、计划与本记录 |
| V1 | 完成，定向验证（完整验证未完成，见下） | `a5d6ba0` |
| V2 | 完成，定向验证（完整验证未完成，见下） | `fd52bd4`，测量见下 |
| V3 | 完成，定向验证 | `38330c1`、`5c4caa5`、`7ab4af0`、`9004673` |
| V4 | 完成，定向验证 | `f22357a` |
| V5 | 完成，定向验证 | 内联、槽压缩与测量见下 |
| V6 | 完成，定向验证 | 常量实参与泛型上下文专用化 |
| V7 | 部分完成，定向验证 | 守卫去虚化、标量替换完成；字段缓存未开始 |
| V8 | 完成，定向验证 | 区间证明的无检查运算 |
| V9 | 部分完成，定向验证 | 原生 `reserve_block`；上限重新测量未开始 |
| V10 | 不在本轮 | 发布收尾 |

## V0：设计与计划（2026-10-21）

用户询问 O3 的可行性后决定：O3 及需要格式变化的优化放入下一个大版本 exec v11，同时包含内联、专用化与对象优化，彻底删除 `getDepth`；先完成完整计划，再由 Claude 自行实施。开发分支 `exec_v11_main`，每个大的更新合入该分支，开发期不改 exec 版本号。

依据：三份只读调查（`getDepth` 与 exec 版本的全部位置；VM/JIT 中新 opcode、虚调用、trace 与规模上限涉及的代码；O2 编译器基础设施）。主要结论已写入 DESIGN 第 1 节和第 4–7 节。

## V1：删除 `getDepth`（2026-10-21）

`a5d6ba0` 从编译器内建表、运行时内建实现、`ownership::depth` 与 VM 宿主桥的伪造作用域链中删除 `getDepth`；内建 ID `0x0abd0001` 保留为空位，加载时拒绝。所有权语料、`lifecycle_fuzz`、一致性变异体（删去 depth-counts-callers）与相关文档同步改写，作用域深度不再可观察。`0aca80c` 为 sanitizer 下的 `end_to_end` 增加 60 秒看门狗（负载高时 `while(true)` 到达步数上限前可能超时）。

定向检查：Java 375/375，ctest 19/19，language_ops 360、compact_exec 404 个 wire 用例、compiler_optimization 27 例、lifecycle_fuzz 自检通过。

## V2：JIT 规模（2026-10-21）

`fd52bd4`：JIT ABI 2，退出只写回活跃且已写的 slot，写入过的 slot 在入口块初始化为缺失；超过 2,048 个操作的函数使用轻量 LLVM 流水线（sroa、early-cse、instcombine、simplifycfg 与 CodeGenOpt None）。原生规模上限提高到 6,144 个操作、65,536 个恢复单元。

测量：stress 模式下 100 项表达式 16.5 s → 0.4–0.5 s；eager 模式 8,522 个操作的长链 O2 流水线 5.7 s → 分层 1.1 s，约 6,100 个操作 0.71 s。callgrind（V1 对 V2，JIT）：integer_loop −11.6%，branch_loop −9.0%，调用约 −2.5%；VM 不变。

## V1+V2 完整验证

第一次在 `0aca80c` 上顺序运行 `build_and_test.py --offline --jit` 与 `--offline --sanitize`，普通构建通过到 JNI 与各端到端阶段后被容器重启中断，无失败但不计为通过。

第二次在 `f585b55`（V1–V8）上运行：两次构建都在第一个测试阶段 `tests/test_export_distribution.py` 失败——`LANGUAGE` 与 `EXEC_FORMAT` 链接到 `docs/exec-v11-roadmap/`，而发行包不含该目录（5 处链接不可重定位）。已在 V9 提交中改为不带链接的源码路径，该测试 23/23 通过；之后的阶段均未运行。**V1–V9 的完整验证（普通 `--jit` 与 `--sanitize`）仍待运行。**

## V3：格式增补（2026-10-21）

- `38330c1`：opcode 54 `seq`（读取端拒绝序列层的声明、跳出序列的 break/continue、return 及对象所有权 opcode）与 55–57 回绕整数运算；树、VM lowering（无作用域的 statement_enter/leave 加 copy）、两个 JIT 后端。
- `5c4caa5`：`entry_kind=2` 私有函数：宿主调用与反射视为不存在，`hasFunction` 为假，签名不能声明，导入与其他模块（含 hint 映射）的调用在 flush 时拒绝；main 与生命周期函数不能私有；VM 按普通帧分类。
- `7ab4af0`：opcode 58 `guard_method`；目标作为无上下文的方法表项读入，因而随 namespace 重定位并按方法表项校验；`heap::find_methods` 为 `methods_at` 的不报错形式；VM 新增对象帧指令。
- `9004673`：七字段扩展调试记录（每个位置的内联下标、内联表、display id），由两引擎共用的 `trace::note_script_frame` 先输出被内联帧；ID 在 trace 时取函数装载后的 namespace，不参与重定位。

验证：每项都有 C++ 读取/拒绝测试、Java 编解码测试与 compact_exec 独立 wire 用例（最终 447 个，树与 VM 均通过）；`seq` 与 `guard_method` 程序在全部预算点上树、VM、JIT stress/eager（interp 与 LLVM）逐字节一致；hint 模块中的内联帧 ID 随装载 namespace 变化，两引擎一致。旧 v10 样本未改动。

## V4：O3 档位骨架（2026-10-21）

`f22357a`：`Optimization.AGGRESSIVE` / `-O3`，O2 阶段集合改为显式列表；`compiler_o2_end_to_end.py`、`compiler_optimization_corpus.py` 增加 `--o3`（v10 运行库检查只用于 O2），`bench_compiler_o2.py --levels` 比较任意两个档位，完整构建以 `--o3` 运行 O2 测试。O0/O1/O2 产物与前一编译器逐字节相同（O2 测试源码 168 次编译）。

## V5：O3-a 内联与槽压缩（2026-10-21）

- `ExecSequenceInliner`（阶段 `SEQUENCE_INLINING`）在 O2 之后运行：把封闭标量函数（O2 的安全集合，因此无递归、无全局、无宿主）内联到任意表达式位置，包括运算数、条件、循环条件和惰性分支；链深 4；被调者节点 ≤256、调用者节点上限 `min(2048, max(3n, n+512))`，槽 ≤256。实参与返回值类型由标量分析证明，类型无法证明的调用（如对象字段实参）保持调用，以保留运行时实参检查及其错误消息。
- 被调者可含 if/while/break/continue 与多个 return：提前 return 通过把其后的语句移入另一分支变为尾部（需复制的尾部 ≤16 个节点），尾部写入结果槽；循环内的 return 不内联。未写的参数以常量或调用者局部量直接代入，其余参数写入快照槽。
- 被内联的槽在调用者根块以常量初值声明一次；互不嵌套的序列共享同类型的槽。`ExecSlotCompaction`（`SLOT_COMPACTION`，`--no-slot-compaction`）另外合并调用者自身在互不嵌套块中声明一次的标量局部量（例：两个 for 循环的函数 8 → 4 个局部量）。
- O3 不再运行 O2 的语句根内联（`SCALAR_INLINING`）：它会丢失被内联帧；序列内联覆盖其位置并生成内联调试记录，`-g` 时 O3 的 trace 帧、函数 ID 与位置和 O0 相同。O1 的内联保留（被调者不会出错）。生命周期钩子（namespace 0）不内联其他 namespace 的函数。
- 可能出错的被调者只内联到不持有对象的调用者：错误在调用者自身展开期间运行的析构函数中，宿主回调看到的是错误离开失败函数入口时发布的 trace，而被内联的函数没有入口（语料 `own-g13b` 发现）。经标量分析证明不会出错的被调者不受此限。
- 标量分析新增：清理区域的受保护体在原位分析（调用者须保证清理体不写局部量；O2 的封闭区域不含清理区域，O2 产物不变），以及非整数/布尔常量不产生事实。

验证：Java 392/392（新增 `ExecO3InliningTest` 10 项）；`compiler_o2_end_to_end.py --o3`：56+11 个用例，O0/O3 语义一致且（新增检查）除预算耗尽外 trace 帧完全一致，JIT eager 与 stress+llvm 重放一致；O0/O1/O2 与前一编译器逐字节相同（213 次编译，含基准源码）。语料（`compiler_optimization_corpus.py --o2 --o3`）：461 例、3,688 次观察全部通过，O0–O3 语义一致、两引擎一致（V5 提交的编译器）；此前一次运行发现 `own-g13b`（见上条规则），已修复。之后发现 V5 在构造函数安装方法表的程序上以 `-O3` 编译时出现内部错误（遍历进入了方法表项），`ec37ff5` 修复并加入回归用例；语料中没有触发它的程序。

性能（Release，负载较高时的快速测量，3 轮×3 批，O3/O2 耗时）：call_1 VM 0.66、LLVM 0.057；call_4 0.69、0.043；call_16 0.77、0.027；scalar_call 1.00、0.68；integer_loop（无调用，函数相同）1.05、1.10 为噪声。调用负载达到 ≤0.8 的目标；正式 7×5 测量待机器空闲时运行。

未实现（相对 DESIGN 第 6.1 节）：递归强连通分量展开一层；循环内 return 的内联。

## V6：O3-b 专用化（2026-10-21）

- `ExecSpecializer`（`SPECIALIZATION`，`--no-specialize`）在 O2 之后、序列内联之前运行。对封闭标量被调者的调用，若某些 int/bool 常量实参对应被调者从不写的参数，则以“被调者 + 常量向量”为键生成私有克隆（entry kind 2），代入常量后以普通函数身份再跑一次 O2 的逐函数阶段（`ExecO2.optimizeFunction`，O2 本身的路径不变）；克隆节点数须不超过原函数的 3/4，否则丢弃。
- 对泛型函数（隐藏上下文数 > 0）的调用，若调用点的上下文完全固定（不含引用），则把函数体中的上下文引用替换为这些固定上下文，生成隐藏上下文数为 0 的私有克隆；擦除为 any 的返回类型由函数体中全部 `return_typed` 的固定上下文确定为同一具体类型，否则放弃。函数体不引用上下文时所有上下文共用一个版本。
- 版本上限：每个函数 4 个、每模块 64 个。克隆 ID 取原函数 namespace 中从 `0xffff` 向下的空位（跳过模块函数与外部签名）。克隆可被序列内联；被内联时内联记录与克隆自身的调试记录都显示原函数的名称、位置与 ID（扩展记录的 display id）。
- 标量分析的 `eligible` 接受 entry kind 2（O2 阶段不存在此类函数，O0–O2 产物不变：255 次编译逐字节相同）。

验证：Java 397/397（新增 `ExecSpecializerTest` 5 项）；O2 测试 `--o3 --only o3-`：14 例（含常量克隆、常量克隆中的除零 trace、泛型克隆），O0/O3 语义与 `-g` trace 帧一致，JIT eager 与 stress+llvm 重放一致。

## V7：O3-c 对象（进行中）

### 守卫去虚化（2026-10-21）

- `DEVIRTUALIZATION`（`--no-devirtualize`）由序列内联器执行。`call_virtual` 的接收者是变量、其余实参是纯表达式时，猜测目标 T：接收者只被定义一次且来自工厂函数时，取该工厂的构造函数最后安装的方法表中该槽的绑定，否则取模块内所有方法表对该槽的唯一绑定。T 须是“接收者只被开头的 object_address 检查使用、其余为封闭标量”的方法。
- 改写为 `select(guard_method(r, slot, T), seq{实参快照; T 的接收者检查; T 的内联体}, 原 call_virtual)`。守卫在 `call_virtual` 不会进入 T 或会出错时为假，此时执行原调用，错误与 trace 不变；实参不触及堆，两条路径看到同一方法表。返回类型须与 `call_virtual` 期望一致，被内联方法的帧通过内联记录还原（`-g` 时 trace 与 O0 相同）。
- `ExecScalarAnalysis` 未修改 C06 的 `Escape` 摘要：`ExecLoopMotion` 的 `isolated` 检查已拒绝任何非标量变量，`OBJECT_ADDRESS`/`BLOCK_ADDRESS` 的分类不影响 O1/O2 产物。

验证：Java 398/398；O2 测试 `--o3 --only o3-`：19 例（单态、交替类别（守卫失败路径）、删除后调用的错误、被内联方法中的除零 trace），O0/O3 语义与 `-g` 帧一致。性能（Release 快速测量，VM，O3/O2）：virtual_monomorphic 0.73（目标 ≤0.9），virtual_alternating 0.90，object_fields 1.06（未变，噪声）。

### 标量替换（2026-10-21）

- `a2432cb`：opcode 59 `reserve_block`，取走下一个字面量块 ID 但不建块，使被拆除的字面量之后的所有字面量和复制对象地址与 O0 相同（`state.probe.next_block` 跨档位比较）。读写端、树与 VM 执行器、Java 模型与编解码、各操作码表及 compact exec 线格式测试同步。
- `SCALAR_REPLACEMENT`（`--no-scalar-replacement`）在序列内联之后、槽压缩之前运行（`ExecScalarReplacement`）。值工厂须为“`new_block(w)`、以参数调用构造函数、无析构/无上下文的 `ob`、`ro`”四条语句；构造函数与方法的函数体只能包含接收者检查、空方法表、字段存储和对同类函数体的调用，值只能是常量、非接收者参数和字段，因此被内联的部分不会出错，也不会丢失错误帧。
- 只定义一次、来自这样的工厂（或复制自候选）的对象局部变量，在每次使用都是字段读写、复制或字段方法调用时成为候选；其他任何出现（借用 `C (*)`、指针、传参、返回）拒绝该对象，拒绝在复制关系中双向传播。
- 改写：实参先求值到快照，再 `reserve_block`，每个字段一个无初值局部变量，构造函数内联为字段存储；构造函数须在读取前存储每个字段。复制先 `reserve_block` 再复制字段。语句位置的方法调用保留实参快照和返回值语句，值位置的方法调用只允许平凡实参。被删除调用的参数和返回类型检查必须由 `ExecScalarAnalysis` 在改写后的函数上证明，否则函数保持不变。内联语句定位在调用处或定义处（`-g` 时预算停止不显示被调函数的行）。
- 构造函数与方法是 1 号入口（类内部函数）；只内联其函数体，入口类别不影响改写。

验证：Java 404/404（新增 `ExecScalarReplacementTest` 6 例：字段与方法、复制、借用对象保留、计算型方法与虚方法保留、计算型构造函数保留、开关）；O2/O3 测试全量 79 例、1438 次观测、684 次预算检查通过（JIT probe:7、eager、stress、on:2+interp 重放），新增 `o3-scalar-fields`、`o3-scalar-copy`、`o3-scalar-borrowed`、`o3-scalar-argument-error`（构造实参除零，`-g` 帧与 O0 一致）。全量运行用的是改定位之前的编译器；改定位后 `--only o3-` 23 例（probe:7、eager、stress）重新通过。`lifecycle_fuzz` 种子 0–199 的生成程序（临时脚本：O0 与 O3 均带 `-g`，树与 VM 各运行一次，按 `framed()` 比较）全部一致。性能（Release 快速测量，3×3，O3/O2）：VM object_fields 0.149、object_copy 0.205、virtual_monomorphic 0.635、call_4 0.566；on:3+llvm object_fields 0.164、object_copy 0.168。

## V8：无检查运算（2026-10-21）

- `UNCHECKED_ARITHMETIC`（`--no-unchecked-arithmetic`）在标量替换之后、槽压缩之前运行（`ExecUncheckedArithmetic`）。对每个 `analyzable` 的函数（槽数与节点数在 O2 区域上限内）建立精确模式的 `ExecScalarAnalysis`，两个操作数都是 int、按区间计算结果在 int32 内的 add/minus/multiply 改为 opcode 55–57。判断全部在原树上完成后再改写；清理体不访问，序列中的语句使用所在语句的事实（序列不写局部变量）。
- 精确模式只供 O3 使用，O2 的分析结果不变：除法结果不超过被除数，余数小于除数且与被除数同号；比较的另一侧为 int 局部变量时按其区间收紧（原先只接受常量）；加宽后的不动点再做至多 8 轮不加宽的下降迭代（Jacobi），恢复循环中被加宽丢掉的界（例如 `total=(total*7+i%13)%10007`）。分析也识别回绕运算：范围外结果为任意 int，但不会失败。
- 回绕运算的其他操作数组合与有检查运算相同，所以只省去溢出检查；证明不了的运算保持原样，边界上的溢出错误与 trace 不变。

验证：Java 408/408（新增 `ExecUncheckedArithmeticTest` 4 例；`ExecO2LoopTest` 一例以 `allOf` 选择阶段，改为排除本阶段，以保持其循环外提检查的原意）；O2/O3 测试 `--only o3-` 27 例（probe:7、eager、stress），新增 `o3-unchecked-loop`、`o3-unchecked-edge`（`a<=2147483646` 时 `a+1` 回绕且得到 2147483647）、`o3-unchecked-overflow`、`o3-unchecked-loop-overflow`（溢出仍按 O0 报告）。O1/O2 产物（`-g`）与 V7b 编译器逐字节相同：200 个生成程序、示例与 O3 草稿源码共 472 次编译。性能（Release 快速测量，3×3，O3/O2，含 V5–V7 的全部 O3 阶段）：VM integer_loop 0.864、branch_loop 0.751、scalar_call 0.862、range_local 1.012、call_4 0.554；on:3+llvm integer_loop 0.894、branch_loop 1.000、scalar_call 0.670、range_local 1.108、call_4 0.070。LLVM 后端的 int 快速路径（V3）已把回绕运算编为不带溢出退出的 add/sub/mul；其余 JIT 消费与上限测量留到 V9。

## V9：JIT 消费 v11 构造（部分，2026-10-22）

- 回绕运算：V3 起两个后端已支持；LLVM 的 int 快速路径不带溢出退出（无需改动）。`seq` 在 VM lowering 中消失，私有函数是普通帧。
- `reserve_block`：JIT 分析原先以“unsupported opcode”拒绝含该指令的函数，标量替换后的循环因而只能在 VM 中执行。现在分析把结果寄存器记为 void；MIR 新增 `reserve_block` 操作，调用不抛出的 `reserve_block` 辅助函数（`blocks::reserve`，ID 用尽时返回 0），失败则 redo 退出，由 VM 重新执行并报告同样的错误；解释后端与 LLVM 后端都已实现。新增辅助函数与 MIR 代码改变布局，`AZJIT_ABI_VERSION` 升为 3。
- 未做：按 O3 产物重新测量原生上限（`native_operation_limit`、`native_resume_limit`）；`guard_method` 仍只出现在对象帧中，JIT 不处理。

验证：ctest 19/19（`jit-analysis-tests` 新增 `reservations`：含 `reserve_block` 的函数可编译，且该操作带退出点）；O2/O3 测试 `--only o3-` 27 例，JIT 模式 probe:7、eager、stress、eager+llvm、stress+llvm，486 次观测；object_fields 的 O3 产物在 eager 下 2 个函数原生编译、结果与 `next_block` 与 VM 相同。性能（Release 快速测量，3×3，O3/O2）：VM object_fields 0.132、object_copy 0.160；on:3+llvm object_fields 0.021、object_copy 0.031（V7b 时 LLVM 为 0.164、0.168，当时这两个函数未进入 JIT）。

## 交接（2026-10-22）

本轮在此暂停，之后在本地继续。分支 `claude/runtime-performance-handoff-5mdt6h` 包含 V1–V9（部分）的全部提交；`exec_v11_main` 尚未更新。

1. 在干净工作区顺序运行 `python3 tools/build_and_test.py --offline --jit` 与 `--offline --sanitize`（V1 以来尚无一次完整通过），通过后补记本文件并把 `exec_v11_main` 快进到该提交。
2. 未完成：V7 字段缓存；V9 的原生上限重新测量；正式 7×5 Release 基准（以上测量均为 3×3 快速测量）。
3. 一次性检查脚本未入库：O0/O3 生命周期模糊比较（`lifecycle_fuzz.py --print-source` 生成、两档 `-g` 编译、树与 VM 运行、`framed()` 比较）与 O1/O2 逐字节对比（两份编译器 jar 分别编译同一批源码后 `cmp`）。需要时可按此描述重写，或加入 `tools/`。
