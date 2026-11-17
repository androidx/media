package androidx.media3.exoplayer.dash;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.net.Uri;
import android.util.SparseArray;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.ChunkSampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Ad and short Slate Periods are removed either in two sequential refreshes
 * or together in a single refresh. Surviving Content retains its Period ID and UID,
 * while its declared duration is shortened during the final refresh.
 * Tests exercise real DashMediaSource -> DashMediaPeriod -> DefaultDashChunkSource updates.
 * Includes 33-ms two-Period removal and live-edge content reappearance coverage.
 * No media fragments are downloaded or decoded.
 */
@RunWith(AndroidJUnit4.class)
public final class DashMediaSourceOverlappingSlateRegressionTest {
  private static final String URL = "https://example.invalid/manifest.mpd";

  private enum SlateTiming { INSIDE_CONTENT, SAME_AS_CONTENT, OVERLAPS_AD_AND_CONTENT }

  // Both 330-ms and 1000-ms slate cases are verified for each timing pattern.
  @Test public void slateInsideContent_330ms_sequentialEviction() throws Exception {
    replay(SlateTiming.INSIDE_CONTENT, 330);
  }
  @Test public void slateInsideContent_1000ms_sequentialEviction() throws Exception {
    replay(SlateTiming.INSIDE_CONTENT, 1000);
  }
  @Test public void slateSameStartAsContent_330ms_sequentialEviction() throws Exception {
    replay(SlateTiming.SAME_AS_CONTENT, 330);
  }
  @Test public void slateSameStartAsContent_1000ms_sequentialEviction() throws Exception {
    replay(SlateTiming.SAME_AS_CONTENT, 1000);
  }
  @Test public void slateOverlapsAdAndContent_330ms_sequentialEviction() throws Exception {
    replay(SlateTiming.OVERLAPS_AD_AND_CONTENT, 330);
  }
  @Test public void slateOverlapsAdAndContent_1000ms_sequentialEviction() throws Exception {
    replay(SlateTiming.OVERLAPS_AD_AND_CONTENT, 1000);
  }

  // Both leading Periods disappear in one refresh rather than sequentially.
  @Test public void slateInsideContent_330ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.INSIDE_CONTENT, 330);
  }
  @Test public void slateInsideContent_1000ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.INSIDE_CONTENT, 1000);
  }
  @Test public void slateSameStartAsContent_330ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.SAME_AS_CONTENT, 330);
  }
  @Test public void slateSameStartAsContent_1000ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.SAME_AS_CONTENT, 1000);
  }
  @Test public void slateOverlapsAdAndContent_330ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.OVERLAPS_AD_AND_CONTENT, 330);
  }
  @Test public void slateOverlapsAdAndContent_1000ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.OVERLAPS_AD_AND_CONTENT, 1000);
  }

  // 33-ms slates: Ad and Slate are removed together in one manifest refresh.
  @Test public void slateInsideContent_33ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.INSIDE_CONTENT, 33);
  }
  @Test public void slateSameStartAsContent_33ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.SAME_AS_CONTENT, 33);
  }
  @Test public void slateOverlapsAdAndContent_33ms_twoPeriodEviction() throws Exception {
    replayTwoRemoved(SlateTiming.OVERLAPS_AD_AND_CONTENT, 33);
  }


  // Live-edge edge case: a NEW content Period is appended, withdrawn, then reappended.
  // The Ad, 33-ms Slate, Content and Next Periods are unchanged at every refresh.
  @Test public void contentAtLiveEdge_appearsDisappearsReappears_33msSlate()
      throws Exception {
    replayLiveEdgeContentReappearance();
  }

  private static void replayLiveEdgeContentReappearance() throws Exception {
    final SlateTiming timing = SlateTiming.SAME_AS_CONTENT;
    final int slateDurationMs = 33;
    final String[] baseline = {AD, SLATE, CONTENT, NEXT};
    final String[] extended = {AD, SLATE, CONTENT, NEXT, APPENDED};
    final String[][] states = {baseline, extended, baseline, extended};
    final String[] xml = new String[states.length];
    for (int stage = 0; stage < states.length; stage++) {
      xml[stage] = mpd(states[stage], stage, timing, slateDurationMs);
      DashManifest parsed = new DashManifestParser().parse(
          Uri.parse(URL),
          new ByteArrayInputStream(xml[stage].getBytes(StandardCharsets.UTF_8)));
      assertWithMessage("MPD Period count at stage " + stage)
          .that(parsed.getPeriodCount()).isEqualTo(states[stage].length);
      for (int i = 0; i < states[stage].length; i++) {
        assertWithMessage("MPD Period ID at stage " + stage + " index " + i)
            .that(parsed.getPeriod(i).id).isEqualTo(states[stage][i]);
      }
      assertWithMessage("33-ms Slate retained at stage " + stage)
          .that(parsed.getPeriod(1).id).isEqualTo(SLATE);
      assertThat(parsed.getPeriod(1).startMs).isEqualTo(parsed.getPeriod(2).startMs);
      if (stage == 1 || stage == 3) {
        assertWithMessage("New Content appended at live edge at stage " + stage)
            .that(parsed.getPeriod(parsed.getPeriodCount() - 1).id).isEqualTo(APPENDED);
      }
    }

    AtomicInteger stageToServe = new AtomicInteger();
    AtomicInteger opens = new AtomicInteger();
    AtomicInteger callbacks = new AtomicInteger();
    AtomicReference<Timeline> latest = new AtomicReference<>();
    DataSource.Factory factory = () -> new DataSource() {
      private ByteArrayDataSource delegate;
      @Override public void addTransferListener(TransferListener listener) {}
      @Override public long open(DataSpec spec) throws IOException {
        int stage = stageToServe.get();
        opens.incrementAndGet();
        delegate = new ByteArrayDataSource(xml[stage].getBytes(StandardCharsets.UTF_8));
        return delegate.open(spec);
      }
      @Override public int read(byte[] b, int offset, int length) throws IOException {
        return delegate.read(b, offset, length);
      }
      @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
      @Override public void close() throws IOException {
        if (delegate != null) { delegate.close(); delegate = null; }
      }
    };

    DashMediaSource source = new DashMediaSource.Factory(factory)
        .createMediaSource(MediaItem.fromUri(URL));
    MediaSource.MediaSourceCaller caller = (unused, timeline) -> {
      latest.set(timeline);
      callbacks.incrementAndGet();
    };
    source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP);
    Map<String, DashMediaPeriod> active = new LinkedHashMap<>();
    Map<String, ChunkSources> chunks = new LinkedHashMap<>();
    try {
      await(source, latest, callbacks, opens, 1, 1, baseline);
      int initialFirstPeriodId = (int) field(source, "firstPeriodId");
      Map<String, Object> stableUids = new LinkedHashMap<>();
      for (String id : baseline) stableUids.put(id, uidOf(latest.get(), id));
      for (String id : new String[] {CONTENT, NEXT}) {
        DashMediaPeriod period = (DashMediaPeriod) source.createPeriod(
            new MediaSource.MediaPeriodId(stableUids.get(id)),
            new DefaultAllocator(true, 64 * 1024), 0);
        active.put(id, period);
        ChunkSources selected = selectAv(period);
        chunks.put(id, selected);
        assertMediaPeriod(period, id, indexOf(baseline, id), "initial " + id);
        assertChunk(selected, id, indexOf(baseline, id), 100, "initial " + id);
      }
      assertProcessedManifest(source, latest.get(), baseline, active, "initial live edge");
      for (int stage = 1; stage < states.length; stage++) {
        int previousCallbacks = callbacks.get();
        int previousOpens = opens.get();
        stageToServe.set(stage);
        source.onDashManifestRefreshRequested();
        await(source, latest, callbacks, opens,
            previousCallbacks + 1, previousOpens + 1, states[stage]);
        Timeline current = latest.get();
        assertProcessedManifest(source, current, states[stage], active,
            "live-edge refresh " + stage);
        boolean shouldAppear = stage == 1 || stage == 3;
        assertWithMessage("Live-edge Content visibility at stage " + stage)
            .that(containsId(current, APPENDED)).isEqualTo(shouldAppear);
        assertWithMessage("No leading Period removed at stage " + stage)
            .that((int) field(source, "firstPeriodId"))
            .isEqualTo(initialFirstPeriodId);
        for (String id : baseline) {
          assertWithMessage(id + " stays present at stage " + stage)
              .that(containsId(current, id)).isTrue();
          assertWithMessage(id + " UID stays stable at stage " + stage)
              .that(uidOf(current, id)).isEqualTo(stableUids.get(id));
        }
        if (shouldAppear) {
          Object edgeUid = uidOf(current, APPENDED);
          for (String id : baseline) {
            assertWithMessage("New Content UID must not collide with " + id)
                .that(edgeUid).isNotEqualTo(stableUids.get(id));
          }
        }
        for (String id : new String[] {CONTENT, NEXT}) {
          int expectedIndex = indexOf(states[stage], id);
          assertMediaPeriod(active.get(id), id, expectedIndex, "stage " + stage);
          assertChunk(chunks.get(id), id, expectedIndex, 100 + stage,
              "stage " + stage);
        }
        source.maybeThrowSourceInfoRefreshError();
      }
    } finally {
      for (DashMediaPeriod period : active.values()) source.releasePeriod(period);
      source.releaseSource(caller);
    }
  }


  // Additional edge-case regressions (separate from active trailing Period lifecycle tests).
  @Test public void consecutiveLiveEdgeContentAdditions_keepExistingUids() throws Exception {
    String[] a = {AD, SLATE, CONTENT, NEXT};
    String[] b = {AD, SLATE, CONTENT, NEXT, APPENDED};
    String[] c = {AD, SLATE, CONTENT, NEXT, APPENDED, "second-appended-content"};
    replayPeriodSequence(new String[][] {a, b, c}, new int[] {0, 0, 0},
        new String[] {CONTENT, NEXT}, false);
  }

  @Test public void contentStartShifts33ms_afterTwoLeadingRemovals() throws Exception {
    String[] initial = {AD, SLATE, CONTENT, NEXT};
    String[] after = {CONTENT, NEXT};
    String[] xml = {
        mpd(initial, 0, SlateTiming.SAME_AS_CONTENT, 33),
        mpd(after, 1, SlateTiming.SAME_AS_CONTENT, 33)
            .replace("id=\"" + CONTENT + "\" start=\"PT2410.000S\"",
            "id=\"" + CONTENT + "\" start=\"PT2410.033S\"")
    };
    DashManifest parsed = parseMpd(xml[1]);
    assertThat(parsed.getPeriod(0).startMs).isEqualTo(2_410_033L);
    replayPeriodSequence(new String[][] {initial, after}, new int[] {0, 2},
        new String[] {CONTENT, NEXT}, false, xml);
  }

  @Test public void repeatedShortSlates_twoThenThreeLeadingRemovals() throws Exception {
    String a1 = "first-ad";
    String s1 = "first-33ms-slate";
    String c1 = "first-content";
    String a2 = "second-ad";
    String s2 = "second-33ms-slate";
    String c2 = "second-content";
    String[] first = {a1, s1, c1, a2, s2, c2};
    String[] second = {c1, a2, s2, c2};
    String[] third = {c2};
    Map<String, Long> starts = new LinkedHashMap<>();
    starts.put(a1, 2_400_000L);
    starts.put(s1, 2_410_000L);
    starts.put(c1, 2_410_000L);
    starts.put(a2, 2_420_000L);
    starts.put(s2, 2_430_000L);
    starts.put(c2, 2_430_000L);
    String[] xml = {
        mpdWithStarts(first, 0, starts, null),
        mpdWithStarts(second, 1, starts, null),
        mpdWithStarts(third, 2, starts, null)
    };
    for (int i = 0; i < 3; i++) {
      assertThat(parseMpd(xml[i]).getPeriodCount()).isEqualTo(
          (new int[] {6, 4, 1})[i]);
    }
    // 2 leading Periods disappear, followed by 3 more leading Periods.
    replayPeriodSequence(new String[][] {first, second, third}, new int[] {0, 2, 5},
        new String[] {c2}, false, xml);
  }

  @Test public void identicalManifestIdsRepeated_keepUidsAndIndices() throws Exception {
    String[] ids = {AD, SLATE, CONTENT, NEXT};
    // The synthetic segments advance while Period IDs and starts remain identical.
    replayPeriodSequence(new String[][] {ids, ids, ids}, new int[] {0, 0, 0},
        new String[] {CONTENT, NEXT}, false);
  }

  @Test public void liveEdgeContentRepeatedToggle_keepsFirstPeriodId() throws Exception {
    String[] baseline = {AD, SLATE, CONTENT, NEXT};
    String[] extended = {AD, SLATE, CONTENT, NEXT, APPENDED};
    replayPeriodSequence(
        new String[][] {baseline, extended, baseline, extended, baseline},
        new int[] {0, 0, 0, 0, 0}, new String[] {CONTENT, NEXT}, false);
  }

  @Test public void selectedAudioVideoAdaptationSetsDisappear_noIndexException()
      throws Exception {
    String[] ids = {CONTENT, NEXT};
    String[] xml = {
        mpdWithOnlyTrack(ids, 0, "both"),
        mpdWithOnlyTrack(ids, 1, "video"),
        mpdWithOnlyTrack(ids, 2, "audio")
    };
    // Check safe manifest updates without assuming automatic track reselection.
    replayPeriodSequence(new String[][] {ids, ids, ids}, new int[] {0, 0, 0},
        new String[] {CONTENT}, true, xml);
  }


  // A 33-ms Slate can be removed in one refresh and the Ad in another.
  @Test public void slateInsideContent_33ms_sequentialEviction() throws Exception {
    replay(SlateTiming.INSIDE_CONTENT, 33);
  }
  @Test public void slateSameStartAsContent_33ms_sequentialEviction() throws Exception {
    replay(SlateTiming.SAME_AS_CONTENT, 33);
  }
  @Test public void slateOverlapsAdAndContent_33ms_sequentialEviction() throws Exception {
    replay(SlateTiming.OVERLAPS_AD_AND_CONTENT, 33);
  }

  // A single leading eviction must not be hidden by new Periods at the live edge.
  @Test public void oneLeadingRemoval_withTwoAppended_preservesSurvivors()
      throws Exception {
    String[] initial = {AD, SLATE, CONTENT, NEXT};
    String[] updated = {SLATE, CONTENT, NEXT, APPENDED, "second-appended-content"};
    replayPeriodSequence(new String[][] {initial, updated}, new int[] {0, 1},
        new String[] {CONTENT, NEXT}, false);
  }

  // Two leading evictions with two append operations preserve Content's UID.
  @Test public void twoLeadingRemovals_withTwoAppended_sameCount()
      throws Exception {
    String[] initial = {AD, SLATE, CONTENT, NEXT};
    String[] updated = {CONTENT, NEXT, APPENDED, "second-appended-content"};
    replayPeriodSequence(new String[][] {initial, updated}, new int[] {0, 2},
        new String[] {CONTENT, NEXT}, false);
  }

  @Test public void twoLeadingRemovals_thenAnotherSlidingRefresh()
      throws Exception {
    String[] first = {AD, SLATE, CONTENT, NEXT};
    String[] second = {CONTENT, NEXT, APPENDED, "second-appended-content"};
    String[] third = {CONTENT, NEXT, APPENDED, "second-appended-content"};
    replayPeriodSequence(new String[][] {first, second, third},
        new int[] {0, 2, 2}, new String[] {CONTENT, NEXT}, false);
  }

  // The same Period retains its ID, but its adaptation sets vanish while selected.
  // Verify safe behavior when a selected AdaptationSet is no longer available.
  @Test public void activeAudioVideo_becomesEventStreamOnly_noIndexException()
      throws Exception {
    String[] ids = {CONTENT, NEXT};
    String[] manifests = {
        mpdWithOnlyTrack(ids, 0, "both"),
        mpdWithOnlyTrack(ids, 1, "event-only")
    };
    replayPeriodSequence(new String[][] {ids, ids}, new int[] {0, 0},
        new String[] {CONTENT}, true, manifests);
  }

  // Verify that the manifest can restore audio/video AdaptationSets
  // after an event-only update, without assuming track reselection.
  @Test public void activeAudioVideo_eventOnly_thenAvRestored_noCrash()
      throws Exception {
    String[] ids = {CONTENT, NEXT};
    String[] manifests = {
        mpdWithOnlyTrack(ids, 0, "both"),
        mpdWithOnlyTrack(ids, 1, "event-only"),
        mpdWithOnlyTrack(ids, 2, "both")
    };
    replayPeriodSequence(new String[][] {ids, ids, ids}, new int[] {0, 0, 0},
        new String[] {CONTENT}, true, manifests);
  }

  // EventStream-only leading Period disappears; existing Content remains active.
  @Test public void eventOnlyLeadingPeriodRemoved_contentRemainsStable()
      throws Exception {
    String[] old = {CONTENT, NEXT};
    String[] updated = {NEXT};
    String[] manifests = {
        contentOnlyEventStreamMpd(old, 0),
        mpdWithOnlyTrack(updated, 1, "both")
    };
    replayPeriodSequence(new String[][] {old, updated}, new int[] {0, 1},
        new String[] {NEXT}, true, manifests);
  }

  // A manifest update with no changes to Period IDs must not mutate their UIDs.
  @Test public void fourConsecutiveIdenticalManifestRefreshes_keepUid()
      throws Exception {
    String[] same = {AD, SLATE, CONTENT, NEXT};
    replayPeriodSequence(new String[][] {same, same, same, same},
        new int[] {0, 0, 0, 0}, new String[] {CONTENT, NEXT}, false);
  }

  // Verify UID stability while a trailing Period is added and removed.
  // The trailing Period itself is not selected as an active MediaPeriod.
  @Test public void liveEdgeAppendRemoveReappendRepeated_noUidCollision()
      throws Exception {
    String[] base = {AD, SLATE, CONTENT, NEXT};
    String[] plus = {AD, SLATE, CONTENT, NEXT, APPENDED};
    replayPeriodSequence(new String[][] {base, plus, base, plus, base, plus},
        new int[] {0, 0, 0, 0, 0, 0}, new String[] {CONTENT, NEXT}, false);
  }

  // Verify behavior when an active trailing Period is no longer published
  // by a refreshed manifest but remains allocated until releasePeriod().
  @Test public void diagnostic_activeTrailingContentRemoved() throws Exception {
    String[] old = {CONTENT, NEXT, APPENDED};
    String[] updated = {CONTENT, NEXT};
    replayPeriodSequence(new String[][] {old, updated}, new int[] {0, 0},
        new String[] {APPENDED}, false, true);
  }

  @Test public void diagnostic_activeTrailingContentRemovedThenReappears()
      throws Exception {
    String[] old = {CONTENT, NEXT, APPENDED};
    String[] updated = {CONTENT, NEXT};
    String[] updated2 = {CONTENT, NEXT, APPENDED};
    replayPeriodSequence(new String[][] {old, updated, updated2},
        new int[] {0, 0, 0}, new String[] {APPENDED}, false, true);
  }
  @Ignore("Pending validation of active trailing Period replacement behavior")
  @Test public void diagnostic_activeTrailingContentReplaced() throws Exception {
    String[] old = {CONTENT, NEXT, APPENDED};
    String[] updated = {CONTENT, NEXT, "second-appended-content"};
    replayPeriodSequence(new String[][] {old, updated}, new int[] {0, 0},
        new String[] {APPENDED}, false);
  }

  /** Drives actual asynchronous MPD loads and active DASH Period/ChunkSource refreshes. */
  private static void replayPeriodSequence(String[][] idsByStage, int[] removedFromFront,
      String[] activeIds, boolean skipChunkAssertions)
      throws Exception {
    replayPeriodSequence(idsByStage, removedFromFront, activeIds,
        skipChunkAssertions, false);
  }

  // Allow a missing active Period only in tests that explicitly exercise
  // removal of an allocated trailing Period.
  private static void replayPeriodSequence(String[][] idsByStage, int[] removedFromFront,
      String[] activeIds, boolean skipChunkAssertions, boolean allowTrailingRemoval)
      throws Exception {
    String[] xml = new String[idsByStage.length];
    for (int stage = 0; stage < xml.length; stage++) {
      xml[stage] = mpd(idsByStage[stage], stage, SlateTiming.SAME_AS_CONTENT, 33);
    }
    replayPeriodSequence(idsByStage, removedFromFront, activeIds,
        skipChunkAssertions, xml, allowTrailingRemoval);
  }

  private static void replayPeriodSequence(String[][] idsByStage, int[] removedFromFront,
      String[] activeIds, boolean skipChunkAssertions,
      String[] xml) throws Exception {
    replayPeriodSequence(idsByStage, removedFromFront, activeIds,
        skipChunkAssertions, xml, false);
  }

  private static void replayPeriodSequence(String[][] idsByStage, int[] removedFromFront,
      String[] activeIds, boolean skipChunkAssertions,
      String[] xml, boolean allowTrailingRemoval) throws Exception {
    assertThat(idsByStage.length).isEqualTo(removedFromFront.length);
    assertThat(xml.length).isEqualTo(idsByStage.length);
    for (int i = 0; i < idsByStage.length; i++) {
      DashManifest parsed = parseMpd(xml[i]);
      assertThat(parsed.getPeriodCount()).isEqualTo(idsByStage[i].length);
      for (int j = 0; j < idsByStage[i].length; j++) {
        assertWithMessage("input Period stage " + i + " index " + j)
            .that(parsed.getPeriod(j).id).isEqualTo(idsByStage[i][j]);
      }
    }
    AtomicInteger nextStage = new AtomicInteger();
    AtomicInteger opens = new AtomicInteger();
    AtomicInteger callbacks = new AtomicInteger();
    AtomicReference<Timeline> latest = new AtomicReference<>();
    DataSource.Factory factory = () -> new DataSource() {
      private ByteArrayDataSource delegate;
      @Override public void addTransferListener(TransferListener listener) {}
      @Override public long open(DataSpec spec) throws IOException {
        opens.incrementAndGet();
        delegate = new ByteArrayDataSource(xml[nextStage.get()].getBytes(StandardCharsets.UTF_8));
        return delegate.open(spec);
      }
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        return delegate.read(buffer, offset, length);
      }
      @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
      @Override public void close() throws IOException {
        if (delegate != null) { delegate.close(); delegate = null; }
      }
    };
    DashMediaSource source = new DashMediaSource.Factory(factory)
        .createMediaSource(MediaItem.fromUri(URL));
    MediaSource.MediaSourceCaller caller = (unused, timeline) -> {
      latest.set(timeline);
      callbacks.incrementAndGet();
    };
    source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP);
    Map<String, DashMediaPeriod> periods = new LinkedHashMap<>();
    Map<String, Object> activeUids = new LinkedHashMap<>();
    Map<String, ChunkSources> selected = new LinkedHashMap<>();
    try {
      await(source, latest, callbacks, opens, 1, 1, idsByStage[0]);
      int initialFirstId = (int) field(source, "firstPeriodId");
      Map<String, Object> stableUids = new LinkedHashMap<>();
      // For leading-eviction scenarios, only assert UIDs for Periods that
      // survive every update; for live-edge scenarios also track initial IDs.
      for (String id : idsByStage[0]) {
        boolean survivesAll = true;
        for (String[] ids : idsByStage) {
          if (indexOf(ids, id) == -1) { survivesAll = false; break; }
        }
        if (survivesAll) stableUids.put(id, uidOf(latest.get(), id));
      }
      for (String id : activeIds) {
        Object uid = uidOf(latest.get(), id);
        DashMediaPeriod period = (DashMediaPeriod) source.createPeriod(
            new MediaSource.MediaPeriodId(uid),
            new DefaultAllocator(true, 64 * 1024), 0);
        periods.put(id, period);
        activeUids.put(id, uid);
        ChunkSources chunks = selectAv(period);
        selected.put(id, chunks);
        assertMediaPeriod(period, id, indexOf(idsByStage[0], id), "initial " + id);
        if (!skipChunkAssertions) {
          assertChunk(chunks, id, indexOf(idsByStage[0], id), 100, "initial " + id);
        }
      }
      assertProcessedManifest(source, latest.get(), idsByStage[0], periods,
          "initial sequence");
      for (int stage = 1; stage < idsByStage.length; stage++) {
        int oldCallbacks = callbacks.get();
        int oldOpens = opens.get();
        nextStage.set(stage);
        source.onDashManifestRefreshRequested();
        await(source, latest, callbacks, opens, oldCallbacks + 1, oldOpens + 1,
            idsByStage[stage]);
        Map<String, Object> allowedMissingUids = new LinkedHashMap<>();
        if (allowTrailingRemoval) {
          for (String id : activeIds) {
            if (indexOf(idsByStage[stage], id) < 0) {
              allowedMissingUids.put(id, activeUids.get(id));
            }
          }
        }
        assertProcessedManifest(source, latest.get(), idsByStage[stage], periods,
            "sequence refresh " + stage, allowedMissingUids);
        assertWithMessage("firstPeriodId at refresh " + stage)
            .that((int) field(source, "firstPeriodId"))
            .isEqualTo(initialFirstId + removedFromFront[stage]);
        // No two concurrently published Periods may share the same UID.
        for (int i = 0; i < idsByStage[stage].length; i++) {
          for (int j = i + 1; j < idsByStage[stage].length; j++) {
            assertWithMessage("UID collision at stage " + stage + ": "
                + idsByStage[stage][i] + " / " + idsByStage[stage][j])
                .that(uidOf(latest.get(), idsByStage[stage][i]))
                .isNotEqualTo(uidOf(latest.get(), idsByStage[stage][j]));
          }
        }
        for (Map.Entry<String, Object> entry : stableUids.entrySet()) {
          assertWithMessage("UID continuity for " + entry.getKey() + " at " + stage)
              .that(uidOf(latest.get(), entry.getKey())).isEqualTo(entry.getValue());
        }
        for (String id : activeIds) {
          int index = indexOf(idsByStage[stage], id);
          if (index < 0 && allowTrailingRemoval) {
            assertWithMessage("removed active Period absent from published timeline: " + id)
                .that(containsId(latest.get(), id)).isFalse();
            // Verify the allocated Period and chunk sources retain their
            // previous valid manifest state until releasePeriod().
            int oldIndex = indexOf(idsByStage[0], id);
            assertMediaPeriod(periods.get(id), id, oldIndex,
                "retained removed Period " + id);
            assertChunk(selected.get(id), id, oldIndex, 100,
                "retained removed Period " + id);
            continue;
          }
          assertWithMessage("active Period remains present: " + id)
              .that(index).isAtLeast(0);
          assertMediaPeriod(periods.get(id), id, index, "refresh " + stage);
          if (!skipChunkAssertions) {
            assertChunk(selected.get(id), id, index, 100 + stage, "refresh " + stage);
          } else {
            // Verify both selected chunk sources remain inspectable. The update
            // is allowed to retain old state when their old AdaptationSet vanished.
            assertThat(field(selected.get(id).video, "manifest")).isNotNull();
            assertThat(field(selected.get(id).audio, "manifest")).isNotNull();
          }
        }
        source.maybeThrowSourceInfoRefreshError();
      }
    } finally {
      try {
        for (DashMediaPeriod period : periods.values()) source.releasePeriod(period);
        if (allowTrailingRemoval) {
          SparseArray<?> retained = (SparseArray<?>) field(source, "periodsById");
          assertWithMessage("all active Periods released by releasePeriod()")
              .that(retained.size()).isEqualTo(0);
        }
      } finally {
        source.releaseSource(caller);
      }
    }
  }

  private static DashManifest parseMpd(String xml) throws IOException {
    return new DashManifestParser().parse(Uri.parse(URL),
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static String mpdWithStarts(String[] ids, int stage, Map<String, Long> starts,
      String onlyTrack) {
    StringBuilder xml = new StringBuilder(
        "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\""
            + " type=\"dynamic\" availabilityStartTime=\"2024-01-01T01:00:00Z\""
            + " publishTime=\"2024-01-01T01:46:0" + stage + "Z\""
            + " minimumUpdatePeriod=\"PT3600S\" timeShiftBufferDepth=\"PT40M\""
            + " minBufferTime=\"PT1S\""
            + " profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">"
            + "<UTCTiming schemeIdUri=\"urn:mpeg:dash:utc:direct:2014\""
            + " value=\"2024-01-01T01:46:0" + stage + "Z\"/>");
    for (String id : ids) {
      Long time = starts.get(id);
      if (time == null) throw new IllegalArgumentException("Unknown Period " + id);
      long duration = id.contains("slate") ? 33L : 10_000L;
      xml.append("<Period id=\"").append(id).append("\" start=\"PT")
          .append(durationText(time)).append("\" duration=\"PT")
          .append(durationText(duration)).append("\">");
      if (!"audio".equals(onlyTrack) && !"event-only".equals(onlyTrack)) {
        xml.append(adaptation(id, "video", "video/mp4", "avc1.42E01E",
            60_000, 96_000, stage));
      }
      if (!"video".equals(onlyTrack) && !"event-only".equals(onlyTrack)) {
        xml.append(adaptation(id, "audio", "audio/mp4", "mp4a.40.2",
            48_000, 76_800, stage));
      }
      if ("event-only".equals(onlyTrack)) {
        xml.append("<EventStream schemeIdUri=\"urn:scte:scte35:2013:xml\" ")
            .append("timescale=\"1000\"><Event id=\"1\" presentationTime=\"0\" ")
            .append("duration=\"33\"/></EventStream>");
      }
      xml.append("</Period>");
    }
    return xml.append("</MPD>").toString();
  }

  private static String mpdWithOnlyTrack(String[] ids, int stage, String onlyTrack) {
    Map<String, Long> starts = new LinkedHashMap<>();
    starts.put(CONTENT, 2_410_000L);
    starts.put(NEXT, 2_420_000L);
    return mpdWithStarts(ids, stage, starts, onlyTrack);
  }

  /** Keep the survivor A/V-enabled while turning the leading Period event-only. */
  private static String contentOnlyEventStreamMpd(String[] ids, int stage) {
    String normal = mpdWithOnlyTrack(ids, stage, "both");
    // Create the same XML with the first Period's AdaptationSets removed only.
    int firstStart = normal.indexOf("<Period id=\"" + CONTENT + "\"");
    int firstEnd = normal.indexOf("</Period>", firstStart);
    if (firstStart < 0 || firstEnd < 0) {
      throw new IllegalArgumentException("First Period not found");
    }
    String opening = normal.substring(firstStart, normal.indexOf('>', firstStart) + 1);
    String eventOnly = opening
        + "<EventStream schemeIdUri=\"urn:scte:scte35:2013:xml\" timescale=\"1000\">"
        + "<Event id=\"1\" presentationTime=\"0\" duration=\"33\"/>"
        + "</EventStream>";
    return normal.substring(0, firstStart) + eventOnly
        + normal.substring(firstEnd);
  }

  private static final String AD = "ad-period";
  private static final String SLATE = "slate-period";
  private static final String CONTENT = "content-period";
  private static final String NEXT = "next-content-period";
  private static final String APPENDED = "appended-content-period";
  private static final String[] OLD_IDS = {AD, SLATE, CONTENT, NEXT};
  private static final String[] MIDDLE_IDS_WITH_APPEND = {SLATE, CONTENT, NEXT, APPENDED};
  private static final String[] FINAL_IDS_WITH_APPEND = {CONTENT, NEXT, APPENDED};
  private static final String[] MIDDLE_IDS_NO_APPEND = {SLATE, CONTENT, NEXT};
  private static final String[] FINAL_IDS_NO_APPEND = {CONTENT, NEXT};

  private static String[] idsAtStage(SlateTiming timing, int stage) {
    if (stage == 0) return OLD_IDS;
    boolean append = timing != SlateTiming.OVERLAPS_AD_AND_CONTENT;
    if (stage == 1) return append ? MIDDLE_IDS_WITH_APPEND : MIDDLE_IDS_NO_APPEND;
    return append ? FINAL_IDS_WITH_APPEND : FINAL_IDS_NO_APPEND;
  }

  private static long startMs(String id, SlateTiming timing, int slateDurationMs) {
    if (AD.equals(id)) return 2_400_000;
    if (CONTENT.equals(id)) return 2_410_000;
    if (NEXT.equals(id)) return 2_420_000;
    if (APPENDED.equals(id)) return 2_430_000;
    if ("second-appended-content".equals(id)) return 2_440_000;
    if (SLATE.equals(id)) {
      switch (timing) {
        case INSIDE_CONTENT:
          // The slate lies wholly inside content [2410000, 2420000).
          return 2_410_200;
        case SAME_AS_CONTENT:
          // Identical start timestamp, but a distinct Period ID.
          return 2_410_000;
        case OVERLAPS_AD_AND_CONTENT:
          // Slate starts inside the Ad and ends inside Content, even at 33 ms.
          // Preserve the existing 100-ms overlap for 330/1000-ms cases.
          long overlapIntoContentMs = Math.min(100, slateDurationMs / 2);
          return 2_410_000 + overlapIntoContentMs - slateDurationMs;
        default:
          throw new IllegalArgumentException();
      }
    }
    throw new IllegalArgumentException(id);
  }

  private static void verifyTiming(
      DashManifest parsed, SlateTiming timing, int durationMs, int stage) {
    String[] expected = idsAtStage(timing, stage);
    assertThat(parsed.dynamic).isTrue();
    assertThat(parsed.getPeriodCount()).isEqualTo(expected.length);
    for (int i = 0; i < expected.length; i++) {
      Period p = parsed.getPeriod(i);
      assertWithMessage("parsed Period ID at " + i).that(p.id).isEqualTo(expected[i]);
      assertWithMessage("parsed Period start of " + p.id)
          .that(p.startMs).isEqualTo(startMs(p.id, timing, durationMs));
      assertThat(p.adaptationSets).hasSize(2);
    }
    if (stage < 2) {
      long slateStart = parsed.getPeriod(indexOf(expected, SLATE)).startMs;
      long slateEnd = slateStart + durationMs;
      long contentStart = parsed.getPeriod(indexOf(expected, CONTENT)).startMs;
      if (timing == SlateTiming.INSIDE_CONTENT) {
        assertThat(slateStart).isGreaterThan(contentStart);
        assertThat(slateEnd).isLessThan(startMs(NEXT, timing, durationMs));
      } else if (timing == SlateTiming.SAME_AS_CONTENT) {
        assertThat(slateStart).isEqualTo(contentStart);
      } else {
        assertThat(slateStart).isLessThan(contentStart);
        assertThat(slateEnd).isGreaterThan(contentStart);
      }
    } else {
      assertWithMessage("Ad has been removed")
          .that(indexOf(expected, AD)).isEqualTo(-1);
      assertWithMessage("Slate has been removed")
          .that(indexOf(expected, SLATE)).isEqualTo(-1);
    }
  }

  private static void replay(SlateTiming timing, int slateDurationMs) throws Exception {
    replay(timing, slateDurationMs, false);
  }

  private static void replayTwoRemoved(SlateTiming timing, int slateDurationMs)
      throws Exception {
    replay(timing, slateDurationMs, true);
  }

  private static void replay(SlateTiming timing, int slateDurationMs,
      boolean removeBothAtOnce) throws Exception {
    String[] xml = removeBothAtOnce
        ? new String[] {
        mpd(idsAtStage(timing, 0), 0, timing, slateDurationMs),
        mpd(idsAtStage(timing, 2), 2, timing, slateDurationMs)}
        : new String[] {
            mpd(idsAtStage(timing, 0), 0, timing, slateDurationMs),
            mpd(idsAtStage(timing, 1), 1, timing, slateDurationMs),
            mpd(idsAtStage(timing, 2), 2, timing, slateDurationMs)};
    for (int stage = 0; stage < xml.length; stage++) {
      DashManifest parsed = new DashManifestParser().parse(Uri.parse(URL),
          new ByteArrayInputStream(xml[stage].getBytes(StandardCharsets.UTF_8)));
      verifyTiming(parsed, timing, slateDurationMs,
          removeBothAtOnce && stage == 1 ? 2 : stage);
    }

    AtomicInteger stageToServe = new AtomicInteger(0);
    AtomicInteger opens = new AtomicInteger();
    AtomicInteger callbacks = new AtomicInteger();
    AtomicReference<Timeline> latest = new AtomicReference<>();
    DataSource.Factory factory = () -> new DataSource() {
      private ByteArrayDataSource delegate;
      private final List<TransferListener> listeners = new ArrayList<>();
      @Override public void addTransferListener(TransferListener listener) {
        listeners.add(listener);
      }
      @Override public long open(DataSpec spec) throws IOException {
        int index = stageToServe.get();
        opens.incrementAndGet();
        delegate = new ByteArrayDataSource(xml[index].getBytes(StandardCharsets.UTF_8));
        for (TransferListener listener : listeners) delegate.addTransferListener(listener);
        return delegate.open(spec);
      }
      @Override public int read(byte[] b, int offset, int length) throws IOException {
        return delegate.read(b, offset, length);
      }
      @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
      @Override public void close() throws IOException {
        if (delegate != null) { delegate.close(); delegate = null; }
      }
    };

    DashMediaSource source = new DashMediaSource.Factory(factory)
        .createMediaSource(MediaItem.fromUri(URL));
    MediaSource.MediaSourceCaller caller = (unused, timeline) -> {
      latest.set(timeline);
      callbacks.incrementAndGet();
    };
    source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP);
    Map<String, DashMediaPeriod> active = new LinkedHashMap<>();
    Map<String, ChunkSources> chunks = new LinkedHashMap<>();
    Map<String, Object> originalUids = new LinkedHashMap<>();
    try {
      await(source, latest, callbacks, opens, 1, 1, OLD_IDS);
      int initialFirstPeriodId = (int) field(source, "firstPeriodId");
      Timeline initial = latest.get();
      for (String id : OLD_IDS) originalUids.put(id, uidOf(initial, id));
      for (String id : new String[]{CONTENT, NEXT}) {
        DashMediaPeriod period = (DashMediaPeriod) source.createPeriod(
            new MediaSource.MediaPeriodId(originalUids.get(id)),
            new DefaultAllocator(true, 64 * 1024), 0);
        active.put(id, period);
        ChunkSources selected = selectAv(period);
        chunks.put(id, selected);
        assertChunk(selected, id, indexOf(OLD_IDS, id), 100, "initial " + id);
      }

      assertProcessedManifest(source, latest.get(), OLD_IDS, active,
          "initial slate sequence");
      for (int refresh = 1; refresh < xml.length; refresh++) {
        int previousCallbacks = callbacks.get();
        int previousOpens = opens.get();
        stageToServe.set(refresh);
        source.onDashManifestRefreshRequested();
        int effectiveStage = removeBothAtOnce ? 2 : refresh;
        String[] expected = idsAtStage(timing, effectiveStage);
        await(source, latest, callbacks, opens, previousCallbacks + 1,
            previousOpens + 1, expected);
        Timeline current = latest.get();
        assertProcessedManifest(source, current, expected, active,
            "slate refresh " + refresh);
        // One-at-a-time eviction advances 1 then 2; direct eviction advances 2.
        assertWithMessage("firstPeriodId after refresh " + refresh)
            .that((int) field(source, "firstPeriodId"))
            .isEqualTo(initialFirstPeriodId + effectiveStage);
        assertWithMessage("updated Period count at stage " + refresh)
            .that(current.getPeriodCount()).isEqualTo(expected.length);
        assertWithMessage("Ad must disappear after first refresh")
            .that(containsId(current, AD)).isFalse();
        if (effectiveStage == 1) {
          assertWithMessage("Slate must remain after Ad eviction")
              .that(containsId(current, SLATE)).isTrue();
        } else {
          assertWithMessage("Slate must disappear after final eviction")
              .that(containsId(current, SLATE)).isFalse();
        }
        assertWithMessage("Content retains its UID at each refresh")
            .that(uidOf(current, CONTENT)).isEqualTo(originalUids.get(CONTENT));
        assertWithMessage("next surviving Period keeps UID")
            .that(uidOf(current, NEXT)).isEqualTo(originalUids.get(NEXT));
        for (String id : new String[]{CONTENT, NEXT}) {
          int expectedIndex = indexOf(expected, id);
          assertMediaPeriod(active.get(id), id, expectedIndex, "refresh " + refresh);
          assertChunk(chunks.get(id), id, expectedIndex, 100 + effectiveStage,
              "refresh " + refresh);
        }
        if (effectiveStage == 2) {
          // Verify the explicit duration in the MPD source. DashManifest.getPeriodDurationMs
          // may derive duration from the next Period start rather than the XML attribute.
          String explicitDuration = durationText(10_000L - slateDurationMs);
          assertWithMessage("Content XML declares shortened duration")
              .that(xml[refresh]).contains("<Period id=\"" + CONTENT
                  + "\" start=\"PT2410.000S\" duration=\"PT"
                  + explicitDuration + "\">");
        }
        source.maybeThrowSourceInfoRefreshError();
      }
    } finally {
      for (DashMediaPeriod period : active.values()) source.releasePeriod(period);
      source.releaseSource(caller);
    }
  }

  private static ChunkSources selectAv(DashMediaPeriod period) throws Exception {
    period.prepare(new MediaPeriod.Callback() {
      @Override public void onPrepared(MediaPeriod p) {}
      @Override public void onContinueLoadingRequested(MediaPeriod p) {}
    }, 0);
    TrackGroupArray groups = period.getTrackGroups();
    ExoTrackSelection[] selections = new ExoTrackSelection[2];
    int count = 0;
    for (int i = 0; i < groups.length; i++) {
      TrackGroup g = groups.get(i);
      if (g.type == C.TRACK_TYPE_VIDEO || g.type == C.TRACK_TYPE_AUDIO) {
        selections[count++] = new FixedTrackSelection(g, 0);
      }
    }
    assertThat(count).isEqualTo(2);
    SampleStream[] streams = new SampleStream[2];
    period.selectTracks(selections, new boolean[2], streams, new boolean[2], 0);
    DefaultDashChunkSource video = null;
    DefaultDashChunkSource audio = null;
    for (int i = 0; i < 2; i++) {
      assertThat(streams[i]).isInstanceOf(ChunkSampleStream.class);
      @SuppressWarnings("unchecked")
      ChunkSampleStream<DashChunkSource> stream = (ChunkSampleStream<DashChunkSource>) streams[i];
      assertThat(stream.getChunkSource()).isInstanceOf(DefaultDashChunkSource.class);
      if (selections[i].getTrackGroup().type == C.TRACK_TYPE_VIDEO) {
        video = (DefaultDashChunkSource) stream.getChunkSource();
      } else {
        audio = (DefaultDashChunkSource) stream.getChunkSource();
      }
    }
    assertThat(video).isNotNull();
    assertThat(audio).isNotNull();
    return new ChunkSources(video, audio);
  }

  private static final class ChunkSources {
    final DefaultDashChunkSource video;
    final DefaultDashChunkSource audio;
    ChunkSources(DefaultDashChunkSource video, DefaultDashChunkSource audio) {
      this.video = video;
      this.audio = audio;
    }
  }

  private static void assertChunk(
      ChunkSources sources, String expectedId, int index, int startNumber, String label)
      throws Exception {
    for (DefaultDashChunkSource chunk :
        new DefaultDashChunkSource[]{sources.video, sources.audio}) {
      DashManifest actual = (DashManifest) field(chunk, "manifest");
      int actualIndex = (int) field(chunk, "periodIndex");
      assertWithMessage(label + " chunk period index").that(actualIndex).isEqualTo(index);
      assertWithMessage(label + " chunk Period ID")
          .that(actual.getPeriod(actualIndex).id).isEqualTo(expectedId);
      // Verifies that the chunk source received the NEW manifest, not only a stable UID.
      assertWithMessage(label + " chunk startNumber")
          .that(actual.getPeriod(actualIndex).adaptationSets.get(0)
              .representations.get(0).getIndex().getFirstSegmentNum())
          .isEqualTo(startNumber);
    }
  }

  /**
   * Checks DashMediaSource.processManifest() state, not just the subsequent
   * DashMediaPeriod/DefaultDashChunkSource.updateManifest() state.
   *
   * Normally active Periods remain in every MPD. In the explicitly opted-in
   * trailing-removal diagnostic, the absent Period retains its original UID
   * mapping until releasePeriod() instead of being updated with a bad index.
   */
  private static void assertProcessedManifest(
      DashMediaSource source, Timeline timeline, String[] expectedIds,
      Map<String, DashMediaPeriod> active, String stageLabel) throws Exception {
    assertProcessedManifest(source, timeline, expectedIds, active, stageLabel,
        new LinkedHashMap<>());
  }

  private static void assertProcessedManifest(
      DashMediaSource source, Timeline timeline, String[] expectedIds,
      Map<String, DashMediaPeriod> active, String stageLabel,
      Map<String, Object> allowedMissingActiveUids) throws Exception {
    DashManifest committed = (DashManifest) field(source, "manifest");
    assertWithMessage(stageLabel + " committed manifest")
        .that(committed).isNotNull();
    assertWithMessage(stageLabel + " committed Period count")
        .that(committed.getPeriodCount()).isEqualTo(expectedIds.length);
    assertWithMessage(stageLabel + " published Period count")
        .that(timeline.getPeriodCount()).isEqualTo(expectedIds.length);
    int firstPeriodId = (int) field(source, "firstPeriodId");
    for (int i = 0; i < expectedIds.length; i++) {
      String id = expectedIds[i];
      assertWithMessage(stageLabel + " committed Period at " + i)
          .that(committed.getPeriod(i).id).isEqualTo(id);
      assertWithMessage(stageLabel + " timeline Period at " + i)
          .that(periodIdAt(timeline, i)).isEqualTo(id);
      assertWithMessage(stageLabel + " Period UID at " + i)
          .that(uidOf(timeline, id)).isEqualTo(firstPeriodId + i);
    }
    // processManifest() must preserve the source's active UID-to-Period map.
    SparseArray<?> periodsById = (SparseArray<?>) field(source, "periodsById");
    assertWithMessage(stageLabel + " active Period map size")
        .that(periodsById.size()).isEqualTo(active.size());
    for (Map.Entry<String, DashMediaPeriod> entry : active.entrySet()) {
      String id = entry.getKey();
      boolean isPublished = indexOf(expectedIds, id) >= 0;
      if (!isPublished) {
        assertWithMessage(stageLabel + " allowed missing active Period " + id)
            .that(allowedMissingActiveUids.containsKey(id)).isTrue();
        assertWithMessage(stageLabel + " removed Period absent from timeline " + id)
            .that(containsId(timeline, id)).isFalse();
        Object oldUid = allowedMissingActiveUids.get(id);
        assertWithMessage(stageLabel + " old active UID is integer for " + id)
            .that(oldUid).isInstanceOf(Integer.class);
        assertWithMessage(stageLabel + " removed active Period kept until release " + id)
            .that(periodsById.get((Integer) oldUid)).isSameInstanceAs(entry.getValue());
        continue;
      }
      Object uid = uidOf(timeline, id);
      assertWithMessage(stageLabel + " active UID is integer for " + id)
          .that(uid).isInstanceOf(Integer.class);
      assertWithMessage(stageLabel + " source active map for " + id)
          .that(periodsById.get((Integer) uid)).isSameInstanceAs(entry.getValue());
    }
  }

  private static String periodIdAt(Timeline timeline, int index) {
    Timeline.Period period = new Timeline.Period();
    timeline.getPeriod(index, period, true);
    return (String) period.id;
  }

  private static void assertMediaPeriod(
      DashMediaPeriod mediaPeriod, String id, int index, String label) throws Exception {
    DashManifest actual = (DashManifest) field(mediaPeriod, "manifest");
    int actualIndex = (int) field(mediaPeriod, "periodIndex");
    assertWithMessage(label + " DashMediaPeriod index").that(actualIndex).isEqualTo(index);
    assertWithMessage(label + " DashMediaPeriod ID")
        .that(actual.getPeriod(actualIndex).id).isEqualTo(id);
  }

  private static Object field(Object instance, String name) throws Exception {
    Field f = instance.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(instance);
  }

  private static void await(
      DashMediaSource source, AtomicReference<Timeline> latest, AtomicInteger callbacks,
      AtomicInteger opens, int callbacksTarget, int opensTarget, String[] expected)
      throws Exception {
    try {
      RobolectricUtil.runMainLooperUntil(() ->
          callbacks.get() >= callbacksTarget && opens.get() >= opensTarget
              && sameIds(latest.get(), expected));
    } catch (java.util.concurrent.TimeoutException timeout) {
      try {
        source.maybeThrowSourceInfoRefreshError();
      } catch (IOException error) {
        throw new AssertionError("DASH manifest loading failed; expected="
            + Arrays.toString(expected), error);
      }
      throw new AssertionError("No new expected timeline: " + Arrays.toString(expected)
          + ", opens=" + opens.get() + ", callbacks=" + callbacks.get(), timeout);
    }
  }

  private static boolean sameIds(Timeline timeline, String[] expected) {
    if (timeline == null || timeline.getPeriodCount() != expected.length) return false;
    Timeline.Period p = new Timeline.Period();
    for (int i = 0; i < expected.length; i++) {
      timeline.getPeriod(i, p, true);
      if (!expected[i].equals(p.id)) return false;
    }
    return true;
  }

  private static boolean containsId(Timeline timeline, String id) {
    if (timeline == null) return false;
    Timeline.Period period = new Timeline.Period();
    for (int i = 0; i < timeline.getPeriodCount(); i++) {
      timeline.getPeriod(i, period, true);
      if (id.equals(period.id)) return true;
    }
    return false;
  }

  private static Object uidOf(Timeline timeline, String id) {
    Timeline.Period p = new Timeline.Period();
    for (int i = 0; i < timeline.getPeriodCount(); i++) {
      timeline.getPeriod(i, p, true);
      if (id.equals(p.id)) return p.uid;
    }
    throw new AssertionError("Missing Period " + id);
  }

  private static int indexOf(String[] values, String id) {
    for (int i = 0; i < values.length; i++) if (id.equals(values[i])) return i;
    return -1;
  }

  private static String durationText(long durationMs) {
    return (durationMs / 1000) + "." + String.format(
        java.util.Locale.US, "%03d", durationMs % 1000) + "S";
  }

  private static String mpd(
      String[] ids, int stage, SlateTiming timing, int slateDurationMs) {
    StringBuilder xml = new StringBuilder(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\""
            + " availabilityStartTime=\"2024-01-01T01:00:00Z\""
            + " publishTime=\"2024-01-01T01:46:0" + stage + "Z\""
            + " minimumUpdatePeriod=\"PT3600S\" timeShiftBufferDepth=\"PT40M\""
            + " minBufferTime=\"PT1S\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">"
            + "<UTCTiming schemeIdUri=\"urn:mpeg:dash:utc:direct:2014\""
            + " value=\"2024-01-01T01:46:0" + stage + "Z\"/>");
    for (String id : ids) {
      long start = startMs(id, timing, slateDurationMs);
      long duration = SLATE.equals(id) ? slateDurationMs
          : CONTENT.equals(id) && stage == 2 ? 10_000L - slateDurationMs
              : 10_000L;
      xml.append("<Period id=\"").append(id).append("\" start=\"PT")
          .append(start / 1000).append('.')
          .append(String.format(java.util.Locale.US, "%03d", start % 1000))
          .append("S\" duration=\"PT")
          .append(duration / 1000).append('.')
          .append(String.format(java.util.Locale.US, "%03d", duration % 1000))
          .append("S\">");
      xml.append(adaptation(id, "video", "video/mp4", "avc1.42E01E", 60_000,
          96_000, stage));
      xml.append(adaptation(id, "audio", "audio/mp4", "mp4a.40.2", 48_000,
          76_800, stage));
      xml.append("</Period>");
    }
    return xml.append("</MPD>").toString();
  }

  private static String adaptation(String id, String type, String mime, String codec,
      int timescale, int segmentDuration, int stage) {
    long firstTime = (long) stage * segmentDuration;
    return "<AdaptationSet contentType=\"" + type + "\" mimeType=\"" + mime
        + "\" segmentAlignment=\"true\">"
        + "<SegmentTemplate timescale=\"" + timescale
        + "\" presentationTimeOffset=\"0\" startNumber=\"" + (100 + stage)
        + "\" media=\"" + id + "-" + type + "-$Number$.m4s\""
        + " initialization=\"" + id + "-" + type + "-init.mp4\">"
        + "<SegmentTimeline><S t=\"" + firstTime + "\" d=\"" + segmentDuration
        + "\" r=\"61\"/></SegmentTimeline></SegmentTemplate>"
        + "<Representation id=\"" + id + "-" + type
        + "\" bandwidth=\"256000\" codecs=\"" + codec + "\""
        + ("video".equals(type) ? " width=\"512\" height=\"288\""
        : " audioSamplingRate=\"48000\"")
        + "/></AdaptationSet>";
  }
}
