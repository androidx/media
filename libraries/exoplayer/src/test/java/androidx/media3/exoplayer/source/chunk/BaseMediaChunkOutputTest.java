/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source.chunk;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link BaseMediaChunkOutput}. */
@RunWith(AndroidJUnit4.class)
public final class BaseMediaChunkOutputTest {

  private DefaultAllocator allocator;

  @Before
  public void setUp() {
    allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
  }

  @Test
  public void constructor_mismatchedArrayLengths_throwsIllegalArgumentException() {
    SampleQueue sampleQueue = SampleQueue.createWithoutDrm(allocator);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BaseMediaChunkOutput(
                new int[] {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT},
                new SampleQueue[] {sampleQueue}));
  }

  @Test
  public void track_matchingTypes_returnsCorrespondingSampleQueues() {
    SampleQueue videoQueue = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(
            new int[] {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT},
            new SampleQueue[] {videoQueue, textQueue});

    TrackOutput videoOutput = chunkOutput.track(/* id= */ 1, C.TRACK_TYPE_VIDEO);
    TrackOutput textOutput = chunkOutput.track(/* id= */ 2, C.TRACK_TYPE_TEXT);

    assertThat(videoOutput).isSameInstanceAs(videoQueue);
    assertThat(textOutput).isSameInstanceAs(textQueue);
  }

  @Test
  public void track_unmatchedType_returnsDiscardingTrackOutput() {
    SampleQueue videoQueue = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(new int[] {C.TRACK_TYPE_VIDEO}, new SampleQueue[] {videoQueue});

    TrackOutput audioOutput = chunkOutput.track(/* id= */ 1, C.TRACK_TYPE_AUDIO);

    assertThat(audioOutput).isInstanceOf(DiscardingTrackOutput.class);
  }

  @Test
  public void track_multipleQueuesOfSameType_bindsDistinctIdsInOrderAndReturnsSameOnRepeat() {
    SampleQueue videoQueue = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue1 = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue2 = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(
            new int[] {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT},
            new SampleQueue[] {videoQueue, textQueue1, textQueue2});

    TrackOutput firstIdFirstCall = chunkOutput.track(/* id= */ 10, C.TRACK_TYPE_TEXT);
    TrackOutput secondIdFirstCall = chunkOutput.track(/* id= */ 20, C.TRACK_TYPE_TEXT);
    TrackOutput firstIdSecondCall = chunkOutput.track(/* id= */ 10, C.TRACK_TYPE_TEXT);
    TrackOutput secondIdSecondCall = chunkOutput.track(/* id= */ 20, C.TRACK_TYPE_TEXT);

    assertThat(firstIdFirstCall).isSameInstanceAs(textQueue1);
    assertThat(secondIdFirstCall).isSameInstanceAs(textQueue2);
    assertThat(firstIdSecondCall).isSameInstanceAs(textQueue1);
    assertThat(secondIdSecondCall).isSameInstanceAs(textQueue2);
  }

  @Test
  public void track_sameIdAcrossDifferentTypes_bindsIndependentlyPerType() {
    SampleQueue textQueue1 = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue2 = SampleQueue.createWithoutDrm(allocator);
    SampleQueue imageQueue = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(
            new int[] {C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_IMAGE},
            new SampleQueue[] {textQueue1, textQueue2, imageQueue});

    TrackOutput firstTextZeroOutput = chunkOutput.track(/* id= */ 0, C.TRACK_TYPE_TEXT);
    TrackOutput imageZeroOutput = chunkOutput.track(/* id= */ 0, C.TRACK_TYPE_IMAGE);
    TrackOutput repeatedTextZeroOutput = chunkOutput.track(/* id= */ 0, C.TRACK_TYPE_TEXT);
    TrackOutput secondTextOutput = chunkOutput.track(/* id= */ 1, C.TRACK_TYPE_TEXT);

    assertThat(firstTextZeroOutput).isSameInstanceAs(textQueue1);
    assertThat(imageZeroOutput).isSameInstanceAs(imageQueue);
    assertThat(repeatedTextZeroOutput).isSameInstanceAs(textQueue1);
    assertThat(secondTextOutput).isSameInstanceAs(textQueue2);
  }

  @Test
  public void track_moreIdsThanQueuesOfSameType_fallsBackToFirstMatchingQueue() {
    SampleQueue videoQueue = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue1 = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue2 = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(
            new int[] {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_TEXT},
            new SampleQueue[] {videoQueue, textQueue1, textQueue2});

    TrackOutput firstTextOutput = chunkOutput.track(/* id= */ 10, C.TRACK_TYPE_TEXT);
    TrackOutput secondTextOutput = chunkOutput.track(/* id= */ 20, C.TRACK_TYPE_TEXT);
    TrackOutput overflowTextOutput = chunkOutput.track(/* id= */ 30, C.TRACK_TYPE_TEXT);
    TrackOutput repeatedSecondTextOutput = chunkOutput.track(/* id= */ 20, C.TRACK_TYPE_TEXT);

    assertThat(firstTextOutput).isSameInstanceAs(textQueue1);
    assertThat(secondTextOutput).isSameInstanceAs(textQueue2);
    assertThat(overflowTextOutput).isSameInstanceAs(textQueue1);
    assertThat(repeatedSecondTextOutput).isSameInstanceAs(textQueue2);
  }

  @Test
  public void getWriteIndices_returnsWriteIndicesInQueueOrder() throws Exception {
    SampleQueue videoQueue = SampleQueue.createWithoutDrm(allocator);
    SampleQueue textQueue = SampleQueue.createWithoutDrm(allocator);
    BaseMediaChunkOutput chunkOutput =
        new BaseMediaChunkOutput(
            new int[] {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT},
            new SampleQueue[] {videoQueue, textQueue});
    textQueue.format(new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).build());
    textQueue.sampleData(new ParsableByteArray(new byte[4]), /* length= */ 4);
    textQueue.sampleMetadata(
        /* timeUs= */ 0,
        /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
        /* size= */ 4,
        /* offset= */ 0,
        /* cryptoData= */ null);

    int[] writeIndices = chunkOutput.getWriteIndices();

    assertThat(writeIndices).asList().containsExactly(0, 1).inOrder();
  }
}
