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

import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;

/**
 * A sequence of {@link EditedMediaItem} instances.
 *
 * <p>{@linkplain EditedMediaItem} instances in a sequence don't overlap in time.
 */
@UnstableApi
public final class EditedMediaItemSequence {

  /** A builder for instances of {@link EditedMediaItemSequence}. */
  public static final class Builder {
    private final ImmutableList.Builder<EditedMediaItem> items;
    private boolean isLooping;
    private boolean forceAudioTrack;
    private boolean forceVideoTrack;

    /** Creates an instance. */
    public Builder(EditedMediaItem... editedMediaItems) {
      items = new ImmutableList.Builder<EditedMediaItem>().add(editedMediaItems);
    }

    /* Creates an instance. */
    public Builder(List<EditedMediaItem> editedMediaItems) {
      items = new ImmutableList.Builder<EditedMediaItem>().addAll(editedMediaItems);
    }

    /** Creates a new instance to build upon the provided {@link EditedMediaItemSequence}. */
    private Builder(EditedMediaItemSequence editedMediaItemSequence) {
      items =
          new ImmutableList.Builder<EditedMediaItem>()
              .addAll(editedMediaItemSequence.editedMediaItems);
      isLooping = editedMediaItemSequence.isLooping;
      forceAudioTrack = editedMediaItemSequence.forceAudioTrack;
      forceVideoTrack = editedMediaItemSequence.forceVideoTrack;
    }

    /**
     * Adds the {@linkplain EditedMediaItem item} to the sequence.
     *
     * @param item The {@link EditedMediaItem} to add.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder addItem(EditedMediaItem item) {
      items.add(item);
      return this;
    }

    /**
     * Adds the {@linkplain EditedMediaItem items} to the sequence.
     *
     * @param items The {@link EditedMediaItem} instances to add.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder addItems(EditedMediaItem... items) {
      this.items.add(items);
      return this;
    }

    /**
     * Adds all the {@linkplain EditedMediaItem items} in the list to the sequence.
     *
     * @param items The list of {@link EditedMediaItem} instances to add.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder addItems(List<EditedMediaItem> items) {
      this.items.addAll(items);
      return this;
    }

    /**
     * Adds a gap to the sequence.
     *
     * <p>A gap is a period of time with no media.
     *
     * <p>If the gap is added at the start of the sequence, then {@linkplain
     * #experimentalSetForceAudioTrack(boolean) force audio track} or/and {@linkplain
     * #experimentalSetForceVideoTrack(boolean) force video track} flag must be set appropriately.
     *
     * @param durationUs The duration of the gap, in milliseconds.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder addGap(long durationUs) {
      items.add(
          new EditedMediaItem.Builder(
                  new MediaItem.Builder().setMediaId(EditedMediaItem.GAP_MEDIA_ID).build())
              .setDurationUs(durationUs)
              .build());
      return this;
    }

    /**
     * See {@link EditedMediaItemSequence#isLooping}.
     *
     * <p>Looping is {@code false} by default.
     *
     * @param isLooping Whether this sequence should loop.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setIsLooping(boolean isLooping) {
      this.isLooping = isLooping;
      return this;
    }

    /**
     * Forces silent audio in the {@linkplain EditedMediaItemSequence sequence}.
     *
     * <p>This flag is necessary when:
     *
     * <ul>
     *   <li>The first {@link EditedMediaItem} in the sequence does not contain audio, but
     *       subsequent items do.
     *   <li>The first item in the sequence is a {@linkplain #addGap(long) gap} and the subsequent
     *       {@linkplain EditedMediaItem media items} contain audio.
     * </ul>
     *
     * <p>If the flag is not set appropriately, then the export will {@linkplain
     * Transformer.Listener#onError(Composition, ExportResult, ExportException) fail}.
     *
     * <p>If the first {@link EditedMediaItem} already contains audio, this flag has no effect.
     *
     * <p>The MIME type of the output's audio track can be set using {@link
     * Transformer.Builder#setAudioMimeType(String)}. The sample rate and channel count can be set
     * by passing relevant {@link AudioProcessor} instances to the {@link Composition}.
     *
     * <p>Forcing an audio track and {@linkplain Composition.Builder#setTransmuxAudio(boolean)
     * requesting audio transmuxing} are not allowed together because generating silence requires
     * transcoding.
     *
     * <p>The default value is {@code false}.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     *
     * @param forceAudioTrack Whether to force audio track.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetForceAudioTrack(boolean forceAudioTrack) {
      this.forceAudioTrack = forceAudioTrack;
      return this;
    }

    /**
     * Forces blank frames in the {@linkplain EditedMediaItemSequence sequence}.
     *
     * <p>This flag is necessary when:
     *
     * <ul>
     *   <li>The first {@link EditedMediaItem} in the sequence does not contain video, but
     *       subsequent items do.
     *   <li>The first item in the sequence is a {@linkplain #addGap(long) gap} and the subsequent
     *       {@linkplain EditedMediaItem media items} contain video.
     * </ul>
     *
     * <p>If the flag is not set appropriately, then the export will {@linkplain
     * Transformer.Listener#onError(Composition, ExportResult, ExportException) fail}.
     *
     * <p>If the first {@link EditedMediaItem} already contains video, this flag has no effect.
     *
     * <p>The MIME type of the output's video track can be set using {@link
     * Transformer.Builder#setVideoMimeType(String)}.
     *
     * <p>The output resolution must be set using a {@link Presentation} effect on the {@link
     * Composition}.
     *
     * <p>Forcing a video track and {@linkplain Composition.Builder#setTransmuxVideo(boolean)
     * requesting video transmuxing} are not allowed together because generating blank frames
     * requires transcoding.
     *
     * <p>The default value is {@code false}.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     *
     * @param forceVideoTrack Whether to force video track.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetForceVideoTrack(boolean forceVideoTrack) {
      this.forceVideoTrack = forceVideoTrack;
      return this;
    }

    /**
     * Builds the {@link EditedMediaItemSequence}.
     *
     * <p>There must be at least one item in the sequence.
     *
     * @return The built {@link EditedMediaItemSequence}.
     */
    public EditedMediaItemSequence build() {
      return new EditedMediaItemSequence(this);
    }
  }

  /**
   * The {@link EditedMediaItem} instances in the sequence.
   *
   * <p>This list must not be empty.
   */
  public final ImmutableList<EditedMediaItem> editedMediaItems;

  /**
   * Whether this sequence is looping.
   *
   * <p>This value indicates whether to loop over the {@link EditedMediaItem} instances in this
   * sequence until all the non-looping sequences in the {@link Composition} have ended.
   *
   * <p>A looping sequence ends at the same time as the longest non-looping sequence. This means
   * that the last exported {@link EditedMediaItem} from a looping sequence can be only partially
   * exported.
   */
  public final boolean isLooping;

  /** Forces silent audio in the {@linkplain EditedMediaItemSequence sequence}. */
  public final boolean forceAudioTrack;

  /** Forces blank frames in the {@linkplain EditedMediaItemSequence sequence}. */
  public final boolean forceVideoTrack;

  /**
   * @deprecated Use {@link Builder}.
   */
  @Deprecated
  public EditedMediaItemSequence(
      EditedMediaItem editedMediaItem, EditedMediaItem... editedMediaItems) {
    this(new Builder().addItem(editedMediaItem).addItems(editedMediaItems));
  }

  /**
   * @deprecated Use {@link Builder}.
   */
  @Deprecated
  public EditedMediaItemSequence(List<EditedMediaItem> editedMediaItems) {
    this(new Builder().addItems(editedMediaItems));
  }

  /**
   * @deprecated Use {@link Builder}.
   */
  @Deprecated
  public EditedMediaItemSequence(List<EditedMediaItem> editedMediaItems, boolean isLooping) {
    this(new Builder().addItems(editedMediaItems).setIsLooping(isLooping));
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /**
   * Converts the given {@code index} into the equivalent {@code mediaItemIndex}.
   *
   * <p>The index could be greater than {@link EditedMediaItemSequence#editedMediaItems} because the
   * sequence might be {@linkplain EditedMediaItemSequence#isLooping looping}.
   */
  /* package */ static int getEditedMediaItemIndex(EditedMediaItemSequence sequence, int index) {
    if (sequence.isLooping) {
      return index % sequence.editedMediaItems.size();
    }
    return index;
  }

  /**
   * Gets the {@link EditedMediaItem} of a given {@code index}.
   *
   * <p>The index could be greater than {@link EditedMediaItemSequence#editedMediaItems} because the
   * sequence might be {@linkplain EditedMediaItemSequence#isLooping looping}.
   */
  /* package */ static EditedMediaItem getEditedMediaItem(
      EditedMediaItemSequence sequence, int index) {
    int mediaItemIndex = getEditedMediaItemIndex(sequence, index);
    return sequence.editedMediaItems.get(mediaItemIndex);
  }

  private EditedMediaItemSequence(EditedMediaItemSequence.Builder builder) {
    this.editedMediaItems = builder.items.build();
    checkArgument(
        !editedMediaItems.isEmpty(), "The sequence must contain at least one EditedMediaItem.");
    checkArgument(
        !editedMediaItems.get(0).isGap() || builder.forceAudioTrack || builder.forceVideoTrack,
        "If the first item in the sequence is a Gap, then forceAudioTrack or forceVideoTrack flag"
            + " must be set");
    this.isLooping = builder.isLooping;
    this.forceAudioTrack = builder.forceAudioTrack;
    this.forceVideoTrack = builder.forceVideoTrack;
  }

  /** Return whether any items are a {@linkplain Builder#addGap(long) gap}. */
  /* package */ boolean hasGaps() {
    for (int i = 0; i < editedMediaItems.size(); i++) {
      if (editedMediaItems.get(i).isGap()) {
        return true;
      }
    }
    return false;
  }
}
