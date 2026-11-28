cmake_minimum_required(VERSION 3.22.1)
project(kokoro_phonemis LANGUAGES CXX)

# Kokoro phonemis JNI for the Kokoro voice lane (#2117, from #1557). Built by core/voice's externalNativeBuild.
# Both sources are GitHub archives of an exact commit, verified by SHA-256 before extraction. FetchContent fails
# configuration on a mismatch, so unverified source is never built. To update a pin, change the commit and recompute
# the SHA-256 of https://github.com/<owner>/<repo>/archive/<commit>.tar.gz; both values change together.
# Offline rebuild: configure once with network, then reconfigure with -DFETCHCONTENT_FULLY_DISCONNECTED=ON; the
# verified sources stay in the build's _deps directory and are not downloaded again.
set(PHONEMIS_COMMIT 5ec260e33127de044bda7147f52cea27a7e2e491)
set(PHONEMIS_SHA256 ff1d5a0467ec65dbffe771692d5089319461418a2c7e558677eb8daea0c94575)
set(VERBALIS_COMMIT b14f1b3f96e759326935de12f81b35b87ec57f6a) # tag v0.1.1
set(VERBALIS_SHA256 26adc3f881571b6ef195837493fceb299156f510d69b36f7f72a5e29ec94f2b9)

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
  URL https://github.com/software-mansion-labs/Verbalis/archive/${VERBALIS_COMMIT}.tar.gz
  URL_HASH SHA256=${VERBALIS_SHA256}
  SOURCE_SUBDIR cpp)
# Phonemis' only submodule is Verbalis, which is declared above; the archive does not include submodules.
FetchContent_Declare(phonemis
  URL https://github.com/software-mansion-labs/Phonemis/archive/${PHONEMIS_COMMIT}.tar.gz
  URL_HASH SHA256=${PHONEMIS_SHA256})
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
