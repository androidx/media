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
package androidx.media3.session.legacy;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.provider.Settings;
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link MediaSessionManager}. */
@RunWith(AndroidJUnit4.class)
public class MediaSessionManagerTest {

  private static final String NOTIFICATION_LISTENER_PACKAGE = "com.example.notificationlistener";
  private static final String MEDIA_CONTROL_PACKAGE = "com.example.mediacontrol";

  private Application context;
  private MediaSessionManager sessionManager;
  private int notificationListenerUid;
  private int mediaControlUid;
  private int otherAppUid;
  private int untrustedUid;

  @Before
  public void setUp() {
    context = getApplicationContext();
    sessionManager = MediaSessionManager.getSessionManager(context);
    notificationListenerUid = getOtherApplicationUid(/* offset= */ 1);
    mediaControlUid = getOtherApplicationUid(/* offset= */ 2);
    otherAppUid = getOtherApplicationUid(/* offset= */ 3);
    untrustedUid = getOtherApplicationUid(/* offset= */ 4);
  }

  @Test
  public void isTrustedForMediaControl_currentAppWithMatchingUid_returnsTrue() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(context.getPackageName(), Process.myPid(), Process.myUid());

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_currentAppPackageWithMismatchedUid_returnsFalse() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(context.getPackageName(), /* pid= */ 1234, otherAppUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_currentAppUidWithDifferentPackage_returnsTrue() {
    installPackage(/* packageName= */ "com.example.other", otherAppUid);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            /* packageName= */ "com.example.other", Process.myPid(), Process.myUid());

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_currentAppUidWithUnresolvablePackage_returnsTrue() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            /* packageName= */ "com.example.invisible", Process.myPid(), Process.myUid());

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_systemUidWithMatchingPackage_returnsTrue() {
    installPackage(/* packageName= */ "android", Process.SYSTEM_UID);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(/* packageName= */ "android", /* pid= */ 1000, Process.SYSTEM_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_systemUidWithDifferentPackage_returnsTrue() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(context.getPackageName(), /* pid= */ 1000, Process.SYSTEM_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_systemUidWithUnresolvablePackage_returnsTrue() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(RemoteUserInfo.LEGACY_CONTROLLER, /* pid= */ 1000, Process.SYSTEM_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void
      isTrustedForMediaControl_mediaContentControlGrantedForPidAndUidWithDifferentPackage_returnsTrue() {
    shadowOf(context)
        .grantPermissions(
            /* pid= */ 1234, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    RemoteUserInfo userInfo =
        new RemoteUserInfo(context.getPackageName(), /* pid= */ 1234, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void
      isTrustedForMediaControl_mediaContentControlGrantedForPidAndUidWithUnresolvablePackage_returnsTrue() {
    shadowOf(context)
        .grantPermissions(
            /* pid= */ 1234, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, /* pid= */ 1234, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void
      isTrustedForMediaControl_mediaContentControlGrantedWithUnknownPidAndMatchingUid_returnsTrue() {
    installPackage(
        MEDIA_CONTROL_PACKAGE, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, RemoteUserInfo.UNKNOWN_PID, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void
      isTrustedForMediaControl_mediaContentControlGrantedWithUnknownPidAndMismatchedUid_returnsFalse() {
    installPackage(
        MEDIA_CONTROL_PACKAGE, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, RemoteUserInfo.UNKNOWN_PID, otherAppUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  @Config(minSdk = 36)
  public void isTrustedForMediaControl_executeAppFunctionsGrantedForPidAndUid_returnsTrue() {
    shadowOf(context)
        .grantPermissions(
            /* pid= */ 1234, mediaControlUid, android.Manifest.permission.EXECUTE_APP_FUNCTIONS);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, /* pid= */ 1234, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(maxSdk = 35)
  public void isTrustedForMediaControl_executeAppFunctionsGrantedBelowApi36_returnsFalse() {
    shadowOf(context)
        .grantPermissions(
            /* pid= */ 1234, mediaControlUid, android.Manifest.permission.EXECUTE_APP_FUNCTIONS);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, /* pid= */ 1234, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  @Config(minSdk = 36)
  public void
      isTrustedForMediaControl_executeAppFunctionsGrantedWithUnknownPidAndMatchingUid_returnsTrue() {
    installPackage(
        MEDIA_CONTROL_PACKAGE, mediaControlUid, android.Manifest.permission.EXECUTE_APP_FUNCTIONS);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, RemoteUserInfo.UNKNOWN_PID, mediaControlUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(minSdk = 36)
  public void
      isTrustedForMediaControl_executeAppFunctionsGrantedWithUnknownPidAndMismatchedUid_returnsFalse() {
    installPackage(
        MEDIA_CONTROL_PACKAGE, mediaControlUid, android.Manifest.permission.EXECUTE_APP_FUNCTIONS);
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, RemoteUserInfo.UNKNOWN_PID, otherAppUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_enabledNotificationListenerWithMatchingUid_returnsTrue() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, notificationListenerUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void isTrustedForMediaControl_enabledNotificationListenerWithMismatchedUid_returnsFalse() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, otherAppUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_multiplePackagesSharingUid_returnsTrue() {
    int sharedUid = getOtherApplicationUid(/* offset= */ 5);
    installPackage(/* packageName= */ "com.example.shared1", sharedUid);
    installPackage(NOTIFICATION_LISTENER_PACKAGE, sharedUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, sharedUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(sdk = 27)
  public void isTrustedForMediaControl_apiBelow28WithUnknownUidAndCurrentAppPackage_returnsTrue() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            context.getPackageName(), RemoteUserInfo.UNKNOWN_PID, RemoteUserInfo.UNKNOWN_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(sdk = 27)
  public void
      isTrustedForMediaControl_apiBelow28WithUnknownUidAndMediaContentControlGrantedForPid_returnsTrue() {
    installPackage(MEDIA_CONTROL_PACKAGE, mediaControlUid);
    shadowOf(context)
        .grantPermissions(
            /* pid= */ 1234, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, /* pid= */ 1234, RemoteUserInfo.UNKNOWN_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(sdk = 27)
  public void
      isTrustedForMediaControl_apiBelow28WithUnknownUidAndEnabledNotificationListener_returnsTrue() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, RemoteUserInfo.UNKNOWN_PID, RemoteUserInfo.UNKNOWN_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isTrue();
  }

  @Test
  @Config(sdk = 27)
  public void isTrustedForMediaControl_apiBelow28WithKnownMismatchedUid_returnsFalse() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(context.getPackageName(), /* pid= */ 1234, otherAppUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  @Config(sdk = 28)
  public void isTrustedForMediaControl_api28WithUnknownUid_returnsFalse() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            context.getPackageName(), RemoteUserInfo.UNKNOWN_PID, RemoteUserInfo.UNKNOWN_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void
      isTrustedForMediaControl_unresolvableEnabledNotificationListenerWithVerifiedPackage_returnsTrue() {
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, notificationListenerUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isTrue();
  }

  @Test
  public void
      isTrustedForMediaControl_unresolvableEnabledNotificationListenerWithUnverifiedPackage_returnsFalse() {
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, notificationListenerUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void
      isTrustedForMediaControl_unresolvableEnabledNotificationListenerInDifferentUserWithVerifiedPackage_returnsFalse() {
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    int otherUserUid = 10 * 100_000 + notificationListenerUid;
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, otherUserUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void
      isTrustedForMediaControl_unresolvableNonNotificationListenerWithVerifiedPackage_returnsFalse() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            /* packageName= */ "com.example.untrusted", /* pid= */ 1234, untrustedUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void
      isTrustedForMediaControl_mediaContentControlGrantedWithUnknownPidInDifferentUserAndVerifiedPackage_returnsFalse() {
    installPackage(
        MEDIA_CONTROL_PACKAGE, mediaControlUid, "android.permission.MEDIA_CONTENT_CONTROL");
    int otherUserUid = 10 * 100_000 + mediaControlUid;
    RemoteUserInfo userInfo =
        new RemoteUserInfo(MEDIA_CONTROL_PACKAGE, RemoteUserInfo.UNKNOWN_PID, otherUserUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_unknownUidWithVerifiedPackage_returnsFalse() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, RemoteUserInfo.UNKNOWN_UID);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_negativeUidWithVerifiedPackage_returnsFalse() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, /* uid= */ -2);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ true);

    assertThat(isTrusted).isFalse();
  }

  @Test
  @Config(sdk = 27)
  public void isTrustedForMediaControl_apiBelow28WithNegativeNonUnknownUid_returnsFalse() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    RemoteUserInfo userInfo =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, /* pid= */ 1234, /* uid= */ -2);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  @Test
  public void isTrustedForMediaControl_nonExistentPackage_returnsFalse() {
    RemoteUserInfo userInfo =
        new RemoteUserInfo(
            /* packageName= */ "com.example.nonexistent", /* pid= */ 1234, untrustedUid);

    boolean isTrusted =
        sessionManager.isTrustedForMediaControl(userInfo, /* isPackageNameVerified= */ false);

    assertThat(isTrusted).isFalse();
  }

  private static int getOtherApplicationUid(int offset) {
    int appUidCount = Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID + 1;
    return Process.FIRST_APPLICATION_UID
        + (Process.myUid() - Process.FIRST_APPLICATION_UID + offset) % appUidCount;
  }

  private void installPackage(String packageName, int uid, String... grantedPermissions) {
    ApplicationInfo applicationInfo = new ApplicationInfo();
    applicationInfo.packageName = packageName;
    applicationInfo.uid = uid;
    PackageInfo packageInfo = new PackageInfo();
    packageInfo.packageName = packageName;
    packageInfo.applicationInfo = applicationInfo;
    if (grantedPermissions.length > 0) {
      packageInfo.requestedPermissions = grantedPermissions;
      packageInfo.requestedPermissionsFlags = new int[grantedPermissions.length];
      Arrays.fill(
          packageInfo.requestedPermissionsFlags,
          0,
          grantedPermissions.length,
          PackageInfo.REQUESTED_PERMISSION_GRANTED);
    }
    shadowOf(context.getPackageManager()).installPackage(packageInfo);
  }
}
