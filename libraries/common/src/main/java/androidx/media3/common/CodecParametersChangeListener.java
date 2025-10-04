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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;
import java.util.List;

/** A listener for changes to codec parameters. */
@UnstableApi
public interface CodecParametersChangeListener {

  /**
   * Called when one or more codec parameters have changed.
   *
   * @param codecParameters A {@link CodecParameters} instance containing the set of changed
   *     parameters.
   */
  void onCodecParametersChanged(CodecParameters codecParameters);

  /**
   * Returns a list of parameter keys that the listener is interested in.
   *
   * <p>The player will only call {@link #onCodecParametersChanged} with parameters whose keys are
   * included in this list. This allows the player to avoid unnecessary work querying parameters
   * that the listener does not care about. If the list is empty, the listener will not be called.
   *
   * @return An {@link List} of the requested keys.
   */
  List<String> getFilterKeys();
}
