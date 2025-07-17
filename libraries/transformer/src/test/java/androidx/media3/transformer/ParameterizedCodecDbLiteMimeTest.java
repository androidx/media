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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.shadows.ShadowBuild;

/**
 * Parameterized tests for {@link CodecDbLite} to evaluate the {@link
 * CodecDbLite#getRecommendedVideoMimeType} API across a variety of chipset configurations.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class ParameterizedCodecDbLiteMimeTest {

  @Parameter public TestParameters params;

  @Parameters(name = "{index}: {0}")
  public static ImmutableList<TestParameters> parameters() {
    return ImmutableList.of(
        new TestParameters("QTI", "SM6375", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM8350", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6769T", MimeTypes.VIDEO_H265),
        new TestParameters("Google", "Tensor G3", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6762", MimeTypes.VIDEO_H264),
        new TestParameters("QTI", "SM6225", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM8475", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6893", MimeTypes.VIDEO_H265),
        new TestParameters("Spreadtrum", "SC9863A", MimeTypes.VIDEO_H264),
        new TestParameters("QTI", "SM8450", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6765", MimeTypes.VIDEO_H264),
        new TestParameters("Mediatek", "MT6789V/CD", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM8250", MimeTypes.VIDEO_H265),
        new TestParameters("Google", "Tensor G2", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6983", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6769Z", MimeTypes.VIDEO_H265),
        new TestParameters("Samsung", "Exynos 850", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM8650", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SDM450", MimeTypes.VIDEO_H264),
        new TestParameters("Spreadtrum", "T606", MimeTypes.VIDEO_H264),
        new TestParameters("Samsung", "s5e9925", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM8550", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM4350", MimeTypes.VIDEO_H265),
        new TestParameters("Samsung", "s5e8825", MimeTypes.VIDEO_H265),
        new TestParameters("QTI", "SM6125", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6833V/NZA", MimeTypes.VIDEO_H265),
        new TestParameters("Mediatek", "MT6761", MimeTypes.VIDEO_H264),
        new TestParameters("Mediatek", "MT6785", MimeTypes.VIDEO_H265),
        new TestParameters("Unknown", "Chipset", MimeTypes.VIDEO_H264));
  }

  @Test
  public void getRecommendedVideoMimeType_returnsCorrectMimeType() {
    ShadowBuild.setSystemOnChipManufacturer(params.socManufacturer);
    ShadowBuild.setSystemOnChipModel(params.socModel);

    assertThat(CodecDbLite.getRecommendedVideoMimeType()).isEqualTo(params.recommendedMimeType);
  }

  /** Parameters for a single CodecDB Lite test case. */
  private static final class TestParameters {
    private final String socManufacturer;
    private final String socModel;
    private final String recommendedMimeType;

    public TestParameters(String socManufacturer, String socModel, String recommendedMimeType) {
      this.socManufacturer = socManufacturer;
      this.socModel = socModel;
      this.recommendedMimeType = recommendedMimeType;
    }

    @Override
    public String toString() {
      return String.format("%s %s", socManufacturer, socModel);
    }
  }
}
