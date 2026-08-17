/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")

package androidx.media3.docsamples.inspector

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.io.IOException
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
object ExtractSamplesKt {

  // [START extract_samples]
  fun extractSamples(context: Context, mediaPath: String) {
    val extractor = MediaExtractorCompat(context)
    try {
      // 1. Setup the extractor
      extractor.setDataSource(mediaPath)

      // Find and select available tracks
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        extractor.selectTrack(i)
      }

      // 2. Process samples
      val buffer = ByteBuffer.allocate(10 * 1024 * 1024)
      while (true) {
        // Read an encoded sample into the buffer.
        val bytesRead = extractor.readSampleData(buffer, 0)
        if (bytesRead < 0) break

        // Access sample metadata
        val trackIndex = extractor.sampleTrackIndex
        val presentationTimeUs = extractor.sampleTime
        val sampleSize = extractor.sampleSize

        extractor.advance()
      }
    } catch (e: IOException) {
      handleFailure(e)
    } finally {
      // 3. Release the extractor
      extractor.release()
    }
  }

  // [END extract_samples]

  private fun handleFailure(e: Exception) {}
}
