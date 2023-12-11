/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.MpeghUtil;
import androidx.media3.extractor.TrackOutput;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Parses a continuous MPEG-H audio byte stream and extracts MPEG-H frames. */
@UnstableApi
public final class MpeghReader implements ElementaryStreamReader {

  private static final String TAG = "MpeghReader";

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private final ParsableByteArray dataBuffer;
  private final ParsableBitArray dataBitBuffer;
  private int dataInBuffer;

  private MpeghUtil.FrameInfo prevFrameInfo;

  // The timestamp to attach to the next sample in the current packet.
  private double timeUs;
  private double timeUsPending;
  private boolean dataPending;
  private boolean rapPending;
  private boolean raiSet;
  private boolean daiSet;

  public MpeghReader() {
    dataBuffer = new ParsableByteArray(0);
    dataBitBuffer = new ParsableBitArray();
    clearDataBuffer();
    timeUs = C.TIME_UNSET;
    timeUsPending = C.TIME_UNSET;
    prevFrameInfo = new MpeghUtil.FrameInfo();
  }

  @Override
  public void seek() {
    clearDataBuffer();
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(
      ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    raiSet = (flags & FLAG_RANDOM_ACCESS_INDICATOR) == FLAG_RANDOM_ACCESS_INDICATOR;
    daiSet = (flags & FLAG_DATA_ALIGNMENT_INDICATOR) == FLAG_DATA_ALIGNMENT_INDICATOR;

    if (daiSet && dataInBuffer != 0) {
      Log.w(TAG, "Internal bit buffer was unexpectedly not empty at data aligned PES");
      clearDataBuffer();
    }

    if (dataInBuffer > 0) {
      dataPending = true;
    }

    if (pesTimeUs != C.TIME_UNSET) {
      if (dataPending) {
        timeUsPending = pesTimeUs;
      } else {
        timeUs = pesTimeUs;
      }
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    // write the PES payload to a data buffer until the packet is complete
    appendToDataBuffer(data);
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    // try to find the sync packet and adjust the data buffer if necessary
    maybeFindSync();

    // get as many MPEG-H AUs as possible from the data buffer
    while (true) {
      dataBitBuffer.reset(dataBuffer);

      // check if a complete MPEG-H frame could be parsed
      if (!MpeghUtil.canParseFrame(dataBitBuffer)) {
        // parsing could not be completed because of not enough data
        break;
      }

      MpeghUtil.FrameInfo frameInfo;
      try {
        frameInfo = MpeghUtil.parseFrame(dataBitBuffer, prevFrameInfo);
      } catch (ParserException e) {
        // an error occurred --> maybe try to find sync and proceed with processing
        dataBitBuffer.byteAlign();
        removeUsedFromDataBuffer();
        rapPending = true;
        maybeFindSync();
        continue;
      }

      if (frameInfo.configChanged && frameInfo.containsConfig) {
        // set the output format
        String codecs = "mhm1";
        if (frameInfo.mpegh3daProfileLevelIndication != C.INDEX_UNSET) {
          codecs += String.format(".%02X", frameInfo.mpegh3daProfileLevelIndication);
        }
        @Nullable List<byte[]> initializationData = null;
        if (frameInfo.compatibleSetIndication != null
            && frameInfo.compatibleSetIndication.length > 0) {
          // The first entry in initializationData is reserved for the audio specific config.
          initializationData = ImmutableList.of(new byte[0], frameInfo.compatibleSetIndication);
        }
        Format format =
            new Format.Builder()
                .setId(formatId)
                .setSampleMimeType(MimeTypes.AUDIO_MPEGH_MHM1)
                .setSampleRate(frameInfo.samplingRate)
                .setCodecs(codecs)
                .setInitializationData(initializationData)
                .build();
        output.format(format);
      }

      // write AU to output
      dataBuffer.setPosition(0);
      output.sampleData(dataBuffer, frameInfo.frameBytes);

      int flag = 0;
      // if we have a frame with an mpegh3daConfig, set the first obtained AU to a key frame
      if (frameInfo.containsConfig) {
        flag = C.BUFFER_FLAG_KEY_FRAME;
        rapPending = false;
      }
      double sampleDurationUs =
          (double) C.MICROS_PER_SECOND * frameInfo.frameSamples / frameInfo.samplingRate;
      long pts = Math.round(timeUs);
      if (dataPending) {
        dataPending = false;
        timeUs = timeUsPending;
      } else {
        timeUs += sampleDurationUs;
      }
      output.sampleMetadata(pts, flag, frameInfo.frameBytes, 0, null);

      removeUsedFromDataBuffer();
      prevFrameInfo = frameInfo;
    }
  }

  private void maybeFindSync() {
    // we are still waiting for a RAP frame
    if (rapPending) {
      if (!raiSet) {
        // RAI is not signalled -> drop the PES data
        clearDataBuffer();
      } else {
        if (!daiSet) {
          // if RAI is signalled but the data is not aligned we need to find the sync packet
          int syncPosByte = MpeghUtil.findSyncPacket(dataBuffer);
          if (syncPosByte < 0) {
            // sync packet could not be found -> drop the PES data
            clearDataBuffer();
            return;
          }
          // sync packet was found -> remove PES data before the sync packet
          dataBuffer.setPosition(syncPosByte);
          removeUsedFromDataBuffer();
        }
      }
    }
  }

  private void clearDataBuffer() {
    dataPending = false;
    rapPending = true;
    dataInBuffer = 0;
    dataBuffer.reset(dataInBuffer);
  }

  private void appendToDataBuffer(ParsableByteArray data) {
    int bytesToRead = data.bytesLeft();
    dataBuffer.ensureCapacity(dataInBuffer + bytesToRead);
    System.arraycopy(
        data.getData(), data.getPosition(), dataBuffer.getData(), dataInBuffer, bytesToRead);
    data.skipBytes(bytesToRead);
    dataInBuffer += bytesToRead;
    dataBuffer.reset(dataInBuffer);
  }

  private void removeUsedFromDataBuffer() {
    dataInBuffer -= dataBuffer.getPosition();
    System.arraycopy(
        dataBuffer.getData(), dataBuffer.getPosition(), dataBuffer.getData(), 0, dataInBuffer);
    dataBuffer.reset(dataInBuffer);
  }
}
