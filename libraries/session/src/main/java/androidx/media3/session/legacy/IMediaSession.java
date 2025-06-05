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
import org.checkerframework.checker.nullness.qual.PolyNull;

/** Interface to a MediaSessionCompat. */
@UnstableApi
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
        case TRANSACTION_sendCommand:
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
            MediaSessionCompat.ResultReceiverWrapper arg2;
            if ((0 != data.readInt())) {
              arg2 = MediaSessionCompat.ResultReceiverWrapper.CREATOR.createFromParcel(data);
            } else {
              arg2 = null;
            }
            this.sendCommand(arg0, arg1, arg2);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_sendMediaButton:
          {
            data.enforceInterface(descriptor);
            android.view.KeyEvent arg0;
            if ((0 != data.readInt())) {
              arg0 = android.view.KeyEvent.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            boolean result = this.sendMediaButton(arg0);
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(((result) ? (1) : (0)));
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
        case TRANSACTION_isTransportControlEnabled:
          {
            data.enforceInterface(descriptor);
            boolean result = this.isTransportControlEnabled();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(((result) ? (1) : (0)));
            return true;
          }
        case TRANSACTION_getPackageName:
          {
            data.enforceInterface(descriptor);
            String result = this.getPackageName();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeString(result);
            return true;
          }
        case TRANSACTION_getTag:
          {
            data.enforceInterface(descriptor);
            String result = this.getTag();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeString(result);
            return true;
          }
        case TRANSACTION_getLaunchPendingIntent:
          {
            data.enforceInterface(descriptor);
            android.app.PendingIntent result = this.getLaunchPendingIntent();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        case TRANSACTION_getFlags:
          {
            data.enforceInterface(descriptor);
            long result = this.getFlags();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeLong(result);
            return true;
          }
        case TRANSACTION_getVolumeAttributes:
          {
            data.enforceInterface(descriptor);
            ParcelableVolumeInfo result = this.getVolumeAttributes();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        case TRANSACTION_adjustVolume:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            int arg1;
            arg1 = data.readInt();
            String arg2;
            arg2 = data.readString();
            this.adjustVolume(arg0, arg1, arg2);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setVolumeTo:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            int arg1;
            arg1 = data.readInt();
            String arg2;
            arg2 = data.readString();
            this.setVolumeTo(arg0, arg1, arg2);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_getMetadata:
          {
            data.enforceInterface(descriptor);
            MediaMetadataCompat result = this.getMetadata();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
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
        case TRANSACTION_getQueue:
          {
            data.enforceInterface(descriptor);
            java.util.List<MediaSessionCompat.QueueItem> result = this.getQueue();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeTypedList(result);
            return true;
          }
        case TRANSACTION_getQueueTitle:
          {
            data.enforceInterface(descriptor);
            CharSequence result = this.getQueueTitle();
            checkNotNull(reply).writeNoException();
            if (result != null) {
              checkNotNull(reply).writeInt(1);
              android.text.TextUtils.writeToParcel(
                  result, reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        case TRANSACTION_getExtras:
          {
            data.enforceInterface(descriptor);
            android.os.Bundle result = this.getExtras();
            checkNotNull(reply).writeNoException();
            if ((result != null)) {
              checkNotNull(reply).writeInt(1);
              result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            } else {
              checkNotNull(reply).writeInt(0);
            }
            return true;
          }
        case TRANSACTION_getRatingType:
          {
            data.enforceInterface(descriptor);
            int result = this.getRatingType();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(result);
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
        case TRANSACTION_isShuffleModeEnabledRemoved:
          {
            data.enforceInterface(descriptor);
            boolean result = this.isShuffleModeEnabledRemoved();
            checkNotNull(reply).writeNoException();
            checkNotNull(reply).writeInt(((result) ? (1) : (0)));
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
        case TRANSACTION_addQueueItem:
          {
            data.enforceInterface(descriptor);
            MediaDescriptionCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = MediaDescriptionCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.addQueueItem(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_addQueueItemAt:
          {
            data.enforceInterface(descriptor);
            MediaDescriptionCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = MediaDescriptionCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            int arg1;
            arg1 = data.readInt();
            this.addQueueItemAt(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_removeQueueItem:
          {
            data.enforceInterface(descriptor);
            MediaDescriptionCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = MediaDescriptionCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.removeQueueItem(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_removeQueueItemAt:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            this.removeQueueItemAt(arg0);
            checkNotNull(reply).writeNoException();
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
        case TRANSACTION_prepare:
          {
            data.enforceInterface(descriptor);
            this.prepare();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_prepareFromMediaId:
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
            this.prepareFromMediaId(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_prepareFromSearch:
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
            this.prepareFromSearch(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_prepareFromUri:
          {
            data.enforceInterface(descriptor);
            android.net.Uri arg0;
            if ((0 != data.readInt())) {
              arg0 = android.net.Uri.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            android.os.Bundle arg1;
            if ((0 != data.readInt())) {
              arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
            } else {
              arg1 = null;
            }
            this.prepareFromUri(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_play:
          {
            data.enforceInterface(descriptor);
            this.play();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_playFromMediaId:
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
            this.playFromMediaId(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_playFromSearch:
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
            this.playFromSearch(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_playFromUri:
          {
            data.enforceInterface(descriptor);
            android.net.Uri arg0;
            if ((0 != data.readInt())) {
              arg0 = android.net.Uri.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            android.os.Bundle arg1;
            if ((0 != data.readInt())) {
              arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
            } else {
              arg1 = null;
            }
            this.playFromUri(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_skipToQueueItem:
          {
            data.enforceInterface(descriptor);
            long arg0;
            arg0 = data.readLong();
            this.skipToQueueItem(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_pause:
          {
            data.enforceInterface(descriptor);
            this.pause();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_stop:
          {
            data.enforceInterface(descriptor);
            this.stop();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_next:
          {
            data.enforceInterface(descriptor);
            this.next();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_previous:
          {
            data.enforceInterface(descriptor);
            this.previous();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_fastForward:
          {
            data.enforceInterface(descriptor);
            this.fastForward();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_rewind:
          {
            data.enforceInterface(descriptor);
            this.rewind();
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_seekTo:
          {
            data.enforceInterface(descriptor);
            long arg0;
            arg0 = data.readLong();
            this.seekTo(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_rate:
          {
            data.enforceInterface(descriptor);
            RatingCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = RatingCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            this.rate(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_rateWithExtras:
          {
            data.enforceInterface(descriptor);
            RatingCompat arg0;
            if ((0 != data.readInt())) {
              arg0 = RatingCompat.CREATOR.createFromParcel(data);
            } else {
              arg0 = null;
            }
            android.os.Bundle arg1;
            if ((0 != data.readInt())) {
              arg1 = android.os.Bundle.CREATOR.createFromParcel(data);
            } else {
              arg1 = null;
            }
            this.rateWithExtras(arg0, arg1);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setPlaybackSpeed:
          {
            data.enforceInterface(descriptor);
            float arg0;
            arg0 = data.readFloat();
            this.setPlaybackSpeed(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setCaptioningEnabled:
          {
            data.enforceInterface(descriptor);
            boolean arg0;
            arg0 = (0 != data.readInt());
            this.setCaptioningEnabled(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setRepeatMode:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            this.setRepeatMode(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setShuffleModeEnabledRemoved:
          {
            data.enforceInterface(descriptor);
            boolean arg0;
            arg0 = (0 != data.readInt());
            this.setShuffleModeEnabledRemoved(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_setShuffleMode:
          {
            data.enforceInterface(descriptor);
            int arg0;
            arg0 = data.readInt();
            this.setShuffleMode(arg0);
            checkNotNull(reply).writeNoException();
            return true;
          }
        case TRANSACTION_sendCustomAction:
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
            this.sendCustomAction(arg0, arg1);
            checkNotNull(reply).writeNoException();
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

      // Next ID: 50

      @Override
      public void sendCommand(
          @Nullable String command,
          @Nullable android.os.Bundle args,
          @Nullable MediaSessionCompat.ResultReceiverWrapper cb)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(command);
          if ((args != null)) {
            data.writeInt(1);
            args.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          if ((cb != null)) {
            data.writeInt(1);
            cb.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_sendCommand, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).sendCommand(command, args, cb);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public boolean sendMediaButton(@Nullable android.view.KeyEvent mediaButton)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        boolean result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((mediaButton != null)) {
            data.writeInt(1);
            mediaButton.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_sendMediaButton, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).sendMediaButton(mediaButton);
          }
          reply.readException();
          result = (0 != reply.readInt());
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
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

      @Override
      public boolean isTransportControlEnabled() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        boolean result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status =
              remote.transact(Stub.TRANSACTION_isTransportControlEnabled, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).isTransportControlEnabled();
          }
          reply.readException();
          result = (0 != reply.readInt());
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public String getPackageName() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        String result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getPackageName, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getPackageName();
          }
          reply.readException();
          result = reply.readString();
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public String getTag() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        String result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getTag, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getTag();
          }
          reply.readException();
          result = reply.readString();
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public android.app.PendingIntent getLaunchPendingIntent() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        android.app.PendingIntent result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getLaunchPendingIntent, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getLaunchPendingIntent();
          }
          reply.readException();
          if ((0 != reply.readInt())) {
            result = android.app.PendingIntent.CREATOR.createFromParcel(reply);
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
      public long getFlags() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        long result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getFlags, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getFlags();
          }
          reply.readException();
          result = reply.readLong();
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public ParcelableVolumeInfo getVolumeAttributes() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        ParcelableVolumeInfo result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getVolumeAttributes, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getVolumeAttributes();
          }
          reply.readException();
          if ((0 != reply.readInt())) {
            result = ParcelableVolumeInfo.CREATOR.createFromParcel(reply);
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
      public void adjustVolume(int direction, int flags, @Nullable String packageName)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(direction);
          data.writeInt(flags);
          data.writeString(packageName);
          boolean status = remote.transact(Stub.TRANSACTION_adjustVolume, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).adjustVolume(direction, flags, packageName);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setVolumeTo(int value, int flags, @Nullable String packageName)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(value);
          data.writeInt(flags);
          data.writeString(packageName);
          boolean status = remote.transact(Stub.TRANSACTION_setVolumeTo, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setVolumeTo(value, flags, packageName);
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
      public MediaMetadataCompat getMetadata() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        MediaMetadataCompat result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getMetadata, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getMetadata();
          }
          reply.readException();
          if ((0 != reply.readInt())) {
            result = MediaMetadataCompat.CREATOR.createFromParcel(reply);
          } else {
            result = null;
          }
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
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

      @Nullable
      @Override
      public java.util.List<MediaSessionCompat.QueueItem> getQueue()
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        java.util.List<MediaSessionCompat.QueueItem> result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getQueue, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getQueue();
          }
          reply.readException();
          result = reply.createTypedArrayList(MediaSessionCompat.QueueItem.CREATOR);
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public CharSequence getQueueTitle() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        CharSequence result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getQueueTitle, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getQueueTitle();
          }
          reply.readException();
          if (0 != reply.readInt()) {
            result = android.text.TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(reply);
          } else {
            result = null;
          }
        } finally {
          reply.recycle();
          data.recycle();
        }
        return result;
      }

      @Nullable
      @Override
      public android.os.Bundle getExtras() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        android.os.Bundle result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getExtras, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getExtras();
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

      @Override
      public int getRatingType() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_getRatingType, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).getRatingType();
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
      public boolean isShuffleModeEnabledRemoved() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        boolean result;
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status =
              remote.transact(Stub.TRANSACTION_isShuffleModeEnabledRemoved, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            return checkNotNull(getDefaultImpl()).isShuffleModeEnabledRemoved();
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

      @Override
      public void addQueueItem(@Nullable MediaDescriptionCompat description)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((description != null)) {
            data.writeInt(1);
            description.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_addQueueItem, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).addQueueItem(description);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void addQueueItemAt(@Nullable MediaDescriptionCompat description, int index)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((description != null)) {
            data.writeInt(1);
            description.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          data.writeInt(index);
          boolean status = remote.transact(Stub.TRANSACTION_addQueueItemAt, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).addQueueItemAt(description, index);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void removeQueueItem(@Nullable MediaDescriptionCompat description)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((description != null)) {
            data.writeInt(1);
            description.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_removeQueueItem, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).removeQueueItem(description);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void removeQueueItemAt(int index) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(index);
          boolean status = remote.transact(Stub.TRANSACTION_removeQueueItemAt, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).removeQueueItemAt(index);
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

      // These commands are for the TransportControls

      @Override
      public void prepare() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_prepare, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).prepare();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void prepareFromMediaId(@Nullable String uri, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(uri);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_prepareFromMediaId, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).prepareFromMediaId(uri, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void prepareFromSearch(@Nullable String string, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(string);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_prepareFromSearch, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).prepareFromSearch(string, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void prepareFromUri(@Nullable android.net.Uri uri, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((uri != null)) {
            data.writeInt(1);
            uri.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_prepareFromUri, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).prepareFromUri(uri, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void play() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_play, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).play();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void playFromMediaId(@Nullable String uri, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(uri);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_playFromMediaId, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).playFromMediaId(uri, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void playFromSearch(@Nullable String string, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(string);
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_playFromSearch, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).playFromSearch(string, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void playFromUri(@Nullable android.net.Uri uri, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((uri != null)) {
            data.writeInt(1);
            uri.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_playFromUri, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).playFromUri(uri, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void skipToQueueItem(long id) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeLong(id);
          boolean status = remote.transact(Stub.TRANSACTION_skipToQueueItem, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).skipToQueueItem(id);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void pause() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_pause, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).pause();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void stop() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_stop, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).stop();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void next() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_next, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).next();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void previous() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_previous, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).previous();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void fastForward() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_fastForward, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).fastForward();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void rewind() throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          boolean status = remote.transact(Stub.TRANSACTION_rewind, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).rewind();
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void seekTo(long pos) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeLong(pos);
          boolean status = remote.transact(Stub.TRANSACTION_seekTo, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).seekTo(pos);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void rate(@Nullable RatingCompat rating) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((rating != null)) {
            data.writeInt(1);
            rating.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_rate, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).rate(rating);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void rateWithExtras(@Nullable RatingCompat rating, @Nullable android.os.Bundle extras)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          if ((rating != null)) {
            data.writeInt(1);
            rating.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          if ((extras != null)) {
            data.writeInt(1);
            extras.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_rateWithExtras, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).rateWithExtras(rating, extras);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setPlaybackSpeed(float speed) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeFloat(speed);
          boolean status = remote.transact(Stub.TRANSACTION_setPlaybackSpeed, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setPlaybackSpeed(speed);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setCaptioningEnabled(boolean enabled) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(((enabled) ? (1) : (0)));
          boolean status = remote.transact(Stub.TRANSACTION_setCaptioningEnabled, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setCaptioningEnabled(enabled);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setRepeatMode(int repeatMode) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(repeatMode);
          boolean status = remote.transact(Stub.TRANSACTION_setRepeatMode, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setRepeatMode(repeatMode);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setShuffleModeEnabledRemoved(boolean shuffleMode)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(((shuffleMode) ? (1) : (0)));
          boolean status =
              remote.transact(Stub.TRANSACTION_setShuffleModeEnabledRemoved, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setShuffleModeEnabledRemoved(shuffleMode);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void setShuffleMode(int shuffleMode) throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeInt(shuffleMode);
          boolean status = remote.transact(Stub.TRANSACTION_setShuffleMode, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).setShuffleMode(shuffleMode);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Override
      public void sendCustomAction(@Nullable String action, @Nullable android.os.Bundle args)
          throws android.os.RemoteException {
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
          data.writeInterfaceToken(DESCRIPTOR);
          data.writeString(action);
          if ((args != null)) {
            data.writeInt(1);
            args.writeToParcel(data, 0);
          } else {
            data.writeInt(0);
          }
          boolean status = remote.transact(Stub.TRANSACTION_sendCustomAction, data, reply, 0);
          if (!status && getDefaultImpl() != null) {
            checkNotNull(getDefaultImpl()).sendCustomAction(action, args);
            return;
          }
          reply.readException();
        } finally {
          reply.recycle();
          data.recycle();
        }
      }

      @Nullable public static IMediaSession defaultImpl;
    }

    static final int TRANSACTION_sendCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_sendMediaButton = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_registerCallbackListener =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_unregisterCallbackListener =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_isTransportControlEnabled =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_getPackageName = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_getTag = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_getLaunchPendingIntent =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_getFlags = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_getVolumeAttributes =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_adjustVolume = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_setVolumeTo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_getMetadata = (android.os.IBinder.FIRST_CALL_TRANSACTION + 26);
    static final int TRANSACTION_getPlaybackState =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 27);
    static final int TRANSACTION_getQueue = (android.os.IBinder.FIRST_CALL_TRANSACTION + 28);
    static final int TRANSACTION_getQueueTitle = (android.os.IBinder.FIRST_CALL_TRANSACTION + 29);
    static final int TRANSACTION_getExtras = (android.os.IBinder.FIRST_CALL_TRANSACTION + 30);
    static final int TRANSACTION_getRatingType = (android.os.IBinder.FIRST_CALL_TRANSACTION + 31);
    static final int TRANSACTION_isCaptioningEnabled =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 44);
    static final int TRANSACTION_getRepeatMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 36);
    static final int TRANSACTION_isShuffleModeEnabledRemoved =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 37);
    static final int TRANSACTION_getShuffleMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 46);
    static final int TRANSACTION_addQueueItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 40);
    static final int TRANSACTION_addQueueItemAt = (android.os.IBinder.FIRST_CALL_TRANSACTION + 41);
    static final int TRANSACTION_removeQueueItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 42);
    static final int TRANSACTION_removeQueueItemAt =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 43);
    static final int TRANSACTION_getSessionInfo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 49);
    static final int TRANSACTION_prepare = (android.os.IBinder.FIRST_CALL_TRANSACTION + 32);
    static final int TRANSACTION_prepareFromMediaId =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 33);
    static final int TRANSACTION_prepareFromSearch =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 34);
    static final int TRANSACTION_prepareFromUri = (android.os.IBinder.FIRST_CALL_TRANSACTION + 35);
    static final int TRANSACTION_play = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_playFromMediaId = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_playFromSearch = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_playFromUri = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_skipToQueueItem = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16);
    static final int TRANSACTION_pause = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_stop = (android.os.IBinder.FIRST_CALL_TRANSACTION + 18);
    static final int TRANSACTION_next = (android.os.IBinder.FIRST_CALL_TRANSACTION + 19);
    static final int TRANSACTION_previous = (android.os.IBinder.FIRST_CALL_TRANSACTION + 20);
    static final int TRANSACTION_fastForward = (android.os.IBinder.FIRST_CALL_TRANSACTION + 21);
    static final int TRANSACTION_rewind = (android.os.IBinder.FIRST_CALL_TRANSACTION + 22);
    static final int TRANSACTION_seekTo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 23);
    static final int TRANSACTION_rate = (android.os.IBinder.FIRST_CALL_TRANSACTION + 24);
    static final int TRANSACTION_rateWithExtras = (android.os.IBinder.FIRST_CALL_TRANSACTION + 50);
    static final int TRANSACTION_setPlaybackSpeed =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 48);
    static final int TRANSACTION_setCaptioningEnabled =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 45);
    static final int TRANSACTION_setRepeatMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 38);
    static final int TRANSACTION_setShuffleModeEnabledRemoved =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 39);
    static final int TRANSACTION_setShuffleMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 47);
    static final int TRANSACTION_sendCustomAction =
        (android.os.IBinder.FIRST_CALL_TRANSACTION + 25);

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

  // Next ID: 50

  public void sendCommand(
      @Nullable String command,
      @Nullable android.os.Bundle args,
      @Nullable MediaSessionCompat.ResultReceiverWrapper cb)
      throws android.os.RemoteException;

  public boolean sendMediaButton(@Nullable android.view.KeyEvent mediaButton)
      throws android.os.RemoteException;

  public void registerCallbackListener(@Nullable IMediaControllerCallback cb)
      throws android.os.RemoteException;

  public void unregisterCallbackListener(@Nullable IMediaControllerCallback cb)
      throws android.os.RemoteException;

  public boolean isTransportControlEnabled() throws android.os.RemoteException;

  @Nullable
  public String getPackageName() throws android.os.RemoteException;

  @Nullable
  public String getTag() throws android.os.RemoteException;

  @Nullable
  public android.app.PendingIntent getLaunchPendingIntent() throws android.os.RemoteException;

  public long getFlags() throws android.os.RemoteException;

  @Nullable
  public ParcelableVolumeInfo getVolumeAttributes() throws android.os.RemoteException;

  public void adjustVolume(int direction, int flags, @Nullable String packageName)
      throws android.os.RemoteException;

  public void setVolumeTo(int value, int flags, @Nullable String packageName)
      throws android.os.RemoteException;

  @Nullable
  public MediaMetadataCompat getMetadata() throws android.os.RemoteException;

  @Nullable
  public PlaybackStateCompat getPlaybackState() throws android.os.RemoteException;

  @Nullable
  public java.util.List<MediaSessionCompat.QueueItem> getQueue() throws android.os.RemoteException;

  @Nullable
  public CharSequence getQueueTitle() throws android.os.RemoteException;

  @Nullable
  public android.os.Bundle getExtras() throws android.os.RemoteException;

  public int getRatingType() throws android.os.RemoteException;

  public boolean isCaptioningEnabled() throws android.os.RemoteException;

  public int getRepeatMode() throws android.os.RemoteException;

  public boolean isShuffleModeEnabledRemoved() throws android.os.RemoteException;

  public int getShuffleMode() throws android.os.RemoteException;

  public void addQueueItem(@Nullable MediaDescriptionCompat description)
      throws android.os.RemoteException;

  public void addQueueItemAt(@Nullable MediaDescriptionCompat description, int index)
      throws android.os.RemoteException;

  public void removeQueueItem(@Nullable MediaDescriptionCompat description)
      throws android.os.RemoteException;

  public void removeQueueItemAt(int index) throws android.os.RemoteException;

  @Nullable
  public android.os.Bundle getSessionInfo() throws android.os.RemoteException;

  // These commands are for the TransportControls

  public void prepare() throws android.os.RemoteException;

  public void prepareFromMediaId(@Nullable String uri, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void prepareFromSearch(@Nullable String string, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void prepareFromUri(@Nullable android.net.Uri uri, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void play() throws android.os.RemoteException;

  public void playFromMediaId(@Nullable String uri, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void playFromSearch(@Nullable String string, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void playFromUri(@Nullable android.net.Uri uri, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void skipToQueueItem(long id) throws android.os.RemoteException;

  public void pause() throws android.os.RemoteException;

  public void stop() throws android.os.RemoteException;

  public void next() throws android.os.RemoteException;

  public void previous() throws android.os.RemoteException;

  public void fastForward() throws android.os.RemoteException;

  public void rewind() throws android.os.RemoteException;

  public void seekTo(long pos) throws android.os.RemoteException;

  public void rate(@Nullable RatingCompat rating) throws android.os.RemoteException;

  public void rateWithExtras(@Nullable RatingCompat rating, @Nullable android.os.Bundle extras)
      throws android.os.RemoteException;

  public void setPlaybackSpeed(float speed) throws android.os.RemoteException;

  public void setCaptioningEnabled(boolean enabled) throws android.os.RemoteException;

  public void setRepeatMode(int repeatMode) throws android.os.RemoteException;

  public void setShuffleModeEnabledRemoved(boolean shuffleMode) throws android.os.RemoteException;

  public void setShuffleMode(int shuffleMode) throws android.os.RemoteException;

  public void sendCustomAction(@Nullable String action, @Nullable android.os.Bundle args)
      throws android.os.RemoteException;
}
