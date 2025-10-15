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

import static androidx.media3.common.util.Util.getBufferFlagsFromMediaCodecFlags;
import static androidx.media3.container.MdtaMetadataEntry.AUXILIARY_TRACKS_SAMPLES_INTERLEAVED;
import static androidx.media3.container.MdtaMetadataEntry.AUXILIARY_TRACKS_SAMPLES_NOT_INTERLEAVED;
import static androidx.media3.container.MdtaMetadataEntry.TYPE_INDICATOR_8_BIT_UNSIGNED_INT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.media.MediaCodec;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.container.XmpData;
import com.google.common.primitives.Longs;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Locale;

/** Utility methods for muxer. */
@UnstableApi
public final class MuxerUtil {
  /** The maximum value of a 32-bit unsigned int. */
  public static final long UNSIGNED_INT_MAX_VALUE = 4_294_967_295L;

  private static final int SEGMENT_MARKER_LENGTH = 2;
  private static final int SEGMENT_SIZE_LENGTH = 2;
  private static final short SOI_MARKER = (short) 0xFFD8;
  private static final short APP0_MARKER = (short) 0xFFE0;
  private static final short APP1_MARKER = (short) 0xFFE1;
  private static final short SOS_MARKER = (short) 0xFFDA;
  private static final short EOI_MARKER = (short) 0xFFD9;
  private static final String JPEG_XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000";

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

  /** Returns {@link BufferInfo} corresponding to the {@link MediaCodec.BufferInfo}. */
  public static BufferInfo getMuxerBufferInfoFromMediaCodecBufferInfo(
      MediaCodec.BufferInfo mediaCodecBufferInfo) {
    checkNotNull(mediaCodecBufferInfo);
    return new BufferInfo(
        mediaCodecBufferInfo.presentationTimeUs,
        mediaCodecBufferInfo.size,
        getBufferFlagsFromMediaCodecFlags(mediaCodecBufferInfo.flags));
  }

  /**
   * Creates a Motion Photo from a JPEG image and a BMFF video as per the <a
   * href="https://developer.android.com/media/platform/motion-photo-format">Motion Photo spec</a>.
   *
   * @param imageInputStream A {@link FileInputStream} containing the image data. The caller is
   *     responsible for closing the stream once this method returns.
   * @param imagePresentationTimestampUs The presentation timestamp of the image in the video (in
   *     microseconds).
   * @param videoInputStream A {@link FileInputStream} containing the video data. The caller is
   *     responsible for closing the stream once this method returns.
   * @param videoContainerMimeType The container mime type of the video. Must be {@link
   *     MimeTypes#VIDEO_MP4} or {@link MimeTypes#VIDEO_QUICK_TIME}.
   * @param outputChannel A {@link WritableByteChannel} to write output to. The caller is
   *     responsible for closing the channel once this method returns.
   * @throws IOException If an error occurs when creating the Motion Photo.
   */
  public static void createMotionPhotoFromJpegImageAndBmffVideo(
      FileInputStream imageInputStream,
      long imagePresentationTimestampUs,
      FileInputStream videoInputStream,
      String videoContainerMimeType,
      WritableByteChannel outputChannel)
      throws IOException {
    checkArgument(
        videoContainerMimeType.equals(MimeTypes.VIDEO_MP4)
            || videoContainerMimeType.equals(MimeTypes.VIDEO_QUICK_TIME),
        "Only MP4 and QUICKTIME container mime types supported");

    FileChannel imageFileChannel = imageInputStream.getChannel();
    MappedByteBuffer imageData =
        imageFileChannel.map(
            FileChannel.MapMode.READ_ONLY, /* position= */ 0, imageFileChannel.size());
    FileChannel videoFileChannel = videoInputStream.getChannel();
    writeImageDataToOutput(
        imageData,
        imagePresentationTimestampUs,
        MimeTypes.IMAGE_JPEG,
        videoFileChannel.size(),
        videoContainerMimeType,
        outputChannel);
    // Write video data to output.
    videoFileChannel.transferTo(/* position= */ 0, videoFileChannel.size(), outputChannel);
  }

  /**
   * Returns whether the given {@linkplain Format track format} is an auxiliary track.
   *
   * <p>The {@linkplain Format track format} with {@link C#ROLE_FLAG_AUXILIARY} and the {@code
   * auxiliaryTrackType} from the following are considered as an auxiliary track.
   *
   * <ul>
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_ORIGINAL}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_LINEAR}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_INVERSE}
   *   <li>{@link C#AUXILIARY_TRACK_TYPE_DEPTH_METADATA}
   * </ul>
   */
  /* package */ static boolean isAuxiliaryTrack(Format format) {
    return (format.roleFlags & C.ROLE_FLAG_AUXILIARY) > 0
        && (format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_ORIGINAL
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE
            || format.auxiliaryTrackType == C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA);
  }

  /** Returns a {@link MdtaMetadataEntry} for the auxiliary tracks offset metadata. */
  /* package */ static MdtaMetadataEntry getAuxiliaryTracksOffsetMetadata(long offset) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_OFFSET,
        Longs.toByteArray(offset),
        MdtaMetadataEntry.TYPE_INDICATOR_UNSIGNED_INT64);
  }

  /** Returns a {@link MdtaMetadataEntry} for the auxiliary tracks length metadata. */
  /* package */ static MdtaMetadataEntry getAuxiliaryTracksLengthMetadata(long length) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_LENGTH,
        Longs.toByteArray(length),
        MdtaMetadataEntry.TYPE_INDICATOR_UNSIGNED_INT64);
  }

  /**
   * Populates auxiliary tracks metadata.
   *
   * @param metadataCollector The {@link MetadataCollector} to add the metadata to.
   * @param timestampData The {@link Mp4TimestampData}.
   * @param samplesInterleaved Whether auxiliary track samples are interleaved with the primary
   *     track samples.
   * @param auxiliaryTracks The auxiliary tracks.
   */
  /* package */ static void populateAuxiliaryTracksMetadata(
      MetadataCollector metadataCollector,
      Mp4TimestampData timestampData,
      boolean samplesInterleaved,
      List<Track> auxiliaryTracks) {
    metadataCollector.addMetadata(timestampData);
    metadataCollector.addMetadata(getAuxiliaryTracksSamplesLocationMetadata(samplesInterleaved));
    metadataCollector.addMetadata(getAuxiliaryTracksMapMetadata(auxiliaryTracks));
  }

  private static MdtaMetadataEntry getAuxiliaryTracksSamplesLocationMetadata(
      boolean samplesInterleaved) {
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_INTERLEAVED,
        new byte[] {
          samplesInterleaved
              ? AUXILIARY_TRACKS_SAMPLES_INTERLEAVED
              : AUXILIARY_TRACKS_SAMPLES_NOT_INTERLEAVED
        },
        TYPE_INDICATOR_8_BIT_UNSIGNED_INT);
  }

  private static MdtaMetadataEntry getAuxiliaryTracksMapMetadata(List<Track> auxiliaryTracks) {
    // 1 byte version + 1 byte track count (n) + n bytes track types.
    int totalTracks = auxiliaryTracks.size();
    int dataSize = 2 + totalTracks;
    byte[] data = new byte[dataSize];
    data[0] = 1; // version
    data[1] = (byte) totalTracks; // track count
    for (int i = 0; i < totalTracks; i++) {
      Track track = auxiliaryTracks.get(i);
      int trackType;
      switch (track.format.auxiliaryTrackType) {
        case C.AUXILIARY_TRACK_TYPE_ORIGINAL:
          trackType = 0;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR:
          trackType = 1;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE:
          trackType = 2;
          break;
        case C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA:
          trackType = 3;
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported auxiliary track type " + track.format.auxiliaryTrackType);
      }
      data[i + 2] = (byte) trackType;
    }
    return new MdtaMetadataEntry(
        MdtaMetadataEntry.KEY_AUXILIARY_TRACKS_MAP,
        data,
        MdtaMetadataEntry.TYPE_INDICATOR_RESERVED);
  }

  private static boolean isMdtaMetadataEntrySupported(MdtaMetadataEntry mdtaMetadataEntry) {
    return mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING
        || mdtaMetadataEntry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32;
  }

  private static boolean isMp4TimestampDataSupported(Mp4TimestampData timestampData) {
    return timestampData.creationTimestampSeconds <= UNSIGNED_INT_MAX_VALUE
        && timestampData.modificationTimestampSeconds <= UNSIGNED_INT_MAX_VALUE;
  }

  private static void writeImageDataToOutput(
      ByteBuffer imageData,
      long imagePresentationTimestampUs,
      String imageMimeType,
      long videoSize,
      String videoContainerMimeType,
      WritableByteChannel outputChannel)
      throws IOException {
    int indexForNewApp1Segment = findIndexForNewApp1Segment(imageData);
    // Write image data till start on new APP1 segment.
    int imageStartIndex = imageData.position();
    int imageEndIndex = imageData.limit();
    imageData.limit(indexForNewApp1Segment);
    outputChannel.write(imageData);
    // Restore imageData indexes.
    imageData.position(imageStartIndex);
    imageData.limit(imageEndIndex);
    // Insert new APP1 segment.
    byte[] motionPhotoXmp =
        generateMotionPhotoXmp(
            imagePresentationTimestampUs, imageMimeType, videoContainerMimeType, videoSize);
    ByteBuffer app1SegmentWithMotionPhotoXmp = getApp1SegmentWithMotionPhotoXmpDate(motionPhotoXmp);
    outputChannel.write(app1SegmentWithMotionPhotoXmp);
    // Write image data after the new APP1 segment index.
    imageData.position(indexForNewApp1Segment);
    outputChannel.write(imageData);
  }

  private static int findIndexForNewApp1Segment(ByteBuffer imageData) {
    imageData.mark();
    short marker = imageData.getShort();
    checkArgument(marker == SOI_MARKER, "SOI marker not found");
    // The new APP1 segment can be placed at the start if APP0 or APP1 segments are not present.
    int indexForNewApp1Segment = imageData.position();
    while (imageData.remaining() > SEGMENT_MARKER_LENGTH) {
      marker = imageData.getShort();
      if (marker == SOS_MARKER || marker == EOI_MARKER) {
        break;
      }
      // Segment length includes the 2 bytes for the length itself, so we need to subtract it to
      // get the length of the segment data.
      int segmentLength = imageData.getShort() - SEGMENT_SIZE_LENGTH;
      // The new APP1 segment can be placed after existing APP0 and APP1 segments.
      if (marker == APP0_MARKER || marker == APP1_MARKER) {
        indexForNewApp1Segment = imageData.position() + segmentLength;
      }
      // Move to the end of the current segment, to read the next marker.
      imageData.position(imageData.position() + segmentLength);
    }
    imageData.reset();
    return indexForNewApp1Segment;
  }

  private static byte[] generateMotionPhotoXmp(
      long imagePresentationTimestampUs,
      String imageMimeType,
      String videoContainerMimeType,
      long videoSize) {
    String motionPhotoXmp =
        String.format(
            Locale.US,
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n"
                + "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "    <rdf:Description rdf:about=\"\"\n"
                + "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n"
                + "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
                + "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n"
                + "      GCamera:MotionPhoto=\"1\"\n"
                + "      GCamera:MotionPhotoVersion=\"1\"\n"
                + "      GCamera:MotionPhotoPresentationTimestampUs=\"%d\">\n"
                + "        <Container:Directory>\n"
                + "          <rdf:Seq>\n"
                + "            <rdf:li rdf:parseType=\"Resource\">\n"
                + "              <Container:Item\n"
                + "                Item:Mime=\"%s\"\n"
                + "                Item:Semantic=\"Primary\"\n"
                + "                Item:Length=\"0\"\n"
                + "                Item:Padding=\"0\"/>\n"
                + "            </rdf:li>\n"
                + "            <rdf:li rdf:parseType=\"Resource\">\n"
                + "              <Container:Item\n"
                + "                Item:Mime=\"%s\"\n"
                + "                Item:Semantic=\"MotionPhoto\"\n"
                + "                Item:Length=\"%d\"\n"
                + "                Item:Padding=\"0\"/>\n"
                + "            </rdf:li>\n"
                + "          </rdf:Seq>\n"
                + "        </Container:Directory>\n"
                + "      </rdf:Description>\n"
                + "    </rdf:RDF>\n"
                + "  </x:xmpmeta>\n",
            imagePresentationTimestampUs,
            imageMimeType,
            videoContainerMimeType,
            videoSize);
    return Util.getUtf8Bytes(motionPhotoXmp);
  }

  private static ByteBuffer getApp1SegmentWithMotionPhotoXmpDate(byte[] motionPhotoXmp) {
    short totalSegmentLength =
        (short) (SEGMENT_SIZE_LENGTH + JPEG_XMP_IDENTIFIER.length() + motionPhotoXmp.length);
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(SEGMENT_MARKER_LENGTH + totalSegmentLength);
    byteBuffer.putShort(APP1_MARKER);
    byteBuffer.putShort(totalSegmentLength);
    byteBuffer.put(Util.getUtf8Bytes(JPEG_XMP_IDENTIFIER));
    byteBuffer.put(motionPhotoXmp);
    byteBuffer.flip();
    return byteBuffer;
  }
}
