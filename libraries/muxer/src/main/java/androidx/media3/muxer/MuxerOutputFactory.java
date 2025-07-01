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
package androidx.media3.muxer;

import androidx.media3.common.util.UnstableApi;

/** A factory for providing output destinations for muxing operation. */
@UnstableApi
public interface MuxerOutputFactory {

  /**
   * Returns a {@link SeekableMuxerOutput} for writing {@link Muxer} output.
   *
   * <p>Every call to this method should return a new {@link SeekableMuxerOutput}.
   *
   * <p>The returned {@link SeekableMuxerOutput} will be automatically {@linkplain
   * SeekableMuxerOutput#close() closed} by the muxer when {@link Muxer#close()} is called.
   */
  SeekableMuxerOutput getSeekableMuxerOutput();

  /**
   * Returns a cache file path to be used during muxing operation.
   *
   * <p>Every call to this method should return a new cache file.
   *
   * <p>The app is responsible for deleting the cache file after {@linkplain Muxer#close() closing}
   * the muxer.
   */
  String getCacheFilePath();
}
