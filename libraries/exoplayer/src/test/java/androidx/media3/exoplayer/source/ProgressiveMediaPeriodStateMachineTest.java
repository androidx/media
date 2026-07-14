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
package androidx.media3.exoplayer.source;

import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_CANCELING;
import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_DEFERRED_RETRY_PENDING;
import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_ERROR;
import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_FINISHED;
import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_IDLE;
import static androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine.STATE_LOADING;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.exoplayer.source.ProgressiveMediaPeriod.LoadingStateMachine;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ProgressiveMediaPeriod.LoadingStateMachine}. */
@RunWith(AndroidJUnit4.class)
public class ProgressiveMediaPeriodStateMachineTest {

  private LoadingStateMachine stateMachine;

  @Before
  public void setUp() {
    stateMachine = new LoadingStateMachine();
  }

  @Test
  public void initialState_isIdle() {
    int state = stateMachine.getState();

    assertThat(state).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.isPendingReset()).isFalse();
  }

  @Test
  public void onStartLoading_idleState_transitionsToLoading() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.isPendingReset()).isFalse();
  }

  @Test
  public void onLoadCompleted_loadingState_transitionsToFinished() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onLoadCompleted();

    assertThat(stateMachine.getState()).isEqualTo(STATE_FINISHED);
  }

  @Test
  public void onSeek_loadingState_outsideBuffer_transitionsToCancelingForSeek() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    assertThat(stateMachine.getState()).isEqualTo(STATE_CANCELING);
    assertThat(stateMachine.isPendingReset()).isTrue();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(5000);
  }

  @Test
  public void onSeek_loadingState_insideBuffer_remainsInLoadingState() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ true, /* loaderIsLoading= */ true);

    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.isPendingReset()).isFalse();
  }

  @Test
  public void onLoadCanceled_cancelingForSeekState_transitionsToIdle() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    stateMachine.onLoadCanceled(/* released= */ false);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.isPendingReset()).isTrue();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(5000);
  }

  @Test
  public void onLoadError_isLengthKnown_resumesFromCurrentPosition() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);

    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ true,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 25);

    assertThat(stateMachine.isDeferredRetryPending()).isFalse();
    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.getExtractedSamplesCountAtStartOfLoad()).isEqualTo(25);
  }

  @Test
  public void onLoadError_unknownLengthAndPrepared_defersRetry() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);

    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 10);

    assertThat(stateMachine.isDeferredRetryPending()).isTrue();
    assertThat(stateMachine.getState()).isEqualTo(STATE_DEFERRED_RETRY_PENDING);
  }

  @Test
  public void onLoadError_unknownLengthAndUnprepared_retriesFromStart() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);

    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ false,
        /* currentExtractedSamplesCount= */ 10);

    assertThat(stateMachine.isDeferredRetryPending()).isFalse();
    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
    assertThat(stateMachine.getLastSeekPositionUs()).isEqualTo(0);
    assertThat(stateMachine.getExtractedSamplesCountAtStartOfLoad()).isEqualTo(0);
  }

  @Test
  public void onLoadError_unknownLengthAndReadingSuppressed_retriesFromStart() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 0);

    assertThat(stateMachine.isDeferredRetryPending()).isFalse();
    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.getLastSeekPositionUs()).isEqualTo(0);
  }

  @Test
  public void onDeferredRetryStarted_deferredRetryState_transitionsToIdleAndSetsDiscontinuity() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 0);

    stateMachine.onDeferredRetryStarted();

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(0);
    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 1)).isEqualTo(0);
  }

  @Test
  public void onPrepared_singleTrack_configuresPendingResetPosition() {
    stateMachine.onPrepared(/* isSingleTrack= */ true, /* positionUs= */ 1000);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.isPendingReset()).isTrue();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(1000);
  }

  @Test
  public void onPrepared_nonSingleTrack_doesNotConfigurePendingResetPosition() {
    stateMachine.onPrepared(/* isSingleTrack= */ false, /* positionUs= */ 1000);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.isPendingReset()).isFalse();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void onLoadCompleted_resetsPendingResetPositionUs() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    stateMachine.onLoadCompleted();

    assertThat(stateMachine.getState()).isEqualTo(STATE_FINISHED);
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(C.TIME_UNSET);
    assertThat(stateMachine.isPendingReset()).isFalse();
  }

  @Test
  public void onSeek_whenAlreadyPendingReset_updatesPendingPosition() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    stateMachine.onSeek(
        /* positionUs= */ 8000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(8000);
    assertThat(stateMachine.getLastSeekPositionUs()).isEqualTo(8000);
  }

  @Test
  public void onSeek_loaderNotLoading_transitionsToIdle() {
    stateMachine.onSeek(
        /* positionUs= */ 3000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ false);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
    assertThat(stateMachine.isPendingReset()).isTrue();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(3000);
  }

  @Test
  public void
      onTrackSelection_enabledTracksZeroAndLoading_transitionsToCancelingAndClearsDiscontinuity() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ true);

    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 0, /* loaderIsLoading= */ true);

    assertThat(stateMachine.getState()).isEqualTo(STATE_CANCELING);
    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void onTrackSelection_enabledTracksZeroAndNotLoading_transitionsToIdle() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 0, /* loaderIsLoading= */ false);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
  }

  @Test
  public void onReevaluateBuffer_allConditionsMet_transitionsToFinished() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onReevaluateBuffer(
        /* hasEnabledTracks= */ true, /* haveSampleQueuesReachedEndTimeUs= */ true);

    assertThat(stateMachine.getState()).isEqualTo(STATE_FINISHED);
  }

  @Test
  public void onReevaluateBuffer_hasPendingReset_doesNotTransitionToFinished() {
    stateMachine.onPrepared(/* isSingleTrack= */ true, /* positionUs= */ 1000);

    stateMachine.onReevaluateBuffer(
        /* hasEnabledTracks= */ true, /* haveSampleQueuesReachedEndTimeUs= */ true);

    assertThat(stateMachine.getState()).isEqualTo(STATE_IDLE);
  }

  @Test
  public void onFatalLoadError_transitionsToError() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onFatalLoadError();

    assertThat(stateMachine.getState()).isEqualTo(STATE_ERROR);
  }

  @Test
  public void onStartLoading_clearsPendingResetPositionUs() {
    stateMachine.onPrepared(/* isSingleTrack= */ true, /* positionUs= */ 2000);

    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 5);

    assertThat(stateMachine.getState()).isEqualTo(STATE_LOADING);
    assertThat(stateMachine.isPendingReset()).isFalse();
    assertThat(stateMachine.getPendingResetPositionUs()).isEqualTo(C.TIME_UNSET);
    assertThat(stateMachine.getExtractedSamplesCountAtStartOfLoad()).isEqualTo(5);
  }

  @Test
  public void readDiscontinuity_beforeTrackSelection_returnsTimeUnset() {
    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    assertThat(discontinuity).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void readDiscontinuity_afterTrackSelectionWithPreroll_handlesInitialDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    assertThat(discontinuity).isEqualTo(0);
  }

  @Test
  public void readDiscontinuity_afterDiscontinuityReadOnce_returnsTimeUnsetOnSubsequentRead() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);
    long unused = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    assertThat(discontinuity).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void readDiscontinuity_usesStreamPrerollFlags_ignoresInitialDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    stateMachine.setUsesStreamPrerollFlags();

    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void readDiscontinuity_afterDeferredRetryWithoutProgress_returnsTimeUnset() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);
    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 10);

    stateMachine.onDeferredRetryStarted();

    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void readDiscontinuity_afterDeferredRetryWithProgress_returnsDiscontinuityOnce() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);
    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 10);
    stateMachine.onDeferredRetryStarted();

    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 1);

    assertThat(discontinuity).isEqualTo(0);
  }

  @Test
  public void canContinueLoading_idleUnprepared_returnsTrue() {
    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ false, /* enabledTrackCount= */ 0);

    assertThat(canContinue).isTrue();
  }

  @Test
  public void canContinueLoading_preparedZeroTracks_returnsFalse() {
    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 0);

    assertThat(canContinue).isFalse();
  }

  @Test
  public void canContinueLoading_preparedOneTrack_returnsTrue() {
    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 1);

    assertThat(canContinue).isTrue();
  }

  @Test
  public void canContinueLoading_cancelingState_returnsFalse() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ false, /* loaderIsLoading= */ true);

    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 1);

    assertThat(canContinue).isFalse();
  }

  @Test
  public void canContinueLoading_deferredRetryPending_returnsFalse() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 0);

    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 1);

    assertThat(canContinue).isFalse();
  }

  @Test
  public void canContinueLoading_errorState_returnsFalse() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onFatalLoadError();

    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 1);

    assertThat(canContinue).isFalse();
  }

  @Test
  public void canContinueLoading_finishedState_returnsFalse() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);
    stateMachine.onLoadCompleted();

    boolean canContinue =
        stateMachine.canContinueLoading(
            /* isPreparedOrSingleTrack= */ true, /* enabledTrackCount= */ 1);

    assertThat(canContinue).isFalse();
  }

  @Test
  public void isFinished_inIdleState_returnsFalse() {
    boolean finished = stateMachine.isFinished();

    assertThat(finished).isFalse();
  }

  @Test
  public void isFinished_inFinishedState_returnsTrue() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onLoadCompleted();

    assertThat(stateMachine.isFinished()).isTrue();
  }

  @Test
  public void isDeferredRetryPending_inDeferredRetryState_returnsTrue() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 0);

    stateMachine.onLoadError(
        /* isLengthKnownOrHasDuration= */ false,
        /* isPrepared= */ true,
        /* currentExtractedSamplesCount= */ 0);

    assertThat(stateMachine.isDeferredRetryPending()).isTrue();
  }

  @Test
  public void isLastSeekPosition_matchesLastSeekPosition() {
    stateMachine.onSeek(
        /* positionUs= */ 5000, /* canSeekInsideBuffer= */ true, /* loaderIsLoading= */ true);

    boolean matches = stateMachine.isLastSeekPosition(5000);

    assertThat(matches).isTrue();
    assertThat(stateMachine.isLastSeekPosition(1000)).isFalse();
  }

  @Test
  public void hasExtractedProgressSinceLoadStart_evaluatesProgress() {
    stateMachine.onStartLoading(/* currentExtractedSamplesCount= */ 10);

    boolean progressMade = stateMachine.hasExtractedProgressSinceLoadStart(11);

    assertThat(progressMade).isTrue();
    assertThat(stateMachine.hasExtractedProgressSinceLoadStart(10)).isFalse();
  }

  @Test
  public void onTrackSelection_noPreroll_clearsPendingInitialDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    assertThat(discontinuity).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void onTrackSelection_withPreroll_retainsPendingInitialDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    long discontinuity = stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0);

    assertThat(discontinuity).isEqualTo(0);
  }

  @Test
  public void onTrackSelection_subsequentCallsDoNotOverwriteClearedDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void onTrackSelection_tracksCleared_clearsInitialDiscontinuity() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ true, /* enabledTrackCount= */ 1, /* loaderIsLoading= */ false);

    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 0, /* loaderIsLoading= */ false);

    assertThat(stateMachine.readDiscontinuity(/* currentExtractedSamplesCount= */ 0))
        .isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void onTrackSelection_zeroTracks_setsSeenFirstTrackSelectionTrue() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 0, /* loaderIsLoading= */ false);

    boolean seenFirstSelection = stateMachine.hasSeenFirstTrackSelection();

    assertThat(seenFirstSelection).isTrue();
  }

  @Test
  public void onTrackSelection_nonZeroTracks_setsSeenFirstTrackSelectionTrue() {
    stateMachine.onTrackSelection(
        /* hasPreroll= */ false, /* enabledTrackCount= */ 2, /* loaderIsLoading= */ false);

    boolean seenFirstSelection = stateMachine.hasSeenFirstTrackSelection();

    assertThat(seenFirstSelection).isTrue();
  }
}
