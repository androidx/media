/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.transformer.TransformerUtil.containsSpeedChangingEffects;
import static androidx.media3.transformer.TransformerUtil.validateSpeedChangingEffects;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.SpeedProviderUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.mp4.Mp4Extractor;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** A {@link MediaItem} with the transformations to apply to it. */
@UnstableApi
public final class EditedMediaItem {
  /* package */ static final String GAP_MEDIA_ID = "androidx-media3-GapMediaItem";

  /** A builder for {@link EditedMediaItem} instances. */
  public static final class Builder {

    private MediaItem mediaItem;
    private boolean removeAudio;
    private boolean removeVideo;
    private boolean flattenForSlowMotion;
    private long durationUs;
    private int frameRate;
    private Effects effects;
    private SpeedProvider speedProvider;
    private boolean allowMatchingSpeedChangingEffectForSpeedProvider;

    /**
     * Creates an instance.
     *
     * <p>For image inputs:
     *
     * <ul>
     *   <li>The {@linkplain MediaItem.Builder#setImageDurationMs(long) image duration} should
     *       always be set.
     *   <li>The values passed into {@link #setRemoveAudio}, {@link #setRemoveVideo} and {@link
     *       #setFlattenForSlowMotion} will be ignored.
     *   <li>For multi-picture formats (e.g. gifs), a single image frame from the container is
     *       displayed if the {@link DefaultAssetLoaderFactory} is used.
     * </ul>
     *
     * @param mediaItem The {@link MediaItem} on which transformations are applied.
     */
    public Builder(MediaItem mediaItem) {
      this.mediaItem = mediaItem;
      durationUs =
          mediaItem.localConfiguration == null
              ? C.TIME_UNSET
              : Util.msToUs(mediaItem.localConfiguration.imageDurationMs);
      frameRate = C.RATE_UNSET_INT;
      effects = Effects.EMPTY;
      speedProvider = SpeedProvider.DEFAULT;
    }

    private Builder(EditedMediaItem editedMediaItem) {
      this.mediaItem = editedMediaItem.mediaItem;
      this.removeAudio = editedMediaItem.removeAudio;
      this.removeVideo = editedMediaItem.removeVideo;
      this.flattenForSlowMotion = editedMediaItem.flattenForSlowMotion;
      this.durationUs = editedMediaItem.durationUs;
      this.frameRate = editedMediaItem.frameRate;
      this.effects = editedMediaItem.effects;
      this.speedProvider = editedMediaItem.speedProvider;
      this.allowMatchingSpeedChangingEffectForSpeedProvider =
          editedMediaItem.allowMatchingSpeedChangingEffectForSpeedProvider;
    }

    /**
     * Sets whether to remove the audio from the {@link MediaItem}.
     *
     * <p>The default value is {@code false}.
     *
     * <p>The audio and video cannot both be removed because the output would not contain any
     * samples.
     *
     * @param removeAudio Whether to remove the audio.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setRemoveAudio(boolean removeAudio) {
      this.removeAudio = removeAudio;
      return this;
    }

    /**
     * Sets whether to remove the video from the {@link MediaItem}.
     *
     * <p>The default value is {@code false}.
     *
     * <p>The audio and video cannot both be removed because the output would not contain any
     * samples.
     *
     * @param removeVideo Whether to remove the video.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setRemoveVideo(boolean removeVideo) {
      this.removeVideo = removeVideo;
      return this;
    }

    /**
     * Sets whether to flatten the {@link MediaItem} if it contains slow motion markers.
     *
     * <p>The default value is {@code false}.
     *
     * <p>See {@link #flattenForSlowMotion} for more information about slow motion flattening.
     *
     * <p>If using an {@link ExoPlayerAssetLoader.Factory} with a provided {@link
     * MediaSource.Factory}, make sure that {@link Mp4Extractor#FLAG_READ_SEF_DATA} is set on the
     * {@link Mp4Extractor} used. Otherwise, the slow motion metadata will be ignored and the input
     * won't be flattened.
     *
     * <p>Slow motion flattening is only supported when the {@link Composition} contains exactly one
     * {@link MediaItem}.
     *
     * <p>Using slow motion flattening together with {@link MediaItem.ClippingConfiguration} is not
     * supported yet.
     *
     * @param flattenForSlowMotion Whether to flatten for slow motion.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setFlattenForSlowMotion(boolean flattenForSlowMotion) {
      // TODO: b/233986762 - Support clipping with SEF flattening.
      checkArgument(
          mediaItem.clippingConfiguration.equals(MediaItem.ClippingConfiguration.UNSET)
              || !flattenForSlowMotion,
          "Slow motion flattening is not supported when clipping is requested");
      this.flattenForSlowMotion = flattenForSlowMotion;
      return this;
    }

    /**
     * Sets the duration in microseconds of the source media represented by the {@link MediaItem}.
     *
     * <p>The provided duration should match the duration of the source media before applying any
     * {@linkplain MediaItem.ClippingConfiguration clipping} or {@linkplain #setSpeed speed-changing
     * effects}.
     *
     * <p>Setting the duration is optional in the following cases:
     *
     * <ul>
     *   <li>The {@link MediaItem} represents an image. The default image duration set via {@link
     *       MediaItem.Builder#setImageDurationMs}.
     *   <li>On {@link Transformer}, when the {@link MediaItem} has an intrinsic duration (e.g.
     *       encoded video/audio data from an input file).
     * </ul>
     *
     * @param durationUs The duration, in microseconds.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setDurationUs(@IntRange(from = 1) long durationUs) {
      checkArgument(durationUs > 0);
      this.durationUs = durationUs;
      return this;
    }

    /**
     * Sets the {@link MediaItem} frame rate in the output video, in frames per second.
     *
     * <p>This should be set for inputs that don't have an intrinsic frame rate (e.g., images). It
     * will be ignored for inputs that do have an intrinsic frame rate (e.g., video).
     *
     * <p>For images, the frame rate depends on factors such as desired look, output format
     * requirement, and whether the content is static or dynamic (e.g., animation). However, 30 fps
     * is suitable for most use cases.
     *
     * <p>No frame rate is set by default.
     *
     * @param frameRate The frame rate, in frames per second.
     * @return This builder.
     */
    // TODO: b/210593170 - Remove/deprecate frameRate parameter when frameRate parameter is added to
    //  transformer.
    @CanIgnoreReturnValue
    public Builder setFrameRate(@IntRange(from = 0) int frameRate) {
      checkArgument(frameRate > 0);
      this.frameRate = frameRate;
      return this;
    }

    /**
     * Sets the {@link Effects} to apply to the {@link MediaItem}.
     *
     * <p>Callers should not interact with underlying {@link Effects#audioProcessors}.
     *
     * <p>The default value is {@link Effects#EMPTY}.
     *
     * @param effects The {@link Effects} to apply.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEffects(Effects effects) {
      this.effects = effects;
      return this;
    }

    /**
     * Sets a {@link SpeedProvider} to control the presentation speed of the {@link
     * EditedMediaItem}.
     *
     * <p>If a {@link SpeedProvider} is set, speed changing {@linkplain #setEffects effects} are not
     * allowed.
     *
     * <p>The default value is {@link SpeedProvider#DEFAULT}, which represents an unmodified speed.
     *
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSpeed(SpeedProvider provider) {
      this.speedProvider = provider;
      return this;
    }

    /** Builds an {@link EditedMediaItem} instance. */
    public EditedMediaItem build() {
      return new EditedMediaItem(this);
    }

    /**
     * Sets the {@link MediaItem} on which transformations are applied.
     *
     * @param mediaItem The {@link MediaItem}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    /* package */ Builder setMediaItem(MediaItem mediaItem) {
      this.mediaItem = mediaItem;
      return this;
    }

    /**
     * Sets the provided {@link SpeedChangingAudioProcessor} and {@link TimestampAdjustment} as the
     * first elements of their respective pipelines.
     *
     * <p>The effects' {@link SpeedProvider} must match the one set by {@link
     * #setSpeed(SpeedProvider)}.
     *
     * @return This builder.
     */
    @CanIgnoreReturnValue
    /* package */ Builder setSpeedChangingEffects(
        SpeedChangingAudioProcessor processor, @Nullable TimestampAdjustment effect) {
      checkArgument(effect == null || processor.getSpeedProvider() == effect.speedProvider);
      this.allowMatchingSpeedChangingEffectForSpeedProvider = true;
      ImmutableList<AudioProcessor> audioProcessors =
          new ImmutableList.Builder<AudioProcessor>()
              .add(processor)
              .addAll(effects.audioProcessors)
              .build();
      ImmutableList<Effect> videoEffects =
          effect == null
              ? effects.videoEffects
              : new ImmutableList.Builder<Effect>()
                  .add(effect)
                  .addAll(effects.videoEffects)
                  .build();
      this.effects = new Effects(audioProcessors, videoEffects);
      return this;
    }
  }

  /** The {@link MediaItem} on which transformations are applied. */
  public final MediaItem mediaItem;

  /** Whether to remove the audio from the {@link #mediaItem}. */
  public final boolean removeAudio;

  /** Whether to remove the video from the {@link #mediaItem}. */
  public final boolean removeVideo;

  /**
   * Whether to flatten the {@link #mediaItem} if it contains slow motion markers.
   *
   * <p>The flattened output is obtained by removing the slow motion metadata and by actually
   * slowing down the parts of the video and audio streams defined in this metadata.
   *
   * <p>Only Samsung Extension Format (SEF) slow motion metadata type is supported. Flattening has
   * no effect if the input does not contain this metadata type.
   *
   * <p>For SEF slow motion media, the following assumptions are made on the input:
   *
   * <ul>
   *   <li>The input container format is (unfragmented) MP4.
   *   <li>The input contains an AVC video elementary stream with temporal SVC.
   *   <li>The recording frame rate of the video is 120 or 240 fps.
   * </ul>
   */
  public final boolean flattenForSlowMotion;

  /**
   * The duration of the image in the output video for image {@link MediaItem}, or the media
   * duration for other types of {@link MediaItem}, in microseconds.
   */
  // TODO: b/309767764 - Consider merging with presentationDurationUs.
  public final long durationUs;

  /** The frame rate of the image in the output video, in frames per second. */
  @IntRange(from = 1)
  public final int frameRate;

  /** The {@link Effects} to apply to the {@link #mediaItem}. */
  public final Effects effects;

  public final SpeedProvider speedProvider;

  private final boolean allowMatchingSpeedChangingEffectForSpeedProvider;

  /** The duration for which this {@code EditedMediaItem} should be presented, in microseconds. */
  private long presentationDurationUs;

  private EditedMediaItem(Builder builder) {
    checkState(
        !builder.removeAudio || !builder.removeVideo, "Audio and video cannot both be removed");
    if (isGap(builder.mediaItem)) {
      checkArgument(builder.durationUs != C.TIME_UNSET);
      checkArgument(!builder.removeAudio);
      checkArgument(!builder.flattenForSlowMotion);
      checkArgument(builder.effects.audioProcessors.isEmpty());
      checkArgument(builder.speedProvider == SpeedProvider.DEFAULT);
    }

    if (builder.speedProvider != SpeedProvider.DEFAULT) {
      if (builder.allowMatchingSpeedChangingEffectForSpeedProvider) {
        checkState(validateSpeedChangingEffects(builder.effects, builder.speedProvider));
        checkState(!containsSpeedChangingEffects(builder.effects, /* ignoreFirstEffect= */ true));
      } else {
        checkState(!containsSpeedChangingEffects(builder.effects, /* ignoreFirstEffect= */ false));
      }
    }

    this.mediaItem = builder.mediaItem;
    this.removeAudio = builder.removeAudio;
    this.removeVideo = builder.removeVideo;
    this.flattenForSlowMotion = builder.flattenForSlowMotion;
    this.durationUs = builder.durationUs;
    this.frameRate = builder.frameRate;
    this.effects = builder.effects;
    this.speedProvider = builder.speedProvider;
    this.allowMatchingSpeedChangingEffectForSpeedProvider =
        builder.allowMatchingSpeedChangingEffectForSpeedProvider;
    presentationDurationUs = C.TIME_UNSET;
  }

  @Override
  public String toString() {
    return toJsonObject().toString();
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /* package */ long getPresentationDurationUs() {
    if (presentationDurationUs == C.TIME_UNSET) {
      if (mediaItem.clippingConfiguration.equals(MediaItem.ClippingConfiguration.UNSET)
          || durationUs == C.TIME_UNSET) {
        // TODO: b/290734981 - Use presentationDurationUs for image presentation
        presentationDurationUs = durationUs;
      } else {
        MediaItem.ClippingConfiguration clippingConfiguration = mediaItem.clippingConfiguration;
        checkArgument(!clippingConfiguration.relativeToDefaultPosition);
        if (clippingConfiguration.endPositionUs == C.TIME_END_OF_SOURCE) {
          presentationDurationUs = durationUs - clippingConfiguration.startPositionUs;
        } else {
          checkArgument(clippingConfiguration.endPositionUs <= durationUs);
          presentationDurationUs =
              clippingConfiguration.endPositionUs - clippingConfiguration.startPositionUs;
        }
      }
      presentationDurationUs = getDurationAfterEffectsApplied(presentationDurationUs);
    }
    return presentationDurationUs;
  }

  /** Returns a {@link JSONObject} that represents the {@code EditedMediaItem}. */
  /* package */ JSONObject toJsonObject() {
    JSONObject jsonObject = new JSONObject();
    try {
      jsonObject.put("mediaItem", jsonObjectFrom(mediaItem));
      jsonObject.put("effects", effects.toJsonObject());
      jsonObject.put("removeAudio", removeAudio);
      jsonObject.put("removeVideo", removeVideo);
      jsonObject.put("durationUs", durationUs);
      jsonObject.put("presentationDuration", getPresentationDurationUs());
    } catch (JSONException e) {
      Log.w(/* tag= */ "EditedMediaItem", "JSON conversion failed.", e);
      return new JSONObject();
    }
    return jsonObject;
  }

  /**
   * Returns the adjusted duration in microseconds after processing {@code durationUs} input with
   * the {@link EditedMediaItem}'s {@link Effects}.
   *
   * <p>If the audio and video durations do not match, the method returns the maximum duration.
   *
   * @param durationUs The input duration in microseconds.
   */
  /* package */ long getDurationAfterEffectsApplied(long durationUs) {
    if (speedProvider != SpeedProvider.DEFAULT) {
      return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(speedProvider, durationUs);
    }

    long audioDurationUs = durationUs;
    long videoDurationUs = durationUs;
    if (removeAudio) {
      audioDurationUs = C.TIME_UNSET;
    } else {
      for (AudioProcessor audioProcessor : effects.audioProcessors) {
        audioDurationUs = audioProcessor.getDurationAfterProcessorApplied(audioDurationUs);
      }
    }
    if (removeVideo) {
      videoDurationUs = C.TIME_UNSET;
    } else {
      for (Effect videoEffect : effects.videoEffects) {
        videoDurationUs = videoEffect.getDurationAfterEffectApplied(videoDurationUs);
      }
    }
    return max(audioDurationUs, videoDurationUs);
  }

  /**
   * Returns whether this {@code EditedMediaItem} is a {@linkplain
   * EditedMediaItemSequence.Builder#addGap(long) gap}.
   */
  /* package */ boolean isGap() {
    return isGap(mediaItem);
  }

  private static boolean isGap(MediaItem mediaItem) {
    return Objects.equals(mediaItem.mediaId, GAP_MEDIA_ID);
  }

  private static JSONObject jsonObjectFrom(MediaItem mediaItem) throws JSONException {
    JSONObject jsonObject = new JSONObject();
    String extension = "UNSET";
    if (mediaItem.localConfiguration != null) {
      String uri = checkNotNull(mediaItem.localConfiguration).uri.toString();
      int dotIndex = uri.lastIndexOf('.');
      if (dotIndex > 0 && dotIndex < uri.length() - 1) {
        extension = uri.substring(dotIndex + 1);
      }
    }
    jsonObject.put("extension", extension);

    if (mediaItem.clippingConfiguration.equals(MediaItem.ClippingConfiguration.UNSET)) {
      jsonObject.put("clipping", "UNSET");
      return jsonObject;
    }

    MediaItem.ClippingConfiguration clippingConfiguration = mediaItem.clippingConfiguration;
    String endPositionValue;
    if (clippingConfiguration.endPositionMs == C.TIME_END_OF_SOURCE) {
      endPositionValue = "END_OF_SOURCE";
    } else {
      endPositionValue = String.valueOf(clippingConfiguration.endPositionMs);
    }
    jsonObject.put("clippingStartMs", clippingConfiguration.startPositionMs);
    jsonObject.put("clippingEndMs", endPositionValue);
    return jsonObject;
  }
}
