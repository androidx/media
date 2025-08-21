/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.session.legacy;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

/**
 * Callback interface for a MediaSessionCompat to send updates to a MediaControllerCompat. This is
 * only used on pre-Lollipop systems.
 */
@RestrictTo(LIBRARY)
public interface IMediaControllerCallback extends android.os.IInterface {
  /** Local-side IPC implementation stub class. */
  public abstract static class Stub extends android.os.Binder implements IMediaControllerCallback {
    private static final String DESCRIPTOR =
        "android.support.v4.media.session.IMediaControllerCallback";

    /** Construct the stub at attach it to the interface. */
    // Using this in constructor
    @SuppressWarnings({"method.invocation.invalid", "argument.type.incompatible"})
    public Stub() {
      this.attachInterface(this, DESCRIPTOR);
    }

    /**
     * Cast an IBinder object into an androidx.media3.session.legacy.IMediaControllerCallback
     * interface, generating a proxy if needed.
     */
    @Nullable
    public static IMediaControllerCallback asInterface(@Nullable android.os.IBinder obj) {
      if ((obj == null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin != null) && (iin instanceof IMediaControllerCallback))) {
        return ((IMediaControllerCallback) iin);
      }
      return new Proxy(obj);
    }

    @Override
    public android.os.IBinder asBinder() {
      return this;
    }

    @Override
    public boolean onTransact(
        int code, android.os.Parcel data, @Nullable android.os.Parcel reply, int flags)
        throws android.os.RemoteException {
      String descriptor = DESCRIPTOR;
      switch (code) {
        case INTERFACE_TRANSACTION:
          {
            checkNotNull(reply).writeString(descriptor);
            return true;
          }
        case TRANSACTION_onPlaybackStateChanged:
          {
            data.enforceInterface(descriptor);
            PlaybackStateCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = PlaybackStateCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.onPlaybackStateChanged(arg0);
            return true;
          }
        case TRANSACTION_onRepeatModeChanged:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            this.onRepeatModeChanged(arg0);
            return true;
          }
        case TRANSACTION_onCaptioningEnabledChanged:
          {
            data.enforceInterface(descriptor);
            boolean arg0;
            arg0 = (0 != data.readInt());
            this.onCaptioningEnabledChanged(arg0);
            return true;
          }
        case TRANSACTION_onShuffleModeChanged:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            this.onShuffleModeChanged(arg0);
            return true;
          }
        case TRANSACTION_onSessionReady:
          {
            data.enforceInterface(descriptor);
            this.onSessionReady();
            return true;
          }
        default:
          {
            return super.onTransact(code, data, reply, flags);
          }
      }
    }

    private static class Proxy implements IMediaControllerCallback {
      private android.os.IBinder remote;

      Proxy(android.os.IBinder remote) {
        this.remote = remote;
      }

      @Override
      public android.os.IBinder asBinder() {
        return remote;
      }

      public String getInterfaceDescriptor() {
        return DESCRIPTOR;
      }

      // These callbacks are for the TransportController

      @Override
      public void onPlaybackStateChanged(@Nullable PlaybackStateCompat state)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((state != null)) {
            data.writeInt(1);
            state.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onPlaybackStateChanged,
                  data,
                  null,
                  android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onPlaybackStateChanged(state);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onRepeatModeChanged(int repeatMode) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(repeatMode);
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onRepeatModeChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onRepeatModeChanged(repeatMode);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onCaptioningEnabledChanged(boolean enabled) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(((enabled) ? (1) : (0)));
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onCaptioningEnabledChanged,
                  data,
                  null,
                  android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onCaptioningEnabledChanged(enabled);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onShuffleModeChanged(int shuffleMode) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(shuffleMode);
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onShuffleModeChanged,
                  data,
                  null,
                  android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onShuffleModeChanged(shuffleMode);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onSessionReady() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onSessionReady, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onSessionReady();
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Nullable public static IMediaControllerCallback defaultImpl;
    }

    static final int TRANSACTION_onPlaybackStateChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onRepeatModeChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_onCaptioningEnabledChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_onShuffleModeChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_onSessionReady = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);

    public static boolean setDefaultImpl(IMediaControllerCallback impl) {
      // Only one user of this interface can use this function
      // at a time. This is a heuristic to detect if two different
      // users in the same process use this function.
      if (Proxy.defaultImpl != null) {
        throw new IllegalStateException("setDefaultImpl() called twice");
      }
      if (impl != null) {
        Proxy.defaultImpl = impl;
        return true;
      }
      return false;
    }

    @Nullable
    public static IMediaControllerCallback getDefaultImpl() {
      return Proxy.defaultImpl;
    }
  }

  // These callbacks are for the TransportController

  public void onPlaybackStateChanged(@Nullable PlaybackStateCompat state)
      throws android.os.RemoteException;

  public void onRepeatModeChanged(int repeatMode) throws android.os.RemoteException;

  public void onCaptioningEnabledChanged(boolean enabled) throws android.os.RemoteException;

  public void onShuffleModeChanged(int shuffleMode) throws android.os.RemoteException;

  public void onSessionReady() throws android.os.RemoteException;
}
