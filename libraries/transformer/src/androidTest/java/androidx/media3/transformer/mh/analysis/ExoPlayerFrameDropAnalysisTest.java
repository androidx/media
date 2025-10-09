/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.transformer.mh.analysis;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.SystemClock;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.test.utils.TestSummaryLogger;
import androidx.media3.transformer.PlayerTestListener;
import androidx.media3.transformer.SurfaceTestActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Instrumentation tests for analyzing {@link ExoPlayer} frame dropping behavior. */
@RunWith(Parameterized.class)
@Ignore("Analysis tests do not verify correctness take a long time to run.")
public class ExoPlayerFrameDropAnalysisTest {
  private static final ImmutableSet<String> INPUT_ASSETS =
      ImmutableSet.of(
          "asset:///media/mp4/long_4k_av1.mp4",
          "asset:///media/mp4/long_1080p_av1.mp4",
          "asset:///media/mp4/long_180p_av1.mp4");

  private static final long TIMEOUT_MS = 120_000;
  private static final long TEST_PLAYBACK_DURATION_MS = 30_000;
  private static final long CLOCK_JUMP_INTERVAL_MS = 2_000;
  private static final long CLOCK_JUMP_AMOUNT_MS = 300;

  @Rule public final TestName testName = new TestName();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  @Parameter(0)
  public TestConfig testConfig;

  @Parameters(name = "{0}")
  public static List<TestConfig> parameters() {
    return Sets.cartesianProduct(
            INPUT_ASSETS,
            ImmutableSet.of(0.4f, 0.5f, 1f, 2f),
            ImmutableSet.of(0L, 15_000L, 50_000L))
        .stream()
        .map(
            testConfigArguments ->
                new TestConfig(
                    /* uri= */ (String) testConfigArguments.get(0),
                    /* playbackSpeed= */ (Float) testConfigArguments.get(1),
                    /* lateThresholdUs= */ (Long) testConfigArguments.get(2)))
        .collect(toImmutableList());
  }

  private final Context context = ApplicationProvider.getApplicationContext();
  private @MonotonicNonNull ExoPlayer player;
  private SurfaceView surfaceView;

  @Before
  public void setUp() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (player != null) {
                player.release();
              }
            });
  }

  @Test
  public void analyzeExoPlayerFrameDrops() throws Exception {
    PlayerTestListener playerTestListener = new PlayerTestListener(TIMEOUT_MS);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(testConfig.uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs((long) (TEST_PLAYBACK_DURATION_MS * testConfig.playbackSpeed))
                    .build())
            .build();
    AtomicReference<String> decoderName = new AtomicReference<>();
    AtomicReference<DecoderCounters> decoderCounters = new AtomicReference<>();
    AnalyticsListener videoDecoderListener =
        new AnalyticsListener() {
          @Override
          public void onVideoDecoderInitialized(
              EventTime eventTime,
              String newDecoderName,
              long initializedTimestampMs,
              long initializationDurationMs) {
            checkState(decoderName.compareAndSet(/* expectedValue= */ null, newDecoderName));
          }

          @Override
          public void onVideoEnabled(EventTime eventTime, DecoderCounters newDecoderCounters) {
            checkState(
                decoderCounters.compareAndSet(/* expectedValue= */ null, newDecoderCounters));
          }
        };
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(
                          context,
                          new DefaultRenderersFactory(context)
                              .experimentalSetParseAv1SampleDependencies(
                                  /* parseAv1SampleDependencies= */ testConfig.lateThresholdUs != 0)
                              .experimentalSetLateThresholdToDropDecoderInputUs(
                                  testConfig.lateThresholdUs))
                      .setClock(new JumpingClock())
                      .build();
              player.setTrackSelectionParameters(
                  new TrackSelectionParameters.Builder()
                      .setDisabledTrackTypes(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
                      .build());
              player.setMediaItem(mediaItem);
              player.setPlaybackSpeed(testConfig.playbackSpeed);
              player.setVideoSurfaceView(surfaceView);
              player.addListener(playerTestListener);
              player.addAnalyticsListener(videoDecoderListener);
              player.prepare();
              player.setPlayWhenReady(true);
            });

    playerTestListener.waitUntilPlayerEnded();

    JSONObject resultJson = testConfig.toJsonObject();
    resultJson.put("decoderName", decoderName.get());
    resultJson.put("queuedInputBufferCount", decoderCounters.get().queuedInputBufferCount);
    resultJson.put("droppedBufferCount", decoderCounters.get().droppedBufferCount);
    resultJson.put("droppedInputBufferCount", decoderCounters.get().droppedInputBufferCount);
    resultJson.put(
        "maxConsecutiveDroppedBufferCount", decoderCounters.get().maxConsecutiveDroppedBufferCount);
    resultJson.put("droppedToKeyframeCount", decoderCounters.get().droppedToKeyframeCount);
    TestSummaryLogger.writeTestSummaryToFile(
        ApplicationProvider.getApplicationContext(),
        /* testId= */ testName.getMethodName(),
        resultJson);
  }

  private static class TestConfig {
    public final String uri;
    public final float playbackSpeed;
    public final long lateThresholdUs;

    public TestConfig(String uri, float playbackSpeed, long lateThresholdUs) {
      this.uri = uri;
      this.playbackSpeed = playbackSpeed;
      this.lateThresholdUs = lateThresholdUs;
    }

    @Override
    public String toString() {
      return String.format(
          "%s_sp_%f_lateUs_%d",
          Uri.parse(uri).getLastPathSegment(), playbackSpeed, lateThresholdUs);
    }

    public JSONObject toJsonObject() throws JSONException {
      JSONObject resultJson = new JSONObject();
      resultJson.put("file", Uri.parse(uri).getLastPathSegment());
      resultJson.put("playbackSpeed", playbackSpeed);
      resultJson.put("lateThresholdUs", lateThresholdUs);
      return resultJson;
    }
  }

  private static class JumpingClock extends SystemClock {
    private long offsetMs;
    private long nextJumpMs;

    public JumpingClock() {
      super();
      nextJumpMs = super.elapsedRealtime() + CLOCK_JUMP_INTERVAL_MS;
    }

    @Override
    public long elapsedRealtime() {
      long clockTimeMs = super.elapsedRealtime();
      if (clockTimeMs >= nextJumpMs) {
        nextJumpMs += CLOCK_JUMP_INTERVAL_MS;
        offsetMs += CLOCK_JUMP_AMOUNT_MS;
      }

      return clockTimeMs + offsetMs;
    }
  }
}
