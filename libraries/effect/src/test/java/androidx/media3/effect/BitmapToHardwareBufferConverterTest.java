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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Robolectric tests for {@link BitmapToHardwareBufferConverter}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 26) // HardwareBuffers only exist on API26+.
public final class BitmapToHardwareBufferConverterTest {

  private ExecutorService executorService;
  private BitmapToHardwareBufferConverter converter;
  private HardwareBufferJniWrapper mockJniWrapper;

  @Before
  public void setUp() {
    executorService = Executors.newSingleThreadExecutor();
    mockJniWrapper = mock(HardwareBufferJniWrapper.class);
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(true);

    converter =
        new BitmapToHardwareBufferConverter(
            mockJniWrapper,
            /* internalExecutor= */ executorService,
            /* errorExecutor= */ directExecutor(),
            /* errorCallback= */ (e) -> {
              throw new AssertionError(e);
            });
  }

  @After
  public void tearDown() {
    if (converter != null) {
      converter.close();
    }
    if (executorService != null) {
      executorService.shutdown();
    }
  }

  @Test
  public void getOrCreateRetainedFrame_sameBitmap_reusesCachedFrame() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame frame1 = converter.getOrCreateRetainedFrame(bitmap);
    HardwareBufferFrame frame2 = converter.getOrCreateRetainedFrame(bitmap);

    assertThat(frame1.hardwareBuffer).isNotNull();
    assertThat(frame1.hardwareBuffer).isSameInstanceAs(frame2.hardwareBuffer);

    frame1.release(/* releaseFence= */ null);
    frame2.release(/* releaseFence= */ null);
  }

  @Test
  public void getOrCreateRetainedFrame_differentBitmap_createsNewFrame() {
    Bitmap bitmap1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    Bitmap bitmap2 = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame frame1 = converter.getOrCreateRetainedFrame(bitmap1);
    HardwareBufferFrame frame2 = converter.getOrCreateRetainedFrame(bitmap2);

    assertThat(frame1.hardwareBuffer).isNotNull();
    assertThat(frame2.hardwareBuffer).isNotNull();
    assertThat(frame1.hardwareBuffer).isNotSameInstanceAs(frame2.hardwareBuffer);

    frame1.release(/* releaseFence= */ null);
    frame2.release(/* releaseFence= */ null);
  }

  @Test
  public void flush_withoutErrors_releasesCachedFrame() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame frame1 = converter.getOrCreateRetainedFrame(bitmap);
    converter.flush();
    HardwareBufferFrame frame2 = converter.getOrCreateRetainedFrame(bitmap);

    assertThat(frame1.hardwareBuffer).isNotNull();
    assertThat(frame2.hardwareBuffer).isNotNull();
    assertThat(frame1.hardwareBuffer).isNotSameInstanceAs(frame2.hardwareBuffer);

    frame1.release(/* releaseFence= */ null);
    frame2.release(/* releaseFence= */ null);
  }

  @Test
  public void getOrCreateRetainedFrame_nativeCopyFails_throwsIllegalStateException() {
    when(mockJniWrapper.nativeCopyBitmapToHardwareBuffer(any(), any())).thenReturn(false);
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    assertThrows(IllegalStateException.class, () -> converter.getOrCreateRetainedFrame(bitmap));
  }
}
