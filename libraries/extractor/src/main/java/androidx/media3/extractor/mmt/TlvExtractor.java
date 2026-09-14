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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import java.io.EOFException;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts data from an MMT (MPEG Media Transport) elementary stream carried in TLV
 * (Type-Length-Value) packets, as used by ISDB-S3 4K/8K broadcasting (ARIB STD-B32 / STD-B60).
 *
 * <p>This extractor is responsible for the outermost transport layers only: it parses TLV packets,
 * unwraps the IPv4/IPv6/header-compressed IP + UDP encapsulation, and passes the resulting MMTP
 * packets to an {@link MmtpReader}. The {@link MmtpReader} performs MMTP header parsing, MPU/MFU
 * reassembly and signaling handling to produce media samples.
 *
 * <p>TLV packet syntax (ARIB STD-B32 Part 3):
 *
 * <pre>
 *   TLV_packet() {
 *     sync_byte          8 bits   // fixed 0x7F
 *     packet_type        8 bits   // 0x01 IPv4, 0x02 IPv6, 0x03 header-compressed IP,
 *                                 // 0xFE transmission control signal, 0xFF null
 *     data_length       16 bits
 *     data_byte         data_length bytes
 *   }
 * </pre>
 */
@UnstableApi
public final class TlvExtractor implements Extractor {

  /** Factory for {@link TlvExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new TlvExtractor()};

  /** Fixed value of the first byte of every TLV packet. */
  public static final int TLV_SYNC_BYTE = 0x7F;

  private static final int TLV_PACKET_TYPE_IPV4 = 0x01;
  private static final int TLV_PACKET_TYPE_IPV6 = 0x02;
  private static final int TLV_PACKET_TYPE_COMPRESSED_IP = 0x03;
  private static final int TLV_PACKET_TYPE_SIGNALLING = 0xFE;
  private static final int TLV_PACKET_TYPE_NULL = 0xFF;

  private static final int TLV_HEADER_SIZE = 4;
  private static final int IP_PROTOCOL_UDP = 17;
  private static final int UDP_HEADER_SIZE = 8;
  private static final int IPV6_HEADER_SIZE = 40;

  /** Number of consecutive well-formed TLV packets required for a successful {@link #sniff}. */
  private static final int SNIFF_TLV_PACKET_COUNT = 5;

  private final ParsableByteArray tlvHeader;
  private final ParsableByteArray tlvPayload;
  private final MmtpReader mmtpReader;

  private @MonotonicNonNull ExtractorOutput output;
  private boolean tracksEnded;

  public TlvExtractor() {
    tlvHeader = new ParsableByteArray(TLV_HEADER_SIZE);
    tlvPayload = new ParsableByteArray(/* limit= */ 0);
    mmtpReader = new MmtpReader();
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    byte[] header = new byte[TLV_HEADER_SIZE];
    int bytesPeeked = 0;
    for (int i = 0; i < SNIFF_TLV_PACKET_COUNT; i++) {
      if (!input.peekFully(header, /* offset= */ 0, TLV_HEADER_SIZE, /* allowEndOfInput= */ true)) {
        return i > 0;
      }
      if ((header[0] & 0xFF) != TLV_SYNC_BYTE) {
        return false;
      }
      int packetType = header[1] & 0xFF;
      if (!isKnownPacketType(packetType)) {
        return false;
      }
      int dataLength = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
      try {
        input.advancePeekPosition(dataLength);
      } catch (EOFException e) {
        // The stream ended mid-packet; treat as a match if we already saw a well-formed packet.
        return i > 0;
      }
      bytesPeeked += TLV_HEADER_SIZE + dataLength;
      // Reset the peek buffer periodically so we don't require an unbounded peek window.
      if (bytesPeeked > 512 * 1024) {
        break;
      }
    }
    return true;
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
    mmtpReader.init(output);
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    checkNotNull(output);
    if (!readTlvHeader(input)) {
      return RESULT_END_OF_INPUT;
    }
    int packetType = tlvHeader.getData()[1] & 0xFF;
    tlvHeader.setPosition(2);
    int dataLength = tlvHeader.readUnsignedShort();
    if (dataLength > 0) {
      prepareTlvPayload(input, dataLength);
      processTlvPayload(packetType);
    }
    if (!tracksEnded) {
      // Media tracks may be created lazily once signaling has been parsed. endTracks() is called by
      // the reader when the package table has been resolved; before that we present no tracks.
      tracksEnded = mmtpReader.maybeEndTracks();
    }
    return RESULT_CONTINUE;
  }

  @Override
  public void seek(long position, long timeUs) {
    // MMT/TLV streams are treated as live/unseekable for now (see seekMap in MmtpReader).
    mmtpReader.seek();
  }

  @Override
  public void release() {
    // Do nothing.
  }

  /**
   * Reads the 4-byte TLV packet header into {@link #tlvHeader}, resynchronising on the sync byte if
   * necessary.
   *
   * @return Whether a header was read. False indicates the end of the stream.
   */
  private boolean readTlvHeader(ExtractorInput input) throws IOException {
    // Resynchronise to the next sync byte to tolerate corrupted or partially delivered packets.
    byte[] headerData = tlvHeader.getData();
    if (!input.readFully(headerData, /* offset= */ 0, /* length= */ 1, /* allowEndOfInput= */ true)) {
      return false;
    }
    int resyncAttempts = 0;
    while ((headerData[0] & 0xFF) != TLV_SYNC_BYTE) {
      if (++resyncAttempts > 188 * 8) {
        // Give up resynchronising after a reasonable amount of data.
        return false;
      }
      if (!input.readFully(headerData, /* offset= */ 0, /* length= */ 1, /* allowEndOfInput= */ true)) {
        return false;
      }
    }
    if (!input.readFully(
        headerData, /* offset= */ 1, /* length= */ TLV_HEADER_SIZE - 1, /* allowEndOfInput= */ true)) {
      return false;
    }
    tlvHeader.setPosition(0);
    return true;
  }

  private void prepareTlvPayload(ExtractorInput input, int dataLength) throws IOException {
    if (dataLength > tlvPayload.capacity()) {
      tlvPayload.reset(new byte[max(tlvPayload.capacity() * 2, dataLength)], dataLength);
    } else {
      tlvPayload.setPosition(0);
      tlvPayload.setLimit(dataLength);
    }
    input.readFully(tlvPayload.getData(), /* offset= */ 0, dataLength);
  }

  private void processTlvPayload(int packetType) {
    switch (packetType) {
      case TLV_PACKET_TYPE_IPV4:
        processIpv4Packet(tlvPayload);
        break;
      case TLV_PACKET_TYPE_IPV6:
        processIpv6Packet(tlvPayload);
        break;
      case TLV_PACKET_TYPE_COMPRESSED_IP:
        processCompressedIpPacket(tlvPayload);
        break;
      case TLV_PACKET_TYPE_SIGNALLING:
        // Transmission control signals (TLV-NIT / AMT). Not required for media playback.
        break;
      case TLV_PACKET_TYPE_NULL:
      default:
        // Null packets are stuffing and are ignored.
        break;
    }
  }

  /** Parses an uncompressed IPv4 datagram and dispatches the contained MMTP payload. */
  private void processIpv4Packet(ParsableByteArray packet) {
    if (packet.bytesLeft() < 20) {
      return;
    }
    int startPosition = packet.getPosition();
    int versionAndIhl = packet.readUnsignedByte();
    int version = versionAndIhl >> 4;
    int headerLength = (versionAndIhl & 0x0F) * 4;
    if (version != 4 || headerLength < 20) {
      return;
    }
    packet.skipBytes(8); // ToS, total length, identification, flags/fragment offset, TTL.
    int protocol = packet.readUnsignedByte();
    if (protocol != IP_PROTOCOL_UDP) {
      return;
    }
    packet.setPosition(startPosition + headerLength);
    dispatchUdpPayload(packet);
  }

  /** Parses an uncompressed IPv6 datagram and dispatches the contained MMTP payload. */
  private void processIpv6Packet(ParsableByteArray packet) {
    if (packet.bytesLeft() < IPV6_HEADER_SIZE) {
      return;
    }
    int startPosition = packet.getPosition();
    int versionAndClass = packet.readUnsignedByte();
    if ((versionAndClass >> 4) != 6) {
      return;
    }
    packet.skipBytes(5); // Remaining traffic class/flow label and payload length.
    int nextHeader = packet.readUnsignedByte();
    if (nextHeader != IP_PROTOCOL_UDP) {
      // TODO: Follow IPv6 extension header chain before the UDP header when present.
      return;
    }
    packet.setPosition(startPosition + IPV6_HEADER_SIZE);
    dispatchUdpPayload(packet);
  }

  /**
   * Parses an ARIB STD-B32 header-compressed IP packet.
   *
   * <p>Only the "no compressed header" case (context type {@code 0x61}, where the payload follows a
   * 2-byte context identification header) is currently unwrapped. Context establishing packets
   * (types {@code 0x20}/{@code 0x21}) that carry a reconstructed IP header are skipped.
   *
   * <p>TODO: Maintain per-CID header contexts and reconstruct compressed IP/UDP headers so that the
   * transmitted timestamps and ports are available.
   */
  private void processCompressedIpPacket(ParsableByteArray packet) {
    if (packet.bytesLeft() < 3) {
      return;
    }
    // context_id (12 bits) + sequence_number (4 bits) + context_identification_header_type (8 bits).
    packet.skipBytes(2); // CID + SN.
    int contextHeaderType = packet.readUnsignedByte();
    switch (contextHeaderType) {
      case 0x61: // Compressed header for partial IPv6 header + partial UDP header.
      case 0x60: // Compressed header for partial IPv4 header + partial UDP header.
        // The MMTP payload immediately follows the context identification header.
        dispatchMmtpPayload(packet);
        break;
      default:
        // 0x20 / 0x21: full header transmission used to (re)establish a context. Skipped for now.
        break;
    }
  }

  private void dispatchUdpPayload(ParsableByteArray packet) {
    if (packet.bytesLeft() < UDP_HEADER_SIZE) {
      return;
    }
    packet.skipBytes(UDP_HEADER_SIZE); // Source/destination port, length, checksum.
    dispatchMmtpPayload(packet);
  }

  private void dispatchMmtpPayload(ParsableByteArray packet) {
    if (packet.bytesLeft() > 0) {
      mmtpReader.consume(packet);
    }
  }

  private static boolean isKnownPacketType(int packetType) {
    return packetType == TLV_PACKET_TYPE_IPV4
        || packetType == TLV_PACKET_TYPE_IPV6
        || packetType == TLV_PACKET_TYPE_COMPRESSED_IP
        || packetType == TLV_PACKET_TYPE_SIGNALLING
        || packetType == TLV_PACKET_TYPE_NULL;
  }

  /** Provides an unseekable {@link SeekMap} for the (live) MMT/TLV stream. */
  static SeekMap createUnseekableSeekMap() {
    return new SeekMap.Unseekable(C.TIME_UNSET);
  }
}
