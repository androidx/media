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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.media.effect.studiosound.ProcessingResult;
import com.google.android.gms.media.effect.studiosound.StudioSound;
import com.google.android.gms.media.effect.studiosound.StudioSoundAudioType;
import com.google.android.gms.media.effect.studiosound.StudioSoundCallback;
import com.google.android.gms.media.effect.studiosound.StudioSoundClient;
import com.google.android.gms.media.effect.studiosound.StudioSoundOptions;
import com.google.android.gms.media.effect.studiosound.StudioSoundSession;
import com.google.android.gms.media.effect.studiosound.StudioSoundSessionCallback;
import com.google.android.gms.media.effect.studiosound.StudioSoundStatusCodes;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * {@link AudioProcessor} implementation for audio source separation using the <a
 * href="https://developers.google.com/android/reference/com/google/android/gms/media/effect/studiosound/package-summary">StudioSound
 * Google Play services module</a>.
 *
 * <p>This {@link AudioProcessor} outputs 8 or 16 interleaved channels containing the separated
 * sources from the received input stream, for mono or stereo input respectively. {@link
 * #getResults()} provides information about each resulting source, including what label it was
 * classified as and what channel indices in the output stream that source corresponds to.
 *
 * <p>The StudioSound module only supports up to 2 minutes of input. {@link #queueInput(ByteBuffer)}
 * will throw if the caller attempts to process an input stream beyond 2 minutes.
 *
 * <p>Each instance only supports processing one input stream. Any attempt to reuse the processor
 * after processing a stream will throw.
 *
 * <p>This {@link AudioProcessor} holds an internal {@link ExecutorService} for handling
 * asynchronous events. If using this class as a standalone effect, call {@link #reset()} to release
 * this {@link ExecutorService} and other resources.
 */
@RequiresApi(27)
@ExperimentalApi // TODO(b/573516156): Make @UnstableApi once we have validated the API.
public final class StudioSoundAudioProcessor extends BaseAudioProcessor {

  /** A record containing metadata about a separated source. */
  public static final class SeparationResult {

    /** The {@link AudioType label} assigned to this source. */
    public final @AudioType int label;

    /**
     * The channel indices of this source within the separated output stream.
     *
     * <p>These indices are generally contiguous and more than one index represents a source with a
     * higher channel count (e.g. stereo).
     */
    public final int[] channelIndices;

    public SeparationResult(@AudioType int label, int[] channelIndices) {
      this.label = label;
      this.channelIndices = channelIndices;
    }
  }

  /** Abstraction over {@link StudioSoundClient} for testing. */
  /* package */ interface Client extends ModuleClient {
    Task<StudioSoundSession> createSession(
        StudioSoundOptions options, StudioSoundSessionCallback callback);
  }

  /**
   * Type of separated source. One of {@link #AUDIO_TYPE_UNSPECIFIED}, {@link #AUDIO_TYPE_SPEECH},
   * {@link #AUDIO_TYPE_CROWD}, {@link #AUDIO_TYPE_MUSIC}, {@link #AUDIO_TYPE_NATURE}, {@link
   * #AUDIO_TYPE_NOISE}, or {@link #AUDIO_TYPE_WIND}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    AUDIO_TYPE_UNSPECIFIED,
    AUDIO_TYPE_SPEECH,
    AUDIO_TYPE_CROWD,
    AUDIO_TYPE_MUSIC,
    AUDIO_TYPE_NATURE,
    AUDIO_TYPE_NOISE,
    AUDIO_TYPE_WIND
  })
  public @interface AudioType {}

  /** See {@link StudioSoundAudioType#UNSPECIFIED}. */
  public static final int AUDIO_TYPE_UNSPECIFIED = StudioSoundAudioType.UNSPECIFIED;

  /** See {@link StudioSoundAudioType#SPEECH}. */
  public static final int AUDIO_TYPE_SPEECH = StudioSoundAudioType.SPEECH;

  /** See {@link StudioSoundAudioType#CROWD}. */
  public static final int AUDIO_TYPE_CROWD = StudioSoundAudioType.CROWD;

  /** See {@link StudioSoundAudioType#MUSIC}. */
  public static final int AUDIO_TYPE_MUSIC = StudioSoundAudioType.MUSIC;

  /** See {@link StudioSoundAudioType#NATURE}. */
  public static final int AUDIO_TYPE_NATURE = StudioSoundAudioType.NATURE;

  /** See {@link StudioSoundAudioType#NOISE}. */
  public static final int AUDIO_TYPE_NOISE = StudioSoundAudioType.NOISE;

  /** See {@link StudioSoundAudioType#WIND}. */
  public static final int AUDIO_TYPE_WIND = StudioSoundAudioType.WIND;

  /** Maximum number of sources that StudioSound will separate a stream into. */
  private static final int MAX_OUTPUT_SOURCES = 8;

  private static final double PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR = 1.0 / 0x7FFFFFFF;
  private static final int SESSION_CREATION_TIMEOUT_MS = 5_000;
  private static final String TAG = "StudioSoundAudioProc";

  private final Client client;

  private final AtomicReference<@NullableType List<ProcessingResult>> pendingResults;
  private final AtomicBoolean isSessionPending;
  private final AtomicInteger pendingStatusCode;
  private final AtomicReference<@NullableType Thread> processingThread;

  private final ExecutorService internalExecutorService;
  private final CatchingExecutor catchingExecutor;
  private final StudioSoundCallbackImpl studioSoundCallback;

  // Assigned on internalExecutor thread.
  private volatile @MonotonicNonNull StudioSoundSession session;

  // Assigned on processing thread.
  private volatile @MonotonicNonNull ImmutableList<SeparationResult> reportedResults;

  // Fields assigned and read from processing thread (except #flush() first call).
  private ByteBuffer accumulatingBuffer;
  private boolean isProcessing;
  private boolean isProcessingFinished;
  private volatile @MonotonicNonNull Task<StudioSoundSession> createSessionTask;

  /**
   * Returns a future that provides a new {@link StudioSoundAudioProcessor} instance.
   *
   * <p>This method verify that the current device supports the Google Play services StudioSound
   * module, attempt to download and install it if needed, and then return a new instance of {@link
   * StudioSoundAudioProcessor}.
   *
   * @param context The {@link Context}.
   */
  public static ListenableFuture<StudioSoundAudioProcessor> create(Context context) {
    return create(
        new PlayServicesClient(context),
        Util.newSingleThreadExecutor(/* threadName= */ "StudioSoundAudioProcessorThread"));
  }

  /* package */ static ListenableFuture<StudioSoundAudioProcessor> create(
      Client client, ExecutorService internalExecutor) {

    Task<StudioSoundAudioProcessor> processorTask =
        client
            .isDeviceSupported()
            .onSuccessTask(
                internalExecutor,
                supported -> {
                  if (!supported) {
                    throw new UnsupportedOperationException(
                        "Device does not support Google Play Services StudioSound module.");
                  }
                  return client.isModuleInstalled();
                })
            .onSuccessTask(
                internalExecutor,
                installed -> {
                  if (installed) {
                    return Tasks.forResult(true);
                  }
                  return client.installModule();
                })
            .onSuccessTask(
                internalExecutor,
                installed -> {
                  checkState(
                      installed, "Failed to install Google Play Services StudioSound module.");
                  return Tasks.forResult(new StudioSoundAudioProcessor(client, internalExecutor));
                })
            .addOnFailureListener(directExecutor(), e -> internalExecutor.shutdown());

    return taskToListenableFuture(processorTask, directExecutor());
  }

  @VisibleForTesting
  /* package */ StudioSoundAudioProcessor(Client client, ExecutorService internalExecutorService) {
    this.client = client;
    accumulatingBuffer = EMPTY_BUFFER;
    pendingResults = new AtomicReference<>();
    isSessionPending = new AtomicBoolean();
    pendingStatusCode = new AtomicInteger(C.INDEX_UNSET);
    this.internalExecutorService = internalExecutorService;
    this.catchingExecutor = new CatchingExecutor(internalExecutorService);
    this.studioSoundCallback = new StudioSoundCallbackImpl();
    this.processingThread = new AtomicReference<>();
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    // TODO(b/528372318): Tighten thread checks once first call also happens on processing
    // thread.
    // This method receives a first call on a different thread than the normal processing
    // thread during export.
    maybeHandleError();

    if ((inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
            && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
        || inputAudioFormat.channelCount > 2) {
      throw new UnhandledAudioFormatException(
          "StudioSoundAudioProcessor only supports float or 16 bit PCM input in mono or"
              + " stereo.",
          inputAudioFormat);
    }

    checkState(
        !isSessionPending.get(),
        "Cannot reconfigure StudioSoundAudioProcessor while another session is being created.");
    checkState(
        session == null,
        "Cannot reconfigure StudioSoundAudioProcessor when there is an active session.");

    StudioSoundOptions options =
        new StudioSoundOptions.Builder(
                toStudioSoundFormat(
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                    C.ENCODING_PCM_FLOAT))
            .build();
    isSessionPending.set(true);

    // These callbacks are run on the binder thread.
    createSessionTask =
        client
            .createSession(
                options,
                // TODO(b/568316431): Provide Executor once API allows it.
                // This callback is invoked on StudioSound's binder thread.
                pendingStatusCode::set)
            .onSuccessTask(
                catchingExecutor,
                s -> {
                  checkState(s.getAudioFormat().equals(options.getAudioFormat()));
                  s.registerCallback(catchingExecutor, studioSoundCallback);
                  // Keep session assignment at the end of scope so that other threads do not begin
                  // to
                  // operate on session before setup completes.
                  session = s;
                  isSessionPending.set(false);
                  return Tasks.forResult(s);
                })
            .addOnFailureListener(
                catchingExecutor,
                e -> {
                  if (e instanceof ApiException) {
                    pendingStatusCode.set(((ApiException) e).getStatusCode());
                  } else {
                    pendingStatusCode.set(StudioSoundStatusCodes.SESSION_INIT_FAILED);
                  }
                });

    return new AudioFormat(
        inputAudioFormat.sampleRate,
        /* channelCount= */ MAX_OUTPUT_SOURCES * inputAudioFormat.channelCount,
        C.ENCODING_PCM_16BIT);
  }

  @Override
  @SuppressWarnings(
      "ReferenceEquality") // We want to explicitly compare the two references and not contents.
  public void queueInput(ByteBuffer inputBuffer) {
    verifyProcessingThread();
    maybeHandleError();
    checkState(
        isSessionPending.get() || session != null,
        "Cannot queue input before calling #configure().");
    if (session == null) {
      return;
    }

    if (accumulatingBuffer == EMPTY_BUFFER) {
      accumulatingBuffer = checkNotNull(session.dequeueInputBuffer());
    }

    // TODO(b/528365674): Throw once AudioProcessingPipeline does not make spurious queueInput()
    // calls after EoS.
    if (isProcessing || isProcessingFinished) {
      return;
    }

    if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
      accumulatingBuffer.put(inputBuffer);
    } else {
      while (inputBuffer.hasRemaining()) {
        short inputSample = inputBuffer.getShort();
        accumulatingBuffer.putInt(toFloatPcmIntBits(inputSample));
      }
    }
  }

  @Override
  public ByteBuffer getOutput() {
    // TODO(b/(b/528372318): Tighten thread checks once first call also happens on processing
    // thread.
    // This method receives a first call on a different thread than the normal processing
    // thread during export due to eager configuration of AudioGraphInput.
    maybeHandleError();
    if (isProcessing) {
      maybeProcessResults();
    }

    return super.getOutput();
  }

  @Override
  public boolean isEnded() {
    return super.isEnded() && isProcessingFinished;
  }

  /**
   * Returns a list of {@link SeparationResult} that holds information about the channels and labels
   * corresponding to each separated source, or {@code null} if processing has not completed.
   *
   * <p>Any channels that do not map to a {@link SeparationResult} are empty and should be ignored.
   */
  @Nullable
  public ImmutableList<SeparationResult> getResults() {
    return reportedResults;
  }

  @Override
  @SuppressWarnings(
      "ReferenceEquality") // We want to explicitly compare the two references and not contents.
  protected void onQueueEndOfStream() {
    verifyProcessingThread();
    maybeHandleError();

    // TODO(b/528365674): Remove once AudioProcessingPipeline does not cause spurious EoS calls.
    if (isProcessing || isProcessingFinished) {
      return;
    }
    checkState(
        accumulatingBuffer != EMPTY_BUFFER && session != null,
        "Cannot queue EoS before queuing any input.");
    accumulatingBuffer.flip();
    session.queueInputBuffer(accumulatingBuffer);
    isProcessing = true;
  }

  @Override
  protected void onReset() {
    verifyProcessingThread();
    pendingResults.set(null);
    if (createSessionTask != null && !createSessionTask.isComplete()) {
      ConditionVariable isSessionCreationFinished = new ConditionVariable();
      createSessionTask.addOnCompleteListener(
          catchingExecutor, task -> isSessionCreationFinished.open());
      boolean sessionCreationTimedOut =
          isSessionCreationFinished.blockUninterruptible(SESSION_CREATION_TIMEOUT_MS);
      if (sessionCreationTimedOut) {
        Log.w(TAG, "StudioSound session creation timed out.");
      }
    }
    // TODO(b/573313278): Add a workaround for service crash during mid-process release.
    if (session != null) {
      session.release();
    }
    isProcessing = false;
    isProcessingFinished = false;
    pendingStatusCode.set(C.INDEX_UNSET);
    accumulatingBuffer = EMPTY_BUFFER;
    internalExecutorService.shutdown();
  }

  @Override
  protected void onFlush(StreamMetadata streamMetadata) {
    // TODO(b/(b/528372318): Tighten thread checks once first call also happens on processing
    // thread.
    // This method receives a first call on a different thread than the normal processing
    // thread during export due to eager configuration of AudioGraphInput. However, flush()
    // immediately gets a second call on the right thread after the spurious one.
    pendingResults.set(null);
    isProcessing = false;
    isProcessingFinished = false;
    accumulatingBuffer = EMPTY_BUFFER;

    if (session != null) {
      session.cancel();
    }
  }

  private void maybeProcessResults() {
    if (isProcessingFinished) {
      return;
    }
    List<ProcessingResult> results = pendingResults.get();
    if (results == null) {
      return;
    }
    checkState(results.size() <= MAX_OUTPUT_SOURCES);
    checkState(!results.isEmpty(), "Unexpected empty results.");
    interleaveResults(results);

    ImmutableList.Builder<SeparationResult> resultsBuilder = new ImmutableList.Builder<>();
    int channelsPerLabel = inputAudioFormat.channelCount;
    int channelIndex = 0;
    for (ProcessingResult result : results) {
      int[] channelIndices =
          IntStream.range(channelIndex, channelIndex + channelsPerLabel).toArray();
      resultsBuilder.add(new SeparationResult(result.getAudioType(), channelIndices));
      channelIndex += channelsPerLabel;
    }
    reportedResults = resultsBuilder.build();

    pendingResults.set(null);
    isProcessing = false;
    isProcessingFinished = true;
    checkNotNull(session).release();
  }

  /**
   * Interleaves the separated sources into a 16 bit PCM stream containing {@link
   * #MAX_OUTPUT_SOURCES} mono or stereo sources.
   *
   * <p>If {@code results} contains less than {@link #MAX_OUTPUT_SOURCES}, this method will pad the
   * remaining channels of the stream with silence, so that the stream always contains {@link
   * #MAX_OUTPUT_SOURCES} or {@code 2 x MAX_OUTPUT_SOURCES} channels.
   */
  private void interleaveResults(List<ProcessingResult> results) {
    ByteBuffer firstBuffer = results.get(0).getByteBuffer();
    checkState(firstBuffer.remaining() % (inputAudioFormat.channelCount * Float.BYTES) == 0);
    for (ProcessingResult result : results) {
      checkState(result.getByteBuffer().remaining() == firstBuffer.remaining());
    }
    // StudioSound outputs float samples (4 bytes), and we need to output 16 bit PCM samples (2
    // bytes).
    int outputBytes = MAX_OUTPUT_SOURCES * firstBuffer.remaining() / Short.BYTES;
    ByteBuffer output = replaceOutputBuffer(outputBytes);
    int skipBytes =
        (MAX_OUTPUT_SOURCES - results.size()) * inputAudioFormat.channelCount * Short.BYTES;
    while (firstBuffer.hasRemaining()) {
      for (int i = 0; i < results.size(); i++) {
        for (int j = 0; j < inputAudioFormat.channelCount; j++) {
          float sample = results.get(i).getByteBuffer().getFloat();
          output.putShort(toInt16BitPcm(sample));
        }
      }
      output.position(output.position() + skipBytes);
    }
    output.flip();
    checkState(output.remaining() == outputBytes);
  }

  private void verifyProcessingThread() {
    processingThread.compareAndSet(/* expectedValue= */ null, Thread.currentThread());
    checkState(processingThread.get() == Thread.currentThread());
  }

  private void maybeHandleError() {
    int statusCode = pendingStatusCode.get();
    if (statusCode != C.INDEX_UNSET) {
      isProcessing = false;
      isProcessingFinished = true;
      if (session != null) {
        session.release();
      }
      throw new IllegalStateException(
          Util.formatInvariant(
              "Unexpected error: %s(%d)",
              StudioSoundStatusCodes.getStatusCodeString(statusCode), statusCode));
    }
    Throwable uncaughtException = catchingExecutor.lastCaughtException.get();
    if (uncaughtException != null) {
      throw new IllegalStateException("Unexpected error:", uncaughtException);
    }
  }

  private static com.google.android.gms.media.effect.studiosound.AudioFormat toStudioSoundFormat(
      int sampleRate, int channelCount, @C.PcmEncoding int pcmEncoding) {
    return new com.google.android.gms.media.effect.studiosound.AudioFormat(
        sampleRate, pcmEncoding, channelCount);
  }

  /* package */ static final class PlayServicesClient implements Client {
    private final StudioSoundClient studioSoundClient;

    PlayServicesClient(Context context) {
      studioSoundClient = StudioSound.getClient(context.getApplicationContext());
    }

    @Override
    public Task<Boolean> isDeviceSupported() {
      return studioSoundClient.isDeviceSupported();
    }

    @Override
    public Task<Boolean> isModuleInstalled() {
      return studioSoundClient.isModuleInstalled();
    }

    @Override
    public Task<Boolean> installModule() {
      return studioSoundClient.installModule(/* installStatusCallback= */ null);
    }

    @Override
    public Task<StudioSoundSession> createSession(
        StudioSoundOptions options, StudioSoundSessionCallback callback) {
      return studioSoundClient.createSession(options, callback);
    }
  }

  private static final class CatchingExecutor implements Executor {

    final AtomicReference<@NullableType Throwable> lastCaughtException;
    private final Executor delegate;

    CatchingExecutor(Executor delegate) {
      this.delegate = delegate;
      lastCaughtException = new AtomicReference<>(null);
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(
          () -> {
            try {
              command.run();
            } catch (Throwable t) {
              lastCaughtException.set(t);
            }
          });
    }
  }

  private final class StudioSoundCallbackImpl implements StudioSoundCallback {
    @Override
    public void onProcessed(List<ProcessingResult> list) {
      pendingResults.set(list);
    }

    @Override
    public void onError(int i) {
      checkNotNull(session).release();
      pendingStatusCode.set(i);
    }

    @Override
    public void onCancelled(int i) {
      // Do nothing. Cancellation is not an error because users cannot request it directly.
    }
  }

  /**
   * Returns a {@link ListenableFuture} wrapping the provided {@link Task}.
   *
   * <p>Snippet taken from <a
   * href="https://developers.google.com/android/guides/tasks#guava-listenablefuture">Task API
   * docs</a>.
   */
  private static <T> ListenableFuture<T> taskToListenableFuture(Task<T> task, Executor executor) {
    return CallbackToFutureAdapter.getFuture(
        completer ->
            task.addOnCompleteListener(
                executor,
                completedTask -> {
                  if (completedTask.isCanceled()) {
                    completer.setCancelled();
                  } else if (completedTask.isSuccessful()) {
                    completer.set(completedTask.getResult());
                  } else {
                    Exception e = completedTask.getException();
                    if (e != null) {
                      completer.setException(e);
                    } else {
                      throw new IllegalStateException();
                    }
                  }
                }));
  }

  /** Converts a 16 bit little endian PCM sample into float PCM and returns it as int bits. */
  private static int toFloatPcmIntBits(short sample) {
    int pcm32BitInteger = sample << 16;
    float pcm32BitFloat = (float) (PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR * pcm32BitInteger);
    return Float.isNaN(pcm32BitFloat) ? 0 : Float.floatToIntBits(pcm32BitFloat);
  }

  /** Converts a float PCM sample into a 16 bit PCM sample */
  private static short toInt16BitPcm(float sample) {
    // 32 bit floating point -> 16 bit resampling. Floating point values are in the range
    // [-1.0, 1.0], so need to be scaled by Short.MAX_VALUE.
    // Clamp to avoid integer overflow if the floating point values exceed their nominal range
    float floatValue = Util.constrainValue(sample, /* min= */ -1, /* max= */ 1);
    return (short) (floatValue * Short.MAX_VALUE);
  }
}
