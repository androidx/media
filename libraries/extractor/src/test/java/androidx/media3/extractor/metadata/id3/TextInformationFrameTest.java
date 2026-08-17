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
package androidx.media3.extractor.metadata.id3;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.MediaMetadata;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TextInformationFrame}. */
@RunWith(AndroidJUnit4.class)
public class TextInformationFrameTest {
  @Test
  public void populateMediaMetadata_title() {
    String title = "the title";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TT2", /* description= */ null, /* values= */ ImmutableList.of(title));

    assertThat(createMetadataFromFrame(frame).title.toString()).isEqualTo(title);
  }

  @Test
  public void populateMediaMetadata_artist() {
    String artist = "artist";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TP1", /* description= */ null, /* values= */ ImmutableList.of(artist));

    assertThat(createMetadataFromFrame(frame).artist.toString()).isEqualTo(artist);
  }

  @Test
  public void populateMediaMetadata_albumTitle() {
    String albumTitle = "album title";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TAL", /* description= */ null, /* values= */ ImmutableList.of(albumTitle));

    assertThat(createMetadataFromFrame(frame).albumTitle.toString()).isEqualTo(albumTitle);
  }

  @Test
  public void populateMediaMetadata_albumArtist() {
    String albumArtist = "album Artist";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TP2", /* description= */ null, /* values= */ ImmutableList.of(albumArtist));

    assertThat(createMetadataFromFrame(frame).albumArtist.toString()).isEqualTo(albumArtist);
  }

  @Test
  public void populateMediaMetadata_trackNumber() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TRK", /* description= */ null, /* values= */ ImmutableList.of("11"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.trackNumber).isEqualTo(11);
    assertThat(mediaMetadata.totalTrackCount).isNull();
  }

  @Test
  public void populateMediaMetadata_trackNumber_invalid() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TRK", /* description= */ null, /* values= */ ImmutableList.of("X"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.trackNumber).isNull();
    assertThat(mediaMetadata.totalTrackCount).isNull();
  }

  @Test
  public void populateMediaMetadata_trackNumberAndTotal() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TRK", /* description= */ null, /* values= */ ImmutableList.of("11/17"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.trackNumber).isEqualTo(11);
    assertThat(mediaMetadata.totalTrackCount).isEqualTo(17);
  }

  @Test
  public void populateMediaMetadata_trackNumberAndTotal_invalid() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TRK", /* description= */ null, /* values= */ ImmutableList.of("11/X"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.trackNumber).isNull();
    assertThat(mediaMetadata.totalTrackCount).isNull();
  }

  @Test
  public void populateMediaMetadata_recordingYear() {
    String recordingYear = "2000";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TYE",
            /* description= */ null,
            /* values= */ ImmutableList.of(recordingYear));

    assertThat(createMetadataFromFrame(frame).recordingYear).isEqualTo(2000);
  }

  @Test
  public void populateMediaMetadata_recordingYear_invalid() {
    String recordingYear = "200X";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TYE",
            /* description= */ null,
            /* values= */ ImmutableList.of(recordingYear));

    assertThat(createMetadataFromFrame(frame).recordingYear).isNull();
  }

  @Test
  public void populateMediaMetadata_recordingDayAndMonth() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TDA", /* description= */ null, /* values= */ ImmutableList.of("1007"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.recordingMonth).isEqualTo(7);
    assertThat(mediaMetadata.recordingDay).isEqualTo(10);
  }

  @Test
  public void populateMediaMetadata_releaseDate() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TDRL",
            /* description= */ null,
            /* values= */ ImmutableList.of("2001-01-02T00:00:00"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.releaseYear).isEqualTo(2001);
    assertThat(mediaMetadata.releaseMonth).isEqualTo(1);
    assertThat(mediaMetadata.releaseDay).isEqualTo(2);
  }

  @Test
  public void populateMediaMetadata_releaseDate_invalid() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TDRL",
            /* description= */ null,
            /* values= */ ImmutableList.of("2001-01-0XT00:00:00"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.releaseYear).isNull();
    assertThat(mediaMetadata.releaseMonth).isNull();
    assertThat(mediaMetadata.releaseDay).isNull();
  }

  @Test
  public void populateMediaMetadata_composer() {
    String composer = "composer";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TCM", /* description= */ null, /* values= */ ImmutableList.of(composer));

    assertThat(createMetadataFromFrame(frame).composer.toString()).isEqualTo(composer);
  }

  @Test
  public void populateMediaMetadata_conductor() {
    String conductor = "conductor";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TP3", /* description= */ null, /* values= */ ImmutableList.of(conductor));

    assertThat(createMetadataFromFrame(frame).conductor.toString()).isEqualTo(conductor);
  }

  @Test
  public void populateMediaMetadata_writer() {
    String writer = "writer";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TXT", /* description= */ null, /* values= */ ImmutableList.of(writer));

    assertThat(createMetadataFromFrame(frame).writer.toString()).isEqualTo(writer);
  }

  @Test
  public void populateMediaMetadata_discSubtitle() {
    String discSubtitle = "disc subtitle";
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TSST",
            /* description= */ null,
            /* values= */ ImmutableList.of(discSubtitle));

    assertThat(createMetadataFromFrame(frame).discSubtitle.toString()).isEqualTo(discSubtitle);
  }

  @Test
  public void populateMediaMetadata_discNumber() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TPOS", /* description= */ null, /* values= */ ImmutableList.of("2"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.discNumber).isEqualTo(2);
    assertThat(mediaMetadata.totalDiscCount).isNull();
  }

  @Test
  public void populateMediaMetadata_discNumber_invalid() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TPOS", /* description= */ null, /* values= */ ImmutableList.of("X"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.discNumber).isNull();
    assertThat(mediaMetadata.totalDiscCount).isNull();
  }

  @Test
  public void populateMediaMetadata_discNumberAndCount() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TPOS", /* description= */ null, /* values= */ ImmutableList.of("3/4"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.discNumber).isEqualTo(3);
    assertThat(mediaMetadata.totalDiscCount).isEqualTo(4);
  }

  @Test
  public void populateMediaMetadata_discNumberAndCount_invalid() {
    TextInformationFrame frame =
        new TextInformationFrame(
            /* id= */ "TPOS", /* description= */ null, /* values= */ ImmutableList.of("3/X"));

    MediaMetadata mediaMetadata = createMetadataFromFrame(frame);
    assertThat(mediaMetadata.discNumber).isNull();
    assertThat(mediaMetadata.totalDiscCount).isNull();
  }

  @Test
  public void emptyValuesListThrowsException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TextInformationFrame("TXXX", "description", ImmutableList.of()));
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated field
  public void deprecatedValueStillPopulated() {
    TextInformationFrame frame =
        new TextInformationFrame("TXXX", "description", ImmutableList.of("value"));

    assertThat(frame.value).isEqualTo("value");
    assertThat(frame.values).containsExactly("value");
  }

  @Test
  @SuppressWarnings({"deprecation", "InlineMeInliner"}) // Testing deprecated constructor
  public void deprecatedConstructorPopulatesValuesList() {
    TextInformationFrame frame = new TextInformationFrame("TXXX", "description", "value");

    assertThat(frame.value).isEqualTo("value");
    assertThat(frame.values).containsExactly("value");
  }

  @Test
  @SuppressWarnings({"deprecation", "InlineMeInliner"}) // Testing deprecated constructor
  public void deprecatedConstructorCreatesEqualInstance() {
    TextInformationFrame frame1 = new TextInformationFrame("TXXX", "description", "value");
    TextInformationFrame frame2 =
        new TextInformationFrame("TXXX", "description", ImmutableList.of("value"));

    assertThat(frame1).isEqualTo(frame2);
    assertThat(frame1.hashCode()).isEqualTo(frame2.hashCode());
  }

  private static MediaMetadata createMetadataFromFrame(TextInformationFrame frame) {
    MediaMetadata.Builder mediaMetadata = new MediaMetadata.Builder();
    frame.populateMediaMetadata(mediaMetadata);
    return mediaMetadata.build();
  }
}
