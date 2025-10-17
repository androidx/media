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

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.Executor;

/** A {@link Frame} implementation that wraps a {@link GlTextureInfo}. */
@UnstableApi
@RestrictTo(Scope.LIBRARY_GROUP)
public class GlTextureFrame implements Frame {

  /** The {@link GlTextureInfo}. */
  public final GlTextureInfo glTextureInfo;

  /** The presentation time of the frame, in microseconds. */
  public final long presentationTimeUs;

  /** The release time of the frame, in nanoseconds. */
  public final long releaseTimeNs;

  /** The format of the frame. */
  public final Format format;

  private final Metadata metadata;

  /** The {@link Executor} on which the {@code releaseTextureCallback} is called. */
  public final Executor releaseTextureExecutor;

  /** The {@link Consumer} to call to release the texture. */
  public final Consumer<GlTextureInfo> releaseTextureCallback;

  /** A builder for {@link GlTextureFrame} instances. */
  public static final class Builder {
    private final GlTextureInfo glTextureInfo;
    private final Executor releaseTextureExecutor;
    private final Consumer<GlTextureInfo> releaseTextureCallback;

    private long presentationTimeUs;
    private Format format;
    private long releaseTimeNs;
    private Metadata metadata;

    /**
     * Creates a new {@link Builder}.
     *
     * @param glTextureInfo The {@link GlTextureInfo} to wrap.
     * @param releaseTextureExecutor The {@link Executor} on which the {@code
     *     releaseTextureCallback} is called.
     * @param releaseTextureCallback The {@link Consumer} to call to release the texture.
     */
    public Builder(
        GlTextureInfo glTextureInfo,
        Executor releaseTextureExecutor,
        Consumer<GlTextureInfo> releaseTextureCallback) {
      this.glTextureInfo = glTextureInfo;
      this.releaseTextureExecutor = releaseTextureExecutor;
      this.releaseTextureCallback = releaseTextureCallback;
      this.metadata = new Metadata() {};
      presentationTimeUs = C.TIME_UNSET;
      format = new Format.Builder().build();
      releaseTimeNs = C.TIME_UNSET;
    }

    /** Sets the {@link GlTextureFrame#presentationTimeUs}. */
    @CanIgnoreReturnValue
    public Builder setPresentationTimeUs(long presentationTimeUs) {
      this.presentationTimeUs = presentationTimeUs;
      return this;
    }

    /** Sets the {@link GlTextureFrame#format}. */
    @CanIgnoreReturnValue
    public Builder setFormat(Format format) {
      this.format = format;
      return this;
    }

    /** Sets the {@link GlTextureFrame#releaseTimeNs}. */
    @CanIgnoreReturnValue
    public Builder setReleaseTimeNs(long releaseTimeNs) {
      this.releaseTimeNs = releaseTimeNs;
      return this;
    }

    /** Sets the {@link GlTextureFrame#metadata}. */
    @CanIgnoreReturnValue
    public Builder setMetadata(Metadata metadata) {
      this.metadata = metadata;
      return this;
    }

    /** Builds the {@link GlTextureFrame} instance. */
    public GlTextureFrame build() {
      return new GlTextureFrame(this);
    }
  }

  private GlTextureFrame(Builder builder) {
    this.glTextureInfo = builder.glTextureInfo;
    this.presentationTimeUs = builder.presentationTimeUs;
    this.releaseTimeNs = builder.releaseTimeNs;
    this.format = builder.format;
    this.metadata = builder.metadata;
    this.releaseTextureExecutor = builder.releaseTextureExecutor;
    this.releaseTextureCallback = builder.releaseTextureCallback;
  }

  @Override
  public Metadata getMetadata() {
    return metadata;
  }

  @Override
  public void release() {
    releaseTextureExecutor.execute(() -> releaseTextureCallback.accept(glTextureInfo));
  }
}
