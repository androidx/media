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
package androidx.media3.exoplayer.source;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.base.Supplier;

/**
 * A forwarding {@link MediaSource.Factory} that forwards all methods to the underlying
 * implementation.
 */
@UnstableApi
public class ForwardingMediaSourceFactory implements MediaSource.Factory {

  private final MediaSource.Factory factory;

  /**
   * Creates a forwarding {@link MediaSource.Factory}.
   *
   * @param factory The {@link MediaSource.Factory} to forward to.
   */
  public ForwardingMediaSourceFactory(MediaSource.Factory factory) {
    this.factory = factory;
  }

  @Override
  public MediaSource createMediaSource(MediaItem mediaItem) {
    return factory.createMediaSource(mediaItem);
  }

  @SuppressWarnings("deprecation") // Forwarding deprecated method.
  @Override
  public MediaSource.Factory experimentalParseSubtitlesDuringExtraction(
      boolean parseSubtitlesDuringExtraction) {
    return factory.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction);
  }

  @Override
  public MediaSource.Factory experimentalSetCodecsToParseWithinGopSampleDependencies(
      @C.VideoCodecFlags int codecsToParseWithinGopSampleDependencies) {
    return factory.experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies);
  }

  @Override
  public @C.ContentType int[] getSupportedTypes() {
    return factory.getSupportedTypes();
  }

  @Override
  public MediaSource.Factory setCmcdConfigurationFactory(
      CmcdConfiguration.Factory cmcdConfigurationFactory) {
    return factory.setCmcdConfigurationFactory(cmcdConfigurationFactory);
  }

  @Override
  public MediaSource.Factory setDownloadExecutor(Supplier<ReleasableExecutor> downloadExecutor) {
    return factory.setDownloadExecutor(downloadExecutor);
  }

  @Override
  public MediaSource.Factory setDrmSessionManagerProvider(
      DrmSessionManagerProvider drmSessionManagerProvider) {
    return factory.setDrmSessionManagerProvider(drmSessionManagerProvider);
  }

  @Override
  public MediaSource.Factory setLoadErrorHandlingPolicy(
      LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
    return factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
  }

  @Override
  public MediaSource.Factory setSubtitleParserFactory(
      SubtitleParser.Factory subtitleParserFactory) {
    return factory.setSubtitleParserFactory(subtitleParserFactory);
  }
}
