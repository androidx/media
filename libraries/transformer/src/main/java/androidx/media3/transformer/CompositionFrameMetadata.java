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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_ITEM_EFFECTS;
import static androidx.media3.transformer.Composition.KEY_COMPOSITION;
import static androidx.media3.transformer.Composition.KEY_COMPOSITION_ITEM_INDEX;

import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.Frame;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.HardwareBufferFrame;
import com.google.common.collect.ImmutableMap;

/**
 * Metadata included on {@link HardwareBufferFrame}s in {@link CompositionPlayer}.
 *
 * <p>Applications can extend this class to add custom metadata.
 *
 * @deprecated Use similar keys in {@link DefaultGlFrameProcessor} instead.
 */
@ExperimentalApi // TODO: b/470355043 - Publish CompositionPlayer.
@Deprecated // TODO: b/498547782 - Remove with effect.HardwareBufferFrame.
public class CompositionFrameMetadata implements HardwareBufferFrame.Metadata {

  /** Converts the {@link CompositionFrameMetadata} to a map for {@link Frame#getMetadata()}. */
  @RequiresApi(26)
  public static ImmutableMap<String, Object> asFrameMetadata(
      CompositionFrameMetadata compositionFrameMetadata) {
    Composition composition = compositionFrameMetadata.composition;
    int sequenceIndex = compositionFrameMetadata.sequenceIndex;
    int itemIndex = compositionFrameMetadata.itemIndex;
    EditedMediaItem editedMediaItem =
        composition.sequences.get(sequenceIndex).editedMediaItems.get(itemIndex);
    return new ImmutableMap.Builder<String, Object>()
        .put(KEY_COMPOSITION, composition)
        .put(KEY_COMPOSITION_SEQUENCE_INDEX, sequenceIndex)
        .put(KEY_COMPOSITION_ITEM_INDEX, itemIndex)
        .put(KEY_ITEM_EFFECTS, editedMediaItem.effects.videoEffects)
        .put(KEY_COMPOSITOR_SETTINGS, composition.videoCompositorSettings)
        .put(KEY_COMPOSITION_EFFECTS, composition.effects.videoEffects)
        .buildOrThrow();
  }

  /**
   * Metadata key for storing the composition frame metadata.
   *
   * @deprecated Use {@link Composition#KEY_COMPOSITION}, {@link
   *     DefaultGlFrameProcessor#KEY_COMPOSITION_SEQUENCE_INDEX} and {@link
   *     Composition#KEY_COMPOSITION_ITEM_INDEX} instead.
   */
  @Deprecated
  public static final String KEY_COMPOSITION_FRAME_METADATA = "KEY_COMPOSITION_FRAME_METADATA";

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
