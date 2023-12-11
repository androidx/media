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
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Utility methods for parsing MPEG-H frames, which are access units in MPEG-H bitstreams. */
@UnstableApi
public final class MpeghUtil {

  /** Holds information contained in the parsed MPEG-H frame. */
  public static class FrameInfo {

    /** Signals if the MPEG-H frame contains a mpegh3daConfig packet. */
    public boolean containsConfig;

    /** Signals if the mpegh3daConfig packet in the MPEG-H frame has changed. */
    public boolean configChanged;

    /** The default number of audio samples in the frame. */
    public int standardFrameSamples;

    /** The audio sampling rate in Hz. */
    public int samplingRate;

    /** The actual number of audio samples in the frame. */
    public int frameSamples;

    /** The number of bytes building the frame. */
    public int frameBytes;

    /** The label of the main stream in the frame. */
    public long mainStreamLabel;

    /** The profile level indication of the audio in the frame. */
    public int mpegh3daProfileLevelIndication;

    /** An array of compatible profile level indications of the audio in the frame. */
    @Nullable public byte[] compatibleSetIndication;

    /**
     * Initializes the {@link FrameInfo} with fields containing default values.
     */
    public FrameInfo() {
      standardFrameSamples = C.LENGTH_UNSET;
      samplingRate = C.RATE_UNSET_INT;
      frameSamples = C.LENGTH_UNSET;
      frameBytes = C.LENGTH_UNSET;
      mainStreamLabel = C.INDEX_UNSET;
      mpegh3daProfileLevelIndication = C.INDEX_UNSET;
      compatibleSetIndication = null;
    }

    /**
     * Initializes the {@link FrameInfo} with fields containing certain values.
     *
     * @param containsConfig See {@link #containsConfig}.
     * @param configChanged See {@link #configChanged}.
     * @param standardFrameSamples See {@link #standardFrameSamples}.
     * @param samplingRate See {@link #samplingRate}.
     * @param frameSamples See {@link #frameSamples}.
     * @param frameBytes See {@link #frameBytes}.
     * @param mainStreamLabel See {@link #mainStreamLabel}.
     * @param mpegh3daProfileLevelIndication See {@link #mpegh3daProfileLevelIndication}.
     * @param compatibleSetIndication See {@link #compatibleSetIndication}.
     */
    public FrameInfo(boolean containsConfig, boolean configChanged, int standardFrameSamples,
        int samplingRate, int frameSamples, int frameBytes, long mainStreamLabel,
        int mpegh3daProfileLevelIndication, @Nullable byte[] compatibleSetIndication) {
      this.containsConfig = containsConfig;
      this.configChanged = configChanged;
      this.standardFrameSamples = standardFrameSamples;
      this.samplingRate = samplingRate;
      this.frameSamples = frameSamples;
      this.frameBytes = frameBytes;
      this.mainStreamLabel = mainStreamLabel;
      this.mpegh3daProfileLevelIndication = mpegh3daProfileLevelIndication;
      if (compatibleSetIndication != null && compatibleSetIndication.length > 0) {
        this.compatibleSetIndication = Arrays.copyOf(compatibleSetIndication,
            compatibleSetIndication.length);
      }
    }

    /**
     * Resets the fields of the {@link FrameInfo} to its default values.
     */
    public void reset() {
      containsConfig = false;
      configChanged = false;
      standardFrameSamples = C.LENGTH_UNSET;
      samplingRate = C.RATE_UNSET_INT;
      frameBytes = C.LENGTH_UNSET;
      frameSamples = C.LENGTH_UNSET;
      mainStreamLabel = C.INDEX_UNSET;
      mpegh3daProfileLevelIndication = C.INDEX_UNSET;
      compatibleSetIndication = null;
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
   * See ISO_IEC_23003-3;2020, 6.1.1.1, Table 72.
   */
  private static final int[] SAMPLING_RATE_TABLE =
      new int[]{
          96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
          C.RATE_UNSET_INT, C.RATE_UNSET_INT, 57600, 51200, 40000, 38400, 34150, 28800, 25600,
          20000, 19200, 17075, 14400, 12800, 9600
      };

  /**
   * See ISO_IEC_23003-3;2020, 6.1.1.1, Table 75.
   */
  private static final int[] OUTPUT_FRAMELENGTH_TABLE =
      new int[]{
          768, 1024, 2048, 2048, 4096
      };

  /**
   * See ISO_IEC_23003-3;2020, 6.1.1.1, Table 75.
   */
  private static final int[] SBR_RATIO_INDEX_TABLE =
      new int[]{
          0, 0, 2, 3, 1
      };

  /**
   * See ISO_IEC_23003-8;2022, 14.4.4.
   */
  private static final int MHAS_SYNCPACKET = 0xC001A5;

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
      } catch (Exception e) {
        // There is not enough data available to parse the MHAS packet header.
        break;
      }
      if (data.bitsLeft() < header.packetLength * C.BITS_PER_BYTE) {
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
   * @throws ParserException if parsing failed.
   */
  public static FrameInfo parseFrame(ParsableBitArray data, FrameInfo prevFrameInfo)
      throws ParserException {
    int standardFrameSamples = prevFrameInfo.standardFrameSamples;
    int samplingFrequency = prevFrameInfo.samplingRate;
    boolean frameFound = false;
    boolean configFound = false;
    boolean configChanged = false;
    int truncationSamples = 0;
    long mainStreamLabel = C.INDEX_UNSET;
    int mpegh3daProfileLevelIndication = C.INDEX_UNSET;
    @Nullable byte[] compatibleSetIndication = null;

    int availableBits = data.bitsLeft();

    if (availableBits == 0) {
      throw ParserException.createForMalformedContainer(/* message= */
          "Not enough data available", /* cause= */ null);
    }
    if (availableBits % C.BITS_PER_BYTE != 0) {
      throw ParserException.createForMalformedContainer(/* message= */
          "Input data buffer is not Byte aligned", /* cause= */ null);
    }

    do {
      // parse MHAS packet header
      MhasPacketHeader packetHeader = parseMhasPacketHeader(data);
      if (packetHeader.packetLabel > 0x10) {
        throw ParserException.createForUnsupportedContainerFeature(
            "Contains sub-stream with label " + packetHeader.packetLabel);
      }

      int dataPos = data.getPosition();

      switch (packetHeader.packetType) {
        case PACTYP_MPEGH3DACFG:
          if (packetHeader.packetLabel == 0) {
            throw ParserException.createForMalformedContainer(/* message= */
                "Mpegh3daConfig packet with wrong packet label "
                    + packetHeader.packetLabel, /* cause= */ null);
          }

          // we already found a mpegh3daConfig
          if (configFound) {
            throw ParserException.createForMalformedContainer(/* message= */
                "Found a second mpegh3daConfig packet", /* cause= */ null);
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
          if (mpegh3daConfig.compatibleProfileLevelSet != null
              && mpegh3daConfig.compatibleProfileLevelSet.length > 0) {
            compatibleSetIndication = mpegh3daConfig.compatibleProfileLevelSet;
          }

          data.setPosition(dataPos);
          data.skipBits(packetHeader.packetLength * C.BITS_PER_BYTE);
          break;

        case PACTYP_AUDIOTRUNCATION:
          if (packetHeader.packetLabel == 0) {
            throw ParserException.createForMalformedContainer(/* message= */
                "AudioTruncation packet with wrong packet label "
                    + packetHeader.packetLabel, /* cause= */ null);
          }

          truncationSamples = parseAudioTruncationInfo(data);
          if (truncationSamples > standardFrameSamples) {
            throw ParserException.createForMalformedContainer(/* message= */
                "Truncation size is too big", /* cause= */ null);
          }

          data.setPosition(dataPos);
          data.skipBits(packetHeader.packetLength * C.BITS_PER_BYTE);
          break;

        case PACTYP_MPEGH3DAFRAME:
          if (packetHeader.packetLabel == 0) {
            throw ParserException.createForMalformedContainer(/* message= */
                "Mpegh3daFrame packet with wrong packet label "
                    + packetHeader.packetLabel, /* cause= */ null);
          }

          if (!configFound) {
            mainStreamLabel = prevFrameInfo.mainStreamLabel;
          }

          // check packet label
          if (packetHeader.packetLabel != mainStreamLabel) {
            throw ParserException.createForMalformedContainer(/* message= */
                "Mpegh3daFrame packet does not belong to main stream", /* cause= */ null);
          }
          frameFound = true;
          data.skipBits(packetHeader.packetLength * C.BITS_PER_BYTE);
          break;

        default:
          data.skipBits(packetHeader.packetLength * C.BITS_PER_BYTE);
          break;
      }

      if (data.bitsLeft() % C.BITS_PER_BYTE != 0) {
        throw ParserException.createForMalformedContainer(/* message= */
            "Data buffer is not Byte aligned after parsing", /* cause= */ null);
      }

    } while (!frameFound);

    int parsedBytes = (availableBits - data.bitsLeft()) / C.BITS_PER_BYTE;

    if (samplingFrequency <= 0) {
      throw ParserException.createForUnsupportedContainerFeature(/* message= */
          "Unsupported sampling frequency " + samplingFrequency);
    }

    if (standardFrameSamples <= 0) {
      throw ParserException.createForUnsupportedContainerFeature(/* message= */
          "Unsupported value of standardFrameSamples " + standardFrameSamples);
    }

    return new FrameInfo(configFound, configChanged, standardFrameSamples, /* samplingRate= */
        samplingFrequency, /* frameSamples= */ standardFrameSamples - truncationSamples,
        /* frameBytes= */ parsedBytes, mainStreamLabel, mpegh3daProfileLevelIndication,
        compatibleSetIndication);
  }

  /**
   * Parses an MHAS packet header.
   * See ISO_IEC_23008-3;2022, 14.2.1, Table 222.
   *
   * @param data The bit array to parse.
   * @return The {@link MhasPacketHeader} info.
   */
  private static MhasPacketHeader parseMhasPacketHeader(ParsableBitArray data) {
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
   * @throws ParserException if sampling frequency could not be obtained.
   */
  public static int getSamplingFrequency(ParsableBitArray data) throws ParserException {
    int sampleRate;
    int idx = data.readBits(5);

    if (idx == 0x1F) {
      sampleRate = data.readBits(24);
    } else if (idx == 13 || idx == 14 || idx >= SAMPLING_RATE_TABLE.length) {
      throw ParserException.createForUnsupportedContainerFeature(/* message= */
          "Unsupported sampling rate index " + idx);
    } else {
      sampleRate = SAMPLING_RATE_TABLE[idx];
    }
    return sampleRate;
  }

  /**
   * Obtains the resampling ratio according to the provided sampling frequency.
   * See ISO_IEC_23008-3;2022, 4.8.2, Table 10.
   *
   * @param usacSamplingFrequency The USAC sampling frequency.
   * @return The resampling ratio.
   * @throws ParserException if USAC sampling frequency is not supported.
   */
  public static double getResamplingRatio(int usacSamplingFrequency) throws ParserException {
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
        throw ParserException.createForUnsupportedContainerFeature(/* message= */
            "Unsupported sampling rate " + usacSamplingFrequency);
    }
    return resamplingRatio;
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
   */
  public static long readEscapedValue(ParsableBitArray data, int bits1, int bits2, int bits3) {
    long value = data.readBitsToLong(bits1);

    if (value == (1L << bits1) - 1) {
      long valueAdd = data.readBitsToLong(bits2);
      value += valueAdd;

      if (valueAdd == (1L << bits2) - 1) {
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
   * @param data The bit array to be parsed.
   * @return The {@link Mpegh3daConfig}.
   * @throws ParserException if parsing failed.
   */
  private static Mpegh3daConfig parseMpegh3daConfig(ParsableBitArray data) throws ParserException {
    Mpegh3daConfig mpegh3daConfig = new Mpegh3daConfig();
    mpegh3daConfig.mpegh3daProfileLevelIndication = data.readBits(8);

    int usacSamplingFrequency = getSamplingFrequency(data);

    int coreSbrFrameLengthIndex = data.readBits(3);
    data.skipBits(2); // cfg_reserved(1), receiverDelayCompensation(1)

    if (coreSbrFrameLengthIndex >= OUTPUT_FRAMELENGTH_TABLE.length ||
        coreSbrFrameLengthIndex >= SBR_RATIO_INDEX_TABLE.length) {
      throw ParserException.createForUnsupportedContainerFeature(/* message= */
          "Unsupported coreSbrFrameLengthIndex " + coreSbrFrameLengthIndex);
    }

    int outputFrameLength = OUTPUT_FRAMELENGTH_TABLE[coreSbrFrameLengthIndex];
    int sbrRatioIndex = SBR_RATIO_INDEX_TABLE[coreSbrFrameLengthIndex];

    parseSpeakerConfig3d(data); // referenceLayout
    int numSignals = parseSignals3d(data); // frameworkConfig3d
    parseMpegh3daDecoderConfig(data, numSignals, sbrRatioIndex); // decoderConfig

    if (data.readBit()) { // usacConfigExtensionPresent
      // Mpegh3daConfigExtension
      int numConfigExtensions = (int) readEscapedValue(data, 2, 4, 8) + 1;
      for (int confExtIdx = 0; confExtIdx < numConfigExtensions; confExtIdx++) {
        int usacConfigExtType = (int) readEscapedValue(data, 4, 8, 16);
        int usacConfigExtLength = (int) readEscapedValue(data, 4, 8, 16);

        if (usacConfigExtType == 7 /*ID_CONFIG_EXT_COMPATIBLE_PROFILELVL_SET*/) {
          int numCompatibleSets = data.readBits(4) + 1;
          data.skipBits(4); // reserved
          mpegh3daConfig.compatibleProfileLevelSet = new byte[numCompatibleSets];
          for (int idx = 0; idx < numCompatibleSets; idx++) {
            mpegh3daConfig.compatibleProfileLevelSet[idx] = (byte) data.readBits(8);
          }
        } else {
          data.skipBits(C.BITS_PER_BYTE * usacConfigExtLength);
        }
      }
    }

    // Get the resampling ratio and adjust the samplingFrequency and the standardFrameSamples
    // accordingly.
    double resamplingRatio = getResamplingRatio(usacSamplingFrequency);
    mpegh3daConfig.samplingFrequency = (int) (usacSamplingFrequency * resamplingRatio);
    mpegh3daConfig.standardFrameSamples = (int) (outputFrameLength * resamplingRatio);

    return mpegh3daConfig;
  }


  /**
   * Obtains the number of truncated samples of the AudioTruncationInfo from an MPEG-H bit stream.
   * See ISO_IEC_23008-3;2022, 14.2.2, Table 225.
   *
   * @param data The bit array to be parsed.
   * @return The number of truncated samples.
   */
  private static int parseAudioTruncationInfo(ParsableBitArray data) {
    int truncationSamples = 0;
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
   * @param data The bit array to be parsed.
   */
  private static void parseSpeakerConfig3d(ParsableBitArray data) {
    int speakerLayoutType = data.readBits(2);
    if (speakerLayoutType == 0) {
      data.skipBits(6); // cicpSpeakerLayoutIdx
    } else {
      int numSpeakers = (int) readEscapedValue(data, 5, 8, 16) + 1;
      if (speakerLayoutType == 1) {
        data.skipBits(7 * numSpeakers); // cicpSpeakerIdx per speaker
      } else if (speakerLayoutType == 2) {
        boolean angularPrecision = data.readBit();
        int angularPrecisionDegrees = angularPrecision ? 1 : 5;
        int elevationAngleBits = angularPrecision ? 7 : 5;
        int azimuthAngleBits = angularPrecision ? 8 : 6;

        // Mpegh3daSpeakerDescription array
        for (int i = 0; i < numSpeakers; i++) {
          int azimuthAngle = 0;
          if (data.readBit()) { // isCICPspeakerIdx
            data.skipBits(7); // cicpSpeakerIdx
          } else {
            int elevationClass = data.readBits(2);
            if (elevationClass == 3) {
              int elevationAngleIdx = data.readBits(elevationAngleBits);
              int elevationAngle = elevationAngleIdx * angularPrecisionDegrees;
              if (elevationAngle != 0) {
                data.skipBit(); // elevationDirection
              }
            }
            int azimuthAngleIdx = data.readBits(azimuthAngleBits);
            azimuthAngle = azimuthAngleIdx * angularPrecisionDegrees;
            if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
              data.skipBit(); // azimuthDirection
            }
            data.skipBit(); // isLFE
          }

          if ((azimuthAngle != 0) && (azimuthAngle != 180)) {
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
   * @param data The bit array to be parsed.
   * @return The number of overall signals in the bit stream.
   */
  private static int parseSignals3d(ParsableBitArray data) {
    int numSignals = 0;
    int bsNumSignalGroups = data.readBits(5);

    for (int grp = 0; grp < bsNumSignalGroups + 1; grp++) {
      int signalGroupType = data.readBits(3);
      int bsNumberOfSignals = (int) readEscapedValue(data, 5, 8, 16);

      numSignals += bsNumberOfSignals + 1;
      if (signalGroupType == 0 /*SignalGroupTypeChannels*/ ||
          signalGroupType == 2 /*SignalGroupTypeSAOC*/) {
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
   * @param data The bit array to be parsed.
   * @param numSignals The number of overall signals.
   * @param sbrRatioIndex The SBR ration index.
   */
  private static void parseMpegh3daDecoderConfig(ParsableBitArray data,
      int numSignals, int sbrRatioIndex) {

    int numElements = (int) readEscapedValue(data, 4, 8, 16) + 1;
    data.skipBit(); // elementLengthPresent

    for (int elemIdx = 0; elemIdx < numElements; elemIdx++) {
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
            data.skipBit(); // igfIndependentTiling
          }
          int stereoConfigIndex = 0;
          if (sbrRatioIndex > 0) {
            parseSbrConfig(data); // sbrConfig
            stereoConfigIndex = data.readBits(2);
          }
          if (stereoConfigIndex > 0) {
            // mps212Config
            data.skipBits(6); // bsFreqRes(3), bsFixedGainDMX(3),
            int bsTempShapeConfig = data.readBits(2);
            data.skipBits(4);// bsDecorrConfig(2), bsHighRateMode(1), bsPhaseCoding(1)
            if (data.readBit()) { // bsOttBandsPhasePresent
              data.skipBits(5); // bsOttBandsPhase
            }
            if (stereoConfigIndex == 2 || stereoConfigIndex == 3) {
              data.skipBits(6); // bsResidualBands(5), bsPseudoLr(1)
            }
            if (bsTempShapeConfig == 2) {
              data.skipBit(); // bsEnvQuantMode
            }
          }

          int nBits = (int) Math.floor(Math.log(numSignals - 1) / Math.log(2.0)) + 1;
          int qceIndex = data.readBits(2);
          if (qceIndex > 0) {
            if (data.readBit()) { // shiftIndex0
              data.skipBits(nBits); // shiftChannel0
            }
          }
          if (data.readBit()) { // shiftIndex1
            data.skipBits(nBits); // shiftChannel1
          }
          if (sbrRatioIndex == 0 && qceIndex == 0) {
            data.skipBit(); // lpdStereoIndex
          }
          break;
        case 3 /*ID_USAC_EXT*/:
          readEscapedValue(data, 4, 8, 16); // usacExtElementType
          int usacExtElementConfigLength = (int) readEscapedValue(data, 4, 8, 16);

          if (data.readBit()) { // usacExtElementDefaultLengthPresent
            readEscapedValue(data, 8, 16, 0)/*+1*/; // usacExtElementDefaultLength
          }
          data.skipBit(); // usacExtElementPayloadFrag

          if (usacExtElementConfigLength > 0) {
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
   * @param data The bit array to be parsed.
   * @return The enhanced noise filling flag.
   */
  private static boolean parseMpegh3daCoreConfig(ParsableBitArray data) {
    data.skipBits(3); // tw_mdct(1), fullbandLpd(1), noiseFilling(1)
    boolean enhancedNoiseFilling = data.readBit();
    if (enhancedNoiseFilling) {
      data.skipBits(13); // igfUseEnf(1), igfUseHighRes(1), igfUseWhitening(1), igfAfterTnsSynth(1), igfStartIndex(5), igfStopIndex(4)
    }
    return enhancedNoiseFilling;
  }

  /**
   * Parses the SbrConfig from an MPEG-H bit stream.
   * See ISO_IEC_23003-3;2020, 5.2, Table 14.
   *
   * @param data The bit array to be parsed.
   */
  private static void parseSbrConfig(ParsableBitArray data) {
    data.skipBits(3); // harmonicSBR(1), bs_interTes(1), bs_pvc(1)
    data.skipBits(8); // dflt_start_freq(4), dflt_stop_freq(4)
    boolean dflt_header_extra1 = data.readBit();
    boolean dflt_header_extra2 = data.readBit();
    if (dflt_header_extra1) {
      data.skipBits(5); // dflt_freq_scale(2), dflt_alter_scale(1), dflt_noise_bands(2)
    }
    if (dflt_header_extra2) {
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
    @Nullable byte[] compatibleProfileLevelSet;

    private Mpegh3daConfig() {
      mpegh3daProfileLevelIndication = C.INDEX_UNSET;
      samplingFrequency = C.RATE_UNSET_INT;
      standardFrameSamples = C.LENGTH_UNSET;
      compatibleProfileLevelSet = null;
    }
  }
}
