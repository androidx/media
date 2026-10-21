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
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileNotFoundException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Unit tests for {@link TransformerOutputResolver}. */
@RunWith(AndroidJUnit4.class)
public final class TransformerOutputResolverTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

  @Test
  public void
      createMuxerWrapper_withFilePathOutput_createsMuxerWrapperAndIgnoresMediaStoreLifecycle()
          throws Exception {
    File outputFile = temporaryFolder.newFile("output.mp4");
    TransformerOutput output = TransformerOutput.create(outputFile.getPath());
    TransformerOutputResolver resolver = new TransformerOutputResolver(context, output);

    MuxerWrapper muxerWrapper =
        resolver.createMuxerWrapper(
            new InAppMp4Muxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    resolver.clearPendingFlag();
    resolver.deleteIfPresent();

    assertThat(outputFile.exists()).isTrue();
  }

  @Test
  public void
      createMuxerWrapper_withMediaStoreOutput_insertsPendingEntryAndReusesUriOnSubsequentCall()
          throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_video.mp4");
    TransformerOutput output =
        TransformerOutput.create(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    TransformerOutputResolver resolver = new TransformerOutputResolver(context, output);

    MuxerWrapper firstMuxerWrapper =
        resolver.createMuxerWrapper(
            new InAppMp4Muxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MuxerWrapper.MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ new Format.Builder().build());
    firstMuxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);
    MuxerWrapper secondMuxerWrapper =
        resolver.createMuxerWrapper(
            new InAppMp4Muxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    secondMuxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);

    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns.IS_PENDING},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null)) {
      assertThat(cursor).isNotNull();
      assertThat(cursor.getCount()).isEqualTo(1);
      assertThat(cursor.moveToFirst()).isTrue();
      assertThat(cursor.getInt(0)).isEqualTo(1);
    }
  }

  @Test
  public void
      clearPendingFlag_afterCreateMuxerWrapper_clearsPendingFlagAndPreventsSubsequentDelete()
          throws Exception {
    TransformerOutput output =
        TransformerOutput.create(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new ContentValues());
    TransformerOutputResolver resolver = new TransformerOutputResolver(context, output);
    MuxerWrapper muxerWrapper =
        resolver.createMuxerWrapper(
            new InAppMp4Muxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);

    resolver.clearPendingFlag();
    resolver.deleteIfPresent();

    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns.IS_PENDING},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null)) {
      assertThat(cursor).isNotNull();
      assertThat(cursor.getCount()).isEqualTo(1);
      assertThat(cursor.moveToFirst()).isTrue();
      assertThat(cursor.getInt(0)).isEqualTo(0);
    }
  }

  @Test
  public void deleteIfPresent_afterCreateMuxerWrapper_deletesMediaStoreEntryOnce()
      throws Exception {
    TransformerOutput output =
        TransformerOutput.create(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new ContentValues());
    TransformerOutputResolver resolver = new TransformerOutputResolver(context, output);
    MuxerWrapper muxerWrapper =
        resolver.createMuxerWrapper(
            new InAppMp4Muxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MuxerWrapper.MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);

    resolver.deleteIfPresent();
    resolver.deleteIfPresent();

    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null)) {
      assertThat(cursor).isNotNull();
      assertThat(cursor.getCount()).isEqualTo(0);
    }
  }

  @Test
  public void createMuxerWrapper_whenOpenFileDescriptorFails_deletesInsertedMediaStoreEntry()
      throws Exception {
    File blockedDirFile = new File(Environment.getExternalStorageDirectory(), "Movies/blocked_dir");
    blockedDirFile.getParentFile().mkdirs();
    assertThat(blockedDirFile.createNewFile()).isTrue();
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/blocked_dir");
    TransformerOutput output =
        TransformerOutput.create(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    TransformerOutputResolver resolver = new TransformerOutputResolver(context, output);

    assertThrows(
        FileNotFoundException.class,
        () ->
            resolver.createMuxerWrapper(
                new InAppMp4Muxer.Factory(),
                new NoOpMuxerListenerImpl(),
                MuxerWrapper.MUXER_MODE_DEFAULT,
                /* dropSamplesBeforeFirstVideoSample= */ false,
                /* appendVideoFormat= */ null));

    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.MediaColumns._ID},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null)) {
      assertThat(cursor).isNotNull();
      assertThat(cursor.getCount()).isEqualTo(0);
    }
  }

  private static final class NoOpMuxerListenerImpl implements MuxerWrapper.Listener {

    @Override
    public void onTrackEnded(
        @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {}

    @Override
    public void onSampleWrittenOrDropped() {}

    @Override
    public void onEnded(long approximateDurationMs) {}

    @Override
    public void onFileSizeBytesAvailable(long fileSizeBytes) {}

    @Override
    public void onError(ExportException exportException) {}
  }
}
