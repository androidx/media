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
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import java.io.IOException;

/**
 * A helper class that allows duplicating a {@link SyncFence}, and wrapping a fence file descriptor
 * on earlier API levels.
 */
@ExperimentalApi // TODO: b/479415385 - remove when packet consumer is production-ready.
public class SyncFenceCompat implements AutoCloseable {
  private final ParcelFileDescriptor parcelFileDescriptor;

  /**
   * Creates an instance that duplicates a {@link SyncFence}. Both fences must be {@linkplain
   * #close() closed} independently.
   */
  @RequiresApi(33)
  public static SyncFenceCompat duplicate(SyncFence syncFence) {
    return new SyncFenceCompat(readFileDescriptor(syncFence));
  }

  @RequiresApi(33)
  private SyncFenceCompat(ParcelFileDescriptor parcelFileDescriptor) {
    this.parcelFileDescriptor = parcelFileDescriptor;
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
    StructPollfd[] structPollfds = new StructPollfd[] {new StructPollfd()};
    structPollfds[0].fd = parcelFileDescriptor.getFileDescriptor();
    structPollfds[0].events = (short) POLLIN;
    structPollfds[0].revents = 0;
    return Os.poll(structPollfds, timeoutMs) == 1;
  }

  @Override
  public void close() throws IOException {
    parcelFileDescriptor.close();
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
