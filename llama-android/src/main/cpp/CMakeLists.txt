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

# Keep the upstream Android binding as the source of truth, while applying the two
# GameOCR-specific correctness fixes in the build directory. Exact-match guards make
# an upstream change fail at configure time instead of silently dropping either fix.
file(READ "${UPSTREAM_CPP}/ai_chat.cpp" GAMEOCR_AI_CHAT_SOURCE)
string(REPLACE "\r\n" "\n" GAMEOCR_AI_CHAT_SOURCE "${GAMEOCR_AI_CHAT_SOURCE}")

set(GAMEOCR_USER_RESET_ORIGINAL [=[
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
]=])
set(GAMEOCR_USER_RESET_PATCHED [=[
    // Each OCR block is an independent translation request. Keep the system prompt,
    // but discard all previous user/assistant tokens and sampler state.
    reset_short_term_states();
    common_sampler_reset(g_sampler);
    if (!llama_memory_seq_rm(
            llama_get_memory(g_context), 0, system_prompt_position, -1)) {
        LOGe("%s: Failed to reset conversation memory", __func__);
        return 3;
    }
    current_position = system_prompt_position;
    const bool keep_system_message =
            !chat_msgs.empty() && chat_msgs.front().role == ROLE_SYSTEM;
    chat_msgs.resize(keep_system_message ? 1 : 0);
    LOGi("%s: Reset conversation to system prompt position %d",
         __func__, system_prompt_position);

    // Obtain and tokenize user prompt
]=])
string(FIND "${GAMEOCR_AI_CHAT_SOURCE}" "${GAMEOCR_USER_RESET_ORIGINAL}" GAMEOCR_USER_RESET_INDEX)
if(GAMEOCR_USER_RESET_INDEX EQUAL -1)
    message(FATAL_ERROR "GameOCR ai_chat conversation reset patch no longer matches upstream")
endif()
string(REPLACE
        "${GAMEOCR_USER_RESET_ORIGINAL}"
        "${GAMEOCR_USER_RESET_PATCHED}"
        GAMEOCR_AI_CHAT_SOURCE
        "${GAMEOCR_AI_CHAT_SOURCE}")

set(GAMEOCR_STOP_POSITION_ORIGINAL [=[
    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
]=])
set(GAMEOCR_STOP_POSITION_PATCHED [=[
    current_position += (int) user_tokens.size();
    stop_generation_position = current_position + n_predict;
]=])
string(FIND "${GAMEOCR_AI_CHAT_SOURCE}" "${GAMEOCR_STOP_POSITION_ORIGINAL}" GAMEOCR_STOP_POSITION_INDEX)
if(GAMEOCR_STOP_POSITION_INDEX EQUAL -1)
    message(FATAL_ERROR "GameOCR ai_chat generation limit patch no longer matches upstream")
endif()
string(REPLACE
        "${GAMEOCR_STOP_POSITION_ORIGINAL}"
        "${GAMEOCR_STOP_POSITION_PATCHED}"
        GAMEOCR_AI_CHAT_SOURCE
        "${GAMEOCR_AI_CHAT_SOURCE}")

set(GAMEOCR_AI_CHAT_CPP "${CMAKE_CURRENT_BINARY_DIR}/gameocr_ai_chat.cpp")
file(WRITE "${GAMEOCR_AI_CHAT_CPP}" "${GAMEOCR_AI_CHAT_SOURCE}")

# Android NDK 已携带 glslc 与 Shaderc 使用的 Khronos SPIR-V headers，但不像桌面版
# Vulkan SDK 那样附带 SPIRV-HeadersConfig.cmake。显式桥接 NDK 自带依赖，使上游
# ggml-vulkan 的 find_package 调用可复现，且不要求每台开发机另装 LunarG SDK。
if(ANDROID AND GGML_VULKAN)
    set(SPIRV_HEADERS_INCLUDE_DIR
            "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
    set(NDK_GLSLC
            "${ANDROID_NDK}/shader-tools/${CMAKE_HOST_SYSTEM_NAME}-${CMAKE_HOST_SYSTEM_PROCESSOR}/glslc")
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(NDK_GLSLC "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
    endif()

    if(NOT EXISTS "${SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR "Android NDK SPIR-V headers not found: ${SPIRV_HEADERS_INCLUDE_DIR}")
    endif()
    if(NOT EXISTS "${NDK_GLSLC}")
        message(FATAL_ERROR "Android NDK glslc not found: ${NDK_GLSLC}")
    endif()

    set("SPIRV-Headers_DIR" "${CMAKE_CURRENT_LIST_DIR}/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${NDK_GLSLC}" CACHE FILEPATH "" FORCE)
    include_directories(SYSTEM "${SPIRV_HEADERS_INCLUDE_DIR}")

    # NDK 27 的 Vulkan C headers 是 1.3.275，但未附带 llama.cpp 使用的 Vulkan-Hpp。
    # 固定到相同 header 版本并校验归档哈希，避免浮动依赖或 C/C++ API 版本错配。
    include(FetchContent)
    FetchContent_Declare(
            gameocr_vulkan_headers
            URL "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.zip"
            URL_HASH "SHA256=2F8B6544E80EE083E11DABDA6B2474B7FF758584107DBA8468903D454A04EF2E")
    FetchContent_GetProperties(gameocr_vulkan_headers)
    if(NOT gameocr_vulkan_headers_POPULATED)
        FetchContent_Populate(gameocr_vulkan_headers)
    endif()
    include_directories(SYSTEM "${gameocr_vulkan_headers_SOURCE_DIR}/include")

    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(GAMEOCR_VSWHERE "C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe")
        if(NOT EXISTS "${GAMEOCR_VSWHERE}")
            message(FATAL_ERROR "Visual Studio locator not found: ${GAMEOCR_VSWHERE}")
        endif()
        execute_process(
                COMMAND "${GAMEOCR_VSWHERE}" -latest -products *
                        -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64
                        -property installationPath
                RESULT_VARIABLE GAMEOCR_VSWHERE_RESULT
                OUTPUT_VARIABLE GAMEOCR_VS_INSTALLATION
                OUTPUT_STRIP_TRAILING_WHITESPACE)
        if(NOT GAMEOCR_VSWHERE_RESULT EQUAL 0 OR GAMEOCR_VS_INSTALLATION STREQUAL "")
            message(FATAL_ERROR "Visual Studio C++ x64 toolchain was not found")
        endif()
        file(TO_CMAKE_PATH "${GAMEOCR_VS_INSTALLATION}" GAMEOCR_VS_INSTALLATION)
        set(GAMEOCR_VCVARS64 "${GAMEOCR_VS_INSTALLATION}/VC/Auxiliary/Build/vcvars64.bat")
        if(NOT EXISTS "${GAMEOCR_VCVARS64}")
            message(FATAL_ERROR "Visual Studio vcvars64.bat not found: ${GAMEOCR_VCVARS64}")
        endif()

        file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" GAMEOCR_HOST_NINJA)
        configure_file(
                "${CMAKE_CURRENT_LIST_DIR}/cmake/vulkan-host-toolchain.cmake.in"
                "${CMAKE_BINARY_DIR}/gameocr-vulkan-host-toolchain.cmake"
                @ONLY)
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
                "${CMAKE_BINARY_DIR}/gameocr-vulkan-host-toolchain.cmake" CACHE FILEPATH "" FORCE)

        set(GAMEOCR_REAL_CMAKE "${CMAKE_COMMAND}")
        configure_file(
                "${CMAKE_CURRENT_LIST_DIR}/cmake/cmake-with-ninja.cmd.in"
                "${CMAKE_BINARY_DIR}/gameocr-cmake-with-ninja.cmd"
                @ONLY)
        set(CMAKE_COMMAND "${CMAKE_BINARY_DIR}/gameocr-cmake-with-ninja.cmd")
    endif()
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${GAMEOCR_AI_CHAT_CPP}
        llama_thread_policy.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
        llama_model_load_from_file=gameocr_llama_model_load_from_file
        llama_init_from_model=gameocr_llama_init_from_model
        common_sampler_init=gameocr_common_sampler_init
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
