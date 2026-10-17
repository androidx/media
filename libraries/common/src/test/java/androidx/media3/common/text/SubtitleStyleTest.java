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
package androidx.media3.common.text;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.accessibility.CaptioningManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SubtitleStyle}. */
@RunWith(AndroidJUnit4.class)
public final class SubtitleStyleTest {

  // TODO(b/572878706): Use the ShadowCaptioningManager setters once Robolectric releases them.
  // Hidden Settings.Secure keys that CaptioningManager reads the user's caption preferences from.
  private static final String CAPTIONING_PRESET = "accessibility_captioning_preset";
  private static final String CAPTIONING_FOREGROUND_COLOR =
      "accessibility_captioning_foreground_color";
  private static final String CAPTIONING_BACKGROUND_COLOR =
      "accessibility_captioning_background_color";
  private static final String CAPTIONING_WINDOW_COLOR = "accessibility_captioning_window_color";
  private static final String CAPTIONING_EDGE_TYPE = "accessibility_captioning_edge_type";
  private static final String CAPTIONING_EDGE_COLOR = "accessibility_captioning_edge_color";
  private static final String CAPTIONING_TYPEFACE = "accessibility_captioning_typeface";

  // The index of the yellow text on black background preset in CaptionStyle.PRESETS.
  private static final int PRESET_YELLOW_ON_BLACK = 2;
  private static final int PRESET_CUSTOM = -1;
  private static final int COLOR_UNSPECIFIED = 0x00FFFFFF;
  private static final int EDGE_TYPE_UNSPECIFIED = -1;

  @Test
  public void defaultStyle_unmodified_hasExpectedValues() {
    assertThat(SubtitleStyle.DEFAULT.foregroundColor).isEqualTo(Color.WHITE);
    assertThat(SubtitleStyle.DEFAULT.backgroundColor).isEqualTo(Color.BLACK);
    assertThat(SubtitleStyle.DEFAULT.windowColor).isEqualTo(Color.TRANSPARENT);
    assertThat(SubtitleStyle.DEFAULT.edgeType).isEqualTo(SubtitleStyle.EDGE_TYPE_NONE);
    assertThat(SubtitleStyle.DEFAULT.edgeColor).isEqualTo(Color.WHITE);
    assertThat(SubtitleStyle.DEFAULT.typeface).isNull();
  }

  @Test
  public void buildUpon_modifiedFields_preservesAllFields() {
    SubtitleStyle style =
        new SubtitleStyle.Builder()
            .setForegroundColor(Color.YELLOW)
            .setBackgroundColor(Color.BLUE)
            .setWindowColor(Color.RED)
            .setEdgeType(SubtitleStyle.EDGE_TYPE_OUTLINE)
            .setEdgeColor(Color.GREEN)
            .setTypeface(Typeface.MONOSPACE)
            .build();

    SubtitleStyle rebuiltStyle = style.buildUpon().build();

    assertThat(rebuiltStyle).isEqualTo(style);
  }

  @Test
  public void fromCaptionStyle_withPreset_copiesPresetValues() {
    Context context = ApplicationProvider.getApplicationContext();
    CaptioningManager captioningManager = context.getSystemService(CaptioningManager.class);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_PRESET, PRESET_YELLOW_ON_BLACK);

    SubtitleStyle style = SubtitleStyle.fromCaptionStyle(captioningManager.getUserStyle());

    assertThat(style.foregroundColor).isEqualTo(Color.YELLOW);
    assertThat(style.backgroundColor).isEqualTo(Color.BLACK);
    assertThat(style.edgeType).isEqualTo(SubtitleStyle.EDGE_TYPE_NONE);
  }

  @Test
  public void fromCaptionStyle_withCustomValues_copiesAllSpecifiedFields() {
    Context context = ApplicationProvider.getApplicationContext();
    CaptioningManager captioningManager = context.getSystemService(CaptioningManager.class);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_PRESET, PRESET_CUSTOM);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_FOREGROUND_COLOR, Color.YELLOW);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_BACKGROUND_COLOR, Color.BLUE);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_WINDOW_COLOR, Color.RED);
    Settings.Secure.putInt(
        context.getContentResolver(), CAPTIONING_EDGE_TYPE, SubtitleStyle.EDGE_TYPE_OUTLINE);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_EDGE_COLOR, Color.GREEN);
    Settings.Secure.putString(context.getContentResolver(), CAPTIONING_TYPEFACE, "monospace");

    SubtitleStyle style = SubtitleStyle.fromCaptionStyle(captioningManager.getUserStyle());

    assertThat(style.foregroundColor).isEqualTo(Color.YELLOW);
    assertThat(style.backgroundColor).isEqualTo(Color.BLUE);
    assertThat(style.windowColor).isEqualTo(Color.RED);
    assertThat(style.edgeType).isEqualTo(SubtitleStyle.EDGE_TYPE_OUTLINE);
    assertThat(style.edgeColor).isEqualTo(Color.GREEN);
    assertThat(style.typeface).isEqualTo(Typeface.create("monospace", Typeface.NORMAL));
  }

  @Test
  public void fromCaptionStyle_withUnspecifiedValues_fallsBackToDefault() {
    Context context = ApplicationProvider.getApplicationContext();
    CaptioningManager captioningManager = context.getSystemService(CaptioningManager.class);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_PRESET, PRESET_CUSTOM);
    Settings.Secure.putInt(
        context.getContentResolver(), CAPTIONING_FOREGROUND_COLOR, COLOR_UNSPECIFIED);
    Settings.Secure.putInt(
        context.getContentResolver(), CAPTIONING_BACKGROUND_COLOR, COLOR_UNSPECIFIED);
    Settings.Secure.putInt(
        context.getContentResolver(), CAPTIONING_WINDOW_COLOR, COLOR_UNSPECIFIED);
    Settings.Secure.putInt(
        context.getContentResolver(), CAPTIONING_EDGE_TYPE, EDGE_TYPE_UNSPECIFIED);
    Settings.Secure.putInt(context.getContentResolver(), CAPTIONING_EDGE_COLOR, COLOR_UNSPECIFIED);

    SubtitleStyle style = SubtitleStyle.fromCaptionStyle(captioningManager.getUserStyle());

    assertThat(style).isEqualTo(SubtitleStyle.DEFAULT);
  }

  @Test
  public void equalsHashCode_multipleEqualityGroups_matchesContract() {
    new EqualsTester()
        .addEqualityGroup(
            new SubtitleStyle.Builder().build(),
            SubtitleStyle.DEFAULT.buildUpon().build(),
            SubtitleStyle.DEFAULT)
        // Different foreground color.
        .addEqualityGroup(new SubtitleStyle.Builder().setForegroundColor(Color.YELLOW).build())
        // Different background color.
        .addEqualityGroup(new SubtitleStyle.Builder().setBackgroundColor(Color.BLUE).build())
        // Different window color.
        .addEqualityGroup(new SubtitleStyle.Builder().setWindowColor(Color.RED).build())
        // Different edge type.
        .addEqualityGroup(
            new SubtitleStyle.Builder().setEdgeType(SubtitleStyle.EDGE_TYPE_OUTLINE).build())
        // Different edge color.
        .addEqualityGroup(new SubtitleStyle.Builder().setEdgeColor(Color.GREEN).build())
        // Different typeface.
        .addEqualityGroup(
            new SubtitleStyle.Builder().setTypeface(Typeface.MONOSPACE).build(),
            new SubtitleStyle.Builder().setTypeface(Typeface.MONOSPACE).build())
        .testEquals();
  }
}
