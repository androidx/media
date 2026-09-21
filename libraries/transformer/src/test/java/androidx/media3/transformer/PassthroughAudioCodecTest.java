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

import static androidx.media3.test.utils.TestUtil.buildTestData;
import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static androidx.media3.test.utils.TestUtil.createShortArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PassthroughAudioCodec}. */
@RunWith(AndroidJUnit4.class)
public final class PassthroughAudioCodecTest {

  private static final Format FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_RAW)
          .setPcmEncoding(C.ENCODING_PCM_16BIT)
          .setChannelCount(2)
          .build();

  private PassthroughAudioCodec codec;

  @Before
  public void setup() {
    codec = new PassthroughAudioCodec(FORMAT);
  }

  @After
  public void tearDown() {
    if (codec != null) {
      codec.release();
    }
  }

  @Test
  public void getConfigurationFormat_returnsFormat() {
    assertThat(codec.getConfigurationFormat()).isEqualTo(FORMAT);
  }

  @Test
  public void getInputFormat_returnsFormat() {
    assertThat(codec.getInputFormat()).isEqualTo(FORMAT);
  }

  @Test
  public void getOutputFormat_returnsFormat() {
    assertThat(codec.getOutputFormat()).isEqualTo(FORMAT);
  }

  @Test
  public void maybeDequeueInputBuffer_withNoPendingInputBuffer_returnsTrueAndAssignsBuffer() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);

    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    assertThat(inputBuffer.data).isNotNull();
    assertThat(inputBuffer.data.position()).isEqualTo(0);
  }

  @Test
  public void maybeDequeueInputBuffer_calledTwiceWithoutQueueing_returnsFalse() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);

    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isFalse();
  }

  @Test
  public void queueInputBuffer_updatesOutputAndClearsInputBuffer() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();

    assertThat(inputBuffer.data).isNotNull();
    inputBuffer.data.put(createByteBuffer(new short[] {10, 20, 30, 40})).flip();
    inputBuffer.timeUs = 123456L;
    codec.queueInputBuffer(inputBuffer);

    assertThat(inputBuffer.data).isNull();
    assertThat(createShortArray(checkNotNull(codec.getOutputBuffer())))
        .isEqualTo(new short[] {10, 20, 30, 40});

    BufferInfo outputBufferInfo = codec.getOutputBufferInfo();
    assertThat(outputBufferInfo).isNotNull();
    assertThat(outputBufferInfo.offset).isEqualTo(0);
    assertThat(outputBufferInfo.size).isEqualTo(8);
    assertThat(outputBufferInfo.presentationTimeUs).isEqualTo(123456L);
    assertThat(outputBufferInfo.flags).isEqualTo(0);
  }

  @Test
  public void releaseOutputBuffer_clearsOutputBufferAndUnlocksDequeuing() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    inputBuffer.data.put(buildTestData(/* length= */ 20)).flip();
    codec.queueInputBuffer(inputBuffer);

    assertThat(codec.getOutputBuffer()).isNotNull();
    assertThat(codec.getOutputBufferInfo()).isNotNull();

    codec.releaseOutputBuffer(/* render= */ false);

    assertThat(codec.getOutputBuffer()).isNull();
    assertThat(codec.getOutputBufferInfo()).isNull();
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
  }

  @Test
  public void queueEndOfStream_endsCodecAfterConsumingOutput() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();

    inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    // Flip buffer so that there is no readable data.
    inputBuffer.data.flip();
    codec.queueInputBuffer(inputBuffer);

    assertThat(codec.isEnded()).isFalse();

    BufferInfo outputBufferInfo = codec.getOutputBufferInfo();
    assertThat(outputBufferInfo).isNotNull();
    assertThat(outputBufferInfo.flags).isEqualTo(MediaCodec.BUFFER_FLAG_END_OF_STREAM);

    codec.releaseOutputBuffer(/* render= */ false);

    // After releasing the output buffer, stream should be ended.
    assertThat(codec.isEnded()).isTrue();
    assertThat(codec.getOutputBuffer()).isNull();
    assertThat(codec.getOutputBufferInfo()).isNull();
  }

  @Test
  public void queueInputBuffer_withoutDequeuing_throws() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);

    assertThrows(IllegalStateException.class, () -> codec.queueInputBuffer(inputBuffer));
  }

  @Test
  public void releaseOutputBuffer_withRenderTrue_throws() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    codec.queueInputBuffer(inputBuffer);

    assertThrows(
        IllegalArgumentException.class, () -> codec.releaseOutputBuffer(/* render= */ true));
  }

  @Test
  public void getInputSurface_throwsException() {
    assertThrows(UnsupportedOperationException.class, codec::getInputSurface);
  }

  @Test
  public void signalEndOfInputStream_throwsException() {
    assertThrows(UnsupportedOperationException.class, codec::signalEndOfInputStream);
  }

  @Test
  public void maybeDequeueInputBuffer_afterEndOfStream_returnsFalse() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    // Flip buffer so that there is no readable data.
    inputBuffer.data.flip();
    inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    codec.queueInputBuffer(inputBuffer);

    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isFalse();
  }

  @Test
  public void queueInputBuffer_afterEndOfStream_throwsException() throws Exception {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    // Flip buffer so that there is no readable data.
    inputBuffer.data.flip();
    inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    codec.queueInputBuffer(inputBuffer);

    assertThrows(IllegalStateException.class, () -> codec.queueInputBuffer(inputBuffer));
  }

  @Test
  public void release_resetsStateAndThrowsOnSubsequentOperations() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    inputBuffer.data.put(buildTestData(/* length= */ 20)).flip();
    codec.queueInputBuffer(inputBuffer);

    codec.release();

    assertThat(codec.isEnded()).isFalse();

    assertThrows(IllegalStateException.class, () -> codec.maybeDequeueInputBuffer(inputBuffer));
    assertThrows(IllegalStateException.class, codec::release);
    assertThrows(IllegalStateException.class, codec::getOutputBuffer);
    assertThrows(IllegalStateException.class, codec::getOutputBufferInfo);

    // Set to null to avoid releasing twice.
    codec = null;
  }

  @Test
  public void releaseOutputBuffer_withNoQueuedData_throws() {
    assertThrows(IllegalStateException.class, () -> codec.releaseOutputBuffer(/* render= */ false));
  }

  @Test
  public void releaseOutputBuffer_calledTwice_throws() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    codec.queueInputBuffer(inputBuffer);

    codec.releaseOutputBuffer(/* render= */ false);
    assertThrows(IllegalStateException.class, () -> codec.releaseOutputBuffer(/* render= */ false));
  }

  @Test
  public void queueInputBuffer_withDifferentBuffer_throwsException() {
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    assertThat(codec.maybeDequeueInputBuffer(inputBuffer)).isTrue();
    inputBuffer.data = ByteBuffer.allocate(10);

    assertThrows(IllegalArgumentException.class, () -> codec.queueInputBuffer(inputBuffer));
  }
}
