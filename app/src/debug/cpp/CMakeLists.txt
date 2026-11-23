cmake_minimum_required(VERSION 3.22.1)
project(kokoro_phonemis LANGUAGES CXX)

# Debug-only spike lane (#1557). Configured only when Gradle gets -Pkokoro.phonemis=true (app/build.gradle.kts).
# Both sources are pinned by commit; nothing is read from a home directory.
set(PHONEMIS_GIT_COMMIT 5ec260e33127de044bda7147f52cea27a7e2e491)
set(VERBALIS_GIT_COMMIT b14f1b3f96e759326935de12f81b35b87ec57f6a) # tag v0.1.1

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_RUNNER OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ET_ON OFF CACHE BOOL "" FORCE)
set(VERBALIS_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(VERBALIS_INSTALL OFF CACHE BOOL "" FORCE)

include(FetchContent)
set(FETCHCONTENT_QUIET ON)

# Declared before phonemis: phonemis checks for an existing verbalis::verbalis target and skips its own tag fetch.
FetchContent_Declare(verbalis
  GIT_REPOSITORY https://github.com/software-mansion-labs/Verbalis.git
  GIT_TAG ${VERBALIS_GIT_COMMIT}
  SOURCE_SUBDIR cpp)
FetchContent_Declare(phonemis
  GIT_REPOSITORY https://github.com/software-mansion-labs/Phonemis.git
  GIT_TAG ${PHONEMIS_GIT_COMMIT})
FetchContent_MakeAvailable(verbalis phonemis)

set_target_properties(phonemis PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(kokoro_phonemis SHARED kokoro_phonemis_jni.cpp)
target_compile_features(kokoro_phonemis PRIVATE cxx_std_20)
target_include_directories(kokoro_phonemis PRIVATE
    "${phonemis_SOURCE_DIR}/src"
    "${phonemis_SOURCE_DIR}/src/third-party"
)
target_link_libraries(kokoro_phonemis PRIVATE phonemis)
find_library(android-log-lib log)
target_link_libraries(kokoro_phonemis PRIVATE ${android-log-lib})
