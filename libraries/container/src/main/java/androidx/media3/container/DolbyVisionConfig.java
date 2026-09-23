/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.container;

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

/** Dolby Vision configuration data. */
@UnstableApi
public final class DolbyVisionConfig {

  /**
   * Parses Dolby Vision configuration data.
   *
   * @param data A {@link ParsableByteArray}, whose position is set to the start of the Dolby Vision
   *     configuration data to parse.
   * @return The {@link DolbyVisionConfig} corresponding to the configuration.
   */
  public static DolbyVisionConfig parse(ParsableByteArray data) {
    data.skipBytes(2); // dv_version_major, dv_version_minor
    int profileData = data.readUnsignedByte();
    int dvProfile = (profileData >> 1);
    int dvLevel = ((profileData & 0x1) << 5) | ((data.readUnsignedByte() >> 3) & 0x1F);
    @Nullable String codecsPrefix;
    if (dvProfile == 4 || dvProfile == 5 || dvProfile == 7 || dvProfile == 8) {
      codecsPrefix = "dvhe";
    } else if (dvProfile == 9) {
      codecsPrefix = "dvav";
    } else if (dvProfile == 10) {
      codecsPrefix = "dav1";
    } else if (dvProfile == 20) {
      codecsPrefix = "dvh1";
    } else {
      // Unrecognized profile. Leave codecs unset so that the track is still reported as Dolby
      // Vision, but with no codecs string: track/decoder selection then correctly treats it as
      // unsupported instead of silently falling back to the base codec's MIME type.
      // See MediaCodecInfo.isCodecProfileAndLevelSupported.
      codecsPrefix = null;
    }
    @Nullable
    String codecs =
        codecsPrefix == null
            ? null
            : codecsPrefix
                + (dvProfile < 10 ? ".0" : ".")
                + dvProfile
                + (dvLevel < 10 ? ".0" : ".")
                + dvLevel;
    return new DolbyVisionConfig(dvProfile, dvLevel, codecs);
  }

  /** The profile number. */
  public final int profile;

  /** The level number. */
  public final int level;

  /**
   * The RFC 6381 codecs string, or {@code null} if the profile isn't recognized and so a codecs
   * string can't be constructed.
   */
  @Nullable public final String codecs;

  private DolbyVisionConfig(int profile, int level, @Nullable String codecs) {
    this.profile = profile;
    this.level = level;
    this.codecs = codecs;
  }
}
