/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.exoplayer.analytics;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit test for {@link DefaultPlaybackSessionManager}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultPlaybackSessionManagerTest {

  private DefaultPlaybackSessionManager sessionManager;

  @Mock private PlaybackSessionManager.Listener mockListener;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    sessionManager = new DefaultPlaybackSessionManager();
    sessionManager.setListener(mockListener);
  }

  @Test
  public void updatesSession_withEmptyTimeline_doesNotCreateNewSession() {
    EventTime eventTime =
        createEventTime(Timeline.EMPTY, /* windowIndex= */ 0, /* mediaPeriodId */ null);

    sessionManager.updateSessions(eventTime);

    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void updateSessions_withoutMediaPeriodId_createsNewSession() {
    Timeline timeline = new FakeTimeline();
    EventTime eventTime = createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId */ null);

    sessionManager.updateSessions(eventTime);

    verify(mockListener).onSessionCreated(eq(eventTime), anyString());
    verify(mockListener).onSessionActive(eq(eventTime), anyString());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void updateSessions_withMediaPeriodId_createsNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTime = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);

    sessionManager.updateSessions(eventTime);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId))
        .isEqualTo(sessionId.getValue());
  }

  @Test
  public void
      updateSessions_ofSameWindow_withMediaPeriodId_afterWithoutMediaPeriodId_doesNotCreateNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTime1 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId))
        .isEqualTo(sessionId.getValue());
  }

  @Test
  public void updateSessions_ofSameWindow_withAd_afterWithoutMediaPeriodId_createsNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    EventTime eventTime1 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> contentSessionId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> adSessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), contentSessionId.capture());
    verify(mockListener).onSessionCreated(eq(eventTime2), adSessionId.capture());
    verify(mockListener).onSessionActive(eventTime1, contentSessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(contentSessionId).isNotEqualTo(adSessionId);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId))
        .isEqualTo(adSessionId.getValue());
  }

  @Test
  public void
      updateSessions_ofSameWindow_withoutMediaPeriodId_afterMediaPeriodId_doesNotCreateNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);
    EventTime eventTime2 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId))
        .isEqualTo(sessionId.getValue());
  }

  @Test
  public void updateSessions_ofSameWindow_withoutMediaPeriodId_afterAd_doesNotCreateNewSession() {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ new Object(),
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10_000_000,
                FakeTimeline.createAdPlaybackState(
                    /* adsPerAdGroup= */ 1, /* adGroupTimesUs... */ 0)));
    MediaPeriodId adMediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    MediaPeriodId contentMediaPeriodIdDuringAd =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* windowSequenceNumber= */ 0,
            /* nextAdGroupIndex= */ 0);
    EventTime adEventTime = createEventTime(timeline, /* windowIndex= */ 0, adMediaPeriodId);
    EventTime contentEventTimeDuringAd =
        createEventTime(
            timeline, /* windowIndex= */ 0, contentMediaPeriodIdDuringAd, adMediaPeriodId);
    EventTime contentEventTimeWithoutMediaPeriodId =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);

    sessionManager.updateSessions(adEventTime);
    sessionManager.updateSessions(contentEventTimeWithoutMediaPeriodId);

    verify(mockListener).onSessionCreated(eq(contentEventTimeDuringAd), anyString());
    ArgumentCaptor<String> adSessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(adEventTime), adSessionId.capture());
    verify(mockListener).onSessionActive(adEventTime, adSessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, adMediaPeriodId))
        .isEqualTo(adSessionId.getValue());
  }

  @Test
  public void updateSessions_withOtherMediaPeriodId_ofSameWindow_doesNotCreateNewSession() {
    Timeline timeline =
        new FakeTimeline(new TimelineWindowDefinition(/* periodCount= */ 2, /* id= */ 0));
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 0);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId1))
        .isEqualTo(sessionId.getValue());
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId2))
        .isEqualTo(sessionId.getValue());
  }

  @Test
  public void updateSessions_withAd_ofSameWindow_createsNewSession() {
    Timeline timeline =
        new FakeTimeline(new TimelineWindowDefinition(/* periodCount= */ 2, /* id= */ 0));
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> contentSessionId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> adSessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), contentSessionId.capture());
    verify(mockListener).onSessionActive(eventTime1, contentSessionId.getValue());
    verify(mockListener).onSessionCreated(eq(eventTime2), adSessionId.capture());
    verifyNoMoreInteractions(mockListener);
    assertThat(contentSessionId).isNotEqualTo(adSessionId);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId1))
        .isEqualTo(contentSessionId.getValue());
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId2))
        .isEqualTo(adSessionId.getValue());
  }

  @Test
  public void updateSessions_ofOtherWindow_createsNewSession() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime1 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTime2 =
        createEventTime(timeline, /* windowIndex= */ 1, /* mediaPeriodId= */ null);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId1.capture());
    verify(mockListener).onSessionCreated(eq(eventTime2), sessionId2.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId1.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionId1).isNotEqualTo(sessionId2);
  }

  @Test
  public void updateSessions_withMediaPeriodId_ofOtherWindow_createsNewSession() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId1.capture());
    verify(mockListener).onSessionCreated(eq(eventTime2), sessionId2.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId1.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionId1).isNotEqualTo(sessionId2);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId1))
        .isEqualTo(sessionId1.getValue());
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId2))
        .isEqualTo(sessionId2.getValue());
  }

  @Test
  public void updateSessions_ofSameWindow_withNewWindowSequenceNumber_createsNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 1);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId1.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId1.getValue());
    verify(mockListener).onSessionCreated(eq(eventTime2), sessionId2.capture());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionId1).isNotEqualTo(sessionId2);
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId1))
        .isEqualTo(sessionId1.getValue());
    assertThat(sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId2))
        .isEqualTo(sessionId2.getValue());
  }

  @Test
  public void
      updateSessions_withoutMediaPeriodId_andPreviouslyCreatedSessions_doesNotCreateNewSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 1);
    MediaPeriodId mediaPeriodIdWithAd =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 0);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);
    EventTime eventTime3 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodIdWithAd);
    EventTime eventTime4 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);

    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);
    sessionManager.updateSessions(eventTime3);
    sessionManager.updateSessions(eventTime4);

    verify(mockListener).onSessionCreated(eq(eventTime1), anyString());
    verify(mockListener).onSessionActive(eq(eventTime1), anyString());
    verify(mockListener).onSessionCreated(eq(eventTime2), anyString());
    verify(mockListener).onSessionCreated(eq(eventTime3), anyString());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void updateSessions_afterSessionForMediaPeriodId_withSameMediaPeriodId_returnsSameValue() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTime = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);

    String expectedSessionId = sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId);
    sessionManager.updateSessions(eventTime);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionId.getValue()).isEqualTo(expectedSessionId);
  }

  @Test
  public void updateSessions_withoutMediaPeriodId_afterSessionForMediaPeriodId_returnsSameValue() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTime =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);

    String expectedSessionId = sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId);
    sessionManager.updateSessions(eventTime);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime), sessionId.capture());
    verify(mockListener).onSessionActive(eventTime, sessionId.getValue());
    verifyNoMoreInteractions(mockListener);
    assertThat(sessionId.getValue()).isEqualTo(expectedSessionId);
  }

  @Test
  public void
      updateSessions_withNewAd_afterDiscontinuitiesFromContentToAdAndBack_doesNotActivateNewAd() {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10 * C.MICROS_PER_SECOND,
                new AdPlaybackState(
                        /* adsId= */ new Object(),
                        /* adGroupTimesUs=... */ 2 * C.MICROS_PER_SECOND,
                        5 * C.MICROS_PER_SECOND)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)));
    EventTime adEventTime1 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime adEventTime2 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTime1 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 0));
    EventTime contentEventTime2 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 1));
    sessionManager.updateSessionsWithTimelineChange(contentEventTime1);
    sessionManager.updateSessions(adEventTime1);
    sessionManager.updateSessionsWithDiscontinuity(
        adEventTime1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    sessionManager.updateSessionsWithDiscontinuity(
        contentEventTime2, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    String adSessionId2 =
        sessionManager.getSessionForMediaPeriodId(adTimeline, adEventTime2.mediaPeriodId);

    sessionManager.updateSessions(adEventTime2);

    verify(mockListener, never()).onSessionActive(any(), eq(adSessionId2));
  }

  @Test
  public void getSessionForMediaPeriodId_returnsValue_butDoesNotCreateSession() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    String session = sessionManager.getSessionForMediaPeriodId(timeline, mediaPeriodId);

    assertThat(session).isNotEmpty();
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void belongsToSession_withSameWindowIndex_returnsTrue() {
    Timeline timeline = new FakeTimeline();
    EventTime eventTimeWithTimeline =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    EventTime eventTimeWithMediaPeriodId =
        createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId);
    sessionManager.updateSessions(eventTimeWithTimeline);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTimeWithTimeline), sessionId.capture());
    assertThat(sessionManager.belongsToSession(eventTimeWithTimeline, sessionId.getValue()))
        .isTrue();
    assertThat(sessionManager.belongsToSession(eventTimeWithMediaPeriodId, sessionId.getValue()))
        .isTrue();
  }

  @Test
  public void belongsToSession_withOtherWindowIndex_returnsFalse() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTimeOtherWindow =
        createEventTime(timeline, /* windowIndex= */ 1, /* mediaPeriodId= */ null);
    MediaPeriodId mediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1);
    EventTime eventTimeWithOtherMediaPeriodId =
        createEventTime(timeline, /* windowIndex= */ 1, mediaPeriodId);
    sessionManager.updateSessions(eventTime);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime), sessionId.capture());
    assertThat(sessionManager.belongsToSession(eventTimeOtherWindow, sessionId.getValue()))
        .isFalse();
    assertThat(
            sessionManager.belongsToSession(eventTimeWithOtherMediaPeriodId, sessionId.getValue()))
        .isFalse();
  }

  @Test
  public void belongsToSession_withOtherWindowSequenceNumber_returnsFalse() {
    Timeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId1 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId mediaPeriodId2 =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 1);
    EventTime eventTime1 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId1);
    EventTime eventTime2 = createEventTime(timeline, /* windowIndex= */ 0, mediaPeriodId2);
    sessionManager.updateSessions(eventTime1);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId.capture());
    assertThat(sessionManager.belongsToSession(eventTime2, sessionId.getValue())).isFalse();
  }

  @Test
  public void belongsToSession_withAd_returnsFalse() {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ new Object(),
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10_000_000,
                FakeTimeline.createAdPlaybackState(
                    /* adsPerAdGroup= */ 1, /* adGroupTimesUs... */ 0)));
    MediaPeriodId contentMediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0);
    MediaPeriodId adMediaPeriodId =
        new MediaPeriodId(
            timeline.getUidOfPeriod(/* periodIndex= */ 0),
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            /* windowSequenceNumber= */ 1);
    EventTime contentEventTime =
        createEventTime(timeline, /* windowIndex= */ 0, contentMediaPeriodId);
    EventTime adEventTime = createEventTime(timeline, /* windowIndex= */ 0, adMediaPeriodId);
    sessionManager.updateSessions(contentEventTime);
    sessionManager.updateSessions(adEventTime);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(contentEventTime), sessionId1.capture());
    verify(mockListener).onSessionCreated(eq(adEventTime), sessionId2.capture());
    assertThat(sessionManager.belongsToSession(adEventTime, sessionId1.getValue())).isFalse();
    assertThat(sessionManager.belongsToSession(contentEventTime, sessionId2.getValue())).isFalse();
    assertThat(sessionManager.belongsToSession(adEventTime, sessionId2.getValue())).isTrue();
  }

  @Test
  public void timelineUpdate_toEmpty_finishesAllSessionsAndDoesNotCreateNewSessions() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime1 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTime2 =
        createEventTime(timeline, /* windowIndex= */ 1, /* mediaPeriodId= */ null);
    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);

    EventTime eventTimeWithEmptyTimeline =
        createEventTime(Timeline.EMPTY, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    sessionManager.updateSessionsWithTimelineChange(eventTimeWithEmptyTimeline);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId1.capture());
    verify(mockListener).onSessionCreated(eq(eventTime2), sessionId2.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId1.getValue());
    verify(mockListener)
        .onSessionFinished(
            eventTimeWithEmptyTimeline,
            sessionId1.getValue(),
            /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener)
        .onSessionFinished(
            eventTimeWithEmptyTimeline,
            sessionId2.getValue(),
            /* automaticTransitionToNextPlayback= */ false);
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void timelineUpdate_resolvesWindowIndices() {
    Timeline initialTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 2, /* id= */ 100),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 200),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 300));
    EventTime eventForInitialTimelineId100 =
        createEventTime(
            initialTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                initialTimeline.getUidOfPeriod(/* periodIndex= */ 1),
                /* windowSequenceNumber= */ 0));
    EventTime eventForInitialTimelineId200 =
        createEventTime(
            initialTimeline,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                initialTimeline.getUidOfPeriod(/* periodIndex= */ 2),
                /* windowSequenceNumber= */ 1));
    EventTime eventForInitialTimelineId300 =
        createEventTime(
            initialTimeline,
            /* windowIndex= */ 2,
            new MediaPeriodId(
                initialTimeline.getUidOfPeriod(/* periodIndex= */ 3),
                /* windowSequenceNumber= */ 2));
    sessionManager.updateSessionsWithTimelineChange(eventForInitialTimelineId100);
    sessionManager.updateSessions(eventForInitialTimelineId200);
    sessionManager.updateSessions(eventForInitialTimelineId300);
    String sessionId100 =
        sessionManager.getSessionForMediaPeriodId(
            initialTimeline, eventForInitialTimelineId100.mediaPeriodId);
    String sessionId200 =
        sessionManager.getSessionForMediaPeriodId(
            initialTimeline, eventForInitialTimelineId200.mediaPeriodId);
    String sessionId300 =
        sessionManager.getSessionForMediaPeriodId(
            initialTimeline, eventForInitialTimelineId300.mediaPeriodId);

    Timeline timelineUpdate =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 300),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 100));
    EventTime eventForTimelineUpdateId100 =
        createEventTime(
            timelineUpdate,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                timelineUpdate.getUidOfPeriod(/* periodIndex= */ 1),
                /* windowSequenceNumber= */ 0));
    EventTime eventForTimelineUpdateId300 =
        createEventTime(
            timelineUpdate,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timelineUpdate.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 2));

    sessionManager.updateSessionsWithTimelineChange(eventForTimelineUpdateId100);
    String updatedSessionId100 =
        sessionManager.getSessionForMediaPeriodId(
            timelineUpdate, eventForTimelineUpdateId100.mediaPeriodId);
    String updatedSessionId300 =
        sessionManager.getSessionForMediaPeriodId(
            timelineUpdate, eventForTimelineUpdateId300.mediaPeriodId);

    verify(mockListener).onSessionCreated(eventForInitialTimelineId100, sessionId100);
    verify(mockListener).onSessionActive(eventForInitialTimelineId100, sessionId100);
    verify(mockListener).onSessionCreated(eventForInitialTimelineId200, sessionId200);
    verify(mockListener).onSessionCreated(eventForInitialTimelineId300, sessionId300);
    verify(mockListener)
        .onSessionFinished(
            eventForTimelineUpdateId100,
            sessionId200,
            /* automaticTransitionToNextPlayback= */ false);
    verifyNoMoreInteractions(mockListener);
    assertThat(updatedSessionId100).isEqualTo(sessionId100);
    assertThat(updatedSessionId300).isEqualTo(sessionId300);
  }

  @Test
  public void timelineUpdate_withContent_doesNotFinishFuturePostrollAd() {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10 * C.MICROS_PER_SECOND,
                new AdPlaybackState(
                        /* adsId= */ new Object(), /* adGroupTimesUs=... */ C.TIME_END_OF_SOURCE)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)));
    EventTime adEventTime =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTime =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 0));
    sessionManager.updateSessions(contentEventTime);
    sessionManager.updateSessions(adEventTime);

    sessionManager.updateSessionsWithTimelineChange(contentEventTime);

    verify(mockListener, never()).onSessionFinished(any(), anyString(), anyBoolean());
  }

  @Test
  public void timelineUpdate_toNewMediaWithWindowIndexOnly_finishesOtherSessions() {
    Timeline firstTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 1000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 2000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 3000));
    EventTime eventTimeFirstTimelineWithPeriodId =
        createEventTime(
            firstTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                firstTimeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTimeFirstTimelineWindowOnly1 =
        createEventTime(firstTimeline, /* windowIndex= */ 1, /* mediaPeriodId= */ null);
    EventTime eventTimeFirstTimelineWindowOnly2 =
        createEventTime(firstTimeline, /* windowIndex= */ 2, /* mediaPeriodId= */ null);
    Timeline secondTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 2000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 4000));
    EventTime eventTimeSecondTimeline =
        createEventTime(secondTimeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    sessionManager.updateSessionsWithTimelineChange(eventTimeFirstTimelineWithPeriodId);
    sessionManager.updateSessions(eventTimeFirstTimelineWindowOnly1);
    sessionManager.updateSessions(eventTimeFirstTimelineWindowOnly2);

    sessionManager.updateSessionsWithTimelineChange(eventTimeSecondTimeline);

    InOrder inOrder = inOrder(mockListener);
    ArgumentCaptor<String> firstId = ArgumentCaptor.forClass(String.class);
    inOrder
        .verify(mockListener)
        .onSessionCreated(eq(eventTimeFirstTimelineWithPeriodId), firstId.capture());
    inOrder
        .verify(mockListener)
        .onSessionActive(eventTimeFirstTimelineWithPeriodId, firstId.getValue());
    ArgumentCaptor<String> secondId = ArgumentCaptor.forClass(String.class);
    inOrder
        .verify(mockListener)
        .onSessionCreated(eq(eventTimeFirstTimelineWindowOnly1), secondId.capture());
    ArgumentCaptor<String> thirdId = ArgumentCaptor.forClass(String.class);
    inOrder
        .verify(mockListener)
        .onSessionCreated(eq(eventTimeFirstTimelineWindowOnly2), thirdId.capture());
    // The sessions may finish at the same time, so the order of these two callbacks is undefined.
    ArgumentCaptor<String> finishedSessions = ArgumentCaptor.forClass(String.class);
    inOrder
        .verify(mockListener, times(2))
        .onSessionFinished(
            eq(eventTimeSecondTimeline),
            finishedSessions.capture(),
            /* automaticTransitionToNextPlayback= */ eq(false));
    assertThat(finishedSessions.getAllValues())
        .containsExactly(firstId.getValue(), thirdId.getValue());
    inOrder.verify(mockListener).onSessionActive(eventTimeSecondTimeline, secondId.getValue());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void timelineUpdate_toNewMediaWithMediaPeriodId_finishesOtherSessions() {
    Timeline firstTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 1000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 2000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 3000));
    EventTime eventTimeFirstTimeline1 =
        createEventTime(
            firstTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                firstTimeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTimeFirstTimeline2 =
        createEventTime(
            firstTimeline,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                firstTimeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1));
    EventTime eventTimeFirstTimeline3 =
        createEventTime(
            firstTimeline,
            /* windowIndex= */ 2,
            new MediaPeriodId(
                firstTimeline.getUidOfPeriod(/* periodIndex= */ 2), /* windowSequenceNumber= */ 2));
    Timeline secondTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 2000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 1000),
            new TimelineWindowDefinition(/* periodCount= */ 1, /* id= */ 3000));
    EventTime eventTimeSecondTimeline =
        createEventTime(
            secondTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                secondTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 1));
    sessionManager.updateSessionsWithTimelineChange(eventTimeFirstTimeline1);
    sessionManager.updateSessions(eventTimeFirstTimeline2);
    sessionManager.updateSessions(eventTimeFirstTimeline3);

    sessionManager.updateSessionsWithTimelineChange(eventTimeSecondTimeline);

    InOrder inOrder = inOrder(mockListener);
    ArgumentCaptor<String> firstId = ArgumentCaptor.forClass(String.class);
    inOrder.verify(mockListener).onSessionCreated(eq(eventTimeFirstTimeline1), firstId.capture());
    inOrder.verify(mockListener).onSessionActive(eventTimeFirstTimeline1, firstId.getValue());
    ArgumentCaptor<String> secondId = ArgumentCaptor.forClass(String.class);
    inOrder.verify(mockListener).onSessionCreated(eq(eventTimeFirstTimeline2), secondId.capture());
    ArgumentCaptor<String> thirdId = ArgumentCaptor.forClass(String.class);
    inOrder.verify(mockListener).onSessionCreated(eq(eventTimeFirstTimeline3), thirdId.capture());
    inOrder
        .verify(mockListener)
        .onSessionFinished(
            eventTimeSecondTimeline,
            firstId.getValue(),
            /* automaticTransitionToNextPlayback= */ false);
    inOrder.verify(mockListener).onSessionActive(eventTimeSecondTimeline, secondId.getValue());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void positionDiscontinuity_withinWindow_doesNotFinishSession() {
    Timeline timeline =
        new FakeTimeline(new TimelineWindowDefinition(/* periodCount= */ 2, /* id= */ 100));
    EventTime eventTime1 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTime2 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 0));
    sessionManager.updateSessionsWithTimelineChange(eventTime1);
    sessionManager.updateSessions(eventTime2);

    sessionManager.updateSessionsWithDiscontinuity(
        eventTime2, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    verify(mockListener).onSessionCreated(eq(eventTime1), anyString());
    verify(mockListener).onSessionActive(eq(eventTime1), anyString());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void positionDiscontinuity_toNewWindow_withPeriodTransitionReason_finishesSession() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime1 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTime2 =
        createEventTime(
            timeline,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1));
    sessionManager.updateSessionsWithTimelineChange(eventTime1);
    sessionManager.updateSessions(eventTime2);
    String sessionId1 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime1.mediaPeriodId);
    String sessionId2 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime2.mediaPeriodId);

    sessionManager.updateSessionsWithDiscontinuity(
        eventTime2, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    verify(mockListener).onSessionCreated(eventTime1, sessionId1);
    verify(mockListener).onSessionActive(eventTime1, sessionId1);
    verify(mockListener).onSessionCreated(eq(eventTime2), anyString());
    verify(mockListener)
        .onSessionFinished(eventTime2, sessionId1, /* automaticTransitionToNextPlayback= */ true);
    verify(mockListener).onSessionActive(eventTime2, sessionId2);
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void
      positionDiscontinuity_toNewWindow_withMediaPeriodIds_withSeekTransitionReason_finishesSession() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime1 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTime2 =
        createEventTime(
            timeline,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1));
    sessionManager.updateSessionsWithTimelineChange(eventTime1);
    sessionManager.updateSessions(eventTime2);
    String sessionId1 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime1.mediaPeriodId);
    String sessionId2 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime2.mediaPeriodId);

    sessionManager.updateSessionsWithDiscontinuity(eventTime2, Player.DISCONTINUITY_REASON_SEEK);

    verify(mockListener).onSessionCreated(eventTime1, sessionId1);
    verify(mockListener).onSessionActive(eventTime1, sessionId1);
    verify(mockListener).onSessionCreated(eq(eventTime2), anyString());
    verify(mockListener)
        .onSessionFinished(eventTime2, sessionId1, /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener).onSessionActive(eventTime2, sessionId2);
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void
      positionDiscontinuity_toNewWindow_withoutMediaPeriodIds_withSeekTransitionReason_finishesSession() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 2);
    EventTime eventTime1 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTime2 =
        createEventTime(timeline, /* windowIndex= */ 1, /* mediaPeriodId= */ null);
    sessionManager.updateSessionsWithTimelineChange(eventTime1);

    sessionManager.updateSessionsWithDiscontinuity(eventTime2, Player.DISCONTINUITY_REASON_SEEK);

    ArgumentCaptor<String> sessionId1 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> sessionId2 = ArgumentCaptor.forClass(String.class);
    verify(mockListener).onSessionCreated(eq(eventTime1), sessionId1.capture());
    verify(mockListener).onSessionActive(eventTime1, sessionId1.getValue());
    verify(mockListener).onSessionCreated(eq(eventTime2), sessionId2.capture());
    verify(mockListener)
        .onSessionFinished(
            eventTime2, sessionId1.getValue(), /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener).onSessionActive(eventTime2, sessionId2.getValue());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void positionDiscontinuity_toSameWindow_withoutMediaPeriodId_doesNotFinishSession() {
    Timeline timeline = new FakeTimeline();
    EventTime eventTime1 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTime2 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    sessionManager.updateSessionsWithTimelineChange(eventTime1);
    sessionManager.updateSessions(eventTime2);

    sessionManager.updateSessionsWithDiscontinuity(eventTime2, Player.DISCONTINUITY_REASON_SEEK);

    verify(mockListener, never()).onSessionFinished(any(), anyString(), anyBoolean());
  }

  @Test
  public void positionDiscontinuity_toNewWindow_finishesOnlyPastSessions() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 4);
    EventTime eventTime1 =
        createEventTime(
            timeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 0), /* windowSequenceNumber= */ 0));
    EventTime eventTime2 =
        createEventTime(
            timeline,
            /* windowIndex= */ 1,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 1), /* windowSequenceNumber= */ 1));
    EventTime eventTime3 =
        createEventTime(
            timeline,
            /* windowIndex= */ 2,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 2), /* windowSequenceNumber= */ 2));
    EventTime eventTime4 =
        createEventTime(
            timeline,
            /* windowIndex= */ 3,
            new MediaPeriodId(
                timeline.getUidOfPeriod(/* periodIndex= */ 3), /* windowSequenceNumber= */ 3));
    sessionManager.updateSessionsWithTimelineChange(eventTime1);
    sessionManager.updateSessions(eventTime1);
    sessionManager.updateSessions(eventTime2);
    sessionManager.updateSessions(eventTime3);
    sessionManager.updateSessions(eventTime4);
    String sessionId1 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime1.mediaPeriodId);
    String sessionId2 =
        sessionManager.getSessionForMediaPeriodId(timeline, eventTime2.mediaPeriodId);

    sessionManager.updateSessionsWithDiscontinuity(eventTime3, Player.DISCONTINUITY_REASON_SEEK);

    verify(mockListener).onSessionCreated(eventTime1, sessionId1);
    verify(mockListener).onSessionActive(eventTime1, sessionId1);
    verify(mockListener).onSessionCreated(eventTime2, sessionId2);
    verify(mockListener).onSessionCreated(eq(eventTime3), anyString());
    verify(mockListener).onSessionCreated(eq(eventTime4), anyString());
    verify(mockListener)
        .onSessionFinished(eventTime3, sessionId1, /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener)
        .onSessionFinished(eventTime3, sessionId2, /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener).onSessionActive(eq(eventTime3), anyString());
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void positionDiscontinuity_fromAdToContent_finishesAd() {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10 * C.MICROS_PER_SECOND,
                new AdPlaybackState(
                        /* adsId= */ new Object(), /* adGroupTimesUs=... */
                        0,
                        5 * C.MICROS_PER_SECOND)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)));
    EventTime adEventTime1 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime adEventTime2 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTimeDuringPreroll =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            /* eventMediaPeriodId= */ new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 0),
            /* currentMediaPeriodId= */ new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTimeBetweenAds =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 1));
    sessionManager.updateSessionsWithTimelineChange(adEventTime1);
    sessionManager.updateSessions(adEventTime2);
    String adSessionId1 =
        sessionManager.getSessionForMediaPeriodId(adTimeline, adEventTime1.mediaPeriodId);
    String contentSessionId =
        sessionManager.getSessionForMediaPeriodId(
            adTimeline, contentEventTimeDuringPreroll.mediaPeriodId);

    sessionManager.updateSessionsWithDiscontinuity(
        contentEventTimeBetweenAds, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    InOrder inOrder = inOrder(mockListener);
    inOrder.verify(mockListener).onSessionCreated(contentEventTimeDuringPreroll, contentSessionId);
    inOrder.verify(mockListener).onSessionCreated(adEventTime1, adSessionId1);
    inOrder.verify(mockListener).onSessionActive(adEventTime1, adSessionId1);
    inOrder.verify(mockListener).onAdPlaybackStarted(adEventTime1, contentSessionId, adSessionId1);
    inOrder.verify(mockListener).onSessionCreated(eq(adEventTime2), anyString());
    inOrder
        .verify(mockListener)
        .onSessionFinished(
            contentEventTimeBetweenAds,
            adSessionId1,
            /* automaticTransitionToNextPlayback= */ true);
    inOrder.verify(mockListener).onSessionActive(eq(contentEventTimeBetweenAds), anyString());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void positionDiscontinuity_fromContentToAd_doesNotFinishSessions() {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10 * C.MICROS_PER_SECOND,
                new AdPlaybackState(
                        /* adsId= */ new Object(), /* adGroupTimesUs=... */
                        2 * C.MICROS_PER_SECOND,
                        5 * C.MICROS_PER_SECOND)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)));
    EventTime adEventTime1 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime adEventTime2 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTime =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 0));
    sessionManager.updateSessionsWithTimelineChange(contentEventTime);
    sessionManager.updateSessions(adEventTime1);
    sessionManager.updateSessions(adEventTime2);

    sessionManager.updateSessionsWithDiscontinuity(
        adEventTime1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    verify(mockListener, never()).onSessionFinished(any(), anyString(), anyBoolean());
  }

  @Test
  public void positionDiscontinuity_fromAdToAd_finishesPastAds_andNotifiesAdPlaybackStated() {
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* durationUs= */ 10 * C.MICROS_PER_SECOND,
                new AdPlaybackState(
                        /* adsId= */ new Object(), /* adGroupTimesUs=... */
                        0,
                        5 * C.MICROS_PER_SECOND)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)));
    EventTime adEventTime1 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime adEventTime2 =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                /* windowSequenceNumber= */ 0));
    EventTime contentEventTime =
        createEventTime(
            adTimeline,
            /* windowIndex= */ 0,
            new MediaPeriodId(
                adTimeline.getUidOfPeriod(/* periodIndex= */ 0),
                /* windowSequenceNumber= */ 0,
                /* nextAdGroupIndex= */ 1));
    sessionManager.updateSessionsWithTimelineChange(contentEventTime);
    sessionManager.updateSessions(adEventTime1);
    sessionManager.updateSessions(adEventTime2);
    String contentSessionId =
        sessionManager.getSessionForMediaPeriodId(adTimeline, contentEventTime.mediaPeriodId);
    String adSessionId1 =
        sessionManager.getSessionForMediaPeriodId(adTimeline, adEventTime1.mediaPeriodId);
    String adSessionId2 =
        sessionManager.getSessionForMediaPeriodId(adTimeline, adEventTime2.mediaPeriodId);

    sessionManager.updateSessionsWithDiscontinuity(
        adEventTime1, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    sessionManager.updateSessionsWithDiscontinuity(adEventTime2, Player.DISCONTINUITY_REASON_SEEK);

    verify(mockListener).onSessionCreated(eq(contentEventTime), anyString());
    verify(mockListener).onSessionActive(eq(contentEventTime), anyString());
    verify(mockListener).onSessionCreated(adEventTime1, adSessionId1);
    verify(mockListener).onSessionCreated(adEventTime2, adSessionId2);
    verify(mockListener).onAdPlaybackStarted(adEventTime1, contentSessionId, adSessionId1);
    verify(mockListener).onSessionActive(adEventTime1, adSessionId1);
    verify(mockListener)
        .onSessionFinished(
            adEventTime2, adSessionId1, /* automaticTransitionToNextPlayback= */ false);
    verify(mockListener).onAdPlaybackStarted(adEventTime2, contentSessionId, adSessionId2);
    verify(mockListener).onSessionActive(adEventTime2, adSessionId2);
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void finishAllSessions_callsOnSessionFinishedForAllCreatedSessions() {
    Timeline timeline = new FakeTimeline(/* windowCount= */ 4);
    EventTime eventTimeWindow0 =
        createEventTime(timeline, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    EventTime eventTimeWindow2 =
        createEventTime(timeline, /* windowIndex= */ 2, /* mediaPeriodId= */ null);
    // Actually create sessions for window 0 and 2.
    sessionManager.updateSessions(eventTimeWindow0);
    sessionManager.updateSessions(eventTimeWindow2);
    // Query information about session for window 1, but don't create it.
    sessionManager.getSessionForMediaPeriodId(
        timeline,
        new MediaPeriodId(
            timeline.getPeriod(/* periodIndex= */ 1, new Timeline.Period(), /* setIds= */ true).uid,
            /* windowSequenceNumber= */ 123));
    verify(mockListener, times(2)).onSessionCreated(any(), anyString());

    EventTime finishEventTime =
        createEventTime(Timeline.EMPTY, /* windowIndex= */ 0, /* mediaPeriodId= */ null);
    sessionManager.finishAllSessions(finishEventTime);

    verify(mockListener, times(2)).onSessionFinished(eq(finishEventTime), anyString(), eq(false));
  }

  private static EventTime createEventTime(
      Timeline timeline, int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    return new EventTime(
        /* realtimeMs= */ 0,
        timeline,
        windowIndex,
        mediaPeriodId,
        /* eventPlaybackPositionMs= */ 0,
        timeline,
        windowIndex,
        mediaPeriodId,
        /* currentPlaybackPositionMs= */ 0,
        /* totalBufferedDurationMs= */ 0);
  }

  private static EventTime createEventTime(
      Timeline timeline,
      int windowIndex,
      @Nullable MediaPeriodId eventMediaPeriodId,
      @Nullable MediaPeriodId currentMediaPeriodId) {
    return new EventTime(
        /* realtimeMs= */ 0,
        timeline,
        windowIndex,
        eventMediaPeriodId,
        /* eventPlaybackPositionMs= */ 0,
        timeline,
        windowIndex,
        currentMediaPeriodId,
        /* currentPlaybackPositionMs= */ 0,
        /* totalBufferedDurationMs= */ 0);
  }
}
