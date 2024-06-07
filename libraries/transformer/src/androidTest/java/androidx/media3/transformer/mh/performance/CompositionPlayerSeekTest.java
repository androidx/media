/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.transformer.mh.performance;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.PlayerTestListener;
import androidx.media3.transformer.SurfaceTestActivity;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@link CompositionPlayer} {@linkplain CompositionPlayer#seekTo
 * seeking}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSeekTest {

  private static final long TEST_TIMEOUT_MS = 10_000;
  private static final String MP4_ASSET = "asset:///media/mp4/sample.mp4";

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context applicationContext = instrumentation.getContext().getApplicationContext();

  private CompositionPlayer compositionPlayer;
  private SurfaceView surfaceView;

  @Before
  public void setupSurfaces() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void closeActivity() {
    rule.getScenario().close();
  }

  @After
  public void releasePlayer() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
  }

  // TODO: b/320244483 - Add tests that seek into the middle of the sequence.
  @Test
  public void seekToZero_singleSequenceOfTwoVideos() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS * 1000);
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    EditedMediaItem video =
        createEditedMediaItem(
            /* videoEffects= */ ImmutableList.of(
                (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence(video, video)).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilPlayerEnded();
    listener.resetStatus();
    instrumentation.runOnMainSync(() -> compositionPlayer.seekTo(0));
    listener.waitUntilPlayerEnded();

    ImmutableList<Long> timestampsUsOfOneSequence =
        ImmutableList.of(
            0L,
            33366L,
            66733L,
            100100L,
            133466L,
            166833L,
            200200L,
            233566L,
            266933L,
            300300L,
            333666L,
            367033L,
            400400L,
            433766L,
            467133L,
            500500L,
            533866L,
            567233L,
            600600L,
            633966L,
            667333L,
            700700L,
            734066L,
            767433L,
            800800L,
            834166L,
            867533L,
            900900L,
            934266L,
            967633L,
            // Second video starts here.
            1024000L,
            1057366L,
            1090733L,
            1124100L,
            1157466L,
            1190833L,
            1224200L,
            1257566L,
            1290933L,
            1324300L,
            1357666L,
            1391033L,
            1424400L,
            1457766L,
            1491133L,
            1524500L,
            1557866L,
            1591233L,
            1624600L,
            1657966L,
            1691333L,
            1724700L,
            1758066L,
            1791433L,
            1824800L,
            1858166L,
            1891533L,
            1924900L,
            1958266L,
            1991633L);

    assertThat(inputTimestampRecordingShaderProgram.timestampsUs)
        // Seeked after the first playback ends, so the timestamps are repeated twice.
        .containsExactlyElementsIn(
            new ImmutableList.Builder<Long>()
                .addAll(timestampsUsOfOneSequence)
                .addAll(timestampsUsOfOneSequence)
                .build())
        .inOrder();
  }

  @Test
  public void seekToZero_after15framesInSingleSequenceOfTwoVideos() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS * 1000);
    ResettableCountDownLatch framesReceivedLatch = new ResettableCountDownLatch(15);
    AtomicBoolean shaderProgramShouldBlockInput = new AtomicBoolean();

    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram() {

          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
            framesReceivedLatch.countDown();
            if (framesReceivedLatch.getCount() == 0) {
              shaderProgramShouldBlockInput.set(true);
            }
          }

          @Override
          public void releaseOutputFrame(GlTextureInfo outputTexture) {
            // The input listener capacity is reported in the super method, block input by skip
            // reporting input capacity.
            if (shaderProgramShouldBlockInput.get()) {
              return;
            }
            super.releaseOutputFrame(outputTexture);
          }

          @Override
          public void flush() {
            super.flush();
            shaderProgramShouldBlockInput.set(false);
            framesReceivedLatch.reset(Integer.MAX_VALUE);
          }
        };
    EditedMediaItem video =
        createEditedMediaItem(
            /* videoEffects= */ ImmutableList.of(
                (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence(video, video)).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    // Wait until the number of frames are received, block further input on the shader program.
    framesReceivedLatch.await();
    instrumentation.runOnMainSync(() -> compositionPlayer.seekTo(0));
    listener.waitUntilPlayerEnded();

    ImmutableList<Long> expectedTimestampsUs =
        ImmutableList.of(
            0L,
            33366L,
            66733L,
            100100L,
            133466L,
            166833L,
            200200L,
            233566L,
            266933L,
            300300L,
            333666L,
            367033L,
            400400L,
            433766L,
            467133L,
            // 15 frames, seek
            0L,
            33366L,
            66733L,
            100100L,
            133466L,
            166833L,
            200200L,
            233566L,
            266933L,
            300300L,
            333666L,
            367033L,
            400400L,
            433766L,
            467133L,
            500500L,
            533866L,
            567233L,
            600600L,
            633966L,
            667333L,
            700700L,
            734066L,
            767433L,
            800800L,
            834166L,
            867533L,
            900900L,
            934266L,
            967633L,
            // Second video starts here.
            1000001024000L,
            1000001057366L,
            1000001090733L,
            1000001124100L,
            1000001157466L,
            1000001190833L,
            1000001224200L,
            1000001257566L,
            1000001290933L,
            1000001324300L,
            1000001357666L,
            1000001391033L,
            1000001424400L,
            1000001457766L,
            1000001491133L,
            1000001524500L,
            1000001557866L,
            1000001591233L,
            1000001624600L,
            1000001657966L,
            1000001691333L,
            1000001724700L,
            1000001758066L,
            1000001791433L,
            1000001824800L,
            1000001858166L,
            1000001891533L,
            1000001924900L,
            1000001958266L,
            1000001991633L);

    assertThat(inputTimestampRecordingShaderProgram.timestampsUs)
        .containsExactlyElementsIn(expectedTimestampsUs)
        .inOrder();
  }

  private static EditedMediaItem createEditedMediaItem(List<Effect> videoEffects) {
    return new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET))
        .setDurationUs(1_024_000)
        .setEffects(new Effects(/* audioProcessors= */ ImmutableList.of(), videoEffects))
        .build();
  }

  private static class InputTimestampRecordingShaderProgram extends PassthroughShaderProgram {
    public final ArrayList<Long> timestampsUs = new ArrayList<>();

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
      timestampsUs.add(presentationTimeUs);
    }
  }

  private static final class ResettableCountDownLatch {
    private CountDownLatch latch;

    public ResettableCountDownLatch(int count) {
      latch = new CountDownLatch(count);
    }

    public void await() throws InterruptedException {
      latch.await();
    }

    public void countDown() {
      latch.countDown();
    }

    public long getCount() {
      return latch.getCount();
    }

    public void reset(int count) {
      latch = new CountDownLatch(count);
    }
  }
}
