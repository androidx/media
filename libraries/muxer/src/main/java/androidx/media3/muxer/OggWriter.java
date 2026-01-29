/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A writer for Ogg data. */
/* package */ final class OggWriter {

  // Ogg page constants
  private static final int OGG_PAGE_HEADER_SIZE_BYTES = 27; // Base size without segment table
  private static final int OGG_PAGE_HEADER_CRC_OFFSET = 22; // Offset to CRC in page header
  private static final int MAX_SEGMENT_COUNT = 255;
  private static final int MAX_SEGMENT_SIZE_BYTES = 255;
  private static final int MAX_PAGE_SIZE_BYTES =
      MAX_SEGMENT_COUNT * MAX_SEGMENT_SIZE_BYTES; // 65025
  private static final byte[] oggCapturePattern = Util.getUtf8Bytes("OggS");

  // Header types
  private static final byte HEADER_TYPE_BOS = 0x02; // Beginning of stream
  private static final byte HEADER_TYPE_EOS = 0x04; // End of stream
  private static final byte HEADER_TYPE_NEW_PAGE = 0x00; // New page
  private static final byte HEADER_TYPE_CONTINUATION = 0x01;

  // Opus-specific constants
  private static final byte[] opusCommentHeaderSignature = Util.getUtf8Bytes("OpusTags");

  private final WritableByteChannel outputChannel;
  private final int streamSerialNumber;
  private final ByteBuffer pageHeaderBuffer;
  private final ByteBuffer pageDataBuffer;
  private final String vendorString;
  private final List<Byte> segmentTable;

  private int pageSequenceNumber;
  // Granule position of the last packet completed on the current page.
  private long currentPageGranulePosition;
  // Accumulates total samples processed to calculate granule position at packet boundaries.
  private long streamGranulePosition;
  private boolean isContinuedPage;
  private boolean wroteIdentificationAndCommentHeader;

  /**
   * Creates a new OggWriter.
   *
   * @param outputChannel The {@link WritableByteChannel} to write to.
   * @param vendorString The vendor string to write to the Opus comment header.
   */
  OggWriter(WritableByteChannel outputChannel, String vendorString) {
    this.outputChannel = outputChannel;
    this.vendorString = vendorString;
    // A logical bitstream is identified by a unique serial number which is created randomly.
    // Using a random value increases the likelihood that Ogg files can be safely chained.
    streamSerialNumber = new Random().nextInt();
    pageHeaderBuffer =
        ByteBuffer.allocate(OGG_PAGE_HEADER_SIZE_BYTES + MAX_SEGMENT_COUNT)
            .order(ByteOrder.LITTLE_ENDIAN);
    pageDataBuffer = ByteBuffer.allocate(MAX_PAGE_SIZE_BYTES);
    segmentTable = new ArrayList<>();
    currentPageGranulePosition = -1;
  }

  /**
   * Sets the {@link Format} for the media stream.
   *
   * <p>This must be called before any samples are written.
   *
   * @param format The {@link Format} for the track. The {@link Format#sampleMimeType} must be
   *     {@link MimeTypes#AUDIO_OPUS}.
   * @throws IOException If an error occurs writing the header pages.
   */
  void setFormat(Format format) throws IOException {
    // Format can only be set once.
    checkState(!wroteIdentificationAndCommentHeader);
    String sampleMimeType = checkNotNull(format.sampleMimeType);
    switch (sampleMimeType) {
      case MimeTypes.AUDIO_OPUS:
        byte[] opusHead = CodecSpecificDataUtil.getOpusInitializationData(format);
        // Parse little-endian pre-skip at byte 10.
        int preSkip = ((opusHead[11] & 0xFF) << 8) | (opusHead[10] & 0xFF);
        this.streamGranulePosition = preSkip;
        writeOpusIdentificationHeader(format);
        writeOpusCommentHeader();
        break;
      default:
        throw new IllegalArgumentException("Unsupported MIME type: " + sampleMimeType);
    }
    wroteIdentificationAndCommentHeader = true;
  }

  /**
   * Writes sample data to the Ogg file.
   *
   * <p>The Ogg packets are written in Ogg pages. One page can contain multiple Ogg packets and one
   * packet can be split across multiple Ogg pages.
   *
   * @param sampleByteBuffer The encoded sample data.
   * @param sampleBufferInfo The {@link BufferInfo} related to this sample.
   * @throws IOException If an error occurs while writing data to the output file.
   */
  void writeSampleData(ByteBuffer sampleByteBuffer, BufferInfo sampleBufferInfo)
      throws IOException {
    checkState(wroteIdentificationAndCommentHeader);

    // Opus granule position = total PCM samples encoded up to the end of this packet.
    streamGranulePosition += OpusUtil.parsePacketAudioSampleCount(sampleByteBuffer);

    addPacketToPage(sampleByteBuffer, streamGranulePosition);
  }

  /** Closes the writer, flushing any remaining data and writing the End Of Stream page. */
  void close() throws IOException {
    try {
      if (wroteIdentificationAndCommentHeader) {
        // Write the last page with the End Of Stream flag set.
        // The granule position should be the same as the last data page.
        writeOggPage(
            HEADER_TYPE_EOS, currentPageGranulePosition == -1 ? 0 : currentPageGranulePosition);
      }
    } finally {
      outputChannel.close();
    }
  }

  /**
   * Writes the Opus identification header to the Ogg file.
   *
   * <p>This header contains the Opus codec specific data and is written as a single page.
   */
  private void writeOpusIdentificationHeader(Format format) throws IOException {
    byte[] opusInitializationData = CodecSpecificDataUtil.getOpusInitializationData(format);
    pageDataBuffer.put(opusInitializationData);
    segmentTable.add((byte) opusInitializationData.length);
    writeOggPage(HEADER_TYPE_BOS, /* granulePosition= */ 0);
  }

  /**
   * Writes the Opus comment header to the Ogg file.
   *
   * <p>This header contains the vendor string and is written as a single page.
   */
  private void writeOpusCommentHeader() throws IOException {
    byte[] vendorBytes = Util.getUtf8Bytes(vendorString);
    int payloadSize =
        opusCommentHeaderSignature.length
            + 4
            + vendorBytes.length
            + 4; // Signature + len + string + user_comment_list_length (0)
    ByteBuffer commentHeaderPayload =
        ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);
    commentHeaderPayload.put(opusCommentHeaderSignature);
    commentHeaderPayload.putInt(vendorBytes.length);
    commentHeaderPayload.put(vendorBytes);
    commentHeaderPayload.putInt(0); // user_comment_list_length = 0
    commentHeaderPayload.flip();
    pageDataBuffer.put(commentHeaderPayload);
    segmentTable.add((byte) payloadSize);
    writeOggPage(HEADER_TYPE_NEW_PAGE, /* granulePosition= */ 0);
  }

  /**
   * Adds a packet to the pages.
   *
   * <p>If the packet fits in a single page, it is written as is. Otherwise, it is split into
   * multiple pages.
   */
  private void addPacketToPage(ByteBuffer packet, long packetGranulePosition) throws IOException {
    int bytesToWrite;

    do {
      // Calculate bytes to write in page.
      bytesToWrite = Math.min(packet.remaining(), MAX_SEGMENT_SIZE_BYTES);

      // Page flush check.
      if (segmentTable.size() == MAX_SEGMENT_COUNT || pageDataBuffer.remaining() < bytesToWrite) {
        // Flush the page using the granule of the last packet that actually finished on this page.
        writeOggPage(HEADER_TYPE_NEW_PAGE, this.currentPageGranulePosition);
        isContinuedPage = true;
        // Reset the page granule position as no packet finished on this page.
        this.currentPageGranulePosition = -1;
      }

      segmentTable.add((byte) bytesToWrite);
      int oldLimit = packet.limit();
      packet.limit(packet.position() + bytesToWrite);
      pageDataBuffer.put(packet);
      packet.limit(oldLimit);
      // If bytesToWrite is 255, we must continue the packet in another segment, even if it's
      // 0-length to terminate it.
    } while (bytesToWrite == MAX_SEGMENT_SIZE_BYTES);
    // Update Granule (only when packet actually finishes).
    this.currentPageGranulePosition = packetGranulePosition;
  }

  /** Writes a complete Ogg page to the output channel. */
  private void writeOggPage(byte headerType, long granulePosition) throws IOException {

    if (isContinuedPage) {
      headerType |= HEADER_TYPE_CONTINUATION;
    }

    // capture_pattern (4 bytes, "OggS")
    pageHeaderBuffer.put(oggCapturePattern);

    // version (1 byte, 0)
    pageHeaderBuffer.put((byte) 0);

    // header_type (1 byte)
    pageHeaderBuffer.put(headerType);

    // granule_position (8 bytes)
    pageHeaderBuffer.putLong(granulePosition);

    // bitstream_serial_number (4 bytes)
    pageHeaderBuffer.putInt(streamSerialNumber);

    // page_sequence_number (4 bytes)
    pageHeaderBuffer.putInt(pageSequenceNumber++);

    // checksum (4 bytes, placeholder 0)
    pageHeaderBuffer.putInt(0);

    // segment_table_size (1 byte)
    pageHeaderBuffer.put((byte) segmentTable.size());

    // segment_table (numSegments bytes)
    for (int i = 0; i < segmentTable.size(); i++) {
      pageHeaderBuffer.put(segmentTable.get(i));
    }
    pageHeaderBuffer.flip();
    int crc32 =
        Util.crc32(
            pageHeaderBuffer.array(),
            pageHeaderBuffer.position(),
            pageHeaderBuffer.remaining(),
            /* initialValue= */ 0);
    pageDataBuffer.flip();
    crc32 =
        Util.crc32(
            pageDataBuffer.array(), pageDataBuffer.position(), pageDataBuffer.remaining(), crc32);
    pageHeaderBuffer.position(OGG_PAGE_HEADER_CRC_OFFSET);
    pageHeaderBuffer.putInt(crc32);
    pageHeaderBuffer.position(0);

    outputChannel.write(pageHeaderBuffer);
    outputChannel.write(pageDataBuffer);
    pageHeaderBuffer.clear();
    pageDataBuffer.clear();
    segmentTable.clear();
    isContinuedPage = false;
  }
}
