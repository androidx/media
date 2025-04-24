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
package androidx.media3.exoplayer.trackselection;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import java.util.List;

/** An {@link ExoTrackSelection} forwarding all calls to a wrapped instance. */
@UnstableApi
public class ForwardingTrackSelection implements ExoTrackSelection {
  private final ExoTrackSelection trackSelection;

  /**
   * Creates the forwarding track selection.
   *
   * @param trackSelection The wrapped {@link ExoTrackSelection}.
   */
  public ForwardingTrackSelection(ExoTrackSelection trackSelection) {
    this.trackSelection = trackSelection;
  }

  /** Returns the wrapped {@link ExoTrackSelection}. */
  public ExoTrackSelection getWrappedInstance() {
    return trackSelection;
  }

  @Override
  public void enable() {
    trackSelection.enable();
  }

  @Override
  public void disable() {
    trackSelection.disable();
  }

  @Override
  public Format getSelectedFormat() {
    return trackSelection.getSelectedFormat();
  }

  @Override
  public int getSelectedIndexInTrackGroup() {
    return trackSelection.getSelectedIndexInTrackGroup();
  }

  @Override
  public int getSelectedIndex() {
    return trackSelection.getSelectedIndex();
  }

  @Override
  public @C.SelectionReason int getSelectionReason() {
    return trackSelection.getSelectionReason();
  }

  @Nullable
  @Override
  public Object getSelectionData() {
    return trackSelection.getSelectionData();
  }

  @Override
  public void onPlaybackSpeed(float playbackSpeed) {
    trackSelection.onPlaybackSpeed(playbackSpeed);
  }

  @Override
  public void onDiscontinuity() {
    trackSelection.onDiscontinuity();
  }

  @Override
  public void onRebuffer() {
    trackSelection.onRebuffer();
  }

  @Override
  public void onPlayWhenReadyChanged(boolean playWhenReady) {
    trackSelection.onPlayWhenReadyChanged(playWhenReady);
  }

  @Override
  public void updateSelectedTrack(
      long playbackPositionUs,
      long bufferedDurationUs,
      long availableDurationUs,
      List<? extends MediaChunk> queue,
      MediaChunkIterator[] mediaChunkIterators) {
    trackSelection.updateSelectedTrack(
        playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators);
  }

  @Override
  public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
  }

  @Override
  public boolean shouldCancelChunkLoad(
      long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
    return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue);
  }

  @Override
  public boolean excludeTrack(int index, long exclusionDurationMs) {
    return trackSelection.excludeTrack(index, exclusionDurationMs);
  }

  @Override
  public boolean isTrackExcluded(int index, long nowMs) {
    return trackSelection.isTrackExcluded(index, nowMs);
  }

  @Override
  public long getLatestBitrateEstimate() {
    return trackSelection.getLatestBitrateEstimate();
  }

  @Override
  public @Type int getType() {
    return trackSelection.getType();
  }

  @Override
  public TrackGroup getTrackGroup() {
    return trackSelection.getTrackGroup();
  }

  @Override
  public int length() {
    return trackSelection.length();
  }

  @Override
  public Format getFormat(int index) {
    return trackSelection.getFormat(index);
  }

  @Override
  public int getIndexInTrackGroup(int index) {
    return trackSelection.getIndexInTrackGroup(index);
  }

  @Override
  public int indexOf(Format format) {
    return trackSelection.indexOf(format);
  }

  @Override
  public int indexOf(int indexInTrackGroup) {
    return trackSelection.indexOf(indexInTrackGroup);
  }

  @Override
  public int hashCode() {
    return trackSelection.hashCode();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ForwardingTrackSelection)) {
      return false;
    }
    ForwardingTrackSelection other = (ForwardingTrackSelection) obj;
    return trackSelection.equals(other.trackSelection);
  }
}
