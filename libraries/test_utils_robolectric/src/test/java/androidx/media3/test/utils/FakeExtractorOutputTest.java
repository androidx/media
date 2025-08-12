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
  public void getTrackOutput_noTracks_returnsNull() {
    FakeExtractorOutput output = new FakeExtractorOutput();

    assertThat(output.getTrackOutput(C.TRACK_TYPE_VIDEO)).isNull();
  }

  @Test
  public void getTrackOutput_returnsOnlyTrackOutputOfType() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    FakeTrackOutput audioTrack = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    output.track(/* id= */ 3, C.TRACK_TYPE_VIDEO);

    assertThat(output.getTrackOutput(C.TRACK_TYPE_AUDIO)).isSameInstanceAs(audioTrack);
  }

  // TODO: Update this test when the contract of getTrackOutput is changed to either return all
  //  tracks of the given type, or the only one found (not an arbitrary one of many).
  @Test
  public void getTrackOutput_returnsOneOfMatchingOutputs() {
    FakeExtractorOutput output = new FakeExtractorOutput();
    FakeTrackOutput audioTrack1 = output.track(/* id= */ 2, C.TRACK_TYPE_AUDIO);
    FakeTrackOutput audioTrack2 = output.track(/* id= */ 3, C.TRACK_TYPE_AUDIO);

    assertThat(output.getTrackOutput(C.TRACK_TYPE_AUDIO)).isAnyOf(audioTrack1, audioTrack2);
  }
}
