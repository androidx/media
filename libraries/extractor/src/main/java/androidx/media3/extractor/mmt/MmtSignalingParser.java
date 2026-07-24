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

import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses MMT signaling messages to discover the assets of the current MMT package.
 *
 * <p>Only the subset required to map each media {@code packet_id} to a codec is parsed:
 *
 * <pre>
 *   MMTP signaling payload  -&gt; PA message (message_id 0x0000)  -&gt; MMT Package Table (id 0x20)
 * </pre>
 *
 * <p>The MMT Package Table (MPT) lists the assets that make up the package; for each asset it gives
 * a four character {@code asset_type} (for example {@code hev1}) and one or more locations. Assets
 * delivered on the same MMTP flow use location type {@code 0x00}, which carries the {@code
 * packet_id} directly.
 *
 * <p>Observed NHK BS4K/BS8K packages, for reference:
 *
 * <ul>
 *   <li>BS8K: {@code hev1} video, {@code mp4a} (MPEG-4 AAC) audio, and two {@code stpp} (TTML)
 *       subtitle assets.
 *   <li>BS4K: {@code hev1} video, two {@code mp4a} audio assets, {@code stpp} subtitles, and
 *       several {@code aapp} data-broadcasting (application) assets.
 * </ul>
 *
 * <p>These streams set {@code number_of_tables} to 0 in the PA message and inline the tables
 * directly, so {@link #parsePaMessage} walks the concatenated tables rather than trusting the
 * (empty) table index.
 */
/* package */ final class MmtSignalingParser {

  /** A media asset discovered in the MMT Package Table. */
  public static final class Asset {
    /** The {@code packet_id} of the MMTP flow carrying the asset. */
    public final int packetId;

    /** The MMT {@code asset_type}, a four character code (see {@code ASSET_TYPE_*}). */
    public final int assetType;

    public Asset(int packetId, int assetType) {
      this.packetId = packetId;
      this.assetType = assetType;
    }
  }

  /** {@code asset_type} four character codes. */
  public static final int ASSET_TYPE_HEV1 = 0x68657631; // "hev1"

  public static final int ASSET_TYPE_HVC1 = 0x68766331; // "hvc1"
  public static final int ASSET_TYPE_AVC1 = 0x61766331; // "avc1"
  public static final int ASSET_TYPE_AVC3 = 0x61766333; // "avc3"

  private static final String TAG = "MmtSignalingParser";

  private static final int MESSAGE_ID_PA = 0x0000;
  private static final int TABLE_ID_MPT = 0x20;
  private static final int LOCATION_TYPE_SAME_FLOW = 0x00;
  private static final int LOCATION_TYPE_IPV4 = 0x01;
  private static final int LOCATION_TYPE_IPV6 = 0x02;

  // Signaling payload fragmentation indicator values.
  private static final int FI_COMPLETE = 0;
  private static final int FI_FIRST = 1;
  private static final int FI_MIDDLE = 2;
  private static final int FI_LAST = 3;

  private final ParsableByteArray messageBuffer;

  private ImmutableList<Asset> assets;
  private boolean assembling;

  public MmtSignalingParser() {
    messageBuffer = new ParsableByteArray(/* limit= */ 0);
    assets = ImmutableList.of();
  }

  /** Returns the assets discovered so far. */
  public ImmutableList<Asset> getAssets() {
    return assets;
  }

  /**
   * Consumes a signaling MMTP payload, positioned at the start of the signaling message header.
   *
   * @return Whether the set of {@linkplain #getAssets() assets} was updated.
   */
  public boolean consume(ParsableByteArray payload) {
    if (payload.bytesLeft() < 2) {
      return false;
    }
    int header = payload.readUnsignedByte();
    int fragmentationIndicator = (header >> 6) & 0x3;
    boolean lengthExtensionFlag = ((header >> 1) & 0x1) != 0;
    boolean aggregated = (header & 0x1) != 0;
    payload.skipBytes(1); // fragmentation_counter.

    if (aggregated) {
      boolean updated = false;
      while (payload.bytesLeft() > (lengthExtensionFlag ? 4 : 2)) {
        long messageLength =
            lengthExtensionFlag ? payload.readUnsignedInt() : payload.readUnsignedShort();
        if (messageLength <= 0 || messageLength > payload.bytesLeft()) {
          break;
        }
        int limit = payload.getPosition() + (int) messageLength;
        updated |= parseSignalingMessage(payload, limit);
        payload.setPosition(limit);
      }
      return updated;
    }

    // Non-aggregated: the payload contains one (possibly fragmented) signaling message.
    switch (fragmentationIndicator) {
      case FI_COMPLETE:
        return parseSignalingMessage(payload, payload.limit());
      case FI_FIRST:
        startReassembly();
        appendToMessageBuffer(payload);
        return false;
      case FI_MIDDLE:
        if (assembling) {
          appendToMessageBuffer(payload);
        }
        return false;
      case FI_LAST:
        if (!assembling) {
          return false;
        }
        appendToMessageBuffer(payload);
        assembling = false;
        messageBuffer.setPosition(0);
        return parseSignalingMessage(messageBuffer, messageBuffer.limit());
      default:
        return false;
    }
  }

  private void startReassembly() {
    messageBuffer.setPosition(0);
    messageBuffer.setLimit(0);
    assembling = true;
  }

  private void appendToMessageBuffer(ParsableByteArray payload) {
    int length = payload.bytesLeft();
    int currentLimit = messageBuffer.limit();
    int required = currentLimit + length;
    if (required > messageBuffer.capacity()) {
      byte[] grown = new byte[max(messageBuffer.capacity() * 2, required)];
      System.arraycopy(messageBuffer.getData(), 0, grown, 0, currentLimit);
      messageBuffer.reset(grown, currentLimit);
    }
    System.arraycopy(
        payload.getData(), payload.getPosition(), messageBuffer.getData(), currentLimit, length);
    messageBuffer.setLimit(required);
  }

  /** Parses a single signaling message bounded by {@code limit}. Returns whether assets changed. */
  private boolean parseSignalingMessage(ParsableByteArray data, int limit) {
    try {
      if (limit - data.getPosition() < 3) {
        return false;
      }
      int messageId = data.readUnsignedShort();
      data.skipBytes(1); // version.
      if (messageId != MESSAGE_ID_PA) {
        // Only the PA message (which carries the MMT Package Table) is needed for asset discovery.
        return false;
      }
      // PA message: length is a 32-bit field.
      if (limit - data.getPosition() < 5) {
        return false;
      }
      data.skipBytes(4); // length.
      return parsePaMessage(data, limit);
    } catch (RuntimeException e) {
      Log.w(TAG, "Error parsing signaling message", e);
      return false;
    }
  }

  private boolean parsePaMessage(ParsableByteArray data, int limit) {
    if (limit - data.getPosition() < 1) {
      return false;
    }
    // The PA message begins with number_of_tables followed by an index of {table_id (8),
    // table_version (8), table_length (16)} entries, and then the tables themselves. In practice
    // ARIB STD-B60 streams (e.g. NHK BS4K/BS8K) set number_of_tables to 0 and inline the tables
    // directly, so the index cannot be relied upon. We therefore skip the index (when present) and
    // then walk the concatenated tables by their own {table_id, version, table_length} headers,
    // which is robust to both layouts.
    int numberOfTables = data.readUnsignedByte();
    data.skipBytes(Math.min(numberOfTables * 4, Math.max(0, limit - data.getPosition())));
    boolean updated = false;
    while (limit - data.getPosition() >= 4) {
      int tableStart = data.getPosition();
      int tableId = data.getData()[tableStart] & 0xFF;
      int tableLength = ((data.getData()[tableStart + 2] & 0xFF) << 8) | (data.getData()[tableStart + 3] & 0xFF);
      int tableLimit = Math.min(limit, tableStart + 4 + tableLength);
      if (tableId == TABLE_ID_MPT) {
        List<Asset> parsed = parseMmtPackageTable(data, tableLimit);
        if (parsed != null && !parsed.isEmpty()) {
          assets = ImmutableList.copyOf(parsed);
          updated = true;
        }
      }
      if (tableLimit <= tableStart) {
        break; // Guard against a zero-length table causing an infinite loop.
      }
      data.setPosition(tableLimit);
    }
    return updated;
  }

  /** Parses an MMT Package Table. Returns the assets, or {@code null} if parsing failed. */
  private List<Asset> parseMmtPackageTable(ParsableByteArray data, int limit) {
    if (limit - data.getPosition() < 4) {
      return null;
    }
    data.skipBytes(1); // table_id (0x20).
    data.skipBytes(1); // version.
    data.skipBytes(2); // length.
    if (limit - data.getPosition() < 2) {
      return null;
    }
    data.skipBytes(1); // reserved (6 bits) + MPT_mode (2 bits).
    int packageIdLength = data.readUnsignedByte();
    if (limit - data.getPosition() < packageIdLength + 2) {
      return null;
    }
    data.skipBytes(packageIdLength); // MMT_package_id_byte.
    int descriptorsLength = data.readUnsignedShort();
    if (limit - data.getPosition() < descriptorsLength + 1) {
      return null;
    }
    data.skipBytes(descriptorsLength); // MPT_descriptors_byte.
    int numberOfAssets = data.readUnsignedByte();
    List<Asset> parsed = new ArrayList<>();
    for (int i = 0; i < numberOfAssets; i++) {
      if (!parseAsset(data, limit, parsed)) {
        break;
      }
    }
    return parsed;
  }

  /** Parses a single asset entry, appending it to {@code out}. Returns whether to keep parsing. */
  private boolean parseAsset(ParsableByteArray data, int limit, List<Asset> out) {
    if (limit - data.getPosition() < 6) {
      return false;
    }
    data.skipBytes(1); // identifier_type.
    data.skipBytes(4); // asset_id_scheme.
    int assetIdLength = data.readUnsignedByte();
    if (limit - data.getPosition() < assetIdLength + 4 + 1 + 1) {
      return false;
    }
    data.skipBytes(assetIdLength); // asset_id_byte.
    int assetType = data.readInt(); // asset_type four character code.
    data.skipBytes(1); // reserved (7 bits) + asset_clock_relation_flag (1 bit).
    int locationCount = data.readUnsignedByte();
    int packetId = -1;
    for (int i = 0; i < locationCount; i++) {
      if (limit - data.getPosition() < 1) {
        return false;
      }
      int locationType = data.readUnsignedByte();
      switch (locationType) {
        case LOCATION_TYPE_SAME_FLOW:
          if (limit - data.getPosition() < 2) {
            return false;
          }
          int candidate = data.readUnsignedShort();
          if (packetId == -1) {
            packetId = candidate;
          }
          break;
        case LOCATION_TYPE_IPV4:
          data.skipBytes(12); // src_addr(4) + dst_addr(4) + dst_port(2) + packet_id(2).
          break;
        case LOCATION_TYPE_IPV6:
          data.skipBytes(36); // src_addr(16) + dst_addr(16) + dst_port(2) + packet_id(2).
          break;
        default:
          // TODO: Handle broadcast / URL location types. Bail out to avoid mis-parsing.
          return false;
      }
    }
    if (limit - data.getPosition() < 2) {
      return false;
    }
    int assetDescriptorsLength = data.readUnsignedShort();
    if (limit - data.getPosition() < assetDescriptorsLength) {
      return false;
    }
    // TODO: Parse MPU_timestamp_descriptor here to anchor presentation times.
    data.skipBytes(assetDescriptorsLength);
    if (packetId != -1) {
      out.add(new Asset(packetId, assetType));
    }
    return true;
  }
}
