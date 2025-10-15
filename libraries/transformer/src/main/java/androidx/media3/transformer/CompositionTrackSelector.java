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

import static androidx.media3.transformer.EditedMediaItemSequence.getEditedMediaItem;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link TrackSelector} implementation for {@link CompositionPlayer}. */
/* package */ final class CompositionTrackSelector extends TrackSelector {

  public interface Listener {
    void onVideoTrackSelection(boolean selected, int inputIndex);
  }

  private final TrackSelectorInternal trackSelectorInternal;

  private @MonotonicNonNull EditedMediaItem currentEditedMediaItem;

  public CompositionTrackSelector(Context context, Listener listener, int sequenceIndex) {
    trackSelectorInternal = new TrackSelectorInternal(context, listener, sequenceIndex);
  }

  @Override
  public void init(InvalidationListener listener, BandwidthMeter bandwidthMeter) {
    super.init(listener, bandwidthMeter);
    trackSelectorInternal.init(listener, bandwidthMeter);
  }

  @Override
  public TrackSelectorResult selectTracks(
      RendererCapabilities[] rendererCapabilities,
      TrackGroupArray trackGroups,
      MediaSource.MediaPeriodId periodId,
      Timeline timeline)
      throws ExoPlaybackException {
    Timeline.Period period = timeline.getPeriodByUid(periodId.periodUid, new Timeline.Period());
    checkState(period.id instanceof EditedMediaItemSequence);
    EditedMediaItemSequence sequence = (EditedMediaItemSequence) period.id;
    currentEditedMediaItem =
        getEditedMediaItem(sequence, /* index= */ timeline.getIndexOfPeriod(periodId.periodUid));
    boolean disableVideoPlayback = false;
    for (int j = 0; j < sequence.editedMediaItems.size(); j++) {
      disableVideoPlayback |= sequence.editedMediaItems.get(j).removeVideo;
    }
    trackSelectorInternal.setDisableVideoPlayback(disableVideoPlayback);

    return trackSelectorInternal.selectTracks(
        rendererCapabilities, trackGroups, periodId, timeline);
  }

  @Override
  public void onSelectionActivated(@Nullable Object info) {
    trackSelectorInternal.onSelectionActivated(info);
  }

  @Override
  public TrackSelectionParameters getParameters() {
    return trackSelectorInternal.getParameters();
  }

  @Override
  public void setParameters(TrackSelectionParameters parameters) {
    trackSelectorInternal.setParameters(parameters);
  }

  @Override
  public boolean isSetParametersSupported() {
    return true;
  }

  /**
   * A {@link DefaultTrackSelector} extension to de-select generated audio when the audio from the
   * media is playable.
   */
  private final class TrackSelectorInternal extends DefaultTrackSelector {

    private static final String SILENCE_AUDIO_TRACK_GROUP_ID = "0:";
    private static final String BLANK_IMAGE_TRACK_GROUP_ID = "1:";
    private final Listener listener;
    private final int sequenceIndex;

    private boolean disableVideoPlayback;

    public TrackSelectorInternal(Context context, Listener listener, int sequenceIndex) {
      super(context);
      this.sequenceIndex = sequenceIndex;
      this.listener = listener;
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectAudioTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        @RendererCapabilities.AdaptiveSupport int[] rendererMixedMimeTypeAdaptationSupports,
        Parameters params)
        throws ExoPlaybackException {

      int audioRenderIndex = C.INDEX_UNSET;
      for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
        if (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
          audioRenderIndex = i;
          break;
        }
      }
      checkState(audioRenderIndex != C.INDEX_UNSET);

      TrackGroupArray audioTrackGroups = mappedTrackInfo.getTrackGroups(audioRenderIndex);
      // If there's only one audio TrackGroup, it'll be silence, there's no need to override track
      // selection.
      if (audioTrackGroups.length > 1) {
        if (checkNotNull(currentEditedMediaItem).removeAudio) {
          // If removing audio, disable all media audio tracks, other than the silence.
          for (int i = 0; i < audioTrackGroups.length; i++) {
            if (audioTrackGroups.get(i).id.startsWith(SILENCE_AUDIO_TRACK_GROUP_ID)) {
              continue;
            }
            for (int j = 0; j < audioTrackGroups.get(i).length; j++) {
              rendererFormatSupports[audioRenderIndex][i][j] =
                  RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
            }
          }
        } else {
          // Check if the media audio is playable.
          boolean shouldUseMediaAudio = false;
          int silenceAudioTrackGroupIndex = C.INDEX_UNSET;
          for (int i = 0; i < audioTrackGroups.length; i++) {
            if (audioTrackGroups.get(i).id.startsWith(SILENCE_AUDIO_TRACK_GROUP_ID)) {
              silenceAudioTrackGroupIndex = i;
              continue;
            }
            // For non-silence tracks
            for (int j = 0; j < audioTrackGroups.get(i).length; j++) {
              shouldUseMediaAudio |=
                  RendererCapabilities.getFormatSupport(
                          rendererFormatSupports[audioRenderIndex][i][j])
                      == C.FORMAT_HANDLED;
            }
          }
          checkState(silenceAudioTrackGroupIndex != C.INDEX_UNSET);

          if (shouldUseMediaAudio) {
            // Disable silence if the media's audio track is playable.
            rendererFormatSupports[audioRenderIndex][silenceAudioTrackGroupIndex][0] =
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
          }
        }
      }

      return super.selectAudioTrack(
          mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectVideoTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        @RendererCapabilities.AdaptiveSupport int[] mixedMimeTypeSupports,
        Parameters params,
        @Nullable String selectedAudioLanguage)
        throws ExoPlaybackException {

      @Nullable
      Pair<ExoTrackSelection.Definition, Integer> trackSelection =
          super.selectVideoTrack(
              mappedTrackInfo,
              rendererFormatSupports,
              mixedMimeTypeSupports,
              params,
              selectedAudioLanguage);
      if (disableVideoPlayback) {
        trackSelection = null;
      }
      listener.onVideoTrackSelection(/* selected= */ trackSelection != null, sequenceIndex);
      return trackSelection;
    }

    @Nullable
    @Override
    protected Pair<ExoTrackSelection.Definition, Integer> selectImageTrack(
        MappedTrackInfo mappedTrackInfo,
        @RendererCapabilities.Capabilities int[][][] rendererFormatSupports,
        Parameters params)
        throws ExoPlaybackException {

      int imageRenderIndex = C.INDEX_UNSET;
      for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
        if (mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_IMAGE) {
          imageRenderIndex = i;
          break;
        }
      }
      checkState(imageRenderIndex != C.INDEX_UNSET);

      TrackGroupArray imageTrackGroups = mappedTrackInfo.getTrackGroups(imageRenderIndex);
      // If there's only one image TrackGroup, there's no need to override track selection
      if (imageTrackGroups.length > 1) {
        // TODO(b/419255366): Support `removeVideo` full functionality
        // Check if media image is playable.
        boolean shouldUseMediaImage = false;
        int blankImageTrackGroupIndex = C.INDEX_UNSET;
        for (int i = 0; i < imageTrackGroups.length; i++) {
          if (imageTrackGroups.get(i).id.startsWith(BLANK_IMAGE_TRACK_GROUP_ID)) {
            blankImageTrackGroupIndex = i;
            continue;
          }
          for (int j = 0; j < imageTrackGroups.get(i).length; j++) {
            shouldUseMediaImage |=
                RendererCapabilities.getFormatSupport(
                        rendererFormatSupports[imageRenderIndex][i][j])
                    == C.FORMAT_HANDLED;
          }
        }
        checkState(blankImageTrackGroupIndex != C.INDEX_UNSET);

        if (shouldUseMediaImage) {
          // Disable blank images if the media's image track is playable.
          rendererFormatSupports[imageRenderIndex][blankImageTrackGroupIndex][0] =
              RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
      }

      @Nullable
      Pair<ExoTrackSelection.Definition, Integer> trackSelection =
          super.selectImageTrack(mappedTrackInfo, rendererFormatSupports, params);
      if (disableVideoPlayback) {
        trackSelection = null;
      }
      // Images are treated as video tracks.
      listener.onVideoTrackSelection(/* selected= */ trackSelection != null, sequenceIndex);
      return trackSelection;
    }

    public void setDisableVideoPlayback(boolean disableVideoPlayback) {
      this.disableVideoPlayback = disableVideoPlayback;
    }
  }
}
