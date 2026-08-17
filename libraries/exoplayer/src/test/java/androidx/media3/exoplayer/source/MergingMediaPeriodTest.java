/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static androidx.media3.exoplayer.source.SampleStream.FLAG_REQUIRE_FORMAT;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.TestUtil.assertSubclassOverridesAllMethods;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.NullableType;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link MergingMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class MergingMediaPeriodTest {

  private static final Format childFormat11 = new Format.Builder().setId("1_1").build();
  private static final Format childFormat12 = new Format.Builder().setId("1_2").build();
  private static final Format childFormat21 = new Format.Builder().setId("2_1").build();
  private static final Format childFormat22 = new Format.Builder().setId("2_2").build();

  @Test
  public void mediaPeriod_overridesAllMethods() throws Exception {
    assertSubclassOverridesAllMethods(MediaPeriod.class, MergingMediaPeriod.class);
  }

  @Test
  public void getTrackGroups_returnsAllChildTrackGroupsWithUniqueIds() throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat11, childFormat12),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat21, childFormat22));

    assertThat(mergingMediaPeriod.getTrackGroups().length).isEqualTo(4);
    assertThat(mergingMediaPeriod.getTrackGroups().get(0).getFormat(0).id).isEqualTo("0:1_1");
    assertThat(mergingMediaPeriod.getTrackGroups().get(1).getFormat(0).id).isEqualTo("0:1_2");
    assertThat(mergingMediaPeriod.getTrackGroups().get(2).getFormat(0).id).isEqualTo("1:2_1");
    assertThat(mergingMediaPeriod.getTrackGroups().get(3).getFormat(0).id).isEqualTo("1:2_2");
  }

  @Test
  public void getTrackGroups_withPrimaryTrackGroupIds_keepsMatchingIds() throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* groupIds= */ ImmutableList.of("A", "B"),
                /* timeOffsetUs= */ 0,
                /* singleSampleTimeUs= */ 0,
                childFormat11,
                childFormat12),
            new MergingPeriodDefinition(
                /* groupIds= */ ImmutableList.of("A", "B"),
                /* timeOffsetUs= */ 0,
                /* singleSampleTimeUs= */ 0,
                childFormat21,
                childFormat22.buildUpon().setPrimaryTrackGroupId("A").build()));

    assertThat(mergingMediaPeriod.getTrackGroups().length).isEqualTo(4);
    assertThat(mergingMediaPeriod.getTrackGroups().get(0).id).isEqualTo("0:A");
    assertThat(mergingMediaPeriod.getTrackGroups().get(1).id).isEqualTo("0:B");
    assertThat(mergingMediaPeriod.getTrackGroups().get(2).id).isEqualTo("1:A");
    assertThat(mergingMediaPeriod.getTrackGroups().get(3).id).isEqualTo("1:B");
    assertThat(mergingMediaPeriod.getTrackGroups().get(0).getFormat(0).primaryTrackGroupId)
        .isNull();
    assertThat(mergingMediaPeriod.getTrackGroups().get(1).getFormat(0).primaryTrackGroupId)
        .isNull();
    assertThat(mergingMediaPeriod.getTrackGroups().get(2).getFormat(0).primaryTrackGroupId)
        .isNull();
    assertThat(mergingMediaPeriod.getTrackGroups().get(3).getFormat(0).primaryTrackGroupId)
        .isEqualTo("1:A");
  }

  @Test
  public void selectTracks_createsSampleStreamsFromChildPeriods() throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat11, childFormat12),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat21, childFormat22));

    ExoTrackSelection selectionForChild1 =
        new FixedTrackSelection(mergingMediaPeriod.getTrackGroups().get(1), /* track= */ 0);
    ExoTrackSelection selectionForChild2 =
        new FixedTrackSelection(mergingMediaPeriod.getTrackGroups().get(2), /* track= */ 0);
    SampleStream[] streams = new SampleStream[4];
    mergingMediaPeriod.selectTracks(
        /* selections= */ new ExoTrackSelection[] {
          null, selectionForChild1, selectionForChild2, null
        },
        /* mayRetainStreamFlags= */ new boolean[] {false, false, false, false},
        streams,
        /* streamResetFlags= */ new boolean[] {false, false, false, false},
        /* positionUs= */ 0);
    mergingMediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());

    assertThat(streams[0]).isNull();
    assertThat(streams[3]).isNull();

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    assertThat(streams[1].readData(formatHolder, inputBuffer, FLAG_REQUIRE_FORMAT))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format).isEqualTo(childFormat12);

    assertThat(streams[2].readData(formatHolder, inputBuffer, FLAG_REQUIRE_FORMAT))
        .isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format).isEqualTo(childFormat21);
  }

  @Test
  public void
      selectTracks_withPeriodOffsets_selectTracksWithOffset_andCreatesSampleStreamsCorrectingOffset()
          throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0,
                /* singleSampleTimeUs= */ 123_000,
                childFormat11,
                childFormat12),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ -3000,
                /* singleSampleTimeUs= */ 456_000,
                childFormat21,
                childFormat22));

    ExoTrackSelection selectionForChild1 =
        new FixedTrackSelection(mergingMediaPeriod.getTrackGroups().get(0), /* track= */ 0);
    ExoTrackSelection selectionForChild2 =
        new FixedTrackSelection(mergingMediaPeriod.getTrackGroups().get(2), /* track= */ 0);
    SampleStream[] streams = new SampleStream[2];
    mergingMediaPeriod.selectTracks(
        /* selections= */ new ExoTrackSelection[] {selectionForChild1, selectionForChild2},
        /* mayRetainStreamFlags= */ new boolean[] {false, false},
        streams,
        /* streamResetFlags= */ new boolean[] {false, false},
        /* positionUs= */ 0);
    mergingMediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    streams[0].readData(formatHolder, inputBuffer, FLAG_REQUIRE_FORMAT);
    streams[1].readData(formatHolder, inputBuffer, FLAG_REQUIRE_FORMAT);

    FakeMediaPeriodWithSelectionParameters childMediaPeriod1 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(0);
    assertThat(childMediaPeriod1.selectTracksPositionUs).isEqualTo(0);
    assertThat(streams[0].readData(formatHolder, inputBuffer, /* readFlags= */ 0))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(inputBuffer.timeUs).isEqualTo(123_000L);

    FakeMediaPeriodWithSelectionParameters childMediaPeriod2 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(1);
    assertThat(childMediaPeriod2.selectTracksPositionUs).isEqualTo(3000L);
    assertThat(streams[1].readData(formatHolder, inputBuffer, /* readFlags= */ 0))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(inputBuffer.timeUs).isEqualTo(456_000 - 3000);
  }

  @Test
  public void selectTracks_withSameArguments_forwardsEqualSelectionsToChildSources()
      throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat11),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat22));
    FakeMediaPeriodWithSelectionParameters childMediaPeriod1 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(0);
    FakeMediaPeriodWithSelectionParameters childMediaPeriod2 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(1);

    TrackGroupArray mergedTrackGroups = mergingMediaPeriod.getTrackGroups();
    ExoTrackSelection[] selectionArray =
        new ExoTrackSelection[] {
          new FixedTrackSelection(mergedTrackGroups.get(0), /* track= */ 0),
          new FixedTrackSelection(mergedTrackGroups.get(1), /* track= */ 0)
        };

    mergingMediaPeriod.selectTracks(
        selectionArray,
        /* mayRetainStreamFlags= */ new boolean[2],
        /* streams= */ new SampleStream[2],
        /* streamResetFlags= */ new boolean[2],
        /* positionUs= */ 0);
    ExoTrackSelection firstSelectionChild1 = childMediaPeriod1.selectTracksSelections[0];
    ExoTrackSelection firstSelectionChild2 = childMediaPeriod2.selectTracksSelections[1];

    mergingMediaPeriod.selectTracks(
        selectionArray,
        /* mayRetainStreamFlags= */ new boolean[2],
        /* streams= */ new SampleStream[2],
        /* streamResetFlags= */ new boolean[2],
        /* positionUs= */ 0);
    ExoTrackSelection secondSelectionChild1 = childMediaPeriod1.selectTracksSelections[0];
    ExoTrackSelection secondSelectionChild2 = childMediaPeriod2.selectTracksSelections[1];

    assertThat(firstSelectionChild1).isEqualTo(secondSelectionChild1);
    assertThat(firstSelectionChild2).isEqualTo(secondSelectionChild2);
  }

  @Test
  public void selectTracks_forwardsSelectionsWithChildIdsToChildSources() throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            /* singleTrackGroup= */ true,
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat11, childFormat12),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 0, childFormat21, childFormat22));
    FakeMediaPeriodWithSelectionParameters childMediaPeriod1 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(0);
    FakeMediaPeriodWithSelectionParameters childMediaPeriod2 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(1);

    TrackGroupArray mergedTrackGroups = mergingMediaPeriod.getTrackGroups();
    ExoTrackSelection[] selectionArray =
        new ExoTrackSelection[] {
          new FixedTrackSelection(mergedTrackGroups.get(0), /* track= */ 0),
          new FixedTrackSelection(mergedTrackGroups.get(1), /* track= */ 1)
        };

    mergingMediaPeriod.selectTracks(
        selectionArray,
        /* mayRetainStreamFlags= */ new boolean[2],
        /* streams= */ new SampleStream[2],
        /* streamResetFlags= */ new boolean[2],
        /* positionUs= */ 0);
    ExoTrackSelection selectionChild1 = childMediaPeriod1.selectTracksSelections[0];
    ExoTrackSelection selectionChild2 = childMediaPeriod2.selectTracksSelections[1];

    assertThat(selectionChild1.getSelectedFormat()).isEqualTo(childFormat11);
    assertThat(selectionChild2.getSelectedFormat()).isEqualTo(childFormat22);
    assertThat(selectionChild1.getFormat(/* index= */ 0)).isEqualTo(childFormat11);
    assertThat(selectionChild2.getFormat(/* index= */ 0)).isEqualTo(childFormat22);
    assertThat(selectionChild1.indexOf(childFormat11)).isEqualTo(0);
    assertThat(selectionChild2.indexOf(childFormat22)).isEqualTo(0);
  }

  @Test
  public void selectTracks_withPrimaryTrackGroupIds_forwardsSelectionsWithChildIdsToChildSources()
      throws Exception {
    Format adjustedChildFormat11 = childFormat11.buildUpon().setPrimaryTrackGroupId("B").build();
    Format adjustedChildFormat22 = childFormat22.buildUpon().setPrimaryTrackGroupId("A").build();
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* groupIds= */ ImmutableList.of("A", "B"),
                /* timeOffsetUs= */ 0,
                /* singleSampleTimeUs= */ 0,
                adjustedChildFormat11,
                childFormat12),
            new MergingPeriodDefinition(
                /* groupIds= */ ImmutableList.of("A", "B"),
                /* timeOffsetUs= */ 0,
                /* singleSampleTimeUs= */ 0,
                childFormat21,
                adjustedChildFormat22));
    FakeMediaPeriodWithSelectionParameters childMediaPeriod1 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(0);
    FakeMediaPeriodWithSelectionParameters childMediaPeriod2 =
        (FakeMediaPeriodWithSelectionParameters) mergingMediaPeriod.getChildPeriod(1);
    TrackGroupArray mergedTrackGroups = mergingMediaPeriod.getTrackGroups();
    ExoTrackSelection[] selectionArray =
        new ExoTrackSelection[] {
          new FixedTrackSelection(mergedTrackGroups.get(0), /* track= */ 0),
          new FixedTrackSelection(mergedTrackGroups.get(1), /* track= */ 0),
          new FixedTrackSelection(mergedTrackGroups.get(2), /* track= */ 0),
          new FixedTrackSelection(mergedTrackGroups.get(3), /* track= */ 0)
        };

    mergingMediaPeriod.selectTracks(
        selectionArray,
        /* mayRetainStreamFlags= */ new boolean[4],
        /* streams= */ new SampleStream[4],
        /* streamResetFlags= */ new boolean[4],
        /* positionUs= */ 0);

    ExoTrackSelection[] selectionsChild1 = childMediaPeriod1.selectTracksSelections;
    ExoTrackSelection[] selectionsChild2 = childMediaPeriod2.selectTracksSelections;
    assertThat(selectionsChild1[0].getSelectedFormat()).isEqualTo(adjustedChildFormat11);
    assertThat(selectionsChild1[1].getSelectedFormat()).isEqualTo(childFormat12);
    assertThat(selectionsChild2[2].getSelectedFormat()).isEqualTo(childFormat21);
    assertThat(selectionsChild2[3].getSelectedFormat()).isEqualTo(adjustedChildFormat22);
    assertThat(selectionsChild1[0].getFormat(/* index= */ 0)).isEqualTo(adjustedChildFormat11);
    assertThat(selectionsChild1[1].getFormat(/* index= */ 0)).isEqualTo(childFormat12);
    assertThat(selectionsChild2[2].getFormat(/* index= */ 0)).isEqualTo(childFormat21);
    assertThat(selectionsChild2[3].getFormat(/* index= */ 0)).isEqualTo(adjustedChildFormat22);
    assertThat(selectionsChild1[0].indexOf(adjustedChildFormat11)).isEqualTo(0);
    assertThat(selectionsChild1[1].indexOf(childFormat12)).isEqualTo(0);
    assertThat(selectionsChild2[2].indexOf(childFormat21)).isEqualTo(0);
    assertThat(selectionsChild2[3].indexOf(adjustedChildFormat22)).isEqualTo(0);
  }

  // https://github.com/google/ExoPlayer/issues/10930
  @Test
  public void selectTracks_withIdenticalFormats_selectsMatchingPeriod() throws Exception {
    MergingMediaPeriod mergingMediaPeriod =
        prepareMergingPeriod(
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ 0, /* singleSampleTimeUs= */ 123_000, childFormat11),
            new MergingPeriodDefinition(
                /* timeOffsetUs= */ -3000, /* singleSampleTimeUs= */ 456_000, childFormat11));

    ExoTrackSelection[] selectionArray = {
      new FixedTrackSelection(mergingMediaPeriod.getTrackGroups().get(1), /* track= */ 0)
    };

    SampleStream[] streams = new SampleStream[1];
    mergingMediaPeriod.selectTracks(
        selectionArray,
        /* mayRetainStreamFlags= */ new boolean[2],
        streams,
        /* streamResetFlags= */ new boolean[2],
        /* positionUs= */ 0);
    mergingMediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());

    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer inputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    streams[0].readData(formatHolder, inputBuffer, FLAG_REQUIRE_FORMAT);

    assertThat(streams[0].readData(formatHolder, inputBuffer, /* readFlags= */ 0))
        .isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(inputBuffer.timeUs).isEqualTo(456_000 - 3000);
  }

  @Test
  public void
      getChildPeriod_withTimeOffsetsAndTimeOffsetPeriodChildren_returnsCorrectChildPeriod() {
    TrackGroupArray trackGroupArray =
        new TrackGroupArray(
            new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build()));
    Allocator allocator =
        new DefaultAllocator(/* trimOnReset= */ false, /* individualAllocationSize= */ 1024);
    MediaPeriod childPeriod0 =
        new FakeMediaPeriod(
            trackGroupArray, allocator, /* singleSampleTimeUs= */ 0, new EventDispatcher());
    MediaPeriod childPeriod1 =
        new TimeOffsetMediaPeriod(
            new FakeMediaPeriod(
                trackGroupArray, allocator, /* singleSampleTimeUs= */ 300, new EventDispatcher()),
            /* timeOffsetUs= */ -300);
    MediaPeriod childPeriod2 =
        new FakeMediaPeriod(
            trackGroupArray, allocator, /* singleSampleTimeUs= */ -500, new EventDispatcher());

    MergingMediaPeriod mergingMediaPeriod =
        new MergingMediaPeriod(
            new DefaultCompositeSequenceableLoaderFactory(),
            /* periodTimeOffsetsUs= */ new long[] {0, 0, 500},
            childPeriod0,
            childPeriod1,
            childPeriod2);

    assertThat(mergingMediaPeriod.getChildPeriod(0)).isEqualTo(childPeriod0);
    assertThat(mergingMediaPeriod.getChildPeriod(1)).isEqualTo(childPeriod1);
    assertThat(mergingMediaPeriod.getChildPeriod(2)).isEqualTo(childPeriod2);
  }

  private MergingMediaPeriod prepareMergingPeriod(MergingPeriodDefinition... definitions)
      throws Exception {
    return prepareMergingPeriod(/* singleTrackGroup= */ false, definitions);
  }

  private MergingMediaPeriod prepareMergingPeriod(
      boolean singleTrackGroup, MergingPeriodDefinition... definitions) throws Exception {
    MediaPeriod[] mediaPeriods = new MediaPeriod[definitions.length];
    long[] timeOffsetsUs = new long[definitions.length];
    for (int i = 0; i < definitions.length; i++) {
      MergingPeriodDefinition definition = definitions[i];
      timeOffsetsUs[i] = definition.timeOffsetUs;
      TrackGroup[] trackGroups;
      if (singleTrackGroup) {
        trackGroups =
            new TrackGroup[] {new TrackGroup(definition.groupIds.get(0), definition.formats)};
      } else {
        trackGroups = new TrackGroup[definition.formats.length];
        for (int j = 0; j < definition.formats.length; j++) {
          trackGroups[j] = new TrackGroup(definition.groupIds.get(j), definition.formats[j]);
        }
      }
      mediaPeriods[i] =
          new FakeMediaPeriodWithSelectionParameters(
              new TrackGroupArray(trackGroups),
              new EventDispatcher()
                  .withParameters(/* windowIndex= */ i, new MediaPeriodId(/* periodUid= */ i)),
              /* trackDataFactory= */ (unusedFormat, unusedMediaPeriodId) ->
                  ImmutableList.of(
                      oneByteSample(definition.singleSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME),
                      END_OF_STREAM_ITEM));
    }
    MergingMediaPeriod mergingMediaPeriod =
        new MergingMediaPeriod(
            new DefaultCompositeSequenceableLoaderFactory(), timeOffsetsUs, mediaPeriods);

    CountDownLatch prepareCountDown = new CountDownLatch(1);
    mergingMediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            prepareCountDown.countDown();
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {
            mergingMediaPeriod.continueLoading(
                new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
          }
        },
        /* positionUs= */ 0);
    prepareCountDown.await();

    return mergingMediaPeriod;
  }

  private static final class FakeMediaPeriodWithSelectionParameters extends FakeMediaPeriod {

    public @NullableType ExoTrackSelection[] selectTracksSelections;
    public long selectTracksPositionUs;

    public FakeMediaPeriodWithSelectionParameters(
        TrackGroupArray trackGroupArray,
        EventDispatcher mediaSourceEventDispatcher,
        TrackDataFactory trackDataFactory) {
      super(
          trackGroupArray,
          new DefaultAllocator(/* trimOnReset= */ false, /* individualAllocationSize= */ 1024),
          trackDataFactory,
          mediaSourceEventDispatcher,
          DrmSessionManager.DRM_UNSUPPORTED,
          new DrmSessionEventListener.EventDispatcher(),
          /* deferOnPrepared= */ false);
      selectTracksSelections = new ExoTrackSelection[trackGroupArray.length];
      selectTracksPositionUs = C.TIME_UNSET;
    }

    @Override
    public long selectTracks(
        @NullableType ExoTrackSelection[] selections,
        boolean[] mayRetainStreamFlags,
        @NullableType SampleStream[] streams,
        boolean[] streamResetFlags,
        long positionUs) {
      selectTracksSelections = Arrays.copyOf(selections, selections.length);
      selectTracksPositionUs = positionUs;
      return super.selectTracks(
          selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs);
    }
  }

  private static final class MergingPeriodDefinition {

    public final ImmutableList<String> groupIds;
    public final long timeOffsetUs;
    public final long singleSampleTimeUs;
    public final Format[] formats;

    public MergingPeriodDefinition(long timeOffsetUs, long singleSampleTimeUs, Format... formats) {
      this(ImmutableList.of(), timeOffsetUs, singleSampleTimeUs, formats);
    }

    public MergingPeriodDefinition(
        ImmutableList<String> groupIds,
        long timeOffsetUs,
        long singleSampleTimeUs,
        Format... formats) {
      if (groupIds.isEmpty()) {
        ImmutableList.Builder<String> groupIdBuilder = ImmutableList.builder();
        for (int i = 0; i < formats.length; i++) {
          groupIdBuilder.add("");
        }
        groupIds = groupIdBuilder.build();
      }
      this.groupIds = groupIds;
      this.timeOffsetUs = timeOffsetUs;
      this.singleSampleTimeUs = singleSampleTimeUs;
      this.formats = formats;
    }
  }
}
