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
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Robolectric tests for {@link BitmapToHardwareBufferProcessor}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 26) // HardwareBuffers only exist on API26+.
public final class BitmapToHardwareBufferProcessorTest {

  private ExecutorService executorService;
  private BitmapToHardwareBufferProcessor processor;
  private HardwareBufferJniWrapper mockJniWrapper;

  @Before
  public void setUp() {
    executorService = Executors.newSingleThreadExecutor();
    mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);

    processor =
        new BitmapToHardwareBufferProcessor(
            mockJniWrapper,
            /* internalExecutor= */ executorService,
            /* errorExecutor= */ directExecutor(),
            /* errorCallback= */ (e) -> {
              throw new AssertionError(e);
            });
  }

  @After
  public void tearDown() {
    if (processor != null) {
      processor.close();
    }
    if (executorService != null) {
      executorService.shutdown();
    }
  }

  @Test
  public void process_defersInputFrameReleaseUntilOutputFrameRelease() {
    CountDownLatch releasedLatch = new CountDownLatch(1);
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                /* releaseCallback= */ (fence) -> releasedLatch.countDown())
            .setInternalFrame(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    // The output frame was generated, but the input frame should NOT be released yet.
    assertThat(releasedLatch.getCount()).isEqualTo(1);

    // Release the downstream output frame should trigger releasing the inputFrame
    outputFrame.release(/* releaseFence= */ null);
    assertThat(releasedLatch.getCount()).isEqualTo(0);
  }
}
