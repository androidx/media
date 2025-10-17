/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.test.utils.TestUtil.assertSubclassOverridesAllMethods;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.robolectric.annotation.GraphicsMode.Mode.NATIVE;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.GraphicsMode;

/** Tests for {@link SizeLimitedBitmapLoader}. */
@RunWith(AndroidJUnit4.class)
@GraphicsMode(value = NATIVE)
public class SizeLimitedBitmapLoaderTest {

  private static final String TEST_IMAGE_PATH = "media/jpeg/non-motion-photo-shortened.jpg";

  private static final String SECOND_TEST_IMAGE_PATH = "media/jpeg/ss-motion-photo-shortened.jpg";

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void overridesAllMethods() throws NoSuchMethodException {
    assertSubclassOverridesAllMethods(BitmapLoader.class, SizeLimitedBitmapLoader.class);
  }

  @Test
  public void decodeBitmapWithLimit() throws Exception {
    int limit = MediaSession.getBitmapDimensionLimit(context);
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ false);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Bitmap expectedBitmap = getExpectedBitmap(imageData, limit);

    Bitmap bitmap = sizeLimitedBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    assertThat(bitmap.sameAs(expectedBitmap)).isTrue();
  }

  @Test
  public void loadBitmapWithLimit() throws Exception {
    int limit = MediaSession.getBitmapDimensionLimit(context);
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ false);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());
    Bitmap expectedBitmap = getExpectedBitmap(imageData, limit);

    Bitmap bitmap = sizeLimitedBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    assertThat(bitmap.sameAs(expectedBitmap)).isTrue();
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void loadBitmapWithLimitWithDifferentUris() throws Exception {
    int limit = MediaSession.getBitmapDimensionLimit(context);
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ false);
    byte[] imageData1 = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    byte[] imageData2 = TestUtil.getByteArray(context, SECOND_TEST_IMAGE_PATH);
    Buffer responseBody1 = new Buffer().write(imageData1);
    Buffer responseBody2 = new Buffer().write(imageData2);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody1));
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody2));
    Uri uri1 = Uri.parse(mockWebServer.url("test_path_1").toString());
    Uri uri2 = Uri.parse(mockWebServer.url("test_path_2").toString());
    Bitmap expectedBitmap1 = getExpectedBitmap(imageData1, limit);
    Bitmap expectedBitmap2 = getExpectedBitmap(imageData2, limit);

    // First request.
    Bitmap bitmap = sizeLimitedBitmapLoader.loadBitmap(uri1).get(10, SECONDS);

    assertThat(bitmap.sameAs(expectedBitmap1)).isTrue();

    // Second request.
    bitmap = sizeLimitedBitmapLoader.loadBitmap(uri2).get(10, SECONDS);

    assertThat(bitmap.sameAs(expectedBitmap2)).isTrue();
    assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
  }

  @Test
  public void loadBitmapWithLimitWithInvalidUri() {
    int limit = MediaSession.getBitmapDimensionLimit(context);
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ false);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(404));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    ListenableFuture<Bitmap> future = sizeLimitedBitmapLoader.loadBitmap(uri);

    ExecutionException executionException =
        assertThrows(ExecutionException.class, () -> future.get(10, SECONDS));
    assertThat(executionException)
        .hasCauseThat()
        .isInstanceOf(HttpDataSource.InvalidResponseCodeException.class);
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void loadBitmapFromMetadata_forwardsCallToWrappedLoader() throws Exception {
    int limit = MediaSession.getBitmapDimensionLimit(context);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    LoadBitmapFromMetadataOnlyBitmapLoader testBitmapLoader =
        new LoadBitmapFromMetadataOnlyBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build());
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(testBitmapLoader, limit, /* makeShared= */ false);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());
    MediaMetadata metadata = new MediaMetadata.Builder().setArtworkUri(uri).build();
    Bitmap expectedBitmap = getExpectedBitmap(imageData, limit);

    Bitmap bitmap = sizeLimitedBitmapLoader.loadBitmapFromMetadata(metadata).get(10, SECONDS);

    assertThat(bitmap.sameAs(expectedBitmap)).isTrue();
  }

  @Test
  public void decodeBitmap_sizeUnderLimitAndMakeShared_returnsImmutableInstance() throws Exception {
    int limit = 10000;
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ true);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = sizeLimitedBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  @Test
  public void decodeBitmap_sizeOverLimitAndMakeShared_returnsImmutableInstance() throws Exception {
    int limit = 20;
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ true);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = sizeLimitedBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  @Test
  public void loadBitmap_sizeUnderLimitAndMakeShared_returnsImmutableInstance() throws Exception {
    int limit = 10000;
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ true);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = sizeLimitedBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  @Test
  public void loadBitmap_sizeOverLimitAndMakeShared_returnsImmutableInstance() throws Exception {
    int limit = 20;
    SizeLimitedBitmapLoader sizeLimitedBitmapLoader =
        new SizeLimitedBitmapLoader(
            new DataSourceBitmapLoader.Builder(context).build(), limit, /* makeShared= */ true);
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = sizeLimitedBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    // We can't assert the shared state directly, so using the fact that sharable Bitmaps are
    // immutable as a proxy.
    assertThat(bitmap.isMutable()).isFalse();
  }

  private static Bitmap apply90DegreeExifRotation(Bitmap bitmap) {
    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(/* degrees= */ 90);
    return Bitmap.createBitmap(
        bitmap,
        /* x= */ 0,
        /* y= */ 0,
        bitmap.getWidth(),
        bitmap.getHeight(),
        rotationMatrix,
        /* filter= */ false);
  }

  private static Bitmap getExpectedBitmap(byte[] imageData, int limit) {
    Bitmap rotatedBitmap =
        apply90DegreeExifRotation(
            BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length));

    float scale = (float) limit / Math.max(rotatedBitmap.getWidth(), rotatedBitmap.getHeight());
    int scaledWidth = (int) (rotatedBitmap.getWidth() * scale);
    int scaledHeight = (int) (rotatedBitmap.getHeight() * scale);
    return Bitmap.createScaledBitmap(rotatedBitmap, scaledWidth, scaledHeight, true/* filter= */ );
  }

  private static class LoadBitmapFromMetadataOnlyBitmapLoader implements BitmapLoader {
    private final BitmapLoader bitmapLoader;

    private LoadBitmapFromMetadataOnlyBitmapLoader(BitmapLoader bitmapLoader) {
      this.bitmapLoader = bitmapLoader;
    }

    @Override
    public boolean supportsMimeType(String mimeType) {
      return true;
    }

    @Override
    public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Bitmap> loadBitmapFromMetadata(MediaMetadata metadata) {
      return bitmapLoader.loadBitmapFromMetadata(metadata);
    }
  }
}
