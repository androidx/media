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
package androidx.media3.test.utils.robolectric;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.createRobolectricConditionVariable;
import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.StreamKey;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.Downloader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake {@link Downloader}. */
@UnstableApi
public class FakeDownloader implements Downloader {

  /** Timeout to use when blocking on conditions that we expect to become unblocked. */
  private static final int TIMEOUT_MS = 10_000;

  private final DownloadRequest request;
  private final ConditionVariable downloadStarted;
  private final ConditionVariable removeStarted;
  private final ConditionVariable cancelStarted;
  private final ConditionVariable finished;
  private final ConditionVariable blocker;
  private final AtomicInteger downloadStartCount;
  private final AtomicInteger removeStartCount;
  private final AtomicInteger bytesDownloaded;

  private volatile boolean canceled;
  private volatile boolean enableDownloadIoException;

  /**
   * Creates a {@link FakeDownloader}.
   *
   * @param request The {@linkplain DownloadRequest download request}.
   */
  public FakeDownloader(DownloadRequest request) {
    this.request = request;
    downloadStarted = createRobolectricConditionVariable();
    removeStarted = createRobolectricConditionVariable();
    cancelStarted = createRobolectricConditionVariable();
    finished = createRobolectricConditionVariable();
    blocker = createRobolectricConditionVariable();
    downloadStartCount = new AtomicInteger();
    removeStartCount = new AtomicInteger();
    bytesDownloaded = new AtomicInteger();
  }

  @Override
  public void cancel() {
    cancelStarted.open();
    canceled = true;
    blocker.open();
  }

  @Override
  public void download(@Nullable ProgressListener progressListener) throws IOException {
    downloadStartCount.incrementAndGet();
    downloadStarted.open();
    try {
      block();
      if (canceled) {
        return;
      }
      int bytesDownloaded = this.bytesDownloaded.get();
      if (progressListener != null && bytesDownloaded > 0) {
        progressListener.onProgress(C.LENGTH_UNSET, bytesDownloaded, C.PERCENTAGE_UNSET);
      }
      if (enableDownloadIoException) {
        enableDownloadIoException = false;
        throw new IOException();
      }
    } finally {
      finished.open();
    }
  }

  @Override
  public void remove() {
    removeStartCount.incrementAndGet();
    removeStarted.open();
    try {
      block();
    } finally {
      finished.open();
    }
  }

  /** Finishes the {@link #download} or {@link #remove} without an error. */
  public void finish() throws InterruptedException {
    blocker.open();
    blockUntilFinished();
  }

  /** Fails {@link #download} or {@link #remove} with an error. */
  public void fail() throws InterruptedException {
    enableDownloadIoException = true;
    blocker.open();
    blockUntilFinished();
  }

  /** Increments the number of bytes that the fake downloader has downloaded. */
  public void incrementBytesDownloaded() {
    bytesDownloaded.incrementAndGet();
  }

  /** Asserts that the {@link FakeDownloader} instance was created with the given {@code id}. */
  public void assertId(String id) {
    assertThat(request.id).isEqualTo(id);
  }

  /**
   * Asserts that the {@link FakeDownloader} instance was created with the given {@code streamKeys}.
   */
  public void assertStreamKeys(StreamKey... streamKeys) {
    assertThat(request.streamKeys).containsExactlyElementsIn(streamKeys);
  }

  /** Asserts that {@link #download} has started. */
  public void assertDownloadStarted() throws InterruptedException {
    assertThat(downloadStarted.block(TIMEOUT_MS)).isTrue();
    downloadStarted.close();
  }

  /** Asserts that {@link #remove} has started or not started. */
  public void assertRemoveStarted(boolean started) throws InterruptedException {
    if (started) {
      assertThat(removeStarted.block(TIMEOUT_MS)).isTrue();
      removeStarted.close();
    } else {
      assertThat(removeStarted.block(TIMEOUT_MS)).isFalse();
    }
  }

  /** Asserts that {@link #download} or {@link #remove} has been canceled or not canceled. */
  public void assertCanceled(boolean canceled) throws InterruptedException {
    if (canceled) {
      assertThat(cancelStarted.block(TIMEOUT_MS)).isTrue();
      blockUntilFinished();
      cancelStarted.close();
    } else {
      assertThat(cancelStarted.block(TIMEOUT_MS)).isFalse();
    }
  }

  // Internal methods.

  private void block() {
    try {
      blocker.block();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e); // Never happens.
    } finally {
      blocker.close();
    }
  }

  private void blockUntilFinished() throws InterruptedException {
    assertThat(finished.block(TIMEOUT_MS)).isTrue();
    finished.close();
  }
}
