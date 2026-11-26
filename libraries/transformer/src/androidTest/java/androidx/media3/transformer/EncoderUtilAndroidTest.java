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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.media.MediaCodecInfo;
import android.util.Range;
import android.util.Size;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link EncoderUtil} against device {@link MediaCodecInfo} encoders. */
@RunWith(AndroidJUnit4.class)
public class EncoderUtilAndroidTest {

  @Test
  public void getSupportedResolution_unalignedWidthAndUnsupportedHeight_doesNotThrow() {
    boolean testedUnalignedWidth = false;

    for (String mimeType : EncoderUtil.getSupportedMimeTypes()) {
      if (!MimeTypes.isVideo(mimeType)) {
        continue;
      }
      ImmutableList<MediaCodecInfo> encoders = EncoderUtil.getSupportedEncoders(mimeType);
      for (int i = 0; i < encoders.size(); i++) {
        MediaCodecInfo encoderInfo = encoders.get(i);
        // EncoderUtil indexes lowercased MIME types, whereas getCapabilitiesForType() is
        // case-sensitive.
        if (!Arrays.asList(encoderInfo.getSupportedTypes()).contains(mimeType)) {
          continue;
        }
        MediaCodecInfo.VideoCapabilities videoCapabilities =
            checkNotNull(encoderInfo.getCapabilitiesForType(mimeType).getVideoCapabilities());
        int widthAlignment = videoCapabilities.getWidthAlignment();
        if (widthAlignment <= 1) {
          continue;
        }
        Range<Integer> supportedWidths = videoCapabilities.getSupportedWidths();
        int unalignedWidth = findUnalignedWidthInRange(supportedWidths, widthAlignment);
        if (unalignedWidth == C.LENGTH_UNSET) {
          continue;
        }
        testedUnalignedWidth = true;
        int unsupportedTallHeight = videoCapabilities.getSupportedHeights().getUpper() * 5;

        @Nullable Size tallResult =
            EncoderUtil.getSupportedResolution(
                encoderInfo, mimeType, unalignedWidth, unsupportedTallHeight);
        @Nullable Size shortResult =
            EncoderUtil.getSupportedResolution(
                encoderInfo, mimeType, unalignedWidth, /* height= */ 1);

        assertResultIsSupportedIfNonNull(
            encoderInfo, mimeType, unalignedWidth, unsupportedTallHeight, tallResult);
        assertResultIsSupportedIfNonNull(
            encoderInfo, mimeType, unalignedWidth, /* height= */ 1, shortResult);
      }
    }

    assumeTrue(
        "No video encoder with widthAlignment > 1 and unaligned width in range",
        testedUnalignedWidth);
  }

  private static void assertResultIsSupportedIfNonNull(
      MediaCodecInfo encoderInfo, String mimeType, int width, int height, @Nullable Size result) {
    if (result != null) {
      assertWithMessage(
              "encoder=%s, mimeType=%s, input=%sx%s, result=%s",
              encoderInfo.getName(), mimeType, width, height, result)
          .that(
              EncoderUtil.isSizeSupported(
                  encoderInfo, mimeType, result.getWidth(), result.getHeight()))
          .isTrue();
    }
  }

  private static int findUnalignedWidthInRange(Range<Integer> supportedWidths, int widthAlignment) {
    int candidate = supportedWidths.getLower();
    if (candidate % widthAlignment == 0) {
      candidate++;
    }
    return supportedWidths.contains(candidate) ? candidate : C.LENGTH_UNSET;
  }
}
