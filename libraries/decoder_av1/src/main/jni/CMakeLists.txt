#
# Copyright 2021 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# LINT.IfChange
cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)
# LINT.ThenChange(../../../build.gradle)

set(CMAKE_CXX_STANDARD 11)

project(libdav1dJNI C CXX)

# Devices using armeabi-v7a are not required to support
# Neon which is why Neon is disabled by default for
# armeabi-v7a build. This flag enables it.
if(${ANDROID_ABI} MATCHES "armeabi-v7a")
    add_compile_options("-mfpu=neon")
    add_compile_options("-marm")
    add_compile_options("-fPIC")
endif()

string(TOLOWER "${CMAKE_BUILD_TYPE}" build_type)
if(build_type MATCHES "^rel")
    add_compile_options("-O2")
endif()

set(libdav1d_jni_root "${CMAKE_CURRENT_SOURCE_DIR}")

# Build cpu_features library.
add_subdirectory("${libdav1d_jni_root}/cpu_features"
                 EXCLUDE_FROM_ALL)

# Path to compiled static libdav1d.
set(DAV1D_LIB_PATH "${libdav1d_jni_root}/nativelib/${ANDROID_ABI}/libdav1d.a")

# Add the include directory from dav1d.
include_directories ("${libdav1d_jni_root}/dav1d/include")

# Build libdav1dJNI.
add_library(dav1dJNI
            SHARED
            dav1d_jni.cc
            cpu_info.cc
            cpu_info.h)

# Locate NDK log library.
find_library(android_log_lib log)

# Link libdav1dJNI against used libraries.
target_link_libraries(dav1dJNI
                      PRIVATE android
                      PRIVATE cpu_features
                      PRIVATE ${DAV1D_LIB_PATH}
                      PRIVATE ${android_log_lib})

# Enable 16 KB ELF alignment.
target_link_options(dav1dJNI
                    PRIVATE "-Wl,-z,max-page-size=16384")
