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

import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.format;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.annotation.Config;

/** Unit tests for {@link MediaCodecRenderer} */
@RunWith(AndroidJUnit4.class)
public class MediaCodecRendererTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private static final Format AUDIO_AAC =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setChannelCount(2)
          .setSampleRate(44100)
          .build();
  private static final Format AUDIO_OPUS =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_OPUS)
          .setChannelCount(2)
          .setSampleRate(48000)
          .build();

  @Before
  public void setUp() {
    Flags.enableFlag(Flags.FLAG_PROCESSED_STREAM_CHANGED_AT_START);
  }

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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);

    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
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
  public void render_withReplaceStreamInBypassMode_triggersOutputCallbacksInCorrectOrder()
      throws Exception {
    Format format1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    Format format2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(format1, /* sampleTimesUs...= */ 0, 100, 200);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        spy(
            new TestRenderer() {
              @Override
              protected boolean shouldUseBypass(Format format) {
                return true;
              }
            });
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    // Enable renderer on the second media period.
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format2},
        fakeSampleStream2,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId2);

    renderer.start();
    long positionUs = 0;
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 200;
    }
    // Seek to first media period and reset second media period/sample stream.
    renderer.stop();
    positionUs = 100;
    renderer.disable();
    fakeSampleStream2 =
        createFakeSampleStream(format2, /* sampleTimesUs...= */ 0, 100, 200, 300, 400);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ positionUs,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ positionUs,
        /* offsetUs= */ 0,
        mediaPeriodId1);

    fakeSampleStream1.seekToUs(positionUs, true);
    renderer.resetPosition(positionUs, /* sampleStreamIsResetToKeyFrame= */ true);
    renderer.start();
    while (!renderer.hasReadStreamToEnd()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 200;
    }
    renderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 300,
        /* offsetUs= */ 300,
        mediaPeriodId2);

    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 200;
    }

    InOrder inOrder = inOrder(renderer);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(0);
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(0);
    inOrder.verify(renderer, times(2)).onPositionReset(100, false, true);
    inOrder.verify(renderer).onOutputFormatChanged(eq(format1), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(100);
    inOrder.verify(renderer).onProcessedOutputBuffer(200);
    inOrder.verify(renderer).onOutputStreamOffsetUsChanged(300);
    inOrder.verify(renderer).onProcessedStreamChange();
    inOrder.verify(renderer).onOutputFormatChanged(eq(format2), any());
    inOrder.verify(renderer).onProcessedOutputBuffer(300);
    inOrder.verify(renderer).onProcessedOutputBuffer(400);
    inOrder.verify(renderer).onProcessedOutputBuffer(500);
    inOrder.verify(renderer).onProcessedOutputBuffer(600);
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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

    renderer.resetPosition(/* positionUs= */ 200, /* sampleStreamIsResetToKeyFrame= */ true);
    renderer.setCurrentStreamFinal();
    long positionUs = 0;
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
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

  @Test
  public void render_samplesAfterLastSample_clearsLastBufferFlag() throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    // A sample follows the sample flagged as the last one, for example because the read end time
    // was extended after the flagged sample was read.
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(
                    /* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_LAST_SAMPLE),
                oneByteSample(/* timeUs= */ 300, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    codecAdapter.makeInputBuffersAvailable(/* count= */ 4);
    MediaCodecRenderer renderer = spy(new TestRenderer(configuration -> codecAdapter));
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();

    // Queues all samples to the codec, including 300 after the sample flagged as the last one.
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    codecAdapter.enqueueOutputBuffer(/* presentationTimeUs= */ 300);
    renderer.render(/* positionUs= */ 300, SystemClock.elapsedRealtime() * 1000);

    verify(renderer)
        .processOutputBuffer(
            anyLong(),
            anyLong(),
            any(),
            any(),
            anyInt(),
            anyInt(),
            anyInt(),
            /* bufferPresentationTimeUs= */ eq(300L),
            anyBoolean(),
            /* isLastBuffer= */ eq(false),
            any());
  }

  @Test
  public void render_discardedSampleAfterLastSample_clearsLastBufferInStreamPresentationTime()
      throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    // A sample follows the sample flagged as the last one, for example because the read end time
    // was extended after the flagged sample was read.
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(
                    /* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_LAST_SAMPLE),
                oneByteSample(/* timeUs= */ 300, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        new TestRenderer() {
          @Override
          protected boolean shouldSkipDecoderInputBuffer(DecoderInputBuffer buffer) {
            return buffer.timeUs == 300;
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    long positionUs = 0;
    while (fakeSampleStream.isReady()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    assertThat(renderer.getLastBufferInStreamPresentationTimeUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void render_samplesAfterLastSampleInBypassMode_clearsLastBufferInStreamPresentationTime()
      throws Exception {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    // A sample follows the sample flagged as the last one, for example because the read end time
    // was extended after the flagged sample was read.
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(
                    /* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_LAST_SAMPLE),
                oneByteSample(/* timeUs= */ 300, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        new TestRenderer() {
          @Override
          protected boolean shouldUseBypass(Format format) {
            return true;
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId);
    renderer.start();

    long positionUs = 0;
    while (fakeSampleStream.isReady()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    assertThat(renderer.getLastBufferInStreamPresentationTimeUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void render_wrapsIllegalStateExceptionFromMediaCodecInExoPlaybackException()
      throws Exception {
    MediaCodecAdapter.Factory throwingMediaCodecAdapterFactory =
        new ThrowingMediaCodecAdapter.Factory(() -> {
          IllegalStateException ise = new IllegalStateException("ISE from inside MediaCodec");
          StackTraceElement[] stackTrace = ise.getStackTrace();
          stackTrace[0] =
              new StackTraceElement(
                  "android.media.MediaCodec",
                  "fakeMethod",
                  stackTrace[0].getFileName(),
                  stackTrace[0].getLineNumber());
          ise.setStackTrace(stackTrace);
          return ise;
        });
    TestRenderer renderer = new TestRenderer(throwingMediaCodecAdapterFactory);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
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

    ExoPlaybackException playbackException =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000));

    assertThat(playbackException.type).isEqualTo(ExoPlaybackException.TYPE_RENDERER);
    assertThat(playbackException)
        .hasCauseThat()
        .hasCauseThat()
        .isInstanceOf(IllegalStateException.class);
    assertThat(playbackException)
        .hasCauseThat()
        .hasCauseThat()
        .hasMessageThat()
        .contains("ISE from inside MediaCodec");
  }

  @Test
  public void render_whenExtensionCodecThrowsDecoderException_wrapsInExoPlaybackException()
      throws Exception {
    DecoderException decoderException = new DecoderException("Extension decoder failed") {};
    FakeExtensionDecoder failingDecoder =
        new FakeExtensionDecoder(/* initialBufferSize= */ 128) {
          @Override
          public DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
            throw decoderException;
          }
        };
    MediaCodecAdapter.Factory extensionAdapterFactory =
        configuration -> new ExtensionCodecAdapter(configuration.format, failingDecoder) {};
    TestRenderer renderer = new TestRenderer(extensionAdapterFactory);
    ExoPlaybackException playbackException;
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        Format format =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setAverageBitrate(1000)
                .build();
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
        enableAndStartRenderer(
            renderer, format, fakeSampleStream, /* positionUs= */ 0, /* startPositionUs= */ 400);
        long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;

        playbackException =
            assertThrows(
                ExoPlaybackException.class,
                () -> renderer.render(/* positionUs= */ 0, elapsedRealtimeUs));
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(playbackException.type).isEqualTo(ExoPlaybackException.TYPE_RENDERER);
    assertThat(playbackException.errorCode)
        .isEqualTo(ExoPlaybackException.ERROR_CODE_DECODING_FAILED);
    assertThat(playbackException)
        .hasCauseThat()
        .hasCauseThat()
        .hasCauseThat()
        .isSameInstanceAs(decoderException);
  }

  @Test
  public void
      render_withExtensionCodecAdapterAndEarlyReturnAfterReallocation_synchronizesInputBufferData()
          throws Exception {
    FakeExtensionDecoder fakeDecoder = new FakeExtensionDecoder(/* initialBufferSize= */ 0);
    MediaCodecAdapter.Factory extensionAdapterFactory =
        configuration -> new ExtensionCodecAdapter(configuration.format, fakeDecoder) {};
    TestRenderer renderer =
        new TestRenderer(extensionAdapterFactory) {
          @Override
          protected DecoderReuseEvaluation canReuseCodec(
              MediaCodecInfo codecInfo,
              Format oldFormat,
              Format newFormat,
              boolean isAdaptiveFormatChange) {
            return new DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION,
                /* discardReasons= */ 0);
          }

          @Override
          protected boolean shouldSkipDecoderInputBuffer(DecoderInputBuffer buffer) {
            return buffer.timeUs == 0;
          }
        };
    int capacityAfterSkippedSample;
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        Format initialFormat =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .build();
        Format reconfiguredFormat =
            initialFormat
                .buildUpon()
                .setInitializationData(ImmutableList.of(new byte[256]))
                .build();
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(
                initialFormat,
                ImmutableList.of(sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, new byte[64])));
        enableAndStartRenderer(renderer, initialFormat, fakeSampleStream);

        // First render reads the 64-byte sample and returns early via shouldSkipDecoderInputBuffer
        // before queueInputBuffer.
        renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
        capacityAfterSkippedSample = checkNotNull(fakeDecoder.decoderInputBuffer.data).capacity();
        // Queue a keyframe followed by a format with 256-byte initializationData and no sample yet,
        // triggering RECONFIGURATION_STATE_WRITE_PENDING followed by RESULT_NOTHING_READ.
        fakeSampleStream.append(
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME),
                format(reconfiguredFormat)));
        fakeSampleStream.writeData(/* startPositionUs= */ 0);
        renderer.render(/* positionUs= */ 20_000, SystemClock.elapsedRealtime() * 1000);
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(capacityAfterSkippedSample).isAtLeast(64);
    assertThat(checkNotNull(fakeDecoder.decoderInputBuffer.data).capacity()).isAtLeast(256);
  }

  @Test
  public void
      resetPosition_withExtensionCodecAdapterAndPendingInputBuffer_flushesAndResetsInputBuffer()
          throws Exception {
    FakeExtensionDecoder fakeDecoder = new FakeExtensionDecoder(/* initialBufferSize= */ 128);
    MediaCodecAdapter.Factory extensionAdapterFactory =
        configuration -> new ExtensionCodecAdapter(configuration.format, fakeDecoder) {};
    TestRenderer renderer =
        new TestRenderer(extensionAdapterFactory) {
          @Override
          protected boolean shouldDiscardDecoderInputBuffer(DecoderInputBuffer buffer) {
            return buffer.timeUs == 0;
          }
        };
    int flushCountAfterReset;
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        Format format =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .build();
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(
                format,
                ImmutableList.of(sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, new byte[64])));
        enableAndStartRenderer(renderer, format, fakeSampleStream);
        // First render dequeues inputBuffer, writes 64 bytes, and discards via
        // shouldDiscardDecoderInputBuffer without clearing buffer.data before any buffer is queued
        // (codecReceivedBuffers == false).
        renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

        renderer.resetPosition(/* positionUs= */ 20_000, /* sampleStreamIsResetToKeyFrame= */ true);
        fakeSampleStream.append(
            ImmutableList.of(oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_KEY_FRAME)));
        fakeSampleStream.writeData(/* startPositionUs= */ 0);
        renderer.render(/* positionUs= */ 20_000, SystemClock.elapsedRealtime() * 1000);
        flushCountAfterReset = fakeDecoder.flushCount;
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(flushCountAfterReset).isEqualTo(1);
    assertThat(fakeDecoder.lastQueuedSize).isEqualTo(1);
  }

  @Test
  public void
      renderAndResetPosition_withExtensionCodecAdapter_updatesOutputStartTimeUsAndBufferFlags()
          throws Exception {
    FakeExtensionDecoder fakeDecoder = new FakeExtensionDecoder(/* initialBufferSize= */ 128);
    MediaCodecAdapter.Factory extensionAdapterFactory =
        configuration -> new ExtensionCodecAdapter(configuration.format, fakeDecoder) {};
    TestRenderer renderer = new TestRenderer(extensionAdapterFactory);
    List<Long> outputStartTimesUsBeforeDisable;
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        Format format =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .build();
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(
                format,
                ImmutableList.of(
                    oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                    oneByteSample(/* timeUs= */ 0),
                    oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_LAST_SAMPLE)));
        enableAndStartRenderer(
            renderer,
            format,
            fakeSampleStream,
            /* positionUs= */ 10_000,
            /* startPositionUs= */ 10_000);

        renderer.render(/* positionUs= */ 10_000, SystemClock.elapsedRealtime() * 1000);
        renderer.resetPosition(
            /* positionUs= */ 20_000, /* sampleStreamIsResetToKeyFrame= */ false);
        fakeSampleStream.append(
            ImmutableList.of(oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME)));
        fakeSampleStream.writeData(/* startPositionUs= */ 0);
        renderer.render(/* positionUs= */ 20_000, SystemClock.elapsedRealtime() * 1000);
        renderer.resetPosition(/* positionUs= */ 30_000, /* sampleStreamIsResetToKeyFrame= */ true);
        fakeSampleStream.append(
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
        fakeSampleStream.writeData(/* startPositionUs= */ 0);
        renderer.render(/* positionUs= */ 30_000, SystemClock.elapsedRealtime() * 1000);
        outputStartTimesUsBeforeDisable = new ArrayList<>(fakeDecoder.outputStartTimesUs);
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(outputStartTimesUsBeforeDisable)
        .containsExactly(10_000L, 20_000L, C.TIME_UNSET, 30_000L)
        .inOrder();
    assertThat(fakeDecoder.queuedInputKeyFrameFlags)
        .containsExactly(true, false, false, true, true)
        .inOrder();
    assertThat(fakeDecoder.queuedInputFirstSampleFlags)
        .containsExactly(true, false, false, false, true)
        .inOrder();
    assertThat(fakeDecoder.queuedInputLastSampleFlags)
        .containsExactly(false, false, true, false, false)
        .inOrder();
  }

  @Test
  public void
      render_withExtensionCodecRequiringInitDataMatchAndChangedAv1InitData_reinitializesCodec()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    List<Format> configuredFormats = new ArrayList<>();
    MediaCodecAdapter.Factory extensionAdapterFactory =
        configuration -> {
          configuredFormats.add(configuration.format);
          return new ExtensionCodecAdapter(
              configuration.format, new FakeExtensionDecoder(/* initialBufferSize= */ 128)) {};
        };
    ExtensionCodecInfo av1ExtensionCodecInfo =
        new ExtensionCodecInfo(/* name= */ "c2.media3.av1.decoder", MimeTypes.VIDEO_AV1) {
          @Override
          protected boolean requiresInitializationDataMatch() {
            return true;
          }
        };
    TestRenderer renderer =
        new TestRenderer(extensionAdapterFactory) {
          @Override
          protected ImmutableList<MediaCodecInfo> getDecoderInfos(
              MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) {
            return ImmutableList.of(av1ExtensionCodecInfo);
          }
        };
    Format av1Format1 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(640)
            .setHeight(360)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3}))
            .build();
    Format av1Format2 =
        av1Format1
            .buildUpon()
            .setInitializationData(ImmutableList.of(new byte[] {4, 5, 6}))
            .build();
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(
                av1Format1,
                ImmutableList.of(
                    oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                    format(av1Format2),
                    oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_KEY_FRAME),
                    END_OF_STREAM_ITEM));
        enableAndStartRenderer(renderer, av1Format1, fakeSampleStream);

        renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
        renderer.render(/* positionUs= */ 10_000, SystemClock.elapsedRealtime() * 1000);
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(configuredFormats).hasSize(2);
    assertThat(configuredFormats.get(0).initializationData)
        .isEqualTo(av1Format1.initializationData);
    assertThat(configuredFormats.get(1).initializationData)
        .isEqualTo(av1Format2.initializationData);
  }

  @Test
  public void
      flushOrReinitializeCodec_whenSwitchingFromPlatformAv1CodecToExtensionCodec_preservesInitializationData()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    List<Format> configuredFormats = new ArrayList<>();
    MediaCodecInfo platformAv1CodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo extensionAv1CodecInfo =
        new ExtensionCodecInfo(/* name= */ "c2.media3.av1.decoder", MimeTypes.VIDEO_AV1) {};
    List<MediaCodecInfo> currentCodecInfos = new ArrayList<>();
    currentCodecInfos.add(platformAv1CodecInfo);
    MediaCodecAdapter.Factory adapterFactory =
        configuration -> {
          configuredFormats.add(configuration.format);
          return new ExtensionCodecAdapter(
              configuration.format, new FakeExtensionDecoder(/* initialBufferSize= */ 128)) {};
        };
    TestRenderer renderer =
        new TestRenderer(adapterFactory) {
          @Override
          protected ImmutableList<MediaCodecInfo> getDecoderInfos(
              MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) {
            return ImmutableList.copyOf(currentCodecInfos);
          }

          @Override
          protected boolean shouldReleaseCodecInsteadOfFlushing() {
            return true;
          }
        };
    Format av1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(640)
            .setHeight(360)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3}))
            .build();
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(av1Format, /* sampleTimesUs...= */ 0, 10_000);
        enableAndStartRenderer(renderer, av1Format, fakeSampleStream);

        renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
        currentCodecInfos.clear();
        currentCodecInfos.add(extensionAv1CodecInfo);
        renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(configuredFormats).hasSize(2);
    assertThat(configuredFormats.get(0).initializationData).isEmpty();
    assertThat(configuredFormats.get(1).initializationData).isEqualTo(av1Format.initializationData);
  }

  @Test
  public void
      render_withExtensionCodecInfoAndNonExtensionCodecAdapter_releasesAdapterAndThrowsDecoderInitFailed()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    AtomicBoolean adapterReleased = new AtomicBoolean();
    ExtensionCodecInfo extensionCodecInfo =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.av1.decoder", MimeTypes.VIDEO_AV1);
    MediaCodecAdapter.Factory invalidWrapperFactory =
        configuration ->
            new ThrowingMediaCodecAdapter(() -> new IllegalStateException("Unexpected call")) {
              @Override
              public void release() {
                adapterReleased.set(true);
              }
            };
    TestRenderer renderer =
        new TestRenderer(invalidWrapperFactory) {
          @Override
          protected ImmutableList<MediaCodecInfo> getDecoderInfos(
              MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) {
            return ImmutableList.of(extensionCodecInfo);
          }
        };
    ExoPlaybackException thrown;
    @Nullable MediaCodecAdapter codecAfterFailure;
    try {
      try {
        renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        Format av1Format =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setWidth(640)
                .setHeight(360)
                .build();
        FakeSampleStream fakeSampleStream =
            createFakeSampleStream(av1Format, /* sampleTimesUs...= */ 0);
        enableAndStartRenderer(renderer, av1Format, fakeSampleStream);

        thrown =
            assertThrows(
                ExoPlaybackException.class,
                () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000));
        codecAfterFailure = renderer.getCodec();
      } finally {
        stopDisableAndResetRenderer(renderer);
      }
    } finally {
      renderer.release();
    }

    assertThat(thrown.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);
    assertThat(thrown).hasCauseThat().hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(adapterReleased.get()).isTrue();
    assertThat(codecAfterFailure).isNull();
  }

  @Test
  public void
      render_withNonMediaCodecAndNonDecoderExceptionIllegalStateException_rethrowsIllegalStateException()
          throws Exception {
    MediaCodecAdapter.Factory throwingMediaCodecAdapterFactory =
        new ThrowingMediaCodecAdapter.Factory(() -> new IllegalStateException("Non-codec ISE"));
    TestRenderer renderer = new TestRenderer(throwingMediaCodecAdapterFactory);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100);
    enableAndStartRenderer(renderer, format, fakeSampleStream);
    long elapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000;

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> renderer.render(/* positionUs= */ 0, elapsedRealtimeUs));

    assertThat(thrown).hasMessageThat().contains("Non-codec ISE");
  }

  // b/347367307#comment6
  @Test
  public void render_wrapsCryptoExceptionFromAnyMediaCodecMethod() throws Exception {
    MediaCodecAdapter.Factory throwingMediaCodecAdapterFactory =
        new ThrowingMediaCodecAdapter.Factory(() ->
            new MediaCodec.CryptoException(
                MediaDrm.ErrorCodes.ERROR_INSUFFICIENT_OUTPUT_PROTECTION, "Test exception"));
    TestRenderer renderer = new TestRenderer(throwingMediaCodecAdapterFactory);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100, 200, 300, 400, 500);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
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

    ExoPlaybackException playbackException =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000));

    assertThat(playbackException.type).isEqualTo(ExoPlaybackException.TYPE_RENDERER);
    assertThat(playbackException).hasCauseThat().isInstanceOf(MediaCodec.CryptoException.class);
    assertThat(playbackException).hasCauseThat().hasMessageThat().contains("Test exception");
  }

  @Test
  @Config(sdk = 29)
  public void setCodecParameters_onEnabledRenderer_appliesToCodecInstance() throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    MediaCodecAdapter mockCodecAdapter = renderer.getCodec();
    CodecParameters testParameters =
        new CodecParameters.Builder().setInteger("test-key", 42).build();

    renderer.handleMessage(Renderer.MSG_SET_CODEC_PARAMETERS, testParameters);

    ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
    verify(mockCodecAdapter).setParameters(bundleCaptor.capture());
    assertThat(bundleCaptor.getValue().getInt("test-key")).isEqualTo(42);
    clearInvocations(mockCodecAdapter);

    renderer.handleMessage(Renderer.MSG_SET_CODEC_PARAMETERS, CodecParameters.EMPTY);

    verify(mockCodecAdapter).setParameters(bundleCaptor.capture());
    assertThat(bundleCaptor.getValue().isEmpty()).isTrue();
  }

  @Test
  @Config(sdk = 29)
  public void setCodecParameters_beforeRendererEnabled_parametersAppliedDuringConfiguration()
      throws Exception {
    MediaCodecAdapter mockCodecAdapter = mock(MediaCodecAdapter.class);
    MediaCodecAdapter.Factory mockCodecAdapterFactory = mock(MediaCodecAdapter.Factory.class);
    when(mockCodecAdapterFactory.createAdapter(any())).thenReturn(mockCodecAdapter);
    TestRenderer renderer = setUpRenderer(mockCodecAdapterFactory);
    CodecParameters testParameters =
        new CodecParameters.Builder().setInteger("test-key", 101).build();
    renderer.handleMessage(Renderer.MSG_SET_CODEC_PARAMETERS, testParameters);

    enableRenderer(mockCodecAdapter, renderer, AUDIO_AAC);

    ArgumentCaptor<MediaCodecAdapter.Configuration> configurationCaptor =
        ArgumentCaptor.forClass(MediaCodecAdapter.Configuration.class);
    verify(mockCodecAdapterFactory).createAdapter(configurationCaptor.capture());
    MediaFormat mediaFormat = configurationCaptor.getValue().mediaFormat;
    assertThat(mediaFormat.getInteger("test-key")).isEqualTo(101);
  }

  @Test
  @Config(sdk = 29)
  public void render_triggersCodecOutputFormatChange_withValueChange_firesOnCodecParametersChanged()
      throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("test-key"));
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(AUDIO_AAC);
    mediaFormat.setInteger("test-key", 100);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(100);
    clearInvocations(renderer);

    mediaFormat.setInteger("test-key", 200);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(200);
  }

  @Test
  @Config(sdk = 29)
  public void render_triggersCodecOutputFormatChange_withKeyAdded_firesOnCodecParametersChanged()
      throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("test-key"));
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(AUDIO_AAC);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer, never()).onCodecParametersChanged(any());

    mediaFormat.setInteger("test-key", 100);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(100);
  }

  @Test
  @Config(sdk = 29)
  public void render_triggersCodecOutputFormatChange_withKeyRemoved_firesOnCodecParametersChanged()
      throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("test-key"));
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(AUDIO_AAC);
    mediaFormat.setInteger("test-key", 100);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(100);
    clearInvocations(renderer);

    mediaFormat.removeKey("test-key");
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().keySet()).doesNotContain("test-key");
  }

  @Test
  @Config(sdk = 29)
  public void render_triggersCodecOutputFormatChange_withValueNulled_firesOnCodecParametersChanged()
      throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("test-key"));
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(AUDIO_AAC);
    mediaFormat.setString("test-key", "initial-value");
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo("initial-value");
    clearInvocations(renderer);

    mediaFormat.setString("test-key", null);
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().keySet()).doesNotContain("test-key");
  }

  @Test
  @Config(sdk = 29)
  public void
      render_triggersCodecOutputFormatChange_withNoSubscribedKeyChange_doesNotFireOnCodecParametersChanged()
          throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("test-key"));
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(AUDIO_AAC);
    mediaFormat.setInteger("test-key", 100);
    mediaFormat.setString("unsubscribed-key", "value1");
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer).onCodecParametersChanged(any());
    clearInvocations(renderer);

    mediaFormat.setString("unsubscribed-key", "value2");
    setUpRendererToTriggerCodecOutputFormatChange(renderer, mediaFormat);

    renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    shadowOf(Looper.getMainLooper()).idle();
    verify(renderer, never()).onCodecParametersChanged(any());
  }

  @Test
  @Config(sdk = 31)
  public void updateCodecSubscriptions_onApi31_callsSubscribeAndUnsubscribe() throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    MediaCodecAdapter mockCodecAdapter = renderer.getCodec();
    @SuppressWarnings("unchecked") // Method being verified is known to take List<String>.
    ArgumentCaptor<List<String>> stringListCaptor = ArgumentCaptor.forClass(List.class);

    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("key1", "key2"));

    verify(mockCodecAdapter).subscribeToVendorParameters(stringListCaptor.capture());
    assertThat(stringListCaptor.getValue()).containsExactly("key1", "key2");
    clearInvocations(mockCodecAdapter);

    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("key2", "key3"));

    verify(mockCodecAdapter).subscribeToVendorParameters(stringListCaptor.capture());
    assertThat(stringListCaptor.getValue()).containsExactly("key3");
    verify(mockCodecAdapter).unsubscribeFromVendorParameters(stringListCaptor.capture());
    assertThat(stringListCaptor.getValue()).containsExactly("key1");
  }

  @Test
  @Config(sdk = 29)
  public void updateCodecSubscriptions_onApi29_doesNotCallSubscribeUnsubscribe() throws Exception {
    TestRenderer renderer = setUpAndEnableRenderer(AUDIO_AAC);
    MediaCodecAdapter mockCodecAdapter = renderer.getCodec();
    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("key1", "key2"));

    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("key2", "key3"));

    verify(mockCodecAdapter, never()).subscribeToVendorParameters(any());
    verify(mockCodecAdapter, never()).unsubscribeFromVendorParameters(any());
  }

  @Test
  public void resetPosition_withoutBuffersReceived_doesNotFlushCodec() throws Exception {
    MediaCodecAdapter mockCodecAdapter = mock(MediaCodecAdapter.class);
    when(mockCodecAdapter.dequeueInputBufferIndex()).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    when(mockCodecAdapter.dequeueOutputBufferIndex(any()))
        .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    MediaCodecAdapter.Factory mockCodecAdapterFactory = configuration -> mockCodecAdapter;
    TestRenderer renderer = setUpRenderer(mockCodecAdapterFactory);

    Format format = AUDIO_AAC;
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000 * 1000);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockCodecAdapter, never())
        .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());

    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);

    verify(mockCodecAdapter, never()).flush();
  }

  @Test
  public void resetPosition_withBuffersReceived_flushesCodec() throws Exception {
    MediaCodecAdapter mockCodecAdapter = mock(MediaCodecAdapter.class);
    doAnswer(
            invocation -> {
              ((ThrowingRunnable<?>) invocation.getArgument(0)).run();
              return null;
            })
        .when(mockCodecAdapter)
        .useBuffer(any());
    when(mockCodecAdapter.dequeueInputBufferIndex())
        .thenReturn(0)
        .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    when(mockCodecAdapter.getInputBuffer(0)).thenReturn(ByteBuffer.allocate(1024));
    when(mockCodecAdapter.dequeueOutputBufferIndex(any()))
        .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    MediaCodecAdapter.Factory mockCodecAdapterFactory = configuration -> mockCodecAdapter;
    TestRenderer renderer = setUpRenderer(mockCodecAdapterFactory);

    Format format = AUDIO_AAC;
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000 * 1000);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockCodecAdapter).queueInputBuffer(eq(0), anyInt(), anyInt(), anyLong(), anyInt());

    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);

    verify(mockCodecAdapter).flush();
  }

  @Test
  @Config(sdk = 31)
  public void codecReinitialized_withSubscribedKeys_resubscribesToVendorParameters()
      throws Exception {
    MediaCodecAdapter mockCodecAdapter1 = mock(MediaCodecAdapter.class);
    MediaCodecAdapter mockCodecAdapter2 = mock(MediaCodecAdapter.class);
    MediaCodecAdapter.Factory mockCodecAdapterFactory = mock(MediaCodecAdapter.Factory.class);
    when(mockCodecAdapterFactory.createAdapter(any()))
        .thenReturn(mockCodecAdapter1)
        .thenReturn(mockCodecAdapter2);
    TestRenderer renderer = setUpRenderer(mockCodecAdapterFactory);
    enableRenderer(mockCodecAdapter1, renderer, AUDIO_AAC);
    @SuppressWarnings("unchecked") // Method being verified is known to take List<String>.
    ArgumentCaptor<List<String>> stringListCaptor = ArgumentCaptor.forClass(List.class);

    renderer.handleMessage(
        Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, ImmutableSet.of("key1", "key2"));

    verify(mockCodecAdapter1).subscribeToVendorParameters(stringListCaptor.capture());
    assertThat(stringListCaptor.getValue()).containsExactly("key1", "key2");

    // Force codec re-initialization by changing the format.
    renderer.stop();
    renderer.disable();
    enableRenderer(mockCodecAdapter2, renderer, AUDIO_OPUS);

    // Verify that the new codec instance is subscribed to the same keys.
    verify(mockCodecAdapter2).subscribeToVendorParameters(stringListCaptor.capture());
    assertThat(stringListCaptor.getValue()).containsExactly("key1", "key2");
  }

  @Test
  public void onCodecError_withWakeUpOnMediaCodecErrorFlagEnabled_wakesUpRenderer()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    Renderer.WakeupListener mockWakeupListener = mock(Renderer.WakeupListener.class);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, mockWakeupListener);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 0, 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    codecAdapter.notifyCodecError(createMediaCodecIllegalStateException());

    verify(mockWakeupListener).onWakeup();
  }

  @Test
  public void onCodecError_withWakeUpOnMediaCodecErrorFlagDisabled_doesNotWakeUpRenderer()
      throws Exception {
    Flags.disableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    Renderer.WakeupListener mockWakeupListener = mock(Renderer.WakeupListener.class);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    renderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, mockWakeupListener);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 0, 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    codecAdapter.notifyCodecError(createMediaCodecIllegalStateException());

    verify(mockWakeupListener, never()).onWakeup();
  }

  @Test
  public void render_withHeldOutputBufferAndEndedInputAndAsyncCodecError_surfacesErrorImmediately()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    codecAdapter.makeInputBuffersAvailable(/* count= */ 2);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    // First render initializes the codec and feeds the single sample + EOS so inputStreamEnded is
    // true. Second render dequeues and holds the output buffer (not released at positionUs=0).
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    boolean hasReadStreamToEndBeforeError = renderer.hasReadStreamToEnd();
    codecAdapter.enqueueOutputBuffer(/* presentationTimeUs= */ 100);
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    codecAdapter.notifyCodecError(createMediaCodecIllegalStateException());

    ExoPlaybackException exception =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000));

    assertThat(hasReadStreamToEndBeforeError).isTrue();
    assertThat(exception.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DECODING_FAILED);
  }

  @Test
  public void render_withHeldOutputBufferAndEndedInputAndAsyncCryptoError_surfacesErrorImmediately()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    codecAdapter.makeInputBuffersAvailable(/* count= */ 2);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    boolean hasReadStreamToEndBeforeError = renderer.hasReadStreamToEnd();
    codecAdapter.enqueueOutputBuffer(/* presentationTimeUs= */ 100);
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    MediaCodec.CryptoException cryptoException =
        new MediaCodec.CryptoException(MediaDrm.ErrorCodes.ERROR_KEY_EXPIRED, "Key expired");
    codecAdapter.notifyCodecError(cryptoException);

    ExoPlaybackException exception =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000));

    assertThat(hasReadStreamToEndBeforeError).isTrue();
    assertThat(exception.errorCode)
        .isEqualTo(PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION);
    assertThat(exception).hasCauseThat().isSameInstanceAs(cryptoException);
  }

  @Test
  public void
      render_withHeldOutputBufferAndEndedInputAndAsyncCodecErrorWithFlagDisabled_defersErrorUntilBufferReleased()
          throws Exception {
    Flags.disableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    codecAdapter.makeInputBuffersAvailable(/* count= */ 2);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    boolean hasReadStreamToEndBeforeError = renderer.hasReadStreamToEnd();
    codecAdapter.enqueueOutputBuffer(/* presentationTimeUs= */ 100);
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    codecAdapter.notifyCodecError(createMediaCodecIllegalStateException());

    // At positionUs=0, the output buffer at 100us is still held and inputStreamEnded is true, so
    // without FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR the pending error is not yet surfaced.
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    ExoPlaybackException exception =
        assertThrows(
            ExoPlaybackException.class,
            () -> renderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000));

    assertThat(hasReadStreamToEndBeforeError).isTrue();
    assertThat(exception.errorCode).isEqualTo(PlaybackException.ERROR_CODE_DECODING_FAILED);
  }

  @Test
  public void render_afterOutputStreamEndedWithAsyncCodecError_doesNotThrow() throws Exception {
    Flags.enableFlag(Flags.FLAG_WAKE_UP_RENDERER_ON_MEDIACODEC_ERROR);
    ReclaimableMediaCodecAdapter codecAdapter = new ReclaimableMediaCodecAdapter();
    codecAdapter.makeInputBuffersAvailable(/* count= */ 2);
    TestRenderer renderer = new TestRenderer(configuration -> codecAdapter);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(AUDIO_AAC, /* sampleTimesUs...= */ 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.setCurrentStreamFinal();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    codecAdapter.enqueueOutputBuffer(/* presentationTimeUs= */ 100);
    renderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000);
    codecAdapter.enqueueEndOfStreamOutputBuffer();
    renderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000);
    boolean endedBeforeError = renderer.isEnded();
    codecAdapter.notifyCodecError(createMediaCodecIllegalStateException());

    renderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000);

    assertThat(endedBeforeError).isTrue();
    assertThat(renderer.isEnded()).isTrue();
  }

  private TestRenderer setUpAndEnableRenderer(Format format) throws Exception {
    MediaCodecAdapter mockCodecAdapter = mock(MediaCodecAdapter.class);
    MediaCodecAdapter.Factory mockCodecAdapterFactory = configuration -> mockCodecAdapter;
    TestRenderer renderer = setUpRenderer(mockCodecAdapterFactory);
    enableRenderer(mockCodecAdapter, renderer, format);
    return renderer;
  }

  private TestRenderer setUpRenderer(MediaCodecAdapter.Factory mockCodecAdapterFactory) {
    TestRenderer testRenderer = spy(new TestRenderer(mockCodecAdapterFactory));
    testRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    return testRenderer;
  }

  private void setUpRendererToTriggerCodecOutputFormatChange(
      TestRenderer renderer, MediaFormat newMediaFormat) {
    MediaCodecAdapter mockCodecAdapter = renderer.getCodec();

    when(mockCodecAdapter.dequeueOutputBufferIndex(any()))
        .thenReturn(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
        .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    when(mockCodecAdapter.getOutputFormat()).thenReturn(newMediaFormat);
  }

  private void enableRenderer(
      MediaCodecAdapter mockCodecAdapter, TestRenderer renderer, Format format) throws Exception {
    when(mockCodecAdapter.dequeueInputBufferIndex()).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
    when(mockCodecAdapter.dequeueOutputBufferIndex(any()))
        .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);

    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(format, /* sampleTimesUs...= */ 0, 100);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
    renderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000 * 1000);
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static void enableAndStartRenderer(
      MediaCodecRenderer renderer, Format format, FakeSampleStream sampleStream) throws Exception {
    enableAndStartRenderer(
        renderer, format, sampleStream, /* positionUs= */ 0, /* startPositionUs= */ 0);
  }

  private static void enableAndStartRenderer(
      MediaCodecRenderer renderer,
      Format format,
      FakeSampleStream sampleStream,
      long positionUs,
      long startPositionUs)
      throws Exception {
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        sampleStream,
        positionUs,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        startPositionUs,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
  }

  private static void stopDisableAndResetRenderer(MediaCodecRenderer renderer) {
    try {
      if (renderer.getState() == Renderer.STATE_STARTED) {
        renderer.stop();
      }
    } finally {
      try {
        if (renderer.getState() == Renderer.STATE_ENABLED) {
          renderer.disable();
        }
      } finally {
        renderer.reset();
      }
    }
  }

  private FakeSampleStream createFakeSampleStream(Format format, long... sampleTimesUs) {
    ImmutableList.Builder<FakeSampleStream.FakeSampleStreamItem> sampleListBuilder =
        ImmutableList.builder();
    for (long sampleTimeUs : sampleTimesUs) {
      sampleListBuilder.add(oneByteSample(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME));
    }
    sampleListBuilder.add(END_OF_STREAM_ITEM);
    return createFakeSampleStream(format, sampleListBuilder.build());
  }

  private static FakeSampleStream createFakeSampleStream(
      Format format, List<FakeSampleStream.FakeSampleStreamItem> sampleItems) {
    FakeSampleStream sampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            sampleItems);
    sampleStream.writeData(/* startPositionUs= */ 0);
    return sampleStream;
  }

  private static IllegalStateException createMediaCodecIllegalStateException() {
    IllegalStateException exception = new IllegalStateException("ISE from inside MediaCodec");
    StackTraceElement[] stackTrace = exception.getStackTrace();
    stackTrace[0] =
        new StackTraceElement(
            "android.media.MediaCodec",
            "fakeMethod",
            stackTrace[0].getFileName(),
            stackTrace[0].getLineNumber());
    exception.setStackTrace(stackTrace);
    return exception;
  }

  private static class TestRenderer extends MediaCodecRenderer {

    public TestRenderer() {
      this(MediaCodecAdapter.Factory.getDefault(ApplicationProvider.getApplicationContext()));
    }

    public TestRenderer(MediaCodecAdapter.Factory mediaCodecAdapterFactory) {
      super(
          ApplicationProvider.getApplicationContext(),
          C.TRACK_TYPE_AUDIO,
          mediaCodecAdapterFactory,
          /* mediaCodecSelector= */ (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
              Collections.singletonList(
                  MediaCodecInfo.newInstance(
                      /* name= */ "name",
                      /* mimeType= */ mimeType,
                      /* codecMimeType= */ mimeType,
                      /* capabilities= */ null,
                      /* hardwareAccelerated= */ false,
                      /* softwareOnly= */ true,
                      /* vendor= */ false)),
          /* enableDecoderFallback= */ false,
          /* assumedMinimumCodecOperatingRate= */ 0);
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
      MediaFormat mediaFormat = new MediaFormat();
      applyCodecParametersToMediaFormat(mediaFormat);
      return MediaCodecAdapter.Configuration.createForAudioDecoding(
          codecInfo, mediaFormat, format, crypto, /* loudnessCodecController= */ null);
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
        if (codec != null) {
          codec.releaseOutputBuffer(bufferIndex, /* render= */ true);
        }
        return true;
      }
      return false;
    }

    @Override
    protected void onCodecParametersChanged(CodecParameters codecParameters) {
      // No-op for this test renderer.
    }

    @Override
    protected DecoderReuseEvaluation canReuseCodec(
        MediaCodecInfo codecInfo,
        Format oldFormat,
        Format newFormat,
        boolean isAdaptiveFormatChange) {
      return codecInfo.canReuseCodec(oldFormat, newFormat);
    }
  }

  /**
   * A {@link MediaCodecAdapter} that throws a pre-specified exception from every decoding-related
   * interaction.
   */
  private static class ThrowingMediaCodecAdapter implements MediaCodecAdapter {

    public static class Factory implements MediaCodecAdapter.Factory {

      private final Supplier<RuntimeException> exceptionSupplier;

      public Factory(Supplier<RuntimeException> exceptionSupplier) {
        this.exceptionSupplier = exceptionSupplier;
      }

      @Override
      public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
        return new ThrowingMediaCodecAdapter(exceptionSupplier);
      }
    }

    private final Supplier<RuntimeException> exceptionSupplier;

    ThrowingMediaCodecAdapter(Supplier<RuntimeException> exceptionSupplier) {
      this.exceptionSupplier = exceptionSupplier;
    }

    @Override
    public int dequeueInputBufferIndex() {
      throw exceptionSupplier.get();
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      throw exceptionSupplier.get();
    }

    @Override
    public MediaFormat getOutputFormat() {
      throw exceptionSupplier.get();
    }

    @Override
    public @Nullable ByteBuffer getInputBuffer(int index) {
      throw exceptionSupplier.get();
    }

    @Override
    public @Nullable ByteBuffer getOutputBuffer(int index) {
      throw exceptionSupplier.get();
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      throw exceptionSupplier.get();
    }

    @Override
    public void queueSecureInputBuffer(
        int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
      throw exceptionSupplier.get();
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      throw exceptionSupplier.get();
    }

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
      throw exceptionSupplier.get();
    }

    @Override
    public void flush() {
      throw exceptionSupplier.get();
    }

    @Override
    public void release() {}

    @Override
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {}

    @Override
    public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
      return false;
    }

    @Override
    public void setOutputSurface(Surface surface) {
      throw exceptionSupplier.get();
    }

    @Override
    public void detachOutputSurface() {
      throw exceptionSupplier.get();
    }

    @Override
    public void setParameters(Bundle params) {
      throw exceptionSupplier.get();
    }

    @Override
    public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
      throw exceptionSupplier.get();
    }

    @Override
    public void subscribeToVendorParameters(List<String> names) {
      throw exceptionSupplier.get();
    }

    @Override
    public void unsubscribeFromVendorParameters(List<String> names) {
      throw exceptionSupplier.get();
    }

    @Override
    public boolean needsReconfiguration() {
      return false;
    }

    @Override
    public PersistableBundle getMetrics() {
      return new PersistableBundle();
    }
  }

  private static final class ReclaimableMediaCodecAdapter implements MediaCodecAdapter {

    private @Nullable RuntimeException pendingException;
    private int availableInputBufferCount;
    private boolean hasAvailableOutputBuffer;
    private int availableOutputSize;
    private long availableOutputPresentationTimeUs;
    private int availableOutputFlags;
    private @Nullable OnBufferAvailableListener onBufferAvailableListener;

    @Override
    public int dequeueInputBufferIndex() {
      maybeThrow();
      if (availableInputBufferCount > 0) {
        availableInputBufferCount--;
        return 0;
      }
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      maybeThrow();
      if (hasAvailableOutputBuffer) {
        hasAvailableOutputBuffer = false;
        bufferInfo.set(
            /* newOffset= */ 0,
            availableOutputSize,
            availableOutputPresentationTimeUs,
            availableOutputFlags);
        return 0;
      }
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }

    @Override
    public MediaFormat getOutputFormat() {
      maybeThrow();
      return new MediaFormat();
    }

    @Override
    public @Nullable ByteBuffer getInputBuffer(int index) {
      maybeThrow();
      return ByteBuffer.allocate(1024);
    }

    @Override
    public <E extends Exception> void useBuffer(ThrowingRunnable<E> runnable) throws E {
      maybeThrow();
      runnable.run();
    }

    @Override
    public void maybeThrowError() {
      maybeThrow();
    }

    @Override
    public @Nullable ByteBuffer getOutputBuffer(int index) {
      maybeThrow();
      return null;
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      maybeThrow();
    }

    @Override
    public void queueSecureInputBuffer(
        int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
      maybeThrow();
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      maybeThrow();
    }

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
      maybeThrow();
    }

    @Override
    public void flush() {
      maybeThrow();
    }

    @Override
    public void release() {}

    @Override
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {}

    @Override
    public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
      this.onBufferAvailableListener = listener;
      return true;
    }

    @Override
    public void setOutputSurface(Surface surface) {
      maybeThrow();
    }

    @Override
    public void detachOutputSurface() {
      maybeThrow();
    }

    @Override
    public void setParameters(Bundle params) {
      maybeThrow();
    }

    @Override
    public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
      maybeThrow();
    }

    @Override
    public void subscribeToVendorParameters(List<String> names) {
      maybeThrow();
    }

    @Override
    public void unsubscribeFromVendorParameters(List<String> names) {
      maybeThrow();
    }

    @Override
    public boolean needsReconfiguration() {
      return false;
    }

    @Override
    public PersistableBundle getMetrics() {
      return new PersistableBundle();
    }

    /* package */ void makeInputBuffersAvailable(int count) {
      availableInputBufferCount = count;
    }

    /* package */ void enqueueOutputBuffer(long presentationTimeUs) {
      hasAvailableOutputBuffer = true;
      availableOutputSize = 1;
      availableOutputPresentationTimeUs = presentationTimeUs;
      availableOutputFlags = 0;
    }

    /* package */ void enqueueEndOfStreamOutputBuffer() {
      hasAvailableOutputBuffer = true;
      availableOutputSize = 0;
      availableOutputPresentationTimeUs = 0;
      availableOutputFlags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
    }

    /* package */ void notifyCodecError(RuntimeException exception) {
      pendingException = exception;
      if (onBufferAvailableListener != null) {
        onBufferAvailableListener.onCodecError();
      }
    }

    private void maybeThrow() {
      @Nullable RuntimeException exception = pendingException;
      if (exception != null) {
        pendingException = null;
        throw exception;
      }
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

  private static class FakeExtensionDecoder
      implements Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private final DecoderInputBuffer decoderInputBuffer;
    private final List<Long> outputStartTimesUs;
    private final List<Boolean> queuedInputKeyFrameFlags;
    private final List<Boolean> queuedInputFirstSampleFlags;
    private final List<Boolean> queuedInputLastSampleFlags;
    private int lastQueuedSize;
    private int flushCount;
    private boolean inputDequeued;
    private boolean pendingEndOfStreamOutput;

    private FakeExtensionDecoder(int initialBufferSize) {
      decoderInputBuffer =
          new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
      if (initialBufferSize > 0) {
        decoderInputBuffer.ensureSpaceForWrite(initialBufferSize);
      }
      outputStartTimesUs = new ArrayList<>();
      queuedInputKeyFrameFlags = new ArrayList<>();
      queuedInputFirstSampleFlags = new ArrayList<>();
      queuedInputLastSampleFlags = new ArrayList<>();
    }

    @Override
    public String getName() {
      return "FakeExtensionDecoder";
    }

    @Override
    public @Nullable DecoderInputBuffer dequeueInputBuffer() throws DecoderException {
      if (inputDequeued) {
        return null;
      }
      inputDequeued = true;
      decoderInputBuffer.clear();
      return decoderInputBuffer;
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) throws DecoderException {
      inputDequeued = false;
      if (inputBuffer.isEndOfStream()) {
        pendingEndOfStreamOutput = true;
      } else {
        queuedInputKeyFrameFlags.add(inputBuffer.isKeyFrame());
        queuedInputFirstSampleFlags.add(inputBuffer.isFirstSample());
        queuedInputLastSampleFlags.add(inputBuffer.isLastSample());
        if (inputBuffer.data != null) {
          lastQueuedSize = inputBuffer.data.remaining();
        }
      }
    }

    @Override
    public @Nullable SimpleDecoderOutputBuffer dequeueOutputBuffer() throws DecoderException {
      if (pendingEndOfStreamOutput) {
        pendingEndOfStreamOutput = false;
        SimpleDecoderOutputBuffer eosBuffer = new SimpleDecoderOutputBuffer(unused -> {});
        eosBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
        return eosBuffer;
      }
      return null;
    }

    @Override
    public void flush() {
      flushCount++;
      inputDequeued = false;
      pendingEndOfStreamOutput = false;
    }

    @Override
    public void release() {}

    @Override
    public void setOutputStartTimeUs(long outputStartTimeUs) {
      outputStartTimesUs.add(outputStartTimeUs);
    }
  }

  @Test
  public void render_formatTransitionFromBypassToCodec_playsSuccessfully() throws Exception {
    Format formatBypass =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    Format formatCodec =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(formatBypass, /* sampleTimesUs...= */ 0, 100);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(formatCodec, /* sampleTimesUs...= */ 0, 100);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        new TestRenderer() {
          @Override
          protected boolean shouldUseBypass(Format format) {
            return Util.areEqual(format.sampleMimeType, MimeTypes.AUDIO_RAW);
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {formatBypass},
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {formatCodec},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);

    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    assertThat(renderer.isEnded()).isTrue();
  }

  @Test
  public void render_formatTransitionFromCodecToBypass_playsSuccessfully() throws Exception {
    Format formatCodec =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(1500).build();
    Format formatBypass =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(formatCodec, /* sampleTimesUs...= */ 0, 100);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(formatBypass, /* sampleTimesUs...= */ 0, 100);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        new TestRenderer() {
          @Override
          protected boolean shouldUseBypass(Format format) {
            return Util.areEqual(format.sampleMimeType, MimeTypes.AUDIO_RAW);
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {formatCodec},
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {formatBypass},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);

    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    assertThat(renderer.isEnded()).isTrue();
  }

  @Test
  public void render_formatTransitionFromBypassToBypass_playsSuccessfully() throws Exception {
    Format formatBypass1 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1000).build();
    Format formatBypass2 =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setAverageBitrate(1500).build();
    FakeSampleStream fakeSampleStream1 =
        createFakeSampleStream(formatBypass1, /* sampleTimesUs...= */ 0, 100);
    FakeSampleStream fakeSampleStream2 =
        createFakeSampleStream(formatBypass2, /* sampleTimesUs...= */ 0, 100);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    MediaCodecRenderer renderer =
        new TestRenderer() {
          @Override
          protected boolean shouldUseBypass(Format format) {
            return Util.areEqual(format.sampleMimeType, MimeTypes.AUDIO_RAW);
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {formatBypass1},
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
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }
    renderer.replaceStream(
        new Format[] {formatBypass2},
        fakeSampleStream2,
        /* startPositionUs= */ 200,
        /* offsetUs= */ 200,
        mediaPeriodId2);

    renderer.setCurrentStreamFinal();
    while (!renderer.isEnded()) {
      renderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 100;
    }

    assertThat(renderer.isEnded()).isTrue();
  }
}
