/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import android.media.AudioFormat;
import android.net.Uri;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.KeyRequestInfo;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowLog;

@RunWith(AndroidJUnit4.class)
public final class EventLoggerTest {

  private static final String CUSTOM_TAG = "TestELTag";
  private static final EventTime EVENT_TIME = createEventTime();
  private static final Metadata METADATA =
      new Metadata(
          new TextInformationFrame(
              "FOO", "fake text info frame", ImmutableList.of("val1", "val2")));
  private static final Format AUDIO_FORMAT =
      ExoPlayerTestRunner.AUDIO_FORMAT.buildUpon().setMetadata(METADATA).build();
  private static final MediaLoadData MEDIA_LOAD_DATA =
      new MediaLoadData(
          C.DATA_TYPE_MEDIA,
          C.TRACK_TYPE_AUDIO,
          AUDIO_FORMAT,
          C.SELECTION_FLAG_DEFAULT,
          /* trackSelectionData= */ null,
          /* mediaStartTimeMs= */ 123,
          /* mediaEndTimeMs= */ 456);

  private final EventLogger eventLogger;

  public EventLoggerTest() {
    this.eventLogger = new EventLogger(CUSTOM_TAG);
  }

  @Test
  public void onIsLoadingChanged() {
    eventLogger.onIsLoadingChanged(EVENT_TIME, true);
    assertThat(onlyLogMessage())
        .isEqualTo("loading [eventTime=0.02, mediaPos=0.46, window=0, period=0, true]");
  }

  @Test
  public void onPlaybackStateChanged() {
    eventLogger.onPlaybackStateChanged(EVENT_TIME, Player.STATE_BUFFERING);
    assertThat(onlyLogMessage())
        .isEqualTo("state [eventTime=0.02, mediaPos=0.46, window=0, period=0, BUFFERING]");
  }

  @Test
  public void onPlayWhenReadyChanged() {
    eventLogger.onPlayWhenReadyChanged(
        EVENT_TIME, /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "playWhenReady [eventTime=0.02, mediaPos=0.46, window=0, period=0, true,"
                + " USER_REQUEST]");
  }

  @Test
  public void onPlaybackSuppressionReasonChanged() {
    eventLogger.onPlaybackSuppressionReasonChanged(
        EVENT_TIME, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "playbackSuppressionReason [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " TRANSIENT_AUDIO_FOCUS_LOSS]");
  }

  @Test
  public void onIsPlayingChanged() {
    eventLogger.onIsPlayingChanged(EVENT_TIME, /* isPlaying= */ true);
    assertThat(onlyLogMessage())
        .isEqualTo("isPlaying [eventTime=0.02, mediaPos=0.46, window=0, period=0, true]");
  }

  @Test
  public void onRepeatModeChanged() {
    eventLogger.onRepeatModeChanged(EVENT_TIME, Player.REPEAT_MODE_ONE);
    assertThat(onlyLogMessage())
        .isEqualTo("repeatMode [eventTime=0.02, mediaPos=0.46, window=0, period=0, ONE]");
  }

  @Test
  public void onShuffleModeChanged() {
    eventLogger.onShuffleModeChanged(EVENT_TIME, /* shuffleModeEnabled= */ true);
    assertThat(onlyLogMessage())
        .isEqualTo("shuffleModeEnabled [eventTime=0.02, mediaPos=0.46, window=0, period=0, true]");
  }

  @Test
  public void onPositionDiscontinuity() {
    eventLogger.onPositionDiscontinuity(
        EVENT_TIME,
        new PositionInfo(
            /* windowUid= */ new Object(),
            /* mediaItemIndex= */ 0,
            MediaItem.fromUri("http://example.test"),
            /* periodUid= */ new Object(),
            /* periodIndex= */ 0,
            /* positionMs= */ 123,
            /* contentPositionMs= */ 456,
            /* adGroupIndex= */ -1,
            /* adIndexInAdGroup= */ -1),
        new PositionInfo(
            /* windowUid= */ new Object(),
            /* mediaItemIndex= */ 1,
            MediaItem.fromUri("http://example.test"),
            /* periodUid= */ new Object(),
            /* periodIndex= */ 1,
            /* positionMs= */ 789,
            /* contentPositionMs= */ 1012,
            /* adGroupIndex= */ -1,
            /* adIndexInAdGroup= */ -1),
        Player.DISCONTINUITY_REASON_SEEK);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "positionDiscontinuity [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " reason=SEEK, PositionInfo:old [mediaItem=0, period=0, pos=123],"
                + " PositionInfo:new [mediaItem=1, period=1, pos=789]]");
  }

  @Test
  public void onPlaybackParametersChanged() {
    eventLogger.onPlaybackParametersChanged(
        EVENT_TIME, new PlaybackParameters(/* speed= */ 1.5f, /* pitch= */ 1.3f));
    assertThat(onlyLogMessage())
        .isEqualTo(
            "playbackParameters [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " PlaybackParameters(speed=1.50, pitch=1.30)]");
  }

  @Test
  public void onTimelineChanged() {
    eventLogger.onTimelineChanged(EVENT_TIME, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE);
    assertThat(logMessages())
        .containsExactly(
            "timeline [eventTime=0.02, mediaPos=0.46, window=0, period=0, periodCount=1,"
                + " windowCount=1, reason=SOURCE_UPDATE",
            "  period [133.00]",
            "  window [10.00, seekable=true, dynamic=false]",
            "]")
        .inOrder();
  }

  @Test
  public void onMediaItemTransition() {
    eventLogger.onMediaItemTransition(
        EVENT_TIME, MediaItem.fromUri("http://test.com"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
    assertThat(onlyLogMessage())
        .isEqualTo("mediaItem [eventTime=0.02, mediaPos=0.46, window=0, period=0, reason=AUTO]");
  }

  @Test
  public void onPlayerError() {
    eventLogger.onPlayerError(
        EVENT_TIME,
        new PlaybackException(
            /* message= */ "test", /* cause= */ null, PlaybackException.ERROR_CODE_REMOTE_ERROR));
    String logMessage = onlyLogMessage();
    assertThat(logMessage)
        .startsWith(
            "playerFailed [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " errorCode=ERROR_CODE_REMOTE_ERROR\n"
                + "  androidx.media3.common.PlaybackException: test\n"
                + "      at androidx.media3.exoplayer.util.EventLoggerTest.onPlayerError");
    assertThat(logMessage).endsWith(")\n]");
  }

  @Test
  public void onTracksChanged() {
    eventLogger.onTracksChanged(
        EVENT_TIME,
        new Tracks(
            ImmutableList.of(
                new Tracks.Group(
                    new TrackGroup(AUDIO_FORMAT),
                    /* adaptiveSupported= */ false,
                    new int[] {C.FORMAT_HANDLED},
                    /* trackSelected= */ new boolean[] {true}))));
    assertThat(logMessages())
        .containsExactly(
            "tracks [eventTime=0.02, mediaPos=0.46, window=0, period=0",
            "  group [ id=",
            "    [X] Track:0, id=null, mimeType=audio/mp4a-latm, bitrate=100000, codecs=mp4a.40.2,"
                + " channels=2, sample_rate=44100, supported=YES",
            "  ]",
            "  Metadata [",
            "    FOO: description=fake text info frame: values=[val1, val2]",
            "  ]",
            "]")
        .inOrder();
  }

  @Test
  public void onMetadata() {
    eventLogger.onMetadata(EVENT_TIME, METADATA);
    assertThat(logMessages())
        .containsExactly(
            "metadata [eventTime=0.02, mediaPos=0.46, window=0, period=0",
            "  FOO: description=fake text info frame: values=[val1, val2]",
            "]")
        .inOrder();
  }

  @Test
  public void onAudioEnabled() {
    eventLogger.onAudioEnabled(EVENT_TIME, /* decoderCounters= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo("audioEnabled [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onAudioDecoderInitialized() {
    eventLogger.onAudioDecoderInitialized(EVENT_TIME, /* decoderName= */ "testDecoder", 0, 0);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioDecoderInitialized [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " testDecoder]");
  }

  @Test
  public void onAudioInputFormatChanged() {
    eventLogger.onAudioInputFormatChanged(
        EVENT_TIME, AUDIO_FORMAT, /* decoderReuseEvaluation= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioInputFormat [eventTime=0.02, mediaPos=0.46, window=0, period=0, id=null,"
                + " mimeType=audio/mp4a-latm, bitrate=100000, codecs=mp4a.40.2, channels=2,"
                + " sample_rate=44100]");
  }

  @Test
  public void onAudioUnderrun() {
    eventLogger.onAudioUnderrun(
        EVENT_TIME,
        /* bufferSize= */ 123,
        /* bufferSizeMs= */ 456,
        /* elapsedSinceLastFeedMs= */ 789);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioTrackUnderrun [eventTime=0.02, mediaPos=0.46, window=0, period=0, 123, 456,"
                + " 789]");
  }

  @Test
  public void onAudioDecoderReleased() {
    eventLogger.onAudioDecoderReleased(EVENT_TIME, /* decoderName= */ "testDecoder");
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioDecoderReleased [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " testDecoder]");
  }

  @Test
  public void onAudioDisabled() {
    eventLogger.onAudioDisabled(EVENT_TIME, /* decoderCounters= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo("audioDisabled [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onAudioSessionIdChanged() {
    eventLogger.onAudioSessionIdChanged(EVENT_TIME, /* audioSessionId= */ 123);
    assertThat(onlyLogMessage())
        .isEqualTo("audioSessionId [eventTime=0.02, mediaPos=0.46, window=0, period=0, 123]");
  }

  @Test
  public void onAudioAttributesChanged() {
    eventLogger.onAudioAttributesChanged(EVENT_TIME, AudioAttributes.DEFAULT);
    assertThat(onlyLogMessage())
        .isEqualTo("audioAttributes [eventTime=0.02, mediaPos=0.46, window=0, period=0, 0,0,1,1]");
  }

  @Test
  public void onSkipSilenceEnabledChanged() {
    eventLogger.onSkipSilenceEnabledChanged(EVENT_TIME, /* skipSilenceEnabled= */ true);
    assertThat(onlyLogMessage())
        .isEqualTo("skipSilenceEnabled [eventTime=0.02, mediaPos=0.46, window=0, period=0, true]");
  }

  @Test
  public void onVolumeChanged() {
    eventLogger.onVolumeChanged(EVENT_TIME, /* volume= */ 0.5f);
    assertThat(onlyLogMessage())
        .isEqualTo("volume [eventTime=0.02, mediaPos=0.46, window=0, period=0, 0.5]");
  }

  @Test
  public void onAudioTrackInitialized() {
    eventLogger.onAudioTrackInitialized(
        EVENT_TIME,
        new AudioTrackConfig(
            C.ENCODING_PCM_24BIT,
            /* sampleRate= */ 44100,
            AudioFormat.CHANNEL_OUT_5POINT1,
            /* tunneling= */ false,
            /* offload= */ false,
            /* bufferSize= */ 123000));
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioTrackInit [eventTime=0.02, mediaPos=0.46, window=0, period=0, enc=pcm-24,"
                + " channelConf=5.1, sampleRate=44100, bufferSize=123000]");
  }

  @Test
  public void onAudioTrackReleased() {
    eventLogger.onAudioTrackReleased(
        EVENT_TIME,
        new AudioTrackConfig(
            C.ENCODING_PCM_24BIT,
            /* sampleRate= */ 44100,
            AudioFormat.CHANNEL_OUT_5POINT1,
            /* tunneling= */ false,
            /* offload= */ false,
            /* bufferSize= */ 123000));
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioTrackReleased [eventTime=0.02, mediaPos=0.46, window=0, period=0, enc=pcm-24,"
                + " channelConf=5.1, sampleRate=44100, bufferSize=123000]");
  }

  @Test
  public void onAudioPositionAdvancing() {
    // This test is potentially flaky, because it relies on System.currentTimeMillis() here and
    // inside onAudioPositionAdvancing returning similar values. It didn't fail when run 10k times,
    // to it seems unlikely to be a real problem.
    eventLogger.onAudioPositionAdvancing(
        EVENT_TIME, /* playoutStartSystemTimeMs= */ System.currentTimeMillis() - 12300);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "audioPositionAdvancing [eventTime=0.02, mediaPos=0.46, window=0, period=0, since"
                + " -12.30]");
  }

  @Test
  public void onVideoEnabled() {
    eventLogger.onVideoEnabled(EVENT_TIME, /* decoderCounters= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo("videoEnabled [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onVideoDecoderInitialized() {
    eventLogger.onVideoDecoderInitialized(
        EVENT_TIME,
        /* decoderName= */ "testDecoder",
        /* initializedTimestampMs= */ 123,
        /* initializationDurationMs= */ 456);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "videoDecoderInitialized [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " testDecoder]");
  }

  @Test
  public void onVideoInputFormatChanged() {
    eventLogger.onVideoInputFormatChanged(
        EVENT_TIME, ExoPlayerTestRunner.VIDEO_FORMAT, /* decoderReuseEvaluation= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "videoInputFormat [eventTime=0.02, mediaPos=0.46, window=0, period=0, id=null,"
                + " mimeType=video/avc, bitrate=800000, res=1280x720]");
  }

  @Test
  public void onDroppedVideoFrames() {
    eventLogger.onDroppedVideoFrames(EVENT_TIME, /* droppedFrames= */ 123, /* elapsedMs= */ 456);
    assertThat(onlyLogMessage())
        .isEqualTo("droppedFrames [eventTime=0.02, mediaPos=0.46, window=0, period=0, 123]");
  }

  @Test
  public void onVideoDecoderReleased() {
    eventLogger.onVideoDecoderReleased(EVENT_TIME, /* decoderName= */ "testDecoder");
    assertThat(onlyLogMessage())
        .isEqualTo(
            "videoDecoderReleased [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " testDecoder]");
  }

  @Test
  public void onVideoDisabled() {
    eventLogger.onVideoDisabled(EVENT_TIME, /* decoderCounters= */ null);
    assertThat(onlyLogMessage())
        .isEqualTo("videoDisabled [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onRenderedFirstFrame() {
    // Override toString so we can assert the output deterministically.
    Object output =
        new Object() {
          @Override
          public String toString() {
            return "fake-output";
          }
        };
    eventLogger.onRenderedFirstFrame(EVENT_TIME, output, /* renderTimeMs= */ 123);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "renderedFirstFrame [eventTime=0.02, mediaPos=0.46, window=0, period=0, fake-output]");
  }

  @Test
  public void onVideoSizeChanged() {
    eventLogger.onVideoSizeChanged(EVENT_TIME, new VideoSize(/* width= */ 100, /* height= */ 200));
    assertThat(onlyLogMessage())
        .isEqualTo("videoSize [eventTime=0.02, mediaPos=0.46, window=0, period=0, w=100, h=200]");
  }

  @Test
  public void onLoadError() {
    eventLogger.onLoadError(
        EVENT_TIME,
        new LoadEventInfo(
            /* loadTaskId= */ 1,
            new DataSpec(Uri.parse("http://foo.test")),
            /* elapsedRealtimeMs= */ 123),
        MEDIA_LOAD_DATA,
        new IOException("test msg"),
        /* wasCanceled= */ true);
    String logMessage = onlyLogMessage();
    assertThat(logMessage)
        .startsWith(
            "internalError [eventTime=0.02, mediaPos=0.46, window=0, period=0, loadError\n"
                + "  java.io.IOException: test msg\n"
                + "      at"
                + " androidx.media3.exoplayer.util.EventLoggerTest.onLoadError(EventLoggerTest.java:");
    assertThat(logMessage).endsWith(")\n]");
  }

  @Test
  public void onSurfaceSizeChanged() {
    eventLogger.onSurfaceSizeChanged(EVENT_TIME, /* width= */ 100, /* height= */ 200);
    assertThat(onlyLogMessage())
        .isEqualTo("surfaceSize [eventTime=0.02, mediaPos=0.46, window=0, period=0, w=100, h=200]");
  }

  @Test
  public void onUpstreamDiscarded() {
    eventLogger.onUpstreamDiscarded(EVENT_TIME, MEDIA_LOAD_DATA);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "upstreamDiscarded [eventTime=0.02, mediaPos=0.46, window=0, period=0, id=null,"
                + " mimeType=audio/mp4a-latm, bitrate=100000, codecs=mp4a.40.2, channels=2,"
                + " sample_rate=44100]");
  }

  @Test
  public void onDownstreamFormatChanged() {
    eventLogger.onDownstreamFormatChanged(EVENT_TIME, MEDIA_LOAD_DATA);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "downstreamFormat [eventTime=0.02, mediaPos=0.46, window=0, period=0, id=null,"
                + " mimeType=audio/mp4a-latm, bitrate=100000, codecs=mp4a.40.2, channels=2,"
                + " sample_rate=44100]");
  }

  @Test
  public void onDrmSessionAcquired() {
    eventLogger.onDrmSessionAcquired(EVENT_TIME, DrmSession.STATE_OPENED);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "drmSessionAcquired [eventTime=0.02, mediaPos=0.46, window=0, period=0, state=3]");
  }

  @Test
  public void onDrmSessionManagerError() {
    eventLogger.onDrmSessionManagerError(EVENT_TIME, new Exception("test msg"));
    String logMessage = onlyLogMessage();
    assertThat(logMessage)
        .startsWith(
            "internalError [eventTime=0.02, mediaPos=0.46, window=0, period=0,"
                + " drmSessionManagerError\n"
                + "  java.lang.Exception: test msg\n"
                + "      at"
                + " androidx.media3.exoplayer.util.EventLoggerTest.onDrmSessionManagerError(EventLoggerTest.java:");
    assertThat(logMessage).endsWith(")\n]");
  }

  @Test
  public void onDrmKeysRestored() {
    eventLogger.onDrmKeysRestored(EVENT_TIME);
    assertThat(onlyLogMessage())
        .isEqualTo("drmKeysRestored [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onDrmKeysRemoved() {
    eventLogger.onDrmKeysRemoved(EVENT_TIME);
    assertThat(onlyLogMessage())
        .isEqualTo("drmKeysRemoved [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onDrmKeysLoaded() {
    eventLogger.onDrmKeysLoaded(EVENT_TIME, new KeyRequestInfo.Builder().build());
    assertThat(onlyLogMessage())
        .isEqualTo("drmKeysLoaded [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onDrmSessionReleased() {
    eventLogger.onDrmSessionReleased(EVENT_TIME);
    assertThat(onlyLogMessage())
        .isEqualTo("drmSessionReleased [eventTime=0.02, mediaPos=0.46, window=0, period=0]");
  }

  @Test
  public void onRendererReadyChanged() {
    eventLogger.onRendererReadyChanged(
        EVENT_TIME, /* rendererIndex= */ 1, C.TRACK_TYPE_TEXT, /* isRendererReady= */ true);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "rendererReady [eventTime=0.02, mediaPos=0.46, window=0, period=0, rendererIndex=1,"
                + " text, true]");
  }

  @Test
  public void onDroppedSeeksWhileScrubbing() {
    eventLogger.onDroppedSeeksWhileScrubbing(EVENT_TIME, 12);
    assertThat(onlyLogMessage())
        .isEqualTo(
            "droppedSeeksWhileScrubbing [eventTime=0.02, mediaPos=0.46, window=0, period=0, 12]");
  }

  private static EventTime createEventTime() {
    FakeTimeline timeline = new FakeTimeline();
    MediaPeriodId mediaPeriodId = new MediaPeriodId(timeline.getUidOfPeriod(0));
    return new EventTime(
        /* realtimeMs= */ 123,
        timeline,
        /* windowIndex= */ 0,
        mediaPeriodId,
        /* eventPlaybackPositionMs= */ 456,
        timeline,
        /* currentWindowIndex= */ 0,
        mediaPeriodId,
        /* currentPlaybackPositionMs= */ 789,
        /* totalBufferedDurationMs= */ 1123);
  }

  private static String onlyLogMessage() {
    return Iterables.getOnlyElement(ShadowLog.getLogsForTag(CUSTOM_TAG)).msg;
  }

  private static ImmutableList<String> logMessages() {
    return ShadowLog.getLogsForTag(CUSTOM_TAG).stream()
        .map(logItem -> logItem.msg)
        .collect(toImmutableList());
  }
}
