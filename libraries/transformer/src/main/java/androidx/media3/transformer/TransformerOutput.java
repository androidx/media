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
package androidx.media3.transformer;

import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Represents the output destination for a Transformer export. */
@UnstableApi
public final class TransformerOutput {

  /** Indicates the output target type. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({TYPE_FILE_PATH})
  /* package */ @interface OutputType {}

  /* package */ static final int TYPE_FILE_PATH = 1;

  /* package */ final @OutputType int outputType;
  /* package */ final String path;

  /**
   * Creates a {@link TransformerOutput} targeting a local file path.
   *
   * @param path The local file path.
   * @return A {@link TransformerOutput} instance.
   */
  public static TransformerOutput create(String path) {
    return new TransformerOutput(TYPE_FILE_PATH, path);
  }

  private TransformerOutput(@OutputType int outputType, String path) {
    this.outputType = outputType;
    this.path = path;
  }
}
