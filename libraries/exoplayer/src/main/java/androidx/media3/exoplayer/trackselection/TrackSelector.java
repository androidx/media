/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.trackselection;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

/**
 * The component of an {@link ExoPlayer} responsible for selecting tracks to be consumed by each of
 * the player's {@link Renderer}s. The {@link DefaultTrackSelector} implementation should be
 * suitable for most use cases.
 *
 * <h2>Interactions with the player</h2>
 *
 * The following interactions occur between the player and its track selector during playback.
 *
 * <ul>
 *   <li>When the player is created it will initialize the track selector by calling {@link
 *       #init(InvalidationListener, BandwidthMeter)}.
 *   <li>When the player needs to make a track selection it will call {@link
 *       #selectTracks(RendererCapabilities[], TrackGroupArray, MediaPeriodId, Timeline)}. This
 *       typically occurs at the start of playback, when the player starts to buffer a new period of
 *       the media being played, and when the track selector invalidates its previous selections.
 *   <li>The player may perform a track selection well in advance of the selected tracks becoming
 *       active, where active is defined to mean that the renderers are actually consuming media
 *       corresponding to the selection that was made. For example when playing media containing
 *       multiple periods, the track selection for a period is made when the player starts to buffer
 *       that period. Hence if the player's buffering policy is to maintain a 30 second buffer, the
 *       selection will occur approximately 30 seconds in advance of it becoming active. In fact the
 *       selection may never become active, for example if the user seeks to some other period of
 *       the media during the 30 second gap. The player indicates to the track selector when a
 *       selection it has previously made becomes active by calling {@link
 *       #onSelectionActivated(Object)}.
 *   <li>When the track selector's configuration changes (for example, if it now wishes to prefer
 *       audio tracks in a particular language) or its previous selections become invalid, it can
 *       trigger invalidation by calling {@link #invalidate(TrackSelectionParameters)}. This invokes
 *       {@link InvalidationListener#onTrackSelectionsInvalidated(TrackSelectionParameters)} on the
 *       {@link InvalidationListener} that was passed to {@link #init(InvalidationListener,
 *       BandwidthMeter)}. When handling this callback, if {@code parameters} is not {@code null},
 *       the listener must call {@link #onParametersActivated(TrackSelectionParameters)} to activate
 *       the updated parameters before triggering the player to make new track selections. Note that
 *       the player will have to re-buffer if the new track selection for the currently playing
 *       period differs from the one that was invalidated.
 *   <li>When the player is {@linkplain Player#release() released}, it will release the track
 *       selector by calling {@link #release()}.
 * </ul>
 *
 * <h2>Track selection parameters</h2>
 *
 * <p>Track selection parameters can be retrieved and modified on the application thread by calling
 * {@link #getParameters()} and {@link #setParameters(TrackSelectionParameters)} respectively (if
 * supported, as indicated by {@link #isSetParametersSupported()}). Modifying parameters on the
 * application thread triggers the invalidation flow described above, ensuring that the updated
 * parameters are activated and applied on the playback thread.
 *
 * <h2>Renderer configuration</h2>
 *
 * The {@link TrackSelectorResult} returned by {@link #selectTracks(RendererCapabilities[],
 * TrackGroupArray, MediaPeriodId, Timeline)} contains not only {@link TrackSelection}s for each
 * renderer, but also {@link RendererConfiguration}s defining configuration parameters that the
 * renderers should apply when consuming the corresponding media. Whilst it may seem counter-
 * intuitive for a track selector to also specify renderer configuration information, in practice
 * the two are tightly bound together. It may only be possible to play a certain combination tracks
 * if the renderers are configured in a particular way. Equally, it may only be possible to
 * configure renderers in a particular way if certain tracks are selected. Hence it makes sense to
 * determine the track selection and corresponding renderer configurations in a single step.
 *
 * <h2>Threading model</h2>
 *
 * <p>All methods must be called on the same playback thread, except for those specifically
 * documented to be called on a separate application thread.
 */
@UnstableApi
public abstract class TrackSelector {

  /**
   * Factory for creating {@linkplain TrackSelector track selectors} from {@linkplain Context
   * contexts}.
   */
  public interface Factory {

    /**
     * Creates a new {@link TrackSelector} with the specified {@link Context}.
     *
     * @param context The context.
     * @return The new {@linkplain TrackSelector track selector}.
     */
    TrackSelector createTrackSelector(Context context);
  }

  /**
   * Notified when selections previously made by a {@link TrackSelector} are no longer valid.
   *
   * <p>The track selector may call methods of this listener from any thread.
   */
  public interface InvalidationListener {

    /**
     * Called by a {@link TrackSelector} to indicate that selections it has previously made are no
     * longer valid, or that track selection parameters have changed.
     *
     * <p>If {@code parameters} is not {@code null}, the receiver must call {@link
     * TrackSelector#onParametersActivated(TrackSelectionParameters)} to activate the new
     * parameters.
     *
     * @param parameters The new track selection parameters, or {@code null} if selections need to
     *     be re-evaluated without applying new parameters.
     */
    void onTrackSelectionsInvalidated(@Nullable TrackSelectionParameters parameters);

    /**
     * Called by a {@link TrackSelector} to indicate that selections it has previously made may no
     * longer be valid due to the renderer capabilities change.
     *
     * @param renderer The renderer whose capabilities changed.
     */
    default void onRendererCapabilitiesChanged(Renderer renderer) {}
  }

  @Nullable private InvalidationListener listener;
  @Nullable private BandwidthMeter bandwidthMeter;

  /**
   * Called by the player to initialize the selector.
   *
   * <p>This method must be called from the application thread.
   *
   * @param listener An invalidation listener that the selector can call to indicate that selections
   *     it has previously made are no longer valid.
   * @param bandwidthMeter A bandwidth meter which can be used by track selections to select tracks.
   */
  @CallSuper
  public void init(InvalidationListener listener, BandwidthMeter bandwidthMeter) {
    checkState(this.listener == null);
    this.listener = listener;
    this.bandwidthMeter = bandwidthMeter;
  }

  /**
   * Called by the player to release the selector. The selector cannot be used until {@link
   * #init(InvalidationListener, BandwidthMeter)} is called again.
   */
  @CallSuper
  public void release() {
    listener = null;
    bandwidthMeter = null;
  }

  /**
   * Called by the player to perform a track selection.
   *
   * @param rendererCapabilities The {@link RendererCapabilities} of the renderers for which tracks
   *     are to be selected.
   * @param trackGroups The available track groups.
   * @param periodId The {@link MediaPeriodId} of the period for which tracks are to be selected.
   * @param timeline The {@link Timeline} holding the period for which tracks are to be selected.
   * @return A {@link TrackSelectorResult} describing the track selections.
   * @throws ExoPlaybackException If an error occurs selecting tracks.
   */
  public abstract TrackSelectorResult selectTracks(
      RendererCapabilities[] rendererCapabilities,
      TrackGroupArray trackGroups,
      MediaPeriodId periodId,
      Timeline timeline)
      throws ExoPlaybackException;

  /**
   * Called by the player when a {@link TrackSelectorResult} previously generated by {@link
   * #selectTracks(RendererCapabilities[], TrackGroupArray, MediaPeriodId, Timeline)} is activated.
   *
   * @param info The value of {@link TrackSelectorResult#info} in the activated selection.
   */
  public abstract void onSelectionActivated(@Nullable Object info);

  /**
   * Called by the player when track selection parameters become active for subsequent track
   * selections made by {@link #selectTracks(RendererCapabilities[], TrackGroupArray, MediaPeriodId,
   * Timeline)}.
   *
   * @param parameters The activated track selection parameters, or {@code null} if no new
   *     parameters need to be activated.
   */
  public void onParametersActivated(@Nullable TrackSelectionParameters parameters) {
    // Default implementation is no-op.
  }

  /**
   * Returns the current parameters for track selection.
   *
   * <p>This method must be called from the application thread.
   */
  public TrackSelectionParameters getParameters() {
    return TrackSelectionParameters.DEFAULT;
  }

  /**
   * Called by the player to provide parameters for track selection.
   *
   * <p>Only supported if {@link #isSetParametersSupported()} returns true.
   *
   * <p>This method must be called from the application thread.
   *
   * @param parameters The parameters for track selection.
   */
  public void setParameters(TrackSelectionParameters parameters) {
    // Default implementation doesn't support this method.
  }

  /**
   * Returns if this {@code TrackSelector} supports {@link
   * #setParameters(TrackSelectionParameters)}.
   *
   * <p>The same value is always returned for a given {@code TrackSelector} instance.
   *
   * <p>This method must be called from the application thread.
   */
  public boolean isSetParametersSupported() {
    return false;
  }

  /** Called by the player to set the {@link AudioAttributes} that will be used for playback. */
  public void setAudioAttributes(AudioAttributes audioAttributes) {
    // Default implementation is no-op.
  }

  /**
   * Returns the {@link RendererCapabilities.Listener} that the concrete instance uses to listen to
   * the renderer capabilities changes. May be {@code null} if the implementation does not listen to
   * the renderer capabilities changes.
   */
  @Nullable
  public RendererCapabilities.Listener getRendererCapabilitiesListener() {
    return null;
  }

  /**
   * Calls {@link InvalidationListener#onTrackSelectionsInvalidated(TrackSelectionParameters)} to
   * invalidate all previously generated track selections.
   *
   * <p>This method can be called from both the application and the playback thread.
   *
   * @param parameters The track selection parameters, or {@code null} to re-evaluate selections
   *     without applying new parameters.
   */
  protected final void invalidate(@Nullable TrackSelectionParameters parameters) {
    if (listener != null) {
      listener.onTrackSelectionsInvalidated(parameters);
    }
  }

  /**
   * Calls {@link InvalidationListener#onRendererCapabilitiesChanged(Renderer)} to invalidate all
   * previously generated track selections because a renderer's capabilities have changed.
   *
   * @param renderer The renderer whose capabilities changed.
   */
  protected final void invalidateForRendererCapabilitiesChange(Renderer renderer) {
    if (listener != null) {
      listener.onRendererCapabilitiesChanged(renderer);
    }
  }

  /**
   * Returns a bandwidth meter which can be used by track selections to select tracks. Must only be
   * called when the track selector is {@linkplain #init(InvalidationListener, BandwidthMeter)
   * initialized}.
   */
  protected final BandwidthMeter getBandwidthMeter() {
    return checkNotNull(bandwidthMeter);
  }
}
