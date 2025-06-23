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

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;

/* package */ final class CompositionUtil {

  /**
   * Returns whether the player should be re-prepared after switching {@link
   * EditedMediaItemSequence} to the new one.
   *
   * <p>It returns {@code true} if the {@code oldSequence} is {@code null}.
   *
   * <p>Currently this method returns {@code false} when
   *
   * <ul>
   *   <li>All the {@link MediaItem mediaItems} in the {@code oldSequence} match with those in the
   *       {@code newSequence}.
   *   <li>Changes in {@link EditedMediaItem}:
   *       <ul>
   *         <li>{@link EditedMediaItem#removeAudio} changed.
   *         <li>{@linkplain EditedMediaItem#effects Video effects} changed.
   *       </ul>
   * </ul>
   */
  public static boolean shouldRePreparePlayer(
      @Nullable EditedMediaItemSequence oldSequence, EditedMediaItemSequence newSequence) {
    if (oldSequence == null) {
      return true;
    }

    if (oldSequence.editedMediaItems.size() != newSequence.editedMediaItems.size()) {
      return true;
    }

    for (int i = 0; i < oldSequence.editedMediaItems.size(); i++) {
      EditedMediaItem oldEditedMediaItem = oldSequence.editedMediaItems.get(i);
      EditedMediaItem newEditedMediaItem = newSequence.editedMediaItems.get(i);
      if (!oldEditedMediaItem.mediaItem.equals(newEditedMediaItem.mediaItem)) {
        // All MediaItems must match - this checks the URI and the clipping.
        return true;
      }

      if (oldEditedMediaItem.flattenForSlowMotion != newEditedMediaItem.flattenForSlowMotion) {
        return true;
      }

      if (oldEditedMediaItem.removeVideo != newEditedMediaItem.removeVideo) {
        return true;
      }

      if (!oldEditedMediaItem.effects.audioProcessors.equals(
          newEditedMediaItem.effects.audioProcessors)) {
        return true;
      }
    }
    return false;
  }

  private CompositionUtil() {}
}
