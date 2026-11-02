/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor.text.pgs;

import static androidx.media3.common.Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PgsParser}. */
@RunWith(AndroidJUnit4.class)
public final class PgsParserTest {

  /** Minimal PGS sample defining a 2x2 opaque white bitmap on a 16x8 plane at (4, 2). */
  private static final byte[] samplePgsCue =
      new byte[] {
        // Palette section: type=0x14, length=7
        0x14,
        0x00,
        0x07,
        0x00,
        0x00, // palette id and version
        0x01, // entry index = 1
        (byte) 0xFF, // Y = 255
        (byte) 0x80, // Cr = 128
        (byte) 0x80, // Cb = 128
        (byte) 0xFF, // Alpha = 255
        // Bitmap picture section: type=0x15, length=15
        0x15,
        0x00,
        0x0F,
        0x00,
        0x00,
        0x00, // object id and version
        (byte) 0x80, // first-in-sequence flag
        0x00,
        0x00,
        0x08, // total data length = 8 (width + height + 4 RLE bytes)
        0x00,
        0x02, // width = 2
        0x00,
        0x02, // height = 2
        0x01,
        0x01,
        0x01,
        0x01, // 4 pixels of color index 1
        // Identifier section: type=0x16, length=19
        0x16,
        0x00,
        0x13,
        0x00,
        0x10, // plane width = 16
        0x00,
        0x08, // plane height = 8
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00, // 11 skipped bytes
        0x00,
        0x04, // bitmapX = 4
        0x00,
        0x02, // bitmapY = 2
        // End section: type=0x80, length=0
        (byte) 0x80,
        0x00,
        0x00
      };

  @Test
  public void getCueReplacementBehavior_returnsReplace() {
    PgsParser parser = new PgsParser();

    assertThat(parser.getCueReplacementBehavior()).isEqualTo(CUE_REPLACEMENT_BEHAVIOR_REPLACE);
  }

  @Test
  public void parse_afterResetWithCompressedInput_emitsExpectedCue() throws Exception {
    PgsParser parser = new PgsParser();
    byte[] compressedSample = deflate(samplePgsCue);
    List<CuesWithTiming> firstOutput = new ArrayList<>();
    List<CuesWithTiming> secondOutput = new ArrayList<>();

    parser.parse(compressedSample, OutputOptions.allCues(), firstOutput::add);
    parser.reset();
    parser.parse(compressedSample, OutputOptions.allCues(), secondOutput::add);

    Cue firstCue = Iterables.getOnlyElement(Iterables.getOnlyElement(firstOutput).cues);
    assertThat(firstCue.bitmap).isNotNull();
    assertThat(firstCue.bitmap.getWidth()).isEqualTo(2);
    assertThat(firstCue.bitmap.getHeight()).isEqualTo(2);
    assertThat(firstCue.bitmap.getPixel(0, 0)).isEqualTo(0xFFFFFFFF);
    Cue secondCue = Iterables.getOnlyElement(Iterables.getOnlyElement(secondOutput).cues);
    assertThat(secondCue.bitmap).isNotNull();
    assertThat(secondCue.bitmap.getWidth()).isEqualTo(2);
    assertThat(secondCue.bitmap.getHeight()).isEqualTo(2);
    assertThat(secondCue.bitmap.getPixel(0, 0)).isEqualTo(0xFFFFFFFF);
    assertThat(secondCue.position).isEqualTo(0.25f);
    assertThat(secondCue.line).isEqualTo(0.25f);
    assertThat(secondCue.size).isEqualTo(0.125f);
    assertThat(secondCue.bitmapHeight).isEqualTo(0.25f);
  }

  private static byte[] deflate(byte[] input) throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflaterOutputStream =
        new DeflaterOutputStream(outputStream, new Deflater(Deflater.BEST_SPEED))) {
      deflaterOutputStream.write(input);
    }
    return outputStream.toByteArray();
  }
}
