# Compiler probe coverage

[中文](PROBE_COVERAGE.md) | English

This inventory maps the concrete, known in-scope probes described in the roadmap to regression source for C01–C27. It records coverage presence, not passing execution or a claim that every original input was reproduced. Actual commands, results, platforms, timings and limitations belong in [VALIDATION.en.md](../../VALIDATION.en.md).

The original scratchpad, `out/bad-*.json` inputs and `agent-abb069cae6d0ce136.jsonl` handback were absent from the supplied workspace. Undisclosed original inputs therefore cannot be checked against these regressions. [STATUS.en.md](STATUS.en.md) controls scope: heap generation/address-reuse safety, use after delete, double delete and automatic-object escape (c1–c4, o1/o2, the escape aspect of o3, f32) are excluded. Stable-ID tooling (`--emit-header`/`--id-lock`) and a default-namespace warning were declined. Function-like macros are covered by rejection diagnostics, not macro expansion support.

Java links resolve to `compiler/src/test/java/azertia/script/`; other links identify their repository files. Names after links are test methods, named cases, or case-name prefixes ending in `-`. The inventory groups related cases rather than listing every assertion.

| Problem / known probe | Regression anchors |
| --- | --- |
| C01 · d1/d2/d5: source/include/macro positions and roundtrips | [DiagnosticsTest.java]: `semanticErrorInAnIncludedFileNamesThatFileAndTheIncludeSite`, `macroExpansionsReportTheUseColumnAndTheDefinition`, `astRoundTripKeepsPositionsDiagnosticsAndBytes`. |
| C02–C03 · d3: recovery, malformed JSON and warnings | [DiagnosticsTest.java]: `independentErrorsInSeveralFunctionsAreReportedTogether`, `compileJsonInputErrorsNameTheirJsonPath`, `assignmentsUsedAsConditionsWarn`, `warningOptionsAndWarningsAsErrors`. |
| C04/C07 · m1–m4/f26: IDs, collisions and reserved namespaces | [hint_linking_end_to_end.py]: `assembly_main`, `second_main`, `shifted_client`, `spaced_library`, `namespace-runtime`; [TypeSafetyTest.java]: `hexadecimalIdsAndReservedNamespacesMatchBetweenSourceAndAst`. |
| C05–C06 · p2–p4: bounded preprocessing and include roots | [PreprocessorTest.java]: `pragmaOnceFilesMayIncludeThemselvesBack`, `sourceBytesReadByOneCompilationAreBounded`, `invalidUtf8IsLocated`, `functionLikeMacrosAreDiagnosed`, `symbolicLinksCannotLeaveTheIncludeRoots`. |
| C08 · L4/L5: local and declaration scaling | [CompilerScalingTest.java]: `localsOfOneScope`, `functions`, `fieldsOfOneClass`, `externDeclarations`; [bench_compiler.py]: `locals_program`, `statements_program`, `functions_program`. |
| C09–C10 · c5: naming/layout parity and nominal class types | [TypeSafetyTest.java]: `compileJsonAppliesTheSameNameRulesWithLocations`, `classLayoutErrorsAreLocatedAndIdenticalForSourceAndCompileJson`, `classesAreNominalEvenWithIdenticalLayouts`. |
| C11–C12 · o3 declaration/type aspect: typed globals and base calls | [TypeSafetyTest.java]: `typedGlobalsGetDefaultsThenInitializersBeforeTheOnloadBody`; [type_safety_end_to_end.py]: `typed-global-cycle`, `typed-global-jni`; [BaseCallTest.java]: `qualifiedCallsBindStaticallyToTheNamedClass`. |
| C13–C14 · e1: optional debug records and error/cleanup traces | [DebugInfoTest.java]: `debugInfoIsOptInAndNeverChangesInstructions`, `malformedDebugWireRecordsAreRejected`; [runtime_trace_end_to_end.py]: `stripped`, `cleanup`, `included`; [runtime_tests.cpp]: `runtime_trace_tests`; [JniRegression.java]: `runtimeTraces`. |
| C15 · s2/f43/f44: boolean conditions and compatible equality | [TypeSafetyTest.java]: `conditionsRequireBooleanAcrossStatementsAndFunctionalForms`, `compileJsonCannotBypassConditionOrEqualityRules`; [type_safety_end_to_end.py]: `dynamic-void-equality`, `boolean-short-circuit`. |
| C16–C17: bitwise/shift rules and checked explicit conversions | [LanguageOpsTest.java]: `genericClosersRemainSeparateFromExpressionShifts`, `explicitConversionsFollowTheScalarMatrixAndRoundTrip`; [language_ops_end_to_end.py]: `shift-boundaries`, `conversion-underflow`, `conversion-escaped-error`, `conversion-jni`. |
| C18 · s1/s4/f28: exact/widening returns and explicit narrowing | [TypeSafetyTest.java]: `numericReturnsPermitOnlyExactTypesAndWidening`; [type_safety_end_to_end.py]: `numeric-return-dynamic-`, `numeric-return-explicit-narrowing`, `numeric-return-jni`. |
| C19–C20: ternary scope/typing, colon parsing and switch jumps | [LanguageOpsTest.java]: `colonIdsKeepRawHexWhileConditionalArmsReadFullLiterals`, `nestedSwitchContinuesReachTheEnclosingRealLoop`; [language_ops_end_to_end.py]: `select-current-scope`, `select-first-error-cleanup`, `switch-default-middle`, `switch-nested-continue`. |
| C21: Unicode strings, immutable indexing and JNI values | [LanguageOpsTest.java]: `stringsRejectIndexWritesFromSourceAndCompileJson`; [runtime_tests.cpp]: `string_tests`; [language_ops_end_to_end.py]: `strings-empty-and-scalars`, `strings-jni`. |
| C22 · o4/o5: typed pointer sinks and closed object storage | [TypeSafetyTest.java]: `classPointersCannotEnterUntypedVariablesGlobalsArgumentsOrRawStores`; [address_end_to_end.py]: `explicit-class-erasure-raw-read-rejected`, `closed-object-construction-`; [JniRegression.java]: `objectStorageGuards`. |
| C23 · c7: checked reflection results and cleanup | [GenericCompilerTest.java]: `symbolicReflectionUsesOneSharedCheckAndUncheckedSymbolicFieldTags`; [generics_end_to_end.py]: `reflection-object-check-`, `reflection-buffer-element-check`, `reflection-generic-pointer-liveness`, `heap-empty`. |
| C24 · g1: virtual dispatch, phase changes, rollback and snapshots | [VirtualMethodTest.java]: `derivedCleanupProtectsOwnedFieldsAndStillFinalizesItsBase`; [VirtualCodecTest.java]: `methodTablesAndVirtualCallsHaveStableNumbersAndRoundTrip`; [virtual_methods_end_to_end.py]: `g1-generic-pointer-bound`, `virtual-throwing-member-keeps-cleaning`, `virtual-empty-base-phase-clears-derived-table`, `jni-virtual-snapshot`; [JniRegression.java]: `virtualSnapshots`; [run_jni_tests.py]: `wrong-hidden-count`, `bad-entry-kind`. |
| C25: exact wire depth and unsupported cuts | [ExecShapeTest.java]: `everyWireOpcodeAgreesWithTheCodecAtItsExactBoundary`; [DepthLimitsTest.java]: `memoryAssignmentsKeepTargetValidationBeforeASpilledPureRhs`, `lazyOperandsSelectArmsAndWhileConditionsCannotBeHoisted`; [compact_exec_end_to_end.py]: `depth-boundary-`. |
| C25: forced 96/64/48 budgets, ownership, aliases and first errors | [DepthLimitsTest.java]: `retainedOwnersRejectTransitiveScalarScopeTransfers`; [limits_end_to_end.py]: `spill-canary`, `owned-return-capture-materializing-argument`, `retained-literal-before-scalar-scope-transfer`, `buffer-mutation-receiver-identity`, `spilled-error-debug-position`. |
| C26 · L1: flat chain threshold, ordering and schema | [DepthLimitsTest.java]: `flatChainsStartAbove128OperandsAndPreserveLegacyAstAndOperatorPositions`, `flatChainSchemaRejectsWrongCountsOperatorsAndPositionsBeforeCompilation`; [limits_end_to_end.py]: `chain-10k-string-locals`, `chain-10k-mixed-plus`, `chain-10k-mid-overflow`, `chain-10k-float-left-fold`, `parser-stack-guard`. |
| C27 · L2: budget-sensitive ladders, scope proofs and source jumps | [DepthLimitsTest.java]: `ordinaryLadderLoweringUsesTheRemainingWireBudgetAtTheActualStatementLevel`, `flatLaddersRejectLaterAutomaticPredicatesAndTransitiveScopeTransfers`; [limits_end_to_end.py]: `ladder-110-inside-three-loops`, `ladder-condition-raw-first-`, `ladder-branch-transitive-scope-`, `ladder-switch-and-loop-targets`, `expression-if-value`. |

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
