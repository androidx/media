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
package androidx.media3.effect;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.Bitmap.Config.RGBA_1010102;
import static android.graphics.Bitmap.Config.RGBA_F16;
import static android.graphics.ColorSpace.Named.BT2020_HLG;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.C.COLOR_TRANSFER_HLG;
import static androidx.media3.common.util.GlUtil.createRgb10A2Texture;
import static androidx.media3.common.util.GlUtil.createTexture;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.NullableType;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link FrameProcessor} implementation that converts a {@link GlTextureFrame} to a {@link
 * BitmapFrame}.
 *
 * <p>This class is experimental and subject to change.
 *
 * <p>This processor reads the pixels from the input OpenGL texture and copies them into an Android
 * {@link Bitmap}. It handles both SDR and HDR content, using appropriate bitmap configurations and
 * color spaces. HDR input is assumed to use {@link ColorTransfer#COLOR_TRANSFER_LINEAR}.
 */
/* package */ final class GlTextureToBitmapFrameProcessor
    implements FrameProcessor<GlTextureFrame, BitmapFrame> {

  /** The visible portion of the frame. */
  private static final ImmutableList<float[]> visiblePolygon =
      ImmutableList.of(
          new float[] {-1, -1, 0, 1},
          new float[] {-1, 1, 0, 1},
          new float[] {1, 1, 0, 1},
          new float[] {1, -1, 0, 1});

  private static final String HDR_VERTEX_SHADER_FILE_PATH =
      "shaders/vertex_shader_transformation_es3.glsl";
  private static final String HDR_FRAGMENT_SHADER_FILE_PATH =
      "shaders/fragment_shader_oetf_es3.glsl";

  private final ListeningExecutorService glThreadExecutorService;
  private final GlObjectsProvider glObjectsProvider;
  private final InputConsumer inputConsumer;
  private final Queue<BitmapFrame> processedFrames;
  private final AtomicBoolean canAcceptInput;
  private final AtomicReference<
          @NullableType Pair<Executor, Consumer<VideoFrameProcessingException>>>
      onErrorCallback;
  private final AtomicBoolean isReleased;
  private final int bytesPerPixel;
  private final boolean useHdr;
  private final boolean hdrUses16BitFloat;
  @Nullable private final GlProgram glProgram;

  private @MonotonicNonNull GlTextureInfo hlgTextureInfo;
  private @MonotonicNonNull ByteBuffer byteBuffer;
  @Nullable private FrameConsumer<BitmapFrame> downstreamConsumer;

  /**
   * Creates a new instance.
   *
   * <p>Must be called on the {@linkplain #glThreadExecutorService GL thread}.
   */
  public GlTextureToBitmapFrameProcessor(
      Context context,
      boolean useHdr,
      ListeningExecutorService glThreadExecutorService,
      GlObjectsProvider glObjectsProvider)
      throws VideoFrameProcessingException {
    this.glThreadExecutorService = glThreadExecutorService;
    this.useHdr = useHdr;
    this.glObjectsProvider = glObjectsProvider;
    this.inputConsumer = new InputConsumer();
    this.processedFrames = new ArrayDeque<>();
    this.canAcceptInput = new AtomicBoolean(true);
    this.onErrorCallback = new AtomicReference<>();
    this.isReleased = new AtomicBoolean();
    // RGBA_1010102 Bitmaps cannot be saved to file prior to API 36. See b/438163272.
    hdrUses16BitFloat = SDK_INT <= 35;
    bytesPerPixel = useHdr && hdrUses16BitFloat ? 8 : 4;
    if (useHdr) {
      checkState(SDK_INT >= 34);
      try {
        glProgram =
            new GlProgram(context, HDR_VERTEX_SHADER_FILE_PATH, HDR_FRAGMENT_SHADER_FILE_PATH);
      } catch (IOException | GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
      glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
      glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
      glProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
      glProgram.setIntUniform("uOutputColorTransfer", COLOR_TRANSFER_HLG);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.createVertexBuffer(visiblePolygon),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    } else {
      glProgram = null;
    }
  }

  @Override
  public FrameConsumer<GlTextureFrame> getInput() {
    checkState(!isReleased.get());
    return inputConsumer;
  }

  @Override
  public ListenableFuture<Void> setOutputAsync(
      @Nullable FrameConsumer<BitmapFrame> nextOutputConsumer) {
    checkState(!isReleased.get());
    return Futures.submit(() -> setOutputInternal(nextOutputConsumer), glThreadExecutorService);
  }

  @Override
  public ListenableFuture<Void> releaseAsync() {
    if (!isReleased.compareAndSet(false, true)) {
      return Futures.immediateVoidFuture();
    }
    return glThreadExecutorService.submit(
        () -> {
          releaseInternal();
          return null;
        });
  }

  @Override
  public void setOnErrorCallback(
      Executor executor, Consumer<VideoFrameProcessingException> onErrorCallback) {
    this.onErrorCallback.set(Pair.create(executor, onErrorCallback));
  }

  @Override
  public void clearOnErrorCallback() {
    onErrorCallback.set(null);
  }

  // Methods called on the GL thread.

  private void setOutputInternal(@Nullable FrameConsumer<BitmapFrame> nextOutputConsumer) {
    @Nullable FrameConsumer<BitmapFrame> oldConsumer = this.downstreamConsumer;
    if (oldConsumer == nextOutputConsumer) {
      return;
    }
    if (oldConsumer != null) {
      oldConsumer.clearOnCapacityAvailableCallback();
    }
    this.downstreamConsumer = nextOutputConsumer;
    if (this.downstreamConsumer != null) {
      this.downstreamConsumer.setOnCapacityAvailableCallback(
          glThreadExecutorService, this::maybeDrainProcessedFrames);
    }
  }

  private void releaseInternal() {
    @Nullable BitmapFrame nextFrame = processedFrames.poll();
    while (nextFrame != null) {
      nextFrame.release();
      nextFrame = processedFrames.poll();
    }
  }

  private void processFrameInternal(GlTextureFrame inputFrame) {
    GlTextureInfo inputTexture = inputFrame.glTextureInfo;
    Bitmap bitmap;
    try {
      ensureConfigured(glObjectsProvider, inputTexture.width, inputTexture.height);
      bitmap = useHdr ? generateHdrBitmap(inputTexture) : generateSdrBitmap(inputTexture);
    } catch (Exception e) {
      inputFrame.release();
      onError(new VideoFrameProcessingException(e));
      return;
    }
    checkState(byteBuffer != null);
    bitmap.copyPixelsFromBuffer(byteBuffer);
    BitmapFrame.Metadata outputFrameMetadata =
        new BitmapFrame.Metadata(inputFrame.presentationTimeUs, inputFrame.format);
    BitmapFrame outputFrame = new BitmapFrame(bitmap, outputFrameMetadata);
    processedFrames.add(outputFrame);
    inputFrame.release();
    canAcceptInput.set(true);
    inputConsumer.notifyCapacityListener();
    maybeDrainProcessedFrames();
  }

  private Bitmap generateHdrBitmap(GlTextureInfo inputTexture) throws GlUtil.GlException {
    checkState(hlgTextureInfo != null);
    checkState(byteBuffer != null);
    if (SDK_INT < 34) {
      throw new IllegalStateException(
          String.format("HDR requires SDK_INT of 34+. Current value is: %s", SDK_INT));
    }
    // Applies OETF conversion.
    GlUtil.focusFramebufferUsingCurrentContext(
        hlgTextureInfo.fboId, hlgTextureInfo.width, hlgTextureInfo.height);
    GlUtil.checkGlError();
    checkNotNull(glProgram).use();
    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
    glProgram.bindAttributesAndUniforms();
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, /* first= */ 0, /* count= */ visiblePolygon.size());
    GlUtil.checkGlError();
    // For OpenGL format, internalFormat, type see the docs:
    // https://registry.khronos.org/OpenGL-Refpages/es3/html/glReadPixels.xhtml
    // https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glTexImage2D.xhtml
    GLES20.glReadPixels(
        /* x= */ 0,
        /* y= */ 0,
        hlgTextureInfo.width,
        hlgTextureInfo.height,
        /* format= */ GLES20.GL_RGBA,
        /* type= */ hdrUses16BitFloat
            ? GLES30.GL_HALF_FLOAT
            : GLES30.GL_UNSIGNED_INT_2_10_10_10_REV,
        byteBuffer);
    GlUtil.checkGlError();
    return Bitmap.createBitmap(
        /* display= */ null,
        hlgTextureInfo.width,
        hlgTextureInfo.height,
        hdrUses16BitFloat ? RGBA_F16 : RGBA_1010102,
        /* hasAlpha= */ false,
        ColorSpace.get(BT2020_HLG));
  }

  private Bitmap generateSdrBitmap(GlTextureInfo inputTexture) throws GlUtil.GlException {
    checkState(byteBuffer != null);
    GlUtil.focusFramebufferUsingCurrentContext(
        inputTexture.fboId, inputTexture.width, inputTexture.height);
    GlUtil.checkGlError();
    GLES20.glReadPixels(
        /* x= */ 0,
        /* y= */ 0,
        inputTexture.width,
        inputTexture.height,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        byteBuffer);
    GlUtil.checkGlError();
    // According to https://www.khronos.org/opengl/wiki/Pixel_Transfer#Endian_issues,
    // the colors will have the order RGBA in client memory. This is what the bitmap expects:
    // https://developer.android.com/reference/android/graphics/Bitmap.Config.
    return Bitmap.createBitmap(inputTexture.width, inputTexture.height, ARGB_8888);
  }

  private void ensureConfigured(GlObjectsProvider glObjectsProvider, int width, int height)
      throws GlUtil.GlException {
    int pixelBufferSize = width * height * bytesPerPixel;
    if (byteBuffer == null || byteBuffer.capacity() != pixelBufferSize) {
      byteBuffer = ByteBuffer.allocateDirect(pixelBufferSize);
    }
    byteBuffer.clear();

    if (useHdr) {
      if (hlgTextureInfo == null
          || hlgTextureInfo.width != width
          || hlgTextureInfo.height != height) {
        if (hlgTextureInfo != null) {
          hlgTextureInfo.release();
        }
        int texId =
            hdrUses16BitFloat
                ? createTexture(width, height, /* useHighPrecisionColorComponents= */ true)
                : createRgb10A2Texture(width, height);
        hlgTextureInfo = glObjectsProvider.createBuffersForTexture(texId, width, height);
      }
    }
  }

  private void maybeDrainProcessedFrames() {
    if (isReleased.get()) {
      return;
    }
    @Nullable BitmapFrame nextFrame = processedFrames.peek();
    while (nextFrame != null) {
      if (downstreamConsumer == null || !downstreamConsumer.queueFrame(nextFrame)) {
        return;
      }
      processedFrames.poll();
      nextFrame = processedFrames.peek();
    }
  }

  private void onError(VideoFrameProcessingException e) {
    @Nullable
    Pair<Executor, Consumer<VideoFrameProcessingException>> errorCallbackPair =
        onErrorCallback.get();
    if (errorCallbackPair != null) {
      errorCallbackPair.first.execute(() -> errorCallbackPair.second.accept(e));
    }
  }

  private final class InputConsumer implements FrameConsumer<GlTextureFrame> {

    private final AtomicReference<@NullableType Pair<Executor, Runnable>>
        onCapacityAvailableCallbackReference;

    public InputConsumer() {
      onCapacityAvailableCallbackReference = new AtomicReference<>(null);
    }

    // Methods called on the queueing thread.

    @Override
    public boolean queueFrame(GlTextureFrame frame) {
      checkState(!isReleased.get());
      if (!canAcceptInput.compareAndSet(true, false)) {
        return false;
      }
      Futures.addCallback(
          glThreadExecutorService.submit(
              () -> {
                processFrameInternal(frame);
                return null;
              }),
          new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object result) {}

            @Override
            public void onFailure(Throwable t) {
              onError(new VideoFrameProcessingException(t));
            }
          },
          glThreadExecutorService);
      return true;
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor onCapacityAvailableExecutor, Runnable onCapacityAvailableCallback) {
      if (!onCapacityAvailableCallbackReference.compareAndSet(
          null, Pair.create(onCapacityAvailableExecutor, onCapacityAvailableCallback))) {
        throw new IllegalStateException("onCapacityAvailableCallback already set");
      }
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      onCapacityAvailableCallbackReference.set(null);
    }

    // Called on the GL thread.
    private void notifyCapacityListener() {
      if (isReleased.get()) {
        return;
      }
      @Nullable
      Pair<Executor, Runnable> onCapacityAvailableCallbackPair =
          onCapacityAvailableCallbackReference.get();
      if (onCapacityAvailableCallbackPair != null) {
        onCapacityAvailableCallbackPair.first.execute(onCapacityAvailableCallbackPair.second);
      }
    }
  }
}
