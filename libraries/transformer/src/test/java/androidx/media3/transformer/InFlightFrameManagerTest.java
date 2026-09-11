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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.ReferenceCounter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link InFlightFrameManager}. */
@RunWith(AndroidJUnit4.class)
public final class InFlightFrameManagerTest {

  private interface ReferenceCountedFrame extends Frame, ReferenceCounter {}

  @Test
  public void trackIfSuccessful_whenQueueSucceeds_tracksFrames() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);

    boolean queued = manager.trackIfSuccessful(p -> true, createPacket(frame));

    assertThat(queued).isTrue();
    manager.releaseAll();
    verify(frame).release(null);
  }

  @Test
  public void trackIfSuccessful_whenQueueFails_removesFramesWithoutReleasing() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);

    boolean queued = manager.trackIfSuccessful(p -> false, createPacket(frame));

    assertThat(queued).isFalse();
    manager.releaseAll();
    verifyNoInteractions(frame);
  }

  @Test
  public void trackIfSuccessful_whenQueueThrows_removesFramesWithoutReleasing() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);
    ImmutableList<AsyncFrame> packet = createPacket(frame);

    assertThrows(
        RuntimeException.class,
        () ->
            manager.trackIfSuccessful(
                p -> {
                  throw new RuntimeException("Queue error");
                },
                packet));

    manager.releaseAll();
    verifyNoInteractions(frame);
  }

  @Test
  public void trackIfSuccessful_whenSubsequentQueueFails_preservesExistingTrackedFrames() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame1 = mock(ReferenceCountedFrame.class);
    ReferenceCountedFrame frame2 = mock(ReferenceCountedFrame.class);

    manager.trackIfSuccessful(p -> true, createPacket(frame1));
    manager.trackIfSuccessful(p -> false, createPacket(frame2));

    manager.releaseAll();
    verify(frame1).release(null);
    verifyNoInteractions(frame2);
  }

  @Test
  public void trackIfSuccessful_withEmptyPacket_succeeds() {
    InFlightFrameManager manager = new InFlightFrameManager();

    boolean queued = manager.trackIfSuccessful(p -> true, ImmutableList.of());

    assertThat(queued).isTrue();
    manager.releaseAll();
  }

  @Test
  public void onFrameProcessed_withRegisteredReferenceCounter_releasesFrame() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame));

    manager.onFrameProcessed(frame, /* releaseFence= */ null);

    verify(frame).release(null);
  }

  @Test
  public void onFrameProcessed_withReleaseFence_releasesFrame() {
    SyncFenceWrapper releaseFence = mock(SyncFenceWrapper.class);
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame));

    manager.onFrameProcessed(frame, releaseFence);

    verify(frame).release(releaseFence);
  }

  @Test
  public void onFrameProcessed_withUnmanagedFrame_removesWithoutError() {
    InFlightFrameManager manager = new InFlightFrameManager();
    Frame unmanagedFrame = mock(Frame.class);
    manager.trackIfSuccessful(p -> true, createPacket(unmanagedFrame));

    manager.onFrameProcessed(unmanagedFrame, /* releaseFence= */ null);

    verifyNoInteractions(unmanagedFrame);
  }

  @Test
  public void onFrameProcessed_withUnmanagedTrackedFrame_closesReleaseFence() {
    SyncFenceWrapper releaseFence = mock(SyncFenceWrapper.class);
    InFlightFrameManager manager = new InFlightFrameManager();
    Frame unmanagedFrame = mock(Frame.class);
    manager.trackIfSuccessful(p -> true, createPacket(unmanagedFrame));

    manager.onFrameProcessed(unmanagedFrame, releaseFence);

    verify(releaseFence).close();
  }

  @Test
  public void onFrameProcessed_withUnregisteredFrame_doesNotThrow() {
    InFlightFrameManager manager = new InFlightFrameManager();
    Frame frame = mock(Frame.class);

    manager.onFrameProcessed(frame, /* releaseFence= */ null);

    verifyNoInteractions(frame);
  }

  @Test
  public void onFrameProcessed_withUnregisteredFrame_closesReleaseFence() {
    SyncFenceWrapper releaseFence = mock(SyncFenceWrapper.class);
    InFlightFrameManager manager = new InFlightFrameManager();
    Frame frame = mock(Frame.class);

    manager.onFrameProcessed(frame, releaseFence);

    verify(releaseFence).close();
  }

  @Test
  public void onFrameProcessed_calledTwiceForSameFrame_releasesOnlyOnceAndClosesSecondFence() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);
    SyncFenceWrapper fence1 = mock(SyncFenceWrapper.class);
    SyncFenceWrapper fence2 = mock(SyncFenceWrapper.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame));

    manager.onFrameProcessed(frame, fence1);
    manager.onFrameProcessed(frame, fence2);

    verify(frame).release(fence1);
    verify(fence2).close();
    verifyNoMoreInteractions(frame);
  }

  @Test
  public void onFrameProcessed_withOneOfMultipleFrames_remainsInFlight() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame1 = mock(ReferenceCountedFrame.class);
    ReferenceCountedFrame frame2 = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame1, frame2));

    manager.onFrameProcessed(frame1, /* releaseFence= */ null);
    verify(frame1).release(null);

    manager.releaseAll();
    verify(frame2).release(null);
    verifyNoMoreInteractions(frame1);
  }

  @Test
  public void onFrameProcessed_withAllMultipleFrames_clearsInFlightFrames() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame1 = mock(ReferenceCountedFrame.class);
    ReferenceCountedFrame frame2 = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame1, frame2));

    manager.onFrameProcessed(frame1, /* releaseFence= */ null);
    manager.onFrameProcessed(frame2, /* releaseFence= */ null);

    verify(frame1).release(null);
    verify(frame2).release(null);

    manager.releaseAll();
    verifyNoMoreInteractions(frame1);
    verifyNoMoreInteractions(frame2);
  }

  @Test
  public void releaseAll_withInFlightFrames_releasesAllFrames() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame1 = mock(ReferenceCountedFrame.class);
    ReferenceCountedFrame frame2 = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame1, frame2));

    manager.releaseAll();

    verify(frame1).release(null);
    verify(frame2).release(null);
  }

  @Test
  public void releaseAll_calledMultipleTimes_releasesFramesOnlyOnce() {
    InFlightFrameManager manager = new InFlightFrameManager();
    ReferenceCountedFrame frame = mock(ReferenceCountedFrame.class);
    manager.trackIfSuccessful(p -> true, createPacket(frame));

    manager.releaseAll();
    manager.releaseAll();

    verify(frame).release(null);
    verifyNoMoreInteractions(frame);
  }

  private static ImmutableList<AsyncFrame> createPacket(Frame... frames) {
    ImmutableList.Builder<AsyncFrame> builder = ImmutableList.builder();
    for (Frame frame : frames) {
      builder.add(new AsyncFrame(frame, /* acquireFence= */ null));
    }
    return builder.build();
  }
}
