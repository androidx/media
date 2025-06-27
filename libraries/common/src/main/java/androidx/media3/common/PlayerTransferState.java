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
package androidx.media3.common;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds a snapshot of a {@link Player player's} transferable state.
 *
 * <p>We define transferable state to be state that should be carried over from one player to
 * another when transferring playback. For example, the media playlist and the playback position are
 * part of the transferable state. However, the buffering position is part of the player's state but
 * it's not state we carry over when moving playback.
 *
 * <p>This class is useful when moving playback across devices. For example, across an Android
 * device and a Cast receiver.
 */
@UnstableApi
public final class PlayerTransferState {

  /** Builder for {@link PlayerTransferState}. */
  public static final class Builder {

    private boolean playWhenReady;
    private int repeatMode;
    private boolean shuffleModeEnabled;
    private int currentMediaItemIndex;
    private long currentPosition;
    private ImmutableList<MediaItem> mediaItems;
    private PlaybackParameters playbackParameters;

    /**
     * Constructs a new {@code Builder} with default values.
     *
     * @see PlayerTransferState#buildUpon
     */
    public Builder() {
      this.playWhenReady = false;
      this.repeatMode = Player.REPEAT_MODE_OFF;
      this.shuffleModeEnabled = false;
      this.currentMediaItemIndex = 0;
      this.currentPosition = 0L;
      this.mediaItems = ImmutableList.of();
      this.playbackParameters = PlaybackParameters.DEFAULT;
    }

    private Builder(PlayerTransferState state) {
      this.playWhenReady = state.playWhenReady;
      this.repeatMode = state.repeatMode;
      this.shuffleModeEnabled = state.shuffleModeEnabled;
      this.currentMediaItemIndex = state.currentMediaItemIndex;
      this.currentPosition = state.currentPosition;
      this.mediaItems = state.mediaItems;
      this.playbackParameters = state.playbackParameters;
    }

    /**
     * Sets the {@link Player#getPlayWhenReady() play when ready value}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setPlayWhenReady(boolean playWhenReady) {
      this.playWhenReady = playWhenReady;
      return this;
    }

    /**
     * Sets the {@link Player#getRepeatMode() repeat mode}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setRepeatMode(int repeatMode) {
      this.repeatMode = repeatMode;
      return this;
    }

    /**
     * Sets the {@link Player#getShuffleModeEnabled() shuffle mode enabled value}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setShuffleModeEnabled(boolean shuffleModeEnabled) {
      this.shuffleModeEnabled = shuffleModeEnabled;
      return this;
    }

    /**
     * Sets the {@link Player#getCurrentMediaItemIndex() current media item index}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setCurrentMediaItemIndex(int currentMediaItemIndex) {
      this.currentMediaItemIndex = currentMediaItemIndex;
      return this;
    }

    /**
     * Sets the {@link Player#getCurrentPosition() current position}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setCurrentPosition(long currentPosition) {
      this.currentPosition = currentPosition;
      return this;
    }

    /**
     * Sets the {@link Player#getMediaItemAt media items}.
     *
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setMediaItems(List<MediaItem> mediaItems) {
      this.mediaItems = ImmutableList.copyOf(mediaItems);
      return this;
    }

    /**
     * Sets the {@link Player#getPlaybackParameters() playback parameters}.
     *
     * @param playbackParameters The playback parameters.
     * @return This {@code Builder} instance for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setPlaybackParameters(PlaybackParameters playbackParameters) {
      this.playbackParameters = Objects.requireNonNull(playbackParameters);
      return this;
    }

    /** Builds a {@link PlayerTransferState} instance. */
    public PlayerTransferState build() {
      return new PlayerTransferState(this);
    }
  }

  private final boolean playWhenReady;
  private final int repeatMode;
  private final boolean shuffleModeEnabled;
  private final int currentMediaItemIndex;
  private final long currentPosition;
  private final ImmutableList<MediaItem> mediaItems;
  private final PlaybackParameters playbackParameters;

  private PlayerTransferState(Builder builder) {
    this.playWhenReady = builder.playWhenReady;
    this.repeatMode = builder.repeatMode;
    this.shuffleModeEnabled = builder.shuffleModeEnabled;
    this.currentMediaItemIndex = builder.currentMediaItemIndex;
    this.currentPosition = builder.currentPosition;
    this.mediaItems = builder.mediaItems;
    this.playbackParameters = builder.playbackParameters;
  }

  /**
   * Equivalent to {@link #builderFromPlayer builderFromPlayer(player).build()}.
   *
   * @return A {@link PlayerTransferState} whose contents are populated with the state from the
   *     given {@link Player}.
   */
  public static PlayerTransferState fromPlayer(Player player) {
    return PlayerTransferState.builderFromPlayer(player).build();
  }

  /**
   * Creates a new {@link Builder} that's pre-populated with the values from the given {@link
   * Player}.
   *
   * @param player The {@link Player} instance to populate the builder from.
   * @return A new {@link Builder} instance populated with the given player's current state.
   */
  public static Builder builderFromPlayer(Player player) {
    Objects.requireNonNull(player);
    List<MediaItem> mediaItems = new ArrayList<>();
    for (int i = 0; i < player.getMediaItemCount(); i++) {
      mediaItems.add(player.getMediaItemAt(i));
    }

    return new Builder()
        .setPlayWhenReady(player.getPlayWhenReady())
        .setRepeatMode(player.getRepeatMode())
        .setShuffleModeEnabled(player.getShuffleModeEnabled())
        .setCurrentMediaItemIndex(player.getCurrentMediaItemIndex())
        .setCurrentPosition(player.getCurrentPosition())
        .setMediaItems(mediaItems)
        .setPlaybackParameters(player.getPlaybackParameters());
  }

  /**
   * Applies this state to a given {@link Player} instance by calling the corresponding setters.
   *
   * @param player The {@link Player} instance to which the state should be applied.
   */
  public void setToPlayer(Player player) {
    Objects.requireNonNull(player);
    // We don't store the available commands because each player operation can change the available
    // commands.
    if (player.getAvailableCommands().contains(Player.COMMAND_PLAY_PAUSE)) {
      player.setPlayWhenReady(this.playWhenReady);
    }
    if (player.getAvailableCommands().contains(Player.COMMAND_SET_REPEAT_MODE)) {
      player.setRepeatMode(this.repeatMode);
    }
    if (player.getAvailableCommands().contains(Player.COMMAND_SET_SHUFFLE_MODE)) {
      player.setShuffleModeEnabled(this.shuffleModeEnabled);
    }
    if (player.getAvailableCommands().contains(Player.COMMAND_SET_MEDIA_ITEM)) {
      player.setMediaItems(this.mediaItems, this.currentMediaItemIndex, this.currentPosition);
    }
    if (player.getAvailableCommands().contains(Player.COMMAND_SET_SPEED_AND_PITCH)) {
      player.setPlaybackParameters(this.playbackParameters);
    }
  }

  /** Returns a {@link Builder} pre-populated with the current state values. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /** Returns the {@link Player#getPlayWhenReady() play when ready value}. */
  public boolean getPlayWhenReady() {
    return playWhenReady;
  }

  /** Returns the {@link Player#getRepeatMode() repeat mode}. */
  public int getRepeatMode() {
    return repeatMode;
  }

  /** Returns the {@link Player#getShuffleModeEnabled() shuffle mode enabled value}. */
  public boolean getShuffleModeEnabled() {
    return shuffleModeEnabled;
  }

  /** Returns the {@link Player#getCurrentMediaItemIndex() current media item index}. */
  public int getCurrentMediaItemIndex() {
    return currentMediaItemIndex;
  }

  /** Returns the {@link Player#getCurrentPosition() current position}. */
  public long getCurrentPosition() {
    return currentPosition;
  }

  /** Returns the {@link Player#getMediaItemAt media items}. */
  public ImmutableList<MediaItem> getMediaItems() {
    return mediaItems;
  }

  /** Returns the {@link Player#getPlaybackParameters() playback parameters}. */
  public PlaybackParameters getPlaybackParameters() {
    return playbackParameters;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlayerTransferState that = (PlayerTransferState) o;
    return playWhenReady == that.playWhenReady
        && repeatMode == that.repeatMode
        && shuffleModeEnabled == that.shuffleModeEnabled
        && currentMediaItemIndex == that.currentMediaItemIndex
        && currentPosition == that.currentPosition
        && Objects.equals(mediaItems, that.mediaItems)
        && Objects.equals(playbackParameters, that.playbackParameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        playWhenReady,
        repeatMode,
        shuffleModeEnabled,
        currentMediaItemIndex,
        currentPosition,
        mediaItems,
        playbackParameters);
  }
}
