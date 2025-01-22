/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.metadata.icy;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MediaMetadata;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link IcyHeaders}. */
@RunWith(AndroidJUnit4.class)
public final class IcyHeadersTest {
  @Test
  public void populateMediaMetadata() {
    IcyHeaders headers =
        new IcyHeaders(
            /* bitrate= */ 1234,
            /* genre= */ "pop",
            /* name= */ "radio station",
            /* url= */ "url",
            /* isPublic= */ true,
            /* metadataInterval= */ 5678);
    MediaMetadata.Builder builder = new MediaMetadata.Builder();

    headers.populateMediaMetadata(builder);
    MediaMetadata mediaMetadata = builder.build();

    assertThat(mediaMetadata.station.toString()).isEqualTo("radio station");
    assertThat(mediaMetadata.genre.toString()).isEqualTo("pop");
  }
}
