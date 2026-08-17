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
package androidx.media3.session;

import static com.google.common.truth.Truth.assertThat;

import android.media.MediaDescription;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import androidx.media3.session.legacy.MediaDescriptionCompat;
import androidx.media3.test.utils.MalformedParcelable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaDescriptionCompat}. */
@RunWith(AndroidJUnit4.class)
public final class MediaDescriptionCompatTest {

  @Test
  public void fromMediaDescription_withMalformedExtras_doesNotCrash() {
    Bundle extras = new Bundle();
    extras.putParcelable("malformed_key", new MalformedParcelable());
    // Also put a valid media URI to trigger the path that reads it
    Uri mediaUri = Uri.parse("content://media/external/audio/media/1");
    extras.putParcelable(MediaDescriptionCompat.DESCRIPTION_KEY_MEDIA_URI, mediaUri);

    MediaDescription platformDescription =
        new MediaDescription.Builder()
            .setMediaId("test_id")
            .setTitle("test_title")
            .setExtras(extras)
            .build();

    // Parcel and unparcel the platform description to force unparcelling error of extras
    Parcel parcel = Parcel.obtain();
    try {
      platformDescription.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      MediaDescription parceledDescription = MediaDescription.CREATOR.createFromParcel(parcel);

      // This should not crash.
      MediaDescriptionCompat descriptionCompat =
          MediaDescriptionCompat.fromMediaDescription(parceledDescription);

      assertThat(descriptionCompat).isNotNull();
      assertThat(descriptionCompat.getMediaId()).isEqualTo("test_id");
      assertThat(descriptionCompat.getTitle().toString()).isEqualTo("test_title");
      // The extras might be null or empty because they failed to unparcel, but we shouldn't crash.
    } finally {
      parcel.recycle();
    }
  }
}
