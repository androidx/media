/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect.playservices;

import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static androidx.media3.test.utils.TestUtil.createFloatArray;
import static androidx.media3.test.utils.TestUtil.getNonRandomByteBuffer;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.effect.playservices.StudioSoundAudioProcessor.SeparationResult;
import androidx.media3.effect.playservices.StudioSoundTestUtil.FakeStudioSoundClient;
import androidx.media3.effect.playservices.StudioSoundTestUtil.FakeStudioSoundSession;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.media.effect.studiosound.AudioFormat;
import com.google.android.gms.media.effect.studiosound.ProcessingResult;
import com.google.android.gms.media.effect.studiosound.StudioSoundAudioType;
import com.google.android.gms.media.effect.studiosound.StudioSoundSession;
import com.google.android.gms.media.effect.studiosound.StudioSoundStatusCodes;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** StudioSoundAudioProcessor test. */
@RunWith(AndroidJUnit4.class)
public final class StudioSoundAudioProcessorTest {

  private static final int TIMEOUT_MS = 1_000;
  private static final AudioFormat STUDIO_SOUND_STEREO_FLOAT =
      new AudioFormat(/* sampleRate= */ 44100, C.ENCODING_PCM_FLOAT, /* channelCount= */ 2);
  private static final AudioFormat STUDIO_SOUND_MONO_FLOAT =
      new AudioFormat(/* sampleRate= */ 44100, C.ENCODING_PCM_FLOAT, /* channelCount= */ 1);
  private static final AudioProcessor.AudioFormat MONO_FLOAT_PCM =
      new AudioProcessor.AudioFormat(
          /* sampleRate= */ 44100, /* channelCount= */ 1, C.ENCODING_PCM_FLOAT);

  private static final AudioProcessor.AudioFormat STEREO_FLOAT_PCM =
      new AudioProcessor.AudioFormat(
          /* sampleRate= */ 44100, /* channelCount= */ 2, C.ENCODING_PCM_FLOAT);

  private static final AudioProcessor.AudioFormat MONO_16BIT_PCM =
      new AudioProcessor.AudioFormat(
          /* sampleRate= */ 44100, /* channelCount= */ 1, C.ENCODING_PCM_16BIT);

  private static final AudioProcessor.AudioFormat STEREO_16BIT_PCM =
      new AudioProcessor.AudioFormat(
          /* sampleRate= */ 44100, /* channelCount= */ 2, C.ENCODING_PCM_16BIT);
  private static final int INPUT_FRAME_COUNT = 1024;

  private Context context;
  private ExecutorService internalExecutorService;
  private FakeStudioSoundClient fakeClient;

  @Before
  public void setUp() {
    context = getInstrumentation().getContext();
    internalExecutorService = Executors.newSingleThreadExecutor();
    fakeClient = new FakeStudioSoundClient();
  }

  @After
  public void tearDown() {
    assertThat(
            MoreExecutors.shutdownAndAwaitTermination(
                internalExecutorService, TIMEOUT_MS, MILLISECONDS))
        .isTrue();
  }

  @Test
  public void create_withoutPlayServicesAccess_throws() {
    ListenableFuture<StudioSoundAudioProcessor> soundSeparationFuture =
        StudioSoundAudioProcessor.create(context);

    Exception thrown = assertThrows(ExecutionException.class, soundSeparationFuture::get);
    assertThat(thrown).hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void create_withFailedInstallation_throws() throws Exception {
    fakeClient.installModuleTask = Tasks.forResult(false);
    fakeClient.moduleInstalledTask = Tasks.forResult(false);

    ListenableFuture<StudioSoundAudioProcessor> soundSeparationFuture =
        StudioSoundAudioProcessor.create(fakeClient, internalExecutorService);

    Exception thrown = assertThrows(ExecutionException.class, soundSeparationFuture::get);
    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(fakeClient.installModuleCalled.get()).isTrue();
    assertThat(internalExecutorService.awaitTermination(TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  @Test
  public void configure_withSessionCreationFailure_throwsOnNextCall() throws Exception {
    fakeClient.createSessionTask = Tasks.forException(new IllegalStateException());
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());

    processor.configure(MONO_FLOAT_PCM);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = getNonRandomByteBuffer(/* frameCount= */ 1, /* bytesPerFrame= */ 4);
    Exception thrown = assertThrows(IllegalStateException.class, () -> processor.queueInput(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.valueOf(StudioSoundStatusCodes.SESSION_INIT_FAILED));
  }

  @Test
  public void configure_withMismatchingStudioSoundSessionOptions_throwsErrorOnInputQueue()
      throws Exception {
    FakeStudioSoundSession fakeSession =
        new FakeStudioSoundSession(
            new AudioFormat(/* sampleRate= */ 44100, C.ENCODING_PCM_FLOAT, /* channelCount= */ 4));
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);

    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    processor.configure(STEREO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input = getNonRandomByteBuffer(INPUT_FRAME_COUNT, STEREO_16BIT_PCM.bytesPerFrame);

    try {
      // Configuration is not immediate, so try queuing input until the configuration fails.
      while (input.hasRemaining()) {
        processor.queueInput(input);
      }
      assertWithMessage("queueInput() did not throw.").fail();
    } catch (RuntimeException e) {
      assertThat(e).isInstanceOf(IllegalStateException.class);
      assertThat(e)
          .hasMessageThat()
          .contains(String.valueOf(StudioSoundStatusCodes.SESSION_INIT_FAILED));
    }
  }

  @Test
  public void configure_with16BitPcmMonoInput_outputs8ChannelsOf16BitPcm() throws Exception {
    fakeClient.createSessionTask = Tasks.forException(new IllegalStateException());
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    AudioProcessor.AudioFormat outputFormat = processor.configure(MONO_16BIT_PCM);

    assertThat(outputFormat.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(outputFormat.channelCount).isEqualTo(8);
    assertThat(outputFormat.sampleRate).isEqualTo(44100);
  }

  @Test
  public void configure_with16BitPcmStereoInput_outputs16ChannelsOf16BitPcm() throws Exception {
    fakeClient.createSessionTask = Tasks.forException(new IllegalStateException());
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    AudioProcessor.AudioFormat outputFormat = processor.configure(STEREO_16BIT_PCM);

    assertThat(outputFormat.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(outputFormat.channelCount).isEqualTo(16);
    assertThat(outputFormat.sampleRate).isEqualTo(44100);
  }

  @Test
  public void configure_whileSessionCreationIsPending_throws() throws Exception {
    TaskCompletionSource<StudioSoundSession> alwaysPendingTask = new TaskCompletionSource<>();
    fakeClient.createSessionTask = alwaysPendingTask.getTask();
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());

    processor.configure(STEREO_FLOAT_PCM);
    Exception thrown =
        assertThrows(IllegalStateException.class, () -> processor.configure(STEREO_FLOAT_PCM));
    assertThat(thrown).hasMessageThat().contains("another session");
  }

  @Test
  public void configure_afterCreatingPreviousSession_throws() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_MONO_FLOAT);
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    // Use directExecutor() to guarantee new session is immediately visible to
    // StudioSoundAudioProcessor.
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());

    processor.configure(MONO_FLOAT_PCM);
    Exception thrown =
        assertThrows(IllegalStateException.class, () -> processor.configure(MONO_FLOAT_PCM));
    assertThat(thrown).hasMessageThat().contains("active session");
  }

  @Test
  public void configure_withSessionCreationFailure_picksUpStatusCode() throws Exception {
    fakeClient.createSessionTask =
        Tasks.forException(
            new ApiException(new Status(StudioSoundStatusCodes.MAX_SESSIONS_REACHED)));
    // Use directExecutor() to guarantee new session is immediately visible to
    // StudioSoundAudioProcessor.
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());

    processor.configure(MONO_FLOAT_PCM);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = getNonRandomByteBuffer(INPUT_FRAME_COUNT, MONO_FLOAT_PCM.bytesPerFrame);
    Exception thrown = assertThrows(IllegalStateException.class, () -> processor.queueInput(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.valueOf(StudioSoundStatusCodes.MAX_SESSIONS_REACHED));
  }

  @Test
  public void configure_withMonoFloat_returns8ChannelsOf16BitPcm() throws Exception {
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    AudioProcessor.AudioFormat outputFormat = processor.configure(MONO_FLOAT_PCM);
    assertThat(outputFormat.channelCount).isEqualTo(8);
    assertThat(outputFormat.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(outputFormat.sampleRate).isEqualTo(MONO_FLOAT_PCM.sampleRate);
  }

  @Test
  public void configure_withStereoFloat_returns16ChannelsOf16BitPcm() throws Exception {
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    AudioProcessor.AudioFormat outputFormat = processor.configure(STEREO_FLOAT_PCM);
    assertThat(outputFormat.channelCount).isEqualTo(16);
    assertThat(outputFormat.encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(outputFormat.sampleRate).isEqualTo(MONO_FLOAT_PCM.sampleRate);
  }

  @Test
  public void process_withProcessingFailure_throwsOnNextCall() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_MONO_FLOAT);
    fakeSession.pendingProcessingError = StudioSoundStatusCodes.INTERNAL_ERROR;
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());
    processor.configure(MONO_FLOAT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input =
        getNonRandomByteBuffer(/* frameCount= */ INPUT_FRAME_COUNT, MONO_FLOAT_PCM.bytesPerFrame);

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.queueEndOfStream();

    Exception thrown = assertThrows(IllegalStateException.class, processor::getOutput);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.valueOf(StudioSoundStatusCodes.INTERNAL_ERROR));
  }

  @Test
  public void process_withMonoInput_interleavesOutputTo8Channels() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_MONO_FLOAT);
    fakeSession.pendingResults =
        ImmutableList.of(
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ 1, /* count= */ INPUT_FRAME_COUNT),
                StudioSoundAudioType.SPEECH),
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ -1, /* count= */ INPUT_FRAME_COUNT),
                StudioSoundAudioType.NOISE),
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ 0, /* count= */ INPUT_FRAME_COUNT),
                StudioSoundAudioType.WIND),
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ 0.5f, /* count= */ INPUT_FRAME_COUNT),
                StudioSoundAudioType.CROWD));
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);
    processor.configure(MONO_FLOAT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input = getNonRandomByteBuffer(INPUT_FRAME_COUNT, MONO_FLOAT_PCM.bytesPerFrame);

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.queueEndOfStream();
    // Callback from StudioSound needs to reach StudioSoundAudioProcessor first, and then
    // getOutput() needs to trigger results processing.
    ByteBuffer output = processor.getOutput();
    while (!processor.isEnded()) {
      output = processor.getOutput();
    }

    assertThat(processor.getResults()).isNotNull();
    assertThat(processor.getResults()).hasSize(4);
    ImmutableList<SeparationResult> results = processor.getResults();
    assertThat(results.get(0).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_SPEECH);
    assertThat(results.get(0).channelIndices).isEqualTo(new int[] {0});
    assertThat(results.get(1).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_NOISE);
    assertThat(results.get(1).channelIndices).isEqualTo(new int[] {1});
    assertThat(results.get(2).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_WIND);
    assertThat(results.get(2).channelIndices).isEqualTo(new int[] {2});
    assertThat(results.get(3).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_CROWD);
    assertThat(results.get(3).channelIndices).isEqualTo(new int[] {3});

    assertThat(output.remaining()).isEqualTo(INPUT_FRAME_COUNT * 2 * 8);
    ShortBuffer shortBuffer = output.asShortBuffer();
    while (shortBuffer.hasRemaining()) {
      assertThat(shortBuffer.get()).isEqualTo(Short.MAX_VALUE);
      assertThat(shortBuffer.get()).isEqualTo(Short.MIN_VALUE + 1);
      assertThat(shortBuffer.get()).isEqualTo(0);
      assertThat(shortBuffer.get()).isEqualTo(Short.MAX_VALUE / 2);
      for (int i = 0; i < 8 - results.size(); i++) {
        assertThat(shortBuffer.get()).isEqualTo(0);
      }
    }
  }

  @Test
  public void process_withStereoInput_interleavesOutputTo16Channels() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_STEREO_FLOAT);
    fakeSession.pendingResults =
        ImmutableList.of(
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ 1, /* count= */ INPUT_FRAME_COUNT * 2),
                StudioSoundAudioType.SPEECH),
            new ProcessingResult(
                createSingleValueBuffer(/* value= */ -1, /* count= */ INPUT_FRAME_COUNT * 2),
                StudioSoundAudioType.NOISE));
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);
    processor.configure(STEREO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input = getNonRandomByteBuffer(INPUT_FRAME_COUNT, STEREO_16BIT_PCM.bytesPerFrame);

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.queueEndOfStream();
    // Callback from StudioSound needs to reach StudioSoundAudioProcessor first, and then
    // getOutput() needs to trigger results processing.
    ByteBuffer output = processor.getOutput();
    while (!processor.isEnded()) {
      output = processor.getOutput();
    }

    assertThat(processor.getResults()).isNotNull();
    assertThat(processor.getResults()).hasSize(2);
    ImmutableList<SeparationResult> results = processor.getResults();
    assertThat(results.get(0).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_SPEECH);
    assertThat(results.get(0).channelIndices).isEqualTo(new int[] {0, 1});
    assertThat(results.get(1).label).isEqualTo(StudioSoundAudioProcessor.AUDIO_TYPE_NOISE);
    assertThat(results.get(1).channelIndices).isEqualTo(new int[] {2, 3});

    assertThat(output.remaining()).isEqualTo(INPUT_FRAME_COUNT * 2 * 16);
    ShortBuffer shortBuffer = output.asShortBuffer();
    while (shortBuffer.hasRemaining()) {
      assertThat(shortBuffer.get()).isEqualTo(Short.MAX_VALUE);
      assertThat(shortBuffer.get()).isEqualTo(Short.MAX_VALUE);
      assertThat(shortBuffer.get()).isEqualTo(Short.MIN_VALUE + 1);
      assertThat(shortBuffer.get()).isEqualTo(Short.MIN_VALUE + 1);
      for (int i = 0; i < 16 - results.size() * 2; i++) {
        assertThat(shortBuffer.get()).isEqualTo((short) 0);
      }
    }
  }

  @Test
  public void process_withMono16BitPcmInput_convertsInputToFloat() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_MONO_FLOAT);
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);
    processor.configure(MONO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input =
        createByteBuffer(new short[] {0, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE / 2});

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.queueEndOfStream();

    assertThat(createFloatArray(fakeSession.receivedInputBuffer.get(TIMEOUT_MS, MILLISECONDS)))
        .usingTolerance(1f / 0x8000)
        .containsExactly(new float[] {0, 1f, -1f, 0.5f})
        .inOrder();
  }

  @Test
  public void process_withStereo16BitPcmInput_convertsInputToFloat() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_STEREO_FLOAT);
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);
    processor.configure(STEREO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input =
        createByteBuffer(new short[] {0, Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE / 2});

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.queueEndOfStream();

    assertThat(createFloatArray(fakeSession.receivedInputBuffer.get(TIMEOUT_MS, MILLISECONDS)))
        .usingTolerance(1f / 0x8000)
        .containsExactly(new float[] {0, 1f, -1f, 0.5f})
        .inOrder();
  }

  @Test
  public void queueEndOfStream_beforeQueuingInput_throws() throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_STEREO_FLOAT);
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);

    processor.configure(STEREO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);

    Throwable t = assertThrows(IllegalStateException.class, processor::queueEndOfStream);
    assertThat(t).hasMessageThat().contains("Cannot queue EoS before queuing any input.");
  }

  @Test
  public void reset_afterSessionCreation_releasesSessionAndShutsDownInternalExecutor()
      throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_STEREO_FLOAT);
    fakeClient.createSessionTask = Tasks.forResult(fakeSession);
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, internalExecutorService);
    processor.configure(STEREO_16BIT_PCM);
    processor.flush(StreamMetadata.DEFAULT);
    ByteBuffer input = getNonRandomByteBuffer(INPUT_FRAME_COUNT, STEREO_16BIT_PCM.bytesPerFrame);

    // Session registration is not immediate, so keep queuing until processor accepts all input.
    // Processor will not accept any samples until session has been set up.
    while (input.hasRemaining()) {
      processor.queueInput(input);
    }
    assertThat(input.hasRemaining()).isFalse();
    processor.reset();

    assertThat(fakeSession.releaseCalled.get()).isTrue();
    assertThat(internalExecutorService.awaitTermination(TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  @Test
  public void reset_withPendingSessionCreation_waitsUntilCompletionAndReleasesSession()
      throws Exception {
    FakeStudioSoundSession fakeSession = new FakeStudioSoundSession(STUDIO_SOUND_STEREO_FLOAT);
    TaskCompletionSource<StudioSoundSession> alwaysPendingTask = new TaskCompletionSource<>();
    fakeClient.createSessionTask = alwaysPendingTask.getTask();
    StudioSoundAudioProcessor processor =
        new StudioSoundAudioProcessor(fakeClient, newDirectExecutorService());
    ConditionVariable resetCompleted = new ConditionVariable();

    internalExecutorService.execute(
        () -> {
          try {
            processor.configure(STEREO_16BIT_PCM);
            processor.flush(StreamMetadata.DEFAULT);
            processor.reset();
            resetCompleted.open();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    alwaysPendingTask.setResult(fakeSession);

    assertThat(resetCompleted.block(TIMEOUT_MS)).isTrue();
    assertThat(fakeSession.releaseCalled.get()).isTrue();
  }

  private static ByteBuffer createSingleValueBuffer(float value, int count) {
    float[] scratch = new float[count];
    Arrays.fill(scratch, value);
    ByteBuffer buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder());
    buffer.asFloatBuffer().put(scratch);
    buffer.limit(buffer.capacity());
    return buffer;
  }
}
