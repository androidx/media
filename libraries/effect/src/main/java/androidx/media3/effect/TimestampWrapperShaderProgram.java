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
package androidx.media3.effect;

import android.content.Context;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import java.util.concurrent.Executor;

/** Applies a {@link TimestampWrapper} to apply a wrapped {@link GlEffect} on certain timestamps. */
@UnstableApi
/* package */ final class TimestampWrapperShaderProgram implements GlShaderProgram {

  private final GlShaderProgram copyGlShaderProgram;
  private int pendingCopyGlShaderProgramFrames;
  private final GlShaderProgram wrappedGlShaderProgram;
  private int pendingWrappedGlShaderProgramFrames;

  private final long startTimeUs;
  private final long endTimeUs;

  /**
   * Creates a {@code TimestampWrapperShaderProgram} instance.
   *
   * @param context The {@link Context}.
   * @param useHdr Whether input textures come from an HDR source. If {@code true}, colors will be
   *     in linear RGB BT.2020. If {@code false}, colors will be in linear RGB BT.709.
   * @param timestampWrapper The {@link TimestampWrapper} to apply to each frame.
   */
  public TimestampWrapperShaderProgram(
      Context context, boolean useHdr, TimestampWrapper timestampWrapper)
      throws VideoFrameProcessingException {
    copyGlShaderProgram = new FrameCache(/* capacity= */ 1).toGlShaderProgram(context, useHdr);
    wrappedGlShaderProgram = timestampWrapper.glEffect.toGlShaderProgram(context, useHdr);

    startTimeUs = timestampWrapper.startTimeUs;
    endTimeUs = timestampWrapper.endTimeUs;
  }

  @Override
  public void setInputListener(InputListener inputListener) {
    copyGlShaderProgram.setInputListener(inputListener);
    wrappedGlShaderProgram.setInputListener(inputListener);
  }

  @Override
  public void setOutputListener(OutputListener outputListener) {
    copyGlShaderProgram.setOutputListener(outputListener);
    wrappedGlShaderProgram.setOutputListener(outputListener);
  }

  @Override
  public void setErrorListener(Executor errorListenerExecutor, ErrorListener errorListener) {
    copyGlShaderProgram.setErrorListener(errorListenerExecutor, errorListener);
    wrappedGlShaderProgram.setErrorListener(errorListenerExecutor, errorListener);
  }

  @Override
  public void setGlObjectsProvider(GlObjectsProvider glObjectsProvider) {
    copyGlShaderProgram.setGlObjectsProvider(glObjectsProvider);
    wrappedGlShaderProgram.setGlObjectsProvider(glObjectsProvider);
  }

  @Override
  public void queueInputFrame(GlTextureInfo inputTexture, long presentationTimeUs) {
    if (presentationTimeUs >= startTimeUs && presentationTimeUs <= endTimeUs) {
      pendingWrappedGlShaderProgramFrames++;
      wrappedGlShaderProgram.queueInputFrame(inputTexture, presentationTimeUs);
    } else {
      pendingCopyGlShaderProgramFrames++;
      copyGlShaderProgram.queueInputFrame(inputTexture, presentationTimeUs);
    }
  }

  @Override
  public void releaseOutputFrame(GlTextureInfo outputTexture) {
    if (pendingCopyGlShaderProgramFrames > 0) {
      copyGlShaderProgram.releaseOutputFrame(outputTexture);
      pendingCopyGlShaderProgramFrames--;
    } else if (pendingWrappedGlShaderProgramFrames > 0) {
      wrappedGlShaderProgram.releaseOutputFrame(outputTexture);
      pendingWrappedGlShaderProgramFrames--;
    } else {
      throw new IllegalArgumentException("Output texture not contained in either shader.");
    }
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    copyGlShaderProgram.signalEndOfCurrentInputStream();
    wrappedGlShaderProgram.signalEndOfCurrentInputStream();
  }

  @Override
  public void flush() {
    copyGlShaderProgram.flush();
    wrappedGlShaderProgram.flush();
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    copyGlShaderProgram.release();
    wrappedGlShaderProgram.release();
  }
}
