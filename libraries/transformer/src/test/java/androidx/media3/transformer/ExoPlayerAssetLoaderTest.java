/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.IT35_MP4_ASSET;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runLooperUntil;
import static androidx.media3.transformer.TestUtil.getIt35SampleDataByTimeUs;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.video.Frame;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.media3.transformer.TestUtil.MetadataRecordingAssetLoaderListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link ExoPlayerAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class ExoPlayerAssetLoaderTest {

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(ShadowMediaCodecConfig.CODEC_INFO_AV1),
          /* encoders= */ ImmutableList.of());

  private static final String SINGLE_TRACK_URI = "asset:///media/mp4/sample.mp4";
  // Contains two representations of asset:///assets/media/dash/ttml-in-mp4/sample.video.mp4
  // one at 360p and the other at 240p.
  private static final String MULTI_TRACK_URI = "asset:///media/dash/multi-track/sample.mpd";

  @Test
  public void exoPlayerAssetLoader_callsListenerCallbacksInRightOrder() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    // Use default clock so that messages sent on different threads are not always executed in the
    // order in which they are received.
    Clock clock = Clock.DEFAULT;
    AssetLoader assetLoader =
        getAssetLoader(
            listener, clock, MediaItem.fromUri(SINGLE_TRACK_URI), /* trackSelectorFactory= */ null);

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          sleep(listener.exceptionRef);
          return (listener.audioOutputFormat != null && listener.videoOutputFormat != null)
              || listener.exceptionRef.get() != null;
        });

    assertThat(listener.exceptionRef.get()).isNull();
  }

  @Test
  public void exoPlayerAssetLoader_withMaxVideoSize_loadsLowResolutionTrack() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    int expectedOutputResolutionHeight = 240;
    DefaultTrackSelector.Parameters trackSelectorParameters =
        new DefaultTrackSelector.Parameters.Builder()
            .setMaxVideoSize(
                /* maxVideoWidth= */ Integer.MAX_VALUE,
                /* maxVideoHeight= */ expectedOutputResolutionHeight)
            .setForceHighestSupportedBitrate(true)
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build();
    TrackSelector.Factory trackSelectorFactory =
        context -> {
          DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
          trackSelector.setParameters(trackSelectorParameters);
          return trackSelector;
        };
    // Use default clock so that messages sent on different threads are not always executed in the
    // order in which they are received.
    Clock clock = Clock.DEFAULT;
    AssetLoader assetLoader =
        getAssetLoader(listener, clock, MediaItem.fromUri(MULTI_TRACK_URI), trackSelectorFactory);

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          sleep(listener.exceptionRef);
          return listener.videoOutputFormat != null || listener.exceptionRef.get() != null;
        });

    assertThat(listener.exceptionRef.get()).isNull();
    assertThat(checkNotNull(listener.videoOutputFormat).height)
        .isEqualTo(expectedOutputResolutionHeight);
  }

  @Test
  public void exoPlayerAssetLoader_withNoMaxVideoSize_loadsHighResolutionTrack() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    int expectedOutputResolutionHeight = 360;
    DefaultTrackSelector.Parameters trackSelectorParameters =
        new DefaultTrackSelector.Parameters.Builder()
            .setForceHighestSupportedBitrate(true)
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .build();
    TrackSelector.Factory trackSelectorFactory =
        context -> {
          DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
          trackSelector.setParameters(trackSelectorParameters);
          return trackSelector;
        };
    // Use default clock so that messages sent on different threads are not always executed in the
    // order in which they are received.
    Clock clock = Clock.DEFAULT;
    AssetLoader assetLoader =
        getAssetLoader(listener, clock, MediaItem.fromUri(MULTI_TRACK_URI), trackSelectorFactory);

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          sleep(listener.exceptionRef);
          return listener.videoOutputFormat != null || listener.exceptionRef.get() != null;
        });

    assertThat(listener.exceptionRef.get()).isNull();
    assertThat(checkNotNull(listener.videoOutputFormat).height)
        .isEqualTo(expectedOutputResolutionHeight);
  }

  @Test
  @Config(minSdk = 37) // HAGC tracks are only merged into the video samples from API 37.
  public void exoPlayerAssetLoader_withIt35Track_forwardsMetadataToSampleConsumer()
      throws Exception {
    MetadataRecordingAssetLoaderListener listener = new MetadataRecordingAssetLoaderListener();
    AssetLoader assetLoader =
        getAssetLoader(
            listener,
            Clock.DEFAULT,
            MediaItem.fromUri(IT35_MP4_ASSET.uri),
            /* trackSelectorFactory= */ null);

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoInputEnded.get() || listener.exception.get() != null;
        });

    assertThat(listener.exception.get()).isNull();
    assertThat(listener.metadataByFrameTimeUs).isNotEmpty();
    ImmutableMap<Long, byte[]> it35SampleDataByTimeUs =
        getIt35SampleDataByTimeUs(IT35_MP4_ASSET.uri.replace("asset:///", ""));
    // The file has an it35 sample for each video frame.
    for (Map.Entry<Long, ImmutableMap<String, Object>> entry :
        listener.metadataByFrameTimeUs.entrySet()) {
      assertWithMessage("HAGC payload of the frame at %s us mismatches", entry.getKey())
          .that(entry.getValue().get(Frame.KEY_HDR_ST2094_50_INFO))
          .isEqualTo(it35SampleDataByTimeUs.get(entry.getKey()));
    }
  }

  @Test
  @Config(minSdk = 37) // HAGC tracks are only merged into the video samples from API 37.
  public void exoPlayerAssetLoader_withClippedIt35Track_forwardsHagcPayloadsOfKeptFrames()
      throws Exception {
    long clipStartUs = 500_000;
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(IT35_MP4_ASSET.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clipStartUs)
                    .build())
            .build();
    MetadataRecordingAssetLoaderListener listener = new MetadataRecordingAssetLoaderListener();
    AssetLoader assetLoader =
        getAssetLoader(listener, Clock.DEFAULT, mediaItem, /* trackSelectorFactory= */ null);

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoInputEnded.get() || listener.exception.get() != null;
        });

    assertThat(listener.exception.get()).isNull();
    assertThat(listener.metadataByFrameTimeUs).isNotEmpty();
    ImmutableMap<Long, byte[]> it35SampleDataByTimeUs =
        getIt35SampleDataByTimeUs(IT35_MP4_ASSET.uri.replace("asset:///", ""));
    // The frames before the clip start are decoded from the key frame at 0 and then dropped, so
    // their HAGC payloads must not be forwarded with the kept frames.
    for (Map.Entry<Long, ImmutableMap<String, Object>> entry :
        listener.metadataByFrameTimeUs.entrySet()) {
      long sampleTimeUs = entry.getKey() + clipStartUs;
      assertWithMessage("HAGC payload of the frame at %s us in the file mismatches", sampleTimeUs)
          .that(entry.getValue().get(Frame.KEY_HDR_ST2094_50_INFO))
          .isEqualTo(it35SampleDataByTimeUs.get(sampleTimeUs));
    }
  }

  private static AssetLoader getAssetLoader(
      AssetLoader.Listener listener,
      Clock clock,
      MediaItem mediaItem,
      TrackSelector.@Nullable Factory trackSelectorFactory) {
    Context context = ApplicationProvider.getApplicationContext();
    Codec.DecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(context).build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();
    return new ExoPlayerAssetLoader.Factory(
            context,
            decoderFactory,
            clock,
            /* mediaSourceFactory= */ null,
            trackSelectorFactory,
            /* logSessionId= */ null,
            /* loadControl= */ null)
        .createAssetLoader(
            editedMediaItem,
            Looper.myLooper(),
            listener,
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false));
  }

  private static void sleep(AtomicReference<@Nullable Exception> exceptionRef) {
    try {
      Thread.sleep(10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exceptionRef.set(e);
    }
  }
}
