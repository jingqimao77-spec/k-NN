#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#

cmake_minimum_required(VERSION 3.24.0)

project(KNNPlugin_JNI)

include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/macros.cmake)

# ---------------------------------- SETUP ----------------------------------
# Target libraries to be compiled
# Shared library with common utilities. Not a JNI library. Other JNI libs should depend on this one.
set(TARGET_LIB_UTIL opensearchknn_util)
set(TARGET_LIB_COMMON opensearchknn_common)  # common lib for JNI
set(TARGET_LIB_NMSLIB opensearchknn_nmslib)  # nmslib JNI
set(TARGET_LIB_FAISS opensearchknn_faiss)    # faiss JNI
set(TARGET_LIB_SIMD opensearchknn_simd)      # SIMD computing JNI
set(TARGET_LIB_KNOWHERE opensearchknn_knowhere) # knowhere JNI
set(TARGET_LIBS "")  # Libs to be installed

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)
option(CONFIG_FAISS "Configure faiss library build when this is on")
option(CONFIG_NMSLIB "Configure nmslib library build when this is on")
option(CONFIG_TEST "Configure tests when this is on")

if (${CONFIG_FAISS} STREQUAL OFF AND ${CONFIG_NMSLIB} STREQUAL OFF AND ${CONFIG_TEST} STREQUAL OFF)
    set(CONFIG_ALL ON)
else()
    set(CONFIG_ALL OFF)
endif ()

# `git am` will create commits from the patches in the native libraries. This is ideal for development envs
# because it prevents full lib rebuild everytime cmake is run. However, for build systems that will run the
# build workflow once, it can cause issues because git commits require that the user and the user's email be set.
# See https://github.com/opensearch-project/k-NN/issues/1651. So, we provide a flag that allows users to select between
# the two
if(NOT DEFINED COMMIT_LIB_PATCHES OR "${COMMIT_LIB_PATCHES}" STREQUAL true)
    set(GIT_PATCH_COMMAND am)
else()
    set(GIT_PATCH_COMMAND apply)
endif()
message(STATUS "Using the following git patch command: \"${GIT_PATCH_COMMAND}\"")

# Set OS specific variables
if (${CMAKE_SYSTEM_NAME} STREQUAL Darwin)
    set(CMAKE_MACOSX_RPATH 1)
    set(JVM_OS_TYPE darwin)
    set(LIB_EXT .jnilib)
elseif(${CMAKE_SYSTEM_NAME} STREQUAL Linux)
    set(JVM_OS_TYPE linux)
    set(LIB_EXT .so)
elseif(${CMAKE_SYSTEM_NAME} STREQUAL Windows)
# Set the CXX_COMPILER_VERSION, CMAKE_CXX_FLAGS, JVM_OS_TYPE, prefix and extension for the target libraries that are built.
    set(CXX_COMPILER_VERSION ${CMAKE_CXX_COMPILER_VERSION})
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fpermissive")
    set(JVM_OS_TYPE win32)
    set(LIB_EXT .dll)
    set(CMAKE_SHARED_LIBRARY_PREFIX "")
    set(CMAKE_STATIC_LIBRARY_PREFIX "")
else()
    message(FATAL_ERROR "Unable to run on system: ${CMAKE_SYSTEM_NAME}")
endif()

# By default, set to 0.0.0
if(NOT KNN_PLUGIN_VERSION)
    set(KNN_PLUGIN_VERSION "0.0.0")
endif()

# Set architecture specific variables
if (${CMAKE_SYSTEM_PROCESSOR} STREQUAL aarch64)
    set(MACH_ARCH arm64)
elseif(${CMAKE_SYSTEM_PROCESSOR} STREQUAL x86_64)
    set(MACH_ARCH x64)
endif()
# ----------------------------------------------------------------------------

# ---------------------------------- UTIL ----------------------------------
add_library(
    ${TARGET_LIB_UTIL}
    SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/src/jni_util.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/commons.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/opensearch_file_manager.cpp
)
target_include_directories(${TARGET_LIB_UTIL} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/${JVM_OS_TYPE})
opensearch_set_common_properties(${TARGET_LIB_UTIL})
list(APPEND TARGET_LIBS ${TARGET_LIB_UTIL})
# ----------------------------------------------------------------------------

# ---------------------------------- COMMON ----------------------------------
add_library(${TARGET_LIB_COMMON} SHARED ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_JNICommons.cpp)
target_link_libraries(${TARGET_LIB_COMMON} ${TARGET_LIB_UTIL})
target_include_directories(${TARGET_LIB_COMMON} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/${JVM_OS_TYPE})
opensearch_set_common_properties(${TARGET_LIB_COMMON})
list(APPEND TARGET_LIBS ${TARGET_LIB_COMMON})
# ----------------------------------------------------------------------------

# ---------------------------------- NMSLIB ----------------------------------
if (${CONFIG_NMSLIB} STREQUAL ON OR ${CONFIG_ALL} STREQUAL ON OR ${CONFIG_TEST} STREQUAL ON)
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/init-nmslib.cmake)
    add_library(${TARGET_LIB_NMSLIB} SHARED ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_NmslibService.cpp ${CMAKE_CURRENT_SOURCE_DIR}/src/nmslib_wrapper.cpp)
    target_link_libraries(${TARGET_LIB_NMSLIB} NonMetricSpaceLib ${TARGET_LIB_UTIL})
    target_include_directories(${TARGET_LIB_NMSLIB} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/${JVM_OS_TYPE} ${CMAKE_CURRENT_SOURCE_DIR}/external/nmslib/similarity_search/include)
    opensearch_set_common_properties(${TARGET_LIB_NMSLIB})
    list(APPEND TARGET_LIBS ${TARGET_LIB_NMSLIB})
endif ()

# ---------------------------------------------------------------------------

# ---------------------------------- FAISS ----------------------------------
if (${CONFIG_FAISS} STREQUAL ON OR ${CONFIG_ALL} STREQUAL ON OR ${CONFIG_TEST} STREQUAL ON)
    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/init-faiss.cmake)
    find_package(OpenMP REQUIRED)
    add_library(
        ${TARGET_LIB_FAISS} SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_FaissService.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/faiss_wrapper.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/faiss_util.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/faiss_index_service.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/src/faiss_methods.cpp
    )
    target_link_libraries(${TARGET_LIB_FAISS} ${TARGET_LINK_FAISS_LIB} ${TARGET_LIB_UTIL} OpenMP::OpenMP_CXX)
    target_include_directories(${TARGET_LIB_FAISS} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        $ENV{JAVA_HOME}/include
        $ENV{JAVA_HOME}/include/${JVM_OS_TYPE}
        ${CMAKE_CURRENT_SOURCE_DIR}/external/faiss
    )
    opensearch_set_common_properties(${TARGET_LIB_FAISS})
    list(APPEND TARGET_LIBS ${TARGET_LIB_FAISS})
endif ()

# ---------------------------------- KNOWHERE ----------------------------------
set(KNOWHERE_BUNDLE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/external/knowhere")
set(KNOWHERE_LIB_DIR "${KNOWHERE_BUNDLE_DIR}/lib")
set(KNOWHERE_INCLUDE_DIR "${KNOWHERE_BUNDLE_DIR}/include")

add_library(${TARGET_LIB_KNOWHERE} SHARED 
    ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_KnowhereService.cpp 
    ${CMAKE_CURRENT_SOURCE_DIR}/src/knowhere_wrapper.cpp
)

# Use -Wl,--whole-archive to ensure all symbols from static libs are included in the SO
# Note: milvus-common headers are now under ${KNOWHERE_INCLUDE_DIR}/milvus-common
target_link_libraries(${TARGET_LIB_KNOWHERE} 
    ${TARGET_LIB_UTIL}
    -Wl,--whole-archive
    ${KNOWHERE_LIB_DIR}/libknowhere.a
    ${KNOWHERE_LIB_DIR}/libknowhere_utils.a
    ${KNOWHERE_LIB_DIR}/libmilvus-common.a
    -Wl,--no-whole-archive
    # Absolute paths to transitive dependencies (some shared, some static)
    "/home/developer/.conan/data/folly/2024.08.12.00/milvus/dev/package/e3fd189726e0f3bd3ab983e3ed61fabb6704f55d/lib/libfolly.so"
    "/home/developer/.conan/data/glog/0.7.1/_/_/package/14ede1c322bb898ec4dd6a3fe972378a714b1175/lib/libglog.so"
    "/home/developer/.conan/data/gflags/2.2.2/_/_/package/770f4a67f1d0d4ce88a0455242306703d33ab8af/lib/libgflags_nothreads.so"
    "/home/developer/.conan/data/fmt/11.0.2/_/_/package/509f2206b09761325d49d488cb0b77c05da8f886/lib/libfmt.a"
    "/home/developer/.conan/data/openssl/3.3.2/_/_/package/f27548f7f380afa49bc7fdfc7a4e58a3ba495b5a/lib/libcrypto.so.3"
    "/home/developer/.conan/data/openssl/3.3.2/_/_/package/f27548f7f380afa49bc7fdfc7a4e58a3ba495b5a/lib/libssl.so.3"
    "/usr/lib/x86_64-linux-gnu/libaio.so"
)

# Set RPATH to ensure shared dependencies can be found in the same directory (as a fallback)
set_target_properties(${TARGET_LIB_KNOWHERE} PROPERTIES 
    INSTALL_RPATH "$ORIGIN"
    BUILD_WITH_INSTALL_RPATH TRUE
)

target_include_directories(${TARGET_LIB_KNOWHERE} PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}/include 
    $ENV{JAVA_HOME}/include 
    $ENV{JAVA_HOME}/include/${JVM_OS_TYPE} 
    ${KNOWHERE_INCLUDE_DIR}
    ${KNOWHERE_INCLUDE_DIR}/knowhere
    ${KNOWHERE_INCLUDE_DIR}/milvus-common
    "/home/developer/.conan/data/nlohmann_json/3.11.3/_/_/package/5ab84d6acfe1f23c4fae0ab88f26e3a396351ac9/include"
    "/home/developer/.conan/data/fmt/11.0.2/_/_/package/509f2206b09761325d49d488cb0b77c05da8f886/include"
    "/home/developer/.conan/data/glog/0.7.1/_/_/package/14ede1c322bb898ec4dd6a3fe972378a714b1175/include"
    "/home/developer/.conan/data/folly/2024.08.12.00/milvus/dev/package/e3fd189726e0f3bd3ab983e3ed61fabb6704f55d/include"
    "/home/developer/.conan/data/boost/1.83.0/_/_/package/b9caf221c687b28d51f5bc60176dc7254cd2d304/include"
)

opensearch_set_common_properties(${TARGET_LIB_KNOWHERE})
target_compile_options(${TARGET_LIB_KNOWHERE} PRIVATE -fpermissive)
list(APPEND TARGET_LIBS ${TARGET_LIB_KNOWHERE})
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------

# ----------------------------- SIMD Computing ------------------------------
# Init SIMD computing properties
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/init-simd.cmake)

# Set target library + source
set(SIMD_COMPUTING_SRCS
    ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_SimdVectorComputeService.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/src/simd/similarity_function/similarity_function.cpp
)

# Make it a shared library
add_library(${TARGET_LIB_SIMD} SHARED ${SIMD_COMPUTING_SRCS})

# Set SIMD compile option
target_compile_options(${TARGET_LIB_SIMD} PRIVATE ${FP16_SIMD_FLAGS} ${SIMD_FLAGS})

# Link util library
target_link_libraries(${TARGET_LIB_SIMD} ${TARGET_LINK_FAISS_LIB} ${TARGET_LIB_UTIL})

# Add include headers
target_include_directories(${TARGET_LIB_SIMD} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/include
    $ENV{JAVA_HOME}/include
    $ENV{JAVA_HOME}/include/${JVM_OS_TYPE}
    ${CMAKE_CURRENT_SOURCE_DIR}/external/faiss
)

# Set common properties
opensearch_set_common_properties(${TARGET_LIB_SIMD})

# Set the output name to embed the SIMD extension (if any)
set_target_properties(${TARGET_LIB_SIMD} PROPERTIES
    OUTPUT_NAME "${TARGET_LIB_SIMD}"
)

# Add target SIMD library to TARGET_LIBS list
list(APPEND TARGET_LIBS ${TARGET_LIB_SIMD})
# ---------------------------------------------------------------------------



# --------------------------------- TESTS -----------------------------------
# Windows : Comment the TESTS for now because the tests are failing(failing to build jni_tests.exe) if we are building our target libraries as SHARED libraries.
# TODO: Fix the failing JNI TESTS on Windows
if (TRUE) # Force enable for verification
    if (TRUE)
        # Reference - https://crascit.com/2015/07/25/cmake-gtest/
        configure_file(CMakeLists.txt.in googletest-download/CMakeLists.txt)
        execute_process(COMMAND "${CMAKE_COMMAND}" -G "${CMAKE_GENERATOR}" .
                WORKING_DIRECTORY "${CMAKE_BINARY_DIR}/googletest-download"
                )
        execute_process(COMMAND "${CMAKE_COMMAND}" --build .
                WORKING_DIRECTORY "${CMAKE_BINARY_DIR}/googletest-download"
                )
        set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)

        add_subdirectory("${CMAKE_BINARY_DIR}/googletest-src"
                "${CMAKE_BINARY_DIR}/googletest-build" EXCLUDE_FROM_ALL
                )
        add_executable(
                jni_test
                tests/faiss_wrapper_test.cpp
                tests/faiss_wrapper_unit_test.cpp
                tests/faiss_util_test.cpp
                tests/nmslib_wrapper_test.cpp
                tests/nmslib_wrapper_unit_test.cpp
                tests/knowhere_wrapper_test.cpp
                tests/test_util.cpp
                tests/commons_test.cpp
                tests/faiss_stream_support_test.cpp
                tests/faiss_index_service_test.cpp
                tests/nmslib_stream_support_test.cpp
                tests/faiss_index_bq_unit_test.cpp
        )

        target_link_libraries(
                jni_test
                gtest_main
                gmock_main
                faiss
                NonMetricSpaceLib
                OpenMP::OpenMP_CXX
                ${TARGET_LIB_FAISS}
                ${TARGET_LIB_NMSLIB}
                ${TARGET_LIB_COMMON}
                ${TARGET_LIB_UTIL}
                # Knowhere dependencies
                knowhere
                common
                fmt
                folly
                glog
                gflags_nothreads
                crypto
                ssl
        )

        target_include_directories(jni_test PRIVATE
                ${CMAKE_CURRENT_SOURCE_DIR}/tests
                ${CMAKE_CURRENT_SOURCE_DIR}/include
                $ENV{JAVA_HOME}/include
                $ENV{JAVA_HOME}/include/${JVM_OS_TYPE}
                ${CMAKE_CURRENT_SOURCE_DIR}/external/faiss
                ${CMAKE_CURRENT_SOURCE_DIR}/external/nmslib/similarity_search/include
                ${CMAKE_CURRENT_SOURCE_DIR}/external/knowhere/include
                ${gtest_SOURCE_DIR}/include
                ${gmock_SOURCE_DIR}/include)


        set_target_properties(jni_test PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/bin)
    endif ()
endif()

# ---------------------------------------------------------------------------
