/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ExperimentalApi;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An implementation of {@link SurfaceHolder} which is backed by an {@link ImageReader}. */
@ExperimentalApi // TODO: b/498176910 - Remove once FrameWriter is production ready.
public final class ImageReaderSurfaceHolder implements SurfaceHolder, AutoCloseable {

  @Nullable public ImageReader imageReader;

  private final List<Callback> callbacks = new CopyOnWriteArrayList<>();
  private final Handler handler;

  public ImageReaderSurfaceHolder(Handler handler) {
    this.handler = handler;
  }

  private int width = 1;
  private int height = 1;
  private int format = PixelFormat.RGBA_8888;

  @Override
  public void addCallback(Callback callback) {
    callbacks.add(callback);
  }

  @Override
  public void removeCallback(Callback callback) {
    callbacks.remove(callback);
  }

  @Override
  public boolean isCreating() {
    return false;
  }

  @Override
  public void setType(int type) {}

  @Override
  public void setFixedSize(int width, int height) {
    this.width = width;
    this.height = height;
    handler.post(this::triggerCallbacks);
  }

  @Override
  public void setSizeFromLayout() {}

  @Override
  public void setFormat(int format) {
    this.format = format;
    handler.post(this::triggerCallbacks);
  }

  @Override
  public void setKeepScreenOn(boolean screenOn) {}

  @Override
  public Canvas lockCanvas() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Canvas lockCanvas(Rect dirty) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void unlockCanvasAndPost(Canvas canvas) {}

  @Override
  public Rect getSurfaceFrame() {
    return new Rect(0, 0, width, height);
  }

  @Override
  public Surface getSurface() {
    if (imageReader == null) {
      imageReader =
          ImageReader.newInstance(
              width == 0 ? 1 : width, height == 0 ? 1 : height, format, /* maxImages= */ 2);
    }
    return imageReader.getSurface();
  }

  @Override
  public void close() {
    if (imageReader != null) {
      imageReader.close();
      imageReader = null;
    }
  }

  /** Drains the {@link ImageReader} so more frames can be queued to the {@link Surface}. */
  public void drainSurface() {
    if (imageReader == null) {
      return;
    }
    try (Image image = imageReader.acquireNextImage()) {
      assertThat(image).isNotNull();
    }
  }

  private void triggerCallbacks() {
    ImageReader reader = imageReader;
    if (reader != null && (reader.getWidth() != width || reader.getHeight() != height)) {
      reader.close();
      imageReader = null;
    }
    for (Callback callback : callbacks) {
      callback.surfaceChanged(/* holder= */ this, format, width, height);
    }
  }
}
