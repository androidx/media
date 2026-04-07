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
package androidx.media3.muxer;

import static androidx.media3.muxer.MuxerTestUtil.feedInputDataToMuxer;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/**
 * End to end tests for {@link HybridMp4Muxer}.
 *
 * <p>Hybrid MP4 writes fragmented MP4 during recording (crash-safe) and converts to a standard
 * non-fragmented MP4 on finalization. The final output should be readable by {@link Mp4Extractor}
 * and produce the same sample data as the regular {@link Mp4Muxer}.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class HybridMp4MuxerEndToEndTest {
  // Video Codecs
  private static final String H264_MP4 = "mp4/sample_no_bframes.mp4";
  private static final String H264_WITH_NON_REFERENCE_B_FRAMES_MP4 =
      "mp4/bbb_800x640_768kbps_30fps_avc_non_reference_3b.mp4";
  private static final String H264_WITH_PYRAMID_B_FRAMES_MP4 =
      "mp4/bbb_800x640_768kbps_30fps_avc_pyramid_3b.mp4";
  private static final String H265_HDR10_MP4 = "mp4/hdr10-720p.mp4";
  private static final String AV1_MP4 = "mp4/sample_av1.mp4";
  // Audio Codecs
  private static final String AUDIO_ONLY_MP4 = "mp4/sample_audio_only_15s.mp4";

  public static final String MEDIA_ASSET_DIRECTORY = "asset:///media/";

  @Parameters(name = "{0}")
  public static ImmutableList<String> mediaSamples() {
    return ImmutableList.of(
        H264_MP4,
        H264_WITH_NON_REFERENCE_B_FRAMES_MP4,
        H264_WITH_PYRAMID_B_FRAMES_MP4,
        H265_HDR10_MP4,
        AV1_MP4,
        AUDIO_ONLY_MP4);
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Parameter public @MonotonicNonNull String inputFile;

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createHybridMp4File_finalized_isReadableByMp4Extractor() throws Exception {
    String outputPath = temporaryFolder.newFile("hybrid_output.mp4").getPath();

    try (HybridMp4Muxer hybridMuxer =
        new HybridMp4Muxer.Builder(SeekableMuxerOutput.of(new FileOutputStream(outputPath)))
            .build()) {
      hybridMuxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context, hybridMuxer, checkNotNull(MEDIA_ASSET_DIRECTORY + inputFile));
    }

    // The finalized hybrid output should be readable as a non-fragmented MP4.
    FakeExtractorOutput extractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(), checkNotNull(outputPath));
    // Verify tracks were extracted.
    assertThat(extractorOutput.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void createHybridMp4File_finalized_boxStructureIsNonFragmented() throws Exception {
    String outputPath = temporaryFolder.newFile("hybrid_output.mp4").getPath();

    try (HybridMp4Muxer hybridMuxer =
        new HybridMp4Muxer.Builder(SeekableMuxerOutput.of(new FileOutputStream(outputPath)))
            .build()) {
      hybridMuxer.addMetadataEntry(
          new Mp4TimestampData(
              /* creationTimestampSeconds= */ 100_000_000L,
              /* modificationTimestampSeconds= */ 500_000_000L));
      feedInputDataToMuxer(
          context, hybridMuxer, checkNotNull(MEDIA_ASSET_DIRECTORY + inputFile));
    }

    byte[] outputBytes = TestUtil.getByteArrayFromFilePath(checkNotNull(outputPath));
    ByteBuffer buffer = ByteBuffer.wrap(outputBytes);

    // After finalization, the file should contain a top-level moov box (non-fragmented)
    // and should NOT contain any moof boxes at the top level.
    boolean foundMoov = false;
    boolean foundMoof = false;
    while (buffer.remaining() >= 8) {
      long boxSize = buffer.getInt() & 0xFFFFFFFFL;
      int boxType = buffer.getInt();
      if (boxSize == 1 && buffer.remaining() >= 8) {
        // 64-bit extended size.
        boxSize = buffer.getLong();
        if (boxType == 0x6D6F6F76) { // "moov"
          foundMoov = true;
        }
        if (boxType == 0x6D6F6F66) { // "moof"
          foundMoof = true;
        }
        if (boxSize < 16 || boxSize - 16 > buffer.remaining()) {
          break;
        }
        buffer.position(buffer.position() + (int) (boxSize - 16));
      } else {
        if (boxType == 0x6D6F6F76) { // "moov"
          foundMoov = true;
        }
        if (boxType == 0x6D6F6F66) { // "moof"
          foundMoof = true;
        }
        if (boxSize < 8 || boxSize - 8 > buffer.remaining()) {
          break;
        }
        buffer.position(buffer.position() + (int) (boxSize - 8));
      }
    }

    assertThat(foundMoov).isTrue();
    // In a properly finalized hybrid MP4, moof boxes should be absorbed into the mdat.
    assertThat(foundMoof).isFalse();
  }

  @Test
  public void createHybridMp4File_notClosed_isPlayableAsFragmentedMp4() throws Exception {
    String outputPath = temporaryFolder.newFile("hybrid_output.mp4").getPath();

    // Write data but do NOT close — simulating a crash mid-recording.
    SeekableMuxerOutput muxerOutput = SeekableMuxerOutput.of(new FileOutputStream(outputPath));
    HybridMp4Muxer hybridMuxer = new HybridMp4Muxer.Builder(muxerOutput).build();
    hybridMuxer.addMetadataEntry(
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 100_000_000L,
            /* modificationTimestampSeconds= */ 500_000_000L));
    feedInputDataToMuxer(
        context, hybridMuxer, checkNotNull(MEDIA_ASSET_DIRECTORY + inputFile));
    // Intentionally not calling hybridMuxer.close().
    // Flush to disk without finalizing.
    muxerOutput.close();

    // The file should still be playable as fragmented MP4 because the ftyp, fragmented moov
    // (with mvex), and moof+mdat fragments were written during recording.
    FakeExtractorOutput extractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new FragmentedMp4Extractor(), checkNotNull(outputPath));
    assertThat(extractorOutput.numberOfTracks).isGreaterThan(0);
  }
}
