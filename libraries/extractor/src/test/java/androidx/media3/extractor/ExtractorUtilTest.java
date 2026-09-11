/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExtractorUtil}. */
@RunWith(AndroidJUnit4.class)
public class ExtractorUtilTest {

  private static final String TEST_URI = "http://www.google.com";
  private static final byte[] TEST_DATA = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8};

  @Test
  public void peekToLength_endNotReached() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource
        .getDataSet()
        .newDefaultData()
        .appendReadData(Arrays.copyOf(TEST_DATA, 3))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 3, 6))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 6, 9));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 2;
    int length = 4;

    int bytesPeeked = ExtractorUtil.peekToLength(input, target, offset, length);

    assertThat(bytesPeeked).isEqualTo(length);
    assertThat(input.getPeekPosition()).isEqualTo(length);
    assertThat(Arrays.copyOfRange(target, offset, offset + length))
        .isEqualTo(Arrays.copyOf(TEST_DATA, length));
  }

  @Test
  public void peekToLength_endReached() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource
        .getDataSet()
        .newDefaultData()
        .appendReadData(Arrays.copyOf(TEST_DATA, 3))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 3, 6))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 6, 9));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 0;
    int length = TEST_DATA.length + 1;

    int bytesPeeked = ExtractorUtil.peekToLength(input, target, offset, length);

    assertThat(bytesPeeked).isEqualTo(TEST_DATA.length);
    assertThat(input.getPeekPosition()).isEqualTo(TEST_DATA.length);
    assertThat(target).isEqualTo(TEST_DATA);
  }

  @Test
  public void readFullyQuietly_endNotReached_isTrueAndReadsData() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource
        .getDataSet()
        .newDefaultData()
        .appendReadData(Arrays.copyOf(TEST_DATA, 3))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 3, 6))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 6, 9));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 2;
    int length = 4;

    boolean hasRead = ExtractorUtil.readFullyQuietly(input, target, offset, length);

    assertThat(hasRead).isTrue();
    assertThat(input.getPosition()).isEqualTo(length);
    assertThat(Arrays.copyOfRange(target, offset, offset + length))
        .isEqualTo(Arrays.copyOf(TEST_DATA, length));
  }

  @Test
  public void readFullyQuietly_endReached_isFalse() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource.getDataSet().newDefaultData().appendReadData(Arrays.copyOf(TEST_DATA, 3));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 0;
    int length = TEST_DATA.length + 1;

    boolean hasRead = ExtractorUtil.readFullyQuietly(input, target, offset, length);

    assertThat(hasRead).isFalse();
    assertThat(input.getPosition()).isEqualTo(0);
  }

  @Test
  public void skipFullyQuietly_endNotReached_isTrueAndSkipsData() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource
        .getDataSet()
        .newDefaultData()
        .appendReadData(Arrays.copyOf(TEST_DATA, 3))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 3, 6))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 6, 9));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    int length = 4;

    boolean hasRead = ExtractorUtil.skipFullyQuietly(input, length);

    assertThat(hasRead).isTrue();
    assertThat(input.getPosition()).isEqualTo(length);
  }

  @Test
  public void skipFullyQuietly_endReached_isFalse() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource.getDataSet().newDefaultData().appendReadData(Arrays.copyOf(TEST_DATA, 3));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    int length = TEST_DATA.length + 1;

    boolean hasRead = ExtractorUtil.skipFullyQuietly(input, length);

    assertThat(hasRead).isFalse();
    assertThat(input.getPosition()).isEqualTo(0);
  }

  @Test
  public void peekFullyQuietly_endNotReached_isTrueAndPeeksData() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource
        .getDataSet()
        .newDefaultData()
        .appendReadData(Arrays.copyOf(TEST_DATA, 3))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 3, 6))
        .appendReadData(Arrays.copyOfRange(TEST_DATA, 6, 9));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 2;
    int length = 4;

    boolean hasRead =
        ExtractorUtil.peekFullyQuietly(input, target, offset, length, /* allowEndOfInput= */ false);

    assertThat(hasRead).isTrue();
    assertThat(input.getPeekPosition()).isEqualTo(length);
    assertThat(Arrays.copyOfRange(target, offset, offset + length))
        .isEqualTo(Arrays.copyOf(TEST_DATA, length));
  }

  @Test
  public void peekFullyQuietly_endReachedWithEndOfInputAllowed_isFalse() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource.getDataSet().newDefaultData().appendReadData(Arrays.copyOf(TEST_DATA, 3));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 0;
    int length = TEST_DATA.length + 1;

    boolean hasRead =
        ExtractorUtil.peekFullyQuietly(input, target, offset, length, /* allowEndOfInput= */ true);

    assertThat(hasRead).isFalse();
    assertThat(input.getPeekPosition()).isEqualTo(0);
  }

  @Test
  public void peekFullyQuietly_endReachedWithoutEndOfInputAllowed_throws() throws Exception {
    FakeDataSource testDataSource = new FakeDataSource();
    testDataSource.getDataSet().newDefaultData().appendReadData(Arrays.copyOf(TEST_DATA, 3));
    testDataSource.open(new DataSpec(Uri.parse(TEST_URI)));
    ExtractorInput input = new DefaultExtractorInput(testDataSource, 0, C.LENGTH_UNSET);
    byte[] target = new byte[TEST_DATA.length];
    int offset = 0;
    int length = TEST_DATA.length + 1;

    assertThrows(
        EOFException.class,
        () ->
            ExtractorUtil.peekFullyQuietly(
                input, target, offset, length, /* allowEndOfInput= */ false));
    assertThat(input.getPeekPosition()).isEqualTo(0);
  }

  @Test
  public void getFramesPerEncodedSample_aacLc_returnsSampleCount() {
    ByteBuffer buffer = ByteBuffer.allocate(0);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AAC_LC, buffer);

    assertThat(sampleCount).isEqualTo(1024);
  }

  @Test
  public void getFramesPerEncodedSample_aacHe_returnsSampleCount() {
    ByteBuffer buffer = ByteBuffer.allocate(0);

    int sampleCountV1 = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AAC_HE_V1, buffer);
    int sampleCountV2 = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AAC_HE_V2, buffer);

    assertThat(sampleCountV1).isEqualTo(2048);
    assertThat(sampleCountV2).isEqualTo(2048);
  }

  @Test
  public void getFramesPerEncodedSample_aacXhe_returnsSampleCount() {
    ByteBuffer buffer = ByteBuffer.allocate(0);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AAC_XHE, buffer);

    assertThat(sampleCount).isEqualTo(1024);
  }

  @Test
  public void getFramesPerEncodedSample_aacEld_returnsSampleCount() {
    ByteBuffer buffer = ByteBuffer.allocate(0);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AAC_ELD, buffer);

    assertThat(sampleCount).isEqualTo(512);
  }

  @Test
  public void getFramesPerEncodedSample_mp3_returnsSampleCount() {
    byte[] mp3FrameHeader = Util.getBytesFromHexString("FFFB9064");
    ByteBuffer buffer = ByteBuffer.wrap(mp3FrameHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_MP3, buffer);

    assertThat(sampleCount).isEqualTo(1152);
  }

  @Test
  public void getFramesPerEncodedSample_invalidMp3_throwsIllegalArgumentException() {
    ByteBuffer buffer = ByteBuffer.allocate(4);

    assertThrows(
        IllegalArgumentException.class,
        () -> ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_MP3, buffer));
  }

  @Test
  public void getFramesPerEncodedSample_trueHdSyncframe_returnsSampleCount() {
    byte[] trueHdSyncframeHeader = Util.getBytesFromHexString("C07504D8F8726FBA0097C00FB7520000");
    ByteBuffer buffer = ByteBuffer.wrap(trueHdSyncframeHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_DOLBY_TRUEHD, buffer);

    assertThat(sampleCount).isEqualTo(640);
  }

  @Test
  public void getFramesPerEncodedSample_trueHdNonSyncframe_returnsZero() {
    byte[] trueHdNonSyncframeHeader =
        Util.getBytesFromHexString("A025048860224E6F6DEDB6D5B6DBAFE6");
    ByteBuffer buffer = ByteBuffer.wrap(trueHdNonSyncframeHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_DOLBY_TRUEHD, buffer);

    assertThat(sampleCount).isEqualTo(0);
  }

  @Test
  public void getFramesPerEncodedSample_ac3_returnsSampleCount() {
    byte[] ac3SyncframeHeader = Util.getBytesFromHexString("0B7700000000");
    ByteBuffer buffer = ByteBuffer.wrap(ac3SyncframeHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AC3, buffer);

    assertThat(sampleCount).isEqualTo(1536);
  }

  @Test
  public void getFramesPerEncodedSample_ac4_returnsSampleCount() {
    byte[] ac4SyncframeHeader = Util.getBytesFromHexString("AC400020000500000000000000000000");
    ByteBuffer buffer = ByteBuffer.wrap(ac4SyncframeHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_AC4, buffer);

    assertThat(sampleCount).isEqualTo(1600);
  }

  @Test
  public void getFramesPerEncodedSample_dts_returnsSampleCount() {
    byte[] dtsFrameHeader = Util.getBytesFromHexString("7FFE8001003C");
    ByteBuffer buffer = ByteBuffer.wrap(dtsFrameHeader);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_DTS, buffer);

    assertThat(sampleCount).isEqualTo(512);
  }

  @Test
  public void getFramesPerEncodedSample_opus_returnsSampleCount() {
    byte[] oggOpusPacket =
        Util.getBytesFromHexString("4F6767530000E001000000000000000000000200000036E1841601EA04");
    ByteBuffer buffer = ByteBuffer.wrap(oggOpusPacket);

    int sampleCount = ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_OPUS, buffer);

    assertThat(sampleCount).isEqualTo(480);
  }

  @Test
  public void getFramesPerEncodedSample_pcmEncoding_throwsIllegalStateException() {
    ByteBuffer buffer = ByteBuffer.allocate(0);

    assertThrows(
        IllegalStateException.class,
        () -> ExtractorUtil.getFramesPerEncodedSample(C.ENCODING_PCM_16BIT, buffer));
  }
}
