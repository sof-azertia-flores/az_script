cmake_minimum_required(VERSION 3.20)
project(abdInvoker LANGUAGES CXX)
include(GNUInstallDirs)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(THREADS_PREFER_PTHREAD_FLAG TRUE)
find_package(Threads REQUIRED)
if(NOT TARGET azertianBinaryData)
    add_subdirectory(../abdC "${CMAKE_CURRENT_BINARY_DIR}/abdC")
endif()
set(AZSCRIPT_RUNTIME_SOURCES core/trace.cpp core/admission.cpp core/types.cpp core/value_ops.cpp core/exec.cpp core/link.cpp library.cpp internelFunctions.cpp heepalloc.cpp blocks.cpp p256.cpp extern_library.cpp runtime_abi.cpp
    vm/storage.cpp vm/frames.cpp vm/program.cpp vm/lowering.cpp vm/loader.cpp
    vm/execution.cpp vm/session.cpp vm/host_bridge.cpp vm/cleanup.cpp)
add_library(abdInvoker SHARED ${AZSCRIPT_RUNTIME_SOURCES})
add_library(abdInvokero STATIC ${AZSCRIPT_RUNTIME_SOURCES})
foreach(target abdInvoker abdInvokero)
    target_compile_features(${target} PUBLIC cxx_std_20)
    target_include_directories(${target} PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/../include>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/azscript/detail/interpreter>)
    target_link_libraries(${target} PUBLIC azertianBinaryData Threads::Threads)
    if(CMAKE_DL_LIBS)
        target_link_libraries(${target} PUBLIC ${CMAKE_DL_LIBS})
    endif()
    if(WIN32)
        target_link_libraries(${target} PUBLIC bcrypt)
    endif()
endforeach()
set_target_properties(abdInvokero PROPERTIES EXPORT_NAME Runtime)
set_target_properties(abdInvoker PROPERTIES EXPORT_NAME RuntimeShared WINDOWS_EXPORT_ALL_SYMBOLS ON
    OUTPUT_NAME abdInvoker-1 VERSION 0.3.0 SOVERSION 1)
add_library(AzScript::Runtime ALIAS abdInvokero)
add_library(AzScript::RuntimeShared ALIAS abdInvoker)
add_executable(azscript-run test.cpp)
target_link_libraries(azscript-run PRIVATE abdInvoker)
add_executable(azscript-sign-extern ../tools/sign_extern.cpp)
target_include_directories(azscript-sign-extern PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(azscript-sign-extern PRIVATE abdInvoker)
if(APPLE)
    set_target_properties(azscript-sign-extern PROPERTIES INSTALL_RPATH "@loader_path/../${CMAKE_INSTALL_LIBDIR}")
elseif(UNIX)
    set_target_properties(azscript-sign-extern PROPERTIES INSTALL_RPATH "$ORIGIN/../${CMAKE_INSTALL_LIBDIR}")
endif()
install(TARGETS azscript-sign-extern RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}")
if(APPLE)
    set_target_properties(azscript-run PROPERTIES INSTALL_RPATH "@loader_path/../${CMAKE_INSTALL_LIBDIR}")
elseif(UNIX)
    set_target_properties(azscript-run PROPERTIES INSTALL_RPATH "$ORIGIN/../${CMAKE_INSTALL_LIBDIR}")
endif()
install(TARGETS abdInvoker abdInvokero EXPORT AzScriptTargets
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(TARGETS azscript-run RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}")
install(FILES library.h internelFunctions.h heepalloc.h blocks.h azertianBinaryDataValues.h acs.h
    DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/azscript/detail/interpreter")
# library.h includes these neutral declarations (debug records, error traces).
install(FILES core/debug.h core/errors.h core/format.h core/limits.h core/signature.h core/types.h core/value.h DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/azscript/detail/interpreter/core")
if(BUILD_TESTING)
    add_executable(runtime-tests runtime_tests.cpp)
    target_link_libraries(runtime-tests PRIVATE abdInvoker)
    # Shared with the compiler's TypeSafetyTest, so both sides check the same hint names.
    target_compile_definitions(runtime-tests PRIVATE AZSCRIPT_HINT_CASES="${CMAKE_CURRENT_SOURCE_DIR}/../tests/hint_names.txt")
    add_test(NAME runtime-tests COMMAND runtime-tests)
    add_executable(numeric-module-host ../tests/numeric_module_host.cpp)
    target_link_libraries(numeric-module-host PRIVATE abdInvoker)
    add_executable(runtime-benchmark-host ../tests/runtime_benchmark_host.cpp)
    target_link_libraries(runtime-benchmark-host PRIVATE abdInvoker)
    target_compile_definitions(runtime-benchmark-host PRIVATE AZSCRIPT_BENCHMARK_BUILD_TYPE="${CMAKE_BUILD_TYPE}")
    add_executable(runtime-benchmark-vm-host ../tests/runtime_benchmark_host.cpp)
    target_link_libraries(runtime-benchmark-vm-host PRIVATE abdInvoker)
    target_compile_definitions(runtime-benchmark-vm-host PRIVATE
        AZSCRIPT_BENCHMARK_BUILD_TYPE="${CMAKE_BUILD_TYPE}" AZSCRIPT_BENCHMARK_VM)
    add_executable(heap-growth-tests heap_growth_tests.cpp heepalloc.cpp)
    target_compile_definitions(heap-growth-tests PRIVATE AZSCRIPT_HEAP_GROWTH_TESTS)
    target_link_libraries(heap-growth-tests PRIVATE abdInvokero)
    add_test(NAME heap-growth-tests COMMAND heap-growth-tests)
    add_executable(variable-access-host ../tests/variable_access_host.cpp)
    target_link_libraries(variable-access-host PRIVATE abdInvoker)
    add_test(NAME variable-access-host COMMAND variable-access-host)
    add_executable(value-tests value_tests.cpp)
    target_link_libraries(value-tests PRIVATE abdInvokero)
    add_test(NAME value-tests COMMAND value-tests)
    # Migration-stage VM tests link the static runtime because they exercise
    # internal interfaces that are not part of the installed headers.
    foreach(vm_test IN ITEMS
            "vm-storage-tests:../tests/vm_storage_tests.cpp"
            "vm-frame-tests:../tests/vm_frame_tests.cpp"
            "vm-program-tests:vm/program_tests.cpp"
            "exec-model-tests:core/exec_tests.cpp"
            "operations-tests:operations_tests.cpp"
            "scalar-vm-tests:scalar_vm_tests.cpp"
            "vm-call-tests:../tests/vm_call_tests.cpp"
            "vm-session-tests:vm_session_tests.cpp"
            "vm-cleanup-tests:vm/cleanup_tests.cpp")
        string(REPLACE ":" ";" vm_test_parts "${vm_test}")
        list(GET vm_test_parts 0 vm_test_name)
        list(GET vm_test_parts 1 vm_test_source)
        add_executable(${vm_test_name} ${vm_test_source})
        target_link_libraries(${vm_test_name} PRIVATE abdInvokero)
        add_test(NAME ${vm_test_name} COMMAND ${vm_test_name})
    endforeach()
    add_executable(vm-loader-host ../tests/vm_loader_host.cpp)
    target_link_libraries(vm-loader-host PRIVATE abdInvokero)
    # Protocol-2 observation adapter for every runtime under comparison; the
    # engine is chosen per process with --engine and reported by the runtime.
    add_executable(observation-host ../tests/observation_host.cpp)
    target_link_libraries(observation-host PRIVATE abdInvokero)
endif()
