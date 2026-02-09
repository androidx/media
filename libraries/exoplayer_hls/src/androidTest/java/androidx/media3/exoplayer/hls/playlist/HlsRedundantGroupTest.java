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
package androidx.media3.exoplayer.hls.playlist;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.ParserException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HlsRedundantGroup}. */
@RunWith(AndroidJUnit4.class)
public class HlsRedundantGroupTest {

  private static final Uri PLAYLIST_URI = Uri.parse("https://example.com/test.m3u8");

  private static final String PLAYLIST_NO_REDUNDANT_VARIANTS_NOR_RENDITIONS =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"English\",DEFAULT=YES,URI=\"eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"A\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"A\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "hi/video.m3u8\n"
          + "\n";

  private static final String PLAYLIST_REDUNDANT_VARIANTS_WITH_PATHWAY_ID =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"English\",DEFAULT=YES,URI=\"eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"B\",NAME=\"English\",DEFAULT=YES,URI=\"https://b.example.com/eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"A\",PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"A\",PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "hi/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"B\",PATHWAY-ID=\"CDN-B\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "https://backup.example.com/low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"B\",PATHWAY-ID=\"CDN-B\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "https://backup.example.com/hi/video.m3u8\n";

  private static final String PLAYLIST_REDUNDANT_VARIANTS_WITHOUT_PATHWAY_ID =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"English\",DEFAULT=YES,URI=\"eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"B\",NAME=\"English\",DEFAULT=YES,URI=\"https://b.example.com/eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"A\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"A\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "hi/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"B\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "https://backup.example.com/low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"B\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "https://backup.example.com/hi/video.m3u8\n";

  private static final String PLAYLIST_REDUNDANT_VARIANTS_MIX_OF_WITH_AND_WITHOUT_PATHWAY_ID =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"English\",DEFAULT=YES,URI=\"eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"B\",NAME=\"English\",DEFAULT=YES,URI=\"https://b.example.com/eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"A\",PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"A\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "hi/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"B\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "https://backup.example.com/low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=7680000,AUDIO=\"B\",PATHWAY-ID=\"CDN-B\",STABLE-VARIANT-ID=\"Video-768\"\n"
          + "https://backup.example.com/hi/video.m3u8\n";

  private static final String PLAYLIST_REDUNDANT_VARIANTS_WITH_SAME_PATHWAY_ID_BUT_DIFFERENT_URLS =
      " #EXTM3U \n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"English\",DEFAULT=YES,URI=\"eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"B\",NAME=\"English\",DEFAULT=YES,URI=\"https://b.example.com/eng.m3u8\",LANGUAGE=\"en\",STABLE-RENDITION-ID=\"Audio-37262\"\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"A\",PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video-128\"\n"
          + "low/video.m3u8\n"
          + "\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=1280000,AUDIO=\"B\",PATHWAY-ID=\"CDN-A\",STABLE-VARIANT-ID=\"Video-128\"\n" // Redundant variants with same PATHWAY-ID field but different URLs
          + "https://backup.example.com/low/video.m3u8\n";

  @Test
  public void
      createRedundantGroupLists_noRedundantVariantsNorRenditions_redundantGroupsCorrectlyCreated()
          throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_NO_REDUNDANT_VARIANTS_NOR_RENDITIONS);

    ImmutableList<HlsRedundantGroup> variantRedundantGroups =
        HlsRedundantGroup.createVariantRedundantGroupList(multivariantPlaylist.variants);
    ImmutableList<HlsRedundantGroup> videoRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.videos);
    ImmutableList<HlsRedundantGroup> audioRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.audios);
    ImmutableList<HlsRedundantGroup> subtitleRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.subtitles);

    assertThat(variantRedundantGroups).hasSize(2);
    HlsRedundantGroup firstVariantRedundantGroup = variantRedundantGroups.get(0);
    assertThat(firstVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(1280000);
    assertThat(firstVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-128");
    assertThat(firstVariantRedundantGroup.groupKey.name).isNull();
    assertThat(firstVariantRedundantGroup.size()).isEqualTo(1);
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(Uri.parse("https://example.com/low/video.m3u8"));

    HlsRedundantGroup secondVariantRedundantGroup = variantRedundantGroups.get(1);
    assertThat(secondVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(7680000);
    assertThat(secondVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-768");
    assertThat(secondVariantRedundantGroup.groupKey.name).isNull();
    assertThat(secondVariantRedundantGroup.size()).isEqualTo(1);
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(Uri.parse("https://example.com/hi/video.m3u8"));

    assertThat(audioRedundantGroups).hasSize(1);
    HlsRedundantGroup audioRedundantGroup = audioRedundantGroups.get(0);
    assertThat(audioRedundantGroup.groupKey.format.language).isEqualTo("en");
    assertThat(audioRedundantGroup.groupKey.stableId).isEqualTo("Audio-37262");
    assertThat(audioRedundantGroup.groupKey.name).isEqualTo("English");
    assertThat(audioRedundantGroup.size()).isEqualTo(1);
    assertThat(audioRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getAllPlaylistUrls())
        .containsExactly(Uri.parse("https://example.com/eng.m3u8"));

    assertThat(videoRedundantGroups).isEmpty();
    assertThat(subtitleRedundantGroups).isEmpty();
  }

  @Test
  public void
      createRedundantGroupLists_redundantVariantsWithPathwayId_redundantGroupsCorrectlyCreated()
          throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_REDUNDANT_VARIANTS_WITH_PATHWAY_ID);

    ImmutableList<HlsRedundantGroup> variantRedundantGroups =
        HlsRedundantGroup.createVariantRedundantGroupList(multivariantPlaylist.variants);
    ImmutableList<HlsRedundantGroup> videoRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.videos);
    ImmutableList<HlsRedundantGroup> audioRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.audios);
    ImmutableList<HlsRedundantGroup> subtitleRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.subtitles);

    assertThat(variantRedundantGroups).hasSize(2);
    HlsRedundantGroup firstVariantRedundantGroup = variantRedundantGroups.get(0);
    assertThat(firstVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(1280000);
    assertThat(firstVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-128");
    assertThat(firstVariantRedundantGroup.groupKey.name).isNull();
    assertThat(firstVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("CDN-A"))
        .isEqualTo(Uri.parse("https://example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("CDN-B"))
        .isEqualTo(Uri.parse("https://backup.example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/low/video.m3u8"),
            Uri.parse("https://backup.example.com/low/video.m3u8"));

    HlsRedundantGroup secondVariantRedundantGroup = variantRedundantGroups.get(1);
    assertThat(secondVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(7680000);
    assertThat(secondVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-768");
    assertThat(secondVariantRedundantGroup.groupKey.name).isNull();
    assertThat(secondVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("CDN-A"))
        .isEqualTo(Uri.parse("https://example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("CDN-B"))
        .isEqualTo(Uri.parse("https://backup.example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/hi/video.m3u8"),
            Uri.parse("https://backup.example.com/hi/video.m3u8"));

    assertThat(audioRedundantGroups).hasSize(1);
    HlsRedundantGroup audioRedundantGroup = audioRedundantGroups.get(0);
    assertThat(audioRedundantGroup.groupKey.format.language).isEqualTo("en");
    assertThat(audioRedundantGroup.groupKey.stableId).isEqualTo("Audio-37262");
    assertThat(audioRedundantGroup.groupKey.name).isEqualTo("English");
    assertThat(audioRedundantGroup.size()).isEqualTo(2);
    assertThat(audioRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getPlaylistUrl(".."))
        .isEqualTo(Uri.parse("https://b.example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/eng.m3u8"), Uri.parse("https://b.example.com/eng.m3u8"));

    assertThat(videoRedundantGroups).isEmpty();
    assertThat(subtitleRedundantGroups).isEmpty();
  }

  @Test
  public void
      createRedundantGroupLists_redundantVariantsWithoutPathwayId_redundantGroupsCorrectlyCreated()
          throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_REDUNDANT_VARIANTS_WITHOUT_PATHWAY_ID);

    ImmutableList<HlsRedundantGroup> variantRedundantGroups =
        HlsRedundantGroup.createVariantRedundantGroupList(multivariantPlaylist.variants);
    ImmutableList<HlsRedundantGroup> videoRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.videos);
    ImmutableList<HlsRedundantGroup> audioRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.audios);
    ImmutableList<HlsRedundantGroup> subtitleRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.subtitles);

    assertThat(variantRedundantGroups).hasSize(2);
    HlsRedundantGroup firstVariantRedundantGroup = variantRedundantGroups.get(0);
    assertThat(firstVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(1280000);
    assertThat(firstVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-128");
    assertThat(firstVariantRedundantGroup.groupKey.name).isNull();
    assertThat(firstVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getPlaylistUrl(".."))
        .isEqualTo(Uri.parse("https://backup.example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/low/video.m3u8"),
            Uri.parse("https://backup.example.com/low/video.m3u8"));

    HlsRedundantGroup secondVariantRedundantGroup = variantRedundantGroups.get(1);
    assertThat(secondVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(7680000);
    assertThat(secondVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-768");
    assertThat(secondVariantRedundantGroup.groupKey.name).isNull();
    assertThat(secondVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getPlaylistUrl(".."))
        .isEqualTo(Uri.parse("https://backup.example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/hi/video.m3u8"),
            Uri.parse("https://backup.example.com/hi/video.m3u8"));

    assertThat(audioRedundantGroups).hasSize(1);
    HlsRedundantGroup audioRedundantGroup = audioRedundantGroups.get(0);
    assertThat(audioRedundantGroup.groupKey.format.language).isEqualTo("en");
    assertThat(audioRedundantGroup.groupKey.stableId).isEqualTo("Audio-37262");
    assertThat(audioRedundantGroup.groupKey.name).isEqualTo("English");
    assertThat(audioRedundantGroup.size()).isEqualTo(2);
    assertThat(audioRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getPlaylistUrl(".."))
        .isEqualTo(Uri.parse("https://b.example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/eng.m3u8"), Uri.parse("https://b.example.com/eng.m3u8"));

    assertThat(videoRedundantGroups).isEmpty();
    assertThat(subtitleRedundantGroups).isEmpty();
  }

  @Test
  public void
      createRedundantGroupLists_redundantVariantsMixOfWithAndWithoutPathwayId_redundantGroupsCorrectlyCreated()
          throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(PLAYLIST_REDUNDANT_VARIANTS_MIX_OF_WITH_AND_WITHOUT_PATHWAY_ID);

    ImmutableList<HlsRedundantGroup> variantRedundantGroups =
        HlsRedundantGroup.createVariantRedundantGroupList(multivariantPlaylist.variants);
    ImmutableList<HlsRedundantGroup> videoRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.videos);
    ImmutableList<HlsRedundantGroup> audioRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.audios);
    ImmutableList<HlsRedundantGroup> subtitleRedundantGroups =
        HlsRedundantGroup.createRenditionRedundantGroupList(multivariantPlaylist.subtitles);

    assertThat(variantRedundantGroups).hasSize(2);
    HlsRedundantGroup firstVariantRedundantGroup = variantRedundantGroups.get(0);
    assertThat(firstVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(1280000);
    assertThat(firstVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-128");
    assertThat(firstVariantRedundantGroup.groupKey.name).isNull();
    assertThat(firstVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("CDN-A"))
        .isEqualTo(Uri.parse("https://example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://backup.example.com/low/video.m3u8"));
    assertThat(firstVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/low/video.m3u8"),
            Uri.parse("https://backup.example.com/low/video.m3u8"));

    HlsRedundantGroup secondVariantRedundantGroup = variantRedundantGroups.get(1);
    assertThat(secondVariantRedundantGroup.groupKey.format.bitrate).isEqualTo(7680000);
    assertThat(secondVariantRedundantGroup.groupKey.stableId).isEqualTo("Video-768");
    assertThat(secondVariantRedundantGroup.groupKey.name).isNull();
    assertThat(secondVariantRedundantGroup.size()).isEqualTo(2);
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getPlaylistUrl("CDN-B"))
        .isEqualTo(Uri.parse("https://backup.example.com/hi/video.m3u8"));
    assertThat(secondVariantRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/hi/video.m3u8"),
            Uri.parse("https://backup.example.com/hi/video.m3u8"));

    assertThat(audioRedundantGroups).hasSize(1);
    HlsRedundantGroup audioRedundantGroup = audioRedundantGroups.get(0);
    assertThat(audioRedundantGroup.groupKey.format.language).isEqualTo("en");
    assertThat(audioRedundantGroup.groupKey.stableId).isEqualTo("Audio-37262");
    assertThat(audioRedundantGroup.groupKey.name).isEqualTo("English");
    assertThat(audioRedundantGroup.size()).isEqualTo(2);
    assertThat(audioRedundantGroup.getPlaylistUrl("."))
        .isEqualTo(Uri.parse("https://example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getPlaylistUrl(".."))
        .isEqualTo(Uri.parse("https://b.example.com/eng.m3u8"));
    assertThat(audioRedundantGroup.getAllPlaylistUrls())
        .containsExactly(
            Uri.parse("https://example.com/eng.m3u8"), Uri.parse("https://b.example.com/eng.m3u8"));

    assertThat(videoRedundantGroups).isEmpty();
    assertThat(subtitleRedundantGroups).isEmpty();
  }

  @Test
  public void
      createRedundantGroupLists_redundantVariantsWithSamePathwayIdButDifferentUrls_throwsParserException()
          throws IOException {
    HlsMultivariantPlaylist multivariantPlaylist =
        parseMultivariantPlaylist(
            PLAYLIST_REDUNDANT_VARIANTS_WITH_SAME_PATHWAY_ID_BUT_DIFFERENT_URLS);

    assertThrows(
        ParserException.class,
        () -> HlsRedundantGroup.createVariantRedundantGroupList(multivariantPlaylist.variants));
  }

  private static HlsMultivariantPlaylist parseMultivariantPlaylist(String playlistString)
      throws IOException {
    ByteArrayInputStream inputStream = new ByteArrayInputStream(playlistString.getBytes(UTF_8));
    return (HlsMultivariantPlaylist) new HlsPlaylistParser().parse(PLAYLIST_URI, inputStream);
  }
}
