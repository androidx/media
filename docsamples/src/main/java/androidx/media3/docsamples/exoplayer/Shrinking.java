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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

/** Code snippets for the Shrinking guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class Shrinking {

  @OptIn(markerClass = UnstableApi.class)
  public static void customRenderersFactory(Context context) {
    // [START custom_renderers_factory]
    RenderersFactory audioOnlyRenderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) ->
            new Renderer[] {
              new MediaCodecAudioRenderer(
                  context, MediaCodecSelector.DEFAULT, handler, audioListener)
            };
    ExoPlayer player = new ExoPlayer.Builder(context, audioOnlyRenderersFactory).build();
    // [END custom_renderers_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customExtractorsFactory(Context context) {
    // [START custom_extractors_factory]
    ExtractorsFactory mp4ExtractorFactory =
        () -> new Extractor[] {new Mp4Extractor(new DefaultSubtitleParserFactory())};
    ExoPlayer player =
        new ExoPlayer.Builder(context, new DefaultMediaSourceFactory(context, mp4ExtractorFactory))
            .build();
    // [END custom_extractors_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void noExtractorsFactory(Context context) {
    // [START no_extractors_factory]
    ExoPlayer player =
        new ExoPlayer.Builder(
                context, new DefaultMediaSourceFactory(context, ExtractorsFactory.EMPTY))
            .build();
    // [END no_extractors_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customMediaSourceFactory(
      Context context, MediaSource.Factory mediaSourceFactory) {
    // [START custom_media_source_factory]
    ExoPlayer player = new ExoPlayer.Builder(context, mediaSourceFactory).build();
    // [END custom_media_source_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void directMediaSourceInstantiation(
      Context context,
      DataSource.Factory dataSourceFactory,
      ExtractorsFactory customExtractorsFactory,
      Uri uri) {
    // [START direct_media_source_instantiation]
    ExoPlayer player = new ExoPlayer.Builder(context, MediaSource.Factory.UNSUPPORTED).build();
    ProgressiveMediaSource mediaSource =
        new ProgressiveMediaSource.Factory(dataSourceFactory, customExtractorsFactory)
            .createMediaSource(MediaItem.fromUri(uri));
    // [END direct_media_source_instantiation]
  }

  private Shrinking() {}
}
