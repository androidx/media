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
package androidx.media3.extractor.text.dvb;

import static androidx.media3.common.Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser.OutputOptions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DvbParser}. */
@RunWith(AndroidJUnit4.class)
public final class DvbParserTest {

  private static final byte[] initializationData = new byte[] {0x00, 0x01, 0x00, 0x01};

  /**
   * Minimal DVB subtitle packet for page 1 defining a 16x8 filled region at (10, 20) using default
   * 2-bit CLUT entry 1 (opaque white) and a 1x2 pixel object at region-local offset (2, 3) using
   * CLUT entry 2 (opaque black).
   */
  private static final byte[] sampleWithSingleFilledRegion =
      new byte[] {
        // Page composition segment: sync=0x0F, type=0x10, page_id=1, length=8
        0x0F,
        0x10,
        0x00,
        0x01,
        0x00,
        0x08,
        0x00, // timeout_secs = 0
        0x04, // version = 0, state = 1 (acquisition point)
        0x01, // region_id = 1
        0x00, // reserved
        0x00,
        0x0A, // region_horizontal_address = 10
        0x00,
        0x14, // region_vertical_address = 20
        // Region composition segment: sync=0x0F, type=0x11, page_id=1, length=16
        0x0F,
        0x11,
        0x00,
        0x01,
        0x00,
        0x10,
        0x01, // region_id = 1
        0x08, // version = 0, fill_flag = 1
        0x00,
        0x10, // width = 16
        0x00,
        0x08, // height = 8
        0x04, // compatibility = 0, depth = 1 (2-bit)
        0x00, // clut_id = 0
        0x00, // 8-bit pixel code = 0
        0x04, // 4-bit pixel code = 0, 2-bit pixel code = 1 (white)
        0x00,
        0x01, // object_id = 1
        0x00,
        0x02, // object_type = 0, object_provider = 0, object_horizontal_position = 2
        0x00,
        0x03, // reserved = 0, object_vertical_position = 3
        // Object data segment: sync=0x0F, type=0x13, page_id=1, length=10
        0x0F,
        0x13,
        0x00,
        0x01,
        0x00,
        0x0A,
        0x00,
        0x01, // object_id = 1
        0x00, // version = 0, coding_method = 0 (pixels), non_modifying_color_flag = 0
        0x00,
        0x03, // top_field_data_block_length = 3
        0x00,
        0x00, // bottom_field_data_block_length = 0 (reuses top field for next line)
        0x10, // DATA_TYPE_2BP_CODE_STRING
        (byte) 0x80, // 1 pixel of CLUT entry 2 (black) followed by end-of-string
        (byte) 0xF0 // DATA_TYPE_END_LINE
      };

  private static final byte[] sampleWithEmptyPageComposition =
      new byte[] {
        // Page composition segment: sync=0x0F, type=0x10, page_id=1, length=2 (0 regions)
        0x0F,
        0x10,
        0x00,
        0x01,
        0x00,
        0x02,
        0x00, // timeout_secs = 0
        0x14 // version = 1, state = 1 (acquisition point)
      };

  @Test
  public void getCueReplacementBehavior_returnsReplace() {
    DvbParser parser = new DvbParser(ImmutableList.of(initializationData));

    assertThat(parser.getCueReplacementBehavior()).isEqualTo(CUE_REPLACEMENT_BEHAVIOR_REPLACE);
  }

  @Test
  public void parse_withEmptyPageComposition_emitsEmptyCues() {
    DvbParser parser = new DvbParser(ImmutableList.of(initializationData));
    List<CuesWithTiming> firstOutput = new ArrayList<>();
    List<CuesWithTiming> clearedOutput = new ArrayList<>();

    parser.parse(sampleWithSingleFilledRegion, OutputOptions.allCues(), firstOutput::add);
    parser.parse(sampleWithEmptyPageComposition, OutputOptions.allCues(), clearedOutput::add);

    assertThat(Iterables.getOnlyElement(firstOutput).cues).hasSize(1);
    assertThat(Iterables.getOnlyElement(clearedOutput).cues).isEmpty();
  }

  @Test
  public void reset_clearsSubtitleServiceStateAndRendersSubsequentRegion() {
    DvbParser parser = new DvbParser(ImmutableList.of(initializationData));
    List<CuesWithTiming> firstOutput = new ArrayList<>();
    List<CuesWithTiming> afterResetEmptyOutput = new ArrayList<>();
    List<CuesWithTiming> secondOutput = new ArrayList<>();

    parser.parse(sampleWithSingleFilledRegion, OutputOptions.allCues(), firstOutput::add);
    parser.reset();
    parser.parse(new byte[0], OutputOptions.allCues(), afterResetEmptyOutput::add);
    parser.parse(sampleWithSingleFilledRegion, OutputOptions.allCues(), secondOutput::add);

    Cue firstCue = Iterables.getOnlyElement(Iterables.getOnlyElement(firstOutput).cues);
    assertThat(firstCue.bitmap).isNotNull();
    assertThat(firstCue.bitmap.getWidth()).isEqualTo(16);
    assertThat(firstCue.bitmap.getHeight()).isEqualTo(8);
    assertThat(firstCue.bitmap.getPixel(0, 0)).isEqualTo(0xFFFFFFFF);
    assertThat(firstCue.bitmap.getPixel(2, 3)).isEqualTo(0xFF000000);
    assertThat(firstCue.bitmap.getPixel(2, 4)).isEqualTo(0xFF000000);
    assertThat(firstCue.position).isEqualTo(10f / 719);
    assertThat(firstCue.line).isEqualTo(20f / 575);
    assertThat(firstCue.size).isEqualTo(16f / 719);
    assertThat(firstCue.bitmapHeight).isEqualTo(8f / 575);
    assertThat(Iterables.getOnlyElement(afterResetEmptyOutput).cues).isEmpty();
    Cue secondCue = Iterables.getOnlyElement(Iterables.getOnlyElement(secondOutput).cues);
    assertThat(secondCue.bitmap).isNotNull();
    assertThat(secondCue.bitmap.getWidth()).isEqualTo(16);
    assertThat(secondCue.bitmap.getHeight()).isEqualTo(8);
    assertThat(secondCue.bitmap.getPixel(0, 0)).isEqualTo(0xFFFFFFFF);
    assertThat(secondCue.bitmap.getPixel(2, 3)).isEqualTo(0xFF000000);
    assertThat(secondCue.bitmap.getPixel(2, 4)).isEqualTo(0xFF000000);
    assertThat(secondCue.position).isEqualTo(10f / 719);
    assertThat(secondCue.line).isEqualTo(20f / 575);
    assertThat(secondCue.size).isEqualTo(16f / 719);
    assertThat(secondCue.bitmapHeight).isEqualTo(8f / 575);
  }
}
