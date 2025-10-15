/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.WebServerDispatcher;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.net.HttpHeaders;
import java.io.IOException;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for HTTP interactions of playback using {@link ProgressiveMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class HttpProgressivePlaybackTest {

  /**
   * {@code sample.mkv} has a {@code Cues} element near the end of the file, so we expect to see a
   * second range request to efficiently access this data. This second request should include an
   * {@code If-Range} header containing the {@code ETag} value provided by the first response.
   */
  @Test
  public void rangeRequest_propagatesEtagFromResponseToRequest() throws Exception {
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.start();
    WebServerDispatcher.Resource resource =
        new WebServerDispatcher.Resource.Builder()
            .setPath("sample.mkv")
            .setData(loadAsset("asset:///media/mkv/sample.mkv"))
            .supportsRangeRequests(true)
            .setExtraResponseHeaders(ImmutableSetMultimap.of(HttpHeaders.ETAG, "\"foo\""))
            .build();
    mockWebServer.setDispatcher(WebServerDispatcher.forResources(ImmutableList.of(resource)));

    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.setMediaItem(MediaItem.fromUri(mockWebServer.url("sample.mkv").toString()));
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    RecordedRequest initialRequest = mockWebServer.takeRequest();
    assertThat(initialRequest.getHeader(HttpHeaders.RANGE)).isNull();

    RecordedRequest secondRequest = mockWebServer.takeRequest();
    assertThat(secondRequest.getHeader(HttpHeaders.RANGE)).isEqualTo("bytes=107445-");
    assertThat(secondRequest.getHeader(HttpHeaders.IF_RANGE)).isEqualTo("\"foo\"");
    mockWebServer.close();
  }

  @Test
  public void rangeRequest_weakEtagNotPropagated() throws Exception {
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.start();
    WebServerDispatcher.Resource resource =
        new WebServerDispatcher.Resource.Builder()
            .setPath("sample.mkv")
            .setData(loadAsset("asset:///media/mkv/sample.mkv"))
            .supportsRangeRequests(true)
            .setExtraResponseHeaders(ImmutableSetMultimap.of(HttpHeaders.ETAG, "W/\"foo\""))
            .build();
    mockWebServer.setDispatcher(WebServerDispatcher.forResources(ImmutableList.of(resource)));

    ExoPlayer player =
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    player.setMediaItem(MediaItem.fromUri(mockWebServer.url("sample.mkv").toString()));
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    RecordedRequest initialRequest = mockWebServer.takeRequest();
    assertThat(initialRequest.getHeader(HttpHeaders.RANGE)).isNull();

    RecordedRequest secondRequest = mockWebServer.takeRequest();
    assertThat(secondRequest.getHeader(HttpHeaders.RANGE)).isEqualTo("bytes=107445-");
    assertThat(secondRequest.getHeader(HttpHeaders.IF_RANGE)).isNull();
    mockWebServer.close();
  }

  private static byte[] loadAsset(String uri) throws IOException {
    AssetDataSource assetDataSource =
        new AssetDataSource(ApplicationProvider.getApplicationContext());
    assetDataSource.open(new DataSpec(Uri.parse(uri)));
    try {
      return DataSourceUtil.readToEnd(assetDataSource);
    } finally {
      DataSourceUtil.closeQuietly(assetDataSource);
    }
  }
}
