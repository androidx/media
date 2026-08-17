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
package androidx.media3.extractor.mkv;

import static androidx.media3.extractor.Extractor.RESULT_SEEK;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public class MatroskaExtractorNonParameterizedTest {

  @Test
  public void seek_afterSeekMapSent_seekMapRemainsValid() throws Exception {
    String fileName = "media/mkv/sample.mkv";
    byte[] fileBytes = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(fileBytes).build();
    PositionHolder positionHolder = new PositionHolder();

    while (output.seekMap == null) {
      int readResult = extractor.read(input, positionHolder);
      if (readResult == RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
    TrackAwareSeekMap trackAwareSeekMap = (TrackAwareSeekMap) output.seekMap;
    assertThat(trackAwareSeekMap.isSeekable()).isTrue();

    int videoTrackId = 1;
    long timeStampUs = trackAwareSeekMap.getDurationUs() / 2;
    SeekPoint expectedGenericSeekPoint = trackAwareSeekMap.getSeekPoints(timeStampUs).first;
    SeekPoint expectedTrackSpecificSeekPoint =
        trackAwareSeekMap.getSeekPoints(timeStampUs, videoTrackId).first;

    extractor.seek(expectedGenericSeekPoint.position, expectedGenericSeekPoint.timeUs);

    assertThat(trackAwareSeekMap.isSeekable()).isTrue();
    assertThat(trackAwareSeekMap.getSeekPoints(timeStampUs).first)
        .isEqualTo(expectedGenericSeekPoint);
    assertThat(trackAwareSeekMap.getSeekPoints(timeStampUs, videoTrackId).first)
        .isEqualTo(expectedTrackSpecificSeekPoint);
  }
}
