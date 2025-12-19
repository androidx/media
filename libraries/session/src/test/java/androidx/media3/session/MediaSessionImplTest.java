/*
 * Copyright 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSessionImpl}. */
@RunWith(AndroidJUnit4.class)
public class MediaSessionImplTest {

  @Test
  public void createSessionUri_customSessionId_correctUriBuilt() {
    assertThat(MediaSessionImpl.createSessionUri("ai_or_die"))
        .isEqualTo(Uri.parse("androidx://media3.session/ai_or_die"));
  }

  @Test
  public void createSessionUri_defaultSessionId_correctUriBuilt() {
    assertThat(MediaSessionImpl.createSessionUri(MediaSession.DEFAULT_SESSION_ID))
        .isEqualTo(Uri.parse("androidx://media3.session/"));
  }

  @Test
  public void getSessionId_customSessionIdUri_correctSessionId() {
    assertThat(
            MediaSessionImpl.getSessionId(MediaSessionImpl.createSessionUri("old_school_coding")))
        .isEqualTo("old_school_coding");
  }

  @Test
  public void getSessionId_defaultSessionIdUri_correctSessionId() {
    assertThat(
            MediaSessionImpl.getSessionId(
                MediaSessionImpl.createSessionUri(MediaSession.DEFAULT_SESSION_ID)))
        .isEqualTo(MediaSession.DEFAULT_SESSION_ID);
  }

  @Test
  public void getSessionId_specialChars_uriEncodedAndDecoded() {
    Uri sessionUri = MediaSessionImpl.createSessionUri("/ _!-ID:");

    assertThat(sessionUri).isEqualTo(Uri.parse("androidx://media3.session/%2F%20_!-ID%3A"));
    assertThat(MediaSessionImpl.getSessionId(sessionUri)).isEqualTo("/ _!-ID:");
  }
}
