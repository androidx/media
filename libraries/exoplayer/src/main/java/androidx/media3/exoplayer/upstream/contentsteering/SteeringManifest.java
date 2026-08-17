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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a steering manifest.
 *
 * <p>See <a href="https://datatracker.ietf.org/doc/html/draft-pantos-content-steering-01">Content
 * Steering</a> specification.
 */
@UnstableApi
public final class SteeringManifest {

  /** Represents a pathway clone. */
  public static final class PathwayClone {

    /** The pathway ID of the base pathway. */
    public final String baseId;

    /** The pathway ID of this pathway clone. */
    public final String id;

    /** The {@link UriReplacement} object. */
    public final UriReplacement uriReplacement;

    /**
     * Constructs an instance.
     *
     * @param baseId See {@link #baseId}.
     * @param id See {@link #id}.
     * @param uriReplacement See {@link #uriReplacement}.
     */
    public PathwayClone(String baseId, String id, UriReplacement uriReplacement) {
      this.baseId = baseId;
      this.id = id;
      this.uriReplacement = uriReplacement;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof PathwayClone)) {
        return false;
      }
      PathwayClone pathwayClone = (PathwayClone) o;
      return Objects.equals(baseId, pathwayClone.baseId)
          && Objects.equals(id, pathwayClone.id)
          && Objects.equals(uriReplacement, pathwayClone.uriReplacement);
    }

    @Override
    public int hashCode() {
      return Objects.hash(baseId, id, uriReplacement);
    }
  }

  /** Represents a definition of URI modifications to apply during the pathway cloning process. */
  public static final class UriReplacement {

    /** The hostname for cloned URIs. Null if not present. */
    @Nullable public final String host;

    /**
     * The query parameters for cloned URIs. The keys represent query parameter names, and the
     * values correspond to the associated parameter values.
     */
    public final ImmutableMap<String, String> params;

    /**
     * The URI overrides per variant stream. The keys are STABLE-VARIANT-ID strings declared in the
     * HLS multivariant playlist.
     *
     * <p>This field is used for HLS only, and is ignored by DASH.
     */
    public final ImmutableMap<String, Uri> perVariantUris;

    /**
     * The URI overrides per rendition stream. The keys are STABLE-RENDITION-ID strings declared in
     * the HLS multivariant playlist.
     *
     * <p>This field is used for HLS only, and is ignored by DASH.
     */
    public final ImmutableMap<String, Uri> perRenditionUris;

    /**
     * Constructs an instance.
     *
     * @param host See {@link #host}.
     * @param params See {@link #params}.
     * @param perVariantUris See {@link #perVariantUris}
     * @param perRenditionUris See {@link #perRenditionUris}
     */
    public UriReplacement(
        @Nullable String host,
        Map<String, String> params,
        Map<String, Uri> perVariantUris,
        Map<String, Uri> perRenditionUris) {
      this.host = host;
      this.params = ImmutableMap.copyOf(params);
      this.perVariantUris = ImmutableMap.copyOf(perVariantUris);
      this.perRenditionUris = ImmutableMap.copyOf(perRenditionUris);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof UriReplacement)) {
        return false;
      }
      UriReplacement uriReplacement = (UriReplacement) o;
      return Objects.equals(host, uriReplacement.host)
          && Objects.equals(params, uriReplacement.params)
          && Objects.equals(perVariantUris, uriReplacement.perVariantUris)
          && Objects.equals(perRenditionUris, uriReplacement.perRenditionUris);
    }

    @Override
    public int hashCode() {
      return Objects.hash(host, params, perVariantUris, perRenditionUris);
    }
  }

  /** The version of the steering manifest. */
  public final int version;

  /**
   * The duration in milliseconds that the client must wait after loading the steering manifest
   * before reloading it. {@link C#TIME_UNSET} if not present.
   */
  public final long timeToLiveMs;

  /**
   * The optional {@link Uri} that the client must use for future steering manifest requests. It may
   * be relative to the current Steering Manifest URI. Null if not present.
   */
  @Nullable public final Uri reloadUri;

  /**
   * The priority list of pathway IDs. The pathway IDs are ordered by pathway preference, with the
   * first being most preferred. This list must not be empty.
   */
  public final ImmutableList<String> pathwayPriority;

  /** The list of {@link PathwayClone} objects. */
  public final ImmutableList<PathwayClone> pathwayClones;

  /**
   * Constructs an instance.
   *
   * @param version See {@link #version}.
   * @param timeToLiveMs See {@link #timeToLiveMs}.
   * @param reloadUri See {@link #reloadUri}.
   * @param pathwayPriority See {@link #pathwayPriority}.
   * @param pathwayClones See {@link #pathwayClones}.
   */
  public SteeringManifest(
      int version,
      long timeToLiveMs,
      @Nullable Uri reloadUri,
      List<String> pathwayPriority,
      List<PathwayClone> pathwayClones) {
    this.version = version;
    this.timeToLiveMs = timeToLiveMs;
    this.reloadUri = reloadUri;
    this.pathwayPriority = ImmutableList.copyOf(pathwayPriority);
    this.pathwayClones = ImmutableList.copyOf(pathwayClones);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SteeringManifest)) {
      return false;
    }
    SteeringManifest steeringManifest = (SteeringManifest) o;
    return version == steeringManifest.version
        && timeToLiveMs == steeringManifest.timeToLiveMs
        && Objects.equals(reloadUri, steeringManifest.reloadUri)
        && Objects.equals(pathwayPriority, steeringManifest.pathwayPriority)
        && Objects.equals(pathwayClones, steeringManifest.pathwayClones);
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, timeToLiveMs, reloadUri, pathwayPriority, pathwayClones);
  }
}
