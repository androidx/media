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
import org.checkerframework.checker.nullness.qual.PolyNull;

/** Interface to a MediaSessionCompat. */
@RestrictTo(LIBRARY)
public interface IMediaSession extends android.os.IInterface {
  /** Local-side IPC implementation stub class. */
  public abstract static class Stub extends android.os.Binder implements IMediaSession {
    private static final String DESCRIPTOR = "android.support.v4.media.session.IMediaSession";

    /** Construct the stub at attach it to the interface. */
    // Using this in constructor
    @SuppressWarnings({"method.invocation.invalid", "argument.type.incompatible"})
    public Stub() {
      this.attachInterface(this, DESCRIPTOR);
    }

    /**
     * Cast an IBinder object into an androidx.media3.session.legacy.IMediaSession interface,
     * generating a proxy if needed.
     */
    public static @PolyNull IMediaSession asInterface(android.os.@PolyNull IBinder obj) {
      if ((obj == null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin != null) && (iin instanceof IMediaSession))) {
        return ((IMediaSession) iin);
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
        case TRANSACTION_registerCallbackListener:
          {
            data.enforceInterface(descriptor);
            IMediaControllerCallback arg0;
            arg0 = IMediaControllerCallback.Stub.asInterface(data.readStrongBinder());
            this.registerCallbackListener(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_unregisterCallbackListener:
          {
            data.enforceInterface(descriptor);
            IMediaControllerCallback arg0;
            arg0 = IMediaControllerCallback.Stub.asInterface(data.readStrongBinder());
            this.unregisterCallbackListener(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_getPlaybackState:
          {
            data.enforceInterface(descriptor);
            PlaybackStateCompat result = this.getPlaybackState();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        case TRANSACTION_isCaptioningEnabled:
          {
            data.enforceInterface(descriptor);
            boolean result = this.isCaptioningEnabled();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(((result) ? (1) : (0)));
            return true;
          }
        case TRANSACTION_getRepeatMode:
          {
            data.enforceInterface(descriptor);
            int result = this.getRepeatMode();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(result);
            return true;
          }
        case TRANSACTION_getShuffleMode:
          {
            data.enforceInterface(descriptor);
            int result = this.getShuffleMode();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(result);
            return true;
          }
        case TRANSACTION_getSessionInfo:
          {
            data.enforceInterface(descriptor);
            android.os.Bundle result = this.getSessionInfo();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        default:
          {
            return super.onTransact(code, data, reply, flags);
          }
      }
    }

    private static class Proxy implements IMediaSession {
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

      @SuppressWarnings("argument.type.incompatible") // writeStrongBinder not annotated correctly
      @Override
      public void registerCallbackListener(@Nullable IMediaControllerCallback cb)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeStrongBinder((((cb != null)) ? (cb.asBinder()) : (null)));
          boolean status =
              remote.transact(Stub.TRANSACTION_registerCallbackListener, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).registerCallbackListener(cb);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @SuppressWarnings("argument.type.incompatible") // writeStrongBinder not annotated correctly
      @Override
      public void unregisterCallbackListener(@Nullable IMediaControllerCallback cb)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeStrongBinder((((cb != null)) ? (cb.asBinder()) : (null)));
          boolean status =
              remote.transact(Stub.TRANSACTION_unregisterCallbackListener, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).unregisterCallbackListener(cb);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Nullable
      @Override
      public PlaybackStateCompat getPlaybackState() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        PlaybackStateCompat result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getPlaybackState, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getPlaybackState();
          }
          reply.readException();
          if ((0 != reply.readInt())) {
            result = PlaybackStateCompat.CREATOR.createFromParcel(reply);
          } else {
            result = null;
          }
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Override
      public boolean isCaptioningEnabled() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        boolean result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_isCaptioningEnabled, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).isCaptioningEnabled();
          }
          reply.readException();
          result = (0 != reply.readInt());
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Override
      public int getRepeatMode() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getRepeatMode, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getRepeatMode();
          }
          reply.readException();
          result = reply.readInt();
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Override
      public int getShuffleMode() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getShuffleMode, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getShuffleMode();
          }
          reply.readException();
          result = reply.readInt();
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public android.os.Bundle getSessionInfo() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        android.os.Bundle result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getSessionInfo, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getSessionInfo();
          }
          reply.readException();
          if ((0 != reply.readInt())) {
            result = android.os.Bundle.CREATOR.createFromParcel(reply);
          } else {
            result = null;
          }
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable public static IMediaSession defaultImpl;
    }

    static final int TRANSACTION_registerCallbackListener =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_unregisterCallbackListener =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_getPlaybackState =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 27);
    static final int TRANSACTION_isCaptioningEnabled =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 44);
    static final int TRANSACTION_getRepeatMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 36);
    static final int TRANSACTION_getShuffleMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 46);
    static final int TRANSACTION_getSessionInfo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 49);

    public static boolean setDefaultImpl(IMediaSession impl) {
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
    public static IMediaSession getDefaultImpl() {
      return Proxy.defaultImpl;
    }
  }

  public void registerCallbackListener(@Nullable IMediaControllerCallback cb)
      throws android.os.RemoteException;

  public void unregisterCallbackListener(@Nullable IMediaControllerCallback cb)
      throws android.os.RemoteException;

  @Nullable
  public PlaybackStateCompat getPlaybackState() throws android.os.RemoteException;

  public boolean isCaptioningEnabled() throws android.os.RemoteException;

  public int getRepeatMode() throws android.os.RemoteException;

  public int getShuffleMode() throws android.os.RemoteException;

  @Nullable
  public android.os.Bundle getSessionInfo() throws android.os.RemoteException;
}
