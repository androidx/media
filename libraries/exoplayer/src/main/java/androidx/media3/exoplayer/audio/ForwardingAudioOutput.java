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
package androidx.media3.exoplayer.audio;

import android.media.AudioDeviceInfo;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import java.nio.ByteBuffer;

/** A forwarding base class that delegates all calls to the provided {@link AudioOutput}. */
public class ForwardingAudioOutput implements AudioOutput {

  private final AudioOutput audioOutput;

  /**
   * Creates the forwarding {@link AudioOutput}.
   *
   * @param audioOutput The wrapped {@link AudioOutput}.
   */
  public ForwardingAudioOutput(AudioOutput audioOutput) {
    this.audioOutput = audioOutput;
  }

  @Override
  public void addListener(Listener listener) {
    audioOutput.addListener(listener);
  }

  @Override
  public void play() {
    audioOutput.play();
  }

  @Override
  public void pause() {
    audioOutput.pause();
  }

  @Override
  public boolean write(ByteBuffer buffer, int encodedAccessUnitCount, long presentationTimeUs)
      throws WriteException {
    return audioOutput.write(buffer, encodedAccessUnitCount, presentationTimeUs);
  }

  @Override
  public void flush() {
    audioOutput.flush();
  }

  @Override
  public void stop() {
    audioOutput.stop();
  }

  @Override
  public void release() {
    audioOutput.release();
  }

  @Override
  public void setVolume(float volume) {
    audioOutput.setVolume(volume);
  }

  @Override
  public boolean isOffloadedPlayback() {
    return audioOutput.isOffloadedPlayback();
  }

  @Override
  public int getAudioSessionId() {
    return audioOutput.getAudioSessionId();
  }

  @Override
  public int getSampleRate() {
    return audioOutput.getSampleRate();
  }

  @Override
  public long getBufferSizeInFrames() {
    return audioOutput.getBufferSizeInFrames();
  }

  @Override
  public long getPositionUs() {
    return audioOutput.getPositionUs();
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return audioOutput.getPlaybackParameters();
  }

  @Override
  public boolean isStalled() {
    return audioOutput.isStalled();
  }

  @Override
  public void removeListener(Listener listener) {
    audioOutput.removeListener(listener);
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParams) {
    audioOutput.setPlaybackParameters(playbackParams);
  }

  @Override
  public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
    audioOutput.setOffloadDelayPadding(delayInFrames, paddingInFrames);
  }

  @Override
  public void setOffloadEndOfStream() {
    audioOutput.setOffloadEndOfStream();
  }

  @UnstableApi
  @Override
  public void setPlayerId(PlayerId playerId) {
    audioOutput.setPlayerId(playerId);
  }

  @Override
  public void attachAuxEffect(int effectId) {
    audioOutput.attachAuxEffect(effectId);
  }

  @Override
  public void setAuxEffectSendLevel(float level) {
    audioOutput.setAuxEffectSendLevel(level);
  }

  @Override
  public void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
    audioOutput.setPreferredDevice(preferredDevice);
  }
}
