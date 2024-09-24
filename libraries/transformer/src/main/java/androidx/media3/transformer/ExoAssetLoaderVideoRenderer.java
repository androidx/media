/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.transformer.TransformerUtil.getDecoderOutputColor;

import android.media.MediaCodec;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/* package */ final class ExoAssetLoaderVideoRenderer extends ExoAssetLoaderBaseRenderer {

  private static final String TAG = "ExoAssetLoaderVideoRenderer";

  private final boolean flattenForSlowMotion;
  private final Codec.DecoderFactory decoderFactory;
  private final @Composition.HdrMode int hdrMode;
  private final List<Long> decodeOnlyPresentationTimestamps;

  private @MonotonicNonNull SefSlowMotionFlattener sefVideoSlowMotionFlattener;
  private int maxDecoderPendingFrameCount;

  public ExoAssetLoaderVideoRenderer(
      boolean flattenForSlowMotion,
      Codec.DecoderFactory decoderFactory,
      @Composition.HdrMode int hdrMode,
      TransformerMediaClock mediaClock,
      AssetLoader.Listener assetLoaderListener) {
    super(C.TRACK_TYPE_VIDEO, mediaClock, assetLoaderListener);
    this.flattenForSlowMotion = flattenForSlowMotion;
    this.decoderFactory = decoderFactory;
    this.hdrMode = hdrMode;
    decodeOnlyPresentationTimestamps = new ArrayList<>();
    maxDecoderPendingFrameCount = C.INDEX_UNSET;
  }

  @Override
  public String getName() {
    return TAG;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The duration is calculated based on the number of {@linkplain #maxDecoderPendingFrameCount
   * allowed pending frames}.
   */
  @Override
  public long getDurationToProgressUs(long positionUs, long elapsedRealtimeUs) {
    if (maxDecoderPendingFrameCount == C.INDEX_UNSET) {
      return DEFAULT_DURATION_TO_PROGRESS_US;
    }
    // TODO: b/258809496 - Consider using async API and dynamic scheduling when decoder input
    //  slots are available.
    return maxDecoderPendingFrameCount * 2_000L;
  }

  @Override
  protected Format overrideInputFormat(Format format) {
    if (hdrMode == Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
        && ColorInfo.isTransferHdr(format.colorInfo)) {
      return format.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build();
    }
    return format;
  }

  @Override
  protected Format overrideOutputFormat(Format format) {
    // Gets the expected output color from the decoder, based on the input track format, if
    // tone-mapping is applied.
    ColorInfo validColor = TransformerUtil.getValidColor(format.colorInfo);
    boolean isDecoderToneMappingRequested =
        hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC;
    ColorInfo outputColor = getDecoderOutputColor(validColor, isDecoderToneMappingRequested);

    return format.buildUpon().setColorInfo(outputColor).build();
  }

  @Override
  protected void onInputFormatRead(Format inputFormat) {
    if (flattenForSlowMotion) {
      sefVideoSlowMotionFlattener = new SefSlowMotionFlattener(inputFormat);
    }
  }

  @Override
  protected void initDecoder(Format inputFormat) throws ExportException {
    // TODO(b/278259383): Move surface creation out of sampleConsumer. Init decoder before
    //  sampleConsumer.
    checkStateNotNull(sampleConsumer);
    boolean isDecoderToneMappingRequired =
        ColorInfo.isTransferHdr(inputFormat.colorInfo)
            && hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC;
    decoder =
        decoderFactory.createForVideoDecoding(
            inputFormat,
            checkNotNull(sampleConsumer.getInputSurface()),
            isDecoderToneMappingRequired);
    maxDecoderPendingFrameCount = decoder.getMaxPendingFrameCount();
  }

  @Override
  protected boolean shouldDropInputBuffer(DecoderInputBuffer inputBuffer) {
    if (inputBuffer.isEndOfStream()) {
      return false;
    }

    ByteBuffer inputBytes = checkNotNull(inputBuffer.data);
    if (sefVideoSlowMotionFlattener != null) {
      long streamOffsetUs = getStreamOffsetUs();
      long presentationTimeUs = inputBuffer.timeUs - streamOffsetUs;
      boolean shouldDropInputBuffer =
          sefVideoSlowMotionFlattener.dropOrTransformSample(inputBytes, presentationTimeUs);
      if (shouldDropInputBuffer) {
        inputBytes.clear();
        return true;
      }
      inputBuffer.timeUs =
          streamOffsetUs + sefVideoSlowMotionFlattener.getSamplePresentationTimeUs();
    }

    if (decoder == null) {
      inputBuffer.timeUs -= streamStartPositionUs;
    }
    return false;
  }

  @Override
  protected void onDecoderInputReady(DecoderInputBuffer inputBuffer) {
    if (inputBuffer.timeUs < getLastResetPositionUs()) {
      decodeOnlyPresentationTimestamps.add(inputBuffer.timeUs);
    }
  }

  @Override
  @RequiresNonNull({"sampleConsumer", "decoder"})
  protected boolean feedConsumerFromDecoder() throws ExportException {
    if (decoder.isEnded()) {
      sampleConsumer.signalEndOfVideoInput();
      isEnded = true;
      return false;
    }

    @Nullable MediaCodec.BufferInfo decoderOutputBufferInfo = decoder.getOutputBufferInfo();
    if (decoderOutputBufferInfo == null) {
      return false;
    }

    long presentationTimeUs = decoderOutputBufferInfo.presentationTimeUs - streamStartPositionUs;
    // Drop samples with negative timestamp in the transcoding case, to prevent encoder failures.
    if (presentationTimeUs < 0 || isDecodeOnlyBuffer(decoderOutputBufferInfo.presentationTimeUs)) {
      decoder.releaseOutputBuffer(/* render= */ false);
      return true;
    }

    if (sampleConsumer.getPendingVideoFrameCount() == maxDecoderPendingFrameCount) {
      return false;
    }

    if (!sampleConsumer.registerVideoFrame(presentationTimeUs)) {
      return false;
    }

    decoder.releaseOutputBuffer(presentationTimeUs);
    return true;
  }

  private boolean isDecodeOnlyBuffer(long presentationTimeUs) {
    // We avoid using decodeOnlyPresentationTimestamps.remove(presentationTimeUs) because it would
    // box presentationTimeUs, creating a Long object that would need to be garbage collected.
    int size = decodeOnlyPresentationTimestamps.size();
    for (int i = 0; i < size; i++) {
      if (decodeOnlyPresentationTimestamps.get(i) == presentationTimeUs) {
        decodeOnlyPresentationTimestamps.remove(i);
        return true;
      }
    }
    return false;
  }
}
