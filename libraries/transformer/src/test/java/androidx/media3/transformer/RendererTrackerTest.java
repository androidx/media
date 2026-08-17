/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link RendererTracker}. */
@RunWith(AndroidJUnit4.class)
public final class RendererTrackerTest {

  @Test
  public void getActiveRenderer_emptyTracker_returnsNull() {
    RendererTracker tracker = new RendererTracker();

    assertThat(tracker.getActiveRenderer()).isNull();
  }

  @Test
  public void getActiveRenderer_singleRegisteredRenderer_returnsIt() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    Object periodUid = timeline.getUidOfPeriod(0);
    MediaPeriodId periodId = new MediaPeriodId(periodUid);
    FakeTrackedRenderer renderer = createFakeRenderer(timeline, periodId);

    tracker.addRenderer(renderer);

    assertThat(tracker.getActiveRenderer()).isEqualTo(renderer);
  }

  @Test
  public void getActiveRenderer_multipleRegisteredRenderers_returnsEarliest() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(3);
    MediaPeriodId p0 = new MediaPeriodId(timeline.getUidOfPeriod(0));
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1));
    MediaPeriodId p2 = new MediaPeriodId(timeline.getUidOfPeriod(2));
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);
    FakeTrackedRenderer r2 = createFakeRenderer(timeline, p2);

    // Register out of order
    tracker.addRenderer(r2);
    tracker.addRenderer(r1);

    // Should return r1 as it is earlier than r2
    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);

    tracker.addRenderer(r0);

    // Should now return r0
    assertThat(tracker.getActiveRenderer()).isEqualTo(r0);
  }

  @Test
  public void getActiveRenderer_multipleWindows_returnsEarliest() {
    RendererTracker tracker = new RendererTracker();
    // Timeline with 2 windows: Window 0 has 1 period, Window 1 has 1 period
    Timeline timeline = createFakeTimelineWithWindows(1, 1);
    MediaPeriodId p0 = new MediaPeriodId(timeline.getUidOfPeriod(0)); // Window 0, Period 0
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1)); // Window 1, Period 0
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);

    // Register out of order
    tracker.addRenderer(r1);
    tracker.addRenderer(r0);

    // Should return r0 as it is in Window 0 (earlier than Window 1)
    assertThat(tracker.getActiveRenderer()).isEqualTo(r0);
  }

  @Test
  public void getActiveRenderer_afterUnregister_returnsNextEarliest() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(3);
    MediaPeriodId p0 = new MediaPeriodId(timeline.getUidOfPeriod(0));
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1));
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);

    tracker.addRenderer(r0);
    tracker.addRenderer(r1);

    assertThat(tracker.getActiveRenderer()).isEqualTo(r0);

    tracker.removeRenderer(r0);

    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  @Test
  public void getActiveRenderer_unrecognizedPeriod_ignored() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    MediaPeriodId unrecognizedPeriod = new MediaPeriodId(new Object());
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1));
    FakeTrackedRenderer rUnrecognized = createFakeRenderer(timeline, unrecognizedPeriod);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);

    tracker.addRenderer(rUnrecognized);
    tracker.addRenderer(r1);

    // unrecognizedPeriod is not in timeline, so it should be ignored. Only r1 is resolved.
    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  @Test
  public void getActiveRenderer_nullPeriod_ignored() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1));
    FakeTrackedRenderer rNull = createFakeRenderer(timeline, /* periodId= */ null);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);

    tracker.addRenderer(rNull);
    tracker.addRenderer(r1);

    // rNull returns null period ID, so it should be ignored. Only r1 is resolved.
    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  @Test
  public void addRenderer_multipleTimes_ignoredDuplicates() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    MediaPeriodId p0 = new MediaPeriodId(timeline.getUidOfPeriod(0));
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p0);

    tracker.addRenderer(r0);
    tracker.addRenderer(r0); // Register twice

    tracker.removeRenderer(r0);

    // Since we use a Set now and registered the SAME renderer object, it is registered only once.
    // Unregistering once should remove it completely.
    assertThat(tracker.getActiveRenderer()).isNull();
  }

  @Test
  public void getActiveRenderer_updateRendererPeriod_resorts() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(3);
    MediaPeriodId p0 = new MediaPeriodId(timeline.getUidOfPeriod(0));
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1));
    MediaPeriodId p2 = new MediaPeriodId(timeline.getUidOfPeriod(2));
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p1);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p2);

    // Initially r0 is at p1, r1 is at p2. Active is r0.
    tracker.addRenderer(r0);
    tracker.addRenderer(r1);

    assertThat(tracker.getActiveRenderer()).isEqualTo(r0);

    // Update r1 to p0. Now active should be r1 (at p0).
    r1.setMediaPeriodId(p0);

    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  @Test
  public void getActiveRenderer_timelineChange_resorts() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline1 = createFakeTimelineWithWindows(1, 1);
    MediaPeriodId p0 = new MediaPeriodId(timeline1.getUidOfPeriod(0));
    MediaPeriodId p1 = new MediaPeriodId(timeline1.getUidOfPeriod(1));
    FakeTrackedRenderer r0 = createFakeRenderer(timeline1, p0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline1, p1);

    tracker.addRenderer(r0);
    tracker.addRenderer(r1);

    // In timeline1, r0 (p0) is at index 0, r1 (p1) is at index 1. Active is r0.
    assertThat(tracker.getActiveRenderer()).isEqualTo(r0);

    // Create timeline2 with swapped windows (Window 0 has UID 1, Window 1 has UID 0)
    TimelineWindowDefinition w0 =
        new TimelineWindowDefinition.Builder().setPeriodCount(1).setUid(1).build();
    TimelineWindowDefinition w1 =
        new TimelineWindowDefinition.Builder().setPeriodCount(1).setUid(0).build();
    Timeline timeline2 = new FakeTimeline(w0, w1);

    // In timeline2, r1 (p1) is at index 0, r0 (p0) is at index 1. Active should be r1.
    r0.setTimeline(timeline2);
    r1.setTimeline(timeline2);
    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  @Test
  public void getActiveRenderer_emptyTimeline_returnsNull() {
    RendererTracker tracker = new RendererTracker();
    FakeTrackedRenderer r0 = createFakeRenderer(Timeline.EMPTY, new MediaPeriodId(new Object()));

    tracker.addRenderer(r0);

    assertThat(tracker.getActiveRenderer()).isNull();
  }

  @Test
  public void getActiveRenderer_allUnrecognizedPeriods_returnsNull() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    MediaPeriodId unrecognizedPeriod0 = new MediaPeriodId(new Object());
    MediaPeriodId unrecognizedPeriod1 = new MediaPeriodId(new Object());
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, unrecognizedPeriod0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, unrecognizedPeriod1);

    tracker.addRenderer(r0);
    tracker.addRenderer(r1);

    assertThat(tracker.getActiveRenderer()).isNull();
  }

  @Test
  public void getActiveRenderer_differentWindowSequenceNumbers_sortedBySequenceNumber() {
    RendererTracker tracker = new RendererTracker();
    Timeline timeline = createFakeTimelineWithPeriods(2);
    // p0 is at index 0 but has later windowSequenceNumber (10)
    MediaPeriodId p0 =
        new MediaPeriodId(timeline.getUidOfPeriod(0), /* windowSequenceNumber= */ 10);
    // p1 is at index 1 but has earlier windowSequenceNumber (5)
    MediaPeriodId p1 = new MediaPeriodId(timeline.getUidOfPeriod(1), /* windowSequenceNumber= */ 5);
    FakeTrackedRenderer r0 = createFakeRenderer(timeline, p0);
    FakeTrackedRenderer r1 = createFakeRenderer(timeline, p1);

    tracker.addRenderer(r0);
    tracker.addRenderer(r1);

    // Active should be r1 because its windowSequenceNumber is smaller (5 < 10),
    // even though r0 is at index 0 in the timeline.
    assertThat(tracker.getActiveRenderer()).isEqualTo(r1);
  }

  private static final class FakeTrackedRenderer extends FakeRenderer
      implements RendererTracker.TrackedRenderer {
    @Nullable private MediaPeriodId mediaPeriodId;

    FakeTrackedRenderer(@Nullable MediaPeriodId mediaPeriodId) {
      super(C.TRACK_TYPE_VIDEO);
      this.mediaPeriodId = mediaPeriodId;
    }

    void setMediaPeriodId(@Nullable MediaPeriodId mediaPeriodId) {
      this.mediaPeriodId = mediaPeriodId;
    }

    @Override
    @Nullable
    public MediaPeriodId getTrackedMediaPeriodId() {
      return mediaPeriodId;
    }

    @Override
    @Nullable
    public Timeline getTrackedTimeline() {
      return getTimeline();
    }
  }

  private static Timeline createFakeTimelineWithPeriods(int periodCount) {
    return new FakeTimeline(
        new TimelineWindowDefinition.Builder().setPeriodCount(periodCount).build());
  }

  private static Timeline createFakeTimelineWithWindows(int... periodCounts) {
    TimelineWindowDefinition[] definitions = new TimelineWindowDefinition[periodCounts.length];
    for (int i = 0; i < periodCounts.length; i++) {
      definitions[i] =
          new TimelineWindowDefinition.Builder().setPeriodCount(periodCounts[i]).setUid(i).build();
    }
    return new FakeTimeline(definitions);
  }

  private static FakeTrackedRenderer createFakeRenderer(
      Timeline timeline, @Nullable MediaPeriodId periodId) {
    FakeTrackedRenderer renderer = new FakeTrackedRenderer(periodId);
    renderer.setTimeline(timeline);
    return renderer;
  }
}
