cmake_minimum_required(VERSION 3.22.1)
project(kernelvec)

add_library(
    kernelvec
    SHARED
    kernel_vec_jni.cpp
    sqlite-vec.c
    sqlite3.c
)

# sqlite-vec must be compiled as part of the SQLite core (not a loadable extension)
# so its sqlite3_api pointer is unused and all calls go directly to our bundled SQLite.
set_source_files_properties(sqlite-vec.c PROPERTIES COMPILE_DEFINITIONS "SQLITE_CORE")

# Bundled SQLite configuration — lightweight, WAL-friendly, no extension loading.
target_compile_definitions(kernelvec PRIVATE
    SQLITE_THREADSAFE=2
    SQLITE_DEFAULT_WAL_SYNCHRONOUS=1
    SQLITE_OMIT_LOAD_EXTENSION
)

find_library(log-lib log)
target_link_libraries(kernelvec ${log-lib})
target_compile_options(kernelvec PRIVATE -O2)
