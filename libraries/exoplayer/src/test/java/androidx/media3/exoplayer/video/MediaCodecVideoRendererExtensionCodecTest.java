/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.exoplayer.Renderer.STATE_STARTED;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.format;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Effect;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.ExtensionCodecInfo;
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.VideoExtensionCodecAdapter;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeCryptoConfig;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.media3.test.utils.robolectric.IdlingMediaCodecAdapterFactory;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Bytes;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for extension-codec support in {@link MediaCodecVideoRenderer}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class MediaCodecVideoRendererExtensionCodecTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private static final Format VIDEO_H264 =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(1920)
          .setHeight(1080)
          .build();
  private static final Format VIDEO_H264_720P =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(1280)
          .setHeight(720)
          .build();
  private static final Format VIDEO_AV1 =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_AV1)
          .setWidth(1920)
          .setHeight(1080)
          .build();
  private static final Format VIDEO_AV1_720P =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_AV1)
          .setWidth(1280)
          .setHeight(720)
          .build();

  private static final ExtensionCodecInfo AV1_EXTENSION_CODEC_INFO =
      ExtensionCodecInfo.newInstance("c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
  private static final MediaCodecSelector AV1_EXTENSION_CODEC_SELECTOR =
      (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
          Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                  && !requiresSecureDecoder
                  && !requiresTunnelingDecoder
              ? ImmutableList.of(AV1_EXTENSION_CODEC_INFO)
              : ImmutableList.of();
  private static final ExtensionCodecInfo DRM_AV1_EXTENSION_CODEC_INFO =
      new ExtensionCodecInfo("c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1) {
        @Override
        public boolean isFormatDrmSupported(Format format) {
          return true;
        }
      };
  private static final MediaCodecSelector DRM_AV1_EXTENSION_CODEC_SELECTOR =
      (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
          Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
              ? ImmutableList.of(DRM_AV1_EXTENSION_CODEC_INFO)
              : ImmutableList.of();

  private final List<MediaCodecVideoRenderer> createdRenderers = new ArrayList<>();
  private final List<Surface> createdSurfaces = new ArrayList<>();
  private final List<SurfaceTexture> createdSurfaceTextures = new ArrayList<>();
  private final RecordingVideoRendererEventListener eventListener =
      new RecordingVideoRendererEventListener();

  private Looper testMainLooper;
  private Surface surface;
  private MediaCodecSelector mediaCodecSelector;
  private IdlingMediaCodecAdapterFactory codecAdapterFactory;

  @Before
  public void setUp() {
    testMainLooper = Looper.getMainLooper();
    codecAdapterFactory =
        new IdlingMediaCodecAdapterFactory(ApplicationProvider.getApplicationContext());
    mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          MediaFormat mediaFormat = new MediaFormat();
          mediaFormat.setString(MediaFormat.KEY_MIME, mimeType);
          if (requiresSecureDecoder) {
            mediaFormat.setFeatureEnabled(CodecCapabilities.FEATURE_SecurePlayback, true);
          }
          CodecCapabilities capabilities =
              MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                  .setMediaFormat(mediaFormat)
                  .setColorFormats(new int[0])
                  .setProfileLevels(new CodecProfileLevel[0])
                  .build();
          return Collections.singletonList(
              MediaCodecInfo.newInstance(
                  /* name= */ "name",
                  /* mimeType= */ mimeType,
                  /* codecMimeType= */ mimeType,
                  capabilities,
                  /* hardwareAccelerated= */ false,
                  /* softwareOnly= */ true,
                  /* vendor= */ false));
        };
    surface = createValidSurface(/* texName= */ 0);
  }

  @After
  public void cleanUp() throws Exception {
    Throwable firstError = null;
    for (MediaCodecVideoRenderer renderer : createdRenderers) {
      try {
        if (renderer.getState() == STATE_STARTED) {
          renderer.stop();
        }
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
      try {
        if (renderer.getState() == Renderer.STATE_ENABLED) {
          renderer.disable();
        }
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
      try {
        renderer.reset();
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
      try {
        renderer.release();
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
    }
    createdRenderers.clear();
    for (Surface createdSurface : createdSurfaces) {
      try {
        createdSurface.release();
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
    }
    createdSurfaces.clear();
    for (SurfaceTexture createdSurfaceTexture : createdSurfaceTextures) {
      try {
        createdSurfaceTexture.release();
      } catch (Throwable t) {
        firstError = recordError(firstError, t);
      }
    }
    createdSurfaceTextures.clear();
    if (firstError instanceof Exception) {
      throw (Exception) firstError;
    } else if (firstError instanceof Error) {
      throw (Error) firstError;
    } else if (firstError != null) {
      throw new RuntimeException(firstError);
    }
  }

  private static Throwable recordError(@Nullable Throwable firstError, Throwable newError) {
    if (firstError == null) {
      return newError;
    }
    if (firstError != newError) {
      firstError.addSuppressed(newError);
    }
    return firstError;
  }

  @Test
  @Config(minSdk = 29)
  public void
      supportsFormat_withPreferredExtensionCodecAndTunnelingFallback_returnsFormatHandledWithoutTunneling()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format av1UnknownResolutionFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaFormat tunnelingMediaFormat = new MediaFormat();
    tunnelingMediaFormat.setString(MediaFormat.KEY_MIME, MimeTypes.VIDEO_AV1);
    tunnelingMediaFormat.setFeatureEnabled(CodecCapabilities.FEATURE_TunneledPlayback, true);
    tunnelingMediaFormat.setFeatureEnabled(CodecCapabilities.FEATURE_AdaptivePlayback, true);
    CodecCapabilities tunnelingCapabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(tunnelingMediaFormat)
            .setColorFormats(new int[0])
            .setProfileLevels(new CodecProfileLevel[0])
            .build();
    MediaCodecInfo fallbackTunnelingHardwareCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.tunneling.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            tunnelingCapabilities,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    MediaCodecSelector fallbackOnlySelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            Objects.equals(mimeType, MimeTypes.VIDEO_AV1) && !requiresSecureDecoder
                ? ImmutableList.of(fallbackTunnelingHardwareCodecInfo)
                : ImmutableList.of();
    MediaCodecSelector selector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (!Objects.equals(mimeType, MimeTypes.VIDEO_AV1) || requiresSecureDecoder) {
            return ImmutableList.of();
          }
          return requiresTunnelingDecoder
              ? ImmutableList.of(fallbackTunnelingHardwareCodecInfo)
              : ImmutableList.of(AV1_EXTENSION_CODEC_INFO, fallbackTunnelingHardwareCodecInfo);
        };
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(selector)
            .build();
    createdRenderers.add(renderer);

    @Capabilities
    int fallbackCapabilities =
        MediaCodecVideoRenderer.supportsFormat(
            ApplicationProvider.getApplicationContext(),
            fallbackOnlySelector,
            av1UnknownResolutionFormat);
    @Capabilities int capabilities = renderer.supportsFormat(av1UnknownResolutionFormat);

    assertThat(RendererCapabilities.getTunnelingSupport(fallbackCapabilities))
        .isEqualTo(RendererCapabilities.TUNNELING_SUPPORTED);
    assertThat(RendererCapabilities.getFormatSupport(capabilities)).isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getAdaptiveSupport(capabilities))
        .isEqualTo(RendererCapabilities.ADAPTIVE_SEAMLESS);
    assertThat(RendererCapabilities.getHardwareAccelerationSupport(capabilities))
        .isEqualTo(RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED);
    assertThat(RendererCapabilities.getTunnelingSupport(capabilities))
        .isEqualTo(RendererCapabilities.TUNNELING_NOT_SUPPORTED);
  }

  private enum EncryptedFormatSupportCase {
    CRYPTO_TYPE_FRAMEWORK {
      @Override
      Format createFormat() {
        return VIDEO_AV1_720P.buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build();
      }

      @Override
      MediaCodecSelector createSelector() {
        return AV1_EXTENSION_CODEC_SELECTOR;
      }
    },
    DRM_INIT_DATA {
      @Override
      Format createFormat() {
        return VIDEO_AV1_720P
            .buildUpon()
            .setDrmInitData(
                new DrmInitData(
                    new DrmInitData.SchemeData(
                        C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, /* data= */ new byte[] {1})))
            .build();
      }

      @Override
      MediaCodecSelector createSelector() {
        return AV1_EXTENSION_CODEC_SELECTOR;
      }
    },
    CUSTOM_CRYPTO_WITH_UNSUPPORTED_EXTENSION_FORMAT {
      @Override
      Format createFormat() {
        return VIDEO_AV1.buildUpon().setCryptoType(C.CRYPTO_TYPE_CUSTOM_BASE).build();
      }

      @Override
      MediaCodecSelector createSelector() {
        return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            requiresSecureDecoder || requiresTunnelingDecoder
                ? ImmutableList.of()
                : ImmutableList.of(
                    new ExtensionCodecInfo("c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1) {
                      @Override
                      protected boolean isFormatSupported(Format candidateFormat) {
                        return false;
                      }
                    });
      }
    };

    abstract Format createFormat();

    abstract MediaCodecSelector createSelector();
  }

  @Test
  public void supportsFormat_withOnlyExtensionCodecAndEncryptedFormat_returnsFormatUnsupportedDrm(
      @TestParameter EncryptedFormatSupportCase testCase) throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);

    @Capabilities
    int capabilities =
        MediaCodecVideoRenderer.supportsFormat(
            ApplicationProvider.getApplicationContext(),
            testCase.createSelector(),
            testCase.createFormat());

    assertThat(RendererCapabilities.getFormatSupport(capabilities))
        .isEqualTo(C.FORMAT_UNSUPPORTED_DRM);
  }

  @Test
  public void
      supportsFormat_withEncryptedFormatSupportedByExtensionWhenFlagDisabled_returnsFormatUnsupportedSubtype()
          throws Exception {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(AV1_EXTENSION_CODEC_SELECTOR)
            .build();
    createdRenderers.add(renderer);
    Format encryptedAv1Format =
        VIDEO_AV1_720P.buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build();

    @Capabilities int capabilities = renderer.supportsFormat(encryptedAv1Format);

    assertThat(RendererCapabilities.getFormatSupport(capabilities))
        .isEqualTo(C.FORMAT_UNSUPPORTED_SUBTYPE);
  }

  @Test
  public void supportsFormat_withUnsupportedEncryptedFormat_returnsFormatUnsupportedSubtype()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(AV1_EXTENSION_CODEC_SELECTOR)
            .build();
    createdRenderers.add(renderer);
    Format encryptedVp9Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VP9)
            .setWidth(1280)
            .setHeight(720)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    @Capabilities int capabilities = renderer.supportsFormat(encryptedVp9Format);

    assertThat(RendererCapabilities.getFormatSupport(capabilities))
        .isEqualTo(C.FORMAT_UNSUPPORTED_SUBTYPE);
  }

  @Test
  public void render_whenHardwareCodecFailsAndFallbackEnabled_fallsBackToExtensionCodec()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    MediaCodecInfo failingHardwareCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.hardware.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    AtomicReference<TestVideoExtensionCodecAdapter> fallbackAdapterRef = new AtomicReference<>();
    MediaCodecAdapter.Factory codecAdapterFactory =
        configuration -> {
          if (configuration.codecInfo.name.equals("c2.android.av1.hardware.decoder")) {
            throw new IOException("Hardware decoder initialization failed");
          }
          TestVideoExtensionCodecAdapter adapter =
              new TestVideoExtensionCodecAdapter(
                  configuration.format,
                  new FakePassthroughVideoDecoder(configuration.format),
                  configuration.surface);
          fallbackAdapterRef.set(adapter);
          return adapter;
        };
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            codecAdapterFactory,
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(failingHardwareCodecInfo, AV1_EXTENSION_CODEC_INFO),
            builder -> builder.setEnableDecoderFallback(true),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ i * 20_000L, msToUs(fakeClock.elapsedRealtime()));
      fakeClock.advanceTime(/* timeDiffMs= */ 20);
      shadowOf(testMainLooper).idle();
    }

    assertThat(checkNotNull(fallbackAdapterRef.get()).renderedSurfaceBufferCount).isEqualTo(1);
    assertThat(eventListener.codecErrors).hasSize(1);
    assertThat(eventListener.codecErrors.get(0)).isInstanceOf(DecoderInitializationException.class);
    assertThat(eventListener.initializedDecoderNames)
        .containsExactly("c2.media3.av1.dav1d.decoder");
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(surface);
  }

  @Test
  public void
      render_withExtensionCodecAndAdaptiveResolutionAndAspectRatioChange_reusesCodecAndUpdatesVideoSize()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format format720p = VIDEO_AV1_720P.buildUpon().setPixelWidthHeightRatio(1.0f).build();
    Format format1080pAnamorphic = VIDEO_AV1.buildUpon().setPixelWidthHeightRatio(2.0f).build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    int[] createAdapterCallCount = new int[1];
    List<@Nullable Surface> configuredSurfaces = new ArrayList<>();
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              createAdapterCallCount[0]++;
              configuredSurfaces.add(configuration.surface);
              return new TestVideoExtensionCodecAdapter(
                  configuration.format,
                  new FakePassthroughVideoDecoder(configuration.format),
                  configuration.surface);
            },
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            format720p,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                format(format1080pAnamorphic),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, format720p, fakeSampleStream);
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ i * 20_000L, msToUs(fakeClock.elapsedRealtime()));
      fakeClock.advanceTime(/* timeDiffMs= */ 20);
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.inputFormatReuseEvaluations).hasSize(2);
    assertThat(checkNotNull(eventListener.inputFormatReuseEvaluations.get(1)).result)
        .isEqualTo(DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(createAdapterCallCount[0]).isEqualTo(1);
    assertThat(configuredSurfaces).containsExactly(surface);
    assertThat(eventListener.reportedVideoSizes)
        .containsExactly(
            new VideoSize(/* width= */ 1280, /* height= */ 720, /* pixelWidthHeightRatio= */ 1.0f),
            new VideoSize(/* width= */ 1920, /* height= */ 1080, /* pixelWidthHeightRatio= */ 2.0f))
        .inOrder();
  }

  @Test
  public void render_withExtensionCodecAndAdaptiveRotationChange_doesNotSwapVideoSize()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format unrotatedFormat = VIDEO_AV1_720P.buildUpon().setRotationDegrees(0).build();
    Format rotatedFormat =
        VIDEO_AV1_720P.buildUpon().setRotationDegrees(90).setPixelWidthHeightRatio(2.0f).build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    new FakePassthroughVideoDecoder(configuration.format),
                    configuration.surface),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            unrotatedFormat,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                format(rotatedFormat),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, unrotatedFormat, fakeSampleStream);
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ i * 20_000L, msToUs(fakeClock.elapsedRealtime()));
      fakeClock.advanceTime(/* timeDiffMs= */ 20);
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.reportedVideoSizes)
        .containsExactly(
            new VideoSize(1280, 720),
            new VideoSize(/* width= */ 1280, /* height= */ 720, /* pixelWidthHeightRatio= */ 2.0f))
        .inOrder();
  }

  @Test
  @Config(sdk = 34)
  public void render_withVideoDecoderOutputBufferRenderer_rendersWithoutPlaceholderSurface(
      @TestParameter boolean needsSetOutputSurfaceWorkaround) throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    List<@Nullable Surface> configuredSurfaces = new ArrayList<>();
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecAdapter.Factory adapterFactory =
        configuration -> {
          configuredSurfaces.add(configuration.surface);
          return extensionCodecAdapter;
        };
    MediaCodecVideoRenderer renderer =
        needsSetOutputSurfaceWorkaround
            ? createRendererWithSetOutputSurfaceWorkaround(
                adapterFactory,
                AV1_EXTENSION_CODEC_SELECTOR,
                builder -> {},
                new FakeClock(/* initialTimeMs= */ 0))
            : createRenderer(adapterFactory);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(renderer, /* count= */ 4, /* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(configuredSurfaces).containsExactly((Surface) null);
    assertThat(bufferRenderer.renderedTimestampsUs).containsExactly(0L);
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(bufferRenderer);
    assertThat(eventListener.reportedVideoSizes).containsExactly(new VideoSize(1280, 720));
  }

  @Test
  public void handleMessage_setVideoOutputSameBufferRendererAfterFirstFrame_renotifiesCallbacks()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(renderer, /* count= */ 4, /* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    shadowOf(testMainLooper).idle();

    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(bufferRenderer, bufferRenderer);
    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(2);
  }

  @Test
  public void handleMessage_setVideoOutputFromBufferRendererToSurfaceWhileStarted_joinsSeamlessly()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setAllowedJoiningTimeMs(5_000), fakeClock);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    // Switch live from VideoDecoderOutputBufferRenderer to Surface while started: join() keeps
    // isReady() true during joining, and outputReplacedSeamlessly = true force-renders the next
    // frame immediately even when earlyUs (180_000) exceeds the 30_000 us threshold.
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    boolean readyImmediatelyAfterSwitch = renderer.isReady();
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyImmediatelyAfterSwitch).isTrue();
    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(surface);
    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(bufferRenderer, surface)
        .inOrder();
    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(2);
  }

  @Test
  public void handleMessage_setVideoOutputNullWithExtensionCodec_detachesOutputAndClearsReadyState()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(renderer, /* count= */ 4, /* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    Object outputBeforeClear = extensionCodecAdapter.lastOutput;
    boolean readyBeforeClear = renderer.isReady();

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);

    assertThat(outputBeforeClear).isSameInstanceAs(bufferRenderer);
    assertThat(readyBeforeClear).isTrue();
    assertThat(extensionCodecAdapter.lastOutput).isNull();
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      render_withVideoDecoderOutputBufferRendererWhenUnifiedFlagDisabled_treatsOutputAsNull()
          throws Exception {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<VideoDecoderOutputBuffer> renderedBuffers = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedBuffers.add(outputBuffer);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderedBuffers).isEmpty();
    assertThat(eventListener.renderedFirstFrameOutputs).isEmpty();
    assertThat(eventListener.getLatestEnabledDecoderCounters().decoderInitCount).isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(0);
  }

  @Test
  public void
      handleMessage_transferResourcesWithVideoDecoderOutputBufferRenderer_transfersOutputToTargetRenderer()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestVideoExtensionCodecAdapter sourceCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    TestVideoExtensionCodecAdapter targetCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer sourceRenderer = createRenderer(sourceCodecAdapter, fakeClock);
    MediaCodecVideoRenderer targetRenderer = createRenderer(targetCodecAdapter, fakeClock);
    List<VideoDecoderOutputBuffer> renderedBuffers = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedBuffers.add(outputBuffer);
          outputBuffer.release();
        };
    sourceRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream sourceSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(sourceRenderer, VIDEO_AV1_720P, sourceSampleStream);
    for (int i = 0; i < 4; i++) {
      sourceRenderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    @Nullable Object sourceOutputBeforeTransfer = sourceCodecAdapter.lastOutput;

    sourceRenderer.handleMessage(Renderer.MSG_TRANSFER_RESOURCES, targetRenderer);
    FakeSampleStream targetSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(targetRenderer, VIDEO_AV1_720P, targetSampleStream);
    fakeClock.advanceTime(/* timeDiffMs= */ 20);
    for (int i = 0; i < 4; i++) {
      targetRenderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(sourceOutputBeforeTransfer).isSameInstanceAs(bufferRenderer);
    assertThat(sourceCodecAdapter.lastOutput).isNull();
    assertThat(renderedBuffers).hasSize(2);
    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(bufferRenderer, bufferRenderer);
  }

  @Test
  public void
      handleMessage_setVideoOutputWithVideoSinkAndBufferRenderer_clearsSinkSurfaceAndPreservesOutputForTransfer()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter sourceCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    TestVideoExtensionCodecAdapter targetCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer sourceRenderer =
        createRenderer(
            sourceCodecAdapter, builder -> builder.setVideoSink(fakeVideoSink), fakeClock);
    MediaCodecVideoRenderer targetRenderer = createRenderer(targetCodecAdapter, fakeClock);
    VideoDecoderOutputBufferRenderer firstBufferRenderer = VideoDecoderOutputBuffer::release;
    VideoDecoderOutputBufferRenderer secondBufferRenderer = VideoDecoderOutputBuffer::release;
    sourceRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream sourceSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    FakeSampleStream targetSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(sourceRenderer, VIDEO_AV1_720P, sourceSampleStream);
    sourceRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    sourceRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, firstBufferRenderer);
    sourceRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, secondBufferRenderer);
    @Nullable Object sourceOutputBeforeTransfer = sourceCodecAdapter.lastOutput;
    sourceRenderer.handleMessage(Renderer.MSG_TRANSFER_RESOURCES, targetRenderer);
    enableAndStartRenderer(targetRenderer, VIDEO_AV1_720P, targetSampleStream);
    targetRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    assertThat(fakeVideoSink.clearOutputSurfaceInfoCallCount).isEqualTo(1);
    assertThat(sourceOutputBeforeTransfer).isSameInstanceAs(sinkInputSurface);
    assertThat(targetCodecAdapter.lastOutput).isSameInstanceAs(secondBufferRenderer);
  }

  @Test
  public void
      handleMessage_setVideoOutputOnPlatformCodecBetweenNullAndBufferRenderer_doesNotReinitializeOrUpdateSurface()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    int[] createAdapterCallCount = new int[1];
    int[] setOutputSurfaceCallCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              createAdapterCallCount[0]++;
              return new ForwardingMediaCodecAdapter(
                  codecAdapterFactory.createAdapter(configuration)) {
                @Override
                public void setOutputSurface(Surface surface) {
                  setOutputSurfaceCallCount[0]++;
                  super.setOutputSurface(surface);
                }
              };
            },
            mediaCodecSelector);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_H264, fakeSampleStream);
    for (int i = 0; i < 4 && createAdapterCallCount[0] == 0; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);

    assertThat(createAdapterCallCount[0]).isEqualTo(1);
    assertThat(setOutputSurfaceCallCount[0]).isEqualTo(0);
  }

  @Test
  public void
      render_withOutputBufferRendererAcrossPlatformCodecTransitionWhilePaused_rendersResumedExtensionFirstFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder resumedAv1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1);
    resumedAv1Decoder.setHoldOutputBuffers(true);
    int[] extensionInitCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              if (configuration.codecInfo instanceof ExtensionCodecInfo) {
                extensionInitCount[0]++;
                FakePassthroughVideoDecoder decoder =
                    extensionInitCount[0] == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : resumedAv1Decoder;
                return new TestVideoExtensionCodecAdapter(
                    configuration.format, decoder, configuration.surface);
              }
              return codecAdapterFactory.createAdapter(configuration);
            },
            createAv1ExtensionAndPlatformSelector(),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                format(h264Format),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    // Advance past the H.264 sample while no usable output is attached so it is skipped, then
    // pause the renderer before transitioning back to the AV1 extension codec.
    fakeClock.advanceTime(/* timeDiffMs= */ 80);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 80_000L, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    renderer.stop();

    // Initialize the resumed AV1 extension codec at 1920x1080 while holding its output buffer, then
    // re-send the same bufferRenderer to verify that H.264's onCodecInitialized reset
    // reportedVideoSize and haveReportedFirstFrameRenderedForCurrentSurface (no premature 1280x720
    // re-notification before the 1920x1080 frame at 80_000L renders).
    fakeSampleStream.append(
        ImmutableList.of(
            format(VIDEO_AV1),
            oneByteSample(/* timeUs= */ 80_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 80_000L, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    shadowOf(testMainLooper).idle();
    resumedAv1Decoder.setHoldOutputBuffers(false);
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ 80_000L, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    assertThat(extensionInitCount[0]).isEqualTo(2);
    assertThat(renderedTimestampsUs).containsExactly(0L, 80_000L).inOrder();
    assertThat(eventListener.reportedVideoSizes)
        .containsExactly(new VideoSize(1280, 720), new VideoSize(1920, 1080))
        .inOrder();
    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(bufferRenderer, bufferRenderer);
  }

  @Test
  public void
      render_withOutputBufferRendererAcrossPlatformCodecTransitionWhileStarted_joinsAndStaysReady()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            createAv1ExtensionAndPlatformAdapterFactory(
                new FakePassthroughVideoDecoder(VIDEO_AV1_720P)),
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    List<Boolean> readyStatesAcrossTransition = new ArrayList<>();

    // Append H.264 format and sample 40_000 at positionUs = 80_000 so the transition to H.264
    // happens live while started and sample 40_000 is late and skipped without setting
    // frameReadyWithoutSurface; joining in updateCodecOutputBufferRendererState must keep isReady()
    // true throughout.
    fakeSampleStream.append(
        ImmutableList.of(
            format(h264Format), oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 80);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 80_000L, msToUs(fakeClock.elapsedRealtime()));
      readyStatesAcrossTransition.add(renderer.isReady());
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.initializedDecoderNames).hasSize(2);
    assertThat(readyStatesAcrossTransition).containsExactly(true, true, true, true, true, true);
  }

  @Test
  public void
      handleMessage_setVideoOutputClearingAndReattachingBufferRendererWhilePaused_rendersHeldFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    // Clear the output while paused, decode the next frame while cleared (holding it and reporting
    // isReady() == true without skipping), then re-attach the output buffer renderer and verify the
    // held frame is rendered.
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    boolean readyWhileOutputCleared = renderer.isReady();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyWhileOutputCleared).isTrue();
    assertThat(renderedTimestampsUs).containsExactly(0L, 40_000L).inOrder();
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(0);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(2);
  }

  @Test
  public void disableAndEnable_withExtensionCodec_detachesAndReattachesOutput() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    TestVideoExtensionCodecAdapter fakeExtensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1, new FakePassthroughVideoDecoder(VIDEO_AV1), /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(fakeExtensionCodecAdapter);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1, ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1, fakeSampleStream);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    @Nullable Object outputWhileEnabled = fakeExtensionCodecAdapter.lastOutput;
    Surface replacementSurface = createValidSurface(/* texName= */ 1);

    renderer.stop();
    renderer.disable();
    @Nullable Object outputAfterDisable = fakeExtensionCodecAdapter.lastOutput;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, replacementSurface);
    @Nullable Object outputWhileDisabledAfterSetVideoOutput = fakeExtensionCodecAdapter.lastOutput;
    enableAndStartRenderer(renderer, VIDEO_AV1, fakeSampleStream);
    @Nullable Object outputAfterReenable = fakeExtensionCodecAdapter.lastOutput;

    assertThat(outputWhileEnabled).isSameInstanceAs(surface);
    assertThat(outputAfterDisable).isNull();
    assertThat(outputWhileDisabledAfterSetVideoOutput).isNull();
    assertThat(outputAfterReenable).isSameInstanceAs(replacementSurface);
  }

  @Test
  public void
      render_withExtensionCodecAndMismatchedOutputBufferMode_skipsBufferWithoutConsumingFirstFrameState()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder modeSwitchingDecoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    modeSwitchingDecoder.setOutputBufferCustomizer((inputBuffer, outputBuffer) -> {
      if (inputBuffer.timeUs < 20_000) {
        outputBuffer.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
      }
    });
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, modeSwitchingDecoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    // Keep renderer in STATE_ENABLED (paused) to verify that the mismatched-mode buffer at
    // timeUs=0 does not consume VideoFrameReleaseControl's first-frame release state.
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderedTimestampsUs).containsExactly(20_000L);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().droppedBufferCount).isEqualTo(0);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(1);
  }

  @Test
  public void
      render_withExtensionCodecAndVideoSinkAndMismatchedOutputBufferMode_skipsBufferAndUpdatesCounters()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setOutputBufferCustomizer((inputBuffer, outputBuffer) -> {
      if (inputBuffer.timeUs == 0) {
        outputBuffer.mode = C.VIDEO_OUTPUT_MODE_NONE;
      }
    });
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setVideoSink(fakeVideoSink), fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, /* flags= */ 0),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ i * 20_000L, msToUs(fakeClock.elapsedRealtime()));
      fakeClock.advanceTime(/* timeDiffMs= */ 20);
      shadowOf(testMainLooper).idle();
    }

    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(sinkInputSurface);
    assertThat(fakeVideoSink.handledFrameTimestampsUs).containsExactly(40_000L);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().droppedBufferCount).isEqualTo(0);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(1);
  }

  @Test
  public void disableAndEnable_withExtensionCodecAndVideoSink_detachesAndReattachesSinkSurface()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setVideoSink(fakeVideoSink), fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    renderer.stop();
    renderer.disable();
    @Nullable Object outputAfterDisable = extensionCodecAdapter.lastOutput;
    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME))));

    assertThat(outputAfterDisable).isNull();
    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(sinkInputSurface);
  }

  @Test
  public void
      render_withExtensionCodecAndSampleDependenciesBeforeStartPosition_skipsNonReferenceAndRendersLastFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(1_000_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(fakeTimeline);
    MediaSource.MediaPeriodId fakeMediaPeriodId =
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0));
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                // Second buffer has BUFFER_FLAG_NOT_DEPENDED_ON and is > 500ms before period end
                // (1_000_000us), so it is discarded at input before the decoder.
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                // Third buffer is explicitly marked with BUFFER_FLAG_LAST_SAMPLE, so it is neither
                // discarded at input nor added to skippedCodecBufferTimestamps, and is rendered.
                oneByteSample(
                    /* timeUs= */ 450_000,
                    C.BUFFER_FLAG_NOT_DEPENDED_ON | C.BUFFER_FLAG_LAST_SAMPLE),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        fakeSampleStream,
        /* startPositionUs= */ 480_000,
        fakeMediaPeriodId);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ 480_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(passthroughAv1Decoder.queuedSampleLastFlags).containsExactly(false, true).inOrder();
    assertThat(renderer.hasReadStreamToEnd()).isTrue();
    assertThat(renderedTimestampsUs).containsExactly(450_000L);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedInputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(1);
  }

  @Test
  public void
      render_withExtensionCodecAndUnknownDurationBeforeStartPosition_skipsPreStartFramesWithoutMarkingLastSample()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME)));

    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        fakeSampleStream,
        /* startPositionUs= */ 200_000,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(passthroughAv1Decoder.queuedSampleLastFlags)
        .containsExactly(false, false, false)
        .inOrder();
    assertThat(renderedTimestampsUs).containsExactly(200_000L);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(2);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(1);
  }

  @Test
  public void
      render_withExtensionCodecAndNonReferenceAv1FrameBeforeStartPosition_retainsHeaderAndDiscardsFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format av1Format1080p = createAv1Format(/* bitdepth= */ 10);
    // Buffer capacity (allocated on demand for the 68-byte first sample) is much smaller than
    // codecMaxValues.inputSize for 1080p (777,600), exercising the ExtensionCodecAdapter branch of
    // hasSpaceForNextFrame.
    FakePassthroughVideoDecoder recordingDecoder = new FakePassthroughVideoDecoder(av1Format1080p);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(av1Format1080p, recordingDecoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    byte[] sequenceHeader =
        createByteArray(
            0x0A, 0x0E, 0x00, 0x00, 0x00, 0x24, 0xC6, 0xAB, 0xDF, 0x3E, 0xFE, 0x24, 0x04, 0x04,
            0x04, 0x10);
    byte[] dependedOnFrame =
        createByteArray(
            0x32, 0x32, 0x10, 0x00, 0xC8, 0xC6, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x12, 0x03,
            0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x34, 0x88, 0x82, 0x3E, 0x0D, 0x3E, 0xC2, 0x98,
            0x91, 0x6A, 0x5C, 0x80, 0x03, 0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x48, 0x35, 0x54,
            0xD8, 0x9D, 0x6C, 0x37, 0xD3, 0x4C, 0x4E, 0xD4, 0x6F, 0xF4);
    byte[] notDependedOnFrame =
        createByteArray(
            0x32, 0x1A, 0x30, 0xC0, 0x00, 0x1D, 0x66, 0x68, 0x46, 0xC9, 0x38, 0x00, 0x60, 0x10,
            0x20, 0x80, 0x20, 0x00, 0x00, 0x01, 0x8B, 0x7A, 0x87, 0xF9, 0xAA, 0x2D, 0x0F, 0x2C);
    byte[] keyFrameWithSequenceHeader = Bytes.concat(sequenceHeader, dependedOnFrame);
    byte[] nonReferenceFrameWithSequenceHeader = Bytes.concat(sequenceHeader, notDependedOnFrame);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setSeekable(true)
                .setDynamic(false)
                .setDurationUs(1_000_000)
                .build());
    renderer.setTimeline(fakeTimeline);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            av1Format1080p,
            ImmutableList.of(
                sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, keyFrameWithSequenceHeader),
                sample(/* timeUs= */ 20_000, /* flags= */ 0, nonReferenceFrameWithSequenceHeader),
                sample(/* timeUs= */ 40_000, /* flags= */ 0, dependedOnFrame),
                sample(/* timeUs= */ 60_000, /* flags= */ 0, dependedOnFrame),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(
        renderer,
        av1Format1080p,
        fakeSampleStream,
        /* startPositionUs= */ 40_000,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ 40_000 + i * 20_000L, msToUs(fakeClock.elapsedRealtime()));
      fakeClock.advanceTime(/* timeDiffMs= */ 20);
      shadowOf(testMainLooper).idle();
    }

    // Sample at 20_000 is partially discarded (retaining the 16-byte sequenceHeader), which is
    // prepended to the 52-byte sample at 40_000 (16 + 52 = 68 bytes).
    assertThat(recordingDecoder.queuedSampleSizes)
        .containsExactly(
            keyFrameWithSequenceHeader.length,
            sequenceHeader.length + dependedOnFrame.length,
            dependedOnFrame.length)
        .inOrder();
  }

  @Test
  @Config(sdk = {34, 37})
  public void render_withExtensionCodecAndAv1HdrMetadata_doesNotRewriteInBandT35Metadata()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format av1Format10Bit = createAv1Format(/* bitdepth= */ 10);
    FakePassthroughVideoDecoder recordingDecoder = new FakePassthroughVideoDecoder(av1Format10Bit);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(av1Format10Bit, recordingDecoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    byte[] inBandHagcObu = createAv1HagcMetadataObu();
    byte[] hdr10PlusSupplementalData =
        createByteArray(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x00, 0x01);
    byte[] sampleWithSupplementalData =
        ByteBuffer.allocate(4 + inBandHagcObu.length + hdr10PlusSupplementalData.length)
            .putInt(inBandHagcObu.length)
            .put(inBandHagcObu)
            .put(hdr10PlusSupplementalData)
            .array();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            av1Format10Bit,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    sampleWithSupplementalData),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, av1Format10Bit, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(recordingDecoder.lastQueuedSupplementalDataBytes)
        .isEqualTo(hdr10PlusSupplementalData);
    assertThat(recordingDecoder.lastQueuedSampleBytes).isEqualTo(inBandHagcObu);
  }

  @Test
  public void handleInputBufferSupplementalData_withExtensionCodec_leavesSupplementalDataUntouched()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format av1Format10Bit = createAv1Format(/* bitdepth= */ 10);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            av1Format10Bit, new FakePassthroughVideoDecoder(av1Format10Bit), /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            av1Format10Bit,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, av1Format10Bit, fakeSampleStream);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    ByteBuffer supplementalData =
        ByteBuffer.wrap(
            createByteArray(0xFF, 0xFF, 0xB5, 0x00, 0x90, 0x00, 0x01, 0x0A, 0x0B, 0x0C));
    supplementalData.position(2);
    buffer.supplementalData = supplementalData;

    renderer.handleInputBufferSupplementalData(buffer);

    assertThat(supplementalData.position()).isEqualTo(2);
  }

  @Test
  @Config(minSdk = 30)
  public void
      render_withExtensionCodecAndSkippedPreStartBuffers_updatesFrameRateEstimatorAndSkippedCount()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format av1FormatNoFrameRate = VIDEO_AV1_720P.buildUpon().setFrameRate(Format.NO_VALUE).build();
    List<Float> recordedSurfaceFrameRates = new ArrayList<>();
    Surface recordingSurface = createFrameRateRecordingSurface(recordedSurfaceFrameRates);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(av1FormatNoFrameRate);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            av1FormatNoFrameRate, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, recordingSurface);
    // 19 samples (0..18) at 25_000us (40 fps) intervals, all < startPositionUs (500_000us) and
    // > 100_000us before period end (2_000_000us):
    // - 10 even-indexed keyframes are queued into the decoder and skipped (added to
    //   skippedCodecBufferTimestamps).
    // - 9 odd-indexed non-depended-on samples are discarded at input (added to
    //   discardedDecoderInputBufferTimestamps).
    // Appending END_OF_STREAM_ITEM afterward triggers renderToEndOfStream(), which flushes all 19
    // interleaved timestamps into frameRateEstimator (reaching the 15-interval sync threshold and
    // updating the output surface frame rate to 40f).
    List<FakeSampleStream.FakeSampleStreamItem> samples = new ArrayList<>();
    for (int i = 0; i < 19; i++) {
      int flags = (i % 2 == 0) ? C.BUFFER_FLAG_KEY_FRAME : C.BUFFER_FLAG_NOT_DEPENDED_ON;
      samples.add(oneByteSample(/* timeUs= */ i * 25_000L, flags));
    }
    FakeSampleStream fakeSampleStream = createFakeSampleStream(av1FormatNoFrameRate, samples);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(2_000_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(fakeTimeline);
    long startPositionUs = 500_000;

    enableAndStartRenderer(
        renderer,
        av1FormatNoFrameRate,
        fakeSampleStream,
        startPositionUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    for (int i = 0; i < 20; i++) {
      renderer.render(startPositionUs, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    fakeSampleStream.append(ImmutableList.of(END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 5; i++) {
      renderer.render(startPositionUs, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    DecoderCounters decoderCounters = eventListener.getLatestEnabledDecoderCounters();
    assertThat(decoderCounters.skippedInputBufferCount).isEqualTo(9);
    assertThat(decoderCounters.skippedOutputBufferCount).isEqualTo(10);
    assertThat(recordedSurfaceFrameRates).hasSize(1);
    assertThat(recordedSurfaceFrameRates.get(0)).isWithin(0.01f).of(40f);
  }

  @Test
  @Config(minSdk = 34)
  public void
      render_withExtensionCodecAndDecodeOnlyFlagEnabled_skipsPreStartBuffersAndUpdatesBuffersInCodecCount()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Flags.enableFlag(Flags.FLAG_ENABLE_MEDIACODEC_BUFFER_DECODE_ONLY);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setOutputBufferCustomizer((inputBuffer, outputBuffer) -> {
      if (inputBuffer.timeUs == 85_000) {
        outputBuffer.shouldBeSkipped = true;
      }
    });
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeTimeline timeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0)
                .setDurationUs(2_000_000L)
                .build());
    renderer.setTimeline(timeline);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 20_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0)));

    // At positionUs=20_000, sample 0 (< startPositionUs) is skipped in the decoder without
    // BUFFER_FLAG_DECODE_ONLY, sample 20_000 is rendered, and sample 80_000 is dequeued and held in
    // MediaCodecRenderer (hasOutputBuffer() == true) because positionUs equals initialPositionUs.
    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        fakeSampleStream,
        /* startPositionUs= */ 20_000,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    // Queue samples 85_000 (shouldBeSkipped = true) and 90_000 while sample 80_000 is held so
    // drainOutputBuffer does not harvest 85_000 yet; maybeDropBuffersToKeyframe must harvest it
    // before counting dropped decoder buffers and flushing.
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 85_000, /* flags= */ 0),
            oneByteSample(/* timeUs= */ 90_000, /* flags= */ 0)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    for (int i = 0; i < 2; i++) {
      renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    // Append a non-keyframe at 95_000 followed by keyframe 1_020_000 and jump to
    // positionUs=1_020_000 so sample 80_000 triggers maybeDropBuffersToKeyframe, harvesting sample
    // 85_000 as skipped, skipping sample 95_000 in the source (1), and dropping the 2 remaining
    // buffers in the codec (80_000 and 90_000).
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 95_000, /* flags= */ 0),
            oneByteSample(/* timeUs= */ 1_020_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    for (int i = 0; i < 8; i++) {
      renderer.render(/* positionUs= */ 1_020_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderedTimestampsUs).containsExactly(20_000L, 1_020_000L).inOrder();
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(2);
    assertThat(eventListener.getLatestEnabledDecoderCounters().droppedBufferCount).isEqualTo(3);
    assertThat(eventListener.getLatestEnabledDecoderCounters().droppedToKeyframeCount).isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(2);
  }

  @Test
  @Config(minSdk = 30)
  public void resetPosition_withExtensionCodecAndAllowSkippingMediaCodecFlush_flushesCodec()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format av1FormatNoFrameRate = VIDEO_AV1.buildUpon().setFrameRate(Format.NO_VALUE).build();
    List<Float> recordedSurfaceFrameRates = new ArrayList<>();
    Surface recordingSurface = createFrameRateRecordingSurface(recordedSurfaceFrameRates);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(av1FormatNoFrameRate);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            av1FormatNoFrameRate, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, recordingSurface);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(2_000_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(fakeTimeline);
    List<FakeSampleStream.FakeSampleStreamItem> preStartSamples = new ArrayList<>();
    for (int i = 0; i < 19; i++) {
      preStartSamples.add(oneByteSample(/* timeUs= */ i * 25_000L, C.BUFFER_FLAG_KEY_FRAME));
    }
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(av1FormatNoFrameRate, preStartSamples);
    enableAndStartRenderer(
        renderer,
        av1FormatNoFrameRate,
        fakeSampleStream,
        /* startPositionUs= */ 500_000,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    for (int i = 0; i < 20; i++) {
      renderer.render(/* positionUs= */ 500_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder().setAllowSkippingMediaCodecFlush(true).build();
    renderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);

    // Resetting to a keyframe must still flush an ExtensionCodecAdapter (and clear pending
    // skippedCodecBufferTimestamps) even when allowSkippingMediaCodecFlush is true.
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 600_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeSampleStream.seekToUs(/* positionUs= */ 600_000, /* allowTimeBeyondBuffer= */ true);
    renderer.resetPosition(/* positionUs= */ 600_000, /* sampleStreamIsResetToKeyFrame= */ true);
    renderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, null);
    renderer.setCurrentStreamFinal();
    fakeClock.advanceTime(/* timeDiffMs= */ 100);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 600_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(passthroughAv1Decoder.flushCount).isEqualTo(1);
    assertThat(recordedSurfaceFrameRates).isEmpty();
  }

  @Test
  public void render_withExtensionCodecAndEarlyThreshold_capsAt30MsAndRespectsLowerThreshold(
      @TestParameter({"50000", "10000"}) long configuredThresholdUs) throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setEarlySchedulingThresholdUs(configuredThresholdUs),
            fakeClock);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 60_000, /* flags= */ 0),
                END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    long effectiveThresholdUs = Math.min(configuredThresholdUs, 30_000L);
    long heldPositionMs = (60_000L - (effectiveThresholdUs + 10_000L)) / 1_000L;
    long releasePositionMs = (60_000L - (effectiveThresholdUs - 5_000L)) / 1_000L;

    fakeClock.advanceTime(heldPositionMs);
    renderer.render(msToUs(heldPositionMs), msToUs(fakeClock.elapsedRealtime()));
    ImmutableList<Long> timestampsWhileEarly =
        ImmutableList.copyOf(bufferRenderer.renderedTimestampsUs);
    fakeClock.advanceTime(releasePositionMs - heldPositionMs);
    renderer.render(msToUs(releasePositionMs), msToUs(fakeClock.elapsedRealtime()));

    assertThat(timestampsWhileEarly).containsExactly(0L);
    assertThat(bufferRenderer.renderedTimestampsUs).containsExactly(0L, 60_000L).inOrder();
  }

  @Test
  public void
      enable_withMayRenderStartOfStreamFalseAndOutputBufferRenderer_staysNotReadyUntilAllowed()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    boolean readyBeforeAllowed = renderer.isReady();
    ImmutableList<Long> renderedBeforeAllowed = ImmutableList.copyOf(renderedTimestampsUs);
    renderer.enableMayRenderStartOfStream();
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyBeforeAllowed).isFalse();
    assertThat(renderedBeforeAllowed).isEmpty();
    assertThat(renderer.isReady()).isTrue();
    assertThat(renderedTimestampsUs).containsExactly(0L);
  }

  @Test
  public void
      render_fromPlatformToExtensionCodecWithOutputBufferRendererWhileStarted_joinsAndStaysReady()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            createAv1ExtensionAndPlatformAdapterFactory(passthroughAv1Decoder),
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            h264Format,
            ImmutableList.of(oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, h264Format, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    boolean readyDuringPlatformCodec = renderer.isReady();
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    // Hold AV1 output buffers initially so AV1 initializes before any AV1 frame is decoded;
    // updateCodecOutputBufferRendererState must join(renderNextFrameImmediately=false) so isReady()
    // stays true across the transition while holding early sample 200_000 until its presentation
    // time.
    fakeSampleStream.append(
        ImmutableList.of(
            format(VIDEO_AV1_720P),
            oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    boolean readyAfterAv1InitBeforeFirstFrame = renderer.isReady();
    passthroughAv1Decoder.setHoldOutputBuffers(false);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    boolean readyWhileEarlyFrameHeld = renderer.isReady();
    ImmutableList<Long> renderedWhileEarlyFrameHeld = ImmutableList.copyOf(renderedTimestampsUs);
    fakeClock.advanceTime(/* timeDiffMs= */ 150);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 200_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.initializedDecoderNames).hasSize(2);
    assertThat(readyDuringPlatformCodec).isTrue();
    assertThat(readyAfterAv1InitBeforeFirstFrame).isTrue();
    assertThat(readyWhileEarlyFrameHeld).isTrue();
    assertThat(renderedWhileEarlyFrameHeld).isEmpty();
    assertThat(renderedTimestampsUs).containsExactly(200_000L);
  }

  @Test
  public void
      render_fromPlatformToExtensionCodecWithOutputBufferRendererWhilePaused_resetsReadyUntilExtensionRenders()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            createAv1ExtensionAndPlatformAdapterFactory(passthroughAv1Decoder),
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            h264Format,
            ImmutableList.of(oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, h264Format, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    boolean readyDuringPlatformCodec = renderer.isReady();
    fakeClock.advanceTime(/* timeDiffMs= */ 60);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 60_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    renderer.stop();

    fakeSampleStream.append(
        ImmutableList.of(
            format(VIDEO_AV1_720P),
            oneByteSample(/* timeUs= */ 60_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 60_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
    boolean readyWhilePausedBeforeAv1Frame = renderer.isReady();
    passthroughAv1Decoder.setHoldOutputBuffers(false);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 60_000, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.initializedDecoderNames).hasSize(2);
    assertThat(readyDuringPlatformCodec).isTrue();
    assertThat(readyWhilePausedBeforeAv1Frame).isFalse();
    assertThat(renderer.isReady()).isTrue();
    assertThat(renderedTimestampsUs).containsExactly(60_000L);
  }

  @Test
  public void render_acrossTwoExtensionCodecsWithOutputBufferRenderer_preservesRenderedState()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format firstAv1Format =
        VIDEO_AV1_720P.buildUpon().setInitializationData(ImmutableList.of(new byte[] {1})).build();
    Format secondAv1Format =
        VIDEO_AV1_720P.buildUpon().setInitializationData(ImmutableList.of(new byte[] {2})).build();
    int[] createAdapterCallCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRenderer(configuration -> {
          createAdapterCallCount[0]++;
          FakePassthroughVideoDecoder decoder =
              new FakePassthroughVideoDecoder(configuration.format);
          if (createAdapterCallCount[0] == 2) {
            decoder.setHoldOutputBuffers(true);
          }
          return new TestVideoExtensionCodecAdapter(
              configuration.format, decoder, configuration.surface);
        });
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            firstAv1Format,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {firstAv1Format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    fakeSampleStream.append(
        ImmutableList.of(
            format(secondAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(createAdapterCallCount[0]).isEqualTo(2);
    assertThat(renderer.isReady()).isTrue();
  }

  @Test
  public void
      handleMessage_setVideoOutputFromSurfaceToBufferRendererWhileStarted_joinsSeamlesslyAndRendersToBufferRenderer()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_AV1_720P, passthroughAv1Decoder, surface);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setAllowedJoiningTimeMs(5_000), fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    eventListener.clear();
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    shadowOf(testMainLooper).idle();
    boolean readyImmediatelyAfterSwitch = renderer.isReady();
    ImmutableList<VideoSize> reportedSizesImmediatelyAfterSwitch =
        ImmutableList.copyOf(eventListener.reportedVideoSizes);
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(readyImmediatelyAfterSwitch).isTrue();
    assertThat(reportedSizesImmediatelyAfterSwitch).containsExactly(new VideoSize(1280, 720));
    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(bufferRenderer);
    assertThat(renderedTimestampsUs).containsExactly(200_000L);
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(bufferRenderer);
    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(1);
  }

  @Test
  public void render_fromExtensionToPlatformCodec_restoresDefault50MsEarlySchedulingThreshold()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_AV1_720P, passthroughAv1Decoder, surface);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                configuration.codecInfo instanceof ExtensionCodecInfo
                    ? extensionCodecAdapter
                    : codecAdapterFactory.createAdapter(configuration),
            createAv1ExtensionAndPlatformSelector(),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 70_000, /* flags= */ 0)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    // At positionUs = 20_000, sample 70_000 has earlyUs = 50_000 (> 30_000 extension cap): held.
    fakeClock.advanceTime(/* timeDiffMs= */ 20);
    renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
    int renderedCountDuringEarlyAv1Check = extensionCodecAdapter.renderedSurfaceBufferCount;
    // Advance to positionUs = 45_000 (earlyUs = 25_000 <= 30_000): sample 70_000 renders.
    fakeClock.advanceTime(/* timeDiffMs= */ 25);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 45_000, msToUs(fakeClock.elapsedRealtime()));

    // Transition from AV1 extension (30ms cap) to H.264 platform codec (50ms default): sample
    // 135_000 at positionUs = 90_000 has earlyUs = 45_000 (> 30_000 cap, <= 50_000 default) and
    // must be scheduled immediately rather than held.
    fakeSampleStream.append(
        ImmutableList.of(
            format(VIDEO_H264_720P),
            oneByteSample(/* timeUs= */ 90_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 135_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 45);
    renderFrames(
        renderer, /* count= */ 12, /* positionUs= */ 90_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderedCountDuringEarlyAv1Check).isEqualTo(1);
    assertThat(extensionCodecAdapter.renderedSurfaceBufferCount).isEqualTo(2);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(4);
  }

  @Test
  public void
      disableAndEnable_withReusedExtensionCodecAndOutputBufferRenderer_rendersNewStreamFirstFrame()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    int[] createAdapterCallCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRenderer(configuration -> {
          createAdapterCallCount[0]++;
          return extensionCodecAdapter;
        });
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream firstStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    FakeSampleStream secondStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, firstStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    renderer.stop();
    renderer.disable();
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        secondStream,
        /* positionUs= */ 10_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 10_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 10_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(createAdapterCallCount[0]).isEqualTo(1);
    assertThat(renderer.isReady()).isTrue();
    assertThat(renderedTimestampsUs).containsExactly(0L, 10_000L).inOrder();
    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(bufferRenderer, bufferRenderer);
  }

  @Test
  public void render_withExtensionCodecAndVideoSinkAndPreStartLastSample_forwardsLastSampleToSink()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setHoldOutputBuffers(true);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setVideoSink(fakeVideoSink), fakeClock);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(250_000)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    renderer.setTimeline(fakeTimeline);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(
                    /* timeUs= */ 180_000, C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_LAST_SAMPLE),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        fakeSampleStream,
        /* startPositionUs= */ 200_000,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    passthroughAv1Decoder.setHoldOutputBuffers(false);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 200_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(passthroughAv1Decoder.queuedSampleLastFlags).containsExactly(false, true).inOrder();
    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(sinkInputSurface);
    assertThat(fakeVideoSink.handledFrameTimestampsUs).containsExactly(180_000L);
  }

  @Test
  public void
      render_withOutputBufferRendererWhenPrimaryCodecFailsMidStreamAndFallsBackToExtension_preservesRenderedState()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format vp9Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VP9)
            .setWidth(1280)
            .setHeight(720)
            .build();
    MediaCodecInfo failingVp9HardwareCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.vp9.hardware.decoder",
            /* mimeType= */ MimeTypes.VIDEO_VP9,
            /* codecMimeType= */ MimeTypes.VIDEO_VP9,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo vp9ExtensionCodecInfo =
        ExtensionCodecInfo.newInstance("c2.media3.vp9.vpx.decoder", MimeTypes.VIDEO_VP9);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              if (configuration.codecInfo.name.equals("c2.android.vp9.hardware.decoder")) {
                throw new IOException("Hardware VP9 decoder initialization failed");
              }
              return new TestVideoExtensionCodecAdapter(
                  configuration.format,
                  new FakePassthroughVideoDecoder(configuration.format),
                  configuration.surface);
            },
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
              if (requiresSecureDecoder || requiresTunnelingDecoder) {
                return ImmutableList.of();
              }
              if (Objects.equals(mimeType, MimeTypes.VIDEO_AV1)) {
                return ImmutableList.of(AV1_EXTENSION_CODEC_INFO);
              }
              if (Objects.equals(mimeType, MimeTypes.VIDEO_VP9)) {
                return ImmutableList.of(failingVp9HardwareCodecInfo, vp9ExtensionCodecInfo);
              }
              return ImmutableList.of();
            },
            builder -> builder.setEnableDecoderFallback(true),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    fakeSampleStream.append(
        ImmutableList.of(
            format(vp9Format),
            oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeClock.advanceTime(/* timeDiffMs= */ 20);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    boolean readyBeforeFallbackFrameDue = renderer.isReady();
    ImmutableList<Long> renderedBeforeFallbackFrameDue = ImmutableList.copyOf(renderedTimestampsUs);
    fakeClock.advanceTime(/* timeDiffMs= */ 180);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 200_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(eventListener.codecErrors).hasSize(1);
    assertThat(eventListener.codecErrors.get(0)).isInstanceOf(DecoderInitializationException.class);
    assertThat(readyBeforeFallbackFrameDue).isTrue();
    assertThat(renderedBeforeFallbackFrameDue).containsExactly(0L);
    assertThat(renderedTimestampsUs).containsExactly(0L, 200_000L).inOrder();
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(bufferRenderer);
    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(1);
  }

  @Test
  @Config(sdk = 34)
  public void render_fromExtensionToPlatformCodecWithoutSurfaceSupport_clearsReportedVideoSize()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            createAv1ExtensionAndPlatformAdapterFactory(
                new FakePassthroughVideoDecoder(VIDEO_AV1_720P)),
            createAv1ExtensionAndPlatformSelector(),
            builder -> {},
            new FakeClock(/* initialTimeMs= */ 0));
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    fakeSampleStream.append(
        ImmutableList.of(
            format(h264Format), oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 40_000, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    shadowOf(testMainLooper).idle();

    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(1);
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(bufferRenderer);
  }

  @Test
  @Config(sdk = 34)
  public void
      render_fromPlatformCodecWithoutSurfaceSupportToExtensionWhileStarted_joinsAndStaysReady()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    passthroughAv1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            createAv1ExtensionAndPlatformAdapterFactory(passthroughAv1Decoder),
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream h264SampleStream =
        createFakeSampleStream(
            h264Format,
            ImmutableList.of(oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, h264Format, h264SampleStream);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    boolean readyWhileWaitingForPlatformCodecSurface = renderer.isReady();

    FakeSampleStream av1SampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    renderer.replaceStream(
        new Format[] {VIDEO_AV1_720P},
        av1SampleStream,
        /* startPositionUs= */ 50_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    boolean readyAfterAv1InitBeforeFirstFrame = renderer.isReady();
    passthroughAv1Decoder.setHoldOutputBuffers(false);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    ImmutableList<Long> renderedWhileEarlyFrameHeld = ImmutableList.copyOf(renderedTimestampsUs);
    fakeClock.advanceTime(/* timeDiffMs= */ 150);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 200_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyWhileWaitingForPlatformCodecSurface).isTrue();
    assertThat(readyAfterAv1InitBeforeFirstFrame).isTrue();
    assertThat(renderedWhileEarlyFrameHeld).isEmpty();
    assertThat(renderedTimestampsUs).containsExactly(200_000L);
  }

  @Test
  public void render_whenMidStreamCodecInitFailsAfterExtensionCodec_clearsReportedVideoSize()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format firstAv1Format =
        VIDEO_AV1_720P.buildUpon().setInitializationData(ImmutableList.of(new byte[] {1})).build();
    Format secondAv1Format =
        VIDEO_AV1_720P.buildUpon().setInitializationData(ImmutableList.of(new byte[] {2})).build();
    int[] createAdapterCallCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRenderer(configuration -> {
          createAdapterCallCount[0]++;
          if (createAdapterCallCount[0] == 2) {
            throw new IOException("Extension decoder init failed");
          }
          return new TestVideoExtensionCodecAdapter(
              configuration.format,
              new FakePassthroughVideoDecoder(configuration.format),
              configuration.surface);
        });
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            firstAv1Format,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, firstAv1Format, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }
    fakeSampleStream.append(
        ImmutableList.of(
            format(secondAv1Format), oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    renderer.render(/* positionUs= */ 40_000, /* elapsedRealtimeUs= */ 0);
    assertThrows(
        ExoPlaybackException.class,
        () -> renderer.render(/* positionUs= */ 40_000, /* elapsedRealtimeUs= */ 0));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    shadowOf(testMainLooper).idle();

    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(1);
    assertThat(eventListener.renderedFirstFrameOutputs).containsExactly(bufferRenderer);
  }

  @Test
  public void
      supportsResetPositionWithoutKeyFrameReset_withExtensionCodecBackwardBelowLastResetPosition_returnsFalse()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 120_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 160_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 200_000, /* flags= */ 0)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    // Seek forward within the same GOP without resetting to keyframe (setting outputStartTimeUs =
    // 160_000 on the extension decoder while lastProcessedOutputBufferTimeUs remains 0).
    renderer.resetPosition(/* positionUs= */ 160_000, /* sampleStreamIsResetToKeyFrame= */ false);
    boolean supportsBackwardBelowReset = renderer.supportsResetPositionWithoutKeyFrameReset(80_000);
    boolean supportsAtOrAboveReset = renderer.supportsResetPositionWithoutKeyFrameReset(160_000);

    assertThat(supportsBackwardBelowReset).isFalse();
    assertThat(supportsAtOrAboveReset).isTrue();
  }

  @Test
  public void
      handleMessage_setVideoOutputBetweenTwoBufferRenderers_rendersFirstFrameToSecondRenderer()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setAllowedJoiningTimeMs(5_000), fakeClock);
    VideoDecoderOutputBufferRenderer firstBufferRenderer = VideoDecoderOutputBuffer::release;
    List<Long> secondRendererTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer secondBufferRenderer =
        outputBuffer -> {
          secondRendererTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, firstBufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, secondBufferRenderer);
    shadowOf(testMainLooper).idle();
    boolean readyImmediatelyAfterSwitch = renderer.isReady();
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyImmediatelyAfterSwitch).isTrue();
    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(secondBufferRenderer);
    assertThat(secondRendererTimestampsUs).containsExactly(200_000L);
    assertThat(eventListener.renderedFirstFrameOutputs)
        .containsExactly(firstBufferRenderer, secondBufferRenderer)
        .inOrder();
    assertThat(Collections.frequency(eventListener.reportedVideoSizes, new VideoSize(1280, 720)))
        .isEqualTo(2);
  }

  @Test
  public void
      handleMessage_setVideoOutputWithBufferRendererBeforeCodecInitializedWhileStarted_doesNotStartJoining()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    decoder.setHoldOutputBuffers(true);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_AV1_720P, decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter, builder -> builder.setAllowedJoiningTimeMs(5_000), fakeClock);
    FakeSampleStream fakeSampleStream = createFakeSampleStream(VIDEO_AV1_720P, ImmutableList.of());
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    fakeSampleStream.append(
        ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  @Config(sdk = 35)
  public void
      render_withPlatformCodecAndOutputBufferRenderer_doesNotRenderToBufferRendererAndReportsReady()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setString(MediaFormat.KEY_MIME, MimeTypes.VIDEO_H264);
    mediaFormat.setFeatureEnabled(CodecCapabilities.FEATURE_DetachedSurface, true);
    CodecCapabilities detachedCapabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(mediaFormat)
            .setColorFormats(new int[0])
            .setProfileLevels(new CodecProfileLevel[0])
            .build();
    MediaCodecInfo detachedH264Info =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.avc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H264,
            /* codecMimeType= */ MimeTypes.VIDEO_H264,
            detachedCapabilities,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            codecAdapterFactory,
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(detachedH264Info),
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            h264Format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, h264Format, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderer.isReady()).isTrue();
    assertThat(renderedTimestampsUs).isEmpty();
    assertThat(eventListener.renderedFirstFrameOutputs).isEmpty();
  }

  @Test
  @Config(sdk = 34)
  public void
      render_acrossPlatformAndExtensionStreamChangesWithOutputBufferRenderer_waitsForStreamStartPosition()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            configuration ->
                configuration.codecInfo instanceof ExtensionCodecInfo
                    ? new TestVideoExtensionCodecAdapter(
                        configuration.format,
                        new FakePassthroughVideoDecoder(configuration.format),
                        configuration.surface)
                    : codecAdapterFactory.createAdapter(configuration),
            createAv1ExtensionAndPlatformSelector(),
            builder -> {},
            fakeClock);
    List<Long> renderedTimestampsUs = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedTimestampsUs.add(outputBuffer.timeUs);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream firstAv1Stream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, firstAv1Stream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    FakeSampleStream h264Stream =
        createFakeSampleStream(
            h264Format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    renderer.replaceStream(
        new Format[] {h264Format},
        h264Stream,
        /* startPositionUs= */ 50_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    FakeSampleStream secondAv1Stream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    renderer.replaceStream(
        new Format[] {VIDEO_AV1_720P},
        secondAv1Stream,
        /* startPositionUs= */ 200_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 100_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    ImmutableList<Long> renderedBeforeStreamStart = ImmutableList.copyOf(renderedTimestampsUs);
    fakeClock.advanceTime(/* timeDiffMs= */ 200);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 200_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderedBeforeStreamStart).containsExactly(0L);
    assertThat(renderedTimestampsUs).containsExactly(0L, 200_000L).inOrder();
  }

  @Test
  @Config(sdk = 34)
  public void render_fromUnreadyExtensionToPlatformCodecWithoutSurfaceSupport_doesNotStartJoining()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .build();
    int[] av1InitCount = new int[1];
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            configuration -> {
              if (configuration.codecInfo instanceof ExtensionCodecInfo) {
                av1InitCount[0]++;
                FakePassthroughVideoDecoder decoder =
                    new FakePassthroughVideoDecoder(configuration.format);
                if (av1InitCount[0] == 1) {
                  decoder.setOutputBufferCustomizer(
                      (inputBuffer, outputBuffer) -> outputBuffer.shouldBeSkipped = true);
                } else {
                  decoder.setHoldOutputBuffers(true);
                }
                return new TestVideoExtensionCodecAdapter(
                    configuration.format, decoder, configuration.surface);
              }
              return codecAdapterFactory.createAdapter(configuration);
            },
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream av1SampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, av1SampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    boolean readyInitially = renderer.isReady();

    // Switch to H.264 (cannot init without surface); since AV1 never rendered a frame, switching
    // to H.264 and then to an unready AV1 stream must not arm a 5-second join deadline.
    av1SampleStream.append(
        ImmutableList.of(
            format(h264Format), oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME)));
    av1SampleStream.writeData(/* startPositionUs= */ 0);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    SampleStream unbufferedAv1Stream =
        new SampleStream() {
          private boolean formatRead;

          @Override
          public boolean isReady() {
            return false;
          }

          @Override
          public void maybeThrowError() {}

          @Override
          public int readData(
              FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
            if (!formatRead) {
              formatRead = true;
              formatHolder.format = VIDEO_AV1_720P;
              return C.RESULT_FORMAT_READ;
            }
            return C.RESULT_NOTHING_READ;
          }

          @Override
          public int skipData(long positionUs) {
            return 0;
          }
        };
    renderer.replaceStream(
        new Format[] {VIDEO_AV1_720P},
        unbufferedAv1Stream,
        /* startPositionUs= */ 40_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(readyInitially).isFalse();
    assertThat(eventListener.initializedDecoderNames).hasSize(2);
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      handleMessage_setVideoOutputResolutionWithOutputBufferRendererAndVideoSink_doesNotThrowAndRestoresSurfaceInfo()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setVideoSink(fakeVideoSink),
            new FakeClock(/* initialTimeMs= */ 0));
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(1280, 720));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);

    assertThat(fakeVideoSink.lastOutputSurface).isSameInstanceAs(surface);
    assertThat(fakeVideoSink.lastOutputResolution).isEqualTo(new Size(1280, 720));
    assertThat(fakeVideoSink.setOutputSurfaceInfoCallCount).isEqualTo(2);
    assertThat(fakeVideoSink.clearOutputSurfaceInfoCallCount).isEqualTo(1);
  }

  @Test
  public void enable_withRetainedExtensionCodecAndVideoSinkWhileJoining_flushesVideoSink()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setVideoSink(fakeVideoSink).setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ true,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    int flushCountBeforeReEnable = fakeVideoSink.flushCallCount;
    renderer.stop();
    renderer.disable();
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1_720P},
        fakeSampleStream,
        /* positionUs= */ 20_000,
        /* joining= */ true,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 20_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    assertThat(fakeVideoSink.flushCallCount).isEqualTo(flushCountBeforeReEnable + 1);
    assertThat(fakeVideoSink.lastFlushResetPosition).isTrue();
  }

  @Test
  public void
      enable_afterDisabledWithRetainedExtensionCodecAndUninitializedVideoSink_reinitializesVideoSinkAndRenders()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    List<TestVideoExtensionCodecAdapter> createdAdapters = new ArrayList<>();
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              TestVideoExtensionCodecAdapter adapter =
                  new TestVideoExtensionCodecAdapter(
                      configuration.format,
                      new FakePassthroughVideoDecoder(configuration.format),
                      configuration.surface);
              createdAdapters.add(adapter);
              return adapter;
            },
            AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setVideoSink(fakeVideoSink),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream firstStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, firstStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    renderer.stop();
    renderer.disable();
    fakeVideoSink.initialized = false;
    FakeSampleStream secondStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, secondStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(createdAdapters).hasSize(2);
    assertThat(fakeVideoSink.isInitialized()).isTrue();
    assertThat(createdAdapters.get(1).lastOutput).isSameInstanceAs(sinkInputSurface);
    assertThat(fakeVideoSink.handledFrameTimestampsUs).containsExactly(0L, 50_000L).inOrder();
  }

  @Test
  public void
      render_withExtensionCodecAndVideoEffects_preservesDefault50MsEarlySchedulingThreshold()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    AtomicReference<RecordingVideoGraph> videoGraphRef = new AtomicReference<>();
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(configuration -> extensionCodecAdapter)
                .setMediaCodecSelector(AV1_EXTENSION_CODEC_SELECTOR)
                .setAllowedJoiningTimeMs(0)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)) {
          @Override
          protected PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
              Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
            return new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
                .setEnablePlaylistMode(true)
                .setClock(getClock())
                .setVideoGraphFactory(
                    new VideoGraph.Factory() {
                      @Override
                      public VideoGraph create(
                          Context context,
                          ColorInfo outputColorInfo,
                          DebugViewProvider debugViewProvider,
                          VideoGraph.Listener listener,
                          Executor listenerExecutor,
                          long initialTimestampOffsetUs,
                          boolean renderFramesAutomatically) {
                        RecordingVideoGraph videoGraph =
                            new RecordingVideoGraph(sinkInputSurface, listener, listenerExecutor);
                        videoGraphRef.set(videoGraph);
                        return videoGraph;
                      }

                      @Override
                      public boolean supportsMultipleInputs() {
                        return false;
                      }
                    })
                .build();
          }
        };
    createdRenderers.add(renderer);
    renderer.init(/* index= */ 0, PlayerId.UNSET, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(1280, 720));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_EFFECTS, ImmutableList.of());
    FakeSampleStream stream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 56_000, /* flags= */ 0),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, stream);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    RecordingVideoGraph videoGraph = checkNotNull(videoGraphRef.get());
    videoGraph.notifyOutputFrameAvailableForRendering(/* framePresentationTimeUs= */ 0L);
    shadowOf(testMainLooper).idle();
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    // At positionUs=10_000, frame 56_000 has earlyUs=46_000: within the default 50_000us
    // VideoFrameReleaseControl threshold even after vsync adjustment, but above the 30_000us
    // extension-codec cap, proving VideoSink preserves the default 50ms threshold.
    videoGraph.notifyOutputFrameAvailableForRendering(/* framePresentationTimeUs= */ 56_000L);
    shadowOf(testMainLooper).idle();
    fakeClock.advanceTime(/* timeDiffMs= */ 10);
    renderer.render(/* positionUs= */ 10_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(extensionCodecAdapter.lastOutput).isSameInstanceAs(sinkInputSurface);
    assertThat(videoGraph.renderedFrameTimesNs).hasSize(2);
  }

  @Test
  public void render_withExtensionCodecAndUnknownDimensions_doesNotReportVideoSizeChanged()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format av1UnknownDimensions =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    FakePassthroughVideoDecoder decoder = new FakePassthroughVideoDecoder(av1UnknownDimensions);
    decoder.setOutputBufferCustomizer((inputBuffer, outputBuffer) ->
        outputBuffer.initForPrivateFrame(
            /* width= */ Format.NO_VALUE, /* height= */ Format.NO_VALUE));
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(av1UnknownDimensions, decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    List<Integer> renderedWidths = new ArrayList<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedWidths.add(outputBuffer.width);
          outputBuffer.release();
        };
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            av1UnknownDimensions,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, av1UnknownDimensions, fakeSampleStream);
    for (int i = 0; i < 6; i++) {
      renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      shadowOf(testMainLooper).idle();
    }

    assertThat(renderedWidths).containsExactly(Format.NO_VALUE);
    assertThat(eventListener.reportedVideoSizes).isEmpty();
  }

  @Test
  @Config(sdk = 34)
  public void disable_whileWaitingForSurface_remainsNotReadyAfterReset() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder av1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    av1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            createAv1ExtensionAndPlatformAdapterFactory(av1Decoder),
            createAv1ExtensionAndPlatformSelector(),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    FakeSampleStream readyH264Stream =
        createFakeSampleStream(
            VIDEO_H264_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_H264_720P, readyH264Stream);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    renderer.stop();
    renderer.disable();
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream bufferedAv1Stream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, bufferedAv1Stream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      handleMessage_setVideoOutputResolutionUnknownAndClearSurface_ignoresInvalidSizeAndClearsStaleResolution()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setVideoSink(fakeVideoSink),
            new FakeClock(/* initialTimeMs= */ 0));
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    Surface firstSurface = createValidSurface(/* texName= */ 2);
    Surface secondSurface = createValidSurface(/* texName= */ 3);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, firstSurface);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(1280, 720));

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, Size.UNKNOWN);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, secondSurface);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(640, 480));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(0, 0));
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, firstSurface);

    assertThat(fakeVideoSink.setOutputSurfaceInfoCallCount).isEqualTo(2);
    assertThat(fakeVideoSink.lastOutputSurface).isNull();
    assertThat(fakeVideoSink.lastOutputResolution).isEqualTo(Size.UNKNOWN);
    assertThat(fakeVideoSink.clearOutputSurfaceInfoCallCount).isEqualTo(1);
  }

  @Test
  public void
      getDurationToProgressUs_afterSkippingMismatchedOutputModeBuffer_returnsDefaultDuration()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Flags.enableFlag(Flags.FLAG_VIDEO_RENDERER_DURATION_TO_PROGRESS);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_AV1_720P, decoder, /* surface= */ null);
    extensionCodecAdapter.setSupportsBufferAvailableCallback(true);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    VideoDecoderOutputBufferRenderer bufferRenderer = VideoDecoderOutputBuffer::release;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    // Render frame 0, then decode frame 100_000 and hold it with TRY_AGAIN_LATER so
    // nextOutputBufferToProcessPresentationTimeUs = 100_000.
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    long durationToProgressBeforeSkipUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    // Switch output to Surface so frame 100_000's YUV buffer mode no longer matches and is skipped.
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    long durationToProgressUs =
        renderer.getDurationToProgressUs(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    assertThat(durationToProgressBeforeSkipUs).isEqualTo(75_000L);
    assertThat(eventListener.getLatestEnabledDecoderCounters().renderedOutputBufferCount)
        .isEqualTo(1);
    assertThat(eventListener.getLatestEnabledDecoderCounters().skippedOutputBufferCount)
        .isEqualTo(1);
    assertThat(durationToProgressUs).isEqualTo(Renderer.DEFAULT_DURATION_TO_PROGRESS_US);
  }

  @Test
  public void
      render_withExtensionCodecAndFrameMetadataListener_passesClockNanoTimeAndNullMediaFormat()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 1_000);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), surface);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    List<Long> reportedReleaseTimesNs = new ArrayList<>();
    List<@Nullable MediaFormat> reportedMediaFormats = new ArrayList<>();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    renderer.handleMessage(
        Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER,
        (VideoFrameMetadataListener)
            (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
              reportedReleaseTimesNs.add(releaseTimeNs);
              reportedMediaFormats.add(mediaFormat);
            });
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }
    // Advance positionUs past initialPositionUs so frame 20_000 (earlyUs = 15_000 <= 30_000) takes
    // the FRAME_RELEASE_SCHEDULED -> releaseFrame path.
    for (int i = 0; i < 4; i++) {
      renderer.render(/* positionUs= */ 5_000, msToUs(fakeClock.elapsedRealtime()));
      shadowOf(testMainLooper).idle();
    }

    assertThat(reportedReleaseTimesNs)
        .containsExactly(fakeClock.nanoTime(), fakeClock.nanoTime())
        .inOrder();
    assertThat(reportedMediaFormats).containsExactly(null, null);
  }

  @Test
  public void render_withExtensionCodecRenderingDecoderException_throwsExoPlaybackException()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), surface);
    DecoderException renderFailure = new DecoderException("Surface render failed");
    extensionCodecAdapter.setRenderSurfaceException(renderFailure);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    // First render reads the format, initializes the codec, and feeds the input sample; the
    // second render dequeues it and fails in renderOutputBufferToSurface.
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    ExoPlaybackException thrown =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime())));
    shadowOf(testMainLooper).idle();

    assertThat(thrown.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DECODING_FAILED);
    assertThat(thrown).hasCauseThat().hasCauseThat().hasCauseThat().isSameInstanceAs(renderFailure);
    assertThat(eventListener.codecErrors).hasSize(1);
    assertThat(eventListener.codecErrors.get(0)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Config(sdk = 34)
  public void
      render_whenSurfaceLessPlatformCodecThrowsDuringFallback_preservesWaitingForCodecSurfaceAndJoinsExtensionCodec()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format h264NoSurfaceFormat = VIDEO_H264_720P;
    FakePassthroughVideoDecoder av1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    av1Decoder.setHoldOutputBuffers(true);
    MediaCodecInfo failingAv1PlatformCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.hardware.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(configuration -> {
                  if (configuration.codecInfo.name.equals("c2.android.av1.hardware.decoder")) {
                    throw new IOException("Primary AV1 init failed");
                  }
                  if (configuration.codecInfo instanceof ExtensionCodecInfo) {
                    return new TestVideoExtensionCodecAdapter(
                        configuration.format, av1Decoder, configuration.surface);
                  }
                  return codecAdapterFactory.createAdapter(configuration);
                })
                .setMediaCodecSelector(
                    (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                        Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                            ? ImmutableList.of(
                                failingAv1PlatformCodecInfo, AV1_EXTENSION_CODEC_INFO)
                            : mediaCodecSelector.getDecoderInfos(
                                mimeType, requiresSecureDecoder, requiresTunnelingDecoder))
                .setEnableDecoderFallback(true)
                .setAllowedJoiningTimeMs(5_000)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)) {
          @Override
          protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
            return Objects.equals(name, "name");
          }
        };
    createdRenderers.add(renderer);
    renderer.init(/* index= */ 0, PlayerId.UNSET, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream h264Stream =
        createFakeSampleStream(
            h264NoSurfaceFormat,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, h264NoSurfaceFormat, h264Stream);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    FakeSampleStream av1Stream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));

    renderer.replaceStream(
        new Format[] {VIDEO_AV1_720P},
        av1Stream,
        /* startPositionUs= */ 40_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(eventListener.codecErrors).hasSize(1);
    assertThat(eventListener.codecErrors.get(0)).isInstanceOf(DecoderInitializationException.class);
    assertThat(eventListener.initializedDecoderNames)
        .containsExactly("c2.media3.av1.dav1d.decoder");
    assertThat(renderer.isReady()).isTrue();
  }

  @Test
  public void render_fromExtensionToDeferredDrmStreamOpening_preservesReadinessOnCodecInit()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager mutableDrmSessionManager =
        createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            mutableDrmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();

    // Switch mid-stream to an encrypted format whose DrmSession starts in STATE_OPENING:
    // render() releases the clear codec and clears hasOutputBufferRenderer while getCodec() ==
    // null, then restores readiness via join() once keys arrive and the second codec initializes.
    fakeSampleStream.append(
        ImmutableList.of(
            format(encryptedAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    int adapterCountBeforeKeys = createdAdapterCount.get();
    int releaseCountBeforeKeys =
        eventListener.getLatestEnabledDecoderCounters().decoderReleaseCount;
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(adapterCountBeforeKeys).isEqualTo(1);
    assertThat(releaseCountBeforeKeys).isEqualTo(1);
    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(renderer.isReady()).isTrue();
  }

  @Test
  @Config(sdk = 34)
  public void
      maybeDropBuffersToKeyframe_withExtensionCodecAndTreatDroppedAsSkipped_updatesSkippedCounters()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakePassthroughVideoDecoder passthroughAv1Decoder =
        new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, passthroughAv1Decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    FakeTimeline timeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(1)
                .setUid(new Object())
                .setSeekable(true)
                .setDynamic(false)
                .setDurationUs(2_000_000L)
                .build());
    renderer.setTimeline(timeline);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 20_000, /* flags= */ 0),
                oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0)));
    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        fakeSampleStream,
        /* startPositionUs= */ 20_000,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
    // Without an attached output, sample 0 (< startPositionUs) is recorded in
    // skippedCodecBufferTimestamps, then sample 80_000 is held while queued after skipping 20_000.
    renderFrames(
        renderer, /* count= */ 6, /* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
    // Append a pre-keyframe sample 95_000 and keyframe 1_020_000 so jumping to 1_020_000 triggers
    // shouldIgnoreFrame(..., treatDroppedBuffersAsSkipped=true) -> maybeDropBuffersToKeyframe.
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 95_000, /* flags= */ 0),
            oneByteSample(/* timeUs= */ 1_020_000, C.BUFFER_FLAG_KEY_FRAME),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.setCurrentStreamFinal();

    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    renderFrames(
        renderer, /* count= */ 6, /* positionUs= */ 1_020_000, msToUs(fakeClock.elapsedRealtime()));

    DecoderCounters counters = eventListener.getLatestEnabledDecoderCounters();
    assertThat(counters.droppedToKeyframeCount).isEqualTo(0);
    assertThat(counters.droppedBufferCount).isEqualTo(0);
    // Samples 0 (pre-start), 20_000 (early < 30ms without output), 80_000 (held in codec when
    // maybeDropBuffersToKeyframe treats dropped buffers as skipped), and 1_020_000 (without
    // output).
    assertThat(counters.skippedOutputBufferCount).isEqualTo(4);
    assertThat(counters.skippedInputBufferCount).isEqualTo(1);
    assertThat(passthroughAv1Decoder.flushCount).isEqualTo(1);
  }

  @Test
  public void
      handleMessage_setVideoOutputWithVideoSinkFromBufferRendererThroughNullToSurface_restoresOutputSurfaceInfo()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setVideoSink(fakeVideoSink),
            new FakeClock(/* initialTimeMs= */ 0));
    Surface displaySurface1 = createValidSurface(/* texName= */ 2);
    enableAndStartRenderer(
        renderer,
        VIDEO_AV1_720P,
        createFakeSampleStream(VIDEO_AV1_720P, ImmutableList.of(END_OF_STREAM_ITEM)));

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, displaySurface1);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, new Size(1280, 720));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    int callCountBeforeReattach = fakeVideoSink.setOutputSurfaceInfoCallCount;
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, displaySurface1);

    assertThat(fakeVideoSink.setOutputSurfaceInfoCallCount).isEqualTo(callCountBeforeReattach + 1);
    assertThat(fakeVideoSink.lastOutputSurface).isSameInstanceAs(displaySurface1);
    assertThat(fakeVideoSink.lastOutputResolution).isEqualTo(new Size(1280, 720));
  }

  @Test
  public void
      getCodecOperatingRateV23_withScrubbingModeAndExtensionCodecUnknownFrameRate_doesNotSetNegativeMaxFloatOperatingRate()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format knownFrameRateFormat = VIDEO_AV1_720P.buildUpon().setFrameRate(60f).build();
    Format unknownFrameRateFormat =
        VIDEO_AV1_720P.buildUpon().setFrameRate(Format.NO_VALUE).build();
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            knownFrameRateFormat, new FakePassthroughVideoDecoder(knownFrameRateFormat), surface);
    List<Bundle> capturedParameters = new ArrayList<>();
    MediaCodecAdapter capturingAdapter =
        new ForwardingMediaCodecAdapter(extensionCodecAdapter) {
          @Override
          public void setParameters(Bundle params) {
            capturedParameters.add(new Bundle(params));
            super.setParameters(params);
          }
        };
    MediaCodecVideoRenderer renderer = createRenderer(capturingAdapter, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream initialStream =
        createFakeSampleStream(
            knownFrameRateFormat,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, knownFrameRateFormat, initialStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    renderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, ScrubbingModeParameters.DEFAULT);
    capturedParameters.clear();
    FakeSampleStream unknownFrameRateStream =
        createFakeSampleStream(
            unknownFrameRateFormat,
            ImmutableList.of(oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    renderer.replaceStream(
        new Format[] {unknownFrameRateFormat},
        unknownFrameRateStream,
        /* startPositionUs= */ 40_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    for (Bundle bundle : capturedParameters) {
      assertThat(bundle.containsKey(MediaFormat.KEY_OPERATING_RATE)).isFalse();
    }
  }

  @Test
  public void
      render_withWrappedVideoExtensionCodecAdapter_rendersToBufferRendererAndDetachesCleanly()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestVideoExtensionCodecAdapter delegate =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecAdapter wrappedAdapter = new ForwardingMediaCodecAdapter(delegate) {};
    MediaCodecVideoRenderer renderer = createRenderer(wrappedAdapter, fakeClock);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));

    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);

    assertThat(bufferRenderer.renderedTimestampsUs).containsExactly(0L);
    assertThat(delegate.lastOutput).isNull();
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void handleMessage_setVideoOutputWithBufferRendererWhileDisabled_doesNotUseBufferRenderer()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    renderer.stop();
    renderer.disable();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());

    assertThat(extensionCodecAdapter.lastOutput).isNull();
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void render_withExtensionCodecAndSurfaceOutput_capsEarlySchedulingThresholdAt30Ms()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), surface);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 60_000, /* flags= */ 0),
                END_OF_STREAM_ITEM));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    // At positionUs = 20_000, earlyUs = 40_000 (> 30_000 extension cap): held.
    fakeClock.advanceTime(/* timeDiffMs= */ 20);
    renderer.render(/* positionUs= */ 20_000, msToUs(fakeClock.elapsedRealtime()));
    int countWhileEarly = extensionCodecAdapter.renderedSurfaceBufferCount;
    // At positionUs = 35_000, earlyUs = 25_000 (<= 30_000 cap): rendered.
    fakeClock.advanceTime(/* timeDiffMs= */ 15);
    renderer.render(/* positionUs= */ 35_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(countWhileEarly).isEqualTo(1);
    assertThat(extensionCodecAdapter.renderedSurfaceBufferCount).isEqualTo(2);
  }

  @Test
  @Config(sdk = 29)
  public void render_withExtensionCodecAndFrameRateChangeBelowApi30_reusesCodec() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format format30Fps = VIDEO_AV1_720P.buildUpon().setFrameRate(30f).build();
    Format format60Fps = VIDEO_AV1_720P.buildUpon().setFrameRate(60f).build();
    AtomicInteger createAdapterCount = new AtomicInteger();
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration -> {
              createAdapterCount.incrementAndGet();
              return new TestVideoExtensionCodecAdapter(
                  configuration.format,
                  new FakePassthroughVideoDecoder(configuration.format),
                  configuration.surface);
            },
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            format30Fps,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                format(format60Fps),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, format30Fps, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 6, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(createAdapterCount.get()).isEqualTo(1);
    assertThat(eventListener.inputFormatReuseEvaluations).hasSize(2);
    assertThat(checkNotNull(eventListener.inputFormatReuseEvaluations.get(1)).result)
        .isEqualTo(DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
  }

  @Test
  public void
      render_withExtensionCodecAndDynamicOutputBufferDimensions_updatesVideoSizeFromOutputBuffer()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Format unknownSizeAv1Format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    FakePassthroughVideoDecoder decoder = new FakePassthroughVideoDecoder(unknownSizeAv1Format);
    decoder.setOutputBufferCustomizer(
        (inputBuffer, outputBuffer) -> outputBuffer.initForPrivateFrame(1920, 1080));
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(unknownSizeAv1Format, decoder, /* surface= */ null);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter, fakeClock);
    RecordingOutputBufferRenderer bufferRenderer = new RecordingOutputBufferRenderer();
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            unknownSizeAv1Format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    enableAndStartRenderer(renderer, unknownSizeAv1Format, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    assertThat(bufferRenderer.renderedWidths).containsExactly(1920);
    assertThat(eventListener.reportedVideoSizes).containsExactly(new VideoSize(1920, 1080));
  }

  @Test
  public void handleMessage_setVideoOutputWithUnrecognizedObject_detachesExtensionCodecOutput()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), surface);
    MediaCodecVideoRenderer renderer = createRenderer(extensionCodecAdapter);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(renderer, /* count= */ 4, /* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new Object());

    assertThat(extensionCodecAdapter.lastOutput).isNull();
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void resetPosition_withNonJoiningDeferredDrmCodecInit_doesNotJoinOnNextCodecInit()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder firstDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1 ? firstDecoder : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream clearStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, clearStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    FakeSampleStream openingDrmStream =
        createFakeSampleStream(
            encryptedAv1Format,
            drmSessionManager,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, /* flags= */ 0),
                END_OF_STREAM_ITEM));
    firstDecoder.setHoldOutputBuffers(true);
    renderer.replaceStream(
        new Format[] {encryptedAv1Format},
        openingDrmStream,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    // Reads format(encryptedAv1Format), marking the first codec DRAIN_ACTION_REINITIALIZE while
    // firstDecoder still holds the EOS drain output.
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    // Non-joining resetPosition releases the first codec and leaves getCodec() == null while
    // DrmSession is STATE_OPENING; it must not latch pendingJoinOnNextCodecInit.
    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void stopAndStart_withPendingJoinOnNextCodecInit_doesNotJoinOnNextCodecInit()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    fakeSampleStream.append(
        ImmutableList.of(
            format(encryptedAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    int adapterCountBeforeStop = createdAdapterCount.get();
    int releaseCountBeforeStop =
        eventListener.getLatestEnabledDecoderCounters().decoderReleaseCount;

    renderer.stop();
    renderer.start();
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(adapterCountBeforeStop).isEqualTo(1);
    assertThat(releaseCountBeforeStop).isEqualTo(1);
    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  @Config(sdk = 34)
  public void stopAndStart_whileWaitingForSurface_doesNotJoinOnNextCodecInit() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    FakePassthroughVideoDecoder av1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    av1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            configuration ->
                configuration.codecInfo instanceof ExtensionCodecInfo
                    ? new TestVideoExtensionCodecAdapter(
                        configuration.format, av1Decoder, configuration.surface)
                    : codecAdapterFactory.createAdapter(configuration),
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                    ? ImmutableList.of(DRM_AV1_EXTENSION_CODEC_INFO)
                    : mediaCodecSelector.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream h264SampleStream =
        createFakeSampleStream(
            VIDEO_H264_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_H264_720P, h264SampleStream);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    FakeSampleStream openingDrmAv1Stream =
        createFakeSampleStream(
            encryptedAv1Format,
            drmSessionManager,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    renderer.replaceStream(
        new Format[] {encryptedAv1Format},
        openingDrmAv1Stream,
        /* startPositionUs= */ 50_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    // Reads format(encryptedAv1Format) while waiting on DrmSession.STATE_OPENING without re-running
    // H.264 shouldInitCodec().
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    renderer.stop();
    renderer.start();
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  @Config(sdk = 34)
  public void render_whilePausedWaitingForSurface_doesNotJoinOnCodecInitAfterStart()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    FakePassthroughVideoDecoder av1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    av1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            configuration ->
                configuration.codecInfo instanceof ExtensionCodecInfo
                    ? new TestVideoExtensionCodecAdapter(
                        configuration.format, av1Decoder, configuration.surface)
                    : codecAdapterFactory.createAdapter(configuration),
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                    ? ImmutableList.of(DRM_AV1_EXTENSION_CODEC_INFO)
                    : mediaCodecSelector.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream h264SampleStream =
        createFakeSampleStream(
            VIDEO_H264_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME)));
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264_720P},
        h264SampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    FakeSampleStream openingDrmAv1Stream =
        createFakeSampleStream(
            encryptedAv1Format,
            drmSessionManager,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));

    renderer.replaceStream(
        new Format[] {encryptedAv1Format},
        openingDrmAv1Stream,
        /* startPositionUs= */ 50_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    renderer.start();
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  @Config(sdk = 34)
  public void
      handleMessage_setVideoOutputClearedToNullWhileWaitingForSurface_doesNotJoinOnNextCodecInit()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    FakePassthroughVideoDecoder av1Decoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    av1Decoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRendererWithSetOutputSurfaceWorkaround(
            configuration ->
                configuration.codecInfo instanceof ExtensionCodecInfo
                    ? new TestVideoExtensionCodecAdapter(
                        configuration.format, av1Decoder, configuration.surface)
                    : codecAdapterFactory.createAdapter(configuration),
            (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                    ? ImmutableList.of(DRM_AV1_EXTENSION_CODEC_INFO)
                    : mediaCodecSelector.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder),
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream h264SampleStream =
        createFakeSampleStream(
            VIDEO_H264_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_H264_720P, h264SampleStream);
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    FakeSampleStream openingDrmAv1Stream =
        createFakeSampleStream(
            encryptedAv1Format,
            drmSessionManager,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    renderer.replaceStream(
        new Format[] {encryptedAv1Format},
        openingDrmAv1Stream,
        /* startPositionUs= */ 50_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    renderer.render(/* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    fakeClock.advanceTime(/* timeDiffMs= */ 50);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 50_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      handleMessage_setVideoOutputClearedToNullDuringDeferredDrmCodecInit_doesNotJoinOnNextCodecInit()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    fakeSampleStream.append(
        ImmutableList.of(
            format(encryptedAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    int adapterCountBeforeOutputChange = createdAdapterCount.get();
    int releaseCountBeforeOutputChange =
        eventListener.getLatestEnabledDecoderCounters().decoderReleaseCount;

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(adapterCountBeforeOutputChange).isEqualTo(1);
    assertThat(releaseCountBeforeOutputChange).isEqualTo(1);
    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      handleMessage_setVideoOutputChangedToSurfaceDuringDeferredDrmCodecInit_doesNotReJoinOnCodecInit()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    fakeSampleStream.append(
        ImmutableList.of(
            format(encryptedAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    int adapterCountBeforeOutputChange = createdAdapterCount.get();
    int releaseCountBeforeOutputChange =
        eventListener.getLatestEnabledDecoderCounters().decoderReleaseCount;

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    fakeClock.advanceTime(/* timeDiffMs= */ 5_000);
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(adapterCountBeforeOutputChange).isEqualTo(1);
    assertThat(releaseCountBeforeOutputChange).isEqualTo(1);
    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(renderer.isReady()).isFalse();
  }

  @Test
  public void
      handleMessage_setVideoOutputChangedToOutputBufferRendererDuringDeferredDrmCodecInit_preservesJoinAndRenders()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    FakeDrmSession fakeDrmSession =
        new FakeDrmSession(DrmSession.STATE_OPENING, /* cryptoConfig= */ null);
    DrmSessionManager drmSessionManager = createCustomCryptoDrmSessionManager(fakeDrmSession);
    AtomicInteger createdAdapterCount = new AtomicInteger();
    FakePassthroughVideoDecoder secondDecoder = new FakePassthroughVideoDecoder(VIDEO_AV1_720P);
    secondDecoder.setHoldOutputBuffers(true);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            configuration ->
                new TestVideoExtensionCodecAdapter(
                    configuration.format,
                    createdAdapterCount.incrementAndGet() == 1
                        ? new FakePassthroughVideoDecoder(configuration.format)
                        : secondDecoder,
                    configuration.surface),
            DRM_AV1_EXTENSION_CODEC_SELECTOR,
            builder -> builder.setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, new RecordingOutputBufferRenderer());
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            drmSessionManager,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    Format encryptedAv1Format = createCustomEncryptedAv1Format();
    fakeSampleStream.append(
        ImmutableList.of(
            format(encryptedAv1Format),
            oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 80_000, /* flags= */ 0),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    int adapterCountBeforeOutputChange = createdAdapterCount.get();
    int releaseCountBeforeOutputChange =
        eventListener.getLatestEnabledDecoderCounters().decoderReleaseCount;
    RecordingOutputBufferRenderer secondBufferRenderer = new RecordingOutputBufferRenderer();

    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, secondBufferRenderer);
    fakeDrmSession.openWithKeys(new FakeCryptoConfig());
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    boolean readyBeforeFrameRendered = renderer.isReady();
    secondDecoder.setHoldOutputBuffers(false);
    fakeClock.advanceTime(/* timeDiffMs= */ 40);
    renderFrames(
        renderer, /* count= */ 2, /* positionUs= */ 40_000, msToUs(fakeClock.elapsedRealtime()));
    fakeClock.advanceTime(/* timeDiffMs= */ 40);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 80_000, msToUs(fakeClock.elapsedRealtime()));

    assertThat(adapterCountBeforeOutputChange).isEqualTo(1);
    assertThat(releaseCountBeforeOutputChange).isEqualTo(1);
    assertThat(createdAdapterCount.get()).isEqualTo(2);
    assertThat(readyBeforeFrameRendered).isTrue();
    assertThat(secondBufferRenderer.renderedTimestampsUs).containsExactly(40_000L, 80_000L);
  }

  @Test
  public void resetPosition_withExtensionCodecAndVideoSink_flushesVideoSink() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 0);
    Surface sinkInputSurface = createValidSurface(/* texName= */ 1);
    FakeVideoSink fakeVideoSink = new FakeVideoSink(sinkInputSurface);
    TestVideoExtensionCodecAdapter extensionCodecAdapter =
        new TestVideoExtensionCodecAdapter(
            VIDEO_AV1_720P, new FakePassthroughVideoDecoder(VIDEO_AV1_720P), /* surface= */ null);
    MediaCodecVideoRenderer renderer =
        createRenderer(
            extensionCodecAdapter,
            builder -> builder.setVideoSink(fakeVideoSink).setAllowedJoiningTimeMs(5_000),
            fakeClock);
    renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(
            VIDEO_AV1_720P,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME)));
    enableAndStartRenderer(renderer, VIDEO_AV1_720P, fakeSampleStream);
    renderFrames(
        renderer, /* count= */ 4, /* positionUs= */ 0, msToUs(fakeClock.elapsedRealtime()));
    int flushCountBeforeReset = fakeVideoSink.flushCallCount;

    renderer.resetPosition(/* positionUs= */ 40_000, /* sampleStreamIsResetToKeyFrame= */ true);

    assertThat(fakeVideoSink.flushCallCount).isEqualTo(flushCountBeforeReset + 1);
    assertThat(fakeVideoSink.lastFlushResetPosition).isTrue();
  }

  private Surface createValidSurface(int texName) {
    SurfaceTexture surfaceTexture = new SurfaceTexture(texName);
    createdSurfaceTextures.add(surfaceTexture);
    Surface validSurface =
        new Surface(surfaceTexture) {
          @Override
          public boolean isValid() {
            return true;
          }
        };
    createdSurfaces.add(validSurface);
    return validSurface;
  }

  private Surface createFrameRateRecordingSurface(List<Float> recordedSurfaceFrameRates) {
    SurfaceTexture recordingSurfaceTexture = new SurfaceTexture(/* texName= */ 1);
    createdSurfaceTextures.add(recordingSurfaceTexture);
    Surface recordingSurface =
        new Surface(recordingSurfaceTexture) {
          @Override
          public boolean isValid() {
            return true;
          }

          @Override
          public void setFrameRate(float frameRate, int compatibility) {
            if (frameRate > 0) {
              recordedSurfaceFrameRates.add(frameRate);
            }
          }
        };
    createdSurfaces.add(recordingSurface);
    return recordingSurface;
  }

  private MediaCodecSelector createAv1ExtensionAndPlatformSelector() {
    return (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
        Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
            ? ImmutableList.of(AV1_EXTENSION_CODEC_INFO)
            : mediaCodecSelector.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
  }

  private MediaCodecAdapter.Factory createAv1ExtensionAndPlatformAdapterFactory(
      FakePassthroughVideoDecoder av1Decoder) {
    return configuration ->
        configuration.codecInfo instanceof ExtensionCodecInfo
            ? new TestVideoExtensionCodecAdapter(
                configuration.format, av1Decoder, configuration.surface)
            : codecAdapterFactory.createAdapter(configuration);
  }

  private static DrmSessionManager createCustomCryptoDrmSessionManager(DrmSession drmSession) {
    return new DrmSessionManager() {
      @Override
      public void setPlayer(Looper playbackLooper, PlayerId playerId) {}

      @Override
      public @Nullable DrmSession acquireSession(
          DrmSessionEventListener.@Nullable EventDispatcher eventDispatcher, Format format) {
        return format.cryptoType != C.CRYPTO_TYPE_NONE ? drmSession : null;
      }

      @Override
      public @C.CryptoType int getCryptoType(Format format) {
        return format.cryptoType;
      }
    };
  }

  private static Format createCustomEncryptedAv1Format() {
    return VIDEO_AV1_720P
        .buildUpon()
        .setDrmInitData(
            new DrmInitData(
                new DrmInitData.SchemeData(
                    C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, /* data= */ new byte[] {1})))
        .setCryptoType(C.CRYPTO_TYPE_CUSTOM_BASE)
        .build();
  }

  private MediaCodecVideoRenderer createRenderer(MediaCodecAdapter codecAdapter) {
    return createRenderer(codecAdapter, new FakeClock(/* initialTimeMs= */ 0));
  }

  private MediaCodecVideoRenderer createRenderer(MediaCodecAdapter codecAdapter, Clock clock) {
    return createRenderer(codecAdapter, builder -> {}, clock);
  }

  private MediaCodecVideoRenderer createRenderer(
      MediaCodecAdapter codecAdapter,
      Consumer<MediaCodecVideoRenderer.Builder> builderCustomizer,
      Clock clock) {
    return createRenderer(
        configuration -> codecAdapter, AV1_EXTENSION_CODEC_SELECTOR, builderCustomizer, clock);
  }

  private MediaCodecVideoRenderer createRenderer(MediaCodecAdapter.Factory codecAdapterFactory) {
    return createRenderer(codecAdapterFactory, AV1_EXTENSION_CODEC_SELECTOR);
  }

  private MediaCodecVideoRenderer createRenderer(
      MediaCodecAdapter.Factory codecAdapterFactory, Clock clock) {
    return createRenderer(codecAdapterFactory, AV1_EXTENSION_CODEC_SELECTOR, clock);
  }

  private MediaCodecVideoRenderer createRenderer(
      MediaCodecAdapter.Factory codecAdapterFactory, MediaCodecSelector mediaCodecSelector) {
    return createRenderer(
        codecAdapterFactory, mediaCodecSelector, new FakeClock(/* initialTimeMs= */ 0));
  }

  private MediaCodecVideoRenderer createRenderer(
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      Clock clock) {
    return createRenderer(codecAdapterFactory, mediaCodecSelector, builder -> {}, clock);
  }

  private MediaCodecVideoRenderer createRenderer(
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      Consumer<MediaCodecVideoRenderer.Builder> builderCustomizer,
      Clock clock) {
    MediaCodecVideoRenderer.Builder builder =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener);
    builderCustomizer.accept(builder);
    MediaCodecVideoRenderer renderer = builder.build();
    createdRenderers.add(renderer);
    renderer.init(/* index= */ 0, PlayerId.UNSET, clock);
    return renderer;
  }

  private MediaCodecVideoRenderer createRendererWithSetOutputSurfaceWorkaround(
      MediaCodecAdapter.Factory codecAdapterFactory,
      MediaCodecSelector mediaCodecSelector,
      Consumer<MediaCodecVideoRenderer.Builder> builderCustomizer,
      Clock clock) {
    MediaCodecVideoRenderer.Builder builder =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener);
    builderCustomizer.accept(builder);
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer(builder) {
          @Override
          protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
            return true;
          }
        };
    createdRenderers.add(renderer);
    renderer.init(/* index= */ 0, PlayerId.UNSET, clock);
    return renderer;
  }

  private void renderFrames(
      MediaCodecVideoRenderer renderer, int count, long positionUs, long elapsedRealtimeUs)
      throws Exception {
    for (int i = 0; i < count; i++) {
      renderer.render(positionUs, elapsedRealtimeUs);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      shadowOf(testMainLooper).idle();
    }
  }

  private static Format createAv1Format(int bitdepth) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(1920)
        .setHeight(1080)
        .setColorInfo(
            new ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                .setLumaBitdepth(bitdepth)
                .setChromaBitdepth(bitdepth)
                .build())
        .build();
  }

  private static byte[] createAv1HagcMetadataObu() {
    return createByteArray(
        // AV1 metadata OBU (0x2A)
        0x2A,
        // OBU size 10 (0x0A)
        0x0A,
        // metadata_type = 4 (0x04)
        0x04,
        // HAGC T.35 header
        0xB5,
        0x00,
        0x90,
        0x00,
        0x01,
        // HAGC payload
        0x0A,
        0x0B,
        0x0C,
        // OBU trailing bits (0x80)
        0x80);
  }

  private FakeSampleStream createFakeSampleStream(
      Format format, List<FakeSampleStream.FakeSampleStreamItem> samples) {
    return createFakeSampleStream(format, DrmSessionManager.DRM_UNSUPPORTED, samples);
  }

  private FakeSampleStream createFakeSampleStream(
      Format format,
      DrmSessionManager drmSessionManager,
      List<FakeSampleStream.FakeSampleStreamItem> samples) {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            drmSessionManager,
            new DrmSessionEventListener.EventDispatcher(),
            format,
            samples);
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    return fakeSampleStream;
  }

  private void enableAndStartRenderer(
      MediaCodecVideoRenderer renderer, Format format, FakeSampleStream sampleStream)
      throws Exception {
    enableAndStartRenderer(
        renderer,
        format,
        sampleStream,
        /* startPositionUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
  }

  private void enableAndStartRenderer(
      MediaCodecVideoRenderer renderer,
      Format format,
      SampleStream sampleStream,
      long startPositionUs,
      MediaSource.MediaPeriodId mediaPeriodId)
      throws Exception {
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        sampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        startPositionUs,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();
    ShadowLooper.idleMainLooper();
  }

  private static final class RecordingOutputBufferRenderer
      implements VideoDecoderOutputBufferRenderer {

    private final List<Long> renderedTimestampsUs = new ArrayList<>();
    private final List<Integer> renderedWidths = new ArrayList<>();

    @Override
    public void setOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
      renderedTimestampsUs.add(outputBuffer.timeUs);
      renderedWidths.add(outputBuffer.width);
      outputBuffer.release();
    }
  }

  private static final class RecordingVideoRendererEventListener
      implements VideoRendererEventListener {

    private final List<DecoderCounters> enabledDecoderCounters = new ArrayList<>();
    private final List<String> initializedDecoderNames = new ArrayList<>();
    private final List<@Nullable DecoderReuseEvaluation> inputFormatReuseEvaluations =
        new ArrayList<>();
    private final List<VideoSize> reportedVideoSizes = new ArrayList<>();
    private final List<Object> renderedFirstFrameOutputs = new ArrayList<>();
    private final List<Exception> codecErrors = new ArrayList<>();

    private void clear() {
      enabledDecoderCounters.clear();
      initializedDecoderNames.clear();
      inputFormatReuseEvaluations.clear();
      reportedVideoSizes.clear();
      renderedFirstFrameOutputs.clear();
      codecErrors.clear();
    }

    private DecoderCounters getLatestEnabledDecoderCounters() {
      return Iterables.getLast(enabledDecoderCounters);
    }

    @Override
    public void onVideoEnabled(DecoderCounters counters) {
      enabledDecoderCounters.add(counters);
    }

    @Override
    public void onVideoDecoderInitialized(
        String decoderName, long initializedTimestampMs, long initializationDurationMs) {
      initializedDecoderNames.add(decoderName);
    }

    @Override
    public void onVideoInputFormatChanged(
        Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
      inputFormatReuseEvaluations.add(decoderReuseEvaluation);
    }

    @Override
    public void onRenderedFirstFrame(Object output, long renderTimeMs) {
      renderedFirstFrameOutputs.add(output);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      reportedVideoSizes.add(videoSize);
    }

    @Override
    public void onVideoCodecError(Exception videoCodecError) {
      codecErrors.add(videoCodecError);
    }
  }

  private static final class FakeDrmSession implements DrmSession {

    private @State int state;
    private @Nullable CryptoConfig cryptoConfig;

    private FakeDrmSession(@State int state, @Nullable CryptoConfig cryptoConfig) {
      this.state = state;
      this.cryptoConfig = cryptoConfig;
    }

    private void openWithKeys(CryptoConfig cryptoConfig) {
      this.cryptoConfig = cryptoConfig;
      this.state = STATE_OPENED_WITH_KEYS;
    }

    @Override
    public @State int getState() {
      return state;
    }

    @Override
    public boolean playClearSamplesWithoutKeys() {
      return false;
    }

    @Override
    public @Nullable DrmSessionException getError() {
      return null;
    }

    @Override
    public UUID getSchemeUuid() {
      return C.WIDEVINE_UUID;
    }

    @Override
    public @Nullable CryptoConfig getCryptoConfig() {
      return cryptoConfig;
    }

    @Override
    public @Nullable Map<String, String> queryKeyStatus() {
      return null;
    }

    @Override
    public byte @Nullable [] getOfflineLicenseKeySetId() {
      return null;
    }

    @Override
    public boolean requiresSecureDecoder(String mimeType) {
      return false;
    }

    @Override
    public void acquire(DrmSessionEventListener.@Nullable EventDispatcher eventDispatcher) {}

    @Override
    public void release(DrmSessionEventListener.@Nullable EventDispatcher eventDispatcher) {}
  }

  private static final class FakeVideoSink implements VideoSink {

    private final Surface inputSurface;
    private final List<Format> inputStreamFormats = new ArrayList<>();
    private final List<Long> handledFrameTimestampsUs = new ArrayList<>();
    private boolean initialized;
    private int flushCallCount;
    private boolean lastFlushResetPosition;
    private int setOutputSurfaceInfoCallCount;
    private int clearOutputSurfaceInfoCallCount;
    private @Nullable Surface lastOutputSurface;
    private Size lastOutputResolution = Size.UNKNOWN;

    private FakeVideoSink(Surface inputSurface) {
      this.inputSurface = inputSurface;
    }

    @Override
    public void startRendering() {}

    @Override
    public void stopRendering() {}

    @Override
    public void setListener(Listener listener, Executor executor) {}

    @Override
    public boolean initialize(Format sourceFormat) {
      initialized = true;
      return true;
    }

    @Override
    public boolean isInitialized() {
      return initialized;
    }

    @Override
    public void redraw() {}

    @Override
    public void flush(boolean resetPosition) {
      flushCallCount++;
      lastFlushResetPosition = resetPosition;
    }

    @Override
    public boolean isReady(boolean otherwiseReady) {
      return otherwiseReady;
    }

    @Override
    public void signalEndOfCurrentInputStream() {}

    @Override
    public void signalEndOfInput() {}

    @Override
    public boolean isEnded() {
      return false;
    }

    @Override
    public Surface getInputSurface() {
      checkState(initialized);
      return inputSurface;
    }

    @Override
    public void setVideoFrameMetadataListener(
        VideoFrameMetadataListener videoFrameMetadataListener) {}

    @Override
    public void setPlaybackSpeed(float speed) {}

    @Override
    public void setVideoEffects(List<Effect> videoEffects) {}

    @Override
    public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {}

    @Override
    public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
      setOutputSurfaceInfoCallCount++;
      lastOutputSurface = outputSurface;
      lastOutputResolution = outputResolution;
    }

    @Override
    public void clearOutputSurfaceInfo() {
      clearOutputSurfaceInfoCallCount++;
      lastOutputSurface = null;
      lastOutputResolution = Size.UNKNOWN;
    }

    @Override
    public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {}

    @Override
    public void onInputStreamChanged(
        @InputType int inputType,
        Format format,
        long startPositionUs,
        @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
        List<Effect> videoEffects) {
      inputStreamFormats.add(format);
    }

    @Override
    public void allowReleaseFirstFrameBeforeStarted() {}

    @Override
    public boolean handleInputFrame(
        long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
      checkState(!inputStreamFormats.isEmpty());
      handledFrameTimestampsUs.add(bufferPresentationTimeUs);
      videoFrameHandler.render(/* renderTimestampNs= */ 0);
      return true;
    }

    @Override
    public boolean handleInputBitmap(
        Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
      return false;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) {}

    @Override
    public void join(boolean renderNextFrameImmediately) {}

    @Override
    public void release() {}
  }

  private static final class FakePassthroughVideoDecoder
      implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private final ArrayDeque<VideoDecoderOutputBuffer> readyOutputBuffers;
    private final DecoderInputBuffer sharedInputBuffer;
    private final int width;
    private final int height;
    private final List<Integer> queuedSampleSizes;
    private final List<Boolean> queuedSampleLastFlags;
    private @C.VideoOutputMode int currentOutputMode;
    private long outputStartTimeUs;
    private int flushCount;
    private boolean holdOutputBuffers;
    private byte @Nullable [] lastQueuedSampleBytes;
    private byte @Nullable [] lastQueuedSupplementalDataBytes;

    private @Nullable BiConsumer<DecoderInputBuffer, VideoDecoderOutputBuffer>
        outputBufferCustomizer;

    private FakePassthroughVideoDecoder(Format format) {
      this.width = format.width;
      this.height = format.height;
      this.currentOutputMode = C.VIDEO_OUTPUT_MODE_NONE;
      readyOutputBuffers = new ArrayDeque<>();
      sharedInputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
      queuedSampleSizes = new ArrayList<>();
      queuedSampleLastFlags = new ArrayList<>();
      outputStartTimeUs = C.TIME_UNSET;
    }

    @Override
    public String getName() {
      return "FakePassthroughVideoDecoder";
    }

    @Override
    public @Nullable DecoderInputBuffer dequeueInputBuffer() {
      sharedInputBuffer.clear();
      return sharedInputBuffer;
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
      VideoDecoderOutputBuffer outputBuffer = new VideoDecoderOutputBuffer(unused -> {});
      outputBuffer.init(inputBuffer.timeUs, currentOutputMode, /* supplementalData= */ null);
      if (inputBuffer.isEndOfStream()) {
        outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      } else {
        queuedSampleLastFlags.add(inputBuffer.isLastSample());
        if (inputBuffer.isLastSample()) {
          outputBuffer.addFlag(C.BUFFER_FLAG_LAST_SAMPLE);
        }
        if (inputBuffer.data != null) {
          ByteBuffer dataCopy = inputBuffer.data.duplicate();
          byte[] sampleBytes = new byte[dataCopy.remaining()];
          dataCopy.get(sampleBytes);
          lastQueuedSampleBytes = sampleBytes;
          queuedSampleSizes.add(sampleBytes.length);
        }
        if (inputBuffer.supplementalData != null) {
          ByteBuffer supplementalCopy = inputBuffer.supplementalData.duplicate();
          byte[] supplementalBytes = new byte[supplementalCopy.remaining()];
          supplementalCopy.get(supplementalBytes);
          lastQueuedSupplementalDataBytes = supplementalBytes;
        }
        if (outputStartTimeUs != C.TIME_UNSET
            && inputBuffer.timeUs < outputStartTimeUs
            && !inputBuffer.isLastSample()) {
          outputBuffer.shouldBeSkipped = true;
        }
        int outputWidth =
            inputBuffer.format != null && inputBuffer.format.width > 0
                ? inputBuffer.format.width
                : width;
        int outputHeight =
            inputBuffer.format != null && inputBuffer.format.height > 0
                ? inputBuffer.format.height
                : height;
        outputBuffer.initForPrivateFrame(outputWidth, outputHeight);
        if (outputBufferCustomizer != null) {
          outputBufferCustomizer.accept(inputBuffer, outputBuffer);
        }
      }
      readyOutputBuffers.addLast(outputBuffer);
    }

    @Override
    public @Nullable VideoDecoderOutputBuffer dequeueOutputBuffer() {
      if (holdOutputBuffers) {
        return null;
      }
      return readyOutputBuffers.pollFirst();
    }

    @Override
    public void flush() {
      while (!readyOutputBuffers.isEmpty()) {
        readyOutputBuffers.pollFirst().release();
      }
      flushCount++;
    }

    @Override
    public void release() {
      while (!readyOutputBuffers.isEmpty()) {
        readyOutputBuffers.pollFirst().release();
      }
    }

    @Override
    public void setOutputStartTimeUs(long outputStartTimeUs) {
      this.outputStartTimeUs = outputStartTimeUs;
    }

    @Override
    public int getAndResetSkippedOutputBufferCount() {
      if (holdOutputBuffers) {
        return 0;
      }
      int skippedCount = 0;
      Iterator<VideoDecoderOutputBuffer> iterator = readyOutputBuffers.iterator();
      while (iterator.hasNext()) {
        VideoDecoderOutputBuffer buffer = iterator.next();
        skippedCount += buffer.skippedOutputBufferCount;
        buffer.skippedOutputBufferCount = 0;
        if (buffer.shouldBeSkipped) {
          skippedCount++;
          iterator.remove();
          buffer.release();
        }
      }
      return skippedCount;
    }

    private void setHoldOutputBuffers(boolean holdOutputBuffers) {
      this.holdOutputBuffers = holdOutputBuffers;
    }

    private void setOutputBufferCustomizer(
        BiConsumer<DecoderInputBuffer, VideoDecoderOutputBuffer> outputBufferCustomizer) {
      this.outputBufferCustomizer = outputBufferCustomizer;
    }

    private void setOutputMode(@C.VideoOutputMode int outputMode) {
      this.currentOutputMode = outputMode;
    }
  }

  private static final class TestVideoExtensionCodecAdapter extends VideoExtensionCodecAdapter {

    private final FakePassthroughVideoDecoder decoder;
    private @Nullable Object lastOutput;
    private int renderedSurfaceBufferCount;
    private boolean supportsBufferAvailableCallback;
    private @Nullable DecoderException renderSurfaceException;

    private TestVideoExtensionCodecAdapter(
        Format format, FakePassthroughVideoDecoder decoder, @Nullable Surface surface) {
      super(format, decoder, surface);
      this.decoder = decoder;
    }

    private void setSupportsBufferAvailableCallback(boolean supportsBufferAvailableCallback) {
      this.supportsBufferAvailableCallback = supportsBufferAvailableCallback;
    }

    private void setRenderSurfaceException(DecoderException renderSurfaceException) {
      this.renderSurfaceException = renderSurfaceException;
    }

    @Override
    protected boolean supportsBufferAvailableCallback() {
      return supportsBufferAvailableCallback;
    }

    @Override
    public void setOutput(@Nullable Object output) {
      super.setOutput(output);
      this.lastOutput = output;
    }

    @Override
    protected void onOutputModeChanged(@C.VideoOutputMode int outputMode) {
      decoder.setOutputMode(outputMode);
    }

    @Override
    protected void renderOutputBufferToSurface(
        VideoDecoderOutputBuffer outputBuffer, Surface surface) throws DecoderException {
      if (renderSurfaceException != null) {
        outputBuffer.release();
        throw renderSurfaceException;
      }
      renderedSurfaceBufferCount++;
      outputBuffer.release();
    }
  }

  private static final class RecordingVideoGraph implements VideoGraph {

    private final Surface inputSurface;
    private final VideoGraph.Listener listener;
    private final Executor listenerExecutor;
    private final List<Long> renderedFrameTimesNs = new ArrayList<>();

    private RecordingVideoGraph(
        Surface inputSurface, VideoGraph.Listener listener, Executor listenerExecutor) {
      this.inputSurface = inputSurface;
      this.listener = listener;
      this.listenerExecutor = listenerExecutor;
    }

    private void notifyOutputFrameAvailableForRendering(long framePresentationTimeUs) {
      listenerExecutor.execute(() ->
          listener.onOutputFrameAvailableForRendering(
              framePresentationTimeUs, /* isRedrawnFrame= */ false));
    }

    @Override
    public void initialize() {}

    @Override
    public void registerInput(int inputIndex) {}

    @Override
    public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {}

    @Override
    public void setOnInputSurfaceReadyListener(int inputIndex, Runnable listener) {}

    @Override
    public Surface getInputSurface(int inputIndex) {
      return inputSurface;
    }

    @Override
    public void setOnInputFrameProcessedListener(
        int inputIndex, OnInputFrameProcessedListener listener) {}

    @Override
    public void setCompositionEffects(List<Effect> compositionEffects) {}

    @Override
    public void setCompositorSettings(VideoCompositorSettings videoCompositorSettings) {}

    @Override
    public void registerInputStream(
        int inputIndex, int inputType, Format format, List<Effect> effects, long offsetToAddUs) {}

    @Override
    public int getPendingInputFrameCount(int inputIndex) {
      return 0;
    }

    @Override
    public boolean registerInputFrame(int inputIndex) {
      return true;
    }

    @Override
    public boolean queueInputBitmap(
        int inputIndex, Bitmap inputBitmap, TimestampIterator timestampIterator) {
      return false;
    }

    @Override
    public boolean queueInputTexture(int inputIndex, int textureId, long presentationTimeUs) {
      return false;
    }

    @Override
    public void renderOutputFrame(long renderTimeNs) {
      if (renderTimeNs != VideoFrameProcessor.DROP_OUTPUT_FRAME) {
        renderedFrameTimesNs.add(renderTimeNs);
      }
    }

    @Override
    public void redraw() {}

    @Override
    public boolean hasProducedFrameWithTimestampZero() {
      return false;
    }

    @Override
    public void flush() {}

    @Override
    public void signalEndOfInput(int inputIndex) {}

    @Override
    public void release() {}
  }
}
