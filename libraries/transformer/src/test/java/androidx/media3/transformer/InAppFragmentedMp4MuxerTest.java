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
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit tests for {@link InAppFragmentedMp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public final class InAppFragmentedMp4MuxerTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void createAndClose_withParcelFileDescriptor_keepsDescriptorOpenUntilMuxerClosed()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppFragmentedMp4Muxer.Factory factory = new InAppFragmentedMp4Muxer.Factory();

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    try (Muxer muxer = factory.create(pfd)) {
      assertThat(muxer).isNotNull();
      assertThat(pfd.getFileDescriptor().valid()).isTrue();
    }
    assertThat(pfd.getFileDescriptor().valid()).isFalse();
  }

  @Test
  public void createAndClose_withPipe_keepsPipeOpenUntilMuxerClosed() throws Exception {
    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
    InAppFragmentedMp4Muxer.Factory factory = new InAppFragmentedMp4Muxer.Factory();
    try (ParcelFileDescriptor readPipe = pipe[0];
        Muxer muxer = factory.create(pipe[1])) {
      assertThat(muxer).isNotNull();
      assertThat(pipe[1].getFileDescriptor().valid()).isTrue();
    }
    assertThat(pipe[1].getFileDescriptor().valid()).isFalse();
  }

  @Test
  public void create_withClosedParcelFileDescriptor_throwsMuxerException() throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppFragmentedMp4Muxer.Factory factory = new InAppFragmentedMp4Muxer.Factory();
    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    pfd.close();

    assertThrows(MuxerException.class, () -> factory.create(pfd));
  }

  @Test
  public void create_withUnexpectedRuntimeException_throwsMuxerExceptionAndClosesPfd()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    InAppFragmentedMp4Muxer.Factory factory = new InAppFragmentedMp4Muxer.Factory();
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
}
