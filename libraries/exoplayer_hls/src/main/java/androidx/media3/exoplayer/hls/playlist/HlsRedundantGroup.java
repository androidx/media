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
import static java.lang.annotation.ElementType.TYPE_USE;

import android.net.Uri;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Rendition;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.Variant;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Represents a group of {@linkplain Variant variants} or {@linkplain Rendition renditions} that are
 * identical streams but from different locations (different playlist urls), and indicated by a
 * shared {@link GroupKey}.
 */
@UnstableApi
public final class HlsRedundantGroup {

  /**
   * Represents the type of a {@link HlsRedundantGroup}. One of {@link #VARIANT}, {@link
   * #VIDEO_RENDITION}, {@link #AUDIO_RENDITION} and {@link #SUBTITLE_RENDITION}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef(value = {VARIANT, VIDEO_RENDITION, AUDIO_RENDITION, SUBTITLE_RENDITION})
  public @interface Type {}

  /** The {@link HlsRedundantGroup} groups {@linkplain Variant variants}. */
  public static final @Type int VARIANT = 0;

  /** The {@link HlsRedundantGroup} groups {@linkplain Rendition video renditions}. */
  public static final @Type int VIDEO_RENDITION = 1;

  /** The {@link HlsRedundantGroup} groups {@linkplain Rendition audio renditions}. */
  public static final @Type int AUDIO_RENDITION = 2;

  /** The {@link HlsRedundantGroup} groups {@linkplain Rendition subtitle renditions}. */
  public static final @Type int SUBTITLE_RENDITION = 3;

  /**
   * A factory for creating lists of {@linkplain HlsRedundantGroup redundant groups} from a {@link
   * HlsMultivariantPlaylist}.
   */
  public static class Factory {

    private final HlsMultivariantPlaylist multivariantPlaylist;
    private final boolean contentSteeringEnabled;
    private final Map<String, String> videoGroupIdToPathwayId;
    private final Map<String, String> audioGroupIdToPathwayId;
    private final Map<String, String> subtitleGroupIdToPathwayId;
    private @MonotonicNonNull ImmutableList<HlsRedundantGroup> variantRedundantGroupList;
    private @MonotonicNonNull ImmutableList<HlsRedundantGroup> videoRenditionRedundantGroupList;
    private @MonotonicNonNull ImmutableList<HlsRedundantGroup> audioRenditionRedundantGroupList;
    private @MonotonicNonNull ImmutableList<HlsRedundantGroup> subtitleRenditionRedundantGroupList;

    /**
     * Creates a new factory.
     *
     * @param multivariantPlaylist The {@link HlsMultivariantPlaylist} for which to create
     *     {@linkplain HlsRedundantGroup redundant groups}.
     */
    public Factory(HlsMultivariantPlaylist multivariantPlaylist) {
      this.multivariantPlaylist = multivariantPlaylist;
      this.contentSteeringEnabled = multivariantPlaylist.contentSteeringInfo != null;
      videoGroupIdToPathwayId = new HashMap<>();
      audioGroupIdToPathwayId = new HashMap<>();
      subtitleGroupIdToPathwayId = new HashMap<>();
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
     * @return A list of {@linkplain HlsRedundantGroup redundant groups}.
     * @throws ParserException If two variants grouped into one {@link HlsRedundantGroup} have the
     *     same {@link Variant#pathwayId} but different {@linkplain Variant#url urls}.
     * @throws ParserException If content steering enabled and a {@linkplain Variant#videoGroupId
     *     video group ID}, {@link Variant#audioGroupId audio group ID} or {@link
     *     Variant#subtitleGroupId subtitle group ID} is associated with more than one pathway.
     * @throws ParserException If content steering is enabled and the set of available pathway IDs
     *     is inconsistent among variant redundant groups.
     */
    public ImmutableList<HlsRedundantGroup> createVariantRedundantGroupList()
        throws ParserException {
      ensureVariantRedundantGroupListCreated();
      return variantRedundantGroupList;
    }

    /**
     * Creates a list of {@linkplain HlsRedundantGroup redundant groups} for {@linkplain Rendition
     * renditions} with given {@code type}.
     *
     * <p>If content steering is enabled, then a pathway Id will be determined by the {@linkplain
     * Rendition#groupId group ID} and the {@linkplain Variant#pathwayId pathway ID} of the
     * {@linkplain Variant} that links to the rendition. Otherwise, a generated pathway ID will be
     * used. The first is '.', and the subsequent ones for the same group are '..', '...', according
     * to the order they are listed in the {@link HlsMultivariantPlaylist}.
     *
     * @param type The type of {@linkplain Rendition renditions} for which to create {@linkplain
     *     HlsRedundantGroup redundant groups}.
     * @return A list of {@linkplain HlsRedundantGroup redundant groups}.
     * @throws ParserException If two renditions grouped into one {@link HlsRedundantGroup} have the
     *     same pathway ID but different {@linkplain Rendition#url urls}.
     * @throws ParserException If content steering enabled and a {@linkplain Rendition#groupId group
     *     ID} is associated with more than one pathway.
     * @throws ParserException If content steering is enabled and the set of available pathway IDs
     *     of a rendition redundant group is inconsistent with the variant redundant groups.
     * @throws IllegalArgumentException If the {@code type} is not one of {@link #VIDEO_RENDITION},
     *     {@link #AUDIO_RENDITION} or {@link #SUBTITLE_RENDITION}.
     */
    public ImmutableList<HlsRedundantGroup> createRenditionRedundantGroupList(@Type int type)
        throws ParserException {
      ensureVariantRedundantGroupListCreated();
      switch (type) {
        case VIDEO_RENDITION:
          if (videoRenditionRedundantGroupList == null) {
            videoRenditionRedundantGroupList =
                createAndValidateRenditionRedundantGroupList(
                    multivariantPlaylist.videos, videoGroupIdToPathwayId);
          }
          return videoRenditionRedundantGroupList;
        case AUDIO_RENDITION:
          if (audioRenditionRedundantGroupList == null) {
            audioRenditionRedundantGroupList =
                createAndValidateRenditionRedundantGroupList(
                    multivariantPlaylist.audios, audioGroupIdToPathwayId);
          }
          return audioRenditionRedundantGroupList;
        case SUBTITLE_RENDITION:
          if (subtitleRenditionRedundantGroupList == null) {
            subtitleRenditionRedundantGroupList =
                createAndValidateRenditionRedundantGroupList(
                    multivariantPlaylist.subtitles, subtitleGroupIdToPathwayId);
          }
          return subtitleRenditionRedundantGroupList;
        default:
          throw new IllegalArgumentException(
              "Invalid type for creating rendition redundant group list");
      }
    }

    @EnsuresNonNull("variantRedundantGroupList")
    private void ensureVariantRedundantGroupListCreated() throws ParserException {
      if (variantRedundantGroupList == null) {
        ArrayList<HlsRedundantGroup> redundantGroupList = new ArrayList<>();
        HashMap<GroupKey, Integer> redundantGroupIndices = new HashMap<>();
        HashMap<GroupKey, Integer> generatedPathwayIdCounts = new HashMap<>();
        for (int i = 0; i < multivariantPlaylist.variants.size(); i++) {
          Variant variant = multivariantPlaylist.variants.get(i);
          GroupKey groupKey = new GroupKey(variant.format, variant.stableVariantId);
          String pathwayId =
              propagateRedundantGroupList(
                  variant.url,
                  variant.pathwayId,
                  /* indexInMultivariantPlaylist= */ i,
                  redundantGroupList,
                  groupKey,
                  redundantGroupIndices,
                  generatedPathwayIdCounts);
          if (contentSteeringEnabled) {
            if (variant.videoGroupId != null) {
              updateGroupIdToPathwayIdMapping(
                  videoGroupIdToPathwayId, variant.videoGroupId, pathwayId);
            }
            if (variant.audioGroupId != null) {
              updateGroupIdToPathwayIdMapping(
                  audioGroupIdToPathwayId, variant.audioGroupId, pathwayId);
            }
            if (variant.subtitleGroupId != null) {
              updateGroupIdToPathwayIdMapping(
                  subtitleGroupIdToPathwayId, variant.subtitleGroupId, pathwayId);
            }
          }
        }
        variantRedundantGroupList = ImmutableList.copyOf(redundantGroupList);
        validateVariantRedundantGroupList();
      }
    }

    @RequiresNonNull("variantRedundantGroupList")
    private ImmutableList<HlsRedundantGroup> createAndValidateRenditionRedundantGroupList(
        List<Rendition> renditions, Map<String, String> groupIdToPathwayId) throws ParserException {
      ArrayList<HlsRedundantGroup> redundantGroupList = new ArrayList<>();
      HashMap<GroupKey, Integer> redundantGroupIndices = new HashMap<>();
      HashMap<GroupKey, Integer> generatedPathwayIdCounts = new HashMap<>();
      for (int i = 0; i < renditions.size(); i++) {
        Rendition rendition = renditions.get(i);
        if (rendition.url == null) {
          continue;
        }
        GroupKey groupKey =
            new GroupKey(rendition.format, rendition.stableRenditionId, rendition.name);
        @Nullable String knownPathwayId = null;
        if (contentSteeringEnabled) {
          knownPathwayId = groupIdToPathwayId.get(rendition.groupId);
          if (knownPathwayId == null) {
            // When Content Steering is enabled, we skip the rendition if we don't know its pathway
            // ID. This also implies that there is no variant referring to this rendition with the
            // rendition.groupId.
            continue;
          }
        }
        propagateRedundantGroupList(
            rendition.url,
            knownPathwayId,
            /* indexInMultivariantPlaylist= */ i,
            redundantGroupList,
            groupKey,
            redundantGroupIndices,
            generatedPathwayIdCounts);
      }
      validateRenditionRedundantGroupList(redundantGroupList);
      return ImmutableList.copyOf(redundantGroupList);
    }

    private static void updateGroupIdToPathwayIdMapping(
        Map<String, String> groupIdToPathwayId, String groupId, String pathwayId)
        throws ParserException {
      if (groupIdToPathwayId.containsKey(groupId)
          && !pathwayId.equals(groupIdToPathwayId.get(groupId))) {
        throw ParserException.createForMalformedManifest(
            String.format(
                "The group ID %s is associated with more than one pathway from" + " variants",
                groupId),
            /* cause= */ null);
      }
      groupIdToPathwayId.put(groupId, pathwayId);
    }

    /**
     * Propagates a list of {@link HlsRedundantGroup} with a {@link Uri playlist url}.
     *
     * <p>This method either creates a new {@link HlsRedundantGroup} for the provided {@link Uri}
     * and adds it to the passed {@code redundantGroupList}, or adds the provided {@link Uri} into
     * an existing {@link HlsRedundantGroup} in the {@code redundantGroupList}.
     *
     * @param url The {@link Uri} to be added either into a new {@link HlsRedundantGroup} or an
     *     existing {@link HlsRedundantGroup}.
     * @param knownPathwayId The known pathway ID of the {@code url}, which will be added with the
     *     {@code url} to an {@link HlsRedundantGroup}. If {@code null} is passed, a generated
     *     pathway ID will be used. The first is '.', and the subsequent ones for the same group are
     *     '..', '...', according to the order of the corresponding {@link Variant} or {@link
     *     Rendition} in the {@link HlsMultivariantPlaylist}.
     * @param indexInMultivariantPlaylist The index of the {@code playlistUrl} in either {@link
     *     HlsMultivariantPlaylist#variants}, {@link HlsMultivariantPlaylist#videos}, {@link
     *     HlsMultivariantPlaylist#audios} or {@link HlsMultivariantPlaylist#subtitles}. May be
     *     {@link C#INDEX_UNSET} if the {@code playlistUrl} is not declared in the multivariant
     *     playlist.
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
     * @return The pathway ID of the given {@code url}, which is either the {@code knownPathwayId}
     *     if provided, or a newly generated pathway ID.
     * @throws ParserException If two different urls grouped into one {@link HlsRedundantGroup} have
     *     the same pathway ID to associate with.
     */
    @CanIgnoreReturnValue
    private String propagateRedundantGroupList(
        Uri url,
        @Nullable String knownPathwayId,
        int indexInMultivariantPlaylist,
        List<HlsRedundantGroup> redundantGroupList,
        GroupKey groupKey,
        Map<GroupKey, Integer> redundantGroupIndices,
        Map<GroupKey, Integer> generatedPathwayIdCounts)
        throws ParserException {
      @Nullable String pathwayId = knownPathwayId;
      @Nullable Integer redundantGroupIndex = redundantGroupIndices.get(groupKey);
      if (redundantGroupIndex == null) {
        if (pathwayId == null) {
          // The default pathwayId is ".".
          pathwayId = ".";
          generatedPathwayIdCounts.put(groupKey, 1);
        } else {
          generatedPathwayIdCounts.put(groupKey, 0);
        }
        HlsRedundantGroup newRedundantGroup =
            new HlsRedundantGroup(groupKey, pathwayId, url, indexInMultivariantPlaylist);
        redundantGroupIndices.put(groupKey, redundantGroupList.size());
        redundantGroupList.add(newRedundantGroup);
      } else {
        if (pathwayId == null) {
          // In the absence of a pathwayId, a generated pathway ID is used. The first is '.', and
          // the subsequent ones for the same group are '..', '...' and so on.
          int generatedPathwayIdCount = checkNotNull(generatedPathwayIdCounts.get(groupKey));
          if (contentSteeringEnabled && generatedPathwayIdCount >= 1) {
            throw ParserException.createForMalformedManifest(
                "At most one playlist URL within an HlsRedundantGroup can have an undefined pathway"
                    + " when Content Steering is enabled",
                /* cause= */ null);
          }
          pathwayId = Strings.repeat(".", ++generatedPathwayIdCount);
          generatedPathwayIdCounts.put(groupKey, generatedPathwayIdCount);
        }
        HlsRedundantGroup redundantGroup = redundantGroupList.get(redundantGroupIndex);
        @Nullable Uri existingUrl = redundantGroup.getPlaylistUrl(pathwayId);
        if (existingUrl != null && !url.equals(existingUrl)) {
          throw ParserException.createForMalformedManifest(
              String.format(
                  "Different playlist URLs are found for pathway ID %s within the"
                      + " HlsRedundantGroup",
                  pathwayId),
              /* cause= */ null);
        }
        redundantGroup.put(pathwayId, url, indexInMultivariantPlaylist);
      }
      return pathwayId;
    }

    @RequiresNonNull("variantRedundantGroupList")
    private void validateVariantRedundantGroupList() throws ParserException {
      if (!contentSteeringEnabled) {
        return;
      }
      Set<String> pathwayIdSet = checkNotNull(variantRedundantGroupList.get(0)).getAllPathwayIds();
      for (int i = 1; i < variantRedundantGroupList.size(); i++) {
        HlsRedundantGroup redundantGroup = variantRedundantGroupList.get(i);
        if (!redundantGroup.getAllPathwayIds().equals(pathwayIdSet)) {
          throw ParserException.createForMalformedManifest(
              "The set of available pathway IDs is inconsistent among variant redundant groups",
              /* cause= */ null);
        }
      }
    }

    @RequiresNonNull("variantRedundantGroupList")
    private void validateRenditionRedundantGroupList(
        List<HlsRedundantGroup> renditionRedundantGroupList) throws ParserException {
      if (!contentSteeringEnabled) {
        return;
      }
      Set<String> pathwayIdSet = checkNotNull(variantRedundantGroupList.get(0)).getAllPathwayIds();
      for (int i = 0; i < renditionRedundantGroupList.size(); i++) {
        HlsRedundantGroup redundantGroup = renditionRedundantGroupList.get(i);
        if (!redundantGroup.getAllPathwayIds().equals(pathwayIdSet)) {
          throw ParserException.createForMalformedManifest(
              "The set of available pathway IDs of a rendition redundant group is inconsistent with"
                  + " variant redundant groups",
              /* cause= */ null);
        }
      }
    }
  }

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
  private final List<Integer> indicesInMultivariantPlaylist;
  private String currentPathwayId;

  /**
   * Creates a {@link HlsRedundantGroup}.
   *
   * @param groupKey See {@link #groupKey}.
   * @param pathwayId The ID of the default current pathway.
   * @param playlistUrl The playlist url that is associated with the default current pathway.
   */
  public HlsRedundantGroup(GroupKey groupKey, String pathwayId, Uri playlistUrl) {
    this(groupKey, pathwayId, playlistUrl, C.INDEX_UNSET);
  }

  /**
   * Creates a {@link HlsRedundantGroup}.
   *
   * @param groupKey See {@link #groupKey}.
   * @param pathwayId The ID of the default current pathway.
   * @param playlistUrl The playlist url that is associated with the default current pathway.
   * @param indexInMultivariantPlaylist The index of the {@code playlistUrl} in either {@link
   *     HlsMultivariantPlaylist#variants}, {@link HlsMultivariantPlaylist#videos}, {@link
   *     HlsMultivariantPlaylist#audios} or {@link HlsMultivariantPlaylist#subtitles}. May be {@link
   *     C#INDEX_UNSET} if the {@code playlistUrl} is not declared in the multivariant playlist.
   */
  public HlsRedundantGroup(
      GroupKey groupKey, String pathwayId, Uri playlistUrl, int indexInMultivariantPlaylist) {
    this.groupKey = groupKey;
    this.pathwayIdToPlaylistUrl = new HashMap<>();
    this.pathwayIdToPlaylistUrl.put(pathwayId, playlistUrl);
    this.currentPathwayId = pathwayId;
    this.indicesInMultivariantPlaylist = new ArrayList<>();
    if (indexInMultivariantPlaylist != C.INDEX_UNSET) {
      indicesInMultivariantPlaylist.add(indexInMultivariantPlaylist);
    }
  }

  /**
   * Puts a {@code playlistUrl} and its associated {@code pathwayId} to the {@link
   * HlsRedundantGroup}.
   */
  public void put(String pathwayId, Uri playlistUrl) {
    put(pathwayId, playlistUrl, C.INDEX_UNSET);
  }

  /**
   * Puts a {@code playlistUrl} and its associated {@code pathwayId} to the {@link
   * HlsRedundantGroup}.
   *
   * @param pathwayId The ID of the pathway to add.
   * @param playlistUrl The playlist url to add.
   * @param indexInMultivariantPlaylist The index of the {@code playlistUrl} in either {@link
   *     HlsMultivariantPlaylist#variants}, {@link HlsMultivariantPlaylist#videos}, {@link
   *     HlsMultivariantPlaylist#audios} or {@link HlsMultivariantPlaylist#subtitles}. May be {@link
   *     C#INDEX_UNSET} if the {@code playlistUrl} is not declared in the multivariant playlist.
   */
  public void put(String pathwayId, Uri playlistUrl, int indexInMultivariantPlaylist) {
    pathwayIdToPlaylistUrl.put(pathwayId, playlistUrl);
    if (indexInMultivariantPlaylist != C.INDEX_UNSET) {
      indicesInMultivariantPlaylist.add(indexInMultivariantPlaylist);
    }
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
   * Returns the indices of the {@linkplain Variant variants} or {@linkplain Rendition renditions}
   * belonging to this {@link HlsRedundantGroup} in either {@link HlsMultivariantPlaylist#variants},
   * {@link HlsMultivariantPlaylist#videos}, {@link HlsMultivariantPlaylist#audios} or {@link
   * HlsMultivariantPlaylist#subtitles}.
   */
  public ImmutableList<Integer> getIndicesInMultivariantPlaylist() {
    return ImmutableList.copyOf(indicesInMultivariantPlaylist);
  }
}
