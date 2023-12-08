/*
 * Copyright (C) 2019 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Utility methods for parsing AC-4 frames, which are access units in AC-4 bitstreams. */
@UnstableApi
public final class Ac4Util {

  private static final String TAG = "Ac4Util";

  /** Holds sample format information as presented by a syncframe header. */
  public static final class SyncFrameInfo {

    /** The bitstream version. */
    public final int bitstreamVersion;

    /** The audio sampling rate in Hz. */
    public final int sampleRate;

    /** The number of audio channels */
    public final int channelCount;

    /** The size of the frame. */
    public final int frameSize;

    /** Number of audio samples in the frame. */
    public final int sampleCount;

    private SyncFrameInfo(
        int bitstreamVersion, int channelCount, int sampleRate, int frameSize, int sampleCount) {
      this.bitstreamVersion = bitstreamVersion;
      this.channelCount = channelCount;
      this.sampleRate = sampleRate;
      this.frameSize = frameSize;
      this.sampleCount = sampleCount;
    }
  }

  public static final int AC40_SYNCWORD = 0xAC40;
  public static final int AC41_SYNCWORD = 0xAC41;

  /** Maximum rate for an AC-4 audio stream, in bytes per second. */
  public static final int MAX_RATE_BYTES_PER_SECOND = 2688 * 1000 / 8;

  /** The channel count of AC-4 stream. */
  // TODO: Parse AC-4 stream channel count.
  private static final int CHANNEL_COUNT_2 = 2;

  /**
   * The AC-4 sync frame header size for extractor. The seven bytes are 0xAC, 0x40, 0xFF, 0xFF,
   * sizeByte1, sizeByte2, sizeByte3. See ETSI TS 103 190-1 V1.3.1, Annex G
   */
  public static final int SAMPLE_HEADER_SIZE = 7;

  /**
   * The header size for AC-4 parser. Only needs to be as big as we need to read, not the full
   * header size.
   */
  public static final int HEADER_SIZE_FOR_PARSER = 16;

  /**
   * Number of audio samples in the frame. Defined in IEC61937-14:2017 table 5 and 6. This table
   * provides the number of samples per frame at the playback sampling frequency of 48 kHz. For 44.1
   * kHz, only frame_rate_index(13) is valid and corresponding sample count is 2048.
   */
  private static final int[] SAMPLE_COUNT =
      new int[] {
        /* [ 0]  23.976 fps */ 2002,
        /* [ 1]  24     fps */ 2000,
        /* [ 2]  25     fps */ 1920,
        /* [ 3]  29.97  fps */ 1601, // 1601 | 1602 | 1601 | 1602 | 1602
        /* [ 4]  30     fps */ 1600,
        /* [ 5]  47.95  fps */ 1001,
        /* [ 6]  48     fps */ 1000,
        /* [ 7]  50     fps */ 960,
        /* [ 8]  59.94  fps */ 800, //  800 |  801 |  801 |  801 |  801
        /* [ 9]  60     fps */ 800,
        /* [10] 100     fps */ 480,
        /* [11] 119.88  fps */ 400, //  400 |  400 |  401 |  400 |  401
        /* [12] 120     fps */ 400,
        /* [13]  23.438 fps */ 2048
      };

  /**
   * channel_mode is defined in ETSI TS 103 190-2 V1.2.1 (2018-02), section 6.3.2.7.2 and Table 78.
   */
  private static final String[] CHANNEL_MODES =
      new String[] {
          "Mono",
          "Stereo",
          "3.0",
          "5.0",
          "5.1",
          "7.0 (3/4/0)",
          "7.1 (3/4/0.1)",
          "7.0 (5/2/0)",
          "7.1 (5/2/0.1)",
          "7.0 (3/2/2)",
          "7.1 (3/2/2.1)",
          "7.0.4",
          "7.1.4",
          "9.0.4",
          "9.1.4",
          "22.2"
      };

  /** Holds AC-4 presentation information. */
  public static final class Ac4Presentation {
    // TS 103 190-1 v1.2.1 4.3.3.8.1: content_classifiers
    public static final int K_COMPLETE_MAIN = 0;
    public static final int K_MUSIC_AND_EFFECTS = 1;
    public static final int K_VISUALLY_IMPAIRED = 2;
    public static final int K_HEARING_IMPAIRED = 3;
    public static final int K_DIALOG = 4;
    public static final int K_COMMENTARY = 5;
    public static final int K_EMERGENCY = 6;
    public static final int K_VOICEOVER = 7;

    public int contentClassifier = K_COMPLETE_MAIN;

    // ETSI TS 103 190-2 V1.1.1 (2015-09) Table 79: channel_mode
    public static final int K_CHANNEL_MODE_MONO = 0;
    public static final int K_CHANNEL_MODE_STEREO = 1;
    public static final int K_CHANNEL_MODE_3_0 = 2;
    public static final int K_CHANNEL_MODE_5_0 = 3;
    public static final int K_CHANNEL_MODE_5_1 = 4;
    public static final int K_CHANNEL_MODE_7_0_34 = 5;
    public static final int K_CHANNEL_MODE_7_1_34 = 6;
    public static final int K_CHANNEL_MODE_7_0_52 = 7;
    public static final int K_CHANNEL_MODE_7_1_52 = 8;
    public static final int K_CHANNEL_MODE_7_0_322 = 9;
    public static final int K_CHANNEL_MODE_7_1_322 = 10;
    public static final int K_CHANNEL_MODE_7_0_4 = 11;
    public static final int K_CHANNEL_MODE_7_1_4 = 12;
    public static final int K_CHANNEL_MODE_9_0_4 = 13;
    public static final int K_CHANNEL_MODE_9_1_4 = 14;
    public static final int K_CHANNEL_MODE_22_2 = 15;
    public static final int K_CHANNEL_MODE_RESERVED = 16;

    public boolean channelCoded;
    public int channelMode;
    public int numOfUmxObjects;
    public boolean backChannelsPresent;
    public int topChannelPairs;
    public int programID;
    public int groupIndex;
    public boolean hasDialogEnhancements;
    public boolean preVirtualized;
    public int version;
    public int level;
    @Nullable
    public ByteBuffer language;
    @Nullable
    public ByteBuffer description;

    private Ac4Presentation() {
      this.channelCoded = true;
      this.channelMode = -1;
      this.numOfUmxObjects = -1;
      this.backChannelsPresent = true;
      this.topChannelPairs = 2;
      this.programID = -1;
      this.groupIndex = -1;
      this.hasDialogEnhancements = false;
      this.preVirtualized = false;
      this.version = 0;
      this.level = 0;
      this.language = null;
      this.description = null;
    }
  }

  /**
   * Returns the AC-4 format given {@code data} containing the AC4SpecificBox according to ETSI TS
   * 103 190-1 Annex E.4 (ac4_dsi) and TS 103 190-2 section E.6 (ac4_dsi_v1). The reading position
   * of {@code data} will be modified.
   *
   * @param data The AC4SpecificBox to parse.
   * @param trackId The track identifier to set on the format.
   * @param language The language to set on the format.
   * @param drmInitData {@link DrmInitData} to be included in the format.
   * @return The AC-4 format parsed from data in the header.
   */
  public static Format parseAc4AnnexEFormat(
      ParsableByteArray data, String trackId, String language, @Nullable DrmInitData drmInitData)
      throws ParserException {
    ParsableBitArray dataBitArray = new ParsableBitArray();
    dataBitArray.reset(data);
    Map<Integer, Ac4Presentation> ac4Presentations = new HashMap<>();
    long dsiSize = dataBitArray.bitsLeft();

    int ac4DsiVersion = dataBitArray.readBits(3);  // ac4_dsi_version
    if (ac4DsiVersion > 1) {
      throw ParserException.createForUnsupportedContainerFeature(
          "Unsupported AC-4 DSI version: " + ac4DsiVersion);
    }
    int bitstreamVersion = dataBitArray.readBits(7);  // bitstream_version
    int sampleRate = dataBitArray.readBit() ? 48000 : 44100;  // fs_index
    dataBitArray.skipBits(4);  // frame_rate_index
    int nPresentations = dataBitArray.readBits(9);  // n_presentations

    int shortProgramId = -1;
    if (bitstreamVersion > 1) {
      if (ac4DsiVersion == 0) {
        throw ParserException.createForUnsupportedContainerFeature(
            "Invalid AC-4 DSI version: " + ac4DsiVersion);
      }
      boolean bProgramId = dataBitArray.readBit();  // b_program_id
      if (bProgramId) {
        shortProgramId = dataBitArray.readBits(16);
        boolean bUuid = dataBitArray.readBit();  // b_uuid
        if (bUuid) {
          dataBitArray.skipBits(16 * 8);  // program_uuid
        }
      }
    }

    if (ac4DsiVersion == 1) {
      if (!parseBitrateDsi(dataBitArray)) {
        throw ParserException.createForUnsupportedContainerFeature(
            "Invalid AC-4 DSI bitrate.");
      }
      dataBitArray.byteAlign();
    }

    for (int presentationIndex = 0; presentationIndex < nPresentations; presentationIndex++) {
      Ac4Presentation ac4Presentation = new Ac4Presentation();
      ac4Presentation.programID = shortProgramId;
      // known as b_single_substream in ac4_dsi_version 0
      boolean bSingleSubstreamGroup = false;
      int presentationConfig = 0;
      int presentationVersion = 0;
      int presBytes = 0;
      long start = 0;

      if (ac4DsiVersion == 0) {
        bSingleSubstreamGroup = dataBitArray.readBit();  // b_single_substream_group
        presentationConfig = dataBitArray.readBits(5);  // presentation_config
        presentationVersion = dataBitArray.readBits(5);  // presentation_version
      } else if (ac4DsiVersion == 1) {
        presentationVersion = dataBitArray.readBits(8);  // presentation_version
        presBytes = dataBitArray.readBits(8);  // pres_bytes
        if (presBytes == 0xff) {
          presBytes += dataBitArray.readBits(16);  // pres_bytes
        }
        if (presentationVersion > 2) {
          dataBitArray.skipBits(presBytes * 8);
          ac4Presentations.put(presentationIndex, ac4Presentation);
          continue;
        }
        // record a marker, less the size of the presentation_config
        start = (dsiSize - dataBitArray.bitsLeft()) / 8;
        // ac4_presentation_v0_dsi(), ac4_presentation_v1_dsi() and ac4_presentation_v2_dsi()
        // all start with a presentation_config of 5 bits
        presentationConfig = dataBitArray.readBits(5);  // presentation_config
        bSingleSubstreamGroup = (presentationConfig == 0x1f);
      }

      boolean bAddEmdfSubstreams;
      if (!bSingleSubstreamGroup && presentationConfig == 6) {
        bAddEmdfSubstreams = true;
      } else {
        int mdcompat = dataBitArray.readBits(3);  // mdcompat
        ac4Presentation.version = presentationVersion;
        ac4Presentation.level = mdcompat;

        boolean bPresentationGroupIndex = dataBitArray.readBit();  // b_presentation_group_index
        if (bPresentationGroupIndex) {
          ac4Presentation.groupIndex = dataBitArray.readBits(5);  // group_index
        }

        dataBitArray.skipBits(2);  // dsi_frame_rate_multiply_info
        if (ac4DsiVersion == 1 && (presentationVersion == 1 || presentationVersion == 2)) {
          dataBitArray.skipBits(2);  // dsi_frame_rate_fraction_info
        }
        dataBitArray.skipBits(5);  // presentation_emdf_version
        dataBitArray.skipBits(10);  // presentation_key_id

        if (ac4DsiVersion == 1) {
          boolean bPresentationChannelCoded;  // b_presentation_channel_coded
          if (presentationVersion == 0) {
            bPresentationChannelCoded = true;
          } else {
            bPresentationChannelCoded = dataBitArray.readBit();  // b_presentation_channel_coded
          }

          ac4Presentation.channelCoded = bPresentationChannelCoded;

          if (bPresentationChannelCoded) {
            if (presentationVersion == 1 || presentationVersion == 2) {
              int dsiPresentationChMode =
                  dataBitArray.readBits(5);  // dsi_presentation_ch_mode
              ac4Presentation.channelMode = dsiPresentationChMode;

              if (dsiPresentationChMode >= 11 && dsiPresentationChMode <= 14) {
                ac4Presentation.backChannelsPresent =
                    dataBitArray.readBit();  // pres_b_4_back_channels_present
                ac4Presentation.topChannelPairs =
                    dataBitArray.readBits(2);  // pres_top_channel_pairs
              }
            }
            // presentation_channel_mask in ac4_presentation_v0_dsi()
            dataBitArray.skipBits(24);  // presentation_channel_mask_v1
          }

          if (presentationVersion == 1 || presentationVersion == 2) {
            boolean bPresentationCoreDiffers =
                dataBitArray.readBit();  // b_presentation_core_differs
            if (bPresentationCoreDiffers) {
              boolean bPresentationCoreChannelCoded =
                  dataBitArray.readBit();  // b_presentation_core_channel_coded
              if (bPresentationCoreChannelCoded) {
                dataBitArray.skipBits(2);  // dsi_presentation_channel_mode_core
              }
            }
            boolean bPresentationFilter = dataBitArray.readBit();  // b_presentation_filter
            if (bPresentationFilter) {
              // Ignore b_enable_presentation field since this flag occurs in AC-4 elementary stream
              // TOC and AC-4 decoder doesn't handle it either.
              dataBitArray.skipBit();  // b_enable_presentation
              int nFilterBytes = dataBitArray.readBits(8);  // n_filter_bytes
              for (int i = 0; i < nFilterBytes; i++) {
                dataBitArray.skipBits(8); // filter_data
              }
            }
          }
        }

        if (bSingleSubstreamGroup) {
          if (presentationVersion == 0) {
            if (!parseSubstreamDSI(
                dataBitArray, ac4Presentation, presentationIndex, 0)) {
              throw ParserException.createForUnsupportedContainerFeature(
                  "Can't parse substream DSI, presentation index = "
                      + presentationIndex + ", single substream.");
            }
          } else {
            if (!parseSubstreamGroupDSI(
                dataBitArray, ac4Presentation, presentationIndex, 0)) {
              throw ParserException.createForUnsupportedContainerFeature(
                  "Can't parse substream group DSI, presentation index = "
                      + presentationIndex + "single substream group.");
            }
          }
        } else {
          if (ac4DsiVersion == 1) {
            dataBitArray.skipBit();  // b_multi_pid
          } else {
            dataBitArray.skipBit();  //  b_hsf_ext
          }
          switch (presentationConfig) {
            case 0:
            case 1:
            case 2:
              if (presentationVersion == 0) {
                for (int substreamID = 0; substreamID < 2; substreamID++) {
                  if (!parseSubstreamDSI(
                      dataBitArray, ac4Presentation, presentationIndex, substreamID)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Can't parse substream DSI, presentation index = "
                            + presentationIndex + ", substream ID = " + substreamID);
                  }
                }
              } else {
                for (int substreamGroupID = 0; substreamGroupID < 2; substreamGroupID++) {
                  if (!parseSubstreamGroupDSI(
                      dataBitArray, ac4Presentation, presentationIndex, substreamGroupID)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Can't parse substream group DSI, presentation index = "
                            + presentationIndex + ", substream group ID = " + substreamGroupID);
                  }
                }
              }
              break;
            case 3:
            case 4:
              if (presentationVersion == 0) {
                for (int substreamID = 0; substreamID < 3; substreamID++) {
                  if (!parseSubstreamDSI(
                      dataBitArray, ac4Presentation, presentationIndex, substreamID)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Can't parse substream DSI, presentation index = "
                            + presentationIndex + ", substream ID = " + substreamID);
                  }
                }
              } else {
                for (int substreamGroupID = 0; substreamGroupID < 3; substreamGroupID++) {
                  if (!parseSubstreamGroupDSI(
                      dataBitArray, ac4Presentation, presentationIndex, substreamGroupID)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Can't parse substream group DSI, presentation index = "
                            + presentationIndex + ", substream group ID = " + substreamGroupID);
                  }
                }
               }
              break;
            case 5:
              if (presentationVersion == 0) {
                if (!parseSubstreamDSI(
                    dataBitArray, ac4Presentation, presentationIndex, 0)) {
                  throw ParserException.createForUnsupportedContainerFeature(
                      "Can't parse substream DSI, presentation index = "
                          + presentationIndex + "single substream.");
                }
              } else {
                int nSubstreamGroupsMinus2 = dataBitArray.readBits(3);
                for (int substreamGroupID = 0; substreamGroupID < nSubstreamGroupsMinus2 + 2;
                    substreamGroupID++) {
                  if (!parseSubstreamGroupDSI(
                      dataBitArray, ac4Presentation, presentationIndex, substreamGroupID)) {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "Can't parse substream group DSI, presentation index = "
                            + presentationIndex + ", substream group ID = " + substreamGroupID);
                  }
                }
              }
              break;
            default:
              int nSkipBytes = dataBitArray.readBits(7);  // n_skip_bytes
              for (int j = 0; j < nSkipBytes; j++) {
                dataBitArray.skipBits(8);
              }
              break;
          }
        }
        ac4Presentation.preVirtualized = dataBitArray.readBit();  // b_pre_virtualized
        bAddEmdfSubstreams = dataBitArray.readBit();  // b_add_emdf_substreams
      }
      if (bAddEmdfSubstreams) {
        int nAddEmdfSubstreams = dataBitArray.readBits(7);  // n_add_emdf_substreams
        for (int j = 0; j < nAddEmdfSubstreams; j++) {
          dataBitArray.skipBits(5 + 10);  // substream_emdf_version and substream_key_id
        }
      }

      boolean bPresentationBitrateInfo = false;
      if (presentationVersion > 0) {
        bPresentationBitrateInfo = dataBitArray.readBit();  // b_presentation_bitrate_info
      }

      if (bPresentationBitrateInfo) {
        if (!parseBitrateDsi(dataBitArray)) {
          throw ParserException.createForUnsupportedContainerFeature(
              "Can't parse bitrate DSI.");
        }
      }

      if (presentationVersion > 0) {
        boolean bAlternative = dataBitArray.readBit();  // b_alternative
        if (bAlternative) {
          dataBitArray.byteAlign();
          int nameLen = dataBitArray.readBits(16);  // name_len
          byte[] presentationName = new byte[nameLen];
          dataBitArray.readBytes(presentationName, 0, nameLen);
          ac4Presentation.description = ByteBuffer.wrap(presentationName);

          int nTargets = dataBitArray.readBits(5);  // n_targets
          for (int i = 0; i < nTargets; i++) {
            dataBitArray.skipBits(3);  // target_md_compat
            dataBitArray.skipBits(8);  // target_device_category
          }
        }
      }

      dataBitArray.byteAlign();

      if (ac4DsiVersion == 1) {
        long end = (dsiSize - dataBitArray.bitsLeft()) / 8;
        long presentationBytes = end - start;
        if (presBytes < presentationBytes) {
          throw ParserException.createForUnsupportedContainerFeature(
              "pres_bytes is smaller than presentation_bytes.");
        }
        long skipBytes = presBytes - presentationBytes;
        dataBitArray.skipBits((int)skipBytes * 8);
      }

      // We should know this or something is probably wrong
      // with the bitstream (or we don't support it)
      if (ac4Presentation.channelCoded && ac4Presentation.channelMode == -1) {
        throw ParserException.createForUnsupportedContainerFeature(
            "Can't determine channel mode of presentation " + presentationIndex);
      }

      ac4Presentations.put(presentationIndex, ac4Presentation);
    }

    int channelCount = -1;
    // Using first presentation (default presentation) channel count
    int presentationIndex = 0;
    Ac4Presentation ac4Presentation =
        Objects.requireNonNull(ac4Presentations.get(presentationIndex));
    if (ac4Presentation.channelCoded) {
      channelCount = convertAc4ChannelModeToChannelCount(ac4Presentation.channelMode,
          ac4Presentation.backChannelsPresent, ac4Presentation.topChannelPairs);
    } else {
      channelCount = ac4Presentation.numOfUmxObjects;
      // TODO: There is a bug in ETSI TS 103 190-2 V1.2.1 (2018-02), E.11.11
      // For AC-4 level 4 stream, the intention is to set 19 to n_umx_objects_minus1 but it is
      // equal to 15 based on current specification. Dolby has filed a bug report to ETSI.
      // The following sentence should be deleted after ETSI specification error is fixed.
      if (ac4Presentation.level == 4) {
        channelCount = channelCount == 16 ? 21 : channelCount;
      }
    }

    if (channelCount <= 0) throw ParserException.createForUnsupportedContainerFeature(
        "Can't determine channel count of presentation.");

    return new Format.Builder()
        .setId(trackId)
        .setSampleMimeType(MimeTypes.AUDIO_AC4)
        .setChannelCount(channelCount)
        .setSampleRate(sampleRate)
        .setDrmInitData(drmInitData)
        .setLanguage(language)
        .build();
  }

  /**
   * Returns AC-4 format information given {@code data} containing a syncframe. The reading position
   * of {@code data} will be modified.
   *
   * @param data The data to parse, positioned at the start of the syncframe.
   * @return The AC-4 format data parsed from the header.
   */
  public static SyncFrameInfo parseAc4SyncframeInfo(ParsableBitArray data) {
    int headerSize = 0;
    int syncWord = data.readBits(16);
    headerSize += 2;
    int frameSize = data.readBits(16);
    headerSize += 2;
    if (frameSize == 0xFFFF) {
      frameSize = data.readBits(24);
      headerSize += 3; // Extended frame_size
    }
    frameSize += headerSize;
    if (syncWord == AC41_SYNCWORD) {
      frameSize += 2; // crc_word
    }
    int bitstreamVersion = data.readBits(2);
    if (bitstreamVersion == 3) {
      bitstreamVersion += readVariableBits(data, /* bitsPerRead= */ 2);
    }
    int sequenceCounter = data.readBits(10);
    if (data.readBit()) { // b_wait_frames
      if (data.readBits(3) > 0) { // wait_frames
        data.skipBits(2); // reserved
      }
    }
    int sampleRate = data.readBit() ? 48000 : 44100;
    int frameRateIndex = data.readBits(4);
    int sampleCount = 0;
    if (sampleRate == 44100 && frameRateIndex == 13) {
      sampleCount = SAMPLE_COUNT[frameRateIndex];
    } else if (sampleRate == 48000 && frameRateIndex < SAMPLE_COUNT.length) {
      sampleCount = SAMPLE_COUNT[frameRateIndex];
      switch (sequenceCounter % 5) {
        case 1: // fall through
        case 3:
          if (frameRateIndex == 3 || frameRateIndex == 8) {
            sampleCount++;
          }
          break;
        case 2:
          if (frameRateIndex == 8 || frameRateIndex == 11) {
            sampleCount++;
          }
          break;
        case 4:
          if (frameRateIndex == 3 || frameRateIndex == 8 || frameRateIndex == 11) {
            sampleCount++;
          }
          break;
        default:
          break;
      }
    }
    return new SyncFrameInfo(bitstreamVersion, CHANNEL_COUNT_2, sampleRate, frameSize, sampleCount);
  }

  /**
   * Returns the size in bytes of the given AC-4 syncframe.
   *
   * @param data The syncframe to parse.
   * @param syncword The syncword value for the syncframe.
   * @return The syncframe size in bytes, or {@link C#LENGTH_UNSET} if the input is invalid.
   */
  public static int parseAc4SyncframeSize(byte[] data, int syncword) {
    if (data.length < 7) {
      return C.LENGTH_UNSET;
    }
    int headerSize = 2; // syncword
    int frameSize = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    headerSize += 2;
    if (frameSize == 0xFFFF) {
      frameSize = ((data[4] & 0xFF) << 16) | ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
      headerSize += 3;
    }
    if (syncword == AC41_SYNCWORD) {
      headerSize += 2;
    }
    frameSize += headerSize;
    return frameSize;
  }

  /**
   * Reads the number of audio samples represented by the given AC-4 syncframe. The buffer's
   * position is not modified.
   *
   * @param buffer The {@link ByteBuffer} from which to read the syncframe.
   * @return The number of audio samples represented by the syncframe.
   */
  public static int parseAc4SyncframeAudioSampleCount(ByteBuffer buffer) {
    byte[] bufferBytes = new byte[HEADER_SIZE_FOR_PARSER];
    int position = buffer.position();
    buffer.get(bufferBytes);
    buffer.position(position);
    return parseAc4SyncframeInfo(new ParsableBitArray(bufferBytes)).sampleCount;
  }

  /** Populates {@code buffer} with an AC-4 sample header for a sample of the specified size. */
  public static void getAc4SampleHeader(int size, ParsableByteArray buffer) {
    // See ETSI TS 103 190-1 V1.3.1, Annex G.
    buffer.reset(SAMPLE_HEADER_SIZE);
    byte[] data = buffer.getData();
    data[0] = (byte) 0xAC;
    data[1] = 0x40;
    data[2] = (byte) 0xFF;
    data[3] = (byte) 0xFF;
    data[4] = (byte) ((size >> 16) & 0xFF);
    data[5] = (byte) ((size >> 8) & 0xFF);
    data[6] = (byte) (size & 0xFF);
  }

  private static boolean parseBitrateDsi(ParsableBitArray dataBitArray) {
    if (dataBitArray.bitsLeft() < 2 + 32 + 32) {
      return false;
    }

    dataBitArray.skipBits(2);  // bit_rate_mode
    dataBitArray.skipBits(32); // bit_rate
    dataBitArray.skipBits(32); // bit_rate_precision

    return true;
  }

  private static int convertAc4ChannelModeToChannelCount(
      int mode, boolean backChannelsPresent, int topChannelPairs) {
    int channelCount = -1;
    switch (mode) {
      case Ac4Presentation.K_CHANNEL_MODE_MONO:
        channelCount = 1;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_STEREO:
        channelCount = 2;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_3_0:
        channelCount = 3;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_5_0:
        channelCount = 5;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_5_1:
        channelCount = 6;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_7_0_34:
      case Ac4Presentation.K_CHANNEL_MODE_7_0_52:
      case Ac4Presentation.K_CHANNEL_MODE_7_0_322:
        channelCount = 7;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_7_1_34:
      case Ac4Presentation.K_CHANNEL_MODE_7_1_52:
      case Ac4Presentation.K_CHANNEL_MODE_7_1_322:
        channelCount = 8;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_7_0_4:
        channelCount = 11;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_7_1_4:
        channelCount = 12;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_9_0_4:
        channelCount = 13;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_9_1_4:
        channelCount = 14;
        break;
      case Ac4Presentation.K_CHANNEL_MODE_22_2:
        channelCount = 24;
        break;
      default:
        Log.w(TAG, "Invalid channel mode in AC-4 presentation.");
        return channelCount;
    }
    switch (mode) {
      case Ac4Presentation.K_CHANNEL_MODE_7_0_4:
      case Ac4Presentation.K_CHANNEL_MODE_7_1_4:
      case Ac4Presentation.K_CHANNEL_MODE_9_0_4:
      case Ac4Presentation.K_CHANNEL_MODE_9_1_4:
        if (!backChannelsPresent) {
          channelCount -= 2;
        }
        if (topChannelPairs == 0) {
          channelCount -= 4;
        } else if (topChannelPairs == 1) {
          channelCount -= 2;
        } else if (topChannelPairs == 2) {
          ;
        } else {
          Log.w(TAG, "Invalid topChannelPairs in AC-4 presentation.");
        }
        break;
      default:
        break;
    }
    return channelCount;
  }

  private static boolean parseLanguageTag(ParsableBitArray dataBitArray,
      Ac4Presentation ac4Presentation, int presentationID, int substreamID) {
    int nLanguageTagBytes = dataBitArray.readBits(6);
    if (nLanguageTagBytes < 2 || nLanguageTagBytes >= 42) {
      return false;
    }

    byte[] languageTagBytes = new byte[nLanguageTagBytes];  // TS 103 190 part 1 4.3.3.8.7
    // Can't use readBytes() since it is not byte-aligned here.
    dataBitArray.readBits(languageTagBytes, 0, nLanguageTagBytes * 8);
    ac4Presentation.language = ByteBuffer.wrap(languageTagBytes);
    Log.d(TAG, presentationID + "." + substreamID + ": language_tag = "
        + ac4Presentation.language);

    return true;
  }

  /**
   * Parse AC-4 substream DSI according to TS 103 190-1 v1.2.1 E.5 and TS 103 190-2 v1.1.1 E.9
   * @param dataBitArray A {@link ParsableBitArray} containing the AC-4 DSI to parse.
   * @param ac4Presentation A structure to store AC-4 presentation info.
   * @param presentationID The AC-4 presentation index.
   * @param substreamID The AC-4 presentation substream ID.
   * @return Whether there is an error during substream paring.
   */
  private static boolean parseSubstreamDSI(ParsableBitArray dataBitArray,
      Ac4Presentation ac4Presentation, int presentationID, int substreamID) {
    int channelMode = dataBitArray.readBits(5);  // channel_mode
    Log.d(TAG, presentationID + "." + substreamID + ": channel_mode = "
        + (channelMode < CHANNEL_MODES.length ? CHANNEL_MODES[channelMode] : "Reserved"));
    dataBitArray.skipBits(2);  // dsi_sf_multiplier

    boolean bSubstreamBitrateIndicator = dataBitArray.readBit();
    if (bSubstreamBitrateIndicator) {
      dataBitArray.skipBits(5);  // substream_bitrate_indicator
    }
    if (channelMode >= 7 && channelMode <= 10) {
      dataBitArray.skipBit();  // add_ch_base
    }

    boolean bContentType = dataBitArray.readBit();  // b_content_type
    if (bContentType) {
      int contentClassifier = dataBitArray.readBits(3);

      // For streams based on TS 103 190 part 1 the presentation level channel_mode doesn't
      // exist and so we use the channel_mode from either the CM or M&E substream
      // (they are mutually exclusive)
      if (ac4Presentation.channelMode == -1 && (contentClassifier == 0 || contentClassifier == 1)) {
        ac4Presentation.channelMode = channelMode;
      }
      ac4Presentation.contentClassifier = contentClassifier;
      boolean bLanguageIndicator = dataBitArray.readBit();  // b_language_indicator
      if (bLanguageIndicator) {
        if (!parseLanguageTag(dataBitArray, ac4Presentation, presentationID, substreamID)) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Parse AC-4 substream group DSI according to ETSI TS 103 190-2 v1.1.1 section E.11
   * @param dataBitArray A {@link ParsableBitArray} containing the AC-4 DSI to parse.
   * @param ac4Presentation A structure to store AC-4 presentation info.
   * @param presentationID The AC-4 presentation index.
   * @param groupID The AC-4 presentation substream group ID.
   * @return Whether there is an error during substream group paring.
   */
  private static boolean parseSubstreamGroupDSI(ParsableBitArray dataBitArray,
      Ac4Presentation ac4Presentation, int presentationID, int groupID) {
    dataBitArray.skipBit();  // b_substreams_present
    dataBitArray.skipBit();  // b_hsf_ext
    boolean bChannelCoded = dataBitArray.readBit();  // b_channel_coded
    int nSubstreams = dataBitArray.readBits(8);  // n_substreams

    for (int i = 0; i < nSubstreams; i++) {
      dataBitArray.skipBits(2);  // dsi_sf_multiplier

      boolean bSubstreamBitrateIndicator = dataBitArray.readBit();  // b_substream_bitrate_indicator
      if (bSubstreamBitrateIndicator) {
        dataBitArray.skipBits(5);  // substream_bitrate_indicator
      }

      if (bChannelCoded) {
        dataBitArray.skipBits(24);  // dsi_substream_channel_mask
      } else {
        boolean bAjoc = dataBitArray.readBit();  // b_ajoc
        if (bAjoc) {
          boolean bStaticDmx = dataBitArray.readBit();  // b_static_dmx
          if (!bStaticDmx) {
            dataBitArray.skipBits(4);  // n_dmx_objects_minus1
          }
          int nUmxObjectsMinus1 = dataBitArray.readBits(6);  // n_umx_objects_minus1
          ac4Presentation.numOfUmxObjects = nUmxObjectsMinus1 + 1;
        }
        dataBitArray.skipBits(4); // objects_assignment_mask
      }
    }

    boolean bContentType = dataBitArray.readBit();  // b_content_type
    if (bContentType) {
      ac4Presentation.contentClassifier = dataBitArray.readBits(3);  // content_classifier

      boolean bLanguageIndicator = dataBitArray.readBit();  // b_language_indicator
      if (bLanguageIndicator) {
        if (!parseLanguageTag(dataBitArray, ac4Presentation, presentationID, groupID)) {
          return false;
        }
      }
    }

    return true;
  }

  private static int readVariableBits(ParsableBitArray data, int bitsPerRead) {
    int value = 0;
    while (true) {
      value += data.readBits(bitsPerRead);
      if (!data.readBit()) {
        break;
      }
      value++;
      value <<= bitsPerRead;
    }
    return value;
  }

  private Ac4Util() {}
}
