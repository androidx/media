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

import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;

/** A forwarding base class that delegates all calls to the provided {@link AudioOutputProvider}. */
public class ForwardingAudioOutputProvider implements AudioOutputProvider {

  private final AudioOutputProvider audioOutputProvider;

  /**
   * Creates the forwarding {@link AudioOutputProvider}.
   *
   * @param audioOutputProvider The wrapped {@link AudioOutputProvider}.
   */
  public ForwardingAudioOutputProvider(AudioOutputProvider audioOutputProvider) {
    this.audioOutputProvider = audioOutputProvider;
  }

  @Override
  public FormatSupport getFormatSupport(FormatConfig formatConfig) {
    return audioOutputProvider.getFormatSupport(formatConfig);
  }

  @Override
  public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
    return audioOutputProvider.getOutputConfig(formatConfig);
  }

  @Override
  public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
    return audioOutputProvider.getAudioOutput(config);
  }

  @Override
  public void addListener(Listener listener) {
    audioOutputProvider.addListener(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    audioOutputProvider.removeListener(listener);
  }

  @UnstableApi
  @Override
  public void setClock(Clock clock) {
    audioOutputProvider.setClock(clock);
  }

  @Override
  public void release() {
    audioOutputProvider.release();
  }
}
