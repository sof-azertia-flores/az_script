# 内联缓冲区、默认比较与类运算符（2026-09-27）

中文 | [English](VALIDATION.en.md)

实现拥有型 `buffer<T>`：连续直接 slots、类元素独立视图、深复制、多层值嵌套、空默认值、显式容量管理和逆序清理。泛型类型上下文新增直接 slot 数、原地构造绑定及元素上下文。新增 `value_compare<T>` 和类的 `+ - * / [] []= ()`，运算符在编译层降为普通成员调用，支持泛型、继承、extern 及类外实现。exec 和 JNI 快照升级到 v9，拒绝旧格式；源码 metadata 版本不变，未改写 AZS 容器库。

顺序执行 `python3 tools/build_and_test.py --offline` 和 `python3 tools/build_and_test.py --offline --sanitize`，均退出 0，无 ASan/UBSan 报告。Java 单元测试 107/107；新 buffer/运算符端到端套件普通构建通过 79 项，不含 JNI 的 sanitizer 构建通过 77 项。真实 JVM 验证通过 2654 断言、4 线程 800 次调用和小栈下 1000 模块链接；buffer 快照覆盖连续存储、视图地址改写、嵌套和空值、恢复后构造/析构，以及 22 种损坏状态的原子拒绝。

原生回归最终为 736 项，覆盖实际 slot 连续分段、扩容旧地址失效、复制隔离、构造回滚、析构失败、预算耗尽及重入销毁。整体赋值严格先清理旧内容再切换；析构回调销毁 literal 或公共堆父对象时清理候选，保留原错误。最后收紧原始 slot 入口，拒绝绕过 buffer 的长度和元素状态检查，同时保留类元素视图的字段访问；独立普通及 ASan/UBSan 定向验证通过；最终入口限制重建后，原生/JNI 和普通、sanitizer 两套 buffer 端到端均再次通过。

源码→AST→ABD 字节一致、Java/C++ 数据往返、95 项独立执行格式检查，以及原有类、泛型、继承、循环、hint、数学库、容器库和签名原生库回归均通过。长平坦表达式的类型查询使用单次查询缓存，避免运算符查找导致指数递归；原有深度与文件限制继续验证。快照额外在分配前限制累计 buffer 容量乘步长，超限保存/恢复明确失败。

中英文语言、执行格式、hint、C++/JNI 接入及发行使用文档已同步。CMake 安装后搬迁 SDK，静态及共享 C++ 消费者均执行新功能样例并返回 42；新公开头文件可单独编译，动态库确认从搬迁目录装载。项目文档 143 个本地链接、发行模板 12 项回归、`git diff --check` 及新增文件空白检查通过。未提交、推送或导出发行包。

日志：`build/buffer-full-final.log`、`build/buffer-sanitize.log`、`build/buffer-final-guard.log`；C++ 安装搬迁验收位于 `build/buffer-install-validation/`。

# 泛型与运行时反射（2026-09-27）

实现共享正文的泛型类、函数、成员方法及参数化继承，包含显式类型实参、实参推断、单个类上界、不变性、类外实现和泛型 extern 匹配。隐藏类型上下文保留具体默认值、默认工厂和返回类别；对象的析构绑定在复制、移动、跨库返回、错误清理及快照恢复后仍然有效。新增按实际函数 ID 调用与两个 hint 查询接口，检查真实返回标签并拒绝未绑定的泛型入口及内部入口。exec 与 JNI 快照均升级到 v8，旧版本直接拒绝；源码 metadata 版本保持不变。

顺序执行 `python3 tools/build_and_test.py --offline` 和 `python3 tools/build_and_test.py --offline --sanitize`，两者均退出 0，未发现 ASan/UBSan 报告。Java 单元测试 95/95、原生运行时 625 项检查通过。新增泛型与反射端到端套件在普通构建通过 71 项，在不含 JNI 的 sanitizer 构建通过 62 项。普通构建中的真实 JVM 验证通过 2648 项断言、4 个工作线程的 800 次调用及小栈下 1000 个模块的链接测试；新增 JNI 用例还覆盖泛型对象快照和 8 种损坏上下文的原子拒绝。

源码→可读 AST→ABD 字节一致、Java/C++ ABD 样本往返和 94 项独立执行格式检查通过。回归覆盖共享函数 ID、标量及对象默认值、递归及跨库泛型调用、原始地址与对象返回的所有权区别、构造回滚、析构错误、预算限制、宿主回调与反射边界、链接失败后的重试。补入前向声明上界缓存校验、手写 AST 类型字符串的 64 层限制和小栈下长继承链回归；已有类、循环、继承、地址、数学库、hint 和签名动态库套件继续通过。

同步更新中英文语言、执行格式、hint、JNI 接入及发行使用文档，增加单文件泛型反射与独立泛型库示例。仓库 123 个本地 Markdown 文件链接有效；发行导出工具 12 项回归验证包内链接与命令，`git diff --check` 和新增文件空白检查通过。未提交、推送或重新导出发行包。日志：`build/generics-validation.log`、`build/generics-sanitize.log`。

# 中英文文档与发行导出（2026-09-27）

为仓库全部 15 份项目 Markdown 文档增加同目录的 `.en.md` 英文版和双向语言链接，保留第三方英文许可证原文。英文版覆盖当前接口、格式契约、语言与数学库说明、宿主接入、开发指南、发行使用及历史验证记录。逐份检查章节与代码块数量，示例代码除注释翻译外保持一致；检查源文档链接、英文表格、未跟踪文件空白及 `git diff --check`。

发行导出同步提供 9 对中英文文档，英文语言手册使用发行包命令并重写相对链接。新增导出回归验证语言切换、包内链接及发行命令；`python3 tests/test_export_distribution.py` 的 12 项检查通过。`python3 tools/build_and_test.py --offline` 全套退出 0，包含真实 JNI 和 AST/ABD 往返。本次只修改文档及文档导出/验收逻辑，未重复运行 sanitizer。

通过 `python3 tools/export_distribution.py build/bilingual-distribution --offline --force` 生成 macOS arm64 双语验收包，17 项搬迁检查通过，159 个清单文件的长度与 SHA-256 匹配，82 个包内本地链接及其使用的章节锚点有效。既有 `dist/` 未覆盖。日志：`build/bilingual-validation.log`、`build/bilingual-export-unit.log`、`build/bilingual-export-final.log`；检查结果：`build/bilingual-docs-check.json`。

# 远程签名动态库更新合并（2026-09-27）

本地 `main` 从 `7ff1d0a` 快进到远程 `4508642`，纳入签名原生动态库加载、可信公钥接口和插件编译/签名工具。exec 与 JNI 快照仍为 v7。合并前的 `C (*)` 参数实现、测试、文档、`compiler/std.azs` 删除及未跟踪的 `AGENTS.md` 均保留；逐项核对原有补丁增删内容一致，没有文本冲突。

实际构建发现并修复三个集成问题：macOS 路径查询重复声明导致 Clang 编译失败；原生测试固定使用 `.so`，与 macOS 的后缀归一化不一致；发行验收仍假设解释器是静态链接。独立解释器现须随带共享运行库，插件与宿主共享同一注册表；验收改为搬迁最小 `bin/lib` 布局，并在没有 Java 和编译器的环境执行。

`python3 tools/build_and_test.py --offline` 与随后顺序执行的 `python3 tools/build_and_test.py --offline --sanitize` 均退出 0，未发现 ASan/UBSan 报告。Java 72/72、原生运行时 563 项及普通构建中的真实 JNI 验证通过；Java/C++ ABD 样本逐字节一致，源码→AST→ABD 往返、既有生命周期与 hint 链接回归通过。签名动态库端到端测试普通构建 16 项、sanitizer 构建 14 项通过；sanitizer 不包含 JNI。

通过 `python3 tools/export_distribution.py build/remote-merge-distribution --offline` 生成独立验收包，17 项搬迁检查及 150 个清单文件的长度/SHA-256 校验通过，包含 C++ 静态/共享消费者和真实 JNI 消费者。另分别使用开发构建与发行包重编签名插件，验证 `C (*)` 接收字面量、手动指针、自动指针和临时对象：修改可见、实参各求值一次、重复加载仅初始化一次、析构完整，AST 往返 ABD 字节一致；临时私钥已删除。实机验证平台为 macOS arm64，既有 `dist/` 未覆盖。

日志：`build/remote-merge-validation.log`、`build/remote-merge-sanitize.log`、`build/remote-merge-export.log`。组合验证结果：`build/flexible-signed-plugin-qhtn77la/report.json`、`build/flexible-signed-package-1uh1orfx/report.json`。合并前备份位于 `build/remote-merge-backup-20260927-082819/`。

# `C (*)` 参数（2026-09-25）

函数参数可以写成 `C (*) p`（`C(*) p` 亦可），表示同时接受 `C *` 指针和 `C` 字面量对象。函数内 `p` 的类型是 `C *`。实参为字面量对象时，编译器在调用处用 `block_address` 取地址后传入，不复制，函数的修改对调用方可见；临时对象在调用期间有效，语句结束时销毁。实参规则与指针参数相同：接受派生类、结构等价的类和 `null`。`(*)` 可用于普通函数、构造方法、成员方法和 `extern` 声明，其 ABI 与 `C *` 相同（address，类型编号 7）；局部变量、字段、返回类型、for 初始化和 `C * (*)` 都会被拒绝。编译器内部的类类型编号改为每类三个（值、指针、`(*)`）。exec 与 JNI 快照格式不变，仍为 v7。

`python3 tools/build_and_test.py --offline` 与随后顺序执行的 `--offline --sanitize` 均退出 0，未发现 ASan/UBSan 报告。Java 测试 72/72，原生运行时 551 项，ctest 三项通过。字面量对象套件增至 61 项（sanitizer 构建不含 JNI，为 60 项），新增用例覆盖：
- 字面量、手动指针、自动指针、临时对象、派生类和 `null` 实参
- 构造方法与成员方法，包括经隐式 `this` 的调用
- 函数保存借来的地址、原对象到期后再访问时报错
- `delete` 借来的字面量对象时报错
- 八种编译期拒绝

hint 链接套件新增跨模块 `extern int measure(Point (*))` 用例。编译器单元测试确认：AST 保留 `P(*)`，exec 参数类型为 7，只有字面量实参被包上 `block_address`；同时覆盖无空格写法、类外定义的 extern 成员、结构等价的 `(*)` extern 声明，以及 `*`/`(*)` 写法不一致的拒绝。

提交前做了一轮多视角对抗评审，分解析、类型系统、运行时生命周期、测试与文档四个方向，每个方向都用实际编译和运行复现。未发现代码缺陷。据此修正了三处文档：示例中 `read(null)` 会解引用空指针；多处仍称 `this` 是取得字面量地址的唯一途径；HINT_LINKING 的擦除列表漏了 `(*)`。并补充了上面列出的测试。

本次未重新导出 `dist/`。日志：`build/flexible-params-validation.log`、`build/flexible-params-sanitize.log`。

# 字面量对象与 slot 块（2026-09-25）

新增运行时值类型 `object`（类型编号 8）。它是一个 slot 块：内部的 slot 各自独立，可以通过 C++ API 延伸或缩短，复制时整块深复制。块地址编码为 `bit63 | id<<24 | offset`，脚本里与公共堆地址无法区分；id 永不复用，块的生命周期一结束，地址随即报废，之后解引用报 `Invalid or expired object address`。`Point a(1,2);`、`Point a;` 和 `Point a();` 现在都声明字面量对象，赋值时原地逐字段复制，每个副本到期都各自运行析构。自动指针对象改写为 `Point * a(1,2);`，无参时也必须写 `()`；手动对象改写为 `Point * m = new Point(1,2);`；单写 `Point * p;` 声明空指针，与 `Point * p = null;` 生成完全相同的代码，不构造对象，也不负责清理之后赋给它的对象。值与指针完全按 C++ 区分：参数、返回值和字段都可以按值传递；`this` 的类型是 `C*`，也是脚本唯一能拿到块地址的地方。`null` 与 `delete` 只用于指针，值对象不能放进无类型位置，也不能比较或切片。

字面量对象的析构顺序与自动指针交错按逆序进行，依次为：本类析构体 → 本类值字段（逆序）→ 基类。临时对象在所在语句结束时析构，`if`/`while` 条件里的临时对象在条件求值后立即析构。返回本函数拥有的局部对象或参数时直接移交，不复制；返回其他对象时先复制。构造失败时不运行本对象的析构，但已经构造好的值字段仍会析构。堆对象的值字段在 `delete` 或自动释放时析构；对堆对象调用原始 `mem_free` 时，值字段只报废地址、不运行析构。`mem_free` 等原始内存接口不接受块地址。exec 升级到 v7，新增 opcode 32–35（`new_block`、`block_address`、`mv`、`drop`）和类型 8。JNI 快照升级到 v7，按对象递归保存值字段；恢复时字面量对象获得新 id，已经报废的地址保持原样并永久作废。字面量对象不跨越 Java 边界。

`python3 tools/build_and_test.py --offline` 全套退出 0：Java 测试 71/71，原生运行时 551 项检查，ctest 中的 abd、runtime、jni 三项通过，Java/C++ ABD 样本逐字节一致。新增字面量对象端到端套件共 47 项，覆盖：各种声明形式、空指针声明及其解引用报错、复制与析构次序、传参/返回/字段/嵌套/继承、`this` 指针在原对象到期后报错、临时对象、全部编译期拒绝、源码→AST→ABD 字节一致往返、宿主调用后堆与块为空，以及 JNI 拒绝和快照恢复。既有套件迁移到新语法后继续通过：类 70、循环 60、继承 89、地址 83、数学库、数字 slots、紧凑 exec（36 个 opcode，93 个独立 wire 用例及 JNI 快照）、hint 链接和编译器/原生集成 72。`python3 tools/build_and_test.py --offline --sanitize` 随后顺序执行，同样退出 0，未发现 ASan/UBSan 报告。sanitizer 构建不含 JNI，因此计数相应减少：字面量对象 46、类 69、继承 88、编译器/原生集成 67，地址 83 与循环 60 不变。

`dist/azscript-macos-arm64` 已刷新为 exec v7 / JNI 快照 v7，17 项搬迁验收全部通过，清单共 143 个文件，包含新头文件 `detail/interpreter/blocks.h`。导出时顺带修正了发行工具中写死的 v6 版本号，以及只允许 opcode 3–31 的检查（继承示例会用到新 opcode 33）。实机验证平台为 macOS arm64。

日志：`build/bare-pointer-validation.log`、`build/bare-pointer-sanitize.log`、`build/literal-objects-export.log`，以及加入空指针声明之前的 `build/literal-objects-validation.log`、`build/literal-objects-sanitize.log`。语法见 `compiler/LANGUAGE.md` 的“类、字面量对象与指针”一节，格式见 `docs/EXEC_FORMAT.md`。

# 独立 64 位地址类型（2026-09-25）

新增源码类型 `address`，运行时类型编号 7，与 int32 区分。对象引用、隐式 this、堆分配起点、所有权和内存接口均保存无符号 64 位地址，空地址为 0；分配数量、成员偏移、变量编号和函数 ID 保持原有整数表示。地址与 int32 偏移加减检查完整 uint64 边界，同类型比较按无符号值执行。类的静态结构类型、继承前缀布局及生命周期规则保持不变，原始 address 返回不隐式提升所有权。

ABD 新增独立标签 `0xce200b`，payload 为恰好八字节小端值；Java/C++ 466 字节跨语言样本逐字节一致。C++ 使用 `azertian::address`，Java ABD 使用 `AcsAddress`，JNI 高层使用 `azertia.Address`，低层指针接口使用 long 原始位模式。exec 与 JNI 快照均升级 v6，拒绝旧格式；快照继续验证模块身份、全部分配与对象记录后原子替换。exec JSON 地址使用十进制字符串对象，避免超过 JavaScript 精确整数范围后损失数据。

`python3 tools/build_and_test.py --offline` 全套通过；最终 `python3 tools/build_and_test.py --offline --sanitize` 亦退出 0，包含新增地址套件。Java 测试 69/69，原生运行时 491 项检查，真实 JVM 回归 2603 个断言、4 线程 800 次调用及小栈 1000 模块链接通过。地址端到端 83 项在普通和 sanitizer 解释器通过，包括静态/动态类型拒绝、null 比较、slots 内存储地址、递增与 for 遍历、显式所有权转移、类字段与继承、独立编码的高位地址模块、溢出/下溢和错误清理。复查修复了动态值与 null 比较被误拒绝，以及动态一元加号绕过数字检查；负零、数值类型和一次求值均有回归。既有数学、类、循环、继承、offset、hint 链接和 JNI 快照用例继续通过；未发现 ASan/UBSan 报告。

`dist/azscript-macos-arm64` 已刷新为 v6，并通过 17 项搬迁验收。包中包含新地址头文件、Java wrapper、地址源码示例及更新文档；新增示例编译、默认两份 JSON、AST 重编译字节一致和独立运行全部通过。142 个清单文件的长度和 SHA-256 一致，25 个文档本地链接有效。实机验证平台为 macOS arm64。

日志：`build/address-validation.log`、`build/address-sanitize.log`、`build/address-source-validation.log`、`build/address-export.log`。源码示例：`compiler/examples/address-regressions.azs`。

# for、continue 与公开单继承（2026-09-25）

`for` 保留可读 AST，编译为独立块和现有 `while`；初始化只运行一次，`continue` 先清理本轮对象，再执行步进与条件检查。简单变量的前后缀 `++/--` 可用作独立语句和步进，不引入表达式取值语义。执行格式仍为 v5，新增无参数 opcode 30（continue）与固定结构 opcode 31（内部 cleanup），旧解释器会拒绝不认识的指令。

公开单继承按父类完整字段前缀、子类声明字段追加分配准确 slots，成员采用静态绑定，支持遮蔽和子类引用向父类转换。父构造先执行，字段初始化继续使用实现模块的成员及全局绑定；析构从子到父。内部 cleanup 保证提前返回、运行错误和临时对象析构错误都经过必要的父类清理。构造完成标记确保只回滚已经成功构造的父类；基类参数中自动临时对象的析构错误也在保护范围内。跨 hint 库的构造、方法及析构引用继续通过 flush 重定位，不向 exec 写入类名、继承表或布局。

`python3 tools/build_and_test.py --offline` 与 `python3 tools/build_and_test.py --offline --sanitize` 均退出 0。新增循环端到端 60 项；继承端到端普通构建 89 项（含 JNI）、sanitizer 构建 88 项。继承测试包含 21 次在同一脚本上的错误及预算耗尽调用，每次结束后立即确认堆分配和对象登记为空，再正常调用验证可继续使用。跨库继承、构造回滚、完整析构链及 JNI 手动/自动对象快照恢复均通过。最终 Java 测试 64/64，含两条新指令的精确 wire 往返和畸形继承 AST 拒绝。既有数学库、普通源码、类生命周期、全局 offset、多模块链接、独立 wire、C++ 与 JNI 回归继续通过；未发现 ASan/UBSan 报告。

`dist/azscript-macos-arm64` 已重新导出并通过 16 项搬迁验收，新增循环与继承源码、两份 JSON、AST 字节一致往返及独立解释器执行。发行包继续包含完整编译器及 Java 运行环境、C++ SDK、JNI 和独立数学库。语法和示例见 `compiler/LANGUAGE.md`、`compiler/examples/loops-regressions.azs`、`compiler/examples/inheritance-regressions.azs`；opcode 契约见 `docs/EXEC_FORMAT.md`。当前实机验证平台为 macOS arm64。

日志：`build/loops-inheritance-validation.log`、`build/loops-inheritance-sanitize.log`、`build/loops-inheritance-junit-final.log`、`build/loops-inheritance-export.log`。

# 数学库独立 hint 模块重构（2026-09-25）

数学库已拆分为 `compiler/stdlib/math.include.azs` 共享声明和 `compiler/stdlib/math.azs` 独立实现。hint 为 `AZSCRIPT_MATH`，头文件使用假定 namespace `4d41`；52个公开函数固定低位编号0002–0035，两个内部辅助函数不进入头文件。实现保留原有算法、定义域和精度约定。浮点接口参数明确为double，整数接口为int，math_pow为(double,int)，按extern契约精确检查类型。

回归示例只include声明；编译后仅包含main和本地测试函数两个定义，数学库本身包含54个定义。库与调用方的源码→AST→ABD往返字节一致。测试独立固定公开ABI编号，验证重复include合并、缺库拒绝、擦除签名不符、两个consumer共享同一库、不同装载顺序、库先装载、重复flush，以及JNI查询实际namespace后直接调用公开函数和拒绝错误参数类型。

`python3 tools/build_and_test.py --offline` 全套通过。数学库保留93项示例断言、114项数值比较、23项运行错误及负零验证；原先以动态参数触发的factorial(3.5)移为编译期类型错误，并补齐其他类型错误，共6项。正常构建中的真实JNI执行通过，无JNI警告。另以现有ASan/UBSan解释器执行更新后的整个数学库测试，全部通过且无报告；本次未修改C++运行时。

```sh
python3 tests/math_library.py --classpath "$(cat build/java/classpath.txt)" \
  --runner build/sanitize/interpreter/azscript-run
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

发行包已刷新，增加预编译 `stdlib/math.exec.abd`、`math.ast.json`、`math.exec.json` 和共享声明头，manifest新增standardLibraries索引。搬迁后的14项发行包验收全部通过，包括重编数学库字节一致、调用方不携带实现及装配运行。138个清单文件的长度与SHA-256均匹配，包内文档链接有效。`build/math.*` 与 `build/math-regressions.*` 为可直接查看的本次产物。

日志为 `build/math-hint-validation.log`、`build/math-hint-sanitize.log`、`build/math-hint-export.log`。使用方法及完整参数契约见 `compiler/stdlib/MATH.md`。

# 多文件库、运行时 hint 链接与类外实现（2026-09-25）

当前执行文件和 JNI 快照均为 v5，只接受新格式。新增独立库 hint、模块独立 assume、普通 extern 与显式函数编号、类外实现、结构类型、构造实现中的字段初始化和显式 flush。类名与布局不进入 exec；函数与析构 ID 保留完整32位，0xffffffff 不再作为无析构哨兵。以下较早记录仅保留历史验证结果，其兼容性和装载流程说明不再代表当前实现。

以下命令均退出0：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

JUnit 63/63（编译器60、归档3）、原生运行时428项通过。JNI通过2526条断言及四线程800次调用；另用256 KiB线程栈成功链接1000个依赖模块，无JNI警告。结构类型测试覆盖3000个相互引用的类，比较使用迭代遍历。运行时链接图也使用迭代遍历，避免合法的深依赖链耗尽宿主线程栈。

普通源码端到端72项、类端到端70项通过，包含源码→AST→ABD字节一致及真实JNI执行。数学库93项回归、114项数值比较、24项错误路径和负零检查通过。数字槽三模块全局offset隔离、全部30种opcode和82个独立wire用例继续通过。sanitizer构建的对应源码67项、类69项及多库测试通过，无ASan/UBSan报告；JNI在普通构建的真实JVM中单独验证。

新 `tests/hint_linking_end_to_end.py` 从独立源码验证共享类声明、消费者工厂、库私有全局初始化、构造参数遮蔽、初始化临时对象生命周期、跨库自动对象连续返回、手动对象返回、逆序析构、同一假定namespace的模块间复用、不同装载顺序、循环依赖、缺库后重试、重复flush、结构等价和高位函数/析构ID。它还通过JNI保存和恢复完整源码生成的多模块对象状态。原生与JNI回归覆盖链接失败原子性、擦除签名不符、初始化失败、未flush关闭、缓存函数及直接宿主回调的执行门禁、重复hint和宿主namespace冲突、快照身份不符与64 MiB超限保存保留旧文件。

发行包 `dist/azscript-macos-arm64` 已重新导出，搬迁后的13项验收通过，包括默认ABD与两份JSON、独立多文件类库、脱离共享库的runner、C++静态/动态消费者和真实JNI消费者。manifest中的134个文件长度与SHA-256全部匹配，21个包内文档链接均存在；execFormatVersion与jniSnapshotVersion均为5。共享头文件及库/调用方示例位于 `compiler/examples/multifile/`，发行包内对应 `examples/multifile/`。`build/demo.*` 和 `build/classes-regressions.*` 也已按v5重新生成并执行验证。

完整日志为 `build/hints-validation.log`、`build/hints-sanitize-validation.log` 和 `build/hints-export.log`。语法、装配和二进制契约见 `docs/HINT_LINKING.md` 与 `docs/EXEC_FORMAT.md`。实机验证范围为macOS arm64；Linux/Windows产物须在对应系统构建验证。

# 缺陷修复验证（2026-09-25）

本次修复 7 个缺陷，均先用最小源程序或 C++ 宿主复现，再补回归测试：

- 字符串 `\uXXXX` 转义产生孤立 UTF-16 代理项时（如 `"\ud800"`），原先被静默写成 `?`；现在词法分析和 `compile-json` 都会报错，成对代理项正常。
- 预处理器把宏展开粘到数字后面：`#define N 5` 后 `5N` 被编译为 `55`，`0x10`（宏 `x10`）变为 `07`，`2e`（宏 `e`）变为 `29`。现在紧贴数字的标识符字符视为同一数值字面量，交由词法分析报告非法后缀。
- `C x(args);` 自动对象构造的参数错误丢失行列号，并显示内部名 `C::<scoped>`；现在带行列并显示为 `C constructor`，`new C(...)` 同样使用该名称。
- 方法调用的参数数量和序号把隐式 `this` 计入：`c.m(1, 2)` 原报 `expects 2 arguments, got 3`，现报 `expects 1 arguments, got 2`，首个实参报为 argument 1。
- 类字段类型未知、方法参数写 `void`、类名为保留类型名时，错误缺少文件与行列；现在与其他语法错误格式一致。
- C++ 宿主 `script::destroy()` 后，未 `delete` 的 `new` 对象永久留在进程级堆和对象登记中，每次加载/销毁循环泄漏一份。现在销毁时释放其存储和登记，仍不补调用户析构（JNI 原本在销毁后清空堆，行为不变）。
- 堆查找对全部分配线性扫描，`alloc` 每次复制并排序整张分配表，大量对象时呈平方级：40,000 个活跃对象时分配耗时 8.7 s、五轮成员访问 17.8 s。现在分配表保持有序并二分查找，首次适配跳过无空洞前缀，同规模分别约 0.1 s 与 0.08 s（Debug 库）。首次适配地址复用顺序不变。

以下命令均退出 0：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

JUnit 53/53（新增 3 项：孤立代理项、数值字面量内不展开宏、类诊断的位置/名称/实参序号），原生运行时 356 项（新增 6,000 步随机操作与朴素首次适配模型的差分比较、20,000 次连续分配及空洞复用、销毁释放未删除手动对象且不运行析构）。其余端到端、类、数学库、数字槽、紧凑格式及 JNI 回归与上一版一致通过；sanitizer 构建无 ASan/UBSan 报告。本次未重新导出 `dist/` 发行包。

# Exec v4 裸 stack 与数字 opcode 验证（2026-09-24）

当前编译器和解释器统一使用 exec v4：原来 27 种 `c` 控制字符串全部改为整数，加上 constant/block/call 共 30 个 opcode。根、函数、签名、表达式及列表使用固定字段顺序的裸 ABD stack；动态常量保留单元素 typed array，扩展元数据保留 typed map。解释器直接创建原生执行节点，不重建指令 Map/Array。旧字符串变量与 exec v3 Map 加载路径、旧节点构造器、字符串变量执行分支已移除；JNI 仅接受当前 v4 快照。下方较早阶段关于兼容读取的记录不再代表当前实现。

以下命令均退出 0：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

JUnit 50/50（编译器 47、归档 3）、原生运行时 350 项、JNI 2334 条断言及四线程 800 次调用通过。普通端到端 72 项、类端到端 70 项通过；sanitizer 对应 67/69 项，ASan/UBSan 无报告。数学库的 93 项回归、114 项数值比较、24 项错误路径、负零与普通构建中的 JNI 执行均通过。三模块数字槽和 insert offset 的源码→AST→ABD 测试在普通与 sanitizer 构建都通过。

新增独立 wire 测试覆盖全部 30 个 opcode、固定字段顺序、完整标量类型、可选字段两种状态、82 个合法/非法编码用例及 JNI 对紧凑脚本字节的快照往返。源代码→可读 AST→ABD、Java 解码再编码、独立 Python 解码再编码均验证字节一致。未知版本和旧 Map 格式明确拒绝；缺失/额外字段、错误宽度、非法布尔、无效 UTF-8、非有限浮点、越界变量、无效类型和超过 128 层的嵌套均有覆盖。原有运行时和 JNI 功能样例通过测试侧编码器迁移为 v4，未因取消兼容而删除生命周期、回调、内存恢复及错误清理的覆盖。

`tests/compact_exec_end_to_end.py` 的同一源程序实测新 ABD 为 4,702 字节，同等内容的旧 Map 编码为 14,504 字节，新文件占原体积 32.4%，减少约 67.6%。此数字仅表示该样例的文件大小，不代表执行速度；旧编码仅由测试构造用于对比及拒绝测试。

发行包已刷新到 `dist/azscript-macos-arm64`，包含当前编译器、独立解释器、C++/JNI SDK、运行环境与 `docs/EXEC_FORMAT.md`。搬迁后的 12 项验收通过，默认同时输出 ABD 和两份 JSON；130 个文件的清单长度及 SHA-256 全部匹配，包内文档链接存在。`build/demo.*` 与 `build/classes-regressions.*` 已重新生成 v4 并执行验证。实机验证范围为 macOS arm64，Linux/Windows 仍需在目标平台构建和验证。

日志为 `build/compact-validation.log`、`build/compact-sanitize-validation.log`、`build/compact-export.log`；规范见 `docs/EXEC_FORMAT.md`。

# 数字变量与 insert 全局偏移验证（2026-09-24）

编译器现输出 `exec-version: 3`、整数 `gvs` 和变量槽号；AST 继续保留源码名称。参数从 0 开始，局部槽紧随参数，全局从 -1 开始。每个加载的函数保存模块的 `global_offset/global_count`，访问全局时解码负数编号再加 offset；旧字符串执行文件继续可读。JNI 快照写 v4 全局值数组，保留旧脚本对应的 v2/v3/无版本格式兼容。

以下命令均退出 0：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
python3 tools/export_distribution.py dist/azscript-macos-arm64 --offline --force
```

JUnit 46/46（编译器 43、归档 3）、原生运行时 250 项、JNI 2329 条断言及 800 次并发调用通过。原有端到端普通构建 72 项、sanitizer 67 项；类端到端普通 70 项、sanitizer 69 项；数学库与跨语言 ABD 检查全部通过。ASan/UBSan 无错误报告，真实 JNI 使用 `-Xcheck:jni` 无警告。

新增 `tests/numeric_slots_end_to_end.py` 和 `tests/numeric_module_host.cpp`，将三个独立源码模块分别编译并经 AST 往返，再通过公开 C++ API 插入。三个模块都声明同名全局，函数记录的偏移分别为 0、1、3；递归、局部遮蔽、循环重入、类析构、内部创建函数及关闭钩子均访问自己的全局。重复插入被拒绝后，全局数量与原函数行为不变。运行时额外覆盖双向跨模块调用、旧模块混合加载、越界/错误类型/超大数量拒绝；JNI 覆盖新快照恢复及损坏数组拒绝的原子性。

完整日志为 `build/numeric-slots-validation.log`、`build/numeric-slots-sanitize-validation.log` 和 `build/numeric-slots-export.log`。`dist/azscript-macos-arm64` 已刷新为配套的新编译器、解释器及 C++/JNI SDK，搬迁后的 12 项发行包验收通过。`build/classes-regressions.ast.json`、`.exec.json` 和 `.exec.abd` 已按新格式重新生成。

实机范围仍为 macOS arm64。数字 exec 需要配套新版解释器；`insert` 的函数 ID 冲突规则和加载钩子运行失败后不回滚已完成写入的行为保持不变。

# 发行包导出验证（2026-09-24）

新增 `tools/export_distribution.py`，在 macOS arm64 上实际生成 `dist/azscript-macos-arm64`。包内附带 Java 运行环境，含完整编译器、独立 main 解释器、C++ 静态/动态 SDK、JNI JAR/dylib、语法与使用文档及接入示例。另以 `--system-java` 生成精简包验证依赖系统 Java 的路径。

两种发行包均通过 `tools/test_distribution.py` 的 12 项检查。检查在安装后将整包移动到包含空格的新位置，从第三个工作目录调用；默认三份输出、显式输出路径、错误退出码、AST→ABD 逐字节一致、类析构、单独复制的解释器、真实 CMake 静态/动态宿主及 `-Xcheck:jni` Java 宿主均通过。带运行环境的编译器在 `PATH` 中没有 Java、`JAVA_HOME` 失效时仍可运行。

`tests/test_export_distribution.py` 的 11 项回归验证非空目录拒绝、非法清单、源码目录保护、替换失败恢复以及并发占用目标时保留旧包备份；该回归已接入统一构建入口。发行包的 129 个文件校验值逐一验证，文档内部链接均存在，安装 CMake 配置及 Mach-O 的加载路径不引用源码/构建目录。

本次重新运行 `python3 tools/build_and_test.py --offline`，全部语言、ABD、运行时与 JNI 回归通过，包括类端到端 70 项、既有端到端 72 项。日志为 `build/export-distribution.log`、`build/export-system-java.log` 和 `build/distribution-regressions.log`。本节未重新运行 sanitizer；此前类功能的 sanitizer 结果见下一节。

Linux/Windows 的安装规则、扩展名和启动脚本已提供，但本机未作实机验证。原生包须匹配目标系统、架构及 C++ ABI，不能把当前 macOS 包用于其他系统。

# 类语法更新验证（2026-09-24）

本次在 macOS 工作区完成了类语法、引用语义、构造与析构、自动对象返回转移，以及 JNI v3 对象快照。以下两条统一入口均退出 `0`：

```sh
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

- JUnit：43/43 通过，其中编译器 40 项、归档 3 项。
- 原生运行时：198 项检查；普通 CTest 3/3、ASan/UBSan CTest 2/2 通过。
- JNI：2210 条断言及 4 个线程的 800 次调用通过，`-Xcheck:jni` 无警告。
- 类端到端：普通构建 70 个场景（含源码编译产物经 JNI 执行、保存、恢复、删除），sanitizer 构建 69 个原生场景；全部有效源程序都通过 AST 再编译的 ABD 逐字节一致性检查。
- 既有端到端：普通构建 72 个场景、sanitizer 构建 67 个场景；数学库的 93 个断言、114 组数值比较、24 个错误边界和正零测试通过，Java/C++ ABD 互读及字节一致性检查通过。

类回归覆盖每字段一个 slot、隐式 `this`、名义类型、动态变量不推断类类型、类前向引用、成员默认值与初始化顺序、接收者和实参一次求值、别名共享、自动/手动对象、空引用、逆序析构、多层块返回及借用对象不降级。还覆盖构造/析构失败、原异常优先、执行预算耗尽、析构重入、原始释放注销、加载失败后的选择性清理，以及同名 `alloc/mem_get/make_free` 成员不能干扰编译器内部操作。

JNI 回归验证快照恢复的原子性、旧格式兼容、已脱离作用域的自动对象、析构顺序，以及 Java 析构回调抛错后继续执行剩余析构并恢复同一个原始异常对象。

当前可检查输出：

- `build/classes-validation.log`：完整离线构建日志。
- `build/classes-sanitize-validation.log`：完整 ASan/UBSan 构建日志；无 sanitizer 错误报告。
- `build/native/Testing/Temporary/LastTest.log`：原生/JNI 检查明细。
- `compiler/examples/classes-regressions.azs`：可执行类示例，真实运行返回 `0`。
- `build/classes-regressions.ast.json`、`build/classes-regressions.exec.json`、`build/classes-regressions.exec.abd`：该示例本次生成的 AST、指令树和二进制。

本次没有验证 Linux/Windows 或 Gradle 构建。对象仍使用整数地址，不提供地址世代识别；成员引用不拥有对象，`new` 对象须显式删除。这些是已确定的语言边界，详见 `compiler/LANGUAGE.md`。

# 此前版本的验证记录

以下保留历史记录，其中的测试数量和原文件校验结果不代表本次重新执行。

环境：macOS arm64，AppleClang 21，JDK 21.0.5（所有 Java 源码按 `--release 17` 编译），CMake，Python 3。Linux、Windows 和用户的大项目接入尚未实际运行验证。

## 已通过

| 验证 | 结果 |
| --- | --- |
| 统一离线构建 `python3 tools/build_and_test.py --offline` | 退出码 0 |
| 编译器与归档 JUnit | 33/33 通过 |
| Java ABD 数据与反射结构回归 | 通过 |
| C++ ABD 回归 | 通过，包含 10,000 个损坏输入变异样本 |
| C++ 解释器 | 135 项检查通过 |
| CTest：数据、运行时、JNI | 3/3 通过 |
| 真实 JNI/JVM `-Xcheck:jni` | 2078 项断言；4 个工作线程共 800 次调用，通过 |
| 源码→编译器→ABD→C++，及编译结果→Java/JNI | 72 个集成用例通过 |
| 纯脚本数学库 | 93 个回归断言、114 组 Python `math` 差分比较、24 个错误边界及正零位模式；原生解释器全量、JNI 加载/数值/异常/重载路径通过 |
| Java→C++、C++→Java 数据互读 | 每个字段一致，重新编码及两端独立生成的字节完全一致 |
| ASan + UBSan 原生构建/CTest | 2/2 通过，无 sanitizer 报告 |
| 静态链接 C++ 嵌入示例 `azscript-embed` | 宿主收到整数 146，销毁钩子输出 done |
| 原文件校验 `python3 tools/verify_originals.py` | 292 个原文件逐字节未变 |

## 关键回归场景

- `20-3-2` → `15`、`100/5/2` → `10`、`2+3*(4+1)` → `17`。
- `5e0/2` 和 `5./2` → `2.5`；`1.5f` 保持 float 类型；`-0.0` 保留负零位模式。
- 字符串内部的引号、反斜杠、逗号、括号、分号、注释符号，以及中文/NUL/emoji。
- 宏尾随 `//` 注释，未启用分支的多行注释，数值内部不展开宏，宏总展开量限制，include 相对路径、循环引用。
- `#namespace` 的脚本函数 ID、带完整签名的 `#extern` 到真实 Java 回调，以及保留固定 ID 的 `main`。
- 七个内置函数使用固定公开名称直接调用，保留名称和 `0xabd` ID 不能被源码或手写 AST 覆盖，未知预处理指令会被拒绝。
- `#extern` 的所有标量参数/返回类型、精确类型和参数数量检查、嵌套返回类型推断、未知动态值拒绝、旧无签名语法拒绝，以及重复/保留 ID。
- 绕过编译器的手工 ABD 与宿主直接调用仍会检查签名；错误参数不会进入回调，错误 Java 回调返回值立即成为 `IllegalStateException`。
- 数学库覆盖 int32 极值、最小 subnormal 到最大 finite double 邻域、`exp(-745..709.78)`、`±1e12` 角度约化、稳定 mean/lerp/hypot、正零位模式、取整边界、定义域错误和重复 include guard。
- 递归/前向调用、短路逻辑、嵌套循环、`break`、循环内 return、单语句循环作用域、参数从左到右按值求值。
- 迁移后的 `abdJavaInvoker/tse.azs` 重新编译后运行，堆输出从 `0:25` 到 `24:33554432`，随后输出 `printed`、`predestroy2`。
- 非法字符、缺括号、未知变量/函数、参数个数、非法赋值、错误返回形式、坏 JSON 都有正常错误退出。
- 2000 层括号/一元/赋值/JSON 以及循环 AST 正常拒绝，宿主随后可继续编译。
- int、float、double 的正负零除法都精确报告 `Division by zero`，并验证了 JNI 异常映射；整数溢出、无限循环、无限递归、损坏 ABD 和无效堆地址均有受控错误。
- JNI 的关闭与 onload/onclose 异常、回调返回/重入/异常、零参数、快照坏输入和恢复原子性；onload 期间的回调可以 `invoke`。
- 自动回收所有权：宿主回调重入的脚本函数不能释放挂起作用域的分配（报错且不留残留），调用者链上的释放和 `make_free` 后的释放照常；Java `memFree` 拒绝释放脚本作用域持有的块。
- 数值转字符串使用最短可往返表示（`0.30000000000000004`、`123456789`、`0.1`、`-0`、`1e+20`）。
- 100 项串联运算、40 层嵌套 if 可编译；130 项串联运算报告带行列的 ABD 上限错误；保留字不能声明为名字；UTF-8 BOM 不影响首行指令；非 void 函数缺少 return 在编译期报错。
- 宿主不能绑定命名空间 `0`/`0xfff`，`#extern` 不能使用脚本命名空间，脚本命名空间中不存在的 ID 不会转给宿主。
- 归档打包/解包拒绝同名文件与目录冲突及空数据条目，且不写出任何文件。

## 日志和可检查输出

- `build/native/Testing/Temporary/LastTest.log`：C++/JNI 详细输出。
- `build/demo.ast.json`：示例的可读代码树。
- `build/demo.exec.json`、`build/demo.exec.abd`：对应指令树和二进制。

当前机器的 Gradle native-platform 动态库无法为 macOS arm64 初始化，因此没有完成 Gradle 路径验证；已通过的统一入口直接调用 javac、jar、JUnit、CMake 和真实 JVM，并复用经 SHA-256 校验的依赖，不依赖 Gradle daemon。Gradle 配置作为另一种构建方式保留。

这些测试覆盖已实现功能和发现的回归，不代表不存在其他缺陷；运行时仍有共享堆、整数地址复用、宿主回调时间不受脚本预算限制等边界，详见 README。
