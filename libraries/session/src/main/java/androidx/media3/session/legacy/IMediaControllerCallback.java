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
import static androidx.media3.common.util.Assertions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;

/**
 * Callback interface for a MediaSessionCompat to send updates to a MediaControllerCompat. This is
 * only used on pre-Lollipop systems.
 */
@UnstableApi
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
        case TRANSACTION_onEvent:
          {
            data.enforceInterface(descriptor);
            String arg0;
            arg0 = data.readString();
            android.os.Bundle arg1;
            if ((0 != data.readInt())) {
              arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
            } else {
              arg1 = null;
            }
            this.onEvent(arg0, arg1);
            return true;
          }
        case TRANSACTION_onSessionDestroyed:
          {
            data.enforceInterface(descriptor);
            this.onSessionDestroyed();
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
        case TRANSACTION_onMetadataChanged:
          {
            data.enforceInterface(descriptor);
            MediaMetadataCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = MediaMetadataCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.onMetadataChanged(arg0);
            return true;
          }
        case TRANSACTION_onQueueChanged:
          {
            data.enforceInterface(descriptor);
            java.util.List<MediaSessionCompat.QueueItem> arg0;
            arg0 = data.createTypedArrayList(MediaSessionCompat.QueueItem.CREATOR);
            this.onQueueChanged(arg0);
            return true;
          }
        case TRANSACTION_onQueueTitleChanged:
          {
            data.enforceInterface(descriptor);
            CharSequence arg0;
            if (0 != data.readInt()) {
              arg0 = android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.onQueueTitleChanged(arg0);
            return true;
          }
        case TRANSACTION_onExtrasChanged:
          {
            data.enforceInterface(descriptor);
            android.os.Bundle arg0;
            if ((0 != data.readInt())) {
              arg0 = android.os.Bundle.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.onExtrasChanged(arg0);
            return true;
          }
        case TRANSACTION_onVolumeInfoChanged:
          {
            data.enforceInterface(descriptor);
            ParcelableVolumeInfo arg0;
            if ((0 != data.readInt())) {
              arg0 = ParcelableVolumeInfo.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.onVolumeInfoChanged(arg0);
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
        case TRANSACTION_onShuffleModeChangedRemoved:
          {
            data.enforceInterface(descriptor);
            boolean arg0;
            arg0 = (0 != data.readInt());
            this.onShuffleModeChangedRemoved(arg0);
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

      @Override
      public void onEvent(@Nullable String event, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(event);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(Stub.TRANSACTION_onEvent, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onEvent(event, extras);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onSessionDestroyed() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onSessionDestroyed, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onSessionDestroyed();
            return;
          }
        } finally {
          data.recycle();
        }
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
      public void onMetadataChanged(@Nullable MediaMetadataCompat metadata)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((metadata != null)) {
            data.writeInt(1);
            metadata.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onMetadataChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onMetadataChanged(metadata);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onQueueChanged(@Nullable java.util.List<MediaSessionCompat.QueueItem> queue)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeTypedList(queue);
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onQueueChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onQueueChanged(queue);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onQueueTitleChanged(@Nullable CharSequence title)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if (title != null) {
            data.writeInt(1);
            android.text.TextUtils.writeToParcel(title, data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onQueueTitleChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onQueueTitleChanged(title);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onExtrasChanged(@Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onExtrasChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onExtrasChanged(extras);
            return;
          }
        } finally {
          data.recycle();
        }
      }

      @Override
      public void onVolumeInfoChanged(@Nullable ParcelableVolumeInfo info)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((info != null)) {
            data.writeInt(1);
            info.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onVolumeInfoChanged, data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onVolumeInfoChanged(info);
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
      public void onShuffleModeChangedRemoved(boolean enabled) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(((enabled) ? (1) : (0)));
          boolean status =
              remote.transact(
                  Stub.TRANSACTION_onShuffleModeChangedRemoved,
                  data,
                  null,
                  android.os.IBinder.FLAG_ONEWAY);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).onShuffleModeChangedRemoved(enabled);
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

    static final int TRANSACTION_onEvent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onSessionDestroyed =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_onPlaybackStateChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onMetadataChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_onQueueChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_onQueueTitleChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_onExtrasChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_onVolumeInfoChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_onRepeatModeChanged =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_onShuffleModeChangedRemoved =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
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

  public void onEvent(@Nullable String event, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void onSessionDestroyed() throws android.os.RemoteException;

  // These callbacks are for the TransportController

  public void onPlaybackStateChanged(@Nullable PlaybackStateCompat state)
      throws android.os.RemoteException;

  public void onMetadataChanged(@Nullable MediaMetadataCompat metadata)
      throws android.os.RemoteException;

  public void onQueueChanged(@Nullable java.util.List<MediaSessionCompat.QueueItem> queue)
      throws android.os.RemoteException;

  public void onQueueTitleChanged(@Nullable CharSequence title) throws android.os.RemoteException;

  public void onExtrasChanged(@Nullable android.os.Bundle extras) throws android.os.RemoteException;

  public void onVolumeInfoChanged(@Nullable ParcelableVolumeInfo info)
      throws android.os.RemoteException;

  public void onRepeatModeChanged(int repeatMode) throws android.os.RemoteException;

  public void onShuffleModeChangedRemoved(boolean enabled) throws android.os.RemoteException;

  public void onCaptioningEnabledChanged(boolean enabled) throws android.os.RemoteException;

  public void onShuffleModeChanged(int shuffleMode) throws android.os.RemoteException;

  public void onSessionReady() throws android.os.RemoteException;
}
