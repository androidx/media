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
package androidx.media3.transformer;

import static androidx.media3.transformer.EditedMediaItemAssetInfo.AUDIO;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.AUDIO_WITH_VIDEO_TIMESTAMPS;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.IMAGE;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_HALF_SPEED;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_TWICE_SPEED;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO_SRGB;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO_WITHOUT_AUDIO;
import static androidx.media3.transformer.EditedMediaItemAssetInfo.VIDEO_WITH_REMOVE_VIDEO;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

/** Test assets that describe various {@link Composition} configurations. */
/* package */ final class CompositionAssetInfo {

  public static final ImmutableList<CompositionAssetInfo> SINGLE_SEQUENCE_CONFIGS =
      ImmutableList.of(
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), VIDEO_SRGB)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO_WITH_REMOVE_VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(ImmutableSet.of(C.TRACK_TYPE_VIDEO), IMAGE)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(ImmutableSet.of(C.TRACK_TYPE_AUDIO), AUDIO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  VIDEO,
                  VIDEO,
                  IMAGE,
                  IMAGE,
                  IMAGE)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  IMAGE,
                  VIDEO,
                  IMAGE,
                  VIDEO,
                  IMAGE,
                  VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  AUDIO_WITH_VIDEO_TIMESTAMPS,
                  IMAGE,
                  AUDIO_WITH_VIDEO_TIMESTAMPS,
                  VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO_WITHOUT_AUDIO,
                  VIDEO,
                  VIDEO_WITHOUT_AUDIO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  VIDEO_WITHOUT_AUDIO,
                  VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  AUDIO_WITH_VIDEO_TIMESTAMPS)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO), VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO), VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO),
                  VIDEO_ONLY_CLIPPED_TWICE_SPEED,
                  VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO),
                  VIDEO_ONLY_CLIPPED_TWICE_SPEED,
                  VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO),
                  VIDEO_ONLY_CLIPPED_HALF_SPEED,
                  VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_VIDEO),
                  VIDEO_ONLY_CLIPPED_HALF_SPEED,
                  VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  VIDEO_SRGB,
                  VIDEO,
                  IMAGE,
                  VIDEO_SRGB)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO_WITH_REMOVE_VIDEO,
                  VIDEO,
                  VIDEO)),
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                  VIDEO,
                  VIDEO_WITH_REMOVE_VIDEO,
                  VIDEO)));

  public static final ImmutableList<CompositionAssetInfo> MULTI_SEQUENCE_IMAGE_CONFIGS =
      ImmutableList.of(
          new CompositionAssetInfo(
              new SequenceAssetInfo(ImmutableSet.of(C.TRACK_TYPE_VIDEO), IMAGE, IMAGE, IMAGE),
              new SequenceAssetInfo(ImmutableSet.of(C.TRACK_TYPE_VIDEO), IMAGE, IMAGE, IMAGE)));

  public static final ImmutableList<CompositionAssetInfo> MULTI_SEQUENCE_VIDEO_CONFIGS =
      ImmutableList.of(
          new CompositionAssetInfo(
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), VIDEO, VIDEO, VIDEO),
              new SequenceAssetInfo(
                  ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), VIDEO, VIDEO, VIDEO)));

  public static final ImmutableList<CompositionAssetInfo>
      MULTI_SEQUENCE_MISMATCHED_DURATION_CONFIGS =
          ImmutableList.of(
              new CompositionAssetInfo(
                  new SequenceAssetInfo(
                      ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                      VIDEO,
                      AUDIO_WITH_VIDEO_TIMESTAMPS,
                      VIDEO),
                  new SequenceAssetInfo(ImmutableSet.of(C.TRACK_TYPE_VIDEO), IMAGE)),
              new CompositionAssetInfo(
                  new SequenceAssetInfo(
                      ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO), VIDEO, VIDEO),
                  new SequenceAssetInfo(
                      /* isLooping= */ false, ImmutableSet.of(C.TRACK_TYPE_AUDIO), AUDIO)));

  public final ImmutableList<SequenceAssetInfo> sequences;

  public CompositionAssetInfo(SequenceAssetInfo sequence, SequenceAssetInfo... sequences) {
    this.sequences =
        new ImmutableList.Builder<SequenceAssetInfo>().add(sequence).add(sequences).build();
  }

  public Composition getComposition() {
    return new Composition.Builder(
            ImmutableList.copyOf(
                Iterables.transform(sequences, SequenceAssetInfo::getEditedMediaItemSequence)))
        .build();
  }

  public ImmutableList<Long> getExpectedVideoTimestampsUs() {
    // When there are multiple sequences, output timestamps should match those of the primary
    // sequence.
    return sequences.get(0).getExpectedVideoTimestampsUs();
  }

  /** All {@link Format} instances that need to be decoded for this {@link Composition}. */
  public ImmutableList<Format> getAllVideoFormats() {
    ImmutableList.Builder<Format> allFormats = new ImmutableList.Builder<>();
    for (SequenceAssetInfo sequence : sequences) {
      for (EditedMediaItemAssetInfo asset : sequence.assets) {
        if (asset.videoFormat != null) {
          allFormats.add(asset.videoFormat);
        }
      }
    }
    return allFormats.build();
  }

  /** All {@link Format} that needs to be encoded for this {@link Composition}. */
  @Nullable
  public Format getVideoEncoderInputFormat() {
    // The first asset is currently used to configure the encoder.
    EditedMediaItemAssetInfo firstAsset = sequences.get(0).assets.get(0);
    Format format = firstAsset.videoFormat;
    if (format == null) {
      return null;
    }
    // If the first asset is a gap, the the encoder will be configured with [1, 1], HEVC.
    if (firstAsset.editedMediaItem != null && firstAsset.editedMediaItem.removeVideo) {
      format =
          format
              .buildUpon()
              .setWidth(1)
              .setHeight(1)
              .setSampleMimeType(MimeTypes.VIDEO_H265)
              .build();
    }
    // Treat SRGB as SDR.
    if (format.colorInfo != null && format.colorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB) {
      ColorInfo adjustedColorInfo =
          format.colorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_SDR).build();
      format = format.buildUpon().setColorInfo(adjustedColorInfo).build();
    }
    // Images are encoded as video output, adjust their mime type so tests aren't mistakenly
    // skipped. H264 should always be supported as an output mime type.
    if (MimeTypes.isImage(format.sampleMimeType)) {
      return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    }
    return format;
  }

  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    for (SequenceAssetInfo sequence : sequences) {
      stringBuilder.append("(");
      stringBuilder.append(sequence);
      stringBuilder.append(")");
    }
    return stringBuilder.toString();
  }
}
