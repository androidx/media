/*
 * Copyright 2022 The Android Open Source Project
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

import static androidx.media3.container.MdtaMetadataEntry.EDITABLE_TRACKS_SAMPLES_LOCATION_INTERLEAVED;
import static androidx.media3.container.MdtaMetadataEntry.EDITABLE_TRACKS_SAMPLES_LOCATION_IN_EDIT_DATA_MP4;
import static androidx.media3.container.MdtaMetadataEntry.TYPE_INDICATOR_8_BIT_UNSIGNED_INT;
import static androidx.media3.container.Mp4Util.EDITABLE_TRACK_TYPE_DEPTH_INVERSE;
import static androidx.media3.container.Mp4Util.EDITABLE_TRACK_TYPE_DEPTH_LINEAR;
import static androidx.media3.container.Mp4Util.EDITABLE_TRACK_TYPE_DEPTH_METADATA;
import static androidx.media3.container.Mp4Util.EDITABLE_TRACK_TYPE_SHARP;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.common.primitives.Longs;
import java.util.List;

/** Utility methods for muxer. */
@UnstableApi
public final class MuxerUtil {
  /** The maximum value of a 32-bit unsigned int. */
  public static final long UNSIGNED_INT_MAX_VALUE = 4_294_967_295L;

  private MuxerUtil() {}

  /** Returns whether a given {@link Metadata.Entry metadata} is supported. */
  public static boolean isMetadataSupported(Metadata.Entry metadata) {
    return metadata instanceof Mp4OrientationData
        || metadata instanceof Mp4LocationData
        || (metadata instanceof Mp4TimestampData
            && isMp4TimestampDataSupported((Mp4TimestampData) metadata))
        || (metadata instanceof MdtaMetadataEntry
            && isMdtaMetadataEntrySupported((MdtaMetadataEntry) metadata))
        || metadata instanceof XmpData;
  }

  /**
   * Returns whether the given {@linkplain Format track format} is an editable video track.
   *
   * <p>The {@linkplain Format track format} with {@link C#ROLE_FLAG_AUXILIARY} and the {@code
   * auxiliaryTrackType} from the following are considered as an editable video track.
   *
   * <ul>
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_ORIGINAL}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_LINEAR}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_INVERSE}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_METADATA}
   * </ul>
   */
  /* package */ static boolean isEditableVideoTrack(Format format) {
    return (format.roleFlags & C.ROLE_FLAG_AUXILIARY) > 0
        && (format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_ORIGINAL
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA);
  }

  /** Returns a {@link MdtaMetadataEntry} for the editable tracks offset metadata. */
  /* package */ static MdtaMetadataEntry getEditableTracksOffsetMetadata(long offset) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_EDITABLE_TRACKS_OFFSET,
        Longs.toByteArray(offset),
        MdtaMetadataEntry.TYPE_INDICATOR_UNSIGNED_INT64);
  }

  /** Returns a {@link MdtaMetadataEntry} for the editable tracks length metadata. */
  /* package */ static MdtaMetadataEntry getEditableTracksLengthMetadata(long length) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_EDITABLE_TRACKS_LENGTH,
        Longs.toByteArray(length),
        MdtaMetadataEntry.TYPE_INDICATOR_UNSIGNED_INT64);
  }

  /**
   * Populates editable video tracks metadata.
   *
   * @param metadataCollector The {@link MetadataCollector} to add the metadata to.
   * @param timestampData The {@link Mp4TimestampData}.
   * @param samplesInterleaved Whether editable video track samples are interleaved with the primary
   *     track samples.
   * @param editableVideoTracks The editable video tracks.
   */
  /* package */ static void populateEditableVideoTracksMetadata(
      MetadataCollector metadataCollector,
      Mp4TimestampData timestampData,
      boolean samplesInterleaved,
      List<Track> editableVideoTracks) {
    metadataCollector.addMetadata(timestampData);
    metadataCollector.addMetadata(getEditableTracksSamplesLocationMetadata(samplesInterleaved));
    metadataCollector.addMetadata(getEditableTracksMapMetadata(editableVideoTracks));
  }

  private static MdtaMetadataEntry getEditableTracksSamplesLocationMetadata(
      boolean samplesInterleaved) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_EDITABLE_TRACKS_SAMPLES_LOCATION,
        new byte[] {
          samplesInterleaved
              ? EDITABLE_TRACKS_SAMPLES_LOCATION_INTERLEAVED
              : EDITABLE_TRACKS_SAMPLES_LOCATION_IN_EDIT_DATA_MP4
        },
        TYPE_INDICATOR_8_BIT_UNSIGNED_INT);
  }

  private static MdtaMetadataEntry getEditableTracksMapMetadata(List<Track> editableVideoTracks) {
    // 1 byte version + 1 byte track count (n) + n bytes track types.
    int totalTracks = editableVideoTracks.size();
    int dataSize = 2 + totalTracks;
    byte[] data = new byte[dataSize];
    data[0] = 1; // version
    data[1] = (byte) totalTracks; // track count
    for (int i = 0; i < totalTracks; i++) {
      Track track = editableVideoTracks.get(i);
      int trackType;
      switch (track.format.auxiliaryTrackType) {
        case C.AUXILIARY_TRACK_TYPE_ORIGINAL:
          trackType = EDITABLE_TRACK_TYPE_SHARP;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR:
          trackType = EDITABLE_TRACK_TYPE_DEPTH_LINEAR;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE:
          trackType = EDITABLE_TRACK_TYPE_DEPTH_INVERSE;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA:
          trackType = EDITABLE_TRACK_TYPE_DEPTH_METADATA;
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported editable track type " + track.format.auxiliaryTrackType);
      }
      data[i + 2] = (byte) trackType;
    }
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_EDITABLE_TRACKS_MAP, data, MdtaMetadataEntry.TYPE_INDICATOR_RESERVED);
  }

  private static boolean isMdtaMetadataEntrySupported(MdtaMetadataEntry mdtaMetadataEntry) {
    return mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING
        || mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32;
  }

  private static boolean isMp4TimestampDataSupported(Mp4TimestampData timestampData) {
    return timestampData.creationTimestampSeconds <= UNSIGNED_INT_MAX_VALUE
        && timestampData.modificationTimestampSeconds <= UNSIGNED_INT_MAX_VALUE;
  }
}
