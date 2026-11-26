/*
 * Copyright (C) 2020 The Android Open Source Project
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

package androidx.media3.test.exoplayer.playback.gts;

import static androidx.media3.common.C.WIDEVINE_UUID;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

/** Utility methods for GTS tests. */
public final class GtsTestUtil {

  private static final String WIDEVINE_SECURITY_LEVEL_1 = "L1";
  private static final String SECURITY_LEVEL_PROPERTY = "securityLevel";

  private GtsTestUtil() {}

  /** Returns true if the device doesn't support Widevine and this is permitted. */
  public static boolean shouldSkipWidevineTest(Context context) {
    if (isGmsInstalled(context)) {
      // GMS devices are required to support Widevine.
      return false;
    }
    // For non-GMS devices Widevine is optional.
    return !MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID);
  }

  /** Returns whether Widevine L1 and a secure decoder for {@code mimeType} are available. */
  @SuppressWarnings("ResourceType")
  public static boolean isL1WidevineAvailable(String mimeType) {
    MediaDrm mediaDrm = null;
    try {
      mediaDrm = new MediaDrm(WIDEVINE_UUID);
      if (MediaCodecUtil.getDecoderInfo(mimeType, /* secure= */ true, /* tunneling= */ false)
          == null) {
        return false;
      }
      String securityProperty = mediaDrm.getPropertyString(SECURITY_LEVEL_PROPERTY);
      return WIDEVINE_SECURITY_LEVEL_1.equals(securityProperty);
    } catch (UnsupportedSchemeException | MediaCodecUtil.DecoderQueryException e) {
      throw new IllegalStateException(e);
    } finally {
      if (mediaDrm != null) {
        mediaDrm.close();
      }
    }
  }

  private static boolean isGmsInstalled(Context context) {
    try {
      context
          .getPackageManager()
          .getPackageInfo("com.google.android.gms", PackageManager.GET_SIGNATURES);
    } catch (PackageManager.NameNotFoundException e) {
      return false;
    }
    return true;
  }
}
