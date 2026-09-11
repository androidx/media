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

import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link SequenceRenderersFactory}. */
@RunWith(AndroidJUnit4.class)
public final class SequenceRenderersFactoryTest {

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
    List<HardwareBufferFrame> receivedFrames = new ArrayList<>();
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
    receivedFrames.get(0).release(/* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();

    fakeWakeupListener.onWakeupCalled = false;
    imageRenderer.disable();

    receivedFrames.get(1).release(/* releaseFence= */ null);
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
    List<HardwareBufferFrame> receivedFrames = new ArrayList<>();
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
    receivedFrames.get(0).release(/* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isTrue();

    fakeWakeupListener.onWakeupCalled = false;
    videoRenderer.disable();

    receivedFrames.get(1).release(/* releaseFence= */ null);
    shadowOf(handlerThread.getLooper()).idle();

    assertThat(fakeWakeupListener.onWakeupCalled).isFalse();
  }

  private static SequenceRenderersFactory createFactoryForHardwareBuffer(
      Supplier<HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier) {
    return SequenceRenderersFactory.createForHardwareBuffer(
        getApplicationContext(),
        new PlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(),
            new DefaultAudioSink.Builder(getApplicationContext()).build()),
        /* imageDecoderFactory= */ mock(ImageDecoder.Factory.class),
        /* inputIndex= */ 0,
        /* videoPrewarmingEnabled= */ false,
        /* compositionRendererListener= */ mock(
            SequenceRenderersFactory.CompositionRendererListener.class),
        hardwareBufferFrameReaderSupplier,
        /* lateThresholdToDropInputUs= */ 0);
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

  private static HardwareBufferFrameReader createHardwareBufferFrameReader(
      EditedMediaItemSequence sequence, List<HardwareBufferFrame> receivedFrames, Looper looper) {
    Composition composition = new Composition.Builder(sequence).build();
    return new HardwareBufferFrameReader(
        composition,
        /* sequenceIndex= */ 0,
        /* frameConsumer= */ receivedFrames::add,
        looper,
        /* defaultSurfacePixelFormat= */ ImageFormat.YUV_420_888,
        new DefaultImageReaderAdapter.Factory(),
        /* listener= */ e -> {},
        SystemClock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
        /* hardwareBufferJniWrapper= */ null);
  }

  private static void enableRenderer(Renderer renderer, Timeline timeline)
      throws ExoPlaybackException {
    renderer.init(/* index= */ 0, PlayerId.UNSET, SystemClock.DEFAULT);
    renderer.setTimeline(timeline);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {new Format.Builder().build()},
        mock(SampleStream.class),
        /* positionUs= */ 0L,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)));
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
