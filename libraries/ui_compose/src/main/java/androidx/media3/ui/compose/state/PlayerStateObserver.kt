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
package androidx.media3.ui.compose.state

import androidx.media3.common.FlagSet
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param player The [Player].
 * @param firstEvent The first [Player.Event] to listen to.
 * @param otherEvents Additional [Player.Event] types to listen to.
 * @param initialStateUpdater The operation to trigger initially on creation and when [observe]
 *   starts.
 * @param stateUpdater The operation to trigger whenever one of the configured events happen. The
 *   provided [Player.Events] contains only the subset of configured events that occurred.
 */
@UnstableApi
class PlayerStateObserver(
  private val player: Player,
  private val firstEvent: @Player.Event Int,
  private vararg val otherEvents: @Player.Event Int,
  private val initialStateUpdater: (Player) -> Unit,
  private val stateUpdater: (Player, Player.Events) -> Unit,
) {

  /**
   * Creates a [PlayerStateObserver] that triggers [stateUpdater] initially (with all configured
   * [Player.Events]) and whenever one of the configured events happen.
   *
   * @param player The [Player].
   * @param firstEvent The first [Player.Event] to listen to.
   * @param otherEvents Additional [Player.Event] types to listen to.
   * @param stateUpdater The operation to trigger initially (with all configured [Player.Events])
   *   and whenever one of the configured events happen.
   */
  constructor(
    player: Player,
    firstEvent: @Player.Event Int,
    vararg otherEvents: @Player.Event Int,
    stateUpdater: (Player, Player.Events) -> Unit,
  ) : this(
    player,
    firstEvent,
    *otherEvents,
    initialStateUpdater = { p -> stateUpdater(p, createEvents(firstEvent, *otherEvents)) },
    stateUpdater = stateUpdater,
  )

  /**
   * Creates a [PlayerStateObserver] that triggers [stateUpdater] initially and whenever one of the
   * configured events happen.
   *
   * @param player The [Player].
   * @param firstEvent The first [Player.Event] to listen to.
   * @param otherEvents Additional [Player.Event] types to listen to.
   * @param stateUpdater The operation to trigger initially and whenever one of the configured
   *   events happen.
   */
  constructor(
    player: Player,
    firstEvent: @Player.Event Int,
    vararg otherEvents: @Player.Event Int,
    stateUpdater: (Player) -> Unit,
  ) : this(
    player,
    firstEvent,
    *otherEvents,
    initialStateUpdater = stateUpdater,
    stateUpdater = { p, _ -> stateUpdater(p) },
  )

  private val configuredEvents = createEvents(firstEvent, *otherEvents)

  init {
    initialStateUpdater.invoke(player)
  }

  /** Observes updates from the configured [Player.Events]. */
  suspend fun observe(): Nothing {
    initialStateUpdater.invoke(player)
    player.listenTo(firstEvent, *otherEvents) { events ->
      stateUpdater.invoke(player, filterEvents(events))
    }
  }

  private fun filterEvents(events: Player.Events): Player.Events {
    val flagSetBuilder = FlagSet.Builder()
    for (i in 0 until events.size()) {
      val event = events.get(i)
      if (configuredEvents.contains(event)) {
        flagSetBuilder.add(event)
      }
    }
    return Player.Events(flagSetBuilder.build())
  }

  companion object {
    private fun createEvents(
      firstEvent: @Player.Event Int,
      vararg otherEvents: @Player.Event Int,
    ): Player.Events = Player.Events(FlagSet.Builder().add(firstEvent).addAll(*otherEvents).build())

    init {
      MediaLibraryInfo.registerModule("media3.ui.compose")
    }
  }
}

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param firstEvent The first [Player.Event] to listen to.
 * @param otherEvents Additional [Player.Event] types to listen to.
 * @param stateUpdater The operation to trigger initially and whenever one of the configured events
 *   happen.
 */
@UnstableApi
fun Player.observeState(
  firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  stateUpdater: (Player) -> Unit,
) = PlayerStateObserver(player = this, firstEvent, *otherEvents, stateUpdater = stateUpdater)

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param firstEvent The first [Player.Event] to listen to.
 * @param otherEvents Additional [Player.Event] types to listen to.
 * @param stateUpdater The operation to trigger initially (with all configured [Player.Events]) and
 *   whenever one of the configured events happen.
 */
@UnstableApi
fun Player.observeState(
  firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  stateUpdater: (Player, Player.Events) -> Unit,
) = PlayerStateObserver(player = this, firstEvent, *otherEvents, stateUpdater = stateUpdater)

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param firstEvent The first [Player.Event] to listen to.
 * @param otherEvents Additional [Player.Event] types to listen to.
 * @param initialStateUpdater The operation to trigger initially on creation and when
 *   [PlayerStateObserver.observe] starts.
 * @param stateUpdater The operation to trigger whenever one of the configured events happen.
 */
@UnstableApi
fun Player.observeState(
  firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  initialStateUpdater: (Player) -> Unit,
  stateUpdater: (Player, Player.Events) -> Unit,
) =
  PlayerStateObserver(
    player = this,
    firstEvent,
    *otherEvents,
    initialStateUpdater = initialStateUpdater,
    stateUpdater = stateUpdater,
  )
