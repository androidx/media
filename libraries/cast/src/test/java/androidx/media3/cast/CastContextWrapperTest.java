/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.Nullable;
import androidx.media3.cast.CastContextWrapper.MediaRouteSelectorListener;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.TaskCompletionSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.shadows.ShadowLooper;

/** Tests for {@link CastContextWrapper}. */
@RunWith(AndroidJUnit4.class)
public final class CastContextWrapperTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private CastContext mockCastContext;
  @Mock private SessionManager mockSessionManager;
  @Mock private CastSession mockCastSession;
  @Captor ArgumentCaptor<OnCompleteListener<CastContext>> completionListenerCaptor;

  @Mock private SessionManagerListener<CastSession> mockListener;
  @Mock private CastContextWrapper.CastContextInitializer mockCastContextInitializer;
  @Mock private MediaRouteSelectorListener mockMediaRouteSelectorListener;
  private MediaRouteSelector mediaRouteSelector;
  private TaskCompletionSource<CastContext> castContextTaskCompletionSource;

  @Before
  public void setUp() {
    when(mockCastContext.getSessionManager()).thenReturn(mockSessionManager);
    castContextTaskCompletionSource = new TaskCompletionSource<>();
    when(mockCastContextInitializer.init()).thenReturn(castContextTaskCompletionSource.getTask());
    mediaRouteSelector = new MediaRouteSelector.Builder().addControlCategory("category").build();
    when(mockCastContext.getMergedSelector()).thenReturn(mediaRouteSelector);
  }

  @After
  public void tearDown() {
    CastContextWrapper.reset();
  }

  @Test
  public void getSingletonInstance_returnsSameInstance() {
    CastContextWrapper instance1 = CastContextWrapper.getSingletonInstance();
    CastContextWrapper instance2 = CastContextWrapper.getSingletonInstance();

    assertThat(instance1).isSameInstanceAs(instance2);
  }

  @Test
  public void initWithContext_alreadyInitialized_doesNothing() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.initWithContext(mockCastContext);

    castContextWrapper.initWithContext(
        mock(CastContext.class)); // Try to init with another context.

    // Still using the first context.
    castContextWrapper.endCurrentSession(true);
    verify(mockSessionManager).endCurrentSession(true);
  }

  @Test
  public void initWithContext_withPendingListener_onSessionStartedNotCalledWithNullSessionId() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.addSessionManagerListener(mockListener);
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);

    castContextWrapper.initWithContext(mockCastContext);

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
    verify(mockListener, never()).onSessionStarted(any(), any());
  }

  @Test
  public void
      initWithContext_withPendingListenerAndNoSession_addsListenerButDoesNotCallOnSessionStarted() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.addSessionManagerListener(mockListener);
    when(mockSessionManager.getCurrentCastSession()).thenReturn(null);

    castContextWrapper.initWithContext(mockCastContext);

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
    verify(mockListener, never()).onSessionStarted(any(), any());
  }

  @Test
  public void needsInitialization_byDefault_returnsTrue() {
    assertThat(CastContextWrapper.getSingletonInstance().needsInitialization()).isTrue();
  }

  @Test
  public void needsInitialization_returnsFalse() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();

    castContextWrapper.initWithContext(mockCastContext);

    assertThat(castContextWrapper.needsInitialization()).isFalse();
  }

  @Test
  public void getCastContextLoadFailure_byDefault_returnsNull() {
    assertThat(CastContextWrapper.getSingletonInstance().getCastContextLoadFailure()).isNull();
  }

  @Test
  public void addSessionManagerListener_beforeInit_addsListenerAfterInit() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.addSessionManagerListener(mockListener);

    castContextWrapper.initWithContext(mockCastContext);

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void addSessionManagerListener_afterInit_isAddedDirectly() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();

    castContextWrapper.initWithContext(mockCastContext);

    castContextWrapper.addSessionManagerListener(mockListener);
    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void removeSessionManagerListener_beforeInit_removesFromPending() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.addSessionManagerListener(mockListener);
    castContextWrapper.removeSessionManagerListener(mockListener);

    castContextWrapper.initWithContext(mockCastContext);

    verify(mockSessionManager, never()).addSessionManagerListener(any(), any());
  }

  @Test
  public void removeSessionManagerListener_afterInit_removesFromSessionManager() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.initWithContext(mockCastContext);

    castContextWrapper.removeSessionManagerListener(mockListener);

    verify(mockSessionManager).removeSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void getCurrentCastSession_beforeInit_returnsNull() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    assertThat(castContextWrapper.getCurrentCastSession()).isNull();
  }

  @Test
  public void getCurrentCastSession_afterInit_returnsSession() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);

    castContextWrapper.initWithContext(mockCastContext);

    assertThat(castContextWrapper.getCurrentCastSession()).isSameInstanceAs(mockCastSession);
  }

  @Test
  public void endCurrentSession_beforeInit_doesNotCrash() {
    CastContextWrapper.getSingletonInstance().endCurrentSession(true);
  }

  @Test
  public void asyncInit_successful_initializesContext() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.addSessionManagerListener(mockListener);
    castContextWrapper.asyncInit(mockCastContextInitializer);
    assertThat(castContextWrapper.needsInitialization()).isFalse();
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);

    CastSession sessionBeforeInit = castContextWrapper.getCurrentCastSession();
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();
    CastSession sessionAfterInit = castContextWrapper.getCurrentCastSession();

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
    verify(mockSessionManager, atLeastOnce()).getCurrentCastSession();
    assertThat(castContextWrapper.needsInitialization()).isFalse();
    assertThat(castContextWrapper.getCastContextLoadFailure()).isNull();
    assertThat(sessionBeforeInit).isNull();
    assertThat(sessionAfterInit).isSameInstanceAs(mockCastSession);
  }

  @Test
  public void asyncInit_failure_setsFailure() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.asyncInit(mockCastContextInitializer);
    assertThat(castContextWrapper.needsInitialization()).isFalse();

    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    assertThat(castContextWrapper.needsInitialization()).isFalse();
    assertThat(castContextWrapper.getCastContextLoadFailure()).isEqualTo(exception);
  }

  @Test
  public void asyncInit_alreadyInitialized_doesNothing() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.initWithContext(mockCastContext);

    castContextWrapper.asyncInit(mockCastContextInitializer);

    verify(mockCastContextInitializer, never()).init();
  }

  @Test
  public void
      registerListenerAndGetCurrentSelector_beforeInit_notifiesListenerAfterSuccessfulInit() {
    verifyRegisterListenerAndGetCurrentSelectorBeforeInit(mediaRouteSelector);
  }

  @Test
  public void
      registerListenerAndGetCurrentSelector_beforeInitWithNullSelector_notifiesListenerAfterInit() {
    verifyRegisterListenerAndGetCurrentSelectorBeforeInit(/* selector= */ null);
  }

  private void verifyRegisterListenerAndGetCurrentSelectorBeforeInit(
      @Nullable MediaRouteSelector selector) {
    when(mockCastContext.getMergedSelector()).thenReturn(selector);
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    MediaRouteSelector unused =
        castContextWrapper.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
    castContextWrapper.asyncInit(mockCastContextInitializer);

    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    MediaRouteSelector expectedSelector = (selector == null) ? MediaRouteSelector.EMPTY : selector;
    verify(mockMediaRouteSelectorListener).onMediaRouteSelectorChanged(expectedSelector);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_beforeInit_notifiesListenerAfterFailedInit() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    MediaRouteSelector unused =
        castContextWrapper.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
    castContextWrapper.asyncInit(mockCastContextInitializer);

    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    verify(mockMediaRouteSelectorListener).onMediaRouteSelectorChanged(MediaRouteSelector.EMPTY);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_afterSuccessfulInit_returnsSelector() {
    verifyRegisterListenerAndGetCurrentSelectorAfterInit(mediaRouteSelector);
  }

  @Test
  public void
      registerListenerAndGetCurrentSelector_afterInitWithNullSelector_returnsEmptySelector() {
    verifyRegisterListenerAndGetCurrentSelectorAfterInit(/* selector= */ null);
  }

  private void verifyRegisterListenerAndGetCurrentSelectorAfterInit(
      @Nullable MediaRouteSelector selector) {
    when(mockCastContext.getMergedSelector()).thenReturn(selector);
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.initWithContext(mockCastContext);

    MediaRouteSelector expectedSelector = (selector == null) ? MediaRouteSelector.EMPTY : selector;
    assertThat(
            castContextWrapper.registerListenerAndGetCurrentSelector(
                mockMediaRouteSelectorListener))
        .isEqualTo(expectedSelector);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_afterFailedInit_returnsEmptySelector() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    castContextWrapper.asyncInit(mockCastContextInitializer);

    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    assertThat(castContextWrapper.getCastContextLoadFailure()).isEqualTo(exception);
    assertThat(
            castContextWrapper.registerListenerAndGetCurrentSelector(
                mockMediaRouteSelectorListener))
        .isEqualTo(MediaRouteSelector.EMPTY);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_unregisteredListener_doesNotNotifyListener() {
    CastContextWrapper castContextWrapper = CastContextWrapper.getSingletonInstance();
    MediaRouteSelector unused =
        castContextWrapper.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    castContextWrapper.unregisterListener(mockMediaRouteSelectorListener);
    castContextWrapper.asyncInit(mockCastContextInitializer);

    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
  }
}
