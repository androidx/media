/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.decoder.av1;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.MediaCrypto;
import android.media.MediaFormat;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.ExtensionCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link Dav1dCodecAdapter}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class Dav1dCodecAdapterTest {

  private static final Format AV1_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();

  private Dav1dCodecInfo codecInfo;

  @Before
  public void setUp() {
    codecInfo = new Dav1dCodecInfo();
  }

  @After
  public void tearDown() {
    Dav1dLibrary.setAvailableForTesting(null);
  }

  @Test
  public void setNumInputBuffers_withValidCount_returnsSameFactory(
      @TestParameter({"1", "4"}) int numInputBuffers) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    Dav1dCodecAdapter.Factory result = factory.setNumInputBuffers(numInputBuffers);

    assertThat(result).isSameInstanceAs(factory);
  }

  @Test
  public void setNumInputBuffers_withNonPositiveCount_throwsIllegalArgumentException(
      @TestParameter({"0", "-1"}) int numInputBuffers) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    assertThrows(IllegalArgumentException.class, () -> factory.setNumInputBuffers(numInputBuffers));
  }

  @Test
  public void setNumOutputBuffers_withNonPositiveCount_throwsIllegalArgumentException(
      @TestParameter({"0", "-1"}) int numOutputBuffers) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    assertThrows(
        IllegalArgumentException.class, () -> factory.setNumOutputBuffers(numOutputBuffers));
  }

  @Test
  public void setInitialInputBufferSize_withNonPositiveSize_throwsIllegalArgumentException(
      @TestParameter({"0", "-1"}) int initialInputBufferSize) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    assertThrows(
        IllegalArgumentException.class,
        () -> factory.setInitialInputBufferSize(initialInputBufferSize));
  }

  @Test
  public void setThreads_withValidCount_returnsSameFactory(
      @TestParameter({
            "0", // THREAD_COUNT_DECODER_DEFAULT
            "-1", // THREAD_COUNT_PERFORMANCE_CORES
            "-2", // THREAD_COUNT_EXPERIMENTAL
            "1", // Minimum explicit thread count
            "256", // Maximum explicit thread count
          })
          int threads) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    Dav1dCodecAdapter.Factory result = factory.setThreads(threads);

    assertThat(result).isSameInstanceAs(factory);
  }

  @Test
  public void setThreads_withInvalidCount_throwsIllegalArgumentException(
      @TestParameter({"-3", "257"}) int threads) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    assertThrows(IllegalArgumentException.class, () -> factory.setThreads(threads));
  }

  @Test
  @SuppressWarnings({"ApiWithNumericTimeUnit", "GoodTime-ApiWithNumericTimeUnit"}) // Frame count.
  public void setMaxFrameDelay_withValidDelay_returnsSameFactory(
      @TestParameter({"0", "1", "256"}) int maxFrameDelay) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    Dav1dCodecAdapter.Factory result = factory.setMaxFrameDelay(maxFrameDelay);

    assertThat(result).isSameInstanceAs(factory);
  }

  @Test
  @SuppressWarnings({"ApiWithNumericTimeUnit", "GoodTime-ApiWithNumericTimeUnit"}) // Frame count.
  public void setMaxFrameDelay_withInvalidDelay_throwsIllegalArgumentException(
      @TestParameter({"-1", "257"}) int maxFrameDelay) {
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    assertThrows(IllegalArgumentException.class, () -> factory.setMaxFrameDelay(maxFrameDelay));
  }

  @Test
  public void getInputBufferSize_withPositiveFormatMaxInputSize_returnsFormatMaxInputSize(
      @TestParameter({
            "200000", // Smaller than DEFAULT_INPUT_BUFFER_SIZE
            "1500000", // Larger than DEFAULT_INPUT_BUFFER_SIZE
          })
          int maxInputSize) {
    Format format = AV1_FORMAT.buildUpon().setMaxInputSize(maxInputSize).build();
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    int inputBufferSize =
        factory.getInputBufferSize(createConfiguration(format, new MediaFormat()));

    assertThat(inputBufferSize).isEqualTo(maxInputSize);
  }

  @Test
  public void getInputBufferSize_withoutPositiveFormatMaxInputSize_returnsDefaultSize(
      @TestParameter({
            "-1", // Format.NO_VALUE
            "0", "-2",
          })
          int maxInputSize) {
    Format format = AV1_FORMAT.buildUpon().setMaxInputSize(maxInputSize).build();
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    int inputBufferSize =
        factory.getInputBufferSize(createConfiguration(format, new MediaFormat()));

    assertThat(inputBufferSize).isEqualTo(Dav1dCodecAdapter.DEFAULT_INPUT_BUFFER_SIZE);
  }

  @Test
  public void
      getInputBufferSize_withCustomInitialInputBufferSizeAndUnsetFormatMaxInputSize_returnsCustomSize() {
    Dav1dCodecAdapter.Factory factory =
        new Dav1dCodecAdapter.Factory().setInitialInputBufferSize(128_000);

    int inputBufferSize =
        factory.getInputBufferSize(createConfiguration(AV1_FORMAT, new MediaFormat()));

    assertThat(inputBufferSize).isEqualTo(128_000);
  }

  @Test
  public void
      getInputBufferSize_withCustomInitialInputBufferSizeAndPositiveFormatMaxInputSize_returnsFormatMaxInputSize() {
    Format format = AV1_FORMAT.buildUpon().setMaxInputSize(64_000).build();
    Dav1dCodecAdapter.Factory factory =
        new Dav1dCodecAdapter.Factory().setInitialInputBufferSize(128_000);

    int inputBufferSize =
        factory.getInputBufferSize(createConfiguration(format, new MediaFormat()));

    assertThat(inputBufferSize).isEqualTo(64_000);
  }

  @Test
  public void
      getInputBufferSize_withMediaFormatMaxInputSizeAndNoFormatMaxInputSize_returnsDefaultSize() {
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 6_220_800);
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    int inputBufferSize = factory.getInputBufferSize(createConfiguration(AV1_FORMAT, mediaFormat));

    assertThat(inputBufferSize).isEqualTo(Dav1dCodecAdapter.DEFAULT_INPUT_BUFFER_SIZE);
  }

  @Test
  public void createAdapter_withNonDav1dCodecInfo_throwsIOException(
      @TestParameter boolean isExtensionCodecInfo) {
    MediaCodecInfo nonDav1dCodecInfo =
        isExtensionCodecInfo
            ? ExtensionCodecInfo.newInstance("c2.other.av1.decoder", MimeTypes.VIDEO_AV1)
            : MediaCodecInfo.newInstance(
                /* name= */ "c2.android.av1.decoder",
                /* mimeType= */ MimeTypes.VIDEO_AV1,
                /* codecMimeType= */ MimeTypes.VIDEO_AV1,
                /* capabilities= */ null,
                /* hardwareAccelerated= */ true,
                /* softwareOnly= */ false,
                /* vendor= */ true);
    MediaCodecAdapter.Configuration configuration =
        MediaCodecAdapter.Configuration.createForVideoDecoding(
            nonDav1dCodecInfo,
            new MediaFormat(),
            AV1_FORMAT,
            /* surface= */ null,
            /* crypto= */ null);
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Dav1dCodecAdapter requires Dav1dCodecInfo");
  }

  @Test
  public void createAdapter_withNonAv1Format_throwsIOException() {
    Format h264Format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(h264Format, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Unsupported sample MIME type");
  }

  @Test
  public void createAdapter_withHevcDolbyVisionFormat_throwsIOException() {
    Format hevcDolbyVisionFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.05.01")
            .build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(hevcDolbyVisionFormat, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Unsupported sample MIME type");
  }

  @Test
  public void createAdapter_withAv1DolbyVisionFormat_throwsIOException() {
    Format av1DolbyVisionFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(av1DolbyVisionFormat, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Unsupported sample MIME type");
  }

  @Test
  public void createAdapter_withNullSampleMimeType_throwsIOException() {
    Format nullMimeFormat = new Format.Builder().build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(nullMimeFormat, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Unsupported sample MIME type");
  }

  @Test
  public void createAdapter_withUnsupportedAv1Codecs_throwsIOException(
      @TestParameter({
            "av01.0.31M.12", // 12-bit
            "av01.1.31M.08", // High Profile (4:4:4)
            "av01.2.04M.08.0.100", // Professional Profile 4:2:2
            "avc1.42E01E", // Mismatched H.264 codec string on video/av01
            "dav1.10.09", // Dolby Vision codec string on video/av01
          })
          String codecs) {
    Format unsupportedAv1Format = AV1_FORMAT.buildUpon().setCodecs(codecs).build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(unsupportedAv1Format, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Unsupported AV1 format");
  }

  @Test
  public void createAdapter_whenBufferAllocationThrowsOutOfMemoryError_throwsIOException() {
    Dav1dLibrary.setAvailableForTesting(true);
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(AV1_FORMAT, new MediaFormat());
    Dav1dCodecAdapter.Factory factory =
        new Dav1dCodecAdapter.Factory().setNumInputBuffers(Integer.MAX_VALUE);

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(OutOfMemoryError.class);
  }

  @Test
  public void createAdapter_withMediaCrypto_throwsIOException() throws Exception {
    MediaCrypto mediaCrypto = new MediaCrypto(C.WIDEVINE_UUID, new byte[0]);
    try {
      MediaCodecAdapter.Configuration configuration =
          MediaCodecAdapter.Configuration.createForVideoDecoding(
              codecInfo, new MediaFormat(), AV1_FORMAT, /* surface= */ null, mediaCrypto);
      Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

      IOException thrown =
          assertThrows(IOException.class, () -> factory.createAdapter(configuration));

      assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
      assertThat(thrown).hasCauseThat().hasMessageThat().contains("does not support secure decode");
    } finally {
      mediaCrypto.release();
    }
  }

  @Test
  public void createAdapter_withEncryptedFormat_throwsIOException() {
    Format encryptedFormat = AV1_FORMAT.buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(encryptedFormat, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("does not support secure decode");
  }

  @Test
  public void createAdapter_withDrmInitData_throwsIOException() {
    DrmInitData drmInitData =
        new DrmInitData(
            new DrmInitData.SchemeData(
                C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, new byte[] {1, 2, 3, 4}));
    Format drmFormat = AV1_FORMAT.buildUpon().setDrmInitData(drmInitData).build();
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(drmFormat, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("does not support secure decode");
  }

  @Test
  public void createAdapter_whenLibraryNotAvailable_throwsIOException() {
    Dav1dLibrary.setAvailableForTesting(false);
    MediaCodecAdapter.Configuration configuration =
        createConfiguration(AV1_FORMAT, new MediaFormat());
    Dav1dCodecAdapter.Factory factory = new Dav1dCodecAdapter.Factory();

    IOException thrown =
        assertThrows(IOException.class, () -> factory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("Failed to load decoder");
  }

  @Test
  public void createAdapter_withDav1dCodecInfoWhenLibraryNotAvailable_throwsIOException() {
    Dav1dLibrary.setAvailableForTesting(false);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();
    MediaCodecAdapter.Configuration configuration = createConfiguration(format, new MediaFormat());
    DefaultMediaCodecAdapterFactory defaultFactory =
        new DefaultMediaCodecAdapterFactory(ApplicationProvider.getApplicationContext());

    IOException thrown =
        assertThrows(IOException.class, () -> defaultFactory.createAdapter(configuration));

    assertThat(thrown).hasCauseThat().isInstanceOf(Dav1dDecoderException.class);
  }

  private MediaCodecAdapter.Configuration createConfiguration(
      Format format, MediaFormat mediaFormat) {
    return MediaCodecAdapter.Configuration.createForVideoDecoding(
        codecInfo, mediaFormat, format, /* surface= */ null, /* crypto= */ null);
  }
}
