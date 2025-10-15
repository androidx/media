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
package androidx.media3.muxer;

import static android.media.MediaFormat.KEY_CHANNEL_COUNT;
import static android.media.MediaFormat.KEY_MIME;
import static android.media.MediaFormat.KEY_SAMPLE_RATE;
import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.util.MediaFormatUtil.createMediaFormatFromFormat;
import static androidx.media3.muxer.MediaMuxerCompat.OUTPUT_FORMAT_MP4;
import static androidx.media3.test.utils.AssetInfo.AMR_NB_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.AMR_WB_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.H263_3GP_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_8K24;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_AV1_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_DOLBY_VISION_HDR;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.media3.test.utils.AssetInfo.MPEG4_MP4_ASSET;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.system.Os;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.exoplayer.MediaExtractorCompat;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.AssetInfo;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import com.google.errorprone.annotations.Immutable;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/**
 * Contract tests for verifying consistent behavior across {@link MediaMuxer} implementations.
 *
 * <p>This tests both platform {@link MediaMuxer} and its compat implementation {@link
 * MediaMuxerCompat}.
 */
@RunWith(TestParameterInjector.class)
public final class MediaMuxerContractTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  // Contains some fake NAL units.
  private static final String FAKE_SAMPLE =
      "0000000167F4000A919B2BF3CB3640000003004000000C83C48965800000000168EBE3C448000001658884002BFFFEF5DBF32CAE4A43FF";

  private final Context context = ApplicationProvider.getApplicationContext();

  private enum MuxerFactoryEnum {
    FRAMEWORK(new FrameworkMediaMuxerProxy.Factory()),
    COMPAT(new CompatMediaMuxerProxy.Factory());

    private final MediaMuxerProxy.Factory value;

    MuxerFactoryEnum(MediaMuxerProxy.Factory factory) {
      this.value = factory;
    }
  }

  @TestParameter private MuxerFactoryEnum muxerFactoryEnum;

  private enum TestAssetEnum {
    H264_AAC_B_FRAMES(MP4_ASSET),
    AMR_NB(AMR_NB_3GP_ASSET),
    AMR_WB(AMR_WB_3GP_ASSET),
    H263(H263_3GP_ASSET),
    MPEG4(MPEG4_MP4_ASSET),
    H265(MP4_ASSET_8K24),
    AV1(MP4_ASSET_AV1_VIDEO),
    DOLBY_VISION(MP4_ASSET_DOLBY_VISION_HDR);

    private final AssetInfo value;

    TestAssetEnum(AssetInfo assetInfo) {
      this.value = assetInfo;
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // To ensure that all codecs are supported.
  public void createMp4File_withTestAsset_writesCorrectTracksAndSamples(
      @TestParameter TestAssetEnum testAssetEnum) throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    AssetInfo testAssetInfo = testAssetEnum.value;

    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      feedDataToMuxer(context, mediaMuxerProxy, testAssetInfo.uri);
    } finally {
      mediaMuxerProxy.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), outputFilePath);
    assertThat(fakeExtractorOutput.numberOfTracks).isEqualTo(testAssetInfo.trackCount);
    // A few assets has multiple video tracks.
    for (FakeTrackOutput videoTrackOutput :
        fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO)) {
      videoTrackOutput.assertSampleCount(testAssetInfo.videoFrameCount);
    }
    ImmutableList<FakeTrackOutput> audioTrackOutputs =
        fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO);
    if (!audioTrackOutputs.isEmpty()) {
      assertThat(audioTrackOutputs).hasSize(1);
      audioTrackOutputs.get(0).assertSampleCount(testAssetInfo.audioSampleCount);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 26) // MediaMuxer(FileDescriptor fd, int format) added in API 26.
  public void createMp4File_withFileDescriptorOutput_writesCorrectTracksAndSamples()
      throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    FileDescriptor outputFileDescriptor = new FileOutputStream(outputFilePath).getFD();

    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFileDescriptor, OUTPUT_FORMAT_MP4);
    Os.close(outputFileDescriptor);
    try {
      feedDataToMuxer(context, mediaMuxerProxy, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);
    } finally {
      mediaMuxerProxy.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    assertThat(fakeExtractorOutput.numberOfTracks)
        .isEqualTo(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.trackCount);
    Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
        .assertSampleCount(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFrameCount);
    Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_AUDIO))
        .assertSampleCount(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.audioSampleCount);
  }

  @Test
  public void addTrack_afterCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaFormat audioFormat = new MediaFormat();
    audioFormat.setString(KEY_MIME, AUDIO_AAC);
    audioFormat.setInteger(KEY_SAMPLE_RATE, 40000);
    audioFormat.setInteger(KEY_CHANNEL_COUNT, 2);
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(audioFormat);
      mediaMuxerProxy.start();

      assertThrows(
          IllegalStateException.class,
          () ->
              mediaMuxerProxy.addTrack(
                  MediaFormatUtil.createMediaFormatFromFormat(
                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat)));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void writeSample_beforeCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    ByteBuffer fakeSample = ByteBuffer.wrap(BaseEncoding.base16().decode(FAKE_SAMPLE));
    MediaCodec.BufferInfo fakeBufferInfo = new MediaCodec.BufferInfo();
    fakeBufferInfo.set(
        /* newOffset= */ 0,
        fakeSample.remaining(),
        /* newTimeUs= */ 0,
        MediaCodec.BUFFER_FLAG_KEY_FRAME);
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(
          createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));

      assertThrows(
          IllegalStateException.class,
          () -> mediaMuxerProxy.writeSampleData(/* trackIndex= */ 0, fakeSample, fakeBufferInfo));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void writeSample_withInvalidTrackIndex_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    ByteBuffer fakeSample = ByteBuffer.wrap(BaseEncoding.base16().decode(FAKE_SAMPLE));
    MediaCodec.BufferInfo fakeBufferInfo = new MediaCodec.BufferInfo();
    fakeBufferInfo.set(
        /* newOffset= */ 0,
        fakeSample.remaining(),
        /* newTimeUs= */ 0,
        MediaCodec.BUFFER_FLAG_KEY_FRAME);
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      int trackIndex =
          mediaMuxerProxy.addTrack(
              createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      assertThrows(
          IllegalArgumentException.class,
          () -> mediaMuxerProxy.writeSampleData(trackIndex + 1, fakeSample, fakeBufferInfo));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void writeSample_withNullByteBuffer_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaCodec.BufferInfo fakeBufferInfo = new MediaCodec.BufferInfo();
    fakeBufferInfo.set(
        /* newOffset= */ 0,
        /* newSize= */ 100,
        /* newTimeUs= */ 0,
        MediaCodec.BUFFER_FLAG_KEY_FRAME);
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      int trackIndex =
          mediaMuxerProxy.addTrack(
              createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      Exception exception =
          assertThrows(
              Exception.class,
              () ->
                  mediaMuxerProxy.writeSampleData(
                      trackIndex, /* byteBuffer= */ null, fakeBufferInfo));
      // MediaMuxer throws IllegalArgumentException and media3 muxer throws NullPointerException.
      assertWithMessage(exception.toString())
          .that(
              exception instanceof IllegalArgumentException
                  || exception instanceof NullPointerException)
          .isTrue();
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void writeSample_withNullBufferInfo_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    ByteBuffer fakeSample = ByteBuffer.wrap(BaseEncoding.base16().decode(FAKE_SAMPLE));
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      int trackIndex =
          mediaMuxerProxy.addTrack(
              createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      Exception exception =
          assertThrows(
              Exception.class,
              () ->
                  mediaMuxerProxy.writeSampleData(trackIndex, fakeSample, /* bufferInfo= */ null));
      // MediaMuxer throws IllegalArgumentException and media3 muxer throws NullPointerException.
      assertWithMessage(exception.toString())
          .that(
              exception instanceof IllegalArgumentException
                  || exception instanceof NullPointerException)
          .isTrue();
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void writeSample_withInvalidBufferSize_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    ByteBuffer fakeSample = ByteBuffer.wrap(BaseEncoding.base16().decode(FAKE_SAMPLE));
    MediaCodec.BufferInfo fakeBufferInfo = new MediaCodec.BufferInfo();
    fakeBufferInfo.set(
        /* newOffset= */ 0,
        fakeSample.remaining() + 1,
        /* newTimeUs= */ 0,
        MediaCodec.BUFFER_FLAG_KEY_FRAME);
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      int trackIndex =
          mediaMuxerProxy.addTrack(
              createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      assertThrows(
          IllegalArgumentException.class,
          () -> mediaMuxerProxy.writeSampleData(trackIndex, fakeSample, fakeBufferInfo));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void setLocation_afterCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(
          createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      assertThrows(
          IllegalStateException.class,
          () -> mediaMuxerProxy.setLocation(/* latitude= */ 33f, /* longitude= */ -120f));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void setOrientationHint_afterCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(
          createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      assertThrows(IllegalStateException.class, () -> mediaMuxerProxy.setOrientationHint(180));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void stop_beforeCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(
          createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));

      assertThrows(IllegalStateException.class, mediaMuxerProxy::stop);
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void start_afterCallingStart_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.addTrack(
          createMediaFormatFromFormat(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat));
      mediaMuxerProxy.start();

      assertThrows(IllegalStateException.class, mediaMuxerProxy::start);
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void restartMuxer_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      feedDataToMuxer(context, mediaMuxerProxy, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);

      assertThrows(IllegalStateException.class, mediaMuxerProxy::start);
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void setLocation_withInvalidLocation_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    float latitude = 100.0f;
    float longitude = -120f;
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);

    try {
      assertThrows(
          IllegalArgumentException.class, () -> mediaMuxerProxy.setLocation(latitude, longitude));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void createMp4File_withLocationData_writesCorrectLocation() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    float latitude = 33.0f;
    float longitude = 120f;

    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.setLocation(latitude, longitude);
      feedDataToMuxer(context, mediaMuxerProxy, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);
    } finally {
      mediaMuxerProxy.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    Format videoTrackFormat =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .lastFormat;
    Mp4LocationData actualLocationData =
        videoTrackFormat.metadata.getFirstEntryOfType(Mp4LocationData.class);
    assertThat(actualLocationData.latitude).isEqualTo(latitude);
    assertThat(actualLocationData.longitude).isEqualTo(longitude);
  }

  @Test
  // MediaMuxer had a bug that introduced a slight deviation in negative values before API 34. See
  // b/232327925.
  @SdkSuppress(minSdkVersion = 34)
  public void createMp4File_withNegativeLocationData_writesCorrectLocation() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    float latitude = 33.0f;
    float longitude = -120f;

    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.setLocation(latitude, longitude);
      feedDataToMuxer(context, mediaMuxerProxy, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);
    } finally {
      mediaMuxerProxy.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    Format videoTrackFormat =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .lastFormat;
    Mp4LocationData actualLocationData =
        videoTrackFormat.metadata.getFirstEntryOfType(Mp4LocationData.class);
    assertThat(actualLocationData.latitude).isEqualTo(latitude);
    assertThat(actualLocationData.longitude).isEqualTo(longitude);
  }

  @Test
  public void setOrientation_withInvalidOrientation_throws() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    int orientation = 45;
    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);

    try {
      assertThrows(
          IllegalArgumentException.class, () -> mediaMuxerProxy.setOrientationHint(orientation));
    } finally {
      try {
        mediaMuxerProxy.release();
      } catch (RuntimeException exception) {
        // MediaMuxer does not release gracefully when an error was thrown. See b/80338884.
      }
    }
  }

  @Test
  public void createMp4File_withOrientationData_writesCorrectOrientation() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    int orientation = 180;

    MediaMuxerProxy mediaMuxerProxy =
        muxerFactoryEnum.value.create(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      mediaMuxerProxy.setOrientationHint(orientation);
      feedDataToMuxer(context, mediaMuxerProxy, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri);
    } finally {
      mediaMuxerProxy.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    Format videoTrackFormat =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .lastFormat;
    assertThat(videoTrackFormat.rotationDegrees).isEqualTo(orientation);
  }

  private static void feedDataToMuxer(Context context, MediaMuxerProxy muxer, String inputFilePath)
      throws IOException {
    MediaExtractorCompat extractor = new MediaExtractorCompat(context);
    Uri fileUri = Uri.parse(inputFilePath);
    extractor.setDataSource(fileUri, /* offset= */ 0);
    List<Integer> trackIndexes = new ArrayList<>();
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      extractor.selectTrack(i);
      trackIndexes.add(muxer.addTrack(format));
    }
    muxer.start();
    do {
      int sampleSize = (int) extractor.getSampleSize();
      ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(sampleSize);
      extractor.readSampleData(sampleBuffer, /* offset= */ 0);
      sampleBuffer.rewind();
      int sampleTrackIndex = extractor.getSampleTrackIndex();
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      bufferInfo.set(
          /* newOffset= */ 0, sampleSize, extractor.getSampleTime(), extractor.getSampleFlags());
      muxer.writeSampleData(trackIndexes.get(sampleTrackIndex), sampleBuffer, bufferInfo);
    } while (extractor.advance());
    muxer.stop();
    extractor.release();
  }

  /** Proxy for {@link android.media.MediaMuxer} or {@link MediaMuxerCompat}. */
  private interface MediaMuxerProxy {
    /** Factory for {@link MediaMuxerProxy} instances. */
    @Immutable
    interface Factory {
      @RequiresApi(26) // MediaMuxer(FileDescriptor fd, int format) added in API 26.
      MediaMuxerProxy create(
          FileDescriptor fileDescriptor, @MediaMuxerCompat.OutputFormat int outputFormat)
          throws IOException;

      MediaMuxerProxy create(String filePath, @MediaMuxerCompat.OutputFormat int outputFormat)
          throws IOException;
    }

    void start();

    int addTrack(MediaFormat format);

    void writeSampleData(int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo);

    void setLocation(float latitude, float longitude);

    void setOrientationHint(int degrees);

    void stop();

    void release();
  }

  @SuppressLint({"WrongConstant"})
  private static class FrameworkMediaMuxerProxy implements MediaMuxerProxy {
    @Immutable
    private static class Factory implements MediaMuxerProxy.Factory {
      @Override
      @RequiresApi(26) // MediaMuxer(FileDescriptor fd, int format) added in API 26.
      public MediaMuxerProxy create(
          FileDescriptor fileDescriptor, @MediaMuxerCompat.OutputFormat int outputFormat)
          throws IOException {
        return new FrameworkMediaMuxerProxy(fileDescriptor, outputFormat);
      }

      @Override
      public MediaMuxerProxy create(
          String filePath, @MediaMuxerCompat.OutputFormat int outputFormat) throws IOException {
        return new FrameworkMediaMuxerProxy(filePath, outputFormat);
      }
    }

    private final MediaMuxer mediaMuxer;

    @RequiresApi(26) // MediaMuxer(FileDescriptor fd, int format) added in API 26.
    public FrameworkMediaMuxerProxy(
        FileDescriptor fileDescriptor, @MediaMuxerCompat.OutputFormat int outputFormat)
        throws IOException {
      mediaMuxer = new MediaMuxer(fileDescriptor, outputFormat);
    }

    public FrameworkMediaMuxerProxy(
        String filePath, @MediaMuxerCompat.OutputFormat int outputFormat) throws IOException {
      mediaMuxer = new MediaMuxer(filePath, outputFormat);
    }

    @Override
    public void start() {
      mediaMuxer.start();
    }

    @Override
    public int addTrack(MediaFormat format) {
      return mediaMuxer.addTrack(format);
    }

    @Override
    public void writeSampleData(
        int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
      mediaMuxer.writeSampleData(trackIndex, byteBuffer, bufferInfo);
    }

    @Override
    public void setLocation(float latitude, float longitude) {
      mediaMuxer.setLocation(latitude, longitude);
    }

    @Override
    public void setOrientationHint(int degrees) {
      mediaMuxer.setOrientationHint(degrees);
    }

    @Override
    public void stop() {
      mediaMuxer.stop();
    }

    @Override
    public void release() {
      mediaMuxer.release();
    }
  }

  private static class CompatMediaMuxerProxy implements MediaMuxerProxy {
    @Immutable
    private static class Factory implements MediaMuxerProxy.Factory {
      @Override
      public MediaMuxerProxy create(
          FileDescriptor fileDescriptor, @MediaMuxerCompat.OutputFormat int outputFormat)
          throws IOException {
        return new CompatMediaMuxerProxy(fileDescriptor, outputFormat);
      }

      @Override
      public MediaMuxerProxy create(
          String filePath, @MediaMuxerCompat.OutputFormat int outputFormat) throws IOException {
        return new CompatMediaMuxerProxy(filePath, outputFormat);
      }
    }

    private final MediaMuxerCompat mediaMuxerCompat;

    public CompatMediaMuxerProxy(
        FileDescriptor fileDescriptor, @MediaMuxerCompat.OutputFormat int outputFormat)
        throws IOException {
      mediaMuxerCompat = new MediaMuxerCompat(fileDescriptor, outputFormat);
    }

    public CompatMediaMuxerProxy(String filePath, @MediaMuxerCompat.OutputFormat int outputFormat)
        throws IOException {
      mediaMuxerCompat = new MediaMuxerCompat(filePath, outputFormat);
    }

    @Override
    public void start() {
      mediaMuxerCompat.start();
    }

    @Override
    public int addTrack(MediaFormat format) {
      return mediaMuxerCompat.addTrack(format);
    }

    @Override
    public void writeSampleData(
        int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
      mediaMuxerCompat.writeSampleData(trackIndex, byteBuffer, bufferInfo);
    }

    @Override
    public void setLocation(float latitude, float longitude) {
      mediaMuxerCompat.setLocation(latitude, longitude);
    }

    @Override
    public void setOrientationHint(int degrees) {
      mediaMuxerCompat.setOrientationHint(degrees);
    }

    @Override
    public void stop() {
      mediaMuxerCompat.stop();
    }

    @Override
    public void release() {
      mediaMuxerCompat.release();
    }
  }
}
