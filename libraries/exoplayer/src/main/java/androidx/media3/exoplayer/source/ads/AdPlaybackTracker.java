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

import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import org.jspecify.annotations.Nullable;

/**
 * Monitors {@link Player} ad playback transitions, coordinates {@link AdPlaybackState} state
 * updates across active {@link AdsMediaSource} sessions, and notifies an optional {@link
 * SdkBridge}.
 *
 * <p>All public methods must be called on the tracker's {@link Looper} thread, which must match the
 * attached {@link Player#getApplicationLooper()}.
 */
@UnstableApi
/* package */ final class AdPlaybackTracker {

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

  private AdPlaybackTracker() {}
}
