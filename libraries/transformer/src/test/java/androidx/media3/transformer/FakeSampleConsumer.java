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

import android.graphics.Bitmap;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.decoder.DecoderInputBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake {@link SampleConsumer} implementation for tests. */
/* package */ final class FakeSampleConsumer implements SampleConsumer {

  /* package */ final List<Long> queuedTimestamps;
  /* package */ volatile boolean isEnded;
  private final DecoderInputBuffer inputBuffer;

  /* package */ FakeSampleConsumer() {
    queuedTimestamps = new CopyOnWriteArrayList<>();
    inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    inputBuffer.ensureSpaceForWrite(0);
  }

  @Override
  public DecoderInputBuffer getInputBuffer() {
    return inputBuffer;
  }

  @Override
  public boolean queueInputBuffer() {
    if (inputBuffer.isEndOfStream()) {
      isEnded = true;
    } else {
      queuedTimestamps.add(inputBuffer.timeUs);
      isEnded = false;
    }
    inputBuffer.clear();
    inputBuffer.timeUs = 0;
    return true;
  }

  @Override
  public @InputResult int queueInputBitmap(
      Bitmap inputBitmap, TimestampIterator inStreamOffsetsUs) {
    while (inStreamOffsetsUs.hasNext()) {
      queuedTimestamps.add(inStreamOffsetsUs.next());
    }
    return INPUT_RESULT_SUCCESS;
  }

  @Override
  public @InputResult int queueInputTexture(int texId, long presentationTimeUs) {
    queuedTimestamps.add(presentationTimeUs);
    return INPUT_RESULT_SUCCESS;
  }

  @Override
  public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {}

  @Override
  public boolean registerVideoFrame(long presentationTimeUs) {
    queuedTimestamps.add(presentationTimeUs);
    return true;
  }

  @Override
  public void signalEndOfVideoInput() {
    isEnded = true;
  }
}
