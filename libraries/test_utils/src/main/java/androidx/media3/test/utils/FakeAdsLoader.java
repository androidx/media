/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.RestrictTo;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C.ContentType;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.ads.AdPlaybackTracker;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A fake implementation of {@link AdsLoader}. */
@UnstableApi
public final class FakeAdsLoader implements AdsLoader {

  public final Map<Object, EventListener> eventListeners;
  private final Map<Object, AdPlaybackState> initialAdPlaybackStates;
  private final @Nullable AdPlaybackTracker adPlaybackTracker;

  public @Nullable Player player;
  public int @ContentType [] supportedContentTypes;

  /** Creates an instance. */
  public FakeAdsLoader() {
    this(/* adPlaybackTracker= */ null);
  }

  /**
   * Creates an instance that delegates session management, player monitoring, and preparation
   * callbacks to the provided {@link AdPlaybackTracker}.
   */
  @RestrictTo(LIBRARY_GROUP)
  public FakeAdsLoader(@Nullable AdPlaybackTracker adPlaybackTracker) {
    this.adPlaybackTracker = adPlaybackTracker;
    supportedContentTypes = new int[0];
    eventListeners = new HashMap<>();
    initialAdPlaybackStates = new HashMap<>();
  }

  /**
   * Pre-registers the initial {@link AdPlaybackState} to publish (or pass to {@link
   * AdPlaybackTracker#startSession}) when {@link #start} is called for {@code adsId}.
   *
   * @throws IllegalArgumentException If {@code initialAdPlaybackState.adsId} does not equal {@code
   *     adsId}.
   */
  public void setInitialAdPlaybackState(Object adsId, AdPlaybackState initialAdPlaybackState) {
    checkArgument(
        adsId.equals(initialAdPlaybackState.adsId),
        "initialAdPlaybackState.adsId (%s) must equal adsId (%s)",
        initialAdPlaybackState.adsId,
        adsId);
    initialAdPlaybackStates.put(adsId, initialAdPlaybackState);
  }

  @Override
  public void setSupportedContentTypes(@ContentType int... contentTypes) {
    supportedContentTypes = contentTypes;
  }

  @Override
  public void setPlayer(@Nullable Player player) {
    this.player = player;
    if (adPlaybackTracker != null) {
      adPlaybackTracker.setPlayer(player);
    }
  }

  @Override
  public void start(
      AdsMediaSource adsMediaSource,
      DataSpec adTagDataSpec,
      Object adsId,
      AdViewProvider adViewProvider,
      EventListener eventListener) {
    this.eventListeners.put(adsId, eventListener);
    @Nullable AdPlaybackState registeredState = initialAdPlaybackStates.get(adsId);
    if (adPlaybackTracker != null) {
      AdPlaybackState initialState =
          registeredState != null ? registeredState : new AdPlaybackState(adsId);
      adPlaybackTracker.startSession(
          adsMediaSource.getMediaItem(), adViewProvider, eventListener, initialState);
    } else if (registeredState != null) {
      eventListener.onAdPlaybackState(registeredState);
    }
  }

  @Override
  public boolean handleContentTimelineChanged(AdsMediaSource adsMediaSource, Timeline timeline) {
    return false;
  }

  @Override
  public void handlePrepareComplete(
      AdsMediaSource adsMediaSource, int adGroupIndex, int adIndexInAdGroup) {
    if (adPlaybackTracker != null) {
      adPlaybackTracker.handlePrepareComplete(
          adsMediaSource.getAdsId(), adGroupIndex, adIndexInAdGroup);
    }
  }

  @Override
  public void handlePrepareError(
      AdsMediaSource adsMediaSource,
      int adGroupIndex,
      int adIndexInAdGroup,
      IOException exception) {
    if (adPlaybackTracker != null) {
      adPlaybackTracker.handlePrepareError(
          adsMediaSource.getAdsId(), adGroupIndex, adIndexInAdGroup, exception);
    }
  }

  @Override
  public void stop(AdsMediaSource adsMediaSource, EventListener eventListener) {
    Object adsId = adsMediaSource.getAdsId();
    this.eventListeners.remove(adsId);
    if (adPlaybackTracker != null) {
      adPlaybackTracker.stopSession(adsId);
    }
  }

  @Override
  public void release() {
    player = null;
    supportedContentTypes = new int[0];
    if (adPlaybackTracker != null) {
      adPlaybackTracker.release();
    }
  }
}
