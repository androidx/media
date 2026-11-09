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
package androidx.media3.test.proguard.minimal;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Class exercising only the core ExoPlayer functionality without optional features (e.g., without
 * setVideoEffects or extension decoders) to ensure unused classes are stripped.
 */
public final class ExoPlayerMinimalModuleProguard {

  private ExoPlayerMinimalModuleProguard() {}

  public static void createAndReleaseExoPlayer(Context context) {
    // TODO: b/289983417 - Remove custom RenderersFactory once legacy subtitle decoding in
    // TextRenderer is removed.
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) ->
            new Renderer[] {
              new MediaCodecVideoRenderer.Builder(context).build(),
              new MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT)
            };
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(
            new DefaultDataSource.Factory(context),
            Mp4Extractor.newFactory(SubtitleParser.Factory.UNSUPPORTED),
            SubtitleParser.Factory.UNSUPPORTED);
    ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory, mediaSourceFactory).build();
    player.setMediaItem(MediaItem.fromUri(Uri.EMPTY));
    player.prepare();
    player.release();
  }
}
