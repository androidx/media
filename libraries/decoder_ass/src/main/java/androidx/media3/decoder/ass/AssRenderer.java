/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.decoder.ass;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.extractor.mkv.FontMetadataEntry;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.dataflow.qual.SideEffectFree;

/**
 * A {@link Renderer} for text.
 *
 * <p>This implementations decodes sample data to {@link Cue} instances. The actual rendering is
 * delegated to a {@link TextOutput}.
 */
@UnstableApi
public final class AssRenderer extends BaseRenderer implements Callback {

  private static final String TAG = "AssRenderer";

  private static final int MSG_UPDATE_OUTPUT = 1;

  private final DecoderInputBuffer assLineDecoderInputBuffer;
  @Nullable private final Handler outputHandler;
  private final TextOutput output;
  private final FormatHolder formatHolder;
  private boolean outputStreamEnded;
  @Nullable private Format streamFormat;
  private long lastRendererPositionUs;
  private long finalStreamEndPositionUs;
  @Nullable private IOException streamError;
  @Nullable private LibassJNI libassJNI;

  // Track management
  private @Nullable String currentTrackId = null;

  private final Set<Long> processedFontUids;
  private long lastTimestampUs;
  // The amount of time to read samples ahead of the current time.
  private static final int SAMPLE_WINDOW_DURATION_US = 100_000;


  /**
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   */
  public AssRenderer(
      TextOutput output,
      @Nullable Looper outputLooper) {
    super(C.TRACK_TYPE_TEXT);
    this.output = checkNotNull(output);
    this.outputHandler =
        outputLooper == null ? null : Util.createHandler(outputLooper, /* callback= */ this);
    this.assLineDecoderInputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    formatHolder = new FormatHolder();
    finalStreamEndPositionUs = C.TIME_UNSET;
    lastRendererPositionUs = C.TIME_UNSET;
    this.libassJNI = null;
    this.processedFontUids = new HashSet<>();
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public @Capabilities int supportsFormat(Format format) {
    @Nullable String mimeType = format.sampleMimeType;
    if (AssLibrary.isAvailable() && Objects.equals(mimeType, MimeTypes.TEXT_SSA)) {
      return RendererCapabilities.create(
          format.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM);
    } else {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
  }

  @Override
  protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs,
                                 MediaSource.MediaPeriodId mediaPeriodId) {
    streamFormat = formats[0];
    Metadata metadata = streamFormat.metadata;
    maybeInitLibassJNI();

    // Get a unique key for the subtitle format
    String formatId = getTrackId(streamFormat);
    currentTrackId = formatId;
    libassJNI.createTrack(formatId);

    // Process font metadata
    if (metadata != null) {
      for (int i = 0; i < metadata.length(); i++) {
        Metadata.Entry entry = metadata.get(i);
        if (entry instanceof FontMetadataEntry) {
          FontMetadataEntry fontEntry = (FontMetadataEntry) entry;
          long uid = fontEntry.getUid();
          if (processedFontUids.contains(uid)) {
            continue;
          }

          String fontFileName = fontEntry.getFileName();
          byte[] fontData = fontEntry.getFontData();
          libassJNI.loadFont(fontFileName, fontData);
          processedFontUids.add(uid);
        }
      }
    }

    // Process format initialization data (ASS headers)
    assert streamFormat != null;
    List<byte[]> assHeaders = streamFormat.initializationData;
    if (assHeaders.size() < 2) {
      throw new IllegalStateException("Invalid ASS format: missing header data. Found " +
          assHeaders.size() + " initialization data entries, expected at least 2.");
    }
    libassJNI.processCodecPrivate(currentTrackId, assHeaders.get(1));
  }


  @Override
  protected void onPositionReset(long positionUs, boolean joining) {
    lastRendererPositionUs = positionUs;
    clearOutput();
    outputStreamEnded = false;
    finalStreamEndPositionUs = C.TIME_UNSET;
    lastTimestampUs = Long.MIN_VALUE;
  }

  /**
   * Creates an instance of the Libass library if it doesn't already exist
   */
  public void maybeInitLibassJNI() {
    if (this.libassJNI != null) {
      return;
    }

    this.libassJNI = new LibassJNI();
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    if (isCurrentStreamFinal()
        && finalStreamEndPositionUs != C.TIME_UNSET
        && positionUs >= finalStreamEndPositionUs) {
      outputStreamEnded = true;
    }

    if (outputStreamEnded) {
      return;
    }

    maybeInitLibassJNI();

    if (currentTrackId == null) {
      Log.w(TAG, "No track ID found. Skipping rendering.");
      return;
    }

    while (!hasReadStreamToEnd() && lastTimestampUs < positionUs + SAMPLE_WINDOW_DURATION_US) {
      assLineDecoderInputBuffer.clear();
      @ReadDataResult int result = readSource(formatHolder, assLineDecoderInputBuffer, /* readFlags= */ 0);
      if (result != C.RESULT_BUFFER_READ || assLineDecoderInputBuffer.isEndOfStream()) {
        break;
      }

      lastTimestampUs = assLineDecoderInputBuffer.timeUs;
      boolean isDecodeOnly = lastTimestampUs < getLastResetPositionUs();
      if (isDecodeOnly) {
        continue;
      }

      assLineDecoderInputBuffer.flip();

      long subtitleStartTimestamp = getPresentationTimeUs(assLineDecoderInputBuffer.timeUs) / 1000;
      ByteBuffer textData = checkNotNull(assLineDecoderInputBuffer.data);
      libassJNI.prepareProcessChunk(textData.array(), textData.position(), textData.remaining(), subtitleStartTimestamp, currentTrackId);
    }

    // Render current subtitles at the current position
    if (currentTrackId != null) {
      long renderTimeMs = getPresentationTimeUs(positionUs) / 1000;
      AssRenderResult renderResult = libassJNI.renderFrame(currentTrackId, renderTimeMs);

      if (renderResult.changedSinceLastCall) {
        if (renderResult.bitmap != null) {
          CueGroup cueGroup = bitmapToCueGroup(renderResult.bitmap, positionUs);
          updateOutput(cueGroup);
        } else {
          // No subtitles to show at this time
          clearOutput();
        }
      }
    }
  }

  @Override
  protected void onDisabled() {
    streamFormat = null;
    finalStreamEndPositionUs = C.TIME_UNSET;
    clearOutput();
    lastRendererPositionUs = C.TIME_UNSET;

    // Release all tracks
    if (libassJNI != null && currentTrackId != null) {
      libassJNI.releaseTrack(currentTrackId);
      currentTrackId = null;
    }
  }

  @Override
  public boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  public boolean isReady() {
    if (streamFormat == null) {
      return true;
    }
    if (streamError == null) {
      try {
        maybeThrowStreamError();
      } catch (IOException e) {
        Log.e(TAG, "Stream error", e);
        streamError = e;
        return false;
      }
    }
    // Don't block playback whilst subtitles are loading.
    // Note: To change this behavior, it will be necessary to consider [Internal: b/12949941].
    return true;
  }
  
  /**
   * Updates the output with the given CueGroup.
   * This method is called when the subtitles are ready to be displayed.
   * @param cueGroup The CueGroup to be displayed.
   */
  private void updateOutput(CueGroup cueGroup) {
    if (outputHandler != null) {
      outputHandler.obtainMessage(MSG_UPDATE_OUTPUT, cueGroup).sendToTarget();
    } else {
      invokeUpdateOutputInternal(cueGroup);
    }
  }

  /**
   * Clears the output by sending an empty CueGroup to the output.
   * This is used to clear the output when there are no subtitles to display.
   */
  private void clearOutput() {
    updateOutput(new CueGroup(ImmutableList.of(), getPresentationTimeUs(lastRendererPositionUs)));
  }

  @Override
  public boolean handleMessage(Message msg) {
    if (msg.what == MSG_UPDATE_OUTPUT) {
      invokeUpdateOutputInternal((CueGroup) msg.obj);
      return true;
    }
    throw new IllegalStateException();
  }

  @Override
  public void handleMessage(@MessageType int messageType, @Nullable Object message)
      throws ExoPlaybackException {
    maybeInitLibassJNI();

    switch (messageType) {
      case MSG_SET_VIDEO_OUTPUT_RESOLUTION:
        Size surfaceSize = (Size) checkNotNull(message, "Surface size message cannot be null");
        libassJNI.setFrameSize(surfaceSize.getWidth(), surfaceSize.getHeight());
        break;

      case MSG_EVENT_VIDEO_SIZE_CHANGED:
        VideoSize videoSize = (VideoSize) checkNotNull(message, "Video size message cannot be null");
        libassJNI.setStorageSize(videoSize.width, videoSize.height);
        break;

      case MSG_EVENT_VIDEO_FORMAT_CHANGED:
        // TODO: Handle this case properly in the alpha blending
        Format videoFormat = (Format) checkNotNull(message, "Video format message cannot be null");
        if (videoFormat.colorInfo != null) {
          Log.d(TAG, "videoFormat.colorInfo = " + videoFormat.colorInfo);
        } else {
          Log.d(TAG, "The video format does not have a defined color info.");
        }
        break;

      default:
        super.handleMessage(messageType, message);
    }
  }

  /**
   * We need to call both onCues methods for backward compatibility.
   * @param cueGroup The CueGroup to be passed to the output.
   */
  @SuppressWarnings("deprecation")
  private void invokeUpdateOutputInternal(CueGroup cueGroup) {
    output.onCues(cueGroup.cues);
    output.onCues(cueGroup);
  }

  /**
   * Used to calculate the presentation time of the subtitles.
   * @param positionUs The current playback position in microseconds.
   * @return The presentation time in microseconds.
   */
  @SideEffectFree
  private long getPresentationTimeUs(long positionUs) {
    checkState(positionUs != C.TIME_UNSET);
    return positionUs - getStreamOffsetUs();
  }

  /**
   * Generate and returns a unique key for a subtitle format to identify tracks, based on format
   * attributes that would define a unique subtitle track.
   *
   * @param format The format to generate a key for.
   * @return A unique key for the format.
   */
  private String getTrackId(Format format) {
    return format.id + "_" +
        format.language + "_" +
        format.selectionFlags + "_" +
        format.roleFlags;
  }

  /**
   * Converts a bitmap containing rendered subtitles into a CueGroup that can be displayed.
   *
   * @param bitmap The bitmap containing the rendered subtitles
   * @param positionUs The current playback position in microseconds
   * @return A CueGroup containing a bitmap cue
   */
  private CueGroup bitmapToCueGroup(Bitmap bitmap, long positionUs) {
    Cue bitmapCue = new Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(0.0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0.0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(1.0f)
        .build();

    return new CueGroup(ImmutableList.of(bitmapCue), getPresentationTimeUs(positionUs));
  }

}
