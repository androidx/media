/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.source.preload;

import static androidx.media3.common.util.Util.postOrRun;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.MediaSource;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A base implementation of a preload manager, which maintains the lifecycle of {@linkplain
 * MediaSource media sources}.
 *
 * <p>Methods should be called on the same thread.
 */
@UnstableApi
public abstract class BasePreloadManager<T, PreloadStatusT> {

  /** A base class of the builder of the concrete extension of {@link BasePreloadManager}. */
  protected abstract static class BuilderBase<T, PreloadStatusT> {
    protected final TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl;
    protected RankingDataComparator<T> rankingDataComparator;
    protected Supplier<MediaSource.Factory> mediaSourceFactorySupplier;

    public BuilderBase(
        RankingDataComparator<T> rankingDataComparator,
        TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl,
        Supplier<MediaSource.Factory> mediaSourceFactorySupplier) {
      this.rankingDataComparator = rankingDataComparator;
      this.targetPreloadStatusControl = targetPreloadStatusControl;
      this.mediaSourceFactorySupplier = mediaSourceFactorySupplier;
    }

    public abstract BasePreloadManager<T, PreloadStatusT> build();
  }

  private final Object lock;
  protected final RankingDataComparator<T> rankingDataComparator;
  private final TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl;
  private final MediaSource.Factory mediaSourceFactory;
  private final ListenerSet<PreloadManagerListener> listeners;
  private final MediaSourceHolderMap mediaSourceHolderMap;
  private final Handler applicationHandler;

  @GuardedBy("lock")
  private final List<MediaSourceHolder> sourceHolderPriorityList;

  @GuardedBy("lock")
  private int indexForSourceHolderToPreload;

  @GuardedBy("lock")
  private int indexForSourceHolderToClear;

  @GuardedBy("lock")
  @Nullable
  private PreloadStatusT targetPreloadStatusOfCurrentPreloadingSource;

  // It's safe to use "this" because the invalidationListener of rankingDataComparator shouldn't be
  // triggered before exiting this (or subclass's) constructor.
  @SuppressWarnings("nullness:methodref.receiver.bound")
  protected BasePreloadManager(
      RankingDataComparator<T> rankingDataComparator,
      TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl,
      MediaSource.Factory mediaSourceFactory) {
    lock = new Object();
    applicationHandler = Util.createHandlerForCurrentOrMainLooper();
    this.rankingDataComparator = rankingDataComparator;
    this.targetPreloadStatusControl = targetPreloadStatusControl;
    this.mediaSourceFactory = mediaSourceFactory;
    listeners = new ListenerSet<>(applicationHandler.getLooper(), Clock.DEFAULT);
    mediaSourceHolderMap = new MediaSourceHolderMap();
    this.rankingDataComparator.setInvalidationListener(this::invalidate);
    sourceHolderPriorityList = new ArrayList<>();
  }

  /**
   * Adds a {@link PreloadManagerListener} to listen to the preload events.
   *
   * <p>This method can be called from any thread.
   */
  public void addListener(PreloadManagerListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a {@link PreloadManagerListener}.
   *
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void removeListener(PreloadManagerListener listener) {
    verifyApplicationThread();
    listeners.remove(listener);
  }

  /**
   * Clears all the {@linkplain PreloadManagerListener listeners}.
   *
   * @throws IllegalStateException If this method is called from the wrong thread.
   */
  public void clearListeners() {
    verifyApplicationThread();
    listeners.clear();
  }

  /**
   * Gets the count of the {@linkplain MediaSource media sources} currently being managed by the
   * preload manager.
   *
   * @return The count of the {@linkplain MediaSource media sources}.
   */
  public final int getSourceCount() {
    return mediaSourceHolderMap.size();
  }

  /**
   * Adds a list of {@linkplain MediaItem media items} with their {@code rankingData} to the preload
   * manager.
   *
   * @param mediaItems The {@linkplain MediaItem media items} to add.
   * @param rankingDataList The ranking data that are associated with each media item.
   * @throws IllegalArgumentException If the passed {@code mediaSources} and {@code rankingDataList}
   *     are different in sizes.
   */
  public final void addMediaItems(List<MediaItem> mediaItems, List<T> rankingDataList) {
    checkArgument(mediaItems.size() == rankingDataList.size());
    for (int i = 0; i < mediaItems.size(); i++) {
      add(mediaItems.get(i), rankingDataList.get(i));
    }
    invalidate();
  }

  /**
   * Adds a {@link MediaItem} with its {@code rankingData} to the preload manager.
   *
   * @param mediaItem The {@link MediaItem} to add.
   * @param rankingData The ranking data that is associated with the {@code mediaItem}.
   */
  public final void add(MediaItem mediaItem, T rankingData) {
    add(mediaSourceFactory.createMediaSource(mediaItem), rankingData);
  }

  /**
   * Adds a list of {@linkplain MediaSource media sources} with their {@code rankingData} to the
   * preload manager.
   *
   * @param mediaSources The {@linkplain MediaSource media sources} to add.
   * @param rankingDataList The ranking data that are associated with each media source.
   * @throws IllegalArgumentException If the passed {@code mediaSources} and {@code rankingDataList}
   *     are different in sizes.
   */
  public final void addMediaSources(List<MediaSource> mediaSources, List<T> rankingDataList) {
    checkArgument(mediaSources.size() == rankingDataList.size());
    for (int i = 0; i < mediaSources.size(); i++) {
      add(mediaSources.get(i), rankingDataList.get(i));
    }
    invalidate();
  }

  /**
   * Adds a {@link MediaSource} with its {@code rankingData} to the preload manager.
   *
   * @param mediaSource The {@link MediaSource} to add.
   * @param rankingData The ranking data that is associated with the {@code mediaSource}.
   */
  public final void add(MediaSource mediaSource, T rankingData) {
    MediaSourceHolder mediaHolder =
        createMediaSourceHolder(mediaSource.getMediaItem(), mediaSource, rankingData);
    mediaSourceHolderMap.put(mediaHolder.mediaItem, mediaHolder.getMediaSource(), mediaHolder);
  }

  /**
   * Invalidates the current preload progress, and triggers a new preload progress based on the new
   * priorities of the managed {@linkplain MediaSource media sources}.
   */
  public final void invalidate() {
    synchronized (lock) {
      resetSourceHolderPriorityList();
      while (indexForSourceHolderToPreload < sourceHolderPriorityList.size()
          && !maybeStartPreloadingNextSourceHolder()) {
        indexForSourceHolderToPreload++;
      }
    }
  }

  @GuardedBy("lock")
  private void resetSourceHolderPriorityList() {
    sourceHolderPriorityList.clear();
    sourceHolderPriorityList.addAll(mediaSourceHolderMap.values());
    Collections.sort(sourceHolderPriorityList);
    indexForSourceHolderToPreload = 0;
    indexForSourceHolderToClear = sourceHolderPriorityList.size() - 1;
  }

  /**
   * Returns the {@link MediaSource} for the given {@link MediaItem}.
   *
   * @param mediaItem The media item.
   * @return The source for the given {@code mediaItem} if it is managed by the preload manager,
   *     null otherwise.
   */
  @Nullable
  public final MediaSource getMediaSource(MediaItem mediaItem) {
    if (!mediaSourceHolderMap.containsKey(mediaItem)) {
      return null;
    }
    return checkNotNull(mediaSourceHolderMap.get(mediaItem)).getMediaSource();
  }

  /**
   * Removes a {@link MediaItem} from the preload manager.
   *
   * @param mediaItem The {@link MediaItem} to remove.
   * @return {@code true} if the preload manager is holding a {@link MediaSource} of the given
   *     {@link MediaItem} and it has been removed, otherwise {@code false}.
   */
  public final boolean remove(MediaItem mediaItem) {
    @Nullable MediaSourceHolder mediaSourceHolder = mediaSourceHolderMap.get(mediaItem);
    if (mediaSourceHolder != null) {
      releaseMediaSourceHolderInternal(mediaSourceHolder);
      mediaSourceHolderMap.remove(mediaItem);
      if (isCurrentlyPreloading(mediaSourceHolder)) {
        maybeAdvanceToNextMediaSourceHolder();
      }
      return true;
    }
    return false;
  }

  /**
   * Removes a list of {@linkplain MediaItem media items} from the preload manager.
   *
   * @param mediaItems The {@linkplain MediaItem media items} to remove.
   */
  public final void removeMediaItems(List<MediaItem> mediaItems) {
    for (MediaItem mediaItem : mediaItems) {
      @Nullable MediaSourceHolder mediaSourceHolder = mediaSourceHolderMap.get(mediaItem);
      if (mediaSourceHolder != null) {
        releaseMediaSourceHolderInternal(mediaSourceHolder);
        mediaSourceHolderMap.remove(mediaItem);
      }
    }
    @Nullable MediaSourceHolder currentMediaSourceHolder;
    synchronized (lock) {
      currentMediaSourceHolder = getCurrentlyPreloadingMediaSourceHolder();
    }
    if (currentMediaSourceHolder != null && currentMediaSourceHolder.isReleased()) {
      maybeAdvanceToNextMediaSourceHolder();
    }
  }

  /**
   * Removes a {@link MediaSource} from the preload manager.
   *
   * @param mediaSource The {@link MediaSource} to remove.
   * @return {@code true} if the preload manager is holding the given {@link MediaSource} instance
   *     and it has been removed, otherwise {@code false}.
   */
  public final boolean remove(MediaSource mediaSource) {
    @Nullable MediaSourceHolder mediaSourceHolder = mediaSourceHolderMap.get(mediaSource);
    if (mediaSourceHolder != null) {
      releaseMediaSourceHolderInternal(mediaSourceHolder);
      mediaSourceHolderMap.remove(mediaSource);
      if (isCurrentlyPreloading(mediaSourceHolder)) {
        maybeAdvanceToNextMediaSourceHolder();
      }
      return true;
    }
    return false;
  }

  /**
   * Removes a list of {@linkplain MediaSource media sources} from the preload manager.
   *
   * @param mediaSources The {@linkplain MediaSource media sources} to remove.
   */
  public final void removeMediaSources(List<MediaSource> mediaSources) {
    for (MediaSource mediaSource : mediaSources) {
      @Nullable MediaSourceHolder mediaSourceHolder = mediaSourceHolderMap.get(mediaSource);
      if (mediaSourceHolder != null) {
        releaseMediaSourceHolderInternal(mediaSourceHolder);
        mediaSourceHolderMap.remove(mediaSource);
      }
    }
    @Nullable MediaSourceHolder currentMediaSourceHolder;
    synchronized (lock) {
      currentMediaSourceHolder = getCurrentlyPreloadingMediaSourceHolder();
    }
    if (currentMediaSourceHolder != null && currentMediaSourceHolder.isReleased()) {
      maybeAdvanceToNextMediaSourceHolder();
    }
  }

  /**
   * Resets the preload manager. All sources that the preload manager is holding will be released.
   */
  public final void reset() {
    for (MediaSourceHolder mediaHolder : mediaSourceHolderMap.values()) {
      releaseMediaSourceHolderInternal(mediaHolder);
    }
    mediaSourceHolderMap.clear();
    synchronized (lock) {
      resetSourceHolderPriorityList();
      targetPreloadStatusOfCurrentPreloadingSource = null;
    }
  }

  /**
   * Releases the preload manager.
   *
   * <p>The preload manager must not be used after calling this method.
   */
  public final void release() {
    reset();
    releaseInternal();
    clearListeners();
  }

  /** Called when the given {@link MediaSource} completes preloading. */
  protected final void onCompleted(
      MediaSource mediaSource, Predicate<PreloadStatusT> shouldNotifyListenerAndAdvancePredicate) {
    applicationHandler.post(
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
          if (targetPreloadStatus == null) {
            return;
          }

          MediaSourceHolder mediaSourceHolder = checkNotNull(mediaSourceHolderMap.get(mediaSource));
          if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
            listeners.sendEvent(listener -> listener.onCompleted(mediaSourceHolder.mediaItem));
            maybeAdvanceToNextMediaSourceHolder();
          }
        });
  }

  /** Called when the given {@link MediaItem} completes preloading. */
  protected final void onCompleted(
      MediaItem mediaItem, Predicate<PreloadStatusT> shouldNotifyListenerAndAdvancePredicate) {
    applicationHandler.post(
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
          if (targetPreloadStatus == null) {
            return;
          }

          MediaSourceHolder mediaSourceHolder = checkNotNull(mediaSourceHolderMap.get(mediaItem));
          if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
            listeners.sendEvent(listener -> listener.onCompleted(mediaSourceHolder.mediaItem));
            maybeAdvanceToNextMediaSourceHolder();
          }
        });
  }

  /** Called when an error occurs. */
  protected final void onError(
      PreloadException error,
      MediaSource mediaSource,
      Predicate<PreloadStatusT> shouldNotifyListenerAndAdvancePredicate) {
    applicationHandler.post(
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
          if (targetPreloadStatus == null) {
            return;
          }

          if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
            listeners.sendEvent(listener -> listener.onError(error));
            maybeAdvanceToNextMediaSourceHolder();
          }
        });
  }

  /** Called when an error occurs. */
  protected final void onError(
      PreloadException error,
      MediaItem mediaItem,
      Predicate<PreloadStatusT> shouldNotifyListenerAndAdvancePredicate) {
    applicationHandler.post(
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
          if (targetPreloadStatus == null) {
            return;
          }

          if (shouldNotifyListenerAndAdvancePredicate.apply(targetPreloadStatus)) {
            listeners.sendEvent(listener -> listener.onError(error));
            maybeAdvanceToNextMediaSourceHolder();
          }
        });
  }

  /** Called when the given {@link MediaSource} has been skipped before completing preloading. */
  protected final void onSkipped(
      MediaSource mediaSource, Predicate<PreloadStatusT> shouldAdvancePredicate) {
    postOrRun(
        applicationHandler,
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
          if (targetPreloadStatus == null) {
            return;
          }

          if (shouldAdvancePredicate.apply(targetPreloadStatus)) {
            maybeAdvanceToNextMediaSourceHolder();
          }
        });
  }

  /** Called when there is a {@link MediaSource} has been cleared. */
  protected final void onSourceCleared() {
    synchronized (lock) {
      indexForSourceHolderToClear--;
    }
  }

  /**
   * Called when the given {@link MediaItem} has its corresponding {@link MediaSource} updated.
   *
   * @param mediaItem The {@link MediaItem} that apps have added with.
   * @param updatedMediaSource The updated {@link MediaSource}.
   */
  protected final void onMediaSourceUpdated(MediaItem mediaItem, MediaSource updatedMediaSource) {
    postOrRun(
        applicationHandler,
        () -> {
          PreloadStatusT targetPreloadStatus =
              getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
          if (targetPreloadStatus == null) {
            return;
          }
          MediaSourceHolder sourceHolder = checkNotNull(mediaSourceHolderMap.get(mediaItem));
          mediaSourceHolderMap.remove(mediaItem);
          sourceHolder.setMediaSource(updatedMediaSource);
          mediaSourceHolderMap.put(mediaItem, updatedMediaSource, sourceHolder);
        });
  }

  private void maybeAdvanceToNextMediaSourceHolder() {
    synchronized (lock) {
      do {
        indexForSourceHolderToPreload++;
      } while (indexForSourceHolderToPreload < sourceHolderPriorityList.size()
          && !maybeStartPreloadingNextSourceHolder());
    }
  }

  @GuardedBy("lock")
  @Nullable
  private MediaSourceHolder getCurrentlyPreloadingMediaSourceHolder() {
    if (indexForSourceHolderToPreload >= sourceHolderPriorityList.size()) {
      return null;
    }
    return sourceHolderPriorityList.get(indexForSourceHolderToPreload);
  }

  @Nullable
  protected MediaSourceHolder getMediaSourceHolderToClear() {
    synchronized (lock) {
      if (indexForSourceHolderToPreload >= indexForSourceHolderToClear) {
        return null;
      }
      return sourceHolderPriorityList.get(indexForSourceHolderToClear);
    }
  }

  @Nullable
  protected final PreloadStatusT getTargetPreloadStatusIfCurrentlyPreloading(
      MediaSource mediaSource) {
    synchronized (lock) {
      MediaSourceHolder currentMediaHolder = getCurrentlyPreloadingMediaSourceHolder();
      if (currentMediaHolder == null || mediaSource != currentMediaHolder.getMediaSource()) {
        return null;
      }
      return targetPreloadStatusOfCurrentPreloadingSource;
    }
  }

  @Nullable
  protected final PreloadStatusT getTargetPreloadStatusIfCurrentlyPreloading(MediaItem mediaItem) {
    synchronized (lock) {
      MediaSourceHolder currentMediaHolder = getCurrentlyPreloadingMediaSourceHolder();
      if (currentMediaHolder == null || !mediaItem.equals(currentMediaHolder.mediaItem)) {
        return null;
      }
      return targetPreloadStatusOfCurrentPreloadingSource;
    }
  }

  private boolean isCurrentlyPreloading(MediaSourceHolder mediaSourceHolder) {
    synchronized (lock) {
      return mediaSourceHolder == getCurrentlyPreloadingMediaSourceHolder();
    }
  }

  /** Returns whether the next {@link MediaSource} should start preloading. */
  protected boolean shouldStartPreloadingNextSource() {
    return true;
  }

  /**
   * Returns the {@link MediaSourceHolder} that the preload manager creates for preloading based on
   * the given {@link MediaItem} and {@link MediaSource}.
   *
   * @param mediaItem The {@link MediaItem}.
   * @param mediaSource The {@link MediaSource} based on which the preload manager creates for
   *     preloading.
   * @param rankingData The ranking data that is associated with the {@code mediaItem}.
   * @return The {@link MediaSourceHolder} the preload manager creates for preloading.
   */
  protected abstract MediaSourceHolder createMediaSourceHolder(
      MediaItem mediaItem, MediaSource mediaSource, T rankingData);

  /**
   * Preloads the given {@link MediaSourceHolder}.
   *
   * @param mediaSourceHolder The {@link MediaSourceHolder} to preload.
   * @param targetPreloadStatus The target preload status.
   */
  protected abstract void preloadMediaSourceHolderInternal(
      MediaSourceHolder mediaSourceHolder, PreloadStatusT targetPreloadStatus);

  /**
   * Releases the given {@link MediaSourceHolder}.
   *
   * @param mediaSourceHolder The {@link MediaSourceHolder} to remove.
   */
  @CallSuper
  protected void releaseMediaSourceHolderInternal(MediaSourceHolder mediaSourceHolder) {
    mediaSourceHolder.release();
  }

  /** Releases the preload manager, see {@link #release()}. */
  protected void releaseInternal() {}

  /**
   * Starts to preload the {@link MediaSource} at the head of the priority list.
   *
   * @return {@code true} if the {@link MediaSource} at the head of the priority list starts to
   *     preload, otherwise {@code false}.
   */
  @GuardedBy("lock")
  private boolean maybeStartPreloadingNextSourceHolder() {
    if (shouldStartPreloadingNextSource()) {
      MediaSourceHolder preloadingHolder =
          sourceHolderPriorityList.get(indexForSourceHolderToPreload);
      if (preloadingHolder.isReleased()) {
        return false;
      }
      this.targetPreloadStatusOfCurrentPreloadingSource =
          targetPreloadStatusControl.getTargetPreloadStatus(preloadingHolder.rankingData);
      preloadMediaSourceHolderInternal(
          preloadingHolder, targetPreloadStatusOfCurrentPreloadingSource);
      return true;
    }
    return false;
  }

  private void verifyApplicationThread() {
    if (Looper.myLooper() != applicationHandler.getLooper()) {
      throw new IllegalStateException("Preload manager is accessed on the wrong thread.");
    }
  }

  /** A holder for information for preloading a single media source. */
  protected class MediaSourceHolder implements Comparable<MediaSourceHolder> {

    public final MediaItem mediaItem;
    public final T rankingData;
    private MediaSource mediaSource;
    private boolean released;

    public MediaSourceHolder(MediaItem mediaItem, T rankingData, MediaSource mediaSource) {
      this.mediaItem = mediaItem;
      this.rankingData = rankingData;
      this.mediaSource = mediaSource;
    }

    public final void release() {
      released = true;
    }

    public final boolean isReleased() {
      return released;
    }

    public synchronized MediaSource getMediaSource() {
      return mediaSource;
    }

    public synchronized void setMediaSource(MediaSource mediaSource) {
      this.mediaSource = mediaSource;
    }

    @Override
    public int compareTo(MediaSourceHolder o) {
      return rankingDataComparator.compare(this.rankingData, o.rankingData);
    }
  }

  /**
   * An internal util class that can fetch a {@link MediaSourceHolder} via a {@link MediaItem} or a
   * {@link MediaSource}.
   */
  private final class MediaSourceHolderMap {

    @GuardedBy("this")
    private final HashMap<MediaItem, MediaSourceHolder> mediaItemToMediaSourceHolder;

    @GuardedBy("this")
    private final HashMap<MediaSource, MediaItem> mediaSourceToMediaItem;

    public MediaSourceHolderMap() {
      mediaItemToMediaSourceHolder = new HashMap<>();
      mediaSourceToMediaItem = new HashMap<>();
    }

    public synchronized void put(
        MediaItem mediaItem, MediaSource mediaSource, MediaSourceHolder mediaSourceHolder) {
      mediaItemToMediaSourceHolder.put(mediaItem, mediaSourceHolder);
      mediaSourceToMediaItem.put(mediaSource, mediaItem);
    }

    public synchronized Collection<MediaSourceHolder> values() {
      return mediaItemToMediaSourceHolder.values();
    }

    public synchronized int size() {
      return mediaItemToMediaSourceHolder.size();
    }

    public synchronized boolean containsKey(MediaItem mediaItem) {
      return mediaItemToMediaSourceHolder.containsKey(mediaItem);
    }

    @Nullable
    public synchronized MediaSourceHolder get(MediaItem mediaItem) {
      return mediaItemToMediaSourceHolder.get(mediaItem);
    }

    @Nullable
    public synchronized MediaSourceHolder get(MediaSource mediaSource) {
      MediaItem mediaItem = mediaSourceToMediaItem.get(mediaSource);
      if (mediaItem != null) {
        return checkNotNull(mediaItemToMediaSourceHolder.get(mediaItem));
      }
      return null;
    }

    public synchronized boolean remove(MediaItem mediaItem) {
      MediaSourceHolder mediaSourceHolder = mediaItemToMediaSourceHolder.remove(mediaItem);
      if (mediaSourceHolder == null) {
        return false;
      }
      checkNotNull(mediaSourceToMediaItem.remove(mediaSourceHolder.getMediaSource()));
      return true;
    }

    public synchronized boolean remove(MediaSource mediaSource) {
      MediaItem mediaItem = mediaSourceToMediaItem.remove(mediaSource);
      if (mediaItem == null) {
        return false;
      }
      checkNotNull(mediaItemToMediaSourceHolder.remove(mediaItem));
      return true;
    }

    public synchronized void clear() {
      mediaItemToMediaSourceHolder.clear();
      mediaSourceToMediaItem.clear();
    }
  }
}
