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
package androidx.media3.effect;

import static androidx.media3.effect.EffectsTestUtil.createPlanarYuv420Planes;
import static androidx.media3.effect.FrameProcessorUtils.RGBA_8888_BYTES_PER_PIXEL;
import static androidx.media3.effect.FrameProcessorUtils.validatePlanarYuv420Planes;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888ImageBuffer;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.test.utils.BitmapPixelTestUtil.ImageBuffer;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A {@link FrameWriter} that saves rendered {@link Frame} instances as bitmaps.
 *
 * <p>This writer has capacity for one dequeued frame at a time (decremented upon {@link
 * #dequeueInputFrame} and restored upon {@link #queueInputFrame}).
 *
 * <p>All {@link FrameWriter} methods must be called on the same thread, which may differ from the
 * thread that constructs the instance. {@link #outputBitmaps}, {@link #outputMetadata} and {@link
 * #configuredFormat} can be safely read from any thread.
 */
/* package */ final class BitmapSavingFrameWriter implements FrameWriter {

  /**
   * The saved output {@link Bitmap} instances in the order they were queued.
   *
   * <p>This list is backed by a {@link CopyOnWriteArrayList} and is safe to read from any thread,
   * but must only be mutated by this writer.
   */
  public final List<Bitmap> outputBitmaps;

  /**
   * The {@linkplain Frame#getMetadata() metadata} of the queued {@link Frame} instances, in the
   * order they were queued.
   *
   * <p>This list is backed by a {@link CopyOnWriteArrayList} and is safe to read from any thread,
   * but must only be mutated by this writer.
   */
  public final List<Map<String, Object>> outputMetadata;

  /**
   * The most recent {@link Format} passed to {@link #configure}.
   *
   * <p>Initialized to an empty {@link Format} before {@link #configure} is called. Safe to read
   * from any thread, but must only be written by this writer.
   */
  public volatile Format configuredFormat;

  private final FrameWriter.Info info;
  private final long fenceTimeoutMs;
  private final FrameBackend frameBackend;
  private final AtomicReference<@Nullable Thread> callingThread;

  private boolean hasCapacity;
  private @Nullable Runnable pendingWakeupListener;
  private @Nullable Executor pendingWakeupExecutor;

  /**
   * Creates an instance backed by a {@link HardwareBufferFrame}.
   *
   * @param fenceTimeoutMs The timeout in milliseconds when awaiting write-complete fences.
   */
  @RequiresApi(29)
  /* package */ static BitmapSavingFrameWriter createForHardwareBuffer(long fenceTimeoutMs) {
    return createForHardwareBuffer(/* info= */ (format, usage) -> true, fenceTimeoutMs);
  }

  /**
   * Creates an instance backed by a {@link HardwareBufferFrame}.
   *
   * @param info The {@link FrameWriter.Info} used to validate formats in {@link #configure}.
   * @param fenceTimeoutMs The timeout in milliseconds when awaiting write-complete fences.
   */
  @RequiresApi(29)
  /* package */ static BitmapSavingFrameWriter createForHardwareBuffer(
      FrameWriter.Info info, long fenceTimeoutMs) {
    return new BitmapSavingFrameWriter(info, fenceTimeoutMs, new HardwareBufferFrameBackend());
  }

  /**
   * Creates an instance backed by {@link PixelFormat#RGBA_8888} {@link ImagePlanesFrame} instances.
   *
   * @param fenceTimeoutMs The timeout in milliseconds when awaiting write-complete fences.
   */
  /* package */ static BitmapSavingFrameWriter createForRgbaImagePlanes(long fenceTimeoutMs) {
    return createForRgbaImagePlanes(
        fenceTimeoutMs, /* rgbaRowPaddingBytes= */ 0, /* rgbaBufferPositionOffset= */ 0);
  }

  /**
   * Creates an instance backed by {@link PixelFormat#RGBA_8888} {@link ImagePlanesFrame} instances
   * with custom buffer layout.
   *
   * @param fenceTimeoutMs The timeout in milliseconds when awaiting write-complete fences.
   * @param rgbaRowPaddingBytes Extra padding bytes added to each row's stride.
   * @param rgbaBufferPositionOffset Initial byte offset applied to the plane buffer's position.
   */
  /* package */ static BitmapSavingFrameWriter createForRgbaImagePlanes(
      long fenceTimeoutMs, int rgbaRowPaddingBytes, int rgbaBufferPositionOffset) {
    return new BitmapSavingFrameWriter(
        /* info= */ (format, usage) -> true,
        fenceTimeoutMs,
        new RgbaImagePlanesFrameBackend(rgbaRowPaddingBytes, rgbaBufferPositionOffset));
  }

  /**
   * Creates an instance backed by {@link ImageFormat#YV12} {@link ImagePlanesFrame} instances.
   *
   * @param fenceTimeoutMs The timeout in milliseconds when awaiting write-complete fences.
   */
  /* package */ static BitmapSavingFrameWriter createForYv12ImagePlanes(long fenceTimeoutMs) {
    return new BitmapSavingFrameWriter(
        /* info= */ (format, usage) -> true, fenceTimeoutMs, new Yv12ImagePlanesFrameBackend());
  }

  private BitmapSavingFrameWriter(
      FrameWriter.Info info, long fenceTimeoutMs, FrameBackend frameBackend) {
    this.outputBitmaps = new CopyOnWriteArrayList<>();
    this.outputMetadata = new CopyOnWriteArrayList<>();
    this.configuredFormat = new Format.Builder().build();
    this.info =
        (format, usage) ->
            frameBackend.isFormatSupported(format) && info.isSupported(format, usage);
    this.fenceTimeoutMs = fenceTimeoutMs;
    this.frameBackend = frameBackend;
    this.callingThread = new AtomicReference<>();
    this.hasCapacity = true;
  }

  @Override
  public Info getInfo() {
    verifyCallingThread();
    return info;
  }

  @Override
  public void configure(Format format, long usage) {
    verifyCallingThread();
    checkArgument(info.isSupported(format, usage));
    this.configuredFormat = format;
    frameBackend.configure(format);
  }

  @Override
  public @Nullable AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    verifyCallingThread();
    checkState(
        configuredFormat.width != Format.NO_VALUE && configuredFormat.height != Format.NO_VALUE,
        "configure() must be called before dequeueInputFrame()");
    if (!hasCapacity) {
      pendingWakeupListener = wakeupListener;
      pendingWakeupExecutor = wakeupExecutor;
      return null;
    }
    hasCapacity = false;
    return new AsyncFrame(frameBackend.createFrame(configuredFormat), /* acquireFence= */ null);
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    verifyCallingThread();
    if (writeCompleteFence != null) {
      try {
        checkState(writeCompleteFence.awaitMs(fenceTimeoutMs), "Fence wait timeout");
      } finally {
        writeCompleteFence.close();
      }
    }
    outputBitmaps.add(frameBackend.createBitmap(frame));
    outputMetadata.add(frame.getMetadata());
    hasCapacity = true;
    if (pendingWakeupListener != null && pendingWakeupExecutor != null) {
      Runnable wakeupListener = pendingWakeupListener;
      Executor wakeupExecutor = pendingWakeupExecutor;
      pendingWakeupListener = null;
      pendingWakeupExecutor = null;
      wakeupExecutor.execute(wakeupListener);
    }
  }

  @Override
  public void signalEndOfStream() {
    verifyCallingThread();
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    verifyCallingThread();
    frameBackend.close();
  }

  /**
   * Returns whether {@link Format#pixelFormat} equals {@code pixelFormat} or is unset.
   *
   * <p>Callers can leave {@link Format#pixelFormat} unset ({@link Format#NO_VALUE}) when they have
   * no pixel-format constraint and do not need to know the {@link FrameWriter}'s default format,
   * letting it use its native format.
   */
  private static boolean hasPixelFormatOrUnset(Format format, int pixelFormat) {
    return format.pixelFormat == pixelFormat || format.pixelFormat == Format.NO_VALUE;
  }

  private void verifyCallingThread() {
    Thread expectedThread =
        checkNotNull(
            callingThread.updateAndGet(thread -> thread == null ? Thread.currentThread() : thread));
    checkState(
        Thread.currentThread() == expectedThread,
        "BitmapSavingFrameWriter must be accessed on a single thread. Expected: %s, actual: %s",
        expectedThread.getName(),
        Thread.currentThread().getName());
  }

  /** Backend that manages frame allocation and converts rendered frames to {@link Bitmap}s. */
  private interface FrameBackend {
    /** Returns whether the given {@link Format} is supported by the backend. */
    boolean isFormatSupported(Format format);

    /** Configures the backend for the given {@link Format}. */
    default void configure(Format format) {}

    /** Creates a writable {@link Frame} for the configured format. */
    Frame createFrame(Format configuredFormat);

    /** Creates a {@link Bitmap} from the rendered {@code frame}. */
    Bitmap createBitmap(Frame frame);

    /** Releases any resources held by the backend. */
    default void close() {}
  }

  /** Implementation of {@link FrameBackend} backed by a {@link HardwareBufferFrame}. */
  @RequiresApi(29)
  private static final class HardwareBufferFrameBackend implements FrameBackend {

    private @Nullable HardwareBuffer hardwareBuffer;

    @Override
    public boolean isFormatSupported(Format format) {
      return hasPixelFormatOrUnset(format, HardwareBuffer.RGBA_8888);
    }

    @Override
    public void configure(Format format) {
      if (hardwareBuffer != null
          && hardwareBuffer.getWidth() == format.width
          && hardwareBuffer.getHeight() == format.height) {
        return;
      }
      if (hardwareBuffer != null) {
        hardwareBuffer.close();
      }
      hardwareBuffer =
          HardwareBuffer.create(
              format.width,
              format.height,
              HardwareBuffer.RGBA_8888,
              /* layers= */ 1,
              HardwareBuffer.USAGE_CPU_READ_OFTEN
                  | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                  | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    }

    @Override
    public Frame createFrame(Format configuredFormat) {
      return new DefaultHardwareBufferFrame.Builder(checkNotNull(hardwareBuffer)).build();
    }

    @Override
    public Bitmap createBitmap(Frame frame) {
      checkArgument(frame instanceof HardwareBufferFrame);
      HardwareBuffer hardwareBuffer = ((HardwareBufferFrame) frame).getHardwareBuffer();
      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
      return checkNotNull(hardwareBitmap).copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false);
    }

    @Override
    public void close() {
      if (hardwareBuffer != null) {
        hardwareBuffer.close();
        hardwareBuffer = null;
      }
    }
  }

  /**
   * Implementation of {@link FrameBackend} backed by {@link PixelFormat#RGBA_8888} {@link
   * ImagePlanesFrame} instances.
   */
  private static final class RgbaImagePlanesFrameBackend implements FrameBackend {

    private final int rgbaRowPaddingBytes;
    private final int rgbaBufferPositionOffset;

    private RgbaImagePlanesFrameBackend(int rgbaRowPaddingBytes, int rgbaBufferPositionOffset) {
      this.rgbaRowPaddingBytes = rgbaRowPaddingBytes;
      this.rgbaBufferPositionOffset = rgbaBufferPositionOffset;
    }

    @Override
    public boolean isFormatSupported(Format format) {
      return hasPixelFormatOrUnset(format, PixelFormat.RGBA_8888);
    }

    @Override
    public Frame createFrame(Format configuredFormat) {
      int rowStride = configuredFormat.width * RGBA_8888_BYTES_PER_PIXEL + rgbaRowPaddingBytes;
      ByteBuffer buffer =
          ByteBuffer.allocateDirect(rgbaBufferPositionOffset + rowStride * configuredFormat.height);
      buffer.position(rgbaBufferPositionOffset);
      Plane plane =
          new DefaultPlane(buffer, rowStride, /* pixelStride= */ RGBA_8888_BYTES_PER_PIXEL);
      Format frameFormat =
          configuredFormat.pixelFormat == Format.NO_VALUE
              ? configuredFormat.buildUpon().setPixelFormat(PixelFormat.RGBA_8888).build()
              : configuredFormat;
      return new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane), frameFormat).build();
    }

    @Override
    public Bitmap createBitmap(Frame frame) {
      checkArgument(frame instanceof ImagePlanesFrame);
      ImagePlanesFrame imagePlanesFrame = (ImagePlanesFrame) frame;
      Format format = imagePlanesFrame.getFormat();
      checkArgument(format.pixelFormat == PixelFormat.RGBA_8888);
      Plane plane = imagePlanesFrame.getPlanes().get(0);
      return createArgb8888BitmapFromRgba8888ImageBuffer(
          new ImageBuffer(
              plane.getBuffer().slice(),
              format.width,
              format.height,
              plane.getRowStride(),
              plane.getPixelStride()));
    }
  }

  /**
   * Implementation of {@link FrameBackend} backed by {@link ImageFormat#YV12} {@link
   * ImagePlanesFrame} instances.
   */
  private static final class Yv12ImagePlanesFrameBackend implements FrameBackend {

    @Override
    public boolean isFormatSupported(Format format) {
      return !ColorInfo.isTransferHdr(format.colorInfo)
          && !ColorInfo.isWideColorGamut(format.colorInfo)
          && hasPixelFormatOrUnset(format, ImageFormat.YV12);
    }

    @Override
    public Frame createFrame(Format configuredFormat) {
      ImmutableList<Plane> planes =
          createPlanarYuv420Planes(configuredFormat.width, configuredFormat.height);
      Format frameFormat = configuredFormat.buildUpon().setPixelFormat(ImageFormat.YV12).build();
      return new DefaultImagePlanesFrame.Builder(planes, frameFormat).build();
    }

    @Override
    public Bitmap createBitmap(Frame frame) {
      checkArgument(frame instanceof ImagePlanesFrame);
      ImagePlanesFrame imagePlanesFrame = (ImagePlanesFrame) frame;
      Format format = imagePlanesFrame.getFormat();
      checkArgument(format.pixelFormat == ImageFormat.YV12);
      validatePlanarYuv420Planes(imagePlanesFrame.getPlanes(), format.width, format.height);
      return createArgb8888BitmapFromPlanarYuv420Planes(
          imagePlanesFrame.getPlanes(), format.width, format.height);
    }

    private static Bitmap createArgb8888BitmapFromPlanarYuv420Planes(
        List<Plane> planes, int width, int height) {
      Plane lumaPlane = planes.get(0);
      Plane uPlane = planes.get(1);
      Plane vPlane = planes.get(2);
      ByteBuffer lumaBuffer = lumaPlane.getBuffer();
      ByteBuffer uBuffer = uPlane.getBuffer();
      ByteBuffer vBuffer = vPlane.getBuffer();
      int lumaRowStride = lumaPlane.getRowStride();
      int uRowStride = uPlane.getRowStride();
      int vRowStride = vPlane.getRowStride();
      int lumaBaseOffset = lumaBuffer.position();
      int uBaseOffset = uBuffer.position();
      int vBaseOffset = vBuffer.position();

      int[] colors = new int[width * height];
      for (int y = 0; y < height; y++) {
        int lumaRowOffset = lumaBaseOffset + y * lumaRowStride;
        int uRowOffset = uBaseOffset + (y / 2) * uRowStride;
        int vRowOffset = vBaseOffset + (y / 2) * vRowStride;
        for (int x = 0; x < width; x++) {
          int luma = lumaBuffer.get(lumaRowOffset + x) & 0xFF;
          int u = uBuffer.get(uRowOffset + x / 2) & 0xFF;
          int v = vBuffer.get(vRowOffset + x / 2) & 0xFF;

          // ITU-R BT.709 limited-range YUV-to-RGB conversion, matching GlRgbaToYuvConverter's
          // ColorInfo.SDR_BT709_LIMITED output (Y in [16, 235], U/V in [16, 240]).
          double yNorm = (luma - 16.0) / 219.0;
          double uNorm = (u - 128.0) / 224.0;
          double vNorm = (v - 128.0) / 224.0;

          int r = max(0, min(255, (int) round((yNorm + 1.5748 * vNorm) * 255.0)));
          int g =
              max(0, min(255, (int) round((yNorm - 0.187324 * uNorm - 0.468124 * vNorm) * 255.0)));
          int b = max(0, min(255, (int) round((yNorm + 1.8556 * uNorm) * 255.0)));
          colors[y * width + x] = Color.argb(255, r, g, b);
        }
      }
      return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
    }
  }
}
