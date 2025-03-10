package androidx.media3.extractor.mkv;

public class FontAttachment {
  public String fileName;
  public byte[] fileData;

  // Constructor
  public FontAttachment(String fileName, byte[] fileData) {
    this.fileName = fileName;
    this.fileData = fileData;
  }
}
