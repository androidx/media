# 在 DefaultRenderersFactory 中使用 FFmpeg 渲染器

- **音频**：用 `useFfmpeg` 控制，开启时把 `FfmpegAudioRenderer` 放在最前，优先软解。
- **视频**：优先硬解（MediaCodec），只有硬件不支持时才用 FFmpeg 软解（作为 fallback）。

## 1. 依赖

确保应用已依赖 decoder_ffmpeg，例如：

```kotlin
implementation(project(":media3-decoder-ffmpeg"))  // 或你的 decoder_ffmpeg 模块名
```

## 2. 推荐写法

```kotlin
val mRenderFactory = object : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // 音频：useFfmpeg 时优先用 FFmpeg
        if (useFfmpeg && FfmpegLibrary.isAvailable()) {
            out.add(FfmpegAudioRenderer())
        }
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // 视频：优先硬解，不支持时再用 FFmpeg。useFfmpeg 时把扩展（含 FFmpeg）放在 MediaCodec 后面即可
        if (useFfmpeg && FfmpegLibrary.isAvailable()) {
            super.buildVideoRenderers(
                context,
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out
            )
        } else {
            super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out
            )
        }
    }
}.apply {
    setEnableDecoderFallback(true)
    if (useFfmpeg) setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    setMediaCodecSelector { ... }
    forceEnableMediaCodecAsynchronousQueueing()
}
```

## 3. 需要的 import

```kotlin
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
// ... 其他已有 import
```

## 4. 说明

- **音频**：`useFfmpeg` 时在列表最前加 `FfmpegAudioRenderer`，再 `super.buildAudioRenderers`，所以会优先用 FFmpeg 解音频。
- **视频**：`useFfmpeg` 时调用 `super.buildVideoRenderers(..., EXTENSION_RENDERER_MODE_ON, ...)`。默认会得到顺序：**MediaCodec → VP9 → AV1 → FFmpeg**，即先试硬解，不支持再试 FFmpeg，无需在 `buildVideoRenderers` 里手动把 FFmpeg 插到最前。
- `setExtensionRendererMode(PREFER)` 只影响「未在子类里重写」的渲染器；视频这里已重写并显式传 `EXTENSION_RENDERER_MODE_ON`，所以视频顺序就是「硬解优先、FFmpeg 兜底」。
