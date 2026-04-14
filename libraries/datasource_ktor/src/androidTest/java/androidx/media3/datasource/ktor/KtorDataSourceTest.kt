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

import androidx.media3.datasource.DataSource
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
import io.ktor.client.plugins.cookies.HttpCookies
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class KtorDataSourceTest {

  // TODO: b/502560161 - Switch to KotlinTestParameters.namedTestValues after upgrading to
  //  TestParameterInjector v1.22+.
  class ClientEngineFactoryProvider : TestParameterValuesProvider() {
    override fun provideValues(context: Context?): List<*>? {
      return listOf(value(Android).withName("Android"), value(OkHttp).withName("OkHttp"))
    }
  }

  @Test
  @Throws(Exception::class)
  fun open_setsCorrectHeaders(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(MockResponse())

    val propertyFromFactory = "fromFactory"
    val defaultRequestProperties = HashMap<String, String>()
    defaultRequestProperties["0"] = propertyFromFactory
    defaultRequestProperties["1"] = propertyFromFactory
    defaultRequestProperties["2"] = propertyFromFactory
    defaultRequestProperties["4"] = propertyFromFactory

    val dataSource =
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

    val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
    assertThat(request).isNotNull()
    val headers = request!!.headers
    assertThat(headers["0"]).isEqualTo(propertyFromFactory)
    assertThat(headers["1"]).isEqualTo(propertyFromSetter)
    assertThat(headers["2"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["3"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["4"]).isEqualTo(propertyFromDataSpec)
    assertThat(headers["5"]).isEqualTo(propertyFromSetter)
    assertThat(headers["6"]).isEqualTo(propertyFromDataSpec)
  }

  @Test
  fun open_invalidResponseCode(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("failure msg"))

    val dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val exception =
      assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
        dataSource.open(dataSpec)
      }

    assertThat(exception.responseCode).isEqualTo(404)
    assertThat(exception.responseBody).isEqualTo("failure msg".toByteArray(StandardCharsets.UTF_8))
  }

  @Test
  @Throws(Exception::class)
  fun factory_setRequestPropertyAfterCreation_setsCorrectHeaders(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(MockResponse())
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    val factory = KtorDataSource.Factory(httpClient)
    val dataSource = factory.createDataSource()

    val defaultRequestProperties = HashMap<String, String>()
    defaultRequestProperties["0"] = "afterCreation"
    factory.setDefaultRequestProperties(defaultRequestProperties)
    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
    assertThat(request).isNotNull()
    val headers = request!!.headers
    assertThat(headers["0"]).isEqualTo("afterCreation")
  }

  @Test
  fun open_malformedUrl_throwsException(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec = DataSpec.Builder().setUri("not-a-valid-url").build()

    val exception =
      assertThrows(HttpDataSource.HttpDataSourceException::class.java) { dataSource.open(dataSpec) }

    assertThat(exception.message).contains("Malformed URL")
  }

  @Test
  @Throws(Exception::class)
  fun open_httpPost_sendsPostRequest(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(MockResponse())

    val dataSource = KtorDataSource.Factory(httpClient).createDataSource()

    val dataSpec =
      DataSpec.Builder()
        .setUri(mockWebServer.url("/test-path").toString())
        .setHttpMethod(DataSpec.HTTP_METHOD_POST)
        .setHttpBody("test body".toByteArray(StandardCharsets.UTF_8))
        .build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
    assertThat(request).isNotNull()
    assertThat(request!!.method).isEqualTo("POST")
    assertThat(request.body.readUtf8()).isEqualTo("test body")
  }

  @Test
  @Throws(java.lang.Exception::class)
  fun cookiesConfigured_cookiesPersistedBetweenRequests(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory) { install(HttpCookies) }
    MockWebServer().use { mockWebServer ->
      mockWebServer.enqueue(
        MockResponse().setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val")
      )
      mockWebServer.enqueue(MockResponse())

      val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("foo").toString()).build()
      val dataSource: KtorDataSource = KtorDataSource.Factory(httpClient).createDataSource()
      dataSource.open(dataSpec)
      dataSource.close()
      dataSource.open(dataSpec)

      val firstRequest = mockWebServer.takeRequest()
      assertThat(firstRequest.path).isEqualTo("/foo")
      assertThat(firstRequest.getHeader(HttpHeaders.COOKIE)).isNull()

      val secondRequest = mockWebServer.takeRequest()
      assertThat(secondRequest.path).isEqualTo("/foo")
      assertThat(secondRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val")
    }
  }

  @Test
  @Throws(java.lang.Exception::class)
  fun cookiesConfigured_cookiesForwardedOnRedirect(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory) { install(HttpCookies) }
    MockWebServer().use { redirectWebServer ->
      MockWebServer().use { originWebServer ->
        val originUrl = originWebServer.url("bar").toString()
        redirectWebServer.enqueue(
          MockResponse()
            .setResponseCode(302)
            .setHeader(HttpHeaders.SET_COOKIE, "cookie-name=cookie-val; Path=/")
            .setHeader(HttpHeaders.LOCATION, originUrl)
        )
        originWebServer.enqueue(MockResponse())

        val redirectUrl = redirectWebServer.url("foo").toString()
        val dataSpec = DataSpec.Builder().setUri(redirectUrl).build()
        val dataSource: KtorDataSource = KtorDataSource.Factory(httpClient).createDataSource()
        dataSource.open(dataSpec)

        val originRequest = originWebServer.takeRequest()
        assertThat(originRequest.path).isEqualTo("/bar")
        assertThat(originRequest.getHeader(HttpHeaders.COOKIE)).isEqualTo("cookie-name=cookie-val")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun factory_setUserAgent_setsCorrectHeader(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(MockResponse())

    val userAgent = "testUserAgent"
    val dataSource = KtorDataSource.Factory(httpClient, userAgent = userAgent).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)

    val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
    assertThat(request).isNotNull()
    assertThat(request!!.getHeader("User-Agent")).isEqualTo(userAgent)
  }

  @Test
  @Throws(Exception::class)
  fun factory_setContentTypePredicate_filtersContentType(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
    mockWebServer.enqueue(
      MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
    )

    val dataSource =
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
  @Throws(Exception::class)
  fun factory_setTransferListener_setsListener(
    @TestParameter(valuesProvider = ClientEngineFactoryProvider::class)
    httpClientEngineFactory: HttpClientEngineFactory<*>
  ) {
    val httpClient = HttpClient(httpClientEngineFactory)
    val mockWebServer = MockWebServer()
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

    val dataSource =
      KtorDataSource.Factory(httpClient, transferListener = transferListener).createDataSource()
    val dataSpec = DataSpec.Builder().setUri(mockWebServer.url("/test-path").toString()).build()

    assertThat(dataSource.open(dataSpec)).isEqualTo(0)
    assertThat(transferInitializingCalled).isTrue()
    assertThat(transferStartCalled).isTrue()

    dataSource.close()
    assertThat(transferEndCalled).isTrue()
  }
}
