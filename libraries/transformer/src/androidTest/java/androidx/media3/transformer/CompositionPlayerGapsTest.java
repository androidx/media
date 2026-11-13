/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.PlayerFence.futureWhen;
import static androidx.media3.transformer.AndroidTestUtil.HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.test.utils.CapturingFrameProcessor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link CompositionPlayer} when the {@link EditedMediaItemSequence} has
 * gaps.
 */
@RunWith(TestParameterInjector.class)
public final class CompositionPlayerGapsTest {
  private static final long EXPECTED_GAP_WIDTH = 16;
  private static final long EXPECTED_GAP_HEIGHT = 16;
  private static final int SKIP_FRAMES_COUNT = 2;

  @Rule
  public final GlFrameProcessorTestRule glFrameProcessorTestRule = new GlFrameProcessorTestRule();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = ApplicationProvider.getApplicationContext();

  @TestParameter(valuesProvider = UseDefaultGlFrameProcessorProvider.class)
  private boolean useDefaultGlFrameProcessor;

  private @MonotonicNonNull CompositionPlayer compositionPlayer;
  private @MonotonicNonNull SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    instrumentation.runOnMainSync(() -> {
      if (compositionPlayer != null) {
        compositionPlayer.release();
      }
    });
    rule.getScenario().close();
  }

  @Test
  public void playback_withVideoGap_producesGapSizedFrames(
      @TestParameter(valuesProvider = TestCaseProvider.class) TestCase testCase) throws Exception {
    SettableFuture<Void> endedFuture = SettableFuture.create();
    final AtomicBoolean incorrectFrameDimensionsInGap = new AtomicBoolean();
    final AtomicBoolean oneByOneFramesInGapCount = new AtomicBoolean();
    final AtomicInteger renderedGapFrames = new AtomicInteger();
    VideoFrameMetadataListener videoFrameMetadataListener =
        (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
          if (presentationTimeUs >= testCase.gapStartUs && presentationTimeUs < testCase.gapEndUs) {
            // Ignore the first SKIP_FRAMES_COUNT actually rendered frames to bypass the format
            // race condition (b/438435783), regardless of their timestamps.
            if (renderedGapFrames.incrementAndGet() > SKIP_FRAMES_COUNT) {
              if (format.width == EXPECTED_GAP_WIDTH && format.height == EXPECTED_GAP_HEIGHT) {
                oneByOneFramesInGapCount.set(true);
              } else {
                // If we get a frame that is not a gap (has the expected size), that's an error.
                incorrectFrameDimensionsInGap.set(true);
              }
            }
          }
        };

    instrumentation.runOnMainSync(() -> {
      compositionPlayer = createCompositionPlayer(videoFrameMetadataListener);
      // Set a surface on the player even though there is no UI on this test. We need a surface
      // otherwise the player will skip/drop video frames.
      compositionPlayer.setVideoSurfaceView(surfaceView);
      endedFuture.setFuture(futureWhen(compositionPlayer).entersPlaybackState(Player.STATE_ENDED));
      compositionPlayer.setComposition(testCase.composition);
      compositionPlayer.prepare();
      compositionPlayer.play();
    });

    endedFuture.get();

    // If the device dropped so many frames that we couldn't even evaluate the gap,
    // skip the test rather than failing it.
    assumeTrue(
        "Device dropped too many frames to evaluate the gap",
        renderedGapFrames.get() > SKIP_FRAMES_COUNT);

    assertThat(incorrectFrameDimensionsInGap.get()).isFalse();
    assertThat(oneByOneFramesInGapCount.get()).isTrue();
  }

  private CompositionPlayer createCompositionPlayer(
      VideoFrameMetadataListener videoFrameMetadataListener) {
    if (useDefaultGlFrameProcessor && SDK_INT >= HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK) {
      FrameProcessor.Factory frameProcessorFactory =
          new CapturingFrameProcessor.Factory(
              glFrameProcessorTestRule.createDefaultGlFrameProcessorFactory(context),
              /* onQueueListener= */ frames -> {
                Frame frame = frames.get(0).frame;
                // In the FrameProcessor pipeline, CompositionPlayer invokes
                // videoFrameMetadataListener from SurfaceHolderFrameWriter, which is configured
                // once using the first frame's Format and therefore does not report the 16x16
                // input Format for gaps that follow a video item. Forward the queued input Frame's
                // Format here so the tests can verify the 16x16 gap Format entering
                // FrameProcessor.
                videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
                    frame.getContentTimeUs(),
                    /* releaseTimeNs= */ C.TIME_UNSET,
                    frame.getFormat(),
                    /* mediaFormat= */ null);
              });
      return glFrameProcessorTestRule
          .createCompositionPlayerBuilder(context)
          .setFrameProcessorFactory(frameProcessorFactory)
          .build();
    }
    CompositionPlayer player = new CompositionPlayer.Builder(context).build();
    player.setVideoFrameMetadataListener(videoFrameMetadataListener);
    return player;
  }

  private static final class TestCaseProvider extends TestParameterValuesProvider {
    private static final long GAP_DURATION_US = 1_000_000;
    private static final EditedMediaItem AUDIO_VIDEO_MEDIA_ITEM =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
            .build();
    private static final EditedMediaItem AUDIO_ONLY_MEDIA_ITEM =
        new EditedMediaItem.Builder(new MediaItem.Builder().setUri(WAV_ASSET.uri).build())
            .setDurationUs(WAV_ASSET.audioDurationUs)
            .build();

    @Override
    protected ImmutableList<TestCase> provideValues(TestParameterValuesProvider.Context context) {
      return ImmutableList.of(
          new TestCase(
              "gap+audio-video+audio-video",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addGap(GAP_DURATION_US)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .build())
                  .build(),
              /* gapStartUs= */ 0,
              /* gapEndUs= */ GAP_DURATION_US),
          new TestCase(
              "audio-video+gap+audio-video",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addGap(GAP_DURATION_US)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .build())
                  .build(),
              /* gapStartUs= */ AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs(),
              /* gapEndUs= */ AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs() + GAP_DURATION_US),
          new TestCase(
              "audio-video+audio-video+gap",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addGap(GAP_DURATION_US)
                          .build())
                  .build(),
              /* gapStartUs= */ 2 * AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs(),
              /* gapEndUs= */ 2 * AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs()
                  + GAP_DURATION_US),
          new TestCase(
              "audio+audio-video+audio-video",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addItem(AUDIO_ONLY_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .build())
                  .build(),
              /* gapStartUs= */ 0,
              /* gapEndUs= */ AUDIO_ONLY_MEDIA_ITEM.getPresentationDurationUs()),
          new TestCase(
              "audio-video+audio+audio-video",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_ONLY_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .build())
                  .build(),
              /* gapStartUs= */ AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs(),
              /* gapEndUs= */ AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs()
                  + AUDIO_ONLY_MEDIA_ITEM.getPresentationDurationUs()),
          new TestCase(
              "audio-video+audio-video+audio",
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_VIDEO_MEDIA_ITEM)
                          .addItem(AUDIO_ONLY_MEDIA_ITEM)
                          .build())
                  .build(),
              /* gapStartUs= */ 2 * AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs(),
              /* gapEndUs= */ 2 * AUDIO_VIDEO_MEDIA_ITEM.getPresentationDurationUs()
                  + AUDIO_ONLY_MEDIA_ITEM.getPresentationDurationUs()));
    }
  }

  private static final class UseDefaultGlFrameProcessorProvider
      extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<Boolean> provideValues(TestParameterValuesProvider.Context context) {
      // Provide true only on supported SDK levels to avoid triggering the AssumeSdkInt lint check.
      if (SDK_INT >= HARDWARE_BUFFER_FRAME_PROCESSOR_MIN_SDK) {
        return ImmutableList.of(false, true);
      }
      return ImmutableList.of(false);
    }
  }

  /**
   * A test case that contains a composition and the composition time range in which video frames
   * must have gap size.
   */
  private static final class TestCase {
    private final String name;
    private final Composition composition;
    private final long gapStartUs;
    private final long gapEndUs;

    private TestCase(String name, Composition composition, long gapStartUs, long gapEndUs) {
      this.name = name;
      this.composition = composition;
      this.gapStartUs = gapStartUs;
      this.gapEndUs = gapEndUs;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
