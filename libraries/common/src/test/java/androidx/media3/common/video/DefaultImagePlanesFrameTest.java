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
package androidx.media3.common.video;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.media3.common.Format;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DefaultImagePlanesFrameTest {

  @Test
  public void buildUpon_buildWithoutModifications_returnsNewHandleWithSameProperties() {
    ImmutableList<ImagePlanesFrame.Plane> planes = createSamplePlanes();
    Format format = new Format.Builder().setWidth(128).setHeight(128).build();
    ImmutableMap<String, Object> metadata = ImmutableMap.of("key", "value");
    Object internalImage = new Object();
    DefaultImagePlanesFrame frame1 =
        new DefaultImagePlanesFrame.Builder(planes)
            .setFormat(format)
            .setMetadata(metadata)
            .setInternalImage(internalImage)
            .setContentTimeUs(1_000_000L)
            .build();

    DefaultImagePlanesFrame frame2 = frame1.buildUpon().build();

    assertThat(frame2).isNotSameInstanceAs(frame1);
    assertThat(frame2.getPlanes()).isEqualTo(planes);
    assertThat(frame2.getFormat()).isEqualTo(format);
    assertThat(frame2.getMetadata()).isEqualTo(metadata);
    assertThat(frame2.getContentTimeUs()).isEqualTo(1_000_000L);
    assertThat(frame2.getInternalImage()).isSameInstanceAs(internalImage);
  }

  @Test
  public void buildUpon_withModifiedMetadata_updatesMetadataAndPreservesOtherProperties() {
    ImmutableList<ImagePlanesFrame.Plane> planes = createSamplePlanes();
    Format format = new Format.Builder().setWidth(128).setHeight(128).build();
    ImmutableMap<String, Object> initialMetadata = ImmutableMap.of("initialKey", "initialValue");
    Object internalImage = new Object();
    DefaultImagePlanesFrame frame1 =
        new DefaultImagePlanesFrame.Builder(planes)
            .setFormat(format)
            .setMetadata(initialMetadata)
            .setInternalImage(internalImage)
            .setContentTimeUs(1_000_000L)
            .build();
    ImmutableMap<String, Object> newMetadata = ImmutableMap.of("newKey", "newValue");

    DefaultImagePlanesFrame frame2 = frame1.buildUpon().setMetadata(newMetadata).build();

    assertThat(frame2).isNotSameInstanceAs(frame1);
    assertThat(frame2.getMetadata()).isEqualTo(newMetadata);
    assertThat(frame2.getFormat()).isEqualTo(format);
    assertThat(frame2.getPlanes()).isEqualTo(planes);
    assertThat(frame2.getContentTimeUs()).isEqualTo(1_000_000L);
    assertThat(frame2.getInternalImage()).isSameInstanceAs(internalImage);
  }

  @Test
  public void buildUpon_withIncrementReferenceCountAndOneHandleReleased_doesNotInvokeCallback() {
    AtomicInteger callbackCount = new AtomicInteger();
    DefaultImagePlanesFrame frame1 =
        new DefaultImagePlanesFrame.Builder(
                createSamplePlanes(), directExecutor(), fence -> callbackCount.incrementAndGet())
            .build();
    DefaultImagePlanesFrame unusedFrame2 =
        frame1.buildUpon().shouldIncrementReferenceCount().build();

    frame1.release(/* releaseFence= */ null);

    assertThat(callbackCount.get()).isEqualTo(0);
  }

  @Test
  public void buildUpon_withIncrementReferenceCountAndAllHandlesReleased_invokesCallback() {
    AtomicInteger callbackCount = new AtomicInteger();
    DefaultImagePlanesFrame frame1 =
        new DefaultImagePlanesFrame.Builder(
                createSamplePlanes(), directExecutor(), fence -> callbackCount.incrementAndGet())
            .build();
    DefaultImagePlanesFrame frame2 = frame1.buildUpon().shouldIncrementReferenceCount().build();

    frame1.release(/* releaseFence= */ null);
    frame2.release(/* releaseFence= */ null);

    assertThat(callbackCount.get()).isEqualTo(1);
  }

  @Test
  public void shouldIncrementReferenceCount_onUnmanagedFrame_throwsIllegalStateException() {
    DefaultImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(createSamplePlanes()).build();
    DefaultImagePlanesFrame.Builder builder = frame.buildUpon();

    assertThrows(IllegalStateException.class, builder::shouldIncrementReferenceCount);
  }

  @Test
  public void buildUpon_afterRelease_throwsIllegalStateException() {
    DefaultImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(createSamplePlanes()).build();
    frame.release(/* releaseFence= */ null);

    assertThrows(IllegalStateException.class, frame::buildUpon);
  }

  @Test
  public void release_withoutLifecycleManagement_closesReleaseFence() {
    DefaultImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(createSamplePlanes()).build();
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);

    frame.release(mockReleaseFence);

    verify(mockReleaseFence).close();
  }

  @Test
  public void release_multipleTimes_secondReleaseNoOp() {
    AtomicInteger callbackCount = new AtomicInteger();
    DefaultImagePlanesFrame frame =
        new DefaultImagePlanesFrame.Builder(
                createSamplePlanes(), directExecutor(), fence -> callbackCount.incrementAndGet())
            .build();
    frame.release(/* releaseFence= */ null);

    frame.release(/* releaseFence= */ null);

    assertThat(callbackCount.get()).isEqualTo(1);
  }

  private static ImmutableList<ImagePlanesFrame.Plane> createSamplePlanes() {
    ByteBuffer yBuffer = ByteBuffer.allocateDirect(64 * 64);
    ByteBuffer uBuffer = ByteBuffer.allocateDirect(32 * 32);
    ByteBuffer vBuffer = ByteBuffer.allocateDirect(32 * 32);
    return ImmutableList.of(
        new DefaultPlane(yBuffer, /* rowStride= */ 64, /* pixelStride= */ 1),
        new DefaultPlane(uBuffer, /* rowStride= */ 32, /* pixelStride= */ 1),
        new DefaultPlane(vBuffer, /* rowStride= */ 32, /* pixelStride= */ 1));
  }
}
