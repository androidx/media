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
import static androidx.media3.muxer.MuxerTestUtil.getFakeSampleAndSampleInfo;
import static com.google.common.truth.Truth.assertThat;

import android.util.Pair;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link AacMuxer}. */
@RunWith(AndroidJUnit4.class)
public class AacMuxerEndToEndTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void writeSampleData_updatesSampleByteBufferPosition() throws Exception {
    String outputPath = temporaryFolder.newFile("muxeroutput.aac").getPath();
    Pair<ByteBuffer, BufferInfo> audioSampleInfo =
        getFakeSampleAndSampleInfo(/* presentationTimeUs= */ 0L, /* isVideo= */ false);

    try (AacMuxer muxer = new AacMuxer(new FileOutputStream(outputPath))) {
      int trackId = muxer.addTrack(FAKE_AUDIO_FORMAT.buildUpon().setSampleRate(48000).build());
      muxer.writeSampleData(trackId, audioSampleInfo.first, audioSampleInfo.second);
    }

    assertThat(audioSampleInfo.first.remaining()).isEqualTo(0);
  }
}
