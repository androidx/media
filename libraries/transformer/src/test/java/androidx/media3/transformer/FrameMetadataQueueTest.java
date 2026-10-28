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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FrameMetadataQueue}. */
@RunWith(AndroidJUnit4.class)
public final class FrameMetadataQueueTest {

  private static final ImmutableMap<String, Object> METADATA_1 = ImmutableMap.of("key", 1);
  private static final ImmutableMap<String, Object> METADATA_2 = ImmutableMap.of("key", 2);
  private static final ImmutableMap<String, Object> METADATA_3 = ImmutableMap.of("key", 3);

  @Test
  public void getMetadata_addedInDecodeOrder_returnsMetadataInPresentationOrder() {
    FrameMetadataQueue frameMetadataQueue = new FrameMetadataQueue();
    frameMetadataQueue.add(/* timeUs= */ 0, METADATA_1);
    frameMetadataQueue.add(/* timeUs= */ 80, METADATA_3);
    frameMetadataQueue.add(/* timeUs= */ 40, METADATA_2);

    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEqualTo(METADATA_1);
    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 40)).isEqualTo(METADATA_2);
    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 80)).isEqualTo(METADATA_3);
  }

  @Test
  public void getMetadata_frameWithoutMetadata_returnsEmptyMap() {
    FrameMetadataQueue frameMetadataQueue = new FrameMetadataQueue();
    frameMetadataQueue.add(/* timeUs= */ 40, METADATA_1);

    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEmpty();
  }

  @Test
  public void getMetadata_sameFrameAgain_returnsSameMetadata() {
    FrameMetadataQueue frameMetadataQueue = new FrameMetadataQueue();
    frameMetadataQueue.add(/* timeUs= */ 0, METADATA_1);

    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEqualTo(METADATA_1);
    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEqualTo(METADATA_1);
  }

  @Test
  public void getMetadata_laterFrame_removesMetadataOfEarlierFrames() {
    FrameMetadataQueue frameMetadataQueue = new FrameMetadataQueue();
    frameMetadataQueue.add(/* timeUs= */ 0, METADATA_1);
    frameMetadataQueue.add(/* timeUs= */ 40, METADATA_2);

    ImmutableMap<String, Object> laterFrameMetadata =
        frameMetadataQueue.getMetadata(/* timeUs= */ 40);

    assertThat(laterFrameMetadata).isEqualTo(METADATA_2);
    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEmpty();
  }

  @Test
  public void clear_withQueuedMetadata_removesAllMetadata() {
    FrameMetadataQueue frameMetadataQueue = new FrameMetadataQueue();
    frameMetadataQueue.add(/* timeUs= */ 0, METADATA_1);
    frameMetadataQueue.add(/* timeUs= */ 40, METADATA_2);

    frameMetadataQueue.clear();

    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 0)).isEmpty();
    assertThat(frameMetadataQueue.getMetadata(/* timeUs= */ 40)).isEmpty();
  }
}
