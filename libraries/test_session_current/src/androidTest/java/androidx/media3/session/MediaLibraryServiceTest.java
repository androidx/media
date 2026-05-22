/*
 * Copyright 2023 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaLibraryService}. */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class MediaLibraryServiceTest {

  @Rule public final RemoteControllerTestRule controllerTestRule = new RemoteControllerTestRule();

  private Context context;
  private SessionToken token;

  @Before
  public void setUp() {
    TestServiceRegistry.getInstance().cleanUp();
    context = ApplicationProvider.getApplicationContext();
    token =
        new SessionToken(context, new ComponentName(context, LocalMockMediaLibraryService.class));
  }

  @After
  public void cleanUp() {
    TestServiceRegistry.getInstance().cleanUp();
  }

  @Test
  public void onConnectAsync_controllerInfo_sameInstanceInOnGetSessionAndCallback()
      throws Exception {
    TestServiceRegistry testServiceRegistry = TestServiceRegistry.getInstance();
    List<MediaSession.ControllerInfo> onGetSessionControllerInfos = new ArrayList<>();
    List<MediaSession.ControllerInfo> browserCommandControllerInfos = new ArrayList<>();
    AtomicReference<MediaSession> session = new AtomicReference<>();
    testServiceRegistry.setOnGetSessionHandler(
        controllerInfo -> {
          MockMediaLibraryService service =
              (MockMediaLibraryService) testServiceRegistry.getServiceInstance();
          // The controllerInfo passed to the onGetSession of the service.
          onGetSessionControllerInfos.add(controllerInfo);
          Player player = new ExoPlayer.Builder(context).build();
          MediaLibrarySession.Callback callback =
              new MediaLibrarySession.Callback() {
                @Override
                public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                    MediaLibrarySession session,
                    MediaSession.ControllerInfo browser,
                    String mediaId) {
                  browserCommandControllerInfos.add(browser);
                  return Futures.immediateFuture(
                      LibraryResult.ofItem(
                          new MediaItem.Builder()
                              .setMediaId("media-id-321")
                              .setMediaMetadata(
                                  new MediaMetadata.Builder()
                                      .setIsPlayable(false)
                                      .setIsBrowsable(true)
                                      .build())
                              .build(),
                          /* params= */ null));
                }
              };
          session.set(new MediaLibrarySession.Builder(service, player, callback).build());
          return session.get();
        });
    // Create the remote browser to start the service.
    RemoteMediaBrowser browser =
        controllerTestRule.createRemoteBrowser(token, /* connectionHints= */ Bundle.EMPTY);
    // Get the started service instance after creation.
    MockMediaLibraryService service =
        (MockMediaLibraryService) testServiceRegistry.getServiceInstance();

    assertThat(browser.getItem("mediaId").resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS);
    browser.release();

    service.blockUntilAllControllersUnbind(TIMEOUT_MS);
    assertThat(onGetSessionControllerInfos).hasSize(1);
    assertThat(browserCommandControllerInfos).hasSize(1);
    assertThat(onGetSessionControllerInfos.get(0))
        .isSameInstanceAs(browserCommandControllerInfos.get(0));
  }

  @SdkSuppress(minSdkVersion = 30) // Emulators up to API 29 don't have bluetooth service.
  @Test
  public void mediaLibraryService_isRecognizedAsAvrcpBrowsable() throws Exception {
    UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    String packageName = context.getPackageName();

    // Toggle Bluetooth off and on to let it initialize its internal state with the test app.
    if (SDK_INT >= 32) {
      executeShellCommand(uiAutomation, "cmd bluetooth_manager disable");
      executeShellCommand(uiAutomation, "cmd bluetooth_manager wait-for-state:STATE_OFF");
      executeShellCommand(uiAutomation, "cmd bluetooth_manager enable");
      executeShellCommand(uiAutomation, "cmd bluetooth_manager wait-for-state:STATE_ON");
    } else {
      executeShellCommand(uiAutomation, "svc bluetooth disable");
      executeShellCommand(uiAutomation, "svc bluetooth enable");
    }

    // Query bluetooth_manager dumpsys to check if it lists our test package as a browser.
    boolean isListed = false;
    long timeoutMs = 20_000;
    long intervalMs = 500;
    while (timeoutMs > 0) {
      if (isPackageBrowsableInBluetoothManager(uiAutomation, packageName)) {
        isListed = true;
        break;
      }
      Thread.sleep(intervalMs);
      timeoutMs -= intervalMs;
    }
    assertThat(isListed).isTrue();
  }

  @Test
  public void mediaLibraryService_isNotListedByDefaultAsGenericAudioPlaybackApp() {
    Intent userIntent = new Intent(Intent.ACTION_VIEW);
    userIntent.addCategory(Intent.CATEGORY_DEFAULT); // Simulates a standard user "Open" action
    userIntent.setType("audio/*");
    String packageName = context.getPackageName();

    List<ResolveInfo> resolveInfos =
        context
            .getPackageManager()
            .queryIntentActivities(userIntent, PackageManager.MATCH_DEFAULT_ONLY);
    boolean isVisibleToUser = false;
    for (ResolveInfo info : resolveInfos) {
      if (info.activityInfo.packageName.equals(packageName)) {
        isVisibleToUser = true;
        break;
      }
    }

    assertThat(isVisibleToUser).isFalse();
  }

  private static boolean isPackageBrowsableInBluetoothManager(
      UiAutomation uiAutomation, String packageName) throws IOException {
    try (ParcelFileDescriptor pfd = uiAutomation.executeShellCommand("dumpsys bluetooth_manager");
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("Browsable Package") && line.contains(packageName)) {
          return true;
        }
      }
    }
    return false;
  }

  @CanIgnoreReturnValue
  private String executeShellCommand(UiAutomation uiAutomation, String command) throws IOException {
    StringBuilder output = new StringBuilder();
    try (ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(command);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }
    }
    return output.toString();
  }
}
