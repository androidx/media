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
package androidx.media3.test.utils;

import static java.util.Objects.requireNonNull;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.media3.common.util.UnstableApi;

/** A {@link Parcelable} that throws a {@link RuntimeException} during deserialization. */
@UnstableApi
public final class MalformedParcelable implements Parcelable {

  public MalformedParcelable() {}

  private MalformedParcelable(Parcel in) {
    requireNonNull(in);
    throw new RuntimeException("Malformed");
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(1);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Creator<MalformedParcelable> CREATOR =
      new Creator<MalformedParcelable>() {
        @Override
        public MalformedParcelable createFromParcel(Parcel in) {
          return new MalformedParcelable(in);
        }

        @Override
        public MalformedParcelable[] newArray(int size) {
          return new MalformedParcelable[size];
        }
      };
}
