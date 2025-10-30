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

import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_ONCE;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_POST;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_PRE;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.NAVIGATION_RESTRICTION_JUMP;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.NAVIGATION_RESTRICTION_SKIP;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.SNAP_TYPE_IN;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.SNAP_TYPE_OUT;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.net.Uri;
import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Segment;
import androidx.media3.extractor.mp4.PsshAtomUtil;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link HlsMediaPlaylistParserTest}. */
@RunWith(AndroidJUnit4.class)
public class HlsMediaPlaylistParserTest {

  @Test
  public void parseMediaPlaylist() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "#EXT-X-START:TIME-OFFSET=-25\n"
            + "#EXT-X-TARGETDURATION:8\n"
            + "#EXT-X-MEDIA-SEQUENCE:2679\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:4\n"
            + "#EXT-X-ALLOW-CACHE:YES\n"
            + "\n"
            + "#EXTINF:7.975,\n"
            + "#EXT-X-BYTERANGE:51370@0\n"
            + "https://priv.example.com/fileSequence2679.ts\n"
            + "\n"
            + "#EXT-X-KEY:METHOD=AES-128,"
            + "URI=\"https://priv.example.com/key.php?r=2680\",IV=0x1566B\n"
            + "#EXTINF:7.975,segment title\n"
            + "#EXT-X-BYTERANGE:51501@2147483648\n"
            + "https://priv.example.com/fileSequence2680.ts\n"
            + "\n"
            + "#EXT-X-KEY:METHOD=NONE\n"
            + "#EXTINF:7.941,segment title .,:/# with interesting chars\n"
            + "#EXT-X-BYTERANGE:51501\n" // @2147535149
            + "https://priv.example.com/fileSequence2681.ts\n"
            + "\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://priv.example.com/key.php?r=2682\"\n"
            + "#EXTINF:7.975\n" // Trailing comma is omitted.
            + "#EXT-X-BYTERANGE:51740\n" // @2147586650
            + "https://priv.example.com/fileSequence2682.ts\n"
            + "\n"
            + "#EXTINF:7.975,\n"
            + "https://priv.example.com/fileSequence2683.ts\n"
            + "\n"
            // 2.002 tests correct rounding, see https://github.com/google/ExoPlayer/issues/9575.
            + "#EXTINF:2.002,\n"
            + "https://priv.example.com/fileSequence2684.ts\n"
            + "#EXT-X-ENDLIST";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsPlaylist playlist = new HlsPlaylistParser().parse(playlistUri, inputStream);

    HlsMediaPlaylist mediaPlaylist = (HlsMediaPlaylist) playlist;
    assertThat(mediaPlaylist.playlistType).isEqualTo(HlsMediaPlaylist.PLAYLIST_TYPE_VOD);
    assertThat(mediaPlaylist.startOffsetUs).isEqualTo(mediaPlaylist.durationUs - 25000000);

    assertThat(mediaPlaylist.mediaSequence).isEqualTo(2679);
    assertThat(mediaPlaylist.version).isEqualTo(3);
    assertThat(mediaPlaylist.hasEndTag).isTrue();
    assertThat(mediaPlaylist.protectionSchemes).isNull();
    assertThat(mediaPlaylist.targetDurationUs).isEqualTo(8000000);
    assertThat(mediaPlaylist.partTargetDurationUs).isEqualTo(C.TIME_UNSET);
    List<Segment> segments = mediaPlaylist.segments;
    assertThat(segments).isNotNull();
    assertThat(segments).hasSize(6);

    Segment segment = segments.get(0);
    assertThat(mediaPlaylist.discontinuitySequence + segment.relativeDiscontinuitySequence)
        .isEqualTo(4);
    assertThat(segment.durationUs).isEqualTo(7975000);
    assertThat(segment.title).isEqualTo("");
    assertThat(segment.fullSegmentEncryptionKeyUri).isNull();
    assertThat(segment.encryptionIV).isNull();
    assertThat(segment.byteRangeLength).isEqualTo(51370);
    assertThat(segment.byteRangeOffset).isEqualTo(0);
    assertThat(segment.url).isEqualTo("https://priv.example.com/fileSequence2679.ts");

    segment = segments.get(1);
    assertThat(segment.relativeDiscontinuitySequence).isEqualTo(0);
    assertThat(segment.durationUs).isEqualTo(7975000);
    assertThat(segment.title).isEqualTo("segment title");
    assertThat(segment.fullSegmentEncryptionKeyUri)
        .isEqualTo("https://priv.example.com/key.php?r=2680");
    assertThat(segment.encryptionIV).isEqualTo("0x1566B");
    assertThat(segment.byteRangeLength).isEqualTo(51501);
    assertThat(segment.byteRangeOffset).isEqualTo(2147483648L);
    assertThat(segment.url).isEqualTo("https://priv.example.com/fileSequence2680.ts");

    segment = segments.get(2);
    assertThat(segment.relativeDiscontinuitySequence).isEqualTo(0);
    assertThat(segment.durationUs).isEqualTo(7941000);
    assertThat(segment.title).isEqualTo("segment title .,:/# with interesting chars");
    assertThat(segment.fullSegmentEncryptionKeyUri).isNull();
    assertThat(segment.encryptionIV).isEqualTo(null);
    assertThat(segment.byteRangeLength).isEqualTo(51501);
    assertThat(segment.byteRangeOffset).isEqualTo(2147535149L);
    assertThat(segment.url).isEqualTo("https://priv.example.com/fileSequence2681.ts");

    segment = segments.get(3);
    assertThat(segment.relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(segment.durationUs).isEqualTo(7975000);
    assertThat(segment.title).isEqualTo("");
    assertThat(segment.fullSegmentEncryptionKeyUri)
        .isEqualTo("https://priv.example.com/key.php?r=2682");
    // 0xA7A == 2682.
    assertThat(segment.encryptionIV).isNotNull();
    assertThat(segment.encryptionIV).ignoringCase().isEqualTo("A7A");
    assertThat(segment.byteRangeLength).isEqualTo(51740);
    assertThat(segment.byteRangeOffset).isEqualTo(2147586650L);
    assertThat(segment.url).isEqualTo("https://priv.example.com/fileSequence2682.ts");

    segment = segments.get(4);
    assertThat(segment.relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(segment.durationUs).isEqualTo(7975000);
    assertThat(segment.title).isEqualTo("");
    assertThat(segment.fullSegmentEncryptionKeyUri)
        .isEqualTo("https://priv.example.com/key.php?r=2682");
    // 0xA7B == 2683.
    assertThat(segment.encryptionIV).isNotNull();
    assertThat(segment.encryptionIV).ignoringCase().isEqualTo("A7B");
    assertThat(segment.byteRangeLength).isEqualTo(C.LENGTH_UNSET);
    assertThat(segment.byteRangeOffset).isEqualTo(0);
    assertThat(segment.url).isEqualTo("https://priv.example.com/fileSequence2683.ts");

    segment = segments.get(5);
    assertThat(segment.durationUs).isEqualTo(2002000);
  }

  @Test
  public void parseMediaPlaylist_withByteRanges() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "\n"
            + "#EXT-X-BYTERANGE:200@100\n"
            + "#EXT-X-MAP:URI=\"stream.mp4\"\n"
            + "#EXTINF:5,\n"
            + "#EXT-X-BYTERANGE:400\n"
            + "stream.mp4\n"
            + "#EXTINF:5,\n"
            + "#EXT-X-BYTERANGE:500\n"
            + "stream.mp4\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:5,\n"
            + "segment.mp4\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsPlaylist playlist = new HlsPlaylistParser().parse(playlistUri, inputStream);

    HlsMediaPlaylist mediaPlaylist = (HlsMediaPlaylist) playlist;
    List<Segment> segments = mediaPlaylist.segments;

    assertThat(segments).isNotNull();
    assertThat(segments).hasSize(3);

    Segment segment = segments.get(0);
    assertThat(segment.initializationSegment.byteRangeOffset).isEqualTo(100);
    assertThat(segment.initializationSegment.byteRangeLength).isEqualTo(200);
    assertThat(segment.byteRangeOffset).isEqualTo(300);
    assertThat(segment.byteRangeLength).isEqualTo(400);

    segment = segments.get(1);
    assertThat(segment.byteRangeOffset).isEqualTo(700);
    assertThat(segment.byteRangeLength).isEqualTo(500);

    segment = segments.get(2);
    assertThat(segment.initializationSegment.byteRangeOffset).isEqualTo(0);
    assertThat(segment.initializationSegment.byteRangeLength).isEqualTo(C.LENGTH_UNSET);
    assertThat(segment.byteRangeOffset).isEqualTo(0);
    assertThat(segment.byteRangeLength).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void parseSampleAesMethod() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/1.ts\n"
            + "\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,URI="
            + "\"data:text/plain;base64,VGhpcyBpcyBhbiBlYXN0ZXIgZWdn\","
            + "IV=0x9358382AEB449EE23C3D809DA0B9CCD3,KEYFORMATVERSIONS=\"1\","
            + "KEYFORMAT=\"com.widevine\",IV=0x1566B\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/2.ts\n"
            + "#EXT-X-ENDLIST\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo(C.CENC_TYPE_cbcs);
    assertThat(playlist.protectionSchemes.get(0).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.protectionSchemes.get(0).hasData()).isFalse();

    assertThat(playlist.segments.get(0).drmInitData).isNull();

    assertThat(playlist.segments.get(1).drmInitData.get(0).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.segments.get(1).drmInitData.get(0).hasData()).isTrue();
  }

  @Test
  public void parseSampleAesCencMethod() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/1.ts\n"
            + "\n"
            + "#EXT-X-KEY:URI=\"data:text/plain;base64,VGhpcyBpcyBhbiBlYXN0ZXIgZWdn\","
            + "IV=0x9358382AEB449EE23C3D809DA0B9CCD3,KEYFORMATVERSIONS=\"1\","
            + "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\","
            + "IV=0x1566B,METHOD=SAMPLE-AES-CENC \n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/2.ts\n"
            + "#EXT-X-ENDLIST\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo(C.CENC_TYPE_cenc);
    assertThat(playlist.protectionSchemes.get(0).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.protectionSchemes.get(0).hasData()).isFalse();
  }

  @Test
  public void parseSampleAesCtrMethod() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/1.ts\n"
            + "\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="
            + "\"data:text/plain;base64,VGhpcyBpcyBhbiBlYXN0ZXIgZWdn\","
            + "IV=0x9358382AEB449EE23C3D809DA0B9CCD3,KEYFORMATVERSIONS=\"1\","
            + "KEYFORMAT=\"com.widevine\",IV=0x1566B\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/2.ts\n"
            + "#EXT-X-ENDLIST\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo(C.CENC_TYPE_cenc);
    assertThat(playlist.protectionSchemes.get(0).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.protectionSchemes.get(0).hasData()).isFalse();
  }

  @Test
  public void parseMediaPlaylist_withPartMediaInformation_succeeds() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-SERVER-CONTROL:PART-HOLD-BACK=1.234\n"
            + "#EXT-X-PART-INF:PART-TARGET=0.5\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2019-02-14T02:13:36.106Z\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence266.mp4";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.partTargetDurationUs).isEqualTo(500000);
  }

  @Test
  public void parseMediaPlaylist_withoutServerControl_serverControlDefaultValues()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXTINF:8,\n"
            + "https://priv.example.com/1.ts\n"
            + "#EXT-X-ENDLIST\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(playlist.serverControl.canBlockReload).isFalse();
    assertThat(playlist.serverControl.partHoldBackUs).isEqualTo(C.TIME_UNSET);
    assertThat(playlist.serverControl.holdBackUs).isEqualTo(C.TIME_UNSET);
    assertThat(playlist.serverControl.skipUntilUs).isEqualTo(C.TIME_UNSET);
    assertThat(playlist.serverControl.canSkipDateRanges).isFalse();
  }

  @Test
  public void parseMediaPlaylist_withServerControl_succeeds() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,HOLD-BACK=18.5,PART-HOLD-BACK=1.234,"
            + "CAN-SKIP-UNTIL=24.0,CAN-SKIP-DATERANGES=YES\n"
            + "#EXT-X-PART-INF:PART-TARGET=0.5\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2019-02-14T02:13:36.106Z\n"
            + "#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence266.mp4";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.serverControl.canBlockReload).isTrue();
    assertThat(playlist.serverControl.partHoldBackUs).isEqualTo(1234000);
    assertThat(playlist.serverControl.holdBackUs).isEqualTo(18500000);
    assertThat(playlist.serverControl.skipUntilUs).isEqualTo(24000000);
    assertThat(playlist.serverControl.canSkipDateRanges).isTrue();
  }

  @Test
  public void parseMediaPlaylist_withSkippedSegments_correctlyMergedSegments() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String previousPlaylistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:1234\n"
            + "#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0\n"
            + "#EXT-X-MEDIA-SEQUENCE:263\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence264.mp4\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence265.mp4\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence266.mp4";
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:1234\n"
            + "#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0\n"
            + "#EXT-X-MEDIA-SEQUENCE:265\n"
            + "#EXT-X-SKIP:SKIPPED-SEGMENTS=1\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence266.mp4"
            + "#EXTINF:4.00008,\n"
            + "fileSequence267.mp4\n";
    InputStream previousInputStream =
        new ByteArrayInputStream(Util.getUtf8Bytes(previousPlaylistString));
    HlsMediaPlaylist previousPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, previousInputStream);
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(HlsMultivariantPlaylist.EMPTY, previousPlaylist)
                .parse(playlistUri, inputStream);

    assertThat(playlist.segments).hasSize(3);
    assertThat(playlist.segments.get(1).relativeStartTimeUs).isEqualTo(4000080);
    assertThat(previousPlaylist.segments.get(0).relativeDiscontinuitySequence).isEqualTo(0);
    assertThat(previousPlaylist.segments.get(1).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(previousPlaylist.segments.get(2).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(0).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(1).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(2).relativeDiscontinuitySequence).isEqualTo(1);
  }

  @Test
  public void parseMediaPlaylist_withSkippedSegments_correctlyMergedParts() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String previousPlaylistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:1234\n"
            + "#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0\n"
            + "#EXT-X-MEDIA-SEQUENCE:264\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part264.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part264.2.ts\"\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence264.mp4\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part265.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part265.2.ts\"\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence265.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.2.ts\"\n"
            + "#EXTINF:4.00008,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"";
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:1234\n"
            + "#EXT-X-SERVER-CONTROL:CAN-SKIP-UNTIL=24.0\n"
            + "#EXT-X-MEDIA-SEQUENCE:265\n"
            + "#EXT-X-SKIP:SKIPPED-SEGMENTS=2\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"";
    InputStream previousInputStream =
        new ByteArrayInputStream(Util.getUtf8Bytes(previousPlaylistString));
    HlsMediaPlaylist previousPlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, previousInputStream);
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(HlsMultivariantPlaylist.EMPTY, previousPlaylist)
                .parse(playlistUri, inputStream);

    assertThat(playlist.segments).hasSize(2);
    assertThat(playlist.segments.get(0).relativeStartTimeUs).isEqualTo(0);
    assertThat(playlist.segments.get(0).parts.get(0).relativeStartTimeUs).isEqualTo(0);
    assertThat(playlist.segments.get(0).parts.get(0).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(0).parts.get(1).relativeStartTimeUs).isEqualTo(2000000);
    assertThat(playlist.segments.get(0).parts.get(1).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(1).relativeStartTimeUs).isEqualTo(4000080);
    assertThat(playlist.segments.get(1).parts.get(0).relativeStartTimeUs).isEqualTo(4000080);
    assertThat(playlist.segments.get(1).parts.get(1).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.segments.get(1).parts.get(1).relativeStartTimeUs).isEqualTo(6000080);
    assertThat(playlist.segments.get(1).parts.get(1).relativeDiscontinuitySequence).isEqualTo(1);
    assertThat(playlist.trailingParts.get(0).relativeStartTimeUs).isEqualTo(8000160);
    assertThat(playlist.trailingParts.get(0).relativeDiscontinuitySequence).isEqualTo(1);
  }

  @Test
  public void parseMediaPlaylist_withParts_parsesPartWithAllAttributes() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,GAP=YES,"
            + "INDEPENDENT=YES,URI=\"part267.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,BYTERANGE=\"1000@1234\",URI=\"part267.2.ts\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence267.ts\n"
            + "#EXT-X-PART:DURATION=2.00000, BYTERANGE=\"1000@1234\",URI=\"part268.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part268.2.ts\", BYTERANGE=\"1000\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.segments.get(1).parts).hasSize(2);
    assertThat(playlist.trailingParts).hasSize(2);

    HlsMediaPlaylist.Part firstPart = playlist.segments.get(1).parts.get(0);
    assertThat(firstPart.byteRangeLength).isEqualTo(C.LENGTH_UNSET);
    assertThat(firstPart.byteRangeOffset).isEqualTo(0);
    assertThat(firstPart.durationUs).isEqualTo(2_000_000);
    assertThat(firstPart.relativeStartTimeUs).isEqualTo(playlist.segments.get(0).durationUs);
    assertThat(firstPart.isIndependent).isTrue();
    assertThat(firstPart.isPreload).isFalse();
    assertThat(firstPart.hasGapTag).isTrue();
    assertThat(firstPart.url).isEqualTo("part267.1.ts");
    HlsMediaPlaylist.Part secondPart = playlist.segments.get(1).parts.get(1);
    assertThat(secondPart.byteRangeLength).isEqualTo(1000);
    assertThat(secondPart.byteRangeOffset).isEqualTo(1234);
    // Assert trailing parts.
    HlsMediaPlaylist.Part thirdPart = playlist.trailingParts.get(0);
    // Assert tailing parts.
    assertThat(thirdPart.byteRangeLength).isEqualTo(1000);
    assertThat(thirdPart.byteRangeOffset).isEqualTo(1234);
    assertThat(thirdPart.relativeStartTimeUs).isEqualTo(8_000_000);
    HlsMediaPlaylist.Part lastPart = playlist.trailingParts.get(1);
    assertThat(lastPart.relativeStartTimeUs).isEqualTo(10_000_000);
    assertThat(lastPart.hasGapTag).isFalse();
    assertThat(lastPart.isIndependent).isFalse();
    assertThat(lastPart.byteRangeLength).isEqualTo(1000);
    assertThat(lastPart.byteRangeOffset).isEqualTo(2234);
  }

  @Test
  public void parseMediaPlaylist_withPartAndAesPlayReadyKey_correctDrmInitData()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"com.microsoft.playready\","
            + "URI=\"data:text/plain;base64,RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.ts\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo("cbcs");
    HlsMediaPlaylist.Part part = playlist.trailingParts.get(0);
    assertThat(part.drmInitData.schemeType).isEqualTo("cbcs");
    assertThat(part.drmInitData.schemeDataCount).isEqualTo(1);
    assertThat(part.drmInitData.get(0).uuid).isEqualTo(C.PLAYREADY_UUID);
    assertThat(part.drmInitData.get(0).data)
        .isEqualTo(
            PsshAtomUtil.buildPsshAtom(
                C.PLAYREADY_UUID,
                Base64.decode("RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==", Base64.DEFAULT)));
  }

  @Test
  public void parseMediaPlaylist_withPartAndAesPlayReadyWithOutPrecedingSegment_correctDrmInitData()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"com.microsoft.playready\","
            + "URI=\"data:text/plain;base64,RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments).isEmpty();
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo("cbcs");
    HlsMediaPlaylist.Part part = playlist.trailingParts.get(0);
    assertThat(part.drmInitData.schemeType).isEqualTo("cbcs");
    assertThat(part.drmInitData.schemeDataCount).isEqualTo(1);
    assertThat(part.drmInitData.get(0).uuid).isEqualTo(C.PLAYREADY_UUID);
    assertThat(part.drmInitData.get(0).data)
        .isEqualTo(
            PsshAtomUtil.buildPsshAtom(
                C.PLAYREADY_UUID,
                Base64.decode("RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==", Base64.DEFAULT)));
  }

  @Test
  public void parseMediaPlaylist_withPartAndAes128_partHasDrmKeyUriAndIV() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"identity\""
            + ", IV=0x410C8AC18AA42EFA18B5155484F5FC34,URI=\"fake://foo.bar/uri\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.ts\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    HlsMediaPlaylist.Part part = playlist.trailingParts.get(0);
    assertThat(playlist.protectionSchemes).isNull();
    assertThat(part.drmInitData).isNull();
    assertThat(part.fullSegmentEncryptionKeyUri).isEqualTo("fake://foo.bar/uri");
    assertThat(part.encryptionIV).isEqualTo("0x410C8AC18AA42EFA18B5155484F5FC34");
  }

  @Test
  public void parseMediaPlaylist_withPartAndAes128WithoutPrecedingSegment_partHasDrmKeyUriAndIV()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"identity\""
            + ", IV=0x410C8AC18AA42EFA18B5155484F5FC34,URI=\"fake://foo.bar/uri\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments).isEmpty();
    HlsMediaPlaylist.Part part = playlist.trailingParts.get(0);
    assertThat(playlist.protectionSchemes).isNull();
    assertThat(part.drmInitData).isNull();
    assertThat(part.fullSegmentEncryptionKeyUri).isEqualTo("fake://foo.bar/uri");
    assertThat(part.encryptionIV).isEqualTo("0x410C8AC18AA42EFA18B5155484F5FC34");
  }

  @Test
  public void parseMediaPlaylist_withPreloadHintTypePart_hasPreloadPartWithAllAttributes()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-MAP:URI=\"map.mp4\"\n"
            + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"identity\""
            + ", IV=0x410C8AC18AA42EFA18B5155484F5FC34,URI=\"fake://foo.bar/uri\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,BYTERANGE-START=1234,BYTERANGE-LENGTH=1000,"
            + "URI=\"filePart267.2.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.trailingParts).hasSize(2);
    HlsMediaPlaylist.Part preloadPart = playlist.trailingParts.get(1);
    assertThat(preloadPart.durationUs).isEqualTo(0L);
    assertThat(preloadPart.url).isEqualTo("filePart267.2.ts");
    assertThat(preloadPart.byteRangeLength).isEqualTo(1000);
    assertThat(preloadPart.byteRangeOffset).isEqualTo(1234);
    assertThat(preloadPart.initializationSegment.url).isEqualTo("map.mp4");
    assertThat(preloadPart.encryptionIV).isEqualTo("0x410C8AC18AA42EFA18B5155484F5FC34");
    assertThat(preloadPart.isPreload).isTrue();
  }

  @Test
  public void parseMediaPlaylist_withMultiplePreloadHintTypeParts_picksOnlyFirstPreloadPart()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.2.ts\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.3.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.trailingParts).hasSize(2);
    assertThat(playlist.trailingParts.get(1).url).isEqualTo("filePart267.2.ts");
    assertThat(playlist.trailingParts.get(1).isPreload).isTrue();
  }

  @Test
  public void parseMediaPlaylist_withUnboundedPreloadHintTypePart_ignoresPreloadPart()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.2.ts,BYTERANGE-START=0\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.trailingParts).hasSize(1);
    assertThat(Iterables.getLast(playlist.trailingParts).url).isEqualTo("part267.1.ts");
    assertThat(Iterables.getLast(playlist.trailingParts).isPreload).isFalse();
  }

  @Test
  public void parseMediaPlaylist_withPreloadHintTypePartAndAesPlayReadyKey_inheritsDrmInitData()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"com.microsoft.playready\","
            + "URI=\"data:text/plain;base64,RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.2.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.trailingParts).hasSize(1);
    assertThat(playlist.protectionSchemes.schemeDataCount).isEqualTo(1);
    HlsMediaPlaylist.Part preloadPart = playlist.trailingParts.get(0);
    assertThat(preloadPart.drmInitData.schemeType).isEqualTo("cbcs");
    assertThat(preloadPart.drmInitData.schemeDataCount).isEqualTo(1);
    assertThat(preloadPart.drmInitData.get(0).uuid).isEqualTo(C.PLAYREADY_UUID);
    assertThat(preloadPart.drmInitData.get(0).data)
        .isEqualTo(
            PsshAtomUtil.buildPsshAtom(
                C.PLAYREADY_UUID,
                Base64.decode("RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==", Base64.DEFAULT)));
  }

  @Test
  public void parseMediaPlaylist_withPreloadHintTypePartAndNewAesPlayReadyKey_correctDrmInitData()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"com.microsoft.playready\","
            + "URI=\"data:text/plain;base64,RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.2.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.trailingParts).hasSize(1);
    assertThat(playlist.protectionSchemes.schemeDataCount).isEqualTo(1);
    HlsMediaPlaylist.Part preloadPart = playlist.trailingParts.get(0);
    assertThat(preloadPart.drmInitData.schemeType).isEqualTo("cbcs");
    assertThat(preloadPart.drmInitData.schemeDataCount).isEqualTo(1);
    assertThat(preloadPart.drmInitData.get(0).uuid).isEqualTo(C.PLAYREADY_UUID);
    assertThat(preloadPart.drmInitData.get(0).data)
        .isEqualTo(
            PsshAtomUtil.buildPsshAtom(
                C.PLAYREADY_UUID,
                Base64.decode("RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==", Base64.DEFAULT)));
  }

  @Test
  public void parseMediaPlaylist_withPreloadHintTypePartAndAes128_partHasDrmKeyUriAndIV()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"identity\""
            + ", IV=0x410C8AC18AA42EFA18B5155484F5FC34,URI=\"fake://foo.bar/uri\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.2.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.segments.get(0).parts).isEmpty();
    assertThat(playlist.trailingParts).hasSize(1);
    HlsMediaPlaylist.Part preloadPart = playlist.trailingParts.get(0);
    assertThat(preloadPart.drmInitData).isNull();
    assertThat(preloadPart.fullSegmentEncryptionKeyUri).isEqualTo("fake://foo.bar/uri");
    assertThat(preloadPart.encryptionIV).isEqualTo("0x410C8AC18AA42EFA18B5155484F5FC34");
  }

  @Test
  public void parseMediaPlaylist_withRenditionReportWithoutPartTargetDuration_lastPartIndexUnset()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-MSN=100\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(100);
    assertThat(report0.lastPartIndex).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportWithoutPartTargetDurationWithoutLastMsn_sameLastMsnAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(266);
    assertThat(report0.lastPartIndex).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void parseMediaPlaylist_withRenditionReportLowLatency_parseAllAttributes()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-MSN=100,LAST-PART=2\n"
            + "#EXT-X-RENDITION-REPORT:"
            + "URI=\"http://foo.bar/rendition2.m3u8\",LAST-MSN=1000,LAST-PART=3\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(2);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(100);
    assertThat(report0.lastPartIndex).isEqualTo(2);
    HlsMediaPlaylist.RenditionReport report2 =
        playlist.renditionReports.get(Uri.parse("http://foo.bar/rendition2.m3u8"));
    assertThat(report2.lastMediaSequence).isEqualTo(1000);
    assertThat(report2.lastPartIndex).isEqualTo(3);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportLowLatencyWithoutLastMsn_sameMsnAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-PART=2\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(267);
    assertThat(report0.lastPartIndex).isEqualTo(2);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportBeforeSegmentsWithoutPartTargetDurationWithoutLastMsn_sameLastMsnAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report.lastMediaSequence).isEqualTo(266);
    assertThat(report.lastPartIndex).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportBeforeSegementsDefaultMsn_sameMsnAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-PART=2\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report.lastMediaSequence).isEqualTo(267);
    assertThat(report.lastPartIndex).isEqualTo(2);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportBeforeSegementsDefaultLastPart_sameLastPartIndexAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-MSN=267\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report.lastMediaSequence).isEqualTo(267);
    assertThat(report.lastPartIndex).isEqualTo(1);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportBeforeSegements_sameMsnAndLastPartIndexAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.1.ts\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report.lastMediaSequence).isEqualTo(267);
    assertThat(report.lastPartIndex).isEqualTo(1);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportLowLatencyWithoutLastPartIndex_sameLastPartIndexAsCurrentPlaylist()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-MSN=267\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(267);
    assertThat(report0.lastPartIndex).isEqualTo(0);
  }

  @Test
  public void
      parseMediaPlaylist_withRenditionReportLowLatencyWithoutLastPartIndex_ignoredPreloadPart()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part267.0.ts\"\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.1.ts\"\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\",LAST-MSN=267\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.trailingParts).hasSize(2);
    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(267);
    assertThat(report0.lastPartIndex).isEqualTo(0);
  }

  @Test
  public void parseMediaPlaylist_withRenditionReportLowLatencyFullSegment_rollingPartIndexUriParam()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-PART-INF:PART-TARGET=1\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:266\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.0.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.1.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.2.ts\"\n"
            + "#EXT-X-PART:DURATION=2.00000,URI=\"part266.3.ts\"\n"
            + "#EXTINF:4.00000,\n"
            + "fileSequence266.mp4\n"
            + "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"filePart267.0.ts\"\n"
            + "#EXT-X-RENDITION-REPORT:URI=\"/rendition0.m3u8\"\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.renditionReports).hasSize(1);
    HlsMediaPlaylist.RenditionReport report0 =
        playlist.renditionReports.get(Uri.parse("https://example.com/rendition0.m3u8"));
    assertThat(report0.lastMediaSequence).isEqualTo(266);
    assertThat(report0.lastPartIndex).isEqualTo(3);
  }

  @Test
  public void parseMediaPlaylist_withInterstitialDateRanges() throws IOException, ParseException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad1\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "END-DATE=\"2020-01-02T21:55:59.000Z\","
            + "DURATION=15.0,"
            + "PLANNED-DURATION=15.13,"
            + "CUE=\" PRE \","
            + "END-ON-NEXT=YES, "
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-RESUME-OFFSET=-1.1, "
            + "X-PLAYOUT-LIMIT=15.112, "
            + "X-SNAP=\"    IN  , OUT  \", "
            + "X-RESTRICT=\"SKIP ,  JUMP\","
            + "X-CONTENT-MAY-VARY=\"NO\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"PRIMARY\","
            + "X-GOOGLE-TEST-TEXT=\"Value at the end\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "X-GOOGLE-TEST-HEX=0XAB10A,"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T22:55:44.000Z\","
            + "END-DATE=\"2020-01-02T22:55:49.120Z\","
            + "DURATION=5.12,"
            + "PLANNED-DURATION=5.11,"
            + "CUE=\"  POST,    ONCE\","
            + "X-GOOGLE-TEST-DOUBLE1=12.123,"
            + "X-CONTENT-MAY-VARY=\"YES\","
            + "X-TIMELINE-OCCUPIES=\"POINT\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "X-ASSET-LIST=\"http://example.com/ad2-assets.json\""
            + "X-GOOGLE-TEST-DOUBLE2=1\n";
    long playlistStartTimeUs = msToUs(Util.parseXsDateTime("2020-01-02T21:55:40.000Z"));
    Interstitial interstitial1 =
        new Interstitial(
            /* id= */ "ad1",
            /* assetUri= */ Uri.parse("http://example.com/ad1.m3u8"),
            /* assetListUri= */ null,
            /* startDateUnixUs= */ playlistStartTimeUs + 4_000_000L,
            /* endDateUnixUs= */ playlistStartTimeUs + 4_000_000L + 15_000_000L,
            /* durationUs= */ 15_000_000L,
            /* plannedDurationUs= */ 15_130_000L,
            /* cue= */ ImmutableList.of(CUE_TRIGGER_PRE),
            /* endOnNext= */ true,
            /* resumeOffsetUs= */ -1_100_000L,
            /* playoutLimitUs= */ 15_112_000L,
            ImmutableList.of(SNAP_TYPE_IN, SNAP_TYPE_OUT),
            ImmutableList.of(NAVIGATION_RESTRICTION_SKIP, NAVIGATION_RESTRICTION_JUMP),
            /* clientDefinedAttributes= */ ImmutableList.of(
                new HlsMediaPlaylist.ClientDefinedAttribute(
                    "X-GOOGLE-TEST-TEXT",
                    "Value at the end",
                    HlsMediaPlaylist.ClientDefinedAttribute.TYPE_TEXT)),
            /* contentMayVary= */ false,
            /* timelineOccupies= */ Interstitial.TIMELINE_OCCUPIES_RANGE,
            /* timelineStyle= */ Interstitial.TIMELINE_STYLE_PRIMARY,
            /* skipControlOffsetUs= */ C.TIME_UNSET,
            /* skipControlDurationUs= */ C.TIME_UNSET,
            /* skipControlLabelId= */ null);
    Interstitial interstitial2 =
        new Interstitial(
            /* id= */ "ad2",
            /* assetUri= */ null,
            /* assetListUri= */ Uri.parse("http://example.com/ad2-assets.json"),
            /* startDateUnixUs= */ playlistStartTimeUs + 4_000_000L + (60L * 60L * 1_000_000L),
            /* endDateUnixUs= */ playlistStartTimeUs
                + 4_000_000L
                + 5_120_000L
                + (60L * 60L * 1_000_000L),
            /* durationUs= */ 5_120_000L,
            /* plannedDurationUs= */ 5_110_000L,
            ImmutableList.of(CUE_TRIGGER_POST, CUE_TRIGGER_ONCE),
            /* endOnNext= */ false,
            /* resumeOffsetUs= */ C.TIME_UNSET,
            /* playoutLimitUs= */ C.TIME_UNSET,
            /* snapTypes= */ ImmutableList.of(),
            /* restrictions= */ ImmutableList.of(),
            /* clientDefinedAttributes= */ ImmutableList.of(
                new HlsMediaPlaylist.ClientDefinedAttribute("X-GOOGLE-TEST-DOUBLE1", 12.123d),
                new HlsMediaPlaylist.ClientDefinedAttribute("X-GOOGLE-TEST-DOUBLE2", 1d),
                new HlsMediaPlaylist.ClientDefinedAttribute(
                    "X-GOOGLE-TEST-HEX",
                    "0XAB10A",
                    HlsMediaPlaylist.ClientDefinedAttribute.TYPE_HEX_TEXT)),
            /* contentMayVary= */ true,
            /* timelineOccupies= */ Interstitial.TIMELINE_OCCUPIES_POINT,
            /* timelineStyle= */ Interstitial.TIMELINE_STYLE_HIGHLIGHT,
            /* skipControlOffsetUs= */ C.TIME_UNSET,
            /* skipControlDurationUs= */ C.TIME_UNSET,
            /* skipControlLabelId= */ null);
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.interstitials).containsExactly(interstitial1, interstitial2).inOrder();
  }

  @Test
  public void parseMediaPlaylist_withInterstitialsVodWithAssetUriOnly_correctlyParseAllAttributes()
      throws IOException {
    Uri playlistUri = Uri.parse("http://example.com/interstitials_vod_asset_uri.m3u8");
    InputStream inputStream =
        TestUtil.getInputStream(
            ApplicationProvider.getApplicationContext(),
            "media/hls/interstitials/interstitials_vod_asset_uri.m3u8");
    Interstitial interstitial1 =
        new Interstitial(
            "preroll",
            /* assetUri= */ Uri.parse("https://example.com/0.m3u8?parse=1"),
            /* assetListUri= */ null,
            /* startDateUnixUs= */ msToUs(Util.parseXsDateTime("2024-11-11T04:35:19.353-08:00")),
            /* endDateUnixUs= */ C.TIME_UNSET,
            /* durationUs= */ C.TIME_UNSET,
            /* plannedDurationUs= */ C.TIME_UNSET,
            /* cue= */ ImmutableList.of("PRE"),
            /* endOnNext= */ false,
            /* resumeOffsetUs= */ 0L,
            /* playoutLimitUs= */ C.TIME_UNSET,
            /* snapTypes= */ ImmutableList.of(),
            /* restrictions= */ ImmutableList.of("JUMP", "SKIP"),
            /* clientDefinedAttributes= */ ImmutableList.of(),
            /* contentMayVary= */ true,
            /* timelineOccupies= */ Interstitial.TIMELINE_OCCUPIES_POINT,
            /* timelineStyle= */ Interstitial.TIMELINE_STYLE_HIGHLIGHT,
            /* skipControlOffsetUs= */ C.TIME_UNSET,
            /* skipControlDurationUs= */ C.TIME_UNSET,
            /* skipControlLabelId= */ null);
    Interstitial interstitial2 =
        new Interstitial(
            "midroll-1",
            /* assetUri= */ Uri.parse("https://example.com/1.m3u8?parse=1"),
            /* assetListUri= */ null,
            /* startDateUnixUs= */ msToUs(Util.parseXsDateTime("2024-11-11T04:35:34.353-08:00")),
            /* endDateUnixUs= */ C.TIME_UNSET,
            /* durationUs= */ C.TIME_UNSET,
            /* plannedDurationUs= */ C.TIME_UNSET,
            /* cue= */ ImmutableList.of(),
            /* endOnNext= */ false,
            /* resumeOffsetUs= */ 0L,
            /* playoutLimitUs= */ C.TIME_UNSET,
            /* snapTypes= */ ImmutableList.of(),
            /* restrictions= */ ImmutableList.of("JUMP", "SKIP"),
            /* clientDefinedAttributes= */ ImmutableList.of(),
            /* contentMayVary= */ true,
            /* timelineOccupies= */ Interstitial.TIMELINE_OCCUPIES_POINT,
            /* timelineStyle= */ Interstitial.TIMELINE_STYLE_HIGHLIGHT,
            /* skipControlOffsetUs= */ C.TIME_UNSET,
            /* skipControlDurationUs= */ C.TIME_UNSET,
            /* skipControlLabelId= */ null);
    Interstitial interstitial3 =
        new Interstitial(
            "postroll",
            /* assetUri= */ Uri.parse("https://example.com/2.m3u8?parse=1"),
            /* assetListUri= */ null,
            /* startDateUnixUs= */ msToUs(Util.parseXsDateTime("2024-11-11T04:36:31.353-08:00")),
            /* endDateUnixUs= */ C.TIME_UNSET,
            /* durationUs= */ C.TIME_UNSET,
            /* plannedDurationUs= */ C.TIME_UNSET,
            /* cue= */ ImmutableList.of("POST"),
            /* endOnNext= */ false,
            /* resumeOffsetUs= */ 0L,
            /* playoutLimitUs= */ C.TIME_UNSET,
            /* snapTypes= */ ImmutableList.of(),
            /* restrictions= */ ImmutableList.of(
                NAVIGATION_RESTRICTION_JUMP, NAVIGATION_RESTRICTION_SKIP),
            /* clientDefinedAttributes= */ ImmutableList.of(),
            /* contentMayVary= */ true,
            /* timelineOccupies= */ Interstitial.TIMELINE_OCCUPIES_POINT,
            /* timelineStyle= */ Interstitial.TIMELINE_STYLE_HIGHLIGHT,
            /* skipControlOffsetUs= */ C.TIME_UNSET,
            /* skipControlDurationUs= */ C.TIME_UNSET,
            /* skipControlLabelId= */ null);

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.interstitials)
        .containsExactly(interstitial1, interstitial2, interstitial3)
        .inOrder();
    assertThat(playlist.startTimeUs)
        .isEqualTo(msToUs(Util.parseXsDateTime("2024-11-11T04:35:19.353-08:00")));
  }

  @Test
  public void parseMediaPlaylist_withDuration_durationSetCorrectly() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=2.222,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(mediaPlaylist.interstitials.get(0).durationUs).isEqualTo(2_222_000L);
  }

  @Test
  public void parseMediaPlaylist_withInvalidDuration_durationUnset() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=-2.222," // negative value
            + "PLANNED-DURATION=- 2.222," // negative value
            + "X-PLAYOUT-LIMIT=-2.222," // negative value
            + "X-RESUME-OFFSET=24.953741497,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-1\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "DURATION=\"2.222\"," // string value
            + "PLANNED-DURATION=\"2.222\"," // string value
            + "X-PLAYOUT-LIMIT=\"2.222\"," // string value
            + "X-RESUME-OFFSET=-24.953741497,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(mediaPlaylist.interstitials.get(0).id).isEqualTo("ad0-0");
    assertThat(mediaPlaylist.interstitials.get(0).durationUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(0).plannedDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(0).playoutLimitUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(0).resumeOffsetUs).isEqualTo(24953741L);
    assertThat(mediaPlaylist.interstitials.get(1).id).isEqualTo("ad0-1");
    assertThat(mediaPlaylist.interstitials.get(1).durationUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(1).plannedDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(1).playoutLimitUs).isEqualTo(C.TIME_UNSET);
    assertThat(mediaPlaylist.interstitials.get(1).resumeOffsetUs).isEqualTo(-24953741L);
  }

  @Test
  public void parseMediaPlaylist_withDurationAfterPlannedDuration_durationSetCorrectly()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "PLANNED-DURATION=1.111,"
            + "DURATION=2.222,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(mediaPlaylist.interstitials.get(0).durationUs).isEqualTo(2_222_000L);
  }

  @Test
  public void parseMediaPlaylist_withInterstitialWithSkipControlAttributes_correctlyParsed()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad1\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=5.5,"
            + "X-SKIP-CONTROL-DURATION=10.0,"
            + "X-SKIP-CONTROL-LABEL-ID=\"skip-label\""
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad2\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=15.5,"
            + "X-SKIP-CONTROL-DURATION=110.0,"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad3\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.interstitials).hasSize(3);
    Interstitial interstitial = playlist.interstitials.get(0);
    assertThat(interstitial.skipControlOffsetUs).isEqualTo(5_500_000L);
    assertThat(interstitial.skipControlDurationUs).isEqualTo(10_000_000L);
    assertThat(interstitial.skipControlLabelId).isEqualTo("skip-label");
    Interstitial interstitial2 = playlist.interstitials.get(1);
    assertThat(interstitial2.skipControlOffsetUs).isEqualTo(15_500_000L);
    assertThat(interstitial2.skipControlDurationUs).isEqualTo(110_000_000L);
    assertThat(interstitial2.skipControlLabelId).isNull();
    Interstitial interstitial3 = playlist.interstitials.get(2);
    assertThat(interstitial3.skipControlOffsetUs).isEqualTo(C.TIME_UNSET);
    assertThat(interstitial3.skipControlDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(interstitial3.skipControlLabelId).isNull();
  }

  @Test
  public void parseMediaPlaylist_withInterstitialWithInvalidSkipControlAttributes_isRobust()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad1\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=invalid," // NaN
            + "X-SKIP-CONTROL-DURATION=10.0"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad2\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=5.5,"
            + "X-SKIP-CONTROL-DURATION=invalid" // NaN
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad3\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=-5.5," // negative value
            + "X-SKIP-CONTROL-DURATION=10.0"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "ID=\"ad4\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "X-SKIP-CONTROL-OFFSET=5.5,"
            + "X-SKIP-CONTROL-DURATION=-10.0" // negative value
            + "\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.interstitials).hasSize(4);
    Interstitial interstitial1 = playlist.interstitials.get(0);
    assertThat(interstitial1.skipControlOffsetUs).isEqualTo(C.TIME_UNSET);
    assertThat(interstitial1.skipControlDurationUs).isEqualTo(10_000_000L);
    Interstitial interstitial2 = playlist.interstitials.get(1);
    assertThat(interstitial2.skipControlOffsetUs).isEqualTo(5_500_000L);
    assertThat(interstitial2.skipControlDurationUs).isEqualTo(C.TIME_UNSET);
    Interstitial interstitial3 = playlist.interstitials.get(2);
    assertThat(interstitial3.skipControlOffsetUs).isEqualTo(C.TIME_UNSET);
    assertThat(interstitial3.skipControlDurationUs).isEqualTo(10_000_000L);
    Interstitial interstitial4 = playlist.interstitials.get(3);
    assertThat(interstitial4.skipControlOffsetUs).isEqualTo(5_500_000L);
    assertThat(interstitial4.skipControlDurationUs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void parseMediaPlaylist_withDurationBeforePlannedDuration_durationSetCorrectly()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "DURATION=2.234,"
            + "ID=\"ad0-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:41.123Z\","
            + "PLANNED-DURATION=3.456,"
            + "X-ASSET-URI=\"http://example.com/media-0-0.m3u8\""
            + "\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist mediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(mediaPlaylist.interstitials.get(0).durationUs).isEqualTo(2_234_000L);
  }

  @Test
  public void parseMediaPlaylist_withInterstitialWithoutId_throwsParserException() {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "CLASS=\"com.apple.hls.interstitial\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    assertThrows(
        ParserException.class,
        () ->
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString))));
  }

  @Test
  public void parseMediaPlaylist_withDateRangeWithUnknownClass_dateRangeIgnored()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"some.other.class\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials).isEmpty();
  }

  @Test
  public void parseMediaPlaylist_withDateRangeWithMissingClass_dateRangeIgnored()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "X-ASSET-URI=\"http://example.com/ad1.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials).isEmpty();
  }

  @Test
  public void parseMediaPlaylist_interstitialWithoutStartDate_ignored() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "X-ASSET-LIST=\"http://example.com/ad2-assets.json\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials).isEmpty();
  }

  @Test
  public void parseMediaPlaylist_withInterstitialWithAssetUriAndList_interstitialIgnored()
      throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\","
            + "X-ASSET-URI=\"http://example.com/media.m3u8\","
            + "X-ASSET-LIST=\"http://example.com/ad2-assets.json\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist hlsMediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(hlsMediaPlaylist.interstitials).isEmpty();
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialWithNeitherAssetUriNorAssetList_interstitialIgnored()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();

    HlsMediaPlaylist hlsMediaPlaylist =
        (HlsMediaPlaylist)
            hlsPlaylistParser.parse(
                playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(hlsMediaPlaylist.interstitials).isEmpty();
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialWithUnknownRestrictions_ignoredForForwardCompatibility()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "X-RESTRICT=\"SKIP , FLY , ROW , JUMP\","
            + "X-ASSET-URI=\"http://example.com/media.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials.get(0).restrictions)
        .containsExactly(NAVIGATION_RESTRICTION_SKIP, NAVIGATION_RESTRICTION_JUMP)
        .inOrder();
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialWithUnknownCueIdentifier_ignoredForForwardCompatibility()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "X-CUE=\"ALONE , PRE , INBETWEEN , BEYOND\","
            + "X-ASSET-URI=\"http://example.com/media.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials.get(0).cue).containsExactly(CUE_TRIGGER_PRE);
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialWithUnknownSnapTypes_ignoredForForwardCompatibility()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2020-01-02T21:55:40.000Z\n"
            + "#EXTINF:6,\n"
            + "main1.0.ts\n"
            + "#EXT-X-ENDLIST"
            + "\n"
            + "#EXT-X-DATERANGE:"
            + "ID=\"ad2\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "X-SNAP=\"    FOR  , OUT  , AT\", "
            + "X-ASSET-URI=\"http://example.com/media.m3u8\","
            + "START-DATE=\"2020-01-02T21:55:44.000Z\"\n";

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials.get(0).snapTypes).containsExactly(SNAP_TYPE_OUT);
  }

  @Test
  public void parseMediaPlaylist_withInterstitialWithUpdatingDateRange() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z\n"
            + "#EXTINF:10.007800,\n"
            + "audio0000.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "PLANNED-DURATION=25,"
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-SNAP=\"OUT,IN\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "X-CONTENT-MAY-VARY=\"YES\""
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "END-DATE=\"2024-09-20T15:29:49.006Z\","
            + "X-PLAYOUT-LIMIT=24.953741497,"
            + "X-RESUME-OFFSET=24.953741497\n";

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials).hasSize(1);
    assertThat(playlist.interstitials.get(0).resumeOffsetUs).isEqualTo(24953741L);
    assertThat(playlist.interstitials.get(0).endDateUnixUs).isEqualTo(1726846189006000L);
    assertThat(playlist.interstitials.get(0).playoutLimitUs).isEqualTo(24953741L);
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialStartDateInvalidUpdate_throwsIllegalArgumentException() {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z\n"
            + "#EXTINF:10.007800,\n"
            + "audio0000.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "PLANNED-DURATION=25,"
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-SNAP=\"OUT,IN\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "X-CONTENT-MAY-VARY=\"YES\""
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:25.006Z\","
            + "END-DATE=\"2024-09-20T15:29:49.006Z\","
            + "X-PLAYOUT-LIMIT=24.953741497,"
            + "X-RESUME-OFFSET=24.953741497\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    assertThrows(
        IllegalArgumentException.class, () -> hlsPlaylistParser.parse(playlistUri, inputStream));
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialTimelineAttributeChanged_throwsIllegalArgumentException() {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "X-TIMELINE-STYLE=\"PRIMARY\","
            + "CLASS=\"com.apple.hls.interstitial\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    assertThrows(
        IllegalArgumentException.class, () -> hlsPlaylistParser.parse(playlistUri, inputStream));

    playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "X-TIMELINE-OCCUPIES=\"POINT\","
            + "CLASS=\"com.apple.hls.interstitial\"\n";
    ByteArrayInputStream inputStream2 = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    assertThrows(
        IllegalArgumentException.class, () -> hlsPlaylistParser.parse(playlistUri, inputStream2));
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialTimelineAttributeUnsetThenOverridden_updateAccepted()
          throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"ID-0\","
            + "X-CONTENT-MAY-VARY=\"NO\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"PRIMARY\","
            + "CLASS=\"com.apple.hls.interstitial\"\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) hlsPlaylistParser.parse(playlistUri, inputStream);

    assertThat(playlist.interstitials.get(0).contentMayVary).isFalse();
    assertThat(playlist.interstitials.get(0).timelineOccupies)
        .isEqualTo(Interstitial.TIMELINE_OCCUPIES_RANGE);
    assertThat(playlist.interstitials.get(0).timelineStyle)
        .isEqualTo(Interstitial.TIMELINE_STYLE_PRIMARY);
  }

  @Test
  public void
      parseMediaPlaylist_withInterstitialClientDefinedAttributeInvalidUpdate_throwsIllegalArgumentException() {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z\n"
            + "#EXTINF:10.007800,\n"
            + "audio0000.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "PLANNED-DURATION=25,"
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-SNAP=\"OUT,IN\","
            + "X-TIMELINE-OCCUPIES=\"RANGE\","
            + "X-TIMELINE-STYLE=\"HIGHLIGHT\","
            + "X-CONTENT-MAY-VARY=\"YES\""
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "END-DATE=\"2024-09-20T15:29:49.006Z\","
            + "X-PLAYOUT-LIMIT=24.953741497,"
            + "X-CONTENT-MAY-VARY=\"NO\","
            + "X-RESUME-OFFSET=24.953741497\n";
    HlsPlaylistParser hlsPlaylistParser = new HlsPlaylistParser();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    assertThrows(
        IllegalArgumentException.class, () -> hlsPlaylistParser.parse(playlistUri, inputStream));
  }

  @Test
  public void parseMediaPlaylist_withInterstitialClientDefinedAttribute() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2024-09-20T15:29:20.000Z\n"
            + "#EXTINF:10.007800,\n"
            + "audio0000.ts\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "START-DATE=\"2024-09-20T15:29:24.006Z\","
            + "PLANNED-DURATION=25,"
            + "X-ASSET-LIST=\"myapp://interstitial/req?_HLS_interstitial_id=15943\","
            + "X-SNAP=\"OUT,IN\","
            + "X-CONTENT-MAY-VARY=\"YES\""
            + "\n"
            + "#EXTINF:10.007800,\n"
            + "audio0001.ts"
            + "\n"
            + "#EXT-X-DATERANGE:ID=\"15943\","
            + "CLASS=\"com.apple.hls.interstitial\","
            + "END-DATE=\"2024-09-20T15:29:49.006Z\","
            + "X-PLAYOUT-LIMIT=24.953741497,"
            + "X-CUSTOM-ATTRIBUTE=\"YES\","
            + "X-RESUME-OFFSET=24.953741497\n";

    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser()
                .parse(playlistUri, new ByteArrayInputStream(Util.getUtf8Bytes(playlistString)));

    assertThat(playlist.interstitials).hasSize(1);
    ImmutableList<HlsMediaPlaylist.ClientDefinedAttribute> clientDefinedAttributes =
        playlist.interstitials.get(0).clientDefinedAttributes;
    assertThat(clientDefinedAttributes).hasSize(1);
    HlsMediaPlaylist.ClientDefinedAttribute clientDefinedAttribute = clientDefinedAttributes.get(0);
    assertThat(clientDefinedAttribute.name).isEqualTo("X-CUSTOM-ATTRIBUTE");
    assertThat(clientDefinedAttribute.getTextValue()).isEqualTo("YES");
  }

  @Test
  public void multipleExtXKeysForSingleSegment() throws Exception {
    Uri playlistUri = Uri.parse("https://example.com/test.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-TARGETDURATION:6\n"
            + "#EXT-X-MAP:URI=\"map.mp4\"\n"
            + "#EXTINF:5.005,\n"
            + "s000000.mp4\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\","
            + "KEYFORMATVERSIONS=\"1\","
            + "URI=\"data:text/plain;base64,Tm90aGluZyB0byBzZWUgaGVyZQ==\"\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.microsoft.playready\","
            + "KEYFORMATVERSIONS=\"1\","
            + "URI=\"data:text/plain;charset=UTF-16;base64,VGhpcyBpcyBhbiBlYXN0ZXIgZWdn\"\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\","
            + "KEYFORMATVERSIONS=\"1\",URI=\"skd://QW5vdGhlciBlYXN0ZXIgZWdn\"\n"
            + "#EXT-X-MAP:URI=\"map.mp4\"\n"
            + "#EXTINF:5.005,\n"
            + "s000000.mp4\n"
            + "#EXTINF:5.005,\n"
            + "s000001.mp4\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,"
            + "KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\","
            + "KEYFORMATVERSIONS=\"1\","
            + "URI=\"data:text/plain;base64,RG9uJ3QgeW91IGdldCB0aXJlZCBvZiBkb2luZyB0aGlzPw==\""
            + "\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.microsoft.playready\","
            + "KEYFORMATVERSIONS=\"1\","
            + "URI=\"data:text/plain;charset=UTF-16;base64,T2ssIGl0J3Mgbm90IGZ1biBhbnltb3Jl\"\n"
            + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\","
            + "KEYFORMATVERSIONS=\"1\","
            + "URI=\"skd://V2FpdCB1bnRpbCB5b3Ugc2VlIHRoZSBuZXh0IG9uZSE=\"\n"
            + "#EXTINF:5.005,\n"
            + "s000024.mp4\n"
            + "#EXTINF:5.005,\n"
            + "s000025.mp4\n"
            + "#EXT-X-KEY:METHOD=NONE\n"
            + "#EXTINF:5.005,\n"
            + "s000026.mp4\n"
            + "#EXTINF:5.005,\n"
            + "s000026.mp4\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(playlist.protectionSchemes.schemeType).isEqualTo(C.CENC_TYPE_cbcs);
    // Unsupported protection schemes like com.apple.streamingkeydelivery are ignored.
    assertThat(playlist.protectionSchemes.schemeDataCount).isEqualTo(2);
    assertThat(playlist.protectionSchemes.get(0).matches(C.PLAYREADY_UUID)).isTrue();
    assertThat(playlist.protectionSchemes.get(0).hasData()).isFalse();
    assertThat(playlist.protectionSchemes.get(1).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.protectionSchemes.get(1).hasData()).isFalse();

    assertThat(playlist.segments.get(0).drmInitData).isNull();

    assertThat(playlist.segments.get(1).drmInitData.get(0).matches(C.PLAYREADY_UUID)).isTrue();
    assertThat(playlist.segments.get(1).drmInitData.get(0).hasData()).isTrue();
    assertThat(playlist.segments.get(1).drmInitData.get(1).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.segments.get(1).drmInitData.get(1).hasData()).isTrue();

    assertThat(playlist.segments.get(1).drmInitData)
        .isEqualTo(playlist.segments.get(2).drmInitData);
    assertThat(playlist.segments.get(2).drmInitData)
        .isNotEqualTo(playlist.segments.get(3).drmInitData);
    assertThat(playlist.segments.get(3).drmInitData.get(0).matches(C.PLAYREADY_UUID)).isTrue();
    assertThat(playlist.segments.get(3).drmInitData.get(0).hasData()).isTrue();
    assertThat(playlist.segments.get(3).drmInitData.get(1).matches(C.WIDEVINE_UUID)).isTrue();
    assertThat(playlist.segments.get(3).drmInitData.get(1).hasData()).isTrue();

    assertThat(playlist.segments.get(3).drmInitData)
        .isEqualTo(playlist.segments.get(4).drmInitData);
    assertThat(playlist.segments.get(5).drmInitData).isNull();
    assertThat(playlist.segments.get(6).drmInitData).isNull();
  }

  @Test
  public void gapTag() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test2.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "#EXT-X-MEDIA-SEQUENCE:0\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2016-09-22T02:00:01+00:00\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.com/key?value=something\"\n"
            + "#EXTINF:5.005,\n"
            + "02/00/27.ts\n"
            + "#EXTINF:5.005,\n"
            + "02/00/32.ts\n"
            + "#EXT-X-KEY:METHOD=NONE\n"
            + "#EXTINF:5.005,\n"
            + "#EXT-X-GAP \n"
            + "../test.ts\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://key-service.bamgrid.com/1.0/key?"
            + "hex-value=9FB8989D15EEAAF8B21B860D7ED3072A\",IV=0x410C8AC18AA42EFA18B5155484F5FC34\n"
            + "#EXTINF:5.005,\n"
            + "02/00/42.ts\n"
            + "#EXTINF:5.005,\n"
            + "02/00/47.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    assertThat(playlist.hasEndTag).isFalse();
    assertThat(playlist.segments.get(1).hasGapTag).isFalse();
    assertThat(playlist.segments.get(2).hasGapTag).isTrue();
    assertThat(playlist.segments.get(3).hasGapTag).isFalse();
  }

  @Test
  public void mapTag() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXTINF:5.005,\n"
            + "02/00/27.ts\n"
            + "#EXT-X-MAP:URI=\"init1.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/32.ts\n"
            + "#EXTINF:5.005,\n"
            + "02/00/42.ts\n"
            + "#EXT-X-MAP:URI=\"init2.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/47.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    List<Segment> segments = playlist.segments;
    assertThat(segments.get(0).initializationSegment).isNull();
    assertThat(segments.get(1).initializationSegment)
        .isSameInstanceAs(segments.get(2).initializationSegment);
    assertThat(segments.get(1).initializationSegment.url).isEqualTo("init1.ts");
    assertThat(segments.get(3).initializationSegment.url).isEqualTo("init2.ts");
  }

  @Test
  public void noExplicitInitSegmentInIFrameOnly_infersInitSegment() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-I-FRAMES-ONLY\n"
            + "#EXTINF:5.005,\n"
            + "#EXT-X-BYTERANGE:100@300\n"
            + "segment1.ts\n"
            + "#EXTINF:5.005,\n"
            + "#EXT-X-BYTERANGE:100@400\n"
            + "segment2.ts\n"
            + "#EXTINF:5.005,\n"
            + "#EXT-X-BYTERANGE:100@400\n"
            + "segment1.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    List<Segment> segments = playlist.segments;
    @Nullable Segment initializationSegment = segments.get(0).initializationSegment;
    assertThat(initializationSegment.url).isEqualTo("segment1.ts");
    assertThat(initializationSegment.byteRangeOffset).isEqualTo(0);
    assertThat(initializationSegment.byteRangeLength).isEqualTo(300);
    initializationSegment = segments.get(1).initializationSegment;
    assertThat(initializationSegment.url).isEqualTo("segment2.ts");
    assertThat(initializationSegment.byteRangeOffset).isEqualTo(0);
    assertThat(initializationSegment.byteRangeLength).isEqualTo(400);
    initializationSegment = segments.get(2).initializationSegment;
    assertThat(initializationSegment.url).isEqualTo("segment1.ts");
    assertThat(initializationSegment.byteRangeOffset).isEqualTo(0);
    assertThat(initializationSegment.byteRangeLength).isEqualTo(300);
  }

  @Test
  public void encryptedMapTag() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXT-X-KEY:METHOD=AES-128,"
            + "URI=\"https://priv.example.com/key.php?r=2680\",IV=0x1566B\n"
            + "#EXT-X-MAP:URI=\"init1.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/32.ts\n"
            + "#EXT-X-KEY:METHOD=NONE\n"
            + "#EXT-X-MAP:URI=\"init2.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/47.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);

    List<Segment> segments = playlist.segments;
    Segment initSegment1 = segments.get(0).initializationSegment;
    assertThat(initSegment1.fullSegmentEncryptionKeyUri)
        .isEqualTo("https://priv.example.com/key.php?r=2680");
    assertThat(initSegment1.encryptionIV).isEqualTo("0x1566B");
    Segment initSegment2 = segments.get(1).initializationSegment;
    assertThat(initSegment2.fullSegmentEncryptionKeyUri).isNull();
    assertThat(initSegment2.encryptionIV).isNull();
  }

  @Test
  public void encryptedMapTagWithNoIvFails() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXT-X-KEY:METHOD=AES-128,"
            + "URI=\"https://priv.example.com/key.php?r=2680\"\n"
            + "#EXT-X-MAP:URI=\"init1.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/32.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));

    try {
      new HlsPlaylistParser().parse(playlistUri, inputStream);
      fail();
    } catch (ParserException e) {
      // Expected because the initialization segment does not have a defined initialization vector,
      // although it is affected by an EXT-X-KEY tag.
    }
  }

  @Test
  public void iframeOnly_withExplicitInitSegment_hasCorrectByteRange() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-MEDIA-SEQUENCE:1616630672\n"
            + "#EXT-X-TARGETDURATION:7\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:491 \n"
            + "#EXT-X-MAP:URI=\"iframe0.tsv\",BYTERANGE=\"564@0\"\n"
            + "\n"
            + "#EXT-X-I-FRAMES-ONLY\n"
            + "#EXT-X-PROGRAM-DATE-TIME:2021-04-12T17:08:22.000Z\n"
            + "#EXTINF:1.001000,\n"
            + "#EXT-X-BYTERANGE:121260@1128\n"
            + "iframe0.tsv";

    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist standalonePlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    @Nullable Segment initSegment = standalonePlaylist.segments.get(0).initializationSegment;

    assertThat(standalonePlaylist.segments).hasSize(1);
    assertThat(initSegment.byteRangeLength).isEqualTo(564);
    assertThat(initSegment.byteRangeOffset).isEqualTo(0);
  }

  @Test
  public void multivariantPlaylistAttributeInheritance() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXTINF:5.005,\n"
            + "02/00/27.ts\n"
            + "#EXT-X-MAP:URI=\"init1.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/32.ts\n"
            + "#EXTINF:5.005,\n"
            + "02/00/42.ts\n"
            + "#EXT-X-MAP:URI=\"init2.ts\""
            + "#EXTINF:5.005,\n"
            + "02/00/47.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist standalonePlaylist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    assertThat(standalonePlaylist.hasIndependentSegments).isFalse();

    inputStream.reset();
    HlsMultivariantPlaylist multivariantPlaylist =
        new HlsMultivariantPlaylist(
            /* baseUri= */ "https://example.com/",
            /* tags= */ Collections.emptyList(),
            /* variants= */ Collections.emptyList(),
            /* videos= */ Collections.emptyList(),
            /* audios= */ Collections.emptyList(),
            /* subtitles= */ Collections.emptyList(),
            /* closedCaptions= */ Collections.emptyList(),
            /* muxedAudioFormat= */ null,
            /* muxedCaptionFormats= */ null,
            /* hasIndependentSegments= */ true,
            /* variableDefinitions= */ Collections.emptyMap(),
            /* sessionKeyDrmInitData= */ Collections.emptyList());
    HlsMediaPlaylist playlistWithInheritance =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(multivariantPlaylist, /* previousMediaPlaylist= */ null)
                .parse(playlistUri, inputStream);
    assertThat(playlistWithInheritance.hasIndependentSegments).isTrue();
  }

  @Test
  public void variableSubstitution() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/substitution.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:8\n"
            + "#EXT-X-DEFINE:NAME=\"underscore_1\",VALUE=\"{\"\n"
            + "#EXT-X-DEFINE:NAME=\"dash-1\",VALUE=\"replaced_value.ts\"\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXTINF:5.005,\n"
            + "segment1.ts\n"
            + "#EXT-X-MAP:URI=\"{$dash-1}\""
            + "#EXTINF:5.005,\n"
            + "segment{$underscore_1}$name_1}\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
    Segment segment = playlist.segments.get(1);
    assertThat(segment.initializationSegment.url).isEqualTo("replaced_value.ts");
    assertThat(segment.url).isEqualTo("segment{$name_1}");
  }

  @Test
  public void inheritedVariableSubstitution() throws IOException {
    Uri playlistUri = Uri.parse("https://example.com/test3.m3u8");
    String playlistString =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:8\n"
            + "#EXT-X-TARGETDURATION:5\n"
            + "#EXT-X-MEDIA-SEQUENCE:10\n"
            + "#EXT-X-DEFINE:IMPORT=\"imported_base\"\n"
            + "#EXTINF:5.005,\n"
            + "{$imported_base}1.ts\n"
            + "#EXTINF:5.005,\n"
            + "{$imported_base}2.ts\n"
            + "#EXTINF:5.005,\n"
            + "{$imported_base}3.ts\n"
            + "#EXTINF:5.005,\n"
            + "{$imported_base}4.ts\n";
    InputStream inputStream = new ByteArrayInputStream(Util.getUtf8Bytes(playlistString));
    HashMap<String, String> variableDefinitions = new HashMap<>();
    variableDefinitions.put("imported_base", "long_path");
    HlsMultivariantPlaylist multivariantPlaylist =
        new HlsMultivariantPlaylist(
            /* baseUri= */ "",
            /* tags= */ Collections.emptyList(),
            /* variants= */ Collections.emptyList(),
            /* videos= */ Collections.emptyList(),
            /* audios= */ Collections.emptyList(),
            /* subtitles= */ Collections.emptyList(),
            /* closedCaptions= */ Collections.emptyList(),
            /* muxedAudioFormat= */ null,
            /* muxedCaptionFormats= */ Collections.emptyList(),
            /* hasIndependentSegments= */ false,
            variableDefinitions,
            /* sessionKeyDrmInitData= */ Collections.emptyList());
    HlsMediaPlaylist playlist =
        (HlsMediaPlaylist)
            new HlsPlaylistParser(multivariantPlaylist, /* previousMediaPlaylist= */ null)
                .parse(playlistUri, inputStream);
    for (int i = 1; i <= 4; i++) {
      assertThat(playlist.segments.get(i - 1).url).isEqualTo("long_path" + i + ".ts");
    }
  }
}
