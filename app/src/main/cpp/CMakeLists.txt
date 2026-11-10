cmake_minimum_required(VERSION 3.22.1)
project("workspacefilemanager")

# ── llama.cpp (embedded via add_subdirectory) ─────────────────────────────
# We pull in llama.cpp's own CMakeLists so we get the correct
# src/ and ggml/ file trees without hard-coding every .cpp path.
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Disable GPU back-ends — Android devices rely on CPU only
set(GGML_CUDA          OFF  CACHE BOOL "" FORCE)
set(GGML_METAL         OFF  CACHE BOOL "" FORCE)
set(GGML_OPENCL        OFF  CACHE BOOL "" FORCE)
set(GGML_HIP           OFF  CACHE BOOL "" FORCE)
set(GGML_SYCL          OFF  CACHE BOOL "" FORCE)
set(GGML_VULKAN        OFF  CACHE BOOL "" FORCE)
set(GGML_KOMPUTE       OFF  CACHE BOOL "" FORCE)
set(GGML_RPC           OFF  CACHE BOOL "" FORCE)
set(GGML_WEBGPU        OFF  CACHE BOOL "" FORCE)
set(GGML_WEBNN         OFF  CACHE BOOL "" FORCE)
set(LLAMA_BLAS         OFF  CACHE BOOL "" FORCE)
set(LLAMA_NATIVE       OFF  CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)

# ── JNI Bridge ────────────────────────────────────────────────────────────
add_library(
    workspaceai           # name of the .so that Android loads
    SHARED
    native-llama-bridge.cpp
)

target_include_directories(workspaceai PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

# std::atomic / std::thread / if-init need C++17 — llama.cpp already requires it,
# but the JNI target must opt in explicitly.
target_compile_features(workspaceai PRIVATE cxx_std_17)
find_package(Threads REQUIRED)
target_link_libraries(workspaceai Threads::Threads)

find_library(log-lib log)

target_link_libraries(
    workspaceai
    llama
    ${log-lib}
    android
)
