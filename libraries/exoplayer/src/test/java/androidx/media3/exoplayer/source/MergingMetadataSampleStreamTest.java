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
import java.util.concurrent.atomic.AtomicInteger;
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
    configureVideoStreamToReturnBuffers(videoStream, /* timesUs...= */ 0);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();

    DecoderInputBuffer buffer = readBuffer(stream, formatHolder);

    assertThat(getSupplementalData(buffer)).isEqualTo(metadataPayload);
  }

  @Test
  public void readData_sparseMetadata_appliesSameMetadataToMultipleFrames() {
    configureMockMetadataStream(
        metadataStream, new MetadataSample(/* timeUs= */ 0, metadataPayload));
    configureVideoStreamToReturnBuffers(videoStream, /* timesUs...= */ 0, 33_000);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();

    // First frame at PTS=0.
    DecoderInputBuffer buffer1 = readBuffer(stream, formatHolder);
    // Second frame at PTS=33000 - same metadata should still apply.
    DecoderInputBuffer buffer2 = readBuffer(stream, formatHolder);

    assertThat(getSupplementalData(buffer1)).isEqualTo(metadataPayload);
    assertThat(getSupplementalData(buffer2)).isEqualTo(metadataPayload);
  }

  @Test
  public void
      readData_multipleMetadataSamplesWithZeroReorderSamples_doesNotEvictFutureMetadataEarly() {
    Format zeroReorderVideoFormat = VIDEO_FORMAT.buildUpon().setMaxNumReorderSamples(0).build();
    byte[] payload0 = {0x00};
    byte[] payload1 = {0x01};
    byte[] payload2 = {0x02};
    byte[] payload3 = {0x03};
    byte[] payload4 = {0x04};
    configureMockMetadataStream(
        metadataStream,
        new MetadataSample(/* timeUs= */ 0, payload0),
        new MetadataSample(/* timeUs= */ 1_000_000, payload1),
        new MetadataSample(/* timeUs= */ 2_000_000, payload2),
        new MetadataSample(/* timeUs= */ 3_000_000, payload3),
        new MetadataSample(/* timeUs= */ 4_000_000, payload4));
    configureVideoStreamToReturnBuffers(
        videoStream,
        /* timesUs...= */ 0,
        33_000,
        67_000,
        100_000,
        1_000_000,
        2_000_000,
        3_000_000,
        4_000_000);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, zeroReorderVideoFormat);
    FormatHolder formatHolder = new FormatHolder();

    DecoderInputBuffer frame000ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame033ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame067ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame100ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame1000ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame2000ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame3000ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frame4000ms = readBuffer(stream, formatHolder);

    assertThat(getSupplementalData(frame000ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frame033ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frame067ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frame100ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frame1000ms)).isEqualTo(payload1);
    assertThat(getSupplementalData(frame2000ms)).isEqualTo(payload2);
    assertThat(getSupplementalData(frame3000ms)).isEqualTo(payload3);
    assertThat(getSupplementalData(frame4000ms)).isEqualTo(payload4);
  }

  @Test
  public void readData_withBframeReordering_attachesCorrectMetadata() {
    Format reorderVideoFormat = VIDEO_FORMAT.buildUpon().setMaxNumReorderSamples(2).build();
    byte[] payload0 = {0x01};
    byte[] payload1 = {0x02};
    configureMockMetadataStream(
        metadataStream,
        new MetadataSample(/* timeUs= */ 0, payload0),
        new MetadataSample(/* timeUs= */ 100_000, payload1));
    // Decode order: I(0), P(133_000), B(66_000), B(33_000), B(100_000).
    configureVideoStreamToReturnBuffers(
        videoStream, /* timesUs...= */ 0, 133_000, 66_000, 33_000, 100_000);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, reorderVideoFormat);
    FormatHolder formatHolder = new FormatHolder();

    DecoderInputBuffer frameI0 = readBuffer(stream, formatHolder);
    DecoderInputBuffer frameP133ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frameB66ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frameB33ms = readBuffer(stream, formatHolder);
    DecoderInputBuffer frameB100ms = readBuffer(stream, formatHolder);

    assertThat(getSupplementalData(frameI0)).isEqualTo(payload0);
    assertThat(getSupplementalData(frameP133ms)).isEqualTo(payload1);
    assertThat(getSupplementalData(frameB66ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frameB33ms)).isEqualTo(payload0);
    assertThat(getSupplementalData(frameB100ms)).isEqualTo(payload1);
  }

  @Test
  public void readData_noMetadataAvailable_doesNotAttachSupplementalData() {
    when(metadataStream.readData(any(), any(), anyInt())).thenReturn(RESULT_NOTHING_READ);
    configureVideoStreamToReturnBuffers(videoStream, /* timesUs...= */ 0);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();

    DecoderInputBuffer buffer = readBuffer(stream, formatHolder);

    assertThat(buffer.supplementalData).isNull();
  }

  @Test
  public void reset_clearsMetadataCache() {
    configureMockMetadataStream(
        metadataStream, new MetadataSample(/* timeUs= */ 0, metadataPayload));
    configureVideoStreamToReturnBuffers(videoStream, /* timesUs...= */ 0, 100_000);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer = readBuffer(stream, formatHolder);
    assertThat(buffer.supplementalData).isNotNull();

    // Reset and read again with no new metadata.
    stream.reset();
    when(metadataStream.readData(any(), any(), anyInt())).thenReturn(RESULT_NOTHING_READ);
    DecoderInputBuffer buffer2 = readBuffer(stream, formatHolder);

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
    configureVideoStreamToReturnBuffers(videoStream, /* timesUs...= */ 100_000, 120_000, 120_000);
    MergingMetadataSampleStream stream =
        new MergingMetadataSampleStream(videoStream, metadataStream, VIDEO_FORMAT);
    FormatHolder formatHolder = new FormatHolder();

    // Read video frame at 100_000. Metadata at 100_000 should be attached.
    DecoderInputBuffer buffer = readBuffer(stream, formatHolder);
    // Read video frame at 120_000. This will cause the stream to try and read the next metadata
    // sample. The next metadata sample has a PTS of 50_000, which is less than the current
    // buffered metadata (120_000). This should invalidate the metadata track.
    DecoderInputBuffer buffer2 = readBuffer(stream, formatHolder);
    // Read video frame at 120_000. The metadata track is invalidated, so no supplemental data
    // should be attached.
    DecoderInputBuffer buffer3 = readBuffer(stream, formatHolder);

    assertThat(getSupplementalData(buffer)).isEqualTo(metadataPayload);
    assertThat(buffer2.supplementalData).isNull(); // Supplemental data should be null.
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
    AtomicInteger callCount = new AtomicInteger();
    when(stream.readData(any(), any(), anyInt()))
        .thenAnswer(
            (Answer<Integer>)
                invocation -> {
                  int index = callCount.getAndIncrement();
                  if (index >= samples.length) {
                    return RESULT_NOTHING_READ;
                  }
                  DecoderInputBuffer buf = invocation.getArgument(1);
                  if (buf == null) {
                    return RESULT_BUFFER_READ;
                  }
                  MetadataSample sample = samples[index];
                  buf.timeUs = sample.timeUs;
                  buf.ensureSpaceForWrite(sample.data.length);
                  buf.data.put(sample.data);
                  return RESULT_BUFFER_READ;
                });
  }

  private static void configureVideoStreamToReturnBuffers(SampleStream stream, long... timesUs) {
    AtomicInteger callCount = new AtomicInteger();
    when(stream.readData(any(), any(), anyInt()))
        .thenAnswer(
            (Answer<Integer>)
                invocation -> {
                  DecoderInputBuffer buf = invocation.getArgument(1);
                  if (buf != null && timesUs.length > 0) {
                    int index = Math.min(callCount.getAndIncrement(), timesUs.length - 1);
                    buf.timeUs = timesUs[index];
                  }
                  return RESULT_BUFFER_READ;
                });
  }

  private static DecoderInputBuffer readBuffer(
      MergingMetadataSampleStream stream, FormatHolder formatHolder) {
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    assertThat(stream.readData(formatHolder, buffer, /* readFlags= */ 0))
        .isEqualTo(RESULT_BUFFER_READ);
    return buffer;
  }

  private static byte[] getSupplementalData(DecoderInputBuffer buffer) {
    buffer.flip();
    assertThat(buffer.supplementalData).isNotNull();
    byte[] supplemental = new byte[buffer.supplementalData.remaining()];
    buffer.supplementalData.get(supplemental);
    return supplemental;
  }
}
