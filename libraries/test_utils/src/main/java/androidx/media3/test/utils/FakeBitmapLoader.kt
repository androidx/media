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

package androidx.media3.test.utils

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A fake [BitmapLoader] that returns [result] for every [decodeBitmap] and [loadBitmap] call, and
 * counts the calls.
 *
 * @param result The result returned for every call.
 */
@UnstableApi
class FakeBitmapLoader(private val result: ListenableFuture<Bitmap>) : BitmapLoader {

  /** Creates an instance that returns [bitmap] for every call. */
  constructor(bitmap: Bitmap) : this(Futures.immediateFuture(bitmap))

  /** Creates an instance that fails with [throwable] for every call. */
  constructor(throwable: Throwable) : this(Futures.immediateFailedFuture(throwable))

  /** The number of times [decodeBitmap] has been called. */
  var decodeBitmapCallCount = 0
    private set

  /** The number of times [loadBitmap] has been called. */
  var loadBitmapCallCount = 0
    private set

  override fun supportsMimeType(mimeType: String): Boolean = true

  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
    decodeBitmapCallCount++
    return result
  }

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
    loadBitmapCallCount++
    return result
  }
}

/**
 * A [BitmapLoader] that only implements [loadBitmap], supporting all MIME types and throwing
 * [UnsupportedOperationException] from [decodeBitmap].
 */
@UnstableApi
fun interface UriOnlyBitmapLoader : BitmapLoader {
  override fun supportsMimeType(mimeType: String): Boolean = true

  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
    throw UnsupportedOperationException()
}

/**
 * A [BitmapLoader] that only implements [decodeBitmap], supporting all MIME types and throwing
 * [UnsupportedOperationException] from [loadBitmap].
 */
@UnstableApi
fun interface BytesOnlyBitmapLoader : BitmapLoader {
  override fun supportsMimeType(mimeType: String): Boolean = true

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
    throw UnsupportedOperationException()
}
