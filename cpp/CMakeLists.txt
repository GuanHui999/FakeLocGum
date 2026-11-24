cmake_minimum_required(VERSION 3.22.1)
project("fakeloc_motion")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FRIDA_GUM_DIR "${CMAKE_CURRENT_LIST_DIR}/frida-gum")

add_library(frida-gum STATIC IMPORTED)
set_target_properties(frida-gum PROPERTIES
    IMPORTED_LOCATION "${FRIDA_GUM_DIR}/${ANDROID_ABI}/libfrida-gum.a"
    INTERFACE_INCLUDE_DIRECTORIES "${FRIDA_GUM_DIR}"
)

add_library(fakeloc_motion SHARED motion_engine.cpp)
target_include_directories(fakeloc_motion PRIVATE "${FRIDA_GUM_DIR}")

find_library(android-lib android)
find_library(log-lib log)
find_library(dl-lib dl)
find_library(z-lib z)
find_library(m-lib m)
# `atomic` is deliberately not searched for: NDK r23+ dropped libatomic from the
# unified sysroot (clang links __atomic_* out of its own compiler-rt builtins),
# so `find_library(atomic-lib atomic)` resolves to NOTFOUND and fails the CMake
# configure step outright.

target_compile_definitions(fakeloc_motion PRIVATE
    GUM_STATIC
    GUM_ANDROID
    _GNU_SOURCE
)

target_compile_options(fakeloc_motion PRIVATE
    -fno-rtti
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror=return-type
)

target_link_options(fakeloc_motion PRIVATE -pthread)

target_link_libraries(fakeloc_motion
    frida-gum
    ${android-lib}
    ${log-lib}
    ${dl-lib}
    ${z-lib}
    ${m-lib}
)
