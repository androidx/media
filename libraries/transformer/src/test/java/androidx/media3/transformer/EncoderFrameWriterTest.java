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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.media.metrics.LogSessionId;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.Frame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link EncoderFrameWriter}. */
@RunWith(AndroidJUnit4.class)
public class EncoderFrameWriterTest {

  @Test
  public void configure_propagatesLogSessionIdToEncoderFactory() {
    AtomicReference<LogSessionId> passedLogSessionId = new AtomicReference<>();

    Codec.EncoderFactory customEncoderFactory =
        new Codec.EncoderFactory() {
          @Override
          public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
              throws ExportException {
            passedLogSessionId.set(logSessionId);

            // Throwing an exception here safely short-circuits configure() before it attempts to
            // initialize ImageWriter.
            throw ExportException.createForUnexpected(new Exception("Short-circuit for unit test"));
          }
        };

    EncoderFrameWriter.Listener testListnener =
        new EncoderFrameWriter.Listener() {
          @Override
          public Format onConfigure(Format requestedFormat) {
            return requestedFormat;
          }

          @Override
          public void onEncoderCreated(Codec encoder) {}

          @Override
          public void onEndOfStream() {}

          @Override
          public void onError(VideoFrameProcessingException e) {}
        };

    EncoderFrameWriter encoderFrameWriter =
        new EncoderFrameWriter(
            customEncoderFactory,
            testListnener,
            directExecutor(),
            new Handler(Looper.getMainLooper()),
            LogSessionId.LOG_SESSION_ID_NONE);

    encoderFrameWriter.configure(new Format.Builder().build(), Frame.USAGE_VIDEO_ENCODE);

    // Verify the parameter was passed through.
    assertThat(passedLogSessionId.get()).isEqualTo(LogSessionId.LOG_SESSION_ID_NONE);
  }
}
