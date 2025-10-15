/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.effect.LottieOverlay.LottieProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.airbnb.lottie.ImageAssetDelegate;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link LottieOverlay}. */
@RunWith(AndroidJUnit4.class)
public final class LottieOverlayTest {

  private static final float TOLERANCE = 0.01f;
  private TestLottieProvider testLottieProvider;
  @Nullable private LottieOverlay lottieOverlay;

  @Before
  public void setUp() {
    testLottieProvider = new TestLottieProvider();
  }

  @After
  public void tearDown() throws VideoFrameProcessingException {
    if (lottieOverlay != null) {
      lottieOverlay.release();
    }
  }

  @Test
  public void getBitmap_afterConfigure_returnsBitmap() {
    lottieOverlay = new LottieOverlay.Builder(testLottieProvider).build();
    lottieOverlay.configure(new Size(1080, 1080));

    Bitmap bitmap = lottieOverlay.getBitmap(/* presentationTimeUs= */ 0);

    assertThat(bitmap).isNotNull();
    assertThat(bitmap.isRecycled()).isFalse();
  }

  @Test
  public void getBitmap_withCustomScale_returnsCorrectlySizedBitmap() {
    float scaleX = 0.5f;
    float scaleY = 0.25f;
    StaticOverlaySettings overlaySettings =
        new StaticOverlaySettings.Builder().setScale(scaleX, scaleY).build();
    lottieOverlay =
        new LottieOverlay.Builder(testLottieProvider).setOverlaySettings(overlaySettings).build();
    lottieOverlay.configure(new Size(1920, 1080)); // Video size doesn't affect canvas size.

    LottieComposition composition = testLottieProvider.getLottieComposition();
    int baseWidth = composition.getBounds().width();
    int baseHeight = composition.getBounds().height();
    int expectedWidth = (int) (baseWidth * scaleX);
    int expectedHeight = (int) (baseHeight * scaleY);
    Bitmap bitmap = lottieOverlay.getBitmap(/* presentationTimeUs= */ 0);

    assertThat(bitmap.getWidth()).isEqualTo(expectedWidth);
    assertThat(bitmap.getHeight()).isEqualTo(expectedHeight);
  }

  @Test
  public void configure_withProvider_setsAssetsOnDrawable() {
    TestLottieProvider provider = new TestLottieProvider();
    TestLottieDrawable testLottieDrawable = new TestLottieDrawable();
    lottieOverlay =
        new LottieOverlay.Builder(provider).setLottieDrawable(testLottieDrawable).build();

    lottieOverlay.configure(new Size(1080, 1080));

    assertThat(testLottieDrawable.imageAssetDelegate).isSameInstanceAs(provider.assetDelegate);
    assertThat(testLottieDrawable.fontMap).isSameInstanceAs(provider.fontMap);
    assertThat(testLottieDrawable.composition).isSameInstanceAs(provider.composition);
  }

  @Test
  public void release_withProvider_releasesProvider() throws Exception {
    lottieOverlay = new LottieOverlay.Builder(testLottieProvider).build();
    lottieOverlay.configure(new Size(1080, 1080));

    lottieOverlay.release();

    assertThat(testLottieProvider.releaseCalled).isTrue();
  }

  @Test
  public void onDraw_withDifferentPresentationTimes_setsCorrectProgressOnDrawable() {
    TestLottieDrawable testLottieDrawable = new TestLottieDrawable();
    lottieOverlay =
        new LottieOverlay.Builder(testLottieProvider).setLottieDrawable(testLottieDrawable).build();
    lottieOverlay.configure(new Size(1080, 1080));

    LottieComposition composition = testLottieProvider.getLottieComposition();
    long durationUs = (long) (composition.getDuration() * 1000);
    long presentationTimeAtMidpoint = durationUs / 2;
    lottieOverlay.getBitmap(/* presentationTimeUs= */ 0);
    assertThat(testLottieDrawable.progress).isEqualTo(0f);
    lottieOverlay.getBitmap(presentationTimeAtMidpoint);

    // Tolerance needed as progress is calculated by taking the lottie frames allowing for a slight
    // deviation from the exact float target value due to rounding
    assertThat(testLottieDrawable.progress).isWithin(TOLERANCE).of(0.5f);
  }

  @Test
  public void getBitmap_withCustomSpeed_advancesAnimationDifferently() throws Exception {
    TestLottieDrawable drawableSpeed1x = new TestLottieDrawable();
    TestLottieDrawable drawableSpeed2x = new TestLottieDrawable();
    LottieOverlay lottieOverlaySpeed2x = null;
    try {
      lottieOverlay =
          new LottieOverlay.Builder(testLottieProvider).setLottieDrawable(drawableSpeed1x).build();
      lottieOverlaySpeed2x =
          new LottieOverlay.Builder(new TestLottieProvider())
              .setSpeed(2.0f)
              .setLottieDrawable(drawableSpeed2x)
              .build();
      lottieOverlay.configure(new Size(1080, 1080));
      lottieOverlaySpeed2x.configure(new Size(1080, 1080));
      lottieOverlay.getBitmap(/* presentationTimeUs= */ 500_000);
      lottieOverlaySpeed2x.getBitmap(/* presentationTimeUs= */ 500_000);

      // Tolerance needed as progress is calculated by taking the lottie frames allowing for a
      // slight deviation from the exact float target value due to rounding
      assertThat(drawableSpeed1x.progress).isWithin(TOLERANCE).of(0.25f);
      assertThat(drawableSpeed2x.progress).isWithin(TOLERANCE).of(0.5f);
    } finally {
      if (lottieOverlaySpeed2x != null) {
        lottieOverlaySpeed2x.release();
      }
    }
  }

  @Test
  public void getBitmap_withAlpha_setsDrawableAlpha() {
    TestLottieDrawable testLottieDrawable = new TestLottieDrawable();
    // Setting the alpha to 0.5f which translates to 127 in the 0 to 255 scale.
    StaticOverlaySettings overlaySettings =
        new StaticOverlaySettings.Builder().setAlphaScale(0.5f).build();
    lottieOverlay =
        new LottieOverlay.Builder(testLottieProvider)
            .setOverlaySettings(overlaySettings)
            .setLottieDrawable(testLottieDrawable)
            .build();
    lottieOverlay.configure(new Size(1080, 1080));

    lottieOverlay.getBitmap(/* presentationTimeUs= */ 0);

    // Checking if it is 127, as 127 is the result of the 50% of the alpha value from 0 to 255 in
    // the overlay set above given the two different scales.
    assertThat(testLottieDrawable.alpha).isEqualTo(127);
  }

  @Test
  public void release_clearsCompositionFromDrawable() throws VideoFrameProcessingException {
    TestLottieDrawable testLottieDrawable = new TestLottieDrawable();
    lottieOverlay =
        new LottieOverlay.Builder(testLottieProvider).setLottieDrawable(testLottieDrawable).build();
    lottieOverlay.configure(new Size(1080, 1080));

    lottieOverlay.release();

    assertThat(testLottieDrawable.compositionCleared).isTrue();
  }

  /** A simple, mock implementation of LottieProvider for testing. */
  private static class TestLottieProvider implements LottieProvider {
    private final LottieComposition composition;
    private final ImageAssetDelegate assetDelegate;
    private final Map<String, Typeface> fontMap;
    private boolean releaseCalled = false;

    private TestLottieProvider() {
      this.composition = createTestComposition();
      this.assetDelegate = asset -> null;
      this.fontMap = new HashMap<>();
    }

    @Override
    public void release() {
      releaseCalled = true;
    }

    @Override
    public LottieComposition getLottieComposition() {
      return composition;
    }

    @Override
    public ImageAssetDelegate getImageAssetDelegate() {
      return assetDelegate;
    }

    @Override
    public Map<String, Typeface> getFontMap() {
      return fontMap;
    }

    private static LottieComposition createTestComposition() {
      // A minimal JSON string for a 1080x1080 composition with one static red square layer.
      String minimalJson =
          "{"
              + "\"v\": \"5.10.0\","
              + "\"fr\": 30,"
              + "\"ip\": 0,"
              + "\"op\": 60,"
              + "\"w\": 1080,"
              + "\"h\": 1080,"
              + "\"layers\": ["
              + "  {"
              + "    \"ty\": 4,"
              + "    \"nm\": \"Red Square\","
              + "    \"ks\": {"
              + "      \"p\": {\"k\": [540, 540, 0]},"
              + "      \"a\": {\"k\": [0, 0, 0]},"
              + "      \"s\": {\"k\": [100, 100, 100]}"
              + "    },"
              + "    \"shapes\": ["
              + "      {"
              + "        \"ty\": \"gr\","
              + "        \"it\": ["
              + "          {"
              + "            \"ty\": \"rc\","
              + "            \"s\": {\"k\": [200, 200]},"
              + "            \"p\": {\"k\": [0, 0]},"
              + "            \"r\": {\"k\": 0}"
              + "          },"
              + "          {"
              + "            \"ty\": \"fl\","
              + "            \"c\": {\"k\": [1, 0, 0, 1]}"
              + "          }"
              + "        ]"
              + "      }"
              + "    ],"
              + "    \"ip\": 0,"
              + "    \"op\": 60"
              + "  }"
              + "]"
              + "}";

      LottieResult<LottieComposition> result =
          LottieCompositionFactory.fromJsonStringSync(minimalJson, null);

      assertThat(result.getValue()).isNotNull();
      return result.getValue();
    }
  }

  /** A mock {@link LottieDrawable} that records method calls for testing. */
  private static class TestLottieDrawable extends LottieDrawable {
    float progress;
    int alpha;
    boolean compositionCleared = false;
    @Nullable ImageAssetDelegate imageAssetDelegate = null;
    @Nullable Map<String, Typeface> fontMap = null;
    @Nullable LottieComposition composition = null;

    @Override
    public void setProgress(float progress) {
      this.progress = progress;
      super.setProgress(progress);
    }

    @Override
    public void setAlpha(int alpha) {
      this.alpha = alpha;
      super.setAlpha(alpha);
    }

    @Override
    public void clearComposition() {
      this.compositionCleared = true;
      super.clearComposition();
    }

    @Override
    public void setImageAssetDelegate(ImageAssetDelegate assetDelegate) {
      this.imageAssetDelegate = assetDelegate;
      super.setImageAssetDelegate(assetDelegate);
    }

    @Override
    public void setFontMap(@Nullable Map<String, Typeface> fontMap) {
      this.fontMap = fontMap;
      super.setFontMap(fontMap);
    }

    @Override
    public boolean setComposition(LottieComposition composition) {
      this.composition = composition;
      return super.setComposition(composition);
    }
  }
}
