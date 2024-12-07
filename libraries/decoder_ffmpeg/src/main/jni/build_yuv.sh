#!/bin/bash
#
# Copyright (C) 2019 The Android Open Source Project
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
set -eu

FFMPEG_MODULE_PATH="$1"
echo "FFMPEG_MODULE_PATH is ${FFMPEG_MODULE_PATH}"
NDK_PATH="$2"
echo "NDK path is ${NDK_PATH}"
ANDROID_ABI="$3"
echo "ANDROID_ABI is ${ANDROID_ABI}"

ABI_LIST="armeabi-v7a arm64-v8a x86 x86_64"
echo "ABI List is ${ABI_LIST}"

ANDROID_ABI_64BIT="$ANDROID_ABI"
if [[ "$ANDROID_ABI_64BIT" -lt 21 ]]
then
    echo "Using ANDROID_ABI 21 for 64-bit architectures"
    ANDROID_ABI_64BIT=21
fi

cd "${FFMPEG_MODULE_PATH}/jni/libyuv"

for abi in ${ABI_LIST}; do
  rm -rf "build-${abi}"
  mkdir "build-${abi}"
  cd "build-${abi}"

  cmake .. \
    -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE=$NDK_PATH/build/cmake/android.toolchain.cmake -DANDROID_ABI=${abi} -DCMAKE_ANDROID_ARCH_ABI=${abi} \
    -DANDROID_NDK=${NDK_PATH} \
    -DANDROID_PLATFORM=${ANDROID_ABI} \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_SYSTEM_NAME=Generic \
    -DCMAKE_ANDROID_STL_TYPE=c++_shared \
    -DCMAKE_SYSTEM_NAME=Android \
    -DCMAKE_THREAD_PREFER_PTHREAD=TRUE \
    -DTHREADS_PREFER_PTHREAD_FLAG=TRUE \
    -DBUILD_STATIC_LIBS=OFF

  cmake --build .
  cd ..
done

for abi in ${ABI_LIST}; do
  mkdir -p "./android-libs/${abi}"
  cp -r "build-${abi}/libyuv.so" "./android-libs/${abi}/libyuv.so"
  echo "build-${abi}/libyuv.so was successfully copied to ./android-libs/${abi}/libyuv.so!"
done
