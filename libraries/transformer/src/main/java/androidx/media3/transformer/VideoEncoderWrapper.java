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
package androidx.media3.transformer;

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.SRGB_BT709_FULL;
import static androidx.media3.common.ColorInfo.isTransferHdr;
import static androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR;
import static androidx.media3.transformer.SampleExporter.findSupportedMimeTypeForEncoderAndMuxer;
import static androidx.media3.transformer.TransformerUtil.getOutputMimeTypeAndHdrModeAfterFallback;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.media.MediaCodec;
import android.media.metrics.LogSessionId;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.SurfaceInfo;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.dataflow.qual.Pure;

/**
 * Wraps an {@linkplain Codec encoder} and provides its input {@link Surface}.
 *
 * <p>The encoder is created once the {@link Surface} is {@linkplain #getSurfaceInfo(int, int)
 * requested}. If it is {@linkplain #getSurfaceInfo(int, int) requested} again with different
 * dimensions, the same encoder is used and the provided dimensions stay fixed.
 */
/* package */ final class VideoEncoderWrapper {
  /** MIME type to use for output video if the input type is not a video. */
  private static final String DEFAULT_OUTPUT_MIME_TYPE = MimeTypes.VIDEO_H265;

  private final Codec.EncoderFactory encoderFactory;
  private final Format inputFormat;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final List<String> muxerSupportedMimeTypes;
  @Nullable private final TransformationRequest transformationRequest;
  private final FallbackListener fallbackListener;
  private final String requestedOutputMimeType;
  private final @Composition.HdrMode int hdrModeAfterFallback;
  @Nullable private final LogSessionId logSessionId;

  private @MonotonicNonNull SurfaceInfo encoderSurfaceInfo;

  private volatile @MonotonicNonNull Codec encoder;
  private volatile int outputRotationDegrees;
  private volatile boolean releaseEncoder;

  public VideoEncoderWrapper(
      Codec.EncoderFactory encoderFactory,
      Format inputFormat,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      List<String> muxerSupportedMimeTypes,
      @Nullable TransformationRequest transformationRequest,
      FallbackListener fallbackListener,
      @Nullable LogSessionId logSessionId) {
    checkArgument(inputFormat.colorInfo != null);
    this.encoderFactory = encoderFactory;
    this.inputFormat = inputFormat;
    this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
    this.muxerSupportedMimeTypes = muxerSupportedMimeTypes;
    this.transformationRequest = transformationRequest;
    this.fallbackListener = fallbackListener;
    this.logSessionId = logSessionId;
    Pair<String, Integer> outputMimeTypeAndHdrModeAfterFallback =
        getRequestedOutputMimeTypeAndHdrModeAfterFallback(inputFormat, transformationRequest);
    requestedOutputMimeType = outputMimeTypeAndHdrModeAfterFallback.first;
    hdrModeAfterFallback = outputMimeTypeAndHdrModeAfterFallback.second;
  }

  private static Pair<String, Integer> getRequestedOutputMimeTypeAndHdrModeAfterFallback(
      Format inputFormat, @Nullable TransformationRequest transformationRequest) {
    String inputSampleMimeType = checkNotNull(inputFormat.sampleMimeType);
    String requestedOutputMimeType;
    if (transformationRequest != null && transformationRequest.videoMimeType != null) {
      requestedOutputMimeType = transformationRequest.videoMimeType;
    } else if (MimeTypes.isImage(inputSampleMimeType)) {
      requestedOutputMimeType = DEFAULT_OUTPUT_MIME_TYPE;
    } else {
      requestedOutputMimeType = inputSampleMimeType;
    }

    return getOutputMimeTypeAndHdrModeAfterFallback(
        transformationRequest == null ? HDR_MODE_KEEP_HDR : transformationRequest.hdrMode,
        requestedOutputMimeType,
        inputFormat.colorInfo);
  }

  public @Composition.HdrMode int getHdrModeAfterFallback() {
    return hdrModeAfterFallback;
  }

  @Nullable
  public SurfaceInfo getSurfaceInfo(int requestedWidth, int requestedHeight)
      throws ExportException {
    if (releaseEncoder) {
      return null;
    }
    if (encoderSurfaceInfo != null) {
      return encoderSurfaceInfo;
    }

    // Encoders commonly support higher maximum widths than maximum heights. This may rotate the
    // frame before encoding, so the encoded frame's width >= height. In this case, the VideoGraph
    // rotates the decoded video frames counter-clockwise, and the muxer adds a clockwise rotation
    // to the metadata.
    if (requestedWidth < requestedHeight) {
      int temp = requestedWidth;
      requestedWidth = requestedHeight;
      requestedHeight = temp;
      outputRotationDegrees = 90;
    }

    // Try to match the inputFormat's rotation, but preserve landscape/portrait mode. This is a
    // best-effort attempt to preserve input video properties (helpful for trim optimization), but
    // is not guaranteed to work when effects are applied.
    if (inputFormat.rotationDegrees % 180 == outputRotationDegrees % 180) {
      outputRotationDegrees = inputFormat.rotationDegrees;
    }

    if (!allowedEncodingRotationDegrees.contains(outputRotationDegrees)) {
      int alternativeOutputRotationDegreesWithSameWidthAndHeight =
          (outputRotationDegrees + 180) % 360;
      if (allowedEncodingRotationDegrees.contains(
          alternativeOutputRotationDegreesWithSameWidthAndHeight)) {
        outputRotationDegrees = alternativeOutputRotationDegreesWithSameWidthAndHeight;
      } else {
        // No allowed rotation of the same orientation. Swap width and height, and use any allowed
        // orientation.
        int temp = requestedWidth;
        requestedWidth = requestedHeight;
        requestedHeight = temp;
        outputRotationDegrees = allowedEncodingRotationDegrees.get(0);
      }
    }

    // Rotation is handled by this class. The encoder must see a video with zero degrees rotation.
    Format requestedEncoderFormat =
        new Format.Builder()
            .setWidth(requestedWidth)
            .setHeight(requestedHeight)
            .setRotationDegrees(0)
            .setFrameRate(inputFormat.frameRate)
            .setSampleMimeType(requestedOutputMimeType)
            .setColorInfo(getSupportedInputColor())
            .setCodecs(inputFormat.codecs)
            .build();

    // TODO: b/324426022 - Move logic for supported mime types to DefaultEncoderFactory.
    encoder =
        encoderFactory.createForVideoEncoding(
            requestedEncoderFormat
                .buildUpon()
                .setSampleMimeType(
                    findSupportedMimeTypeForEncoderAndMuxer(
                        requestedEncoderFormat, muxerSupportedMimeTypes))
                .build(),
            logSessionId);

    Format actualEncoderFormat = encoder.getConfigurationFormat();

    if (transformationRequest != null) {
      fallbackListener.onTransformationRequestFinalized(
          createSupportedTransformationRequest(
              transformationRequest,
              /* hasOutputFormatRotation= */ outputRotationDegrees != 0,
              requestedEncoderFormat,
              actualEncoderFormat,
              hdrModeAfterFallback));
    }

    encoderSurfaceInfo =
        new SurfaceInfo(
            encoder.getInputSurface(),
            actualEncoderFormat.width,
            actualEncoderFormat.height,
            outputRotationDegrees,
            /* isEncoderInputSurface= */ true);

    if (releaseEncoder) {
      encoder.release();
    }
    return encoderSurfaceInfo;
  }

  /** Returns the {@link ColorInfo} expected from the input surface. */
  private ColorInfo getSupportedInputColor() {
    boolean isInputToneMapped =
        isTransferHdr(inputFormat.colorInfo) && hdrModeAfterFallback != HDR_MODE_KEEP_HDR;
    if (isInputToneMapped) {
      // When tone-mapping HDR to SDR is enabled, assume we get BT.709 to avoid having the encoder
      // populate default color info, which depends on the resolution.
      return SDR_BT709_LIMITED;
    }
    if (SRGB_BT709_FULL.equals(inputFormat.colorInfo)) {
      return SDR_BT709_LIMITED;
    }
    return checkNotNull(inputFormat.colorInfo);
  }

  /**
   * Creates a {@link TransformationRequest}, based on an original {@code TransformationRequest} and
   * parameters specifying alterations to it that indicate device support.
   *
   * @param transformationRequest The requested transformation.
   * @param hasOutputFormatRotation Whether the input video will be rotated to landscape during
   *     processing, with {@link Format#rotationDegrees} of 90 added to the output format.
   * @param requestedFormat The requested format.
   * @param supportedFormat A format supported by the device.
   * @param supportedHdrMode A {@link Composition.HdrMode} supported by the device.
   * @return The created instance.
   */
  @Pure
  private static TransformationRequest createSupportedTransformationRequest(
      TransformationRequest transformationRequest,
      boolean hasOutputFormatRotation,
      Format requestedFormat,
      Format supportedFormat,
      @Composition.HdrMode int supportedHdrMode) {
    // TODO: b/255953153 - Consider including bitrate in the revised fallback.

    TransformationRequest.Builder supportedRequestBuilder = transformationRequest.buildUpon();
    if (transformationRequest.hdrMode != supportedHdrMode) {
      supportedRequestBuilder.setHdrMode(supportedHdrMode);
    }

    if (!Objects.equals(requestedFormat.sampleMimeType, supportedFormat.sampleMimeType)) {
      supportedRequestBuilder.setVideoMimeType(supportedFormat.sampleMimeType);
    }

    if (hasOutputFormatRotation) {
      if (requestedFormat.width != supportedFormat.width) {
        supportedRequestBuilder.setResolution(/* outputHeight= */ supportedFormat.width);
      }
    } else if (requestedFormat.height != supportedFormat.height) {
      supportedRequestBuilder.setResolution(supportedFormat.height);
    }

    return supportedRequestBuilder.build();
  }

  public void signalEndOfInputStream() throws ExportException {
    if (encoder != null) {
      encoder.signalEndOfInputStream();
    }
  }

  @Nullable
  public Format getOutputFormat() throws ExportException {
    if (encoder == null) {
      return null;
    }
    @Nullable Format outputFormat = encoder.getOutputFormat();
    if (outputFormat != null && outputRotationDegrees != 0) {
      outputFormat = outputFormat.buildUpon().setRotationDegrees(outputRotationDegrees).build();
    }
    return outputFormat;
  }

  @Nullable
  public ByteBuffer getOutputBuffer() throws ExportException {
    return encoder != null ? encoder.getOutputBuffer() : null;
  }

  @Nullable
  public MediaCodec.BufferInfo getOutputBufferInfo() throws ExportException {
    return encoder != null ? encoder.getOutputBufferInfo() : null;
  }

  public void releaseOutputBuffer(boolean render) throws ExportException {
    if (encoder != null) {
      encoder.releaseOutputBuffer(render);
    }
  }

  public boolean isEnded() {
    return encoder != null && encoder.isEnded();
  }

  public void release() {
    if (encoder != null) {
      encoder.release();
    }
    releaseEncoder = true;
  }
}
