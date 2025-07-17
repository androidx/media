/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import static android.os.Build.VERSION.SDK_INT;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.MediaCodec;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.spherical.CameraMotionRenderer;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.ForOverride;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.util.ArrayList;

/** Default {@link RenderersFactory} implementation. */
@UnstableApi
public class DefaultRenderersFactory implements RenderersFactory {

  /**
   * The default maximum duration for which a video renderer can attempt to seamlessly join an
   * ongoing playback.
   */
  public static final long DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS = 5000;

  /**
   * Modes for using extension renderers. One of {@link #EXTENSION_RENDERER_MODE_OFF}, {@link
   * #EXTENSION_RENDERER_MODE_ON} or {@link #EXTENSION_RENDERER_MODE_PREFER}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({EXTENSION_RENDERER_MODE_OFF, EXTENSION_RENDERER_MODE_ON, EXTENSION_RENDERER_MODE_PREFER})
  public @interface ExtensionRendererMode {}

  /** Do not allow use of extension renderers. */
  public static final int EXTENSION_RENDERER_MODE_OFF = 0;

  /**
   * Allow use of extension renderers. Extension renderers are indexed after core renderers of the
   * same type. A {@link TrackSelector} that prefers the first suitable renderer will therefore
   * prefer to use a core renderer to an extension renderer in the case that both are able to play a
   * given track.
   */
  public static final int EXTENSION_RENDERER_MODE_ON = 1;

  /**
   * Allow use of extension renderers. Extension renderers are indexed before core renderers of the
   * same type. A {@link TrackSelector} that prefers the first suitable renderer will therefore
   * prefer to use an extension renderer to a core renderer in the case that both are able to play a
   * given track.
   */
  public static final int EXTENSION_RENDERER_MODE_PREFER = 2;

  /**
   * The maximum number of frames that can be dropped between invocations of {@link
   * VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public static final int MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50;

  private static final String TAG = "DefaultRenderersFactory";

  private final Context context;
  private final DefaultMediaCodecAdapterFactory codecAdapterFactory;
  private @ExtensionRendererMode int extensionRendererMode;
  private long allowedVideoJoiningTimeMs;
  private boolean enableDecoderFallback;
  private MediaCodecSelector mediaCodecSelector;
  private boolean enableFloatOutput;
  private boolean enableAudioTrackPlaybackParams;
  private boolean enableMediaCodecVideoRendererPrewarming;
  private boolean parseAv1SampleDependencies;
  private long lateThresholdToDropDecoderInputUs;
  private boolean enableMediaCodecBufferDecodeOnlyFlag;

  /**
   * @param context A {@link Context}.
   */
  public DefaultRenderersFactory(Context context) {
    this.context = context;
    codecAdapterFactory = new DefaultMediaCodecAdapterFactory(context);
    extensionRendererMode = EXTENSION_RENDERER_MODE_OFF;
    allowedVideoJoiningTimeMs = DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS;
    mediaCodecSelector = MediaCodecSelector.DEFAULT;
    lateThresholdToDropDecoderInputUs = C.TIME_UNSET;
  }

  /**
   * Sets the extension renderer mode, which determines if and how available extension renderers are
   * used. Note that extensions must be included in the application build for them to be considered
   * available.
   *
   * <p>The default value is {@link #EXTENSION_RENDERER_MODE_OFF}.
   *
   * @param extensionRendererMode The extension renderer mode.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setExtensionRendererMode(
      @ExtensionRendererMode int extensionRendererMode) {
    this.extensionRendererMode = extensionRendererMode;
    return this;
  }

  /**
   * Enables {@link androidx.media3.exoplayer.mediacodec.MediaCodecRenderer} instances to operate
   * their {@link MediaCodec} in asynchronous mode and perform asynchronous queueing.
   *
   * <p>This feature can be enabled only on devices with API versions &gt;= 23. For devices with
   * older API versions, this method is a no-op.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory forceEnableMediaCodecAsynchronousQueueing() {
    codecAdapterFactory.forceEnableAsynchronous();
    return this;
  }

  /**
   * Disables {@link androidx.media3.exoplayer.mediacodec.MediaCodecRenderer} instances from
   * operating their {@link MediaCodec} in asynchronous mode and perform asynchronous queueing.
   * {@link MediaCodec} instances will be operated synchronous mode.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory forceDisableMediaCodecAsynchronousQueueing() {
    codecAdapterFactory.forceDisableAsynchronous();
    return this;
  }

  /**
   * Sets whether to enable {@link MediaCodec#CONFIGURE_FLAG_USE_CRYPTO_ASYNC} on API 34 and above
   * when operating the codec in asynchronous mode.
   *
   * <p>This method is experimental. Its default value may change, or it may be renamed or removed
   * in a future release.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory experimentalSetMediaCodecAsyncCryptoFlagEnabled(
      boolean enableAsyncCryptoFlag) {
    codecAdapterFactory.experimentalSetAsyncCryptoFlagEnabled(enableAsyncCryptoFlag);
    return this;
  }

  /**
   * Sets whether to enable fallback to lower-priority decoders if decoder initialization fails.
   * This may result in using a decoder that is less efficient or slower than the primary decoder.
   *
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setEnableDecoderFallback(boolean enableDecoderFallback) {
    this.enableDecoderFallback = enableDecoderFallback;
    return this;
  }

  /**
   * Sets a {@link MediaCodecSelector} for use by {@link MediaCodec} based renderers.
   *
   * <p>The default value is {@link MediaCodecSelector#DEFAULT}.
   *
   * @param mediaCodecSelector The {@link MediaCodecSelector}.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setMediaCodecSelector(
      MediaCodecSelector mediaCodecSelector) {
    this.mediaCodecSelector = mediaCodecSelector;
    return this;
  }

  /**
   * Sets whether floating point audio should be output when possible.
   *
   * <p>Enabling floating point output disables audio processing, but may allow for higher quality
   * audio output.
   *
   * <p>The default value is {@code false}.
   *
   * @param enableFloatOutput Whether to enable use of floating point audio output, if available.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setEnableAudioFloatOutput(boolean enableFloatOutput) {
    this.enableFloatOutput = enableFloatOutput;
    return this;
  }

  /**
   * Sets whether to enable setting playback speed using {@link
   * android.media.AudioTrack#setPlaybackParams(PlaybackParams)}, which is supported from API level
   * 23, rather than using application-level audio speed adjustment. This setting has no effect on
   * builds before API level 23 (application-level speed adjustment will be used in all cases).
   *
   * <p>If enabled and supported, new playback speed settings will take effect more quickly because
   * they are applied at the audio mixer, rather than at the point of writing data to the track.
   *
   * <p>When using this mode, the maximum supported playback speed is limited by the size of the
   * audio track's buffer. If the requested speed is not supported the player's event listener will
   * be notified twice on setting playback speed, once with the requested speed, then again with the
   * old playback speed reflecting the fact that the requested speed was not supported.
   *
   * @param enableAudioTrackPlaybackParams Whether to enable setting playback speed using {@link
   *     android.media.AudioTrack#setPlaybackParams(PlaybackParams)}.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setEnableAudioTrackPlaybackParams(
      boolean enableAudioTrackPlaybackParams) {
    this.enableAudioTrackPlaybackParams = enableAudioTrackPlaybackParams;
    return this;
  }

  /**
   * Sets whether a secondary {@link MediaCodecVideoRenderer} will be created through {@link
   * #buildSecondaryVideoRenderer} for use by {@link ExoPlayer} for pre-warming.
   *
   * <p>If enabled, {@link ExoPlayer} will pre-process the video of consecutive media items during
   * playback to reduce media item transition latency.
   *
   * <p>If {@link #buildVideoRenderers} is overridden to provide custom video renderers then to
   * still enable pre-warming,{@link #buildSecondaryVideoRenderer} must also be overridden to
   * provide matching secondary custom video renderers.
   *
   * <p>This method is experimental. Its default value may change, or it may be renamed or removed
   * in a future release.
   *
   * @param enableMediaCodecVideoRendererPrewarming Whether to enable {@link
   *     #buildSecondaryVideoRenderer} to provide a secondary {@link MediaCodecVideoRenderer} for
   *     pre-warming.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory experimentalSetEnableMediaCodecVideoRendererPrewarming(
      boolean enableMediaCodecVideoRendererPrewarming) {
    this.enableMediaCodecVideoRendererPrewarming = enableMediaCodecVideoRendererPrewarming;
    return this;
  }

  /**
   * Sets whether {@link MimeTypes#VIDEO_AV1} bitstream parsing for sample dependency information is
   * enabled. Knowing which input frames are not depended on can speed up seeking and reduce dropped
   * frames.
   *
   * <p>Defaults to {@code false}.
   *
   * <p>This method is experimental and will be renamed or removed in a future release.
   *
   * @param parseAv1SampleDependencies Whether bitstream parsing is enabled.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory experimentalSetParseAv1SampleDependencies(
      boolean parseAv1SampleDependencies) {
    this.parseAv1SampleDependencies = parseAv1SampleDependencies;
    return this;
  }

  /**
   * Sets whether the {@link MediaCodec#BUFFER_FLAG_DECODE_ONLY} flag will be included when queuing
   * decode-only input buffers to the decoder.
   *
   * <p>If {@code false}, then only if the decoder is set up in tunneling mode will the decode-only
   * input buffers be queued with the {@link MediaCodec#BUFFER_FLAG_DECODE_ONLY} flag. The default
   * value is {@code false}.
   *
   * <p>Requires API 34.
   *
   * <p>This method is experimental and will be renamed or removed in a future release.
   */
  @RequiresApi(34)
  @CanIgnoreReturnValue
  public DefaultRenderersFactory experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
      boolean enableMediaCodecBufferDecodeOnlyFlag) {
    this.enableMediaCodecBufferDecodeOnlyFlag = enableMediaCodecBufferDecodeOnlyFlag;
    return this;
  }

  /**
   * Sets the maximum duration for which video renderers can attempt to seamlessly join an ongoing
   * playback.
   *
   * <p>The default value is {@link #DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS}.
   *
   * @param allowedVideoJoiningTimeMs The maximum duration for which video renderers can attempt to
   *     seamlessly join an ongoing playback, in milliseconds.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory setAllowedVideoJoiningTimeMs(
      long allowedVideoJoiningTimeMs) {
    this.allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs;
    return this;
  }

  /**
   * Sets the late threshold for rendered output buffers, in microseconds, after which decoder input
   * buffers may be dropped.
   *
   * <p>The default value is {@link C#TIME_UNSET} and therefore no input buffers will be dropped due
   * to this logic.
   *
   * <p>This method is experimental and will be renamed or removed in a future release.
   *
   * @param lateThresholdToDropDecoderInputUs The threshold.
   */
  @CanIgnoreReturnValue
  public final DefaultRenderersFactory experimentalSetLateThresholdToDropDecoderInputUs(
      long lateThresholdToDropDecoderInputUs) {
    this.lateThresholdToDropDecoderInputUs = lateThresholdToDropDecoderInputUs;
    return this;
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    ArrayList<Renderer> renderersList = new ArrayList<>();
    buildVideoRenderers(
        context,
        extensionRendererMode,
        mediaCodecSelector,
        enableDecoderFallback,
        eventHandler,
        videoRendererEventListener,
        allowedVideoJoiningTimeMs,
        renderersList);
    @Nullable
    AudioSink audioSink =
        buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
    if (audioSink != null) {
      buildAudioRenderers(
          context,
          extensionRendererMode,
          mediaCodecSelector,
          enableDecoderFallback,
          audioSink,
          eventHandler,
          audioRendererEventListener,
          renderersList);
    }
    buildTextRenderers(
        context,
        textRendererOutput,
        eventHandler.getLooper(),
        extensionRendererMode,
        renderersList);
    buildMetadataRenderers(
        context,
        metadataRendererOutput,
        eventHandler.getLooper(),
        extensionRendererMode,
        renderersList);
    buildCameraMotionRenderers(context, extensionRendererMode, renderersList);
    buildImageRenderers(context, renderersList);
    buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, renderersList);
    return renderersList.toArray(new Renderer[0]);
  }

  /**
   * Builds video renderers for use by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param extensionRendererMode The extension renderer mode.
   * @param mediaCodecSelector A decoder selector.
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails. This may result in using a decoder that is slower/less efficient than
   *     the primary decoder.
   * @param eventHandler A handler associated with the main thread's looper.
   * @param eventListener An event listener.
   * @param allowedVideoJoiningTimeMs The maximum duration for which video renderers can attempt to
   *     seamlessly join an ongoing playback, in milliseconds.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildVideoRenderers(
      Context context,
      @ExtensionRendererMode int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      long allowedVideoJoiningTimeMs,
      ArrayList<Renderer> out) {
    MediaCodecVideoRenderer.Builder videoRendererBuilder =
        new MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .experimentalSetParseAv1SampleDependencies(parseAv1SampleDependencies)
            .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropDecoderInputUs);
    if (SDK_INT >= 34) {
      videoRendererBuilder =
          videoRendererBuilder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
              enableMediaCodecBufferDecodeOnlyFlag);
    }
    out.add(videoRendererBuilder.build());

    if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
      return;
    }
    int extensionRendererIndex = out.size();
    if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
      extensionRendererIndex--;
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.vp9.LibvpxVideoRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              long.class,
              Handler.class,
              androidx.media3.exoplayer.video.VideoRendererEventListener.class,
              int.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer)
              constructor.newInstance(
                  allowedVideoJoiningTimeMs,
                  eventHandler,
                  eventListener,
                  MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded LibvpxVideoRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating VP9 extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.dav1d.Libdav1dVideoRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              long.class,
              Handler.class,
              androidx.media3.exoplayer.video.VideoRendererEventListener.class,
              int.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer)
              constructor.newInstance(
                  allowedVideoJoiningTimeMs,
                  eventHandler,
                  eventListener,
                  MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded Libdav1dVideoRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating dav1d AV1 extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.av1.Libgav1VideoRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              long.class,
              Handler.class,
              androidx.media3.exoplayer.video.VideoRendererEventListener.class,
              int.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer)
              constructor.newInstance(
                  allowedVideoJoiningTimeMs,
                  eventHandler,
                  eventListener,
                  MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded Libgav1VideoRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating AV1 extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz =
          Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              long.class,
              Handler.class,
              androidx.media3.exoplayer.video.VideoRendererEventListener.class,
              int.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer)
              constructor.newInstance(
                  allowedVideoJoiningTimeMs,
                  eventHandler,
                  eventListener,
                  MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded FfmpegVideoRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating FFmpeg extension", e);
    }
  }

  /**
   * Builds audio renderers for use by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param extensionRendererMode The extension renderer mode.
   * @param mediaCodecSelector A decoder selector.
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails. This may result in using a decoder that is slower/less efficient than
   *     the primary decoder.
   * @param audioSink A sink to which the renderers will output.
   * @param eventHandler A handler to use when invoking event listeners and outputs.
   * @param eventListener An event listener.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildAudioRenderers(
      Context context,
      @ExtensionRendererMode int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      AudioSink audioSink,
      Handler eventHandler,
      AudioRendererEventListener eventListener,
      ArrayList<Renderer> out) {
    MediaCodecAudioRenderer audioRenderer =
        new MediaCodecAudioRenderer(
            context,
            getCodecAdapterFactory(),
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink);
    out.add(audioRenderer);

    if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
      return;
    }
    int extensionRendererIndex = out.size();
    if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
      extensionRendererIndex--;
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.midi.MidiRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              Context.class,
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer) constructor.newInstance(context, eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded MidiRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating MIDI extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.opus.LibopusAudioRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded LibopusAudioRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating Opus extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.flac.LibflacAudioRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded LibflacAudioRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating FLAC extension", e);
    }

    try {
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded FfmpegAudioRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating FFmpeg extension", e);
    }

    try {
      // Full class names used for constructor args so the LINT rule triggers if any of them move.
      // LINT.IfChange
      Class<?> clazz = Class.forName("androidx.media3.decoder.iamf.LibiamfAudioRenderer");
      // Full class names used for media3 constructor args so the LINT rule triggers if any of them
      // move.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      Constructor<?> constructor =
          clazz.getConstructor(
              Context.class,
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      // LINT.ThenChange(../../../../../../proguard-rules.txt)
      Renderer renderer =
          (Renderer) constructor.newInstance(context, eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded LibiamfAudioRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating IAMF extension", e);
    }

    try {
      // Full class names used for constructor args so the LINT rule triggers if any of them move.
      Class<?> clazz = Class.forName("androidx.media3.decoder.mpegh.MpeghAudioRenderer");
      Constructor<?> constructor =
          clazz.getConstructor(
              Handler.class,
              androidx.media3.exoplayer.audio.AudioRendererEventListener.class,
              androidx.media3.exoplayer.audio.AudioSink.class);
      Renderer renderer =
          (Renderer) constructor.newInstance(eventHandler, eventListener, audioSink);
      out.add(extensionRendererIndex++, renderer);
      Log.i(TAG, "Loaded MpeghAudioRenderer.");
    } catch (ClassNotFoundException e) {
      // Expected if the app was built without the extension.
    } catch (Exception e) {
      // The extension is present, but instantiation failed.
      throw new IllegalStateException("Error instantiating MPEG-H extension", e);
    }
  }

  /**
   * Builds text renderers for use by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param output An output for the renderers.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   * @param extensionRendererMode The extension renderer mode.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildTextRenderers(
      Context context,
      TextOutput output,
      Looper outputLooper,
      @ExtensionRendererMode int extensionRendererMode,
      ArrayList<Renderer> out) {
    out.add(new TextRenderer(output, outputLooper));
  }

  /**
   * Builds metadata renderers for use by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param output An output for the renderers.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   * @param extensionRendererMode The extension renderer mode.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildMetadataRenderers(
      Context context,
      MetadataOutput output,
      Looper outputLooper,
      @ExtensionRendererMode int extensionRendererMode,
      ArrayList<Renderer> out) {
    out.add(new MetadataRenderer(output, outputLooper));
    out.add(new MetadataRenderer(output, outputLooper));
  }

  /**
   * Builds camera motion renderers for use by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param extensionRendererMode The extension renderer mode.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildCameraMotionRenderers(
      Context context, @ExtensionRendererMode int extensionRendererMode, ArrayList<Renderer> out) {
    out.add(new CameraMotionRenderer());
  }

  /**
   * @deprecated Override {@link #buildImageRenderers(Context, ArrayList)} instead.
   */
  @Deprecated
  protected void buildImageRenderers(ArrayList<Renderer> out) {
    out.add(new ImageRenderer(getImageDecoderFactory(context), /* imageOutput= */ null));
  }

  /**
   * Builds image renderers for use by the player.
   *
   * <p>The {@link ImageRenderer} is built with {@code ImageOutput} set to null and {@link
   * ImageDecoder.Factory} set to {@code ImageDecoder.Factory.DEFAULT} by default.
   *
   * @param context The {@link Context} associated with the player.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildImageRenderers(Context context, ArrayList<Renderer> out) {
    buildImageRenderers(out);
  }

  /**
   * Builds any miscellaneous renderers used by the player.
   *
   * @param context The {@link Context} associated with the player.
   * @param eventHandler A handler to use when invoking event listeners and outputs.
   * @param extensionRendererMode The extension renderer mode.
   * @param out An array to which the built renderers should be appended.
   */
  protected void buildMiscellaneousRenderers(
      Context context,
      Handler eventHandler,
      @ExtensionRendererMode int extensionRendererMode,
      ArrayList<Renderer> out) {
    // Do nothing.
  }

  /**
   * Builds an {@link AudioSink} to which the audio renderers will output.
   *
   * @param context The {@link Context} associated with the player.
   * @param enableFloatOutput Whether to enable use of floating point audio output, if available.
   * @param enableAudioTrackPlaybackParams Whether to enable setting playback speed using {@link
   *     android.media.AudioTrack#setPlaybackParams(PlaybackParams)}, if supported.
   * @return The {@link AudioSink} to which the audio renderers will output. May be {@code null} if
   *     no audio renderers are required. If {@code null} is returned then {@link
   *     #buildAudioRenderers} will not be called.
   */
  @Nullable
  protected AudioSink buildAudioSink(
      Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
    return new DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build();
  }

  @Override
  @Nullable
  public Renderer createSecondaryRenderer(
      Renderer renderer,
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    if (renderer.getTrackType() == C.TRACK_TYPE_VIDEO) {
      return buildSecondaryVideoRenderer(
          renderer,
          context,
          extensionRendererMode,
          mediaCodecSelector,
          enableDecoderFallback,
          eventHandler,
          videoRendererEventListener,
          allowedVideoJoiningTimeMs);
    }
    return null;
  }

  /**
   * Builds a secondary video renderer for an {@link ExoPlayer} instance to use for pre-warming.
   *
   * <p>The created secondary video {@code Renderer} should match its primary {@link Renderer} in
   * reported track type support and {@link RendererCapabilities}.
   *
   * <p>If {@link #buildVideoRenderers} is overridden to provide custom video renderers, then this
   * method must also be overridden to supply corresponding custom secondary video renderers.
   *
   * @param renderer The primary {@link Renderer} for which to create the secondary.
   * @param context The {@link Context} associated with the player.
   * @param extensionRendererMode The extension renderer mode.
   * @param mediaCodecSelector A decoder selector.
   * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
   *     initialization fails. This may result in using a decoder that is slower/less efficient than
   *     the primary decoder.
   * @param eventHandler A handler associated with the main thread's looper.
   * @param eventListener An event listener.
   * @param allowedVideoJoiningTimeMs The maximum duration for which video renderers can attempt to
   *     seamlessly join an ongoing playback, in milliseconds.
   * @return The created secondary {@link Renderer renderer instance}.
   */
  @Nullable
  protected Renderer buildSecondaryVideoRenderer(
      Renderer renderer,
      Context context,
      @ExtensionRendererMode int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      long allowedVideoJoiningTimeMs) {
    if (enableMediaCodecVideoRendererPrewarming
        && renderer.getClass() == MediaCodecVideoRenderer.class) {
      MediaCodecVideoRenderer.Builder builder =
          new MediaCodecVideoRenderer.Builder(context)
              .setCodecAdapterFactory(getCodecAdapterFactory())
              .setMediaCodecSelector(mediaCodecSelector)
              .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
              .setEnableDecoderFallback(enableDecoderFallback)
              .setEventHandler(eventHandler)
              .setEventListener(eventListener)
              .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
              .experimentalSetParseAv1SampleDependencies(parseAv1SampleDependencies)
              .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropDecoderInputUs);
      if (SDK_INT >= 34) {
        builder =
            builder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
                enableMediaCodecBufferDecodeOnlyFlag);
      }
      return builder.build();
    }
    return null;
  }

  /**
   * Returns the {@link MediaCodecAdapter.Factory} that will be used when creating {@link
   * androidx.media3.exoplayer.mediacodec.MediaCodecRenderer} instances.
   */
  protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
    return codecAdapterFactory;
  }

  /** Returns the {@link ImageDecoder.Factory} used to build the image renderer. */
  @ForOverride
  protected ImageDecoder.Factory getImageDecoderFactory(Context context) {
    return new BitmapFactoryImageDecoder.Factory(context);
  }
}
