/*
 * Copyright (C) 2024 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkStateNotNull;

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2.ControllerCallback;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.RoutingController;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.Executor;

/** Default implementation for {@link SuitableOutputChecker}. */
@RequiresApi(35)
/* package */ final class DefaultSuitableOutputChecker implements SuitableOutputChecker {

  private static final RouteDiscoveryPreference EMPTY_DISCOVERY_PREFERENCE =
      new RouteDiscoveryPreference.Builder(
              /* preferredFeatures= */ ImmutableList.of(), /* activeScan= */ false)
          .build();

  private final MediaRouter2 router;
  private final RouteCallback routeCallback;
  private final Executor executor;

  @Nullable private ControllerCallback controllerCallback;
  private boolean isPreviousSelectedOutputSuitableForPlayback;

  public DefaultSuitableOutputChecker(Context context, Handler eventHandler) {
    router = MediaRouter2.getInstance(context);
    routeCallback = new RouteCallback() {};
    executor =
        new Executor() {
          @Override
          public void execute(Runnable command) {
            Util.postOrRun(eventHandler, command);
          }
        };
  }

  @Override
  public void enable(Callback callback) {
    router.registerRouteCallback(executor, routeCallback, EMPTY_DISCOVERY_PREFERENCE);
    controllerCallback =
        new ControllerCallback() {
          @Override
          public void onControllerUpdated(RoutingController controller) {
            boolean isCurrentSelectedOutputSuitableForPlayback =
                isSelectedOutputSuitableForPlayback();
            if (isPreviousSelectedOutputSuitableForPlayback
                != isCurrentSelectedOutputSuitableForPlayback) {
              isPreviousSelectedOutputSuitableForPlayback =
                  isCurrentSelectedOutputSuitableForPlayback;
              callback.onSelectedOutputSuitabilityChanged(
                  isCurrentSelectedOutputSuitableForPlayback);
            }
          }
        };
    router.registerControllerCallback(executor, controllerCallback);
    isPreviousSelectedOutputSuitableForPlayback = isSelectedOutputSuitableForPlayback();
  }

  @Override
  public void disable() {
    checkStateNotNull(controllerCallback, "SuitableOutputChecker is not enabled");
    router.unregisterControllerCallback(controllerCallback);
    controllerCallback = null;
    router.unregisterRouteCallback(routeCallback);
  }

  @Override
  public boolean isSelectedOutputSuitableForPlayback() {
    checkStateNotNull(controllerCallback, "SuitableOutputChecker is not enabled");
    int transferReason = router.getSystemController().getRoutingSessionInfo().getTransferReason();
    boolean wasTransferInitiatedBySelf = router.getSystemController().wasTransferInitiatedBySelf();
    for (MediaRoute2Info routeInfo : router.getSystemController().getSelectedRoutes()) {
      if (isRouteSuitableForMediaPlayback(routeInfo, transferReason, wasTransferInitiatedBySelf)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRouteSuitableForMediaPlayback(
      MediaRoute2Info routeInfo, int transferReason, boolean wasTransferInitiatedBySelf) {
    int suitabilityStatus = routeInfo.getSuitabilityStatus();

    if (suitabilityStatus == MediaRoute2Info.SUITABILITY_STATUS_SUITABLE_FOR_MANUAL_TRANSFER) {
      return (transferReason == RoutingSessionInfo.TRANSFER_REASON_SYSTEM_REQUEST
              || transferReason == RoutingSessionInfo.TRANSFER_REASON_APP)
          && wasTransferInitiatedBySelf;
    }

    return suitabilityStatus == MediaRoute2Info.SUITABILITY_STATUS_SUITABLE_FOR_DEFAULT_TRANSFER;
  }
}
