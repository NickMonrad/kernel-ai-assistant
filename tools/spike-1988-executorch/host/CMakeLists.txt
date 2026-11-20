# Host check for #1988: ExecuTorch v1.5.1 built from source with the Android preset's XNNPACK options
# (-DEXECUTORCH_XNNPACK_ENABLE_WEIGHT_CACHE=ON -DEXECUTORCH_XNNPACK_SHARED_WORKSPACE=ON, --preset linux),
# installed to a prefix, then: cmake -S . -B build -DCMAKE_PREFIX_PATH=<prefix> && cmake --build build
cmake_minimum_required(VERSION 3.24)
project(et1988_runner CXX)
set(CMAKE_CXX_STANDARD 17)
find_package(executorch REQUIRED CONFIG)
add_executable(runner xnnpack_options_runner.cpp)
target_link_libraries(runner PRIVATE executorch_core extension_module_static extension_tensor extension_data_loader extension_flat_tensor extension_named_data_map
  xnnpack_backend optimized_native_cpu_ops_lib quantized_ops_lib custom_ops extension_threadpool cpuinfo pthreadpool)
