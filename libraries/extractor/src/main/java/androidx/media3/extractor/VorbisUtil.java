/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor;

import android.util.Base64;
import androidx.annotation.Nullable;
import androidx.media3.common.Metadata;
import androidx.media3.common.Metadata.Entry;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.metadata.flac.PictureFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** Utility methods for parsing Vorbis streams. */
@UnstableApi
public final class VorbisUtil {

  private static final String TAG = "VorbisUtil";

  /**
   * Returns the mapping from VORBIS channel layout to the channel layout expected by Android, or
   * null if the mapping is unchanged.
   *
   * <p>See https://www.xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-810004.3.9 and
   * https://developer.android.com/reference/android/media/AudioFormat#channelMask.
   */
  @Nullable
  public static int[] getVorbisToAndroidChannelLayoutMapping(int channelCount) {
    switch (channelCount) {
      case 3:
        return new int[] {0, 2, 1};
      case 5:
        return new int[] {0, 2, 1, 3, 4};
      case 6:
        return new int[] {0, 2, 1, 5, 3, 4};
      case 7:
        return new int[] {0, 2, 1, 6, 5, 3, 4};
      case 8:
        return new int[] {0, 2, 1, 7, 5, 6, 3, 4};
      default:
        return null;
    }
  }

  /**
   * Returns codec-specific data for configuring a media codec for decoding Vorbis.
   *
   * @param initializationData The initialization data from the ESDS box.
   * @return Codec-specific data for configuring a media codec for decoding Vorbis.
   */
  public static ImmutableList<byte[]> parseVorbisCsdFromEsdsInitializationData(
      byte[] initializationData) {
    ParsableByteArray buffer = new ParsableByteArray(initializationData);
    buffer.skipBytes(1); // 0x02 for vorbis audio

    int identificationHeaderLength = 0;
    while (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == 0xFF) {
      identificationHeaderLength += 0xFF;
      buffer.skipBytes(1);
    }
    identificationHeaderLength += buffer.readUnsignedByte();

    int commentHeaderLength = 0;
    while (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == 0xFF) {
      commentHeaderLength += 0xFF;
      buffer.skipBytes(1);
    }
    commentHeaderLength += buffer.readUnsignedByte();

    // csd-0 is the identification header.
    byte[] csd0 = new byte[identificationHeaderLength];
    int identificationHeaderOffset = buffer.getPosition();
    System.arraycopy(
        /* src= */ initializationData,
        /* srcPos= */ identificationHeaderOffset,
        /* dest= */ csd0,
        /* destPos= */ 0,
        /* length= */ identificationHeaderLength);

    // csd-1 is the setup header, which is the remaining data after the identification and comment
    // headers.
    int setupHeaderOffset =
        identificationHeaderOffset + identificationHeaderLength + commentHeaderLength;
    int setupHeaderLength = initializationData.length - setupHeaderOffset;
    byte[] csd1 = new byte[setupHeaderLength];
    System.arraycopy(
        /* src= */ initializationData,
        /* srcPos= */ setupHeaderOffset,
        /* dest= */ csd1,
        /* destPos= */ 0,
        /* length= */ setupHeaderLength);
    return ImmutableList.of(csd0, csd1);
  }

  /**
   * Builds a {@link Metadata} instance from a list of Vorbis Comments.
   *
   * <p>METADATA_BLOCK_PICTURE comments will be transformed into {@link PictureFrame} entries. All
   * others will be transformed into {@link VorbisComment} entries.
   *
   * @param vorbisComments The raw input of comments, as a key-value pair KEY=VAL.
   * @return The fully parsed Metadata instance. Null if no vorbis comments could be parsed.
   */
  @Nullable
  public static Metadata parseVorbisComments(List<String> vorbisComments) {
    List<Entry> metadataEntries = new ArrayList<>();
    for (int i = 0; i < vorbisComments.size(); i++) {
      String vorbisComment = vorbisComments.get(i);
      String[] keyAndValue = Util.splitAtFirst(vorbisComment, "=");
      if (keyAndValue.length != 2) {
        Log.w(TAG, "Failed to parse Vorbis comment: " + vorbisComment);
        continue;
      }

      if (keyAndValue[0].equals("METADATA_BLOCK_PICTURE")) {
        // This tag is a special cover art tag, outlined by
        // https://wiki.xiph.org/index.php/VorbisComment#Cover_art.
        // Decode it from Base64 and transform it into a PictureFrame.
        try {
          byte[] decoded = Base64.decode(keyAndValue[1], Base64.DEFAULT);
          metadataEntries.add(PictureFrame.fromPictureBlock(new ParsableByteArray(decoded)));
        } catch (RuntimeException e) {
          Log.w(TAG, "Failed to parse vorbis picture", e);
        }
      } else {
        VorbisComment entry = new VorbisComment(keyAndValue[0], keyAndValue[1]);
        metadataEntries.add(entry);
      }
    }

    return metadataEntries.isEmpty() ? null : new Metadata(metadataEntries);
  }

  private VorbisUtil() {
    // Prevent instantiation.
  }
}
