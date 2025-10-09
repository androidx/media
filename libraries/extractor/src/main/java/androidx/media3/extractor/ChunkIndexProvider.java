/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law of agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

/**
 * An interface for objects that can provide a {@link ChunkIndex}.
 *
 * <p>This should be implemented by classes, typically a {@link SeekMap}, that are used in contexts
 * where a {@link ChunkIndex} is required to access sample-level information. For example, chunk
 * sources for adaptive streaming formats like DASH require a {@code ChunkIndex} to locate samples
 * within a media segment.
 */
@UnstableApi
public interface ChunkIndexProvider {

  /**
   * Returns a {@link ChunkIndex} for use by chunk-based media sources, or {@code null} if not
   * applicable.
   */
  @Nullable
  ChunkIndex getChunkIndex();
}
