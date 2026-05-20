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

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final MediaItem SHORT_MP3_ITEM =
      MediaItem.fromUri("asset:///media/mp3/play-trimmed.mp3");

  @Test
  public void entersPlaybackState_ready() throws Exception {
    AtomicReference<Player> player = new AtomicReference<>();
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
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

    getInstrumentation()
        .runOnMainSync(
            () -> {
              assertThat(player.get().getPlaybackState()).isEqualTo(Player.STATE_READY);
              player.get().release();
            });
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
    AtomicReference<Player> player = new AtomicReference<>();
    SettableFuture<Void> playerReadyFuture = SettableFuture.create();
    // Deliberately don't use runOnMainSync so it doesn't block the test thread, allowing us to
    // race.
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              player.set(
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build());
              sleepUninterruptibly(100, MILLISECONDS);
              playerReadyFuture.setFuture(
                  futureWhen(player.get()).entersPlaybackState(Player.STATE_READY));

              player.get().setMediaItem(SHORT_MP3_ITEM);
              player.get().prepare();
            });

    // This call will happen before the playbackStateFuture() call above.
    playerReadyFuture.get();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              assertThat(player.get().getPlaybackState()).isEqualTo(Player.STATE_READY);
              player.get().release();
            });
  }

  @Test
  public void entersPlaybackState_wrongThread_throws() throws Exception {
    AtomicReference<Player> player = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(
                  new ExoPlayer.Builder(getInstrumentation().getContext().getApplicationContext())
                      .build());
              player.get().setMediaItem(SHORT_MP3_ITEM);
              player.get().prepare();
            });

    assertThrows(
        IllegalStateException.class,
        () -> futureWhen(player.get()).entersPlaybackState(Player.STATE_READY));

    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.get().release();
            });
  }
}
