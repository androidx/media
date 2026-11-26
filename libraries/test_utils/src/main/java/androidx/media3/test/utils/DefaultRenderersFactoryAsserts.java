/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
import static androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
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
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Assertions for {@link DefaultRenderersFactory}. */
@UnstableApi
public final class DefaultRenderersFactoryAsserts {

  /**
   * Asserts that an extension renderer of type {@code clazz} is not instantiated for {@link
   * DefaultRenderersFactory#EXTENSION_RENDERER_MODE_OFF}, and that it's instantiated in the correct
   * position relative to other renderers of the same type for {@link
   * DefaultRenderersFactory#EXTENSION_RENDERER_MODE_ON} and {@link
   * DefaultRenderersFactory#EXTENSION_RENDERER_MODE_PREFER}, assuming no other extension renderers
   * can be loaded.
   *
   * @param clazz The extension renderer class.
   * @param type The type of the renderer.
   */
  public static void assertExtensionRendererCreated(
      Class<? extends Renderer> clazz, @C.TrackType int type) {
    // In EXTENSION_RENDERER_MODE_OFF the renderer should not be created.
    Renderer[] renderers = createRenderers(EXTENSION_RENDERER_MODE_OFF);
    for (Renderer renderer : renderers) {
      assertThat(renderer).isNotInstanceOf(clazz);
    }

    // In EXTENSION_RENDERER_MODE_ON the renderer should be created and last of its type.
    renderers = createRenderers(EXTENSION_RENDERER_MODE_ON);
    boolean found = false;
    for (Renderer renderer : renderers) {
      if (!found) {
        if (clazz.isInstance(renderer)) {
          found = true;
        }
      } else {
        assertThat(renderer.getTrackType()).isNotEqualTo(type);
      }
    }
    assertThat(found).isTrue();

    // In EXTENSION_RENDERER_MODE_PREFER the renderer should be created and first of its type.
    renderers = createRenderers(EXTENSION_RENDERER_MODE_PREFER);
    found = false;
    for (Renderer renderer : renderers) {
      if (!found) {
        if (clazz.isInstance(renderer)) {
          found = true;
        } else {
          assertThat(renderer.getTrackType()).isNotEqualTo(type);
        }
      } else {
        assertThat(renderer).isNotInstanceOf(clazz);
      }
    }
    assertThat(found).isTrue();
  }

  /**
   * Asserts that when {@link Flags#FLAG_UNIFIED_DECODER_RENDERER} is enabled, {@link
   * DefaultRenderersFactory} orders {@code platformCodecInfo} and {@code extensionCodecName}
   * according to {@link DefaultRenderersFactory.ExtensionRendererMode} without instantiating {@code
   * extensionRendererClass}.
   *
   * <p>This method is experimental and may be renamed or removed in a future release.
   *
   * @param extensionRendererClass The legacy extension renderer class that must not be
   *     instantiated.
   * @param trackType The track type ({@link C#TRACK_TYPE_VIDEO}).
   * @param format The media format to decode.
   * @param platformCodecInfo The platform decoder candidate.
   * @param extensionCodecName The name of the extension decoder candidate.
   * @throws Exception If an error occurs initializing or running the renderer.
   */
  @ExperimentalApi // TODO(b/562381449): Remove or make non-experimental.
  public static void assertUnifiedExtensionDecoderPreference(
      Class<? extends Renderer> extensionRendererClass,
      @C.TrackType int trackType,
      Format format,
      MediaCodecInfo platformCodecInfo,
      String extensionCodecName)
      throws Exception {
    checkArgument(trackType == C.TRACK_TYPE_VIDEO, "trackType must be TRACK_TYPE_VIDEO");
    checkNotNull(format.sampleMimeType);
    checkState(
        Flags.isEnabled(Flags.FLAG_UNIFIED_DECODER_RENDERER),
        "Flags.FLAG_UNIFIED_DECODER_RENDERER must be enabled");
    String platformCodecName = platformCodecInfo.name;

    // In EXTENSION_RENDERER_MODE_PREFER, extension decoder should be attempted first.
    List<String> codecsPrefer =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_PREFER,
            /* useSecondaryRenderer= */ false);
    assertWithMessage("primary renderer in EXTENSION_RENDERER_MODE_PREFER")
        .that(codecsPrefer)
        .containsExactly(extensionCodecName, platformCodecName)
        .inOrder();

    // In EXTENSION_RENDERER_MODE_ON, platform decoder should be attempted first.
    List<String> codecsOn =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_ON,
            /* useSecondaryRenderer= */ false);
    assertWithMessage("primary renderer in EXTENSION_RENDERER_MODE_ON")
        .that(codecsOn)
        .containsExactly(platformCodecName, extensionCodecName)
        .inOrder();

    // In EXTENSION_RENDERER_MODE_OFF, extension decoder should not be attempted.
    List<String> codecsOff =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_OFF,
            /* useSecondaryRenderer= */ false);
    assertWithMessage("primary renderer in EXTENSION_RENDERER_MODE_OFF")
        .that(codecsOff)
        .containsExactly(platformCodecName);

    // If a custom MediaCodecSelector already includes extension decoders from
    // MediaCodecUtil.getExtensionDecoderInfos, its chosen ordering is preserved without duplicating
    // or moving extension decoders.
    MediaCodecSelector selectorWithCustomExtensionOrder =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.<MediaCodecInfo>builder()
                .add(platformCodecInfo)
                .addAll(
                    MediaCodecUtil.getExtensionDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder))
                .build();
    List<String> customOrderInPrefer =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            selectorWithCustomExtensionOrder,
            EXTENSION_RENDERER_MODE_PREFER,
            /* useSecondaryRenderer= */ false);
    assertWithMessage("custom selector order in EXTENSION_RENDERER_MODE_PREFER")
        .that(customOrderInPrefer)
        .containsExactly(platformCodecName, extensionCodecName)
        .inOrder();
    List<String> customOrderInOn =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            selectorWithCustomExtensionOrder,
            EXTENSION_RENDERER_MODE_ON,
            /* useSecondaryRenderer= */ false);
    assertWithMessage("custom selector order in EXTENSION_RENDERER_MODE_ON")
        .that(customOrderInOn)
        .containsExactly(platformCodecName, extensionCodecName)
        .inOrder();

    List<String> secondaryCodecsPrefer =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_PREFER,
            /* useSecondaryRenderer= */ true);
    assertWithMessage("secondary renderer in EXTENSION_RENDERER_MODE_PREFER")
        .that(secondaryCodecsPrefer)
        .containsExactly(extensionCodecName, platformCodecName)
        .inOrder();

    List<String> secondaryCodecsOn =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_ON,
            /* useSecondaryRenderer= */ true);
    assertWithMessage("secondary renderer in EXTENSION_RENDERER_MODE_ON")
        .that(secondaryCodecsOn)
        .containsExactly(platformCodecName, extensionCodecName)
        .inOrder();

    List<String> secondaryCodecsOff =
        getAttemptedCodecNames(
            extensionRendererClass,
            trackType,
            format,
            platformCodecInfo,
            EXTENSION_RENDERER_MODE_OFF,
            /* useSecondaryRenderer= */ true);
    assertWithMessage("secondary renderer in EXTENSION_RENDERER_MODE_OFF")
        .that(secondaryCodecsOff)
        .containsExactly(platformCodecName);
  }

  private static Renderer[] createRenderers(
      @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode) {
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(ApplicationProvider.getApplicationContext())
            .setExtensionRendererMode(extensionRendererMode);
    return factory.createRenderers(
        new Handler(Looper.getMainLooper()),
        new VideoRendererEventListener() {},
        new AudioRendererEventListener() {},
        (CueGroup cueGroup) -> {},
        (Metadata metadata) -> {});
  }

  private static List<String> getAttemptedCodecNames(
      Class<? extends Renderer> extensionRendererClass,
      @C.TrackType int trackType,
      Format format,
      MediaCodecInfo platformCodecInfo,
      @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode,
      boolean useSecondaryRenderer)
      throws ExoPlaybackException {
    return getAttemptedCodecNames(
        extensionRendererClass,
        trackType,
        format,
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.of(platformCodecInfo),
        extensionRendererMode,
        useSecondaryRenderer);
  }

  private static List<String> getAttemptedCodecNames(
      Class<? extends Renderer> extensionRendererClass,
      @C.TrackType int trackType,
      Format format,
      MediaCodecSelector mediaCodecSelector,
      @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode,
      boolean useSecondaryRenderer)
      throws ExoPlaybackException {
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
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(extensionRendererMode)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(useSecondaryRenderer)
            .setMediaCodecSelector(mediaCodecSelector);

    Renderer[] renderers =
        factory.createRenderers(
            new Handler(Looper.getMainLooper()),
            new VideoRendererEventListener() {},
            new AudioRendererEventListener() {},
            cueGroup -> {},
            metadata -> {});
    @Nullable Renderer secondaryRenderer = null;
    @Nullable SurfaceTexture surfaceTexture = null;
    @Nullable Surface surface = null;
    try {
      @Nullable Renderer targetRenderer = null;
      for (Renderer renderer : renderers) {
        assertThat(renderer).isNotInstanceOf(extensionRendererClass);
        if (renderer.getTrackType() == trackType && renderer instanceof MediaCodecVideoRenderer) {
          targetRenderer = renderer;
        }
      }
      assertThat(targetRenderer).isNotNull();
      Renderer nonNullRenderer = checkNotNull(targetRenderer);
      if (useSecondaryRenderer) {
        secondaryRenderer =
            factory.createSecondaryRenderer(
                nonNullRenderer,
                new Handler(Looper.getMainLooper()),
                new VideoRendererEventListener() {},
                new AudioRendererEventListener() {},
                cueGroup -> {},
                metadata -> {});
        assertThat(secondaryRenderer).isInstanceOf(MediaCodecVideoRenderer.class);
        nonNullRenderer = checkNotNull(secondaryRenderer);
      }

      surfaceTexture = new SurfaceTexture(/* texName= */ 0);
      surface =
          new Surface(surfaceTexture) {
            @Override
            public boolean isValid() {
              return true;
            }
          };

      try {
        nonNullRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        nonNullRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
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
        nonNullRenderer.enable(
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
          nonNullRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
        } catch (ExoPlaybackException ignored) {
          // Expected when all simulated decoder initializations fail.
        }
      } finally {
        if (nonNullRenderer.getState() == Renderer.STATE_ENABLED) {
          nonNullRenderer.disable();
        }
        nonNullRenderer.reset();
      }
    } finally {
      if (surface != null) {
        surface.release();
      }
      if (surfaceTexture != null) {
        surfaceTexture.release();
      }
      if (secondaryRenderer != null) {
        secondaryRenderer.release();
      }
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }

    return attemptedCodecNames;
  }
}
