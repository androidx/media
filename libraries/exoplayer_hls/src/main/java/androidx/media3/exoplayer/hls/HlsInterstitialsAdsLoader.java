/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.common.AdPlaybackState.AD_STATE_AVAILABLE;
import static androidx.media3.common.AdPlaybackState.AD_STATE_ERROR;
import static androidx.media3.common.AdPlaybackState.AD_STATE_PLAYED;
import static androidx.media3.common.AdPlaybackState.AD_STATE_SKIPPED;
import static androidx.media3.common.AdPlaybackState.AD_STATE_UNAVAILABLE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP;
import static androidx.media3.common.Player.STATE_IDLE;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_POST;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.CUE_TRIGGER_PRE;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.SNAP_TYPE_IN;
import static androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial.SNAP_TYPE_OUT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.abs;
import static java.lang.Math.max;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdPlaybackState.AdGroup;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.AdsConfiguration;
import androidx.media3.common.MediaItem.LocalConfiguration;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Period;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist.Interstitial;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * An {@linkplain AdsLoader ads loader} that reads interstitials from the HLS playlist, adds them to
 * the {@link AdPlaybackState} and passes the ad playback state to {@link
 * EventListener#onAdPlaybackState(AdPlaybackState)}.
 *
 * <p>An ads ID must be unique within the playlist of ExoPlayer. If this is the case, a single
 * {@link HlsInterstitialsAdsLoader} instance can be passed to multiple {@linkplain AdsMediaSource
 * ads media sources}. These ad media source can be added to the same playlist as far as each of the
 * sources have a different ads IDs.
 */
@SuppressWarnings({"PatternMatchingInstanceof", "EffectivelyPrivate"})
@UnstableApi
public final class HlsInterstitialsAdsLoader implements AdsLoader {

  /** Holds a list of {@linkplain Asset assets}. */
  public static final class AssetList {

    /* package */ static final AssetList EMPTY =
        new AssetList(ImmutableList.of(), ImmutableList.of(), /* skipInfo= */ null);

    /** The list of assets. */
    public final ImmutableList<Asset> assets;

    /** The list of string attributes of the asset list JSON object. */
    public final ImmutableList<StringAttribute> stringAttributes;

    /** The skip control information, or {@code null} if not specified. */
    @Nullable public final SkipInfo skipInfo;

    /** Creates an instance. */
    /* package */ AssetList(
        ImmutableList<Asset> assets,
        ImmutableList<StringAttribute> stringAttributes,
        @Nullable SkipInfo skipInfo) {
      this.assets = assets;
      this.stringAttributes = stringAttributes;
      this.skipInfo = skipInfo;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AssetList)) {
        return false;
      }
      AssetList assetList = (AssetList) o;
      return Objects.equals(assets, assetList.assets)
          && Objects.equals(stringAttributes, assetList.stringAttributes)
          && Objects.equals(skipInfo, assetList.skipInfo);
    }

    @Override
    public int hashCode() {
      return Objects.hash(assets, stringAttributes, skipInfo);
    }
  }

  /**
   * An asset with a URI and a duration.
   *
   * <p>See RFC 8216bis, appendix D.2, X-ASSET-LIST.
   */
  public static final class Asset {

    /** A uri to an HLS source. */
    public final Uri uri;

    /** The duration, in microseconds. */
    public final long durationUs;

    /** Creates an instance. */
    /* package */ Asset(Uri uri, long durationUs) {
      this.uri = uri;
      this.durationUs = durationUs;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Asset)) {
        return false;
      }
      Asset asset = (Asset) o;
      return durationUs == asset.durationUs && Objects.equals(uri, asset.uri);
    }

    @Override
    public int hashCode() {
      return Objects.hash(uri, durationUs);
    }
  }

  /** A string attribute with its name and value. */
  public static final class StringAttribute {

    /** The name of the attribute. */
    public final String name;

    /** The value of the attribute. */
    public final String value;

    /** Creates an instance. */
    /* package */ StringAttribute(String name, String value) {
      this.name = name;
      this.value = value;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof StringAttribute)) {
        return false;
      }
      StringAttribute that = (StringAttribute) o;
      return Objects.equals(name, that.name) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, value);
    }
  }

  /**
   * The state of the given ads ID to resume playback at the given {@link AdPlaybackState}.
   *
   * <p>This state object can be bundled and unbundled while preserving an {@link
   * AdPlaybackState#adsId ads ID} of type {@link String}.
   */
  public static class AdsResumptionState {

    private final AdPlaybackState adPlaybackState;

    /** The ads ID */
    public final String adsId;

    /**
     * Creates a new instance.
     *
     * @param adsId The ads ID of the playback state.
     * @param adPlaybackState The {@link AdPlaybackState} with the given {@code adsId}.
     * @throws IllegalArgumentException Thrown if the passed in adsId is not equal to {@link
     *     AdPlaybackState#adsId}.
     */
    public AdsResumptionState(String adsId, AdPlaybackState adPlaybackState) {
      checkArgument(adsId.equals(adPlaybackState.adsId));
      this.adsId = adsId;
      this.adPlaybackState = adPlaybackState;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof AdsResumptionState)) {
        return false;
      }
      AdsResumptionState adsResumptionState = (AdsResumptionState) o;
      return Objects.equals(adsId, adsResumptionState.adsId)
          && Objects.equals(adPlaybackState, adsResumptionState.adPlaybackState);
    }

    @Override
    public int hashCode() {
      return Objects.hash(adsId, adPlaybackState);
    }

    private static final String FIELD_ADS_ID = Util.intToStringMaxRadix(0);
    private static final String FIELD_AD_PLAYBACK_STATE = Util.intToStringMaxRadix(1);

    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putString(FIELD_ADS_ID, adsId);
      bundle.putBundle(FIELD_AD_PLAYBACK_STATE, adPlaybackState.toBundle());
      return bundle;
    }

    public static AdsResumptionState fromBundle(Bundle bundle) {
      String adsId = checkNotNull(bundle.getString(FIELD_ADS_ID));
      AdPlaybackState adPlaybackState =
          AdPlaybackState.fromBundle(checkNotNull(bundle.getBundle(FIELD_AD_PLAYBACK_STATE)))
              .withAdsId(adsId);
      return new AdsResumptionState(adsId, adPlaybackState);
    }
  }

  /**
   * A {@link MediaSource.Factory} to create a media source to play HLS streams with interstitials.
   */
  public static final class AdsMediaSourceFactory implements MediaSource.Factory {

    private final MediaSource.Factory mediaSourceFactory;
    private final AdViewProvider adViewProvider;
    private final HlsInterstitialsAdsLoader adsLoader;

    /**
     * Creates an instance with a {@link DefaultMediaSourceFactory}.
     *
     * @param adsLoader The {@link HlsInterstitialsAdsLoader}.
     * @param adViewProvider Provider of views for the ad UI.
     * @param context The {@link Context}.
     */
    public AdsMediaSourceFactory(
        HlsInterstitialsAdsLoader adsLoader, AdViewProvider adViewProvider, Context context) {
      this(adsLoader, context, /* mediaSourceFactory= */ null, adViewProvider);
    }

    /**
     * Creates an instance with a custom {@link MediaSource.Factory}.
     *
     * @param adsLoader The {@link HlsInterstitialsAdsLoader}.
     * @param adViewProvider Provider of views for the ad UI.
     * @param mediaSourceFactory The {@link MediaSource.Factory} used to create content and ad media
     *     sources.
     * @throws IllegalStateException If the provided {@linkplain MediaSource.Factory media source
     *     factory} doesn't support content type {@link C#CONTENT_TYPE_HLS}.
     */
    public AdsMediaSourceFactory(
        HlsInterstitialsAdsLoader adsLoader,
        AdViewProvider adViewProvider,
        MediaSource.Factory mediaSourceFactory) {
      this(adsLoader, /* context= */ null, mediaSourceFactory, adViewProvider);
    }

    private AdsMediaSourceFactory(
        HlsInterstitialsAdsLoader adsLoader,
        @Nullable Context context,
        @Nullable MediaSource.Factory mediaSourceFactory,
        AdViewProvider adViewProvider) {
      checkArgument(context != null || mediaSourceFactory != null);
      this.adsLoader = adsLoader;
      this.mediaSourceFactory =
          mediaSourceFactory != null
              ? mediaSourceFactory
              : new HlsMediaSource.Factory(new DefaultDataSource.Factory(checkNotNull(context)));
      this.adViewProvider = adViewProvider;
      boolean supportsHls = false;
      for (int supportedType : this.mediaSourceFactory.getSupportedTypes()) {
        if (supportedType == C.CONTENT_TYPE_HLS) {
          supportsHls = true;
          break;
        }
      }
      checkState(supportsHls);
    }

    @Override
    public @C.ContentType int[] getSupportedTypes() {
      return new int[] {C.CONTENT_TYPE_HLS};
    }

    @Override
    @CanIgnoreReturnValue
    public AdsMediaSourceFactory setDrmSessionManagerProvider(
        DrmSessionManagerProvider drmSessionManagerProvider) {
      mediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
      return this;
    }

    @Override
    @CanIgnoreReturnValue
    public AdsMediaSourceFactory setLoadErrorHandlingPolicy(
        LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      mediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
      return this;
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
      checkNotNull(mediaItem.localConfiguration);
      MediaSource contentMediaSource = mediaSourceFactory.createMediaSource(mediaItem);
      if (mediaItem.localConfiguration.adsConfiguration == null) {
        return contentMediaSource;
      } else if (!(mediaItem.localConfiguration.adsConfiguration.adsId instanceof String)) {
        throw new IllegalArgumentException(
            "Please use an AdsConfiguration with an adsId of type String when using"
                + " HlsInterstitialsAdsLoader");
      }
      return new AdsMediaSource(
          contentMediaSource,
          new DataSpec(mediaItem.localConfiguration.adsConfiguration.adTagUri), // unused
          checkNotNull(mediaItem.localConfiguration.adsConfiguration.adsId),
          mediaSourceFactory,
          adsLoader,
          adViewProvider,
          /* useLazyContentSourcePreparation= */ false);
    }
  }

  /** A listener to be notified of events emitted by the ads loader. */
  public interface Listener {

    /**
     * Called when the ads loader was started for the given HLS media item and ads ID.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adViewProvider {@linkplain AdViewProvider Provider} of views for the ad UI.
     */
    default void onStart(MediaItem mediaItem, Object adsId, AdViewProvider adViewProvider) {
      // Do nothing.
    }

    /**
     * Called when the timeline of the content media source has changed. The {@link HlsManifest} of
     * the content source can be accessed through {@link Window#manifest}.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param hlsContentTimeline The latest {@link Timeline}.
     */
    default void onContentTimelineChanged(
        MediaItem mediaItem, Object adsId, Timeline hlsContentTimeline) {
      // Do nothing.
    }

    /**
     * Called when the asset list has been started to load for the given ad.
     *
     * @param mediaItem The {@link MediaItem} with which the {@linkplain MediaSource content media
     *     source} was created.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group of the ad period.
     * @param adIndexInAdGroup The index of the ad in the ad group of the ad period.
     */
    default void onAssetListLoadStarted(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      // Do nothing.
    }

    /**
     * Called when an asset list has completed to load for the given ad.
     *
     * @param mediaItem The {@link MediaItem} with which the {@linkplain MediaSource content media
     *     source} was created.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group of the ad period.
     * @param adIndexInAdGroup The index of the ad in the ad group of the ad period.
     * @param assetList The {@link AssetList} for which loading has completed.
     */
    default void onAssetListLoadCompleted(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        AssetList assetList) {
      // Do nothing.
    }

    /**
     * Called when an asset list has failed to load for the given ad.
     *
     * @param mediaItem The {@link MediaItem} with which the {@linkplain MediaSource content media
     *     source} was created.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group of the ad period.
     * @param adIndexInAdGroup The index of the ad in the ad group of the ad period.
     * @param ioException The exception, may be null if cancelled.
     * @param cancelled Whether the load was cancelled.
     */
    default void onAssetListLoadFailed(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        @Nullable IOException ioException,
        boolean cancelled) {
      // Do nothing.
    }

    /**
     * Called when preparation of an ad period has completed successfully.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group in the ad media source.
     * @param adIndexInAdGroup The index of the ad in the ad group.
     */
    default void onPrepareCompleted(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      // Do nothing.
    }

    /**
     * Called when preparation of an ad period failed.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group in the ad media source.
     * @param adIndexInAdGroup The index of the ad in the ad group.
     * @param exception The {@link IOException} thrown when preparing.
     */
    default void onPrepareError(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        IOException exception) {
      // Do nothing.
    }

    /**
     * Called when {@link Metadata} is emitted by the player during an ad period of an active HLS
     * media item.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group in the ad media source.
     * @param adIndexInAdGroup The index of the ad in the ad group.
     * @param metadata The emitted {@link Metadata}.
     */
    default void onMetadata(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        Metadata metadata) {
      // Do nothing.
    }

    /**
     * Called when an ad period has completed playback and transitioned to the following ad or
     * content period, or the playlist ended.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adGroupIndex The index of the ad group in the ad media source.
     * @param adIndexInAdGroup The index of the ad in the ad group.
     */
    default void onAdCompleted(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      // Do nothing.
    }

    /**
     * Called when the ads loader was stopped for the given HLS media item.
     *
     * @param mediaItem The {@link MediaItem} of the content media source.
     * @param adsId The ads identifier (see {@link AdsConfiguration#adsId}).
     * @param adPlaybackState The {@link AdPlaybackState} after the ad media source was released.
     */
    default void onStop(MediaItem mediaItem, Object adsId, AdPlaybackState adPlaybackState) {
      // Do nothing.
    }
  }

  private static final String TAG = "HlsInterstitiaAdsLoader";

  private final DataSource.Factory dataSourceFactory;
  private final PlayerListener playerListener;
  private final ContentMediaSourceAdDataHolder contentMediaSourceAdDataHolder;
  private final Map<Object, AdPlaybackState> resumptionStates;
  private final List<Listener> listeners;

  @Nullable private ExoPlayer player;
  @Nullable private Loader loader;
  private boolean isReleased;
  @Nullable private PlayerMessage pendingAssetListResolutionMessage;

  /**
   * Creates an instance with a {@link DefaultDataSource.Factory} to read HLS X-ASSET-LIST JSON
   * objects.
   *
   * @param context The context.
   */
  public HlsInterstitialsAdsLoader(Context context) {
    this(new DefaultDataSource.Factory(context));
  }

  /**
   * Creates an instance.
   *
   * @param dataSourceFactory The data source factory to read HLS X-ASSET-LIST JSON objects.
   */
  public HlsInterstitialsAdsLoader(DataSource.Factory dataSourceFactory) {
    this.dataSourceFactory = dataSourceFactory;
    playerListener = new PlayerListener();
    contentMediaSourceAdDataHolder = new ContentMediaSourceAdDataHolder();
    resumptionStates = new HashMap<>();
    listeners = new ArrayList<>();
  }

  /** Adds a {@link Listener}. */
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /** Removes a {@link Listener}. */
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  // Implementation of AdsLoader methods

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException If an app is attempting to set a new player instance after {@link
   *     #release} was called or while {@linkplain AdsMediaSource ads media sources} started by the
   *     old player are still active, an {@link IllegalStateException} is thrown. Release the old
   *     player first, or remove all ads media sources from it before setting another player
   *     instance.
   */
  @Override
  public void setPlayer(@Nullable Player player) {
    checkState(!isReleased);
    checkArgument(player == null || player instanceof ExoPlayer);
    if (Objects.equals(this.player, player)) {
      return;
    }
    @Nullable Player localPlayer = this.player;
    if (localPlayer != null && !contentMediaSourceAdDataHolder.isIdle()) {
      localPlayer.removeListener(playerListener);
    }
    checkState(player == null || contentMediaSourceAdDataHolder.isIdle());
    this.player = (ExoPlayer) player;
  }

  @Override
  public void setSupportedContentTypes(@C.ContentType int... contentTypes) {
    for (int contentType : contentTypes) {
      if (contentType == C.CONTENT_TYPE_HLS) {
        return;
      }
    }
    throw new IllegalArgumentException();
  }

  /**
   * Returns the resumption states of the currently active {@link AdsMediaSource ads media sources}.
   *
   * <p>Call this method to get the resumption states before releasing the player and {@linkplain
   * #addAdResumptionState(AdsResumptionState) resume at the same state later}.
   *
   * <p>Live streams and streams with an {@linkplain AdsMediaSource#getAdsId() ads ID} that are not
   * of type string are ignored and are not included in the returned list of ad resumption state.
   *
   * <p>See {@link Listener#onStop(MediaItem, Object, AdPlaybackState)} and {@link
   * #addAdResumptionState(Object, AdPlaybackState)} also.
   */
  public ImmutableList<AdsResumptionState> getAdsResumptionStates() {
    ImmutableList.Builder<AdsResumptionState> resumptionStates = new ImmutableList.Builder<>();
    for (AdPlaybackState adPlaybackState : contentMediaSourceAdDataHolder.getAdPlaybackStates()) {
      boolean isLiveStream = adPlaybackState.endsWithLivePostrollPlaceHolder();
      if (!isLiveStream && adPlaybackState.adsId instanceof String) {
        resumptionStates.add(
            new AdsResumptionState((String) adPlaybackState.adsId, adPlaybackState.copy()));
      } else {
        Log.i(
            TAG,
            isLiveStream
                ? "getAdsResumptionStates(): ignoring active ad playback state of live stream."
                    + " adsId="
                    + adPlaybackState.adsId
                : "getAdsResumptionStates(): ignoring active ad playback state when creating"
                    + " resumption states. `adsId` is not of type String: "
                    + castNonNull(adPlaybackState.adsId).getClass());
      }
    }
    return resumptionStates.build();
  }

  /**
   * Adds the given {@link AdsResumptionState} to resume playback of the {@link AdsMediaSource} with
   * {@linkplain AdsMediaSource#getAdsId() ads ID} at the provided ad playback state.
   *
   * <p>If added while the given ads ID is active, the resumption state is ignored. The resumption
   * state for a given ads ID must be added before {@link #start(AdsMediaSource, DataSpec, Object,
   * AdViewProvider, EventListener)} or after {@link #stop(AdsMediaSource, EventListener)} is called
   * for that ads ID.
   *
   * @param adsResumptionState The state to resume with.
   * @throws IllegalArgumentException Thrown if the ad playback state {@linkplain
   *     AdPlaybackState#endsWithLivePostrollPlaceHolder() ends with a live placeholder}.
   */
  public void addAdResumptionState(AdsResumptionState adsResumptionState) {
    addAdResumptionState(adsResumptionState.adsId, adsResumptionState.adPlaybackState);
  }

  /**
   * Adds the given {@link AdPlaybackState} to resume playback of the {@link AdsMediaSource} with
   * {@linkplain AdsMediaSource#getAdsId() ads ID} at the provided ad playback state.
   *
   * <p>If added while the given ads ID is active, the resumption state is ignored. The resumption
   * state for a given ads ID must be added before {@link #start(AdsMediaSource, DataSpec, Object,
   * AdViewProvider, EventListener)} or after {@link #stop(AdsMediaSource, EventListener)} is called
   * for that ads ID.
   *
   * @param adsId The ads ID identifying the {@link AdsMediaSource} to resume with the given state.
   * @param adPlaybackState The state to resume with.
   * @throws IllegalArgumentException Thrown if the ad playback state {@linkplain
   *     AdPlaybackState#endsWithLivePostrollPlaceHolder() ends with a live placeholder}.
   */
  public void addAdResumptionState(Object adsId, AdPlaybackState adPlaybackState) {
    checkArgument(!adPlaybackState.endsWithLivePostrollPlaceHolder());
    if (!contentMediaSourceAdDataHolder.isStartedContentMediaSource(adsId)) {
      resumptionStates.put(adsId, adPlaybackState.copy().withAdsId(adsId));
    } else {
      Log.w(
          TAG,
          "Attempting to add an ad resumption state for an adsId that is currently active. adsId="
              + adsId);
    }
  }

  /**
   * Removes the {@link AdsResumptionState} for the given ads ID, or null if there is no active ad
   * playback state for the given ads ID.
   *
   * @param adsId The ads ID for which to remove the resumption state.
   * @return The removed resumption state or null.
   */
  public boolean removeAdResumptionState(Object adsId) {
    return resumptionStates.remove(adsId) != null;
  }

  /** Clears all ad resumptions states. */
  public void clearAllAdResumptionStates() {
    resumptionStates.clear();
  }

  /**
   * Skips the ad currently being played.
   *
   * <p>Does nothing if no ad is playing or the ad is not managed by this ads loader.
   */
  public void skipCurrentAd() {
    checkNotNull(this.player);
    if (!player.isPlayingAd()) {
      return;
    }
    setWithSkippedAd(player.getCurrentAdGroupIndex(), player.getCurrentAdIndexInAdGroup());
  }

  /**
   * Skips the ad group of the ad currently being played.
   *
   * <p>Does nothing if no ad is playing or the ad is not managed by this ads loader.
   */
  public void skipCurrentAdGroup() {
    checkNotNull(this.player);
    if (!player.isPlayingAd()) {
      return;
    }
    setWithSkippedAdGroup(player.getCurrentAdGroupIndex());
  }

  /**
   * Sets the given ad to {@link AdPlaybackState#AD_STATE_SKIPPED}.
   *
   * <p>Ads that are already in state {@link AdPlaybackState#AD_STATE_PLAYED}, {@link
   * AdPlaybackState#AD_STATE_SKIPPED} or {@link AdPlaybackState#AD_STATE_ERROR} are left unchanged.
   *
   * @param adGroupIndex The index of the ad group in the ad playback state.
   * @param adIndexInAdGroup The index of the ad in the ad group in the ad playback state.
   * @throws IllegalArgumentException if there is no ad group or ad available for the given indices.
   * @throws IllegalStateException if called when the player {@linkplain #setPlayer(Player) is not
   *     set}.
   */
  public void setWithSkippedAd(int adGroupIndex, int adIndexInAdGroup) {
    checkState(this.player != null);
    AdPlaybackState adPlaybackState = getAdPlaybackState();
    if (adPlaybackState != null) {
      checkArgument(adGroupIndex < adPlaybackState.adGroupCount);
      AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      checkArgument(adIndexInAdGroup < adGroup.count);
      if (adGroup.states[adIndexInAdGroup] == AD_STATE_PLAYED
          || adGroup.states[adIndexInAdGroup] == AD_STATE_ERROR) {
        Log.w(
            TAG,
            "ignoring request to set ad for state AD_STATE_SKIPPED for played or failed ad "
                + "at adGroupIndex="
                + adGroupIndex
                + ", adIndexInAgGroup="
                + adIndexInAdGroup);
        return;
      }
      if (adGroup.states[adIndexInAdGroup] != AD_STATE_SKIPPED) {
        adPlaybackState = adPlaybackState.withSkippedAd(adGroupIndex, adIndexInAdGroup);
        putAndNotifyAdPlaybackStateUpdate(checkNotNull(adPlaybackState.adsId), adPlaybackState);
        removeUnresolvedAssetListOfAdGroup(adPlaybackState, adGroup);
      }
    }
  }

  /**
   * Sets the given ad group to {@link AdPlaybackState#AD_STATE_SKIPPED}.
   *
   * <p>Ads that are already in state {@link AdPlaybackState#AD_STATE_PLAYED}, {@link
   * AdPlaybackState#AD_STATE_SKIPPED} or {@link AdPlaybackState#AD_STATE_ERROR} are left unchanged.
   *
   * @param adGroupIndex The index of the ad group in the ad playback state.
   * @throws IllegalArgumentException if there is no ad group available for the given index.
   * @throws IllegalStateException if called when the player {@linkplain #setPlayer(Player) is not
   *     set}.
   */
  public void setWithSkippedAdGroup(int adGroupIndex) {
    checkState(this.player != null);
    AdPlaybackState adPlaybackState = getAdPlaybackState();
    if (adPlaybackState != null) {
      checkArgument(adGroupIndex < adPlaybackState.adGroupCount);
      adPlaybackState = adPlaybackState.withSkippedAdGroup(adGroupIndex);
      AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      putAndNotifyAdPlaybackStateUpdate(checkNotNull(adPlaybackState.adsId), adPlaybackState);
      removeUnresolvedAssetListOfAdGroup(adPlaybackState, adGroup);
    }
  }

  /**
   * Sets the given ad to {@link AdPlaybackState#AD_STATE_AVAILABLE}.
   *
   * @param adGroupIndex The index of the ad group in the ad playback state.
   * @param adIndexInAdGroup The index of the ad in the ad group in the ad playback state.
   * @param mediaItem The optional media item. If not set the same ad media item is played again.
   * @throws IllegalArgumentException if there is no ad group or ad available for the given indices
   *     or if the provided {@link MediaItem} is not an HLS media item.
   * @throws IllegalStateException if the media item that was passed in is null, and the given ad
   *     doesn't have a media item set already, or if called when the player {@linkplain
   *     #setPlayer(Player) is not set}.
   */
  public void setWithAvailableAdMediaItem(
      int adGroupIndex, int adIndexInAdGroup, @Nullable MediaItem mediaItem) {
    checkState(this.player != null);
    if (mediaItem != null) {
      checkArgument(isHlsMediaItem(mediaItem));
    }
    AdPlaybackState adPlaybackState = getAdPlaybackState();
    if (adPlaybackState != null) {
      checkArgument(adGroupIndex < adPlaybackState.adGroupCount);
      AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      checkArgument(adIndexInAdGroup < adGroup.count);
      if (mediaItem == null) {
        mediaItem = adGroup.mediaItems[adIndexInAdGroup];
        checkState(mediaItem != null);
      }
      if (adGroup.states[adIndexInAdGroup] != AD_STATE_AVAILABLE) {
        adPlaybackState =
            adPlaybackState.withAvailableAdMediaItem(adGroupIndex, adIndexInAdGroup, mediaItem);
        putAndNotifyAdPlaybackStateUpdate(checkNotNull(adPlaybackState.adsId), adPlaybackState);
        removeUnresolvedAssetListOfAdGroup(adPlaybackState, adGroup);
      }
    }
  }

  /**
   * Sets the given ad group to {@link AdPlaybackState#AD_STATE_AVAILABLE}. Ads that are in state
   * {@link AdPlaybackState#AD_STATE_UNAVAILABLE} or {@link AdPlaybackState#AD_STATE_ERROR} are left
   * unchanged.
   *
   * @param adGroupIndex The index of the ad group in the ad playback state.
   * @throws IllegalArgumentException if there is no ad group available for the given index.
   * @throws IllegalStateException if called when the player {@linkplain #setPlayer(Player) is not
   *     set} or if any ad in the group doesn't have a media item for the ad stream assigned.
   */
  public void setWithAvailableAdGroup(int adGroupIndex) {
    checkState(this.player != null);
    AdPlaybackState adPlaybackState = getAdPlaybackState();
    if (adPlaybackState != null) {
      checkArgument(adGroupIndex < adPlaybackState.adGroupCount);
      AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      for (int adIndexInAdGroup = 0; adIndexInAdGroup < adGroup.states.length; adIndexInAdGroup++) {
        if ((adGroup.states[adIndexInAdGroup] == AD_STATE_PLAYED
                || adGroup.states[adIndexInAdGroup] == AD_STATE_SKIPPED)
            && adGroup.mediaItems[adIndexInAdGroup] != null) {
          @Nullable MediaItem mediaItem = adGroup.mediaItems[adIndexInAdGroup];
          checkState(mediaItem != null);
          adPlaybackState =
              adPlaybackState.withAvailableAdMediaItem(adGroupIndex, adIndexInAdGroup, mediaItem);
        }
      }
      putAndNotifyAdPlaybackStateUpdate(checkNotNull(adPlaybackState.adsId), adPlaybackState);
      removeUnresolvedAssetListOfAdGroup(adPlaybackState, adGroup);
    }
  }

  private void removeUnresolvedAssetListOfAdGroup(
      AdPlaybackState adPlaybackState, AdGroup adGroup) {
    checkArgument(adPlaybackState.adsId != null);
    Map<Long, AssetListData> unresolvedAssetLists =
        contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adPlaybackState.adsId);
    if (unresolvedAssetLists != null) {
      // remove unresolved asset list when the user manually manipulates the ad group.
      unresolvedAssetLists.remove(
          adGroup.timeUs == C.TIME_END_OF_SOURCE ? Long.MAX_VALUE : adGroup.timeUs);
    }
  }

  @Nullable
  private AdPlaybackState getAdPlaybackState() {
    if (player == null) {
      return null;
    }
    Player player = this.player;
    Timeline timeline = player.getCurrentTimeline();
    if (timeline.isEmpty()) {
      return null;
    }
    int periodIndex = player.getCurrentPeriodIndex();
    Period period = timeline.getPeriod(periodIndex, new Period());
    return period.adPlaybackState.adsId != null
        ? contentMediaSourceAdDataHolder.getAdPlaybackState(period.adPlaybackState.adsId)
        : null;
  }

  @Override
  public void start(
      AdsMediaSource adsMediaSource,
      DataSpec adTagDataSpec,
      Object adsId,
      AdViewProvider adViewProvider,
      EventListener eventListener) {
    if (isReleased) {
      // Run without ads after release to not interrupt playback.
      eventListener.onAdPlaybackState(new AdPlaybackState(adsId));
      return;
    }
    if (contentMediaSourceAdDataHolder.isStartedContentMediaSource(adsId)) {
      throw new IllegalStateException(
          "media item with adsId='"
              + adsId
              + "' already started. Make sure adsIds are unique within the same playlist.");
    }
    if (contentMediaSourceAdDataHolder.isIdle()) {
      // Set the player listener when the first ad starts.
      checkNotNull(player, "setPlayer(Player) needs to be called").addListener(playerListener);
    }
    contentMediaSourceAdDataHolder.startContentSource(adsId, eventListener);
    MediaItem mediaItem = adsMediaSource.getMediaItem();
    if (isHlsMediaItem(mediaItem)) {
      if (adsId instanceof String && resumptionStates.containsKey(adsId)) {
        // Use resumption playback state. Interstitials arriving with the timeline are ignored.
        putAndNotifyAdPlaybackStateUpdate(adsId, checkNotNull(resumptionStates.remove(adsId)));
      } else {
        // Mark with NONE and wait for the timeline to get interstitials from the HLS playlist.
        contentMediaSourceAdDataHolder.putAdPlaybackState(adsId, AdPlaybackState.NONE);
      }
      notifyListeners(listener -> listener.onStart(mediaItem, adsId, adViewProvider));
    } else {
      Log.w(TAG, "Unsupported media item. Playing without ads for adsId=" + adsId);
      putAndNotifyAdPlaybackStateUpdate(adsId, new AdPlaybackState(adsId));
      contentMediaSourceAdDataHolder.addUnsupportedContentMediaSource(adsId);
    }
  }

  @Override
  public boolean handleContentTimelineChanged(AdsMediaSource adsMediaSource, Timeline timeline) {
    Object adsId = adsMediaSource.getAdsId();
    if (isReleased) {
      EventListener eventListener = contentMediaSourceAdDataHolder.getEventListener(adsId);
      if (eventListener != null) {
        AdPlaybackState adPlaybackState =
            checkNotNull(contentMediaSourceAdDataHolder.stopContentSource(adsId));
        if (adPlaybackState.equals(AdPlaybackState.NONE)) {
          // Play without ads after release to not interrupt playback.
          eventListener.onAdPlaybackState(new AdPlaybackState(adsId));
        }
      }
      return false;
    }

    AdPlaybackState adPlaybackState =
        checkNotNull(contentMediaSourceAdDataHolder.getAdPlaybackState(adsId));
    if (!adPlaybackState.equals(AdPlaybackState.NONE)
        && !adPlaybackState.endsWithLivePostrollPlaceHolder()) {
      // Multiple timeline updates for VOD not supported.
      return false;
    }

    if (adPlaybackState.equals(AdPlaybackState.NONE)) {
      // Setup initial ad playback state for VOD or live.
      adPlaybackState = new AdPlaybackState(adsId);
      if (isLiveMediaItem(adsMediaSource.getMediaItem(), timeline)) {
        adPlaybackState =
            adPlaybackState.withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
      }
    }

    Window window = timeline.getWindow(0, new Window());
    if (window.manifest instanceof HlsManifest) {
      HlsMediaPlaylist mediaPlaylist = ((HlsManifest) window.manifest).mediaPlaylist;
      int assetListCount = contentMediaSourceAdDataHolder.getUnresolvedAssetListCount(adsId);
      adPlaybackState =
          window.isLive()
              ? mapInterstitialsForLive(
                  window.mediaItem, mediaPlaylist, adPlaybackState, window.positionInFirstPeriodUs)
              : mapInterstitialsForVod(window, mediaPlaylist, adPlaybackState);
      Player player = this.player;
      if (assetListCount != contentMediaSourceAdDataHolder.getUnresolvedAssetListCount(adsId)
          && player != null
          && Objects.equals(window.mediaItem, player.getCurrentMediaItem())) {
        long contentPositionUs;
        if (window.isLive()) {
          int currentPublicPeriodIndex = player.getCurrentPeriodIndex();
          Period publicPeriod =
              player.getCurrentTimeline().getPeriod(currentPublicPeriodIndex, new Period());
          // Use the default position if this is the first timeline update.
          contentPositionUs =
              publicPeriod.isPlaceholder
                  ? window.defaultPositionUs
                  : msToUs(player.getContentPosition());
        } else {
          contentPositionUs = msToUs(player.getContentPosition());
        }
        int adGroupIndex =
            adPlaybackState.getAdGroupIndexForPositionUs(contentPositionUs, window.durationUs);
        maybeExecuteOrSetNextAssetListResolutionMessage(
            adsId,
            timeline,
            /* windowIndex= */ 0,
            adGroupIndex != C.INDEX_UNSET
                ? adPlaybackState.getAdGroup(adGroupIndex).timeUs
                : contentPositionUs);
      }
    }
    boolean adPlaybackStateUpdated = putAndNotifyAdPlaybackStateUpdate(adsId, adPlaybackState);
    if (!contentMediaSourceAdDataHolder.isUnsupportedContentMediaSource(adsId)) {
      notifyListeners(
          listener ->
              listener.onContentTimelineChanged(adsMediaSource.getMediaItem(), adsId, timeline));
    }
    return adPlaybackStateUpdated;
  }

  @Override
  public void handlePrepareComplete(
      AdsMediaSource adsMediaSource, int adGroupIndex, int adIndexInAdGroup) {
    Object adsId = adsMediaSource.getAdsId();
    if (!isReleased && !contentMediaSourceAdDataHolder.isUnsupportedContentMediaSource(adsId)) {
      notifyListeners(
          listener ->
              listener.onPrepareCompleted(
                  adsMediaSource.getMediaItem(), adsId, adGroupIndex, adIndexInAdGroup));
    }
  }

  @Override
  public void handlePrepareError(
      AdsMediaSource adsMediaSource,
      int adGroupIndex,
      int adIndexInAdGroup,
      IOException exception) {
    Object adsId = adsMediaSource.getAdsId();
    AdPlaybackState adPlaybackState =
        checkNotNull(contentMediaSourceAdDataHolder.getAdPlaybackState(adsId))
            .withAdLoadError(adGroupIndex, adIndexInAdGroup);
    putAndNotifyAdPlaybackStateUpdate(adsId, adPlaybackState);
    if (!isReleased && !contentMediaSourceAdDataHolder.isUnsupportedContentMediaSource(adsId)) {
      notifyListeners(
          listener ->
              listener.onPrepareError(
                  adsMediaSource.getMediaItem(), adsId, adGroupIndex, adIndexInAdGroup, exception));
    }
  }

  @Override
  public void stop(AdsMediaSource adsMediaSource, EventListener eventListener) {
    Object adsId = adsMediaSource.getAdsId();
    boolean isStarted = contentMediaSourceAdDataHolder.isStartedContentMediaSource(adsId);
    checkState(isStarted || isReleased);
    boolean wasUnsupportedSource =
        contentMediaSourceAdDataHolder.isUnsupportedContentMediaSource(adsId);
    @Nullable
    AdPlaybackState adPlaybackState = contentMediaSourceAdDataHolder.stopContentSource(adsId);
    @Nullable Player player = this.player;
    if (player != null && contentMediaSourceAdDataHolder.isIdle()) {
      player.removeListener(playerListener);
      if (isReleased) {
        this.player = null;
      }
    }
    if (!isReleased && !wasUnsupportedSource) {
      if (adPlaybackState != null
          && adsId instanceof String
          && resumptionStates.containsKey(adsId)) {
        // Update the resumption state in case the user has added one.
        resumptionStates.put(adsId, adPlaybackState);
      }
      notifyListeners(
          listener ->
              listener.onStop(
                  adsMediaSource.getMediaItem(),
                  adsMediaSource.getAdsId(),
                  checkNotNull(adPlaybackState)));
    }
    cancelPendingAssetListResolutionMessage();
    if (pendingAssetListResolutionMessage != null
        && adsMediaSource
            .getMediaItem()
            .equals(castNonNull(pendingAssetListResolutionMessage).getPayload())) {
      cancelPendingAssetListResolutionMessage();
    }
  }

  @Override
  public void release() {
    // Note: Do not clear active resources as media sources still may have references to the loader
    // and we need to ensure sources can complete playback.
    if (contentMediaSourceAdDataHolder.isIdle()) {
      player = null;
    }
    clearAllAdResumptionStates();
    cancelPendingAssetListResolutionMessage();
    if (loader != null) {
      loader.release();
      loader = null;
    }
    isReleased = true;
  }

  /**
   * Returns whether {@link #release()} has been called.
   *
   * @return True if {@link #release()} has been called, false otherwise.
   */
  public boolean isReleased() {
    return isReleased;
  }

  // private methods

  private void startLoadingAssetList(AssetListData assetListData) {
    cancelPendingAssetListResolutionMessage();
    getLoader()
        .startLoading(
            new ParsingLoadable<>(
                dataSourceFactory.createDataSource(),
                checkNotNull(assetListData.interstitial.assetListUri),
                C.DATA_TYPE_AD,
                new AssetListParser()),
            new LoaderCallback(assetListData),
            /* defaultMinRetryCount= */ 1);
    notifyListeners(
        (listener) ->
            listener.onAssetListLoadStarted(
                assetListData.mediaItem,
                assetListData.adsId,
                assetListData.adGroupIndex,
                assetListData.adIndexInAdGroup));
  }

  private void maybeExecuteOrSetNextAssetListResolutionMessage(
      Object adsId, Timeline contentTimeline, int windowIndex, long windowPositionUs) {
    if (loader != null && loader.isLoading()) {
      return;
    }
    cancelPendingAssetListResolutionMessage();
    Window window = contentTimeline.getWindow(windowIndex, new Window());
    long currentPeriodPositionUs = window.positionInFirstPeriodUs + windowPositionUs;
    RunnableAtPosition nextAssetResolution = getNextAssetResolution(adsId, currentPeriodPositionUs);
    if (nextAssetResolution == null) {
      return;
    }

    long resolutionStartTimeUs =
        nextAssetResolution.adStartTimeUs != Long.MAX_VALUE
            ? nextAssetResolution.adStartTimeUs
            : window.durationUs;
    // Load 3 times the target duration before the ad starts.
    resolutionStartTimeUs =
        max(
            currentPeriodPositionUs,
            resolutionStartTimeUs - (3 * nextAssetResolution.targetDurationUs));
    if (resolutionStartTimeUs - currentPeriodPositionUs < 200_000L) {
      // Start loading immediately.
      nextAssetResolution.run();
    } else {
      long messagePositionUs = resolutionStartTimeUs - window.positionInFirstPeriodUs;
      pendingAssetListResolutionMessage =
          checkNotNull(player)
              .createMessage((messageType, message) -> nextAssetResolution.run())
              .setPayload(window.mediaItem)
              .setLooper(checkNotNull(Looper.myLooper()))
              .setPosition(usToMs(messagePositionUs));
      pendingAssetListResolutionMessage.send();
    }
  }

  @Nullable
  private RunnableAtPosition getNextAssetResolution(Object adsId, long periodPositionUs) {
    Map<Long, AssetListData> assetListDataMap =
        checkNotNull(contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adsId));
    for (Long assetListTimeUs : assetListDataMap.keySet()) {
      if (assetListDataMap.size() == 1 || periodPositionUs <= assetListTimeUs) {
        AssetListData assetListData = checkNotNull(assetListDataMap.get(assetListTimeUs));
        return new RunnableAtPosition(
            /* adStartTimeUs= */ assetListTimeUs,
            assetListData.targetDurationUs,
            () -> {
              if (assetListDataMap.remove(assetListTimeUs) != null) {
                startLoadingAssetList(assetListData);
              }
            });
      }
    }
    return null;
  }

  private void cancelPendingAssetListResolutionMessage() {
    if (pendingAssetListResolutionMessage != null) {
      pendingAssetListResolutionMessage.cancel();
      pendingAssetListResolutionMessage = null;
    }
  }

  private long getUnresolvedAssetListWindowPositionForContentPositionUs(
      long contentPositionUs, Timeline timeline, int periodIndex) {
    Period period = timeline.getPeriod(periodIndex, new Period());
    long periodPositionUs = contentPositionUs - period.positionInWindowUs;
    AdPlaybackState adPlaybackState = period.adPlaybackState;
    if (adPlaybackState.adsId == null) {
      return C.TIME_UNSET;
    }
    int adGroupIndex = adPlaybackState.getAdGroupIndexForPositionUs(periodPositionUs, C.TIME_UNSET);
    if (adGroupIndex != C.INDEX_UNSET) {
      // Seek adjustment will snap to a playable ad behind the seek position.
      AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
      Map<Long, AssetListData> unresolvedAssets =
          contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adPlaybackState.adsId);
      if (unresolvedAssets != null && unresolvedAssets.containsKey(adGroup.timeUs)) {
        Window window = timeline.getWindow(period.windowIndex, new Window());
        return adGroup.timeUs - window.positionInFirstPeriodUs;
      }
    }
    return C.TIME_UNSET;
  }

  private void notifyListeners(Consumer<Listener> callable) {
    for (int i = 0; i < listeners.size(); i++) {
      callable.accept(listeners.get(i));
    }
  }

  private Loader getLoader() {
    if (loader == null) {
      loader = new Loader("HLS-interstitials");
    }
    return loader;
  }

  private boolean putAndNotifyAdPlaybackStateUpdate(Object adsId, AdPlaybackState adPlaybackState) {
    @Nullable
    AdPlaybackState oldAdPlaybackState =
        contentMediaSourceAdDataHolder.putAdPlaybackState(adsId, adPlaybackState);
    if (!adPlaybackState.equals(oldAdPlaybackState)) {
      @Nullable
      EventListener eventListener = contentMediaSourceAdDataHolder.getEventListener(adsId);
      if (eventListener != null) {
        eventListener.onAdPlaybackState(adPlaybackState);
        return true;
      } else {
        contentMediaSourceAdDataHolder.stopContentSource(adsId);
      }
    }
    return false;
  }

  private void notifyAssetResolutionFailed(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
    AdPlaybackState adPlaybackState = contentMediaSourceAdDataHolder.getAdPlaybackState(adsId);
    if (adPlaybackState == null) {
      return;
    }
    adPlaybackState = adPlaybackState.withAdLoadError(adGroupIndex, adIndexInAdGroup);
    putAndNotifyAdPlaybackStateUpdate(adsId, adPlaybackState);
  }

  private static boolean isLiveMediaItem(MediaItem mediaItem, Timeline timeline) {
    int windowIndex = timeline.getFirstWindowIndex(/* shuffleModeEnabled= */ false);
    Window window = new Window();
    while (windowIndex != C.INDEX_UNSET) {
      timeline.getWindow(windowIndex, window);
      if (window.mediaItem.equals(mediaItem)) {
        return window.isLive();
      }
      windowIndex =
          timeline.getNextWindowIndex(
              windowIndex, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false);
    }
    return false;
  }

  private static boolean isHlsMediaItem(MediaItem mediaItem) {
    LocalConfiguration localConfiguration = checkNotNull(mediaItem.localConfiguration);
    return Objects.equals(localConfiguration.mimeType, MimeTypes.APPLICATION_M3U8)
        || Util.inferContentType(localConfiguration.uri) == C.CONTENT_TYPE_HLS;
  }

  private AdPlaybackState mapInterstitialsForLive(
      MediaItem mediaItem,
      HlsMediaPlaylist mediaPlaylist,
      AdPlaybackState adPlaybackState,
      long windowPositionInPeriodUs) {
    Object adsId = checkNotNull(adPlaybackState.adsId);
    ArrayList<Interstitial> interstitials = new ArrayList<>(mediaPlaylist.interstitials);
    for (int i = 0; i < interstitials.size(); i++) {
      Interstitial interstitial = interstitials.get(i);
      if (contentMediaSourceAdDataHolder.isInsertedInterstitialId(adsId, interstitial.id)
          || interstitial.cue.contains(CUE_TRIGGER_POST)) {
        continue;
      }
      long positionInPlaylistWindowUs =
          resolveInterstitialStartTimeUs(interstitial, mediaPlaylist) - mediaPlaylist.startTimeUs;
      if (positionInPlaylistWindowUs < 0
          || mediaPlaylist.durationUs + (3 * mediaPlaylist.targetDurationUs)
              < positionInPlaylistWindowUs) {
        // Ignore when behind the window including C.TIME_UNSET and C.TIME_END_OF_SOURCE.
        // When far in the future before the window, we wait until the window advances.
        continue;
      }
      long timeUs = windowPositionInPeriodUs + positionInPlaylistWindowUs;
      int insertionIndex = adPlaybackState.adGroupCount - 1;
      boolean isNewAdGroup = true;
      for (int adGroupIndex = adPlaybackState.adGroupCount - 2; // skip live placeholder
          adGroupIndex >= adPlaybackState.removedAdGroupCount;
          adGroupIndex--) {
        AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
        if (adGroup.timeUs == timeUs) {
          // Insert interstitials into or update in existing group.
          insertionIndex = adGroupIndex;
          isNewAdGroup = false;
          break;
        } else if (adGroup.timeUs < timeUs) {
          // Insert at index after group behind interstitial.
          insertionIndex = adGroupIndex + 1;
          break;
        }
        // Interstitial is behind the ad group. Possible insertion index.
        insertionIndex = adGroupIndex;
      }
      if (isNewAdGroup) {
        if (insertionIndex < getLowestValidAdGroupInsertionIndex(adPlaybackState)) {
          Log.w(
              TAG,
              "Skipping insertion of interstitial attempted to be inserted behind an already"
                  + " initialized ad group.");
          continue;
        }
        adPlaybackState = adPlaybackState.withNewAdGroup(insertionIndex, timeUs);
      }
      adPlaybackState =
          insertOrUpdateInterstitialInAdGroup(
              mediaItem,
              interstitial,
              adPlaybackState,
              /* adGroupIndex= */ insertionIndex,
              mediaPlaylist.targetDurationUs);
      contentMediaSourceAdDataHolder.addInsertedInterstitialId(adsId, interstitial.id);
    }
    return adPlaybackState;
  }

  private AdPlaybackState mapInterstitialsForVod(
      Window window, HlsMediaPlaylist mediaPlaylist, AdPlaybackState adPlaybackState) {
    checkArgument(adPlaybackState.adGroupCount == adPlaybackState.removedAdGroupCount);
    ImmutableList<Interstitial> interstitials = mediaPlaylist.interstitials;
    long clippedWindowStartTimeUs = mediaPlaylist.startTimeUs + window.positionInFirstPeriodUs;
    long clippedWindowEndTimeUs = clippedWindowStartTimeUs + window.durationUs;
    for (int i = 0; i < interstitials.size(); i++) {
      Interstitial interstitial = interstitials.get(i);
      long interstitialStartTimeUs = resolveInterstitialStartTimeUs(interstitial, mediaPlaylist);
      if (interstitialStartTimeUs < clippedWindowStartTimeUs
          && interstitial.cue.contains(CUE_TRIGGER_PRE)) {
        // Declared pre roll: move to the start of the clipped window.
        interstitialStartTimeUs = clippedWindowStartTimeUs;
      } else if (interstitialStartTimeUs > clippedWindowEndTimeUs
          && interstitial.cue.contains(CUE_TRIGGER_POST)) {
        // Declared post roll: move to the end of the clipped window.
        interstitialStartTimeUs = clippedWindowEndTimeUs;
      } else if (interstitialStartTimeUs < clippedWindowStartTimeUs
          || clippedWindowEndTimeUs < interstitialStartTimeUs) {
        // Ignore interstitials before or after the window that are not explicit pre or post rolls.
        continue;
      }
      long timeUs =
          clippedWindowEndTimeUs == interstitialStartTimeUs
              ? C.TIME_END_OF_SOURCE
              : interstitialStartTimeUs - mediaPlaylist.startTimeUs;
      int adGroupIndex =
          adPlaybackState.getAdGroupIndexForPositionUs(timeUs, mediaPlaylist.durationUs);
      if (adGroupIndex == C.INDEX_UNSET) {
        // There is no ad group before or at the interstitials position.
        adGroupIndex = adPlaybackState.removedAdGroupCount;
        adPlaybackState =
            adPlaybackState.withNewAdGroup(adPlaybackState.removedAdGroupCount, timeUs);
      } else if (adPlaybackState.getAdGroup(adGroupIndex).timeUs != timeUs) {
        // There is an ad group before the interstitial. Insert after that index.
        adGroupIndex++;
        adPlaybackState = adPlaybackState.withNewAdGroup(adGroupIndex, timeUs);
      }
      adPlaybackState =
          insertOrUpdateInterstitialInAdGroup(
              window.mediaItem,
              interstitial,
              adPlaybackState,
              adGroupIndex,
              mediaPlaylist.targetDurationUs);
      contentMediaSourceAdDataHolder.addInsertedInterstitialId(
          checkNotNull(adPlaybackState.adsId), interstitial.id);
    }
    return adPlaybackState;
  }

  private AdPlaybackState insertOrUpdateInterstitialInAdGroup(
      MediaItem mediaItem,
      Interstitial interstitial,
      AdPlaybackState adPlaybackState,
      int adGroupIndex,
      long playlistTargetDurationUs) {
    AdPlaybackState.AdGroup adGroup = adPlaybackState.getAdGroup(adGroupIndex);
    int adIndexInAdGroup = adGroup.getIndexOfAdId(interstitial.id);
    if (adIndexInAdGroup != C.INDEX_UNSET) {
      // Interstitial already inserted. Updating not yet supported.
      return adPlaybackState;
    }

    // Append to the end of the group.
    adIndexInAdGroup = max(adGroup.count, 0);
    // Append duration of new interstitial into existing ad durations.
    long interstitialDurationUs =
        resolveInterstitialDurationUs(interstitial, /* defaultDurationUs= */ C.TIME_UNSET);
    long[] adDurations;
    if (adIndexInAdGroup == 0) {
      adDurations = new long[1];
    } else {
      long[] previousDurations = adGroup.durationsUs;
      adDurations = new long[previousDurations.length + 1];
      System.arraycopy(previousDurations, 0, adDurations, 0, previousDurations.length);
    }
    adDurations[adDurations.length - 1] = interstitialDurationUs;
    long resumeOffsetIncrementUs =
        interstitial.resumeOffsetUs != C.TIME_UNSET
            ? interstitial.resumeOffsetUs
            : (interstitialDurationUs != C.TIME_UNSET ? interstitialDurationUs : 0L);
    long resumeOffsetUs = adGroup.contentResumeOffsetUs + resumeOffsetIncrementUs;
    adPlaybackState =
        adPlaybackState
            .withAdCount(adGroupIndex, adIndexInAdGroup + 1)
            .withAdId(adGroupIndex, adIndexInAdGroup, interstitial.id)
            .withAdDurationsUs(adGroupIndex, adDurations)
            .withContentResumeOffsetUs(adGroupIndex, resumeOffsetUs);
    if (interstitial.skipControlDurationUs != C.TIME_UNSET
        || interstitial.skipControlOffsetUs != C.TIME_UNSET
        || interstitial.skipControlLabelId != null) {
      // if any of the skip control properties is set, then we consider the interstitials as
      // skippable by applying the default values of the other properties defined in the spec.
      adPlaybackState =
          adPlaybackState.withAdSkipInfo(
              adGroupIndex,
              adIndexInAdGroup,
              new SkipInfo(
                  interstitial.skipControlOffsetUs,
                  interstitial.skipControlDurationUs,
                  interstitial.skipControlLabelId));
    }

    if (interstitial.assetUri != null) {
      adPlaybackState =
          adPlaybackState.withAvailableAdMediaItem(
              adGroupIndex,
              adIndexInAdGroup,
              new MediaItem.Builder()
                  .setUri(interstitial.assetUri)
                  .setMimeType(MimeTypes.APPLICATION_M3U8)
                  .build());
    } else {
      Object adsId = checkNotNull(adPlaybackState.adsId);
      checkNotNull(contentMediaSourceAdDataHolder.getUnresolvedAssetLists(adsId))
          .put(
              adGroup.timeUs != C.TIME_END_OF_SOURCE ? adGroup.timeUs : Long.MAX_VALUE,
              new AssetListData(
                  mediaItem,
                  adsId,
                  interstitial,
                  adGroupIndex,
                  adIndexInAdGroup,
                  playlistTargetDurationUs));
    }
    return adPlaybackState;
  }

  private static int getLowestValidAdGroupInsertionIndex(AdPlaybackState adPlaybackState) {
    for (int adGroupIndex = adPlaybackState.adGroupCount - 1;
        adGroupIndex >= adPlaybackState.removedAdGroupCount;
        adGroupIndex--) {
      for (@AdPlaybackState.AdState int state : adPlaybackState.getAdGroup(adGroupIndex).states) {
        if (state != AD_STATE_UNAVAILABLE) {
          return adGroupIndex + 1;
        }
      }
    }
    // All ad groups unavailable.
    return adPlaybackState.removedAdGroupCount;
  }

  private static long resolveInterstitialDurationUs(
      Interstitial interstitial, long defaultDurationUs) {
    if (interstitial.playoutLimitUs != C.TIME_UNSET) {
      return interstitial.playoutLimitUs;
    } else if (interstitial.durationUs != C.TIME_UNSET) {
      return interstitial.durationUs;
    } else if (interstitial.endDateUnixUs != C.TIME_UNSET) {
      return interstitial.endDateUnixUs - interstitial.startDateUnixUs;
    } else if (interstitial.plannedDurationUs != C.TIME_UNSET) {
      return interstitial.plannedDurationUs;
    }
    return defaultDurationUs;
  }

  private static long resolveInterstitialStartTimeUs(
      Interstitial interstitial, HlsMediaPlaylist mediaPlaylist) {
    if (interstitial.cue.contains(CUE_TRIGGER_PRE)) {
      return mediaPlaylist.startTimeUs;
    } else if (interstitial.cue.contains(CUE_TRIGGER_POST)) {
      return mediaPlaylist.startTimeUs + mediaPlaylist.durationUs;
    } else if (interstitial.snapTypes.contains(SNAP_TYPE_OUT)) {
      return getClosestSegmentBoundaryUs(interstitial.startDateUnixUs, mediaPlaylist);
    } else if (interstitial.snapTypes.contains(SNAP_TYPE_IN)) {
      long resumeOffsetUs =
          interstitial.resumeOffsetUs != C.TIME_UNSET
              ? interstitial.resumeOffsetUs
              : resolveInterstitialDurationUs(interstitial, /* defaultDurationUs= */ 0L);
      return getClosestSegmentBoundaryUs(
              interstitial.startDateUnixUs + resumeOffsetUs, mediaPlaylist)
          - resumeOffsetUs;
    } else {
      return interstitial.startDateUnixUs;
    }
  }

  @VisibleForTesting
  /* package */ static long getClosestSegmentBoundaryUs(
      long unixTimeUs, HlsMediaPlaylist mediaPlaylist) {
    long positionInPlaylistUs = unixTimeUs - mediaPlaylist.startTimeUs;
    if (positionInPlaylistUs <= 0 || mediaPlaylist.segments.isEmpty()) {
      return mediaPlaylist.startTimeUs;
    } else if (positionInPlaylistUs >= mediaPlaylist.durationUs) {
      return mediaPlaylist.startTimeUs + mediaPlaylist.durationUs;
    }

    // Binary search to find the segment containing or closest to the position
    int left = 0;
    int right = mediaPlaylist.segments.size() - 1;
    int closestIndex = 0;

    while (left <= right) {
      int mid = left + (right - left) / 2;
      HlsMediaPlaylist.Segment midSegment = mediaPlaylist.segments.get(mid);
      long segmentStart = midSegment.relativeStartTimeUs;
      long segmentEnd = segmentStart + midSegment.durationUs;

      if (positionInPlaylistUs >= segmentStart && positionInPlaylistUs <= segmentEnd) {
        // Position is within this segment
        closestIndex = mid;
        break;
      } else if (positionInPlaylistUs < segmentStart) {
        closestIndex = mid;
        right = mid - 1;
      } else {
        closestIndex = mid;
        left = mid + 1;
      }
    }

    HlsMediaPlaylist.Segment segment = mediaPlaylist.segments.get(closestIndex);
    return positionInPlaylistUs - segment.relativeStartTimeUs
            < abs(positionInPlaylistUs - (segment.relativeStartTimeUs + segment.durationUs))
        ? mediaPlaylist.startTimeUs + segment.relativeStartTimeUs
        : mediaPlaylist.startTimeUs + segment.relativeStartTimeUs + segment.durationUs;
  }

  private class PlayerListener implements Player.Listener {

    private final Period period = new Period();

    @Override
    public void onMetadata(Metadata metadata) {
      @Nullable Player player = HlsInterstitialsAdsLoader.this.player;
      if (player == null || !player.isPlayingAd()) {
        return;
      }
      player.getCurrentTimeline().getPeriod(player.getCurrentPeriodIndex(), period);
      @Nullable Object adsId = period.adPlaybackState.adsId;
      if (adsId == null || !contentMediaSourceAdDataHolder.isManagedContentSource(adsId)) {
        return;
      }
      MediaItem currentMediaItem = checkNotNull(player.getCurrentMediaItem());
      int currentAdGroupIndex = player.getCurrentAdGroupIndex();
      int currentAdIndexInAdGroup = player.getCurrentAdIndexInAdGroup();
      notifyListeners(
          listener ->
              listener.onMetadata(
                  currentMediaItem, adsId, currentAdGroupIndex, currentAdIndexInAdGroup, metadata));
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
      if (timeline.isEmpty()) {
        cancelPendingAssetListResolutionMessage();
      }
    }

    @Override
    public void onPositionDiscontinuity(
        Player.PositionInfo oldPosition,
        Player.PositionInfo newPosition,
        @Player.DiscontinuityReason int reason) {
      if (player == null
          || oldPosition.mediaItem == null
          || newPosition.mediaItem == null
          || reason == DISCONTINUITY_REASON_REMOVE) {
        cancelPendingAssetListResolutionMessage();
        return;
      }
      Timeline currentTimeline = player.getCurrentTimeline();
      AdPlaybackState adPlaybackState =
          currentTimeline.getPeriod(newPosition.periodIndex, period).adPlaybackState;
      @Nullable Object adsId = adPlaybackState.adsId;
      if (adsId == null || !contentMediaSourceAdDataHolder.isManagedContentSource(adsId)) {
        // Currently playing a period without ads, or an ad period not managed by this ads loader.
        cancelPendingAssetListResolutionMessage();
        return;
      }
      if ((reason == DISCONTINUITY_REASON_AUTO_TRANSITION || reason == DISCONTINUITY_REASON_SKIP)
          && oldPosition.adGroupIndex != C.INDEX_UNSET) {
        currentTimeline.getPeriod(oldPosition.periodIndex, period);
        markAdAsPlayedAndNotifyListeners(
            oldPosition.mediaItem, adsId, oldPosition.adGroupIndex, oldPosition.adIndexInAdGroup);
      } else if (reason == DISCONTINUITY_REASON_SEEK
          || reason == DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        long windowPositionUs = msToUs(newPosition.contentPositionMs);
        long assetListWindowPositionUs =
            getUnresolvedAssetListWindowPositionForContentPositionUs(
                windowPositionUs, currentTimeline, newPosition.periodIndex);
        maybeExecuteOrSetNextAssetListResolutionMessage(
            adsId,
            currentTimeline,
            newPosition.mediaItemIndex,
            assetListWindowPositionUs != C.TIME_UNSET
                ? assetListWindowPositionUs
                : windowPositionUs);
      }
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      Player player = HlsInterstitialsAdsLoader.this.player;
      if (playbackState != Player.STATE_ENDED || player == null || !player.isPlayingAd()) {
        return;
      }
      player.getCurrentTimeline().getPeriod(player.getCurrentPeriodIndex(), period);
      @Nullable Object adsId = period.adPlaybackState.adsId;
      if (adsId != null && contentMediaSourceAdDataHolder.isManagedContentSource(adsId)) {
        markAdAsPlayedAndNotifyListeners(
            checkNotNull(player.getCurrentMediaItem()),
            adsId,
            player.getCurrentAdGroupIndex(),
            player.getCurrentAdIndexInAdGroup());
      }
    }

    private void markAdAsPlayedAndNotifyListeners(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      @Nullable
      AdPlaybackState adPlaybackState = contentMediaSourceAdDataHolder.getAdPlaybackState(adsId);
      if (adPlaybackState != null
          && adPlaybackState.getAdGroup(adGroupIndex).states[adIndexInAdGroup]
              == AD_STATE_AVAILABLE) {
        adPlaybackState = adPlaybackState.withPlayedAd(adGroupIndex, adIndexInAdGroup);
        putAndNotifyAdPlaybackStateUpdate(adsId, adPlaybackState);
        notifyListeners(
            listener -> listener.onAdCompleted(mediaItem, adsId, adGroupIndex, adIndexInAdGroup));
      }
    }
  }

  private static final class ContentMediaSourceAdDataHolder {
    private final Map<Object, EventListener> activeEventListeners;
    private final Map<Object, AdPlaybackState> activeAdPlaybackStates;
    private final Map<Object, Set<String>> insertedInterstitialIds;
    private final Map<Object, TreeMap<Long, AssetListData>> unresolvedAssetLists;
    private final Set<Object> unsupportedAdsIds;

    /** Creates a new instance */
    public ContentMediaSourceAdDataHolder() {
      activeEventListeners = new HashMap<>();
      activeAdPlaybackStates = new HashMap<>();
      insertedInterstitialIds = new HashMap<>();
      unresolvedAssetLists = new HashMap<>();
      unsupportedAdsIds = new HashSet<>();
    }

    /** Returns whether the holder is idle with no {@link EventListener} registered. */
    public boolean isIdle() {
      return activeEventListeners.isEmpty();
    }

    /** Start a content source for the given ads ID. */
    @Nullable
    public EventListener startContentSource(Object adsId, EventListener listener) {
      insertedInterstitialIds.put(adsId, new HashSet<>());
      unresolvedAssetLists.put(adsId, new TreeMap<>());
      return activeEventListeners.put(adsId, listener);
    }

    /** Returns whether the content source with the given ads ID is started. */
    public boolean isStartedContentMediaSource(Object adsId) {
      return activeEventListeners.containsKey(adsId);
    }

    /**
     * Returns whether the content media source with the given ads ID is a managed content source.
     */
    public boolean isManagedContentSource(Object adsId) {
      return activeAdPlaybackStates.containsKey(adsId);
    }

    /** Returns the event listener for the given ads ID, or null if not found. */
    @Nullable
    public EventListener getEventListener(Object adsId) {
      return activeEventListeners.get(adsId);
    }

    /** Adds an ads ID of a content source that was started but is not supported. */
    public void addUnsupportedContentMediaSource(Object adsId) {
      unsupportedAdsIds.add(adsId);
    }

    /** Whether the given ads ID was started and considered unsupported. */
    public boolean isUnsupportedContentMediaSource(Object adsId) {
      return unsupportedAdsIds.contains(adsId);
    }

    /**
     * Puts an ad playback state for the given ads ID and returns the previous one, or null if there
     * was none.
     */
    @Nullable
    public AdPlaybackState putAdPlaybackState(Object adsId, AdPlaybackState state) {
      return activeAdPlaybackStates.put(adsId, state);
    }

    /** Returns the ad playback state for the given ads ID, or null if not found. */
    @Nullable
    public AdPlaybackState getAdPlaybackState(Object adsId) {
      return activeAdPlaybackStates.get(adsId);
    }

    /** Returns a collection of all active ad playback states. */
    public Collection<AdPlaybackState> getAdPlaybackStates() {
      return activeAdPlaybackStates.values();
    }

    /** Adds an interstitial ID for the given ads ID to mark it as inserted. */
    public void addInsertedInterstitialId(Object adsId, String interstitialId) {
      Set<String> insertedInterstitialIdSet = insertedInterstitialIds.get(adsId);
      if (insertedInterstitialIdSet != null) {
        insertedInterstitialIdSet.add(interstitialId);
      }
    }

    /** Returns whether the given interstitial ID has already been inserted for the given ads ID. */
    public boolean isInsertedInterstitialId(Object adsId, String interstitialId) {
      Set<String> insertedInterstitialIdSet = insertedInterstitialIds.get(adsId);
      return insertedInterstitialIdSet != null
          && insertedInterstitialIdSet.contains(interstitialId);
    }

    /** Returns the map of unresolved asset lists for the given ads ID, or null if not found. */
    @Nullable
    public Map<Long, AssetListData> getUnresolvedAssetLists(Object adsId) {
      return unresolvedAssetLists.get(adsId);
    }

    /** Return the number of unresolved asset list for the given ads ID. */
    public int getUnresolvedAssetListCount(Object adsId) {
      TreeMap<Long, AssetListData> assetListDataTreeMap = unresolvedAssetLists.get(adsId);
      return assetListDataTreeMap != null ? assetListDataTreeMap.size() : 0;
    }

    /**
     * Stops calling the {@link EventListener} and clears all ad data for the given ads ID.
     *
     * @param adsId The ads ID.
     * @return The {@link AdPlaybackState} for the given ads ID.
     */
    @Nullable
    public AdPlaybackState stopContentSource(Object adsId) {
      activeEventListeners.remove(adsId);
      insertedInterstitialIds.remove(adsId);
      unresolvedAssetLists.remove(adsId);
      unsupportedAdsIds.remove(adsId);
      return activeAdPlaybackStates.remove(adsId);
    }
  }

  private class LoaderCallback implements Loader.Callback<ParsingLoadable<AssetList>> {

    private final AssetListData assetListData;

    /** Creates an instance. */
    public LoaderCallback(AssetListData assetListData) {
      this.assetListData = assetListData;
    }

    @Override
    public void onLoadCompleted(
        ParsingLoadable<AssetList> loadable, long elapsedRealtimeMs, long loadDurationMs) {
      @Nullable AssetList assetList = loadable.getResult();
      AdPlaybackState adPlaybackState =
          contentMediaSourceAdDataHolder.getAdPlaybackState(assetListData.adsId);
      // Get the state of the ad to validate there was no manual change since we started loading.
      int assetListAdState =
          adPlaybackState != null
              ? adPlaybackState.getAdGroup(assetListData.adGroupIndex)
                  .states[assetListData.adIndexInAdGroup]
              : AD_STATE_ERROR;
      if (assetListAdState != AD_STATE_UNAVAILABLE) {
        // The ad was manipulated manually since the asset loading was started. Ignore asset list
        // and make sure the next asset list is scheduled for loading (if any).
        maybeContinueAssetResolution();
        notifyListeners(
            listener ->
                listener.onAssetListLoadFailed(
                    assetListData.mediaItem,
                    assetListData.adsId,
                    assetListData.adGroupIndex,
                    assetListData.adIndexInAdGroup,
                    /* ioException= */ null,
                    /* cancelled= */ true));
        return;
      } else if (assetList == null || assetList.assets.isEmpty()) {
        // Mark the ad as failed and schedule the next asset list for loading (if any).
        handleAssetResolutionFailed(new IOException("empty asset list"), /* cancelled= */ false);
        return;
      }
      AdPlaybackState.AdGroup adGroup =
          checkNotNull(adPlaybackState).getAdGroup(assetListData.adGroupIndex);
      long oldAdDurationUs =
          adGroup.durationsUs[assetListData.adIndexInAdGroup] != C.TIME_UNSET
              ? adGroup.durationsUs[assetListData.adIndexInAdGroup]
              : 0;
      int oldAdCount = adGroup.count;
      long sumOfAssetListAdDurationUs = 0L;
      if (assetList.assets.size() > 1) {
        // expanding to multiple ads
        adPlaybackState =
            adPlaybackState.withAdCount(
                assetListData.adGroupIndex, oldAdCount + assetList.assets.size() - 1);
        // Re-fetch ad group after ad count changed
        adGroup = adPlaybackState.getAdGroup(assetListData.adGroupIndex);
      }
      int adIndex = assetListData.adIndexInAdGroup;
      long[] newDurationsUs = adGroup.durationsUs.clone();
      for (int i = 0; i < assetList.assets.size(); i++) {
        Asset asset = assetList.assets.get(i);
        if (i > 0) {
          adIndex = oldAdCount + i - 1;
        }
        newDurationsUs[adIndex] = asset.durationUs;
        sumOfAssetListAdDurationUs += asset.durationUs;
        MediaItem mediaItem =
            new MediaItem.Builder()
                .setUri(asset.uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        adPlaybackState =
            adPlaybackState.withAvailableAdMediaItem(
                assetListData.adGroupIndex, adIndex, mediaItem);
        if (assetList.skipInfo != null) {
          adPlaybackState =
              adPlaybackState.withAdSkipInfo(
                  assetListData.adGroupIndex, adIndex, assetList.skipInfo);
        }
      }
      adPlaybackState =
          adPlaybackState.withAdDurationsUs(assetListData.adGroupIndex, newDurationsUs);
      if (assetListData.interstitial.resumeOffsetUs == C.TIME_UNSET) {
        adGroup = adPlaybackState.getAdGroup(assetListData.adGroupIndex);
        long newContentResumeOffsetUs =
            adGroup.contentResumeOffsetUs - oldAdDurationUs + sumOfAssetListAdDurationUs;
        adPlaybackState =
            adPlaybackState.withContentResumeOffsetUs(
                assetListData.adGroupIndex, newContentResumeOffsetUs);
      }
      putAndNotifyAdPlaybackStateUpdate(assetListData.adsId, adPlaybackState);
      notifyListeners(
          listener ->
              listener.onAssetListLoadCompleted(
                  assetListData.mediaItem,
                  assetListData.adsId,
                  assetListData.adGroupIndex,
                  assetListData.adIndexInAdGroup,
                  assetList));
      maybeContinueAssetResolution();
    }

    @Override
    public void onLoadCanceled(
        ParsingLoadable<AssetList> loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        boolean released) {
      handleAssetResolutionFailed(/* error= */ null, /* cancelled= */ true);
    }

    @Override
    public Loader.LoadErrorAction onLoadError(
        ParsingLoadable<AssetList> loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        IOException error,
        int errorCount) {
      handleAssetResolutionFailed(error, /* cancelled= */ false);
      return Loader.DONT_RETRY;
    }

    private void handleAssetResolutionFailed(@Nullable IOException error, boolean cancelled) {
      notifyAssetResolutionFailed(
          assetListData.adsId, assetListData.adGroupIndex, assetListData.adIndexInAdGroup);
      notifyListeners(
          listener ->
              listener.onAssetListLoadFailed(
                  assetListData.mediaItem,
                  assetListData.adsId,
                  assetListData.adGroupIndex,
                  assetListData.adIndexInAdGroup,
                  error,
                  cancelled));
      maybeContinueAssetResolution();
    }

    private void maybeContinueAssetResolution() {
      ExoPlayer player = HlsInterstitialsAdsLoader.this.player;
      if (player == null
          || player.getPlaybackState() == STATE_IDLE
          || !assetListData.mediaItem.equals(player.getCurrentMediaItem())) {
        return;
      }
      long contentPositionUs = msToUs(player.getContentPosition());
      Timeline currentTimeline = player.getCurrentTimeline();
      long assetListTimeUsForPositionUs =
          getUnresolvedAssetListWindowPositionForContentPositionUs(
              contentPositionUs, currentTimeline, player.getCurrentPeriodIndex());
      maybeExecuteOrSetNextAssetListResolutionMessage(
          assetListData.adsId,
          currentTimeline,
          player.getCurrentMediaItemIndex(),
          /* windowPositionUs= */ assetListTimeUsForPositionUs != C.TIME_UNSET
              ? assetListTimeUsForPositionUs
              : contentPositionUs);
    }
  }

  private static class RunnableAtPosition implements Runnable {
    public final long adStartTimeUs;
    private final long targetDurationUs;
    private final Runnable runnable;

    /** Creates an instance. */
    public RunnableAtPosition(long adStartTimeUs, long targetDurationUs, Runnable runnable) {
      this.adStartTimeUs = adStartTimeUs;
      this.targetDurationUs = targetDurationUs;
      this.runnable = runnable;
    }

    @Override
    public void run() {
      runnable.run();
    }
  }

  private static class AssetListData {
    private final MediaItem mediaItem;
    private final Object adsId;
    private final int adGroupIndex;
    private final int adIndexInAdGroup;
    private final long targetDurationUs;
    private final Interstitial interstitial;

    /** Create an instance. */
    public AssetListData(
        MediaItem mediaItem,
        Object adsId,
        Interstitial interstitial,
        int adGroupIndex,
        int adIndexInAdGroup,
        long targetDurationUs) {
      checkArgument(interstitial.assetListUri != null);
      this.mediaItem = mediaItem;
      this.adsId = adsId;
      this.adGroupIndex = adGroupIndex;
      this.adIndexInAdGroup = adIndexInAdGroup;
      this.targetDurationUs = targetDurationUs;
      this.interstitial = interstitial;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof AssetListData)) {
        return false;
      }
      AssetListData that = (AssetListData) o;
      return adGroupIndex == that.adGroupIndex
          && adIndexInAdGroup == that.adIndexInAdGroup
          && targetDurationUs == that.targetDurationUs
          && Objects.equals(mediaItem, that.mediaItem)
          && Objects.equals(adsId, that.adsId)
          && Objects.equals(interstitial, that.interstitial);
    }

    @Override
    public int hashCode() {
      int result = mediaItem.hashCode();
      result = 31 * result + adsId.hashCode();
      result = 31 * result + interstitial.hashCode();
      result = 31 * result + adGroupIndex;
      result = 31 * result + adIndexInAdGroup;
      result = (int) (31L * result + targetDurationUs);
      return result;
    }
  }
}
