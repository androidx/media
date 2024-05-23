/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.transformer.mh.performance;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextOverlay;
import com.google.common.collect.ImmutableList;

/** Utilities for playback tests. */
/* package */ final class PlaybackTestUtil {

  private PlaybackTestUtil() {}

  /** Creates an {@link OverlayEffect} that draws the timestamp onto frames. */
  public static OverlayEffect createTimestampOverlay() {
    return new OverlayEffect(
        ImmutableList.of(
            new TextOverlay() {
              @Override
              public SpannableString getText(long presentationTimeUs) {
                SpannableString text = new SpannableString(String.valueOf(presentationTimeUs));
                text.setSpan(
                    new ForegroundColorSpan(Color.WHITE),
                    /* start= */ 0,
                    text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(
                    new AbsoluteSizeSpan(/* size= */ 96),
                    /* start= */ 0,
                    text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(
                    new TypefaceSpan(/* family= */ "sans-serif"),
                    /* start= */ 0,
                    text.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return text;
              }
            }));
  }
}
