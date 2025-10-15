# AV1 decoder module

The AV1 module provides `Libdav1dVideoRenderer`, which uses dav1d native library
to decode AV1 videos.

## License note

Please note that whilst the code in this repository is licensed under
[Apache 2.0][], using this module also requires building and including one or
more external libraries as described below. These are licensed separately.

[Apache 2.0]: ../../LICENSE

## Build instructions (Linux, macOS)

To use the module you need to clone this GitHub project and depend on its
modules locally. Instructions for doing this can be found in the
[top level README][].

In addition, it's necessary to fetch `cpu_features` library and `dav1d` with its
dependencies as follows:

*   Set the following environment variables:

```
cd "<path to project checkout>"
AV1_MODULE_PATH="$(pwd)/libraries/decoder_av1/src/main"
```

*   Download the [Android NDK][] and set its location in a shell variable. This
    build configuration has been tested with NDK r27.

```
NDK_PATH="<path to Android NDK>"
```

*   Set the host platform (e.g., "darwin-x86_64" for macOS):

```
HOST_PLATFORM="linux-x86_64"
```

*   Fetch the `cpu_features` library:

```
cd "${AV1_MODULE_PATH}/jni" && \
git clone https://github.com/google/cpu_features
```

*   Install [Meson][] (0.49 or higher), [Ninja][], and, for x86* targets,
    [nasm][] (2.14 or higher)

*   Fetch the `dav1d` library:

```
cd "${AV1_MODULE_PATH}/jni" && \
git clone https://code.videolan.org/videolan/dav1d.git
```

*   Execute `build_dav1d.sh` to build `libdav1d.a` for all supported
    architectures (`armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`).

```
cd "${AV1_MODULE_PATH}/jni" && \
./build_dav1d.sh \
  "${AV1_MODULE_PATH}" \
  "${NDK_PATH}" \
  "${HOST_PLATFORM}"
```

[top level README]: ../../README.md
[Android NDK]: https://developer.android.com/tools/sdk/ndk/index.html
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

## Rendering options

There are two possibilities for rendering the output `Libdav1dVideoRenderer`
gets from the dav1d decoder:

*   GL rendering using GL shader for color space conversion

    *   If you are using `ExoPlayer` with `PlayerView`, enable this option by
        setting the `surface_type` of the view to be
        `video_decoder_gl_surface_view`.
    *   Otherwise, enable this option by sending `Libdav1dVideoRenderer` a
        message of type `Renderer.MSG_SET_VIDEO_OUTPUT` with an instance of
        `VideoDecoderOutputBufferRenderer` as its object.
        `VideoDecoderGLSurfaceView` is the concrete
        `VideoDecoderOutputBufferRenderer` implementation used by `PlayerView`.

*   Native rendering using `ANativeWindow`

    *   If you are using `ExoPlayer` with `PlayerView`, this option is enabled
        by default.
    *   Otherwise, enable this option by sending `Libdav1dVideoRenderer` a
        message of type `Renderer.MSG_SET_VIDEO_OUTPUT` with an instance of
        `SurfaceView` as its object.

Note: Although the default option uses `ANativeWindow`, based on our testing the
GL rendering mode has better performance, so should be preferred

## Links

*   [Troubleshooting using decoding extensions][]

[Troubleshooting using decoding extensions]: https://developer.android.com/media/media3/exoplayer/troubleshooting#how-can-i-get-a-decoding-library-to-load-and-be-used-for-playback
