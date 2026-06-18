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
package androidx.media3.test.utils;

import static androidx.media3.test.utils.PlayerFence.futureWhen;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.SurfaceTexture;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.ElapsedRealtimeTicker;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link PlayerFence} from a Java-only context (without coroutines or suspend functions).
 *
 * <p>These tests aren't exhaustive, most edge cases that apply to both Java and Kotlin consumers
 * only need to be tested from the Kotlin {@link PlayerFenceTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class PlayerFenceJavaTest {

  private static final MediaItem MP3_ITEM = MediaItem.fromUri("asset:///media/mp3/bear-id3.mp3");
  private static final MediaItem SHORT_MP3_ITEM =
      MediaItem.fromUri("asset:///media/mp3/play-trimmed.mp3");
  private static final MediaItem MP4_ITEM = MediaItem.fromUri("asset:///media/mp4/sample.mp4");

  @Nullable private Player player;

  @After
  public void releasePlayer() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (player != null) {
                player.release();
              }
            });
  }

  @Test
  public void entersPlaybackState_ready() throws Exception {
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              playerReadyFuture.setFuture(
                  futureWhen(player).entersPlaybackState(Player.STATE_READY));

              player.setMediaItem(SHORT_MP3_ITEM);
              player.prepare();
            });

    playerReadyFuture.get();

    getInstrumentation()
        .runOnMainSync(() -> assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_READY));
  }

  @Test
  public void entersPlaybackState_timeout_cancelsFuture() throws Exception {
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              playerReadyFuture.setFuture(
                  futureWhen(player).withTimeoutMs(100).entersPlaybackState(Player.STATE_READY));

              player.setMediaItem(SHORT_MP3_ITEM);
              // Don't prepare the player, so it will never become ready.
            });

    Stopwatch stopwatch = Stopwatch.createStarted(new ElapsedRealtimeTicker());
    assertThrows(CancellationException.class, playerReadyFuture::get);
    assertThat(stopwatch.elapsed(MILLISECONDS)).isLessThan(200);
  }

  @Test
  public void entersPlaybackState_nonMainThreadPlayer() throws Exception {
    AtomicReference<Player> player = new AtomicReference<>();
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    HandlerThread backgroundThread = new HandlerThread("non-main-thread-for-test");
    backgroundThread.start();
    Handler handler = new Handler(backgroundThread.getLooper());
    handler.post(
        () -> {
          player.set(
              new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                  .build());
          playerReadyFuture.setFuture(
              futureWhen(player.get()).entersPlaybackState(Player.STATE_READY));

          player.get().setMediaItem(SHORT_MP3_ITEM);
          player.get().prepare();
        });

    playerReadyFuture.get();

    ConditionVariable playerReleased = new ConditionVariable();
    handler.post(
        () -> {
          assertThat(player.get().getPlaybackState()).isEqualTo(Player.STATE_READY);
          player.get().release();
          playerReleased.open();
        });
    playerReleased.block();
    backgroundThread.quit();
  }

  @Test
  public void entersPlaybackState_blockOnFutureBeforeItsReady() throws Exception {
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    // Deliberately don't use runOnMainSync so it doesn't block the test thread, allowing us to
    // race.
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              sleepUninterruptibly(100, MILLISECONDS);
              playerReadyFuture.setFuture(
                  futureWhen(player).entersPlaybackState(Player.STATE_READY));

              player.setMediaItem(SHORT_MP3_ITEM);
              player.prepare();
            });

    // This call will happen before the entersPlaybackState() call above.
    playerReadyFuture.get();

    getInstrumentation()
        .runOnMainSync(() -> assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_READY));
  }

  @Test
  public void entersPlaybackState_wrongThread_throws() throws Exception {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              player.setMediaItem(SHORT_MP3_ITEM);
              player.prepare();
            });

    assertThrows(
        IllegalStateException.class,
        () -> futureWhen(player).entersPlaybackState(Player.STATE_READY));
  }

  @Test
  public void isPlaying() throws Exception {
    SettableFuture<Void> isPlayingFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              isPlayingFuture.setFuture(futureWhen(player).isPlaying(true));

              player.setMediaItem(SHORT_MP3_ITEM);
              player.prepare();
              player.play();
            });

    isPlayingFuture.get();

    getInstrumentation().runOnMainSync(() -> assertThat(player.isPlaying()).isTrue());
  }

  @Test
  public void rendersFirstFrame() throws Exception {
    AtomicReference<Surface> surface = new AtomicReference<>();
    AtomicReference<SurfaceTexture> surfaceTexture = new AtomicReference<>();
    SettableFuture<Void> firstFrameRenderedFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              firstFrameRenderedFuture.setFuture(futureWhen(player).rendersFirstFrame());

              surfaceTexture.set(new SurfaceTexture(10));
              surface.set(new Surface(surfaceTexture.get()));
              player.setVideoSurface(surface.get());
              player.setMediaItem(MP4_ITEM);
              player.prepare();
              player.play();
            });

    firstFrameRenderedFuture.get();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.clearVideoSurface();
              surface.get().release();
              surfaceTexture.get().release();
            });
  }

  @Test
  public void passesContentPosition() throws Exception {
    AtomicReference<Player> player = new AtomicReference<>();
    SettableFuture<Void> contentPositionFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              ExoPlayer exoPlayer =
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build();
              player.set(exoPlayer);
              contentPositionFuture.setFuture(futureWhen(exoPlayer).passesContentPosition(1000L));

              exoPlayer.setMediaItem(MP3_ITEM);
              exoPlayer.prepare();
              exoPlayer.play();
            });

    contentPositionFuture.get();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              assertThat(player.get().getContentPosition()).isAtLeast(1000L);
              player.get().release();
            });
  }
}
