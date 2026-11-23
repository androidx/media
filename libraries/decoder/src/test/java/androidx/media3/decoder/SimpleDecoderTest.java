/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.decoder;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SimpleDecoder}. */
@RunWith(AndroidJUnit4.class)
public final class SimpleDecoderTest {

  @Test
  public void setCallback_notifiesOnOutputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean outputAvailable;
    try {
      CountDownLatch latch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              // Do nothing.
            }

            @Override
            public void onOutputBufferAvailable() {
              latch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());

      decoder.queueInputBuffer(inputBuffer);
      outputAvailable = latch.await(1, SECONDS);
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
  }

  @Test
  public void setCallback_notifiesOnInputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean inputAvailable;
    try {
      CountDownLatch latch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              latch.countDown();
            }

            @Override
            public void onOutputBufferAvailable() {
              // Do nothing.
            }
          },
          Runnable::run);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());

      decoder.queueInputBuffer(inputBuffer);
      inputAvailable = latch.await(1, SECONDS);
    } finally {
      decoder.release();
    }

    assertThat(inputAvailable).isTrue();
  }

  @Test
  public void flush_doesNotNotifyInputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    AtomicBoolean notified = new AtomicBoolean(false);
    try {
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              notified.set(true);
            }

            @Override
            public void onOutputBufferAvailable() {
              // Do nothing.
            }
          },
          Runnable::run);
      DecoderInputBuffer unused = decoder.dequeueInputBuffer();

      decoder.flush();
    } finally {
      decoder.release();
    }

    assertThat(notified.get()).isFalse();
  }

  @Test
  public void setCallback_whenOutputBufferShouldBeSkipped_doesNotNotifyOnOutputBufferAvailable()
      throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean inputAvailable;
    AtomicBoolean outputNotified = new AtomicBoolean(false);
    try {
      decoder.setOutputStartTimeUs(100_000L);
      CountDownLatch inputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              inputAvailableLatch.countDown();
            }

            @Override
            public void onOutputBufferAvailable() {
              outputNotified.set(true);
            }
          },
          Runnable::run);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      inputBuffer.timeUs = 50_000L;

      decoder.queueInputBuffer(inputBuffer);
      inputAvailable = inputAvailableLatch.await(1, SECONDS);
    } finally {
      decoder.release();
    }

    assertThat(inputAvailable).isTrue();
    assertThat(outputNotified.get()).isFalse();
  }

  @Test
  public void setCallback_whenDecodeFails_notifiesOnOutputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean outputAvailable;
    try {
      decoder.setFailDecode(true);
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());

      decoder.queueInputBuffer(inputBuffer);
      outputAvailable = outputAvailableLatch.await(1, SECONDS);
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
  }

  @Test
  public void setCallback_withNull_clearsCallback() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    AtomicBoolean notified = new AtomicBoolean(false);
    boolean decodeCalled;
    @Nullable SimpleDecoderOutputBuffer outputBuffer = null;
    try {
      CountDownLatch decodeCalledLatch = new CountDownLatch(1);
      decoder.setDecodeLatch(decodeCalledLatch);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              notified.set(true);
            }

            @Override
            public void onOutputBufferAvailable() {
              notified.set(true);
            }
          },
          Runnable::run);
      decoder.setCallback(/* callback= */ null, /* executor= */ null);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());

      decoder.queueInputBuffer(inputBuffer);
      decodeCalled = decodeCalledLatch.await(1, SECONDS);
    } finally {
      decoder.release();
    }
    outputBuffer = decoder.dequeueOutputBuffer();
    if (outputBuffer != null) {
      outputBuffer.release();
    }

    assertThat(decodeCalled).isTrue();
    assertThat(outputBuffer).isNotNull();
    assertThat(notified.get()).isFalse();
  }

  @Test
  public void setCallback_withNonNullCallbackAndNullExecutor_throwsIllegalArgumentException() {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    try {
      Decoder.Callback callback =
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {}
          };

      assertThrows(
          IllegalArgumentException.class,
          () -> decoder.setCallback(callback, /* executor= */ null));
    } finally {
      decoder.release();
    }
  }

  @Test
  public void
      setOutputStartTimeUs_whileInputBufferDequeuedWithoutFlush_updatesStartTimeAndSkipsEarlierBuffers()
          throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder(/* numBuffers= */ 2);
    boolean outputAvailable;
    long outputTimeUs;
    int skippedCount;
    try {
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer firstInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      firstInputBuffer.timeUs = 10_000L;

      decoder.setOutputStartTimeUs(20_000L);
      decoder.queueInputBuffer(firstInputBuffer);
      DecoderInputBuffer secondInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      secondInputBuffer.timeUs = 20_000L;
      decoder.queueInputBuffer(secondInputBuffer);
      outputAvailable = outputAvailableLatch.await(1, SECONDS);
      SimpleDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        outputTimeUs = outputBuffer.timeUs;
        skippedCount = outputBuffer.skippedOutputBufferCount;
      } finally {
        outputBuffer.release();
      }
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
    assertThat(outputTimeUs).isEqualTo(20_000L);
    assertThat(skippedCount).isEqualTo(1);
  }

  @Test
  public void setOutputStartTimeUs_whileDecodeInFlight_skipsInFlightBufferBelowNewStartTime()
      throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder(/* numBuffers= */ 2);
    CountDownLatch proceedDecodeLatch = new CountDownLatch(1);
    boolean decodeStarted;
    boolean outputAvailable;
    long outputTimeUs;
    int skippedCount;
    try {
      CountDownLatch decodeStartedLatch = new CountDownLatch(1);
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setDecodeLatch(decodeStartedLatch);
      decoder.setProceedDecodeLatch(proceedDecodeLatch);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer firstInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      firstInputBuffer.timeUs = 10_000L;
      decoder.queueInputBuffer(firstInputBuffer);
      decodeStarted = decodeStartedLatch.await(1, SECONDS);
      DecoderInputBuffer secondInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      secondInputBuffer.timeUs = 20_000L;

      decoder.setOutputStartTimeUs(20_000L);
      decoder.queueInputBuffer(secondInputBuffer);
      proceedDecodeLatch.countDown();
      outputAvailable = outputAvailableLatch.await(1, SECONDS);
      SimpleDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        outputTimeUs = outputBuffer.timeUs;
        skippedCount = outputBuffer.skippedOutputBufferCount;
      } finally {
        outputBuffer.release();
      }
    } finally {
      proceedDecodeLatch.countDown();
      decoder.release();
    }

    assertThat(decodeStarted).isTrue();
    assertThat(outputAvailable).isTrue();
    assertThat(outputTimeUs).isEqualTo(20_000L);
    assertThat(skippedCount).isEqualTo(1);
  }

  @Test
  public void
      getAndResetSkippedOutputBufferCount_whenSkippedCountAttachedToQueuedOutputBuffer_returnsAndResetsCount()
          throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder(/* numBuffers= */ 2);
    boolean outputAvailable;
    int harvestedSkippedCount;
    int outputBufferSkippedCount;
    try {
      decoder.setOutputStartTimeUs(20_000L);
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer firstInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      firstInputBuffer.timeUs = 10_000L;
      decoder.queueInputBuffer(firstInputBuffer);
      DecoderInputBuffer secondInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      secondInputBuffer.timeUs = 20_000L;
      decoder.queueInputBuffer(secondInputBuffer);
      outputAvailable = outputAvailableLatch.await(1, SECONDS);

      harvestedSkippedCount = decoder.getAndResetSkippedOutputBufferCount();
      SimpleDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        outputBufferSkippedCount = outputBuffer.skippedOutputBufferCount;
      } finally {
        outputBuffer.release();
      }
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
    assertThat(harvestedSkippedCount).isEqualTo(1);
    assertThat(outputBufferSkippedCount).isEqualTo(0);
  }

  @Test
  public void getAndResetSkippedOutputBufferCount_beforeOutputBufferDequeued_returnsAndResetsCount()
      throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean inputAvailable;
    int skippedCount;
    int subsequentSkippedCount;
    try {
      decoder.setOutputStartTimeUs(20_000L);
      CountDownLatch inputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              inputAvailableLatch.countDown();
            }

            @Override
            public void onOutputBufferAvailable() {}
          },
          Runnable::run);
      DecoderInputBuffer firstInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      firstInputBuffer.timeUs = 10_000L;

      decoder.queueInputBuffer(firstInputBuffer);
      inputAvailable = inputAvailableLatch.await(1, SECONDS);
      skippedCount = decoder.getAndResetSkippedOutputBufferCount();
      subsequentSkippedCount = decoder.getAndResetSkippedOutputBufferCount();
    } finally {
      decoder.release();
    }

    assertThat(inputAvailable).isTrue();
    assertThat(skippedCount).isEqualTo(1);
    assertThat(subsequentSkippedCount).isEqualTo(0);
  }

  @Test
  public void clear_onVideoDecoderOutputBuffer_resetsFormatToNull() {
    VideoDecoderOutputBuffer outputBuffer = new VideoDecoderOutputBuffer(unused -> {});
    outputBuffer.format = new Format.Builder().setWidth(1920).setHeight(1080).build();

    outputBuffer.clear();

    assertThat(outputBuffer.format).isNull();
  }

  @Test
  public void
      dequeueOutputBuffer_whenDecodeClearsShouldBeSkippedBeforeOutputStartTime_doesNotSkipBuffer()
          throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    boolean outputAvailable;
    long outputTimeUs;
    int skippedCount;
    try {
      decoder.setClearShouldBeSkipped(true);
      decoder.setOutputStartTimeUs(100_000L);
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer inputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      inputBuffer.timeUs = 0L;

      decoder.queueInputBuffer(inputBuffer);
      outputAvailable = outputAvailableLatch.await(1, SECONDS);
      SimpleDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        outputTimeUs = outputBuffer.timeUs;
        skippedCount = outputBuffer.skippedOutputBufferCount;
      } finally {
        outputBuffer.release();
      }
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
    assertThat(outputTimeUs).isEqualTo(0L);
    assertThat(skippedCount).isEqualTo(0);
  }

  @Test
  public void
      dequeueOutputBuffer_withVideoDecoderOutputBufferAndLastSampleBeforeStartTime_doesNotSkipLastSample()
          throws Exception {
    FakeVideoSimpleDecoder decoder = new FakeVideoSimpleDecoder(/* numBuffers= */ 2);
    boolean outputAvailable;
    long outputTimeUs;
    boolean isLastSample;
    int skippedCount;
    try {
      decoder.setOutputStartTimeUs(100_000L);
      CountDownLatch outputAvailableLatch = new CountDownLatch(1);
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {}

            @Override
            public void onOutputBufferAvailable() {
              outputAvailableLatch.countDown();
            }
          },
          Runnable::run);
      DecoderInputBuffer firstInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      firstInputBuffer.timeUs = 10_000L;
      decoder.queueInputBuffer(firstInputBuffer);
      DecoderInputBuffer secondInputBuffer = checkNotNull(decoder.dequeueInputBuffer());
      secondInputBuffer.timeUs = 20_000L;
      secondInputBuffer.addFlag(C.BUFFER_FLAG_LAST_SAMPLE);

      decoder.queueInputBuffer(secondInputBuffer);
      outputAvailable = outputAvailableLatch.await(1, SECONDS);
      VideoDecoderOutputBuffer outputBuffer = checkNotNull(decoder.dequeueOutputBuffer());
      try {
        outputTimeUs = outputBuffer.timeUs;
        isLastSample = outputBuffer.isLastSample();
        skippedCount = outputBuffer.skippedOutputBufferCount;
      } finally {
        outputBuffer.release();
      }
    } finally {
      decoder.release();
    }

    assertThat(outputAvailable).isTrue();
    assertThat(outputTimeUs).isEqualTo(20_000L);
    assertThat(isLastSample).isTrue();
    assertThat(skippedCount).isEqualTo(1);
  }

  private static final class FakeSimpleDecoder
      extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private volatile boolean failDecode;
    private volatile boolean clearShouldBeSkipped;
    private volatile @Nullable CountDownLatch decodeLatch;
    private volatile @Nullable CountDownLatch proceedDecodeLatch;

    private FakeSimpleDecoder() {
      this(/* numBuffers= */ 1);
    }

    private FakeSimpleDecoder(int numBuffers) {
      super(new DecoderInputBuffer[numBuffers], new SimpleDecoderOutputBuffer[numBuffers]);
    }

    @Override
    public String getName() {
      return "FakeSimpleDecoder";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
      return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
      return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
      return new DecoderException("Unexpected error", error);
    }

    @Override
    protected @Nullable DecoderException decode(
        DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
      CountDownLatch latch = this.decodeLatch;
      if (latch != null) {
        latch.countDown();
      }
      CountDownLatch proceedLatch = this.proceedDecodeLatch;
      if (proceedLatch != null) {
        try {
          proceedLatch.await(1, SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (failDecode) {
        return new DecoderException("Simulated decode failure");
      }
      if (clearShouldBeSkipped) {
        outputBuffer.shouldBeSkipped = false;
      }
      if (inputBuffer.isEndOfStream()) {
        outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      }
      return null;
    }

    private void setFailDecode(boolean failDecode) {
      this.failDecode = failDecode;
    }

    private void setClearShouldBeSkipped(boolean clearShouldBeSkipped) {
      this.clearShouldBeSkipped = clearShouldBeSkipped;
    }

    private void setDecodeLatch(CountDownLatch decodeLatch) {
      this.decodeLatch = decodeLatch;
    }

    private void setProceedDecodeLatch(CountDownLatch proceedDecodeLatch) {
      this.proceedDecodeLatch = proceedDecodeLatch;
    }
  }

  private static final class FakeVideoSimpleDecoder
      extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private FakeVideoSimpleDecoder(int numBuffers) {
      super(new DecoderInputBuffer[numBuffers], new VideoDecoderOutputBuffer[numBuffers]);
    }

    @Override
    public String getName() {
      return "FakeVideoSimpleDecoder";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
      return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
      return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
      return new DecoderException("Unexpected error", error);
    }

    @Override
    protected @Nullable DecoderException decode(
        DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
      return null;
    }
  }
}
