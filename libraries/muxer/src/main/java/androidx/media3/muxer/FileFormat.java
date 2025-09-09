/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import static androidx.media3.muxer.FileFormat.FILE_FORMAT_MP4;
import static androidx.media3.muxer.FileFormat.FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaMuxer;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The file format. */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(TYPE_USE)
@IntDef({FILE_FORMAT_MP4, FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION})
@UnstableApi
public @interface FileFormat {
  /** The MP4 file format. */
  int FILE_FORMAT_MP4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

  /**
   * The MP4 With Auxiliary Tracks Extension (MP4-AT) file format. In this file format all the
   * tracks with {@linkplain Format#auxiliaryTrackType} set to {@link
   * C#AUXILIARY_TRACK_TYPE_ORIGINAL}, {@link C#AUXILIARY_TRACK_TYPE_DEPTH_LINEAR}, {@link
   * C#AUXILIARY_TRACK_TYPE_DEPTH_INVERSE}, or {@link C#AUXILIARY_TRACK_TYPE_DEPTH_METADATA} are
   * written in the Auxiliary Tracks MP4 (axte box). The rest of the tracks are written as usual.
   *
   * <p>See the file format at https://developer.android.com/media/platform/mp4-at-file-format.
   */
  int FILE_FORMAT_MP4_WITH_AUXILIARY_TRACKS_EXTENSION = 1000;
}
