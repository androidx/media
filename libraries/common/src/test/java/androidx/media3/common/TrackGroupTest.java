/*
 * Copyright (C) 2018 The Android Open Source Project
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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TrackGroup}. */
@RunWith(AndroidJUnit4.class)
public final class TrackGroupTest {

  @Test
  public void roundTripViaBundle_ofTrackGroup_yieldsEqualInstance() {
    Format.Builder formatBuilder = new Format.Builder();
    Format format1 = formatBuilder.setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Format format2 = formatBuilder.setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    String id = "abc";

    TrackGroup trackGroupToBundle = new TrackGroup(id, format1, format2);

    TrackGroup trackGroupFromBundle = TrackGroup.fromBundle(trackGroupToBundle.toBundle());

    assertThat(trackGroupFromBundle).isEqualTo(trackGroupToBundle);
  }

  @Test
  public void trackGroup_withPredefinedSampleMimeType_resolvesTrackType() {
    TrackGroup noTypeTrackGroup = new TrackGroup(new Format.Builder().build());
    TrackGroup audioTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC4).build());
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).build());
    TrackGroup textTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build());
    TrackGroup imageTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.IMAGE_BMP).build());
    TrackGroup metadataTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_ICY).build());
    TrackGroup cameraMotionTrackGroup =
        new TrackGroup(
            new Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CAMERA_MOTION).build());

    assertThat(noTypeTrackGroup.type).isEqualTo(C.TRACK_TYPE_UNKNOWN);
    assertThat(audioTrackGroup.type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(videoTrackGroup.type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(textTrackGroup.type).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(imageTrackGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(metadataTrackGroup.type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(cameraMotionTrackGroup.type).isEqualTo(C.TRACK_TYPE_CAMERA_MOTION);
  }

  @Test
  public void trackGroup_withCustomSampleMimeTypes_resolvesTrackType() {
    TrackGroup audioTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("audio/custom").build());
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("video/custom").build());
    TrackGroup textTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("text/custom").build());
    TrackGroup imageTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("image/custom").build());
    MimeTypes.registerCustomMimeType("custom/custom", "codecPrefix", C.TRACK_TYPE_METADATA);
    TrackGroup registeredMetadataTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("custom/custom").build());
    MimeTypes.clearRegisteredCustomMimeTypes();
    TrackGroup unregisteredTypeTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType("custom/unregistered").build());

    assertThat(audioTrackGroup.type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(videoTrackGroup.type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(textTrackGroup.type).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(imageTrackGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(registeredMetadataTrackGroup.type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(unregisteredTypeTrackGroup.type).isEqualTo(C.TRACK_TYPE_UNKNOWN);
  }

  @Test
  public void trackGroup_withPredefinedContainerMimeTypes_resolvesTrackType() {
    TrackGroup noTypeTrackGroup = new TrackGroup(new Format.Builder().build());
    TrackGroup audioTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType(MimeTypes.AUDIO_AC4).build());
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType(MimeTypes.VIDEO_VP9).build());
    TrackGroup textTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType(MimeTypes.TEXT_SSA).build());
    TrackGroup imageTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType(MimeTypes.IMAGE_BMP).build());
    TrackGroup metadataTrackGroup =
        new TrackGroup(
            new Format.Builder().setContainerMimeType(MimeTypes.APPLICATION_ICY).build());
    TrackGroup cameraMotionTrackGroup =
        new TrackGroup(
            new Format.Builder().setContainerMimeType(MimeTypes.APPLICATION_CAMERA_MOTION).build());

    assertThat(noTypeTrackGroup.type).isEqualTo(C.TRACK_TYPE_UNKNOWN);
    assertThat(audioTrackGroup.type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(videoTrackGroup.type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(textTrackGroup.type).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(imageTrackGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(metadataTrackGroup.type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(cameraMotionTrackGroup.type).isEqualTo(C.TRACK_TYPE_CAMERA_MOTION);
  }

  @Test
  public void trackGroup_withCustomContainerMimeTypes_resolvesTrackType() {
    TrackGroup audioTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("audio/custom").build());
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("video/custom").build());
    TrackGroup textTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("text/custom").build());
    TrackGroup imageTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("image/custom").build());
    MimeTypes.registerCustomMimeType("custom/custom", "codecPrefix", C.TRACK_TYPE_METADATA);
    TrackGroup registeredMetadataTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("custom/custom").build());
    TrackGroup unregisteredTypeTrackGroup =
        new TrackGroup(new Format.Builder().setContainerMimeType("custom/unregistered").build());

    assertThat(audioTrackGroup.type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(videoTrackGroup.type).isEqualTo(C.TRACK_TYPE_VIDEO);
    assertThat(textTrackGroup.type).isEqualTo(C.TRACK_TYPE_TEXT);
    assertThat(imageTrackGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(registeredMetadataTrackGroup.type).isEqualTo(C.TRACK_TYPE_METADATA);
    assertThat(unregisteredTypeTrackGroup.type).isEqualTo(C.TRACK_TYPE_UNKNOWN);
  }

  @Test
  public void trackGroup_withMixedSampleAndContainerMimeTypes_prefersSampleMimeType() {
    TrackGroup mixedKnownTypesTrackGroup =
        new TrackGroup(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setContainerMimeType(MimeTypes.VIDEO_VP9)
                .build());
    TrackGroup knownSampleUnknownContainerGroup =
        new TrackGroup(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_JPEG)
                .setContainerMimeType("custom/unregistered")
                .build());
    TrackGroup unknownSampleKnownContainerGroup =
        new TrackGroup(
            new Format.Builder()
                .setSampleMimeType("custom/unregistered")
                .setContainerMimeType(MimeTypes.IMAGE_JPEG)
                .build());
    TrackGroup knownSampleEmptyContainerGroup =
        new TrackGroup(
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_JPEG)
                .setContainerMimeType("")
                .build());
    TrackGroup emptySampleKnownContainerGroup =
        new TrackGroup(
            new Format.Builder()
                .setSampleMimeType("")
                .setContainerMimeType(MimeTypes.IMAGE_JPEG)
                .build());

    assertThat(mixedKnownTypesTrackGroup.type).isEqualTo(C.TRACK_TYPE_AUDIO);
    assertThat(knownSampleUnknownContainerGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(unknownSampleKnownContainerGroup.type).isEqualTo(C.TRACK_TYPE_UNKNOWN);
    assertThat(knownSampleEmptyContainerGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(emptySampleKnownContainerGroup.type).isEqualTo(C.TRACK_TYPE_IMAGE);
  }
}
