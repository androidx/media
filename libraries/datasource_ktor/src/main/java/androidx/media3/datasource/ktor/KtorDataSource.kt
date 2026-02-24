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
import com.google.common.net.HttpHeaders
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
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An [HttpDataSource] that delegates to Ktor's [HttpClient].
 *
 * Note: HTTP request headers will be set using all parameters passed via (in order of decreasing
 * priority) the `dataSpec`, [setRequestProperty] and the default parameters used to construct the
 * instance.
 */
class KtorDataSource
private constructor(
  private val httpClient: HttpClient,
  private val coroutineScope: CoroutineScope,
  private val userAgent: String?,
  private val cacheControl: String?,
  private val defaultRequestProperties: HttpDataSource.RequestProperties?,
  private val contentTypePredicate: Predicate<String>?,
  private val requestProperties: HttpDataSource.RequestProperties,
) : BaseDataSource(true), HttpDataSource {

  companion object {
    private const val TAG = "KtorDataSource"

    init {
      MediaLibraryInfo.registerModule("media3.datasource.ktor")
    }
  }

  /**
   * [androidx.media3.datasource.DataSource.Factory] for [KtorDataSource] instances.
   *
   * @param httpClient A [HttpClient] for use by the sources created by the factory.
   * @param scope A [CoroutineScope] for running suspend functions. If not provided, a default scope
   *   with [Dispatchers.IO] and a [SupervisorJob] will be created.
   */
  class Factory(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  ) : HttpDataSource.Factory {

    private val defaultRequestProperties = HttpDataSource.RequestProperties()

    private var userAgent: String? = null

    private var cacheControl: String? = null

    private var transferListener: TransferListener? = null

    private var contentTypePredicate: Predicate<String>? = null

    @UnstableApi
    override fun setDefaultRequestProperties(
      defaultRequestProperties: Map<String, String>
    ): Factory {
      this.defaultRequestProperties.clearAndSet(defaultRequestProperties)
      return this
    }

    /**
     * Sets the user agent that will be used.
     *
     * The default is `null`, which causes the default user agent of the underlying [HttpClient] to
     * be used.
     *
     * @param userAgent The user agent that will be used, or `null` to use the default user agent of
     *   the underlying [HttpClient].
     * @return This factory.
     */
    fun setUserAgent(userAgent: String?): Factory {
      this.userAgent = userAgent
      return this
    }

    /**
     * Sets the Cache-Control header that will be used.
     *
     * The default is `null`.
     *
     * @param cacheControl The cache control header value that will be used, or `null` to clear a
     *   previously set value.
     * @return This factory.
     */
    @UnstableApi
    fun setCacheControl(cacheControl: String?): Factory {
      this.cacheControl = cacheControl
      return this
    }

    /**
     * Sets a content type [Predicate]. If a content type is rejected by the predicate then a
     * [HttpDataSource.InvalidContentTypeException] is thrown from [KtorDataSource.open].
     *
     * The default is `null`.
     *
     * @param contentTypePredicate The content type [Predicate], or `null` to clear a predicate that
     *   was previously set.
     * @return This factory.
     */
    @UnstableApi
    fun setContentTypePredicate(contentTypePredicate: Predicate<String>?): Factory {
      this.contentTypePredicate = contentTypePredicate
      return this
    }

    /**
     * Sets the [TransferListener] that will be used.
     *
     * The default is `null`.
     *
     * See [androidx.media3.datasource.DataSource.addTransferListener].
     *
     * @param transferListener The listener that will be used.
     * @return This factory.
     */
    @UnstableApi
    fun setTransferListener(transferListener: TransferListener?): Factory {
      this.transferListener = transferListener
      return this
    }

    @UnstableApi
    override fun createDataSource(): KtorDataSource {
      val client = httpClient
      val dataSource =
        KtorDataSource(
          client,
          scope,
          userAgent,
          cacheControl,
          defaultRequestProperties,
          contentTypePredicate,
          HttpDataSource.RequestProperties(),
        )
      transferListener?.let { dataSource.addTransferListener(it) }
      return dataSource
    }
  }

  private var dataSpec: DataSpec? = null

  private var response: HttpResponse? = null

  private var responseInputStream: java.io.InputStream? = null

  private var currentJob: Job? = null

  private var connectionEstablished = false
  private var bytesToRead: Long = 0
  private var bytesRead: Long = 0

  @UnstableApi
  override fun getUri(): Uri? {
    return if (response != null) {
      Uri.parse(response!!.request.url.toString())
    } else if (dataSpec != null) {
      dataSpec!!.uri
    } else {
      null
    }
  }

  @UnstableApi
  override fun getResponseCode(): Int {
    return response?.status?.value ?: -1
  }

  @UnstableApi
  override fun getResponseHeaders(): Map<String, List<String>> {
    val httpResponse = response ?: return emptyMap()
    val headers = TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
    httpResponse.headers.names().forEach { name ->
      headers[name] = httpResponse.headers.getAll(name) ?: emptyList()
    }
    return headers
  }

  @UnstableApi
  override fun setRequestProperty(name: String, value: String) {
    requireNotNull(name) { "name cannot be null" }
    requireNotNull(value) { "value cannot be null" }
    requestProperties.set(name, value)
  }

  @UnstableApi
  override fun clearRequestProperty(name: String) {
    requireNotNull(name) { "name cannot be null" }
    requestProperties.remove(name)
  }

  @UnstableApi
  override fun clearAllRequestProperties() {
    requestProperties.clear()
  }

  @UnstableApi
  @Throws(HttpDataSource.HttpDataSourceException::class)
  override fun open(dataSpec: DataSpec): Long {
    this.dataSpec = dataSpec
    bytesRead = 0
    bytesToRead = 0
    transferInitializing(dataSpec)

    try {
      val httpResponse = executeRequest(dataSpec)
      this.response = httpResponse
      this.responseInputStream = executeSuspend { httpResponse.bodyAsChannel().toInputStream() }
    } catch (e: IOException) {
      if (e is HttpDataSource.HttpDataSourceException) throw e
      throw HttpDataSource.HttpDataSourceException.createForIOException(
        e,
        dataSpec,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
      )
    }

    val httpResponse = this.response!!
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
          responseInputStream?.readBytes() ?: Util.EMPTY_BYTE_ARRAY
        } catch (e: IOException) {
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
      val contentLength = httpResponse.contentLength() ?: -1L
      bytesToRead = if (contentLength >= 0) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
    }

    connectionEstablished = true
    transferStarted(dataSpec)

    try {
      skipFully(bytesToSkip, dataSpec)
    } catch (e: HttpDataSource.HttpDataSourceException) {
      closeConnectionQuietly()
      throw e
    }

    return bytesToRead
  }

  @UnstableApi
  @Throws(HttpDataSource.HttpDataSourceException::class)
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return try {
      readInternal(buffer, offset, length)
    } catch (e: IOException) {
      throw HttpDataSource.HttpDataSourceException.createForIOException(
        e,
        dataSpec!!,
        HttpDataSource.HttpDataSourceException.TYPE_READ,
      )
    }
  }

  @UnstableApi
  override fun close() {
    if (connectionEstablished) {
      connectionEstablished = false
      transferEnded()
      closeConnectionQuietly()
    }
    response = null
    dataSpec = null
  }

  @Throws(IOException::class)
  private fun executeRequest(dataSpec: DataSpec): HttpResponse {
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
    defaultRequestProperties?.snapshot?.forEach { (key, value) -> mergedHeaders[key] = value }
    requestProperties.snapshot.forEach { (key, value) -> mergedHeaders[key] = value }
    dataSpec.httpRequestHeaders.forEach { (key, value) -> mergedHeaders[key] = value }

    return executeSuspend {
      httpClient
        .prepareRequest {
          url(urlString)

          headers {
            mergedHeaders.forEach { (key, value) -> append(key, value) }

            val rangeHeader = HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)
            if (rangeHeader != null) {
              append(HttpHeaders.RANGE, rangeHeader)
            }

            if (userAgent != null) {
              append(HttpHeaders.USER_AGENT, userAgent)
            }

            if (cacheControl != null) {
              append(HttpHeaders.CACHE_CONTROL, cacheControl)
            }

            if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
              append(HttpHeaders.ACCEPT_ENCODING, "identity")
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
          } else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
            setBody(ByteArray(0))
          }
        }
        .execute()
    }
  }

  @Throws(IOException::class)
  private fun <T> executeSuspend(block: suspend () -> T): T {
    val exceptionRef = AtomicReference<Throwable?>(null)
    val resultRef = AtomicReference<T?>(null)
    val latch = CountDownLatch(1)

    currentJob =
      coroutineScope.launch {
        try {
          resultRef.set(block())
        } catch (e: CancellationException) {
          exceptionRef.set(InterruptedIOException())
        } catch (e: Exception) {
          exceptionRef.set(e)
        } finally {
          latch.countDown()
        }
      }

    try {
      latch.await()
    } catch (e: InterruptedException) {
      currentJob?.cancel()
      throw InterruptedIOException()
    }

    exceptionRef.get()?.let { throwable ->
      when (throwable) {
        is IOException -> throw throwable
        is InterruptedIOException -> throw throwable
        else -> throw IOException(throwable)
      }
    }

    @Suppress("UNCHECKED_CAST")
    return resultRef.get() as T
  }

  @Throws(HttpDataSource.HttpDataSourceException::class)
  private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
    if (bytesToSkip == 0L) return

    val skipBuffer = ByteArray(4096)
    var remaining = bytesToSkip

    try {
      val inputStream = responseInputStream ?: throw IOException("Stream closed")
      while (remaining > 0) {
        val readLength = min(remaining.toInt(), skipBuffer.size)
        val read = inputStream.read(skipBuffer, 0, readLength)

        if (Thread.currentThread().isInterrupted) {
          throw InterruptedIOException()
        }

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
  private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
    if (readLength == 0) return 0

    if (bytesToRead != C.LENGTH_UNSET.toLong()) {
      val bytesRemaining = bytesToRead - bytesRead
      if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

      val actualReadLength = min(readLength.toLong(), bytesRemaining).toInt()
      return readFromStream(buffer, offset, actualReadLength)
    }

    return readFromStream(buffer, offset, readLength)
  }

  @Throws(IOException::class)
  private fun readFromStream(buffer: ByteArray, offset: Int, readLength: Int): Int {
    val inputStream = responseInputStream ?: return C.RESULT_END_OF_INPUT
    val read = inputStream.read(buffer, offset, readLength)

    if (read < 0) return C.RESULT_END_OF_INPUT

    bytesRead += read
    bytesTransferred(read)
    return read
  }

  private fun closeConnectionQuietly() {
    responseInputStream?.close()
    responseInputStream = null
    currentJob = null
  }
}
