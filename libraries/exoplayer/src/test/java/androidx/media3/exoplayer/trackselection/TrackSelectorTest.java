/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.annotation.Nullable;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.TrackSelector.InvalidationListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit test for {@link TrackSelector}. */
@RunWith(AndroidJUnit4.class)
public class TrackSelectorTest {

  private TrackSelector trackSelector;

  @Before
  public void setUp() {
    trackSelector =
        new TrackSelector() {
          @Override
          public TrackSelectorResult selectTracks(
              RendererCapabilities[] rendererCapabilities,
              TrackGroupArray trackGroups,
              MediaPeriodId periodId,
              Timeline timeline)
              throws ExoPlaybackException {
            throw new UnsupportedOperationException();
          }

          @Override
          public void onSelectionActivated(@Nullable Object info) {}
        };
  }

  @Test
  public void getBandwidthMeter_beforeInitialization_throwsException() {
    assertThrows(NullPointerException.class, () -> trackSelector.getBandwidthMeter());
  }

  @Test
  public void getBandwidthMeter_afterInitialization_returnsProvidedBandwidthMeter() {
    InvalidationListener invalidationListener = Mockito.mock(InvalidationListener.class);
    BandwidthMeter bandwidthMeter = Mockito.mock(BandwidthMeter.class);
    trackSelector.init(invalidationListener, bandwidthMeter);

    assertThat(trackSelector.getBandwidthMeter()).isSameInstanceAs(bandwidthMeter);
  }

  @Test
  public void getBandwidthMeter_afterRelease_throwsException() {
    InvalidationListener invalidationListener = Mockito.mock(InvalidationListener.class);
    BandwidthMeter bandwidthMeter = Mockito.mock(BandwidthMeter.class);
    trackSelector.init(invalidationListener, bandwidthMeter);

    trackSelector.release();

    assertThrows(NullPointerException.class, () -> trackSelector.getBandwidthMeter());
  }

  @Test
  public void initialize_afterRelease() {
    InvalidationListener invalidationListener = Mockito.mock(InvalidationListener.class);
    BandwidthMeter bandwidthMeter = Mockito.mock(BandwidthMeter.class);
    trackSelector.init(invalidationListener, bandwidthMeter);

    trackSelector.release();
    BandwidthMeter anotherBandwidthMeter = Mockito.mock(BandwidthMeter.class);
    trackSelector.init(invalidationListener, anotherBandwidthMeter);

    assertThat(trackSelector.getBandwidthMeter()).isSameInstanceAs(anotherBandwidthMeter);
  }
}
