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
package androidx.media3.muxer;

import static com.google.common.truth.Truth.assertThat;

import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.ByteBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link SeekableMuxerOutput}. */
@RunWith(AndroidJUnit4.class)
public final class SeekableMuxerOutputTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void ofAndClose_withParcelFileDescriptor_keepsDescriptorOpenUntilOutputClosed()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    try (SeekableMuxerOutput output = SeekableMuxerOutput.of(pfd)) {
      assertThat(output).isNotNull();
      assertThat(pfd.getFileDescriptor().valid()).isTrue();
    }
    assertThat(pfd.getFileDescriptor().valid()).isFalse();
  }

  @Test
  public void of_withParcelFileDescriptor_supportsSeekableOperations() throws Exception {
    File tempFile = temporaryFolder.newFile("test_operations.mp4");

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    try (SeekableMuxerOutput output = SeekableMuxerOutput.of(pfd)) {
      byte[] data = new byte[] {1, 2, 3, 4, 5};
      output.write(ByteBuffer.wrap(data));
      assertThat(output.getPosition()).isEqualTo(5);
      assertThat(output.getSize()).isEqualTo(5);

      output.setPosition(2);
      assertThat(output.getPosition()).isEqualTo(2);

      output.write(ByteBuffer.wrap(new byte[] {9, 9}));
      assertThat(output.getPosition()).isEqualTo(4);

      output.truncate(3);
      assertThat(output.getSize()).isEqualTo(3);
    }

    assertThat(pfd.getFileDescriptor().valid()).isFalse();
    assertThat(tempFile.length()).isEqualTo(3);
  }
}
