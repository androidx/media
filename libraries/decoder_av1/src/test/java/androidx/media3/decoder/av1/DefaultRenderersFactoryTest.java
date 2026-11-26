/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.av1;

import static android.media.MediaCodecInfo.CodecProfileLevel.AV1Level4;
import static android.media.MediaCodecInfo.CodecProfileLevel.AV1Level51;
import static android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.DefaultRenderersFactoryAsserts;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.MediaCodecInfoBuilder;

/** Unit tests for {@link DefaultRenderersFactory} and {@link MediaCodecUtil} with Dav1d. */
@RunWith(AndroidJUnit4.class)
public final class DefaultRenderersFactoryTest {

  private static final Format AV1_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_AV1)
          .setWidth(1920)
          .setHeight(1080)
          .build();

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  @After
  public void tearDown() {
    Dav1dLibrary.setAvailableForTesting(null);
  }

  @Test
  public void createRenderers_withUnifiedFlagDisabled_instantiatesDav1dRenderer() {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);

    DefaultRenderersFactoryAsserts.assertExtensionRendererCreated(
        Libdav1dVideoRenderer.class, C.TRACK_TYPE_VIDEO);
  }

  @Test
  public void createRenderers_withUnifiedFlagDisabled_doesNotAddDav1dToMediaCodecVideoRenderer()
      throws Exception {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    MediaCodecInfo hardwareAv1 = createPlatformAv1CodecInfo("c2.qti.av1.decoder", AV1Level51);
    List<String> attemptedCodecNames = new ArrayList<>();
    DefaultRenderersFactory factory =
        createRecordingRenderersFactory(attemptedCodecNames)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(hardwareAv1));

    runMediaCodecVideoRenderer(factory, AV1_FORMAT);

    assertThat(attemptedCodecNames).containsExactly("c2.qti.av1.decoder");
  }

  @Test
  public void createRenderers_withUnifiedFlagEnabled_respectsExtensionRendererMode()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    DefaultRenderersFactoryAsserts.assertUnifiedExtensionDecoderPreference(
        Libdav1dVideoRenderer.class,
        C.TRACK_TYPE_VIDEO,
        AV1_FORMAT,
        createPlatformAv1CodecInfo("c2.android.av1.decoder", AV1Level51),
        /* extensionCodecName= */ Dav1dCodecInfo.NAME);
  }

  @Test
  public void supportsFormat_inModeOnWhenLevelExceedsHwCapabilities_reportsFallbackSupport()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    MediaCodecInfo hardwareAv1Level4 = createPlatformAv1CodecInfo("c2.qti.av1.decoder", AV1Level4);
    Format level51Av1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.13M.08")
            .build();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext())
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(hardwareAv1Level4));

    @Capabilities int capabilities = getVideoRendererCapabilities(factory, level51Av1Format);

    assertThat(RendererCapabilities.getFormatSupport(capabilities)).isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getDecoderSupport(capabilities))
        .isEqualTo(RendererCapabilities.DECODER_SUPPORT_FALLBACK);
    assertThat(RendererCapabilities.getHardwareAccelerationSupport(capabilities))
        .isEqualTo(RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED);
  }

  @Test
  public void render_inModeOnWhenLevelExceedsHwCapabilities_choosesDav1d() throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    MediaCodecInfo hardwareAv1Level4 = createPlatformAv1CodecInfo("c2.qti.av1.decoder", AV1Level4);
    Format level51Av1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.13M.08")
            .build();
    List<String> attemptedCodecNames = new ArrayList<>();
    DefaultRenderersFactory factory =
        createRecordingRenderersFactory(attemptedCodecNames)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(hardwareAv1Level4));

    runMediaCodecVideoRenderer(factory, level51Av1Format);

    assertThat(attemptedCodecNames).containsExactly(Dav1dCodecInfo.NAME);
  }

  @Test
  public void supportsFormat_withDolbyVisionProfile10AndOnlyDav1d_returnsUnsupportedSubtype()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    Format dolbyVisionProfile10Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.09")
            .build();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext())
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector(
                (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> ImmutableList.of());

    @Capabilities int av1Capabilities = getVideoRendererCapabilities(factory, AV1_FORMAT);
    @Capabilities
    int dolbyVisionCapabilities = getVideoRendererCapabilities(factory, dolbyVisionProfile10Format);

    assertThat(RendererCapabilities.getFormatSupport(av1Capabilities)).isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getFormatSupport(dolbyVisionCapabilities))
        .isEqualTo(C.FORMAT_UNSUPPORTED_SUBTYPE);
  }

  @Test
  public void buildVideoRenderers_whenOverridden_receivesUnwrappedSelectorAndSuperCombinesDav1d()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    MediaCodecInfo hardwareAv1 = createPlatformAv1CodecInfo("c2.qti.av1.decoder", AV1Level51);
    MediaCodecSelector configuredSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.of(hardwareAv1);
    List<MediaCodecSelector> receivedSelectors = new ArrayList<>();
    List<String> attemptedCodecNames = new ArrayList<>();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext()) {
          @Override
          protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
            return configuration -> {
              attemptedCodecNames.add(configuration.codecInfo.name);
              throw new IOException("Simulated initialization failure to record candidate order");
            };
          }

          @SuppressWarnings("NonApiType") // Superclass signature uses ArrayList.
          @Override
          protected void buildVideoRenderers(
              Context context,
              int extensionRendererMode,
              MediaCodecSelector mediaCodecSelector,
              boolean enableDecoderFallback,
              Handler eventHandler,
              VideoRendererEventListener eventListener,
              long allowedVideoJoiningTimeMs,
              ArrayList<Renderer> out) {
            receivedSelectors.add(mediaCodecSelector);
            super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out);
          }

          @Override
          protected @Nullable Renderer buildSecondaryVideoRenderer(
              Renderer renderer,
              Context context,
              int extensionRendererMode,
              MediaCodecSelector mediaCodecSelector,
              boolean enableDecoderFallback,
              Handler eventHandler,
              VideoRendererEventListener eventListener,
              long allowedVideoJoiningTimeMs) {
            receivedSelectors.add(mediaCodecSelector);
            return super.buildSecondaryVideoRenderer(
                renderer,
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs);
          }
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(true)
            .setMediaCodecSelector(configuredSelector);

    runMediaCodecVideoRenderer(factory, AV1_FORMAT);
    Renderer[] renderers = createFactoryRenderers(factory);
    @Nullable Renderer secondaryRenderer = null;
    try {
      secondaryRenderer =
          factory.createSecondaryRenderer(
              findVideoRenderer(renderers),
              new Handler(Looper.getMainLooper()),
              new VideoRendererEventListener() {},
              new AudioRendererEventListener() {},
              cueGroup -> {},
              metadata -> {});
    } finally {
      if (secondaryRenderer != null) {
        secondaryRenderer.release();
      }
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }

    assertThat(secondaryRenderer).isNotNull();
    assertThat(receivedSelectors)
        .containsExactly(configuredSelector, configuredSelector, configuredSelector);
    assertThat(attemptedCodecNames)
        .containsExactly(Dav1dCodecInfo.NAME, "c2.qti.av1.decoder")
        .inOrder();
  }

  @Test
  public void setExtensionAdapterFactory_forDav1d_usesRegisteredFactoryForDiscoveredExtension()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);
    List<MediaCodecInfo> adapterCodecInfos = new ArrayList<>();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext())
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(
                (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> ImmutableList.of())
            .setExtensionAdapterFactory(
                Dav1dCodecInfo.NAME,
                configuration -> {
                  adapterCodecInfos.add(configuration.codecInfo);
                  throw new IOException("Simulated initialization failure");
                });

    runMediaCodecVideoRenderer(factory, AV1_FORMAT);

    assertThat(adapterCodecInfos).hasSize(1);
    assertThat(adapterCodecInfos.get(0)).isInstanceOf(Dav1dCodecInfo.class);
  }

  @Test
  public void getExtensionDecoderInfos_withAv1WhenLibraryAvailable_returnsDav1dCodecInfo() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).hasSize(1);
    assertThat(decoderInfos.get(0)).isInstanceOf(Dav1dCodecInfo.class);
    assertThat(decoderInfos.get(0).name).isEqualTo(Dav1dCodecInfo.NAME);
  }

  @Test
  public void
      getExtensionDecoderInfos_whenLibraryAvailabilityChanges_reflectsLiveLibraryAvailability() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(false);
    List<MediaCodecInfo> unavailableDecoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> availableDecoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(unavailableDecoders).isEmpty();
    assertThat(availableDecoders).hasSize(1);
    assertThat(availableDecoders.get(0)).isInstanceOf(Dav1dCodecInfo.class);
  }

  @Test
  public void getExtensionDecoderInfos_withUpperCaseAv1MimeType_returnsDav1dCodecInfo() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getExtensionDecoderInfos(
            "VIDEO/AV01",
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).hasSize(1);
    assertThat(decoderInfos.get(0)).isInstanceOf(Dav1dCodecInfo.class);
  }

  @Test
  public void getExtensionDecoderInfos_withAv1RequiringSecure_returnsEmpty() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> secureDecoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ true,
            /* requiresTunnelingDecoder= */ false);

    assertThat(secureDecoders).isEmpty();
  }

  @Test
  public void getExtensionDecoderInfos_withAv1RequiringTunneling_returnsEmpty() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> tunnelingDecoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ true);

    assertThat(tunnelingDecoders).isEmpty();
  }

  @Test
  public void getExtensionDecoderInfos_withNonAv1MimeType_returnsEmpty() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> h264Decoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_H264,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(h264Decoders).isEmpty();
  }

  @Test
  public void getExtensionDecoderInfos_whenUnifiedFlagDisabled_returnsEmpty() {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Dav1dLibrary.setAvailableForTesting(true);

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).isEmpty();
  }

  private static DefaultRenderersFactory createRecordingRenderersFactory(
      List<String> attemptedCodecNames) {
    return new DefaultRenderersFactory(ApplicationProvider.getApplicationContext()) {
      @Override
      protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
        return configuration -> {
          attemptedCodecNames.add(configuration.codecInfo.name);
          throw new IOException("Simulated initialization failure to record candidate order");
        };
      }
    };
  }

  private static @Capabilities int getVideoRendererCapabilities(
      DefaultRenderersFactory factory, Format format) throws ExoPlaybackException {
    Renderer[] renderers = createFactoryRenderers(factory);
    try {
      MediaCodecVideoRenderer videoRenderer = findVideoRenderer(renderers);
      videoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
      return videoRenderer.supportsFormat(format);
    } finally {
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }
  }

  private static void runMediaCodecVideoRenderer(DefaultRenderersFactory factory, Format format)
      throws Exception {
    Renderer[] renderers = createFactoryRenderers(factory);
    @Nullable SurfaceTexture surfaceTexture = null;
    @Nullable Surface surface = null;
    try {
      MediaCodecVideoRenderer videoRenderer = findVideoRenderer(renderers);
      surfaceTexture = new SurfaceTexture(/* texName= */ 0);
      surface =
          new Surface(surfaceTexture) {
            @Override
            public boolean isValid() {
              return true;
            }
          };
      try {
        videoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        videoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
        FakeSampleStream sampleStream =
            new FakeSampleStream(
                new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
                /* mediaSourceEventDispatcher= */ null,
                DrmSessionManager.DRM_UNSUPPORTED,
                new DrmSessionEventListener.EventDispatcher(),
                /* initialFormat= */ format,
                ImmutableList.of(
                    FakeSampleStream.FakeSampleStreamItem.oneByteSample(
                        /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                    FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM));
        sampleStream.writeData(/* startPositionUs= */ 0);
        videoRenderer.enable(
            RendererConfiguration.DEFAULT,
            new Format[] {format},
            sampleStream,
            /* positionUs= */ 0,
            /* joining= */ false,
            /* mayRenderStartOfStream= */ true,
            /* startPositionUs= */ 0,
            /* offsetUs= */ 0,
            /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));
        try {
          videoRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
        } catch (ExoPlaybackException ignored) {
          // Expected when all simulated decoder initializations fail.
        }
      } finally {
        if (videoRenderer.getState() == Renderer.STATE_ENABLED) {
          videoRenderer.disable();
        }
        videoRenderer.reset();
      }
    } finally {
      if (surface != null) {
        surface.release();
      }
      if (surfaceTexture != null) {
        surfaceTexture.release();
      }
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }
  }

  private static Renderer[] createFactoryRenderers(DefaultRenderersFactory factory) {
    return factory.createRenderers(
        new Handler(Looper.getMainLooper()),
        new VideoRendererEventListener() {},
        new AudioRendererEventListener() {},
        cueGroup -> {},
        metadata -> {});
  }

  private static MediaCodecVideoRenderer findVideoRenderer(Renderer[] renderers) {
    return Iterables.getOnlyElement(
        Iterables.filter(Arrays.asList(renderers), MediaCodecVideoRenderer.class));
  }

  private static MediaCodecInfo createPlatformAv1CodecInfo(String name, int level) {
    int maxWidth = level >= AV1Level51 ? 3840 : 1920;
    int maxHeight = level >= AV1Level51 ? 2160 : 1080;
    MediaFormat mediaFormat =
        MediaFormat.createVideoFormat(MimeTypes.VIDEO_AV1, maxWidth, maxHeight);
    CodecCapabilities capabilities =
        MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(mediaFormat)
            .setIsEncoder(false)
            .setColorFormats(new int[] {CodecCapabilities.COLOR_FormatYUV420Flexible})
            .setProfileLevels(
                new CodecProfileLevel[] {
                  MediaCodecUtil.createCodecProfileLevel(AV1ProfileMain8, level)
                })
            .build();
    return MediaCodecInfo.newInstance(
        name,
        MimeTypes.VIDEO_AV1,
        MimeTypes.VIDEO_AV1,
        capabilities,
        /* hardwareAccelerated= */ true,
        /* softwareOnly= */ false,
        /* vendor= */ true);
  }
}
