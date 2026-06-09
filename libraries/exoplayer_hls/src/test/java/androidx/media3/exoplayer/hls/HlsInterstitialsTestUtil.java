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
package androidx.media3.exoplayer.hls;

import androidx.annotation.Nullable;
import com.google.common.util.concurrent.Uninterruptibles;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Locale;

/** Utility methods for HLS interstitials testing. */
public final class HlsInterstitialsTestUtil {

  /**
   * Returns a JSON asset list containing the specified number of assets, optionally delaying the
   * production of the payload by a given duration.
   *
   * @param assetCount The number of assets to include in the JSON list.
   * @param delayMs The delay in milliseconds before returning the asset list, or 0 for no delay.
   * @return The JSON asset list as a byte array.
   */
  public static byte[] getJsonAssetList(int assetCount, int delayMs) {
    StringBuilder assetList = new StringBuilder("{\"ASSETS\": [");
    for (int i = 0; i < assetCount; i++) {
      assetList.append(getJsonAsset(/* uri= */ "http://" + i, /* durationSec= */ 10.123d + i));
      if (i < assetCount - 1) {
        assetList.append(",");
      }
    }
    if (delayMs > 0) {
      Uninterruptibles.sleepUninterruptibly(Duration.ofMillis(delayMs));
    }
    return assetList.append("]}\n").toString().getBytes(Charset.defaultCharset());
  }

  /**
   * Returns a JSON asset list containing the specified number of assets accompanied by skip control
   * information.
   *
   * @param assetCount The number of assets to include in the JSON list.
   * @param skipInfoOffsetSeconds The skip offset in seconds, or null if not set.
   * @param skipInfoDurationSeconds The skip duration in seconds, or null if not set.
   * @param skipInfoLabelId The skip label ID, or null if not set.
   * @return The JSON asset list with skip information as a byte array.
   */
  public static byte[] getJsonAssetListWithSkipInformation(
      int assetCount,
      @Nullable Float skipInfoOffsetSeconds,
      @Nullable Float skipInfoDurationSeconds,
      @Nullable String skipInfoLabelId) {
    StringBuilder assetList = new StringBuilder("{\"ASSETS\": [");
    for (int i = 0; i < assetCount; i++) {
      assetList.append(getJsonAsset(/* uri= */ "http://" + i, /* durationSec= */ 10.123d + i));
      if (i < assetCount - 1) {
        assetList.append(",");
      }
    }
    assetList.append("],\n");
    assetList.append("\"SKIP-CONTROL\": {");
    if (skipInfoOffsetSeconds != null) {
      assetList.append("   \"OFFSET\": ").append(skipInfoOffsetSeconds).append(",");
    }
    if (skipInfoDurationSeconds != null) {
      assetList.append("   \"DURATION\": ").append(skipInfoDurationSeconds);
      if (skipInfoLabelId != null) {
        assetList.append(",");
      }
    }
    if (skipInfoLabelId != null) {
      assetList.append("   \"LABEL-ID\": \"").append(skipInfoLabelId).append("\"");
    }
    assetList.append("}"); // end of SKIP_CONTROL
    assetList.append("}"); // end of document
    return assetList.toString().getBytes(Charset.defaultCharset());
  }

  /**
   * Returns a JSON string representation of a single asset with the specified URI and duration.
   *
   * @param uri The URI of the asset.
   * @param durationSec The duration of the asset in seconds.
   * @return The representation of the asset as a JSON string.
   */
  public static String getJsonAsset(String uri, double durationSec) {
    return String.format(Locale.US, "{\"URI\": \"%s\", \"DURATION\": %f}", uri, durationSec);
  }

  private HlsInterstitialsTestUtil() {}
}
