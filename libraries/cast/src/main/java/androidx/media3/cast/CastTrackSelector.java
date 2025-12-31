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
package androidx.media3.cast;

import static androidx.core.util.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import com.google.android.gms.cast.MediaTrack;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Handles track selections and parameter changes for {@link RemoteCastPlayer}.
 *
 * <p>See {@link #evaluate} for the most important uses of this class.
 *
 * <p>An instance of this class must not be reused across different {@link RemoteCastPlayer}
 * instances. A new instance must be created for each player.
 */
@UnstableApi
public abstract class CastTrackSelector {

  /** Holds parameters associated with a {@link #evaluate evaluation request}. */
  public static final class CastTrackSelectorRequest {

    /** The id of the active Cast media item when the evaluation is made. */
    public final int mediaItemId;

    /** The currently active track selection parameters. */
    public final TrackSelectionParameters trackSelectionParameters;

    /**
     * The list of available {@link TrackGroup track groups}, where positions match the track
     * group's corresponding {@link #mediaTracks Cast media tracks}.
     */
    public final ImmutableList<TrackGroup> trackGroupList;

    /** The Cast media tracks, where positions match the corresponding {@link #trackGroupList}. */
    public final ImmutableList<MediaTrack> mediaTracks;

    /** The currently selected track groups, as reported by Cast remote media client. */
    public final ImmutableSet<TrackGroup> currentlySelectedTrackGroups;

    /**
     * The reason for this track selection evaluation request.
     *
     * <p>Can be used to determine whether to change the {@link CastTrackSelectorResult#selections}
     * (for example, in response to new {@link TrackSelectionParameters}), or the {@link
     * CastTrackSelectorResult#trackSelectionParameters} (for example, in response to a
     * receiver-side track selection change).
     */
    public final @TrackSelectionRequestReason int trackSelectionRequestReason;

    /**
     * Creates a new instance.
     *
     * @param mediaItemId See {@link #mediaItemId}.
     * @param trackSelectionParameters See {@link #trackSelectionParameters}.
     * @param trackGroupList See {@link #trackGroupList}.
     * @param mediaTracks See {@link #mediaTracks}.
     * @param currentlySelectedTrackGroups See {@link #currentlySelectedTrackGroups}.
     * @param trackSelectionRequestReason See {@link #trackSelectionRequestReason}.
     */
    public CastTrackSelectorRequest(
        int mediaItemId,
        TrackSelectionParameters trackSelectionParameters,
        ImmutableList<TrackGroup> trackGroupList,
        ImmutableList<MediaTrack> mediaTracks,
        ImmutableSet<TrackGroup> currentlySelectedTrackGroups,
        @TrackSelectionRequestReason int trackSelectionRequestReason) {
      this.mediaItemId = mediaItemId;
      this.trackSelectionParameters = trackSelectionParameters;
      this.trackGroupList = trackGroupList;
      this.mediaTracks = mediaTracks;
      this.currentlySelectedTrackGroups = currentlySelectedTrackGroups;
      this.trackSelectionRequestReason = trackSelectionRequestReason;
    }

    /**
     * Returns a {@link CastTrackSelectorResult.Builder} that's already initialized with the values
     * from this {@link CastTrackSelectorRequest}, so that clients can easily limit changes to
     * fields they want to modify.
     */
    public CastTrackSelectorResult.Builder buildResultUpon() {
      return new CastTrackSelectorResult.Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CastTrackSelectorRequest that = (CastTrackSelectorRequest) o;
      return mediaItemId == that.mediaItemId
          && trackSelectionRequestReason == that.trackSelectionRequestReason
          && Objects.equals(trackSelectionParameters, that.trackSelectionParameters)
          && Objects.equals(trackGroupList, that.trackGroupList)
          && Objects.equals(mediaTracks, that.mediaTracks)
          && Objects.equals(currentlySelectedTrackGroups, that.currentlySelectedTrackGroups);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          mediaItemId,
          trackSelectionParameters,
          trackGroupList,
          mediaTracks,
          currentlySelectedTrackGroups,
          trackSelectionRequestReason);
    }
  }

  /** The result of a {@link #evaluate track selection}. */
  public static final class CastTrackSelectorResult {

    /** The track groups that must become selected. */
    public final ImmutableSet<TrackGroup> selections;

    /**
     * The new {@link TrackSelectionParameters} to set to {@link RemoteCastPlayer}.
     *
     * <p>The new parameters can be retrieved through {@link
     * RemoteCastPlayer#getTrackSelectionParameters()}, and a change will trigger a {@link
     * Player.Listener#onTrackSelectionParametersChanged} call.
     */
    public final TrackSelectionParameters trackSelectionParameters;

    /* package */ CastTrackSelectorResult(
        ImmutableSet<TrackGroup> selections, TrackSelectionParameters trackSelectionParameters) {
      this.selections = selections;
      this.trackSelectionParameters = trackSelectionParameters;
    }

    /** A builder for {@link CastTrackSelectorResult}. */
    public static final class Builder {

      private @MonotonicNonNull ImmutableSet<TrackGroup> selections;
      private @MonotonicNonNull TrackSelectionParameters trackSelectionParameters;

      /**
       * Creates a builder.
       *
       * <p>Setters must be invoked before calling {@link #build()}.
       *
       * <p>Consider using {@link CastTrackSelectorRequest#buildResultUpon} instead, which is less
       * susceptible to API changes.
       */
      public Builder() {
        selections = null;
        trackSelectionParameters = null;
      }

      /**
       * Creates a builder that's initialized with the values from the given {@link
       * CastTrackSelectorRequest}.
       *
       * @param request The request to initialize the builder with.
       */
      /* package */ Builder(CastTrackSelectorRequest request) {
        selections = request.currentlySelectedTrackGroups;
        trackSelectionParameters = request.trackSelectionParameters;
      }

      /**
       * Sets the track groups that must become selected.
       *
       * @param selections The track groups to select.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setSelections(ImmutableSet<TrackGroup> selections) {
        this.selections = checkNotNull(selections);
        return this;
      }

      /**
       * Sets the new {@link TrackSelectionParameters} to set to {@link RemoteCastPlayer}.
       *
       * @param trackSelectionParameters The new track selection parameters.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setTrackSelectionParameters(
          TrackSelectionParameters trackSelectionParameters) {
        this.trackSelectionParameters = checkNotNull(trackSelectionParameters);
        return this;
      }

      /** Builds the {@link CastTrackSelectorResult}. */
      public CastTrackSelectorResult build() {
        return new CastTrackSelectorResult(
            Preconditions.checkNotNull(selections),
            Preconditions.checkNotNull(trackSelectionParameters));
      }
    }
  }

  /**
   * The reason for track selection request. One of {@link
   * #TRACK_SELECTION_REQUEST_REASON_INVALIDATION}, {@link
   * #TRACK_SELECTION_REQUEST_REASON_RECEIVER_UPDATE}, {@link
   * #TRACK_SELECTION_REQUEST_REASON_PARAMETER_CHANGE}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(
      open = true,
      value = {
        TRACK_SELECTION_REQUEST_REASON_INVALIDATION,
        TRACK_SELECTION_REQUEST_REASON_RECEIVER_UPDATE,
        TRACK_SELECTION_REQUEST_REASON_PARAMETER_CHANGE
      })
  public @interface TrackSelectionRequestReason {}

  /** The selection request is the result of a call to {@link #invalidate()}. */
  public static final int TRACK_SELECTION_REQUEST_REASON_INVALIDATION = 0;

  /** The selection request is the result of an update from the Cast receiver app. */
  public static final int TRACK_SELECTION_REQUEST_REASON_RECEIVER_UPDATE = 1;

  /**
   * The selection request is the result of a call to {@link
   * RemoteCastPlayer#setTrackSelectionParameters}.
   */
  public static final int TRACK_SELECTION_REQUEST_REASON_PARAMETER_CHANGE = 2;

  /**
   * Special listener instance that means that {@link #init} has not yet been called on this
   * instance.
   */
  private static final InvalidationListener INVALIDATION_LISTENER_UNINITIALIZED = () -> {};

  private InvalidationListener invalidationListener;

  /** Constructor. */
  protected CastTrackSelector() {
    invalidationListener = INVALIDATION_LISTENER_UNINITIALIZED;
  }

  /**
   * Called by {@link RemoteCastPlayer} to optionally change the current track selections and/or
   * parameters.
   *
   * <p>You can implement this method to change the currently active tracks through {@link
   * CastTrackSelectorResult#selections}. For example, in response to a change in the current track
   * selection parameters, available through {@link
   * CastTrackSelectorRequest#trackSelectionParameters}.
   *
   * <p>Alternatively, you can also change the {@link RemoteCastPlayer#getTrackSelectionParameters()
   * current track selection parameters} in response to a receiver app track selection change.
   *
   * <p>You can trigger a call to this method by calling {@link #invalidate()}, or by changing the
   * {@link RemoteCastPlayer#setTrackSelectionParameters track selection parameters}.
   *
   * @param request The {@link CastTrackSelectorRequest} that contains the current state to evaluate
   *     in order to provide a {@link CastTrackSelectorResult} with updated track selections or
   *     parameters.
   * @return A {@link CastTrackSelectorResult} to update the state of {@link RemoteCastPlayer} and
   *     the receiver Cast application.
   */
  public abstract CastTrackSelectorResult evaluate(CastTrackSelectorRequest request);

  /**
   * Triggers a new call to {@link #evaluate}, so as to change the selected tracks.
   *
   * <p>Must be called on the main thread.
   */
  public final void invalidate() {
    invalidationListener.onTrackSelectionsInvalidated();
  }

  /**
   * Initializes the track selector with an {@link InvalidationListener}.
   *
   * <p>This method is intended to be called by {@link RemoteCastPlayer}.
   *
   * @param invalidationListener The listener for invalidations.
   * @throws IllegalStateException If this method is called twice on the same instance.
   */
  /* package */ final void init(InvalidationListener invalidationListener) {
    Preconditions.checkState(
        this.invalidationListener == INVALIDATION_LISTENER_UNINITIALIZED,
        "CastTrackSelector must not be reused by different RemoteCastPlayers.");
    this.invalidationListener = invalidationListener;
  }

  /** Listener for track selection invalidation, which triggers a call to {@link #evaluate}. */
  /* package */ interface InvalidationListener {

    /** Called by the track selector to trigger a new track selection. */
    void onTrackSelectionsInvalidated();
  }
}
