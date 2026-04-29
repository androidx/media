/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.media3.effect;

import static androidx.media3.common.util.Util.formatInvariant;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.JsonWriter;
import androidx.annotation.GuardedBy;
import androidx.annotation.StringDef;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;

/** A debugging tracing utility. Debug logging is disabled at compile time by default. */
@UnstableApi
public final class DebugTraceUtil {

  /**
   * Whether to store tracing events for debug logging. Should be set to {@code true} for testing
   * and debugging purposes only.
   */
  @SuppressWarnings("NonFinalStaticField") // Only for debugging/testing.
  public static boolean enableTracing = false;

  /** Events logged by {@link #logEvent}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @StringDef({
    EVENT_SET_COMPOSITION,
    EVENT_SEEK_TO,
    EVENT_SET_VIDEO_OUTPUT,
    EVENT_RELEASE,
    EVENT_START,
    EVENT_INPUT_FORMAT,
    EVENT_OUTPUT_FORMAT,
    EVENT_ACCEPTED_INPUT,
    EVENT_PRODUCED_OUTPUT,
    EVENT_INPUT_ENDED,
    EVENT_OUTPUT_ENDED,
    EVENT_REGISTER_NEW_INPUT_STREAM,
    EVENT_SURFACE_TEXTURE_INPUT,
    EVENT_SURFACE_TEXTURE_TRANSFORM_FIX,
    EVENT_QUEUE_FRAME,
    EVENT_QUEUE_BITMAP,
    EVENT_QUEUE_TEXTURE,
    EVENT_OUTPUT_TEXTURE_RENDERED,
    EVENT_RENDERED_TO_OUTPUT_SURFACE,
    EVENT_RECEIVE_END_OF_ALL_INPUT,
    EVENT_SIGNAL_EOS,
    EVENT_SIGNAL_ENDED,
    EVENT_CAN_WRITE_SAMPLE
  })
  @Target(TYPE_USE)
  public @interface Event {}

  // TODO: b/443718535 - Remove excessive debug trace logging.
  public static final String EVENT_SET_COMPOSITION = "SetComposition";
  public static final String EVENT_SEEK_TO = "SeekTo";
  public static final String EVENT_SET_VIDEO_OUTPUT = "SetVideoOutput";
  public static final String EVENT_RELEASE = "Release";
  public static final String EVENT_START = "Start";
  public static final String EVENT_INPUT_FORMAT = "InputFormat";
  public static final String EVENT_OUTPUT_FORMAT = "OutputFormat";
  public static final String EVENT_ACCEPTED_INPUT = "AcceptedInput";
  public static final String EVENT_PRODUCED_OUTPUT = "ProducedOutput";
  public static final String EVENT_INPUT_ENDED = "InputEnded";
  public static final String EVENT_OUTPUT_ENDED = "OutputEnded";
  public static final String EVENT_REGISTER_NEW_INPUT_STREAM = "RegisterNewInputStream";
  public static final String EVENT_SURFACE_TEXTURE_INPUT = "SurfaceTextureInput";
  public static final String EVENT_SURFACE_TEXTURE_TRANSFORM_FIX = "SurfaceTextureTransformFix";
  public static final String EVENT_QUEUE_FRAME = "QueueFrame";
  public static final String EVENT_QUEUE_BITMAP = "QueueBitmap";
  public static final String EVENT_QUEUE_TEXTURE = "QueueTexture";
  public static final String EVENT_OUTPUT_TEXTURE_RENDERED = "OutputTextureRendered";
  public static final String EVENT_RENDERED_TO_OUTPUT_SURFACE = "RenderedToOutputSurface";
  public static final String EVENT_RECEIVE_END_OF_ALL_INPUT = "ReceiveEndOfAllInput";
  public static final String EVENT_SIGNAL_EOS = "SignalEOS";
  public static final String EVENT_SIGNAL_ENDED = "SignalEnded";
  public static final String EVENT_CAN_WRITE_SAMPLE = "CanWriteSample";

  /** Components logged by {@link #logEvent}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @StringDef({
    COMPONENT_COMPOSITION_PLAYER,
    COMPONENT_TRANSFORMER_INTERNAL,
    COMPONENT_ASSET_LOADER,
    COMPONENT_AUDIO_DECODER,
    COMPONENT_AUDIO_GRAPH,
    COMPONENT_AUDIO_MIXER,
    COMPONENT_AUDIO_ENCODER,
    COMPONENT_VIDEO_DECODER,
    COMPONENT_VFP,
    COMPONENT_BITMAP_TEXTURE_MANAGER,
    COMPONENT_EXTERNAL_TEXTURE_MANAGER,
    COMPONENT_TEX_ID_TEXTURE_MANAGER,
    COMPONENT_COMPOSITOR,
    COMPONENT_VIDEO_ENCODER,
    COMPONENT_MUXER
  })
  @Target(TYPE_USE)
  public @interface Component {}

  public static final String COMPONENT_COMPOSITION_PLAYER = "CompositionPlayer";
  public static final String COMPONENT_TRANSFORMER_INTERNAL = "TransformerInternal";
  public static final String COMPONENT_ASSET_LOADER = "AssetLoader";
  public static final String COMPONENT_AUDIO_DECODER = "AudioDecoder";
  public static final String COMPONENT_AUDIO_GRAPH = "AudioGraph";
  public static final String COMPONENT_AUDIO_MIXER = "AudioMixer";
  public static final String COMPONENT_AUDIO_ENCODER = "AudioEncoder";
  public static final String COMPONENT_VIDEO_DECODER = "VideoDecoder";
  public static final String COMPONENT_VFP = "VideoFrameProcessor";
  public static final String COMPONENT_EXTERNAL_TEXTURE_MANAGER = "ExternalTextureManager";
  public static final String COMPONENT_BITMAP_TEXTURE_MANAGER = "BitmapTextureManager";
  public static final String COMPONENT_TEX_ID_TEXTURE_MANAGER = "TexIdTextureManager";
  public static final String COMPONENT_COMPOSITOR = "Compositor";
  public static final String COMPONENT_VIDEO_ENCODER = "VideoEncoder";
  public static final String COMPONENT_MUXER = "Muxer";

  /**
   * Whether to {@linkplain Log#d(String, String) log} tracing events to the logcat as they occur.
   * Should be set to {@code true} for testing and debugging purposes only.
   *
   * <p>Note that enabling this can add a large amount of logcat lines.
   *
   * <p>Requires {@link #enableTracing} to be true.
   */
  private static final boolean ENABLE_TRACES_IN_LOGCAT = false;

  private static final int MAX_FIRST_LAST_LOGS = 10;

  @GuardedBy("DebugTraceUtil.class")
  private static final Map<@Component String, Map<@Event String, EventLogger>>
      componentsToEventsToLogs = new LinkedHashMap<>();

  @GuardedBy("DebugTraceUtil.class")
  private static long startTimeMs = SystemClock.DEFAULT.elapsedRealtime();

  public static synchronized void reset() {
    componentsToEventsToLogs.clear();
    startTimeMs = SystemClock.DEFAULT.elapsedRealtime();
  }

  /**
   * Logs a new event, if debug logging is enabled.
   *
   * @param component The {@link Component} to log.
   * @param event The {@link Event} to log.
   * @param presentationTimeUs The current presentation time of the media. Use {@link C#TIME_UNSET}
   *     if unknown, {@link C#TIME_END_OF_SOURCE} if EOS.
   * @param extraFormat Format string for optional extra information. See {@link
   *     Util#formatInvariant(String, Object...)}.
   * @param extraArgs Arguments for optional extra information.
   */
  @SuppressWarnings("ComputeIfAbsentContainsKey") // Avoid Java8 for visibility
  public static synchronized void logEvent(
      @Component String component,
      @Event String event,
      long presentationTimeUs,
      String extraFormat,
      Object... extraArgs) {
    if (!enableTracing) {
      return;
    }
    logEventInternal(
        component,
        event,
        new StringEventLog(
            presentationTimeUs, getEventTimeMs(), formatInvariant(extraFormat, extraArgs)));
  }

  /**
   * Logs a new event, if debug logging is enabled.
   *
   * @param component The {@link Component} to log.
   * @param event The {@link Event} to log.
   * @param presentationTimeUs The current presentation time of the media. Use {@link C#TIME_UNSET}
   *     if unknown, {@link C#TIME_END_OF_SOURCE} if EOS.
   */
  public static synchronized void logEvent(
      @Component String component, @Event String event, long presentationTimeUs) {
    logEvent(component, event, presentationTimeUs, /* extraFormat= */ "");
  }

  /**
   * Logs an {@link Event} for a codec, if debug logging is enabled.
   *
   * @param isDecoder Whether the codec is a decoder.
   * @param isVideo Whether the codec is for video.
   * @param eventName The {@link Event} to log.
   * @param presentationTimeUs The current presentation time of the media. Use {@link C#TIME_UNSET}
   *     if unknown, {@link C#TIME_END_OF_SOURCE} if EOS.
   * @param extraFormat Format string for optional extra information. See {@link
   *     Util#formatInvariant(String, Object...)}.
   * @param extraArgs Arguments for optional extra information.
   */
  public static synchronized void logCodecEvent(
      boolean isDecoder,
      boolean isVideo,
      @Event String eventName,
      long presentationTimeUs,
      String extraFormat,
      Object... extraArgs) {
    logEvent(
        getCodecComponent(isDecoder, isVideo),
        eventName,
        presentationTimeUs,
        extraFormat,
        extraArgs);
  }

  /**
   * Generate a summary of the logged events, containing the total number of times an event happened
   * and the detailed log of a window of the oldest and newest events.
   */
  public static synchronized String generateTraceSummary() {
    if (!enableTracing) {
      return "\"Tracing disabled\"";
    }
    StringWriter stringWriter = new StringWriter();
    JsonWriter jsonWriter = new JsonWriter(stringWriter);
    try {
      jsonWriter.beginObject();
      for (Entry<@Component String, Map<@Event String, EventLogger>> entry :
          componentsToEventsToLogs.entrySet()) {
        @Component String component = entry.getKey();
        Map<@Event String, EventLogger> eventsToLogs = entry.getValue();
        jsonWriter.name(component).beginObject();
        for (Entry<@Event String, EventLogger> eventEntry : eventsToLogs.entrySet()) {
          jsonWriter.name(eventEntry.getKey());
          eventEntry.getValue().toJson(jsonWriter);
        }
        jsonWriter.endObject();
      }
      jsonWriter.endObject();
      stringWriter.append("--- End of summary---");
      return stringWriter.toString();
    } catch (IOException e) {
      return "\"Error generating trace summary\"";
    } finally {
      Util.closeQuietly(jsonWriter);
    }
  }

  private static synchronized long getEventTimeMs() {
    return SystemClock.DEFAULT.elapsedRealtime() - startTimeMs;
  }

  private static synchronized void logEventInternal(
      String component, String event, EventLog eventLog) {
    if (!componentsToEventsToLogs.containsKey(component)) {
      componentsToEventsToLogs.put(component, new LinkedHashMap<>());
    }
    Map<@Event String, EventLogger> events = componentsToEventsToLogs.get(component);
    if (!events.containsKey(event)) {
      events.put(event, new EventLogger());
    }
    events.get(event).addLog(eventLog);
    if (ENABLE_TRACES_IN_LOGCAT) {
      Log.d("DebugTrace-" + component, event + ": " + eventLog);
    }
  }

  private static String presentationTimeToString(long presentationTimeUs) {
    if (presentationTimeUs == C.TIME_UNSET) {
      return "UNSET";
    } else if (presentationTimeUs == C.TIME_END_OF_SOURCE) {
      return "EOS";
    } else {
      return presentationTimeUs + "us";
    }
  }

  private static @Component String getCodecComponent(boolean isDecoder, boolean isVideo) {
    if (isDecoder) {
      if (isVideo) {
        return COMPONENT_VIDEO_DECODER;
      } else {
        return COMPONENT_AUDIO_DECODER;
      }
    } else {
      if (isVideo) {
        return COMPONENT_VIDEO_ENCODER;
      } else {
        return COMPONENT_AUDIO_ENCODER;
      }
    }
  }

  private abstract static class EventLog {
    public final long presentationTimeUs;
    public final long eventTimeMs;

    protected EventLog(long presentationTimeUs, long eventTimeMs) {
      this.presentationTimeUs = presentationTimeUs;
      this.eventTimeMs = eventTimeMs;
    }

    @Override
    public abstract String toString();
  }

  private static final class StringEventLog extends EventLog {
    public final String extra;

    private StringEventLog(long presentationTimeUs, long eventTimeMs, String extra) {
      super(presentationTimeUs, eventTimeMs);
      this.extra = extra;
    }

    @Override
    public String toString() {
      return formatInvariant("%s@%dms", presentationTimeToString(presentationTimeUs), eventTimeMs)
          + (extra.isEmpty() ? "" : formatInvariant("(%s)", extra));
    }
  }

  private static final class EventLogger {
    private final List<EventLog> firstLogs;
    private final Queue<EventLog> lastLogs;
    private int totalCount;

    public EventLogger() {
      firstLogs = new ArrayList<>(MAX_FIRST_LAST_LOGS);
      lastLogs = new ArrayDeque<>(MAX_FIRST_LAST_LOGS);
      totalCount = 0;
    }

    public void addLog(EventLog log) {
      if (firstLogs.size() < MAX_FIRST_LAST_LOGS) {
        firstLogs.add(log);
      } else {
        lastLogs.add(log);
        if (lastLogs.size() > MAX_FIRST_LAST_LOGS) {
          lastLogs.remove();
        }
      }
      totalCount++;
    }

    public void toJson(JsonWriter jsonWriter) throws IOException {
      jsonWriter.beginObject().name("count").value(totalCount).name("first").beginArray();
      for (EventLog eventLog : firstLogs) {
        jsonWriter.value(eventLog.toString());
      }
      jsonWriter.endArray();
      if (!lastLogs.isEmpty()) {
        jsonWriter.name("last").beginArray();
        for (EventLog eventLog : lastLogs) {
          jsonWriter.value(eventLog.toString());
        }
        jsonWriter.endArray();
      }
      jsonWriter.endObject();
    }
  }
}
