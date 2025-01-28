/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.metrics.LogSessionId;
import android.net.Uri;
import android.os.PersistableBundle;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Contract tests for verifying consistent behavior across {@link MediaExtractor} implementations.
 *
 * <p>This tests both platform {@link MediaExtractor} and its compat implementation {@link
 * MediaExtractorCompat}.
 */
@RunWith(Parameterized.class)
public class MediaExtractorContractTest {

  @Parameters(name = "{0}")
  public static ImmutableList<Function<Context, MediaExtractorProxy>>
      mediaExtractorProxyFactories() {
    return ImmutableList.of(
        new Function<Context, MediaExtractorProxy>() {
          @Override
          public MediaExtractorProxy apply(Context context) {
            return new FrameworkMediaExtractorProxy();
          }

          @Override
          public String toString() {
            return FrameworkMediaExtractorProxy.class.getSimpleName();
          }
        },
        new Function<Context, MediaExtractorProxy>() {
          @Override
          public MediaExtractorProxy apply(Context context) {
            return new CompatMediaExtractorProxy(context);
          }

          @Override
          public String toString() {
            return CompatMediaExtractorProxy.class.getSimpleName();
          }
        });
  }

  @Parameter public Function<Context, MediaExtractorProxy> mediaExtractorProxyFactory;

  private MediaExtractorProxy mediaExtractorProxy;

  @Before
  public void setUp() {
    mediaExtractorProxy =
        mediaExtractorProxyFactory.apply(ApplicationProvider.getApplicationContext());
  }

  @After
  public void tearDown() {
    mediaExtractorProxy.release();
  }

  @Test
  public void setDataSource_withAssetFileDescriptor_returnsCorrectTrackCount() throws IOException {
    AssetFileDescriptor afd =
        ApplicationProvider.getApplicationContext().getAssets().openFd("media/mp4/sample.mp4");

    mediaExtractorProxy.setDataSource(afd);

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  private static class FrameworkMediaExtractorProxy implements MediaExtractorProxy {

    private final MediaExtractor mediaExtractor;

    public FrameworkMediaExtractorProxy() {
      this.mediaExtractor = new MediaExtractor();
    }

    @Override
    public boolean advance() {
      return mediaExtractor.advance();
    }

    @Override
    public long getCachedDuration() {
      return mediaExtractor.getCachedDuration();
    }

    @Override
    @RequiresApi(24)
    public Object getDrmInitData() {
      return mediaExtractor.getDrmInitData();
    }

    @Override
    @RequiresApi(31)
    public LogSessionId getLogSessionId() {
      return mediaExtractor.getLogSessionId();
    }

    @Override
    @RequiresApi(26)
    public PersistableBundle getMetrics() {
      return mediaExtractor.getMetrics();
    }

    @Override
    public Map<UUID, byte[]> getPsshInfo() {
      return mediaExtractor.getPsshInfo();
    }

    @Override
    public boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info) {
      return mediaExtractor.getSampleCryptoInfo(info);
    }

    @Override
    public int getSampleFlags() {
      return mediaExtractor.getSampleFlags();
    }

    @Override
    @RequiresApi(28)
    public long getSampleSize() {
      return mediaExtractor.getSampleSize();
    }

    @Override
    public long getSampleTime() {
      return mediaExtractor.getSampleTime();
    }

    @Override
    public int getSampleTrackIndex() {
      return mediaExtractor.getSampleTrackIndex();
    }

    @Override
    public int getTrackCount() {
      return mediaExtractor.getTrackCount();
    }

    @Override
    public MediaFormat getTrackFormat(int trackIndex) {
      return mediaExtractor.getTrackFormat(trackIndex);
    }

    @Override
    public boolean hasCacheReachedEndOfStream() {
      return mediaExtractor.hasCacheReachedEndOfStream();
    }

    @Override
    public int readSampleData(ByteBuffer buffer, int offset) {
      return mediaExtractor.readSampleData(buffer, offset);
    }

    @Override
    public void release() {
      mediaExtractor.release();
    }

    @Override
    public void seekTo(long timeUs, int mode) {
      mediaExtractor.seekTo(timeUs, mode);
    }

    @Override
    public void selectTrack(int trackIndex) {
      mediaExtractor.selectTrack(trackIndex);
    }

    @Override
    @RequiresApi(24)
    public void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException {
      mediaExtractor.setDataSource(assetFileDescriptor);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor) throws IOException {
      mediaExtractor.setDataSource(fileDescriptor);
    }

    @Override
    @RequiresApi(23)
    public void setDataSource(MediaDataSource mediaDataSource) throws IOException {
      mediaExtractor.setDataSource(mediaDataSource);
    }

    @Override
    public void setDataSource(Context context, Uri uri, @Nullable Map<String, String> headers)
        throws IOException {
      mediaExtractor.setDataSource(context, uri, headers);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor, long offset, long length)
        throws IOException {
      mediaExtractor.setDataSource(fileDescriptor, offset, length);
    }

    @Override
    public void setDataSource(String path) throws IOException {
      mediaExtractor.setDataSource(path);
    }

    @Override
    public void setDataSource(String path, @Nullable Map<String, String> headers)
        throws IOException {
      mediaExtractor.setDataSource(path, headers);
    }

    @Override
    @RequiresApi(31)
    public void setLogSessionId(LogSessionId logSessionId) {
      mediaExtractor.setLogSessionId(logSessionId);
    }

    @Override
    public void unselectTrack(int trackIndex) {
      mediaExtractor.unselectTrack(trackIndex);
    }
  }

  private static class CompatMediaExtractorProxy implements MediaExtractorProxy {

    private final MediaExtractorCompat mediaExtractorCompat;

    public CompatMediaExtractorProxy(Context context) {
      this.mediaExtractorCompat = new MediaExtractorCompat(context);
    }

    @Override
    public boolean advance() {
      return mediaExtractorCompat.advance();
    }

    @Override
    public long getCachedDuration() {
      return mediaExtractorCompat.getCachedDuration();
    }

    @Override
    @RequiresApi(24)
    public Object getDrmInitData() {
      return mediaExtractorCompat.getDrmInitData();
    }

    @Override
    @RequiresApi(31)
    public LogSessionId getLogSessionId() {
      return mediaExtractorCompat.getLogSessionId();
    }

    @Override
    @RequiresApi(26)
    public PersistableBundle getMetrics() {
      return mediaExtractorCompat.getMetrics();
    }

    @Override
    public Map<UUID, byte[]> getPsshInfo() {
      return mediaExtractorCompat.getPsshInfo();
    }

    @Override
    public boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info) {
      return mediaExtractorCompat.getSampleCryptoInfo(info);
    }

    @Override
    public int getSampleFlags() {
      return mediaExtractorCompat.getSampleFlags();
    }

    @Override
    @RequiresApi(28)
    public long getSampleSize() {
      return mediaExtractorCompat.getSampleSize();
    }

    @Override
    public long getSampleTime() {
      return mediaExtractorCompat.getSampleTime();
    }

    @Override
    public int getSampleTrackIndex() {
      return mediaExtractorCompat.getSampleTrackIndex();
    }

    @Override
    public int getTrackCount() {
      return mediaExtractorCompat.getTrackCount();
    }

    @Override
    public MediaFormat getTrackFormat(int trackIndex) {
      return mediaExtractorCompat.getTrackFormat(trackIndex);
    }

    @Override
    public boolean hasCacheReachedEndOfStream() {
      return mediaExtractorCompat.hasCacheReachedEndOfStream();
    }

    @Override
    public int readSampleData(ByteBuffer buffer, int offset) {
      return mediaExtractorCompat.readSampleData(buffer, offset);
    }

    @Override
    public void release() {
      mediaExtractorCompat.release();
    }

    @Override
    public void seekTo(long timeUs, int mode) {
      mediaExtractorCompat.seekTo(timeUs, mode);
    }

    @Override
    public void selectTrack(int trackIndex) {
      mediaExtractorCompat.selectTrack(trackIndex);
    }

    @Override
    @RequiresApi(24)
    public void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException {
      mediaExtractorCompat.setDataSource(assetFileDescriptor);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor) throws IOException {
      mediaExtractorCompat.setDataSource(fileDescriptor);
    }

    @Override
    @RequiresApi(23)
    public void setDataSource(MediaDataSource mediaDataSource) throws IOException {
      mediaExtractorCompat.setDataSource(mediaDataSource);
    }

    @Override
    public void setDataSource(Context context, Uri uri, @Nullable Map<String, String> headers)
        throws IOException {
      mediaExtractorCompat.setDataSource(context, uri, headers);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor, long offset, long length)
        throws IOException {
      mediaExtractorCompat.setDataSource(fileDescriptor, offset, length);
    }

    @Override
    public void setDataSource(String path) throws IOException {
      mediaExtractorCompat.setDataSource(path);
    }

    @Override
    public void setDataSource(String path, @Nullable Map<String, String> headers)
        throws IOException {
      mediaExtractorCompat.setDataSource(path, headers);
    }

    @Override
    @RequiresApi(31)
    public void setLogSessionId(LogSessionId logSessionId) {
      mediaExtractorCompat.setLogSessionId(logSessionId);
    }

    @Override
    public void unselectTrack(int trackIndex) {
      mediaExtractorCompat.unselectTrack(trackIndex);
    }
  }

  @SuppressWarnings("unused") // TODO(b/392566318): Remove after adding tests for all methods.
  private interface MediaExtractorProxy {

    boolean advance();

    long getCachedDuration();

    @RequiresApi(24)
    Object getDrmInitData();

    @RequiresApi(31)
    LogSessionId getLogSessionId();

    @RequiresApi(26)
    PersistableBundle getMetrics();

    Map<UUID, byte[]> getPsshInfo();

    boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info);

    int getSampleFlags();

    @RequiresApi(28)
    long getSampleSize();

    long getSampleTime();

    int getSampleTrackIndex();

    int getTrackCount();

    MediaFormat getTrackFormat(int trackIndex);

    boolean hasCacheReachedEndOfStream();

    int readSampleData(ByteBuffer buffer, int offset);

    void release();

    void seekTo(long timeUs, @MediaExtractorCompat.SeekMode int mode);

    void selectTrack(int trackIndex);

    @RequiresApi(24)
    void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException;

    void setDataSource(FileDescriptor fileDescriptor) throws IOException;

    @RequiresApi(23)
    void setDataSource(MediaDataSource mediaDataSource) throws IOException;

    void setDataSource(Context context, Uri uri, @Nullable Map<String, String> headers)
        throws IOException;

    void setDataSource(FileDescriptor fileDescriptor, long offset, long length) throws IOException;

    void setDataSource(String path) throws IOException;

    void setDataSource(String path, @Nullable Map<String, String> headers) throws IOException;

    @RequiresApi(31)
    void setLogSessionId(LogSessionId logSessionId);

    void unselectTrack(int trackIndex);
  }
}
