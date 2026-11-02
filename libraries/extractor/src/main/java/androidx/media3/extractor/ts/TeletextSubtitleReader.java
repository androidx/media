/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TeletextSubtitleInfo;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Collections;
import java.util.List;

/**
 * Extracts raw DVB Teletext PES payloads (ETSI EN 300 472) for decoding by a {@link
 * androidx.media3.extractor.text.SubtitleParser}.
 */
@UnstableApi
public final class TeletextSubtitleReader implements ElementaryStreamReader {

  private static final int TELETEXT_TYPE_HEARING_IMPAIRED = 0x05;

  private final List<TeletextSubtitleInfo> subtitleInfos;
  private final String containerMimeType;
  private final TrackOutput[] outputs;

  private boolean writingSample;
  private int sampleBytesWritten;
  private long sampleTimeUs;

  /**
   * @param subtitleInfos Information about the Teletext subtitles associated to the stream.
   * @param containerMimeType The MIME type of the container holding the stream.
   */
  public TeletextSubtitleReader(
      List<TeletextSubtitleInfo> subtitleInfos, String containerMimeType) {
    this.subtitleInfos = subtitleInfos;
    this.containerMimeType = containerMimeType;
    outputs = new TrackOutput[subtitleInfos.size()];
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    writingSample = false;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    for (int i = 0; i < outputs.length; i++) {
      TeletextSubtitleInfo subtitleInfo = subtitleInfos.get(i);
      idGenerator.generateNewId();
      TrackOutput output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
      int roleFlags = C.ROLE_FLAG_SUBTITLE;
      if (subtitleInfo.teletextType == TELETEXT_TYPE_HEARING_IMPAIRED) {
        roleFlags |= C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND;
      }
      byte[] initializationData =
          new byte[] {(byte) subtitleInfo.magazineNumber, (byte) subtitleInfo.pageNumber};
      output.format(
          new Format.Builder()
              .setId(idGenerator.getFormatId())
              .setContainerMimeType(containerMimeType)
              .setSampleMimeType(MimeTypes.APPLICATION_TELETEXT)
              .setInitializationData(Collections.singletonList(initializationData))
              .setLanguage(subtitleInfo.language)
              .setRoleFlags(roleFlags)
              .build());
      outputs[i] = output;
    }
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs == C.TIME_UNSET) {
      writingSample = false;
      return;
    }
    writingSample = true;
    sampleTimeUs = pesTimeUs;
    sampleBytesWritten = 0;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (!writingSample) {
      return;
    }
    int dataPosition = data.getPosition();
    int bytesAvailable = data.bytesLeft();
    for (TrackOutput output : outputs) {
      data.setPosition(dataPosition);
      output.sampleData(data, bytesAvailable);
    }
    sampleBytesWritten += bytesAvailable;
  }

  @Override
  public void packetFinished() {
    if (writingSample) {
      checkState(sampleTimeUs != C.TIME_UNSET);
      if (sampleBytesWritten > 0) {
        for (TrackOutput output : outputs) {
          output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
        }
      }
      writingSample = false;
    }
  }
}
