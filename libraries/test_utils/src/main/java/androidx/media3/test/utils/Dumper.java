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
package androidx.media3.test.utils;

import static androidx.media3.common.util.Assertions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.Locale;

/** Helper utility to dump field values. */
@UnstableApi
public final class Dumper {

  /** Provides custom dump method. */
  public interface Dumpable {
    /**
     * Dumps the fields of the object using the {@code dumper}.
     *
     * @param dumper The {@link Dumper} to be used to dump fields.
     */
    void dump(Dumper dumper);
  }

  private static final int INDENT_SIZE_IN_SPACES = 2;

  private final StringBuilder sb;
  private int indent;

  public Dumper() {
    sb = new StringBuilder();
  }

  @CanIgnoreReturnValue
  public Dumper add(String field, @Nullable Object value) {
    checkNotNull(value);
    String[] lines = Util.split(value.toString(), "\n");
    addLine(field + " = " + lines[0]);
    int fieldValueAdditionalIndent = field.length() + 3;
    indent += fieldValueAdditionalIndent;
    for (int i = 1; i < lines.length; i++) {
      addLine(lines[i]);
    }
    indent -= fieldValueAdditionalIndent;
    return this;
  }

  @CanIgnoreReturnValue
  public Dumper add(Dumpable object) {
    object.dump(this);
    return this;
  }

  @CanIgnoreReturnValue
  public Dumper add(String field, @Nullable byte[] value) {
    String string =
        String.format(
            Locale.US,
            "%s = length %d, hash %X",
            field,
            value == null ? 0 : value.length,
            Arrays.hashCode(value));
    return addLine(string);
  }

  @CanIgnoreReturnValue
  public Dumper addTime(String field, long time) {
    return add(field, time == C.TIME_UNSET ? "UNSET TIME" : time);
  }

  @CanIgnoreReturnValue
  public Dumper startBlock(String name) {
    addLine(name + ":");
    indent += INDENT_SIZE_IN_SPACES;
    return this;
  }

  @CanIgnoreReturnValue
  public Dumper endBlock() {
    indent -= INDENT_SIZE_IN_SPACES;
    return this;
  }

  @Override
  public String toString() {
    return sb.toString();
  }

  @CanIgnoreReturnValue
  private Dumper addLine(String string) {
    for (int i = 0; i < indent; i++) {
      sb.append(' ');
    }
    sb.append(string);
    sb.append('\n');
    return this;
  }
}
