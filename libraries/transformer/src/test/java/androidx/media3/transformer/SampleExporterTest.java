/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SampleExporter}. */
@RunWith(AndroidJUnit4.class)
public class SampleExporterTest {

  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(),
          /* encoders= */ ImmutableList.of(ShadowMediaCodecConfig.CODEC_INFO_AAC));

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void
      findSupportedMimeTypeForEncoderAndMuxer_withRawAudioAndNoEncodingNeeded_returnsRawAudio() {
    Format pcmFormat =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 1, /* sampleRate= */ 44100);
    String sampleMimeType =
        SampleExporter.findSupportedMimeTypeForEncoderAndMuxer(
            pcmFormat,
            new DefaultMuxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO),
            new DefaultEncoderFactory.Builder(context).build());
    assertThat(sampleMimeType).isEqualTo(MimeTypes.AUDIO_RAW);
  }

  @Test
  public void
      findSupportedMimeTypeForEncoderAndMuxer_withRawAudioAndSetBitrate_returnsEncodedAudio() {
    Format pcmFormat =
        Util.getPcmFormat(C.ENCODING_PCM_16BIT, /* channels= */ 1, /* sampleRate= */ 44100);
    String sampleMimeType =
        SampleExporter.findSupportedMimeTypeForEncoderAndMuxer(
            pcmFormat,
            new DefaultMuxer.Factory().getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO),
            new DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(
                    new AudioEncoderSettings.Builder().setBitrate(1000).build())
                .build());
    assertThat(sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
  }
}
