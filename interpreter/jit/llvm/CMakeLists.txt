# The JIT module: LLVM is linked statically and stays private to it, so the
# runtime libraries, their static consumers and JVM hosts never see LLVM.
find_package(LLVM 18.1.8 EXACT CONFIG REQUIRED PATHS "${AZSCRIPT_LLVM_PREFIX}/lib/cmake/llvm" NO_DEFAULT_PATH)
llvm_map_components_to_libnames(AZSCRIPT_LLVM_LIBRARIES orcjit jitlink native passes)
add_library(azscript-jit MODULE module.cpp)
separate_arguments(AZSCRIPT_LLVM_DEFINITIONS NATIVE_COMMAND "${LLVM_DEFINITIONS}")
target_compile_definitions(azscript-jit PRIVATE ${AZSCRIPT_LLVM_DEFINITIONS})
target_include_directories(azscript-jit SYSTEM PRIVATE ${LLVM_INCLUDE_DIRS})
target_link_libraries(azscript-jit PRIVATE ${AZSCRIPT_LLVM_LIBRARIES})
# Next to the runtime library and the executables, where the loader looks.
set_target_properties(azscript-jit PROPERTIES OUTPUT_NAME azscript-jit
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/../.."
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
if(APPLE)
    target_link_options(azscript-jit PRIVATE "LINKER:-exported_symbol,_azscript_jit_backend_v1")
else()
    target_link_options(azscript-jit PRIVATE "LINKER:--exclude-libs,ALL"
        "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map" "LINKER:-Bsymbolic")
endif()
install(TARGETS azscript-jit LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})
