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
package androidx.media3.test.utils;

import static androidx.media3.test.utils.ExoPlayerTestRunner.VIDEO_FORMAT;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FakeMediaPeriodTest {

  @Test
  public void trackDataFactory_forFpsDurationAndKeyframeInterval_30fps() {
    TrackDataFactory trackDataFactory =
        TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
            /* initialSampleTimeUs= */ 0,
            /* sampleRate= */ 30,
            /* durationUs= */ 2_000_000,
            /* keyFrameInterval= */ 15);
    List<FakeSampleStreamItem> items =
        trackDataFactory.create(VIDEO_FORMAT, new MediaPeriodId(new Object()));

    assertThat(items).hasSize(61);
    // Check timestamps (excluding end of stream sample)
    assertThat(items.stream().limit(60).map(item -> item.sampleInfo.timeUs)).isInStrictOrder();
    assertThat(items.get(0).sampleInfo.timeUs).isEqualTo(0);
    assertThat(items.get(1).sampleInfo.timeUs).isEqualTo(33_333);
    assertThat(items.get(2).sampleInfo.timeUs).isEqualTo(66_666);
    assertThat(items.get(3).sampleInfo.timeUs).isEqualTo(100_000);
    // Check key frames
    assertThat(getKeyframeIndices(items)).containsExactly(0, 15, 30, 45).inOrder();
    // Check end of stream sample
    assertThat(isEndOfStream(items.get(60))).isTrue();
    assertThat(items.get(60).sampleInfo.data).hasLength(0);
  }

  @Test
  public void trackDataFactory_forFpsDurationAndKeyframeInterval_60fps() {
    TrackDataFactory trackDataFactory =
        TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
            /* initialSampleTimeUs= */ 0,
            /* sampleRate= */ 60,
            /* durationUs= */ 2_000_000,
            /* keyFrameInterval= */ 20);

    List<FakeSampleStreamItem> items =
        trackDataFactory.create(VIDEO_FORMAT, new MediaPeriodId(new Object()));

    assertThat(items).hasSize(121);
    // Check timestamps (excluding end of stream sample)
    assertThat(items.stream().limit(120).map(item -> item.sampleInfo.timeUs)).isInStrictOrder();
    assertThat(items.get(0).sampleInfo.timeUs).isEqualTo(0);
    assertThat(items.get(1).sampleInfo.timeUs).isEqualTo(16_666);
    assertThat(items.get(2).sampleInfo.timeUs).isEqualTo(33_333);
    assertThat(items.get(3).sampleInfo.timeUs).isEqualTo(50_000);
    assertThat(items.get(4).sampleInfo.timeUs).isEqualTo(66_666);
    assertThat(items.get(5).sampleInfo.timeUs).isEqualTo(83_333);
    assertThat(items.get(6).sampleInfo.timeUs).isEqualTo(100_000);
    // Check key frames
    assertThat(getKeyframeIndices(items)).containsExactly(0, 20, 40, 60, 80, 100).inOrder();
    // Check end of stream sample
    assertThat(isEndOfStream(items.get(120))).isTrue();
    assertThat(items.get(120).sampleInfo.data).hasLength(0);
  }

  @Test
  public void trackDataFactory_forFpsDurationAndKeyframeInterval_singleTruncatedSample() {
    TrackDataFactory trackDataFactory =
        TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
            /* initialSampleTimeUs= */ 0,
            /* sampleRate= */ 1,
            /* durationUs= */ 5_000,
            /* keyFrameInterval= */ 20);

    List<FakeSampleStreamItem> items =
        trackDataFactory.create(VIDEO_FORMAT, new MediaPeriodId(new Object()));

    assertThat(items).hasSize(2);
    assertThat(items.get(0).sampleInfo.timeUs).isEqualTo(0);
    assertThat(isEndOfStream(items.get(1))).isTrue();
    assertThat(items.get(1).sampleInfo.data).hasLength(0);
  }

  private static ImmutableList<Integer> getKeyframeIndices(
      List<FakeSampleStreamItem> fakeSampleStreamItems) {
    ImmutableList.Builder<Integer> keyframeIndices = ImmutableList.builder();
    for (int i = 0; i < fakeSampleStreamItems.size(); i++) {
      if (isKeyFrame(fakeSampleStreamItems.get(i))) {
        keyframeIndices.add(i);
      }
    }
    return keyframeIndices.build();
  }

  private static boolean isKeyFrame(FakeSampleStreamItem fakeSampleStreamItem) {
    return (fakeSampleStreamItem.sampleInfo.flags & C.BUFFER_FLAG_KEY_FRAME) != 0;
  }

  private static boolean isEndOfStream(FakeSampleStreamItem fakeSampleStreamItem) {
    return (fakeSampleStreamItem.sampleInfo.flags & C.BUFFER_FLAG_END_OF_STREAM) != 0;
  }
}
