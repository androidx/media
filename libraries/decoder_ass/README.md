# ASS decoder module

The ASS module provides `AssRenderer`, which uses libass for decoding
and can render ssa/ass subtitle.

## License note

Please note that whilst the code in this repository is licensed under
[Apache 2.0][], using this module also requires building and including one or
more external libraries as described below. These are licensed separately.

[Apache 2.0]: ../../LICENSE

## Build instructions

### Prerequisites

Before running the build script for libass, you will need the following dependencies installed on
your system. You may use whatever package manager to install these:

* build-essential (or equivalent build tools on non-Debian systems)
* pkg-config
* autoconf
* automake
* libtool
* wget
* gperf
* meson and its dependencies (ninja-build, python3, etc.)

### Building on Linux or macOS

1. In a terminal, navigate to this current directory, to make sure the build of libass is done in
   the correct directory, as it will create an `ass` directory directly inside `jni`:
   ```bash
   cd /path/to/media/libraries/decoder_ass/src/main/jni
   ```
2. Locate the path of your Android NDK. You can manually download it from the
   official [Android NDK website](https://developer.android.com/ndk/downloads):
   ```bash
   NDK_PATH="<path to Android NDK>"
   ```
3. Set the host platform:
    * For Linux:
   ```bash
   HOST_PLATFORM="linux-x86_64"
   ```
    * For macOS:
   ```bash
   HOST_PLATFORM="darwin-x86_64"
   ```
4. Set the ABI version for native code (typically equal to your minSdk, and must not exceed it):
   ```bash
   ANDROID_ABI_VERSION=21
   ```
5. Execute `build_libass.sh` to build libass for all architectures:
   ```bash
   ./build_libass.sh "${NDK_PATH}" "${HOST_PLATFORM}" "${ANDROID_ABI_VERSION}"
   ```

Be aware that you can always edit the script to only build for a specific architecture. The build
process may take some time minutes depending on your system. When complete, the built libraries will
be available in `ass/<architecture>/usr/local/lib/ directories`.

## Build instructions (Windows)

We do not provide official support for building this module directly on Windows. However, it is
possible to build using Windows Subsystem for Linux
[WSL2](https://learn.microsoft.com/en-us/windows/wsl/install) with the appropriate tools installed:

1. Install WSL2 and a Linux distribution (like Ubuntu) from the Microsoft Store
2. Download the [Android NDK](https://developer.android.com/ndk/downloads) for Linux within your
   WSL2 environment
3. Follow the Linux build instructions above, setting the NDK path to its location in your WSL2
   filesystem

For example, if you downloaded and extracted the NDK to your home directory in WSL2, the command
might look like:

```bash
./build_libass.sh ~/android-ndk-r27c linux-x86_64 21
```

## Note about the build script

The following script, `build_libass.sh`, as the name suggests, builds libass and its dependencies
for Android platforms. It automates the process of cross-compiling libass and all its dependencies
for Android. This enables Android applications to render complex subtitle formats with advanced
styling and positioning.

The script builds the following libraries in sequence:

1. [HarfBuzz (v11.0.0)](https://github.com/harfbuzz/harfbuzz) - An OpenType text shaping engine
2. [FreeType (v2.13.3)](https://freetype.org/) - A font rendering library
3. [FriBidi (v1.0.16)](https://github.com/fribidi/fribidi/) - A library implementing the Unicode
   Bidirectional Algorithm
4. [UniBreak (v6.1)](https://github.com/adah1972/libunibreak/) - A line breaking library
   implementing the Unicode Line Breaking Algorithm
5. [Expat (v2.7.1)](https://github.com/libexpat/libexpat) - An XML parser library
6. [Fontconfig (v2.16.0)](https://gitlab.freedesktop.org/fontconfig/fontconfig) - A library for font
   customization and configuration
7. [libass (v0.17.3)](https://github.com/libass/libass) - The subtitle rendering library

All libraries are built as static libraries for four Android architectures:

* x86_64
* x86 (i686)
* armeabi-v7a (armv7a)
* arm64-v8a (aarch64)


## Troubleshooting

If you encounter issues during the build process:

1. Ensure all prerequisites are properly installed
2. Verify your NDK path is correct and the NDK version is compatible
3. Check that the HOST_PLATFORM matches your system
4. Make sure ANDROID_ABI_VERSION is appropriate for your project

Common errors:

* `meson: command not found` - Install meson using pip:
   ```bash
  pip3 install meson
  ```
* NDK-related errors - Double-check your NDK path and ensure it's a complete installation
* Library download failures - Verify your internet connection and try again

For issues with specific dependencies, individual build functions in the script can be modified as
needed.

## Using the Built Libraries

After a successful build, the compiled libraries and headers can be used in your Android project.
They will be located in:

* Libraries: ass/<architecture>/usr/local/lib/
* Headers: ass/<architecture>/usr/local/include/

These files are already be referenced in your project's CMakeLists.txt file to link against the
static libraries.