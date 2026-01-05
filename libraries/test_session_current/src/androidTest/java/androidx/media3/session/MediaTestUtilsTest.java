/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.session;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link MediaTestUtils}. */
@RunWith(AndroidJUnit4.class)
public class MediaTestUtilsTest {

  @Test
  public void createInvalidBundle_afterWritingToParcel_isDetectedAsInvalid() {
    Bundle invalid = MediaTestUtils.createInvalidBundle();
    Parcel parcel = Parcel.obtain();
    Bundle restoredBundle;

    try {
      parcel.writeBundle(invalid);
      parcel.setDataPosition(0);
      restoredBundle = parcel.readBundle();
    } finally {
      parcel.recycle();
    }

    assertThat(Util.convertToNullIfInvalid(restoredBundle)).isNull();
  }
}
