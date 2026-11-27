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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MediaItem;
import androidx.media3.test.utils.FakePlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultCastPlayerTransferCallback}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultCastPlayerTransferCallbackTest {

  private FakePlayer sourcePlayer;
  private FakePlayer targetPlayer;
  private DefaultCastPlayerTransferCallback transferCallback;

  @Before
  public void setUp() {
    sourcePlayer = new FakePlayer();
    targetPlayer = new FakePlayer();
    transferCallback = new DefaultCastPlayerTransferCallback();
  }

  @Test
  public void transferState_filtersItemsWithNoPlaybackUri() {
    MediaItem uriItem = new MediaItem.Builder().setUri("http://uri").build();
    MediaItem noUriItem = new MediaItem.Builder().setMediaId("no-uri").build();
    sourcePlayer.setMediaItems(
        ImmutableList.of(uriItem, noUriItem), /* startIndex= */ 0, /* startPositionMs= */ 0);

    transferCallback.transferState(sourcePlayer, targetPlayer);

    assertThat(targetPlayer.getMediaItemCount()).isEqualTo(1);
    assertThat(targetPlayer.getMediaItemAt(0)).isEqualTo(uriItem);
    assertThat(targetPlayer.getCurrentMediaItemIndex()).isEqualTo(0);
  }

  @Test
  public void transferState_adjustsCurrentIndexWhenCurrentItemIsFiltered() {
    MediaItem uriItem = new MediaItem.Builder().setUri("http://uri").build();
    MediaItem noUriItem = new MediaItem.Builder().setMediaId("no-uri").build();
    sourcePlayer.setMediaItems(
        ImmutableList.of(uriItem, noUriItem), /* startIndex= */ 1, /* startPositionMs= */ 1000);

    transferCallback.transferState(sourcePlayer, targetPlayer);

    assertThat(targetPlayer.getMediaItemCount()).isEqualTo(1);
    assertThat(targetPlayer.getMediaItemAt(0)).isEqualTo(uriItem);
    assertThat(targetPlayer.getCurrentMediaItemIndex()).isEqualTo(0);
    assertThat(targetPlayer.getCurrentPosition()).isEqualTo(0);
  }

  @Test
  public void transferState_whenCurrentItemIsNotFiltered_preservesPosition() {
    MediaItem uriItem = new MediaItem.Builder().setUri("http://uri").build();
    MediaItem noUriItem = new MediaItem.Builder().setMediaId("no-uri").build();
    sourcePlayer.setMediaItems(
        ImmutableList.of(uriItem, noUriItem), /* startIndex= */ 0, /* startPositionMs= */ 1000);

    transferCallback.transferState(sourcePlayer, targetPlayer);

    assertThat(targetPlayer.getMediaItemCount()).isEqualTo(1);
    assertThat(targetPlayer.getMediaItemAt(0)).isEqualTo(uriItem);
    assertThat(targetPlayer.getCurrentMediaItemIndex()).isEqualTo(0);
    assertThat(targetPlayer.getCurrentPosition()).isEqualTo(1000);
  }

  @Test
  public void transferState_whenAllItemsAreFiltered_setsEmptyPlaylistAndIndexUnset() {
    MediaItem noUriItem1 = new MediaItem.Builder().setMediaId("no-uri-1").build();
    MediaItem noUriItem2 = new MediaItem.Builder().setMediaId("no-uri-2").build();
    sourcePlayer.setMediaItems(
        ImmutableList.of(noUriItem1, noUriItem2), /* startIndex= */ 0, /* startPositionMs= */ 0);

    transferCallback.transferState(sourcePlayer, targetPlayer);

    assertThat(targetPlayer.getMediaItemCount()).isEqualTo(0);
    assertThat(targetPlayer.getCurrentMediaItemIndex()).isEqualTo(0);
  }
}
