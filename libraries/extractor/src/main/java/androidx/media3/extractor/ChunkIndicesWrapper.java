package androidx.media3.extractor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ChunkIndicesWrapper {

  /** The chunk sizes, in bytes. */
  private final ArrayList<ChunkIndex> chunks = new ArrayList<>();

  private final HashSet<Long> timesIndexed = new HashSet<>();

  public void merge(ChunkIndex chunk) {
    if (chunk.timesUs != null
        && chunk.timesUs.length > 0
        && !timesIndexed.contains(chunk.timesUs[0])) {
      chunks.add(chunk);
      timesIndexed.add(chunk.timesUs[0]);
    }
  }

  public ChunkIndex toChunkIndex() {
    ArrayList<int[]> sizesList = new ArrayList<>();
    ArrayList<long[]> offsetsList = new ArrayList<>();
    ArrayList<long[]> durationsList = new ArrayList<>();
    ArrayList<long[]> timesList = new ArrayList<>();

    for (ChunkIndex chunk : chunks) {
      sizesList.add(chunk.sizes);
      offsetsList.add(chunk.offsets);
      durationsList.add(chunk.durationsUs);
      timesList.add(chunk.timesUs);
    }

    return new ChunkIndex(
        concatInts(sizesList),
        concatLongs(offsetsList),
        concatLongs(durationsList),
        concatLongs(timesList));
  }

  public void clear() {
    chunks.clear();
    timesIndexed.clear();
  }

  public int size() {
    return chunks.size();
  }

  private long[] concatLongs(List<long[]> arrays) {
    int totalLength = 0;
    for (long[] array : arrays) {
      totalLength += array.length;
    }

    long[] res = new long[totalLength];

    int offset = 0;
    for (long[] array : arrays) {
      System.arraycopy(array, 0, res, offset, array.length);
      offset += array.length;
    }

    return res;
  }

  private int[] concatInts(List<int[]> arrays) {
    int totalLength = 0;
    for (int[] array : arrays) {
      totalLength += array.length;
    }

    int[] res = new int[totalLength];

    int offset = 0;
    for (int[] array : arrays) {
      System.arraycopy(array, 0, res, offset, array.length);
      offset += array.length;
    }

    return res;
  }
}
