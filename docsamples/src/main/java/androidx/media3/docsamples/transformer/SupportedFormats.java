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
package androidx.media3.docsamples.transformer;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Transformer;

/** Snippets for supported-formats.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class SupportedFormats {

  private SupportedFormats() {}

  @OptIn(markerClass = UnstableApi.class)
  public static void transformerSupportedFormatsFlattenSlowMotion(
      MediaItem inputMediaItem,
      Context context,
      Transformer.Listener transformerListener,
      String outputPath) {
    // [START flatten_slow_motion]
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(inputMediaItem).setFlattenForSlowMotion(true).build();
    Transformer transformer =
        new Transformer.Builder(context).addListener(transformerListener).build();
    transformer.start(editedMediaItem, outputPath);
    // [END flatten_slow_motion]
  }
}
