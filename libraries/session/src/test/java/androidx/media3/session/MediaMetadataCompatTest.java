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

import static androidx.media3.test.utils.truth.SpannedSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.text.Html;
import android.text.Spanned;
import androidx.media3.session.legacy.MediaMetadataCompat;
import androidx.media3.session.legacy.RatingCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MediaMetadataCompatTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  private static final String TEST_ARTWORK_PATH = "media/jpeg/london.jpg";

  @Test
  public void convertToMediaMetadata_withSpanStyledString() {
    Spanned artist = Html.fromHtml("<em>a</em>rtist");
    MediaMetadataCompat mediaMetadataCompat =
        new MediaMetadataCompat.Builder()
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build();

    MediaMetadata mediaMetadata = mediaMetadataCompat.getMediaMetadata();

    assertThat((Spanned) mediaMetadata.getText(MediaMetadata.METADATA_KEY_ARTIST))
        .hasItalicSpanBetween(0, 1);
  }

  @Test
  public void convertToMediaMetadata_withBitmap_keepSameBitmapInstance() {
    Bitmap testArtworkBitmap = loadBitmap(TEST_ARTWORK_PATH);
    assertThat(testArtworkBitmap).isNotNull();
    MediaMetadataCompat testMediaMetadataCompat =
        new MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 1000)
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, "artist")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "title")
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testArtworkBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, testArtworkBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, testArtworkBitmap)
            .putRating(MediaMetadataCompat.METADATA_KEY_RATING, RatingCompat.newHeartRating(true))
            .build();

    MediaMetadata mediaMetadata = testMediaMetadataCompat.getMediaMetadata();

    // Verify that the long/text/string/rating values are set correctly.
    assertThat(mediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).isEqualTo(1000);
    assertThat(mediaMetadata.getText(MediaMetadata.METADATA_KEY_ARTIST).toString())
        .isEqualTo("artist");
    assertThat(mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE)).isEqualTo("title");
    assertThat(mediaMetadata.getRating(MediaMetadata.METADATA_KEY_RATING).hasHeart()).isTrue();

    // Verify that the bitmap instances are the same as the original.
    Bitmap bitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
    assertThat(bitmap).isSameInstanceAs(testArtworkBitmap);

    bitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
    assertThat(bitmap).isSameInstanceAs(testArtworkBitmap);

    bitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
    assertThat(bitmap).isSameInstanceAs(testArtworkBitmap);
  }

  @Test
  public void getMostRelevantArtworkBitmapData_returnsCompressedBitmap() {
    Bitmap testBitmap =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    MediaMetadataCompat metadata =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap)
            .build();

    byte[] data = metadata.getMostRelevantArtworkBitmapData();

    assertThat(data).isNotNull();
    assertThat(data.length).isGreaterThan(0);
  }

  @Test
  public void getMostRelevantArtworkBitmapData_calledTwice_returnsSameByteArrayInstance() {
    Bitmap testBitmap =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    MediaMetadataCompat metadata =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap)
            .build();

    byte[] data1 = metadata.getMostRelevantArtworkBitmapData();
    byte[] data2 = metadata.getMostRelevantArtworkBitmapData();

    assertThat(data1).isSameInstanceAs(data2);
  }

  @Test
  public void preserveArtworkBitmapData_withSameBitmap_preservesArtworkBitmapData() {
    Bitmap testBitmap1 =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    Bitmap testBitmap2 = Bitmap.createBitmap(testBitmap1);
    MediaMetadataCompat metadata1 =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap1)
            .build();
    MediaMetadataCompat metadata2 =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap2)
            .build();
    byte[] data1 = metadata1.getMostRelevantArtworkBitmapData();

    metadata2.preserveArtworkBitmapData(metadata1);
    byte[] data2 = metadata2.getMostRelevantArtworkBitmapData();

    assertThat(data2).isSameInstanceAs(data1);
  }

  @Test
  public void preserveArtworkBitmapData_withDifferentBitmap_doesNotPreserveArtworkBitmapData() {
    Bitmap testBitmap1 =
        Bitmap.createBitmap(/* width= */ 10, /* height= */ 10, Bitmap.Config.ARGB_8888);
    Bitmap testBitmap2 =
        Bitmap.createBitmap(/* width= */ 5, /* height= */ 5, Bitmap.Config.ARGB_8888);
    MediaMetadataCompat metadata1 =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap1)
            .build();
    MediaMetadataCompat metadata2 =
        new MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, testBitmap2)
            .build();
    byte[] data1 = metadata1.getMostRelevantArtworkBitmapData();

    metadata2.preserveArtworkBitmapData(metadata1);
    byte[] data2 = metadata2.getMostRelevantArtworkBitmapData();

    assertThat(data2).isNotSameInstanceAs(data1);
  }

  private Bitmap loadBitmap(String path) {
    try {
      return BitmapFactory.decodeStream(context.getResources().getAssets().open(path));
    } catch (IOException e) {
      fail(e.getMessage());
    }
    return null;
  }
}
