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

package androidx.media3.exoplayer;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.robolectric.PlayerAudioFocusContractTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.runner.RunWith;

/**
 * {@linkplain Player#COMMAND_SET_AUDIO_ATTRIBUTES Player audio focus} contract tests for {@link
 * ExoPlayer}.
 */
@RunWith(AndroidJUnit4.class)
public class ExoPlayerAudioFocusContractTest extends PlayerAudioFocusContractTest {

  @Override
  protected PlayerInfo createPlayerInfo() {
    return new ExoPlayerInfo(new TestExoPlayerBuilder(getApplicationContext()).build());
  }

  private static final class ExoPlayerInfo implements PlayerInfo {
    private final ExoPlayer player;

    private ExoPlayerInfo(ExoPlayer player) {
      this.player = player;
    }

    @Override
    public Player getPlayer() {
      return player;
    }

    @Override
    public Clock getClock() {
      return player.getClock();
    }

    @Override
    public Looper getPlaybackLooper() {
      return player.getPlaybackLooper();
    }
  }
}
