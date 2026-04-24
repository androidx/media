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
package androidx.media3.session;

import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.ForegroundServiceStartNotAllowedException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.MediaSessionService.Listener;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSession} with an application thread different to the main thread. */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class MediaSessionServiceWithNonMainApplicationThreadTest {

  @ClassRule public static final MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule
  public final HandlerThreadTestRule threadTestRule =
      new HandlerThreadTestRule("MediaSessionPlayRequestTest");

  @Rule public final RemoteControllerTestRule controllerTestRule = new RemoteControllerTestRule();

  private Context context;
  private SessionToken token;

  @Before
  public void setUp() {
    TestServiceRegistry.getInstance().cleanUp();
    context = ApplicationProvider.getApplicationContext();
    token =
        new SessionToken(context, new ComponentName(context, LocalMockMediaSessionService.class));
  }

  @After
  public void tearDown() {
    TestServiceRegistry.getInstance().cleanUp();
    if (context != null) {
      context.stopService(new Intent(context, LocalMockMediaSessionService.class));
    }
  }

  @Test
  public void play_playCalledOnAppThread() throws Exception {
    TestServiceRegistry testServiceRegistry = TestServiceRegistry.getInstance();
    HandlerThread appThread = new HandlerThread("AppThread");
    appThread.start();
    Looper appLooper = appThread.getLooper();

    SettableFuture<Looper> playFuture = SettableFuture.create();
    testServiceRegistry.setOnGetSessionHandler(
        controllerInfo -> {
          ExoPlayer exoPlayer =
              new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                  .setMediaSourceFactory(new FakeMediaSourceFactory())
                  .setLooper(appLooper)
                  .build();
          Player player =
              new ForwardingPlayer(exoPlayer) {
                @Override
                public void play() {
                  playFuture.set(Looper.myLooper());
                }
              };
          return new MediaSession.Builder(context, player)
              .setCallback(
                  new MediaSession.Callback() {
                    @Override
                    public ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(
                        MediaSession mediaSession,
                        ControllerInfo controller,
                        boolean isForPlayback) {
                      MediaItem item =
                          new MediaItem.Builder()
                              .setUri("https://www.example.com")
                              .setMediaId("mediaItem1")
                              .build();
                      return immediateFuture(
                          new MediaItemsWithStartPosition(
                              ImmutableList.of(item),
                              /* startIndex= */ 0,
                              /* startPositionMs= */ C.TIME_UNSET));
                    }
                  })
              .build();
        });
    RemoteMediaController controller = controllerTestRule.createRemoteController(token);

    controller.play();

    // Verify that playback starts on the application looper.
    assertThat(playFuture.get(TIMEOUT_MS, MILLISECONDS)).isEqualTo(appLooper);
    appThread.quitSafely();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31, maxSdkVersion = 32) // test sanitizing play calls: b/221413442
  public void play_throwsForegroundServiceStartNotAllowedException_exceptionListenerCalled()
      throws Exception {
    TestServiceRegistry testServiceRegistry = TestServiceRegistry.getInstance();
    HandlerThread appThread = new HandlerThread("AppThread");
    appThread.start();
    Looper appLooper = appThread.getLooper();
    // Fake throwing a ForegroundServiceStartNotAllowedException because from within the test app
    // we are always allowed to start the FGS.
    testServiceRegistry.setOnUpdateMediaNotificationAsynHandler(
        (session, startInForegroundRequired) ->
            immediateFailedFuture(
                new ForegroundServiceStartNotAllowedException(
                    "thrown by "
                        + MediaSessionServiceWithNonMainApplicationThreadTest.class.getName())));
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    testServiceRegistry.setOnGetSessionHandler(
        controllerInfo -> {
          if (sessionReference.get() != null) {
            return sessionReference.get();
          }
          ExoPlayer exoPlayer =
              new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                  .setMediaSourceFactory(new FakeMediaSourceFactory())
                  .setLooper(appLooper)
                  .build();
          MediaSession session =
              new MediaSession.Builder(context, exoPlayer)
                  .setCallback(
                      new MediaSession.Callback() {
                        @Override
                        public ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(
                            MediaSession mediaSession,
                            ControllerInfo controller,
                            boolean isForPlayback) {
                          MediaItem item =
                              new MediaItem.Builder()
                                  .setUri("https://www.example.com")
                                  .setMediaId("mediaItem1")
                                  .build();
                          return immediateFuture(
                              new MediaItemsWithStartPosition(
                                  ImmutableList.of(item),
                                  /* startIndex= */ 0,
                                  /* startPositionMs= */ C.TIME_UNSET));
                        }
                      })
                  .build();
          sessionReference.set(session);
          return session;
        });
    RemoteMediaController controller = controllerTestRule.createRemoteController(token);
    MediaSessionService serviceInstance = testServiceRegistry.getServiceInstance();
    CountDownLatch foregroundServiceStartNotAllowedExceptionLatch =
        new CountDownLatch(/* count= */ 1);
    serviceInstance.setListener(
        new Listener() {
          @Override
          public void onForegroundServiceStartNotAllowedException() {
            foregroundServiceStartNotAllowedExceptionLatch.countDown();
          }
        });

    controller.play();

    assertThat(foregroundServiceStartNotAllowedExceptionLatch.await(TIMEOUT_MS, MILLISECONDS))
        .isTrue();
    appThread.quitSafely();
  }

  @Test
  @SdkSuppress(minSdkVersion = 31, maxSdkVersion = 32)
  public void play_startsForegroundService_playCalledOnAppThread() throws Exception {
    TestServiceRegistry testServiceRegistry = TestServiceRegistry.getInstance();
    HandlerThread appThread = new HandlerThread("AppThread");
    appThread.start();
    Looper appLooper = appThread.getLooper();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    SettableFuture<Looper> playFuture = SettableFuture.create();
    testServiceRegistry.setOnGetSessionHandler(
        controllerInfo -> {
          if (sessionReference.get() != null) {
            return sessionReference.get();
          }
          ExoPlayer exoPlayer =
              new ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                  .setMediaSourceFactory(new FakeMediaSourceFactory())
                  .setLooper(appLooper)
                  .build();
          Player player =
              new ForwardingPlayer(exoPlayer) {
                @Override
                public void play() {
                  playFuture.set(Looper.myLooper());
                }
              };
          MediaSession session =
              new MediaSession.Builder(context, player)
                  .setCallback(
                      new MediaSession.Callback() {
                        @Override
                        public ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(
                            MediaSession mediaSession,
                            ControllerInfo controller,
                            boolean isForPlayback) {
                          MediaItem item =
                              new MediaItem.Builder()
                                  .setUri("https://www.example.com")
                                  .setMediaId("mediaItem1")
                                  .build();
                          return immediateFuture(
                              new MediaItemsWithStartPosition(
                                  ImmutableList.of(item),
                                  /* startIndex= */ 0,
                                  /* startPositionMs= */ C.TIME_UNSET));
                        }
                      })
                  .build();
          sessionReference.set(session);
          return session;
        });
    RemoteMediaController controller = controllerTestRule.createRemoteController(token);

    controller.play();

    // Verify that playback starts on application looper after FGS initialized successfully.
    assertThat(playFuture.get(TIMEOUT_MS, MILLISECONDS)).isEqualTo(appLooper);
    appThread.quitSafely();
  }
}
