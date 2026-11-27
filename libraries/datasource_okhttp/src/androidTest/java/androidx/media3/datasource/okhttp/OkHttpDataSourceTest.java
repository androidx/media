/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.media3.datasource.okhttp;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.net.HttpHeaders;
import java.net.CookieManager;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import okhttp3.Headers;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link OkHttpDataSource}. */
@RunWith(AndroidJUnit4.class)
public class OkHttpDataSourceTest {

  /**
   * This test will set HTTP default request parameters (1) in the OkHttpDataSource, (2) via
   * OkHttpDataSource.setRequestProperty() and (3) in the DataSpec instance according to the table
   * below. Values wrapped in '*' are the ones that should be set in the connection request.
   *
   * <pre>{@code
   * +---------------+-----+-----+-----+-----+-----+-----+-----+
   * |               |               Header Key                |
   * +---------------+-----+-----+-----+-----+-----+-----+-----+
   * |   Location    |  0  |  1  |  2  |  3  |  4  |  5  |  6  |
   * +---------------+-----+-----+-----+-----+-----+-----+-----+
   * | Constructor   | *Y* |  Y  |  Y  |     |  Y  |     |     |
   * | Setter        |     | *Y* |  Y  |  Y  |     | *Y* |     |
   * | DataSpec      |     |     | *Y* | *Y* | *Y* |     | *Y* |
   * +---------------+-----+-----+-----+-----+-----+-----+-----+
   * }</pre>
   */
  @Test
  public void open_setsCorrectHeaders() throws Exception {
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse());

    String propertyFromFactory = "fromFactory";
    Map<String, String> defaultRequestProperties = new HashMap<>();
    defaultRequestProperties.put("0", propertyFromFactory);
    defaultRequestProperties.put("1", propertyFromFactory);
    defaultRequestProperties.put("2", propertyFromFactory);
    defaultRequestProperties.put("4", propertyFromFactory);
    HttpDataSource dataSource =
        new OkHttpDataSource.Factory(new OkHttpClient())
            .setDefaultRequestProperties(defaultRequestProperties)
            .createDataSource();

    String propertyFromSetter = "fromSetter";
    dataSource.setRequestProperty("1", propertyFromSetter);
    dataSource.setRequestProperty("2", propertyFromSetter);
    dataSource.setRequestProperty("3", propertyFromSetter);
    dataSource.setRequestProperty("5", propertyFromSetter);

    String propertyFromDataSpec = "fromDataSpec";
    Map<String, String> dataSpecRequestProperties = new HashMap<>();
    dataSpecRequestProperties.put("2", propertyFromDataSpec);
    dataSpecRequestProperties.put("3", propertyFromDataSpec);
    dataSpecRequestProperties.put("4", propertyFromDataSpec);
    dataSpecRequestProperties.put("6", propertyFromDataSpec);

    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(mockWebServer.url("/test-path").toString())
            .setHttpRequestHeaders(dataSpecRequestProperties)
            .build();

    dataSource.open(dataSpec);

    Headers headers = mockWebServer.takeRequest(10, SECONDS).getHeaders();
    assertThat(headers.get("0")).isEqualTo(propertyFromFactory);
    assertThat(headers.get("1")).isEqualTo(propertyFromSetter);
    assertThat(headers.get("2")).isEqualTo(propertyFromDataSpec);
    assertThat(headers.get("3")).isEqualTo(propertyFromDataSpec);
    assertThat(headers.get("4")).isEqualTo(propertyFromDataSpec);
    assertThat(headers.get("5")).isEqualTo(propertyFromSetter);
    assertThat(headers.get("6")).isEqualTo(propertyFromDataSpec);
  }

  @Test
  public void open_invalidResponseCode() {
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(404).setBody("failure msg"));

    HttpDataSource okHttpDataSource =
        new OkHttpDataSource.Factory(new OkHttpClient()).createDataSource();

    DataSpec dataSpec =
        new DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build();

    HttpDataSource.InvalidResponseCodeException exception =
        assertThrows(
            HttpDataSource.InvalidResponseCodeException.class,
            () -> okHttpDataSource.open(dataSpec));

    assertThat(exception.responseCode).isEqualTo(404);
    assertThat(exception.responseBody).isEqualTo("failure msg".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @SdkSuppress(minSdkVersion = 25) // Cookies aren't persisted on API <= 24: b/502906567
  public void cookiejarConfigured_cookiesPersistedBetweenRequests() throws Exception {
    try (MockWebServer mockWebServer = new MockWebServer()) {
      mockWebServer.enqueue(
          new MockResponse().setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val"));
      mockWebServer.enqueue(new MockResponse());

      DataSpec dataSpec =
          new DataSpec.Builder().setUri(mockWebServer.url("foo").toString()).build();
      OkHttpDataSource dataSource =
          new OkHttpDataSource.Factory(
                  new OkHttpClient.Builder()
                      .cookieJar(new JavaNetCookieJar(new CookieManager()))
                      .build())
              .createDataSource();
      dataSource.open(dataSpec);
      dataSource.close();
      dataSource.open(dataSpec);

      RecordedRequest firstRequest = mockWebServer.takeRequest();
      assertThat(firstRequest.getPath()).isEqualTo("/foo");
      assertThat(firstRequest.getHeader(HttpHeaders.COOKIE)).isNull();

      RecordedRequest secondRequest = mockWebServer.takeRequest();
      assertThat(secondRequest.getPath()).isEqualTo("/foo");
      assertThat(secondRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val");
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 25) // Cookies aren't persisted on API <= 24: b/502906567
  public void cookiejarConfigured_cookiesForwardedOnRedirect() throws Exception {
    try (MockWebServer redirectWebServer = new MockWebServer();
        MockWebServer originWebServer = new MockWebServer()) {
      redirectWebServer.enqueue(
          new MockResponse()
              .setResponseCode(302)
              .setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val")
              .setHeader(HttpHeaders.LOCATION, originWebServer.url("bar").toString()));
      originWebServer.enqueue(new MockResponse());

      DataSpec dataSpec =
          new DataSpec.Builder().setUri(redirectWebServer.url("foo").toString()).build();
      OkHttpDataSource dataSource =
          new OkHttpDataSource.Factory(
                  new OkHttpClient.Builder()
                      .cookieJar(new JavaNetCookieJar(new CookieManager()))
                      .build())
              .createDataSource();
      dataSource.open(dataSpec);

      RecordedRequest originRequest = originWebServer.takeRequest();
      assertThat(originRequest.getPath()).isEqualTo("/bar");
      assertThat(originRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val");
    }
  }

  @Test
  public void factory_setRequestPropertyAfterCreation_setsCorrectHeaders() throws Exception {
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse());
    DataSpec dataSpec =
        new DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build();
    OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(new OkHttpClient());
    OkHttpDataSource dataSource = factory.createDataSource();

    Map<String, String> defaultRequestProperties = new HashMap<>();
    defaultRequestProperties.put("0", "afterCreation");
    factory.setDefaultRequestProperties(defaultRequestProperties);
    dataSource.open(dataSpec);

    Headers headers = mockWebServer.takeRequest(10, SECONDS).getHeaders();
    assertThat(headers.get("0")).isEqualTo("afterCreation");
  }
}
