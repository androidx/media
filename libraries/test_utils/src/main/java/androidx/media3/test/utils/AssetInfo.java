/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.common.MimeTypes.IMAGE_JPEG;
import static androidx.media3.common.MimeTypes.IMAGE_PNG;
import static androidx.media3.common.MimeTypes.IMAGE_WEBP;
import static androidx.media3.common.MimeTypes.VIDEO_AV1;
import static androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.common.MimeTypes.VIDEO_H265;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Immutable;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Information about a test asset. */
@Immutable
@UnstableApi
public final class AssetInfo {
  private static final class Builder {
    private final String uri;
    private int trackCount;
    private @MonotonicNonNull Format videoFormat;
    private int videoFrameCount;
    private int audioSampleCount;
    private long videoDurationUs;
    private long audioDurationUs;
    private @MonotonicNonNull ImmutableList<Long> videoTimestampsUs;

    public Builder(String uri) {
      this.uri = uri;
      trackCount = C.LENGTH_UNSET;
      videoFrameCount = C.LENGTH_UNSET;
      audioSampleCount = C.LENGTH_UNSET;
      videoDurationUs = C.TIME_UNSET;
      audioDurationUs = C.TIME_UNSET;
    }

    /** See {@link AssetInfo#trackCount}. */
    @CanIgnoreReturnValue
    public Builder setTrackCount(int trackCount) {
      this.trackCount = trackCount;
      return this;
    }

    /** See {@link AssetInfo#videoFormat}. */
    @CanIgnoreReturnValue
    public Builder setVideoFormat(Format format) {
      this.videoFormat = format;
      return this;
    }

    /** See {@link AssetInfo#videoFrameCount}. */
    @CanIgnoreReturnValue
    public Builder setVideoFrameCount(int frameCount) {
      // Frame count can be found using the following command for a given file:
      // ffprobe -count_frames -select_streams v:0 -show_entries stream=nb_read_frames <file>
      this.videoFrameCount = frameCount;
      return this;
    }

    /** See {@link AssetInfo#audioSampleCount}. */
    @CanIgnoreReturnValue
    public Builder setAudioSampleCount(int audioSampleCount) {
      this.audioSampleCount = audioSampleCount;
      return this;
    }

    /** See {@link AssetInfo#videoDurationUs}. */
    @CanIgnoreReturnValue
    public Builder setVideoDurationUs(long durationUs) {
      this.videoDurationUs = durationUs;
      return this;
    }

    /** See {@link AssetInfo#audioDurationUs}. */
    @CanIgnoreReturnValue
    public Builder setAudioDurationUs(long durationUs) {
      this.audioDurationUs = durationUs;
      return this;
    }

    /** See {@link AssetInfo#videoTimestampsUs}. */
    @CanIgnoreReturnValue
    public Builder setVideoTimestampsUs(ImmutableList<Long> videoTimestampsUs) {
      this.videoTimestampsUs = videoTimestampsUs;
      return this;
    }

    /** Creates an {@link AssetInfo}. */
    public AssetInfo build() {
      if (videoTimestampsUs != null) {
        checkState(
            videoFrameCount == C.LENGTH_UNSET || videoFrameCount == videoTimestampsUs.size());
        videoFrameCount = videoTimestampsUs.size();
      }
      return new AssetInfo(
          uri,
          trackCount,
          videoFormat,
          videoDurationUs,
          audioDurationUs,
          videoFrameCount,
          audioSampleCount,
          videoTimestampsUs);
    }
  }

  public static final AssetInfo PNG_ASSET =
      new AssetInfo.Builder("asset:///media/png/media3test.png")
          .setVideoFormat(
              new Format.Builder().setSampleMimeType(IMAGE_PNG).setWidth(304).setHeight(84).build())
          .build();

  public static final AssetInfo PNG_ASSET_LINES_1080P =
      new AssetInfo.Builder("asset:///media/png/loremipsum_1920x720.png")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(IMAGE_PNG)
                  .setWidth(1920)
                  .setHeight(720)
                  .build())
          .build();

  public static final AssetInfo JPG_ASSET =
      new AssetInfo.Builder("asset:///media/jpeg/london.jpg")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(IMAGE_JPEG)
                  .setWidth(1020)
                  .setHeight(768)
                  .build())
          .build();

  public static final AssetInfo JPG_PORTRAIT_ASSET =
      new AssetInfo.Builder("asset:///media/jpeg/tokyo.jpg")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(IMAGE_JPEG)
                  .setWidth(600)
                  .setHeight(800)
                  .build())
          .build();

  public static final AssetInfo JPG_SINGLE_PIXEL_ASSET =
      new AssetInfo.Builder("asset:///media/jpeg/white-1x1.jpg")
          .setVideoFormat(
              new Format.Builder().setSampleMimeType(IMAGE_JPEG).setWidth(1).setHeight(1).build())
          .build();

  public static final AssetInfo JPG_ULTRA_HDR_ASSET =
      new AssetInfo.Builder("asset:///media/jpeg/ultraHDR.jpg")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(IMAGE_JPEG)
                  .setWidth(3072)
                  .setHeight(4080)
                  .build())
          .build();

  public static final AssetInfo JPG_PIXEL_MOTION_PHOTO_ASSET =
      new AssetInfo.Builder("asset:///media/jpeg/pixel-motion-photo-2-hevc-tracks.jpg")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(1024)
                  .setHeight(768)
                  .setFrameRate(27.61f)
                  .setCodecs("hvc1.1.6.L153")
                  .build())
          .setVideoFrameCount(58)
          .build();

  public static final AssetInfo WEBP_LARGE =
      new AssetInfo.Builder("asset:///media/webp/black_large.webp")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(IMAGE_WEBP)
                  .setWidth(16000)
                  .setHeight(9000)
                  .build())
          .build();

  public static final AssetInfo MP4_TRIM_OPTIMIZATION =
      new AssetInfo.Builder("asset:///media/mp4/internal_emulator_transformer_output.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .build())
          .build();

  /** This file contains an edit lists that adds one second to all video frames. */
  public static final AssetInfo MP4_POSITIVE_SHIFT_EDIT_LIST =
      new AssetInfo.Builder("asset:///media/mp4/edit_list_positive_shift.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.f)
                  .build())
          .build();

  /**
   * An MP4 file containing an edit list that makes its only sync sample a preroll sample (i.e.,
   * have a negative presentation timestamp).
   */
  public static final AssetInfo MP4_ONLY_PREROLL_SYNC_SAMPLE_EDIT_LIST =
      new AssetInfo.Builder("asset:///media/mp4/sample_edit_list_only_preroll_sync_sample.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(640)
                  .setHeight(360)
                  .setFrameRate(30.0f)
                  .build())
          .build();

  /**
   * This file has been edited to show a visual stopwatch to make it easier to know when frames were
   * presented in the original video.
   */
  public static final AssetInfo MP4_VISUAL_TIMESTAMPS =
      new AssetInfo.Builder(
              "asset:///media/mp4/internal_emulator_transformer_output_visual_timestamps.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .build())
          .build();

  public static final AssetInfo MP4_TRIM_OPTIMIZATION_270 =
      new AssetInfo.Builder(
              "asset:///media/mp4/internal_emulator_transformer_output_270_rotated.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setRotationDegrees(270)
                  .build())
          .build();

  public static final AssetInfo MP4_TRIM_OPTIMIZATION_180 =
      new AssetInfo.Builder(
              "asset:///media/mp4/internal_emulator_transformer_output_180_rotated.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setRotationDegrees(180)
                  .build())
          .build();

  public static final AssetInfo MP4_TRIM_OPTIMIZATION_PIXEL =
      new AssetInfo.Builder("asset:///media/mp4/pixel7_videoOnly_cleaned.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(29.871f)
                  .setRotationDegrees(180)
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/sample.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .build())
          .setTrackCount(2)
          .setVideoDurationUs(1_024_000L)
          .setVideoFrameCount(30)
          .setAudioSampleCount(45)
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_366L, 66_733L, 100_100L, 133_466L, 166_833L, 200_200L, 233_566L, 266_933L,
                  300_300L, 333_666L, 367_033L, 400_400L, 433_766L, 467_133L, 500_500L, 533_866L,
                  567_233L, 600_600L, 633_966L, 667_333L, 700_700L, 734_066L, 767_433L, 800_800L,
                  834_166L, 867_533L, 900_900L, 934_266L, 967_633L))
          .build();

  public static final AssetInfo MP4_VIDEO_ONLY_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/sample_video_only.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .build())
          // This is slightly different from sample.mp4
          .setVideoDurationUs(1_001_000L)
          .setVideoFrameCount(30)
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_366L, 66_733L, 100_100L, 133_466L, 166_833L, 200_200L, 233_566L, 266_933L,
                  300_300L, 333_666L, 367_033L, 400_400L, 433_766L, 467_133L, 500_500L, 533_866L,
                  567_233L, 600_600L, 633_966L, 667_333L, 700_700L, 734_066L, 767_433L, 800_800L,
                  834_166L, 867_533L, 900_900L, 934_266L, 967_633L))
          .build();

  public static final AssetInfo MP4_ASSET_SRGB =
      new AssetInfo.Builder("asset:///media/mp4/sample_srgb.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorSpace(C.COLOR_SPACE_BT709)
                          .setColorTransfer(C.COLOR_TRANSFER_SRGB)
                          .build())
                  .build())
          .setVideoDurationUs(1_024_000L)
          .setVideoFrameCount(30)
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_366L, 66_733L, 100_100L, 133_466L, 166_833L, 200_200L, 233_566L, 266_933L,
                  300_300L, 333_666L, 367_033L, 400_400L, 433_766L, 467_133L, 500_500L, 533_866L,
                  567_233L, 600_600L, 633_966L, 667_333L, 700_700L, 734_066L, 767_433L, 800_800L,
                  834_166L, 867_533L, 900_900L, 934_266L, 967_633L))
          .build();

  public static final AssetInfo MOV_WITH_PCM_AUDIO =
      new AssetInfo.Builder("asset:///media/mp4/sowt-with-video.mov")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(50f)
                  .setCodecs("avc1.64002A")
                  .build())
          .build();

  public static final AssetInfo BT601_MOV_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bt601.mov")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(640)
                  .setHeight(428)
                  .setFrameRate(29.97f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT601)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_SDR)
                          .build())
                  .setCodecs("avc1.4D001E")
                  .build())
          .build();

  public static final AssetInfo BT601_MP4_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bt601.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(360)
                  .setHeight(240)
                  .setFrameRate(29.97f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT601)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_SDR)
                          .build())
                  .setCodecs("avc1.42C00D")
                  .build())
          .setVideoFrameCount(30)
          .build();

  public static final AssetInfo MP4_PORTRAIT_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/sample_portrait.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(720)
                  .setHeight(1080)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_H264_1080P_10SEC_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/h264_1080p_30fps_10sec.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(30.0f)
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_H264_4K_10SEC_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/h264_4k_30fps_10sec.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setFrameRate(30.0f)
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_AV1_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/sample_av1.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_AV1)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(30.0f)
                  .build())
          .setTrackCount(2)
          .setVideoFrameCount(30)
          .setAudioSampleCount(45)
          .build();

  public static final AssetInfo MP4_ASSET_CHECKERBOARD_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/checkerboard_854x356_avc_baseline.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(854)
                  .setHeight(356)
                  .setFrameRate(25.0f)
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_WITH_INCREASING_TIMESTAMPS =
      new AssetInfo.Builder("asset:///media/mp4/sample_with_increasing_timestamps.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.00f)
                  .setCodecs("avc1.42C033")
                  .build())
          .setTrackCount(2)
          .setVideoDurationUs(1_000_000L)
          .setVideoFrameCount(30)
          .setAudioSampleCount(47)
          .build();

  public static final AssetInfo MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS =
      new AssetInfo.Builder("asset:///media/mp4/long_1080p_videoonly_lowbitrate.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.00f)
                  .setCodecs("avc1.42C028")
                  .build())
          .build();

  public static final AssetInfo MP4_LONG_ASSET_WITH_AUDIO_AND_INCREASING_TIMESTAMPS =
      new AssetInfo.Builder("asset:///media/mp4/long_1080p_lowbitrate.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.00f)
                  .setCodecs("avc1.42C028")
                  .build())
          .build();

  /** Baseline profile level 3.0 H.264 stream, which should be supported on all devices. */
  public static final AssetInfo MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S =
      new AssetInfo.Builder("asset:///media/mp4/sample_with_increasing_timestamps_320w_240h.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(320)
                  .setHeight(240)
                  .setFrameRate(60.00f)
                  .setCodecs("avc1.42C015")
                  .build())
          .setVideoFrameCount(932)
          .build();

  /** Baseline profile level 3.0 H.264 stream, which should be supported on all devices. */
  public static final AssetInfo MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S =
      new AssetInfo.Builder("asset:///media/mp4/sample_with_increasing_timestamps_320w_240h_5s.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(320)
                  .setHeight(240)
                  .setFrameRate(60.00f)
                  .setCodecs("avc1.42C015")
                  .build())
          .setVideoFrameCount(300)
          .setVideoDurationUs(5_019_000L)
          .build();

  /** Baseline profile level 3.0 H.264 stream, which should be supported on all devices. */
  public static final AssetInfo MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_GAMMA22_1S =
      new AssetInfo.Builder("asset:///media/mp4/sample_gamma2.2.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(320)
                  .setHeight(240)
                  .setFrameRate(60.00f)
                  .setCodecs("avc1.42C015")
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorRange(C.COLOR_RANGE_FULL)
                          .setColorSpace(C.COLOR_SPACE_BT709)
                          .setColorTransfer(C.COLOR_TRANSFER_GAMMA_2_2)
                          .build())
                  .build())
          .setVideoFrameCount(60)
          .setVideoDurationUs(1_009_000L)
          .build();

  public static final AssetInfo MP4_ASSET_WITH_SHORTER_AUDIO =
      new AssetInfo.Builder("asset:///media/mp4/sample_shorter_audio.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(320)
                  .setHeight(240)
                  .setFrameRate(30.00f)
                  .setCodecs("avc1.42C015")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_SEF =
      new AssetInfo.Builder("asset:///media/mp4/sample_sef_slow_motion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(320)
                  .setHeight(240)
                  .setFrameRate(30.472f)
                  .setCodecs("avc1.64000D")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_SEF_H265 =
      new AssetInfo.Builder("asset:///media/mp4/sample_sef_slow_motion_hevc.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.01679f)
                  .setCodecs("hvc1.1.6.L120.B0")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_BT2020_SDR =
      new AssetInfo.Builder("asset:///media/mp4/bt2020-sdr.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setFrameRate(29.822f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_SDR)
                          .build())
                  .setCodecs("avc1.640033")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_1080P_5_SECOND_HLG10 =
      new AssetInfo.Builder("asset:///media/mp4/hlg-1080p.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.000f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_HLG)
                          .build())
                  .setCodecs("hvc1.2.4.L153")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_COLOR_TEST_1080P_HLG10 =
      new AssetInfo.Builder("asset:///media/mp4/hlg10-color-test.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setFrameRate(30.000f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_HLG)
                          .build())
                  .setCodecs("hvc1.2.4.L153")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_720P_4_SECOND_HDR10 =
      new AssetInfo.Builder("asset:///media/mp4/hdr10-720p.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                          .build())
                  .setCodecs("hvc1.2.4.L153")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_AV1_2_SECOND_HDR10 =
      new AssetInfo.Builder("asset:///media/mp4/hdr10-av1.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_AV1)
                  .setWidth(720)
                  .setHeight(1280)
                  .setFrameRate(59.94f)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                          .build())
                  .build())
          .build();

  // This file needs alternative MIME type, meaning the decoder needs to be configured with
  // video/hevc instead of video/dolby-vision.
  public static final AssetInfo MP4_ASSET_DOLBY_VISION_HDR =
      new AssetInfo.Builder("asset:///media/mp4/dolbyVision-hdr.MOV")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_DOLBY_VISION)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(30.00f)
                  .setCodecs("dvhe.08.02")
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorTransfer(C.COLOR_TRANSFER_HLG)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .build())
                  .build())
          .setTrackCount(3)
          .setVideoFrameCount(5)
          .setAudioSampleCount(7)
          .build();

  public static final AssetInfo MP4_ASSET_4K60_PORTRAIT =
      new AssetInfo.Builder("asset:///media/mp4/portrait_4k60.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setFrameRate(60.00f)
                  .setCodecs("avc1.640033")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_10_SECONDS =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/android-screens-10s.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  /** Test clip transcoded from {@linkplain #MP4_REMOTE_10_SECONDS with H264 and MP3}. */
  public static final AssetInfo MP4_REMOTE_H264_MP3 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/%20android-screens-10s-h264-mp3.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_ASSET_8K24 =
      new AssetInfo.Builder("asset:///media/mp4/8k24fps_300ms.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(7680)
                  .setHeight(4320)
                  .setFrameRate(24.00f)
                  .setCodecs("hvc1.1.6.L183")
                  .build())
          .setTrackCount(2)
          .setVideoFrameCount(8)
          .setAudioSampleCount(15)
          .build();

  // From b/357743907.
  public static final AssetInfo MP4_ASSET_PHOTOS_TRIM_OPTIMIZATION_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/trim_optimization_failure.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(518)
                  .setHeight(488)
                  .setFrameRate(29.882f)
                  .setCodecs("avc1.640034")
                  .build())
          .build();

  // The 7 HIGHMOTION files are H264 and AAC.

  public static final AssetInfo MP4_REMOTE_1280W_720H_5_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/1280w_720h_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setAverageBitrate(8_939_000)
                  .setFrameRate(30.075f)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1440W_1440H_5_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/1440w_1440h_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1440)
                  .setHeight(1440)
                  .setAverageBitrate(17_000_000)
                  .setFrameRate(29.97f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1920W_1080H_5_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/1920w_1080h_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setAverageBitrate(17_100_000)
                  .setFrameRate(30.037f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_3840W_2160H_5_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/3840w_2160h_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setAverageBitrate(48_300_000)
                  .setFrameRate(30.090f)
                  .setCodecs("avc1.640033")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1280W_720H_30_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/1280w_720h_30s_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setAverageBitrate(9_962_000)
                  .setFrameRate(30.078f)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1920W_1080H_30_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/1920w_1080h_30s_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setAverageBitrate(15_000_000)
                  .setFrameRate(28.561f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_3840W_2160H_32_SECOND_HIGHMOTION =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/3840w_2160h_32s_highmotion.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setAverageBitrate(47_800_000)
                  .setFrameRate(28.414f)
                  .setCodecs("avc1.640033")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_256W_144H_30_SECOND_ROOF_ONEPLUSNORD2_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_downsampled_256w_144h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(256)
                  .setHeight(144)
                  .setFrameRate(30)
                  .setCodecs("avc1.64000C")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_426W_240H_30_SECOND_ROOF_ONEPLUSNORD2_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_downsampled_426w_240h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(426)
                  .setHeight(240)
                  .setFrameRate(30)
                  .setCodecs("avc1.640015")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_640W_360H_30_SECOND_ROOF_ONEPLUSNORD2_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_downsampled_640w_360h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(640)
                  .setHeight(360)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001E")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_854W_480H_30_SECOND_ROOF_ONEPLUSNORD2_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_downsampled_854w_480h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(854)
                  .setHeight(480)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_256W_144H_30_SECOND_ROOF_REDMINOTE9_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_downsampled_256w_144h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(256)
                  .setHeight(144)
                  .setFrameRate(30)
                  .setCodecs("avc1.64000C")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_426W_240H_30_SECOND_ROOF_REDMINOTE9_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_downsampled_426w_240h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(426)
                  .setHeight(240)
                  .setFrameRate(30)
                  .setCodecs("avc1.640015")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_640W_360H_30_SECOND_ROOF_REDMINOTE9_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_downsampled_640w_360h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(640)
                  .setHeight(360)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001E")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_854W_480H_30_SECOND_ROOF_REDMINOTE9_DOWNSAMPLED =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_downsampled_854w_480h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(854)
                  .setHeight(480)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_640W_480H_31_SECOND_ROOF_SONYXPERIAXZ3 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/SonyXperiaXZ3_640w_480h_31s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(640)
                  .setHeight(480)
                  .setAverageBitrate(3_578_000)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001E")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1280W_720H_30_SECOND_ROOF_ONEPLUSNORD2 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_1280w_720h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setAverageBitrate(8_966_000)
                  .setFrameRate(29.763f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1280W_720H_32_SECOND_ROOF_REDMINOTE9 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_1280w_720h_32s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1280)
                  .setHeight(720)
                  .setAverageBitrate(14_100_000)
                  .setFrameRate(30)
                  .setCodecs("avc1.64001F")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1440W_1440H_31_SECOND_ROOF_SAMSUNGS20ULTRA5G =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/SsS20Ultra5G_1440hw_31s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1440)
                  .setHeight(1440)
                  .setAverageBitrate(16_300_000)
                  .setFrameRate(25.931f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1920W_1080H_60_FPS_30_SECOND_ROOF_ONEPLUSNORD2 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_1920w_1080h_60fr_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setAverageBitrate(20_000_000)
                  .setFrameRate(59.94f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_1920W_1080H_60_FPS_30_SECOND_ROOF_REDMINOTE9 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_1920w_1080h_60fps_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(1920)
                  .setHeight(1080)
                  .setAverageBitrate(20_100_000)
                  .setFrameRate(61.069f)
                  .setCodecs("avc1.64002A")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_2400W_1080H_34_SECOND_ROOF_SAMSUNGS20ULTRA5G =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/SsS20Ultra5G_2400w_1080h_34s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(2400)
                  .setHeight(1080)
                  .setAverageBitrate(29_500_000)
                  .setFrameRate(27.472f)
                  .setCodecs("hvc1.2.4.L153.B0")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_3840W_2160H_30_SECOND_ROOF_ONEPLUSNORD2 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/OnePlusNord2_3840w_2160h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setAverageBitrate(49_800_000)
                  .setFrameRate(29.802f)
                  .setCodecs("avc1.640028")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_3840W_2160H_30_SECOND_ROOF_REDMINOTE9 =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/RedmiNote9_3840w_2160h_30s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H264)
                  .setWidth(3840)
                  .setHeight(2160)
                  .setAverageBitrate(42_100_000)
                  .setFrameRate(30)
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .setColorRange(C.COLOR_RANGE_FULL)
                          .setColorTransfer(C.COLOR_TRANSFER_SDR)
                          .build())
                  .setCodecs("avc1.640033")
                  .build())
          .build();

  public static final AssetInfo MP4_REMOTE_7680W_4320H_31_SECOND_ROOF_SAMSUNGS20ULTRA5G =
      new AssetInfo.Builder(
              "https://storage.googleapis.com/exoplayer-test-media-1/mp4/device_videos/SsS20Ultra5G_7680w_4320h_31s_roof.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_H265)
                  .setWidth(7680)
                  .setHeight(4320)
                  .setAverageBitrate(79_900_000)
                  .setFrameRate(23.163f)
                  .setCodecs("hvc1.1.6.L183.B0")
                  .build())
          .build();

  public static final AssetInfo MP3_ASSET =
      new AssetInfo.Builder("asset:///media/mp3/test-cbr-info-header.mp3").build();

  // This file contains 1 second of audio at 44.1kHZ.
  public static final AssetInfo WAV_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sample.wav").build();

  public static final AssetInfo WAV_96KHZ_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sample_96khz.wav").build();

  public static final AssetInfo WAV_192KHZ_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sample_192khz.wav").build();

  public static final AssetInfo WAV_80KHZ_MONO_20_REPEATING_1_SAMPLES_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sample_80KHz_mono_20_repeating_1_samples.wav")
          .build();

  public static final AssetInfo WAV_24LE_PCM_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sine_24le.wav")
          .setAudioSampleCount(44100)
          .setAudioDurationUs(1_000_000)
          .build();

  public static final AssetInfo WAV_32LE_PCM_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sine_32le.wav")
          .setAudioSampleCount(44100)
          .setAudioDurationUs(1_000_000)
          .build();

  public static final AssetInfo FLAC_STEREO_ASSET =
      new AssetInfo.Builder("asset:///media/flac/bear.flac").build();

  public static final AssetInfo AMR_NB_3GP_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bbb_mono_8kHz_12.2kbps_amrnb.3gp")
          .setTrackCount(1)
          .setAudioSampleCount(151)
          .build();

  public static final AssetInfo AMR_WB_3GP_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bbb_mono_16kHz_23.05kbps_amrwb.3gp")
          .setTrackCount(1)
          .setAudioSampleCount(150)
          .build();

  public static final AssetInfo H263_3GP_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bbb_176x144_128kbps_15fps_h263.3gp")
          .setTrackCount(1)
          .setVideoFrameCount(15)
          .build();

  public static final AssetInfo MPEG4_MP4_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bbb_176x144_192kbps_15fps_mpeg4.mp4")
          .setTrackCount(1)
          .setVideoFrameCount(15)
          .build();

  public static final AssetInfo VORBIS_OGG_ASSET =
      new AssetInfo.Builder("asset:///media/mp4/bbb_1ch_16kHz_q10_vorbis.ogg")
          .setTrackCount(1)
          .setAudioSampleCount(103)
          .build();

  /** Asset uri string. */
  public final String uri;

  /** Total number of tracks, or {@link C#LENGTH_UNSET}. */
  public final int trackCount;

  /** Video {@link Format}, or {@code null}. */
  // Format object is not deeply immutable but it is meant to be immutable.
  @SuppressWarnings("Immutable")
  @Nullable
  public final Format videoFormat;

  /** Video duration in microseconds, or {@link C#TIME_UNSET}. */
  public final long videoDurationUs;

  /** Audio duration in microseconds, or {@link C#TIME_UNSET}. */
  public final long audioDurationUs;

  /** Video frame count, or {@link C#LENGTH_UNSET}. */
  public final int videoFrameCount;

  /** Audio sample count, or {@link C#LENGTH_UNSET}. */
  public final int audioSampleCount;

  /** Video frame timestamps in microseconds, or {@code null}. */
  @Nullable public final ImmutableList<Long> videoTimestampsUs;

  private AssetInfo(
      String uri,
      int trackCount,
      @Nullable Format videoFormat,
      long videoDurationUs,
      long audioDurationUs,
      int videoFrameCount,
      int audioSampleCount,
      @Nullable ImmutableList<Long> videoTimestampsUs) {
    this.uri = uri;
    this.trackCount = trackCount;
    this.videoFormat = videoFormat;
    this.videoDurationUs = videoDurationUs;
    this.audioDurationUs = audioDurationUs;
    this.videoFrameCount = videoFrameCount;
    this.audioSampleCount = audioSampleCount;
    this.videoTimestampsUs = videoTimestampsUs;
  }

  @Override
  public String toString() {
    return "AssetInfo(" + uri + ")";
  }
}
