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
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.media.MediaCodec;
import android.os.HandlerThread;
import androidx.annotation.IntDef;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ExperimentalApi;
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
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The default {@link MediaCodecAdapter.Factory}.
 *
 * <p>By default, this factory {@link #createAdapter creates} {@link AsynchronousMediaCodecAdapter}
 * instances on devices with API level &gt;= 31 (Android 12+). For devices with older API versions,
 * the default behavior is to create {@link SynchronousMediaCodecAdapter} instances. The factory
 * offers APIs to force the creation of {@link AsynchronousMediaCodecAdapter} or {@link
 * SynchronousMediaCodecAdapter} instances. When {@link MediaCodecAdapter.Configuration#codecInfo}
 * represents an extension decoder, this factory delegates to the registered or default adapter
 * factory for that extension codec.
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

  private final @Nullable Context context;
  private final @Nullable Supplier<HandlerThread> callbackThreadSupplier;
  private final @Nullable Supplier<HandlerThread> queueingThreadSupplier;
  private final Map<String, MediaCodecAdapter.Factory> extensionAdapterFactories;

  private @Mode int asynchronousMode;
  private boolean asyncCryptoFlagEnabled;

  /**
   * @deprecated Use {@link #DefaultMediaCodecAdapterFactory(Context)} instead.
   */
  @Deprecated
  public DefaultMediaCodecAdapterFactory() {
    asynchronousMode = MODE_DEFAULT;
    context = null;
    callbackThreadSupplier = null;
    queueingThreadSupplier = null;
    extensionAdapterFactories = new HashMap<>();
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
    asyncCryptoFlagEnabled = true;
    this.callbackThreadSupplier = callbackThreadSupplier;
    this.queueingThreadSupplier = queueingThreadSupplier;
    extensionAdapterFactories = new HashMap<>();
  }

  /**
   * Forces this factory to always create {@link AsynchronousMediaCodecAdapter} instances.
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
   * Sets whether to enable {@link MediaCodec#CONFIGURE_FLAG_USE_CRYPTO_ASYNC} on API 36 and above
   * for {@link AsynchronousMediaCodecAdapter} instances. The default is {@code true}.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory setAsyncCryptoFlagEnabled(boolean enableAsyncCryptoFlag) {
    asyncCryptoFlagEnabled = enableAsyncCryptoFlag;
    return this;
  }

  /**
   * Sets the {@link MediaCodecAdapter.Factory} to use for a specific extension decoder.
   *
   * <p>The provided {@code factory} must return an extension codec adapter (for example from a
   * configured {@code Dav1dCodecAdapter.Factory}), optionally decorated with {@link
   * ForwardingMediaCodecAdapter}. Note that decorators around extension codec adapters do not
   * intercept {@link MediaCodecAdapter#getInputBuffer}, {@link MediaCodecAdapter#setOutputSurface},
   * or {@link MediaCodecAdapter#detachOutputSurface}.
   *
   * <p>This method is experimental and may be renamed or removed in a future release.
   *
   * @param codecName The non-empty name of the extension decoder (e.g. {@code
   *     "c2.media3.av1.dav1d.decoder"}).
   * @param factory The {@link MediaCodecAdapter.Factory} to use for creating the adapter.
   * @return This factory, for convenience.
   * @throws IllegalArgumentException If {@code codecName} is empty.
   */
  @ExperimentalApi // TODO(b/562381449): Remove or make non-experimental.
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory setExtensionAdapterFactory(
      String codecName, MediaCodecAdapter.Factory factory) {
    checkArgument(!codecName.isEmpty());
    extensionAdapterFactories.put(codecName, checkNotNull(factory));
    return this;
  }

  @Override
  public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration)
      throws IOException {
    if (configuration.codecInfo instanceof ExtensionCodecInfo) {
      return createExtensionAdapter(configuration);
    }
    if (asynchronousMode == MODE_ENABLED
        || (asynchronousMode == MODE_DEFAULT && shouldUseAsynchronousAdapterInDefaultMode())) {
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
      factory.setAsyncCryptoFlagEnabled(asyncCryptoFlagEnabled);
      return factory.createAdapter(configuration);
    }
    return new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration);
  }

  private MediaCodecAdapter createExtensionAdapter(MediaCodecAdapter.Configuration configuration)
      throws IOException {
    MediaCodecAdapter.@Nullable Factory customFactory =
        extensionAdapterFactories.get(configuration.codecInfo.name);
    if (customFactory != null) {
      return customFactory.createAdapter(configuration);
    }
    MediaCodecAdapter.Factory defaultFactory;
    try {
      // LINT.IfChange(dav1d_adapter_factory)
      if (configuration.codecInfo.name.equals("c2.media3.av1.dav1d.decoder")) {
        defaultFactory =
            Class.forName("androidx.media3.decoder.av1.Dav1dCodecAdapter$Factory")
                .asSubclass(MediaCodecAdapter.Factory.class)
                .getConstructor()
                .newInstance();
        // LINT.ThenChange(
        //     ../../../../../../../proguard-rules.txt,
        //
        // ../../../../../../../../decoder_av1/src/main/java/androidx/media3/decoder/av1/Dav1dCodecInfo.java:codec_name,
        //
        // ../../../../../../../../decoder_av1/src/main/java/androidx/media3/decoder/av1/Dav1dCodecAdapter.java:factory_constructor)
      } else {
        throw new IllegalArgumentException(
            "Unsupported extension decoder: " + configuration.codecInfo.name);
      }
    } catch (Exception e) {
      throw new IOException("Failed to create extension codec adapter", e);
    }
    return defaultFactory.createAdapter(configuration);
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
