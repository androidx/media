package androidx.media3.effect;

/*
 * Copyright 2024 The Android Open Source Project
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
import android.graphics.Gainmap;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;

/** Utilities for Gainmaps. */
@UnstableApi
/* package */ class GainmapUtil {
  private GainmapUtil() {}

  /** Checks whether the contents and fields relevant to effects processing are equal. */
  @RequiresApi(34)
  public static boolean equals(Gainmap g1, Gainmap g2) {
    return g1.getGamma() == g2.getGamma()
        && g1.getRatioMax() == g2.getRatioMax()
        && g1.getRatioMin() == g2.getRatioMin()
        && g1.getEpsilonHdr() == g2.getEpsilonHdr()
        && g1.getEpsilonSdr() == g2.getEpsilonSdr()
        && g1.getDisplayRatioForFullHdr() == g2.getDisplayRatioForFullHdr()
        && g1.getMinDisplayRatioForHdrTransition() == g2.getMinDisplayRatioForHdrTransition()
        && g1.getGainmapContents() == g2.getGainmapContents()
        && g1.getGainmapContents().getGenerationId() == g2.getGainmapContents().getGenerationId();
  }
}
