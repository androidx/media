/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common

import androidx.media3.test.utils.StubExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerPoolTest {

  class TrackingPlayer : StubExoPlayer() {
    var releaseCount = 0

    private var _playWhenReady = false

    override fun release() {
      releaseCount++
    }

    override fun stop() {
      // Do nothing
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
      // Do nothing
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
      _playWhenReady = playWhenReady
    }

    override fun getPlayWhenReady(): Boolean = _playWhenReady
  }

  @Test
  fun constructor_withNonPositiveCapacity_throwsException() {
    assertThrows(IllegalArgumentException::class.java) {
      PlayerPool(poolCapacity = 0) { TrackingPlayer() }
    }

    assertThrows(IllegalArgumentException::class.java) {
      PlayerPool(poolCapacity = -1) { TrackingPlayer() }
    }
  }

  @Test
  fun acquire_createsNewPlayersUntilCapacityIsReached() = runTest {
    var factoryCalls = 0
    val pool =
      PlayerPool(poolCapacity = 3) {
        factoryCalls++
        TrackingPlayer()
      }

    val player1 = pool.acquire()
    val player2 = pool.acquire()
    val player3 = pool.acquire()

    assertThat(factoryCalls).isEqualTo(3)
    assertThat(player1).isNotSameInstanceAs(player2)
    assertThat(player2).isNotSameInstanceAs(player3)
  }

  @Test
  fun acquire_reusesReturnedPlayerWithoutCreatingNewOne() = runTest {
    var factoryCalls = 0
    val pool =
      PlayerPool(poolCapacity = 2) {
        factoryCalls++
        TrackingPlayer()
      }
    val player1 = pool.acquire()
    pool.yield(player1)
    val player2 = pool.acquire()

    assertThat(factoryCalls).isEqualTo(1)
    assertThat(player2).isSameInstanceAs(player1)
  }

  @Test
  fun acquire_suspendsWhenPoolIsExhausted_andResumesWhenPlayerReturned() = runTest {
    var factoryCalls = 0
    val pool =
      PlayerPool(poolCapacity = 1) {
        factoryCalls++
        TrackingPlayer()
      }

    val player1 = pool.acquire()
    assertThat(factoryCalls).isEqualTo(1)

    var player2: TrackingPlayer? = null
    launch { player2 = pool.acquire() }

    advanceUntilIdle()
    assertThat(player2).isNull()

    pool.yield(player1)
    advanceUntilIdle()

    assertThat(player2).isSameInstanceAs(player1)
    assertThat(factoryCalls).isEqualTo(1)
  }

  @Test
  fun release_releasesAllPlayersAndFailsSubsequentAcquisitions() = runTest {
    val pool = PlayerPool(poolCapacity = 2) { TrackingPlayer() }
    val player1 = pool.acquire()
    val player2 = pool.acquire()

    pool.release()

    assertThat(player1.releaseCount).isEqualTo(1)
    assertThat(player2.releaseCount).isEqualTo(1)

    val result = runCatching { pool.acquire() }
    assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(result.exceptionOrNull()).hasMessageThat().contains("PlayerPool is already released")
  }

  @Test
  fun executeForAll_appliesActionToAllPlayers() = runTest {
    var factoryCalls = 0
    val pool =
      PlayerPool(poolCapacity = 2) {
        factoryCalls++
        TrackingPlayer()
      }

    val player1 = pool.acquire()
    val player2 = pool.acquire()
    pool.yield(player2)

    pool.executeForAll { playWhenReady = true }

    assertThat(player1.playWhenReady).isTrue()
    // player2 is in the pool but executeForAll applies to all created players
    assertThat(player2.playWhenReady).isTrue()
  }

  @Test
  fun executeForAcquired_appliesActionToActivePlayersOnly() = runTest {
    var factoryCalls = 0
    val pool =
      PlayerPool(poolCapacity = 2) {
        factoryCalls++
        TrackingPlayer()
      }

    val player1 = pool.acquire()
    val player2 = pool.acquire()
    pool.yield(player2)

    pool.executeForAcquired { playWhenReady = true }

    assertThat(player1.playWhenReady).isTrue()
    // player2 is in the pool and should not be affected by executeForAcquired
    assertThat(player2.playWhenReady).isFalse()
  }
}
