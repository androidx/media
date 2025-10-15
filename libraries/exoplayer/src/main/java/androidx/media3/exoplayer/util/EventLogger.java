/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.common.util.Util.getFormatSupportString;
import static androidx.media3.common.util.Util.getTrackTypeString;
import static java.lang.Math.min;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PlaybackSuppressionReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.KeyRequestInfo;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Logs events from {@link Player} and other core components using {@link Log}. */
@SuppressWarnings("UngroupedOverloads")
public class EventLogger implements AnalyticsListener {

  private static final String DEFAULT_TAG = "EventLogger";
  private static final int MAX_TIMELINE_ITEM_LINES = 3;
  private static final Joiner COMMA_JOINER = Joiner.on(", ");
  private static final NumberFormat TIME_FORMAT;

  static {
    TIME_FORMAT = NumberFormat.getInstance(Locale.US);
    TIME_FORMAT.setMinimumFractionDigits(2);
    TIME_FORMAT.setMaximumFractionDigits(2);
    TIME_FORMAT.setGroupingUsed(false);
  }

  private final String tag;
  private final Timeline.Window window;
  private final Timeline.Period period;
  private final long startTimeMs;

  /** Creates an instance. */
  public EventLogger() {
    this(DEFAULT_TAG);
  }

  /**
   * Creates an instance.
   *
   * @param tag The tag used for logging.
   */
  public EventLogger(String tag) {
    this.tag = tag;
    window = new Timeline.Window();
    period = new Timeline.Period();
    startTimeMs = SystemClock.elapsedRealtime();
  }

  /**
   * Creates an instance.
   *
   * @param trackSelector This parameter is ignored.
   * @deprecated Use {@link #EventLogger()}
   */
  @UnstableApi
  @Deprecated
  @SuppressWarnings("unused") // Maintain backwards compatibility for callers.
  public EventLogger(@Nullable MappingTrackSelector trackSelector) {
    this(DEFAULT_TAG);
  }

  /**
   * Creates an instance.
   *
   * @param trackSelector This parameter is ignored.
   * @param tag The tag used for logging.
   * @deprecated Use {@link #EventLogger(String)}
   */
  @UnstableApi
  @Deprecated
  @SuppressWarnings("unused") // Maintain backwards compatibility for callers.
  public EventLogger(@Nullable MappingTrackSelector trackSelector, String tag) {
    this(tag);
  }

  // AnalyticsListener

  @UnstableApi
  @Override
  public void onIsLoadingChanged(EventTime eventTime, boolean isLoading) {
    logd(eventTime, "loading", Boolean.toString(isLoading));
  }

  @UnstableApi
  @Override
  public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {
    logd(eventTime, "state", getStateString(state));
  }

  @UnstableApi
  @Override
  public void onPlayWhenReadyChanged(
      EventTime eventTime, boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
    logd(
        eventTime,
        "playWhenReady",
        playWhenReady + ", " + getPlayWhenReadyChangeReasonString(reason));
  }

  @UnstableApi
  @Override
  public void onPlaybackSuppressionReasonChanged(
      EventTime eventTime, @PlaybackSuppressionReason int playbackSuppressionReason) {
    logd(
        eventTime,
        "playbackSuppressionReason",
        getPlaybackSuppressionReasonString(playbackSuppressionReason));
  }

  @UnstableApi
  @Override
  public void onIsPlayingChanged(EventTime eventTime, boolean isPlaying) {
    logd(eventTime, "isPlaying", Boolean.toString(isPlaying));
  }

  @UnstableApi
  @Override
  public void onRepeatModeChanged(EventTime eventTime, @Player.RepeatMode int repeatMode) {
    logd(eventTime, "repeatMode", getRepeatModeString(repeatMode));
  }

  @UnstableApi
  @Override
  public void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {
    logd(eventTime, "shuffleModeEnabled", Boolean.toString(shuffleModeEnabled));
  }

  @UnstableApi
  @Override
  public void onPositionDiscontinuity(
      EventTime eventTime,
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    String details =
        "reason="
            + getDiscontinuityReasonString(reason)
            + ", PositionInfo:old ["
            + oldPosition
            + "], PositionInfo:new ["
            + newPosition
            + "]";
    logd(eventTime, "positionDiscontinuity", details);
  }

  @UnstableApi
  @Override
  public void onPlaybackParametersChanged(
      EventTime eventTime, PlaybackParameters playbackParameters) {
    logd(eventTime, "playbackParameters", playbackParameters.toString());
  }

  @UnstableApi
  @Override
  public void onTimelineChanged(EventTime eventTime, @Player.TimelineChangeReason int reason) {
    int periodCount = eventTime.timeline.getPeriodCount();
    int windowCount = eventTime.timeline.getWindowCount();
    logd(
        "timeline ["
            + getEventTimeString(eventTime)
            + ", periodCount="
            + periodCount
            + ", windowCount="
            + windowCount
            + ", reason="
            + getTimelineChangeReasonString(reason));
    for (int i = 0; i < min(periodCount, MAX_TIMELINE_ITEM_LINES); i++) {
      eventTime.timeline.getPeriod(i, period);
      logd("  " + "period [" + getTimeString(period.getDurationMs()) + "]");
    }
    if (periodCount > MAX_TIMELINE_ITEM_LINES) {
      logd("  ...");
    }
    for (int i = 0; i < min(windowCount, MAX_TIMELINE_ITEM_LINES); i++) {
      eventTime.timeline.getWindow(i, window);
      logd(
          "  "
              + "window ["
              + getTimeString(window.getDurationMs())
              + ", seekable="
              + window.isSeekable
              + ", dynamic="
              + window.isDynamic
              + "]");
    }
    if (windowCount > MAX_TIMELINE_ITEM_LINES) {
      logd("  ...");
    }
    logd("]");
  }

  @UnstableApi
  @Override
  public void onMediaItemTransition(
      EventTime eventTime, @Nullable MediaItem mediaItem, int reason) {
    logd(
        "mediaItem ["
            + getEventTimeString(eventTime)
            + ", reason="
            + getMediaItemTransitionReasonString(reason)
            + "]");
  }

  @UnstableApi
  @Override
  public void onPlayerError(EventTime eventTime, PlaybackException error) {
    loge(eventTime, "playerFailed", error);
  }

  @UnstableApi
  @Override
  public void onTracksChanged(EventTime eventTime, Tracks tracks) {
    logd("tracks [" + getEventTimeString(eventTime));
    // Log tracks associated to renderers.
    ImmutableList<Tracks.Group> trackGroups = tracks.getGroups();
    for (int groupIndex = 0; groupIndex < trackGroups.size(); groupIndex++) {
      Tracks.Group trackGroup = trackGroups.get(groupIndex);
      logd("  group [ id=" + trackGroup.getMediaTrackGroup().id);
      for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
        String status = getTrackStatusString(trackGroup.isTrackSelected(trackIndex));
        String formatSupport = getFormatSupportString(trackGroup.getTrackSupport(trackIndex));
        logd(
            "    "
                + status
                + " Track:"
                + trackIndex
                + ", "
                + Format.toLogString(trackGroup.getTrackFormat(trackIndex))
                + ", supported="
                + formatSupport);
      }
      logd("  ]");
    }
    // TODO: Replace this with an override of onMediaMetadataChanged.
    // Log metadata for at most one of the selected tracks.
    boolean loggedMetadata = false;
    for (int groupIndex = 0; !loggedMetadata && groupIndex < trackGroups.size(); groupIndex++) {
      Tracks.Group trackGroup = trackGroups.get(groupIndex);
      for (int trackIndex = 0; !loggedMetadata && trackIndex < trackGroup.length; trackIndex++) {
        if (trackGroup.isTrackSelected(trackIndex)) {
          @Nullable Metadata metadata = trackGroup.getTrackFormat(trackIndex).metadata;
          if (metadata != null && metadata.length() > 0) {
            logd("  Metadata [");
            printMetadata(metadata, "    ");
            logd("  ]");
            loggedMetadata = true;
          }
        }
      }
    }
    logd("]");
  }

  @UnstableApi
  @Override
  public void onMetadata(EventTime eventTime, Metadata metadata) {
    logd("metadata [" + getEventTimeString(eventTime));
    printMetadata(metadata, "  ");
    logd("]");
  }

  @UnstableApi
  @Override
  public void onAudioEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "audioEnabled");
  }

  @UnstableApi
  @Override
  public void onAudioDecoderInitialized(
      EventTime eventTime,
      String decoderName,
      long initializedTimestampMs,
      long initializationDurationMs) {
    logd(eventTime, "audioDecoderInitialized", decoderName);
  }

  @UnstableApi
  @Override
  public void onAudioInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    logd(eventTime, "audioInputFormat", Format.toLogString(format));
  }

  @UnstableApi
  @Override
  public void onAudioUnderrun(
      EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
    loge(
        eventTime,
        "audioTrackUnderrun",
        bufferSize + ", " + bufferSizeMs + ", " + elapsedSinceLastFeedMs,
        /* throwable= */ null);
  }

  @UnstableApi
  @Override
  public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
    logd(eventTime, "audioDecoderReleased", decoderName);
  }

  @UnstableApi
  @Override
  public void onAudioDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "audioDisabled");
  }

  @UnstableApi
  @Override
  public void onAudioSessionIdChanged(EventTime eventTime, int audioSessionId) {
    logd(eventTime, "audioSessionId", Integer.toString(audioSessionId));
  }

  @UnstableApi
  @Override
  public void onAudioAttributesChanged(EventTime eventTime, AudioAttributes audioAttributes) {
    logd(
        eventTime,
        "audioAttributes",
        audioAttributes.contentType
            + ","
            + audioAttributes.flags
            + ","
            + audioAttributes.usage
            + ","
            + audioAttributes.allowedCapturePolicy);
  }

  @UnstableApi
  @Override
  public void onSkipSilenceEnabledChanged(EventTime eventTime, boolean skipSilenceEnabled) {
    logd(eventTime, "skipSilenceEnabled", Boolean.toString(skipSilenceEnabled));
  }

  @UnstableApi
  @Override
  public void onVolumeChanged(EventTime eventTime, float volume) {
    logd(eventTime, "volume", Float.toString(volume));
  }

  @UnstableApi
  @Override
  public void onAudioTrackInitialized(
      EventTime eventTime, AudioSink.AudioTrackConfig audioTrackConfig) {
    logd(eventTime, "audioTrackInit", getAudioTrackConfigString(audioTrackConfig));
  }

  @UnstableApi
  @Override
  public void onAudioTrackReleased(
      EventTime eventTime, AudioSink.AudioTrackConfig audioTrackConfig) {
    logd(eventTime, "audioTrackReleased", getAudioTrackConfigString(audioTrackConfig));
  }

  @UnstableApi
  @Override
  public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
    long playoutStartTimeInElapsedRealtimeMs =
        playoutStartSystemTimeMs - System.currentTimeMillis() + SystemClock.elapsedRealtime();
    logd(
        eventTime,
        "audioPositionAdvancing",
        "since " + getTimeString(playoutStartTimeInElapsedRealtimeMs - startTimeMs));
  }

  @UnstableApi
  @Override
  public void onVideoEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "videoEnabled");
  }

  @UnstableApi
  @Override
  public void onVideoDecoderInitialized(
      EventTime eventTime,
      String decoderName,
      long initializedTimestampMs,
      long initializationDurationMs) {
    logd(eventTime, "videoDecoderInitialized", decoderName);
  }

  @UnstableApi
  @Override
  public void onVideoInputFormatChanged(
      EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    logd(eventTime, "videoInputFormat", Format.toLogString(format));
  }

  @UnstableApi
  @Override
  public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
    logd(eventTime, "droppedFrames", Integer.toString(droppedFrames));
  }

  @UnstableApi
  @Override
  public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
    logd(eventTime, "videoDecoderReleased", decoderName);
  }

  @UnstableApi
  @Override
  public void onVideoDisabled(EventTime eventTime, DecoderCounters decoderCounters) {
    logd(eventTime, "videoDisabled");
  }

  @UnstableApi
  @Override
  public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
    logd(eventTime, "renderedFirstFrame", String.valueOf(output));
  }

  @UnstableApi
  @Override
  public void onVideoSizeChanged(EventTime eventTime, VideoSize videoSize) {
    StringBuilder description =
        new StringBuilder("w=" + videoSize.width + ", h=" + videoSize.height);
    if (videoSize.pixelWidthHeightRatio != 1.0f) {
      description.append(", par=").append(videoSize.pixelWidthHeightRatio);
    }
    logd(eventTime, "videoSize", description.toString());
  }

  @UnstableApi
  @Override
  public void onLoadError(
      EventTime eventTime,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      IOException error,
      boolean wasCanceled) {
    printInternalError(eventTime, "loadError", error);
  }

  @UnstableApi
  @Override
  public void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {
    logd(eventTime, "surfaceSize", "w=" + width + ", h=" + height);
  }

  @UnstableApi
  @Override
  public void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {
    logd(eventTime, "upstreamDiscarded", Format.toLogString(mediaLoadData.trackFormat));
  }

  @UnstableApi
  @Override
  public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {
    logd(eventTime, "downstreamFormat", Format.toLogString(mediaLoadData.trackFormat));
  }

  @UnstableApi
  @Override
  public void onDrmSessionAcquired(EventTime eventTime, @DrmSession.State int state) {
    logd(eventTime, "drmSessionAcquired", "state=" + state);
  }

  @UnstableApi
  @Override
  public void onDrmSessionManagerError(EventTime eventTime, Exception error) {
    printInternalError(eventTime, "drmSessionManagerError", error);
  }

  @UnstableApi
  @Override
  public void onDrmKeysRestored(EventTime eventTime) {
    logd(eventTime, "drmKeysRestored");
  }

  @UnstableApi
  @Override
  public void onDrmKeysRemoved(EventTime eventTime) {
    logd(eventTime, "drmKeysRemoved");
  }

  @UnstableApi
  @Override
  public void onDrmKeysLoaded(EventTime eventTime, KeyRequestInfo keyRequestInfo) {
    logd(eventTime, "drmKeysLoaded");
  }

  @UnstableApi
  @Override
  public void onDrmSessionReleased(EventTime eventTime) {
    logd(eventTime, "drmSessionReleased");
  }

  @UnstableApi
  @Override
  public void onRendererReadyChanged(
      EventTime eventTime,
      int rendererIndex,
      @C.TrackType int rendererTrackType,
      boolean isRendererReady) {
    logd(
        eventTime,
        "rendererReady",
        "rendererIndex="
            + rendererIndex
            + ", "
            + getTrackTypeString(rendererTrackType)
            + ", "
            + isRendererReady);
  }

  @UnstableApi
  @Override
  public void onDroppedSeeksWhileScrubbing(EventTime eventTime, int droppedSeeks) {
    logd(eventTime, "droppedSeeksWhileScrubbing", Integer.toString(droppedSeeks));
  }

  /**
   * Logs a debug message.
   *
   * @param msg The message to log.
   */
  @UnstableApi
  protected void logd(String msg) {
    Log.d(tag, msg);
  }

  /**
   * Logs an error message.
   *
   * @param msg The message to log.
   */
  @UnstableApi
  protected void loge(String msg) {
    Log.e(tag, msg);
  }

  // Internal methods

  private void logd(EventTime eventTime, String eventName) {
    logd(getEventString(eventTime, eventName, /* eventDescription= */ null, /* throwable= */ null));
  }

  private void logd(EventTime eventTime, String eventName, String eventDescription) {
    logd(getEventString(eventTime, eventName, eventDescription, /* throwable= */ null));
  }

  private void loge(EventTime eventTime, String eventName, @Nullable Throwable throwable) {
    loge(getEventString(eventTime, eventName, /* eventDescription= */ null, throwable));
  }

  private void loge(
      EventTime eventTime,
      String eventName,
      String eventDescription,
      @Nullable Throwable throwable) {
    loge(getEventString(eventTime, eventName, eventDescription, throwable));
  }

  private void printInternalError(EventTime eventTime, String type, Exception e) {
    loge(eventTime, "internalError", type, e);
  }

  private void printMetadata(Metadata metadata, String prefix) {
    for (int i = 0; i < metadata.length(); i++) {
      logd(prefix + metadata.get(i));
    }
  }

  private String getEventString(
      EventTime eventTime,
      String eventName,
      @Nullable String eventDescription,
      @Nullable Throwable throwable) {
    String eventString = eventName + " [" + getEventTimeString(eventTime);
    if (throwable instanceof PlaybackException) {
      eventString += ", errorCode=" + ((PlaybackException) throwable).getErrorCodeName();
    }
    if (eventDescription != null) {
      eventString += ", " + eventDescription;
    }
    @Nullable String throwableString = Log.getThrowableString(throwable);
    if (!TextUtils.isEmpty(throwableString)) {
      eventString += "\n  " + throwableString.replace("\n", "\n  ") + '\n';
    }
    eventString += "]";
    return eventString;
  }

  private String getEventTimeString(EventTime eventTime) {
    String windowPeriodString = "window=" + eventTime.windowIndex;
    if (eventTime.mediaPeriodId != null) {
      windowPeriodString +=
          ", period=" + eventTime.timeline.getIndexOfPeriod(eventTime.mediaPeriodId.periodUid);
      if (eventTime.mediaPeriodId.isAd()) {
        windowPeriodString += ", adGroup=" + eventTime.mediaPeriodId.adGroupIndex;
        windowPeriodString += ", ad=" + eventTime.mediaPeriodId.adIndexInAdGroup;
      }
    }
    return "eventTime="
        + getTimeString(eventTime.realtimeMs - startTimeMs)
        + ", mediaPos="
        + getTimeString(eventTime.eventPlaybackPositionMs)
        + ", "
        + windowPeriodString;
  }

  private static String getTimeString(long timeMs) {
    return timeMs == C.TIME_UNSET ? "?" : TIME_FORMAT.format((timeMs) / 1000f);
  }

  private static String getStateString(int state) {
    switch (state) {
      case Player.STATE_BUFFERING:
        return "BUFFERING";
      case Player.STATE_ENDED:
        return "ENDED";
      case Player.STATE_IDLE:
        return "IDLE";
      case Player.STATE_READY:
        return "READY";
      default:
        return "?";
    }
  }

  private static String getTrackStatusString(boolean selected) {
    return selected ? "[X]" : "[ ]";
  }

  private static String getRepeatModeString(@Player.RepeatMode int repeatMode) {
    switch (repeatMode) {
      case Player.REPEAT_MODE_OFF:
        return "OFF";
      case Player.REPEAT_MODE_ONE:
        return "ONE";
      case Player.REPEAT_MODE_ALL:
        return "ALL";
      default:
        return "?";
    }
  }

  private static String getDiscontinuityReasonString(@Player.DiscontinuityReason int reason) {
    switch (reason) {
      case Player.DISCONTINUITY_REASON_AUTO_TRANSITION:
        return "AUTO_TRANSITION";
      case Player.DISCONTINUITY_REASON_SEEK:
        return "SEEK";
      case Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT:
        return "SEEK_ADJUSTMENT";
      case Player.DISCONTINUITY_REASON_REMOVE:
        return "REMOVE";
      case Player.DISCONTINUITY_REASON_SKIP:
        return "SKIP";
      case Player.DISCONTINUITY_REASON_INTERNAL:
        return "INTERNAL";
      case Player.DISCONTINUITY_REASON_SILENCE_SKIP:
        return "SILENCE_SKIP";
      default:
        return "?";
    }
  }

  private static String getTimelineChangeReasonString(@Player.TimelineChangeReason int reason) {
    switch (reason) {
      case Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE:
        return "SOURCE_UPDATE";
      case Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED:
        return "PLAYLIST_CHANGED";
      default:
        return "?";
    }
  }

  private static String getMediaItemTransitionReasonString(
      @Player.MediaItemTransitionReason int reason) {
    switch (reason) {
      case Player.MEDIA_ITEM_TRANSITION_REASON_AUTO:
        return "AUTO";
      case Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED:
        return "PLAYLIST_CHANGED";
      case Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT:
        return "REPEAT";
      case Player.MEDIA_ITEM_TRANSITION_REASON_SEEK:
        return "SEEK";
      default:
        return "?";
    }
  }

  private static String getPlaybackSuppressionReasonString(
      @PlaybackSuppressionReason int playbackSuppressionReason) {
    switch (playbackSuppressionReason) {
      case Player.PLAYBACK_SUPPRESSION_REASON_NONE:
        return "NONE";
      case Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS:
        return "TRANSIENT_AUDIO_FOCUS_LOSS";
      case Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT:
        return "UNSUITABLE_AUDIO_OUTPUT";
      case Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING:
        return "SCRUBBING";
      default:
        return "?";
    }
  }

  private static String getPlayWhenReadyChangeReasonString(
      @Player.PlayWhenReadyChangeReason int reason) {
    switch (reason) {
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY:
        return "AUDIO_BECOMING_NOISY";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS:
        return "AUDIO_FOCUS_LOSS";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE:
        return "REMOTE";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST:
        return "USER_REQUEST";
      case Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM:
        return "END_OF_MEDIA_ITEM";
      default:
        return "?";
    }
  }

  private static String getAudioTrackConfigString(AudioSink.AudioTrackConfig audioTrackConfig) {
    List<String> result = new ArrayList<>();
    if (audioTrackConfig.encoding != Format.NO_VALUE) {
      result.add("enc=" + encodingAsString(audioTrackConfig.encoding));
    }
    result.add("channelConf=" + channelConfigAsString(audioTrackConfig.channelConfig));
    result.add("sampleRate=" + audioTrackConfig.sampleRate);
    result.add("bufferSize=" + audioTrackConfig.bufferSize);
    if (audioTrackConfig.tunneling) {
      result.add("tunneling");
    }
    if (audioTrackConfig.offload) {
      result.add("offload");
    }
    return COMMA_JOINER.join(result);
  }

  private static String encodingAsString(@C.Encoding int encoding) {
    switch (encoding) {
      case C.ENCODING_AAC_ELD:
        return "aac-eld";
      case C.ENCODING_AAC_ER_BSAC:
        return "aac-er-bsac";
      case C.ENCODING_AAC_HE_V1:
        return "aac-he-v1";
      case C.ENCODING_AAC_HE_V2:
        return "aac-he-v2";
      case C.ENCODING_AAC_LC:
        return "aac-lc";
      case C.ENCODING_AAC_XHE:
        return "aac-xhe";
      case C.ENCODING_AC3:
        return "ac3";
      case C.ENCODING_AC4:
        return "ac4";
      case C.ENCODING_DOLBY_TRUEHD:
        return "truehd";
      case C.ENCODING_DTS:
        return "dts";
      case C.ENCODING_DTS_HD:
        return "dts-hd";
      case C.ENCODING_DTS_UHD_P2:
        return "dts-uhd-p2";
      case C.ENCODING_E_AC3:
        return "eac3";
      case C.ENCODING_E_AC3_JOC:
        return "eac3-joc";
      case C.ENCODING_MP3:
        return "mp3";
      case C.ENCODING_OPUS:
        return "opus";
      case C.ENCODING_PCM_8BIT:
        return "pcm-8";
      case C.ENCODING_PCM_16BIT:
        return "pcm-16";
      case C.ENCODING_PCM_16BIT_BIG_ENDIAN:
        return "pcm-16be";
      case C.ENCODING_PCM_24BIT:
        return "pcm-24";
      case C.ENCODING_PCM_24BIT_BIG_ENDIAN:
        return "pcm-24be";
      case C.ENCODING_PCM_32BIT:
        return "pcm-32";
      case C.ENCODING_PCM_32BIT_BIG_ENDIAN:
        return "pcm-32be";
      case C.ENCODING_PCM_FLOAT:
        return "pcm-float";
      case C.ENCODING_INVALID:
      default:
        return String.valueOf(encoding);
    }
  }

  private static String channelConfigAsString(int channelConfig) {
    switch (channelConfig) {
      case AudioFormat.CHANNEL_OUT_MONO:
        return "mono";
      case AudioFormat.CHANNEL_OUT_STEREO:
        return "stereo";
      case AudioFormat.CHANNEL_OUT_QUAD:
        return "quad";
      case AudioFormat.CHANNEL_OUT_5POINT1:
        return "5.1";
      case AudioFormat.CHANNEL_OUT_5POINT1POINT2:
        return "5.1.2";
      case AudioFormat.CHANNEL_OUT_5POINT1POINT4:
        return "5.1.4";
      case AudioFormat.CHANNEL_OUT_7POINT1_SURROUND:
        return "7.1";
      case AudioFormat.CHANNEL_OUT_7POINT1POINT2:
        return "7.1.2";
      case AudioFormat.CHANNEL_OUT_7POINT1POINT4:
        return "7.1.4";
      case AudioFormat.CHANNEL_OUT_9POINT1POINT4:
        return "9.1.4";
      case AudioFormat.CHANNEL_OUT_9POINT1POINT6:
        return "9.1.6";
      default:
        return "0x" + Integer.toHexString(channelConfig);
    }
  }
}
