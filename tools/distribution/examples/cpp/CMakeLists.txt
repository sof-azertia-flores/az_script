cmake_minimum_required(VERSION 3.20)
project(azscript_distribution_example LANGUAGES CXX)

find_package(AzScript CONFIG REQUIRED)
add_executable(azscript-host main.cpp)
target_link_libraries(azscript-host PRIVATE AzScript::Runtime)
