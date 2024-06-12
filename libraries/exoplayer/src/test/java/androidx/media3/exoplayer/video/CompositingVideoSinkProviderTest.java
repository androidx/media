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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit test for {@link CompositingVideoSinkProvider}. */
@RunWith(AndroidJUnit4.class)
public final class CompositingVideoSinkProviderTest {
  @Test
  public void builder_calledMultipleTimes_throws() {
    Context context = ApplicationProvider.getApplicationContext();
    CompositingVideoSinkProvider.Builder builder =
        new CompositingVideoSinkProvider.Builder(context, createVideoFrameReleaseControl());

    builder.build();

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void initializeSink_calledTwice_throws() throws VideoSink.VideoSinkException {
    CompositingVideoSinkProvider provider = createCompositingVideoSinkProvider();
    VideoSink sink = provider.getSink();
    sink.initialize(new Format.Builder().build());

    assertThrows(IllegalStateException.class, () -> sink.initialize(new Format.Builder().build()));
  }

  private static CompositingVideoSinkProvider createCompositingVideoSinkProvider() {
    Context context = ApplicationProvider.getApplicationContext();
    return new CompositingVideoSinkProvider.Builder(context, createVideoFrameReleaseControl())
        .setPreviewingVideoGraphFactory(new TestPreviewingVideoGraphFactory())
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

  private static class TestPreviewingVideoGraphFactory implements PreviewingVideoGraph.Factory {
    // Using a mock but we don't assert mock interactions. If needed to assert interactions, we
    // should a fake instead.
    private final PreviewingVideoGraph previewingVideoGraph =
        Mockito.mock(PreviewingVideoGraph.class);
    private final VideoFrameProcessor videoFrameProcessor = Mockito.mock(VideoFrameProcessor.class);

    @Override
    public PreviewingVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs) {
      when(previewingVideoGraph.getProcessor(anyInt())).thenReturn(videoFrameProcessor);
      when(videoFrameProcessor.registerInputFrame()).thenReturn(true);
      return previewingVideoGraph;
    }
  }
}
