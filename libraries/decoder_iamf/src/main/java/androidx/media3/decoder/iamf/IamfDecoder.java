/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.decoder.iamf;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.List;

/** IAMF decoder. */
@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
public final class IamfDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, IamfDecoderException> {

  /**
   * Represents the different sound systems supported by IAMF.
   *
   * <p>NOTE: Values from iamf_tools_api_types.h but are translated by iamf_jni.cc.
   */
  @Documented
  @Retention(SOURCE)
  @Target(ElementType.TYPE_USE)
  @IntDef({
    OUTPUT_LAYOUT_UNSET,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0,
    OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0,
    OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0
  })
  public @interface OutputLayout {}

  /** Value to be used to not specify an output layout. */
  public static final int OUTPUT_LAYOUT_UNSET = -1;

  /** ITU-R B.S. 2051-3 sound system A (0+2+0), commonly known as stereo. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0 = 0;

  /** ITU-R B.S. 2051-3 sound system B (0+5+0), commonly known as 5.1. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_B_0_5_0 = 1;

  /** ITU-R B.S. 2051-3 sound system C (2+5+0), commonly known as 5.1.2. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_C_2_5_0 = 2;

  /** ITU-R B.S. 2051-3 sound system D (4+5+0), commonly known as 5.1.4. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_D_4_5_0 = 3;

  /** ITU-R B.S. 2051-3 sound system E (4+5+1). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_E_4_5_1 = 4;

  /** ITU-R B.S. 2051-3 sound system F (3+7+0). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_F_3_7_0 = 5;

  /** ITU-R B.S. 2051-3 sound system G (4+9+0). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_G_4_9_0 = 6;

  /** ITU-R B.S. 2051-3 sound system H (9+10+3). */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_H_9_10_3 = 7;

  /** ITU-R B.S. 2051-3 sound system I (0+7+0), commonly known as 7.1. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_I_0_7_0 = 8;

  /** ITU-R B.S. 2051-3 sound system J (4+7+0), commonly known as 7.1.4. */
  public static final int OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_J_4_7_0 = 9;

  /** IAMF extension 7.1.2. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_7_0 = 10;

  /** IAMF extension 3.1.2. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_2_3_0 = 11;

  /** Mono. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_0_1_0 = 12;

  /** IAMF Extension 9.1.6. */
  public static final int OUTPUT_LAYOUT_IAMF_SOUND_SYSTEM_EXTENSION_6_9_0 = 13;

  /**
   * Represents the different possible output sample types supported by the iamf_tools decoder.
   *
   * <p>NOTE: Values from iamf_tools_api_types.h.
   */
  @Documented
  @Retention(SOURCE)
  @Target(ElementType.TYPE_USE)
  @IntDef({
    OUTPUT_SAMPLE_TYPE_UNSET,
    OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN,
    OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN
  })
  public @interface OutputSampleType {}

  /** Value used to not specify an output sample type. */
  public static final int OUTPUT_SAMPLE_TYPE_UNSET = -1;

  /** Interleaved little endian signed 16-bit. */
  public static final int OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN = 1;

  /** Interleaved little endian signed 32-bit. */
  public static final int OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN = 2;

  /**
   * Represents the different possible output channel orderings supported by the iamf_tools decoder.
   *
   * <p>NOTE: Values from iamf_tools_api_types.h.
   */
  @Documented
  @Retention(SOURCE)
  @Target(ElementType.TYPE_USE)
  @IntDef({
    CHANNEL_ORDERING_UNSET,
    CHANNEL_ORDERING_IAMF_ORDERING,
    CHANNEL_ORDERING_ANDROID_ORDERING
  })
  public @interface ChannelOrdering {}

  /** Value to be used to not specify a ChannelOrdering. */
  public static final int CHANNEL_ORDERING_UNSET = -1;

  /** Ordering as specified in ITU/IAMF spec. This is the default behaviour of iamf_tools. */
  public static final int CHANNEL_ORDERING_IAMF_ORDERING = 0;

  /** Ordering to match that found in Android's AudioFormat.java. */
  public static final int CHANNEL_ORDERING_ANDROID_ORDERING = 1;

  /**
   * Used to indicate no requested Mix Presentation ID when creating a decoder.
   *
   * <p>When this value is used, the decoder will select a Mix Presentation ID based on the default
   * logic, including considering the requested OutputLayout, if provided.
   */
  public static final long REQUESTED_MIX_PRESENTATION_ID_UNSET = -1;

  /**
   * Status codes returned by the JNI wrapper around the decoder.
   *
   * <p>These values are also defined by the JNI and must be kept in sync.
   */
  @Documented
  @Retention(SOURCE)
  @Target(ElementType.TYPE_USE)
  @IntDef({STATUS_ERROR, STATUS_OK})
  private @interface Status {}

  private static final int STATUS_ERROR = -1;
  private static final int STATUS_OK = 0;

  /** Pointer to the native decoder, must be manually closed. */
  private long nativeDecoderPointer;

  /**
   * Creates an IAMF decoder from Descriptor OBUs.
   *
   * @param initializationData Descriptor OBUs data for the decoder.
   * @param requestedOutputLayout The desired {@link OutputLayout} to request. Can be set to
   *     OUTPUT_LAYOUT_UNSET to avoid specifying. The actual layout used may not be the same as
   *     requested, so getSelectedOutputLayout() can be used to get the actual layout used.
   * @param requestedMixPresentationId The desired Mix Presentation ID. Can be set to
   *     REQUESTED_MIX_PRESENTATION_ID_UNSET to avoid specifying. The actual Mix Presentation ID
   *     used may not be the same as requested, so getSelectedMixPresentationId() can be used to get
   *     the actual Mix Presentation ID used.
   * @param outputSampleType The desired {@link OutputSampleType}.
   * @param channelOrdering The desired {@link ChannelOrdering}.
   * @throws IamfDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public IamfDecoder(
      List<byte[]> initializationData,
      @OutputLayout int requestedOutputLayout,
      long requestedMixPresentationId,
      @OutputSampleType int outputSampleType,
      @ChannelOrdering int channelOrdering)
      throws IamfDecoderException {
    super(new DecoderInputBuffer[1], new SimpleDecoderOutputBuffer[1]);
    if (!IamfLibrary.isAvailable()) {
      throw new IamfDecoderException("Failed to load decoder native libraries.");
    }
    if (initializationData.size() != 1) {
      throw new IamfDecoderException("Initialization data must contain a single element.");
    }
    nativeDecoderPointer = iamfOpen();
    if (nativeDecoderPointer == 0) {
      throw new IamfDecoderException("Failed to open decoder");
    }
    @Status
    int status =
        iamfCreateFromDescriptors(
            initializationData.get(0),
            requestedOutputLayout,
            requestedMixPresentationId,
            outputSampleType,
            channelOrdering,
            nativeDecoderPointer);
    if (status != STATUS_OK) {
      throw new IamfDecoderException("Failed to configure decoder with returned status: " + status);
    }
  }

  @Override
  public String getName() {
    return "IamfDecoder";
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected IamfDecoderException createUnexpectedDecodeException(Throwable error) {
    return new IamfDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected IamfDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      @Status int resetResult = iamfReset(nativeDecoderPointer);
      if (resetResult != STATUS_OK) {
        return new IamfDecoderException("Failed to reset decoder.");
      }
    }
    ByteBuffer inputData = checkNotNull(inputBuffer.data);
    if (!inputData.isDirect()) {
      // Only direct ByteBuffers are able to be read by GetDirectBufferAddress in the JNI.
      return new IamfDecoderException("Input buffer's data is not direct.");
    }
    @Status int decodeResult = iamfDecode(inputData, inputData.limit(), nativeDecoderPointer);
    if (decodeResult != STATUS_OK) {
      return new IamfDecoderException("Failed to decode.");
    }
    if (isTemporalUnitAvailable()) {
      int bufferSize;
      try {
        bufferSize = getOutputBufferSizeBytes();
      } catch (IamfDecoderException e) {
        return e;
      }
      outputBuffer.init(inputBuffer.timeUs, bufferSize);
      ByteBuffer outputData = checkNotNull(outputBuffer.data);
      int bytesWritten =
          iamfGetOutputTemporalUnit(outputData, outputData.limit(), nativeDecoderPointer);
      if (bytesWritten == STATUS_ERROR) {
        return new IamfDecoderException("GetOutputTemporalUnit failed.");
      }
      outputData.position(0);
      outputData.limit(bytesWritten);
    }
    return null;
  }

  @Override
  public void release() {
    super.release();
    iamfClose(nativeDecoderPointer);
  }

  /** Returns whether an output buffer (temporal unit) is available to be retrieved. */
  public boolean isTemporalUnitAvailable() {
    return iamfIsTemporalUnitAvailable(nativeDecoderPointer);
  }

  /** Returns whether Descriptor OBU processing is complete. */
  public boolean isDescriptorProcessingComplete() {
    return iamfIsDescriptorProcessingComplete(nativeDecoderPointer);
  }

  /**
   * Returns the number of output channels.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting number of output
   *     channels. Generally happens if Descriptor OBU processing has not completed.
   */
  public int getNumberOfOutputChannels() throws IamfDecoderException {
    int result = iamfGetNumberOfOutputChannels(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get number of output channels.");
    }
    return result;
  }

  /**
   * Returns the output layout that has been selected by the decoder.
   *
   * <p>Even if a requested layout was specified, the selected layout may be different than the
   * requested layout, for example, if the requested layout was not available.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting selected output layout.
   *     Generally happens if Descriptor OBU processing has not completed.
   */
  public int getSelectedOutputLayout() throws IamfDecoderException {
    int result = iamfGetSelectedOutputLayout(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get selected output layout.");
    }
    return result;
  }

  /**
   * Returns the Mix Presentation ID that has been selected by the decoder.
   *
   * <p>Even if a requested Mix Presentation ID was specified, the selected Mix Presentation ID may
   * be different than the requested Mix Presentation ID, for example, if the requested mix
   * presentation ID was not available.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting selected mix
   *     presentation ID. Generally happens if Descriptor OBU processing has not completed.
   */
  public long getSelectedMixPresentationId() throws IamfDecoderException {
    long result = iamfGetSelectedMixPresentationId(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get selected Mix Presentation ID.");
    }
    return result;
  }

  /**
   * Returns the output sample type.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting output sample type.
   *     Generally happens if Descriptor OBU processing has not completed.
   */
  public @OutputSampleType int getOutputSampleType() throws IamfDecoderException {
    int result = iamfGetOutputSampleType(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get output sample type.");
    }
    return result;
  }

  /**
   * Returns the sample rate.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting sample rate. Generally
   *     happens if Descriptor OBU processing has not completed.
   */
  public int getSampleRate() throws IamfDecoderException {
    int result = iamfGetSampleRate(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get sample rate.");
    }
    return result;
  }

  /**
   * Returns the frame size.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting frame size. Generally
   *     happens if Descriptor OBU processing has not completed.
   */
  public int getFrameSize() throws IamfDecoderException {
    int result = iamfGetFrameSize(nativeDecoderPointer);
    if (result < 0) {
      throw new IamfDecoderException("Failed to get frame size.");
    }
    return result;
  }

  /**
   * Returns the output buffer size in bytes.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when getting output buffer size.
   *     Generally happens if Descriptor OBU processing has not completed.
   */
  public int getOutputBufferSizeBytes() throws IamfDecoderException {
    int channelCount = iamfGetNumberOfOutputChannels(nativeDecoderPointer);
    if (channelCount < 0) {
      throw new IamfDecoderException("Failed to get number of output channels.");
    }
    int frameSize = iamfGetFrameSize(nativeDecoderPointer);
    if (frameSize < 0) {
      throw new IamfDecoderException("Failed to get frame size.");
    }
    int outputSampleType = iamfGetOutputSampleType(nativeDecoderPointer);
    if (outputSampleType < 0) {
      throw new IamfDecoderException("Failed to get output sample type.");
    }
    int bytesPerSample = getBytesPerSample(outputSampleType);
    return channelCount * frameSize * bytesPerSample;
  }

  /**
   * Resets the decoder to a clean state ready to decode new data.
   *
   * <p>This function can only be used if the decoder was created with Descriptor OBUs, i.e. with
   * initialization data.
   *
   * <p>A clean state refers to a state in which descriptors OBUs have been parsed, but no other
   * data has been parsed.
   *
   * <p>Useful for seeking applications.
   *
   * @throws IamfDecoderException if an exception occurs when resetting decoder. This will happen if
   *     the underlying decoder was created without Descriptor OBUs (i.e. initialization data),
   *     among other reasons.
   */
  public void reset() throws IamfDecoderException {
    @Status int result = iamfReset(nativeDecoderPointer);
    if (result != STATUS_OK) {
      throw new IamfDecoderException("Failed to reset decoder.");
    }
  }

  /**
   * Resets the decoder to a clean state with a new output layout and/or Mix Presentation ID.
   *
   * <p>This function can only be used if the decoder was created with Descriptor OBUs, i.e. with
   * initialization data.
   *
   * <p>A clean state refers to a state in which descriptors OBUs have been parsed, but no other
   * data has been parsed.
   *
   * <p>Useful for dynamic playback layout changes, e.g. connect or disconnect headphones.
   *
   * @param requestedOutputLayout The desired output layout to request. Can be set to
   *     OUTPUT_LAYOUT_UNSET to avoid specifying. The actual layout used may not be the same as
   *     requested, so {@link #getSelectedOutputLayout} can be used to get the actual layout used.
   * @param requestedMixPresentationId The desired Mix Presentation ID. Can be set to
   *     REQUESTED_MIX_PRESENTATION_ID_UNSET to avoid specifying. The actual Mix Presentation ID
   *     used may not be the same as requested, so {@link #getSelectedMixPresentationId} can be used
   *     to get the actual Mix Presentation ID used.
   * @throws IamfDecoderException if an exception occurs when resetting decoder. This will happen if
   *     the underlying decoder was created without Descriptor OBUs (i.e. initialization data),
   *     among other reasons.
   */
  public void resetWithNewMix(
      @OutputLayout int requestedOutputLayout, long requestedMixPresentationId)
      throws IamfDecoderException {
    @Status
    int result =
        iamfResetWithNewMix(
            requestedOutputLayout, requestedMixPresentationId, nativeDecoderPointer);
    if (result != STATUS_OK) {
      throw new IamfDecoderException("Failed to reset decoder with new mix.");
    }
  }

  /**
   * Signals end of decoding to the decoder.
   *
   * @throws IamfDecoderException Thrown if an exception occurs when signaling end of decoding.
   */
  public void signalEndOfDecoding() throws IamfDecoderException {
    @Status int result = iamfSignalEndOfDecoding(nativeDecoderPointer);
    if (result != STATUS_OK) {
      throw new IamfDecoderException("Failed to signal end of decoding.");
    }
  }

  private static int getBytesPerSample(@OutputSampleType int outputSampleType) {
    switch (outputSampleType) {
      case OUTPUT_SAMPLE_TYPE_INT16_LITTLE_ENDIAN:
        return 2;
      case OUTPUT_SAMPLE_TYPE_INT32_LITTLE_ENDIAN:
        return 4;
      default:
        throw new IllegalArgumentException("Unsupported output sample type: " + outputSampleType);
    }
  }

  // ===== Native Method Declarations =====

  /** Necessary first step to get the decoderRawPointer. */
  private native long iamfOpen();

  /** Must be called to free the decoder when no longer needed. */
  private native void iamfClose(long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfCreate(
      int outputLayout, int outputSampleType, int channelOrdering, long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfCreateFromDescriptors(
      byte[] initializationData,
      int outputLayout,
      long requestedMixPresentationId,
      int outputSampleType,
      int channelOrdering,
      long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfDecode(
      ByteBuffer inputBuffer, int inputSize, long decoderRawPointer);

  /** Returns bytes written on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetOutputTemporalUnit(
      ByteBuffer outputBuffer, int outputSize, long decoderRawPointer);

  /** Returns whether an output buffer (temporal unit) is available to be retrieved. */
  private native boolean iamfIsTemporalUnitAvailable(long decoderRawPointer);

  /** Returns whether Descriptor OBU processing is complete. */
  private native boolean iamfIsDescriptorProcessingComplete(long decoderRawPointer);

  /** Returns channel count on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetNumberOfOutputChannels(long decoderRawPointer);

  /** Returns output layout on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetSelectedOutputLayout(long decoderRawPointer);

  /** Returns Mix Presentation ID on success, or {@link #STATUS_ERROR} on failure. */
  private native long iamfGetSelectedMixPresentationId(long decoderRawPointer);

  /** Returns sample type on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetOutputSampleType(long decoderRawPointer);

  /** Returns sample rate on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetSampleRate(long decoderRawPointer);

  /** Returns frame size on success, or {@link #STATUS_ERROR} on failure. */
  private native int iamfGetFrameSize(long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfReset(long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfResetWithNewMix(
      int requestedOutputLayout, long requestedMixPresentationId, long decoderRawPointer);

  /** Returns {@link #STATUS_OK} on success, or {@link #STATUS_ERROR} on failure. */
  private native @Status int iamfSignalEndOfDecoding(long decoderRawPointer);
}
