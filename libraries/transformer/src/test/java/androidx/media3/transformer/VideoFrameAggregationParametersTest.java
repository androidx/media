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
import static org.junit.Assert.assertThrows;

import android.util.Rational;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoFrameAggregationParameters}. */
@RunWith(AndroidJUnit4.class)
public class VideoFrameAggregationParametersTest {

  @Test
  public void builder_setFrameRate_setsFrameRate() {
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();

    assertThat(parameters.frameRate).isEqualTo(new Rational(60, 1));
  }

  @Test
  public void builder_unsetFrameRate_setsToNull() {
    VideoFrameAggregationParameters.Builder builder = new VideoFrameAggregationParameters.Builder();
    builder.setFrameRate(new Rational(60, 1));
    builder.setFrameRate(null);

    VideoFrameAggregationParameters parameters = builder.build();

    assertThat(parameters.frameRate).isNull();
  }

  @Test
  public void builder_setFrameRate_withZero_throwsIllegalArgumentException() {
    VideoFrameAggregationParameters.Builder builder = new VideoFrameAggregationParameters.Builder();
    Rational zeroFrameRate = new Rational(0, 1);

    assertThrows(IllegalArgumentException.class, () -> builder.setFrameRate(zeroFrameRate));
  }

  @Test
  public void builder_setFrameRate_withNegative_throwsIllegalArgumentException() {
    VideoFrameAggregationParameters.Builder builder = new VideoFrameAggregationParameters.Builder();
    Rational negativeFrameRate = new Rational(-30, 1);

    assertThrows(IllegalArgumentException.class, () -> builder.setFrameRate(negativeFrameRate));
  }

  @Test
  public void build_withoutSetters_returnsDefaultValues() {
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().build();

    assertThat(parameters).isEqualTo(VideoFrameAggregationParameters.DEFAULT);
    assertThat(parameters.frameRate).isNull();
  }

  @Test
  public void buildUpon_createsEqualInstance() {
    VideoFrameAggregationParameters parameters =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();

    assertThat(parameters.buildUpon().build()).isEqualTo(parameters);
  }

  @Test
  public void equals_identicalParameters_returnsTrue() {
    VideoFrameAggregationParameters params1 =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(30, 1)).build();
    VideoFrameAggregationParameters params2 =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(30, 1)).build();

    assertThat(params1).isEqualTo(params2);
    assertThat(params1.hashCode()).isEqualTo(params2.hashCode());
  }

  @Test
  public void equals_differentParameters_returnsFalse() {
    VideoFrameAggregationParameters params1 =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(30, 1)).build();
    VideoFrameAggregationParameters params2 =
        new VideoFrameAggregationParameters.Builder().setFrameRate(new Rational(60, 1)).build();

    assertThat(params1).isNotEqualTo(params2);
  }
}
