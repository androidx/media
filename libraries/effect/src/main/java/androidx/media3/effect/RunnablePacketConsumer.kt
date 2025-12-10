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

package androidx.media3.effect

import androidx.media3.common.util.ExperimentalApi

/**
 * Defines a [PacketConsumer] that operates with an active processing loop.
 *
 * This interface is experimental and will be renamed or removed in a future release.
 *
 * The owner of this component is responsible for managing its lifecycle, including launching [run]
 * and handling any exceptions that may propagate from it.
 */
@ExperimentalApi
interface RunnablePacketConsumer<T> : PacketConsumer<T> {

  /**
   * Runs the main processing loop of the consumer.
   *
   * This is a suspending, run-to-completion function that contains the core logic for receiving and
   * processing packets.
   *
   * This function is expected to run until the owner has no more need of the consumer, at which
   * point the consumer must be [released][release] to clean up its resources and terminate.
   *
   * @throws IllegalStateException If called when the consumer is already running or if called after
   *   [release].
   */
  suspend fun run()
}
