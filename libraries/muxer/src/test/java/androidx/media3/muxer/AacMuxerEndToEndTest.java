/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.muxer.MuxerTestUtil.FAKE_AUDIO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.FailingCloseSeekableMuxerOutput.CLOSE_FAILURE_MESSAGE;
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.util.Pair;
import androidx.media3.common.Format;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link AacMuxer}. */
@RunWith(AndroidJUnit4.class)
public class AacMuxerEndToEndTest {

  private static final Format AUDIO_FORMAT =
      FAKE_AUDIO_FORMAT.buildUpon().setSampleRate(48_000).build();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void writeSampleData_updatesSampleByteBufferPosition() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.aac").getPath();
    Pair<ByteBuffer, BufferInfo> audioSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);

    try (AacMuxer muxer = new AacMuxer(new FileOutputStream(outputPath))) {
      int trackId = muxer.addTrack(AUDIO_FORMAT);
      muxer.writeSampleData(trackId, audioSampleInfo.first, audioSampleInfo.second);
    }

    assertThat(audioSampleInfo.first.remaining()).isEqualTo(0);
  }

  @Test
  public void addTrack_afterClose_throws() throws Exception {
    AacMuxer muxer = new AacMuxer(new FileOutputStream(temporaryFolder.newFile()));
    muxer.close();

    assertThrows(IllegalStateException.class, () -> muxer.addTrack(AUDIO_FORMAT));
  }

  @Test
  public void writeSampleData_afterClose_throws() throws Exception {
    AacMuxer muxer = new AacMuxer(new FileOutputStream(temporaryFolder.newFile()));
    int trackId = muxer.addTrack(AUDIO_FORMAT);
    muxer.close();
    Pair<ByteBuffer, BufferInfo> audioSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);

    assertThrows(
        IllegalStateException.class,
        () -> muxer.writeSampleData(trackId, audioSampleInfo.first, audioSampleInfo.second));
  }

  @Test
  public void addMetadataEntry_afterClose_throws() throws Exception {
    AacMuxer muxer = new AacMuxer(new FileOutputStream(temporaryFolder.newFile()));
    muxer.close();
    Mp4OrientationData orientationData = new Mp4OrientationData(/* orientation= */ 90);

    assertThrows(IllegalStateException.class, () -> muxer.addMetadataEntry(orientationData));
  }

  @Test
  public void close_afterClose_doesNotChangeOutput() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.aac").getPath();
    Pair<ByteBuffer, BufferInfo> audioSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);
    AacMuxer muxer = new AacMuxer(new FileOutputStream(outputPath));
    int trackId = muxer.addTrack(AUDIO_FORMAT);
    muxer.writeSampleData(trackId, audioSampleInfo.first, audioSampleInfo.second);
    muxer.close();
    byte[] outputAfterFirstClose = TestUtil.getByteArrayFromFilePath(outputPath);

    muxer.close();

    assertThat(TestUtil.getByteArrayFromFilePath(outputPath)).isEqualTo(outputAfterFirstClose);
  }

  @Test
  public void close_whenOutputCloseFails_treatsMuxerAsClosed() throws Exception {
    FileOutputStream failingCloseOutputStream =
        new FileOutputStream(temporaryFolder.newFile()) {
          @Override
          public void close() throws IOException {
            super.close();
            throw new IOException(CLOSE_FAILURE_MESSAGE);
          }
        };
    AacMuxer muxer = new AacMuxer(failingCloseOutputStream);

    MuxerException exception = assertThrows(MuxerException.class, muxer::close);

    assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo(CLOSE_FAILURE_MESSAGE);
    muxer.close();
    assertThrows(IllegalStateException.class, () -> muxer.addTrack(AUDIO_FORMAT));
  }
}
