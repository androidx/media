/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.transformer.CompositionUtil.shouldRePreparePlayer;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.effect.Contrast;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link CompositionUtil}. */
@RunWith(AndroidJUnit4.class)
public class CompositionUtilTest {

  private static final String URI_0 = "CompositionUtilTest_0";
  private static final String URI_1 = "CompositionUtilTest_1";

  @Test
  public void shouldRePreparePlayer_withNoPreviousSequence_returnsTrue() {
    assertThat(
            shouldRePreparePlayer(
                /* oldSequence= */ null,
                new EditedMediaItemSequence.Builder(
                        new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build())
                    .build()))
        .isTrue();
  }

  @Test
  public void shouldRePreparePlayer_withPreviousSequenceAddedNumberOfMediaItems_returnsTrue() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(editedMediaItem, editedMediaItem).build()))
        .isTrue();
  }

  @Test
  public void shouldRePreparePlayer_withPreviousSequenceReducedNumberOfMediaItems_returnsTrue() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(editedMediaItem, editedMediaItem).build()))
        .isTrue();
  }

  @Test
  public void shouldRePreparePlayer_withPreviousSequenceMismatchingMediaItems_returnsTrue() {
    EditedMediaItem editedMediaItem0 =
        new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(URI_1)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem0).build(),
                new EditedMediaItemSequence.Builder(editedMediaItem1).build()))
        .isTrue();
  }

  @Test
  public void
      shouldRePreparePlayer_withPreviousSequenceMatchingMediaItemMismatchingFlattenSlowMotion_returnsTrue() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(
                        editedMediaItem.buildUpon().setFlattenForSlowMotion(true).build())
                    .build()))
        .isTrue();
  }

  @Test
  public void
      shouldRePreparePlayer_withPreviousSequenceMatchingMediaItemMismatchingRemoveVideoMatchingRemoveAudio_returnsTrue() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(
                        editedMediaItem.buildUpon().setRemoveVideo(true).build())
                    .build()))
        .isTrue();
  }

  @Test
  public void
      shouldRePreparePlayer_withPreviousSequenceMatchingMediaItemMatchingRemoveVideoMismatchingRemoveAudio_returnsFalse() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri(URI_0)).build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(
                        editedMediaItem.buildUpon().setRemoveAudio(true).build())
                    .build()))
        .isFalse();
  }

  @Test
  public void
      shouldRePreparePlayer_withPreviousSequenceMatchingMediaItemMismatchingVideoEffectsMismatchingAudioEffects_returnTrue() {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(URI_0))
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of()))
            .build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(
                        editedMediaItem
                            .buildUpon()
                            .setEffects(
                                new Effects(
                                    /* audioProcessors= */ ImmutableList.of(
                                        new FakeAudioProcessor()),
                                    /* videoEffects= */ ImmutableList.of(new Contrast(0.4f))))
                            .build())
                    .build()))
        .isTrue();
  }

  @Test
  public void
      shouldRePreparePlayer_withPreviousSequenceMatchingMediaItemMismatchingVideoEffects_returnsFalse() {
    AudioProcessor audioProcessor = new FakeAudioProcessor();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(URI_0))
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(audioProcessor),
                    /* videoEffects= */ ImmutableList.of()))
            .build();
    assertThat(
            shouldRePreparePlayer(
                new EditedMediaItemSequence.Builder(editedMediaItem).build(),
                new EditedMediaItemSequence.Builder(
                        editedMediaItem
                            .buildUpon()
                            .setEffects(
                                new Effects(
                                    /* audioProcessors= */ ImmutableList.of(audioProcessor),
                                    /* videoEffects= */ ImmutableList.of(new Contrast(0.4f))))
                            .build())
                    .build()))
        .isFalse();
  }

  private static final class FakeAudioProcessor extends BaseAudioProcessor {
    @Override
    public void queueInput(ByteBuffer inputBuffer) {}
  }
}
