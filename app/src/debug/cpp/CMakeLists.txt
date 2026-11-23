cmake_minimum_required(VERSION 3.22.1)
project(kokoro_phonemis LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_RUNNER OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ET_ON OFF CACHE BOOL "" FORCE)
set(VERBALIS_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(VERBALIS_INSTALL OFF CACHE BOOL "" FORCE)

set(PHONEMIS_SOURCE_DIR "$ENV{HOME}/.cache/kokoro-et-1557/phonemis-src" CACHE PATH "Local phonemis source checkout")
if(NOT EXISTS "${PHONEMIS_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "phonemis sources not found at ${PHONEMIS_SOURCE_DIR}")
endif()

# Reuse the already-fetched, pinned Verbalis source from the phonemis oracle checkout.
set(FETCHCONTENT_SOURCE_DIR_VERBALIS "${PHONEMIS_SOURCE_DIR}/build/_deps/verbalis-src" CACHE PATH "Verbalis source")
add_subdirectory("${PHONEMIS_SOURCE_DIR}" phonemis-build)

set_target_properties(phonemis PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(kokoro_phonemis SHARED kokoro_phonemis_jni.cpp)
target_compile_features(kokoro_phonemis PRIVATE cxx_std_20)
target_include_directories(kokoro_phonemis PRIVATE
    "${PHONEMIS_SOURCE_DIR}/src"
    "${PHONEMIS_SOURCE_DIR}/src/third-party"
)
target_link_libraries(kokoro_phonemis PRIVATE phonemis)
find_library(android-log-lib log)
target_link_libraries(kokoro_phonemis PRIVATE ${android-log-lib})
