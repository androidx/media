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

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
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
  private List<HardwareBufferFrame> receivedFrames;
  private HandlerThread handlerThread;
  private AtomicReference<ExportException> errorRef;

  @Before
  public void setUp() {
    handlerThread = new HandlerThread("HardwareBufferSampleConsumerTest");
    handlerThread.start();
    errorRef = new AtomicReference<>();
    receivedFrames = new ArrayList<>();

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();

    Looper looper = handlerThread.getLooper();
    HandlerWrapper handlerWrapper = SystemClock.DEFAULT.createHandler(looper, /* callback= */ null);

    sampleConsumer =
        new HardwareBufferSampleConsumer(
            composition,
            /* sequenceIndex= */ 0,
            looper,
            handlerWrapper,
            frame -> receivedFrames.add(frame),
            error -> errorRef.set(error));
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

    assertThat(receivedFrames).hasSize(2);

    HardwareBufferFrame frame1 = receivedFrames.get(0);
    // The frame.hardwareBuffer should also be non null, but in roboelectric HardwareBuffers are not
    // created properly so this is tested in the AndroidTest.
    assertThat(frame1.presentationTimeUs).isEqualTo(0);
    assertThat(frame1.releaseTimeNs).isEqualTo(0);
    assertThat(((CompositionFrameMetadata) frame1.getMetadata()).itemIndex).isEqualTo(0);
    // For bitmaps, the format is currently hardcoded in HardwareBufferFrameReader.
    assertThat(frame1.format).isNotNull();
    assertThat(frame1.format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_RAW);

    HardwareBufferFrame frame2 = receivedFrames.get(1);
    assertThat(frame2.presentationTimeUs).isEqualTo(33_333);
    assertThat(frame2.releaseTimeNs).isEqualTo(33_333 * 1000);
    assertThat(((CompositionFrameMetadata) frame2.getMetadata()).itemIndex).isEqualTo(0);
    assertThat(frame2.format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_RAW);
  }

  @Test
  public void onMediaItemChanged_updatesFormatAndAdjustsReleaseTime() {
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
    HardwareBufferFrame frame = receivedFrames.get(0);

    long expectedOffsetNs = item1DurationUs * 1000;
    assertThat(frame.presentationTimeUs).isEqualTo(0);
    assertThat(frame.releaseTimeNs).isEqualTo(expectedOffsetNs);
    assertThat(((CompositionFrameMetadata) frame.getMetadata()).itemIndex).isEqualTo(1);
  }

  @Test
  public void onMediaItemChanged_accumulatesOffsetsAcrossMultipleItems() {
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
    HardwareBufferFrame frame1 = receivedFrames.get(0);
    assertThat(frame1.releaseTimeNs).isEqualTo(frame1.presentationTimeUs * 1000);
    assertThat(frame1.getMetadata()).isInstanceOf(CompositionFrameMetadata.class);
    assertThat(((CompositionFrameMetadata) frame1.getMetadata()).itemIndex).isEqualTo(0);

    for (HardwareBufferFrame frame : receivedFrames) {
      frame.release(/* releaseFence= */ null);
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
    HardwareBufferFrame frame2 = receivedFrames.get(0);
    long expectedOffset2 = item1DurationUs * 1000;
    assertThat(frame2.releaseTimeNs)
        .isEqualTo((frame2.presentationTimeUs * 1000) + expectedOffset2);
    assertThat(((CompositionFrameMetadata) frame2.getMetadata()).itemIndex).isEqualTo(1);

    for (HardwareBufferFrame frame : receivedFrames) {
      frame.release(/* releaseFence= */ null);
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
    HardwareBufferFrame frame3 = receivedFrames.get(0);
    long expectedOffset3 = (item1DurationUs + item2DurationUs) * 1000;
    assertThat(frame3.releaseTimeNs)
        .isEqualTo((frame3.presentationTimeUs * 1000) + expectedOffset3);
    assertThat(((CompositionFrameMetadata) frame3.getMetadata()).itemIndex).isEqualTo(2);
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
    assertThat(Iterables.getLast(receivedFrames))
        .isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }
}
