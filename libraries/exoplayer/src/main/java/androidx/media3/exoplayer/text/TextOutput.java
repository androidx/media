/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.text;

import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import java.util.List;
import javax.annotation.Nullable;

/** Receives text output. */
@UnstableApi
public interface TextOutput {

  /**
   * Called when there is a change in the {@link Cue Cues}.
   *
   * <p>Both {@link #onCues(List)} and {@link #onCues(CueGroup)} are called when there is a change
   * in the cues. You should only implement one or the other.
   *
   * @deprecated Use {@link #onCues(CueGroup)} instead.
   */
  @Deprecated
  default void onCues(List<Cue> cues) {}

  /**
   * Called when there is a change in the {@link CueGroup}.
   *
   * <p>Both {@link #onCues(List)} and {@link #onCues(CueGroup)} are called when there is a change
   * in the cues. You should only implement one or the other.
   */
  void onCues(CueGroup cueGroup);

  default void onSubtitleDecoderError(SubtitleDecoderErrorInfo subtitleDecoderErrorInfo) {}

  class SubtitleDecoderErrorInfo {
    @Nullable public final Format streamingFormat;
    public final Throwable throwable;

    public SubtitleDecoderErrorInfo(
        @Nullable final Format streamingFormat,
        final Throwable throwable) {
      this.streamingFormat = streamingFormat;
      this.throwable = throwable;
    }
  }
}
