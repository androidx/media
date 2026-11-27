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
package androidx.media3.exoplayer.upstream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.nullToEmpty;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.NetworkTypeObserver;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener.EventDispatcher;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Estimates bandwidth by listening to data transfers.
 *
 * <p>The bandwidth estimate is calculated using a {@link SlidingPercentile} and is updated each
 * time a transfer ends. The initial estimate is based on the current operator's network country
 * code or the locale of the user, as well as the network connection type. This can be configured in
 * the {@link Builder}.
 */
@UnstableApi
public final class DefaultBandwidthMeter implements BandwidthMeter, TransferListener {

  /** Default initial Wifi bitrate estimate in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI =
      ImmutableList.of(4_300_000L, 3_200_000L, 2_400_000L, 1_700_000L, 860_000L);

  /** Default initial 2G bitrate estimates in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_2G =
      ImmutableList.of(1_500_000L, 980_000L, 750_000L, 520_000L, 290_000L);

  /** Default initial 3G bitrate estimates in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_3G =
      ImmutableList.of(2_000_000L, 1_300_000L, 1_000_000L, 860_000L, 610_000L);

  /** Default initial 4G bitrate estimates in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_4G =
      ImmutableList.of(2_500_000L, 1_700_000L, 1_200_000L, 970_000L, 680_000L);

  /** Default initial 5G-NSA bitrate estimates in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA =
      ImmutableList.of(4_700_000L, 2_800_000L, 2_100_000L, 1_700_000L, 980_000L);

  /** Default initial 5G-SA bitrate estimates in bits per second. */
  public static final ImmutableList<Long> DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA =
      ImmutableList.of(2_700_000L, 2_000_000L, 1_600_000L, 1_300_000L, 1_000_000L);

  /**
   * Default initial bitrate estimate used when the device is offline or the network type cannot be
   * determined, in bits per second.
   */
  public static final long DEFAULT_INITIAL_BITRATE_ESTIMATE = 1_000_000;

  /** Default maximum weight for the sliding window. */
  public static final int DEFAULT_SLIDING_WINDOW_MAX_WEIGHT = 2000;

  /**
   * Shift for the 2G group index in the packed int returned by {@link
   * #getInitialBitrateCountryGroupAssignment}.
   */
  private static final int COUNTRY_GROUP_SHIFT_2G = 3;

  /**
   * Shift for the 3G group index in the packed int returned by {@link
   * #getInitialBitrateCountryGroupAssignment}.
   */
  private static final int COUNTRY_GROUP_SHIFT_3G = 6;

  /**
   * Shift for the 4G group index in the packed int returned by {@link
   * #getInitialBitrateCountryGroupAssignment}.
   */
  private static final int COUNTRY_GROUP_SHIFT_4G = 9;

  /**
   * Shift for the 5G-NSA group index in the packed int returned by {@link
   * #getInitialBitrateCountryGroupAssignment}.
   */
  private static final int COUNTRY_GROUP_SHIFT_5G_NSA = 12;

  /**
   * Shift for the 5G-SA group index in the packed int returned by {@link
   * #getInitialBitrateCountryGroupAssignment}.
   */
  private static final int COUNTRY_GROUP_SHIFT_5G_SA = 15;

  /** Mask to extract the 3-bit country group index. */
  private static final int COUNTRY_GROUP_INDEX_MASK = 0x7;

  // Intentionally mutable static field and using application context to prevent leakage.
  @SuppressLint({"NonFinalStaticField", "StaticFieldLeak"})
  @Nullable
  private static DefaultBandwidthMeter singletonInstance;

  /**
   * Supplies an initial bitrate estimate to the {@link DefaultBandwidthMeter}.
   *
   * <p>This interface allows applications to provide custom logic for determining the initial
   * bitrate when a bandwidth estimate is unavailable.
   */
  public interface InitialBitrateSupplier {
    /**
     * Returns an initial bitrate estimate in bits per second (bps) for the given network type.
     *
     * <p>Implementations can return a specific value based on the {@code networkType}. If the
     * supplier doesn't have a specific estimate for the given {@code networkType}, it should return
     * {@link C#TIME_UNSET}. In this case, {@link DefaultBandwidthMeter} will fall back to its
     * internal default logic for that network type.
     *
     * @param networkType The current {@link C.NetworkType}.
     * @return The initial bitrate estimate in bps, or {@link C#TIME_UNSET} to use the default
     *     fallback.
     */
    long getInitialBitrateEstimate(@C.NetworkType int networkType);
  }

  /** Builder for a bandwidth meter. */
  public static final class Builder {

    @Nullable private final Context context;
    private final Map<Integer, Long> initialBitrateEstimates;

    private int slidingWindowMaxWeight;
    private Clock clock;
    private boolean resetOnNetworkTypeChange;
    @Nullable private InitialBitrateSupplier initialBitrateSupplier;

    /**
     * Creates a builder with default parameters and without listener.
     *
     * @param context A context.
     */
    public Builder(Context context) {
      // Handling of null is for backward compatibility only.
      this.context = context == null ? null : context.getApplicationContext();
      slidingWindowMaxWeight = DEFAULT_SLIDING_WINDOW_MAX_WEIGHT;
      clock = Clock.DEFAULT;
      resetOnNetworkTypeChange = true;
      initialBitrateEstimates = new HashMap<>(/* initialCapacity= */ 8);
      initialBitrateEstimates.put(C.NETWORK_TYPE_UNKNOWN, DEFAULT_INITIAL_BITRATE_ESTIMATE);
      initialBitrateEstimates.put(C.NETWORK_TYPE_WIFI, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_2G, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_3G, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_4G, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_5G_NSA, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_5G_SA, C.TIME_UNSET);
      initialBitrateEstimates.put(C.NETWORK_TYPE_ETHERNET, C.TIME_UNSET);
    }

    /**
     * Sets the maximum weight for the sliding window.
     *
     * @param slidingWindowMaxWeight The maximum weight for the sliding window.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSlidingWindowMaxWeight(int slidingWindowMaxWeight) {
      this.slidingWindowMaxWeight = slidingWindowMaxWeight;
      return this;
    }

    /**
     * Sets the initial bitrate estimate in bits per second that should be assumed when a bandwidth
     * estimate is unavailable.
     *
     * @param initialBitrateEstimate The initial bitrate estimate in bits per second.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setInitialBitrateEstimate(long initialBitrateEstimate) {
      for (Integer networkType : initialBitrateEstimates.keySet()) {
        setInitialBitrateEstimate(networkType, initialBitrateEstimate);
      }
      return this;
    }

    /**
     * Sets the initial bitrate estimate in bits per second that should be assumed when a bandwidth
     * estimate is unavailable and the current network connection is of the specified type.
     *
     * @param networkType The {@link C.NetworkType} this initial estimate is for.
     * @param initialBitrateEstimate The initial bitrate estimate in bits per second.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setInitialBitrateEstimate(
        @C.NetworkType int networkType, long initialBitrateEstimate) {
      initialBitrateEstimates.put(networkType, initialBitrateEstimate);
      return this;
    }

    /**
     * Sets the initial bitrate estimates to the default values of the specified country. The
     * initial estimates are used when a bandwidth estimate is unavailable.
     *
     * @param countryCode The ISO 3166-1 alpha-2 country code of the country whose default bitrate
     *     estimates should be used.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setInitialBitrateEstimate(String countryCode) {
      countryCode = Ascii.toUpperCase(countryCode);
      for (Integer networkType : initialBitrateEstimates.keySet()) {
        setInitialBitrateEstimate(
            networkType, getInitialBitrateEstimatesForCountry(countryCode, networkType));
      }
      return this;
    }

    /**
     * Sets the clock used to estimate bandwidth from data transfers. Should only be set for testing
     * purposes.
     *
     * @param clock The clock used to estimate bandwidth from data transfers.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Sets whether to reset if the network type changes. The default value is {@code true}.
     *
     * @param resetOnNetworkTypeChange Whether to reset if the network type changes.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setResetOnNetworkTypeChange(boolean resetOnNetworkTypeChange) {
      this.resetOnNetworkTypeChange = resetOnNetworkTypeChange;
      return this;
    }

    /**
     * Sets the {@link InitialBitrateSupplier} to use for obtaining the initial bitrate estimate
     * when a bandwidth estimate is unavailable.
     *
     * <p>By default, no supplier is set and the meter falls back to its internal default logic.
     *
     * @param initialBitrateSupplier The {@link InitialBitrateSupplier}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setInitialBitrateSupplier(InitialBitrateSupplier initialBitrateSupplier) {
      this.initialBitrateSupplier = initialBitrateSupplier;
      return this;
    }

    /**
     * Builds the bandwidth meter.
     *
     * @return A bandwidth meter with the configured properties.
     */
    public DefaultBandwidthMeter build() {
      return new DefaultBandwidthMeter(
          context,
          initialBitrateEstimates,
          slidingWindowMaxWeight,
          clock,
          resetOnNetworkTypeChange,
          initialBitrateSupplier);
    }
  }

  /**
   * Returns a singleton instance of a {@link DefaultBandwidthMeter} with default configuration.
   *
   * @param context A {@link Context}.
   * @return The singleton instance.
   */
  public static synchronized DefaultBandwidthMeter getSingletonInstance(Context context) {
    if (singletonInstance == null) {
      singletonInstance = new DefaultBandwidthMeter.Builder(context).build();
    }
    return singletonInstance;
  }

  private static final int ELAPSED_MILLIS_FOR_ESTIMATE = 2000;
  private static final int BYTES_TRANSFERRED_FOR_ESTIMATE = 512 * 1024;

  @Nullable private final Context context;
  private final ImmutableMap<Integer, Long> initialBitrateEstimates;
  private final EventDispatcher eventDispatcher;
  private final Clock clock;
  private final boolean resetOnNetworkTypeChange;
  @Nullable private final InitialBitrateSupplier initialBitrateSupplier;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private final SlidingPercentile slidingPercentile;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private int streamCount;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long sampleStartTimeMs;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long sampleBytesTransferred;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long totalElapsedTimeMs;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long totalBytesTransferred;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long bitrateEstimate;

  @GuardedBy("this") // Used in TransferListener methods that are called on a background thread.
  private long lastReportedBitrateEstimate;

  private @C.NetworkType int networkType;
  private boolean networkTypeOverrideSet;
  private @C.NetworkType int networkTypeOverride;
  private @MonotonicNonNull String countryCode;

  private DefaultBandwidthMeter(
      @Nullable Context context,
      Map<Integer, Long> initialBitrateEstimates,
      int maxWeight,
      Clock clock,
      boolean resetOnNetworkTypeChange,
      @Nullable InitialBitrateSupplier initialBitrateSupplier) {
    this.context = context == null ? null : context.getApplicationContext();
    this.initialBitrateEstimates = ImmutableMap.copyOf(initialBitrateEstimates);
    this.eventDispatcher = new EventDispatcher();
    this.slidingPercentile = new SlidingPercentile(maxWeight);
    this.clock = clock;
    this.resetOnNetworkTypeChange = resetOnNetworkTypeChange;
    this.initialBitrateSupplier = initialBitrateSupplier;
    if (context != null) {
      NetworkTypeObserver networkTypeObserver = NetworkTypeObserver.getInstance(context);
      networkType = networkTypeObserver.getNetworkType();
      bitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType);
      networkTypeObserver.register(
          /* listener= */ this::onNetworkTypeChanged, BackgroundExecutor.get());
    } else {
      networkType = C.NETWORK_TYPE_UNKNOWN;
      bitrateEstimate = DEFAULT_INITIAL_BITRATE_ESTIMATE;
    }
  }

  /**
   * Overrides the network type. Handled in the same way as if the meter had detected a change from
   * the current network type to the specified network type internally.
   *
   * <p>Applications should not normally call this method. It is intended for testing purposes.
   *
   * @param networkType The overriding network type.
   */
  public synchronized void setNetworkTypeOverride(@C.NetworkType int networkType) {
    networkTypeOverride = networkType;
    networkTypeOverrideSet = true;
    onNetworkTypeChanged(networkType);
  }

  @Override
  public synchronized long getBitrateEstimate() {
    return bitrateEstimate;
  }

  @Override
  public TransferListener getTransferListener() {
    return this;
  }

  @Override
  public void addEventListener(Handler eventHandler, EventListener eventListener) {
    checkNotNull(eventHandler);
    checkNotNull(eventListener);
    eventDispatcher.addListener(eventHandler, eventListener);
  }

  @Override
  public void removeEventListener(EventListener eventListener) {
    eventDispatcher.removeListener(eventListener);
  }

  @Override
  public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    // Do nothing.
  }

  @Override
  public synchronized void onTransferStart(
      DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    if (streamCount == 0) {
      sampleStartTimeMs = clock.elapsedRealtime();
    }
    streamCount++;
  }

  @Override
  public synchronized void onBytesTransferred(
      DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
    if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    sampleBytesTransferred += bytesTransferred;
  }

  @Override
  public synchronized void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
    if (!isTransferAtFullNetworkSpeed(dataSpec, isNetwork)) {
      return;
    }
    checkState(streamCount > 0);
    long nowMs = clock.elapsedRealtime();
    int sampleElapsedTimeMs = (int) (nowMs - sampleStartTimeMs);
    totalElapsedTimeMs += sampleElapsedTimeMs;
    totalBytesTransferred += sampleBytesTransferred;
    if (sampleElapsedTimeMs > 0) {
      float bitsPerSecond = (sampleBytesTransferred * 8000f) / sampleElapsedTimeMs;
      slidingPercentile.addSample((int) Math.sqrt(sampleBytesTransferred), bitsPerSecond);
      if (totalElapsedTimeMs >= ELAPSED_MILLIS_FOR_ESTIMATE
          || totalBytesTransferred >= BYTES_TRANSFERRED_FOR_ESTIMATE) {
        bitrateEstimate = (long) slidingPercentile.getPercentile(0.5f);
      }
      maybeNotifyBandwidthSample(sampleElapsedTimeMs, sampleBytesTransferred, bitrateEstimate);
      sampleStartTimeMs = nowMs;
      sampleBytesTransferred = 0;
    } // Else any sample bytes transferred will be carried forward into the next sample.
    streamCount--;
  }

  private synchronized void onNetworkTypeChanged(@C.NetworkType int networkType) {
    if (this.networkType != C.NETWORK_TYPE_UNKNOWN && !resetOnNetworkTypeChange) {
      // Reset on network change disabled. Ignore all updates except the initial one.
      return;
    }

    if (networkTypeOverrideSet) {
      networkType = networkTypeOverride;
    }
    if (this.networkType == networkType && countryCode != null) {
      return;
    }

    this.networkType = networkType;
    if (networkType == C.NETWORK_TYPE_OFFLINE
        || networkType == C.NETWORK_TYPE_UNKNOWN
        || networkType == C.NETWORK_TYPE_OTHER) {
      // It's better not to reset the bandwidth meter for these network types.
      return;
    }

    if (countryCode == null) {
      countryCode = Util.getCountryCode(context);
    }

    // Reset the bitrate estimate and report it, along with any bytes transferred.
    this.bitrateEstimate = getInitialBitrateEstimateForNetworkType(networkType);
    long nowMs = clock.elapsedRealtime();
    int sampleElapsedTimeMs = streamCount > 0 ? (int) (nowMs - sampleStartTimeMs) : 0;
    maybeNotifyBandwidthSample(sampleElapsedTimeMs, sampleBytesTransferred, bitrateEstimate);

    // Reset the remainder of the state.
    sampleStartTimeMs = nowMs;
    sampleBytesTransferred = 0;
    totalBytesTransferred = 0;
    totalElapsedTimeMs = 0;
    slidingPercentile.reset();
  }

  @GuardedBy("this")
  private void maybeNotifyBandwidthSample(
      int elapsedMs, long bytesTransferred, long bitrateEstimate) {
    if (elapsedMs == 0 && bytesTransferred == 0 && bitrateEstimate == lastReportedBitrateEstimate) {
      return;
    }
    lastReportedBitrateEstimate = bitrateEstimate;
    eventDispatcher.bandwidthSample(elapsedMs, bytesTransferred, bitrateEstimate);
  }

  private long getInitialBitrateEstimateForNetworkType(@C.NetworkType int networkType) {
    if (initialBitrateSupplier != null) {
      long supplierEstimate = initialBitrateSupplier.getInitialBitrateEstimate(networkType);
      if (supplierEstimate != C.TIME_UNSET) {
        return supplierEstimate;
      }
    }
    Long initialBitrateEstimate = initialBitrateEstimates.get(networkType);
    if (initialBitrateEstimate == null) {
      initialBitrateEstimate = initialBitrateEstimates.get(C.NETWORK_TYPE_UNKNOWN);
    } else if (initialBitrateEstimate == C.TIME_UNSET) {
      initialBitrateEstimate = getInitialBitrateEstimatesForCountry(countryCode, networkType);
    }
    if (initialBitrateEstimate == null) {
      initialBitrateEstimate = DEFAULT_INITIAL_BITRATE_ESTIMATE;
    }
    return initialBitrateEstimate;
  }

  private static boolean isTransferAtFullNetworkSpeed(DataSpec dataSpec, boolean isNetwork) {
    return isNetwork && !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED);
  }

  private static long getInitialBitrateEstimatesForCountry(
      @Nullable String countryCode, @C.NetworkType int networkType) {
    int packedGroupIndices = getInitialBitrateCountryGroupAssignment(nullToEmpty(countryCode));
    switch (networkType) {
      case C.NETWORK_TYPE_WIFI:
      case C.NETWORK_TYPE_ETHERNET:
        // Assume default Wifi speed for Ethernet to prevent using the slower fallback.
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI.get(
            packedGroupIndices & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_2G:
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_2G.get(
            (packedGroupIndices >> COUNTRY_GROUP_SHIFT_2G) & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_3G:
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_3G.get(
            (packedGroupIndices >> COUNTRY_GROUP_SHIFT_3G) & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_4G:
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_4G.get(
            (packedGroupIndices >> COUNTRY_GROUP_SHIFT_4G) & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_5G_NSA:
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA.get(
            (packedGroupIndices >> COUNTRY_GROUP_SHIFT_5G_NSA) & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_5G_SA:
        return DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA.get(
            (packedGroupIndices >> COUNTRY_GROUP_SHIFT_5G_SA) & COUNTRY_GROUP_INDEX_MASK);
      case C.NETWORK_TYPE_UNKNOWN:
      default:
        return DEFAULT_INITIAL_BITRATE_ESTIMATE;
    }
  }

  /**
   * Returns initial bitrate group assignments for a {@code country} packed into a 32-bit integer.
   * The packed integer assigns 3 bits for each network type's group index in the following layout:
   * [Wifi (bits 0-2), 2G (bits 3-5), 3G (bits 6-8), 4G (bits 9-11), 5G_NSA (bits 12-14), 5G_SA
   * (bits 15-17)].
   */
  private static int getInitialBitrateCountryGroupAssignment(String country) {
    switch (country) {
      case "AE":
        return 1
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AL":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AO":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AR":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AS":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AU":
        return 0
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AW":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BD":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BE":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BH":
        return 1
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BJ":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BM":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BN":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BO":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BR":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BS":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BT":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BW":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BY":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CF":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CH":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AG":
      case "CI":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BZ":
      case "CK":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CN":
        return 2
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CO":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CV":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CY":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CZ":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "DE":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "DK":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "EC":
        return 1
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ES":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ET":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FI":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FJ":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FM":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FO":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FR":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GA":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GB":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GD":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GE":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GF":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GG":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GH":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GN":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GP":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GR":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GT":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GU":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GW":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GY":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "HK":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ID":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IE":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IL":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IN":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (3 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IO":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IQ":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IR":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (3 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "IT":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GI":
      case "IM":
      case "JE":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "JM":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "JP":
        return 0
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KE":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KG":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KH":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KR":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "HR":
      case "KW":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KZ":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LA":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LB":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LC":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "DO":
      case "LR":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LT":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LU":
        return 4
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (3 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MA":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GL":
      case "MC":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MD":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ME":
        return 2
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MF":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CG":
      case "EG":
      case "MG":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MK":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CD":
      case "ML":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LK":
      case "MM":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MN":
        return 2
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MO":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MQ":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CM":
      case "MR":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MU":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MV":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MW":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MX":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MY":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NA":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NG":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CR":
      case "NI":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NL":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NO":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NP":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NZ":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "OM":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AM":
      case "PA":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PE":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PF":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "LS":
      case "PG":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PH":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PK":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PL":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PR":
        return 2
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PS":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "PW":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BL":
      case "MP":
      case "PY":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "QA":
        return 1
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "RE":
        return 0
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "RO":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "RS":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "RU":
        return 1
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (3 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "RW":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SA":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AT":
      case "EE":
      case "HU":
      case "IS":
      case "LV":
      case "MT":
      case "SE":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SG":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AQ":
      case "ER":
      case "NU":
      case "SC":
      case "SH":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BG":
      case "PT":
      case "SI":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "FK":
      case "NF":
      case "SJ":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SK":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AZ":
      case "DJ":
      case "LY":
      case "SL":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SN":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SO":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SR":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "GM":
      case "SS":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ST":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SV":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AF":
      case "SZ":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TC":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BF":
      case "SD":
      case "SY":
      case "TD":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TG":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CL":
      case "TH":
        return 0
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "DZ":
      case "TJ":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CU":
      case "KI":
      case "NR":
      case "TL":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TN":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TO":
        return 3
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BA":
      case "JO":
      case "TR":
        return 1
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TT":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TW":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (0 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (0 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "TZ":
        return 3
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "CA":
      case "UA":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (3 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "UG":
        return 3
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (4 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "US":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (1 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "UY":
        return 2
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "UZ":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (3 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AX":
      case "CX":
      case "LI":
      case "MS":
      case "PM":
      case "SM":
      case "VA":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "AD":
      case "AI":
      case "BB":
      case "BQ":
      case "CW":
      case "DM":
      case "KN":
      case "KY":
      case "SX":
      case "VC":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (0 << COUNTRY_GROUP_SHIFT_3G)
            | (0 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "VG":
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (4 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "VI":
        return 0
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "VN":
        return 0
            | (0 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "KM":
      case "VU":
        return 4
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MH":
      case "TM":
      case "TV":
      case "WF":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "MZ":
      case "WS":
        return 3
            | (1 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "XK":
        return 1
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (1 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "BI":
      case "GQ":
      case "HT":
      case "NE":
      case "VE":
      case "YE":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "NC":
      case "YT":
        return 2
            | (3 << COUNTRY_GROUP_SHIFT_2G)
            | (3 << COUNTRY_GROUP_SHIFT_3G)
            | (4 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ZA":
        return 2
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (1 << COUNTRY_GROUP_SHIFT_4G)
            | (1 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "ZM":
        return 4
            | (4 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      case "SB":
      case "ZW":
        return 4
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (4 << COUNTRY_GROUP_SHIFT_3G)
            | (3 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
      default:
        return 2
            | (2 << COUNTRY_GROUP_SHIFT_2G)
            | (2 << COUNTRY_GROUP_SHIFT_3G)
            | (2 << COUNTRY_GROUP_SHIFT_4G)
            | (2 << COUNTRY_GROUP_SHIFT_5G_NSA)
            | (2 << COUNTRY_GROUP_SHIFT_5G_SA);
    }
  }
}
