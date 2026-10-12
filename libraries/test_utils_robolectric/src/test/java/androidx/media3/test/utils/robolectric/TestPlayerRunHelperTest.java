/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.test.utils.robolectric;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TestPlayerRunHelper}. */
@RunWith(AndroidJUnit4.class)
public final class TestPlayerRunHelperTest {

  private Context context;
  private ExoPlayer player;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    player = new TestExoPlayerBuilder(context).build();
  }

  @After
  public void tearDown() {
    player.release();
  }

  @Test
  public void untilBackgroundThreadCondition_afterTimeout_stopsPollingCondition() throws Exception {
    AtomicInteger conditionCheckCount = new AtomicInteger();
    // The timeout is measured in real time, so keep it short.
    assertThrows(
        TimeoutException.class,
        () ->
            advance(player)
                .withTimeoutMs(100)
                .untilBackgroundThreadCondition(
                    () -> {
                      conditionCheckCount.incrementAndGet();
                      return false;
                    }));
    int conditionCheckCountAfterTimeout = conditionCheckCount.get();
    assertThat(conditionCheckCountAfterTimeout).isGreaterThan(0);

    // Keep running the main looper with an auto-advancing clock. A polling runnable that was left
    // behind by the timed out call would be invoked again on every clock advancement.
    player.setMediaSource(new FakeMediaSource(new FakeTimeline()));
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    assertThat(conditionCheckCount.get()).isEqualTo(conditionCheckCountAfterTimeout);
  }
}
