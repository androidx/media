/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer.codecdb;

import static androidx.media3.common.util.Util.getVideoCodecFlagForMime;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;

/**
 * Lightweight dataclass that stores a {@link VideoEncoderConfiguration} which is mapped to its
 * corresponding {@link VideoEncoderQualityModel}.
 */
/* package */ class EncoderConfigModel {
  /* package */ final VideoEncoderConfiguration config;
  /* package */ VideoEncoderQualityModel model;

  /* package */ EncoderConfigModel(
      VideoEncoderConfiguration config, VideoEncoderQualityModel model) {
    this.config = config;
    this.model = model;
  }

  @C.VideoCodecFlags
  int videoCodec() {
    return getVideoCodecFlagForMime(checkNotNull(config.format.sampleMimeType));
  }

  int maxBFrames() {
    return config.videoEncoderSettings.maxBFrames;
  }
}
