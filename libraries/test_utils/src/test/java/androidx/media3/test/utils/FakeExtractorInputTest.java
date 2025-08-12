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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FakeExtractorInputTest {

  @Test
  public void peekPastLimit_partialReads_throwsException() throws Exception {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(TestUtil.buildTestData(5))
            .setSimulatePartialReads(true)
            .setPeekLimit(2)
            .build();
    byte[] destination = new byte[3];
    // Increment the peek and read position away from zero.
    input.readFully(destination, 0, 1);

    // The partial peek of 1 byte succeeds since it's within the limit.
    assertThat(input.peek(destination, 0, 3)).isEqualTo(1);
    // The second attempt to peek to the same position will not be partial, and so will exceed peek
    // limit.
    assertThrows(IllegalStateException.class, () -> input.peek(destination, 1, 2));
  }

  @Test
  public void peekFullyPastLimit_throwsException() throws Exception {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(TestUtil.buildTestData(5)).setPeekLimit(2).build();
    byte[] destination = new byte[3];
    // Increment the peek and read position away from zero.
    input.readFully(destination, 0, 1);

    assertThrows(IllegalStateException.class, () -> input.peekFully(destination, 0, 3));
  }

  @Test
  public void advancePeekPositionPastLimit_throwsException() throws Exception {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(TestUtil.buildTestData(5)).setPeekLimit(2).build();
    byte[] destination = new byte[1];
    // Increment the peek and read position away from zero.
    input.readFully(destination, 0, 1);

    assertThrows(IllegalStateException.class, () -> input.advancePeekPosition(3));
  }
}
