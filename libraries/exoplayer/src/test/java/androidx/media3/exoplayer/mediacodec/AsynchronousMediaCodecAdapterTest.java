/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.annotation.Config.ALL_SDKS;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.Sets;
import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for {@link AsynchronousMediaCodecAdapter}. */
// TODO: b/507008072 - Remove this when it's the default for the whole module
@Config(sdk = ALL_SDKS)
@RunWith(AndroidJUnit4.class)
public class AsynchronousMediaCodecAdapterTest {
  private AsynchronousMediaCodecAdapter adapter;
  private ThreadSupplier queueingThreadSupplier;
  private ThreadSupplier callbackThreadSupplier;
  private MediaCodec.BufferInfo bufferInfo;

  @Before
  public void setUp() throws Exception {
    MediaCodecAdapter.Configuration configuration = createAdapterConfiguration();
    queueingThreadSupplier = new ThreadSupplier("TestMediaCodecQueueing");
    callbackThreadSupplier = new ThreadSupplier("TestMediaCodecCallback");
    adapter =
        new AsynchronousMediaCodecAdapter.Factory(callbackThreadSupplier, queueingThreadSupplier)
            .createAdapter(configuration);
    bufferInfo = new MediaCodec.BufferInfo();
    // After starting the MediaCodec, the ShadowMediaCodec offers input buffer 0. We advance the
    // looper to make sure any messages have been propagated to the adapter.
    idleThreads(callbackThreadSupplier.createdThreads);
  }

  @After
  public void tearDown() {
    adapter.release();
  }

  @Test
  public void dequeueInputBufferIndex_withInputBuffer_returnsInputBuffer() {
    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(0);
  }

  @Test
  public void dequeueInputBufferIndex_withMediaCodecError_throwsException() throws Exception {
    // Set an error directly on the adapter (not through the looper).
    adapter.onError(createCodecException());

    assertThrows(IllegalStateException.class, () -> adapter.dequeueInputBufferIndex());
  }

  @Test
  public void
      dequeueInputBufferIndex_withAsynchronousEnqueuerAndPendingQueueingError_throwsException()
          throws Exception {
    createAdapterWithSynchronousEnqueuerDisabled();

    // Force MediaCodec to throw an error by attempting to queue input buffer -1.
    adapter.queueInputBuffer(
        /* index= */ -1,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 0,
        /* flags= */ 0);
    idleThreads(queueingThreadSupplier.createdThreads);

    assertThrows(IllegalStateException.class, () -> adapter.dequeueInputBufferIndex());
  }

  @Test
  public void dequeueInputBufferIndex_afterShutdown_returnsTryAgainLater() {
    adapter.release();

    assertThat(adapter.dequeueInputBufferIndex()).isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueOutputBufferIndex_withoutOutputBuffer_returnsTryAgainLater() {
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    // Assert that output buffer is available.
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void dequeueOutputBufferIndex_withOutputBuffer_returnsOutputBuffer() {
    int index = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(index, 0, 0, 0, 0);
    // Progress the queueuing looper first so the asynchronous enqueuer submits the input buffer,
    // the ShadowMediaCodec processes the input buffer and produces an output buffer. Then, progress
    // the callback looper so that the available output buffer callback is handled and the output
    // buffer reaches the adapter.
    idleThreads(queueingThreadSupplier.createdThreads);
    idleThreads(callbackThreadSupplier.createdThreads);

    // The ShadowMediaCodec will first offer an output format and then the output buffer.
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    // Assert it's the ShadowMediaCodec's output format
    assertThat(adapter.getOutputFormat().getByteBuffer("csd-0")).isNotNull();
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo)).isEqualTo(index);
  }

  @Test
  public void dequeueOutputBufferIndex_withMediaCodecError_throwsException() throws Exception {
    // Set an error directly on the adapter.
    adapter.onError(createCodecException());

    assertThrows(IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));
  }

  @Test
  public void
      dequeueOutputBufferIndex_withAsynchronousEnqueuerAndPendingQueueingError_throwsException()
          throws Exception {
    createAdapterWithSynchronousEnqueuerDisabled();

    // Force MediaCodec to throw an error by attempting to queue input buffer -1.
    adapter.queueInputBuffer(
        /* index= */ -1,
        /* offset= */ 0,
        /* size= */ 0,
        /* presentationTimeUs= */ 0,
        /* flags= */ 0);
    idleThreads(queueingThreadSupplier.createdThreads);

    assertThrows(IllegalStateException.class, () -> adapter.dequeueOutputBufferIndex(bufferInfo));
  }

  @Test
  public void dequeueOutputBufferIndex_afterShutdown_returnsTryAgainLater() {
    int index = adapter.dequeueInputBufferIndex();
    adapter.queueInputBuffer(index, 0, 0, 0, 0);
    // Progress the looper so that the ShadowMediaCodec processes the input buffer.
    idleThreads(callbackThreadSupplier.createdThreads);
    adapter.release();

    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void getOutputFormat_withoutFormatReceived_throwsException() {
    assertThrows(IllegalStateException.class, () -> adapter.getOutputFormat());
  }

  @Test
  public void getOutputFormat_withMultipleFormats_returnsCorrectFormat() {
    // Add another format on the adapter.
    adapter.onOutputFormatChanged(createMediaFormat("format2"));

    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    // The first format is the ShadowMediaCodec's output format.
    assertThat(adapter.getOutputFormat().getByteBuffer("csd-0")).isNotNull();
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED);
    // The 2nd format is the format we enqueued 'manually' above.
    assertThat(adapter.getOutputFormat().getString("name")).isEqualTo("format2");
    assertThat(adapter.dequeueOutputBufferIndex(bufferInfo))
        .isEqualTo(MediaCodec.INFO_TRY_AGAIN_LATER);
  }

  @Test
  public void getOutputFormat_afterFlush_returnsPreviousFormat() {
    adapter.dequeueOutputBufferIndex(bufferInfo);
    MediaFormat outputFormat = adapter.getOutputFormat();
    // Flush the adapter and progress the looper so that flush is completed.
    adapter.flush();
    idleThreads(callbackThreadSupplier.createdThreads);

    assertThat(adapter.getOutputFormat()).isEqualTo(outputFormat);
  }

  private static MediaCodecInfo createMediaCodecInfo(String name, String mimeType) {
    return MediaCodecInfo.newInstance(
        name,
        mimeType,
        /* codecMimeType= */ mimeType,
        /* capabilities= */ null,
        /* hardwareAccelerated= */ false,
        /* softwareOnly= */ false,
        /* vendor= */ false,
        /* forceDisableAdaptive= */ false,
        /* forceSecure= */ false);
  }

  private static MediaCodecAdapter.Configuration createAdapterConfiguration() {
    MediaCodecInfo codecInfo = createMediaCodecInfo("aac", "audio/aac");
    return MediaCodecAdapter.Configuration.createForAudioDecoding(
        codecInfo,
        createMediaFormat("format"),
        new Format.Builder().build(),
        /* crypto= */ null,
        /* loudnessCodecController= */ null);
  }

  private static MediaFormat createMediaFormat(String name) {
    MediaFormat format = new MediaFormat();
    format.setString("name", name);
    return format;
  }

  /** Reflectively create a {@link MediaCodec.CodecException}. */
  private static MediaCodec.CodecException createCodecException() throws Exception {
    Constructor<MediaCodec.CodecException> constructor =
        MediaCodec.CodecException.class.getDeclaredConstructor(
            Integer.TYPE, Integer.TYPE, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        /* errorCode */ 0, /* actionCode */ 0, /* detailMessage */ "error from codec");
  }

  private static void idleThreads(Set<HandlerThread> threads) {
    for (HandlerThread thread : threads) {
      if (thread.isAlive()) {
        @Nullable Looper looper = thread.getLooper();
        if (looper != null) {
          ShadowLooper shadowLooper = shadowOf(looper);
          try {
            shadowLooper.idle();
          } catch (IllegalStateException e) {
            // Ignorable, may happen if Looper is already quitting.
          }
        }
      }
    }
  }

  private void createAdapterWithSynchronousEnqueuerDisabled() throws Exception {
    // Release adapter configured in setup.
    adapter.release();
    AsynchronousMediaCodecAdapter.Factory factory =
        new AsynchronousMediaCodecAdapter.Factory(callbackThreadSupplier, queueingThreadSupplier);
    factory.setAsyncCryptoFlagEnabled(false);
    MediaCodecAdapter.Configuration configuration = createAdapterConfiguration();
    adapter = factory.createAdapter(configuration);
    // After starting the MediaCodec, the ShadowMediaCodec offers input buffer 0. We advance the
    // looper to make sure any messages have been propagated to the adapter.
    idleThreads(callbackThreadSupplier.createdThreads);
  }

  private static final class ThreadSupplier implements Supplier<HandlerThread> {

    private final String label;
    private final Set<HandlerThread> createdThreads;
    private final AtomicInteger counter;

    private ThreadSupplier(String label) {
      this.label = label;
      this.createdThreads = Sets.newConcurrentHashSet();
      this.counter = new AtomicInteger();
    }

    @Override
    public HandlerThread get() {
      HandlerThread thread = new HandlerThread(label + ":" + counter.getAndIncrement());
      createdThreads.add(thread);
      return thread;
    }
  }
}
