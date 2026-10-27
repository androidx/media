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
package androidx.media3.exoplayer.mediacodec;

import android.media.MediaCodec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link LoudnessCodecController}.
 *
 * <p>A decoder created on a background thread can be added after the renderer released the
 * controller: such calls must be ignored.
 */
@RunWith(AndroidJUnit4.class)
public class LoudnessCodecControllerTest {

  private MediaCodec codec;

  @Before
  public void setUp() throws Exception {
    codec = MediaCodec.createByCodecName("codec");
  }

  @After
  public void tearDown() {
    codec.release();
  }

  @Test
  public void addMediaCodec_afterRelease_isIgnored() {
    LoudnessCodecController controller = new LoudnessCodecController();
    controller.release();

    // Adding the same codec twice would fail if the first call registered it.
    controller.addMediaCodec(codec);
    controller.addMediaCodec(codec);
    controller.removeMediaCodec(codec);
    controller.release();
  }

  @Test
  public void addMediaCodec_afterReleaseWithAnAudioSession_doesNotUseTheClosedController() {
    LoudnessCodecController controller = new LoudnessCodecController();
    controller.setAudioSessionId(/* audioSessionId= */ 1);
    controller.release();

    // Robolectric's platform controller throws on a codec missing from the codec list, so any call
    // reaching the closed platform controller fails this test.
    controller.addMediaCodec(codec);
    controller.setAudioSessionId(/* audioSessionId= */ 2);
    controller.addMediaCodec(codec);
    controller.removeMediaCodec(codec);
    controller.release();
  }
}
