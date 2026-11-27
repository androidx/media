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
package androidx.media3.extractor.mp4;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link FixedSampleSizeRechunker}. */
@RunWith(AndroidJUnit4.class)
public class FixedSampleSizeRechunkerTest {

  @Test
  public void rechunk_withFixedSampleSizeExceedingMaxSampleSize_doesNotThrowDivisionByZero() {
    int fixedSampleSize = 10_000; // > MAX_SAMPLE_SIZE (8192)
    long[] chunkOffsets = new long[] {100};
    int[] chunkSampleCounts = new int[] {5};
    long timestampDeltaInTimeUnits = 1000;

    FixedSampleSizeRechunker.Results results =
        FixedSampleSizeRechunker.rechunk(
            fixedSampleSize, chunkOffsets, chunkSampleCounts, timestampDeltaInTimeUnits);

    assertThat(results.offsets).isEqualTo(new long[] {100, 10100, 20100, 30100, 40100});
    assertThat(results.sizes).isEqualTo(new int[] {10000, 10000, 10000, 10000, 10000});
    assertThat(results.maximumSize).isEqualTo(10000);
    assertThat(results.timestamps).isEqualTo(new long[] {0, 1000, 2000, 3000, 4000});
    assertThat(results.flags).isEqualTo(new int[] {1, 1, 1, 1, 1});
  }
}
