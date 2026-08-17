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
package androidx.media3.exoplayer;

import static org.mockito.Mockito.mock;

import androidx.media3.common.Player;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test that some interfaces can be mocked with Mockito without using Robolectric.
 *
 * <p>This is a <a
 * href="https://developer.android.com/training/testing/local-tests#mocking-dependencies">recommended
 * approach</a> for apps that only have incidental use of an interface for some of their tests, to
 * avoid the full bloat of Robolectric.
 */
// This test only checks that mocks can be created successfully, it doesn't use them.
@RunWith(JUnit4.class)
@SuppressWarnings("MockNotUsedInProduction")
public final class NonRobolectricJvmMockTest {

  @Test
  public void player() {
    Player unusedMockPlayer = mock(Player.class);
  }

  @Test
  public void exoplayer() {
    ExoPlayer unusedMockExoplayer = mock(ExoPlayer.class);
  }
}
