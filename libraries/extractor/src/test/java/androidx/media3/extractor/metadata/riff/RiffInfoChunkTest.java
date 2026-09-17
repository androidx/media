/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.extractor.metadata.riff;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.MediaMetadata;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link RiffInfoChunk}. */
@RunWith(AndroidJUnit4.class)
public class RiffInfoChunkTest {
  @Test
  public void populateMediaMetadata_title() {
    String title = "the title";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "INAM", /* values= */ ImmutableList.of(title));

    assertThat(createMetadataFromFrame(frame).title.toString()).isEqualTo(title);
  }

  @Test
  public void populateMediaMetadata_artist() {
    String artist = "artist";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "IART", /* values= */ ImmutableList.of(artist));

    assertThat(createMetadataFromFrame(frame).artist.toString()).isEqualTo(artist);
  }

  @Test
  public void populateMediaMetadata_albumTitle() {
    String albumTitle = "album title";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "IPRD", /* values= */ ImmutableList.of(albumTitle));

    assertThat(createMetadataFromFrame(frame).albumTitle.toString()).isEqualTo(albumTitle);
  }

  @Test
  public void populateMediaMetadata_trackNumberAndTotal() {
    RiffInfoChunk frame = new RiffInfoChunk(/* id= */ "ITRK", /* values= */ ImmutableList.of("11"));
    RiffInfoChunk frame2 =
        new RiffInfoChunk(/* id= */ "IFRM", /* values= */ ImmutableList.of("17"));

    assertThat(createMetadataFromFrame(frame).trackNumber).isEqualTo(11);
    assertThat(createMetadataFromFrame(frame2).totalTrackCount).isEqualTo(17);
  }

  @Test
  public void populateMediaMetadata_trackNumberAndTotal_invalid() {
    RiffInfoChunk frame = new RiffInfoChunk(/* id= */ "ITRK", /* values= */ ImmutableList.of("X"));
    RiffInfoChunk frame2 = new RiffInfoChunk(/* id= */ "IFRM", /* values= */ ImmutableList.of("X"));

    assertThat(createMetadataFromFrame(frame).trackNumber).isNull();
    assertThat(createMetadataFromFrame(frame2).totalTrackCount).isNull();
  }

  @Test
  public void populateMediaMetadata_releaseYear() {
    String releaseYear = "2000";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "IYER", /* values= */ ImmutableList.of(releaseYear));

    assertThat(createMetadataFromFrame(frame).releaseYear).isEqualTo(2000);
  }

  @Test
  public void populateMediaMetadata_releaseYear_invalid() {
    String releaseYear = "200X";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "IYER", /* values= */ ImmutableList.of(releaseYear));

    assertThat(createMetadataFromFrame(frame).releaseYear).isNull();
  }

  @Test
  public void populateMediaMetadata_recordingDate() {
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "ICRD", /* values= */ ImmutableList.of("2001-10-07"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.recordingYear).isEqualTo(2001);
    assertThat(mediaMetadata.recordingMonth).isEqualTo(10);
    assertThat(mediaMetadata.recordingDay).isEqualTo(7);
  }

  @Test
  public void populateMediaMetadata_recordingDate_invalid() {
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "ICRD", /* values= */ ImmutableList.of("2001-01"));

    assertThat(createMetadataFromFrame(frame).recordingYear).isNull();
    assertThat(createMetadataFromFrame(frame).recordingMonth).isNull();
    assertThat(createMetadataFromFrame(frame).recordingDay).isNull();
  }

  @Test
  public void populateMediaMetadata_composer() {
    String composer = "composer";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "ICOM", /* values= */ ImmutableList.of(composer));

    assertThat(createMetadataFromFrame(frame).composer.toString()).isEqualTo(composer);
  }

  @Test
  public void populateMediaMetadata_writer() {
    String writer = "writer";
    RiffInfoChunk frame =
        new RiffInfoChunk(/* id= */ "IWRI", /* values= */ ImmutableList.of(writer));

    assertThat(createMetadataFromFrame(frame).writer.toString()).isEqualTo(writer);
  }

  @Test
  public void emptyValuesListThrowsException() {
    assertThrows(
        IllegalArgumentException.class, () -> new RiffInfoChunk("IART", ImmutableList.of()));
  }

  private static MediaMetadata createMetadataFromFrame(RiffInfoChunk frame) {
    MediaMetadata.Builder mediaMetadata = new MediaMetadata.Builder();
    frame.populateMediaMetadata(mediaMetadata);
    return mediaMetadata.build();
  }
}
