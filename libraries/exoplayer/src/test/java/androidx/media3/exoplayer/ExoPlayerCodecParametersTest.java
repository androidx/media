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
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestParameterInjector;
import org.robolectric.annotation.Config;

/** Unit tests for ExoPlayer's codec parameter handling. */
@RunWith(RobolectricTestParameterInjector.class)
public class ExoPlayerCodecParametersTest {

  private enum TestConfig {
    AUDIO,
    VIDEO
  }

  @SuppressWarnings("unused") // Used by TestParameterInjector
  @TestParameter
  private TestConfig testConfig;

  private ExoPlayer player;
  private FakeCodecRenderer fakeRenderer;

  @Before
  public void setUp() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    fakeRenderer =
        testConfig == TestConfig.AUDIO ? new FakeAudioRenderer() : new FakeVideoRenderer();
    RenderersFactory renderersFactory =
        (eventHandler, videoListener, audioListener, textOutput, metadataOutput) -> {
          fakeRenderer.setEventListener(
              eventHandler, testConfig == TestConfig.AUDIO ? audioListener : videoListener);
          return new Renderer[] {fakeRenderer};
        };
    player = new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();
    player.setMediaSource(new FakeMediaSource());
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
  }

  @After
  public void tearDown() {
    player.release();
  }

  @Test
  @Config(sdk = 29)
  public void listener_receivesInitialState_whenRegistered() throws Exception {
    // Simulate renderer having an initial state for keyB.
    fakeRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("keyB", 200).build());
    shadowOf(Looper.getMainLooper()).idle();
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    List<String> keys = Arrays.asList("keyA", "keyB");

    fakeRenderer.addListener(player, mockListener, keys);
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
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    fakeRenderer.addListener(player, mockListener, Arrays.asList("test-key"));
    advance(player).untilPendingCommandsAreFullyHandled();
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(any());
    clearInvocations(mockListener);

    fakeRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("test-key", 100).build());

    ArgumentCaptor<CodecParameters> paramsCaptor = ArgumentCaptor.forClass(CodecParameters.class);
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().get("test-key")).isEqualTo(100);
  }

  @Test
  @Config(sdk = 29)
  public void listener_notNotifiedForUnsubscribedKeyChange() throws Exception {
    CodecParametersChangeListener mockListener = mock(CodecParametersChangeListener.class);
    fakeRenderer.addListener(player, mockListener, Arrays.asList("keyA"));
    advance(player).untilPendingCommandsAreFullyHandled();
    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener).onCodecParametersChanged(any());
    clearInvocations(mockListener);

    // Simulate a change to a key the listener is not subscribed to.
    fakeRenderer.simulateParametersChange(
        new CodecParameters.Builder().setInteger("keyB", 500).build());

    shadowOf(Looper.getMainLooper()).idle();
    verify(mockListener, never()).onCodecParametersChanged(any());
  }

  @Test
  @Config(sdk = 29)
  public void multipleListeners_addAndRemove_correctNotifications() throws Exception {
    CodecParametersChangeListener listener1 = mock(CodecParametersChangeListener.class);
    CodecParametersChangeListener listener2 = mock(CodecParametersChangeListener.class);
    fakeRenderer.addListener(player, listener1, Arrays.asList("keyA", "keyB"));
    fakeRenderer.addListener(player, listener2, Arrays.asList("keyB", "keyC"));
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
    fakeRenderer.simulateParametersChange(params);
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
    fakeRenderer.removeListener(player, listener2);
    advance(player).untilPendingCommandsAreFullyHandled();

    // Simulate change affecting keyB and keyC
    CodecParameters nextParams =
        new CodecParameters.Builder()
            .setInteger("keyA", 10)
            .setInteger("keyB", 200)
            .setInteger("keyC", 300)
            .build();
    fakeRenderer.simulateParametersChange(nextParams);
    shadowOf(Looper.getMainLooper()).idle();

    // Verify only listener1 is called, and only with keys A and B
    verify(listener1).onCodecParametersChanged(captor1.capture());
    assertThat(captor1.getValue().keySet()).containsExactly("keyA", "keyB");
    assertThat(captor1.getValue().get("keyB")).isEqualTo(200);
    verify(listener2, never()).onCodecParametersChanged(any());

    player.release();
  }

  private abstract static class FakeCodecRenderer extends FakeRenderer {
    @Nullable Object eventListener;
    @Nullable Handler eventHandler;

    private FakeCodecRenderer(int trackType) {
      super(trackType);
    }

    private void setEventListener(Handler handler, Object listener) {
      this.eventHandler = handler;
      this.eventListener = listener;
    }

    abstract void addListener(
        ExoPlayer player, CodecParametersChangeListener listener, List<String> keys);

    abstract void removeListener(ExoPlayer player, CodecParametersChangeListener listener);

    abstract void simulateParametersChange(CodecParameters newParams);
  }

  private static class FakeAudioRenderer extends FakeCodecRenderer {
    private FakeAudioRenderer() {
      super(C.TRACK_TYPE_AUDIO);
    }

    @Override
    public void addListener(
        ExoPlayer player, CodecParametersChangeListener listener, List<String> keys) {
      player.addAudioCodecParametersChangeListener(listener, keys);
    }

    @Override
    public void removeListener(ExoPlayer player, CodecParametersChangeListener listener) {
      player.removeAudioCodecParametersChangeListener(listener);
    }

    @Override
    public void simulateParametersChange(CodecParameters newParams) {
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(
            () ->
                ((AudioRendererEventListener) eventListener)
                    .onAudioCodecParametersChanged(newParams));
      }
    }
  }

  private static class FakeVideoRenderer extends FakeCodecRenderer {
    private FakeVideoRenderer() {
      super(C.TRACK_TYPE_VIDEO);
    }

    @Override
    public void addListener(
        ExoPlayer player, CodecParametersChangeListener listener, List<String> keys) {
      player.addVideoCodecParametersChangeListener(listener, keys);
    }

    @Override
    public void removeListener(ExoPlayer player, CodecParametersChangeListener listener) {
      player.removeVideoCodecParametersChangeListener(listener);
    }

    @Override
    public void simulateParametersChange(CodecParameters newParams) {
      if (eventHandler != null && eventListener != null) {
        eventHandler.post(
            () ->
                ((VideoRendererEventListener) eventListener)
                    .onVideoCodecParametersChanged(newParams));
      }
    }
  }
}
