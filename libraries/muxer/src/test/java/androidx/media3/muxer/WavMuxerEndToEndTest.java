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

import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link WavMuxer}. */
@RunWith(AndroidJUnit4.class)
public class WavMuxerEndToEndTest {
  private static final Format RAW_AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_RAW)
          .setPcmEncoding(C.ENCODING_PCM_16BIT)
          .setChannelCount(1)
          .setSampleRate(44_100)
          .build();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void addTrack_withUnsupportedAacAudioFormat_throws() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();
    Format aacAudioFormat =
        new Format.Builder()
            .setSampleMimeType(AUDIO_AAC)
            .setCodecs("mp4a.40.2")
            .setSampleRate(44_100)
            .setChannelCount(2)
            .setInitializationData(ImmutableList.of(createByteArray(0x12, 0x08)))
            .build();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      assertThrows(IllegalArgumentException.class, () -> wavMuxer.addTrack(aacAudioFormat));
    }
  }

  @Test
  public void addTrack_withZeroChannelCount_throws() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();
    Format zeroChannelFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(0)
            .setSampleRate(44_100)
            .build();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      assertThrows(IllegalArgumentException.class, () -> wavMuxer.addTrack(zeroChannelFormat));
    }
  }

  @Test
  public void addTrack_withZeroSampleRate_throws() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();
    Format zeroSampleRateFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(1)
            .setSampleRate(0)
            .build();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      assertThrows(IllegalArgumentException.class, () -> wavMuxer.addTrack(zeroSampleRateFormat));
    }
  }

  @Test
  public void addTrack_secondTime_throws() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      int unused = wavMuxer.addTrack(RAW_AUDIO_FORMAT);

      assertThrows(IllegalStateException.class, () -> wavMuxer.addTrack(RAW_AUDIO_FORMAT));
    }
  }

  @Test
  public void createWavFile_withEvenSizedSampleData_doesNotAddExtraPadding() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();
    byte[] evenSizedSample = new byte[] {1, 2, 3, 4};

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      int trackId = wavMuxer.addTrack(RAW_AUDIO_FORMAT);
      wavMuxer.writeSampleData(
          trackId,
          ByteBuffer.wrap(evenSizedSample),
          new BufferInfo(/* presentationTimeUs= */ 0, /* size= */ 4, /* flags= */ 0));
    }

    byte[] bytesFromOutputFile = TestUtil.getByteArrayFromFilePath(outputFilePath);
    int fixedHeaderSize = 44;
    assertThat(bytesFromOutputFile).hasLength(fixedHeaderSize + evenSizedSample.length);
  }

  @Test
  public void createWavFile_withOddSizedSampleData_addsExtraPadding() throws Exception {
    String outputFilePath = temporaryFolder.newFile("muxeroutput.wav").getPath();
    byte[] oddSizedSample = new byte[] {1, 2, 3};

    try (WavMuxer wavMuxer = new WavMuxer(SeekableMuxerOutput.of(outputFilePath))) {
      int trackId = wavMuxer.addTrack(RAW_AUDIO_FORMAT);
      wavMuxer.writeSampleData(
          trackId,
          ByteBuffer.wrap(oddSizedSample),
          new BufferInfo(/* presentationTimeUs= */ 0, /* size= */ 3, /* flags= */ 0));
    }

    byte[] bytesFromOutputFile = TestUtil.getByteArrayFromFilePath(outputFilePath);
    int fixedHeaderSize = 44;
    int padding = 1;
    assertThat(bytesFromOutputFile).hasLength(fixedHeaderSize + oddSizedSample.length + padding);
  }
}
