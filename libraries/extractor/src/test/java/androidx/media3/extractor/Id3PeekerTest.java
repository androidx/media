/*
 * Copyright (C) 2018 The Android Open Source Project
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

package androidx.media3.extractor;

import static androidx.media3.test.utils.TestUtil.buildTestData;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static androidx.media3.test.utils.TestUtil.getByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.metadata.id3.ApicFrame;
import androidx.media3.extractor.metadata.id3.CommentFrame;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link Id3Peeker}. */
@RunWith(AndroidJUnit4.class)
public final class Id3PeekerTest {

  public static final ApicFrame EXPECTED_APIC_FRAME =
      new ApicFrame(
          MimeTypes.IMAGE_JPEG,
          "Hello World",
          /* pictureType= */ 16,
          /* pictureData= */ new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0});

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekId3Data_returnNull_ifId3TagNotPresentAtBeginningOfInput() throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(new byte[] {1, 'I', 'D', '3', 2, 3, 4, 5, 6, 7, 8, 9, 10})
            .build();

    @Nullable Metadata metadata = id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null);

    assertThat(metadata).isNull();
  }

  @Test
  public void peekId3Data_id3TagPrefixedByGarbageByte_oneSearchByteAllowed_returnsTag()
      throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                Bytes.concat(
                    createByteArray(1),
                    getByteArray(
                        ApplicationProvider.getApplicationContext(), "media/id3/apic.id3")))
            .build();

    @Nullable
    Metadata metadata =
        id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null, /* maxTagPeekBytes= */ 1);

    assertThat(metadata.length()).isEqualTo(1);
    assertApicFramesEqual((ApicFrame) metadata.get(0), EXPECTED_APIC_FRAME);
  }

  @Test
  public void peekId3Data_id3TagPrefixedBy15GarbageBytes_15ByteSearchAllowed_returnsTag()
      throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                Bytes.concat(
                    buildTestData(/* length= */ 15),
                    getByteArray(
                        ApplicationProvider.getApplicationContext(), "media/id3/apic.id3")))
            .build();

    @Nullable
    Metadata metadata =
        id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null, /* maxTagPeekBytes= */ 15);

    assertThat(metadata.length()).isEqualTo(1);
    assertApicFramesEqual((ApicFrame) metadata.get(0), EXPECTED_APIC_FRAME);
  }

  @Test
  public void peekId3Data_id3TagPrefixedBy15GarbageBytes_14ByteSearchAllowed_returnsNull()
      throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                Bytes.concat(
                    buildTestData(/* length= */ 15),
                    getByteArray(
                        ApplicationProvider.getApplicationContext(), "media/id3/apic.id3")))
            .build();

    @Nullable
    Metadata metadata =
        id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null, /* maxTagPeekBytes= */ 14);

    assertThat(metadata).isNull();
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekId3Data_returnId3Tag_ifId3TagPresent() throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                getByteArray(ApplicationProvider.getApplicationContext(), "media/id3/apic.id3"))
            .build();

    @Nullable Metadata metadata = id3Peeker.peekId3Data(input, /* id3FramePredicate= */ null);

    assertThat(metadata.length()).isEqualTo(1);
    assertApicFramesEqual((ApicFrame) metadata.get(0), EXPECTED_APIC_FRAME);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void peekId3Data_returnId3TagAccordingToGivenPredicate_ifId3TagPresent()
      throws IOException {
    Id3Peeker id3Peeker = new Id3Peeker();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                getByteArray(
                    ApplicationProvider.getApplicationContext(), "media/id3/comm_apic.id3"))
            .build();

    @Nullable
    Metadata metadata =
        id3Peeker.peekId3Data(
            input,
            (majorVersion, id0, id1, id2, id3) ->
                id0 == 'C' && id1 == 'O' && id2 == 'M' && id3 == 'M');
    assertThat(metadata).isNotNull();
    assertThat(metadata.length()).isEqualTo(1);

    CommentFrame commentFrame = (CommentFrame) metadata.get(0);
    assertThat(commentFrame.language).isEqualTo("eng");
    assertThat(commentFrame.description).isEqualTo("description");
    assertThat(commentFrame.text).isEqualTo("text");
  }

  private static void assertApicFramesEqual(ApicFrame actual, ApicFrame expected) {
    assertThat(actual.mimeType).isEqualTo(expected.mimeType);
    assertThat(actual.description).isEqualTo(expected.description);
    assertThat(actual.pictureType).isEqualTo(expected.pictureType);
    assertThat(actual.pictureData).isEqualTo(expected.pictureData);
  }
}
