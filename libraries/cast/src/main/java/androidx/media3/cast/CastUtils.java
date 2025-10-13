/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.cast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaTrack;

/** Utility methods for Cast integration. */
/* package */ final class CastUtils {

  /**
   * Returns the duration in microseconds advertised by a media info, or {@link C#TIME_UNSET} if
   * unknown or not applicable.
   *
   * @param mediaInfo The media info to get the duration from.
   * @return The duration in microseconds, or {@link C#TIME_UNSET} if unknown or not applicable.
   */
  public static long getStreamDurationUs(@Nullable MediaInfo mediaInfo) {
    if (mediaInfo == null) {
      return C.TIME_UNSET;
    }
    long durationMs = mediaInfo.getStreamDuration();
    return durationMs != MediaInfo.UNKNOWN_DURATION ? Util.msToUs(durationMs) : C.TIME_UNSET;
  }

  /**
   * Returns a descriptive log string for the given {@code statusCode}, or "Unknown." if not one of
   * {@link CastStatusCodes}.
   *
   * @param statusCode A Cast API status code.
   * @return A descriptive log string for the given {@code statusCode}, or "Unknown." if not one of
   *     {@link CastStatusCodes}.
   */
  public static String getLogString(int statusCode) {
    switch (statusCode) {
      case CastStatusCodes.APPLICATION_NOT_FOUND:
        return "A requested application could not be found.";
      case CastStatusCodes.APPLICATION_NOT_RUNNING:
        return "A requested application is not currently running.";
      case CastStatusCodes.AUTHENTICATION_FAILED:
        return "Authentication failure.";
      case CastStatusCodes.CANCELED:
        return "An in-progress request has been canceled, most likely because another action has "
            + "preempted it.";
      case CastStatusCodes.ERROR_SERVICE_CREATION_FAILED:
        return "The Cast Remote Display service could not be created.";
      case CastStatusCodes.ERROR_SERVICE_DISCONNECTED:
        return "The Cast Remote Display service was disconnected.";
      case CastStatusCodes.FAILED:
        return "The in-progress request failed.";
      case CastStatusCodes.INTERNAL_ERROR:
        return "An internal error has occurred.";
      case CastStatusCodes.INTERRUPTED:
        return "A blocking call was interrupted while waiting and did not run to completion.";
      case CastStatusCodes.INVALID_REQUEST:
        return "An invalid request was made.";
      case CastStatusCodes.MESSAGE_SEND_BUFFER_TOO_FULL:
        return "A message could not be sent because there is not enough room in the send buffer at "
            + "this time.";
      case CastStatusCodes.MESSAGE_TOO_LARGE:
        return "A message could not be sent because it is too large.";
      case CastStatusCodes.NETWORK_ERROR:
        return "Network I/O error.";
      case CastStatusCodes.NOT_ALLOWED:
        return "The request was disallowed and could not be completed.";
      case CastStatusCodes.REPLACED:
        return "The request's progress is no longer being tracked because another request of the "
            + "same type has been made before the first request completed.";
      case CastStatusCodes.SUCCESS:
        return "Success.";
      case CastStatusCodes.TIMEOUT:
        return "An operation has timed out.";
      case CastStatusCodes.UNKNOWN_ERROR:
        return "An unknown, unexpected error has occurred.";
      default:
        return CastStatusCodes.getStatusCodeString(statusCode);
    }
  }

  /** Returns a {@link TrackGroup} that represents the given {@link MediaTrack}. */
  public static TrackGroup mediaTrackToTrackGroup(String trackGroupId, MediaTrack mediaTrack) {
    String mimeType = mediaTrack.getContentType();
    @TrackType int media3TrackType = toMedia3TrackType(mediaTrack.getType());
    if (media3TrackType != MimeTypes.getTrackType(mimeType)) {
      // We update the mime type to match the track's type, so as to ensure that TrackGroup infers
      // the correct track type from the created format. See b/447601947.
      String mimeTypeForTrackType = getUnknownMimeTypeForTrackType(media3TrackType);
      if (mimeTypeForTrackType != null) {
        mimeType = mimeTypeForTrackType;
      }
    }
    Format format =
        new Format.Builder()
            .setId(mediaTrack.getContentId())
            .setContainerMimeType(mimeType)
            .setLanguage(mediaTrack.getLanguage())
            .build();
    return new TrackGroup(trackGroupId, format);
  }

  /**
   * Returns the {@link C.TrackType} equivalent of the given Cast {@link
   * com.google.android.gms.cast.MediaTrack#getType()}.
   */
  private static @TrackType int toMedia3TrackType(int castTrackType) {
    switch (castTrackType) {
      case MediaTrack.TYPE_AUDIO:
        return C.TRACK_TYPE_AUDIO;
      case MediaTrack.TYPE_VIDEO:
        return C.TRACK_TYPE_VIDEO;
      case MediaTrack.TYPE_TEXT:
        return C.TRACK_TYPE_TEXT;
      default:
        return C.TRACK_TYPE_UNKNOWN;
    }
  }

  /**
   * Returns a MIME type with a type that matches the given track type and unknown sub-type, or null
   * if the track type is not one of {@link C#TRACK_TYPE_AUDIO}, {@link C#TRACK_TYPE_TEXT}, or
   * {@link C#TRACK_TYPE_VIDEO}, which are the types supported by the Cast SDK.
   */
  @Nullable
  private static String getUnknownMimeTypeForTrackType(@TrackType int media3TrackType) {
    switch (media3TrackType) {
      case C.TRACK_TYPE_AUDIO:
        return MimeTypes.AUDIO_UNKNOWN;
      case C.TRACK_TYPE_TEXT:
        return MimeTypes.TEXT_UNKNOWN;
      case C.TRACK_TYPE_VIDEO:
        return MimeTypes.VIDEO_UNKNOWN;
      default:
        return null;
    }
  }

  private CastUtils() {}
}
