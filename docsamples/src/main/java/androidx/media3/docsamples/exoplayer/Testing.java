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
package androidx.media3.docsamples.exoplayer;

import static androidx.media3.test.utils.PlayerFence.futureWhen;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakePlayer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.SettableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Snippets for Testing. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public final class Testing {

  // [START robolectric_playback_test]
  @RunWith(AndroidJUnit4.class)
  @OptIn(markerClass = UnstableApi.class)
  public static class PlaybackRobolectricTest {

    private @Nullable ExoPlayer player;

    @After
    public void tearDown() {
      if (player != null) {
        player.release();
      }
    }

    @Test
    public void play_sampleMediaItem_reachesEndedState() throws Exception {
      Context context = ApplicationProvider.getApplicationContext();
      player = new TestExoPlayerBuilder(context).build();
      player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
      player.prepare();

      play(player).untilState(Player.STATE_ENDED);

      assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_ENDED);
    }
  }

  // [END robolectric_playback_test]

  @OptIn(markerClass = UnstableApi.class)
  public static void robolectricFakeMediaSource(Context context) throws Exception {
    // [START robolectric_fake_media_source]
    ExoPlayer player = new TestExoPlayerBuilder(context).build();
    FakeMediaSource fakeMediaSource = new FakeMediaSource(new FakeTimeline(/* windowCount= */ 2));
    player.setMediaSource(fakeMediaSource);
    player.prepare();

    play(player).untilMediaItemIndex(/* mediaItemIndex= */ 1);

    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(1);
    player.release();
    // [END robolectric_fake_media_source]
  }

  // [START instrumentation_playback_test]
  @RunWith(AndroidJUnit4.class)
  @OptIn(markerClass = UnstableApi.class)
  public static class PlaybackInstrumentationTest {

    private @Nullable ExoPlayer player;

    @After
    public void tearDown() {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> {
            if (player != null) {
              player.release();
            }
          });
    }

    @Test
    public void play_sampleMediaItem_advancesAndEnds() throws Exception {
      SettableFuture<Void> playbackStarted = SettableFuture.create();
      SettableFuture<Void> positionPassed = SettableFuture.create();
      SettableFuture<Void> playbackEnded = SettableFuture.create();
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            player = new ExoPlayer.Builder(context).build();
            player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
            playbackStarted.setFuture(futureWhen(player).isPlaying(true));
            positionPassed.setFuture(futureWhen(player).passesContentPosition(500));
            playbackEnded.setFuture(futureWhen(player).entersPlaybackState(Player.STATE_ENDED));
            player.prepare();
            player.play();
          });

      playbackStarted.get();
      positionPassed.get();
      playbackEnded.get();

      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_ENDED));
    }
  }

  // [END instrumentation_playback_test]

  @OptIn(markerClass = UnstableApi.class)
  public static void instrumentationCustomSupervision(ExoPlayer player, MediaItem mediaItem)
      throws Exception {
    // [START instrumentation_custom_supervision]
    SettableFuture<Void> firstFrameFuture = SettableFuture.create();
    SettableFuture<CharSequence> metadataTitleFuture = SettableFuture.create();
    SettableFuture<CharSequence> supervisedTitleFuture = SettableFuture.create();
    Player.Listener metadataListener =
        new Player.Listener() {
          @Override
          public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
            if (mediaMetadata.title != null) {
              metadataTitleFuture.set(mediaMetadata.title);
            }
          }
        };

    try {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> {
            // Register transient event fences (with a video surface attached) before prepare().
            firstFrameFuture.setFuture(futureWhen(player).rendersFirstFrame());

            // Supervise a custom ListenableFuture so player errors fail fast.
            player.addListener(metadataListener);
            supervisedTitleFuture.setFuture(
                futureWhen(player).withSupervision(metadataTitleFuture));

            player.setMediaItem(mediaItem);
            if (player.getMediaMetadata().title != null) {
              metadataTitleFuture.set(player.getMediaMetadata().title);
            }
            player.prepare();
            player.play();
          });

      firstFrameFuture.get();
      CharSequence title = supervisedTitleFuture.get();
    } finally {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(() -> {
            player.removeListener(metadataListener);
            firstFrameFuture.cancel(/* mayInterruptIfRunning= */ true);
            supervisedTitleFuture.cancel(/* mayInterruptIfRunning= */ true);
          });
    }
    // [END instrumentation_custom_supervision]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void fakePlayerTest() {
    // [START fake_player_test]
    FakePlayer fakePlayer = new FakePlayer();
    fakePlayer.setMediaItem(MediaItem.fromUri("https://example.com/media.mp4"));
    // Drive state transitions directly (prepare() simulates a 100 ms buffering delay by default).
    fakePlayer.setPlaybackState(Player.STATE_READY);
    fakePlayer.play();

    assertThat(fakePlayer.isPlaying()).isTrue();

    fakePlayer.setPosition(30_000);
    fakePlayer.setPlaybackState(Player.STATE_ENDED);
    fakePlayer.release();
    // [END fake_player_test]
  }
}
