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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.experimental.BandwidthEstimator;
import androidx.media3.exoplayer.upstream.experimental.SplitParallelSampleBandwidthEstimator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An estimator that estimates media download throughput from network per service location.
 *
 * <p>The throughput estimates for each service location are calculated based on a sliding window of
 * average of the throughput samples.
 */
@UnstableApi
/* package */ final class ServiceLocationFilteringThroughputEstimator implements TransferListener {

  @Nullable private final TransferListener delegateTransferListener;
  private final ConcurrentHashMap<String, BandwidthEstimator> estimatorsByServiceLocation;

  private volatile boolean throughputEstimateStopped;

  /**
   * Creates an instance.
   *
   * @param delegateTransferListener A delegate {@link TransferListener} to forward transfer events
   *     to, or {@code null}.
   */
  public ServiceLocationFilteringThroughputEstimator(
      @Nullable TransferListener delegateTransferListener) {
    this.delegateTransferListener = delegateTransferListener;
    this.estimatorsByServiceLocation = new ConcurrentHashMap<>();
  }

  /**
   * Returns the estimated throughput in bits per second for the given service location, or {@code
   * 0} if no estimate is available.
   *
   * @param serviceLocation The ID of service location.
   * @return The throughput estimate in bits per second.
   */
  public long getThroughputEstimate(String serviceLocation) {
    @Nullable BandwidthEstimator estimator = estimatorsByServiceLocation.get(serviceLocation);
    if (estimator == null) {
      return 0L;
    }
    synchronized (estimator) {
      long estimate = estimator.getBandwidthEstimate();
      return estimate == BandwidthEstimator.ESTIMATE_NOT_AVAILABLE ? 0L : estimate;
    }
  }

  /**
   * Stops throughput estimate.
   *
   * <p>After this method is called, {@link #getThroughputEstimate(String)} will return {@code 0}
   * for all service locations. The estimator will still forward transfer events to the delegate
   * transfer listener.
   */
  public void stopThroughputEstimate() {
    throughputEstimateStopped = true;
    estimatorsByServiceLocation.clear();
  }

  // TransferListener implementations

  @Override
  public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (delegateTransferListener != null) {
      delegateTransferListener.onTransferInitializing(source, dataSpec, isNetwork);
    }
  }

  @Override
  public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (delegateTransferListener != null) {
      delegateTransferListener.onTransferStart(source, dataSpec, isNetwork);
    }
    if (throughputEstimateStopped || !isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    @Nullable String serviceLocation = dataSpec.location;
    if (serviceLocation != null) {
      estimatorsByServiceLocation.putIfAbsent(
          serviceLocation, new SplitParallelSampleBandwidthEstimator.Builder().build());
      @Nullable BandwidthEstimator estimator = estimatorsByServiceLocation.get(serviceLocation);
      if (estimator != null) {
        synchronized (estimator) {
          estimator.onTransferStart(source);
        }
      }
    }
  }

  @Override
  public void onBytesTransferred(
      DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
    if (delegateTransferListener != null) {
      delegateTransferListener.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred);
    }
    if (throughputEstimateStopped || !isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    @Nullable String serviceLocation = dataSpec.location;
    if (serviceLocation != null) {
      @Nullable BandwidthEstimator estimator = estimatorsByServiceLocation.get(serviceLocation);
      if (estimator != null) {
        synchronized (estimator) {
          estimator.onBytesTransferred(source, bytesTransferred);
        }
      }
    }
  }

  @Override
  public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (delegateTransferListener != null) {
      delegateTransferListener.onTransferEnd(source, dataSpec, isNetwork);
    }
    if (throughputEstimateStopped || !isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    @Nullable String serviceLocation = dataSpec.location;
    if (serviceLocation != null) {
      @Nullable BandwidthEstimator estimator = estimatorsByServiceLocation.get(serviceLocation);
      if (estimator != null) {
        synchronized (estimator) {
          estimator.onTransferEnd(source);
        }
      }
    }
  }

  private static boolean isTransferAtFullNetworkSpeed(DataSpec dataSpec, boolean isNetwork) {
    return isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED);
  }
}
