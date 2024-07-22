/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.datasource.HttpUtil.buildRangeRequestHeader;
import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.datasource.HttpDataSource.InvalidContentTypeException;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.datasource.HttpUtil;
import androidx.media3.datasource.TransferListener;
import com.google.common.base.Predicate;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * An {@link HttpDataSource} that delegates to Square's {@link Call.Factory}.
 *
 * <p>Note: HTTP request headers will be set using all parameters passed via (in order of decreasing
 * priority) the {@code dataSpec}, {@link #setRequestProperty} and the default parameters used to
 * construct the instance.
 */
public class OkHttpDataSource extends BaseDataSource implements HttpDataSource {

  static {
    MediaLibraryInfo.registerModule("media3.datasource.okhttp");
  }

  /** {@link DataSource.Factory} for {@link OkHttpDataSource} instances. */
  public static final class Factory implements HttpDataSource.Factory {

    private final RequestProperties defaultRequestProperties;
    private final Call.Factory callFactory;

    @Nullable private String userAgent;
    @Nullable private TransferListener transferListener;
    @Nullable private CacheControl cacheControl;
    @Nullable private Predicate<String> contentTypePredicate;

    /**
     * Creates an instance.
     *
     * @param callFactory A {@link Call.Factory} (typically an {@link OkHttpClient}) for use by the
     *     sources created by the factory.
     */
    public Factory(Call.Factory callFactory) {
      this.callFactory = callFactory;
      defaultRequestProperties = new RequestProperties();
    }

    @CanIgnoreReturnValue
    @UnstableApi
    @Override
    public final Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
      this.defaultRequestProperties.clearAndSet(defaultRequestProperties);
      return this;
    }

    /**
     * Sets the user agent that will be used.
     *
     * <p>The default is {@code null}, which causes the default user agent of the underlying {@link
     * OkHttpClient} to be used.
     *
     * @param userAgent The user agent that will be used, or {@code null} to use the default user
     *     agent of the underlying {@link OkHttpClient}.
     * @return This factory.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Factory setUserAgent(@Nullable String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /**
     * Sets the {@link CacheControl} that will be used.
     *
     * <p>The default is {@code null}.
     *
     * @param cacheControl The cache control that will be used.
     * @return This factory.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Factory setCacheControl(@Nullable CacheControl cacheControl) {
      this.cacheControl = cacheControl;
      return this;
    }

    /**
     * Sets a content type {@link Predicate}. If a content type is rejected by the predicate then a
     * {@link HttpDataSource.InvalidContentTypeException} is thrown from {@link
     * OkHttpDataSource#open(DataSpec)}.
     *
     * <p>The default is {@code null}.
     *
     * @param contentTypePredicate The content type {@link Predicate}, or {@code null} to clear a
     *     predicate that was previously set.
     * @return This factory.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Factory setContentTypePredicate(@Nullable Predicate<String> contentTypePredicate) {
      this.contentTypePredicate = contentTypePredicate;
      return this;
    }

    /**
     * Sets the {@link TransferListener} that will be used.
     *
     * <p>The default is {@code null}.
     *
     * <p>See {@link DataSource#addTransferListener(TransferListener)}.
     *
     * @param transferListener The listener that will be used.
     * @return This factory.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Factory setTransferListener(@Nullable TransferListener transferListener) {
      this.transferListener = transferListener;
      return this;
    }

    @UnstableApi
    @Override
    public OkHttpDataSource createDataSource() {
      OkHttpDataSource dataSource =
          new OkHttpDataSource(
              callFactory, userAgent, cacheControl, defaultRequestProperties, contentTypePredicate);
      if (transferListener != null) {
        dataSource.addTransferListener(transferListener);
      }
      return dataSource;
    }
  }

  private final Call.Factory callFactory;
  private final RequestProperties requestProperties;

  @Nullable private final String userAgent;
  @Nullable private final CacheControl cacheControl;
  @Nullable private final RequestProperties defaultRequestProperties;

  @Nullable private Predicate<String> contentTypePredicate;
  @Nullable private DataSpec dataSpec;
  @Nullable private Response response;
  @Nullable private InputStream responseByteStream;
  private boolean opened;
  private long bytesToRead;
  private long bytesRead;

  /**
   * @deprecated Use {@link OkHttpDataSource.Factory} instead.
   */
  @SuppressWarnings("deprecation")
  @UnstableApi
  @Deprecated
  public OkHttpDataSource(Call.Factory callFactory) {
    this(callFactory, /* userAgent= */ null);
  }

  /**
   * @deprecated Use {@link OkHttpDataSource.Factory} instead.
   */
  @SuppressWarnings("deprecation")
  @UnstableApi
  @Deprecated
  public OkHttpDataSource(Call.Factory callFactory, @Nullable String userAgent) {
    this(callFactory, userAgent, /* cacheControl= */ null, /* defaultRequestProperties= */ null);
  }

  /**
   * @deprecated Use {@link OkHttpDataSource.Factory} instead.
   */
  @UnstableApi
  @Deprecated
  public OkHttpDataSource(
      Call.Factory callFactory,
      @Nullable String userAgent,
      @Nullable CacheControl cacheControl,
      @Nullable RequestProperties defaultRequestProperties) {
    this(
        callFactory,
        userAgent,
        cacheControl,
        defaultRequestProperties,
        /* contentTypePredicate= */ null);
  }

  private OkHttpDataSource(
      Call.Factory callFactory,
      @Nullable String userAgent,
      @Nullable CacheControl cacheControl,
      @Nullable RequestProperties defaultRequestProperties,
      @Nullable Predicate<String> contentTypePredicate) {
    super(/* isNetwork= */ true);
    this.callFactory = Assertions.checkNotNull(callFactory);
    this.userAgent = userAgent;
    this.cacheControl = cacheControl;
    this.defaultRequestProperties = defaultRequestProperties;
    this.contentTypePredicate = contentTypePredicate;
    this.requestProperties = new RequestProperties();
  }

  /**
   * @deprecated Use {@link OkHttpDataSource.Factory#setContentTypePredicate(Predicate)} instead.
   */
  @UnstableApi
  @Deprecated
  public void setContentTypePredicate(@Nullable Predicate<String> contentTypePredicate) {
    this.contentTypePredicate = contentTypePredicate;
  }

  @UnstableApi
  @Override
  @Nullable
  public Uri getUri() {
    return response == null ? null : Uri.parse(response.request().url().toString());
  }

  @UnstableApi
  @Override
  public int getResponseCode() {
    return response == null ? -1 : response.code();
  }

  @UnstableApi
  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return response == null ? Collections.emptyMap() : response.headers().toMultimap();
  }

  @UnstableApi
  @Override
  public void setRequestProperty(String name, String value) {
    Assertions.checkNotNull(name);
    Assertions.checkNotNull(value);
    requestProperties.set(name, value);
  }

  @UnstableApi
  @Override
  public void clearRequestProperty(String name) {
    Assertions.checkNotNull(name);
    requestProperties.remove(name);
  }

  @UnstableApi
  @Override
  public void clearAllRequestProperties() {
    requestProperties.clear();
  }

  @UnstableApi
  @Override
  public long open(DataSpec dataSpec) throws HttpDataSourceException {
    this.dataSpec = dataSpec;
    bytesRead = 0;
    bytesToRead = 0;
    transferInitializing(dataSpec);

    Request request = makeRequest(dataSpec);
    Response response;
    ResponseBody responseBody;
    Call call = callFactory.newCall(request);
    try {
      this.response = executeCall(call);
      response = this.response;
      responseBody = Assertions.checkNotNull(response.body());
      responseByteStream = responseBody.byteStream();
    } catch (IOException e) {
      throw HttpDataSourceException.createForIOException(
          e, dataSpec, HttpDataSourceException.TYPE_OPEN);
    }

    int responseCode = response.code();

    // Check for a valid response code.
    if (!response.isSuccessful()) {
      if (responseCode == 416) {
        long documentSize =
            HttpUtil.getDocumentSize(response.headers().get(HttpHeaders.CONTENT_RANGE));
        if (dataSpec.position == documentSize) {
          opened = true;
          transferStarted(dataSpec);
          return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : 0;
        }
      }

      byte[] errorResponseBody;
      try {
        errorResponseBody = Util.toByteArray(Assertions.checkNotNull(responseByteStream));
      } catch (IOException e) {
        errorResponseBody = Util.EMPTY_BYTE_ARRAY;
      }
      Map<String, List<String>> headers = response.headers().toMultimap();
      closeConnectionQuietly();
      @Nullable
      IOException cause =
          responseCode == 416
              ? new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
              : null;
      throw new InvalidResponseCodeException(
          responseCode, response.message(), cause, headers, dataSpec, errorResponseBody);
    }

    // Check for a valid content type.
    @Nullable MediaType mediaType = responseBody.contentType();
    String contentType = mediaType != null ? mediaType.toString() : "";
    if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
      closeConnectionQuietly();
      throw new InvalidContentTypeException(contentType, dataSpec);
    }

    // If we requested a range starting from a non-zero position and received a 200 rather than a
    // 206, then the server does not support partial requests. We'll need to manually skip to the
    // requested position.
    long bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;

    // Determine the length of the data to be read, after skipping.
    if (dataSpec.length != C.LENGTH_UNSET) {
      bytesToRead = dataSpec.length;
    } else {
      long contentLength = responseBody.contentLength();
      bytesToRead = contentLength != -1 ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
    }

    opened = true;
    transferStarted(dataSpec);

    try {
      skipFully(bytesToSkip, dataSpec);
    } catch (HttpDataSourceException e) {
      closeConnectionQuietly();
      throw e;
    }

    return bytesToRead;
  }

  @UnstableApi
  @Override
  public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
    try {
      return readInternal(buffer, offset, length);
    } catch (IOException e) {
      throw HttpDataSourceException.createForIOException(
          e, castNonNull(dataSpec), HttpDataSourceException.TYPE_READ);
    }
  }

  @UnstableApi
  @Override
  public void close() {
    if (opened) {
      opened = false;
      transferEnded();
      closeConnectionQuietly();
    }
  }

  /** Establishes a connection. */
  private Request makeRequest(DataSpec dataSpec) throws HttpDataSourceException {
    long position = dataSpec.position;
    long length = dataSpec.length;

    @Nullable HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
    if (url == null) {
      throw new HttpDataSourceException(
          "Malformed URL",
          dataSpec,
          PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
          HttpDataSourceException.TYPE_OPEN);
    }

    Request.Builder builder = new Request.Builder().url(url);
    if (cacheControl != null) {
      builder.cacheControl(cacheControl);
    }

    Map<String, String> headers = new HashMap<>();
    if (defaultRequestProperties != null) {
      headers.putAll(defaultRequestProperties.getSnapshot());
    }

    headers.putAll(requestProperties.getSnapshot());
    headers.putAll(dataSpec.httpRequestHeaders);

    for (Map.Entry<String, String> header : headers.entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }

    @Nullable String rangeHeader = buildRangeRequestHeader(position, length);
    if (rangeHeader != null) {
      builder.addHeader(HttpHeaders.RANGE, rangeHeader);
    }
    if (userAgent != null) {
      builder.addHeader(HttpHeaders.USER_AGENT, userAgent);
    }
    if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
      builder.addHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
    }

    @Nullable RequestBody requestBody = null;
    if (dataSpec.httpBody != null) {
      requestBody = RequestBody.create(dataSpec.httpBody);
    } else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
      // OkHttp requires a non-null body for POST requests.
      requestBody = RequestBody.create(Util.EMPTY_BYTE_ARRAY);
    }
    builder.method(dataSpec.getHttpMethodString(), requestBody);
    return builder.build();
  }

  /**
   * This method is an interrupt safe replacement of OkHttp Call.execute() which can get in bad
   * states if interrupted while writing to the shared connection socket.
   */
  private Response executeCall(Call call) throws IOException {
    SettableFuture<Response> future = SettableFuture.create();
    call.enqueue(
        new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            future.setException(e);
          }

          @Override
          public void onResponse(Call call, Response response) {
            future.set(response);
          }
        });

    try {
      return future.get();
    } catch (InterruptedException e) {
      call.cancel();
      throw new InterruptedIOException();
    } catch (ExecutionException ee) {
      throw new IOException(ee);
    }
  }

  /**
   * Attempts to skip the specified number of bytes in full.
   *
   * @param bytesToSkip The number of bytes to skip.
   * @param dataSpec The {@link DataSpec}.
   * @throws HttpDataSourceException If the thread is interrupted during the operation, or an error
   *     occurs while reading from the source, or if the data ended before skipping the specified
   *     number of bytes.
   */
  private void skipFully(long bytesToSkip, DataSpec dataSpec) throws HttpDataSourceException {
    if (bytesToSkip == 0) {
      return;
    }
    byte[] skipBuffer = new byte[4096];
    try {
      while (bytesToSkip > 0) {
        int readLength = (int) min(bytesToSkip, skipBuffer.length);
        int read = castNonNull(responseByteStream).read(skipBuffer, 0, readLength);
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedIOException();
        }
        if (read == -1) {
          throw new HttpDataSourceException(
              dataSpec,
              PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
              HttpDataSourceException.TYPE_OPEN);
        }
        bytesToSkip -= read;
        bytesTransferred(read);
      }
      return;
    } catch (IOException e) {
      if (e instanceof HttpDataSourceException) {
        throw (HttpDataSourceException) e;
      } else {
        throw new HttpDataSourceException(
            dataSpec,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            HttpDataSourceException.TYPE_OPEN);
      }
    }
  }

  /**
   * Reads up to {@code length} bytes of data and stores them into {@code buffer}, starting at index
   * {@code offset}.
   *
   * <p>This method blocks until at least one byte of data can be read, the end of the opened range
   * is detected, or an exception is thrown.
   *
   * @param buffer The buffer into which the read data should be stored.
   * @param offset The start offset into {@code buffer} at which data should be written.
   * @param readLength The maximum number of bytes to read.
   * @return The number of bytes read, or {@link C#RESULT_END_OF_INPUT} if the end of the opened
   *     range is reached.
   * @throws IOException If an error occurs reading from the source.
   */
  private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
    if (readLength == 0) {
      return 0;
    }
    if (bytesToRead != C.LENGTH_UNSET) {
      long bytesRemaining = bytesToRead - bytesRead;
      if (bytesRemaining == 0) {
        return C.RESULT_END_OF_INPUT;
      }
      readLength = (int) min(readLength, bytesRemaining);
    }

    int read = castNonNull(responseByteStream).read(buffer, offset, readLength);
    if (read == -1) {
      return C.RESULT_END_OF_INPUT;
    }

    bytesRead += read;
    bytesTransferred(read);
    return read;
  }

  /** Closes the current connection quietly, if there is one. */
  private void closeConnectionQuietly() {
    if (response != null) {
      Assertions.checkNotNull(response.body()).close();
      response = null;
    }
    responseByteStream = null;
  }
}
