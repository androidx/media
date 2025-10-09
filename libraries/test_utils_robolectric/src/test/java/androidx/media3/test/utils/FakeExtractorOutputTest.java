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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FakeExtractorOutputTest {

  @Test
  public void track_returnsOutputWithCorrectType() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    FakeTrackOutput trackOutput = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);

    assertThat(trackOutput.getType()).isEqualTo(C.TRACK_TYPE_AUDIO);
  }

  @Test
  public void track_repeatCallWithSameIdBeforeAndAfterEndTracks_returnsSameInstance() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    FakeTrackOutput trackOutput1 = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    FakeTrackOutput trackOutput2 = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    output.endTracks();
    FakeTrackOutput trackOutput3 = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);

    assertThat(trackOutput2).isSameInstanceAs(trackOutput1);
    assertThat(trackOutput3).isSameInstanceAs(trackOutput1);
  }

  @Test
  public void track_repeatCallWithDifferentType_fails() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    assertThrows(
        IllegalArgumentException.class, () -> output.track(/* id= */ 2, C.TRACK_TYPE_VIDEO));
  }

  @Test
  public void track_newIdAfterEndTracks_fails() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    output.endTracks();

    assertThrows(AssertionError.class, () -> output.track(/* id= */ 3, C.TRACK_TYPE_VIDEO));
  }

  @Test
  public void getTrackOutputsForType_noTracks_returnsEmptyList() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    assertThat(output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO)).isEmpty();
  }

  @Test
  public void getTrackOutputsForType_returnsAllTrackOutputOfType() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    FakeTrackOutput videoTrack = output.track(/* id= */ 1, C.TRACK_TYPE_VIDEO);
    FakeTrackOutput audioTrack1 = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    FakeTrackOutput audioTrack2 = output.track(/* id= */ 3, C.TRACK_TYPE_AUDIO);

    assertThat(Iterables.getOnlyElement(output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO)))
        .isSameInstanceAs(videoTrack);
    ImmutableList<FakeTrackOutput> audioTracks = output.getTrackOutputsForType(C.TRACK_TYPE_AUDIO);
    assertThat(audioTracks).containsExactly(audioTrack1, audioTrack2);
  }
}
