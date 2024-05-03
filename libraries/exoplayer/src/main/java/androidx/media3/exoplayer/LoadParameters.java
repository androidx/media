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
package androidx.media3.exoplayer;

import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;

public class LoadParameters {
    /* Identifier for a player instance. */
    public final PlayerId playerId;
    /* A flexible representation of the structure of media. */
    public final Timeline timeline;
    /* Identifier for a {@link MediaPeriod}. */
    public final MediaSource.MediaPeriodId mediaPeriodId;
    /* The current playback position in microseconds, relative to the start
     * of the {@link Timeline.Period period} that will continue to be loaded if this method
     * returns {@code true}. If playback of this period has not yet started, the value will be
     * negative and equal in magnitude to the duration of any media in previous periods still to
     * be played.
     */
    public final long playbackPositionUs;
    /* The duration of media that's currently buffered. */
    public final long bufferedDurationUs;
    /* The current factor by which playback is sped up. */
    public final float playbackSpeed;
    /** Whether playback should proceed when {@link Player#STATE_READY}. */
    public final boolean playWhenReady;

    public LoadParameters(final PlayerId playerId,
                          final Timeline timeline,
                          final MediaSource.MediaPeriodId mediaPeriodId,
                          final long playbackPositionUs,
                          final long bufferedDurationUs,
                          final float playbackSpeed,
                          final boolean playWhenReady) {
        this.playerId = playerId;
        this.timeline = timeline;
        this.mediaPeriodId = mediaPeriodId;
        this.playbackPositionUs = playbackPositionUs;
        this.bufferedDurationUs = bufferedDurationUs;
        this.playbackSpeed = playbackSpeed;
        this.playWhenReady = playWhenReady;
    }
}
