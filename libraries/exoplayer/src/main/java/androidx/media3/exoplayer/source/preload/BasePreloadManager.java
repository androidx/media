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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.postOrRun;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.MediaSource;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

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

    protected final Comparator<T> rankingDataComparator;
    protected final TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl;
    protected Supplier<MediaSource.Factory> mediaSourceFactorySupplier;

    public BuilderBase(
        Comparator<T> rankingDataComparator,
        TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl,
        Supplier<MediaSource.Factory> mediaSourceFactorySupplier) {
      this.rankingDataComparator = rankingDataComparator;
      this.targetPreloadStatusControl = targetPreloadStatusControl;
      this.mediaSourceFactorySupplier = mediaSourceFactorySupplier;
    }

    public abstract BasePreloadManager<T, PreloadStatusT> build();
  }

  private final Object lock;
  protected final Comparator<T> rankingDataComparator;
  private final TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl;
  private final MediaSource.Factory mediaSourceFactory;
  private final ListenerSet<PreloadManagerListener> listeners;
  private final MediaSourceHolderMap mediaSourceHolderMap;
  private final Handler applicationHandler;

  @GuardedBy("lock")
  private final PriorityQueue<MediaSourceHolder> sourceHolderPriorityQueue;

  @GuardedBy("lock")
  @Nullable
  private PreloadStatusT targetPreloadStatusOfCurrentPreloadingSource;

  protected BasePreloadManager(
      Comparator<T> rankingDataComparator,
      TargetPreloadStatusControl<T, PreloadStatusT> targetPreloadStatusControl,
      MediaSource.Factory mediaSourceFactory) {
    lock = new Object();
    applicationHandler = Util.createHandlerForCurrentOrMainLooper();
    this.rankingDataComparator = rankingDataComparator;
    this.targetPreloadStatusControl = targetPreloadStatusControl;
    this.mediaSourceFactory = mediaSourceFactory;
    listeners =
        new ListenerSet<>(applicationHandler.getLooper(), Clock.DEFAULT, (listener, flags) -> {});
    mediaSourceHolderMap = new MediaSourceHolderMap();
    sourceHolderPriorityQueue = new PriorityQueue<>();
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
   * Adds a {@link MediaItem} with its {@code rankingData} to the preload manager.
   *
   * @param mediaItem The {@link MediaItem} to add.
   * @param rankingData The ranking data that is associated with the {@code mediaItem}.
   */
  public final void add(MediaItem mediaItem, T rankingData) {
    add(mediaSourceFactory.createMediaSource(mediaItem), rankingData);
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
      sourceHolderPriorityQueue.clear();
      sourceHolderPriorityQueue.addAll(mediaSourceHolderMap.values());
      while (!sourceHolderPriorityQueue.isEmpty() && !maybeStartPreloadingNextSourceHolder()) {
        sourceHolderPriorityQueue.poll();
      }
    }
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
    if (mediaSourceHolderMap.containsKey(mediaItem)) {
      MediaSourceHolder mediaHolder = checkNotNull(mediaSourceHolderMap.get(mediaItem));
      if (isCurrentlyPreloading(mediaHolder)) {
        maybeAdvanceToNextMediaSourceHolder();
      }
      releaseMediaSourceHolderInternal(mediaHolder);
      mediaSourceHolderMap.remove(mediaItem);
      return true;
    }
    return false;
  }

  /**
   * Removes a {@link MediaSource} from the preload manager.
   *
   * @param mediaSource The {@link MediaSource} to remove.
   * @return {@code true} if the preload manager is holding the given {@link MediaSource} instance
   *     and it has been removed, otherwise {@code false}.
   */
  public final boolean remove(MediaSource mediaSource) {
    if (mediaSourceHolderMap.containsKey(mediaSource)) {
      MediaSourceHolder mediaHolder = checkNotNull(mediaSourceHolderMap.get(mediaSource));
      if (isCurrentlyPreloading(mediaHolder)) {
        maybeAdvanceToNextMediaSourceHolder();
      }
      releaseMediaSourceHolderInternal(mediaHolder);
      mediaSourceHolderMap.remove(mediaSource);
      return true;
    }
    return false;
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
      sourceHolderPriorityQueue.clear();
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
            listeners.sendEvent(
                /* eventFlag= */ C.INDEX_UNSET,
                listener -> listener.onCompleted(mediaSourceHolder.mediaItem));
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
            listeners.sendEvent(
                /* eventFlag= */ C.INDEX_UNSET,
                listener -> listener.onCompleted(mediaSourceHolder.mediaItem));
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
            listeners.sendEvent(
                /* eventFlag= */ C.INDEX_UNSET, listener -> listener.onError(error));
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
            listeners.sendEvent(
                /* eventFlag= */ C.INDEX_UNSET, listener -> listener.onError(error));
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
        sourceHolderPriorityQueue.poll();
      } while (!sourceHolderPriorityQueue.isEmpty() && !maybeStartPreloadingNextSourceHolder());
    }
  }

  @GuardedBy("lock")
  @Nullable
  private MediaSourceHolder getCurrentlyPreloadingMediaSourceHolder() {
    if (sourceHolderPriorityQueue.isEmpty()) {
      return null;
    }
    return sourceHolderPriorityQueue.peek();
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
  protected abstract void releaseMediaSourceHolderInternal(MediaSourceHolder mediaSourceHolder);

  /** Releases the preload manager, see {@link #release()}. */
  protected void releaseInternal() {}

  /**
   * Starts to preload the {@link MediaSource} at the head of the priority queue.
   *
   * @return {@code true} if the {@link MediaSource} at the head of the priority queue starts to
   *     preload, otherwise {@code false}.
   * @throws NullPointerException if the priority queue is empty.
   */
  @GuardedBy("lock")
  private boolean maybeStartPreloadingNextSourceHolder() {
    if (shouldStartPreloadingNextSource()) {
      MediaSourceHolder preloadingHolder = checkNotNull(sourceHolderPriorityQueue.peek());
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

    public MediaSourceHolder(MediaItem mediaItem, T rankingData, MediaSource mediaSource) {
      this.mediaItem = mediaItem;
      this.rankingData = rankingData;
      this.mediaSource = mediaSource;
    }

    public synchronized MediaSource getMediaSource() {
      return mediaSource;
    }

    public synchronized void setMediaSource(MediaSource mediaSource) {
      this.mediaSource = mediaSource;
    }

    public void release() {}

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

    public synchronized boolean containsKey(MediaSource mediaSource) {
      MediaItem mediaItem = mediaSourceToMediaItem.get(mediaSource);
      if (mediaItem != null) {
        checkState(mediaItemToMediaSourceHolder.containsKey(mediaItem));
        return true;
      }
      return false;
    }

    @Nullable
    public synchronized MediaSourceHolder get(MediaItem mediaItem) {
      return mediaItemToMediaSourceHolder.get(mediaItem);
    }

    @Nullable
    public synchronized MediaSourceHolder get(MediaSource mediaSource) {
      MediaItem mediaItem = mediaSourceToMediaItem.get(mediaSource);
      if (mediaItem != null) {
        return checkStateNotNull(mediaItemToMediaSourceHolder.get(mediaItem));
      }
      return null;
    }

    public synchronized boolean remove(MediaItem mediaItem) {
      MediaSourceHolder mediaSourceHolder = mediaItemToMediaSourceHolder.remove(mediaItem);
      if (mediaSourceHolder == null) {
        return false;
      }
      checkStateNotNull(mediaSourceToMediaItem.remove(mediaSourceHolder.getMediaSource()));
      return true;
    }

    public synchronized boolean remove(MediaSource mediaSource) {
      MediaItem mediaItem = mediaSourceToMediaItem.remove(mediaSource);
      if (mediaItem == null) {
        return false;
      }
      checkStateNotNull(mediaItemToMediaSourceHolder.remove(mediaItem));
      return true;
    }

    public synchronized void clear() {
      mediaItemToMediaSourceHolder.clear();
      mediaSourceToMediaItem.clear();
    }
  }
}
