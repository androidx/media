/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.extractor.jpeg;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link JpegExtractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class JpegExtractorTest {

  @Test
  public void sampleNonMotionPhotoShortened_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE),
        "media/jpeg/non-motion-photo-shortened.jpg",
        /* peekLimit= */ 2,
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDumpFilesPrefix(
                "extractordumps/jpeg/non-motion-photo-shortened.jpg_JpegExtractor.FLAG_READ_IMAGE")
            .build(),
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoShortened_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE),
        "media/jpeg/pixel-motion-photo-shortened.jpg",
        /* peekLimit= */ 2,
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDumpFilesPrefix(
                "extractordumps/jpeg/pixel-motion-photo-shortened.jpg_JpegExtractor.FLAG_READ_IMAGE")
            .build(),
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegExtractor::new,
        "media/jpeg/pixel-motion-photo-shortened.jpg",
        /* peekLimit= */ 2235,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoJfifSegmentShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-jfif-segment-shortened.jpg",
        /* peekLimit= */ 5413,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoVideoRemovedShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-video-removed-shortened.jpg",
        /* peekLimit= */ 2235,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoWithoutExifShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-without-exif-shortened.jpg",
        /* peekLimit= */ 1264,
        simulationConfig);
  }

  @Test
  public void sampleSsMotionPhotoShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/ss-motion-photo-shortened.jpg",
        /* peekLimit= */ 11924,
        simulationConfig);
  }

  /** Regression test for [internal b/301025983]. */
  @Test
  public void samplePixelMotionPhotoWithTwoHevcTracks_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-2-hevc-tracks.jpg",
        /* peekLimit= */ 24344,
        simulationConfig);
  }

  @Test
  public void sniff_onMotionPhotoWithDefaultFlags_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/pixel-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onMotionPhotoWithReadImageFlag_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/pixel-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onNonMotionPhotoWithDefaultFlags_returnsFalse() throws Exception {
    JpegExtractor extractor = new JpegExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/non-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isFalse();
  }

  @Test
  public void sniff_onNonMotionPhotoWithReadImageFlag_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/non-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }
}
