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

/**
 * A [PacketConsumer] that forwards processed [Packet]s to to a downstream [PacketConsumer].
 *
 * This interface is experimental and will be renamed or removed in a future release.
 *
 * @param I The type of the input packets.
 * @param O The type of the output packets.
 */
internal interface PacketFilter<I, O> : PacketConsumer<I> {

  /**
   * Sets the output [PacketConsumer] that will receive the processed packets.
   *
   * @param output The [PacketConsumer] for the output packets.
   */
  fun setOutput(output: PacketConsumer<O>)
}
