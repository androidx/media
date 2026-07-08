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

import android.util.Rational;
import androidx.annotation.Nullable;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  public void constructor_withZeroSequences_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrameAggregator(
                /* numSequences= */ 0,
                /* frameRate= */ null,
                /* downstreamConsumer= */ outputFrames::add,
                /* onFlush= */ flushedSequences::add));
  }

  @Test
  public void constructor_withNegativeNumSequences_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FrameAggregator(
                /* numSequences= */ -1,
                /* frameRate= */ null,
                /* downstreamConsumer= */ outputFrames::add,
                /* onFlush= */ flushedSequences::add));
  }

  @Test
  public void queueFrame_withInvalidSequenceIndex_throwsIllegalArgumentException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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
            /* frameRate= */ null,
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

  @Test
  public void queueFrame_withFrameRate_upsamplesMatchesAndDownsamplesInputStreams() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 3,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 3);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 0: 0us]    [Tick 1: 33_333us]    [Tick 2: 66_667us]
     * ----------------------------------------------------------------------------------
     * Seq 0 (15 FPS, up):    |--- 0us ---|    |--- 66_667us ---|    | (hold 66_667us)|
     * Seq 1 (30 FPS, exact): |--- 0us ---|    |--- 33_333us ---|    |--- 66_667us ---|
     * Seq 2 (60 FPS, drop):  |0| (16_667)|    |--- 33_333us ---|    |(50_000)| 66_667|
     */

    List<HardwareBufferFrame> seq0Frames =
        createFrameList(/* numFrames= */ 2, /* frameRate= */ 15, /* sequenceIndex= */ 0);
    List<HardwareBufferFrame> seq1Frames =
        createFrameList(/* numFrames= */ 3, /* frameRate= */ 30, /* sequenceIndex= */ 1);
    List<HardwareBufferFrame> seq2Frames =
        createFrameList(/* numFrames= */ 5, /* frameRate= */ 60, /* sequenceIndex= */ 2);

    // Queue initial frame batch for Virtual Tick 0 (0us)
    frameAggregator.queueFrame(seq0Frames.get(0), /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(seq1Frames.get(0), /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(seq2Frames.get(0), /* sequenceIndex= */ 2);

    assertOutputPacket(
        Iterables.getLast(outputFrames),
        /* expectedSize= */ 3,
        /* expectedTimeUs= */ 0,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 0,
            /* sequencePresentationTimeUs= */ 0),
        new SourceFrame(
            /* sequenceIndex= */ 1,
            /* presentationTimeUs= */ 0,
            /* sequencePresentationTimeUs= */ 0),
        new SourceFrame(
            /* sequenceIndex= */ 2,
            /* presentationTimeUs= */ 0,
            /* sequencePresentationTimeUs= */ 0));

    // Queue next frame batch for Virtual Tick 1 (33_333us)
    // Seq 0 (15 FPS): Queues Frame 2 (66_667us >= 33_333us), which is matched for Virtual Tick 1
    // (33_333us).
    // Seq 2 (60 FPS): Frame 2 (16_667us) is dropped (downsampled).
    frameAggregator.queueFrame(seq0Frames.get(1), /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(seq1Frames.get(1), /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(seq2Frames.get(1), /* sequenceIndex= */ 2);
    frameAggregator.queueFrame(seq2Frames.get(2), /* sequenceIndex= */ 2);

    assertThat(outputFrames).hasSize(2); // Virtual Ticks 0 and 1 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames),
        /* expectedSize= */ 3,
        /* expectedTimeUs= */ 33_333,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 66_667,
            /* sequencePresentationTimeUs= */ 66_667),
        new SourceFrame(
            /* sequenceIndex= */ 1,
            /* presentationTimeUs= */ 33_333,
            /* sequencePresentationTimeUs= */ 33_333),
        new SourceFrame(
            /* sequenceIndex= */ 2,
            /* presentationTimeUs= */ 33_333,
            /* sequencePresentationTimeUs= */ 33_333));
    assertThat(releasedFrameTimestamps).contains(seq2Frames.get(1).presentationTimeUs);

    // Queue final frame batch for Virtual Tick 2 (66_667us)
    // Seq 0 (15 FPS): Holds (reuses) Frame 2 (66_667us == 66_667us).
    // Seq 2 (60 FPS): Frame 4 (50_000us) is dropped.
    frameAggregator.queueFrame(seq1Frames.get(2), /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(seq2Frames.get(3), /* sequenceIndex= */ 2);
    frameAggregator.queueFrame(seq2Frames.get(4), /* sequenceIndex= */ 2);

    assertThat(outputFrames).hasSize(3); // Virtual Ticks 0, 1, and 2 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames),
        /* expectedSize= */ 3,
        /* expectedTimeUs= */ 66_667,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 66_667,
            /* sequencePresentationTimeUs= */ 66_667),
        new SourceFrame(
            /* sequenceIndex= */ 1,
            /* presentationTimeUs= */ 66_667,
            /* sequencePresentationTimeUs= */ 66_667),
        new SourceFrame(
            /* sequenceIndex= */ 2,
            /* presentationTimeUs= */ 66_667,
            /* sequencePresentationTimeUs= */ 66_667));
    assertThat(releasedFrameTimestamps).contains(seq2Frames.get(3).presentationTimeUs);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 1);
    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 2);

    assertThat(outputFrames).hasSize(4);
    assertThat(Iterables.getLast(outputFrames))
        .containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueFrame_withFrameRateAndSecondarySequenceEndsEarly_outputsOnlyPrimaryFrame() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 0: 0us]         [Tick 1: 33_333us]
     * ---------------------------------------------------------------
     * Seq 0 (Primary):       |--- 0us ---|         |--- 33_333us ---|
     * Seq 1 (Secondary):     |--- 0us ---| (EOS)
     */

    List<HardwareBufferFrame> primaryFrames =
        createFrameList(/* numFrames= */ 2, /* frameRate= */ 30, /* sequenceIndex= */ 0);
    List<HardwareBufferFrame> secondaryFrames =
        createFrameList(/* numFrames= */ 1, /* frameRate= */ 30, /* sequenceIndex= */ 1);

    frameAggregator.queueFrame(primaryFrames.get(0), /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrames.get(0), /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1); // Virtual Tick 0 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames), /* expectedSize= */ 2, /* expectedTimeUs= */ 0);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 1);
    frameAggregator.queueFrame(primaryFrames.get(1), /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2); // Virtual Ticks 0 and 1 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames),
        /* expectedSize= */ 1,
        /* expectedTimeUs= */ 33_333,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 33_333,
            /* sequencePresentationTimeUs= */ 33_333));
  }

  @Test
  public void queueFrame_withFrameRateAndPrimarySequenceEndsEarly_outputsOnlySecondaryFrame() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 0: 0us]         [Tick 1: 33_333us]
     * ---------------------------------------------------------------
     * Seq 0 (Primary):       |--- 0us ---| (EOS)
     * Seq 1 (Secondary):     |--- 0us ---|         |--- 33_333us ---|
     */

    List<HardwareBufferFrame> primaryFrames =
        createFrameList(/* numFrames= */ 1, /* frameRate= */ 30, /* sequenceIndex= */ 0);
    List<HardwareBufferFrame> secondaryFrames =
        createFrameList(/* numFrames= */ 2, /* frameRate= */ 30, /* sequenceIndex= */ 1);

    frameAggregator.queueFrame(primaryFrames.get(0), /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrames.get(0), /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1); // Virtual Tick 0 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames), /* expectedSize= */ 2, /* expectedTimeUs= */ 0);

    frameAggregator.queueEndOfStream(/* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrames.get(1), /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2); // Virtual Ticks 0 and 1 emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames),
        /* expectedSize= */ 1,
        /* expectedTimeUs= */ 33_333,
        new SourceFrame(
            /* sequenceIndex= */ 1,
            /* presentationTimeUs= */ 33_333,
            /* sequencePresentationTimeUs= */ 33_333));
  }

  @Test
  public void queueFrame_withFrameRateAndStartsAtVirtualTick_exactMatch() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 1: 33_333us]
     * -----------------------------------------
     * Seq 0 (Primary):       |--- 33_333us ---|
     * Seq 1 (Secondary):     |--- 33_333us ---|
     */

    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 33_333);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 33_333, /* sequencePresentationTimeUs= */ 33_333);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertOutputPacket(
        Iterables.getLast(outputFrames), /* expectedSize= */ 2, /* expectedTimeUs= */ 33_333);
  }

  @Test
  public void queueFrame_withFrameRateAndStartsAtVirtualTick_dropsDueToUpstreamRounding() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    // TODO: b/525309275 - Consider adding a tolerance to getVirtualFrameIndexCeil.
    // Right now, perfectly aligned frames might be dropped due to upstream integer rounding.
    // 66_667 is the nearest-integer rounded timestamp for the 2nd tick at 30fps (66666.666... us).
    // When we calculate the exact index: ceil((66_667 * 30) / 1_000_000) = ceil(2.00001) = 3.
    //
    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 2: 66_667us]    [Tick 3: 100_000us]
     * ----------------------------------------------------------------
     * Seq 0 (Primary):                             | (Drop 66_667us) |
     * Seq 1 (Secondary):                           | (Wait)          |
     */
    //
    // The virtual clock starts at tick 3 (100_000us) because the ceiling logic overshoots to 3.
    // Because the incoming 66_667us frames are strictly older than the target time of 100_000us,
    // they are dropped, and the aggregator outputs nothing until the next frames arrive.
    HardwareBufferFrame primaryFrame =
        createFrame(/* presentationTimeUs= */ 66_667, /* sequencePresentationTimeUs= */ 66_667);
    HardwareBufferFrame secondaryFrame =
        createFrame(/* presentationTimeUs= */ 66_667, /* sequencePresentationTimeUs= */ 66_667);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame, /* sequenceIndex= */ 1);

    assertThat(outputFrames).isEmpty(); // Dropped
    // Only the primary frame is dropped initially because the matcher aborts checking further
    // sequences as soon as the primary queue is empty.
    assertThat(releasedFrameTimestamps).containsExactly(66_667L);
  }

  @Test
  public void queueFrame_withFrameRateAndRoundsUpToNearestVirtualTick_dropsPrecedingFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks (30 FPS):   [  Tick 3: 100_000us  ]      [ Tick 4: 133_333us ]
     * ---------------------------------------------------------------------------------
     * Seq 0 (Primary):          | 82_000us | 100_000us|      |---- 133_333us ----|
     * Seq 1 (Secondary):        | (Wait)   | 133_333us|      |---- 133_333us ----|
     */

    HardwareBufferFrame primaryFrame1 =
        createFrame(/* presentationTimeUs= */ 82_000, /* sequencePresentationTimeUs= */ 82_000);
    HardwareBufferFrame primaryFrame2 =
        createFrame(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000);
    HardwareBufferFrame primaryFrame3 =
        createFrame(/* presentationTimeUs= */ 133_333, /* sequencePresentationTimeUs= */ 133_333);
    HardwareBufferFrame secondaryFrame1 =
        createFrame(/* presentationTimeUs= */ 133_333, /* sequencePresentationTimeUs= */ 133_333);

    // Queue secondary frame [133_333]. Output is empty.
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);

    assertThat(outputFrames).isEmpty();

    // Queue primary frame [82_000].
    // timeUs * frameRate / 1_000_000.0 = 82_000 * 30 / 1M = 2.46.
    // This ceils to index 3 (target time 100_000us) to ensure the virtual clock
    // never produces frames with timestamps earlier than the current available frames.
    // Because 82_000 < 100_000, it is dropped.
    frameAggregator.queueFrame(primaryFrame1, /* sequenceIndex= */ 0);

    assertThat(outputFrames).isEmpty();
    assertThat(releasedFrameTimestamps).containsExactly(82_000L);

    // Queue primary frame [100_000]. Output matches S1[100_000] + S2[133_333] at Target 100_000.
    frameAggregator.queueFrame(primaryFrame2, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(1);
    assertOutputPacket(outputFrames.get(0), /* expectedSize= */ 2, /* expectedTimeUs= */ 100_000);

    // Queue primary frame [133_333]. Output matches S1[133_333] + S2[133_333] at Target 133_333.
    // The previous primary frame [100_000] is released.
    frameAggregator.queueFrame(primaryFrame3, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);
    assertOutputPacket(outputFrames.get(1), /* expectedSize= */ 2, /* expectedTimeUs= */ 133_333);
    assertThat(releasedFrameTimestamps).containsExactly(82_000L);
  }

  @Test
  public void flush_withFrameRate_resetsToSeekTimestampVirtualTick() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 2,
            /* frameRate= */ new Rational(30, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 2);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 30 FPS):
     *
     * Virtual Ticks:         [Tick 0: 0us]    <SEEK>    [Tick 3: 100_000us]
     * ---------------------------------------------------------------------
     * Seq 0 (Primary):       |--- 0us ---|    <SEEK>    |--- 100_000us ---|
     * Seq 1 (Secondary):     |--- 0us ---|    <SEEK>    |--- 100_000us ---|
     */

    frameAggregator.queueFrame(
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0),
        /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(
        createFrame(/* presentationTimeUs= */ 0, /* sequencePresentationTimeUs= */ 0),
        /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1); // Pre-seek Virtual Tick emitted

    // Seek to 100_000us
    frameAggregator.flush(/* sequenceIndex= */ 0);
    frameAggregator.flush(/* sequenceIndex= */ 1);

    frameAggregator.queueFrame(
        createFrame(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000),
        /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(
        createFrame(/* presentationTimeUs= */ 100_000, /* sequencePresentationTimeUs= */ 100_000),
        /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(2); // Post-seek Virtual Tick emitted
    assertOutputPacket(
        Iterables.getLast(outputFrames), /* expectedSize= */ 2, /* expectedTimeUs= */ 100_000);
  }

  @Test
  public void queueFrame_withFrameRate_multipleItems_retimesPresentationAndSequenceTimestamps() {
    FrameAggregator frameAggregator =
        new FrameAggregator(
            /* numSequences= */ 1,
            /* frameRate= */ new Rational(10, 1),
            /* downstreamConsumer= */ outputFrames::add,
            /* onFlush= */ flushedSequences::add);
    registerAllSequences(frameAggregator, /* numSequences= */ 1);

    /*
     * Pacing / Retiming Timeline (Target Virtual Clock: 10 FPS):
     *
     * Virtual Ticks (10 FPS):  [ Tick 0: 0us ]                      [ Tick 1: 100_000us ]
     * -----------------------------------------------------------------------------------
     * Item 1 (30 FPS):         |--- 0us ---| (33) | (66) |
     * Item 2 (30 FPS, offset):                         | (90) |     |---- 123_333us ----|
     */

    List<HardwareBufferFrame> item1Frames =
        createFrameList(/* numFrames= */ 3, /* frameRate= */ 30, /* sequenceIndex= */ 0);
    List<HardwareBufferFrame> item2Frames =
        createFrameList(
            /* numFrames= */ 2,
            /* frameRate= */ 30,
            /* sequenceIndex= */ 0,
            /* sequencePresentationTimeOffsetUs= */ 90_000);

    item1Frames.forEach(frame -> frameAggregator.queueFrame(frame, /* sequenceIndex= */ 0));
    item2Frames.forEach(frame -> frameAggregator.queueFrame(frame, /* sequenceIndex= */ 0));

    assertThat(outputFrames).hasSize(2); // Virtual Ticks 0 and 1 emitted

    // Virtual Tick 0 (0us): Matches Item 1 Frame 0 (SeqPTS = 0us).
    assertOutputPacket(
        outputFrames.get(0),
        /* expectedSize= */ 1,
        /* expectedTimeUs= */ 0,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 0,
            /* sequencePresentationTimeUs= */ 0));

    // Virtual Tick 1 (100_000us):
    // Intermediate 30 FPS frames (Item 1 Frames 1 & 2 at 33_333us & 66_667us;
    // Item 2 Frame 0 at 90_000us) are dropped because SeqPTS < 100_000us.
    // Item 2 Frame 1 (SeqPTS = 90_000 + 33_333 = 123_333us >= 100_000us) matches.
    // shiftDeltaUs = 100_000 - 123_333 = -23_333us.
    // Retimed PTS = 33_333 - 23_333 = 10_000us.
    assertOutputPacket(
        outputFrames.get(1),
        /* expectedSize= */ 1,
        /* expectedPresentationTimeUs= */ 10_000,
        /* expectedSequencePresentationTimeUs= */ 100_000,
        new SourceFrame(
            /* sequenceIndex= */ 0,
            /* presentationTimeUs= */ 33_333,
            /* sequencePresentationTimeUs= */ 123_333));
  }

  private static void assertOutputPacket(
      List<HardwareBufferFrame> outputPacket,
      int expectedSize,
      long expectedTimeUs,
      SourceFrame... expectedSourceFrames) {
    assertOutputPacket(
        outputPacket,
        expectedSize,
        /* expectedPresentationTimeUs= */ expectedTimeUs,
        /* expectedSequencePresentationTimeUs= */ expectedTimeUs,
        expectedSourceFrames);
  }

  private static void assertOutputPacket(
      List<HardwareBufferFrame> outputPacket,
      int expectedSize,
      long expectedPresentationTimeUs,
      long expectedSequencePresentationTimeUs,
      SourceFrame... expectedSourceFrames) {
    assertThat(outputPacket).hasSize(expectedSize);
    for (HardwareBufferFrame frame : outputPacket) {
      assertThat(frame.presentationTimeUs).isEqualTo(expectedPresentationTimeUs);
      assertThat(frame.sequencePresentationTimeUs).isEqualTo(expectedSequencePresentationTimeUs);
    }
    if (expectedSourceFrames.length > 0) {
      List<SourceFrame> actualSourceFrames = new ArrayList<>();
      for (HardwareBufferFrame frame : outputPacket) {
        actualSourceFrames.add(getSourceFrame(frame));
      }
      assertThat(actualSourceFrames).containsExactlyElementsIn(expectedSourceFrames).inOrder();
    }
  }

  /** Creates a {@link GlTextureFrame} for testing. */
  private HardwareBufferFrame createFrame(
      long presentationTimeUs, long sequencePresentationTimeUs) {
    return createFrame(presentationTimeUs, sequencePresentationTimeUs, /* sequenceIndex= */ 0);
  }

  private HardwareBufferFrame createFrame(
      long presentationTimeUs, long sequencePresentationTimeUs, int sequenceIndex) {
    return new HardwareBufferFrame.Builder(
            /* hardwareBuffer= */ null,
            directExecutor(),
            (releaseFence) -> releasedFrameTimestamps.add(presentationTimeUs))
        .setPresentationTimeUs(presentationTimeUs)
        .setSequencePresentationTimeUs(sequencePresentationTimeUs)
        .setMetadata(
            new SourceFrameMetadata(
                new SourceFrame(sequenceIndex, presentationTimeUs, sequencePresentationTimeUs)))
        .setInternalFrame(this)
        .build();
  }

  private List<HardwareBufferFrame> createFrameList(
      int numFrames, float frameRate, int sequenceIndex) {
    return createFrameList(
        numFrames, frameRate, sequenceIndex, /* sequencePresentationTimeOffsetUs= */ 0);
  }

  private List<HardwareBufferFrame> createFrameList(
      int numFrames, float frameRate, int sequenceIndex, long sequencePresentationTimeOffsetUs) {
    List<HardwareBufferFrame> frames = new ArrayList<>();
    for (int i = 0; i < numFrames; i++) {
      long timeUs = Math.round(i * 1_000_000.0 / frameRate);
      frames.add(
          createFrame(
              /* presentationTimeUs= */ timeUs,
              /* sequencePresentationTimeUs= */ sequencePresentationTimeOffsetUs + timeUs,
              sequenceIndex));
    }
    return frames;
  }

  private static SourceFrame getSourceFrame(HardwareBufferFrame frame) {
    return ((SourceFrameMetadata) frame.getMetadata()).sourceFrame;
  }

  private static void registerAllSequences(FrameAggregator frameAggregator, int numSequences) {
    for (int i = 0; i < numSequences; i++) {
      frameAggregator.registerSequence(i, /* shouldAggregate= */ true);
    }
  }

  private static final class SourceFrame {
    final int sequenceIndex;
    final long presentationTimeUs;
    final long sequencePresentationTimeUs;

    SourceFrame(int sequenceIndex, long presentationTimeUs, long sequencePresentationTimeUs) {
      this.sequenceIndex = sequenceIndex;
      this.presentationTimeUs = presentationTimeUs;
      this.sequencePresentationTimeUs = sequencePresentationTimeUs;
    }

    @Override
    @SuppressWarnings("PatternMatchingInstanceof")
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof SourceFrame)) {
        return false;
      }
      SourceFrame other = (SourceFrame) obj;
      return sequenceIndex == other.sequenceIndex
          && presentationTimeUs == other.presentationTimeUs
          && sequencePresentationTimeUs == other.sequencePresentationTimeUs;
    }

    @Override
    public int hashCode() {
      return Objects.hash(sequenceIndex, presentationTimeUs, sequencePresentationTimeUs);
    }

    @Override
    public String toString() {
      return "SourceFrame[sequence="
          + sequenceIndex
          + ", pts="
          + presentationTimeUs
          + ", seqPts="
          + sequencePresentationTimeUs
          + "]";
    }
  }

  private static final class SourceFrameMetadata implements HardwareBufferFrame.Metadata {
    final SourceFrame sourceFrame;

    SourceFrameMetadata(SourceFrame sourceFrame) {
      this.sourceFrame = sourceFrame;
    }
  }
}
