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
package androidx.media3.datasource.ktor

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.KotlinTestParameters.namedTestValues
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class KtorDataSourceTest(
  @TestParameter
  private val httpClientEngineFactory: HttpClientEngineFactory<*> =
    namedTestValues("Android" to Android, "OkHttp" to OkHttp)
) {

  @get:Rule val mockWebServer = MockWebServer()

  private lateinit var httpClient: HttpClient
  private lateinit var dataSource: KtorDataSource

  @After
  fun tearDown() {
    if (::dataSource.isInitialized) dataSource.close()
    if (::httpClient.isInitialized) httpClient.close()
  }

  @Test
  fun open_setsCorrectHeaders() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse())

    val propertyFromFactory = "fromFactory"
    val defaultRequestProperties = HashMap<String, String>()
    defaultRequestProperties["0"] = propertyFromFactory
    defaultRequestProperties["1"] = propertyFromFactory
    defaultRequestProperties["2"] = propertyFromFactory
    defaultRequestProperties["4"] = propertyFromFactory

    dataSource =
      KtorDataSource.Factory(httpClient)
        .setDefaultRequestProperties(defaultRequestProperties)
        .createDataSource()

    val propertyFromSetter = "fromSetter"
    dataSource.setRequestProperty("1", propertyFromSetter)
    dataSource.setRequestProperty("2", propertyFromSetter)
    dataSource.setRequestProperty("3", propertyFromSetter)
    dataSource.setRequestProperty("5", propertyFromSetter)

    val propertyFromDataSpec = "fromDataSpec"
    val dataSpecRequestProperties = HashMap<String, String>()
    dataSpecRequestProperties["2"] = propertyFromDataSpec
    dataSpecRequestProperties["3"] = propertyFromDataSpec
    dataSpecRequestProperties["4"] = propertyFromDataSpec
    dataSpecRequestProperties["6"] = propertyFromDataSpec

    val dataSpec =
      DataSpec.Builder()
        .setUri(mockWebServer.url("/test-path").toString())
        .setHttpRequestHeaders(dataSpecRequestProperties)
        .build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val headers =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
        .headers
    assertThat(headers["0"]).isEqualTo(propertyFromFactory)
    assertThat(headers["1"]).isEqualTo(propertyFromSetter)
    assertThat(headers["2"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["3"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["4"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["5"]).isEqualTo(propertyFromSetter)
    assertThat(headers["6"]).isEqualTo(propertyFromDataSpec)
  }

  @Test
  fun open_invalidResponseCode() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("failure msg"))

    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val exception =
      assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
        dataSource.open(dataSpec)
      }

    assertThat(exception.responseCode).isEqualTo(404)
    assertThat(exception.responseBody).isEqualTo("failure msg".toByteArray(StandardCharsets.UTF_8))
  }

  @Test
  fun factory_setRequestPropertyAfterCreation_setsCorrectHeaders() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse())
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val factory = KtorDataSource.Factory(httpClient)
    dataSource = factory.createDataSource()

    val defaultRequestProperties = HashMap<String, String>()
    defaultRequestProperties["0"] = "afterCreation"
    factory.setDefaultRequestProperties(defaultRequestProperties)
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val headers =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
        .headers
    assertThat(headers["0"]).isEqualTo("afterCreation")
  }

  @Test
  fun open_malformedUrl_throwsException() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec = DataSpec.Builder().setUri("not-a-valid-url").build()

    val exception =
      assertThrows(HttpDataSource.HttpDataSourceException::class.java) { dataSource.open(dataSpec) }

    assertThat(exception.message).contains("Malformed URL")
  }

  @Test
  fun open_httpPost_sendsPostRequest() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse())

    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec =
      DataSpec.Builder()
        .setUri(mockWebServer.url("/test-path").toString())
        .setHttpMethod(DataSpec.HTTP_METHOD_POST)
        .setHttpBody("test body".toByteArray(StandardCharsets.UTF_8))
        .build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.body.readUtf8()).isEqualTo("test body")
  }

  @Test
  fun cookiesConfigured_cookiesPersistedBetweenRequests() {
    httpClient =
      HttpClient(httpClientEngineFactory) {
        configureTimeout()
        install(HttpCookies)
      }
    mockWebServer.enqueue(
      MockResponse().setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val")
    )
    mockWebServer.enqueue(MockResponse())

    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("foo").toString()).build()
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)
    dataSource.close()
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val firstRequest =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(firstRequest.path).isEqualTo("/foo")
    assertThat(firstRequest.getHeader(HttpHeaders.COOKIE)).isNull()

    val secondRequest =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(secondRequest.path).isEqualTo("/foo")
    assertThat(secondRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val")
  }

  @Test
  fun cookiesConfigured_cookiesForwardedOnRedirect() {
    httpClient =
      HttpClient(httpClientEngineFactory) {
        configureTimeout()
        install(HttpCookies)
      }
    MockWebServer().use { redirectWebServer ->
      val originUrl = mockWebServer.url("bar").toString()
      redirectWebServer.enqueue(
        MockResponse()
          .setResponseCode(302)
          .setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val; Path=/")
          .setHeader(HttpHeaders.LOCATION, originUrl)
      )
      mockWebServer.enqueue(MockResponse())

      val redirectUrl = redirectWebServer.url("foo").toString()
      val dataSpec = DataSpec.Builder().setUri(redirectUrl).build()
      dataSource = KtorDataSource.Factory(httpClient).createDataSource()
      assertThat(dataSource.open(dataSpec)).isEqualTo(0)

      val originRequest =
        checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
      assertThat(originRequest.path).isEqualTo("/bar")
      assertThat(originRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val")
    }
  }

  @Test
  fun factory_setUserAgent_setsCorrectHeader() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse())

    val userAgent = "testUserAgent"
    dataSource = KtorDataSource.Factory(httpClient, userAgent = userAgent).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(request.getHeader("User-Agent")).isEqualTo(userAgent)
  }

  @Test
  fun factory_setContentTypePredicate_filtersContentType() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(
      MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
    )

    dataSource =
      KtorDataSource.Factory(
          httpClient,
          contentTypePredicate = { contentType -> contentType == "audio/mpeg" },
        )
        .createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val exception =
      assertThrows(HttpDataSource.InvalidContentTypeException::class.java) {
        dataSource.open(dataSpec)
      }

    assertThat(exception.contentType).isEqualTo("text/html")
  }

  @Test
  fun factory_setTransferListener_setsListener() {
    httpClient = HttpClient(httpClientEngineFactory) { configureTimeout() }
    mockWebServer.enqueue(MockResponse())

    var transferInitializingCalled = false
    var transferStartCalled = false
    var transferEndCalled = false

    val transferListener =
      object : TransferListener {
        override fun onTransferInitializing(
          source: DataSource,
          dataSpec: DataSpec,
          isNetwork: Boolean,
        ) {
          assertThat(source).isNotNull()
          assertThat(dataSpec).isNotNull()
          assertThat(isNetwork).isNotNull()
          transferInitializingCalled = true
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
          assertThat(source).isNotNull()
          assertThat(dataSpec).isNotNull()
          assertThat(isNetwork).isNotNull()
          transferStartCalled = true
        }

        override fun onBytesTransferred(
          source: DataSource,
          dataSpec: DataSpec,
          isNetwork: Boolean,
          bytesTransferred: Int,
        ) {

          assertThat(source).isNotNull()
          assertThat(dataSpec).isNotNull()
          assertThat(isNetwork).isNotNull()
          assertThat(bytesTransferred).isAtLeast(0)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
          transferEndCalled = true
        }
      }

    dataSource =
      KtorDataSource.Factory(httpClient, transferListener = transferListener).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)
    assertThat(transferInitializingCalled).isTrue()
    assertThat(transferStartCalled).isTrue()

    dataSource.close()
    assertThat(transferEndCalled).isTrue()
  }

  @Test
  fun open_doesNotWaitForResponseBody() {
    val testData = "a".repeat(100)
    val bodyDelayMs = 800L
    mockWebServer.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", testData.length)
        .setBody(testData)
        .setBodyDelay(bodyDelayMs, TimeUnit.MILLISECONDS)
    )

    httpClient =
      HttpClient(httpClientEngineFactory) {
        install(HttpTimeout) {
          requestTimeoutMillis = 3000
          connectTimeoutMillis = 3000
          socketTimeoutMillis = 3000
        }
      }
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val startTimeMs = SystemClock.elapsedRealtime()
    assertThat(dataSource.open(dataSpec)).isEqualTo(testData.length.toLong())
    val openDurationMs = SystemClock.elapsedRealtime() - startTimeMs

    // Verify that open() completed without waiting for the delayed response body.
    assertThat(openDurationMs).isLessThan(400L)

    val buffer = ByteArray(10)
    val bytesRead = dataSource.read(buffer, 0, buffer.size)
    val readDurationMs = SystemClock.elapsedRealtime() - startTimeMs
    assertThat(bytesRead).isEqualTo(10)
    assertThat(readDurationMs).isGreaterThan(bodyDelayMs)
  }

  // TODO: b/503301819 - Remove this when the OkHttp engine works without it.
  private fun HttpClientConfig<*>.configureTimeout() {
    install(HttpTimeout) {
      requestTimeoutMillis = 800
      connectTimeoutMillis = 800
      socketTimeoutMillis = 800
    }
  }
}
