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
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static androidx.media3.common.util.Util.usToMs;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.util.Util;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation unit tests for {@link DefaultAudioSink}. */
@RunWith(AndroidJUnit4.class)
public class DefaultAudioSinkTest {

  @Test
  @SdkSuppress(minSdkVersion = 24) // TODO: b/399130330 - Debug why this fails on API 23.
  public void
      audioTrackExceedsSharedMemory_playbackThreadStillAlive_retriesUntilOngoingReleasesAreDone()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Handler mainHandler = Util.createHandlerForCurrentOrMainLooper();
    // Create audio sinks in parallel until we exceed the device's shared audio memory.
    ArrayList<DefaultAudioSink> audioSinks = new ArrayList<>();
    while (true) {
      runOnHandlerSync(
          mainHandler,
          () -> {
            AudioOutputProvider defaultProvider =
                new AudioTrackAudioOutputProvider.Builder(context).build();
            // Use large enough buffer size to quickly reach the device limit while still being able
            // to create multiple sinks.
            DefaultAudioSink audioSink =
                new DefaultAudioSink.Builder(context)
                    .setAudioOutputProvider(
                        new ForwardingAudioOutputProvider(defaultProvider) {
                          @Override
                          public OutputConfig getOutputConfig(FormatConfig formatConfig)
                              throws ConfigurationException {
                            return super.getOutputConfig(formatConfig)
                                .buildUpon()
                                .setBufferSize(2_000_000)
                                .build();
                          }
                        })
                    .build();
            audioSinks.add(audioSink);
          });
      try {
        configureAudioSinkAndFeedDataOnHandler(mainHandler, getLast(audioSinks));
      } catch (Exception e) {
        // Expected to happen once we reached the shared audio memory limit of the device.
        break;
      }
    }
    // Trigger release of one sink and immediately try the failed sink again. This should
    // now succeed even if the sink is released asynchronously.
    runOnHandlerSync(
        mainHandler,
        () -> {
          audioSinks.get(0).flush();
          audioSinks.get(0).release();
        });
    try {
      configureAudioSinkAndFeedDataOnHandler(mainHandler, getLast(audioSinks));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    // Clean-up
    runOnHandlerSync(
        mainHandler,
        () -> {
          for (int i = 1; i < audioSinks.size(); i++) {
            audioSinks.get(i).flush();
            audioSinks.get(i).release();
          }
        });
  }

  @Test
  @SdkSuppress(minSdkVersion = 24)
  public void
      audioTrackExceedsSharedMemory_playbackThreadNotAlive_retriesUntilOngoingReleasesAreDone()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Handler mainHandler = Util.createHandlerForCurrentOrMainLooper();
    // Create audio sinks in parallel until we exceed the device's shared audio memory.
    ArrayList<DefaultAudioSink> mainThreadSinks = new ArrayList<>();
    while (true) {
      AtomicReference<DefaultAudioSink> sinkRef = new AtomicReference<>();
      runOnHandlerSync(
          mainHandler,
          () -> {
            AudioOutputProvider defaultProvider =
                new AudioTrackAudioOutputProvider.Builder(context).build();
            DefaultAudioSink audioSink =
                new DefaultAudioSink.Builder(context)
                    .setAudioOutputProvider(
                        new ForwardingAudioOutputProvider(defaultProvider) {
                          @Override
                          public OutputConfig getOutputConfig(FormatConfig formatConfig)
                              throws ConfigurationException {
                            return super.getOutputConfig(formatConfig)
                                .buildUpon()
                                .setBufferSize(2_000_000)
                                .build();
                          }
                        })
                    .build();
            sinkRef.set(audioSink);
            mainThreadSinks.add(audioSink);
          });
      try {
        // Configure the audio sinks on the main thread.
        configureAudioSinkAndFeedDataOnHandler(mainHandler, sinkRef.get());
      } catch (Exception e) {
        // Expected to happen once we reached the shared audio memory limit.
        break;
      }
    }

    // Free one audio sink on the main thread so we have room to create one sink on the background
    // playback thread.
    runOnHandlerSync(
        mainHandler,
        () -> {
          mainThreadSinks.get(0).flush();
          mainThreadSinks.get(0).release();
        });
    mainThreadSinks.remove(0);

    HandlerThread playbackThread = new HandlerThread("PlaybackThread");
    playbackThread.start();
    Handler playbackHandler = new Handler(playbackThread.getLooper());
    // Create and configure one audio sink on the background playback thread.
    AtomicReference<DefaultAudioSink> bgSinkRef = new AtomicReference<>();
    runOnHandlerSync(
        playbackHandler,
        () -> {
          AudioOutputProvider defaultProvider =
              new AudioTrackAudioOutputProvider.Builder(context).build();
          DefaultAudioSink audioSink =
              new DefaultAudioSink.Builder(context)
                  .setAudioOutputProvider(
                      new ForwardingAudioOutputProvider(defaultProvider) {
                        @Override
                        public OutputConfig getOutputConfig(FormatConfig formatConfig)
                            throws ConfigurationException {
                          return super.getOutputConfig(formatConfig)
                              .buildUpon()
                              .setBufferSize(2_000_000)
                              .build();
                        }
                      })
                  .build();
          bgSinkRef.set(audioSink);
        });
    configureAudioSinkAndFeedDataOnHandler(playbackHandler, bgSinkRef.get());

    // Trigger release of the background sink and quit the thread. This simulates the player being
    // destroyed.
    runOnHandlerSync(
        playbackHandler,
        () -> {
          bgSinkRef.get().flush();
          bgSinkRef.get().release();
        });
    playbackThread.quit();

    // Immediately configure an audio sink again on the main thread.
    try {
      configureAudioSinkAndFeedDataOnHandler(mainHandler, getLast(mainThreadSinks));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    // Clean-up all main thread sinks safely on the main thread.
    runOnHandlerSync(
        mainHandler,
        () -> {
          for (DefaultAudioSink sink : mainThreadSinks) {
            sink.flush();
            sink.release();
          }
        });
  }

  @Test
  @SdkSuppress(minSdkVersion = 24) // The test depends on AudioTrack#getUnderrunCount() (API 24+).
  public void audioTrackUnderruns_callsOnUnderrun() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    AtomicInteger underrunCount = new AtomicInteger();
    // Set buffer size of ~1.1ms. The tiny size helps cause an underrun.
    AudioOutputProvider defaultProvider =
        new AudioTrackAudioOutputProvider.Builder(context).build();
    DefaultAudioSink sink =
        new DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(
                new ForwardingAudioOutputProvider(defaultProvider) {
                  @Override
                  public OutputConfig getOutputConfig(FormatConfig formatConfig)
                      throws ConfigurationException {
                    return super.getOutputConfig(formatConfig)
                        .buildUpon()
                        .setBufferSize(100)
                        .build();
                  }
                })
            .build();
    sink.setListener(
        new AudioSink.Listener() {
          @Override
          public void onPositionDiscontinuity() {}

          @Override
          public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
            underrunCount.addAndGet(1);
          }

          @Override
          public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {}
        });
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(1)
            .setSampleRate(44_100)
            .build();
    // Create a big buffer to prime the sink's AudioTrack (~113ms).
    long bigBufferDurationUs =
        sampleCountToDurationUs(/* sampleCount= */ 5000, /* sampleRate= */ 44_100);
    ByteBuffer bigBuffer = ByteBuffer.allocateDirect(5000 * 2).order(ByteOrder.nativeOrder());

    // Create a buffer smaller than sink buffer size to eventually cause an underrun (~567us).
    long smallBufferDurationUs =
        sampleCountToDurationUs(/* sampleCount= */ 25, /* sampleRate= */ 44_100);
    ByteBuffer smallBuffer = ByteBuffer.allocateDirect(50).order(ByteOrder.nativeOrder());

    runOnHandlerSync(
        Util.createHandlerForCurrentOrMainLooper(),
        () -> {
          try {
            sink.configure(new AudioSink.AudioSinkConfig.Builder(format).build());

            // Prime AudioTrack with buffer larger than start threshold. Otherwise, AudioTrack
            // won't start playing.
            sink.handleBuffer(
                bigBuffer, /* presentationTimeUs= */ 0, /* encodedAccessUnitCount= */ 1);
            sink.play();
            // Sleep until AudioTrack starts running out of queued samples.
            Thread.sleep(usToMs(bigBufferDurationUs));
            for (int i = 0; i < 5; i++) {
              smallBuffer.rewind();
              // Queue small buffer so that sink buffer is never filled up.
              sink.handleBuffer(
                  smallBuffer,
                  /* presentationTimeUs= */ bigBufferDurationUs + smallBufferDurationUs * i,
                  /* encodedAccessUnitCount= */ 1);
              // Add additional latency so loop can never fill up sink buffer quickly enough.
              Thread.sleep(20);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertThat(underrunCount.get()).isGreaterThan(0);
  }

  private void configureAudioSinkAndFeedDataOnHandler(Handler handler, DefaultAudioSink audioSink)
      throws Exception {
    ByteBuffer buffer = ByteBuffer.allocateDirect(8000).order(ByteOrder.nativeOrder());
    runOnHandlerSync(
        handler,
        () -> {
          Format format =
              new Format.Builder()
                  .setSampleMimeType(MimeTypes.AUDIO_RAW)
                  .setPcmEncoding(C.ENCODING_PCM_16BIT)
                  .setChannelCount(2)
                  .setSampleRate(44_100)
                  .build();
          audioSink.configure(new AudioSink.AudioSinkConfig.Builder(format).build());
          audioSink.play();
        });
    AtomicBoolean handledBuffer = new AtomicBoolean();
    while (!handledBuffer.get()) {
      runOnHandlerSync(
          handler,
          () ->
              handledBuffer.set(
                  audioSink.handleBuffer(
                      buffer, /* presentationTimeUs= */ 0, /* encodedAccessUnitCount= */ 1)));
    }
  }

  private static void runOnHandlerSync(Handler handler, ThrowingRunnable<?> runnable)
      throws Exception {
    AtomicReference<Exception> exceptionOnHandler = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    handler.post(
        () -> {
          try {
            runnable.run();
          } catch (Exception e) {
            exceptionOnHandler.set(e);
          } finally {
            latch.countDown();
          }
        });
    latch.await();
    if (exceptionOnHandler.get() != null) {
      throw exceptionOnHandler.get();
    }
  }
}
