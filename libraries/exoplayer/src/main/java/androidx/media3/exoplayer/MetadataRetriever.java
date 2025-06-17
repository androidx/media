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
package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.Mp4Extractor;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Retrieves information from a {@link MediaItem} without playback.
 *
 * <p>An instance is created for a single {@link MediaItem} via a {@link Builder}. It provides
 * methods to asynchronously retrieve metadata. The instance must be {@link #close() closed} after
 * use to release resources.
 */
@UnstableApi
public final class MetadataRetriever implements AutoCloseable {

  /** Builder for {@link MetadataRetriever} instances. */
  public static final class Builder {

    @Nullable private final Context context;
    private final MediaItem mediaItem;
    @Nullable private MediaSource.Factory mediaSourceFactory;
    private Clock clock;

    /**
     * Creates a new builder.
     *
     * @param context The {@link Context}. Can be {@code null} if a {@link MediaSource.Factory} is
     *     provided via {@link #setMediaSourceFactory(MediaSource.Factory)}.
     * @param mediaItem The {@link MediaItem} to retrieve metadata from.
     */
    public Builder(@Nullable Context context, MediaItem mediaItem) {
      this.context = context != null ? context.getApplicationContext() : null;
      this.mediaItem = checkNotNull(mediaItem);
      this.clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link MediaSource.Factory} to be used to read the data. If not set, a {@link
     * DefaultMediaSourceFactory} with default extractors will be used.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      this.mediaSourceFactory = checkNotNull(mediaSourceFactory);
      return this;
    }

    /**
     * Sets the {@link Clock} to be used. If not set, {@link Clock#DEFAULT} is used.
     *
     * @param clock The {@link Clock}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = checkNotNull(clock);
      return this;
    }

    /** Builds the {@link MetadataRetriever} instance. */
    public MetadataRetriever build() {
      if (mediaSourceFactory == null) {
        checkStateNotNull(context, "Context must be provided if MediaSource.Factory is not set.");
        ExtractorsFactory extractorsFactory =
            new DefaultExtractorsFactory()
                .setMp4ExtractorFlags(
                    Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA | Mp4Extractor.FLAG_READ_SEF_DATA);
        mediaSourceFactory = new DefaultMediaSourceFactory(context, extractorsFactory);
      }
      return new MetadataRetriever(mediaItem, checkNotNull(mediaSourceFactory), clock);
    }
  }

  private static final class InternalResult {
    public final TrackGroupArray trackGroups;
    public final Timeline timeline;

    public InternalResult(TrackGroupArray trackGroups, Timeline timeline) {
      this.trackGroups = trackGroups;
      this.timeline = timeline;
    }
  }

  /** The default number of maximum parallel retrievals. */
  public static final int DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS = 5;

  private final MediaItem mediaItem;
  private final MediaSource.Factory mediaSourceFactory;
  private final Clock clock;
  private final Object lock;

  @GuardedBy("lock")
  private final List<ListenableFuture<?>> allFutures;

  @GuardedBy("lock")
  private @MonotonicNonNull SettableFuture<InternalResult> preparationFuture;

  @GuardedBy("lock")
  private @MonotonicNonNull MetadataRetrieverInternal internalRetriever;

  @GuardedBy("lock")
  private boolean released;

  private MetadataRetriever(
      MediaItem mediaItem, MediaSource.Factory mediaSourceFactory, Clock clock) {
    this.mediaItem = mediaItem;
    this.mediaSourceFactory = mediaSourceFactory;
    this.clock = clock;
    this.lock = new Object();
    this.allFutures = new ArrayList<>();
  }

  /**
   * Asynchronously retrieves the {@link TrackGroupArray} for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the {@link TrackGroupArray}.
   */
  public ListenableFuture<TrackGroupArray> retrieveTrackGroups() {
    synchronized (lock) {
      if (released) {
        return immediateFailedFuture(new IllegalStateException("Retriever is released."));
      }
      startPreparation();
      SettableFuture<TrackGroupArray> externalFuture = SettableFuture.create();
      allFutures.add(externalFuture);
      Futures.addCallback(
          checkNotNull(preparationFuture),
          new FutureCallback<InternalResult>() {
            @Override
            public void onSuccess(InternalResult result) {
              externalFuture.set(result.trackGroups);
            }

            @Override
            public void onFailure(Throwable t) {
              externalFuture.setException(t);
            }
          },
          directExecutor());
      return externalFuture;
    }
  }

  /**
   * Asynchronously retrieves the {@link Timeline} for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the {@link Timeline}.
   */
  public ListenableFuture<Timeline> retrieveTimeline() {
    synchronized (lock) {
      if (released) {
        return immediateFailedFuture(new IllegalStateException("Retriever is released."));
      }
      startPreparation();
      SettableFuture<Timeline> externalFuture = SettableFuture.create();
      allFutures.add(externalFuture);
      Futures.addCallback(
          checkNotNull(preparationFuture),
          new FutureCallback<InternalResult>() {
            @Override
            public void onSuccess(InternalResult result) {
              externalFuture.set(result.timeline);
            }

            @Override
            public void onFailure(Throwable t) {
              externalFuture.setException(t);
            }
          },
          directExecutor());
      return externalFuture;
    }
  }

  /**
   * Asynchronously retrieves the duration for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the duration in microseconds, or
   *     {@link C#TIME_UNSET} if unknown.
   */
  public ListenableFuture<Long> retrieveDurationUs() {
    synchronized (lock) {
      if (released) {
        return immediateFailedFuture(new IllegalStateException("Retriever is released."));
      }
      ListenableFuture<Timeline> timelineFuture = retrieveTimeline();
      SettableFuture<Long> externalFuture = SettableFuture.create();
      allFutures.add(externalFuture);
      Futures.addCallback(
          timelineFuture,
          new FutureCallback<Timeline>() {
            @Override
            public void onSuccess(Timeline timeline) {
              if (timeline.isEmpty()) {
                externalFuture.set(C.TIME_UNSET);
              } else {
                externalFuture.set(timeline.getWindow(0, new Timeline.Window()).getDurationUs());
              }
            }

            @Override
            public void onFailure(Throwable t) {
              externalFuture.setException(t);
            }
          },
          directExecutor());
      return externalFuture;
    }
  }

  /** Starts the media preparation if it hasn't been started yet. */
  @GuardedBy("lock")
  private void startPreparation() {
    if (preparationFuture == null) {
      preparationFuture = SettableFuture.create();
      internalRetriever =
          new MetadataRetrieverInternal(
              mediaSourceFactory,
              mediaItem,
              clock,
              (trackGroups, timeline) -> { // onPrepared
                synchronized (lock) {
                  checkNotNull(preparationFuture).set(new InternalResult(trackGroups, timeline));
                }
              },
              (e) -> { // onFailure
                synchronized (lock) {
                  checkNotNull(preparationFuture).setException(e);
                }
              });
      internalRetriever.queueRetrieval();
    }
  }

  /**
   * @deprecated Use {@link Builder} to create an instance and call {@link #retrieveTrackGroups()}
   *     instead.
   */
  @Deprecated
  public static ListenableFuture<TrackGroupArray> retrieveMetadata(
      Context context, MediaItem mediaItem) {
    return retrieveMetadata(context, mediaItem, Clock.DEFAULT);
  }

  /**
   * @deprecated Use {@link Builder} to create an instance and call {@link #retrieveTrackGroups()}
   *     instead.
   */
  @Deprecated
  public static ListenableFuture<TrackGroupArray> retrieveMetadata(
      MediaSource.Factory mediaSourceFactory, MediaItem mediaItem) {
    return retrieveMetadata(mediaSourceFactory, mediaItem, Clock.DEFAULT);
  }

  @VisibleForTesting
  @Deprecated
  /* package */ static ListenableFuture<TrackGroupArray> retrieveMetadata(
      Context context, MediaItem mediaItem, Clock clock) {
    try (MetadataRetriever retriever = new Builder(context, mediaItem).setClock(clock).build()) {
      return retriever.retrieveTrackGroups();
    }
  }

  @Deprecated
  private static ListenableFuture<TrackGroupArray> retrieveMetadata(
      MediaSource.Factory mediaSourceFactory, MediaItem mediaItem, Clock clock) {
    try (MetadataRetriever retriever =
        new Builder(/* context= */ null, mediaItem)
            .setMediaSourceFactory(mediaSourceFactory)
            .setClock(clock)
            .build()) {
      return retriever.retrieveTrackGroups();
    }
  }

  /**
   * Sets the maximum number of metadata retrievals run in parallel.
   *
   * <p>The default is {@link #DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS}.
   *
   * @param maximumParallelRetrievals The maximum number of parallel retrievals.
   */
  public static void setMaximumParallelRetrievals(int maximumParallelRetrievals) {
    checkArgument(maximumParallelRetrievals >= 1);
    SharedWorkerThread.MAX_PARALLEL_RETRIEVALS.set(maximumParallelRetrievals);
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (released) {
        return;
      }
      released = true;
      // Defer the actual release until all returned futures are complete.
      ListenableFuture<?> unused =
          Futures.whenAllComplete(allFutures)
              .run(
                  () -> {
                    synchronized (lock) {
                      if (internalRetriever != null) {
                        internalRetriever.release();
                      }
                    }
                  },
                  directExecutor());
    }
  }

  private static final class MetadataRetrieverInternal {

    private static final int MESSAGE_PREPARE_SOURCE = 1;
    private static final int MESSAGE_CHECK_FOR_FAILURE = 2;
    private static final int MESSAGE_CONTINUE_LOADING = 3;
    private static final int MESSAGE_RELEASE = 4;

    private static final SharedWorkerThread SHARED_WORKER_THREAD = new SharedWorkerThread();

    private final MediaSource.Factory mediaSourceFactory;
    private final MediaItem mediaItem;
    private final HandlerWrapper mediaSourceHandler;
    private final OnPreparedListener onPreparedListener;
    private final OnFailureListener onFailureListener;

    public interface OnPreparedListener {
      void onPrepared(TrackGroupArray trackGroups, Timeline timeline);
    }

    public interface OnFailureListener {
      void onFailure(Exception e);
    }

    public MetadataRetrieverInternal(
        MediaSource.Factory mediaSourceFactory,
        MediaItem mediaItem,
        Clock clock,
        OnPreparedListener onPreparedListener,
        OnFailureListener onFailureListener) {
      this.mediaSourceFactory = mediaSourceFactory;
      this.mediaItem = mediaItem;
      this.onPreparedListener = onPreparedListener;
      this.onFailureListener = onFailureListener;
      Looper workerThreadLooper = SHARED_WORKER_THREAD.addWorker();
      mediaSourceHandler =
          clock.createHandler(workerThreadLooper, new MediaSourceHandlerCallback());
    }

    public void queueRetrieval() {
      SHARED_WORKER_THREAD.startRetrieval(this);
    }

    public void start() {
      mediaSourceHandler.obtainMessage(MESSAGE_PREPARE_SOURCE, mediaItem).sendToTarget();
    }

    public void release() {
      mediaSourceHandler.obtainMessage(MESSAGE_RELEASE).sendToTarget();
    }

    private final class MediaSourceHandlerCallback implements Handler.Callback {

      private static final int ERROR_POLL_INTERVAL_MS = 100;

      private final MediaSourceCaller mediaSourceCaller;
      private @MonotonicNonNull MediaSource mediaSource;
      private @MonotonicNonNull MediaPeriod mediaPeriod;
      private @MonotonicNonNull Timeline timeline;

      public MediaSourceHandlerCallback() {
        mediaSourceCaller = new MediaSourceCaller();
      }

      @Override
      public boolean handleMessage(Message msg) {
        switch (msg.what) {
          case MESSAGE_PREPARE_SOURCE:
            MediaItem mediaItem = (MediaItem) msg.obj;
            mediaSource = mediaSourceFactory.createMediaSource(mediaItem);
            mediaSource.prepareSource(
                mediaSourceCaller, /* mediaTransferListener= */ null, PlayerId.UNSET);
            mediaSourceHandler.sendEmptyMessage(MESSAGE_CHECK_FOR_FAILURE);
            return true;
          case MESSAGE_CHECK_FOR_FAILURE:
            try {
              if (mediaPeriod == null) {
                checkNotNull(mediaSource).maybeThrowSourceInfoRefreshError();
              } else {
                mediaPeriod.maybeThrowPrepareError();
              }
              mediaSourceHandler.sendEmptyMessageDelayed(
                  MESSAGE_CHECK_FOR_FAILURE, /* delayMs= */ ERROR_POLL_INTERVAL_MS);
            } catch (IOException e) {
              onFailureListener.onFailure(e);
              mediaSourceHandler.obtainMessage(MESSAGE_RELEASE).sendToTarget();
            }
            return true;
          case MESSAGE_CONTINUE_LOADING:
            checkNotNull(mediaPeriod)
                .continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
            return true;
          case MESSAGE_RELEASE:
            if (mediaPeriod != null) {
              checkNotNull(mediaSource).releasePeriod(mediaPeriod);
            }
            if (mediaSource != null) {
              mediaSource.releaseSource(mediaSourceCaller);
            }
            mediaSourceHandler.removeCallbacksAndMessages(/* token= */ null);
            SHARED_WORKER_THREAD.removeWorker();
            return true;
          default:
            return false;
        }
      }

      private final class MediaSourceCaller implements MediaSource.MediaSourceCaller {

        private final MediaPeriodCallback mediaPeriodCallback;
        private final Allocator allocator;
        private boolean mediaPeriodCreated;

        public MediaSourceCaller() {
          mediaPeriodCallback = new MediaPeriodCallback();
          allocator =
              new DefaultAllocator(
                  /* trimOnReset= */ true,
                  /* individualAllocationSize= */ C.DEFAULT_BUFFER_SEGMENT_SIZE);
        }

        @Override
        public void onSourceInfoRefreshed(MediaSource source, Timeline timeline) {
          MediaSourceHandlerCallback.this.timeline = timeline;

          if (mediaPeriodCreated) {
            // Ignore dynamic updates.
            return;
          }
          mediaPeriodCreated = true;
          mediaPeriod =
              source.createPeriod(
                  new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(/* periodIndex= */ 0)),
                  allocator,
                  /* startPositionUs= */ 0);
          mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
        }

        private final class MediaPeriodCallback implements MediaPeriod.Callback {
          @Override
          public void onPrepared(MediaPeriod mediaPeriod) {
            onPreparedListener.onPrepared(mediaPeriod.getTrackGroups(), checkNotNull(timeline));
            mediaSourceHandler.obtainMessage(MESSAGE_RELEASE).sendToTarget();
          }

          @Override
          public void onContinueLoadingRequested(MediaPeriod mediaPeriod) {
            mediaSourceHandler.obtainMessage(MESSAGE_CONTINUE_LOADING).sendToTarget();
          }
        }
      }
    }
  }

  private static final class SharedWorkerThread {

    public static final AtomicInteger MAX_PARALLEL_RETRIEVALS =
        new AtomicInteger(DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS);

    private final Deque<MetadataRetrieverInternal> pendingRetrievals;
    @Nullable private HandlerThread mediaSourceThread;
    private int referenceCount;

    public SharedWorkerThread() {
      pendingRetrievals = new ArrayDeque<>();
    }

    public synchronized Looper addWorker() {
      if (mediaSourceThread == null) {
        checkState(referenceCount == 0);
        mediaSourceThread = new HandlerThread("ExoPlayer:MetadataRetriever");
        mediaSourceThread.start();
      }
      referenceCount++;
      return checkNotNull(mediaSourceThread).getLooper();
    }

    public synchronized void startRetrieval(MetadataRetrieverInternal retrieval) {
      pendingRetrievals.addLast(retrieval);
      maybeStartNewRetrieval();
    }

    public synchronized void removeWorker() {
      if (--referenceCount == 0) {
        checkNotNull(mediaSourceThread).quit();
        mediaSourceThread = null;
        pendingRetrievals.clear();
      } else {
        maybeStartNewRetrieval();
      }
    }

    @GuardedBy("this")
    private void maybeStartNewRetrieval() {
      if (pendingRetrievals.isEmpty()) {
        return;
      }
      int activeRetrievals = referenceCount - pendingRetrievals.size();
      if (activeRetrievals < MAX_PARALLEL_RETRIEVALS.get()) {
        MetadataRetrieverInternal retrieval = pendingRetrievals.removeFirst();
        retrieval.start();
      }
    }
  }
}
