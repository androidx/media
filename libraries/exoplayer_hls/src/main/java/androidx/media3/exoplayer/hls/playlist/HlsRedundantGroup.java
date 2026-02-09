/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.hls.playlist;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Rendition;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a group of {@linkplain Variant variants} or {@linkplain Rendition renditions} that are
 * identical streams but from different locations (different playlist urls), and indicated by a
 * shared {@link GroupKey}.
 */
@UnstableApi
public final class HlsRedundantGroup {

  /**
   * Defines a key that groups {@linkplain Variant variants} or {@linkplain Rendition renditions}
   * into one {@link HlsRedundantGroup}.
   */
  public static class GroupKey {

    /**
     * The {@link Format} shared by the {@linkplain Variant grouped variants} or the {@linkplain
     * Rendition grouped renditions}.
     */
    public final Format format;

    /**
     * The stable identifier shared by variants or renditions, or {@code null}.
     *
     * <p>If the {@link GroupKey} groups {@linkplain Variant variants}, then this field is the
     * {@link Variant#stableVariantId} of the variants; If the {@link GroupKey} groups {@linkplain
     * Rendition renditions}, then this field is the {@link Rendition#stableRenditionId} of the
     * renditions.
     */
    @Nullable public final String stableId;

    /**
     * The {@linkplain Rendition#name name} of the {@linkplain Rendition grouped renditions}. This
     * can be {@code null} if unknown or the {@link GroupKey} groups {@linkplain Variant variants}.
     */
    @Nullable public final String name;

    /**
     * Creates a {@link GroupKey}.
     *
     * @param format See {@link #format}.
     * @param stableId See {@link #stableId}.
     */
    public GroupKey(Format format, @Nullable String stableId) {
      this(format, stableId, /* name= */ null);
    }

    /**
     * Creates a {@link GroupKey}.
     *
     * @param format See {@link #format}.
     * @param stableId See {@link #stableId}.
     * @param name See {@link #name}.
     */
    public GroupKey(Format format, @Nullable String stableId, @Nullable String name) {
      // Normalize the format to ensure only fields affecting identity are part of the key.
      this.format = format.buildUpon().setId(null).setMetadata(null).build();
      this.stableId = stableId;
      this.name = name;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof GroupKey)) {
        return false;
      }
      GroupKey groupKey = (GroupKey) other;
      return Objects.equals(format, groupKey.format)
          && Objects.equals(stableId, groupKey.stableId)
          && Objects.equals(name, groupKey.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(format, stableId, name);
    }
  }

  /**
   * The {@link GroupKey} that the {@linkplain Variant grouped variants} or {@linkplain Rendition
   * grouped renditions} share.
   */
  public final GroupKey groupKey;

  private final HashMap<String, Uri> pathwayIdToPlaylistUrl;
  private String currentPathwayId;

  /**
   * Creates a {@link HlsRedundantGroup}.
   *
   * @param groupKey See {@link #groupKey}.
   * @param pathwayId The ID of the default current pathway.
   * @param playlistUrl The playlist url that is associated with the default current pathway.
   */
  public HlsRedundantGroup(GroupKey groupKey, String pathwayId, Uri playlistUrl) {
    this.groupKey = groupKey;
    this.pathwayIdToPlaylistUrl = new HashMap<>();
    this.pathwayIdToPlaylistUrl.put(pathwayId, playlistUrl);
    this.currentPathwayId = pathwayId;
  }

  /**
   * Puts a {@code playlistUrl} and its associated {@code pathwayId} to the {@link
   * HlsRedundantGroup}.
   */
  public void put(String pathwayId, Uri playlistUrl) {
    pathwayIdToPlaylistUrl.put(pathwayId, playlistUrl);
  }

  /** Returns the size of the {@link HlsRedundantGroup}. */
  public int size() {
    return pathwayIdToPlaylistUrl.size();
  }

  /** Sets the {@code pathwayId} that is currently chosen for playback. */
  public void setCurrentPathwayId(String pathwayId) {
    checkState(pathwayIdToPlaylistUrl.containsKey(pathwayId));
    currentPathwayId = pathwayId;
  }

  /** Returns the pathway ID that is currently chosen for playback. */
  public String getCurrentPathwayId() {
    return currentPathwayId;
  }

  /** Returns all pathway IDs that belong to this {@link HlsRedundantGroup}. */
  public ImmutableSet<String> getAllPathwayIds() {
    return ImmutableSet.copyOf(pathwayIdToPlaylistUrl.keySet());
  }

  /** Returns the playlist url that is associated with the currently chosen pathway ID. */
  public Uri getCurrentPlaylistUrl() {
    return checkNotNull(pathwayIdToPlaylistUrl.get(currentPathwayId));
  }

  /**
   * Returns the playlist url associated with the given {@code pathwayId}, or {@code null} if the
   * given {@code pathwayId} doesn't exist in this {@link HlsRedundantGroup}.
   */
  @Nullable
  public Uri getPlaylistUrl(String pathwayId) {
    return pathwayIdToPlaylistUrl.get(pathwayId);
  }

  /** Returns all playlist urls that belong to this {@link HlsRedundantGroup}. */
  public ImmutableSet<Uri> getAllPlaylistUrls() {
    return ImmutableSet.copyOf(pathwayIdToPlaylistUrl.values());
  }

  /**
   * Creates a list of {@linkplain HlsRedundantGroup redundant groups} for {@linkplain Variant
   * variants}.
   *
   * <p>If a {@link Variant} has a non-null {@linkplain Variant#pathwayId pathway ID}, then its
   * {@link Variant#url} will be associated with that pathway ID when added to the {@link
   * HlsRedundantGroup}. Otherwise, a generated pathway ID will be used. The first is '.', and the
   * subsequent ones for the same group are '..', '...', according to the order they are listed in
   * the {@link HlsMultivariantPlaylist}.
   *
   * @param variants The {@linkplain Variant variants}.
   * @return A list of {@linkplain HlsRedundantGroup redundant groups}.
   * @throws ParserException If two variants grouped into one {@link HlsRedundantGroup} have the
   *     same {@link Variant#pathwayId} but different {@linkplain Variant#url urls}.
   */
  public static ImmutableList<HlsRedundantGroup> createVariantRedundantGroupList(
      List<Variant> variants) throws ParserException {
    ArrayList<HlsRedundantGroup> redundantGroupList = new ArrayList<>();
    HashMap<GroupKey, Integer> redundantGroupIndices = new HashMap<>();
    HashMap<GroupKey, Integer> generatedPathwayIdCounts = new HashMap<>();

    for (Variant variant : variants) {
      GroupKey groupKey = new GroupKey(variant.format, variant.stableVariantId);
      propagateRedundantGroupList(
          variant.url,
          variant.pathwayId,
          redundantGroupList,
          groupKey,
          redundantGroupIndices,
          generatedPathwayIdCounts);
    }
    return ImmutableList.copyOf(redundantGroupList);
  }

  /**
   * Creates a list of {@linkplain HlsRedundantGroup redundant groups} for {@linkplain Rendition
   * renditions}.
   *
   * <p>For each {@link Rendition} with a non-null {@link Rendition#url}, a generated pathway ID
   * will be used. The first is '.', and the subsequent ones for the same group are '..', '...',
   * according to the order they are listed in the {@link HlsMultivariantPlaylist}.
   *
   * @param renditions The {@linkplain Rendition renditions}.
   * @return A list of {@linkplain HlsRedundantGroup redundant groups}.
   */
  public static ImmutableList<HlsRedundantGroup> createRenditionRedundantGroupList(
      List<Rendition> renditions) {
    ArrayList<HlsRedundantGroup> redundantGroupList = new ArrayList<>();
    HashMap<GroupKey, Integer> redundantGroupIndices = new HashMap<>();
    HashMap<GroupKey, Integer> generatedPathwayIdCounts = new HashMap<>();
    for (Rendition rendition : renditions) {
      if (rendition.url == null) {
        continue;
      }
      GroupKey groupKey =
          new GroupKey(rendition.format, rendition.stableRenditionId, rendition.name);
      try {
        propagateRedundantGroupList(
            rendition.url,
            /* knownPathwayId= */ null,
            redundantGroupList,
            groupKey,
            redundantGroupIndices,
            generatedPathwayIdCounts);
      } catch (ParserException e) {
        // Should not happen, as we don't pass a knownPathwayId for renditions, and the generated
        // pathwayId should be monotonically increasing in the repeat count of ".".
      }
    }
    return ImmutableList.copyOf(redundantGroupList);
  }

  /**
   * Propagates a list of {@link HlsRedundantGroup} with a {@link Uri playlist url}.
   *
   * <p>This method either creates a new {@link HlsRedundantGroup} for the provided {@link Uri} and
   * adds it to the passed {@code redundantGroupList}, or adds the provided {@link Uri} into an
   * existing {@link HlsRedundantGroup} in the {@code redundantGroupList}.
   *
   * @param url The {@link Uri} to be added either into a new {@link HlsRedundantGroup} or an
   *     existing {@link HlsRedundantGroup}.
   * @param knownPathwayId The known pathway ID of the {@code url}, which will be added with the
   *     {@code url} to an {@link HlsRedundantGroup}. If {@code null} is passed, a generated pathway
   *     ID will be used. The first is '.', and the subsequent ones for the same group are '..',
   *     '...', according to the order of the corresponding {@link Variant} or {@link Rendition} in
   *     the {@link HlsMultivariantPlaylist}.
   * @param redundantGroupList The list of {@link HlsRedundantGroup} to be propagated.
   * @param groupKey The {@link GroupKey} to look up the existing {@link HlsRedundantGroup}.
   * @param redundantGroupIndices A map to look up an existing {@link HlsRedundantGroup} for a
   *     {@link GroupKey}. The keys are the {@link GroupKey group keys} of the {@link
   *     HlsRedundantGroup redundant groups}, and the values are the indices of the corresponding
   *     {@link HlsRedundantGroup redundant groups} in the {@code redundantGroupList}.
   * @param generatedPathwayIdCounts A map to look up the count of the generated pathway ID for an
   *     {@link HlsRedundantGroup} of a {@link GroupKey}. The keys are the {@link GroupKey group
   *     keys} of the {@link HlsRedundantGroup redundant groups}, and the values are the counts of
   *     the generated pathway IDs of the {@link HlsRedundantGroup redundant groups}.
   * @throws ParserException If two different urls grouped into one {@link HlsRedundantGroup} have
   *     the same pathway ID to associate with.
   */
  private static void propagateRedundantGroupList(
      Uri url,
      @Nullable String knownPathwayId,
      List<HlsRedundantGroup> redundantGroupList,
      GroupKey groupKey,
      Map<GroupKey, Integer> redundantGroupIndices,
      Map<GroupKey, Integer> generatedPathwayIdCounts)
      throws ParserException {
    @Nullable String pathwayId = knownPathwayId;
    @Nullable Integer redundantGroupIndex = redundantGroupIndices.get(groupKey);
    if (redundantGroupIndex == null) {
      generatedPathwayIdCounts.put(groupKey, 0);
      if (pathwayId == null) {
        // The default pathwayId is ".".
        pathwayId = ".";
        generatedPathwayIdCounts.put(groupKey, 1);
      }
      HlsRedundantGroup newRedundantGroup = new HlsRedundantGroup(groupKey, pathwayId, url);
      redundantGroupIndices.put(groupKey, redundantGroupList.size());
      redundantGroupList.add(newRedundantGroup);
    } else {
      if (pathwayId == null) {
        // In the absence of a pathwayId, a generated pathway ID is used. The first is '.', and the
        // subsequent ones for the same group are '..', '...' and so on.
        int generatedPathwayIdCount = checkNotNull(generatedPathwayIdCounts.get(groupKey));
        pathwayId = Strings.repeat(".", ++generatedPathwayIdCount);
        generatedPathwayIdCounts.put(groupKey, generatedPathwayIdCount);
      }
      HlsRedundantGroup redundantGroup = redundantGroupList.get(redundantGroupIndex);
      @Nullable Uri existingUrl = redundantGroup.getPlaylistUrl(pathwayId);
      if (existingUrl != null && !url.equals(existingUrl)) {
        throw ParserException.createForMalformedManifest(
            String.format(
                "Different playlist URLs are found for pathway ID %s within the HlsRedundantGroup",
                pathwayId),
            /* cause= */ null);
      }
      redundantGroup.put(pathwayId, url);
    }
  }
}
