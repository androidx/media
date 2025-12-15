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
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.guava.future

@ExperimentalApi
object PacketConsumerUtil {

  /**
   * Releases the [PacketConsumer] from Java by wrapping the suspend call in a [ListenableFuture].
   *
   * @param consumer The [PacketConsumer] to release.
   * @param executor The [ExecutorService] to launch the coroutine on.
   * @return A [ListenableFuture] that completes when the consumer has been released.
   */
  @JvmStatic
  fun <T> release(consumer: PacketConsumer<T>, executor: ExecutorService): ListenableFuture<Void?> =
    CoroutineScope(executor.asCoroutineDispatcher()).future {
      consumer.release()
      null
    }
}
