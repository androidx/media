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

import static androidx.media3.muxer.MuxerTestUtil.FailingCloseSeekableMuxerOutput.CLOSE_FAILURE_MESSAGE;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.MuxerTestUtil.FailingCloseSeekableMuxerOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link OggMuxer}. */
@RunWith(AndroidJUnit4.class)
public final class OggMuxerEndToEndTest {

  private static final Format OPUS_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_OPUS)
          .setChannelCount(2)
          .setSampleRate(48_000)
          .setInitializationData(
              ImmutableList.of(
                  // A minimal Opus identification header, as defined in RFC 7845, section 5.1.
                  TestUtil.createByteArray(
                      0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, // "OpusHead"
                      0x01, // Version.
                      0x02, // Channel count.
                      0x38, 0x01, // Pre-skip.
                      0x80, 0xBB, 0x00, 0x00, // Input sample rate.
                      0x00, 0x00, // Output gain.
                      0x00))) // Channel mapping family.
          .build();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void addTrack_afterClose_throws() throws Exception {
    OggMuxer muxer =
        new OggMuxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel()).build();
    muxer.close();

    assertThrows(IllegalStateException.class, () -> muxer.addTrack(OPUS_FORMAT));
  }

  @Test
  public void writeSampleData_afterClose_throws() throws Exception {
    OggMuxer muxer =
        new OggMuxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel()).build();
    int trackId = muxer.addTrack(OPUS_FORMAT);
    muxer.close();
    ByteBuffer sampleData = ByteBuffer.allocate(0);
    BufferInfo bufferInfo =
        new BufferInfo(/* presentationTimeUs= */ 0, /* size= */ 0, /* flags= */ 0);

    assertThrows(
        IllegalStateException.class, () -> muxer.writeSampleData(trackId, sampleData, bufferInfo));
  }

  @Test
  public void addMetadataEntry_afterClose_throws() throws Exception {
    OggMuxer muxer =
        new OggMuxer.Builder(new FileOutputStream(temporaryFolder.newFile()).getChannel()).build();
    muxer.close();
    Mp4OrientationData orientationData = new Mp4OrientationData(/* orientation= */ 90);

    assertThrows(IllegalStateException.class, () -> muxer.addMetadataEntry(orientationData));
  }

  @Test
  public void close_afterClose_doesNotChangeOutput() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    OggMuxer muxer =
        new OggMuxer.Builder(new FileOutputStream(outputFilePath).getChannel()).build();
    int unusedTrackId = muxer.addTrack(OPUS_FORMAT);
    muxer.close();
    byte[] outputAfterFirstClose = TestUtil.getByteArrayFromFilePath(outputFilePath);

    muxer.close();

    assertThat(TestUtil.getByteArrayFromFilePath(outputFilePath)).isEqualTo(outputAfterFirstClose);
  }

  @Test
  public void close_whenOutputCloseFails_treatsMuxerAsClosed() throws Exception {
    OggMuxer muxer =
        new OggMuxer.Builder(
                new FailingCloseSeekableMuxerOutput(
                    SeekableMuxerOutput.of(temporaryFolder.newFile().getPath())))
            .build();

    MuxerException exception = assertThrows(MuxerException.class, muxer::close);

    assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(CLOSE_FAILURE_MESSAGE);
    muxer.close();
    assertThrows(IllegalStateException.class, () -> muxer.addTrack(OPUS_FORMAT));
  }
}
