# Opus decoder module

The Opus module provides `LibopusAudioRenderer`, which uses libopus (the Opus
decoding library) to decode Opus audio.

## License note

Please note that whilst the code in this repository is licensed under
[Apache 2.0][], using this module also requires building and including one or
more external libraries as described below. These are licensed separately.

[Apache 2.0]: ../../LICENSE

## Build instructions (Linux, macOS)

To use the module you need to clone this GitHub project and depend on its
modules locally. Instructions for doing this can be found in the
[top level README][].

In addition, it's necessary to build the module's native components as follows:

* Set the following environment variables:

```
cd "<path to project checkout>"
OPUS_MODULE_PATH="$(pwd)/libraries/decoder_opus/src/main"
```

* Download the [Android NDK][] and set its location in an environment variable.
  This build configuration has been tested on NDK r21.

```
NDK_PATH="<path to Android NDK>"
```

* Fetch libopus:

```
cd "${OPUS_MODULE_PATH}/jni" && \
git clone https://gitlab.xiph.org/xiph/opus.git libopus
```

* Run the script to convert arm assembly to NDK compatible format:

```
cd ${OPUS_MODULE_PATH}/jni && ./convert_android_asm.sh
```

* Build the JNI native libraries from the command line:

```
cd "${OPUS_MODULE_PATH}"/jni && \
${NDK_PATH}/ndk-build APP_ABI=all -j4
```

[top level README]: ../../README.md
[Android NDK]: https://developer.android.com/tools/sdk/ndk/index.html

## Build instructions (Windows)

We do not provide support for building this module on Windows, however it should
be possible to follow the Linux instructions in [Windows PowerShell][].

[Windows PowerShell]: https://docs.microsoft.com/en-us/powershell/scripting/getting-started/getting-started-with-windows-powershell

## Notes

* Every time there is a change to the libopus checkout:
  * Arm assembly should be converted by running `convert_android_asm.sh`
  * Clean and re-build the project.
* If you want to use your own version of libopus, place it in
  `${OPUS_MODULE_PATH}/jni/libopus`.

## Using the module with ExoPlayer

Once you've followed the instructions above to check out, build and depend on
the module, the next step is to tell ExoPlayer to use `LibopusAudioRenderer`.
How you do this depends on which player API you're using:

*   If you're passing a `DefaultRenderersFactory` to `ExoPlayer.Builder`, you
    can enable using the module by setting the `extensionRendererMode` parameter
    of the `DefaultRenderersFactory` constructor to
    `EXTENSION_RENDERER_MODE_ON`. This will use `LibopusAudioRenderer` for
    playback if `MediaCodecAudioRenderer` doesn't support the input format. Pass
    `EXTENSION_RENDERER_MODE_PREFER` to give `LibopusAudioRenderer` priority
    over `MediaCodecAudioRenderer`.
*   If you've subclassed `DefaultRenderersFactory`, add a `LibopusAudioRenderer`
    to the output list in `buildAudioRenderers`. ExoPlayer will use the first
    `Renderer` in the list that supports the input media format.
*   If you've implemented your own `RenderersFactory`, return a
    `LibopusAudioRenderer` instance from `createRenderers`. ExoPlayer will use
    the first `Renderer` in the returned array that supports the input media
    format.
*   If you're using `ExoPlayer.Builder`, pass a `LibopusAudioRenderer` in the
    array of `Renderer`s. ExoPlayer will use the first `Renderer` in the list
    that supports the input media format.

Note: These instructions assume you're using `DefaultTrackSelector`. If you have
a custom track selector the choice of `Renderer` is up to your implementation,
so you need to make sure you are passing an `LibopusAudioRenderer` to the
player, then implement your own logic to use the renderer for a given track.

## Links

*   [Troubleshooting using decoding extensions][]

[Troubleshooting using decoding extensions]: https://developer.android.com/media/media3/exoplayer/troubleshooting#how-can-i-get-a-decoding-library-to-load-and-be-used-for-playback
