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

import static androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;

import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/** Fake {@link AssetLoader} implementation for tests. */
/* package */ final class FakeAssetLoader implements AssetLoader {

  /** A factory for {@link FakeAssetLoader} instances. */
  /* package */ static final class Factory implements AssetLoader.Factory {

    /* package */ final List<FakeAssetLoader> createdAssetLoaders;
    private final ImmutableList<Format> formats;
    private final @SupportedOutputTypes int supportedOutputTypes;

    /* package */ Factory(List<Format> formats, @SupportedOutputTypes int supportedOutputTypes) {
      this.formats = ImmutableList.copyOf(formats);
      this.supportedOutputTypes = supportedOutputTypes;
      createdAssetLoaders = new CopyOnWriteArrayList<>();
    }

    /**
     * Creates {@link FakeAssetLoader} and tracks all asset loaders in {@link createdAssetLoaders}.
     */
    @Override
    public AssetLoader createAssetLoader(
        EditedMediaItem editedMediaItem,
        Looper looper,
        Listener listener,
        CompositionSettings compositionSettings) {
      FakeAssetLoader assetLoader =
          new FakeAssetLoader(listener, editedMediaItem.durationUs, formats, supportedOutputTypes);
      createdAssetLoaders.add(assetLoader);
      return assetLoader;
    }
  }

  /* package */ final Map<Integer, SampleConsumer> sampleConsumersByTrackType;
  private final Listener listener;
  private final long durationUs;
  private final ImmutableList<Format> formats;
  private final @SupportedOutputTypes int supportedOutputTypes;

  private FakeAssetLoader(
      Listener listener,
      long durationUs,
      ImmutableList<Format> formats,
      @SupportedOutputTypes int supportedOutputTypes) {
    this.listener = listener;
    this.durationUs = durationUs;
    this.formats = formats;
    this.supportedOutputTypes = supportedOutputTypes;
    sampleConsumersByTrackType = new ConcurrentHashMap<>();
  }

  /**
   * Reports added tracks and output formats to the {@link listener}, and tracks sample consumers.
   */
  @Override
  public void start() {
    listener.onDurationUs(durationUs);
    listener.onTrackCount(formats.size());
    for (Format format : formats) {
      listener.onTrackAdded(format, supportedOutputTypes);
    }
    try {
      for (Format format : formats) {
        @Nullable SampleConsumer sampleConsumer = listener.onOutputFormat(format);
        if (sampleConsumer != null) {
          sampleConsumersByTrackType.put(
              MimeTypes.getTrackType(format.sampleMimeType), sampleConsumer);
        }
      }
    } catch (ExportException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    return PROGRESS_STATE_UNAVAILABLE;
  }

  @Override
  public ImmutableMap<Integer, String> getDecoderNames() {
    return ImmutableMap.of();
  }

  @Override
  public void release() {}
}
