/*
 * Copyright 2025 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Manages the internal logic for retrieving metadata from a {@link MediaItem}.
 *
 * <p>This class contains the core implementation for metadata retrieval, including the management
 * of a shared worker thread and the futures that provide the results. It prepares a {@link
 * MediaSource} just enough to extract information like track groups and timeline without performing
 * a full playback.
 */
// TODO(b/442827020): Move this class to the androidx.media3.inspector package and make it
// package-private.
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class MetadataRetrieverInternal implements AutoCloseable {

  private final MediaItem mediaItem;
  private final MediaSource.Factory mediaSourceFactory;
  private final Clock clock;
  private final Object lock;

  @GuardedBy("lock")
  private final List<ListenableFuture<?>> allFutures;

  @GuardedBy("lock")
  private @MonotonicNonNull SettableFuture<InternalResult> preparationFuture;

  @GuardedBy("lock")
  private @MonotonicNonNull RetrievalTask retrievalTask;

  @GuardedBy("lock")
  private boolean released;

  /**
   * Constructs an instance.
   *
   * @param mediaItem The {@link MediaItem} to retrieve metadata from.
   * @param mediaSourceFactory The {@link MediaSource.Factory} for creating {@link MediaSource}s.
   * @param clock The {@link Clock} to use.
   */
  public MetadataRetrieverInternal(
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

  @Override
  public void close() {
    synchronized (lock) {
      if (released) {
        return;
      }
      released = true;
      ListenableFuture<?> unused =
          Futures.whenAllComplete(allFutures)
              .run(
                  () -> {
                    synchronized (lock) {
                      if (retrievalTask != null) {
                        retrievalTask.release();
                      }
                    }
                  },
                  directExecutor());
    }
  }

  @GuardedBy("lock")
  private void startPreparation() {
    if (preparationFuture == null) {
      preparationFuture = SettableFuture.create();
      retrievalTask =
          new RetrievalTask(
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
      retrievalTask.queueRetrieval();
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

  /** A task that retrieves metadata from a {@link MediaItem}. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public static final class RetrievalTask {

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

    /** A listener for successfully prepared media. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public interface OnPreparedListener {
      /** Called when the media is prepared. */
      void onPrepared(TrackGroupArray trackGroups, Timeline timeline);
    }

    /** A listener for failures. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public interface OnFailureListener {
      /** Called when an error occurs. */
      void onFailure(Exception e);
    }

    /**
     * Constructs a retrieval task.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory} for creating {@link MediaSource}s.
     * @param mediaItem The {@link MediaItem} to retrieve metadata from.
     * @param clock The {@link Clock} to use.
     * @param onPreparedListener A listener to be notified when the media is prepared.
     * @param onFailureListener A listener to be notified of failures.
     */
    public RetrievalTask(
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

    /** Queues the retrieval to be started when permitted. */
    public void queueRetrieval() {
      SHARED_WORKER_THREAD.startRetrieval(this);
    }

    /** Starts the retrieval. */
    public void start() {
      mediaSourceHandler.obtainMessage(MESSAGE_PREPARE_SOURCE, mediaItem).sendToTarget();
    }

    /** Releases the resources used by this task. */
    public void release() {
      mediaSourceHandler.obtainMessage(MESSAGE_RELEASE).sendToTarget();
    }

    private final class MediaSourceHandlerCallback implements Handler.Callback {

      private static final int ERROR_POLL_INTERVAL_MS = 100;

      private final MediaSourceCaller mediaSourceCaller;
      private @MonotonicNonNull MediaSource mediaSource;
      private @MonotonicNonNull MediaPeriod mediaPeriod;
      private @MonotonicNonNull Timeline timeline;
      private boolean released;

      public MediaSourceHandlerCallback() {
        mediaSourceCaller = new MediaSourceCaller();
      }

      @Override
      public boolean handleMessage(Message msg) {
        if (released) {
          return true;
        }
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
            released = true;
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

  /** Manages a shared worker thread for metadata retrieval. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public static final class SharedWorkerThread {

    /** The maximum number of parallel metadata retrieval operations. */
    // TODO(b/442827020): When MetadataRetrieverInternal is moved to the inspector module, update
    // this to use a DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS constant defined within
    // androidx.media3.inspector.MetadataRetriever.
    public static final AtomicInteger MAX_PARALLEL_RETRIEVALS =
        new AtomicInteger(MetadataRetriever.DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS);

    private final Deque<RetrievalTask> pendingRetrievals;
    @Nullable private HandlerThread mediaSourceThread;
    private int referenceCount;

    private SharedWorkerThread() {
      pendingRetrievals = new ArrayDeque<>();
    }

    /**
     * Adds a worker to the shared thread, creating and starting the thread if needed.
     *
     * @return The {@link Looper} of the shared thread.
     */
    public synchronized Looper addWorker() {
      if (mediaSourceThread == null) {
        checkState(referenceCount == 0);
        mediaSourceThread = new HandlerThread("ExoPlayer:MetadataRetriever");
        mediaSourceThread.start();
      }
      referenceCount++;
      return checkNotNull(mediaSourceThread).getLooper();
    }

    /**
     * Schedules a retrieval task to run when resources are available.
     *
     * @param retrieval The retrieval task to run.
     */
    public synchronized void startRetrieval(RetrievalTask retrieval) {
      pendingRetrievals.addLast(retrieval);
      maybeStartNewRetrieval();
    }

    /** Removes a worker from the shared thread, quitting the thread if it's the last worker. */
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
        RetrievalTask retrieval = pendingRetrievals.removeFirst();
        retrieval.start();
      }
    }
  }
}
