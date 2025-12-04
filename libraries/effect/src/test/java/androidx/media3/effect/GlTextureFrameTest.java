/*
 * Copyright 2025 The Android Open Source Project
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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.GlTextureInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link GlTextureFrame}. */
@RunWith(AndroidJUnit4.class)
public class GlTextureFrameTest {

  private static final GlTextureInfo TEXTURE_INFO =
      new GlTextureInfo(
          /* texId= */ 1, /* fboId= */ 1, /* rboId= */ 1, /* width= */ 1, /* height= */ 1);

  @Test
  public void releaseWithoutRetain_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.release();

    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void releaseAfterRetain_doesNotReleaseFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.retain();
    frame.release();

    assertThat(isReleased.get()).isFalse();
  }

  @Test
  public void matchingReleaseAndRetainCalls_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.retain();
    frame.retain();
    frame.release();
    frame.release();
    frame.release();

    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void releaseWithoutRetain_throwsIllegalStateException() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();
    frame.release();

    assertThrows(IllegalStateException.class, frame::release);
  }

  @Test
  public void retainAfterRelease_throwsIllegalStateException() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();
    frame.release();

    assertThrows(IllegalStateException.class, frame::retain);
  }
}
