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

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;

/** Default implementation of {@link HardwareBufferFrame}. */
@RestrictTo(Scope.LIBRARY_GROUP)
public final class DefaultHardwareBufferFrame implements HardwareBufferFrame {

  /** Concrete implementation of {@link HardwareBufferFrame.Builder}. */
  public static final class Builder implements HardwareBufferFrame.Builder {

    private final HardwareBuffer hardwareBuffer;
    private Format format;
    private ImmutableMap<String, Object> metadata;
    private long contentTimeUs;
    @Nullable private Object internalImage;

    /**
     * Creates a builder for {@link HardwareBufferFrame} instances.
     *
     * @param hardwareBuffer The {@link HardwareBuffer} that backs the frame.
     */
    @RequiresApi(26)
    public Builder(HardwareBuffer hardwareBuffer) {
      this.hardwareBuffer = hardwareBuffer;
      this.format = new Format.Builder().build();
      this.metadata = ImmutableMap.of();
      this.contentTimeUs = C.TIME_UNSET;
    }

    private Builder(DefaultHardwareBufferFrame frame) {
      this.hardwareBuffer = frame.hardwareBuffer;
      this.format = frame.format;
      this.metadata = frame.metadata;
      this.contentTimeUs = frame.contentTimeUs;
      this.internalImage = frame.internalImage;
    }

    @Override
    public DefaultHardwareBufferFrame build() {
      return new DefaultHardwareBufferFrame(this);
    }

    @CanIgnoreReturnValue
    @Override
    public DefaultHardwareBufferFrame.Builder setMetadata(Map<String, Object> metadata) {
      this.metadata = ImmutableMap.copyOf(metadata);
      return this;
    }

    @CanIgnoreReturnValue
    @Override
    public DefaultHardwareBufferFrame.Builder setContentTimeUs(long contentTimeUs) {
      this.contentTimeUs = contentTimeUs;
      return this;
    }

    @CanIgnoreReturnValue
    public DefaultHardwareBufferFrame.Builder setFormat(Format format) {
      this.format = format;
      return this;
    }

    @CanIgnoreReturnValue
    public DefaultHardwareBufferFrame.Builder setInternalImage(@Nullable Object internalImage) {
      this.internalImage = internalImage;
      return this;
    }
  }

  private final HardwareBuffer hardwareBuffer;
  private final Format format;
  private final ImmutableMap<String, Object> metadata;
  private final long contentTimeUs;
  @Nullable private final Object internalImage;

  /** Private constructor used by the builder. */
  private DefaultHardwareBufferFrame(Builder builder) {
    this.hardwareBuffer = builder.hardwareBuffer;
    this.format = builder.format;
    this.metadata = builder.metadata;
    this.contentTimeUs = builder.contentTimeUs;
    this.internalImage = builder.internalImage;
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

  @RequiresApi(26)
  @Override
  public HardwareBuffer getHardwareBuffer() {
    return hardwareBuffer;
  }

  @Override
  public HardwareBufferFrame.Builder buildUpon() {
    return new Builder(this);
  }

  @Nullable
  public Object getInternalImage() {
    return internalImage;
  }
}
