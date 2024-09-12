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

package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.media3.exoplayer.audio.AudioSink;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link PlaybackAudioGraphWrapper}. */
@RunWith(AndroidJUnit4.class)
public class PlaybackAudioGraphWrapperTest {
  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private PlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  @Mock AudioSink outputAudioSink;

  @Before
  public void setUp() {
    playbackAudioGraphWrapper =
        new PlaybackAudioGraphWrapper(
            new DefaultAudioMixer.Factory(), /* effects= */ ImmutableList.of(), outputAudioSink);
  }

  @After
  public void tearDown() {
    playbackAudioGraphWrapper.release();
  }

  @Test
  public void processData_noAudioSinksCreated_returnsFalse() throws Exception {
    assertThat(playbackAudioGraphWrapper.processData()).isFalse();
  }

  @Test
  public void processData_audioSinkHasNotConfiguredYet_returnsFalse() throws Exception {
    AudioGraphInputAudioSink unused = playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);

    assertThat(playbackAudioGraphWrapper.processData()).isFalse();
  }

  @Test
  public void inputPlay_withOneInput_playsOutputSink() throws Exception {
    AudioGraphInputAudioSink inputAudioSink =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);

    inputAudioSink.play();

    verify(outputAudioSink).play();
  }

  @Test
  public void inputPause_withOneInput_pausesOutputSink() throws Exception {
    AudioGraphInputAudioSink inputAudioSink =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);

    inputAudioSink.play();
    inputAudioSink.pause();

    verify(outputAudioSink).pause();
  }

  @Test
  public void inputReset_withOneInput_pausesOutputSink() {
    AudioGraphInputAudioSink inputAudioSink =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);

    inputAudioSink.play();
    inputAudioSink.reset();

    verify(outputAudioSink).pause();
  }

  @Test
  public void inputPlay_whenPlaying_doesNotPlayOutputSink() throws Exception {
    AudioGraphInputAudioSink inputAudioSink =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    inputAudioSink.play();
    inputAudioSink.play();

    verify(outputAudioSink, atMostOnce()).play();
  }

  @Test
  public void inputPause_whenNotPlaying_doesNotPauseOutputSink() throws Exception {
    AudioGraphInputAudioSink inputAudioSink =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);

    inputAudioSink.pause();

    verify(outputAudioSink, never()).pause();
  }

  @Test
  public void someInputPlay_withMultipleInputs_doesNotPlayOutputSink() throws Exception {
    AudioGraphInputAudioSink inputAudioSink1 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    AudioGraphInputAudioSink inputAudioSink2 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 1);
    AudioGraphInputAudioSink unused = playbackAudioGraphWrapper.createInput(/* inputIndex= */ 2);

    inputAudioSink1.play();
    inputAudioSink2.play();
    verify(outputAudioSink, never()).play();
  }

  @Test
  public void allInputPlay_withMultipleInputs_playsOutputSinkOnce() throws Exception {
    AudioGraphInputAudioSink inputAudioSink1 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    AudioGraphInputAudioSink inputAudioSink2 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 1);
    AudioGraphInputAudioSink inputAudioSink3 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 2);

    inputAudioSink1.play();
    inputAudioSink2.play();
    inputAudioSink3.play();

    verify(outputAudioSink, atMostOnce()).play();
  }

  @Test
  public void firstInputPause_withMultipleInputs_pausesOutputSink() throws Exception {
    InOrder inOrder = inOrder(outputAudioSink);
    AudioGraphInputAudioSink inputAudioSink1 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    AudioGraphInputAudioSink inputAudioSink2 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 1);
    AudioGraphInputAudioSink inputAudioSink3 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 2);

    inputAudioSink1.play();
    inputAudioSink2.play();
    inputAudioSink3.play();
    inputAudioSink2.pause();

    inOrder.verify(outputAudioSink).pause();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void allInputPause_withMultipleInputs_pausesOutputSinkOnce() throws Exception {
    AudioGraphInputAudioSink inputAudioSink1 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    AudioGraphInputAudioSink inputAudioSink2 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 1);
    AudioGraphInputAudioSink inputAudioSink3 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 2);

    inputAudioSink1.play();
    inputAudioSink2.play();
    inputAudioSink3.play();
    inputAudioSink2.pause();
    inputAudioSink1.pause();
    inputAudioSink3.pause();

    verify(outputAudioSink, atMostOnce()).pause();
  }

  @Test
  public void inputPlayAfterPause_withMultipleInputs_playsOutputSink() throws Exception {
    InOrder inOrder = inOrder(outputAudioSink);
    AudioGraphInputAudioSink inputAudioSink1 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 0);
    AudioGraphInputAudioSink inputAudioSink2 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 1);
    AudioGraphInputAudioSink inputAudioSink3 =
        playbackAudioGraphWrapper.createInput(/* inputIndex= */ 2);

    inputAudioSink1.play();
    inputAudioSink2.play();
    inputAudioSink3.play();
    inputAudioSink2.pause();
    inputAudioSink1.pause();
    inputAudioSink2.play();
    inputAudioSink1.play();

    inOrder.verify(outputAudioSink).play();
    inOrder.verify(outputAudioSink).pause();
    inOrder.verify(outputAudioSink).play();
    Mockito.verifyNoMoreInteractions(outputAudioSink);
  }
}
