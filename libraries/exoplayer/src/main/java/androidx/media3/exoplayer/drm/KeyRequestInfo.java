package androidx.media3.exoplayer.drm;

import androidx.annotation.Nullable;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.util.Assertions;
import androidx.media3.exoplayer.source.LoadEventInfo;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Encapsulates info for the sequence of load requests ({@link LoadEventInfo}, which were required
 * to complete loading a DRM key
 */
public class KeyRequestInfo {

  public static class Builder {
    @MonotonicNonNull private LoadEventInfo loadEventInfo;
    private final List<LoadEventInfo> retriedLoadRequests;
    @Nullable private final List<SchemeData> schemeDatas;

    public Builder(@Nullable List<SchemeData> schemeDatas) {
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
      Assertions.checkNotNull(loadEventInfo, "build() called before setMainLoadRequest()");
      return new KeyRequestInfo(this);
    }
  }

  /**
   * The {@link LoadEventInfo} for the initial request to laod the key, or null if no load required
   */
  public final LoadEventInfo loadEventInfo;

  /** If the load required multiple retries, the {@link LoadEventInfo} for each retry */
  public final ImmutableList<LoadEventInfo> retriedLoadRequests;

  /**
   * The DRM {@link SchemeData} that identifies the loaded key, or null if this session uses offline
   * keys. // TODO add sessionId to the KeyLoadInfo maybe?
   */
  @Nullable public final ImmutableList<SchemeData> schemeDatas;

  private KeyRequestInfo(Builder builder) {
    retriedLoadRequests =
        new ImmutableList.Builder<LoadEventInfo>().addAll(builder.retriedLoadRequests).build();
    loadEventInfo = builder.loadEventInfo;
    schemeDatas = builder.schemeDatas == null ? null : ImmutableList.copyOf(builder.schemeDatas);
  }
}
