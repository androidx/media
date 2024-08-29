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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.muxer.ColorUtils.MEDIAFORMAT_STANDARD_TO_PRIMARIES_AND_MATRIX;
import static androidx.media3.muxer.ColorUtils.MEDIAFORMAT_TRANSFER_TO_MP4_TRANSFER;
import static androidx.media3.muxer.Mp4Utils.UNSIGNED_INT_MAX_VALUE;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.muxer.FragmentedMp4Writer.SampleMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Bytes;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Writes out various types of boxes as per MP4 (ISO/IEC 14496-12) standards.
 *
 * <p>Boxes do not construct their sub-boxes but take them as input {@linkplain ByteBuffer byte
 * buffers}.
 */
/* package */ final class Boxes {
  /* Total number of bytes in an integer. */
  private static final int BYTES_PER_INTEGER = 4;

  // Box size (4 bytes) + Box name (4 bytes)
  public static final int BOX_HEADER_SIZE = 2 * BYTES_PER_INTEGER;

  public static final int MFHD_BOX_CONTENT_SIZE = 2 * BYTES_PER_INTEGER;

  public static final int TFHD_BOX_CONTENT_SIZE = 4 * BYTES_PER_INTEGER;

  /**
   * The maximum length of boxes which have fixed sizes.
   *
   * <p>Technically, we'd know how long they actually are; this upper bound is much simpler to
   * produce though and we'll throw if we overflow anyway.
   */
  private static final int MAX_FIXED_LEAF_BOX_SIZE = 200;

  /**
   * The per-video timebase, used for durations in MVHD and TKHD even if the per-track timebase is
   * different (e.g. typically the sample rate for audio).
   */
  private static final long MVHD_TIMEBASE = 10_000L;

  // unsigned int(2) sample_depends_on = 2 (bit index 25 and 24)
  private static final int TRUN_BOX_SYNC_SAMPLE_FLAGS = 0b00000010_00000000_00000000_00000000;
  // unsigned int(2) sample_depends_on = 1 (bit index 25 and 24)
  // bit(1) sample_is_non_sync_sample = 1 (bit index 16)
  private static final int TRUN_BOX_NON_SYNC_SAMPLE_FLAGS = 0b00000001_00000001_00000000_00000000;

  private Boxes() {}

  public static final ImmutableList<Byte> XMP_UUID =
      ImmutableList.of(
          (byte) 0xBE,
          (byte) 0x7A,
          (byte) 0xCF,
          (byte) 0xCB,
          (byte) 0x97,
          (byte) 0xA9,
          (byte) 0x42,
          (byte) 0xE8,
          (byte) 0x9C,
          (byte) 0x71,
          (byte) 0x99,
          (byte) 0x94,
          (byte) 0x91,
          (byte) 0xE3,
          (byte) 0xAF,
          (byte) 0xAC);

  /**
   * Returns the tkhd box.
   *
   * <p>This is a per-track header box.
   */
  public static ByteBuffer tkhd(
      int trackId,
      long trackDurationUs,
      int creationTimestampSeconds,
      int modificationTimestampSeconds,
      int orientation,
      Format format) {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x00000007); // version and flags; allow presentation, etc.

    contents.putInt(creationTimestampSeconds); // creation_time; unsigned int(32)
    contents.putInt(modificationTimestampSeconds); // modification_time; unsigned int(32)

    contents.putInt(trackId);
    contents.putInt(0); // reserved

    // Using the time base of the entire file, not that of the track; otherwise,
    // Quicktime will stretch the audio accordingly, see b/158120042.
    int trackDurationVu = (int) vuFromUs(trackDurationUs, MVHD_TIMEBASE);
    contents.putInt(trackDurationVu);

    contents.putInt(0); // reserved
    contents.putInt(0); // reserved

    contents.putInt(0); // layer = 0 and alternate_group = 0
    contents.putShort(MimeTypes.isAudio(format.sampleMimeType) ? (short) 0x0100 : 0); // volume
    contents.putShort((short) 0); // reserved

    contents.put(rotationMatrixFromOrientation(orientation));

    int width = format.width != Format.NO_VALUE ? format.width : 0;
    int height = format.height != Format.NO_VALUE ? format.height : 0;

    contents.putInt(width << 16);
    contents.putInt(height << 16);

    contents.flip();
    return BoxUtils.wrapIntoBox("tkhd", contents);
  }

  /**
   * Returns the mvhd box.
   *
   * <p>This is the movie header for the entire MP4 file.
   */
  public static ByteBuffer mvhd(
      int nextEmptyTrackId,
      int creationTimestampSeconds,
      int modificationTimestampSeconds,
      long videoDurationUs) {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0); // version and flags

    contents.putInt(creationTimestampSeconds); // creation_time; unsigned int(32)
    contents.putInt(modificationTimestampSeconds); // modification_time; unsigned int(32)
    contents.putInt((int) MVHD_TIMEBASE); // The per-track timescales might be different.
    contents.putInt(
        (int) vuFromUs(videoDurationUs, MVHD_TIMEBASE)); // Duration of the entire video.
    contents.putInt(0x00010000); // rate = 1.0
    contents.putShort((short) 0x0100); // volume = full volume
    contents.putShort((short) 0); // reserved

    contents.putInt(0); // reserved
    contents.putInt(0); // reserved

    // Default values (unity matrix). It looks like that this needs to be an identity matrix, since
    // some players will apply both this and the per-track transformation, while some only go with
    // the per-track one.
    int[] matrix = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
    for (int i = 0; i < matrix.length; i++) {
      contents.putInt(matrix[i]);
    }

    for (int i = 0; i < 6; i++) {
      contents.putInt(0); // pre_defined
    }

    // Next empty track id.
    contents.putInt(nextEmptyTrackId);

    contents.flip();
    return BoxUtils.wrapIntoBox("mvhd", contents);
  }

  /**
   * Returns the mdhd box.
   *
   * <p>This is a per-track (media) header.
   */
  public static ByteBuffer mdhd(
      long trackDurationVu,
      int videoUnitTimebase,
      int creationTimestampSeconds,
      int modificationTimestampSeconds,
      @Nullable String languageCode) {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x0); // version and flags

    contents.putInt(creationTimestampSeconds); // creation_time; unsigned int(32)
    contents.putInt(modificationTimestampSeconds); // modification_time; unsigned int(32)

    contents.putInt(videoUnitTimebase);

    contents.putInt((int) trackDurationVu);

    contents.putShort(languageCodeFromString(languageCode));
    contents.putShort((short) 0);

    contents.flip();
    return BoxUtils.wrapIntoBox("mdhd", contents);
  }

  /**
   * Returns the vmhd box.
   *
   * <p>This is a header for video tracks.
   */
  public static ByteBuffer vmhd() {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x0); // version and flags

    contents.putShort((short) 0); // graphicsmode
    // opcolor (red, green, blue)
    contents.putShort((short) 0);
    contents.putShort((short) 0);
    contents.putShort((short) 0);

    contents.flip();
    return BoxUtils.wrapIntoBox("vmhd", contents);
  }

  /**
   * Returns the smhd box.
   *
   * <p>This is a header for audio tracks.
   */
  public static ByteBuffer smhd() {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x0); // version and flags

    contents.putShort((short) 0); // balance
    contents.putShort((short) 0); // reserved

    contents.flip();
    return BoxUtils.wrapIntoBox("smhd", contents);
  }

  /**
   * Returns the nmhd box.
   *
   * <p>This is a header for metadata tracks.
   */
  public static ByteBuffer nmhd() {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x0); // version and flags

    contents.flip();
    return BoxUtils.wrapIntoBox("nmhd", contents);
  }

  /**
   * Returns a text metadata sample entry box as per ISO/IEC 14496-12: 8.5.2.2.
   *
   * <p>This contains the sample entry (to be placed within the sample description box) for the text
   * metadata tracks.
   */
  public static ByteBuffer textMetaDataSampleEntry(Format format) {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    String mimeType = checkNotNull(format.sampleMimeType);
    byte[] mimeBytes = Util.getUtf8Bytes(mimeType);
    contents.put(mimeBytes); // content_encoding
    contents.put((byte) 0x00);
    contents.put(mimeBytes); // mime_format
    contents.put((byte) 0x00);

    contents.flip();
    return BoxUtils.wrapIntoBox("mett", contents);
  }

  /** Returns the minf (media info) box. */
  public static ByteBuffer minf(ByteBuffer... subBoxes) {
    return BoxUtils.wrapBoxesIntoBox("minf", Arrays.asList(subBoxes));
  }

  /** Returns the dref (data references) box. */
  public static ByteBuffer dref(ByteBuffer... dataLocationBoxes) {
    // We have a "number of contained boxes" field; let's pretend this is also a box so that
    // wrapBoxesIntoBoxes() can concatenate it with the rest.
    ByteBuffer header = ByteBuffer.allocate(8);
    header.putInt(0);
    header.putInt(dataLocationBoxes.length);
    header.flip();

    List<ByteBuffer> contents = new ArrayList<>();
    contents.add(header);
    Collections.addAll(contents, dataLocationBoxes);

    return BoxUtils.wrapBoxesIntoBox("dref", contents);
  }

  /** Returns the dinf (data information) box. */
  public static ByteBuffer dinf(ByteBuffer dref) {
    return BoxUtils.wrapIntoBox("dinf", dref);
  }

  /**
   * Returns the url box.
   *
   * <p>This box declares the location of media data (whether it is in this file or in some other
   * remote file).
   */
  public static ByteBuffer localUrl() {
    ByteBuffer contents = ByteBuffer.allocate(4);

    // Flag indicating that the data is in fact in this very file instead of a remote
    // URL. Accordingly, no actual URL string is present.
    contents.putInt(1);

    // Since we set the flag to 1, no actual URL needs to follow.

    contents.flip();
    return BoxUtils.wrapIntoBox("url ", contents);
  }

  /**
   * Returns the hdlr box.
   *
   * <p>This box includes tha handler specification for a track (signals whether this is video,
   * audio or metadata).
   *
   * @param handlerType The handle type, as defined in ISO/IEC 14496-12: 8.4.3.3.
   * @param handlerName The handler name, a human-readable name to identify track type for debugging
   *     and inspection purposes.
   * @return {@link ByteBuffer} containing the hdlr box.
   */
  public static ByteBuffer hdlr(String handlerType, String handlerName) {
    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x0); // version and flags.
    contents.putInt(0); // pre_defined.
    contents.put(Util.getUtf8Bytes(handlerType)); // handler_type.
    contents.putInt(0); // reserved.
    contents.putInt(0); // reserved.
    contents.putInt(0); // reserved.
    contents.put(Util.getUtf8Bytes(handlerName)); // name.
    contents.put((byte) 0); // The null terminator for name.

    contents.flip();
    return BoxUtils.wrapIntoBox("hdlr", contents);
  }

  /**
   * Returns the mdia box.
   *
   * <p>This box describes the media format of a track.
   */
  public static ByteBuffer mdia(ByteBuffer... subBoxes) {
    return BoxUtils.wrapBoxesIntoBox("mdia", Arrays.asList(subBoxes));
  }

  /**
   * Returns the trak box.
   *
   * <p>This is a top level track descriptor box; each track has one.
   */
  public static ByteBuffer trak(ByteBuffer... subBoxes) {
    return BoxUtils.wrapBoxesIntoBox("trak", Arrays.asList(subBoxes));
  }

  /**
   * Returns the udta box.
   *
   * <p>This box contains user data like location info.
   */
  public static ByteBuffer udta(@Nullable Mp4LocationData location) {
    // We can just omit the entire box if there is no location info available.
    if (location == null) {
      return ByteBuffer.allocate(0);
    }

    String locationString =
        Util.formatInvariant("%+.4f%+.4f/", location.latitude, location.longitude);

    ByteBuffer xyzBoxContents = ByteBuffer.allocate(locationString.length() + 2 + 2);
    xyzBoxContents.putShort((short) (xyzBoxContents.capacity() - 4));
    xyzBoxContents.putShort((short) 0x15C7); // language code?

    xyzBoxContents.put(Util.getUtf8Bytes(locationString));
    checkState(xyzBoxContents.limit() == xyzBoxContents.capacity());
    xyzBoxContents.flip();

    return BoxUtils.wrapIntoBox(
        "udta",
        BoxUtils.wrapIntoBox(
            new byte[] {
              (byte) 0xA9, // copyright symbol
              'x',
              'y',
              'z'
            },
            xyzBoxContents));
  }

  /**
   * Returns the keys box.
   *
   * <p>This box contains a list of metadata keys.
   */
  public static ByteBuffer keys(List<MdtaMetadataEntry> mdtaMetadataEntries) {
    int totalSizeToStoreKeys = 0;
    for (int i = 0; i < mdtaMetadataEntries.size(); i++) {
      // Add header size to wrap each key into a "mdta" box.
      totalSizeToStoreKeys += mdtaMetadataEntries.get(i).key.length() + BOX_HEADER_SIZE;
    }
    ByteBuffer contents = ByteBuffer.allocate(2 * BYTES_PER_INTEGER + totalSizeToStoreKeys);
    contents.putInt(0x0); // version and flags
    contents.putInt(mdtaMetadataEntries.size()); // Entry count

    for (int i = 0; i < mdtaMetadataEntries.size(); i++) {
      ByteBuffer keyNameBuffer = ByteBuffer.wrap(Util.getUtf8Bytes(mdtaMetadataEntries.get(i).key));
      contents.put(BoxUtils.wrapIntoBox("mdta", keyNameBuffer));
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("keys", contents);
  }

  /**
   * Returns the ilst box.
   *
   * <p>This box contains a list of metadata values.
   */
  public static ByteBuffer ilst(List<MdtaMetadataEntry> mdtaMetadataEntries) {
    int totalSizeToStoreValues = 0;
    for (int i = 0; i < mdtaMetadataEntries.size(); i++) {
      // Add additional 16 bytes for writing metadata associated to each value.
      // Add header size to wrap each value into a "data" box.
      totalSizeToStoreValues +=
          mdtaMetadataEntries.get(i).value.length + 4 * BYTES_PER_INTEGER + BOX_HEADER_SIZE;
    }

    ByteBuffer contents = ByteBuffer.allocate(totalSizeToStoreValues);

    for (int i = 0; i < mdtaMetadataEntries.size(); i++) {
      int keyId = i + 1;
      MdtaMetadataEntry currentMdtaMetadataEntry = mdtaMetadataEntries.get(i);

      ByteBuffer valueContents =
          ByteBuffer.allocate(2 * BYTES_PER_INTEGER + currentMdtaMetadataEntry.value.length);
      valueContents.putInt(currentMdtaMetadataEntry.typeIndicator);
      valueContents.putInt(currentMdtaMetadataEntry.localeIndicator);
      valueContents.put(currentMdtaMetadataEntry.value);

      valueContents.flip();
      ByteBuffer valueBox = BoxUtils.wrapIntoBox("data", valueContents);
      contents.putInt(valueBox.remaining() + BOX_HEADER_SIZE);
      contents.putInt(keyId);
      contents.put(valueBox);
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("ilst", contents);
  }

  /** Returns the meta (metadata) box. */
  public static ByteBuffer meta(ByteBuffer... subBoxes) {
    return BoxUtils.wrapBoxesIntoBox("meta", Arrays.asList(subBoxes));
  }

  /**
   * Returns the uuid box.
   *
   * <p>This box is used for XMP and other metadata.
   */
  public static ByteBuffer uuid(List<Byte> uuid, ByteBuffer contents) {
    checkArgument(contents.remaining() > 0);
    return BoxUtils.wrapBoxesIntoBox(
        "uuid", ImmutableList.of(ByteBuffer.wrap(Bytes.toArray(uuid)), contents));
  }

  /**
   * Returns the moov box.
   *
   * <p>This box is a top level movie descriptor box (there is a single one of this per Mp4 file).
   */
  public static ByteBuffer moov(
      ByteBuffer mvhdBox,
      ByteBuffer udtaBox,
      ByteBuffer metaBox,
      List<ByteBuffer> trakBoxes,
      ByteBuffer mvexBox) {
    List<ByteBuffer> subBoxes = new ArrayList<>();
    subBoxes.add(mvhdBox);
    subBoxes.add(udtaBox);
    subBoxes.add(metaBox);
    subBoxes.addAll(trakBoxes);
    subBoxes.add(mvexBox);

    return BoxUtils.wrapBoxesIntoBox("moov", subBoxes);
  }

  /** Returns an audio sample entry box based on the MIME type. */
  public static ByteBuffer audioSampleEntry(Format format) {
    String fourcc = codecSpecificFourcc(format);
    ByteBuffer codecSpecificBox = codecSpecificBox(format);

    ByteBuffer contents =
        ByteBuffer.allocate(codecSpecificBox.remaining() + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x00); // reserved
    contents.putShort((short) 0x0); // reserved
    contents.putShort((short) 0x1); // data ref index
    contents.putInt(0x00); // reserved
    contents.putInt(0x00); // reserved

    int channelCount = format.channelCount;
    contents.putShort((short) channelCount);
    contents.putShort((short) 16); // sample size
    contents.putShort((short) 0x0); // predefined
    contents.putShort((short) 0x0); // reserved

    int sampleRate = format.sampleRate;
    contents.putInt(sampleRate << 16);

    contents.put(codecSpecificBox);

    contents.flip();
    return BoxUtils.wrapIntoBox(fourcc, contents);
  }

  /** Returns a codec specific box. */
  public static ByteBuffer codecSpecificBox(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_VORBIS:
        return esdsBox(format);
      case MimeTypes.AUDIO_AMR_NB:
        return damrBox(/* mode= */ (short) 0x81FF); // mode set: all enabled for AMR-NB
      case MimeTypes.AUDIO_AMR_WB:
        return damrBox(/* mode= */ (short) 0x83FF); // mode set: all enabled for AMR-WB
      case MimeTypes.AUDIO_OPUS:
        return dOpsBox(format);
      case MimeTypes.VIDEO_H263:
        return d263Box();
      case MimeTypes.VIDEO_H264:
        return avcCBox(format);
      case MimeTypes.VIDEO_H265:
        return hvcCBox(format);
      case MimeTypes.VIDEO_AV1:
        return av1CBox(format);
      case MimeTypes.VIDEO_MP4V:
        return esdsBox(format);
      default:
        throw new IllegalArgumentException("Unsupported format: " + mimeType);
    }
  }

  /**
   * Returns a {@code VisualSampleEntry} box based upon the MIME type.
   *
   * <p>The {@code VisualSampleEntry} schema is defined in ISO/IEC 14496-12: 8.5.2.2.
   */
  public static ByteBuffer videoSampleEntry(Format format) {
    ByteBuffer codecSpecificBox = codecSpecificBox(format);
    String fourcc = codecSpecificFourcc(format);

    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE + codecSpecificBox.limit());

    // reserved = 0 (6 bytes)
    contents.putInt(0);
    contents.putShort((short) 0);

    contents.putShort((short) 1); // data_reference_index

    contents.putShort((short) 0); // pre_defined
    contents.putShort((short) 0); // reserved

    // pre_defined
    contents.putInt(0);
    contents.putInt(0);
    contents.putInt(0);

    contents.putShort(format.width != Format.NO_VALUE ? (short) format.width : 0);
    contents.putShort(format.height != Format.NO_VALUE ? (short) format.height : 0);

    contents.putInt(0x00480000); // horizresolution = 72 dpi
    contents.putInt(0x00480000); // vertresolution = 72 dpi

    contents.putInt(0); // reserved

    contents.putShort((short) 1); // frame_count

    // compressorname
    contents.putLong(0);
    contents.putLong(0);
    contents.putLong(0);
    contents.putLong(0);

    contents.putShort((short) 0x0018); // depth
    contents.putShort((short) -1); // pre_defined

    contents.put(codecSpecificBox);

    contents.put(paspBox());

    // Put in a "colr" box if any of the three color format parameters has a non-default (0) value.
    // TODO: b/278101856 - Only null check should be enough once we disallow invalid values.
    if (format.colorInfo != null
        && (format.colorInfo.colorSpace != 0
            || format.colorInfo.colorTransfer != 0
            || format.colorInfo.colorRange != 0)) {
      contents.put(colrBox(format.colorInfo));
    }

    contents.flip();
    return BoxUtils.wrapIntoBox(fourcc, contents);
  }

  /**
   * Converts sample presentation times (in microseconds) to sample durations (in timebase units).
   *
   * <p>All the tracks must start from the same time. If all the tracks do not start from the same
   * time, then the caller must pass the minimum presentation timestamp across all tracks to be set
   * for the first sample. As a result, the duration of that first sample may be larger.
   *
   * @param samplesInfo A list of {@linkplain BufferInfo sample info}.
   * @param firstSamplePresentationTimeUs The presentation timestamp to override the first sample's
   *     presentation timestamp, in microseconds. This should be the minimum presentation timestamp
   *     across all tracks if the {@code samplesInfo} contains the first sample of the track.
   *     Otherwise this should be equal to the presentation timestamp of first sample present in the
   *     {@code samplesInfo} list.
   * @param videoUnitTimescale The timescale of the track.
   * @param lastDurationBehavior The behaviour for the last sample duration.
   * @return A list of all the sample durations.
   */
  // TODO: b/280084657 - Add support for setting last sample duration.
  // TODO: b/317373578 - Consider changing return type to List<Integer>.
  public static List<Long> convertPresentationTimestampsToDurationsVu(
      List<BufferInfo> samplesInfo,
      long firstSamplePresentationTimeUs,
      int videoUnitTimescale,
      @Mp4Muxer.LastFrameDurationBehavior int lastDurationBehavior) {
    List<Long> presentationTimestampsUs = new ArrayList<>(samplesInfo.size());
    List<Long> durationsVu = new ArrayList<>(samplesInfo.size());

    if (samplesInfo.isEmpty()) {
      return durationsVu;
    }

    boolean hasBframe = false;
    long lastSampleCompositionTimeUs = 0L;
    for (int sampleId = 0; sampleId < samplesInfo.size(); sampleId++) {
      long currentSampleCompositionTimeUs = samplesInfo.get(sampleId).presentationTimeUs;
      presentationTimestampsUs.add(currentSampleCompositionTimeUs);
      if (currentSampleCompositionTimeUs < lastSampleCompositionTimeUs) {
        hasBframe = true;
      }
      lastSampleCompositionTimeUs = currentSampleCompositionTimeUs;
    }

    if (hasBframe) {
      Collections.sort(presentationTimestampsUs);
    }

    long currentSampleTimeUs = firstSamplePresentationTimeUs;
    for (int nextSampleId = 1; nextSampleId < presentationTimestampsUs.size(); nextSampleId++) {
      long nextSampleTimeUs = presentationTimestampsUs.get(nextSampleId);
      // TODO: b/316158030 - First calculate the duration and then convert us to vu to avoid
      //  rounding error.
      long currentSampleDurationVu =
          vuFromUs(nextSampleTimeUs, videoUnitTimescale)
              - vuFromUs(currentSampleTimeUs, videoUnitTimescale);
      if (currentSampleDurationVu > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(
            String.format(
                Locale.US, "Timestamp delta %d doesn't fit into an int", currentSampleDurationVu));
      }
      durationsVu.add(currentSampleDurationVu);
      currentSampleTimeUs = nextSampleTimeUs;
    }
    // Default duration for the last sample.
    durationsVu.add(0L);

    adjustLastSampleDuration(durationsVu, lastDurationBehavior);
    return durationsVu;
  }

  /** Generates the stts (decoding time to sample) box. */
  public static ByteBuffer stts(List<Long> durationsVu) {
    ByteBuffer contents = ByteBuffer.allocate(durationsVu.size() * 8 + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x0); // version and flags.

    // We will know total entry count only after processing all the sample durations, so put in a
    // placeholder for total entry count and store its index.
    int totalEntryCountIndex = contents.position();
    contents.putInt(0x0); // entry_count.

    int totalEntryCount = 0;
    long lastDurationVu = -1L;
    int lastSampleCountIndex = -1;

    // Note that the framework MediaMuxer adjust time deltas within plus-minus 100 us, so that
    // samples have repeating duration values. It saves few entries in the table.
    for (int i = 0; i < durationsVu.size(); i++) {
      long durationVu = durationsVu.get(i);
      if (lastDurationVu != durationVu) {
        lastDurationVu = durationVu;
        lastSampleCountIndex = contents.position();

        // sample_count; this will be updated instead of adding a new entry if the next sample has
        // the same duration.
        contents.putInt(1);
        contents.putInt((int) durationVu); // sample_delta.
        totalEntryCount++;
      } else {
        contents.putInt(lastSampleCountIndex, contents.getInt(lastSampleCountIndex) + 1);
      }
    }

    contents.putInt(totalEntryCountIndex, totalEntryCount);

    contents.flip();
    return BoxUtils.wrapIntoBox("stts", contents);
  }

  /** Returns the ctts (composition time to sample) box. */
  public static ByteBuffer ctts(
      List<BufferInfo> samplesInfo, List<Long> durationVu, int videoUnitTimescale) {
    // Generate the sample composition offsets list to create ctts box.
    List<Integer> compositionOffsets =
        Boxes.calculateSampleCompositionTimeOffsets(samplesInfo, durationVu, videoUnitTimescale);

    if (compositionOffsets.isEmpty()) {
      return ByteBuffer.allocate(0);
    }

    ByteBuffer contents =
        ByteBuffer.allocate(
            2 * BYTES_PER_INTEGER + 2 * compositionOffsets.size() * BYTES_PER_INTEGER);

    contents.putInt(1); // version and flags.

    // We will know total entry count only after processing all the composition offsets, so put in
    // a placeholder for total entry count and store its index.
    int totalEntryCountIndex = contents.position();
    contents.putInt(0x0); // entry_count.

    int totalEntryCount = 0;
    int lastCompositionOffset = -1;
    int lastSampleCountIndex = -1;

    for (int i = 0; i < compositionOffsets.size(); i++) {
      int currentCompositionOffset = compositionOffsets.get(i);
      if (lastCompositionOffset != currentCompositionOffset) {
        lastCompositionOffset = currentCompositionOffset;
        lastSampleCountIndex = contents.position();

        // sample_count; this will be updated instead of adding a new entry if the next sample has
        // the same composition offset.
        contents.putInt(1); // sample_count
        contents.putInt(currentCompositionOffset); // sample_offset
        totalEntryCount++;
      } else {
        contents.putInt(lastSampleCountIndex, contents.getInt(lastSampleCountIndex) + 1);
      }
    }

    contents.putInt(totalEntryCountIndex, totalEntryCount);

    contents.flip();
    return BoxUtils.wrapIntoBox("ctts", contents);
  }

  /**
   * Calculates sample composition time offsets (in timebase units).
   *
   * <p>The sample composition time offset gives offset between composition time (CT) and decoding
   * time (DT), such that {@code CT(n) = DT(n) + sample_offset(n)}.
   *
   * @param samplesInfo A list of {@linkplain BufferInfo sample info}.
   * @param durationVu A list of all the sample durations.
   * @param videoUnitTimescale The timescale of the track.
   * @return A list of all the sample composition time offsets.
   */
  public static List<Integer> calculateSampleCompositionTimeOffsets(
      List<BufferInfo> samplesInfo, List<Long> durationVu, int videoUnitTimescale) {
    List<Integer> compositionOffsets = new ArrayList<>(samplesInfo.size());
    if (samplesInfo.isEmpty()) {
      return compositionOffsets;
    }

    long currentSampleDecodeTime = 0L;
    long firstSamplePresentationTimeUs = samplesInfo.get(0).presentationTimeUs;
    boolean hasBFrame = false;
    long lastSampleCompositionTimeUs = 0L;

    for (int sampleId = 0; sampleId < samplesInfo.size(); sampleId++) {
      long currentSampleCompositionTimeUs =
          samplesInfo.get(sampleId).presentationTimeUs - firstSamplePresentationTimeUs;
      long currentCompositionOffsetVu =
          vuFromUs(currentSampleCompositionTimeUs, videoUnitTimescale) - currentSampleDecodeTime;
      checkState(currentCompositionOffsetVu <= Integer.MAX_VALUE, "Only 32-bit offset is allowed");
      currentSampleDecodeTime += durationVu.get(sampleId); // DT(n+1) = DT(n) + STTS(n)
      compositionOffsets.add((int) currentCompositionOffsetVu);

      if (currentSampleCompositionTimeUs < lastSampleCompositionTimeUs) {
        hasBFrame = true;
      }
      lastSampleCompositionTimeUs = currentSampleCompositionTimeUs;
    }

    if (!hasBFrame) {
      compositionOffsets.clear();
    }
    return compositionOffsets;
  }

  /** Returns the stsz (sample size) box. */
  public static ByteBuffer stsz(List<MediaCodec.BufferInfo> writtenSamples) {
    ByteBuffer contents = ByteBuffer.allocate(writtenSamples.size() * 4 + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x0); // version and flags.

    // TODO: b/270583563 - Consider optimizing for identically-sized samples.
    //  sample_size; specifying the default sample size. Set to zero to indicate that the samples
    //  have different sizes and they are stored in the sample size table.
    contents.putInt(0);

    contents.putInt(writtenSamples.size()); // sample_count.

    for (int i = 0; i < writtenSamples.size(); i++) {
      contents.putInt(writtenSamples.get(i).size);
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("stsz", contents);
  }

  /** Returns the stsc (sample to chunk) box. */
  public static ByteBuffer stsc(List<Integer> writtenChunkSampleCounts) {
    ByteBuffer contents =
        ByteBuffer.allocate(writtenChunkSampleCounts.size() * 12 + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x0); // version and flags.
    contents.putInt(writtenChunkSampleCounts.size()); // entry_count.

    int currentChunk = 1;

    // TODO: b/270583563 - Consider optimizing for consecutive chunks having same number of samples.
    for (int i = 0; i < writtenChunkSampleCounts.size(); i++) {
      int samplesInChunk = writtenChunkSampleCounts.get(i);
      contents.putInt(currentChunk); // first_chunk.
      contents.putInt(samplesInChunk); // samples_per_chunk.
      // sample_description_index; we have only one sample description in each track.
      contents.putInt(1);

      currentChunk += 1;
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("stsc", contents);
  }

  /** Returns the stco (32-bit chunk offset) box. */
  public static ByteBuffer stco(List<Long> writtenChunkOffsets) {
    ByteBuffer contents =
        ByteBuffer.allocate(2 * BYTES_PER_INTEGER + writtenChunkOffsets.size() * BYTES_PER_INTEGER);

    contents.putInt(0x0); // version and flags
    contents.putInt(writtenChunkOffsets.size()); // entry_count; unsigned int(32)

    for (int i = 0; i < writtenChunkOffsets.size(); i++) {
      long chunkOffset = writtenChunkOffsets.get(i);
      checkState(chunkOffset <= UNSIGNED_INT_MAX_VALUE, "Only 32-bit chunk offset is allowed");
      contents.putInt((int) chunkOffset); // chunk_offset; unsigned int(32)
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("stco", contents);
  }

  /** Returns the co64 (64-bit chunk offset) box. */
  public static ByteBuffer co64(List<Long> writtenChunkOffsets) {
    ByteBuffer contents =
        ByteBuffer.allocate(
            2 * BYTES_PER_INTEGER + 2 * writtenChunkOffsets.size() * BYTES_PER_INTEGER);

    contents.putInt(0x0); // version and flags
    contents.putInt(writtenChunkOffsets.size()); // entry_count; unsigned int(32)

    for (int i = 0; i < writtenChunkOffsets.size(); i++) {
      contents.putLong(writtenChunkOffsets.get(i)); // chunk_offset; unsigned int(64)
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("co64", contents);
  }

  /** Returns the stss (sync sample) box. */
  public static ByteBuffer stss(List<MediaCodec.BufferInfo> writtenSamples) {
    ByteBuffer contents = ByteBuffer.allocate(writtenSamples.size() * 4 + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x0); // version and flags.

    // We will know total entry count only after processing all the sample, so put in a placeholder
    // for total entry count and store its index.
    int totalEntryCountIndex = contents.position();
    contents.putInt(writtenSamples.size()); // entry_count.

    int currentSampleNumber = 1;
    int totalKeyFrames = 0;
    for (int i = 0; i < writtenSamples.size(); i++) {
      MediaCodec.BufferInfo info = writtenSamples.get(i);
      if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) > 0) {
        contents.putInt(currentSampleNumber); // sample_number.
        totalKeyFrames++;
      }

      currentSampleNumber++;
    }

    contents.putInt(totalEntryCountIndex, totalKeyFrames);

    contents.flip();
    return BoxUtils.wrapIntoBox("stss", contents);
  }

  /** Returns the stsd (sample description) box. */
  public static ByteBuffer stsd(ByteBuffer sampleEntryBox) {
    ByteBuffer contents = ByteBuffer.allocate(sampleEntryBox.limit() + MAX_FIXED_LEAF_BOX_SIZE);

    contents.putInt(0x0); // version and flags.
    contents.putInt(1); // entry_count, We have only one sample description in each track.
    contents.put(sampleEntryBox);

    contents.flip();
    return BoxUtils.wrapIntoBox("stsd", contents);
  }

  /** Returns the stbl (sample table) box. */
  public static ByteBuffer stbl(ByteBuffer... subBoxes) {
    return BoxUtils.wrapBoxesIntoBox("stbl", Arrays.asList(subBoxes));
  }

  /** Creates the ftyp box. */
  public static ByteBuffer ftyp() {
    List<ByteBuffer> boxBytes = new ArrayList<>();

    String majorVersion = "isom";
    boxBytes.add(ByteBuffer.wrap(Util.getUtf8Bytes(majorVersion)));

    int minorVersion = 0x020000;
    ByteBuffer minorBytes = ByteBuffer.allocate(4);
    minorBytes.putInt(minorVersion);
    minorBytes.flip();
    boxBytes.add(minorBytes);

    String[] compatibleBrands = {"isom", "iso2", "mp41"};
    for (String compatibleBrand : compatibleBrands) {
      boxBytes.add(ByteBuffer.wrap(Util.getUtf8Bytes(compatibleBrand)));
    }

    return BoxUtils.wrapBoxesIntoBox("ftyp", boxBytes);
  }

  /** Returns the movie fragment (moof) box. */
  public static ByteBuffer moof(ByteBuffer mfhdBox, List<ByteBuffer> trafBoxes) {
    return BoxUtils.wrapBoxesIntoBox(
        "moof", new ImmutableList.Builder<ByteBuffer>().add(mfhdBox).addAll(trafBoxes).build());
  }

  /** Returns the movie fragment header (mfhd) box. */
  public static ByteBuffer mfhd(int sequenceNumber) {
    ByteBuffer contents = ByteBuffer.allocate(MFHD_BOX_CONTENT_SIZE);
    contents.putInt(0x0); // version and flags
    contents.putInt(sequenceNumber); // An unsigned int(32)
    contents.flip();
    return BoxUtils.wrapIntoBox("mfhd", contents);
  }

  /** Returns a track fragment (traf) box. */
  public static ByteBuffer traf(ByteBuffer tfhdBox, ByteBuffer trunBox) {
    return BoxUtils.wrapBoxesIntoBox("traf", ImmutableList.of(tfhdBox, trunBox));
  }

  /** Returns a track fragment header (tfhd) box. */
  public static ByteBuffer tfhd(int trackId, long baseDataOffset) {
    ByteBuffer contents = ByteBuffer.allocate(TFHD_BOX_CONTENT_SIZE);
    // 0x000001 base-data-offset-present: indicates the presence of the base-data-offset field.
    contents.putInt(0x0 | 0x000001); // version and flags
    contents.putInt(trackId);
    contents.putLong(baseDataOffset);
    contents.flip();
    return BoxUtils.wrapIntoBox("tfhd", contents);
  }

  /** Returns a track fragment run (trun) box. */
  public static ByteBuffer trun(
      List<SampleMetadata> samplesMetadata, int dataOffset, boolean hasBFrame) {
    ByteBuffer contents =
        ByteBuffer.allocate(getTrunBoxContentSize(samplesMetadata.size(), hasBFrame));

    // 0x000001 data-offset-present.
    // 0x000100 sample-duration-present: indicates that each sample has its own duration, otherwise
    // the default is used.
    // 0x000200 sample-size-present: indicates that each sample has its own size, otherwise the
    // default is used.
    // 0x000400 sample-flags-present: indicates that each sample has its own flags, otherwise the
    // default is used.
    // 0x000800 sample-composition-time-offsets-present: indicates that each sample has its own
    // composition time offset, otherwise default is used.
    // Version (the most significant byte of versionAndFlags) is 0x1.
    int versionAndFlags = 0x1 << 24 | 0x000001 | 0x000100 | 0x000200 | 0x000400;
    if (hasBFrame) {
      versionAndFlags |= 0x000800;
    }
    contents.putInt(versionAndFlags);
    contents.putInt(samplesMetadata.size()); // An unsigned int(32)
    contents.putInt(dataOffset); // A signed int(32)
    for (int i = 0; i < samplesMetadata.size(); i++) {
      SampleMetadata currentSampleMetadata = samplesMetadata.get(i);
      contents.putInt((int) currentSampleMetadata.durationVu); // An unsigned int(32)
      contents.putInt(currentSampleMetadata.size); // An unsigned int(32)
      contents.putInt(
          (currentSampleMetadata.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
              ? TRUN_BOX_SYNC_SAMPLE_FLAGS
              : TRUN_BOX_NON_SYNC_SAMPLE_FLAGS);
      if (hasBFrame) {
        contents.putInt(currentSampleMetadata.compositionTimeOffsetVu);
      }
    }
    contents.flip();
    return BoxUtils.wrapIntoBox("trun", contents);
  }

  /** Returns the size required for {@link #trun(List, int, boolean)} box content. */
  public static int getTrunBoxContentSize(int sampleCount, boolean hasBFrame) {
    int trunBoxFixedSize = 3 * BYTES_PER_INTEGER;
    int intWrittenPerSample = hasBFrame ? 4 : 3;
    return trunBoxFixedSize + intWrittenPerSample * sampleCount * BYTES_PER_INTEGER;
  }

  /** Returns a movie extends (mvex) box. */
  public static ByteBuffer mvex(List<ByteBuffer> trexBoxes) {
    return BoxUtils.wrapBoxesIntoBox("mvex", trexBoxes);
  }

  /** Returns a track extends (trex) box. */
  public static ByteBuffer trex(int trackId) {
    ByteBuffer contents = ByteBuffer.allocate(6 * BYTES_PER_INTEGER);
    contents.putInt(0x0); // version and flags
    contents.putInt(trackId);
    contents.putInt(1); // default_sample_description_index
    contents.putInt(0); // default_sample_duration
    contents.putInt(0); // default_sample_size
    contents.putInt(0); // default_sample_flags
    contents.flip();
    return BoxUtils.wrapIntoBox("trex", contents);
  }

  // TODO: b/317117431 - Change this method to getLastSampleDuration().
  /** Adjusts the duration of the very last sample if needed. */
  private static void adjustLastSampleDuration(
      List<Long> durationsToBeAdjustedVu, @Mp4Muxer.LastFrameDurationBehavior int behavior) {
    // Technically, MP4 file stores frame durations, not timestamps. If a frame starts at a
    // given timestamp then the duration of the last frame is not obvious. If samples follow each
    // other in roughly regular intervals (e.g. in a normal, 30 fps video), it can be safely assumed
    // that the last sample will have same duration (~33ms) as other samples. On the other hand, if
    // there are just a few, irregularly spaced frames, with duplication, the entire duration of the
    // video will increase, creating abnormal gaps.

    if (durationsToBeAdjustedVu.size() <= 2) {
      // Nothing to duplicate if there are 0 or 1 entries.
      return;
    }

    switch (behavior) {
      case Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_DUPLICATE_PREV_DURATION:
        // This is the default MediaMuxer behavior: the last sample duration is a copy of the
        // previous sample duration.
        durationsToBeAdjustedVu.set(
            durationsToBeAdjustedVu.size() - 1,
            durationsToBeAdjustedVu.get(durationsToBeAdjustedVu.size() - 2));
        break;
      case Mp4Muxer.LAST_FRAME_DURATION_BEHAVIOR_INSERT_SHORT_FRAME:
        // Keep the last sample duration as short as possible.
        checkState(Iterables.getLast(durationsToBeAdjustedVu) == 0L);
        break;
      default:
        throw new IllegalArgumentException(
            "Unexpected value for the last frame duration behavior " + behavior);
    }
  }

  /** Returns the d263Box box as per 3GPP ETSI TS 126 244: 6.8. */
  private static ByteBuffer d263Box() {
    ByteBuffer d263Box = ByteBuffer.allocate(7);
    d263Box.put("    ".getBytes(UTF_8)); // 4 spaces (vendor)
    d263Box.put((byte) 0x00); // decoder version
    // TODO: b/352000778 - Get profile and level from format.
    d263Box.put((byte) 0x10); // level
    d263Box.put((byte) 0x00); // profile

    d263Box.flip();
    return BoxUtils.wrapIntoBox("d263", d263Box);
  }

  /** Returns the avcC box as per ISO/IEC 14496-15: 5.3.3.1.2. */
  private static ByteBuffer avcCBox(Format format) {
    checkArgument(
        format.initializationData.size() >= 2, "csd-0 and/or csd-1 not found in the format.");

    byte[] csd0 = format.initializationData.get(0);
    checkArgument(csd0.length > 0, "csd-0 is empty.");

    byte[] csd1 = format.initializationData.get(1);
    checkArgument(csd1.length > 0, "csd-1 is empty.");

    ByteBuffer csd0ByteBuffer = ByteBuffer.wrap(csd0);
    ByteBuffer csd1ByteBuffer = ByteBuffer.wrap(csd1);

    ByteBuffer contents =
        ByteBuffer.allocate(
            csd0ByteBuffer.limit() + csd1ByteBuffer.limit() + MAX_FIXED_LEAF_BOX_SIZE);

    contents.put((byte) 0x01); // configurationVersion

    ImmutableList<ByteBuffer> csd0NalUnits = AnnexBUtils.findNalUnits(csd0ByteBuffer);
    checkArgument(csd0NalUnits.size() == 1, "SPS data not found in csd0.");

    ByteBuffer sps = csd0NalUnits.get(0);
    byte[] spsData = new byte[sps.remaining()];
    sps.get(spsData);
    sps.rewind();

    NalUnitUtil.SpsData h264SpsData =
        NalUnitUtil.parseSpsNalUnit(spsData, /* nalOffset= */ 0, spsData.length);
    contents.put((byte) h264SpsData.profileIdc); // AVCProfileIndication
    contents.put((byte) h264SpsData.constraintsFlagsAndReservedZero2Bits); // profile_compatibility
    contents.put((byte) h264SpsData.levelIdc); // AVCLevelIndication

    contents.put((byte) 0xFF); // 6 bits reserved ('0b111111') + 2 bits lengthSizeMinusOne (3)
    contents.put((byte) 0xE1); // 3 bits reserved ('0b111') + 5 bits numOfSequenceParameterSets (1)
    contents.putShort((short) sps.remaining()); // sequenceParameterSetLength
    contents.put(sps); // sequenceParameterSetNALUnit
    sps.rewind();

    ImmutableList<ByteBuffer> csd1NalUnits = AnnexBUtils.findNalUnits(csd1ByteBuffer);
    checkState(csd1NalUnits.size() == 1, "PPS data not found in csd1.");

    contents.put((byte) 0x01); // numOfPictureParameterSets

    ByteBuffer pps = csd1NalUnits.get(0);
    contents.putShort((short) pps.remaining()); // pictureParameterSetLength
    contents.put(pps); // pictureParameterSetNALUnit
    pps.rewind();

    contents.flip();
    return BoxUtils.wrapIntoBox("avcC", contents);
  }

  /** Returns the hvcC box as per ISO/IEC 14496-15: 8.3.3.1.2. */
  private static ByteBuffer hvcCBox(Format format) {
    // For H.265, all three codec-specific NALUs (VPS, SPS, PPS) are packed into csd-0.
    checkArgument(!format.initializationData.isEmpty(), "csd-0 not found in the format.");

    byte[] csd0 = format.initializationData.get(0);
    checkArgument(csd0.length > 0, "csd-0 is empty.");

    ByteBuffer csd0ByteBuffer = ByteBuffer.wrap(csd0);

    ByteBuffer contents = ByteBuffer.allocate(csd0ByteBuffer.limit() + MAX_FIXED_LEAF_BOX_SIZE);

    ImmutableList<ByteBuffer> nalusWithEmulationPrevention =
        AnnexBUtils.findNalUnits(csd0ByteBuffer);

    // Remove emulation prevention bytes to parse the actual csd-0 data.
    // For storing the csd-0 data into MP4 file, use original NALUs with emulation prevention bytes.
    List<ByteBuffer> nalusWithoutEmulationPrevention = new ArrayList<>();
    for (int i = 0; i < nalusWithEmulationPrevention.size(); i++) {
      nalusWithoutEmulationPrevention.add(
          AnnexBUtils.stripEmulationPrevention(nalusWithEmulationPrevention.get(i)));
    }

    contents.put((byte) 0x01); // configurationVersion

    // Assuming that VPS, SPS and PPS are in this order in csd-0.
    ByteBuffer vps = nalusWithoutEmulationPrevention.get(0);

    if (vps.get(vps.position()) != 0x40) {
      throw new IllegalArgumentException("First NALU in csd-0 is not the VPS.");
    }

    // general_profile_space (2 bits) + general_tier_flag (1 bit) + general_profile_idc (5 bits)
    contents.put(vps.get(6));

    contents.putInt(vps.getInt(7)); // general_profile_compatibility_flags

    // general_constraint_indicator_flags (6 bytes)
    contents.putInt(vps.getInt(11));
    contents.putShort(vps.getShort(15));

    contents.put(vps.get(17)); // general_level_idc

    // First 4 bits reserved + min_spatial_segmentation_idc (12 bits)
    contents.putShort((short) 0xF000);

    // First 6 bits reserved + parallelismType (2 bits)
    contents.put((byte) 0xFC);

    ByteBuffer sps = nalusWithEmulationPrevention.get(1);
    byte[] spsArray = new byte[sps.remaining()];
    sps.get(spsArray);
    sps.rewind();

    NalUnitUtil.H265SpsData h265SpsData =
        NalUnitUtil.parseH265SpsNalUnit(
            spsArray, /* nalOffset= */ 0, /* nalLimit= */ spsArray.length);

    byte chromaFormat = (byte) (0xFC | h265SpsData.chromaFormatIdc); // First 6 bits reserved
    byte bitDepthLumaMinus8 =
        (byte) (0xF8 | h265SpsData.bitDepthLumaMinus8); // First 5 bits reserved
    byte bitDepthChromaMinus8 =
        (byte) (0xF8 | h265SpsData.bitDepthChromaMinus8); // First 5 bits reserved
    contents.put(chromaFormat);
    contents.put(bitDepthLumaMinus8);
    contents.put(bitDepthChromaMinus8);

    // avgFrameRate; value 0 indicates an unspecified average frame rate.
    contents.putShort((short) 0);

    // constantFrameRate (2 bits) + numTemporalLayers (3 bits) + temporalIdNested (1 bit) +
    // lengthSizeMinusOne (2 bits)
    contents.put((byte) 0x0F);

    // Put all NALUs.
    contents.put((byte) nalusWithEmulationPrevention.size()); // numOfArrays

    for (int i = 0; i < nalusWithEmulationPrevention.size(); i++) {
      ByteBuffer nalu = nalusWithEmulationPrevention.get(i);

      // array_completeness (1 bit) + reserved (1 bit) + NAL_unit_type (6 bits)
      byte naluType = (byte) ((nalu.get(0) >> 1) & 0x3F);
      contents.put(naluType);

      contents.putShort((short) 1); // numNalus; number of NALUs in array
      contents.putShort((short) nalu.limit()); // nalUnitLength
      contents.put(nalu);
    }

    contents.flip();
    return BoxUtils.wrapIntoBox("hvcC", contents);
  }

  /** Returns the av1C box. */
  private static ByteBuffer av1CBox(Format format) {
    // For AV1, the entire codec-specific box is packed into csd-0.
    checkArgument(!format.initializationData.isEmpty(), "csd-0 is not found in the format");

    byte[] csd0 = format.initializationData.get(0);
    checkArgument(csd0.length > 0, "csd-0 is empty.");

    return BoxUtils.wrapIntoBox("av1C", ByteBuffer.wrap(csd0));
  }

  /** Returns the pasp box. */
  private static ByteBuffer paspBox() {
    ByteBuffer contents = ByteBuffer.allocate(8);

    contents.putInt(1 << 16); // hspacing
    contents.putInt(1 << 16); // vspacing

    contents.rewind();
    return BoxUtils.wrapIntoBox("pasp", contents);
  }

  /** Returns the colr box. */
  @SuppressWarnings("InlinedApi")
  private static ByteBuffer colrBox(ColorInfo colorInfo) {
    ByteBuffer contents = ByteBuffer.allocate(20);
    contents.put((byte) 'n');
    contents.put((byte) 'c');
    contents.put((byte) 'l');
    contents.put((byte) 'x');

    // Parameters going into the file.
    short primaries = 0;
    short transfer = 0;
    short matrix = 0;
    byte range = 0;

    if (colorInfo.colorSpace != Format.NO_VALUE) {
      int standard = colorInfo.colorSpace;
      if (standard < 0 || standard >= MEDIAFORMAT_STANDARD_TO_PRIMARIES_AND_MATRIX.size()) {
        throw new IllegalArgumentException("Color standard not implemented: " + standard);
      }

      primaries = MEDIAFORMAT_STANDARD_TO_PRIMARIES_AND_MATRIX.get(standard).get(0);
      matrix = MEDIAFORMAT_STANDARD_TO_PRIMARIES_AND_MATRIX.get(standard).get(1);
    }

    if (colorInfo.colorTransfer != Format.NO_VALUE) {
      int transferInFormat = colorInfo.colorTransfer;
      if (transferInFormat < 0 || transferInFormat >= MEDIAFORMAT_TRANSFER_TO_MP4_TRANSFER.size()) {
        throw new IllegalArgumentException("Color transfer not implemented: " + transferInFormat);
      }

      transfer = MEDIAFORMAT_TRANSFER_TO_MP4_TRANSFER.get(transferInFormat);
    }

    if (colorInfo.colorRange != Format.NO_VALUE) {
      int rangeInFormat = colorInfo.colorRange;
      // Handled values are 0 (unknown), 1 (full) and 2 (limited).
      if (rangeInFormat < 0 || rangeInFormat > 2) {
        throw new IllegalArgumentException("Color range not implemented: " + rangeInFormat);
      }

      // Set this to 0x80 only for full range, 0 otherwise.
      range = rangeInFormat == C.COLOR_RANGE_FULL ? (byte) 0x80 : 0;
    }

    contents.putShort(primaries);
    contents.putShort(transfer);
    contents.putShort(matrix);
    contents.put(range);

    contents.flip();
    return BoxUtils.wrapIntoBox("colr", contents);
  }

  /** Returns codec specific fourcc. */
  private static String codecSpecificFourcc(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_VORBIS:
        return "mp4a";
      case MimeTypes.AUDIO_AMR_NB:
        return "samr";
      case MimeTypes.AUDIO_AMR_WB:
        return "sawb";
      case MimeTypes.VIDEO_H263:
        return "s263";
      case MimeTypes.AUDIO_OPUS:
        return "Opus";
      case MimeTypes.VIDEO_H264:
        return "avc1";
      case MimeTypes.VIDEO_H265:
        return "hvc1";
      case MimeTypes.VIDEO_AV1:
        return "av01";
      case MimeTypes.VIDEO_MP4V:
        return "mp4v-es";
      default:
        throw new IllegalArgumentException("Unsupported format: " + mimeType);
    }
  }

  /** Returns the esds box. */
  private static ByteBuffer esdsBox(Format format) {
    checkArgument(!format.initializationData.isEmpty(), "csd-0 not found in the format.");

    byte[] csd0 = format.initializationData.get(0);
    checkArgument(csd0.length > 0, "csd-0 is empty.");

    String mimeType = checkNotNull(format.sampleMimeType);
    boolean isVorbis = mimeType.equals(MimeTypes.AUDIO_VORBIS);
    ByteBuffer csdByteBuffer =
        isVorbis ? getVorbisInitializationData(format) : ByteBuffer.wrap(csd0);

    int peakBitrate = format.peakBitrate;
    int averageBitrate = format.averageBitrate;
    boolean isVideo = MimeTypes.isVideo(mimeType);

    int csdSize = csdByteBuffer.remaining();
    ByteBuffer dsiSizeBuffer = getSizeBuffer(csdSize);
    ByteBuffer dcdSizeBuffer = getSizeBuffer(csdSize + dsiSizeBuffer.remaining() + 14);
    ByteBuffer esdSizeBuffer =
        getSizeBuffer(csdSize + dsiSizeBuffer.remaining() + dcdSizeBuffer.remaining() + 21);

    ByteBuffer contents = ByteBuffer.allocate(csdSize + MAX_FIXED_LEAF_BOX_SIZE);
    contents.putInt(0x00); // Version and flags.
    contents.put((byte) 0x03); // ES_DescrTag

    contents.put(esdSizeBuffer);

    contents.putShort((short) 0x0000); // ES_ID
    // streamDependenceFlag (1 bit) + URL_Flag (1 bit) + OCRstreamFlag (1 bit) + streamPriority (5
    // bits)
    contents.put(isVideo ? (byte) 0x1f : (byte) 0x00);

    contents.put((byte) 0x04); // DecoderConfigDescrTag
    contents.put(dcdSizeBuffer);

    Byte objectType = checkNotNull(MimeTypes.getMp4ObjectTypeFromMimeType(mimeType));
    contents.put(objectType); // objectTypeIndication

    // streamType (6 bits) + upStream (1 bit) + reserved = 1 (1 bit)
    contents.put((byte) ((isVideo ? (0x04 << 2) : (0x05 << 2)) | 0x01));

    int size = isVideo ? 0x017700 : 0x000300;
    contents.putShort((short) ((size >> 8) & 0xFFFF)); // First 16 bits of buffer size.
    contents.put((byte) 0x00); // Last 8 bits of buffer size.

    contents.putInt(peakBitrate != Format.NO_VALUE ? peakBitrate : 0);
    contents.putInt(averageBitrate != Format.NO_VALUE ? averageBitrate : 0);

    contents.put((byte) 0x05); // DecoderSpecificInfoTag
    contents.put(dsiSizeBuffer);
    contents.put(csdByteBuffer);
    csdByteBuffer.rewind();

    contents.put((byte) 0x06); // SLConfigDescriptorTag
    contents.put((byte) 0x01);
    contents.put((byte) 0x02);

    contents.flip();
    return BoxUtils.wrapIntoBox("esds", contents);
  }

  private static ByteBuffer getSizeBuffer(int length) {
    int prefix = 0;
    ArrayDeque<Byte> esdsSizeBytes = new ArrayDeque<>();
    do {
      esdsSizeBytes.push((byte) (prefix | (length & 0x7F)));
      length >>= 7;
      prefix = 0x80;
    } while (length > 0);

    ByteBuffer sizeBuffer = ByteBuffer.allocate(esdsSizeBytes.size());
    while (!esdsSizeBytes.isEmpty()) {
      sizeBuffer.put(esdsSizeBytes.removeFirst());
    }
    sizeBuffer.flip();
    return sizeBuffer;
  }

  /* Returns csd wrapped in ByteBuffer in vorbis codec initialization data format. */
  private static ByteBuffer getVorbisInitializationData(Format format) {
    checkArgument(
        format.initializationData.size() > 1, "csd-1 should contain setup header for Vorbis.");
    byte[] csd0 = format.initializationData.get(0); // identification Header

    // csd0Size is represented using "Xiph lacing" style.
    // The lacing size is split into 255 values, stored as unsigned octets – for example, 500 is
    // coded 255;245 or [0xFF 0xF5]. A frame with a size multiple of 255 is coded with a 0 at the
    // end of the size – for example, 765 is coded 255;255;255;0 or [0xFF 0xFF 0xFF 0x00].
    byte[] csd0Size = new byte[csd0.length / 255 + 1];
    Arrays.fill(csd0Size, (byte) 0xFF);
    csd0Size[csd0Size.length - 1] = (byte) (csd0.length % 255);

    byte[] csd1 = format.initializationData.get(1); // setUp Header
    checkArgument(csd1.length > 0, "csd-1 should be present and contain setup header for Vorbis.");

    // Add 2 bytes - 1 for Vorbis audio and 1 for comment header length.
    ByteBuffer csd = ByteBuffer.allocate(csd0Size.length + csd0.length + csd1.length + 2);
    csd.put((byte) 0x02); // Vorbis audio
    csd.put(csd0Size); // Size of identification header
    csd.put((byte) 0); // Length of comment header
    csd.put(csd0);
    csd.put(csd1);
    csd.flip();

    return csd;
  }

  /** Returns the audio damr box. */
  private static ByteBuffer damrBox(short mode) {

    ByteBuffer contents = ByteBuffer.allocate(MAX_FIXED_LEAF_BOX_SIZE);

    contents.put("    ".getBytes(UTF_8)); // vendor: 4 bytes
    contents.put((byte) 0); // decoder version
    contents.putShort(mode);
    contents.put((byte) 0); // mode change period
    contents.put((byte) 1); // frames per sample

    contents.flip();
    return BoxUtils.wrapIntoBox("damr", contents);
  }

  /** Returns the audio dOps box for Opus codec as per RFC-7845: 5.1. */
  private static ByteBuffer dOpsBox(Format format) {
    checkArgument(!format.initializationData.isEmpty());

    int opusHeaderLength = 8;
    byte[] csd0 = format.initializationData.get(0);
    checkArgument(
        csd0.length >= opusHeaderLength,
        "As csd0 contains 'OpusHead' in first 8 bytes, csd0 length should be greater than 8");
    ByteBuffer contents = ByteBuffer.allocate(csd0.length);
    // Skip 8 bytes containing "OpusHead".
    contents.put(
        /* src */ csd0, /* offset */ opusHeaderLength, /* length */ csd0.length - opusHeaderLength);
    contents.flip();

    return BoxUtils.wrapIntoBox("dOps", contents);
  }

  /** Packs a three-letter language code into a short, packing 3x5 bits. */
  private static short languageCodeFromString(@Nullable String code) {
    if (code == null) {
      return 0;
    }

    byte[] bytes = Util.getUtf8Bytes(code);

    if (bytes.length != 3) {
      throw new IllegalArgumentException("Non-length-3 language code: " + code);
    }

    // Use an int so that we don't bump into the issue of Java not having unsigned types. We take
    // the last 5 bits of each letter to supply 5 bits each of the eventual code.

    int value = (bytes[2] & 0x1F);
    value += (bytes[1] & 0x1F) << 5;
    value += (bytes[0] & 0x1F) << 10;

    // This adds up to 15 bits; the 16th one is really supposed to be 0.
    checkState((value & 0x8000) == 0);
    return (short) (value & 0xFFFF);
  }

  /**
   * Generates an orientation matrix, to be included in the MP4 header.
   *
   * <p>The supported values are 0, 90, 180 and 270 (degrees).
   */
  private static byte[] rotationMatrixFromOrientation(int orientation) {
    // The transformation matrix is defined as below:
    // | a b u |
    // | c d v |
    // | x y w |
    // To specify the orientation (u, v, w) are restricted to (0, 0, 0x40000000).
    // Reference: ISO/IEC 14496-12: 8.2.2.3.
    int fixedOne = 65536;
    switch (orientation) {
      case 0:
        return Util.toByteArray(fixedOne, 0, 0, 0, fixedOne, 0, 0, 0, 0x40000000);
      case 90:
        return Util.toByteArray(0, fixedOne, 0, -fixedOne, 0, 0, 0, 0, 0x40000000);
      case 180:
        return Util.toByteArray(-fixedOne, 0, 0, 0, -fixedOne, 0, 0, 0, 0x40000000);
      case 270:
        return Util.toByteArray(0, -fixedOne, 0, fixedOne, 0, 0, 0, 0, 0x40000000);
      default:
        throw new IllegalArgumentException("invalid orientation " + orientation);
    }
  }

  /** Converts microseconds to video units, using the provided timebase. */
  private static long vuFromUs(long timestampUs, long videoUnitTimebase) {
    return timestampUs * videoUnitTimebase / 1_000_000L; // (division for us to s conversion)
  }
}
