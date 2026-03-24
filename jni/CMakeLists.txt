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
target_include_directories(${TARGET_LIB_UTIL} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/external/knowhere/include $ENV{JAVA_HOME}/include $ENV{JAVA_HOME}/include/${JVM_OS_TYPE})
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

set(CONAN_CACHE_HINTS
    "$ENV{CONAN_USER_HOME}/.conan/data"
    "$ENV{HOME}/.conan/data"
    "/root/.conan/data"
    "/home/developer/.conan/data"
)

function(find_conan_path OUT_VAR PACKAGE RELATIVE_GLOB)
    set(_matches "")
    foreach(_cache_dir IN LISTS CONAN_CACHE_HINTS)
        if(_cache_dir)
            file(GLOB _pkg_matches "${_cache_dir}/${PACKAGE}/*/*/*/package/*/${RELATIVE_GLOB}")
            list(APPEND _matches ${_pkg_matches})
        endif()
    endforeach()

    list(LENGTH _matches _match_count)
    if(_match_count EQUAL 0)
        message(FATAL_ERROR "Unable to find Conan package path for ${PACKAGE}/${RELATIVE_GLOB}. Set CONAN_USER_HOME or install the dependency.")
    endif()

    list(GET _matches 0 _selected)
    set(${OUT_VAR} "${_selected}" PARENT_SCOPE)
endfunction()

function(find_conan_optional_path OUT_VAR PACKAGE RELATIVE_GLOB)
    set(_matches "")
    foreach(_cache_dir IN LISTS CONAN_CACHE_HINTS)
        if(_cache_dir)
            file(GLOB _pkg_matches "${_cache_dir}/${PACKAGE}/*/*/*/package/*/${RELATIVE_GLOB}")
            list(APPEND _matches ${_pkg_matches})
        endif()
    endforeach()

    list(LENGTH _matches _match_count)
    if(_match_count GREATER 0)
        list(GET _matches 0 _selected)
        set(${OUT_VAR} "${_selected}" PARENT_SCOPE)
    else()
        set(${OUT_VAR} "" PARENT_SCOPE)
    endif()
endfunction()

function(find_conan_library_prefer_static OUT_VAR PACKAGE STATIC_GLOB DYNAMIC_GLOB)
    find_conan_optional_path(_static_lib ${PACKAGE} "${STATIC_GLOB}")
    if(_static_lib)
        set(${OUT_VAR} "${_static_lib}" PARENT_SCOPE)
    else()
        find_conan_path(${OUT_VAR} ${PACKAGE} "${DYNAMIC_GLOB}")
    endif()
endfunction()
find_conan_library_prefer_static(FOLLY_LIB folly "lib/libfolly.a" "lib/libfolly.so*")
find_conan_library_prefer_static(GLOG_LIB glog "lib/libglog.a" "lib/libglog.so*")
find_conan_library_prefer_static(GFLAGS_LIB gflags "lib/libgflags_nothreads.a" "lib/libgflags_nothreads.so*")
find_conan_optional_path(FMT_LIB fmt "lib/libfmt.a")
find_conan_library_prefer_static(OPENSSL_CRYPTO_LIB openssl "lib/libcrypto.a" "lib/libcrypto.so*")
find_conan_library_prefer_static(OPENSSL_SSL_LIB openssl "lib/libssl.a" "lib/libssl.so*")

function(append_conan_package_libs OUT_VAR PACKAGE)
    find_conan_optional_path(_lib_dir ${PACKAGE} "lib")
    if(_lib_dir)
        file(GLOB _static_libs "${_lib_dir}/*.a")
        file(GLOB _dynamic_libs "${_lib_dir}/*.so" "${_lib_dir}/*.so.*" "${_lib_dir}/*.dylib")
        list(SORT _static_libs)
        list(SORT _dynamic_libs)
        set(_all_libs ${_static_libs})
        list(APPEND _all_libs ${_dynamic_libs})
        set(_current ${${OUT_VAR}})
        list(APPEND _current ${_all_libs})
        list(REMOVE_DUPLICATES _current)
        set(${OUT_VAR} ${_current} PARENT_SCOPE)
    endif()
endfunction()

find_conan_path(NLOHMANN_JSON_INCLUDE nlohmann_json "include")
find_conan_path(FMT_INCLUDE fmt "include")
find_conan_path(GLOG_INCLUDE glog "include")
find_conan_path(GFLAGS_INCLUDE gflags "include")
find_conan_path(FOLLY_INCLUDE folly "include")
find_conan_path(BOOST_INCLUDE boost "include")
find_conan_optional_path(OPENSSL_INCLUDE openssl "include")
find_library(AIO_LIB aio REQUIRED)
find_package(OpenMP REQUIRED)

set(KNOWHERE_THIRDPARTY_LIBS "")
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS prometheus-cpp)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS opentelemetry-cpp)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS opentelemetry-proto)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS grpc)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS protobuf)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS double-conversion)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS libevent)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS libcurl)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS thrift)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS c-ares)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS re2)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS zlib)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS abseil)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS snappy)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS bzip2)
append_conan_package_libs(KNOWHERE_THIRDPARTY_LIBS liblzma)

add_library(${TARGET_LIB_KNOWHERE} SHARED 
    ${CMAKE_CURRENT_SOURCE_DIR}/src/org_opensearch_knn_jni_KnowhereService.cpp 
    ${CMAKE_CURRENT_SOURCE_DIR}/src/knowhere_wrapper.cpp
)

target_link_libraries(${TARGET_LIB_KNOWHERE}
    ${TARGET_LIB_UTIL}
    OpenMP::OpenMP_CXX
    -Wl,--start-group
    -Wl,--whole-archive
    ${KNOWHERE_LIB_DIR}/libknowhere.a
    ${KNOWHERE_LIB_DIR}/libknowhere_utils.a
    ${KNOWHERE_LIB_DIR}/libdiskann.a
    -Wl,--no-whole-archive
    ${KNOWHERE_LIB_DIR}/libfaiss.a
    ${FOLLY_LIB}
    ${GLOG_LIB}
    ${GFLAGS_LIB}
    ${FMT_LIB}
    ${OPENSSL_CRYPTO_LIB}
    ${OPENSSL_SSL_LIB}
    ${KNOWHERE_THIRDPARTY_LIBS}
    -Wl,--end-group
    ${AIO_LIB}
    ${CMAKE_DL_LIBS}
    pthread
    rt
    m
    atomic
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
    ${NLOHMANN_JSON_INCLUDE}
    ${FMT_INCLUDE}
    ${GLOG_INCLUDE}
    ${GFLAGS_INCLUDE}
    ${FOLLY_INCLUDE}
    ${BOOST_INCLUDE}
    ${OPENSSL_INCLUDE}
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
if (${CONFIG_TEST} STREQUAL ON OR ${CONFIG_ALL} STREQUAL ON)
    if (NOT WIN32)
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
