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
package androidx.media3.extractor;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.Nullable;
import androidx.annotation.StringDef;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Utility methods for parsing DTS frames. */
@UnstableApi
public final class DtsUtil {
  /**
   * The possible MIME types for DTS that can be used.
   *
   * <p>One of:
   *
   * <ul>
   *   <li>{@link MimeTypes#AUDIO_DTS}
   *   <li>{@link MimeTypes#AUDIO_DTS_EXPRESS}
   *   <li>{@link MimeTypes#AUDIO_DTS_X}
   * </ul>
   */
  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @StringDef({MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X})
  public @interface DtsAudioMimeType {}

  /** Format information for DTS audio. */
  public static final class DtsAudioFormat {
    /** The mime type of the DTS bitstream. */
    public final @DtsAudioMimeType String mimeType;

    /** The audio sampling rate in Hertz. */
    public final int sampleRate;

    /** The number of channels */
    public final int channelCount;

    /** The size of a DTS frame (compressed), in bytes. */
    public final int frameSize;

    /** Number of audio samples in a frame. */
    public final int sampleCount;

    /** The bitrate of compressed stream. */
    public final int bitrate;

    private DtsAudioFormat(
        String mimeType,
        int channelCount,
        int sampleRate,
        int frameSize,
        int sampleCount,
        int bitrate) {
      this.mimeType = mimeType;
      this.channelCount = channelCount;
      this.sampleRate = sampleRate;
      this.frameSize = frameSize;
      this.sampleCount = sampleCount;
      this.bitrate = bitrate;
    }
  }

  /** Variables that are extracted in the FTOC sync frame and re-used in the subsequent
   * FTOC non-sync frame. */
  public static final class DtsUhdState {
    /* A state variable that stores the UHD audio chunk ID extracted from the FTOC sync frame. */
    private int storedUhdAudioChunkId;
  }

  /**
   * Maximum rate for a DTS audio stream, in bytes per second.
   *
   * <p>DTS allows an 'open' bitrate, but we assume the maximum listed value: 1536 kbit/s.
   */
  public static final int DTS_MAX_RATE_BYTES_PER_SECOND = 1536 * 1000 / 8;
  /** Maximum rate for a DTS-HD audio stream, in bytes per second. */
  public static final int DTS_HD_MAX_RATE_BYTES_PER_SECOND = 18000 * 1000 / 8;

  /** DTS UHD Channel count. */
  public static final int DTS_UHD_CHANNEL_COUNT = 2;

  private static final int SYNC_VALUE_BE = 0x7FFE8001;
  private static final int SYNC_VALUE_14B_BE = 0x1FFFE800;
  private static final int SYNC_VALUE_LE = 0xFE7F0180;
  private static final int SYNC_VALUE_14B_LE = 0xFF1F00E8;

  /**
   * DTS Extension Substream Syncword (in different Endianness). See ETSI TS 102 114 (V1.6.1)
   * Section 7.4.1.
   */
  private static final int SYNC_VALUE_EXTSS_BE = 0x64582025;

  private static final int SYNC_VALUE_EXTSS_LE = 0x25205864;

  /**
   * DTS UHD FTOC Sync words (in different Endianness). See ETSI TS 103 491 (V1.2.1) Section
   * 6.4.4.1.
   */
  private static final int SYNC_VALUE_UHD_FTOC_SYNC_BE = 0x40411BF2;

  private static final int SYNC_VALUE_UHD_FTOC_SYNC_LE = 0xF21B4140;
  private static final int SYNC_VALUE_UHD_FTOC_NONSYNC_BE = 0x71C442E8;
  private static final int SYNC_VALUE_UHD_FTOC_NONSYNC_LE = 0xE842C471;

  private static final byte FIRST_BYTE_BE = (byte) (SYNC_VALUE_BE >>> 24);
  private static final byte FIRST_BYTE_14B_BE = (byte) (SYNC_VALUE_14B_BE >>> 24);
  private static final byte FIRST_BYTE_LE = (byte) (SYNC_VALUE_LE >>> 24);
  private static final byte FIRST_BYTE_14B_LE = (byte) (SYNC_VALUE_14B_LE >>> 24);
  private static final byte FIRST_BYTE_EXTSS_BE = (byte) (SYNC_VALUE_EXTSS_BE >>> 24);
  private static final byte FIRST_BYTE_EXTSS_LE = (byte) (SYNC_VALUE_EXTSS_LE >>> 24);
  private static final byte FIRST_BYTE_UHD_FTOC_SYNC_BE =
      (byte) (SYNC_VALUE_UHD_FTOC_SYNC_BE >>> 24);
  private static final byte FIRST_BYTE_UHD_FTOC_SYNC_LE =
      (byte) (SYNC_VALUE_UHD_FTOC_SYNC_LE >>> 24);
  private static final byte FIRST_BYTE_UHD_FTOC_NONSYNC_BE =
      (byte) (SYNC_VALUE_UHD_FTOC_NONSYNC_BE >>> 24);
  private static final byte FIRST_BYTE_UHD_FTOC_NONSYNC_LE =
      (byte) (SYNC_VALUE_UHD_FTOC_NONSYNC_LE >>> 24);

  /** Maps AMODE to the number of channels. See ETSI TS 102 114 table 5-4. */
  private static final int[] CHANNELS_BY_AMODE =
      new int[] {1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8};

  /** Maps SFREQ to the sampling frequency in Hz. See ETSI TS 102 114 table 5-5. */
  private static final int[] SAMPLE_RATE_BY_SFREQ =
      new int[] {
          -1, 8_000, 16_000, 32_000, -1, -1, 11_025, 22_050, 44_100, -1, -1, 12_000, 24_000, 48_000,
          -1, -1
      };

  /** Maps RATE to 2 * bitrate in kbit/s. See ETSI TS 102 114 table 5-7. */
  private static final int[] TWICE_BITRATE_KBPS_BY_RATE =
      new int[] {
          64, 112, 128, 192, 224, 256, 384, 448, 512, 640, 768, 896, 1_024, 1_152, 1_280, 1_536,
          1_920, 2_048, 2_304, 2_560, 2_688, 2_816, 2_823, 2_944, 3_072, 3_840, 4_096, 6_144, 7_680
      };

  /**
   * Maps MaxSampleRate index to sampling frequency in Hz. See ETSI TS 102 114 V1.6.1 (2019-08)
   * table 7-9.
   */
  private static final int[] SAMPLE_RATE_BY_INDEX =
      new int[] {
          8_000, 16_000, 32_000, 64_000, 128_000, 22_050, 44_100, 88_200, 176_400, 352_800, 12_000,
          24_000, 48_000, 96_000, 192_000, 384_000
      };

  /**
   * Maps RefClockCode (Reference Clock Code) to reference clock period. See ETSI TS 102 114 V1.6.1
   * (2019-08) table 7-3.
   */
  private static final int[] REF_CLOCK_FREQUENCY_BY_CODE = new int[] {32_000, 44_100, 48_000};

  /**
   * Index look-up table corresponding to the prefix code read from the bitstream. See ETSI TS 103
   * 491 V1.2.1 (2019-05) Table 5-2: ExtractVarLenBitFields.
   */
  private static final int[] VAR_INT_INDEX_TABLE = new int[] {0, 0, 0, 0, 1, 1, 2, 3};

  /**
   * Number of bits used to encode a field. See ETSI TS 103 491 V1.2.1 (2019-05) Table 5-2:
   * ExtractVarLenBitFields.
   */
  private static final int[] VAR_INT_BITS_USED = new int[] {1, 1, 1, 1, 2, 2, 3, 3};

  /**
   * Maps index to number of clock cycles in the bfase duration period. See ETSI TS 103 491 V1.2.1
   * (2019-05) Table 6-13.
   */
  private static final int[] BASE_DURATION_BY_INDEX = new int[] {512, 480, 384};

  /**
   * Returns whether a given integer matches a DTS Core sync word. Synchronization and storage modes
   * are defined in ETSI TS 102 114 V1.6.1 (2019-08), Section 5.3.
   *
   * @param word An integer.
   * @return Whether a given integer matches a DTS Core sync word.
   */
  public static boolean isCoreSyncWord(int word) {
    return word == SYNC_VALUE_BE
        || word == SYNC_VALUE_LE
        || word == SYNC_VALUE_14B_BE
        || word == SYNC_VALUE_14B_LE;
  }

  /**
   * Returns whether a given integer matches a DTS Extension Sub-stream sync word. Synchronization
   * and storage modes are defined in ETSI TS 102 114 V1.6.1 (2019-08), Section 7.5.
   *
   * @param word An integer.
   * @return Whether a given integer matches a DTS Extension Sub-stream sync word.
   */
  public static boolean isExtensionSubstreamSyncWord(int word) {
    return word == SYNC_VALUE_EXTSS_BE || word == SYNC_VALUE_EXTSS_LE;
  }

  /**
   * Returns whether a given integer matches a DTS UHD FTOC sync word. Synchronization and storage
   * modes are defined in ETSI TS 103 491 V1.2.1 (2019-05), Section 6.4.4.1.
   *
   * @param word An integer.
   * @return Whether a given integer matches a DTS UHD FTOC sync word.
   */
  public static boolean isUhdFtocSyncWord(int word) {
    return word == SYNC_VALUE_UHD_FTOC_SYNC_BE || word == SYNC_VALUE_UHD_FTOC_SYNC_LE;
  }

  /**
   * Returns whether a given integer matches a DTS UHD FTOC non-sync word. Synchronization and
   * storage modes are defined in ETSI TS 103 491 V1.2.1 (2019-05), Section 6.4.4.1.
   *
   * @param word An integer.
   * @return Whether a given integer matches a DTS UHD FTOC non-sync word.
   */
  public static boolean isUhdFtocNonSyncWord(int word) {
    return word == SYNC_VALUE_UHD_FTOC_NONSYNC_BE || word == SYNC_VALUE_UHD_FTOC_NONSYNC_LE;
  }

  /**
   * Returns the DTS format given {@code data} containing the DTS Core frame according to ETSI TS
   * 102 114 V1.6.1 (2019-08) subsections 5.3/5.4.
   *
   * @param frame The DTS Core frame to parse.
   * @param trackId The track identifier to set on the format.
   * @param language The language to set on the format.
   * @param drmInitData {@link DrmInitData} to be included in the format.
   * @return The DTS format parsed from data in the header.
   */
  public static Format parseDtsFormat(
      byte[] frame,
      @Nullable String trackId,
      @Nullable String language,
      @Nullable DrmInitData drmInitData) {
    ParsableBitArray frameBits = getNormalizedFrame(frame);
    frameBits.skipBits(32 + 1 + 5 + 1 + 7 + 14); // SYNC, FTYPE, SHORT, CPF, NBLKS, FSIZE
    int amode = frameBits.readBits(6);
    int channelCount = CHANNELS_BY_AMODE[amode];
    int sfreq = frameBits.readBits(4);
    int sampleRate = SAMPLE_RATE_BY_SFREQ[sfreq];
    int rate = frameBits.readBits(5);
    int bitrate =
        rate >= TWICE_BITRATE_KBPS_BY_RATE.length
            ? Format.NO_VALUE
            : TWICE_BITRATE_KBPS_BY_RATE[rate] * 1000 / 2;
    frameBits.skipBits(10); // MIX, DYNF, TIMEF, AUXF, HDCD, EXT_AUDIO_ID, EXT_AUDIO, ASPF
    channelCount += frameBits.readBits(2) > 0 ? 1 : 0; // LFF
    return new Format.Builder()
        .setId(trackId)
        .setSampleMimeType(MimeTypes.AUDIO_DTS)
        .setAverageBitrate(bitrate)
        .setChannelCount(channelCount)
        .setSampleRate(sampleRate)
        .setDrmInitData(drmInitData)
        .setLanguage(language)
        .build();
  }

  /**
   * Returns the number of audio samples represented by the given DTS Core frame.
   *
   * @param data The frame to parse.
   * @return The number of audio samples represented by the frame.
   */
  public static int parseDtsAudioSampleCount(byte[] data) {
    int nblks;
    switch (data[0]) {
      case FIRST_BYTE_LE:
        nblks = ((data[5] & 0x01) << 6) | ((data[4] & 0xFC) >> 2);
        break;
      case FIRST_BYTE_14B_LE:
        nblks = ((data[4] & 0x07) << 4) | ((data[7] & 0x3C) >> 2);
        break;
      case FIRST_BYTE_14B_BE:
        nblks = ((data[5] & 0x07) << 4) | ((data[6] & 0x3C) >> 2);
        break;
      default:
        // We blindly assume FIRST_BYTE_BE if none of the others match.
        nblks = ((data[4] & 0x01) << 6) | ((data[5] & 0xFC) >> 2);
    }
    return (nblks + 1) * 32;
  }

  /**
   * Like {@link #parseDtsAudioSampleCount(byte[])} but reads from a {@link ByteBuffer}. The
   * buffer's position is not modified.
   *
   * @param buffer The {@link ByteBuffer} from which to read.
   * @return The number of audio samples represented by the syncframe.
   */
  public static int parseDtsAudioSampleCount(ByteBuffer buffer) {
    if ((buffer.getInt(0) == SYNC_VALUE_UHD_FTOC_SYNC_LE)
        || (buffer.getInt(0) == SYNC_VALUE_UHD_FTOC_NONSYNC_LE)) {
      // Check for DTS:X Profile 2 sync or non sync word and return 1024 if found. This is the only
      // audio sample count that is used by DTS:X Streaming Encoder.
      return 1024;
    } else if (buffer.getInt(0) == SYNC_VALUE_EXTSS_LE) {
      // Check for DTS Express sync word and return 4096 if found. This is the only audio sample
      // count that is used by DTS Streaming Encoder.
      return 4096;
    }

    // See ETSI TS 102 114 subsection 5.4.1.
    int position = buffer.position();
    int nblks;
    switch (buffer.get(position)) {
      case FIRST_BYTE_LE:
        nblks = ((buffer.get(position + 5) & 0x01) << 6) | ((buffer.get(position + 4) & 0xFC) >> 2);
        break;
      case FIRST_BYTE_14B_LE:
        nblks = ((buffer.get(position + 4) & 0x07) << 4) | ((buffer.get(position + 7) & 0x3C) >> 2);
        break;
      case FIRST_BYTE_14B_BE:
        nblks = ((buffer.get(position + 5) & 0x07) << 4) | ((buffer.get(position + 6) & 0x3C) >> 2);
        break;
      default:
        // We blindly assume FIRST_BYTE_BE if none of the others match.
        nblks = ((buffer.get(position + 4) & 0x01) << 6) | ((buffer.get(position + 5) & 0xFC) >> 2);
    }
    return (nblks + 1) * 32;
  }

  /**
   * Returns the size in bytes of the given DTS Core frame.
   *
   * @param data The frame to parse.
   * @return The frame's size in bytes.
   */
  public static int getDtsFrameSize(byte[] data) {
    int fsize;
    boolean uses14BitPerWord = false;
    switch (data[0]) {
      case FIRST_BYTE_14B_BE:
        fsize = (((data[6] & 0x03) << 12) | ((data[7] & 0xFF) << 4) | ((data[8] & 0x3C) >> 2)) + 1;
        uses14BitPerWord = true;
        break;
      case FIRST_BYTE_LE:
        fsize = (((data[4] & 0x03) << 12) | ((data[7] & 0xFF) << 4) | ((data[6] & 0xF0) >> 4)) + 1;
        break;
      case FIRST_BYTE_14B_LE:
        fsize = (((data[7] & 0x03) << 12) | ((data[6] & 0xFF) << 4) | ((data[9] & 0x3C) >> 2)) + 1;
        uses14BitPerWord = true;
        break;
      default:
        // We blindly assume FIRST_BYTE_BE if none of the others match.
        fsize = (((data[5] & 0x03) << 12) | ((data[6] & 0xFF) << 4) | ((data[7] & 0xF0) >> 4)) + 1;
    }

    // If the frame is stored in 14-bit mode, adjust the frame size to reflect the actual byte size.
    return uses14BitPerWord ? fsize * 16 / 14 : fsize;
  }

  /**
   * Returns the DTS audio format given {@code data} containing the DTS-HD frame(containing only
   * Extension Sub-stream) according to ETSI TS 102 114 V1.6.1 (2019-08), Section 7.4/7.5.
   *
   * @param frame The DTS-HD frame(containing only Extension Sub-stream) to parse.
   * @return The DTS audio format parsed from data in the header.
   */
  public static DtsAudioFormat parseDtsHdFormat(byte[] frame) throws ParserException {
    ParsableBitArray frameBits = getNormalizedFrame(frame);
    frameBits.skipBits(32 + 8); // SYNCEXTSSH, UserDefinedBits

    int extensionSubstreamIndex = frameBits.readBits(2); // nExtSSIndex
    int headerBits;
    int extensionSubstreamFrameSizeBits;
    if (!frameBits.readBit()) { // bHeaderSizeType
      headerBits = 8; // nuBits4Header
      extensionSubstreamFrameSizeBits = 16; // nuBits4ExSSFsize
    } else {
      headerBits = 12; // nuBits4Header
      extensionSubstreamFrameSizeBits = 20; // nuBits4ExSSFsize
    }
    frameBits.skipBits(headerBits); // nuExtSSHeaderSize
    int extensionSubstreamFrameSize =
        frameBits.readBits(extensionSubstreamFrameSizeBits) + 1; // nuExtSSFsize

    int assetsCount; // nuNumAssets
    int referenceClockCode; // nuRefClockCode
    int extensionSubstreamFrameDurationCode; // nuExSSFrameDurationCode

    boolean staticFieldsPresent = frameBits.readBit(); // bStaticFieldsPresent
    if (staticFieldsPresent) {
      referenceClockCode = frameBits.readBits(2); // nuRefClockCode
      extensionSubstreamFrameDurationCode =
          512 * (frameBits.readBits(3) + 1); // nuExSSFrameDurationCode

      if (frameBits.readBit()) { // bTimeStampFlag
        frameBits.skipBits(32 + 4); // nuTimeStamp, nLSB
      }

      int audioPresentationsCount = frameBits.readBits(3) + 1; // nuNumAudioPresnt
      assetsCount = frameBits.readBits(3) + 1; // nuNumAssets
      if (audioPresentationsCount != 1 || assetsCount != 1) {
        throw ParserException.createForUnsupportedContainerFeature(
            /* message= */ "Multiple audio presentations or assets not supported");
      }

      int[] activeExtensionSubstreamMask = new int[8]; // nuActiveExSSMask
      for (int i = 0; i < audioPresentationsCount; i++) {
        activeExtensionSubstreamMask[i] = frameBits.readBits(extensionSubstreamIndex + 1);
      }

      for (int i = 0; i < audioPresentationsCount; i++) {
        for (int j = 0; j < extensionSubstreamIndex + 1; j++) {
          if (((activeExtensionSubstreamMask[i] >> j) & 0x1) == 1) {
            frameBits.skipBits(8); // nuActiveAssetMask
          }
        }
      }

      if (frameBits.readBit()) { // bMixMetadataEnbl
        frameBits.skipBits(2); // nuMixMetadataAdjLevel
        int mixerOutputMaskBits = (frameBits.readBits(2) + 1) << 2; // nuBits4MixOutMask
        int mixerOutputConfigurationCount = frameBits.readBits(2) + 1; // nuNumMixOutConfigs
        // Output Mixing Configuration Loop
        for (int i = 0; i < mixerOutputConfigurationCount; i++) {
          frameBits.skipBits(mixerOutputMaskBits); // nuMixOutChMask
        }
      }
    } else {
      assetsCount = 1;
      referenceClockCode = 0; // nuRefClockCode defaults to 0
      extensionSubstreamFrameDurationCode = 0; // nuExSSFrameDurationCode defaults to 0
    }

    for (int i = 0; i < assetsCount; i++) {
      frameBits.skipBits(extensionSubstreamFrameSizeBits); // nuAssetFsize
    }

    // Asset descriptor
    int maxSampleRate = 0;
    int channelCount = 0;
    for (int i = 0; i < assetsCount; i++) {
      frameBits.skipBits(9 + 3); // nuAssetDescriptFsize, nuAssetIndex
      if (staticFieldsPresent) {
        if (frameBits.readBit()) { // bAssetTypeDescrPresent
          frameBits.skipBits(4); // nuAssetTypeDescriptor
        }
        if (frameBits.readBit()) { // bLanguageDescrPresent
          frameBits.skipBits(24); // LanguageDescriptor
        }
        if (frameBits.readBit()) { // bInfoTextPresent
          int infoTextByteSize = frameBits.readBits(10) + 1; // nuInfoTextByteSize
          frameBits.skipBytes(infoTextByteSize); // InfoTextString
        }
        frameBits.skipBits(5); // nuBitResolution
        maxSampleRate = SAMPLE_RATE_BY_INDEX[frameBits.readBits(4)]; // nuMaxSampleRate
        channelCount = frameBits.readBits(8) + 1; // nuTotalNumChs
      }
    }

    // Number of audio samples in a compressed DTS frame
    int sampleCount =
        extensionSubstreamFrameDurationCode
            * (maxSampleRate / REF_CLOCK_FREQUENCY_BY_CODE[referenceClockCode]);
    return new DtsAudioFormat(
        MimeTypes.AUDIO_DTS_EXPRESS,
        channelCount,
        maxSampleRate,
        extensionSubstreamFrameSize,
        sampleCount,
        /* bitrate= */ 0);
  }

  /**
   * Returns the size of frame header in a DTS-HD frame(containing only Extension Sub-stream).
   * This function will parse upto 55 bits from the input bitstream.
   *
   * @param frame A DTS-HD frame(only Extension Sub-stream) containing at least 55 bits from the
   * very beginning.
   * @return Size of the DTS-HD frame header in bytes.
   */
  public static int parseDtsHdHeaderSize(byte[] frame) {
    ParsableBitArray frameBits = getNormalizedFrame(frame);
    frameBits.skipBits(32 + 8 + 2); // SYNCEXTSSH, UserDefinedBits, nExtSSIndex
    // Unpack the num of bits to be used to read header size
    int headerBits = frameBits.readBit() ? 12 : 8; // bHeaderSizeType
    // Unpack the substream header size
    return frameBits.readBits(headerBits) + 1; // nuExtSSHeaderSize
  }

  /**
   * Returns the DTS audio format given {@code data} containing the DTS-UHD(Profile 2) frame
   * according to ETSI TS 103 491 V1.2.1 (2019-05), Section 6.4.3.
   *
   * @param frame            The DTS-UHD frame to parse.
   * @param dtsUhdStateParam Holds the values extracted in the last FTOC sync-frame.
   * @return The DTS audio format parsed from data in the header.
   */
  public static DtsAudioFormat parseDtsUhdFormat(byte[] frame,
      DtsUhdState dtsUhdStateParam) throws ParserException {
    ParsableBitArray frameBits = getNormalizedFrame(frame);
    int syncWord = frameBits.readBits(32);
    boolean syncFrameFlag = syncWord == SYNC_VALUE_UHD_FTOC_SYNC_BE;

    int[] fieldLenTable1 = new int[] {5, 8, 10, 12};
    int ftocPayloadInBytes =
        parseUnsignedVarInt(frameBits, fieldLenTable1, /* extractAndAddFlag= */ true) + 1;

    int sampleRate = 0;
    int sampleCount = 0;
    if (syncFrameFlag) {
      // ETSI TS 103 491 V1.2.1, Section 6.4.6.1.
      if (!frameBits.readBit()) { // m_bFullChannelBasedMixFlag
        throw ParserException.createForUnsupportedContainerFeature(
            /* message= */ "Only supports full channel mask-based audio presentation");
      }
      // ETSI TS 103 491 V1.2.1, Section 6.4.6.2.
      if (!extractAndCheckCRC16(frame, ftocPayloadInBytes)) {
        throw ParserException.createForMalformedContainer(
            /* message= */ "CRC check failed",
            /* cause= */ null);
      }
      int baseDuration = BASE_DURATION_BY_INDEX[frameBits.readBits(2)]; // m_unBaseDuration
      int frameDuration = baseDuration * frameBits.readBits(3) + 1; // m_unFrameDuration
      int clockRateIndex = frameBits.readBits(2); // m_unClockRateInHz
      int clockRateHertz = 0;
      switch (clockRateIndex) {
        case 0:
          clockRateHertz = 32_000;
          break;
        case 1:
          clockRateHertz = 44_100;
          break;
        case 2:
          clockRateHertz = 48_000;
          break;
        default: // this would be an error
          throw ParserException.createForMalformedContainer(
              /* message= */ "Unsupported clock rate index in DTS UHD header: " + clockRateIndex,
              /* cause= */ null);
      }
      // Skip time stamp information if present, See section 5.2.3.2.
      if (frameBits.readBit()) { // m_TimeStamp.m_bUpdateFlag
        frameBits.skipBits(32 + 4); // m_TimeStamp
      }
      int sampleRateMultiplier = (1 << frameBits.readBits(2));
      sampleRate = clockRateHertz * sampleRateMultiplier; // m_unAudioSamplRate
      sampleCount = frameDuration * sampleRateMultiplier; // ETSI TS 103 491 V1.2.1, Section 6.4.6.9
    }

    // ETSI TS 103 491 V1.2.1, Section 6.4.6.1.
    // m_bFullChannelBasedMixFlag == true as we throw unsupported container feature otherwise.
    int chunkPayloadBytes = 0;
    int numOfMdChunks = syncFrameFlag ? 1 : 0; // Metadata chunks
    int[] fieldLenTable2 = new int[] {6, 9, 12, 15};
    for (int i = 0; i < numOfMdChunks; i++) {
      int mdChunkSize = parseUnsignedVarInt(frameBits, fieldLenTable2, /* extractAndAddFlag= */ true);
      if (mdChunkSize > 32767) {
        throw ParserException.createForMalformedContainer(
            /* message= */ "Unsupported metadata chunk size in DTS UHD header: " + mdChunkSize,
            /* cause= */ null);
      }
      chunkPayloadBytes += mdChunkSize;
    }

    // See ETSI TS 103 491 V1.2.1, Section 6.4.14.4.
    // m_bFullChannelBasedMixFlag == true as we throw unsupported container feature otherwise.
    int numAudioChunks = 1;
    int audioChunkId;
    int[] fieldLenTable3 = new int[] {2, 4, 6, 8};
    int[] fieldLenTable4 = new int[] {9, 11, 13, 16};
    for (int i = 0; i < numAudioChunks; i++) {
      // If syncFrameFlag is true the audio chunk ID will be present
      if (syncFrameFlag) {
        audioChunkId = parseUnsignedVarInt(frameBits, fieldLenTable3,
            /* extractAndAddFlag= */ true);
        dtsUhdStateParam.storedUhdAudioChunkId = audioChunkId;
      } else {
        // Get the stored audio chunk ID
        audioChunkId = dtsUhdStateParam.storedUhdAudioChunkId < 256 /* invalid chunk ID */ ?
            dtsUhdStateParam.storedUhdAudioChunkId : 0;
      }
      int audioChunkSize =
          audioChunkId != 0
              ? parseUnsignedVarInt(frameBits, fieldLenTable4, /* extractAndAddFlag= */ true)
              : 0;
      if (audioChunkSize > 65535) {
        throw ParserException.createForMalformedContainer(
            /* message= */ "Unsupported audio chunk size in DTS UHD header: " + audioChunkSize,
            /* cause= */ null);
      }
      chunkPayloadBytes += audioChunkSize;
    }

    int frameSize = ftocPayloadInBytes + chunkPayloadBytes;
    return new DtsAudioFormat(
        MimeTypes.AUDIO_DTS_X,
        // To determine the actual number of channels from a bit stream, we need to read the
        // metadata chunk bytes. If defining a constant channel count causes problems, we can
        // consider adding additional parsing logic for UHD frames.
        DTS_UHD_CHANNEL_COUNT,
        sampleRate,
        frameSize,
        sampleCount,
        /* bitrate= */ 0);
  }

  /**
   * Returns the size of frame header in a DTS-UHD(Profile 2) frame by parsing a few bytes of data
   * (minimum 7 bytes) from the input bitstream.
   *
   * @param frame The initial 7 bytes(minimum) of a DTS-UHD frame.
   * @return Size of the DTS-UHD frame header in bytes.
   */
  public static int parseDtsUhdHeaderSize(byte[] frame) {
    ParsableBitArray frameBits = getNormalizedFrame(frame);
    frameBits.skipBits(32); // SYNC
    int[] fieldLenTable = new int[] {5, 8, 10, 12};
    return parseUnsignedVarInt(frameBits, fieldLenTable, /* extractAndAddFlag= */ true) + 1;
  }

  // Helper function for the DTS UHD header parsing. Used to extract a field of variable length.
  // See ETSI TS 103 491 V1.2.1, Section 5.2.3.1.
  private static int parseUnsignedVarInt(
      ParsableBitArray frameBits, int[] lengths, boolean extractAndAddFlag) {
    int code = frameBits.readBits(3); // uncode
    int currentPosition = frameBits.getPosition();
    int index = VAR_INT_INDEX_TABLE[code];
    int unusedBits = 3 - VAR_INT_BITS_USED[code];
    frameBits.setPosition(currentPosition - unusedBits); // Rewind unused bits

    if (lengths[index] <= 0) {
      return 0;
    }

    int value = 0;
    if (extractAndAddFlag) {
      for (int i = 0; i < index; i++) {
        value += (1 << lengths[i]);
      }
    }
    value += frameBits.readBits(lengths[index]);
    return value;
  }

  // Helper functions for CRC checking of UHD FTOC header
  private static int crc16Update4BitsFast(short val, int mCRC16Register) {
    int[] mCRC16Lookup = new int[] {
        0x0000, 0x01021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF };
    short t; // This will be handled as unsigned 8 bit data
    // Step one, extract the most significant 4 bits of the CRC register
    t = (short)((mCRC16Register >> 12) & 0xFF);
    // XOR in the Message Data into the extracted bits
    t = (short)((t ^ val)& 0xFF);
    // Shift the CRC register left 4 bits
    mCRC16Register = (mCRC16Register << 4) & 0xFFFF; // Handle as 16 bit, discard any sign extension
    // Do the table look-ups and XOR the result into the CRC tables
    mCRC16Register = (mCRC16Register ^ mCRC16Lookup[t]) & 0xFFFF;

    return mCRC16Register;
  }

  // Process one Message Byte to update the current CRC Value
  private static int crc16Update(short val, int mCRC16Register) {
    // Process 4 bits of the message to update the CRC Value.
    // Note that the data will be in the low nibble of val.
    mCRC16Register = crc16Update4BitsFast((short)(val >> 4), mCRC16Register); // High nibble first
    mCRC16Register = crc16Update4BitsFast((short)(val & 0x0F), mCRC16Register); // Low nibble
    return mCRC16Register;
  }

  // Calculate the CRC value from the frame header
  private static int calculateCRC16(byte[] frame, int length) {
    // Initialize the CRC to 0xFFFF as per specification
    int mCRC16Register = 0xFFFF; // This will be handled as unsigned 16 bit data
    for (int counter = 0; counter < length; counter++) {
      // Process one byte from the frame header
      mCRC16Register = crc16Update((short)(frame[counter] & 0xFF), mCRC16Register);
    }
    return mCRC16Register;
  }

  // Check if calculated and extracted CRC16 words match
  private static boolean extractAndCheckCRC16(byte[] frame, int sizeInBytes) {
    // Calculate CRC
    short calcCRC16 = (short)calculateCRC16(frame, sizeInBytes - 2);
    // Extract the encoded CRC value from the header
    short extractedCRC16 = (short)((((frame[sizeInBytes - 2] << 8)) & 0xFFFF) |
        (frame[sizeInBytes - 1] & 0xFF));
    return (calcCRC16 == extractedCRC16) ? true : false;
  }

  private static ParsableBitArray getNormalizedFrame(byte[] frame) {
    if (frame[0] == FIRST_BYTE_BE
        || frame[0] == FIRST_BYTE_EXTSS_BE
        || frame[0] == FIRST_BYTE_UHD_FTOC_SYNC_BE
        || frame[0] == FIRST_BYTE_UHD_FTOC_NONSYNC_BE) {
      // The frame is already 16-bit mode, big endian.
      return new ParsableBitArray(frame);
    }
    // Data is not normalized, but we don't want to modify frame.
    frame = Arrays.copyOf(frame, frame.length);
    if (isLittleEndianFrameHeader(frame)) {
      // Change endianness.
      for (int i = 0; i < frame.length - 1; i += 2) {
        byte temp = frame[i];
        frame[i] = frame[i + 1];
        frame[i + 1] = temp;
      }
    }
    ParsableBitArray frameBits = new ParsableBitArray(frame);
    if (frame[0] == (byte) (SYNC_VALUE_14B_BE >> 24)) {
      // Discard the 2 most significant bits of each 16 bit word.
      ParsableBitArray scratchBits = new ParsableBitArray(frame);
      while (scratchBits.bitsLeft() >= 16) {
        scratchBits.skipBits(2);
        frameBits.putInt(scratchBits.readBits(14), 14);
      }
    }
    frameBits.reset(frame);
    return frameBits;
  }

  private static boolean isLittleEndianFrameHeader(byte[] frameHeader) {
    return frameHeader[0] == FIRST_BYTE_LE
        || frameHeader[0] == FIRST_BYTE_14B_LE
        || frameHeader[0] == FIRST_BYTE_EXTSS_LE
        || frameHeader[0] == FIRST_BYTE_UHD_FTOC_SYNC_LE
        || frameHeader[0] == FIRST_BYTE_UHD_FTOC_NONSYNC_LE;
  }

  private DtsUtil() {}
}
