/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_BITMAP;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_SURFACE;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID;
import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A wrapper for {@link VideoFrameProcessor} that handles {@link GraphInput} events. */
/* package */ final class VideoFrameProcessingWrapper implements GraphInput {
  private final VideoFrameProcessor videoFrameProcessor;
  private final List<Effect> postProcessingEffects;
  private final long initialTimestampOffsetUs;
  private final AtomicLong mediaItemOffsetUs;

  public VideoFrameProcessingWrapper(
      VideoFrameProcessor videoFrameProcessor,
      List<Effect> postProcessingEffects,
      long initialTimestampOffsetUs) {
    this.videoFrameProcessor = videoFrameProcessor;
    this.postProcessingEffects = postProcessingEffects;
    this.initialTimestampOffsetUs = initialTimestampOffsetUs;
    mediaItemOffsetUs = new AtomicLong();
  }

  @Override
  public void onMediaItemChanged(
      EditedMediaItem editedMediaItem,
      long durationUs,
      @Nullable Format decodedFormat,
      boolean isLast) {
    checkArgument(!editedMediaItem.isGap());
    boolean isSurfaceAssetLoaderMediaItem = isMediaItemForSurfaceAssetLoader(editedMediaItem);
    durationUs = editedMediaItem.getDurationAfterEffectsApplied(durationUs);
    if (decodedFormat != null) {
      Size decodedSize = getDecodedSize(decodedFormat);
      ImmutableList<Effect> combinedEffects =
          new ImmutableList.Builder<Effect>()
              .addAll(editedMediaItem.effects.videoEffects)
              .addAll(postProcessingEffects)
              .build();
      videoFrameProcessor.registerInputStream(
          isSurfaceAssetLoaderMediaItem
              ? VideoFrameProcessor.INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION
              : getInputTypeForMimeType(checkNotNull(decodedFormat.sampleMimeType)),
          combinedEffects,
          new FrameInfo.Builder(
                  checkNotNull(decodedFormat.colorInfo),
                  decodedSize.getWidth(),
                  decodedSize.getHeight())
              .setPixelWidthHeightRatio(decodedFormat.pixelWidthHeightRatio)
              .setOffsetToAddUs(initialTimestampOffsetUs + mediaItemOffsetUs.get())
              .build());
    }
    mediaItemOffsetUs.addAndGet(durationUs);
  }

  @Override
  public @InputResult int queueInputBitmap(
      Bitmap inputBitmap, TimestampIterator timestampIterator) {
    return videoFrameProcessor.queueInputBitmap(inputBitmap, timestampIterator)
        ? INPUT_RESULT_SUCCESS
        : INPUT_RESULT_TRY_AGAIN_LATER;
  }

  @Override
  public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
    videoFrameProcessor.setOnInputFrameProcessedListener(listener);
  }

  @Override
  public void setOnInputSurfaceReadyListener(Runnable runnable) {
    videoFrameProcessor.setOnInputSurfaceReadyListener(runnable);
  }

  @Override
  public @InputResult int queueInputTexture(int texId, long presentationTimeUs) {
    return videoFrameProcessor.queueInputTexture(texId, presentationTimeUs)
        ? INPUT_RESULT_SUCCESS
        : INPUT_RESULT_TRY_AGAIN_LATER;
  }

  @Override
  public Surface getInputSurface() {
    return videoFrameProcessor.getInputSurface();
  }

  @Override
  public int getPendingVideoFrameCount() {
    return videoFrameProcessor.getPendingInputFrameCount();
  }

  @Override
  public boolean registerVideoFrame(long presentationTimeUs) {
    return videoFrameProcessor.registerInputFrame();
  }

  @Override
  public void signalEndOfVideoInput() {
    videoFrameProcessor.signalEndOfInput();
  }

  public void release() {
    videoFrameProcessor.release();
  }

  private static Size getDecodedSize(Format format) {
    // The decoder rotates encoded frames for display by firstInputFormat.rotationDegrees.
    int decodedWidth = (format.rotationDegrees % 180 == 0) ? format.width : format.height;
    int decodedHeight = (format.rotationDegrees % 180 == 0) ? format.height : format.width;
    return new Size(decodedWidth, decodedHeight);
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
