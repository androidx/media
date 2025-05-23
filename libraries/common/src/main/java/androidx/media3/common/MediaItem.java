/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.common;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.common.util.Util.usToMs;

import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.BundleCollectionUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Representation of a media item. */
public final class MediaItem {

  /**
   * Creates a {@link MediaItem} for the given URI.
   *
   * @param uri The URI.
   * @return An {@link MediaItem} for the given URI.
   */
  public static MediaItem fromUri(String uri) {
    return new MediaItem.Builder().setUri(uri).build();
  }

  /**
   * Creates a {@link MediaItem} for the given {@link Uri URI}.
   *
   * @param uri The {@link Uri uri}.
   * @return An {@link MediaItem} for the given URI.
   */
  public static MediaItem fromUri(Uri uri) {
    return new MediaItem.Builder().setUri(uri).build();
  }

  /** A builder for {@link MediaItem} instances. */
  public static final class Builder {

    @Nullable private String mediaId;
    @Nullable private Uri uri;
    @Nullable private String mimeType;
    // TODO: Change this to ClippingProperties once all the deprecated individual setters are
    // removed.
    private ClippingConfiguration.Builder clippingConfiguration;
    // TODO: Change this to @Nullable DrmConfiguration once all the deprecated individual setters
    // are removed.
    private DrmConfiguration.Builder drmConfiguration;
    private List<StreamKey> streamKeys;
    @Nullable private String customCacheKey;
    private ImmutableList<SubtitleConfiguration> subtitleConfigurations;
    @Nullable private AdsConfiguration adsConfiguration;
    @Nullable private Object tag;
    private long imageDurationMs;
    @Nullable private MediaMetadata mediaMetadata;
    // TODO: Change this to LiveConfiguration once all the deprecated individual setters
    // are removed.
    private LiveConfiguration.Builder liveConfiguration;
    private RequestMetadata requestMetadata;

    /** Creates a builder. */
    @SuppressWarnings("deprecation") // Temporarily uses DrmConfiguration.Builder() constructor.
    public Builder() {
      clippingConfiguration = new ClippingConfiguration.Builder();
      drmConfiguration = new DrmConfiguration.Builder();
      streamKeys = Collections.emptyList();
      subtitleConfigurations = ImmutableList.of();
      liveConfiguration = new LiveConfiguration.Builder();
      requestMetadata = RequestMetadata.EMPTY;
      imageDurationMs = C.TIME_UNSET;
    }

    // Using deprecated DrmConfiguration.Builder to support deprecated methods.
    @SuppressWarnings("deprecation")
    private Builder(MediaItem mediaItem) {
      this();
      clippingConfiguration = mediaItem.clippingConfiguration.buildUpon();
      mediaId = mediaItem.mediaId;
      mediaMetadata = mediaItem.mediaMetadata;
      liveConfiguration = mediaItem.liveConfiguration.buildUpon();
      requestMetadata = mediaItem.requestMetadata;
      @Nullable LocalConfiguration localConfiguration = mediaItem.localConfiguration;
      if (localConfiguration != null) {
        customCacheKey = localConfiguration.customCacheKey;
        mimeType = localConfiguration.mimeType;
        uri = localConfiguration.uri;
        streamKeys = localConfiguration.streamKeys;
        subtitleConfigurations = localConfiguration.subtitleConfigurations;
        tag = localConfiguration.tag;
        drmConfiguration =
            localConfiguration.drmConfiguration != null
                ? localConfiguration.drmConfiguration.buildUpon()
                : new DrmConfiguration.Builder();
        adsConfiguration = localConfiguration.adsConfiguration;
        imageDurationMs = localConfiguration.imageDurationMs;
      }
    }

    /**
     * Sets the optional media ID which identifies the media item.
     *
     * <p>By default {@link #DEFAULT_MEDIA_ID} is used.
     */
    @CanIgnoreReturnValue
    public Builder setMediaId(String mediaId) {
      this.mediaId = checkNotNull(mediaId);
      return this;
    }

    /**
     * Sets the optional URI.
     *
     * <p>If {@code uri} is null or unset then no {@link LocalConfiguration} object is created
     * during {@link #build()} and no other {@code Builder} methods that would populate {@link
     * MediaItem#localConfiguration} should be called.
     */
    @CanIgnoreReturnValue
    public Builder setUri(@Nullable String uri) {
      return setUri(uri == null ? null : Uri.parse(uri));
    }

    /**
     * Sets the optional URI.
     *
     * <p>If {@code uri} is null or unset then no {@link LocalConfiguration} object is created
     * during {@link #build()} and no other {@code Builder} methods that would populate {@link
     * MediaItem#localConfiguration} should be called.
     */
    @CanIgnoreReturnValue
    public Builder setUri(@Nullable Uri uri) {
      this.uri = uri;
      return this;
    }

    /**
     * Sets the optional MIME type.
     *
     * <p>The MIME type may be used as a hint for inferring the type of the media item.
     *
     * <p>This method should only be called if {@link #setUri} is passed a non-null value.
     *
     * @param mimeType The MIME type.
     */
    @CanIgnoreReturnValue
    public Builder setMimeType(@Nullable String mimeType) {
      this.mimeType = mimeType;
      return this;
    }

    /** Sets the {@link ClippingConfiguration}, defaults to {@link ClippingConfiguration#UNSET}. */
    @CanIgnoreReturnValue
    public Builder setClippingConfiguration(ClippingConfiguration clippingConfiguration) {
      this.clippingConfiguration = clippingConfiguration.buildUpon();
      return this;
    }

    /**
     * @deprecated Use {@link #setClippingConfiguration(ClippingConfiguration)} and {@link
     *     ClippingConfiguration.Builder#setStartPositionMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setClipStartPositionMs(@IntRange(from = 0) long startPositionMs) {
      clippingConfiguration.setStartPositionMs(startPositionMs);
      return this;
    }

    /**
     * @deprecated Use {@link #setClippingConfiguration(ClippingConfiguration)} and {@link
     *     ClippingConfiguration.Builder#setEndPositionMs(long)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setClipEndPositionMs(long endPositionMs) {
      clippingConfiguration.setEndPositionMs(endPositionMs);
      return this;
    }

    /**
     * @deprecated Use {@link #setClippingConfiguration(ClippingConfiguration)} and {@link
     *     ClippingConfiguration.Builder#setRelativeToLiveWindow(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setClipRelativeToLiveWindow(boolean relativeToLiveWindow) {
      clippingConfiguration.setRelativeToLiveWindow(relativeToLiveWindow);
      return this;
    }

    /**
     * @deprecated Use {@link #setClippingConfiguration(ClippingConfiguration)} and {@link
     *     ClippingConfiguration.Builder#setRelativeToDefaultPosition(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setClipRelativeToDefaultPosition(boolean relativeToDefaultPosition) {
      clippingConfiguration.setRelativeToDefaultPosition(relativeToDefaultPosition);
      return this;
    }

    /**
     * @deprecated Use {@link #setClippingConfiguration(ClippingConfiguration)} and {@link
     *     ClippingConfiguration.Builder#setStartsAtKeyFrame(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setClipStartsAtKeyFrame(boolean startsAtKeyFrame) {
      clippingConfiguration.setStartsAtKeyFrame(startsAtKeyFrame);
      return this;
    }

    /** Sets the optional DRM configuration. */
    // Using deprecated DrmConfiguration.Builder to support deprecated methods.
    @SuppressWarnings("deprecation")
    @CanIgnoreReturnValue
    public Builder setDrmConfiguration(@Nullable DrmConfiguration drmConfiguration) {
      this.drmConfiguration =
          drmConfiguration != null ? drmConfiguration.buildUpon() : new DrmConfiguration.Builder();
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setLicenseUri(Uri)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmLicenseUri(@Nullable Uri licenseUri) {
      drmConfiguration.setLicenseUri(licenseUri);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setLicenseUri(String)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmLicenseUri(@Nullable String licenseUri) {
      drmConfiguration.setLicenseUri(licenseUri);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setLicenseRequestHeaders(Map)} instead. Note that {@link
     *     DrmConfiguration.Builder#setLicenseRequestHeaders(Map)} doesn't accept null, use an empty
     *     map to clear the headers.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmLicenseRequestHeaders(
        @Nullable Map<String, String> licenseRequestHeaders) {
      drmConfiguration.setLicenseRequestHeaders(
          licenseRequestHeaders != null ? licenseRequestHeaders : ImmutableMap.of());
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and pass the {@code uuid} to
     *     {@link DrmConfiguration.Builder#Builder(UUID)} instead.
     */
    @SuppressWarnings("deprecation") // Forwarding deprecated call
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmUuid(@Nullable UUID uuid) {
      drmConfiguration.setNullableScheme(uuid);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setMultiSession(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmMultiSession(boolean multiSession) {
      drmConfiguration.setMultiSession(multiSession);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setForceDefaultLicenseUri(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmForceDefaultLicenseUri(boolean forceDefaultLicenseUri) {
      drmConfiguration.setForceDefaultLicenseUri(forceDefaultLicenseUri);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setPlayClearContentWithoutKey(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmPlayClearContentWithoutKey(boolean playClearContentWithoutKey) {
      drmConfiguration.setPlayClearContentWithoutKey(playClearContentWithoutKey);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setForceSessionsForAudioAndVideoTracks(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmSessionForClearPeriods(boolean sessionForClearPeriods) {
      drmConfiguration.setForceSessionsForAudioAndVideoTracks(sessionForClearPeriods);
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setForcedSessionTrackTypes(List)} instead. Note that {@link
     *     DrmConfiguration.Builder#setForcedSessionTrackTypes(List)} doesn't accept null, use an
     *     empty list to clear the contents.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmSessionForClearTypes(
        @Nullable List<@C.TrackType Integer> sessionForClearTypes) {
      drmConfiguration.setForcedSessionTrackTypes(
          sessionForClearTypes != null ? sessionForClearTypes : ImmutableList.of());
      return this;
    }

    /**
     * @deprecated Use {@link #setDrmConfiguration(DrmConfiguration)} and {@link
     *     DrmConfiguration.Builder#setKeySetId(byte[])} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setDrmKeySetId(@Nullable byte[] keySetId) {
      drmConfiguration.setKeySetId(keySetId);
      return this;
    }

    /**
     * Sets the optional stream keys by which the manifest is filtered (only used for adaptive
     * streams).
     *
     * <p>{@code null} or an empty {@link List} can be used for a reset.
     *
     * <p>If {@link #setUri} is passed a non-null {@code uri}, the stream keys are used to create a
     * {@link LocalConfiguration} object. Otherwise they will be ignored.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Builder setStreamKeys(@Nullable List<StreamKey> streamKeys) {
      this.streamKeys =
          streamKeys != null && !streamKeys.isEmpty()
              ? Collections.unmodifiableList(new ArrayList<>(streamKeys))
              : Collections.emptyList();
      return this;
    }

    /**
     * Sets the optional custom cache key (only used for progressive streams).
     *
     * <p>This method should only be called if {@link #setUri} is passed a non-null value.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Builder setCustomCacheKey(@Nullable String customCacheKey) {
      this.customCacheKey = customCacheKey;
      return this;
    }

    /**
     * @deprecated Use {@link #setSubtitleConfigurations(List)} instead. Note that {@link
     *     #setSubtitleConfigurations(List)} doesn't accept null, use an empty list to clear the
     *     contents.
     */
    @SuppressWarnings("deprecation") // Supporting deprecated type
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setSubtitles(@Nullable List<Subtitle> subtitles) {
      this.subtitleConfigurations =
          subtitles != null ? ImmutableList.copyOf(subtitles) : ImmutableList.of();
      return this;
    }

    /**
     * Sets the optional subtitles.
     *
     * <p>This method should only be called if {@link #setUri} is passed a non-null value.
     */
    @CanIgnoreReturnValue
    public Builder setSubtitleConfigurations(List<SubtitleConfiguration> subtitleConfigurations) {
      this.subtitleConfigurations = ImmutableList.copyOf(subtitleConfigurations);
      return this;
    }

    /**
     * Sets the optional {@link AdsConfiguration}.
     *
     * <p>This method should only be called if {@link #setUri} is passed a non-null value.
     */
    @CanIgnoreReturnValue
    public Builder setAdsConfiguration(@Nullable AdsConfiguration adsConfiguration) {
      this.adsConfiguration = adsConfiguration;
      return this;
    }

    /**
     * @deprecated Use {@link #setAdsConfiguration(AdsConfiguration)}, parse the {@code adTagUri}
     *     with {@link Uri#parse(String)} and pass the result to {@link
     *     AdsConfiguration.Builder#Builder(Uri)} instead.
     */
    @SuppressWarnings("deprecation") // Forwarding to other deprecated setter
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setAdTagUri(@Nullable String adTagUri) {
      return setAdTagUri(adTagUri != null ? Uri.parse(adTagUri) : null);
    }

    /**
     * @deprecated Use {@link #setAdsConfiguration(AdsConfiguration)} and pass the {@code adTagUri}
     *     to {@link AdsConfiguration.Builder#Builder(Uri)} instead.
     */
    @SuppressWarnings("deprecation") // Forwarding to other deprecated setter
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setAdTagUri(@Nullable Uri adTagUri) {
      return setAdTagUri(adTagUri, /* adsId= */ null);
    }

    /**
     * @deprecated Use {@link #setAdsConfiguration(AdsConfiguration)}, pass the {@code adTagUri} to
     *     {@link AdsConfiguration.Builder#Builder(Uri)} and the {@code adsId} to {@link
     *     AdsConfiguration.Builder#setAdsId(Object)} instead.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setAdTagUri(@Nullable Uri adTagUri, @Nullable Object adsId) {
      this.adsConfiguration =
          adTagUri != null ? new AdsConfiguration.Builder(adTagUri).setAdsId(adsId).build() : null;
      return this;
    }

    /** Sets the {@link LiveConfiguration}. Defaults to {@link LiveConfiguration#UNSET}. */
    @CanIgnoreReturnValue
    public Builder setLiveConfiguration(LiveConfiguration liveConfiguration) {
      this.liveConfiguration = liveConfiguration.buildUpon();
      return this;
    }

    /**
     * @deprecated Use {@link #setLiveConfiguration(LiveConfiguration)} and {@link
     *     LiveConfiguration.Builder#setTargetOffsetMs(long)}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setLiveTargetOffsetMs(long liveTargetOffsetMs) {
      liveConfiguration.setTargetOffsetMs(liveTargetOffsetMs);
      return this;
    }

    /**
     * @deprecated Use {@link #setLiveConfiguration(LiveConfiguration)} and {@link
     *     LiveConfiguration.Builder#setMinOffsetMs(long)}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setLiveMinOffsetMs(long liveMinOffsetMs) {
      liveConfiguration.setMinOffsetMs(liveMinOffsetMs);
      return this;
    }

    /**
     * @deprecated Use {@link #setLiveConfiguration(LiveConfiguration)} and {@link
     *     LiveConfiguration.Builder#setMaxOffsetMs(long)}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setLiveMaxOffsetMs(long liveMaxOffsetMs) {
      liveConfiguration.setMaxOffsetMs(liveMaxOffsetMs);
      return this;
    }

    /**
     * @deprecated Use {@link #setLiveConfiguration(LiveConfiguration)} and {@link
     *     LiveConfiguration.Builder#setMinPlaybackSpeed(float)}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setLiveMinPlaybackSpeed(float minPlaybackSpeed) {
      liveConfiguration.setMinPlaybackSpeed(minPlaybackSpeed);
      return this;
    }

    /**
     * @deprecated Use {@link #setLiveConfiguration(LiveConfiguration)} and {@link
     *     LiveConfiguration.Builder#setMaxPlaybackSpeed(float)}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    @Deprecated
    public Builder setLiveMaxPlaybackSpeed(float maxPlaybackSpeed) {
      liveConfiguration.setMaxPlaybackSpeed(maxPlaybackSpeed);
      return this;
    }

    /**
     * Sets the optional tag for custom attributes. The tag for the media source which will be
     * published in the {@code androidx.media3.common.Timeline} of the source as {@code
     * androidx.media3.common.Timeline.Window#tag}.
     *
     * <p>This method should only be called if {@link #setUri} is passed a non-null value.
     */
    @CanIgnoreReturnValue
    public Builder setTag(@Nullable Object tag) {
      this.tag = tag;
      return this;
    }

    /**
     * Sets the image duration in video output, in milliseconds.
     *
     * <p>Must be set if {@linkplain #setUri the URI} is set and resolves to an image. Ignored
     * otherwise.
     *
     * <p>Motion photos will be rendered as images if this parameter is set, and as videos
     * otherwise.
     *
     * <p>Default value is {@link C#TIME_UNSET}.
     */
    @CanIgnoreReturnValue
    @UnstableApi
    public Builder setImageDurationMs(long imageDurationMs) {
      checkArgument(imageDurationMs > 0 || imageDurationMs == C.TIME_UNSET);
      this.imageDurationMs = imageDurationMs;
      return this;
    }

    /** Sets the media metadata. */
    @CanIgnoreReturnValue
    public Builder setMediaMetadata(MediaMetadata mediaMetadata) {
      this.mediaMetadata = mediaMetadata;
      return this;
    }

    /** Sets the request metadata. */
    @CanIgnoreReturnValue
    public Builder setRequestMetadata(RequestMetadata requestMetadata) {
      this.requestMetadata = requestMetadata;
      return this;
    }

    /** Returns a new {@link MediaItem} instance with the current builder values. */
    @SuppressWarnings("deprecation") // Building deprecated ClippingProperties type
    public MediaItem build() {
      // TODO: remove this check once all the deprecated individual DRM setters are removed.
      checkState(drmConfiguration.licenseUri == null || drmConfiguration.scheme != null);
      @Nullable LocalConfiguration localConfiguration = null;
      @Nullable Uri uri = this.uri;
      if (uri != null) {
        localConfiguration =
            new LocalConfiguration(
                uri,
                mimeType,
                drmConfiguration.scheme != null ? drmConfiguration.build() : null,
                adsConfiguration,
                streamKeys,
                customCacheKey,
                subtitleConfigurations,
                tag,
                imageDurationMs);
      }
      return new MediaItem(
          mediaId != null ? mediaId : DEFAULT_MEDIA_ID,
          clippingConfiguration.buildClippingProperties(),
          localConfiguration,
          liveConfiguration.build(),
          mediaMetadata != null ? mediaMetadata : MediaMetadata.EMPTY,
          requestMetadata);
    }
  }

  /** DRM configuration for a media item. */
  public static final class DrmConfiguration {
    /** Builder for {@link DrmConfiguration}. */
    public static final class Builder {

      // TODO remove @Nullable annotation when the deprecated zero-arg constructor is removed.
      @Nullable private UUID scheme;
      @Nullable private Uri licenseUri;
      private ImmutableMap<String, String> licenseRequestHeaders;
      private boolean multiSession;
      private boolean playClearContentWithoutKey;
      private boolean forceDefaultLicenseUri;
      private ImmutableList<@C.TrackType Integer> forcedSessionTrackTypes;
      @Nullable private byte[] keySetId;

      /**
       * Constructs an instance.
       *
       * @param scheme The {@link UUID} of the protection scheme.
       */
      @SuppressWarnings("deprecation") // Calling deprecated constructor to reduce code duplication.
      public Builder(UUID scheme) {
        this();
        this.scheme = scheme;
      }

      /**
       * @deprecated This only exists to support the deprecated setters for individual DRM
       *     properties on {@link MediaItem.Builder}.
       */
      @Deprecated
      private Builder() {
        this.licenseRequestHeaders = ImmutableMap.of();
        this.playClearContentWithoutKey = true;
        this.forcedSessionTrackTypes = ImmutableList.of();
      }

      private Builder(DrmConfiguration drmConfiguration) {
        this.scheme = drmConfiguration.scheme;
        this.licenseUri = drmConfiguration.licenseUri;
        this.licenseRequestHeaders = drmConfiguration.licenseRequestHeaders;
        this.multiSession = drmConfiguration.multiSession;
        this.playClearContentWithoutKey = drmConfiguration.playClearContentWithoutKey;
        this.forceDefaultLicenseUri = drmConfiguration.forceDefaultLicenseUri;
        this.forcedSessionTrackTypes = drmConfiguration.forcedSessionTrackTypes;
        this.keySetId = drmConfiguration.keySetId;
      }

      /** Sets the {@link UUID} of the protection scheme. */
      @CanIgnoreReturnValue
      public Builder setScheme(UUID scheme) {
        this.scheme = scheme;
        return this;
      }

      /**
       * @deprecated This only exists to support the deprecated {@link
       *     MediaItem.Builder#setDrmUuid(UUID)}.
       */
      @CanIgnoreReturnValue
      @Deprecated
      private Builder setNullableScheme(@Nullable UUID scheme) {
        this.scheme = scheme;
        return this;
      }

      /** Sets the optional default DRM license server URI. */
      @CanIgnoreReturnValue
      public Builder setLicenseUri(@Nullable Uri licenseUri) {
        this.licenseUri = licenseUri;
        return this;
      }

      /** Sets the optional default DRM license server URI. */
      @CanIgnoreReturnValue
      public Builder setLicenseUri(@Nullable String licenseUri) {
        this.licenseUri = licenseUri == null ? null : Uri.parse(licenseUri);
        return this;
      }

      /** Sets the optional request headers attached to DRM license requests. */
      @CanIgnoreReturnValue
      public Builder setLicenseRequestHeaders(Map<String, String> licenseRequestHeaders) {
        this.licenseRequestHeaders = ImmutableMap.copyOf(licenseRequestHeaders);
        return this;
      }

      /**
       * Sets whether multi session is enabled.
       *
       * <p>The default is {@code false} (multi session disabled).
       */
      @CanIgnoreReturnValue
      public Builder setMultiSession(boolean multiSession) {
        this.multiSession = multiSession;
        return this;
      }

      /**
       * Sets whether to always use the default DRM license server URI even if the media specifies
       * its own DRM license server URI.
       *
       * <p>The default is {@code false}.
       */
      @CanIgnoreReturnValue
      public Builder setForceDefaultLicenseUri(boolean forceDefaultLicenseUri) {
        this.forceDefaultLicenseUri = forceDefaultLicenseUri;
        return this;
      }

      /**
       * Sets whether clear samples within protected content should be played when keys for the
       * encrypted part of the content have yet to be loaded.
       *
       * <p>The default is {@code true}.
       */
      @CanIgnoreReturnValue
      public Builder setPlayClearContentWithoutKey(boolean playClearContentWithoutKey) {
        this.playClearContentWithoutKey = playClearContentWithoutKey;
        return this;
      }

      /**
       * @deprecated Use {@link #setForceSessionsForAudioAndVideoTracks(boolean)} instead.
       */
      @CanIgnoreReturnValue
      @UnstableApi
      @Deprecated
      @InlineMe(
          replacement =
              "this.setForceSessionsForAudioAndVideoTracks(forceSessionsForAudioAndVideoTracks)")
      public Builder forceSessionsForAudioAndVideoTracks(
          boolean forceSessionsForAudioAndVideoTracks) {
        return setForceSessionsForAudioAndVideoTracks(forceSessionsForAudioAndVideoTracks);
      }

      /**
       * Sets whether a DRM session should be used for clear tracks of type {@link
       * C#TRACK_TYPE_VIDEO} and {@link C#TRACK_TYPE_AUDIO}.
       *
       * <p>This method overrides what has been set by previously calling {@link
       * #setForcedSessionTrackTypes(List)}.
       *
       * <p>The default is {@code false}.
       */
      @CanIgnoreReturnValue
      public Builder setForceSessionsForAudioAndVideoTracks(
          boolean forceSessionsForAudioAndVideoTracks) {
        this.setForcedSessionTrackTypes(
            forceSessionsForAudioAndVideoTracks
                ? ImmutableList.of(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                : ImmutableList.of());
        return this;
      }

      /**
       * Sets a list of {@link C.TrackType track type} constants for which to use a DRM session even
       * when the tracks are in the clear.
       *
       * <p>For the common case of using a DRM session for {@link C#TRACK_TYPE_VIDEO} and {@link
       * C#TRACK_TYPE_AUDIO}, {@link #setForceSessionsForAudioAndVideoTracks(boolean)} can be used.
       *
       * <p>This method overrides what has been set by previously calling {@link
       * #setForceSessionsForAudioAndVideoTracks(boolean)}.
       *
       * <p>The default is an empty list (i.e. DRM sessions are not forced for any track type).
       */
      @CanIgnoreReturnValue
      public Builder setForcedSessionTrackTypes(
          List<@C.TrackType Integer> forcedSessionTrackTypes) {
        this.forcedSessionTrackTypes = ImmutableList.copyOf(forcedSessionTrackTypes);
        return this;
      }

      /**
       * Sets the key set ID of the offline license.
       *
       * <p>The key set ID identifies an offline license. The ID is required to query, renew or
       * release an existing offline license (see {@code DefaultDrmSessionManager#setMode(int
       * mode,byte[] offlineLicenseKeySetId)}).
       */
      @CanIgnoreReturnValue
      public Builder setKeySetId(@Nullable byte[] keySetId) {
        this.keySetId = keySetId != null ? Arrays.copyOf(keySetId, keySetId.length) : null;
        return this;
      }

      public DrmConfiguration build() {
        return new DrmConfiguration(this);
      }
    }

    /** The UUID of the protection scheme. */
    public final UUID scheme;

    /**
     * @deprecated Use {@link #scheme} instead.
     */
    @UnstableApi @Deprecated public final UUID uuid;

    /**
     * Optional default DRM license server {@link Uri}. If {@code null} then the DRM license server
     * must be specified by the media.
     */
    @Nullable public final Uri licenseUri;

    /**
     * @deprecated Use {@link #licenseRequestHeaders} instead.
     */
    @UnstableApi @Deprecated public final ImmutableMap<String, String> requestHeaders;

    /** The headers to attach to requests sent to the DRM license server. */
    public final ImmutableMap<String, String> licenseRequestHeaders;

    /** Whether the DRM configuration is multi session enabled. */
    public final boolean multiSession;

    /**
     * Whether clear samples within protected content should be played when keys for the encrypted
     * part of the content have yet to be loaded.
     */
    public final boolean playClearContentWithoutKey;

    /**
     * Whether to force use of {@link #licenseUri} even if the media specifies its own DRM license
     * server URI.
     */
    public final boolean forceDefaultLicenseUri;

    /**
     * @deprecated Use {@link #forcedSessionTrackTypes}.
     */
    @UnstableApi @Deprecated public final ImmutableList<@C.TrackType Integer> sessionForClearTypes;

    /**
     * The types of tracks for which to always use a DRM session even if the content is unencrypted.
     */
    public final ImmutableList<@C.TrackType Integer> forcedSessionTrackTypes;

    @Nullable private final byte[] keySetId;

    @SuppressWarnings("deprecation") // Setting deprecated field
    private DrmConfiguration(Builder builder) {
      checkState(!(builder.forceDefaultLicenseUri && builder.licenseUri == null));
      this.scheme = checkNotNull(builder.scheme);
      this.uuid = scheme;
      this.licenseUri = builder.licenseUri;
      this.requestHeaders = builder.licenseRequestHeaders;
      this.licenseRequestHeaders = builder.licenseRequestHeaders;
      this.multiSession = builder.multiSession;
      this.forceDefaultLicenseUri = builder.forceDefaultLicenseUri;
      this.playClearContentWithoutKey = builder.playClearContentWithoutKey;
      this.sessionForClearTypes = builder.forcedSessionTrackTypes;
      this.forcedSessionTrackTypes = builder.forcedSessionTrackTypes;
      this.keySetId =
          builder.keySetId != null
              ? Arrays.copyOf(builder.keySetId, builder.keySetId.length)
              : null;
    }

    /** Returns the key set ID of the offline license. */
    @Nullable
    public byte[] getKeySetId() {
      return keySetId != null ? Arrays.copyOf(keySetId, keySetId.length) : null;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof DrmConfiguration)) {
        return false;
      }

      DrmConfiguration other = (DrmConfiguration) obj;
      return scheme.equals(other.scheme)
          && Objects.equals(licenseUri, other.licenseUri)
          && Objects.equals(licenseRequestHeaders, other.licenseRequestHeaders)
          && multiSession == other.multiSession
          && forceDefaultLicenseUri == other.forceDefaultLicenseUri
          && playClearContentWithoutKey == other.playClearContentWithoutKey
          && forcedSessionTrackTypes.equals(other.forcedSessionTrackTypes)
          && Arrays.equals(keySetId, other.keySetId);
    }

    @Override
    public int hashCode() {
      int result = scheme.hashCode();
      result = 31 * result + (licenseUri != null ? licenseUri.hashCode() : 0);
      result = 31 * result + licenseRequestHeaders.hashCode();
      result = 31 * result + (multiSession ? 1 : 0);
      result = 31 * result + (forceDefaultLicenseUri ? 1 : 0);
      result = 31 * result + (playClearContentWithoutKey ? 1 : 0);
      result = 31 * result + forcedSessionTrackTypes.hashCode();
      result = 31 * result + Arrays.hashCode(keySetId);
      return result;
    }

    private static final String FIELD_SCHEME = Util.intToStringMaxRadix(0);
    private static final String FIELD_LICENSE_URI = Util.intToStringMaxRadix(1);
    private static final String FIELD_LICENSE_REQUEST_HEADERS = Util.intToStringMaxRadix(2);
    private static final String FIELD_MULTI_SESSION = Util.intToStringMaxRadix(3);

    @VisibleForTesting
    static final String FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY = Util.intToStringMaxRadix(4);

    private static final String FIELD_FORCE_DEFAULT_LICENSE_URI = Util.intToStringMaxRadix(5);
    private static final String FIELD_FORCED_SESSION_TRACK_TYPES = Util.intToStringMaxRadix(6);
    private static final String FIELD_KEY_SET_ID = Util.intToStringMaxRadix(7);

    /** Restores a {@code DrmConfiguration} from a {@link Bundle}. */
    @UnstableApi
    public static DrmConfiguration fromBundle(Bundle bundle) {
      UUID scheme = UUID.fromString(checkNotNull(bundle.getString(FIELD_SCHEME)));
      @Nullable Uri licenseUri = bundle.getParcelable(FIELD_LICENSE_URI);
      Bundle licenseMapAsBundle =
          BundleCollectionUtil.getBundleWithDefault(
              bundle, FIELD_LICENSE_REQUEST_HEADERS, Bundle.EMPTY);
      ImmutableMap<String, String> licenseRequestHeaders =
          BundleCollectionUtil.bundleToStringImmutableMap(licenseMapAsBundle);
      boolean multiSession = bundle.getBoolean(FIELD_MULTI_SESSION, false);
      boolean playClearContentWithoutKey =
          bundle.getBoolean(FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY, false);
      boolean forceDefaultLicenseUri = bundle.getBoolean(FIELD_FORCE_DEFAULT_LICENSE_URI, false);
      ArrayList<@C.TrackType Integer> forcedSessionTrackTypesArray =
          BundleCollectionUtil.getIntegerArrayListWithDefault(
              bundle, FIELD_FORCED_SESSION_TRACK_TYPES, new ArrayList<>());
      ImmutableList<@C.TrackType Integer> forcedSessionTrackTypes =
          ImmutableList.copyOf(forcedSessionTrackTypesArray);
      @Nullable byte[] keySetId = bundle.getByteArray(FIELD_KEY_SET_ID);

      Builder builder = new Builder(scheme);
      return builder
          .setLicenseUri(licenseUri)
          .setLicenseRequestHeaders(licenseRequestHeaders)
          .setMultiSession(multiSession)
          .setForceDefaultLicenseUri(forceDefaultLicenseUri)
          .setPlayClearContentWithoutKey(playClearContentWithoutKey)
          .setForcedSessionTrackTypes(forcedSessionTrackTypes)
          .setKeySetId(keySetId)
          .build();
    }

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putString(FIELD_SCHEME, scheme.toString());
      if (licenseUri != null) {
        bundle.putParcelable(FIELD_LICENSE_URI, licenseUri);
      }
      if (!licenseRequestHeaders.isEmpty()) {
        bundle.putBundle(
            FIELD_LICENSE_REQUEST_HEADERS,
            BundleCollectionUtil.stringMapToBundle(licenseRequestHeaders));
      }
      if (multiSession) {
        bundle.putBoolean(FIELD_MULTI_SESSION, multiSession);
      }
      if (playClearContentWithoutKey) {
        bundle.putBoolean(FIELD_PLAY_CLEAR_CONTENT_WITHOUT_KEY, playClearContentWithoutKey);
      }
      if (forceDefaultLicenseUri) {
        bundle.putBoolean(FIELD_FORCE_DEFAULT_LICENSE_URI, forceDefaultLicenseUri);
      }
      if (!forcedSessionTrackTypes.isEmpty()) {
        bundle.putIntegerArrayList(
            FIELD_FORCED_SESSION_TRACK_TYPES, new ArrayList<>(forcedSessionTrackTypes));
      }
      if (keySetId != null) {
        bundle.putByteArray(FIELD_KEY_SET_ID, keySetId);
      }
      return bundle;
    }
  }

  /** Configuration for playing back linear ads with a media item. */
  public static final class AdsConfiguration {

    /** Builder for {@link AdsConfiguration} instances. */
    public static final class Builder {

      private Uri adTagUri;
      @Nullable private Object adsId;

      /**
       * Constructs a new instance.
       *
       * @param adTagUri The ad tag URI to load.
       */
      public Builder(Uri adTagUri) {
        this.adTagUri = adTagUri;
      }

      /** Sets the ad tag URI to load. */
      @CanIgnoreReturnValue
      public Builder setAdTagUri(Uri adTagUri) {
        this.adTagUri = adTagUri;
        return this;
      }

      /**
       * Sets the ads identifier.
       *
       * <p>See details on {@link AdsConfiguration#adsId} for how the ads identifier is used and how
       * it's calculated if not explicitly set.
       */
      @CanIgnoreReturnValue
      public Builder setAdsId(@Nullable Object adsId) {
        this.adsId = adsId;
        return this;
      }

      public AdsConfiguration build() {
        return new AdsConfiguration(this);
      }
    }

    /** The ad tag URI to load. */
    public final Uri adTagUri;

    /**
     * An opaque identifier for ad playback state associated with this item, or {@code null} if the
     * combination of the {@link MediaItem.Builder#setMediaId(String) media ID} and {@link #adTagUri
     * ad tag URI} should be used as the ads identifier.
     *
     * <p>Media items in the playlist that have the same ads identifier and ads loader share the
     * same ad playback state. To resume ad playback when recreating the playlist on returning from
     * the background, pass the same ads identifiers to the player.
     */
    @Nullable public final Object adsId;

    private AdsConfiguration(Builder builder) {
      this.adTagUri = builder.adTagUri;
      this.adsId = builder.adsId;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(adTagUri).setAdsId(adsId);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof AdsConfiguration)) {
        return false;
      }

      AdsConfiguration other = (AdsConfiguration) obj;
      return adTagUri.equals(other.adTagUri) && Objects.equals(adsId, other.adsId);
    }

    @Override
    public int hashCode() {
      int result = adTagUri.hashCode();
      result = 31 * result + (adsId != null ? adsId.hashCode() : 0);
      return result;
    }

    private static final String FIELD_AD_TAG_URI = Util.intToStringMaxRadix(0);

    /** Restores a {@code AdsConfiguration} from a {@link Bundle}. */
    @UnstableApi
    public static AdsConfiguration fromBundle(Bundle bundle) {
      @Nullable Uri adTagUri = bundle.getParcelable(FIELD_AD_TAG_URI);
      checkNotNull(adTagUri);
      return new AdsConfiguration.Builder(adTagUri).build();
    }

    /**
     * Returns a {@link Bundle} representing the information stored in this object.
     *
     * <p>It omits the {@link #adsId} field. The {@link #adsId} of an instance restored from such a
     * bundle by {@link #fromBundle} will be {@code null}.
     */
    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putParcelable(FIELD_AD_TAG_URI, adTagUri);
      return bundle;
    }
  }

  /** Properties for local playback. */
  public static final class LocalConfiguration {

    /** The {@link Uri}. */
    public final Uri uri;

    /**
     * The optional MIME type of the item, or {@code null} if unspecified.
     *
     * <p>The MIME type can be used to disambiguate media items that have a URI which does not allow
     * to infer the actual media type.
     */
    @Nullable public final String mimeType;

    /** Optional {@link DrmConfiguration} for the media. */
    @Nullable public final DrmConfiguration drmConfiguration;

    /** Optional ads configuration. */
    @Nullable public final AdsConfiguration adsConfiguration;

    /** Optional stream keys by which the manifest is filtered. */
    @UnstableApi public final List<StreamKey> streamKeys;

    /** Optional custom cache key (only used for progressive streams). */
    @UnstableApi @Nullable public final String customCacheKey;

    /** Optional subtitles to be sideloaded. */
    public final ImmutableList<SubtitleConfiguration> subtitleConfigurations;

    /**
     * @deprecated Use {@link #subtitleConfigurations} instead.
     */
    @SuppressWarnings("deprecation") // Using deprecated type in deprecated field
    @UnstableApi
    @Deprecated
    public final List<Subtitle> subtitles;

    /**
     * Optional tag for custom attributes. The tag for the media source which will be published in
     * the {@code androidx.media3.common.Timeline} of the source as {@code
     * androidx.media3.common.Timeline.Window#tag}.
     */
    @Nullable public final Object tag;

    /** Duration for image assets in milliseconds. */
    @UnstableApi public final long imageDurationMs;

    @SuppressWarnings("deprecation") // Setting deprecated subtitles field.
    private LocalConfiguration(
        Uri uri,
        @Nullable String mimeType,
        @Nullable DrmConfiguration drmConfiguration,
        @Nullable AdsConfiguration adsConfiguration,
        List<StreamKey> streamKeys,
        @Nullable String customCacheKey,
        ImmutableList<SubtitleConfiguration> subtitleConfigurations,
        @Nullable Object tag,
        long imageDurationMs) {
      this.uri = uri;
      this.mimeType = MimeTypes.normalizeMimeType(mimeType);
      this.drmConfiguration = drmConfiguration;
      this.adsConfiguration = adsConfiguration;
      this.streamKeys = streamKeys;
      this.customCacheKey = customCacheKey;
      this.subtitleConfigurations = subtitleConfigurations;
      ImmutableList.Builder<Subtitle> subtitles = ImmutableList.builder();
      for (int i = 0; i < subtitleConfigurations.size(); i++) {
        subtitles.add(subtitleConfigurations.get(i).buildUpon().buildSubtitle());
      }
      this.subtitles = subtitles.build();
      this.tag = tag;
      this.imageDurationMs = imageDurationMs;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof LocalConfiguration)) {
        return false;
      }
      LocalConfiguration other = (LocalConfiguration) obj;

      return uri.equals(other.uri)
          && Objects.equals(mimeType, other.mimeType)
          && Objects.equals(drmConfiguration, other.drmConfiguration)
          && Objects.equals(adsConfiguration, other.adsConfiguration)
          && streamKeys.equals(other.streamKeys)
          && Objects.equals(customCacheKey, other.customCacheKey)
          && subtitleConfigurations.equals(other.subtitleConfigurations)
          && Objects.equals(tag, other.tag)
          && imageDurationMs == other.imageDurationMs;
    }

    @Override
    public int hashCode() {
      int result = uri.hashCode();
      result = 31 * result + (mimeType == null ? 0 : mimeType.hashCode());
      result = 31 * result + (drmConfiguration == null ? 0 : drmConfiguration.hashCode());
      result = 31 * result + (adsConfiguration == null ? 0 : adsConfiguration.hashCode());
      result = 31 * result + streamKeys.hashCode();
      result = 31 * result + (customCacheKey == null ? 0 : customCacheKey.hashCode());
      result = 31 * result + subtitleConfigurations.hashCode();
      result = 31 * result + (tag == null ? 0 : tag.hashCode());
      result = (int) (31L * result + imageDurationMs);
      return result;
    }

    private static final String FIELD_URI = Util.intToStringMaxRadix(0);
    private static final String FIELD_MIME_TYPE = Util.intToStringMaxRadix(1);
    private static final String FIELD_DRM_CONFIGURATION = Util.intToStringMaxRadix(2);
    private static final String FIELD_ADS_CONFIGURATION = Util.intToStringMaxRadix(3);
    private static final String FIELD_STREAM_KEYS = Util.intToStringMaxRadix(4);
    private static final String FIELD_CUSTOM_CACHE_KEY = Util.intToStringMaxRadix(5);
    private static final String FIELD_SUBTITLE_CONFIGURATION = Util.intToStringMaxRadix(6);
    private static final String FIELD_IMAGE_DURATION_MS = Util.intToStringMaxRadix(7);

    /**
     * Returns a {@link Bundle} representing the information stored in this object.
     *
     * <p>It omits the {@link #tag} field. The {@link #tag} of an instance restored from such a
     * bundle by {@link #fromBundle} will be {@code null}.
     */
    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putParcelable(FIELD_URI, uri);
      if (mimeType != null) {
        bundle.putString(FIELD_MIME_TYPE, mimeType);
      }
      if (drmConfiguration != null) {
        bundle.putBundle(FIELD_DRM_CONFIGURATION, drmConfiguration.toBundle());
      }
      if (adsConfiguration != null) {
        bundle.putBundle(FIELD_ADS_CONFIGURATION, adsConfiguration.toBundle());
      }
      if (!streamKeys.isEmpty()) {
        bundle.putParcelableArrayList(
            FIELD_STREAM_KEYS,
            BundleCollectionUtil.toBundleArrayList(streamKeys, StreamKey::toBundle));
      }
      if (customCacheKey != null) {
        bundle.putString(FIELD_CUSTOM_CACHE_KEY, customCacheKey);
      }
      if (!subtitleConfigurations.isEmpty()) {
        bundle.putParcelableArrayList(
            FIELD_SUBTITLE_CONFIGURATION,
            BundleCollectionUtil.toBundleArrayList(
                subtitleConfigurations, SubtitleConfiguration::toBundle));
      }
      if (imageDurationMs != C.TIME_UNSET) {
        bundle.putLong(FIELD_IMAGE_DURATION_MS, imageDurationMs);
      }
      return bundle;
    }

    /** Restores a {@code LocalConfiguration} from a {@link Bundle}. */
    @UnstableApi
    public static LocalConfiguration fromBundle(Bundle bundle) {
      @Nullable Bundle drmBundle = bundle.getBundle(FIELD_DRM_CONFIGURATION);
      DrmConfiguration drmConfiguration =
          drmBundle == null ? null : DrmConfiguration.fromBundle(drmBundle);
      @Nullable Bundle adsBundle = bundle.getBundle(FIELD_ADS_CONFIGURATION);
      AdsConfiguration adsConfiguration =
          adsBundle == null ? null : AdsConfiguration.fromBundle(adsBundle);
      @Nullable List<Bundle> streamKeysBundles = bundle.getParcelableArrayList(FIELD_STREAM_KEYS);
      List<StreamKey> streamKeys =
          streamKeysBundles == null
              ? ImmutableList.of()
              : BundleCollectionUtil.fromBundleList(StreamKey::fromBundle, streamKeysBundles);
      @Nullable
      List<Bundle> subtitleBundles = bundle.getParcelableArrayList(FIELD_SUBTITLE_CONFIGURATION);
      ImmutableList<SubtitleConfiguration> subtitleConfiguration =
          subtitleBundles == null
              ? ImmutableList.of()
              : BundleCollectionUtil.fromBundleList(
                  SubtitleConfiguration::fromBundle, subtitleBundles);
      long imageDurationMs = bundle.getLong(FIELD_IMAGE_DURATION_MS, C.TIME_UNSET);

      return new LocalConfiguration(
          checkNotNull(bundle.getParcelable(FIELD_URI)),
          bundle.getString(FIELD_MIME_TYPE),
          drmConfiguration,
          adsConfiguration,
          streamKeys,
          bundle.getString(FIELD_CUSTOM_CACHE_KEY),
          subtitleConfiguration,
          /* tag= */ null,
          imageDurationMs);
    }
  }

  /** Live playback configuration. */
  public static final class LiveConfiguration {

    /** Builder for {@link LiveConfiguration} instances. */
    public static final class Builder {
      private long targetOffsetMs;
      private long minOffsetMs;
      private long maxOffsetMs;
      private float minPlaybackSpeed;
      private float maxPlaybackSpeed;

      /** Creates a new instance with default values. */
      public Builder() {
        this.targetOffsetMs = C.TIME_UNSET;
        this.minOffsetMs = C.TIME_UNSET;
        this.maxOffsetMs = C.TIME_UNSET;
        this.minPlaybackSpeed = C.RATE_UNSET;
        this.maxPlaybackSpeed = C.RATE_UNSET;
      }

      private Builder(LiveConfiguration liveConfiguration) {
        this.targetOffsetMs = liveConfiguration.targetOffsetMs;
        this.minOffsetMs = liveConfiguration.minOffsetMs;
        this.maxOffsetMs = liveConfiguration.maxOffsetMs;
        this.minPlaybackSpeed = liveConfiguration.minPlaybackSpeed;
        this.maxPlaybackSpeed = liveConfiguration.maxPlaybackSpeed;
      }

      /**
       * Sets the target live offset, in milliseconds.
       *
       * <p>See {@code Player#getCurrentLiveOffset()}.
       *
       * <p>Defaults to {@link C#TIME_UNSET}, indicating the media-defined default will be used.
       */
      @CanIgnoreReturnValue
      public Builder setTargetOffsetMs(long targetOffsetMs) {
        this.targetOffsetMs = targetOffsetMs;
        return this;
      }

      /**
       * Sets the minimum allowed live offset, in milliseconds.
       *
       * <p>See {@code Player#getCurrentLiveOffset()}.
       *
       * <p>Defaults to {@link C#TIME_UNSET}, indicating the media-defined default will be used.
       */
      @CanIgnoreReturnValue
      public Builder setMinOffsetMs(long minOffsetMs) {
        this.minOffsetMs = minOffsetMs;
        return this;
      }

      /**
       * Sets the maximum allowed live offset, in milliseconds.
       *
       * <p>See {@code Player#getCurrentLiveOffset()}.
       *
       * <p>Defaults to {@link C#TIME_UNSET}, indicating the media-defined default will be used.
       */
      @CanIgnoreReturnValue
      public Builder setMaxOffsetMs(long maxOffsetMs) {
        this.maxOffsetMs = maxOffsetMs;
        return this;
      }

      /**
       * Sets the minimum playback speed.
       *
       * <p>Defaults to {@link C#RATE_UNSET}, indicating the media-defined default will be used.
       */
      @CanIgnoreReturnValue
      public Builder setMinPlaybackSpeed(float minPlaybackSpeed) {
        this.minPlaybackSpeed = minPlaybackSpeed;
        return this;
      }

      /**
       * Sets the maximum playback speed.
       *
       * <p>Defaults to {@link C#RATE_UNSET}, indicating the media-defined default will be used.
       */
      @CanIgnoreReturnValue
      public Builder setMaxPlaybackSpeed(float maxPlaybackSpeed) {
        this.maxPlaybackSpeed = maxPlaybackSpeed;
        return this;
      }

      /** Creates a {@link LiveConfiguration} with the values from this builder. */
      public LiveConfiguration build() {
        return new LiveConfiguration(this);
      }
    }

    /**
     * A live playback configuration with unset values, meaning media-defined default values will be
     * used.
     */
    public static final LiveConfiguration UNSET = new LiveConfiguration.Builder().build();

    /**
     * Target offset from the live edge, in milliseconds, or {@link C#TIME_UNSET} to use the
     * media-defined default.
     */
    public final long targetOffsetMs;

    /**
     * The minimum allowed offset from the live edge, in milliseconds, or {@link C#TIME_UNSET} to
     * use the media-defined default.
     */
    public final long minOffsetMs;

    /**
     * The maximum allowed offset from the live edge, in milliseconds, or {@link C#TIME_UNSET} to
     * use the media-defined default.
     */
    public final long maxOffsetMs;

    /**
     * Minimum factor by which playback can be sped up, or {@link C#RATE_UNSET} to use the
     * media-defined default.
     */
    public final float minPlaybackSpeed;

    /**
     * Maximum factor by which playback can be sped up, or {@link C#RATE_UNSET} to use the
     * media-defined default.
     */
    public final float maxPlaybackSpeed;

    @SuppressWarnings("deprecation") // Using the deprecated constructor while it exists.
    private LiveConfiguration(Builder builder) {
      this(
          builder.targetOffsetMs,
          builder.minOffsetMs,
          builder.maxOffsetMs,
          builder.minPlaybackSpeed,
          builder.maxPlaybackSpeed);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @UnstableApi
    @Deprecated
    public LiveConfiguration(
        long targetOffsetMs,
        long minOffsetMs,
        long maxOffsetMs,
        float minPlaybackSpeed,
        float maxPlaybackSpeed) {
      this.targetOffsetMs = targetOffsetMs;
      this.minOffsetMs = minOffsetMs;
      this.maxOffsetMs = maxOffsetMs;
      this.minPlaybackSpeed = minPlaybackSpeed;
      this.maxPlaybackSpeed = maxPlaybackSpeed;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof LiveConfiguration)) {
        return false;
      }
      LiveConfiguration other = (LiveConfiguration) obj;

      return targetOffsetMs == other.targetOffsetMs
          && minOffsetMs == other.minOffsetMs
          && maxOffsetMs == other.maxOffsetMs
          && minPlaybackSpeed == other.minPlaybackSpeed
          && maxPlaybackSpeed == other.maxPlaybackSpeed;
    }

    @Override
    public int hashCode() {
      int result = (int) (targetOffsetMs ^ (targetOffsetMs >>> 32));
      result = 31 * result + (int) (minOffsetMs ^ (minOffsetMs >>> 32));
      result = 31 * result + (int) (maxOffsetMs ^ (maxOffsetMs >>> 32));
      result = 31 * result + (minPlaybackSpeed != 0 ? Float.floatToIntBits(minPlaybackSpeed) : 0);
      result = 31 * result + (maxPlaybackSpeed != 0 ? Float.floatToIntBits(maxPlaybackSpeed) : 0);
      return result;
    }

    private static final String FIELD_TARGET_OFFSET_MS = Util.intToStringMaxRadix(0);
    private static final String FIELD_MIN_OFFSET_MS = Util.intToStringMaxRadix(1);
    private static final String FIELD_MAX_OFFSET_MS = Util.intToStringMaxRadix(2);
    private static final String FIELD_MIN_PLAYBACK_SPEED = Util.intToStringMaxRadix(3);
    private static final String FIELD_MAX_PLAYBACK_SPEED = Util.intToStringMaxRadix(4);

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      if (targetOffsetMs != UNSET.targetOffsetMs) {
        bundle.putLong(FIELD_TARGET_OFFSET_MS, targetOffsetMs);
      }
      if (minOffsetMs != UNSET.minOffsetMs) {
        bundle.putLong(FIELD_MIN_OFFSET_MS, minOffsetMs);
      }
      if (maxOffsetMs != UNSET.maxOffsetMs) {
        bundle.putLong(FIELD_MAX_OFFSET_MS, maxOffsetMs);
      }
      if (minPlaybackSpeed != UNSET.minPlaybackSpeed) {
        bundle.putFloat(FIELD_MIN_PLAYBACK_SPEED, minPlaybackSpeed);
      }
      if (maxPlaybackSpeed != UNSET.maxPlaybackSpeed) {
        bundle.putFloat(FIELD_MAX_PLAYBACK_SPEED, maxPlaybackSpeed);
      }
      return bundle;
    }

    /** Restores a {@code LiveConfiguration} from a {@link Bundle}. */
    @UnstableApi
    public static LiveConfiguration fromBundle(Bundle bundle) {
      return new LiveConfiguration.Builder()
          .setTargetOffsetMs(
              bundle.getLong(FIELD_TARGET_OFFSET_MS, /* defaultValue= */ UNSET.targetOffsetMs))
          .setMinOffsetMs(
              bundle.getLong(FIELD_MIN_OFFSET_MS, /* defaultValue= */ UNSET.minOffsetMs))
          .setMaxOffsetMs(
              bundle.getLong(FIELD_MAX_OFFSET_MS, /* defaultValue= */ UNSET.maxOffsetMs))
          .setMinPlaybackSpeed(
              bundle.getFloat(FIELD_MIN_PLAYBACK_SPEED, /* defaultValue= */ UNSET.minPlaybackSpeed))
          .setMaxPlaybackSpeed(
              bundle.getFloat(FIELD_MAX_PLAYBACK_SPEED, /* defaultValue= */ UNSET.maxPlaybackSpeed))
          .build();
    }
  }

  /** Properties for a text track. */
  // TODO: Mark this final when Subtitle is deleted.
  public static class SubtitleConfiguration {

    /** Builder for {@link SubtitleConfiguration} instances. */
    public static final class Builder {
      private Uri uri;
      @Nullable private String mimeType;
      @Nullable private String language;
      private @C.SelectionFlags int selectionFlags;
      private @C.RoleFlags int roleFlags;
      @Nullable private String label;
      @Nullable private String id;

      /**
       * Constructs an instance.
       *
       * @param uri The {@link Uri} to the subtitle file.
       */
      public Builder(Uri uri) {
        this.uri = uri;
      }

      private Builder(SubtitleConfiguration subtitleConfiguration) {
        this.uri = subtitleConfiguration.uri;
        this.mimeType = subtitleConfiguration.mimeType;
        this.language = subtitleConfiguration.language;
        this.selectionFlags = subtitleConfiguration.selectionFlags;
        this.roleFlags = subtitleConfiguration.roleFlags;
        this.label = subtitleConfiguration.label;
        this.id = subtitleConfiguration.id;
      }

      /** Sets the {@link Uri} to the subtitle file. */
      @CanIgnoreReturnValue
      public Builder setUri(Uri uri) {
        this.uri = uri;
        return this;
      }

      /** Sets the MIME type. */
      @CanIgnoreReturnValue
      public Builder setMimeType(@Nullable String mimeType) {
        this.mimeType = MimeTypes.normalizeMimeType(mimeType);
        return this;
      }

      /** Sets the optional language of the subtitle file. */
      @CanIgnoreReturnValue
      public Builder setLanguage(@Nullable String language) {
        this.language = language;
        return this;
      }

      /** Sets the flags used for track selection. */
      @CanIgnoreReturnValue
      public Builder setSelectionFlags(@C.SelectionFlags int selectionFlags) {
        this.selectionFlags = selectionFlags;
        return this;
      }

      /** Sets the role flags. These are used for track selection. */
      @CanIgnoreReturnValue
      public Builder setRoleFlags(@C.RoleFlags int roleFlags) {
        this.roleFlags = roleFlags;
        return this;
      }

      /** Sets the optional label for this subtitle track. */
      @CanIgnoreReturnValue
      public Builder setLabel(@Nullable String label) {
        this.label = label;
        return this;
      }

      /** Sets the optional ID for this subtitle track. */
      @CanIgnoreReturnValue
      public Builder setId(@Nullable String id) {
        this.id = id;
        return this;
      }

      /** Creates a {@link SubtitleConfiguration} from the values of this builder. */
      public SubtitleConfiguration build() {
        return new SubtitleConfiguration(this);
      }

      @SuppressWarnings("deprecation") // Building deprecated type to support deprecated builder
      private Subtitle buildSubtitle() {
        return new Subtitle(this);
      }
    }

    /** The {@link Uri} to the subtitle file. */
    public final Uri uri;

    /** The optional MIME type of the subtitle file, or {@code null} if unspecified. */
    @Nullable public final String mimeType;

    /** The language. */
    @Nullable public final String language;

    /** The selection flags. */
    public final @C.SelectionFlags int selectionFlags;

    /** The role flags. */
    public final @C.RoleFlags int roleFlags;

    /** The label. */
    @Nullable public final String label;

    /**
     * The ID of the subtitles. This will be propagated to the {@link Format#id} of the subtitle
     * track created from this configuration.
     */
    @Nullable public final String id;

    private SubtitleConfiguration(
        Uri uri,
        String mimeType,
        @Nullable String language,
        int selectionFlags,
        int roleFlags,
        @Nullable String label,
        @Nullable String id) {
      this.uri = uri;
      this.mimeType = MimeTypes.normalizeMimeType(mimeType);
      this.language = language;
      this.selectionFlags = selectionFlags;
      this.roleFlags = roleFlags;
      this.label = label;
      this.id = id;
    }

    private SubtitleConfiguration(Builder builder) {
      this.uri = builder.uri;
      this.mimeType = builder.mimeType;
      this.language = builder.language;
      this.selectionFlags = builder.selectionFlags;
      this.roleFlags = builder.roleFlags;
      this.label = builder.label;
      this.id = builder.id;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof SubtitleConfiguration)) {
        return false;
      }

      SubtitleConfiguration other = (SubtitleConfiguration) obj;

      return uri.equals(other.uri)
          && Objects.equals(mimeType, other.mimeType)
          && Objects.equals(language, other.language)
          && selectionFlags == other.selectionFlags
          && roleFlags == other.roleFlags
          && Objects.equals(label, other.label)
          && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
      int result = uri.hashCode();
      result = 31 * result + (mimeType == null ? 0 : mimeType.hashCode());
      result = 31 * result + (language == null ? 0 : language.hashCode());
      result = 31 * result + selectionFlags;
      result = 31 * result + roleFlags;
      result = 31 * result + (label == null ? 0 : label.hashCode());
      result = 31 * result + (id == null ? 0 : id.hashCode());
      return result;
    }

    private static final String FIELD_URI = Util.intToStringMaxRadix(0);
    private static final String FIELD_MIME_TYPE = Util.intToStringMaxRadix(1);
    private static final String FIELD_LANGUAGE = Util.intToStringMaxRadix(2);
    private static final String FIELD_SELECTION_FLAGS = Util.intToStringMaxRadix(3);
    private static final String FIELD_ROLE_FLAGS = Util.intToStringMaxRadix(4);
    private static final String FIELD_LABEL = Util.intToStringMaxRadix(5);
    private static final String FIELD_ID = Util.intToStringMaxRadix(6);

    /** Restores a {@code SubtitleConfiguration} from a {@link Bundle}. */
    @UnstableApi
    public static SubtitleConfiguration fromBundle(Bundle bundle) {
      Uri uri = checkNotNull(bundle.getParcelable(FIELD_URI));
      @Nullable String mimeType = bundle.getString(FIELD_MIME_TYPE);
      @Nullable String language = bundle.getString(FIELD_LANGUAGE);
      @C.SelectionFlags int selectionFlags = bundle.getInt(FIELD_SELECTION_FLAGS, 0);
      @C.RoleFlags int roleFlags = bundle.getInt(FIELD_ROLE_FLAGS, 0);
      @Nullable String label = bundle.getString(FIELD_LABEL);
      @Nullable String id = bundle.getString(FIELD_ID);

      SubtitleConfiguration.Builder builder = new SubtitleConfiguration.Builder(uri);
      return builder
          .setMimeType(mimeType)
          .setLanguage(language)
          .setSelectionFlags(selectionFlags)
          .setRoleFlags(roleFlags)
          .setLabel(label)
          .setId(id)
          .build();
    }

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putParcelable(FIELD_URI, uri);
      if (mimeType != null) {
        bundle.putString(FIELD_MIME_TYPE, mimeType);
      }
      if (language != null) {
        bundle.putString(FIELD_LANGUAGE, language);
      }
      if (selectionFlags != 0) {
        bundle.putInt(FIELD_SELECTION_FLAGS, selectionFlags);
      }
      if (roleFlags != 0) {
        bundle.putInt(FIELD_ROLE_FLAGS, roleFlags);
      }
      if (label != null) {
        bundle.putString(FIELD_LABEL, label);
      }
      if (id != null) {
        bundle.putString(FIELD_ID, id);
      }
      return bundle;
    }
  }

  /**
   * @deprecated Use {@link MediaItem.SubtitleConfiguration} instead
   */
  @UnstableApi
  @Deprecated
  public static final class Subtitle extends SubtitleConfiguration {

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @SuppressWarnings("deprecation") // Forwarding to other deprecated constructor
    @UnstableApi
    @Deprecated
    public Subtitle(Uri uri, String mimeType, @Nullable String language) {
      this(uri, mimeType, language, /* selectionFlags= */ 0);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @SuppressWarnings("deprecation") // Forwarding to other deprecated constructor
    @UnstableApi
    @Deprecated
    public Subtitle(
        Uri uri, String mimeType, @Nullable String language, @C.SelectionFlags int selectionFlags) {
      this(uri, mimeType, language, selectionFlags, /* roleFlags= */ 0, /* label= */ null);
    }

    /**
     * @deprecated Use {@link Builder} instead.
     */
    @UnstableApi
    @Deprecated
    public Subtitle(
        Uri uri,
        String mimeType,
        @Nullable String language,
        @C.SelectionFlags int selectionFlags,
        @C.RoleFlags int roleFlags,
        @Nullable String label) {
      super(uri, mimeType, language, selectionFlags, roleFlags, label, /* id= */ null);
    }

    private Subtitle(Builder builder) {
      super(builder);
    }
  }

  /** Optionally clips the media item to a custom start and end position. */
  // TODO: Mark this final when ClippingProperties is deleted.
  public static class ClippingConfiguration {

    /** A clipping configuration with default values. */
    public static final ClippingConfiguration UNSET = new ClippingConfiguration.Builder().build();

    /** Builder for {@link ClippingConfiguration} instances. */
    public static final class Builder {
      private long startPositionUs;
      private long endPositionUs;
      private boolean relativeToLiveWindow;
      private boolean relativeToDefaultPosition;
      private boolean startsAtKeyFrame;

      /** Creates a new instance with default values. */
      public Builder() {
        endPositionUs = C.TIME_END_OF_SOURCE;
      }

      private Builder(ClippingConfiguration clippingConfiguration) {
        startPositionUs = clippingConfiguration.startPositionUs;
        endPositionUs = clippingConfiguration.endPositionUs;
        relativeToLiveWindow = clippingConfiguration.relativeToLiveWindow;
        relativeToDefaultPosition = clippingConfiguration.relativeToDefaultPosition;
        startsAtKeyFrame = clippingConfiguration.startsAtKeyFrame;
      }

      /**
       * Sets the optional start position in milliseconds which must be a value larger than or equal
       * to zero (Default: 0).
       */
      @CanIgnoreReturnValue
      public Builder setStartPositionMs(@IntRange(from = 0) long startPositionMs) {
        return setStartPositionUs(msToUs(startPositionMs));
      }

      /**
       * Sets the optional start position in microseconds which must be a value larger than or equal
       * to zero (Default: 0).
       */
      @UnstableApi
      @CanIgnoreReturnValue
      public Builder setStartPositionUs(@IntRange(from = 0) long startPositionUs) {
        Assertions.checkArgument(startPositionUs >= 0);
        this.startPositionUs = startPositionUs;
        return this;
      }

      /**
       * Sets the optional end position in milliseconds which must be a value larger than or equal
       * to zero, or {@link C#TIME_END_OF_SOURCE} to end when playback reaches the end of media
       * (Default: {@link C#TIME_END_OF_SOURCE}).
       */
      @CanIgnoreReturnValue
      public Builder setEndPositionMs(long endPositionMs) {
        return setEndPositionUs(msToUs(endPositionMs));
      }

      /**
       * Sets the optional end position in milliseconds which must be a value larger than or equal
       * to zero, or {@link C#TIME_END_OF_SOURCE} to end when playback reaches the end of media
       * (Default: {@link C#TIME_END_OF_SOURCE}).
       */
      @UnstableApi
      @CanIgnoreReturnValue
      public Builder setEndPositionUs(long endPositionUs) {
        Assertions.checkArgument(endPositionUs == C.TIME_END_OF_SOURCE || endPositionUs >= 0);
        this.endPositionUs = endPositionUs;
        return this;
      }

      /**
       * Sets whether the start/end positions should move with the live window for live streams. If
       * {@code false}, live streams end when playback reaches the end position in live window seen
       * when the media is first loaded (Default: {@code false}).
       */
      @CanIgnoreReturnValue
      public Builder setRelativeToLiveWindow(boolean relativeToLiveWindow) {
        this.relativeToLiveWindow = relativeToLiveWindow;
        return this;
      }

      /**
       * Sets whether the start position and the end position are relative to the default position
       * in the window (Default: {@code false}).
       */
      @CanIgnoreReturnValue
      public Builder setRelativeToDefaultPosition(boolean relativeToDefaultPosition) {
        this.relativeToDefaultPosition = relativeToDefaultPosition;
        return this;
      }

      /**
       * Sets whether the start point is guaranteed to be a key frame. If {@code false}, the
       * playback transition into the clip may not be seamless (Default: {@code false}).
       */
      @CanIgnoreReturnValue
      public Builder setStartsAtKeyFrame(boolean startsAtKeyFrame) {
        this.startsAtKeyFrame = startsAtKeyFrame;
        return this;
      }

      /**
       * Returns a {@link ClippingConfiguration} instance initialized with the values of this
       * builder.
       */
      public ClippingConfiguration build() {
        return new ClippingConfiguration(this);
      }

      /**
       * @deprecated Use {@link #build()} instead.
       */
      @SuppressWarnings("deprecation") // Building deprecated type to support deprecated methods
      @UnstableApi
      @Deprecated
      public ClippingProperties buildClippingProperties() {
        return new ClippingProperties(this);
      }
    }

    /** The start position in milliseconds. This is a value larger than or equal to zero. */
    @IntRange(from = 0)
    public final long startPositionMs;

    /** The start position in microseconds. This is a value larger than or equal to zero. */
    @UnstableApi
    @IntRange(from = 0)
    public final long startPositionUs;

    /**
     * The end position in milliseconds. This is a value larger than or equal to zero or {@link
     * C#TIME_END_OF_SOURCE} to play to the end of the stream.
     */
    public final long endPositionMs;

    /**
     * The end position in microseconds. This is a value larger than or equal to zero or {@link
     * C#TIME_END_OF_SOURCE} to play to the end of the stream.
     */
    @UnstableApi public final long endPositionUs;

    /**
     * Whether the clipping of active media periods moves with a live window. If {@code false},
     * playback ends when it reaches {@link #endPositionMs}.
     */
    public final boolean relativeToLiveWindow;

    /**
     * Whether {@link #startPositionMs} and {@link #endPositionMs} are relative to the default
     * position.
     */
    public final boolean relativeToDefaultPosition;

    /** Sets whether the start point is guaranteed to be a key frame. */
    public final boolean startsAtKeyFrame;

    private ClippingConfiguration(Builder builder) {
      this.startPositionMs = usToMs(builder.startPositionUs);
      this.endPositionMs = usToMs(builder.endPositionUs);
      this.startPositionUs = builder.startPositionUs;
      this.endPositionUs = builder.endPositionUs;
      this.relativeToLiveWindow = builder.relativeToLiveWindow;
      this.relativeToDefaultPosition = builder.relativeToDefaultPosition;
      this.startsAtKeyFrame = builder.startsAtKeyFrame;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ClippingConfiguration)) {
        return false;
      }

      ClippingConfiguration other = (ClippingConfiguration) obj;

      return startPositionUs == other.startPositionUs
          && endPositionUs == other.endPositionUs
          && relativeToLiveWindow == other.relativeToLiveWindow
          && relativeToDefaultPosition == other.relativeToDefaultPosition
          && startsAtKeyFrame == other.startsAtKeyFrame;
    }

    @Override
    public int hashCode() {
      int result = (int) (startPositionUs ^ (startPositionUs >>> 32));
      result = 31 * result + (int) (endPositionUs ^ (endPositionUs >>> 32));
      result = 31 * result + (relativeToLiveWindow ? 1 : 0);
      result = 31 * result + (relativeToDefaultPosition ? 1 : 0);
      result = 31 * result + (startsAtKeyFrame ? 1 : 0);
      return result;
    }

    private static final String FIELD_START_POSITION_MS = Util.intToStringMaxRadix(0);
    private static final String FIELD_END_POSITION_MS = Util.intToStringMaxRadix(1);
    private static final String FIELD_RELATIVE_TO_LIVE_WINDOW = Util.intToStringMaxRadix(2);
    private static final String FIELD_RELATIVE_TO_DEFAULT_POSITION = Util.intToStringMaxRadix(3);
    private static final String FIELD_STARTS_AT_KEY_FRAME = Util.intToStringMaxRadix(4);
    static final String FIELD_START_POSITION_US = Util.intToStringMaxRadix(5);
    static final String FIELD_END_POSITION_US = Util.intToStringMaxRadix(6);

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      if (startPositionMs != UNSET.startPositionMs) {
        bundle.putLong(FIELD_START_POSITION_MS, startPositionMs);
      }
      if (endPositionMs != UNSET.endPositionMs) {
        bundle.putLong(FIELD_END_POSITION_MS, endPositionMs);
      }
      if (startPositionUs != UNSET.startPositionUs) {
        bundle.putLong(FIELD_START_POSITION_US, startPositionUs);
      }
      if (endPositionUs != UNSET.endPositionUs) {
        bundle.putLong(FIELD_END_POSITION_US, endPositionUs);
      }
      if (relativeToLiveWindow != UNSET.relativeToLiveWindow) {
        bundle.putBoolean(FIELD_RELATIVE_TO_LIVE_WINDOW, relativeToLiveWindow);
      }
      if (relativeToDefaultPosition != UNSET.relativeToDefaultPosition) {
        bundle.putBoolean(FIELD_RELATIVE_TO_DEFAULT_POSITION, relativeToDefaultPosition);
      }
      if (startsAtKeyFrame != UNSET.startsAtKeyFrame) {
        bundle.putBoolean(FIELD_STARTS_AT_KEY_FRAME, startsAtKeyFrame);
      }
      return bundle;
    }

    /** Restores a {@code ClippingProperties} from a {@link Bundle}. */
    @SuppressWarnings("deprecation") // Building deprecated type for backwards compatibility
    @UnstableApi
    public static ClippingProperties fromBundle(Bundle bundle) {
      ClippingConfiguration.Builder clippingConfiguration =
          new ClippingConfiguration.Builder()
              .setStartPositionMs(
                  bundle.getLong(
                      FIELD_START_POSITION_MS, /* defaultValue= */ UNSET.startPositionMs))
              .setEndPositionMs(
                  bundle.getLong(FIELD_END_POSITION_MS, /* defaultValue= */ UNSET.endPositionMs))
              .setRelativeToLiveWindow(
                  bundle.getBoolean(
                      FIELD_RELATIVE_TO_LIVE_WINDOW,
                      /* defaultValue= */ UNSET.relativeToLiveWindow))
              .setRelativeToDefaultPosition(
                  bundle.getBoolean(
                      FIELD_RELATIVE_TO_DEFAULT_POSITION,
                      /* defaultValue= */ UNSET.relativeToDefaultPosition))
              .setStartsAtKeyFrame(
                  bundle.getBoolean(
                      FIELD_STARTS_AT_KEY_FRAME, /* defaultValue= */ UNSET.startsAtKeyFrame));
      long startPositionUs =
          bundle.getLong(FIELD_START_POSITION_US, /* defaultValue= */ UNSET.startPositionUs);
      if (startPositionUs != UNSET.startPositionUs) {
        clippingConfiguration.setStartPositionUs(startPositionUs);
      }
      long endPositionUs =
          bundle.getLong(FIELD_END_POSITION_US, /* defaultValue= */ UNSET.endPositionUs);
      if (endPositionUs != UNSET.endPositionUs) {
        clippingConfiguration.setEndPositionUs(endPositionUs);
      }
      return clippingConfiguration.buildClippingProperties();
    }
  }

  /**
   * @deprecated Use {@link ClippingConfiguration} instead.
   */
  @UnstableApi
  @Deprecated
  public static final class ClippingProperties extends ClippingConfiguration {
    @SuppressWarnings("deprecation") // Using deprecated type
    public static final ClippingProperties UNSET =
        new ClippingConfiguration.Builder().buildClippingProperties();

    private ClippingProperties(Builder builder) {
      super(builder);
    }
  }

  /**
   * Metadata that helps the player to understand a playback request represented by a {@link
   * MediaItem}.
   *
   * <p>This metadata is most useful for cases where playback requests are forwarded to other player
   * instances (e.g. from a {@code androidx.media3.session.MediaController}) and the player creating
   * the request doesn't know the required {@link LocalConfiguration} for playback.
   */
  public static final class RequestMetadata {

    /** Empty request metadata. */
    public static final RequestMetadata EMPTY = new Builder().build();

    /** Builder for {@link RequestMetadata} instances. */
    public static final class Builder {

      @Nullable private Uri mediaUri;
      @Nullable private String searchQuery;
      @Nullable private Bundle extras;

      /** Constructs an instance. */
      public Builder() {}

      private Builder(RequestMetadata requestMetadata) {
        this.mediaUri = requestMetadata.mediaUri;
        this.searchQuery = requestMetadata.searchQuery;
        this.extras = requestMetadata.extras;
      }

      /** Sets the URI of the requested media, or null if not known or applicable. */
      @CanIgnoreReturnValue
      public Builder setMediaUri(@Nullable Uri mediaUri) {
        this.mediaUri = mediaUri;
        return this;
      }

      /** Sets the search query for the requested media, or null if not applicable. */
      @CanIgnoreReturnValue
      public Builder setSearchQuery(@Nullable String searchQuery) {
        this.searchQuery = searchQuery;
        return this;
      }

      /** Sets optional extras {@link Bundle}. */
      @CanIgnoreReturnValue
      public Builder setExtras(@Nullable Bundle extras) {
        this.extras = extras;
        return this;
      }

      /** Builds the request metadata. */
      public RequestMetadata build() {
        return new RequestMetadata(this);
      }
    }

    /** The URI of the requested media, or null if not known or applicable. */
    @Nullable public final Uri mediaUri;

    /** The search query for the requested media, or null if not applicable. */
    @Nullable public final String searchQuery;

    /**
     * Optional extras {@link Bundle}.
     *
     * <p>Given the complexities of checking the equality of two {@link Bundle} instances, the
     * contents of these extras are not considered in the {@link #equals(Object)} or {@link
     * #hashCode()} implementation.
     */
    @Nullable public final Bundle extras;

    private RequestMetadata(Builder builder) {
      this.mediaUri = builder.mediaUri;
      this.searchQuery = builder.searchQuery;
      this.extras = builder.extras;
    }

    /** Returns a {@link Builder} initialized with the values of this instance. */
    public Builder buildUpon() {
      return new Builder(this);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RequestMetadata)) {
        return false;
      }
      RequestMetadata that = (RequestMetadata) o;
      return Objects.equals(mediaUri, that.mediaUri)
          && Objects.equals(searchQuery, that.searchQuery)
          && ((extras == null) == (that.extras == null));
    }

    @Override
    public int hashCode() {
      int result = mediaUri == null ? 0 : mediaUri.hashCode();
      result = 31 * result + (searchQuery == null ? 0 : searchQuery.hashCode());
      result = 31 * result + (extras == null ? 0 : 1);
      return result;
    }

    private static final String FIELD_MEDIA_URI = Util.intToStringMaxRadix(0);
    private static final String FIELD_SEARCH_QUERY = Util.intToStringMaxRadix(1);
    private static final String FIELD_EXTRAS = Util.intToStringMaxRadix(2);

    @UnstableApi
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      if (mediaUri != null) {
        bundle.putParcelable(FIELD_MEDIA_URI, mediaUri);
      }
      if (searchQuery != null) {
        bundle.putString(FIELD_SEARCH_QUERY, searchQuery);
      }
      if (extras != null) {
        bundle.putBundle(FIELD_EXTRAS, extras);
      }
      return bundle;
    }

    /** Restores a {@code RequestMetadata} from a {@link Bundle}. */
    @UnstableApi
    public static RequestMetadata fromBundle(Bundle bundle) {
      return new RequestMetadata.Builder()
          .setMediaUri(bundle.getParcelable(FIELD_MEDIA_URI))
          .setSearchQuery(bundle.getString(FIELD_SEARCH_QUERY))
          .setExtras(bundle.getBundle(FIELD_EXTRAS))
          .build();
    }
  }

  /**
   * The default media ID that is used if the media ID is not explicitly set by {@link
   * Builder#setMediaId(String)}.
   */
  public static final String DEFAULT_MEDIA_ID = "";

  /** Empty {@link MediaItem}. */
  public static final MediaItem EMPTY = new MediaItem.Builder().build();

  /** Identifies the media item. */
  public final String mediaId;

  /**
   * Optional configuration for local playback. May be {@code null} if shared over process
   * boundaries.
   */
  @Nullable public final LocalConfiguration localConfiguration;

  /**
   * @deprecated Use {@link #localConfiguration} instead.
   */
  @UnstableApi @Deprecated @Nullable public final LocalConfiguration playbackProperties;

  /** The live playback configuration. */
  public final LiveConfiguration liveConfiguration;

  /** The media metadata. */
  public final MediaMetadata mediaMetadata;

  /** The clipping properties. */
  public final ClippingConfiguration clippingConfiguration;

  /**
   * @deprecated Use {@link #clippingConfiguration} instead.
   */
  @SuppressWarnings("deprecation") // Keeping deprecated field with deprecated type
  @UnstableApi
  @Deprecated
  public final ClippingProperties clippingProperties;

  /** The media {@link RequestMetadata}. */
  public final RequestMetadata requestMetadata;

  // Using ClippingProperties until they're deleted.
  @SuppressWarnings("deprecation")
  private MediaItem(
      String mediaId,
      ClippingProperties clippingConfiguration,
      @Nullable LocalConfiguration localConfiguration,
      LiveConfiguration liveConfiguration,
      MediaMetadata mediaMetadata,
      RequestMetadata requestMetadata) {
    this.mediaId = mediaId;
    this.localConfiguration = localConfiguration;
    this.playbackProperties = localConfiguration;
    this.liveConfiguration = liveConfiguration;
    this.mediaMetadata = mediaMetadata;
    this.clippingConfiguration = clippingConfiguration;
    this.clippingProperties = clippingConfiguration;
    this.requestMetadata = requestMetadata;
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MediaItem)) {
      return false;
    }

    MediaItem other = (MediaItem) obj;

    return Objects.equals(mediaId, other.mediaId)
        && clippingConfiguration.equals(other.clippingConfiguration)
        && Objects.equals(localConfiguration, other.localConfiguration)
        && Objects.equals(liveConfiguration, other.liveConfiguration)
        && Objects.equals(mediaMetadata, other.mediaMetadata)
        && Objects.equals(requestMetadata, other.requestMetadata);
  }

  @Override
  public int hashCode() {
    int result = mediaId.hashCode();
    result = 31 * result + (localConfiguration != null ? localConfiguration.hashCode() : 0);
    result = 31 * result + liveConfiguration.hashCode();
    result = 31 * result + clippingConfiguration.hashCode();
    result = 31 * result + mediaMetadata.hashCode();
    result = 31 * result + requestMetadata.hashCode();
    return result;
  }

  private static final String FIELD_MEDIA_ID = Util.intToStringMaxRadix(0);
  private static final String FIELD_LIVE_CONFIGURATION = Util.intToStringMaxRadix(1);
  private static final String FIELD_MEDIA_METADATA = Util.intToStringMaxRadix(2);
  private static final String FIELD_CLIPPING_PROPERTIES = Util.intToStringMaxRadix(3);
  private static final String FIELD_REQUEST_METADATA = Util.intToStringMaxRadix(4);
  private static final String FIELD_LOCAL_CONFIGURATION = Util.intToStringMaxRadix(5);

  @UnstableApi
  private Bundle toBundle(boolean includeLocalConfiguration) {
    Bundle bundle = new Bundle();
    if (!mediaId.equals(DEFAULT_MEDIA_ID)) {
      bundle.putString(FIELD_MEDIA_ID, mediaId);
    }
    if (!liveConfiguration.equals(LiveConfiguration.UNSET)) {
      bundle.putBundle(FIELD_LIVE_CONFIGURATION, liveConfiguration.toBundle());
    }
    if (!mediaMetadata.equals(MediaMetadata.EMPTY)) {
      bundle.putBundle(FIELD_MEDIA_METADATA, mediaMetadata.toBundle());
    }
    if (!clippingConfiguration.equals(ClippingConfiguration.UNSET)) {
      bundle.putBundle(FIELD_CLIPPING_PROPERTIES, clippingConfiguration.toBundle());
    }
    if (!requestMetadata.equals(RequestMetadata.EMPTY)) {
      bundle.putBundle(FIELD_REQUEST_METADATA, requestMetadata.toBundle());
    }
    if (includeLocalConfiguration && localConfiguration != null) {
      bundle.putBundle(FIELD_LOCAL_CONFIGURATION, localConfiguration.toBundle());
    }
    return bundle;
  }

  /**
   * Returns a {@link Bundle} representing the information stored in this object.
   *
   * <p>It omits the {@link #localConfiguration} field. The {@link #localConfiguration} of an
   * instance restored from such a bundle by {@link #fromBundle} will be {@code null}.
   */
  @UnstableApi
  public Bundle toBundle() {
    return toBundle(/* includeLocalConfiguration= */ false);
  }

  /**
   * Returns a {@link Bundle} representing the information stored in this {@link #MediaItem} object,
   * while including the {@link #localConfiguration} field if it is not null (otherwise skips it).
   */
  @UnstableApi
  public Bundle toBundleIncludeLocalConfiguration() {
    return toBundle(/* includeLocalConfiguration= */ true);
  }

  /**
   * Restores a {@code MediaItem} from a {@link Bundle}.
   *
   * <p>The {@link #localConfiguration} of a restored instance will always be {@code null}.
   */
  @UnstableApi
  @SuppressWarnings("deprecation") // Unbundling to ClippingProperties while it still exists.
  public static MediaItem fromBundle(Bundle bundle) {
    String mediaId = checkNotNull(bundle.getString(FIELD_MEDIA_ID, DEFAULT_MEDIA_ID));
    @Nullable Bundle liveConfigurationBundle = bundle.getBundle(FIELD_LIVE_CONFIGURATION);
    LiveConfiguration liveConfiguration;
    if (liveConfigurationBundle == null) {
      liveConfiguration = LiveConfiguration.UNSET;
    } else {
      liveConfiguration = LiveConfiguration.fromBundle(liveConfigurationBundle);
    }
    @Nullable Bundle mediaMetadataBundle = bundle.getBundle(FIELD_MEDIA_METADATA);
    MediaMetadata mediaMetadata;
    if (mediaMetadataBundle == null) {
      mediaMetadata = MediaMetadata.EMPTY;
    } else {
      mediaMetadata = MediaMetadata.fromBundle(mediaMetadataBundle);
    }
    @Nullable Bundle clippingConfigurationBundle = bundle.getBundle(FIELD_CLIPPING_PROPERTIES);
    ClippingProperties clippingConfiguration;
    if (clippingConfigurationBundle == null) {
      clippingConfiguration = ClippingProperties.UNSET;
    } else {
      clippingConfiguration = ClippingConfiguration.fromBundle(clippingConfigurationBundle);
    }
    @Nullable Bundle requestMetadataBundle = bundle.getBundle(FIELD_REQUEST_METADATA);
    RequestMetadata requestMetadata;
    if (requestMetadataBundle == null) {
      requestMetadata = RequestMetadata.EMPTY;
    } else {
      requestMetadata = RequestMetadata.fromBundle(requestMetadataBundle);
    }
    @Nullable Bundle localConfigurationBundle = bundle.getBundle(FIELD_LOCAL_CONFIGURATION);
    LocalConfiguration localConfiguration;
    if (localConfigurationBundle == null) {
      localConfiguration = null;
    } else {
      localConfiguration = LocalConfiguration.fromBundle(localConfigurationBundle);
    }
    return new MediaItem(
        mediaId,
        clippingConfiguration,
        localConfiguration,
        liveConfiguration,
        mediaMetadata,
        requestMetadata);
  }
}
