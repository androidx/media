/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.mediacodec;

import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoInfo;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A {@link MediaCodecAdapter} instance that forwards all calls to its delegate.
 *
 * <p>Subclasses can override methods as part of the <a
 * href="https://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 */
@UnstableApi
public class ForwardingMediaCodecAdapter implements MediaCodecAdapter {

  private final MediaCodecAdapter delegate;

  public ForwardingMediaCodecAdapter(MediaCodecAdapter delegate) {
    this.delegate = delegate;
  }

  @Override
  public int dequeueInputBufferIndex() {
    return delegate.dequeueInputBufferIndex();
  }

  @Override
  public int dequeueOutputBufferIndex(BufferInfo bufferInfo) {
    return delegate.dequeueOutputBufferIndex(bufferInfo);
  }

  @Override
  public MediaFormat getOutputFormat() {
    return delegate.getOutputFormat();
  }

  @Nullable
  @Override
  public ByteBuffer getInputBuffer(int index) {
    return delegate.getInputBuffer(index);
  }

  @Override
  public void useInputBuffer(Runnable runnable) {
    delegate.useInputBuffer(runnable);
  }

  @Nullable
  @Override
  public ByteBuffer getOutputBuffer(int index) {
    return delegate.getOutputBuffer(index);
  }

  @Override
  public void queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
  }

  @Override
  public void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
    delegate.queueSecureInputBuffer(index, offset, info, presentationTimeUs, flags);
  }

  @Override
  public void releaseOutputBuffer(int index, boolean render) {
    delegate.releaseOutputBuffer(index, render);
  }

  @Override
  public void releaseOutputBuffer(int index, long renderTimeStampNs) {
    delegate.releaseOutputBuffer(index, renderTimeStampNs);
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void release() {
    delegate.release();
  }

  @Override
  public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    delegate.setOnFrameRenderedListener(listener, handler);
  }

  @Override
  public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
    return delegate.registerOnBufferAvailableListener(listener);
  }

  @Override
  public void setOutputSurface(Surface surface) {
    delegate.setOutputSurface(surface);
  }

  @RequiresApi(35)
  @Override
  public void detachOutputSurface() {
    delegate.detachOutputSurface();
  }

  @Override
  public void setParameters(Bundle params) {
    delegate.setParameters(params);
  }

  @Override
  public void setVideoScalingMode(int scalingMode) {
    delegate.setVideoScalingMode(scalingMode);
  }

  @Override
  public boolean needsReconfiguration() {
    return delegate.needsReconfiguration();
  }

  @RequiresApi(26)
  @Override
  public PersistableBundle getMetrics() {
    return delegate.getMetrics();
  }

  @Override
  @RequiresApi(31)
  public void subscribeToVendorParameters(List<String> names) {
    delegate.subscribeToVendorParameters(names);
  }

  @Override
  @RequiresApi(31)
  public void unsubscribeFromVendorParameters(List<String> names) {
    delegate.unsubscribeFromVendorParameters(names);
  }
}
