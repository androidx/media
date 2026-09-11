/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.dash;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.BaseUrl;
import androidx.media3.exoplayer.dash.manifest.ContentSteering;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.Location;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SingleSegmentBase;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.shadows.ShadowSystemClock;

/** Test for {@link DashContentSteeringTracker}. */
@RunWith(AndroidJUnit4.class)
public final class DashContentSteeringTrackerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final String TEST_INITIAL_STEERING_URI_STRING = "https://steering";

  @Mock private DashContentSteeringTracker.Callback mockCallback;
  private AtomicInteger serviceLocationPriorityUpdateCount;
  private DashManifest initialDashManifest;
  private DashContentSteeringTracker contentSteeringTracker;

  @Before
  public void setUp() {
    serviceLocationPriorityUpdateCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              serviceLocationPriorityUpdateCount.incrementAndGet();
              return null;
            })
        .when(mockCallback)
        .onServiceLocationPriorityUpdated(any());
    ImmutableList<Location> locations =
        ImmutableList.of(
            new Location(Uri.parse("https://loc-a"), "CDN-A"),
            new Location(Uri.parse("https://loc-b"), "CDN-B"),
            new Location(Uri.parse("https://loc-c"), "CDN-C"));
    initialDashManifest = createDashManifest(locations, ImmutableList.of());
    String steeringManifest =
        "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-B\", \"CDN-A\", \"CDN-C\"]}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());
    contentSteeringTracker =
        new DashContentSteeringTracker(
            () -> fakeDataSource,
            /* downloadExecutorSupplier= */ null,
            mockCallback,
            /* mediaTransferListener= */ null,
            initialDashManifest);
  }

  @After
  public void tearDown() {
    contentSteeringTracker.release();
  }

  @Test
  public void start_withMultipleValidInitialPathwayIds_picksInitialPathwaysAndNotifiesCallback() {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A", "CDN-B", "CDN-C"),
        new MediaSourceEventListener.EventDispatcher());

    verify(mockCallback)
        .onServiceLocationPriorityUpdated(ImmutableList.of("CDN-A", "CDN-B", "CDN-C"));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_withMultipleInitialPathwayIdsOneInvalid_throwsIllegalStateException() {
    assertThrows(
        IllegalStateException.class,
        () ->
            contentSteeringTracker.start(
                Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
                ImmutableList.of("CDN-A", "CDN-INVALID"),
                new MediaSourceEventListener.EventDispatcher()));
    assertThat(contentSteeringTracker.isActive()).isFalse();
  }

  @Test
  public void start_onSteeringManifestUpdated_propagatesPathwayPriorityUpdate() throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(
        /* maxTimeDiffMs= */ 5_000L, () -> serviceLocationPriorityUpdateCount.get() >= 2);

    verify(mockCallback).onServiceLocationPriorityUpdated(ImmutableList.of("CDN-A"));
    verify(mockCallback)
        .onServiceLocationPriorityUpdated(ImmutableList.of("CDN-B", "CDN-A", "CDN-C"));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void updateManifest_updatesAvailablePathways() {
    ImmutableList<Location> locations =
        ImmutableList.of(new Location(Uri.parse("https://loc-a"), "CDN-A"));
    DashManifest manifest = createDashManifest(locations, ImmutableList.of());
    contentSteeringTracker =
        new DashContentSteeringTracker(
            FakeDataSource::new,
            /* downloadExecutorSupplier= */ null,
            mockCallback,
            /* mediaTransferListener= */ null,
            manifest);

    assertThat(contentSteeringTracker.isPathwayAvailable("CDN-A")).isTrue();
    assertThat(contentSteeringTracker.isPathwayAvailable("CDN-B")).isFalse();

    // Add CDN-B in Location and CDN-C in BaseUrl.
    ImmutableList<Location> newLocations =
        ImmutableList.of(
            new Location(Uri.parse("https://loc-a"), "CDN-A"),
            new Location(Uri.parse("https://loc-b"), "CDN-B"));
    ImmutableList<BaseUrl> newBaseUrls =
        ImmutableList.of(new BaseUrl("http://cdn-c", "CDN-C", 1, 1));
    DashManifest newManifest = createDashManifest(newLocations, newBaseUrls);
    contentSteeringTracker.updateManifest(newManifest);

    assertThat(contentSteeringTracker.isPathwayAvailable("CDN-A")).isTrue();
    assertThat(contentSteeringTracker.isPathwayAvailable("CDN-B")).isTrue();
    assertThat(contentSteeringTracker.isPathwayAvailable("CDN-C")).isTrue();
  }

  @Test
  public void
      getSteeringQueryParameters_withMultipleSteeringQueryParamsProviders_returnsCorrectQueryParameters()
          throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(() -> serviceLocationPriorityUpdateCount.get() >= 2);
    contentSteeringTracker.addSteeringQueryParamsProvider(() -> ImmutableList.of("CDN-A", "CDN-B"));
    contentSteeringTracker.addSteeringQueryParamsProvider(() -> ImmutableList.of("CDN-B", "CDN-C"));
    TransferListener transferListener = contentSteeringTracker.getMediaTransferListener();
    simulateTransfers(
        transferListener,
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-a.com/chunk.mp4"))
            .setLocation("CDN-A")
            .build(),
        /* bytesTransferred= */ 1000_000,
        /* transferDurationMs= */ 1000);
    simulateTransfers(
        transferListener,
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-b.com/chunk.mp4"))
            .setLocation("CDN-B")
            .build(),
        /* bytesTransferred= */ 500_000,
        /* transferDurationMs= */ 2000);

    ImmutableMap<String, String> params = contentSteeringTracker.getSteeringQueryParameters();

    assertThat(params).containsKey("_DASH_pathway");
    assertThat(params).containsKey("_DASH_throughput");
    String[] pathways = params.get("_DASH_pathway").split(",", /* limit= */ -1);
    String[] throughputs =
        params
            .get("_DASH_throughput")
            .split(",", /* limit= */ -1); // keep the trailing empty string
    assertThat(pathways).hasLength(3);
    assertThat(throughputs).hasLength(3);
    Map<String, String> serviceLocationsToThroughputs = new HashMap<>();
    for (int i = 0; i < 3; i++) {
      serviceLocationsToThroughputs.put(pathways[i], throughputs[i]);
    }
    assertThat(serviceLocationsToThroughputs).containsEntry("CDN-A", "8000000");
    assertThat(serviceLocationsToThroughputs).containsEntry("CDN-B", "2000000");
    assertThat(serviceLocationsToThroughputs).containsEntry("CDN-C", "");
  }

  @Test
  public void
      getSteeringQueryParameters_forFirstSteeringManifestRequest_omitsPathwayAndThroughputParams()
          throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(() -> serviceLocationPriorityUpdateCount.get() == 1);

    ImmutableMap<String, String> params = contentSteeringTracker.getSteeringQueryParameters();

    assertThat(params).doesNotContainKey("_DASH_pathway");
    assertThat(params).doesNotContainKey("_DASH_throughput");
  }

  @Test
  public void getSteeringQueryParameters_noSteeredServiceLocations_omitsPathwayAndThroughputParams()
      throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(() -> serviceLocationPriorityUpdateCount.get() >= 2);

    ImmutableMap<String, String> params = contentSteeringTracker.getSteeringQueryParameters();

    assertThat(params).doesNotContainKey("_DASH_pathway");
    assertThat(params).doesNotContainKey("_DASH_throughput");
  }

  @Test
  public void getSteeringQueryParameters_noThroughputEstimate_omitsThroughputParam()
      throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(() -> serviceLocationPriorityUpdateCount.get() >= 2);
    contentSteeringTracker.addSteeringQueryParamsProvider(() -> ImmutableList.of("CDN-A", "CDN-B"));

    ImmutableMap<String, String> params = contentSteeringTracker.getSteeringQueryParameters();

    assertThat(params).containsKey("_DASH_pathway");
    assertThat(params).doesNotContainKey("_DASH_throughput");
  }

  @Test
  public void removeSteeringQueryParamsProvider_providerInfoDoesNotAppearInQueryParameters()
      throws Exception {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    runMainLooperUntil(() -> serviceLocationPriorityUpdateCount.get() >= 2);

    contentSteeringTracker.addSteeringQueryParamsProvider(() -> ImmutableList.of("CDN-A", "CDN-B"));
    DashContentSteeringTracker.SteeringQueryParamsProvider anotherProvider =
        () -> ImmutableList.of("CDN-B", "CDN-C");
    contentSteeringTracker.addSteeringQueryParamsProvider(anotherProvider);
    contentSteeringTracker.removeSteeringQueryParamsProvider(anotherProvider);
    TransferListener transferListener = contentSteeringTracker.getMediaTransferListener();
    simulateTransfers(
        transferListener,
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-a.com/chunk.mp4"))
            .setLocation("CDN-A")
            .build(),
        /* bytesTransferred= */ 1000_000,
        /* transferDurationMs= */ 1000);
    simulateTransfers(
        transferListener,
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-b.com/chunk.mp4"))
            .setLocation("CDN-B")
            .build(),
        /* bytesTransferred= */ 500_000,
        /* transferDurationMs= */ 2000);
    simulateTransfers(
        transferListener,
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-c.com/chunk.mp4"))
            .setLocation("CDN-C")
            .build(),
        /* bytesTransferred= */ 500_000,
        /* transferDurationMs= */ 2000);

    ImmutableMap<String, String> params = contentSteeringTracker.getSteeringQueryParameters();

    assertThat(params).containsKey("_DASH_pathway");
    assertThat(params).containsKey("_DASH_throughput");
    String[] pathways = params.get("_DASH_pathway").split(",", /* limit= */ -1);
    String[] throughputs =
        params
            .get("_DASH_throughput")
            .split(",", /* limit= */ -1); // keep the trailing empty string
    assertThat(pathways).hasLength(2);
    assertThat(throughputs).hasLength(2);
    Map<String, String> serviceLocationsToThroughputs = new HashMap<>();
    for (int i = 0; i < 2; i++) {
      serviceLocationsToThroughputs.put(pathways[i], throughputs[i]);
    }
    assertThat(serviceLocationsToThroughputs).containsEntry("CDN-A", "8000000");
    assertThat(serviceLocationsToThroughputs).containsEntry("CDN-B", "2000000");
  }

  @Test
  public void release_noLongerActive() {
    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        ImmutableList.of("CDN-A"),
        new MediaSourceEventListener.EventDispatcher());
    assertThat(contentSteeringTracker.isActive()).isTrue();

    contentSteeringTracker.release();
    assertThat(contentSteeringTracker.isActive()).isFalse();
  }

  private static DashManifest createDashManifest(List<Location> locations, List<BaseUrl> baseUrls) {
    List<BaseUrl> manifestBaseUrls =
        baseUrls.isEmpty() ? ImmutableList.of(new BaseUrl("http://default")) : baseUrls;
    Representation representation =
        new Representation.SingleSegmentRepresentation(
            /* revisionId= */ 1L,
            new Format.Builder().build(),
            manifestBaseUrls,
            new SingleSegmentBase(),
            /* inbandEventStreams= */ null,
            /* essentialProperties= */ ImmutableList.of(),
            /* supplementalProperties= */ ImmutableList.of(),
            /* cacheKey= */ null,
            /* contentLength= */ 1);
    AdaptationSet adaptationSet =
        new AdaptationSet(
            /* id= */ 1,
            /* type= */ C.TRACK_TYPE_VIDEO,
            ImmutableList.of(representation),
            /* accessibilityDescriptors= */ ImmutableList.of(),
            /* essentialProperties= */ ImmutableList.of(),
            /* supplementalProperties= */ ImmutableList.of());
    Period period =
        new Period(/* id= */ "period1", /* startMs= */ 0, ImmutableList.of(adaptationSet));
    return new DashManifest(
        /* availabilityStartTimeMs= */ 0,
        /* durationMs= */ 1000,
        /* minBufferTimeMs= */ 1,
        /* dynamic= */ false,
        /* minUpdatePeriodMs= */ 2,
        /* timeShiftBufferDepthMs= */ 3,
        /* suggestedPresentationDelayMs= */ 4,
        /* publishTimeMs= */ 12345,
        /* programInformation= */ null,
        /* utcTiming= */ null,
        /* serviceDescription= */ null,
        ImmutableList.of(period),
        locations,
        new ContentSteering(
            Uri.parse("http://example.com/steer"),
            /* defaultServiceLocation= */ new String[0],
            /* queryBeforeStart= */ true));
  }

  private static byte[] getBytes(String jsonString) {
    return jsonString.getBytes(Charset.defaultCharset());
  }

  private static void simulateTransfers(
      @Nullable TransferListener transferListener,
      DataSpec dataSpec,
      long bytesTransferred,
      long transferDurationMs) {
    if (transferListener == null) {
      return;
    }
    DataSource dataSource = new FakeDataSource();
    transferListener.onTransferInitializing(dataSource, dataSpec, /* isNetwork= */ true);
    transferListener.onTransferStart(dataSource, dataSpec, /* isNetwork= */ true);
    transferListener.onBytesTransferred(
        dataSource, dataSpec, /* isNetwork= */ true, (int) bytesTransferred);
    ShadowSystemClock.advanceBy(Duration.ofMillis(transferDurationMs));
    transferListener.onTransferEnd(dataSource, dataSpec, /* isNetwork= */ true);
  }
}
