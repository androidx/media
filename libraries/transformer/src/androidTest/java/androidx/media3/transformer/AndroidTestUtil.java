/*
 * Copyright 2021 The Android Open Source Project
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

import static androidx.media3.common.MimeTypes.IMAGE_JPEG;
import static androidx.media3.common.MimeTypes.IMAGE_PNG;
import static androidx.media3.common.MimeTypes.IMAGE_WEBP;
import static androidx.media3.common.MimeTypes.VIDEO_AV1;
import static androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.common.MimeTypes.VIDEO_H265;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.SDK_INT;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.media.Image;
import android.media.MediaFormat;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.GlRect;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.Util;
import androidx.media3.effect.ByteBufferGlEffect;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.muxer.Muxer;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.media3.test.utils.VideoDecodingWrapper;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.AssumptionViolatedException;

/** Utilities for instrumentation tests. */
public final class AndroidTestUtil {
  private static final String TAG = "AndroidTestUtil";

  /** An {@link Effects} instance that forces video transcoding. */
  public static final Effects FORCE_TRANSCODE_VIDEO_EFFECTS =
      new Effects(
          /* audioProcessors= */ ImmutableList.of(),
          ImmutableList.of(
              new ScaleAndRotateTransformation.Builder().setRotationDegrees(45).build()));

  /** Information about a test asset. */
  public static final class AssetInfo {
    private static final class Builder {
      private final String uri;
      private @MonotonicNonNull Format videoFormat;
      private int videoFrameCount;
      private long videoDurationUs;
      private @MonotonicNonNull ImmutableList<Long> videoTimestampsUs;

      public Builder(String uri) {
        this.uri = uri;
        videoFrameCount = C.LENGTH_UNSET;
        videoDurationUs = C.TIME_UNSET;
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

      /** See {@link AssetInfo#videoDurationUs}. */
      @CanIgnoreReturnValue
      public Builder setVideoDurationUs(long durationUs) {
        this.videoDurationUs = durationUs;
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
        return new AssetInfo(uri, videoFormat, videoDurationUs, videoFrameCount, videoTimestampsUs);
      }
    }

    /** Asset uri string. */
    public final String uri;

    /** Video {@link Format}, or {@code null}. */
    @Nullable public final Format videoFormat;

    /** Video duration in microseconds, or {@link C#TIME_UNSET}. */
    public final long videoDurationUs;

    /** Video frame count, or {@link C#LENGTH_UNSET}. */
    public final int videoFrameCount;

    /** Video frame timestamps in microseconds, or {@code null}. */
    @Nullable public final ImmutableList<Long> videoTimestampsUs;

    private AssetInfo(
        String uri,
        @Nullable Format videoFormat,
        long videoDurationUs,
        int videoFrameCount,
        @Nullable ImmutableList<Long> videoTimestampsUs) {
      this.uri = uri;
      this.videoFormat = videoFormat;
      this.videoDurationUs = videoDurationUs;
      this.videoFrameCount = videoFrameCount;
      this.videoTimestampsUs = videoTimestampsUs;
    }

    @Override
    public String toString() {
      return "AssetInfo(" + uri + ")";
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
          .setVideoDurationUs(1_024_000L)
          .setVideoFrameCount(30)
          .setVideoTimestampsUs(
              ImmutableList.of(
                  0L, 33_366L, 66_733L, 100_100L, 133_466L, 166_833L, 200_200L, 233_566L, 266_933L,
                  300_300L, 333_666L, 367_033L, 400_400L, 433_766L, 467_133L, 500_500L, 533_866L,
                  567_233L, 600_600L, 633_966L, 667_333L, 700_700L, 734_066L, 767_433L, 800_800L,
                  834_166L, 867_533L, 900_900L, 934_266L, 967_633L))
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

  public static final AssetInfo MP4_ASSET_AV1_VIDEO =
      new AssetInfo.Builder("asset:///media/mp4/sample_av1.mp4")
          .setVideoFormat(
              new Format.Builder()
                  .setSampleMimeType(VIDEO_AV1)
                  .setWidth(1080)
                  .setHeight(720)
                  .setFrameRate(30.0f)
                  .build())
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
                  .setFrameRate(30.00f)
                  .setCodecs("avc1.42C015")
                  .build())
          .setVideoFrameCount(932)
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
                  .setCodecs("hev1.08.02")
                  .setColorInfo(
                      new ColorInfo.Builder()
                          .setColorTransfer(C.COLOR_TRANSFER_HLG)
                          .setColorRange(C.COLOR_RANGE_LIMITED)
                          .setColorSpace(C.COLOR_SPACE_BT2020)
                          .build())
                  .build())
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

  public static final AssetInfo WAV_ASSET =
      new AssetInfo.Builder("asset:///media/wav/sample.wav").build();

  /**
   * Creates the GL objects needed to set up a GL environment including an {@link EGLDisplay} and an
   * {@link EGLContext}.
   */
  public static EGLContext createOpenGlObjects() throws GlUtil.GlException {
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    GlObjectsProvider glObjectsProvider =
        new DefaultGlObjectsProvider(/* sharedEglContext= */ null);
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    return eglContext;
  }

  /**
   * Generates a {@linkplain android.opengl.GLES10#GL_TEXTURE_2D traditional GLES texture} from the
   * given bitmap.
   *
   * <p>Must have a GL context set up.
   */
  public static int generateTextureFromBitmap(Bitmap bitmap) throws GlUtil.GlException {
    return GlUtil.createTexture(bitmap);
  }

  /**
   * Log in logcat and in an analysis file that this test was skipped.
   *
   * <p>Analysis file is a JSON summarising the test, saved to the application cache.
   *
   * <p>The analysis json will contain a {@code skipReason} key, with the reason for skipping the
   * test case.
   */
  public static void recordTestSkipped(Context context, String testId, String reason)
      throws JSONException, IOException {
    Log.i(TAG, testId + ": " + reason);
    JSONObject testJson = new JSONObject();
    testJson.put("skipReason", reason);

    writeTestSummaryToFile(context, testId, testJson);
  }

  public static void assertSdrColors(Context context, String filePath)
      throws ExecutionException, InterruptedException {
    ColorInfo colorInfo = retrieveTrackFormat(context, filePath, C.TRACK_TYPE_VIDEO).colorInfo;
    // Allow unset color values as some encoders don't encode color information for the standard SDR
    // dataspace.
    assertThat(colorInfo.colorTransfer).isAnyOf(C.COLOR_TRANSFER_SDR, Format.NO_VALUE);
    // Before API 34 some encoders output a BT.601 bitstream even though we request BT.709 for SDR
    // output, so allow both color spaces in output files when checking for SDR.
    assertThat(colorInfo.colorSpace)
        .isAnyOf(C.COLOR_SPACE_BT709, C.COLOR_SPACE_BT601, Format.NO_VALUE);
  }

  public static ImmutableList<Bitmap> extractBitmapsFromVideo(Context context, String filePath)
      throws IOException, InterruptedException {
    return extractBitmapsFromVideo(context, filePath, Config.ARGB_8888);
  }

  public static ImmutableList<Bitmap> extractBitmapsFromVideo(
      Context context, String filePath, Bitmap.Config config)
      throws IOException, InterruptedException {
    // b/298599172 - runUntilComparisonFrameOrEnded fails on this device because reading decoder
    //  output as a bitmap doesn't work.
    assumeFalse(Util.SDK_INT == 21 && Ascii.toLowerCase(Util.MODEL).contains("nexus"));
    ImmutableList.Builder<Bitmap> bitmaps = new ImmutableList.Builder<>();
    try (VideoDecodingWrapper decodingWrapper =
        new VideoDecodingWrapper(
            context, filePath, /* comparisonInterval= */ 1, /* maxImagesAllowed= */ 1)) {
      while (true) {
        @Nullable Image image = decodingWrapper.runUntilComparisonFrameOrEnded();
        if (image == null) {
          break;
        }
        bitmaps.add(BitmapPixelTestUtil.createGrayscaleBitmapFromYuv420888Image(image, config));
        image.close();
      }
    }
    return bitmaps.build();
  }

  /**
   * Creates a {@link GlEffect} that counts the number of frames processed in {@code frameCount}.
   */
  public static GlEffect createFrameCountingEffect(AtomicInteger frameCount) {
    return new GlEffect() {
      @Override
      public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
        return new PassthroughShaderProgram() {
          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
            frameCount.incrementAndGet();
          }
        };
      }
    };
  }

  /** A customizable forwarding {@link Codec.EncoderFactory} that forces encoding. */
  public static final class ForceEncodeEncoderFactory implements Codec.EncoderFactory {

    private final Codec.EncoderFactory encoderFactory;

    /** Creates an instance that wraps {@link DefaultEncoderFactory}. */
    public ForceEncodeEncoderFactory(Context context) {
      encoderFactory = new DefaultEncoderFactory.Builder(context).build();
    }

    /**
     * Creates an instance that wraps {@link DefaultEncoderFactory} that wraps another {@link
     * Codec.EncoderFactory}.
     */
    public ForceEncodeEncoderFactory(Codec.EncoderFactory wrappedEncoderFactory) {
      this.encoderFactory = wrappedEncoderFactory;
    }

    @Override
    public Codec createForAudioEncoding(Format format) throws ExportException {
      return encoderFactory.createForAudioEncoding(format);
    }

    @Override
    public Codec createForVideoEncoding(Format format) throws ExportException {
      return encoderFactory.createForVideoEncoding(format);
    }

    @Override
    public boolean audioNeedsEncoding() {
      return true;
    }

    @Override
    public boolean videoNeedsEncoding() {
      return true;
    }
  }

  /**
   * Implementation of {@link ByteBufferGlEffect.Processor} that counts how many frames are copied
   * to CPU memory.
   */
  public static final class FrameCountingByteBufferProcessor
      implements ByteBufferGlEffect.Processor<Integer> {
    public final AtomicInteger frameCount;

    private int width;
    private int height;

    public FrameCountingByteBufferProcessor() {
      frameCount = new AtomicInteger();
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
      width = inputWidth;
      height = inputHeight;
      return new Size(width, height);
    }

    @Override
    public GlRect getScaledRegion(long presentationTimeUs) {
      return new GlRect(width, height);
    }

    @Override
    public ListenableFuture<Integer> processImage(
        ByteBufferGlEffect.Image image, long presentationTimeUs) {
      return immediateFuture(frameCount.incrementAndGet());
    }

    @Override
    public void finishProcessingAndBlend(
        GlTextureInfo outputFrame, long presentationTimeUs, Integer result) {}

    @Override
    public void release() {}
  }

  /**
   * Writes the summary of a test run to the application cache file.
   *
   * <p>The cache filename follows the pattern {@code <testId>-result.txt}.
   *
   * @param context The {@link Context}.
   * @param testId A unique identifier for the transformer test run.
   * @param testJson A {@link JSONObject} containing a summary of the test run.
   */
  public static void writeTestSummaryToFile(Context context, String testId, JSONObject testJson)
      throws IOException, JSONException {
    testJson.put("testId", testId).put("device", JsonUtil.getDeviceDetailsAsJsonObject());

    String analysisContents = testJson.toString(/* indentSpaces= */ 2);

    // Log contents as well as writing to file, for easier visibility on individual device testing.
    for (String line : Util.split(analysisContents, "\n")) {
      Log.i(TAG, testId + ": " + line);
    }

    File analysisFile = createExternalCacheFile(context, /* fileName= */ testId + "-result.txt");
    try (FileWriter fileWriter = new FileWriter(analysisFile)) {
      fileWriter.write(analysisContents);
    }
  }

  /**
   * Assumes that the device supports decoding the input format, and encoding/muxing the output
   * format if needed.
   *
   * @param context The {@link Context context}.
   * @param testId The test ID.
   * @param inputFormat The {@link Format format} to decode, or the input is not produced by
   *     MediaCodec, like an image.
   * @param outputFormat The {@link Format format} to encode/mux or {@code null} if the output won't
   *     be encoded or muxed.
   * @throws AssumptionViolatedException If the device does not support the formats. In this case,
   *     the reason for skipping the test is logged.
   */
  public static void assumeFormatsSupported(
      Context context, String testId, @Nullable Format inputFormat, @Nullable Format outputFormat)
      throws IOException, JSONException, MediaCodecUtil.DecoderQueryException {
    // TODO(b/278657595): Make this capability check match the default codec factory selection code.
    boolean canDecode = inputFormat == null || canDecode(inputFormat);

    boolean canEncode = outputFormat == null || canEncode(outputFormat);
    boolean canMux = outputFormat == null || canMux(outputFormat);
    if (canDecode && canEncode && canMux) {
      return;
    }

    StringBuilder skipReasonBuilder = new StringBuilder();
    if (!canDecode) {
      skipReasonBuilder.append("Cannot decode ").append(inputFormat).append('\n');
    }
    if (!canEncode) {
      skipReasonBuilder.append("Cannot encode ").append(outputFormat).append('\n');
    }
    if (!canMux) {
      skipReasonBuilder.append("Cannot mux ").append(outputFormat);
    }
    String skipReason = skipReasonBuilder.toString();
    recordTestSkipped(context, testId, skipReason);
    throw new AssumptionViolatedException(skipReason);
  }

  /** Returns a {@link Muxer.Factory} depending upon the API level. */
  public static Muxer.Factory getMuxerFactoryBasedOnApi() {
    // MediaMuxer supports B-frame from API > 24.
    return SDK_INT > 24 ? new DefaultMuxer.Factory() : new InAppMuxer.Factory.Builder().build();
  }

  private static boolean canDecode(Format format) {
    if (MimeTypes.isImage(format.sampleMimeType)) {
      return Util.isBitmapFactorySupportedMimeType(format.sampleMimeType);
    }

    // Check decoding capability in the same way as the default decoder factory.
    MediaFormat mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format);
    @Nullable
    Pair<Integer, Integer> codecProfileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
    if (codecProfileAndLevel != null) {
      MediaFormatUtil.maybeSetInteger(
          mediaFormat, MediaFormat.KEY_PROFILE, codecProfileAndLevel.first);
    }
    return EncoderUtil.findCodecForFormat(mediaFormat, /* isDecoder= */ true) != null
        && !deviceNeedsDisable8kWorkaround(format);
  }

  private static boolean deviceNeedsDisable8kWorkaround(Format format) {
    // Fixed on API 31+. See http://b/278234847#comment40 for more information.
    // Duplicate of DefaultDecoderFactory#deviceNeedsDisable8kWorkaround.
    return SDK_INT < 31
        && format.width >= 7680
        && format.height >= 4320
        && format.sampleMimeType != null
        && format.sampleMimeType.equals(MimeTypes.VIDEO_H265)
        && (Ascii.equalsIgnoreCase(Util.MODEL, "SM-F711U1")
            || Ascii.equalsIgnoreCase(Util.MODEL, "SM-F926U1"));
  }

  private static boolean canEncode(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    ImmutableList<android.media.MediaCodecInfo> supportedEncoders =
        EncoderUtil.getSupportedEncoders(mimeType);
    if (supportedEncoders.isEmpty()) {
      return false;
    }

    android.media.MediaCodecInfo encoder = supportedEncoders.get(0);
    boolean sizeSupported =
        EncoderUtil.isSizeSupported(encoder, mimeType, format.width, format.height);
    boolean bitrateSupported =
        format.averageBitrate == Format.NO_VALUE
            || EncoderUtil.getSupportedBitrateRange(encoder, mimeType)
                .contains(format.averageBitrate);
    return sizeSupported && bitrateSupported;
  }

  private static boolean canMux(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    return new DefaultMuxer.Factory()
        .getSupportedSampleMimeTypes(MimeTypes.getTrackType(mimeType))
        .contains(mimeType);
  }

  /**
   * Creates a {@link File} of the {@code fileName} in the application cache directory.
   *
   * <p>If a file of that name already exists, it is overwritten.
   */
  /* package */ static File createExternalCacheFile(Context context, String fileName)
      throws IOException {
    File file = new File(context.getExternalCacheDir(), fileName);
    checkState(!file.exists() || file.delete(), "Could not delete file: " + file.getAbsolutePath());
    checkState(file.createNewFile(), "Could not create file: " + file.getAbsolutePath());
    return file;
  }

  private AndroidTestUtil() {}
}
