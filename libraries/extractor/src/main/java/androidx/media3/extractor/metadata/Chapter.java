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
package androidx.media3.extractor.metadata;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Label;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Chapter information, for example ID3 {@code CHAP} or Quicktime {@code chap} data.
 *
 * <p>The start and end times are an offset from the start of the current {@link Timeline.Period}.
 * To use these times with {@link Player#seekTo}, they must be converted to be relative to the
 * window by subtracting the window offset.
 *
 * <p>Example conversion:
 *
 * <pre>{@code
 * long windowOffsetMs = player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window()).positionInFirstPeriodUs / 1000;
 * long seekPositionMs = chapter.getStartTimeMs() - windowOffsetMs;
 * player.seekTo(seekPositionMs);
 * }</pre>
 */
@UnstableApi
public interface Chapter extends Metadata.Entry {

  /** Builder for {@link Chapter} instances. */
  class Builder {
    private long startTimeMs;
    private long endTimeMs;
    private boolean isHidden;
    @Nullable private Label title;

    /** Constructs an instance. */
    public Builder() {
      this.startTimeMs = C.TIME_UNSET;
      this.endTimeMs = C.TIME_UNSET;
    }

    /**
     * Sets the start time of the chapter, or {@link C#TIME_UNSET} if not known.
     *
     * <p>Defaults to {@link C#TIME_UNSET}.
     *
     * @see Chapter#getStartTimeMs()
     */
    @CanIgnoreReturnValue
    public Builder setStartTimeMs(long startTimeMs) {
      this.startTimeMs = startTimeMs;
      return this;
    }

    /**
     * Sets the end time of the chapter, or {@link C#TIME_UNSET} if not known.
     *
     * <p>Defaults to {@link C#TIME_UNSET}.
     *
     * @see Chapter#getEndTimeMs()
     */
    @CanIgnoreReturnValue
    public Builder setEndTimeMs(long endTimeMs) {
      this.endTimeMs = endTimeMs;
      return this;
    }

    /**
     * Sets whether the chapter is hidden.
     *
     * <p>Defaults to {@code false}.
     *
     * @see Chapter#isHidden()
     */
    @CanIgnoreReturnValue
    public Builder setHidden(boolean isHidden) {
      this.isHidden = isHidden;
      return this;
    }

    /**
     * Sets the title of this chapter, or {@code null} if not known.
     *
     * <p>Defaults to {@code null}.
     *
     * @see Chapter#getTitle()
     */
    @CanIgnoreReturnValue
    public Builder setTitle(@Nullable Label title) {
      this.title = title;
      return this;
    }

    /** Builds a {@link Chapter} instance with the provided values. */
    public Chapter build() {
      return new ChapterImpl(startTimeMs, endTimeMs, isHidden, title);
    }
  }

  /** Returns the start time of the chapter in milliseconds, or {@link C#TIME_UNSET} if unknown. */
  long getStartTimeMs();

  /** Returns the end time of the chapter in milliseconds, or {@link C#TIME_UNSET} if unknown. */
  long getEndTimeMs();

  /**
   * Returns whether the chapter is hidden.
   *
   * <p>A hidden chapter should not be shown in a table of contents UI, but should not affect
   * playback.
   */
  boolean isHidden();

  /** Returns the title of the chapter, or {@code null} if it has no title. */
  @Nullable
  Label getTitle();
}
