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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.media.effect.enhancement.EnhancementCallback;
import com.google.android.gms.media.effect.enhancement.EnhancementMode;
import com.google.android.gms.media.effect.enhancement.EnhancementOptions;
import com.google.android.gms.media.effect.enhancement.EnhancementSession;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link EnhancementSessionManager}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 30)
public final class EnhancementSessionManagerTest {

  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

  private EnhancementSessionTestUtil.FakeClient fakeClient;
  private FakeEnhancementSession fakeSession;
  private TestListener testListener;
  private EnhancementSessionManager sessionManager;

  @Before
  public void setUp() {
    fakeSession = new FakeEnhancementSession();
    fakeClient = new EnhancementSessionTestUtil.FakeClient(fakeSession);
    testListener = new TestListener();
    sessionManager = new EnhancementSessionManager(directExecutor(), testListener, fakeClient);
  }

  @Test
  public void initializeSession_whenDeviceNotSupported_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forResult(false);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Device not supported");
    assertThat(testListener.readySession.get()).isNull();
  }

  @Test
  public void initializeSession_whenDeviceCheckFails_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forException(new IOException("Service disconnected"));

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.readySession.get()).isNull();
  }

  @Test
  public void initializeSession_whenModuleAlreadyInstalled_skipsInstallAndCreatesSession() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ true,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.installModuleCalled.get()).isFalse();
    assertThat(fakeClient.createSessionCalled.get()).isTrue();
    assertThat(testListener.readySession.get()).isEqualTo(fakeSession);
    assertThat(testListener.readyOptions.get()).isNotNull();
    assertThat(testListener.readyOptions.get().isTonemappingEnabled()).isTrue();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void initializeSession_whenModuleNotInstalled_installsModuleAndCreatesSession() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(false);
    fakeClient.installModuleTask = Tasks.forResult(true);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ true,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.installModuleCalled.get()).isTrue();
    assertThat(fakeClient.createSessionCalled.get()).isTrue();
    assertThat(testListener.readySession.get()).isEqualTo(fakeSession);
    assertThat(testListener.readyOptions.get().isDeblurAndDenoiseVideoEnabled()).isTrue();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void initializeSession_whenModuleInstallFails_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(false);
    fakeClient.installModuleTask = Tasks.forResult(false);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.installModuleCalled.get()).isTrue();
    assertThat(fakeClient.createSessionCalled.get()).isFalse();
    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Failed to install");
  }

  @Test
  public void initializeSession_whenSessionCreationFailed_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionCreationFailed(
        new Status(CommonStatusCodes.INTERNAL_ERROR, "Initialization failed"));

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Session creation failed");
    assertThat(testListener.errorCount.get()).isEqualTo(1);
    assertThat(testListener.readySession.get()).isNull();
  }

  @Test
  public void
      initializeSession_whenSessionCreationFailedAndCreateSessionTaskFails_notifiesErrorOnce() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;
    fakeClient.createSessionTask = Tasks.forException(new IOException("Creation failed"));

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionCreationFailed(
        new Status(CommonStatusCodes.INTERNAL_ERROR, "Initialization failed"));

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.errorCount.get()).isEqualTo(1);
  }

  @Test
  public void initializeSession_whenSessionDisconnected_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionDisconnected(
        new Status(CommonStatusCodes.NETWORK_ERROR, "Service died"));

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Session disconnected");
  }

  @Test
  public void initializeSession_afterRelease_doesNotCreateSession() {
    sessionManager.release();

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(fakeClient.createSessionCalled.get()).isFalse();
    assertThat(testListener.readySession.get()).isNull();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void release_whileCheckingDeviceSupport_doesNotInstallModuleOrCreateSession() {
    TaskCompletionSource<Boolean> deviceSupportedCompletionSource = new TaskCompletionSource<>();
    fakeClient.deviceSupportedTask = deviceSupportedCompletionSource.getTask();
    fakeClient.moduleInstalledTask = Tasks.forResult(false);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);
    sessionManager.release();
    deviceSupportedCompletionSource.setResult(true);

    assertThat(fakeClient.installModuleCalled.get()).isFalse();
    assertThat(fakeClient.createSessionCalled.get()).isFalse();
    assertThat(testListener.readySession.get()).isNull();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void release_beforeSessionCreated_releasesSessionWhenItArrivesAndDoesNotNotifyListener() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    sessionManager.release();

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionCreated(fakeSession);

    assertThat(fakeSession.isReleased.get()).isTrue();
    assertThat(testListener.readySession.get()).isNull();
    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void release_beforeSessionCreationFailed_doesNotNotifyListener() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    sessionManager.release();

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionCreationFailed(
        new Status(CommonStatusCodes.INTERNAL_ERROR, "Initialization failed"));

    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void release_beforeSessionDisconnected_doesNotNotifyListener() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    sessionManager.release();

    assertThat(fakeClient.savedSessionCallback).isNotNull();
    fakeClient.savedSessionCallback.onSessionDisconnected(
        new Status(CommonStatusCodes.NETWORK_ERROR, "Service died"));

    assertThat(testListener.error.get()).isNull();
  }

  @Test
  public void release_afterSessionCreated_releasesUnderlyingSession() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(testListener.readySession.get()).isEqualTo(fakeSession);
    assertThat(fakeSession.isReleased.get()).isFalse();

    sessionManager.release();
    assertThat(fakeSession.isReleased.get()).isTrue();
  }

  @Test
  public void initializeSession_configuresCorrectEnhancementOptions() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ true,
        /* isDeblurAndDenoiseVideoEnabled= */ true,
        /* isUpscaleVideoEnabled= */ true);

    EnhancementOptions options = testListener.readyOptions.get();
    assertThat(options).isNotNull();
    assertThat(options.getWidth()).isEqualTo(WIDTH);
    assertThat(options.getHeight()).isEqualTo(HEIGHT);
    assertThat(options.getEnhancementMode()).isEqualTo(EnhancementMode.SURFACE);
    assertThat(options.isTonemappingEnabled()).isTrue();
    assertThat(options.isDeblurAndDenoiseVideoEnabled()).isTrue();
    assertThat(options.isUpscaleVideoEnabled()).isTrue();
  }

  @Test
  public void initializeSession_whenCreateSessionReturnsNull_triggersOnError() {
    fakeClient.deviceSupportedTask = Tasks.forResult(true);
    fakeClient.moduleInstalledTask = Tasks.forResult(true);
    fakeClient.autoTriggerSessionCallback = false;
    fakeClient.createSessionTask = Tasks.forResult(null);

    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get())
        .hasMessageThat()
        .contains("Session creation returned null");
    assertThat(testListener.errorCount.get()).isEqualTo(1);
    assertThat(testListener.readySession.get()).isNull();
  }

  @Test
  public void initializeSession_whenCalledTwice_throwsIllegalStateException() {
    sessionManager.initializeSession(
        WIDTH,
        HEIGHT,
        /* isTonemappingEnabled= */ false,
        /* isDeblurAndDenoiseVideoEnabled= */ false,
        /* isUpscaleVideoEnabled= */ false);

    assertThrows(
        IllegalStateException.class,
        () ->
            sessionManager.initializeSession(
                WIDTH,
                HEIGHT,
                /* isTonemappingEnabled= */ false,
                /* isDeblurAndDenoiseVideoEnabled= */ false,
                /* isUpscaleVideoEnabled= */ false));
  }

  private static class FakeEnhancementSession implements EnhancementSession {
    final AtomicBoolean isReleased = new AtomicBoolean(false);

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

  private static class TestListener implements EnhancementSessionManager.Listener {
    final AtomicReference<EnhancementSession> readySession = new AtomicReference<>();
    final AtomicReference<EnhancementOptions> readyOptions = new AtomicReference<>();
    final AtomicReference<VideoFrameProcessingException> error = new AtomicReference<>();
    final AtomicInteger errorCount = new AtomicInteger(0);

    @Override
    public void onSessionReady(EnhancementSession session, EnhancementOptions options) {
      readySession.set(session);
      readyOptions.set(options);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      error.set(exception);
      errorCount.incrementAndGet();
    }
  }
}
