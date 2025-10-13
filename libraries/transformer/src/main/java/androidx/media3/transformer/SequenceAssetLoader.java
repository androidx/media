/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.common.util.Util.percentInt;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_ASSET_LOADER;
import static androidx.media3.effect.DebugTraceUtil.EVENT_INPUT_FORMAT;
import static androidx.media3.effect.DebugTraceUtil.EVENT_OUTPUT_FORMAT;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED;
import static androidx.media3.transformer.TransformerUtil.getProcessedTrackType;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.DebugTraceUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An {@link AssetLoader} that is composed of a {@linkplain EditedMediaItemSequence sequence} of
 * non-overlapping {@linkplain AssetLoader asset loaders}.
 */
/* package */ final class SequenceAssetLoader implements AssetLoader, AssetLoader.Listener {

  private static final Format FORCE_AUDIO_TRACK_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setSampleRate(44100)
          .setChannelCount(2)
          .build();

  private static final int BLANK_IMAGE_BITMAP_WIDTH = 1;
  private static final int BLANK_IMAGE_BITMAP_HEIGHT = 1;
  private static final Format BLANK_IMAGE_BITMAP_FORMAT =
      new Format.Builder()
          .setWidth(BLANK_IMAGE_BITMAP_WIDTH)
          .setHeight(BLANK_IMAGE_BITMAP_HEIGHT)
          .setSampleMimeType(MimeTypes.IMAGE_RAW)
          .setColorInfo(ColorInfo.SRGB_BT709_FULL)
          .build();

  private static final float BLANK_IMAGE_FRAME_RATE = 30.0f;

  private static final int RETRY_DELAY_MS = 10;

  private final List<EditedMediaItem> editedMediaItems;
  private final ImmutableSet<@C.TrackType Integer> sequenceTrackTypes;
  private final boolean isLooping;
  private final Factory assetLoaderFactory;
  private final CompositionSettings compositionSettings;
  private final Listener sequenceAssetLoaderListener;
  private final HandlerWrapper handler;

  /**
   * A mapping from track types to {@link SampleConsumer} instances.
   *
   * <p>This map never contains more than 2 entries, as the only track types allowed are audio and
   * video.
   */
  private final Map<Integer, SampleConsumerWrapper> sampleConsumersByTrackType;

  /**
   * A mapping from track types to {@link OnMediaItemChangedListener} instances.
   *
   * <p>This map never contains more than 2 entries, as the only track types allowed are audio and
   * video.
   */
  private final Map<Integer, OnMediaItemChangedListener> mediaItemChangedListenersByTrackType;

  private final ImmutableList.Builder<ExportResult.ProcessedInput> processedInputsBuilder;
  private final AtomicInteger reportedTrackCount;
  private final AtomicInteger nonEndedTrackCount;

  private boolean isCurrentAssetFirstAsset;
  private int currentMediaItemIndex;
  private AssetLoader currentAssetLoader;
  private boolean isTrackCountReported;
  private boolean decodeAudio;
  private boolean decodeVideo;
  private int sequenceLoopCount;
  private int processedInputsSize;
  private @MonotonicNonNull Format currentAudioInputFormat;
  private @MonotonicNonNull Format currentVideoInputFormat;

  // Accessed when switching asset loader.
  private volatile boolean released;

  private volatile long currentAssetDurationUs;
  private volatile long currentAssetDurationAfterEffectsAppliedUs;
  private volatile long maxSequenceDurationUs;
  private volatile boolean isMaxSequenceDurationUsFinal;
  private volatile boolean sequenceHasAudio;
  private volatile boolean sequenceHasVideo;

  public SequenceAssetLoader(
      EditedMediaItemSequence sequence,
      Factory assetLoaderFactory,
      CompositionSettings compositionSettings,
      Listener listener,
      Clock clock,
      Looper looper) {
    sequenceTrackTypes = sequence.trackTypes;
    editedMediaItems =
        applySequenceTrackTypeConstraints(sequenceTrackTypes, sequence.editedMediaItems);
    isLooping = sequence.isLooping;
    this.assetLoaderFactory = new GapInterceptingAssetLoaderFactory(assetLoaderFactory);
    this.compositionSettings = compositionSettings;
    sequenceAssetLoaderListener = listener;
    handler = clock.createHandler(looper, /* callback= */ null);
    sampleConsumersByTrackType = new HashMap<>();
    mediaItemChangedListenersByTrackType = new HashMap<>();
    processedInputsBuilder = new ImmutableList.Builder<>();
    reportedTrackCount = new AtomicInteger();
    nonEndedTrackCount = new AtomicInteger();
    isCurrentAssetFirstAsset = true;
    // It's safe to use "this" because we don't start the AssetLoader before exiting the
    // constructor.
    @SuppressWarnings("nullness:argument.type.incompatible")
    AssetLoader currentAssetLoader =
        this.assetLoaderFactory.createAssetLoader(
            editedMediaItems.get(0), looper, /* listener= */ this, compositionSettings);
    this.currentAssetLoader = currentAssetLoader;
  }

  // Methods called from TransformerInternal thread.

  @Override
  public void start() {
    currentAssetLoader.start();
    if (editedMediaItems.size() > 1 || isLooping) {
      sequenceAssetLoaderListener.onDurationUs(C.TIME_UNSET);
    }
  }

  @Override
  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (isLooping) {
      return Transformer.PROGRESS_STATE_UNAVAILABLE;
    }
    int progressState = currentAssetLoader.getProgress(progressHolder);
    int mediaItemCount = editedMediaItems.size();
    if (mediaItemCount == 1 || progressState == PROGRESS_STATE_NOT_STARTED) {
      return progressState;
    }

    int progress = percentInt(currentMediaItemIndex, mediaItemCount);
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      progress += progressHolder.progress / mediaItemCount;
    }
    progressHolder.progress = progress;
    return PROGRESS_STATE_AVAILABLE;
  }

  @Override
  public ImmutableMap<Integer, String> getDecoderNames() {
    return currentAssetLoader.getDecoderNames();
  }

  /**
   * Returns the partially or entirely {@linkplain ExportResult.ProcessedInput processed inputs}.
   */
  public ImmutableList<ExportResult.ProcessedInput> getProcessedInputs() {
    addCurrentProcessedInput();
    return processedInputsBuilder.build();
  }

  @Override
  public void release() {
    currentAssetLoader.release();
    released = true;
  }

  private void addCurrentProcessedInput() {
    if ((sequenceLoopCount * editedMediaItems.size() + currentMediaItemIndex)
        >= processedInputsSize) {
      MediaItem mediaItem = editedMediaItems.get(currentMediaItemIndex).mediaItem;
      ImmutableMap<Integer, String> decoders = getDecoderNames();
      processedInputsBuilder.add(
          new ExportResult.ProcessedInput(
              mediaItem,
              currentAssetDurationUs,
              currentAudioInputFormat,
              currentVideoInputFormat,
              decoders.get(C.TRACK_TYPE_AUDIO),
              decoders.get(C.TRACK_TYPE_VIDEO)));
      processedInputsSize++;
    }
  }

  // Methods called from AssetLoader threads.

  /**
   * Adds an {@link OnMediaItemChangedListener} for the given track type.
   *
   * <p>There can't be more than one {@link OnMediaItemChangedListener} for the same track type.
   *
   * <p>Must be called from the thread used by the current {@link AssetLoader} to pass data to the
   * {@link SampleConsumer}.
   *
   * @param onMediaItemChangedListener The {@link OnMediaItemChangedListener}.
   * @param trackType The {@link C.TrackType} for which to listen to {@link MediaItem} change
   *     events. Must be {@link C#TRACK_TYPE_AUDIO} or {@link C#TRACK_TYPE_VIDEO}.
   */
  public void addOnMediaItemChangedListener(
      OnMediaItemChangedListener onMediaItemChangedListener, @C.TrackType int trackType) {
    checkArgument(trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO);
    checkArgument(mediaItemChangedListenersByTrackType.get(trackType) == null);
    mediaItemChangedListenersByTrackType.put(trackType, onMediaItemChangedListener);
  }

  @Override
  public boolean onTrackAdded(Format inputFormat, @SupportedOutputTypes int supportedOutputTypes) {
    boolean isAudio = getProcessedTrackType(inputFormat.sampleMimeType) == C.TRACK_TYPE_AUDIO;
    DebugTraceUtil.logEvent(
        COMPONENT_ASSET_LOADER,
        EVENT_INPUT_FORMAT,
        C.TIME_UNSET,
        "%s:%s",
        isAudio ? "audio" : "video",
        inputFormat);

    if (isAudio) {
      currentAudioInputFormat = inputFormat;
    } else {
      currentVideoInputFormat = inputFormat;
    }

    if (!isCurrentAssetFirstAsset) {
      boolean decode = isAudio ? decodeAudio : decodeVideo;
      if (decode) {
        checkArgument((supportedOutputTypes & SUPPORTED_OUTPUT_TYPE_DECODED) != 0);
      } else {
        checkArgument((supportedOutputTypes & SUPPORTED_OUTPUT_TYPE_ENCODED) != 0);
      }
      return decode;
    }

    boolean shouldAddAudioGap = false;
    boolean shouldAddVideoGap = false;
    if (reportedTrackCount.get() == 1) {
      shouldAddAudioGap = sequenceTrackTypes.contains(C.TRACK_TYPE_AUDIO) && !isAudio;
      shouldAddVideoGap = sequenceTrackTypes.contains(C.TRACK_TYPE_VIDEO) && isAudio;
    }

    if (!isTrackCountReported) {
      int trackCount = reportedTrackCount.get() + (shouldAddAudioGap || shouldAddVideoGap ? 1 : 0);
      sequenceAssetLoaderListener.onTrackCount(trackCount);
      isTrackCountReported = true;
    }

    boolean decodeOutput =
        sequenceAssetLoaderListener.onTrackAdded(inputFormat, supportedOutputTypes);

    if (isAudio) {
      decodeAudio = decodeOutput;
    } else {
      decodeVideo = decodeOutput;
    }

    if (shouldAddAudioGap) {
      sequenceAssetLoaderListener.onTrackAdded(
          FORCE_AUDIO_TRACK_FORMAT, SUPPORTED_OUTPUT_TYPE_DECODED);
      decodeAudio = true;
    }
    if (shouldAddVideoGap) {
      sequenceAssetLoaderListener.onTrackAdded(
          BLANK_IMAGE_BITMAP_FORMAT, SUPPORTED_OUTPUT_TYPE_DECODED);
      decodeVideo = true;
    }

    return decodeOutput;
  }

  @Nullable
  @Override
  public SampleConsumerWrapper onOutputFormat(Format format) throws ExportException {
    @C.TrackType int trackType = getProcessedTrackType(format.sampleMimeType);
    DebugTraceUtil.logEvent(
        COMPONENT_ASSET_LOADER,
        EVENT_OUTPUT_FORMAT,
        C.TIME_UNSET,
        "%s:%s",
        Util.getTrackTypeString(trackType),
        format);

    SampleConsumerWrapper sampleConsumer;
    if (isCurrentAssetFirstAsset) {
      // TODO: b/445884217 - Remove logic that relies on the first item for gap generation.
      if (trackType == C.TRACK_TYPE_VIDEO) {
        sequenceHasVideo = true;
      } else {
        sequenceHasAudio = true;
      }
      @Nullable
      SampleConsumer wrappedSampleConsumer = sequenceAssetLoaderListener.onOutputFormat(format);
      if (wrappedSampleConsumer == null) {
        return null;
      }
      sampleConsumer = new SampleConsumerWrapper(wrappedSampleConsumer, trackType);
      sampleConsumersByTrackType.put(trackType, sampleConsumer);

      if (reportedTrackCount.get() == 1) {
        if (sequenceTrackTypes.contains(C.TRACK_TYPE_AUDIO) && trackType == C.TRACK_TYPE_VIDEO) {
          SampleConsumer wrappedAudioSampleConsumer =
              checkNotNull(
                  sequenceAssetLoaderListener.onOutputFormat(
                      FORCE_AUDIO_TRACK_FORMAT
                          .buildUpon()
                          .setSampleMimeType(MimeTypes.AUDIO_RAW)
                          .setPcmEncoding(C.ENCODING_PCM_16BIT)
                          .build()));
          sampleConsumersByTrackType.put(
              C.TRACK_TYPE_AUDIO,
              new SampleConsumerWrapper(wrappedAudioSampleConsumer, C.TRACK_TYPE_AUDIO));
        } else if (sequenceTrackTypes.contains(C.TRACK_TYPE_VIDEO)
            && trackType == C.TRACK_TYPE_AUDIO) {
          SampleConsumer wrappedVideoSampleConsumer =
              checkNotNull(sequenceAssetLoaderListener.onOutputFormat(BLANK_IMAGE_BITMAP_FORMAT));
          sampleConsumersByTrackType.put(
              C.TRACK_TYPE_VIDEO,
              new SampleConsumerWrapper(wrappedVideoSampleConsumer, C.TRACK_TYPE_VIDEO));
        }
      }
    } else {
      // TODO: b/445884217 - Remove check when removing deprecated EditedMediaItemSequence methods
      String missingTrackMessage =
          trackType == C.TRACK_TYPE_AUDIO
              ? "The preceding MediaItem does not contain any audio track. If the sequence starts"
                  + " with an item without audio track (like images), followed by items with"
                  + " audio tracks, then"
                  + " EditedMediaItemSequence.Builder.experimentalSetForceAudioTrack() needs to"
                  + " be set to true."
              : "The preceding MediaItem does not contain any video track. If the sequence starts"
                  + " with an item without video track (audio only), followed by items with video"
                  + " tracks, then"
                  + " EditedMediaItemSequence.Builder.experimentalSetForceVideoTrack() needs to"
                  + " be set to true.";
      sampleConsumer = checkNotNull(sampleConsumersByTrackType.get(trackType), missingTrackMessage);
    }
    onMediaItemChanged(trackType, format);
    if (reportedTrackCount.get() == 1 && sampleConsumersByTrackType.size() == 2) {
      // One track is missing from the current media item.
      if (trackType == C.TRACK_TYPE_AUDIO) {
        // Fill video gap with blank frames.
        onMediaItemChanged(C.TRACK_TYPE_VIDEO, /* outputFormat= */ BLANK_IMAGE_BITMAP_FORMAT);
        nonEndedTrackCount.incrementAndGet();
        handler.post(() -> insertBlankFrames(getBlankImageBitmap()));
      } else {
        // Generate audio silence in the AudioGraph by signalling null format.
        onMediaItemChanged(C.TRACK_TYPE_AUDIO, /* outputFormat= */ null);
      }
    }
    return sampleConsumer;
  }

  private static Bitmap getBlankImageBitmap() {
    return Bitmap.createBitmap(
        new int[] {Color.BLACK},
        BLANK_IMAGE_BITMAP_WIDTH,
        BLANK_IMAGE_BITMAP_HEIGHT,
        Bitmap.Config.ARGB_8888);
  }

  private static List<EditedMediaItem> applySequenceTrackTypeConstraints(
      Set<@C.TrackType Integer> sequenceTrackTypes, List<EditedMediaItem> editedMediaItems) {
    if (sequenceTrackTypes.contains(C.TRACK_TYPE_NONE)) {
      return editedMediaItems;
    }
    ImmutableList.Builder<EditedMediaItem> updatedEditedMediaItemsBuilder =
        new ImmutableList.Builder<>();
    for (EditedMediaItem editedMediaItem : editedMediaItems) {
      if (editedMediaItem.isGap()) {
        // Selecting appropriate Gap tracks is handled by GapSignalingAssetLoader
        updatedEditedMediaItemsBuilder.add(editedMediaItem);
        continue;
      }
      updatedEditedMediaItemsBuilder.add(
          editedMediaItem
              .buildUpon()
              .setRemoveAudio(
                  editedMediaItem.removeAudio || !sequenceTrackTypes.contains(C.TRACK_TYPE_AUDIO))
              .setRemoveVideo(
                  editedMediaItem.removeVideo || !sequenceTrackTypes.contains(C.TRACK_TYPE_VIDEO))
              .build());
    }
    return updatedEditedMediaItemsBuilder.build();
  }

  private void insertBlankFrames(Bitmap bitmap) {
    SampleConsumerWrapper videoSampleConsumer =
        checkNotNull(sampleConsumersByTrackType.get(C.TRACK_TYPE_VIDEO));
    if (videoSampleConsumer.queueInputBitmap(
            bitmap,
            new ConstantRateTimestampIterator(currentAssetDurationUs, BLANK_IMAGE_FRAME_RATE))
        != SampleConsumer.INPUT_RESULT_SUCCESS) {
      handler.postDelayed(() -> insertBlankFrames(bitmap), RETRY_DELAY_MS);
    } else {
      videoSampleConsumer.signalEndOfVideoInput();
    }
  }

  private void onMediaItemChanged(int trackType, @Nullable Format outputFormat) {
    @Nullable
    OnMediaItemChangedListener onMediaItemChangedListener =
        mediaItemChangedListenersByTrackType.get(trackType);
    if (onMediaItemChangedListener == null) {
      return;
    }

    EditedMediaItem editedMediaItem = editedMediaItems.get(currentMediaItemIndex);

    onMediaItemChangedListener.onMediaItemChanged(
        editedMediaItem,
        /* durationUs= */ (trackType == C.TRACK_TYPE_AUDIO && isLooping && decodeAudio)
            ? C.TIME_UNSET
            : currentAssetDurationUs,
        /* decodedFormat= */ (editedMediaItem.isGap() && trackType == C.TRACK_TYPE_AUDIO)
            ? null
            : outputFormat,
        /* isLast= */ isLastMediaItemInSequence(),
        /* positionOffsetUs */ 0);
  }

  // Methods called from any thread.

  /**
   * Sets the maximum {@link EditedMediaItemSequence} duration in the {@link Composition}.
   *
   * <p>The duration passed is the current maximum duration. This method can be called multiple
   * times as this duration increases. Indeed, a sequence duration will increase during an export
   * when a new {@link MediaItem} is loaded, which can increase the maximum sequence duration.
   *
   * <p>Can be called from any thread.
   *
   * @param maxSequenceDurationUs The current maximum sequence duration, in microseconds.
   * @param isFinal Whether the duration passed is final. Setting this value to {@code true} means
   *     that the duration passed will not change anymore during the entire export.
   */
  public void setMaxSequenceDurationUs(long maxSequenceDurationUs, boolean isFinal) {
    this.maxSequenceDurationUs = maxSequenceDurationUs;
    isMaxSequenceDurationUsFinal = isFinal;
  }

  @Override
  public void onDurationUs(long durationUs) {
    checkArgument(
        durationUs != C.TIME_UNSET || isLastMediaItemInSequence(),
        "Could not retrieve required duration for EditedMediaItem %s",
        currentMediaItemIndex);
    currentAssetDurationAfterEffectsAppliedUs =
        editedMediaItems.get(currentMediaItemIndex).getDurationAfterEffectsApplied(durationUs);
    currentAssetDurationUs = durationUs;
    if (editedMediaItems.size() == 1 && !isLooping) {
      sequenceAssetLoaderListener.onDurationUs(currentAssetDurationAfterEffectsAppliedUs);
    }
  }

  @Override
  public void onTrackCount(int trackCount) {
    reportedTrackCount.set(trackCount);
    nonEndedTrackCount.set(trackCount);
  }

  @Override
  public void onError(ExportException exportException) {
    sequenceAssetLoaderListener.onError(exportException);
  }

  private boolean isLastMediaItemInSequence() {
    return currentMediaItemIndex == editedMediaItems.size() - 1;
  }

  // Classes accessed from AssetLoader threads.

  private final class SampleConsumerWrapper implements SampleConsumer {

    private final SampleConsumer sampleConsumer;
    private final @C.TrackType int trackType;

    private long totalDurationUs;
    private boolean audioLoopingEnded;
    private boolean videoLoopingEnded;

    public SampleConsumerWrapper(SampleConsumer sampleConsumer, @C.TrackType int trackType) {
      this.sampleConsumer = sampleConsumer;
      this.trackType = trackType;
    }

    @Nullable
    @Override
    public DecoderInputBuffer getInputBuffer() {
      return sampleConsumer.getInputBuffer();
    }

    @Override
    public boolean queueInputBuffer() {
      DecoderInputBuffer inputBuffer = checkNotNull(sampleConsumer.getInputBuffer());
      long globalTimestampUs = totalDurationUs + inputBuffer.timeUs;
      if (isLooping && (globalTimestampUs >= maxSequenceDurationUs || audioLoopingEnded)) {
        if (isMaxSequenceDurationUsFinal && !audioLoopingEnded) {
          checkNotNull(inputBuffer.data).limit(0);
          inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
          // We know that queueInputBuffer() will always return true for the underlying
          // SampleConsumer so there is no need to handle the case where the sample wasn't queued.
          checkState(sampleConsumer.queueInputBuffer());
          audioLoopingEnded = true;
          nonEndedTrackCount.decrementAndGet();
        }
        return false;
      }

      if (inputBuffer.isEndOfStream()) {
        nonEndedTrackCount.decrementAndGet();
        if (!isLastMediaItemInSequence() || isLooping) {
          if (trackType == C.TRACK_TYPE_AUDIO && !isLooping && decodeAudio) {
            // Trigger silence generation (if needed) for a decoded audio track when end of stream
            // is first encountered. This helps us avoid a muxer deadlock when audio track is
            // shorter than video track. Not applicable for looping sequences.
            checkState(sampleConsumer.queueInputBuffer());
          } else {
            inputBuffer.clear();
            inputBuffer.timeUs = 0;
          }
          if (nonEndedTrackCount.get() == 0) {
            switchAssetLoader();
          }
          return true;
        }
      }

      checkState(sampleConsumer.queueInputBuffer());
      return true;
    }

    @Override
    public @InputResult int queueInputBitmap(
        Bitmap inputBitmap, TimestampIterator timestampIterator) {
      if (isLooping) {
        long lastOffsetUs = C.TIME_UNSET;
        while (timestampIterator.hasNext()) {
          long offsetUs = timestampIterator.next();
          if (totalDurationUs + offsetUs > maxSequenceDurationUs) {
            if (!isMaxSequenceDurationUsFinal) {
              return INPUT_RESULT_TRY_AGAIN_LATER;
            }
            if (lastOffsetUs == C.TIME_UNSET) {
              if (!videoLoopingEnded) {
                videoLoopingEnded = true;
                signalEndOfVideoInput();
                return INPUT_RESULT_END_OF_STREAM;
              }
              return INPUT_RESULT_TRY_AGAIN_LATER;
            }
            timestampIterator = new ClippingIterator(timestampIterator.copyOf(), lastOffsetUs);
            videoLoopingEnded = true;
            break;
          }
          lastOffsetUs = offsetUs;
        }
      }
      return sampleConsumer.queueInputBitmap(inputBitmap, timestampIterator.copyOf());
    }

    @Override
    public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
      sampleConsumer.setOnInputFrameProcessedListener(listener);
    }

    @Override
    public void setOnInputSurfaceReadyListener(Runnable runnable) {
      sampleConsumer.setOnInputSurfaceReadyListener(runnable);
    }

    @Override
    public @InputResult int queueInputTexture(int texId, long presentationTimeUs) {
      long globalTimestampUs = totalDurationUs + presentationTimeUs;
      if (isLooping && globalTimestampUs >= maxSequenceDurationUs) {
        if (isMaxSequenceDurationUsFinal && !videoLoopingEnded) {
          videoLoopingEnded = true;
          signalEndOfVideoInput();
          return INPUT_RESULT_END_OF_STREAM;
        }
        return INPUT_RESULT_TRY_AGAIN_LATER;
      }
      return sampleConsumer.queueInputTexture(texId, presentationTimeUs);
    }

    @Override
    public Surface getInputSurface() {
      return sampleConsumer.getInputSurface();
    }

    @Override
    public int getPendingVideoFrameCount() {
      return sampleConsumer.getPendingVideoFrameCount();
    }

    @Override
    public boolean registerVideoFrame(long presentationTimeUs) {
      long globalTimestampUs = totalDurationUs + presentationTimeUs;
      if (isLooping && globalTimestampUs >= maxSequenceDurationUs) {
        if (isMaxSequenceDurationUsFinal && !videoLoopingEnded) {
          videoLoopingEnded = true;
          signalEndOfVideoInput();
        }
        return false;
      }

      return sampleConsumer.registerVideoFrame(presentationTimeUs);
    }

    @Override
    public void signalEndOfVideoInput() {
      nonEndedTrackCount.decrementAndGet();
      boolean videoEnded = isLooping ? videoLoopingEnded : isLastMediaItemInSequence();
      if (videoEnded) {
        sampleConsumer.signalEndOfVideoInput();
      } else if (nonEndedTrackCount.get() == 0) {
        switchAssetLoader();
      }
    }

    private void onAudioGapSignalled() {
      int nonEndedTracks = nonEndedTrackCount.decrementAndGet();
      if (nonEndedTracks == 0 && !isLastMediaItemInSequence()) {
        switchAssetLoader();
      }
    }

    private void switchAssetLoader() {
      handler.post(
          () -> {
            try {
              if (released) {
                return;
              }
              addCurrentProcessedInput();
              totalDurationUs += currentAssetDurationAfterEffectsAppliedUs;
              currentAssetLoader.release();
              isCurrentAssetFirstAsset = false;
              currentMediaItemIndex++;
              if (currentMediaItemIndex == editedMediaItems.size()) {
                currentMediaItemIndex = 0;
                sequenceLoopCount++;
              }
              EditedMediaItem editedMediaItem = editedMediaItems.get(currentMediaItemIndex);
              currentAssetLoader =
                  assetLoaderFactory.createAssetLoader(
                      editedMediaItem,
                      checkNotNull(Looper.myLooper()),
                      /* listener= */ SequenceAssetLoader.this,
                      compositionSettings);
              currentAssetLoader.start();
            } catch (RuntimeException e) {
              onError(
                  ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_UNSPECIFIED));
            }
          });
    }
  }

  /**
   * Wraps a {@link TimestampIterator}, providing all the values in the original timestamp iterator
   * (in the same order) up to and including the first occurrence of the {@code clippingValue}.
   */
  private static final class ClippingIterator implements TimestampIterator {

    private final TimestampIterator iterator;
    private final long clippingValue;
    private boolean hasReachedClippingValue;

    public ClippingIterator(TimestampIterator iterator, long clippingValue) {
      this.iterator = iterator;
      this.clippingValue = clippingValue;
    }

    @Override
    public boolean hasNext() {
      return !hasReachedClippingValue && iterator.hasNext();
    }

    @Override
    public long next() {
      checkState(hasNext());
      long next = iterator.next();
      if (clippingValue <= next) {
        hasReachedClippingValue = true;
      }
      return next;
    }

    @Override
    public TimestampIterator copyOf() {
      return new ClippingIterator(iterator.copyOf(), clippingValue);
    }
  }

  /**
   * Internally signals that the current asset is a {@linkplain
   * EditedMediaItemSequence.Builder#addGap(long) gap}, but does no loading or processing of media.
   *
   * <p>This component requires downstream components to handle generation of the gap media.
   */
  private final class GapSignalingAssetLoader implements AssetLoader {

    private final long durationUs;
    private final boolean shouldProduceAudio;
    private final boolean shouldProduceVideo;
    private final Format audioTrackFormat;
    private final Format audioTrackDecodedFormat;

    private boolean producedAudio;
    private boolean producedVideo;

    private GapSignalingAssetLoader(long durationUs) {
      this.durationUs = durationUs;
      shouldProduceAudio = sequenceHasAudio || sequenceTrackTypes.contains(C.TRACK_TYPE_AUDIO);
      shouldProduceVideo = sequenceHasVideo || sequenceTrackTypes.contains(C.TRACK_TYPE_VIDEO);
      checkState(shouldProduceAudio || shouldProduceVideo);
      this.audioTrackFormat = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build();
      this.audioTrackDecodedFormat =
          new Format.Builder()
              .setSampleMimeType(MimeTypes.AUDIO_RAW)
              .setSampleRate(44100)
              .setChannelCount(2)
              .setPcmEncoding(C.ENCODING_PCM_16BIT)
              .build();
    }

    @Override
    public void start() {
      onDurationUs(durationUs);
      int trackCount = shouldProduceAudio && shouldProduceVideo ? 2 : 1;
      onTrackCount(trackCount);
      if (shouldProduceAudio) {
        onTrackAdded(audioTrackFormat, SUPPORTED_OUTPUT_TYPE_DECODED);
      }
      if (shouldProduceVideo) {
        onTrackAdded(BLANK_IMAGE_BITMAP_FORMAT, SUPPORTED_OUTPUT_TYPE_DECODED);
      }
      outputFormatToSequenceAssetLoader();
    }

    @Override
    public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
      boolean audioPending = shouldProduceAudio && !producedAudio;
      boolean videoPending = shouldProduceVideo && !producedVideo;
      if (audioPending && videoPending) {
        progressHolder.progress = 0;
      } else if (!audioPending && !videoPending) {
        progressHolder.progress = 99;
      } else {
        progressHolder.progress = 50;
      }
      return PROGRESS_STATE_AVAILABLE;
    }

    @Override
    public ImmutableMap<Integer, String> getDecoderNames() {
      return ImmutableMap.of();
    }

    @Override
    public void release() {}

    /** Outputs the gap format, scheduling to try again if unsuccessful. */
    private void outputFormatToSequenceAssetLoader() {
      boolean audioPending = shouldProduceAudio && !producedAudio;
      boolean videoPending = shouldProduceVideo && !producedVideo;
      checkState(audioPending || videoPending);

      try {
        boolean shouldRetry = false;
        if (audioPending) {
          @Nullable
          SampleConsumerWrapper sampleConsumerWrapper = onOutputFormat(audioTrackDecodedFormat);
          if (sampleConsumerWrapper == null) {
            shouldRetry = true;
          } else {
            sampleConsumerWrapper.onAudioGapSignalled();
            producedAudio = true;
          }
        }
        if (videoPending) {
          @Nullable
          SampleConsumerWrapper sampleConsumerWrapper = onOutputFormat(BLANK_IMAGE_BITMAP_FORMAT);
          if (sampleConsumerWrapper == null) {
            shouldRetry = true;
          } else {
            insertBlankFrames(getBlankImageBitmap());
            producedVideo = true;
          }
        }
        if (shouldRetry) {
          handler.postDelayed(this::outputFormatToSequenceAssetLoader, RETRY_DELAY_MS);
        }
      } catch (ExportException e) {
        onError(e);
      } catch (RuntimeException e) {
        onError(ExportException.createForAssetLoader(e, ExportException.ERROR_CODE_UNSPECIFIED));
      }
    }
  }

  /**
   * Intercepts {@link AssetLoader.Factory} calls, when {@linkplain
   * EditedMediaItemSequence.Builder#addGap(long) a gap} is detected, otherwise forwards them to the
   * provided {@link AssetLoader.Factory}.
   *
   * <p>In the case that a gap is detected, a {@link GapSignalingAssetLoader} is returned.
   */
  private final class GapInterceptingAssetLoaderFactory implements AssetLoader.Factory {

    private final AssetLoader.Factory factory;

    public GapInterceptingAssetLoaderFactory(AssetLoader.Factory factory) {
      this.factory = factory;
    }

    @Override
    public AssetLoader createAssetLoader(
        EditedMediaItem editedMediaItem,
        Looper looper,
        Listener listener,
        CompositionSettings compositionSettings) {
      if (editedMediaItem.isGap()) {
        return new GapSignalingAssetLoader(editedMediaItem.durationUs);
      }
      return factory.createAssetLoader(editedMediaItem, looper, listener, compositionSettings);
    }
  }
}
