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
import static com.google.common.truth.Truth.assertThat;

import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link TextureAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class TextureAssetLoaderTest {

  @Test
  public void textureAssetLoader_callsListenerCallbacksInRightOrder() throws Exception {
    FakeAssetLoaderListener listener = new FakeAssetLoaderListener();
    TextureAssetLoader assetLoader = getAssetLoader(listener);

    runTextureAssetLoader(assetLoader);
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoOutputFormat != null || listener.exceptionRef.get() != null;
        });

    assertThat(listener.exceptionRef.get()).isNull();
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void runTextureAssetLoader(TextureAssetLoader assetLoader) {
    assetLoader.start();

    Executors.newSingleThreadExecutor()
        .submit(
            () -> {
              assetLoader.queueInputTexture(/* texId= */ 0, /* presentationTimeUs= */ 0);
              assetLoader.signalEndOfVideoInput();
            });
  }

  private static TextureAssetLoader getAssetLoader(AssetLoader.Listener listener) {
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(new MediaItem.Builder().build())
            .setDurationUs(C.MICROS_PER_SECOND)
            .build();
    Format format = new Format.Builder().setWidth(10).setHeight(10).build();
    OnInputFrameProcessedListener frameProcessedListener = (unused, unused2) -> {};
    return new TextureAssetLoader(editedMediaItem, listener, format, frameProcessedListener);
  }
}
