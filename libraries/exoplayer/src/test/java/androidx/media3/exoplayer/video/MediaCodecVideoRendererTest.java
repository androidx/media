/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static android.media.MediaCodec.INFO_TRY_AGAIN_LATER;
import static android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42;
import static android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
import static android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
import static android.view.Display.DEFAULT_DISPLAY;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.exoplayer.Renderer.STATE_STARTED;
import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.format;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter;
import androidx.media3.exoplayer.source.ClippingMediaPeriod;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.robolectric.IdlingMediaCodecAdapterFactory;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit test for {@link MediaCodecVideoRenderer}. */
@RunWith(AndroidJUnit4.class)
public class MediaCodecVideoRendererTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Format VIDEO_H264 =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(1920)
          .setHeight(1080)
          .build();

  private static final Format VIDEO_AV1 =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_AV1)
          .setWidth(1920)
          .setHeight(1080)
          .build();

  private static final TrackGroup TRACK_GROUP_H264 = new TrackGroup(VIDEO_H264);

  private static final MediaCodecInfo H264_PROFILE8_LEVEL4_HW_MEDIA_CODEC_INFO =
      MediaCodecInfo.newInstance(
          /* name= */ "h264-codec-hw",
          /* mimeType= */ MimeTypes.VIDEO_H264,
          /* codecMimeType= */ MimeTypes.VIDEO_H264,
          /* capabilities= */ createCodecCapabilities(AVCProfileHigh, CodecProfileLevel.AVCLevel4),
          /* hardwareAccelerated= */ true,
          /* softwareOnly= */ false,
          /* vendor= */ false,
          /* forceDisableAdaptive= */ false,
          /* forceSecure= */ false);

  private static final MediaCodecInfo H264_PROFILE8_LEVEL5_SW_MEDIA_CODEC_INFO =
      MediaCodecInfo.newInstance(
          /* name= */ "h264-codec-sw",
          /* mimeType= */ MimeTypes.VIDEO_H264,
          /* codecMimeType= */ MimeTypes.VIDEO_H264,
          /* capabilities= */ createCodecCapabilities(AVCProfileHigh, CodecProfileLevel.AVCLevel5),
          /* hardwareAccelerated= */ false,
          /* softwareOnly= */ true,
          /* vendor= */ false,
          /* forceDisableAdaptive= */ false,
          /* forceSecure= */ false);

  private Looper testMainLooper;
  private Surface surface;
  private MediaCodecVideoRenderer mediaCodecVideoRenderer;
  private MediaCodecSelector mediaCodecSelector;
  private IdlingMediaCodecAdapterFactory codecAdapterFactory;
  @Nullable private Format currentOutputFormat;

  @Mock private VideoRendererEventListener eventListener;

  @Before
  public void setUp() throws Exception {
    testMainLooper = Looper.getMainLooper();
    codecAdapterFactory =
        new IdlingMediaCodecAdapterFactory(ApplicationProvider.getApplicationContext());
    mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
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
                    /* forceSecure= */ false));
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }

          @Override
          protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat) {
            super.onOutputFormatChanged(format, mediaFormat);
            currentOutputFormat = format;
          }
        };

    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    surface = new Surface(new SurfaceTexture(/* texName= */ 0));
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
  }

  @After
  public void cleanUp() {
    surface.release();
  }

  @Test
  public void render_withLateBuffer_dropsBuffer() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000), // Late buffer.
                oneByteSample(/* timeUs= */ 100_000), // Last buffer.
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    int posUs = 80_001; // Ensures buffer will be 30_001us late.
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      posUs += 40_000;
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onDroppedFrames(eq(1), anyLong());
  }

  @Test
  public void render_withVeryLateBuffer_dropsBuffersUpstream() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 20_000))); // Very late buffer.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be 1 second late and new (not yet read) buffers are available
    // to be skipped and to skip to in the input stream.
    int posUs = 1_020_000;
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 30_000),
            oneByteSample(/* timeUs= */ 1_020_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 1_200_000),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the new keyframe has been processed and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 10_000;
    }

    assertThat(decoderCounters.renderedOutputBufferCount).isEqualTo(3);
    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(1);
  }

  @Test
  public void render_earlyWithoutSurfaceAndStarted_skipsBuffer() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Set placeholder surface.
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ true,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    int posUs = 20_001; // Ensures buffer will be 29_999us early.
    mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
  }

  @Test
  public void render_earlyWithoutSurfaceAndNotStarted_doesNotSkipBuffer() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Set placeholder surface.
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ true,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));

    mediaCodecVideoRenderer.setCurrentStreamFinal();
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    int posUs = 20_001; // Ensures buffer will be 29_999us early.
    for (int i = 0; i < 3; i++) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 10_000;
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(0);
  }

  @Test
  public void render_withNewSurfaceAndAlreadyReadyWithPlaceholder_canRenderFirstFrameImmediately()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 10_000),
                oneByteSample(/* timeUs= */ 20_000),
                oneByteSample(/* timeUs= */ 30_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Set placeholder surface.
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
    // Enable at a non-zero start position to ensure the renderer isn't ready with one render call.
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 20_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));
    while (!mediaCodecVideoRenderer.isReady()) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    }
    ShadowLooper.idleMainLooper(); // Ensure all pending events are delivered.
    verify(eventListener, never()).onRenderedFirstFrame(any(), anyLong());

    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    ShadowLooper.idleMainLooper(); // Ensure all pending events are delivered.

    verify(eventListener).onRenderedFirstFrame(any(), anyLong());
  }

  @Test
  public void render_withBufferLimitEqualToNumberOfSamples_rendersLastFrameAfterEndOfStream()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 10_000),
                oneByteSample(/* timeUs= */ 20_000), // Last buffer.
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Seek to time after samples.
    fakeSampleStream.seekToUs(30_000, /* allowTimeBeyondBuffer= */ true);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            new ForwardingSynchronousMediaCodecAdapterWithBufferLimit.Factory(/* bufferLimit= */ 3),
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            /* eventListener= */ eventListener,
            /* maxDroppedFramesToNotify= */ 1);
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 30_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    // Call to render should have read all samples up to but not including the END_OF_STREAM_ITEM.
    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isFalse();
    int posUs = 30_000;
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 40_000;
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_withoutSampleDependencies_rendersLastFrameAfterEndOfStream() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                // Second buffer will be skipped before decoder during a seek.
                oneByteSample(/* timeUs= */ 10_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                // Last buffer without sample dependencies will be rendered.
                oneByteSample(/* timeUs= */ 20_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Seek to time after samples.
    fakeSampleStream.seekToUs(30_000, /* allowTimeBeyondBuffer= */ true);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            new ForwardingSynchronousMediaCodecAdapterWithBufferLimit.Factory(/* bufferLimit= */ 3),
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            eventListener,
            /* maxDroppedFramesToNotify= */ 1);
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 30_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    // Call to render has read all samples including the END_OF_STREAM_ITEM because the
    // previous sample is skipped before decoding.
    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    int posUs = 30_000;
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 40_000;
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    // First key frame is decoded and skipped as an output buffer.
    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    // Second frame is skipped before the decoder, as an input buffer.
    assertThat(argumentDecoderCounters.getValue().skippedInputBufferCount).isEqualTo(1);
    // Last frame is rendered.
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(1);
  }

  @Test
  public void render_withoutSampleDependenciesAndShortDuration_skipsNoDecoderInputBuffers()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(
                /* isSeekable= */ true, /* isDynamic= */ false, /* durationUs= */ 30_000));
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(
                    /* timeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
                    C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(
                    /* timeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 10_000,
                    C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(
                    /* timeUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 20_000,
                    C.BUFFER_FLAG_NOT_DEPENDED_ON),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Seek to time after samples.
    fakeSampleStream.seekToUs(30_000, /* allowTimeBeyondBuffer= */ true);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            new ForwardingSynchronousMediaCodecAdapterWithBufferLimit.Factory(/* bufferLimit= */ 5),
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            /* eventListener= */ eventListener,
            /* maxDroppedFramesToNotify= */ 1);
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 30_000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    // Call to render has read all samples including the END_OF_STREAM_ITEM.
    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    int posUs = 30_000;
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 40_000;
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().skippedInputBufferCount).isEqualTo(0);
  }

  @Test
  public void
      render_withClippingMediaPeriodAndBufferContainingLastAndClippingSamples_rendersLastFrame()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    // Set up MediaPeriod with samples.
    MediaSource.MediaPeriodId fakeMediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    FakeMediaPeriod mediaPeriod =
        new FakeMediaPeriod(
            new TrackGroupArray(TRACK_GROUP_H264),
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* trackDataFactory= */ (format, mediaPeriodId) -> ImmutableList.of(),
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, fakeMediaPeriodId),
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* deferOnPrepared= */ false) {
          @Override
          protected FakeSampleStream createSampleStream(
              Allocator allocator,
              @Nullable MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
              DrmSessionManager drmSessionManager,
              DrmSessionEventListener.EventDispatcher drmEventDispatcher,
              Format initialFormat,
              List<FakeSampleStream.FakeSampleStreamItem> fakeSampleStreamItems) {
            FakeSampleStream fakeSampleStream =
                new FakeSampleStream(
                    allocator,
                    mediaSourceEventDispatcher,
                    drmSessionManager,
                    drmEventDispatcher,
                    initialFormat,
                    /* fakeSampleStreamItems= */ ImmutableList.of(
                        oneByteSample(/* timeUs= */ 90, C.BUFFER_FLAG_KEY_FRAME),
                        oneByteSample(/* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME),
                        END_OF_STREAM_ITEM));
            fakeSampleStream.writeData(0);
            return fakeSampleStream;
          }
        };
    ClippingMediaPeriod clippingMediaPeriod =
        new ClippingMediaPeriod(
            mediaPeriod,
            /* enableInitialDiscontinuity= */ true,
            /* startUs= */ 0,
            /* endUs= */ 100);
    AtomicBoolean periodPrepared = new AtomicBoolean();
    clippingMediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            periodPrepared.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            clippingMediaPeriod.continueLoading(
                new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
          }
        },
        /* positionUs= */ 100);
    RobolectricUtil.runMainLooperUntil(periodPrepared::get);
    SampleStream[] sampleStreams = new SampleStream[1];
    clippingMediaPeriod.selectTracks(
        new ExoTrackSelection[] {new FixedTrackSelection(TRACK_GROUP_H264, /* track= */ 0)},
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 100);
    clippingMediaPeriod.readDiscontinuity();
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            new ForwardingSynchronousMediaCodecAdapterWithBufferLimit.Factory(/* bufferLimit= */ 3),
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            /* eventListener= */ eventListener,
            /* maxDroppedFramesToNotify= */ 1);
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        sampleStreams[0],
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 100,
        /* offsetUs= */ 0,
        fakeMediaPeriodId);

    mediaCodecVideoRenderer.start();
    // Call to render should have read all samples up before endUs.
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000L);
    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    // Following call to render should force-render last frame.
    mediaCodecVideoRenderer.render(100, SystemClock.elapsedRealtime() * 1000L);
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(1);
  }

  @Test
  public void render_withClippingMediaPeriodSetCurrentStreamFinal_rendersLastFrame()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    // Set up MediaPeriod with samples.
    MediaSource.MediaPeriodId fakeMediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    FakeMediaPeriod mediaPeriod =
        new FakeMediaPeriod(
            new TrackGroupArray(TRACK_GROUP_H264),
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* trackDataFactory= */ (format, mediaPeriodId) -> ImmutableList.of(),
            new MediaSourceEventListener.EventDispatcher()
                .withParameters(/* windowIndex= */ 0, fakeMediaPeriodId),
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* deferOnPrepared= */ false) {
          @Override
          protected FakeSampleStream createSampleStream(
              Allocator allocator,
              @Nullable MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
              DrmSessionManager drmSessionManager,
              DrmSessionEventListener.EventDispatcher drmEventDispatcher,
              Format initialFormat,
              List<FakeSampleStream.FakeSampleStreamItem> fakeSampleStreamItems) {
            FakeSampleStream fakeSampleStream =
                new FakeSampleStream(
                    allocator,
                    mediaSourceEventDispatcher,
                    drmSessionManager,
                    drmEventDispatcher,
                    initialFormat,
                    /* fakeSampleStreamItems= */ ImmutableList.of(
                        oneByteSample(/* timeUs= */ 90, C.BUFFER_FLAG_KEY_FRAME),
                        oneByteSample(/* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME),
                        END_OF_STREAM_ITEM));
            fakeSampleStream.writeData(0);
            return fakeSampleStream;
          }
        };
    ClippingMediaPeriod clippingMediaPeriod =
        new ClippingMediaPeriod(
            mediaPeriod,
            /* enableInitialDiscontinuity= */ true,
            /* startUs= */ 0,
            /* endUs= */ 100);
    AtomicBoolean periodPrepared = new AtomicBoolean();
    clippingMediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            periodPrepared.set(true);
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            clippingMediaPeriod.continueLoading(
                new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
          }
        },
        /* positionUs= */ 100);
    RobolectricUtil.runMainLooperUntil(periodPrepared::get);
    SampleStream[] sampleStreams = new SampleStream[1];
    clippingMediaPeriod.selectTracks(
        new ExoTrackSelection[] {new FixedTrackSelection(TRACK_GROUP_H264, /* track= */ 0)},
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 100);
    clippingMediaPeriod.readDiscontinuity();
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            new ForwardingSynchronousMediaCodecAdapterWithBufferLimit.Factory(/* bufferLimit= */ 3),
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            /* eventListener= */ eventListener,
            /* maxDroppedFramesToNotify= */ 1);
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        sampleStreams[0],
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 100,
        /* offsetUs= */ 0,
        fakeMediaPeriodId);

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Call to render should have read all samples up before endUs.
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000L);
    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    // Following call to render should force-render last frame.
    mediaCodecVideoRenderer.render(100, SystemClock.elapsedRealtime() * 1000L);
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing propagation of deprecated unappliedRotationDegrees.
  public void render_sendsVideoSizeChangeWithCurrentFormatValues() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    mediaCodecVideoRenderer.start();

    int positionUs = 0;
    do {
      mediaCodecVideoRenderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 10;
    } while (!mediaCodecVideoRenderer.isEnded());
    shadowOf(testMainLooper).idle();

    verify(eventListener)
        .onVideoSizeChanged(
            new VideoSize(VIDEO_H264.width, VIDEO_H264.height, VIDEO_H264.pixelWidthHeightRatio));
  }

  @Test
  public void
      render_withMultipleQueued_sendsVideoSizeChangedWithCorrectPixelAspectRatioWhenMultipleQueued()
          throws Exception {
    Format pAsp1 = VIDEO_H264.buildUpon().setPixelWidthHeightRatio(1f).build();
    Format pAsp2 = VIDEO_H264.buildUpon().setPixelWidthHeightRatio(2f).build();
    Format pAsp3 = VIDEO_H264.buildUpon().setPixelWidthHeightRatio(3f).build();

    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ pAsp1,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    SystemClock.setCurrentTimeMillis(876_000_000);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {pAsp1, pAsp2, pAsp3},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(/* positionUs= */ 0, msToUs(SystemClock.elapsedRealtime()));
    ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS);
    mediaCodecVideoRenderer.render(/* positionUs= */ 10_000, msToUs(SystemClock.elapsedRealtime()));

    fakeSampleStream.append(
        ImmutableList.of(
            format(pAsp2),
            oneByteSample(/* timeUs= */ 20_000),
            oneByteSample(/* timeUs= */ 40_000),
            format(pAsp3),
            oneByteSample(/* timeUs= */ 60_000),
            oneByteSample(/* timeUs= */ 80_000),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 20_000);
    mediaCodecVideoRenderer.setCurrentStreamFinal();

    int positionUs = 20_000;
    do {
      ShadowSystemClock.advanceBy(2, TimeUnit.MILLISECONDS);
      mediaCodecVideoRenderer.render(positionUs, msToUs(SystemClock.elapsedRealtime()));
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      positionUs += 2_000;
    } while (!mediaCodecVideoRenderer.isEnded());
    shadowOf(testMainLooper).idle();

    ArgumentCaptor<VideoSize> videoSizesCaptor = ArgumentCaptor.forClass(VideoSize.class);
    verify(eventListener, times(3)).onVideoSizeChanged(videoSizesCaptor.capture());
    assertThat(
            videoSizesCaptor.getAllValues().stream()
                .map(videoSize -> videoSize.pixelWidthHeightRatio)
                .collect(Collectors.toList()))
        .containsExactly(1f, 2f, 3f);
  }

  @Test
  public void render_includingResetPosition_keepsOutputFormatInVideoFrameMetadataListener()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    mediaCodecVideoRenderer.resetPosition(0, /* sampleStreamIsResetToKeyFrame= */ true);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    int positionUs = 10;
    do {
      mediaCodecVideoRenderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 10;
    } while (!mediaCodecVideoRenderer.isEnded());
    shadowOf(testMainLooper).idle();

    assertThat(currentOutputFormat).isEqualTo(VIDEO_H264);
  }

  @Test
  public void render_withLateBufferWithoutDependencies_dropsInputBuffers() throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(
                /* isSeekable= */ true, /* isDynamic= */ false, /* durationUs= */ 1_000_000));
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(50_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be ~280ms late and new (not yet read) buffers are available
    // to be dropped.
    int posUs = 300_000;
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 30_000, C.BUFFER_FLAG_NOT_DEPENDED_ON), // Dropped on input.
            oneByteSample(/* timeUs= */ 300_000), // Caught up - render.
            oneByteSample(/* timeUs= */ 500_000), // Last buffer is always rendered.
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the non-dropped frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(2);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(2);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_withLateAV1BufferWithoutDependencies_dropsInputBuffers() throws Exception {
    // ShadowMediaCodec does not respect the MediaFormat.KEY_MAX_INPUT_SIZE value requested
    // so we have to specify large buffers here.
    ShadowMediaCodec.addDecoder(
        "name",
        new ShadowMediaCodec.CodecConfig(
            /* inputBufferSize= */ 2_000_000,
            /* outputBufferSize= */ 2_000_000,
            /* codec= */ (in, out) -> {}));
    byte[] syncFrameBytes =
        createByteArray(
            0x0A, 0x0E, 0x00, 0x00, 0x00, 0x24, 0xC6, 0xAB, 0xDF, 0x3E, 0xFE, 0x24, 0x04, 0x04,
            0x04, 0x10, 0x32, 0x32, 0x10, 0x00, 0xC8, 0xC6, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00,
            0x12, 0x03, 0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x34, 0x88, 0x82, 0x3E, 0x0D, 0x3E,
            0xC2, 0x98, 0x91, 0x6A, 0x5C, 0x80, 0x03, 0xCE, 0x0A, 0x5C, 0x9B, 0xB6, 0x7C, 0x48,
            0x35, 0x54, 0xD8, 0x9D, 0x6C, 0x37, 0xD3, 0x4C, 0x4E, 0xD4, 0x6F, 0xF4);
    byte[] notDependedOnFrameBytes =
        createByteArray(
            0x32, 0x1A, 0x30, 0xC0, 0x00, 0x1D, 0x66, 0x68, 0x46, 0xC9, 0x38, 0x00, 0x60, 0x10,
            0x20, 0x80, 0x20, 0x00, 0x00, 0x01, 0x8B, 0x7A, 0x87, 0xF9, 0xAA, 0x2D, 0x0F, 0x2C);
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(
                /* isSeekable= */ true, /* isDynamic= */ false, /* durationUs= */ 1_000_000));
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_AV1,
            ImmutableList.of(
                sample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, syncFrameBytes), // First frame
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(50_000)
                .experimentalSetParseAv1SampleDependencies(true)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_AV1},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be ~280ms late and new (not yet read) buffers are available
    // to be dropped.
    int posUs = 300_000;
    fakeSampleStream.append(
        ImmutableList.of(
            sample(
                /* timeUs= */ 30_000, /* flags= */ 0, notDependedOnFrameBytes), // Dropped on input.
            oneByteSample(/* timeUs= */ 300_000), // Caught up - render.
            oneByteSample(/* timeUs= */ 500_000), // Last buffer is always rendered.
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the non-dropped frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(2);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(2);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  // TODO: b/390604981 - Run the test on older SDK levels to ensure it uses a MediaCodec shadow
  // with more than one buffer slot.
  @Config(minSdk = 30)
  @Test
  public void render_afterVeryLateBuffer_doesNotDropInputBuffers() throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(
                /* isSeekable= */ true, /* isDynamic= */ false, /* durationUs= */ 2_000_000));
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 20_000))); // Very late buffer.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(50_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be 1 second late and new (not yet read) buffers are available
    // to be skipped and to skip to in the input stream.
    int posUs = 1_020_000;
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 30_000), // Dropped input buffer when skipping to keyframe.
            oneByteSample(/* timeUs= */ 1_020_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 1_040_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 1_060_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 2_000_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the new keyframe has been processed and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 1_000;
    }

    assertThat(decoderCounters.renderedOutputBufferCount).isEqualTo(5);
    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(1);
  }

  @Test
  public void render_afterVeryLateBuffer_countsDroppedInputBuffersCorrectly() throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(
                /* isSeekable= */ true, /* isDynamic= */ false, /* durationUs= */ 3_000_000));
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 20_000))); // Late buffer.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(-5_000_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(10_000, SystemClock.elapsedRealtime() * 1000);
    }
    fakeSampleStream.append(
        ImmutableList.of(
            // Dropped input buffer.
            oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 300_000), // Render.
            oneByteSample(/* timeUs= */ 400_000) // Very late buffer.
            ));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    // Render until the frame at time 300_000 us is displayed.
    int posUs = 300_000;
    while (decoderCounters.renderedOutputBufferCount < 2) {
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(
                /* timeUs= */ 1_000_000), // Dropped input buffer when skipping to keyframe.
            oneByteSample(/* timeUs= */ 2_020_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 3_000_000),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    posUs = 2_020_000;
    while (decoderCounters.renderedOutputBufferCount + decoderCounters.skippedOutputBufferCount
        < 3) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 1_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(2);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(1);
  }

  @Test
  public void
      render_withLateBufferAndOutOfOrderSamplesWithoutDependencies_dropsInputBuffersAndRendersLast()
          throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(offsetUs, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(offsetUs + 10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be ~280ms late and new (not yet read) buffers are available
    // to be dropped.
    // The last two processed buffers have (pts, early) = [(0, -10_000), (20_000, -280_000)]
    // VideoFrameReleaseEarlyTimeForecaster will compute the rate of change as
    // SMOOTHING_FACTOR * 0 + (1 - SMOOTHING_FACTOR) * 1 = 0.8
    // VideoFrameReleaseEarlyTimeForecaster assumes realtime processing, so the predicted earlyUs
    // will be on a line passing through (20_000, -280_000) with slope 0.8.
    // That is, earlyUs(X) = -280_000 + (x - 20_000) * 0.8;
    // earlyUs(330_000) = -32_000
    long posUs = offsetUs + 300_000;
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 300_000), // Render.
            oneByteSample(/* timeUs= */ 320_000), // Render.
            // Drop consecutive input buffers that aren't consecutive output buffers.
            oneByteSample(/* timeUs= */ 310_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 330_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            // Last buffer is always rendered.
            oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the first frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(2);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(3);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_samplesWithoutDependencies_afterStop_doesNotDropInputBuffers()
      throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(offsetUs, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(offsetUs + 10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Make sure that stopping / starting the renderer resets the forecaster state and doesn't drop
    // future frames. If not reset, forecaster would predict earlyUs(330_000) = -32_000 and input
    // buffers would be dropped.
    mediaCodecVideoRenderer.stop();
    mediaCodecVideoRenderer.start();
    long posUs = offsetUs + 300_000;
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 300_000),
            oneByteSample(/* timeUs= */ 310_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 320_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 330_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the first frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_samplesWithoutDependencies_afterFormatChange_doesNotDropInputBuffers()
      throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(offsetUs, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(offsetUs + 10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Make sure that changing the video format resets the forecaster state and doesn't drop
    // future frames. If not reset, forecaster would predict earlyUs(330_000) = -32_000 and input
    // buffers would be dropped.
    long posUs = offsetUs + 300_000;
    fakeSampleStream.append(
        ImmutableList.of(
            format(VIDEO_H264.buildUpon().setWidth(1280).build()),
            oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
            oneByteSample(/* timeUs= */ 310_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 320_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 330_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the first frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_samplesWithoutDependencies_afterReplaceStream_doesNotDropInputBuffers()
      throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 310_000, C.BUFFER_FLAG_KEY_FRAME), // Render.
                oneByteSample(/* timeUs= */ 320_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 330_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(offsetUs, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(offsetUs + 10_000, SystemClock.elapsedRealtime() * 1000);
    }
    long posUs = offsetUs + 300_000;
    fakeSampleStream.append(
        ImmutableList.of(oneByteSample(/* timeUs= */ 300_000), END_OF_STREAM_ITEM)); // Render.
    while (decoderCounters.droppedBufferCount == 0) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    // Make sure that replacing the stream resets the forecaster state. If not reset, the forecaster
    // would predict earlyUs(330_000) = -32_000 and input buffers would be dropped.
    mediaCodecVideoRenderer.replaceStream(
        new Format[] {VIDEO_H264},
        fakeSampleStream2,
        /* startPositionUs= */ posUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(/* periodIndex= */ 0)));
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the first frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_afterSeek_samplesWithoutDependencies_doesNotDropFrames() throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 400_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                oneByteSample(/* timeUs= */ 600_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();
    mediaCodecVideoRenderer.setCurrentStreamFinal();

    mediaCodecVideoRenderer.start();
    long posUs = offsetUs;
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }
    // Seek to the beginning.
    seekToUs(
        mediaCodecVideoRenderer,
        /* scrubbingModeParameters= */ null,
        fakeSampleStream,
        /* positionUs= */ offsetUs);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    posUs = offsetUs;
    // Output one more frame before starting the renderer.
    while (decoderCounters.renderedOutputBufferCount + decoderCounters.skippedOutputBufferCount
        == 7) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    mediaCodecVideoRenderer.start();
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(0);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void
      render_setPlaybackSpeedWithLateBufferAndOutOfOrderSamplesWithoutDependencies_rendersFramesAsExpected()
          throws Exception {
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(1_000_000).build());
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(
                    /* timeUs= */ 20_000))); // Late buffer triggers input buffer dropping.
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
                .setCodecAdapterFactory(
                    new ForwardingSynchronousMediaCodecAdapterWithReordering.Factory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(0)
                .setEnableDecoderFallback(false)
                .setEventHandler(new Handler(testMainLooper))
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(1)
                .experimentalSetLateThresholdToDropDecoderInputUs(30_000)) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };

    long offsetUs = 1_000_000_000L;
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    shadowOf(testMainLooper).idle();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    DecoderCounters decoderCounters = argumentDecoderCounters.getValue();

    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(offsetUs, SystemClock.elapsedRealtime() * 1000);
    while (decoderCounters.renderedOutputBufferCount == 0) {
      mediaCodecVideoRenderer.render(offsetUs + 10_000, SystemClock.elapsedRealtime() * 1000);
    }
    // Ensure existing buffer will be ~280ms late and new (not yet read) buffers are available
    // to be dropped.
    // The last two processed buffers have (pts, early) = [(0, -10_000), (20_000, -280_000)]
    // VideoFrameReleaseEarlyTimeForecaster will compute the rate of change as
    // SMOOTHING_FACTOR * 0 + (1 - SMOOTHING_FACTOR) * 1 = 0.8
    // VideoFrameReleaseEarlyTimeForecaster assumes realtime processing, so the predicted earlyUs
    // will be on a line passing through (20_000, -280_000) with slope 0.8.
    // That is, earlyUs(X) = -280_000 + (x - 20_000) * 0.8;
    // earlyUs(330_000) = -32_000
    long posUs = offsetUs + 300_000;
    // Change playback speed to reset the frame early forecaster state.
    mediaCodecVideoRenderer.setPlaybackSpeed(
        /* currentPlaybackSpeed= */ 0.9f, /* targetPlaybackSpeed= */ 0.9f);
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 300_000), // Render.
            oneByteSample(/* timeUs= */ 320_000), // Render.
            oneByteSample(/* timeUs= */ 310_000, C.BUFFER_FLAG_NOT_DEPENDED_ON), // Render.
            oneByteSample(/* timeUs= */ 330_000, C.BUFFER_FLAG_NOT_DEPENDED_ON), // Render.
            // Last buffer is always rendered.
            oneByteSample(/* timeUs= */ 500_000, C.BUFFER_FLAG_NOT_DEPENDED_ON),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    // Render until the first frame is reached and then increase time to reach the end.
    while (decoderCounters.renderedOutputBufferCount < 2) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
    }
    while (!mediaCodecVideoRenderer.isEnded()) {
      mediaCodecVideoRenderer.render(posUs, SystemClock.elapsedRealtime() * 1000);
      posUs += 2_000;
    }
    shadowOf(testMainLooper).idle();

    assertThat(decoderCounters.droppedInputBufferCount).isEqualTo(0);
    assertThat(decoderCounters.droppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.maxConsecutiveDroppedBufferCount).isEqualTo(1);
    assertThat(decoderCounters.droppedToKeyframeCount).isEqualTo(0);
  }

  @Test
  public void render_afterSeekWithFlushingEnabled_rendersFramesAsExpected() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Set scrubbing mode but with flushing enabled.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(false)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 200_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void
      render_afterSeekWithFlushingDisabledAndNonZeroMaxNumReorderSamples_rendersFramesAsExpected()
          throws Exception {
    Format h264FormatWithNonZeroMaxNumReorderSamples =
        VIDEO_H264.buildUpon().setMaxNumReorderSamples(2).build();
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ h264FormatWithNonZeroMaxNumReorderSamples,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {h264FormatWithNonZeroMaxNumReorderSamples},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Set scrubbing mode with flushing disabled.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 200_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeekWithFlushingDisabled_rendersFramesAsExpected() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing so that the seek will skip the 50_000 us sample.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 200_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(2);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeekBackwardsWithFlushingDisabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 150_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 150_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Skip first three samples.
    mediaCodecVideoRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 100_000);

    for (int i = 0; i < 7; i++) {
      mediaCodecVideoRenderer.render(100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(6);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void
      render_afterSeekBackwardsWithFlushingDisabledToSamePresentationTimeUs_rendersFramesAsExpected()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 100_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 100_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Skip first three samples.
    mediaCodecVideoRenderer.start();
    for (int i = 0; i < 8; i++) {
      mediaCodecVideoRenderer.render(100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 150_000);

    for (int i = 0; i < 4; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(3);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void
      render_afterSeekBackwardsWithFlushingDisabledAndZeroInputBuffersQueued_rendersFramesAsExpected()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM)) {
          @Override
          public int readData(
              FormatHolder formatHolder, DecoderInputBuffer buffer, @ReadFlags int readFlags) {
            int result = super.readData(formatHolder, buffer, readFlags);
            if (result == C.RESULT_BUFFER_READ && buffer.timeUs >= 200_000) {
              return C.RESULT_NOTHING_READ;
            }
            return result;
          }
        };
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 150_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 150_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Skip first three samples.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 100_000);

    for (int i = 0; i < 7; i++) {
      mediaCodecVideoRenderer.render(100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(5);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeekBackwardsTwiceWithFlushingDisabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 350_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 200_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 200_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    mediaCodecVideoRenderer.start();
    for (int i = 0; i < 12; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing and seek should be ready to process the 150_000 us sample.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 150_000);
    for (int i = 0; i < 4; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Seek should skip 200_000 us sample.
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 50_000);
    for (int i = 0; i < 6; i++) {
      mediaCodecVideoRenderer.render(50_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(7);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(3);
  }

  @Test
  public void render_afterSeekBackwardsFromLastSampleWithFlushingDisabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 200_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 200_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    assertThat(mediaCodecVideoRenderer.isEnded()).isFalse();

    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 100_000);

    for (int i = 0; i < 8; i++) {
      mediaCodecVideoRenderer.render(100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(5);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeekBackwardsFromEoSWithFlushingDisabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 200_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 200_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    mediaCodecVideoRenderer.setCurrentStreamFinal();
    mediaCodecVideoRenderer.start();
    for (int i = 0; i < 12; i++) {
      mediaCodecVideoRenderer.render(200_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    assertThat(mediaCodecVideoRenderer.hasReadStreamToEnd()).isTrue();
    assertThat(mediaCodecVideoRenderer.isEnded()).isTrue();

    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 100_000);

    for (int i = 0; i < 8; i++) {
      mediaCodecVideoRenderer.render(100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(5);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeekToEoSWithFlushingDisabled_rendersFramesAsExpected() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing so that the seek will skip the 50_000 us sample.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 300_000);

    for (int i = 0; i < 7; i++) {
      mediaCodecVideoRenderer.render(300_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(3);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeeksWithFlushingDisabledThenEnabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 350_000),
                oneByteSample(/* timeUs= */ 400_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing so that the seek will skip the 50_000 us sample.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 200_000);

    // Enable flushing so that new seek will flush the 50_000 us sample.
    scrubbingModeParameters =
        scrubbingModeParameters.buildUpon().setAllowSkippingMediaCodecFlush(false).build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 350_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(350_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void render_afterSeeksWithFlushingEnabledThenDisabled_rendersFramesAsExpected()
      throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 350_000),
                oneByteSample(/* timeUs= */ 400_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Enable flushing so that new seek will flush the 50_000 us sample.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(false)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 200_000);

    // Disable flushing but there should be zero samples to drop with the seek.
    scrubbingModeParameters =
        scrubbingModeParameters.buildUpon().setAllowSkippingMediaCodecFlush(true).build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 350_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(350_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void
      render_afterSeekWithFlushingDisabledAndSkippedFlushOffsetOverflow_rendersFramesAsExpected()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 350_000),
                oneByteSample(/* timeUs= */ 400_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeSampleStream.seekToUs(/* positionUs= */ 150_000, true);
    long duration = (Long.MAX_VALUE - 200_001) / 2 - 123 * C.MICROS_PER_SECOND;
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .build()
                .buildUpon()
                .setDurationUs(duration)
                .build());
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 150_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 150_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing and seek to 0.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer, scrubbingModeParameters, fakeSampleStream, /* positionUs= */ 0);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    // Seek to 300_000 but because skippedOffset has potential for overflow, the codec will be
    // flushed and the 50_000 us sample will be dropped.
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 300_000);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(300_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(3);
  }

  @Test
  public void
      render_afterSeekWithFlushingDisabledAndLastSampleFlushResetsSkippedFlushOffset_rendersFramesAsExpected()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                oneByteSample(/* timeUs= */ 300_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 350_000),
                oneByteSample(/* timeUs= */ 400_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeSampleStream.seekToUs(/* positionUs= */ 150_000, true);
    long duration = (Long.MAX_VALUE - 250_002) / 2 - 123 * C.MICROS_PER_SECOND;
    FakeTimeline fakeTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .build()
                .buildUpon()
                .setDurationUs(duration)
                .build());
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 150_000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 150_000,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(150_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Disable flushing and seek to 0 thereby incrementing skipFlushOffset to 200_001.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(true)
            .setAllowSkippingKeyFrameReset(false)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer, scrubbingModeParameters, fakeSampleStream, /* positionUs= */ 0);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    // Seek to the last sample, 400_000 us, to ensure that subsequent seek will flush and reset
    // the skippedFlushOffset.
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ 400_000);

    for (int i = 0; i < 7; i++) {
      mediaCodecVideoRenderer.render(400_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    // Seek to the same presentation time thrice to increase the skippedFlushOffset by 100_002 us;
    for (int i = 0; i < 3; i++) {
      seekToUs(
          mediaCodecVideoRenderer,
          scrubbingModeParameters,
          fakeSampleStream,
          /* positionUs= */ 300_000);
      for (int j = 0; j < 5; j++) {
        mediaCodecVideoRenderer.render(300_000, SystemClock.elapsedRealtime() * 1000);
        codecAdapterFactory.idleQueueingAndCallbackThreads();
      }
      shadowOf(testMainLooper).idle();
    }

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(6);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(6);
  }

  @Test
  public void render_afterSeekWithSkipKeyFrameReset_rendersFramesAsExpected() throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Enable skip key frame reset
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder().setAllowSkippingKeyFrameReset(true).build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    long seekPositionUs = 100_000;
    assertThat(mediaCodecVideoRenderer.supportsResetPositionWithoutKeyFrameReset(seekPositionUs))
        .isTrue();
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream,
        /* positionUs= */ seekPositionUs);

    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(seekPositionUs, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());

    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(1);
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(2);
  }

  @Test
  public void
      render_withSeekAfterReplaceStreamAndSkipKeyFrameReset_doesNotRenderFirstFrameOfNewStream()
          throws Exception {
    ArgumentCaptor<DecoderCounters> argumentDecoderCounters =
        ArgumentCaptor.forClass(DecoderCounters.class);
    ShadowLooper shadowLooper = shadowOf(testMainLooper);
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 300_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 300_000),
                oneByteSample(/* timeUs= */ 600_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    mediaCodecVideoRenderer.start();
    boolean replacedStream = false;
    for (int i = 0; i < 8; i++) {
      mediaCodecVideoRenderer.render(
          /* positionUs= */ i * 100_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      if (!replacedStream && mediaCodecVideoRenderer.hasReadStreamToEnd()) {
        mediaCodecVideoRenderer.replaceStream(
            new Format[] {VIDEO_H264},
            fakeSampleStream2,
            /* startPositionUs= */ 2_000_000,
            /* offsetUs= */ 2_000_000,
            mediaPeriodId2);
        replacedStream = true;
      }
    }

    shadowLooper.idle();
    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());

    // Skip keyframe reset to ensure first frames of new stream are decode-only.
    ScrubbingModeParameters scrubbingModeParameters =
        new ScrubbingModeParameters.Builder()
            .setAllowSkippingMediaCodecFlush(false)
            .setAllowSkippingKeyFrameReset(true)
            .build();
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_SCRUBBING_MODE, scrubbingModeParameters);
    seekToUs(
        mediaCodecVideoRenderer,
        scrubbingModeParameters,
        fakeSampleStream2,
        /* positionUs= */ 2_600_000);
    for (int i = 0; i < 6; i++) {
      mediaCodecVideoRenderer.render(
          /* positionUs= */ 2_600_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    shadowLooper.idle();
    verify(eventListener).onVideoEnabled(argumentDecoderCounters.capture());
    assertThat(argumentDecoderCounters.getValue().renderedOutputBufferCount).isEqualTo(3);
    assertThat(argumentDecoderCounters.getValue().skippedOutputBufferCount).isEqualTo(2);
    verify(eventListener, times(2))
        .onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void supportsResetPositionWithoutKeyFrameReset_withEarlierPosition_returnsFalse()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    assertThat(mediaCodecVideoRenderer.supportsResetPositionWithoutKeyFrameReset(50_000L)).isTrue();
  }

  @Test
  public void supportsResetPositionWithoutKeyFrameReset_withAdvancingPosition_returnsTrue()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000),
                oneByteSample(/* timeUs= */ 100_000),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000),
                oneByteSample(/* timeUs= */ 250_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    FakeTimeline fakeTimeline = new FakeTimeline();
    mediaCodecVideoRenderer.setTimeline(fakeTimeline);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        /* mediaPeriodId= */ new MediaSource.MediaPeriodId(fakeTimeline.getUidOfPeriod(0)));
    // Render first sample and decode the second.
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(0, SystemClock.elapsedRealtime() * 1000);
    for (int i = 0; i < 5; i++) {
      mediaCodecVideoRenderer.render(40_000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    assertThat(mediaCodecVideoRenderer.supportsResetPositionWithoutKeyFrameReset(100_000)).isTrue();
  }

  @Test
  public void enable_withMayRenderStartOfStream_rendersFirstFrameBeforeStart() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Config(minSdk = 30)
  @Test
  public void enable_withoutMayRenderStartOfStream_doesNotRenderFirstFrameBeforeStart()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener, never()).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void enable_withoutMayRenderStartOfStream_rendersFirstFrameAfterStart() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecVideoRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void enable_withPrerollSamplesLessThanStartPosition_rendersFirstFrame() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ -500, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 500),
                oneByteSample(/* timeUs= */ 1500)));
    fakeSampleStream.writeData(/* startPositionUs= */ -500);

    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 2000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 2000,
        /* offsetUs= */ 1000,
        new MediaSource.MediaPeriodId(new Object()));
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }
    shadowOf(testMainLooper).idle();

    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void replaceStream_rendersFirstFrameOnlyAfterStartPosition() throws Exception {
    ShadowLooper shadowLooper = shadowOf(testMainLooper);
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 1_000_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    mediaCodecVideoRenderer.start();

    boolean replacedStream = false;
    // Render to just before the specified start position.
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(
          /* positionUs= */ i * 10, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      if (!replacedStream && mediaCodecVideoRenderer.hasReadStreamToEnd()) {
        mediaCodecVideoRenderer.replaceStream(
            new Format[] {VIDEO_H264},
            fakeSampleStream2,
            /* startPositionUs= */ 100,
            /* offsetUs= */ 50,
            mediaPeriodId2);
        replacedStream = true;
      }
    }

    // Assert that only one first frame was rendered so far.
    shadowLooper.idle();
    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());

    // Render at start position.
    mediaCodecVideoRenderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000);

    // Assert the new first frame was rendered.
    shadowLooper.idle();
    verify(eventListener, times(2))
        .onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void replaceStream_whenNotStarted_doesNotRenderFirstFrameOfNewStream() throws Exception {
    ShadowLooper shadowLooper = shadowOf(testMainLooper);
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);

    boolean replacedStream = false;
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(
          /* positionUs= */ i * 10, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
      if (!replacedStream && mediaCodecVideoRenderer.hasReadStreamToEnd()) {
        mediaCodecVideoRenderer.replaceStream(
            new Format[] {VIDEO_H264},
            fakeSampleStream2,
            /* startPositionUs= */ 100,
            /* offsetUs= */ 100,
            mediaPeriodId2);
        replacedStream = true;
      }
    }

    shadowLooper.idle();
    verify(eventListener).onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());

    // Render to streamOffsetUs and verify the new first frame gets rendered.
    mediaCodecVideoRenderer.render(/* positionUs= */ 100, SystemClock.elapsedRealtime() * 1000);

    shadowLooper.idle();
    verify(eventListener, times(2))
        .onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void resetPosition_toBeforeOriginalStartPosition_rendersFirstFrame() throws Exception {
    ShadowLooper shadowLooper = shadowOf(testMainLooper);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            ImmutableList.of(oneByteSample(/* timeUs= */ 1000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 1000,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 1000,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecVideoRenderer.start();
    // Render at the original start position.
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 1000, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Reset the position to before the original start position and render at this position.
    mediaCodecVideoRenderer.resetPosition(500, /* sampleStreamIsResetToKeyFrame= */ true);
    fakeSampleStream.append(
        ImmutableList.of(oneByteSample(/* timeUs= */ 500, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 500);
    for (int i = 0; i < 10; i++) {
      mediaCodecVideoRenderer.render(/* positionUs= */ 500, SystemClock.elapsedRealtime() * 1000);
      codecAdapterFactory.idleQueueingAndCallbackThreads();
    }

    // Assert that we rendered the first frame after the reset.
    shadowLooper.idle();
    verify(eventListener, times(2))
        .onRenderedFirstFrame(eq(surface), /* renderTimeMs= */ anyLong());
  }

  @Test
  public void supportsFormat_withDolbyVisionMedia_returnsTrueWhenFallbackToH265orH264Allowed()
      throws Exception {
    // Create Dolby media formats that could fall back to H265 or H264.
    Format formatDvheDtrFallbackToH265 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.04.01")
            .build();
    Format formatDvheStFallbackToH265 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.01")
            .build();
    Format formatDvavSeFallbackToH264 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvav.09.01")
            .build();
    Format formatNoFallbackPossible =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvav.01.01")
            .build();
    // Only provide H264 and H265 decoders with codec profiles needed for fallback.
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          switch (mimeType) {
            case MimeTypes.VIDEO_H264:
              CodecCapabilities capabilitiesH264 = new CodecCapabilities();
              capabilitiesH264.profileLevels =
                  new CodecProfileLevel[] {
                    createCodecProfileLevel(AVCProfileBaseline, AVCLevel42),
                    createCodecProfileLevel(AVCProfileHigh, AVCLevel42)
                  };
              return ImmutableList.of(
                  MediaCodecInfo.newInstance(
                      /* name= */ "h264-codec",
                      /* mimeType= */ mimeType,
                      /* codecMimeType= */ mimeType,
                      /* capabilities= */ capabilitiesH264,
                      /* hardwareAccelerated= */ false,
                      /* softwareOnly= */ true,
                      /* vendor= */ false,
                      /* forceDisableAdaptive= */ false,
                      /* forceSecure= */ false));
            case MimeTypes.VIDEO_H265:
              CodecCapabilities capabilitiesH265 = new CodecCapabilities();
              capabilitiesH265.profileLevels =
                  new CodecProfileLevel[] {
                    createCodecProfileLevel(HEVCProfileMain, HEVCMainTierLevel41),
                    createCodecProfileLevel(HEVCProfileMain10, HEVCHighTierLevel51)
                  };
              return ImmutableList.of(
                  MediaCodecInfo.newInstance(
                      /* name= */ "h265-codec",
                      /* mimeType= */ mimeType,
                      /* codecMimeType= */ mimeType,
                      /* capabilities= */ capabilitiesH265,
                      /* hardwareAccelerated= */ false,
                      /* softwareOnly= */ true,
                      /* vendor= */ false,
                      /* forceDisableAdaptive= */ false,
                      /* forceSecure= */ false));
            default:
              return ImmutableList.of();
          }
        };
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(1)
            .build();
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    @Capabilities
    int capabilitiesDvheDtrFallbackToH265 = renderer.supportsFormat(formatDvheDtrFallbackToH265);
    @Capabilities
    int capabilitiesDvheStFallbackToH265 = renderer.supportsFormat(formatDvheStFallbackToH265);
    @Capabilities
    int capabilitiesDvavSeFallbackToH264 = renderer.supportsFormat(formatDvavSeFallbackToH264);
    @Capabilities
    int capabilitiesNoFallbackPossible = renderer.supportsFormat(formatNoFallbackPossible);

    assertThat(RendererCapabilities.getFormatSupport(capabilitiesDvheDtrFallbackToH265))
        .isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getFormatSupport(capabilitiesDvheStFallbackToH265))
        .isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getFormatSupport(capabilitiesDvavSeFallbackToH264))
        .isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getFormatSupport(capabilitiesNoFallbackPossible))
        .isEqualTo(C.FORMAT_UNSUPPORTED_SUBTYPE);
  }

  @Test
  public void supportsFormat_withDolbyVision_setsDecoderSupportFlagsByDisplayDolbyVisionSupport()
      throws Exception {
    Format formatDvheDtr =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.04.01")
            .build();
    // Provide supporting Dolby Vision and fallback HEVC decoders
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          switch (mimeType) {
            case MimeTypes.VIDEO_DOLBY_VISION:
              {
                CodecCapabilities capabilitiesDolby =
                    createCodecCapabilities(DolbyVisionProfileDvheDtr, DolbyVisionLevelFhd30);
                return ImmutableList.of(
                    MediaCodecInfo.newInstance(
                        /* name= */ "dvhe-codec",
                        /* mimeType= */ mimeType,
                        /* codecMimeType= */ mimeType,
                        /* capabilities= */ capabilitiesDolby,
                        /* hardwareAccelerated= */ true,
                        /* softwareOnly= */ false,
                        /* vendor= */ false,
                        /* forceDisableAdaptive= */ false,
                        /* forceSecure= */ false));
              }
            case MimeTypes.VIDEO_H265:
              {
                CodecCapabilities capabilitiesH265 = new CodecCapabilities();
                capabilitiesH265.profileLevels =
                    new CodecProfileLevel[] {
                      createCodecProfileLevel(HEVCProfileMain, HEVCMainTierLevel41),
                      createCodecProfileLevel(HEVCProfileMain10, HEVCHighTierLevel51)
                    };
                return ImmutableList.of(
                    MediaCodecInfo.newInstance(
                        /* name= */ "h265-codec",
                        /* mimeType= */ mimeType,
                        /* codecMimeType= */ mimeType,
                        /* capabilities= */ capabilitiesH265,
                        /* hardwareAccelerated= */ true,
                        /* softwareOnly= */ false,
                        /* vendor= */ false,
                        /* forceDisableAdaptive= */ false,
                        /* forceSecure= */ false));
              }
            default:
              return ImmutableList.of();
          }
        };
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(1)
            .build();
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    @Capabilities int capabilitiesDvheDtr = renderer.supportsFormat(formatDvheDtr);

    assertThat(RendererCapabilities.getDecoderSupport(capabilitiesDvheDtr))
        .isEqualTo(RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE);

    // Set Display to have Dolby Vision support
    Context context = ApplicationProvider.getApplicationContext();
    DisplayManager displayManager =
        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    Display display = (displayManager != null) ? displayManager.getDisplay(DEFAULT_DISPLAY) : null;
    ShadowDisplay shadowDisplay = Shadows.shadowOf(display);
    int[] hdrCapabilities =
        new int[] {
          Display.HdrCapabilities.HDR_TYPE_HDR10, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        };
    shadowDisplay.setDisplayHdrCapabilities(
        display.getDisplayId(),
        /* maxLuminance= */ 100f,
        /* maxAverageLuminance= */ 100f,
        /* minLuminance= */ 100f,
        hdrCapabilities);

    capabilitiesDvheDtr = renderer.supportsFormat(formatDvheDtr);

    assertThat(RendererCapabilities.getDecoderSupport(capabilitiesDvheDtr))
        .isEqualTo(RendererCapabilities.DECODER_SUPPORT_PRIMARY);
  }

  @Test
  public void getDecoderInfo_withNonPerformantHardwareDecoder_returnsHardwareDecoderFirst()
      throws Exception {
    // AVC Format, Profile: 8, Level: 8192
    Format avcFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.64002a")
            .build();
    // Provide hardware and software AVC decoders
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (!mimeType.equals(MimeTypes.VIDEO_H264)) {
            return ImmutableList.of();
          }
          // Hardware decoder supports above format functionally but not performantly as
          // it supports MIME type & Profile but not Level
          // Software decoder supports format functionally and peformantly as it supports
          // MIME type, Profile, and Level(assuming resolution/frame rate support too)
          return ImmutableList.of(
              H264_PROFILE8_LEVEL4_HW_MEDIA_CODEC_INFO, H264_PROFILE8_LEVEL5_SW_MEDIA_CODEC_INFO);
        };
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(1)
            .build();
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    List<MediaCodecInfo> mediaCodecInfoList =
        renderer.getDecoderInfos(mediaCodecSelector, avcFormat, false);
    @Capabilities int capabilities = renderer.supportsFormat(avcFormat);

    assertThat(mediaCodecInfoList).hasSize(2);
    assertThat(mediaCodecInfoList.get(0).hardwareAccelerated).isTrue();
    assertThat(RendererCapabilities.getFormatSupport(capabilities)).isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getDecoderSupport(capabilities))
        .isEqualTo(RendererCapabilities.DECODER_SUPPORT_FALLBACK);
  }

  @Test
  public void getDecoderInfo_softwareDecoderPreferred_returnsSoftwareDecoderFirst()
      throws Exception {
    // AVC Format, Profile: 8, Level: 8192
    Format avcFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.64002a")
            .build();
    // Provide software and hardware AVC decoders
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (!mimeType.equals(MimeTypes.VIDEO_H264)) {
            return ImmutableList.of();
          }
          // Hardware decoder supports above format functionally but not performantly as
          // it supports MIME type & Profile but not Level
          // Software decoder supports format functionally and peformantly as it supports
          // MIME type, Profile, and Level(assuming resolution/frame rate support too)
          return ImmutableList.of(
              H264_PROFILE8_LEVEL5_SW_MEDIA_CODEC_INFO, H264_PROFILE8_LEVEL4_HW_MEDIA_CODEC_INFO);
        };
    MediaCodecVideoRenderer renderer =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(0)
            .setEventHandler(new Handler(testMainLooper))
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(1)
            .build();
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    List<MediaCodecInfo> mediaCodecInfoList =
        renderer.getDecoderInfos(mediaCodecSelector, avcFormat, false);
    @Capabilities int capabilities = renderer.supportsFormat(avcFormat);

    assertThat(mediaCodecInfoList).hasSize(2);
    assertThat(mediaCodecInfoList.get(0).hardwareAccelerated).isFalse();
    assertThat(RendererCapabilities.getFormatSupport(capabilities)).isEqualTo(C.FORMAT_HANDLED);
    assertThat(RendererCapabilities.getDecoderSupport(capabilities))
        .isEqualTo(RendererCapabilities.DECODER_SUPPORT_PRIMARY);
  }

  @Test
  public void setVideoOutput_withNoEffects_updatesSurfaceOnMediaCodec()
      throws ExoPlaybackException {
    ArrayList<Surface> surfacesSet = new ArrayList<>();
    MediaCodecAdapter.Factory codecAdapterFactory =
        configuration ->
            new ForwardingMediaCodecAdapter(
                new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration)) {
              @Override
              public void setOutputSurface(Surface surface) {
                super.setOutputSurface(surface);
                surfacesSet.add(surface);
              }
            };
    MediaCodecVideoRenderer mediaCodecVideoRenderer =
        new MediaCodecVideoRenderer(
            ApplicationProvider.getApplicationContext(),
            codecAdapterFactory,
            mediaCodecSelector,
            /* allowedJoiningTimeMs= */ 0,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(testMainLooper),
            /* eventListener= */ eventListener,
            /* maxDroppedFramesToNotify= */ 1,
            /* assumedMinimumCodecOperatingRate= */ 30) {
          @Override
          protected @Capabilities int supportsFormat(
              MediaCodecSelector mediaCodecSelector, Format format) {
            return RendererCapabilities.create(C.FORMAT_HANDLED);
          }
        };
    mediaCodecVideoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ VIDEO_H264,
            /* fakeSampleStreamItems= */ ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), // First buffer.
                oneByteSample(/* timeUs= */ 50_000), // Late buffer.
                oneByteSample(/* timeUs= */ 100_000), // Last buffer.
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecVideoRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {VIDEO_H264},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecVideoRenderer.start();
    mediaCodecVideoRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    Surface newSurface = new Surface(new SurfaceTexture(/* texName= */ 0));
    mediaCodecVideoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, newSurface);

    assertThat(surfacesSet).containsExactly(newSurface);
  }

  @Test
  public void build_calledTwice_throwsIllegalStateException() throws Exception {
    MediaCodecVideoRenderer.Builder mediaCodecVideoRendererBuilder =
        new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext());
    mediaCodecVideoRendererBuilder.build();

    assertThrows(IllegalStateException.class, mediaCodecVideoRendererBuilder::build);
  }

  private void seekToUs(
      MediaCodecVideoRenderer mediaCodecVideoRenderer,
      @Nullable ScrubbingModeParameters scrubbingModeParameters,
      FakeSampleStream fakeSampleStream,
      long positionUs)
      throws ExoPlaybackException {
    if (mediaCodecVideoRenderer.getState() == STATE_STARTED) {
      mediaCodecVideoRenderer.stop();
    }
    boolean sampleStreamIsResetToKeyFrame = true;
    if (scrubbingModeParameters != null
        && scrubbingModeParameters.allowSkippingKeyFrameReset
        && mediaCodecVideoRenderer.supportsResetPositionWithoutKeyFrameReset(positionUs)) {
      sampleStreamIsResetToKeyFrame = false;
    } else {
      fakeSampleStream.seekToUs(positionUs, true);
    }
    mediaCodecVideoRenderer.resetPosition(positionUs, sampleStreamIsResetToKeyFrame);
  }

  private static CodecCapabilities createCodecCapabilities(int profile, int level) {
    CodecCapabilities capabilities = new CodecCapabilities();
    capabilities.profileLevels = new CodecProfileLevel[] {createCodecProfileLevel(profile, level)};
    return capabilities;
  }

  @Test
  public void getCodecMaxInputSize_videoH263() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_H263);

    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_H263, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(230400);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H263, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(691200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_H263, 1920, 1080)))
        .isEqualTo(1555200);
  }

  @Test
  public void getCodecMaxInputSize_videoH264() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_H264);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_H264, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(230400);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H264, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(691200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H264, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(1566720);
  }

  @Test
  public void getCodecMaxInputSize_videoHevc() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_H265);

    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_H265, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(2097152);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H265, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(2097152);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H265, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(2097152);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_H265, /* width= */ 3840, /* height= */ 2160)))
        .isEqualTo(6220800);
  }

  @Test
  public void getCodecMaxInputSize_videoMp4v() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_MP4V);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(230400);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(691200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(1555200);
  }

  @Test
  public void getCodecMaxInputSize_videoAv1() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_AV1);

    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(230400);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(691200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_MP4V, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(1555200);
  }

  @Test
  public void getCodecMaxInputSize_videoVp8() {
    MediaCodecInfo vp8CodecInfo = createMediaCodecInfo(MimeTypes.VIDEO_VP8);

    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                vp8CodecInfo,
                createFormat(MimeTypes.VIDEO_VP8, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(230400);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                vp8CodecInfo,
                createFormat(MimeTypes.VIDEO_VP8, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(691200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                vp8CodecInfo,
                createFormat(MimeTypes.VIDEO_VP8, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(1555200);
  }

  @Test
  public void getCodecMaxInputSize_dolbyVision_fallBack() {
    MediaCodecInfo dvCodecInfo = createMediaCodecInfo(MimeTypes.VIDEO_DOLBY_VISION);
    int h264MaxSampleSize =
        MediaCodecVideoRenderer.getCodecMaxInputSize(
            createMediaCodecInfo(MimeTypes.VIDEO_H264),
            createFormat(MimeTypes.VIDEO_H264, /* width= */ 1920, /* height= */ 1080));
    int hevcMaxSampleSize =
        MediaCodecVideoRenderer.getCodecMaxInputSize(
            createMediaCodecInfo(MimeTypes.VIDEO_H265),
            createFormat(MimeTypes.VIDEO_H265, /* width= */ 1920, /* height= */ 1080));

    // DV format without codec string fallbacks to HEVC.
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    // DV profiles "00", "01" and "09" fallback to H264.
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.00.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(h264MaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.01.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(h264MaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.09.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(h264MaxSampleSize);
    // DV profiles "02", "03", "04", "05", "06, "07" and "08" fallback to HEVC.
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.02.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.03.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.04.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.05.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.06.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.07.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                dvCodecInfo,
                new Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                    .setCodecs("dvhe.08.01")
                    .setWidth(1920)
                    .setHeight(1080)
                    .build()))
        .isEqualTo(hevcMaxSampleSize);
  }

  @Test
  public void getCodecMaxInputSize_videoVp9() {
    MediaCodecInfo codecInfo = createMediaCodecInfo(MimeTypes.VIDEO_VP9);

    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_VP9, /* width= */ 640, /* height= */ 480)))
        .isEqualTo(115200);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo, createFormat(MimeTypes.VIDEO_VP9, /* width= */ 1280, /* height= */ 720)))
        .isEqualTo(345600);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                codecInfo,
                createFormat(MimeTypes.VIDEO_VP9, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(777600);
  }

  @Test
  public void getCodecMaxInputSize_withUnsupportedFormat_returnsNoValue() {
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MP43),
                createFormat(MimeTypes.VIDEO_MP43, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MP42),
                createFormat(MimeTypes.VIDEO_MP42, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MJPEG),
                createFormat(MimeTypes.VIDEO_MJPEG, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_AVI),
                createFormat(MimeTypes.VIDEO_AVI, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_OGG),
                createFormat(MimeTypes.VIDEO_OGG, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_FLV),
                createFormat(MimeTypes.VIDEO_FLV, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_VC1),
                createFormat(MimeTypes.VIDEO_VC1, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MPEG2),
                createFormat(MimeTypes.VIDEO_MPEG2, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_PS),
                createFormat(MimeTypes.VIDEO_PS, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MPEG),
                createFormat(MimeTypes.VIDEO_MPEG, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_MP2T),
                createFormat(MimeTypes.VIDEO_MP2T, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_WEBM),
                createFormat(MimeTypes.VIDEO_WEBM, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
    assertThat(
            MediaCodecVideoRenderer.getCodecMaxInputSize(
                createMediaCodecInfo(MimeTypes.VIDEO_DIVX),
                createFormat(MimeTypes.VIDEO_DIVX, /* width= */ 1920, /* height= */ 1080)))
        .isEqualTo(Format.NO_VALUE);
  }

  private static MediaCodecInfo createMediaCodecInfo(String mimeType) {
    return MediaCodecInfo.newInstance(
        /* name= */ mimeType,
        /* mimeType= */ mimeType,
        /* codecMimeType= */ mimeType,
        /* capabilities= */ new CodecCapabilities(),
        /* hardwareAccelerated= */ true,
        /* softwareOnly= */ false,
        /* vendor= */ true,
        /* forceDisableAdaptive= */ false,
        /* forceSecure= */ false);
  }

  private static Format createFormat(String mimeType, int width, int height) {
    return new Format.Builder()
        .setSampleMimeType(mimeType)
        .setWidth(width)
        .setHeight(height)
        .build();
  }

  private static final class ForwardingSynchronousMediaCodecAdapterWithReordering
      extends ForwardingMediaCodecAdapter {
    /** A factory for {@link ForwardingSynchronousMediaCodecAdapterWithReordering} instances. */
    public static final class Factory implements MediaCodecAdapter.Factory {
      @Override
      public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
        return new ForwardingSynchronousMediaCodecAdapterWithReordering(
            new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration));
      }
    }

    private final PriorityQueue<Long> timestamps;

    ForwardingSynchronousMediaCodecAdapterWithReordering(MediaCodecAdapter adapter) {
      super(adapter);
      timestamps = new PriorityQueue<>();
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      int outputBufferIndex = super.dequeueOutputBufferIndex(bufferInfo);
      Long smallestTimestamp = timestamps.peek();
      if (smallestTimestamp != null && outputBufferIndex != INFO_TRY_AGAIN_LATER) {
        bufferInfo.presentationTimeUs = smallestTimestamp;
      }
      return outputBufferIndex;
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      if ((flags & C.BUFFER_FLAG_END_OF_STREAM) == 0) {
        timestamps.add(presentationTimeUs);
      }
      super.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      timestamps.poll();
      super.releaseOutputBuffer(index, render);
    }

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
      timestamps.poll();
      super.releaseOutputBuffer(index, renderTimeStampNs);
    }

    @Override
    public void flush() {
      timestamps.clear();
      super.flush();
    }

    @Override
    public void release() {
      timestamps.clear();
      super.release();
    }
  }

  private static final class ForwardingSynchronousMediaCodecAdapterWithBufferLimit
      extends ForwardingMediaCodecAdapter {
    /** A factory for {@link ForwardingSynchronousMediaCodecAdapterWithBufferLimit} instances. */
    public static final class Factory implements MediaCodecAdapter.Factory {
      private final int bufferLimit;

      Factory(int bufferLimit) {
        this.bufferLimit = bufferLimit;
      }

      @Override
      public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
        return new ForwardingSynchronousMediaCodecAdapterWithBufferLimit(
            bufferLimit, new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration));
      }
    }

    private int bufferCounter;

    ForwardingSynchronousMediaCodecAdapterWithBufferLimit(
        int bufferCounter, MediaCodecAdapter adapter) {
      super(adapter);
      this.bufferCounter = bufferCounter;
    }

    @Override
    public int dequeueInputBufferIndex() {
      if (bufferCounter > 0) {
        bufferCounter--;
        return super.dequeueInputBufferIndex();
      }
      return -1;
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      int outputIndex = super.dequeueOutputBufferIndex(bufferInfo);
      if (outputIndex >= 0) {
        bufferCounter++;
      }
      return outputIndex;
    }
  }
}
