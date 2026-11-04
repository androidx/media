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

import androidx.media3.container.Mp4Box;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link HeifExtractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class HeifExtractorTest {

  /** Start byte offset of the {@code mpvd} box in {@code media/heif/sample_MP.heic}. */
  private static final int SAMPLE_MP_MPVD_BOX_OFFSET = 28_853;

  /**
   * Start byte offset of the {@code mpvd} payload (immediately after its 16-byte extended header)
   * in {@code media/heif/sample_MP.heic}.
   */
  private static final int SAMPLE_MP_MPVD_PAYLOAD_OFFSET =
      SAMPLE_MP_MPVD_BOX_OFFSET + Mp4Box.LONG_HEADER_SIZE;

  @Test
  public void sampleStillPhoto_noFlags_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        HeifExtractor::new, "media/heif/sample_still_photo.heic", simulationConfig);
  }

  @Test
  public void sampleStillPhoto_readImageFlag_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new HeifExtractor(HeifExtractor.FLAG_READ_IMAGE),
        "media/heif/sample_still_photo.heic",
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
            .setDumpFilesPrefix("extractordumps/heif/sample_MP.heic_image")
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
  public void sniff_onStillPhotoWithDefaultFlags_returnsTrue() throws Exception {
    HeifExtractor extractor = new HeifExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/heif/sample_still_photo.heic"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
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

  @Test
  public void read_withTruncatedMotionPhotoAfterMpvdHeader_endsTracksWithUnseekableSeekMap()
      throws Exception {
    byte[] truncatedAfterMpvdHeader =
        Arrays.copyOf(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(), "media/heif/sample_MP.heic"),
            /* newLength= */ SAMPLE_MP_MPVD_PAYLOAD_OFFSET);
    HeifExtractor extractor = new HeifExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(truncatedAfterMpvdHeader).build();

    readToEnd(extractor, input);
    extractor.release();

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(1);
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
  }

  private static void readToEnd(Extractor extractor, FakeExtractorInput input) throws IOException {
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = extractor.read(input, positionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
  }
}
