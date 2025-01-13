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
package androidx.media3.exoplayer;

import androidx.media3.common.util.UnstableApi;

/** A list of {@link RendererCapabilities}. */
@UnstableApi
public interface RendererCapabilitiesList {

  /** A factory for {@link RendererCapabilitiesList} instances. */
  interface Factory {

    /** Creates a {@link RendererCapabilitiesList} instance. */
    RendererCapabilitiesList createRendererCapabilitiesList();
  }

  /** Returns an array of {@link RendererCapabilities}. */
  RendererCapabilities[] getRendererCapabilities();

  /** Returns the number of {@link RendererCapabilities}. */
  int size();

  /** Releases any resources associated with this {@link RendererCapabilitiesList}. */
  void release();
}
