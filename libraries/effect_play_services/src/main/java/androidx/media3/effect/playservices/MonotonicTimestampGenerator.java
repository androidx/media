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
package androidx.media3.effect.playservices;

/** Generator of strictly monotonically increasing synthetic timestamps in nanoseconds. */
/* package */ final class MonotonicTimestampGenerator {

  // 1 microsecond in nanoseconds so timestamps remain strictly increasing after conversion to
  // microseconds.
  private static final long TIMESTAMP_STEP_NS = 1_000L;

  private long nextTimestampNs;

  public long getNextTimestampNs() {
    long timestampNs = nextTimestampNs;
    nextTimestampNs += TIMESTAMP_STEP_NS;
    return timestampNs;
  }
}
