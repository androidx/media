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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.FileNotFoundException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link TransformerOutputResolver}. */
@RunWith(AndroidJUnit4.class)
public final class TransformerOutputResolverTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void insertMediaStoreEntry_validValues_returnsItemUri() throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_video.mp4");
    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

    Uri itemUri =
        TransformerOutputResolver.insertMediaStoreEntry(
            context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

    assertThat(itemUri).isNotNull();
    assertThat(itemUri.toString())
        .startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString());
  }

  @Test
  public void clearMediaStorePendingFlag_doesNotThrow() throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_clear.mp4");
    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");

    Uri itemUri =
        TransformerOutputResolver.insertMediaStoreEntry(
            context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

    TransformerOutputResolver.clearMediaStorePendingFlag(context, itemUri);
  }

  @Test
  public void deleteMediaStoreEntry_afterInsert_doesNotThrow() throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_delete.mp4");
    values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
    Uri itemUri =
        TransformerOutputResolver.insertMediaStoreEntry(
            context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

    TransformerOutputResolver.deleteMediaStoreEntry(context, itemUri);
  }

  @Test
  public void deleteMediaStoreEntry_invalidUri_doesNotThrow() {
    Uri invalidUri = Uri.parse("content://media/external/video/media/99999999");

    // Deletion is best effort, so that a cleanup failure cannot mask the error that triggered it.
    TransformerOutputResolver.deleteMediaStoreEntry(context, invalidUri);
  }

  @Test
  public void openFileDescriptor_invalidUri_throwsFileNotFoundException() {
    Uri invalidUri = Uri.parse("content://media/external/video/media/99999999");

    assertThrows(
        FileNotFoundException.class,
        () -> TransformerOutputResolver.openFileDescriptor(context, invalidUri));
  }
}
