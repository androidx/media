/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer;

import androidx.media3.common.util.UnstableApi;

/** A listener for changes to codec parameters. */
@UnstableApi
public interface CodecParametersChangeListener {

  /**
   * Called when one or more of the parameters this listener was registered for have changed.
   *
   * <p>The provided {@link CodecParameters} object represents the new state and contains entries
   * for the keys this listener is subscribed to, but only if they are currently reported by the
   * codec.
   *
   * @param codecParameters A {@link CodecParameters} instance containing the current values for the
   *     keys this listener is subscribed to.
   */
  void onCodecParametersChanged(CodecParameters codecParameters);
}
