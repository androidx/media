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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.ExtensionCodecInfo;
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.VideoExtensionCodecAdapter;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultRenderersFactory} with unified decoder renderers. */
@RunWith(AndroidJUnit4.class)
public final class DefaultRenderersFactoryTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private Context context;
  private Handler handler;
  private VideoRendererEventListener eventListener;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    handler = new Handler(Looper.getMainLooper());
    eventListener = new VideoRendererEventListener() {};
  }

  @Test
  public void setExtensionAdapterFactory_withCustomExtensionCodec_usesRegisteredFactory()
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(1280)
            .setHeight(720)
            .build();
    ExtensionCodecInfo customExtensionInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.custom.decoder", MimeTypes.VIDEO_AV1);
    List<Surface> renderedSurfaces = new ArrayList<>();
    List<@Nullable Object> receivedOutputs = new ArrayList<>();
    AtomicBoolean customFactoryInvoked = new AtomicBoolean();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(customExtensionInfo))
            .setExtensionAdapterFactory(
                "c2.media3.av1.custom.decoder",
                configuration -> {
                  customFactoryInvoked.set(true);
                  return new FakeVideoExtensionCodecAdapter(
                      configuration.format,
                      configuration.surface,
                      renderedSurfaces,
                      receivedOutputs);
                });

    Renderer[] renderers =
        factory.createRenderers(
            handler,
            eventListener,
            new AudioRendererEventListener() {},
            /* textRendererOutput= */ cues -> {},
            /* metadataRendererOutput= */ metadata -> {});
    @Nullable SurfaceTexture surfaceTexture = null;
    @Nullable Surface surface = null;
    try {
      MediaCodecVideoRenderer videoRenderer = (MediaCodecVideoRenderer) renderers[0];
      surfaceTexture = new SurfaceTexture(/* texName= */ 0);
      surface =
          new Surface(surfaceTexture) {
            @Override
            public boolean isValid() {
              return true;
            }
          };
      try {
        videoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        videoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
        FakeSampleStream sampleStream =
            new FakeSampleStream(
                new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
                /* mediaSourceEventDispatcher= */ null,
                DrmSessionManager.DRM_UNSUPPORTED,
                new DrmSessionEventListener.EventDispatcher(),
                /* initialFormat= */ format,
                ImmutableList.of(
                    FakeSampleStream.FakeSampleStreamItem.oneByteSample(
                        /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                    FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM));
        sampleStream.writeData(/* startPositionUs= */ 0);
        videoRenderer.enable(
            RendererConfiguration.DEFAULT,
            new Format[] {format},
            sampleStream,
            /* positionUs= */ 0,
            /* joining= */ false,
            /* mayRenderStartOfStream= */ true,
            /* startPositionUs= */ 0,
            /* offsetUs= */ 0,
            /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));
        videoRenderer.start();
        for (int i = 0; i < 10 && renderedSurfaces.isEmpty(); i++) {
          videoRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
        }
      } finally {
        if (videoRenderer.getState() == Renderer.STATE_STARTED) {
          videoRenderer.stop();
        }
        if (videoRenderer.getState() == Renderer.STATE_ENABLED) {
          videoRenderer.disable();
        }
        videoRenderer.reset();
      }
    } finally {
      if (surface != null) {
        surface.release();
      }
      if (surfaceTexture != null) {
        surfaceTexture.release();
      }
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }

    assertThat(customFactoryInvoked.get()).isTrue();
    assertThat(renderedSurfaces).containsExactly(surface);
  }

  @Test
  public void
      getCodecAdapterFactory_wrappedWithForwardingMediaCodecAdapter_decodesAndDetachesOutputs()
          throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(1280)
            .setHeight(720)
            .build();
    ExtensionCodecInfo customExtensionInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.custom.decoder", MimeTypes.VIDEO_AV1);
    List<Surface> renderedSurfaces = new ArrayList<>();
    List<@Nullable Object> receivedOutputs = new ArrayList<>();
    List<VideoDecoderOutputBuffer> renderedBuffers = new ArrayList<>();
    AtomicInteger wrapperQueuedInputCount = new AtomicInteger();
    AtomicBoolean wrapperReleased = new AtomicBoolean();
    DefaultRenderersFactory factory =
        new DefaultRenderersFactory(context) {
          @Override
          protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
            MediaCodecAdapter.Factory delegateFactory = super.getCodecAdapterFactory();
            return configuration ->
                new ForwardingMediaCodecAdapter(delegateFactory.createAdapter(configuration)) {
                  @Override
                  public void queueInputBuffer(
                      int index, int offset, int size, long presentationTimeUs, int flags) {
                    wrapperQueuedInputCount.incrementAndGet();
                    super.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
                  }

                  @Override
                  public void release() {
                    wrapperReleased.set(true);
                    super.release();
                  }
                };
          }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                ImmutableList.of(customExtensionInfo))
            .setExtensionAdapterFactory(
                "c2.media3.av1.custom.decoder",
                configuration ->
                    new FakeVideoExtensionCodecAdapter(
                        configuration.format,
                        configuration.surface,
                        renderedSurfaces,
                        receivedOutputs));
    VideoDecoderOutputBufferRenderer bufferRenderer =
        outputBuffer -> {
          renderedBuffers.add(outputBuffer);
          outputBuffer.release();
        };

    Renderer[] renderers =
        factory.createRenderers(
            handler,
            eventListener,
            new AudioRendererEventListener() {},
            /* textRendererOutput= */ cues -> {},
            /* metadataRendererOutput= */ metadata -> {});
    @Nullable SurfaceTexture surfaceTexture = null;
    @Nullable Surface surface = null;
    List<@Nullable Object> outputsAfterExplicitDetach;
    try {
      MediaCodecVideoRenderer videoRenderer = (MediaCodecVideoRenderer) renderers[0];
      surfaceTexture = new SurfaceTexture(/* texName= */ 0);
      surface =
          new Surface(surfaceTexture) {
            @Override
            public boolean isValid() {
              return true;
            }
          };
      try {
        videoRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
        videoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface);
        FakeSampleStream sampleStream =
            new FakeSampleStream(
                new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
                /* mediaSourceEventDispatcher= */ null,
                DrmSessionManager.DRM_UNSUPPORTED,
                new DrmSessionEventListener.EventDispatcher(),
                /* initialFormat= */ format,
                ImmutableList.of(
                    FakeSampleStream.FakeSampleStreamItem.oneByteSample(
                        /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
        sampleStream.writeData(/* startPositionUs= */ 0);
        videoRenderer.enable(
            RendererConfiguration.DEFAULT,
            new Format[] {format},
            sampleStream,
            /* positionUs= */ 0,
            /* joining= */ false,
            /* mayRenderStartOfStream= */ true,
            /* startPositionUs= */ 0,
            /* offsetUs= */ 0,
            /* mediaPeriodId= */ new MediaSource.MediaPeriodId(new Object()));
        videoRenderer.start();
        for (int i = 0; i < 10 && renderedSurfaces.isEmpty(); i++) {
          videoRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
        }
        // Switch to VideoDecoderOutputBufferRenderer, feed a second sample, and detach output
        // (null).
        videoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, bufferRenderer);
        sampleStream.append(
            ImmutableList.of(
                FakeSampleStream.FakeSampleStreamItem.oneByteSample(
                    /* timeUs= */ 30_000, C.BUFFER_FLAG_KEY_FRAME),
                FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM));
        sampleStream.writeData(/* startPositionUs= */ 0);
        for (int i = 0; i < 10 && renderedBuffers.isEmpty(); i++) {
          videoRenderer.render(/* positionUs= */ 30_000, /* elapsedRealtimeUs= */ 0);
        }
        videoRenderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null);
        outputsAfterExplicitDetach = new ArrayList<>(receivedOutputs);
      } finally {
        if (videoRenderer.getState() == Renderer.STATE_STARTED) {
          videoRenderer.stop();
        }
        if (videoRenderer.getState() == Renderer.STATE_ENABLED) {
          videoRenderer.disable();
        }
        videoRenderer.reset();
        shadowOf(Looper.getMainLooper()).idle();
      }
    } finally {
      if (surface != null) {
        surface.release();
      }
      if (surfaceTexture != null) {
        surfaceTexture.release();
      }
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }

    assertThat(renderedSurfaces).containsExactly(surface);
    assertThat(renderedBuffers).hasSize(1);
    assertThat(outputsAfterExplicitDetach).containsExactly(surface, bufferRenderer, null).inOrder();
    assertThat(wrapperQueuedInputCount.get()).isEqualTo(2);
    assertThat(wrapperReleased.get()).isTrue();
  }

  private static final class FakeVideoExtensionCodecAdapter extends VideoExtensionCodecAdapter {

    private final SynchronousPassthroughVideoDecoder decoder;
    private final List<Surface> renderedSurfaces;
    private final List<@Nullable Object> receivedOutputs;

    private FakeVideoExtensionCodecAdapter(
        Format format,
        @Nullable Surface surface,
        List<Surface> renderedSurfaces,
        List<@Nullable Object> receivedOutputs) {
      this(
          format,
          new SynchronousPassthroughVideoDecoder(
              surface != null ? C.VIDEO_OUTPUT_MODE_SURFACE_YUV : C.VIDEO_OUTPUT_MODE_NONE),
          surface,
          renderedSurfaces,
          receivedOutputs);
    }

    private FakeVideoExtensionCodecAdapter(
        Format format,
        SynchronousPassthroughVideoDecoder decoder,
        @Nullable Surface surface,
        List<Surface> renderedSurfaces,
        List<@Nullable Object> receivedOutputs) {
      super(format, decoder, surface);
      this.decoder = decoder;
      this.renderedSurfaces = renderedSurfaces;
      this.receivedOutputs = receivedOutputs;
    }

    @Override
    public void setOutput(@Nullable Object output) {
      super.setOutput(output);
      receivedOutputs.add(output);
    }

    @Override
    protected void onOutputModeChanged(@C.VideoOutputMode int outputMode) {
      decoder.outputMode = outputMode;
    }

    @Override
    protected void renderOutputBufferToSurface(
        VideoDecoderOutputBuffer outputBuffer, Surface surface) {
      renderedSurfaces.add(surface);
      outputBuffer.release();
    }
  }

  private static final class SynchronousPassthroughVideoDecoder
      implements Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException> {

    private final Queue<DecoderInputBuffer> freeInputs = new ArrayDeque<>();
    private final Queue<VideoDecoderOutputBuffer> freeOutputs = new ArrayDeque<>();
    private final Queue<VideoDecoderOutputBuffer> readyOutputs = new ArrayDeque<>();
    private @C.VideoOutputMode int outputMode;

    private SynchronousPassthroughVideoDecoder(@C.VideoOutputMode int initialOutputMode) {
      this.outputMode = initialOutputMode;
      for (int i = 0; i < 4; i++) {
        freeInputs.add(new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT));
        freeOutputs.add(new VideoDecoderOutputBuffer(freeOutputs::add));
      }
    }

    @Override
    public String getName() {
      return "SynchronousPassthroughVideoDecoder";
    }

    @Override
    public @Nullable DecoderInputBuffer dequeueInputBuffer() {
      return freeInputs.poll();
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
      VideoDecoderOutputBuffer outputBuffer = freeOutputs.remove();
      if (inputBuffer.isEndOfStream()) {
        outputBuffer.timeUs = inputBuffer.timeUs;
        outputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
      } else {
        outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
      }
      inputBuffer.clear();
      freeInputs.add(inputBuffer);
      readyOutputs.add(outputBuffer);
    }

    @Override
    public @Nullable VideoDecoderOutputBuffer dequeueOutputBuffer() {
      return readyOutputs.poll();
    }

    @Override
    public void flush() {
      while (!readyOutputs.isEmpty()) {
        readyOutputs.remove().release();
      }
    }

    @Override
    public void release() {
      flush();
    }

    @Override
    public void setOutputStartTimeUs(long outputStartTimeUs) {}
  }
}
