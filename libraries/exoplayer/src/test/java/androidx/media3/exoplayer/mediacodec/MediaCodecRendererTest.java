/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/** Unit tests for {@link MediaCodecRenderer} */
@RunWith(AndroidJUnit4.class)
public class MediaCodecRendererTest {

  @Test
  public void render_withReplaceStream_triggersOutputCallbacksInCorrectOrder() throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 400,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(400);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAndBufferBeyondDuration_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500, 600);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 400,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(400);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAndBufferLessThanStartPosition_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200, 300);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 200,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
  }

  @Test
  public void
      render_withReplaceStreamAfterInitialEmptySampleStream_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 = createFakeSampleStream(format1 /* no samples */);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId2);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
  }

  @Test
  public void
      render_withReplaceStreamAfterIntermittentEmptySampleStream_triggersOutputCallbacksInCorrectOrder()
          throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    Format format3 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(2000).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100);
    FakeSampleStream fakeSampleStream2 = createFakeSampleStream(format2 /* no samples */);
    FakeSampleStream fakeSampleStream3 =
        createFakeSampleStream(format3, /* sampleTimesUs...= */ 0, 100, 200);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId3 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format3},
        fakeSampleStream3,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId3);
    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(200);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format3), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
  }

  @Test
  public void render_afterEnableWithStartPositionUs_skipsSamplesBeforeStartPositionUs()
      throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 300,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();
    renderer.setCurrentStreamFinal();
    long positionUs = 0;
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 0, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 100, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 200, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 300, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 400, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 500, /* isDecodeOnly= */ false);
  }

  @Test
  public void render_afterPositionReset_skipsSamplesBeforeStartPositionUs() throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer = spy(new TestRenderer());
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 400,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    renderer.resetPosition(/* positionUs= */ 200);
    renderer.setCurrentStreamFinal();
    long positionUs = 0;
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime());
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 0, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 100, /* isDecodeOnly= */ true);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 200, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 300, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 400, /* isDecodeOnly= */ false);
    verifyProcessOutputBufferDecodeOnly(
        inOrder, renderer, /* presentationTimeUs= */ 500, /* isDecodeOnly= */ false);
  }

  private FakeSampleStream createFakeSampleStream(Format format, long... sampleTimesUs) {
    ImmutableList.Builder<FakeSampleStream.FakeSampleStreamItem> sampleListBuilder =
        ImmutableList.builder();
    for (long sampleTimeUs : sampleTimesUs) {
      sampleListBuilder.add(oneByteSample(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME));
    }
    sampleListBuilder.add(END_OF_STREAM_ITEM);
    FakeSampleStream sampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            sampleListBuilder.build());
    sampleStream.writeData(/* startPositionUs= */ 0);
    return sampleStream;
  }

  private static class TestRenderer extends MediaCodecRenderer {

    public TestRenderer() {
      super(
          C.TRACK_TYPE_AUDIO,
          MediaCodecAdapter.Factory.getDefault(ApplicationProvider.getApplicationContext()),
          /* mediaCodecSelector= */ (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
              Collections.singletonList(
                  MediaCodecInfo.newInstance(
                      /* name= */ "name",
                      /* mimeType= */ mimeType,
                      /* codecMimeType= */ mimeType,
                      /* capabilities= */ null,
                      /* hardwareAccelerated= */ false,
                      /* softwareOnly= */ true,
                      /* vendor= */ false,
                      /* forceDisableAdaptive= */ false,
                      /* forceSecure= */ false)),
          /* enableDecoderFallback= */ false,
          /* assumedMinimumCodecOperatingRate= */ 44100);
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    protected @Capabilities int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format)
        throws MediaCodecUtil.DecoderQueryException {
      return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected List<MediaCodecInfo> getDecoderInfos(
        MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder)
        throws MediaCodecUtil.DecoderQueryException {
      return mediaCodecSelector.getDecoderInfos(
          format.sampleMimeType,
          /* requiresSecureDecoder= */ false,
          /* requiresTunnelingDecoder= */ false);
    }

    @Override
    protected MediaCodecAdapter.Configuration getMediaCodecConfiguration(
        MediaCodecInfo codecInfo,
        Format format,
        @Nullable MediaCrypto crypto,
        float codecOperatingRate) {
      return MediaCodecAdapter.Configuration.createForAudioDecoding(
          codecInfo, new MediaFormat(), format, crypto);
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs,
        long elapsedRealtimeUs,
        @Nullable MediaCodecAdapter codec,
        @Nullable ByteBuffer buffer,
        int bufferIndex,
        int bufferFlags,
        int sampleCount,
        long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer,
        boolean isLastBuffer,
        Format format)
        throws ExoPlaybackException {
      if (bufferPresentationTimeUs <= positionUs) {
        // Only release buffers when the position advances far enough for realistic behavior where
        // input of buffers to the codec is faster than output.
        codec.releaseOutputBuffer(bufferIndex, /* render= */ true);
        return true;
      }
      return false;
    }

    @Override
    protected DecoderReuseEvaluation canReuseCodec(
        MediaCodecInfo codecInfo, Format oldFormat, Format newFormat) {
      return new DecoderReuseEvaluation(
          codecInfo.name,
          oldFormat,
          newFormat,
          REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
          /* discardReasons= */ 0);
    }
  }

  private static void verifyProcessOutputBufferDecodeOnly(
      InOrder inOrder, MediaCodecRenderer renderer, long presentationTimeUs, boolean isDecodeOnly)
      throws Exception {
    inOrder
        .verify(renderer)
        .processOutputBuffer(
            anyLong(),
            anyLong(),
            any(),
            any(),
            anyInt(),
            anyInt(),
            anyInt(),
            eq(presentationTimeUs),
            eq(isDecodeOnly),
            anyBoolean(),
            any());
  }
}
