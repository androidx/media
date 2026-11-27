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

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;

/** Snippets for transformations.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class Transformations {

  private Transformations() {}

  @OptIn(markerClass = UnstableApi.class)
  public static void transcodeBetweenFormats(Context context) {
    // [START transcode_between_formats]
    new Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .build();
    // [END transcode_between_formats]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void removeAudio(MediaItem inputMediaItem) {
    // [START remove_audio]
    new EditedMediaItem.Builder(inputMediaItem).setRemoveAudio(true).build();
    // [END remove_audio]
  }

  public static void trimAClip(Uri uri) {
    // [START trim_a_clip]
    MediaItem inputMediaItem =
        new MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(10_000)
                    .setEndPositionMs(20_000)
                    .build())
            .build();
    // [END trim_a_clip]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void applyVideoEffects(Uri uri) {
    // [START apply_video_effects]
    new EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(
            new Effects(
                /* audioProcessors= */ ImmutableList.of(),
                /* videoEffects= */ ImmutableList.of(Presentation.createForHeight(480))))
        .build();
    // [END apply_video_effects]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void applyVideoEffectsScaling(Uri uri) {
    // [START apply_video_effects_scaling]
    new EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(
            new Effects(
                /* audioProcessors= */ ImmutableList.of(),
                /* videoEffects= */ ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setScale(.5f, .5f).build())))
        .build();
    // [END apply_video_effects_scaling]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void applyVideoEffectsRotation(Uri uri) {
    // [START apply_video_effects_rotation]
    new EditedMediaItem.Builder(MediaItem.fromUri(uri))
        .setEffects(
            new Effects(
                /* audioProcessors= */ ImmutableList.of(),
                /* videoEffects= */ ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build())))
        .build();
    // [END apply_video_effects_rotation]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void transformationsImageInput(Uri imageUri) {
    // [START transformations_input_image]
    MediaItem imageMediaItem =
        new MediaItem.Builder()
            .setUri(imageUri)
            .setImageDurationMs(5000) // 5 seconds
            .build();
    new EditedMediaItem.Builder(imageMediaItem)
        .setFrameRate(30) // 30 frames per second
        .build();
    // [END transformations_input_image]
  }

  @OptIn(markerClass = {ExperimentalApi.class, UnstableApi.class})
  public static void mp4EditList(Context context) {
    // [START mp4-edit-lists]
    new Transformer.Builder(context).experimentalSetMp4EditListTrimEnabled(true).build();
    // [END mp4-edit-lists]
  }

  @OptIn(markerClass = {ExperimentalApi.class, UnstableApi.class})
  public static void trimOptimization(Context context) {
    // [START trim_optimization]
    new Transformer.Builder(context).experimentalSetTrimOptimizationEnabled(true).build();
    // [END trim_optimization]
  }
}
