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

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.ImmutableIntArray;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Tests for {@link SizeAvoidingBitmapLoader}. */
@RunWith(AndroidJUnit4.class)
public class SizeAvoidingBitmapLoaderTest {

  private static final String TEST_IMAGE_PATH = "media/jpeg/non-motion-photo-shortened.jpg";

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void overridesAllMethods() throws NoSuchMethodException {
    assertSubclassOverridesAllMethods(BitmapLoader.class, SizeAvoidingBitmapLoader.class);
  }

  @Test
  public void decodeBitmap_sizeBelowLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 199,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = sizeAvoidingBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(199);
  }

  @Test
  public void decodeBitmap_sizeAboveLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 201,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = sizeAvoidingBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(201);
  }

  @Test
  public void decodeBitmap_sizeAtLimit_changesSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 200,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);

    Bitmap bitmap = sizeAvoidingBitmapLoader.decodeBitmap(imageData).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isNotEqualTo(200);
  }

  @Test
  public void loadBitmap_sizeBelowLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 199,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(199);
  }

  @Test
  public void loadBitmap_sizeAboveLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 201,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(201);
  }

  @Test
  public void loadBitmap_sizeAtLimit_changesSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 200,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    Buffer responseBody = new Buffer().write(imageData);
    MockWebServer mockWebServer = new MockWebServer();
    mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(responseBody));
    Uri uri = Uri.parse(mockWebServer.url("test_path").toString());

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmap(uri).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isNotEqualTo(200);
  }

  @Test
  public void loadBitmapFromMetadata_sizeBelowLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 199,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    MediaMetadata metadata =
        new MediaMetadata.Builder()
            .setArtworkData(imageData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build();

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmapFromMetadata(metadata).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(199);
  }

  @Test
  public void loadBitmapFromMetadata_sizeAboveLimit_keepsSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 201,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    MediaMetadata metadata =
        new MediaMetadata.Builder()
            .setArtworkData(imageData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build();

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmapFromMetadata(metadata).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isEqualTo(201);
  }

  @Test
  public void loadBitmapFromMetadata_sizeAtLimit_changesSize() throws Exception {
    SizeAvoidingBitmapLoader sizeAvoidingBitmapLoader =
        new SizeAvoidingBitmapLoader(
            new SizeLimitedBitmapLoader(
                new DataSourceBitmapLoader.Builder(context).build(),
                /* maxBitmapSize= */ 200,
                /* makeShared= */ true),
            /* avoidSizes= */ ImmutableIntArray.of(200));
    byte[] imageData = TestUtil.getByteArray(context, TEST_IMAGE_PATH);
    MediaMetadata metadata =
        new MediaMetadata.Builder()
            .setArtworkData(imageData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build();

    Bitmap bitmap = sizeAvoidingBitmapLoader.loadBitmapFromMetadata(metadata).get(10, SECONDS);

    assertThat(bitmap.getHeight()).isNotEqualTo(200);
  }
}
