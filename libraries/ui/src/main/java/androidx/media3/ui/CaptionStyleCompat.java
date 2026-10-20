/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static java.lang.annotation.ElementType.TYPE_USE;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;
import androidx.annotation.IntDef;
import androidx.media3.common.text.SubtitleStyle;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/**
 * A compatibility wrapper for {@link CaptionStyle}.
 *
 * @deprecated Use {@link SubtitleStyle} instead.
 */
@Deprecated
@UnstableApi
public final class CaptionStyleCompat {

  /**
   * @deprecated Use {@link SubtitleStyle.EdgeType} instead.
   */
  @Deprecated
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    EDGE_TYPE_NONE,
    EDGE_TYPE_OUTLINE,
    EDGE_TYPE_DROP_SHADOW,
    EDGE_TYPE_RAISED,
    EDGE_TYPE_DEPRESSED
  })
  public @interface EdgeType {}

  /**
   * @deprecated Use {@link SubtitleStyle#EDGE_TYPE_NONE} instead.
   */
  @Deprecated public static final int EDGE_TYPE_NONE = SubtitleStyle.EDGE_TYPE_NONE;

  /**
   * @deprecated Use {@link SubtitleStyle#EDGE_TYPE_OUTLINE} instead.
   */
  @Deprecated public static final int EDGE_TYPE_OUTLINE = SubtitleStyle.EDGE_TYPE_OUTLINE;

  /**
   * @deprecated Use {@link SubtitleStyle#EDGE_TYPE_DROP_SHADOW} instead.
   */
  @Deprecated public static final int EDGE_TYPE_DROP_SHADOW = SubtitleStyle.EDGE_TYPE_DROP_SHADOW;

  /**
   * @deprecated Use {@link SubtitleStyle#EDGE_TYPE_RAISED} instead.
   */
  @Deprecated public static final int EDGE_TYPE_RAISED = SubtitleStyle.EDGE_TYPE_RAISED;

  /**
   * @deprecated Use {@link SubtitleStyle#EDGE_TYPE_DEPRESSED} instead.
   */
  @Deprecated public static final int EDGE_TYPE_DEPRESSED = SubtitleStyle.EDGE_TYPE_DEPRESSED;

  /**
   * Use color setting specified by the track and fallback to default caption style.
   *
   * @deprecated This constant is not used by the library.
   */
  @Deprecated public static final int USE_TRACK_COLOR_SETTINGS = 1;

  /**
   * @deprecated Use {@link SubtitleStyle#DEFAULT} instead.
   */
  @Deprecated
  public static final CaptionStyleCompat DEFAULT = fromSubtitleStyle(SubtitleStyle.DEFAULT);

  /** The preferred foreground color. */
  public final int foregroundColor;

  /** The preferred background color. */
  public final int backgroundColor;

  /** The preferred window color. */
  public final int windowColor;

  /**
   * The preferred edge type. One of:
   *
   * <ul>
   *   <li>{@link #EDGE_TYPE_NONE}
   *   <li>{@link #EDGE_TYPE_OUTLINE}
   *   <li>{@link #EDGE_TYPE_DROP_SHADOW}
   *   <li>{@link #EDGE_TYPE_RAISED}
   *   <li>{@link #EDGE_TYPE_DEPRESSED}
   * </ul>
   */
  public final @EdgeType int edgeType;

  /** The preferred edge color, if using an edge type other than {@link #EDGE_TYPE_NONE}. */
  public final int edgeColor;

  /** The preferred typeface, or {@code null} if unspecified. */
  @Nullable public final Typeface typeface;

  /**
   * @deprecated Use {@link SubtitleStyle#fromCaptionStyle(CaptionStyle)} instead.
   */
  @Deprecated
  public static CaptionStyleCompat createFromCaptionStyle(
      CaptioningManager.CaptionStyle captionStyle) {
    return fromSubtitleStyle(SubtitleStyle.fromCaptionStyle(captionStyle));
  }

  /**
   * @deprecated Use {@link SubtitleStyle.Builder} instead.
   */
  @Deprecated
  public CaptionStyleCompat(
      int foregroundColor,
      int backgroundColor,
      int windowColor,
      @EdgeType int edgeType,
      int edgeColor,
      @Nullable Typeface typeface) {
    this.foregroundColor = foregroundColor;
    this.backgroundColor = backgroundColor;
    this.windowColor = windowColor;
    this.edgeType = edgeType;
    this.edgeColor = edgeColor;
    this.typeface = typeface;
  }

  /** Returns the equivalent {@link SubtitleStyle}. */
  @SuppressLint("WrongConstant") // CaptionStyleCompat.EdgeType and SubtitleStyle.EdgeType match.
  /* package */ SubtitleStyle toSubtitleStyle() {
    return new SubtitleStyle.Builder()
        .setForegroundColor(foregroundColor)
        .setBackgroundColor(backgroundColor)
        .setWindowColor(windowColor)
        .setEdgeType(edgeType)
        .setEdgeColor(edgeColor)
        .setTypeface(typeface)
        .build();
  }

  private static CaptionStyleCompat fromSubtitleStyle(SubtitleStyle subtitleStyle) {
    return new CaptionStyleCompat(
        subtitleStyle.foregroundColor,
        subtitleStyle.backgroundColor,
        subtitleStyle.windowColor,
        subtitleStyle.edgeType,
        subtitleStyle.edgeColor,
        subtitleStyle.typeface);
  }
}
