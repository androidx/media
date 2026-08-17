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

package androidx.media3.docsamples.inspector;

import android.content.Context;
import android.media.MediaFormat;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.inspector.MediaExtractorCompat;
import java.io.IOException;
import java.nio.ByteBuffer;

@SuppressWarnings({"CheckReturnValue", "unused"})
@OptIn(markerClass = UnstableApi.class)
final class ExtractSamples {

  // [START extract_samples]
  public void extractSamples(Context context, String mediaPath) {
    MediaExtractorCompat extractor = new MediaExtractorCompat(context);
    try {
      // 1. Setup the extractor
      extractor.setDataSource(mediaPath);

      // Find and select available tracks
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        extractor.selectTrack(i);
      }

      // 2. Process samples
      ByteBuffer buffer = ByteBuffer.allocate(10 * 1024 * 1024);
      while (true) {
        // Read an encoded sample into the buffer.
        int bytesRead = extractor.readSampleData(buffer, 0);
        if (bytesRead < 0) {
          break;
        }

        // Access sample metadata
        int trackIndex = extractor.getSampleTrackIndex();
        long presentationTimeUs = extractor.getSampleTime();
        long sampleSize = extractor.getSampleSize();

        extractor.advance();
      }
    } catch (IOException e) {
      handleFailure(e);
    } finally {
      // 3. Release the extractor
      extractor.release();
    }
  }

  // [END extract_samples]

  private void handleFailure(Exception e) {}
}
