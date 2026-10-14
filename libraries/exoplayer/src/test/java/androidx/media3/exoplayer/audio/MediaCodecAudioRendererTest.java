/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.END_OF_STREAM_ITEM;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.format;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.sample;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioFormat;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilities.Capabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeSampleStream;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.annotation.Config;

/** Unit tests for {@link MediaCodecAudioRenderer} */
@RunWith(AndroidJUnit4.class)
public class MediaCodecAudioRendererTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  /** Payload for Ogg ID Header Page in accordance with RFC 7845. */
  private static final byte[] OGG_OPUS_ID_HEADER_PAYLOAD =
      TestUtil.createByteArray(
          0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 0x1, 0x2, 0x38, 0x1, 0x80, 0xBB, 0x0, 0x0,
          0x0, 0x0, 0x0);

  private static final byte[] SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA =
      TestUtil.createByteArray(0x0, 0x0, 0x0, 0x1, 0x64, 0xA, 0x2C, 0x2A, 0x0, 0x0, 0x0, 0x0, 0x0);
  private static final byte[] SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA =
      ByteBuffer.allocate(13)
          .putInt(1)
          .put((byte) 0x64)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putLong(5_000_000L) // 240 samples at 48 kHz.
          .array();
  private static final Format AUDIO_AAC =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setPcmEncoding(C.ENCODING_PCM_16BIT)
          .setChannelCount(2)
          .setSampleRate(44100)
          .setEncoderDelay(100)
          .setEncoderPadding(150)
          .build();
  private static final Format AUDIO_OPUS =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_OPUS)
          .setChannelCount(2)
          .setSampleRate(48000)
          .setInitializationData(ImmutableList.of(OGG_OPUS_ID_HEADER_PAYLOAD))
          .build();
  private static final AudioOffloadSupport AUDIO_OFFLOAD_SUPPORTED_GAPLESS_NOT_SUPPORTED =
      new AudioOffloadSupport.Builder()
          .setIsFormatSupported(true)
          .setIsGaplessSupported(false)
          .build();
  private static final AudioOffloadSupport AUDIO_OFFLOAD_SUPPORTED_GAPLESS_SUPPORTED =
      new AudioOffloadSupport.Builder()
          .setIsFormatSupported(true)
          .setIsGaplessSupported(true)
          .build();
  private static final RendererConfiguration
      RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED =
          new RendererConfiguration(
              AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED, /* tunneling= */ false);

  private final AtomicReference<AudioSink.Listener> audioSinkListener = new AtomicReference<>();

  private MediaCodecAudioRenderer mediaCodecAudioRenderer;
  private DefaultMediaCodecAdapterFactory codecAdapterFactory;
  private MediaCodecSelector mediaCodecSelector;
  @Nullable private HandlerThread callbackThread;
  @Nullable private HandlerThread queueingThread;

  @Mock private AudioSink audioSink;
  @Mock private AudioRendererEventListener audioRendererEventListener;
  @Mock private RendererCapabilities.Listener rendererCapabilitiesListener;

  @Before
  public void setUp() throws Exception {
    // audioSink isEnded can always be true because the MediaCodecAudioRenderer isEnded =
    // super.isEnded && audioSink.isEnded.
    when(audioSink.isEnded()).thenReturn(true);
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(audioSink.supportsFormat(any()))
        .thenAnswer(
            invocation -> {
              Format format = invocation.getArgument(/* index= */ 0, Format.class);
              return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                  && format.pcmEncoding == C.ENCODING_PCM_16BIT;
            });
    when(audioSink.getFormatOffloadSupport(any()))
        .thenReturn(AudioOffloadSupport.DEFAULT_UNSUPPORTED);
    doAnswer(
            invocation -> {
              audioSinkListener.set(invocation.getArgument(0, AudioSink.Listener.class));
              return null;
            })
        .when(audioSink)
        .setListener(any(AudioSink.Listener.class));
    mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            Collections.singletonList(
                MediaCodecInfo.newInstance(
                    /* name= */ "name",
                    /* mimeType= */ mimeType,
                    /* codecMimeType= */ mimeType,
                    /* capabilities= */ null,
                    /* hardwareAccelerated= */ false,
                    /* softwareOnly= */ true,
                    /* vendor= */ false));

    Handler eventHandler = new Handler(Looper.getMainLooper());
    codecAdapterFactory =
        new DefaultMediaCodecAdapterFactory(
            ApplicationProvider.getApplicationContext(),
            () -> {
              callbackThread = new HandlerThread("MCARTest:MediaCodecAsyncAdapter");
              return callbackThread;
            },
            () -> {
              queueingThread = new HandlerThread("MCARTest:MediaCodecQueueingThread");
              return queueingThread;
            });
    mediaCodecAudioRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            codecAdapterFactory,
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            eventHandler,
            audioRendererEventListener,
            audioSink);
    mediaCodecAudioRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
  }

  @Test
  public void onOutputFormatChanged_withChannelMask_configuresAudioSinkWithChannelMask()
      throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(6)
            .setSampleRate(44100)
            .build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 6);
    mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_OUT_5POINT1);

    mediaCodecAudioRenderer.onOutputFormatChanged(format, mediaFormat);

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink, atLeastOnce()).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.channelMask)
        .isEqualTo(AudioFormat.CHANNEL_OUT_5POINT1);
  }

  @Test
  public void onOutputFormatChanged_withMismatchedChannelCountAndMask_dropsChannelMask()
      throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(6)
            .setSampleRate(44100)
            .build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
    mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
    // 5.1 mask has 6 bits, but count is 2.
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_OUT_5POINT1);

    mediaCodecAudioRenderer.onOutputFormatChanged(format, mediaFormat);

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink, atLeastOnce()).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.channelMask).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void render_configuresAudioSink_afterFormatChange() throws Exception {
    Format changedFormat = AUDIO_AAC.buildUpon().setSampleRate(48_000).setEncoderDelay(400).build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                format(changedFormat),
                oneByteSample(/* timeUs= */ 150, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC, changedFormat},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    mediaCodecAudioRenderer.render(/* positionUs= */ 250, SystemClock.elapsedRealtime() * 1000);
    mediaCodecAudioRenderer.setCurrentStreamFinal();

    int positionUs = 500;
    do {
      mediaCodecAudioRenderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 250;
    } while (!mediaCodecAudioRenderer.isEnded());

    ArgumentCaptor<AudioSink.AudioSinkConfig> configs =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink, times(2)).configure(configs.capture());
    assertThat(configs.getAllValues().stream().map(config -> config.format))
        .containsExactly(getAudioSinkFormat(AUDIO_AAC), getAudioSinkFormat(changedFormat))
        .inOrder();
  }

  @Test
  public void render_configuresAudioSink_afterGaplessFormatChange() throws Exception {
    Format changedFormat =
        AUDIO_AAC.buildUpon().setEncoderDelay(400).setEncoderPadding(232).build();

    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                format(changedFormat),
                oneByteSample(/* timeUs= */ 150, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC, changedFormat},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    mediaCodecAudioRenderer.render(/* positionUs= */ 250, SystemClock.elapsedRealtime() * 1000);
    mediaCodecAudioRenderer.setCurrentStreamFinal();

    int positionUs = 500;
    do {
      mediaCodecAudioRenderer.render(positionUs, SystemClock.elapsedRealtime() * 1000);
      positionUs += 250;
    } while (!mediaCodecAudioRenderer.isEnded());

    ArgumentCaptor<AudioSink.AudioSinkConfig> configs =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink, times(2)).configure(configs.capture());
    assertThat(configs.getAllValues().stream().map(config -> config.format))
        .containsExactly(getAudioSinkFormat(AUDIO_AAC), getAudioSinkFormat(changedFormat))
        .inOrder();
  }

  @Test
  public void render_throwsExoPlaybackExceptionJustOnce_whenSet() throws Exception {
    MediaCodecAudioRenderer exceptionThrowingRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            /* eventHandler= */ null,
            /* eventListener= */ null) {
          @Override
          protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat)
              throws ExoPlaybackException {
            super.onOutputFormatChanged(format, mediaFormat);
            if (!format.equals(AUDIO_AAC)) {
              setPendingPlaybackException(
                  ExoPlaybackException.createForRenderer(
                      new AudioSink.ConfigurationException("Test", format),
                      "rendererName",
                      /* rendererIndex= */ 0,
                      format,
                      C.FORMAT_HANDLED,
                      /* mediaPeriodId= */ null,
                      /* isRecoverable= */ false,
                      PlaybackException.ERROR_CODE_UNSPECIFIED));
            }
          }
        };

    Format changedFormat = AUDIO_AAC.buildUpon().setSampleRate(32_000).build();

    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    exceptionThrowingRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    exceptionThrowingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC, changedFormat},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    exceptionThrowingRenderer.start();
    exceptionThrowingRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    exceptionThrowingRenderer.render(/* positionUs= */ 250, SystemClock.elapsedRealtime() * 1000);

    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
    mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 32_000);
    // Simulating the exception being thrown when not traceable back to render.
    exceptionThrowingRenderer.onOutputFormatChanged(changedFormat, mediaFormat);

    assertThrows(
        ExoPlaybackException.class,
        () ->
            exceptionThrowingRenderer.render(
                /* positionUs= */ 500, SystemClock.elapsedRealtime() * 1000));

    // Doesn't throw an exception because it's cleared after being thrown in the previous call to
    // render.
    exceptionThrowingRenderer.render(/* positionUs= */ 750, SystemClock.elapsedRealtime() * 1000);
  }

  @Test
  public void render_callsAudioRendererEventListener_whenAudioSinkListenerOnAudioSinkErrorIsCalled()
      throws Exception {
    Format format = AUDIO_AAC;
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(END_OF_STREAM_ITEM));
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    final ArgumentCaptor<AudioSink.Listener> listenerCaptor =
        ArgumentCaptor.forClass(AudioSink.Listener.class);
    verify(audioSink, atLeastOnce()).setListener(listenerCaptor.capture());
    AudioSink.Listener audioSinkListener = listenerCaptor.getValue();

    Exception error =
        new AudioSink.WriteException(
            /* errorCode= */ 1, new Format.Builder().build(), /* isRecoverable= */ true);
    audioSinkListener.onAudioSinkError(error);

    shadowOf(Looper.getMainLooper()).idle();
    verify(audioRendererEventListener).onAudioSinkError(error);
  }

  @Test
  public void
      renderer_callsRendererCapabilitiesListener_whenAudioSinkListenerOnAudioCapabilitiesChangedIsCalled()
          throws Exception {
    Format format = AUDIO_AAC;
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ format,
            ImmutableList.of(END_OF_STREAM_ITEM));
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    final ArgumentCaptor<AudioSink.Listener> listenerCaptor =
        ArgumentCaptor.forClass(AudioSink.Listener.class);
    verify(audioSink, atLeastOnce()).setListener(listenerCaptor.capture());
    AudioSink.Listener audioSinkListener = listenerCaptor.getValue();
    mediaCodecAudioRenderer.getCapabilities().setListener(rendererCapabilitiesListener);

    audioSinkListener.onAudioCapabilitiesChanged();

    shadowOf(Looper.getMainLooper()).idle();
    verify(rendererCapabilitiesListener).onRendererCapabilitiesChanged(mediaCodecAudioRenderer);
  }

  @Test
  public void render_callsAudioSinkSetOutputStreamOffset_whenReplaceStream() throws Exception {
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 1_000_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_001_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId);

    mediaCodecAudioRenderer.start();
    while (!mediaCodecAudioRenderer.hasReadStreamToEnd()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    mediaCodecAudioRenderer.replaceStream(
        new Format[] {AUDIO_AAC},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    InOrder inOrderAudioSink = inOrder(audioSink);
    inOrderAudioSink.verify(audioSink).setOutputStreamOffsetUs(0);
    inOrderAudioSink.verify(audioSink).setOutputStreamOffsetUs(1_000_000);
  }

  @Test
  public void supportsFormat_withEac3JocMediaAndEac3Decoder_returnsTrue() throws Exception {
    Format mediaFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setCodecs(MimeTypes.CODEC_E_AC3_JOC)
            .build();
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            !mimeType.equals(MimeTypes.AUDIO_E_AC3)
                ? ImmutableList.of()
                : ImmutableList.of(
                    MediaCodecInfo.newInstance(
                        /* name= */ "eac3-codec",
                        /* mimeType= */ mimeType,
                        /* codecMimeType= */ mimeType,
                        /* capabilities= */ null,
                        /* hardwareAccelerated= */ false,
                        /* softwareOnly= */ true,
                        /* vendor= */ false));
    MediaCodecAudioRenderer renderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink);
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);

    @Capabilities int capabilities = renderer.supportsFormat(mediaFormat);

    assertThat(RendererCapabilities.getFormatSupport(capabilities)).isEqualTo(C.FORMAT_HANDLED);
  }

  @Test
  @Config(minSdk = 29) // Audio offload is only supported on API 29+.
  public void render_withOffloadConfiguredWithoutOffloadSupport_setsOffloadModeDisabled()
      throws Exception {
    when(audioSink.getFormatOffloadSupport(AUDIO_AAC))
        .thenReturn(AudioOffloadSupport.DEFAULT_UNSUPPORTED);
    Format format = AUDIO_AAC.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();

    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED);
  }

  @Test
  @Config(minSdk = 29) // Audio offload is only supported on API 29+.
  public void render_withOffloadConfiguredWithoutGaplessOffloadSupport_setsOffloadModeDisabled()
      throws Exception {
    when(audioSink.getFormatOffloadSupport(AUDIO_AAC))
        .thenReturn(AUDIO_OFFLOAD_SUPPORTED_GAPLESS_NOT_SUPPORTED);
    Format format = AUDIO_AAC.buildUpon().setEncoderDelay(100).setEncoderPadding(100).build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED,
        new Format[] {format},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();

    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED);
  }

  @Test
  @Config(minSdk = 29) // Audio offload is only supported on API 29+.
  public void render_replaceStreamWithOffloadSupport_setsOffloadModeEnabled() throws Exception {
    when(audioSink.getFormatOffloadSupport(any()))
        .thenReturn(AUDIO_OFFLOAD_SUPPORTED_GAPLESS_NOT_SUPPORTED);
    Format format1 = AUDIO_AAC.buildUpon().setEncoderDelay(100).setEncoderPadding(100).build();
    Format format2 = AUDIO_AAC.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build();
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format1,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format2,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 1_000_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_001_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId = new MediaSource.MediaPeriodId(new Object());
    mediaCodecAudioRenderer.enable(
        RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId);
    mediaCodecAudioRenderer.start();
    while (!mediaCodecAudioRenderer.hasReadStreamToEnd()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    mediaCodecAudioRenderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED);
    inOrder.verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED);
  }

  @Test
  @Config(minSdk = 29) // Audio offload is only supported on API 29+.
  public void render_replaceStreamWithoutGaplessSupport_setsOffloadModeDisabled() throws Exception {
    when(audioSink.getFormatOffloadSupport(any()))
        .thenReturn(AUDIO_OFFLOAD_SUPPORTED_GAPLESS_NOT_SUPPORTED);
    Format format1 = AUDIO_AAC.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build();
    Format format2 = AUDIO_AAC.buildUpon().setEncoderDelay(100).setEncoderPadding(100).build();
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format1,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            format2,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 1_000_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 1_001_000),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());

    mediaCodecAudioRenderer.enable(
        RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED,
        new Format[] {format1},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    mediaCodecAudioRenderer.start();
    while (!mediaCodecAudioRenderer.hasReadStreamToEnd()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED);

    mediaCodecAudioRenderer.replaceStream(
        new Format[] {format2},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId2);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    verify(audioSink).setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED);
  }

  @Test
  @Config(minSdk = 30)
  public void render_offloadedOpusWithPadding_callsSetOffloadDelayPadding() throws Exception {
    when(audioSink.getFormatOffloadSupport(any()))
        .thenReturn(AUDIO_OFFLOAD_SUPPORTED_GAPLESS_SUPPORTED);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                sample(
                    /* timeUs= */ 150,
                    C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RENDERER_CONFIGURATION_OFFLOAD_ENABLED_GAPLESS_REQUIRED,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    verify(audioSink).setOffloadDelayPadding(/* delayInFrames= */ 312, /* paddingInFrames= */ 133);
  }

  @Test
  public void render_opusWithPaddingBeforeOutputFormat_callsSetEncoderPaddingAfterConfigure()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(133);
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
  }

  @Test
  public void render_opusWithNearestNanosecondDiscardPadding_roundsToNearestSample()
      throws Exception {
    // 100 samples at 48 kHz is 2,083,333.33... ns, which Matroska muxers store as 2,083,333 ns.
    byte[] nearestNanosecondPaddingSample =
        ByteBuffer.allocate(13)
            .putInt(1)
            .put((byte) 0x64)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(2_083_333L)
            .array();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    nearestNanosecondPaddingSample),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    verify(audioSink).setEncoderPadding(100);
  }

  @Test
  public void render_opusWithPaddingBeforeOutputFormatThenPositionReset_clearsDynamicPaddingOnSeek()
      throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 5; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    // Seek back and feed a sample without supplemental data.
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.resetPosition(
        /* positionUs= */ 50, /* sampleStreamIsResetToKeyFrame= */ true);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 50, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).flush();
    inOrder.verify(audioSink, never()).setEncoderPadding(anyInt());
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
  }

  @Test
  public void render_opusWithPaddingAfterOutputFormat_callsSetEncoderPadding() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    // Render first sample so codec is initialized and onOutputFormatChanged is triggered.
    for (int i = 0; i < 5; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    // Append sample with supplemental data and end of stream.
    fakeSampleStream.append(
        ImmutableList.of(
            sample(
                /* timeUs= */ 50,
                C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    verify(audioSink).setEncoderPadding(133);
  }

  @Test
  public void render_opusWithPaddingAfterPositionReset_callsSetEncoderPadding() throws Exception {
    codecAdapterFactory.forceDisableAsynchronous();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 5; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    // Simulate a seek (which flushes the codec without emitting a new output format).
    fakeSampleStream.append(
        ImmutableList.of(
            sample(
                /* timeUs= */ 50,
                C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.resetPosition(
        /* positionUs= */ 50, /* sampleStreamIsResetToKeyFrame= */ true);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 50, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    verify(audioSink).setEncoderPadding(133);
  }

  @Test
  public void render_opusStreamTransition_appliesPaddingToBothStreams() throws Exception {
    Format secondOpusFormat = AUDIO_OPUS.buildUpon().setId("2").build();
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ secondOpusFormat,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 1_000_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    mediaCodecAudioRenderer.start();
    while (!mediaCodecAudioRenderer.hasReadStreamToEnd()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    mediaCodecAudioRenderer.replaceStream(
        new Format[] {secondOpusFormat},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId2);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(240);
  }

  @Test
  public void
      render_opusFormatChangeWithDrainBeforeOutputFormatChanged_appliesPaddingToRespectiveFormats()
          throws Exception {
    Format secondOpusFormat = AUDIO_OPUS.buildUpon().setId("2").build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                format(secondOpusFormat),
                sample(
                    /* timeUs= */ 50_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo(AUDIO_OPUS.id);
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo("2");
    inOrder.verify(audioSink).setEncoderPadding(240);
    assertThat(configCaptor.getAllValues().get(0).format.encoderPadding).isEqualTo(0);
    assertThat(configCaptor.getAllValues().get(1).format.encoderPadding).isEqualTo(0);
  }

  @Test
  public void render_opusStreamTransitionWithCodecReuse_appliesPaddingToBothStreams()
      throws Exception {
    MediaCodecAudioRenderer codecReusingRenderer = createCodecReusingRenderer();
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 1_000_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);
    MediaSource.MediaPeriodId mediaPeriodId1 = new MediaSource.MediaPeriodId(new Object());
    MediaSource.MediaPeriodId mediaPeriodId2 = new MediaSource.MediaPeriodId(new Object());

    codecReusingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        mediaPeriodId1);
    codecReusingRenderer.start();
    // Render until the first stream's output format has configured the AudioSink.
    for (int i = 0; i < 5; i++) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    codecReusingRenderer.replaceStream(
        new Format[] {AUDIO_OPUS},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        mediaPeriodId2);
    codecReusingRenderer.setCurrentStreamFinal();
    while (!codecReusingRenderer.isEnded()) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    inOrder.verify(audioSink).setEncoderPadding(240);
    assertThat(configCaptor.getAllValues().get(0).format.encoderPadding).isEqualTo(0);
    assertThat(configCaptor.getAllValues().get(1).format.encoderPadding).isEqualTo(0);
  }

  @Test
  public void
      render_opusFormatChangeWithCodecReuseBeforeOutputFormatChanged_appliesPaddingToBothFormats()
          throws Exception {
    MediaCodecAudioRenderer codecReusingRenderer = createCodecReusingRenderer();
    Format secondOpusFormat = AUDIO_OPUS.buildUpon().setId("2").build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                format(secondOpusFormat),
                sample(
                    /* timeUs= */ 50_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    codecReusingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    codecReusingRenderer.start();
    codecReusingRenderer.setCurrentStreamFinal();
    while (!codecReusingRenderer.isEnded()) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo(AUDIO_OPUS.id);
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo("2");
    inOrder.verify(audioSink).setEncoderPadding(240);
  }

  @Test
  public void
      render_opusSameFormatReferenceWithCodecReuse_appliesSecondPaddingAfterSecondConfigure()
          throws Exception {
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(false).thenReturn(true);
    MediaCodecAudioRenderer codecReusingRenderer = createCodecReusingRenderer();
    FakeSampleStream fakeSampleStream1 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream1.writeData(/* startPositionUs= */ 0);
    FakeSampleStream fakeSampleStream2 =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 1_000_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream2.writeData(/* startPositionUs= */ 0);

    codecReusingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream1,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    codecReusingRenderer.start();
    // First render feeds the first stream's sample and reaches the end of the first stream.
    codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    codecReusingRenderer.replaceStream(
        new Format[] {AUDIO_OPUS},
        fakeSampleStream2,
        /* startPositionUs= */ 1_000_000,
        /* offsetUs= */ 1_000_000,
        new MediaSource.MediaPeriodId(new Object()));
    codecReusingRenderer.setCurrentStreamFinal();
    // Subsequent renders drain the first stream's output format (while handleBuffer returns false
    // on the first attempt) and feed the second stream's identical AUDIO_OPUS format and sample
    // before the second stream's output format is processed.
    while (!codecReusingRenderer.isEnded()) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(any());
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(any());
    inOrder.verify(audioSink).setEncoderPadding(240);
  }

  @Test
  public void render_opusWithMalformedSupplementalData_doesNotSetEncoderPadding() throws Exception {
    byte[] shortSupplementalDataSample =
        ByteBuffer.allocate(9).putInt(1).put((byte) 0x64).putInt(1234).array();
    byte[] negativeSupplementalDataSample =
        ByteBuffer.allocate(13)
            .putInt(1)
            .put((byte) 0x64)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(-5_000_000L)
            .array();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    shortSupplementalDataSample),
                sample(
                    /* timeUs= */ 50,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    negativeSupplementalDataSample),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    verify(audioSink, never()).setEncoderPadding(anyInt());
  }

  @Test
  public void
      render_opusWithPaddingBeforeOutputFormatThenPositionReset_clearsPendingEncoderPadding()
          throws Exception {
    // Force synchronous mode because Robolectric's async ShadowMediaCodec emits an output format
    // change during start() before making input buffers available.
    codecAdapterFactory.forceDisableAsynchronous();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    // Feed the input buffer (with supplemental data) before any output format is drained.
    mediaCodecAudioRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    fakeSampleStream.append(
        ImmutableList.of(
            oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME), END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    fakeSampleStream.seekToUs(/* positionUs= */ 50, /* allowTimeBeyondBuffer= */ true);
    mediaCodecAudioRenderer.resetPosition(
        /* positionUs= */ 50, /* sampleStreamIsResetToKeyFrame= */ true);
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    while (!mediaCodecAudioRenderer.isEnded()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 50, /* elapsedRealtimeUs= */ 0);
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    verify(audioSink, never()).setEncoderPadding(anyInt());
  }

  @Test
  public void
      onOutputFormatChanged_repeatedForSameFormat_preservesDynamicPaddingAndQueuedNextFormatPadding()
          throws Exception {
    codecAdapterFactory.forceDisableAsynchronous();
    AtomicReference<Format> lastOutputFormat = new AtomicReference<>();
    MediaCodecAudioRenderer codecReusingRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            codecAdapterFactory,
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink) {
          @Override
          protected DecoderReuseEvaluation canReuseCodec(
              MediaCodecInfo codecInfo,
              Format oldFormat,
              Format newFormat,
              boolean isAdaptiveFormatChange) {
            return new DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
                /* discardReasons= */ 0);
          }

          @Override
          protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat)
              throws ExoPlaybackException {
            lastOutputFormat.set(format);
            super.onOutputFormatChanged(format, mediaFormat);
          }
        };
    codecReusingRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    Format secondOpusFormat = AUDIO_OPUS.buildUpon().setId("2").build();
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                format(secondOpusFormat),
                sample(
                    /* timeUs= */ 50_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setString(MediaFormat.KEY_MIME, MimeTypes.AUDIO_RAW);
    mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, AUDIO_OPUS.channelCount);
    mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, AUDIO_OPUS.sampleRate);
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(false, true);

    codecReusingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    codecReusingRenderer.start();
    // Feed both samples and process the first format's output format change, pausing on the first
    // output buffer so the second format's output buffer is not yet dequeued, then simulate a
    // mid-stream unsolicited INFO_OUTPUT_FORMAT_CHANGED for the first format.
    codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    codecReusingRenderer.onOutputFormatChanged(lastOutputFormat.get(), mediaFormat);
    codecReusingRenderer.setCurrentStreamFinal();
    while (!codecReusingRenderer.isEnded()) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo(AUDIO_OPUS.id);
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo(AUDIO_OPUS.id);
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    inOrder.verify(audioSink).setEncoderPadding(133);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo("2");
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    inOrder.verify(audioSink).setEncoderPadding(240);
  }

  @Test
  public void
      render_supersededFormatAtSameTimestampAndReusedFormatInstance_appliesMatchingFormatPadding()
          throws Exception {
    MediaCodecAudioRenderer codecReusingRenderer = createCodecReusingRenderer();
    Format secondOpusFormat = AUDIO_OPUS.buildUpon().setId("2").build();
    // First format has 133 padding samples at timeUs=0, immediately superseded by secondOpusFormat
    // (with no padding) also at timeUs=0 (which clears the first format in TimedValueQueue),
    // followed by switching back to the original AUDIO_OPUS instance at timeUs=50_000 (with 240
    // padding samples).
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(
                sample(
                    /* timeUs= */ 0,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
                format(secondOpusFormat),
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                format(AUDIO_OPUS),
                sample(
                    /* timeUs= */ 50_000,
                    C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                    SAMPLE_ONE_BYTE_WITH_240_PADDING_SAMPLES_SUPPLEMENTAL_DATA),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    codecReusingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    codecReusingRenderer.start();
    codecReusingRenderer.setCurrentStreamFinal();
    while (!codecReusingRenderer.isEnded()) {
      codecReusingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    ArgumentCaptor<AudioSink.AudioSinkConfig> configCaptor =
        ArgumentCaptor.forClass(AudioSink.AudioSinkConfig.class);
    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo("2");
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    inOrder.verify(audioSink).configure(configCaptor.capture());
    assertThat(configCaptor.getValue().format.id).isEqualTo(AUDIO_OPUS.id);
    assertThat(configCaptor.getValue().format.encoderPadding).isEqualTo(0);
    inOrder.verify(audioSink).setEncoderPadding(240);
    verify(audioSink, times(1)).setEncoderPadding(anyInt());
  }

  @Test
  public void resetCodecStateForRelease_thenReinitWithoutInputFormatChange_appliesEncoderPadding()
      throws Exception {
    codecAdapterFactory.forceDisableAsynchronous();
    class CodecReleasingRenderer extends MediaCodecAudioRenderer {
      CodecReleasingRenderer() {
        super(
            ApplicationProvider.getApplicationContext(),
            codecAdapterFactory,
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink);
      }

      @Override
      public void releaseCodec() {
        super.releaseCodec();
      }
    }
    CodecReleasingRenderer codecReleasingRenderer = new CodecReleasingRenderer();
    codecReleasingRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_OPUS,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);

    codecReleasingRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_OPUS},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ true,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    codecReleasingRenderer.start();
    for (int i = 0; i < 5; i++) {
      codecReleasingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }
    codecReleasingRenderer.releaseCodec();
    fakeSampleStream.append(
        ImmutableList.of(
            sample(
                /* timeUs= */ 50_000,
                C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
                SAMPLE_ONE_BYTE_WITH_SUPPLEMENTAL_DATA),
            END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    codecReleasingRenderer.setCurrentStreamFinal();
    while (!codecReleasingRenderer.isEnded()) {
      codecReleasingRenderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    }

    InOrder inOrder = inOrder(audioSink);
    inOrder.verify(audioSink, times(2)).configure(any());
    inOrder.verify(audioSink).setEncoderPadding(133);
  }

  @Test
  public void getDurationToProgressUs_usingAudioTrackBufferDurationUs_returnsCalculatedDuration()
      throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(50_000L);
  }

  @Test
  public void
      getDurationToProgressUs_usingAudioTrackBufferDurationUsAndDoublePlaybackSpeed_returnsCalculatedDuration()
          throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    PlaybackParameters playbackParametersWithDoubleSpeed =
        new PlaybackParameters(/* speed= */ 2.0f);
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(playbackParametersWithDoubleSpeed);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(25_000L);
  }

  @Test
  public void getDurationToProgressUs_beforeAudioPositionAdvances_returnsDefaultDuration()
      throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void
      getDurationToProgressUs_withAudioTrackBufferDurationUsUnsupported_returnsDefaultDuration()
          throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(C.TIME_UNSET);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void getDurationToProgressUs_writtenFullBufferDurationWhenNotStarted_returnsIdleDuration()
      throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(1_000_000L);
  }

  @Test
  public void
      getDurationToProgressUs_writtenLessThanBufferDurationWhenStarted_returnsDefaultDuration()
          throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being accepted, simulating a sink that isn't full yet.
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void
      getDurationToProgressUs_writtenLessThanBufferDurationWhenNotStarted_returnsDefaultDuration()
          throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being accepted, simulating a sink that isn't full yet.
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void
      getDurationToProgressUs_writtenLessThanBufferDurationAfterRenderToEndOfStreamAndNotStarted_returnsIdleDuration()
          throws Exception {
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being accepted, simulating a sink that isn't full yet.
    when(audioSink.handleBuffer(any(), anyLong(), anyInt())).thenReturn(true);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(1_000_000L);
  }

  @Test
  public void
      getDurationToProgressUs_writtenLessThanBufferDurationAfterRenderToEndOfStream_returnsCalculatedDuration()
          throws Exception {
    AtomicBoolean hasCalledRenderToEndOfStream = new AtomicBoolean();
    mediaCodecAudioRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            new DefaultMediaCodecAdapterFactory(
                ApplicationProvider.getApplicationContext(),
                () -> {
                  callbackThread = new HandlerThread("MCARTest:MediaCodecAsyncAdapter");
                  return callbackThread;
                },
                () -> {
                  queueingThread = new HandlerThread("MCARTest:MediaCodecQueueingThread");
                  return queueingThread;
                }),
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink) {
          @Override
          protected void renderToEndOfStream() throws ExoPlaybackException {
            super.renderToEndOfStream();
            hasCalledRenderToEndOfStream.set(true);
          }
        };
    mediaCodecAudioRenderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    when(audioSink.getAudioTrackBufferSizeUs()).thenReturn(100_000L);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 250_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    while (!hasCalledRenderToEndOfStream.get()) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 200_000L, SystemClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(25_000L);
  }

  @Test
  public void getDurationToProgressUs_afterResetPosition_returnsDefaultDuration() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ true);
    mediaCodecAudioRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink);
    mediaCodecAudioRenderer.init(/* index= */ 0, PlayerId.UNSET, fakeClock);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    // Simulate a seek through resetPosition which should flush the audio sink.
    mediaCodecAudioRenderer.stop();
    mediaCodecAudioRenderer.resetPosition(
        /* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);
    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, fakeClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void getDurationToProgressUs_afterDisable_returnsDefaultDuration() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ true);
    mediaCodecAudioRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink);
    mediaCodecAudioRenderer.init(/* index= */ 0, PlayerId.UNSET, fakeClock);
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 150_000, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 50_000), anyInt()))
        .thenAnswer(
            invocation -> {
              audioSinkListener.get().onPositionAdvancing(0);
              return true;
            });
    // Represents audio sink buffers being full when trying to write 150_000 us sample.
    when(audioSink.handleBuffer(
            any(), longThat(presentationTimeUs -> presentationTimeUs == 150_000), anyInt()))
        .thenReturn(false);
    when(audioSink.getPlaybackParameters()).thenReturn(PlaybackParameters.DEFAULT);
    when(audioSink.hasPendingData()).thenReturn(true);
    mediaCodecAudioRenderer.start();
    for (int i = 0; i < 10; i++) {
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
      maybeIdleAsynchronousMediaCodecAdapterThreads();
    }

    // Simulate a track reselection forcing a disable and causing a flush of the audio sink.
    mediaCodecAudioRenderer.stop();
    mediaCodecAudioRenderer.disable();
    long durationToProgressUs =
        mediaCodecAudioRenderer.getDurationToProgressUs(
            /* positionUs= */ 0, fakeClock.elapsedRealtime() * 1000);

    assertThat(durationToProgressUs).isEqualTo(10_000L);
  }

  @Test
  public void isReady_returnsTrueOnceAudioSinkHasPendingData() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            /* initialFormat= */ AUDIO_AAC,
            ImmutableList.of(
                oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 50, C.BUFFER_FLAG_KEY_FRAME),
                oneByteSample(/* timeUs= */ 100, C.BUFFER_FLAG_KEY_FRAME),
                END_OF_STREAM_ITEM));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));

    mediaCodecAudioRenderer.start();
    mediaCodecAudioRenderer.setCurrentStreamFinal();
    when(audioSink.hasPendingData()).thenReturn(false);

    boolean isReadyBeforeFirstRender = mediaCodecAudioRenderer.isReady();
    for (int i = 0; i < 3; i++) {
      // Handle some buffers.
      mediaCodecAudioRenderer.render(/* positionUs= */ 0, SystemClock.elapsedRealtime() * 1000);
    }
    boolean isReadyAfterDecoding = mediaCodecAudioRenderer.isReady();
    when(audioSink.hasPendingData()).thenReturn(true);
    boolean isReadyAfterPendingData = mediaCodecAudioRenderer.isReady();

    assertThat(isReadyBeforeFirstRender).isFalse();
    assertThat(isReadyAfterDecoding).isFalse();
    assertThat(isReadyAfterPendingData).isTrue();
  }

  @Test
  public void onCodecParametersChanged_dispatchesToAudioRendererEventListener() {
    CodecParameters codecParameters =
        new CodecParameters.Builder().setInteger("test-key", 456).build();

    mediaCodecAudioRenderer.onCodecParametersChanged(codecParameters);

    shadowOf(Looper.getMainLooper()).idle();
    verify(audioRendererEventListener).onAudioCodecParametersChanged(codecParameters);
  }

  @Test
  public void isReady_withoutPreviousReadyState_returnsFalse() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ false);
    setupRendererWithFakeClock(fakeClock);
    enableAndStartRenderer();

    when(audioSink.hasPendingData()).thenReturn(false);

    assertThat(mediaCodecAudioRenderer.isReady()).isFalse();
  }

  @Test
  public void isReady_duringTransientUnderrun_returnsTrueForGracePeriodDuration() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ false);
    setupRendererWithFakeClock(fakeClock);
    enableAndStartRenderer();

    // 1. Transition to ready state
    when(audioSink.hasPendingData()).thenReturn(true);
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // 2. Transition to dry (underrun) state
    when(audioSink.hasPendingData()).thenReturn(false);

    // First call to isReady in underrun triggers the grace period
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // Advance clock, still within grace period (< 100ms)
    fakeClock.advanceTime(99);
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();
  }

  @Test
  public void isReady_persistentUnderrun_returnsFalseAfterGracePeriodDuration() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ false);
    setupRendererWithFakeClock(fakeClock);
    enableAndStartRenderer();

    // 1. Transition to ready state
    when(audioSink.hasPendingData()).thenReturn(true);
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // 2. Transition to dry (underrun) state
    when(audioSink.hasPendingData()).thenReturn(false);

    // First call triggers grace period
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // Advance clock past the grace period (>= 100ms)
    fakeClock.advanceTime(100);
    assertThat(mediaCodecAudioRenderer.isReady()).isFalse();
  }

  @Test
  public void isReady_onPositionReset_resetsGracePeriodState() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ false);
    setupRendererWithFakeClock(fakeClock);
    enableAndStartRenderer();

    // 1. Transition to ready state
    when(audioSink.hasPendingData()).thenReturn(true);
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // 2. Perform position reset
    mediaCodecAudioRenderer.resetPosition(
        /* positionUs= */ 0, /* sampleStreamIsResetToKeyFrame= */ true);

    // 3. Transition to dry state
    when(audioSink.hasPendingData()).thenReturn(false);

    // Should return false immediately (no grace period) because grace period state was reset
    assertThat(mediaCodecAudioRenderer.isReady()).isFalse();
  }

  @Test
  public void isReady_onStopped_resetsGracePeriodState() throws Exception {
    FakeClock fakeClock = new FakeClock(/* initialTimeMs= */ 100, /* isAutoAdvancing= */ false);
    setupRendererWithFakeClock(fakeClock);
    enableAndStartRenderer();

    // 1. Transition to ready state
    when(audioSink.hasPendingData()).thenReturn(true);
    assertThat(mediaCodecAudioRenderer.isReady()).isTrue();

    // 2. Stop and start renderer
    mediaCodecAudioRenderer.stop();
    mediaCodecAudioRenderer.start();

    // 3. Transition to dry state
    when(audioSink.hasPendingData()).thenReturn(false);

    // Should return false immediately (no grace period) because grace period state was reset
    assertThat(mediaCodecAudioRenderer.isReady()).isFalse();
  }

  private void setupRendererWithFakeClock(FakeClock fakeClock) {
    mediaCodecAudioRenderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            /* eventHandler= */ new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink);
    mediaCodecAudioRenderer.init(/* index= */ 0, PlayerId.UNSET, fakeClock);
  }

  private void enableAndStartRenderer() throws Exception {
    FakeSampleStream fakeSampleStream =
        new FakeSampleStream(
            new DefaultAllocator(/* trimOnReset= */ true, /* individualAllocationSize= */ 1024),
            /* mediaSourceEventDispatcher= */ null,
            DrmSessionManager.DRM_UNSUPPORTED,
            new DrmSessionEventListener.EventDispatcher(),
            AUDIO_AAC,
            ImmutableList.of(oneByteSample(/* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME)));
    fakeSampleStream.writeData(/* startPositionUs= */ 0);
    mediaCodecAudioRenderer.enable(
        RendererConfiguration.DEFAULT,
        new Format[] {AUDIO_AAC},
        fakeSampleStream,
        /* positionUs= */ 0,
        /* joining= */ false,
        /* mayRenderStartOfStream= */ false,
        /* startPositionUs= */ 0,
        /* offsetUs= */ 0,
        new MediaSource.MediaPeriodId(new Object()));
    mediaCodecAudioRenderer.start();
  }

  private MediaCodecAudioRenderer createCodecReusingRenderer() {
    codecAdapterFactory.forceDisableAsynchronous();
    MediaCodecAudioRenderer renderer =
        new MediaCodecAudioRenderer(
            ApplicationProvider.getApplicationContext(),
            codecAdapterFactory,
            mediaCodecSelector,
            /* enableDecoderFallback= */ false,
            new Handler(Looper.getMainLooper()),
            audioRendererEventListener,
            audioSink) {
          @Override
          protected DecoderReuseEvaluation canReuseCodec(
              MediaCodecInfo codecInfo,
              Format oldFormat,
              Format newFormat,
              boolean isAdaptiveFormatChange) {
            return new DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
                /* discardReasons= */ 0);
          }
        };
    renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
    return renderer;
  }

  private void maybeIdleAsynchronousMediaCodecAdapterThreads() {
    if (queueingThread != null) {
      shadowOf(queueingThread.getLooper()).idle();
    }
    if (callbackThread != null) {
      shadowOf(callbackThread.getLooper()).idle();
    }
  }

  private static Format getAudioSinkFormat(Format inputFormat) {
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(inputFormat.channelCount)
        .setSampleRate(inputFormat.sampleRate)
        .setEncoderDelay(inputFormat.encoderDelay)
        .setEncoderPadding(inputFormat.encoderPadding)
        .build();
  }
}
