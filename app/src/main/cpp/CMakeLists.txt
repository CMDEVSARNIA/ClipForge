cmake_minimum_required(VERSION 3.22)

# whisper.cpp in a Debug build is unusably slow, so native code is always optimized.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(clipforge_whisper C CXX)
set(CMAKE_CXX_STANDARD 17)

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp submodule is missing. Run: git submodule update --init --recursive")
endif()

# Static whisper + ggml (CPU only), linked into one JNI library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
# Portable baseline (armv8-a / x86-64): no instructions older phones lack.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool; avoids shipping libomp.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(clipforge_whisper SHARED whisper_jni.cpp whisper_core.cpp)
target_compile_options(clipforge_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(clipforge_whisper PRIVATE whisper log android)
target_link_options(clipforge_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
