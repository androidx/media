package androidx.media3.extractor.text.vobsub;


import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

// Much of this is taken from or very similar to PgsParser

/** A {@link SubtitleParser} for Vobsub subtitles. */
@UnstableApi
public final class VobsubParser implements SubtitleParser {

  /**
   * The {@link CueReplacementBehavior} for consecutive {@link CuesWithTiming} emitted by this
   * implementation.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final int DEFAULT_DURATION = 5000000;

  private static final int CMD_COLORS = 3;
  private static final int CMD_ALPHA = 4;
  private static final int CMD_AREA = 5;
  private static final int CMD_OFFSETS = 6;
  private static final int CMD_END = 255;

  private static final int INFLATE_HEADER = 0x78;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;

  public VobsubParser(List<byte[]> initializationData) {

    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
    cueBuilder.parseIdx(new String(initializationData.get(0), StandardCharsets.UTF_8));
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior() {
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
    maybeInflateData(buffer);

    cueBuilder.reset();
    ArrayList<Cue> cues = new ArrayList<>();
    Cue cue = null;

    int blen = buffer.bytesLeft();
    if (blen >= 2) {
      int len = buffer.readUnsignedShort();

      if (len == blen) {
        cueBuilder.parseSpu(buffer);
        cue = cueBuilder.build(buffer);
      }
    }
    if (cue != null) {
      cues.add(cue);
    }
    output.accept(
        new CuesWithTiming(
            cues, /* startTimeUs= */ C.TIME_UNSET, /* durationUs= */ DEFAULT_DURATION));
  }

  // directly taken from PgsParser

  private void maybeInflateData(ParsableByteArray buffer) {
    if (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == INFLATE_HEADER) {
      if (inflater == null) {
        inflater = new Inflater();
      }
      if (Util.inflate(buffer, inflatedBuffer, inflater)) {
        buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
      } // else assume data is not compressed.
    }
  }

  private static final class CueBuilder {

    private boolean hasPlane;
    private boolean hasColors;
    private boolean hasPosition;
    private boolean hasDataOffsets;
    private int[] palette;
    private int planeWidth;
    private int planeHeight;
    private int[] colors;
    private int x0, y0, x1, y1, width, height;
    private int dataOffset0, dataOffset1;
    private int dataSize;

    public CueBuilder() {
      hasPlane = false;
      hasColors = false;
      hasPosition = false;
      hasDataOffsets = false;
      palette = null;
      colors = new int[4];
    }

    public void parseIdx(String idx) {
      for (String line : idx.trim().split("\\r?\\n")) {
        if (line.startsWith("palette: ")) {
          String[] values = line.substring(9).split(",");
          int l = values.length;

          palette = new int[l];

          for (int i = 0; i < l; i++) {
            palette[i] = parseColor(values[i].trim());
          }
        } else if (line.startsWith("size: ")) {
          String[] sizes = line.substring(6).trim().split("x");

          if (sizes.length == 2) {
            try {
              planeWidth = Integer.parseInt(sizes[0]);
              planeHeight = Integer.parseInt(sizes[1]);
              hasPlane = true;
            } catch (Exception e) {
            }
          }
        }
      }
    }

    private int parseColor(String value) {
      try {
        return Integer.parseInt(value, 16);
      } catch (Exception e) {
      }

      return 0;
    }

    public void parseSpu(ParsableByteArray buffer) {

      if (palette == null || !hasPlane) return;

      int pos = buffer.getPosition();

      dataSize = buffer.readUnsignedShort();
      pos += dataSize;
      buffer.setPosition(pos);

      int end = buffer.readUnsignedShort();
      parseControl(buffer, end);
    }

    private void parseControl(ParsableByteArray buffer, int end) {
      int t, d0, d1, d2;

      while (buffer.getPosition() < end && buffer.bytesLeft() > 0) {
        t = buffer.readUnsignedByte();

        switch (t) {
          case CMD_COLORS:
            if (buffer.bytesLeft() < 2) return;

            d0 = buffer.readUnsignedByte();
            d1 = buffer.readUnsignedByte();
            colors[3] = getColor(d0 >> 4);
            colors[2] = getColor(d0 & 0xf);
            colors[1] = getColor(d1 >> 4);
            colors[0] = getColor(d1 & 0xf);
            hasColors = true;
            break;

          case CMD_ALPHA:
            if (buffer.bytesLeft() < 2) return;

            d0 = buffer.readUnsignedByte();
            d1 = buffer.readUnsignedByte();

            colors[3] = setAlpha(colors[3], (d0 >> 4));
            colors[2] = setAlpha(colors[2], (d0 & 0xf));
            colors[1] = setAlpha(colors[1], (d1 >> 4));
            colors[0] = setAlpha(colors[0], (d1 & 0xf));
            break;

          case CMD_AREA:
            if (buffer.bytesLeft() < 6) return;
            d0 = buffer.readUnsignedByte();
            d1 = buffer.readUnsignedByte();
            d2 = buffer.readUnsignedByte();
            x0 = (d0 << 4) | (d1 >> 4);
            x1 = ((d1 & 0xf) << 8) | d2;
            d0 = buffer.readUnsignedByte();
            d1 = buffer.readUnsignedByte();
            d2 = buffer.readUnsignedByte();
            y0 = (d0 << 4) | (d1 >> 4);
            y1 = ((d1 & 0xf) << 8) | d2;
            width = x1 - x0 + 1;
            height = y1 - y0 + 1;
            hasPosition = true;
            break;

          case CMD_OFFSETS:
            if (buffer.bytesLeft() < 4) return;
            dataOffset0 = buffer.readUnsignedShort();
            dataOffset1 = buffer.readUnsignedShort();
            hasDataOffsets = true;
            break;

          case CMD_END:
            return;
        }
      }
    }

    private int getColor(int index) {
      if (index >= 0 && index < palette.length) return palette[index];
      return palette[0];
    }

    private int setAlpha(int color, int alpha) {
      return ((color & 0x00ffffff) | ((alpha * 17) << 24));
    }

    public Cue build(ParsableByteArray buffer) {
      if (palette == null
          || !hasPlane
          || !hasColors
          || !hasPosition
          || !hasDataOffsets
          || width < 2
          || height < 2) {
        return null;
      }
      int[] bitmapData = new int[width * height];
      ParsableBitArray bitBuffer = new ParsableBitArray();

      buffer.setPosition(dataOffset0);
      bitBuffer.reset(buffer);
      parseRleData(bitmapData, bitBuffer, 0);
      buffer.setPosition(dataOffset1);
      bitBuffer.reset(buffer);
      parseRleData(bitmapData, bitBuffer, 1);

      Bitmap bitmap = Bitmap.createBitmap(bitmapData, width, height, Bitmap.Config.ARGB_8888);

      return new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition((float) x0 / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) y0 / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) width / planeWidth)
          .setBitmapHeight((float) height / planeHeight)
          .build();
    }

    private void parseRleData(int[] bitmapData, ParsableBitArray bitBuffer, int y) {
      int x = 0;
      int l = 0;
      int i = y * width;
      Run run = new Run();

      while (true) {
        parseRun(bitBuffer, run);

        l = run.length;
        if (l > width - x) l = width - x;

        while (l > 0) {
          bitmapData[i++] = run.color;
          x++;
          l--;
        }
        if (x >= width) {
          y += 2;
          if (y >= height) break;
          x = 0;
          i = y * width;
          bitBuffer.byteAlign();
        }
      }
    }

    private void parseRun(ParsableBitArray bitBuffer, Run run) {
      int v = 0;
      int t = 1;
      int b;

      while (v < t && t <= 0x40) {
        if (bitBuffer.bitsLeft() < 4) {
          run.color = 0;
          run.length = 0;
          return;
        }
        b = bitBuffer.readBits(4);
        v = (v << 4) | b;
        t <<= 2;
      }
      run.color = colors[v & 3];
      if (v < 4) run.length = width;
      else run.length = (v >> 2);
    }

    public void reset() {
      hasColors = false;
      hasPosition = false;
      hasDataOffsets = false;
    }

    private class Run {
      public int color;
      public int length;
    }
  }
}
