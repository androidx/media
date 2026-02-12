/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioDescriptor;
import android.media.AudioFormat;
import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Tests {@link AudioDescriptorUtil}. */
@RunWith(AndroidJUnit4.class)
public final class AudioDescriptorUtilTest {

  @Test
  @Config(maxSdk = 33)
  public void getChannelMaskFromSadb_returnsZeroBeforeSdk34() {
    assertThat(
            AudioDescriptorUtil.getChannelMaskFromSadb(
                new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff}))
        .isEqualTo(0);
  }

  @Test
  @Config(minSdk = 34)
  public void getChannelMaskFromSadb_parsesByte1() {
    // Bit 0 Front left/right (FL/FR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00000001, 0, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT);

    // Bit 1 Low-frequency effects (LFE)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00000010, 0, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_LOW_FREQUENCY);

    // Bit 2 Front center (FC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00000100, 0, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_FRONT_CENTER);

    // Bit 3 Back left/right (BL/BR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00001000, 0, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_BACK_LEFT | AudioFormat.CHANNEL_OUT_BACK_RIGHT);

    // Bit 4 Back center (BC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00010000, 0, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_BACK_CENTER);

    // Bit 5 Front left/right center (FLc/FRc)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b00100000, 0, 0}))
        .isEqualTo(
            AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
                | AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER);

    // Bit 6 Rear left/right center (RLC/RRC).  Unused.
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b01000000, 0, 0}))
        .isEqualTo(0);

    // Bit 7 Front left/right wide (FLw/FRw)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {(byte) 0b10000000, 0, 0}))
        .isEqualTo(
            AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT | AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT);
  }

  @Test
  @Config(minSdk = 34)
  public void getChannelMaskFromSadb_parsesByte2() {
    // Bit 0 Top front left/right (TpFL/TpFR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00000001, 0}))
        .isEqualTo(
            AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT);

    // Bit 1 Top center (TpC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00000010, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_TOP_CENTER);

    // Bit 2 Top front center (TpFC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00000100, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER);

    // Bit 3 Left surround/right surround (LS/RS)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00001000, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT);

    // Bit 4 Low-frequency effects 2 (LFE2)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00010000, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2);

    // Bit 5 Top back center (TpBC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b00100000, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER);

    // Bit 6 Side left/right (SiL/SiR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b01000000, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT);

    // Bit 7 Top side left/right (TpSiL/TpSiR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, (byte) 0b10000000, 0}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT | AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT);
  }

  @Test
  @Config(minSdk = 34)
  public void getChannelMaskFromSadb_parsesByte3() {
    // Bit 1 Top back left/right (TpBL/TpBR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, 0, (byte) 0b00000001}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT | AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT);

    // Bit 2 Bottom front center (BtFC)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, 0, (byte) 0b00000010}))
        .isEqualTo(AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER);

    // Bit 3 Bottom front left/right (BtFL/BtFR)
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(new byte[] {0, 0, (byte) 0b00000100}))
        .isEqualTo(
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT);
  }

  /** Helper for getting SADB representing just a stereo L/R pair. */
  private byte[] getStereoSadbBytes() {
    return new byte[] {(byte) 0b00000001, 0, 0};
  }

  /** Helper for getting bytes representing standard 7.1. */
  private byte[] getSevenPointOneSurroundSadbBytes() {
    return new byte[] {(byte) 0b00001111, (byte) 0b00001000, 0};
  }

  @Test
  @Config(minSdk = 34)
  public void getChannelMaskFromSadb_parsesCombination() {
    // 7.1 Layout
    // Byte 1: Front left/right (FL/FR) (Bit 0) | Low-frequency effects (LFE) (Bit 1) | Front center
    // (FC) (Bit 2) | Back left/right (BL/BR) (Bit 3) = 0b00001111
    // Byte 2: Surround left/right (SL/SR) (Bit 3) = 0b00001000
    byte[] data = getSevenPointOneSurroundSadbBytes();
    int expected = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
    assertThat(AudioDescriptorUtil.getChannelMaskFromSadb(data)).isEqualTo(expected);
  }

  /** The value indicating LPCM encoding in the Short Audio Descriptor (SAD). */
  private static final int PCM_ENCODING = 1;

  @Test
  @Config(minSdk = 31)
  public void getMaxLpcmChannelCountFromPcmSads_returnsMaxChannelCount() {
    AudioDescriptor twoChannelPcmSad =
        createAudioDescriptor(AudioDescriptor.STANDARD_EDID, 0, createSadBytes(PCM_ENCODING, 2));
    AudioDescriptor sixChannelPcmSad =
        createAudioDescriptor(AudioDescriptor.STANDARD_EDID, 0, createSadBytes(PCM_ENCODING, 6));
    int expectedChannelCount = 6;
    assertThat(
            AudioDescriptorUtil.getMaxLpcmChannelCountFromPcmSads(
                ImmutableList.of(twoChannelPcmSad, sixChannelPcmSad)))
        .isEqualTo(expectedChannelCount);
  }

  @Test
  @Config(minSdk = 31)
  public void getMaxLpcmChannelCountFromPcmSads_returnsZeroForNonPcmSads() {
    AudioDescriptor nonPcmSad1 =
        createAudioDescriptor(
            AudioDescriptor.STANDARD_EDID, 0, createSadBytes(PCM_ENCODING + 1, 2));
    AudioDescriptor nonPcmSad2 =
        createAudioDescriptor(
            AudioDescriptor.STANDARD_EDID, 0, createSadBytes(PCM_ENCODING + 3, 6));
    int expectedChannelCount = 0;
    assertThat(
            AudioDescriptorUtil.getMaxLpcmChannelCountFromPcmSads(
                ImmutableList.of(nonPcmSad1, nonPcmSad2)))
        .isEqualTo(expectedChannelCount);
  }

  @Test
  @Config(minSdk = 31)
  public void getMaxLpcmChannelCountFromPcmSads_returnsZeroForNonEdidSads() {
    AudioDescriptor nonPcmSad1 =
        createAudioDescriptor(AudioDescriptor.STANDARD_SADB, 0, createSadBytes(PCM_ENCODING, 2));
    AudioDescriptor nonPcmSad2 =
        createAudioDescriptor(AudioDescriptor.STANDARD_NONE, 0, createSadBytes(PCM_ENCODING, 2));
    int expectedChannelCount = 0;
    assertThat(
            AudioDescriptorUtil.getMaxLpcmChannelCountFromPcmSads(
                ImmutableList.of(nonPcmSad1, nonPcmSad2)))
        .isEqualTo(expectedChannelCount);
  }

  @Test
  @Config(minSdk = 34)
  public void getAllChannelMasksFromSadbs_emptyList_returnsEmpty() {
    assertThat(AudioDescriptorUtil.getAllChannelMasksFromSadbs(ImmutableList.of())).isEmpty();
  }

  @Test
  @Config(minSdk = 31, maxSdk = 33)
  public void getAllChannelMasksFromSadbs_returnsEmptyBeforeSdk34() {
    AudioDescriptor sadb =
        createAudioDescriptor(AudioDescriptor.STANDARD_SADB, 0, getStereoSadbBytes());
    assertThat(AudioDescriptorUtil.getAllChannelMasksFromSadbs(ImmutableList.of(sadb))).isEmpty();
  }

  @Test
  @Config(minSdk = 34)
  public void getAllChannelMasksFromSadbs_returnsSortedChannelMasks() {
    AudioDescriptor stereoSadb =
        createAudioDescriptor(AudioDescriptor.STANDARD_SADB, 0, getStereoSadbBytes());
    AudioDescriptor pcmSad =
        createAudioDescriptor(AudioDescriptor.STANDARD_EDID, 0, createSadBytes(PCM_ENCODING, 2));
    AudioDescriptor sevenPointOneSadb =
        createAudioDescriptor(
            AudioDescriptor.STANDARD_SADB, 0, getSevenPointOneSurroundSadbBytes());

    ImmutableList<Integer> channelMasks =
        AudioDescriptorUtil.getAllChannelMasksFromSadbs(
            ImmutableList.of(stereoSadb, pcmSad, sevenPointOneSadb));

    // Expecting 7.1 surround (8 channels) before stereo (2 channels).
    assertThat(channelMasks)
        .containsExactly(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_STEREO)
        .inOrder();
  }

  /** Helper for doing the bit-shifting to make the descriptor bytes. */
  @RequiresApi(31)
  private static byte[] createSadBytes(int encodingType, int channelCount) {
    int firstByte = (encodingType & 0b1111) << 3 | ((channelCount - 1) & 0b111);
    return new byte[] {(byte) firstByte, 0, 0};
  }

  /** Uses reflection to get around the @SystemApi constructor. */
  @RequiresApi(31)
  private static AudioDescriptor createAudioDescriptor(
      int standard, int encapsulationType, byte[] descriptor) {
    return ReflectionHelpers.callConstructor(
        AudioDescriptor.class,
        ClassParameter.from(Integer.TYPE, standard),
        ClassParameter.from(Integer.TYPE, encapsulationType),
        ClassParameter.from(byte[].class, descriptor));
  }
}
