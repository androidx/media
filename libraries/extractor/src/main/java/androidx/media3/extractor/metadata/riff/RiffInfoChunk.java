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
package androidx.media3.extractor.metadata.riff;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;

/** The data from one or more chunks in the RIFF INFO list with the same fourcc. */
@UnstableApi
public final class RiffInfoChunk implements Metadata.Entry {

  /** The fourcc converted to a String. */
  public final String id;

  /** The text values of these chunks. Will always have at least one element. */
  public final ImmutableList<String> values;

  public RiffInfoChunk(String id, List<String> values) {
    checkArgument(!values.isEmpty());

    this.id = id;
    this.values = ImmutableList.copyOf(values);
  }

  /**
   * Uses the first element in {@link #values} to set the relevant field in {@link MediaMetadata}
   * (as determined by {@link #id}).
   */
  @Override
  public void populateMediaMetadata(MediaMetadata.Builder builder) {
    switch (id) {
      case "IART":
        builder.setArtist(values.get(0));
        break;
      case "ICRD":
        try {
          int year = Integer.parseInt(values.get(0).substring(0, 4));
          int month = Integer.parseInt(values.get(0).substring(5, 7));
          int day = Integer.parseInt(values.get(0).substring(8, 10));
          builder.setRecordingYear(year);
          builder.setRecordingMonth(month);
          builder.setRecordingDay(day);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
          // Do nothing, invalid input.
        }
        break;
      case "IGNR":
      case "GENR":
        builder.setGenre(values.get(0));
        break;
      case "IPRD":
      case "IALB":
        builder.setAlbumTitle(values.get(0));
        break;
      case "ICOM":
      case "IMUS":
        builder.setComposer(values.get(0));
        break;
      case "IWRI":
        builder.setWriter(values.get(0));
        break;
      case "ISBJ":
        builder.setDescription(values.get(0));
        break;
      case "INAM":
      case "TITL":
        builder.setTitle(values.get(0));
        break;
      case "IYER":
      case "YEAR":
        try {
          builder.setReleaseYear(Integer.parseInt(values.get(0)));
        } catch (NumberFormatException e) {
          // Do nothing, invalid input.
        }
        break;
      case "IFRM":
        try {
          builder.setTotalTrackCount(Integer.parseInt(values.get(0)));
        } catch (NumberFormatException e) {
          // Do nothing, invalid input.
        }
        break;
      case "ITRK":
      case "TRCK":
      case "IPRT":
        try {
          builder.setTrackNumber(Integer.parseInt(values.get(0)));
        } catch (NumberFormatException e) {
          // Do nothing, invalid input.
        }
        break;
      default:
        break;
    }
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    RiffInfoChunk other = (RiffInfoChunk) obj;
    return Objects.equals(id, other.id) && values.equals(other.values);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + id.hashCode();
    result = 31 * result + values.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return id + ": values=" + values;
  }
}
