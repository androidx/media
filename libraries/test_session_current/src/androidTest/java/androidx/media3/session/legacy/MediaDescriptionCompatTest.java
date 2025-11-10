/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.session.legacy;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.test.session.common.TestUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaDescriptionCompat}. */
@RunWith(AndroidJUnit4.class)
public class MediaDescriptionCompatTest {

  @Test
  public void roundTripViaFrameworkObject_returnsEqualMediaUriAndExtras() {
    Uri mediaUri = Uri.parse("androidx://media/uri");
    MediaDescriptionCompat originalDescription =
        new MediaDescriptionCompat.Builder()
            .setMediaUri(mediaUri)
            .setExtras(createExtras())
            .build();

    MediaDescriptionCompat restoredDescription =
        MediaDescriptionCompat.fromMediaDescription(originalDescription.getMediaDescription());

    // Test second round-trip as MediaDescriptionCompat keeps an internal reference to a previously
    // restored platform instance.
    MediaDescriptionCompat restoredDescription2 =
        MediaDescriptionCompat.fromMediaDescription(restoredDescription.getMediaDescription());

    assertEquals(mediaUri, restoredDescription.getMediaUri());
    TestUtils.equals(createExtras(), restoredDescription.getExtras());
    assertEquals(mediaUri, restoredDescription2.getMediaUri());
    TestUtils.equals(createExtras(), restoredDescription2.getExtras());
  }

  @Test
  public void getMediaDescription_withMediaUri_doesNotTouchExtras() {
    MediaDescriptionCompat originalDescription =
        new MediaDescriptionCompat.Builder()
            .setMediaUri(Uri.EMPTY)
            .setExtras(createExtras())
            .build();
    originalDescription.getMediaDescription();
    TestUtils.equals(createExtras(), originalDescription.getExtras());
  }

  @Test
  public void getIconBitmapData_returnsCompressedBitmap() {
    Bitmap testBitmap =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    MediaDescriptionCompat description =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap).build();

    byte[] data = description.getIconBitmapData();

    assertThat(data).isNotNull();
    assertThat(data.length).isGreaterThan(0);
  }

  @Test
  public void getIconBitmapData_calledTwice_returnsSameByteArrayInstance() {
    Bitmap testBitmap =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    MediaDescriptionCompat description =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap).build();

    byte[] data1 = description.getIconBitmapData();
    byte[] data2 = description.getIconBitmapData();

    assertThat(data1).isSameInstanceAs(data2);
  }

  @Test
  public void preserveIconBitmapData_withSameBitmap_preservesIconBitmapData() {
    Bitmap testBitmap1 =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    Bitmap testBitmap2 = Bitmap.createBitmap(testBitmap1);
    MediaDescriptionCompat description1 =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap1).build();
    MediaDescriptionCompat description2 =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap2).build();
    byte[] data1 = description1.getIconBitmapData();

    description2.preserveIconBitmapData(description1);
    byte[] data2 = description2.getIconBitmapData();

    assertThat(data2).isSameInstanceAs(data1);
  }

  @Test
  public void preserveIconBitmapData_withDifferentBitmap_doesNotPreserveIconBitmapData() {
    Bitmap testBitmap1 =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    Bitmap testBitmap2 =
        Bitmap.createBitmap(/* width= */ 5, /* height= */ 5, Bitmap.Config.ARGB_8888);
    MediaDescriptionCompat description1 =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap1).build();
    MediaDescriptionCompat description2 =
        new MediaDescriptionCompat.Builder().setIconBitmap(testBitmap2).build();
    byte[] data1 = description1.getIconBitmapData();

    description2.preserveIconBitmapData(description1);
    byte[] data2 = description2.getIconBitmapData();

    assertThat(data2).isNotSameInstanceAs(data1);
  }

  private static Bundle createExtras() {
    Bundle extras = new Bundle();
    extras.putString("key1", "value1");
    extras.putString("key2", "value2");
    return extras;
  }
}
