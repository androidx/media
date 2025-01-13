/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static androidx.media3.common.C.FORMAT_EXCEEDS_CAPABILITIES;
import static androidx.media3.common.C.FORMAT_HANDLED;
import static androidx.media3.common.C.FORMAT_UNSUPPORTED_DRM;
import static androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE;
import static androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE;
import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_UNKNOWN;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.MimeTypes.AUDIO_OPUS;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SUPPORTED;
import static androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_SEAMLESS;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Point;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TrackSelectionUtil}. */
@RunWith(AndroidJUnit4.class)
public class TrackSelectionUtilTest {

  @Test
  public void buildTrackInfos_withTestValues_isAsExpected() {
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo =
        new MappingTrackSelector.MappedTrackInfo(
            new String[] {"rendererName1", "rendererName2"},
            new int[] {TRACK_TYPE_AUDIO, TRACK_TYPE_VIDEO},
            new TrackGroupArray[] {
              new TrackGroupArray(
                  new TrackGroup("0", new Format.Builder().setSampleMimeType(AUDIO_AAC).build()),
                  new TrackGroup("1", new Format.Builder().setSampleMimeType(AUDIO_OPUS).build())),
              new TrackGroupArray(
                  new TrackGroup(
                      "2",
                      new Format.Builder().setSampleMimeType(VIDEO_H264).build(),
                      new Format.Builder().setSampleMimeType(VIDEO_H264).build()))
            },
            new int[] {ADAPTIVE_SEAMLESS, ADAPTIVE_NOT_SUPPORTED},
            new int[][][] {
              new int[][] {new int[] {FORMAT_HANDLED}, new int[] {FORMAT_UNSUPPORTED_SUBTYPE}},
              new int[][] {new int[] {FORMAT_UNSUPPORTED_DRM, FORMAT_EXCEEDS_CAPABILITIES}}
            },
            new TrackGroupArray(new TrackGroup(new Format.Builder().build())));
    TrackSelection[] selections =
        new TrackSelection[] {
          new FixedTrackSelection(mappedTrackInfo.getTrackGroups(0).get(1), 0),
          new FixedTrackSelection(mappedTrackInfo.getTrackGroups(1).get(0), 1)
        };

    Tracks tracks = TrackSelectionUtil.buildTracks(mappedTrackInfo, selections);

    ImmutableList<Tracks.Group> trackGroups = tracks.getGroups();
    assertThat(trackGroups).hasSize(4);
    assertThat(trackGroups.get(0).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getTrackGroups(0).get(0));
    assertThat(trackGroups.get(1).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getTrackGroups(0).get(1));
    assertThat(trackGroups.get(2).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getTrackGroups(1).get(0));
    assertThat(trackGroups.get(3).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getUnmappedTrackGroups().get(0));
    assertThat(trackGroups.get(0).getTrackSupport(0)).isEqualTo(FORMAT_HANDLED);
    assertThat(trackGroups.get(1).getTrackSupport(0)).isEqualTo(FORMAT_UNSUPPORTED_SUBTYPE);
    assertThat(trackGroups.get(2).getTrackSupport(0)).isEqualTo(FORMAT_UNSUPPORTED_DRM);
    assertThat(trackGroups.get(2).getTrackSupport(1)).isEqualTo(FORMAT_EXCEEDS_CAPABILITIES);
    assertThat(trackGroups.get(3).getTrackSupport(0)).isEqualTo(FORMAT_UNSUPPORTED_TYPE);
    assertThat(trackGroups.get(0).isTrackSelected(0)).isFalse();
    assertThat(trackGroups.get(1).isTrackSelected(0)).isTrue();
    assertThat(trackGroups.get(2).isTrackSelected(0)).isFalse();
    assertThat(trackGroups.get(2).isTrackSelected(1)).isTrue();
    assertThat(trackGroups.get(3).isTrackSelected(0)).isFalse();
    assertThat(trackGroups.get(0).getType()).isEqualTo(TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(1).getType()).isEqualTo(TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(2).getType()).isEqualTo(TRACK_TYPE_VIDEO);
    assertThat(trackGroups.get(3).getType()).isEqualTo(TRACK_TYPE_UNKNOWN);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"}) // Initialization of array of Lists.
  public void buildTrackInfos_withMultipleSelectionForRenderer_isAsExpected() {
    MappingTrackSelector.MappedTrackInfo mappedTrackInfo =
        new MappingTrackSelector.MappedTrackInfo(
            new String[] {"rendererName1", "rendererName2"},
            new int[] {TRACK_TYPE_AUDIO, TRACK_TYPE_VIDEO},
            new TrackGroupArray[] {
              new TrackGroupArray(
                  new TrackGroup("0", new Format.Builder().setSampleMimeType(AUDIO_AAC).build()),
                  new TrackGroup(
                      "1",
                      new Format.Builder().setSampleMimeType(AUDIO_OPUS).setSampleRate(1).build(),
                      new Format.Builder().setSampleMimeType(AUDIO_OPUS).setSampleRate(2).build())),
              new TrackGroupArray()
            },
            new int[] {ADAPTIVE_SEAMLESS, ADAPTIVE_SEAMLESS},
            new int[][][] {
              new int[][] {new int[] {FORMAT_HANDLED}, new int[] {FORMAT_HANDLED, FORMAT_HANDLED}},
              new int[][] {new int[0]}
            },
            new TrackGroupArray());

    List<TrackSelection>[] selections =
        new List[] {
          ImmutableList.of(
              new FixedTrackSelection(mappedTrackInfo.getTrackGroups(0).get(0), 0),
              new FixedTrackSelection(mappedTrackInfo.getTrackGroups(0).get(1), 1)),
          ImmutableList.of()
        };

    Tracks tracks = TrackSelectionUtil.buildTracks(mappedTrackInfo, selections);

    ImmutableList<Tracks.Group> trackGroups = tracks.getGroups();
    assertThat(trackGroups).hasSize(2);
    assertThat(trackGroups.get(0).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getTrackGroups(0).get(0));
    assertThat(trackGroups.get(1).getMediaTrackGroup())
        .isEqualTo(mappedTrackInfo.getTrackGroups(0).get(1));
    assertThat(trackGroups.get(0).getTrackSupport(0)).isEqualTo(FORMAT_HANDLED);
    assertThat(trackGroups.get(1).getTrackSupport(0)).isEqualTo(FORMAT_HANDLED);
    assertThat(trackGroups.get(1).getTrackSupport(1)).isEqualTo(FORMAT_HANDLED);
    assertThat(trackGroups.get(0).isTrackSelected(0)).isTrue();
    assertThat(trackGroups.get(1).isTrackSelected(0)).isFalse();
    assertThat(trackGroups.get(1).isTrackSelected(1)).isTrue();
    assertThat(trackGroups.get(0).getType()).isEqualTo(TRACK_TYPE_AUDIO);
    assertThat(trackGroups.get(1).getType()).isEqualTo(TRACK_TYPE_AUDIO);
  }

  @Test
  public void getMaxVideoSizeInViewport_aspectRatioMatches() {
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ true,
            /* viewportWidth= */ 1920,
            /* viewportHeight= */ 1080,
            /* videoWidth= */ 3840,
            /* videoHeight= */ 2160);

    assertThat(maxVideoSize).isEqualTo(new Point(1920, 1080));
  }

  @Test
  public void getMaxVideoSizeInViewport_rotatedAspectRatioMatches_rotationAllowed() {
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ true,
            /* viewportWidth= */ 1080,
            /* viewportHeight= */ 1920,
            /* videoWidth= */ 3840,
            /* videoHeight= */ 2160);

    assertThat(maxVideoSize).isEqualTo(new Point(1920, 1080));
  }

  @Test
  public void getMaxVideoSizeInViewport_letterboxing() {
    // 16:9 content on 16:10 screen
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ false,
            /* viewportWidth= */ 1920,
            /* viewportHeight= */ 1200,
            /* videoWidth= */ 1280,
            /* videoHeight= */ 720);

    assertThat(maxVideoSize).isEqualTo(new Point(1920, 1080));
  }

  @Test
  public void getMaxVideoSizeInViewport_letterboxingWhenRotated() {
    // 16:9 content on 10:16 screen
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ true,
            /* viewportWidth= */ 1200,
            /* viewportHeight= */ 1920,
            /* videoWidth= */ 1280,
            /* videoHeight= */ 720);

    assertThat(maxVideoSize).isEqualTo(new Point(1920, 1080));
  }

  @Test
  public void getMaxVideoSizeInViewport_pillarboxing() {
    // 4:3 content on 16:10 screen
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ false,
            /* viewportWidth= */ 1920,
            /* viewportHeight= */ 1200,
            /* videoWidth= */ 960,
            /* videoHeight= */ 720);

    assertThat(maxVideoSize).isEqualTo(new Point(1600, 1200));
  }

  @Test
  public void getMaxVideoSizeInViewport_pillarboxingWhenRotated() {
    // 4:3 content on 10:16 screen
    Point maxVideoSize =
        TrackSelectionUtil.getMaxVideoSizeInViewport(
            /* orientationMayChange= */ true,
            /* viewportWidth= */ 1200,
            /* viewportHeight= */ 1920,
            /* videoWidth= */ 960,
            /* videoHeight= */ 720);

    assertThat(maxVideoSize).isEqualTo(new Point(1600, 1200));
  }
}
