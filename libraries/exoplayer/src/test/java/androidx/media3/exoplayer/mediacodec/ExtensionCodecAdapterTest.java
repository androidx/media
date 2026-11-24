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
package androidx.media3.exoplayer.mediacodec;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ExtensionCodecAdapter}. */
@RunWith(AndroidJUnit4.class)
public final class ExtensionCodecAdapterTest {

  private static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_AV1)
          .setWidth(1920)
          .setHeight(1080)
          .build();

  private static final Format AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_OPUS)
          .setSampleRate(48000)
          .setChannelCount(2)
          .build();

  private PassthroughDecoder decoder;
  private TestExtensionCodecAdapter adapter;
  private MediaCodec.BufferInfo bufferInfo;

  @Before
  public void setUp() {
    decoder = new PassthroughDecoder();
    adapter = new TestExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    bufferInfo = new MediaCodec.BufferInfo();
  }

  @After
  public void tearDown() {
    adapter.release();
  }

  @Test
  public void dequeueOutputBufferIndex_beforeOutputBufferAvailable_returnsTryAgainLater() {
    int index = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(index).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueOutputBufferIndex_onFirstOutputBuffer_returnsOutputFormatChangedThenBuffer() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    int firstResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat outputFormat = adapter.getOutputFormat();
    int secondResult = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(firstResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputFormat.getString(MediaFormat.KEY_MIME)).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(secondResult).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1000L);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenOutputBufferAlreadyDequeued_returnsTryAgainLaterUntilReleased() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

    int repeatedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    int afterReleaseResult = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(repeatedResult).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
    assertThat(afterReleaseResult).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void
      getAndResetSkippedOutputBufferCount_withSubsequentSkippedBufferInSimpleDecoder_harvestsImmediately()
          throws Exception {
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>
        multiBufferSimpleDecoder =
            new SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
                new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[2]) {
              @Override
              public String getName() {
                return "MultiBufferSimpleDecoder";
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
                  DecoderInputBuffer inputBuffer,
                  SimpleDecoderOutputBuffer outputBuffer,
                  boolean reset) {
                if (!outputBuffer.shouldBeSkipped) {
                  outputBuffer.init(inputBuffer.timeUs, /* size= */ 4);
                  outputBuffer.data.putInt(0);
                  outputBuffer.data.flip();
                }
                return null;
              }
            };
    boolean listenerRegistered;
    boolean firstOutputReady;
    boolean secondSampleProcessed;
    int formatChangedOutputIndex;
    int firstOutputIndex;
    int skippedWithFirstBuffer;
    int skippedWhileFirstBufferStillHeld;
    int skippedAfterFirstBufferReleased;
    try {
      multiBufferSimpleDecoder.setOutputStartTimeUs(1000L);
      CountDownLatch firstOutputAvailableLatch = new CountDownLatch(1);
      CountDownLatch secondInputReleasedLatch = new CountDownLatch(2);
      TestExtensionCodecAdapter simpleAdapter =
          new TestExtensionCodecAdapter(AUDIO_FORMAT, multiBufferSimpleDecoder);
      try {
        listenerRegistered =
            simpleAdapter.registerOnBufferAvailableListener(
                new MediaCodecAdapter.OnBufferAvailableListener() {
                  @Override
                  public void onInputBufferAvailable() {
                    secondInputReleasedLatch.countDown();
                  }

                  @Override
                  public void onOutputBufferAvailable() {
                    firstOutputAvailableLatch.countDown();
                  }
                });
        int unusedFirstInputIndex = simpleAdapter.dequeueInputBufferIndex();
        simpleAdapter.queueInputBuffer(
            /* index= */ 0,
            /* offset= */ 0,
            /* size= */ 4,
            /* presentationTimeUs= */ 1000L,
            /* flags= */ 0);
        firstOutputReady = firstOutputAvailableLatch.await(1, SECONDS);
        formatChangedOutputIndex = simpleAdapter.dequeueOutputBufferIndex(bufferInfo);
        int unusedSecondInputIndex = simpleAdapter.dequeueInputBufferIndex();
        simpleAdapter.queueInputBuffer(
            /* index= */ 0,
            /* offset= */ 0,
            /* size= */ 4,
            /* presentationTimeUs= */ 500L,
            /* flags= */ 0);
        secondSampleProcessed = secondInputReleasedLatch.await(1, SECONDS);

        firstOutputIndex = simpleAdapter.dequeueOutputBufferIndex(bufferInfo);
        skippedWithFirstBuffer = simpleAdapter.getAndResetSkippedOutputBufferCount();
        skippedWhileFirstBufferStillHeld = simpleAdapter.getAndResetSkippedOutputBufferCount();
        simpleAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
        skippedAfterFirstBufferReleased = simpleAdapter.getAndResetSkippedOutputBufferCount();
      } finally {
        simpleAdapter.release();
      }
    } finally {
      multiBufferSimpleDecoder.release();
    }

    assertThat(listenerRegistered).isTrue();
    assertThat(firstOutputReady).isTrue();
    assertThat(secondSampleProcessed).isTrue();
    assertThat(formatChangedOutputIndex).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(firstOutputIndex).isEqualTo(0);
    assertThat(skippedWithFirstBuffer).isEqualTo(1);
    assertThat(skippedWhileFirstBufferStillHeld).isEqualTo(0);
    assertThat(skippedAfterFirstBufferReleased).isEqualTo(0);
  }

  @Test
  public void getOutputFormat_forVideoAdapter_containsWidthAndHeight() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);

    MediaFormat outputFormat = videoAdapter.getOutputFormat();

    assertThat(outputFormat.getString(MediaFormat.KEY_MIME)).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(1920);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(1080);
  }

  @Test
  public void getOutputFormat_forAudioFormat_containsSampleRateChannelCountAndPcmEncoding() {
    Format audioFormatWithPcmEncoding =
        AUDIO_FORMAT.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build();
    TestExtensionCodecAdapter audioAdapter =
        new TestExtensionCodecAdapter(audioFormatWithPcmEncoding, decoder);

    MediaFormat outputFormat = audioAdapter.getOutputFormat();

    assertThat(outputFormat.getString(MediaFormat.KEY_MIME)).isEqualTo(MimeTypes.AUDIO_OPUS);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)).isEqualTo(48000);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).isEqualTo(2);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING))
        .isEqualTo(C.ENCODING_PCM_FLOAT);
  }

  @Test
  public void dequeueInputBufferIndex_whenBufferAvailable_returnsIndexZeroAndClearsOnRepeat() {
    int index = adapter.dequeueInputBufferIndex();
    ByteBuffer inputBuffer = adapter.getInputBuffer(/* index= */ 0);
    inputBuffer.put((byte) 0x42);

    int secondIndex = adapter.dequeueInputBufferIndex();

    assertThat(index).isEqualTo(0);
    assertThat(secondIndex).isEqualTo(0);
    assertThat(inputBuffer.position()).isEqualTo(0);
  }

  @Test
  public void dequeueInputBufferIndex_whenNoBuffersAvailable_returnsTryAgainLater() {
    decoder.drainAvailableInputBuffers();

    int index = adapter.dequeueInputBufferIndex();

    assertThat(index).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void queueInputBuffer_forwardsBufferAndKeyFrameFlagToDecoder() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    decoder.setNextOutputBufferIsKeyFrame(true);

    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 12,
        /* size= */ 100,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ MediaCodec.BUFFER_FLAG_KEY_FRAME);
    int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputIndex).isEqualTo(0);
    assertThat(decoder.lastQueuedInputOffset).isEqualTo(12);
    assertThat(decoder.lastQueuedInputSize).isEqualTo(100);
    assertThat(decoder.lastQueuedInputWasKeyFrame).isTrue();
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1000L);
    assertThat(bufferInfo.flags).isEqualTo(MediaCodec.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void getDequeuedInputBuffer_returnsDequeuedBufferAndReflectsDirectMutations() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    ByteBuffer replacementBuffer = ByteBuffer.allocateDirect(4096);

    DecoderInputBuffer inputBuffer = adapter.getDequeuedInputBuffer();
    inputBuffer.data = replacementBuffer;

    assertThat(adapter.getInputBuffer(/* index= */ 0)).isSameInstanceAs(replacementBuffer);
  }

  @Test
  public void queueInputBuffer_withEosFlag_setsEosFlagOnOutputBufferInfo() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();

    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    ByteBuffer eosOutputBuffer = adapter.getOutputBuffer(/* index= */ 0);
    adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    int subsequentOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(outputIndex).isEqualTo(0);
    assertThat(eosOutputBuffer).isNull();
    assertThat((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)).isNotEqualTo(0);
    assertThat(decoder.availableOutputBuffers).hasSize(4);
    assertThat(subsequentOutputIndex).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void
      queueInputBuffer_withFirstSampleFlagOnDequeuedBuffer_forwardsFirstSampleFlagToDecoder() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.getDequeuedInputBuffer().addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);

    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 4,
        /* presentationTimeUs= */ 0L,
        /* flags= */ 0);

    assertThat(decoder.lastQueuedInputWasFirstSample).isTrue();
  }

  @Test
  public void dequeueOutputBufferIndex_withSkippedBuffer_skipsAndReturnsNextBuffer() {
    int unusedFirstInputIndex = adapter.dequeueInputBufferIndex();
    decoder.setNextOutputBufferShouldBeSkipped(true);
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedSecondInputIndex = adapter.dequeueInputBufferIndex();
    decoder.setNextSkippedOutputBufferCount(2);
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ 0);

    int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int firstSkippedCount = adapter.getAndResetSkippedOutputBufferCount();
    int secondSkippedCount = adapter.getAndResetSkippedOutputBufferCount();

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputIndex).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(2000L);
    assertThat(firstSkippedCount).isEqualTo(3);
    assertThat(secondSkippedCount).isEqualTo(0);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenEndOfStreamBufferMarkedShouldBeSkipped_doesNotSkipEndOfStream() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    decoder.setNextOutputBufferShouldBeSkipped(true);
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ MediaCodec.BUFFER_FLAG_END_OF_STREAM);

    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int skippedCount = adapter.getAndResetSkippedOutputBufferCount();

    assertThat(outputIndex).isEqualTo(0);
    assertThat(bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM).isNotEqualTo(0);
    assertThat(skippedCount).isEqualTo(0);
  }

  @Test
  public void
      dequeueOutputBufferIndex_withVideoAdapterWhenLastSampleMarkedShouldBeSkipped_skipsBuffer() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    int unusedInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().addFlag(C.BUFFER_FLAG_LAST_SAMPLE);
    decoder.setNextOutputBufferShouldBeSkipped(true);
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    int outputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    int skippedCount = videoAdapter.getAndResetSkippedOutputBufferCount();

    assertThat(outputIndex).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
    assertThat(skippedCount).isEqualTo(1);
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenSetOutputStartTimeUsCalledAfterDecode_skipsBuffersBeforeStartTime() {
    int unusedFirstInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedSecondInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ 0);

    adapter.setOutputStartTimeUs(1500L);
    int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int skippedCount = adapter.getAndResetSkippedOutputBufferCount();

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputIndex).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(2000L);
    assertThat(skippedCount).isEqualTo(1);
  }

  @Test
  public void releaseOutputBuffer_withRenderTrue_rendersAndReleasesBuffer() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 3000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int availableBeforeRelease = decoder.availableOutputBuffers.size();

    adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true);

    assertThat(availableBeforeRelease).isEqualTo(3);
    assertThat(adapter.rendered).isTrue();
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void releaseOutputBuffer_withRenderFalse_releasesWithoutRendering() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 3000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int availableBeforeRelease = decoder.availableOutputBuffers.size();

    adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);

    assertThat(availableBeforeRelease).isEqualTo(3);
    assertThat(adapter.rendered).isFalse();
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void flush_resetsBuffersReleasesDequeuedOutputAndClearsSkippedCount() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    decoder.setNextSkippedOutputBufferCount(2);
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedSecondInputIndex = adapter.dequeueInputBufferIndex();

    adapter.flush();
    int skippedCountAfterFlush = adapter.getAndResetSkippedOutputBufferCount();
    int inputIndexAfterFlush = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 4000L,
        /* flags= */ 0);
    int outputIndexAfterFlush = adapter.dequeueOutputBufferIndex(bufferInfo);
    long presentationTimeAfterFlushUs = bufferInfo.presentationTimeUs;
    adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);

    assertThat(decoder.isFlushed).isTrue();
    assertThat(skippedCountAfterFlush).isEqualTo(0);
    assertThat(inputIndexAfterFlush).isEqualTo(0);
    // After flush, INFO_OUTPUT_FORMAT_CHANGED is not re-emitted.
    assertThat(outputIndexAfterFlush).isEqualTo(0);
    assertThat(presentationTimeAfterFlushUs).isEqualTo(4000L);
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void flush_withDequeuedOutputBuffer_flushesDecoderBeforeReleasingOutputBuffer() {
    List<String> callOrder = new ArrayList<>();
    PassthroughDecoder orderTrackingDecoder =
        new PassthroughDecoder() {
          @Override
          public void flush() {
            callOrder.add("decoder.flush");
            super.flush();
          }

          @Override
          protected void releaseOutputBuffer(VideoDecoderOutputBuffer buffer) {
            callOrder.add("outputBuffer.release");
            super.releaseOutputBuffer(buffer);
          }
        };
    TestExtensionCodecAdapter orderTrackingAdapter =
        new TestExtensionCodecAdapter(VIDEO_FORMAT, orderTrackingDecoder);
    int unusedInputIndex = orderTrackingAdapter.dequeueInputBufferIndex();
    orderTrackingAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = orderTrackingAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = orderTrackingAdapter.dequeueOutputBufferIndex(bufferInfo);

    orderTrackingAdapter.flush();

    assertThat(callOrder).containsExactly("decoder.flush", "outputBuffer.release").inOrder();
    assertThat(orderTrackingDecoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void release_releasesDecoderAndDisablesOperations() {
    adapter.release();
    int inputIndexAfterRelease = adapter.dequeueInputBufferIndex();
    int outputIndexAfterRelease = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(decoder.isReleased).isTrue();
    assertThat(inputIndexAfterRelease).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
    assertThat(outputIndexAfterRelease).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void registerOnBufferAvailableListener_whenCallbackNotSupported_returnsFalse() {
    boolean registered =
        adapter.registerOnBufferAvailableListener(
            new MediaCodecAdapter.OnBufferAvailableListener() {});

    assertThat(registered).isFalse();
  }

  @Test
  public void registerOnBufferAvailableListener_whenCallbackSupported_forwardsCallbacks() {
    ExtensionCodecAdapter callbackAdapter =
        new ExtensionCodecAdapter(VIDEO_FORMAT, decoder) {
          @Override
          protected boolean supportsBufferAvailableCallback() {
            return true;
          }
        };
    AtomicInteger inputAvailableCount = new AtomicInteger();
    AtomicInteger outputAvailableCount = new AtomicInteger();
    MediaCodecAdapter.OnBufferAvailableListener listener =
        new MediaCodecAdapter.OnBufferAvailableListener() {
          @Override
          public void onInputBufferAvailable() {
            inputAvailableCount.incrementAndGet();
          }

          @Override
          public void onOutputBufferAvailable() {
            outputAvailableCount.incrementAndGet();
          }
        };

    boolean registered = callbackAdapter.registerOnBufferAvailableListener(listener);
    int inputIndex = callbackAdapter.dequeueInputBufferIndex();
    callbackAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    assertThat(registered).isTrue();
    assertThat(inputIndex).isEqualTo(0);
    assertThat(inputAvailableCount.get()).isEqualTo(1);
    assertThat(outputAvailableCount.get()).isEqualTo(1);
  }

  @Test
  public void registerOnBufferAvailableListener_withSimpleDecoder_returnsTrue() {
    TestSimpleDecoder simpleDecoder = new TestSimpleDecoder();
    boolean registered;
    try {
      TestExtensionCodecAdapter simpleDecoderAdapter =
          new TestExtensionCodecAdapter(AUDIO_FORMAT, simpleDecoder);
      try {
        registered =
            simpleDecoderAdapter.registerOnBufferAvailableListener(
                new MediaCodecAdapter.OnBufferAvailableListener() {});
      } finally {
        simpleDecoderAdapter.release();
      }
    } finally {
      simpleDecoder.release();
    }

    assertThat(registered).isTrue();
  }

  @Test
  public void dequeueInputBufferIndex_whenDecoderThrows_throwsIllegalStateException() {
    TestExtensionCodecAdapter throwingAdapter =
        new TestExtensionCodecAdapter(
            VIDEO_FORMAT,
            new PassthroughDecoder() {
              @Override
              public DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
                throw new DecoderException("Dequeue input error");
              }
            });

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, throwingAdapter::dequeueInputBufferIndex);

    assertThat(thrown).hasCauseThat().isInstanceOf(DecoderException.class);
  }

  @Test
  public void queueInputBuffer_whenDecoderThrows_throwsIllegalStateException() {
    TestExtensionCodecAdapter throwingAdapter =
        new TestExtensionCodecAdapter(
            VIDEO_FORMAT,
            new PassthroughDecoder() {
              @Override
              public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws DecoderException {
                throw new DecoderException("Decode error");
              }
            });
    int unusedInputIndex = throwingAdapter.dequeueInputBufferIndex();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                throwingAdapter.queueInputBuffer(
                    /* index= */ 0,
                    /* offset= */ 0,
                    /* size= */ 10,
                    /* presentationTimeUs= */ 0L,
                    /* flags= */ 0));

    assertThat(thrown).hasCauseThat().isInstanceOf(DecoderException.class);
  }

  @Test
  public void dequeueOutputBufferIndex_whenDecoderThrows_throwsIllegalStateException() {
    TestExtensionCodecAdapter throwingAdapter =
        new TestExtensionCodecAdapter(
            VIDEO_FORMAT,
            new PassthroughDecoder() {
              @Override
              public TestOutputBuffer dequeueOutputBuffer() throws DecoderException {
                throw new DecoderException("Dequeue output error");
              }
            });

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> throwingAdapter.dequeueOutputBufferIndex(bufferInfo));

    assertThat(thrown).hasCauseThat().isInstanceOf(DecoderException.class);
  }

  @Test
  public void
      releaseOutputBuffer_whenRenderOutputBufferToSurfaceThrows_throwsIllegalStateException() {
    SurfaceTexture surfaceTexture = new SurfaceTexture(/* texName= */ 1);
    IllegalStateException thrown;
    try {
      Surface surface = new Surface(surfaceTexture);
      try {
        VideoExtensionCodecAdapter videoAdapter =
            new VideoExtensionCodecAdapter(VIDEO_FORMAT, decoder, surface) {
              @Override
              protected void renderOutputBufferToSurface(
                  VideoDecoderOutputBuffer outputBuffer, Surface surface) throws DecoderException {
                outputBuffer.release();
                throw new DecoderException("Surface render error");
              }
            };
        try {
          int unusedInputIndex = videoAdapter.dequeueInputBufferIndex();
          videoAdapter.queueInputBuffer(
              /* index= */ 0,
              /* offset= */ 0,
              /* size= */ 10,
              /* presentationTimeUs= */ 3000L,
              /* flags= */ 0);
          int unusedFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
          int unusedOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);

          thrown =
              assertThrows(
                  IllegalStateException.class,
                  () -> videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true));
        } finally {
          videoAdapter.release();
        }
      } finally {
        surface.release();
      }
    } finally {
      surfaceTexture.release();
    }

    assertThat(thrown).hasCauseThat().isInstanceOf(DecoderException.class);
  }

  @Test
  public void releaseOutputBuffer_whenBufferModeDoesNotMatchOutputMode_releasesWithoutRendering() {
    SurfaceTexture surfaceTexture = new SurfaceTexture(/* texName= */ 1);
    AtomicInteger renderToSurfaceCount = new AtomicInteger();
    boolean canRender;
    try {
      Surface surface = new Surface(surfaceTexture);
      try {
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_NONE);
        VideoExtensionCodecAdapter videoAdapter =
            new VideoExtensionCodecAdapter(VIDEO_FORMAT, decoder, surface) {
              @Override
              protected void renderOutputBufferToSurface(
                  VideoDecoderOutputBuffer outputBuffer, Surface surface) {
                renderToSurfaceCount.incrementAndGet();
                outputBuffer.release();
              }
            };
        try {
          int unusedInputIndex = videoAdapter.dequeueInputBufferIndex();
          videoAdapter.queueInputBuffer(
              /* index= */ 0,
              /* offset= */ 0,
              /* size= */ 10,
              /* presentationTimeUs= */ 3000L,
              /* flags= */ 0);
          int unusedFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
          int unusedOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);

          canRender = videoAdapter.canRenderOutputBuffer();
          videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true);
        } finally {
          videoAdapter.release();
        }
      } finally {
        surface.release();
      }
    } finally {
      surfaceTexture.release();
    }

    assertThat(canRender).isFalse();
    assertThat(renderToSurfaceCount.get()).isEqualTo(0);
  }

  @Test
  public void dequeueOutputBufferIndex_whenDecodedDimensionsChange_returnsOutputFormatChanged() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    int unusedFirstInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 500L,
        /* flags= */ 0);
    int unusedFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedFirstOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    decoder.setOutputDimensions(/* width= */ 1280, /* height= */ 720);
    int unusedSecondInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    int formatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat updatedFormat = videoAdapter.getOutputFormat();
    int bufferResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(updatedFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(1280);
    assertThat(updatedFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(720);
    assertThat(bufferResult).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1000L);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenDecodedDimensionsAreNonPositive_retainsExistingDimensions() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    int unusedFirstInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 500L,
        /* flags= */ 0);
    int unusedFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedFirstOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    decoder.setOutputDimensions(/* width= */ 0, /* height= */ 0);
    int unusedSecondInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    int bufferResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat outputFormat = videoAdapter.getOutputFormat();

    assertThat(bufferResult).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1000L);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(VIDEO_FORMAT.width);
    assertThat(outputFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(VIDEO_FORMAT.height);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenQueuedInputFormatChangesAndDecoderDropsBuffer_updatesOutputFormat() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    int unusedFirstInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().format = VIDEO_FORMAT;
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 500L,
        /* flags= */ 0);
    int unusedFirstFormatChanged = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedFirstOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    Format updatedContainerFormat =
        VIDEO_FORMAT
            .buildUpon()
            .setWidth(1280)
            .setHeight(720)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    decoder.setOutputDimensions(/* width= */ 640, /* height= */ 360);
    decoder.setNextOutputBufferShouldBeSkipped(true);
    int unusedSkippedInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().format = updatedContainerFormat;
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedSecondInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().format = updatedContainerFormat;
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ 0);

    int formatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat updatedMediaFormat = videoAdapter.getOutputFormat();
    Format currentOutputFormat = videoAdapter.getCurrentOutputFormat();
    int bufferResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    VideoDecoderOutputBuffer dequeuedBuffer =
        (VideoDecoderOutputBuffer) videoAdapter.getDequeuedOutputBuffer();

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(updatedMediaFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(640);
    assertThat(updatedMediaFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(360);
    assertThat(currentOutputFormat.width).isEqualTo(640);
    assertThat(currentOutputFormat.height).isEqualTo(360);
    assertThat(currentOutputFormat.colorInfo).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
    assertThat(bufferResult).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(2000L);
    assertThat(dequeuedBuffer.format).isEqualTo(currentOutputFormat);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenInputFormatChangesWithMultiFrameDelay_appliesFormatAtMatchingTimestamp() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    Format updatedFormat = VIDEO_FORMAT.buildUpon().setWidth(1280).setHeight(720).build();
    int unusedFirstInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().format = VIDEO_FORMAT;
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedSecondInputIndex = videoAdapter.dequeueInputBufferIndex();
    videoAdapter.getDequeuedInputBuffer().format = updatedFormat;
    videoAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ 0);

    int firstFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat firstMediaFormat = videoAdapter.getOutputFormat();
    int firstBufferResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    long firstPresentationTimeUs = bufferInfo.presentationTimeUs;
    videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    int secondFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat secondMediaFormat = videoAdapter.getOutputFormat();
    int secondBufferResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
    long secondPresentationTimeUs = bufferInfo.presentationTimeUs;

    assertThat(firstFormatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(firstMediaFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(1920);
    assertThat(firstMediaFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(1080);
    assertThat(firstBufferResult).isEqualTo(0);
    assertThat(firstPresentationTimeUs).isEqualTo(1000L);
    assertThat(secondFormatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(secondMediaFormat.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(1280);
    assertThat(secondMediaFormat.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(720);
    assertThat(secondBufferResult).isEqualTo(0);
    assertThat(secondPresentationTimeUs).isEqualTo(2000L);
  }

  @Test
  public void
      dequeueOutputBufferIndex_whenFormatChangesAfterPoolFullCycle_retainsUpdatedFormatAcrossRecycledBuffers() {
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    Format updatedFormat =
        VIDEO_FORMAT
            .buildUpon()
            .setWidth(1280)
            .setHeight(720)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    for (int i = 0; i < 5; i++) {
      int unusedInputIndex = videoAdapter.dequeueInputBufferIndex();
      videoAdapter.getDequeuedInputBuffer().format = VIDEO_FORMAT;
      videoAdapter.queueInputBuffer(
          /* index= */ 0,
          /* offset= */ 0,
          /* size= */ 10,
          /* presentationTimeUs= */ i * 1000L,
          /* flags= */ 0);
      if (i == 0) {
        int unusedInitialFormatChange = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
      }
      int unusedOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
      videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    }

    int formatChangedAfterSwitch = MediaCodec.INFO_TRY_AGAIN_LATER;
    Format[] outputFormatsAfterSwitch = new Format[4];
    Format[] bufferFormatsAfterSwitch = new Format[4];
    for (int i = 0; i < 4; i++) {
      int unusedInputIndex = videoAdapter.dequeueInputBufferIndex();
      videoAdapter.getDequeuedInputBuffer().format = updatedFormat;
      videoAdapter.queueInputBuffer(
          /* index= */ 0,
          /* offset= */ 0,
          /* size= */ 10,
          /* presentationTimeUs= */ (5 + i) * 1000L,
          /* flags= */ 0);
      if (i == 0) {
        formatChangedAfterSwitch = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
      }
      int unusedOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
      outputFormatsAfterSwitch[i] = videoAdapter.getCurrentOutputFormat();
      bufferFormatsAfterSwitch[i] =
          ((VideoDecoderOutputBuffer) videoAdapter.getDequeuedOutputBuffer()).format;
      videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    }

    assertThat(formatChangedAfterSwitch).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputFormatsAfterSwitch)
        .asList()
        .containsExactly(updatedFormat, updatedFormat, updatedFormat, updatedFormat);
    assertThat(bufferFormatsAfterSwitch)
        .asList()
        .containsExactly(updatedFormat, updatedFormat, updatedFormat, updatedFormat);
  }

  @Test
  public void flush_clearsPendingQueuedInputFormatsAndRequeuesAfterFlush() {
    TestExtensionCodecAdapter audioAdapter = new TestExtensionCodecAdapter(AUDIO_FORMAT, decoder);
    Format updatedAudioFormat =
        AUDIO_FORMAT.buildUpon().setSampleRate(44100).setChannelCount(1).build();
    int unusedFirstInputIndex = audioAdapter.dequeueInputBufferIndex();
    audioAdapter.getDequeuedInputBuffer().format = AUDIO_FORMAT;
    audioAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedInitialFormatChanged = audioAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedInitialOutputIndex = audioAdapter.dequeueOutputBufferIndex(bufferInfo);
    audioAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    int unusedPendingInputIndex = audioAdapter.dequeueInputBufferIndex();
    audioAdapter.getDequeuedInputBuffer().format = updatedAudioFormat;
    audioAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 2000L,
        /* flags= */ 0);

    audioAdapter.flush();
    int unusedPostFlushSameFormatIndex = audioAdapter.dequeueInputBufferIndex();
    audioAdapter.getDequeuedInputBuffer().format = AUDIO_FORMAT;
    audioAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 3000L,
        /* flags= */ 0);
    int postFlushSameFormatResult = audioAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat postFlushSameMediaFormat = audioAdapter.getOutputFormat();
    audioAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
    int unusedPostFlushNewFormatIndex = audioAdapter.dequeueInputBufferIndex();
    audioAdapter.getDequeuedInputBuffer().format = updatedAudioFormat;
    audioAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 4000L,
        /* flags= */ 0);
    int postFlushNewFormatChangedResult = audioAdapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat postFlushNewMediaFormat = audioAdapter.getOutputFormat();

    assertThat(postFlushSameFormatResult).isEqualTo(0);
    assertThat(postFlushSameMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)).isEqualTo(48000);
    assertThat(postFlushSameMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).isEqualTo(2);
    assertThat(postFlushNewFormatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(postFlushNewMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)).isEqualTo(44100);
    assertThat(postFlushNewMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)).isEqualTo(1);
  }

  @Test
  public void setOutputStartTimeUs_forwardsToDecoder() {
    adapter.setOutputStartTimeUs(50_000L);

    assertThat(decoder.outputStartTimeUs).isEqualTo(50_000L);
  }

  @Test
  public void flush_resetsOutputStartTimeUsOnDecoderAndInAdapter() {
    adapter.setOutputStartTimeUs(50_000L);

    adapter.flush();
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int outputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(decoder.outputStartTimeUs).isEqualTo(C.TIME_UNSET);
    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThat(outputIndex).isEqualTo(0);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1000L);
  }

  @Test
  public void release_clearsDecoderCallbackAndPreventsFurtherListenerNotifications() {
    ExtensionCodecAdapter callbackAdapter =
        new ExtensionCodecAdapter(VIDEO_FORMAT, decoder) {
          @Override
          protected boolean supportsBufferAvailableCallback() {
            return true;
          }
        };
    AtomicInteger inputAvailableCount = new AtomicInteger();
    AtomicInteger outputAvailableCount = new AtomicInteger();
    boolean registered =
        callbackAdapter.registerOnBufferAvailableListener(
            new MediaCodecAdapter.OnBufferAvailableListener() {
              @Override
              public void onInputBufferAvailable() {
                inputAvailableCount.incrementAndGet();
              }

              @Override
              public void onOutputBufferAvailable() {
                outputAvailableCount.incrementAndGet();
              }
            });
    Decoder.Callback capturedCallback = decoder.callback;

    callbackAdapter.release();
    capturedCallback.onInputBufferAvailable();
    capturedCallback.onOutputBufferAvailable();
    callbackAdapter.notifyOutputBufferAvailable();

    assertThat(registered).isTrue();
    assertThat(decoder.callback).isNull();
    assertThat(decoder.executor).isNull();
    assertThat(inputAvailableCount.get()).isEqualTo(0);
    assertThat(outputAvailableCount.get()).isEqualTo(0);
  }

  @Test
  public void release_withSimpleDecoder_checksCallbackSupportBeforeClearingDecoder() {
    TestSimpleDecoder simpleDecoder = new TestSimpleDecoder();
    List<Boolean> callbackSupportDuringRelease = new ArrayList<>();
    ExtensionCodecAdapter simpleDecoderAdapter =
        new ExtensionCodecAdapter(AUDIO_FORMAT, simpleDecoder) {
          @Override
          protected boolean supportsBufferAvailableCallback() {
            boolean supported = super.supportsBufferAvailableCallback();
            callbackSupportDuringRelease.add(supported);
            return supported;
          }
        };
    boolean registered;
    try {
      registered =
          simpleDecoderAdapter.registerOnBufferAvailableListener(
              new MediaCodecAdapter.OnBufferAvailableListener() {});
      callbackSupportDuringRelease.clear();

      simpleDecoderAdapter.release();
    } finally {
      simpleDecoder.release();
    }

    assertThat(registered).isTrue();
    assertThat(callbackSupportDuringRelease).containsExactly(true);
  }

  @Test
  public void release_withDecoderDependentCallbackSupport_clearsCallbackWithoutLock() {
    AtomicReference<Boolean> heldLockInSetCallback = new AtomicReference<>();
    AtomicReference<Boolean> heldLockInBufferRelease = new AtomicReference<>();
    AtomicReference<ExtensionCodecAdapter> adapterRef = new AtomicReference<>();
    PassthroughDecoder lockCheckingDecoder =
        new PassthroughDecoder() {
          @Override
          public void setCallback(@Nullable Callback callback, @Nullable Executor executor) {
            if (callback == null && adapterRef.get() != null) {
              heldLockInSetCallback.set(Thread.holdsLock(adapterRef.get().lock));
            }
            super.setCallback(callback, executor);
          }

          @Override
          protected void releaseOutputBuffer(VideoDecoderOutputBuffer buffer) {
            if (adapterRef.get() != null && adapterRef.get().getDecoder() == null) {
              heldLockInBufferRelease.set(Thread.holdsLock(adapterRef.get().lock));
            }
            super.releaseOutputBuffer(buffer);
          }
        };
    ExtensionCodecAdapter callbackAdapter =
        new ExtensionCodecAdapter(VIDEO_FORMAT, lockCheckingDecoder) {
          @Override
          protected boolean supportsBufferAvailableCallback() {
            return getDecoder() != null;
          }
        };
    adapterRef.set(callbackAdapter);
    boolean registered =
        callbackAdapter.registerOnBufferAvailableListener(
            new MediaCodecAdapter.OnBufferAvailableListener() {});
    int unusedInputIndex = callbackAdapter.dequeueInputBufferIndex();
    callbackAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = callbackAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = callbackAdapter.dequeueOutputBufferIndex(bufferInfo);

    callbackAdapter.release();

    assertThat(registered).isTrue();
    assertThat(lockCheckingDecoder.callback).isNull();
    assertThat(lockCheckingDecoder.executor).isNull();
    assertThat(heldLockInSetCallback.get()).isFalse();
    assertThat(heldLockInBufferRelease.get()).isFalse();
  }

  @Test
  public void flush_withConcurrentCallbackThread_doesNotDeadlock() throws Exception {
    AtomicInteger inputAvailableCount = new AtomicInteger();
    AtomicInteger outputAvailableCount = new AtomicInteger();
    CountDownLatch callbacksCompleted = new CountDownLatch(1);
    AtomicReference<Throwable> callbackThreadError = new AtomicReference<>();
    AtomicReference<Thread> callbackThreadRef = new AtomicReference<>();
    PassthroughDecoder concurrentCallbackDecoder =
        new PassthroughDecoder() {
          private @Nullable Callback activeCallback;
          private @Nullable Executor activeExecutor;

          @Override
          public void setCallback(@Nullable Callback callback, @Nullable Executor executor) {
            activeCallback = callback;
            activeExecutor = executor;
            super.setCallback(callback, executor);
          }

          @Override
          public void flush() {
            @Nullable Callback currentCallback = activeCallback;
            @Nullable Executor currentExecutor = activeExecutor;
            if (currentCallback != null && currentExecutor != null) {
              Thread callbackThread =
                  new Thread(
                      () -> {
                        try {
                          currentExecutor.execute(currentCallback::onInputBufferAvailable);
                          currentExecutor.execute(currentCallback::onOutputBufferAvailable);
                        } catch (Throwable t) {
                          callbackThreadError.set(t);
                        } finally {
                          callbacksCompleted.countDown();
                        }
                      },
                      "ExtensionCodecAdapterTest:Callback");
              callbackThreadRef.set(callbackThread);
              callbackThread.start();
              try {
                // Wait while ExtensionCodecAdapter.lock is held by flush() to detect lock
                // inversion.
                boolean completed = callbacksCompleted.await(5, SECONDS);
                if (!completed) {
                  callbackThreadError.set(
                      new AssertionError("Timed out waiting for concurrent buffer callbacks"));
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callbackThreadError.set(e);
              }
            }
            super.flush();
          }
        };
    ExtensionCodecAdapter callbackAdapter =
        new ExtensionCodecAdapter(VIDEO_FORMAT, concurrentCallbackDecoder) {
          @Override
          protected boolean supportsBufferAvailableCallback() {
            return true;
          }
        };
    boolean registered =
        callbackAdapter.registerOnBufferAvailableListener(
            new MediaCodecAdapter.OnBufferAvailableListener() {
              @Override
              public void onInputBufferAvailable() {
                inputAvailableCount.incrementAndGet();
              }

              @Override
              public void onOutputBufferAvailable() {
                outputAvailableCount.incrementAndGet();
              }
            });

    try {
      callbackAdapter.flush();
      if (callbackThreadRef.get() != null) {
        callbackThreadRef.get().join(5_000);
      }
    } finally {
      callbackAdapter.release();
    }

    assertThat(registered).isTrue();
    assertThat(callbackThreadError.get()).isNull();
    assertThat(inputAvailableCount.get()).isEqualTo(1);
    assertThat(outputAvailableCount.get()).isEqualTo(1);
  }

  @Test
  public void getDecoder_returnsDecoderBeforeReleaseAndNullAfterRelease() {
    Decoder<?, ?, ?> decoderBeforeRelease = adapter.getDecoder();

    adapter.release();
    Decoder<?, ?, ?> decoderAfterRelease = adapter.getDecoder();

    assertThat(decoderBeforeRelease).isSameInstanceAs(decoder);
    assertThat(decoderAfterRelease).isNull();
  }

  @Test
  public void releaseOutputBuffer_withRenderTimestampNs_rendersAndReleasesBuffer() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 3000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = adapter.dequeueOutputBufferIndex(bufferInfo);

    adapter.releaseOutputBuffer(/* index= */ 0, /* renderTimeStampNs= */ 1_000_000L);

    assertThat(adapter.rendered).isTrue();
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void getDequeuedInputBuffer_withSupplementalDataAndEos_preservesFlagsAndData() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    DecoderInputBuffer inputBuffer = adapter.getDequeuedInputBuffer();
    inputBuffer.addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    inputBuffer.resetSupplementalData(4);
    inputBuffer.supplementalData.put(new byte[] {1, 2, 3, 4}).flip();

    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 5000L,
        /* flags= */ MediaCodec.BUFFER_FLAG_END_OF_STREAM);

    assertThat(decoder.lastQueuedHasSupplementalData).isTrue();
    assertThat(decoder.lastQueuedIsEndOfStream).isTrue();
    assertThat(decoder.lastQueuedSupplementalDataBytes).isEqualTo(new byte[] {1, 2, 3, 4});
  }

  @Test
  public void dequeueInputBufferIndex_clearsPreviouslyMutatedDequeuedInputBuffer() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    DecoderInputBuffer inputBuffer = adapter.getDequeuedInputBuffer();
    inputBuffer.format = VIDEO_FORMAT;
    inputBuffer.addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA);
    inputBuffer.resetSupplementalData(4);
    inputBuffer.supplementalData.put(new byte[] {1, 2, 3, 4}).flip();

    // Calling dequeueInputBufferIndex again while a buffer is already dequeued clears it.
    int inputIndex = adapter.dequeueInputBufferIndex();
    Format formatAfterClear = adapter.getDequeuedInputBuffer().format;
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    assertThat(inputIndex).isEqualTo(0);
    assertThat(formatAfterClear).isNull();
    assertThat(decoder.lastQueuedHasSupplementalData).isFalse();
    assertThat(decoder.lastQueuedSupplementalDataBytes).isNull();
  }

  @Test
  public void getDequeuedInputBuffer_withoutDequeuedInputBuffer_throwsIllegalStateException() {
    assertThrows(IllegalStateException.class, () -> adapter.getDequeuedInputBuffer());
  }

  @Test
  public void releaseOutputBuffer_withRenderTrueOnBaseAdapter_releasesOutputBuffer() {
    ExtensionCodecAdapter baseAdapter = new ExtensionCodecAdapter(AUDIO_FORMAT, decoder) {};
    int unusedInputIndex = baseAdapter.dequeueInputBufferIndex();
    baseAdapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);
    int unusedFormatChangedResult = baseAdapter.dequeueOutputBufferIndex(bufferInfo);
    int unusedOutputIndex = baseAdapter.dequeueOutputBufferIndex(bufferInfo);
    int availableBeforeRelease = decoder.availableOutputBuffers.size();

    baseAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true);

    assertThat(availableBeforeRelease).isEqualTo(3);
    assertThat(decoder.availableOutputBuffers).hasSize(4);
  }

  @Test
  public void dequeueOutputBufferIndex_withSimpleDecoderOutputBuffer_setsOffsetAndSize() {
    Queue<SimpleDecoderOutputBuffer> outputQueue = new ArrayDeque<>();
    SimpleDecoderOutputBuffer pcmBuffer = new SimpleDecoderOutputBuffer(outputBuffer -> {});
    pcmBuffer.init(/* timeUs= */ 1234L, /* size= */ 8);
    pcmBuffer.data.put(new byte[] {10, 20, 30, 40, 50, 60, 70, 80});
    pcmBuffer.data.position(2);
    pcmBuffer.data.limit(6);
    outputQueue.add(pcmBuffer);
    Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> audioDecoder =
        createQueueBasedSimpleDecoder(outputQueue);
    TestExtensionCodecAdapter audioAdapter =
        new TestExtensionCodecAdapter(AUDIO_FORMAT, audioDecoder);
    int unusedFormatChangedResult = audioAdapter.dequeueOutputBufferIndex(bufferInfo);

    int pcmResult = audioAdapter.dequeueOutputBufferIndex(bufferInfo);
    ByteBuffer outputByteBuffer = audioAdapter.getOutputBuffer(/* index= */ 0);
    audioAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);

    assertThat(pcmResult).isEqualTo(0);
    assertThat(bufferInfo.offset).isEqualTo(2);
    assertThat(bufferInfo.size).isEqualTo(4);
    assertThat(bufferInfo.presentationTimeUs).isEqualTo(1234L);
    assertThat(outputByteBuffer).isSameInstanceAs(pcmBuffer.data);
  }

  @Test
  public void dequeueOutputBufferIndex_withEndOfStreamSimpleDecoderOutputBuffer_setsZeroSize() {
    Queue<SimpleDecoderOutputBuffer> outputQueue = new ArrayDeque<>();
    SimpleDecoderOutputBuffer eosBuffer = new SimpleDecoderOutputBuffer(outputBuffer -> {});
    eosBuffer.init(/* timeUs= */ 5678L, /* size= */ 8);
    eosBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    outputQueue.add(eosBuffer);
    Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> audioDecoder =
        createQueueBasedSimpleDecoder(outputQueue);
    TestExtensionCodecAdapter audioAdapter =
        new TestExtensionCodecAdapter(AUDIO_FORMAT, audioDecoder);

    int eosResult = audioAdapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(eosResult).isEqualTo(0);
    assertThat(bufferInfo.size).isEqualTo(0);
    assertThat(bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM).isNotEqualTo(0);
  }

  @Test
  public void
      getOutputBufferAndReleaseOutputBuffer_beforeBufferIndexReturnedAfterFormatChanged_throwsIllegalStateException() {
    int unusedInputIndex = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(
        /* index= */ 0,
        /* offset= */ 0,
        /* size= */ 10,
        /* presentationTimeUs= */ 1000L,
        /* flags= */ 0);

    int formatChangedResult = adapter.dequeueOutputBufferIndex(bufferInfo);

    assertThat(formatChangedResult).isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    assertThrows(IllegalStateException.class, () -> adapter.getOutputBuffer(/* index= */ 0));
    assertThrows(
        IllegalStateException.class,
        () -> adapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false));
  }

  @Test
  public void
      setOutput_onVideoExtensionCodecAdapter_updatesOutputModeInvokesHooksAndRendersToTarget() {
    List<Integer> observedOutputModes = new ArrayList<>();
    AtomicReference<Surface> renderedSurface = new AtomicReference<>();
    AtomicReference<VideoDecoderOutputBuffer> renderedBuffer = new AtomicReference<>();
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedBuffer.set(outputBuffer);
          outputBuffer.release();
        };
    boolean canRenderYuv;
    boolean canRenderWhileDetached;
    boolean canRenderAfterReattach;
    Surface secondSurface;
    SurfaceTexture firstSurfaceTexture = new SurfaceTexture(/* texName= */ 1);
    try {
      Surface firstSurface = new Surface(firstSurfaceTexture);
      try {
        SurfaceTexture secondSurfaceTexture = new SurfaceTexture(/* texName= */ 2);
        try {
          secondSurface = new Surface(secondSurfaceTexture);
          try {
            VideoExtensionCodecAdapter videoAdapter =
                new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder, firstSurface) {
                  @Override
                  protected void onOutputModeChanged(@C.VideoOutputMode int outputMode) {
                    super.onOutputModeChanged(outputMode);
                    decoder.setOutputMode(outputMode);
                    observedOutputModes.add(outputMode);
                  }

                  @Override
                  protected void renderOutputBufferToSurface(
                      VideoDecoderOutputBuffer outputBuffer, Surface surface) {
                    renderedSurface.set(surface);
                    outputBuffer.release();
                  }
                };
            try {
              videoAdapter.setOutputSurface(firstSurface);
              videoAdapter.setOutputSurface(secondSurface);
              int unusedFirstInputIndex = videoAdapter.dequeueInputBufferIndex();
              videoAdapter.queueInputBuffer(
                  /* index= */ 0,
                  /* offset= */ 0,
                  /* size= */ 10,
                  /* presentationTimeUs= */ 1000L,
                  /* flags= */ 0);
              int unusedFormatChangedResult = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
              int unusedFirstOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
              videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true);
              videoAdapter.setOutput(bufferRenderer);
              int unusedSecondInputIndex = videoAdapter.dequeueInputBufferIndex();
              videoAdapter.queueInputBuffer(
                  /* index= */ 0,
                  /* offset= */ 0,
                  /* size= */ 10,
                  /* presentationTimeUs= */ 2000L,
                  /* flags= */ 0);
              int unusedSecondOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
              canRenderYuv = videoAdapter.canRenderOutputBuffer();
              videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ true);
              videoAdapter.detachOutputSurface();
              int unusedThirdInputIndex = videoAdapter.dequeueInputBufferIndex();
              videoAdapter.queueInputBuffer(
                  /* index= */ 0,
                  /* offset= */ 0,
                  /* size= */ 10,
                  /* presentationTimeUs= */ 3000L,
                  /* flags= */ 0);
              int unusedThirdOutputIndex = videoAdapter.dequeueOutputBufferIndex(bufferInfo);
              canRenderWhileDetached = videoAdapter.canRenderOutputBuffer();
              videoAdapter.setOutput(bufferRenderer);
              canRenderAfterReattach = videoAdapter.canRenderOutputBuffer();
              videoAdapter.releaseOutputBuffer(/* index= */ 0, /* render= */ false);
            } finally {
              videoAdapter.release();
            }
          } finally {
            secondSurface.release();
          }
        } finally {
          secondSurfaceTexture.release();
        }
      } finally {
        firstSurface.release();
      }
    } finally {
      firstSurfaceTexture.release();
    }

    assertThat(observedOutputModes)
        .containsExactly(
            C.VIDEO_OUTPUT_MODE_SURFACE_YUV,
            C.VIDEO_OUTPUT_MODE_SURFACE_YUV,
            C.VIDEO_OUTPUT_MODE_YUV,
            C.VIDEO_OUTPUT_MODE_YUV)
        .inOrder();
    assertThat(renderedSurface.get()).isSameInstanceAs(secondSurface);
    assertThat(canRenderYuv).isTrue();
    assertThat(renderedBuffer.get()).isNotNull();
    assertThat(canRenderWhileDetached).isFalse();
    assertThat(canRenderAfterReattach).isTrue();
  }

  @Test
  public void maybeUnwrap_withDirectAndNestedForwardingAdapters_unwrapsExtensionAdapters() {
    PassthroughDecoder decoder = new PassthroughDecoder();
    TestVideoExtensionCodecAdapter videoAdapter =
        new TestVideoExtensionCodecAdapter(VIDEO_FORMAT, decoder);
    MediaCodecAdapter doublyWrappedVideoAdapter =
        new ForwardingMediaCodecAdapter(new ForwardingMediaCodecAdapter(videoAdapter) {}) {};
    TestExtensionCodecAdapter audioAdapter =
        new TestExtensionCodecAdapter(
            AUDIO_FORMAT, createQueueBasedSimpleDecoder(new ArrayDeque<>()));
    MediaCodecAdapter wrappedAudioAdapter = new ForwardingMediaCodecAdapter(audioAdapter) {};

    assertThat(ExtensionCodecAdapter.maybeUnwrap(null)).isNull();
    assertThat(VideoExtensionCodecAdapter.maybeUnwrap(null)).isNull();
    assertThat(ExtensionCodecAdapter.maybeUnwrap(doublyWrappedVideoAdapter))
        .isSameInstanceAs(videoAdapter);
    assertThat(VideoExtensionCodecAdapter.maybeUnwrap(doublyWrappedVideoAdapter))
        .isSameInstanceAs(videoAdapter);
    assertThat(ExtensionCodecAdapter.maybeUnwrap(wrappedAudioAdapter))
        .isSameInstanceAs(audioAdapter);
    assertThat(VideoExtensionCodecAdapter.maybeUnwrap(wrappedAudioAdapter)).isNull();
  }

  private static Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>
      createQueueBasedSimpleDecoder(Queue<SimpleDecoderOutputBuffer> outputQueue) {
    return new Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>() {
      @Override
      public String getName() {
        return "FakeAudioDecoder";
      }

      @Override
      public @Nullable DecoderInputBuffer dequeueInputBuffer() {
        return null;
      }

      @Override
      public void queueInputBuffer(DecoderInputBuffer inputBuffer) {}

      @Override
      public @Nullable SimpleDecoderOutputBuffer dequeueOutputBuffer() {
        return outputQueue.poll();
      }

      @Override
      public void flush() {}

      @Override
      public void release() {}

      @Override
      public void setOutputStartTimeUs(long outputStartTimeUs) {}
    };
  }

  private static final class TestExtensionCodecAdapter extends ExtensionCodecAdapter {
    private boolean rendered;

    private TestExtensionCodecAdapter(
        Format format,
        Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
            decoder) {
      super(format, decoder);
    }

    @Override
    /* package */ void renderOutputBuffer(DecoderOutputBuffer outputBuffer) {
      rendered = true;
      outputBuffer.release();
    }
  }

  private static class TestVideoExtensionCodecAdapter extends VideoExtensionCodecAdapter {
    private TestVideoExtensionCodecAdapter(
        Format format,
        Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
            decoder) {
      this(format, decoder, /* surface= */ null);
    }

    private TestVideoExtensionCodecAdapter(
        Format format,
        Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
            decoder,
        @Nullable Surface surface) {
      super(format, decoder, surface);
    }

    @Override
    protected void renderOutputBufferToSurface(
        VideoDecoderOutputBuffer outputBuffer, Surface surface) {
      outputBuffer.release();
    }
  }

  private static final class TestOutputBuffer extends VideoDecoderOutputBuffer {
    private TestOutputBuffer(PassthroughDecoder owner) {
      super(owner::releaseOutputBuffer);
    }
  }

  private static class PassthroughDecoder
      implements Decoder<DecoderInputBuffer, TestOutputBuffer, DecoderException> {

    private final Queue<DecoderInputBuffer> availableInputBuffers;
    private final Queue<TestOutputBuffer> availableOutputBuffers;
    private final Queue<TestOutputBuffer> queuedOutputBuffers;
    private boolean nextBufferIsKeyFrame;
    private boolean nextBufferShouldBeSkipped;
    private int nextSkippedOutputBufferCount;
    private @C.VideoOutputMode int outputMode;
    private int outputWidth;
    private int outputHeight;
    private @Nullable Callback callback;
    private @Nullable Executor executor;
    private int lastQueuedInputOffset;
    private int lastQueuedInputSize;
    private boolean lastQueuedInputWasKeyFrame;
    private boolean lastQueuedInputWasFirstSample;
    private long outputStartTimeUs;
    private boolean lastQueuedHasSupplementalData;
    private boolean lastQueuedIsEndOfStream;
    private byte @Nullable [] lastQueuedSupplementalDataBytes;
    private boolean isFlushed;
    private boolean isReleased;

    private PassthroughDecoder() {
      availableInputBuffers = new ArrayDeque<>();
      availableOutputBuffers = new ArrayDeque<>();
      queuedOutputBuffers = new ArrayDeque<>();
      outputMode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
      outputStartTimeUs = C.TIME_UNSET;
      for (int i = 0; i < 4; i++) {
        DecoderInputBuffer inputBuffer =
            new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        inputBuffer.ensureSpaceForWrite(1024);
        availableInputBuffers.add(inputBuffer);
        availableOutputBuffers.add(new TestOutputBuffer(this));
      }
    }

    @Override
    public String getName() {
      return "PassthroughDecoder";
    }

    @Override
    public void setOutputStartTimeUs(long outputStartTimeUs) {
      this.outputStartTimeUs = outputStartTimeUs;
    }

    @Override
    public void setCallback(@Nullable Callback callback, @Nullable Executor executor) {
      this.callback = callback;
      this.executor = executor;
    }

    @Override
    public @Nullable DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
      return availableInputBuffers.poll();
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws DecoderException {
      if (inputBuffer.data != null) {
        lastQueuedInputOffset = inputBuffer.data.position();
        lastQueuedInputSize = inputBuffer.data.remaining();
      }
      lastQueuedInputWasKeyFrame = inputBuffer.isKeyFrame();
      lastQueuedInputWasFirstSample = inputBuffer.isFirstSample();
      lastQueuedHasSupplementalData = inputBuffer.hasSupplementalData();
      lastQueuedIsEndOfStream = inputBuffer.isEndOfStream();
      if (inputBuffer.hasSupplementalData() && inputBuffer.supplementalData != null) {
        lastQueuedSupplementalDataBytes = new byte[inputBuffer.supplementalData.remaining()];
        inputBuffer.supplementalData.duplicate().get(lastQueuedSupplementalDataBytes);
      } else {
        lastQueuedSupplementalDataBytes = null;
      }
      TestOutputBuffer outputBuffer = availableOutputBuffers.poll();
      if (outputBuffer != null) {
        outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
        if (outputWidth > 0 && outputHeight > 0) {
          outputBuffer.initForPrivateFrame(outputWidth, outputHeight);
        }
        if (inputBuffer.isEndOfStream()) {
          outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        }
        if (inputBuffer.isLastSample()) {
          outputBuffer.addFlag(C.BUFFER_FLAG_LAST_SAMPLE);
        }
        if (nextBufferIsKeyFrame) {
          outputBuffer.addFlag(C.BUFFER_FLAG_KEY_FRAME);
          nextBufferIsKeyFrame = false;
        }
        if (nextBufferShouldBeSkipped) {
          outputBuffer.shouldBeSkipped = true;
          nextBufferShouldBeSkipped = false;
        }
        if (nextSkippedOutputBufferCount > 0) {
          outputBuffer.skippedOutputBufferCount = nextSkippedOutputBufferCount;
          nextSkippedOutputBufferCount = 0;
        }
        queuedOutputBuffers.add(outputBuffer);
        if (callback != null && executor != null) {
          executor.execute(callback::onOutputBufferAvailable);
        }
      }
      inputBuffer.clear();
      availableInputBuffers.add(inputBuffer);
      if (callback != null && executor != null) {
        executor.execute(callback::onInputBufferAvailable);
      }
    }

    @Override
    public @Nullable TestOutputBuffer dequeueOutputBuffer() throws DecoderException {
      return queuedOutputBuffers.poll();
    }

    @Override
    public void flush() {
      isFlushed = true;
      while (!queuedOutputBuffers.isEmpty()) {
        queuedOutputBuffers.poll().release();
      }
    }

    @Override
    public void release() {
      isReleased = true;
      flush();
      availableInputBuffers.clear();
      availableOutputBuffers.clear();
    }

    private void drainAvailableInputBuffers() {
      availableInputBuffers.clear();
    }

    private void setNextOutputBufferIsKeyFrame(boolean isKeyFrame) {
      this.nextBufferIsKeyFrame = isKeyFrame;
    }

    private void setNextOutputBufferShouldBeSkipped(boolean shouldBeSkipped) {
      this.nextBufferShouldBeSkipped = shouldBeSkipped;
    }

    private void setNextSkippedOutputBufferCount(int skippedOutputBufferCount) {
      this.nextSkippedOutputBufferCount = skippedOutputBufferCount;
    }

    private void setOutputMode(@C.VideoOutputMode int outputMode) {
      this.outputMode = outputMode;
    }

    private void setOutputDimensions(int width, int height) {
      this.outputWidth = width;
      this.outputHeight = height;
    }

    protected void releaseOutputBuffer(VideoDecoderOutputBuffer buffer) {
      buffer.clear();
      availableOutputBuffers.add((TestOutputBuffer) buffer);
    }
  }

  private static final class TestSimpleDecoder
      extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private TestSimpleDecoder() {
      super(new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[1]);
    }

    @Override
    public String getName() {
      return "TestSimpleDecoder";
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
      return null;
    }
  }
}
