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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;

import android.util.Rational;
import androidx.media3.common.MediaItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link Composition}. */
@RunWith(AndroidJUnit4.class)
public class CompositionTest {

  @Test
  public void builder_setsAllParameters() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri("uri")).build();
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(), /* videoEffects= */ ImmutableList.of());
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(editedMediaItem)))
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .experimentalSetRetainHdrFromUltraHdrImage(true)
            .setEffects(effects)
            .setVideoFrameAggregationParameters(parameters)
            .build();

    assertThat(composition.transmuxAudio).isTrue();
    assertThat(composition.transmuxVideo).isTrue();
    assertThat(composition.hdrMode).isEqualTo(Composition.HDR_MODE_KEEP_HDR);
    assertThat(composition.retainHdrFromUltraHdrImage).isTrue();
    assertThat(composition.effects).isEqualTo(effects);
    assertThat(composition.videoFrameAggregationParameters).isEqualTo(parameters);
  }

  @Test
  public void buildUpon_preservesAllParameters() {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri("uri")).build();
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(), /* videoEffects= */ ImmutableList.of());
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(editedMediaItem)))
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .experimentalSetRetainHdrFromUltraHdrImage(true)
            .setEffects(effects)
            .setVideoFrameAggregationParameters(parameters)
            .build();

    Composition newComposition = composition.buildUpon().build();

    assertThat(newComposition.transmuxAudio).isTrue();
    assertThat(newComposition.transmuxVideo).isTrue();
    assertThat(newComposition.hdrMode).isEqualTo(Composition.HDR_MODE_KEEP_HDR);
    assertThat(newComposition.retainHdrFromUltraHdrImage).isTrue();
    assertThat(newComposition.effects).isEqualTo(effects);
    assertThat(newComposition.videoFrameAggregationParameters).isEqualTo(parameters);
  }

  @Test
  public void toJsonObject_containsAllParameters() throws Exception {
    EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(MediaItem.fromUri("uri")).build();
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();
    Effects effects =
        new Effects(
            /* audioProcessors= */ ImmutableList.of(), /* videoEffects= */ ImmutableList.of());
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(editedMediaItem)))
            .setTransmuxAudio(true)
            .setTransmuxVideo(true)
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .experimentalSetRetainHdrFromUltraHdrImage(true)
            .setEffects(effects)
            .setVideoFrameAggregationParameters(parameters)
            .build();

    JSONObject jsonObject = composition.toJsonObject();

    assertThat(jsonObject.getBoolean("transmuxAudio")).isTrue();
    assertThat(jsonObject.getBoolean("transmuxVideo")).isTrue();
    assertThat(jsonObject.getInt("hdrMode")).isEqualTo(Composition.HDR_MODE_KEEP_HDR);
    assertThat(jsonObject.getBoolean("retainHdrFromUltraHdrImage")).isTrue();
    assertThat(jsonObject.has("effects")).isTrue();
    assertThat(jsonObject.getJSONObject("videoFrameAggregationParameters").getString("frameRate"))
        .isEqualTo("60/1");
  }
}
