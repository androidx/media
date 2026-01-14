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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assume.assumeTrue;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.os.HandlerThread;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link HardwareBufferFrameReader}. This is an emulator test because producing frames
 * into a Surface seems unsupported on robolectric.
 */
@RunWith(AndroidJUnit4.class)
public class HardwareBufferFrameReaderTest {

  private static final long TEST_TIMEOUT_MS = 100;
  private static final Format TEST_FORMAT =
      new Format.Builder()
          .setWidth(10)
          .setHeight(20)
          .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
          .build();

  private Composition composition;
  private BlockingQueue<HardwareBufferFrame> receivedFrames;
  private HardwareBufferFrameReader hardwareBufferFrameReader;
  private HandlerThread handlerThread;
  private AtomicReference<Exception> hardwareBufferFrameReaderException;

  @Before
  public void setUp() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    composition = new Composition.Builder(sequence).build();
    handlerThread = new HandlerThread("HardwareBufferFrameReaderTest");
    handlerThread.start();
    hardwareBufferFrameReaderException = new AtomicReference<>();
    receivedFrames = new LinkedBlockingQueue<>();

    hardwareBufferFrameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            hardwareBufferFrame -> receivedFrames.add(hardwareBufferFrame),
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
            e -> hardwareBufferFrameReaderException.set(e),
            SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), null));
  }

  @After
  public void tearDown() {
    hardwareBufferFrameReader.release();
    handlerThread.quit();
  }

  @Test
  public void frameReader_queueFrameViaSurface_receivesFrame() throws Exception {
    hardwareBufferFrameReader.queueFrameViaSurface(
        /* presentationTimeUs= */ 0, /* indexOfItem= */ 0, TEST_FORMAT);
    produceFrameToFrameReaderSurface(/* presentationTimeUs= */ 0);

    HardwareBufferFrame receivedFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedFrame.presentationTimeUs).isEqualTo(0);
    assertThat(receivedFrame.format).isEqualTo(TEST_FORMAT);
    assertThat(receivedFrame.internalFrame).isNotNull();
    assertThat(receivedFrame.getMetadata()).isInstanceOf(CompositionFrameMetadata.class);
    CompositionFrameMetadata compositionFrameMetadata =
        (CompositionFrameMetadata) receivedFrame.getMetadata();
    assertThat(compositionFrameMetadata.composition).isEqualTo(composition);
    assertThat(compositionFrameMetadata.sequenceIndex).isEqualTo(0);
    assertThat(compositionFrameMetadata.itemIndex).isEqualTo(0);
  }

  @Test
  public void frameReader_releaseOutputFrame_closesTheHardwareBuffer() throws Exception {
    assumeTrue(SDK_INT >= 28);
    hardwareBufferFrameReader.queueFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0, TEST_FORMAT);
    produceFrameToFrameReaderSurface(/* presentationTimeUs= */ 1234);
    HardwareBufferFrame receivedFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    HardwareBuffer hardwareBuffer = checkNotNull(receivedFrame.hardwareBuffer);

    receivedFrame.release();
    handlerThread.join(TEST_TIMEOUT_MS);

    assertThat(hardwareBuffer.isClosed()).isTrue();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void frameReader_releaseOutputFrame_freesUpCapacity() throws Exception {
    hardwareBufferFrameReader.queueFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0, TEST_FORMAT);
    checkState(!hardwareBufferFrameReader.canAcceptFrameViaSurface());
    produceFrameToFrameReaderSurface(/* presentationTimeUs= */ 1234);
    HardwareBufferFrame receivedFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    receivedFrame.release();
    handlerThread.join(TEST_TIMEOUT_MS);

    assertThat(hardwareBufferFrameReader.canAcceptFrameViaSurface()).isTrue();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void produceSurfaceFrame_withPendingBitmap_outputsBitmap() throws Exception {
    Format expectedBitmapFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_RAW)
            .setWidth(1)
            .setHeight(1)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .setFrameRate(/* frameRate= */ 30)
            .build();
    hardwareBufferFrameReader.queueFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0, TEST_FORMAT);
    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 30f),
        /* indexOfItem= */ 1);

    produceFrameToFrameReaderSurface(/* presentationTimeUs= */ 1234);
    HardwareBufferFrame firstFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    HardwareBufferFrame secondFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(firstFrame).isNotNull();
    assertThat(secondFrame.internalFrame).isInstanceOf(Bitmap.class);
    assertThat(secondFrame.getMetadata()).isInstanceOf(CompositionFrameMetadata.class);
    assertThat(secondFrame.format).isEqualTo(expectedBitmapFormat);
    CompositionFrameMetadata bitmapFrameMetadata =
        (CompositionFrameMetadata) secondFrame.getMetadata();
    assertThat(bitmapFrameMetadata.composition).isEqualTo(composition);
    assertThat(bitmapFrameMetadata.sequenceIndex).isEqualTo(0);
    assertThat(bitmapFrameMetadata.itemIndex).isEqualTo(1);
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void frameReader_queueFrameViaSurfaceThenQueueEndOfStream_receivesFrameThenEos()
      throws Exception {
    long frameTimeUs = 1234;
    hardwareBufferFrameReader.queueFrameViaSurface(frameTimeUs, /* indexOfItem= */ 0, TEST_FORMAT);
    hardwareBufferFrameReader.queueEndOfStream();

    produceFrameToFrameReaderSurface(frameTimeUs);

    HardwareBufferFrame firstFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    HardwareBufferFrame secondFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    HardwareBufferFrame shouldBeNull = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(firstFrame).isNotNull();
    assertThat(firstFrame.presentationTimeUs).isEqualTo(frameTimeUs);
    assertThat(firstFrame.format).isEqualTo(TEST_FORMAT);
    assertThat(secondFrame).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);
    assertThat(shouldBeNull).isNull();
  }

  @Test
  public void frameReader_queueEndOfStreamThenQueueFrameViaSurface_receivesEosThenFrame()
      throws Exception {
    long frameTimeUs = 5678;
    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.queueFrameViaSurface(frameTimeUs, /* indexOfItem= */ 0, TEST_FORMAT);

    HardwareBufferFrame receivedEos = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(receivedEos).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);

    produceFrameToFrameReaderSurface(frameTimeUs);

    HardwareBufferFrame firstFrame = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    HardwareBufferFrame shouldBeNull = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(hardwareBufferFrameReaderException.get()).isNull();
    assertThat(firstFrame).isNotNull();
    assertThat(firstFrame.presentationTimeUs).isEqualTo(frameTimeUs);
    assertThat(firstFrame.format).isEqualTo(TEST_FORMAT);
    assertThat(shouldBeNull).isNull();
  }

  @Test
  public void frameReader_queueMultipleSurfaceFramesAndEos_receivesInOrder() throws Exception {
    long frameTimeUs1 = 1000;
    long frameTimeUs2 = 2000;
    long frameTimeUs3 = 3000;
    Format format1 =
        new Format.Builder().setWidth(10).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();
    Format format2 =
        new Format.Builder().setWidth(20).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();
    Format format3 =
        new Format.Builder().setWidth(30).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();

    hardwareBufferFrameReader.queueFrameViaSurface(frameTimeUs1, /* indexOfItem= */ 0, format1);
    hardwareBufferFrameReader.queueFrameViaSurface(frameTimeUs2, /* indexOfItem= */ 0, format2);
    hardwareBufferFrameReader.queueEndOfStream();
    hardwareBufferFrameReader.queueFrameViaSurface(frameTimeUs3, /* indexOfItem= */ 0, format3);

    produceFrameToFrameReaderSurface(frameTimeUs1);
    HardwareBufferFrame recFrame1 = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recFrame1).isNotNull();
    assertThat(recFrame1.presentationTimeUs).isEqualTo(frameTimeUs1);
    assertThat(recFrame1.format).isEqualTo(format1);
    recFrame1.release();

    produceFrameToFrameReaderSurface(frameTimeUs2);
    HardwareBufferFrame recFrame2 = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recFrame2).isNotNull();
    assertThat(recFrame2.presentationTimeUs).isEqualTo(frameTimeUs2);
    assertThat(recFrame2.format).isEqualTo(format2);
    recFrame2.release();

    HardwareBufferFrame recEos = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recEos).isEqualTo(HardwareBufferFrame.END_OF_STREAM_FRAME);

    produceFrameToFrameReaderSurface(frameTimeUs3);
    HardwareBufferFrame recFrame3 = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recFrame3).isNotNull();
    assertThat(recFrame3.presentationTimeUs).isEqualTo(frameTimeUs3);
    assertThat(recFrame3.format).isEqualTo(format3);
    recFrame3.release();

    assertThat(receivedFrames).isEmpty();
    assertThat(hardwareBufferFrameReaderException.get()).isNull();
  }

  @Test
  public void queueFrameViaSurface_nullColorInfo_setsDefaultColorInfo() throws Exception {
    long frameTimeUs1 = 1000;
    Format nullColorInfoFormat = new Format.Builder().setWidth(10).build();
    Format expectedFormat =
        new Format.Builder().setWidth(10).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();

    hardwareBufferFrameReader.queueFrameViaSurface(
        frameTimeUs1, /* indexOfItem= */ 0, nullColorInfoFormat);
    produceFrameToFrameReaderSurface(frameTimeUs1);

    HardwareBufferFrame recFrame1 = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recFrame1.format).isEqualTo(expectedFormat);
  }

  @Test
  public void queueFrameViaSurface_unsetColorInfo_setsDefaultColorInfo() throws Exception {
    long frameTimeUs1 = 1000;
    Format unsetColorSpaceFormat =
        new Format.Builder()
            .setWidth(10)
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.INDEX_UNSET)
                    .setColorRange(C.INDEX_UNSET)
                    .setColorTransfer(C.INDEX_UNSET)
                    .build())
            .build();
    Format expectedFormat =
        new Format.Builder().setWidth(10).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();

    hardwareBufferFrameReader.queueFrameViaSurface(
        frameTimeUs1, /* indexOfItem= */ 0, unsetColorSpaceFormat);
    produceFrameToFrameReaderSurface(frameTimeUs1);

    HardwareBufferFrame recFrame1 = receivedFrames.poll(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(recFrame1.format).isEqualTo(expectedFormat);
  }

  private void produceFrameToFrameReaderSurface(long presentationTimeUs) {
    try (ImageWriter imageWriter =
        ImageWriter.newInstance(hardwareBufferFrameReader.getSurface(), /* maxImages= */ 1)) {
      Image image = imageWriter.dequeueInputImage();
      image.setTimestamp(presentationTimeUs * 1000);
      imageWriter.queueInputImage(image);
    }
  }
}
