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
package androidx.media3.test.proguard.minimal;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Class exercising only the core ExoPlayer functionality without optional features (e.g., without
 * setVideoEffects or extension decoders) to ensure unused classes are stripped.
 */
public final class ExoPlayerMinimalModuleProguard {

  private ExoPlayerMinimalModuleProguard() {}

  public static void createAndReleaseExoPlayer(Context context) {
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    player.setMediaItem(MediaItem.fromUri(Uri.EMPTY));
    player.prepare();
    player.release();
  }
}
