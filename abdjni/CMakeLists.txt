cmake_minimum_required(VERSION 3.20)
project(abdJi LANGUAGES CXX)
include(GNUInstallDirs)
if(APPLE AND NOT DEFINED JAVA_HOME AND NOT DEFINED ENV{JAVA_HOME})
    execute_process(COMMAND /usr/libexec/java_home
        OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE java_home_result)
    if(NOT java_home_result EQUAL 0)
        message(FATAL_ERROR "Install a JDK or set JAVA_HOME before building JNI")
    endif()
endif()
find_package(JNI REQUIRED COMPONENTS JVM)
if(NOT TARGET abdInvoker)
    add_subdirectory(../interpreter ${CMAKE_CURRENT_BINARY_DIR}/interpreter)
endif()
add_library(abdJ SHARED lib.cpp)
target_compile_features(abdJ PRIVATE cxx_std_20)
target_include_directories(abdJ PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(abdJ PRIVATE abdInvoker)
set_target_properties(abdJ PROPERTIES EXPORT_NAME JNI)
add_library(AzScript::JNI ALIAS abdJ)
install(TARGETS abdJ EXPORT AzScriptTargets
    RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}"
    LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
    ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
if(BUILD_TESTING AND NOT AZSCRIPT_SANITIZE)
    find_package(Java 17 REQUIRED COMPONENTS Development)
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    add_test(NAME jni-regression
        COMMAND ${Python3_EXECUTABLE}
            ${CMAKE_CURRENT_SOURCE_DIR}/../abdJavaInvoker/tests/run_jni_tests.py
            --library $<TARGET_FILE:abdJ>
            --work-dir ${CMAKE_CURRENT_BINARY_DIR}/jni-tests
            --java ${Java_JAVA_EXECUTABLE}
            --javac ${Java_JAVAC_EXECUTABLE})
    set_tests_properties(jni-regression PROPERTIES TIMEOUT 120)
endif()
