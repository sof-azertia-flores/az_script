# JIT 设计：基于 VM 执行器的优化层

日期：2026-10-19（UTC）。适用分支：`claude/runtime-performance-handoff-5mdt6h`。本文件是运行时性能计划（[../runtime-performance-roadmap/PLAN.md](../runtime-performance-roadmap/PLAN.md) 第 1 节）所列“JIT/AOT 后续独立项目”中 JIT 部分的设计，建立在 R12 之后的寄存器 VM 上，不改变 [ENGINE_SPLIT.md](../runtime-performance-roadmap/ENGINE_SPLIT.md) 中“VM 证明可靠后删除树执行器”的目标。分阶段实施与验收见 [PLAN.md](PLAN.md)，实际进度和验证证据见 [STATUS.md](STATUS.md)。

**用户确认的选择：** 目标 x86-64 与 AArch64；用 LLVM ORC 生成代码；第一个里程碑即包含优化层（类型特化、守卫、去优化）；JIT 编为单独的共享模块；LLVM 以固定版本源码构建；本环境用 qemu 验证 AArch64。

## 1. 动机

VM 已执行全部语言功能，并与树执行器在全部套件上一致（差异登记为空），比树快约 1.9 倍。但 `integer_loop` 每次迭代仍约 9.6k 条机器指令（callgrind，bench(512) 每次调用约 4.94 M Ir）。主要开销是每条指令的语义工作：64 字节的 `RegisterValue`、24 字节带标签的 `Value`、cell 到 `SegmentedPool` 的间接访问、每个 AST 节点一次 `tick`、按名字比较的 `value_ops::binary`，以及每条指令写入位置。仅去掉分派收效很小；JIT 必须在可证明不可观察的地方去掉这些工作，在不能证明时把控制交回 VM。

## 2. 可观察契约

JIT 不引入新的允许差异，`tests/runtime_differences.json` 不增加条目。与 VM（因而与树执行器）逐项一致的内容包括：返回值；错误的 C++ 异常类型和消息；trace 帧与源码位置；每个预算扫描停止点的 `remaining_steps`；宿主回调的次序和实参；global、堆与对象记录；workspace 失败位置及 used/peak。

## 3. 总体结构：VM 的一个层，而非第三个引擎

- **帧兼容。** 原生代码直接运行在 VM 自身的 `FrameRecord` 和 workspace 向量上。调度循环在**恢复点**进入原生代码，原生代码返回一个**退出类型**，之后由 VM 执行下一条指令。解释执行一个函数、一个区域或一条指令是正常路径，不是悄悄切换引擎；树执行器不参与。
- **边界处 VM 内存精确。** 进入与退出都经溢出缓冲区，并由运行时 C++ 代码按**退出映射**写回或装入；两个后端共用这一份实现。
- **原生代码不抛异常、不构造错误消息。** 一切可能失败的操作都在产生副作用之前检查；失败时退出，由 VM 重新执行该指令，产生相同的错误和 trace。辅助函数为 `extern "C" noexcept`、失败原子并返回状态；生成代码为 `nounwind`，不注册 EH 帧；`vm/execution.cpp` 的 `fail()` 仍是唯一的展开过程。
- **调用全部经调度器。** 脚本调用、宿主调用、析构 continuation、对象操作和清理区域由 VM 执行（`STEP` 退出），被调者再进入自己的原生代码。因此 VM 保持非递归，准入、原生栈与 trace 协议只有一处实现，宿主调用期间栈上没有原生 JIT 帧。

## 4. 退出协议

`interpreter/jit/abi.h` 只含 C 类型：

```
struct azjit_frame { void* vm; uint32_t* locals; Value* const* globals; const void* constants;
                     uint64_t steps; uint64_t* spill; uint32_t resume, exit_pc, exit_site; };
uint32_t entry(azjit_frame*);   // 返回 STEP | REDO | DEOPT | REJECT | YIELD
```

- **进入：** `exit_map::fill_resume(site)` 从 VM 内存把活跃值装入 `spill[]` 并执行类型守卫；守卫失败返回 `REJECT`，不改变任何状态。原生代码按 `resume` 分支。
- **退出：** 原生代码把影子值存入 `spill[]` 后返回；运行时写回每个仍有影子的寄存器值与局部 cell 值（影子标签为 `absent` 的槽由 VM 内存负责，不写）、`returned/broken/continued`、静态的 `statement_depth/loop_depth`、剩余步数和 pc。寄存器的引用字段、cell 的分配/保留/释放、`locals[]`、`declared[]`/`declared_count` 与 `scope_marks[]`/`scope_count` 由辅助函数在 VM 原指令处直接写入内存，退出时无需重建；原生执行的 `set_return` 由辅助函数写 `result` 与 `return_location`。`node/location/position` 不在原生代码中维护，由下一次 VM 序言（`execution.cpp` 调度循环开头）按 pc 重新计算。
- **退出类型：** `STEP`：VM 恰好执行 `exit_pc` 一条指令（调用、宿主调用、`cleanup_*`、`function_end`、慢速声明及任何未实现的操作码），然后在 `pc+1` 重新进入。`REDO/DEOPT/REJECT`：预计出错、预算不足或推测失败，VM 继续解释到下一个恢复点。`YIELD`：压力模式专用。
- **不缓存运行时可变地址。** 寄存器基址、cell、池页和 global 的 `Value*` 在每次进入时重新取得：宿主重入会让共享 `WorkspaceVector` 重新分配，`HostBridge::refresh_globals` 和 JNI 快照恢复会改变 global 的 SlotId。代码中不嵌入 SlotId、`Value*` 或寄存器基址。

## 5. VM 钩子：每条指令零开销

- 复用调度循环已有的边界检查 `frame.pc>=frame.end`。由层管理的帧把一个“重新查询阈值”存入 `end`；冷路径 `tier_dispatch`（`noinline`、`cold`）或者确认真正的函数结尾（抛出相同的 `logic_error`），或者进入原生代码。VM 的批量预算路径（标量帧中由 `tick` 开始、只含无副作用标量指令的直线段）在段内不检查边界，可能越过阈值；因此层在任意 pc 都可被查询：该 pc 若不是恢复点，只返回下一个阈值。
- 函数入口按每个函数一个不计入 workspace 的层槽选择 `end`，并递减入口计数；`loop_next` 回边递减回边计数，用于升层和 OSR（在循环头恢复点进入）。返回到有原生代码的调用者时经同一阈值机制。
- 恢复点：函数开头、每个可能 `STEP` 之后的 `pc+1`、循环头、清理区域的 `finalizer/after`；压力模式下每个 pc 都是恢复点。
- 计数器、代码、分析结果和溢出缓冲区都不计入 `WorkspaceBudget`，也不改变任何 `WorkspaceVector` 的增长方式。`tier_dispatch` 检查原生栈余量，不足时解释执行（结果相同）。

关闭 JIT 时，VM 的 callgrind 每调用指令数增幅不得超过 0.5%（[ENGINE_SPLIT.md](../runtime-performance-roadmap/ENGINE_SPLIT.md) 第 6 节的纯重构门槛）。

## 6. 分析与 MIR

- **控制流与深度。** 基本块取自已验证函数；`verify_flow` 保证每个汇合点的作用域、语句、循环和清理栈相同，因此这些深度在每个 pc 静态已知。声明列表与作用域标记则不是：语句可以在块内后续声明执行前离开该块（`branch_flow` 跳到块尾），汇合点两侧已声明的局部不同，因此它们留在 VM 内存中。
- **抽象解释覆盖全部操作码**（包括由 VM 执行的指令，以保证恢复点处的状态可靠）：寄存器为 `value|ref_local(i)|ref_global|ref_orphan|unknown` 加标签集合（`ref_local` 指局部当前的 cell），局部为 `未声明|已声明|可能已声明` 加声明时的作用域深度和标签集合。`declare_slot` 和 `scope_leave` 把指向旧绑定的引用标为孤立：寄存器引用会让 cell 在作用域结束后继续存活，因此释放前把仍可能被引用的局部影子写回 cell。
- **类型来源。** 常量、形参类型（`ANY` 形参需要守卫）、`value_ops` 的结果规则。宿主调用结果和 global 一律视为 `top` 并加守卫：`HostBridge` 不检查返回标签，global 的标签按 [SEMANTICS.md](../runtime-performance-roadmap/SEMANTICS.md) 的约定是动态的。
- **状态放置。**

| 类别 | 内容 | 处理 |
| --- | --- | --- |
| 影子（SSA） | 标量寄存器值、精确绑定局部的 cell 值、控制标志、剩余步数 | 退出时写回 |
| 静态 | 语句与循环深度 | 由退出点常量写入 |
| 直写 | 寄存器引用字段、`locals[]`、`declared[]`、作用域标记、cell 的分配/保留/释放 | 由辅助函数在与 VM 完全相同的指令处、按相同次序执行（LIFO 空闲表带代数；`declare_slot` 先分配后释放；需要扩容的分配交给 VM） |
| 常驻内存 | global（两个下标可能指向同一 SlotId）、字符串、孤立引用 | 经 `Value*` 和辅助函数访问 |

- 函数规模和分析时间有上限，超限的函数不编译。

## 7. 必须保持的精确语义

- **预算。** 基本块在 `STEP` 处和恢复点处结束。块开头若 `steps<k` 则以 `REDO` 退回块首，由 VM 在确切的 tick 处耗尽；否则在影子值上逐个 tick 递减，块中任何退出都带着精确余额。每个环都含 tick，原生循环必然终止。宿主可读写 `remaining_steps`，每次 `STEP` 前写回。
- **int32。** 加减乘用 `llvm.s{add,sub,mul}.with.overflow.i32`，与 `checked_int` 相同；`/` 和 `%` 按 i64 语义（`INT_MIN%-1` 为 0、`INT_MIN/-1` 溢出、除数为零报错），不生成未加守卫的 `sdiv/srem`；移位数不在 0..31 时退出。
- **float/double。** 以 double 计算，检查有限性，再 `fptrunc` 并检查 float 溢出；比较经 double；`ne` 为 `fcmp une`；不用 fast-math 或 contract 标志；double→float 在舍入前拒绝 `|x|>FLT_MAX`；double→int 在 `fptosi` 前检查范围。
- **地址。** 只做带检查的加减整数及比较；字符串及其它情况保持装箱，经 `value_ops` 辅助函数执行。

## 8. 后端与策略

- **解释后端**（`interpreter/jit/interp_backend.cpp`，不依赖 LLVM）：以同一 ABI 和退出映射执行 MIR，用于 J1–J4 阶段、sanitizer 构建，并作为 LLVM 后端的对照。
- **LLVM 后端**（`interpreter/jit/llvm/` → `libazscript-jit`）：只接收 POD 形式的 MIR、布局表和辅助函数地址，不包含运行时 C++ 头文件。进程内一个 `LLJIT`（JITLink、`InProcessMemoryManager`，W^X），每个 `Session::Prepared` 一个 `JITDylib`，每个代码版本一个 `ResourceTracker`；使用 TBAA/noalias 和只含标量优化的 O2 管线，代码生成前执行 `verifyModule`。
- **策略。** `AZSCRIPT_JIT=off|on|eager|stress|require`（编译模式可写成 `eager+llvm` 等以指定后端）、`AZSCRIPT_JIT_BACKEND=interp|llvm`（选择 llvm 而模块无法装载时，会话准备失败）、阈值变量、`AZSCRIPT_JIT_STATS=<路径>`（统计走旁路文件，因为观察协议拒绝未知字段）。在钩子处持 `runtime_mutex` 同步编译，结果确定。分析可靠且不做推测（global 与宿主结果按 `top` 处理），因此不需要去优化后重编；每个会话编译的 MIR 有上限。切换版本总是安全的：钩子处栈上没有原生代码。缺少模块或可执行内存被拒绝时只用 VM 并记入统计；`require` 模式把这种情况变为错误，避免验证在 JIT 未运行时空转通过。后台编译是以后的可选阶段。

## 9. 调用与对象帧

- 第一个里程碑中所有调用都经 `STEP`。
- 对象帧随后支持：把操作码主体移入 `State` 成员函数，供 `switch` 与辅助函数共用；凡可能启动驱动或 continuation 的指令（非空闲的作用域/语句结束、OBJECT 的存储/声明/物化/返回、delete/drop、`function_end`、调用、`context_default`、buffer 操作）都是 `STEP`；空闲快路径用辅助函数；对象局部只在无退出区域内缓存。
- 之后：原生快速调用与小函数内联（共用 enter/leave 辅助函数，入口失败经 `pending_error` 抛出，去优化时物化 `FrameRecord`），以及单态 `call_virtual` 缓存。

## 10. 构建、打包与平台

- **固定版本 LLVM。** `python3 tools/build_and_test.py --offline --jit` 先运行 `tools/build_llvm.py`（缺少缓存时去掉 `--offline` 下载），之后的构建因 `AZSCRIPT_JIT=AUTO` 自动构建模块并加入 LLVM 后端的门禁；不加 `--jit` 时，已有的固定前缀同样会被使用。`tools/build_and_test.py` 增加源码依赖 `llvm-project-18.1.8.src.tar.xz`（GitHub 发布页，SHA-256 校验，缓存在 `build/deps/`，`--offline` 不下载）。`tools/build_llvm.py` 只构建 `X86;AArch64`、Release、静态库、开启 RTTI，不构建工具、测试和 zlib/zstd/libxml2/terminfo，安装到 `build/deps/llvm-18.1.8-<triple>/`，按摘要与配置打戳；交叉构建复用主机的 `llvm-tblgen`。
- **CMake。** `AZSCRIPT_JIT=OFF|AUTO|ON`（AUTO：存在固定版本前缀时构建模块）；只在 `interpreter/jit/llvm/CMakeLists.txt` 中 `find_package(LLVM 18.1.8 EXACT CONFIG)`。模块为共享库，隐藏符号，版本脚本只导出 `azscript_jit_backend_v1`，并使用 `--exclude-libs,ALL`。运行库 `abdInvoker`/`abdInvokero` 不链接 LLVM。
- **装载。** 依次查找 `AZSCRIPT_JIT_MODULE`、运行库所在目录（`shared_library_directory()`）、可执行文件目录；`dlopen(RTLD_NOW|RTLD_LOCAL)`，不卸载；握手比较 ABI 版本与布局摘要。它属于同一构建，不是用户插件，不需要签名，只从上述位置装载。
- **发行。** 构建了模块时由 `tools/export_distribution.py` 一并导出，清单记录 JIT 架构；第三方说明增加 LLVM（Apache-2.0 WITH LLVM-exception）。
- **平台。** Linux/macOS 的 x86-64 与 AArch64；Windows 暂只用 VM。macOS arm64 经 JITLink 使用 `MAP_JIT`，宿主进程需要 `allow-jit` 授权；被拒绝时只用 VM。
- **AArch64 模拟验证。** `cmake/toolchains/aarch64-linux-gnu.cmake` 交叉构建（`-DAZSCRIPT_BUILD_JNI=OFF`）；`tools/build_llvm.py --triple aarch64-linux-gnu --toolchain … --tablegen <主机前缀>/bin/llvm-tblgen` 交叉构建 LLVM；ctest 经 `CMAKE_CROSSCOMPILING_EMULATOR`（`qemu-aarch64 -L /usr/aarch64-linux-gnu`）运行；`tools/emulated_test.py` 用包装后的观察宿主在模拟器中收集树参考、比较 VM 并按各 JIT 模式重放。Java 编译器仍在主机 JVM 上运行；arm64 上的 JNI 需要真实的 arm64 JVM，记为模拟未覆盖；所有权阶段（约 5.2 万个进程）在模拟器下过慢，未纳入。

## 11. 风险

LLVM 构建时间与模块体积（每个目标约 20–40 MB）；编译延迟（O2 下每函数 10–100 ms，因此阈值较高，eager 只用于测试）；LLVM 内存不足或致命错误会终止进程；可执行内存被拒绝（SELinux execmem、加固的 macOS）时只用 VM；valgrind 需要 `--smc-check=all`；qemu 较慢；VM 仍在演进，钩子限定在四处。
