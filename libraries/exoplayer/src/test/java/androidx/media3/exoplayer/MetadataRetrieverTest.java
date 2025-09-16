/*
 * Copyright 2020 The Android Open Source Project
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

import static androidx.media3.container.MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS;
import static androidx.media3.container.MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32;
import static androidx.media3.container.MdtaMetadataEntry.TYPE_INDICATOR_STRING;
import static androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;
import androidx.media3.extractor.metadata.mp4.SlowMotionData;
import androidx.media3.extractor.metadata.mp4.SmtaMetadataEntry;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** Tests for {@link MetadataRetriever}. */
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
@RunWith(AndroidJUnit4.class)
public class MetadataRetrieverTest {

  private static final long TEST_TIMEOUT_SEC = 10;

  private Context context;
  private FakeClock clock;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    clock = new FakeClock(/* isAutoAdvancing= */ true);
  }

  @After
  public void tearDown() throws Exception {
    // Drain loopers to ensure async cleanup tasks complete before the test ends.
    // This prevents state leakage between tests.
    for (Looper looper : ShadowLooper.getAllLoopers()) {
      try {
        shadowOf(looper).idle();
      } catch (IllegalStateException e) {
        // Looper was already quit, safe to ignore.
      }
    }
    MetadataRetriever.setMaximumParallelRetrievals(
        MetadataRetriever.DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS);
  }

  // --- Tests for deprecated static methods ---

  @Test
  public void retrieveMetadata_singleMediaItem_outputsExpectedMetadata() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);
    TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups.length).isEqualTo(2);
    // Video group.
    assertThat(trackGroups.get(0).length).isEqualTo(1);
    assertThat(trackGroups.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    // Audio group.
    assertThat(trackGroups.get(1).length).isEqualTo(1);
    assertThat(trackGroups.get(1).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
  }

  @Test
  public void retrieveMetadata_multipleMediaItems_outputsExpectedMetadata() throws Exception {
    MediaItem mediaItem1 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    MediaItem mediaItem2 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp3/bear-id3.mp3"));

    ListenableFuture<TrackGroupArray> trackGroupsFuture1 =
        retrieveMetadata(context, mediaItem1, clock);
    ListenableFuture<TrackGroupArray> trackGroupsFuture2 =
        retrieveMetadata(context, mediaItem2, clock);
    TrackGroupArray trackGroups1 = trackGroupsFuture1.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
    TrackGroupArray trackGroups2 = trackGroupsFuture2.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    // First track group.
    assertThat(trackGroups1.length).isEqualTo(2);
    // First track group - Video group.
    assertThat(trackGroups1.get(0).length).isEqualTo(1);
    assertThat(trackGroups1.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    // First track group - Audio group.
    assertThat(trackGroups1.get(1).length).isEqualTo(1);
    assertThat(trackGroups1.get(1).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);

    // Second track group.
    assertThat(trackGroups2.length).isEqualTo(1);
    // Second track group - Audio group.
    assertThat(trackGroups2.get(0).length).isEqualTo(1);
    assertThat(trackGroups2.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_MPEG);
  }

  @Test
  public void retrieveMetadata_heicMotionPhoto_outputsExpectedMetadata() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/heif/sample_MP.heic"));
    MotionPhotoMetadata expectedMotionPhotoMetadata =
        new MotionPhotoMetadata(
            /* photoStartPosition= */ 0,
            /* photoSize= */ 28_853,
            /* photoPresentationTimestampUs= */ C.TIME_UNSET,
            /* videoStartPosition= */ 28_869,
            /* videoSize= */ 28_803);

    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);
    TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups.length).isEqualTo(3);
    assertThat(trackGroups.get(0).length).isEqualTo(1);
    assertThat(trackGroups.get(0).getFormat(0).metadata.length()).isEqualTo(1);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(0))
        .isEqualTo(expectedMotionPhotoMetadata);
  }

  @Test
  public void retrieveMetadata_heicStillPhotoWithImageDuration_outputsEmptyMetadata()
      throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset://android_asset/media/heif/sample_still_photo.heic")
            .setImageDurationMs(3000L)
            .build();

    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);
    TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups.length).isEqualTo(1);
    assertThat(trackGroups.get(0).length).isEqualTo(1);
    assertThat(trackGroups.get(0).getFormat(0).metadata).isNull();
  }

  @Test
  public void retrieveMetadata_sefSlowMotionAvc_outputsExpectedMetadata() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample_sef_slow_motion.mp4"));
    MdtaMetadataEntry expectedAndroidVersionMetadata =
        new MdtaMetadataEntry(
            /* key= */ "com.android.version",
            /* value= */ Util.getUtf8Bytes("10"),
            TYPE_INDICATOR_STRING);
    MdtaMetadataEntry expectedTemporalLayersCountMetadata =
        new MdtaMetadataEntry(
            /* key= */ "com.android.video.temporal_layers_count",
            /* value= */ Ints.toByteArray(4),
            MdtaMetadataEntry.TYPE_INDICATOR_INT32);
    SmtaMetadataEntry expectedSmtaEntry =
        new SmtaMetadataEntry(/* captureFrameRate= */ 240, /* svcTemporalLayerCount= */ 4);
    List<SlowMotionData.Segment> segments = new ArrayList<>();
    segments.add(
        new SlowMotionData.Segment(
            /* startTimeMs= */ 88, /* endTimeMs= */ 879, /* speedDivisor= */ 2));
    segments.add(
        new SlowMotionData.Segment(
            /* startTimeMs= */ 1255, /* endTimeMs= */ 1970, /* speedDivisor= */ 8));
    SlowMotionData expectedSlowMotionData = new SlowMotionData(segments);
    Mp4TimestampData expectedMp4TimestampData =
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 3_686_904_890L,
            /* modificationTimestampSeconds= */ 3_686_904_890L,
            /* timescale= */ 1000);
    MdtaMetadataEntry expectedMdtaEntry =
        new MdtaMetadataEntry(
            KEY_ANDROID_CAPTURE_FPS, /* value= */ Util.toByteArray(240.0f), TYPE_INDICATOR_FLOAT32);

    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);
    TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups.length).isEqualTo(2); // Video and audio
    // Audio
    assertThat(trackGroups.get(0).getFormat(0).metadata.length()).isEqualTo(5);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(0))
        .isEqualTo(expectedAndroidVersionMetadata);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(1))
        .isEqualTo(expectedTemporalLayersCountMetadata);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(2)).isEqualTo(expectedSlowMotionData);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(3)).isEqualTo(expectedSmtaEntry);
    assertThat(trackGroups.get(0).getFormat(0).metadata.get(4)).isEqualTo(expectedMp4TimestampData);

    // Video
    assertThat(trackGroups.get(1).getFormat(0).metadata.length()).isEqualTo(6);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(0))
        .isEqualTo(expectedAndroidVersionMetadata);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(1))
        .isEqualTo(expectedTemporalLayersCountMetadata);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(2)).isEqualTo(expectedMdtaEntry);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(3)).isEqualTo(expectedSlowMotionData);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(4)).isEqualTo(expectedSmtaEntry);
    assertThat(trackGroups.get(1).getFormat(0).metadata.get(5)).isEqualTo(expectedMp4TimestampData);
  }

  @Test
  public void retrieveMetadata_sefSlowMotionHevc_outputsExpectedMetadata() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(
            Uri.parse("asset://android_asset/media/mp4/sample_sef_slow_motion_hevc.mp4"));
    MdtaMetadataEntry expectedAndroidVersionMetadata =
        new MdtaMetadataEntry(
            /* key= */ "com.android.version",
            /* value= */ Util.getUtf8Bytes("13"),
            TYPE_INDICATOR_STRING);
    SmtaMetadataEntry expectedSmtaEntry =
        new SmtaMetadataEntry(/* captureFrameRate= */ 240, /* svcTemporalLayerCount= */ 4);
    SlowMotionData expectedSlowMotionData =
        new SlowMotionData(
            ImmutableList.of(
                new SlowMotionData.Segment(
                    /* startTimeMs= */ 2128, /* endTimeMs= */ 9856, /* speedDivisor= */ 8)));
    MdtaMetadataEntry expectedCaptureFpsMdtaEntry =
        new MdtaMetadataEntry(
            KEY_ANDROID_CAPTURE_FPS, /* value= */ Util.toByteArray(240.0f), TYPE_INDICATOR_FLOAT32);
    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);
    TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups.length).isEqualTo(2); // Video and audio

    // Video
    Metadata videoFormatMetadata = trackGroups.get(0).getFormat(0).metadata;
    List<Metadata.Entry> videoMetadataEntries = new ArrayList<>();
    for (int i = 0; i < videoFormatMetadata.length(); i++) {
      videoMetadataEntries.add(videoFormatMetadata.get(i));
    }
    assertThat(videoMetadataEntries).contains(expectedAndroidVersionMetadata);
    assertThat(videoMetadataEntries).contains(expectedSlowMotionData);
    assertThat(videoMetadataEntries).contains(expectedSmtaEntry);
    assertThat(videoMetadataEntries).contains(expectedCaptureFpsMdtaEntry);

    // Audio
    Metadata audioFormatMetadata = trackGroups.get(1).getFormat(0).metadata;
    List<Metadata.Entry> audioMetadataEntries = new ArrayList<>();
    for (int i = 0; i < audioFormatMetadata.length(); i++) {
      audioMetadataEntries.add(audioFormatMetadata.get(i));
    }
    assertThat(audioMetadataEntries).contains(expectedAndroidVersionMetadata);
    assertThat(audioMetadataEntries).contains(expectedSlowMotionData);
    assertThat(audioMetadataEntries).contains(expectedSmtaEntry);
  }

  @Test
  public void retrieveMetadata_invalidMediaItem_throwsError() {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/does_not_exist"));

    ListenableFuture<TrackGroupArray> trackGroupsFuture =
        retrieveMetadata(context, mediaItem, clock);

    assertThrows(
        ExecutionException.class, () -> trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS));
  }

  // --- Tests for new instance-based API ---

  @Test
  public void retrieveUsingInstance_singleMediaItem_outputsExpectedResult() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups.length).isEqualTo(2);
      // Video group.
      assertThat(trackGroups.get(0).length).isEqualTo(1);
      assertThat(trackGroups.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
      // Audio group.
      assertThat(trackGroups.get(1).length).isEqualTo(1);
      assertThat(trackGroups.get(1).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
      // Assert timeline.
      assertThat(timeline.getWindowCount()).isEqualTo(1);
      assertThat(timeline.getWindow(0, new Timeline.Window()).getDurationUs()).isEqualTo(1_024_000);
      // Assert duration.
      assertThat(durationUs).isEqualTo(1_024_000);
    }
  }

  @Test
  public void retrieveUsingInstance_multipleMediaItems_outputsExpectedResults() throws Exception {
    MediaItem mediaItem1 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    MediaItem mediaItem2 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp3/bear-id3.mp3"));

    try (MetadataRetriever retriever1 =
            new MetadataRetriever.Builder(context, mediaItem1).setClock(clock).build();
        MetadataRetriever retriever2 =
            new MetadataRetriever.Builder(context, mediaItem2).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture1 = retriever1.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture1 = retriever1.retrieveTimeline();
      ListenableFuture<Long> durationFuture1 = retriever1.retrieveDurationUs();
      ListenableFuture<TrackGroupArray> trackGroupsFuture2 = retriever2.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture2 = retriever2.retrieveTimeline();
      ListenableFuture<Long> durationFuture2 = retriever2.retrieveDurationUs();

      TrackGroupArray trackGroups1 = trackGroupsFuture1.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline1 = timelineFuture1.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs1 = durationFuture1.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      TrackGroupArray trackGroups2 = trackGroupsFuture2.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline2 = timelineFuture2.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs2 = durationFuture2.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      // First track group.
      assertThat(trackGroups1.length).isEqualTo(2);
      // First track group - Video group.
      assertThat(trackGroups1.get(0).length).isEqualTo(1);
      assertThat(trackGroups1.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
      // First track group - Audio group.
      assertThat(trackGroups1.get(1).length).isEqualTo(1);
      assertThat(trackGroups1.get(1).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
      // Assert timeline.
      assertThat(timeline1.getWindowCount()).isEqualTo(1);
      // Assert duration.
      assertThat(durationUs1).isEqualTo(1_024_000);

      // Second track group.
      assertThat(trackGroups2.length).isEqualTo(1);
      // Second track group - Audio group.
      assertThat(trackGroups2.get(0).length).isEqualTo(1);
      assertThat(trackGroups2.get(0).getFormat(0).sampleMimeType).isEqualTo(MimeTypes.AUDIO_MPEG);
      // Assert timeline.
      assertThat(timeline2.getWindowCount()).isEqualTo(1);
      // Assert duration.
      assertThat(durationUs2).isEqualTo(2_808_000);
    }
  }

  @Test
  public void retrieveUsingInstance_heicMotionPhoto_outputsExpectedResult() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/heif/sample_MP.heic"));
    MotionPhotoMetadata expectedMotionPhotoMetadata =
        new MotionPhotoMetadata(
            /* photoStartPosition= */ 0,
            /* photoSize= */ 28_853,
            /* photoPresentationTimestampUs= */ C.TIME_UNSET,
            /* videoStartPosition= */ 28_869,
            /* videoSize= */ 28_803);

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups.length).isEqualTo(3);
      assertThat(trackGroups.get(0).length).isEqualTo(1);
      assertThat(trackGroups.get(0).getFormat(0).metadata.length()).isEqualTo(1);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(0))
          .isEqualTo(expectedMotionPhotoMetadata);
      assertThat(timeline.getWindowCount()).isEqualTo(1);
      assertThat(durationUs).isEqualTo(1_231_000);
    }
  }

  @Test
  public void
      retrieveUsingInstance_heicStillPhotoWithImageDuration_outputsEmptyMetadataAndImageDuration()
          throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset://android_asset/media/heif/sample_still_photo.heic")
            .setImageDurationMs(3000L)
            .build();

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups.length).isEqualTo(1);
      assertThat(trackGroups.get(0).length).isEqualTo(1);
      assertThat(trackGroups.get(0).getFormat(0).metadata).isNull();
      assertThat(timeline.getWindowCount()).isEqualTo(1);
      assertThat(durationUs).isEqualTo(3_000_000);
    }
  }

  @Test
  public void retrieveUsingInstance_sefSlowMotionAvc_outputsExpectedResult() throws Exception {
    MdtaMetadataEntry expectedAndroidVersionMetadata =
        new MdtaMetadataEntry(
            "com.android.version", Util.getUtf8Bytes("10"), TYPE_INDICATOR_STRING);
    MdtaMetadataEntry expectedTemporalLayersCountMetadata =
        new MdtaMetadataEntry(
            "com.android.video.temporal_layers_count",
            Ints.toByteArray(4),
            MdtaMetadataEntry.TYPE_INDICATOR_INT32);
    SmtaMetadataEntry expectedSmtaEntry = new SmtaMetadataEntry(240, 4);
    List<SlowMotionData.Segment> segments = new ArrayList<>();
    segments.add(new SlowMotionData.Segment(88, 879, 2));
    segments.add(new SlowMotionData.Segment(1255, 1970, 8));
    SlowMotionData expectedSlowMotionData = new SlowMotionData(segments);
    Mp4TimestampData expectedMp4TimestampData =
        new Mp4TimestampData(3_686_904_890L, 3_686_904_890L, 1000);
    MdtaMetadataEntry expectedMdtaEntry =
        new MdtaMetadataEntry(
            KEY_ANDROID_CAPTURE_FPS, Util.toByteArray(240.0f), TYPE_INDICATOR_FLOAT32);
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample_sef_slow_motion.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups.length).isEqualTo(2);
      // Audio
      assertThat(trackGroups.get(0).getFormat(0).metadata.length()).isEqualTo(5);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(0))
          .isEqualTo(expectedAndroidVersionMetadata);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(1))
          .isEqualTo(expectedTemporalLayersCountMetadata);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(2)).isEqualTo(expectedSlowMotionData);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(3)).isEqualTo(expectedSmtaEntry);
      assertThat(trackGroups.get(0).getFormat(0).metadata.get(4))
          .isEqualTo(expectedMp4TimestampData);

      // Video
      assertThat(trackGroups.get(1).getFormat(0).metadata.length()).isEqualTo(6);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(0))
          .isEqualTo(expectedAndroidVersionMetadata);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(1))
          .isEqualTo(expectedTemporalLayersCountMetadata);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(2)).isEqualTo(expectedMdtaEntry);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(3)).isEqualTo(expectedSlowMotionData);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(4)).isEqualTo(expectedSmtaEntry);
      assertThat(trackGroups.get(1).getFormat(0).metadata.get(5))
          .isEqualTo(expectedMp4TimestampData);

      // Timeline
      assertThat(timeline.getWindowCount()).isEqualTo(1);

      // Duration
      assertThat(durationUs).isEqualTo(2_152_000);
    }
  }

  @Test
  public void retrieveUsingInstance_sefSlowMotionHevc_outputsExpectedResult() throws Exception {
    MdtaMetadataEntry expectedAndroidVersionMetadata =
        new MdtaMetadataEntry(
            "com.android.version", Util.getUtf8Bytes("13"), TYPE_INDICATOR_STRING);
    SmtaMetadataEntry expectedSmtaEntry = new SmtaMetadataEntry(240, 4);
    SlowMotionData expectedSlowMotionData =
        new SlowMotionData(ImmutableList.of(new SlowMotionData.Segment(2128, 9856, 8)));
    MdtaMetadataEntry expectedCaptureFpsMdtaEntry =
        new MdtaMetadataEntry(
            KEY_ANDROID_CAPTURE_FPS, Util.toByteArray(240.0f), TYPE_INDICATOR_FLOAT32);
    MediaItem mediaItem =
        MediaItem.fromUri(
            Uri.parse("asset://android_asset/media/mp4/sample_sef_slow_motion_hevc.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();

      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      Timeline timeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      long durationUs = durationFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups.length).isEqualTo(2);

      // Video
      Metadata videoFormatMetadata = trackGroups.get(0).getFormat(0).metadata;
      List<Metadata.Entry> videoMetadataEntries = new ArrayList<>();
      for (int i = 0; i < videoFormatMetadata.length(); i++) {
        videoMetadataEntries.add(videoFormatMetadata.get(i));
      }
      assertThat(videoMetadataEntries).contains(expectedAndroidVersionMetadata);
      assertThat(videoMetadataEntries).contains(expectedSlowMotionData);
      assertThat(videoMetadataEntries).contains(expectedSmtaEntry);
      assertThat(videoMetadataEntries).contains(expectedCaptureFpsMdtaEntry);

      // Audio
      Metadata audioFormatMetadata = trackGroups.get(1).getFormat(0).metadata;
      List<Metadata.Entry> audioMetadataEntries = new ArrayList<>();
      for (int i = 0; i < audioFormatMetadata.length(); i++) {
        audioMetadataEntries.add(audioFormatMetadata.get(i));
      }
      assertThat(audioMetadataEntries).contains(expectedAndroidVersionMetadata);
      assertThat(audioMetadataEntries).contains(expectedSlowMotionData);
      assertThat(audioMetadataEntries).contains(expectedSmtaEntry);

      // Assert timeline.
      assertThat(timeline.getWindowCount()).isEqualTo(1);

      // Duration
      assertThat(durationUs).isEqualTo(11_793_400);
    }
  }

  @Test
  public void retrieveUsingInstance_invalidMediaItem_throwsError() {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/does_not_exist"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();

      assertThrows(
          ExecutionException.class,
          () -> trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS));
    }
  }

  @Test
  public void retrieveUsingInstance_subsequentRetrievals_completeImmediately() throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build()) {
      // Retrieve one future and wait for it to complete.
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      TrackGroupArray trackGroups = trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      // Now, after the first retrieval is complete, request the others.
      ListenableFuture<Long> durationFuture = retriever.retrieveDurationUs();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();

      // Get the results of the subsequent futures. They should complete almost instantly.
      long durationUs = durationFuture.get(10, TimeUnit.MILLISECONDS);
      Timeline timeline = timelineFuture.get(10, TimeUnit.MILLISECONDS);

      assertThat(trackGroups.length).isEqualTo(2);
      assertThat(durationUs).isEqualTo(1_024_000);
      assertThat(timeline.getWindowCount()).isEqualTo(1);
    }
  }

  @Test
  public void retrieveUsingInstance_releasesMediaSource_afterRetrieval() throws Exception {
    FakeMediaSource fakeMediaSource = new FakeMediaSource();
    MediaSource.Factory mediaSourceFactory = mock(MediaSource.Factory.class);
    when(mediaSourceFactory.createMediaSource(any(MediaItem.class))).thenReturn(fakeMediaSource);
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(/* context= */ null, mediaItem)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      trackGroupsFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    // The MediaSource is released on a background thread. We sleep to give that thread a chance
    // to complete the release before we assert its state.
    Thread.sleep(500);
    fakeMediaSource.assertReleased();
  }

  @Test
  public void retrieveUsingInstance_releasesMediaSource_afterCancellation() throws Exception {
    FakeMediaSource fakeMediaSource = new FakeMediaSource();
    fakeMediaSource.setAllowPreparation(false);
    MediaSource.Factory mediaSourceFactory = mock(MediaSource.Factory.class);
    when(mediaSourceFactory.createMediaSource(any(MediaItem.class))).thenReturn(fakeMediaSource);
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(/* context= */ null, mediaItem)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      trackGroupsFuture.cancel(true);
      assertThrows(CancellationException.class, trackGroupsFuture::get);
    }

    // The MediaSource is released on a background thread. We sleep to give that thread a chance
    // to complete the release before we assert its state.
    Thread.sleep(500);
    fakeMediaSource.assertReleased();
  }

  @Test
  public void retrieveUsingInstance_closeWhileRetrievalOngoing_doesNotInterruptRetrieval()
      throws Exception {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build();
    ListenableFuture<TrackGroupArray> future = retriever.retrieveTrackGroups();
    retriever.close(); // Close immediately. Should not interrupt the retrieval.

    TrackGroupArray trackGroups = future.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(trackGroups).isNotNull();
    assertThat(trackGroups.length).isEqualTo(2);
  }

  @Test
  public void retrieveUsingInstance_cancelOneFuture_doesNotAffectOthers() throws Exception {
    Timeline timeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setPeriodCount(1).build());
    FakeMediaSource fakeMediaSource = new FakeMediaSource(timeline);
    fakeMediaSource.setAllowPreparation(false);
    MediaSource.Factory mediaSourceFactory = mock(MediaSource.Factory.class);
    when(mediaSourceFactory.createMediaSource(any(MediaItem.class))).thenReturn(fakeMediaSource);
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));

    try (MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture = retriever.retrieveTrackGroups();
      ListenableFuture<Timeline> timelineFuture = retriever.retrieveTimeline();

      assertThat(trackGroupsFuture.isDone()).isFalse();
      assertThat(timelineFuture.isDone()).isFalse();
      assertThat(trackGroupsFuture.cancel(true)).isTrue();
      assertThrows(CancellationException.class, trackGroupsFuture::get);
      fakeMediaSource.setAllowPreparation(true);
      // The other future should still complete successfully.
      Timeline retrievedTimeline = timelineFuture.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      assertThat(retrievedTimeline).isNotNull();
      assertThat(retrievedTimeline.getPeriodCount()).isEqualTo(1);
    }
  }

  @Test
  public void retrieveUsingInstance_afterClose_throwsError() {
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    MetadataRetriever retriever =
        new MetadataRetriever.Builder(context, mediaItem).setClock(clock).build();

    retriever.close();

    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> retriever.retrieveTrackGroups().get());
    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("Retriever is released.");
  }

  @Test
  public void setMaximumParallelRetrievals_toOne_processesAllRetrievalsSequentially()
      throws Exception {
    MetadataRetriever.setMaximumParallelRetrievals(1);
    MediaItem mediaItem1 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp4/sample.mp4"));
    MediaItem mediaItem2 =
        MediaItem.fromUri(Uri.parse("asset://android_asset/media/mp3/bear-id3.mp3"));

    try (MetadataRetriever retriever1 =
            new MetadataRetriever.Builder(context, mediaItem1).setClock(clock).build();
        MetadataRetriever retriever2 =
            new MetadataRetriever.Builder(context, mediaItem2).setClock(clock).build()) {
      ListenableFuture<TrackGroupArray> trackGroupsFuture1 = retriever1.retrieveTrackGroups();
      ListenableFuture<TrackGroupArray> trackGroupsFuture2 = retriever2.retrieveTrackGroups();

      TrackGroupArray trackGroups1 = trackGroupsFuture1.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);
      TrackGroupArray trackGroups2 = trackGroupsFuture2.get(TEST_TIMEOUT_SEC, TimeUnit.SECONDS);

      assertThat(trackGroups1.length).isEqualTo(2);
      assertThat(trackGroups2.length).isEqualTo(1);
    }
  }
}
