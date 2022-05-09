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
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Parses an VP9 byte stream carried on RTP packets, and extracts VP9 Access Units. Refer to <a
 * href=https://datatracker.ietf.org/doc/html/draft-ietf-payload-vp9> this draft RFC</a> for more
 * details.
 */
/* package */ final class RtpVp9Reader implements RtpPayloadReader {

  private static final String TAG = "RtpVp9Reader";

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final int SCALABILITY_STRUCTURE_SIZE = 4;

  private final RtpPayloadFormat payloadFormat;

  private @MonotonicNonNull TrackOutput trackOutput;

  /**
   * First received RTP timestamp. All RTP timestamps are dimension-less, the time base is defined
   * by {@link #MEDIA_CLOCK_FREQUENCY}.
   */
  private long firstReceivedTimestamp;
  private long startTimeOffsetUs;
  private int previousSequenceNumber;
  /** The combined size of a sample that is fragmented into multiple RTP packets. */
  private int fragmentedSampleSizeBytes;
  private int width;
  private int height;
  /**
   * Whether the first packet of one VP9 frame is received, it mark the start of a VP9 partition.
   * A VP9 frame can be split into multiple RTP packets.
   */
  private boolean gotFirstPacketOfVP9Frame;
  private boolean isKeyFrame;
  private boolean isOutputFormatSet;

  /** Creates an instance. */
  public RtpVp9Reader(RtpPayloadFormat payloadFormat) {
    this.payloadFormat = payloadFormat;
    firstReceivedTimestamp = C.TIME_UNSET;
    startTimeOffsetUs = 0;    // The start time offset must be 0 until the first seek.
    previousSequenceNumber = C.INDEX_UNSET;
    fragmentedSampleSizeBytes = 0;
    width = C.LENGTH_UNSET;
    height = C.LENGTH_UNSET;
    gotFirstPacketOfVP9Frame = false;
    isKeyFrame = false;
    isOutputFormatSet = false;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_VIDEO);
    trackOutput.format(payloadFormat.format);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {
    checkStateNotNull(trackOutput);

    if (validateVp9Descriptor(data, sequenceNumber)) {
      if (fragmentedSampleSizeBytes == 0 && gotFirstPacketOfVP9Frame) {
        isKeyFrame = (data.peekUnsignedByte() & 0x04) == 0;
      }

      if (!isOutputFormatSet && width != C.LENGTH_UNSET  && height != C.LENGTH_UNSET) {
        if (width != payloadFormat.format.width || height != payloadFormat.format.height) {
          trackOutput.format(
              payloadFormat.format.buildUpon().setWidth(width).setHeight(height).build());
        }
        isOutputFormatSet = true;
      }

      int fragmentSize = data.bytesLeft();
      // Write the video sample.
      trackOutput.sampleData(data, fragmentSize);
      fragmentedSampleSizeBytes += fragmentSize;

      if (rtpMarker) {
        if (firstReceivedTimestamp == C.TIME_UNSET) {
          firstReceivedTimestamp = timestamp;
        }
        long timeUs = toSampleUs(startTimeOffsetUs, timestamp, firstReceivedTimestamp);
        trackOutput.sampleMetadata(
            timeUs,
            isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0,
            fragmentedSampleSizeBytes,
            /* offset= */ 0,
            /* cryptoData= */ null);
        fragmentedSampleSizeBytes = 0;
        gotFirstPacketOfVP9Frame = false;
      }
      previousSequenceNumber = sequenceNumber;
    }
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    fragmentedSampleSizeBytes = 0;
    startTimeOffsetUs = timeUs;
  }

  // Internal methods.
  private static long toSampleUs(
      long startTimeOffsetUs, long rtpTimestamp, long firstReceivedRtpTimestamp) {
    return startTimeOffsetUs
        + Util.scaleLargeTimestamp(
            (rtpTimestamp - firstReceivedRtpTimestamp),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
  }

  /**
   * Returns {@code true} and sets the {@link ParsableByteArray#getPosition() payload.position} to
   * the end of the descriptor, if a valid VP9 descriptor is present.
   */
  private boolean validateVp9Descriptor(ParsableByteArray payload, int packetSequenceNumber)
      throws ParserException {
    // VP9 Payload Descriptor, Section 4.2
    //         0 1 2 3 4 5 6 7
    //        +-+-+-+-+-+-+-+-+
    //        |I|P|L|F|B|E|V|Z| (REQUIRED)
    //        +-+-+-+-+-+-+-+-+
    //   I:   |M| PICTURE ID  | (RECOMMENDED)
    //        +-+-+-+-+-+-+-+-+
    //   M:   | EXTENDED PID  | (RECOMMENDED)
    //        +-+-+-+-+-+-+-+-+
    //   L:   | TID |U| SID |D| (Conditionally RECOMMENDED)
    //        +-+-+-+-+-+-+-+-+
    //        |   TL0PICIDX   | (Conditionally REQUIRED)
    //        +-+-+-+-+-+-+-+-+
    //   V:   | SS            |
    //        | ..            |
    //        +-+-+-+-+-+-+-+-+

    int header = payload.readUnsignedByte();
    if (!gotFirstPacketOfVP9Frame) {
      if ((header & 0x08) == 0) {
        Log.w(
            TAG,
            "First payload octet of the RTP packet is not the beginning of a new VP9 partition,"
                + " Dropping current packet.");
        return false;
      }
      gotFirstPacketOfVP9Frame = true;
    } else {
      // Check that this packet is in the sequence of the previous packet.
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (packetSequenceNumber != expectedSequenceNumber) {
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d."
                    + " Dropping packet.",
                expectedSequenceNumber, packetSequenceNumber));
        return false;
      }
    }

    // Check if optional I header is present.
    if ((header & 0x80) != 0) {
      int optionalHeader = payload.readUnsignedByte();
      // Check M for 15 bits PictureID.
      if ((optionalHeader & 0x80) != 0) {
        if (payload.bytesLeft() < 1) {
          return false;
        }
      }
    }

    // Flexible-mode not implemented.
    checkArgument((header & 0x10) == 0, "VP9 flexible mode unsupported");

    // Check if optional L header is present.
    if ((header & 0x20) != 0) {
      payload.skipBytes(1);
      if (payload.bytesLeft() < 1) {
        return false;
      }
      // Check if TL0PICIDX header present (non-flexible mode).
      if ((header & 0x10) == 0) {
        payload.skipBytes(1);
      }
    }

    // Check if optional V header is present, Refer Section 4.2.1.
    if ((header & 0x02) != 0) {
      int scalabilityStructure = payload.readUnsignedByte();
      int numSpatialLayers = (scalabilityStructure >> 5) & 0x7 ;
      int scalabilityStructureLength =
          ((scalabilityStructure & 0x10) != 0) ? numSpatialLayers + 1 : 0;

      if ((scalabilityStructure & 0x10) != 0) {
        if (payload.bytesLeft() < scalabilityStructureLength * SCALABILITY_STRUCTURE_SIZE) {
          return false;
        }
        for (int index = 0; index < scalabilityStructureLength; index++) {
          width = payload.readUnsignedShort();
          height = payload.readUnsignedShort();
        }
      }

      // Check G bit, skips all additional temporal layers.
      if ((scalabilityStructure & 0x08) != 0) {
        // Reads N_G.
        int numOfPicInPictureGroup = payload.readUnsignedByte();
        if (payload.bytesLeft() < numOfPicInPictureGroup) {
          return false;
        }
        for (int picIndex = 0; picIndex < numOfPicInPictureGroup; picIndex++) {
          int picture = payload.readUnsignedShort();
          int referenceIndices = (picture & 0x0C) >> 2;
          if (payload.bytesLeft() < referenceIndices) {
            return false;
          }
          // Ignore Reference indices
          payload.skipBytes(referenceIndices);
        }
      }
    }
    return true;
  }
}
