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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A sequence of {@link EditedMediaItem} instances.
 *
 * <p>{@linkplain EditedMediaItem} instances in a sequence don't overlap in time.
 */
@UnstableApi
public final class EditedMediaItemSequence {
  private static final ImmutableSet<@C.TrackType Integer> ALLOWED_TRACK_TYPES =
      ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO);

  /**
   * Creates an audio-only sequence from a list of {@link EditedMediaItem}s.
   *
   * <p>This is equivalent to using the {@link Builder} and setting the track types to only include
   * {@link C#TRACK_TYPE_AUDIO}. The sequence will only produce an audio output.
   *
   * @param editedMediaItems The list of {@link EditedMediaItem}s to add to the sequence.
   * @return A new audio-only {@link EditedMediaItemSequence}.
   */
  public static EditedMediaItemSequence withAudioFrom(List<EditedMediaItem> editedMediaItems) {
    return new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
        .addItems(editedMediaItems)
        .build();
  }

  /**
   * Creates an video-only sequence from a list of {@link EditedMediaItem}s.
   *
   * <p>This is equivalent to using the {@link Builder} and setting the track types to only include
   * {@link C#TRACK_TYPE_VIDEO}. The sequence will only produce a video output.
   *
   * @param editedMediaItems The list of {@link EditedMediaItem}s to add to the sequence.
   * @return A new video-only {@link EditedMediaItemSequence}.
   */
  public static EditedMediaItemSequence withVideoFrom(List<EditedMediaItem> editedMediaItems) {
    return new EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_VIDEO))
        .addItems(editedMediaItems)
        .build();
  }

  /**
   * Creates a sequence with both audio and video from a list of {@link EditedMediaItem}s.
   *
   * <p>This is equivalent to using the {@link Builder} and setting the track types to include
   * {@link C#TRACK_TYPE_AUDIO} and {@link C#TRACK_TYPE_VIDEO}. The sequence will produce both audio
   * and video output.
   *
   * @param editedMediaItems The list of {@link EditedMediaItem}s to add to the sequence.
   * @return A new audio and video {@link EditedMediaItemSequence}.
   */
  public static EditedMediaItemSequence withAudioAndVideoFrom(
      List<EditedMediaItem> editedMediaItems) {
    return new EditedMediaItemSequence.Builder(
            ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
        .addItems(editedMediaItems)
        .build();
  }

  /** A builder for instances of {@link EditedMediaItemSequence}. */
  public static final class Builder {
    private final ImmutableList.Builder<EditedMediaItem> items;
    private ImmutableSet<@C.TrackType Integer> trackTypes;
    private boolean isLooping;

    /**
     * Creates an instance.
     *
     * @param trackTypes The non-empty set of track types enabled for this sequence. Must only
     *     contain {@link C#TRACK_TYPE_AUDIO} and/or {@link C#TRACK_TYPE_VIDEO}. This determines
     *     which tracks will be included in this sequence's output. For example, passing a set
     *     containing only {@link C#TRACK_TYPE_AUDIO} will result in an audio-only sequence.
     */
    public Builder(Set<@C.TrackType Integer> trackTypes) {
      checkState(!trackTypes.isEmpty());
      checkState(
          ALLOWED_TRACK_TYPES.containsAll(trackTypes),
          "trackTypes must only contain TRACK_TYPE_AUDIO and/or TRACK_TYPE_VIDEO.");
      this.trackTypes = ImmutableSet.copyOf(trackTypes);
      this.items = new ImmutableList.Builder<>();
    }

    /**
     * @deprecated Use {@link Builder#Builder(Set)} to create the builder, and {@link
     *     #addItems(List)} to add the {@link EditedMediaItem}s, or use the static factory methods
     *     like {@link #withAudioFrom(List)}, {@link #withVideoFrom(List)}, or {@link
     *     #withAudioAndVideoFrom(List)}.
     */
    // TODO: b/445884217 - Remove deprecated builders.
    @Deprecated
    public Builder(EditedMediaItem... editedMediaItems) {
      this.trackTypes = ImmutableSet.of(C.TRACK_TYPE_NONE);
      this.items = new ImmutableList.Builder<EditedMediaItem>().add(editedMediaItems);
    }

    /**
     * @deprecated Use {@link Builder#Builder(Set)} to create the builder, and {@link
     *     #addItems(List)} to add the {@link EditedMediaItem}s, or use the static factory methods
     *     like {@link #withAudioFrom(List)}, {@link #withVideoFrom(List)}, or {@link
     *     #withAudioAndVideoFrom(List)}.
     */
    // TODO: b/445884217 - Remove deprecated builders.
    @Deprecated
    public Builder(List<EditedMediaItem> editedMediaItems) {
      this.trackTypes = ImmutableSet.of(C.TRACK_TYPE_NONE);
      this.items = new ImmutableList.Builder<EditedMediaItem>().addAll(editedMediaItems);
    }

    /** Creates a new instance to build upon the provided {@link EditedMediaItemSequence}. */
    private Builder(EditedMediaItemSequence editedMediaItemSequence) {
      items =
          new ImmutableList.Builder<EditedMediaItem>()
              .addAll(editedMediaItemSequence.editedMediaItems);
      isLooping = editedMediaItemSequence.isLooping;
      trackTypes = editedMediaItemSequence.trackTypes;
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
     * <p>The gap's tracks match the {@link EditedMediaItemSequence#trackTypes}.
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
     * @deprecated Use {@link #Builder(Set)} to set sequence track types instead.
     */
    // TODO: b/445884217 - Remove deprecated methods.
    @Deprecated
    @CanIgnoreReturnValue
    public Builder experimentalSetForceAudioTrack(boolean forceAudioTrack) {
      checkState(trackTypes.contains(C.TRACK_TYPE_NONE));
      if (forceAudioTrack) {
        trackTypes =
            new ImmutableSet.Builder<Integer>().addAll(trackTypes).add(C.TRACK_TYPE_AUDIO).build();
      } else {
        trackTypes =
            Sets.difference(trackTypes, ImmutableSet.of(C.TRACK_TYPE_AUDIO)).immutableCopy();
      }
      return this;
    }

    /**
     * @deprecated Use {@link #Builder(Set)} to set sequence track types instead.
     */
    // TODO: b/445884217 - Remove deprecated methods.
    @Deprecated
    @CanIgnoreReturnValue
    public Builder experimentalSetForceVideoTrack(boolean forceVideoTrack) {
      checkState(trackTypes.contains(C.TRACK_TYPE_NONE));
      if (forceVideoTrack) {
        trackTypes =
            new ImmutableSet.Builder<Integer>().addAll(trackTypes).add(C.TRACK_TYPE_VIDEO).build();
      } else {
        trackTypes =
            Sets.difference(trackTypes, ImmutableSet.of(C.TRACK_TYPE_VIDEO)).immutableCopy();
      }
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
   * The track types enabled for this sequence.
   *
   * <p>This set, containing {@link C#TRACK_TYPE_AUDIO} and/or {@link C#TRACK_TYPE_VIDEO},
   * determines which tracks will be included in the sequence's output. For example, a set
   * containing only {@link C#TRACK_TYPE_AUDIO} will result in an audio-only output.
   */
  public final ImmutableSet<@C.TrackType Integer> trackTypes;

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

  // TODO: b/445884217 - Remove deprecated field.
  /**
   * @deprecated Use {@code trackTypes.contains(C.TRACK_TYPE_AUDIO)} instead.
   */
  @Deprecated public final boolean forceAudioTrack;

  // TODO: b/445884217 - Remove deprecated field.
  /**
   * @deprecated Use {@code trackTypes.contains(C.TRACK_TYPE_VIDEO)} instead.
   */
  @Deprecated public final boolean forceVideoTrack;

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

  /** Returns a {@link JSONObject} that represents the {@code EditedMediaItemSequence}. */
  /* package */ JSONObject toJsonObject() {
    JSONObject jsonObject = new JSONObject();
    try {
      JSONArray editedMediaItemsJsonArray = new JSONArray();
      for (int i = 0; i < editedMediaItems.size(); i++) {
        editedMediaItemsJsonArray.put(editedMediaItems.get(i).toJsonObject());
      }
      jsonObject.put("mediaItems", editedMediaItemsJsonArray);
      jsonObject.put("trackTypes", new JSONArray(trackTypes));
      jsonObject.put("isLooping", isLooping);
      return jsonObject;
    } catch (JSONException e) {
      Log.w(/* tag= */ "EditedSequence", "JSON conversion failed.", e);
      return new JSONObject();
    }
  }

  @Override
  public String toString() {
    return toJsonObject().toString();
  }

  private EditedMediaItemSequence(EditedMediaItemSequence.Builder builder) {
    this.editedMediaItems = builder.items.build();
    checkArgument(
        !editedMediaItems.isEmpty(), "The sequence must contain at least one EditedMediaItem.");

    ImmutableSet<@C.TrackType Integer> trackTypes = builder.trackTypes;
    // TODO: b/445884217 - Remove TRACK_TYPE_NONE logic.
    if (trackTypes.contains(C.TRACK_TYPE_NONE)) {
      // When using the deprecated builder, we still need to check that the flags are set when
      // sequence starts with a gap.
      checkArgument(
          !editedMediaItems.get(0).isGap()
              || trackTypes.contains(C.TRACK_TYPE_AUDIO)
              || trackTypes.contains(C.TRACK_TYPE_VIDEO),
          "If the first item in the sequence is a Gap, then forceAudioTrack or forceVideoTrack flag"
              + " must be set");
    }
    this.trackTypes = trackTypes;
    this.isLooping = builder.isLooping;
    this.forceAudioTrack = trackTypes.contains(C.TRACK_TYPE_AUDIO);
    this.forceVideoTrack = trackTypes.contains(C.TRACK_TYPE_VIDEO);
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

  /**
   * Returns a copy of this sequence replacing its {@link EditedMediaItem} list with {@code items}.
   */
  /* package */ EditedMediaItemSequence copyWithEditedMediaItems(List<EditedMediaItem> items) {
    checkArgument(!items.isEmpty());
    // TODO: b/445884217 - Remove TRACK_TYPE_NONE logic
    if (this.trackTypes.contains(C.TRACK_TYPE_NONE)) {
      return new EditedMediaItemSequence.Builder(items)
          .setIsLooping(this.isLooping)
          .experimentalSetForceAudioTrack(this.forceAudioTrack)
          .experimentalSetForceVideoTrack(this.forceVideoTrack)
          .build();
    } else {
      return new EditedMediaItemSequence.Builder(this.trackTypes)
          .addItems(items)
          .setIsLooping(this.isLooping)
          .build();
    }
  }
}
