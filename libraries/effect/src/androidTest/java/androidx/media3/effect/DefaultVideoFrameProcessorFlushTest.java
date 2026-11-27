/*
 * Copyright 2023 The Android Open Source Project
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

import static android.graphics.Bitmap.createBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmapUnpremultipliedAlpha;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Bitmap;
import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.test.utils.VideoFrameProcessorTestRunner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Test for {@link DefaultVideoFrameProcessor} flushing. */
@RunWith(AndroidJUnit4.class)
public class DefaultVideoFrameProcessorFlushTest {
  private static final String ORIGINAL_PNG_ASSET_PATH = "media/png/media3test_srgb.png";

  @Rule public final TestName testName = new TestName();

  private int outputFrameCount;
  private String testId;
  private @MonotonicNonNull VideoFrameProcessorTestRunner videoFrameProcessorTestRunner;

  @Before
  public void setUp() {
    testId = testName.getMethodName();
  }

  @After
  public void release() {
    checkNotNull(videoFrameProcessorTestRunner).release();
  }

  // This tests a condition that is difficult to synchronize, and is subject to a race condition. It
  // may flake/fail if any queued frames are processed in the VideoFrameProcessor thread, before
  // flush begins and cancels these pending frames. However, this is better than not testing this
  // behavior at all, and in practice has succeeded every time on a 1000-time run.
  // TODO: b/302695659 - Make this test more deterministic.
  @Test
  public void imageInput_flushRightAfterInput_outputsPartialFrames() throws Exception {
    videoFrameProcessorTestRunner = createDefaultVideoFrameProcessorTestRunner(testId);
    Bitmap bitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_PNG_ASSET_PATH);
    int inputFrameCount = 3;

    videoFrameProcessorTestRunner.queueInputBitmap(
        bitmap,
        /* durationUs= */ inputFrameCount * C.MICROS_PER_SECOND,
        /* offsetToAddUs= */ 0L,
        /* frameRate= */ 1);
    videoFrameProcessorTestRunner.flush();
    videoFrameProcessorTestRunner.endFrameProcessing();

    // This assertion is subject to flaking, per test comments. If it flakes, consider increasing
    // inputFrameCount.
    assertThat(outputFrameCount).isLessThan(inputFrameCount);
  }

  @Test
  public void imageInput_flushAfterAllFramesOutput_outputsAllFrames() throws Exception {
    videoFrameProcessorTestRunner = createDefaultVideoFrameProcessorTestRunner(testId);
    Bitmap bitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_PNG_ASSET_PATH);
    int inputFrameCount = 3;

    videoFrameProcessorTestRunner.queueInputBitmap(
        bitmap,
        /* durationUs= */ inputFrameCount * C.MICROS_PER_SECOND,
        /* offsetToAddUs= */ 0L,
        /* frameRate= */ 1);
    videoFrameProcessorTestRunner.endFrameProcessing();
    videoFrameProcessorTestRunner.flush();

    assertThat(outputFrameCount).isEqualTo(inputFrameCount);
  }

  @Test
  public void textureOutput_flushAfterAllFramesOutput_outputsAllFramesAndReceivesFlush()
      throws Exception {
    Bitmap bitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_PNG_ASSET_PATH);
    Queue<Long> textureListenerEvents = new ConcurrentLinkedQueue<>();
    videoFrameProcessorTestRunner =
        createDefaultVideoFrameProcessorTestRunner(
            testId,
            new DefaultVideoFrameProcessor.Factory.Builder()
                .setTextureOutput(
                    new GlTextureProducer.Listener() {
                      @Override
                      public void onTextureRendered(
                          GlTextureProducer textureProducer,
                          GlTextureInfo outputTexture,
                          long presentationTimeUs,
                          long syncObject) {
                        textureListenerEvents.add(presentationTimeUs);
                        textureProducer.releaseOutputTexture(presentationTimeUs);
                      }

                      @Override
                      public void flush() {
                        textureListenerEvents.add(C.TIME_UNSET);
                      }
                    },
                    /* textureOutputCapacity= */ 1)
                .build());

    videoFrameProcessorTestRunner.queueInputBitmap(
        bitmap, /* durationUs= */ 300_000, /* offsetToAddUs= */ 0, /* frameRate= */ 10);
    videoFrameProcessorTestRunner.endFrameProcessing();
    videoFrameProcessorTestRunner.flush();

    assertThat(textureListenerEvents)
        .containsExactly(0L, 100_000L, 200_000L, C.TIME_UNSET)
        .inOrder();
  }

  @Test
  public void textureOutput_flush_outputsOnlyTexturesFromSecondItemAfterFlush() throws Exception {
    Bitmap bitmap = createBitmap(/* width= */ 1, /* height= */ 1, Bitmap.Config.ARGB_8888);
    Queue<Long> textureListenerEventsSinceLastFlush = new ConcurrentLinkedQueue<>();
    ConditionVariable firstFrameReceived = new ConditionVariable();
    videoFrameProcessorTestRunner =
        createDefaultVideoFrameProcessorTestRunner(
            testId,
            new DefaultVideoFrameProcessor.Factory.Builder()
                .setTextureOutput(
                    new GlTextureProducer.Listener() {
                      @Override
                      public void onTextureRendered(
                          GlTextureProducer textureProducer,
                          GlTextureInfo outputTexture,
                          long presentationTimeUs,
                          long syncObject) {
                        firstFrameReceived.open();
                        textureListenerEventsSinceLastFlush.add(presentationTimeUs);
                        textureProducer.releaseOutputTexture(presentationTimeUs);
                      }

                      @Override
                      public void flush() {
                        textureListenerEventsSinceLastFlush.clear();
                        textureListenerEventsSinceLastFlush.add(C.TIME_UNSET);
                      }
                    },
                    /* textureOutputCapacity= */ 1)
                .build());

    // Video frame processor may recycle the bitmap. Use a copy.
    videoFrameProcessorTestRunner.queueInputBitmap(
        bitmap.copy(bitmap.getConfig(), /* isMutable= */ false),
        /* durationUs= */ 300_000,
        /* offsetToAddUs= */ 0,
        /* frameRate= */ 10);
    firstFrameReceived.block(/* timeoutMs= */ 1_000);
    videoFrameProcessorTestRunner.flush();
    videoFrameProcessorTestRunner.queueInputBitmap(
        bitmap.copy(bitmap.getConfig(), /* isMutable= */ false),
        /* durationUs= */ 300_000,
        /* offsetToAddUs= */ 1_000_000,
        /* frameRate= */ 10);
    videoFrameProcessorTestRunner.endFrameProcessing();

    assertThat(textureListenerEventsSinceLastFlush)
        .containsExactly(C.TIME_UNSET, 1_000_000L, 1_100_000L, 1_200_000L)
        .inOrder();
  }

  private VideoFrameProcessorTestRunner createDefaultVideoFrameProcessorTestRunner(String testId)
      throws VideoFrameProcessingException {
    return createDefaultVideoFrameProcessorTestRunner(
        testId, new DefaultVideoFrameProcessor.Factory.Builder().build());
  }

  private VideoFrameProcessorTestRunner createDefaultVideoFrameProcessorTestRunner(
      String testId, DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory)
      throws VideoFrameProcessingException {
    return new VideoFrameProcessorTestRunner.Builder()
        .setTestId(testId)
        .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
        .setOnOutputFrameAvailableForRenderingListener(unused -> outputFrameCount++)
        .build();
  }
}
