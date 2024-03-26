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

import android.os.Handler;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A base implementation of a preload manager, which maintains the lifecycle of {@linkplain
 * MediaSource media sources}.
 *
 * <p>Methods should be called on the same thread.
 */
@UnstableApi
public abstract class BasePreloadManager<T> {

  /** A base class of the builder of the concrete extension of {@link BasePreloadManager}. */
  protected abstract static class BuilderBase<T> {

    protected final Comparator<T> rankingDataComparator;
    protected final TargetPreloadStatusControl<T> targetPreloadStatusControl;
    protected final MediaSource.Factory mediaSourceFactory;

    public BuilderBase(
        Comparator<T> rankingDataComparator,
        TargetPreloadStatusControl<T> targetPreloadStatusControl,
        MediaSource.Factory mediaSourceFactory) {
      this.rankingDataComparator = rankingDataComparator;
      this.targetPreloadStatusControl = targetPreloadStatusControl;
      this.mediaSourceFactory = mediaSourceFactory;
    }

    public abstract BasePreloadManager<T> build();
  }

  private final Object lock;
  protected final Comparator<T> rankingDataComparator;
  private final TargetPreloadStatusControl<T> targetPreloadStatusControl;
  private final MediaSource.Factory mediaSourceFactory;
  private final Map<MediaItem, MediaSourceHolder> mediaItemMediaSourceHolderMap;
  private final Handler startPreloadingHandler;

  @GuardedBy("lock")
  private final PriorityQueue<MediaSourceHolder> sourceHolderPriorityQueue;

  @GuardedBy("lock")
  @Nullable
  private TargetPreloadStatusControl.PreloadStatus targetPreloadStatusOfCurrentPreloadingSource;

  protected BasePreloadManager(
      Comparator<T> rankingDataComparator,
      TargetPreloadStatusControl<T> targetPreloadStatusControl,
      MediaSource.Factory mediaSourceFactory) {
    lock = new Object();
    this.rankingDataComparator = rankingDataComparator;
    this.targetPreloadStatusControl = targetPreloadStatusControl;
    this.mediaSourceFactory = mediaSourceFactory;
    mediaItemMediaSourceHolderMap = new HashMap<>();
    startPreloadingHandler = Util.createHandlerForCurrentOrMainLooper();
    sourceHolderPriorityQueue = new PriorityQueue<>();
  }

  /**
   * Gets the count of the {@linkplain MediaSource media sources} currently being managed by the
   * preload manager.
   *
   * @return The count of the {@linkplain MediaSource media sources}.
   */
  public final int getSourceCount() {
    return mediaItemMediaSourceHolderMap.size();
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
    MediaSource mediaSourceForPreloading = createMediaSourceForPreloading(mediaSource);
    MediaSourceHolder mediaSourceHolder =
        new MediaSourceHolder(mediaSourceForPreloading, rankingData);
    mediaItemMediaSourceHolderMap.put(mediaSourceForPreloading.getMediaItem(), mediaSourceHolder);
  }

  /**
   * Invalidates the current preload progress, and triggers a new preload progress based on the new
   * priorities of the managed {@linkplain MediaSource media sources}.
   */
  public final void invalidate() {
    synchronized (lock) {
      sourceHolderPriorityQueue.clear();
      sourceHolderPriorityQueue.addAll(mediaItemMediaSourceHolderMap.values());
      while (!sourceHolderPriorityQueue.isEmpty() && !maybeStartPreloadNextSource()) {
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
    if (!mediaItemMediaSourceHolderMap.containsKey(mediaItem)) {
      return null;
    }
    return mediaItemMediaSourceHolderMap.get(mediaItem).mediaSource;
  }

  /**
   * Removes a {@link MediaItem} from the preload manager.
   *
   * @param mediaItem The {@link MediaItem} to remove.
   */
  public final void remove(MediaItem mediaItem) {
    if (mediaItemMediaSourceHolderMap.containsKey(mediaItem)) {
      MediaSource mediaSource = mediaItemMediaSourceHolderMap.get(mediaItem).mediaSource;
      mediaItemMediaSourceHolderMap.remove(mediaItem);
      releaseSourceInternal(mediaSource);
    }
  }

  /** Releases the preload manager. */
  public final void release() {
    for (MediaSourceHolder sourceHolder : mediaItemMediaSourceHolderMap.values()) {
      releaseSourceInternal(sourceHolder.mediaSource);
    }
    mediaItemMediaSourceHolderMap.clear();
    synchronized (lock) {
      sourceHolderPriorityQueue.clear();
      targetPreloadStatusOfCurrentPreloadingSource = null;
    }
    releaseInternal();
  }

  /** Called when the given {@link MediaSource} completes to preload. */
  protected final void onPreloadCompleted(MediaSource source) {
    startPreloadingHandler.post(
        () -> {
          synchronized (lock) {
            if (sourceHolderPriorityQueue.isEmpty()
                || checkNotNull(sourceHolderPriorityQueue.peek()).mediaSource != source) {
              return;
            }
            do {
              sourceHolderPriorityQueue.poll();
            } while (!sourceHolderPriorityQueue.isEmpty() && !maybeStartPreloadNextSource());
          }
        });
  }

  /**
   * Returns the {@linkplain TargetPreloadStatusControl.PreloadStatus target preload status} of the
   * given {@link MediaSource}.
   */
  @Nullable
  protected final TargetPreloadStatusControl.PreloadStatus getTargetPreloadStatus(
      MediaSource source) {
    synchronized (lock) {
      if (sourceHolderPriorityQueue.isEmpty()
          || checkNotNull(sourceHolderPriorityQueue.peek()).mediaSource != source) {
        return null;
      }
      return targetPreloadStatusOfCurrentPreloadingSource;
    }
  }

  /**
   * Returns the {@link MediaSource} that the preload manager creates for preloading based on the
   * given {@link MediaSource source}. The default implementation returns the same source.
   *
   * @param mediaSource The source based on which the preload manager creates for preloading.
   * @return The source the preload manager creates for preloading.
   */
  protected MediaSource createMediaSourceForPreloading(MediaSource mediaSource) {
    return mediaSource;
  }

  /** Returns whether the next {@link MediaSource} should start preloading. */
  protected boolean shouldStartPreloadingNextSource() {
    return true;
  }

  /**
   * Preloads the given {@link MediaSource}.
   *
   * @param mediaSource The media source to preload.
   * @param startPositionsUs The expected starting position in microseconds, or {@link C#TIME_UNSET}
   *     to indicate the default start position.
   */
  protected abstract void preloadSourceInternal(MediaSource mediaSource, long startPositionsUs);

  /**
   * Releases the given {@link MediaSource}.
   *
   * @param mediaSource The media source to release.
   */
  protected abstract void releaseSourceInternal(MediaSource mediaSource);

  /** Releases the preload manager, see {@link #release()}. */
  protected void releaseInternal() {}

  /**
   * Starts to preload the {@link MediaSource} at the head of the priority queue, if the {@linkplain
   * TargetPreloadStatusControl.PreloadStatus target preload status} for that source is not null.
   *
   * @return {@code true} if the {@link MediaSource} at the head of the priority queue starts to
   *     preload, otherwise {@code false}.
   * @throws NullPointerException if the priority queue is empty.
   */
  @GuardedBy("lock")
  private boolean maybeStartPreloadNextSource() {
    if (shouldStartPreloadingNextSource()) {
      MediaSourceHolder preloadingHolder = checkNotNull(sourceHolderPriorityQueue.peek());
      this.targetPreloadStatusOfCurrentPreloadingSource =
          targetPreloadStatusControl.getTargetPreloadStatus(preloadingHolder.rankingData);
      if (targetPreloadStatusOfCurrentPreloadingSource != null) {
        preloadSourceInternal(preloadingHolder.mediaSource, preloadingHolder.startPositionUs);
        return true;
      }
    }
    return false;
  }

  /** A holder for information for preloading a single media source. */
  private final class MediaSourceHolder implements Comparable<MediaSourceHolder> {

    public final MediaSource mediaSource;
    public final T rankingData;
    public final long startPositionUs;

    public MediaSourceHolder(MediaSource mediaSource, T rankingData) {
      this(mediaSource, rankingData, C.TIME_UNSET);
    }

    public MediaSourceHolder(MediaSource mediaSource, T rankingData, long startPositionUs) {
      this.mediaSource = mediaSource;
      this.rankingData = rankingData;
      this.startPositionUs = startPositionUs;
    }

    @Override
    public int compareTo(BasePreloadManager<T>.MediaSourceHolder o) {
      return rankingDataComparator.compare(this.rankingData, o.rankingData);
    }
  }
}
