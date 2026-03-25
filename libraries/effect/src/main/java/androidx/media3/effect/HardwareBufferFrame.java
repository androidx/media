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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link Frame} implementation backed by a {@link HardwareBuffer}.
 *
 * <p>Frames of this type may be mappable to memory accessible by various hardware systems, such as
 * GPU, media codecs, NPU, or other auxiliary processing units.
 *
 * <p>On API levels before 26, where {@link HardwareBuffer} is not defined, {@link #internalFrame}
 * will be set for use within this package. {@link #internalFrame} is not intended for use by third
 * party apps.
 */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class HardwareBufferFrame implements Frame {
  private static final String TAG = "HardwareBufferFrame";

  /** A callback to be invoked when the {@link HardwareBufferFrame} is released. */
  public interface ReleaseCallback {
    /**
     * Releases the underlying resources of the {@link HardwareBufferFrame}.
     *
     * @param releaseFence A {@link SyncFenceWrapper} that must signal before the underlying
     *     resources can be fully released, or {@code null} if the resources can be released
     *     immediately.
     */
    void release(@Nullable SyncFenceWrapper releaseFence);
  }

  public static final HardwareBufferFrame END_OF_STREAM_FRAME =
      new HardwareBufferFrame.Builder(
              /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
          .setInternalFrame(new Object())
          .build();

  /**
   * Returns the {@link HardwareBuffer} which holds the frame data, or {@code null} if hardware
   * buffers are not supported on the current API level.
   */
  @Nullable public final HardwareBuffer hardwareBuffer;

  /** The presentation time of the frame, in microseconds. */
  public final long presentationTimeUs;

  /** The release time of the frame, in nanoseconds. */
  public final long releaseTimeNs;

  /** The format of the frame. */
  public final Format format;

  private final Metadata metadata;

  /**
   * An acquire {@link SyncFenceWrapper} for the {@linkplain #hardwareBuffer HardwareBuffer}.
   *
   * <p>Callers should ensure that the acquire fence has signaled before accessing {@linkplain
   * #hardwareBuffer HardwareBuffer}.
   *
   * <p>If the acquire fence is {@code null}, it's safe to access {@linkplain #hardwareBuffer
   * HardwareBuffer}.
   */
  @Nullable public final SyncFenceWrapper acquireFence;

  /** An optional internal frame type that is used when {@link #hardwareBuffer} is not supported. */
  @Nullable public final Object internalFrame;

  /** The shared state object managing reference counts and release orchestration. */
  private final SharedState sharedState;

  @GuardedBy("this")
  private boolean isReleased;

  /** A builder for {@link HardwareBufferFrame} instances. */
  public static final class Builder {
    @Nullable private final HardwareBuffer hardwareBuffer;
    private final Executor releaseExecutor;
    private final ReleaseCallback releaseCallback;

    private long presentationTimeUs;
    private Format format;
    private long releaseTimeNs;
    private Metadata metadata;
    @Nullable private SyncFenceWrapper acquireFence;
    @Nullable private Object internalFrame;
    @Nullable private SharedState sharedState;

    /**
     * Creates a new {@link Builder}.
     *
     * @param hardwareBuffer The {@link HardwareBuffer} supplier, or {@code null} if a hardware
     *     buffer cannot be constructed on the current API level.
     * @param releaseExecutor The {@link Executor} on which the {@code releaseCallback} is called.
     * @param releaseCallback The {@link Consumer} to call to release the texture.
     */
    public Builder(
        @Nullable HardwareBuffer hardwareBuffer,
        Executor releaseExecutor,
        ReleaseCallback releaseCallback) {
      this.hardwareBuffer = hardwareBuffer;
      this.releaseExecutor = releaseExecutor;
      this.releaseCallback = releaseCallback;
      this.metadata = new Metadata() {};
      presentationTimeUs = C.TIME_UNSET;
      format = new Format.Builder().build();
      releaseTimeNs = C.TIME_UNSET;
    }

    private Builder(HardwareBufferFrame frame) {
      this(frame.hardwareBuffer, frame.sharedState.executor, frame.sharedState.callback);
      this.metadata = frame.metadata;
      this.presentationTimeUs = frame.presentationTimeUs;
      this.format = frame.format;
      this.releaseTimeNs = frame.releaseTimeNs;
      this.acquireFence = frame.acquireFence;
      this.internalFrame = frame.internalFrame;
      this.sharedState = frame.sharedState;
    }

    /** Sets the {@link HardwareBufferFrame#presentationTimeUs}. */
    @CanIgnoreReturnValue
    public Builder setPresentationTimeUs(long presentationTimeUs) {
      this.presentationTimeUs = presentationTimeUs;
      return this;
    }

    /** Sets the {@link HardwareBufferFrame#format}. */
    @CanIgnoreReturnValue
    public Builder setFormat(Format format) {
      this.format = format;
      return this;
    }

    /** Sets the {@link HardwareBufferFrame#releaseTimeNs}. */
    @CanIgnoreReturnValue
    public Builder setReleaseTimeNs(long releaseTimeNs) {
      this.releaseTimeNs = releaseTimeNs;
      return this;
    }

    /** Sets the {@link HardwareBufferFrame#metadata}. */
    @CanIgnoreReturnValue
    public Builder setMetadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    /**
     * Sets the {@link HardwareBufferFrame#acquireFence}.
     *
     * <p>The default value is {@code null}.
     */
    @CanIgnoreReturnValue
    public Builder setAcquireFence(@Nullable SyncFenceWrapper acquireFence) {
      this.acquireFence = acquireFence;
      return this;
    }

    /**
     * Sets the {@link HardwareBufferFrame#internalFrame}.
     *
     * <p>The default value is {@code null}.
     *
     * @param internalFrame The internal frame.
     */
    @CanIgnoreReturnValue
    public Builder setInternalFrame(@Nullable Object internalFrame) {
      this.internalFrame = internalFrame;
      return this;
    }

    /** Builds the {@link HardwareBufferFrame} instance. */
    public HardwareBufferFrame build() {
      return new HardwareBufferFrame(this);
    }
  }

  private HardwareBufferFrame(Builder builder) {
    checkArgument(builder.hardwareBuffer != null || builder.internalFrame != null);
    this.hardwareBuffer = builder.hardwareBuffer;
    this.presentationTimeUs = builder.presentationTimeUs;
    this.releaseTimeNs = builder.releaseTimeNs;
    this.format = builder.format;
    this.metadata = builder.metadata;
    this.acquireFence = builder.acquireFence;
    this.internalFrame = builder.internalFrame;
    if (builder.sharedState != null) {
      this.sharedState = builder.sharedState;
    } else {
      this.sharedState =
          new SharedState(builder.releaseCallback, builder.releaseExecutor, builder.acquireFence);
    }
    isReleased = false;
  }

  /** Private constructor for creating retained handles sharing the same state. */
  private HardwareBufferFrame(HardwareBufferFrame original, SharedState sharedState) {
    this.hardwareBuffer = original.hardwareBuffer;
    this.presentationTimeUs = original.presentationTimeUs;
    this.releaseTimeNs = original.releaseTimeNs;
    this.format = original.format;
    this.metadata = original.metadata;
    this.acquireFence = original.acquireFence;
    this.internalFrame = original.internalFrame;
    this.sharedState = sharedState;
    isReleased = false;
  }

  /**
   * Returns a {@link Builder} initialized with the values of this instance. This method does not
   * increase the reference count.
   *
   * <p>The returned builder creates handles that share the same underlying reference count as this
   * instance. To create a new reference that increments the reference count, call {@link #retain()}
   * instead.
   */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public Metadata getMetadata() {
    return metadata;
  }

  @Override
  public void release(@Nullable SyncFenceWrapper releaseFence) {
    synchronized (this) {
      if (isReleased) {
        closeFenceSilently(releaseFence);
        return;
      }
      isReleased = true;
    }

    sharedState.release(releaseFence);
  }

  /**
   * Creates a new reference to this frame.
   *
   * <p>For every call to this method there must be an extra {@link #release} call. The underlying
   * buffer will only be released when the reference count drops to zero.
   *
   * @throws IllegalStateException if called after the frame has been released.
   */
  public synchronized HardwareBufferFrame retain() {
    if (isReleased) {
      throw new IllegalStateException("Cannot retain a frame that has already been released.");
    }
    sharedState.retain();
    return new HardwareBufferFrame(this, sharedState);
  }

  private static void closeFenceSilently(@Nullable SyncFenceWrapper fence) {
    if (fence == null) {
      return;
    }
    fence.close();
  }

  /**
   * Internal Shared State.
   *
   * <p>This class manages the synchronization and reference counting between multiple handles to +
   * the same underlying frame.
   */
  private static final class SharedState {

    private final ReleaseCallback callback;
    private final Executor executor;
    @Nullable private final SyncFenceWrapper acquireFence;

    @GuardedBy("this")
    private int refCount = 1;

    @GuardedBy("this")
    private final List<SyncFenceWrapper> releaseFences = new ArrayList<>();

    private SharedState(
        ReleaseCallback callback, Executor executor, @Nullable SyncFenceWrapper acquireFence) {
      this.callback = callback;
      this.executor = executor;
      this.acquireFence = acquireFence;
    }

    private synchronized void retain() {
      if (refCount <= 0) {
        throw new IllegalStateException("retain() called on a released HardwareBufferFrame.");
      }
      refCount++;
    }

    private void release(@Nullable SyncFenceWrapper releaseFence) {
      List<SyncFenceWrapper> fencesToWaitOn;

      synchronized (this) {
        if (releaseFence != null) {
          releaseFences.add(releaseFence);
        }
        refCount--;
        if (refCount != 0) {
          // The buffer is still retained by other references, do nothing further.
          return;
        }

        // Collect all fences for the final release signal.
        // The acquire fence should have signaled before the release fences, but we combine it
        // here anyway to be safe.
        fencesToWaitOn = new ArrayList<>(releaseFences);
        if (acquireFence != null) {
          fencesToWaitOn.add(acquireFence);
        }
        releaseFences.clear();
      }

      // TODO: b/479415385 - Consider forwarding a List<SyncFenceWrapper> to callback.release().
      executor.execute(
          () -> {
            for (SyncFenceWrapper fence : fencesToWaitOn) {
              fence.awaitForever();
              fence.close();
            }
            callback.release(/* releaseFence= */ null);
          });
    }
  }
}
