/*
 * Copyright (C) 2024 The Android Open Source Project
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
 *
 */
package androidx.media3.common.text;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.os.Bundle;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/**
 * A span representing a speaker.
 *
 * <p>More information on <a href="https://www.w3.org/TR/webvtt1/#webvtt-cue-voice-span">
 *   voice spans</a>.
 */
@UnstableApi
public final class VoiceSpan {

  /** The speaker name. */
  public final String speakerName;

  private static final String FIELD_NAME = Util.intToStringMaxRadix(0);

  public VoiceSpan(String speakerName) {
    this.speakerName = speakerName;
  }

  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putString(FIELD_NAME, speakerName);
    return bundle;
  }

  public static VoiceSpan fromBundle(Bundle bundle) {
    return new VoiceSpan(/* speakerName = */ checkNotNull(bundle.getString(FIELD_NAME)));
  }
}
