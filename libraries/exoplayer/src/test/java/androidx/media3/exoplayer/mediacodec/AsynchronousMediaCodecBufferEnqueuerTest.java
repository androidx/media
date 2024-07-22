/*
 * Copyright (C) 2020 The Android Open Source Project
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

package androidx.media3.exoplayer.mediacodec;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.HandlerThread;
import androidx.media3.common.C;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.decoder.CryptoInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AsynchronousMediaCodecBufferEnqueuer}. */
@RunWith(AndroidJUnit4.class)
public class AsynchronousMediaCodecBufferEnqueuerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private MediaCodec codec;
  private AsynchronousMediaCodecBufferEnqueuer enqueuer;
  private TestHandlerThread handlerThread;
  @Mock private ConditionVariable mockConditionVariable;

  @Before
  public void setUp() throws IOException {
    codec = MediaCodec.createByCodecName("h264");
    codec.configure(new MediaFormat(), /* surface= */ null, /* crypto= */ null, /* flags= */ 0);
    codec.start();
    handlerThread = new TestHandlerThread("TestHandlerThread");
    enqueuer =
        new AsynchronousMediaCodecBufferEnqueuer(codec, handlerThread, mockConditionVariable);
  }

  @After
  public void tearDown() {
    enqueuer.shutdown();
    codec.stop();
    codec.release();
    assertThat(!handlerThread.hasStarted() || handlerThread.hasQuit()).isTrue();
  }

  @Test
  public void queueInputBuffer_queuesInputBufferOnMediaCodec() {
    enqueuer.start();
    int inputBufferIndex = codec.dequeueInputBuffer(0);
    assertThat(inputBufferIndex).isAtLeast(0);
    byte[] inputData = new byte[] {0, 1, 2, 3};
    codec.getInputBuffer(inputBufferIndex).put(inputData);

    enqueuer.queueInputBuffer(
        inputBufferIndex,
        /* offset= */ 0,
        /* size= */ 4,
        /* presentationTimeUs= */ 0,
        /* flags= */ 0);
    shadowOf(handlerThread.getLooper()).idle();

    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    assertThat(codec.dequeueOutputBuffer(bufferInfo, 0))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(codec.dequeueOutputBuffer(bufferInfo, 0)).isEqualTo(inputBufferIndex);
    ByteBuffer outputBuffer = codec.getOutputBuffer(inputBufferIndex);
    assertThat(outputBuffer.limit()).isEqualTo(4);
    byte[] outputData = new byte[4];
    outputBuffer.get(outputData);
    assertThat(outputData).isEqualTo(inputData);
  }

  @Test
  public void queueInputBuffer_withPendingCryptoExceptionSet_throwsCryptoException() {
    enqueuer.setPendingRuntimeException(
        new MediaCodec.CryptoException(/* errorCode= */ 0, /* detailMessage= */ null));
    enqueuer.start();

    assertThrows(
        MediaCodec.CryptoException.class,
        () ->
            enqueuer.queueInputBuffer(
                /* index= */ 0,
                /* offset= */ 0,
                /* size= */ 0,
                /* presentationTimeUs= */ 0,
                /* flags= */ 0));
  }

  @Test
  public void queueInputBuffer_withPendingIllegalStateExceptionSet_throwsIllegalStateException() {
    enqueuer.start();
    enqueuer.setPendingRuntimeException(new IllegalStateException());
    assertThrows(
        IllegalStateException.class,
        () ->
            enqueuer.queueInputBuffer(
                /* index= */ 0,
                /* offset= */ 0,
                /* size= */ 0,
                /* presentationTimeUs= */ 0,
                /* flags= */ 0));
  }

  @Test
  public void queueSecureInputBuffer_withPendingCryptoException_throwsCryptoException() {
    enqueuer.setPendingRuntimeException(
        new MediaCodec.CryptoException(/* errorCode= */ 0, /* detailMessage= */ null));
    enqueuer.start();
    CryptoInfo info = createCryptoInfo();

    assertThrows(
        MediaCodec.CryptoException.class,
        () ->
            enqueuer.queueSecureInputBuffer(
                /* index= */ 0,
                /* offset= */ 0,
                info,
                /* presentationTimeUs= */ 0,
                /* flags= */ 0));
  }

  @Test
  public void queueSecureInputBuffer_codecThrewIllegalStateException_throwsIllegalStateException() {
    enqueuer.setPendingRuntimeException(new IllegalStateException());
    enqueuer.start();
    CryptoInfo info = createCryptoInfo();

    assertThrows(
        IllegalStateException.class,
        () ->
            enqueuer.queueSecureInputBuffer(
                /* index= */ 0,
                /* offset= */ 0,
                /* info= */ info,
                /* presentationTimeUs= */ 0,
                /* flags= */ 0));
  }

  @Test
  public void setParameters_withPendingCryptoExceptionSet_throwsCryptoException() {
    enqueuer.setPendingRuntimeException(
        new MediaCodec.CryptoException(/* errorCode= */ 0, /* detailMessage= */ null));
    enqueuer.start();

    assertThrows(MediaCodec.CryptoException.class, () -> enqueuer.setParameters(new Bundle()));
  }

  @Test
  public void setParameters_withPendingIllegalStateExceptionSet_throwsIllegalStateException() {
    enqueuer.start();
    enqueuer.setPendingRuntimeException(new IllegalStateException());

    assertThrows(IllegalStateException.class, () -> enqueuer.setParameters(new Bundle()));
  }

  @Test
  public void flush_withoutStart_works() {
    enqueuer.flush();
  }

  @Test
  public void flush_onInterruptedException_throwsIllegalStateException()
      throws InterruptedException {
    doAnswer(
            invocation -> {
              throw new InterruptedException();
            })
        .doNothing()
        .when(mockConditionVariable)
        .block();

    enqueuer.start();

    assertThrows(IllegalStateException.class, () -> enqueuer.flush());
  }

  @Test
  public void flush_multipleTimes_works() {
    enqueuer.start();

    enqueuer.flush();
    enqueuer.flush();
  }

  @Test
  public void flush_withPendingError_doesNotResetError() {
    enqueuer.start();
    enqueuer.setPendingRuntimeException(
        new MediaCodec.CryptoException(/* errorCode= */ 0, /* detailMessage= */ null));

    enqueuer.flush();

    assertThrows(
        MediaCodec.CryptoException.class,
        () ->
            enqueuer.queueInputBuffer(
                /* index= */ 0,
                /* offset= */ 0,
                /* size= */ 0,
                /* presentationTimeUs= */ 0,
                /* flags= */ 0));
  }

  @Test
  public void shutdown_withoutStart_works() {
    enqueuer.shutdown();
  }

  @Test
  public void shutdown_multipleTimes_works() {
    enqueuer.start();

    enqueuer.shutdown();
    enqueuer.shutdown();
  }

  @Test
  public void shutdown_onInterruptedException_throwsIllegalStateException()
      throws InterruptedException {
    doAnswer(
            invocation -> {
              throw new InterruptedException();
            })
        .doNothing()
        .when(mockConditionVariable)
        .block();

    enqueuer.start();

    assertThrows(IllegalStateException.class, () -> enqueuer.shutdown());
  }

  @Test
  public void shutdown_withPendingError_doesNotThrow() {
    enqueuer.start();
    enqueuer.setPendingRuntimeException(
        new MediaCodec.CryptoException(/* errorCode= */ 0, /* detailMessage= */ null));

    // Shutting down with a pending error set should not throw .
    enqueuer.shutdown();
  }

  private static CryptoInfo createCryptoInfo() {
    CryptoInfo info = new CryptoInfo();
    int numSubSamples = 5;
    int[] numBytesOfClearData = new int[] {0, 1, 2, 3};
    int[] numBytesOfEncryptedData = new int[] {4, 5, 6, 7};
    byte[] key = new byte[] {0, 1, 2, 3};
    byte[] iv = new byte[] {4, 5, 6, 7};
    @C.CryptoMode int mode = C.CRYPTO_MODE_AES_CBC;
    int encryptedBlocks = 16;
    int clearBlocks = 8;
    info.set(
        numSubSamples,
        numBytesOfClearData,
        numBytesOfEncryptedData,
        key,
        iv,
        mode,
        encryptedBlocks,
        clearBlocks);
    return info;
  }

  private static class TestHandlerThread extends HandlerThread {
    private boolean started;
    private boolean quit;

    TestHandlerThread(String label) {
      super(label);
    }

    public boolean hasStarted() {
      return started;
    }

    public boolean hasQuit() {
      return quit;
    }

    @Override
    public synchronized void start() {
      super.start();
      started = true;
    }

    @Override
    public boolean quit() {
      quit = true;
      return super.quit();
    }
  }
}
