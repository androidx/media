/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_TARGET_LOG2_HDR_HEADROOM;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.ParserException;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HagcMetadata;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Processes {@link DefaultGlFrameProcessor#BT2020_LINEAR} {@link GlTextureFrame}s carrying HAGC (ST
 * 2094-50) headroom-adaptive tone-mapping metadata.
 *
 * <p>Frames without {@link DefaultGlFrameProcessor#BT2020_LINEAR} color info, valid HAGC tone-map
 * metadata, or a target HDR headroom are passed through unmodified.
 *
 * <p>Methods in this class must be called on the GL thread unless otherwise stated.
 */
/* package */ final class HagcGlTextureFrameProcessor extends BaseGlTextureFrameProcessor {

  private static final String TAG = "HagcGlFrameProcessor";

  private byte @Nullable [] lastPayloadBytes;
  private @Nullable HagcMetadata lastParsedMetadata;

  /**
   * Creates an instance.
   *
   * <p>May be called on any thread.
   *
   * @param errorConsumerExecutor The {@link Executor} on which {@code errorConsumer} is invoked.
   * @param errorConsumer The {@link Consumer} to report errors to.
   */
  /* package */ HagcGlTextureFrameProcessor(
      Executor errorConsumerExecutor, Consumer<VideoFrameProcessingException> errorConsumer) {
    super(errorConsumerExecutor, errorConsumer);
  }

  @Override
  protected boolean hasInFlightFrames() {
    return false;
  }

  @Override
  protected void onFrameDiscontinuity() {}

  @Override
  protected boolean onQueueFrame(
      GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
    if (hasValidToneMap(frame)) {
      // TODO: b/575451990 - Apply GPU tone mapping.
    }
    return checkNotNull(downstreamConsumer).queue(frame, listenerExecutor, wakeupListener);
  }

  @Override
  protected void onEndOfStream() {
    checkNotNull(downstreamConsumer).signalEndOfStream();
  }

  @Override
  protected void onClose() {
    lastPayloadBytes = null;
    lastParsedMetadata = null;
  }

  private boolean hasValidToneMap(GlTextureFrame inputFrame) {
    @Nullable ColorInfo colorInfo = inputFrame.format.colorInfo;
    if (colorInfo == null
        || colorInfo.colorSpace != C.COLOR_SPACE_BT2020
        || colorInfo.colorTransfer != C.COLOR_TRANSFER_LINEAR) {
      return false;
    }
    @Nullable Object rawPayload = inputFrame.getMetadata().get(Frame.KEY_HDR_ST2094_50_INFO);
    if (rawPayload != null) {
      checkArgument(rawPayload instanceof byte[], "KEY_HDR_ST2094_50_INFO must be a byte[].");
    }
    @Nullable Object rawHeadroom = inputFrame.getMetadata().get(KEY_TARGET_LOG2_HDR_HEADROOM);
    if (rawHeadroom != null) {
      checkArgument(rawHeadroom instanceof Float, "KEY_TARGET_LOG2_HDR_HEADROOM must be a Float.");
      float targetLog2Headroom = (Float) rawHeadroom;
      checkArgument(
          Float.isFinite(targetLog2Headroom) && targetLog2Headroom >= 0.0f,
          "KEY_TARGET_LOG2_HDR_HEADROOM must be finite and >= 0.0f: %s",
          targetLog2Headroom);
    }
    if (rawPayload == null || rawHeadroom == null) {
      return false;
    }
    byte[] payload = (byte[]) rawPayload;
    if (!Arrays.equals(payload, lastPayloadBytes)) {
      lastPayloadBytes = payload.clone();
      try {
        lastParsedMetadata = HagcMetadata.parse(payload);
      } catch (ParserException e) {
        Log.w(TAG, "Ignoring malformed HAGC metadata", e);
        lastParsedMetadata = null;
      }
    }
    return lastParsedMetadata != null && lastParsedMetadata.headroomAdaptiveToneMap != null;
  }
}
