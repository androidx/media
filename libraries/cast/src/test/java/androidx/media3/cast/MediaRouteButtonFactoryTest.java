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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.mediarouter.app.MediaRouteActionProvider;
import androidx.mediarouter.app.MediaRouteButton;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
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
import org.robolectric.fakes.RoboMenu;

/** Tests for {@link MediaRouteButtonFactory}. */
@RunWith(AndroidJUnit4.class)
public final class MediaRouteButtonFactoryTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final int MENU_ITEM_ID = 123;

  @Mock private CastContext mockCastContext;
  @Mock private SessionManager mockSessionManager;
  @Mock private CastContextWrapper.CastContextInitializer mockCastContextInitializer;
  @Mock private Task<CastContext> mockCastContextTask;

  @Captor private ArgumentCaptor<OnCompleteListener<CastContext>> completionListenerCaptor;

  private Context context;
  private MediaRouteSelector mediaRouteSelector;
  private CastContextWrapper castContextWrapper;

  @Before
  public void setUp() {
    context =
        new ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_TestAppTheme);
    mediaRouteSelector = new MediaRouteSelector.Builder().addControlCategory("category").build();
    castContextWrapper = CastContextWrapper.getSingletonInstance();
    when(mockCastContextInitializer.init()).thenReturn(mockCastContextTask);
    when(mockCastContext.getSessionManager()).thenReturn(mockSessionManager);
    when(mockCastContext.getMergedSelector()).thenReturn(mediaRouteSelector);
  }

  @After
  public void tearDown() {
    CastContextWrapper.reset();
  }

  @Test
  public void setUpMediaRouteButton_withoutMenuItem_throwsExceptionWithListenableFuture() {
    castContextWrapper.initWithContext(mockCastContext);
    RoboMenu menu = new RoboMenu(context);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> MediaRouteButtonFactory.setUpMediaRouteButton(context, menu, MENU_ITEM_ID));

    assertThat(thrown)
        .hasMessageThat()
        .contains(MediaRouteButtonFactory.MESSAGE_FAILED_TO_GET_MENU_ITEM);
  }

  @Test
  public void setUpMediaRouteButton_withoutActionProvider_throwsExceptionWithListenableFuture() {
    castContextWrapper.initWithContext(mockCastContext);
    RoboMenu menu = new RoboMenu(context);
    var unused =
        menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, R.string.media_route_button_menu_title);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> MediaRouteButtonFactory.setUpMediaRouteButton(context, menu, MENU_ITEM_ID));
    assertThat(thrown)
        .hasMessageThat()
        .contains(MediaRouteButtonFactory.MESSAGE_FAILED_TO_GET_MEDIA_ROUTE_ACTION_PROVIDER);
  }

  @Test
  public void setUpMediaRouteButton_withMenuItem_setSelector() throws Exception {
    castContextWrapper.initWithContext(mockCastContext);
    RoboMenu menu = new RoboMenu(context);
    MenuItem menuItem =
        menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, R.string.media_route_button_menu_title);
    MediaRouteActionProvider mediaRouteActionProvider = new MediaRouteActionProvider(context);

    // RoboMenu doesn't support setting a non-null MediaRouteActionProvider in the menu item. So we
    // use the setUpMediaRouteButton() method that accepts the MediaRouteActionProvider directly.
    ListenableFuture<MenuItem> future =
        MediaRouteButtonFactory.setUpMediaRouteButton(
            context, menuItem, mediaRouteActionProvider, MENU_ITEM_ID);

    assertThat(future.get()).isEqualTo(menuItem);
    assertThat(mediaRouteActionProvider.getRouteSelector()).isEqualTo(mediaRouteSelector);
  }

  @Test
  public void setUpMediaRouteButton_withMenuItem_onBackgroundThread_throwsException()
      throws Exception {
    castContextWrapper.initWithContext(mockCastContext);
    RoboMenu menu = new RoboMenu(context);
    MenuItem menuItem =
        menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, R.string.media_route_button_menu_title);
    MediaRouteActionProvider mediaRouteActionProvider = new MediaRouteActionProvider(context);
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    BackgroundExecutor.get()
        .execute(
            () -> {
              try {
                var unused =
                    MediaRouteButtonFactory.setUpMediaRouteButton(
                        context, menuItem, mediaRouteActionProvider, MENU_ITEM_ID);
              } catch (Throwable t) {
                exceptionRef.set(t);
              } finally {
                latch.countDown();
              }
            });
    assertThat(latch.await(5, SECONDS)).isTrue();

    Throwable thrown = exceptionRef.get();
    assertThat(thrown).isNotNull();
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void setUpMediaRouteButton_withFailedInit_throwsExceptionWithListenableFuture() {
    castContextWrapper.asyncInit(mockCastContextInitializer);
    verify(mockCastContextTask).addOnCompleteListener(completionListenerCaptor.capture());
    OnCompleteListener<CastContext> listener = completionListenerCaptor.getValue();
    Exception exception = new RuntimeException("Failed to load");
    when(mockCastContextTask.isSuccessful()).thenReturn(false);
    when(mockCastContextTask.getException()).thenReturn(exception);
    listener.onComplete(mockCastContextTask);
    assertThat(castContextWrapper.getCastContextLoadFailure()).isEqualTo(exception);

    MediaRouteButton mediaRouteButton = new MediaRouteButton(context);
    ListenableFuture<Void> future =
        MediaRouteButtonFactory.setUpMediaRouteButton(context, mediaRouteButton);

    ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get());
    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains(MediaRouteButtonFactory.MESSAGE_FAILED_TO_GET_SELECTOR);
  }

  @Test
  public void setUpMediaRouteButton_withNullButton_throwsExceptionWithListenableFuture() {
    castContextWrapper.initWithContext(mockCastContext);

    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> MediaRouteButtonFactory.setUpMediaRouteButton(context, /* button= */ null));
    assertThat(thrown)
        .hasMessageThat()
        .contains(MediaRouteButtonFactory.MESSAGE_FAILED_WITH_NULL_MEDIA_ROUTE_BUTTON);
  }

  @Test
  public void setUpMediaRouteButton_withSuccessfulInit_setsSelector() throws Exception {
    castContextWrapper.initWithContext(mockCastContext);

    MediaRouteButton mediaRouteButton = new MediaRouteButton(context);
    ListenableFuture<Void> future =
        MediaRouteButtonFactory.setUpMediaRouteButton(context, mediaRouteButton);

    future.get(); // Wait for completion.
    assertThat(mediaRouteButton.getRouteSelector()).isEqualTo(mediaRouteSelector);
  }

  @Test
  public void setUpMediaRouteButton_onBackgroundThread_throwsException() throws Exception {
    castContextWrapper.initWithContext(mockCastContext);
    MediaRouteButton mediaRouteButton = new MediaRouteButton(context);
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    BackgroundExecutor.get()
        .execute(
            () -> {
              try {
                var unused =
                    MediaRouteButtonFactory.setUpMediaRouteButton(context, mediaRouteButton);
              } catch (Throwable t) {
                exceptionRef.set(t);
              } finally {
                latch.countDown();
              }
            });
    assertThat(latch.await(5, SECONDS)).isTrue();

    Throwable thrown = exceptionRef.get();
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
  }
}
