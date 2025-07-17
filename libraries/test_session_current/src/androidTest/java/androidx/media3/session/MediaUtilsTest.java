/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.session;

import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.common.MimeTypes.VIDEO_H265;
import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.session.PlayerInfo.BundlingExclusions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaUtils}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class MediaUtilsTest {

  @Test
  public void truncateListBySize() {
    List<Bundle> bundleList = new ArrayList<>();
    Bundle testBundle = new Bundle();
    testBundle.putString("key", "value");

    Parcel p = Parcel.obtain();
    p.writeParcelable(testBundle, 0);
    int bundleSize = p.dataSize();
    p.recycle();

    bundleList.addAll(Collections.nCopies(10, testBundle));

    for (int i = 0; i < 5; i++) {
      assertThat(MediaUtils.truncateListBySize(bundleList, bundleSize * i + 1)).hasSize(i);
    }
  }

  @Test
  public void mergePlayerInfo_timelineAndTracksExcluded_correctMerge() {
    Timeline timeline =
        new Timeline.RemotableTimeline(
            ImmutableList.of(new Timeline.Window()),
            ImmutableList.of(new Timeline.Period()),
            /* shuffledWindowIndices= */ new int[] {0});
    Tracks tracks =
        new Tracks(
            ImmutableList.of(
                new Tracks.Group(
                    new TrackGroup(new Format.Builder().setSampleMimeType(AUDIO_AAC).build()),
                    /* adaptiveSupported= */ false,
                    new int[] {C.FORMAT_EXCEEDS_CAPABILITIES},
                    /* trackSelected= */ new boolean[] {true}),
                new Tracks.Group(
                    new TrackGroup(
                        new Format.Builder().setSampleMimeType(VIDEO_H264).build(),
                        new Format.Builder().setSampleMimeType(VIDEO_H265).build()),
                    /* adaptiveSupported= */ true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE},
                    /* trackSelected= */ new boolean[] {false, true})));
    PlayerInfo oldPlayerInfo =
        PlayerInfo.DEFAULT.copyWithCurrentTracks(tracks).copyWithTimeline(timeline);
    PlayerInfo newPlayerInfo = PlayerInfo.DEFAULT;
    Player.Commands availableCommands =
        Player.Commands.EMPTY
            .buildUpon()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_TRACKS)
            .build();

    PlayerInfo mergeResult =
        MediaUtils.mergePlayerInfo(
            oldPlayerInfo,
            newPlayerInfo,
            new BundlingExclusions(
                /* isTimelineExcluded= */ true, /* areCurrentTracksExcluded= */ true),
            availableCommands,
            /* keepOldUnmuteVolumeForMutedSessions= */ false);

    assertThat(mergeResult.timeline).isSameInstanceAs(oldPlayerInfo.timeline);
    assertThat(mergeResult.currentTracks).isSameInstanceAs(oldPlayerInfo.currentTracks);
  }

  @Test
  public void mergePlayerInfo_getTimelineCommandNotAvailable_emptyTimeline() {
    Timeline timeline =
        new Timeline.RemotableTimeline(
            ImmutableList.of(new Timeline.Window()),
            ImmutableList.of(new Timeline.Period()),
            /* shuffledWindowIndices= */ new int[] {0});
    Tracks tracks =
        new Tracks(
            ImmutableList.of(
                new Tracks.Group(
                    new TrackGroup(new Format.Builder().setSampleMimeType(AUDIO_AAC).build()),
                    /* adaptiveSupported= */ false,
                    new int[] {C.FORMAT_EXCEEDS_CAPABILITIES},
                    /* trackSelected= */ new boolean[] {true}),
                new Tracks.Group(
                    new TrackGroup(
                        new Format.Builder().setSampleMimeType(VIDEO_H264).build(),
                        new Format.Builder().setSampleMimeType(VIDEO_H265).build()),
                    /* adaptiveSupported= */ true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE},
                    /* trackSelected= */ new boolean[] {false, true})));
    PlayerInfo oldPlayerInfo =
        PlayerInfo.DEFAULT.copyWithCurrentTracks(tracks).copyWithTimeline(timeline);
    PlayerInfo newPlayerInfo = PlayerInfo.DEFAULT;
    Player.Commands availableCommands =
        Player.Commands.EMPTY.buildUpon().add(Player.COMMAND_GET_TRACKS).build();

    PlayerInfo mergeResult =
        MediaUtils.mergePlayerInfo(
            oldPlayerInfo,
            newPlayerInfo,
            new BundlingExclusions(
                /* isTimelineExcluded= */ true, /* areCurrentTracksExcluded= */ true),
            availableCommands,
            /* keepOldUnmuteVolumeForMutedSessions= */ false);

    assertThat(mergeResult.timeline).isSameInstanceAs(Timeline.EMPTY);
    assertThat(mergeResult.currentTracks).isSameInstanceAs(oldPlayerInfo.currentTracks);
  }

  @Test
  public void mergePlayerInfo_getTracksCommandNotAvailable_emptyTracks() {
    Timeline timeline =
        new Timeline.RemotableTimeline(
            ImmutableList.of(new Timeline.Window()),
            ImmutableList.of(new Timeline.Period()),
            /* shuffledWindowIndices= */ new int[] {0});
    Tracks tracks =
        new Tracks(
            ImmutableList.of(
                new Tracks.Group(
                    new TrackGroup(new Format.Builder().setSampleMimeType(AUDIO_AAC).build()),
                    /* adaptiveSupported= */ false,
                    new int[] {C.FORMAT_EXCEEDS_CAPABILITIES},
                    /* trackSelected= */ new boolean[] {true}),
                new Tracks.Group(
                    new TrackGroup(
                        new Format.Builder().setSampleMimeType(VIDEO_H264).build(),
                        new Format.Builder().setSampleMimeType(VIDEO_H265).build()),
                    /* adaptiveSupported= */ true,
                    new int[] {C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE},
                    /* trackSelected= */ new boolean[] {false, true})));
    PlayerInfo oldPlayerInfo =
        PlayerInfo.DEFAULT.copyWithCurrentTracks(tracks).copyWithTimeline(timeline);
    PlayerInfo newPlayerInfo = PlayerInfo.DEFAULT;
    Player.Commands availableCommands =
        Player.Commands.EMPTY.buildUpon().add(Player.COMMAND_GET_TIMELINE).build();

    PlayerInfo mergeResult =
        MediaUtils.mergePlayerInfo(
            oldPlayerInfo,
            newPlayerInfo,
            new BundlingExclusions(
                /* isTimelineExcluded= */ true, /* areCurrentTracksExcluded= */ true),
            availableCommands,
            /* keepOldUnmuteVolumeForMutedSessions= */ false);

    assertThat(mergeResult.timeline).isSameInstanceAs(oldPlayerInfo.timeline);
    assertThat(mergeResult.currentTracks).isSameInstanceAs(Tracks.EMPTY);
  }

  @Test
  public void mergePlayerInfo_keepOldUnmuteVolumeForMutedSessions_correctMerge() {
    PlayerInfo oldPlayerInfo = PlayerInfo.DEFAULT.copyWithVolume(0.7f);
    PlayerInfo newPlayerInfo = PlayerInfo.DEFAULT.copyWithVolume(0f).copyWithUnmuteVolume(0.5f);
    Player.Commands availableCommands =
        Player.Commands.EMPTY
            .buildUpon()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_TRACKS)
            .build();

    PlayerInfo mergeResult =
        MediaUtils.mergePlayerInfo(
            oldPlayerInfo,
            newPlayerInfo,
            BundlingExclusions.NONE,
            availableCommands,
            /* keepOldUnmuteVolumeForMutedSessions= */ true);

    assertThat(mergeResult.volume).isEqualTo(newPlayerInfo.volume);
    assertThat(mergeResult.unmuteVolume).isEqualTo(oldPlayerInfo.unmuteVolume);
  }

  @Test
  public void mergePlayerInfo_keepOldUnmuteVolumeForUnmutedSessions_correctMerge() {
    PlayerInfo oldPlayerInfo = PlayerInfo.DEFAULT.copyWithVolume(0.7f);
    PlayerInfo newPlayerInfo = PlayerInfo.DEFAULT.copyWithVolume(0.5f); // unmuteVolume=0.5 as well
    Player.Commands availableCommands =
        Player.Commands.EMPTY
            .buildUpon()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_TRACKS)
            .build();

    PlayerInfo mergeResult =
        MediaUtils.mergePlayerInfo(
            oldPlayerInfo,
            newPlayerInfo,
            BundlingExclusions.NONE,
            availableCommands,
            /* keepOldUnmuteVolumeForMutedSessions= */ true);

    assertThat(mergeResult.volume).isEqualTo(newPlayerInfo.volume);
    assertThat(mergeResult.unmuteVolume).isEqualTo(newPlayerInfo.unmuteVolume);
  }
}
