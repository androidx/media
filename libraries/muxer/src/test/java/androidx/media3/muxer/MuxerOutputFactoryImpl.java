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

/** A {@link MuxerOutputFactory} implementation for tests. */
@UnstableApi
public final class MuxerOutputFactoryImpl implements MuxerOutputFactory {
  private final SeekableMuxerOutput seekableMuxerOutput;
  private final String cacheFilePath;

  /** Creates an instance. */
  public MuxerOutputFactoryImpl(SeekableMuxerOutput seekableMuxerOutput) {
    this(seekableMuxerOutput, /* cacheFilePath= */ "");
  }

  /** Creates an instance. */
  public MuxerOutputFactoryImpl(SeekableMuxerOutput seekableMuxerOutput, String cacheFilePath) {
    this.seekableMuxerOutput = seekableMuxerOutput;
    this.cacheFilePath = cacheFilePath;
  }

  @Override
  public SeekableMuxerOutput getSeekableMuxerOutput() {
    return seekableMuxerOutput;
  }

  @Override
  public String getCacheFilePath() {
    return cacheFilePath;
  }
}
