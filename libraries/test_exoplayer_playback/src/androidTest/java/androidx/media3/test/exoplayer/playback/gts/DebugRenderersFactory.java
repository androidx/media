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
package androidx.media3.test.exoplayer.playback.gts;

import static android.os.Build.VERSION.SDK_INT;
import static java.lang.Math.max;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;

/**
 * A debug extension of {@link DefaultRenderersFactory}. Provides a video renderer that performs
 * video buffer timestamp assertions.
 */
// TODO: Move this class to `testutils` and add basic tests.
/* package */ final class DebugRenderersFactory extends DefaultRenderersFactory {

  private final DefaultMediaCodecAdapterFactory codecAdapterFactory;

  public DebugRenderersFactory(Context context) {
    super(context);
    codecAdapterFactory = new DefaultMediaCodecAdapterFactory(context);
    if (SDK_INT == 36) {
      // Flag disabled for the test due to b/482020055. The impact on playback is minor and can
      // stay enabled for API 36 devices.
      codecAdapterFactory.setAsyncCryptoFlagEnabled(false);
    }
  }

  @Override
  protected void buildVideoRenderers(
      Context context,
      @ExtensionRendererMode int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      long allowedVideoJoiningTimeMs,
      ArrayList<Renderer> out) {
    out.add(
        new DebugMediaCodecVideoRenderer(
            context,
            mediaCodecSelector,
            allowedVideoJoiningTimeMs,
            eventHandler,
            eventListener,
            MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            codecAdapterFactory));
  }

  @Override
  protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
    return codecAdapterFactory;
  }

  /**
   * Decodes and renders video using {@link MediaCodecVideoRenderer}. Provides buffer timestamp
   * assertions.
   */
  private static class DebugMediaCodecVideoRenderer extends MediaCodecVideoRenderer {

    private static final String TAG = "DMCodecVideoRenderer";
    private static final int ARRAY_SIZE = 1000;

    private final long[] timestampsList;

    private int startIndex;
    private int queueSize;
    private int bufferCount;
    private int minimumInsertIndex;

    public DebugMediaCodecVideoRenderer(
        Context context,
        MediaCodecSelector mediaCodecSelector,
        long allowedJoiningTimeMs,
        Handler eventHandler,
        VideoRendererEventListener eventListener,
        int maxDroppedFrameCountToNotify,
        MediaCodecAdapter.Factory codecAdapterFactory) {
      super(
          new Builder(context)
              .setMediaCodecSelector(mediaCodecSelector)
              .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
              .setEventHandler(eventHandler)
              .setEventListener(eventListener)
              .setMaxDroppedFramesToNotify(maxDroppedFrameCountToNotify)
              .setCodecAdapterFactory(codecAdapterFactory)
              // TODO: b/321230611 - Remove this override when 'late' buffers that result in
              // identical release timestamps are reported as 'dropped' instead of 'skipped'.
              .setSkipBuffersWithIdenticalReleaseTime(false));
      timestampsList = new long[ARRAY_SIZE];
    }

    @Override
    public String getName() {
      return TAG;
    }

    @Override
    protected void resetCodecStateForFlush() {
      super.resetCodecStateForFlush();
      clearTimestamps();
    }

    @Override
    @Nullable
    protected DecoderReuseEvaluation onInputFormatChanged(FormatHolder formatHolder)
        throws ExoPlaybackException {
      @Nullable DecoderReuseEvaluation evaluation = super.onInputFormatChanged(formatHolder);
      // Ensure timestamps of buffers queued after this format change are never inserted into the
      // queue of expected output timestamps before those of buffers that have already been queued.
      minimumInsertIndex = startIndex + queueSize;
      return evaluation;
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
      super.onQueueInputBuffer(buffer);
      insertTimestamp(buffer.timeUs);
      maybeShiftTimestampsList();
    }

    @Override
    protected void onProcessedOutputBuffer(long presentationTimeUs) {
      super.onProcessedOutputBuffer(presentationTimeUs);
      bufferCount++;
      long expectedTimestampUs = dequeueTimestamp();
      if (expectedTimestampUs != presentationTimeUs) {
        throw new IllegalStateException(
            "Expected to dequeue video buffer with presentation "
                + "timestamp: "
                + expectedTimestampUs
                + ". Instead got: "
                + presentationTimeUs
                + " (Processed buffers since last flush: "
                + bufferCount
                + ").");
      }
    }

    private void clearTimestamps() {
      startIndex = 0;
      queueSize = 0;
      bufferCount = 0;
      minimumInsertIndex = 0;
    }

    private void insertTimestamp(long presentationTimeUs) {
      for (int i = startIndex + queueSize - 1; i >= minimumInsertIndex; i--) {
        if (presentationTimeUs >= timestampsList[i]) {
          timestampsList[i + 1] = presentationTimeUs;
          queueSize++;
          return;
        }
        timestampsList[i + 1] = timestampsList[i];
      }
      timestampsList[minimumInsertIndex] = presentationTimeUs;
      queueSize++;
    }

    private void maybeShiftTimestampsList() {
      if (startIndex + queueSize == ARRAY_SIZE) {
        System.arraycopy(timestampsList, startIndex, timestampsList, 0, queueSize);
        minimumInsertIndex -= startIndex;
        startIndex = 0;
      }
    }

    private long dequeueTimestamp() {
      queueSize--;
      startIndex++;
      minimumInsertIndex = max(minimumInsertIndex, startIndex);
      return timestampsList[startIndex - 1];
    }
  }
}
