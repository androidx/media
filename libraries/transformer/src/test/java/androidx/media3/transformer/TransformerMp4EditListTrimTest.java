/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_ELST_SKIP_500MS;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S;
import static androidx.media3.transformer.TestUtil.FILE_JPG_PIXEL_MOTION_PHOTO;
import static androidx.media3.transformer.TestUtil.FILE_MP4_POSITIVE_SHIFT_EDIT_LIST;
import static androidx.media3.transformer.TestUtil.FILE_MP4_TRIM_OPTIMIZATION_180;
import static androidx.media3.transformer.TestUtil.FILE_MP4_TRIM_OPTIMIZATION_270;
import static androidx.media3.transformer.TestUtil.FILE_MP4_VISUAL_TIMESTAMPS;
import static androidx.media3.transformer.TestUtil.getAudioSampleTimesUs;
import static androidx.media3.transformer.TestUtil.getDumpFileName;
import static androidx.media3.transformer.TestUtil.getVideoSampleTimesUs;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.TestTransformerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for MP4 edit list trimming in {@link Transformer}. */
// TODO: b/443998866 - Use MetadataRetriever to get exact duration in all tests.
@RunWith(AndroidJUnit4.class)
public class TransformerMp4EditListTrimTest {

  @Rule public final TemporaryFolder outputDir = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();
  private String outputPath;

  @Before
  public void setup() throws Exception {
    outputPath = outputDir.newFile().getPath();
  }

  @Test
  public void buildTransformer_mp4EditListTrimEnabledButNotSupported_throwsIllegalStateException()
      throws Exception {
    TestTransformerBuilder builder =
        new TestTransformerBuilder(context)
            .setMuxerFactory(new FrameworkMuxer.Factory())
            .experimentalSetMp4EditListTrimEnabled(true);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void exportWithoutTrimming_mp4WithMp4EditListTrimEnabled_exportsWithCorrectTimestamps()
      throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(ASSET_URI_PREFIX + FILE_MP4_VISUAL_TIMESTAMPS).build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isEqualTo(10007);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(outputPath);
    assertThat(videoTimestampsUs).hasSize(300);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(0);
    assertThat(videoTimestampsUs.get(1)).isEqualTo(33_366);
    assertThat(videoTimestampsUs.get(2)).isEqualTo(66_733);
    assertThat(videoTimestampsUs.get(61)).isEqualTo(2_035_366);
    List<Long> audioTimestampsUs = getAudioSampleTimesUs(outputPath);
    assertThat(audioTimestampsUs).hasSize(432);
    assertThat(audioTimestampsUs.get(0)).isEqualTo(0);
    assertThat(audioTimestampsUs.get(1)).isEqualTo(23_219);
    assertThat(audioTimestampsUs.get(2)).isEqualTo(46_439);
    assertThat(audioTimestampsUs.get(431)).isEqualTo(10_007_165);
  }

  @Test
  public void trimAndExport_mp4WithMp4EditListTrimEnabled_exportsWithCorrectTimestamps()
      throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_MP4_VISUAL_TIMESTAMPS)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(50)
                    .setEndPositionMs(2050)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(outputPath);
    assertThat(videoTimestampsUs).hasSize(62);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(-50_000); // Original PTS: 0
    assertThat(videoTimestampsUs.get(1)).isEqualTo(-16_633); // Original PTS: 33_367
    assertThat(videoTimestampsUs.get(2)).isEqualTo(16_733); // Original PTS: 66_733
    assertThat(videoTimestampsUs.get(61)).isEqualTo(1_985_366); // Original PTS: 2_035_366
    // The below timestamps are off by 13 because audio PTS is rounded to the mvhd timesbase.
    List<Long> audioTimestampsUs = getAudioSampleTimesUs(outputPath);
    assertThat(audioTimestampsUs).hasSize(86);
    assertThat(audioTimestampsUs.get(0)).isEqualTo(19_700); // Original PTS: 69_687
    assertThat(audioTimestampsUs.get(1)).isEqualTo(42_919); // Original PTS: 92_916
    assertThat(audioTimestampsUs.get(2)).isEqualTo(66_139); // Original PTS: 116_145
    assertThat(audioTimestampsUs.get(55)).isEqualTo(1_296_797); // Original PTS: 2_100_700
  }

  @Test
  public void trimAndExport_motionPhotoWithMp4EditListTrimEnabled_completesWithCorrectTimestamps()
      throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_JPG_PIXEL_MOTION_PHOTO)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(50)
                    .setEndPositionMs(2050)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(outputPath);
    assertThat(videoTimestampsUs).hasSize(56);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(-50_000); // Original PTS: 0
    assertThat(videoTimestampsUs.get(1)).isEqualTo(-16_655); // Original PTS: 33_334
    assertThat(videoTimestampsUs.get(2)).isEqualTo(16_688); // Original PTS: 66_688
    assertThat(videoTimestampsUs.get(55)).isEqualTo(1_967_311); // Original PTS: 2_100_700
  }

  @Test
  public void trimAndExport_audioEditListWithMp4EditListTrimEnabled_completesWithCorrectTimestamps()
      throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(false);
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(muxerFactory)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_ELST_SKIP_500MS)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(1).build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    TransformerTestRunner.runLooper(transformer);

    // This dump file contains the same samples as the long_edit_list_audioonly.mp4/transmuxed.dump
    // presented 1ms earlier, apart from the first sample which was previously hidden by an edit
    // list and is now removed.
    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            /* originalFileName= */ FILE_AUDIO_ELST_SKIP_500MS, /* modifications...= */
            "clipped_with_edit_list"));
  }

  @Test
  public void
      trimAndExport_withNoKeyFrameBetweenClipTimesAndMp4EditListTrimEnabled_exportsWithCorrectTimestamps()
          throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(10_000)
                    .setEndPositionMs(11_000)
                    .build())
            .build();

    transformer.start(mediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isAtMost(983_333);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(checkNotNull(outputPath));
    assertThat(videoTimestampsUs).hasSize(160);
    // Last key frame before trim point, at 8.3s in original clip.
    assertThat(videoTimestampsUs.get(0)).isEqualTo(-1_666_666);
    assertThat(videoTimestampsUs.get(1)).isEqualTo(-1_650_000);
    // Trim start point, at 10s in original clip.
    assertThat(videoTimestampsUs.get(100)).isEqualTo(0);
    assertThat(videoTimestampsUs.get(101)).isEqualTo(16_666);
    // Trim end point, at 10.983s in original clip.
    assertThat(videoTimestampsUs.get(159)).isEqualTo(983_333);
  }

  @Test
  public void
      trimAndExport_withNoKeyFramesAfterClipStartAndMp4EditListTrimEnabled_exportsWithCorrectTimestamps()
          throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.parse(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S))
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(14_500).build())
            .build();

    transformer.start(mediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isEqualTo(1_016);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(checkNotNull(outputPath));
    assertThat(videoTimestampsUs).hasSize(182);
    // Last key frame before trim point, at 12.5s in original clip.
    assertThat(videoTimestampsUs.get(0)).isEqualTo(-2_000_000);
    assertThat(videoTimestampsUs.get(1)).isEqualTo(-1_983_333);
    // Trim start point, at 14.5s in original clip.
    assertThat(videoTimestampsUs.get(120)).isEqualTo(0);
    assertThat(videoTimestampsUs.get(121)).isEqualTo(16_666);
    // Trim end point, at 15.516s in original clip.
    assertThat(videoTimestampsUs.get(181)).isEqualTo(1_016_666);
  }

  @Test
  public void
      trimAndExport_positiveEditListWithMp4EditListTrimEnabled_setsFirstVideoTimestampToZero()
          throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_MP4_POSITIVE_SHIFT_EDIT_LIST)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(100_000).build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.fileSizeBytes).isGreaterThan(0);
    assertThat(exportResult.approximateDurationMs).isEqualTo(9905);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    List<Long> videoTimestampsUs = getVideoSampleTimesUs(checkNotNull(outputPath));
    assertThat(videoTimestampsUs).hasSize(270);
    assertThat(videoTimestampsUs.get(0)).isEqualTo(0);
    // The second sample is originally at 1_033_333, clipping at 100_000 results in 933_333.
    assertThat(videoTimestampsUs.get(1)).isEqualTo(933_333);
  }

  @Test
  public void
      trimAndExport_withInputFileRotated270AndMp4EditListTrimEnabled_exportsWithCorrectOrientation()
          throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_MP4_TRIM_OPTIMIZATION_270)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    Format format = retrieveTrackFormat(context, outputPath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(270);
  }

  @Test
  public void
      trimAndExport_withInputFileRotated180AndMp4EditListTrimEnabled_exportsWithCorrectOrientation()
          throws Exception {
    Transformer transformer =
        new TestTransformerBuilder(context)
            .experimentalSetMp4EditListTrimEnabled(true)
            .setMuxerFactory(new InAppMp4Muxer.Factory())
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_MP4_TRIM_OPTIMIZATION_180)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(500)
                    .setEndPositionMs(2500)
                    .build())
            .build();
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem).build();

    transformer.start(editedMediaItem, outputPath);
    ExportResult exportResult = TransformerTestRunner.runLooper(transformer);

    assertThat(exportResult.approximateDurationMs).isAtMost(2000);
    assertThat(exportResult.videoEncoderName).isNull();
    assertThat(exportResult.audioEncoderName).isNull();
    Format format = retrieveTrackFormat(context, outputPath, C.TRACK_TYPE_VIDEO);
    assertThat(format.rotationDegrees).isEqualTo(180);
  }

  @Test
  public void
      trimAndExport_withClippingStartAtKeyFrameAndMp4EditListTrimmingEnabled_matchesNonOptimizedExport()
          throws Exception {
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ false);
    Transformer transformer =
        new TestTransformerBuilder(context)
            .setMuxerFactory(muxerFactory)
            .experimentalSetMp4EditListTrimEnabled(true)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(12_500)
                    .setEndPositionMs(14_000)
                    .build())
            .build();

    transformer.start(mediaItem, outputDir.newFile().getPath());
    ExportResult result = TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            /* originalFileName= */ FILE_AUDIO_VIDEO_INCREASING_TIMESTAMPS_15S,
            /* modifications...= */ "clipped"));
    assertThat(result.videoEncoderName).isNull();
    assertThat(result.audioEncoderName).isNull();
  }

  @Test
  public void trimAndExport_rawAudioWithMp4EditListTrimEnabled_matchesNonOptimizedExport()
      throws Exception {
    CapturingMuxer.Factory muxerFactory =
        new CapturingMuxer.Factory(/* handleAudioAsPcm= */ true, new InAppMp4Muxer.Factory());
    Transformer transformer =
        new TestTransformerBuilder(context)
            .setMuxerFactory(muxerFactory)
            .experimentalSetMp4EditListTrimEnabled(true)
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(500_000)
                    .setEndPositionUs(900_000)
                    .build())
            .build();

    transformer.start(mediaItem, outputDir.newFile().getPath());
    ExportResult result = TransformerTestRunner.runLooper(transformer);

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(
            /* originalFileName= */ FILE_AUDIO_RAW, /* modifications...= */
            // This dump file has the same bytes as the "clipped" file but with the mp4 type.
            "clipped_with_edit_list"));
    assertThat(result.videoEncoderName).isNull();
    assertThat(result.audioEncoderName).isNull();
  }
}
