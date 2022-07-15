/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.smoothstreaming;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit test for creating SmoothStreaming media sources with the {@link DefaultMediaSourceFactory}.
 */
@RunWith(AndroidJUnit4.class)
public class DefaultMediaSourceFactoryTest {

  private static final String URI_MEDIA = "http://exoplayer.dev/video";

  @Test
  public void createMediaSource_withMimeType_smoothstreamingSource() {
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(URI_MEDIA).setMimeType(MimeTypes.APPLICATION_SS).build();
    MediaSource mediaSource = defaultMediaSourceFactory.createMediaSource(mediaItem);
    assertThat(mediaSource).isInstanceOf(SsMediaSource.class);
  }

  @Test
  public void createMediaSource_withTag_tagInSource() {
    Object tag = new Object();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(URI_MEDIA)
            .setMimeType(MimeTypes.APPLICATION_SS)
            .setTag(tag)
            .build();

    MediaSource mediaSource = defaultMediaSourceFactory.createMediaSource(mediaItem);

    assertThat(mediaSource.getMediaItem().localConfiguration.tag).isEqualTo(tag);
  }

  @Test
  public void createMediaSource_withIsmPath_smoothstreamingSource() {
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaItem mediaItem = new MediaItem.Builder().setUri(URI_MEDIA + "/file.ism").build();

    MediaSource mediaSource = defaultMediaSourceFactory.createMediaSource(mediaItem);

    assertThat(mediaSource).isInstanceOf(SsMediaSource.class);
  }

  @Test
  public void createMediaSource_withManifestPath_smoothstreamingSource() {
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());
    MediaItem mediaItem = new MediaItem.Builder().setUri(URI_MEDIA + ".ism/Manifest").build();

    MediaSource mediaSource = defaultMediaSourceFactory.createMediaSource(mediaItem);

    assertThat(mediaSource).isInstanceOf(SsMediaSource.class);
  }

  @Test
  public void getSupportedTypes_smoothstreamingModule_containsTypeSS() {
    int[] supportedTypes =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext())
            .getSupportedTypes();

    assertThat(supportedTypes).asList().containsExactly(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_SS);
  }

  @Test
  public void createMediaSource_withSetDataSourceFactory_usesDataSourceFactory() throws Exception {
    FakeDataSource fakeDataSource = new FakeDataSource();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext())
            .setDataSourceFactory(() -> fakeDataSource);

    prepareSsUrlAndWaitForPrepareError(defaultMediaSourceFactory);

    assertThat(fakeDataSource.getAndClearOpenedDataSpecs()).asList().isNotEmpty();
  }

  @Test
  public void
      createMediaSource_usingDefaultDataSourceFactoryAndSetDataSourceFactory_usesUpdatesDataSourceFactory()
          throws Exception {
    FakeDataSource fakeDataSource = new FakeDataSource();
    DefaultMediaSourceFactory defaultMediaSourceFactory =
        new DefaultMediaSourceFactory((Context) ApplicationProvider.getApplicationContext());

    // Use default DataSource.Factory first.
    prepareSsUrlAndWaitForPrepareError(defaultMediaSourceFactory);
    defaultMediaSourceFactory.setDataSourceFactory(() -> fakeDataSource);
    prepareSsUrlAndWaitForPrepareError(defaultMediaSourceFactory);

    assertThat(fakeDataSource.getAndClearOpenedDataSpecs()).asList().isNotEmpty();
  }

  private static void prepareSsUrlAndWaitForPrepareError(
      DefaultMediaSourceFactory defaultMediaSourceFactory) throws Exception {
    MediaSource mediaSource =
        defaultMediaSourceFactory.createMediaSource(MediaItem.fromUri(URI_MEDIA + "/file.ism"));
    getInstrumentation()
        .runOnMainSync(
            () ->
                mediaSource.prepareSource(
                    (source, timeline) -> {}, /* mediaTransferListener= */ null, PlayerId.UNSET));
    // We don't expect this to prepare successfully.
    RobolectricUtil.runMainLooperUntil(
        () -> {
          try {
            mediaSource.maybeThrowSourceInfoRefreshError();
            return false;
          } catch (IOException e) {
            return true;
          }
        });
  }
}
