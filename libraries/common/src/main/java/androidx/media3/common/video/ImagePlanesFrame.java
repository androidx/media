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

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * A {@link Frame} backed by CPU image planes (e.g. planar/semi-planar YUV or single-plane RGB/RGBA
 * from {@link android.media.Image} or {@link ByteBuffer} instances).
 *
 * <p>The pixel format of the planes is specified by {@link Format#pixelFormat} on the frame's
 * {@linkplain #getFormat() format}.
 */
// TODO: b/531653682 - Remove LIBRARY_GROUP restriction when ImagePlanesFrame pipeline is ready.
@RestrictTo(Scope.LIBRARY_GROUP)
public interface ImagePlanesFrame extends Frame {

  /** Represents a single data plane of the frame. */
  interface Plane {
    /** Returns the {@link ByteBuffer} containing the plane data. */
    ByteBuffer getBuffer();

    /** Returns the row stride of the plane, in bytes. */
    int getRowStride();

    /** Returns the pixel stride of the plane, in bytes. */
    int getPixelStride();
  }

  /** A Builder for {@link ImagePlanesFrame} instances. */
  interface Builder {
    /**
     * Sets the {@linkplain Frame#getMetadata() metadata} associated with the frame.
     *
     * @param metadata The metadata to associate with this frame.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    Builder setMetadata(Map<String, Object> metadata);

    /**
     * Sets the {@linkplain Frame#getContentTimeUs() content time} of the frame.
     *
     * @param contentTimeUs The time of the frame in the context of the input media, in
     *     microseconds, or {@link C#TIME_UNSET} if unset.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    Builder setContentTimeUs(long contentTimeUs);

    /** Builds an {@link ImagePlanesFrame} instance. */
    ImagePlanesFrame build();
  }

  /** Returns the planes backing this frame. */
  ImmutableList<Plane> getPlanes();

  /** Returns a {@link Builder} initialized with the values of this instance. */
  Builder buildUpon();
}
