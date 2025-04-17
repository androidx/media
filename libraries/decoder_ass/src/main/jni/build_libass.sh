#!/bin/bash

# If an error occurs, stop the script
set -eu

# Base directory (where the script is located)
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Build directory
mkdir -p "ass"
BUILD_DIR="$SCRIPT_DIR/ass"

# Android NDK configuration
NDK_PATH="$1"
HOST_PLATFORM="$2"
ANDROID_ABI_VERSION="$3"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM"
CROSS_FILE_PATH="$BUILD_DIR/cross-file.tmp"


# Function to check if NDK is properly set up
check_ndk_setup() {
    if [ ! -d "$NDK_PATH" ]; then
        echo "Error: Android NDK not found at $NDK_PATH"
        echo "Please verify the NDK path. Current path is set to: $NDK_PATH"
        exit 1
    fi

    if [ ! -d "$TOOLCHAIN" ]; then
        echo "Error: Toolchain not found at $TOOLCHAIN"
        echo "Please verify that the NDK installation is complete"
        exit 1
    fi
}

# Function to create a Meson cross file for cross-compilation
create_meson_cross_file() {
    local cpu_family=$CPU
    [ "$cpu_family" == "i686" ] && cpu_family=x86

    cat > "$CROSS_FILE_PATH" <<CROSSFILE
[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
prefix = '/usr/local'

[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
nm = '$NM'
strip = '$STRIP'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$CPU'
endian = 'little'
CROSSFILE
}

# Function to build HarfBuzz library
build_harfbuzz() {
    echo "Building HarfBuzz..."
    cd "$BUILD_DIR"

    if [ ! -d harfbuzz-11.0.0 ]; then
        wget -O harfbuzz-11.0.0.tar.xz https://github.com/harfbuzz/harfbuzz/releases/download/11.0.0/harfbuzz-11.0.0.tar.xz
        tar -xf harfbuzz-11.0.0.tar.xz
    fi

    cd harfbuzz-11.0.0

    meson setup build \
        --cross-file "$CROSS_FILE_PATH" \
        -Dtests=disabled \
        -Ddocs=disabled

    ninja -C build
    DESTDIR="$ABS_BUILD_PATH" ninja -C build install
    rm -rf build

    cd "$BUILD_DIR"
    rm -f harfbuzz-11.0.0.tar.xz
}

# Function to build FreeType library
build_freetype() {
    echo "Building FreeType..."
    cd "$BUILD_DIR"

    if [ ! -d freetype-2.13.3 ]; then
        wget -O freetype-2.13.3.tar.xz https://downloads.sourceforge.net/freetype/freetype-2.13.3.tar.xz
        tar -xf freetype-2.13.3.tar.xz
    fi

    cd freetype-2.13.3

    ./autogen.sh
    ./configure --host=$TARGET \
                --enable-static \
                --disable-shared \
                --with-pic \
                --with-harfbuzz=yes \
                --with-zlib=no

    make -j$(nproc)
    make DESTDIR="$ABS_BUILD_PATH" install
    make distclean

    cd "$BUILD_DIR"
    rm -f freetype-2.13.3.tar.xz
}

# Function to build FriBidi library
build_fribidi() {
    echo "Building FriBidi..."
    cd "$BUILD_DIR"

    if [ ! -d fribidi-1.0.16 ]; then
        echo "Downloading and extracting FriBidi..."
        wget -O fribidi-1.0.16.tar.xz https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz
        tar -xf fribidi-1.0.16.tar.xz
    fi

    cd fribidi-1.0.16

    meson setup build \
        --cross-file "$CROSS_FILE_PATH" \
        -Ddocs=false \
        -Dtests=false

    ninja -C build
    DESTDIR="$ABS_BUILD_PATH" ninja -C build install
    rm -rf build

    cd "$BUILD_DIR"
    rm -f fribidi-1.0.16.tar.xz
}

# Function to build Expat library
build_expat() {
    echo "Building Expat..."
    cd "$BUILD_DIR"

    if [ ! -d expat-2.7.1 ]; then
        echo "Downloading and extracting Expat..."
        wget -O expat-2.7.1.tar.xz https://github.com/libexpat/libexpat/releases/download/R_2_7_1/expat-2.7.1.tar.xz
        tar -xf expat-2.7.1.tar.xz
    fi

    cd expat-2.7.1

    ./configure --host=$TARGET \
                --enable-static \
                --disable-shared \
                --with-pic

    make -j$(nproc)
    make DESTDIR="$ABS_BUILD_PATH" install
    make distclean

    cd "$BUILD_DIR"
    rm -f expat-2.7.1.tar.xz
}

# Function to build Fontconfig library
build_fontconfig() {
    echo "Building Fontconfig..."
    cd "$BUILD_DIR"

    if [ ! -d fontconfig-2.16.0 ]; then
        echo "Downloading and extracting Fontconfig..."
        wget -O fontconfig-2.16.0.tar.xz https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.16.0.tar.xz
        tar -xf fontconfig-2.16.0.tar.xz
    fi

    cd fontconfig-2.16.0

    meson setup build \
        --cross-file "$CROSS_FILE_PATH" \
        -Dtests=disabled \
        -Ddoc=disabled \
        -Dtools=disabled \
        -Dxml-backend=expat

    ninja -C build
    DESTDIR="$ABS_BUILD_PATH" ninja -C build install
    rm -rf build

    cd "$BUILD_DIR"
    rm -f fontconfig-2.16.0.tar.xz
}

# Function to build libass library
build_libass() {
    echo "Building libass..."
    cd "$BUILD_DIR"

    if [ ! -d libass-0.17.3 ]; then
        echo "Downloading and extracting libass..."
        wget -O libass-0.17.3.tar.xz https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.xz
        tar -xf libass-0.17.3.tar.xz
    fi

    cd libass-0.17.3

    ./configure --host=$TARGET \
                --enable-static \
                --disable-shared \
                --with-pic \
                --enable-fontconfig

    make -j$(nproc)
    make DESTDIR="$ABS_BUILD_PATH" install
    make distclean

    cd "$BUILD_DIR"
    rm -f libass-0.17.3.tar.xz
}

# Function to build libass and its dependencies
build_libass_and_dependencies() {
    echo "Building for $TARGET"
    # Set up cross-compilation environment
    export CC=$TOOLCHAIN/bin/$TARGET$ANDROID_ABI_VERSION-clang
    export CXX=$TOOLCHAIN/bin/$TARGET$ANDROID_ABI_VERSION-clang++
    export AR=$TOOLCHAIN/bin/llvm-ar
    export NM=$TOOLCHAIN/bin/llvm-nm
    export STRIP=$TOOLCHAIN/bin/llvm-strip
    export RANLIB=$TOOLCHAIN/bin/llvm-ranlib

    CPU=${TARGET%%-*}

    local android_abi=$CPU
    [ "$android_abi" == "i686" ] && android_abi=x86
    [ "$android_abi" == "armv7a" ] && android_abi=armeabi-v7a
    [ "$android_abi" == "aarch64" ] && android_abi=arm64-v8a

    # Configure libass/dependencies build path
    local build_path="$BUILD_DIR/$android_abi"
    ABS_BUILD_PATH=$(realpath "$build_path")

    # PKG_CONFIG configuration
    export PKG_CONFIG_SYSROOT_DIR="$ABS_BUILD_PATH"
    export PKG_CONFIG_LIBDIR="$ABS_BUILD_PATH/usr/local/lib/pkgconfig"

    create_meson_cross_file

    # Build libass dependencies
    echo "--------------------------------------------------------------"
    build_harfbuzz
    echo "--------------------------------------------------------------"
    build_freetype
    echo "--------------------------------------------------------------"
    build_fribidi
    echo "--------------------------------------------------------------"
    build_expat
    echo "--------------------------------------------------------------"
    build_fontconfig

    # Build libass
    echo "--------------------------------------------------------------"
    build_libass
    echo "--------------------------------------------------------------"

    rm "$CROSS_FILE_PATH"
}

# Main function to orchestrate the build process
main () {
    # check_directory_structure
    check_ndk_setup
    echo "===================="

    TARGET=x86_64-linux-android
    build_libass_and_dependencies

    TARGET=i686-linux-android
    build_libass_and_dependencies

    TARGET=armv7a-linux-androideabi
    build_libass_and_dependencies

    TARGET=aarch64-linux-android
    build_libass_and_dependencies
}

main