/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.annotation.Config.ALL_SDKS;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link AudioAttributes}. */
@Config(sdk = ALL_SDKS)
@RunWith(AndroidJUnit4.class)
public class AudioAttributesTest {

  @Test
  public void roundTripViaBundle_yieldsEqualInstance() {
    AudioAttributes audioAttributes =
        new AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .setFlags(C.FLAG_AUDIBILITY_ENFORCED)
            .setUsage(C.USAGE_ALARM)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
            .setIsContentSpatialized(true)
            .build();

    assertThat(AudioAttributes.fromBundle(audioAttributes.toBundle())).isEqualTo(audioAttributes);
  }

  @Test
  public void fromPlatformAudioAttributes_setsCorrectValues() {
    android.media.AudioAttributes platformAttributes =
        new android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setFlags(android.media.AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build();

    AudioAttributes audioAttributes =
        AudioAttributes.fromPlatformAudioAttributes(platformAttributes);

    assertThat(audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC);
    assertThat(audioAttributes.usage).isEqualTo(C.USAGE_MEDIA);
    assertThat(audioAttributes.flags).isEqualTo(C.FLAG_AUDIBILITY_ENFORCED);
  }

  @Config(minSdk = 29)
  @Test
  public void fromPlatformAudioAttributesV29_setsCorrectValues() {
    android.media.AudioAttributes platformAttributes =
        new android.media.AudioAttributes.Builder()
            .setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
            .build();

    AudioAttributes audioAttributes =
        AudioAttributes.fromPlatformAudioAttributes(platformAttributes);

    assertThat(audioAttributes.allowedCapturePolicy).isEqualTo(C.ALLOW_CAPTURE_BY_SYSTEM);
  }

  @Config(minSdk = 32)
  @Test
  public void fromPlatformAudioAttributesV32_setsCorrectValues() {
    android.media.AudioAttributes platformAttributes =
        new android.media.AudioAttributes.Builder()
            .setSpatializationBehavior(android.media.AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
            .setIsContentSpatialized(true)
            .build();

    AudioAttributes audioAttributes =
        AudioAttributes.fromPlatformAudioAttributes(platformAttributes);

    assertThat(audioAttributes.spatializationBehavior).isEqualTo(C.SPATIALIZATION_BEHAVIOR_NEVER);
    assertThat(audioAttributes.isContentSpatialized).isTrue();
  }

  @Test
  public void builder_setsCorrectValues() {
    AudioAttributes.Builder builder =
        new AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setFlags(C.FLAG_AUDIBILITY_ENFORCED)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
            .setIsContentSpatialized(true)
            .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER);

    AudioAttributes audioAttributes = builder.build();

    assertThat(audioAttributes.contentType).isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC);
    assertThat(audioAttributes.usage).isEqualTo(C.USAGE_MEDIA);
    assertThat(audioAttributes.flags).isEqualTo(C.FLAG_AUDIBILITY_ENFORCED);
    assertThat(audioAttributes.allowedCapturePolicy).isEqualTo(C.ALLOW_CAPTURE_BY_SYSTEM);
    assertThat(audioAttributes.spatializationBehavior).isEqualTo(C.SPATIALIZATION_BEHAVIOR_NEVER);
    assertThat(audioAttributes.isContentSpatialized).isTrue();
  }

  @Test
  public void getStreamType_defaultInstance_returnsMusic() {
    assertThat(AudioAttributes.DEFAULT.getStreamType()).isEqualTo(C.STREAM_TYPE_MUSIC);
  }

  @Test
  public void getStreamType_withUsageNotification_returnsNotification() {
    assertThat(new AudioAttributes.Builder().setUsage(C.USAGE_NOTIFICATION).build().getStreamType())
        .isEqualTo(C.STREAM_TYPE_NOTIFICATION);
  }

  @Test
  public void getStreamType_withUsageAlarm_returnsAlarm() {
    assertThat(new AudioAttributes.Builder().setUsage(C.USAGE_ALARM).build().getStreamType())
        .isEqualTo(C.STREAM_TYPE_ALARM);
  }

  @Test
  public void getStreamType_withUsageAssistanceSonification_returnsSystem() {
    assertThat(
            new AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANCE_SONIFICATION)
                .build()
                .getStreamType())
        .isEqualTo(C.STREAM_TYPE_SYSTEM);
  }

  @Test
  public void getStreamType_withAudibilityEnforcedFlag_returnsSystem() {
    assertThat(
            new AudioAttributes.Builder()
                .setFlags(C.FLAG_AUDIBILITY_ENFORCED)
                .build()
                .getStreamType())
        .isEqualTo(C.STREAM_TYPE_SYSTEM);
  }
}
