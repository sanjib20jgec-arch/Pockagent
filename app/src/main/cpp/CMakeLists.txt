cmake_minimum_required(VERSION 3.22.1)
project(PocketAgentWhisper LANGUAGES C CXX)

include(FetchContent)

# Build only the CPU speech engine and this app's small JNI bridge.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    URL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.9.5
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(pocketagent_whisper SHARED whisper_jni.cpp)
target_compile_features(pocketagent_whisper PRIVATE cxx_std_17)
target_include_directories(pocketagent_whisper PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_link_libraries(pocketagent_whisper PRIVATE whisper log android)
