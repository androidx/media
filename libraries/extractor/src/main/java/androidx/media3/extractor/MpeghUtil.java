/***************************************************************************

 Fraunhofer hereby grants to Google free of charge a worldwide, perpetual,
 irrevocable, non-exclusive copyright license with the right to sublicense
 through multiple tiers to use, copy, distribute, modify and create
 derivative works of the Software Patches for Exoplayer in source code form
 and/or object code versions of the software. For the avoidance of doubt,
 this license does not include any license to any Fraunhofer patents or any
 third-party patents. Since the license is granted without any charge,
 Fraunhofer provides the Software Patches for Exoplayer, in accordance with
 the laws of the Federal Republic of Germany, on an "as is" basis, WITHOUT
 WARRANTIES or conditions of any kind, either express or implied, including,
 without limitation, any warranties or conditions of title, non-infringement,
 merchantability, or fitness for a particular purpose.

 For the purpose of clarity, the provision of the Software Patches for
 Exoplayer by Fraunhofer and the use of the same by Google shall be subject
 solely to the license stated above.

 ***************************************************************************/
package androidx.media3.extractor;

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Utility methods for parsing MPEG-H frames, which are access units in MPEG-H bitstreams. */
@UnstableApi
public final class MpeghUtil {

  public static class FrameInfo {

    public boolean containsConfig;
    public boolean configChanged;
    public int standardFrameSamples;
    public int samplingRate;
    public int frameSamples;
    public int frameBytes;
    public long mainStreamLabel;

    public int mpegh3daProfileLevelIndication;
    public byte[] compatibleSetIndication;

    public FrameInfo() {
      this.standardFrameSamples = Format.NO_VALUE;
      this.samplingRate = Format.NO_VALUE;
      this.frameSamples = Format.NO_VALUE;
      this.frameBytes = Format.NO_VALUE;
      this.mainStreamLabel = Format.NO_VALUE;
      this.mpegh3daProfileLevelIndication = Format.NO_VALUE;
      this.compatibleSetIndication = new byte[0];
    }

    public FrameInfo(boolean containsConfig, boolean configChanged, int standardFrameSamples,
        int samplingRate, int frameSamples, int frameBytes, long mainStreamLabel,
        int mpegh3daProfileLevelIndication, byte[] compatibleSetIndication) {
      this.containsConfig = containsConfig;
      this.configChanged = configChanged;
      this.standardFrameSamples = standardFrameSamples;
      this.samplingRate = samplingRate;
      this.frameSamples = frameSamples;
      this.frameBytes = frameBytes;
      this.mainStreamLabel = mainStreamLabel;
      this.mpegh3daProfileLevelIndication = mpegh3daProfileLevelIndication;
      if (compatibleSetIndication.length > 0) {
        this.compatibleSetIndication = Arrays.copyOf(compatibleSetIndication,
            compatibleSetIndication.length);
      }
    }

    public void reset() {
      containsConfig = false;
      configChanged = false;
      standardFrameSamples = Format.NO_VALUE;
      samplingRate = Format.NO_VALUE;
      frameBytes = Format.NO_VALUE;
      frameSamples = Format.NO_VALUE;
      mainStreamLabel = Format.NO_VALUE;
      mpegh3daProfileLevelIndication = Format.NO_VALUE;
      compatibleSetIndication = new byte[0];
    }
  }

  public static class ParseException extends IOException {

    public final @State int parseState;

    public static ParseException createForNotEnoughData() {
      return new ParseException(null, null, STATE_END_OUTPUT);
    }

    public static ParseException createForUnsupportedSubstream(@Nullable String message) {
      return new ParseException(message, null, STATE_SUBSTREAM_UNSUPPORTED);
    }

    public static ParseException createForParsingError(@Nullable String message) {
      return new ParseException(message, null, STATE_PARSE_ERROR);
    }

    protected ParseException(
        @Nullable String message,
        @Nullable Throwable cause,
        @State int parseState) {
      super(message, cause);
      this.parseState = parseState;
    }
  }

  /**
   * MHAS packet types. See ISO_IEC_23008-3;2022, 14.3.1, Table 226.
   * One of {@link #PACTYP_FILLDATA}, {@link #PACTYP_MPEGH3DACFG}, {@link #PACTYP_MPEGH3DAFRAME},
   * {@link #PACTYP_AUDIOSCENEINFO}, {@link #PACTYP_SYNC}, {@link #PACTYP_SYNCGAP},
   * {@link #PACTYP_MARKER}, {@link #PACTYP_CRC16}, {@link #PACTYP_CRC32},
   * {@link #PACTYP_DESCRIPTOR}, {@link #PACTYP_USERINTERACTION}, {@link #PACTYP_LOUDNESS_DRC},
   * {@link #PACTYP_BUFFERINFO}, {@link #PACTYP_GLOBAL_CRC16}, {@link #PACTYP_GLOBAL_CRC32},
   * {@link #PACTYP_AUDIOTRUNCATION}, {@link #PACTYP_GENDATA}, {@link #PACTYPE_EARCON},
   * {@link #PACTYPE_PCMCONFIG}, {@link #PACTYPE_PCMDATA}, {@link #PACTYP_LOUDNESS}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
      PACTYP_FILLDATA,
      PACTYP_MPEGH3DACFG,
      PACTYP_MPEGH3DAFRAME,
      PACTYP_AUDIOSCENEINFO,
      PACTYP_SYNC,
      PACTYP_SYNCGAP,
      PACTYP_MARKER,
      PACTYP_CRC16,
      PACTYP_CRC32,
      PACTYP_DESCRIPTOR,
      PACTYP_USERINTERACTION,
      PACTYP_LOUDNESS_DRC,
      PACTYP_BUFFERINFO,
      PACTYP_GLOBAL_CRC16,
      PACTYP_GLOBAL_CRC32,
      PACTYP_AUDIOTRUNCATION,
      PACTYP_GENDATA,
      PACTYPE_EARCON,
      PACTYPE_PCMCONFIG,
      PACTYPE_PCMDATA,
      PACTYP_LOUDNESS
  })
  private @interface MhasPacketType {}

  private static final int PACTYP_FILLDATA = 0;
  private static final int PACTYP_MPEGH3DACFG = 1;
  private static final int PACTYP_MPEGH3DAFRAME = 2;
  private static final int PACTYP_AUDIOSCENEINFO = 3;
  private static final int PACTYP_SYNC = 6;
  private static final int PACTYP_SYNCGAP = 7;
  private static final int PACTYP_MARKER = 8;
  private static final int PACTYP_CRC16 = 9;
  private static final int PACTYP_CRC32 = 10;
  private static final int PACTYP_DESCRIPTOR = 11;
  private static final int PACTYP_USERINTERACTION = 12;
  private static final int PACTYP_LOUDNESS_DRC = 13;
  private static final int PACTYP_BUFFERINFO = 14;
  private static final int PACTYP_GLOBAL_CRC16 = 15;
  private static final int PACTYP_GLOBAL_CRC32 = 16;
  private static final int PACTYP_AUDIOTRUNCATION = 17;
  private static final int PACTYP_GENDATA = 18;
  private static final int PACTYPE_EARCON = 19;
  private static final int PACTYPE_PCMCONFIG = 20;
  private static final int PACTYPE_PCMDATA = 21;
  private static final int PACTYP_LOUDNESS = 22;

  /**
   * Represents the parsing state. One of {@link #STATE_END_OUTPUT},
   * {@link #STATE_PARSE_ERROR}, {@link #STATE_SUBSTREAM_UNSUPPORTED}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
      STATE_END_OUTPUT,
      STATE_PARSE_ERROR,
      STATE_SUBSTREAM_UNSUPPORTED
  })
  private @interface State {}

  private static final int STATE_END_OUTPUT = 0;
  private static final int STATE_PARSE_ERROR = 1;
  private static final int STATE_SUBSTREAM_UNSUPPORTED = 2;

  /** See ISO_IEC_23003-3;2020, 6.1.1.1, Table 72. */
  private static final int[] SAMPLING_RATE_TABLE =
      new int[]{
          96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
          0, 0, 57600, 51200, 40000, 38400, 34150, 28800, 25600, 20000, 19200, 17075, 14400, 12800,
          9600, 0, 0, 0, 0
      };

  /** See ISO_IEC_23003-3;2020, 6.1.1.1, Table 75. */
  private static final int[] OUTPUT_FRAMELENGTH_TABLE =
      new int[]{
          768, 1024, 2048, 2048, 4096, 0, 0, 0
      };

  /** See ISO_IEC_23003-3;2020, 6.1.1.1, Table 75. */
  private static final int[] SBR_RATIO_INDEX_TABLE =
      new int[]{
          0, 0, 2, 3, 1
      };

  /** See ISO_IEC_23003-8;2022, 14.4.4. */
  private static final int MHAS_SYNCPACKET = 0xC001A5;

  /**
   * Validates that the provided number of bits is available in the bit array.
   *
   * @param data The byte array to parse.
   * @param numBits The number of bits to check for.
   * @throws {@link ParseException} if not enough bits are available.
   */
  public static void validateBitsAvailability(ParsableBitArray data, int numBits)
      throws ParseException {
    if (data.bitsLeft() < numBits) {
      throw ParseException.createForNotEnoughData();
    }
  }

  /**
   * Finds the start position of the MHAS sync packet in the provided data buffer.
   * See ISO_IEC_23008-3;2022, 14.4.4.
   *
   * @param data The byte array to parse.
   * @return Byte index in data of the MHAS sync packet on success, {@link C#INDEX_UNSET} on failure.
   */
  public static int findSyncPacket(ParsableByteArray data) {
    int startPos = data.getPosition();
    int syncPacketBytePos = C.INDEX_UNSET;
    while (data.bytesLeft() >= 3) {
      int syncword = data.readUnsignedInt24();
      if (syncword == MHAS_SYNCPACKET) {
        syncPacketBytePos = data.getPosition() - 3;
        break;
      }
      data.skipBytes(-2);
    }

    data.setPosition(startPos);
    return syncPacketBytePos;
  }

  /**
   * Checks if a complete MHAS frame could be parsed by calculating if enough data is available
   * in the provided ParsableBitArray.
   *
   * @param data The bit array to parse.
   * @return Whether a complete MHAS frame could be parsed.
   */
  public static boolean canParseFrame(ParsableBitArray data) {
    boolean retVal = false;
    int dataPos = data.getPosition();
    while (true) {
      MhasPacketHeader header;
      try {
        header = parseMhasPacketHeader(data);
      } catch (ParseException e) {
        // There is not enough data available to parse the MHAS packet header.
        break;
      }
      if (data.bitsLeft() < header.packetLength * 8) {
        // There is not enough data available to parse the current MHAS packet.
        break;
      }
      data.skipBytes(header.packetLength);

      if (header.packetType == PACTYP_MPEGH3DAFRAME) {
        // An mpegh3daFrame packet has been found which signals the end of the MHAS frame.
        retVal = true;
        break;
      }
    }
    data.setPosition(dataPos);
    return retVal;
  }

  /**
   * Parses the necessary info of an MPEG-H frame into the FrameInfo structure.
   *
   * @param data The bit array to parse, positioned at the start of the MHAS frame.
   * @param prevFrameInfo A previously obtained FrameInfo.
   * @return {@link FrameInfo} of the current frame.
   * @throws {@link ParseException} if parsing failed.
   */
  public static FrameInfo parseFrame(ParsableBitArray data, FrameInfo prevFrameInfo)
      throws ParseException {
    int nBitsIns;
    int standardFrameSamples = prevFrameInfo.standardFrameSamples;
    int samplingFrequency = prevFrameInfo.samplingRate;
    boolean frameFound = false;
    boolean configFound = false;
    boolean configChanged = false;
    int truncationSamples = 0;
    long mainStreamLabel = Format.NO_VALUE;
    int mpegh3daProfileLevelIndication = Format.NO_VALUE;
    byte[] compatibleSetIndication = new byte[0];

    nBitsIns = data.bitsLeft();

    if (nBitsIns == 0) {
      throw ParseException.createForNotEnoughData();
    }
    if (nBitsIns % 8 != 0) {
      throw ParseException.createForParsingError("The input data buffer is not Byte aligned.");
    }

    do {
      // parse MHAS packet header
      MhasPacketHeader packetHeader = parseMhasPacketHeader(data);
      if (packetHeader.packetLabel > 0x10) {
        throw ParseException.createForUnsupportedSubstream(
            "Contains sub-stream with label " + packetHeader.packetLabel);
      }

      // check if the complete packet could be parsed
      validateBitsAvailability(data, packetHeader.packetLength * 8);
      int dataPos = data.getPosition();

      switch (packetHeader.packetType) {
        case PACTYP_MPEGH3DACFG:
          if (packetHeader.packetLabel == 0) {
            throw ParseException.createForParsingError(
                "mpegh3daConfig packet with unsupported packet label " + packetHeader.packetLabel);
          }

          // we already found a mpegh3daConfig
          if (configFound) {
            throw ParseException.createForParsingError("found a second mpegh3daConfig packet");
          }
          configFound = true;

          // check for config change
          if (packetHeader.packetLabel != prevFrameInfo.mainStreamLabel) {
            configChanged = true;
          }
          // save new packet label
          mainStreamLabel = packetHeader.packetLabel;

          // parse the mpegh3daConfig
          Mpegh3daConfig mpegh3daConfig = parseMpegh3daConfig(data);

          // get the necessary data from mpegh3daConfig
          samplingFrequency = mpegh3daConfig.samplingFrequency;
          standardFrameSamples = mpegh3daConfig.standardFrameSamples;
          mpegh3daProfileLevelIndication = mpegh3daConfig.mpegh3daProfileLevelIndication;
          if (mpegh3daConfig.compatibleProfileLevelSet != null && mpegh3daConfig.compatibleProfileLevelSet.length > 0) {
            compatibleSetIndication = mpegh3daConfig.compatibleProfileLevelSet;
          }

          data.setPosition(dataPos);
          data.skipBits(packetHeader.packetLength * 8);
          break;

        case PACTYP_AUDIOTRUNCATION:
          if (packetHeader.packetLabel == 0) {
            throw ParseException.createForParsingError(
                "audioTruncation packet with unsupported packet label " + packetHeader.packetLabel);
          }

          truncationSamples = parseAudioTruncationInfo(data);
          if (truncationSamples > standardFrameSamples) {
            throw ParseException.createForParsingError("truncation size is too big " + truncationSamples);
          }

          data.setPosition(dataPos);
          data.skipBits(packetHeader.packetLength * 8);
          break;

        case PACTYP_MPEGH3DAFRAME:
          if (packetHeader.packetLabel == 0) {
            throw ParseException.createForParsingError(
                "mpegh3daFrame packet with unsupported packet label " + packetHeader.packetLabel);
          }

          if (!configFound) {
            mainStreamLabel = prevFrameInfo.mainStreamLabel;
          }

          // check packet label
          if (packetHeader.packetLabel != mainStreamLabel) {
            throw ParseException.createForParsingError(
                "mpegh3daFrame packet does not belong to main stream");
          }
          frameFound = true;
          data.skipBits(packetHeader.packetLength * 8);
          break;

        default:
          data.skipBits(packetHeader.packetLength * 8);
          break;
      }

      if (data.bitsLeft() % 8 != 0) {
        throw ParseException.createForParsingError("The data buffer is not Byte aligned after parsing.");
      }

    } while (!frameFound);

    int parsedBytes = (nBitsIns - data.bitsLeft()) / 8;

    if (samplingFrequency <= 0) {
      throw ParseException.createForParsingError(
          "unsupported sampling frequency " + samplingFrequency);
    }

    if (standardFrameSamples <= 0) {
      throw ParseException.createForParsingError("unsupported value of standardFrameSamples " + standardFrameSamples);
    }

    return new FrameInfo(configFound, configChanged, standardFrameSamples, samplingFrequency,
        standardFrameSamples - truncationSamples, parsedBytes, mainStreamLabel,
        mpegh3daProfileLevelIndication, compatibleSetIndication);
  }

  /**
   * Parses an MHAS packet header.
   * See ISO_IEC_23008-3;2022, 14.2.1, Table 222.
   *
   * @param data The bit array to parse.
   * @return The {@link MhasPacketHeader} info.
   * @throws {@link ParseException} if parsing failed, i.e. there is not enough data available.
   */
  private static MhasPacketHeader parseMhasPacketHeader(ParsableBitArray data) throws ParseException {
    @MhasPacketType int packetType = (int) readEscapedValue(data, 3, 8, 8);
    long packetLabel = readEscapedValue(data, 2, 8, 32);
    int packetLength = (int) readEscapedValue(data, 11, 24, 24);
    return new MhasPacketHeader(packetType, packetLabel, packetLength);
  }

  /**
   * Obtains the sampling rate of the current MPEG-H frame.
   *
   * @param data The bit array holding the bits to be parsed.
   * @return The sampling frequency.
   * @throws {@link ParseException} if sampling frequency could not be obtained.
   */
  public static int getSamplingFrequency(ParsableBitArray data) throws ParseException {
    int sampleRate;

    validateBitsAvailability(data, 5);
    int idx = data.readBits(5);

    if (idx == 0x1F) {
      validateBitsAvailability(data, 24);
      sampleRate = data.readBits(24);
    } else {
      sampleRate = SAMPLING_RATE_TABLE[idx];
    }
    return sampleRate;
  }

  /**
   * Obtains an escaped value from an MPEG-H bit stream.
   * See ISO_IEC_23003-3;2020, 5.2, Table 19.
   *
   * @param data  The bit array to be parsed.
   * @param bits1 number of bits to be parsed.
   * @param bits2 number of bits to be parsed.
   * @param bits3 number of bits to be parsed.
   * @return The escaped value.
   * @throws {@link ParseException} if parsing failed.
   */
  public static long readEscapedValue(ParsableBitArray data, int bits1, int bits2, int bits3)
      throws ParseException {
    validateBitsAvailability(data, bits1);
    long value = data.readBitsToLong(bits1);

    if (value == (1L << bits1) - 1) {
      validateBitsAvailability(data, bits2);
      long valueAdd = data.readBitsToLong(bits2);
      value += valueAdd;

      if (valueAdd == (1L << bits2) - 1) {
        validateBitsAvailability(data, bits3);
        valueAdd = data.readBitsToLong(bits3);
        value += valueAdd;
      }
    }
    return value;
  }

  /**
   * Obtains the necessary info of the Mpegh3daConfig from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 5.2.2.1, Table 15.
   *
   * @param data  The bit array to be parsed.
   * @return The {@link Mpegh3daConfig}.
   * @throws {@link ParseException} if parsing failed.
   */
  private static Mpegh3daConfig parseMpegh3daConfig(ParsableBitArray data) throws ParseException {
    Mpegh3daConfig mpegh3daConfig = new Mpegh3daConfig();
    validateBitsAvailability(data, 8);
    mpegh3daConfig.mpegh3daProfileLevelIndication = data.readBits(8);

    int usacSamplingFrequency = getSamplingFrequency(data);

    validateBitsAvailability(data, 5);
    int coreSbrFrameLengthIndex = data.readBits(3);
    data.skipBits(2); // cfg_reserved(1), receiverDelayCompensation(1)

    int outputFrameLength = OUTPUT_FRAMELENGTH_TABLE[coreSbrFrameLengthIndex];
    int sbrRatioIndex = SBR_RATIO_INDEX_TABLE[coreSbrFrameLengthIndex];

    parseSpeakerConfig3d(data); // referenceLayout
    int numSignals = parseSignals3d(data); // frameworkConfig3d
    parseMpegh3daDecoderConfig(data, numSignals, sbrRatioIndex); // decoderConfig

    validateBitsAvailability(data, 1);
    if (data.readBit()) { // usacConfigExtensionPresent
      // Mpegh3daConfigExtension
      int numConfigExtensions = (int) readEscapedValue(data, 2, 4, 8) + 1;
      for (int confExtIdx = 0; confExtIdx < numConfigExtensions; confExtIdx++) {
        int usacConfigExtType = (int) readEscapedValue(data, 4, 8, 16);
        int usacConfigExtLength = (int) readEscapedValue(data, 4, 8, 16);

        if (usacConfigExtType == 7 /*ID_CONFIG_EXT_COMPATIBLE_PROFILELVL_SET*/) {
          validateBitsAvailability(data, 8);
          int numCompatibleSets = data.readBits(4) + 1;
          data.skipBits(4); // reserved
          mpegh3daConfig.compatibleProfileLevelSet = new byte[numCompatibleSets];
          for (int idx = 0; idx < numCompatibleSets; idx++) {
            validateBitsAvailability(data, 8);
            mpegh3daConfig.compatibleProfileLevelSet[idx] = (byte) data.readBits(8);
          }
        } else {
          validateBitsAvailability(data, 8 * usacConfigExtLength);
          data.skipBits(8 * usacConfigExtLength);
        }
      }
    }

    // Get the resampling ratio and adjust the samplingFrequency and the standardFrameSamples
    // accordingly. See ISO_IEC_23008-3;2022, 4.8.2, Table 10
    double resamplingRatio;
    switch (usacSamplingFrequency) {
      case 96000:
      case 88200:
      case 48000:
      case 44100:
        resamplingRatio = 1;
        break;
      case 64000:
      case 58800:
      case 32000:
      case 29400:
        resamplingRatio = 1.5;
        break;
      case 24000:
      case 22050:
        resamplingRatio = 2;
        break;
      case 16000:
      case 14700:
        resamplingRatio = 3;
        break;
      default:
        throw ParseException.createForParsingError(
            "unsupported sampling rate " + usacSamplingFrequency);
    }
    mpegh3daConfig.samplingFrequency = (int) (usacSamplingFrequency * resamplingRatio);
    mpegh3daConfig.standardFrameSamples = (int) (outputFrameLength * resamplingRatio);

    return mpegh3daConfig;
  }


  /**
   * Obtains the number of truncated samples of the AudioTruncationInfo from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 14.2.2, Table 225.
   *
   * @param data  The bit array to be parsed.
   * @return The number of truncated samples.
   * @throws {@link ParseException} if parsing failed.
   */
  private static int parseAudioTruncationInfo(ParsableBitArray data) throws ParseException {
    int truncationSamples = 0;
    validateBitsAvailability(data, 16);
    boolean isActive = data.readBit();
    data.skipBits(2); // reserved(1), truncFromBegin(1)
    int trunc = data.readBits(13);
    if (isActive) {
      truncationSamples = trunc;
    }
    return truncationSamples;
  }


  /**
   * Parses the SpeakerConfig3d from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 5.2.2.2, Table 18.
   *
   * @param data  The bit array to be parsed.
   * @throws {@link ParseException} if parsing failed.
   */
  private static void parseSpeakerConfig3d(ParsableBitArray data) throws ParseException {
    validateBitsAvailability(data, 2);
    int speakerLayoutType = data.readBits(2);
    if (speakerLayoutType == 0) {
      validateBitsAvailability(data, 6);
      data.skipBits(6); // cicpSpeakerLayoutIdx
    } else {
      int numSpeakers = (int) readEscapedValue(data, 5, 8, 16) + 1;
      if (speakerLayoutType == 1) {
        validateBitsAvailability(data, 7 * numSpeakers);
        data.skipBits(7 * numSpeakers); // cicpSpeakerIdx per speaker
      } else if (speakerLayoutType == 2) {
        validateBitsAvailability(data, 1);
        boolean angularPrecision = data.readBit();
        int angularPrecisionDegrees = angularPrecision ? 1 : 5;
        int elevationAngleBits = angularPrecision ? 7 : 5;
        int azimuthAngleBits = angularPrecision ? 8 : 6;

        // Mpegh3daSpeakerDescription array
        for (int i = 0; i < numSpeakers; i++) {
          int azimuthAngle = 0;
          validateBitsAvailability(data, 1);
          if (data.readBit()) { // isCICPspeakerIdx
            validateBitsAvailability(data, 7);
            data.skipBits(7); // cicpSpeakerIdx
          } else {
            validateBitsAvailability(data, 2);
            int elevationClass = data.readBits(2);
            if (elevationClass == 3) {
              validateBitsAvailability(data, elevationAngleBits);
              int elevationAngleIdx = data.readBits(elevationAngleBits);
              int elevationAngle = elevationAngleIdx * angularPrecisionDegrees;
              if (elevationAngle != 0) {
                validateBitsAvailability(data, 1);
                data.skipBit(); // elevationDirection
              }
            }
            validateBitsAvailability(data, azimuthAngleBits);
            int azimuthAngleIdx = data.readBits(azimuthAngleBits);
            azimuthAngle = azimuthAngleIdx * angularPrecisionDegrees;
            if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
              validateBitsAvailability(data, 1);
              data.skipBit(); // azimuthDirection
            }
            validateBitsAvailability(data, 1);
            data.skipBit(); // isLFE
          }

          if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
            validateBitsAvailability(data, 1);
            if (data.readBit()) { // alsoAddSymmetricPair
              i++;
            }
          }
        }
      }
    }
  }

  /**
   * Obtains the necessary info of Signals3d from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 5.2.2.1, Table 17.
   *
   * @param data  The bit array to be parsed.
   * @return The number of overall signals in the bit stream.
   * @throws {@link ParseException} if parsing failed.
   */
  private static int parseSignals3d(ParsableBitArray data)
      throws ParseException {
    int numSignals = 0;
    validateBitsAvailability(data, 5);
    int bsNumSignalGroups = data.readBits(5);

    for (int grp = 0; grp < bsNumSignalGroups + 1; grp++) {
      validateBitsAvailability(data, 3);
      int signalGroupType = data.readBits(3);
      int bsNumberOfSignals = (int) readEscapedValue(data, 5, 8, 16);

      numSignals += bsNumberOfSignals + 1;
      if (signalGroupType == 0 /*SignalGroupTypeChannels*/ ||
          signalGroupType == 2 /*SignalGroupTypeSAOC*/) {
        validateBitsAvailability(data, 1);
        if (data.readBit()) { // differsFromReferenceLayout OR saocDmxLayoutPresent
          parseSpeakerConfig3d(data); // audioChannelLayout[grp] OR saocDmxChannelLayout
        }
      }
    }
    return numSignals;
  }

  /**
   * Parses the Mpegh3daDecoderConfig from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 5.2.2.3, Table 21.
   *
   * @param data  The bit array to be parsed.
   * @param numSignals The number of overall signals.
   * @param sbrRatioIndex The SBR ration index.
   * @throws {@link ParseException} if parsing failed.
   */
  private static void parseMpegh3daDecoderConfig(ParsableBitArray data,
      int numSignals, int sbrRatioIndex)
      throws ParseException {

    int numElements = (int) readEscapedValue(data, 4, 8, 16) + 1;
    validateBitsAvailability(data, 1);
    data.skipBit(); // elementLengthPresent

    for (int elemIdx = 0; elemIdx < numElements; elemIdx++) {
      validateBitsAvailability(data, 2);
      int usacElementType = data.readBits(2);

      switch (usacElementType) {
        case 0 /*ID_USAC_SCE*/:
          parseMpegh3daCoreConfig(data); // coreConfig
          if (sbrRatioIndex > 0) {
            parseSbrConfig(data); // sbrConfig
          }
          break;
        case 1 /*ID_USAC_CPE*/:
          boolean enhancedNoiseFilling = parseMpegh3daCoreConfig(data); // coreConfig
          if (enhancedNoiseFilling) {
            validateBitsAvailability(data, 1);
            data.skipBit(); // igfIndependentTiling
          }
          int stereoConfigIndex = 0;
          if (sbrRatioIndex > 0) {
            parseSbrConfig(data); // sbrConfig
            validateBitsAvailability(data, 2);
            stereoConfigIndex = data.readBits(2);
          }
          if (stereoConfigIndex > 0) {
            // mps212Config
            validateBitsAvailability(data, 13);
            data.skipBits(6); // bsFreqRes(3), bsFixedGainDMX(3),
            int bsTempShapeConfig = data.readBits(2);
            data.skipBits(4);// bsDecorrConfig(2), bsHighRateMode(1), bsPhaseCoding(1)
            if (data.readBit()) { // bsOttBandsPhasePresent
              validateBitsAvailability(data, 5);
              data.skipBits(5); // bsOttBandsPhase
            }
            if (stereoConfigIndex == 2 || stereoConfigIndex == 3) {
              validateBitsAvailability(data, 6);
              data.skipBits(6); // bsResidualBands(5), bsPseudoLr(1)
            }
            if (bsTempShapeConfig == 2) {
              validateBitsAvailability(data, 1);
              data.skipBit(); // bsEnvQuantMode
            }
          }

          int nBits = (int) Math.floor(Math.log(numSignals - 1) / Math.log(2.0)) + 1;

          validateBitsAvailability(data, 2);
          int qceIndex = data.readBits(2);
          if (qceIndex > 0) {
            validateBitsAvailability(data, 1);
            if (data.readBit()) { // shiftIndex0
              validateBitsAvailability(data, nBits);
              data.skipBits(nBits); // shiftChannel0
            }
          }
          validateBitsAvailability(data, 1);
          if (data.readBit()) { // shiftIndex1
            validateBitsAvailability(data, nBits);
            data.skipBits(nBits); // shiftChannel1
          }
          if (sbrRatioIndex == 0 && qceIndex == 0) {
            validateBitsAvailability(data, 1);
            data.skipBit(); // lpdStereoIndex
          }
          break;
        case 3 /*ID_USAC_EXT*/:
          readEscapedValue(data, 4, 8, 16); // usacExtElementType
          int usacExtElementConfigLength = (int) readEscapedValue(data, 4, 8, 16);

          validateBitsAvailability(data, 1);
          if (data.readBit()) { // usacExtElementDefaultLengthPresent
            readEscapedValue(data, 8, 16, 0)/*+1*/; // usacExtElementDefaultLength
          }
          validateBitsAvailability(data, 1);
          data.skipBit(); // usacExtElementPayloadFrag

          if (usacExtElementConfigLength > 0) {
            validateBitsAvailability(data, 8 * usacExtElementConfigLength);
            data.skipBits(8 * usacExtElementConfigLength);
          }
          break;
        default:
          break;
      }
    }
  }

  /**
   * Obtains the necessary info of the Mpegh3daCoreConfig from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 5.2.2.3, Table 24.
   *
   * @param data  The bit array to be parsed.
   * @return The enhanced noise filling flag.
   * @throws {@link ParseException} if parsing failed.
   */
  private static boolean parseMpegh3daCoreConfig(ParsableBitArray data)
      throws ParseException {
    validateBitsAvailability(data, 4);
    data.skipBits(3); // tw_mdct(1), fullbandLpd(1), noiseFilling(1)
    boolean enhancedNoiseFilling = data.readBit();
    if (enhancedNoiseFilling) {
      validateBitsAvailability(data, 13);
      data.skipBits(13); // igfUseEnf(1), igfUseHighRes(1), igfUseWhitening(1), igfAfterTnsSynth(1), igfStartIndex(5), igfStopIndex(4)
    }
    return enhancedNoiseFilling;
  }

  /**
   * Parses the SbrConfig from an MPEG-H bit stream.
   * See ISO_IEC_23003-3;2020, 5.2, Table 14.
   *
   * @param data  The bit array to be parsed.
   * @throws {@link ParseException} if parsing failed.
   */
  private static void parseSbrConfig(ParsableBitArray data) throws ParseException {
    validateBitsAvailability(data, 3);
    data.skipBits(3); // harmonicSBR(1), bs_interTes(1), bs_pvc(1)

    validateBitsAvailability(data, 10);
    data.skipBits(8); // dflt_start_freq(4), dflt_stop_freq(4)
    boolean dflt_header_extra1 = data.readBit();
    boolean dflt_header_extra2 = data.readBit();
    if (dflt_header_extra1) {
      validateBitsAvailability(data, 5);
      data.skipBits(5); // dflt_freq_scale(2), dflt_alter_scale(1), dflt_noise_bands(2)
    }
    if (dflt_header_extra2) {
      validateBitsAvailability(data, 6);
      data.skipBits(6); // dflt_limiter_bands(2), dflt_limiter_gains(2), dflt_interpol_freq(1), dflt_smoothing_mode(1)
    }
  }

  private MpeghUtil() {
  }

  private static class MhasPacketHeader {
    @MhasPacketType int packetType;
    long packetLabel;
    int packetLength;

    public MhasPacketHeader(@MhasPacketType int type, long label, int length) {
      packetType = type;
      packetLabel = label;
      packetLength = length;
    }
  }

  private static class Mpegh3daConfig {

    int mpegh3daProfileLevelIndication;
    int samplingFrequency;
    int standardFrameSamples;
    @Nullable
    byte[] compatibleProfileLevelSet;

    private Mpegh3daConfig() {
      this.mpegh3daProfileLevelIndication = Format.NO_VALUE;
      this.samplingFrequency = Format.NO_VALUE;
      this.standardFrameSamples = Format.NO_VALUE;
      this.compatibleProfileLevelSet = null;
    }
  }
}
