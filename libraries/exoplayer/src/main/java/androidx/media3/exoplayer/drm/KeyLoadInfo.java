package androidx.media3.exoplayer.drm;

import androidx.media3.exoplayer.source.LoadEventInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates info for the sequence of load requests ({@link LoadEventInfo}, which were required
 * to complete loading a DRM key
 */
public class KeyLoadInfo {
  public LoadEventInfo loadEventInfo;
  public final List<LoadEventInfo> retriedLoadRequests;

  public KeyLoadInfo() {
    retriedLoadRequests = new ArrayList<>();
  }

  void setMainLoadRequest(LoadEventInfo loadEventInfo) {
    this.loadEventInfo = loadEventInfo;
  }

  void addRetryLoadRequest(LoadEventInfo loadEventInfo) {
    retriedLoadRequests.add(loadEventInfo);
  }
}
