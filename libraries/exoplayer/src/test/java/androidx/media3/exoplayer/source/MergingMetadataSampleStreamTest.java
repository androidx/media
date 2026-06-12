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
package androidx.media3.exoplayer.source;

import static androidx.media3.common.C.RESULT_BUFFER_READ;
import static androidx.media3.common.C.RESULT_NOTHING_READ;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;

/** Tests for {@link MergingMetadataSampleStream}. */
@RunWith(AndroidJUnit4.class)
public final class MergingMetadataSampleStreamTest {

  private static final Format VIDEO_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build();

  private static final byte[] metadataPayload = {0x01, 0x02, 0x03};

  private SampleStream videoStream;
  private SampleStream metadataStream;

  @Before
  public void setUp() {
    videoStream = mock(SampleStream.class);
    metadataStream = mock(SampleStream.class);
  }

  @Test
  public void readData_attachesMetadataAsSupplementalData() {
    configureMockMetadataStream(
        metadataStream, new MetadataSample(/* timeUs= */ 0, metadataPayload));
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 0);

    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result = stream.readData(formatHolder, buffer, /* readFlags= */ 0);

    assertThat(result).isEqualTo(RESULT_BUFFER_READ);
    buffer.flip();
    assertThat(buffer.supplementalData).isNotNull();
    byte[] supplemental = new byte[buffer.supplementalData.remaining()];
    buffer.supplementalData.get(supplemental);
    assertThat(supplemental).isEqualTo(metadataPayload);
  }

  @Test
  public void readData_sparseMetadata_appliesSameMetadataToMultipleFrames() {
    configureMockMetadataStream(
        metadataStream, new MetadataSample(/* timeUs= */ 0, metadataPayload));

    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();

    // First frame at PTS=0.
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 0);
    DecoderInputBuffer buffer1 =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result1 = stream.readData(formatHolder, buffer1, /* readFlags= */ 0);
    assertThat(result1).isEqualTo(RESULT_BUFFER_READ);

    // Second frame at PTS=33000 - same metadata should still apply.
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 33_000);
    DecoderInputBuffer buffer2 =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result2 = stream.readData(formatHolder, buffer2, /* readFlags= */ 0);
    assertThat(result2).isEqualTo(RESULT_BUFFER_READ);
    buffer2.flip();
    assertThat(buffer2.supplementalData).isNotNull();
    byte[] supplemental = new byte[buffer2.supplementalData.remaining()];
    buffer2.supplementalData.get(supplemental);
    assertThat(supplemental).isEqualTo(metadataPayload);
  }

  @Test
  public void readData_noMetadataAvailable_doesNotAttachSupplementalData() {
    when(metadataStream.readData(any(), any(), anyInt())).thenReturn(RESULT_NOTHING_READ);
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 0);

    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    int result = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(RESULT_BUFFER_READ);
    assertThat(buffer.supplementalData).isNull();
  }

  @Test
  public void reset_clearsMetadataCache() {
    configureMockMetadataStream(
        metadataStream, new MetadataSample(/* timeUs= */ 0, metadataPayload));
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 0);

    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    int result = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(result).isEqualTo(RESULT_BUFFER_READ);
    assertThat(buffer.supplementalData).isNotNull();

    // Reset and read again with no new metadata.
    stream.reset();
    when(metadataStream.readData(any(), any(), anyInt())).thenReturn(RESULT_NOTHING_READ);
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 100_000);
    DecoderInputBuffer buffer2 =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result2 = stream.readData(formatHolder, buffer2, /* readFlags= */ 0);
    assertThat(result2).isEqualTo(RESULT_BUFFER_READ);
    assertThat(buffer2.supplementalData).isNull();
  }

  @Test
  public void readData_decreasingMetadataPts_invalidatesMetadataTrack() {
    byte[] otherMetadataPayload = {0x04, 0x05};
    configureMockMetadataStream(
        metadataStream,
        new MetadataSample(/* timeUs= */ 100_000, metadataPayload),
        new MetadataSample(/* timeUs= */ 120_000, metadataPayload),
        new MetadataSample(/* timeUs= */ 50_000, otherMetadataPayload)); // Decreasing PTS

    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    // Read video frame at 100_000. Metadata at 100_000 should be attached.
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 100_000);
    int result1 = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    assertThat(result1).isEqualTo(RESULT_BUFFER_READ);
    buffer.flip();
    assertThat(buffer.supplementalData).isNotNull();
    byte[] supplemental1 = new byte[buffer.supplementalData.remaining()];
    buffer.supplementalData.get(supplemental1);
    assertThat(supplemental1).isEqualTo(metadataPayload);

    // Read video frame at 120_000. This will cause the stream to try and read the next metadata
    // sample. The next metadata sample has a PTS of 50_000, which is less than the current
    // buffered metadata (120_000). This should invalidate the metadata track.
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 120_000);
    DecoderInputBuffer buffer2 =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result2 = stream.readData(formatHolder, buffer2, /* readFlags= */ 0);
    assertThat(result2).isEqualTo(RESULT_BUFFER_READ);
    assertThat(buffer2.supplementalData).isNull(); // Supplemental data should be null.

    // Read video frame at 120_000. The metadata track is invalidated, so no supplemental data
    // should be attached.
    configureVideoStreamToReturnBuffer(videoStream, /* timeUs= */ 120_000);
    DecoderInputBuffer buffer3 =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    int result3 = stream.readData(formatHolder, buffer3, /* readFlags= */ 0);
    assertThat(result3).isEqualTo(RESULT_BUFFER_READ);
    assertThat(buffer3.supplementalData).isNull();
  }

  @Test
  public void isReady_delegatesToVideoStream() {
    when(videoStream.isReady()).thenReturn(true);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    assertThat(stream.isReady()).isTrue();
  }

  @Test
  public void getPrimaryStream_returnsVideoStream() {
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    assertThat(stream.getPrimaryStream()).isSameInstanceAs(videoStream);
  }

  @Test
  public void getMetadataStream_returnsMetadataStream() {
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    assertThat(stream.getMetadataStream()).isSameInstanceAs(metadataStream);
  }

  // -- Helpers --

  private static final class MetadataSample {
    final long timeUs;
    final byte[] data;

    MetadataSample(long timeUs, byte[] data) {
      this.timeUs = timeUs;
      this.data = data;
    }
  }

  private static void configureMockMetadataStream(SampleStream stream, MetadataSample... samples) {
    final int[] callCount = {0};
    when(stream.readData(any(), any(), anyInt()))
        .thenAnswer(
            (Answer<Integer>)
                invocation -> {
                  if (callCount[0] >= samples.length) {
                    return RESULT_NOTHING_READ;
                  }
                  DecoderInputBuffer buf = invocation.getArgument(1);
                  if (buf == null) {
                    return RESULT_BUFFER_READ;
                  }
                  MetadataSample sample = samples[callCount[0]++];
                  buf.timeUs = sample.timeUs;
                  buf.ensureSpaceForWrite(sample.data.length);
                  buf.data.put(sample.data);
                  return RESULT_BUFFER_READ;
                });
  }

  private static void configureVideoStreamToReturnBuffer(SampleStream stream, long timeUs) {
    when(stream.readData(any(), any(), anyInt()))
        .thenAnswer(
            (Answer<Integer>)
                invocation -> {
                  DecoderInputBuffer buf = invocation.getArgument(1);
                  if (buf != null) {
                    buf.timeUs = timeUs;
                  }
                  return RESULT_BUFFER_READ;
                });
  }
}
