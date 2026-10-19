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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.muxer.Muxer;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Internal helper for resolving {@link TransformerOutput} options to low-level handles.
 *
 * <p>This class is not thread-safe. All instance methods must be called on the {@link Transformer}
 * application thread.
 */
@UnstableApi
/* package */ final class TransformerOutputResolver {

  private final Context context;
  private final TransformerOutput transformerOutput;
  @Nullable private Uri mediaStoreUri;

  public TransformerOutputResolver(Context context, TransformerOutput transformerOutput) {
    this.context = context;
    this.transformerOutput = transformerOutput;
  }

  /**
   * Creates a {@link MuxerWrapper} for the configured {@link TransformerOutput}.
   *
   * <p>For {@link TransformerOutput#TYPE_MEDIA_STORE}, inserts a new MediaStore entry on the first
   * call and reuses that entry (truncating its underlying file via {@code "rwt"} mode) on
   * subsequent calls within the same export operation. If opening the file descriptor fails, any
   * inserted MediaStore entry is deleted before the exception is propagated.
   */
  public MuxerWrapper createMuxerWrapper(
      Muxer.Factory muxerFactory,
      MuxerWrapper.Listener listener,
      @MuxerWrapper.MuxerMode int muxerMode,
      boolean dropSamplesBeforeFirstVideoSample,
      @Nullable Format appendVideoFormat)
      throws IOException {
    if (transformerOutput.outputType == TransformerOutput.TYPE_MEDIA_STORE) {
      try {
        if (mediaStoreUri == null) {
          mediaStoreUri =
              insertMediaStoreEntry(
                  context,
                  checkNotNull(transformerOutput.collectionUri),
                  checkNotNull(transformerOutput.contentValues));
        }
        ParcelFileDescriptor pfd = openFileDescriptor(context, mediaStoreUri);
        return new MuxerWrapper(
            pfd,
            muxerFactory,
            listener,
            muxerMode,
            dropSamplesBeforeFirstVideoSample,
            appendVideoFormat);
      } catch (IOException e) {
        // The entry may have been inserted before opening its file descriptor failed.
        deleteIfPresent();
        throw e;
      }
    }
    return new MuxerWrapper(
        checkNotNull(transformerOutput.path),
        muxerFactory,
        listener,
        muxerMode,
        dropSamplesBeforeFirstVideoSample,
        appendVideoFormat);
  }

  /**
   * Publishes the MediaStore entry created for this export, if any, by clearing its {@code
   * IS_PENDING} flag on API 29+ or requesting a media scan on API &lt; 29.
   */
  public void clearPendingFlag() {
    if (mediaStoreUri != null) {
      clearMediaStorePendingFlag(context, mediaStoreUri);
      // The entry is published, so it must not be deleted by a subsequent cancel().
      mediaStoreUri = null;
    }
  }

  /**
   * Deletes the MediaStore entry created for this export, if any, so that an export that did not
   * complete successfully does not leave an orphaned entry behind.
   */
  public void deleteIfPresent() {
    if (mediaStoreUri != null) {
      deleteMediaStoreEntry(context, mediaStoreUri);
      mediaStoreUri = null;
    }
  }

  /**
   * Opens a {@link ParcelFileDescriptor} with {@code "rwt"} mode for the given {@link Uri}.
   *
   * @param context The {@link Context}.
   * @param uri The {@link Uri} to open.
   * @return The opened {@link ParcelFileDescriptor}.
   * @throws IOException If the file descriptor could not be opened.
   */
  @SuppressWarnings("ContentResolverUri") // Open source Media3 component consuming Uri
  public static ParcelFileDescriptor openFileDescriptor(Context context, Uri uri)
      throws IOException {
    ContentResolver resolver = context.getContentResolver();
    ParcelFileDescriptor pfd;
    try {
      pfd = resolver.openFileDescriptor(uri, "rwt");
    } catch (RuntimeException e) {
      throw new IOException("Could not open file descriptor for URI: " + uri, e);
    }
    if (pfd == null) {
      throw new FileNotFoundException("Could not open file descriptor for URI: " + uri);
    }
    return pfd;
  }

  /**
   * Inserts a MediaStore entry and returns the inserted {@link Uri}.
   *
   * @param context The {@link Context}.
   * @param collectionUri The collection {@link Uri} to insert into.
   * @param contentValues The metadata values for the new entry.
   * @return The {@link Uri} of the inserted item.
   * @throws IOException If the insertion into MediaStore failed.
   */
  @SuppressWarnings("ContentResolverUri") // Open source Media3 component consuming MediaStore Uri
  public static Uri insertMediaStoreEntry(
      Context context, Uri collectionUri, ContentValues contentValues) throws IOException {
    ContentValues values = new ContentValues(contentValues);
    if (Build.VERSION.SDK_INT >= 29) {
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    }
    ContentResolver resolver = context.getContentResolver();
    Uri itemUri;
    try {
      itemUri = resolver.insert(collectionUri, values);
    } catch (RuntimeException e) {
      throw new IOException("Failed to insert MediaStore entry into " + collectionUri, e);
    }
    if (itemUri == null) {
      throw new IOException("Failed to insert MediaStore entry into " + collectionUri);
    }
    return itemUri;
  }

  /**
   * Clears the {@code IS_PENDING} flag on API 29+ or requests a media scan on API < 29 for the
   * specified MediaStore entry after successful writing.
   *
   * @param context The {@link Context}.
   * @param itemUri The {@link Uri} of the MediaStore item.
   */
  @SuppressWarnings("ContentResolverUri") // Open source Media3 component updating MediaStore Uri
  public static void clearMediaStorePendingFlag(Context context, Uri itemUri) {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.IS_PENDING, 0);
      context
          .getContentResolver()
          .update(itemUri, values, /* where= */ null, /* selectionArgs= */ null);
    } else {
      String filePath = getFilePathFromUri(context, itemUri);
      if (filePath != null) {
        MediaScannerConnection.scanFile(
            context, new String[] {filePath}, /* mimeTypes= */ null, /* callback= */ null);
      }
    }
  }

  /**
   * Deletes the specified MediaStore entry, removing both the database row and the underlying file.
   *
   * <p>This is the counterpart to {@link #insertMediaStoreEntry} and is used to clean up an entry
   * whose export did not complete successfully. Deletion is best effort: failures are ignored so
   * that they do not mask the error that triggered the cleanup.
   *
   * @param context The {@link Context}.
   * @param itemUri The {@link Uri} of the MediaStore item.
   */
  @SuppressWarnings("ContentResolverUri") // Open source Media3 component deleting MediaStore Uri
  public static void deleteMediaStoreEntry(Context context, Uri itemUri) {
    try {
      context.getContentResolver().delete(itemUri, /* where= */ null, /* selectionArgs= */ null);
    } catch (RuntimeException e) {
      // Deletion failure; on API 30+ the pending entry is later reclaimed by the system.
    }
  }

  @Nullable
  private static String getFilePathFromUri(Context context, Uri uri) {
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {MediaStore.MediaColumns.DATA},
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* sortOrder= */ null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
        return cursor.getString(columnIndex);
      }
    } catch (RuntimeException e) {
      // Query failure on legacy device; let system background media scanner discover the file.
    }
    return null;
  }
}
