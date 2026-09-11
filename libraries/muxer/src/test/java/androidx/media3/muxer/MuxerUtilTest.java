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
package androidx.media3.muxer;

import static androidx.media3.muxer.MuxerTestUtil.FAKE_AUDIO_FORMAT;
import static androidx.media3.muxer.MuxerTestUtil.FAKE_VIDEO_FORMAT;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MuxerUtil}. */
@RunWith(AndroidJUnit4.class)
public class MuxerUtilTest {

  @Test
  public void getFtypCompatibleBrands_withNonDolbyTracks_returnsEmptyList() {
    Track videoTrack =
        new Track(/* trackId= */ 0, FAKE_VIDEO_FORMAT, /* sampleCopyEnabled= */ false);
    Track audioTrack =
        new Track(/* trackId= */ 1, FAKE_AUDIO_FORMAT, /* sampleCopyEnabled= */ false);

    ImmutableList<String> compatibleBrands =
        MuxerUtil.getFtypCompatibleBrands(ImmutableList.of(videoTrack, audioTrack));

    assertThat(compatibleBrands).isEmpty();
  }

  @Test
  public void getFtypCompatibleBrands_withDolbyVisionTrack_returnsDby1() {
    Track dolbyVisionTrack =
        new Track(
            /* trackId= */ 0,
            FAKE_VIDEO_FORMAT.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build(),
            /* sampleCopyEnabled= */ false);

    ImmutableList<String> compatibleBrands =
        MuxerUtil.getFtypCompatibleBrands(ImmutableList.of(dolbyVisionTrack));

    assertThat(compatibleBrands).containsExactly("dby1");
  }

  @Test
  public void getFtypCompatibleBrands_withMixedDolbyAndNonDolbyTracks_returnsDby1() {
    Track videoTrack =
        new Track(/* trackId= */ 0, FAKE_VIDEO_FORMAT, /* sampleCopyEnabled= */ false);
    Track dolbyVisionTrack =
        new Track(
            /* trackId= */ 1,
            FAKE_VIDEO_FORMAT.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build(),
            /* sampleCopyEnabled= */ false);
    Track audioTrack =
        new Track(/* trackId= */ 2, FAKE_AUDIO_FORMAT, /* sampleCopyEnabled= */ false);

    ImmutableList<String> compatibleBrands =
        MuxerUtil.getFtypCompatibleBrands(
            ImmutableList.of(videoTrack, dolbyVisionTrack, audioTrack));

    assertThat(compatibleBrands).containsExactly("dby1");
  }

  @Test
  public void getFtypCompatibleBrands_withMultipleDolbyTracks_returnsSingleDby1() {
    Format dolbyVisionFormat =
        FAKE_VIDEO_FORMAT.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build();
    Track dolbyVisionTrack1 =
        new Track(/* trackId= */ 0, dolbyVisionFormat, /* sampleCopyEnabled= */ false);
    Track dolbyVisionTrack2 =
        new Track(/* trackId= */ 1, dolbyVisionFormat, /* sampleCopyEnabled= */ false);
    Track ac4Track =
        new Track(
            /* trackId= */ 2,
            FAKE_AUDIO_FORMAT.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AC4).build(),
            /* sampleCopyEnabled= */ false);

    ImmutableList<String> compatibleBrands =
        MuxerUtil.getFtypCompatibleBrands(
            ImmutableList.of(dolbyVisionTrack1, dolbyVisionTrack2, ac4Track));

    assertThat(compatibleBrands).containsExactly("dby1");
  }

  @Test
  public void getFtypCompatibleBrands_withAc3Track_returnsDby1() {
    assertThatTrackWithMimeTypeProducesDby1(MimeTypes.AUDIO_AC3);
  }

  @Test
  public void getFtypCompatibleBrands_withAc4Track_returnsDby1() {
    assertThatTrackWithMimeTypeProducesDby1(MimeTypes.AUDIO_AC4);
  }

  @Test
  public void getFtypCompatibleBrands_withEac3Track_returnsDby1() {
    assertThatTrackWithMimeTypeProducesDby1(MimeTypes.AUDIO_E_AC3);
  }

  @Test
  public void getFtypCompatibleBrands_withEac3JocTrack_returnsDby1() {
    assertThatTrackWithMimeTypeProducesDby1(MimeTypes.AUDIO_E_AC3_JOC);
  }

  @Test
  public void getFtypCompatibleBrands_withTrueHdTrack_returnsDby1() {
    assertThatTrackWithMimeTypeProducesDby1(MimeTypes.AUDIO_TRUEHD);
  }

  @Test
  public void getFtypCompatibleBrands_withEmptyTrackList_returnsEmptyList() {
    ImmutableList<String> compatibleBrands = MuxerUtil.getFtypCompatibleBrands(ImmutableList.of());

    assertThat(compatibleBrands).isEmpty();
  }

  private static void assertThatTrackWithMimeTypeProducesDby1(String mimeType) {
    Track track =
        new Track(
            /* trackId= */ 0,
            FAKE_AUDIO_FORMAT.buildUpon().setSampleMimeType(mimeType).build(),
            /* sampleCopyEnabled= */ false);

    ImmutableList<String> compatibleBrands =
        MuxerUtil.getFtypCompatibleBrands(ImmutableList.of(track));

    assertThat(compatibleBrands).containsExactly("dby1");
  }
}
