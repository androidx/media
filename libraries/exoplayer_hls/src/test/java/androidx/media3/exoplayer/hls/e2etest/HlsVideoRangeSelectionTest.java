/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.hls.e2etest;

import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CodecInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** End-to-end tests for selecting between HLS variants with different {@code VIDEO-RANGE}. */
@Config(sdk = 30)
@RunWith(AndroidJUnit4.class)
public final class HlsVideoRangeSelectionTest {

  private static final String PQ_HEVC_CODECS = "hvc1.2.4.L153.B0,mp4a.40.2";
  private static final String SDR_HEVC_CODECS = "hvc1.1.4.L153.B0,mp4a.40.2";

  /** An initial estimate that exceeds the bandwidth of every variant. */
  private static final long HIGH_BITRATE_ESTIMATE = 1_000_000_000;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withNoDefaultSupportedCodecs();

  // The PQ variant is listed first so that only the decoder capability check can select the SDR
  // variant.
  @Test
  public void pqAndSdrHevcVariants_decoderWithoutHdr10Support_selectsSdrVariant() throws Exception {
    mediaCodecConfig.addDecoders(
        ShadowMediaCodecConfig.CODEC_INFO_AAC,
        createHevcDecoderInfo(
            CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10));

    Format format =
        getFirstMediaLoadFormat("asset:///media/hls/video-range/pq_hevc_and_sdr_hevc.m3u8");

    assertThat(format.codecs).isEqualTo(SDR_HEVC_CODECS);
  }

  // The SDR variant is listed first so that only the HDR preference can select the PQ variant.
  @Test
  public void sdrAv1AndPqHevcVariants_decodersSupportBoth_selectsPqVariant() throws Exception {
    mediaCodecConfig.addDecoders(
        ShadowMediaCodecConfig.CODEC_INFO_AAC,
        ShadowMediaCodecConfig.CODEC_INFO_AV1,
        createHevcDecoderInfo(
            CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10));

    Format format =
        getFirstMediaLoadFormat("asset:///media/hls/video-range/sdr_av1_and_pq_hevc.m3u8");

    assertThat(format.codecs).isEqualTo(PQ_HEVC_CODECS);
  }

  private static CodecInfo createHevcDecoderInfo(int... profiles) {
    ImmutableList.Builder<CodecProfileLevel> profileLevels = ImmutableList.builder();
    for (int profile : profiles) {
      profileLevels.add(createCodecProfileLevel(profile, CodecProfileLevel.HEVCMainTierLevel61));
    }
    return new CodecInfo(
        /* codecName= */ "media3.video.hevc",
        MimeTypes.VIDEO_H265,
        profileLevels.build(),
        /* colorFormats= */ ImmutableList.of(CodecCapabilities.COLOR_FormatYUV420Flexible));
  }

  /**
   * Prepares a player for the given multivariant playlist and returns the track format of the first
   * media segment it requests.
   */
  private static Format getFirstMediaLoadFormat(String multivariantPlaylistUri) throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setBandwidthMeter(
                new DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(HIGH_BITRATE_ESTIMATE)
                    .build())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    AtomicReference<Format> firstMediaLoadFormat = new AtomicReference<>();
    player.addAnalyticsListener(
        new AnalyticsListener() {
          @Override
          public void onLoadStarted(
              EventTime eventTime,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              int retryCount) {
            @Nullable Format trackFormat = mediaLoadData.trackFormat;
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && trackFormat != null) {
              firstMediaLoadFormat.compareAndSet(/* expectedValue= */ null, trackFormat);
            }
          }
        });

    player.setMediaItem(MediaItem.fromUri(multivariantPlaylistUri));
    player.prepare();
    advance(player).untilBackgroundThreadCondition(() -> firstMediaLoadFormat.get() != null);
    player.release();

    return firstMediaLoadFormat.get();
  }
}
