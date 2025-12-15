#
# Copyright 2024 The Android Open Source Project
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

cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

# Enable C++20 features for the iamf_tools library.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Define project name for your JNI module
project(libiamfJNI C CXX)

set(iamf_jni_root "${CMAKE_CURRENT_SOURCE_DIR}")

# Path to the compiled static iamf_tools lib.
# The build script places the static lib here.
set(IAMF_TOOLS_LIB_PATH
    "${iamf_jni_root}/nativelib/${ANDROID_ABI}/libiamf_decoder_static.a")

# Add include directory for iamf_tools.
include_directories("${iamf_jni_root}/iamf_tools/iamf/include")

# Build libiamfJNI.
add_library(iamfJNI
            SHARED
            iamf_jni.cc)

# Locate NDK log library.
find_library(android_log_lib log)

# Link libiamfJNI against used libraries.
target_link_libraries(iamfJNI
                      PRIVATE android
                      PRIVATE ${IAMF_TOOLS_LIB_PATH}
                      PRIVATE ${android_log_lib})

target_link_options(iamfJNI
                    PRIVATE "-Wl,-z,max-page-size=16384")
