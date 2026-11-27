/*
 * Copyright (C) 2025 The Android Open Source Project
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

package androidx.media3.exoplayer.ima;

import android.net.Uri;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.VideoOrientation;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Builder for URI for IMA client side ad insertion streams. */
@UnstableApi
public final class ImaAdTagUriBuilder {
  /* package */ static final String IMA_AUTHORITY = "ima.google.com";
  private static final String URI = "uri";
  private static final String VIDEO_ORIENTATION_KEY = "videoOrientation";

  /** Types of video orientations. */
  @IntDef(
      value = {
        IMA_ORIENTATION_UNSET,
        IMA_ORIENTATION_LANDSCAPE,
        IMA_ORIENTATION_PORTRAIT,
        IMA_ORIENTATION_SQUARE
      })
  @Retention(RetentionPolicy.SOURCE)
  // Types of orientations.
  public @interface ImaOrientationType {}

  // Unset orientation.
  public static final int IMA_ORIENTATION_UNSET = 0;
  // Landscape orientation.
  public static final int IMA_ORIENTATION_LANDSCAPE = 1;
  // Portrait orientation.
  public static final int IMA_ORIENTATION_PORTRAIT = 2;
  // Square orientation.
  public static final int IMA_ORIENTATION_SQUARE = 3;

  private final Uri uri;

  @ImaOrientationType private int preferredLinearOrientation = IMA_ORIENTATION_UNSET;

  /**
   * Creates a new instance.
   *
   * @param uri The URI.
   */
  public ImaAdTagUriBuilder(Uri uri) {
    this.uri = uri;
  }

  /**
   * Sets the preferred linear orientation to be used. The publisher can specify an orientation that
   * ads should be displayed in.
   *
   * @param preferredLinearOrientation The preferred linear orientation.
   * @return This instance, for convenience.
   */
  @CanIgnoreReturnValue
  public ImaAdTagUriBuilder setPreferredLinearOrientation(
      @ImaOrientationType int preferredLinearOrientation) {
    this.preferredLinearOrientation = preferredLinearOrientation;
    return this;
  }

  /**
   * Builds a URI with the builder's current values. This URI can be used in the call to
   * AdConfiguration.Builder.setAdTagUri().
   *
   * @return The build {@link Uri}.
   * @throws IllegalStateException If the builder has missing or invalid inputs.
   */
  public Uri build() {
    Uri.Builder builder = new Uri.Builder().scheme(C.CSAI_SCHEME).authority(IMA_AUTHORITY);
    builder.appendQueryParameter(URI, uri.toString());
    builder.appendQueryParameter(VIDEO_ORIENTATION_KEY, String.valueOf(preferredLinearOrientation));
    return builder.build();
  }

  /* package */ static AdsRequest createAdsRequest(ImaUtil.ImaFactory imaFactory, Uri uri) {
    AdsRequest request = imaFactory.createAdsRequest();
    String adTagUrl = uri.getQueryParameter(URI);
    if (adTagUrl != null) {
      request.setAdTagUrl(adTagUrl);
    }
    String videoOrientation = uri.getQueryParameter(VIDEO_ORIENTATION_KEY);
    if (videoOrientation != null) {
      request.setPreferredLinearOrientation(fromImaOrientation(Integer.parseInt(videoOrientation)));
    }
    return request;
  }

  /**
   * Conversion from the {@link ImaAdTagUriBuilder.ImaOrientationType} enum to the {@link
   * VideoOrientation} enum used by the IMA SDK. Keeping this package private as this should only be
   * used within the exoplayer/ima library.
   */
  private static VideoOrientation fromImaOrientation(@ImaOrientationType int imaOrientation) {
    switch (imaOrientation) {
      case IMA_ORIENTATION_LANDSCAPE:
        return VideoOrientation.LANDSCAPE;
      case IMA_ORIENTATION_PORTRAIT:
        return VideoOrientation.PORTRAIT;
      case IMA_ORIENTATION_SQUARE:
        return VideoOrientation.SQUARE;
      default:
        return VideoOrientation.UNSET;
    }
  }
}
