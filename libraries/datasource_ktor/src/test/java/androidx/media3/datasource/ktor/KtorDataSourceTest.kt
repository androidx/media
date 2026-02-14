package androidx.media3.datasource.ktor

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KtorDataSourceTest {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val httpClient = HttpClient()

    @Test
    @Throws(Exception::class)
    fun open_setsCorrectHeaders() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse())

        val propertyFromFactory = "fromFactory"
        val defaultRequestProperties = HashMap<String, String>()
        defaultRequestProperties["0"] = propertyFromFactory
        defaultRequestProperties["1"] = propertyFromFactory
        defaultRequestProperties["2"] = propertyFromFactory
        defaultRequestProperties["4"] = propertyFromFactory
        
        val dataSource = KtorDataSource.Factory(httpClient, coroutineScope)
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

        val dataSpec = DataSpec.Builder()
            .setUri(mockWebServer.url("/test-path").toString())
            .setHttpRequestHeaders(dataSpecRequestProperties)
            .build()

        dataSource.open(dataSpec)

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
    fun open_invalidResponseCode() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("failure msg"))

        val dataSource = KtorDataSource.Factory(httpClient, coroutineScope).createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(mockWebServer.url("/test-path").toString())
            .build()

        val exception = assertThrows(
            HttpDataSource.InvalidResponseCodeException::class.java
        ) { dataSource.open(dataSpec) }

        assertThat(exception.responseCode).isEqualTo(404)
        assertThat(exception.responseBody).isEqualTo("failure msg".toByteArray(StandardCharsets.UTF_8))
    }

    @Test
    @Throws(Exception::class)
    fun factory_setRequestPropertyAfterCreation_setsCorrectHeaders() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse())
        val dataSpec = DataSpec.Builder()
            .setUri(mockWebServer.url("/test-path").toString())
            .build()
        
        val factory = KtorDataSource.Factory(httpClient, coroutineScope)
        val dataSource = factory.createDataSource()

        val defaultRequestProperties = HashMap<String, String>()
        defaultRequestProperties["0"] = "afterCreation"
        factory.setDefaultRequestProperties(defaultRequestProperties)
        dataSource.open(dataSpec)

        val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
        assertThat(request).isNotNull()
        val headers = request!!.headers
        assertThat(headers["0"]).isEqualTo("afterCreation")
    }

    @Test
    fun open_malformedUrl_throwsException() {
        val dataSource = KtorDataSource.Factory(httpClient, coroutineScope).createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri("not-a-valid-url")
            .build()

        val exception = assertThrows(
            HttpDataSource.HttpDataSourceException::class.java
        ) { dataSource.open(dataSpec) }

        assertThat(exception.message).contains("Malformed URL")
    }

    @Test
    @Throws(Exception::class)
    fun open_httpPost_sendsPostRequest() {
        val mockWebServer = MockWebServer()
        mockWebServer.enqueue(MockResponse())

        val dataSource = KtorDataSource.Factory(httpClient, coroutineScope).createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(mockWebServer.url("/test-path").toString())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody("test body".toByteArray(StandardCharsets.UTF_8))
            .build()

        dataSource.open(dataSpec)

        val request = mockWebServer.takeRequest(10, TimeUnit.SECONDS)
        assertThat(request).isNotNull()
        assertThat(request!!.method).isEqualTo("POST")
        assertThat(request.body.readUtf8()).isEqualTo("test body")
    }
}
