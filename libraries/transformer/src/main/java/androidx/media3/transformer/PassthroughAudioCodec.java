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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** A passthrough {@link Codec} implementation for PCM audio samples. */
/* package */ final class PassthroughAudioCodec implements Codec {

  private static final int DEFAULT_BUFFER_SIZE_FRAMES = 4096;

  private final Format format;
  private final ByteBuffer buffer;
  private final BufferInfo bufferInfo;

  private boolean isInputBufferDequeued;
  private boolean isOutputBufferAvailable;
  private boolean isInputStreamEnded;
  private boolean isReleased;

  public PassthroughAudioCodec(Format format) {
    checkArgument(Objects.equals(format.sampleMimeType, MimeTypes.AUDIO_RAW));
    this.format = format;
    int frameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount);
    this.buffer =
        ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE_FRAMES * frameSize)
            .order(ByteOrder.nativeOrder());
    this.bufferInfo = new BufferInfo();
  }

  @Override
  public String getName() {
    return "androidx.media3.transformer.PassthroughAudioCodec";
  }

  @Override
  public Format getConfigurationFormat() {
    return format;
  }

  @Override
  public Surface getInputSurface() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) {
    checkState(!isReleased);
    if (isInputBufferDequeued || isOutputBufferAvailable || isInputStreamEnded) {
      return false;
    }
    inputBuffer.clear();
    inputBuffer.data = buffer;
    isInputBufferDequeued = true;
    return true;
  }

  @Override
  // We explicitly want to compare instances between inputBuffer.data and buffer and avoid comparing
  // the contents of the buffer.
  @SuppressWarnings("ReferenceEquality")
  public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
    checkState(!isReleased);
    checkState(isInputBufferDequeued, "Input buffer is not dequeued.");
    checkState(!isInputStreamEnded, "Input buffer cannot be queued after EoS.");
    checkArgument(inputBuffer.data != null);
    checkArgument(inputBuffer.data == buffer);

    int offset = 0;
    int size = 0;
    int flags = 0;

    // Ignore data when EoS is queued.
    if (inputBuffer.isEndOfStream()) {
      checkState(!inputBuffer.data.hasRemaining(), "EoS buffer should have no remaining data.");
      isInputStreamEnded = true;
      flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
    } else {
      offset = inputBuffer.data.position();
      size = inputBuffer.data.remaining();
    }

    bufferInfo.set(offset, size, inputBuffer.timeUs, flags);
    isInputBufferDequeued = false;
    isOutputBufferAvailable = true;

    // Clear state or release reference from inputBuffer
    inputBuffer.data = null;
  }

  @Override
  public void signalEndOfInputStream() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Format getInputFormat() {
    return format;
  }

  @Override
  public Format getOutputFormat() {
    return format;
  }

  @Override
  @Nullable
  public ByteBuffer getOutputBuffer() {
    checkState(!isReleased);
    if (!isOutputBufferAvailable) {
      return null;
    }
    // Return a duplicate to prevent the caller from modifying buffer's positions.
    return buffer.duplicate().order(buffer.order());
  }

  @Override
  @Nullable
  public BufferInfo getOutputBufferInfo() {
    checkState(!isReleased);
    return isOutputBufferAvailable ? bufferInfo : null;
  }

  @Override
  public void releaseOutputBuffer(boolean render) {
    checkState(!isReleased);
    checkArgument(!render);
    checkState(isOutputBufferAvailable);
    isOutputBufferAvailable = false;
    buffer.clear();
  }

  @Override
  public void releaseOutputBuffer(long renderPresentationTimeUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isEnded() {
    return isInputStreamEnded && !isOutputBufferAvailable;
  }

  @Override
  public void release() {
    checkState(!isReleased);
    isReleased = true;
    isInputBufferDequeued = false;
    isOutputBufferAvailable = false;
    isInputStreamEnded = false;
    buffer.clear();
  }
}
