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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import androidx.annotation.Nullable;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import com.airbnb.lottie.ImageAssetDelegate;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieDrawable;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;

/**
 * A {@link CanvasOverlay} that renders a Lottie animation.
 *
 * <p>This overlay uses the Lottie library to draw an animation loaded from a raw resource. The
 * animation's size, position, speed, and looping behavior can be configured using the {@link
 * Builder}.
 */
@UnstableApi
public final class LottieOverlay extends CanvasOverlay {

  /** Provides assets for a Lottie animation and manages their lifecycle. */
  @UnstableApi
  public interface LottieProvider {
    /** Releases any resources held by this provider. */
    void release();

    /** Returns the {@link LottieComposition}. */
    LottieComposition getLottieComposition();

    /**
     * Returns an {@link ImageAssetDelegate} to handle image loading.
     *
     * <p>The default implementation returns {@code null}.
     */
    @Nullable
    default ImageAssetDelegate getImageAssetDelegate() {
      return null;
    }

    /**
     * Returns a map of pre-loaded fonts for the composition.
     *
     * <p>The default implementation returns an empty map.
     */
    default Map<String, Typeface> getFontMap() {
      return ImmutableMap.of();
    }
  }

  /** A builder for {@link LottieOverlay} instances. */
  public static final class Builder {
    private final LottieProvider lottieProvider;
    private float speed;
    @Nullable private StaticOverlaySettings overlaySettings;
    @Nullable private LottieDrawable lottieDrawable;

    public Builder(LottieProvider lottieProvider) {
      this.lottieProvider = lottieProvider;
      this.overlaySettings = null;
      this.speed = 1.0f;
      this.lottieDrawable = null;
    }

    /**
     * Sets a specific {@link LottieDrawable} instance for the overlay.
     *
     * <p>By default, an instance of {@link LottieDrawable} is created when {@link #build()} is
     * called.
     *
     * @param lottieDrawable The specific {@link LottieDrawable} to use.
     * @return This builder, for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setLottieDrawable(LottieDrawable lottieDrawable) {
      this.lottieDrawable = lottieDrawable;
      return this;
    }

    /**
     * Sets the {@link StaticOverlaySettings} to configure the overlay's visual properties.
     *
     * <p>By default, an instance of {@link StaticOverlaySettings.Builder#build()} is used. This can
     * be overwritten for the default settings like scale and position.
     *
     * @param overlaySettings The settings object defining the overlay's static properties.
     * @return This builder, for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setOverlaySettings(StaticOverlaySettings overlaySettings) {
      this.overlaySettings = overlaySettings;
      return this;
    }

    /**
     * Sets the animation playback speed multiplier.
     *
     * <p>By default, the speed will be set to {@code 1.0f} (frames will be synced based on the
     * lottie and playback framerate).
     *
     * @param speed The playback speed. Must be non-negative.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSpeed(float speed) {
      checkArgument(speed >= 0, "speed must be non-negative.");
      this.speed = speed;
      return this;
    }

    public LottieOverlay build() {
      return new LottieOverlay(
          lottieProvider,
          overlaySettings != null ? overlaySettings : new StaticOverlaySettings.Builder().build(),
          speed,
          lottieDrawable != null ? lottieDrawable : new LottieDrawable());
    }
  }

  private final LottieProvider lottieProvider;
  private final StaticOverlaySettings overlaySettings;
  private final LottieDrawable lottieDrawable;
  private final float speed;
  private float timeToProgressFactor;

  private LottieOverlay(
      LottieProvider lottieProvider,
      StaticOverlaySettings overlaySettings,
      float speed,
      LottieDrawable lottieDrawable) {
    super(/* useInputFrameSize= */ false);
    this.lottieProvider = lottieProvider;
    this.overlaySettings = overlaySettings;
    this.speed = speed;
    this.lottieDrawable = lottieDrawable;
    this.timeToProgressFactor = 0f;
  }

  @Override
  public void configure(Size videoSize) {
    super.configure(videoSize);
    LottieComposition composition = lottieProvider.getLottieComposition();

    lottieDrawable.setComposition(composition);

    ImageAssetDelegate imageAssetDelegate = lottieProvider.getImageAssetDelegate();
    if (imageAssetDelegate != null) {
      lottieDrawable.setImageAssetDelegate(imageAssetDelegate);
    }
    lottieDrawable.setFontMap(lottieProvider.getFontMap());

    lottieDrawable.setRepeatCount(LottieDrawable.INFINITE);
    lottieDrawable.invalidateSelf();
    tryToSetCanvasSize(composition);
  }

  @Override
  public synchronized void release() throws VideoFrameProcessingException {
    lottieProvider.release();
    lottieDrawable.clearComposition();
    timeToProgressFactor = 0f;
    // Release subclass resources before calling super to ensure a clean teardown order.
    super.release();
  }

  // Synchronized to ensure the Lottie drawable's state is not modified by another thread (e.g. in
  // release()) during a draw operation.
  @Override
  public synchronized void onDraw(Canvas canvas, long presentationTimeUs) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    float progress = (presentationTimeUs * timeToProgressFactor) % 1.0f;
    lottieDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
    lottieDrawable.setProgress(progress);
    lottieDrawable.setAlpha((int) (getOverlaySettings(presentationTimeUs).getAlphaScale() * 255));
    lottieDrawable.draw(canvas);
  }

  @Override
  public OverlaySettings getOverlaySettings(long presentationTimeUs) {
    return overlaySettings;
  }

  // Synchronized to ensure that updates to the overlay's configuration properties (canvas size,
  // overlay settings, time factor) are atomic.
  private synchronized void tryToSetCanvasSize(LottieComposition composition) {
    OverlaySettings overlaySettings = getOverlaySettings(0);
    float scaleX = overlaySettings.getScale().first;
    float scaleY = overlaySettings.getScale().second;
    int targetWidth = (int) (composition.getBounds().width() * scaleX);
    int targetHeight = (int) (composition.getBounds().height() * scaleY);

    int finalWidth = max(targetWidth, 1);
    int finalHeight = max(targetHeight, 1);
    setCanvasSize(finalWidth, finalHeight);

    long lottieDurationUs = (long) (composition.getDuration() * 1000);
    timeToProgressFactor = lottieDurationUs > 0 ? speed / lottieDurationUs : 0f;
  }
}
