/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.annotation.RestrictTo.Scope.LIBRARY;

import android.media.VolumeProvider;
import android.os.Build;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Handles requests to adjust or set the volume on a session. This is also used to push volume
 * updates back to the session after a request has been handled. You can set a volume provider on a
 * session by calling {@link MediaSessionCompat#setPlaybackToRemote}.
 */
@RestrictTo(LIBRARY)
public abstract class VolumeProviderCompat {

  /** */
  @IntDef({VOLUME_CONTROL_FIXED, VOLUME_CONTROL_RELATIVE, VOLUME_CONTROL_ABSOLUTE})
  @Retention(RetentionPolicy.SOURCE)
  public @interface ControlType {}

  /** The volume is fixed and can not be modified. Requests to change volume should be ignored. */
  public static final int VOLUME_CONTROL_FIXED = 0;

  /**
   * The volume control uses relative adjustment via {@link #onAdjustVolume(int)}. Attempts to set
   * the volume to a specific value should be ignored.
   */
  public static final int VOLUME_CONTROL_RELATIVE = 1;

  /**
   * The volume control uses an absolute value. It may be adjusted using {@link
   * #onAdjustVolume(int)} or set directly using {@link #onSetVolumeTo(int)}.
   */
  public static final int VOLUME_CONTROL_ABSOLUTE = 2;

  private final int controlType;
  private final int maxVolume;
  @Nullable private final String controlId;
  private int currentVolume;
  @Nullable private Callback callback;

  @Nullable private VolumeProvider volumeProviderFwk;

  /**
   * Create a new volume provider for handling volume events. You must specify the type of volume
   * control and the maximum volume that can be used.
   *
   * @param volumeControl The method for controlling volume that is used by this provider.
   * @param maxVolume The maximum allowed volume.
   * @param currentVolume The current volume.
   */
  public VolumeProviderCompat(@ControlType int volumeControl, int maxVolume, int currentVolume) {
    this(volumeControl, maxVolume, currentVolume, null);
  }

  /**
   * Create a new volume provider for handling volume events. You must specify the type of volume
   * control and the maximum volume that can be used.
   *
   * @param volumeControl The method for controlling volume that is used by this provider.
   * @param maxVolume The maximum allowed volume.
   * @param currentVolume The current volume.
   * @param volumeControlId The volume control ID of this provider.
   */
  public VolumeProviderCompat(
      @ControlType int volumeControl,
      int maxVolume,
      int currentVolume,
      @Nullable String volumeControlId) {
    controlType = volumeControl;
    this.maxVolume = maxVolume;
    this.currentVolume = currentVolume;
    controlId = volumeControlId;
  }

  /**
   * Get the maximum volume this provider allows.
   *
   * @return The max allowed volume.
   */
  public final int getMaxVolume() {
    return maxVolume;
  }

  /**
   * Set the current volume and notify the system that the volume has been changed.
   *
   * @param currentVolume The current volume of the output.
   */
  public final void setCurrentVolume(int currentVolume) {
    this.currentVolume = currentVolume;
    VolumeProvider volumeProviderFwk = (VolumeProvider) getVolumeProvider();
    volumeProviderFwk.setCurrentVolume(currentVolume);
    if (callback != null) {
      callback.onVolumeChanged(this);
    }
  }

  /**
   * Override to handle requests to set the volume of the current output.
   *
   * @param volume The volume to set the output to.
   */
  public void onSetVolumeTo(int volume) {}

  /**
   * Override to handle requests to adjust the volume of the current output.
   *
   * @param direction The direction to adjust the volume in.
   */
  public void onAdjustVolume(int direction) {}

  /**
   * Sets a callback to receive volume changes.
   *
   * <p>Used internally by the support library.
   */
  public void setCallback(@Nullable Callback callback) {
    this.callback = callback;
  }

  /**
   * Gets the underlying framework {@link android.media.VolumeProvider} object.
   *
   * <p>This method is only supported on API 21+.
   *
   * @return An equivalent {@link android.media.VolumeProvider} object, or null if none.
   */
  public Object getVolumeProvider() {
    if (volumeProviderFwk == null) {
      if (Build.VERSION.SDK_INT >= 30) {
        volumeProviderFwk =
            new VolumeProvider(controlType, maxVolume, currentVolume, controlId) {
              @Override
              public void onSetVolumeTo(int volume) {
                VolumeProviderCompat.this.onSetVolumeTo(volume);
              }

              @Override
              public void onAdjustVolume(int direction) {
                VolumeProviderCompat.this.onAdjustVolume(direction);
              }
            };
      } else {
        volumeProviderFwk =
            new VolumeProvider(controlType, maxVolume, currentVolume) {
              @Override
              public void onSetVolumeTo(int volume) {
                VolumeProviderCompat.this.onSetVolumeTo(volume);
              }

              @Override
              public void onAdjustVolume(int direction) {
                VolumeProviderCompat.this.onAdjustVolume(direction);
              }
            };
      }
    }
    return volumeProviderFwk;
  }

  /** Listens for changes to the volume. */
  public abstract static class Callback {
    public abstract void onVolumeChanged(VolumeProviderCompat volumeProvider);
  }
}
