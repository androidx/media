/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.metadata;

import static androidx.media3.common.util.Util.castNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult;
import androidx.media3.extractor.metadata.MetadataDecoder;
import androidx.media3.extractor.metadata.MetadataInputBuffer;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.jspecify.annotations.Nullable;

/**
 * A renderer for metadata.
 *
 * <p>The renderer can be configured to render metadata as soon as they are available using {@link
 * #MetadataRenderer(MetadataOutput, Looper, MetadataDecoderFactory, boolean)}.
 */
@UnstableApi
public final class MetadataRenderer extends BaseRenderer implements Callback {

  private static final String TAG = "MetadataRenderer";
  private static final int MSG_INVOKE_RENDERER = 1;

  /**
   * Maximum duration to read ahead from the current playback position in microseconds. Limiting the
   * read ahead ensures the samples are not consumed too early to allow player changes affecting
   * these samples (e.g. duration changes).
   */
  private static final long MAX_READ_AHEAD_DURATION_US = C.MICROS_PER_SECOND;

  private final MetadataDecoderFactory decoderFactory;
  private final MetadataOutput output;
  private final @Nullable Handler outputHandler;
  private final MetadataInputBuffer buffer;
  private final boolean outputMetadataEarly;

  private @Nullable MetadataDecoder decoder;
  private boolean inputStreamEnded;
  private boolean outputStreamEnded;
  private long subsampleOffsetUs;
  private @Nullable Metadata pendingMetadata;
  private long outputStreamOffsetUs;
  private float playbackSpeed;
  private long nextSourceReadPositionUs;
  private boolean sourceReadPending;

  /**
   * Creates an instance that uses {@link MetadataDecoderFactory#DEFAULT} to create {@link
   * MetadataDecoder} instances.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   */
  public MetadataRenderer(MetadataOutput output, @Nullable Looper outputLooper) {
    this(output, outputLooper, MetadataDecoderFactory.DEFAULT);
  }

  /**
   * Creates an instance.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   * @param decoderFactory A factory from which to obtain {@link MetadataDecoder} instances.
   */
  public MetadataRenderer(
      MetadataOutput output, @Nullable Looper outputLooper, MetadataDecoderFactory decoderFactory) {
    this(output, outputLooper, decoderFactory, /* outputMetadataEarly= */ false);
  }

  /**
   * Creates an instance.
   *
   * @param output The output.
   * @param outputLooper The looper associated with the thread on which the output should be called.
   *     If the output makes use of standard Android UI components, then this should normally be the
   *     looper associated with the application's main thread, which can be obtained using {@link
   *     android.app.Activity#getMainLooper()}. Null may be passed if the output should be called
   *     directly on the player's internal rendering thread.
   * @param decoderFactory A factory from which to obtain {@link MetadataDecoder} instances.
   * @param outputMetadataEarly Whether the renderer outputs metadata early. When {@code true},
   *     {@link #render} will output metadata as soon as they are available to the renderer,
   *     otherwise {@link #render} will output metadata in sync with the rendering position.
   */
  public MetadataRenderer(
      MetadataOutput output,
      @Nullable Looper outputLooper,
      MetadataDecoderFactory decoderFactory,
      boolean outputMetadataEarly) {
    super(C.TRACK_TYPE_METADATA);
    this.output = checkNotNull(output);
    this.outputHandler =
        outputLooper == null ? null : Util.createHandler(outputLooper, /* callback= */ this);
    this.decoderFactory = checkNotNull(decoderFactory);
    this.outputMetadataEarly = outputMetadataEarly;
    buffer = new MetadataInputBuffer();
    outputStreamOffsetUs = C.TIME_UNSET;
    playbackSpeed = 1f;
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = true;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public @Capabilities int supportsFormat(Format format) {
    if (decoderFactory.supportsFormat(format)) {
      return RendererCapabilities.create(
          format.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM);
    } else {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
  }

  @Override
  public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed) {
    this.playbackSpeed = currentPlaybackSpeed;
  }

  @Override
  public long getDurationToProgressUs(long positionUs, long elapsedRealtimeUs) {
    if (!Flags.isEnabled(Flags.FLAG_METADATA_RENDERER_DURATION_TO_PROGRESS)
        || getState() == STATE_DISABLED) {
      return super.getDurationToProgressUs(positionUs, elapsedRealtimeUs);
    }

    boolean isStarted = getState() == STATE_STARTED;
    if (outputStreamEnded) {
      // If the metadata stream ended early in a period with a known end position, wake up at the
      // end of the stream so period transition or playback completion is not delayed.
      if (isStarted) {
        long streamEndPositionUs = getStreamEndPositionUs();
        if (streamEndPositionUs != C.TIME_UNSET) {
          long streamEndRendererPositionUs = getStreamOffsetUs() + streamEndPositionUs;
          if (positionUs < streamEndRendererPositionUs) {
            return Util.calculateDurationToProgressUs(
                positionUs, streamEndRendererPositionUs, playbackSpeed);
          }
        }
      }
      return DEFAULT_IDLE_DURATION_TO_PROGRESS_US;
    }

    // If decoded metadata is pending output, wake when its presentation time is reached.
    if (pendingMetadata != null) {
      long targetPositionUs = pendingMetadata.presentationTimeUs + outputStreamOffsetUs;
      if (outputMetadataEarly || positionUs >= targetPositionUs) {
        return 0;
      }
      return isStarted
          ? Util.calculateDurationToProgressUs(positionUs, targetPositionUs, playbackSpeed)
          : DEFAULT_IDLE_DURATION_TO_PROGRESS_US;
    }

    if (nextSourceReadPositionUs == C.TIME_UNSET) {
      // SampleStream cannot be read yet (e.g. before first render, still loading, or waiting for
      // end-of-stream to become final), so poll at the default active rate while started or when a
      // source read is pending.
      return isStarted || sourceReadPending
          ? DEFAULT_DURATION_TO_PROGRESS_US
          : DEFAULT_IDLE_DURATION_TO_PROGRESS_US;
    }

    // Wake when the next sample or end-of-stream signal enters the read-ahead window.
    if (nextSourceReadPositionUs == C.TIME_END_OF_SOURCE) {
      return DEFAULT_IDLE_DURATION_TO_PROGRESS_US;
    }
    if (positionUs >= nextSourceReadPositionUs) {
      return 0;
    }
    return isStarted
        ? Util.calculateDurationToProgressUs(positionUs, nextSourceReadPositionUs, playbackSpeed)
        : DEFAULT_IDLE_DURATION_TO_PROGRESS_US;
  }

  @Override
  protected void onStreamChanged(
      Format[] formats,
      long startPositionUs,
      long offsetUs,
      MediaSource.MediaPeriodId mediaPeriodId) {
    decoder = decoderFactory.createDecoder(formats[0]);
    if (pendingMetadata != null) {
      pendingMetadata =
          pendingMetadata.copyWithPresentationTimeUs(
              pendingMetadata.presentationTimeUs + outputStreamOffsetUs - offsetUs);
    }
    outputStreamOffsetUs = offsetUs;
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = true;
  }

  @Override
  protected void onPositionReset(
      long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
    pendingMetadata = null;
    inputStreamEnded = false;
    outputStreamEnded = false;
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = true;
  }

  @Override
  protected void onTimelineChanged(Timeline timeline) {
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = true;
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = false;
    boolean working = true;
    while (working) {
      readMetadata(positionUs);
      working = outputMetadata(positionUs);
    }
  }

  /**
   * Iterates through {@code metadata.entries} and checks each one to see if contains wrapped
   * metadata. If it does, then we recursively decode the wrapped metadata. If it doesn't (recursion
   * base-case), we add the {@link Metadata.Entry} to {@code decodedEntries} (output parameter).
   */
  private void decodeWrappedMetadata(Metadata metadata, List<Metadata.Entry> decodedEntries) {
    for (int i = 0; i < metadata.length(); i++) {
      @Nullable Format wrappedMetadataFormat = metadata.get(i).getWrappedMetadataFormat();
      if (wrappedMetadataFormat != null && decoderFactory.supportsFormat(wrappedMetadataFormat)) {
        MetadataDecoder wrappedMetadataDecoder =
            decoderFactory.createDecoder(wrappedMetadataFormat);
        // wrappedMetadataFormat != null so wrappedMetadataBytes must be non-null too.
        byte[] wrappedMetadataBytes = checkNotNull(metadata.get(i).getWrappedMetadataBytes());
        buffer.clear();
        buffer.ensureSpaceForWrite(wrappedMetadataBytes.length);
        castNonNull(buffer.data).put(wrappedMetadataBytes);
        buffer.flip();
        @Nullable Metadata innerMetadata = wrappedMetadataDecoder.decode(buffer);
        if (innerMetadata != null) {
          // The decoding succeeded, so we'll try another level of unwrapping.
          decodeWrappedMetadata(innerMetadata, decodedEntries);
        }
      } else {
        // Entry doesn't contain any wrapped metadata, so output it directly.
        decodedEntries.add(metadata.get(i));
      }
    }
  }

  @Override
  protected void onDisabled() {
    pendingMetadata = null;
    decoder = null;
    outputStreamOffsetUs = C.TIME_UNSET;
    nextSourceReadPositionUs = C.TIME_UNSET;
    sourceReadPending = true;
  }

  @Override
  public boolean isEnded() {
    return outputStreamEnded;
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case MSG_INVOKE_RENDERER:
        invokeRendererInternal((Metadata) msg.obj);
        return true;
      default:
        // Should never happen.
        throw new IllegalStateException();
    }
  }

  private void readMetadata(long positionUs) {
    if (!inputStreamEnded && pendingMetadata == null) {
      buffer.clear();
      FormatHolder formatHolder = getFormatHolder();
      @ReadDataResult
      int result =
          readSource(
              formatHolder, buffer, SampleStream.FLAG_PEEK | SampleStream.FLAG_OMIT_SAMPLE_DATA);
      while (result == C.RESULT_FORMAT_READ) {
        // Format reads are not repeated by a subsequent non-peek read, so handle it here.
        subsampleOffsetUs = checkNotNull(formatHolder.format).subsampleOffsetUs;
        result =
            readSource(
                formatHolder, buffer, SampleStream.FLAG_PEEK | SampleStream.FLAG_OMIT_SAMPLE_DATA);
      }
      if (result == C.RESULT_NOTHING_READ && !buffer.isEndOfStream()) {
        // Nothing to consume yet.
        return;
      } else if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream()) {
        // New buffer available, only consume if close enough to the current position.
        if (!outputMetadataEarly && positionUs < buffer.timeUs - MAX_READ_AHEAD_DURATION_US) {
          nextSourceReadPositionUs = buffer.timeUs - MAX_READ_AHEAD_DURATION_US;
          return;
        }
      } else if (result == C.RESULT_BUFFER_READ || result == C.RESULT_NOTHING_READ) {
        // EOS signal available, only consume if close enough to the stream duration.
        long streamEndPositionUs = getStreamEndPositionUs();
        long positionInPeriodUs = positionUs - getStreamOffsetUs();
        if (streamEndPositionUs == C.TIME_UNSET
            || positionInPeriodUs < streamEndPositionUs - MAX_READ_AHEAD_DURATION_US) {
          nextSourceReadPositionUs =
              streamEndPositionUs == C.TIME_UNSET
                  ? C.TIME_END_OF_SOURCE
                  : getStreamOffsetUs() + streamEndPositionUs - MAX_READ_AHEAD_DURATION_US;
          return;
        }
      }
      result = readSource(formatHolder, buffer, /* readFlags= */ 0);
      if (result == C.RESULT_BUFFER_READ) {
        if (buffer.isEndOfStream()) {
          inputStreamEnded = true;
        } else if (buffer.timeUs >= getLastResetPositionUs()) {
          // Ignore metadata before start position.
          buffer.subsampleOffsetUs = subsampleOffsetUs;
          buffer.flip();
          @Nullable Metadata metadata = castNonNull(decoder).decode(buffer);
          if (metadata != null) {
            List<Metadata.Entry> entries = new ArrayList<>(metadata.length());
            decodeWrappedMetadata(metadata, entries);
            if (!entries.isEmpty()) {
              Metadata expandedMetadata =
                  new Metadata(getPresentationTimeUs(buffer.timeUs), entries);
              pendingMetadata = expandedMetadata;
            }
          }
        }
        if (!inputStreamEnded && pendingMetadata == null) {
          nextSourceReadPositionUs = positionUs;
        }
      } else if (result == C.RESULT_FORMAT_READ) {
        subsampleOffsetUs = checkNotNull(formatHolder.format).subsampleOffsetUs;
        nextSourceReadPositionUs = positionUs;
      }
    }
  }

  private boolean outputMetadata(long positionUs) {
    boolean didOutput = false;
    if (pendingMetadata != null
        && (outputMetadataEarly
            || pendingMetadata.presentationTimeUs <= getPresentationTimeUs(positionUs))) {
      invokeRenderer(pendingMetadata);
      pendingMetadata = null;
      didOutput = true;
    }
    if (inputStreamEnded && pendingMetadata == null) {
      outputStreamEnded = true;
    }
    return didOutput;
  }

  private void invokeRenderer(Metadata metadata) {
    if (outputHandler != null) {
      outputHandler.obtainMessage(MSG_INVOKE_RENDERER, metadata).sendToTarget();
    } else {
      invokeRendererInternal(metadata);
    }
  }

  private void invokeRendererInternal(Metadata metadata) {
    output.onMetadata(metadata);
  }

  @SideEffectFree
  private long getPresentationTimeUs(long positionUs) {
    checkState(positionUs != C.TIME_UNSET);
    checkState(outputStreamOffsetUs != C.TIME_UNSET);

    return positionUs - outputStreamOffsetUs;
  }
}
