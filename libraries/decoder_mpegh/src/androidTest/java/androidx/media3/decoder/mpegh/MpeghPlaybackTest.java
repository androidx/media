/*
 * Copyright (C) 2016 The Android Open Source Project
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
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Playback tests using {@link MpeghAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public class MpeghPlaybackTest {

  private static final String BEAR_URI = "mp4/sample_mhm1_prefaudiolang.mp4";

  private static final int MAX_PERSISTENCE_STORAGE = 8096;
  private @Nullable ByteBuffer persistence_buffer;

  @Before
  public void setUp() {
    if (!MpeghLibrary.isAvailable()) {
      fail("Mpegh library not available.");
    }
    persistence_buffer = ByteBuffer.allocateDirect(MAX_PERSISTENCE_STORAGE);
  }

  @Test
  public void testPlayback() throws Exception {
    playAndAssertAudioSinkInput(BEAR_URI, null, null, 1);
  }

  @Test
  public void testPlaybackWithCommand() throws Exception {
    String command = "<ActionEvent uuid=\"BB0C0000-0000-0000-0000-000083610318\" version=\"11.0\" actionType=\"60\" paramInt=\"0\" paramFloat=\"20\" />";
    playAndAssertAudioSinkInput(BEAR_URI, command, null, 1);
  }

  @Test
  public void testPlaybackWithCommandAndPersistence() throws Exception {
    String command = "<ActionEvent uuid=\"BB0C0000-0000-0000-0000-000083610318\" version=\"11.0\" actionType=\"60\" paramInt=\"0\" paramFloat=\"20\" />";
    playAndAssertAudioSinkInput(BEAR_URI, command, persistence_buffer, 1);

    playAndAssertAudioSinkInput(BEAR_URI, null, persistence_buffer, 2);
  }

  private static void playAndAssertAudioSinkInput(String fileName, String command, ByteBuffer persistence_buffer, int run) throws Exception {
    CapturingAudioSink audioSink = CapturingAudioSink.create();

    TestPlaybackRunnable testPlaybackRunnable =
        new TestPlaybackRunnable(
            Uri.parse("asset:///media/" + fileName),
            ApplicationProvider.getApplicationContext(),
            audioSink,
            command,
            persistence_buffer);
    Thread thread = new Thread(testPlaybackRunnable);
    thread.start();
    thread.join();
    if (testPlaybackRunnable.playbackException != null) {
      throw testPlaybackRunnable.playbackException;
    }

    String tmp = "";
    if (command != null) {
      tmp = ".cmd";
    }

    String tmp_persist = "";
    if (persistence_buffer != null) {
      tmp_persist = ".persist." + run;
    }

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        audioSink,
        "audiosinkdumps/" + fileName + tmp + tmp_persist + ".audiosink.dump");

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        testPlaybackRunnable,
        "audiosinkdumps/" + fileName + tmp + tmp_persist + ".asi.dump");
  }

  private static class TestPlaybackRunnable implements Player.Listener, Runnable, Dumper.Dumpable {

    private final Context context;
    private final Uri uri;
    private final AudioSink audioSink;
    private final String command;

    private final List<String> interceptedAsi;
    @Nullable private final ByteBuffer persistence_buffer;

    @Nullable private ExoPlayer player;
    @Nullable private PlaybackException playbackException;

    public TestPlaybackRunnable(Uri uri, Context context, AudioSink audioSink, String command, @Nullable ByteBuffer persistence_buffer) {
      this.uri = uri;
      this.context = context;
      this.audioSink = audioSink;
      this.command = command;
      this.interceptedAsi = new ArrayList<>();
      this.persistence_buffer = persistence_buffer;
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
        codecParametersBuilder.setString("mpegh-ui-command", command);
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
      if (persistence_buffer != null) {
        persistence_buffer.rewind();
        CodecParameters.Builder codecParametersBuilderPersistence = new CodecParameters.Builder();
        codecParametersBuilderPersistence.setByteBuffer("mpegh-ui-persistence-buffer", persistence_buffer);
        player.setAudioCodecParameters(codecParametersBuilderPersistence.build());
      }
      ArrayList<String> filterKeys = new ArrayList<>();
      filterKeys.add("mpegh-ui-config");
      filterKeys.add("mpegh-ui-persistence-buffer");
      player.addAudioCodecParametersChangeListener(codecParameters -> {
        if (codecParameters.get("mpegh-ui-config") != null) {
          interceptedAsi.add((String)codecParameters.get("mpegh-ui-config"));
        }
        if (codecParameters.get("mpegh-ui-persistence-buffer") != null) {
          ByteBuffer tmp = (ByteBuffer)codecParameters.get("mpegh-ui-persistence-buffer");
          if (tmp != null && persistence_buffer != null && !tmp.equals(persistence_buffer)) {
            tmp.rewind();
            persistence_buffer.rewind();
            persistence_buffer.put(tmp);
            persistence_buffer.rewind();
          }
          Looper.myLooper().quit();
        }
      }, filterKeys);

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
        if (persistence_buffer == null) {
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

      if (persistence_buffer != null) {
        Charset charset = StandardCharsets.UTF_8;
        String text = charset.decode(persistence_buffer).toString();
        dumper.add("MPEG-H persistence buffer ", text);
      }
    }
  }
}
