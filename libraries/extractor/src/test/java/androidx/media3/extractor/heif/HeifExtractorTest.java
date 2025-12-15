/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.extractor.heif;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link HeifExtractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class HeifExtractorTest {

  @Test
  public void sampleStillPhoto_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new HeifExtractor(HeifExtractor.FLAG_READ_IMAGE),
        "media/heif/sample_still_photo.heic",
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDumpFilesPrefix(
                "extractordumps/heif/sample_still_photo.heic_HeifExtractor.FLAG_READ_IMAGE")
            .build(),
        simulationConfig);
  }

  @Test
  public void sampleMotionPhoto_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new HeifExtractor(HeifExtractor.FLAG_READ_IMAGE),
        "media/heif/sample_MP.heic",
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDumpFilesPrefix("extractordumps/heif/sample_MP.heic_HeifExtractor.FLAG_READ_IMAGE")
            .build(),
        simulationConfig);
  }

  @Test
  public void sampleMotionPhoto_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        HeifExtractor::new, "media/heif/sample_MP.heic", simulationConfig);
  }

  @Test
  public void sniff_onMotionPhotoWithDefaultFlags_returnsTrue() throws Exception {
    HeifExtractor extractor = new HeifExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(), "media/heif/sample_MP.heic"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onMotionPhotoWithReadImageFlag_returnsTrue() throws Exception {
    HeifExtractor extractor = new HeifExtractor(HeifExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(), "media/heif/sample_MP.heic"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onStillPhotoWithDefaultFlags_returnsFalse() throws Exception {
    HeifExtractor extractor = new HeifExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/heif/sample_still_photo.heic"))
            .build();

    assertThat(extractor.sniff(input)).isFalse();
  }

  @Test
  public void sniff_onStillPhotoWithReadImageFlag_returnsTrue() throws Exception {
    HeifExtractor extractor = new HeifExtractor(HeifExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/heif/sample_still_photo.heic"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }
}
