/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.decoder.ass;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;

public class AssRenderResult {
  @Nullable public final Bitmap bitmap;
  public final boolean changedSinceLastCall;

  public AssRenderResult(@Nullable Bitmap bitmap, boolean changedSinceLastCall) {
    this.bitmap = bitmap;
    this.changedSinceLastCall = changedSinceLastCall;
  }
}
