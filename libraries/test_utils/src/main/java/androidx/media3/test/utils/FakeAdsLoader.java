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

import androidx.annotation.Nullable;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C.ContentType;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** A fake implementation of {@link AdsLoader}. */
@UnstableApi
public final class FakeAdsLoader implements AdsLoader {

  public final Map<Object, EventListener> eventListeners;

  @Nullable public Player player;
  public int @ContentType [] supportedContentTypes;

  /** Creates an instance. */
  public FakeAdsLoader() {
    supportedContentTypes = new int[0];
    eventListeners = new HashMap<>();
  }

  @Override
  public void setSupportedContentTypes(@ContentType int... contentTypes) {
    supportedContentTypes = contentTypes;
  }

  @Override
  public void setPlayer(@Nullable Player player) {
    this.player = player;
  }

  @Override
  public void start(
      AdsMediaSource adsMediaSource,
      DataSpec adTagDataSpec,
      Object adsId,
      AdViewProvider adViewProvider,
      EventListener eventListener) {
    this.eventListeners.put(adsId, eventListener);
  }

  @Override
  public boolean handleContentTimelineChanged(AdsMediaSource adsMediaSource, Timeline timeline) {
    return false;
  }

  @Override
  public void handlePrepareComplete(
      AdsMediaSource adsMediaSource, int adGroupIndex, int adIndexInAdGroup) {
    // Do nothing.
  }

  @Override
  public void handlePrepareError(
      AdsMediaSource adsMediaSource,
      int adGroupIndex,
      int adIndexInAdGroup,
      IOException exception) {
    // Do nothing.
  }

  @Override
  public void stop(AdsMediaSource adsMediaSource, EventListener eventListener) {
    Object adsId = adsMediaSource.getAdsId();
    this.eventListeners.remove(adsId);
  }

  @Override
  public void release() {
    player = null;
    supportedContentTypes = new int[0];
  }
}
