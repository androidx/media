/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.VideoGraph;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit test for {@link PlaybackVideoGraphWrapper}. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackVideoGraphWrapperTest {
  @Test
  public void builder_calledMultipleTimes_throws() {
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper.Builder builder =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl());

    builder.build();

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void initializeSink_calledTwice_throws() throws VideoSink.VideoSinkException {
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(new TestVideoGraphFactory());
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);
    sink.initialize(new Format.Builder().build());

    assertThrows(IllegalStateException.class, () -> sink.initialize(new Format.Builder().build()));
  }

  @Test
  public void onInputStreamChanged_setsVideoSinkVideoEffects() throws VideoSink.VideoSinkException {
    ImmutableList<Effect> firstEffects = ImmutableList.of(Mockito.mock(Effect.class));
    ImmutableList<Effect> secondEffects =
        ImmutableList.of(Mockito.mock(Effect.class), Mockito.mock(Effect.class));
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    Format format = new Format.Builder().build();
    long startPositionUs = 0;
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    sink.initialize(format);

    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        format,
        startPositionUs,
        RELEASE_FIRST_FRAME_IMMEDIATELY,
        firstEffects);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        format,
        startPositionUs,
        RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED,
        secondEffects);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        format,
        startPositionUs,
        RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED,
        ImmutableList.of());
    testVideoGraphFactory.verifyRegisterInputStream(/* invocationTimes= */ 3);
    assertThat(testVideoGraphFactory.getCapturedEffects())
        .isEqualTo(ImmutableList.of(firstEffects, secondEffects, ImmutableList.of()));
  }

  @Test
  public void onInputStreamChanged_withNoToneMapping_initializesGraphWithInputColorInfo()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    ColorInfo hlgColorInfo =
        new ColorInfo.Builder()
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build();
    Format inputFormat = new Format.Builder().setColorInfo(hlgColorInfo).build();
    long startPositionUs = 0;
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    sink.initialize(inputFormat);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        inputFormat,
        startPositionUs,
        RELEASE_FIRST_FRAME_IMMEDIATELY,
        /* videoEffects= */ ImmutableList.of());

    testVideoGraphFactory.verifyRegisterInputStream(/* invocationTimes= */ 1);
    assertThat(testVideoGraphFactory.getCapturedFormats()).containsExactly(inputFormat);
  }

  @Test
  public void onInputStreamChanged_withInputSdrToneMapped_initializesGraphWithBt709Input()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    ColorInfo hlgColorInfo =
        new ColorInfo.Builder()
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build();
    Format inputFormat = new Format.Builder().setColorInfo(hlgColorInfo).build();
    long startPositionUs = 0;
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    playbackVideoGraphWrapper.setIsInputSdrToneMapped(true);
    sink.initialize(inputFormat);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        inputFormat,
        startPositionUs,
        RELEASE_FIRST_FRAME_IMMEDIATELY,
        /* videoEffects= */ ImmutableList.of());

    testVideoGraphFactory.verifyRegisterInputStream(/* invocationTimes= */ 1);
    Format bt709ColorInfoInputFormat =
        inputFormat.buildUpon().setColorInfo(SDR_BT709_LIMITED).build();
    assertThat(testVideoGraphFactory.getCapturedFormats())
        .containsExactly(bt709ColorInfoInputFormat);
  }

  @Test
  public void onInputStreamChanged_withOpenGlToneMapping_initializesGraphWithInputColorInfo()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    ColorInfo hlgColorInfo =
        new ColorInfo.Builder()
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build();
    Format inputFormat = new Format.Builder().setColorInfo(hlgColorInfo).build();
    long startPositionUs = 0;
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    playbackVideoGraphWrapper.setRequestOpenGlToneMapping(true);
    sink.initialize(inputFormat);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE,
        inputFormat,
        startPositionUs,
        RELEASE_FIRST_FRAME_IMMEDIATELY,
        /* videoEffects= */ ImmutableList.of());

    testVideoGraphFactory.verifyRegisterInputStream(/* invocationTimes= */ 1);
    assertThat(testVideoGraphFactory.getCapturedFormats()).containsExactly(inputFormat);
  }

  @Test
  public void inputSinkInitialized_withGamma22_createsGraphWithBT709()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    ColorInfo gamma22ColorInfo =
        new ColorInfo.Builder()
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_GAMMA_2_2)
            .build();
    Format inputFormat = new Format.Builder().setColorInfo(gamma22ColorInfo).build();
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    sink.initialize(inputFormat);

    assertThat(testVideoGraphFactory.outputColorInfo).isEqualTo(SDR_BT709_LIMITED);
  }

  @Test
  public void handleInputFrame_withSlightlyDelayedForecaster_doesNotProcessOrDropInputFrame()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    AtomicBoolean frameDropped = new AtomicBoolean(false);
    VideoSink.VideoFrameHandler videoFrameHandler =
        new VideoSink.VideoFrameHandler() {
          @Override
          public void render(long renderTimestampNs) {}

          @Override
          public void skip() {
            frameDropped.set(true);
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
            .setVideoGraphFactory(testVideoGraphFactory)
            .setVideoFrameReleaseEarlyTimeForecaster(forecaster)
            .build();
    Format inputFormat = new Format.Builder().build();
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);
    // The only processed frame is 10ms late -- and the next frame should be dropped.
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ -10_000);

    sink.initialize(inputFormat);
    boolean frameHandled =
        sink.handleInputFrame(/* bufferPresentationTimeUs= */ 100, videoFrameHandler);

    assertThat(frameHandled).isFalse();
    assertThat(frameDropped.get()).isFalse();
  }

  @Test
  public void handleInputFrame_withMoreDelayedForecaster_dropsInputFrame()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    AtomicBoolean frameDropped = new AtomicBoolean(false);
    VideoSink.VideoFrameHandler videoFrameHandler =
        new VideoSink.VideoFrameHandler() {
          @Override
          public void render(long renderTimestampNs) {}

          @Override
          public void skip() {
            frameDropped.set(true);
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
            .setVideoGraphFactory(testVideoGraphFactory)
            .setVideoFrameReleaseEarlyTimeForecaster(forecaster)
            .build();
    Format inputFormat = new Format.Builder().build();
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);
    // The only processed frame is 20ms late -- and the next frame should be dropped.
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ -20_000);

    sink.initialize(inputFormat);
    boolean frameHandled =
        sink.handleInputFrame(/* bufferPresentationTimeUs= */ 100, videoFrameHandler);

    assertThat(frameHandled).isTrue();
    assertThat(frameDropped.get()).isTrue();
  }

  @Test
  public void handleInputFrame_withDelayedForecaster_dropsUpTo2ConsecutiveFrames()
      throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    AtomicInteger droppedFramesCount = new AtomicInteger();
    VideoSink.VideoFrameHandler videoFrameHandler =
        new VideoSink.VideoFrameHandler() {
          @Override
          public void render(long renderTimestampNs) {}

          @Override
          public void skip() {
            droppedFramesCount.incrementAndGet();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
            .setVideoGraphFactory(testVideoGraphFactory)
            .setVideoFrameReleaseEarlyTimeForecaster(forecaster)
            .build();
    Format inputFormat = new Format.Builder().build();
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);
    // The only processed frame is 20ms late -- and the next frames should be dropped.
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ -20_000);

    sink.initialize(inputFormat);
    sink.handleInputFrame(/* bufferPresentationTimeUs= */ 98, videoFrameHandler);
    sink.handleInputFrame(/* bufferPresentationTimeUs= */ 99, videoFrameHandler);
    boolean thirdFrameHandled =
        sink.handleInputFrame(/* bufferPresentationTimeUs= */ 100, videoFrameHandler);

    assertThat(thirdFrameHandled).isFalse();
    assertThat(droppedFramesCount.get()).isEqualTo(2);
  }

  @Test
  public void setPlaybackSpeed_updatesForecaster() throws VideoSink.VideoSinkException {
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        Mockito.mock(VideoFrameReleaseEarlyTimeForecaster.class);
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
            .setVideoGraphFactory(testVideoGraphFactory)
            .setVideoFrameReleaseEarlyTimeForecaster(forecaster)
            .build();
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    sink.setPlaybackSpeed(2f);

    verify(forecaster).setPlaybackSpeed(2f);
  }

  private static PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
      VideoGraph.Factory videoGraphFactory) {
    Context context = ApplicationProvider.getApplicationContext();
    return new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
        .setVideoGraphFactory(videoGraphFactory)
        .build();
  }

  private static VideoFrameReleaseControl createVideoFrameReleaseControl() {
    Context context = ApplicationProvider.getApplicationContext();
    VideoFrameReleaseControl.FrameTimingEvaluator frameTimingEvaluator =
        new VideoFrameReleaseControl.FrameTimingEvaluator() {
          @Override
          public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
            return false;
          }

          @Override
          public boolean shouldDropFrame(
              long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
            return false;
          }

          @Override
          public boolean shouldIgnoreFrame(
              long earlyUs,
              long positionUs,
              long elapsedRealtimeUs,
              boolean isLastFrame,
              boolean treatDroppedBuffersAsSkipped) {
            return false;
          }
        };
    return new VideoFrameReleaseControl(
        context, frameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
  }

  private static class TestVideoGraphFactory implements VideoGraph.Factory {
    // Using a mock but we don't assert mock interactions. If needed to assert interactions, we
    // should a fake instead.
    private final VideoGraph videoGraph = Mockito.mock(VideoGraph.class);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<List<Effect>> effectsCaptor = ArgumentCaptor.forClass(List.class);

    private final ArgumentCaptor<Format> formatCaptor = ArgumentCaptor.forClass(Format.class);

    public ColorInfo outputColorInfo;

    @Override
    public VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      this.outputColorInfo = outputColorInfo;

      when(videoGraph.registerInputFrame(anyInt())).thenReturn(true);
      return videoGraph;
    }

    @Override
    public boolean supportsMultipleInputs() {
      return false;
    }

    public void verifyRegisterInputStream(int invocationTimes) {
      verify(videoGraph, times(invocationTimes))
          .registerInputStream(
              /* inputIndex= */ anyInt(),
              /* inputType= */ eq(VideoSink.INPUT_TYPE_SURFACE),
              formatCaptor.capture(),
              effectsCaptor.capture(),
              /* offsetToAddUs= */ anyLong());
    }

    public List<List<Effect>> getCapturedEffects() {
      return effectsCaptor.getAllValues();
    }

    public List<Format> getCapturedFormats() {
      return formatCaptor.getAllValues();
    }
  }
}
