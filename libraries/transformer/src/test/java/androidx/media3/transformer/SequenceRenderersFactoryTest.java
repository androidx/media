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

import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_AVC;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.EditedMediaItemSequence.withVideoFrom;
import static androidx.media3.transformer.TransformerUtil.releaseIfNeeded;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.transformer.TestUtil.FakeImageReaderAdapter;
import androidx.media3.transformer.TestUtil.FakeImageReaderAdapterFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Bytes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLooper;

/** Unit test for {@link SequenceRenderersFactory}. */
@RunWith(AndroidJUnit4.class)
public final class SequenceRenderersFactoryTest {

  private static final long VIDEO_ITEM_DURATION_US = 1_000_000;
  private static final Format H264_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(640)
          .setHeight(360)
          .build();

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_AVC), /* encoders= */ ImmutableList.of());

  private HandlerThread handlerThread;

  @Before
  public void setUp() {
    handlerThread = new HandlerThread("SequenceRenderersFactoryTest");
    handlerThread.start();
  }

  @After
  public void tearDown() {
    handlerThread.quitSafely();
  }

  @Test
  public void hardwareBufferImageRenderer_implementsRendererWakeupListener_andForwardsWakeup()
      throws Exception {
    SequenceRenderersFactory factory =
        createFactoryForHardwareBuffer(/* hardwareBufferFrameReaderSupplier= */ () -> null);
    Renderer imageRenderer = createRenderer(factory, C.TRACK_TYPE_IMAGE);

    assertThat(imageRenderer).isInstanceOf(HardwareBufferFrameReader.RendererWakeupListener.class);
    FakeWakeupListener fakeWakeupListener = new FakeWakeupListener();

    imageRenderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, fakeWakeupListener);
    ((HardwareBufferFrameReader.RendererWakeupListener) imageRenderer).onWakeup();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();
  }

  @Test
  public void hardwareBufferImageRenderer_onEnabledRegistersWakeupListener_onDisabledUnregisters()
      throws Exception {
    List<AsyncFrame> receivedFrames = new ArrayList<>();
    EditedMediaItemSequence sequence = createSequence();
    HardwareBufferFrameReader hardwareBufferFrameReader =
        createHardwareBufferFrameReader(sequence, receivedFrames, handlerThread.getLooper());
    SequenceRenderersFactory factory =
        createFactoryForHardwareBuffer(() -> hardwareBufferFrameReader);
    Renderer imageRenderer = createRenderer(factory, C.TRACK_TYPE_IMAGE);
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(new FakeTimeline(), sequence);
    enableRenderer(imageRenderer, timeline);
    FakeWakeupListener fakeWakeupListener = new FakeWakeupListener();
    imageRenderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, fakeWakeupListener);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888),
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 2f),
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);
    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();

    fakeWakeupListener.onWakeupCalled = false;
    imageRenderer.disable();

    releaseIfNeeded(receivedFrames.get(1).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isFalse();
  }

  @Test
  public void hardwareBufferVideoRenderer_implementsRendererWakeupListener_andForwardsWakeup()
      throws Exception {
    SequenceRenderersFactory factory =
        createFactoryForHardwareBuffer(/* hardwareBufferFrameReaderSupplier= */ () -> null);
    Renderer videoRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);

    assertThat(videoRenderer).isInstanceOf(HardwareBufferFrameReader.RendererWakeupListener.class);
    FakeWakeupListener fakeWakeupListener = new FakeWakeupListener();

    videoRenderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, fakeWakeupListener);
    ((HardwareBufferFrameReader.RendererWakeupListener) videoRenderer).onWakeup();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();
  }

  @Test
  public void hardwareBufferVideoRenderer_onEnabledRegistersWakeupListener_onDisabledUnregisters()
      throws Exception {
    List<AsyncFrame> receivedFrames = new ArrayList<>();
    EditedMediaItemSequence sequence = createSequence();
    HardwareBufferFrameReader hardwareBufferFrameReader =
        createHardwareBufferFrameReader(sequence, receivedFrames, handlerThread.getLooper());

    SequenceRenderersFactory factory =
        createFactoryForHardwareBuffer(() -> hardwareBufferFrameReader);
    Renderer videoRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);

    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(new FakeTimeline(), sequence);
    enableRenderer(videoRenderer, timeline);
    FakeWakeupListener fakeWakeupListener = new FakeWakeupListener();
    videoRenderer.handleMessage(Renderer.MSG_SET_WAKEUP_LISTENER, fakeWakeupListener);

    hardwareBufferFrameReader.outputBitmap(
        Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888),
        new ConstantRateTimestampIterator(/* durationUs= */ 1_000_000, /* frameRate= */ 2f),
        /* sequenceOffsetUs= */ 0,
        /* indexOfItem= */ 0);
    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();

    fakeWakeupListener.onWakeupCalled = false;
    videoRenderer.disable();

    releaseIfNeeded(receivedFrames.get(1).frame, /* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isFalse();
  }

  @Test
  public void
      hardwareBufferVideoRenderer_releasingAndReinitializingCodec_closesPreviousInputSurfaceAndCreatesNewOne()
          throws Exception {
    List<AsyncFrame> receivedFrames = new ArrayList<>();
    List<FakeImageReaderAdapter> imageReaders = new ArrayList<>();
    EditedMediaItemSequence sequence = createSequence();
    HardwareBufferFrameReader hardwareBufferFrameReader =
        createHardwareBufferFrameReader(
            sequence,
            receivedFrames,
            handlerThread.getLooper(),
            (width, height, format, maxImages, usage) -> {
              FakeImageReaderAdapter imageReader = new FakeImageReaderAdapter();
              imageReaders.add(imageReader);
              return imageReader;
            });
    SequenceRenderersFactory factory =
        createFactoryForHardwareBuffer(() -> hardwareBufferFrameReader);
    Renderer videoRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(new FakeTimeline(), sequence);
    Format videoFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(640)
            .setHeight(360)
            .build();

    enableRenderer(videoRenderer, timeline, /* periodIndex= */ 0, videoFormat);
    videoRenderer.render(/* positionUs= */ 0L, /* elapsedRealtimeUs= */ 0L);

    assertThat(imageReaders).hasSize(1);
    assertThat(imageReaders.get(0).isClosed()).isFalse();

    videoRenderer.disable();
    videoRenderer.reset();

    assertThat(imageReaders.get(0).isClosed()).isTrue();

    enableRenderer(videoRenderer, timeline, /* periodIndex= */ 0, videoFormat);
    videoRenderer.render(/* positionUs= */ 0L, /* elapsedRealtimeUs= */ 0L);

    assertThat(imageReaders).hasSize(2);
    assertThat(imageReaders.get(1).isClosed()).isFalse();
  }

  @Test
  public void hardwareBufferVideoRenderer_reenabledAfterStop_doesNotAttachStaleHagc()
      throws Exception {
    List<AsyncFrame> receivedFrames = new ArrayList<>();
    EditedMediaItemSequence sequence = createVideoSequence();
    HardwareBufferFrameReader hardwareBufferFrameReader =
        createHardwareBufferFrameReader(
            sequence,
            receivedFrames,
            handlerThread.getLooper(),
            new FakeImageReaderAdapterFactory());
    Renderer videoRenderer =
        createRenderer(
            createFactoryForHardwareBuffer(() -> hardwareBufferFrameReader), C.TRACK_TYPE_VIDEO);
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(new FakeTimeline(), sequence);
    Format videoFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(640)
            .setHeight(360)
            .build();

    enableRenderer(
        videoRenderer,
        timeline,
        /* periodIndex= */ 0,
        videoFormat,
        /* fakeSampleStreamItems= */ ImmutableList.of(
            sampleWithHagc(new byte[] {1, 2, 3}), END_OF_STREAM_ITEM));
    renderUntilFrameCount(videoRenderer, receivedFrames, /* frameCount= */ 1);
    // Stopping the player disables the renderer, and preparing it again re-enables it with the
    // same timestamps. Metadata queued before the stop must not be attached after it.
    videoRenderer.disable();
    // Enable the renderer again, with a frame at the same time but without HAGC metadata.
    enableRenderer(videoRenderer, timeline, /* periodIndex= */ 0, videoFormat);
    renderUntilFrameCount(videoRenderer, receivedFrames, /* frameCount= */ 2);

    assertThat(receivedFrames.get(0).frame.getMetadata().get(Frame.KEY_HDR_ST2094_50_INFO))
        .isEqualTo(new byte[] {1, 2, 3});
    assertThat(receivedFrames.get(1).frame.getMetadata())
        .doesNotContainKey(Frame.KEY_HDR_ST2094_50_INFO);
  }

  // TODO: b/390604981 - Once the asynchronous ShadowMediaCodec offers more than one input buffer,
  // test that frames still in the decoder when the renderer moves to the next item keep the HAGC
  // of their item.

  @Test
  @SuppressWarnings("deprecation") // Uses deprecated CompositionFrameMetadata.
  public void hardwareBufferImageRenderer_loopingSequence_wrapsItemIndexInFrameMetadata()
      throws Exception {
    List<AsyncFrame> receivedFrames = new ArrayList<>();
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
            .addItems(createSequence().editedMediaItems)
            .setIsLooping(true)
            .build();
    HardwareBufferFrameReader hardwareBufferFrameReader =
        createHardwareBufferFrameReader(loopingSequence, receivedFrames, handlerThread.getLooper());
    Renderer imageRenderer =
        createRenderer(
            createFactoryForHardwareBuffer(() -> hardwareBufferFrameReader), C.TRACK_TYPE_IMAGE);
    Timeline timeline =
        new CompositionPlayer.CompositionForwardingTimeline(
            new FakeTimeline(
                new FakeTimeline.TimelineWindowDefinition(/* periodCount= */ 2, /* id= */ 0)),
            loopingSequence);
    enableRenderer(imageRenderer, timeline, /* periodIndex= */ 1);

    while (receivedFrames.isEmpty()) {
      imageRenderer.render(/* positionUs= */ 0L, /* elapsedRealtimeUs= */ 0L);
    }

    CompositionFrameMetadata metadata =
        (CompositionFrameMetadata)
            receivedFrames
                .get(0)
                .frame
                .getMetadata()
                .get(CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA);
    assertThat(metadata.itemIndex).isEqualTo(0);
  }

  @Test
  public void prewarmedVideoRenderer_afterPreviousRendererEnded_queuesFirstFrameBeforeStarted()
      throws Exception {
    FakeFrameProcessingVideoSink videoSink = new FakeFrameProcessingVideoSink();
    SequenceRenderersFactory factory =
        createFactoryForVideoSink(videoSink, /* pendingInputFrameCountSupplier= */ () -> 0);
    Renderer primaryRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);
    Renderer secondaryRenderer = createSecondaryRenderer(factory, primaryRenderer);
    Timeline timeline = createVideoTimeline(/* itemCount= */ 2);

    playToEndOfStream(primaryRenderer, timeline);
    prewarmRenderer(secondaryRenderer, timeline, /* periodIndex= */ 1);
    renderUntil(secondaryRenderer, () -> videoSink.inputFramePresentationTimesUs.size() == 2);

    assertThat(secondaryRenderer.getState()).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoSink.inputFramePresentationTimesUs)
        .containsExactly(0L, VIDEO_ITEM_DURATION_US)
        .inOrder();
  }

  @Test
  public void prewarmedVideoRenderer_withPendingInputFrames_waitsUntilFramesProcessed()
      throws Exception {
    FakeFrameProcessingVideoSink videoSink = new FakeFrameProcessingVideoSink();
    AtomicInteger pendingInputFrameCount = new AtomicInteger(1);
    SequenceRenderersFactory factory =
        createFactoryForVideoSink(videoSink, pendingInputFrameCount::get);
    Renderer primaryRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);
    Renderer secondaryRenderer = createSecondaryRenderer(factory, primaryRenderer);
    Timeline timeline = createVideoTimeline(/* itemCount= */ 2);

    playToEndOfStream(primaryRenderer, timeline);
    prewarmRenderer(secondaryRenderer, timeline, /* periodIndex= */ 1);

    assertThat(videoSink.inputFramePresentationTimesUs).containsExactly(0L);

    pendingInputFrameCount.set(0);
    renderUntil(secondaryRenderer, () -> videoSink.inputFramePresentationTimesUs.size() == 2);

    assertThat(secondaryRenderer.getState()).isEqualTo(Renderer.STATE_ENABLED);
    assertThat(videoSink.inputFramePresentationTimesUs)
        .containsExactly(0L, VIDEO_ITEM_DURATION_US)
        .inOrder();
  }

  @Test
  public void prewarmedVideoRenderer_notDirectlyAfterPreviousItem_waitsUntilStarted()
      throws Exception {
    FakeFrameProcessingVideoSink videoSink = new FakeFrameProcessingVideoSink();
    SequenceRenderersFactory factory =
        createFactoryForVideoSink(videoSink, /* pendingInputFrameCountSupplier= */ () -> 0);
    Renderer primaryRenderer = createRenderer(factory, C.TRACK_TYPE_VIDEO);
    Renderer secondaryRenderer = createSecondaryRenderer(factory, primaryRenderer);
    Timeline timeline = createVideoTimeline(/* itemCount= */ 3);

    playToEndOfStream(primaryRenderer, timeline);
    prewarmRenderer(secondaryRenderer, timeline, /* periodIndex= */ 2);

    assertThat(videoSink.inputFramePresentationTimesUs).containsExactly(0L);

    secondaryRenderer.start();
    renderUntil(secondaryRenderer, () -> videoSink.inputFramePresentationTimesUs.size() == 2);

    assertThat(videoSink.inputFramePresentationTimesUs)
        .containsExactly(0L, 2 * VIDEO_ITEM_DURATION_US)
        .inOrder();
  }

  private static SequenceRenderersFactory createFactoryForHardwareBuffer(
      Supplier<HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier) {
    return SequenceRenderersFactory.createForHardwareBuffer(
        getApplicationContext(),
        new PlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(),
            new DefaultAudioSink.Builder(getApplicationContext()).build()),
        new ExternallyLoadedImageDecoder.Factory(
            request ->
                immediateFuture(
                    Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888))),
        /* inputIndex= */ 0,
        /* videoPrewarmingEnabled= */ false,
        /* compositionRendererListener= */ mock(
            SequenceRenderersFactory.CompositionRendererListener.class),
        hardwareBufferFrameReaderSupplier,
        /* lateThresholdToDropInputUs= */ 0);
  }

  private static SequenceRenderersFactory createFactoryForVideoSink(
      VideoSink videoSink, Supplier<Integer> pendingInputFrameCountSupplier) {
    return SequenceRenderersFactory.createForVideoSink(
        getApplicationContext(),
        new PlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(),
            new DefaultAudioSink.Builder(getApplicationContext()).build()),
        videoSink,
        new ExternallyLoadedImageDecoder.Factory(
            request ->
                immediateFuture(
                    Bitmap.createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888))),
        /* inputIndex= */ 0,
        /* videoPrewarmingEnabled= */ true,
        /* lateThresholdToDropInputUs= */ C.TIME_UNSET,
        pendingInputFrameCountSupplier);
  }

  private static Renderer createSecondaryRenderer(
      SequenceRenderersFactory factory, Renderer primaryRenderer) {
    return checkNotNull(
        factory.createSecondaryRenderer(
            primaryRenderer,
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            /* videoRendererEventListener= */ mock(VideoRendererEventListener.class),
            /* audioRendererEventListener= */ mock(AudioRendererEventListener.class),
            /* textRendererOutput= */ mock(TextOutput.class),
            /* metadataRendererOutput= */ mock(MetadataOutput.class)));
  }

  private static Timeline createVideoTimeline(int itemCount) {
    EditedMediaItemSequence.Builder sequenceBuilder =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO));
    for (int i = 0; i < itemCount; i++) {
      sequenceBuilder.addItem(
          new EditedMediaItem.Builder(MediaItem.fromUri("https://example.com/" + i))
              .setDurationUs(VIDEO_ITEM_DURATION_US)
              .build());
    }
    return new CompositionPlayer.CompositionForwardingTimeline(
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition(/* periodCount= */ itemCount, /* id= */ 0)),
        sequenceBuilder.build());
  }

  /** Plays the first item to its end, keeping its renderer enabled as the player does. */
  private static void playToEndOfStream(Renderer renderer, Timeline timeline) throws Exception {
    enableRenderer(
        renderer,
        timeline,
        /* periodIndex= */ 0,
        H264_FORMAT,
        /* mayRenderStartOfStream= */ true,
        /* offsetUs= */ 0);
    // The next item is played by the other renderer.
    renderer.setCurrentStreamFinal();
    renderer.start();
    renderUntil(renderer, renderer::isEnded);
  }

  /** Enables the renderer for a later item and renders it before it's started. */
  private static void prewarmRenderer(Renderer renderer, Timeline timeline, int periodIndex)
      throws Exception {
    enableRenderer(
        renderer,
        timeline,
        periodIndex,
        H264_FORMAT,
        /* mayRenderStartOfStream= */ false,
        /* offsetUs= */ periodIndex * VIDEO_ITEM_DURATION_US);
    for (int i = 0; i < 100; i++) {
      renderer.render(
          /* positionUs= */ VIDEO_ITEM_DURATION_US / 2,
          /* elapsedRealtimeUs= */ SystemClock.DEFAULT.elapsedRealtime() * 1000);
      ShadowLooper.idleMainLooper();
      Thread.sleep(1);
    }
  }

  private static void renderUntil(Renderer renderer, Supplier<Boolean> condition) throws Exception {
    runMainLooperUntil(
        () -> {
          try {
            renderer.render(
                /* positionUs= */ VIDEO_ITEM_DURATION_US / 2,
                /* elapsedRealtimeUs= */ SystemClock.DEFAULT.elapsedRealtime() * 1000);
          } catch (ExoPlaybackException e) {
            throw new IllegalStateException(e);
          }
          return condition.get();
        });
  }

  private static Renderer createRenderer(
      SequenceRenderersFactory factory, @C.TrackType int trackType) {
    Renderer[] renderers =
        factory.createRenderers(
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            /* videoRendererEventListener= */ mock(VideoRendererEventListener.class),
            /* audioRendererEventListener= */ mock(AudioRendererEventListener.class),
            /* textRendererOutput= */ mock(TextOutput.class),
            /* metadataRendererOutput= */ mock(MetadataOutput.class));
    for (Renderer renderer : renderers) {
      if (renderer.getTrackType() == trackType) {
        return renderer;
      }
    }
    throw new IllegalStateException("No renderer found for track type: " + trackType);
  }

  private static EditedMediaItemSequence createSequence() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri("https://example.com"))
            .setDurationUs(1_000_000)
            .build();
    return withAudioFrom(ImmutableList.of(editedMediaItem));
  }

  private static EditedMediaItemSequence createVideoSequence() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri("https://example.com"))
            .setDurationUs(1_000_000)
            .build();
    return withVideoFrom(ImmutableList.of(editedMediaItem));
  }

  private static HardwareBufferFrameReader createHardwareBufferFrameReader(
      EditedMediaItemSequence sequence, List<AsyncFrame> receivedFrames, Looper looper) {
    return createHardwareBufferFrameReader(
        sequence, receivedFrames, looper, new DefaultImageReaderAdapter.Factory());
  }

  private static HardwareBufferFrameReader createHardwareBufferFrameReader(
      EditedMediaItemSequence sequence,
      List<AsyncFrame> receivedFrames,
      Looper looper,
      ImageReaderAdapter.Factory imageReaderAdapterFactory) {
    Composition composition = new Composition.Builder(sequence, createSequence()).build();
    HardwareBufferJniWrapper mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);
    return new HardwareBufferFrameReader(
        composition,
        /* sequenceIndex= */ 0,
        /* frameConsumer= */ receivedFrames::add,
        looper,
        /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
        imageReaderAdapterFactory,
        /* listener= */ e -> {},
        SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
        mockJniWrapper);
  }

  private static void enableRenderer(Renderer renderer, Timeline timeline)
      throws ExoPlaybackException {
    enableRenderer(renderer, timeline, /* periodIndex= */ 0);
  }

  private static void enableRenderer(Renderer renderer, Timeline timeline, int periodIndex)
      throws ExoPlaybackException {
    Format imageFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
            .setTileCountHorizontal(1)
            .setTileCountVertical(1)
            .build();
    enableRenderer(renderer, timeline, periodIndex, imageFormat);
  }

  private static void enableRenderer(
      Renderer renderer, Timeline timeline, int periodIndex, Format format)
      throws ExoPlaybackException {
    enableRenderer(
        renderer,
        timeline,
        periodIndex,
        format,
        /* mayRenderStartOfStream= */ true,
        /* offsetUs= */ 0L);
  }

  private static void enableRenderer(
      Renderer renderer,
      Timeline timeline,
      int periodIndex,
      Format format,
      boolean mayRenderStartOfStream,
      long offsetUs)
      throws ExoPlaybackException {
    enableRenderer(
        renderer,
        timeline,
        periodIndex,
        format,
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 0L, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM),
        mayRenderStartOfStream,
        offsetUs);
  }

  private static void enableRenderer(
      Renderer renderer,
      Timeline timeline,
      int periodIndex,
      Format format,
      List<FakeSampleStreamItem> fakeSampleStreamItems)
      throws ExoPlaybackException {
    enableRenderer(
        renderer,
        timeline,
        periodIndex,
        format,
        fakeSampleStreamItems,
        /* mayRenderStartOfStream= */ true,
        /* offsetUs= */ 0L);
  }

  private static void enableRenderer(
      Renderer renderer,
      Timeline timeline,
      int periodIndex,
      Format format,
      List<FakeSampleStreamItem> fakeSampleStreamItems,
      boolean mayRenderStartOfStream,
      long offsetUs)
      throws ExoPlaybackException {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format,
            fakeSampleStreamItems);
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    renderer.init(/* index= */ 0, PlayerId.UNSET, SystemClock.DEFAULT);
    renderer.setTimeline(timeline);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ offsetUs,
        /* joining= */ false,
        mayRenderStartOfStream,
        /* startPositionUs= */ offsetUs,
        offsetUs,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(periodIndex)));
  }

  /** A frame processing {@link VideoSink} that records the frames queued to it. */
  private static final class FakeFrameProcessingVideoSink extends ForwardingVideoSink {

    private final List<Long> inputFramePresentationTimesUs;
    private final Surface inputSurface;

    private FakeFrameProcessingVideoSink() {
      super(mock(VideoSink.class));
      inputFramePresentationTimesUs = new ArrayList<>();
      inputSurface = new Surface(new SurfaceTexture(/* texName= */ 0));
    }

    @Override
    public boolean initialize(Format sourceFormat) {
      return true;
    }

    @Override
    public boolean isInitialized() {
      return true;
    }

    @Override
    public boolean isReady(boolean otherwiseReady) {
      return true;
    }

    @Override
    public Surface getInputSurface() {
      return inputSurface;
    }

    @Override
    public boolean isEnded() {
      // Frames are processed as soon as they are queued.
      return true;
    }

    @Override
    public boolean handleInputFrame(
        long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
      inputFramePresentationTimesUs.add(bufferPresentationTimeUs);
      videoFrameHandler.render(/* renderTimestampNs= */ bufferPresentationTimeUs * 1000);
      return true;
    }
  }

  /**
   * Calls {@link Renderer#render} until the frame reader has received {@code frameCount} frames.
   *
   * <p>The renderer hands each frame to the frame reader as soon as the decoder outputs it,
   * whatever the position. This can take several calls: a frame leaves the decoder in a later call
   * than the one that queued its sample, and the frame reader takes one frame at a time.
   */
  private static void renderUntilFrameCount(
      Renderer renderer, List<AsyncFrame> receivedFrames, int frameCount)
      throws ExoPlaybackException {
    while (receivedFrames.size() < frameCount) {
      renderer.render(/* positionUs= */ 0L, /* elapsedRealtimeUs= */ 0L);
    }
  }

  /**
   * Returns a one-byte key frame sample at time zero with the given HAGC payload as supplemental
   * data.
   */
  private static FakeSampleStreamItem sampleWithHagc(byte[] hagcPayload) {
    // The sample size, the sample, then the HAGC T.35 header and payload.
    byte[] sampleData =
        Bytes.concat(createByteArray(0, 0, 0, 1, 0, 0xB5, 0x00, 0x90, 0x00, 0x01), hagcPayload);
    return sample(
        /* timeUs= */ 0L,
        C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
        sampleData);
  }

  private static final class FakeWakeupListener implements Renderer.WakeupListener {
    private boolean onWakeupCalled;

    @Override
    public void onSleep() {}

    @Override
    public void onWakeup() {
      onWakeupCalled = true;
    }
  }
}
