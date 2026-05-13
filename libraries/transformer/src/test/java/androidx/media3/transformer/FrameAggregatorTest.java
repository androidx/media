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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FrameAggregator}. */
@RunWith(AndroidJUnit4.class)
public class FrameAggregatorTest {

  /**
   * The set of timestamps that have been released. This is a List in order to preserve duplicates,
   * but the order in which frames are released is not tested.
   */
  private List<Long> releasedFrameTimestamps;

  private ArrayList<List<HardwareBufferFrame>> outputFrames;
  private ArrayList<Integer> flushedSequences;

  @Before
  public void setUp() {
    releasedFrameTimestamps = new ArrayList<>();
    outputFrames = new ArrayList<>();
    flushedSequences = new ArrayList<>();
  }

  @Test
  public void queueFrame_withInvalidSequenceIndex_throwsIllegalArgumentException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    HardwareBufferFrame frame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    assertThrows(IllegalArgumentException.class, () -> frameAggregator.queueFrame(frame, 2));
  }

  @Test
  public void queueFrame_singleSequence_passesFramesThrough() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    HardwareBufferFrame frame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame frame2 =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);

    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame2, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(0)).containsExactly(frame1);
    assertThat(outputFrames.get(1)).containsExactly(frame2);
  }

  @Test
  public void queueFrame_waitsForAllSequencesBeforeAggregating() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    // Should not output a frame yet because the secondary sequence is missing.
    assertThat(outputFrames).isEmpty();

    frameAggregator.queueFrame(secondaryFrame, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame.presentationTimeUs);
  }

  @Test
  public void queueFrame_dropsSecondaryFramesWithEarlierPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 50, /* sequencePresentationTimeUs= */ 50);
    HardwareBufferFrame secondaryFrame2 =
        createFrame(/* presentationTimeUs= */ 80, /* sequencePresentationTimeUs= */ 80);
    HardwareBufferFrame secondaryFrame3 =
        createFrame(/* presentationTimeUs= */ 110, /* sequencePresentationTimeUs= */ 110);

    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    // Frames before the primary frame should be dropped and released.
    assertThat(releasedFrameTimestamps)
        .containsExactly(secondaryFrame1.presentationTimeUs, secondaryFrame2.presentationTimeUs);
    assertThat(outputFrames).isEmpty();

    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame3.presentationTimeUs);
  }

  @Test
  public void queueFrame_selectsSecondaryFrameWithEqualPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 199, /* sequencePresentationTimeUs= */ 199);
    HardwareBufferFrame secondaryFrame2 =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame3 =
        createFrame(/* presentationTimeUs= */ 201, /* sequencePresentationTimeUs= */ 201);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame2.presentationTimeUs);
    assertThat(releasedFrameTimestamps).containsExactly(secondaryFrame1.presentationTimeUs);
  }

  @Test
  public void queueFrame_selectsSecondaryFrameWithGreaterPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 199, /* sequencePresentationTimeUs= */ 199);
    HardwareBufferFrame secondaryFrame2 =
        createFrame(/* presentationTimeUs= */ 201, /* sequencePresentationTimeUs= */ 201);
    HardwareBufferFrame secondaryFrame3 =
        createFrame(/* presentationTimeUs= */ 202, /* sequencePresentationTimeUs= */ 202);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame2.presentationTimeUs);
    assertThat(releasedFrameTimestamps).containsExactly(secondaryFrame1.presentationTimeUs);
  }

  @Test
  public void queueFrame_upsampling_reusesFutureSecondaryFrame() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 101, /* sequencePresentationTimeUs= */ 101);
    HardwareBufferFrame primaryFrame3 =
        createFrame(/* presentationTimeUs= */ 102, /* sequencePresentationTimeUs= */ 102);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 102, /* sequencePresentationTimeUs= */ 102);

    frameAggregator.queueFrame(primaryFrame1, 0);
    frameAggregator.queueFrame(primaryFrame2, 0);
    frameAggregator.queueFrame(primaryFrame3, 0);

    // Aggregator must wait for a frame >= target timestamps
    assertThat(outputFrames).isEmpty();

    frameAggregator.queueFrame(secondaryFrame, 1);

    assertThat(outputFrames).hasSize(3);
    assertThat(outputFrames.get(0).get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame.presentationTimeUs);
    assertThat(outputFrames.get(1).get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame.presentationTimeUs);
    assertThat(outputFrames.get(2).get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame.presentationTimeUs);
  }

  @Test
  public void queueFrame_downsampling_dropsIntermediateFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 102, /* sequencePresentationTimeUs= */ 102);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame2 =
        createFrame(/* presentationTimeUs= */ 101, /* sequencePresentationTimeUs= */ 101);
    HardwareBufferFrame secondaryFrame3 =
        createFrame(/* presentationTimeUs= */ 102, /* sequencePresentationTimeUs= */ 102);

    frameAggregator.queueFrame(primaryFrame, 0);
    frameAggregator.queueFrame(secondaryFrame1, 1);
    frameAggregator.queueFrame(secondaryFrame2, 1);

    // Frames 100 and 101 are strictly in the past for target 102. They should be dropped.
    assertThat(outputFrames).isEmpty();
    assertThat(releasedFrameTimestamps)
        .containsExactly(secondaryFrame1.presentationTimeUs, secondaryFrame2.presentationTimeUs);

    frameAggregator.queueFrame(secondaryFrame3, 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame3.presentationTimeUs);
  }

  @Test
  public void queueFrame_mismatchedDuration_omitsEndedSecondary() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame1, 0);
    frameAggregator.queueFrame(secondaryFrame, 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).hasSize(2);

    // Secondary sequence ends earlier than primary.
    frameAggregator.queueEndOfStream(1);
    frameAggregator.queueFrame(primaryFrame2, 0);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(1)).containsExactly(primaryFrame2);
  }

  @Test
  public void queueFrame_throughputStress_1fpsSecondary() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    // Queue one second of primary frames (30 packets).
    for (int i = 0; i < 30; i++) {
      long presentationTimeUs = i * 33333L;
      frameAggregator.queueFrame(
          createFrame(presentationTimeUs, /* sequencePresentationTimeUs= */ presentationTimeUs), 0);
    }

    // Pipeline is stalled waiting for the secondary frame.
    assertThat(outputFrames).isEmpty();

    // Arrival of the secondary frame triggers a burst of all 30 pending packets.
    frameAggregator.queueFrame(
        createFrame(/* presentationTimeUs= */ 1000000L, /* sequencePresentationTimeUs= */ 1000000L),
        1);

    assertThat(outputFrames).hasSize(30);
    // Every primary frame in the first second paired with the 1s secondary frame.
    for (int i = 0; i < 30; i++) {
      assertThat(outputFrames.get(i).get(1).presentationTimeUs).isEqualTo(1000000L);
    }
  }

  @Test
  public void queueFrame_interleavedTimestamps_aggregatesCorrectly() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    // Simulate ~30fps for primary sequence
    long[] primaryTimestampsUs = {0, 33_333, 66_667, 100_000, 133_333, 166_667, 200_000};
    // Simulate ~20fps for secondary sequence
    long[] secondaryTimestampsUs = {0, 50_000, 100_000, 150_000, 200_000};
    long[] expectedSecondaryMatches = {0, 50_000, 100_000, 100_000, 150_000, 200_000, 200_000};
    int primaryIndex = 0;
    int secondaryIndex = 0;

    // Iterate through both lists and queue the frames in chronological order
    // simulating two sequences working normally.
    while (primaryIndex < primaryTimestampsUs.length
        || secondaryIndex < secondaryTimestampsUs.length) {
      long nextPrimaryUs =
          primaryIndex < primaryTimestampsUs.length
              ? primaryTimestampsUs[primaryIndex]
              : Long.MAX_VALUE;
      long nextSecondaryUs =
          secondaryIndex < secondaryTimestampsUs.length
              ? secondaryTimestampsUs[secondaryIndex]
              : Long.MAX_VALUE;
      if (nextPrimaryUs <= nextSecondaryUs) {
        frameAggregator.queueFrame(
            createFrame(
                /* presentationTimeUs= */ nextPrimaryUs,
                /* sequencePresentationTimeUs= */ nextPrimaryUs),
            /* sequenceIndex= */ 0);
        primaryIndex++;
      } else {
        frameAggregator.queueFrame(
            createFrame(
                /* presentationTimeUs= */ nextSecondaryUs,
                /* sequencePresentationTimeUs= */ nextSecondaryUs),
            /* sequenceIndex= */ 1);
        secondaryIndex++;
      }
    }

    assertThat(outputFrames).hasSize(primaryTimestampsUs.length);
    for (int i = 0; i < primaryTimestampsUs.length; i++) {
      List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(i);
      assertThat(aggregatedPacket.get(0).presentationTimeUs).isEqualTo(primaryTimestampsUs[i]);
      assertThat(aggregatedPacket.get(1).presentationTimeUs).isEqualTo(expectedSecondaryMatches[i]);
    }
  }

  @Test
  public void queueFrame_withStalls_waitsForMissingFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    long[] primaryUs = {0, 33_333, 66_667, 100_000};
    long[] secondaryUs = {0, 50_000, 100_000};

    // Queue first frames, expect immediate output
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ primaryUs[0], /* sequencePresentationTimeUs= */ primaryUs[0]),
        /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ secondaryUs[0],
            /* sequencePresentationTimeUs= */ secondaryUs[0]),
        /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);

    // Intentionally add a stall: Queue primary frames faster than secondary
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ primaryUs[1], /* sequencePresentationTimeUs= */ primaryUs[1]),
        /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ primaryUs[2], /* sequencePresentationTimeUs= */ primaryUs[2]),
        /* sequenceIndex= */ 0);

    // Assert pipeline is stalled waiting for secondary frame
    assertThat(outputFrames).hasSize(1);

    // Queue secondary frame, resolving the stall for one primary frame
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ secondaryUs[1],
            /* sequencePresentationTimeUs= */ secondaryUs[1]),
        /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2);

    // Queue next secondary frame, resolving the stall for the other primary frame
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ secondaryUs[2],
            /* sequencePresentationTimeUs= */ secondaryUs[2]),
        /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(3);

    // Secondary sequence is now ahead. It stalls waiting for the next primary.
    // Queue primary frame, resolving the stall
    frameAggregator.queueFrame(
        createFrame(
            /* presentationTimeUs= */ primaryUs[3], /* sequencePresentationTimeUs= */ primaryUs[3]),
        /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(4);
    assertThat(outputFrames.get(0).get(1).presentationTimeUs).isEqualTo(secondaryUs[0]);
    assertThat(outputFrames.get(1).get(1).presentationTimeUs).isEqualTo(secondaryUs[1]);
    assertThat(outputFrames.get(2).get(1).presentationTimeUs).isEqualTo(secondaryUs[2]);
    assertThat(outputFrames.get(3).get(1).presentationTimeUs).isEqualTo(secondaryUs[2]);
  }

  @Test
  public void queueFrame_multiItemPrimarySequence_matchesUsingSequenceTime() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    // Primary Sequence with two items:
    // Item 1: presentation time 0 -> 33_333, sequence time 0 -> 33_333
    HardwareBufferFrame primary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    HardwareBufferFrame primary2 =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 33_333);
    // Item 2: presentation time resets to 0 -> 33_333, sequence time continues 66_667 -> 100_000
    HardwareBufferFrame primary3 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 66_667);
    HardwareBufferFrame primary4 =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 100_000);
    // Secondary Sequence with one item
    HardwareBufferFrame secondary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    HardwareBufferFrame secondary2 =
        createFrame(/* presentationTimeUs= */ 50_000, /* sequencePresentationTimeUs= */ 50_000);
    HardwareBufferFrame secondary3 =
        createFrame(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);

    frameAggregator.queueFrame(primary1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(primary2, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(primary3, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(primary4, /* sequenceIndex= */ 0);
    // Matches primary 0 with secondary 0
    frameAggregator.queueFrame(secondary1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0).get(0).sequencePresentationTimeUs).isEqualTo(0);
    assertThat(outputFrames.get(0).get(1).sequencePresentationTimeUs).isEqualTo(0);

    // Matches primary 33_333 with secondary 50_000
    frameAggregator.queueFrame(secondary2, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(1).get(0).sequencePresentationTimeUs).isEqualTo(33_333);
    assertThat(outputFrames.get(1).get(1).sequencePresentationTimeUs).isEqualTo(50_000);

    // Matches primary 66_667 with secondary 100_000, and primary 100_000 with secondary 100_000
    frameAggregator.queueFrame(secondary3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(4);
    assertThat(outputFrames.get(2).get(0).sequencePresentationTimeUs).isEqualTo(66_667);
    assertThat(outputFrames.get(2).get(1).sequencePresentationTimeUs).isEqualTo(100_000);
    assertThat(outputFrames.get(3).get(0).sequencePresentationTimeUs).isEqualTo(100_000);
    assertThat(outputFrames.get(3).get(1).sequencePresentationTimeUs).isEqualTo(100_000);
  }

  @Test
  public void queueFrame_multiItemSecondarySequence_matchesUsingSequenceTime() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    // Primary Sequence with one item spanning the whole duration
    HardwareBufferFrame primary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    HardwareBufferFrame primary2 =
        createFrame(/* presentationTimeUs= */ 50_000, /* sequencePresentationTimeUs= */ 50_000);
    HardwareBufferFrame primary3 =
        createFrame(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    // Secondary Sequence with two items:
    // Item 1: presentation time 0 -> 33_333, sequence time 0 -> 33_333
    HardwareBufferFrame secondary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    HardwareBufferFrame secondary2 =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 33_333);
    // Item 2: presentation time resets to 0 -> 33_333, sequence time continues 66_667 -> 100_000
    HardwareBufferFrame secondary3 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 66_667);
    HardwareBufferFrame secondary4 =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 100_000);

    // Queue primary frames
    frameAggregator.queueFrame(primary1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(primary2, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(primary3, /* sequenceIndex= */ 0);
    // Matches primary 0 with secondary 0
    frameAggregator.queueFrame(secondary1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0).get(0).sequencePresentationTimeUs).isEqualTo(0);
    assertThat(outputFrames.get(0).get(1).sequencePresentationTimeUs).isEqualTo(0);

    frameAggregator.queueFrame(secondary2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondary3, /* sequenceIndex= */ 1);

    // secondary2 (33_333) < primary2 (50_000), so it gets dropped.
    // secondary3 (66_667) >= primary2 (50_000), so it correctly matches.
    // If we relied on the reset presentation time, secondary3's `0` would be dropped!
    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(1).get(0).sequencePresentationTimeUs).isEqualTo(50_000);
    assertThat(outputFrames.get(1).get(1).sequencePresentationTimeUs).isEqualTo(66_667);

    // Matches primary 100_000 with secondary 100_000
    frameAggregator.queueFrame(secondary4, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(3);
    assertThat(outputFrames.get(2).get(0).sequencePresentationTimeUs).isEqualTo(100_000);
    assertThat(outputFrames.get(2).get(1).sequencePresentationTimeUs).isEqualTo(100_000);
  }

  @Test
  public void close_releasesAllHeldFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 3,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 3);
    HardwareBufferFrame frame0 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame frame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame frame2 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame frame3 =
        createFrame(/* presentationTimeUs= */ 150, /* sequencePresentationTimeUs= */ 150);
    HardwareBufferFrame frame4 =
        createFrame(/* presentationTimeUs= */ 150, /* sequencePresentationTimeUs= */ 150);

    frameAggregator.queueFrame(frame0, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(frame2, /* sequenceIndex= */ 2);
    frameAggregator.queueFrame(frame3, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame4, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(3);
    assertThat(aggregatedPacket.get(0).presentationTimeUs).isEqualTo(frame0.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs).isEqualTo(frame1.presentationTimeUs);
    assertThat(aggregatedPacket.get(2).presentationTimeUs).isEqualTo(frame2.presentationTimeUs);

    frameAggregator.close();

    assertThat(releasedFrameTimestamps)
        .containsExactly(frame3.presentationTimeUs, frame4.presentationTimeUs);
    assertThat(outputFrames).hasSize(1);
  }

  @Test
  public void registerSequence_afterClose_throwsIllegalStateException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);

    frameAggregator.close();

    assertThrows(
        IllegalStateException.class,
        () ->
            frameAggregator.registerSequence(/* sequenceIndex= */ 0, /* shouldAggregate= */ true));
  }

  @Test
  public void queueFrame_afterClose_releasesFrameImmediately() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    frameAggregator.close();

    HardwareBufferFrame frame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(frame, /* sequenceIndex= */ 0);

    assertThat(outputFrames).isEmpty();
    assertThat(releasedFrameTimestamps).containsExactly(frame.presentationTimeUs);
  }

  @Test
  public void queueEndOfStream_afterClose_isIgnored() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    frameAggregator.close();

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);

    assertThat(outputFrames).isEmpty();
  }

  @Test
  public void flush_afterClose_isIgnored() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    frameAggregator.close();

    frameAggregator.flush(/* sequenceIndex= */ 0);

    assertThat(flushedSequences).isEmpty();
  }

  @Test
  public void flush_thenQueueFramesWithEarlierPresentationTimeUs_aggregatesCorrectly() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket1 = outputFrames.get(0);
    assertThat(aggregatedPacket1).hasSize(2);
    assertThat(aggregatedPacket1.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame1.presentationTimeUs);
    assertThat(aggregatedPacket1.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame1.presentationTimeUs);

    frameAggregator.flush(/* sequenceIndex= */ 0);
    frameAggregator.flush(/* sequenceIndex= */ 1);

    assertThat(flushedSequences).containsExactly(0, 1).inOrder();

    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 50, /* sequencePresentationTimeUs= */ 50);
    HardwareBufferFrame secondaryFrame2 =
        createFrame(/* presentationTimeUs= */ 50, /* sequencePresentationTimeUs= */ 50);

    frameAggregator.queueFrame(primaryFrame2, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2);
    List<HardwareBufferFrame> aggregatedPacket2 = outputFrames.get(1);
    assertThat(aggregatedPacket2).hasSize(2);
    assertThat(aggregatedPacket2.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame2.presentationTimeUs);
    assertThat(aggregatedPacket2.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame2.presentationTimeUs);
  }

  @Test
  public void queueEndOfStream_withInvalidSequenceIndex_throwsIllegalArgumentException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    assertThrows(IllegalArgumentException.class, () -> frameAggregator.queueEndOfStream(2));
  }

  @Test
  public void queueEndOfStream_secondaryStream_aggregatesWithoutSecondaryFrame() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    assertThat(outputFrames).isEmpty();

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame);
  }

  @Test
  public void queueEndOfStream_oneOfTwoSecondaryStreams_aggregatesWithAvailableFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 3,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 3);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).isEmpty();

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 2);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame1.presentationTimeUs);
  }

  @Test
  public void queueEndOfStream_thenFlush_resetsEndedState() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);

    frameAggregator.queueFrame(primaryFrame1, /* sequenceIndex= */ 0);
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);

    frameAggregator.flush(/* sequenceIndex= */ 1);

    frameAggregator.queueFrame(primaryFrame2, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(1);

    frameAggregator.queueFrame(secondaryFrame, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(1);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame2.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame.presentationTimeUs);

    assertThat(flushedSequences).containsExactly(1);
  }

  @Test
  public void queueEndOfStream_secondaryStreamAlreadyHasFrames_usesFramesBeforeSkipping() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 200, /* sequencePresentationTimeUs= */ 200);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame1, 0);
    frameAggregator.queueFrame(primaryFrame2, 0);

    frameAggregator.queueFrame(secondaryFrame1, 1);

    assertThat(outputFrames).hasSize(1);
    List<HardwareBufferFrame> aggregatedPacket = outputFrames.get(0);
    assertThat(aggregatedPacket).hasSize(2);
    assertThat(aggregatedPacket.get(0).presentationTimeUs)
        .isEqualTo(primaryFrame1.presentationTimeUs);
    assertThat(aggregatedPacket.get(1).presentationTimeUs)
        .isEqualTo(secondaryFrame1.presentationTimeUs);

    frameAggregator.queueEndOfStream(1);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(1)).containsExactly(primaryFrame2);
  }

  @Test
  public void queueEndOfStream_primarySequence_outputsEndOfStreamFrame() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueEndOfStream_primarySequenceAfterFrames_outputsFramesThenEndOfStream() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    HardwareBufferFrame frame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 0);
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(0)).containsExactly(frame1);
    assertThat(outputFrames.get(1)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueEndOfStream_primarySequence_ignoresSubsequentFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);
    HardwareBufferFrame frame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);
    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);

    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(1);
  }

  @Test
  public void queueEndOfStream_primarySequence_idempotent() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueEndOfStream_thenFlushPrimary_allowsNewFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);
    assertThat(outputFrames.get(0)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);

    frameAggregator.flush(/* sequenceIndex= */ 0);

    HardwareBufferFrame frame1 =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);
    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(1)).containsExactly(frame1);

    assertThat(flushedSequences).containsExactly(0);
  }

  @Test
  public void queueEndOfStream_primaryEndsWhileWaitingForSecondary_outputsFramesThenEos() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primaryFrame = createFrame(100, /* sequencePresentationTimeUs= */ 100);
    HardwareBufferFrame secondaryFrame = createFrame(100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame, 0);
    frameAggregator.queueEndOfStream(0);

    assertThat(outputFrames).isEmpty();

    frameAggregator.queueFrame(secondaryFrame, 1);

    assertThat(outputFrames).hasSize(2);
    List<HardwareBufferFrame> dataPacket = outputFrames.get(0);
    assertThat(dataPacket).hasSize(2);
    assertThat(dataPacket.get(0).presentationTimeUs).isEqualTo(primaryFrame.presentationTimeUs);
    assertThat(dataPacket.get(1).presentationTimeUs).isEqualTo(secondaryFrame.presentationTimeUs);
    List<HardwareBufferFrame> eosPacket = outputFrames.get(1);
    assertThat(eosPacket).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueFrame_beforeRegisterSequence_throwsIllegalStateException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    assertThrows(
        IllegalStateException.class,
        () -> frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0));
  }

  @Test
  public void queueEndOfStream_beforeRegisterSequence_throwsIllegalStateException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);

    assertThrows(
        IllegalStateException.class,
        () -> frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0));
  }

  @Test
  public void registerSequence_withInvalidSequenceIndex_throwsIllegalArgumentException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            frameAggregator.registerSequence(/* sequenceIndex= */ 2, /* shouldAggregate= */ false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            frameAggregator.registerSequence(
                /* sequenceIndex= */ -1, /* shouldAggregate= */ false));
  }

  @Test
  public void registerSequence_withShouldAggregateFalse_doesNotWaitForSecondarySequence() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 100, /* sequencePresentationTimeUs= */ 100);

    frameAggregator.registerSequence(/* sequenceIndex= */ 0, /* shouldAggregate= */ true);
    frameAggregator.registerSequence(/* sequenceIndex= */ 1, /* shouldAggregate= */ false);
    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    // The aggregator should not wait for sequence 1 and should output the primary frame
    // immediately.
    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame);
  }

  @Test
  public void handlePrimaryEndOfStream_withPendingSecondaryFrames_doesNotReleaseSecondaryFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);
    HardwareBufferFrame primary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);
    HardwareBufferFrame secondary1 =
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0);

    frameAggregator.queueFrame(primary1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondary1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);

    // Simulate a seek: The secondary sequence flushes first and queues new frames BEFORE the
    // primary EOS arrives.
    frameAggregator.flush(/* sequenceIndex= */ 1);
    HardwareBufferFrame postSeekSecondary =
        createFrame(/* presentationTimeUs= */ 50_000, /* sequencePresentationTimeUs= */ 50_000);
    frameAggregator.queueFrame(postSeekSecondary, /* sequenceIndex= */ 1);
    // A delayed EOS arrives from the slow primary sequence.
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);

    // The primary sequence flushes and queues its new frames.
    frameAggregator.flush(/* sequenceIndex= */ 0);
    HardwareBufferFrame postSeekPrimary =
        createFrame(/* presentationTimeUs= */ 50_000, /* sequencePresentationTimeUs= */ 50_000);
    frameAggregator.queueFrame(postSeekPrimary, /* sequenceIndex= */ 0);

    // The post-seek frames should successfully aggregate.
    // outputFrames now contains: [initial data packet], [EOS packet], [post-seek data packet]
    assertThat(outputFrames).hasSize(3);
    assertThat(outputFrames.get(0).get(0).sequencePresentationTimeUs).isEqualTo(0);
    assertThat(outputFrames.get(0).get(1).sequencePresentationTimeUs).isEqualTo(0);
    assertThat(outputFrames.get(1)).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
    assertThat(outputFrames.get(2).get(0).sequencePresentationTimeUs).isEqualTo(50_000);
    assertThat(outputFrames.get(2).get(1).sequencePresentationTimeUs).isEqualTo(50_000);
  }

  /** Creates a {@link GlTextureFrame} for testing. */
  private HardwareBufferFrame createFrame(
      long presentationTimeUs, long sequencePresentationTimeUs) {
    return new HardwareBufferFrame.Builder(
            /* hardwareBuffer= */ null,
            directExecutor(),
            (releaseFence) -> releasedFrameTimestamps.add(presentationTimeUs))
        .setPresentationTimeUs(presentationTimeUs)
        .setSequencePresentationTimeUs(sequencePresentationTimeUs)
        .setInternalFrame(this)
        .build();
  }

  private static void registerAllSequences(FrameAggregator frameAggregator, int numSequences) {
    for (int i = 0; i < numSequences; i++) {
      frameAggregator.registerSequence(i, /* shouldAggregate= */ true);
    }
  }
}
