/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.test.utils.robolectric;

import static java.lang.Math.max;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.dvbsi.AppInfoTable;
import androidx.media3.extractor.metadata.emsg.EventMessage;
import androidx.media3.extractor.metadata.flac.PictureFrame;
import androidx.media3.extractor.metadata.icy.IcyHeaders;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import androidx.media3.extractor.metadata.id3.Id3Frame;
import androidx.media3.extractor.metadata.mp4.MotionPhotoMetadata;
import androidx.media3.extractor.metadata.mp4.SlowMotionData;
import androidx.media3.extractor.metadata.mp4.SmtaMetadataEntry;
import androidx.media3.extractor.metadata.scte35.SpliceCommand;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import androidx.media3.test.utils.CapturingRenderersFactory;
import androidx.media3.test.utils.Dumper;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class to capture output from a playback test.
 *
 * <p>Implements {@link Dumper.Dumpable} so the output can be easily dumped to a string for
 * comparison against previous test runs.
 */
@UnstableApi
public final class PlaybackOutput implements Dumper.Dumpable {

  private final CapturingRenderersFactory capturingRenderersFactory;

  private final List<Metadata> metadatas;
  private final List<CueGroup> subtitles;
  private final List<List<Cue>> subtitlesFromDeprecatedTextOutput;

  private PlaybackOutput(ExoPlayer player, CapturingRenderersFactory capturingRenderersFactory) {
    this.capturingRenderersFactory = capturingRenderersFactory;

    metadatas = Collections.synchronizedList(new ArrayList<>());
    subtitles = Collections.synchronizedList(new ArrayList<>());
    subtitlesFromDeprecatedTextOutput = Collections.synchronizedList(new ArrayList<>());
    // TODO: Consider passing playback position into MetadataOutput. Calling
    // player.getCurrentPosition() inside onMetadata will likely be non-deterministic
    // because renderer-thread != playback-thread.
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMetadata(Metadata metadata) {
            metadatas.add(metadata);
          }

          @SuppressWarnings("deprecation") // Intentionally testing deprecated output
          @Override
          public void onCues(List<Cue> cues) {
            subtitlesFromDeprecatedTextOutput.add(cues);
          }

          @Override
          public void onCues(CueGroup cueGroup) {
            subtitles.add(cueGroup);
          }
        });
  }

  /**
   * Create an instance that captures the metadata and text output from {@code player} and the audio
   * and video output via {@code capturingRenderersFactory}.
   *
   * <p>Must be called <b>before</b> playback to ensure metadata and text output is captured
   * correctly.
   *
   * @param player The {@link ExoPlayer} to capture metadata and text output from.
   * @param capturingRenderersFactory The {@link CapturingRenderersFactory} to capture audio and
   *     video output from.
   * @return A new instance that can be used to dump the playback output.
   */
  public static PlaybackOutput register(
      ExoPlayer player, CapturingRenderersFactory capturingRenderersFactory) {
    return new PlaybackOutput(player, capturingRenderersFactory);
  }

  @Override
  public void dump(Dumper dumper) {
    capturingRenderersFactory.dump(dumper);

    dumpMetadata(dumper);
    dumpSubtitles(dumper);
  }

  private void dumpMetadata(Dumper dumper) {
    if (metadatas.isEmpty()) {
      return;
    }
    dumper.startBlock("MetadataOutput");
    for (int i = 0; i < metadatas.size(); i++) {
      dumper.startBlock("Metadata[" + i + "]");
      Metadata metadata = metadatas.get(i);
      dumper.add("presentationTimeUs", metadata.presentationTimeUs);
      for (int j = 0; j < metadata.length(); j++) {
        dumper.add("entry[" + j + "]", getEntryAsString(metadata.get(j)));
      }
      dumper.endBlock();
    }
    dumper.endBlock();
  }

  /**
   * Returns {@code entry.toString()} if we know the implementation overrides it, otherwise returns
   * the simple class name.
   */
  private static String getEntryAsString(Metadata.Entry entry) {
    if (entry instanceof EventMessage
        || entry instanceof PictureFrame
        || entry instanceof VorbisComment
        || entry instanceof Id3Frame
        || entry instanceof MdtaMetadataEntry
        || entry instanceof MotionPhotoMetadata
        || entry instanceof SlowMotionData
        || entry instanceof SmtaMetadataEntry
        || entry instanceof AppInfoTable
        || entry instanceof IcyHeaders
        || entry instanceof IcyInfo
        || entry instanceof SpliceCommand
        || "androidx.media3.exoplayer.hls.HlsTrackMetadataEntry"
            .equals(entry.getClass().getCanonicalName())) {
      return entry.toString();
    } else {
      return entry.getClass().getSimpleName();
    }
  }

  private void dumpSubtitles(Dumper dumper) {
    if (subtitles.size() != subtitlesFromDeprecatedTextOutput.size()) {
      throw new IllegalStateException(
          "Expected subtitles to be of equal length from both implementations of onCues method.");
    }

    if (subtitles.isEmpty()) {
      return;
    }
    dumper.startBlock("TextOutput");
    for (int i = 0; i < subtitles.size(); i++) {
      dumper.startBlock("Subtitle[" + i + "]");
      // TODO: Solving https://github.com/google/ExoPlayer/issues/9672 will allow us to remove this
      // hack of forcing presentationTimeUs to be >= 0.
      dumper.add("presentationTimeUs", max(0, subtitles.get(i).presentationTimeUs));
      ImmutableList<Cue> subtitle = subtitles.get(i).cues;
      if (!subtitle.equals(subtitlesFromDeprecatedTextOutput.get(i))) {
        throw new IllegalStateException(
            "Expected subtitle to be equal from both implementations of onCues method for index "
                + i);
      }
      if (subtitle.isEmpty()) {
        dumper.add("Cues", ImmutableList.of());
      }
      for (int j = 0; j < subtitle.size(); j++) {
        dumper.startBlock("Cue[" + j + "]");
        Cue cue = subtitle.get(j);
        dumpIfNotEqual(dumper, "text", cue.text, null);
        dumpIfNotEqual(dumper, "textAlignment", cue.textAlignment, null);
        dumpBitmap(dumper, cue.bitmap);
        dumpIfNotEqual(dumper, "line", cue.line, Cue.DIMEN_UNSET);
        dumpIfNotEqual(dumper, "lineType", cue.lineType, Cue.TYPE_UNSET);
        dumpIfNotEqual(dumper, "lineAnchor", cue.lineAnchor, Cue.TYPE_UNSET);
        dumpIfNotEqual(dumper, "position", cue.position, Cue.DIMEN_UNSET);
        dumpIfNotEqual(dumper, "positionAnchor", cue.positionAnchor, Cue.TYPE_UNSET);
        dumpIfNotEqual(dumper, "size", cue.size, Cue.DIMEN_UNSET);
        dumpIfNotEqual(dumper, "bitmapHeight", cue.bitmapHeight, Cue.DIMEN_UNSET);
        if (cue.windowColorSet) {
          dumper.add("cue.windowColor", cue.windowColor);
        }
        dumpIfNotEqual(dumper, "textSizeType", cue.textSizeType, Cue.TYPE_UNSET);
        dumpIfNotEqual(dumper, "textSize", cue.textSize, Cue.DIMEN_UNSET);
        dumpIfNotEqual(dumper, "verticalType", cue.verticalType, Cue.TYPE_UNSET);
        dumper.endBlock();
      }
      dumper.endBlock();
    }
    dumper.endBlock();
  }

  private static void dumpIfNotEqual(
      Dumper dumper, String field, @Nullable Object actual, @Nullable Object comparison) {
    if (!Util.areEqual(actual, comparison)) {
      dumper.add(field, actual);
    }
  }

  private static void dumpBitmap(Dumper dumper, @Nullable Bitmap bitmap) {
    if (bitmap == null) {
      return;
    }
    byte[] bytes = new byte[bitmap.getByteCount()];
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes));
    dumper.add("bitmap", bytes);
  }
}
