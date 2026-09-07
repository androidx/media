/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.test.utils.FakeDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link ServiceLocationFilteringThroughputEstimator}. */
@RunWith(AndroidJUnit4.class)
public final class ServiceLocationFilteringThroughputEstimatorTest {

  private static final String SERVICE_LOCATION_A = "serviceLocationA";
  private static final String SERVICE_LOCATION_B = "serviceLocationB";

  @Test
  public void onTransferEvents_withDelegateListener_updatesEstimateAndForwardsToDelegate() {
    TransferListener delegateListener = mock(TransferListener.class);
    ServiceLocationFilteringThroughputEstimator estimator =
        new ServiceLocationFilteringThroughputEstimator(delegateListener);
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-a.com/chunk.mp4"))
            .setLocation(SERVICE_LOCATION_A)
            .build();

    simulateTransfers(
        estimator,
        dataSpec,
        /* bytesTransferred= */ 1_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ true);

    verify(delegateListener).onTransferInitializing(any(), eq(dataSpec), eq(true));
    verify(delegateListener).onTransferStart(any(), eq(dataSpec), eq(true));
    verify(delegateListener).onBytesTransferred(any(), eq(dataSpec), eq(true), eq(1_000_000));
    verify(delegateListener).onTransferEnd(any(), eq(dataSpec), eq(true));
    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_A)).isEqualTo(8_000_000L);
  }

  @Test
  public void getThroughputEstimate_multipleServiceLocations_estimatesThroughputIndependently() {
    ServiceLocationFilteringThroughputEstimator estimator =
        new ServiceLocationFilteringThroughputEstimator(/* delegateTransferListener= */ null);
    DataSpec dataSpecA =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-a.com/chunk.mp4"))
            .setLocation(SERVICE_LOCATION_A)
            .build();
    DataSpec dataSpecB =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-b.com/chunk.mp4"))
            .setLocation(SERVICE_LOCATION_B)
            .build();

    // Transfer for location A: 1MB in 1s = 8Mbps
    simulateTransfers(
        estimator,
        dataSpecA,
        /* bytesTransferred= */ 1_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ true);

    // Transfer twice for location B: 500KB in 2s = 2Mbps
    simulateTransfers(
        estimator,
        dataSpecB,
        /* bytesTransferred= */ 500_000,
        /* transferDurationMs= */ 2000L,
        /* isNetwork= */ true);
    simulateTransfers(
        estimator,
        dataSpecB,
        /* bytesTransferred= */ 500_000,
        /* transferDurationMs= */ 2000L,
        /* isNetwork= */ true);

    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_A)).isEqualTo(8_000_000L);
    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_B)).isEqualTo(2_000_000L);
  }

  @Test
  public void getThroughputEstimate_unknownServiceLocation_returnsZero() {
    ServiceLocationFilteringThroughputEstimator estimator =
        new ServiceLocationFilteringThroughputEstimator(/* delegateTransferListener= */ null);

    assertThat(estimator.getThroughputEstimate("unknown_location")).isEqualTo(0L);
  }

  @Test
  public void
      onTransferEventsNotAtFullNetworkSpeed_withDelegateListener_ignoresForEstimateAndForwardsToDelegate() {
    TransferListener delegateListener = mock(TransferListener.class);
    ServiceLocationFilteringThroughputEstimator estimator =
        new ServiceLocationFilteringThroughputEstimator(delegateListener);
    DataSpec dataSpecA =
        new DataSpec.Builder()
            .setUri(Uri.parse("file:///path/to/media.mp4"))
            .setLocation(SERVICE_LOCATION_A)
            .build();
    simulateTransfers(
        estimator,
        dataSpecA,
        /* bytesTransferred= */ 1_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ false);

    verify(delegateListener).onTransferStart(any(), eq(dataSpecA), eq(false));
    verify(delegateListener).onBytesTransferred(any(), eq(dataSpecA), eq(false), eq(1_000_000));
    verify(delegateListener).onTransferEnd(any(), eq(dataSpecA), eq(false));
    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_A)).isEqualTo(0L);

    DataSpec dataSpecB =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://cdn-b.com/chunk.mp4"))
            .setLocation(SERVICE_LOCATION_B)
            .setFlags(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
            .build();
    simulateTransfers(
        estimator,
        dataSpecB,
        /* bytesTransferred= */ 1_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ true);

    verify(delegateListener).onTransferStart(any(), eq(dataSpecB), eq(true));
    verify(delegateListener).onBytesTransferred(any(), eq(dataSpecB), eq(true), eq(1_000_000));
    verify(delegateListener).onTransferEnd(any(), eq(dataSpecB), eq(true));
    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_B)).isEqualTo(0L);
  }

  @Test
  public void stopThroughputEstimate_clearsAllEstimatesButStillForwardsToDelegate() {
    TransferListener delegateListener = mock(TransferListener.class);
    ServiceLocationFilteringThroughputEstimator estimator =
        new ServiceLocationFilteringThroughputEstimator(delegateListener);
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/chunk.mp4"))
            .setLocation(SERVICE_LOCATION_A)
            .build();
    simulateTransfers(
        estimator,
        dataSpec,
        /* bytesTransferred= */ 1_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ true);
    assertThat(estimator.getThroughputEstimate(SERVICE_LOCATION_A)).isEqualTo(8_000_000L);

    estimator.stopThroughputEstimate();
    long estimateForExistingTransfer = estimator.getThroughputEstimate(SERVICE_LOCATION_A);

    DataSpec newDataSpec =
        new DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/chunk2.mp4"))
            .setLocation(SERVICE_LOCATION_A)
            .build();
    simulateTransfers(
        estimator,
        newDataSpec,
        /* bytesTransferred= */ 2_000_000,
        /* transferDurationMs= */ 1000L,
        /* isNetwork= */ true);
    long estimateForNewTransfer = estimator.getThroughputEstimate(SERVICE_LOCATION_A);

    assertThat(estimateForExistingTransfer).isEqualTo(0L);
    assertThat(estimateForNewTransfer).isEqualTo(0L);
    verify(delegateListener).onTransferInitializing(any(), eq(newDataSpec), eq(true));
    verify(delegateListener).onTransferStart(any(), eq(newDataSpec), eq(true));
    verify(delegateListener).onBytesTransferred(any(), eq(newDataSpec), eq(true), eq(2_000_000));
    verify(delegateListener).onTransferEnd(any(), eq(newDataSpec), eq(true));
  }

  private static void simulateTransfers(
      ServiceLocationFilteringThroughputEstimator estimator,
      DataSpec dataSpec,
      int bytesTransferred,
      long transferDurationMs,
      boolean isNetwork) {
    DataSource dataSource = new FakeDataSource();
    estimator.onTransferInitializing(dataSource, dataSpec, /* isNetwork= */ isNetwork);
    estimator.onTransferStart(dataSource, dataSpec, /* isNetwork= */ isNetwork);
    estimator.onBytesTransferred(
        dataSource, dataSpec, /* isNetwork= */ isNetwork, bytesTransferred);
    ShadowSystemClock.advanceBy(Duration.ofMillis(transferDurationMs));
    estimator.onTransferEnd(dataSource, dataSpec, /* isNetwork= */ isNetwork);
  }
}
