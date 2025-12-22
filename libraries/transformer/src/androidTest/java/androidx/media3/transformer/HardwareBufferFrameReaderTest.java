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

import static android.graphics.PixelFormat.RGBA_8888;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.HandlerThread;
import android.view.Surface;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
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

  private Composition composition;
  private HardwareBufferFrame receivedFrame;
  private HardwareBufferFrameReader hardwareBufferFrameReader;
  private ConditionVariable frameReceivedSignal;
  private HandlerThread handlerThread;

  @Before
  public void setUp() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri)).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    composition = new Composition.Builder(sequence).build();
    handlerThread = new HandlerThread("HardwareBufferFrameReaderTest");
    handlerThread.start();
    frameReceivedSignal = new ConditionVariable();

    hardwareBufferFrameReader =
        new HardwareBufferFrameReader(
            composition,
            /* sequenceIndex= */ 0,
            hardwareBufferFrame -> {
              receivedFrame = hardwareBufferFrame;
              frameReceivedSignal.open();
            },
            handlerThread.getLooper(),
            /* defaultSurfacePixelFormat= */ RGBA_8888);
  }

  @After
  public void tearDown() {
    hardwareBufferFrameReader.release();
    handlerThread.quit();
  }

  @Test
  public void frameReader_willOutputFrameViaSurface_receivesFrame() throws Exception {
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0);
    produceFrameToFrameReaderSurface();
    frameReceivedSignal.block();

    assertThat(receivedFrame.presentationTimeUs).isEqualTo(1234);
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
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0);
    produceFrameToFrameReaderSurface();
    frameReceivedSignal.block();
    HardwareBuffer hardwareBuffer = checkNotNull(receivedFrame.hardwareBuffer);

    receivedFrame.release();
    handlerThread.join(TEST_TIMEOUT_MS);

    assertThat(hardwareBuffer.isClosed()).isTrue();
  }

  @Test
  public void frameReader_releaseOutputFrame_freesUpCapacity() throws Exception {
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 1234, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 0, /* indexOfItem= */ 0);
    checkState(!hardwareBufferFrameReader.canAcceptFrame());
    produceFrameToFrameReaderSurface();
    frameReceivedSignal.block();

    receivedFrame.release();
    handlerThread.join(TEST_TIMEOUT_MS);

    assertThat(hardwareBufferFrameReader.canAcceptFrame()).isTrue();
  }

  @Test
  public void canAcceptFrame_returnsTrue() {
    assertThat(hardwareBufferFrameReader.canAcceptFrame()).isTrue();
  }

  @Test
  public void canAcceptFrame_afterOneFrame_returnsTrue() {
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 0, /* indexOfItem= */ 0);

    assertThat(hardwareBufferFrameReader.canAcceptFrame()).isTrue();
  }

  @Test
  public void canAcceptFrame_afterTwoFrames_returnsFalse() {
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 0, /* indexOfItem= */ 0);
    hardwareBufferFrameReader.willOutputFrameViaSurface(
        /* presentationTimeUs= */ 1, /* indexOfItem= */ 0);

    assertThat(hardwareBufferFrameReader.canAcceptFrame()).isFalse();
  }

  private void produceFrameToFrameReaderSurface() {
    Surface surface = hardwareBufferFrameReader.getSurface();
    Canvas unused = surface.lockCanvas(new Rect());
    surface.unlockCanvasAndPost(unused);
  }
}
