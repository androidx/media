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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.session.legacy.MediaSessionManager;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSession}. */
@RunWith(AndroidJUnit4.class)
public class MediaSessionUnitTest { // Avoid naming collision with session_current

  private MediaSession session;

  @Before
  public void setUp() {
    session =
        new MediaSession.Builder(
                getApplicationContext(), new TestExoPlayerBuilder(getApplicationContext()).build())
            .build();
  }

  @After
  public void tearDown() {
    session.release();
  }

  @Test
  public void isAutomotiveController_automotiveLauncher_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "com.android.car.carlauncher",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);

    assertThat(session.isAutomotiveController(createMinimalLegacyControllerInfo(remoteUserInfo)))
        .isTrue();
  }

  @Test
  public void isAutomotiveController_automotiveMedia_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "com.android.car.media",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);

    assertThat(session.isAutomotiveController(createMinimalLegacyControllerInfo(remoteUserInfo)))
        .isTrue();
  }

  @Test
  public void isAutomotiveController_automotiveMediaMedia3Version_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "com.android.car.media",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            /* trusted= */ true,
            /* cb= */ null,
            /* connectionHints= */ Bundle.EMPTY,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ true);

    assertThat(session.isAutomotiveController(controllerInfo)).isTrue();
  }

  @Test
  public void isAutomotiveController_randomPackageName_returnsFalse() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "another.package",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);

    assertThat(session.isAutoCompanionController(createMinimalLegacyControllerInfo(remoteUserInfo)))
        .isFalse();
  }

  @Test
  public void isAutoCompanionController_companionController_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "com.google.android.projection.gearhead",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);

    assertThat(session.isAutoCompanionController(createMinimalLegacyControllerInfo(remoteUserInfo)))
        .isTrue();
  }

  @Test
  public void isAutoCompanionController_randomPackage_returnsFalse() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "some.package",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);

    assertThat(session.isAutoCompanionController(createMinimalLegacyControllerInfo(remoteUserInfo)))
        .isFalse();
  }

  @Test
  public void isAutoCompanionController_media3version_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "com.google.android.projection.gearhead",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            /* trusted= */ true,
            /* cb= */ null,
            /* connectionHints= */ Bundle.EMPTY,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ true);

    assertThat(session.isAutoCompanionController(controllerInfo)).isTrue();
  }

  @Test
  public void isMediaNotificationController_applicationPackage_returnsTrue() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            getApplicationContext().getPackageName(),
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    Bundle connectionHints = new Bundle();
    connectionHints.putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            /* trusted= */ true,
            /* cb= */ null,
            connectionHints,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ true);

    assertThat(session.isMediaNotificationController(controllerInfo)).isTrue();
  }

  @Test
  public void isMediaNotificationController_randomPackage_returnsFalse() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            /* packageName= */ "some.package",
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    Bundle connectionHints = new Bundle();
    connectionHints.putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            /* trusted= */ false,
            /* cb= */ null,
            connectionHints,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ false);

    assertThat(session.isMediaNotificationController(controllerInfo)).isFalse();
  }

  @Test
  public void isMediaNotificationController_applicationPackageMissingBundle_returnsFalse() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            getApplicationContext().getPackageName(),
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            /* trusted= */ true,
            /* cb= */ null,
            /* connectionHints= */ Bundle.EMPTY,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ true);

    assertThat(session.isMediaNotificationController(controllerInfo)).isFalse();
  }

  @Test
  public void isMediaNotificationController_applicationPackageLegacyVersion_returnsFalse() {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(
            getApplicationContext().getPackageName(),
            /* pid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            /* uid= */ MediaSessionManager.RemoteUserInfo.UNKNOWN_UID);
    Bundle connectionHints = new Bundle();
    connectionHints.putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true);
    MediaSession.ControllerInfo controllerInfo =
        new MediaSession.ControllerInfo(
            remoteUserInfo,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            /* trusted= */ true,
            /* cb= */ null,
            connectionHints,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ true);

    assertThat(session.isMediaNotificationController(controllerInfo)).isFalse();
  }

  @Test
  public void getBitmapLoader_defaultConfiguration_decodesToExactLimit() throws Exception {
    int limit = MediaSession.getBitmapDimensionLimit(getApplicationContext());
    // Create an image slightly larger than the limit, which would trigger the power-of-2
    // under-shooting bug if not handled. See https://github.com/androidx/media/issues/3134.
    int imageSize = limit + 20;
    Bitmap originalBitmap = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888);
    originalBitmap.eraseColor(Color.RED);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    originalBitmap.compress(Bitmap.CompressFormat.PNG, /* quality= */ 100, outputStream);
    byte[] imageData = outputStream.toByteArray();

    BitmapLoader bitmapLoader = session.getBitmapLoader();
    Bitmap decodedBitmap = bitmapLoader.decodeBitmap(imageData).get();

    assertThat(decodedBitmap.getWidth()).isEqualTo(limit);
    assertThat(decodedBitmap.getHeight()).isEqualTo(limit);
  }

  private static MediaSession.ControllerInfo createMinimalLegacyControllerInfo(
      MediaSessionManager.RemoteUserInfo remoteUserInfo) {
    return new MediaSession.ControllerInfo(
        remoteUserInfo,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
        MediaSession.ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
        /* trusted= */ false,
        /* cb= */ null,
        /* connectionHints= */ Bundle.EMPTY,
        /* maxCommandsForMediaItems= */ 0,
        /* isPackageNameVerified= */ true);
  }
}
