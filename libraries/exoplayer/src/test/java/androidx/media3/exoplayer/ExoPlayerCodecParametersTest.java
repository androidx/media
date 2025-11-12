/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;

/** Unit tests for ExoPlayer's codec parameter handling. */
@RunWith(AndroidJUnit4.class)
public class ExoPlayerCodecParametersTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  @Config(sdk = 29)
  public void listener_receivesInitialState_whenRegistered() throws Exception {
    FakeAudioRenderer fakeAudioRenderer = new FakeAudioRenderer();
    RenderersFactory renderersFactory =
        (eventHandler, videoListener, audioListener, textOutput, metadataOutput) -> {
          fakeAudioRenderer.setEventListener(eventHandler, audioListener);
          return new Renderer[] {fakeAudioRenderer};
        };
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    // Simulate renderer having an initial state for keyB.
    fakeAudioRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("keyB", 200).build());
    shadowOf(Looper.getMainLooper()).idle();
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    List<String> keys = Arrays.asList("keyA", "keyB");

    player.addAudioCodecParametersChangeListener(mockListener, keys);
    advance(player).untilPendingCommandsAreFullyHandled();

    // Verify listener is immediately called with the current state of its subscribed keys.
    ArgumentCaptor<CodecParameters> captor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(captor.capture());
    assertThat(captor.getValue().keySet()).containsExactly("keyB");
    assertThat(captor.getValue().get("keyB")).isEqualTo(200);

    player.release();
  }

  @Test
  @Config(sdk = 29)
  public void listener_notifiedOnValueChange() throws Exception {
    FakeAudioRenderer fakeAudioRenderer = new FakeAudioRenderer();
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) -> {
          fakeAudioRenderer.setEventListener(handler, audioListener);
          return new Renderer[] {fakeAudioRenderer};
        };
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    player.addAudioCodecParametersChangeListener(mockListener, Arrays.asList("test-key"));
    advance(player).untilPendingCommandsAreFullyHandled();
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(any());
    clearInvocations(mockListener);

    fakeAudioRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("test-key", 100).build());

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(100);
  }

  @Test
  @Config(sdk = 29)
  public void listener_notNotifiedForUnsubscribedKeyChange() throws Exception {
    FakeAudioRenderer fakeAudioRenderer = new FakeAudioRenderer();
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) -> {
          fakeAudioRenderer.setEventListener(handler, audioListener);
          return new Renderer[] {fakeAudioRenderer};
        };
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    player.addAudioCodecParametersChangeListener(mockListener, Arrays.asList("keyA"));
    advance(player).untilPendingCommandsAreFullyHandled();
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(any());
    clearInvocations(mockListener);

    // Simulate a change to a key the listener is not subscribed to.
    fakeAudioRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("keyB", 500).build());

    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener, never()).onCodecParametersChanged(any());
  }

  @Test
  @Config(sdk = 29)
  public void multipleListeners_addAndRemove_correctNotifications() throws Exception {
    FakeAudioRenderer fakeAudioRenderer = new FakeAudioRenderer();
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) -> {
          fakeAudioRenderer.setEventListener(handler, audioListener);
          return new Renderer[] {fakeAudioRenderer};
        };
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    CodecParametersChangeListener listener1 = mock(CodecParametersChangeListener.class);
    CodecParametersChangeListener listener2 = mock(CodecParametersChangeListener.class);
    player.addAudioCodecParametersChangeListener(listener1, Arrays.asList("keyA", "keyB"));
    player.addAudioCodecParametersChangeListener(listener2, Arrays.asList("keyB", "keyC"));
    advance(player).untilPendingCommandsAreFullyHandled();
    shadowOf(Looper.getMainLooper()).idle();
    clearInvocations(listener1, listener2);

    // Simulate change affecting all keys.
    CodecParameters params =
        new CodecParameters.Builder()
            .setInteger("keyA", 10)
            .setInteger("keyB", 20)
            .setInteger("keyC", 30)
            .build();
    fakeAudioRenderer.simulateParametersChange(params);
    shadowOf(Looper.getMainLooper()).idle();

    // Verify listener1 gets only its keys.
    ArgumentCaptor<CodecParameters> captor1 = ArgumentCaptor.forClass(CodecParameters.class);
    verify(listener1).onCodecParametersChanged(captor1.capture());
    assertThat(captor1.getValue().keySet()).containsExactly("keyA", "keyB");

    // Verify listener2 gets only its keys.
    ArgumentCaptor<CodecParameters> captor2 = ArgumentCaptor.forClass(CodecParameters.class);
    verify(listener2).onCodecParametersChanged(captor2.capture());
    assertThat(captor2.getValue().keySet()).containsExactly("keyB", "keyC");

    clearInvocations(listener1, listener2);

    // Remove listener2.
    player.removeAudioCodecParametersChangeListener(listener2);
    advance(player).untilPendingCommandsAreFullyHandled();

    // Simulate change affecting keyB and keyC
    CodecParameters nextParams =
        new CodecParameters.Builder()
            .setInteger("keyA", 10)
            .setInteger("keyB", 200)
            .setInteger("keyC", 300)
            .build();
    fakeAudioRenderer.simulateParametersChange(nextParams);
    shadowOf(Looper.getMainLooper()).idle();

    // Verify only listener1 is called, and only with keys A and B
    verify(listener1).onCodecParametersChanged(captor1.capture());
    assertThat(captor1.getValue().keySet()).containsExactly("keyA", "keyB");
    assertThat(captor1.getValue().get("keyB")).isEqualTo(200);
    verify(listener2, never()).onCodecParametersChanged(any());

    player.release();
  }

  /**
   * A fake renderer that allows tests to simulate the {@code onAudioCodecParametersChanged} event.
   */
  private static class FakeAudioRenderer extends FakeRenderer {
    @Nullable private AudioRendererEventListener eventListener;
    @Nullable private Handler eventHandler;

    private FakeAudioRenderer() {
      super(C.TRACK_TYPE_AUDIO);
    }

    private void setEventListener(Handler handler, AudioRendererEventListener listener) {
      this.eventHandler = handler;
      this.eventListener = listener;
    }

    private void simulateParametersChange(CodecParameters newParams) {
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(() -> eventListener.onAudioCodecParametersChanged(newParams));
      }
    }
  }
}
