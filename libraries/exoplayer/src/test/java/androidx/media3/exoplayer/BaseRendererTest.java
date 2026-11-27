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
package androidx.media3.exoplayer;

import static androidx.media3.common.C.TRACK_TYPE_METADATA;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static com.google.common.truth.Truth.assertThat;

import android.util.Pair;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BaseRendererTest {

  private static final Format EMSG_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_EMSG).build();

  @Test
  public void getEndPosition_ofContentPeriodWithoutAds_isPeriodDurationUs() throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(/* periodUid= */ Pair.create(uid, 0));
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(C.TIME_UNSET);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(123_010_000L);
  }

  @Test
  public void getEndPosition_ofContentPeriodWithAd_isStartOfNextAd() throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* windowSequenceNumber= */ 0,
            /* nextAdGroupIndex= */ 0);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(
                    ImmutableList.of(new AdPlaybackState("adsId", /* ...adGroupTimesUs= */ 2_000L)))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(2_000L);
  }

  @Test
  public void getEndPosition_ofContentPeriodWithPlayedAdAndUnplayedAd_isStartOfNextUnplayedAd()
      throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* windowSequenceNumber= */ 0,
            /* nextAdGroupIndex= */ 1);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 2_000_000L, 5_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(5_000_000L);
  }

  @Test
  public void getEndPosition_ofContentPeriodWithNextAdPostroll_isPeriodDurationUs()
      throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* windowSequenceNumber= */ 0,
            /* nextAdGroupIndex= */ 0);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(123_010_000L);
  }

  @Test
  public void getEndPosition_ofAdPeriod_isAdDuration() throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, /* adDurationsUs...= */ 5_000L);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(5_000L);
  }

  @Test
  public void getEndPosition_ofAdPeriodWithUnknownDuration_durationIsUnknown() throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void getEndPosition_ofPostRollAdPeriod_isAdDuration() throws Exception {
    TestBaseRenderer renderer = new TestBaseRenderer();
    FakeSampleStream fakeSampleStream =
        createFakeSampleStream(ImmutableList.of(END_OF_STREAM_ITEM));
    Object uid = new Object();
    MediaSource.MediaPeriodId mediaPeriodId =
        new MediaSource.MediaPeriodId(
            /* periodUid= */ Pair.create(uid, 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    renderer.replaceStream(
        new Format[] {EMSG_FORMAT},
        fakeSampleStream,
        /* startPositionUs= */ 0L,
        /* offsetUs= */ 0L,
        mediaPeriodId);
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, /* adDurationsUs...= */ 8_000L);

    renderer.setTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid(uid)
                .setMediaItem(MediaItem.EMPTY)
                .setDurationUs(10_000L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build()));

    assertThat(renderer.getStreamEndPositionUsForUnitTest()).isEqualTo(8_000L);
  }

  private static FakeSampleStream createFakeSampleStream(
      ImmutableList<FakeSampleStream.FakeSampleStreamItem> samples) {
    return new FakeSampleStream(
        new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
        /* mediaSourceEventDispatcher= */ null,
        DrmSessionManager.DRM_UNSUPPORTED,
        new DrmSessionEventListener.EventDispatcher(),
        EMSG_FORMAT,
        samples);
  }

  private static class TestBaseRenderer extends BaseRenderer {
    private TestBaseRenderer() {
      super(TRACK_TYPE_METADATA);
    }

    @Override
    public String getName() {
      return "test_renderer";
    }

    @Override
    public @Capabilities int supportsFormat(Format format) throws ExoPlaybackException {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {}

    @Override
    public boolean isReady() {
      return false;
    }

    @Override
    public boolean isEnded() {
      return false;
    }

    long getStreamEndPositionUsForUnitTest() {
      return super.getStreamEndPositionUs();
    }
  }
}
