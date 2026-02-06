/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_SRGB;
import static androidx.media3.test.utils.AssetInfo.MP4_VIDEO_ONLY_ASSET;
import static androidx.media3.test.utils.AssetInfo.PNG_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static com.google.common.base.Preconditions.checkNotNull;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.test.utils.AssetInfo;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Information about an {@link EditedMediaItem} and its associated {@link AssetInfo}. */
/* package */ final class EditedMediaItemAssetInfo {

  /** A builder for {@link EditedMediaItemAssetInfo} instances. */
  static final class Builder {
    private @MonotonicNonNull AssetInfo originalAssetInfo;
    private @MonotonicNonNull EditedMediaItem editedMediaItem;
    private @MonotonicNonNull String name;
    @Nullable private ImmutableList<Long> videoTimestampsUs;

    public Builder() {}

    /** Sets the {@link AssetInfo} of the original source. */
    @CanIgnoreReturnValue
    public Builder setOriginalAssetInfo(AssetInfo originalAssetInfo) {
      this.originalAssetInfo = originalAssetInfo;
      return this;
    }

    /** Sets the {@link EditedMediaItem} containing the transformations to apply. */
    @CanIgnoreReturnValue
    public Builder setEditedMediaItem(EditedMediaItem editedMediaItem) {
      this.editedMediaItem = editedMediaItem;
      return this;
    }

    /** Sets the expected output video timestamps in microseconds. */
    @CanIgnoreReturnValue
    public Builder setVideoTimestampsUs(Collection<Long> videoTimestampsUs) {
      this.videoTimestampsUs = ImmutableList.copyOf(videoTimestampsUs);
      return this;
    }

    /** Sets a descriptive name for the edited asset. */
    @CanIgnoreReturnValue
    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    /** Creates an {@link EditedMediaItemAssetInfo} instance. */
    public EditedMediaItemAssetInfo build() {
      checkNotNull(originalAssetInfo);
      checkNotNull(editedMediaItem);
      checkNotNull(name);
      return new EditedMediaItemAssetInfo(this);
    }
  }

  /** Constant effects for a 0.5x speed change. */
  public static final Pair<AudioProcessor, Effect> HALF_SPEED_CHANGE_EFFECTS =
      Effects.createExperimentalSpeedChangingEffect(
          new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
              return 0.5f;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
              return C.TIME_UNSET;
            }
          });

  /** Constant effects for a 2.0x speed change. */
  public static final Pair<AudioProcessor, Effect> TWICE_SPEED_CHANGE_EFFECTS =
      Effects.createExperimentalSpeedChangingEffect(
          new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
              return 2f;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
              return C.TIME_UNSET;
            }
          });

  /** An {@link EditedMediaItemAssetInfo} for a static image with 500ms duration. */
  public static final EditedMediaItemAssetInfo IMAGE =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(PNG_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(
                      new MediaItem.Builder()
                          .setUri(PNG_ASSET.uri)
                          .setImageDurationMs(usToMs(/* timeUs= */ 500_000))
                          .build())
                  .setDurationUs(500_000)
                  .setFrameRate(30)
                  .build())
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L, 200_000L, 233_333L, 266_667L,
                  300_000L, 333_333L, 366_667L, 400_000L, 433_333L, 466_667L))
          .setName("Image")
          .build();

  /** An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_ASSET}. */
  public static final EditedMediaItemAssetInfo VIDEO =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(new MediaItem.Builder().setUri(MP4_ASSET.uri).build())
                  .setDurationUs(MP4_ASSET.videoDurationUs)
                  .build())
          .setName("Video")
          .build();

  /** An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_ASSET_SRGB}. */
  public static final EditedMediaItemAssetInfo VIDEO_SRGB =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_ASSET_SRGB)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(
                      new MediaItem.Builder().setUri(MP4_ASSET_SRGB.uri).build())
                  .setDurationUs(MP4_ASSET_SRGB.videoDurationUs)
                  .build())
          .setName("Video_srgb")
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_ASSET} with the audio
   * track removed.
   */
  public static final EditedMediaItemAssetInfo VIDEO_WITHOUT_AUDIO =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                  .setDurationUs(MP4_ASSET.videoDurationUs)
                  .setRemoveAudio(true)
                  .build())
          .setName("Video_no_audio")
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_VIDEO_ONLY_ASSET}
   * clipped to start at 500ms.
   */
  private static final MediaItem VIDEO_ONLY_CLIPPED_MEDIA_ITEM =
      MediaItem.fromUri(MP4_VIDEO_ONLY_ASSET.uri)
          .buildUpon()
          .setClippingConfiguration(
              new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(500).build())
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_VIDEO_ONLY_ASSET}
   * clipped to start at 500ms and speed adjusted x2.
   */
  public static final EditedMediaItemAssetInfo VIDEO_ONLY_CLIPPED_TWICE_SPEED =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_VIDEO_ONLY_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(VIDEO_ONLY_CLIPPED_MEDIA_ITEM)
                  .setDurationUs(MP4_VIDEO_ONLY_ASSET.videoDurationUs)
                  .setRemoveAudio(true)
                  .setEffects(
                      new Effects(
                          /* audioProcessors= */ ImmutableList.of(),
                          /* videoEffects= */ ImmutableList.of(TWICE_SPEED_CHANGE_EFFECTS.second)))
                  .build())
          .setVideoTimestampsUs(
              ImmutableList.of(
                  250L, 16933L, 33617L, 50300L, 66983L, 83667L, 100350L, 117033L, 133717L, 150400L,
                  167083L, 183767L, 200450L, 217133L, 233817L))
          .setName("Video_only_clipped_twice_speed")
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_VIDEO_ONLY_ASSET}
   * clipped to start at 500ms and speed adjusted x0.5.
   */
  public static final EditedMediaItemAssetInfo VIDEO_ONLY_CLIPPED_HALF_SPEED =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_VIDEO_ONLY_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(VIDEO_ONLY_CLIPPED_MEDIA_ITEM)
                  .setDurationUs(MP4_VIDEO_ONLY_ASSET.videoDurationUs)
                  .setRemoveAudio(true)
                  .setEffects(
                      new Effects(
                          /* audioProcessors= */ ImmutableList.of(),
                          /* videoEffects= */ ImmutableList.of(HALF_SPEED_CHANGE_EFFECTS.second)))
                  .build())
          .setVideoTimestampsUs(
              ImmutableList.of(
                  1000L, 67732L, 134466L, 201200L, 267932L, 334666L, 401400L, 468132L, 534866L,
                  601600L, 668332L, 735066L, 801800L, 868532L, 935266L))
          .setName("Video_only_clipped_half_speed")
          .build();

  /** An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#WAV_ASSET}. */
  public static final EditedMediaItemAssetInfo AUDIO =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(WAV_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
                  .setDurationUs(1_000_000)
                  .build())
          .setVideoTimestampsUs(ImmutableList.of())
          .setName("Audio")
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#WAV_ASSET} with video
   * timestamps.
   */
  public static final EditedMediaItemAssetInfo AUDIO_WITH_VIDEO_TIMESTAMPS =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(WAV_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
                  .setDurationUs(1_000_000)
                  .build())
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L, 200_000L, 233_333L, 266_667L,
                  300_000L, 333_333L, 366_667L, 400_000L, 433_333L, 466_667L, 500_000L, 533_333L,
                  566_667L, 600_000L, 633_333L, 666_667L, 700_000L, 733_333L, 766_667L, 800_000L,
                  833_333L, 866_667L, 900_000L, 933_333L, 966_667L))
          .setName("Audio_with_video_gap")
          .build();

  /**
   * An {@link EditedMediaItemAssetInfo} wrapper around {@link AssetInfo#MP4_ASSET} with the video
   * track removed.
   */
  public static final EditedMediaItemAssetInfo VIDEO_WITH_REMOVE_VIDEO =
      new EditedMediaItemAssetInfo.Builder()
          .setOriginalAssetInfo(MP4_ASSET)
          .setEditedMediaItem(
              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                  .setDurationUs(MP4_ASSET.videoDurationUs)
                  .setRemoveVideo(true)
                  .build())
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L,
                  33_333L,
                  66_667L,
                  100_000L,
                  133_333L,
                  166_667L,
                  200_000L,
                  233_333L,
                  266_667L,
                  300_000L,
                  333_333L,
                  366_667L,
                  400_000L,
                  433_333L,
                  466_667L,
                  500_000L,
                  533_333L,
                  566_667L,
                  600_000L,
                  633_333L,
                  666_667L,
                  700_000L,
                  733_333L,
                  766_667L,
                  800_000L,
                  833_333L,
                  866_667L,
                  900_000L,
                  933_333L,
                  966_667L,
                  1_000_000L))
          .setName("Video_with_remove_video_set")
          .build();

  /** The {@link EditedMediaItem} to be processed. */
  public final EditedMediaItem editedMediaItem;

  /** Expected output video timestamps in microseconds. */
  public final ImmutableList<Long> videoTimestampsUs;

  /** The {@link Format} of the video, or {@code null} if not applicable. */
  @Nullable public final Format videoFormat;

  /** Descriptive name of the asset configuration. */
  private final String name;

  private EditedMediaItemAssetInfo(Builder builder) {
    this.editedMediaItem = builder.editedMediaItem;
    this.videoTimestampsUs =
        builder.videoTimestampsUs != null
            ? builder.videoTimestampsUs
            : builder.originalAssetInfo.videoTimestampsUs;
    this.name = builder.name;
    this.videoFormat = builder.originalAssetInfo.videoFormat;
  }

  @Override
  public String toString() {
    return name;
  }
}
