cmake_minimum_required(VERSION 3.22.1)
project(llama-android C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. Path to llama.cpp source submodule / directory
# Verified pinned release: tag b3925 (commit 0e41b300ed28f7fe185d938b2e3d56a0bf7411ed)
set(LLAMA_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if (NOT EXISTS "${LLAMA_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR 
        "\n========================================================================\n"
        "[FATAL ERROR] Исходный код llama.cpp не найден в ${LLAMA_SRC_DIR}!\n"
        "Требуется зафиксированный тег: b3925 (commit 0e41b300ed28f7fe185d938b2e3d56a0bf7411ed).\n"
        "Для клонирования выполните:\n"
        "  git clone --depth 1 --branch b3925 https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp\n"
        "========================================================================\n"
    )
endif()

# 2. llama.cpp build configuration options
set(LLAMA_STANDALONE OFF CACHE BOOL "llama standalone" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama build common utils" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama build server" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build static llama libraries" FORCE)

# ARM64-v8a target optimizations for Helio G99 / Cortex-A76 + Cortex-A55
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    add_compile_options(-O3 -DNDEBUG -flto -march=armv8-a+fp+simd)
    message(STATUS "Configuring for arm64-v8a with ARM NEON / SIMD optimizations")
endif()

# 3. Add genuine llama.cpp directory
add_subdirectory(${LLAMA_SRC_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# 4. Define our JNI bridge shared library
add_library(
    llama-android
    SHARED
    llama-jni.cpp
)

target_include_directories(
    llama-android
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

# 5. Link genuine llama engine and system libraries
target_link_libraries(
    llama-android
    PRIVATE
    llama
    ggml
    ${log-lib}
    ${android-lib}
)
