#
# Copyright 2026 The Android Open Source Project
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

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

project(hardwareBufferJNI C CXX)

# Add the NDK libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

add_library(${PROJECT_NAME} SHARED hardware_buffer_jni.cc)

target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(${PROJECT_NAME}
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
)
