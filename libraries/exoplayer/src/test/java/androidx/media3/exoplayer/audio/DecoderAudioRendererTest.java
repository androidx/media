/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.C.FORMAT_HANDLED;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SEAMLESS;
import static androidx.media3.exoplayer.RendererCapabilities.DECODER_SUPPORT_PRIMARY;
import static androidx.media3.exoplayer.RendererCapabilities.TUNNELING_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.TUNNELING_SUPPORTED;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

/** Unit test for {@link DecoderAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public class DecoderAudioRendererTest {

  private static final Format FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build();

  @Mock private AudioSink mockAudioSink;
  private DecoderAudioRenderer<FakeDecoder> audioRenderer;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    audioRenderer =
        new DecoderAudioRenderer<FakeDecoder>(null, null, mockAudioSink) {
          @Override
          public String getName() {
            return "TestAudioRenderer";
          }

          @Override
          protected @C.FormatSupport int supportsFormatInternal(Format format) {
            return FORMAT_HANDLED;
          }

          @Override
          protected FakeDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig) {
            return new FakeDecoder();
          }

          @Override
          protected Format getOutputFormat(FakeDecoder decoder) {
            return FORMAT;
          }
        };
    audioRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
  }

  @Config(sdk = 19)
  @Test
  public void supportsFormatAtApi19() {
    assertThat(audioRenderer.supportsFormat(FORMAT))
        .isEqualTo(
            ADAPTIVE_NOT_SEAMLESS
                | TUNNELING_NOT_SUPPORTED
                | FORMAT_HANDLED
                | DECODER_SUPPORT_PRIMARY);
  }

  @Config(sdk = 21)
  @Test
  public void supportsFormatAtApi21() {
    // From API 21, tunneling is supported.
    assertThat(audioRenderer.supportsFormat(FORMAT))
        .isEqualTo(
            ADAPTIVE_NOT_SEAMLESS | TUNNELING_SUPPORTED | FORMAT_HANDLED | DECODER_SUPPORT_PRIMARY);
  }

  @Test
  public void immediatelyReadEndOfStreamPlaysAudioSinkToEndOfStream() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            FORMAT,
            ImmutableList.of(END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    audioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    audioRenderer.setCurrentStreamFinal();
    when(mockAudioSink.isEnded()).thenReturn(true);
    while (!audioRenderer.isEnded()) {
      audioRenderer.render(0, 0);
    }
    verify(mockAudioSink, times(1)).playToEndOfStream();
    audioRenderer.disable();
    audioRenderer.reset();
    verify(mockAudioSink, times(1)).reset();
  }

  @Test
  public void firstSampleOfStreamSignalsDiscontinuityAndSetOutputStreamOffsetToAudioSink()
      throws Exception {
    when(mockAudioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(mockAudioSink.isEnded()).thenReturn(true);
    InOrder inOrderAudioSink = inOrder(mockAudioSink);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            FORMAT,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    audioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {FORMAT},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    audioRenderer.setCurrentStreamFinal();
    while (!audioRenderer.isEnded()) {
      audioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    inOrderAudioSink.verify(mockAudioSink, times(1)).setOutputStreamOffsetUs(0);
    inOrderAudioSink.verify(mockAudioSink, times(1)).handleDiscontinuity();
    inOrderAudioSink.verify(mockAudioSink, times(2)).handleBuffer(any(), anyLong(), anyInt());
  }

  @Test
  public void
      firstSampleOfReplacementStreamSignalsDiscontinuityAndSetOutputStreamOffsetToAudioSink()
          throws Exception {
    when(mockAudioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(mockAudioSink.isEnded()).thenReturn(true);
    InOrder inOrderAudioSink = inOrder(mockAudioSink);
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            FORMAT,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            FORMAT,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 1_000_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_001_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    audioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {FORMAT},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId);

    while (!audioRenderer.hasReadStreamToEnd()) {
      audioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    audioRenderer.replaceStream(
        new Format[] {FORMAT},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId);
    audioRenderer.setCurrentStreamFinal();
    while (!audioRenderer.isEnded()) {
      audioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    inOrderAudioSink.verify(mockAudioSink, times(1)).setOutputStreamOffsetUs(0);
    inOrderAudioSink.verify(mockAudioSink, times(1)).handleDiscontinuity();
    inOrderAudioSink.verify(mockAudioSink, times(2)).handleBuffer(any(), anyLong(), anyInt());
    inOrderAudioSink.verify(mockAudioSink, times(1)).handleDiscontinuity();
    inOrderAudioSink.verify(mockAudioSink, times(1)).setOutputStreamOffsetUs(1_000_000);
    inOrderAudioSink.verify(mockAudioSink, times(2)).handleBuffer(any(), anyLong(), anyInt());
  }

  private static final class FakeDecoder
      extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    public FakeDecoder() {
      super(new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[1]);
    }

    @Override
    public String getName() {
      return "FakeDecoder";
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
      return new DecoderException("Unexpected decode error", error);
    }

    @Override
    protected DecoderException decode(
        DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
      if (inputBuffer.isEndOfStream()) {
        outputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
      }
      return null;
    }
  }
}
