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

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.SRGB_BT709_FULL;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.BitmapFrame.Metadata;
import androidx.media3.effect.EffectsTestUtil.FakeFrameConsumer;
import androidx.media3.effect.EffectsTestUtil.FakeGlShaderProgram;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlShaderProgramFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class BitmapToGlTextureFrameProcessorTest {

  private static final int TEST_TIMEOUT = 1000;
  private static final int WIDTH = 20;
  private static final int HEIGHT = 10;
  private BitmapToGlTextureFrameProcessor processor;
  private ListeningExecutorService glThreadExecutorService;
  private Consumer<VideoFrameProcessingException> errorListener;
  private FakeGlShaderProgram fakeSampler;
  private FakeTextureManager fakeTextureManager;
  private FakeFrameConsumer<GlTextureFrame> fakeFrameConsumer;

  @Before
  public void setUp() throws Exception {
    glThreadExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    errorListener =
        exception -> {
          throw new AssertionError(exception);
        };
    VideoFrameProcessingTaskExecutor taskExecutor =
        new VideoFrameProcessingTaskExecutor(
            glThreadExecutorService,
            /* shouldShutdownExecutorService= */ false,
            errorListener::accept);
    fakeTextureManager = new FakeTextureManager(taskExecutor);
    fakeSampler = new FakeGlShaderProgram();
    fakeFrameConsumer = new FakeFrameConsumer<>(1);
    processor =
        BitmapToGlTextureFrameProcessor.create(
            glThreadExecutorService, fakeTextureManager, fakeSampler, SDR_BT709_LIMITED);
    processor.setOnErrorCallback(glThreadExecutorService, errorListener);
    processor.setOutputAsync(fakeFrameConsumer).get(TEST_TIMEOUT, MILLISECONDS);
  }

  @After
  public void tearDown() throws ExecutionException, InterruptedException, TimeoutException {
    processor.releaseAsync().get(TEST_TIMEOUT, MILLISECONDS);
    glThreadExecutorService.shutdownNow();
  }

  @Test
  public void create_chainsListeners() {
    assertThat(fakeTextureManager.samplingGlShaderProgram).isSameInstanceAs(fakeSampler);
    assertThat(fakeSampler.inputListener).isSameInstanceAs(fakeTextureManager);
    assertThat(fakeSampler.outputListener).isSameInstanceAs(processor);
  }

  @Test
  public void queueFrame_callsTextureManagerQueueInputBitmap() {
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);

    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();

    assertThat(fakeTextureManager.queuedBitmaps).containsExactly(inputFrame.getBitmap());
    assertThat(fakeTextureManager.queuedFrameInfos).hasSize(1);
    FrameInfo queuedFrameInfo = fakeTextureManager.queuedFrameInfos.get(0);
    assertThat(queuedFrameInfo.offsetToAddUs).isEqualTo(0);
    assertThat(queuedFrameInfo.format).isSameInstanceAs(inputFrame.getMetadata().getFormat());
    assertThat(fakeTextureManager.queuedTimestampIterators).hasSize(1);
    TimestampIterator queuedTimestampIterator = fakeTextureManager.queuedTimestampIterators.get(0);
    queuedTimestampIterator.next();
    assertThat(queuedTimestampIterator.hasNext()).isFalse();
  }

  @Test
  public void queueFrame_callsTextureManagerSignalEndOfCurrentInputStream() {
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);
    assertThat(fakeTextureManager.signalEndOfCurrentInputStreamCalled).isFalse();

    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();

    assertThat(fakeTextureManager.signalEndOfCurrentInputStreamCalled).isTrue();
  }

  @Test
  public void samplingShaderProgram_onOutputFrameAvailable_queuesDownstream() throws Exception {
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);
    GlTextureInfo outputTextureInfo = new GlTextureInfo(1, -1, -1, WIDTH, HEIGHT);
    processor.getInput().queueFrame(inputFrame);

    fakeSampler.outputListener.onOutputFrameAvailable(outputTextureInfo, 2000L);

    fakeFrameConsumer.awaitFrame(TEST_TIMEOUT);
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.glTextureInfo).isSameInstanceAs(outputTextureInfo);
    assertThat(outputFrame.presentationTimeUs).isEqualTo(2000L);
    assertThat(outputFrame.format.colorInfo).isEqualTo(SDR_BT709_LIMITED);
  }

  @Test
  public void samplingShaderProgram_onCurrentOutputStreamEnded_allowsNextFrameToBeQueued()
      throws Exception {
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);
    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();
    assertThat(processor.getInput().queueFrame(inputFrame)).isFalse();

    fakeSampler.outputListener.onCurrentOutputStreamEnded();

    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();
  }

  @Test
  public void samplingShaderProgram_onCurrentOutputStreamEnded_notifiesCapacityListener()
      throws Exception {
    CountDownLatch onCapacityAvailableLatch = new CountDownLatch(1);
    FakeCapacityListener fakeCapacityListener =
        new FakeCapacityListener(onCapacityAvailableLatch::countDown);
    processor
        .getInput()
        .setOnCapacityAvailableCallback(
            glThreadExecutorService, fakeCapacityListener::onCapacityAvailable);
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);
    assertThat(processor.getInput().queueFrame(inputFrame)).isTrue();
    assertThat(processor.getInput().queueFrame(inputFrame)).isFalse();

    fakeSampler.outputListener.onCurrentOutputStreamEnded();

    assertThat(onCapacityAvailableLatch.await(TEST_TIMEOUT, MILLISECONDS)).isTrue();
  }

  @Test
  public void processor_onDownstreamCapacityAvailable_queuesDownstream() throws Exception {
    fakeFrameConsumer.acceptFrames = false;
    BitmapFrame inputFrame = createTestFrame(SRGB_BT709_FULL);
    GlTextureInfo outputTextureInfo = new GlTextureInfo(1, -1, -1, WIDTH, HEIGHT);
    processor.getInput().queueFrame(inputFrame);
    fakeSampler.outputListener.onOutputFrameAvailable(outputTextureInfo, 2000L);
    assertThat(fakeFrameConsumer.receivedFrames).isEmpty();

    fakeFrameConsumer.acceptFrames = true;
    fakeFrameConsumer.notifyCallbackListener();

    fakeFrameConsumer.awaitFrame(TEST_TIMEOUT);
    assertThat(fakeFrameConsumer.receivedFrames).hasSize(1);
    GlTextureFrame outputFrame = fakeFrameConsumer.receivedFrames.get(0);
    assertThat(outputFrame.glTextureInfo).isSameInstanceAs(outputTextureInfo);
    assertThat(outputFrame.presentationTimeUs).isEqualTo(2000L);
    assertThat(outputFrame.format.colorInfo).isEqualTo(SDR_BT709_LIMITED);
  }

  @Test
  public void getInput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(IllegalStateException.class, () -> processor.getInput());
  }

  @Test
  public void queueFrame_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    FrameConsumer<BitmapFrame> consumer = processor.getInput();
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(
        IllegalStateException.class, () -> consumer.queueFrame(createTestFrame(SRGB_BT709_FULL)));
  }

  @Test
  public void setOutput_afterReleaseStarted_throwsIllegalStateException() throws Exception {
    ListenableFuture<Void> unused = processor.releaseAsync();

    assertThrows(IllegalStateException.class, () -> processor.setOutputAsync(fakeFrameConsumer));
  }

  private BitmapFrame createTestFrame(ColorInfo colorInfo) {
    Bitmap testBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Config.ARGB_8888);
    Format testFormat =
        new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).setColorInfo(colorInfo).build();
    BitmapFrame.Metadata metadata = new Metadata(1000L, testFormat);
    return new BitmapFrame(testBitmap, metadata);
  }

  private static final class FakeCapacityListener {
    private final Runnable onOnCapacityAvailable;

    public FakeCapacityListener(Runnable onOnCapacityAvailable) {
      this.onOnCapacityAvailable = onOnCapacityAvailable;
    }

    public void onCapacityAvailable() {
      onOnCapacityAvailable.run();
    }
  }

  private static final class FakeTextureManager extends TextureManager {

    public final List<Bitmap> queuedBitmaps;
    public final List<FrameInfo> queuedFrameInfos;
    public final List<TimestampIterator> queuedTimestampIterators;
    public @MonotonicNonNull GlShaderProgram samplingGlShaderProgram;
    public boolean signalEndOfCurrentInputStreamCalled;
    public boolean releaseCalled;

    public FakeTextureManager(VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor) {
      super(videoFrameProcessingTaskExecutor);
      queuedBitmaps = new ArrayList<>();
      queuedFrameInfos = new ArrayList<>();
      queuedTimestampIterators = new ArrayList<>();
    }

    @Override
    public void queueInputBitmap(
        Bitmap inputBitmap, FrameInfo frameInfo, TimestampIterator inStreamOffsetsUs) {
      queuedBitmaps.add(inputBitmap);
      queuedFrameInfos.add(frameInfo);
      queuedTimestampIterators.add(inStreamOffsetsUs);
    }

    @Override
    public void setSamplingGlShaderProgram(GlShaderProgram samplingGlShaderProgram) {
      this.samplingGlShaderProgram = samplingGlShaderProgram;
    }

    @Override
    public int getPendingFrameCount() {
      return 0;
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      signalEndOfCurrentInputStreamCalled = true;
    }

    @Override
    public void release() {
      if (releaseCalled) {
        throw new IllegalStateException("Already released");
      }
      releaseCalled = true;
    }
  }
}
