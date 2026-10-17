# 编译器探针回归覆盖

中文 | [English](PROBE_COVERAGE.en.md)

本清单将路线图中已知、具体且属于本轮范围的探针映射到 C01–C27 回归源码，只记录覆盖是否存在，不表示执行通过，也不声称复现了全部原始输入。实际命令、结果、平台、耗时及限制另见 [VALIDATION.md](../../VALIDATION.md)。

提供的工作区中缺少原 scratchpad、`out/bad-*.json` 输入及 `agent-abb069cae6d0ce136.jsonl` 交接记录，因此无法逐一核对未披露的原始输入。范围以 [STATUS.en.md](STATUS.en.md) 为准：堆分配代次/地址复用安全、删除后使用、重复删除及自动对象逃逸（c1–c4、o1/o2、o3 的逃逸部分、f32）不在本轮范围内；稳定 ID 工具（`--emit-header`/`--id-lock`）和默认 namespace 警告已被拒绝。函数式宏覆盖的是拒绝诊断，并未实现其展开。

Java 链接指向 `compiler/src/test/java/azertia/script/`，其他链接指向对应仓库文件。链接后的名称是测试方法、命名 case，或以 `-` 结尾的 case 名前缀；相关用例按问题归组，不逐条罗列断言。

| 问题 / 已知探针 | 回归位置 |
| --- | --- |
| C01 · d1/d2/d5：源码、include、宏位置及往返一致性 | [DiagnosticsTest.java]: `semanticErrorInAnIncludedFileNamesThatFileAndTheIncludeSite`, `macroExpansionsReportTheUseColumnAndTheDefinition`, `astRoundTripKeepsPositionsDiagnosticsAndBytes`. |
| C02–C03 · d3：错误恢复、非法 JSON 及警告 | [DiagnosticsTest.java]: `independentErrorsInSeveralFunctionsAreReportedTogether`, `compileJsonInputErrorsNameTheirJsonPath`, `assignmentsUsedAsConditionsWarn`, `warningOptionsAndWarningsAsErrors`. |
| C04/C07 · m1–m4/f26：ID、冲突及保留 namespace | [hint_linking_end_to_end.py]: `assembly_main`, `second_main`, `shifted_client`, `spaced_library`, `namespace-runtime`; [TypeSafetyTest.java]: `hexadecimalIdsAndReservedNamespacesMatchBetweenSourceAndAst`. |
| C05–C06 · p2–p4：预处理上限及 include 根目录 | [PreprocessorTest.java]: `pragmaOnceFilesMayIncludeThemselvesBack`, `sourceBytesReadByOneCompilationAreBounded`, `invalidUtf8IsLocated`, `functionLikeMacrosAreDiagnosed`, `symbolicLinksCannotLeaveTheIncludeRoots`. |
| C08 · L4/L5：局部变量及声明规模增长 | [CompilerScalingTest.java]: `localsOfOneScope`, `functions`, `fieldsOfOneClass`, `externDeclarations`; [bench_compiler.py]: `locals_program`, `statements_program`, `functions_program`. |
| C09–C10 · c5：命名与布局检查一致性、名义类类型 | [TypeSafetyTest.java]: `compileJsonAppliesTheSameNameRulesWithLocations`, `classLayoutErrorsAreLocatedAndIdenticalForSourceAndCompileJson`, `classesAreNominalEvenWithIdenticalLayouts`. |
| C11–C12 · o3 的声明/类型部分：带类型全局及父类调用 | [TypeSafetyTest.java]: `typedGlobalsGetDefaultsThenInitializersBeforeTheOnloadBody`; [type_safety_end_to_end.py]: `typed-global-cycle`, `typed-global-jni`; [BaseCallTest.java]: `qualifiedCallsBindStaticallyToTheNamedClass`. |
| C13–C14 · e1：可选调试记录及错误/清理调用栈 | [DebugInfoTest.java]: `debugInfoIsOptInAndNeverChangesInstructions`, `malformedDebugWireRecordsAreRejected`; [runtime_trace_end_to_end.py]: `stripped`, `cleanup`, `included`; [runtime_tests.cpp]: `runtime_trace_tests`; [JniRegression.java]: `runtimeTraces`. |
| C15 · s2/f43/f44：布尔条件及相容相等比较 | [TypeSafetyTest.java]: `conditionsRequireBooleanAcrossStatementsAndFunctionalForms`, `compileJsonCannotBypassConditionOrEqualityRules`; [type_safety_end_to_end.py]: `dynamic-void-equality`, `boolean-short-circuit`. |
| C16–C17：位运算/移位规则及显式转换检查 | [LanguageOpsTest.java]: `genericClosersRemainSeparateFromExpressionShifts`, `explicitConversionsFollowTheScalarMatrixAndRoundTrip`; [language_ops_end_to_end.py]: `shift-boundaries`, `conversion-underflow`, `conversion-escaped-error`, `conversion-jni`. |
| C18 · s1/s4/f28：精确/拓宽返回及显式收窄 | [TypeSafetyTest.java]: `numericReturnsPermitOnlyExactTypesAndWidening`; [type_safety_end_to_end.py]: `numeric-return-dynamic-`, `numeric-return-explicit-narrowing`, `numeric-return-jni`. |
| C19–C20：条件表达式作用域/类型、冒号解析及 switch 跳转 | [LanguageOpsTest.java]: `colonIdsKeepRawHexWhileConditionalArmsReadFullLiterals`, `nestedSwitchContinuesReachTheEnclosingRealLoop`; [language_ops_end_to_end.py]: `select-current-scope`, `select-first-error-cleanup`, `switch-default-middle`, `switch-nested-continue`. |
| C21：Unicode 字符串、不可变下标及 JNI 值 | [LanguageOpsTest.java]: `stringsRejectIndexWritesFromSourceAndCompileJson`; [runtime_tests.cpp]: `string_tests`; [language_ops_end_to_end.py]: `strings-empty-and-scalars`, `strings-jni`. |
| C22 · o4/o5：指针类型保留及对象存储封闭 | [TypeSafetyTest.java]: `classPointersCannotEnterUntypedVariablesGlobalsArgumentsOrRawStores`; [address_end_to_end.py]: `explicit-class-erasure-raw-read-rejected`, `closed-object-construction-`; [JniRegression.java]: `objectStorageGuards`. |
| C23 · c7：反射结果检查及清理 | [GenericCompilerTest.java]: `symbolicReflectionUsesOneSharedCheckAndUncheckedSymbolicFieldTags`; [generics_end_to_end.py]: `reflection-object-check-`, `reflection-buffer-element-check`, `reflection-generic-pointer-liveness`, `heap-empty`. |
| C24 · g1：虚调用、阶段切换、回滚及快照 | [VirtualMethodTest.java]: `derivedCleanupProtectsOwnedFieldsAndStillFinalizesItsBase`; [VirtualCodecTest.java]: `methodTablesAndVirtualCallsHaveStableNumbersAndRoundTrip`; [virtual_methods_end_to_end.py]: `g1-generic-pointer-bound`, `virtual-throwing-member-keeps-cleaning`, `virtual-empty-base-phase-clears-derived-table`, `jni-virtual-snapshot`; [JniRegression.java]: `virtualSnapshots`; [run_jni_tests.py]: `wrong-hidden-count`, `bad-entry-kind`. |
| C25：精确 wire 深度及不支持的切分 | [ExecShapeTest.java]: `everyWireOpcodeAgreesWithTheCodecAtItsExactBoundary`; [DepthLimitsTest.java]: `memoryAssignmentsKeepTargetValidationBeforeASpilledPureRhs`, `lazyOperandsSelectArmsAndWhileConditionsCannotBeHoisted`; [compact_exec_end_to_end.py]: `depth-boundary-`. |
| C25：强制 96/64/48 预算、所有权、别名及首个错误 | [DepthLimitsTest.java]: `retainedOwnersRejectTransitiveScalarScopeTransfers`; [limits_end_to_end.py]: `spill-canary`, `owned-return-capture-materializing-argument`, `retained-literal-before-scalar-scope-transfer`, `buffer-mutation-receiver-identity`, `spilled-error-debug-position`. |
| C26 · L1：扁平运算链阈值、顺序及结构 | [DepthLimitsTest.java]: `flatChainsStartAbove128OperandsAndPreserveLegacyAstAndOperatorPositions`, `flatChainSchemaRejectsWrongCountsOperatorsAndPositionsBeforeCompilation`; [limits_end_to_end.py]: `chain-10k-string-locals`, `chain-10k-mixed-plus`, `chain-10k-mid-overflow`, `chain-10k-float-left-fold`, `parser-stack-guard`. |
| C27 · L2：按剩余预算降级条件链、作用域证明及源码跳转 | [DepthLimitsTest.java]: `ordinaryLadderLoweringUsesTheRemainingWireBudgetAtTheActualStatementLevel`, `flatLaddersRejectLaterAutomaticPredicatesAndTransitiveScopeTransfers`; [limits_end_to_end.py]: `ladder-110-inside-three-loops`, `ladder-condition-raw-first-`, `ladder-branch-transitive-scope-`, `ladder-switch-and-loop-targets`, `expression-if-value`. |

[DiagnosticsTest.java]: ../../compiler/src/test/java/azertia/script/DiagnosticsTest.java
[PreprocessorTest.java]: ../../compiler/src/test/java/azertia/script/PreprocessorTest.java
[CompilerScalingTest.java]: ../../compiler/src/test/java/azertia/script/CompilerScalingTest.java
[TypeSafetyTest.java]: ../../compiler/src/test/java/azertia/script/TypeSafetyTest.java
[BaseCallTest.java]: ../../compiler/src/test/java/azertia/script/BaseCallTest.java
[DebugInfoTest.java]: ../../compiler/src/test/java/azertia/script/DebugInfoTest.java
[LanguageOpsTest.java]: ../../compiler/src/test/java/azertia/script/LanguageOpsTest.java
[GenericCompilerTest.java]: ../../compiler/src/test/java/azertia/script/GenericCompilerTest.java
[VirtualMethodTest.java]: ../../compiler/src/test/java/azertia/script/VirtualMethodTest.java
[VirtualCodecTest.java]: ../../compiler/src/test/java/azertia/script/VirtualCodecTest.java
[ExecShapeTest.java]: ../../compiler/src/test/java/azertia/script/ExecShapeTest.java
[DepthLimitsTest.java]: ../../compiler/src/test/java/azertia/script/DepthLimitsTest.java
[hint_linking_end_to_end.py]: ../../tests/hint_linking_end_to_end.py
[type_safety_end_to_end.py]: ../../tests/type_safety_end_to_end.py
[runtime_trace_end_to_end.py]: ../../tests/runtime_trace_end_to_end.py
[language_ops_end_to_end.py]: ../../tests/language_ops_end_to_end.py
[address_end_to_end.py]: ../../tests/address_end_to_end.py
[generics_end_to_end.py]: ../../tests/generics_end_to_end.py
[virtual_methods_end_to_end.py]: ../../tests/virtual_methods_end_to_end.py
[limits_end_to_end.py]: ../../tests/limits_end_to_end.py
[compact_exec_end_to_end.py]: ../../tests/compact_exec_end_to_end.py
[runtime_tests.cpp]: ../../interpreter/runtime_tests.cpp
[JniRegression.java]: ../../abdJavaInvoker/src/test/java/azertia/JniRegression.java
[run_jni_tests.py]: ../../abdJavaInvoker/tests/run_jni_tests.py
[bench_compiler.py]: ../../tools/bench_compiler.py
