cmake_minimum_required(VERSION 3.22.1)
project(jandal_llama_probe LANGUAGES C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if (GGML_OPENCL)
    if (NOT OPENCL_ANDROID_LIBRARY OR NOT EXISTS "${OPENCL_ANDROID_LIBRARY}")
        message(FATAL_ERROR "OpenCL probe requires -PopenclSystemLibrary=<S23 libOpenCL.so link input>")
    endif ()
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_HEADERS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_HEADERS_BUILD_CXX_TESTS OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG 82f60316cc142e7b21dc1d8983b26de18858fbd9
        GIT_SHALLOW FALSE
    )
    FetchContent_MakeAvailable(opencl_headers)
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}")
    set(OpenCL_LIBRARY "${OPENCL_ANDROID_LIBRARY}")
    find_package(OpenCL REQUIRED)
endif ()

# Android's Debug toolchain defaults to -O0. Keep the Debug configuration and its -g
# information, but optimize every native target (including fetched llama.cpp/ggml) like
# the Box reference build so the probe's CPU prefill is not measured at -O0.
add_compile_options("$<$<CONFIG:Debug>:-O3>")

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 620d2225cc436ab0b001e3200e590e3b286ef9ab
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(jandal_llama_probe SHARED llama_jni.cpp)
target_compile_features(jandal_llama_probe PRIVATE cxx_std_17)
target_link_libraries(jandal_llama_probe PRIVATE llama-common llama log android)
if (GGML_OPENCL)
    target_link_libraries(jandal_llama_probe PRIVATE ${OpenCL_LIBRARIES})
    target_include_directories(jandal_llama_probe PRIVATE ${OpenCL_INCLUDE_DIRS})
    target_compile_definitions(jandal_llama_probe PRIVATE JANDAL_LLAMA_OPENCL_PROBE=1)
endif ()
