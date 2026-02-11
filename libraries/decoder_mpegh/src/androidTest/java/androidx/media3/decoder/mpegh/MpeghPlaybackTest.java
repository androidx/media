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
package androidx.media3.decoder.mpegh;

import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_COMMAND;
import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_CONFIG;
import static androidx.media3.decoder.mpegh.MpeghAudioRenderer.CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.CapturingAudioSink;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.Dumper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Playback tests using {@link MpeghAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public final class MpeghPlaybackTest {

  private static final String SAMPLE_MHM1_URI = "mp4/sample_mhm1_prefaudiolang.mp4";
  private static final String ACTION_EVENT_XML =
      "<ActionEvent uuid=\"BB0C0000-0000-0000-0000-000083610318\" version=\"11.0\""
          + " actionType=\"60\" paramInt=\"0\" paramFloat=\"20\" />";
  private static final int MAX_PERSISTENCE_STORAGE = 8096;

  @Nullable private ByteBuffer persistenceBuffer;

  @Before
  public void setUp() {
    if (!MpeghLibrary.isAvailable()) {
      fail("Mpegh library not available.");
    }
    persistenceBuffer = ByteBuffer.allocateDirect(MAX_PERSISTENCE_STORAGE);
  }

  @Test
  public void testPlayback() throws Exception {
    playAndAssertAudioSinkInput(
        SAMPLE_MHM1_URI, /* command= */ null, /* persistenceBuffer= */ null, /* run= */ 1);
  }

  @Test
  public void testPlaybackWithCommand() throws Exception {
    playAndAssertAudioSinkInput(
        SAMPLE_MHM1_URI, ACTION_EVENT_XML, /* persistenceBuffer= */ null, /* run= */ 1);
  }

  @Test
  public void testPlaybackWithCommandAndPersistence() throws Exception {
    // First run with command to populate persistence
    playAndAssertAudioSinkInput(SAMPLE_MHM1_URI, ACTION_EVENT_XML, persistenceBuffer, /* run= */ 1);

    // Second run without command to verify persistence behavior
    playAndAssertAudioSinkInput(
        SAMPLE_MHM1_URI, /* command= */ null, persistenceBuffer, /* run= */ 2);
  }

  private static void playAndAssertAudioSinkInput(
      String fileName, @Nullable String command, @Nullable ByteBuffer persistenceBuffer, int run)
      throws Exception {
    CapturingAudioSink audioSink = CapturingAudioSink.create();
    TestPlaybackRunnable testPlaybackRunnable =
        new TestPlaybackRunnable(
            Uri.parse("asset:///media/" + fileName),
            ApplicationProvider.getApplicationContext(),
            audioSink,
            command,
            persistenceBuffer);

    Thread thread = new Thread(testPlaybackRunnable);
    thread.start();
    thread.join();

    if (testPlaybackRunnable.playbackException != null) {
      throw testPlaybackRunnable.playbackException;
    }

    String commandSuffix = command != null ? ".cmd" : "";
    String persistenceSuffix = persistenceBuffer != null ? ".persist." + run : "";

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        audioSink,
        "audiosinkdumps/" + fileName + commandSuffix + persistenceSuffix + ".audiosink.dump");
    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        testPlaybackRunnable,
        "audiosinkdumps/" + fileName + commandSuffix + persistenceSuffix + ".asi.dump");
  }

  private static final class TestPlaybackRunnable
      implements Player.Listener, Runnable, Dumper.Dumpable {

    private final Context context;
    private final Uri uri;
    private final AudioSink audioSink;
    @Nullable private final String command;
    @Nullable private final ByteBuffer persistenceBuffer;
    private final List<String> interceptedAsi;

    @Nullable private ExoPlayer player;
    @Nullable private PlaybackException playbackException;

    public TestPlaybackRunnable(
        Uri uri,
        Context context,
        AudioSink audioSink,
        @Nullable String command,
        @Nullable ByteBuffer persistenceBuffer) {
      this.uri = uri;
      this.context = context;
      this.audioSink = audioSink;
      this.command = command;
      this.persistenceBuffer = persistenceBuffer;
      this.interceptedAsi = new ArrayList<>();
    }

    @Override
    public void run() {
      Looper.prepare();
      RenderersFactory renderersFactory =
          (eventHandler,
              videoRendererEventListener,
              audioRendererEventListener,
              textRendererOutput,
              metadataRendererOutput) ->
              new Renderer[] {
                new MpeghAudioRenderer(eventHandler, audioRendererEventListener, audioSink)
              };
      player = new ExoPlayer.Builder(context, renderersFactory).build();
      player.addListener(this);
      MediaSource mediaSource =
          new ProgressiveMediaSource.Factory(
                  new DefaultDataSource.Factory(context),
                  Mp4Extractor.newFactory(new DefaultSubtitleParserFactory()))
              .createMediaSource(MediaItem.fromUri(uri));
      player.setMediaSource(mediaSource);

      if (command != null) {
        CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
        codecParametersBuilder.setString(CODEC_PARAM_MPEGH_UI_COMMAND, command);
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
      if (persistenceBuffer != null) {
        persistenceBuffer.rewind();
        CodecParameters.Builder codecParametersBuilderPersistence = new CodecParameters.Builder();
        codecParametersBuilderPersistence.setByteBuffer(
            CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER, persistenceBuffer);
        player.setAudioCodecParameters(codecParametersBuilderPersistence.build());
      }

      List<String> filterKeys =
          Arrays.asList(CODEC_PARAM_MPEGH_UI_CONFIG, CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER);
      player.addAudioCodecParametersChangeListener(
          codecParameters -> {
            if (codecParameters.get(CODEC_PARAM_MPEGH_UI_CONFIG) != null) {
              interceptedAsi.add((String) codecParameters.get(CODEC_PARAM_MPEGH_UI_CONFIG));
            }
            if (codecParameters.get(CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER) != null) {
              ByteBuffer tmp =
                  (ByteBuffer) codecParameters.get(CODEC_PARAM_MPEGH_UI_PERSISTENCE_BUFFER);
              if (tmp != null && persistenceBuffer != null && !tmp.equals(persistenceBuffer)) {
                tmp.rewind();
                persistenceBuffer.rewind();
                persistenceBuffer.put(tmp);
                persistenceBuffer.rewind();
              }
              Looper.myLooper().quit();
            }
          },
          filterKeys);

      player.prepare();
      player.play();
      Looper.loop();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      playbackException = error;
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED
          || (playbackState == Player.STATE_IDLE && playbackException != null)) {
        player.release();
        if (persistenceBuffer == null) {
          Looper.myLooper().quit();
        }
      }
    }

    @Override
    public void dump(Dumper dumper) {
      if (!interceptedAsi.isEmpty()) {
        for (int i = 0; i < interceptedAsi.size(); i++) {
          dumper.add("MPEG-H ASI " + i, interceptedAsi.get(i));
        }
      }

      if (persistenceBuffer != null) {
        Charset charset = StandardCharsets.UTF_8;
        String text = charset.decode(persistenceBuffer).toString();
        dumper.add("MPEG-H persistence buffer ", text);
      }
    }
  }
}
