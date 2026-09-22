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

import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.HardwareBufferFrameReader.CAPACITY;
import static androidx.media3.transformer.TransformerUtil.END_OF_STREAM_ASYNC_FRAME;
import static androidx.media3.transformer.TransformerUtil.releaseIfNeeded;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.round;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HardwareBufferSampleConsumerTest {

  private HardwareBufferSampleConsumer sampleConsumer;
  private List<AsyncFrame> receivedFrames;
  private HandlerThread handlerThread;
  private AtomicReference<ExportException> errorRef;

  @Before
  public void setUp() {
    handlerThread = new HandlerThread("HardwareBufferSampleConsumerTest");
    handlerThread.start();
    errorRef = new AtomicReference<>();
    receivedFrames = new ArrayList<>();

    sampleConsumer = createSampleConsumer(createComposition(/* itemCount= */ 1));
  }

  @After
  public void tearDown() {
    sampleConsumer.release();
    handlerThread.quit();
  }

  @Test
  public void queueInputBitmap_singleItem_outputsFramesWithCorrectReleaseTimeAndIndex() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        /* durationUs= */ 100_000,
        /* decodedFormat= */ format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    // 30fps constant rate for 100ms: frames at 0, 33333, 66666
    ConstantRateTimestampIterator timestampIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 100_000, /* frameRate= */ 30f);

    assertThat(sampleConsumer.queueInputBitmap(bitmap, timestampIterator))
        .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
    shadowOf(handlerThread.getLooper()).idle();

    // The reader outputs frames up to its capacity before any frame is released. The capacity is 2
    // below API 29 and 3 from API 29, and the timestamp iterator produces 3 frames, so the reader
    // outputs exactly CAPACITY frames.
    assertThat(receivedFrames).hasSize(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      AsyncFrame frame = receivedFrames.get(i);
      long expectedPresentationTimeUs = round(i * (C.MICROS_PER_SECOND / 30f));
      // The frame.hardwareBuffer should also be non null, but in Robolectric HardwareBuffers are
      // not created properly so this is tested in the AndroidTest.
      assertThat(getPresentationTimeUs(frame)).isEqualTo(expectedPresentationTimeUs);
      assertThat(frame.frame.getContentTimeUs()).isEqualTo(expectedPresentationTimeUs);
      assertThat(getDisplayTimeNs(frame)).isEqualTo(expectedPresentationTimeUs * 1000);
      assertThat(getCompositionFrameMetadata(frame).itemIndex).isEqualTo(0);
      // For bitmaps, the format is currently hardcoded in HardwareBufferFrameReader.
      assertThat(frame.frame.getFormat()).isNotNull();
      assertThat(frame.frame.getFormat().sampleMimeType).isEqualTo(MimeTypes.IMAGE_RAW);
    }
  }

  @Test
  public void onMediaItemChanged_updatesFormatAndAdjustsReleaseTime() {
    sampleConsumer.release();
    sampleConsumer = createSampleConsumer(createComposition(/* itemCount= */ 2));
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    long item1DurationUs = 10_000;
    Format format1 = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Format format2 = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build();

    // Simulate start of Item 1 (index 0)
    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        item1DurationUs,
        /* decodedFormat= */ format1,
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);

    // Simulate start of Item 2 (index 1)
    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        /* durationUs= */ 500_000,
        /* decodedFormat= */ format2,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    ConstantRateTimestampIterator timestampIterator =
        new ConstantRateTimestampIterator(item1DurationUs, /* frameRate= */ 30f);
    assertThat(sampleConsumer.queueInputBitmap(bitmap, timestampIterator))
        .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(1);
    AsyncFrame frame = receivedFrames.get(0);

    assertThat(getPresentationTimeUs(frame)).isEqualTo(0);
    assertThat(frame.frame.getContentTimeUs()).isEqualTo(item1DurationUs);
    assertThat(getDisplayTimeNs(frame)).isEqualTo(item1DurationUs * 1000);
    assertThat(getCompositionFrameMetadata(frame).itemIndex).isEqualTo(1);
  }

  @Test
  public void onMediaItemChanged_accumulatesOffsetsAcrossMultipleItems() {
    sampleConsumer.release();
    sampleConsumer = createSampleConsumer(createComposition(/* itemCount= */ 3));
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    long item1DurationUs = 1_000_000;
    long item2DurationUs = 500_000;
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        item1DurationUs,
        /* decodedFormat= */ format,
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);

    ConstantRateTimestampIterator timestampIterator1 =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 30f);
    assertThat(sampleConsumer.queueInputBitmap(bitmap, timestampIterator1))
        .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(1);
    AsyncFrame frame1 = receivedFrames.get(0);
    assertThat(frame1.frame.getContentTimeUs()).isEqualTo(0);
    assertThat(getDisplayTimeNs(frame1)).isEqualTo(0);
    assertThat(getCompositionFrameMetadata(frame1).itemIndex).isEqualTo(0);

    for (AsyncFrame frame : receivedFrames) {
      releaseIfNeeded(frame.frame, /* releaseFence= */ null);
    }
    shadowOf(handlerThread.getLooper()).idle();
    receivedFrames.clear();

    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        item2DurationUs,
        /* decodedFormat= */ format,
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);

    ConstantRateTimestampIterator timestampIterator2 =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 30f);
    assertThat(sampleConsumer.queueInputBitmap(bitmap, timestampIterator2))
        .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(1);
    AsyncFrame frame2 = receivedFrames.get(0);
    assertThat(getPresentationTimeUs(frame2)).isEqualTo(0);
    assertThat(frame2.frame.getContentTimeUs()).isEqualTo(item1DurationUs);
    assertThat(getDisplayTimeNs(frame2)).isEqualTo(item1DurationUs * 1000);
    assertThat(getCompositionFrameMetadata(frame2).itemIndex).isEqualTo(1);

    for (AsyncFrame frame : receivedFrames) {
      releaseIfNeeded(frame.frame, /* releaseFence= */ null);
    }
    shadowOf(handlerThread.getLooper()).idle();
    receivedFrames.clear();

    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        /* durationUs= */ 100_000,
        /* decodedFormat= */ format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    ConstantRateTimestampIterator timestampIterator3 =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 30f);
    assertThat(sampleConsumer.queueInputBitmap(bitmap, timestampIterator3))
        .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(1);
    AsyncFrame frame3 = receivedFrames.get(0);
    long expectedOffsetUs3 = item1DurationUs + item2DurationUs;
    assertThat(getPresentationTimeUs(frame3)).isEqualTo(0);
    assertThat(frame3.frame.getContentTimeUs()).isEqualTo(expectedOffsetUs3);
    assertThat(getDisplayTimeNs(frame3)).isEqualTo(expectedOffsetUs3 * 1000);
    assertThat(getCompositionFrameMetadata(frame3).itemIndex).isEqualTo(2);
  }

  @Test
  public void onMediaItemChanged_withLoopingSequence_wrapsItemIndex() {
    sampleConsumer.release();
    sampleConsumer =
        createSampleConsumer(createComposition(/* itemCount= */ 2, /* isLooping= */ true));
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    for (int expectedItemIndex : new int[] {0, 1, 0}) {
      sampleConsumer.onMediaItemChanged(
          /* editedMediaItem= */ null,
          /* durationUs= */ 100_000,
          /* decodedFormat= */ format,
          /* isLast= */ false,
          /* positionOffsetUs= */ 0);
      assertThat(
              sampleConsumer.queueInputBitmap(
                  bitmap,
                  new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 30f)))
          .isEqualTo(GraphInput.INPUT_RESULT_SUCCESS);
      shadowOf(handlerThread.getLooper()).idle();

      assertThat(receivedFrames).hasSize(1);
      assertThat(getCompositionFrameMetadata(receivedFrames.get(0)).itemIndex)
          .isEqualTo(expectedItemIndex);
      releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
      shadowOf(handlerThread.getLooper()).idle();
      receivedFrames.clear();
    }

    sampleConsumer.signalEndOfVideoInput();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).isNotEmpty();
    assertThat(Iterables.getLast(receivedFrames)).isEqualTo(END_OF_STREAM_ASYNC_FRAME);
  }

  @Test
  public void otherMethods_returnsExpectedValues() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        /* durationUs= */ 1_000,
        /* decodedFormat= */ format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(sampleConsumer.getPendingVideoFrameCount()).isEqualTo(0);
    assertThat(sampleConsumer.getInputSurface()).isNotNull();
    // Initially should accept frames
    assertThat(sampleConsumer.registerVideoFrame(0)).isTrue();
  }

  @Test
  public void registerVideoFrame_beforeOnMediaItemChanged_throwsIllegalStateException() {
    assertThrows(IllegalStateException.class, () -> sampleConsumer.registerVideoFrame(0));
  }

  @Test
  public void signalEndOfVideoInput_queuesEndOfStream() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    sampleConsumer.onMediaItemChanged(
        /* editedMediaItem= */ null,
        /* durationUs= */ 1_000,
        /* decodedFormat= */ format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    sampleConsumer.signalEndOfVideoInput();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).isNotEmpty();
    assertThat(Iterables.getLast(receivedFrames)).isEqualTo(END_OF_STREAM_ASYNC_FRAME);
  }

  private HardwareBufferSampleConsumer createSampleConsumer(Composition composition) {
    return createSampleConsumer(composition, /* sequenceIndex= */ composition.sequences.size() - 1);
  }

  private HardwareBufferSampleConsumer createSampleConsumer(
      Composition composition, int sequenceIndex) {
    Looper looper = handlerThread.getLooper();
    HandlerWrapper handlerWrapper = SystemClock.DEFAULT.createHandler(looper, /* callback= */ null);

    HardwareBufferJniWrapper mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);
    return new HardwareBufferSampleConsumer(
        composition,
        sequenceIndex,
        looper,
        handlerWrapper,
        frame -> receivedFrames.add(frame),
        error -> errorRef.set(error),
        mockJniWrapper);
  }

  private static Composition createComposition(int itemCount) {
    return createComposition(itemCount, /* isLooping= */ false);
  }

  private static Composition createComposition(int itemCount, boolean isLooping) {
    ImmutableList.Builder<EditedMediaItem> mediaItems = ImmutableList.builder();
    for (int i = 0; i < itemCount; i++) {
      mediaItems.add(
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build());
    }
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addItems(mediaItems.build())
            .setIsLooping(isLooping)
            .build();
    if (isLooping) {
      EditedMediaItem primaryItem =
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
      EditedMediaItemSequence primarySequence = withAudioFrom(ImmutableList.of(primaryItem));
      return new Composition.Builder(primarySequence, sequence).build();
    }
    return new Composition.Builder(sequence).build();
  }

  private static long getPresentationTimeUs(AsyncFrame asyncFrame) {
    return checkNotNull((Long) asyncFrame.frame.getMetadata().get(Frame.KEY_PRESENTATION_TIME_US));
  }

  private static long getDisplayTimeNs(AsyncFrame asyncFrame) {
    return checkNotNull((Long) asyncFrame.frame.getMetadata().get(Frame.KEY_DISPLAY_TIME_NS));
  }

  @SuppressWarnings("deprecation") // Uses deprecated CompositionFrameMetadata.
  private static CompositionFrameMetadata getCompositionFrameMetadata(AsyncFrame asyncFrame) {
    return (CompositionFrameMetadata)
        asyncFrame.frame.getMetadata().get(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA);
  }
}
