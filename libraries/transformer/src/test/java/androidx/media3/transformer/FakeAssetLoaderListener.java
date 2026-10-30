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
package androidx.media3.transformer;

import static androidx.media3.transformer.AssetLoader.SUPPORTED_OUTPUT_TYPE_ENCODED;
import static androidx.media3.transformer.TransformerUtil.getProcessedTrackType;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Fake {@link AssetLoader.Listener} implementation for tests. */
/* package */ final class FakeAssetLoaderListener implements AssetLoader.Listener {

  /* package */ final AtomicReference<@Nullable Exception> exceptionRef;
  /* package */ volatile @Nullable Format audioOutputFormat;
  /* package */ volatile @Nullable Format videoOutputFormat;
  /* package */ final @Nullable SampleConsumer audioSampleConsumer;
  /* package */ final @Nullable SampleConsumer videoSampleConsumer;

  private volatile boolean isDurationSet;
  private volatile boolean isTrackCountSet;
  private volatile boolean isAudioTrackAdded;
  private volatile boolean isVideoTrackAdded;

  /* package */ FakeAssetLoaderListener() {
    this(new FakeSampleConsumer(), new FakeSampleConsumer());
  }

  /* package */ FakeAssetLoaderListener(
      @Nullable SampleConsumer audioSampleConsumer, @Nullable SampleConsumer videoSampleConsumer) {
    this.audioSampleConsumer = audioSampleConsumer;
    this.videoSampleConsumer = videoSampleConsumer;
    exceptionRef = new AtomicReference<>();
  }

  @Override
  public void onDurationUs(long durationUs) {
    // Sleep to increase the chances of a test failing if callbacks are called out of order from
    // different threads.
    sleep();
    isDurationSet = true;
  }

  @Override
  public void onTrackCount(int trackCount) {
    // Sleep to increase the chances of a test failing if callbacks are called out of order from
    // different threads.
    sleep();
    isTrackCountSet = true;
  }

  @Override
  public boolean onTrackAdded(
      Format inputFormat, @AssetLoader.SupportedOutputTypes int supportedOutputTypes) {
    if (!isDurationSet) {
      exceptionRef.set(new IllegalStateException("onTrackAdded() called before onDurationUs()"));
    } else if (!isTrackCountSet) {
      exceptionRef.set(new IllegalStateException("onTrackAdded() called before onTrackCount()"));
    }
    sleep();
    if (getProcessedTrackType(inputFormat.sampleMimeType) == C.TRACK_TYPE_VIDEO) {
      isVideoTrackAdded = true;
    } else {
      isAudioTrackAdded = true;
    }
    return (supportedOutputTypes & SUPPORTED_OUTPUT_TYPE_ENCODED) == 0;
  }

  @Override
  public @Nullable SampleConsumer onOutputFormat(Format format) {
    boolean isVideo = getProcessedTrackType(format.sampleMimeType) == C.TRACK_TYPE_VIDEO;
    boolean isTrackAdded = isVideo ? isVideoTrackAdded : isAudioTrackAdded;
    if (!isTrackAdded) {
      exceptionRef.set(new IllegalStateException("onOutputFormat() called before onTrackAdded()"));
    }
    if (isVideo) {
      videoOutputFormat = format;
      return videoSampleConsumer;
    } else {
      audioOutputFormat = format;
      return audioSampleConsumer;
    }
  }

  @Override
  public void onError(ExportException exportException) {
    exceptionRef.set(exportException);
  }

  private void sleep() {
    try {
      Thread.sleep(/* millis= */ 10);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exceptionRef.set(e);
    }
  }
}
