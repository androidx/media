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
package androidx.media3.inspector;

import static androidx.media3.common.C.CENC_TYPE_cenc;
import static androidx.media3.common.C.WIDEVINE_UUID;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.min;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.metrics.LogSessionId;
import android.media.metrics.MediaMetricsManager;
import android.media.metrics.PlaybackSession;
import android.net.Uri;
import android.os.PersistableBundle;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.mp4.PsshAtomUtil;
import androidx.media3.test.utils.AssetContentProvider;
import androidx.media3.test.utils.ImmutableByteArray;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SdkSuppress;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
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

  private static final String AUTHORITY = "androidx.media3.inspector.test.AssetContentProvider";

  /** The PSSH data payload for the "media/drm/sample_fragmented_widevine.mp4" test asset. */
  private static final ImmutableByteArray WIDEVINE_PSSH_DATA_FOR_SAMPLE_FRAGMENTED_MP4 =
      ImmutableByteArray.ofUnsigned(
          0x08, 0x01, 0x12, 0x01, 0x35, 0x1A, 0x0D, 0x77, 0x69, 0x64, 0x65, 0x76, 0x69, 0x6E, 0x65,
          0x5F, 0x74, 0x65, 0x73, 0x74, 0x22, 0x0A, 0x32, 0x30, 0x31, 0x35, 0x5F, 0x74, 0x65, 0x61,
          0x72, 0x73, 0x2A, 0x02, 0x53, 0x44);

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

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();
  @Parameter public Function<Context, MediaExtractorProxy> mediaExtractorProxyFactory;

  private MediaExtractorProxy mediaExtractorProxy;
  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    mediaExtractorProxy = mediaExtractorProxyFactory.apply(context);
  }

  @After
  public void tearDown() {
    mediaExtractorProxy.release();
  }

  @Test
  @SdkSuppress(minSdkVersion = 24)
  public void setDataSource_withAssetFileDescriptor_returnsCorrectTrackCount() throws IOException {
    AssetFileDescriptor afd = context.getAssets().openFd("media/mp4/sample.mp4");

    mediaExtractorProxy.setDataSource(afd);

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withFileDescriptor_returnsCorrectTrackCount() throws IOException {
    File file = tempFolder.newFile();
    Files.write(TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4"), file);

    try (FileInputStream inputStream = new FileInputStream(file)) {
      mediaExtractorProxy.setDataSource(inputStream.getFD());
    }

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withFileDescriptorOffsetAndLength_returnsCorrectTrackCount()
      throws IOException {
    AssetFileDescriptor afd = context.getAssets().openFd("media/mp4/sample.mp4");

    mediaExtractorProxy.setDataSource(
        afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withMediaDataSource_returnsCorrectTrackCount() throws IOException {
    byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
    MediaDataSource mediaDataSource =
        new MediaDataSource() {
          @Override
          public int readAt(long position, byte[] buffer, int offset, int size) {
            if (size == 0) {
              return 0;
            }

            if (position > getSize()) {
              return C.RESULT_END_OF_INPUT;
            }

            size = min(size, (int) (getSize() - position));

            System.arraycopy(fileData, (int) position, buffer, offset, size);
            return size;
          }

          @Override
          public long getSize() {
            return fileData.length;
          }

          @Override
          public void close() {}
        };

    mediaExtractorProxy.setDataSource(mediaDataSource);

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withFilePath_returnsCorrectTrackCount() throws IOException {
    File file = tempFolder.newFile();
    Files.write(TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4"), file);

    mediaExtractorProxy.setDataSource(file.getAbsolutePath());

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withHttpUrlAsString_returnsCorrectTrackCount() throws IOException {
    try (MockWebServer mockWebServer = new MockWebServer()) {
      byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
      mockWebServer.enqueue(new MockResponse().setBody(new Buffer().write(fileData)));

      mediaExtractorProxy.setDataSource(mockWebServer.url("/test-path").toString());

      assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
    }
  }

  @Test
  public void setDataSource_withHttpUrlAsStringAndHeaders_returnsCorrectTrackCountAndHeaders()
      throws Exception {
    try (MockWebServer mockWebServer = new MockWebServer()) {
      byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
      mockWebServer.enqueue(new MockResponse().setBody(new Buffer().write(fileData)));
      Map<String, String> headers = new HashMap<>();
      headers.put("k", "v");

      mediaExtractorProxy.setDataSource(mockWebServer.url("/test-path").toString(), headers);

      assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
      assertThat(mockWebServer.takeRequest().getHeaders().get("k")).isEqualTo("v");
    }
  }

  @Test
  public void setDataSource_withContentUri_returnsCorrectTrackCount() throws IOException {
    Uri contentUri =
        AssetContentProvider.buildUri(AUTHORITY, "media/mp4/sample.mp4", /* pipeMode= */ false);

    mediaExtractorProxy.setDataSource(context, contentUri, /* headers= */ null);

    assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void setDataSource_withHttpUrlAsContentUriAndHeaders_returnsCorrectTrackCountAndHeaders()
      throws Exception {
    try (MockWebServer mockWebServer = new MockWebServer()) {
      byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
      mockWebServer.enqueue(new MockResponse().setBody(new Buffer().write(fileData)));
      Map<String, String> headers = new HashMap<>();
      headers.put("k", "v");

      mediaExtractorProxy.setDataSource(
          context, Uri.parse(mockWebServer.url("/test-path").toString()), headers);

      assertThat(mediaExtractorProxy.getTrackCount()).isEqualTo(2);
      assertThat(mockWebServer.takeRequest().getHeaders().get("k")).isEqualTo("v");
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void getLogSessionId_withUnsetSessionId_returnsNone() {
    assertThat(mediaExtractorProxy.getLogSessionId()).isEqualTo(LogSessionId.LOG_SESSION_ID_NONE);
  }

  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void getLogSessionId_withSetSessionId_returnsSetSessionId() {
    MediaMetricsManager mediaMetricsManager = context.getSystemService(MediaMetricsManager.class);
    PlaybackSession playbackSession = mediaMetricsManager.createPlaybackSession();
    LogSessionId logSessionId = playbackSession.getSessionId();

    mediaExtractorProxy.setLogSessionId(logSessionId);

    assertThat(mediaExtractorProxy.getLogSessionId()).isEqualTo(logSessionId);
  }

  @Test
  @SdkSuppress(minSdkVersion = 30)
  public void getDrmInitData_forPsshV0EncryptedFile_returnsCorrectWidevineSchemeData()
      throws IOException {
    AssetFileDescriptor afd =
        context.getAssets().openFd("media/drm/sample_fragmented_widevine.mp4");
    mediaExtractorProxy.setDataSource(afd);

    TestDrmInitData testDrmInitData = mediaExtractorProxy.getDrmInitData();

    assertThat(testDrmInitData.schemeDataCount).isEqualTo(1);
    assertThat(testDrmInitData.schemeType).isEqualTo(CENC_TYPE_cenc);
    assertThat(testDrmInitData.uuid).isEqualTo(WIDEVINE_UUID);
    assertThat(testDrmInitData.data)
        .isEqualTo(WIDEVINE_PSSH_DATA_FOR_SAMPLE_FRAGMENTED_MP4.toArray());
  }

  @Test
  public void getPsshInfo_forPsshV0EncryptedFile_returnsCorrectWidevinePsshData()
      throws IOException {
    AssetFileDescriptor afd =
        context.getAssets().openFd("media/drm/sample_fragmented_widevine.mp4");
    mediaExtractorProxy.setDataSource(
        afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

    Map<UUID, byte[]> psshInfo = mediaExtractorProxy.getPsshInfo();

    assertThat(psshInfo).containsKey(WIDEVINE_UUID);
    assertThat(psshInfo.get(WIDEVINE_UUID))
        .isEqualTo(WIDEVINE_PSSH_DATA_FOR_SAMPLE_FRAGMENTED_MP4.toArray());
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void getMetrics_withMp4DataSource_returnsExpectedMetricsBundle() throws IOException {
    AssetFileDescriptor afd = context.getAssets().openFd("media/mp4/sample.mp4");
    mediaExtractorProxy.setDataSource(
        afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

    PersistableBundle bundle = mediaExtractorProxy.getMetrics();

    // MetricsConstants.FORMAT is the name of the extractor used in metrics, which is expected to be
    // different for the framework and compat implementations, hence not asserted.
    assertThat(bundle.getString(MediaExtractor.MetricsConstants.MIME_TYPE))
        .isEqualTo(MimeTypes.VIDEO_MP4);
    assertThat(bundle.getInt(MediaExtractor.MetricsConstants.TRACKS)).isEqualTo(2);
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(30)
    public TestDrmInitData getDrmInitData() {
      android.media.DrmInitData frameworkDrmInitData = mediaExtractor.getDrmInitData();
      if (frameworkDrmInitData == null || frameworkDrmInitData.getSchemeInitDataCount() == 0) {
        return null;
      }
      // Fully-qualified name used for clarity, avoids ambiguity with imported Media3 DrmInitData.
      @SuppressWarnings("UnnecessarilyFullyQualified")
      android.media.DrmInitData.SchemeInitData firstSchemeData =
          frameworkDrmInitData.getSchemeInitDataAt(0);
      return new TestDrmInitData(
          frameworkDrmInitData.getSchemeInitDataCount(),
          firstSchemeData.mimeType,
          firstSchemeData.uuid,
          firstSchemeData.data);
    }

    @Override
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(31)
    public LogSessionId getLogSessionId() {
      return mediaExtractor.getLogSessionId();
    }

    @Override
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(24)
    public void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException {
      mediaExtractor.setDataSource(assetFileDescriptor);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor) throws IOException {
      mediaExtractor.setDataSource(fileDescriptor);
    }

    @Override
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(30)
    public TestDrmInitData getDrmInitData() {
      DrmInitData drmInitData = mediaExtractorCompat.getDrmInitData();
      if (drmInitData == null || drmInitData.schemeDataCount == 0) {
        return null;
      }

      DrmInitData.SchemeData firstScheme = drmInitData.get(0);
      PsshAtomUtil.PsshAtom psshAtom = PsshAtomUtil.parsePsshAtom(firstScheme.data);
      byte[] data = psshAtom != null ? psshAtom.schemeData : firstScheme.data;
      return new TestDrmInitData(
          drmInitData.schemeDataCount, drmInitData.schemeType, firstScheme.uuid, data);
    }

    @Override
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(31)
    public LogSessionId getLogSessionId() {
      return mediaExtractorCompat.getLogSessionId();
    }

    @Override
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(24)
    public void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException {
      mediaExtractorCompat.setDataSource(assetFileDescriptor);
    }

    @Override
    public void setDataSource(FileDescriptor fileDescriptor) throws IOException {
      mediaExtractorCompat.setDataSource(fileDescriptor);
    }

    @Override
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
    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(30)
    TestDrmInitData getDrmInitData();

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(31)
    LogSessionId getLogSessionId();

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(26)
    PersistableBundle getMetrics();

    Map<UUID, byte[]> getPsshInfo();

    boolean getSampleCryptoInfo(MediaCodec.CryptoInfo info);

    int getSampleFlags();

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
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

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(24)
    void setDataSource(AssetFileDescriptor assetFileDescriptor) throws IOException;

    void setDataSource(FileDescriptor fileDescriptor) throws IOException;

    void setDataSource(MediaDataSource mediaDataSource) throws IOException;

    void setDataSource(Context context, Uri uri, @Nullable Map<String, String> headers)
        throws IOException;

    void setDataSource(FileDescriptor fileDescriptor, long offset, long length) throws IOException;

    void setDataSource(String path) throws IOException;

    void setDataSource(String path, @Nullable Map<String, String> headers) throws IOException;

    @SuppressWarnings("UseSdkSuppress") // https://issuetracker.google.com/382253664
    @RequiresApi(31)
    void setLogSessionId(LogSessionId logSessionId);

    void unselectTrack(int trackIndex);
  }

  /**
   * A test-only representation of DRM initialization data.
   *
   * <p>This class is necessary because the platform {@link android.media.DrmInitData} and Media3
   * {@link DrmInitData} are not easily convertible between each other, so this class provides a
   * common object for comparison in tests.
   *
   * <p>The platform {@code DrmInitData} cannot be constructed from the Media3 object due to
   * visibility restrictions, and the Media3 object is too rich to be synthesized from the limited
   * information provided by the platform object.
   */
  private static final class TestDrmInitData {
    private final int schemeDataCount;
    private final String schemeType;
    private final UUID uuid;
    private final byte[] data;

    private TestDrmInitData(int schemeDataCount, String schemeType, UUID uuid, byte[] data) {
      this.schemeDataCount = schemeDataCount;
      this.schemeType = schemeType;
      this.uuid = uuid;
      this.data = data;
    }
  }
}
