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
package androidx.media3.transformer;

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.util.GlUtil.createTexture;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoCompositor;
import androidx.media3.effect.GlTextureProducer;
import androidx.media3.effect.VideoCompositor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for interactions between {@link DefaultVideoCompositor}, {@link
 * DefaultVideoCompositor.Listener} and {@link GlTextureProducer.Listener}.
 */
@RunWith(AndroidJUnit4.class)
public final class DefaultVideoCompositorListenerTest {
  private static final int TEST_TIMEOUT_MS = 1_000;

  private @MonotonicNonNull VideoCompositor videoCompositor;
  private @MonotonicNonNull ExecutorService sharedExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull AtomicReference<VideoFrameProcessingException>
      videoFrameProcessingException;
  private VideoCompositor.@MonotonicNonNull Listener videoCompositorListener;
  private GlTextureProducer.@MonotonicNonNull Listener textureOutputListener;
  private @MonotonicNonNull List<GlTextureInfo> inputTextureInfos;

  /**
   * All presentationTimeUs of frames sent to {@link GlTextureProducer.Listener#onTextureRendered}
   */
  private @MonotonicNonNull List<Long> outputPresentationTimesUs;

  @Before
  public void createGlObjects() throws Exception {
    sharedExecutorService = Util.newSingleThreadExecutor("Effect:Shared:GlThread");
    sharedExecutorService
        .invokeAll(
            ImmutableList.of(
                () -> {
                  eglDisplay = GlUtil.getDefaultEglDisplay();
                  eglContext = GlUtil.createEglContext(eglDisplay);
                  glObjectsProvider = new DefaultGlObjectsProvider();
                  GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
                  inputTextureInfos = createInputTextureInfos(/* count= */ 10);
                  return null;
                }))
        // get the result of the gl setup task to ensure any exceptions thrown immediately fail the
        // tests, rather than attempting to run the test cases.
        .get(0)
        .get();
  }

  @Before
  public void createListeners() {
    videoFrameProcessingException = new AtomicReference<>();
    videoCompositorListener =
        new VideoCompositor.Listener() {
          @Override
          public void onError(VideoFrameProcessingException exception) {
            videoFrameProcessingException.compareAndSet(null, exception);
          }

          @Override
          public void onEnded() {}
        };
    outputPresentationTimesUs = new ArrayList<>();
    textureOutputListener =
        (outputTextureProducer, outputTexture, presentationTimeUs, syncObject) ->
            outputPresentationTimesUs.add(presentationTimeUs);
  }

  @After
  public void checkExceptionsAndRelease() throws VideoFrameProcessingException, GlException {
    if (videoFrameProcessingException.get() != null) {
      throw videoFrameProcessingException.get();
    }
    if (inputTextureInfos != null) {
      for (GlTextureInfo textureInfo : inputTextureInfos) {
        GlUtil.deleteTexture(textureInfo.texId);
      }
    }
    if (eglContext != null && eglDisplay != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  public void queueFrame_singleInputSource_processesFirstFrameImmediately() throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 1);

    queueTexture(0, inputTextureInfos.get(0), 1000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);
  }

  @Test
  public void queueFrame_singleInputSource_processesLaterFramesOnRelease() throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 1);

    queueTexture(0, inputTextureInfos.get(0), 1000L);
    queueTexture(0, inputTextureInfos.get(1), 2000L);
    queueTexture(0, inputTextureInfos.get(1), 3000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);

    releaseOutputTexture(1000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 2000L).inOrder();

    releaseOutputTexture(2000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 2000L, 3000L).inOrder();
  }

  @Test
  public void queueFrame_multipleInputSources_processesFirstFrameOnceSecondStreamLooksAhead()
      throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 2);

    queueTexture(0, inputTextureInfos.get(0), 1000L);
    queueTexture(1, inputTextureInfos.get(1), 1000L);
    queueTexture(0, inputTextureInfos.get(2), 2000L);

    assertThat(outputPresentationTimesUs).isEmpty();

    queueTexture(1, inputTextureInfos.get(3), 2000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);
  }

  @Test
  public void queueFrame_multipleInputSources_processesLaterFramesOnRelease() throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 2);

    queueTexture(0, inputTextureInfos.get(0), 1000L);
    queueTexture(1, inputTextureInfos.get(1), 1000L);
    queueTexture(0, inputTextureInfos.get(2), 2000L);
    queueTexture(1, inputTextureInfos.get(3), 2000L);
    queueTexture(0, inputTextureInfos.get(4), 3000L);
    queueTexture(1, inputTextureInfos.get(5), 3000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);

    releaseOutputTexture(1000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 2000L).inOrder();
  }

  @Test
  public void queueFrame_multipleInputSourcesMissingPrimarySourceTimestamp_skipped()
      throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 2);

    queueTexture(0, inputTextureInfos.get(0), 1000L);
    queueTexture(1, inputTextureInfos.get(1), 1000L);
    // This timestamp has no corresponding frame in the primary stream.
    queueTexture(1, inputTextureInfos.get(2), 2000L);
    queueTexture(0, inputTextureInfos.get(3), 3000L);
    queueTexture(1, inputTextureInfos.get(4), 3000L);
    queueTexture(0, inputTextureInfos.get(5), 4000L);
    queueTexture(1, inputTextureInfos.get(6), 4000L);
    queueTexture(0, inputTextureInfos.get(7), 5000L);
    queueTexture(1, inputTextureInfos.get(8), 5000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);

    releaseOutputTexture(1000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 3000L).inOrder();

    releaseOutputTexture(3000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 3000L, 4000L).inOrder();
  }

  @Test
  public void queueFrame_multipleInputSourcesMissingSecondarySourceTimestamp_stillOutput()
      throws Exception {
    buildDefaultVideoCompositor(/* numInputSources= */ 2);

    queueTexture(0, inputTextureInfos.get(0), 1000L);
    queueTexture(1, inputTextureInfos.get(1), 1000L);
    // This timestamp has no corresponding frame in the secondary stream.
    queueTexture(0, inputTextureInfos.get(2), 2000L);
    queueTexture(1, inputTextureInfos.get(3), 3000L);
    queueTexture(0, inputTextureInfos.get(4), 3000L);
    queueTexture(1, inputTextureInfos.get(5), 4000L);
    queueTexture(0, inputTextureInfos.get(6), 4000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L);

    releaseOutputTexture(1000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 2000L).inOrder();

    releaseOutputTexture(2000L);

    assertThat(outputPresentationTimesUs).containsExactly(1000L, 2000L, 3000L).inOrder();
  }

  private void buildDefaultVideoCompositor(int numInputSources) {
    checkNotNull(videoCompositorListener);
    checkNotNull(textureOutputListener);
    videoCompositor =
        new DefaultVideoCompositor(
            getApplicationContext(),
            glObjectsProvider,
            sharedExecutorService,
            videoCompositorListener,
            textureOutputListener,
            /* textureOutputCapacity= */ 1);

    for (int i = 0; i < numInputSources; i++) {
      videoCompositor.registerInputSource(i);
    }
  }

  private void queueTexture(int inputIndex, GlTextureInfo textureInfo, long presentationTimeUs)
      throws InterruptedException {
    videoCompositor.queueInputTexture(
        inputIndex,
        new FakeGlTextureProducer(),
        textureInfo,
        SDR_BT709_LIMITED,
        presentationTimeUs);
    // Force any pending tasks to be run.
    sharedExecutorService.invokeAll(ImmutableList.of(() -> null), TEST_TIMEOUT_MS, MILLISECONDS);
  }

  private void releaseOutputTexture(long presentationTimeUs) throws InterruptedException {
    videoCompositor.releaseOutputTexture(presentationTimeUs);
    // Force any pending tasks to be run.
    sharedExecutorService.invokeAll(ImmutableList.of(() -> null), TEST_TIMEOUT_MS, MILLISECONDS);
  }

  private static List<GlTextureInfo> createInputTextureInfos(int count) throws GlException {
    List<GlTextureInfo> inputTextureInfos = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Bitmap bitmap = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
      int texId = createTexture(bitmap);
      inputTextureInfos.add(new GlTextureInfo(texId, C.INDEX_UNSET, C.INDEX_UNSET, 1, 1));
    }
    return inputTextureInfos;
  }

  private static class FakeGlTextureProducer implements GlTextureProducer {

    @Override
    public void releaseOutputTexture(long presentationTimeUs) {}
  }
}
