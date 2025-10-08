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

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import java.util.concurrent.Executor;

/** A {@link Frame} implementation that wraps a {@link GlTextureInfo}. */
@UnstableApi
@RestrictTo(Scope.LIBRARY_GROUP)
public class GlTextureFrame implements Frame {

  /** Metadata associated with a {@link GlTextureFrame}. */
  public static class Metadata implements Frame.Metadata {

    /** A builder for {@link Metadata} instances. */
    public static final class Builder {
      private long presentationTimeUs;
      private Format format;
      private long releaseTimeNs;

      /** Creates a new {@link Builder}. */
      public Builder() {
        presentationTimeUs = C.TIME_UNSET;
        format = new Format.Builder().build();
        releaseTimeNs = C.TIME_UNSET;
      }

      /** Creates a new {@link Builder} with values initialized to the given {@link Metadata}. */
      public Builder(Metadata metadata) {
        this.presentationTimeUs = metadata.presentationTimeUs;
        this.format = metadata.format;
        this.releaseTimeNs = metadata.releaseTimeNs;
      }

      /** Sets the {@link Metadata#presentationTimeUs}. */
      @CanIgnoreReturnValue
      public Builder setPresentationTimeUs(long presentationTimeUs) {
        this.presentationTimeUs = presentationTimeUs;
        return this;
      }

      /** Sets the {@link Metadata#format}. */
      @CanIgnoreReturnValue
      public Builder setFormat(Format format) {
        this.format = format;
        return this;
      }

      /** Sets the {@link Metadata#releaseTimeNs}. */
      @CanIgnoreReturnValue
      public Builder setReleaseTimeNs(long releaseTimeNs) {
        this.releaseTimeNs = releaseTimeNs;
        return this;
      }

      /** Builds the {@link Metadata} instance. */
      public Metadata build() {
        return new Metadata(this);
      }
    }

    private final long presentationTimeUs;
    private final long releaseTimeNs;
    private final Format format;

    private Metadata(Builder builder) {
      presentationTimeUs = builder.presentationTimeUs;
      format = builder.format;
      releaseTimeNs = builder.releaseTimeNs;
    }

    /** Returns the {@link #presentationTimeUs} of the frame, in microseconds. */
    public long getPresentationTimeUs() {
      return presentationTimeUs;
    }

    /** Returns the {@link #format} of the frame. */
    public Format getFormat() {
      return format;
    }

    /** Returns the {@link #releaseTimeNs} of the frame, in nanoseconds. */
    public long getReleaseTimeNs() {
      return releaseTimeNs;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (this == other) {
        return true;
      } else if (!(other instanceof Metadata)) {
        return false;
      }
      Metadata that = (Metadata) other;
      return presentationTimeUs == that.presentationTimeUs
          && releaseTimeNs == that.releaseTimeNs
          && format.equals(that.format);
    }

    @Override
    public int hashCode() {
      return Objects.hash(presentationTimeUs, releaseTimeNs, format);
    }
  }

  private final GlTextureInfo glTextureInfo;
  private final GlTextureFrame.Metadata metadata;
  private final Executor releaseTextureExecutor;
  private final Consumer<GlTextureInfo> releaseTextureCallback;

  public GlTextureFrame(
      GlTextureInfo glTextureInfo,
      GlTextureFrame.Metadata metadata,
      Executor releaseTextureExecutor,
      Consumer<GlTextureInfo> releaseTextureCallback) {
    this.glTextureInfo = glTextureInfo;
    this.metadata = metadata;
    this.releaseTextureExecutor = releaseTextureExecutor;
    this.releaseTextureCallback = releaseTextureCallback;
  }

  @Override
  public GlTextureFrame.Metadata getMetadata() {
    return metadata;
  }

  @Override
  public void release() {
    releaseTextureExecutor.execute(() -> releaseTextureCallback.accept(glTextureInfo));
  }

  /** Returns {@link #glTextureInfo}. */
  public GlTextureInfo getGlTextureInfo() {
    return glTextureInfo;
  }

  /** Returns {@link #releaseTextureExecutor}. */
  public Executor getReleaseTextureExecutor() {
    return releaseTextureExecutor;
  }

  /** Returns {@link #releaseTextureCallback}. */
  public Consumer<GlTextureInfo> getReleaseTextureCallback() {
    return releaseTextureCallback;
  }
}
