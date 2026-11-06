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
package androidx.media3.transformer;

import static androidx.media3.transformer.TestUtil.FAKE_AUDIO_TRACK_FORMAT;
import static androidx.media3.transformer.TestUtil.FAKE_VIDEO_TRACK_FORMAT;
import static androidx.media3.transformer.TestUtil.createFakeVideoSample;
import static androidx.media3.transformer.TestUtil.extractAllSamplesFromMp4File;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import android.os.ParcelFileDescriptor;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.container.Mp4Box;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.container.Mp4TrackReferenceData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit tests for {@link InAppMp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public final class InAppMp4MuxerTest {

  private static final Format VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(64)
          .setHeight(64)
          .build();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void getSupportedSampleMimeTypes_forMetadata_returnsIt35() {
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory();

    assertThat(factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_METADATA))
        .containsExactly(MimeTypes.APPLICATION_ITUT_T35);
  }

  @Test
  public void addTrack_it35ThenVideo_linksIt35ToVideoWithRndr() throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();

    try (Muxer muxer = new InAppMp4Muxer.Factory().create(outputPath)) {
      int it35TrackId = muxer.addTrack(CodecSpecificDataUtil.buildHagcMetadataFormat());
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_TRACK_FORMAT);
      writeSample(muxer, it35TrackId, /* presentationTimeUs= */ 0);
      writeVideoSample(muxer, videoTrackId, /* presentationTimeUs= */ 0);
    }

    FakeExtractorOutput extractorOutput = extractAllSamplesFromMp4File(outputPath);
    assertThat(getSampleMimeTypes(extractorOutput))
        .containsExactly(MimeTypes.VIDEO_H264, MimeTypes.APPLICATION_ITUT_T35)
        .inOrder();
    Format videoFormat = extractorOutput.trackOutputs.get(0).lastFormat;
    Format it35Format = extractorOutput.trackOutputs.get(1).lastFormat;
    assertThat(it35Format.metadata.getEntriesOfType(Mp4TrackReferenceData.class))
        .containsExactly(
            new Mp4TrackReferenceData(
                Mp4Box.TYPE_rndr, ImmutableList.of(Integer.parseInt(videoFormat.id))));
  }

  @Test
  public void addTrack_audioVideoThenIt35_linksIt35ToVideoWithRndr() throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();

    try (Muxer muxer = new InAppMp4Muxer.Factory().create(outputPath)) {
      int audioTrackId = muxer.addTrack(FAKE_AUDIO_TRACK_FORMAT);
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_TRACK_FORMAT);
      writeSample(muxer, audioTrackId, /* presentationTimeUs= */ 0);
      writeVideoSample(muxer, videoTrackId, /* presentationTimeUs= */ 0);
      int it35TrackId = muxer.addTrack(CodecSpecificDataUtil.buildHagcMetadataFormat());
      writeSample(muxer, it35TrackId, /* presentationTimeUs= */ 0);
    }

    FakeExtractorOutput extractorOutput = extractAllSamplesFromMp4File(outputPath);
    assertThat(getSampleMimeTypes(extractorOutput))
        .containsExactly(MimeTypes.AUDIO_AAC, MimeTypes.VIDEO_H264, MimeTypes.APPLICATION_ITUT_T35)
        .inOrder();
    Format videoFormat = extractorOutput.trackOutputs.get(1).lastFormat;
    Format it35Format = extractorOutput.trackOutputs.get(2).lastFormat;
    assertThat(it35Format.metadata.getEntriesOfType(Mp4TrackReferenceData.class))
        .containsExactly(
            new Mp4TrackReferenceData(
                Mp4Box.TYPE_rndr, ImmutableList.of(Integer.parseInt(videoFormat.id))));
  }

  @Test
  public void writeSampleData_it35SampleAfterVideoDuration_dropsSample() throws Exception {
    String outputPath = temporaryFolder.newFile().getPath();
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory().setVideoDurationUs(1_000);

    try (Muxer muxer = factory.create(outputPath)) {
      int videoTrackId = muxer.addTrack(FAKE_VIDEO_TRACK_FORMAT);
      int it35TrackId = muxer.addTrack(CodecSpecificDataUtil.buildHagcMetadataFormat());
      writeVideoSample(muxer, videoTrackId, /* presentationTimeUs= */ 0);
      writeSample(muxer, it35TrackId, /* presentationTimeUs= */ 0);
      writeSample(muxer, it35TrackId, /* presentationTimeUs= */ 2_000);
    }

    FakeTrackOutput it35TrackOutput = extractAllSamplesFromMp4File(outputPath).trackOutputs.get(1);
    assertThat(it35TrackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_ITUT_T35);
    it35TrackOutput.assertSampleCount(1);
  }

  @Test
  public void createAndClose_withParcelFileDescriptor_keepsDescriptorOpenUntilMuxerClosed()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory();

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    try (Muxer muxer = factory.create(pfd)) {
      assertThat(muxer).isNotNull();
      assertThat(pfd.getFileDescriptor().valid()).isTrue();
    }
    assertThat(pfd.getFileDescriptor().valid()).isFalse();
  }

  @Test
  public void create_withNonSeekableParcelFileDescriptor_throwsMuxerExceptionAndClosesPfd()
      throws Exception {
    File fifoFile = new File(temporaryFolder.getRoot(), "test_fifo");
    Process mkfifo = new ProcessBuilder("mkfifo", fifoFile.getAbsolutePath()).start();
    if (mkfifo.waitFor() != 0) {
      return;
    }

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(fifoFile, ParcelFileDescriptor.MODE_READ_WRITE);
    ParcelFileDescriptor spyPfd = Mockito.spy(pfd);
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory();

    try {
      MuxerException exception = assertThrows(MuxerException.class, () -> factory.create(spyPfd));
      assertThat(exception).hasMessageThat().contains("not seekable");
      verify(spyPfd, Mockito.atLeastOnce()).close();
      assertThat(pfd.getFileDescriptor().valid()).isFalse();
    } finally {
      fifoFile.delete();
    }
  }

  @Test
  public void create_withClosedParcelFileDescriptor_throwsMuxerException() throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory();
    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    pfd.close();

    assertThrows(MuxerException.class, () -> factory.create(pfd));
  }

  @Test
  public void create_withUnexpectedRuntimeException_throwsMuxerExceptionAndClosesPfd()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppMp4Muxer.Factory factory = new InAppMp4Muxer.Factory();
    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    ParcelFileDescriptor spyPfd = Mockito.spy(pfd);
    Mockito.doThrow(new RuntimeException("Simulated unexpected failure"))
        .when(spyPfd)
        .getFileDescriptor();

    MuxerException exception = assertThrows(MuxerException.class, () -> factory.create(spyPfd));
    assertThat(exception).hasCauseThat().isInstanceOf(RuntimeException.class);
    verify(spyPfd).close();
  }

  @Test
  public void addTrack_afterClose_throws() throws Exception {
    InAppMp4Muxer muxer = new InAppMp4Muxer.Factory().create(temporaryFolder.newFile().getPath());
    muxer.close();

    assertThrows(IllegalStateException.class, () -> muxer.addTrack(VIDEO_FORMAT));
  }

  @Test
  public void writeSampleData_afterCloseWithSampleAfterVideoDuration_throws() throws Exception {
    InAppMp4Muxer muxer =
        new InAppMp4Muxer.Factory()
            .setVideoDurationUs(1_000)
            .create(temporaryFolder.newFile().getPath());
    int trackId = muxer.addTrack(VIDEO_FORMAT);
    muxer.close();
    ByteBuffer sampleData = ByteBuffer.allocate(0);
    BufferInfo bufferInfo =
        new BufferInfo(/* presentationTimeUs= */ 2_000, /* size= */ 0, /* flags= */ 0);

    assertThrows(
        IllegalStateException.class, () -> muxer.writeSampleData(trackId, sampleData, bufferInfo));
  }

  @Test
  public void addMetadataEntry_afterClose_throws() throws Exception {
    InAppMp4Muxer muxer = new InAppMp4Muxer.Factory().create(temporaryFolder.newFile().getPath());
    muxer.close();
    Mp4OrientationData orientationData = new Mp4OrientationData(/* orientation= */ 90);

    assertThrows(IllegalStateException.class, () -> muxer.addMetadataEntry(orientationData));
  }

  @Test
  public void close_afterClose_doesNotChangeOutput() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();
    InAppMp4Muxer muxer = new InAppMp4Muxer.Factory().create(outputFilePath);
    int unusedTrackId = muxer.addTrack(VIDEO_FORMAT);
    muxer.close();
    byte[] outputAfterFirstClose = TestUtil.getByteArrayFromFilePath(outputFilePath);

    muxer.close();

    assertThat(TestUtil.getByteArrayFromFilePath(outputFilePath)).isEqualTo(outputAfterFirstClose);
  }

  /** Writes a 1-byte key frame sample to the track. */
  private static void writeSample(Muxer muxer, int trackId, long presentationTimeUs)
      throws MuxerException {
    muxer.writeSampleData(
        trackId,
        ByteBuffer.allocateDirect(1),
        new BufferInfo(presentationTimeUs, /* size= */ 1, C.BUFFER_FLAG_KEY_FRAME));
  }

  /** Writes an H.264 key frame sample to the video track. */
  private static void writeVideoSample(Muxer muxer, int trackId, long presentationTimeUs)
      throws MuxerException {
    ByteBuffer sample = createFakeVideoSample();
    muxer.writeSampleData(
        trackId,
        sample,
        new BufferInfo(presentationTimeUs, sample.remaining(), C.BUFFER_FLAG_KEY_FRAME));
  }

  /** Returns the sample MIME types of the extracted tracks, in track order. */
  private static ImmutableList<String> getSampleMimeTypes(FakeExtractorOutput extractorOutput) {
    ImmutableList.Builder<String> sampleMimeTypes = new ImmutableList.Builder<>();
    for (int i = 0; i < extractorOutput.numberOfTracks; i++) {
      sampleMimeTypes.add(extractorOutput.trackOutputs.get(i).lastFormat.sampleMimeType);
    }
    return sampleMimeTypes.build();
  }
}
