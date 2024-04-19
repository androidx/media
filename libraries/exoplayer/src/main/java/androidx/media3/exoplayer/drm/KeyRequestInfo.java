package androidx.media3.exoplayer.drm;

import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.exoplayer.source.LoadEventInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates info for the sequence of load requests ({@link LoadEventInfo}, which were required
 * to complete loading a DRM key
 */
public class KeyRequestInfo {

  public static class Builder {
    private LoadEventInfo loadEventInfo;
    private final List<LoadEventInfo> retriedLoadRequests;
    private final List<SchemeData> schemeDatas;

    public Builder(List<SchemeData> schemeDatas) {
      this.schemeDatas = schemeDatas;
      retriedLoadRequests = new ArrayList<>();
      loadEventInfo = null;
    }

    public Builder setMainLoadRequest(LoadEventInfo loadEventInfo) {
      this.loadEventInfo = loadEventInfo;
      return this;
    }

    public Builder addRetryLoadRequest(LoadEventInfo loadEventInfo) {
      retriedLoadRequests.add(loadEventInfo);
      return this;
    }

    public KeyRequestInfo build() {
      return new KeyRequestInfo(this);
    }
  }

  /** The {@link LoadEventInfo} for the initial request to laod the key, or null if no load required
   */
  public final LoadEventInfo loadEventInfo;

  /** If the load required multiple retries, the {@link LoadEventInfo} for each retry
   */
  public final List<LoadEventInfo> retriedLoadRequests;

  /** The DRM {@link SchemeData} that identifes the loaded key
   */
  public final List<SchemeData> schemeDatas;

  private KeyRequestInfo(Builder builder) {
    retriedLoadRequests = builder.retriedLoadRequests;
    loadEventInfo = builder.loadEventInfo;
    schemeDatas = builder.schemeDatas;
  }
}
