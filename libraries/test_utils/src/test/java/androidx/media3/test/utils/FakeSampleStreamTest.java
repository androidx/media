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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Format;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link FakeSampleStream}. */
@RunWith(AndroidJUnit4.class)
public final class FakeSampleStreamTest {

  @Test
  public void defaultFlags_isZero() {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 65536),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            new Format.Builder().build(),
            /* fakeSampleStreamItems= */ ImmutableList.of());

    assertThat(fakeSampleStream.getFlags()).isEqualTo(0);
  }

  @Test
  public void setFlags_matchesConfiguredFlags() {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 65536),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            new Format.Builder().build(),
            /* fakeSampleStreamItems= */ ImmutableList.of());

    fakeSampleStream.setFlags(SampleStream.FLAG_STRICT_DURATION);

    assertThat(fakeSampleStream.getFlags()).isEqualTo(SampleStream.FLAG_STRICT_DURATION);
  }
}
