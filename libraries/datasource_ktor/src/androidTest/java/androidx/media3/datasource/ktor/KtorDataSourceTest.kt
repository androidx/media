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

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.statement.HttpResponse
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class KtorDataSourceTest(
  @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
  private val httpClientEngineFactory: HttpClientEngineFactory<*>
) {

  private class ClientEngineFactoryProvider : TestParameterValuesProvider() {
    override fun provideValues(context: Context?): List<*>? {
      return listOf(value(Android).withName("Android"), value(OkHttp).withName("OkHttp"))
    }
  }

  private val mockWebServer = MockWebServer()

  private lateinit var httpClient: HttpClient
  private lateinit var dataSource: KtorDataSource

  @Before
  fun setUp() {
    mockWebServer.start()
  }

  @After
  fun tearDown() {
    if (::dataSource.isInitialized) dataSource.close()
    if (::httpClient.isInitialized) httpClient.close()
    mockWebServer.close()
  }

  @Test
  fun open_setsCorrectHeaders() {
    httpClient = HttpClient(httpClientEngineFactory)
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
    httpClient = HttpClient(httpClientEngineFactory)
    mockWebServer.enqueue(MockResponse(code = 404, body = "failure msg"))

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
    httpClient = HttpClient(httpClientEngineFactory)
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
    httpClient = HttpClient(httpClientEngineFactory)
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec = DataSpec.Builder().setUri("not-a-valid-url").build()

    val exception =
      assertThrows(HttpDataSource.HttpDataSourceException::class.java) { dataSource.open(dataSpec) }

    assertThat(exception.message).contains("Malformed URL")
  }

  @Test
  fun open_httpPost_sendsPostRequest() {
    httpClient = HttpClient(httpClientEngineFactory)
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
    assertThat(request.body?.utf8()).isEqualTo("test body")
  }

  @Test
  fun cookiesConfigured_cookiesPersistedBetweenRequests() {
    httpClient = HttpClient(httpClientEngineFactory) { install(HttpCookies) }
    mockWebServer.enqueue(
      MockResponse.Builder().setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val").build()
    )
    mockWebServer.enqueue(MockResponse())

    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("foo").toString()).build()
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)
    dataSource.close()
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val firstRequest =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(firstRequest.target).isEqualTo("/foo")
    assertThat(firstRequest.headers[HttpHeaders.COOKIE]).isNull()

    val secondRequest =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(secondRequest.target).isEqualTo("/foo")
    assertThat(secondRequest.headers[HttpHeaders.COOKIE]).isEqualTo("cookie-name=cookie-val")
  }

  @Test
  fun cookiesConfigured_cookiesForwardedOnRedirect() {
    httpClient = HttpClient(httpClientEngineFactory) { install(HttpCookies) }
    MockWebServer().use { redirectWebServer ->
      redirectWebServer.start()
      val originUrl = mockWebServer.url("bar").toString()
      redirectWebServer.enqueue(
        MockResponse.Builder()
          .code(302)
          .setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val; Path=/")
          .setHeader(HttpHeaders.LOCATION, originUrl)
          .build()
      )
      mockWebServer.enqueue(MockResponse())

      val redirectUrl = redirectWebServer.url("foo").toString()
      val dataSpec = DataSpec.Builder().setUri(redirectUrl).build()
      dataSource = KtorDataSource.Factory(httpClient).createDataSource()
      assertThat(dataSource.open(dataSpec)).isEqualTo(0)

      val originRequest =
        checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
      assertThat(originRequest.target).isEqualTo("/bar")
      assertThat(originRequest.headers[HttpHeaders.COOKIE]).isEqualTo("cookie-name=cookie-val")
    }
  }

  @Test
  fun factory_setUserAgent_setsCorrectHeader() {
    httpClient = HttpClient(httpClientEngineFactory)
    mockWebServer.enqueue(MockResponse())

    val userAgent = "testUserAgent"
    dataSource = KtorDataSource.Factory(httpClient, userAgent = userAgent).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request =
      checkNotNull(mockWebServer.takeRequest(10, TimeUnit.SECONDS)) { "No request received." }
    assertThat(request.headers["User-Agent"]).isEqualTo(userAgent)
  }

  @Test
  fun factory_setContentTypePredicate_filtersContentType() {
    httpClient = HttpClient(httpClientEngineFactory)
    mockWebServer.enqueue(
      MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").build()
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
    httpClient = HttpClient(httpClientEngineFactory)
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
    val responseBody = CompletableDeferred<String>()
    mockWebServer.enqueue(
      MockResponse.Builder()
        .code(200)
        .body(createDeferredResponseBody(testData.length.toLong(), responseBody))
        .build()
    )
    httpClient = HttpClient(httpClientEngineFactory)
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    try {
      // open() must return while the server is still blocked from writing the body.
      assertThat(dataSource.open(dataSpec)).isEqualTo(testData.length.toLong())

      responseBody.complete(testData)
      assertThat(DataSourceUtil.readToEnd(dataSource))
        .isEqualTo(testData.toByteArray(StandardCharsets.UTF_8))
    } finally {
      responseBody.cancel()
    }
  }

  @Test
  fun read_connectionClosedBeforeContentLengthReached_throwsHttpDataSourceException() {
    mockWebServer.enqueue(
      MockResponse.Builder()
        .body("a".repeat(100))
        .setHeader(HttpHeaders.CONTENT_LENGTH, 200)
        .onResponseEnd(SocketEffect.ShutdownConnection)
        .build()
    )
    var response: HttpResponse? = null
    httpClient =
      HttpClient(httpClientEngineFactory) {
        install(createClientPlugin("CaptureResponse") { onResponse { response = it } })
      }
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    assertThat(
        dataSource.open(
          DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()
        )
      )
      .isEqualTo(200)
    // Wait until Ktor has finished receiving the response body, so that the connection failure has
    // already been recorded by the time the body is read.
    runBlocking { withTimeout(5_000) { checkNotNull(response).coroutineContext.job.join() } }

    val exception =
      assertThrows(HttpDataSource.HttpDataSourceException::class.java) {
        DataSourceUtil.readToEnd(dataSource)
      }

    assertThat(exception.reason)
      .isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
  }

  @Test
  fun read_responseBodyTimeout_throwsNetworkConnectionTimeoutException() {
    val responseBody = CompletableDeferred<String>()
    mockWebServer.enqueue(
      MockResponse.Builder()
        .code(200)
        .body(createDeferredResponseBody(contentLength = 100, responseBody))
        .build()
    )
    httpClient =
      HttpClient(httpClientEngineFactory) { install(HttpTimeout) { socketTimeoutMillis = 1_000 } }
    dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    try {
      assertThat(
          dataSource.open(
            DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()
          )
        )
        .isEqualTo(100)

      val exception =
        assertThrows(HttpDataSource.HttpDataSourceException::class.java) {
          DataSourceUtil.readToEnd(dataSource)
        }

      assertThat(exception.reason)
        .isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
      assertThat(exception).hasCauseThat().isInstanceOf(SocketTimeoutException::class.java)
    } finally {
      responseBody.cancel()
    }
  }

  @Test
  fun open_connectionClosedDuringSkip_throwsNetworkConnectionFailedException() {
    mockWebServer.enqueue(
      MockResponse.Builder()
        .body("a".repeat(100))
        .setHeader(HttpHeaders.CONTENT_LENGTH, 200)
        .onResponseEnd(SocketEffect.ShutdownConnection)
        .build()
    )
    var response: HttpResponse? = null
    httpClient =
      HttpClient(httpClientEngineFactory) {
        install(createClientPlugin("CaptureResponse") { onResponse { response = it } })
      }
    dataSource =
      KtorDataSource.Factory(
          httpClient,
          contentTypePredicate = {
            // Wait until Ktor has finished receiving the response body, so that the connection
            // failure has already been recorded by the time skipFully() reads from the channel.
            runBlocking {
              withTimeout(5_000) { checkNotNull(response).coroutineContext.job.join() }
            }
            true
          },
        )
        .createDataSource()
    val dataSpec =
      DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).setPosition(150).build()

    val exception =
      assertThrows(HttpDataSource.HttpDataSourceException::class.java) { dataSource.open(dataSpec) }

    assertThat(exception.reason)
      .isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
  }

  private fun createDeferredResponseBody(
    contentLength: Long,
    body: Deferred<String>,
  ): MockResponseBody =
    object : MockResponseBody {
      override val contentLength: Long = contentLength

      override fun writeTo(sink: BufferedSink) {
        try {
          sink.writeUtf8(runBlocking { body.await() })
          // Flush explicitly because mockwebserver3 < 5.5.0 calls emit() rather than flush().
          sink.flush()
        } catch (_: CancellationException) {
          // The test ended before supplying a response body.
        }
      }
    }
}
