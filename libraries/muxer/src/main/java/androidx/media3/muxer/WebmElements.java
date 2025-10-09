/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static androidx.media3.common.util.CodecSpecificDataUtil.getVorbisInitializationData;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.muxer.WebmConstants.MkvEbmlElement;
import androidx.media3.muxer.WebmConstants.TrackNumber;
import androidx.media3.muxer.WebmConstants.TrackType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines WebM elements ids as per the <a href="https://datatracker.ietf.org/doc/rfc9559/">WebM
 * specification</a>.
 *
 * <p>See also: <a href="https://datatracker.ietf.org/doc/rfc8794/">EBML element types of RFC
 * 8794</a>
 */
/* package */ final class WebmElements {

  private static final int MAX_CHROMATICITY = 50_000; // For HDR metadata scaling.
  private static final int TIMESTAMP_SCALE = 1_000_000;

  private WebmElements() {}

  /** Creates an EBML Unsigned Integer element. */
  public static ByteBuffer createUnsignedIntElement(@MkvEbmlElement long elementId, long value) {
    ByteBuffer valueBytes = uintToMinimumLengthByteBuffer(value);
    return wrapIntoElement(elementId, valueBytes);
  }

  /** Creates an EBML Float element. */
  public static ByteBuffer createFloatElement(@MkvEbmlElement long elementId, float value) {
    ByteBuffer valueBytes = ByteBuffer.wrap(Util.toByteArray(value));
    return wrapIntoElement(elementId, valueBytes);
  }

  /** Creates an EBML UTF-8 String element. */
  public static ByteBuffer createStringElement(@MkvEbmlElement long elementId, String value) {
    ByteBuffer valueBytes = ByteBuffer.wrap(Util.getUtf8Bytes(value));
    return wrapIntoElement(elementId, valueBytes);
  }

  /** Creates an EBML SimpleBlock element. */
  public static ByteBuffer createSimpleBlockElement(
      int trackNumber, long timestampTicks, boolean keyFrame, ByteBuffer frameData) {
    // Construct the SimpleBlock payload:
    // TrackNumber (EBML VINT) + Timestamp (2 bytes) + Flags (1 byte) + FrameData.
    ByteBuffer trackNumberVintBytes = EbmlUtils.encodeVInt(trackNumber);
    byte timestampFirstByte = (byte) ((timestampTicks >> 8) & 0xFF);
    byte timestampSecondByte = (byte) (timestampTicks & 0xFF);
    byte flags = (byte) (keyFrame ? 0x80 : 0x00);
    int payloadSize =
        trackNumberVintBytes.remaining()
            + 2 // for timestamp bytes
            + 1 // for flags byte
            + frameData.remaining();

    ByteBuffer simpleBlockPayload = ByteBuffer.allocate(payloadSize);
    simpleBlockPayload.put(trackNumberVintBytes);
    simpleBlockPayload.put(timestampFirstByte);
    simpleBlockPayload.put(timestampSecondByte);
    simpleBlockPayload.put(flags);
    simpleBlockPayload.put(frameData);
    simpleBlockPayload.flip();

    return wrapIntoElement(MkvEbmlElement.SIMPLE_BLOCK, simpleBlockPayload);
  }

  /** Creates the EBML header for the WebM file. */
  public static ByteBuffer createEbmlHeaderElement() {
    List<ByteBuffer> headerElements = new ArrayList<>();
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.EBML_VERSION, 1));
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.EBML_READ_VERSION, 1));
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.EBML_MAX_ID_LENGTH, 4));
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.EBML_MAX_SIZE_LENGTH, 8));
    headerElements.add(createStringElement(MkvEbmlElement.DOC_TYPE, "webm"));
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.DOC_TYPE_VERSION, 2));
    headerElements.add(createUnsignedIntElement(MkvEbmlElement.DOC_TYPE_READ_VERSION, 2));
    return wrapIntoElement(MkvEbmlElement.EBML, headerElements);
  }

  /**
   * Creates the SeekHead element.
   *
   * <p>The SeekHead contains pointers to other top-level elements like Info, Tracks, and Cues.
   *
   * @param infoPosition The position of the Info element.
   * @param tracksPosition The position of the Tracks element.
   * @param cuePosition The position of the Cues element.
   * @return A {@link ByteBuffer} containing the SeekHead element.
   */
  public static ByteBuffer createSeekHeadElement(
      long infoPosition, long tracksPosition, long cuePosition) {

    List<ByteBuffer> seekEntries = new ArrayList<>();

    List<ByteBuffer> infoSeekEntry = new ArrayList<>();
    infoSeekEntry.add(
        createUnsignedIntElement(
            MkvEbmlElement.SEEK_ID,
            // INFO is the EBML ID for the Info element.
            MkvEbmlElement.INFO));
    infoSeekEntry.add(createUnsignedIntElement(MkvEbmlElement.SEEK_POSITION, infoPosition));
    seekEntries.add(wrapIntoElement(MkvEbmlElement.SEEK, infoSeekEntry));

    // Seek entry for Tracks element.
    List<ByteBuffer> tracksSeekEntry = new ArrayList<>();
    tracksSeekEntry.add(
        createUnsignedIntElement(
            MkvEbmlElement.SEEK_ID,
            // TRACKS is the EBML ID for the Tracks element.
            MkvEbmlElement.TRACKS));
    tracksSeekEntry.add(createUnsignedIntElement(MkvEbmlElement.SEEK_POSITION, tracksPosition));
    seekEntries.add(wrapIntoElement(MkvEbmlElement.SEEK, tracksSeekEntry));

    // Seek entry for Cue element.
    List<ByteBuffer> cueSeekEntry = new ArrayList<>();
    cueSeekEntry.add(
        createUnsignedIntElement(
            MkvEbmlElement.SEEK_ID,
            // CUES is the EBML ID for the Cues element.
            MkvEbmlElement.CUES));
    cueSeekEntry.add(createUnsignedIntElement(MkvEbmlElement.SEEK_POSITION, cuePosition));
    seekEntries.add(wrapIntoElement(MkvEbmlElement.SEEK, cueSeekEntry));

    return wrapIntoElement(MkvEbmlElement.SEEK_HEAD, seekEntries);
  }

  /**
   * Creates a void-like element of a specific total size.
   *
   * <p>This is used for placeholders (like SeekHead) or padding (Void). The data part of the
   * element will be all zeros.
   *
   * @param totalSize The total desired size of the element, including ID and size fields.
   * @return A {@link ByteBuffer} containing the Void element.
   */
  public static ByteBuffer createVoidElement(int totalSize) {
    ByteBuffer idBytes = uintToMinimumLengthByteBuffer(MkvEbmlElement.VOID);
    int idLength = idBytes.remaining();
    // The total size should be at least 2 bytes, since the ID and size fields are at least 1 byte
    // each.
    checkArgument(totalSize >= 2);

    int dataSizeVintLength;

    if (totalSize < 9) {
      // If the totalSize is less than 9, then the 1 byte should be enough to write data size field
      // as VINT.
      dataSizeVintLength = 1;
    } else {
      // If the totalSize is greater than 9, then the 8 bytes should be enough to write data size
      // field as VINT.
      dataSizeVintLength = 8;
    }
    int dataSize = totalSize - idLength - dataSizeVintLength;

    ByteBuffer element = ByteBuffer.allocate(totalSize);
    element.put(idBytes);
    element.put(EbmlUtils.encodeVIntWithWidth(dataSize, /* width= */ dataSizeVintLength));
    // The remaining bytes are already zeroed out by ByteBuffer.allocate().
    element.position(element.position() + dataSize);
    element.flip();
    return element;
  }

  /** Creates the Info element, which contains metadata about the segment. */
  public static ByteBuffer createInfoElement(float segmentDuration) {
    List<ByteBuffer> infoBuffer = new ArrayList<>();
    infoBuffer.add(createFloatElement(MkvEbmlElement.SEGMENT_DURATION, segmentDuration));
    infoBuffer.add(createUnsignedIntElement(MkvEbmlElement.TIMESTAMP_SCALE, TIMESTAMP_SCALE));

    infoBuffer.add(createStringElement(MkvEbmlElement.MUXING_APP, "android"));
    infoBuffer.add(createStringElement(MkvEbmlElement.WRITING_APP, "android"));
    return wrapIntoElement(MkvEbmlElement.INFO, infoBuffer);
  }

  /**
   * Creates a CuePoint element.
   *
   * <p>CuePoints are used to allow seeking to keyframes within the file.
   *
   * @param timestampTicks The presentation timestamp of the keyframe, in SegmentTicks.
   * @param trackNumber The track number of the keyframe.
   * @param clusterOffset The offset of the cluster containing the keyframe, relative to the start
   *     of the segment data.
   * @return A {@link ByteBuffer} containing the CuePoint element.
   */
  public static ByteBuffer createCuePointElement(
      long timestampTicks, int trackNumber, long clusterOffset) {
    List<ByteBuffer> cuePointElements = new ArrayList<>();
    cuePointElements.add(createUnsignedIntElement(MkvEbmlElement.CUE_TIME, timestampTicks));

    List<ByteBuffer> cueTrackPositionsElements = new ArrayList<>();
    cueTrackPositionsElements.add(createUnsignedIntElement(MkvEbmlElement.CUE_TRACK, trackNumber));
    cueTrackPositionsElements.add(
        createUnsignedIntElement(MkvEbmlElement.CUE_CLUSTER_POSITION, clusterOffset));

    cuePointElements.add(
        wrapIntoElement(MkvEbmlElement.CUE_TRACK_POSITIONS, cueTrackPositionsElements));

    return wrapIntoElement(MkvEbmlElement.CUE_POINT, cuePointElements);
  }

  public static ByteBuffer createTrackElements(List<Track> trackList) {
    ArrayList<ByteBuffer> trackEntries = new ArrayList<>();
    for (int i = 0; i < trackList.size(); i++) {
      Track track = trackList.get(i);
      @C.TrackType int trackType = MimeTypes.getTrackType(track.format.sampleMimeType);
      switch (trackType) {
        case C.TRACK_TYPE_AUDIO:
          ByteBuffer audioTrackEntry = createAudioTrackEntryElement(track.id, track.format);
          trackEntries.add(audioTrackEntry);
          break;
        case C.TRACK_TYPE_VIDEO:
          ByteBuffer videoTrackEntry = createVideoTrackEntryElement(track.id, track.format);
          trackEntries.add(videoTrackEntry);
          break;
        default:
          // canAddTrack has already validated that the track is audio or video. Shouldn't reach
          // here.
          throw new IllegalArgumentException(
              String.format(
                  "Track MimeType %s is not supported in WebM.", track.format.sampleMimeType));
      }
    }
    return wrapIntoElement(MkvEbmlElement.TRACKS, trackEntries);
  }

  /**
   * Creates a TrackEntry element for an audio track.
   *
   * @param trackId The track ID.
   * @param format The {@link Format} of the audio track.
   * @return A {@link ByteBuffer} containing the audio TrackEntry element.
   */
  private static ByteBuffer createAudioTrackEntryElement(int trackId, Format format) {
    List<ByteBuffer> audioTrackEntry =
        getCommonTrackEntry(TrackNumber.AUDIO, trackId, TrackType.AUDIO, format);
    List<ByteBuffer> audioInfoBuffers = new ArrayList<>();
    audioInfoBuffers.add(createUnsignedIntElement(MkvEbmlElement.CHANNELS, format.channelCount));
    audioInfoBuffers.add(createFloatElement(MkvEbmlElement.SAMPLING_FREQUENCY, format.sampleRate));
    audioInfoBuffers.add(createFloatElement(MkvEbmlElement.BIT_DEPTH, format.pcmEncoding));
    boolean isVorbis = checkNotNull(format.sampleMimeType).equals(MimeTypes.AUDIO_VORBIS);
    ByteBuffer csdByteBuffer;
    if (isVorbis) {
      csdByteBuffer = getVorbisInitializationData(format);
    } else {
      csdByteBuffer = ByteBuffer.wrap(format.initializationData.get(0));
    }
    ByteBuffer audioInfo = wrapIntoElement(MkvEbmlElement.AUDIO, audioInfoBuffers);
    ByteBuffer csd = wrapIntoElement(MkvEbmlElement.CODEC_PRIVATE, csdByteBuffer);
    audioTrackEntry.add(audioInfo);
    audioTrackEntry.add(csd);
    return wrapIntoElement(MkvEbmlElement.TRACK_ENTRY, audioTrackEntry);
  }

  /**
   * Returns a list of common track entries for a track element.
   *
   * @param trackNumber The track number as used in the Block Header.
   * @param uid A UID that identifies the Track.
   * @param trackType The {@link TrackType type}.
   * @param format The {@link Format} of the track.
   * @return A list of {@link ByteBuffer}s with the common track entries.
   */
  private static List<ByteBuffer> getCommonTrackEntry(
      @TrackNumber int trackNumber, int uid, @TrackType int trackType, Format format) {
    List<ByteBuffer> trackEntry = new ArrayList<>();
    trackEntry.add(createUnsignedIntElement(MkvEbmlElement.TRACK_NUMBER, trackNumber));
    trackEntry.add(createUnsignedIntElement(MkvEbmlElement.TRACK_UID, uid));
    trackEntry.add(createUnsignedIntElement(MkvEbmlElement.FLAG_LACING, /* false */ 0));
    trackEntry.add(createStringElement(MkvEbmlElement.LANGUAGE, checkNotNull(format.language)));
    trackEntry.add(
        createStringElement(
            MkvEbmlElement.CODEC_ID, getCodecId(checkNotNull(format.sampleMimeType))));
    trackEntry.add(createUnsignedIntElement(MkvEbmlElement.TRACK_TYPE, trackType));
    return trackEntry;
  }

  /** Returns the WebM codec ID for the given {@link MimeTypes MIME type}. */
  private static String getCodecId(String mimeType) {
    switch (mimeType) {
      case MimeTypes.VIDEO_VP8:
        return "V_VP8";
      case MimeTypes.VIDEO_VP9:
        return "V_VP9";
      case MimeTypes.AUDIO_OPUS:
        return "A_OPUS";
      case MimeTypes.AUDIO_VORBIS:
        return "A_VORBIS";
      default:
        throw new IllegalArgumentException("Unsupported mime type: " + mimeType);
    }
  }

  /**
   * Creates a TrackEntry element for a video track.
   *
   * @param trackId The track ID.
   * @param format The {@link Format} of the video track.
   * @return A {@link ByteBuffer} containing the video TrackEntry element.
   */
  private static ByteBuffer createVideoTrackEntryElement(int trackId, Format format) {
    List<ByteBuffer> videoTrackEntry =
        getCommonTrackEntry(TrackNumber.VIDEO, trackId, TrackType.VIDEO, format);
    checkNotNull(format.sampleMimeType);
    // VP8 does not have codec private data.
    if (!format.initializationData.isEmpty()) {
      videoTrackEntry.add(
          wrapIntoElement(
              MkvEbmlElement.CODEC_PRIVATE, ByteBuffer.wrap(format.initializationData.get(0))));
    }
    videoTrackEntry.add(createVideoElement(format));
    return wrapIntoElement(MkvEbmlElement.TRACK_ENTRY, videoTrackEntry);
  }

  /**
   * Creates a Video element, which contains video-specific metadata like width and height.
   *
   * @param format The {@link Format} of the video track.
   * @return A {@link ByteBuffer} containing the Video element.
   */
  private static ByteBuffer createVideoElement(Format format) {
    List<ByteBuffer> videoInfoBuffer = new ArrayList<>();
    videoInfoBuffer.add(createUnsignedIntElement(MkvEbmlElement.PIXEL_WIDTH, format.width));
    videoInfoBuffer.add(createUnsignedIntElement(MkvEbmlElement.PIXEL_HEIGHT, format.height));
    if (format.colorInfo != null) {
      videoInfoBuffer.add(createColorElement(format.colorInfo));
    }
    return wrapIntoElement(MkvEbmlElement.VIDEO, videoInfoBuffer);
  }

  /**
   * Creates a Colour element from the given {@link ColorInfo}.
   *
   * @param colorInfo The {@link ColorInfo} of the video track.
   * @return A {@link ByteBuffer} containing the Colour element.
   */
  private static ByteBuffer createColorElement(ColorInfo colorInfo) {
    List<ByteBuffer> colorInfoBuffers = new ArrayList<>();
    int colorPrimaries = ColorInfo.colorSpaceToIsoColorPrimaries(colorInfo.colorSpace);
    colorInfoBuffers.add(createUnsignedIntElement(MkvEbmlElement.PRIMARIES, colorPrimaries));
    int transferCharacteristics =
        ColorInfo.colorTransferToIsoTransferCharacteristics(colorInfo.colorTransfer);
    colorInfoBuffers.add(
        createUnsignedIntElement(MkvEbmlElement.TRANSFER_CHARACTERISTICS, transferCharacteristics));
    int matrixCoefficients = ColorInfo.colorSpaceToIsoMatrixCoefficients(colorInfo.colorSpace);
    colorInfoBuffers.add(
        createUnsignedIntElement(MkvEbmlElement.MATRIX_COEFFICIENTS, matrixCoefficients));
    int colorRange = colorInfo.colorRange;
    colorInfoBuffers.add(createUnsignedIntElement(MkvEbmlElement.RANGE, colorRange));
    // HDR info is according to CTA-861-3.
    byte[] hdrStaticInfoData = colorInfo.hdrStaticInfo;
    if (hdrStaticInfoData != null && hdrStaticInfoData.length == 25) {
      ByteBuffer rawHdrData = ByteBuffer.wrap(hdrStaticInfoData).order(ByteOrder.LITTLE_ENDIAN);
      byte type = rawHdrData.get(); // type byte
      if (type == 0) {
        // convert HDRStaticInfo values to matroska equivalent values.
        List<ByteBuffer> masteringMetadataElements = new ArrayList<>();
        // Chromaticity values are scaled by 50000 in ST 2086.
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_R_CHROMATICITY_X,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_R_CHROMATICITY_Y,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_G_CHROMATICITY_X,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_G_CHROMATICITY_Y,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_B_CHROMATICITY_X,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.PRIMARY_B_CHROMATICITY_Y,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.WHITE_POINT_CHROMATICITY_X,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.WHITE_POINT_CHROMATICITY_Y,
                rawHdrData.getShort() / (float) MAX_CHROMATICITY));
        // Luminance values are direct
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.LUMINANCE_MAX, rawHdrData.getShort())); // Max Mastering Luminance.
        // HDRStaticInfo Type1 stores min luminance scaled 10000:1.
        masteringMetadataElements.add(
            createFloatElement(
                MkvEbmlElement.LUMINANCE_MIN,
                rawHdrData.getShort() * 0.0001f)); // Min Mastering Luminance.
        int maxContentLuminance = rawHdrData.getShort();
        int maxFrameAverageLuminance = rawHdrData.getShort();

        masteringMetadataElements.add(
            createUnsignedIntElement(MkvEbmlElement.MAX_CLL, maxContentLuminance));
        masteringMetadataElements.add(
            createUnsignedIntElement(MkvEbmlElement.MAX_FALL, maxFrameAverageLuminance));
        colorInfoBuffers.add(
            wrapIntoElement(MkvEbmlElement.MASTERING_METADATA, masteringMetadataElements));
      }
    }
    return wrapIntoElement(MkvEbmlElement.COLOUR, colorInfoBuffers);
  }

  /**
   * Serializes an unsigned long value to a {@link ByteBuffer} with minimum length.
   *
   * @param value The value to serialize.
   * @return A {@link ByteBuffer} containing the serialized value.
   */
  public static ByteBuffer uintToMinimumLengthByteBuffer(long value) {
    int unsignedIntegerLength;
    if (value == 0) {
      unsignedIntegerLength = 1;
    } else {
      // Number of bits needed is (64 - leading zeros). Bytes is ceil(bits/8).
      unsignedIntegerLength = (64 - Long.numberOfLeadingZeros(value) + 7) / 8;
    }
    byte[] bytes = new byte[unsignedIntegerLength];
    for (int i = unsignedIntegerLength - 1; i >= 0; i--) {
      bytes[i] = (byte) (value & 0xff);
      value >>>= 8;
    }
    return ByteBuffer.wrap(bytes);
  }

  /**
   * Wraps an EBML ID and a list of child ByteBuffers into a single EBML element.
   *
   * <p>Format: ID (VINT) + Size of children (VINT) + Children data.
   *
   * @param elementId The EBML ID of the element.
   * @param children The list of {@link ByteBuffer} containing the child elements.
   * @return A {@link ByteBuffer} containing the serialized element.
   */
  public static ByteBuffer wrapIntoElement(
      @MkvEbmlElement long elementId, List<ByteBuffer> children) {
    int childrenTotalSize = 0;
    for (ByteBuffer child : children) {
      childrenTotalSize += child.remaining();
    }

    ByteBuffer id = uintToMinimumLengthByteBuffer(elementId);
    ByteBuffer vintContentSize = EbmlUtils.encodeVInt(childrenTotalSize);
    int size = id.remaining() + vintContentSize.remaining() + childrenTotalSize;
    ByteBuffer element = ByteBuffer.allocate(size);

    element.put(id);
    element.put(vintContentSize);
    for (ByteBuffer child : children) {
      element.put(child);
    }
    element.flip();
    return element;
  }

  /**
   * Wraps an EBML ID and content into an EBML element.
   *
   * <p>Format :
   *
   * <p>ID (VINT) + Size of content (VINT) + Content(actual data).
   *
   * @param elementId The EBML ID of the element.
   * @param content The content of the element.
   * @return A {@link ByteBuffer} containing the serialized element.
   */
  private static ByteBuffer wrapIntoElement(@MkvEbmlElement long elementId, ByteBuffer content) {
    return wrapIntoElement(elementId, Collections.singletonList(content));
  }
}
