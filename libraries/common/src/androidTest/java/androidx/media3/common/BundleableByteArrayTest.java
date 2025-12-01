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

import static android.os.IBinder.FIRST_CALL_TRANSACTION;
import static androidx.media3.common.util.Util.EMPTY_BYTE_ARRAY;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.SettableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link BundleableByteArray}. */
@RunWith(AndroidJUnit4.class)
public final class BundleableByteArrayTest {

  @Test
  public void writeToBundleReadFromBundle_remoteProcessEmptyArray_returnsEmptyArray()
      throws Exception {
    IBinder remoteService = ByteArrayTransferTestService.connectToRemoteService();
    BundleableByteArray byteArray = new BundleableByteArray(EMPTY_BYTE_ARRAY);

    byte[] returnedBytes = ByteArrayTransferTestService.transferBytes(remoteService, byteArray);

    assertThat(returnedBytes).isEqualTo(EMPTY_BYTE_ARRAY);
  }

  @Test
  public void writeToBundleReadFromBundle_remoteProcessSmallArray_returnsSameByteArray()
      throws Exception {
    IBinder remoteService = ByteArrayTransferTestService.connectToRemoteService();
    byte[] bytes = generateByteArray(/* size= */ 100);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);

    byte[] returnedBytes = ByteArrayTransferTestService.transferBytes(remoteService, byteArray);

    assertThat(returnedBytes).isEqualTo(bytes);
  }

  @Test
  public void writeToBundleReadFromBundle_remoteProcessLargeArray_returnsSameByteArray()
      throws Exception {
    IBinder remoteService = ByteArrayTransferTestService.connectToRemoteService();
    byte[] bytes = generateByteArray(/* size= */ 20_000_000);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);

    byte[] returnedBytes = ByteArrayTransferTestService.transferBytes(remoteService, byteArray);

    assertThat(returnedBytes).isEqualTo(bytes);
  }

  @Test
  public void writeToBundleReadFromBundle_remoteProcessReusingInstance_returnsSameByteArray()
      throws Exception {
    IBinder remoteService = ByteArrayTransferTestService.connectToRemoteService();
    byte[] bytes = generateByteArray(/* size= */ 2_000_000);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);

    byte[] returnedBytes1 = ByteArrayTransferTestService.transferBytes(remoteService, byteArray);
    byte[] returnedBytes2 = ByteArrayTransferTestService.transferBytes(remoteService, byteArray);

    assertThat(returnedBytes1).isEqualTo(bytes);
    assertThat(returnedBytes2).isEqualTo(bytes);
  }

  @Test
  public void writeToBundleReadFromBundle_inProcessEmptyArray_returnsEmptyArray() throws Exception {
    BundleableByteArray byteArray = new BundleableByteArray(EMPTY_BYTE_ARRAY);
    Bundle bundle = new Bundle();

    byteArray.writeToBundle(bundle, "key");
    byte[] returnedBytes = BundleableByteArray.readFromBundle(bundle, "key");

    assertThat(returnedBytes).isEqualTo(EMPTY_BYTE_ARRAY);
  }

  @Test
  public void writeToBundleReadFromBundle_inProcessSmallArray_returnsSameByteArray() {
    byte[] bytes = generateByteArray(/* size= */ 100);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);
    Bundle bundle = new Bundle();

    byteArray.writeToBundle(bundle, "key");
    byte[] returnedBytes = BundleableByteArray.readFromBundle(bundle, "key");

    assertThat(returnedBytes).isEqualTo(bytes);
  }

  @Test
  public void writeToBundleReadFromBundle_inProcessLargeArray_returnsSameByteArray() {
    byte[] bytes = generateByteArray(/* size= */ 20_000_000);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);
    Bundle bundle = new Bundle();

    byteArray.writeToBundle(bundle, "key");
    byte[] returnedBytes = BundleableByteArray.readFromBundle(bundle, "key");

    assertThat(returnedBytes).isEqualTo(bytes);
  }

  @Test
  public void writeToBundleReadFromBundle_inProcessReusingInstance_returnsSameByteArray() {
    byte[] bytes = generateByteArray(/* size= */ 2_000_000);
    BundleableByteArray byteArray = new BundleableByteArray(bytes);
    Bundle bundle1 = new Bundle();
    Bundle bundle2 = new Bundle();

    byteArray.writeToBundle(bundle1, "key");
    byte[] returnedBytes1 = BundleableByteArray.readFromBundle(bundle1, "key");
    byteArray.writeToBundle(bundle2, "key");
    byte[] returnedBytes2 = BundleableByteArray.readFromBundle(bundle2, "key");

    assertThat(returnedBytes1).isEqualTo(bytes);
    assertThat(returnedBytes2).isEqualTo(bytes);
  }

  private static byte[] generateByteArray(int size) {
    byte[] byteArray = new byte[size];
    for (int i = 0; i < size; i++) {
      byteArray[i] = (byte) i;
    }
    return byteArray;
  }

  /** Remote test service that transfers byte array back and forth to test bundling. */
  public static final class ByteArrayTransferTestService extends Service {

    private static final ComponentName COMPONENT_NAME =
        new ComponentName(
            "androidx.media3.common.test",
            "androidx.media3.common.BundleableByteArrayTest$ByteArrayTransferTestService");
    private static final String INTENT_ACTION =
        "androidx.media3.common.test.START_BYTE_ARRAY_TRANSFER_TEST_SERVICE";
    private static final String BUNDLE_KEY = "byte_array";

    private static IBinder connectToRemoteService() throws Exception {
      Intent intent = new Intent(INTENT_ACTION);
      intent.setComponent(COMPONENT_NAME);
      SettableFuture<IBinder> binderFuture = SettableFuture.create();
      ServiceConnection serviceConnection =
          new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
              binderFuture.set(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
          };
      Context context = ApplicationProvider.getApplicationContext();
      boolean bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
      checkState(bound);
      return binderFuture.get();
    }

    private static byte[] transferBytes(IBinder binder, BundleableByteArray byteArray)
        throws RemoteException {
      Bundle byteArrayBundle = new Bundle();
      Bundle replyBundle;
      byteArray.writeToBundle(byteArrayBundle, BUNDLE_KEY);
      Parcel data = Parcel.obtain();
      Parcel reply = Parcel.obtain();
      try {
        data.writeBundle(byteArrayBundle);
        binder.transact(FIRST_CALL_TRANSACTION, data, reply, /* flags= */ 0);
        replyBundle = checkNotNull(reply.readBundle());
      } finally {
        data.recycle();
        reply.recycle();
      }
      return BundleableByteArray.readFromBundle(replyBundle, BUNDLE_KEY);
    }

    @Override
    public IBinder onBind(Intent intent) {
      return new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, @Nullable Parcel reply, int flags) {
          Bundle dataBundle = checkNotNull(data.readBundle());
          @Nullable
          byte[] byteArray =
              checkNotNull(BundleableByteArray.readFromBundle(dataBundle, BUNDLE_KEY));
          Bundle replyBundle = new Bundle();
          new BundleableByteArray(byteArray).writeToBundle(replyBundle, BUNDLE_KEY);
          checkNotNull(reply).writeBundle(replyBundle);
          return true;
        }
      };
    }
  }
}
