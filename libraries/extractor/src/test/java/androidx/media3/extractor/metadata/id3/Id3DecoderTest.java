/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.test.utils.Id3TestUtil.buildFrames;
import static androidx.media3.test.utils.Id3TestUtil.buildSingleFrameTag;
import static androidx.media3.test.utils.Id3TestUtil.buildTag;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static androidx.media3.test.utils.TestUtil.createMetadataInputBuffer;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Metadata;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.metadata.MetadataInputBuffer;
import androidx.media3.test.utils.Id3TestUtil.FrameSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link Id3Decoder}. */
@RunWith(AndroidJUnit4.class)
public final class Id3DecoderTest {

  private static final int ID3_TEXT_ENCODING_UTF_8 = 3;

  @Test
  public void decodeTxxxFrame_utf8() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "TXXX",
            new byte[] {
              3, 0, 109, 100, 105, 97, 108, 111, 103, 95, 86, 73, 78, 68, 73, 67, 79, 49, 53, 50,
              55, 54, 54, 52, 95, 115, 116, 97, 114, 116, 0
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.id).isEqualTo("TXXX");
    assertThat(textInformationFrame.description).isEmpty();
    assertThat(textInformationFrame.values.get(0)).isEqualTo("mdialog_VINDICO1527664_start");
  }

  @Test
  public void decodeTxxxFrame_utf16() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "TXXX",
            new byte[] {
              1, 0, 72, 0, 101, 0, 108, 0, 108, 0, 111, 0, 32, 0, 87, 0, 111, 0, 114, 0, 108, 0,
              100, 0, 0
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.id).isEqualTo("TXXX");
    assertThat(textInformationFrame.description).isEqualTo("Hello World");
    assertThat(textInformationFrame.values).containsExactly("");
  }

  @Test
  public void decodeTxxxFrame_multipleValues() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "TXXX",
            new byte[] {
              1, 0, 72, 0, 101, 0, 108, 0, 108, 0, 111, 0, 32, 0, 87, 0, 111, 0, 114, 0, 108, 0,
              100, 0, 0, 0, 70, 0, 111, 0, 111, 0, 0, 0, 66, 0, 97, 0, 114, 0, 0
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.description).isEqualTo("Hello World");
    assertThat(textInformationFrame.values).containsExactly("Foo", "Bar").inOrder();
  }

  @Test
  public void decodeTxxxFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("TXXX", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(0);
  }

  @Test
  public void decodeTxxxFrame_encodingByteOnly() {
    byte[] rawId3 = buildSingleFrameTag("TXXX", new byte[] {ID3_TEXT_ENCODING_UTF_8});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.id).isEqualTo("TXXX");
    assertThat(textInformationFrame.description).isEmpty();
    assertThat(textInformationFrame.values).containsExactly("");
  }

  @Test
  public void decodeTextInformationFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "TIT2", new byte[] {3, 72, 101, 108, 108, 111, 32, 87, 111, 114, 108, 100, 0});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.id).isEqualTo("TIT2");
    assertThat(textInformationFrame.description).isNull();
    assertThat(textInformationFrame.values.size()).isEqualTo(1);
    assertThat(textInformationFrame.values.get(0)).isEqualTo("Hello World");
  }

  @Test
  public void decodeTextInformationFrame_multipleValues() {
    // Test multiple values.
    byte[] rawId3 = buildSingleFrameTag("TIT2", new byte[] {3, 70, 111, 111, 0, 66, 97, 114, 0});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.values).containsExactly("Foo", "Bar").inOrder();
  }

  @Test
  public void decodeTextInformationFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("TIT2", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(0);
  }

  @Test
  public void decodeTextInformationFrame_encodingByteOnly() {
    byte[] rawId3 = buildSingleFrameTag("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame textInformationFrame = (TextInformationFrame) metadata.get(0);
    assertThat(textInformationFrame.id).isEqualTo("TIT2");
    assertThat(textInformationFrame.description).isNull();
    assertThat(textInformationFrame.values).containsExactly("");
  }

  @Test
  public void decodeWxxxFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "WXXX",
            new byte[] {
              ID3_TEXT_ENCODING_UTF_8,
              116,
              101,
              115,
              116,
              0,
              104,
              116,
              116,
              112,
              115,
              58,
              47,
              47,
              116,
              101,
              115,
              116,
              46,
              99,
              111,
              109,
              47,
              97,
              98,
              99,
              63,
              100,
              101,
              102
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    UrlLinkFrame urlLinkFrame = (UrlLinkFrame) metadata.get(0);
    assertThat(urlLinkFrame.id).isEqualTo("WXXX");
    assertThat(urlLinkFrame.description).isEqualTo("test");
    assertThat(urlLinkFrame.url).isEqualTo("https://test.com/abc?def");
  }

  @Test
  public void decodeWxxxFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("WXXX", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(0);
  }

  @Test
  public void decodeWxxxFrame_encodingByteOnly() {
    byte[] rawId3 = buildSingleFrameTag("WXXX", new byte[] {ID3_TEXT_ENCODING_UTF_8});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    UrlLinkFrame urlLinkFrame = (UrlLinkFrame) metadata.get(0);
    assertThat(urlLinkFrame.id).isEqualTo("WXXX");
    assertThat(urlLinkFrame.description).isEmpty();
    assertThat(urlLinkFrame.url).isEmpty();
  }

  @Test
  public void decodeUrlLinkFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "WCOM",
            new byte[] {
              104, 116, 116, 112, 115, 58, 47, 47, 116, 101, 115, 116, 46, 99, 111, 109, 47, 97, 98,
              99, 63, 100, 101, 102
            });
    Id3Decoder decoder = new Id3Decoder();
    Metadata metadata = decoder.decode(rawId3, rawId3.length);
    assertThat(metadata.length()).isEqualTo(1);
    UrlLinkFrame urlLinkFrame = (UrlLinkFrame) metadata.get(0);
    assertThat(urlLinkFrame.id).isEqualTo("WCOM");
    assertThat(urlLinkFrame.description).isNull();
    assertThat(urlLinkFrame.url).isEqualTo("https://test.com/abc?def");
  }

  @Test
  public void decodeUrlLinkFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("WCOM", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    UrlLinkFrame urlLinkFrame = (UrlLinkFrame) metadata.get(0);
    assertThat(urlLinkFrame.id).isEqualTo("WCOM");
    assertThat(urlLinkFrame.description).isNull();
    assertThat(urlLinkFrame.url).isEmpty();
  }

  @Test
  public void decodePrivFrame() {
    byte[] rawId3 = buildSingleFrameTag("PRIV", new byte[] {116, 101, 115, 116, 0, 1, 2, 3, 4});
    Id3Decoder decoder = new Id3Decoder();
    Metadata metadata = decoder.decode(rawId3, rawId3.length);
    assertThat(metadata.length()).isEqualTo(1);
    PrivFrame privFrame = (PrivFrame) metadata.get(0);
    assertThat(privFrame.owner).isEqualTo("test");
    assertThat(privFrame.privateData).isEqualTo(new byte[] {1, 2, 3, 4});
  }

  @Test
  public void decodePrivFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("PRIV", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    PrivFrame privFrame = (PrivFrame) metadata.get(0);
    assertThat(privFrame.owner).isEmpty();
    assertThat(privFrame.privateData).isEqualTo(new byte[0]);
  }

  @Test
  public void decodeApicFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "APIC",
            new byte[] {
              3, 105, 109, 97, 103, 101, 47, 106, 112, 101, 103, 0, 16, 72, 101, 108, 108, 111, 32,
              87, 111, 114, 108, 100, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0
            });
    Id3Decoder decoder = new Id3Decoder();
    Metadata metadata = decoder.decode(rawId3, rawId3.length);
    assertThat(metadata.length()).isEqualTo(1);
    ApicFrame apicFrame = (ApicFrame) metadata.get(0);
    assertThat(apicFrame.mimeType).isEqualTo("image/jpeg");
    assertThat(apicFrame.pictureType).isEqualTo(16);
    assertThat(apicFrame.description).isEqualTo("Hello World");
    assertThat(apicFrame.pictureData).hasLength(10);
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
  }

  @Test
  public void decodeApicFrame_utf16DescriptionEvenOffset() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "APIC",
            new byte[] {
              1, 105, 109, 97, 103, 101, 47, 106, 112, 101, 103, 0, 16, 0, 72, 0, 101, 0, 108, 0,
              108, 0, 111, 0, 32, 0, 87, 0, 111, 0, 114, 0, 108, 0, 100, 0, 0, 1, 2, 3, 4, 5, 6, 7,
              8, 9, 0
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    ApicFrame apicFrame = (ApicFrame) metadata.get(0);
    assertThat(apicFrame.mimeType).isEqualTo("image/jpeg");
    assertThat(apicFrame.pictureType).isEqualTo(16);
    assertThat(apicFrame.description).isEqualTo("Hello World");
    assertThat(apicFrame.pictureData).hasLength(10);
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
  }

  @Test
  public void decodeApicFrame_utf16DescriptionOddOffset() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "APIC",
            new byte[] {
              1, 105, 109, 97, 103, 101, 47, 112, 110, 103, 0, 16, 0, 72, 0, 101, 0, 108, 0, 108, 0,
              111, 0, 32, 0, 87, 0, 111, 0, 114, 0, 108, 0, 100, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0
            });
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    ApicFrame apicFrame = (ApicFrame) metadata.get(0);
    assertThat(apicFrame.mimeType).isEqualTo("image/png");
    assertThat(apicFrame.pictureType).isEqualTo(16);
    assertThat(apicFrame.description).isEqualTo("Hello World");
    assertThat(apicFrame.pictureData).hasLength(10);
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
  }

  @Test
  public void decodeCommentFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "COMM",
            new byte[] {
              ID3_TEXT_ENCODING_UTF_8,
              101,
              110,
              103,
              100,
              101,
              115,
              99,
              114,
              105,
              112,
              116,
              105,
              111,
              110,
              0,
              116,
              101,
              120,
              116,
              0
            });
    Id3Decoder decoder = new Id3Decoder();
    Metadata metadata = decoder.decode(rawId3, rawId3.length);
    assertThat(metadata.length()).isEqualTo(1);
    CommentFrame commentFrame = (CommentFrame) metadata.get(0);
    assertThat(commentFrame.language).isEqualTo("eng");
    assertThat(commentFrame.description).isEqualTo("description");
    assertThat(commentFrame.text).isEqualTo("text");
  }

  @Test
  public void decodeCommentFrame_empty() {
    byte[] rawId3 = buildSingleFrameTag("COMM", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(0);
  }

  @Test
  public void decodeCommentFrame_languageOnly() {
    byte[] rawId3 =
        buildSingleFrameTag("COMM", new byte[] {ID3_TEXT_ENCODING_UTF_8, 101, 110, 103});
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    CommentFrame commentFrame = (CommentFrame) metadata.get(0);
    assertThat(commentFrame.language).isEqualTo("eng");
    assertThat(commentFrame.description).isEmpty();
    assertThat(commentFrame.text).isEmpty();
  }

  @Test
  public void decodeMultiFrames() {
    byte[] rawId3 =
        buildTag(
            new FrameSpec(
                "COMM",
                new byte[] {
                  3, 101, 110, 103, 100, 101, 115, 99, 114, 105, 112, 116, 105, 111, 110, 0, 116,
                  101, 120, 116, 0
                }),
            new FrameSpec(
                "APIC",
                new byte[] {
                  3, 105, 109, 97, 103, 101, 47, 106, 112, 101, 103, 0, 16, 72, 101, 108, 108, 111,
                  32, 87, 111, 114, 108, 100, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0
                }));
    Id3Decoder decoder = new Id3Decoder();
    Metadata metadata = decoder.decode(rawId3, rawId3.length);
    assertThat(metadata.length()).isEqualTo(2);
    CommentFrame commentFrame = (CommentFrame) metadata.get(0);
    ApicFrame apicFrame = (ApicFrame) metadata.get(1);

    assertThat(commentFrame.language).isEqualTo("eng");
    assertThat(commentFrame.description).isEqualTo("description");
    assertThat(commentFrame.text).isEqualTo("text");

    assertThat(apicFrame.mimeType).isEqualTo("image/jpeg");
    assertThat(apicFrame.pictureType).isEqualTo(16);
    assertThat(apicFrame.description).isEqualTo("Hello World");
    assertThat(apicFrame.pictureData).hasLength(10);
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 0});
  }

  @Test
  public void decodeGeobFrame_withObjectData_decodesFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "GEOB",
            Bytes.concat(
                new byte[] {ID3_TEXT_ENCODING_UTF_8},
                "application/octet-stream\0file.bin\0desc\0".getBytes(ISO_8859_1),
                new byte[] {10, 20, 30}));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    GeobFrame geobFrame = (GeobFrame) metadata.get(0);
    assertThat(geobFrame.mimeType).isEqualTo("application/octet-stream");
    assertThat(geobFrame.filename).isEqualTo("file.bin");
    assertThat(geobFrame.description).isEqualTo("desc");
    assertThat(geobFrame.data).isEqualTo(new byte[] {10, 20, 30});
  }

  @Test
  public void decodeGeobFrame_empty_skipsMalformedFrame() {
    byte[] rawId3 = buildSingleFrameTag("GEOB", new byte[0]);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(0);
  }

  @Test
  public void decodeApicFrame_truncatedBeforePictureType_skipsMalformedFrameAndDecodesNextFrame() {
    byte[] rawId3 =
        buildTag(
            new FrameSpec("APIC", new byte[0]),
            new FrameSpec(
                "APIC", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'i', 'm', 'a', 'g', 'e', '/', 'p'}),
            new FrameSpec(
                "APIC", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'i', 'm', 'a', 'g', 'e', '/', 'p', 0}),
            new FrameSpec("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'O', 'K', 0}));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame titleFrame = (TextInformationFrame) metadata.get(0);
    assertThat(titleFrame.id).isEqualTo("TIT2");
    assertThat(titleFrame.values).containsExactly("OK");
  }

  @Test
  public void decodeApicFrame_v2JpgWithPictureData_decodesFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            /* majorVersion= */ 2,
            "PIC",
            Bytes.concat(
                new byte[] {ID3_TEXT_ENCODING_UTF_8},
                "JPG".getBytes(ISO_8859_1),
                new byte[] {3},
                "cover\0".getBytes(ISO_8859_1),
                new byte[] {1, 2}));
    byte[] oversizedBuffer = Arrays.copyOf(rawId3, rawId3.length + 16);
    Arrays.fill(oversizedBuffer, rawId3.length, oversizedBuffer.length, (byte) 'Z');
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(oversizedBuffer, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    ApicFrame apicFrame = (ApicFrame) metadata.get(0);
    assertThat(apicFrame.mimeType).isEqualTo("image/jpeg");
    assertThat(apicFrame.pictureType).isEqualTo(3);
    assertThat(apicFrame.description).isEqualTo("cover");
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {1, 2});
  }

  @Test
  public void
      decodeApicFrame_v2TruncatedBeforePictureType_skipsMalformedFrameAndDecodesNextFrame() {
    byte[] rawId3 =
        buildTag(
            /* majorVersion= */ 2,
            // Frame 1: PIC with frameSize=3 (1 encoding byte + 2 format bytes, missing 3rd format
            // byte and pictureType).
            new FrameSpec(
                "PIC",
                Bytes.concat(new byte[] {ID3_TEXT_ENCODING_UTF_8}, "JP".getBytes(ISO_8859_1))),
            // Frame 2: PIC with frameSize=4 (1 encoding byte + 3 format bytes, missing
            // pictureType).
            new FrameSpec(
                "PIC",
                Bytes.concat(new byte[] {ID3_TEXT_ENCODING_UTF_8}, "JPG".getBytes(ISO_8859_1))),
            // Frame 3: TT2 ("OK").
            new FrameSpec(
                "TT2",
                Bytes.concat(new byte[] {ID3_TEXT_ENCODING_UTF_8}, "OK\0".getBytes(ISO_8859_1))));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    TextInformationFrame titleFrame = (TextInformationFrame) metadata.get(0);
    assertThat(titleFrame.id).isEqualTo("TT2");
    assertThat(titleFrame.values).containsExactly("OK");
  }

  @Test
  public void decodeBinaryFrame_followedBySecondFrame_copiesOnlyFrameSlice() {
    byte[] rawId3 =
        buildTag(
            new FrameSpec("XXXX", new byte[] {4, 5, 6}),
            new FrameSpec("WCOM", new byte[] {'h', 't', 't', 'p', ':', '/', '/', 'a'}),
            new FrameSpec("PRIV", new byte[] {'o', 'w', 'n', 'e', 'r', 0, 7, 8}));
    byte[] oversizedBuffer = Arrays.copyOf(rawId3, rawId3.length + 64);
    Arrays.fill(oversizedBuffer, rawId3.length, oversizedBuffer.length, (byte) 'Z');
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(oversizedBuffer, rawId3.length);

    assertThat(metadata.length()).isEqualTo(3);
    BinaryFrame binaryFrame = (BinaryFrame) metadata.get(0);
    UrlLinkFrame urlLinkFrame = (UrlLinkFrame) metadata.get(1);
    PrivFrame privFrame = (PrivFrame) metadata.get(2);
    assertThat(binaryFrame.id).isEqualTo("XXXX");
    assertThat(binaryFrame.data).isEqualTo(new byte[] {4, 5, 6});
    assertThat(urlLinkFrame.url).isEqualTo("http://a");
    assertThat(privFrame.owner).isEqualTo("owner");
    assertThat(privFrame.privateData).isEqualTo(new byte[] {7, 8});
  }

  @Test
  public void decode_multiFramesWithUnterminatedStringsAndBinaryPayloads_staysWithinFrameBounds() {
    byte[] rawId3 =
        buildTag(
            new FrameSpec("TXXX", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'k', 'e', 'y'}),
            new FrameSpec("TIT2", new byte[] {/* ID3_TEXT_ENCODING_UTF_16BE= */ 2, 0, 'H', 0, 'i'}),
            new FrameSpec(
                "WXXX",
                new byte[] {
                  ID3_TEXT_ENCODING_UTF_8, 'd', 0, 'h', 't', 't', 'p', ':', '/', '/', 'x'
                }),
            new FrameSpec(
                "COMM",
                new byte[] {
                  /* ID3_TEXT_ENCODING_UTF_16BE= */ 2, 'e', 'n', 'g', 0, 'd', 0, 0, 0, 'n', 0, 'o'
                }),
            new FrameSpec(
                "GEOB",
                new byte[] {ID3_TEXT_ENCODING_UTF_8, 't', '/', 'p', 0, 'f', 0, 'd', 0, 1, 2}),
            new FrameSpec(
                "APIC",
                Bytes.concat(
                    new byte[] {ID3_TEXT_ENCODING_UTF_8},
                    "image/png\0".getBytes(ISO_8859_1),
                    new byte[] {3},
                    "d\0".getBytes(ISO_8859_1),
                    new byte[] {4, 5})),
            new FrameSpec(
                "CTOC",
                Bytes.concat(
                    "toc\0".getBytes(ISO_8859_1),
                    new byte[] {/* flags= */ 0x03, /* childCount= */ 2},
                    "ch1".getBytes(ISO_8859_1))),
            new FrameSpec("TPE1", new byte[] {/* ID3_TEXT_ENCODING_UTF_16BE= */ 2, 0, 'A', 0}));
    byte[] oversizedBuffer = Arrays.copyOf(rawId3, rawId3.length + 16);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(oversizedBuffer, rawId3.length);

    assertThat(metadata.length()).isEqualTo(8);
    TextInformationFrame txxxFrame = (TextInformationFrame) metadata.get(0);
    TextInformationFrame tit2Frame = (TextInformationFrame) metadata.get(1);
    UrlLinkFrame wxxxFrame = (UrlLinkFrame) metadata.get(2);
    CommentFrame commentFrame = (CommentFrame) metadata.get(3);
    GeobFrame geobFrame = (GeobFrame) metadata.get(4);
    ApicFrame apicFrame = (ApicFrame) metadata.get(5);
    ChapterTocFrame ctocFrame = (ChapterTocFrame) metadata.get(6);
    TextInformationFrame tpe1Frame = (TextInformationFrame) metadata.get(7);
    assertThat(txxxFrame.description).isEqualTo("key");
    assertThat(txxxFrame.values).containsExactly("");
    assertThat(tit2Frame.values).containsExactly("Hi");
    assertThat(wxxxFrame.description).isEqualTo("d");
    assertThat(wxxxFrame.url).isEqualTo("http://x");
    assertThat(commentFrame.language).isEqualTo("eng");
    assertThat(commentFrame.description).isEqualTo("d");
    assertThat(commentFrame.text).isEqualTo("no");
    assertThat(geobFrame.data).isEqualTo(new byte[] {1, 2});
    assertThat(apicFrame.pictureData).isEqualTo(new byte[] {4, 5});
    assertThat(ctocFrame.elementId).isEqualTo("toc");
    assertThat(ctocFrame.children).asList().containsExactly("ch1", "").inOrder();
    // Trailing single zero byte inside TPE1 is an incomplete UTF-16 code unit (decoded as U+FFFD);
    // overrunning into the zero-filled buffer past rawId3.length would pair it into a 0x0000
    // terminator and yield "A" instead.
    assertThat(tpe1Frame.values).containsExactly("A\uFFFD");
  }

  @Test
  public void
      decode_chapterSubframesSmallerThanFlagBytes_skipsMalformedSubframesAndDecodesValidSubframe() {
    // Subframes inside CHAP bypass top-level validateFrames, so frame flags (such as
    // FRAME_FLAG_V4_HAS_GROUP_IDENTIFIER = 0x0040, FRAME_FLAG_V4_IS_UNSYNCHRONIZED = 0x0002, and
    // FRAME_FLAG_V4_HAS_DATA_LENGTH = 0x0001) with payloads smaller than the flag bytes reach
    // decodeFrame directly, including at the exact end of the tag.
    byte[] subFramesBytes =
        buildFrames(
            new FrameSpec("XXXX", new byte[0], /* flags= */ 0x0040),
            new FrameSpec("XXXX", new byte[] {1, 2}, /* flags= */ 0x0003),
            new FrameSpec("WCOM", new byte[] {1, 2}, /* flags= */ 0x0001),
            new FrameSpec("PRIV", new byte[] {1, 2, 3, 4}, /* flags= */ 0x0041),
            new FrameSpec("CTOC", new byte[] {1, 2}, /* flags= */ 0x0001),
            new FrameSpec("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'O', 'K', 0}),
            new FrameSpec("XXXX", new byte[] {1, 2}, /* flags= */ 0x0001));
    byte[] chapHeaderPayload =
        Bytes.concat(
            "chapter-id-01\0".getBytes(ISO_8859_1),
            new byte[] {0, 0, 0, 0}, // startTimeMs = 0
            new byte[] {0, 0, 3, (byte) 0xE8}, // endTimeMs = 1000
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, // startOffset = UNSET
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}); // endOffset = UNSET
    byte[] rawId3 = buildSingleFrameTag("CHAP", Bytes.concat(chapHeaderPayload, subFramesBytes));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    ChapterFrame chapterFrame = (ChapterFrame) metadata.get(0);
    assertThat(chapterFrame.chapterId).isEqualTo("chapter-id-01");
    assertThat(chapterFrame.getSubFrameCount()).isEqualTo(1);
    TextInformationFrame titleFrame = (TextInformationFrame) chapterFrame.getSubFrame(0);
    assertThat(titleFrame.id).isEqualTo("TIT2");
    assertThat(titleFrame.values).containsExactly("OK");
  }

  @Test
  public void decodeChapterTocFrame_unterminatedChildIdAtEndOfTag_decodesFrame() {
    byte[] rawId3 =
        buildSingleFrameTag(
            "CTOC",
            Bytes.concat(
                "toc\0".getBytes(ISO_8859_1),
                new byte[] {/* flags= */ 0x03, /* childCount= */ 2},
                "ch1".getBytes(ISO_8859_1)));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    ChapterTocFrame ctocFrame = (ChapterTocFrame) metadata.get(0);
    assertThat(ctocFrame.elementId).isEqualTo("toc");
    assertThat(ctocFrame.isRoot).isTrue();
    assertThat(ctocFrame.isOrdered).isTrue();
    assertThat(ctocFrame.children).asList().containsExactly("ch1", "").inOrder();
  }

  @Test
  public void
      decodeChapterTocFrame_malformedSubframeAndTrailingBytesFollowedByFrame_decodesValidSubframeWithinBounds() {
    byte[] ctocHeaderPayload =
        Bytes.concat(
            "toc-id\0".getBytes(ISO_8859_1),
            new byte[] {/* flags= */ 0x03, /* childCount= */ 2},
            "ch1\0ch2\0".getBytes(ISO_8859_1));
    byte[] ctocSubFramesAndTrailingBytes =
        Bytes.concat(
            buildFrames(
                new FrameSpec("XXXX", new byte[] {1, 2}, /* flags= */ 0x0001),
                new FrameSpec("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'T', 'O', 'C', 0})),
            new byte[] {0, 0});
    byte[] rawId3 =
        buildTag(
            new FrameSpec("CTOC", Bytes.concat(ctocHeaderPayload, ctocSubFramesAndTrailingBytes)),
            new FrameSpec(
                "TPE1", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'A', 'r', 't', 'i', 's', 't', 0}));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(2);
    ChapterTocFrame ctocFrame = (ChapterTocFrame) metadata.get(0);
    TextInformationFrame artistFrame = (TextInformationFrame) metadata.get(1);
    assertThat(ctocFrame.elementId).isEqualTo("toc-id");
    assertThat(ctocFrame.children).asList().containsExactly("ch1", "ch2").inOrder();
    assertThat(ctocFrame.getSubFrameCount()).isEqualTo(1);
    TextInformationFrame tocTitle = (TextInformationFrame) ctocFrame.getSubFrame(0);
    assertThat(tocTitle.values).containsExactly("TOC");
    assertThat(artistFrame.values).containsExactly("Artist");
  }

  @Test
  public void
      decodeChapterFrames_truncatedHeadersOrOversizedSubframeFollowedByFrame_staysWithinFrameBounds() {
    byte[] validChapHeader =
        Bytes.concat(
            "ch\0".getBytes(ISO_8859_1),
            new byte[] {0, 0, 0, 0},
            new byte[] {0, 0, 3, (byte) 0xE8},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    byte[] validCtocHeader =
        Bytes.concat(
            "toc\0".getBytes(ISO_8859_1),
            new byte[] {/* flags= */ 0x03, /* childCount= */ 1},
            "ch\0".getBytes(ISO_8859_1));
    byte[] oversizedSubFrame =
        Bytes.concat(
            "TIT2".getBytes(ISO_8859_1),
            new byte[] {0, 0, 0, 50, 0, 0, ID3_TEXT_ENCODING_UTF_8, 'X'});
    byte[] msbSetSubFrame =
        Bytes.concat(
            "TIT2".getBytes(ISO_8859_1),
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0});
    byte[] rawId3 =
        buildTag(
            new FrameSpec("CHAP", new byte[0]),
            new FrameSpec("CHAP", "unterminated".getBytes(ISO_8859_1)),
            new FrameSpec("CHAP", Bytes.concat("ch\0".getBytes(ISO_8859_1), new byte[15])),
            new FrameSpec("CTOC", new byte[0]),
            new FrameSpec("CTOC", "unterminated".getBytes(ISO_8859_1)),
            new FrameSpec("CTOC", Bytes.concat("toc\0".getBytes(ISO_8859_1), new byte[] {0x03})),
            new FrameSpec("CHAP", Bytes.concat(validChapHeader, oversizedSubFrame)),
            new FrameSpec("CHAP", Bytes.concat(validChapHeader, new byte[] {0, 0})),
            new FrameSpec("CTOC", Bytes.concat(validCtocHeader, msbSetSubFrame)),
            new FrameSpec("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'O', 'K', 0}));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(4);
    ChapterFrame chapWithOversizedSubFrame = (ChapterFrame) metadata.get(0);
    ChapterFrame chapWithTrailingBytes = (ChapterFrame) metadata.get(1);
    ChapterTocFrame ctocWithMsbSetSubFrame = (ChapterTocFrame) metadata.get(2);
    TextInformationFrame titleFrame = (TextInformationFrame) metadata.get(3);
    assertThat(chapWithOversizedSubFrame.getSubFrameCount()).isEqualTo(0);
    assertThat(chapWithTrailingBytes.getSubFrameCount()).isEqualTo(0);
    assertThat(ctocWithMsbSetSubFrame.getSubFrameCount()).isEqualTo(0);
    assertThat(titleFrame.values).containsExactly("OK");
  }

  @Test
  public void
      decodeChapterFrame_v3SubframesWithLargeUnsignedSizes_skipsMalformedSubframesWithoutDroppingChapters() {
    byte[] validSubFrame =
        buildFrames(
            /* majorVersion= */ 3,
            new FrameSpec("TIT2", new byte[] {ID3_TEXT_ENCODING_UTF_8, 'O', 'K', 0}));
    byte[] chap1Payload =
        Bytes.concat(
            "ch1\0".getBytes(ISO_8859_1),
            new byte[] {0, 0, 0, 0},
            new byte[] {0, 0, 3, (byte) 0xE8},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            validSubFrame,
            "TIT2".getBytes(ISO_8859_1),
            new byte[] {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0});
    byte[] chap2Payload =
        Bytes.concat(
            "ch2\0".getBytes(ISO_8859_1),
            new byte[] {0, 0, 3, (byte) 0xE8},
            new byte[] {0, 0, 7, (byte) 0xD0},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
            validSubFrame,
            "TIT2".getBytes(ISO_8859_1),
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0});
    byte[] rawId3 =
        buildTag(
            /* majorVersion= */ 3,
            new FrameSpec("CHAP", chap1Payload),
            new FrameSpec("CHAP", chap2Payload));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(2);
    ChapterFrame chapter1 = (ChapterFrame) metadata.get(0);
    ChapterFrame chapter2 = (ChapterFrame) metadata.get(1);
    assertThat(chapter1.chapterId).isEqualTo("ch1");
    assertThat(chapter1.getSubFrameCount()).isEqualTo(1);
    assertThat(((TextInformationFrame) chapter1.getSubFrame(0)).values).containsExactly("OK");
    assertThat(chapter2.chapterId).isEqualTo("ch2");
    assertThat(chapter2.getSubFrameCount()).isEqualTo(1);
    assertThat(((TextInformationFrame) chapter2.getSubFrame(0)).values).containsExactly("OK");
  }

  @Test
  public void decode_unsynchronizedV3Tag_removesUnsynchronizationSequences() {
    byte[] unsynchronizedPayload =
        Bytes.concat(
            "PRIV".getBytes(ISO_8859_1),
            new byte[] {0, 0, 0, 11, 0, 0},
            "id\0".getBytes(ISO_8859_1),
            new byte[] {
              (byte) 0xFF,
              0x00,
              (byte) 0xE0,
              (byte) 0xFF,
              0x00,
              0x00,
              (byte) 0xFF,
              0x00,
              (byte) 0xFF,
              0x00,
              0x00,
              (byte) 0xFF
            });
    byte[] rawId3 = buildTag(/* majorVersion= */ 3, /* flags= */ 0x80, unsynchronizedPayload);
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(1);
    PrivFrame privFrame = (PrivFrame) metadata.get(0);
    assertThat(privFrame.owner).isEqualTo("id");
    assertThat(privFrame.privateData)
        .isEqualTo(
            new byte[] {
              (byte) 0xFF,
              (byte) 0xE0,
              (byte) 0xFF,
              0x00,
              (byte) 0xFF,
              (byte) 0xFF,
              0x00,
              (byte) 0xFF
            });
  }

  @Test
  public void
      decode_unsynchronizedV3TagWithExtendedHeaderLargerThanTagSize_throwsIllegalArgumentException() {
    // Declared tag framesSize is 4 bytes, while the v3 extended header claims 6 payload bytes
    // (10 bytes including its 4-byte size field), yielding a negative remaining framesSize (-6)
    // passed to removeUnsynchronization.
    byte[] tagHeaderWithDeclaredSize4 =
        Arrays.copyOf(
            buildTag(/* majorVersion= */ 3, /* flags= */ 0xC0, new byte[4]),
            Id3Decoder.ID3_HEADER_LENGTH);
    byte[] extendedHeaderAndTrailingData =
        Bytes.concat(
            new byte[] {0, 0, 0, 6},
            new byte[6],
            buildFrames(
                new FrameSpec(
                    "TIT2",
                    Bytes.concat(new byte[] {ID3_TEXT_ENCODING_UTF_8}, "OK\0".getBytes(UTF_8)))));
    byte[] rawId3 = Bytes.concat(tagHeaderWithDeclaredSize4, extendedHeaderAndTrailingData);
    Id3Decoder decoder = new Id3Decoder();

    assertThrows(IllegalArgumentException.class, () -> decoder.decode(rawId3, rawId3.length));
  }

  @Test
  public void decode_unsynchronizedV4FrameFollowedByFrame_staysWithinFrameBounds() {
    byte[] rawId3 =
        buildTag(
            new FrameSpec(
                "XXXX",
                new byte[] {(byte) 0xFF, 0x00, (byte) 0xAB, (byte) 0xFF, 0x00, 0x00, (byte) 0xFF},
                /* flags= */ 0x0002),
            new FrameSpec("TIT2", new byte[] {0x00, 'A', 'B', 'C'}));
    Id3Decoder decoder = new Id3Decoder();

    Metadata metadata = decoder.decode(rawId3, rawId3.length);

    assertThat(metadata.length()).isEqualTo(2);
    BinaryFrame binaryFrame = (BinaryFrame) metadata.get(0);
    TextInformationFrame textFrame = (TextInformationFrame) metadata.get(1);
    assertThat(binaryFrame.data)
        .isEqualTo(new byte[] {(byte) 0xFF, (byte) 0xAB, (byte) 0xFF, 0x00, (byte) 0xFF});
    assertThat(textFrame.values).containsExactly("ABC");
  }

  @Test
  public void decodeFailsIfPositionNonZero() {
    Id3Decoder decoder = new Id3Decoder();
    MetadataInputBuffer buffer = createMetadataInputBuffer(createByteArray(1, 2, 3));
    buffer.data.position(1);

    assertThrows(IllegalArgumentException.class, () -> decoder.decode(buffer));
  }

  @Test
  public void decodeFailsIfBufferHasNoArray() {
    Id3Decoder decoder = new Id3Decoder();
    MetadataInputBuffer buffer = createMetadataInputBuffer(createByteArray(1, 2, 3));
    buffer.data = Util.createReadOnlyByteBuffer(buffer.data);

    assertThrows(IllegalArgumentException.class, () -> decoder.decode(buffer));
  }

  @Test
  public void decodeFailsIfArrayOffsetNonZero() {
    Id3Decoder decoder = new Id3Decoder();
    MetadataInputBuffer buffer = createMetadataInputBuffer(createByteArray(1, 2, 3));
    buffer.data.position(1);
    buffer.data = buffer.data.slice();

    assertThrows(IllegalArgumentException.class, () -> decoder.decode(buffer));
  }
}
