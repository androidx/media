/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_AVC;
import static androidx.media3.transformer.EditedMediaItemSequence.withVideoFrom;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.PixelFormat;
import android.media.metrics.LogSessionId;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeHardwareBufferJniWrapper;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.transformer.PacketConsumerVideoSampleExporter.FrameWriterEncoderFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PacketConsumerVideoSampleExporter}. */
@RunWith(AndroidJUnit4.class)
public final class PacketConsumerVideoSampleExporterTest {

  private static final Format LANDSCAPE_FORMAT =
      new Format.Builder()
          .setWidth(1920)
          .setHeight(1080)
          .setFrameRate(30)
          .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
          .build();

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(), /* encoders= */ ImmutableList.of(CODEC_INFO_AVC));

  private FakeEncoderFactory fakeEncoderFactory;
  private PacketConsumerVideoSampleExporter exporter;

  @Before
  public void setUp() {
    fakeEncoderFactory = new FakeEncoderFactory();
    Composition composition =
        new Composition.Builder(
                withVideoFrom(
                    ImmutableList.of(new EditedMediaItem.Builder(MediaItem.EMPTY).build())))
            .build();
    TransformationRequest transformationRequest = new TransformationRequest.Builder().build();
    Looper playbackLooper = checkNotNull(Looper.myLooper());
    HandlerWrapper handlerWrapper =
        Clock.DEFAULT.createHandler(playbackLooper, /* callback= */ null);
    FallbackListener fallbackListener =
        new FallbackListener(
            composition,
            new ListenerSet<>(playbackLooper, Clock.DEFAULT, (listener, flags) -> {}),
            handlerWrapper,
            transformationRequest);
    fallbackListener.setTrackCount(1);
    exporter =
        new PacketConsumerVideoSampleExporter(
            ApplicationProvider.getApplicationContext(),
            composition,
            LANDSCAPE_FORMAT.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
            transformationRequest,
            new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true),
            new FakeHardwareBufferJniWrapper(),
            fakeEncoderFactory,
            new MuxerWrapper(
                /* outputPath= */ "unused",
                new InAppMp4Muxer.Factory(),
                new NoOpMuxerListenerImpl(),
                MuxerWrapper.MUXER_MODE_DEFAULT,
                /* dropSamplesBeforeFirstVideoSample= */ false,
                /* appendVideoFormat= */ null),
            /* errorConsumer= */ e -> {},
            fallbackListener,
            /* allowedEncodingRotationDegrees= */ ImmutableList.of(0, 90, 180, 270),
            /* logSessionId= */ null,
            playbackLooper,
            handlerWrapper);
  }

  @After
  public void tearDown() {
    exporter.release();
  }

  @Test
  public void isVideoFormatSupported_withDifferentFormatMimeType_ignoresFormatMimeType() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));

    boolean isSupported =
        factory.isVideoFormatSupported(
            LANDSCAPE_FORMAT.buildUpon().setSampleMimeType(MimeTypes.IMAGE_RAW).build());

    assertThat(isSupported).isTrue();
    assertThat(fakeEncoderFactory.checkedFormats.get(0).sampleMimeType)
        .isEqualTo(MimeTypes.VIDEO_H264);
  }

  @Test
  public void
      isVideoFormatSupported_whenRequestedMimeTypeUnsupportedByDevice_fallsBackToSupportedMimeType() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H265,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(
                MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264));

    boolean isSupported = factory.isVideoFormatSupported(LANDSCAPE_FORMAT);

    assertThat(isSupported).isTrue();
    assertThat(fakeEncoderFactory.checkedFormats.get(0).sampleMimeType)
        .isEqualTo(MimeTypes.VIDEO_H264);
  }

  @Test
  public void isVideoFormatSupported_whenNoMimeTypeSupportedByBothEncoderAndMuxer_returnsFalse() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_VP9));

    boolean isSupported = factory.isVideoFormatSupported(LANDSCAPE_FORMAT);

    assertThat(isSupported).isFalse();
    assertThat(fakeEncoderFactory.checkedFormats).isEmpty();
  }

  @Test
  public void isVideoFormatSupported_hdrColorWithoutHdrEncoder_returnsFalse() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));
    Format hdrFormat =
        LANDSCAPE_FORMAT
            .buildUpon()
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .build())
            .build();

    boolean isSupported = factory.isVideoFormatSupported(hdrFormat);

    assertThat(isSupported).isFalse();
    assertThat(fakeEncoderFactory.checkedFormats).isEmpty();
  }

  @Test
  public void isVideoFormatSupported_noColorInfo_checksMimeTypeWithEncoder() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));

    boolean isSupported =
        factory.isVideoFormatSupported(LANDSCAPE_FORMAT.buildUpon().setColorInfo(null).build());

    assertThat(isSupported).isTrue();
    assertThat(fakeEncoderFactory.checkedFormats.get(0).sampleMimeType)
        .isEqualTo(MimeTypes.VIDEO_H264);
  }

  @Test
  public void isVideoFormatSupported_rotatedFormat_checksUnrotatedSize() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));

    boolean isSupported =
        factory.isVideoFormatSupported(
            LANDSCAPE_FORMAT.buildUpon().setRotationDegrees(270).build());

    assertThat(isSupported).isTrue();
    Format checkedFormat = fakeEncoderFactory.checkedFormats.get(0);
    assertThat(checkedFormat.rotationDegrees).isEqualTo(0);
    assertThat(checkedFormat.width).isEqualTo(1920);
    assertThat(checkedFormat.height).isEqualTo(1080);
  }

  @Test
  public void isVideoFormatSupported_withInputFormat_passesOnlyEncoderFields() {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));
    Format formatWithInputFields =
        LANDSCAPE_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .setAverageBitrate(10_000_000)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setRotationDegrees(90)
            .build();

    boolean isSupported = factory.isVideoFormatSupported(formatWithInputFields);

    assertThat(isSupported).isTrue();
    assertThat(fakeEncoderFactory.checkedFormats)
        .containsExactly(
            new Format.Builder()
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30)
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                .setPixelFormat(PixelFormat.RGBA_8888)
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .build());
  }

  @Test
  public void isVideoFormatSupported_encoderFactoryRejectsFormat_returnsFalse() {
    fakeEncoderFactory.setIsSupported(false);
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));

    boolean isSupported = factory.isVideoFormatSupported(LANDSCAPE_FORMAT);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void createForVideoEncoding_afterSupportCheck_createsEncoderWithSameFormatAsSupportCheck()
      throws Exception {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));
    Format format =
        LANDSCAPE_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.IMAGE_RAW)
            .setRotationDegrees(270)
            .build();

    boolean unusedIsSupported = factory.isVideoFormatSupported(format);
    Codec unusedEncoder = factory.createForVideoEncoding(format, /* logSessionId= */ null);

    assertThat(fakeEncoderFactory.createdFormats)
        .containsExactlyElementsIn(fakeEncoderFactory.checkedFormats);
  }

  @Test
  public void createForVideoEncoding_rotatedFormat_setsExporterMuxerInputFormatRotation()
      throws Exception {
    FrameWriterEncoderFactory factory =
        createFactory(
            /* requestedSampleMimeType= */ MimeTypes.VIDEO_H264,
            /* muxerSupportedSampleMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264));
    Format rotatedFormat = LANDSCAPE_FORMAT.buildUpon().setRotationDegrees(270).build();

    Codec unusedEncoder = factory.createForVideoEncoding(rotatedFormat, /* logSessionId= */ null);

    assertThat(exporter.getMuxerInputFormat())
        .isEqualTo(
            LANDSCAPE_FORMAT
                .buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setRotationDegrees(270)
                .build());
  }

  private FrameWriterEncoderFactory createFactory(
      String requestedSampleMimeType, List<String> muxerSupportedSampleMimeTypes) {
    return exporter
    .new FrameWriterEncoderFactory(
        fakeEncoderFactory, requestedSampleMimeType, muxerSupportedSampleMimeTypes);
  }

  private static final class NoOpMuxerListenerImpl implements MuxerWrapper.Listener {

    @Override
    public void onTrackEnded(
        @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {}

    @Override
    public void onSampleWrittenOrDropped() {}

    @Override
    public void onEnded(long approximateDurationMs) {}

    @Override
    public void onFileSizeBytesAvailable(long fileSizeBytes) {}

    @Override
    public void onError(ExportException exportException) {}
  }

  private static final class FakeEncoderFactory implements Codec.EncoderFactory {

    private final List<Format> checkedFormats = new ArrayList<>();
    private final List<Format> createdFormats = new ArrayList<>();
    private boolean isSupported = true;

    private void setIsSupported(boolean isSupported) {
      this.isSupported = isSupported;
    }

    @Override
    public boolean isVideoFormatSupported(Format format) {
      checkedFormats.add(format);
      return isSupported;
    }

    @Override
    public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      createdFormats.add(format);
      Codec fakeEncoder = mock(Codec.class);
      when(fakeEncoder.getOutputFormat()).thenReturn(format);
      return fakeEncoder;
    }
  }
}
