/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.upstream.contentsteering;

import static androidx.media3.common.util.Util.createHandlerForCurrentLooper;
import static androidx.media3.exoplayer.upstream.contentsteering.SteeringManifestTracker.FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.test.utils.FakeClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test for {@link SteeringManifestTracker}. */
@RunWith(AndroidJUnit4.class)
public final class SteeringManifestTrackerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final String INITIAL_STEERING_MANIFEST_PATH = "/steering/initial?video=001";
  private static final String STEERING_MANIFEST_JSON_STRING_1 =
      "{\"VERSION\": 1, \"TTL\": 300, \"RELOAD-URI\": \"reload?video=001\", \"PATHWAY-PRIORITY\":"
          + " [\"CDN-A\", \"CDN-B\"]}";
  private static final String STEERING_MANIFEST_JSON_STRING_2 =
      "{\"VERSION\": 1, \"TTL\": 300, \"RELOAD-URI\": \"/steering2/reload?video=001\","
          + " \"PATHWAY-PRIORITY\": [\"CDN-B\", \"CDN-A\"]}";
  private static final String STEERING_MANIFEST_JSON_STRING_3 =
      "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-B\"]}";

  private MockWebServer mockWebServer;
  @Mock private SteeringManifestTracker.Callback mockCallback;
  private int enqueueCounter;
  private int assertedRequestCounter;
  private FakeClock clock;
  private AtomicInteger loadStartedCount;
  private AtomicInteger loadCompletedOrErrorCount;
  private List<Integer> completedDataTypes;
  private List<Integer> errorDataTypes;
  private List<Boolean> errorWasCanceled;
  private MediaSourceEventListener.EventDispatcher eventDispatcher;

  @Before
  public void setUp() {
    mockWebServer = new MockWebServer();
    AtomicInteger requestCounter = new AtomicInteger();
    when(mockCallback.getSteeringQueryParameters())
        .then(
            invocation ->
                ImmutableMap.of("_query_param", String.valueOf(requestCounter.incrementAndGet())));
    enqueueCounter = 0;
    assertedRequestCounter = 0;
    clock = new FakeClock(/* isAutoAdvancing= */ true);
    loadStartedCount = new AtomicInteger();
    loadCompletedOrErrorCount = new AtomicInteger();
    completedDataTypes = new ArrayList<>();
    errorDataTypes = new ArrayList<>();
    errorWasCanceled = new ArrayList<>();
    eventDispatcher = new MediaSourceEventListener.EventDispatcher();
    eventDispatcher.addEventListener(
        createHandlerForCurrentLooper(),
        new MediaSourceEventListener() {
          @Override
          public void onLoadStarted(
              int windowIndex,
              @Nullable MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              int retryCount) {
            loadStartedCount.incrementAndGet();
          }

          @Override
          public void onLoadCompleted(
              int windowIndex,
              @Nullable MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData) {
            completedDataTypes.add(mediaLoadData.dataType);
            loadCompletedOrErrorCount.incrementAndGet();
            // We advance the clock for FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS (5 min) to avoid
            // that the test times out while waiting for the next reload.
            clock.advanceTime(FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS);
          }

          @Override
          public void onLoadError(
              int windowIndex,
              @Nullable MediaPeriodId mediaPeriodId,
              LoadEventInfo loadEventInfo,
              MediaLoadData mediaLoadData,
              IOException error,
              boolean wasCanceled) {
            errorDataTypes.add(mediaLoadData.dataType);
            errorWasCanceled.add(wasCanceled);
            loadCompletedOrErrorCount.incrementAndGet();
            // We advance the clock for FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS (5 min) to avoid
            // that the test times out while waiting for the next reload.
            clock.advanceTime(FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS);
          }
        });
  }

  @After
  public void tearDown() throws Exception {
    assertThat(assertedRequestCounter).isEqualTo(enqueueCounter);
    mockWebServer.shutdown();
  }

  @Test
  public void start_loadSteeringManifestSuccessfullyAndScheduleReloads() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/reload?video=001&_query_param=2",
              "/steering2/reload?video=001&_query_param=3",
              "/steering2/reload?video=001&_query_param=4"
            },
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_2),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_3),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_3));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadCompletedOrErrorCount.get() >= 4,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(4);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(1).pathwayPriority)
        .containsExactly("CDN-B", "CDN-A")
        .inOrder();
    assertThat(steeringManifests.get(2).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(3).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    verify(mockCallback, never())
        .onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ anyBoolean());
    assertThat(completedDataTypes)
        .containsExactly(
            C.DATA_TYPE_STEERING_MANIFEST,
            C.DATA_TYPE_STEERING_MANIFEST,
            C.DATA_TYPE_STEERING_MANIFEST,
            C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(timeoutExceptionRef.get()).isNull();
  }

  @Test
  public void start_afterResponse410_noMoreRetryScheduled() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/reload?video=001&_query_param=2",
              "/steering2/reload?video=001&_query_param=3"
            },
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_2),
            new MockResponse().setResponseCode(410));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadStartedCount.get() >= 4,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(2);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(1).pathwayPriority)
        .containsExactly("CDN-B", "CDN-A")
        .inOrder();
    verify(mockCallback).onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ eq(true));
    assertThat(completedDataTypes)
        .containsExactly(C.DATA_TYPE_STEERING_MANIFEST, C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorWasCanceled).containsExactly(true);
    assertThat(timeoutExceptionRef.get()).isNotNull();
  }

  @Test
  public void start_afterResponse429WithValidRetryAfterHeader_retryScheduled() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/reload?video=001&_query_param=2",
              "/steering/reload?video=001&_query_param=3",
            },
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1),
            new MockResponse().setResponseCode(429).setHeader("Retry-After", 60),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_2));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadCompletedOrErrorCount.get() >= 3,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(2);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(1).pathwayPriority)
        .containsExactly("CDN-B", "CDN-A")
        .inOrder();
    verify(mockCallback).onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ eq(false));
    assertThat(completedDataTypes)
        .containsExactly(C.DATA_TYPE_STEERING_MANIFEST, C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorWasCanceled).containsExactly(false);
    assertThat(timeoutExceptionRef.get()).isNull();
  }

  @Test
  public void start_afterResponse429WithInvalidRetryAfterHeader_retryScheduled() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/reload?video=001&_query_param=2",
              "/steering/reload?video=001&_query_param=3",
            },
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1),
            new MockResponse().setResponseCode(429).setHeader("Retry-After", "{}"),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_2));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadCompletedOrErrorCount.get() >= 3,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(2);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(1).pathwayPriority)
        .containsExactly("CDN-B", "CDN-A")
        .inOrder();
    verify(mockCallback).onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ eq(false));
    assertThat(completedDataTypes)
        .containsExactly(C.DATA_TYPE_STEERING_MANIFEST, C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorWasCanceled).containsExactly(false);
    assertThat(timeoutExceptionRef.get()).isNull();
  }

  @Test
  public void start_afterOtherErrorResponses_retryScheduled() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/reload?video=001&_query_param=2",
              "/steering/reload?video=001&_query_param=3"
            },
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1),
            new MockResponse().setResponseCode(404),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_2));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadCompletedOrErrorCount.get() >= 3,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(2);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    assertThat(steeringManifests.get(1).pathwayPriority)
        .containsExactly("CDN-B", "CDN-A")
        .inOrder();
    verify(mockCallback).onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ eq(false));
    assertThat(completedDataTypes)
        .containsExactly(C.DATA_TYPE_STEERING_MANIFEST, C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorWasCanceled).containsExactly(false);
    assertThat(timeoutExceptionRef.get()).isNull();
  }

  @Test
  public void start_failWhenLoadingFirstSteeringManifest_retryScheduled() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/steering/initial?video=001&_query_param=1",
              "/steering/initial?video=001&_query_param=2"
            },
            new MockResponse().setResponseCode(404),
            getMockResponse(STEERING_MANIFEST_JSON_STRING_1));
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    runSteeringManifestTracker(
        Uri.parse(mockWebServer.url(INITIAL_STEERING_MANIFEST_PATH).toString()),
        () -> loadCompletedOrErrorCount.get() >= 2,
        timeoutExceptionRef);

    assertRequestUrlsCalled(httpUrls);
    ArgumentCaptor<SteeringManifest> manifestCaptor =
        ArgumentCaptor.forClass(SteeringManifest.class);
    verify(mockCallback, atLeastOnce()).onSteeringManifestUpdated(manifestCaptor.capture());
    List<SteeringManifest> steeringManifests = manifestCaptor.getAllValues();
    assertThat(steeringManifests).hasSize(1);
    assertThat(steeringManifests.get(0).pathwayPriority)
        .containsExactly("CDN-A", "CDN-B")
        .inOrder();
    verify(mockCallback).onSteeringManifestLoadError(/* error= */ any(), /* canceled= */ eq(false));
    assertThat(completedDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorDataTypes).containsExactly(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(errorWasCanceled).containsExactly(false);
    assertThat(timeoutExceptionRef.get()).isNull();
  }

  private List<HttpUrl> enqueueWebServerResponses(String[] paths, MockResponse... mockResponses) {
    assertThat(paths).hasLength(mockResponses.length);
    for (MockResponse mockResponse : mockResponses) {
      enqueueCounter++;
      mockWebServer.enqueue(mockResponse);
    }
    List<HttpUrl> urls = new ArrayList<>();
    for (String path : paths) {
      urls.add(mockWebServer.url(path));
    }
    return urls;
  }

  private void assertRequestUrlsCalled(List<HttpUrl> httpUrls) throws InterruptedException {
    for (HttpUrl url : httpUrls) {
      assertedRequestCounter++;
      assertThat(url.toString()).endsWith(mockWebServer.takeRequest().getPath());
    }
  }

  private void runSteeringManifestTracker(
      Uri initialSteeringManifestUrl,
      Supplier<Boolean> terminateCondition,
      AtomicReference<TimeoutException> timeoutExceptionRef) {
    SteeringManifestTracker steeringManifestTracker =
        new SteeringManifestTracker(
            new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()),
            /* downloadExecutorSupplier= */ null,
            clock);
    steeringManifestTracker.start(initialSteeringManifestUrl, mockCallback, eventDispatcher);
    try {
      runMainLooperUntil(
          /* maxTimeDiffMs= */ 3_000, // Account for scheduled steering manifest refresh delays
          terminateCondition);
    } catch (TimeoutException e) {
      timeoutExceptionRef.set(e);
    }
    shadowOf(Looper.getMainLooper()).idle();
    steeringManifestTracker.stop();
  }

  private static MockResponse getMockResponse(String jsonString) {
    return new MockResponse()
        .setResponseCode(200)
        .setBody(new Buffer().write(getBytes(jsonString)));
  }

  private static byte[] getBytes(String jsonString) {
    return jsonString.getBytes(Charset.defaultCharset());
  }
}
