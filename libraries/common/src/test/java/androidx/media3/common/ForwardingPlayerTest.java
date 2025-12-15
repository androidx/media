/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.common;

import static androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED;
import static androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION;
import static androidx.media3.common.Player.EVENT_TIMELINE_CHANGED;
import static androidx.media3.test.utils.TestUtil.assertForwardingClassForwardsAllMethodsExcept;
import static androidx.media3.test.utils.TestUtil.assertSubclassOverridesAllMethods;
import static androidx.media3.test.utils.TestUtil.getInnerClass;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.annotation.Nullable;
import androidx.media3.test.utils.StubPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ForwardingPlayer}. */
@RunWith(AndroidJUnit4.class)
public class ForwardingPlayerTest {

  @Test
  public void addListener_addsForwardingListener_toEqualityBasedPlayer() {
    EqualityBasedRelaxedFakePlayer player = new EqualityBasedRelaxedFakePlayer();
    Player.Listener listener1 = new AllIsEqualPlayerListener();
    Player.Listener listener2 = new AllIsEqualPlayerListener();

    // Even though the listeners are equal, ForwardingPlayer should hide this from the player.
    ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player);
    forwardingPlayer.addListener(listener1);
    // Add listener1 again.
    forwardingPlayer.addListener(listener1);
    forwardingPlayer.addListener(listener2);

    assertThat(player.listeners).hasSize(2);
  }

  @Test
  public void removeListener_removesForwardingListener_toEqualityBasedPlayer() {
    EqualityBasedRelaxedFakePlayer player = new EqualityBasedRelaxedFakePlayer();
    Player.Listener listener1 = new AllIsEqualPlayerListener();
    Player.Listener listener2 = new AllIsEqualPlayerListener();

    // Even though the listeners are equal, ForwardingPlayer should hide this from the player.
    ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player);
    forwardingPlayer.addListener(listener1);
    forwardingPlayer.addListener(listener2);

    forwardingPlayer.removeListener(listener1);
    assertThat(player.listeners).hasSize(1);
    // Remove same listener again.
    forwardingPlayer.removeListener(listener1);
    assertThat(player.listeners).hasSize(1);
    forwardingPlayer.removeListener(listener2);
    assertThat(player.listeners).isEmpty();
  }

  @Test
  public void addListener_addsForwardingListener_toIdentityBasedPlayer() {
    IdentityBasedStrictFakePlayer player = new IdentityBasedStrictFakePlayer();
    Player.Listener listener1 = new AllIsEqualPlayerListener();
    Player.Listener listener2 = new AllIsEqualPlayerListener();

    // The listeners are equal, but the Player handles that, and ForwardingPlayer should, too.
    ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player);
    forwardingPlayer.addListener(listener1);
    // Add listener1 again.
    assertThrows(IllegalArgumentException.class, () -> forwardingPlayer.addListener(listener1));
    assertThat(player.listeners).hasSize(1);
    forwardingPlayer.addListener(listener2);
    assertThat(player.listeners).hasSize(2);
  }

  @Test
  public void removeListener_removesForwardingListener_toIdentityBasedPlayer() {
    IdentityBasedStrictFakePlayer player = new IdentityBasedStrictFakePlayer();
    Player.Listener listener1 = new AllIsEqualPlayerListener();
    Player.Listener listener2 = new AllIsEqualPlayerListener();

    // The listeners are equal, but the Player handles that, and ForwardingPlayer should, too.
    ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player);
    forwardingPlayer.addListener(listener1);
    forwardingPlayer.addListener(listener2);

    forwardingPlayer.removeListener(listener1);
    assertThat(player.listeners).hasSize(1);
    // Remove same listener again.
    assertThrows(IllegalArgumentException.class, () -> forwardingPlayer.removeListener(listener1));
    assertThat(player.listeners).hasSize(1);
    forwardingPlayer.removeListener(listener2);
    assertThat(player.listeners).isEmpty();
  }

  @Test
  public void onEvents_passesForwardingPlayerAsArgument() {
    EqualityBasedRelaxedFakePlayer player = new EqualityBasedRelaxedFakePlayer();
    Player.Listener listener = mock(Player.Listener.class);
    ForwardingPlayer forwardingPlayer = new ForwardingPlayer(player);
    forwardingPlayer.addListener(listener);
    Player.Listener forwardingListener = player.listeners.iterator().next();

    forwardingListener.onEvents(
        player,
        new Player.Events(
            new FlagSet.Builder()
                .addAll(
                    EVENT_TIMELINE_CHANGED, EVENT_MEDIA_ITEM_TRANSITION, EVENT_IS_PLAYING_CHANGED)
                .build()));

    ArgumentCaptor<Player.Events> eventsArgumentCaptor =
        ArgumentCaptor.forClass(Player.Events.class);
    verify(listener).onEvents(same(forwardingPlayer), eventsArgumentCaptor.capture());
    Player.Events receivedEvents = eventsArgumentCaptor.getValue();
    assertThat(receivedEvents.size()).isEqualTo(3);
    assertThat(receivedEvents.contains(EVENT_TIMELINE_CHANGED)).isTrue();
    assertThat(receivedEvents.contains(EVENT_MEDIA_ITEM_TRANSITION)).isTrue();
    assertThat(receivedEvents.contains(EVENT_IS_PLAYING_CHANGED)).isTrue();
  }

  @Test
  public void forwardingPlayer_overridesAllPlayerMethods() throws Exception {
    assertSubclassOverridesAllMethods(Player.class, ForwardingPlayer.class);
  }

  @Test
  public void forwardingPlayer_forwardsAllPlayerMethods() throws Exception {
    // addListener and removeListener don't directly forward their parameters due to wrapping in
    // ForwardingListener. They are tested separately in addListener_addsForwardingListener() and
    // removeListener_removesForwardingListener().
    assertForwardingClassForwardsAllMethodsExcept(
        Player.class,
        ForwardingPlayer::new,
        /* excludedMethods= */ ImmutableSet.of("addListener", "removeListener"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void forwardingListener_overridesAllListenerMethods() throws Exception {
    // Check with reflection that ForwardingListener overrides all Listener methods.
    Class<? extends Player.Listener> forwardingListenerClass =
        (Class<? extends Player.Listener>)
            checkNotNull(getInnerClass(ForwardingPlayer.class, "ForwardingListener"));
    assertSubclassOverridesAllMethods(Player.Listener.class, forwardingListenerClass);
  }

  /**
   * A {@link Player} that compares registered {@link Player.Listener} instances with {@link
   * Object#equals(Object)}, and silently ignores duplicate registrations and removals of
   * unrecognized listeners.
   */
  private static final class EqualityBasedRelaxedFakePlayer extends StubPlayer {

    private final Set<Listener> listeners = new HashSet<>();

    @Override
    public void addListener(Listener listener) {
      listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
      listeners.remove(listener);
    }
  }

  /**
   * A {@link Player} that compares registered {@link Player.Listener} instances with reference
   * equality ({@code ==}), and throws an error on duplicate registrations and removals of
   * unrecognized listeners.
   */
  private static final class IdentityBasedStrictFakePlayer extends StubPlayer {

    private final List<Listener> listeners = new ArrayList<>();

    @Override
    public void addListener(Listener listener) {
      for (Listener listener1 : listeners) {
        if (listener == listener1) {
          throw new IllegalArgumentException("Trying to add duplicate listener");
        }
      }
      listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
      int found = -1;
      for (int i = 0; i < listeners.size(); i++) {
        if (listener == listeners.get(i)) {
          found = i;
        }
      }
      if (found == -1) {
        throw new IllegalArgumentException("Trying to remove listener that doesn't exist");
      }
      listeners.remove(found);
    }
  }

  private static final class AllIsEqualPlayerListener implements Player.Listener {

    @Override
    public boolean equals(@Nullable Object obj) {
      return true;
    }

    @Override
    public int hashCode() {
      return 2;
    }
  }
}
