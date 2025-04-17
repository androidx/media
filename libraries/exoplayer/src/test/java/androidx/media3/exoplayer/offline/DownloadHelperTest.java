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
package androidx.media3.exoplayer.offline;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback;
import androidx.media3.exoplayer.offline.DownloadHelper.Callback;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DownloadHelper}. */
@RunWith(AndroidJUnit4.class)
public class DownloadHelperTest {

  private static final Object TEST_MANIFEST = new Object();

  private static final long TEST_WINDOW_DEFAULT_POSITION_US = C.MICROS_PER_SECOND;
  private static final Timeline TEST_TIMELINE =
      new FakeTimeline(
          new Object[] {TEST_MANIFEST},
          new TimelineWindowDefinition.Builder()
              .setPeriodCount(2)
              .setDefaultPositionUs(TEST_WINDOW_DEFAULT_POSITION_US)
              .build());

  private static TrackGroup trackGroupVideoLow;
  private static TrackGroup trackGroupVideoLowAndHigh;
  private static TrackGroup trackGroupAudioUs;
  private static TrackGroup trackGroupAudioZh;
  private static TrackGroup trackGroupTextUs;
  private static TrackGroup trackGroupTextZh;
  private static TrackGroupArray[] trackGroupArrays;
  private static MediaItem testMediaItem;

  private RenderersFactory renderersFactory;
  private DownloadHelper downloadHelper;

  @BeforeClass
  public static void staticSetUp() {
    Format videoFormatLow = createVideoFormat(/* bitrate= */ 200_000);
    Format videoFormatHigh = createVideoFormat(/* bitrate= */ 800_000);
    Format audioFormatEn = createAudioFormat(/* language= */ "en");
    Format audioFormatDe = createAudioFormat(/* language= */ "de");
    Format textFormatEn = createTextFormat(/* language= */ "en");
    Format textFormatDe = createTextFormat(/* language= */ "de");

    trackGroupVideoLow = new TrackGroup(videoFormatLow);
    trackGroupVideoLowAndHigh = new TrackGroup(videoFormatLow, videoFormatHigh);
    trackGroupAudioUs = new TrackGroup(audioFormatEn);
    trackGroupAudioZh = new TrackGroup(audioFormatDe);
    trackGroupTextUs = new TrackGroup(textFormatEn);
    trackGroupTextZh = new TrackGroup(textFormatDe);

    TrackGroupArray trackGroupArrayAll =
        new TrackGroupArray(
            trackGroupVideoLowAndHigh,
            trackGroupAudioUs,
            trackGroupAudioZh,
            trackGroupTextUs,
            trackGroupTextZh);
    TrackGroupArray trackGroupArraySingle =
        new TrackGroupArray(trackGroupVideoLow, trackGroupAudioUs);
    trackGroupArrays = new TrackGroupArray[] {trackGroupArrayAll, trackGroupArraySingle};

    testMediaItem =
        new MediaItem.Builder().setUri("http://test.uri").setCustomCacheKey("cacheKey").build();
  }

  @Before
  public void setUp() {
    FakeRenderer videoRenderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
    FakeRenderer audioRenderer = new FakeRenderer(C.TRACK_TYPE_AUDIO);
    FakeRenderer textRenderer = new FakeRenderer(C.TRACK_TYPE_TEXT);
    renderersFactory =
        (handler, videoListener, audioListener, metadata, text) ->
            new Renderer[] {textRenderer, audioRenderer, videoRenderer};

    downloadHelper =
        new DownloadHelper(
            testMediaItem,
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
  }

  @Test
  public void prepare_withoutMediaSource_tracksInfoNotAvailable() throws Exception {
    // DownloadHelper will be constructed without MediaSource if no DataSource.Factory is provided.
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory().create(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));

    boolean tracksInfoAvailable = prepareDownloadHelper(downloadHelper);

    assertThat(tracksInfoAvailable).isFalse();
  }

  @Test
  public void prepare_prepareProgressiveSource_tracksInfoNotAvailable() throws Exception {
    Context context = getApplicationContext();
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(context))
            .create(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));

    boolean tracksInfoAvailable = prepareDownloadHelper(downloadHelper);

    assertThat(tracksInfoAvailable).isFalse();
  }

  @Test
  public void prepare_prepareNonProgressiveSource_tracksInfoAvailable() throws Exception {
    // We use this.downloadHelper as it was created with a TestMediaSource, thus the DownloadHelper
    // will treat it as non-progressive.
    boolean tracksInfoAvailable = prepareDownloadHelper(downloadHelper);

    assertThat(tracksInfoAvailable).isTrue();
  }

  @Test
  public void getManifest_returnsManifest() throws Exception {
    prepareDownloadHelper(downloadHelper);

    assertThat(downloadHelper.getManifest()).isEqualTo(TEST_MANIFEST);
  }

  @Test
  public void getPeriodCount_returnsPeriodCount() throws Exception {
    prepareDownloadHelper(downloadHelper);

    assertThat(downloadHelper.getPeriodCount()).isEqualTo(2);
  }

  @Test
  public void getTrackGroups_returnsTrackGroups() throws Exception {
    prepareDownloadHelper(downloadHelper);

    TrackGroupArray trackGroupArrayPeriod0 = downloadHelper.getTrackGroups(/* periodIndex= */ 0);
    TrackGroupArray trackGroupArrayPeriod1 = downloadHelper.getTrackGroups(/* periodIndex= */ 1);

    assertThat(trackGroupArrayPeriod0).isEqualTo(trackGroupArrays[0]);
    assertThat(trackGroupArrayPeriod1).isEqualTo(trackGroupArrays[1]);
  }

  @Test
  public void getMappedTrackInfo_returnsMappedTrackInfo() throws Exception {
    prepareDownloadHelper(downloadHelper);

    MappedTrackInfo mappedTracks0 = downloadHelper.getMappedTrackInfo(/* periodIndex= */ 0);
    MappedTrackInfo mappedTracks1 = downloadHelper.getMappedTrackInfo(/* periodIndex= */ 1);

    assertThat(mappedTracks0.getRendererCount()).isEqualTo(3);
    assertThat(mappedTracks0.getRendererType(/* rendererIndex= */ 0)).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(mappedTracks0.getRendererType(/* rendererIndex= */ 1)).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(mappedTracks0.getRendererType(/* rendererIndex= */ 2)).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 0).length).isEqualTo(2);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 1).length).isEqualTo(2);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 2).length).isEqualTo(1);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 0).get(/* index= */ 0))
        .isEqualTo(trackGroupTextUs);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 0).get(/* index= */ 1))
        .isEqualTo(trackGroupTextZh);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 1).get(/* index= */ 0))
        .isEqualTo(trackGroupAudioUs);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 1).get(/* index= */ 1))
        .isEqualTo(trackGroupAudioZh);
    assertThat(mappedTracks0.getTrackGroups(/* rendererIndex= */ 2).get(/* index= */ 0))
        .isEqualTo(trackGroupVideoLowAndHigh);

    assertThat(mappedTracks1.getRendererCount()).isEqualTo(3);
    assertThat(mappedTracks1.getRendererType(/* rendererIndex= */ 0)).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(mappedTracks1.getRendererType(/* rendererIndex= */ 1)).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(mappedTracks1.getRendererType(/* rendererIndex= */ 2)).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(mappedTracks1.getTrackGroups(/* rendererIndex= */ 0).length).isEqualTo(0);
    assertThat(mappedTracks1.getTrackGroups(/* rendererIndex= */ 1).length).isEqualTo(1);
    assertThat(mappedTracks1.getTrackGroups(/* rendererIndex= */ 2).length).isEqualTo(1);
    assertThat(mappedTracks1.getTrackGroups(/* rendererIndex= */ 1).get(/* index= */ 0))
        .isEqualTo(trackGroupAudioUs);
    assertThat(mappedTracks1.getTrackGroups(/* rendererIndex= */ 2).get(/* index= */ 0))
        .isEqualTo(trackGroupVideoLow);
  }

  @Test
  public void getTrackSelections_returnsInitialSelection() throws Exception {
    prepareDownloadHelper(downloadHelper);

    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertSingleTrackSelectionEquals(selectedText0, trackGroupTextUs, 0);
    assertSingleTrackSelectionEquals(selectedAudio0, trackGroupAudioUs, 0);
    assertSingleTrackSelectionEquals(selectedVideo0, trackGroupVideoLowAndHigh, 1);

    assertThat(selectedText1).isEmpty();
    assertSingleTrackSelectionEquals(selectedAudio1, trackGroupAudioUs, 0);
    assertSingleTrackSelectionEquals(selectedVideo1, trackGroupVideoLow, 0);
  }

  @Test
  public void getTrackSelections_afterClearTrackSelections_isEmpty() throws Exception {
    prepareDownloadHelper(downloadHelper);

    // Clear only one period selection to verify second period selection is untouched.
    downloadHelper.clearTrackSelections(/* periodIndex= */ 0);
    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertThat(selectedText0).isEmpty();
    assertThat(selectedAudio0).isEmpty();
    assertThat(selectedVideo0).isEmpty();

    // Verify
    assertThat(selectedText1).isEmpty();
    assertSingleTrackSelectionEquals(selectedAudio1, trackGroupAudioUs, 0);
    assertSingleTrackSelectionEquals(selectedVideo1, trackGroupVideoLow, 0);
  }

  @Test
  public void getTrackSelections_afterReplaceTrackSelections_returnsNewSelections()
      throws Exception {
    prepareDownloadHelper(downloadHelper);
    DefaultTrackSelector.Parameters parameters =
        new DefaultTrackSelector.Parameters.Builder()
            .setPreferredAudioLanguage("de")
            .setPreferredTextLanguage("de")
            .setRendererDisabled(/* rendererIndex= */ 2, true)
            .build();

    // Replace only one period selection to verify second period selection is untouched.
    downloadHelper.replaceTrackSelections(/* periodIndex= */ 0, parameters);
    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertSingleTrackSelectionEquals(selectedText0, trackGroupTextZh, 0);
    assertSingleTrackSelectionEquals(selectedAudio0, trackGroupAudioZh, 0);
    assertThat(selectedVideo0).isEmpty();

    assertThat(selectedText1).isEmpty();
    assertSingleTrackSelectionEquals(selectedAudio1, trackGroupAudioUs, 0);
    assertSingleTrackSelectionEquals(selectedVideo1, trackGroupVideoLow, 0);
  }

  @Test
  public void getTrackSelections_afterAddTrackSelections_returnsCombinedSelections()
      throws Exception {
    prepareDownloadHelper(downloadHelper);
    // Select parameters to require some merging of track groups because the new parameters add
    // all video tracks to initial video single track selection.
    DefaultTrackSelector.Parameters parameters =
        new DefaultTrackSelector.Parameters.Builder()
            .setPreferredAudioLanguage("de")
            .setPreferredTextLanguage("en")
            .build();

    // Add only to one period selection to verify second period selection is untouched.
    downloadHelper.addTrackSelection(/* periodIndex= */ 0, parameters);
    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertSingleTrackSelectionEquals(selectedText0, trackGroupTextUs, 0);
    assertThat(selectedAudio0).hasSize(2);
    assertTrackSelectionEquals(selectedAudio0.get(0), trackGroupAudioUs, 0);
    assertTrackSelectionEquals(selectedAudio0.get(1), trackGroupAudioZh, 0);
    assertSingleTrackSelectionEquals(selectedVideo0, trackGroupVideoLowAndHigh, 0, 1);

    assertThat(selectedText1).isEmpty();
    assertSingleTrackSelectionEquals(selectedAudio1, trackGroupAudioUs, 0);
    assertSingleTrackSelectionEquals(selectedVideo1, trackGroupVideoLow, 0);
  }

  @Test
  public void getTrackSelections_afterAddAudioLanguagesToSelection_returnsCombinedSelections()
      throws Exception {
    prepareDownloadHelper(downloadHelper);
    downloadHelper.clearTrackSelections(/* periodIndex= */ 0);
    downloadHelper.clearTrackSelections(/* periodIndex= */ 1);

    // Add a non-default language, and a non-existing language (which will select the default).
    downloadHelper.addAudioLanguagesToSelection("de", "Klingonese");
    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertThat(selectedVideo0).isEmpty();
    assertThat(selectedText0).isEmpty();
    assertThat(selectedAudio0).hasSize(2);
    assertTrackSelectionEquals(selectedAudio0.get(0), trackGroupAudioZh, 0);
    assertTrackSelectionEquals(selectedAudio0.get(1), trackGroupAudioUs, 0);

    assertThat(selectedVideo1).isEmpty();
    assertThat(selectedText1).isEmpty();
    assertSingleTrackSelectionEquals(selectedAudio1, trackGroupAudioUs, 0);
  }

  @Test
  public void getTrackSelections_afterAddTextLanguagesToSelection_returnsCombinedSelections()
      throws Exception {
    prepareDownloadHelper(downloadHelper);
    downloadHelper.clearTrackSelections(/* periodIndex= */ 0);
    downloadHelper.clearTrackSelections(/* periodIndex= */ 1);

    // Add a non-default language, and a non-existing language (which will select the default).
    downloadHelper.addTextLanguagesToSelection(
        /* selectUndeterminedTextLanguage= */ true, "de", "Klingonese");
    List<ExoTrackSelection> selectedText0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo0 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 0, /* rendererIndex= */ 2);
    List<ExoTrackSelection> selectedText1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 0);
    List<ExoTrackSelection> selectedAudio1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 1);
    List<ExoTrackSelection> selectedVideo1 =
        downloadHelper.getTrackSelections(/* periodIndex= */ 1, /* rendererIndex= */ 2);

    assertThat(selectedVideo0).isEmpty();
    assertThat(selectedAudio0).isEmpty();
    assertThat(selectedText0).hasSize(2);
    assertTrackSelectionEquals(selectedText0.get(0), trackGroupTextZh, 0);
    assertTrackSelectionEquals(selectedText0.get(1), trackGroupTextUs, 0);

    assertThat(selectedVideo1).isEmpty();
    assertThat(selectedAudio1).isEmpty();
    assertThat(selectedText1).isEmpty();
  }

  @Test
  public void getDownloadRequest_createsDownloadRequest_withAllSelectedTracks() throws Exception {
    prepareDownloadHelper(downloadHelper);
    // Ensure we have track groups with multiple indices, renderers with multiple track groups and
    // also renderers without any track groups.
    DefaultTrackSelector.Parameters parameters =
        new DefaultTrackSelector.Parameters.Builder()
            .setPreferredAudioLanguage("de")
            .setPreferredTextLanguage("en")
            .build();
    downloadHelper.addTrackSelection(/* periodIndex= */ 0, parameters);
    byte[] data = TestUtil.buildTestData(10);

    DownloadRequest downloadRequest = downloadHelper.getDownloadRequest(data);

    assertThat(downloadRequest.uri).isEqualTo(testMediaItem.localConfiguration.uri);
    assertThat(downloadRequest.mimeType).isEqualTo(testMediaItem.localConfiguration.mimeType);
    assertThat(downloadRequest.customCacheKey)
        .isEqualTo(testMediaItem.localConfiguration.customCacheKey);
    assertThat(downloadRequest.data).isEqualTo(data);
    assertThat(downloadRequest.streamKeys)
        .containsExactly(
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 0, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 0, /* streamIndex= */ 1),
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 1, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 2, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 3, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 1, /* groupIndex= */ 0, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 1, /* groupIndex= */ 1, /* streamIndex= */ 0));
  }

  @Test
  public void getDownloadRequest_createsDownloadRequest_withMultipleOverridesOfSameType()
      throws Exception {
    prepareDownloadHelper(downloadHelper);

    DefaultTrackSelector.Parameters parameters =
        new DefaultTrackSelector.Parameters.Builder()
            .addOverride(new TrackSelectionOverride(trackGroupAudioUs, /* trackIndex= */ 0))
            .addOverride(new TrackSelectionOverride(trackGroupAudioZh, /* trackIndex= */ 0))
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, /* disabled= */ true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ true)
            .build();

    downloadHelper.replaceTrackSelections(/* periodIndex= */ 0, parameters);
    downloadHelper.clearTrackSelections(/* periodIndex= */ 1);

    DownloadRequest downloadRequest = downloadHelper.getDownloadRequest(/* data= */ null);

    assertThat(downloadRequest.streamKeys)
        .containsExactly(
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 1, /* streamIndex= */ 0),
            new StreamKey(/* periodIndex= */ 0, /* groupIndex= */ 2, /* streamIndex= */ 0));
  }

  @Test
  public void
      getDownloadRequestForProgressive_withConcreteTimeRange_requestContainsConcreteByteRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .create(MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4"));
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 0, /* durationMs= */ 30000);

    assertThat(downloadRequest.byteRange).isNotNull();
    assertThat(downloadRequest.byteRange.offset).isAtLeast(0);
    assertThat(downloadRequest.byteRange.length).isGreaterThan(0);
  }

  @Test
  public void
      getDownloadRequestForProgressive_withUnsetStartPosition_requestContainsConcreteByteRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .create(MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4"));
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ C.TIME_UNSET, /* durationMs= */ 30000);

    assertThat(downloadRequest.byteRange).isNotNull();
    assertThat(downloadRequest.byteRange.offset).isAtLeast(0);
    assertThat(downloadRequest.byteRange.length).isGreaterThan(0);
  }

  @Test
  public void
      getDownloadRequestForProgressive_withUnsetDuration_requestContainsUnsetByteRangeLength()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .create(MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4"));
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 30000, /* durationMs= */ C.TIME_UNSET);

    assertThat(downloadRequest.byteRange).isNotNull();
    assertThat(downloadRequest.byteRange.offset).isAtLeast(0);
    assertThat(downloadRequest.byteRange.length).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void
      getDownloadRequestForShortProgressive_withConcreteTimeRange_requestContainsUnsetByteRangeLength()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .create(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 0, /* durationMs= */ 30000);

    assertThat(downloadRequest.byteRange).isNotNull();
    assertThat(downloadRequest.byteRange.offset).isAtLeast(0);
    assertThat(downloadRequest.byteRange.length).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void getDownloadRequestForProgressive_withoutRange_requestContainsNullByteRange()
      throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .create(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest = downloadHelper.getDownloadRequest(/* data= */ null);

    assertThat(downloadRequest.byteRange).isNull();
  }

  @Test
  public void
      getDownloadRequestForNonProgressive_withConcreteTimeRange_requestContainsCorrectTimeRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 0, /* durationMs= */ 10000);

    assertThat(downloadRequest.timeRange).isNotNull();
    assertThat(downloadRequest.timeRange.startPositionUs).isEqualTo(0);
    assertThat(downloadRequest.timeRange.durationUs).isEqualTo(10000000);
  }

  @Test
  public void
      getDownloadRequestForNonProgressive_withUnsetStartPosition_requestContainsCorrectTimeRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ C.TIME_UNSET, /* durationMs= */ 5000);

    assertThat(downloadRequest.timeRange).isNotNull();
    // The startPositionUs is set to window.defaultPositionUs.
    Timeline.Window window = TEST_TIMELINE.getWindow(0, new Timeline.Window());
    assertThat(downloadRequest.timeRange.startPositionUs).isEqualTo(window.defaultPositionUs);
    assertThat(downloadRequest.timeRange.durationUs).isEqualTo(5000000);
  }

  @Test
  public void
      getDownloadRequestForNonProgressive_withStartPositionExceedingWindowDuration_requestContainsCorrectTimeRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);
    Timeline.Window window = TEST_TIMELINE.getWindow(0, new Timeline.Window());

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null,
            /* startPositionMs= */ window.durationUs + 100,
            /* durationMs= */ C.TIME_UNSET);

    assertThat(downloadRequest.timeRange).isNotNull();
    // The startPositionUs is set to window.durationUs.
    assertThat(downloadRequest.timeRange.startPositionUs).isEqualTo(window.durationUs);
    assertThat(downloadRequest.timeRange.durationUs).isEqualTo(0);
  }

  @Test
  public void
      getDownloadRequestForNonProgressive_withUnsetDuration_requestContainsCorrectTimeRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 10, /* durationMs= */ C.TIME_UNSET);

    assertThat(downloadRequest.timeRange).isNotNull();
    assertThat(downloadRequest.timeRange.startPositionUs).isEqualTo(10_000);
    Timeline.Window window = TEST_TIMELINE.getWindow(0, new Timeline.Window());
    assertThat(downloadRequest.timeRange.durationUs).isEqualTo(window.durationUs - 10_000);
  }

  @Test
  public void
      getDownloadRequestForNonProgressive_withDurationExceedingWindowDuration_requestContainsCorrectTimeRange()
          throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);
    Timeline.Window window = TEST_TIMELINE.getWindow(0, new Timeline.Window());

    DownloadRequest downloadRequest =
        downloadHelper.getDownloadRequest(
            /* data= */ null, /* startPositionMs= */ 0, /* durationMs= */ window.durationUs + 100);

    assertThat(downloadRequest.timeRange).isNotNull();
    assertThat(downloadRequest.timeRange.startPositionUs).isEqualTo(0);
    assertThat(downloadRequest.timeRange.durationUs).isEqualTo(window.durationUs);
  }

  @Test
  public void getDownloadRequestForNonProgressive_withoutRange_requestContainsNullTimeRange()
      throws Exception {
    DownloadHelper downloadHelper =
        new DownloadHelper(
            new MediaItem.Builder()
                .setUri("http://test.uri")
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
            new TestMediaSource(),
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                .createRendererCapabilitiesList());
    prepareDownloadHelper(downloadHelper);

    DownloadRequest downloadRequest = downloadHelper.getDownloadRequest(/* data= */ null);

    assertThat(downloadRequest.timeRange).isNull();
  }

  // https://github.com/androidx/media/issues/1224
  @Test
  public void prepareThenRelease_renderersReleased() throws Exception {
    // We can't use this.downloadHelper because we need access to the FakeRenderer instances for
    // later assertions, so we recreate a local DownloadHelper.
    FakeRenderer videoRenderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
    FakeRenderer audioRenderer = new FakeRenderer(C.TRACK_TYPE_AUDIO);
    FakeRenderer textRenderer = new FakeRenderer(C.TRACK_TYPE_TEXT);
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, metadata, text) ->
            new Renderer[] {textRenderer, audioRenderer, videoRenderer};
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setDataSourceFactory(new DefaultDataSource.Factory(getApplicationContext()))
            .setRenderersFactory(renderersFactory)
            .create(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));

    prepareDownloadHelper(downloadHelper);
    downloadHelper.release();

    assertThat(videoRenderer.isReleased).isTrue();
    assertThat(audioRenderer.isReleased).isTrue();
    assertThat(textRenderer.isReleased).isTrue();
  }

  @Test
  public void forMediaItem_mediaItemOnly_worksWithoutLooperThread() throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    AtomicReference<DownloadHelper> downloadHelper = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                downloadHelper.set(new DownloadHelper.Factory().create(testMediaItem));
              } catch (Throwable e) {
                exception.set(e);
              }
            });

    thread.start();
    thread.join();

    assertThat(exception.get()).isNull();
    assertThat(downloadHelper.get()).isNotNull();
  }

  // Internal b/333089854
  @Test
  public void forMediaItem_withContext_worksWithoutLooperThread() throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    AtomicReference<DownloadHelper> downloadHelper = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                FakeRenderer videoRenderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
                RenderersFactory renderersFactory =
                    (handler, videoListener, audioListener, metadata, text) ->
                        new Renderer[] {videoRenderer};
                downloadHelper.set(
                    new DownloadHelper.Factory()
                        .setDataSourceFactory(new FakeDataSource.Factory())
                        .setRenderersFactory(renderersFactory)
                        .create(testMediaItem));
              } catch (Throwable e) {
                exception.set(e);
              }
            });

    thread.start();
    thread.join();

    assertThat(exception.get()).isNull();
    assertThat(downloadHelper.get()).isNotNull();
  }

  @Test
  public void forMediaItem_withTrackSelectionParams_worksWithoutLooperThread() throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    AtomicReference<DownloadHelper> downloadHelper = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                FakeRenderer videoRenderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
                RenderersFactory renderersFactory =
                    (handler, videoListener, audioListener, metadata, text) ->
                        new Renderer[] {videoRenderer};
                downloadHelper.set(
                    new DownloadHelper.Factory()
                        .setDataSourceFactory(new FakeDataSource.Factory())
                        .setRenderersFactory(renderersFactory)
                        .create(testMediaItem));
              } catch (Throwable e) {
                exception.set(e);
              }
            });

    thread.start();
    thread.join();

    assertThat(exception.get()).isNull();
    assertThat(downloadHelper.get()).isNotNull();
  }

  @Test
  public void forMediaItem_withTrackSelectionParamsAndDrm_worksWithoutLooperThread()
      throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    AtomicReference<DownloadHelper> downloadHelper = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                FakeRenderer videoRenderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
                RenderersFactory renderersFactory =
                    (handler, videoListener, audioListener, metadata, text) ->
                        new Renderer[] {videoRenderer};
                downloadHelper.set(
                    new DownloadHelper.Factory()
                        .setDataSourceFactory(new FakeDataSource.Factory())
                        .setRenderersFactory(renderersFactory)
                        .setDrmSessionManager(
                            new DefaultDrmSessionManager.Builder()
                                .build(
                                    new HttpMediaDrmCallback(
                                        /* defaultLicenseUrl= */ null,
                                        new DefaultDataSource.Factory(getApplicationContext()))))
                        .create(testMediaItem));
              } catch (Throwable e) {
                exception.set(e);
              }
            });

    thread.start();
    thread.join();

    assertThat(exception.get()).isNull();
    assertThat(downloadHelper.get()).isNotNull();
  }

  @Test
  public void constructor_worksWithoutLooperThread() throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    AtomicReference<DownloadHelper> downloadHelper = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                RendererCapabilitiesList emptyRendererCapabilitiesList =
                    new RendererCapabilitiesList() {
                      @Override
                      public RendererCapabilities[] getRendererCapabilities() {
                        return new RendererCapabilities[0];
                      }

                      @Override
                      public int size() {
                        return 0;
                      }

                      @Override
                      public void release() {}
                    };
                downloadHelper.set(
                    new DownloadHelper(
                        testMediaItem,
                        new FakeMediaSource(),
                        TrackSelectionParameters.DEFAULT,
                        emptyRendererCapabilitiesList));
              } catch (Throwable e) {
                exception.set(e);
              }
            });

    thread.start();
    thread.join();

    assertThat(exception.get()).isNull();
  }

  private static boolean prepareDownloadHelper(DownloadHelper downloadHelper) throws Exception {
    AtomicBoolean tracksInfoAvailableRef = new AtomicBoolean();
    AtomicReference<Exception> prepareException = new AtomicReference<>(null);
    CountDownLatch preparedLatch = new CountDownLatch(1);
    downloadHelper.prepare(
        new Callback() {
          @Override
          public void onPrepared(DownloadHelper helper, boolean tracksInfoAvailable) {
            preparedLatch.countDown();
            tracksInfoAvailableRef.set(tracksInfoAvailable);
          }

          @Override
          public void onPrepareError(DownloadHelper helper, IOException e) {
            prepareException.set(e);
            preparedLatch.countDown();
          }
        });
    while (!preparedLatch.await(0, MILLISECONDS)) {
      shadowMainLooper().idleFor(shadowMainLooper().getNextScheduledTaskTime());
    }
    if (prepareException.get() != null) {
      throw prepareException.get();
    }

    return tracksInfoAvailableRef.get();
  }

  private static Format createVideoFormat(int bitrate) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setAverageBitrate(bitrate)
        .build();
  }

  private static Format createAudioFormat(String language) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setLanguage(language)
        .build();
  }

  private static Format createTextFormat(String language) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.TEXT_VTT)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .setLanguage(language)
        .build();
  }

  private static void assertSingleTrackSelectionEquals(
      List<ExoTrackSelection> trackSelectionList, TrackGroup trackGroup, int... tracks) {
    assertThat(trackSelectionList).hasSize(1);
    assertTrackSelectionEquals(trackSelectionList.get(0), trackGroup, tracks);
  }

  private static void assertTrackSelectionEquals(
      ExoTrackSelection trackSelection, TrackGroup trackGroup, int... tracks) {
    assertThat(trackSelection.getTrackGroup()).isEqualTo(trackGroup);
    assertThat(trackSelection.length()).isEqualTo(tracks.length);
    int[] selectedTracksInGroup = new int[trackSelection.length()];
    for (int i = 0; i < trackSelection.length(); i++) {
      selectedTracksInGroup[i] = trackSelection.getIndexInTrackGroup(i);
    }
    Arrays.sort(selectedTracksInGroup);
    Arrays.sort(tracks);
    assertThat(selectedTracksInGroup).isEqualTo(tracks);
  }

  private static final class TestMediaSource extends FakeMediaSource {

    public TestMediaSource() {
      super(TEST_TIMELINE);
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
      int periodIndex = TEST_TIMELINE.getIndexOfPeriod(id.periodUid);
      return new FakeMediaPeriod(
          trackGroupArrays[periodIndex],
          allocator,
          TEST_TIMELINE.getWindow(0, new Timeline.Window()).positionInFirstPeriodUs,
          new EventDispatcher().withParameters(/* windowIndex= */ 0, id)) {
        @Override
        public List<StreamKey> getStreamKeys(List<ExoTrackSelection> trackSelections) {
          List<StreamKey> result = new ArrayList<>();
          for (ExoTrackSelection trackSelection : trackSelections) {
            int groupIndex = trackGroupArrays[periodIndex].indexOf(trackSelection.getTrackGroup());
            for (int i = 0; i < trackSelection.length(); i++) {
              result.add(
                  new StreamKey(periodIndex, groupIndex, trackSelection.getIndexInTrackGroup(i)));
            }
          }
          return result;
        }
      };
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
      // Do nothing.
    }
  }
}
