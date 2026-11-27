/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link AnalyticsListener} that captures video and audio {@link DecoderCounters} objects.
 *
 * <p>This is needed because {@link CompositionPlayer} does not expose methods like {@link
 * ExoPlayer#getAudioDecoderCounters()} and {@link ExoPlayer#getVideoDecoderCounters()}.
 */
public final class DecoderCountersListener implements AnalyticsListener {

  private final AtomicReference<DecoderCounters> audioDecoderCounters = new AtomicReference<>();
  private final AtomicReference<DecoderCounters> videoDecoderCounters = new AtomicReference<>();

  @Override
  public void onAudioEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    audioDecoderCounters.set(decoderCounters);
  }

  @Override
  public void onVideoEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    videoDecoderCounters.set(decoderCounters);
  }

  /**
   * Returns the audio {@link DecoderCounters} (if set).
   *
   * <p>Can be called on any thread.
   */
  @Nullable
  public DecoderCounters getAudioDecoderCounters() {
    return audioDecoderCounters.get();
  }

  /**
   * Returns the video {@link DecoderCounters} (if set).
   *
   * <p>Can be called on any thread.
   */
  @Nullable
  public DecoderCounters getVideoDecoderCounters() {
    return videoDecoderCounters.get();
  }
}
