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

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.MediaSourceTestRunner;
import androidx.media3.test.utils.TimelineAsserts;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link LoopingMediaSource}. */
@SuppressWarnings("deprecation") // Testing deprecated class.
@RunWith(AndroidJUnit4.class)
public class LoopingMediaSourceTest {

  private FakeTimeline multiWindowTimeline;

  @Before
  public void setUp() throws Exception {
    multiWindowTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(1, 111),
            new TimelineWindowDefinition(1, 222),
            new TimelineWindowDefinition(1, 333));
  }

  @Test
  public void singleLoopTimeline() throws IOException {
    Timeline timeline = getLoopingTimeline(multiWindowTimeline, 1);
    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1);
    boolean shuffled = false;
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 2, 0, 1);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 0);
    shuffled = true; // FakeTimeline has FakeShuffleOrder which returns a reverse order.
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, C.INDEX_UNSET);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, C.INDEX_UNSET, 0, 1);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, shuffled, 2, 0, 1);
  }

  @Test
  public void multiLoopTimeline() throws IOException {
    Timeline timeline = getLoopingTimeline(multiWindowTimeline, 3);
    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333, 111, 222, 333, 111, 222, 333);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    boolean shuffled = false;
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, C.INDEX_UNSET, 0, 1, 2, 3, 4, 5, 6, 7);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2, 3, 4, 5, 6, 7, 8);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 8, 0, 1, 2, 3, 4, 5, 6, 7);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, 3, 4, 5, 6, 7, 8, C.INDEX_UNSET);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2, 3, 4, 5, 6, 7, 8);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 3, 4, 5, 6, 7, 8, 0);
    shuffled = true; // FakeTimeline has FakeShuffleOrder which returns a reverse order.
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, C.INDEX_UNSET, 4, 5, 0, 7, 8, 3);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2, 3, 4, 5, 6, 7, 8);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 6, 4, 5, 0, 7, 8, 3);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 5, 0, 1, 8, 3, 4, C.INDEX_UNSET, 6, 7);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2, 3, 4, 5, 6, 7, 8);
    TimelineAsserts.assertNextWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 5, 0, 1, 8, 3, 4, 2, 6, 7);
  }

  @Test
  public void infiniteLoopTimeline() throws IOException {
    Timeline timeline = getLoopingTimeline(multiWindowTimeline, Integer.MAX_VALUE);
    TimelineAsserts.assertWindowTags(timeline, 111, 222, 333);
    TimelineAsserts.assertPeriodCounts(timeline, 1, 1, 1);
    boolean shuffled = false;
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 2, 0, 1);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 2, 0, 1);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 0);
    shuffled = true; // FakeTimeline has FakeShuffleOrder which returns a reverse order.
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_OFF, shuffled, 1, 2, 0);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertPreviousWindowIndices(
        timeline, Player.REPEAT_MODE_ALL, shuffled, 1, 2, 0);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_OFF, shuffled, 2, 0, 1);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ONE, shuffled, 0, 1, 2);
    TimelineAsserts.assertNextWindowIndices(timeline, Player.REPEAT_MODE_ALL, shuffled, 2, 0, 1);
  }

  @Test
  public void emptyTimelineLoop() throws IOException {
    Timeline timeline = getLoopingTimeline(Timeline.EMPTY, 1);
    TimelineAsserts.assertEmpty(timeline);

    timeline = getLoopingTimeline(Timeline.EMPTY, 3);
    TimelineAsserts.assertEmpty(timeline);

    timeline = getLoopingTimeline(Timeline.EMPTY, Integer.MAX_VALUE);
    TimelineAsserts.assertEmpty(timeline);
  }

  @Test
  public void singleLoopPeriodCreation() throws Exception {
    testMediaPeriodCreation(multiWindowTimeline, /* loopCount= */ 1);
  }

  @Test
  public void multiLoopPeriodCreation() throws Exception {
    testMediaPeriodCreation(multiWindowTimeline, /* loopCount= */ 3);
  }

  @Test
  public void infiniteLoopPeriodCreation() throws Exception {
    testMediaPeriodCreation(multiWindowTimeline, /* loopCount= */ Integer.MAX_VALUE);
  }

  /**
   * Wraps the specified timeline in a {@link LoopingMediaSource} and returns the looping timeline.
   */
  private static Timeline getLoopingTimeline(Timeline timeline, int loopCount) throws IOException {
    FakeMediaSource fakeMediaSource = new FakeMediaSource(timeline);
    LoopingMediaSource mediaSource = new LoopingMediaSource(fakeMediaSource, loopCount);
    MediaSourceTestRunner testRunner = new MediaSourceTestRunner(mediaSource);
    try {
      Timeline loopingTimeline = testRunner.prepareSource();
      testRunner.releaseSource();
      fakeMediaSource.assertReleased();
      return loopingTimeline;
    } finally {
      testRunner.release();
    }
  }

  /**
   * Wraps the specified timeline in a {@link LoopingMediaSource} and asserts that all periods of
   * the looping timeline can be created and prepared.
   */
  private static void testMediaPeriodCreation(Timeline timeline, int loopCount) throws Exception {
    FakeMediaSource fakeMediaSource = new FakeMediaSource(timeline);
    LoopingMediaSource mediaSource = new LoopingMediaSource(fakeMediaSource, loopCount);
    MediaSourceTestRunner testRunner = new MediaSourceTestRunner(mediaSource);
    try {
      testRunner.prepareSource();
      testRunner.assertPrepareAndReleaseAllPeriods();
      testRunner.releaseSource();
    } finally {
      testRunner.release();
    }
  }
}
