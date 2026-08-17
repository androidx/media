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
package androidx.media3.common.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlUtil}. */
@RunWith(AndroidJUnit4.class)
public final class GlUtilTest {

  @Test
  public void createSyncFences_withoutNativeFenceSupport_returnsEmptyList() throws Exception {
    // Returns an empty list in unit tests because:
    // - On SDK < 33, native sync fences are unsupported by the platform.
    // - On SDK >= 33, Robolectric's shadow EGL does not report the EGL_ANDROID_native_fence_sync
    //   extension.
    // The successful creation path on supported hardware is verified in GlUtilAndroidTest.
    ImmutableList<SyncFenceWrapper> syncFences = GlUtil.createSyncFences(/* count= */ 1);

    assertThat(syncFences).isEmpty();
  }

  @Test
  public void createSyncFences_countZero_throwsIllegalArgumentException() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> GlUtil.createSyncFences(/* count= */ 0));
  }
}
