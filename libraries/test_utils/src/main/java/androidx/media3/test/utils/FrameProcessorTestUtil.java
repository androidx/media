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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.hash;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionFrameMetadata;
import androidx.media3.transformer.EditedMediaItemSequence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** Test utilities for asserting {@link FrameProcessor} inputs. */
@RequiresApi(26)
@UnstableApi
public final class FrameProcessorTestUtil {

  /** Represents a frame that is expected to be presented. */
  public static class ExpectedFrame {
    public final long contentTimeUs;
    public final int sequenceIndex;

    /**
     * Creates an instance.
     *
     * @param contentTimeUs The expected presentation timestamp, in microseconds.
     * @param sequenceIndex The index of the sequence this frame belongs to.
     */
    public ExpectedFrame(long contentTimeUs, int sequenceIndex) {
      this.contentTimeUs = contentTimeUs;
      this.sequenceIndex = sequenceIndex;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ExpectedFrame)) {
        return false;
      }
      ExpectedFrame that = (ExpectedFrame) o;
      return contentTimeUs == that.contentTimeUs && sequenceIndex == that.sequenceIndex;
    }

    @Override
    public int hashCode() {
      return hash(contentTimeUs, sequenceIndex);
    }

    @Override
    public String toString() {
      return Util.formatInvariant("(t=%d, seq=%d)", contentTimeUs, sequenceIndex);
    }
  }

  /**
   * Asserts the {@link CapturingFrameProcessor} consumes frames matching the {@link
   * CompositionAssetInfo}, if it was played from start to finish.
   *
   * <p>This method verifies both the timing/structure of the playback (timestamps, sequence
   * indices, EOS) and that the correct {@link MediaItem} metadata is associated with each frame.
   */
  public static void assertPlaybackOutput(
      CapturingFrameProcessor frameProcessor, CompositionAssetInfo compositionAssetInfo) {
    ImmutableList<CapturingFrameProcessor.Event> actualEvents = frameProcessor.getQueuedEvents();
    assertThat(Iterables.getLast(actualEvents))
        .isInstanceOf(CapturingFrameProcessor.EosEvent.class);

    ImmutableList<CapturingFrameProcessor.Event> actualEventsWithoutEos =
        actualEvents.subList(0, actualEvents.size() - 1);
    ImmutableList<List<ExpectedFrame>> actual = toExpectedEvents(actualEventsWithoutEos);
    ImmutableList<List<ExpectedFrame>> expected = getExpectedEvents(compositionAssetInfo);
    assertThat(actual).isEqualTo(expected);

    for (int i = 0; i < actualEventsWithoutEos.size(); i++) {
      List<ExpectedFrame> expectedFrames = expected.get(i);
      CapturingFrameProcessor.Event actualEvent = actualEvents.get(i);

      assertThat(actualEvent).isInstanceOf(CapturingFrameProcessor.FramesEvent.class);
      CapturingFrameProcessor.FramesEvent actualFrames =
          (CapturingFrameProcessor.FramesEvent) actualEvent;

      for (int j = 0; j < actualFrames.frames.size(); j++) {
        AsyncFrame asyncFrame = actualFrames.frames.get(j);
        Frame frame = asyncFrame.frame;
        ExpectedFrame expectedFrame = expectedFrames.get(j);

        CompositionFrameMetadata compositionFrameMetadata =
            checkNotNull(
                (CompositionFrameMetadata)
                    frame
                        .getMetadata()
                        .get(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA));
        assertThat(compositionFrameMetadata).isNotNull();

        MediaItem itemFromMetadata = itemFromMetadata(compositionFrameMetadata);
        MediaItem expectedItemAtTime =
            expectedItemAtTime(
                compositionAssetInfo, expectedFrame.sequenceIndex, expectedFrame.contentTimeUs);
        assertThat(itemFromMetadata).isEqualTo(expectedItemAtTime);
      }
    }
  }

  /**
   * Returns the events expected to be consumed by the {@link
   * androidx.media3.common.video.FrameProcessor} if the {@linkplain
   * androidx.media3.test.utils.CompositionAssetInfo#getComposition() Composition} was played from
   * start to finish.
   */
  public static ImmutableList<List<ExpectedFrame>> getExpectedEvents(
      CompositionAssetInfo compositionAssetInfo) {
    List<SequenceAssetInfo> videoSequences = new ArrayList<>();
    List<Integer> videoSequenceIndices = new ArrayList<>();
    for (int i = 0; i < compositionAssetInfo.sequences.size(); i++) {
      SequenceAssetInfo seq = compositionAssetInfo.sequences.get(i);
      if (seq.trackTypes.contains(C.TRACK_TYPE_VIDEO)) {
        videoSequences.add(seq);
        videoSequenceIndices.add(i);
      }
    }
    if (videoSequences.isEmpty()) {
      return ImmutableList.of();
    }

    return getExpectedEvents(
        videoSequences.get(0).getExpectedVideoTimestampsUs(),
        videoSequenceIndices.get(0),
        extractContentTimesUs(videoSequences.subList(/* fromIndex= */ 1, videoSequences.size())),
        videoSequenceIndices.subList(/* fromIndex= */ 1, videoSequenceIndices.size()));
  }

  /**
   * Calculates the expected events from the primary and secondary sequence timestamps.
   *
   * <p>For each primary timestamp, this utility selects the smallest secondary timestamp greater
   * than or equal to the primary. If there is no matching secondary timestamp, then that sequence
   * is omitted. This matches the logic in androidx.media3.transformer.FrameAggregator when the
   * primary sequence drives the aggregation.
   *
   * @param primaryContentTimesUs Timestamps of the primary sequence.
   * @param primarySeqIndex Index of the primary sequence.
   * @param secondarySequencesContentTimesUs Timestamps of the secondary sequences.
   * @param secondarySeqIndices Indices of the secondary sequences.
   * @return A list of expected events.
   */
  private static ImmutableList<List<ExpectedFrame>> getExpectedEvents(
      List<Long> primaryContentTimesUs,
      int primarySeqIndex,
      List<Queue<Long>> secondarySequencesContentTimesUs,
      List<Integer> secondarySeqIndices) {

    ImmutableList.Builder<List<ExpectedFrame>> expectedEvents = ImmutableList.builder();

    // Always add one primary timestamp per packet.
    for (long primaryTime : primaryContentTimesUs) {
      List<ExpectedFrame> packet = new ArrayList<>();
      packet.add(new ExpectedFrame(primaryTime, primarySeqIndex));

      for (int i = 0; i < secondarySequencesContentTimesUs.size(); i++) {
        Queue<Long> queue = secondarySequencesContentTimesUs.get(i);
        int seqIndex = secondarySeqIndices.get(i);

        // Drain the secondary sequence while it's timestamp is less than the primary timestamp.
        while (!queue.isEmpty()) {
          long peekedTime = checkNotNull(queue.peek());
          if (peekedTime < primaryTime) {
            queue.poll();
          } else {
            break;
          }
        }

        if (!queue.isEmpty()) {
          // Guaranteed to be the smallest timestamp >= to the primary time.
          packet.add(new ExpectedFrame(checkNotNull(queue.peek()), seqIndex));
        }
      }
      expectedEvents.add(packet);
    }
    return expectedEvents.build();
  }

  /**
   * Converts the recorded {@link CapturingFrameProcessor.Event}s to expected events format.
   *
   * @param actualEvents The events recorded by the capturing processor.
   * @return The mapped expected events.
   */
  private static ImmutableList<List<ExpectedFrame>> toExpectedEvents(
      List<CapturingFrameProcessor.Event> actualEvents) {
    List<List<ExpectedFrame>> result = new ArrayList<>();
    for (CapturingFrameProcessor.Event event : actualEvents) {
      assertThat(event).isInstanceOf(CapturingFrameProcessor.FramesEvent.class);
      CapturingFrameProcessor.FramesEvent framesEvent = (CapturingFrameProcessor.FramesEvent) event;
      List<ExpectedFrame> packet = new ArrayList<>();
      for (AsyncFrame asyncFrame : framesEvent.frames) {
        Frame frame = asyncFrame.frame;
        long contentTimeUs = frame.getContentTimeUs();
        CompositionFrameMetadata metadata =
            (CompositionFrameMetadata)
                frame.getMetadata().get(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA);
        int sequenceIndex = metadata != null ? metadata.sequenceIndex : C.INDEX_UNSET;
        packet.add(new ExpectedFrame(contentTimeUs, sequenceIndex));
      }
      result.add(packet);
    }
    return ImmutableList.copyOf(result);
  }

  /** Extracts the expected video timestamps from a list of sequences. */
  private static ImmutableList<Queue<Long>> extractContentTimesUs(
      List<SequenceAssetInfo> sequences) {
    ImmutableList.Builder<Queue<Long>> list = new ImmutableList.Builder<>();
    for (SequenceAssetInfo seq : sequences) {
      list.add(new ArrayDeque<>(seq.getExpectedVideoTimestampsUs()));
    }
    return list.build();
  }

  /** Extracts the {@link MediaItem} that produced the frame from its metadata. */
  private static MediaItem itemFromMetadata(CompositionFrameMetadata metadata) {
    return metadata
        .composition
        .sequences
        .get(metadata.sequenceIndex)
        .editedMediaItems
        .get(metadata.itemIndex)
        .mediaItem;
  }

  /**
   * Resolves the expected {@link MediaItem} that should be active in a sequence at a given
   * presentation time.
   */
  private static MediaItem expectedItemAtTime(
      CompositionAssetInfo compositionAssetInfo, int sequenceIndex, long presentationTimeUs) {
    Composition composition = compositionAssetInfo.getComposition();
    EditedMediaItemSequence sequence = composition.sequences.get(sequenceIndex);
    int itemIndex = 0;
    while (itemIndex < sequence.editedMediaItems.size()) {
      long itemDurationUs = sequence.editedMediaItems.get(itemIndex).getPresentationDurationUs();
      if (presentationTimeUs < itemDurationUs) {
        break;
      }
      presentationTimeUs -= itemDurationUs;
      itemIndex++;
    }
    assertThat(itemIndex).isLessThan(sequence.editedMediaItems.size());
    return sequence.editedMediaItems.get(itemIndex).mediaItem;
  }

  /** Prevents instantiation. */
  private FrameProcessorTestUtil() {}
}
