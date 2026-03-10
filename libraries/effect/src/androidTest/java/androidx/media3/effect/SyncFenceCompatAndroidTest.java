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

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Color;
import android.graphics.HardwareBufferRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.opengl.EGL14;
import android.opengl.EGL15;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.EGLSync;
import android.opengl.GLES20;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Pair;
import androidx.annotation.RequiresApi;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.util.GlUtil;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link SyncFenceCompat}. */
@RunWith(TestParameterInjector.class)
public class SyncFenceCompatAndroidTest {

  private static final int TEST_TIMEOUT_MS = 1000;

  private enum FenceCreationType {
    DUPLICATE_OPEN_GL,
    DUPLICATE_HARDWARE_BUFFER_RENDERER,
    ADOPT_FILE_DESCRIPTOR
  }

  private static class FenceCreationTypeProvider extends TestParameterValuesProvider {
    @Override
    protected List<FenceCreationType> provideValues(TestParameterValuesProvider.Context context) {
      List<FenceCreationType> supportedTypes = new ArrayList<>();
      supportedTypes.add(FenceCreationType.ADOPT_FILE_DESCRIPTOR);

      if (Build.VERSION.SDK_INT >= 33) {
        supportedTypes.add(FenceCreationType.DUPLICATE_OPEN_GL);
      }
      if (Build.VERSION.SDK_INT >= 34) {
        supportedTypes.add(FenceCreationType.DUPLICATE_HARDWARE_BUFFER_RENDERER);
      }

      return supportedTypes;
    }
  }

  private final List<AutoCloseable> resourcesToClose = new ArrayList<>();
  private final GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();

  private @MonotonicNonNull EGLDisplay eglDisplay;

  @Before
  public void setUp() throws Exception {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    @MonotonicNonNull
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    EGLSurface unused =
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  @After
  public void tearDown() throws Exception {
    for (AutoCloseable resource : resourcesToClose) {
      resource.close();
    }
    resourcesToClose.clear();
    glObjectsProvider.release(eglDisplay);
  }

  @Test
  public void await_signalsAndClosesCorrectly(
      @TestParameter(valuesProvider = FenceCreationTypeProvider.class)
          FenceCreationType creationType)
      throws Exception {
    try (SyncFenceCompat compatFence = createSignaledFence(creationType)) {
      boolean signaled = compatFence.await(TEST_TIMEOUT_MS);
      assertThat(signaled).isTrue();
    }
  }

  @Test
  public void await_afterCombine_signalsCorrectly(
      @TestParameter(valuesProvider = FenceCreationTypeProvider.class)
          FenceCreationType creationType)
      throws Exception {
    try (SyncFenceCompat compat1 = createSignaledFence(creationType);
        SyncFenceCompat compat2 = createSignaledFence(creationType);
        SyncFenceCompat combined = SyncFenceCompat.combine(Arrays.asList(compat1, compat2))) {

      assertThat(combined.await(TEST_TIMEOUT_MS)).isTrue();

      // The underlying fences should immediately signal once the combined fence has signaled.
      assertThat(compat1.await(/* timeoutMs= */ 0)).isTrue();
      assertThat(compat2.await(/* timeoutMs= */ 0)).isTrue();
    }
  }

  @Test
  public void await_afterClose_signalsCorrectly(
      @TestParameter(valuesProvider = FenceCreationTypeProvider.class)
          FenceCreationType creationType)
      throws Exception {
    try (SyncFenceCompat compat1 = createSignaledFence(creationType)) {

      compat1.close();

      // The underlying fence should immediately signal after it is closed.
      assertThat(compat1.await(/* timeoutMs= */ 0)).isTrue();
    }
  }

  @Test
  public void await_afterCloseAndDuplicate_signalsImmediately(
      @TestParameter(valuesProvider = FenceCreationTypeProvider.class)
          FenceCreationType creationType)
      throws Exception {
    try (SyncFenceCompat compat1 = createSignaledFence(creationType)) {

      compat1.close();

      try (SyncFenceCompat compat2 = SyncFenceCompat.combine(ImmutableList.of(compat1))) {
        // The duplicated fence should not have to be waited on before signaling.
        boolean signaled2 = compat2.await(/* timeoutMs= */ 0);
        assertThat(signaled2).isTrue();
      }
    }
  }

  @Test
  public void await_withUnsignaledFence_timesOut() throws Exception {
    Pair<ParcelFileDescriptor, ParcelFileDescriptor> readWritePfds =
        createReadWritePfds(resourcesToClose);
    ParcelFileDescriptor readPfd = readWritePfds.first;

    try (SyncFenceCompat compatFence =
        SyncFenceCompat.adoptFenceFileDescriptor(readPfd.detachFd())) {
      boolean signaled = compatFence.await(/* timeoutMs= */ 100);
      assertThat(signaled).isFalse();
    }
  }

  @Test
  public void await_afterSignal_succeeds() throws Exception {
    Pair<ParcelFileDescriptor, ParcelFileDescriptor> readWritePfds =
        createReadWritePfds(resourcesToClose);
    ParcelFileDescriptor readPfd = readWritePfds.first;
    ParcelFileDescriptor writePfd = readWritePfds.second;

    int rawFd = readPfd.detachFd();

    try (SyncFenceCompat compatFence = SyncFenceCompat.adoptFenceFileDescriptor(rawFd)) {
      assertThat(compatFence.await(/* timeoutMs= */ 100)).isFalse();

      // Write a single byte to the pipe, this causes the read-end to signal POLLIN.
      Os.write(writePfd.getFileDescriptor(), new byte[] {1}, 0, 1);

      assertThat(compatFence.await(/* timeoutMs= */ 100)).isTrue();
    }
  }

  /**
   * Creates a {@link SyncFenceCompat} that will signal, with the given {@link FenceCreationType}.
   */
  private SyncFenceCompat createSignaledFence(FenceCreationType type) throws Exception {
    switch (type) {
      case DUPLICATE_HARDWARE_BUFFER_RENDERER:
        return Api34TestUtils.createSignaledHwbrFenceCompat(resourcesToClose);

      case DUPLICATE_OPEN_GL:
        return Api33TestUtils.createSignaledEglFenceCompat(eglDisplay);

      case ADOPT_FILE_DESCRIPTOR:
        return createSignaledFileDescriptorFenceCompat(resourcesToClose);
    }
    throw new IllegalArgumentException("Unknown creation type");
  }

  private static SyncFenceCompat createSignaledFileDescriptorFenceCompat(
      List<AutoCloseable> resourcesToClose) throws Exception {
    Pair<ParcelFileDescriptor, ParcelFileDescriptor> readWritePfds =
        createReadWritePfds(resourcesToClose);
    ParcelFileDescriptor readPfd = readWritePfds.first;
    ParcelFileDescriptor writePfd = readWritePfds.second;

    // Write a single byte to the pipe, this causes the read-end to signal POLLIN.
    Os.write(writePfd.getFileDescriptor(), new byte[] {1}, 0, 1);

    return SyncFenceCompat.adoptFenceFileDescriptor(readPfd.detachFd());
  }

  /**
   * Returns a {@link Pair} holding the {@linkplain ParcelFileDescriptor readPfd}, and {@linkplain
   * ParcelFileDescriptor writePfd}.
   */
  private static Pair<ParcelFileDescriptor, ParcelFileDescriptor> createReadWritePfds(
      List<AutoCloseable> resourcesToClose) throws Exception {
    FileDescriptor[] pipe = Os.pipe();
    ParcelFileDescriptor readPfd = ParcelFileDescriptor.dup(pipe[0]);
    ParcelFileDescriptor writePfd = ParcelFileDescriptor.dup(pipe[1]);
    resourcesToClose.add(readPfd);
    resourcesToClose.add(writePfd);
    // Clean up the original pipe file descriptors as they have been duped.
    Os.close(pipe[0]);
    Os.close(pipe[1]);

    return Pair.create(readPfd, writePfd);
  }

  @RequiresApi(34)
  private static class Api34TestUtils {
    static SyncFenceCompat createSignaledHwbrFenceCompat(List<AutoCloseable> resourcesToClose)
        throws InterruptedException {
      HardwareBuffer buffer =
          HardwareBuffer.create(
              /* width= */ 10,
              /* height= */ 10,
              HardwareBuffer.RGBA_8888,
              /* layers= */ 1,
              HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);

      resourcesToClose.add(buffer);

      HardwareBufferRenderer renderer = new HardwareBufferRenderer(buffer);
      resourcesToClose.add(renderer);
      RenderNode renderNode = new RenderNode("content");
      renderNode.setPosition(0, 0, buffer.getWidth(), buffer.getHeight());
      RecordingCanvas canvas = renderNode.beginRecording();
      canvas.drawColor(Color.GREEN);
      renderNode.endRecording();

      renderer.setContentRoot(renderNode);

      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<SyncFence> fenceRef = new AtomicReference<>();

      renderer
          .obtainRenderRequest()
          .draw(
              /* executor= */ Runnable::run,
              /* renderCallback= */ result -> {
                fenceRef.set(result.getFence());
                latch.countDown();
              });

      assertThat(latch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

      SyncFence fence = fenceRef.get();
      SyncFenceCompat compat = SyncFenceCompat.duplicate(fence);
      fence.close();
      return compat;
    }
  }

  @RequiresApi(33)
  private static class Api33TestUtils {
    static SyncFenceCompat createSignaledEglFenceCompat(EGLDisplay display) {
      long[] attribs = {EGL14.EGL_NONE};

      EGLSync eglSync =
          EGL15.eglCreateSync(
              display, EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID, attribs, /* offset= */ 0);
      assertThat(eglSync).isNotEqualTo(EGL15.EGL_NO_SYNC);

      SyncFence fence = EGLExt.eglDupNativeFenceFDANDROID(display, eglSync);
      GLES20.glFlush();
      EGL15.eglDestroySync(display, eglSync);

      SyncFenceCompat compat = SyncFenceCompat.duplicate(fence);
      fence.close();
      return compat;
    }
  }
}
