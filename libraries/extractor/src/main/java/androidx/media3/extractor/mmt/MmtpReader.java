/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor.mmt;

import static java.lang.Math.max;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.H264Reader;
import androidx.media3.extractor.ts.H265Reader;
import androidx.media3.extractor.ts.SeiReader;
import androidx.media3.extractor.ts.TsPayloadReader;
import com.google.common.collect.ImmutableList;

/**
 * Parses MMTP (MMT Protocol) packets extracted from the TLV layer by {@link TlvExtractor} and
 * produces media samples.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Parse the MMTP packet header (ISO/IEC 23008-1 §9.3).
 *   <li>Route signaling packets to {@link MmtSignalingParser} to discover the assets that make up
 *       the MMT package (the MMT Package Table maps each {@code packet_id} to an asset type).
 *   <li>Route MPU packets to a per-{@code packet_id} {@link MpuAssembler} that reassembles MFU
 *       fragments into access units and forwards them to the matching {@link ElementaryStreamReader}.
 * </ul>
 */
/* package */ final class MmtpReader {

  private static final String TAG = "MmtpReader";

  /** MMTP payload types (ISO/IEC 23008-1 Table 8). */
  private static final int PAYLOAD_TYPE_MPU = 0x00;

  private static final int PAYLOAD_TYPE_SIGNALLING = 0x02;

  /** MPU fragment types (ISO/IEC 23008-1 §9.4). */
  private static final int MPU_FRAGMENT_TYPE_MPU_METADATA = 0x00;

  private static final int MPU_FRAGMENT_TYPE_MOVIE_FRAGMENT_METADATA = 0x01;
  private static final int MPU_FRAGMENT_TYPE_MFU = 0x02;

  /** Fragmentation indicator values (ISO/IEC 23008-1 §9.4). */
  private static final int FI_COMPLETE = 0;

  private static final int FI_FIRST = 1;
  private static final int FI_MIDDLE = 2;
  private static final int FI_LAST = 3;

  private final MmtSignalingParser signalingParser;
  private final SparseArray<MpuAssembler> assemblersByPacketId;
  private final TsPayloadReader.TrackIdGenerator idGenerator;

  @Nullable private ExtractorOutput output;
  private boolean packageResolved;
  private boolean tracksEnded;

  public MmtpReader() {
    signalingParser = new MmtSignalingParser();
    assemblersByPacketId = new SparseArray<>();
    // Match the id scheme used by the TS extractor so downstream renderers see stable ids.
    idGenerator = new TsPayloadReader.TrackIdGenerator(/* firstTrackId= */ 0, /* trackIdIncrement= */ 1);
  }

  public void init(ExtractorOutput output) {
    this.output = output;
  }

  public void seek() {
    for (int i = 0; i < assemblersByPacketId.size(); i++) {
      assemblersByPacketId.valueAt(i).reset();
    }
  }

  /**
   * Signals to the caller whether all media tracks have been declared. Tracks are declared lazily
   * once the MMT Package Table has been parsed.
   *
   * @return Whether {@link ExtractorOutput#endTracks()} has been called.
   */
  public boolean maybeEndTracks() {
    if (!tracksEnded && packageResolved && output != null) {
      output.endTracks();
      output.seekMap(TlvExtractor.createUnseekableSeekMap());
      tracksEnded = true;
    }
    return tracksEnded;
  }

  /** Consumes a single MMTP packet, whose position is set to the start of the MMTP header. */
  public void consume(ParsableByteArray packet) {
    if (packet.bytesLeft() < 2) {
      return;
    }
    int b0 = packet.readUnsignedByte();
    int b1 = packet.readUnsignedByte();
    boolean packetCounterFlag = ((b0 >> 5) & 0x1) != 0;
    boolean extensionFlag = ((b0 >> 1) & 0x1) != 0;
    int payloadType = b1 & 0x3F;
    if (packet.bytesLeft() < 10) {
      return;
    }
    int packetId = packet.readUnsignedShort();
    long timestamp = packet.readUnsignedInt(); // NTP short-format transmission timestamp.
    packet.skipBytes(4); // packet_sequence_number.
    if (packetCounterFlag) {
      if (packet.bytesLeft() < 4) {
        return;
      }
      packet.skipBytes(4); // packet_counter.
    }
    if (extensionFlag) {
      if (packet.bytesLeft() < 4) {
        return;
      }
      packet.skipBytes(2); // extension_header_type.
      int extensionLength = packet.readUnsignedShort();
      if (packet.bytesLeft() < extensionLength) {
        return;
      }
      packet.skipBytes(extensionLength);
    }
    switch (payloadType) {
      case PAYLOAD_TYPE_SIGNALLING:
        consumeSignalling(packet);
        break;
      case PAYLOAD_TYPE_MPU:
        consumeMpu(packetId, timestamp, packet);
        break;
      default:
        // FEC repair and other payload types are ignored.
        break;
    }
  }

  private void consumeSignalling(ParsableByteArray payload) {
    if (signalingParser.consume(payload)) {
      // The package table has (re)appeared. (Re)declare tracks for any newly discovered assets.
      maybeCreateAssemblers();
    }
  }

  private void maybeCreateAssemblers() {
    if (output == null || tracksEnded) {
      return;
    }
    ImmutableList<MmtSignalingParser.Asset> assets = signalingParser.getAssets();
    for (int i = 0; i < assets.size(); i++) {
      MmtSignalingParser.Asset asset = assets.get(i);
      if (assemblersByPacketId.get(asset.packetId) != null) {
        continue;
      }
      @Nullable ElementaryStreamReader reader = createReader(asset.assetType);
      if (reader == null) {
        continue;
      }
      reader.createTracks(output, idGenerator);
      assemblersByPacketId.put(asset.packetId, new MpuAssembler(reader, asset.assetType));
    }
    if (assemblersByPacketId.size() > 0) {
      packageResolved = true;
    }
  }

  /**
   * Creates an {@link ElementaryStreamReader} for the given MMT asset type (a four character code
   * such as {@code hev1} or {@code avc1}), or {@code null} if the asset type is not supported yet.
   */
  @Nullable
  private ElementaryStreamReader createReader(int assetType) {
    switch (assetType) {
      case MmtSignalingParser.ASSET_TYPE_HEV1:
      case MmtSignalingParser.ASSET_TYPE_HVC1:
        return new H265Reader(
            new SeiReader(/* closedCaptionFormats= */ ImmutableList.of(), MimeTypes.VIDEO_H265),
            MimeTypes.VIDEO_H265);
      case MmtSignalingParser.ASSET_TYPE_AVC1:
      case MmtSignalingParser.ASSET_TYPE_AVC3:
        return new H264Reader(
            new SeiReader(/* closedCaptionFormats= */ ImmutableList.of(), MimeTypes.VIDEO_H264),
            /* allowNonIdrKeyframes= */ false,
            /* detectAccessUnits= */ true,
            MimeTypes.VIDEO_H264);
      default:
        // TODO: Add MH-AAC / MPEG-H 3D audio ('mp4a', 'mh4a') and ARIB subtitle asset support.
        return null;
    }
  }

  private void consumeMpu(int packetId, long timestamp, ParsableByteArray payload) {
    @Nullable MpuAssembler assembler = assemblersByPacketId.get(packetId);
    if (assembler == null) {
      // Either signaling has not been parsed yet, or this asset type is unsupported.
      return;
    }
    if (payload.bytesLeft() < 8) {
      return;
    }
    payload.skipBytes(2); // MMTP payload length (redundant with the TLV/UDP lengths).
    int header = payload.readUnsignedByte();
    int fragmentType = (header >> 4) & 0x0F;
    boolean timed = ((header >> 3) & 0x1) != 0;
    int fragmentationIndicator = (header >> 1) & 0x3;
    boolean aggregated = (header & 0x1) != 0;
    payload.skipBytes(1); // fragmentation_counter.
    long mpuSequenceNumber = payload.readUnsignedInt();

    if (fragmentType != MPU_FRAGMENT_TYPE_MFU) {
      // MPU metadata / movie fragment metadata carry ISO-BMFF boxes; used only to refine timing.
      if (fragmentType == MPU_FRAGMENT_TYPE_MPU_METADATA
          || fragmentType == MPU_FRAGMENT_TYPE_MOVIE_FRAGMENT_METADATA) {
        assembler.consumeMetadata(fragmentType, mpuSequenceNumber, payload);
      }
      return;
    }

    try {
      if (aggregated) {
        while (payload.bytesLeft() > 2) {
          int dataUnitLength = payload.readUnsignedShort();
          if (dataUnitLength <= 0 || dataUnitLength > payload.bytesLeft()) {
            break;
          }
          int limit = payload.getPosition() + dataUnitLength;
          assembler.consumeTimedDataUnit(
              FI_COMPLETE, timed, mpuSequenceNumber, timestamp, payload, limit);
          payload.setPosition(limit);
        }
      } else {
        assembler.consumeTimedDataUnit(
            fragmentationIndicator,
            timed,
            mpuSequenceNumber,
            timestamp,
            payload,
            payload.limit());
      }
    } catch (ParserException e) {
      Log.w(TAG, "Discarding malformed MPU on packet_id " + packetId, e);
      assembler.reset();
    }
  }

  /**
   * Reassembles the MFU (Media Fragment Unit) data units of a single asset ({@code packet_id}) into
   * access units and forwards them to an {@link ElementaryStreamReader}.
   *
   * <p>For timed HEVC/AVC assets each access unit is a set of length-prefixed NAL units. They are
   * converted to Annex-B (start-code delimited) form so that the existing {@link H265Reader} /
   * {@link H264Reader} elementary stream readers can extract the samples and derive the {@link
   * androidx.media3.common.Format} from the in-band parameter sets.
   */
  private static final class MpuAssembler {

    /** Default presentation duration per sample when timing metadata is unavailable (30 fps). */
    private static final long DEFAULT_SAMPLE_DURATION_US = C.MICROS_PER_SECOND / 30;

    private static final int NAL_LENGTH_FIELD_SIZE = 4;

    private final ElementaryStreamReader reader;
    private final boolean isVideo;
    private final ParsableByteArray sampleData;

    private long sampleDurationUs;
    private long nextSampleTimeUs;
    private boolean assembling;

    public MpuAssembler(ElementaryStreamReader reader, int assetType) {
      this.reader = reader;
      this.isVideo =
          assetType == MmtSignalingParser.ASSET_TYPE_HEV1
              || assetType == MmtSignalingParser.ASSET_TYPE_HVC1
              || assetType == MmtSignalingParser.ASSET_TYPE_AVC1
              || assetType == MmtSignalingParser.ASSET_TYPE_AVC3;
      sampleData = new ParsableByteArray(/* limit= */ 0);
      sampleDurationUs = DEFAULT_SAMPLE_DURATION_US;
      nextSampleTimeUs = 0;
    }

    public void reset() {
      reader.seek();
      sampleData.setPosition(0);
      sampleData.setLimit(0);
      assembling = false;
    }

    /** Parses ISO-BMFF metadata boxes to refine the per-sample duration when possible. */
    public void consumeMetadata(int fragmentType, long mpuSequenceNumber, ParsableByteArray payload) {
      // TODO: Parse moov (mdhd timescale) and moof (tfhd/trun default_sample_duration) to derive
      // exact per-sample presentation times, and anchor them with the MPU_timestamp_descriptor from
      // the MMT Package Table. Until then a fixed frame rate is assumed for video assets.
    }

    /**
     * Consumes a single MFU data unit, reassembling fragmented access units.
     *
     * @param fragmentationIndicator One of the {@code FI_*} constants.
     * @param timed Whether the MPU is a timed asset.
     * @param mpuSequenceNumber The MPU sequence number (unused for now, see {@link
     *     #consumeMetadata}).
     * @param mmtpTimestampNtp The MMTP transmission timestamp (NTP short format), used only as a
     *     coarse anchor for the first sample.
     * @param payload The payload, positioned at the start of the data unit.
     * @param limit The exclusive end position of this data unit within {@code payload}.
     */
    public void consumeTimedDataUnit(
        int fragmentationIndicator,
        boolean timed,
        long mpuSequenceNumber,
        long mmtpTimestampNtp,
        ParsableByteArray payload,
        int limit)
        throws ParserException {
      boolean hasDataUnitHeader =
          fragmentationIndicator == FI_COMPLETE || fragmentationIndicator == FI_FIRST;
      if (timed && hasDataUnitHeader) {
        // Skip the 16-byte timed MFU data unit header: movie_fragment_sequence_number (4),
        // sample_number (4), offset (4), priority (1), dependency_counter (1) + 2 reserved.
        if (payload.getPosition() + 16 > limit) {
          throw ParserException.createForMalformedContainer(
              "Invalid timed MFU data unit", /* cause= */ null);
        }
        payload.skipBytes(16);
      }
      int length = limit - payload.getPosition();
      if (length <= 0) {
        return;
      }
      switch (fragmentationIndicator) {
        case FI_COMPLETE:
          startSample();
          appendSampleData(payload, length);
          emitSample(mmtpTimestampNtp);
          break;
        case FI_FIRST:
          startSample();
          appendSampleData(payload, length);
          break;
        case FI_MIDDLE:
          if (assembling) {
            appendSampleData(payload, length);
          } else {
            payload.skipBytes(length);
          }
          break;
        case FI_LAST:
          if (assembling) {
            appendSampleData(payload, length);
            emitSample(mmtpTimestampNtp);
          } else {
            payload.skipBytes(length);
          }
          break;
        default:
          payload.skipBytes(length);
          break;
      }
    }

    private void startSample() {
      sampleData.setPosition(0);
      sampleData.setLimit(0);
      assembling = true;
    }

    private void appendSampleData(ParsableByteArray payload, int length) {
      int currentLimit = sampleData.limit();
      int requiredCapacity = currentLimit + length;
      if (requiredCapacity > sampleData.capacity()) {
        byte[] grown = new byte[max(sampleData.capacity() * 2, requiredCapacity)];
        System.arraycopy(sampleData.getData(), 0, grown, 0, currentLimit);
        sampleData.reset(grown, currentLimit);
      }
      System.arraycopy(
          payload.getData(), payload.getPosition(), sampleData.getData(), currentLimit, length);
      payload.skipBytes(length);
      sampleData.setLimit(requiredCapacity);
    }

    private void emitSample(long mmtpTimestampNtp) {
      if (!assembling) {
        return;
      }
      assembling = false;
      int sampleSize = sampleData.limit();
      if (sampleSize <= 0) {
        return;
      }
      if (isVideo) {
        convertLengthPrefixedNalUnitsToAnnexB();
      }
      long timeUs = nextSampleTimeUs;
      nextSampleTimeUs += sampleDurationUs;
      sampleData.setPosition(0);
      reader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
      try {
        reader.consume(sampleData);
        reader.packetFinished();
      } catch (ParserException e) {
        Log.w(TAG, "Discarding sample that could not be parsed", e);
      }
    }

    /**
     * Rewrites the reassembled access unit in place, replacing each 4-byte NAL unit length prefix
     * with a 4-byte Annex-B start code ({@code 00 00 00 01}).
     */
    private void convertLengthPrefixedNalUnitsToAnnexB() {
      byte[] data = sampleData.getData();
      int limit = sampleData.limit();
      int position = 0;
      while (position + NAL_LENGTH_FIELD_SIZE <= limit) {
        int nalLength =
            ((data[position] & 0xFF) << 24)
                | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8)
                | (data[position + 3] & 0xFF);
        if (nalLength <= 0 || position + NAL_LENGTH_FIELD_SIZE + nalLength > limit) {
          // Not length-prefixed (or corrupt); leave the remainder untouched.
          break;
        }
        data[position] = 0x00;
        data[position + 1] = 0x00;
        data[position + 2] = 0x00;
        data[position + 3] = 0x01;
        position += NAL_LENGTH_FIELD_SIZE + nalLength;
      }
    }
  }
}
