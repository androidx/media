/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.common.util;

import androidx.annotation.RequiresApi;
import androidx.media3.common.MediaLibraryInfo;

/** Calls through to {@link android.os.Trace} methods on supported API levels. */
@UnstableApi
public final class TraceUtil {

  private TraceUtil() {}

  /**
   * Writes a trace message to indicate that a given section of code has begun.
   *
   * @see android.os.Trace#beginSection(String)
   * @param sectionName The name of the code section to appear in the trace. This may be at most 127
   *     Unicode code units long.
   */
  public static void beginSection(String sectionName) {
    if (MediaLibraryInfo.TRACE_ENABLED) {
      beginSectionV18(sectionName);
    }
  }

  /**
   * Writes a trace message to indicate that a given section of code has ended.
   *
   * @see android.os.Trace#endSection()
   */
  public static void endSection() {
    if (MediaLibraryInfo.TRACE_ENABLED) {
      endSectionV18();
    }
  }

  @RequiresApi(18)
  private static void beginSectionV18(String sectionName) {
    android.os.Trace.beginSection(sectionName);
  }

  @RequiresApi(18)
  private static void endSectionV18() {
    android.os.Trace.endSection();
  }
}
