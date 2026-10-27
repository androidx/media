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
package androidx.media3.ui;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

/** Tests for {@link PlayerView}. */
@RunWith(AndroidJUnit4.class)
public final class PlayerViewTest {

  @Test
  public void getAdVideoView_defaultLayout_returnsContentFrameContainer() {
    PlayerView playerView = new PlayerView(ApplicationProvider.getApplicationContext());

    View adVideoView = playerView.getAdVideoView();

    assertThat(adVideoView).isNotNull();
    assertThat(adVideoView)
        .isSameInstanceAs(playerView.findViewById(R.id.exo_content_frame_container));
    assertThat(adVideoView.getLayoutParams()).isInstanceOf(ViewGroup.MarginLayoutParams.class);
  }

  @Test
  @Config(maxSdk = 35) // Robolectric.buildAttributeSet() is only supported up to API 35.
  @SuppressWarnings(
      "deprecation") // Robolectric.buildAttributeSet() has no direct test API replacement.
  public void getAdVideoView_withSurfaceTypeNone_returnsNull() {
    PlayerView playerView =
        new PlayerView(
            ApplicationProvider.getApplicationContext(),
            Robolectric.buildAttributeSet().addAttribute(R.attr.surface_type, "none").build());

    View adVideoView = playerView.getAdVideoView();

    assertThat(adVideoView).isNull();
  }

  @Test
  public void getAdVideoView_whenContentFrameNotChildOfContainer_returnsNull() {
    PlayerView playerView = new PlayerView(ApplicationProvider.getApplicationContext());
    ViewGroup contentFrameContainer =
        requireNonNull(playerView.findViewById(R.id.exo_content_frame_container));
    View contentFrame = requireNonNull(playerView.findViewById(R.id.exo_content_frame));
    contentFrameContainer.removeView(contentFrame);
    playerView.addView(contentFrame);

    View adVideoView = playerView.getAdVideoView();

    assertThat(adVideoView).isNull();
  }

  @Test
  public void getAdVideoView_whenMarginsApplied_centersAspectRatioContentFrameInCutout() {
    PlayerView playerView = new PlayerView(ApplicationProvider.getApplicationContext());
    View adVideoView = requireNonNull(playerView.getAdVideoView());
    AspectRatioFrameLayout contentFrame =
        requireNonNull(playerView.findViewById(R.id.exo_content_frame));
    contentFrame.setAspectRatio(16f / 9f);
    ViewGroup.MarginLayoutParams marginLayoutParams =
        (ViewGroup.MarginLayoutParams) adVideoView.getLayoutParams();

    marginLayoutParams.setMargins(
        /* left= */ 100, /* top= */ 50, /* right= */ 300, /* bottom= */ 150);
    adVideoView.setLayoutParams(marginLayoutParams);
    playerView.measure(
        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    playerView.layout(0, 0, 1000, 800);

    // Cutout container is 600x600 at (100, 50); 16:9 content frame is 600x337 centered inside it.
    assertThat(adVideoView.getLeft()).isEqualTo(100);
    assertThat(adVideoView.getTop()).isEqualTo(50);
    assertThat(adVideoView.getWidth()).isEqualTo(600);
    assertThat(adVideoView.getHeight()).isEqualTo(600);
    assertThat(contentFrame.getWidth()).isEqualTo(600);
    assertThat(contentFrame.getHeight()).isEqualTo(337);
    assertThat(contentFrame.getLeft()).isEqualTo(0);
    assertThat(contentFrame.getTop()).isEqualTo((600 - 337) / 2);
  }
}
