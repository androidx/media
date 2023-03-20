/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller;
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder;
import androidx.media3.test.utils.DummyMainThread;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeShuffleOrder;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.MediaSourceTestRunner;
import androidx.media3.test.utils.TimelineAsserts;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ConcatenatingMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class ConcatenatingMediaSourceTest {

  private ConcatenatingMediaSource mediaSource;
  private MediaSourceTestRunner testRunner;

  @Before
  public void setUp() throws Exception {
    mediaSource = new ConcatenatingMediaSource(/* isAtomic= */ false, new FakeShuffleOrder(0));
    testRunner = new MediaSourceTestRunner(mediaSource, null);
  }

  @After
  public void tearDown() throws Exception {
    testRunner.release();
  }

  @Test
  public void playlistChangesAfterPreparation() throws IOException, InterruptedException {
    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertEmpty(timeline);

    FakeMediaSource[] childSources = createMediaSources(7);

    // Add first source.
    mediaSource.addMediaSource(childSources[0]);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1);
    TimelineAsserts.assertWindowTags(timeline, 111);

    // Add at front of queue.
    mediaSource.addMediaSource(0, childSources[1]);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 1);
    TimelineAsserts.assertWindowTags(timeline, 222, 111);

    // Add at back of queue.
    mediaSource.addMediaSource(childSources[2]);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 1, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 111, 333);

    // Add in the middle.
    mediaSource.addMediaSource(1, childSources[3]);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 4, 1, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 444, 111, 333);

    // Add bulk.
    mediaSource.addMediaSources(
        3, Arrays.asList(childSources[4], childSources[5], childSources[6]));
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 4, 1, 5, 6, 7, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 444, 111, 555, 666, 777, 333);

    // Move sources.
    mediaSource.moveMediaSource(2, 3);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 4, 5, 1, 6, 7, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 444, 555, 111, 666, 777, 333);
    mediaSource.moveMediaSource(3, 2);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 4, 1, 5, 6, 7, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 444, 111, 555, 666, 777, 333);
    mediaSource.moveMediaSource(0, 6);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 4, 1, 5, 6, 7, 3, 2);
    TimelineAsserts.assertWindowTags(timeline, 444, 111, 555, 666, 777, 333, 222);
    mediaSource.moveMediaSource(6, 0);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 4, 1, 5, 6, 7, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 444, 111, 555, 666, 777, 333);

    // Remove in the middle.
    mediaSource.removeMediaSource(3);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.removeMediaSource(3);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.removeMediaSource(3);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.removeMediaSource(1);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 2, 1, 3);
    TimelineAsserts.assertWindowTags(timeline, 222, 111, 333);
    for (int i = 3; i <= 6; i++) {
      childSources[i].assertReleased();
    }

    // Assert the correct child source preparation load events have been returned (with the
    // respective window index at the time of preparation).
    testRunner.assertCompletedManifestLoads(0, 0, 2, 1, 3, 4, 5);

    // Assert correct next and previous indices behavior after some insertions and removals.
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, false, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, false, 1, 2, 0);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ONE, false, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ALL, false, 2, 0, 1);
    assertThat(timeline.getFirstWindowIndex(false)).isEqualTo(0);
    assertThat(timeline.getLastWindowIndex(false)).isEqualTo(timeline.getWindowCount() - 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, true, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, true, 2, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ONE, true, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ALL, true, 1, 2, 0);
    assertThat(timeline.getFirstWindowIndex(true)).isEqualTo(timeline.getWindowCount() - 1);
    assertThat(timeline.getLastWindowIndex(true)).isEqualTo(0);

    // Assert all periods can be prepared and the respective load events are returned.
    testRunner.assertPrepareAndReleaseAllPeriods();
    assertCompletedAllMediaPeriodLoads(timeline);

    // Remove at front of queue.
    mediaSource.removeMediaSource(0);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 3);
    TimelineAsserts.assertWindowTags(timeline, 111, 333);
    childSources[1].assertReleased();

    // Remove at back of queue.
    mediaSource.removeMediaSource(1);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1);
    TimelineAsserts.assertWindowTags(timeline, 111);
    childSources[2].assertReleased();

    // Remove last source.
    mediaSource.removeMediaSource(0);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertEmpty(timeline);
    childSources[3].assertReleased();
  }

  @Test
  public void playlistChangesBeforePreparation() throws IOException, InterruptedException {
    FakeMediaSource[] childSources = createMediaSources(4);
    mediaSource.addMediaSource(childSources[0]);
    mediaSource.addMediaSource(childSources[1]);
    mediaSource.addMediaSource(0, childSources[2]);
    mediaSource.moveMediaSource(0, 2);
    mediaSource.removeMediaSource(0);
    mediaSource.moveMediaSource(1, 0);
    mediaSource.addMediaSource(1, childSources[3]);
    testRunner.assertNoTimelineChange();

    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertPeriodCounts(timeline, 3, 4, 2);
    TimelineAsserts.assertWindowTags(timeline, 333, 444, 222);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, 1, 2, C.INDEX_UNSET);

    testRunner.assertPrepareAndReleaseAllPeriods();
    testRunner.assertCompletedManifestLoads(0, 1, 2);
    assertCompletedAllMediaPeriodLoads(timeline);
    testRunner.releaseSource();
    for (int i = 1; i < 4; i++) {
      childSources[i].assertReleased();
    }
  }

  @Test
  public void playlistWithLazyMediaSource() throws IOException, InterruptedException {
    // Create some normal (immediately preparing) sources and some lazy sources whose timeline
    // updates need to be triggered.
    FakeMediaSource[] fastSources = createMediaSources(2);
    final FakeMediaSource[] lazySources = new FakeMediaSource[4];
    for (int i = 0; i < 4; i++) {
      lazySources[i] = new FakeMediaSource(null);
    }

    // Add lazy sources and normal sources before preparation. Also remove one lazy source again
    // before preparation to check it doesn't throw or change the result.
    mediaSource.addMediaSource(lazySources[0]);
    mediaSource.addMediaSource(0, fastSources[0]);
    mediaSource.removeMediaSource(1);
    mediaSource.addMediaSource(1, lazySources[1]);
    testRunner.assertNoTimelineChange();

    // Prepare and assert that the timeline contains all information for normal sources while having
    // placeholder information for lazy sources.
    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1);
    TimelineAsserts.assertWindowTags(timeline, 111, null);
    TimelineAsserts.assertWindowIsDynamic(timeline, false, true);

    // Trigger source info refresh for lazy source and check that the timeline now contains all
    // information for all windows.
    testRunner.runOnPlaybackThread(() -> lazySources[1].setNewSourceInfo(createFakeTimeline(8)));
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 9);
    TimelineAsserts.assertWindowTags(timeline, 111, 999);
    TimelineAsserts.assertWindowIsDynamic(timeline, false, false);
    testRunner.assertPrepareAndReleaseAllPeriods();
    testRunner.assertCompletedManifestLoads(0, 1);
    assertCompletedAllMediaPeriodLoads(timeline);

    // Add further lazy and normal sources after preparation. Also remove one lazy source again to
    // check it doesn't throw or change the result.
    mediaSource.addMediaSource(1, lazySources[2]);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.addMediaSource(2, fastSources[1]);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.addMediaSource(0, lazySources[3]);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.removeMediaSource(2);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 2, 9);
    TimelineAsserts.assertWindowTags(timeline, null, 111, 222, 999);
    TimelineAsserts.assertWindowIsDynamic(timeline, true, false, false, false);

    // Create a period from an unprepared lazy media source and assert Callback.onPrepared is not
    // called yet.
    MediaPeriod lazyPeriod =
        testRunner.createPeriod(
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    CountDownLatch preparedCondition = testRunner.preparePeriod(lazyPeriod, 0);
    assertThat(preparedCondition.getCount()).isEqualTo(1);

    // Trigger source info refresh for lazy media source. Assert that now all information is
    // available again and the previously created period now also finished preparing.
    testRunner.runOnPlaybackThread(() -> lazySources[3].setNewSourceInfo(createFakeTimeline(7)));
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 8, 1, 2, 9);
    TimelineAsserts.assertWindowTags(timeline, 888, 111, 222, 999);
    TimelineAsserts.assertWindowIsDynamic(timeline, false, false, false, false);
    assertThat(preparedCondition.getCount()).isEqualTo(0);

    // Release the period and source.
    testRunner.releasePeriod(lazyPeriod);
    testRunner.releaseSource();

    // Assert all sources were fully released.
    for (FakeMediaSource fastSource : fastSources) {
      fastSource.assertReleased();
    }
    for (FakeMediaSource lazySource : lazySources) {
      lazySource.assertReleased();
    }
  }

  @Test
  public void emptyTimelineMediaSource() throws IOException, InterruptedException {
    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertEmpty(timeline);

    mediaSource.addMediaSource(new FakeMediaSource(Timeline.EMPTY));
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertEmpty(timeline);

    mediaSource.addMediaSources(
        Arrays.asList(
            new MediaSource[] {
              new FakeMediaSource(Timeline.EMPTY), new FakeMediaSource(Timeline.EMPTY),
              new FakeMediaSource(Timeline.EMPTY), new FakeMediaSource(Timeline.EMPTY),
              new FakeMediaSource(Timeline.EMPTY), new FakeMediaSource(Timeline.EMPTY)
            }));
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertEmpty(timeline);
    testRunner.assertCompletedManifestLoads(/* empty */ );

    // Insert non-empty media source to leave empty sources at the start, the end, and the middle
    // (with single and multiple empty sources in a row).
    MediaSource[] mediaSources = createMediaSources(3);
    mediaSource.addMediaSource(1, mediaSources[0]);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.addMediaSource(4, mediaSources[1]);
    testRunner.assertTimelineChangeBlocking();
    mediaSource.addMediaSource(6, mediaSources[2]);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 2, 3);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ONE, false, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ALL, false, 2, 0, 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, false, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, false, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, false, 1, 2, 0);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ONE, true, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(timeline, Player.REPEAT_MODE_ALL, true, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, true, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, true, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, true, 2, 0, 1);
    assertThat(timeline.getFirstWindowIndex(false)).isEqualTo(0);
    assertThat(timeline.getLastWindowIndex(false)).isEqualTo(2);
    assertThat(timeline.getFirstWindowIndex(true)).isEqualTo(2);
    assertThat(timeline.getLastWindowIndex(true)).isEqualTo(0);
    testRunner.assertPrepareAndReleaseAllPeriods();
    testRunner.assertCompletedManifestLoads(0, 1, 2);
    assertCompletedAllMediaPeriodLoads(timeline);
  }

  @Test
  public void dynamicChangeOfEmptyTimelines() throws IOException {
    FakeMediaSource[] childSources =
        new FakeMediaSource[] {
          new FakeMediaSource(Timeline.EMPTY),
          new FakeMediaSource(Timeline.EMPTY),
          new FakeMediaSource(Timeline.EMPTY),
        };
    Timeline nonEmptyTimeline = new FakeTimeline(/* windowCount= */ 1);

    mediaSource.addMediaSources(Arrays.asList(childSources));
    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertEmpty(timeline);

    childSources[0].setNewSourceInfo(nonEmptyTimeline);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1);

    childSources[2].setNewSourceInfo(nonEmptyTimeline);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1);

    childSources[1].setNewSourceInfo(nonEmptyTimeline);
    timeline = testRunner.assertTimelineChangeBlocking();
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1);
  }

  @Test
  public void illegalArguments() {
    MediaSource validSource = new FakeMediaSource(createFakeTimeline(1));

    // Null sources.
    try {
      mediaSource.addMediaSource(null);
      fail("Null mediaSource not allowed.");
    } catch (NullPointerException e) {
      // Expected.
    }

    MediaSource[] mediaSources = {validSource, null};
    try {
      mediaSource.addMediaSources(Arrays.asList(mediaSources));
      fail("Null mediaSource not allowed.");
    } catch (NullPointerException e) {
      // Expected.
    }
  }

  @Test
  public void customCallbackBeforePreparationAddSingle() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            mediaSource.addMediaSource(
                createFakeMediaSource(),
                Util.createHandlerForCurrentLooper(),
                runnableInvoked::countDown));
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationAddMultiple() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            mediaSource.addMediaSources(
                Arrays.asList(new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()}),
                Util.createHandlerForCurrentLooper(),
                runnableInvoked::countDown));
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationAddSingleWithIndex() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            mediaSource.addMediaSource(
                /* index */ 0,
                createFakeMediaSource(),
                Util.createHandlerForCurrentLooper(),
                runnableInvoked::countDown));
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationAddMultipleWithIndex() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            mediaSource.addMediaSources(
                /* index */ 0,
                Arrays.asList(new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()}),
                Util.createHandlerForCurrentLooper(),
                runnableInvoked::countDown));
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationRemove() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () -> {
          mediaSource.addMediaSource(createFakeMediaSource());
          mediaSource.removeMediaSource(
              /* index */ 0, Util.createHandlerForCurrentLooper(), runnableInvoked::countDown);
        });
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationMove() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () -> {
          mediaSource.addMediaSources(
              Arrays.asList(new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()}));
          mediaSource.moveMediaSource(
              /* currentIndex= */ 1,
              /* newIndex= */ 0,
              Util.createHandlerForCurrentLooper(),
              runnableInvoked::countDown);
        });
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackAfterPreparationAddSingle() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.addMediaSource(
                  createFakeMediaSource(), Util.createHandlerForCurrentLooper(), timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(1);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackAfterPreparationAddMultiple() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.addMediaSources(
                  Arrays.asList(
                      new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()}),
                  Util.createHandlerForCurrentLooper(),
                  timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(2);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackAfterPreparationAddSingleWithIndex() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.addMediaSource(
                  /* index */ 0,
                  createFakeMediaSource(),
                  Util.createHandlerForCurrentLooper(),
                  timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(1);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackAfterPreparationAddMultipleWithIndex() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.addMediaSources(
                  /* index */ 0,
                  Arrays.asList(
                      new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()}),
                  Util.createHandlerForCurrentLooper(),
                  timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(2);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackAfterPreparationRemove() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      testThread.runOnMainThread(() -> mediaSource.addMediaSource(createFakeMediaSource()));
      testRunner.assertTimelineChangeBlocking();

      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.removeMediaSource(
                  /* index */ 0, Util.createHandlerForCurrentLooper(), timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(0);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackAfterPreparationMove() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      testRunner.prepareSource();
      testThread.runOnMainThread(
          () ->
              mediaSource.addMediaSources(
                  Arrays.asList(
                      new MediaSource[] {createFakeMediaSource(), createFakeMediaSource()})));
      testRunner.assertTimelineChangeBlocking();

      final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.moveMediaSource(
                  /* currentIndex= */ 1,
                  /* newIndex= */ 0,
                  Util.createHandlerForCurrentLooper(),
                  timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getWindowCount()).isEqualTo(2);
    } finally {
      testThread.release();
    }
  }

  @Test
  public void customCallbackIsCalledAfterRelease() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    CountDownLatch callbackCalledCondition = new CountDownLatch(1);
    try {
      testThread.runOnMainThread(
          () -> {
            MediaSourceCaller caller = mock(MediaSourceCaller.class);
            mediaSource.addMediaSources(Arrays.asList(createMediaSources(2)));
            mediaSource.prepareSource(caller, /* mediaTransferListener= */ null, PlayerId.UNSET);
            mediaSource.moveMediaSource(
                /* currentIndex= */ 0,
                /* newIndex= */ 1,
                Util.createHandlerForCurrentLooper(),
                callbackCalledCondition::countDown);
            mediaSource.releaseSource(caller);
          });
      assertThat(callbackCalledCondition.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS))
          .isTrue();
    } finally {
      testThread.release();
    }
  }

  @Test
  public void periodCreationWithAds() throws IOException, InterruptedException {
    // Create concatenated media source with ad child source.
    Timeline timelineContentOnly =
        new FakeTimeline(
            new TimelineWindowDefinition(2, 111, true, false, 10 * C.MICROS_PER_SECOND));
    Timeline timelineWithAds =
        new FakeTimeline(
            new TimelineWindowDefinition(
                2,
                222,
                true,
                false,
                10 * C.MICROS_PER_SECOND,
                FakeTimeline.createAdPlaybackState(
                    /* adsPerAdGroup= */ 1, /* adGroupTimesUs=... */ 0)));
    FakeMediaSource mediaSourceContentOnly = new FakeMediaSource(timelineContentOnly);
    FakeMediaSource mediaSourceWithAds = new FakeMediaSource(timelineWithAds);
    mediaSource.addMediaSource(mediaSourceContentOnly);
    mediaSource.addMediaSource(mediaSourceWithAds);

    Timeline timeline = testRunner.prepareSource();

    // Assert the timeline contains ad groups.
    TimelineAsserts.assertAdGroupCounts(timeline, 0, 0, 1, 1);

    // Create all periods and assert period creation of child media sources has been called.
    testRunner.assertPrepareAndReleaseAllPeriods();
    Object timelineContentOnlyPeriodUid0 = timelineContentOnly.getUidOfPeriod(/* periodIndex= */ 0);
    Object timelineContentOnlyPeriodUid1 = timelineContentOnly.getUidOfPeriod(/* periodIndex= */ 1);
    Object timelineWithAdsPeriodUid0 = timelineWithAds.getUidOfPeriod(/* periodIndex= */ 0);
    Object timelineWithAdsPeriodUid1 = timelineWithAds.getUidOfPeriod(/* periodIndex= */ 1);
    mediaSourceContentOnly.assertMediaPeriodCreated(
        new MediaPeriodId(timelineContentOnlyPeriodUid0, /* windowSequenceNumber= */ 0));
    mediaSourceContentOnly.assertMediaPeriodCreated(
        new MediaPeriodId(timelineContentOnlyPeriodUid1, /* windowSequenceNumber= */ 0));
    mediaSourceWithAds.assertMediaPeriodCreated(
        new MediaPeriodId(timelineWithAdsPeriodUid0, /* windowSequenceNumber= */ 1));
    mediaSourceWithAds.assertMediaPeriodCreated(
        new MediaPeriodId(timelineWithAdsPeriodUid1, /* windowSequenceNumber= */ 1));
    mediaSourceWithAds.assertMediaPeriodCreated(
        new MediaPeriodId(
            timelineWithAdsPeriodUid0,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 1));
    mediaSourceWithAds.assertMediaPeriodCreated(
        new MediaPeriodId(
            timelineWithAdsPeriodUid1,
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 1));
    testRunner.assertCompletedManifestLoads(0, 1);
    assertCompletedAllMediaPeriodLoads(timeline);
  }

  @Test
  public void atomicTimelineWindowOrder() throws IOException {
    // Release default test runner with non-atomic media source and replace with new test runner.
    testRunner.release();
    ConcatenatingMediaSource mediaSource =
        new ConcatenatingMediaSource(/* isAtomic= */ true, new FakeShuffleOrder(0));
    testRunner = new MediaSourceTestRunner(mediaSource, null);
    mediaSource.addMediaSources(Arrays.asList(createMediaSources(3)));
    Timeline timeline = testRunner.prepareSource();
    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 2, 3);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ true, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ false, 2, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ true, 2, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ false, 2, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ true, 2, 0, 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ true, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ false, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ true, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ false, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ true, 1, 2, 0);
    assertThat(timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ false)).isEqualTo(0);
    assertThat(timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true)).isEqualTo(0);
    assertThat(timeline.getLastWindowIndex(/* shuffleModeEnabled= */ false)).isEqualTo(2);
    assertThat(timeline.getLastWindowIndex(/* shuffleModeEnabled= */ true)).isEqualTo(2);
  }

  @Test
  public void nestedTimeline() throws IOException {
    ConcatenatingMediaSource nestedSource1 =
        new ConcatenatingMediaSource(/* isAtomic= */ false, new FakeShuffleOrder(0));
    ConcatenatingMediaSource nestedSource2 =
        new ConcatenatingMediaSource(/* isAtomic= */ true, new FakeShuffleOrder(0));
    mediaSource.addMediaSource(nestedSource1);
    mediaSource.addMediaSource(nestedSource2);
    testRunner.prepareSource();
    FakeMediaSource[] childSources = createMediaSources(4);
    nestedSource1.addMediaSource(childSources[0]);
    testRunner.assertTimelineChangeBlocking();
    nestedSource1.addMediaSource(childSources[1]);
    testRunner.assertTimelineChangeBlocking();
    nestedSource2.addMediaSource(childSources[2]);
    testRunner.assertTimelineChangeBlocking();
    nestedSource2.addMediaSource(childSources[3]);
    Timeline timeline = testRunner.assertTimelineChangeBlocking();

    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333, 444);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 2, 3, 4);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false, C.INDEX_UNSET, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ false, 0, 1, 3, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ false, 3, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false, 1, 2, 3, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ false, 0, 1, 3, 2);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ false, 1, 2, 3, 0);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ true, 1, 3, C.INDEX_UNSET, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ true, 0, 1, 3, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ true, 1, 3, 0, 2);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ true, C.INDEX_UNSET, 0, 3, 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, /* shuffleModeEnabled= */ true, 0, 1, 3, 2);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, /* shuffleModeEnabled= */ true, 2, 0, 3, 1);
  }

  @Test
  public void removeChildSourceWithActiveMediaPeriod() throws IOException {
    FakeMediaSource childSource = createFakeMediaSource();
    mediaSource.addMediaSource(childSource);
    Timeline timeline = testRunner.prepareSource();
    MediaPeriod mediaPeriod =
        testRunner.createPeriod(
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    mediaSource.removeMediaSource(/* index= */ 0);
    testRunner.assertTimelineChangeBlocking();
    testRunner.releasePeriod(mediaPeriod);
    childSource.assertReleased();
    testRunner.releaseSource();
  }

  @Test
  public void duplicateMediaSources() throws IOException, InterruptedException {
    Timeline childTimeline = new FakeTimeline(/* windowCount= */ 2);
    FakeMediaSource childSource = new FakeMediaSource(childTimeline);

    mediaSource.addMediaSource(childSource);
    mediaSource.addMediaSource(childSource);
    testRunner.prepareSource();
    mediaSource.addMediaSources(Arrays.asList(childSource, childSource));
    Timeline timeline = testRunner.assertTimelineChangeBlocking();

    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1, 1, 1, 1, 1, 1);
    testRunner.assertPrepareAndReleaseAllPeriods();
    Object childPeriodUid0 = childTimeline.getUidOfPeriod(/* periodIndex= */ 0);
    Object childPeriodUid1 = childTimeline.getUidOfPeriod(/* periodIndex= */ 1);
    assertThat(childSource.getCreatedMediaPeriods())
        .containsAtLeast(
            new MediaPeriodId(childPeriodUid0, /* windowSequenceNumber= */ 0),
            new MediaPeriodId(childPeriodUid0, /* windowSequenceNumber= */ 2),
            new MediaPeriodId(childPeriodUid0, /* windowSequenceNumber= */ 4),
            new MediaPeriodId(childPeriodUid0, /* windowSequenceNumber= */ 6),
            new MediaPeriodId(childPeriodUid1, /* windowSequenceNumber= */ 1),
            new MediaPeriodId(childPeriodUid1, /* windowSequenceNumber= */ 3),
            new MediaPeriodId(childPeriodUid1, /* windowSequenceNumber= */ 5),
            new MediaPeriodId(childPeriodUid1, /* windowSequenceNumber= */ 7));
    // Assert that only one manifest load is reported because the source is reused.
    testRunner.assertCompletedManifestLoads(/* windowIndices=... */ 0);
    assertCompletedAllMediaPeriodLoads(timeline);

    testRunner.releaseSource();
    childSource.assertReleased();
  }

  @Test
  public void duplicateNestedMediaSources() throws IOException, InterruptedException {
    Timeline childTimeline = new FakeTimeline();
    FakeMediaSource childSource = new FakeMediaSource(childTimeline);
    ConcatenatingMediaSource nestedConcatenation = new ConcatenatingMediaSource();

    testRunner.prepareSource();
    mediaSource.addMediaSources(
        Arrays.asList(childSource, nestedConcatenation, nestedConcatenation));
    testRunner.assertTimelineChangeBlocking();
    nestedConcatenation.addMediaSource(childSource);
    testRunner.assertTimelineChangeBlocking();
    nestedConcatenation.addMediaSource(childSource);
    Timeline timeline = testRunner.assertTimelineChangeBlocking();

    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1, 1, 1);
    testRunner.assertPrepareAndReleaseAllPeriods();
    Object childPeriodUid = childTimeline.getUidOfPeriod(/* periodIndex= */ 0);
    assertThat(childSource.getCreatedMediaPeriods())
        .containsAtLeast(
            new MediaPeriodId(childPeriodUid, /* windowSequenceNumber= */ 0),
            new MediaPeriodId(childPeriodUid, /* windowSequenceNumber= */ 1),
            new MediaPeriodId(childPeriodUid, /* windowSequenceNumber= */ 2),
            new MediaPeriodId(childPeriodUid, /* windowSequenceNumber= */ 3),
            new MediaPeriodId(childPeriodUid, /* windowSequenceNumber= */ 4));
    // Assert that only one manifest load is needed because the source is reused.
    testRunner.assertCompletedManifestLoads(/* windowIndices=... */ 0);
    assertCompletedAllMediaPeriodLoads(timeline);

    testRunner.releaseSource();
    childSource.assertReleased();
  }

  @Test
  public void clear() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    final FakeMediaSource preparedChildSource = createFakeMediaSource();
    final FakeMediaSource unpreparedChildSource = new FakeMediaSource(/* timeline= */ null);
    testThread.runOnMainThread(
        () -> {
          mediaSource.addMediaSource(preparedChildSource);
          mediaSource.addMediaSource(unpreparedChildSource);
        });
    testRunner.prepareSource();
    final TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);

    testThread.runOnMainThread(
        () -> mediaSource.clear(Util.createHandlerForCurrentLooper(), timelineGrabber));

    Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
    assertThat(timeline.isEmpty()).isTrue();
    preparedChildSource.assertReleased();
    unpreparedChildSource.assertReleased();
  }

  @Test
  public void releaseAndReprepareSource() throws IOException {
    FakeMediaSource[] fakeMediaSources = createMediaSources(/* count= */ 2);
    mediaSource.addMediaSource(fakeMediaSources[0]); // Child source with 1 period.
    mediaSource.addMediaSource(fakeMediaSources[1]); // Child source with 2 periods.
    Timeline timeline = testRunner.prepareSource();
    Object periodId0 = timeline.getUidOfPeriod(/* periodIndex= */ 0);
    Object periodId1 = timeline.getUidOfPeriod(/* periodIndex= */ 1);
    Object periodId2 = timeline.getUidOfPeriod(/* periodIndex= */ 2);
    testRunner.releaseSource();

    mediaSource.moveMediaSource(/* currentIndex= */ 1, /* newIndex= */ 0);
    timeline = testRunner.prepareSource();
    Object newPeriodId0 = timeline.getUidOfPeriod(/* periodIndex= */ 0);
    Object newPeriodId1 = timeline.getUidOfPeriod(/* periodIndex= */ 1);
    Object newPeriodId2 = timeline.getUidOfPeriod(/* periodIndex= */ 2);
    assertThat(newPeriodId0).isEqualTo(periodId1);
    assertThat(newPeriodId1).isEqualTo(periodId2);
    assertThat(newPeriodId2).isEqualTo(periodId0);
  }

  @Test
  public void childTimelineChangeWithActiveMediaPeriod() throws IOException {
    FakeMediaSource[] nestedChildSources = createMediaSources(/* count= */ 2);
    ConcatenatingMediaSource childSource = new ConcatenatingMediaSource(nestedChildSources);
    mediaSource.addMediaSource(childSource);

    Timeline timeline = testRunner.prepareSource();
    MediaPeriod mediaPeriod =
        testRunner.createPeriod(
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 0));
    childSource.moveMediaSource(/* currentIndex= */ 0, /* newIndex= */ 1);
    timeline = testRunner.assertTimelineChangeBlocking();
    testRunner.preparePeriod(mediaPeriod, /* positionUs= */ 0);

    testRunner.assertCompletedMediaPeriodLoads(
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
  }

  @Test
  public void childSourceIsNotPreparedWithLazyPreparation() throws IOException {
    FakeMediaSource[] childSources = createMediaSources(/* count= */ 2);
    mediaSource =
        new ConcatenatingMediaSource(
            /* isAtomic= */ false,
            /* useLazyPreparation= */ true,
            new DefaultShuffleOrder(0),
            childSources);
    testRunner = new MediaSourceTestRunner(mediaSource, /* allocator= */ null);
    testRunner.prepareSource();

    assertThat(childSources[0].isPrepared()).isFalse();
    assertThat(childSources[1].isPrepared()).isFalse();
  }

  @Test
  public void childSourceIsPreparedWithLazyPreparationAfterPeriodCreation() throws IOException {
    FakeMediaSource[] childSources = createMediaSources(/* count= */ 2);
    mediaSource =
        new ConcatenatingMediaSource(
            /* isAtomic= */ false,
            /* useLazyPreparation= */ true,
            new DefaultShuffleOrder(0),
            childSources);
    testRunner = new MediaSourceTestRunner(mediaSource, /* allocator= */ null);
    Timeline timeline = testRunner.prepareSource();
    testRunner.createPeriod(
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));

    assertThat(childSources[0].isPrepared()).isTrue();
    assertThat(childSources[1].isPrepared()).isFalse();
  }

  @Test
  public void childSourceWithLazyPreparationOnlyPreparesSourceOnce() throws IOException {
    FakeMediaSource[] childSources = createMediaSources(/* count= */ 2);
    mediaSource =
        new ConcatenatingMediaSource(
            /* isAtomic= */ false,
            /* useLazyPreparation= */ true,
            new DefaultShuffleOrder(0),
            childSources);
    testRunner = new MediaSourceTestRunner(mediaSource, /* allocator= */ null);
    Timeline timeline = testRunner.prepareSource();

    // The lazy preparation must only be triggered once, even if we create multiple periods from
    // the media source. FakeMediaSource.prepareSource asserts that it's not called twice, so
    // creating two periods shouldn't throw.
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    testRunner.createPeriod(mediaPeriodId);
    testRunner.createPeriod(mediaPeriodId);
  }

  @Test
  public void removeUnpreparedChildSourceWithLazyPreparation() throws IOException {
    FakeMediaSource[] childSources = createMediaSources(/* count= */ 2);
    mediaSource =
        new ConcatenatingMediaSource(
            /* isAtomic= */ false,
            /* useLazyPreparation= */ true,
            new DefaultShuffleOrder(0),
            childSources);
    testRunner = new MediaSourceTestRunner(mediaSource, /* allocator= */ null);
    testRunner.prepareSource();

    // Check that removal doesn't throw even though the child sources are unprepared.
    mediaSource.removeMediaSource(0);
  }

  @Test
  public void setShuffleOrderBeforePreparation() throws Exception {
    mediaSource.setShuffleOrder(new ShuffleOrder.UnshuffledShuffleOrder(/* length= */ 0));
    mediaSource.addMediaSources(
        Arrays.asList(createFakeMediaSource(), createFakeMediaSource(), createFakeMediaSource()));
    Timeline timeline = testRunner.prepareSource();

    assertThat(timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true)).isEqualTo(0);
  }

  @Test
  public void setShuffleOrderAfterPreparation() throws Exception {
    mediaSource.addMediaSources(
        Arrays.asList(createFakeMediaSource(), createFakeMediaSource(), createFakeMediaSource()));
    testRunner.prepareSource();
    mediaSource.setShuffleOrder(new ShuffleOrder.UnshuffledShuffleOrder(/* length= */ 3));
    Timeline timeline = testRunner.assertTimelineChangeBlocking();

    assertThat(timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true)).isEqualTo(0);
  }

  @Test
  public void customCallbackBeforePreparationSetShuffleOrder() throws Exception {
    CountDownLatch runnableInvoked = new CountDownLatch(1);

    DummyMainThread testThread = new DummyMainThread();
    testThread.runOnMainThread(
        () ->
            mediaSource.setShuffleOrder(
                new ShuffleOrder.UnshuffledShuffleOrder(/* length= */ 0),
                Util.createHandlerForCurrentLooper(),
                runnableInvoked::countDown));
    runnableInvoked.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS);
    testThread.release();

    assertThat(runnableInvoked.getCount()).isEqualTo(0);
  }

  @Test
  public void customCallbackAfterPreparationSetShuffleOrder() throws Exception {
    DummyMainThread testThread = new DummyMainThread();
    try {
      mediaSource.addMediaSources(
          Arrays.asList(createFakeMediaSource(), createFakeMediaSource(), createFakeMediaSource()));
      testRunner.prepareSource();
      TimelineGrabber timelineGrabber = new TimelineGrabber(testRunner);
      testThread.runOnMainThread(
          () ->
              mediaSource.setShuffleOrder(
                  new ShuffleOrder.UnshuffledShuffleOrder(/* length= */ 3),
                  Util.createHandlerForCurrentLooper(),
                  timelineGrabber));
      Timeline timeline = timelineGrabber.assertTimelineChangeBlocking();
      assertThat(timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true)).isEqualTo(0);
    } finally {
      testThread.release();
    }
  }

  private void assertCompletedAllMediaPeriodLoads(Timeline timeline) {
    Timeline.Period period = new Timeline.Period();
    Timeline.Window window = new Timeline.Window();
    ArrayList<MediaPeriodId> expectedMediaPeriodIds = new ArrayList<>();
    for (int windowIndex = 0; windowIndex < timeline.getWindowCount(); windowIndex++) {
      timeline.getWindow(windowIndex, window);
      for (int periodIndex = window.firstPeriodIndex;
          periodIndex <= window.lastPeriodIndex;
          periodIndex++) {
        timeline.getPeriod(periodIndex, period);
        Object periodUid = timeline.getUidOfPeriod(periodIndex);
        expectedMediaPeriodIds.add(new MediaPeriodId(periodUid, windowIndex));
        for (int adGroupIndex = 0; adGroupIndex < period.getAdGroupCount(); adGroupIndex++) {
          for (int adIndex = 0; adIndex < period.getAdCountInAdGroup(adGroupIndex); adIndex++) {
            expectedMediaPeriodIds.add(
                new MediaPeriodId(periodUid, adGroupIndex, adIndex, windowIndex));
          }
        }
      }
    }
    testRunner.assertCompletedMediaPeriodLoads(
        expectedMediaPeriodIds.toArray(new MediaPeriodId[0]));
  }

  private static FakeMediaSource[] createMediaSources(int count) {
    FakeMediaSource[] sources = new FakeMediaSource[count];
    for (int i = 0; i < count; i++) {
      sources[i] = new FakeMediaSource(createFakeTimeline(i));
    }
    return sources;
  }

  private static FakeMediaSource createFakeMediaSource() {
    return new FakeMediaSource(createFakeTimeline(/* index */ 0));
  }

  private static FakeTimeline createFakeTimeline(int index) {
    return new FakeTimeline(new TimelineWindowDefinition(index + 1, (index + 1) * 111));
  }

  private static final class TimelineGrabber implements Runnable {

    private final MediaSourceTestRunner testRunner;
    private final CountDownLatch finishedLatch;

    private Timeline timeline;
    private AssertionError error;

    public TimelineGrabber(MediaSourceTestRunner testRunner) {
      this.testRunner = testRunner;
      finishedLatch = new CountDownLatch(1);
    }

    @Override
    public void run() {
      try {
        timeline = testRunner.assertTimelineChange();
      } catch (AssertionError e) {
        error = e;
      }
      finishedLatch.countDown();
    }

    public Timeline assertTimelineChangeBlocking() throws InterruptedException {
      assertThat(finishedLatch.await(MediaSourceTestRunner.TIMEOUT_MS, MILLISECONDS)).isTrue();
      if (error != null) {
        throw error;
      }
      return timeline;
    }
  }
}
