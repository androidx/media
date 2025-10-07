/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.container.Mp4TimestampData;
import androidx.media3.extractor.metadata.id3.Id3Frame;
import androidx.media3.extractor.metadata.id3.PrivFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MetadataTest {

  @Test
  public void getFirstEntryOfType_exactTypeMatch() {
    Metadata metadata =
        new Metadata(
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    TextInformationFrame textInformationFrame =
        metadata.getFirstEntryOfType(TextInformationFrame.class);
    assertThat(textInformationFrame.id).isEqualTo("foo");
    assertThat(textInformationFrame).isEqualTo(metadata.get(1));
  }

  @Test
  public void getFirstEntryOfType_subtypeMatch() {
    Metadata metadata =
        new Metadata(
            new Mp4TimestampData(
                /* creationTimestampSeconds= */ 123, /* modificationTimestampSeconds= */ 456),
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")));

    assertThat(metadata.getFirstEntryOfType(Id3Frame.class)).isEqualTo(metadata.get(1));
  }

  @Test
  public void getFirstEntryOfType_noMatch() {
    Metadata metadata =
        new Metadata(
            new Mp4TimestampData(
                /* creationTimestampSeconds= */ 123, /* modificationTimestampSeconds= */ 456),
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)));

    assertThat(metadata.getFirstEntryOfType(TextInformationFrame.class)).isNull();
  }

  @Test
  public void getFirstMatchingEntry_matches() {
    Metadata metadata =
        new Metadata(
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    TextInformationFrame matchingTextInfoFrame =
        metadata.getFirstMatchingEntry(TextInformationFrame.class, tif -> tif.id.equals("bar"));
    assertThat(matchingTextInfoFrame).isEqualTo(metadata.get(2));
  }

  @Test
  public void getFirstMatchingEntry_noMatch() {
    Metadata metadata =
        new Metadata(
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    TextInformationFrame matchingTextInfoFrame =
        metadata.getFirstMatchingEntry(TextInformationFrame.class, tif -> tif.id.equals("baz"));
    assertThat(matchingTextInfoFrame).isNull();
  }

  @Test
  public void getEntriesOfType_matches() {
    Metadata metadata =
        new Metadata(
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    ImmutableList<TextInformationFrame> textInformationFrames =
        metadata.getEntriesOfType(TextInformationFrame.class);
    assertThat(textInformationFrames).containsExactly(metadata.get(1), metadata.get(2)).inOrder();
  }

  @Test
  public void getEntriesOfType_noMatches() {
    Metadata metadata =
        new Metadata(
            new PrivFrame("owner", /* privateData= */ TestUtil.buildTestData(5)),
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    assertThat(metadata.getEntriesOfType(Mp4TimestampData.class)).isEmpty();
  }

  @Test
  public void getMatchingEntries_matches() {
    Metadata metadata =
        new Metadata(
            new TextInformationFrame(
                /* id= */ "foo", /* description= */ null, /* values= */ ImmutableList.of("val1")),
            new VorbisComment("key1", "value"),
            new VorbisComment("key2", "value"),
            new TextInformationFrame(
                /* id= */ "bar", /* description= */ null, /* values= */ ImmutableList.of("val2")));

    ImmutableList<VorbisComment> matchingComments =
        metadata.getMatchingEntries(VorbisComment.class, comment -> comment.value.equals("value"));
    assertThat(matchingComments).containsExactly(metadata.get(1), metadata.get(2)).inOrder();
  }
}
