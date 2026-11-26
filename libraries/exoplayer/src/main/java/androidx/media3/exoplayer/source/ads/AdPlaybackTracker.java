/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source.ads;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.Looper;
import androidx.annotation.RestrictTo;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Monitors {@link Player} ad playback transitions, coordinates {@link AdPlaybackState} state
 * updates across active {@link AdsMediaSource} sessions, and notifies an optional {@link
 * SdkBridge}.
 *
 * <p>All public methods must be called on the tracker's {@link Looper} thread, which must match the
 * attached {@link Player#getApplicationLooper()}.
 *
 * <p>When the tracker is no longer needed (for example, from {@link AdsLoader#release()}), call
 * {@link #release()} to stop any active sessions, detach the listener from the attached {@link
 * Player}, and clear registered listeners. A released tracker cannot be reused.
 */
@RestrictTo(LIBRARY_GROUP)
public final class AdPlaybackTracker {

  private static final String TAG = "AdPlaybackTracker";

  /**
   * Listener for {@link AdPlaybackState} changes across active sessions.
   *
   * <p>All methods are called on the tracker's {@link Looper} thread, which is also the attached
   * {@link Player#getApplicationLooper()}.
   */
  public interface AdPlaybackStateListener {

    /**
     * Called when the {@link AdPlaybackState} for {@code adsId} has changed.
     *
     * @param adsId The session identifier.
     * @param adPlaybackState The updated {@link AdPlaybackState}. Listeners must not modify this
     *     instance.
     */
    void onAdPlaybackStateChanged(Object adsId, AdPlaybackState adPlaybackState);
  }

  /**
   * Bridge interface for external ad verification and management SDKs (such as the IMA SDK).
   *
   * <p>All methods are called synchronously on the tracker's {@link Looper} thread, which is also
   * the attached {@link Player#getApplicationLooper()}. Implementations must not block. Calling
   * back into {@link AdPlaybackTracker} from a callback is allowed, but re-enters the tracker
   * synchronously.
   *
   * <p>Any {@link AdPlaybackState} passed to a callback is a {@linkplain AdPlaybackState#copy()
   * copy} of the tracker's state; modifying its array fields has no effect on the tracker.
   */
  public interface SdkBridge {

    /**
     * Called when the active {@link Player} instance changes.
     *
     * @param player The active {@link Player}, or {@code null} if detached.
     */
    default void onPlayerChanged(@Nullable Player player) {}

    /**
     * Called when an {@link AdsMediaSource} session starts for the given {@code adsId}.
     *
     * @param adsId The session identifier.
     * @param contentMediaItem The content {@link MediaItem} for the session.
     * @param adViewProvider The {@link AdViewProvider} for the session.
     */
    default void onSessionStarted(
        Object adsId, MediaItem contentMediaItem, AdViewProvider adViewProvider) {}

    /**
     * Called when an ad period completes preparation in {@link AdsMediaSource}.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdPrepareCompleted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when playback enters the ad group at {@code adGroupIndex}.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     */
    default void onAdGroupStarted(Object adsId, int adGroupIndex) {}

    /**
     * Called when playback starts an individual ad at {@code (adGroupIndex, adIndexInAdGroup)}
     * within an ad group.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdStarted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when playback pauses while playing the specified ad.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdPaused(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when playback resumes after being paused while playing the specified ad.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdResumed(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when the player enters {@link Player#STATE_BUFFERING} while playing an ad.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdBuffering(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when timed {@link Metadata} is emitted while playing the specified ad.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     * @param metadata The timed {@link Metadata}.
     */
    default void onAdMetadata(
        Object adsId, int adGroupIndex, int adIndexInAdGroup, Metadata metadata) {}

    /**
     * Called when the specified ad finishes playing to completion.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdCompleted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when the specified ad is skipped.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     */
    default void onAdSkipped(Object adsId, int adGroupIndex, int adIndexInAdGroup) {}

    /**
     * Called when the specified ad encounters a preparation or playback error.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     * @param adIndexInAdGroup The ad index in the ad group.
     * @param error The preparation or playback error.
     */
    default void onAdError(Object adsId, int adGroupIndex, int adIndexInAdGroup, Exception error) {}

    /**
     * Called when playback exits the ad group at {@code adGroupIndex} back to content.
     *
     * @param adsId The session identifier.
     * @param adGroupIndex The ad group index.
     */
    default void onAdGroupEnded(Object adsId, int adGroupIndex) {}

    /**
     * Called when all scheduled ads for {@code adsId} have completed, skipped, or errored.
     *
     * <p>When {@code adPlaybackState} {@linkplain AdPlaybackState#endsWithLivePostrollPlaceHolder()
     * ends with a live postroll placeholder}, this callback is withheld while the content is
     * dynamic (meaning new ad groups may still arrive) and fires once the stream becomes static
     * (and no ad group is active).
     *
     * @param adsId The session identifier.
     * @param adPlaybackState The current {@link AdPlaybackState}.
     */
    default void onAllAdsCompleted(Object adsId, AdPlaybackState adPlaybackState) {}

    /**
     * Called when the primary content stream for {@code adsId} finishes playback.
     *
     * @param adsId The session identifier.
     */
    default void onContentCompleted(Object adsId) {}

    /**
     * Called when the player volume changes for an active session.
     *
     * @param adsId The session identifier.
     * @param volumePercent The updated player volume as a percentage in {@code [0, 100]}.
     */
    default void onVolumeChanged(Object adsId, int volumePercent) {}

    /**
     * Called when the {@link AdsMediaSource} session for {@code adsId} is stopped.
     *
     * @param adsId The session identifier.
     * @param finalAdPlaybackState The final {@link AdPlaybackState} of the session.
     */
    default void onSessionStopped(Object adsId, AdPlaybackState finalAdPlaybackState) {}
  }

  private final @Nullable SdkBridge sdkBridge;
  private final Looper looper;
  private final Map<Object, AdSession> sessionsByAdsId;
  private final CopyOnWriteArrayList<AdPlaybackStateListener> listeners;
  private final PlayerListener playerListener;
  private final AdSession.Callback sessionCallback;

  private @Nullable Player player;
  private boolean isReleased;

  /**
   * Creates an instance using the current thread's {@link Looper} for state confinement and {@link
   * SdkBridge} notifications.
   *
   * @param sdkBridge The optional {@link SdkBridge} to notify of ad lifecycle and playback events.
   * @throws NullPointerException If the current thread does not have a {@link Looper}.
   */
  public AdPlaybackTracker(@Nullable SdkBridge sdkBridge) {
    this(
        sdkBridge,
        checkNotNull(
            Looper.myLooper(), "AdPlaybackTracker must be created on a thread with a Looper."));
  }

  /**
   * Creates an instance using the specified {@link Looper} for state confinement and {@link
   * SdkBridge} notifications.
   *
   * @param sdkBridge The optional {@link SdkBridge} to notify of ad lifecycle and playback events.
   * @param looper The {@link Looper} on which all tracker methods, state mutations, and {@link
   *     SdkBridge} callbacks must be executed.
   */
  public AdPlaybackTracker(@Nullable SdkBridge sdkBridge, Looper looper) {
    this.sdkBridge = sdkBridge;
    this.looper = checkNotNull(looper);
    this.sessionsByAdsId = new LinkedHashMap<>();
    this.listeners = new CopyOnWriteArrayList<>();
    this.playerListener = new PlayerListener();
    this.sessionCallback = new SessionCallback();
  }

  /**
   * Adds an {@link AdPlaybackStateListener} to be notified of {@link AdPlaybackState} changes.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param listener The listener to add.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public void addListener(AdPlaybackStateListener listener) {
    verifyLooperThread();
    verifyNotReleased();
    listeners.addIfAbsent(checkNotNull(listener));
  }

  /**
   * Removes an {@link AdPlaybackStateListener}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param listener The listener to remove.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public void removeListener(AdPlaybackStateListener listener) {
    verifyLooperThread();
    verifyNotReleased();
    listeners.remove(checkNotNull(listener));
  }

  /**
   * Sets the active {@link Player} and registers or unregisters the internal listener.
   *
   * <p>Setting a new player or {@code null} ends any active ad group on ongoing {@link
   * AdsMediaSource} sessions.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param player The {@link Player} to observe, or {@code null} to detach. If non-null, its {@link
   *     Player#getApplicationLooper()} must match this tracker's {@link Looper}.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   * @throws IllegalArgumentException If {@code player} is non-null and its {@link
   *     Player#getApplicationLooper()} does not match this tracker's {@link Looper}.
   */
  public void setPlayer(@Nullable Player player) {
    verifyLooperThread();
    verifyNotReleased();
    setPlayerInternal(player);
  }

  /**
   * Returns the active {@link Player}, or {@code null} if none is set.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public @Nullable Player getPlayer() {
    verifyLooperThread();
    verifyNotReleased();
    return player;
  }

  /**
   * Returns whether an {@link AdsMediaSource} session is currently active for the given {@code
   * adsId}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public boolean isSessionActive(Object adsId) {
    verifyLooperThread();
    verifyNotReleased();
    return sessionsByAdsId.containsKey(adsId);
  }

  /**
   * Returns an {@link ImmutableSet} of {@code adsId}s for all currently active {@link
   * AdsMediaSource} sessions, in the order the sessions were started.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public ImmutableSet<Object> getActiveSessionAdsIds() {
    verifyLooperThread();
    verifyNotReleased();
    return ImmutableSet.copyOf(sessionsByAdsId.keySet());
  }

  /**
   * Returns the current {@link AdPlaybackState} for {@code adsId}, or {@code null} if no session is
   * active for {@code adsId}.
   *
   * <p>The returned instance is a {@linkplain AdPlaybackState#copy() copy} of the tracker's state.
   * Modifying its array fields has no effect on the tracker.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  public @Nullable AdPlaybackState getAdPlaybackState(Object adsId) {
    verifyLooperThread();
    verifyNotReleased();
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    return session != null ? session.getAdPlaybackState().copy() : null;
  }

  /**
   * Applies {@code stateMutator} to the {@link AdPlaybackState} for {@code adsId} and publishes the
   * updated state to the session's {@link AdsLoader.EventListener}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param stateMutator The mutation to apply to the current {@link AdPlaybackState}. The state
   *     passed to the mutator is a {@linkplain AdPlaybackState#copy() copy} of the tracker's state,
   *     and the returned state is copied before it is stored, so neither the input nor retained
   *     references to the output can be used to modify the tracker's state. The returned state must
   *     have the same {@link AdPlaybackState#adsId} as the input state and must be a valid update
   *     from the current state.
   * @return {@code true} if an active session existed for {@code adsId}, or {@code false} if no
   *     session was found.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread, after {@link #release()}, if {@code stateMutator} returns a state with a different
   *     {@link AdPlaybackState#adsId}, or if {@code stateMutator} returns an invalid {@link
   *     AdPlaybackState} update (such as removing ad groups, changing existing ad group times, or
   *     replacing an already available ad's {@link MediaItem}).
   */
  @CanIgnoreReturnValue
  public boolean updateAdPlaybackState(Object adsId, UnaryOperator<AdPlaybackState> stateMutator) {
    verifyLooperThread();
    verifyNotReleased();
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    if (session == null) {
      return false;
    }
    AdPlaybackState currentState = session.getAdPlaybackState();
    session.setAdPlaybackState(stateMutator.apply(currentState.copy()));
    return true;
  }

  /**
   * Resets ads in all non-removed ad groups for {@code adsId} from final states ({@link
   * AdPlaybackState#AD_STATE_PLAYED}, {@link AdPlaybackState#AD_STATE_SKIPPED}, and optionally
   * {@link AdPlaybackState#AD_STATE_ERROR}) back to {@link AdPlaybackState#AD_STATE_AVAILABLE} or
   * {@link AdPlaybackState#AD_STATE_UNAVAILABLE}, resets the ad resume position to {@code 0}, and
   * publishes the updated {@link AdPlaybackState}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param resetErrorAds Whether ads in {@link AdPlaybackState#AD_STATE_ERROR} should also be reset
   *     to available or unavailable. If {@code false}, errored ads remain in {@link
   *     AdPlaybackState#AD_STATE_ERROR}.
   * @return {@code true} if an active session existed for {@code adsId}, or {@code false} if no
   *     session was found.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  @CanIgnoreReturnValue
  public boolean resetAllAds(Object adsId, boolean resetErrorAds) {
    verifyLooperThread();
    verifyNotReleased();
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    if (session == null) {
      return false;
    }
    session.resetAllAds(resetErrorAds);
    return true;
  }

  /**
   * Marks the specified ad as {@link AdPlaybackState#AD_STATE_SKIPPED} (if currently unavailable or
   * available) and publishes the updated {@link AdPlaybackState}.
   *
   * <p>If no active session exists for {@code adsId}, if {@code adGroupIndex} or {@code
   * adIndexInAdGroup} is negative or out of bounds (including removed ad groups and live postroll
   * placeholders), or if the ad is already played, skipped, or in an error state, this method is a
   * no-op and returns {@code false}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param adGroupIndex The ad group index.
   * @param adIndexInAdGroup The ad index in the ad group.
   * @return {@code true} if the ad was marked as skipped and the {@link AdPlaybackState} was
   *     updated, or {@code false} otherwise.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  @CanIgnoreReturnValue
  public boolean skipAd(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
    verifyLooperThread();
    verifyNotReleased();
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    return session != null && session.skipAd(adGroupIndex, adIndexInAdGroup);
  }

  /**
   * Marks all unplayed ads in {@code adGroupIndex} as {@link AdPlaybackState#AD_STATE_SKIPPED}
   * (preserving already played and errored ads) and publishes the updated {@link AdPlaybackState}.
   *
   * <p>If no active session exists for {@code adsId}, if {@code adGroupIndex} is negative or out of
   * bounds (including removed ad groups and live postroll placeholders), or if the ad group has no
   * unplayed ads to skip, this method is a no-op and returns {@code false}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param adGroupIndex The ad group index.
   * @return {@code true} if the {@link AdPlaybackState} was updated, or {@code false} otherwise.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread or after {@link #release()}.
   */
  @CanIgnoreReturnValue
  public boolean skipAdGroup(Object adsId, int adGroupIndex) {
    verifyLooperThread();
    verifyNotReleased();
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    return session != null && session.skipAdGroup(adGroupIndex);
  }

  /**
   * Registers an active {@link AdsMediaSource} session for {@code initialAdPlaybackState.adsId},
   * publishes {@code initialAdPlaybackState} to {@code eventListener}, and notifies {@link
   * SdkBridge#onSessionStarted}.
   *
   * <p>The {@code contentMediaItem} must have a non-null {@link MediaItem.AdsConfiguration} with an
   * {@link MediaItem.AdsConfiguration#adsId} equal to {@code initialAdPlaybackState.adsId}.
   *
   * <p>If this tracker has already been {@linkplain #release() released} or has no attached {@link
   * Player}, this method publishes an empty {@link AdPlaybackState} for {@code
   * initialAdPlaybackState.adsId} to {@code eventListener} (so that playback of the content can
   * proceed without ads) and returns without registering a session or notifying {@link SdkBridge}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread.
   * @throws IllegalArgumentException If the {@link MediaItem.AdsConfiguration#adsId} of {@code
   *     contentMediaItem} does not equal {@code initialAdPlaybackState.adsId}, or if a session for
   *     {@code initialAdPlaybackState.adsId} is already active.
   */
  public void startSession(
      MediaItem contentMediaItem,
      AdViewProvider adViewProvider,
      AdsLoader.EventListener eventListener,
      AdPlaybackState initialAdPlaybackState) {
    verifyLooperThread();
    Object adsId = checkNotNull(initialAdPlaybackState.adsId);
    if (isReleased || player == null) {
      // Run without ads after release or when no player is attached so that a still-active
      // AdsMediaSource is not stuck waiting for an initial AdPlaybackState.
      eventListener.onAdPlaybackState(new AdPlaybackState(adsId));
      return;
    }
    checkNotNull(contentMediaItem.localConfiguration);
    checkNotNull(contentMediaItem.localConfiguration.adsConfiguration);
    @Nullable Object mediaItemAdsId = contentMediaItem.localConfiguration.adsConfiguration.adsId;
    checkArgument(
        adsId.equals(mediaItemAdsId),
        "contentMediaItem must have an AdsConfiguration with adsId (%s) matching"
            + " initialAdPlaybackState.adsId (%s)",
        mediaItemAdsId,
        adsId);
    checkArgument(
        !sessionsByAdsId.containsKey(adsId), "Session already active for adsId: %s", adsId);
    AdSession session = new AdSession(eventListener, initialAdPlaybackState, sessionCallback);
    sessionsByAdsId.put(adsId, session);
    // Publish before notifying the bridge so that a re-entrant updateAdPlaybackState() from
    // onSessionStarted() is not overwritten by the initial state.
    session.publishAdPlaybackState();
    notifyBridge(bridge -> bridge.onSessionStarted(adsId, contentMediaItem, adViewProvider));
  }

  /**
   * Handles ad period preparation completion and notifies {@link SdkBridge#onAdPrepareCompleted}.
   *
   * <p>If this tracker is released, has no active session for {@code adsId}, or if {@code
   * adGroupIndex} or {@code adIndexInAdGroup} is negative or out of bounds (including removed ad
   * groups and live postroll placeholders), this method is a no-op.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param adGroupIndex The ad group index.
   * @param adIndexInAdGroup The ad index in the ad group.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread.
   */
  public void handlePrepareComplete(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
    verifyLooperThread();
    if (isReleased) {
      return;
    }
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    if (session != null) {
      session.handlePrepareComplete(adGroupIndex, adIndexInAdGroup);
    }
  }

  /**
   * Marks the specified ad as {@link AdPlaybackState#AD_STATE_ERROR} (if still unplayed), publishes
   * the updated {@link AdPlaybackState}, and notifies {@link SdkBridge#onAdError}.
   *
   * <p>If this tracker is released, has no active session for {@code adsId}, or if {@code
   * adGroupIndex} or {@code adIndexInAdGroup} is negative or out of bounds (including removed ad
   * groups and live postroll placeholders), this method is a no-op.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @param adsId The session identifier.
   * @param adGroupIndex The ad group index.
   * @param adIndexInAdGroup The ad index in the ad group.
   * @param exception The preparation error.
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread.
   */
  public void handlePrepareError(
      Object adsId, int adGroupIndex, int adIndexInAdGroup, IOException exception) {
    verifyLooperThread();
    if (isReleased) {
      return;
    }
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    if (session != null) {
      session.handlePrepareError(adGroupIndex, adIndexInAdGroup, exception);
    }
  }

  /**
   * Unregisters the {@link AdsMediaSource} session for {@code adsId}, notifies {@link
   * SdkBridge#onSessionStopped}, and returns the final {@link AdPlaybackState} (or {@code null} if
   * this tracker is released or has no active session for {@code adsId}).
   *
   * <p>The returned instance is a {@linkplain AdPlaybackState#copy() copy} of the session's final
   * state.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread.
   */
  @CanIgnoreReturnValue
  public @Nullable AdPlaybackState stopSession(Object adsId) {
    verifyLooperThread();
    if (isReleased) {
      return null;
    }
    @Nullable AdSession session = sessionsByAdsId.get(adsId);
    if (session == null) {
      return null;
    }
    stopSession(session);
    return session.getAdPlaybackState().copy();
  }

  /**
   * Stops all active sessions, detaches the player listener, clears the player reference, and
   * removes all registered {@link AdPlaybackStateListener} instances.
   *
   * <p>This method should be called when the tracker is no longer needed (for example, from {@link
   * AdsLoader#release()}). Once released, the tracker cannot be reused; calling any method other
   * than {@link #release()} or the {@link AdsMediaSource} callbacks ({@link #startSession}, {@link
   * #stopSession}, {@link #handlePrepareComplete}, {@link #handlePrepareError}) throws {@link
   * IllegalStateException}.
   *
   * <p>Must be called on this tracker's {@link Looper} thread.
   *
   * @throws IllegalStateException If called on a thread other than this tracker's {@link Looper}
   *     thread.
   */
  public void release() {
    verifyLooperThread();
    if (isReleased) {
      return;
    }
    isReleased = true;
    for (AdSession session : ImmutableList.copyOf(sessionsByAdsId.values())) {
      stopSession(session);
    }
    setPlayerInternal(/* player= */ null);
    listeners.clear();
  }

  // Internal methods.

  private void setPlayerInternal(@Nullable Player player) {
    if (this.player == player) {
      return;
    }
    if (player != null) {
      checkArgument(
          player.getApplicationLooper() == looper,
          "Player application looper (%s) must match tracker looper (%s)",
          player.getApplicationLooper(),
          looper);
    }
    if (this.player != null) {
      this.player.removeListener(playerListener);
    }
    for (AdSession session : ImmutableList.copyOf(sessionsByAdsId.values())) {
      // End the ad group for any active sessions when the player listener is removed.
      if (session.isInAdGroup()) {
        session.endAdGroup();
      }
    }
    this.player = player;
    if (player != null) {
      player.addListener(playerListener);
    }
    notifyBridge(bridge -> bridge.onPlayerChanged(player));
    if (player != null && this.player == player) {
      playerListener.updateCurrentSessionDynamicState(player);
    }
  }

  private void stopSession(AdSession session) {
    if (sessionsByAdsId.remove(session.adsId, session)) {
      session.stop();
    }
  }

  private void verifyLooperThread() {
    checkState(
        Looper.myLooper() == looper,
        "Method must be called on the tracker looper thread (%s)",
        looper);
  }

  private void verifyNotReleased() {
    checkState(!isReleased, "AdPlaybackTracker has been released.");
  }

  private void notifyBridge(Consumer<SdkBridge> action) {
    if (sdkBridge == null) {
      return;
    }
    try {
      action.accept(sdkBridge);
    } catch (RuntimeException e) {
      Log.e(TAG, "Unexpected exception in SdkBridge callback", e);
    }
  }

  private @Nullable AdSession getCurrentSession(
      Player currentPlayer, Timeline.Period scratchPeriod) {
    @Nullable AdSession session =
        getSessionForPeriodIndex(
            currentPlayer.getCurrentTimeline(),
            currentPlayer.getCurrentPeriodIndex(),
            scratchPeriod);
    return session != null ? session : getSessionForMediaItem(currentPlayer.getCurrentMediaItem());
  }

  /**
   * Returns the session owning the period at {@code periodIndex}, or {@code null} if there is none.
   */
  private @Nullable AdSession getSessionForPeriodIndex(
      Timeline timeline, int periodIndex, Timeline.Period scratchPeriod) {
    if (sessionsByAdsId.isEmpty()
        || timeline.isEmpty()
        || periodIndex < 0
        || periodIndex >= timeline.getPeriodCount()) {
      return null;
    }
    @Nullable Object adsId = timeline.getPeriod(periodIndex, scratchPeriod).getAdsId();
    return adsId != null ? sessionsByAdsId.get(adsId) : null;
  }

  private @Nullable AdSession getSessionForPosition(
      Timeline timeline, Player.PositionInfo positionInfo, Timeline.Period scratchPeriod) {
    if (sessionsByAdsId.isEmpty()) {
      return null;
    }
    int periodIndex =
        positionInfo.periodUid != null
            ? timeline.getIndexOfPeriod(positionInfo.periodUid)
            : positionInfo.periodIndex;
    if (periodIndex != C.INDEX_UNSET) {
      @Nullable AdSession session = getSessionForPeriodIndex(timeline, periodIndex, scratchPeriod);
      return session != null ? session : getSessionForMediaItem(positionInfo.mediaItem);
    }
    return getSessionWithStartedAdForMediaItem(positionInfo.mediaItem);
  }

  private @Nullable AdSession getSessionWithStartedAdForMediaItem(@Nullable MediaItem mediaItem) {
    @Nullable AdSession session = getSessionForMediaItem(mediaItem);
    return session != null && session.isInAdGroup() ? session : null;
  }

  private @Nullable AdSession getSessionForMediaItem(@Nullable MediaItem mediaItem) {
    if (mediaItem == null
        || mediaItem.localConfiguration == null
        || mediaItem.localConfiguration.adsConfiguration == null
        || mediaItem.localConfiguration.adsConfiguration.adsId == null) {
      return null;
    }
    return sessionsByAdsId.get(mediaItem.localConfiguration.adsConfiguration.adsId);
  }

  private final class SessionCallback implements AdSession.Callback {

    @Override
    public void onAdPlaybackStateChanged(AdSession session, AdPlaybackState adPlaybackState) {
      if (listeners.isEmpty()) {
        return;
      }
      // Hand out a single copy per dispatch (shared across all registered listeners to avoid
      // per-listener array allocations) so that listeners can't modify the tracker's state.
      AdPlaybackState adPlaybackStateCopy = adPlaybackState.copy();
      for (AdPlaybackStateListener listener : listeners) {
        listener.onAdPlaybackStateChanged(session.adsId, adPlaybackStateCopy);
      }
    }

    @Override
    public void notifyBridge(Consumer<SdkBridge> action) {
      AdPlaybackTracker.this.notifyBridge(action);
    }
  }

  /**
   * Translates {@link Player.Listener} events into {@link AdSession} transitions. Resolves which
   * session(s) an event belongs to and delegates.
   */
  private final class PlayerListener implements Player.Listener {

    private final Timeline.Period scratchPeriod = new Timeline.Period();
    private final Timeline.Window scratchWindow = new Timeline.Window();

    @Override
    public void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null || sessionsByAdsId.isEmpty()) {
        return;
      }
      Timeline currentTimeline = currentPlayer.getCurrentTimeline();
      if (currentTimeline.isEmpty()) {
        return;
      }
      @Nullable AdSession currentSession = getCurrentSession(currentPlayer, scratchPeriod);
      if (currentSession != null) {
        updateSessionDynamicState(currentSession, currentPlayer, currentTimeline);
        currentSession.reconcileActiveAd(
            currentPlayer.isPlayingAd(),
            currentPlayer.getCurrentAdGroupIndex(),
            currentPlayer.getCurrentAdIndexInAdGroup());
      }
    }

    @Override
    public void onMediaItemTransition(
        @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null) {
        return;
      }
      updateCurrentSessionDynamicState(currentPlayer);
    }

    private void updateCurrentSessionDynamicState(Player currentPlayer) {
      if (sessionsByAdsId.isEmpty()) {
        return;
      }
      Timeline timeline = currentPlayer.getCurrentTimeline();
      if (timeline.isEmpty()) {
        return;
      }
      @Nullable AdSession currentSession = getCurrentSession(currentPlayer, scratchPeriod);
      if (currentSession != null) {
        updateSessionDynamicState(currentSession, currentPlayer, timeline);
      }
    }

    private void updateSessionDynamicState(
        AdSession currentSession, Player currentPlayer, Timeline timeline) {
      int windowIndex = currentPlayer.getCurrentMediaItemIndex();
      if (windowIndex >= 0 && windowIndex < timeline.getWindowCount()) {
        currentSession.setIsDynamic(isContentDynamic(timeline, windowIndex));
      }
    }

    private boolean isContentDynamic(Timeline timeline, int windowIndex) {
      timeline.getWindow(windowIndex, scratchWindow);
      // A placeholder window doesn't hold the real window information yet, so it's unknown whether
      // the content is static.
      if (scratchWindow.isDynamic || scratchWindow.isPlaceholder) {
        return true;
      }
      if (!scratchWindow.isLive()) {
        return false;
      }
      timeline.getPeriod(scratchWindow.lastPeriodIndex, scratchPeriod);
      return scratchPeriod.durationUs == C.TIME_UNSET;
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null || playbackState == Player.STATE_IDLE) {
        return;
      }
      @Nullable AdSession session = getCurrentSession(currentPlayer, scratchPeriod);
      if (session == null) {
        return;
      }
      if (playbackState == Player.STATE_ENDED) {
        session.notifyContentCompleted();
        return;
      }
      if (!currentPlayer.isPlayingAd()) {
        return;
      }
      int currentAdGroupIndex = currentPlayer.getCurrentAdGroupIndex();
      int currentAdIndexInAdGroup = currentPlayer.getCurrentAdIndexInAdGroup();
      if (playbackState == Player.STATE_READY) {
        session.startAd(
            currentAdGroupIndex, currentAdIndexInAdGroup, currentPlayer.getPlayWhenReady());
      } else if (playbackState == Player.STATE_BUFFERING) {
        session.notifyAdBuffering(currentAdGroupIndex, currentAdIndexInAdGroup);
      }
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null || !currentPlayer.isPlayingAd()) {
        return;
      }
      @Nullable AdSession session = getCurrentSession(currentPlayer, scratchPeriod);
      if (session != null) {
        session.setPlayWhenReady(playWhenReady);
      }
    }

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @Player.DiscontinuityReason int reason) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null) {
        return;
      }
      Timeline timeline = currentPlayer.getCurrentTimeline();
      @Nullable AdSession oldSession = getSessionForPosition(timeline, oldPosition, scratchPeriod);
      @Nullable AdSession newSession = getSessionForPosition(timeline, newPosition, scratchPeriod);
      if (oldSession == null && newSession == null) {
        return;
      }
      boolean playWhenReady = currentPlayer.getPlayWhenReady();

      if (oldSession != null && oldPosition.adGroupIndex != C.INDEX_UNSET) {
        boolean stayedInSameAdGroup =
            oldSession == newSession && oldPosition.adGroupIndex == newPosition.adGroupIndex;
        boolean stayedOnSameAd =
            stayedInSameAdGroup && oldPosition.adIndexInAdGroup == newPosition.adIndexInAdGroup;
        boolean preemptedByEarlierAdInSameGroup =
            stayedInSameAdGroup
                && (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                    || reason == Player.DISCONTINUITY_REASON_SKIP)
                && newPosition.adIndexInAdGroup != C.INDEX_UNSET
                && newPosition.adIndexInAdGroup < oldPosition.adIndexInAdGroup;
        if (!stayedOnSameAd && !preemptedByEarlierAdInSameGroup) {
          oldSession.handleAdEnded(
              oldPosition.adGroupIndex, oldPosition.adIndexInAdGroup, reason, playWhenReady);
        }
        if (!stayedInSameAdGroup) {
          oldSession.endAdGroup();
        }
      }

      if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
          && oldSession != null
          && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
        oldSession.notifyContentCompleted();
      }

      if (newSession != null && currentPlayer.getPlaybackState() == Player.STATE_READY) {
        newSession.startAd(newPosition.adGroupIndex, newPosition.adIndexInAdGroup, playWhenReady);
      }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      @Nullable Player currentPlayer = player;
      if (currentPlayer == null || !currentPlayer.isPlayingAd()) {
        return;
      }
      @Nullable AdSession session = getCurrentSession(currentPlayer, scratchPeriod);
      if (session == null) {
        return;
      }
      session.handlePlaybackError(
          currentPlayer.getCurrentAdGroupIndex(),
          currentPlayer.getCurrentAdIndexInAdGroup(),
          error);
    }
  }
}
