package androidx.media3.extractor.text.pgs;

import static java.lang.Math.min;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.Inflater;

@UnstableApi
public final class PgsParser implements SubtitleParser {

  /**
   * Uses REPLACE so each sample replaces the previous (PGS has no duration; display until next
   * sample). Ensures overlapping cues (e.g. recap at 1s, narration at 1.5s) show correctly. Requires
   * subtitle transcoding during extraction (default in Media3) so the renderer uses
   * ReplacingCuesResolver.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final String TAG = "PgsParser";

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_END = 0x80;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;

  @Nullable
  private Inflater inflater;

  public PgsParser() {
    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
  }

  @Override
  public int getCueReplacementBehavior() {
    return CUE_REPLACEMENT_BEHAVIOR;
  }

  @Override
  public void parse(
      byte[] data,
      int offset,
      int length,
      OutputOptions outputOptions,
      Consumer<CuesWithTiming> output) {

    buffer.reset(data, offset + length);
    buffer.setPosition(offset);

    if (inflater == null) {
      inflater = new Inflater();
    }

    if (Util.maybeInflate(buffer, inflatedBuffer, inflater)) {
      buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
    }

    cueBuilder.reset();
    ArrayList<Cue> cues = new ArrayList<>();
    StringBuilder sectionLog = new StringBuilder("sections:");
    boolean sawPcs = false;
    boolean sawEnd = false;

    while (buffer.bytesLeft() >= 3) {

      int limit = buffer.limit();
      int sectionType = buffer.readUnsignedByte();
      int sectionLength = buffer.readUnsignedShort();

      int nextSectionPosition = buffer.getPosition() + sectionLength;

      if (nextSectionPosition > limit) {
        buffer.setPosition(limit);
        break;
      }

      sectionLog.append(
          sectionType == SECTION_TYPE_PALETTE
              ? " P"
              : sectionType == SECTION_TYPE_BITMAP_PICTURE
                  ? " B"
                  : sectionType == SECTION_TYPE_IDENTIFIER
                      ? " I"
                      : sectionType == SECTION_TYPE_END ? " E" : " ?");

      switch (sectionType) {

        case SECTION_TYPE_PALETTE:
          cueBuilder.parsePaletteSection(buffer, sectionLength);
          break;

        case SECTION_TYPE_BITMAP_PICTURE:
          cueBuilder.parseBitmapSection(buffer, sectionLength);
          // One display set can have multiple objects (I B B E). Build one Cue per complete bitmap.
          if (cueBuilder.isBitmapComplete()) {
            Cue bitmapCue = cueBuilder.build();
            if (bitmapCue != null) {
              cues.add(bitmapCue);
            }
            cueBuilder.resetBitmapOnly();
          }
          break;

        case SECTION_TYPE_IDENTIFIER:
          cueBuilder.parseIdentifierSection(buffer, sectionLength);
          sawPcs = true;
          break;

        case SECTION_TYPE_END:

          Cue cue = cueBuilder.build();

          if (cue != null) {
            cues.add(cue);
          }

          cueBuilder.reset();
          sawEnd = true;
          break;
      }

      buffer.setPosition(nextSectionPosition);
    }

    Log.d(
        TAG,
        "parse sampleLen=" + length
            + " " + sectionLog
            + " cues=" + cues.size()
            + " startTimeUs=TIME_UNSET durationUs=TIME_UNSET");

    if (!cues.isEmpty()) {
      output.accept(
          new CuesWithTiming(
              cues,
              /* startTimeUs= */ C.TIME_UNSET,
              /* durationUs= */ C.TIME_UNSET));
    } else if (sawPcs && sawEnd) {
      // Clear segment (e.g. I E with 0 objects). Emit empty so previous cues disappear at this time.
      output.accept(
          new CuesWithTiming(
              ImmutableList.of(),
              /* startTimeUs= */ C.TIME_UNSET,
              /* durationUs= */ C.TIME_UNSET));
    }
  }

  @Override
  public void reset() {
    cueBuilder.reset();
  }

  private static final int MAX_COMPOSITION_OBJECTS = 32;

  private static final class CueBuilder {

    private final ParsableByteArray bitmapData;
    private final int[] colors;

    private boolean colorsSet;
    /**
     * True if bitmap data has been received for the current object (since the last IDENTIFIER).
     * Used to avoid building a cue with a new position but stale bitmap from the previous object.
     */
    private boolean hasBitmapDataForCurrentObject;

    private int planeWidth;
    private int planeHeight;

    private int bitmapX;
    private int bitmapY;

    private int bitmapWidth;
    private int bitmapHeight;

    /** Per-object positions from PCS (Presentation Composition Segment). Index matches ODS order. */
    private final int[] compositionObjectX = new int[MAX_COMPOSITION_OBJECTS];
    private final int[] compositionObjectY = new int[MAX_COMPOSITION_OBJECTS];
    private int compositionObjectCount;
    private int compositionObjectIndex;

    public CueBuilder() {
      bitmapData = new ParsableByteArray();
      colors = new int[256];
    }

    private void parsePaletteSection(ParsableByteArray buffer, int sectionLength) {

      if ((sectionLength % 5) != 2) {
        return;
      }

      buffer.skipBytes(2);
      Arrays.fill(colors, 0);

      int entryCount = sectionLength / 5;

      for (int i = 0; i < entryCount; i++) {

        int index = buffer.readUnsignedByte();
        int y = buffer.readUnsignedByte();
        int cr = buffer.readUnsignedByte();
        int cb = buffer.readUnsignedByte();
        int a = buffer.readUnsignedByte();

        int r = (int) (y + (1.40200 * (cr - 128)));
        int g = (int) (y - (0.34414 * (cb - 128)) - (0.71414 * (cr - 128)));
        int b = (int) (y + (1.77200 * (cb - 128)));

        colors[index] =
            (a << 24)
                | (Util.constrainValue(r, 0, 255) << 16)
                | (Util.constrainValue(g, 0, 255) << 8)
                | Util.constrainValue(b, 0, 255);
      }

      colorsSet = true;
    }

    private void parseBitmapSection(ParsableByteArray buffer, int sectionLength) {

      if (sectionLength < 4) {
        return;
      }

      buffer.skipBytes(3);
      boolean isBaseSection = (0x80 & buffer.readUnsignedByte()) != 0;

      sectionLength -= 4;

      if (isBaseSection) {

        if (sectionLength < 7) {
          return;
        }

        int totalLength = buffer.readUnsignedInt24();

        if (totalLength < 4) {
          return;
        }

        bitmapWidth = buffer.readUnsignedShort();
        bitmapHeight = buffer.readUnsignedShort();

        bitmapData.reset(totalLength - 4);

        sectionLength -= 7;
      }

      int position = bitmapData.getPosition();
      int limit = bitmapData.limit();

      if (position < limit && sectionLength > 0) {

        int bytesToRead = min(sectionLength, limit - position);

        buffer.readBytes(bitmapData.getData(), position, bytesToRead);

        bitmapData.setPosition(position + bytesToRead);
        hasBitmapDataForCurrentObject = true;
        Log.d(
            TAG,
            "bitmapData received plane=" + planeWidth + "x" + planeHeight
                + " bitmap=" + bitmapWidth + "x" + bitmapHeight
                + " pos=(" + bitmapX + "," + bitmapY + ")");
      }
    }

    /**
     * Parses PCS (Presentation Composition Segment, 0x16). PCS defines per-object positions so
     * multiple ODS in one display set get correct (x,y) instead of all sharing the last position.
     */
    private void parseIdentifierSection(ParsableByteArray buffer, int sectionLength) {

      if (sectionLength < 11) {
        return;
      }

      hasBitmapDataForCurrentObject = false;
      planeWidth = buffer.readUnsignedShort();
      planeHeight = buffer.readUnsignedShort();
      buffer.skipBytes(6); // frame rate, composition number, state, palette update, palette id
      int numObjects = buffer.readUnsignedByte();
      int remaining = sectionLength - 11;

      compositionObjectCount = 0;
      compositionObjectIndex = 0;
      for (int i = 0; i < numObjects && remaining >= 8 && compositionObjectCount < MAX_COMPOSITION_OBJECTS; i++) {
        buffer.skipBytes(2); // object_id
        buffer.skipBytes(1); // window_id
        int croppedFlag = buffer.readUnsignedByte();
        int x = buffer.readUnsignedShort();
        int y = buffer.readUnsignedShort();
        remaining -= 8;
        if ((croppedFlag & 0x40) != 0 && remaining >= 8) {
          buffer.skipBytes(8); // cropping position and size
          remaining -= 8;
        }
        compositionObjectX[compositionObjectCount] = x;
        compositionObjectY[compositionObjectCount] = y;
        compositionObjectCount++;
      }
      bitmapX = compositionObjectCount > 0 ? compositionObjectX[0] : 0;
      bitmapY = compositionObjectCount > 0 ? compositionObjectY[0] : 0;
      Log.d(
          TAG,
          "PCS plane=" + planeWidth + "x" + planeHeight
              + " objects=" + compositionObjectCount
              + " pos0=(" + bitmapX + "," + bitmapY + ")");
    }

    @Nullable
    public Cue build() {

      if (!hasBitmapDataForCurrentObject) {
        Log.d(TAG, "build() skip: no bitmap for current object pos=(" + bitmapX + "," + bitmapY + ")");
        return null;
      }
      if (planeWidth == 0
          || planeHeight == 0
          || bitmapWidth == 0
          || bitmapHeight == 0
          || bitmapData.limit() == 0
          || bitmapData.getPosition() != bitmapData.limit()
          || !colorsSet) {

        Log.d(
            TAG,
            "build() skip: incomplete plane=" + planeWidth + "x" + planeHeight
                + " bitmap=" + bitmapWidth + "x" + bitmapHeight
                + " limit=" + bitmapData.limit() + " pos=" + bitmapData.getPosition()
                + " colorsSet=" + colorsSet);
        return null;
      }

      bitmapData.setPosition(0);

      int[] argbBitmapData = new int[bitmapWidth * bitmapHeight];

      int index = 0;

      while (index < argbBitmapData.length) {

        int colorIndex = bitmapData.readUnsignedByte();

        if (colorIndex != 0) {

          argbBitmapData[index++] = colors[colorIndex];

        } else {

          int switchBits = bitmapData.readUnsignedByte();

          if (switchBits != 0) {

            int runLength =
                (switchBits & 0x40) == 0
                    ? (switchBits & 0x3F)
                    : (((switchBits & 0x3F) << 8)
                        | bitmapData.readUnsignedByte());

            int color =
                (switchBits & 0x80) == 0
                    ? colors[0]
                    : colors[bitmapData.readUnsignedByte()];

            Arrays.fill(
                argbBitmapData,
                index,
                index + runLength,
                color);

            index += runLength;
          }
        }
      }

      Bitmap bitmap =
          Bitmap.createBitmap(
              argbBitmapData,
              bitmapWidth,
              bitmapHeight,
              Bitmap.Config.ARGB_8888);

      int x = bitmapX;
      int y = bitmapY;
      if (compositionObjectIndex < compositionObjectCount) {
        x = compositionObjectX[compositionObjectIndex];
        y = compositionObjectY[compositionObjectIndex];
        compositionObjectIndex++;
      }
      float position = (float) x / planeWidth;
      float line = (float) y / planeHeight;
      Log.d(
          TAG,
          "build() cue objIndex=" + (compositionObjectIndex - 1)
              + " pos=(" + x + "," + y + ") norm=(" + position + "," + line + ")"
              + " size=" + bitmapWidth + "x" + bitmapHeight);
      return new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition(position)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine(line, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) bitmapWidth / planeWidth)
          .setBitmapHeight((float) bitmapHeight / planeHeight)
          .build();
    }

    /**
     * Returns true if the current bitmap is fully received and can be built (same conditions as
     * build() returning non-null). Used to emit one Cue per object when a segment has I B B E.
     */
    public boolean isBitmapComplete() {
      return hasBitmapDataForCurrentObject
          && planeWidth != 0
          && planeHeight != 0
          && bitmapWidth != 0
          && bitmapHeight != 0
          && bitmapData.limit() != 0
          && bitmapData.getPosition() == bitmapData.limit()
          && colorsSet;
    }

    /** Resets only bitmap-related state so the next BITMAP_PICTURE can be parsed. Keeps PCS positions. */
    public void resetBitmapOnly() {
      bitmapWidth = 0;
      bitmapHeight = 0;
      bitmapData.reset(0);
      hasBitmapDataForCurrentObject = false;
    }

    public void reset() {
      planeWidth = 0;
      planeHeight = 0;
      bitmapX = 0;
      bitmapY = 0;
      bitmapWidth = 0;
      bitmapHeight = 0;
      bitmapData.reset(0);
      colorsSet = false;
      hasBitmapDataForCurrentObject = false;
      compositionObjectCount = 0;
      compositionObjectIndex = 0;
    }
  }
}