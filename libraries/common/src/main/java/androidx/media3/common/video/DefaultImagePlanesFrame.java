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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Default implementation of {@link ImagePlanesFrame} with {@link ReferenceCounter} lifecycle
 * management.
 */
@RestrictTo(Scope.LIBRARY_GROUP)
public final class DefaultImagePlanesFrame implements ImagePlanesFrame, ReferenceCounter {

  /** An implementation of {@link ImagePlanesFrame.Plane}. */
  public static final class DefaultPlane implements ImagePlanesFrame.Plane {
    private final ByteBuffer buffer;
    private final int rowStride;
    private final int pixelStride;

    public DefaultPlane(ByteBuffer buffer, int rowStride, int pixelStride) {
      this.buffer = checkNotNull(buffer);
      checkArgument(rowStride > 0, "rowStride must be positive: %s", rowStride);
      checkArgument(pixelStride > 0, "pixelStride must be positive: %s", pixelStride);
      this.rowStride = rowStride;
      this.pixelStride = pixelStride;
    }

    @Override
    public ByteBuffer getBuffer() {
      return buffer;
    }

    @Override
    public int getRowStride() {
      return rowStride;
    }

    @Override
    public int getPixelStride() {
      return pixelStride;
    }
  }

  /** A builder for {@link DefaultImagePlanesFrame}. */
  public static final class Builder implements ImagePlanesFrame.Builder, ReferenceCounter.Builder {

    private final ImmutableList<Plane> planes;
    private final SharedStateReferenceCounter.Builder delegateCounterBuilder;
    private Format format;
    private ImmutableMap<String, Object> metadata;
    private long contentTimeUs;
    @Nullable private Object internalImage;

    /**
     * Creates a builder for {@link ImagePlanesFrame} instances without lifecycle management.
     *
     * @param planes The {@link Plane} instances backing this frame.
     */
    public Builder(List<Plane> planes) {
      checkArgument(!planes.isEmpty(), "planes cannot be empty");
      this.planes = ImmutableList.copyOf(planes);
      this.delegateCounterBuilder = new SharedStateReferenceCounter.Builder();
      this.format = new Format.Builder().build();
      this.metadata = ImmutableMap.of();
      this.contentTimeUs = C.TIME_UNSET;
    }

    /**
     * Creates a builder for reference-counted {@link ImagePlanesFrame} instances.
     *
     * @param planes The {@link Plane} instances backing this frame.
     * @param releaseExecutor The {@link Executor} on which {@code releaseCallback} is called.
     * @param releaseCallback The callback invoked when the frame is fully released.
     */
    public Builder(List<Plane> planes, Executor releaseExecutor, ReleaseCallback releaseCallback) {
      checkArgument(!planes.isEmpty(), "planes cannot be empty");
      this.planes = ImmutableList.copyOf(planes);
      this.delegateCounterBuilder =
          new SharedStateReferenceCounter.Builder(releaseExecutor, releaseCallback);
      this.format = new Format.Builder().build();
      this.metadata = ImmutableMap.of();
      this.contentTimeUs = C.TIME_UNSET;
    }

    private Builder(DefaultImagePlanesFrame frame) {
      this.planes = frame.planes;
      this.delegateCounterBuilder =
          new SharedStateReferenceCounter.Builder(frame.delegateCounter.getSharedState());
      this.format = frame.format;
      this.metadata = frame.metadata;
      this.contentTimeUs = frame.contentTimeUs;
      this.internalImage = frame.internalImage;
    }

    @CanIgnoreReturnValue
    @Override
    public DefaultImagePlanesFrame.Builder shouldIncrementReferenceCount() {
      delegateCounterBuilder.shouldIncrementReferenceCount();
      return this;
    }

    @Override
    public DefaultImagePlanesFrame build() {
      return new DefaultImagePlanesFrame(this);
    }

    @CanIgnoreReturnValue
    @Override
    public DefaultImagePlanesFrame.Builder setMetadata(Map<String, Object> metadata) {
      this.metadata = ImmutableMap.copyOf(metadata);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public DefaultImagePlanesFrame.Builder setContentTimeUs(long contentTimeUs) {
      this.contentTimeUs = contentTimeUs;
      return this;
    }

    @CanIgnoreReturnValue
    public DefaultImagePlanesFrame.Builder setFormat(Format format) {
      this.format = format;
      return this;
    }

    @CanIgnoreReturnValue
    public DefaultImagePlanesFrame.Builder setInternalImage(@Nullable Object internalImage) {
      this.internalImage = internalImage;
      return this;
    }
  }

  private final ImmutableList<Plane> planes;
  private final Format format;
  private final ImmutableMap<String, Object> metadata;
  private final long contentTimeUs;
  @Nullable private final Object internalImage;
  private final SharedStateReferenceCounter delegateCounter;

  private DefaultImagePlanesFrame(Builder builder) {
    this.planes = builder.planes;
    this.format = builder.format;
    this.metadata = builder.metadata;
    this.contentTimeUs = builder.contentTimeUs;
    this.internalImage = builder.internalImage;
    this.delegateCounter = builder.delegateCounterBuilder.build();
  }

  @Override
  public Format getFormat() {
    return format;
  }

  @Override
  public ImmutableMap<String, Object> getMetadata() {
    return metadata;
  }

  @Override
  public long getContentTimeUs() {
    return contentTimeUs;
  }

  @Override
  public ImmutableList<Plane> getPlanes() {
    return planes;
  }

  @Override
  public DefaultImagePlanesFrame.Builder buildUpon() {
    delegateCounter.checkNotReleased(
        "Cannot buildUpon a DefaultImagePlanesFrame that has already been released.");
    return new Builder(this);
  }

  @Override
  public void release(@Nullable SyncFenceWrapper releaseFence) {
    delegateCounter.release(releaseFence);
  }

  @Nullable
  public Object getInternalImage() {
    return internalImage;
  }
}
