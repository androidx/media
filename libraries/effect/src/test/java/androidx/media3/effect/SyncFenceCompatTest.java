/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SyncFenceCompat}. */
@RunWith(AndroidJUnit4.class)
public final class SyncFenceCompatTest {

  @Test
  public void close_withMultipleFailures_aggregatesExceptions() throws Exception {
    IOException ex1 = new IOException("First failure");
    IOException ex2 = new IOException("Second failure");
    // We create placeholder descriptors to satisfy the copy constructor.
    ParcelFileDescriptor[] pipe1 = ParcelFileDescriptor.createPipe();
    ParcelFileDescriptor[] pipe2 = ParcelFileDescriptor.createPipe();
    try {
      ParcelFileDescriptor pfd1 =
          new ParcelFileDescriptor(pipe1[0]) {
            @Override
            public void close() throws IOException {
              throw ex1;
            }
          };
      ParcelFileDescriptor pfd2 =
          new ParcelFileDescriptor(pipe2[0]) {
            @Override
            public void close() throws IOException {
              throw ex2;
            }
          };

      // Combine them and test the aggregate close() logic.
      SyncFenceCompat fence =
          SyncFenceCompat.combine(
              Arrays.asList(
                  SyncFenceCompat.adoptParcelFileDescriptor(pfd1),
                  SyncFenceCompat.adoptParcelFileDescriptor(pfd2)));

      IOException thrown = assertThrows(IOException.class, fence::close);
      // Verify aggregation: first exception is thrown, second is suppressed.
      assertThat(thrown).isSameInstanceAs(ex1);
      assertThat(thrown.getSuppressed()).asList().containsExactly(ex2);

    } finally {
      // Manually clean up the descriptors we created for the test.
      pipe1[0].close();
      pipe1[1].close();
      pipe2[0].close();
      pipe2[1].close();
    }
  }

  @Test
  public void close_withNoFailures_closesAllDescriptors() throws Exception {
    ParcelFileDescriptor[] pipe1 = ParcelFileDescriptor.createPipe();
    ParcelFileDescriptor[] pipe2 = ParcelFileDescriptor.createPipe();
    SyncFenceCompat fence =
        SyncFenceCompat.combine(
            Arrays.asList(
                SyncFenceCompat.adoptParcelFileDescriptor(pipe1[0]),
                SyncFenceCompat.adoptParcelFileDescriptor(pipe2[0])));

    // Should close without throwing.
    fence.close();

    // Calling getFd() on a closed PFD throws IllegalStateException.
    assertThrows(IllegalStateException.class, pipe1[0]::getFd);
    assertThrows(IllegalStateException.class, pipe2[0]::getFd);

    // Cleanup other ends of pipes.
    pipe1[1].close();
    pipe2[1].close();
  }
}
