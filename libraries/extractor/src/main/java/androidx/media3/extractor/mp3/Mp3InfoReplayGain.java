/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.mp3;

import androidx.media3.common.Metadata;
import androidx.media3.common.util.UnstableApi;
import java.util.Objects;

/** Representation of the ReplayGain data stored in a LAME Xing or Info frame. */
@UnstableApi
public final class Mp3InfoReplayGain implements Metadata.Entry {
  /**
   * 32 bit floating point "Peak signal amplitude".
   *
   * <p>1.0 is maximal signal amplitude store-able in decoding format. 0.8 is 80% of maximal signal
   * amplitude store-able in decoding format. 1.5 is 150% of maximal signal amplitude store-able in
   * decoding format.
   *
   * <p>A value above 1.0 can occur for example due to "true peak" measurement. A value of 0.0 means
   * the peak signal amplitude is unknown.
   */
  public final float peak;

  /**
   * NAME of Gain adjustment in the first field, also called "Radio Replay Gain" field:
   *
   * <p>b000 = not set
   *
   * <p>b001 = radio
   *
   * <p>b010 = audiophile
   */
  public final byte field1Name;

  /**
   * ORIGINATOR of Gain adjustment in the first field, also called "Radio Replay Gain" field:
   *
   * <p>b000 = not set
   *
   * <p>b001 = set by artist
   *
   * <p>b010 = set by user
   *
   * <p>b011 = set by ReplayGain model
   *
   * <p>b100 = set by simple RMS average
   */
  public final byte field1Originator;

  /**
   * Absolute gain adjustment in the first field, also called "Radio Replay Gain" field.
   *
   * <p>Stored in the header with 1 decimal of precision by being multiplied by 10; this field is
   * already divided by 10 again.
   */
  public final float field1Value;

  /**
   * NAME of Gain adjustment in the second field, also called "Audiophile Replay Gain" field:
   *
   * <p>b000 = not set
   *
   * <p>b001 = radio
   *
   * <p>b010 = audiophile
   */
  public final byte field2Name;

  /**
   * ORIGINATOR of Gain adjustment in the second field, also called "Audiophile Replay Gain" field:
   *
   * <p>b000 = not set
   *
   * <p>b001 = set by artist
   *
   * <p>b010 = set by user
   *
   * <p>b011 = set by ReplayGain model
   *
   * <p>b100 = set by simple RMS average
   */
  public final byte field2Originator;

  /**
   * Absolute gain adjustment in the second field, also called "Audiophile Replay Gain" field.
   *
   * <p>Stored in the header with 1 decimal of precision by being multiplied by 10; this field is
   * already divided by 10 again.
   */
  public final float field2Value;

  /* package */ Mp3InfoReplayGain(XingFrame frame) {
    this.peak = frame.replayGainPeak;
    this.field1Name = frame.replayGainField1Name;
    this.field1Originator = frame.replayGainField1Originator;
    this.field1Value = frame.replayGainField1Value;
    this.field2Name = frame.replayGainField2Name;
    this.field2Originator = frame.replayGainField2Originator;
    this.field2Value = frame.replayGainField2Value;
  }

  @Override
  public String toString() {
    return "ReplayGain Xing/Info: "
        + "peak="
        + peak
        + ", f1 name="
        + field1Name
        + ", f1 orig="
        + field1Originator
        + ", f1 val="
        + field1Value
        + ", f2 name="
        + field2Name
        + ", f2 orig="
        + field2Originator
        + ", f2 val="
        + field2Value;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Mp3InfoReplayGain)) return false;
    Mp3InfoReplayGain that = (Mp3InfoReplayGain) o;
    return Float.compare(peak, that.peak) == 0
        && field1Name == that.field1Name
        && field1Originator == that.field1Originator
        && Float.compare(field1Value, that.field1Value) == 0
        && field2Name == that.field2Name
        && field2Originator == that.field2Originator
        && Float.compare(field2Value, that.field2Value) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        peak, field1Name, field1Originator, field1Value, field2Name, field2Originator, field2Value);
  }
}
