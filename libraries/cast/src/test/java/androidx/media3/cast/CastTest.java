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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import androidx.annotation.Nullable;
import androidx.media3.cast.Cast.MediaRouteSelectorListener;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastOptions;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

/** Tests for {@link Cast}. */
@RunWith(AndroidJUnit4.class)
public final class CastTest {

  private static final String FAKE_RECEIVER_APP_ID = "1234ABCD";

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private CastContext mockCastContext;
  @Mock private SessionManager mockSessionManager;
  @Mock private CastSession mockCastSession;
  @Captor ArgumentCaptor<OnCompleteListener<CastContext>> completionListenerCaptor;
  @Captor ArgumentCaptor<CastOptions.Modifier> optionsModifierCaptor;

  @Mock private SessionManagerListener<CastSession> mockListener;
  @Mock private Cast.CastContextInitializer mockCastContextInitializer;
  @Mock private MediaRouteSelectorListener mockMediaRouteSelectorListener;
  private Context context;
  private CastParams castParams;
  private MediaRouteSelector mediaRouteSelector;
  private TaskCompletionSource<CastContext> castContextTaskCompletionSource;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    CastParams.sdkVersionForTesting = CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP;
    castParams =
        new CastParams.Builder()
            .setReceiverApplicationId(FAKE_RECEIVER_APP_ID)
            .setRemoteToLocalEnabled(true)
            .setShowSystemOutputSwitcherOnCastButtonClick(false)
            .build();
    when(mockCastContext.getSessionManager()).thenReturn(mockSessionManager);
    castContextTaskCompletionSource = new TaskCompletionSource<>();
    when(mockCastContextInitializer.init()).thenReturn(castContextTaskCompletionSource.getTask());
    mediaRouteSelector = new MediaRouteSelector.Builder().addControlCategory("category").build();
    when(mockCastContext.getMergedSelector()).thenReturn(mediaRouteSelector);
  }

  @After
  public void tearDown() {
    Cast.reset();
    CastParams.sdkVersionForTesting = Build.VERSION.SDK_INT;
  }

  @Test
  public void getSingletonInstance_returnsSameInstance() {
    Cast instance1 = Cast.getSingletonInstance(context);
    Cast instance2 = Cast.getSingletonInstance(context);
    Cast instance3 = Cast.getSingletonInstance(mockCastContext);

    assertThat(instance1).isSameInstanceAs(instance2);
    assertThat(instance1).isSameInstanceAs(instance3);
  }

  @Test
  public void getSingletonInstance_withoutContext_throwsException() {
    assertThrows(NullPointerException.class, () -> Cast.getSingletonInstance((Context) null));
  }

  @Test
  public void needsInitialization_byDefault_returnsTrue() {
    assertThat(Cast.getSingletonInstance(context).needsInitialization()).isTrue();
  }

  @Test
  public void needsInitialization_returnsFalse() {
    assertThat(Cast.getSingletonInstance(mockCastContext).needsInitialization()).isFalse();
  }

  @Test
  public void ensureInitialized_alreadyInitialized_doesNotThrowException() {
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    cast.ensureInitialized(context);
  }

  @Test
  public void ensureInitialized_notInitializedAndExceptionThrownAfterInit_rethrowsException() {
    IllegalStateException originalException =
        new IllegalStateException("No manifest options provider to initialize CastContext");
    when(mockCastContextInitializer.init()).thenThrow(originalException);
    Cast cast = Cast.getSingletonInstance(context);
    assertThat(cast.needsInitialization()).isTrue();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> cast.ensureInitialized(mockCastContextInitializer));

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Must initialize Cast prior to using it. To achieve this, call"
                + " androidx.media3.cast.Cast.getSingletoninstance(context).initialize() in"
                + " Application#onCreate() method.");
    assertThat(exception).hasCauseThat().isEqualTo(originalException);
  }

  @Test
  public void ensureInitialized_notInitializedAndSuccessfulAfterInit_initializesContext() {
    Cast cast = Cast.getSingletonInstance(context);

    cast.ensureInitialized(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    assertThat(cast.needsInitialization()).isFalse();
    assertThat(cast.getCastContextLoadFailure()).isNull();
  }

  @Test
  public void ensureInitialized_notInitializedAndFailureAfterInit_setsFailure() {
    Cast cast = Cast.getSingletonInstance(context);
    assertThat(cast.needsInitialization()).isTrue();

    cast.ensureInitialized(mockCastContextInitializer);
    IllegalStateException exception = new IllegalStateException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    assertThat(cast.needsInitialization()).isFalse();
    assertThat(cast.getCastContextLoadFailure()).isEqualTo(exception);
  }

  @Test
  public void getCastContextLoadFailure_byDefault_returnsNull() {
    assertThat(Cast.getSingletonInstance(context).getCastContextLoadFailure()).isNull();
  }

  @Test
  public void addSessionManagerListener_beforeInit_addsListenerAfterInit() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.addSessionManagerListener(mockListener);

    Cast unused = Cast.getSingletonInstance(mockCastContext);

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void addSessionManagerListener_afterInit_isAddedDirectly() {
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    cast.addSessionManagerListener(mockListener);
    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void removeSessionManagerListener_beforeInit_removesFromPending() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.addSessionManagerListener(mockListener);
    cast.removeSessionManagerListener(mockListener);

    Cast unused = Cast.getSingletonInstance(mockCastContext);

    verify(mockSessionManager, never()).addSessionManagerListener(any(), any());
  }

  @Test
  public void removeSessionManagerListener_afterInit_removesFromSessionManager() {
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    cast.removeSessionManagerListener(mockListener);

    verify(mockSessionManager).removeSessionManagerListener(mockListener, CastSession.class);
  }

  @Test
  public void getCurrentCastSession_beforeInit_returnsNull() {
    Cast cast = Cast.getSingletonInstance(context);
    assertThat(cast.getCurrentCastSession()).isNull();
  }

  @Test
  public void getCurrentCastSession_afterInit_returnsSession() {
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    assertThat(cast.getCurrentCastSession()).isSameInstanceAs(mockCastSession);
  }

  @Test
  public void endCurrentSession_beforeInit_doesNotCrash() {
    Cast.getSingletonInstance(context).endCurrentSession(true);
  }

  @Test
  public void initialize_successful_initializesContext() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.addSessionManagerListener(mockListener);
    cast.initialize(mockCastContextInitializer);
    assertThat(cast.needsInitialization()).isFalse();
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);

    CastSession sessionBeforeInit = cast.getCurrentCastSession();
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();
    CastSession sessionAfterInit = cast.getCurrentCastSession();

    verify(mockSessionManager).addSessionManagerListener(mockListener, CastSession.class);
    verify(mockSessionManager, atLeastOnce()).getCurrentCastSession();
    assertThat(cast.needsInitialization()).isFalse();
    assertThat(cast.getCastContextLoadFailure()).isNull();
    assertThat(sessionBeforeInit).isNull();
    assertThat(sessionAfterInit).isSameInstanceAs(mockCastSession);
  }

  @Test
  public void initialize_failure_setsFailure() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    assertThat(cast.needsInitialization()).isFalse();

    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    assertThat(cast.needsInitialization()).isFalse();
    assertThat(cast.getCastContextLoadFailure()).isEqualTo(exception);
  }

  @Test
  public void initialize_alreadyInitialized_doesNothing() {
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    cast.initialize(mockCastContextInitializer);

    verify(mockCastContextInitializer, never()).init();
  }

  @Test
  public void initialize_withCastParams_initializesCastContextWithCastParamsDirectly() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer, castParams);

    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    assertThat(cast.needsInitialization()).isFalse();
    assertThat(cast.getCastContextLoadFailure()).isNull();
    verify(mockCastContext, never()).applyOptionsModifier(any());
  }

  @Test
  public void initialize_withExistingCastContext_appliesCastParams() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    cast.initialize(castParams);

    verify(mockCastContext).applyOptionsModifier(optionsModifierCaptor.capture());
    verifyCastOptionsModifierEquals(
        castParams.toCastOptionsModifier(context), optionsModifierCaptor.getValue());
  }

  @Test
  public void initialize_withExistingCastContextLoadFailure_doesNotApplyCastParams() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();
    assertThat(cast.getCastContextLoadFailure()).isEqualTo(exception);

    cast.initialize(castParams);

    verify(mockCastContext, never()).applyOptionsModifier(any());
  }

  @Test
  public void initialize_withPendingCastContextLoading_appliesCastParamsAfterInit() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);

    cast.initialize(castParams);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    verify(mockCastContext).applyOptionsModifier(optionsModifierCaptor.capture());
    verifyCastOptionsModifierEquals(
        castParams.toCastOptionsModifier(context), optionsModifierCaptor.getValue());
  }

  @Test
  public void initialize_withPendingCastContextLoading_appliesLastCastParamsAfterInit() {
    String newReceiverAppId1 = FAKE_RECEIVER_APP_ID + "_changed1";
    String newReceiverAppId2 = FAKE_RECEIVER_APP_ID + "_changed2";
    CastParams castParams1 =
        new CastParams.Builder().setReceiverApplicationId(newReceiverAppId1).build();
    CastParams castParams2 =
        new CastParams.Builder().setReceiverApplicationId(newReceiverAppId2).build();
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);

    cast.initialize(castParams1);
    cast.initialize(castParams2);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    verify(mockCastContext).applyOptionsModifier(optionsModifierCaptor.capture());
    verifyCastOptionsModifierEquals(
        castParams2.toCastOptionsModifier(context), optionsModifierCaptor.getValue());
  }

  @Test
  public void initialize_calledMultipleTimesAfterInit_appliesLatestCastParams() {
    String id1 = "ID_1";
    String id2 = "ID_2";
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();
    CastParams params1 = new CastParams.Builder().setReceiverApplicationId(id1).build();
    CastParams params2 = new CastParams.Builder().setReceiverApplicationId(id2).build();

    cast.initialize(params1);
    cast.initialize(params2);

    verify(mockCastContext, times(2)).applyOptionsModifier(optionsModifierCaptor.capture());
    String firstApplicationIdSet =
        optionsModifierCaptor.getAllValues().get(0).getReceiverApplicationId();
    assertThat(firstApplicationIdSet).isEqualTo(id1);
    String secondApplicationIdSet =
        optionsModifierCaptor.getAllValues().get(1).getReceiverApplicationId();
    assertThat(secondApplicationIdSet).isEqualTo(id2);
  }

  @Test
  public void initialize_noArgs_appliesDefaultCastParams() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    cast.initialize();

    verify(mockCastContext).applyOptionsModifier(optionsModifierCaptor.capture());
    verifyCastOptionsModifierEquals(
        CastParams.DEFAULT.toCastOptionsModifier(context), optionsModifierCaptor.getValue());
  }

  @Test
  public void initialize_withTargetSdkAtMin_appliesDefaultTrue() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP;
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();
    CastParams emptyParams = new CastParams.Builder().build();

    cast.initialize(emptyParams);

    verify(mockCastContext, atLeastOnce()).applyOptionsModifier(optionsModifierCaptor.capture());
    assertThat(optionsModifierCaptor.getValue().getShowSystemOutputSwitcherOnCastIconClick())
        .isTrue();
  }

  @Test
  public void initialize_withTargetSdkBelowMin_appliesDefaultFalse() {
    context.getApplicationInfo().targetSdkVersion =
        CastParams.SDK_VERSION_MIN_SHOW_OUTPUT_SWITCHER_IN_APP - 1;
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);
    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();
    CastParams emptyParams = new CastParams.Builder().build();

    cast.initialize(emptyParams);

    verify(mockCastContext, atLeastOnce()).applyOptionsModifier(optionsModifierCaptor.capture());
    assertThat(optionsModifierCaptor.getValue().getShowSystemOutputSwitcherOnCastIconClick())
        .isFalse();
  }

  @Test
  public void initialize_withNullCastParams_throwsException() {
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    NullPointerException exception =
        assertThrows(NullPointerException.class, () -> cast.initialize((CastParams) null));

    assertThat(exception).hasMessageThat().contains("castParams must not be null.");
  }

  @Test
  @Config(minSdk = VERSION_CODES.P)
  public void constructor_withContext_notOnMainProcess_throwsException() {
    final String mainProcessName = context.getPackageName();
    final String backgroundProcessName = mainProcessName + ":background";
    final String originalProcessName = Application.getProcessName();
    // Configure the ShadowApplication to run on the background process.
    ShadowApplication.setProcessName(backgroundProcessName);

    try {
      IllegalStateException exception =
          assertThrows(IllegalStateException.class, () -> Cast.getSingletonInstance(context));
      assertThat(exception)
          .hasMessageThat()
          .contains(
              String.format(
                  "The method must be called on the main process (%s), but was called on the"
                      + " process (%s).",
                  mainProcessName, backgroundProcessName));
    } finally {
      ShadowApplication.setProcessName(originalProcessName);
    }
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
    Cast cast = Cast.getSingletonInstance(context);
    MediaRouteSelector unused =
        cast.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
    cast.initialize(mockCastContextInitializer);

    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    MediaRouteSelector expectedSelector = (selector == null) ? MediaRouteSelector.EMPTY : selector;
    verify(mockMediaRouteSelectorListener).onMediaRouteSelectorChanged(expectedSelector);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_beforeInit_notifiesListenerAfterFailedInit() {
    Cast cast = Cast.getSingletonInstance(context);
    MediaRouteSelector unused =
        cast.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
    cast.initialize(mockCastContextInitializer);

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

  @Test
  public void registerListenerAndGetCurrentSelector_afterFailedInit_returnsEmptySelector() {
    Cast cast = Cast.getSingletonInstance(context);
    cast.initialize(mockCastContextInitializer);

    Exception exception = new RuntimeException("Failed to load");
    castContextTaskCompletionSource.setException(exception);
    ShadowLooper.idleMainLooper();

    assertThat(cast.getCastContextLoadFailure()).isEqualTo(exception);
    assertThat(cast.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener))
        .isEqualTo(MediaRouteSelector.EMPTY);
  }

  @Test
  public void registerListenerAndGetCurrentSelector_unregisteredListener_doesNotNotifyListener() {
    Cast cast = Cast.getSingletonInstance(context);
    MediaRouteSelector unused =
        cast.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener);
    cast.unregisterListener(mockMediaRouteSelectorListener);
    cast.initialize(mockCastContextInitializer);

    castContextTaskCompletionSource.setResult(mockCastContext);
    ShadowLooper.idleMainLooper();

    verify(mockMediaRouteSelectorListener, never()).onMediaRouteSelectorChanged(any());
  }

  private void verifyRegisterListenerAndGetCurrentSelectorAfterInit(
      @Nullable MediaRouteSelector selector) {
    when(mockCastContext.getMergedSelector()).thenReturn(selector);
    Cast cast = Cast.getSingletonInstance(mockCastContext);

    MediaRouteSelector expectedSelector = (selector == null) ? MediaRouteSelector.EMPTY : selector;
    assertThat(cast.registerListenerAndGetCurrentSelector(mockMediaRouteSelectorListener))
        .isEqualTo(expectedSelector);
  }

  private void verifyCastOptionsModifierEquals(
      CastOptions.Modifier expected, CastOptions.Modifier actual) {
    assertThat(actual.getReceiverApplicationId()).isEqualTo(expected.getReceiverApplicationId());
    assertThat(actual.getRemoteToLocalEnabled()).isEqualTo(expected.getRemoteToLocalEnabled());
    assertThat(actual.getShowSystemOutputSwitcherOnCastIconClick())
        .isEqualTo(expected.getShowSystemOutputSwitcherOnCastIconClick());
  }
}
