/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.SpeedParameters;
import androidx.media3.common.audio.SpeedProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link EditedMediaItem}. */
@RunWith(AndroidJUnit4.class)
public class EditedMediaItemTest {

  @Test
  public void toJsonObject_editedMediaItem_containsAllParameters() throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri("uri.mp4"))
            .setRemoveAudio(true)
            .setRemoveVideo(false)
            .setFlattenForSlowMotion(true)
            .setDurationUs(1_000_000L)
            .setFrameRate(30)
            .setSpeed(
                new SpeedParameters(
                    new SpeedProvider() {
                      @Override
                      public float getSpeed(long timeUs) {
                        return 2.0f;
                      }

                      @Override
                      public long getNextSpeedChangeTimeUs(long timeUs) {
                        return C.TIME_UNSET;
                      }
                    },
                    /* shouldMaintainPitch= */ true))
            .build();

    JSONObject jsonObject = editedMediaItem.toJsonObject();

    assertThat(jsonObject.getBoolean("removeAudio")).isTrue();
    assertThat(jsonObject.getBoolean("removeVideo")).isFalse();
    assertThat(jsonObject.getBoolean("flattenForSlowMotion")).isTrue();
    assertThat(jsonObject.getLong("durationUs")).isEqualTo(1_000_000L);
    assertThat(jsonObject.getInt("frameRate")).isEqualTo(30);
    assertThat(jsonObject.has("speedParameters")).isTrue();
    assertThat(jsonObject.getJSONObject("speedParameters").getBoolean("shouldMaintainPitch"))
        .isTrue();
    assertThat(jsonObject.getLong("presentationDuration")).isEqualTo(500_000L);
  }

  @Test
  public void toJsonObject_withDefaultParameters_returnsJsonObjectWithDefaultFields()
      throws Exception {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri("uri.mp4")).build();

    JSONObject jsonObject = editedMediaItem.toJsonObject();

    assertThat(jsonObject.getBoolean("removeAudio")).isFalse();
    assertThat(jsonObject.getBoolean("removeVideo")).isFalse();
    assertThat(jsonObject.getBoolean("flattenForSlowMotion")).isFalse();
    assertThat(jsonObject.getString("frameRate")).isEqualTo("UNSET");
    assertThat(jsonObject.has("speedParameters")).isFalse();
  }
}
