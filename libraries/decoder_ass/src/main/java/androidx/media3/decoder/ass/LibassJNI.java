/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.decoder.ass;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.extractor.text.ssa.SsaParser;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LibassJNI {

  private final String TAG = "LibassJNI";
  private final long assLibraryPtr;
  private final long assRendererPtr;
  private final Map<String, Long> assTrackPtrs = new HashMap<>();

  @Nullable
  private Integer frame_width = null;
  @Nullable
  private Integer frame_height = null;

  private @C.ColorSpace int videoColorSpace;
  private @C.ColorRange int videoColorRange;

  public LibassJNI() {
    if (!AssLibrary.isAvailable()) {
      throw new RuntimeException("Libass native library is not available");
    }

    assLibraryPtr = assLibraryInit();
    if (assLibraryPtr == 0) {
      throw new RuntimeException("Failed to initialize ASS_Library");
    }

    assRendererPtr = assRendererInit(assLibraryPtr);
    if (assRendererPtr == 0) {
      throw new RuntimeException("Failed to initialize ASS_Renderer");
    }

    this.videoColorSpace = Format.NO_VALUE;
    this.videoColorRange = Format.NO_VALUE;
  }

  /**
   * Sets the frame size for the ASS_Renderer.
   *
   * @param width  The width of the frame in pixels.
   * @param height The height of the frame in pixels.
   */
  public void setFrameSize(int width, int height) {
    frame_width = width;
    frame_height = height;
    assSetFrameSize(assRendererPtr, width, height);
  }

  /**
   * Sets the storage size for the ASS_Renderer.
   *
   * @param width  The width of the storage in pixels.
   * @param height The height of the storage in pixels.
   */
  public void setStorageSize(int width, int height) {
    assSetStorageSize(assRendererPtr, width, height);
  }

  /**
   * Sets the video color space and color range
   * that will be used for the alpha blending.
   *
   * @param videoColorSpace The video color space.
   * @param videoColorRange The video color range.
   */
  public void setVideoColorProperties(@C.ColorSpace int videoColorSpace, @C.ColorRange int videoColorRange) {
    this.videoColorSpace = videoColorSpace;
    this.videoColorRange = videoColorRange;
  }

  /**
   * Creates a new ASS_Track instance if it does not already exist for the given format ID.
   *
   * @param formatId The unique identifier for the format.
   * @throws RuntimeException if the ASS_Track creation fails.
   */
  public void createTrack(String formatId) {
    if (assTrackPtrs.containsKey(formatId)) {
      return;
    }

    long trackPtr = assNewTrack(assLibraryPtr);
    if (trackPtr == 0) {
      throw new RuntimeException("Failed to create ASS_Track");
    }
    assTrackPtrs.put(formatId, trackPtr);
  }

  /**
   * Releases a track when it's no longer needed.
   *
   * @param trackId The ID of the track to release.
   */
  public void releaseTrack(String trackId) {
    Long trackPtr = assTrackPtrs.remove(trackId);
    assFreeTrack(trackPtr);
  }

  /**
   * Prepares and formats data to then call {@link #assProcessChunk}()}.
   *
   * @param data     The ass subtitle event.
   * @param offset   The index in {@code data} to start reading from (inclusive).
   * @param length   The number of bytes to read from {@code data}.
   * @param timecode The timestamp in milliseconds.
   * @param trackId  The ID of the track to process subtitles from.
   */
  public void prepareProcessChunk(
      byte[] data,
      int offset,
      int length,
      long timecode,
      String trackId) {
    Long trackPtr = assTrackPtrs.get(trackId);
    if (trackPtr == null) {
      Log.w(TAG, "The trackID '" + trackId + "' isn't registered.");
      return;
    }

    // Find the first and second comma positions
    int firstComma = -1, secondComma = -1, commaCount = 0;
    for (int i = offset; i < offset + length; i++) {
      if (data[i] == ',') {
        commaCount++;
        if (commaCount == 1) {
          firstComma = i;
        } else {
          secondComma = i;
          break;
        }
      }
    }

    // If event formatting is wrong
    if (secondComma == -1) {
      String dialogueLine = new String(data, offset, length, StandardCharsets.UTF_8);
      Log.w(TAG, "Skipping dialogue line with fewer columns than 2: " + dialogueLine);
      return;
    }

    // Extract the timestamp
    int timestampLength = secondComma - firstComma - 1;
    byte[] timestampBytes = new byte[timestampLength];
    System.arraycopy(data, firstComma + 1, timestampBytes, 0, timestampLength);

    long durationMs = SsaParser.parseTimecodeUs(new String(timestampBytes)) / 1000;

    // Isolate the part after the end time.
    // Ex:
    //  From: "Dialogue: 0:00:00:00,0:00:05:00,1,0,Default,,0,0,0,,Line Text"
    //  Result:                               "1,0,Default,,0,0,0,,Line Text"
    int line_offset = secondComma + 1;
    int line_length = offset + length - secondComma - 1;

    assProcessChunk(trackPtr, data, line_offset, line_length, timecode, durationMs);
  }

  /**
   * Processes codec private data (subtitle headers) for a specific track.
   *
   * @param trackId The ID of the track to process the data for.
   * @param data    The codec private data bytes.
   */
  public void processCodecPrivate(String trackId, byte[] data) {
    Long trackPtr = assTrackPtrs.get(trackId);
    assProcessCodecPrivate(trackPtr, data);
  }

  /**
   * Renders a frame for a specific track at the given timestamp.
   *
   * @param trackId The ID of the track to render.
   * @param timeMs  The timestamp in milliseconds.
   * @return A bitmap with the rendered subtitle image, or null if no image was rendered.
   */
  @Nullable
  public AssRenderResult renderFrame(String trackId, long timeMs) {
    Long trackPtr = assTrackPtrs.get(trackId);
    if (trackPtr == null) {
      Log.w(TAG, "The trackID '" + trackId + "' isn't registered.");
      return null;
    }
    if (frame_width == null || frame_height == null) {
      throw new RuntimeException("Frame size has not been set");
    }
    return assRenderFrame(assRendererPtr, trackPtr, frame_width, frame_height, timeMs,
        videoColorSpace, videoColorRange);
  }

  /**
   * Loads a font from its raw byte data and adds it to the ASS_Library.
   *
   * @param fileName The name of the font file.
   * @param fontData The raw byte data of the font.
   */
  public void loadFont(String fileName, byte[] fontData) {
    assAddFont(assLibraryPtr, fileName, fontData);
  }

  @Override
  protected void finalize() throws Throwable {
    try {
      for (Map.Entry<String, Long> entry : assTrackPtrs.entrySet()) {
        if (entry.getValue() != 0) {
          assFreeTrack(entry.getValue());
        }
      }

      if (assRendererPtr != 0) {
        assRendererDone(assRendererPtr);
      }
      if (assLibraryPtr != 0) {
        assLibraryDone(assLibraryPtr);
      }
    } finally {
      super.finalize();
    }
  }

  /**
   * Adds a font to the ASS_Library.
   *
   * @param assLibraryPtr The pointer to the native ASS_Library instance.
   * @param fontName      The name of the font.
   * @param fontData      The raw byte data of the font.
   */
  private native void assAddFont(long assLibraryPtr, String fontName, byte[] fontData);

  /**
   * Initializes the native ASS_Library and returns its pointer as a long.
   * This pointer must be passed to native methods that require it.
   */
  private native long assLibraryInit();

  /**
   * Destroys the native ASS_Library instance.
   *
   * @param assLibraryPtr The pointer to the native ASS_Library instance.
   */
  private native void assLibraryDone(long assLibraryPtr);

  /**
   * Initializes the native ASS_Renderer and returns its pointer as a long.
   * This pointer must be passed to native methods that require it.
   *
   * @param assLibraryPtr The pointer to the native ASS_Library instance.
   * @return The pointer to the native ASS_Renderer instance.
   */
  private native long assRendererInit(long assLibraryPtr);

  /**
   * Destroys the native ASS_Renderer instance.
   *
   * @param assRendererPtr The pointer to the native ASS_Renderer instance.
   */
  private native void assRendererDone(long assRendererPtr);

  /**
   * Sets the frame size for the ASS_Renderer.
   *
   * @param assRendererPtr The pointer to the native ASS_Renderer instance.
   * @param width          The width of the frame in pixels.
   * @param height         The height of the frame in pixels.
   */
  private native void assSetFrameSize(long assRendererPtr, int width, int height);

  /**
   * Sets the storage size for the ASS_Renderer.
   *
   * @param assRendererPtr The pointer to the native ASS_Renderer instance.
   * @param width          The width of the storage in pixels.
   * @param height         The height of the storage in pixels.
   */
  private native void assSetStorageSize(long assRendererPtr, int width, int height);

  /**
   * Creates a new ASS_Track instance.
   *
   * @param assLibraryPtr The pointer to the native ASS_Library instance.
   * @return The pointer to the created ASS_Track instance.
   */
  private native long assNewTrack(long assLibraryPtr);

  /**
   * Destroys the ASS_Track instance.
   *
   * @param assTrackPtr The pointer to the native ASS_Track instance.
   */
  private native void assFreeTrack(long assTrackPtr);


  /**
   * Process a chunk of subtitle stream format
   *
   * @param assTrackPtr The pointer to the native ASS_Track instance.
   * @param eventData   The ass subtitle event.
   * @param offset      The index in {@code eventData} to start reading from (inclusive).
   * @param length      The number of bytes to read from {@code eventData}.
   * @param timecode    The timestamp in milliseconds.
   * @param duration    The duration of the event.
   */
  private native void assProcessChunk(long assTrackPtr, byte[] eventData, int offset, int length,
      long timecode, long duration);


  private native AssRenderResult assRenderFrame(long assRendererPtr, long assTrackPtr,
      int frame_width, int frame_height, long timeMs, int colorSpace, int colorRange);


  /**
   * Processes codec private data (subtitle headers) for the ASS_Track.
   *
   * @param assTrackPtr The pointer to the native ASS_Track instance.
   * @param data        The codec private data bytes.
   */
  private native void assProcessCodecPrivate(long assTrackPtr, byte[] data);
}
