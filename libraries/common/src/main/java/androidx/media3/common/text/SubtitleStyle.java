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
package androidx.media3.common.text;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/**
 * Styling for subtitles that isn't carried by the media, such as user or app preferences.
 *
 * <p>An instance can be created from the user's system caption style using {@link
 * #fromCaptionStyle(CaptionStyle)} with {@link CaptioningManager#getUserStyle()}, or constructed
 * directly with {@link Builder}.
 */
@UnstableApi
public final class SubtitleStyle {

  /**
   * The type of edge, which may be none. One of {@link #EDGE_TYPE_NONE}, {@link
   * #EDGE_TYPE_OUTLINE}, {@link #EDGE_TYPE_DROP_SHADOW}, {@link #EDGE_TYPE_RAISED} or {@link
   * #EDGE_TYPE_DEPRESSED}.
   */
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

  /** Edge type value specifying no character edges. */
  public static final int EDGE_TYPE_NONE = 0;

  /** Edge type value specifying uniformly outlined character edges. */
  public static final int EDGE_TYPE_OUTLINE = 1;

  /** Edge type value specifying drop-shadowed character edges. */
  public static final int EDGE_TYPE_DROP_SHADOW = 2;

  /** Edge type value specifying raised bevel character edges. */
  public static final int EDGE_TYPE_RAISED = 3;

  /** Edge type value specifying depressed bevel character edges. */
  public static final int EDGE_TYPE_DEPRESSED = 4;

  /** A {@link SubtitleStyle} with the default values specified by {@link Builder}. */
  public static final SubtitleStyle DEFAULT = new Builder().build();

  /** A builder for {@link SubtitleStyle} instances. */
  public static final class Builder {

    @ColorInt private int foregroundColor;
    @ColorInt private int backgroundColor;
    @ColorInt private int windowColor;
    private @EdgeType int edgeType;
    @ColorInt private int edgeColor;
    @Nullable private Typeface typeface;

    /** Creates an instance with default initial values. */
    public Builder() {
      foregroundColor = Color.WHITE;
      backgroundColor = Color.BLACK;
      windowColor = Color.TRANSPARENT;
      edgeType = EDGE_TYPE_NONE;
      edgeColor = Color.WHITE;
      typeface = null;
    }

    private Builder(SubtitleStyle subtitleStyle) {
      foregroundColor = subtitleStyle.foregroundColor;
      backgroundColor = subtitleStyle.backgroundColor;
      windowColor = subtitleStyle.windowColor;
      edgeType = subtitleStyle.edgeType;
      edgeColor = subtitleStyle.edgeColor;
      typeface = subtitleStyle.typeface;
    }

    /**
     * Sets the preferred foreground color.
     *
     * <p>The default is {@link Color#WHITE}.
     *
     * @param foregroundColor See {@link SubtitleStyle#foregroundColor}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setForegroundColor(@ColorInt int foregroundColor) {
      this.foregroundColor = foregroundColor;
      return this;
    }

    /**
     * Sets the preferred background color.
     *
     * <p>The default is {@link Color#BLACK}.
     *
     * @param backgroundColor See {@link SubtitleStyle#backgroundColor}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBackgroundColor(@ColorInt int backgroundColor) {
      this.backgroundColor = backgroundColor;
      return this;
    }

    /**
     * Sets the preferred window color.
     *
     * <p>The default is {@link Color#TRANSPARENT}.
     *
     * @param windowColor See {@link SubtitleStyle#windowColor}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setWindowColor(@ColorInt int windowColor) {
      this.windowColor = windowColor;
      return this;
    }

    /**
     * Sets the preferred edge type.
     *
     * <p>The default is {@link #EDGE_TYPE_NONE}.
     *
     * @param edgeType See {@link SubtitleStyle#edgeType}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEdgeType(@EdgeType int edgeType) {
      this.edgeType = edgeType;
      return this;
    }

    /**
     * Sets the preferred edge color, if using an edge type other than {@link #EDGE_TYPE_NONE}.
     *
     * <p>The default is {@link Color#WHITE}.
     *
     * @param edgeColor See {@link SubtitleStyle#edgeColor}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEdgeColor(@ColorInt int edgeColor) {
      this.edgeColor = edgeColor;
      return this;
    }

    /**
     * Sets the preferred typeface, or {@code null} if unspecified.
     *
     * <p>The default is {@code null}.
     *
     * @param typeface See {@link SubtitleStyle#typeface}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTypeface(@Nullable Typeface typeface) {
      this.typeface = typeface;
      return this;
    }

    /** Builds a {@link SubtitleStyle} instance. */
    public SubtitleStyle build() {
      return new SubtitleStyle(this);
    }
  }

  /** The preferred foreground color. */
  @ColorInt public final int foregroundColor;

  /** The preferred background color. */
  @ColorInt public final int backgroundColor;

  /** The preferred window color. */
  @ColorInt public final int windowColor;

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
  @ColorInt public final int edgeColor;

  /** The preferred typeface, or {@code null} if unspecified. */
  @Nullable public final Typeface typeface;

  /**
   * Creates a {@link SubtitleStyle} from a provided {@link CaptionStyle}.
   *
   * <p>Any properties that aren't set in the {@link CaptionStyle} are taken from {@link #DEFAULT}.
   *
   * @param captionStyle A {@link CaptionStyle}.
   * @return The corresponding {@link SubtitleStyle}.
   */
  public static SubtitleStyle fromCaptionStyle(CaptionStyle captionStyle) {
    Builder builder = new Builder();
    if (captionStyle.hasForegroundColor()) {
      builder.setForegroundColor(captionStyle.foregroundColor);
    }
    if (captionStyle.hasBackgroundColor()) {
      builder.setBackgroundColor(captionStyle.backgroundColor);
    }
    if (captionStyle.hasWindowColor()) {
      builder.setWindowColor(captionStyle.windowColor);
    }
    if (captionStyle.hasEdgeType()) {
      builder.setEdgeType(captionStyle.edgeType);
    }
    if (captionStyle.hasEdgeColor()) {
      builder.setEdgeColor(captionStyle.edgeColor);
    }
    return builder.setTypeface(captionStyle.getTypeface()).build();
  }

  private SubtitleStyle(Builder builder) {
    this.foregroundColor = builder.foregroundColor;
    this.backgroundColor = builder.backgroundColor;
    this.windowColor = builder.windowColor;
    this.edgeType = builder.edgeType;
    this.edgeColor = builder.edgeColor;
    this.typeface = builder.typeface;
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    SubtitleStyle that = (SubtitleStyle) obj;
    return foregroundColor == that.foregroundColor
        && backgroundColor == that.backgroundColor
        && windowColor == that.windowColor
        && edgeType == that.edgeType
        && edgeColor == that.edgeColor
        && Objects.equals(typeface, that.typeface);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + foregroundColor;
    result = 31 * result + backgroundColor;
    result = 31 * result + windowColor;
    result = 31 * result + edgeType;
    result = 31 * result + edgeColor;
    result = 31 * result + (typeface != null ? typeface.hashCode() : 0);
    return result;
  }
}
