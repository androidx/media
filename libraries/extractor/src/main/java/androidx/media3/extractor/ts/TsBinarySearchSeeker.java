/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.BinarySearchSeeker;
import androidx.media3.extractor.ExtractorInput;
import java.io.IOException;

/**
 * A seeker that supports seeking within TS stream using binary search.
 *
 * <p>This seeker uses the first and last DTS values within the stream, as well as the stream
 * duration to interpolate the DTS value of the seeking position. Then it performs binary search
 * within the stream to find a packets whose DTS value is within {@link #SEEK_TOLERANCE_US} from the
 * target DTS.
 */
/* package */ final class TsBinarySearchSeeker extends BinarySearchSeeker {

  private static final long SEEK_TOLERANCE_US = 100_000;
  private static final int MINIMUM_SEARCH_RANGE_BYTES = 5 * TsExtractor.TS_PACKET_SIZE;

  public TsBinarySearchSeeker(
      TimestampAdjuster timestampAdjuster,
      long streamDurationUs,
      long inputLength,
      int selectedPid,
      int timestampSearchBytes) {
    super(
        new DefaultSeekTimestampConverter(),
        new TsTimestampSeeker(selectedPid, timestampAdjuster, timestampSearchBytes),
        streamDurationUs,
        /* floorTimePosition= */ 0,
        /* ceilingTimePosition= */ streamDurationUs + 1,
        /* floorBytePosition= */ 0,
        /* ceilingBytePosition= */ inputLength,
        /* approxBytesPerFrame= */ TsExtractor.TS_PACKET_SIZE,
        MINIMUM_SEARCH_RANGE_BYTES);
  }

  /**
   * A {@link TimestampSeeker} implementation that looks for a given DTS timestamp at a given
   * position in a TS stream.
   *
   * <p>Given a DTS timestamp, and a position within a TS stream, this seeker will peek up to {@link
   * #timestampSearchBytes} from that stream position, look for all packets with PID equal to
   * SELECTED_PID, and then compare the DTS timestamps (if available) of these packets to the target
   * timestamp.
   */
  private static final class TsTimestampSeeker implements TimestampSeeker {

    private final TimestampAdjuster timestampAdjuster;
    private final ParsableByteArray packetBuffer;
    private final int selectedPid;
    private final int timestampSearchBytes;

    public TsTimestampSeeker(
        int selectedPid, TimestampAdjuster timestampAdjuster, int timestampSearchBytes) {
      this.selectedPid = selectedPid;
      this.timestampAdjuster = timestampAdjuster;
      this.timestampSearchBytes = timestampSearchBytes;
      packetBuffer = new ParsableByteArray();
    }

    @Override
    public TimestampSearchResult searchForTimestamp(ExtractorInput input, long targetTimestamp)
        throws IOException {
      long inputPosition = input.getPosition();
      int bytesToSearch = (int) min(timestampSearchBytes, input.getLength() - inputPosition);

      packetBuffer.reset(bytesToSearch);
      input.peekFully(packetBuffer.getData(), /* offset= */ 0, bytesToSearch);

      return searchForDtsValueInBuffer(packetBuffer, targetTimestamp, inputPosition);
    }

    private TimestampSearchResult searchForDtsValueInBuffer(
        ParsableByteArray packetBuffer, long targetDtsTimeUs, long bufferStartOffset) {
      int limit = packetBuffer.limit();

      long startOfLastPacketPosition = C.INDEX_UNSET;
      long endOfLastPacketPosition = C.INDEX_UNSET;
      long lastDtsTimeUsInRange = C.TIME_UNSET;

      while (packetBuffer.bytesLeft() >= TsExtractor.TS_PACKET_SIZE) {
        int startOfPacket =
            TsUtil.findSyncBytePosition(packetBuffer.getData(), packetBuffer.getPosition(), limit);
        int endOfPacket = startOfPacket + TsExtractor.TS_PACKET_SIZE;
        if (endOfPacket > limit) {
          break;
        }
        long dtsValue = TsUtil.readDtsFromPacket(packetBuffer, startOfPacket, selectedPid);
        if (dtsValue != C.TIME_UNSET) {
          long dtsTimeUs = timestampAdjuster.adjustTsTimestamp(dtsValue);
          if (dtsTimeUs > targetDtsTimeUs) {
            if (lastDtsTimeUsInRange == C.TIME_UNSET) {
              // First DTS timestamp is already over target.
              return TimestampSearchResult.overestimatedResult(dtsTimeUs, bufferStartOffset);
            } else {
              // Last DTS timestamp < target timestamp < this timestamp.
              return TimestampSearchResult.targetFoundResult(
                  bufferStartOffset + startOfLastPacketPosition);
            }
          } else if (dtsTimeUs + SEEK_TOLERANCE_US > targetDtsTimeUs) {
            long startOfPacketInStream = bufferStartOffset + startOfPacket;
            return TimestampSearchResult.targetFoundResult(startOfPacketInStream);
          }

          lastDtsTimeUsInRange = dtsTimeUs;
          startOfLastPacketPosition = startOfPacket;
        }
        packetBuffer.setPosition(endOfPacket);
        endOfLastPacketPosition = endOfPacket;
      }

      if (lastDtsTimeUsInRange != C.TIME_UNSET) {
        long endOfLastPacketPositionInStream = bufferStartOffset + endOfLastPacketPosition;
        return TimestampSearchResult.underestimatedResult(
            lastDtsTimeUsInRange, endOfLastPacketPositionInStream);
      } else {
        return TimestampSearchResult.NO_TIMESTAMP_IN_RANGE_RESULT;
      }
    }

    @Override
    public void onSeekFinished() {
      packetBuffer.reset(Util.EMPTY_BYTE_ARRAY);
    }
  }
}
