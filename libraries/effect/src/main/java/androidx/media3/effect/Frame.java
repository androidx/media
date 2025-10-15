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
import androidx.media3.common.util.UnstableApi;

/**
 * An interface that represents a single frame of data.
 *
 * <p>This interface is experimental and subject to change.
 *
 * <p>A {@code Frame} is the basic unit of data that {@link FrameConsumer}s can process.
 * Implementations should wrap the underlying data objects, such as a {@link
 * android.graphics.Bitmap} or a GL texture.
 */
@UnstableApi
@RestrictTo(Scope.LIBRARY_GROUP)
public interface Frame {

  /** A marker interface for storing arbitrary metadata associated with a {@link Frame}. */
  interface Metadata {}

  /** Returns the {@link Metadata} associated with this frame. */
  Frame.Metadata getMetadata();

  /** Releases the frame and its underlying resources. */
  void release();
}
