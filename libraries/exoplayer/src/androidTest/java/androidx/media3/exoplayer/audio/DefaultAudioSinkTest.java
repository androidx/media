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
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation unit tests for {@link DefaultAudioSink}. */
@RunWith(AndroidJUnit4.class)
public class DefaultAudioSinkTest {

  @Test
  @SdkSuppress(minSdkVersion = 24) // TODO: b/399130330 - Debug why this fails on API 23.
  public void audioTrackExceedsSharedMemory_retriesUntilOngoingReleasesAreDone() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              // Create audio sinks in parallel until we exceed the device's shared audio memory.
              ArrayList<DefaultAudioSink> audioSinks = new ArrayList<>();
              while (true) {
                DefaultAudioSink audioSink = new DefaultAudioSink.Builder(context).build();
                audioSinks.add(audioSink);
                try {
                  configureAudioSinkAndFeedData(audioSink);
                } catch (Exception e) {
                  // Expected to happen once we reached the shared audio memory limit of the device.
                  break;
                }
              }

              // Trigger release of one sink and immediately try the failed sink again. This should
              // now succeed even if the sink is released asynchronously.
              audioSinks.get(0).flush();
              audioSinks.get(0).release();
              try {
                configureAudioSinkAndFeedData(getLast(audioSinks));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }

              // Clean-up
              for (int i = 1; i < audioSinks.size(); i++) {
                audioSinks.get(i).flush();
                audioSinks.get(i).release();
              }
            });
  }

  @Test
  @SdkSuppress(minSdkVersion = 24) // The test depends on AudioTrack#getUnderrunCount() (API 24+).
  public void audioTrackUnderruns_callsOnUnderrun() throws InterruptedException {
    AtomicInteger underrunCount = new AtomicInteger();
    DefaultAudioSink sink =
        new DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build();
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

    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                // Set buffer size of ~1.1ms. The tiny size helps cause an underrun.
                sink.configure(format, /* specifiedBufferSize= */ 100, /* outputChannels= */ null);

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

  private void configureAudioSinkAndFeedData(DefaultAudioSink audioSink) throws Exception {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44_100)
            .build();
    audioSink.configure(format, /* specifiedBufferSize= */ 2_000_000, /* outputChannels= */ null);
    audioSink.play();
    ByteBuffer buffer = ByteBuffer.allocateDirect(8000).order(ByteOrder.nativeOrder());
    boolean handledBuffer = false;
    while (!handledBuffer) {
      handledBuffer =
          audioSink.handleBuffer(
              buffer, /* presentationTimeUs= */ 0, /* encodedAccessUnitCount= */ 1);
    }
  }
}
