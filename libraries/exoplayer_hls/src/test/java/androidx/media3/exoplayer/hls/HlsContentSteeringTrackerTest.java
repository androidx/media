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
package androidx.media3.exoplayer.hls;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.SystemClock;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.charset.Charset;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test for {@link HlsContentSteeringTracker}. */
@RunWith(AndroidJUnit4.class)
public final class HlsContentSteeringTrackerTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private static final String TEST_INITIAL_STEERING_URI_STRING = "https://steering";

  @Mock private BandwidthMeter mockBandwidthMeter;
  @Mock private HlsPlaylistTracker mockPlaylistTracker;
  @Mock private HlsContentSteeringTracker.Callback mockContentSteeringTrackerCallback;
  private AtomicInteger currentPathwayUpdateCount;

  @Before
  public void setUp() {
    setupHlsPlaylistTracker();
    when(mockBandwidthMeter.getBitrateEstimate()).thenReturn(500_000L);
    currentPathwayUpdateCount = new AtomicInteger();
    doAnswer(invocation -> currentPathwayUpdateCount.incrementAndGet())
        .when(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(any(), any(), anyLong());
  }

  @Test
  public void start_withValidInitialPathwayId_picksTheInitialPathwayAndNotifyCallback() {
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            new FakeDataSource(),
            /* initialPathwayId= */ "CDN-B",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 1,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-B"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void
      start_withoutInitialPathwayId_picksTheCurrentPathwayOfVariantRedundantGroupAndNotifyCallback() {
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            new FakeDataSource(),
            /* initialPathwayId= */ null,
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 1,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_withInvalidInitialPathwayId_throwsIllegalStateException() {
    assertThrows(
        IllegalStateException.class,
        () ->
            runContentSteeringTracker(
                new FakeDataSource(),
                /* initialPathwayId= */ "CDN-D",
                mockContentSteeringTrackerCallback,
                SystemClock.DEFAULT,
                /* awaitedCurrentPathwayUpdateCount= */ 0,
                /* timeoutExceptionRef= */ new AtomicReference<>()));
  }

  @Test
  public void start_onSteeringManifestUpdated_evaluatesAndUpdatesPathway() {
    String steeringManifest =
        "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-C\", \"CDN-B\", \"CDN-A\"]}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());

    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-C"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_onSteeringManifestUpdatedWithPathwayClones_notifiesCallback() {
    String steeringManifest =
        "{\n"
            + "  \"VERSION\": 1,\n"
            + "  \"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\", \"CDN-B\", \"CDN-C\"],\n"
            + "  \"PATHWAY-CLONES\": [\n"
            + "    {\n"
            + "      \"BASE-ID\": \"CDN-A\",\n"
            + "      \"ID\": \"CDN-A-CLONE\",\n"
            + "      \"URI-REPLACEMENT\": {\n"
            + "        \"HOST\": \"backup.com\",\n"
            + "        \"PARAMS\": {\n"
            + "          \"param1\": \"newValue1\",\n"
            + "          \"param2\": \"newValue2\"\n"
            + "        },\n"
            + "        \"PER-VARIANT-URIS\": {\n"
            + "          \"VIDEO-720\": \"https://backup2.com/720.test\"\n"
            + "        },\n"
            + "        \"PER-RENDITION-URIS\": {\n"
            + "          \"AUDIO\": \"https://backup2.com/audio.test\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());

    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));

    ImmutableList.Builder<Uri> expectedNewPlaylistUrls = new ImmutableList.Builder<>();
    ImmutableList.Builder<Uri> expectedBasePlaylistUrls = new ImmutableList.Builder<>();
    // The 720p variant playlist url and audio rendition playlist url (the first and third in the
    // list) should be fully overridden by the new urls declared in the PER-VARIANT-URIS and
    // PER-RENDITION-URIS. The 360p variant playlist url (the second in the list) should have its
    // host component to be replaced by the HOST, and parameters declared in PARAMS appended as the
    // query parameters.
    expectedNewPlaylistUrls.add(
        Uri.parse("https://backup2.com/720.test"),
        Uri.parse("https://backup.com/360.test?param1=newValue1&param2=newValue2"),
        Uri.parse("https://backup2.com/audio.test"));
    expectedBasePlaylistUrls.add(
        Uri.parse("https://cdn-a/720.test"),
        Uri.parse("https://cdn-a/360.test?param1=value1"),
        Uri.parse("https://cdn-a/audio.test"));
    verify(mockContentSteeringTrackerCallback)
        .onNewPathwayAvailable(
            /* newPathwayId= */ eq("CDN-A-CLONE"),
            /* basePathwayId= */ eq("CDN-A"),
            /* newPlaylistUrls= */ eq(expectedNewPlaylistUrls.build()),
            /* basePlaylistUrls= */ eq(expectedBasePlaylistUrls.build()));

    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A-CLONE"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_onSteeringManifestUpdatedWithPerVariantUrisPathwayCloneOnly_notifiesCallback() {
    String steeringManifest =
        "{\n"
            + "  \"VERSION\": 1,\n"
            + "  \"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\", \"CDN-B\", \"CDN-C\"],\n"
            + "  \"PATHWAY-CLONES\": [\n"
            + "    {\n"
            + "      \"BASE-ID\": \"CDN-A\",\n"
            + "      \"ID\": \"CDN-A-CLONE\",\n"
            + "      \"URI-REPLACEMENT\": {\n"
            + "        \"PER-VARIANT-URIS\": {\n"
            + "          \"VIDEO-720\": \"https://backup2.com/720.test\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());

    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));

    ImmutableList.Builder<Uri> expectedNewPlaylistUrls = new ImmutableList.Builder<>();
    ImmutableList.Builder<Uri> expectedBasePlaylistUrls = new ImmutableList.Builder<>();
    // The 720p variant playlist url (the first in the list) should be fully overridden by the new
    // url declared in the PER-VARIANT-URIS. The 360p variant playlist url and the audio rendition
    // playlist url (the second and third in the list) should be simply copied from the base urls
    // as there is no other general URI replacement instruction provided.
    expectedNewPlaylistUrls.add(
        Uri.parse("https://backup2.com/720.test"),
        Uri.parse("https://cdn-a/360.test?param1=value1"),
        Uri.parse("https://cdn-a/audio.test"));
    expectedBasePlaylistUrls.add(
        Uri.parse("https://cdn-a/720.test"),
        Uri.parse("https://cdn-a/360.test?param1=value1"),
        Uri.parse("https://cdn-a/audio.test"));
    verify(mockContentSteeringTrackerCallback)
        .onNewPathwayAvailable(
            /* newPathwayId= */ eq("CDN-A-CLONE"),
            /* basePathwayId= */ eq("CDN-A"),
            /* newPlaylistUrls= */ eq(expectedNewPlaylistUrls.build()),
            /* basePlaylistUrls= */ eq(expectedBasePlaylistUrls.build()));

    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A-CLONE"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_onSteeringManifestUpdatedWithPathwayCloneWithUnknownBaseId_skipsClone() {
    String steeringManifest =
        "{\n"
            + "  \"VERSION\": 1,\n"
            + "  \"PATHWAY-PRIORITY\": [\"CDN-D-CLONE\", \"CDN-A\", \"CDN-B\", \"CDN-C\"],\n"
            + "  \"PATHWAY-CLONES\": [\n"
            + "    {\n"
            + "      \"BASE-ID\": \"CDN-D\",\n"
            + "      \"ID\": \"CDN-D-CLONE\",\n"
            + "      \"URI-REPLACEMENT\": {\n"
            + "        \"PER-VARIANT-URIS\": {\n"
            + "          \"VIDEO-720\": \"https://backup2.com/720.test\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());

    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 1,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback, never())
        .onNewPathwayAvailable(any(), any(), any(), any());
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_onSteeringManifestUpdatedWithPathwayCloneWithExistingId_skipsClone() {
    String steeringManifest =
        "{\n"
            + "  \"VERSION\": 1,\n"
            + "  \"PATHWAY-PRIORITY\": [\"CDN-B\", \"CDN-A\", \"CDN-C\"],\n"
            + "  \"PATHWAY-CLONES\": [\n"
            + "    {\n"
            + "      \"BASE-ID\": \"CDN-A\",\n"
            + "      \"ID\": \"CDN-B\",\n"
            + "      \"URI-REPLACEMENT\": {\n"
            + "        \"PER-VARIANT-URIS\": {\n"
            + "          \"VIDEO-720\": \"https://backup2.com/720.test\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());

    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-B"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback, never())
        .onNewPathwayAvailable(any(), any(), any(), any());
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void
      start_newSteeringManifestWithUnknownPathwayIdInPriorityList_evaluatesAndUpdatesWithSkippingUnknownPathway() {
    String steeringManifest =
        "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-D\", \"CDN-C\", \"CDN-B\", \"CDN-A\"]}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-C"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_steeringManifestLoadCanceledWhenNoPriorityListAvailable_stopsTracker() {
    FakeDataSet fakeDataSet =
        new FakeDataSet()
            .newDefaultData()
            .appendReadError(
                new HttpDataSource.InvalidResponseCodeException(
                    /* responseCode= */ 410,
                    /* responseMessage= */ "Gone",
                    /* cause= */ null,
                    /* headerFields= */ ImmutableMap.of(),
                    new DataSpec(Uri.EMPTY),
                    /* responseBody= */ new byte[0]))
            .appendReadData(2)
            .endData();
    FakeDataSource fakeDataSource = new FakeDataSource(fakeDataSet);
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    // We expect to encounter a TimeoutException as we want to verify that only one update of
    // current pathway is received and we wait long enough.
    assertThat(timeoutExceptionRef.get()).isNotNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isFalse();
  }

  @Test
  public void start_steeringManifestLoadCanceledWhenPriorityListAvailable_doesNotStopTracker() {
    String steeringManifest =
        "{\"VERSION\": 1, \"RELOAD-URI\": \"https://reload\", \"PATHWAY-PRIORITY\": [\"CDN-C\","
            + " \"CDN-B\", \"CDN-A\"]}";
    FakeDataSet fakeDataSet =
        new FakeDataSet()
            .setData(
                "https://steering?_HLS_pathway=CDN-A&_HLS_throughput=500000",
                getBytes(steeringManifest))
            .newData("https://reload?_HLS_pathway=CDN-C&_HLS_throughput=500000")
            .appendReadError(
                new HttpDataSource.InvalidResponseCodeException(
                    /* responseCode= */ 410,
                    /* responseMessage= */ "Gone",
                    /* cause= */ null,
                    /* headerFields= */ ImmutableMap.of(),
                    new DataSpec(Uri.EMPTY),
                    /* responseBody= */ new byte[0]))
            .endData();
    FakeDataSource fakeDataSource = new FakeDataSource(fakeDataSet);
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 3,
            timeoutExceptionRef);

    // We expect to encounter a TimeoutException as we want to verify that only two updates of
    // current pathway are received and we wait long enough.
    assertThat(timeoutExceptionRef.get()).isNotNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-C"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void start_steeringManifestLoadErrorButNotCanceled_doesNotStopTracker() {
    FakeDataSet fakeDataSet =
        new FakeDataSet()
            .newDefaultData()
            .appendReadError(
                new HttpDataSource.InvalidResponseCodeException(
                    /* responseCode= */ 404,
                    /* responseMessage= */ "Not found",
                    /* cause= */ null,
                    /* headerFields= */ ImmutableMap.of(),
                    new DataSpec(Uri.EMPTY),
                    /* responseBody= */ new byte[0]))
            .endData();
    FakeDataSource fakeDataSource = new FakeDataSource(fakeDataSet);
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();

    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    // We expect to encounter a TimeoutException as we want to verify that only one update of
    // current pathway is received and we wait long enough.
    assertThat(timeoutExceptionRef.get()).isNotNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    assertThat(contentSteeringTracker.isActive()).isTrue();
  }

  @Test
  public void
      excludeCurrentPathway_lowerPriorityPathwaysAvailable_excludesSuccessfullyAndUpdatesPathway() {
    FakeClock clock = new FakeClock.Builder().build();
    String steeringManifest =
        "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-C\", \"CDN-B\", \"CDN-A\"]}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            clock,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);
    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-C"),
            /* previousPathwayId= */ eq("CDN-A"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    clearInvocations(mockContentSteeringTrackerCallback);

    // Exclude CDN-A
    assertThat(contentSteeringTracker.excludeCurrentPathway(10_000L)).isTrue();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-B"),
            /* previousPathwayId= */ eq("CDN-C"),
            /* previousPathwayExcludeDurationMs= */ eq(10_000L));

    // Wait for the exclusion period of CDN-A to expire
    clock.advanceTime(10_000L);
    shadowOf(Looper.getMainLooper()).idle();

    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-C"),
            /* previousPathwayId= */ eq("CDN-B"),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
  }

  @Test
  public void
      excludeCurrentPathway_lowerPriorityPathwaysUnavailable_excludesUnsuccessfullyAndDoesNotUpdatePathway() {
    String steeringManifest = "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-A\"]}";
    FakeDataSource fakeDataSource =
        new FakeDataSource(
            new FakeDataSet()
                .newDefaultData()
                .appendReadData(getBytes(steeringManifest))
                .endData());
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            fakeDataSource,
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 1,
            timeoutExceptionRef);
    assertThat(timeoutExceptionRef.get()).isNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(eq("CDN-A"), eq(null), eq(C.TIME_UNSET));
    clearInvocations(mockContentSteeringTrackerCallback);

    // Exclude CDN-A
    assertThat(contentSteeringTracker.excludeCurrentPathway(10_000L)).isFalse();
    verifyNoInteractions(mockContentSteeringTrackerCallback);
  }

  @Test
  public void
      excludeCurrentPathway_beforeFirstPathwayPriorityListAvailable_excludesUnsuccessfullyAndDoesNotUpdatePathway() {
    AtomicReference<TimeoutException> timeoutExceptionRef = new AtomicReference<>();
    HlsContentSteeringTracker contentSteeringTracker =
        runContentSteeringTracker(
            new FakeDataSource(),
            /* initialPathwayId= */ "CDN-A",
            mockContentSteeringTrackerCallback,
            SystemClock.DEFAULT,
            /* awaitedCurrentPathwayUpdateCount= */ 2,
            timeoutExceptionRef);

    // We expect to encounter a TimeoutException as we want to verify that only one update of
    // current pathway is received and we wait long enough.
    assertThat(timeoutExceptionRef.get()).isNotNull();
    verify(mockContentSteeringTrackerCallback)
        .onCurrentPathwayUpdated(
            /* currentPathwayId= */ eq("CDN-A"),
            /* previousPathwayId= */ eq(null),
            /* previousPathwayExcludeDurationMs= */ eq(C.TIME_UNSET));
    clearInvocations(mockContentSteeringTrackerCallback);

    // Exclude CDN-A
    assertThat(contentSteeringTracker.excludeCurrentPathway(10_000L)).isFalse();
    verifyNoInteractions(mockContentSteeringTrackerCallback);
  }

  private void setupHlsPlaylistTracker() {
    HlsRedundantGroup.GroupKey variantGroupKey1 =
        new HlsRedundantGroup.GroupKey(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            /* stableId= */ "VIDEO-720");
    HlsRedundantGroup.GroupKey variantGroupKey2 =
        new HlsRedundantGroup.GroupKey(
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            /* stableId= */ "VIDEO-360");
    HlsRedundantGroup.GroupKey audioGroupKey =
        new HlsRedundantGroup.GroupKey(
            new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
            /* stableId= */ "AUDIO");
    HlsRedundantGroup variantRedundantGroup1 =
        new HlsRedundantGroup(variantGroupKey1, "CDN-A", Uri.parse("https://cdn-a/720.test"));
    variantRedundantGroup1.put("CDN-B", Uri.parse("https://cdn-b/720.test"));
    variantRedundantGroup1.put("CDN-C", Uri.parse("https://cdn-c/720.test"));
    HlsRedundantGroup variantRedundantGroup2 =
        new HlsRedundantGroup(
            variantGroupKey2, "CDN-A", Uri.parse("https://cdn-a/360.test?param1=value1"));
    variantRedundantGroup2.put("CDN-B", Uri.parse("https://cdn-b/360.test"));
    variantRedundantGroup2.put("CDN-C", Uri.parse("https://cdn-c/360.test"));
    HlsRedundantGroup audioRedundantGroup =
        new HlsRedundantGroup(audioGroupKey, "CDN-A", Uri.parse("https://cdn-a/audio.test"));
    audioRedundantGroup.put("CDN-B", Uri.parse("https://cdn-b/audio.test"));
    audioRedundantGroup.put("CDN-C", Uri.parse("https://cdn-c/audio.test"));

    when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT))
        .thenReturn(ImmutableList.of(variantRedundantGroup1, variantRedundantGroup2));
    when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.AUDIO_RENDITION))
        .thenReturn(ImmutableList.of(audioRedundantGroup));
    when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.VIDEO_RENDITION))
        .thenReturn(ImmutableList.of());
    when(mockPlaylistTracker.getRedundantGroups(HlsRedundantGroup.SUBTITLE_RENDITION))
        .thenReturn(ImmutableList.of());
  }

  private HlsContentSteeringTracker runContentSteeringTracker(
      FakeDataSource fakeDataSource,
      String initialPathwayId,
      HlsContentSteeringTracker.Callback callback,
      Clock clock,
      int awaitedCurrentPathwayUpdateCount,
      AtomicReference<TimeoutException> timeoutExceptionRef) {
    HlsDataSourceFactory mockHlsDataSourceFactory = mock(HlsDataSourceFactory.class);
    when(mockHlsDataSourceFactory.createDataSource(anyInt())).thenReturn(fakeDataSource);
    HlsContentSteeringTracker contentSteeringTracker =
        new HlsContentSteeringTracker(
            mockHlsDataSourceFactory,
            /* downloadExecutorSupplier= */ null,
            mockPlaylistTracker,
            callback,
            mockBandwidthMeter,
            clock);

    contentSteeringTracker.start(
        Uri.parse(TEST_INITIAL_STEERING_URI_STRING),
        initialPathwayId,
        new MediaSourceEventListener.EventDispatcher());

    try {
      runMainLooperUntil(
          /* maxTimeDiffMs= */ 3_000L,
          () -> currentPathwayUpdateCount.get() >= awaitedCurrentPathwayUpdateCount);
    } catch (TimeoutException e) {
      timeoutExceptionRef.set(e);
    }
    return contentSteeringTracker;
  }

  private static byte[] getBytes(String jsonString) {
    return jsonString.getBytes(Charset.defaultCharset());
  }
}
