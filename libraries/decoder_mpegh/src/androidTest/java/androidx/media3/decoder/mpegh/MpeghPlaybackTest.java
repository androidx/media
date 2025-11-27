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
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Playback tests using {@link MpeghAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public class MpeghPlaybackTest {

  private static final String BEAR_URI = "mp4/sample_mhm1_prefaudiolang.mp4";

  @Before
  public void setUp() {
    if (!MpeghLibrary.isAvailable()) {
      fail("Mpegh library not available.");
    }
  }

  @Test
  public void testPlayback() throws Exception {
    playAndAssertAudioSinkInput(BEAR_URI, null);
  }

  @Test
  public void testPlaybackWithCommand() throws Exception {
    String command = "<ActionEvent uuid=\"00000000-0000-0000-0000-000000000000\" version=\"11.0\" actionType=\"70\" paramText=\"eng\" />";
    playAndAssertAudioSinkInput(BEAR_URI, command);
  }

  private static void playAndAssertAudioSinkInput(String fileName, String command) throws Exception {
    CapturingAudioSink audioSink = CapturingAudioSink.create();

    TestPlaybackRunnable testPlaybackRunnable =
        new TestPlaybackRunnable(
            Uri.parse("asset:///media/" + fileName),
            ApplicationProvider.getApplicationContext(),
            audioSink,
            command);
    Thread thread = new Thread(testPlaybackRunnable);
    thread.start();
    thread.join();
    if (testPlaybackRunnable.playbackException != null) {
      throw testPlaybackRunnable.playbackException;
    }

    String tmp = ".def";
    if (command != null) {
      tmp = ".eng";
    }

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        audioSink,
        "audiosinkdumps/" + fileName + tmp + ".audiosink.dump");

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        testPlaybackRunnable,
        "audiosinkdumps/" + fileName + tmp + ".asi.dump");
  }

  private static class TestPlaybackRunnable implements Player.Listener, Runnable, Dumper.Dumpable {

    private final Context context;
    private final Uri uri;
    private final AudioSink audioSink;
    private final String command;

    private final List<String> interceptedData;

    @Nullable private ExoPlayer player;
    @Nullable private PlaybackException playbackException;

    public TestPlaybackRunnable(Uri uri, Context context, AudioSink audioSink, String command) {
      this.uri = uri;
      this.context = context;
      this.audioSink = audioSink;
      this.command = command;
      this.interceptedData = new ArrayList<>();
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
      ArrayList<String> filterKeys = new ArrayList<>();
      filterKeys.add("mpegh-ui-config");
      player.addAudioCodecParametersChangeListener(codecParameters -> {
        if (codecParameters.get("mpegh-ui-config") != null) {
          interceptedData.add((String)codecParameters.get("mpegh-ui-config"));
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
        Looper.myLooper().quit();
      }
    }

    @Override
    public void dump(Dumper dumper) {
      if (interceptedData.isEmpty()) {
        return;
      }
      for (int i = 0; i < interceptedData.size(); i++) {
        dumper.add("MPEG-H ASI " + i, interceptedData.get(i));
      }
    }
  }
}
