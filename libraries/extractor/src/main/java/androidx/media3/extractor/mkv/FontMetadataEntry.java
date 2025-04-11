package androidx.media3.extractor.mkv;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.media3.common.Metadata;

/**
 * Represents a font attachment in the MKV file.
 */
public class FontMetadataEntry implements Metadata.Entry {
  private final String fileName;
  private final byte[] fontData;
  private final long uid;

  public FontMetadataEntry(String fileName, byte[] fontData, long uid) {
    this.fileName = fileName;
    this.fontData = fontData;
    this.uid = uid;
  }

  public String getFileName() {
    return fileName;
  }

  public byte[] getFontData() {
    return fontData;
  }

  public long getUid() { return uid; }
}
