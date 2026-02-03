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
package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Bitmap;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.SystemClock;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android tests for {@link HardwareBufferSampleConsumer}. This is an emulator test because creating
 * hardware buffers seems unsupported on robolectric.
 */
@RunWith(AndroidJUnit4.class)
public class HardwareBufferSampleConsumerAndroidTest {

  private static final long TEST_TIMEOUT_MS = 500L;
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
  }

  @After
  public void tearDown() {
    sampleConsumer.release();
    handlerThread.quit();
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void queueInputARGB888Bitmap_aboveApi31_outputsHardwareBufferFrame()
      throws InterruptedException {
    CountDownLatch framesReceivedLatch = new CountDownLatch(2);
    sampleConsumer =
        createSampleConsumer(
            /* onFrame= */ (frame) -> {
              receivedFrames.add(frame);
              framesReceivedLatch.countDown();
            });
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

    assertThat(framesReceivedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).hardwareBuffer).isNotNull();
    assertThat(receivedFrames.get(1).hardwareBuffer).isNotNull();
  }

  @SdkSuppress(maxSdkVersion = 30)
  @Test
  public void queueInputARGB888Bitmap_belowApi31_outputsBitmapFrame() throws InterruptedException {
    CountDownLatch framesReceivedLatch = new CountDownLatch(2);
    sampleConsumer =
        createSampleConsumer(
            /* onFrame= */ (frame) -> {
              receivedFrames.add(frame);
              framesReceivedLatch.countDown();
            });
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

    assertThat(framesReceivedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(receivedFrames).hasSize(2);
    assertThat(receivedFrames.get(0).hardwareBuffer).isNull();
    assertThat(receivedFrames.get(1).hardwareBuffer).isNull();
  }

  private HardwareBufferSampleConsumer createSampleConsumer(Consumer<HardwareBufferFrame> onFrame) {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();

    Looper looper = handlerThread.getLooper();
    HandlerWrapper handlerWrapper = SystemClock.DEFAULT.createHandler(looper, /* callback= */ null);

    return new HardwareBufferSampleConsumer(
        composition,
        /* sequenceIndex= */ 0,
        looper,
        handlerWrapper,
        onFrame,
        error -> errorRef.set(error));
  }
}
