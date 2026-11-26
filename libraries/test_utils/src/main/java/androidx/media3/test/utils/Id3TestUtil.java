/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static androidx.media3.extractor.metadata.id3.Id3Decoder.ID3_HEADER_LENGTH;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.US_ASCII;

import androidx.media3.common.util.UnstableApi;
import com.google.common.base.CharMatcher;

/** Utility methods for building raw ID3v2 tags and frames in tests. */
@UnstableApi
public final class Id3TestUtil {

  /** Specification for an ID3v2 frame. */
  public static final class FrameSpec {

    private final String frameId;
    private final byte[] frameData;
    private final int flags;

    /**
     * Creates a frame specification with zero flags.
     *
     * @param frameId The ASCII frame identifier (3 bytes for ID3v2.2, 4 bytes for ID3v2.3/v2.4).
     * @param frameData The raw frame payload bytes.
     */
    public FrameSpec(String frameId, byte[] frameData) {
      this(frameId, frameData, /* flags= */ 0);
    }

    /**
     * Creates a frame specification.
     *
     * @param frameId The ASCII frame identifier (3 bytes for ID3v2.2, 4 bytes for ID3v2.3/v2.4).
     * @param frameData The raw frame payload bytes.
     * @param flags The 16-bit frame header flags (must be in {@code [0, 0xFFFF]}, and {@code 0} for
     *     ID3v2.2).
     */
    public FrameSpec(String frameId, byte[] frameData, int flags) {
      checkArgument(
          CharMatcher.ascii().matchesAllOf(frameId), "Frame ID must be ASCII: %s", frameId);
      checkArgument(flags >= 0 && flags <= 0xFFFF, "Frame flags out of range: %s", flags);
      this.frameId = frameId;
      this.frameData = frameData;
      this.flags = flags;
    }
  }

  private static final int FRAME_HEADER_LENGTH_V2 = 6;
  private static final int FRAME_HEADER_LENGTH_V3_V4 = 10;

  /**
   * Builds an ID3v2.4 tag containing a single frame.
   *
   * @param frameId The 4-byte ASCII frame identifier.
   * @param frameData The raw frame payload bytes.
   * @return The encoded ID3v2.4 tag bytes.
   */
  public static byte[] buildSingleFrameTag(String frameId, byte[] frameData) {
    return buildSingleFrameTag(/* majorVersion= */ 4, frameId, frameData);
  }

  /**
   * Builds an ID3v2 tag of the given major version containing a single frame.
   *
   * @param majorVersion The ID3v2 major version ({@code 2}, {@code 3}, or {@code 4}).
   * @param frameId The ASCII frame identifier (3 bytes for v2.2, 4 bytes for v2.3/v2.4).
   * @param frameData The raw frame payload bytes.
   * @return The encoded ID3v2 tag bytes.
   */
  public static byte[] buildSingleFrameTag(int majorVersion, String frameId, byte[] frameData) {
    return buildTag(majorVersion, new FrameSpec(frameId, frameData));
  }

  /**
   * Builds an ID3v2.4 tag containing the specified frames.
   *
   * @param frames The frames to include in the tag.
   * @return The encoded ID3v2.4 tag bytes.
   */
  public static byte[] buildTag(FrameSpec... frames) {
    return buildTag(/* majorVersion= */ 4, frames);
  }

  /**
   * Builds an ID3v2 tag of the given major version containing the specified frames.
   *
   * @param majorVersion The ID3v2 major version ({@code 2}, {@code 3}, or {@code 4}).
   * @param frames The frames to include in the tag.
   * @return The encoded ID3v2 tag bytes.
   */
  public static byte[] buildTag(int majorVersion, FrameSpec... frames) {
    return buildTag(majorVersion, /* flags= */ 0, buildFrames(majorVersion, frames));
  }

  /**
   * Builds an ID3v2 tag header prepended to the provided raw frame payload bytes.
   *
   * @param majorVersion The ID3v2 major version ({@code 2}, {@code 3}, or {@code 4}).
   * @param flags The 8-bit tag header flags (must be in {@code [0, 0xFF]}).
   * @param framesData The raw payload bytes following the 10-byte ID3v2 tag header.
   * @return The encoded ID3v2 tag bytes.
   */
  public static byte[] buildTag(int majorVersion, int flags, byte[] framesData) {
    checkArgument(
        majorVersion >= 2 && majorVersion <= 4, "Unsupported majorVersion: %s", majorVersion);
    checkArgument(flags >= 0 && flags <= 0xFF, "Tag flags out of range: %s", flags);
    int framesSize = framesData.length;
    checkArgument(framesSize < (1 << 28), "Tag payload too large: %s", framesSize);
    byte[] tagData = new byte[ID3_HEADER_LENGTH + framesSize];
    tagData[0] = 'I';
    tagData[1] = 'D';
    tagData[2] = '3';
    tagData[3] = (byte) majorVersion;
    tagData[4] = 0;
    tagData[5] = (byte) flags;
    tagData[6] = (byte) ((framesSize >>> 21) & 0x7F);
    tagData[7] = (byte) ((framesSize >>> 14) & 0x7F);
    tagData[8] = (byte) ((framesSize >>> 7) & 0x7F);
    tagData[9] = (byte) (framesSize & 0x7F);
    System.arraycopy(framesData, 0, tagData, ID3_HEADER_LENGTH, framesSize);
    return tagData;
  }

  /**
   * Builds concatenated raw ID3v2.4 frames without a tag header.
   *
   * @param frames The frames to encode.
   * @return The encoded frame bytes.
   */
  public static byte[] buildFrames(FrameSpec... frames) {
    return buildFrames(/* majorVersion= */ 4, frames);
  }

  /**
   * Builds concatenated raw ID3v2 frames for the given major version without a tag header.
   *
   * @param majorVersion The ID3v2 major version ({@code 2}, {@code 3}, or {@code 4}).
   * @param frames The frames to encode.
   * @return The encoded frame bytes.
   */
  public static byte[] buildFrames(int majorVersion, FrameSpec... frames) {
    checkArgument(
        majorVersion >= 2 && majorVersion <= 4, "Unsupported majorVersion: %s", majorVersion);
    int frameHeaderLength = majorVersion == 2 ? FRAME_HEADER_LENGTH_V2 : FRAME_HEADER_LENGTH_V3_V4;
    int totalLength = 0;
    for (FrameSpec frame : frames) {
      if (majorVersion == 2) {
        checkArgument(
            frame.frameId.length() == 3, "ID3v2.2 frame ID must be 3 bytes: %s", frame.frameId);
        checkArgument(
            frame.frameData.length < (1 << 24),
            "ID3v2.2 frame data too large: %s",
            frame.frameData.length);
        checkArgument(frame.flags == 0, "ID3v2.2 frames do not support flags: %s", frame.flags);
      } else {
        checkArgument(
            frame.frameId.length() == 4,
            "ID3v2.%s frame ID must be 4 bytes: %s",
            majorVersion,
            frame.frameId);
        if (majorVersion == 4) {
          checkArgument(
              frame.frameData.length < (1 << 28),
              "ID3v2.4 frame data too large: %s",
              frame.frameData.length);
        }
      }
      totalLength = Math.addExact(totalLength, frameHeaderLength);
      totalLength = Math.addExact(totalLength, frame.frameData.length);
    }
    byte[] framesData = new byte[totalLength];
    int offset = 0;
    for (FrameSpec frame : frames) {
      byte[] frameData = frame.frameData;
      byte[] frameIdBytes = frame.frameId.getBytes(US_ASCII);
      if (majorVersion == 2) {
        framesData[offset++] = frameIdBytes[0];
        framesData[offset++] = frameIdBytes[1];
        framesData[offset++] = frameIdBytes[2];
        framesData[offset++] = (byte) (frameData.length >>> 16);
        framesData[offset++] = (byte) (frameData.length >>> 8);
        framesData[offset++] = (byte) frameData.length;
      } else {
        framesData[offset++] = frameIdBytes[0];
        framesData[offset++] = frameIdBytes[1];
        framesData[offset++] = frameIdBytes[2];
        framesData[offset++] = frameIdBytes[3];
        if (majorVersion == 3) {
          framesData[offset++] = (byte) (frameData.length >>> 24);
          framesData[offset++] = (byte) (frameData.length >>> 16);
          framesData[offset++] = (byte) (frameData.length >>> 8);
          framesData[offset++] = (byte) frameData.length;
        } else {
          framesData[offset++] = (byte) ((frameData.length >>> 21) & 0x7F);
          framesData[offset++] = (byte) ((frameData.length >>> 14) & 0x7F);
          framesData[offset++] = (byte) ((frameData.length >>> 7) & 0x7F);
          framesData[offset++] = (byte) (frameData.length & 0x7F);
        }
        framesData[offset++] = (byte) (frame.flags >>> 8);
        framesData[offset++] = (byte) frame.flags;
      }
      System.arraycopy(frameData, 0, framesData, offset, frameData.length);
      offset += frameData.length;
    }
    return framesData;
  }

  private Id3TestUtil() {}
}
