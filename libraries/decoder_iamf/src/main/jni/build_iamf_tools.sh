#!/bin/bash
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

# Builds the iamf_tools native library for all supported architectures.
# Usage
# ./build_iamf.sh ${IAMF_MODULE_PATH}

set -eu

IAMF_MODULE_PATH="$1"
echo "IAMF_MODULE_PATH is ${IAMF_MODULE_PATH}"

rm -rf "${IAMF_MODULE_PATH}/jni/nativelib"
echo "Cleaned old pre-built libraries."

# We need to run the bazel command from within the workspace to get the correct
# settings in files such as iamf_tools/.bazelrc.
cd ${IAMF_MODULE_PATH}/jni/iamf_tools

if ! command -v bazelisk > /dev/null 2 >&1
then
    echo "bazelisk required but not found.  Install and try again."
    exit 1
fi

declare -A CONFIG_TO_ABI_MAP
CONFIG_TO_ABI_MAP["android_armv7"]="armeabi-v7a"
CONFIG_TO_ABI_MAP["android_arm64"]="arm64-v8a"
CONFIG_TO_ABI_MAP["android_x86_32"]="x86"
CONFIG_TO_ABI_MAP["android_x86_64"]="x86_64"

for config in android_armv7 android_arm64 android_x86_32 android_x86_64; do
  bazelisk build \
    --experimental_cc_static_library \
    --copt=-femulated-tls \
    --config=$config \
    --compilation_mode=opt \
    --copt=-fvisibility=hidden \
    --copt=-fno-exceptions \
    --copt=-fno-rtti \
    --features=thin_lto \
    //iamf/include/iamf_tools:iamf_decoder_static
  ABI="${CONFIG_TO_ABI_MAP[$config]}"
  OUTPUT_DIR="${IAMF_MODULE_PATH}/jni/nativelib/${ABI}"
  mkdir -p "$OUTPUT_DIR"
  cp ./bazel-bin/iamf/include/iamf_tools/libiamf_decoder_static.a "$OUTPUT_DIR/"
  echo "Successfully built and copied libiamf_decoder_static.a to ${OUTPUT_DIR}"
done

