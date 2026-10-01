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

import android.os.ParcelFileDescriptor;
import androidx.media3.muxer.Muxer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultMuxer}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultMuxerTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void createAndClose_withParcelFileDescriptor_keepsDescriptorOpenUntilMuxerClosed()
      throws Exception {
    File tempFile = temporaryFolder.newFile("test_output.mp4");
    DefaultMuxer.Factory factory = new DefaultMuxer.Factory();

    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
    try (Muxer muxer = factory.create(pfd)) {
      assertThat(muxer).isNotNull();
      assertThat(pfd.getFileDescriptor().valid()).isTrue();
    }
    assertThat(pfd.getFileDescriptor().valid()).isFalse();
  }
}
