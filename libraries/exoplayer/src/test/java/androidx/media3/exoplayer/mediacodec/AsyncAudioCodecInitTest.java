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

import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * {@link MediaCodecRenderer#experimentalSetAsyncCodecInitializationEnabled}: an audio decoder is
 * created off the calling thread, the renderer keeps its samples meanwhile and finishes the decoder
 * on a later render; a failed creation falls back to the next decoder; a seek keeps the decoder
 * being created; a decoder still being created (or created and not yet used) when the renderer is
 * disabled, or when bypass becomes possible, is released once it exists; a playback speed, codec
 * parameters or a priority set while the decoder is being created are applied once it exists;
 * renderers that did not opt in are unchanged.
 */
@RunWith(AndroidJUnit4.class)
public class AsyncAudioCodecInitTest {
  private static final String CODEC_INIT_THREAD = "ExoPlayer:CodecInit";
  private static final String BROKEN = "broken";
  private static final Format AAC =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setChannelCount(2)
          .setSampleRate(44100)
          .build();

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private AudioSink audioSink;
  @Mock private AudioRendererEventListener listener;

  // Creations on the background thread wait for createMayFinish; on any other thread they do not.
  private final CountDownLatch createEntered = new CountDownLatch(1);
  private final CountDownLatch createMayFinish = new CountDownLatch(1);
  private final CountDownLatch createExited = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);
  private final List<String> createThreads = new CopyOnWriteArrayList<>();
  private final AtomicInteger releases = new AtomicInteger();
  private final List<Bundle> setParameters = new CopyOnWriteArrayList<>();
  private volatile boolean sinkSupportsAac;
  private MediaCodecAudioRenderer renderer;

  @Before
  public void setUp() throws Exception {
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    // PCM only: an AAC input needs a decoder (no bypass).
    when(audioSink.supportsFormat(any()))
        .thenAnswer(
            invocation -> {
              Format format = invocation.getArgument(/* index= */ 0, Format.class);
              return (MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                      && format.pcmEncoding == C.ENCODING_PCM_16BIT)
                  || (MimeTypes.AUDIO_AAC.equals(format.sampleMimeType) && sinkSupportsAac);
            });
    when(audioSink.getFormatOffloadSupport(any()))
        .thenReturn(AudioOffloadSupport.DEFAULT_UNSUPPORTED);
    renderer = newRenderer(/* enableDecoderFallback= */ false, "name");
  }

  @After
  public void tearDown() {
    createMayFinish.countDown();
  }

  @Test
  public void on_createsOffTheCallingThread_keepsSamples_finishesOnALaterRender() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);

    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(createThreads).containsExactly(CODEC_INIT_THREAD);
    assertThat(renderer.getCodec()).isNull();
    render(renderer); // Still being created: nothing finished, nothing skipped.
    assertThat(renderer.getCodec()).isNull();
    assertThat(renderer.decoderCounters.skippedInputBufferCount).isEqualTo(0);

    createMayFinish.countDown();
    renderUntilCodec(renderer);
    assertThat(renderer.getCodec()).isNotNull();
    assertThat(renderer.decoderCounters.decoderInitCount).isEqualTo(1);
    assertThat(renderer.decoderCounters.skippedInputBufferCount).isEqualTo(0);
    renderer.stop();
    renderer.disable();
    renderer.reset();
    assertThat(releases.get()).isEqualTo(1);
  }

  @Test
  public void off_createsOnTheCallingThread_whileAnotherRendererIsOn() throws Exception {
    // Another renderer's opt-in does not change this one.
    MediaCodecAudioRenderer other = newRenderer(/* enableDecoderFallback= */ false, "other");
    other.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(other);
    render(other);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    enableAndStart(renderer);
    render(renderer);

    assertThat(createThreads)
        .containsExactly(CODEC_INIT_THREAD, Thread.currentThread().getName())
        .inOrder();
    assertThat(renderer.getCodec()).isNotNull();
    assertThat(renderer.decoderCounters.decoderInitCount).isEqualTo(1);
    assertThat(other.getCodec()).isNull();
    assertThat(setParameters).isEmpty();
  }

  @Test
  public void on_creationFails_fallsBackToTheNextDecoder() throws Exception {
    MediaCodecAudioRenderer fallback =
        newRenderer(/* enableDecoderFallback= */ true, BROKEN, "good");
    fallback.experimentalSetAsyncCodecInitializationEnabled(true);
    createMayFinish.countDown();
    enableAndStart(fallback);

    renderUntilCodec(fallback);

    assertThat(fallback.getCodec()).isNotNull();
    assertThat(fallback.getCodecInfo().name).isEqualTo("good");
    assertThat(createThreads).containsExactly(CODEC_INIT_THREAD, CODEC_INIT_THREAD);
    assertThat(fallback.decoderCounters.decoderInitCount).isEqualTo(1);
    assertThat(fallback.decoderCounters.skippedInputBufferCount).isEqualTo(0);
  }

  @Test
  public void on_creationFailsWithoutFallback_throwsADecoderInitializationError() throws Exception {
    MediaCodecAudioRenderer broken = newRenderer(/* enableDecoderFallback= */ false, BROKEN);
    broken.experimentalSetAsyncCodecInitializationEnabled(true);
    createMayFinish.countDown();
    enableAndStart(broken);

    ExoPlaybackException error =
        assertThrows(ExoPlaybackException.class, () -> renderUntilCodec(broken));

    assertThat(error.getRendererException())
        .isInstanceOf(MediaCodecRenderer.DecoderInitializationException.class);
    assertThat(createThreads).containsExactly(CODEC_INIT_THREAD);
    assertThat(broken.getCodec()).isNull();
  }

  @Test
  public void on_seekWhileCreating_keepsTheDecoderAndItsSamples() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.resetPosition(/* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);
    createMayFinish.countDown();
    renderUntilCodec(renderer);

    assertThat(renderer.getCodec()).isNotNull();
    assertThat(createThreads).containsExactly(CODEC_INIT_THREAD);
    assertThat(releases.get()).isEqualTo(0);
    assertThat(renderer.decoderCounters.decoderInitCount).isEqualTo(1);
    assertThat(renderer.decoderCounters.skippedInputBufferCount).isEqualTo(0);
  }

  @Test
  public void on_disabledWhileCreating_releasesTheDecoderOnceCreated() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.stop();
    renderer.disable();
    createMayFinish.countDown();

    assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(releases.get()).isEqualTo(1);
    assertThat(renderer.getCodec()).isNull();
    assertThat(renderer.decoderCounters.decoderInitCount).isEqualTo(0);
  }

  @Test
  public void on_disabledAfterCreationBeforeItsUse_releasesTheDecoder() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    createMayFinish.countDown();
    assertThat(createExited.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.stop();
    renderer.disable();
    renderer.reset();

    assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(releases.get()).isEqualTo(1);
    assertThat(renderer.getCodec()).isNull();
    assertThat(renderer.decoderCounters.decoderInitCount).isEqualTo(0);
  }

  @Test
  public void on_speedChangedWhileCreating_setsTheOperatingRateOnceCreated() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.setPlaybackSpeed(/* currentPlaybackSpeed= */ 2f, /* targetPlaybackSpeed= */ 2f);
    createMayFinish.countDown();
    renderUntilCodec(renderer);

    assertThat(floatParameters(MediaFormat.KEY_OPERATING_RATE)).containsExactly(88_200f);
  }

  @Test
  public void on_codecParametersSetWhileCreating_setsThemOnceCreated() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.handleMessage(
        Renderer.MSG_SET_CODEC_PARAMETERS,
        new CodecParameters.Builder().setInteger("vendor.test.value", 7).build());
    createMayFinish.countDown();
    renderUntilCodec(renderer);

    assertThat(intParameters("vendor.test.value")).containsExactly(7);
  }

  @Test
  public void on_priorityChangedWhileCreating_setsTheImportanceOnceCreated() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    renderer.handleMessage(Renderer.MSG_SET_PRIORITY, C.PRIORITY_PLAYBACK - 1);
    createMayFinish.countDown();
    renderUntilCodec(renderer);

    assertThat(intParameters(MediaFormat.KEY_IMPORTANCE)).containsExactly(1 - C.PRIORITY_PLAYBACK);
  }

  @Test
  public void off_importanceCodecParameter_isNotOverridden() throws Exception {
    // An app's own importance (codec parameters) applies on top of the priority in the format.
    renderer.handleMessage(
        Renderer.MSG_SET_CODEC_PARAMETERS,
        new CodecParameters.Builder().setInteger(MediaFormat.KEY_IMPORTANCE, 5).build());
    enableAndStart(renderer);
    render(renderer);

    assertThat(renderer.getCodec()).isNotNull();
    assertThat(setParameters).isEmpty();
  }

  @Test
  public void on_bypassPossibleWhileCreating_releasesTheDecoderOnceCreated() throws Exception {
    renderer.experimentalSetAsyncCodecInitializationEnabled(true);
    enableAndStart(renderer);
    render(renderer);
    assertThat(createEntered.await(10, TimeUnit.SECONDS)).isTrue();

    sinkSupportsAac = true; // For example, an audio output that now plays AAC directly.
    render(renderer);
    createMayFinish.countDown();

    assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(releases.get()).isEqualTo(1);
    assertThat(renderer.getCodec()).isNull();
  }

  private List<Float> floatParameters(String key) {
    List<Float> values = new ArrayList<>();
    for (Bundle bundle : setParameters) {
      if (bundle.containsKey(key)) {
        values.add(bundle.getFloat(key));
      }
    }
    return values;
  }

  private List<Integer> intParameters(String key) {
    List<Integer> values = new ArrayList<>();
    for (Bundle bundle : setParameters) {
      if (bundle.containsKey(key)) {
        values.add(bundle.getInt(key));
      }
    }
    return values;
  }

  private MediaCodecAudioRenderer newRenderer(boolean enableDecoderFallback, String... names) {
    MediaCodecAdapter.Factory real =
        new DefaultMediaCodecAdapterFactory(ApplicationProvider.getApplicationContext());
    MediaCodecAdapter.Factory gated =
        configuration -> {
          createThreads.add(Thread.currentThread().getName());
          try {
            if (Thread.currentThread().getName().equals(CODEC_INIT_THREAD)) {
              createEntered.countDown();
              createMayFinish.await(10, TimeUnit.SECONDS);
            }
            if (configuration.codecInfo.name.equals(BROKEN)) {
              throw new IOException("creation failed");
            }
            return new ForwardingMediaCodecAdapter(real.createAdapter(configuration)) {
              @Override
              public void setParameters(Bundle params) {
                setParameters.add(params);
                super.setParameters(params);
              }

              @Override
              public void release() {
                releases.incrementAndGet();
                released.countDown();
                super.release();
              }
            };
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          } finally {
            createExited.countDown();
          }
        };
    ImmutableList.Builder<MediaCodecInfo> infos = ImmutableList.builder();
    for (String name : names) {
      infos.add(
          MediaCodecInfo.newInstance(
              name,
              MimeTypes.AUDIO_AAC,
              MimeTypes.AUDIO_AAC,
              /* capabilities= */ null,
              /* hardwareAccelerated= */ false,
              /* softwareOnly= */ true,
              /* vendor= */ false,
              /* forceDisableAdaptive= */ false,
              /* forceSecure= */ false));
    }
    ImmutableList<MediaCodecInfo> codecInfos = infos.build();
    MediaCodecAudioRenderer created =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            gated,
            (mimeType, secure, tunneling) -> codecInfos,
            enableDecoderFallback,
            new Handler(Looper.getMainLooper()),
            listener,
            audioSink);
    created.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    return created;
  }

  private static void enableAndStart(MediaCodecAudioRenderer renderer) throws Exception {
    FakeSampleStream stream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 20_000),
                END_OF_STREAM_ITEM));
    stream.writeData(/* startPositionUs= */ 0);
    renderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AAC},
        stream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    renderer.start();
  }

  private static void renderUntilCodec(MediaCodecAudioRenderer renderer) throws Exception {
    // Wall-clock deadline: Robolectric's SystemClock does not advance while the test sleeps.
    long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    render(renderer);
    while (renderer.getCodec() == null && System.nanoTime() < deadlineNs) {
      Thread.sleep(5);
      render(renderer);
    }
  }

  private static void render(MediaCodecAudioRenderer renderer) throws Exception {
    // A position past the samples: without a codec the stock path would skip them.
    renderer.render(/* positionUs= */ 100_000, SystemClock.elapsedRealtime() * 1000);
  }
}
