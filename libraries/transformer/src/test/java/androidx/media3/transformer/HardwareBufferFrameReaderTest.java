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

import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.HardwareBufferFrameReader.CAPACITY;
import static androidx.media3.transformer.TransformerUtil.END_OF_STREAM_ASYNC_FRAME;
import static androidx.media3.transformer.TransformerUtil.releaseIfNeeded;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.round;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.os.HandlerThread;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.transformer.HardwareBufferFrameReader.RendererWakeupListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Robolectric tests for {@link HardwareBufferFrameReader}. */
@RunWith(AndroidJUnit4.class)
public class HardwareBufferFrameReaderTest {

  private List<AsyncFrame> receivedFrames;
  private HardwareBufferFrameReader hardwareBufferFrameReader;
  private HandlerThread handlerThread;
  private AtomicReference<Exception> hardwareBufferFrameReaderException;

  @Before
  public void setUp() {
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();
    handlerThread = new HandlerThread("HardwareBufferFrameReaderTest");
    handlerThread.start();
    hardwareBufferFrameReaderException = new AtomicReference<>();
    receivedFrames = new ArrayList<>();

    HardwareBufferJniWrapper mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);
    hardwareBufferFrameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            /* frameConsumer= */ asyncFrame -> {
              receivedFrames.add(asyncFrame);
            },
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            new DefaultImageReaderAdapter.Factory(),
            /* listener= */ e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
            mockJniWrapper);
  }

  @After
  public void tearDown() {
    hardwareBufferFrameReader.release();
    handlerThread.quit();
  }

  @Test
  public void canAcceptFrameViaSurface_returnsTrue() {
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isTrue();
  }

  @Test
  public void canAcceptFrameViaSurface_afterSurfaceFrame_returnsFalse() {
    hardwareBufferFrameReader.queueFrameViaSurface(
        /* presentationTimeUs= */ 0,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0,
        new Format.Builder().build());

    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void canAcceptFrameViaSurface_afterSequenceOfBitmaps_returnsFalse() {
    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f),
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void canAcceptFrameViaSurface_afterSingleBitmap_returnsTrue() {
    // A timestamp iterator that will produce only a single output frame.
    TimestampIterator singleFrame =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ singleFrame,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    assertThat(singleFrame.hasNext()).isFalse();
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isTrue();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void outputBitmap_outputsBitmapsUpToCapacityImmediately() {
    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ thirtyFrames,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    // Trying to output 30 frames. Stop outputting when the output capacity is reached.
    assertThat(thirtyFrames.hasNext()).isTrue();
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();
    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(
        receivedFrames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void releaseFrame_outputsMoreBitmaps() {
    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ thirtyFrames,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    // Trying to output 30 frames. Stop outputting when the output capacity is reached.
    assertThat(receivedFrames).hasSize(CAPACITY);

    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    // Once a frame is released, more output can be generated.
    assertThat(receivedFrames).hasSize(CAPACITY + 1);
    assertFramePresentationTimes(
        receivedFrames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void addWakeupListenerProvider_releaseFrame_callsRendererWakeupListener() {
    TimestampIterator capacityFrames = createTimestampIterator(CAPACITY);
    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ capacityFrames,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    AtomicBoolean onWakeupCalled = new AtomicBoolean();
    hardwareBufferFrameReader.addRendererWakeupListener(() -> onWakeupCalled.set(true));
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();

    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(onWakeupCalled.get()).isTrue();
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isTrue();
  }

  @Test
  public void releaseFrame_afterRemoveWakeupListenerProvider_doesNotRendererWakeupListener() {
    AtomicBoolean onWakeupCalled = new AtomicBoolean();
    RendererWakeupListener rendererWakeupListener = () -> onWakeupCalled.set(true);
    TimestampIterator capacityFrames = createTimestampIterator(CAPACITY);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ capacityFrames,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    hardwareBufferFrameReader.addRendererWakeupListener(rendererWakeupListener);
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();

    hardwareBufferFrameReader.removeRendererWakeupListener(rendererWakeupListener);
    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(onWakeupCalled.get()).isFalse();
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isTrue();
  }

  @Test
  public void queueEndOfStream_outputsEosFrame() {
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).containsExactly(END_OF_STREAM_ASYNC_FRAME);
  }

  @Test
  public void outputBitmap_thenQueueEndOfStream_outputsBitmapThenEos() {
    TimestampIterator singleFrameIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

    hardwareBufferFrameReader.outputBitmap(
        bitmap, singleFrameIterator, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).hasSize(2);
    assertThat(getInternalImage(receivedFrames.get(0))).isInstanceOf(Bitmap.class);
    assertThat(getPresentationTimeUs(receivedFrames.get(0))).isEqualTo(0);
    assertThat(receivedFrames.get(0).frame.getContentTimeUs()).isEqualTo(0);
    assertThat(receivedFrames.get(1)).isEqualTo(END_OF_STREAM_ASYNC_FRAME);
  }

  @Test
  public void outputBitmap_withSequenceOffset_outputsFramesWithIncreasingSequenceTime() {
    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);
    long sequenceOffsetUs = 100_000;

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ thirtyFrames,
        sequenceOffsetUs,
        /* indexOfItem= */ 1);

    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(receivedFrames, /* firstFrameIndex= */ 0, sequenceOffsetUs);

    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    // Check that as presentation time increments, sequence time also correctly increments.\
    assertThat(receivedFrames).hasSize(CAPACITY + 1);
    assertFramePresentationTimes(receivedFrames, /* firstFrameIndex= */ 0, sequenceOffsetUs);
  }

  @Test
  public void queueEndOfStream_thenOutputBitmap_outputsEosThenBitmap() {
    // Use a single frame iterator to avoid capacity issues blocking immediate output
    TimestampIterator singleFrameIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.outputBitmap(
        bitmap, singleFrameIterator, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0)).isEqualTo(END_OF_STREAM_ASYNC_FRAME);
    assertThat(getInternalImage(receivedFrames.get(1))).isInstanceOf(Bitmap.class);
    assertThat(getPresentationTimeUs(receivedFrames.get(1))).isEqualTo(0);
    assertThat(receivedFrames.get(1).frame.getContentTimeUs()).isEqualTo(0);
  }

  @Test
  public void queueEndOfStream_multipleTimes_outputsMultipleEosFrames() {
    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames)
        .containsExactly(END_OF_STREAM_ASYNC_FRAME, END_OF_STREAM_ASYNC_FRAME)
        .inOrder();
  }

  @Test
  public void queueEndOfStream_afterBitmapsFillCapacity_isOutputAfterBitmapsReleased() {
    TimestampIterator capacityPlusOneFrames = createTimestampIterator(CAPACITY + 1);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    // Queue one more bitmap than the capacity, and EOS. Only the bitmaps filling the capacity
    // should be output immediately.
    hardwareBufferFrameReader.outputBitmap(
        bitmap, capacityPlusOneFrames, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(
        receivedFrames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);

    releaseIfNeeded(receivedFrames.remove(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    // The last bitmap is output, keeping the capacity filled.
    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(
        receivedFrames, /* firstFrameIndex= */ 1, /* sequenceOffsetUs= */ 0);
    assertThat(capacityPlusOneFrames.hasNext()).isFalse();

    releaseIfNeeded(receivedFrames.remove(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    // With no bitmaps left, EOS is output after the remaining bitmaps.
    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(
        receivedFrames.subList(0, CAPACITY - 1),
        /* firstFrameIndex= */ 2,
        /* sequenceOffsetUs= */ 0);
    assertThat(receivedFrames.get(CAPACITY - 1)).isEqualTo(END_OF_STREAM_ASYNC_FRAME);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void flush_preventsMoreFramesFromBeingOutput() {
    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ thirtyFrames,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    // Trying to output 30 frames, but HardwareBufferFrameReader#CAPACITY is smaller.
    assertThat(receivedFrames).hasSize(CAPACITY);

    hardwareBufferFrameReader.flush();

    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    // Once a frame is released, more output can be accepted downstream. But calling flush()
    // clears the remaining frames from the thirtyFrames timestamp iterator.
    assertThat(receivedFrames).hasSize(CAPACITY);
    assertFramePresentationTimes(
        receivedFrames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void outputBitmap_withSdrBitmap_outputsImageRawFormatWithSdrColorTransfer() {
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    TimestampIterator singleFrame =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);

    hardwareBufferFrameReader.outputBitmap(
        bitmap,
        /* timestampIterator= */ singleFrame,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    assertThat(receivedFrames).hasSize(1);
    Format format = receivedFrames.get(0).frame.getFormat();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_RAW);
    assertThat(format.colorInfo).isNotNull();
    assertThat(format.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_SDR);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Config(sdk = 34)
  @Test
  public void outputBitmap_withUltraHdrBitmap_outputsImageJpegRFormatWithSrgbColorTransfer() {
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    Bitmap gainmapBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
    Gainmap gainmap = new Gainmap(gainmapBitmap);
    bitmap.setGainmap(gainmap);
    TimestampIterator singleFrame =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);

    hardwareBufferFrameReader.outputBitmap(
        bitmap,
        /* timestampIterator= */ singleFrame,
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);

    assertThat(receivedFrames).hasSize(1);
    Format format = receivedFrames.get(0).frame.getFormat();
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.IMAGE_JPEG_R);
    assertThat(format.colorInfo).isNotNull();
    assertThat(format.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_SRGB);
    assertThat(format.colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT709);
    assertThat(format.colorInfo.colorRange).isEqualTo(C.COLOR_RANGE_FULL);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void
      outputBitmap_withBitmapToHardwareBufferConverter_reusesHardwareBufferForRepeatedBitmap() {
    HardwareBufferJniWrapper mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem))).build();
    List<AsyncFrame> frames = new ArrayList<>();
    HardwareBufferFrameReader frameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            /* frameConsumer= */ frames::add,
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            new DefaultImageReaderAdapter.Factory(),
            /* listener= */ e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
            mockJniWrapper);

    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    frameReader.outputBitmap(bitmap, thirtyFrames, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0);

    assertThat(frames).hasSize(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      DefaultHardwareBufferFrame frame = (DefaultHardwareBufferFrame) frames.get(i).frame;
      assertThat(frame).isNotNull();
      assertThat(frame.getHardwareBuffer())
          .isSameInstanceAs(((DefaultHardwareBufferFrame) frames.get(0).frame).getHardwareBuffer());
      assertThat(frame.getInternalImage()).isSameInstanceAs(bitmap);
    }
    assertFramePresentationTimes(frames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);

    releaseIfNeeded(frames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(frames).hasSize(CAPACITY + 1);
    DefaultHardwareBufferFrame frame = (DefaultHardwareBufferFrame) frames.get(CAPACITY).frame;
    assertThat(frame.getHardwareBuffer())
        .isSameInstanceAs(((DefaultHardwareBufferFrame) frames.get(0).frame).getHardwareBuffer());
    assertThat(frame.getInternalImage()).isSameInstanceAs(bitmap);
    assertFramePresentationTimes(frames, /* firstFrameIndex= */ 0, /* sequenceOffsetUs= */ 0);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();

    for (int i = 1; i < CAPACITY; i++) {
      releaseIfNeeded(frames.get(1).frame, /* releaseFence= */ null);
    }
    frameReader.release();
  }

  @Test
  public void outputBitmap_withConverterAndDifferentBitmap_createsNewHardwareBuffer() {
    HardwareBufferJniWrapper mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);

    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2)))
            .build();
    List<AsyncFrame> frames = new ArrayList<>();
    HardwareBufferFrameReader frameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            /* frameConsumer= */ frames::add,
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            new DefaultImageReaderAdapter.Factory(),
            /* listener= */ e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
            mockJniWrapper);

    TimestampIterator singleFrame1 =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    TimestampIterator singleFrame2 =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    Bitmap bitmap1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    Bitmap bitmap2 = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);

    frameReader.outputBitmap(
        bitmap1, singleFrame1, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0);
    frameReader.outputBitmap(
        bitmap2, singleFrame2, /* sequenceOffsetUs= */ 10_000, /* indexOfItem= */ 1);

    assertThat(frames).hasSize(2);
    assertThat(getHardwareBuffer(frames.get(0))).isNotNull();
    assertThat(getHardwareBuffer(frames.get(1))).isNotNull();
    assertThat(getHardwareBuffer(frames.get(0)))
        .isNotSameInstanceAs(getHardwareBuffer(frames.get(1)));

    frameReader.release();
  }

  @Test
  public void outputBitmap_withoutHardwareBuffer_throwsNullPointerException() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    HardwareBufferFrameReader frameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            /* frameConsumer= */ receivedFrames::add,
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            new DefaultImageReaderAdapter.Factory(),
            /* listener= */ e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
            /* hardwareBufferJniWrapper= */ null);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    ConstantRateTimestampIterator timestampIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);
    try {
      assertThrows(
          NullPointerException.class,
          () ->
              frameReader.outputBitmap(
                  bitmap, timestampIterator, /* sequenceOffsetUs= */ 0, /* indexOfItem= */ 0));
    } finally {
      frameReader.release();
    }
  }

  private static long getPresentationTimeUs(AsyncFrame asyncFrame) {
    Long presentationTimeUs =
        (Long) asyncFrame.frame.getMetadata().get(Frame.KEY_PRESENTATION_TIME_US);
    return presentationTimeUs != null ? presentationTimeUs : asyncFrame.frame.getContentTimeUs();
  }

  private static Object getInternalImage(AsyncFrame asyncFrame) {
    return ((DefaultHardwareBufferFrame) asyncFrame.frame).getInternalImage();
  }

  private static HardwareBuffer getHardwareBuffer(AsyncFrame asyncFrame) {
    return ((HardwareBufferFrame) asyncFrame.frame).getHardwareBuffer();
  }

  /** Returns a 30 fps {@link TimestampIterator} producing exactly {@code frameCount} timestamps. */
  private static TimestampIterator createTimestampIterator(int frameCount) {
    return new ConstantRateTimestampIterator(
        /* durationUs= */ round(frameCount * (C.MICROS_PER_SECOND / 30f)), /* frameRate= */ 30f);
  }

  /**
   * Asserts that {@code frames} have the presentation times of the 30 fps frames starting at {@code
   * firstFrameIndex}, with sequence presentation times shifted by {@code sequenceOffsetUs}.
   */
  private static void assertFramePresentationTimes(
      List<AsyncFrame> frames, int firstFrameIndex, long sequenceOffsetUs) {
    for (int i = 0; i < frames.size(); i++) {
      long presentationTimeUs = round((firstFrameIndex + i) * (C.MICROS_PER_SECOND / 30f));
      assertThat(getPresentationTimeUs(frames.get(i))).isEqualTo(presentationTimeUs);
      assertThat(frames.get(i).frame.getContentTimeUs())
          .isEqualTo(presentationTimeUs + sequenceOffsetUs);
    }
  }
}
