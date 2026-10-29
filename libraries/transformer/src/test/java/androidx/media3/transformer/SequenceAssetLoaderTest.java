/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runLooperUntil;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.getIt35SampleDataByTimeUs;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.video.Frame;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.media3.transformer.TestUtil.MetadataRecordingAssetLoaderListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link SequenceAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class SequenceAssetLoaderTest {

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(ShadowMediaCodecConfig.CODEC_INFO_AV1),
          /* encoders= */ ImmutableList.of());

  @Test
  @Config(minSdk = 37) // HAGC tracks are only merged into the video samples from API 37.
  public void sequenceAssetLoader_withTwoIt35Items_forwardsHagcPayloadsToSampleConsumer()
      throws Exception {
    String it35FilePath = "mp4/sample_with_it35_track.mp4";
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + it35FilePath)).build();
    Context context = ApplicationProvider.getApplicationContext();
    AssetLoader.Factory assetLoaderFactory =
        new ExoPlayerAssetLoader.Factory(
            context, new DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT);
    MetadataRecordingAssetLoaderListener listener = new MetadataRecordingAssetLoaderListener();
    SequenceAssetLoader sequenceAssetLoader =
        new SequenceAssetLoader(
            EditedMediaItemSequence.withVideoFrom(
                ImmutableList.of(editedMediaItem, editedMediaItem)),
            assetLoaderFactory,
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false),
            listener,
            Clock.DEFAULT,
            Looper.myLooper());

    sequenceAssetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoInputEnded.get() || listener.exception.get() != null;
        });

    assertThat(listener.exception.get()).isNull();
    assertThat(listener.metadataByFrameTimeUs).isNotEmpty();
    ImmutableMap<Long, byte[]> it35SampleDataByTimeUs =
        getIt35SampleDataByTimeUs("media/" + it35FilePath);
    // The frames of each item are registered with their presentation times in the item, so the
    // frames of the second item replace those of the first item in the map.
    for (Map.Entry<Long, ImmutableMap<String, Object>> entry :
        listener.metadataByFrameTimeUs.entrySet()) {
      assertWithMessage("HAGC payload of the frame at %s us mismatches", entry.getKey())
          .that(entry.getValue().get(Frame.KEY_HDR_ST2094_50_INFO))
          .isEqualTo(it35SampleDataByTimeUs.get(entry.getKey()));
    }
  }
}
