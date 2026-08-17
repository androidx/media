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

package androidx.media3.transformer;

import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayer;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.test.utils.robolectric.ScrubbingModeContractTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.runner.RunWith;

/**
 * {@link Player#COMMAND_SET_AUDIO_ATTRIBUTES} and {@link CompositionPlayer#setScrubbingModeEnabled}
 * contract tests for {@link CompositionPlayer}.
 */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerScrubbingModeContractTest extends ScrubbingModeContractTest {

  @Override
  protected PlayerInfo createPlayerInfo() {
    return new CompositionPlayerInfo(createTestCompositionPlayer());
  }

  private static final class CompositionPlayerInfo implements ScrubbingModeContractTest.PlayerInfo {
    private final CompositionPlayer player;

    private CompositionPlayerInfo(CompositionPlayer player) {
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
      return checkNotNull(player.getPlaybackLooper());
    }

    @Override
    public Looper getAudioFocusListenerLooper() {
      return player.getApplicationLooper();
    }

    @Override
    public void setMediaItem(MediaItem item) {
      EditedMediaItem editedMediaItem =
          new EditedMediaItem.Builder(item).setDurationUs(1_000_000).build();
      EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
      player.setComposition(new Composition.Builder(sequence).build());
    }

    @Override
    public void setScrubbingModeEnabled(boolean scrubbingModeEnabled) {
      player.setScrubbingModeEnabled(scrubbingModeEnabled);
    }
  }
}
