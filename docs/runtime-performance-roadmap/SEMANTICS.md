# 运行时差分契约与覆盖清单

R03 基础设施；参考源码 `c2b572d6b36ca521c5faf2658adc296e403f47f1`。本文件登记迁移必须覆盖的入口，不把既有回归、静态指令出现或新旧相同实现对照称为新 VM 已验证。

## 独立进程和证据

`tests/observation_host.cpp`（构建目标 `observation-host`）是测试适配器，同一份源码分别链接冻结参考运行库和候选运行库，`--engine tree|vm` 选择每个进程使用的执行器。它不修改运行库、不加入执行器扣费点、不用于性能计时。基线适配器和基线运行库分别记录摘要；后续适配器 API 迁移也要重新记录摘要，不能覆盖原始计时基线。R03 及 R05 的历史证据由当时的 `tests/runtime_differential_host.cpp`（协议 1）产生；该适配器已被协议 2 的 `observation_host.cpp` 取代，自 S0.2 起的冻结参考见 [ENGINE_SPLIT.md](ENGINE_SPLIT.md) 第 6 节的 F0。

```sh
python3 tests/runtime_differential.py --self-test
python3 tests/runtime_differential.py \
  --baseline-host build/runtime-perf/f0-source/build/native/interpreter/observation-host \
  --candidate-host build/native/interpreter/observation-host \
  --candidate-engine vm \
  --baseline-library build/runtime-perf/reference/libabdInvoker.so \
  --candidate-library build/native/interpreter/libabdInvoker.so \
  --classpath 'build/java/compiler.jar:build/java/abdJava.jar:build/deps/gson-2.11.0.jar' \
  --baseline-revision '<F0 提交>' \
  --candidate-revision '<实际候选提交>' \
  --work build/runtime-perf/r03-observations
```

实际目录由构建者传入；`--work` 必须是新目录。每个案例/预算在两个新进程中运行，保留输入 ABD、输入 SHA-256、种子/生成配方、命令、两个完整观察和总报告。宿主异常、进程崩溃、超时、额外 stderr 或非法输出均是基础设施失败，不能把“双方都崩溃”计作相等。基线与候选路径不能相同，但二进制摘要可以相同：R03 当前阶段本就需要验证相同执行器的观察可重复性。

可选 `--classpath` 启用源码、调试位置、析构、首次错误及虚方法案例；省略时仅运行独立 wire/生成案例。`report.json` 列出静态出现/未出现的 opcode；它**不是**执行路径覆盖。源码编译器也不参与执行计时。

## 精确比较内容

宿主仅在完整观察写出后退出 0，脚本失败属于观察值。观察协议版本为 2（在协议 1 字段之外增加下文最后三项）：

- 返回实际 ABI 标签和值；int32 为精确整数，float/double 为 IEEE 位模式，address 为完整 uint64 十六进制，字符串及 stdout 为原始字节十六进制；区分 void 与 null 宿主指针。
- 宿主回调的有序实参快照、嵌套调用返回事件；脚本 print/析构 print 的 stdout；构造/析构事件通过明确 extern 回调记录。
- 调用结束或异常后的 global 内容、活动堆分配起点/长度及每个 slot 的值、对象登记/方法表数量。销毁单独记录异常、事件、stdout 和剩余堆状态，不覆盖原调用错误。
- 错误公共异常类别、精确消息、完整已发布 trace（function ID、native 标记、name/file/line/column、截断帧数）、失败阶段、faulted 标记和 remaining_steps。
- 对象值按结构访问分配测试观察编号，并保留重复引用关系；不使用 native 指针作为比较值。**address 标签的值不规范化**，包括 first-fit 起点、用户输入高位地址和脚本暴露的对象地址。
- 回调 `0x6dff0005` 在宿主回调内记录当时的 `active_calls`、`remaining_steps` 和已发布 trace，用于检查析构、重入及错误展开期间的调用深度、预算和 trace 状态；错误另记录 `describe_error` 文本，异常类别区分 `logic_error`。
- 进程级对象状态探针：调用后与销毁后各取一次全局作用域 owned 列表、对象登记明细（起点、manual、析构 ID、上下文数、方法表长度）、下一个块 ID（`blocks::create(0)` 的地址）和 first-fit 位置（`heap::alloc(1)` 后立即释放）。探针本身消耗一个块 ID，两个执行器在相同位置执行相同探针，扰动一致且同样受比较。
- `engine` 为运行库实际报告的执行器（是否存在 VM 会话），不是命令行回显；按指定执行器检查，报告其它执行器的观察直接失败，其余字段按上文严格比较。

没有忽略错误、trace、地址或浮点的通用开关。已登记的少数架构资源差异必须以后按具体案例实现；R03 不提供豁免。对象结构观察目前不是完整快照对照：buffer slab 的所有 element、绑定 context 内容、持久化 snapshot 字节以及 JNI throwable 身份仍由既有专项回归覆盖，后续阶段须接入新 VM 对照。

基础案例有独立预期结果，不能只因两个执行器同样返回错误就通过。自检故意改变返回 tag、负零位模式、事件顺序、stdout、全局/地址、错误、trace、预算、teardown 和键集合，确认比较器检测差异；另启动真实子进程检查协议及非零退出。

R03 基础设施实测：两个分别编译的适配器都链接原始 Release 运行库，23 个案例、145 对独立进程观察通过，包含 5 个案例从预算 1 到成功扣费数+1 的完整枚举；构造/析构案例覆盖预算 1…92。stdout 含 NUL/UTF-8、onload 创建且 pre-destroy 释放的可见原始堆也有独立预期。证据在 `build/runtime-perf/r03-self-baseline-complete/`。另用明确标记的错误包装进程只给种子案例返回值加 1，工具退出 1 并保存差异，在两次尝试中缩至空操作配方；证据 `build/runtime-perf/r03-negative-control/`。这验证观察器和缩小器能发现已知差异，**不**代表候选优化或新 VM 已通过。初次空字段类夹具被编译器拒绝，修正后重跑，原失败日志保留。

随后对 R05 未提交候选（`build/native/interpreter/runtime-differential-host` / `libabdInvoker-1.so.1`）与冻结 Release 库实际运行相同 23 案例、145 对观察，全部通过；清单保存当时两个宿主和运行库的 SHA-256，而不把工作分支标签当作提交身份。证据 `build/runtime-perf/r03-candidate-r05/`。此轮只作语义比较，不比较 Debug/Release 耗时；候选仍是原树执行器的新值存储阶段。

## 种子、预算和失败缩小

默认种子 `7, 91, 20261017`，可重复传入 `--seed`。随机案例使用限定 int32 范围的 add/subtract/xor、global 写入及 callback，Python 模型给出结果和事件预期。失败保留完整配方，自动按块删除生成操作并重新运行两边，最多 `--shrink-attempts` 次；缩小仅针对这种合法构造器生成的案例，不对任意 ABD 字节盲删。生成失败的原输入始终保留。

小案例先用固定高预算运行，然后枚举预算 1 到成功实际扣费数+1；比较每个中断点的全部状态、事件、错误和 trace。`--budget-max` 是实验上限，若不足，报告 `incomplete` 并退出非零，不能宣称全预算覆盖。循环、清理、泛型/buffer 批量扣费的更多边界在对应迁移阶段增加。生成随机案例默认不枚举所有预算，避免规模与测试成本不受控。

## Wire 指令清单

下表登记所有 v10 opcode；编号与 `ExecOpcodes.java` 对齐。R03 的实际静态出现情况以报告为准。完整合法/非法记录已有 `tests/compact_exec_end_to_end.py` 独立 wire 语料；语义侧引用各专项回归，后续 R07–R16 不能将“可解码”当作“已由新 VM 执行”。

| 编号 | 指令 | 语义回归归属 |
| --- | --- | --- |
| 0 | constant | 差分 scalar 位模式；compact exec |
| 1 | block | 差分预算/作用域；loops |
| 2 | call | 差分 callback/reentry；generics/hint linking |
| 3 | v | 差分 global/lhs；numeric slots |
| 4 | vd | 差分 declared_type 不保证后续值类型；numeric slots |
| 5 | vs | 差分写入/快照；numeric slots |
| 6 | m | address/classes/literal objects |
| 7 | r | 差分返回值；runtime trace |
| 8 | ro | literal objects/generics |
| 9 | oa | classes/inheritance/address |
| 10 | ob | classes/literal objects |
| 11 | od | classes/inheritance |
| 12 | add | 差分 lhs 快照/混合类型 |
| 13 | minus | 差分种子模型 |
| 14 | multiply | language ops/math |
| 15 | divide | 差分错误/trace |
| 16 | mod | language ops/math |
| 17 | gt | loops/language ops |
| 18 | lt | loops/language ops |
| 19 | eq | 差分短路；language ops |
| 20 | ne | language ops |
| 21 | ge | language ops |
| 22 | le | language ops |
| 23 | and | 差分跳过 RHS |
| 24 | or | language ops/limits |
| 25 | not | language ops |
| 26 | neg | language ops/math |
| 27 | if | 差分 source-control；loops |
| 28 | wi | 差分 source-control；loops |
| 29 | brk | 差分 source-control；loops |
| 30 | cont | 差分 source-control；loops |
| 31 | cleanup | 差分 source-first-error；inheritance |
| 32 | new_block | 差分 source-cleanup；literal objects |
| 33 | block_address | literal objects |
| 34 | mv | literal objects/generics |
| 35 | drop | inheritance/generics |
| 36 | context_abi | generics |
| 37 | context_default | generics/buffers |
| 38 | return_typed | generics |
| 39 | check_type | generics |
| 40 | buffer_new | buffers |
| 41 | buffer_op | buffers/buffers snapshot |
| 42 | value_compare | containers/language ops |
| 43 | bit_and | language ops |
| 44 | bit_or | language ops |
| 45 | bit_xor | 差分种子模型 |
| 46 | shl | language ops |
| 47 | shr | language ops |
| 48 | ushr | language ops |
| 49 | convert | address/language ops |
| 50 | select | language ops/limits |
| 51 | check_object | language ops/generics |
| 52 | set_methods | 差分 source-virtual；virtual methods |
| 53 | call_virtual | 差分 source-virtual；virtual methods |

## 内建函数清单

全部编号属于 `0x0abd` namespace。这些函数由普通 CALL 进入；执行 CALL、实参及 helper 时分别按实际扣费点计费，不另外假设“每个 builtin 固定一步”。

| ID | 名称 | 验收归属 |
| --- | --- | --- |
| 0x0abd0000 | print | stdout 字节；end-to-end |
| 0x0abd0001 | getDepth | loops/numeric slots；逻辑作用域 |
| 0x0abd0002 | mem_free | address；first-fit/owner |
| 0x0abd0003 | alloc | address；容量/first-fit |
| 0x0abd0004 | make_free | address；自动所有权移除 |
| 0x0abd0005 | mem_send_up | literal objects；caller/lexical parent |
| 0x0abd0006 | mem_get | address；内部 member 访问区别 |
| 0x0abd0007 | load_extern_library | extern library；签名/ABI/执行中状态限制 |
| 0x0abd0008 | reflect_invoke_function | generics/containers；签名/结果检查 |
| 0x0abd0009 | reflect_get_hint_namespace | hint linking |
| 0x0abd000a | reflect_hint_loaded | hint linking |
| 0x0abd000b | str_length | language ops；UTF-8/codepoint |
| 0x0abd000c | str_byte_length | language ops |
| 0x0abd000d | str_substring | language ops |
| 0x0abd000e | str_char_at | language ops |
| 0x0abd000f | str_code_at | language ops |
| 0x0abd0010 | str_from_code | language ops |
| 0x0abd0011 | str_index_of | language ops |
| 0x0abd0012 | str_starts_with | language ops |
| 0x0abd0013 | str_ends_with | language ops |
| 0x0abd0014 | object_alloc（编译器内部） | classes/virtual；登记发生于构造前 |

## 扣费点和顺序

当前 `interpreter/library.cpp::tick` 同时检查实际 native stack，并在 remaining_steps 为零时抛错；减法不允许下溢。迁移可以改变物理指令数量，不能改变以下可观察顺序：

| 位置/路径 | 当前扣费和求值规则 |
| --- | --- |
| `complex_expression::execute_body` | 进入块扣一次；每个语句还有其节点扣费，语句后清理临时量 |
| constant、v、vd、vs、m、return、break/continue | 各入口扣费；m 先取目标再求 RHS；普通二元算术先复制 LHS 再求 RHS |
| 算术/比较/位运算、conversion、select | 节点入口扣费；短路仅求值实际选中的子树；不能合并副作用前后的检查 |
| if/while | if 入口一次；while 入口一次、每次条件的节点费，以及每次进入循环体前额外一次；条件临时量先清理 |
| CALL/VCALL | 节点入口一次；实参从左到右，每个求值后立即物化；再绑定/调用；callee 的块独立扣费 |
| cleanup | 入口扣费失败也进入已有首错/清理协议；清理代码受剩余预算约束，不补发额度 |
| object/member/new_block/block_address/move_out/drop | 节点入口一次；析构执行其自身脚本扣费 |
| context_default/return_typed/check_type/check_object/buffer_new/buffer_op/value_compare | 节点入口一次；default 工厂和元素 placement 可再次执行脚本 |
| set_methods | 指令入口一次，求 receiver，再对每个条目各扣一次；不得部分发布新表 |
| `blocks.cpp::charge` | 按实际 slots 或 buffer capacity×element width 计费；copy、reserve及增长路径使用该入口；不足时余额置零后失败 |

`ofunction::invoke_bound` 负责逻辑调用深度/参数/返回/生命周期，但不存在统一的额外“CALL header tick”；不要根据新设计想当然增加扣费。重入共享同一执行会话预算，不是每次给新 max_steps。

## 重新执行脚本的入口

| 入口 | 源码锚点与所需 continuation |
| --- | --- |
| 普通调用 | `library.cpp::functionInvocationExpression::execute` → `invoke_bound_function` |
| 虚方法 | `call_virtual_expression::execute` → 方法表中 function/context |
| 默认对象工厂 | `context_expression::execute` → context.factory_id |
| 字面量/类值析构 | `blocks.cpp::call_destructor` 中 `invoke_bound_function`；随后恢复其它清理 |
| 堆对象析构 | `heepalloc.cpp` 的 destructor 调用；保留首错和剩余字段/父类清理 |
| buffer 类元素原地构造 | `blocks.cpp` 的 placement_id 调用；失败回滚已完成元素 |
| 反射和用户 comparator | `internelFunctions.cpp::reflect_invoke_function` → target.invoke；`compiler/stdlib/containers.azs` 自定义 comparator 通过反射进入；`blocks::compare_value` 只比较支持的基础类型，**不**隐含执行用户 comparator |
| onload / pre-destroy | `script::flush` / `script::destroy`，各模块顺序与失败状态保持 |
| C++ 宿主重入 | callback 内再次 `script::invoke`；R03 host-reentry 覆盖基础路径 |
| JNI 重入及 Java 异常 | `abdjni/`、`abdJavaInvoker` 现有真实 JVM 回归；R14 必须强制新 VM 后端验收 |

普通 CALL 改为显式帧还不足以关闭迁移任务；以上 helper 必须能暂停、压入脚本帧并恢复，或明确属于受限宿主重入。R03 只建立观察与入口清单，不包含新调度器。

## 新旧一致之外的硬约束

- 本清单不是许可静态类型假设：v10 `vd.declared_type` 当前只在加载时校验编号，global 没有永久 ABI 声明。宿主改变 tag 后，专用算术必须使用当前值并能退回通用路径，不能重复 RHS。
- scope ending、return/break/continue、构造失败与析构失败维持清理顺序、首错及 trace；预算失败后必要 native 存储释放继续，但不能免费执行用户析构。
- 暂存的宿主实参是快照；可写global/heap引用指向真实单元。未来 R04 接口迁移对冻结适配器使用编译期访问器检测，不能修改冻结运行库来迎合新测试。
- native stack限制、逻辑调用深度和新增 VM workspace额度是不同契约。旧引擎因 native stack 失败而新引擎成功须按具体输入登记，不能把此类差异从所有比较中删掉。
- snapshot、插件 ABI、坏输入原子拒绝、所有opcode的执行路径、JNI异常身份和所有权内部关系仍需原有全量验证及 R07–R16 后端强制验证；R03报告不代替它们。
