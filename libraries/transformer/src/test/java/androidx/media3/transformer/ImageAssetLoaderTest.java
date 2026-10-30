/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runLooperUntil;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.DataSourceBitmapLoader;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link ImageAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class ImageAssetLoaderTest {

  @Test
  public void imageAssetLoader_callsListenerCallbacksInRightOrder() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    AssetLoader assetLoader = getAssetLoader(listener, "asset:///media/png/media3test.png");

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoOutputFormat != null || listener.exceptionRef.get() != null;
        });

    assertThat(listener.exceptionRef.get()).isNull();
  }

  @Test
  public void imageAssetLoader_onUnsupportedMimeType_callsListener() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    AssetLoader assetLoader = getAssetLoader(listener, "asset:///media3test.gif");

    assetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.exceptionRef.get() != null;
        });
    ParserException parserException =
        (ParserException) checkNotNull(listener.exceptionRef.get()).getCause();

    assertThat(parserException)
        .hasMessageThat()
        .contains("Attempted to load a Bitmap from unsupported MIME type: image/gif");
    assertThat(parserException.contentIsMalformed).isFalse();
    assertThat(parserException.dataType).isEqualTo(C.DATA_TYPE_MEDIA);
  }

  private static AssetLoader getAssetLoader(AssetLoader.Listener listener, String uri) {
    Context context = ApplicationProvider.getApplicationContext();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                new MediaItem.Builder().setUri(uri).setImageDurationMs(1000).build())
            .setFrameRate(30)
            .build();
    return new ImageAssetLoader.Factory(
            context,
            new DataSourceBitmapLoader.Builder(ApplicationProvider.getApplicationContext()).build())
        .createAssetLoader(
            editedMediaItem,
            Looper.myLooper(),
            listener,
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false));
  }
}
