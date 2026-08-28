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

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ExoPlayer} video frame metadata listeners. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerVideoFrameMetadataListenerTest {

  private Surface surface;
  private ExoPlayer player;
  private VideoFrameMetadataListener listener1;
  private VideoFrameMetadataListener listener2;

  @Before
  public void setUp() {
    Context context = ApplicationProvider.getApplicationContext();
    surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player = new TestExoPlayerBuilder(context).build();
    player.setVideoSurface(surface);
    player.setMediaSource(
        new FakeMediaSource(new FakeTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT));
    listener1 = mock(VideoFrameMetadataListener.class);
    listener2 = mock(VideoFrameMetadataListener.class);
  }

  @After
  public void tearDown() {
    player.release();
    surface.release();
  }

  @Test
  public void addAndRemoveVideoFrameMetadataListener_deliversEventsToRegisteredListeners()
      throws Exception {
    player.addVideoFrameMetadataListener(listener1);
    player.addVideoFrameMetadataListener(listener2);
    player.prepare();

    play(player).untilPosition(0, 100);

    verify(listener1, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());

    clearInvocations(listener1, listener2);
    player.removeVideoFrameMetadataListener(listener1);
    player.seekTo(0);

    play(player).untilPosition(0, 100);

    verify(listener1, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated setVideoFrameMetadataListener.
  public void setAndClearVideoFrameMetadataListener_deliversEventsAndUnregistersCorrectly()
      throws Exception {
    player.setVideoFrameMetadataListener(listener1);
    player.setVideoFrameMetadataListener(listener2);
    player.prepare();

    play(player).untilPosition(0, 100);

    verify(listener1, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());

    clearInvocations(listener1, listener2);
    player.clearVideoFrameMetadataListener(listener2);
    player.seekTo(0);

    play(player).untilPosition(0, 100);

    verify(listener2, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
  }

  @Test
  @SuppressWarnings("deprecation") // Testing interaction with deprecated methods.
  public void mixedSetAndAddCalls_deliversEventsAndUnregistersCorrectly() throws Exception {
    player.setVideoFrameMetadataListener(listener1);
    player.addVideoFrameMetadataListener(listener2);
    player.prepare();

    play(player).untilPosition(0, 100);

    verify(listener1, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());

    clearInvocations(listener1, listener2);
    player.removeVideoFrameMetadataListener(listener1);
    player.seekTo(0);

    play(player).untilPosition(0, 100);

    verify(listener1, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, atLeastOnce())
        .onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());

    clearInvocations(listener1, listener2);
    player.clearVideoFrameMetadataListener(listener2);
    player.seekTo(0);

    play(player).untilPosition(0, 100);

    verify(listener1, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
    verify(listener2, never()).onVideoFrameAboutToBeRendered(anyLong(), anyLong(), any(), any());
  }
}
