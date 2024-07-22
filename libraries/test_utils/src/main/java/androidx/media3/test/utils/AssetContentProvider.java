/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** A {@link ContentProvider} for reading asset data. */
@UnstableApi
public final class AssetContentProvider extends ContentProvider
    implements ContentProvider.PipeDataWriter<Object> {

  private static final String AUTHORITY = "androidx.media3.test.utils.AssetContentProvider";
  private static final String PARAM_PIPE_MODE = "pipe-mode";

  public static Uri buildUri(String filePath, boolean pipeMode) {
    Uri.Builder builder =
        new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .path(filePath);
    if (pipeMode) {
      builder.appendQueryParameter(PARAM_PIPE_MODE, "1");
    }
    return builder.build();
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(
      Uri uri,
      @Nullable String[] projection,
      @Nullable String selection,
      @Nullable String[] selectionArgs,
      @Nullable String sortOrder) {
    throw new UnsupportedOperationException();
  }

  @Override
  @Nullable
  public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
    if (uri.getPath() == null) {
      return null;
    }
    try {
      String fileName = getFileName(uri);
      boolean pipeMode = uri.getQueryParameter(PARAM_PIPE_MODE) != null;
      if (pipeMode) {
        ParcelFileDescriptor fileDescriptor =
            openPipeHelper(
                uri,
                /* mimeType= */ "application/octet-stream",
                /* opts= */ null,
                /* args= */ null,
                /* func= */ this);
        return new AssetFileDescriptor(
            fileDescriptor, /* startOffset= */ 0, AssetFileDescriptor.UNKNOWN_LENGTH);
      } else {
        return checkNotNull(getContext()).getAssets().openFd(fileName);
      }
    } catch (IOException e) {
      FileNotFoundException exception = new FileNotFoundException(checkNotNull(e.getMessage()));
      exception.initCause(e);
      throw exception;
    }
  }

  @Override
  public String getType(Uri uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(
      Uri uri,
      @Nullable ContentValues values,
      @Nullable String selection,
      @Nullable String[] selectionArgs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void writeDataToPipe(
      ParcelFileDescriptor output,
      Uri uri,
      String mimeType,
      @Nullable Bundle opts,
      @Nullable Object args) {
    try (FileOutputStream outputStream = new FileOutputStream(output.getFileDescriptor())) {
      byte[] data = TestUtil.getByteArray(checkNotNull(getContext()), getFileName(uri));
      outputStream.write(data);
    } catch (IOException e) {
      if (isBrokenPipe(e)) {
        // Swallow the exception if it's caused by a broken pipe - this indicates the reader has
        // closed the pipe and is therefore no longer interested in the data being written.
        // [See internal b/186728171].
        return;
      }
      throw new RuntimeException("Error writing to pipe", e);
    }
  }

  private static String getFileName(Uri uri) {
    return checkNotNull(uri.getPath()).replaceFirst("/", "");
  }

  private static boolean isBrokenPipe(IOException e) {
    return Util.SDK_INT >= 21
        && e.getCause() instanceof ErrnoException
        && ((ErrnoException) e.getCause()).errno == OsConstants.EPIPE;
  }
}
