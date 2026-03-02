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
package androidx.media3.effect;

import static android.system.OsConstants.POLLIN;
import static com.google.common.base.Preconditions.checkState;

import android.hardware.SyncFence;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class that allows duplicating a {@link SyncFence}, and wrapping a fence file descriptor
 * on earlier API levels.
 */
@ExperimentalApi // TODO: b/479415385 - remove when packet consumer is production-ready.
public class SyncFenceCompat implements AutoCloseable {
  private final ImmutableList<ParcelFileDescriptor> parcelFileDescriptors;

  /**
   * Creates an instance that duplicates a {@link SyncFence}. Both fences must be {@linkplain
   * #close() closed} independently.
   */
  @RequiresApi(33)
  public static SyncFenceCompat duplicate(SyncFence syncFence) {
    return new SyncFenceCompat(ImmutableList.of(readFileDescriptor(syncFence)));
  }

  /**
   * Combines a list of {@link SyncFenceCompat} instances into a single instance.
   *
   * <p>Waiting on the returned instance is equivalent to waiting on all the individual fences.
   *
   * @param fences The list of fences to combine. Must not be empty.
   * @return A new combined {@link SyncFenceCompat}.
   */
  public static SyncFenceCompat combine(List<SyncFenceCompat> fences) {
    checkState(!fences.isEmpty());
    ImmutableList.Builder<ParcelFileDescriptor> pfdsBuilder = ImmutableList.builder();
    for (SyncFenceCompat fence : fences) {
      pfdsBuilder.addAll(fence.parcelFileDescriptors);
    }
    return new SyncFenceCompat(pfdsBuilder.build());
  }

  /**
   * Take ownership of a raw native fence file descriptor into a new SyncFenceCompat.
   *
   * <p>The returned instance now owns the given file descriptor, and will be responsible for
   * closing it.
   *
   * <p>Graphics APIs such as <a
   * href="https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_external_fence_fd.html">Vulkan</a>
   * are a common source of a native fence file descriptors.
   *
   * @param fileDescriptor The raw native fence file descriptor.
   */
  public static SyncFenceCompat adoptFenceFileDescriptor(int fileDescriptor) {
    return new SyncFenceCompat(ImmutableList.of(ParcelFileDescriptor.adoptFd(fileDescriptor)));
  }

  /**
   * Creates an instance that wraps an existing {@link ParcelFileDescriptor}.
   *
   * <p>The provided descriptor is owned by the new instance and will be closed when {@link
   * #close()} is called.
   */
  /* package */ static SyncFenceCompat adoptParcelFileDescriptor(ParcelFileDescriptor pfd) {
    return new SyncFenceCompat(ImmutableList.of(pfd));
  }

  private SyncFenceCompat(List<ParcelFileDescriptor> parcelFileDescriptors) {
    this.parcelFileDescriptors = ImmutableList.copyOf(parcelFileDescriptors);
  }

  /**
   * Waits for the fence to signal.
   *
   * @param timeoutMs The timeout in milliseconds. A negative value means infinite timeout.
   * @return Whether the fence signaled within the timeout.
   * @throws ErrnoException If an error occurs while polling the fence. See the Linux manual pages
   *     for the poll system call.
   */
  public boolean await(int timeoutMs) throws ErrnoException {
    if (parcelFileDescriptors.isEmpty()) {
      return true;
    }
    long startTimeMs = SystemClock.elapsedRealtime();
    List<ParcelFileDescriptor> remaining = new ArrayList<>(parcelFileDescriptors);
    // Os.poll() returns as soon as any of the provided file descriptors signal. We loop and retry
    // with the remaining unsignaled fences until all have signaled or the timeout is reached.
    while (!remaining.isEmpty()) {
      long elapsedMs = SystemClock.elapsedRealtime() - startTimeMs;
      int currentTimeout = timeoutMs < 0 ? -1 : (int) Math.max(0, timeoutMs - elapsedMs);
      if (timeoutMs > 0 && currentTimeout == 0) {
        return false;
      }
      StructPollfd[] structPollfds = new StructPollfd[remaining.size()];
      for (int i = 0; i < remaining.size(); i++) {
        structPollfds[i] = new StructPollfd();
        structPollfds[i].fd = remaining.get(i).getFileDescriptor();
        structPollfds[i].events = (short) POLLIN;
      }

      if (Os.poll(structPollfds, currentTimeout) == 0) {
        return false;
      }

      for (int i = remaining.size() - 1; i >= 0; i--) {
        if ((structPollfds[i].revents & POLLIN) != 0) {
          remaining.remove(i);
        }
      }
    }
    return true;
  }

  /**
   * Closes all underlying file descriptors.
   *
   * <p>This method attempts to close every file descriptor, even if one throws an {@link
   * IOException}. Any exceptions encountered are collected and thrown at the end.
   */
  @Override
  public void close() throws IOException {
    IOException exception = null;
    for (ParcelFileDescriptor pfd : parcelFileDescriptors) {
      exception = closeAndAccumulateException(pfd, exception);
    }
    if (exception != null) {
      throw exception;
    }
  }

  @Nullable
  private static IOException closeAndAccumulateException(
      ParcelFileDescriptor pfd, @Nullable IOException currentException) {
    try {
      pfd.close();
    } catch (IOException e) {
      if (currentException == null) {
        return e;
      }
      currentException.addSuppressed(e);
    }
    return currentException;
  }

  @RequiresApi(33)
  private static ParcelFileDescriptor readFileDescriptor(SyncFence syncFence) {
    Parcel parcel = Parcel.obtain();

    syncFence.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);
    checkState(parcel.readBoolean());

    return parcel.readFileDescriptor();
  }
}
