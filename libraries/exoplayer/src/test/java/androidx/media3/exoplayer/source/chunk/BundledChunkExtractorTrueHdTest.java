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
package androidx.media3.exoplayer.source.chunk;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BundledChunkExtractorTrueHdTest {

  private static final String TRUEHD_FRAGMENTED_ASSET = "media/mp4/sample_dthd_fragmented.mp4";
  private static final int[] MEDIA_SEGMENT_ENDS = {23461, 47105, 71009, 95017, 99389};

  private final Context context = getApplicationContext();

  @Test
  public void trueHd_reusedExtractorAcrossMediaSegments_outputsEverySampleByte() throws Exception {
    byte[] data = TestUtil.getByteArray(context, TRUEHD_FRAGMENTED_ASSET);
    FakeTrackOutput singlePassOutput =
        extract(data, new int[] {MEDIA_SEGMENT_ENDS[MEDIA_SEGMENT_ENDS.length - 1]});
    FakeTrackOutput segmentedOutput = extract(data, MEDIA_SEGMENT_ENDS);

    // Four 120-sample segments produce eight chunks each; the final 22 samples produce two.
    segmentedOutput.assertSampleCount(34);
    assertThat(concatenateSampleData(segmentedOutput))
        .isEqualTo(concatenateSampleData(singlePassOutput));
  }

  private static FakeTrackOutput extract(byte[] data, int[] segmentEnds) throws IOException {
    ChunkExtractor chunkExtractor =
        new BundledChunkExtractor(
            new FragmentedMp4Extractor(),
            C.TRACK_TYPE_AUDIO,
            new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build());
    FakeTrackOutput trackOutput =
        new FakeTrackOutput(C.TRACK_TYPE_AUDIO, /* deduplicateConsecutiveFormats= */ false);
    ChunkExtractor.TrackOutputProvider trackOutputProvider = (id, type) -> trackOutput;
    int segmentStart = 0;
    for (int segmentEnd : segmentEnds) {
      chunkExtractor.init(trackOutputProvider, /* startTimeUs= */ C.TIME_UNSET, C.TIME_UNSET);
      ExtractorInput input =
          new FakeExtractorInput.Builder()
              .setData(Arrays.copyOfRange(data, segmentStart, segmentEnd))
              .build();
      while (chunkExtractor.read(input)) {}
      segmentStart = segmentEnd;
    }
    return trackOutput;
  }

  private static byte[] concatenateSampleData(FakeTrackOutput trackOutput) {
    ByteArrayOutputStream sampleData = new ByteArrayOutputStream();
    for (int i = 0; i < trackOutput.getSampleCount(); i++) {
      byte[] sample = trackOutput.getSampleData(i);
      sampleData.write(sample, 0, sample.length);
    }
    return sampleData.toByteArray();
  }
}
