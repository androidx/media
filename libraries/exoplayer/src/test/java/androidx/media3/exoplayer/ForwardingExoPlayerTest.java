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

import static androidx.media3.test.utils.TestUtil.assertForwardingClassForwardsAllMethodsExcept;
import static androidx.media3.test.utils.TestUtil.assertSubclassOverridesAllMethods;
import static androidx.media3.test.utils.TestUtil.getInnerClass;
import static androidx.media3.test.utils.TestUtil.getPublicOverridableMethods;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.util.SparseArray;
import androidx.media3.common.FlagSet;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.test.utils.StubExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link ForwardingExoPlayer}. */
@RunWith(AndroidJUnit4.class)
public class ForwardingExoPlayerTest {

  @Test
  public void getWrappedPlayer_returnsWrappedExoPlayer() {
    FakeExoPlayer player = new FakeExoPlayer();
    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);

    assertThat(forwardingPlayer.getWrappedPlayer()).isSameInstanceAs(player);
  }

  @Test
  public void addAnalyticsListener_addsForwardingAnalyticsListener() {
    FakeExoPlayer player = new FakeExoPlayer();
    AnalyticsListener listener1 = mock(AnalyticsListener.class);
    AnalyticsListener listener2 = mock(AnalyticsListener.class);

    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.addAnalyticsListener(listener1);
    // Add listener1 again.
    forwardingPlayer.addAnalyticsListener(listener1);
    forwardingPlayer.addAnalyticsListener(listener2);

    assertThat(player.analyticsListeners).hasSize(2);
  }

  @Test
  public void removeAnalyticsListener_removesForwardingAnalyticsListener() {
    FakeExoPlayer player = new FakeExoPlayer();
    AnalyticsListener listener1 = mock(AnalyticsListener.class);
    AnalyticsListener listener2 = mock(AnalyticsListener.class);

    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.addAnalyticsListener(listener1);
    forwardingPlayer.addAnalyticsListener(listener2);

    forwardingPlayer.removeAnalyticsListener(listener1);
    assertThat(player.analyticsListeners).hasSize(1);
    // Remove same listener again.
    forwardingPlayer.removeAnalyticsListener(listener1);
    assertThat(player.analyticsListeners).hasSize(1);
    forwardingPlayer.removeAnalyticsListener(listener2);
    assertThat(player.analyticsListeners).isEmpty();
  }

  @Test
  public void getAnalyticsCollector_returnsSameInstance() {
    FakeExoPlayer player = new FakeExoPlayer();
    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);

    assertThat(forwardingPlayer.getAnalyticsCollector())
        .isSameInstanceAs(forwardingPlayer.getAnalyticsCollector());
  }

  @Test
  public void analyticsCollector_addListener_addsForwardingAnalyticsListener() {
    FakeExoPlayer player = new FakeExoPlayer();
    AnalyticsListener listener1 = mock(AnalyticsListener.class);
    AnalyticsListener listener2 = mock(AnalyticsListener.class);

    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.getAnalyticsCollector().addListener(listener1);
    // Add listener1 again.
    forwardingPlayer.getAnalyticsCollector().addListener(listener1);
    forwardingPlayer.getAnalyticsCollector().addListener(listener2);

    assertThat(player.analyticsListeners).hasSize(2);
  }

  @Test
  public void analyticsCollector_removeListener_removesForwardingAnalyticsListener() {
    FakeExoPlayer player = new FakeExoPlayer();
    AnalyticsListener listener1 = mock(AnalyticsListener.class);
    AnalyticsListener listener2 = mock(AnalyticsListener.class);

    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.getAnalyticsCollector().addListener(listener1);
    forwardingPlayer.addAnalyticsListener(listener2);

    forwardingPlayer.getAnalyticsCollector().removeListener(listener1);
    assertThat(player.analyticsListeners).hasSize(1);
    // Remove same listener again.
    forwardingPlayer.getAnalyticsCollector().removeListener(listener1);
    assertThat(player.analyticsListeners).hasSize(1);
    forwardingPlayer.getAnalyticsCollector().removeListener(listener2);
    assertThat(player.analyticsListeners).isEmpty();
  }

  @Test
  public void onEvents_playerListener_passesForwardingExoPlayerAsArgument() {
    FakeExoPlayer player = new FakeExoPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.addListener(listener);
    Player.Listener forwardingListener = Iterables.getOnlyElement(player.listeners);

    forwardingListener.onEvents(
        player,
        new Player.Events(
            new FlagSet.Builder()
                .addAll(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)
                .build()));

    ArgumentCaptor<Player.Events> eventsArgumentCaptor =
        ArgumentCaptor.forClass(Player.Events.class);
    verify(listener).onEvents(same(forwardingPlayer), eventsArgumentCaptor.capture());
    Player.Events receivedEvents = eventsArgumentCaptor.getValue();
    assertThat(receivedEvents.size()).isEqualTo(2);
    assertThat(receivedEvents.get(0)).isEqualTo(Player.EVENT_TIMELINE_CHANGED);
    assertThat(receivedEvents.get(1)).isEqualTo(Player.EVENT_IS_PLAYING_CHANGED);
  }

  @Test
  public void onEvents_analyticsListener_passesForwardingExoPlayerAsArgument() {
    FakeExoPlayer player = new FakeExoPlayer();
    AnalyticsListener listener = mock(AnalyticsListener.class);
    ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(player);
    forwardingPlayer.addAnalyticsListener(listener);
    AnalyticsListener forwardingListener = Iterables.getOnlyElement(player.analyticsListeners);
    AnalyticsListener.EventTime eventTime =
        new AnalyticsListener.EventTime(
            /* realtimeMs= */ 0,
            Timeline.EMPTY,
            /* windowIndex= */ 0,
            /* mediaPeriodId= */ null,
            /* eventPlaybackPositionMs= */ 0,
            Timeline.EMPTY,
            /* currentWindowIndex= */ 0,
            /* currentMediaPeriodId= */ null,
            /* currentPlaybackPositionMs= */ 0,
            /* totalBufferedDurationMs= */ 0);
    SparseArray<AnalyticsListener.EventTime> eventTimes = new SparseArray<>();
    eventTimes.append(AnalyticsListener.EVENT_TIMELINE_CHANGED, eventTime);
    eventTimes.append(AnalyticsListener.EVENT_IS_PLAYING_CHANGED, eventTime);

    forwardingListener.onEvents(
        player,
        new AnalyticsListener.Events(
            new FlagSet.Builder()
                .addAll(
                    AnalyticsListener.EVENT_TIMELINE_CHANGED,
                    AnalyticsListener.EVENT_IS_PLAYING_CHANGED)
                .build(),
            eventTimes));

    ArgumentCaptor<AnalyticsListener.Events> eventsArgumentCaptor =
        ArgumentCaptor.forClass(AnalyticsListener.Events.class);
    verify(listener).onEvents(same(forwardingPlayer), eventsArgumentCaptor.capture());
    AnalyticsListener.Events receivedEvents = eventsArgumentCaptor.getValue();
    assertThat(receivedEvents.size()).isEqualTo(2);
    assertThat(receivedEvents.get(0)).isEqualTo(AnalyticsListener.EVENT_TIMELINE_CHANGED);
    assertThat(receivedEvents.get(1)).isEqualTo(AnalyticsListener.EVENT_IS_PLAYING_CHANGED);
  }

  @Test
  public void forwardingExoPlayer_overridesAllExoPlayerMethods() throws Exception {
    // Check with reflection that ForwardingExoPlayer (or ForwardingPlayer for inherited Player
    // methods) overrides all ExoPlayer methods.
    for (Method method : getPublicOverridableMethods(ExoPlayer.class)) {
      if (method.getDeclaringClass().equals(ExoPlayer.class)) {
        assertThat(
                ForwardingExoPlayer.class
                    .getDeclaredMethod(method.getName(), method.getParameterTypes())
                    .getDeclaringClass())
            .isEqualTo(ForwardingExoPlayer.class);
      } else {
        assertThat(
                ForwardingExoPlayer.class
                    .getMethod(method.getName(), method.getParameterTypes())
                    .getDeclaringClass())
            .isAnyOf(ForwardingExoPlayer.class, ForwardingPlayer.class);
      }
    }
  }

  @Test
  public void forwardingExoPlayer_forwardsAllExoPlayerMethods() throws Exception {
    assertForwardingClassForwardsAllMethodsExcept(
        ExoPlayer.class,
        ForwardingExoPlayer::new,
        ImmutableSet.of(
            "addListener",
            "removeListener",
            "addAnalyticsListener",
            "removeAnalyticsListener",
            "getAnalyticsCollector"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void forwardingAnalyticsListener_overridesAllAnalyticsListenerMethods() throws Exception {
    // Check with reflection that ForwardingAnalyticsListener overrides all AnalyticsListener
    // methods.
    Class<? extends AnalyticsListener> forwardingAnalyticsListenerClass =
        (Class<? extends AnalyticsListener>)
            checkNotNull(getInnerClass(ForwardingExoPlayer.class, "ForwardingAnalyticsListener"));
    assertSubclassOverridesAllMethods(AnalyticsListener.class, forwardingAnalyticsListenerClass);
  }

  @Test
  public void forwardingAnalyticsListener_forwardsAllAnalyticsListenerMethods() throws Exception {
    assertForwardingClassForwardsAllMethodsExcept(
        AnalyticsListener.class,
        delegate -> {
          FakeExoPlayer fakeExoPlayer = new FakeExoPlayer();
          ForwardingExoPlayer forwardingPlayer = new ForwardingExoPlayer(fakeExoPlayer);
          forwardingPlayer.addAnalyticsListener(delegate);
          return Iterables.getOnlyElement(fakeExoPlayer.analyticsListeners);
        },
        ImmutableSet.of("onEvents"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void forwardingAnalyticsCollector_overridesAllAnalyticsCollectorMethods()
      throws Exception {
    // Check with reflection that ForwardingAnalyticsCollector overrides all AnalyticsCollector
    // methods.
    Class<? extends AnalyticsCollector> forwardingAnalyticsCollectorClass =
        (Class<? extends AnalyticsCollector>)
            checkNotNull(getInnerClass(ForwardingExoPlayer.class, "ForwardingAnalyticsCollector"));
    assertSubclassOverridesAllMethods(AnalyticsCollector.class, forwardingAnalyticsCollectorClass);
  }

  @Test
  public void forwardingAnalyticsCollector_forwardsAllAnalyticsCollectorMethods() throws Exception {
    assertForwardingClassForwardsAllMethodsExcept(
        AnalyticsCollector.class,
        delegate -> new ForwardingExoPlayer(new FakeExoPlayer(delegate)).getAnalyticsCollector(),
        ImmutableSet.of("addListener", "removeListener"));
  }

  private static final class FakeExoPlayer extends StubExoPlayer {

    private final Set<Player.Listener> listeners = new HashSet<>();
    private final Set<AnalyticsListener> analyticsListeners = new HashSet<>();
    private final AnalyticsCollector analyticsCollector;

    private FakeExoPlayer() {
      this(mock(AnalyticsCollector.class));
      doAnswer(
              invocation -> {
                analyticsListeners.add(invocation.getArgument(0));
                return null;
              })
          .when(analyticsCollector)
          .addListener(any());
      doAnswer(
              invocation -> {
                analyticsListeners.remove(invocation.getArgument(0));
                return null;
              })
          .when(analyticsCollector)
          .removeListener(any());
    }

    private FakeExoPlayer(AnalyticsCollector analyticsCollector) {
      this.analyticsCollector = analyticsCollector;
    }

    @Override
    public AnalyticsCollector getAnalyticsCollector() {
      return analyticsCollector;
    }

    @Override
    public void addListener(Player.Listener listener) {
      listeners.add(listener);
    }

    @Override
    public void removeListener(Player.Listener listener) {
      listeners.remove(listener);
    }

    @Override
    public void addAnalyticsListener(AnalyticsListener listener) {
      analyticsListeners.add(listener);
    }

    @Override
    public void removeAnalyticsListener(AnalyticsListener listener) {
      analyticsListeners.remove(listener);
    }
  }
}
