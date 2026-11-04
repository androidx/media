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
package androidx.media3.docsamples.transformer;

import static java.lang.Math.min;

import android.content.Context;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;

/** Snippets for editing-app.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class EditingApp {

  private EditingApp() {}

  @OptIn(markerClass = UnstableApi.class)
  public static void transcodeBetweenFormats(Context context) {
    // [START transcode_between_formats]
    Transformer transformer =
        new Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build();
    // [END transcode_between_formats]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setHdrMode(EditedMediaItem editedMediaItem) {
    // [START set_hdr_mode]
    EditedMediaItemSequence videoSequence =
        EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(editedMediaItem));
    Composition composition =
        new Composition.Builder(videoSequence).setHdrMode(Composition.HDR_MODE_KEEP_HDR).build();
    // [END set_hdr_mode]
  }

  public static void trimVideo(Uri videoUri) {
    // [START trim_video]
    ClippingConfiguration clippingConfiguration =
        new MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(10_000) // start at 10 seconds
            .setEndPositionMs(20_000) // end at 20 seconds
            .build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(clippingConfiguration)
            .build();
    // [END trim_video]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void useBuiltInEffects(MediaItem mediaItem) {
    // [START use_built_in_effects]
    ChannelMixingAudioProcessor channelMixingProcessor = new ChannelMixingAudioProcessor();
    channelMixingProcessor.putChannelMixingMatrix(
        ChannelMixingMatrix.createForConstantGain(
            /* inputChannelCount= */ 2, /* outputChannelCount= */ 1));
    ScaleAndRotateTransformation rotateEffect =
        new ScaleAndRotateTransformation.Builder().setRotationDegrees(60f).build();
    Crop cropEffect = new Crop(-0.5f, 0.5f, -0.5f, 0.5f);
    Effects effects =
        new Effects(
            ImmutableList.of(channelMixingProcessor), ImmutableList.of(rotateEffect, cropEffect));

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setEffects(effects).build();
    // [END use_built_in_effects]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createCustomEffects(MediaItem inputMediaItem) {
    // [START create_custom_effects]
    MatrixTransformation zoomEffect =
        presentationTimeUs -> {
          Matrix transformationMatrix = new Matrix();
          // Set the scaling factor based on the playback position
          float scale = min(1f, presentationTimeUs / 1_000_000f);
          transformationMatrix.postScale(/* sx= */ scale, /* sy= */ scale);
          return transformationMatrix;
        };

    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(inputMediaItem)
            .setEffects(new Effects(ImmutableList.of(), ImmutableList.of(zoomEffect)))
            .build();
    // [END create_custom_effects]
  }

  @OptIn(markerClass = {ExperimentalApi.class, UnstableApi.class})
  public static void previewComposition(Context context, Composition composition) {
    // [START preview_composition]
    CompositionPlayer compositionPlayer = new CompositionPlayer.Builder(context).build();
    compositionPlayer.setComposition(composition);
    compositionPlayer.prepare();
    compositionPlayer.play();
    // [END preview_composition]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void previewVideoEffects(
      Context context, MediaItem inputMediaItem, Effect zoomEffect) {
    // [START preview_video_effects]
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    player.setMediaItem(inputMediaItem);
    player.setVideoEffects(ImmutableList.of(zoomEffect));
    player.prepare();
    // [END preview_video_effects]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void previewAudioEffects(
      Context context, ChannelMixingAudioProcessor channelMixingProcessor) {
    // [START preview_audio_effects]
    ExoPlayer player =
        new ExoPlayer.Builder(
                context,
                new DefaultRenderersFactory(context) {
                  @Override
                  protected AudioSink buildAudioSink(
                      Context context,
                      boolean enableFloatOutput,
                      boolean enableAudioOutputPlaybackParams) {
                    return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[] {channelMixingProcessor})
                        .build();
                  }
                })
            .build();
    // [END preview_audio_effects]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void startTransformation(
      Context context,
      Transformer.Listener listener,
      EditedMediaItem editedMediaItem,
      String outputPath) {
    // [START start_transformation]
    Transformer transformer = new Transformer.Builder(context).addListener(listener).build();
    transformer.start(editedMediaItem, outputPath);
    // [END start_transformation]
  }
}
