#
# Copyright 2025 The Android Open Source Project
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

# Enable C++17 features.
set(CMAKE_CXX_STANDARD 17)

# Define project name for your JNI module
project(libassJNI C CXX)

set(libass_location "${CMAKE_CURRENT_SOURCE_DIR}/ass/${ANDROID_ABI}")
set(libass_binaries "${libass_location}/usr/local/lib")

foreach(libass_lib fribidi harfbuzz freetype expat fontconfig ass)
    set(libass_lib_filename lib${libass_lib}.a)
    set(libass_lib_file_path ${libass_binaries}/${libass_lib_filename})

    add_library(
            ${libass_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${libass_lib} PROPERTIES
            IMPORTED_LOCATION
            ${libass_lib_file_path})
endforeach()

include_directories("${libass_location}/usr/local/include")

add_library(assJNI
        SHARED
        ass_jni.cpp)

target_link_libraries(assJNI
        android
        log
        jnigraphics
        ass
        fribidi
        harfbuzz
        freetype
        fontconfig
        expat)