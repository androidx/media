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

import androidx.annotation.Nullable;
import com.google.android.gms.media.effect.studiosound.AudioFormat;
import com.google.android.gms.media.effect.studiosound.ProcessingResult;
import com.google.android.gms.media.effect.studiosound.StudioSoundCallback;
import com.google.android.gms.media.effect.studiosound.StudioSoundOptions;
import com.google.android.gms.media.effect.studiosound.StudioSoundSession;
import com.google.android.gms.media.effect.studiosound.StudioSoundSessionCallback;
import com.google.android.gms.media.effect.studiosound.StudioSoundStatusCodes.StatusCode;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Utilities and fakes for {@link StudioSoundAudioProcessor} tests. */
/* package */ final class StudioSoundTestUtil {

  private StudioSoundTestUtil() {}

  /* package*/ static final class FakeStudioSoundSession implements StudioSoundSession {

    private static final int BUFFER_SIZE = 4096;

    /* package */ @StatusCode int pendingProcessingError;

    /* package */ List<ProcessingResult> pendingResults;

    /* package */ final SettableFuture<ByteBuffer> receivedInputBuffer;
    /* package */ final AtomicBoolean releaseCalled;

    private final AudioFormat audioFormat;
    private final ByteBuffer inputBuffer;

    @Nullable private StudioSoundCallback callback;
    private @MonotonicNonNull Executor listenerExecutor;

    FakeStudioSoundSession(AudioFormat audioFormat) {
      this.audioFormat = audioFormat;
      inputBuffer =
          ByteBuffer.allocateDirect(BUFFER_SIZE * audioFormat.getChannelCount())
              .order(ByteOrder.nativeOrder());
      pendingResults = ImmutableList.of();
      receivedInputBuffer = SettableFuture.create();
      releaseCalled = new AtomicBoolean();
    }

    @Override
    public AudioFormat getAudioFormat() {
      return audioFormat;
    }

    @Nullable
    @Override
    public ByteBuffer dequeueInputBuffer() {
      return inputBuffer;
    }

    @Override
    public void registerCallback(Executor executor, StudioSoundCallback studioSoundCallback) {
      callback = studioSoundCallback;
      listenerExecutor = executor;
    }

    @Override
    public void unregisterCallback() {
      callback = null;
      listenerExecutor = null;
    }

    @Override
    public void queueInputBuffer(ByteBuffer byteBuffer) {
      receivedInputBuffer.set(byteBuffer);
      if (callback == null) {
        return;
      }
      if (pendingProcessingError != 0) {
        checkNotNull(listenerExecutor).execute(() -> callback.onError(pendingProcessingError));
      } else if (!pendingResults.isEmpty()) {
        listenerExecutor.execute(() -> callback.onProcessed(pendingResults));
      }
    }

    @Override
    public void cancel() {}

    @Override
    public void release() {
      releaseCalled.set(true);
    }
  }

  /* package*/ static final class FakeStudioSoundClient extends FakeModuleClient
      implements StudioSoundAudioProcessor.Client {
    /* package */ Task<StudioSoundSession> createSessionTask;
    /* package */ final AtomicBoolean createSessionCalled;
    @Nullable /* package */ StudioSoundSessionCallback savedSessionCallback;
    @Nullable /* package */ StudioSoundOptions lastOptions;

    FakeStudioSoundClient() {
      createSessionTask = Tasks.forCanceled();
      this.createSessionCalled = new AtomicBoolean(false);
    }

    @Override
    public Task<StudioSoundSession> createSession(
        StudioSoundOptions studioSoundOptions,
        StudioSoundSessionCallback studioSoundSessionCallback) {
      createSessionCalled.set(true);
      savedSessionCallback = studioSoundSessionCallback;
      lastOptions = studioSoundOptions;
      return createSessionTask;
    }
  }
}
