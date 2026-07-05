# 屏译 :llama-android 的 CMake 入口。本文件是 ggml-org/llama.cpp 上游
# examples/llama.android/lib/src/main/cpp/CMakeLists.txt 的镜像，**只改了 LLAMA_SRC 路径**
# 与源文件引用，让源码全部从 third_party/llama.cpp submodule 直读，无需拷贝。
#
# 同步上游：git submodule update --remote third_party/llama.cpp。如果上游 CMakeLists.txt
# 结构变了，需要手动同步本文件——保留这条说明便于排查。

cmake_minimum_required(VERSION 3.22)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI} (屏译仅支持 arm64-v8a)")
    endif()
endif()

# 从 llama-android/src/main/cpp/ 上溯 4 层到仓库根，再进 third_party/llama.cpp。
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
set(UPSTREAM_CPP ${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${UPSTREAM_CPP}/ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${UPSTREAM_CPP}
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
