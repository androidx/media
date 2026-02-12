/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.hls.playlist;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.HlsTrackMetadataEntry;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link HlsPlaylistParser} to parse {@link HlsMultivariantPlaylist}. */
// This is an instrumentation test to provide realistic regex behaviour for regression tests for
// https://github.com/androidx/media/issues/2420.
@RunWith(AndroidJUnit4.class)
public class HlsMultivariantPlaylistParserTest {

  private static final Uri PLAYLIST_URI = Uri.parse("https://example.com/test.m3u8");

  private static final String PLAYLIST_SIMPLE =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"mp4a.40.2 , avc1.66.30 \"\n"
          + "http://example.com/spaces_in_codecs.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2560000,FRAME-RATE=25,RESOLUTION=384x160\n"
          + "http://example.com/mid.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,FRAME-RATE=29.997\n"
          + "http://example.com/hi.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"mp4a.40.5\"\n"
          + "http://example.com/audio-only.m3u8";

  private static final String PLAYLIST_WITH_AVG_BANDWIDTH =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1270000,"
          + "CODECS=\"mp4a.40.2 , avc1.66.30 \"\n"
          + "http://example.com/spaces_in_codecs.m3u8\n";

  private static final String PLAYLIST_WITH_DOLBY_VISION =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8500000,AVERAGE-BANDWIDTH=6000000,"
          + "CODECS=\"dvh1.10.05\",RESOLUTION=1920x1080,VIDEO-RANGE=PQ\n"
          + "http://example.com/high_hdr.m3u8\n";

  private static final String PLAYLIST_WITH_PATHWAY_ID_AND_STABLE_VARIANT_ID =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128,PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video1\"\n"
          + "http://example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8940000,CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=1920x1080,PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video2\"\n"
          + "http://example.com/high.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128,PATHWAY-ID=\"CDN-B\",STABLE-VARIANT-ID=\"Video1\"\n"
          + "http://backup.example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8940000,CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=1920x1080,PATHWAY-ID=\"CDN-B\",STABLE-VARIANT-ID=\"Video2\"\n"
          + "http://backup.example.com/high.m3u8\n";

  private static final String PLAYLIST_WITH_INVALID_HEADER =
      "#EXTMU3\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n";

  // NAME contains \f as a regression test for https://github.com/androidx/media/issues/2420.
  private static final String PLAYLIST_WITH_CC =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"cc1\","
          + "LANGUAGE=\"es\",NAME=\"\fEng\",INSTREAM-ID=\"SERVICE4\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITH_CHANNELS_ATTRIBUTE =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",CHANNELS=\"6\",NAME=\"Eng6\","
          + "URI=\"something.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",CHANNELS=\"2/6\",NAME=\"Eng26\","
          + "URI=\"something2.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Eng\","
          + "URI=\"something3.m3u8\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",AUDIO=\"audio\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITHOUT_CC =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"cc1\","
          + "LANGUAGE=\"es\",NAME=\"Eng\",INSTREAM-ID=\"SERVICE4\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128,"
          + "CLOSED-CAPTIONS=NONE\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITH_SUBTITLES =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub1\",URI=\"s1/en/prog_index.m3u8\","
          + "LANGUAGE=\"es\",NAME=\"Eng\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITH_SUBTITLES_NO_URI =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub1\","
          + "LANGUAGE=\"es\",NAME=\"Eng\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITH_SUBTITLES_STABLE_RENDITION_ID =
      " #EXTM3U \n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub1\",URI=\"s1/en/prog_index.m3u8\","
          + "LANGUAGE=\"es\",NAME=\"Eng\",STABLE-RENDITION-ID=\"Subtitles-Eng\"\n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub1\",URI=\"s1/fr/prog_index.m3u8\","
          + "LANGUAGE=\"fr\",NAME=\"Fra\",STABLE-RENDITION-ID=\"Subtitles-Fra\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128,STABLE-VARIANT-ID=\"Video\"\n"
          + "http://example.com/low.m3u8\n";

  private static final String PLAYLIST_WITH_AUDIO_MEDIA_TAG =
      "#EXTM3U\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2227464,CODECS=\"avc1.640020,mp4a.40.2\",AUDIO=\"aud1\"\n"
          + "uri1.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8178040,CODECS=\"avc1.64002a,mp4a.40.2\",AUDIO=\"aud1\"\n"
          + "uri2.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2448841,CODECS=\"avc1.640020,ac-3\",AUDIO=\"aud2\"\n"
          + "uri1.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8399417,CODECS=\"avc1.64002a,ac-3\",AUDIO=\"aud2\"\n"
          + "uri2.m3u8\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud1\",LANGUAGE=\"en\",NAME=\"English\","
          + "AUTOSELECT=YES,DEFAULT=YES,CHANNELS=\"2\",URI=\"a1/prog_index.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud2\",LANGUAGE=\"en\",NAME=\"English\","
          + "AUTOSELECT=YES,DEFAULT=YES,CHANNELS=\"6\",URI=\"a2/prog_index.m3u8\"\n";

  private static final String PLAYLIST_WITH_AUDIO_STABLE_RENDITION_ID =
      "#EXTM3U\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8178040,CODECS=\"avc1.64002a,mp4a.40.2\",AUDIO=\"aud1\",STABLE-VARIANT-ID=\"Video1\"\n"
          + "uri1.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2448841,CODECS=\"avc1.640020,ac-3\",AUDIO=\"aud2\",STABLE-VARIANT-ID=\"Video2\"\n"
          + "uri2.m3u8\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud1\",LANGUAGE=\"en\",NAME=\"English\",STABLE-RENDITION-ID=\"Audio1\",AUTOSELECT=YES,DEFAULT=YES,CHANNELS=\"2\",URI=\"a1/prog_index.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud2\",LANGUAGE=\"en\",NAME=\"English\",STABLE-RENDITION-ID=\"Audio2\",AUTOSELECT=YES,DEFAULT=YES,CHANNELS=\"6\",URI=\"a2/prog_index.m3u8\"\n";

  private static final String PLAYLIST_WITH_INDEPENDENT_SEGMENTS =
      " #EXTM3U\n"
          + "\n"
          + "#EXT-X-INDEPENDENT-SEGMENTS\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,"
          + "CODECS=\"mp4a.40.2,avc1.66.30\",RESOLUTION=304x128\n"
          + "http://example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"mp4a.40.2 , avc1.66.30 \"\n"
          + "http://example.com/spaces_in_codecs.m3u8\n";

  private static final String PLAYLIST_WITH_VARIABLE_SUBSTITUTION =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-DEFINE:NAME=\"codecs\",VALUE=\"mp4a.40.5\"\n"
          + "#EXT-X-DEFINE:NAME=\"tricky\",VALUE=\"This/{$nested}/reference/shouldnt/work\"\n"
          + "#EXT-X-DEFINE:NAME=\"nested\",VALUE=\"This should not be inserted\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"{$codecs}\"\n"
          + "http://example.com/{$tricky}\n";

  private static final String PLAYLIST_WITH_QUERY_PARAM_SUBSTITUTION =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-DEFINE:QUERYPARAM=\"path\",VALUE=\"\"\n"
          + "#EXT-X-DEFINE:QUERYPARAM=\"codecs\",VALUE=\"\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"{$codecs}\"\n"
          + "http://example.com/{$path}\n";

  private static final String PLAYLIST_WITH_DUPLICATE_VARIABLE_AND_QUERY_PARAM_NAMES =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-DEFINE:NAME=\"path\",VALUE=\"path/to/glory\"\n"
          + "#EXT-X-DEFINE:QUERYPARAM=\"path\",VALUE=\"\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"mp4a.40.5\"\n"
          + "http://example.com/{$path}\n";

  private static final String PLAYLIST_WITH_DUPLICATE_VARIABLE_NAMES =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-DEFINE:NAME=\"var_name\",VALUE=\"path/to/glory\"\n"
          + "#EXT-X-DEFINE:NAME=\"var_name\",VALUE=\"\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"mp4a.40.5\"\n"
          + "http://example.com/{$path}\n";

  private static final String PLAYLIST_WITH_DUPLICATE_QUERY_PARAM_NAMES =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-DEFINE:QUERYPARAM=\"query_param\",VALUE=\"\"\n"
          + "#EXT-X-DEFINE:QUERYPARAM=\"query_param\",VALUE=\"\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=65000,CODECS=\"mp4a.40.5\"\n"
          + "http://example.com/{$path}\n";

  private static final String PLAYLIST_WITH_MATCHING_STREAM_INF_URLS =
      "#EXTM3U\n"
          + "#EXT-X-VERSION:6\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2227464,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud1\",SUBTITLES=\"sub1\"\n"
          + "v5/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=6453202,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud1\",SUBTITLES=\"sub1\"\n"
          + "v8/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=5054232,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud1\",SUBTITLES=\"sub1\"\n"
          + "v7/prog_index.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2448841,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud2\",SUBTITLES=\"sub1\"\n"
          + "v5/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8399417,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud2\",SUBTITLES=\"sub1\"\n"
          + "v9/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=5275609,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud2\",SUBTITLES=\"sub1\"\n"
          + "v7/prog_index.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=2256841,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud3\",SUBTITLES=\"sub1\"\n"
          + "v5/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=8207417,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud3\",SUBTITLES=\"sub1\"\n"
          + "v9/prog_index.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=6482579,"
          + "CLOSED-CAPTIONS=\"cc1\",AUDIO=\"aud3\",SUBTITLES=\"sub1\"\n"
          + "v8/prog_index.m3u8\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud1\",NAME=\"English\",URI=\"a1/index.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud2\",NAME=\"English\",URI=\"a2/index.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud3\",NAME=\"English\",URI=\"a3/index.m3u8\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,"
          + "GROUP-ID=\"cc1\",NAME=\"English\",INSTREAM-ID=\"CC1\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,"
          + "GROUP-ID=\"sub1\",NAME=\"English\",URI=\"s1/en/prog_index.m3u8\"\n";

  private static final String PLAYLIST_WITH_TTML_SUBTITLE =
      " #EXTM3U\n"
          + "\n"
          + "#EXT-X-VERSION:6\n"
          + "\n"
          + "#EXT-X-INDEPENDENT-SEGMENTS\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS=\"stpp.ttml.im1t,mp4a.40.2,avc1.66.30\",RESOLUTION=304x128,AUDIO=\"aud1\",SUBTITLES=\"sub1\"\n"
          + "http://example.com/low.m3u8\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud1\",NAME=\"English\",URI=\"a1/index.m3u8\"\n"
          + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub1\",NAME=\"English\",AUTOSELECT=YES,DEFAULT=YES,URI=\"s1/en/prog_index.m3u8\"\n";

  private static final String PLAYLIST_WITH_IFRAME_VARIANTS =
      "#EXTM3U\n"
          + "#EXT-X-VERSION:5\n"
          + "#EXT-X-MEDIA:URI=\"AUDIO_English/index.m3u8\",TYPE=AUDIO,GROUP-ID=\"audio-aac\",LANGUAGE=\"en\",NAME=\"English\",AUTOSELECT=YES\n"
          + "#EXT-X-MEDIA:URI=\"AUDIO_Spanish/index.m3u8\",TYPE=AUDIO,GROUP-ID=\"audio-aac\",LANGUAGE=\"es\",NAME=\"Spanish\",AUTOSELECT=YES\n"
          + "#EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID=\"cc1\",LANGUAGE=\"en\",NAME=\"English\",AUTOSELECT=YES,DEFAULT=YES,INSTREAM-ID=\"CC1\"\n"
          + "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=400000,RESOLUTION=480x320,CODECS=\"mp4a.40.2,avc1.640015\",AUDIO=\"audio-aac\",CLOSED-CAPTIONS=\"cc1\"\n"
          + "400000/index.m3u8\n"
          + "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1000000,RESOLUTION=848x480,CODECS=\"mp4a.40.2,avc1.64001f\",AUDIO=\"audio-aac\",CLOSED-CAPTIONS=\"cc1\"\n"
          + "1000000/index.m3u8\n"
          + "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3220000,RESOLUTION=1280x720,CODECS=\"mp4a.40.2,avc1.64001f\",AUDIO=\"audio-aac\",CLOSED-CAPTIONS=\"cc1\"\n"
          + "3220000/index.m3u8\n"
          + "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=8940000,RESOLUTION=1920x1080,CODECS=\"mp4a.40.2,avc1.640028\",AUDIO=\"audio-aac\",CLOSED-CAPTIONS=\"cc1\"\n"
          + "8940000/index.m3u8\n"
          + "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1313400,RESOLUTION=1920x1080,CODECS=\"avc1.640028\",URI=\"iframe_1313400/index.m3u8\"\n";

  @Test
  public void parseMultivariantPlaylist_withSimple_success() throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_SIMPLE);

    List<HlsMultivariantPlaylist.Variant> variants = multivariantPlaylist.variants;
    assertThat(variants).hasSize(5);
    assertThat(multivariantPlaylist.muxedCaptionFormats).isNull();

    assertThat(variants.get(0).format.bitrate).isEqualTo(1280000);
    assertThat(variants.get(0).format.codecs).isEqualTo("mp4a.40.2,avc1.66.30");
    assertThat(variants.get(0).format.width).isEqualTo(304);
    assertThat(variants.get(0).format.height).isEqualTo(128);
    assertThat(variants.get(0).url).isEqualTo(Uri.parse("http://example.com/low.m3u8"));
    assertThat(variants.get(0).pathwayId).isNull();
    assertThat(variants.get(0).stableVariantId).isNull();

    assertThat(variants.get(1).format.bitrate).isEqualTo(1280000);
    assertThat(variants.get(1).format.codecs).isEqualTo("mp4a.40.2 , avc1.66.30 ");
    assertThat(variants.get(1).url)
        .isEqualTo(Uri.parse("http://example.com/spaces_in_codecs.m3u8"));
    assertThat(variants.get(1).pathwayId).isNull();
    assertThat(variants.get(1).stableVariantId).isNull();

    assertThat(variants.get(2).format.bitrate).isEqualTo(2560000);
    assertThat(variants.get(2).format.codecs).isNull();
    assertThat(variants.get(2).format.width).isEqualTo(384);
    assertThat(variants.get(2).format.height).isEqualTo(160);
    assertThat(variants.get(2).format.frameRate).isEqualTo(25.0f);
    assertThat(variants.get(2).url).isEqualTo(Uri.parse("http://example.com/mid.m3u8"));
    assertThat(variants.get(2).pathwayId).isNull();
    assertThat(variants.get(2).stableVariantId).isNull();

    assertThat(variants.get(3).format.bitrate).isEqualTo(7680000);
    assertThat(variants.get(3).format.codecs).isNull();
    assertThat(variants.get(3).format.width).isEqualTo(Format.NO_VALUE);
    assertThat(variants.get(3).format.height).isEqualTo(Format.NO_VALUE);
    assertThat(variants.get(3).format.frameRate).isEqualTo(29.997f);
    assertThat(variants.get(3).url).isEqualTo(Uri.parse("http://example.com/hi.m3u8"));
    assertThat(variants.get(3).pathwayId).isNull();
    assertThat(variants.get(3).stableVariantId).isNull();

    assertThat(variants.get(4).format.bitrate).isEqualTo(65000);
    assertThat(variants.get(4).format.codecs).isEqualTo("mp4a.40.5");
    assertThat(variants.get(4).format.width).isEqualTo(Format.NO_VALUE);
    assertThat(variants.get(4).format.height).isEqualTo(Format.NO_VALUE);
    assertThat(variants.get(4).format.frameRate).isEqualTo((float) Format.NO_VALUE);
    assertThat(variants.get(4).url).isEqualTo(Uri.parse("http://example.com/audio-only.m3u8"));
    assertThat(variants.get(4).pathwayId).isNull();
    assertThat(variants.get(4).stableVariantId).isNull();
  }

  @Test
  public void parseMultivariantPlaylist_withAverageBandwidth_success() throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_AVG_BANDWIDTH);

    List<HlsMultivariantPlaylist.Variant> variants = multivariantPlaylist.variants;

    assertThat(variants.get(0).format.bitrate).isEqualTo(1280000);
    assertThat(variants.get(1).format.bitrate).isEqualTo(1280000);
  }

  @Test
  public void parseMultivariantPlaylist_withDolbyVisionProfile10_success() throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_DOLBY_VISION);

    List<HlsMultivariantPlaylist.Variant> variants = multivariantPlaylist.variants;

    assertThat(variants.get(0).format.colorInfo).isNotNull();
    assertThat(variants.get(0).format.colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(variants.get(0).format.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_ST2084);
    assertThat(variants.get(0).format.colorInfo.colorRange).isEqualTo(C.COLOR_RANGE_FULL);
  }

  @Test
  public void parseMultivariantPlaylist_withPathwayIdAndStableVariantId_success()
      throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_PATHWAY_ID_AND_STABLE_VARIANT_ID);

    List<HlsMultivariantPlaylist.Variant> variants = multivariantPlaylist.variants;

    assertThat(variants.get(0).pathwayId).isEqualTo("CDN-A");
    assertThat(variants.get(0).stableVariantId).isEqualTo("Video1");

    assertThat(variants.get(1).pathwayId).isEqualTo("CDN-A");
    assertThat(variants.get(1).stableVariantId).isEqualTo("Video2");

    assertThat(variants.get(2).pathwayId).isEqualTo("CDN-B");
    assertThat(variants.get(2).stableVariantId).isEqualTo("Video1");

    assertThat(variants.get(3).pathwayId).isEqualTo("CDN-B");
    assertThat(variants.get(3).stableVariantId).isEqualTo("Video2");
  }

  @Test
  public void parseMultivariantPlaylist_withInvalidHeader_throwsException() throws IOException {
    try {
      parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_INVALID_HEADER);
      fail("Expected exception not thrown.");
    } catch (ParserException e) {
      // Expected due to invalid header.
    }
  }

  @Test
  public void parseMultivariantPlaylist_withClosedCaption_success() throws IOException {
    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_CC);
    assertThat(playlist.muxedCaptionFormats).hasSize(1);
    Format closedCaptionFormat = playlist.muxedCaptionFormats.get(0);
    assertThat(closedCaptionFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_CEA708);
    assertThat(closedCaptionFormat.accessibilityChannel).isEqualTo(4);
    assertThat(closedCaptionFormat.language).isEqualTo("es");
  }

  @Test
  public void parseMultivariantPlaylist_withChannelsAttribute_success() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_CHANNELS_ATTRIBUTE);
    List<HlsMultivariantPlaylist.Rendition> audios = playlist.audios;
    assertThat(audios).hasSize(3);
    assertThat(audios.get(0).format.channelCount).isEqualTo(6);
    assertThat(audios.get(1).format.channelCount).isEqualTo(2);
    assertThat(audios.get(2).format.channelCount).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void parseMultivariantPlaylist_withoutClosedCaption_success() throws IOException {
    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITHOUT_CC);
    assertThat(playlist.muxedCaptionFormats).isEmpty();
  }

  @Test
  public void parseMultivariantPlaylist_withAudio_codecPropagated() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_AUDIO_MEDIA_TAG);

    Format firstAudioFormat = playlist.audios.get(0).format;
    assertThat(firstAudioFormat.codecs).isEqualTo("mp4a.40.2");
    assertThat(firstAudioFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AAC);

    Format secondAudioFormat = playlist.audios.get(1).format;
    assertThat(secondAudioFormat.codecs).isEqualTo("ac-3");
    assertThat(secondAudioFormat.sampleMimeType).isEqualTo(MimeTypes.AUDIO_AC3);
  }

  @Test
  public void parseMultivariantPlaylist_withAudio_audioIdPropagated() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_AUDIO_MEDIA_TAG);

    HlsMultivariantPlaylist.Rendition firstAudioRendition = playlist.audios.get(0);
    Format firstAudioFormat = firstAudioRendition.format;
    assertThat(firstAudioFormat.id).isEqualTo("aud1:English");
    assertThat(firstAudioRendition.stableRenditionId).isNull();

    HlsMultivariantPlaylist.Rendition secondAudioRendition = playlist.audios.get(1);
    Format secondAudioFormat = secondAudioRendition.format;
    assertThat(secondAudioFormat.id).isEqualTo("aud2:English");
    assertThat(secondAudioRendition.stableRenditionId).isNull();
  }

  @Test
  public void parseMultivariantPlaylist_withAudio_stableRenditionIdPropagated() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_AUDIO_STABLE_RENDITION_ID);

    assertThat(playlist.audios.get(0).stableRenditionId).isEqualTo("Audio1");
    assertThat(playlist.audios.get(1).stableRenditionId).isEqualTo("Audio2");
  }

  @Test
  public void parseMultivariantPlaylist_withCc_cCIdPropagated() throws IOException {
    HlsMultivariantPlaylist playlist = parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_CC);

    Format firstTextFormat = playlist.muxedCaptionFormats.get(0);
    assertThat(firstTextFormat.id).isEqualTo("cc1:\fEng");
  }

  @Test
  public void parseMultivariantPlaylist_withSubtitles_subtitlesIdPropagated() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_SUBTITLES);

    HlsMultivariantPlaylist.Rendition firstSubtitlesRendition = playlist.subtitles.get(0);
    Format firstTextFormat = firstSubtitlesRendition.format;
    assertThat(firstTextFormat.id).isEqualTo("sub1:Eng");
    assertThat(firstTextFormat.sampleMimeType).isEqualTo(MimeTypes.TEXT_VTT);
    assertThat(firstSubtitlesRendition.stableRenditionId).isNull();
  }

  @Test
  public void parseMultivariantPlaylist_withSubtitles_stableRenditionIdPropagated()
      throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_SUBTITLES_STABLE_RENDITION_ID);

    assertThat(playlist.subtitles.get(0).stableRenditionId).isEqualTo("Subtitles-Eng");
    assertThat(playlist.subtitles.get(1).stableRenditionId).isEqualTo("Subtitles-Fra");
  }

  @Test
  public void parseMultivariantPlaylist_subtitlesWithoutUri_skipsSubtitles() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_SUBTITLES_NO_URI);

    assertThat(playlist.subtitles).isEmpty();
  }

  @Test
  public void parseMultivariantPlaylist_withIndependentSegments_hasNoIndenpendentSegments()
      throws IOException {
    HlsMultivariantPlaylist playlistWithIndependentSegments =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_INDEPENDENT_SEGMENTS);
    assertThat(playlistWithIndependentSegments.hasIndependentSegments).isTrue();

    HlsMultivariantPlaylist playlistWithoutIndependentSegments =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_SIMPLE);
    assertThat(playlistWithoutIndependentSegments.hasIndependentSegments).isFalse();
  }

  @Test
  public void parseMultivariantPlaylist_withQueryParams_placeholderSubstituted()
      throws IOException {
    Uri playlistUri = Uri.parse("http://example.com/?path=appended/path&codecs=mp4a.40.5");

    HlsMultivariantPlaylist playlistWithSubstitutions =
        parseMultivariantPlaylist(playlistUri, PLAYLIST_WITH_QUERY_PARAM_SUBSTITUTION);

    HlsMultivariantPlaylist.Variant variant = playlistWithSubstitutions.variants.get(0);
    assertThat(variant.format.codecs).isEqualTo("mp4a.40.5");
    assertThat(variant.url).isEqualTo(Uri.parse("http://example.com/appended/path"));
  }

  @Test
  public void parseMultivariantPlaylist_withDuplicateVariableNameAndQueryParam_throws() {
    ParserException parserException =
        assertThrows(
            ParserException.class,
            () ->
                parseMultivariantPlaylist(
                    Uri.parse("http://example.com/?path=appended/path&codecs=mp4a.40.5"),
                    PLAYLIST_WITH_DUPLICATE_VARIABLE_AND_QUERY_PARAM_NAMES));
    assertThat(parserException).hasMessageThat().contains("duplicate variable name \"path\"");
  }

  @Test
  public void parseMultivariantPlaylist_withDuplicateVariableName_throws() {
    ParserException parserException =
        assertThrows(
            ParserException.class,
            () ->
                parseMultivariantPlaylist(
                    Uri.parse("http://example.com/?path=appended/path&codecs=mp4a.40.5"),
                    PLAYLIST_WITH_DUPLICATE_VARIABLE_NAMES));
    assertThat(parserException).hasMessageThat().contains("duplicate variable name \"var_name\"");
  }

  @Test
  public void parseMultivariantPlaylist_withDuplicateQueryParamName_throws() {
    ParserException parserException =
        assertThrows(
            ParserException.class,
            () ->
                parseMultivariantPlaylist(
                    Uri.parse("http://example.com/?query_param=value_1"),
                    PLAYLIST_WITH_DUPLICATE_QUERY_PARAM_NAMES));
    assertThat(parserException)
        .hasMessageThat()
        .contains("duplicate variable name \"query_param\"");
  }

  @Test
  public void parseMultivariantPlaylist_missingQueryParam_throws() {
    ParserException parserException =
        assertThrows(
            ParserException.class,
            () -> parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_QUERY_PARAM_SUBSTITUTION));
    assertThat(parserException)
        .hasMessageThat()
        .contains("QUERYPARAM \"path\" not found in playlist URI");
  }

  @Test
  public void
      parseMultivariantPlaylist_dependentPlaylistWithImportedQueryParam_placeholderSubstituted()
          throws IOException {
    String mediaPlaylistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:8\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXT-X-DEFINE:IMPORT=\"path\"\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/1.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/2.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/3.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/4.ts\n";
    Uri multivariantPlaylistUri =
        Uri.parse("http://example.com/?path=appended/path&codecs=mp4a.40.5");
    HlsMultivariantPlaylist playlistWithSubstitutions =
        parseMultivariantPlaylist(multivariantPlaylistUri, PLAYLIST_WITH_QUERY_PARAM_SUBSTITUTION);
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(Util.getUtf8Bytes(mediaPlaylistString));

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(playlistWithSubstitutions, /* previousMediaPlaylist= */ null)
                .parse(Uri.parse("http://example.com/"), inputStream);

    for (int i = 0; i < mediaPlaylist.segments.size(); i++) {
      assertThat(mediaPlaylist.segments.get(i).url)
          .isEqualTo("relative/from/appended/path/" + (i + 1) + ".ts");
    }
  }

  @Test
  public void parseMultivariantPlaylist_dependentPlaylistWithoutImport_placeholderNotSubstituted()
      throws IOException {
    String mediaPlaylistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:8\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/1.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/2.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/3.ts\n"
            + "#EXTINF:5.005,\n"
            + "relative/from/{$path}/4.ts\n";
    Uri multivariantPlaylistUri =
        Uri.parse("http://example.com/?path=appended/path&codecs=mp4a.40.5");
    HlsMultivariantPlaylist playlistWithSubstitutions =
        parseMultivariantPlaylist(multivariantPlaylistUri, PLAYLIST_WITH_QUERY_PARAM_SUBSTITUTION);
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(Util.getUtf8Bytes(mediaPlaylistString));

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(playlistWithSubstitutions, /* previousMediaPlaylist= */ null)
                .parse(Uri.parse("http://example.com/"), inputStream);

    for (int i = 0; i < mediaPlaylist.segments.size(); i++) {
      assertThat(mediaPlaylist.segments.get(i).url)
          .isEqualTo("relative/from/{$path}/" + (i + 1) + ".ts");
    }
  }

  @Test
  public void parseMultivariantPlaylist_withVariableSubstitution_success() throws IOException {
    HlsMultivariantPlaylist playlistWithSubstitutions =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_VARIABLE_SUBSTITUTION);
    HlsMultivariantPlaylist.Variant variant = playlistWithSubstitutions.variants.get(0);
    assertThat(variant.format.codecs).isEqualTo("mp4a.40.5");
    assertThat(variant.url)
        .isEqualTo(Uri.parse("http://example.com/This/{$nested}/reference/shouldnt/work"));
  }

  @Test
  public void parseMultivariantPlaylist_withTtmlSubtitle() throws IOException {
    HlsMultivariantPlaylist playlistWithTtmlSubtitle =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_TTML_SUBTITLE);
    HlsMultivariantPlaylist.Variant variant = playlistWithTtmlSubtitle.variants.get(0);
    Format firstTextFormat = playlistWithTtmlSubtitle.subtitles.get(0).format;
    assertThat(firstTextFormat.id).isEqualTo("sub1:English");
    assertThat(firstTextFormat.containerMimeType).isEqualTo(MimeTypes.APPLICATION_M3U8);
    assertThat(firstTextFormat.sampleMimeType).isEqualTo(MimeTypes.APPLICATION_TTML);
    assertThat(variant.format.codecs).isEqualTo("stpp.ttml.im1t,mp4a.40.2,avc1.66.30");
  }

  @Test
  public void parseMultivariantPlaylist_withMatchingStreamInfUrls_success() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_MATCHING_STREAM_INF_URLS);
    assertThat(playlist.variants).hasSize(4);
    assertThat(playlist.variants.get(0).format.metadata)
        .isEqualTo(
            createExtXStreamInfMetadata(
                createVariantInfo(/* peakBitrate= */ 2227464, /* audioGroupId= */ "aud1"),
                createVariantInfo(/* peakBitrate= */ 2448841, /* audioGroupId= */ "aud2"),
                createVariantInfo(/* peakBitrate= */ 2256841, /* audioGroupId= */ "aud3")));
    assertThat(playlist.variants.get(1).format.metadata)
        .isEqualTo(
            createExtXStreamInfMetadata(
                createVariantInfo(/* peakBitrate= */ 6453202, /* audioGroupId= */ "aud1"),
                createVariantInfo(/* peakBitrate= */ 6482579, /* audioGroupId= */ "aud3")));
    assertThat(playlist.variants.get(2).format.metadata)
        .isEqualTo(
            createExtXStreamInfMetadata(
                createVariantInfo(/* peakBitrate= */ 5054232, /* audioGroupId= */ "aud1"),
                createVariantInfo(/* peakBitrate= */ 5275609, /* audioGroupId= */ "aud2")));
    assertThat(playlist.variants.get(3).format.metadata)
        .isEqualTo(
            createExtXStreamInfMetadata(
                createVariantInfo(/* peakBitrate= */ 8399417, /* audioGroupId= */ "aud2"),
                createVariantInfo(/* peakBitrate= */ 8207417, /* audioGroupId= */ "aud3")));

    assertThat(playlist.audios).hasSize(3);
    assertThat(playlist.audios.get(0).format.metadata)
        .isEqualTo(createExtXMediaMetadata(/* groupId= */ "aud1", /* name= */ "English"));
    assertThat(playlist.audios.get(1).format.metadata)
        .isEqualTo(createExtXMediaMetadata(/* groupId= */ "aud2", /* name= */ "English"));
    assertThat(playlist.audios.get(2).format.metadata)
        .isEqualTo(createExtXMediaMetadata(/* groupId= */ "aud3", /* name= */ "English"));
  }

  @Test
  public void testIFrameVariant() throws IOException {
    HlsMultivariantPlaylist playlist =
        parseMultivariantPlaylist(PLAYLIST_URI, PLAYLIST_WITH_IFRAME_VARIANTS);
    assertThat(playlist.variants).hasSize(5);
    for (int i = 0; i < 4; i++) {
      assertThat(playlist.variants.get(i).format.roleFlags).isEqualTo(0);
    }
    Variant iFramesOnlyVariant = playlist.variants.get(4);
    assertThat(iFramesOnlyVariant.format.bitrate).isEqualTo(1313400);
    assertThat(iFramesOnlyVariant.format.roleFlags & C.ROLE_FLAG_TRICK_PLAY)
        .isEqualTo(C.ROLE_FLAG_TRICK_PLAY);
  }

  private static Metadata createExtXStreamInfMetadata(HlsTrackMetadataEntry.VariantInfo... infos) {
    return new Metadata(
        new HlsTrackMetadataEntry(/* groupId= */ null, /* name= */ null, Arrays.asList(infos)));
  }

  private static Metadata createExtXMediaMetadata(String groupId, String name) {
    return new Metadata(new HlsTrackMetadataEntry(groupId, name, Collections.emptyList()));
  }

  private static HlsTrackMetadataEntry.VariantInfo createVariantInfo(
      int peakBitrate, String audioGroupId) {
    return new HlsTrackMetadataEntry.VariantInfo(
        /* averageBitrate= */ Format.NO_VALUE,
        /* peakBitrate= */ peakBitrate,
        /* videoGroupId= */ null,
        audioGroupId,
        /* subtitleGroupId= */ "sub1",
        /* captionGroupId= */ "cc1");
  }

  private static HlsMultivariantPlaylist parseMultivariantPlaylist(
      Uri playlistUri, String playlistString) throws IOException {
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream(playlistString.getBytes(StandardCharsets.UTF_8));
    return (HlsMultivariantPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
  }
}
