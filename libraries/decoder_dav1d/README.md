Note: This dav1d extension module is currently in a temporary location. It is
planned to replace the existing AV1 extension (decoder-av1) in the near future.

# dav1d decoder module

The dav1d module provides `Libdav1dVideoRenderer`, which uses dav1d native
library to decode AV1 videos.

## License note

Please note that whilst the code in this repository is licensed under
[Apache 2.0][], using this module also requires building and including one or
more external libraries as described below. These are licensed separately.

[Apache 2.0]: ../../LICENSE

## Build instructions (Linux, macOS)

To use the module you need to clone this GitHub project and depend on its
modules locally. Instructions for doing this can be found in the
[top level README][].

In addition, it's necessary to fetch `cpu_features` library and manually build
the `dav1d` library, so that gradle can bundle the `dav1d` binaries in the APK:

*   Set the following environment variables:

```
cd "<path to project checkout>"
DAV1D_MODULE_PATH="$(pwd)/libraries/decoder_dav1d/src/main"
```

*   Fetch `cpu_features` library:

```
cd "${DAV1D_MODULE_PATH}/jni" && \
git clone https://github.com/google/cpu_features
```

*   Install [Meson][] (0.49 or higher), [Ninja][], and, for x86* targets,
    [nasm][] (2.14 or higher)

*   Fetch `dav1d` library and enter it:

```
cd "${DAV1D_MODULE_PATH}/jni" && \
git clone https://code.videolan.org/videolan/dav1d.git && \
cd dav1d
```

*   Set path to your cross-compilation architecture file from the
    `package/crossfiles` directory:

```
CROSS_FILE_PATH="$(pwd)/package/crossfiles/<file name>
```

Note: Binary name formats may differ between distributions. Verify the names,
and use alias if certain binaries cannot be found.

*   Create and enter the build directory:

```
mkdir build && \
cd build
```

*   Compile the library:

```
meson setup .. --cross-file="${CROSS_FILE_PATH}" --default-library=static && \
ninja
```

*   The resulting `.a` static library will be located inside `build/src`

[top level README]: ../../README.md
[Meson]: https://mesonbuild.com/
[Ninja]: https://ninja-build.org/
[nasm]: https://nasm.us/

## Build instructions (Windows)

We do not provide support for building this module on Windows, however it should
be possible to follow the Linux instructions in [Windows PowerShell][].

[Windows PowerShell]: https://docs.microsoft.com/en-us/powershell/scripting/getting-started/getting-started-with-windows-powershell

## Using the module with ExoPlayer

Once you've followed the instructions above to check out, build and depend on
the module, the next step is to tell ExoPlayer to use `Libdav1dVideoRenderer`.
How you do this depends on which player API you're using:

*   If you're passing a `DefaultRenderersFactory` to `ExoPlayer.Builder`, you
    can enable using the module by setting the `extensionRendererMode` parameter
    of the `DefaultRenderersFactory` constructor to
    `EXTENSION_RENDERER_MODE_ON`. This will use `Libdav1dVideoRenderer` for
    playback if `MediaCodecVideoRenderer` doesn't support decoding the input AV1
    stream. Pass `EXTENSION_RENDERER_MODE_PREFER` to give
    `Libdav1dVideoRenderer` priority over `MediaCodecVideoRenderer`.
*   If you've subclassed `DefaultRenderersFactory`, add a
    `Libdav1dVideoRenderer` to the output list in `buildVideoRenderers`.
    ExoPlayer will use the first `Renderer` in the list that supports the input
    media format.
*   If you've implemented your own `RenderersFactory`, return a
    `Libdav1dVideoRenderer` instance from `createRenderers`. ExoPlayer will use
    the first `Renderer` in the returned array that supports the input media
    format.
*   If you're using `ExoPlayer.Builder`, pass a `Libdav1dVideoRenderer` in the
    array of `Renderer`s. ExoPlayer will use the first `Renderer` in the list
    that supports the input media format.

Note: These instructions assume you're using `DefaultTrackSelector`. If you have
a custom track selector the choice of `Renderer` is up to your implementation.
You need to make sure you are passing a `Libdav1dVideoRenderer` to the player
and then you need to implement your own logic to use the renderer for a given
track.

## Links

*   [Troubleshooting using decoding extensions][]

[Troubleshooting using decoding extensions]: https://developer.android.com/media/media3/exoplayer/troubleshooting#how-can-i-get-a-decoding-library-to-load-and-be-used-for-playback
