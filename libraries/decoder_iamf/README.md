# IAMF decoder module

The IAMF module provides `LibiamfAudioRenderer`, which uses libiamf (the IAMF
decoding library) to decode IAMF audio.

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

IAMF_MODULE_PATH="$(pwd)/libraries/decoder_iamf/src/main"
```

*   Download the [Android NDK][] and set its location in an environment
    variable. This build configuration has been tested on NDK r27.

```
NDK_PATH="<path to Android NDK>"
```

*   Fetch libiamf:

Clone the repository containing libiamf to a local folder of choice - preferably
outside of the project checkout. Link it to the project's jni folder through
symlink.

```
cd <preferred location for libiamf>


git clone https://github.com/AOMediaCodec/libiamf.git libiamf && \
cd libiamf && \
LIBIAMF_PATH=$(pwd)
```

*   Symlink the folder containing libiamf to the project's JNI folder and run
    the script to convert libiamf code to NDK compatible format:

```
cd "${IAMF_MODULE_PATH}"/jni && \
ln -s $LIBIAMF_PATH libiamf && \
cd libiamf/code &&\
cmake . && \
make
```

* Build the JNI native libraries from the command line:

```
cd "${IAMF_MODULE_PATH}"/jni && \
${NDK_PATH}/ndk-build APP_ABI=all -j4
```

[top level README]: ../../README.md
[Android NDK]: https://developer.android.com/tools/sdk/ndk/index.html

## Build instructions (Windows)

We do not provide support for building this module on Windows, however it should
be possible to follow the Linux instructions in [Windows PowerShell][].

[Windows PowerShell]: https://docs.microsoft.com/en-us/powershell/scripting/getting-started/getting-started-with-windows-powershell

## Notes

*   Every time there is a change to the libiamf checkout clean and re-build the
    project.
*   If you want to use your own version of libiamf, place it in
    `${IAMF_MODULE_PATH}/jni/libiamf`.

## Using the module with ExoPlayer

Once you've followed the instructions above to check out, build and depend on
the module, the next step is to tell ExoPlayer to use `LibiamfAudioRenderer`.
How you do this depends on which player API you're using:

*   If you're passing a `DefaultRenderersFactory` to `ExoPlayer.Builder`, you
    can enable using the module by setting the `extensionRendererMode` parameter
    of the `DefaultRenderersFactory` constructor to
    `EXTENSION_RENDERER_MODE_ON`. This will use `LibiamfAudioRenderer` for
    playback if `MediaCodecAudioRenderer` doesn't support the input format. Pass
    `EXTENSION_RENDERER_MODE_PREFER` to give `LibiamfAudioRenderer` priority
    over `MediaCodecAudioRenderer`.
*   If you've subclassed `DefaultRenderersFactory`, add a `LibiamfAudioRenderer`
    to the output list in `buildAudioRenderers`. ExoPlayer will use the first
    `Renderer` in the list that supports the input media format.
*   If you've implemented your own `RenderersFactory`, return a
    `LibiamfAudioRenderer` instance from `createRenderers`. ExoPlayer will use
    the first `Renderer` in the returned array that supports the input media
    format.
*   If you're using `ExoPlayer.Builder`, pass a `LibiamfAudioRenderer` in the
    array of `Renderer`s. ExoPlayer will use the first `Renderer` in the list
    that supports the input media format.

Note: These instructions assume you're using `DefaultTrackSelector`. If you have
a custom track selector the choice of `Renderer` is up to your implementation,
so you need to make sure you are passing a `LibiamfAudioRenderer` to the
player, then implement your own logic to use the renderer for a given track.

## Links

*   [Troubleshooting using decoding extensions][]

[Troubleshooting using decoding extensions]: https://developer.android.com/media/media3/exoplayer/troubleshooting#how-can-i-get-a-decoding-library-to-load-and-be-used-for-playback
