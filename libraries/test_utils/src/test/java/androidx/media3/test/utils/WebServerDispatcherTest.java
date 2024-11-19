/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import java.util.Arrays;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link WebServerDispatcher}. */
// We use the OkHttp client library for these tests because it's generally nicer to use than Java's
// HttpURLConnection.
//
// However, OkHttp's 'transparent compression' behaviour is annoying when trying to test the edge
// cases of the WebServerDispatcher's Accept-Encoding header handling. If passed a request with no
// Accept-Encoding header, the OkHttp client library will silently add one that accepts gzip and
// then silently unzip the response data (and remove the Content-Coding header) before returning it.
//
// This gets in the way of some test cases, for example testing how the WebServerDispatcher handles
// a request with *no* Accept-Encoding header (since it's impossible to send this using OkHttp).
//
// Under Robolectric, the Java HttpURLConnection doesn't have this transparent compression
// behaviour, but that's a Robolectric 'bug' (internal: b/177071755) because the Android platform
// implementation of HttpURLConnection does (it uses OkHttp under the hood). So we can't really use
// HttpURLConnection to test these edge cases either (even though it would work for now) because
// ideally Robolectric will in the future make the implementation more realistic and suddenly our
// tests would be wrong.
//
// So instead we just don't test these cases that require passing header combinations that are
// impossible with OkHttp.
@RunWith(AndroidJUnit4.class)
public class WebServerDispatcherTest {

  private static int seed;
  // Note: Leading slash is deliberately skipped to test that Resource#setPath() will add it if
  // it's missing.
  private static final String RANGE_SUPPORTED_PATH = "range/requests-supported";
  private static final byte[] RANGE_SUPPORTED_DATA =
      TestUtil.buildTestData(/* length= */ 20, seed++);
  private static final String RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH =
      "range/requests-supported-length-unknown";
  private static final byte[] RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA =
      TestUtil.buildTestData(/* length= */ 20, seed++);
  private static final String RANGE_UNSUPPORTED_PATH = "/range/requests/not-supported";
  private static final byte[] RANGE_UNSUPPORTED_DATA =
      TestUtil.buildTestData(/* length= */ 20, seed++);
  private static final String RANGE_UNSUPPORTED_LENGTH_UNKNOWN_PATH =
      "/range/requests/not-supported-length-unknown";
  private static final byte[] RANGE_UNSUPPORTED_LENGTH_UNKNOWN_DATA =
      TestUtil.buildTestData(/* length= */ 20, seed++);
  private static final String GZIP_ENABLED_PATH = "/gzip/enabled";
  private static final byte[] GZIP_ENABLED_DATA = TestUtil.buildTestData(/* length= */ 20, seed++);
  private static final String GZIP_FORCED_PATH = "/gzip/forced";
  private static final byte[] GZIP_FORCED_DATA = TestUtil.buildTestData(/* length= */ 20, seed++);

  private MockWebServer mockWebServer;

  @Before
  public void setupServer() {
    mockWebServer = new MockWebServer();
    mockWebServer.setDispatcher(
        WebServerDispatcher.forResources(
            ImmutableList.of(
                new WebServerDispatcher.Resource.Builder()
                    .setPath(RANGE_SUPPORTED_PATH)
                    .setData(RANGE_SUPPORTED_DATA)
                    .supportsRangeRequests(true)
                    .build(),
                new WebServerDispatcher.Resource.Builder()
                    .setPath(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH)
                    .setData(RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA)
                    .supportsRangeRequests(true)
                    .resolvesToUnknownLength(true)
                    .build(),
                new WebServerDispatcher.Resource.Builder()
                    .setPath(RANGE_UNSUPPORTED_PATH)
                    .setData(RANGE_UNSUPPORTED_DATA)
                    .supportsRangeRequests(false)
                    .build(),
                new WebServerDispatcher.Resource.Builder()
                    .setPath(RANGE_UNSUPPORTED_LENGTH_UNKNOWN_PATH)
                    .setData(RANGE_UNSUPPORTED_LENGTH_UNKNOWN_DATA)
                    .supportsRangeRequests(false)
                    .resolvesToUnknownLength(true)
                    .build(),
                new WebServerDispatcher.Resource.Builder()
                    .setPath(GZIP_ENABLED_PATH)
                    .setData(GZIP_ENABLED_DATA)
                    .setGzipSupport(WebServerDispatcher.Resource.GZIP_SUPPORT_ENABLED)
                    .build(),
                new WebServerDispatcher.Resource.Builder()
                    .setPath(GZIP_FORCED_PATH)
                    .setData(GZIP_FORCED_DATA)
                    .setGzipSupport(WebServerDispatcher.Resource.GZIP_SUPPORT_FORCED)
                    .build())));
  }

  @Test
  public void rangeRequestsSupported_handlesConventionalRequest() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder().url(mockWebServer.url(RANGE_SUPPORTED_PATH)).build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("20");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isNull();
      assertThat(response.body().bytes()).isEqualTo(RANGE_SUPPORTED_DATA);
    }
  }

  @Test
  public void rangeRequestsSupported_boundedRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-10")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("6");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-10/20");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_DATA, 5, 11));
    }
  }

  @Test
  public void rangeRequestsSupported_startOnly() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("15");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-19/20");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_DATA, 5, 20));
    }
  }

  @Test
  public void rangeRequestsSupported_suffixBytes() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=-5")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("5");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 15-19/20");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_DATA, 15, 20));
    }
  }

  @Test
  public void rangeRequestsSupported_truncatesBoundedRangeToLength() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-25")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("15");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-19/20");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_DATA, 5, 20));
    }
  }

  @Test
  public void rangeRequestsSupported_truncatesSuffixToLength() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=-25")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("20");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-19/20");
      assertThat(response.body().bytes()).isEqualTo(RANGE_SUPPORTED_DATA);
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsStartAtResourceEnd() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=20-25")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */20");
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsStartAfterResourceEnd() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=25-30")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */20");
    }
  }

  @Test
  public void rangeRequestsSupported_lengthUnknown_handlesConventionalRequest() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder().url(mockWebServer.url(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH)).build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isNull();
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isNull();
      assertThat(response.body().contentLength()).isEqualTo(-1);

      // Calling ResponseBody#bytes() times out because Content-Length isn't set, so instead we
      // read exactly the number of bytes we expect.
      byte[] actualBytes = new byte[20];
      response.body().byteStream().read(actualBytes);
      assertThat(actualBytes).isEqualTo(RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA);
    }
  }

  @Test
  public void rangeRequestsSupported_lengthUnknown_boundedRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-10")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("6");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-10/*");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA, 5, 11));
    }
  }

  @Test
  public void rangeRequestsSupported_lengthUnknown_startOnly() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isNull();
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-19/*");
      assertThat(response.body().contentLength()).isEqualTo(-1);

      // Calling ResponseBody#bytes() times out because Content-Length isn't set, so instead we
      // read exactly the number of bytes we expect.
      byte[] actualBytes = new byte[15];
      response.body().byteStream().read(actualBytes);
      assertThat(actualBytes)
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA, 5, 20));
    }
  }

  @Test
  public void rangeRequestsSupported_lengthUnknown_truncatesBoundedRangeToLength()
      throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-25")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(206);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("15");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-19/*");
      assertThat(response.body().bytes())
          .isEqualTo(Arrays.copyOfRange(RANGE_SUPPORTED_LENGTH_UNKNOWN_DATA, 5, 20));
    }
  }

  @Test
  public void rangeRequestsSupported_lengthUnknown_rejectsSuffixRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_LENGTH_UNKNOWN_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=-5")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsRangeWithEndBeforeStart() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=15-10")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */20");
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsNonByteRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "seconds=5-10")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsMultipartRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=2-6,10-12")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
    }
  }

  @Test
  public void rangeRequestsSupported_rejectsMalformedRange() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=foo")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(416);
    }
  }

  @Test
  public void rangeRequestsUnsupported_conventionalRequestWorksAsExpected() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder().url(mockWebServer.url(RANGE_UNSUPPORTED_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isNull();
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("20");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isNull();
      assertThat(response.body().bytes()).isEqualTo(RANGE_UNSUPPORTED_DATA);
    }
  }

  @Test
  public void rangeRequestsUnsupported_rangeRequestHeadersIgnored() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_UNSUPPORTED_PATH))
            .addHeader(HttpHeaders.RANGE, "bytes=5-10")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header(HttpHeaders.ACCEPT_RANGES)).isNull();
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH)).isEqualTo("20");
      assertThat(response.header(HttpHeaders.CONTENT_RANGE)).isNull();
      assertThat(response.body().bytes()).isEqualTo(RANGE_UNSUPPORTED_DATA);
    }
  }

  @Test
  public void gzipDisabled_acceptEncodingHeaderAllowsAnyCoding_identityResponse() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "*")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("identity");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(RANGE_SUPPORTED_DATA.length));
      assertThat(response.body().bytes()).isEqualTo(RANGE_SUPPORTED_DATA);
    }
  }

  @Test
  public void gzipDisabled_acceptEncodingHeaderRequiresGzip_406Response() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip;q=1.0")
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "identity;q=0")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(406);
    }
  }

  @Test
  public void gzipDisabled_acceptEncodingHeaderRequiresGzipViaAsterisk_406Response()
      throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(RANGE_SUPPORTED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip;q=1.0")
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "*;q=0")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(406);
    }
  }

  @Test
  public void gzipEnabled_acceptEncodingHeaderAllowsGzip_responseGzipped() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_ENABLED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip")
            .build();
    try (Response response = client.newCall(request).execute()) {
      byte[] expectedData = Util.gzip(GZIP_ENABLED_DATA);
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(expectedData.length));
      assertThat(response.body().bytes()).isEqualTo(expectedData);
    }
  }

  @Test
  public void gzipEnabled_acceptEncodingHeaderAllowsAnyCoding_responseGzipped() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_ENABLED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "*")
            .build();
    try (Response response = client.newCall(request).execute()) {
      byte[] expectedData = Util.gzip(GZIP_ENABLED_DATA);

      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(expectedData.length));
      assertThat(response.body().bytes()).isEqualTo(expectedData);
    }
  }

  @Test
  public void gzipEnabled_acceptEncodingHeaderPrefersIdentity_responseNotGzipped()
      throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_ENABLED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "identity;q=0.8, gzip;q=0.2")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("identity");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(GZIP_ENABLED_DATA.length));
      assertThat(response.body().bytes()).isEqualTo(GZIP_ENABLED_DATA);
    }
  }

  @Test
  public void gzipEnabled_acceptEncodingHeaderExcludesGzip_responseNotGzipped() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_ENABLED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("identity");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(GZIP_ENABLED_DATA.length));
      assertThat(response.body().bytes()).isEqualTo(GZIP_ENABLED_DATA);
    }
  }

  @Test
  public void gzipForced_acceptEncodingHeaderAllowsGzip_responseGzipped() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_FORCED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip")
            .build();
    try (Response response = client.newCall(request).execute()) {
      byte[] expectedData = Util.gzip(GZIP_FORCED_DATA);

      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(expectedData.length));
      assertThat(response.body().bytes()).isEqualTo(expectedData);
    }
  }

  @Test
  public void gzipForced_acceptEncodingHeaderExcludesGzip_responseNotGzipped() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request =
        new Request.Builder()
            .url(mockWebServer.url(GZIP_FORCED_PATH))
            .addHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
            .build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      assertThat(response.header("Content-Encoding")).isEqualTo("identity");
      assertThat(response.header(HttpHeaders.CONTENT_LENGTH))
          .isEqualTo(String.valueOf(GZIP_FORCED_DATA.length));
      assertThat(response.body().bytes()).isEqualTo(GZIP_FORCED_DATA);
    }
  }
}
