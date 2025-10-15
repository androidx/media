/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.dash;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.inspector.MetadataRetriever;
import androidx.media3.test.utils.FakeClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.LooperMode;

/** {@link MetadataRetriever} tests for DASH streams. */
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@RunWith(AndroidJUnit4.class)
public class DashMetadataRetrieverTest {

  private static final long TEST_TIMEOUT_SEC = 10;

  @Test
  public void retrieveUsingInstance_dashStream_outputsExpectedResult() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/dash/webvtt-in-mp4/sample.mpd"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(ApplicationProvider.getApplicationContext(), mediaItem)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, SECONDS);

      assertThat(trackGroups.length).isEqualTo(2);
      // Text group.
      assertThat(trackGroups.get(0).length).isEqualTo(1);
      assertThat(trackGroups.get(0).getFormat(0).sampleMimeType)
          .isEqualTo(MimeTypes.APPLICATION_MEDIA3_CUES);
      // Video group.
      assertThat(trackGroups.get(1).length).isEqualTo(1);
      assertThat(trackGroups.get(1).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
      // Timeline.
      assertThat(timeline.getWindowCount()).isEqualTo(1);
      // Duration.
      assertThat(durationUs).isEqualTo(1_001_000);
    }
  }
}
