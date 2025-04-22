/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.exoplayer.mediacodec;

import static android.os.Build.VERSION.SDK_INT;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.MediaCodec;
import android.os.HandlerThread;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Supplier;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The default {@link MediaCodecAdapter.Factory}.
 *
 * <p>By default, this factory {@link #createAdapter creates} {@link AsynchronousMediaCodecAdapter}
 * instances on devices with API level &gt;= 31 (Android 12+). For devices with older API versions,
 * the default behavior is to create {@link SynchronousMediaCodecAdapter} instances. The factory
 * offers APIs to force the creation of {@link AsynchronousMediaCodecAdapter} (applicable for
 * devices with API &gt;= 23) or {@link SynchronousMediaCodecAdapter} instances.
 */
@UnstableApi
public final class DefaultMediaCodecAdapterFactory implements MediaCodecAdapter.Factory {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({MODE_DEFAULT, MODE_ENABLED, MODE_DISABLED})
  private @interface Mode {}

  private static final int MODE_DEFAULT = 0;
  private static final int MODE_ENABLED = 1;
  private static final int MODE_DISABLED = 2;

  private static final String TAG = "DMCodecAdapterFactory";

  @Nullable private final Context context;
  @Nullable private final Supplier<HandlerThread> callbackThreadSupplier;
  @Nullable private final Supplier<HandlerThread> queueingThreadSupplier;

  private @Mode int asynchronousMode;
  private boolean asyncCryptoFlagEnabled;

  /**
   * @deprecated Use {@link #DefaultMediaCodecAdapterFactory(Context)} instead.
   */
  @Deprecated
  public DefaultMediaCodecAdapterFactory() {
    asynchronousMode = MODE_DEFAULT;
    asyncCryptoFlagEnabled = false;
    context = null;
    callbackThreadSupplier = null;
    queueingThreadSupplier = null;
  }

  /**
   * Creates the default media codec adapter factory.
   *
   * @param context A {@link Context}.
   */
  public DefaultMediaCodecAdapterFactory(Context context) {
    this(context, null, null);
  }

  /**
   * Creates the default media codec adapter factory.
   *
   * @param context A {@link Context}.
   * @param callbackThreadSupplier A supplier of {@link HandlerThread} used for {@link MediaCodec}
   *     callbacks invoked when buffers are available.
   * @param queueingThreadSupplier A supplier of {@link HandlerThread} to use for queueing buffers.
   */
  public DefaultMediaCodecAdapterFactory(
      Context context,
      @Nullable Supplier<HandlerThread> callbackThreadSupplier,
      @Nullable Supplier<HandlerThread> queueingThreadSupplier) {
    this.context = context;
    asynchronousMode = MODE_DEFAULT;
    asyncCryptoFlagEnabled = false;
    this.callbackThreadSupplier = callbackThreadSupplier;
    this.queueingThreadSupplier = queueingThreadSupplier;
  }

  /**
   * Forces this factory to always create {@link AsynchronousMediaCodecAdapter} instances, provided
   * the device API level is &gt;= 23. For devices with API level &lt; 23, the factory will create
   * {@link SynchronousMediaCodecAdapter SynchronousMediaCodecAdapters}.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory forceEnableAsynchronous() {
    asynchronousMode = MODE_ENABLED;
    return this;
  }

  /**
   * Forces the factory to always create {@link SynchronousMediaCodecAdapter} instances.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory forceDisableAsynchronous() {
    asynchronousMode = MODE_DISABLED;
    return this;
  }

  /**
   * Sets whether to enable {@link MediaCodec#CONFIGURE_FLAG_USE_CRYPTO_ASYNC} on API 34 and above
   * for {@link AsynchronousMediaCodecAdapter} instances.
   *
   * <p>This method is experimental. Its default value may change, or it may be renamed or removed
   * in a future release.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory experimentalSetAsyncCryptoFlagEnabled(
      boolean enableAsyncCryptoFlag) {
    asyncCryptoFlagEnabled = enableAsyncCryptoFlag;
    return this;
  }

  @Override
  public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration)
      throws IOException {
    if (SDK_INT >= 23
        && (asynchronousMode == MODE_ENABLED
            || (asynchronousMode == MODE_DEFAULT && shouldUseAsynchronousAdapterInDefaultMode()))) {
      int trackType = MimeTypes.getTrackType(configuration.format.sampleMimeType);
      Log.i(
          TAG,
          "Creating an asynchronous MediaCodec adapter for track type "
              + Util.getTrackTypeString(trackType));
      AsynchronousMediaCodecAdapter.Factory factory =
          callbackThreadSupplier != null && queueingThreadSupplier != null
              ? new AsynchronousMediaCodecAdapter.Factory(
                  callbackThreadSupplier, queueingThreadSupplier)
              : new AsynchronousMediaCodecAdapter.Factory(trackType);
      factory.experimentalSetAsyncCryptoFlagEnabled(asyncCryptoFlagEnabled);
      return factory.createAdapter(configuration);
    }
    return new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration);
  }

  private boolean shouldUseAsynchronousAdapterInDefaultMode() {
    if (SDK_INT >= 31) {
      // Asynchronous codec interactions started to be reliable for all devices on API 31+.
      return true;
    }
    // Allow additional devices that work reliably with the asynchronous adapter and show
    // performance problems when not using it.
    if (context != null
        && SDK_INT >= 28
        && context.getPackageManager().hasSystemFeature("com.amazon.hardware.tv_screen")) {
      return true;
    }
    return false;
  }
}
