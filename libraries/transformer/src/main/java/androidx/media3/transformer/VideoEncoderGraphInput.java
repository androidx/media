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
package androidx.media3.transformer;

import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_BITMAP;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_SURFACE;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID;
import static com.google.common.base.Preconditions.checkNotNull;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.TimestampIterator;
import java.util.concurrent.atomic.AtomicLong;

/** A wrapper for {@link VideoGraph} input that handles {@link GraphInput} events. */
/* package */ final class VideoEncoderGraphInput implements GraphInput {
  private final VideoGraph videoGraph;
  private final int inputIndex;
  private final long initialTimestampOffsetUs;
  private final AtomicLong mediaItemOffsetUs;

  public VideoEncoderGraphInput(
      VideoGraph videoGraph, int inputIndex, long initialTimestampOffsetUs) {
    this.videoGraph = videoGraph;
    this.inputIndex = inputIndex;
    this.initialTimestampOffsetUs = initialTimestampOffsetUs;
    mediaItemOffsetUs = new AtomicLong();
  }

  @Override
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem,
      long durationUs,
      @Nullable Format decodedFormat,
      boolean isLast,
      @IntRange(from = 0) long positionOffsetUs) {
    boolean isSurfaceAssetLoaderMediaItem = isMediaItemForSurfaceAssetLoader(editedMediaItem);
    durationUs = editedMediaItem.getDurationAfterEffectsApplied(durationUs);
    if (decodedFormat != null) {
      decodedFormat = applyDecoderRotation(decodedFormat);
      videoGraph.registerInputStream(
          inputIndex,
          isSurfaceAssetLoaderMediaItem
              ? VideoFrameProcessor.INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION
              : getInputTypeForMimeType(checkNotNull(decodedFormat.sampleMimeType)),
          decodedFormat,
          editedMediaItem.effects.videoEffects,
          /* offsetToAddUs= */ initialTimestampOffsetUs + mediaItemOffsetUs.get());
    }
    mediaItemOffsetUs.addAndGet(durationUs);
  }

  @Override
  public @InputResult int queueInputBitmap(
      Bitmap inputBitmap, TimestampIterator timestampIterator) {
    return videoGraph.queueInputBitmap(inputIndex, inputBitmap, timestampIterator)
        ? INPUT_RESULT_SUCCESS
        : INPUT_RESULT_TRY_AGAIN_LATER;
  }

  @Override
  public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
    videoGraph.setOnInputFrameProcessedListener(inputIndex, listener);
  }

  @Override
  public void setOnInputSurfaceReadyListener(Runnable runnable) {
    videoGraph.setOnInputSurfaceReadyListener(inputIndex, runnable);
  }

  @Override
  public @InputResult int queueInputTexture(int texId, long presentationTimeUs) {
    return videoGraph.queueInputTexture(inputIndex, texId, presentationTimeUs)
        ? INPUT_RESULT_SUCCESS
        : INPUT_RESULT_TRY_AGAIN_LATER;
  }

  @Override
  public Surface getInputSurface() {
    return videoGraph.getInputSurface(inputIndex);
  }

  @Override
  public int getPendingVideoFrameCount() {
    return videoGraph.getPendingInputFrameCount(inputIndex);
  }

  @Override
  public boolean registerVideoFrame(long presentationTimeUs) {
    return videoGraph.registerInputFrame(inputIndex);
  }

  @Override
  public void signalEndOfVideoInput() {
    videoGraph.signalEndOfInput(inputIndex);
  }

  private static Format applyDecoderRotation(Format format) {
    // The decoder rotates encoded frames for display by format.rotationDegrees.
    if (format.rotationDegrees % 180 == 0) {
      return format;
    }
    return format
        .buildUpon()
        .setWidth(format.height)
        .setHeight(format.width)
        .setRotationDegrees(0)
        .build();
  }

  private static @VideoFrameProcessor.InputType int getInputTypeForMimeType(String sampleMimeType) {
    if (MimeTypes.isImage(sampleMimeType)) {
      return INPUT_TYPE_BITMAP;
    }
    if (sampleMimeType.equals(MimeTypes.VIDEO_RAW)) {
      return INPUT_TYPE_TEXTURE_ID;
    }
    if (MimeTypes.isVideo(sampleMimeType)) {
      return INPUT_TYPE_SURFACE;
    }
    throw new IllegalArgumentException("MIME type not supported " + sampleMimeType);
  }

  private static boolean isMediaItemForSurfaceAssetLoader(EditedMediaItem editedMediaItem) {
    @Nullable
    MediaItem.LocalConfiguration localConfiguration = editedMediaItem.mediaItem.localConfiguration;
    if (localConfiguration == null) {
      return false;
    }
    @Nullable String scheme = localConfiguration.uri.getScheme();
    if (scheme == null) {
      return false;
    }
    return scheme.equals(SurfaceAssetLoader.MEDIA_ITEM_URI_SCHEME);
  }
}
