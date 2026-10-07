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
package androidx.media3.effect.playservices;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.gms.media.effect.enhancement.EnhancementCallback;
import com.google.android.gms.media.effect.enhancement.EnhancementOptions;
import com.google.android.gms.media.effect.enhancement.EnhancementSession;
import com.google.android.gms.media.effect.enhancement.EnhancementSessionCallback;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.atomic.AtomicBoolean;

/** Utilities and fakes for {@link EnhancementSessionManager} tests. */
/* package */ final class EnhancementSessionTestUtil {

  private EnhancementSessionTestUtil() {}

  /** Fake {@link EnhancementSession} for tests. */
  /* package */ static final class FakeEnhancementSession implements EnhancementSession {
    /* package */ final AtomicBoolean isReleased = new AtomicBoolean(false);

    @Override
    public Surface getInputSurface() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setOutputSurface(
        Surface surface, EnhancementOptions options, EnhancementCallback callback) {}

    @Override
    public void process(Bitmap bitmap, EnhancementOptions options, EnhancementCallback callback) {}

    @Override
    public void release() {
      isReleased.set(true);
    }

    @Override
    public void cancel() {}
  }

  /** Fake {@link ModuleClient} implementation for tests. */
  /* package */ static class FakeModuleClient implements ModuleClient {

    /* package */ final AtomicBoolean installModuleCalled;
    /* package */ Task<Boolean> deviceSupportedTask;
    /* package */ Task<Boolean> moduleInstalledTask;
    /* package */ Task<Boolean> installModuleTask;

    /* package */ FakeModuleClient() {
      this.installModuleCalled = new AtomicBoolean(false);
      this.deviceSupportedTask = Tasks.forResult(true);
      this.moduleInstalledTask = Tasks.forResult(true);
      this.installModuleTask = Tasks.forResult(true);
    }

    @Override
    public Task<Boolean> isDeviceSupported() {
      return deviceSupportedTask;
    }

    @Override
    public Task<Boolean> isModuleInstalled() {
      return moduleInstalledTask;
    }

    @Override
    public Task<Boolean> installModule() {
      installModuleCalled.set(true);
      return installModuleTask;
    }
  }

  /** Fake {@link EnhancementSessionManager.Client} for tests. */
  /* package */ static final class FakeClient extends FakeModuleClient
      implements EnhancementSessionManager.Client {

    /* package */ final AtomicBoolean createSessionCalled;
    @Nullable /* package */ Task<EnhancementSession> createSessionTask;
    /* package */ boolean autoTriggerSessionCallback;
    @Nullable /* package */ EnhancementSessionCallback savedSessionCallback;
    @Nullable /* package */ EnhancementOptions lastOptions;

    @Nullable private final EnhancementSession sessionToReturn;

    /* package */ FakeClient(@Nullable EnhancementSession sessionToReturn) {
      this.createSessionCalled = new AtomicBoolean(false);
      this.sessionToReturn = sessionToReturn;
      this.autoTriggerSessionCallback = sessionToReturn != null;
    }

    @Override
    public Task<EnhancementSession> createSession(
        EnhancementOptions options, EnhancementSessionCallback callback) {
      createSessionCalled.set(true);
      lastOptions = options;
      savedSessionCallback = callback;
      if (autoTriggerSessionCallback && sessionToReturn != null) {
        callback.onSessionCreated(sessionToReturn);
      }
      if (createSessionTask != null) {
        return createSessionTask;
      }
      return sessionToReturn != null ? Tasks.forResult(sessionToReturn) : Tasks.forCanceled();
    }
  }
}
