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
package androidx.media3.common;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.EMPTY_BYTE_ARRAY;
import static com.google.common.collect.Iterables.getLast;
import static java.lang.Math.min;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SharedMemory;
import android.system.OsConstants;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A wrapper for byte arrays that allows them to be transferred over {@link Bundle} instances using
 * shared memory.
 *
 * <p>This class should be used for transferring byte arrays whose size is not trivially small, e.g.
 * below 5000 bytes, to ensure it stays well below the binder transaction limits.
 */
@UnstableApi
public final class BundleableByteArray {

  private static final String TAG = "BundleableByteArray";
  private static final String IN_PROCESS_BINDER_SUFFIX = "_IN_PROCESS";

  private final byte[] byteArray;
  private final InProcessBinder inProcessBinder;

  @Nullable SharedMemoryApi27 sharedMemoryApi27;
  @Nullable SplitArrayRetriever splitArrayRetriever;

  /**
   * Creates the bundleable byte array.
   *
   * @param byteArray The byte array to wrap. This class assumes this array will not be modified
   *     after being passed to this constructor.
   */
  public BundleableByteArray(byte[] byteArray) {
    this.byteArray = byteArray;
    this.inProcessBinder = new InProcessBinder();
  }

  /**
   * Writes a reference to the byte array to a {@link Bundle}.
   *
   * @param bundle The {@link Bundle} to write to.
   * @param key The bundle key for the byte array reference.
   */
  public void writeToBundle(Bundle bundle, String key) {
    bundle.putBinder(key + IN_PROCESS_BINDER_SUFFIX, inProcessBinder);
    if (SDK_INT >= 27 && byteArray.length > 0) {
      if (sharedMemoryApi27 == null) {
        sharedMemoryApi27 = SharedMemoryApi27.create(byteArray);
      }
      if (sharedMemoryApi27 != null) {
        sharedMemoryApi27.writeToBundle(bundle, key);
        return;
      }
    }
    if (splitArrayRetriever == null) {
      splitArrayRetriever = new SplitArrayRetriever(byteArray);
    }
    splitArrayRetriever.writeToBundle(bundle, key);
  }

  /**
   * Reads a bundleable byte array from a {@link Bundle}.
   *
   * @param bundle The {@link Bundle} to read from.
   * @param key The bundle key for the byte array reference.
   * @return The bundleable byte array, or null if the key is not present or malformed.
   */
  @Nullable
  public static byte[] readFromBundle(Bundle bundle, String key) {
    @Nullable IBinder inProcessBinder = bundle.getBinder(key + IN_PROCESS_BINDER_SUFFIX);
    if (inProcessBinder == null) {
      return null;
    }
    boolean isLocal = inProcessBinder instanceof InProcessBinder;
    if (isLocal) {
      return ((InProcessBinder) inProcessBinder).getByteArray();
    }
    if (SDK_INT >= 27) {
      byte[] byteArray = SharedMemoryApi27.readFromBundle(bundle, key);
      if (byteArray != null) {
        return byteArray;
      }
    }
    return SplitArrayRetriever.readFromBundle(bundle, key);
  }

  @RequiresApi(27)
  private static final class SharedMemoryApi27 {

    @Nullable
    private static SharedMemoryApi27 create(byte[] byteArray) {
      SharedMemory sharedMemory = null;
      try {
        sharedMemory = SharedMemory.create(TAG, byteArray.length);
        ByteBuffer byteBuffer = sharedMemory.mapReadWrite();
        byteBuffer.put(byteArray);
        SharedMemory.unmap(byteBuffer);
        sharedMemory.setProtect(OsConstants.PROT_READ);
        return new SharedMemoryApi27(sharedMemory);
      } catch (Exception e) {
        Log.w(TAG, "Failed to allocate shared memory for byte array, size=" + byteArray.length, e);
        if (sharedMemory != null) {
          sharedMemory.close();
        }
        return null;
      }
    }

    private final SharedMemory sharedMemory;

    private SharedMemoryApi27(SharedMemory sharedMemory) {
      this.sharedMemory = sharedMemory;
    }

    private void writeToBundle(Bundle bundle, String key) {
      bundle.putParcelable(key, sharedMemory);
    }

    @Nullable
    private static byte[] readFromBundle(Bundle bundle, String key) {
      @Nullable Parcelable parcelable = bundle.getParcelable(key);
      if (!(parcelable instanceof SharedMemory)) {
        return null;
      }
      SharedMemory sharedMemory = (SharedMemory) parcelable;
      ByteBuffer byteBuffer = null;
      try {
        byteBuffer = sharedMemory.mapReadOnly();
        byte[] byteArray = new byte[sharedMemory.getSize()];
        byteBuffer.get(byteArray);
        return byteArray;
      } catch (Exception e) {
        Log.w(TAG, "Failed to read byte array from shared memory", e);
        return null;
      } finally {
        if (byteBuffer != null) {
          SharedMemory.unmap(byteBuffer);
        }
        sharedMemory.close();
      }
    }
  }

  private static final class SplitArrayRetriever {

    private static final int CHUNK_SIZE = C.SUGGESTED_MAX_IPC_SIZE;
    private static final String BUNDLE_KEY = "bytes";

    private final BundleListRetriever bundleListRetriever;

    private SplitArrayRetriever(byte[] byteArray) {
      ImmutableList.Builder<Bundle> splitListBuilder = ImmutableList.builder();
      int chunkCount = Util.ceilDivide(byteArray.length, CHUNK_SIZE);
      for (int i = 0; i < chunkCount; i++) {
        Bundle bundle = new Bundle();
        int from = i * CHUNK_SIZE;
        int to = min(from + CHUNK_SIZE, byteArray.length);
        bundle.putByteArray(BUNDLE_KEY, Arrays.copyOfRange(byteArray, from, to));
        splitListBuilder.add(bundle);
      }
      bundleListRetriever = new BundleListRetriever(splitListBuilder.build());
    }

    private void writeToBundle(Bundle bundle, String key) {
      bundle.putBinder(key, bundleListRetriever);
    }

    @Nullable
    private static byte[] readFromBundle(Bundle bundle, String key) {
      @Nullable IBinder binder = bundle.getBinder(key);
      if (binder == null) {
        return null;
      }
      ImmutableList<Bundle> list;
      try {
        list = BundleListRetriever.getList(binder);
      } catch (RuntimeException e) {
        Log.w(TAG, "Failed to read byte array from bundle list retriever", e);
        return null;
      }
      if (list.isEmpty()) {
        return EMPTY_BYTE_ARRAY;
      }
      byte[] lastByteArray = getLast(list).getByteArray(BUNDLE_KEY);
      if (lastByteArray == null) {
        return null;
      }
      int fullChunkCount = list.size() - 1;
      byte[] outArray = new byte[fullChunkCount * CHUNK_SIZE + lastByteArray.length];
      System.arraycopy(
          lastByteArray, 0, outArray, fullChunkCount * CHUNK_SIZE, lastByteArray.length);
      for (int i = 0; i < fullChunkCount; i++) {
        byte[] chunk = list.get(i).getByteArray(BUNDLE_KEY);
        if (chunk == null || chunk.length != CHUNK_SIZE) {
          return null;
        }
        System.arraycopy(chunk, 0, outArray, i * CHUNK_SIZE, CHUNK_SIZE);
      }
      return outArray;
    }
  }

  private final class InProcessBinder extends Binder {
    private byte[] getByteArray() {
      return BundleableByteArray.this.byteArray;
    }
  }
}
