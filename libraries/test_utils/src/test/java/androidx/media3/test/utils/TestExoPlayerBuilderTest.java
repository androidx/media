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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TestExoPlayerBuilder}. */
@RunWith(AndroidJUnit4.class)
public class TestExoPlayerBuilderTest {

  @Test
  public void setPlaybackLooper_setsPlaybackLooperOnBuilder() {
    Context context = ApplicationProvider.getApplicationContext();
    HandlerThread thread = new HandlerThread("testPlaybackLooper");
    thread.start();
    Looper playbackLooper = thread.getLooper();

    TestExoPlayerBuilder builder =
        new TestExoPlayerBuilder(context).setPlaybackLooper(playbackLooper);

    assertThat(builder.getPlaybackLooper()).isEqualTo(playbackLooper);
    thread.quit();
  }

  @Test
  public void build_withCustomPlaybackLooper_playerUsesPlaybackLooper() {
    Context context = ApplicationProvider.getApplicationContext();
    HandlerThread thread = new HandlerThread("testPlaybackLooper");
    thread.start();
    Looper playbackLooper = thread.getLooper();

    ExoPlayer player = new TestExoPlayerBuilder(context).setPlaybackLooper(playbackLooper).build();

    assertThat(player.getPlaybackLooper()).isEqualTo(playbackLooper);
    player.release();
    thread.quit();
  }
}
