/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.CryptoInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultMediaCodecAdapterFactory}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultMediaCodecAdapterFactoryTest {

  @Test
  public void setExtensionAdapterFactory_withEmptyCodecName_throwsIllegalArgumentException() {
    Context context = ApplicationProvider.getApplicationContext();
    DefaultMediaCodecAdapterFactory factory = new DefaultMediaCodecAdapterFactory(context);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            factory.setExtensionAdapterFactory(
                /* codecName= */ "", configuration -> new NoOpMediaCodecAdapter()));
  }

  @Test
  public void createAdapter_withUnknownExtensionCodec_throwsIOException() {
    Context context = ApplicationProvider.getApplicationContext();
    DefaultMediaCodecAdapterFactory factory = new DefaultMediaCodecAdapterFactory(context);
    MediaCodecInfo unknownExtensionCodecInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.unknown.decoder", MimeTypes.VIDEO_AV1);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaCodecAdapter.Configuration configuration =
        MediaCodecAdapter.Configuration.createForVideoDecoding(
            unknownExtensionCodecInfo,
            new MediaFormat(),
            format,
            /* surface= */ null,
            /* crypto= */ null);

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Unsupported extension decoder: c2.media3.unknown.decoder");
  }

  @Test
  public void createAdapter_withRegisteredExtensionAdapterFactory_delegatesToCustomFactory()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo customExtensionCodecInfo =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.custom.decoder", MimeTypes.VIDEO_AV1);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaCodecAdapter.Configuration configuration =
        MediaCodecAdapter.Configuration.createForVideoDecoding(
            customExtensionCodecInfo,
            new MediaFormat(),
            format,
            /* surface= */ null,
            /* crypto= */ null);
    MediaCodecAdapter expectedAdapter = new NoOpMediaCodecAdapter();
    AtomicReference<MediaCodecAdapter.Configuration> receivedConfig = new AtomicReference<>();
    DefaultMediaCodecAdapterFactory factory =
        new DefaultMediaCodecAdapterFactory(context)
            .setExtensionAdapterFactory(
                "c2.media3.custom.decoder",
                config -> {
                  receivedConfig.set(config);
                  return expectedAdapter;
                });

    MediaCodecAdapter result = factory.createAdapter(configuration);

    assertThat(result).isSameInstanceAs(expectedAdapter);
    assertThat(receivedConfig.get()).isSameInstanceAs(configuration);
  }

  @Test
  public void createAdapter_withDav1dWithoutExtensionBundled_throwsIOException() {
    Context context = ApplicationProvider.getApplicationContext();
    DefaultMediaCodecAdapterFactory factory = new DefaultMediaCodecAdapterFactory(context);
    MediaCodecInfo dav1dCodecInfo =
        ExtensionCodecInfo.newInstance("c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaCodecAdapter.Configuration configuration =
        MediaCodecAdapter.Configuration.createForVideoDecoding(
            dav1dCodecInfo, new MediaFormat(), format, /* surface= */ null, /* crypto= */ null);

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(ClassNotFoundException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains("androidx.media3.decoder.av1.Dav1dCodecAdapter$Factory");
  }

  @Test
  public void createAdapter_withRegisteredExtensionAdapterFactoryForDav1d_overridesBuiltInLookup()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo dav1dCodecInfo =
        ExtensionCodecInfo.newInstance("c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaCodecAdapter.Configuration configuration =
        MediaCodecAdapter.Configuration.createForVideoDecoding(
            dav1dCodecInfo, new MediaFormat(), format, /* surface= */ null, /* crypto= */ null);
    MediaCodecAdapter expectedAdapter = new NoOpMediaCodecAdapter();
    AtomicReference<MediaCodecAdapter.Configuration> receivedConfig = new AtomicReference<>();
    DefaultMediaCodecAdapterFactory factory =
        new DefaultMediaCodecAdapterFactory(context)
            .setExtensionAdapterFactory(
                "c2.media3.av1.dav1d.decoder",
                config -> {
                  receivedConfig.set(config);
                  return expectedAdapter;
                });

    MediaCodecAdapter result = factory.createAdapter(configuration);

    assertThat(result).isSameInstanceAs(expectedAdapter);
    assertThat(receivedConfig.get()).isSameInstanceAs(configuration);
  }

  private static final class NoOpMediaCodecAdapter implements MediaCodecAdapter {
    @Override
    public int dequeueInputBufferIndex() {
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }

    @Override
    public MediaFormat getOutputFormat() {
      return new MediaFormat();
    }

    @Override
    public @Nullable ByteBuffer getInputBuffer(int index) {
      return null;
    }

    @Override
    public @Nullable ByteBuffer getOutputBuffer(int index) {
      return null;
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {}

    @Override
    public void queueSecureInputBuffer(
        int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {}

    @Override
    public void releaseOutputBuffer(int index, boolean render) {}

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {}

    @Override
    public void flush() {}

    @Override
    public void release() {}

    @Override
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {}

    @Override
    public void setOutputSurface(Surface surface) {}

    @Override
    public void detachOutputSurface() {}

    @Override
    public void setParameters(Bundle params) {}

    @Override
    public void setVideoScalingMode(int scalingMode) {}

    @Override
    public boolean needsReconfiguration() {
      return false;
    }

    @Override
    public PersistableBundle getMetrics() {
      return new PersistableBundle();
    }

    @Override
    public void subscribeToVendorParameters(List<String> names) {}

    @Override
    public void unsubscribeFromVendorParameters(List<String> names) {}
  }
}
