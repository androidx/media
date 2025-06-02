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
package androidx.media3.exoplayer.drm;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.MimeTypes.VIDEO_AV1;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for DRM playback. */
@RunWith(AndroidJUnit4.class)
public final class DrmPlaybackTest {

  /** The license response needed to play the {@code drm/sample_fragmented_clearkey.mp4} file. */
  // NOTE: Changing this response *should* make the test fail, but it seems the clearkey CDM
  // implementation is quite robust. This means an 'invalid' response means it just incorrectly
  // decrypts the content, resulting in invalid data fed to the decoder and no video shown on the
  // screen (but no error thrown that's detectable by this test).
  private static final String CLEARKEY_RESPONSE =
      "{\"keys\":"
          + "[{"
          + "\"kty\":\"oct\","
          + "\"k\":\"Y8tfcYTdS2iaXF_xHuajKA\","
          + "\"kid\":\"zX65_4jzTK6wYYWwACTkwg\""
          + "}],"
          + "\"type\":\"temporary\"}";

  private final Context context = getInstrumentation().getContext();

  private MockWebServer mockWebServer;
  private MediaItem.DrmConfiguration drmConfiguration;

  @Before
  public void setUpDrmConfiguration() throws IOException {
    mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(CLEARKEY_RESPONSE));
    mockWebServer.start();
    drmConfiguration =
        new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
            .setLicenseUri(mockWebServer.url("license").toString())
            .build();
  }

  @Test
  public void clearkeyPlayback() throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/drm/sample_fragmented_clearkey.mp4")
            .setDrmConfiguration(drmConfiguration)
            .build();
    AtomicReference<ExoPlayer> player = new AtomicReference<>();
    ConditionVariable playbackComplete = new ConditionVariable();
    AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new ExoPlayer.Builder(context).build());
              player
                  .get()
                  .addListener(
                      new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(@Player.State int playbackState) {
                          if (playbackState == Player.STATE_ENDED) {
                            playbackComplete.open();
                          }
                        }

                        @Override
                        public void onPlayerError(PlaybackException error) {
                          playbackException.set(error);
                          playbackComplete.open();
                        }
                      });
              player.get().setMediaItem(mediaItem);
              player.get().prepare();
              player.get().play();
            });

    playbackComplete.block();
    getInstrumentation().runOnMainSync(() -> player.get().release());
    getInstrumentation().waitForIdleSync();
    assertThat(playbackException.get()).isNull();
  }

  @Test
  public void clearkeyPlayback_withLateThresholdToDropDecoderInput_dropsInputBuffers()
      throws Exception {
    // The API 21 emulator doesn't have a secure decoder. Due to b/18678462 MediaCodecUtil pretends
    // that there is a secure decoder so we must only run this test on API 21 - i.e. we cannot
    // assumeTrue() on getDecoderInfos.
    assumeTrue(SDK_INT > 21);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/drm/sample_fragmented_clearkey.mp4")
            .setDrmConfiguration(drmConfiguration)
            .build();
    AtomicReference<ExoPlayer> player = new AtomicReference<>();
    ConditionVariable playbackComplete = new ConditionVariable();
    AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
    AtomicReference<DecoderCounters> decoderCountersAtomicReference = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(
                  new ExoPlayer.Builder(
                          context,
                          new DefaultRenderersFactory(context)
                              .experimentalSetLateThresholdToDropDecoderInputUs(-100_000_000L),
                          new DefaultMediaSourceFactory(context)
                              .experimentalSetCodecsToParseWithinGopSampleDependencies(
                                  C.VIDEO_CODEC_FLAG_H264))
                      .build());
              player
                  .get()
                  .addListener(
                      new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(@Player.State int playbackState) {
                          if (playbackState == Player.STATE_ENDED) {
                            decoderCountersAtomicReference.set(
                                player.get().getVideoDecoderCounters());
                            playbackComplete.open();
                          }
                        }

                        @Override
                        public void onPlayerError(PlaybackException error) {
                          playbackException.set(error);
                          playbackComplete.open();
                        }
                      });
              player.get().setMediaItem(mediaItem);
              player.get().prepare();
              player.get().play();
            });

    playbackComplete.block();
    getInstrumentation().runOnMainSync(() -> player.get().release());
    getInstrumentation().waitForIdleSync();

    assertThat(playbackException.get()).isNull();
    // Which input buffers are dropped first depends on the number of MediaCodec buffer slots.
    // This means the asserts cannot be isEqualTo.
    assertThat(decoderCountersAtomicReference.get().droppedInputBufferCount).isAtLeast(1);
  }

  @Test
  public void clearkeyPlayback_parseAv1SampleDependencies_skipsOnlyFullFrames() throws Exception {
    // Only run this test if any AV1 decoder is present. Accept non-secure decoders that likely
    // produce corrupted visual output, as this test asserts only on decoder counters.
    assumeFalse(
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(
                VIDEO_AV1,
                /* requiresSecureDecoder= */ false,
                /* requiresTunnelingDecoder= */ false)
            .isEmpty());
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri("asset:///media/drm/sample_av1c_fragmented_clearkey.mp4")
            .setDrmConfiguration(drmConfiguration)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setAllowUnseekableMedia(true)
                    .setStartPositionMs(200)
                    .build())
            .build();
    AtomicReference<ExoPlayer> player = new AtomicReference<>();
    ConditionVariable playbackComplete = new ConditionVariable();
    AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
    AtomicReference<DecoderCounters> decoderCountersAtomicReference = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(
                  new ExoPlayer.Builder(
                          context,
                          new DefaultRenderersFactory(context)
                              .experimentalSetParseAv1SampleDependencies(true))
                      .build());
              player
                  .get()
                  .addListener(
                      new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(@Player.State int playbackState) {
                          if (playbackState == Player.STATE_ENDED) {
                            decoderCountersAtomicReference.set(
                                player.get().getVideoDecoderCounters());
                            playbackComplete.open();
                          }
                        }

                        @Override
                        public void onPlayerError(PlaybackException error) {
                          playbackException.set(error);
                          playbackComplete.open();
                        }
                      });
              player.get().setMediaItem(mediaItem);
              player.get().prepare();
              player.get().play();
            });

    playbackComplete.block();
    getInstrumentation().runOnMainSync(() -> player.get().release());
    getInstrumentation().waitForIdleSync();

    assertThat(playbackException.get()).isNull();
    assertThat(decoderCountersAtomicReference.get().skippedInputBufferCount).isEqualTo(3);
  }
}
