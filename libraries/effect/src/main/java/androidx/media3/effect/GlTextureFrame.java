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
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import java.util.concurrent.Executor;

/** A {@link Frame} implementation that wraps a {@link GlTextureInfo}. */
@UnstableApi
@RestrictTo(Scope.LIBRARY_GROUP)
public class GlTextureFrame implements Frame {

  /** Metadata associated with a {@link GlTextureFrame}. */
  public static class Metadata implements Frame.Metadata {
    private final long presentationTimeUs;
    private final Format format;

    public Metadata(long presentationTimeUs, Format format) {
      this.presentationTimeUs = presentationTimeUs;
      this.format = format;
    }

    public long getPresentationTimeUs() {
      return presentationTimeUs;
    }

    public Format getFormat() {
      return format;
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

  public GlTextureInfo getGlTextureInfo() {
    return glTextureInfo;
  }
}
