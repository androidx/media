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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import android.os.ParcelFileDescriptor;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
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
}
