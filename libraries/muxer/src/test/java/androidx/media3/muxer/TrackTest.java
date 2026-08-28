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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4TimestampData;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link Track}. */
@RunWith(AndroidJUnit4.class)
public class TrackTest {

  @Test
  public void videoUnitTimebase_withAudioFormat_returnsSampleRate() {
    Format audioFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44100).build();
    Track track = new Track(/* trackId= */ 1, audioFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(44100);
  }

  @Test
  public void videoUnitTimebase_withMetadataTimescaleAndUnsetFrameRate_returnsMetadataTimescale() {
    Mp4TimestampData timestampData =
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 0,
            /* modificationTimestampSeconds= */ 0,
            /* timescale= */ 24_000);
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CAMERA_MOTION)
            .setMetadata(new Metadata(timestampData))
            .build();
    Track track = new Track(/* trackId= */ 1, format, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(24_000);
  }

  @Test
  public void videoUnitTimebase_withFrameRateAndMetadataTimescale_prefersFrameRateTimescale() {
    Mp4TimestampData timestampData =
        new Mp4TimestampData(
            /* creationTimestampSeconds= */ 0,
            /* modificationTimestampSeconds= */ 0,
            /* timescale= */ 1_000);
    Format videoFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setFrameRate(60.0f)
            .setMetadata(new Metadata(timestampData))
            .build();
    Track track = new Track(/* trackId= */ 1, videoFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(60_000);
  }

  @Test
  public void videoUnitTimebase_withVideoFormatAndFrameRate_returnsScaledFrameRate() {
    Format videoFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setFrameRate(60.0f).build();
    Track track = new Track(/* trackId= */ 1, videoFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(60_000);
  }

  @Test
  public void videoUnitTimebase_withVideoFormatUnsetFrameRate_returnsDefault90k() {
    Format videoFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Track track = new Track(/* trackId= */ 1, videoFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(90_000);
  }

  @Test
  public void videoUnitTimebase_withAudioFormatUnsetSampleRate_returnsDefault48k() {
    Format audioFormat = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    Track track = new Track(/* trackId= */ 1, audioFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(48_000);
  }

  @Test
  public void videoUnitTimebase_withVideoFormatHighFrameRate_returnsClampedTimescale() {
    Format videoFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setFrameRate(2000.0f).build();
    Track track = new Track(/* trackId= */ 1, videoFormat, /* sampleCopyEnabled= */ false);

    int videoUnitTimebase = track.videoUnitTimebase();

    assertThat(videoUnitTimebase).isEqualTo(1_000_000);
  }

  @Test
  public void videoUnitTimebase_withVideoFormatSmallFrameRate_returnsDefault90k() {
    Format videoFormatSmall =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setFrameRate(0.1f).build();
    Track trackSmall =
        new Track(/* trackId= */ 1, videoFormatSmall, /* sampleCopyEnabled= */ false);

    assertThat(trackSmall.videoUnitTimebase()).isEqualTo(90_000);
  }
}
