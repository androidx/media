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
package androidx.media3.transformer;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_ITEM_EFFECTS;
import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.transformer.Composition.KEY_COMPOSITION;
import static androidx.media3.transformer.Composition.KEY_COMPOSITION_ITEM_INDEX;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Presentation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link CompositionFrameMetadata}. */
@RunWith(AndroidJUnit4.class)
@SuppressWarnings("deprecation") // Uses deprecated CompositionFrameMetadata
public final class CompositionFrameMetadataTest {

  @Test
  public void asFrameMetadata_validIndices_populatesAllKeysIncludingItemEffects() {
    Effect testEffect = Presentation.createForHeight(720);
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri))
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(), ImmutableList.of(testEffect)))
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(item));
    Composition composition = new Composition.Builder(sequence).build();
    CompositionFrameMetadata metadata =
        new CompositionFrameMetadata(composition, /* sequenceIndex= */ 0, /* itemIndex= */ 0);

    ImmutableMap<String, Object> map = CompositionFrameMetadata.asFrameMetadata(metadata);

    assertThat(map).containsEntry(KEY_COMPOSITION, composition);
    assertThat(map).containsEntry(KEY_COMPOSITION_SEQUENCE_INDEX, 0);
    assertThat(map).containsEntry(KEY_COMPOSITION_ITEM_INDEX, 0);
    assertThat(map).containsEntry(KEY_COMPOSITOR_SETTINGS, composition.videoCompositorSettings);
    assertThat(map).containsEntry(KEY_COMPOSITION_EFFECTS, composition.effects.videoEffects);
    assertThat(map).containsEntry(KEY_ITEM_EFFECTS, ImmutableList.of(testEffect));
  }

  @Test
  public void asFrameMetadata_negativeSequenceIndex_throwsIndexOutOfBoundsException() {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
    CompositionFrameMetadata metadata =
        new CompositionFrameMetadata(composition, /* sequenceIndex= */ -1, /* itemIndex= */ 0);

    assertThrows(
        IndexOutOfBoundsException.class, () -> CompositionFrameMetadata.asFrameMetadata(metadata));
  }

  @Test
  public void asFrameMetadata_outOfBoundsSequenceIndex_throwsIndexOutOfBoundsException() {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
    CompositionFrameMetadata metadata =
        new CompositionFrameMetadata(composition, /* sequenceIndex= */ 1, /* itemIndex= */ 0);

    assertThrows(
        IndexOutOfBoundsException.class, () -> CompositionFrameMetadata.asFrameMetadata(metadata));
  }

  @Test
  public void asFrameMetadata_negativeItemIndex_throwsIndexOutOfBoundsException() {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
    CompositionFrameMetadata metadata =
        new CompositionFrameMetadata(composition, /* sequenceIndex= */ 0, /* itemIndex= */ -1);

    assertThrows(
        IndexOutOfBoundsException.class, () -> CompositionFrameMetadata.asFrameMetadata(metadata));
  }

  @Test
  public void asFrameMetadata_outOfBoundsItemIndex_throwsIndexOutOfBoundsException() {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri)).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
    CompositionFrameMetadata metadata =
        new CompositionFrameMetadata(composition, /* sequenceIndex= */ 0, /* itemIndex= */ 1);

    assertThrows(
        IndexOutOfBoundsException.class, () -> CompositionFrameMetadata.asFrameMetadata(metadata));
  }
}
