/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.datasource;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.ParserException;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.concurrent.ExecutionException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/**
 * Tests for {@link DataSourceBitmapLoader}.
 *
 * <p>This test needs to run as an androidTest because robolectric's BitmapFactory is not fully
 * functional.
 */
// The image data fails to decode on API 23 (b/429101350) so the only tests which pass are the
// "not found" or other failure ones, which don't seem worth running on their own.
@SdkSuppress(minSdkVersion = 24)
@RunWith(AndroidJUnit4.class)
public class DataSourceBitmapLoaderTest {

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private static final String TEST_IMAGE_FOLDER = "media/jpeg/";
  private static final String TEST_IMAGE_PATH =
      TEST_IMAGE_FOLDER + "non-motion-photo-shortened-no-exif.jpg";

  private DataSource.Factory dataSourceFactory;
  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    dataSourceFactory = new DefaultDataSource.Factory(context);
  }

  @Test
  public void decodeBitmap_withValidData_loadsCorrectData() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = bitmapLoader.decodeBitmap(imageData).get();

    assertThat(
            bitmap.sameAs(
                BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
        .isTrue();
  }

  @Test
  public void decodeBitmap_withExifRotation_loadsCorrectData() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    byte[] imageData =
        TestUtil.getByteArray(context, TEST_IMAGE_FOLDER + "non-motion-photo-shortened.jpg");
    Bitmap bitmapWithoutRotation =
        BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length);
    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(/* degrees= */ 90);
    Bitmap expectedBitmap =
        Bitmap.createBitmap(
            bitmapWithoutRotation,
            /* x= */ 0,
            /* y= */ 0,
            bitmapWithoutRotation.getWidth(),
            bitmapWithoutRotation.getHeight(),
            rotationMatrix,
            /* filter= */ false);

    Bitmap actualBitmap = bitmapLoader.decodeBitmap(imageData).get();

    assertThat(actualBitmap.sameAs(expectedBitmap)).isTrue();
  }

  @Test
  public void decodeBitmap_withInvalidData_throws() {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();

    ListenableFuture<Bitmap> future = bitmapLoader.decodeBitmap(new byte[0]);

    assertException(
        future::get, ParserException.class, /* messagePart= */ "Could not decode image data");
  }

  @Test
  public void loadBitmap_withHttpUri_loadsCorrectData() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    Bitmap bitmap;
    try (MockWebServer mockWebServer = new MockWebServer()) {
      mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));

      bitmap = bitmapLoader.loadBitmap(Uri.parse(mockWebServer.url("test_path").toString())).get();
    }

    assertThat(
            bitmap.sameAs(
                BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
        .isTrue();
  }

  @Test
  public void loadBitmap_httpUriAndServerError_throws() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    ListenableFuture<Bitmap> future;
    try (MockWebServer mockWebServer = new MockWebServer()) {
      mockWebServer.enqueue(new MockResponse().setResponseCode(404));

      future = bitmapLoader.loadBitmap(Uri.parse(mockWebServer.url("test_path").toString()));
    }

    assertException(
        future::get, HttpDataSource.InvalidResponseCodeException.class, /* messagePart= */ "404");
  }

  @Test
  public void loadBitmap_assetUri_loadsCorrectData() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = bitmapLoader.loadBitmap(Uri.parse("asset:///" + TEST_IMAGE_PATH)).get();

    assertThat(
            bitmap.sameAs(
                BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
        .isTrue();
  }

  @Test
  public void loadBitmap_assetUriWithAssetNotExisting_throws() {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();

    assertException(
        () -> bitmapLoader.loadBitmap(Uri.parse("asset:///not_valid/path/image.bmp")).get(),
        AssetDataSource.AssetDataSourceException.class,
        /* messagePart= */ "");
  }

  @Test
  public void loadBitmap_withFileUri_loadsCorrectData() throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    File file = tempFolder.newFile();
    Files.write(imageData, file);
    Uri uri = Uri.fromFile(file);
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();

    Bitmap bitmap = bitmapLoader.loadBitmap(uri).get();

    assertThat(
            bitmap.sameAs(
                BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
        .isTrue();
  }

  @Test
  public void loadBitmap_withFileUriAndOptions_loadsDataWithRespectToOptions() throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    File file = tempFolder.newFile();
    Files.write(imageData, file);
    Uri uri = Uri.fromFile(file);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inMutable = true;
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .setBitmapFactoryOptions(options)
            .build();

    Bitmap bitmap = bitmapLoader.loadBitmap(uri).get();

    assertThat(bitmap.isMutable()).isTrue();
  }

  @Test
  public void loadBitmap_withFileUriAndMaxOutputDimension_loadsDataWithSmallerSize()
      throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    File file = tempFolder.newFile();
    Files.write(imageData, file);
    Uri uri = Uri.fromFile(file);
    int maximumOutputDimension = 2000;
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .setMaximumOutputDimension(maximumOutputDimension)
            .build();

    Bitmap bitmap = bitmapLoader.loadBitmap(uri).get();

    assertThat(bitmap.getWidth()).isAtMost(maximumOutputDimension);
    assertThat(bitmap.getHeight()).isAtMost(maximumOutputDimension);
  }

  @Test
  public void loadBitmap_withFileUriAndMaxOutputDimension_loadsDataWithSmallerSize2()
      throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    File file = tempFolder.newFile();
    Files.write(imageData, file);
    Uri uri = Uri.fromFile(file);
    int maximumOutputDimension = 720;

    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setMaximumOutputDimension(maximumOutputDimension)
            .build();

    Bitmap bitmap = bitmapLoader.loadBitmap(uri).get();

    assertThat(bitmap.getWidth()).isAtMost(maximumOutputDimension);
    assertThat(bitmap.getHeight()).isAtMost(maximumOutputDimension);
  }

  @Test
  public void loadBitmap_fileUriWithFileNotExisting_throws() {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();

    assertException(
        () -> bitmapLoader.loadBitmap(Uri.parse("file:///not_valid/path/image.bmp")).get(),
        FileDataSource.FileDataSourceException.class,
        /* messagePart= */ "No such file or directory");
  }

  @Test
  public void loadBitmapFromMetadata_withArtworkDataAndArtworkUriSet_decodeFromArtworkData()
      throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    try (MockWebServer mockWebServer = new MockWebServer()) {
      Uri uri = Uri.parse(mockWebServer.url("test_path").toString());
      MediaMetadata metadata =
          new MediaMetadata.Builder()
              .setArtworkData(imageData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
              .setArtworkUri(uri)
              .build();

      Bitmap bitmap = bitmapLoader.loadBitmapFromMetadata(metadata).get();

      assertThat(
              bitmap.sameAs(
                  BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
          .isTrue();
      assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }
  }

  @Test
  public void loadBitmapFromMetadata_withArtworkUriSet_loadFromArtworkUri() throws Exception {
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();
    try (MockWebServer mockWebServer = new MockWebServer()) {
      mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
      Uri uri = Uri.parse(mockWebServer.url("test_path").toString());
      MediaMetadata metadata = new MediaMetadata.Builder().setArtworkUri(uri).build();

      Bitmap bitmap = bitmapLoader.loadBitmapFromMetadata(metadata).get();

      assertThat(
              bitmap.sameAs(
                  BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
          .isTrue();
      assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
  }

  @Test
  public void loadBitmapFromMetadata_withArtworkDataAndArtworkUriUnset_returnNull() {
    MediaMetadata metadata = new MediaMetadata.Builder().build();
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context)
            .setExecutorService(newDirectExecutorService())
            .setDataSourceFactory(dataSourceFactory)
            .build();

    ListenableFuture<Bitmap> bitmapFuture = bitmapLoader.loadBitmapFromMetadata(metadata);

    assertThat(bitmapFuture).isNull();
  }

  @Test
  public void decodeBitmap_makeShared_returnsImmutableInstance() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context).setMakeShared(true).build();
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = bitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  @Test
  public void loadBitmap_makeShared_returnsImmutableInstance() throws Exception {
    DataSourceBitmapLoader bitmapLoader =
        new DataSourceBitmapLoader.Builder(context).setMakeShared(true).build();
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = bitmapLoader.loadBitmap(uri).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  private static void assertException(
      ThrowingRunnable runnable, Class<? extends Exception> clazz, String messagePart) {
    ExecutionException executionException = assertThrows(ExecutionException.class, runnable);
    assertThat(executionException).hasCauseThat().isInstanceOf(clazz);
    assertThat(executionException).hasMessageThat().contains(messagePart);
  }
}
