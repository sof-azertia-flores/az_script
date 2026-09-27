cmake_minimum_required(VERSION 3.20)
project(AzScript VERSION 0.2.0 LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(GNUInstallDirs)
include(CMakePackageConfigHelpers)
# Installed shared libraries resolve siblings after the package is moved.
if(APPLE)
    set(CMAKE_INSTALL_NAME_DIR "@rpath")
    set(CMAKE_INSTALL_RPATH "@loader_path")
elseif(UNIX)
    set(CMAKE_INSTALL_RPATH "$ORIGIN")
endif()
if(WIN32)
    set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)
endif()
option(AZSCRIPT_BUILD_JNI "Build Java native bridge" ON)
option(AZSCRIPT_SANITIZE "Enable address and undefined behavior sanitizers" OFF)
include(CTest)
if(AZSCRIPT_SANITIZE AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()
add_subdirectory(abdC)
add_subdirectory(interpreter)
if(AZSCRIPT_BUILD_JNI)
    add_subdirectory(abdjni)
endif()

option(AZSCRIPT_BUILD_EXAMPLES "Build the C++ embedding example" ON)
if(AZSCRIPT_BUILD_EXAMPLES)
    add_executable(azscript-embed examples/embedded.cpp)
    target_link_libraries(azscript-embed PRIVATE abdInvokero)
endif()

configure_file(cmake/runtime.hpp.in "${CMAKE_CURRENT_BINARY_DIR}/public-headers/azscript/runtime.hpp" @ONLY)
configure_file(cmake/data.hpp.in "${CMAKE_CURRENT_BINARY_DIR}/public-headers/azscript/data.hpp" @ONLY)
install(FILES
    "${CMAKE_CURRENT_BINARY_DIR}/public-headers/azscript/runtime.hpp"
    "${CMAKE_CURRENT_BINARY_DIR}/public-headers/azscript/data.hpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/azscript/extern_library.hpp"
    DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/azscript")
configure_package_config_file(cmake/AzScriptConfig.cmake.in
    "${CMAKE_CURRENT_BINARY_DIR}/AzScriptConfig.cmake"
    INSTALL_DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/AzScript")
write_basic_package_version_file("${CMAKE_CURRENT_BINARY_DIR}/AzScriptConfigVersion.cmake"
    VERSION "${AzScript_VERSION}" COMPATIBILITY SameMajorVersion)
install(EXPORT AzScriptTargets NAMESPACE AzScript::
    DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/AzScript")
install(FILES
    "${CMAKE_CURRENT_BINARY_DIR}/AzScriptConfig.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/AzScriptConfigVersion.cmake"
    DESTINATION "${CMAKE_INSTALL_LIBDIR}/cmake/AzScript")
if(MSVC)
    set(CMAKE_INSTALL_SYSTEM_RUNTIME_DESTINATION "${CMAKE_INSTALL_BINDIR}")
    include(InstallRequiredSystemLibraries)
elseif(MINGW)
    # MinGW's C++/GCC/thread runtimes are not Windows system DLLs.
    get_filename_component(AZSCRIPT_COMPILER_DIRECTORY "${CMAKE_CXX_COMPILER}" DIRECTORY)
    configure_file(cmake/InstallMinGWRuntime.cmake.in
        "${CMAKE_CURRENT_BINARY_DIR}/InstallMinGWRuntime.cmake" @ONLY)
    install(SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/InstallMinGWRuntime.cmake")
endif()
