/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import com.google.common.collect.ImmutableMap;
import java.util.PriorityQueue;
import org.jspecify.annotations.Nullable;

/** Holds the metadata of the frames queued to a decoder until the decoder outputs them. */
/* package */ final class FrameMetadataQueue {

  private final PriorityQueue<FrameMetadata> queue;

  public FrameMetadataQueue() {
    queue = new PriorityQueue<>();
  }

  /** Adds the metadata of the frame with the given timestamp. */
  public void add(long timeUs, ImmutableMap<String, Object> metadata) {
    queue.add(new FrameMetadata(timeUs, metadata));
  }

  /**
   * Returns the metadata of the frame with the given timestamp, or an empty map if there is none.
   * Removes the metadata of earlier frames.
   */
  public ImmutableMap<String, Object> getMetadata(long timeUs) {
    @Nullable FrameMetadata head = queue.peek();
    while (head != null && head.timeUs < timeUs) {
      queue.remove();
      head = queue.peek();
    }
    return head != null && head.timeUs == timeUs ? head.metadata : ImmutableMap.of();
  }

  /** Removes all the metadata. */
  public void clear() {
    queue.clear();
  }

  private static final class FrameMetadata implements Comparable<FrameMetadata> {

    private final long timeUs;
    private final ImmutableMap<String, Object> metadata;

    private FrameMetadata(long timeUs, ImmutableMap<String, Object> metadata) {
      this.timeUs = timeUs;
      this.metadata = metadata;
    }

    @Override
    public int compareTo(FrameMetadata other) {
      return Long.compare(timeUs, other.timeUs);
    }
  }
}
