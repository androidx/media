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

import static androidx.media3.common.util.Util.EMPTY_BYTE_ARRAY;
import static androidx.media3.datasource.DataSpec.HTTP_METHOD_GET;
import static androidx.media3.datasource.DataSpec.HTTP_METHOD_HEAD;
import static androidx.media3.datasource.DataSpec.HTTP_METHOD_POST;
import static androidx.media3.test.utils.WebServerDispatcher.Resource.GZIP_SUPPORT_DISABLED;
import static androidx.media3.test.utils.WebServerDispatcher.Resource.GZIP_SUPPORT_FORCED;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.Pair;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec.HttpMethod;
import androidx.media3.datasource.HttpUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.net.HttpHeaders;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Headers;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link Dispatcher} for {@link okhttp3.mockwebserver.MockWebServer} that allows per-path
 * customisation of the static data served.
 */
@UnstableApi
public final class WebServerDispatcher extends Dispatcher {

  /** The body associated with a response for an unrecognized path. */
  public static final String NOT_FOUND_BODY = "Resource not found!";

  /** A resource served by {@link WebServerDispatcher}. */
  public static final class Resource {

    /**
     * The level of gzip support offered by the server for a resource.
     *
     * <p>One of:
     *
     * <ul>
     *   <li>{@link #GZIP_SUPPORT_DISABLED}
     *   <li>{@link #GZIP_SUPPORT_ENABLED}
     *   <li>{@link #GZIP_SUPPORT_FORCED}
     * </ul>
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef({GZIP_SUPPORT_DISABLED, GZIP_SUPPORT_ENABLED, GZIP_SUPPORT_FORCED})
    private @interface GzipSupport {}

    /** The server doesn't support gzip. */
    public static final int GZIP_SUPPORT_DISABLED = 1;

    /**
     * The server supports gzip. Responses are only compressed if the request signals "gzip" is an
     * acceptable content-coding using an {@code Accept-Encoding} header.
     */
    public static final int GZIP_SUPPORT_ENABLED = 2;

    /**
     * The server supports gzip. Responses are compressed if the request contains no {@code
     * Accept-Encoding} header or one that accepts {@code "gzip"}.
     *
     * <p>RFC 2616 14.3 recommends a server use {@code "identity"} content-coding if no {@code
     * Accept-Encoding} is present, but some servers will still compress responses in this case.
     * This option mimics that behaviour.
     */
    public static final int GZIP_SUPPORT_FORCED = 3;

    /** Builder for {@link Resource}. */
    public static final class Builder {
      private @MonotonicNonNull String path;
      private @HttpMethod int httpMethod;
      private ImmutableListMultimap<String, String> requestHeaders;
      private byte[] requestBody;
      private byte @MonotonicNonNull [] data;
      private boolean supportsRangeRequests;
      private boolean includesContentLengthInRangeResponses;
      private boolean resolvesToUnknownLength;
      private @GzipSupport int gzipSupport;
      private ImmutableListMultimap<String, String> extraResponseHeaders;

      /** Constructs an instance. */
      public Builder() {
        this.httpMethod = HTTP_METHOD_GET;
        this.requestHeaders = ImmutableListMultimap.of();
        this.requestBody = EMPTY_BYTE_ARRAY;
        this.includesContentLengthInRangeResponses = true;
        this.gzipSupport = GZIP_SUPPORT_DISABLED;
        this.extraResponseHeaders = ImmutableListMultimap.of();
      }

      private Builder(Resource resource) {
        this.path = resource.getPath();
        this.httpMethod = resource.getHttpMethod();
        this.requestHeaders = resource.getRequestHeaders();
        this.requestBody = resource.getRequestBody();
        this.data = resource.getData();
        this.supportsRangeRequests = resource.supportsRangeRequests();
        this.includesContentLengthInRangeResponses =
            resource.includesContentLengthInRangeResponses();
        this.resolvesToUnknownLength = resource.resolvesToUnknownLength();
        this.gzipSupport = resource.getGzipSupport();
        this.extraResponseHeaders = resource.getExtraResponseHeaders();
      }

      /**
       * Sets the path this data should be served at. This is required.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setPath(String path) {
        this.path = path.startsWith("/") ? path : "/" + path;
        return this;
      }

      /**
       * Sets the HTTP method that is expected to be used to access the resource.
       *
       * <p>Defaults to {@link androidx.media3.datasource.DataSpec#HTTP_METHOD_GET}.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setHttpMethod(@HttpMethod int httpMethod) {
        this.httpMethod = httpMethod;
        return this;
      }

      /**
       * Sets the request headers that are expected to be included in a request for the resource.
       *
       * <p>This is non-exhaustive, additional headers are permitted, but if any headers in this map
       * are missing from a request (or have mismatched values), {@link WebServerDispatcher} will
       * return a {@code 400 Bad Request} error response.
       *
       * <p>Defaults to an empty map.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setRequestHeaders(ImmutableListMultimap<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
        return this;
      }

      /**
       * The request body that is expected to be included in a request for the resource.
       *
       * <p>If a request is made with a mismatched body, {@link WebServerDispatcher} will return a
       * {@code 400 Bad Request} error response.
       *
       * <p>Defaults to an empty array.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setRequestBody(byte[] requestBody) {
        this.requestBody = requestBody;
        return this;
      }

      /**
       * Sets the data served by this resource. This is required.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setData(byte[] data) {
        this.data = data;
        return this;
      }

      /**
       * Sets if RFC 7233 range requests should be supported for this resource. Defaults to false.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder supportsRangeRequests(boolean supportsRangeRequests) {
        this.supportsRangeRequests = supportsRangeRequests;
        return this;
      }

      /**
       * Sets if RFC 7233 HTTP 206 range responses should include a {@code Content-Length} header.
       *
       * <p>Setting this to false allows simulating servers or proxies that only include the {@code
       * Content-Range} header and not {@code Content-Length}, as described in the comments in
       * {@link HttpUtil#getContentLength(String, String)}.
       *
       * <p>Defaults to true.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder includesContentLengthInRangeResponses(
          boolean includesContentLengthInRangeResponses) {
        this.includesContentLengthInRangeResponses = includesContentLengthInRangeResponses;
        return this;
      }

      /**
       * Sets if the server shouldn't include the resource length in header responses.
       *
       * <p>If true, responses to unbound requests won't include a Content-Length header and
       * Content-Range headers won't include the total resource length.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder resolvesToUnknownLength(boolean resolvesToUnknownLength) {
        this.resolvesToUnknownLength = resolvesToUnknownLength;
        return this;
      }

      /**
       * Sets the level of gzip support for this resource. Defaults to {@link
       * #GZIP_SUPPORT_DISABLED}.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setGzipSupport(@GzipSupport int gzipSupport) {
        this.gzipSupport = gzipSupport;
        return this;
      }

      /**
       * Sets the extra response headers that should be attached.
       *
       * @return this builder, for convenience.
       */
      @CanIgnoreReturnValue
      public Builder setExtraResponseHeaders(Multimap<String, String> extraResponseHeaders) {
        this.extraResponseHeaders = ImmutableListMultimap.copyOf(extraResponseHeaders);
        return this;
      }

      /** Builds the {@link Resource}. */
      public Resource build() {
        if (requestBody.length > 0) {
          checkState(httpMethod != HTTP_METHOD_GET, "requestBody must be empty for a GET request.");
        }
        if (gzipSupport != GZIP_SUPPORT_DISABLED) {
          checkState(!supportsRangeRequests, "Can't enable compression & range requests.");
          checkState(!resolvesToUnknownLength, "Can't enable compression if length isn't known.");
        }
        checkState(
            supportsRangeRequests || includesContentLengthInRangeResponses,
            "Can't exclude Content-Length from range responses if range requests aren't"
                + " supported.");
        return new Resource(
            checkNotNull(path),
            httpMethod,
            requestHeaders,
            requestBody,
            checkNotNull(data),
            supportsRangeRequests,
            includesContentLengthInRangeResponses,
            resolvesToUnknownLength,
            gzipSupport,
            extraResponseHeaders);
      }
    }

    private final String path;
    private final @HttpMethod int httpMethod;
    private final ImmutableListMultimap<String, String> requestHeaders;
    private final byte[] requestBody;
    private final byte[] data;
    private final boolean supportsRangeRequests;
    private final boolean includesContentLengthInRangeResponses;
    private final boolean resolvesToUnknownLength;
    private final @GzipSupport int gzipSupport;
    ImmutableListMultimap<String, String> extraResponseHeaders;

    private Resource(
        String path,
        @HttpMethod int httpMethod,
        ImmutableListMultimap<String, String> requestHeaders,
        byte[] requestBody,
        byte[] data,
        boolean supportsRangeRequests,
        boolean includesContentLengthInRangeResponses,
        boolean resolvesToUnknownLength,
        @GzipSupport int gzipSupport,
        ImmutableListMultimap<String, String> extraResponseHeaders) {
      this.path = path;
      this.httpMethod = httpMethod;
      this.requestHeaders = requestHeaders;
      this.requestBody = requestBody;
      this.data = data;
      this.supportsRangeRequests = supportsRangeRequests;
      this.includesContentLengthInRangeResponses = includesContentLengthInRangeResponses;
      this.resolvesToUnknownLength = resolvesToUnknownLength;
      this.gzipSupport = gzipSupport;
      this.extraResponseHeaders = extraResponseHeaders;
    }

    /** Returns the path this resource is available at. */
    public String getPath() {
      return path;
    }

    /** The HTTP method that should be used to request the resource. */
    public @HttpMethod int getHttpMethod() {
      return httpMethod;
    }

    /** The headers that should be included in a request for the resource. */
    public ImmutableListMultimap<String, String> getRequestHeaders() {
      return requestHeaders;
    }

    /** The body that should be included in a request for the resource. */
    public byte[] getRequestBody() {
      return requestBody.clone();
    }

    /** Returns the data served by this resource. */
    public byte[] getData() {
      return data.clone();
    }

    /** Returns true if RFC 7233 range requests should be supported for this resource. */
    public boolean supportsRangeRequests() {
      return supportsRangeRequests;
    }

    /**
     * Returns true if RFC 7233 HTTP 206 responses should include a {@code Content-Length} header.
     */
    public boolean includesContentLengthInRangeResponses() {
      return includesContentLengthInRangeResponses;
    }

    /** Returns true if the resource should resolve to an unknown length. */
    public boolean resolvesToUnknownLength() {
      return resolvesToUnknownLength;
    }

    /** Returns the level of gzip support the server should provide for this resource. */
    public @GzipSupport int getGzipSupport() {
      return gzipSupport;
    }

    /** Returns the extra response headers that should be attached. */
    public ImmutableListMultimap<String, String> getExtraResponseHeaders() {
      return extraResponseHeaders;
    }

    /** Returns a new {@link Builder} initialized with the values from this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }
  }

  private static final String TAG = "WebServerDispatcher";

  /** Matches an Accept-Encoding header value (format defined in RFC 2616 section 14.3). */
  private static final Pattern ACCEPT_ENCODING_PATTERN =
      Pattern.compile("\\W*(\\w+|\\*)(?:;q=(\\d+\\.?\\d*))?\\W*");

  private final ImmutableMap<String, Resource> resourcesByPath;

  /** Returns the path for a given {@link RecordedRequest}, stripping any query parameters. */
  public static String getRequestPath(RecordedRequest request) {
    return Util.splitAtFirst(Strings.nullToEmpty(request.getPath()), "\\?")[0];
  }

  /**
   * Constructs a dispatcher that handles requests based the provided {@link Resource} instances.
   */
  public static WebServerDispatcher forResources(Iterable<Resource> resources) {
    return new WebServerDispatcher(Maps.uniqueIndex(resources, Resource::getPath));
  }

  private WebServerDispatcher(ImmutableMap<String, Resource> resourcesByPath) {
    this.resourcesByPath = resourcesByPath;
  }

  @Override
  public MockResponse dispatch(RecordedRequest request) {
    String requestPath = getRequestPath(request);
    MockResponse response = new MockResponse();
    if (!resourcesByPath.containsKey(requestPath)) {
      return response.setBody(NOT_FOUND_BODY).setResponseCode(404);
    }
    Resource resource = checkNotNull(resourcesByPath.get(requestPath));
    String expectedMethod = httpMethodString(resource.getHttpMethod());
    if (!Objects.equals(request.getMethod(), expectedMethod)) {
      Log.e(
          TAG,
          "Rejecting request for "
              + requestPath
              + "\nWrong method. Expected: "
              + expectedMethod
              + ", Received: "
              + request.getMethod());
      return response
          .setResponseCode(405)
          .setHeader(HttpHeaders.ALLOW, expectedMethod)
          .setBody("Wrong method");
    }

    if (!expectedHeadersArePresent(request.getHeaders(), resource.getRequestHeaders())) {
      return response
          .setResponseCode(400)
          .setBody("Request headers don't contain expected headers.");
    }

    if (resource.getHttpMethod() != HTTP_METHOD_GET) {
      byte[] requestBody = request.getBody().readByteArray();
      if (!Arrays.equals(requestBody, resource.getRequestBody())) {
        String errorMessage =
            "Request body doesn't match.\nReceived: "
                + (requestBody.length > 0 ? "0x" + Util.toHexString(requestBody) : "<empty>")
                + "\nExpected: "
                + (resource.getRequestBody().length > 0
                    ? "0x" + Util.toHexString(resource.getRequestBody())
                    : "<empty>");
        Log.e(TAG, "Rejecting request for " + requestPath + "\n" + errorMessage);
        return response.setResponseCode(400).setBody(errorMessage);
      }
    }
    for (Map.Entry<String, String> extraHeader : resource.getExtraResponseHeaders().entries()) {
      response.addHeader(extraHeader.getKey(), extraHeader.getValue());
    }
    byte[] resourceData = resource.getData();
    if (resource.supportsRangeRequests()) {
      response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
    }
    @Nullable ImmutableMap<String, Float> acceptEncodingHeader = getAcceptEncodingHeader(request);
    @Nullable String preferredContentCoding;
    if (resource.getGzipSupport() == GZIP_SUPPORT_FORCED && acceptEncodingHeader == null) {
      preferredContentCoding = "gzip";
    } else {
      ImmutableList<String> supportedContentCodings =
          resource.getGzipSupport() == GZIP_SUPPORT_DISABLED
              ? ImmutableList.of("identity")
              : ImmutableList.of("gzip", "identity");
      preferredContentCoding =
          getPreferredContentCoding(acceptEncodingHeader, supportedContentCodings);
    }
    if (preferredContentCoding == null) {
      // None of the supported encodings are accepted by the client.
      return response.setResponseCode(406);
    }

    @Nullable String rangeHeader = request.getHeader(HttpHeaders.RANGE);
    if (!resource.supportsRangeRequests() || rangeHeader == null) {
      setResponseBody(
          response,
          preferredContentCoding,
          resourceData,
          /* chunked= */ resource.resolvesToUnknownLength());
      return response;
    }

    @Nullable
    Pair<@NullableType Integer, @NullableType Integer> range = getRangeHeader(rangeHeader);

    if (range == null || (range.first != null && range.first >= resourceData.length)) {
      return response
          .setResponseCode(416)
          .setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resourceData.length);
    }

    if (range.first == null || range.second == null) {
      int start;
      if (range.first == null) {
        // We're handling a suffix range
        if (resource.resolvesToUnknownLength()) {
          // Can't return the suffix of an unknown-length resource.
          return response
              .setResponseCode(416)
              .setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resourceData.length);
        }
        start = max(0, resourceData.length - checkNotNull(range.second));
      } else {
        // We're handling an unbounded range
        if (resource.resolvesToUnknownLength()) {
          // The Content-Range header requires defining the length of the returned data, which
          // we can't fulfil for an unbounded range request to a resource with an unknown length, so
          // we just return a 200 response instead.
          setResponseBody(response, preferredContentCoding, resourceData, /* chunked= */ true);
          return response;
        }
        start = checkNotNull(range.first);
      }
      // resource.resolvesToUnknownLength() == false
      response
          .setResponseCode(206)
          .setHeader(
              HttpHeaders.CONTENT_RANGE,
              "bytes " + start + "-" + (resourceData.length - 1) + "/" + resourceData.length);
      setResponseBody(
          response,
          Arrays.copyOfRange(resourceData, start, resourceData.length),
          /* chunked= */ !resource.includesContentLengthInRangeResponses);
      return response;
    }

    // range.first and range.second are both non-null, so the range is bounded.

    if (range.second < range.first) {
      return response
          .setResponseCode(416)
          .setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resourceData.length);
    }

    int end = min(range.second + 1, resourceData.length);
    response
        .setResponseCode(206)
        .setHeader(
            HttpHeaders.CONTENT_RANGE,
            "bytes "
                + range.first
                + "-"
                + (end - 1)
                + "/"
                + (resource.resolvesToUnknownLength() ? "*" : resourceData.length));
    setResponseBody(
        response,
        Arrays.copyOfRange(resourceData, range.first, end),
        /* chunked= */ !resource.includesContentLengthInRangeResponses());
    return response;
  }

  /**
   * Sets the response body, considering the preferred encoding value from an {@code
   * Accept-Encoding} request header.
   */
  private static void setResponseBody(
      MockResponse response, String preferredContentCoding, byte[] body, boolean chunked) {
    switch (preferredContentCoding) {
      case "gzip":
        setResponseBody(response, Util.gzip(body), chunked);
        response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
        break;
      case "identity":
        setResponseBody(response, body, chunked);
        response.setHeader(HttpHeaders.CONTENT_ENCODING, "identity");
        break;
      default:
        throw new IllegalStateException("Unexpected content coding: " + preferredContentCoding);
    }
  }

  /**
   * Populates a response with the specified body.
   *
   * @param response The response whose body should be populated.
   * @param body The body data.
   * @param chunked Whether to use chunked transfer encoding. Note that if set to {@code true}, the
   *     "Content-Length" header will not be set.
   */
  private static void setResponseBody(MockResponse response, byte[] body, boolean chunked) {
    if (chunked) {
      response.setChunkedBody(new Buffer().write(body), /* maxChunkSize= */ Integer.MAX_VALUE);
    } else {
      response.setBody(new Buffer().write(body));
    }
  }

  /**
   * Parses an RFC 2616 14.3 Accept-Encoding header into a map from content-coding to qvalue.
   *
   * <p>Returns null if the header is not present.
   *
   * <p>Missing qvalues are stored in the map as -1.
   */
  @Nullable
  private static ImmutableMap<String, Float> getAcceptEncodingHeader(RecordedRequest request) {
    @Nullable
    List<String> headers = request.getHeaders().toMultimap().get(HttpHeaders.ACCEPT_ENCODING);
    if (headers == null) {
      return null;
    }
    String header = Joiner.on(",").join(headers);
    String[] encodings = Util.split(header, ",");
    ImmutableMap.Builder<String, Float> parsedEncodings = ImmutableMap.builder();
    for (String encoding : encodings) {
      Matcher matcher = ACCEPT_ENCODING_PATTERN.matcher(encoding);
      if (!matcher.matches()) {
        continue;
      }
      String contentCoding = checkNotNull(matcher.group(1));
      @Nullable String qvalue = matcher.group(2);
      parsedEncodings.put(contentCoding, qvalue == null ? -1f : Float.parseFloat(qvalue));
    }
    return parsedEncodings.buildOrThrow();
  }

  /**
   * Returns the preferred content-coding based on the (optional) Accept-Encoding header, or null if
   * none of {@code supportedContentCodings} are accepted by the client.
   *
   * <p>The selection algorithm is described in RFC 2616 section 14.3.
   *
   * @param acceptEncodingHeader The Accept-Encoding header parsed into a map from content-coding to
   *     qvalue (absent qvalues are represented by -1), or null if the header isn't present.
   * @param supportedContentCodings A list of content-codings supported by the server in order of
   *     preference.
   */
  @Nullable
  private static String getPreferredContentCoding(
      @Nullable ImmutableMap<String, Float> acceptEncodingHeader,
      List<String> supportedContentCodings) {
    if (acceptEncodingHeader == null) {
      return "identity";
    }
    if (!acceptEncodingHeader.containsKey("identity") && !acceptEncodingHeader.containsKey("*")) {
      acceptEncodingHeader =
          ImmutableMap.<String, Float>builder()
              .putAll(acceptEncodingHeader)
              .put("identity", -1f)
              .buildOrThrow();
    }
    float asteriskQvalue = acceptEncodingHeader.getOrDefault("*", 0f);
    @Nullable String preferredContentCoding = null;
    float preferredQvalue = Integer.MIN_VALUE;
    for (String supportedContentCoding : supportedContentCodings) {
      float qvalue = acceptEncodingHeader.getOrDefault(supportedContentCoding, 0f);
      if (!acceptEncodingHeader.containsKey(supportedContentCoding)
          && asteriskQvalue != 0
          && asteriskQvalue > preferredQvalue) {
        preferredContentCoding = supportedContentCoding;
        preferredQvalue = asteriskQvalue;
      } else if (qvalue != 0 && qvalue > preferredQvalue) {
        preferredContentCoding = supportedContentCoding;
        preferredQvalue = qvalue;
      }
    }

    return preferredContentCoding;
  }

  /**
   * Parses an RFC 7233 Range header to its component parts. Returns null if the Range is invalid.
   */
  @Nullable
  private static Pair<@NullableType Integer, @NullableType Integer> getRangeHeader(
      String rangeHeader) {
    Pattern rangePattern = Pattern.compile("bytes=(\\d*)-(\\d*)");
    Matcher rangeMatcher = rangePattern.matcher(rangeHeader);
    if (!rangeMatcher.matches() || rangeHeader.contains(",")) {
      // This implementation only supports byte ranges and doesn't support multiple ranges.
      return null;
    }
    String first = checkNotNull(rangeMatcher.group(1));
    String second = checkNotNull(rangeMatcher.group(2));

    Pair<@NullableType Integer, @NullableType Integer> result =
        Pair.create(
            first.isEmpty() ? null : Integer.parseInt(first),
            second.isEmpty() ? null : Integer.parseInt(second));
    if (result.first != null && result.second != null && result.second < result.first) {
      return null;
    }
    return result;
  }

  private static String httpMethodString(@HttpMethod int httpMethod) {
    switch (httpMethod) {
      case HTTP_METHOD_GET:
        return "GET";
      case HTTP_METHOD_HEAD:
        return "HEAD";
      case HTTP_METHOD_POST:
        return "POST";
      default:
        throw new IllegalArgumentException("Unrecognized @HttpMethod value: " + httpMethod);
    }
  }

  private static boolean expectedHeadersArePresent(
      Headers actualHeaders, Multimap<String, String> expectedHeaders) {
    for (String expectedHeaderName : expectedHeaders.keySet()) {
      ImmutableSet<List<String>> actualValues =
          ImmutableSet.of(actualHeaders.values(expectedHeaderName));
      ImmutableSet<Collection<String>> expectedValues =
          ImmutableSet.of(expectedHeaders.get(expectedHeaderName));
      if (!actualValues.equals(expectedValues)) {
        Log.e(
            TAG,
            "Mismatched headers.\nExpected: " + expectedValues + "\nReceived: " + actualValues);
        return false;
      }
    }
    return true;
  }
}
