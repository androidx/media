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

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.os.HandlerThread;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.Util;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Robolectric tests for {@link HardwareBufferFrameReader}. */
@RunWith(AndroidJUnit4.class)
public class HardwareBufferFrameReaderTest {

  private List<HardwareBufferFrame> receivedFrames;
  private HardwareBufferFrameReader hardwareBufferFrameReader;
  private HandlerThread handlerThread;
  private AtomicReference<Exception> hardwareBufferFrameReaderException;

  @Before
  public void setUp() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    handlerThread = new HandlerThread("HardwareBufferFrameReaderTest");
    handlerThread.start();
    hardwareBufferFrameReaderException = new AtomicReference<>();
    receivedFrames = new ArrayList<>();

    hardwareBufferFrameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            /* frameConsumer= */ hardwareBufferFrame -> {
              receivedFrames.add(hardwareBufferFrame);
            },
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            /* listener= */ e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null));
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
        /* presentationTimeUs= */ 0, /* indexOfItem= */ 0);

    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void canAcceptFrameViaSurface_afterSequenceOfBitmaps_returnsFalse() {
    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f),
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
        /* indexOfItem= */ 0);

    // Trying to output 30 frames. Stop outputting when the output capacity is reached.
    assertThat(thirtyFrames.hasNext()).isTrue();
    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isFalse();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(0);
    assertThat(receivedFrames.get(1).presentationTimeUs).isEqualTo(33_333);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void releaseFrame_outputsMoreBitmaps() {
    TimestampIterator thirtyFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        /* timestampIterator= */ thirtyFrames,
        /* indexOfItem= */ 0);

    // Trying to output 30 frames. Stop outputting when the output capacity is reached.
    assertThat(receivedFrames).hasSize(2);

    receivedFrames.get(0).release();
    shadowOf(handlerThread.getLooper()).idle();

    // Once a frame is released, more output can be generated.
    assertThat(receivedFrames).hasSize(3);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(0);
    assertThat(receivedFrames.get(1).presentationTimeUs).isEqualTo(33_333);
    assertThat(receivedFrames.get(2).presentationTimeUs).isEqualTo(66_667);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void queueEndOfStream_outputsEosFrame() {
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).containsExactly(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void outputBitmap_thenQueueEndOfStream_outputsBitmapThenEos() {
    TimestampIterator singleFrameIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

    hardwareBufferFrameReader.outputBitmap(bitmap, singleFrameIterator, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).internalFrame).isInstanceOf(Bitmap.class);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(0);
    assertThat(receivedFrames.get(1)).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);
  }

  @Test
  public void queueEndOfStream_thenOutputBitmap_outputsEosThenBitmap() {
    // Use a single frame iterator to avoid capacity issues blocking immediate output
    TimestampIterator singleFrameIterator =
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000, /* frameRate= */ 1f);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);

    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.outputBitmap(bitmap, singleFrameIterator, /* indexOfItem= */ 0);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0)).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);
    assertThat(receivedFrames.get(1).internalFrame).isInstanceOf(Bitmap.class);
    assertThat(receivedFrames.get(1).presentationTimeUs).isEqualTo(0);
  }

  @Test
  public void queueEndOfStream_multipleTimes_outputsMultipleEosFrames() {
    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrames)
        .containsExactly(
            HardwareBufferFrame.END_OF_STREAM_FRAME, HardwareBufferFrame.END_OF_STREAM_FRAME)
        .inOrder();
  }

  @Test
  public void queueEndOfStream_afterBitmapsFillCapacity_isOutputAfterBitmapsReleased() {
    TimestampIterator threeFrames =
        new ConstantRateTimestampIterator(/* durationUs= */ 100_000, /* frameRate= */ 30f);
    Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    // Queue 3 bitmaps and EOS. Only the first 2 should be output immediately, filling the capacity.
    hardwareBufferFrameReader.outputBitmap(bitmap, threeFrames, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.queueEndOfStream();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(0);
    assertThat(receivedFrames.get(1).presentationTimeUs).isEqualTo(33_333);

    receivedFrames.remove(0).release();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(33_333);
    assertThat(receivedFrames.get(1).presentationTimeUs).isEqualTo(66_667);
    assertThat(threeFrames.hasNext()).isFalse();

    receivedFrames.remove(0).release();
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).presentationTimeUs).isEqualTo(66_667);
    assertThat(receivedFrames.get(1)).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }
}
