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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.source.ads.AdPlaybackTracker.SdkBridge;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tracks the ad playback lifecycle of a single {@link AdsMediaSource} session on behalf of {@link
 * AdPlaybackTracker}.
 *
 * <p>A session owns the current {@link AdPlaybackState} of its {@code adsId} and the position of
 * the ad that is currently playing, and derives {@link SdkBridge} lifecycle events (ad group
 * started/ended, ad started/completed/skipped/errored, all ads completed) from the transitions
 * reported by the tracker. The session has no knowledge of the {@link Player}: the tracker resolves
 * which session a player event belongs to and calls the corresponding method.
 *
 * <p>Not thread-safe. All methods must be called on the tracker's looper thread.
 */
/* package */ final class AdSession {

  /** Callbacks used by a session to publish state changes and notify the {@link SdkBridge}. */
  interface Callback {

    /**
     * Called after the {@link AdPlaybackState} of {@code session} changed and was sent to the
     * session's {@link AdsLoader.EventListener}.
     */
    void onAdPlaybackStateChanged(AdSession session, AdPlaybackState adPlaybackState);

    /** Dispatches {@code action} to the {@link SdkBridge}, if one is set. */
    void notifyBridge(Consumer<SdkBridge> action);
  }

  /** The session identifier. */
  public final Object adsId;

  /** The content {@link MediaItem} of the session. */
  public final MediaItem contentMediaItem;

  private final AdsLoader.EventListener eventListener;
  private final Callback callback;
  private final Set<Long> endedAds;

  private AdPlaybackState adPlaybackState;
  private int activeAdGroupIndex;
  private int activeAdIndexInAdGroup;
  private boolean isAdPaused;
  private boolean allAdsCompletedNotified;
  private boolean contentCompletedNotified;
  private boolean isStopped;

  /**
   * Creates a session. The initial state is not published until {@link #publishAdPlaybackState()}
   * is called.
   *
   * @param contentMediaItem The content {@link MediaItem}.
   * @param eventListener The {@link AdsLoader.EventListener} of the {@link AdsMediaSource}.
   * @param initialAdPlaybackState The initial {@link AdPlaybackState}. Its {@link
   *     AdPlaybackState#adsId} must not be {@code null}. The session stores a {@linkplain
   *     AdPlaybackState#copy() copy} so that the caller can't modify the session state afterwards.
   * @param callback The {@link Callback}.
   */
  public AdSession(
      MediaItem contentMediaItem,
      AdsLoader.EventListener eventListener,
      AdPlaybackState initialAdPlaybackState,
      Callback callback) {
    checkArgument(
        initialAdPlaybackState.adsId != null, "initialAdPlaybackState.adsId must not be null.");
    this.adsId = initialAdPlaybackState.adsId;
    this.contentMediaItem = contentMediaItem;
    this.eventListener = eventListener;
    this.adPlaybackState = initialAdPlaybackState.copy();
    this.callback = callback;
    this.endedAds = new HashSet<>();
    this.activeAdGroupIndex = C.INDEX_UNSET;
    this.activeAdIndexInAdGroup = C.INDEX_UNSET;
  }

  // Queries.

  /** Returns the current {@link AdPlaybackState}. */
  public AdPlaybackState getAdPlaybackState() {
    return adPlaybackState;
  }

  /** Returns whether an ad group has been started and not ended. */
  public boolean isInAdGroup() {
    return activeAdGroupIndex != C.INDEX_UNSET;
  }

  /** Returns whether an individual ad is currently being played. */
  public boolean isPlayingAd() {
    return activeAdGroupIndex != C.INDEX_UNSET && activeAdIndexInAdGroup != C.INDEX_UNSET;
  }

  /** Returns whether the specified ad is the ad that is currently being played. */
  public boolean isPlayingAd(int adGroupIndex, int adIndexInAdGroup) {
    return activeAdGroupIndex == adGroupIndex && activeAdIndexInAdGroup == adIndexInAdGroup;
  }

  // Session lifecycle.

  /**
   * Publishes the current {@link AdPlaybackState} to the {@link AdsLoader.EventListener} and the
   * {@link Callback}.
   */
  public void publishAdPlaybackState() {
    publish(adPlaybackState);
  }

  // State mutations requested through the tracker API.

  /**
   * Validates and replaces the current {@link AdPlaybackState}, synchronizes internal session
   * state, and publishes it if it changed.
   *
   * @return Whether the state changed.
   * @throws IllegalStateException If {@code adPlaybackState} has a different {@link
   *     AdPlaybackState#adsId} or is an invalid update from the current state.
   */
  @CanIgnoreReturnValue
  public boolean setAdPlaybackState(AdPlaybackState adPlaybackState) {
    if (adPlaybackState.equals(this.adPlaybackState)) {
      return false;
    }
    checkState(
        Objects.equals(adPlaybackState.adsId, adsId),
        "setAdPlaybackState must not change adsId (expected %s, got %s)",
        adsId,
        adPlaybackState.adsId);
    AdsMediaSource.checkValidAdPlaybackStateUpdate(this.adPlaybackState, adPlaybackState);
    List<Long> newlyErroredAds =
        syncStateWithUpdatedAdPlaybackState(this.adPlaybackState, adPlaybackState);
    publish(adPlaybackState);
    notifyNewlyErroredAds(newlyErroredAds);
    maybeNotifyAllAdsCompletedIfNotInAdGroup();
    return true;
  }

  /**
   * Resets ads in all non-removed ad groups from final states ({@link
   * AdPlaybackState#AD_STATE_PLAYED}, {@link AdPlaybackState#AD_STATE_SKIPPED}, and optionally
   * {@link AdPlaybackState#AD_STATE_ERROR}) back to {@link AdPlaybackState#AD_STATE_AVAILABLE} or
   * {@link AdPlaybackState#AD_STATE_UNAVAILABLE}, resets the ad resume position to {@code 0}, and
   * publishes the updated {@link AdPlaybackState}.
   */
  public void resetAllAds(boolean resetErrorAds) {
    AdPlaybackState state = adPlaybackState;
    for (int i = state.removedAdGroupCount; i < state.adGroupCount; i++) {
      if (state.isLivePostrollPlaceholder(i)) {
        continue;
      }
      AdPlaybackState.AdGroup oldGroup = state.getAdGroup(i);
      state = state.withResetAdGroup(i);
      if (!resetErrorAds) {
        for (int j = 0; j < oldGroup.states.length; j++) {
          if (oldGroup.states[j] == AdPlaybackState.AD_STATE_ERROR) {
            state = state.withAdLoadError(i, j);
          }
        }
      }
    }
    state = state.withAdResumePositionUs(0);
    if (resetErrorAds) {
      endedAds.clear();
    } else {
      AdPlaybackState updatedState = state;
      endedAds.removeIf(
          adKey ->
              isUnplayedAd(
                  updatedState,
                  /* adGroupIndex= */ (int) (adKey >>> 32),
                  /* adIndexInAdGroup= */ (int) adKey.longValue()));
    }
    contentCompletedNotified = false;
    if (!areAllAdsCompleted(state)) {
      allAdsCompletedNotified = false;
    }
    setAdPlaybackStateInternal(state);
  }

  /**
   * Marks the specified ad as {@link AdPlaybackState#AD_STATE_SKIPPED} if it is currently {@link
   * AdPlaybackState#AD_STATE_UNAVAILABLE} or {@link AdPlaybackState#AD_STATE_AVAILABLE}.
   */
  public void skipAd(int adGroupIndex, int adIndexInAdGroup) {
    if (!isUnplayedAd(adGroupIndex, adIndexInAdGroup)) {
      return;
    }
    setAdPlaybackStateInternal(adPlaybackState.withSkippedAd(adGroupIndex, adIndexInAdGroup));
  }

  /**
   * Marks all unplayed ads in {@code adGroupIndex} as {@link AdPlaybackState#AD_STATE_SKIPPED},
   * preserving already played and errored ads.
   */
  public void skipAdGroup(int adGroupIndex) {
    if (!isValidAdGroupIndex(adPlaybackState, adGroupIndex)) {
      return;
    }
    setAdPlaybackStateInternal(adPlaybackState.withSkippedAdGroup(adGroupIndex));
  }

  /**
   * Handles a preparation error of the specified ad: marks the ad as {@link
   * AdPlaybackState#AD_STATE_ERROR} if it is still unplayed, notifies {@link SdkBridge#onAdError}
   * and, if no ad group is active, checks whether all ads have completed.
   */
  public void handlePrepareError(int adGroupIndex, int adIndexInAdGroup, Exception error) {
    handleAdError(adGroupIndex, adIndexInAdGroup, error);
    maybeNotifyAllAdsCompletedIfNotInAdGroup();
  }

  /**
   * Handles a fatal playback error while the player is playing the specified ad: marks the ad as
   * {@link AdPlaybackState#AD_STATE_ERROR} if it is still unplayed and notifies {@link
   * SdkBridge#onAdError}.
   */
  public void handlePlaybackError(int adGroupIndex, int adIndexInAdGroup, Exception error) {
    handleAdError(adGroupIndex, adIndexInAdGroup, error);
  }

  // Transitions derived from player events.

  /**
   * Starts the specified ad, unless it is already the ad that is playing. Ends the previous ad
   * group and starts a new one if the ad group changed.
   *
   * @param adGroupIndex The ad group index, or {@link C#INDEX_UNSET} to do nothing.
   * @param adIndexInAdGroup The ad index in the ad group, or {@link C#INDEX_UNSET} to do nothing.
   * @param playWhenReady The current {@link Player#getPlayWhenReady()}.
   */
  public void startAd(int adGroupIndex, int adIndexInAdGroup, boolean playWhenReady) {
    if (isStopped || adGroupIndex == C.INDEX_UNSET || adIndexInAdGroup == C.INDEX_UNSET) {
      return;
    }
    if (isPlayingAd(adGroupIndex, adIndexInAdGroup)) {
      return;
    }
    int previousAdGroupIndex = activeAdGroupIndex;
    boolean adGroupChanged = previousAdGroupIndex != adGroupIndex;
    activeAdGroupIndex = adGroupIndex;
    activeAdIndexInAdGroup = adIndexInAdGroup;
    isAdPaused = !playWhenReady;
    endedAds.remove(getAdKey(adGroupIndex, adIndexInAdGroup));
    if (adGroupChanged) {
      if (previousAdGroupIndex != C.INDEX_UNSET) {
        callback.notifyBridge(bridge -> bridge.onAdGroupEnded(adsId, previousAdGroupIndex));
      }
      callback.notifyBridge(bridge -> bridge.onAdGroupStarted(adsId, adGroupIndex));
    }
    callback.notifyBridge(bridge -> bridge.onAdStarted(adsId, adGroupIndex, adIndexInAdGroup));
  }

  /**
   * Handles the player having left the specified ad.
   *
   * <p>For {@link Player#DISCONTINUITY_REASON_AUTO_TRANSITION} the ad is reported as completed (and
   * marked {@link AdPlaybackState#AD_STATE_PLAYED} if it was available), or as skipped if it was
   * marked skipped before it started. For {@link Player#DISCONTINUITY_REASON_SKIP} the ad is marked
   * and reported as skipped unless it was already played or errored. Other reasons are ignored.
   * Each ad is reported at most once per playthrough; if the ad was never started it is started
   * first so that start and end events are always paired.
   *
   * @param adGroupIndex The ad group index of the ad that was left.
   * @param adIndexInAdGroup The ad index in the ad group of the ad that was left.
   * @param reason The {@link Player.DiscontinuityReason}.
   * @param playWhenReady The current {@link Player#getPlayWhenReady()}.
   */
  public void handleAdEnded(
      int adGroupIndex,
      int adIndexInAdGroup,
      @Player.DiscontinuityReason int reason,
      boolean playWhenReady) {
    long adKey = getAdKey(adGroupIndex, adIndexInAdGroup);
    if (isStopped
        || (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            && reason != Player.DISCONTINUITY_REASON_SKIP)
        || !isValidAdIndex(adPlaybackState, adGroupIndex, adIndexInAdGroup)
        || endedAds.contains(adKey)) {
      return;
    }
    @AdPlaybackState.AdState
    int adState = adPlaybackState.getAdGroup(adGroupIndex).states[adIndexInAdGroup];
    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
      if (adState == AdPlaybackState.AD_STATE_SKIPPED) {
        startAd(adGroupIndex, adIndexInAdGroup, playWhenReady);
        // The ad was skipped before the tracker started it (e.g. skipAd() while the ad was still
        // buffering) and the player auto-transitioned to the next ad in the same ad group.
        // ExoPlayer reports this as an auto transition, so the timeline change did not handle it.
        // Report the skip now that the ad has been started above.
        callback.notifyBridge(bridge -> bridge.onAdSkipped(adsId, adGroupIndex, adIndexInAdGroup));
      } else if (adState != AdPlaybackState.AD_STATE_ERROR) {
        startAd(adGroupIndex, adIndexInAdGroup, playWhenReady);
        if (adState == AdPlaybackState.AD_STATE_AVAILABLE) {
          publish(adPlaybackState.withPlayedAd(adGroupIndex, adIndexInAdGroup));
        }
        callback.notifyBridge(
            bridge -> bridge.onAdCompleted(adsId, adGroupIndex, adIndexInAdGroup));
      }
    } else if (adState != AdPlaybackState.AD_STATE_ERROR
        && adState != AdPlaybackState.AD_STATE_PLAYED) {
      startAd(adGroupIndex, adIndexInAdGroup, playWhenReady);
      if (adState != AdPlaybackState.AD_STATE_SKIPPED) {
        // The ad was not skipped through the tracker, so the state is not updated yet.
        publish(adPlaybackState.withSkippedAd(adGroupIndex, adIndexInAdGroup));
      }
      callback.notifyBridge(bridge -> bridge.onAdSkipped(adsId, adGroupIndex, adIndexInAdGroup));
    }
    if (isPlayingAd(adGroupIndex, adIndexInAdGroup)) {
      // Keep activeAdGroupIndex set until endAdGroup() ends the ad group.
      activeAdIndexInAdGroup = C.INDEX_UNSET;
      isAdPaused = false;
    }
    endedAds.add(adKey);
  }

  /**
   * Ends the active ad group, if any, notifying {@link SdkBridge#onAdGroupEnded}, and checks
   * whether all ads have completed.
   */
  public void endAdGroup() {
    if (activeAdGroupIndex != C.INDEX_UNSET) {
      int endedAdGroupIndex = activeAdGroupIndex;
      resetActiveAd();
      callback.notifyBridge(bridge -> bridge.onAdGroupEnded(adsId, endedAdGroupIndex));
    }
    maybeNotifyAllAdsCompleted();
  }

  /**
   * Reconciles the ad that is tracked as playing with the player position after a timeline change.
   *
   * <p>Compensates for dropped {@link Player.Listener#onPositionDiscontinuity} events when the
   * tracked ad is marked {@link AdPlaybackState#AD_STATE_PLAYED}, {@link
   * AdPlaybackState#AD_STATE_SKIPPED}, or {@link AdPlaybackState#AD_STATE_ERROR} and the player
   * transitions out of the ad with only a timeline update (see bug-ref: 577167822).
   *
   * @param isPlayingAdInSession Whether the player is currently playing an ad that belongs to this
   *     session.
   * @param currentAdGroupIndex The {@link Player#getCurrentAdGroupIndex()}.
   * @param currentAdIndexInAdGroup The {@link Player#getCurrentAdIndexInAdGroup()}.
   */
  public void reconcileActiveAd(
      boolean isPlayingAdInSession, int currentAdGroupIndex, int currentAdIndexInAdGroup) {
    if (!isPlayingAd()
        || !isValidAdIndex(adPlaybackState, activeAdGroupIndex, activeAdIndexInAdGroup)) {
      return;
    }
    int adGroupIndex = activeAdGroupIndex;
    int adIndexInAdGroup = activeAdIndexInAdGroup;
    if (isPlayingAdInSession
        && currentAdGroupIndex == adGroupIndex
        && currentAdIndexInAdGroup == adIndexInAdGroup) {
      return;
    }
    @AdPlaybackState.AdState
    int adState = adPlaybackState.getAdGroup(adGroupIndex).states[adIndexInAdGroup];
    if (adState != AdPlaybackState.AD_STATE_SKIPPED
        && adState != AdPlaybackState.AD_STATE_ERROR
        && adState != AdPlaybackState.AD_STATE_PLAYED) {
      return;
    }
    // The player left the active ad without reporting a position discontinuity (see
    // https://issuetracker.google.com/577167822). Emit the missing end notifications.
    boolean newlyMarkedEnded = endedAds.add(getAdKey(adGroupIndex, adIndexInAdGroup));
    boolean exitedAdGroup = !isPlayingAdInSession || currentAdGroupIndex != adGroupIndex;
    if (exitedAdGroup) {
      resetActiveAd();
    } else {
      activeAdIndexInAdGroup = C.INDEX_UNSET;
      isAdPaused = false;
    }
    if (newlyMarkedEnded && adState == AdPlaybackState.AD_STATE_SKIPPED) {
      callback.notifyBridge(bridge -> bridge.onAdSkipped(adsId, adGroupIndex, adIndexInAdGroup));
    }
    if (exitedAdGroup) {
      callback.notifyBridge(bridge -> bridge.onAdGroupEnded(adsId, adGroupIndex));
      maybeNotifyAllAdsCompleted();
    }
  }

  /**
   * Handles a change of {@link Player#getPlayWhenReady()} while an ad of this session is playing,
   * notifying {@link SdkBridge#onAdPaused} or {@link SdkBridge#onAdResumed} on transitions.
   */
  public void setPlayWhenReady(boolean playWhenReady) {
    if (!isPlayingAd()) {
      return;
    }
    int adGroupIndex = activeAdGroupIndex;
    int adIndexInAdGroup = activeAdIndexInAdGroup;
    if (!playWhenReady && !isAdPaused) {
      isAdPaused = true;
      callback.notifyBridge(bridge -> bridge.onAdPaused(adsId, adGroupIndex, adIndexInAdGroup));
    } else if (playWhenReady && isAdPaused) {
      isAdPaused = false;
      callback.notifyBridge(bridge -> bridge.onAdResumed(adsId, adGroupIndex, adIndexInAdGroup));
    }
  }

  /**
   * Notifies {@link SdkBridge#onAdBuffering} if the specified ad is the ad that is currently
   * playing.
   */
  public void notifyAdBuffering(int adGroupIndex, int adIndexInAdGroup) {
    if (isPlayingAd(adGroupIndex, adIndexInAdGroup)) {
      callback.notifyBridge(bridge -> bridge.onAdBuffering(adsId, adGroupIndex, adIndexInAdGroup));
    }
  }

  /** Notifies {@link SdkBridge#onContentCompleted} once per session. */
  public void notifyContentCompleted() {
    if (contentCompletedNotified || isStopped) {
      return;
    }
    contentCompletedNotified = true;
    callback.notifyBridge(bridge -> bridge.onContentCompleted(adsId));
  }

  /**
   * Stops the session and notifies {@link SdkBridge#onSessionStopped}. The session must not be used
   * afterwards.
   */
  public void stop() {
    if (isStopped) {
      return;
    }
    isStopped = true;
    resetActiveAd();
    AdPlaybackState finalAdPlaybackState = adPlaybackState.copy();
    callback.notifyBridge(bridge -> bridge.onSessionStopped(adsId, finalAdPlaybackState));
  }

  // Internal methods.

  private static boolean isEndedAd(AdPlaybackState.AdGroup adGroup, int adIndexInAdGroup) {
    if (adIndexInAdGroup >= adGroup.states.length) {
      return false;
    }
    @AdPlaybackState.AdState int adState = adGroup.states[adIndexInAdGroup];
    return adState == AdPlaybackState.AD_STATE_PLAYED
        || adState == AdPlaybackState.AD_STATE_SKIPPED
        || adState == AdPlaybackState.AD_STATE_ERROR;
  }

  /**
   * Returns whether the specified ad in {@code state} is {@link
   * AdPlaybackState#AD_STATE_UNAVAILABLE} or {@link AdPlaybackState#AD_STATE_AVAILABLE}.
   *
   * <p>Unlike {@link #isValidAdIndex}, an index at or beyond {@link AdPlaybackState.AdGroup#states}
   * is accepted (and considered unavailable) as long as it is within {@link
   * AdPlaybackState.AdGroup#count} or the count is unset, so that ads can be marked before they
   * have been loaded.
   */
  private static boolean isUnplayedAd(
      AdPlaybackState state, int adGroupIndex, int adIndexInAdGroup) {
    if (!canMarkOrReportAd(state, adGroupIndex, adIndexInAdGroup)) {
      return false;
    }
    AdPlaybackState.AdGroup adGroup = state.getAdGroup(adGroupIndex);
    @AdPlaybackState.AdState
    int adState =
        adIndexInAdGroup < adGroup.states.length
            ? adGroup.states[adIndexInAdGroup]
            : AdPlaybackState.AD_STATE_UNAVAILABLE;
    return adState == AdPlaybackState.AD_STATE_UNAVAILABLE
        || adState == AdPlaybackState.AD_STATE_AVAILABLE;
  }

  private boolean isUnplayedAd(int adGroupIndex, int adIndexInAdGroup) {
    return isUnplayedAd(adPlaybackState, adGroupIndex, adIndexInAdGroup);
  }

  private static boolean canMarkOrReportAd(
      AdPlaybackState state, int adGroupIndex, int adIndexInAdGroup) {
    if (!isValidAdGroupIndex(state, adGroupIndex) || adIndexInAdGroup < 0) {
      return false;
    }
    AdPlaybackState.AdGroup adGroup = state.getAdGroup(adGroupIndex);
    return adGroup.count == C.LENGTH_UNSET || adIndexInAdGroup < adGroup.count;
  }

  /**
   * Returns whether all playable ads in {@code state} have been played, skipped or errored.
   *
   * <p>While {@code state} ends with a live postroll placeholder, more ads may still be inserted
   * and the result is {@code false}.
   */
  private static boolean areAllAdsCompleted(AdPlaybackState state) {
    if (state.endsWithLivePostrollPlaceHolder()) {
      return false;
    }
    if (state.adGroupCount <= state.removedAdGroupCount) {
      return false;
    }
    for (int i = state.removedAdGroupCount; i < state.adGroupCount; i++) {
      if (state.getAdGroup(i).hasUnplayedAds()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isValidAdGroupIndex(AdPlaybackState state, int adGroupIndex) {
    return adGroupIndex >= state.removedAdGroupCount
        && adGroupIndex < state.adGroupCount
        && !state.isLivePostrollPlaceholder(adGroupIndex);
  }

  /** Returns whether the specified ad exists in {@link AdPlaybackState.AdGroup#states}. */
  private static boolean isValidAdIndex(
      AdPlaybackState state, int adGroupIndex, int adIndexInAdGroup) {
    if (!isValidAdGroupIndex(state, adGroupIndex) || adIndexInAdGroup < 0) {
      return false;
    }
    AdPlaybackState.AdGroup adGroup = state.getAdGroup(adGroupIndex);
    return adGroup.count == C.LENGTH_UNSET
        ? adIndexInAdGroup < adGroup.states.length
        : adIndexInAdGroup < adGroup.count;
  }

  private static long getAdKey(int adGroupIndex, int adIndexInAdGroup) {
    return (((long) adGroupIndex) << 32) | (adIndexInAdGroup & 0xFFFFFFFFL);
  }

  @CanIgnoreReturnValue
  private boolean setAdPlaybackStateInternal(AdPlaybackState adPlaybackState) {
    if (adPlaybackState.equals(this.adPlaybackState)) {
      return false;
    }
    publish(adPlaybackState);
    maybeNotifyAllAdsCompletedIfNotInAdGroup();
    return true;
  }

  private void publish(AdPlaybackState adPlaybackState) {
    this.adPlaybackState = adPlaybackState;
    eventListener.onAdPlaybackState(adPlaybackState);
    callback.onAdPlaybackStateChanged(this, adPlaybackState);
  }

  private List<Long> syncStateWithUpdatedAdPlaybackState(
      AdPlaybackState oldState, AdPlaybackState newState) {
    boolean hasReactivatedAd = false;
    List<Long> newlyErroredAds = new ArrayList<>();
    for (int i = newState.removedAdGroupCount; i < newState.adGroupCount; i++) {
      if (newState.isLivePostrollPlaceholder(i)) {
        break;
      }
      boolean isExistingAdGroup =
          i < oldState.adGroupCount && !oldState.isLivePostrollPlaceholder(i);
      AdPlaybackState.AdGroup newGroup = newState.getAdGroup(i);
      for (int j = 0; j < newGroup.states.length; j++) {
        @AdPlaybackState.AdState int newAdState = newGroup.states[j];
        long adKey = getAdKey(i, j);
        if (newAdState == AdPlaybackState.AD_STATE_UNAVAILABLE
            || newAdState == AdPlaybackState.AD_STATE_AVAILABLE) {
          endedAds.remove(adKey);
          if (isExistingAdGroup && isEndedAd(oldState.getAdGroup(i), j)) {
            hasReactivatedAd = true;
          }
        } else if (newAdState == AdPlaybackState.AD_STATE_PLAYED
            || newAdState == AdPlaybackState.AD_STATE_ERROR) {
          // AD_STATE_SKIPPED is intentionally omitted so handleAdEnded can report buffering skips.
          if (!isPlayingAd(i, j)) {
            endedAds.add(adKey);
          }
          if (newAdState == AdPlaybackState.AD_STATE_ERROR
              && (!isExistingAdGroup || isUnplayedAd(oldState, i, j))) {
            newlyErroredAds.add(adKey);
          }
        }
      }
    }
    if (hasReactivatedAd) {
      contentCompletedNotified = false;
    }
    if (!areAllAdsCompleted(newState)) {
      allAdsCompletedNotified = false;
    }
    return newlyErroredAds;
  }

  private void notifyNewlyErroredAds(List<Long> newlyErroredAds) {
    for (int i = 0; i < newlyErroredAds.size(); i++) {
      long adKey = newlyErroredAds.get(i);
      int adGroupIndex = (int) (adKey >>> 32);
      int adIndexInAdGroup = (int) adKey;
      IOException error = new IOException("Ad marked as error in AdPlaybackState");
      callback.notifyBridge(
          bridge -> bridge.onAdError(adsId, adGroupIndex, adIndexInAdGroup, error));
    }
  }

  private void resetActiveAd() {
    activeAdGroupIndex = C.INDEX_UNSET;
    activeAdIndexInAdGroup = C.INDEX_UNSET;
    isAdPaused = false;
  }

  private void handleAdError(int adGroupIndex, int adIndexInAdGroup, Exception error) {
    if (isStopped || !canMarkOrReportAd(adPlaybackState, adGroupIndex, adIndexInAdGroup)) {
      return;
    }
    if (isUnplayedAd(adGroupIndex, adIndexInAdGroup)) {
      publish(adPlaybackState.withAdLoadError(adGroupIndex, adIndexInAdGroup));
    }
    callback.notifyBridge(bridge -> bridge.onAdError(adsId, adGroupIndex, adIndexInAdGroup, error));
  }

  private void maybeNotifyAllAdsCompletedIfNotInAdGroup() {
    if (!isInAdGroup()) {
      maybeNotifyAllAdsCompleted();
    }
  }

  private void maybeNotifyAllAdsCompleted() {
    if (allAdsCompletedNotified || isStopped) {
      // Already notified, or the session was stopped re-entrantly by a preceding bridge callback.
      return;
    }
    if (!areAllAdsCompleted(adPlaybackState)) {
      return;
    }
    allAdsCompletedNotified = true;
    AdPlaybackState stateCopy = adPlaybackState.copy();
    callback.notifyBridge(bridge -> bridge.onAllAdsCompleted(adsId, stateCopy));
  }
}
