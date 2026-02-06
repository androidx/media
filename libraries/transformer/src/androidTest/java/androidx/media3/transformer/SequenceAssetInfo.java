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

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C.TrackType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Set;

/** Test assets that describe various {@link EditedMediaItemSequence} configurations. */
/* package */ final class SequenceAssetInfo {
  public final ImmutableList<EditedMediaItemAssetInfo> assets;
  private final ImmutableSet<@TrackType Integer> trackTypes;
  private final boolean isLooping;

  public SequenceAssetInfo(
      Set<@TrackType Integer> trackTypes,
      EditedMediaItemAssetInfo asset,
      EditedMediaItemAssetInfo... assets) {
    this(/* isLooping= */ false, trackTypes, asset, assets);
  }

  public SequenceAssetInfo(
      boolean isLooping,
      Set<@TrackType Integer> trackTypes,
      EditedMediaItemAssetInfo asset,
      EditedMediaItemAssetInfo... assets) {
    this.assets =
        new ImmutableList.Builder<EditedMediaItemAssetInfo>().add(asset).add(assets).build();
    this.trackTypes = ImmutableSet.copyOf(trackTypes);
    this.isLooping = isLooping;
  }

  public EditedMediaItemSequence getEditedMediaItemSequence() {
    EditedMediaItemSequence.Builder sequenceBuilder =
        new EditedMediaItemSequence.Builder(trackTypes);
    for (EditedMediaItemAssetInfo asset : assets) {
      sequenceBuilder.addItem(checkNotNull(asset.editedMediaItem));
    }
    sequenceBuilder.setIsLooping(isLooping);
    return sequenceBuilder.build();
  }

  public ImmutableList<Long> getExpectedVideoTimestampsUs() {
    ImmutableList.Builder<Long> expectedVideoTimestampsUs = new ImmutableList.Builder<>();
    long previousDuration = 0;
    for (EditedMediaItemAssetInfo asset : assets) {
      long finalPreviousDuration = previousDuration;
      ImmutableList<Long> videoTimestampsUs =
          asset.videoTimestampsUs == null ? ImmutableList.of() : asset.videoTimestampsUs;
      expectedVideoTimestampsUs.addAll(
          Iterables.transform(
              videoTimestampsUs, timestampUs -> finalPreviousDuration + timestampUs));
      previousDuration += checkNotNull(asset.editedMediaItem).getPresentationDurationUs();
    }
    return expectedVideoTimestampsUs.build();
  }

  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    for (EditedMediaItemAssetInfo asset : assets) {
      stringBuilder.append(asset);
    }
    if (isLooping) {
      stringBuilder.append("Loop");
    }
    return stringBuilder.toString();
  }
}
