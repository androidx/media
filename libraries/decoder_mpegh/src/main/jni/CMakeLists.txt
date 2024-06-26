cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

project(mpeghJNI CXX)

if(${ANDROID_ABI} MATCHES "armeabi-v7a")
  add_compile_options("-mfpu=neon")
  add_compile_options("-marm")
  add_compile_options("-fPIC")
endif()

# Locate NDK log library.
find_library(android_log_lib log)

add_subdirectory(mpeghdec)
include(${CMAKE_CURRENT_SOURCE_DIR}/mpeghdec.cmake)
