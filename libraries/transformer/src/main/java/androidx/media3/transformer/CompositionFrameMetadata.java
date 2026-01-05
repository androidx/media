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
package androidx.media3.transformer;

import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.Frame;

/**
 * Metadata included on {@link Frame}s in {@link CompositionPlayer}.
 *
 * <p>Applications can extend this class to add custom metadata.
 */
@ExperimentalApi // TODO: b/470355043 - Publish CompositionPlayer.
public class CompositionFrameMetadata implements Frame.Metadata {

  /** The {@link Composition} that this frame belongs to. */
  public final Composition composition;

  /** The sequence index in the {@link #composition} that this frame belongs to. */
  public final int sequenceIndex;

  /**
   * The media item index in the {@linkplain Composition#sequences sequence} that this frame belongs
   * to.
   */
  public final int itemIndex;

  /** Creates a new instance. */
  public CompositionFrameMetadata(Composition composition, int sequenceIndex, int itemIndex) {
    this.composition = composition;
    this.sequenceIndex = sequenceIndex;
    this.itemIndex = itemIndex;
  }
}
