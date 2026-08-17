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

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Label;
import com.google.common.primitives.Longs;
import java.util.Objects;

/** A generic, format-agnostic implementation of the {@link Chapter} interface. */
/* package */ final class ChapterImpl implements Chapter {

  private final long startTimeMs;
  private final long endTimeMs;
  private final boolean isHidden;
  @Nullable private final Label title;

  /**
   * Creates an instance.
   *
   * @param startTimeMs The start time in milliseconds.
   * @param endTimeMs The end time in milliseconds.
   * @param isHidden Whether the chapter is hidden.
   * @param title The title of the chapter.
   */
  public ChapterImpl(long startTimeMs, long endTimeMs, boolean isHidden, @Nullable Label title) {
    checkArgument(
        startTimeMs == C.TIME_UNSET || endTimeMs == C.TIME_UNSET || startTimeMs <= endTimeMs);
    this.startTimeMs = startTimeMs;
    this.endTimeMs = endTimeMs;
    this.isHidden = isHidden;
    this.title = title;
  }

  @Override
  public long getStartTimeMs() {
    return startTimeMs;
  }

  @Override
  public long getEndTimeMs() {
    return endTimeMs;
  }

  @Override
  public boolean isHidden() {
    return isHidden;
  }

  @Nullable
  @Override
  public Label getTitle() {
    return title;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    ChapterImpl other = (ChapterImpl) obj;
    return startTimeMs == other.startTimeMs
        && endTimeMs == other.endTimeMs
        && isHidden == other.isHidden
        && Objects.equals(title, other.title);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + Longs.hashCode(startTimeMs);
    result = 31 * result + Longs.hashCode(endTimeMs);
    result = 31 * result + (isHidden ? 1 : 0);
    result = 31 * result + (title != null ? title.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "Chapter: startTimeMs="
        + (startTimeMs == C.TIME_UNSET ? "UNSET" : startTimeMs)
        + (endTimeMs == C.TIME_UNSET ? "" : ", endTimeMs=" + endTimeMs)
        + (isHidden ? ", hidden" : "")
        + (title == null ? "" : ", title=" + title);
  }
}
