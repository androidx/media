/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * A sequence of {@link EditedMediaItem} instances.
 *
 * <p>{@linkplain EditedMediaItem} instances in a sequence don't overlap in time.
 */
@UnstableApi
public final class EditedMediaItemSequence {

  /**
   * The {@link EditedMediaItem} instances in the sequence.
   *
   * <p>This list must not be empty.
   */
  public final ImmutableList<EditedMediaItem> editedMediaItems;

  /**
   * Creates an instance.
   *
   * @param editedMediaItems The {@link #editedMediaItems}.
   */
  public EditedMediaItemSequence(List<EditedMediaItem> editedMediaItems) {
    checkArgument(!editedMediaItems.isEmpty());
    this.editedMediaItems = ImmutableList.copyOf(editedMediaItems);
  }
}
