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
package androidx.media3.cast;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * The default {@link CastPlayer.TransferCallback} implementation.
 *
 * <p>Filters out {@link MediaItem}s that do not have a playback URI and adjusts the current media
 * item index accordingly.
 */
@UnstableApi
public final class DefaultCastPlayerTransferCallback implements CastPlayer.TransferCallback {

  @Override
  public void transferState(Player sourcePlayer, Player targetPlayer) {
    PlayerTransferState transferState = PlayerTransferState.fromPlayer(sourcePlayer);
    List<MediaItem> mediaItems = transferState.getMediaItems();
    ImmutableList.Builder<MediaItem> playableMediaItemsBuilder = ImmutableList.builder();
    int currentIndex = transferState.getCurrentMediaItemIndex();
    int newCurrentIndex = currentIndex;
    boolean currentIndexFiltered = false;
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);
      if (isPlayable(mediaItem)) {
        playableMediaItemsBuilder.add(mediaItem);
      } else if (i < currentIndex) {
        newCurrentIndex--;
      } else if (i == currentIndex) {
        currentIndexFiltered = true;
      }
    }
    List<MediaItem> playableMediaItems = playableMediaItemsBuilder.build();
    if (playableMediaItems.size() < mediaItems.size()) {
      if (playableMediaItems.isEmpty()) {
        newCurrentIndex = C.INDEX_UNSET;
      } else {
        newCurrentIndex = Math.min(newCurrentIndex, playableMediaItems.size() - 1);
      }
      PlayerTransferState.Builder builder =
          transferState
              .buildUpon()
              .setMediaItems(playableMediaItems)
              .setCurrentMediaItemIndex(newCurrentIndex);
      if (currentIndexFiltered) {
        builder.setCurrentPosition(0);
      }
      transferState = builder.build();
    }
    transferState.setToPlayer(targetPlayer);
  }

  private static boolean isPlayable(MediaItem mediaItem) {
    return mediaItem.localConfiguration != null
        && !Uri.EMPTY.equals(mediaItem.localConfiguration.uri);
  }
}
