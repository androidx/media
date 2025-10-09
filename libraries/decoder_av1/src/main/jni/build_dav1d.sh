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
# Builds the dav1d native library for all supported architectures.

set -eu

AV1_MODULE_PATH="$1"
echo "AV1_MODULE_PATH is ${AV1_MODULE_PATH}"
NDK_PATH="$2"
echo "NDK_PATH is ${NDK_PATH}"
HOST_PLATFORM="$3"
echo "Host platform is ${HOST_PLATFORM}"

DAV1D_SOURCE_PATH="${AV1_MODULE_PATH}/jni/dav1d"
if [[ ! -d "${DAV1D_SOURCE_PATH}" ]]
then
    echo "dav1d source directory not found at ${DAV1D_SOURCE_PATH}"
    exit 1
fi
echo "DAV1D_SOURCE_PATH is ${DAV1D_SOURCE_PATH}"

CROSS_FILES_PATH="${DAV1D_SOURCE_PATH}/package/crossfiles"
echo "CROSS_FILES_PATH is ${CROSS_FILES_PATH}"

TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
if [[ ! -d "${TOOLCHAIN_PREFIX}" ]]
then
    echo "Please set correct NDK_PATH, $NDK_PATH is incorrect"
    exit 1
fi

rm -rf "${AV1_MODULE_PATH}/jni/nativelib"
echo "Cleaned old pre-built libraries."

# Map Android ABIs to their NDK toolchain prefix.
declare -A NDK_TARGET_MAP
NDK_TARGET_MAP["arm64-v8a"]="aarch64-linux-android21"
NDK_TARGET_MAP["armeabi-v7a"]="armv7a-linux-androideabi21"
NDK_TARGET_MAP["x86_64"]="x86_64-linux-android21"
NDK_TARGET_MAP["x86"]="i686-linux-android21"

# Map meson cross-file names to Android ABIs.
declare -A ABI_MAP
ABI_MAP["arm64-v8a"]="aarch64-android"
ABI_MAP["armeabi-v7a"]="arm-android"
ABI_MAP["x86_64"]="x86_64-android"
ABI_MAP["x86"]="x86-android"

# Change into the dav1d source directory to run the build commands.
cd "${DAV1D_SOURCE_PATH}"

# Create a temporary directory to store all build artifacts.
BUILD_ROOT=$(mktemp -d)
trap 'rm -rf "${BUILD_ROOT}"' EXIT
echo "Created temporary build root: ${BUILD_ROOT}"


for android_abi in "${!ABI_MAP[@]}"; do
    ndk_target=${NDK_TARGET_MAP[$android_abi]}
    original_cross_file="${ABI_MAP[$android_abi]}.meson"

    echo "Building dav1d for ${android_abi}..."
    # Create a dedicated build directory for this ABI.
    ABI_BUILD_DIR="${BUILD_ROOT}/${android_abi}"
    mkdir -p "${ABI_BUILD_DIR}"

    # Find and replace the binaries in a temporary cross-file.
    TEMP_CROSS_FILE="${ABI_BUILD_DIR}/temp-android-cross-file.meson"

    # Copy the original file to our temporary location.
    cp "$CROSS_FILES_PATH/${original_cross_file}" "${TEMP_CROSS_FILE}"

    # Use sed to find each line in the [binaries] section and replace its value.
    sed -i.bak \
      -e "s|c = .*|c = '${TOOLCHAIN_PREFIX}/${ndk_target}-clang'|g" \
      -e "s|cpp = .*|cpp = '${TOOLCHAIN_PREFIX}/${ndk_target}-clang++'|g" \
      -e "s|ar = .*|ar = '${TOOLCHAIN_PREFIX}/llvm-ar'|g" \
      -e "s|strip = .*|strip = '${TOOLCHAIN_PREFIX}/llvm-strip'|g" \
      "${TEMP_CROSS_FILE}"
    rm "${TEMP_CROSS_FILE}.bak"

    meson setup "${ABI_BUILD_DIR}" --cross-file="${TEMP_CROSS_FILE}" --default-library=static
    ninja -C "${ABI_BUILD_DIR}"

    OUTPUT_DIR="${AV1_MODULE_PATH}/jni/nativelib/${android_abi}"
    mkdir -p "$OUTPUT_DIR"
    cp "${ABI_BUILD_DIR}/src/libdav1d.a" "$OUTPUT_DIR/"
    echo "Successfully built and copied libdav1d.a to ${OUTPUT_DIR}"
done

echo "dav1d build finished for all architectures."
