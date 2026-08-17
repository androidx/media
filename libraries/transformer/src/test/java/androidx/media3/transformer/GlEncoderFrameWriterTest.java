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
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.media.metrics.LogSessionId;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlEncoderFrameWriter}. */
@RunWith(AndroidJUnit4.class)
public final class GlEncoderFrameWriterTest {

  private static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(1920)
          .setHeight(1080)
          .build();

  private GlEncoderFrameWriter glEncoderFrameWriter;
  private FakeEncoderFactory fakeEncoderFactory;
  private TestListener testListener;
  private HardwareBufferJniWrapper mockHardwareBufferJniWrapper;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    fakeEncoderFactory = new FakeEncoderFactory();
    testListener = new TestListener();
    mockHardwareBufferJniWrapper = mock(HardwareBufferJniWrapper.class);

    glEncoderFrameWriter =
        new GlEncoderFrameWriter(
            context,
            fakeEncoderFactory,
            testListener,
            /* listenerExecutor= */ directExecutor(),
            new DefaultGlObjectsProvider(),
            newDirectExecutorService(),
            mockHardwareBufferJniWrapper,
            /* logSessionId= */ null);
  }

  @Test
  public void isSupported_withHdrColorInfo_returnsFalse() {
    Format hdrFormat =
        VIDEO_FORMAT
            .buildUpon()
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .build())
            .build();
    assertThat(glEncoderFrameWriter.getInfo().isSupported(hdrFormat, Frame.USAGE_VIDEO_ENCODE))
        .isFalse();
  }

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
            // initialize GL.
            throw ExportException.createForUnexpected(new Exception("Short-circuit for unit test"));
          }
        };

    GlEncoderFrameWriter.Listener testListener =
        new GlEncoderFrameWriter.Listener() {
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

    Context context = ApplicationProvider.getApplicationContext();
    GlEncoderFrameWriter glEncoderFrameWriter =
        new GlEncoderFrameWriter(
            context,
            customEncoderFactory,
            testListener,
            directExecutor(),
            new DefaultGlObjectsProvider(),
            newDirectExecutorService(),
            mockHardwareBufferJniWrapper,
            LogSessionId.LOG_SESSION_ID_NONE);

    glEncoderFrameWriter.configure(new Format.Builder().build(), Frame.USAGE_VIDEO_ENCODE);

    // Verify the parameter was passed through.
    assertThat(passedLogSessionId.get()).isEqualTo(LogSessionId.LOG_SESSION_ID_NONE);
  }

  @Test
  public void isSupported_returnsTrueWhenSupported() {
    fakeEncoderFactory.setIsSupportedToReturn(true);

    boolean isSupported = glEncoderFrameWriter.getInfo().isSupported(VIDEO_FORMAT, /* usage= */ 0L);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isSupported_returnsFalseWhenUnsupported() {
    fakeEncoderFactory.setIsSupportedToReturn(false);

    boolean isSupported = glEncoderFrameWriter.getInfo().isSupported(VIDEO_FORMAT, /* usage= */ 0L);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void configure_factoryThrowsException_notifiesListenerOnError() {
    ExportException expectedException =
        ExportException.createForCodec(
            new IllegalArgumentException("Unsupported format"),
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            new ExportException.CodecInfo(
                VIDEO_FORMAT.toString(),
                /* isVideo= */ true,
                /* isDecoder= */ false,
                /* name= */ null));
    fakeEncoderFactory.setExceptionToThrow(expectedException);

    glEncoderFrameWriter.configure(VIDEO_FORMAT, /* usage= */ 0L);

    // Verify the exception was intercepted and passed to the listener.
    assertThat(testListener.exception).isNotNull();
    assertThat(testListener.exception).hasCauseThat().isEqualTo(expectedException);
  }

  @Test
  public void dequeueInputFrame_beforeConfigure_throwIllegalStateException() {
    Executor wakeupExecutor = directExecutor();
    assertThrows(
        IllegalStateException.class,
        () -> glEncoderFrameWriter.dequeueInputFrame(wakeupExecutor, () -> {}));

    assertThrows(IllegalStateException.class, () -> glEncoderFrameWriter.signalEndOfStream());
  }

  @Test
  public void queueInputFrame_beforeConfigure_throwIllegalStateException() {
    assertThrows(
        IllegalStateException.class,
        () ->
            glEncoderFrameWriter.queueInputFrame(
                new DefaultHardwareBufferFrame.Builder(null).build(), null));
  }

  @Test
  public void signalEndOfStream_beforeConfigure_throwIllegalStateException() {
    assertThrows(IllegalStateException.class, () -> glEncoderFrameWriter.signalEndOfStream());
  }

  /** A fake listener that records callbacks for validation. */
  private static class TestListener implements GlEncoderFrameWriter.Listener {
    @Nullable private VideoFrameProcessingException exception;

    @Override
    public Format onConfigure(Format requestedFormat) {
      return requestedFormat;
    }

    @Override
    public void onEncoderCreated(Codec encoder) {}

    @Override
    public void onEndOfStream() {}

    @Override
    public void onError(VideoFrameProcessingException e) {
      exception = e;
    }
  }

  /** A fake factory that allows stubbing the format negotiation. */
  private static class FakeEncoderFactory implements Codec.EncoderFactory {

    private boolean isSupportedToReturn;
    @Nullable private ExportException exceptionToThrow;

    private void setIsSupportedToReturn(boolean isSupported) {
      this.isSupportedToReturn = isSupported;
    }

    private void setExceptionToThrow(ExportException exception) {
      this.exceptionToThrow = exception;
    }

    @Override
    public boolean isVideoFormatSupported(Format format) {
      return isSupportedToReturn;
    }

    @Override
    public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      if (exceptionToThrow != null) {
        throw exceptionToThrow;
      }
      throw new UnsupportedOperationException();
    }
  }
}
