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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import com.google.common.base.Predicate
import com.google.common.collect.Maps
import com.google.common.net.HttpHeaders
import com.google.errorprone.annotations.CanIgnoreReturnValue
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * An [HttpDataSource] that delegates to Ktor's [HttpClient].
 *
 * Note: HTTP request headers will be set using all parameters passed via (in order of decreasing
 * priority) the `dataSpec`, [setRequestProperty] and the default parameters used to construct the
 * instance.
 */
@UnstableApi
class KtorDataSource
private constructor(
  private val httpClient: HttpClient,
  private val userAgent: String?,
  private val defaultRequestProperties: HttpDataSource.RequestProperties?,
  private val contentTypePredicate: Predicate<String>?,
  private val requestProperties: HttpDataSource.RequestProperties,
) : BaseDataSource(/* isNetwork= */ true), HttpDataSource {

  private var dataSpec: DataSpec? = null
  private var response: HttpResponse? = null
  private var responseChannel: ByteReadChannel? = null
  private var connectionEstablished = false
  private var bytesToRead: Long = 0
  private var bytesRead: Long = 0

  override fun getUri(): Uri? =
    this.response?.request?.url?.let { Uri.parse(it.toString()) } ?: this.dataSpec?.uri

  override fun getResponseCode(): Int = response?.status?.value ?: -1

  override fun getResponseHeaders(): Map<String, List<String>> {
    val headers = response?.headers ?: return emptyMap()
    return Maps.asMap<String, List<String>>(headers.names()) { name ->
      headers.getAll(name) as List<String>
    }
  }

  override fun setRequestProperty(name: String, value: String) = requestProperties.set(name, value)

  override fun clearRequestProperty(name: String) = requestProperties.remove(name)

  override fun clearAllRequestProperties() = requestProperties.clear()

  @Throws(HttpDataSource.HttpDataSourceException::class)
  override fun open(dataSpec: DataSpec): Long {
    this.dataSpec = dataSpec
    bytesRead = 0
    bytesToRead = 0
    transferInitializing(dataSpec)

    val urlString = dataSpec.uri.toString()
    val uri = Uri.parse(urlString)
    val scheme = uri.scheme
    if (scheme == null || !scheme.lowercase().startsWith("http")) {
      throw HttpDataSource.HttpDataSourceException(
        "Malformed URL",
        dataSpec,
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
    }

    val mergedHeaders = HashMap<String, String>()
    defaultRequestProperties?.snapshot?.let { properties -> mergedHeaders.putAll(properties) }
    mergedHeaders.putAll(requestProperties.snapshot)
    mergedHeaders.putAll(dataSpec.httpRequestHeaders)

    val httpResponse: HttpResponse
    val channel: ByteReadChannel
    try {
      runBlocking {
        httpResponse =
          httpClient
            .prepareRequest {
              url(urlString)

              headers {
                for ((key, value) in mergedHeaders) {
                  set(key, value)
                }

                val rangeHeader =
                  HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)
                rangeHeader?.let { set(HttpHeaders.RANGE, rangeHeader) }

                userAgent?.let { set(HttpHeaders.USER_AGENT, userAgent) }

                if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
                  set(HttpHeaders.ACCEPT_ENCODING, "identity")
                }
              }

              method =
                when (dataSpec.httpMethod) {
                  DataSpec.HTTP_METHOD_GET -> HttpMethod.Get
                  DataSpec.HTTP_METHOD_POST -> HttpMethod.Post
                  DataSpec.HTTP_METHOD_HEAD -> HttpMethod.Head
                  else -> HttpMethod.Get
                }

              if (dataSpec.httpBody != null) {
                setBody(dataSpec.httpBody!!)
              }
            }
            .execute()
        channel = httpResponse.bodyAsChannel()
      }
      this.response = httpResponse
      this.responseChannel = channel
    } catch (e: CancellationException) {
      throw InterruptedIOException(e.message)
    } catch (e: IOException) {
      throw HttpDataSource.HttpDataSourceException.createForIOException(
        e,
        dataSpec,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
    }

    val responseCode = httpResponse.status.value

    if (responseCode !in 200..299) {
      if (responseCode == 416) {
        val contentRange = httpResponse.headers[HttpHeaders.CONTENT_RANGE]
        val documentSize = HttpUtil.getDocumentSize(contentRange)
        if (dataSpec.position == documentSize) {
          connectionEstablished = true
          transferStarted(dataSpec)
          return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
        }
      }

      val errorResponseBody: ByteArray =
        try {
          runBlocking {
            val ch = responseChannel ?: return@runBlocking Util.EMPTY_BYTE_ARRAY
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!ch.isClosedForRead) {
              val read = ch.readAvailable(buffer, 0, buffer.size)
              if (read > 0) {
                output.write(buffer, 0, read)
              }
            }
            val bytes = output.toByteArray()
            if (bytes.isNotEmpty()) bytes else Util.EMPTY_BYTE_ARRAY
          }
        } catch (_: Exception) {
          Util.EMPTY_BYTE_ARRAY
        }

      val headers = getResponseHeaders()
      closeConnectionQuietly()

      val cause: IOException? =
        if (responseCode == 416) {
          DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } else {
          null
        }

      throw HttpDataSource.InvalidResponseCodeException(
        responseCode,
        httpResponse.status.description,
        cause,
        headers,
        dataSpec,
        errorResponseBody,
      )
    }

    val contentType = httpResponse.contentType()?.toString() ?: ""
    if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
      closeConnectionQuietly()
      throw HttpDataSource.InvalidContentTypeException(contentType, dataSpec)
    }

    val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L

    if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
      bytesToRead = dataSpec.length
    } else {
      val contentLength =
        HttpUtil.getContentLength(
          httpResponse.headers[HttpHeaders.CONTENT_LENGTH],
          httpResponse.headers[HttpHeaders.CONTENT_RANGE],
        )
      bytesToRead =
        if (contentLength != C.LENGTH_UNSET.toLong()) contentLength - bytesToSkip
        else C.LENGTH_UNSET.toLong()
    }

    connectionEstablished = true
    transferStarted(dataSpec)

    try {
      runBlocking { skipFully(bytesToSkip, dataSpec) }
    } catch (e: HttpDataSource.HttpDataSourceException) {
      closeConnectionQuietly()
      throw e
    } catch (e: CancellationException) {
      closeConnectionQuietly()
      throw InterruptedIOException(e.message)
    }

    return bytesToRead
  }

  @Throws(HttpDataSource.HttpDataSourceException::class)
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return try {
      runBlocking { readInternal(buffer, offset, length) }
    } catch (_: CancellationException) {
      throw HttpDataSource.HttpDataSourceException(
        InterruptedIOException(),
        dataSpec!!,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        HttpDataSource.HttpDataSourceException.TYPE_READ,
      )
    } catch (e: IOException) {
      throw HttpDataSource.HttpDataSourceException.createForIOException(
        e,
        dataSpec!!,
        HttpDataSource.HttpDataSourceException.TYPE_READ,
      )
    } catch (e: Exception) {
      throw HttpDataSource.HttpDataSourceException(
        e.message ?: "Unknown error",
        null,
        dataSpec!!,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        HttpDataSource.HttpDataSourceException.TYPE_READ,
      )
    }
  }

  override fun close() {
    if (connectionEstablished) {
      connectionEstablished = false
      transferEnded()
      closeConnectionQuietly()
    }
    response = null
    dataSpec = null
  }

  @Throws(HttpDataSource.HttpDataSourceException::class)
  private suspend fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
    if (bytesToSkip == 0L) return

    val skipBuffer = ByteArray(4096)
    var remaining = bytesToSkip

    try {
      val channel = responseChannel ?: throw IOException("Channel closed")
      while (remaining > 0) {
        val readLength = min(remaining.toInt(), skipBuffer.size)
        val read = channel.readAvailable(skipBuffer, 0, readLength)

        if (read < 0) {
          throw HttpDataSource.HttpDataSourceException(
            dataSpec,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
          )
        }

        remaining -= read
        bytesTransferred(read)
      }
    } catch (e: IOException) {
      if (e is HttpDataSource.HttpDataSourceException) throw e
      throw HttpDataSource.HttpDataSourceException(
        dataSpec,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
    }
  }

  @Throws(IOException::class)
  private suspend fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
    if (readLength == 0) return 0

    if (bytesToRead != C.LENGTH_UNSET.toLong()) {
      val bytesRemaining = bytesToRead - bytesRead
      if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

      val actualReadLength = min(readLength.toLong(), bytesRemaining).toInt()
      return readFromChannel(buffer, offset, actualReadLength)
    }

    return readFromChannel(buffer, offset, readLength)
  }

  @Throws(IOException::class)
  private suspend fun readFromChannel(buffer: ByteArray, offset: Int, readLength: Int): Int {
    val channel = responseChannel ?: return C.RESULT_END_OF_INPUT
    val read = channel.readAvailable(buffer, offset, readLength)

    if (read < 0) return C.RESULT_END_OF_INPUT

    bytesRead += read
    bytesTransferred(read)
    return read
  }

  private fun closeConnectionQuietly() {
    responseChannel?.cancel(null)
    responseChannel = null
  }

  /**
   * [androidx.media3.datasource.DataSource.Factory] for [KtorDataSource] instances.
   *
   * @param httpClient A [HttpClient] for use by the sources created by the factory.
   * @param userAgent The user agent that will be used for requests. The default is `null`, which
   *   causes the default user agent of the underlying [HttpClient] to be used.
   * @param contentTypePredicate An optional content type [Predicate]. If a content type is rejected
   *   by the predicate then a [HttpDataSource.InvalidContentTypeException] is thrown from
   *   [KtorDataSource.open].
   * @param transferListener An optional transfer listener. See
   *   [androidx.media3.datasource.DataSource.addTransferListener].
   */
  class Factory(
    private val httpClient: HttpClient,
    private val userAgent: String? = null,
    private val contentTypePredicate: Predicate<String>? = null,
    private val transferListener: TransferListener? = null,
  ) : HttpDataSource.Factory {

    private val defaultRequestProperties = HttpDataSource.RequestProperties()

    @CanIgnoreReturnValue
    override fun setDefaultRequestProperties(
      defaultRequestProperties: Map<String, String>
    ): Factory {
      this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
      return this
    }

    override fun createDataSource(): KtorDataSource {
      val client = httpClient
      val dataSource =
        KtorDataSource(
          client,
          userAgent,
          defaultRequestProperties,
          contentTypePredicate,
          HttpDataSource.RequestProperties(),
        )
      transferListener?.let { dataSource.addTransferListener(it) }
      return dataSource
    }
  }

  companion object {
    private const val TAG = "KtorDataSource"

    init {
      MediaLibraryInfo.registerModule("media3.datasource.ktor")
    }
  }
}
