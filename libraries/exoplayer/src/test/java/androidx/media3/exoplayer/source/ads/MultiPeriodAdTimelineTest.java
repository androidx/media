package androidx.media3.exoplayer.source.ads;

import static androidx.media3.common.C.INDEX_UNSET;
import static androidx.media3.common.C.MICROS_PER_SECOND;
import static androidx.media3.common.C.TIME_END_OF_SOURCE;
import static androidx.media3.test.utils.FakeTimeline.FAKE_MEDIA_ITEM;
import static org.junit.Assert.assertEquals;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Timeline.Period;
import androidx.media3.test.utils.FakeTimeline;
import org.junit.Test;

public class MultiPeriodAdTimelineTest {
  @Test
  public void getPeriod() {
    String windowId = "windowId";

    FakeTimeline contentTimeline = new FakeTimeline(
        new FakeTimeline.TimelineWindowDefinition(
            3, // periodCount
            windowId,
            true, // isSeekable
            false, // isDynamic
            false, // isLive
            false, // isPlaceholder
            60 * MICROS_PER_SECOND, // durationUs
            0, // defaultPositionUs
            0, // windowOffsetInFirstPeriodUs
            AdPlaybackState.NONE, // adPlaybackState
            FAKE_MEDIA_ITEM // mediaItem
        )
    );

    MultiPeriodAdTimeline multiPeriodAdTimeline = new MultiPeriodAdTimeline(
        contentTimeline, new AdPlaybackState(
        "adsId",
        0L, 10 * MICROS_PER_SECOND, // period 0:  0s - 20s
        25 * MICROS_PER_SECOND, 35 * MICROS_PER_SECOND, // period 1: 20s - 40s
        45 * MICROS_PER_SECOND, 55 * MICROS_PER_SECOND // period 2: 40s - 60s
    ));

    Period period0 = new Period();
    multiPeriodAdTimeline.getPeriod(0, period0);

    // period durations are uniformly split windowDuration/periodCount
    assertEquals(20 * MICROS_PER_SECOND, period0.durationUs);

    // positions within the 0th period
    assertEquals(0, period0.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND));
    assertEquals(1, period0.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND));
    assertEquals(1, period0.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND));
    // no more ads to be played in 0th period
    assertEquals(INDEX_UNSET, period0.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND));

    Period period1 = new Period();
    multiPeriodAdTimeline.getPeriod(1, period1);

    // positions within the 1st period
    assertEquals(1, period1.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND)); // 21s
    assertEquals(2, period1.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND)); // 21s
    assertEquals(2, period1.getAdGroupIndexForPositionUs(10 * MICROS_PER_SECOND)); // 30s
    assertEquals(3, period1.getAdGroupIndexAfterPositionUs(10 * MICROS_PER_SECOND)); // 30s
    assertEquals(3, period1.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND)); // 39s
    // no more ads to be played in 1st period
    assertEquals(INDEX_UNSET, period1.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND)); // 39s

    Period period2 = new Period();
    multiPeriodAdTimeline.getPeriod(2, period2);

    // positions within the 2nd period
    assertEquals(3, period2.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND)); // 41s
    assertEquals(4, period2.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND)); // 41s
    assertEquals(4, period2.getAdGroupIndexForPositionUs(10 * MICROS_PER_SECOND)); // 50s
    assertEquals(5, period2.getAdGroupIndexAfterPositionUs(10 * MICROS_PER_SECOND)); // 50s
    assertEquals(5, period2.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND)); // 59s
    // no more ads to be played in 2nd period
    assertEquals(INDEX_UNSET, period2.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND)); // 59s
  }

  @Test
  public void getPeriod_postRoll() {
    String windowId = "windowId";

    FakeTimeline contentTimeline = new FakeTimeline(
        new FakeTimeline.TimelineWindowDefinition(
            2, // periodCount
            windowId,
            true, // isSeekable
            false, // isDynamic
            false, // isLive
            false, // isPlaceholder
            60 * MICROS_PER_SECOND, // durationUs
            0, // defaultPositionUs
            0, // windowOffsetInFirstPeriodUs
            AdPlaybackState.NONE, // adPlaybackState
            FAKE_MEDIA_ITEM // mediaItem
        )
    );

    MultiPeriodAdTimeline multiPeriodAdTimeline = new MultiPeriodAdTimeline(
        contentTimeline, new AdPlaybackState(
        "adsId",
        TIME_END_OF_SOURCE // period 1: 30s - 60s
    ));

    Period period0 = new Period();
    multiPeriodAdTimeline.getPeriod(0, period0);

    // period durations are uniformly split windowDuration/periodCount
    assertEquals(30 * MICROS_PER_SECOND, period0.durationUs);

    assertEquals(INDEX_UNSET, period0.getAdGroupIndexForPositionUs(15 * MICROS_PER_SECOND));
    // post-roll should not be played in 0th period
    assertEquals(INDEX_UNSET, period0.getAdGroupIndexAfterPositionUs(15 * MICROS_PER_SECOND));

    Period period1 = new Period();
    multiPeriodAdTimeline.getPeriod(1, period1);

    assertEquals(INDEX_UNSET, period1.getAdGroupIndexForPositionUs(29 * MICROS_PER_SECOND)); // 59s
    // post-roll in the end
    assertEquals(0, period1.getAdGroupIndexAfterPositionUs(29 * MICROS_PER_SECOND)); // 59s
  }
}
