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

import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link TransmuxTranscodeHelper}. */
@RunWith(AndroidJUnit4.class)
public final class TransmuxTranscodeHelperTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void copyFile_notCancelled_copiesAllBytes() throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ 200_000);
    File source = createFile("source", data);
    File destination = temporaryFolder.newFile("destination");

    TransmuxTranscodeHelper.copyFile(source, destination, /* isCancelled= */ () -> false);

    assertThat(TestUtil.getByteArrayFromFilePath(destination.getPath())).isEqualTo(data);
  }

  @Test
  public void copyFile_cancelledAfterFirstChunk_stopsCopying() throws Exception {
    // Larger than a single copy chunk.
    byte[] data = TestUtil.buildTestData(/* length= */ 200_000);
    File source = createFile("source", data);
    File destination = temporaryFolder.newFile("destination");
    AtomicInteger cancellationCheckCount = new AtomicInteger();

    TransmuxTranscodeHelper.copyFile(
        source, destination, /* isCancelled= */ () -> cancellationCheckCount.incrementAndGet() > 1);

    assertThat(destination.length()).isGreaterThan(0);
    assertThat(destination.length()).isLessThan(data.length);
  }

  private File createFile(String fileName, byte[] data) throws IOException {
    File file = temporaryFolder.newFile(fileName);
    try (OutputStream outputStream = new FileOutputStream(file)) {
      outputStream.write(data);
    }
    return file;
  }
}
