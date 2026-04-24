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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SimpleDecoder}. */
@RunWith(AndroidJUnit4.class)
public final class SimpleDecoderTest {

  @Test
  public void setCallback_notifiesOnOutputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
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
        Runnable::run // Synchronous execution
        );

    DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer(); // Dequeue to allow queuing
    decoder.queueInputBuffer(inputBuffer);

    assertThat(latch.await(1, SECONDS)).isTrue();
  }

  @Test
  public void setCallback_notifiesOnInputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
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
        Runnable::run // Synchronous execution
        );

    DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
    decoder.queueInputBuffer(inputBuffer); // This will decode and then release input buffer

    assertThat(latch.await(1, SECONDS)).isTrue();
  }

  @Test
  public void flush_doesNotNotifyInputBufferAvailable() throws Exception {
    FakeSimpleDecoder decoder = new FakeSimpleDecoder();
    AtomicBoolean notified = new AtomicBoolean(false);
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
        Runnable::run // Synchronous execution
        );

    DecoderInputBuffer unused2 = decoder.dequeueInputBuffer(); // Dequeue to have something to flush
    decoder.flush();

    assertThat(notified.get()).isFalse();
  }

  private static final class FakeSimpleDecoder
      extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    FakeSimpleDecoder() {
      super(new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[1]);
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

    @Nullable
    @Override
    protected DecoderException decode(
        DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
      if (inputBuffer.isEndOfStream()) {
        outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      }
      return null;
    }
  }
}
