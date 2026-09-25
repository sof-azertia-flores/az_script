cmake_minimum_required(VERSION 3.20)
project(azertianBinaryData LANGUAGES CXX)
include(GNUInstallDirs)

add_library(azertianBinaryData STATIC library.cpp values.cpp)
add_library(dynazertianBinaryData SHARED library.cpp values.cpp)
foreach(target azertianBinaryData dynazertianBinaryData)
    target_compile_features(${target} PUBLIC cxx_std_20)
    target_include_directories(${target} PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/azscript/detail/abdC>)
    set_target_properties(${target} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()
set_target_properties(azertianBinaryData PROPERTIES EXPORT_NAME Data)
set_target_properties(dynazertianBinaryData PROPERTIES EXPORT_NAME DataShared WINDOWS_EXPORT_ALL_SYMBOLS ON)
add_library(AzScript::Data ALIAS azertianBinaryData)
add_library(AzScript::DataShared ALIAS dynazertianBinaryData)
install(TARGETS azertianBinaryData dynazertianBinaryData EXPORT AzScriptTargets
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
install(FILES library.h azertianBinaryDataValues.h
    DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/azscript/detail/abdC")
include(CTest)
if(BUILD_TESTING)
    add_executable(abd_regression tests/regression.cpp)
    target_link_libraries(abd_regression PRIVATE azertianBinaryData)
    add_test(NAME abd_regression COMMAND abd_regression)
endif()
