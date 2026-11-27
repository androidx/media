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

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static androidx.media3.common.video.Frame.KEY_HDR_ST2094_50_INFO;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_TARGET_LOG2_HDR_HEADROOM;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link HagcGlTextureFrameProcessor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class HagcGlTextureFrameProcessorTest {

  private static final int WIDTH = 16;
  private static final int HEIGHT = 8;
  private static final Format BT2020_LINEAR_FORMAT =
      new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).setColorInfo(BT2020_LINEAR).build();
  // Reference white tone mapping (RWTMO) with baseline log2 headroom 3.0.
  private static final String RWTMO_BASELINE_THREE_HEX = "0040753080";

  private ArrayDeque<Runnable> queuedWakeupTasks;
  private Executor wakeupExecutor;
  private FakeGlTextureFrameConsumer downstreamConsumer;
  private HagcGlTextureFrameProcessor processor;

  @Before
  public void setUp() {
    queuedWakeupTasks = new ArrayDeque<>();
    wakeupExecutor = queuedWakeupTasks::add;
    downstreamConsumer = new FakeGlTextureFrameConsumer(/* frameWriter= */ null);
    processor =
        new HagcGlTextureFrameProcessor(
            directExecutor(),
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });
    processor.setOutput(downstreamConsumer);
  }

  @After
  public void tearDown() throws Exception {
    processor.close();
  }

  @Test
  public void queue_withPassThroughMetadata_passesFrameThroughUnmodified(
      @TestParameter(valuesProvider = MetadataProvider.class) Map<String, Object> metadata)
      throws Exception {
    AtomicInteger releaseCount = new AtomicInteger();
    GlTextureFrame inputFrame = createBt2020LinearFrame(metadata, releaseCount);

    boolean queued = processor.queue(inputFrame, wakeupExecutor, /* wakeupListener= */ () -> {});

    assertThat(queued).isTrue();
    assertThat(downstreamConsumer.lastReceivedFrame).isSameInstanceAs(inputFrame);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void queue_withInvalidMetadata_throwsIllegalArgumentException(
      @TestParameter(valuesProvider = InvalidMetadataProvider.class) Map<String, Object> metadata) {
    AtomicInteger releaseCount = new AtomicInteger();
    GlTextureFrame inputFrame = createBt2020LinearFrame(metadata, releaseCount);

    assertThrows(
        IllegalArgumentException.class,
        () -> processor.queue(inputFrame, wakeupExecutor, /* wakeupListener= */ () -> {}));

    assertThat(downstreamConsumer.framesReceived).isEqualTo(0);
    assertThat(releaseCount.get()).isEqualTo(0);
  }

  @Test
  public void queue_withUnsupportedColorInfo_passesFrameThroughUnmodified(
      @TestParameter(valuesProvider = UnsupportedColorInfoProvider.class)
          @Nullable ColorInfo colorInfo)
      throws Exception {
    AtomicInteger releaseCount = new AtomicInteger();
    Format format = BT2020_LINEAR_FORMAT.buildUpon().setColorInfo(colorInfo).build();
    GlTextureFrame inputFrame =
        createFrame(
            format,
            hagcMap(
                /* payload= */ getBytesFromHexString(RWTMO_BASELINE_THREE_HEX),
                /* targetLog2Headroom= */ 0.0f),
            releaseCount);

    boolean queued = processor.queue(inputFrame, wakeupExecutor, /* wakeupListener= */ () -> {});

    assertThat(queued).isTrue();
    assertThat(downstreamConsumer.lastReceivedFrame).isSameInstanceAs(inputFrame);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void queue_withValidHagcMetadata_passesFramesThrough() throws Exception {
    AtomicInteger releaseCount = new AtomicInteger();
    GlTextureFrame firstFrame =
        createBt2020LinearFrame(
            hagcMap(
                /* payload= */ getBytesFromHexString(RWTMO_BASELINE_THREE_HEX),
                /* targetLog2Headroom= */ 1.0f),
            releaseCount);
    GlTextureFrame secondFrame =
        createBt2020LinearFrame(
            hagcMap(
                /* payload= */ getBytesFromHexString(RWTMO_BASELINE_THREE_HEX),
                /* targetLog2Headroom= */ 1.0f),
            releaseCount);

    boolean queuedFirst =
        processor.queue(firstFrame, wakeupExecutor, /* wakeupListener= */ () -> {});
    assertThat(queuedFirst).isTrue();
    assertThat(downstreamConsumer.lastReceivedFrame).isSameInstanceAs(firstFrame);

    boolean queuedSecond =
        processor.queue(secondFrame, wakeupExecutor, /* wakeupListener= */ () -> {});

    assertThat(queuedSecond).isTrue();
    assertThat(downstreamConsumer.lastReceivedFrame).isSameInstanceAs(secondFrame);
    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);
    assertThat(releaseCount.get()).isEqualTo(2);
  }

  @Test
  public void queue_whenDownstreamRejectsPassThroughFrame_forwardsWakeupListener()
      throws Exception {
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    AtomicBoolean wakeupCalled = new AtomicBoolean();
    AtomicInteger releaseCount = new AtomicInteger();
    GlTextureFrame inputFrame =
        createBt2020LinearFrame(ImmutableMap.of(KEY_TARGET_LOG2_HDR_HEADROOM, 0.0f), releaseCount);

    boolean queued =
        processor.queue(
            inputFrame, wakeupExecutor, /* wakeupListener= */ () -> wakeupCalled.set(true));
    downstreamConsumer.triggerWakeup();
    queuedWakeupTasks.remove().run();

    assertThat(queued).isFalse();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(wakeupCalled.get()).isTrue();
    assertThat(releaseCount.get()).isEqualTo(0);
  }

  @Test
  public void signalEndOfStream_afterSetOutput_forwardsToDownstreamConsumer() {
    processor.signalEndOfStream();

    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  private static GlTextureFrame createBt2020LinearFrame(
      Map<String, Object> metadata, AtomicInteger releaseCount) {
    return createFrame(BT2020_LINEAR_FORMAT, metadata, releaseCount);
  }

  private static GlTextureFrame createFrame(
      Format format, Map<String, Object> metadata, AtomicInteger releaseCount) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ 100,
                /* fboId= */ C.INDEX_UNSET,
                /* rboId= */ C.INDEX_UNSET,
                WIDTH,
                HEIGHT),
            /* releaseTextureExecutor= */ directExecutor(),
            /* releaseTextureCallback= */ unused -> releaseCount.incrementAndGet())
        .setFormat(format)
        .setMetadata(metadata)
        .build();
  }

  private static ImmutableMap<String, Object> hagcMap(byte[] payload, float targetLog2Headroom) {
    return ImmutableMap.of(
        KEY_HDR_ST2094_50_INFO, payload, KEY_TARGET_LOG2_HDR_HEADROOM, targetLog2Headroom);
  }

  private static final class MetadataProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(TestParameterValuesProvider.Context context) {
      byte[] valid = getBytesFromHexString(RWTMO_BASELINE_THREE_HEX);
      return ImmutableList.of(
          value(ImmutableMap.of()).withName("emptyMetadata"),
          value(ImmutableMap.of(KEY_HDR_ST2094_50_INFO, valid)).withName("withoutTargetHeadroom"),
          value(ImmutableMap.of(KEY_TARGET_LOG2_HDR_HEADROOM, 0.0f))
              .withName("withoutHagcMetadata"),
          value(hagcMap(/* payload= */ new byte[] {(byte) 0x40}, /* targetLog2Headroom= */ 0.0f))
              .withName("malformedHagcPayload"),
          value(
                  hagcMap(
                      /* payload= */ getBytesFromHexString("0000"), /* targetLog2Headroom= */ 0.0f))
              .withName("metadataWithoutToneMap"));
    }
  }

  private static final class InvalidMetadataProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(TestParameterValuesProvider.Context context) {
      byte[] valid = getBytesFromHexString(RWTMO_BASELINE_THREE_HEX);
      return ImmutableList.of(
          value(
                  ImmutableMap.of(
                      KEY_HDR_ST2094_50_INFO, "notBytes", KEY_TARGET_LOG2_HDR_HEADROOM, 0.0f))
              .withName("wrongTypePayload"),
          value(ImmutableMap.of(KEY_HDR_ST2094_50_INFO, valid, KEY_TARGET_LOG2_HDR_HEADROOM, 1.0d))
              .withName("wrongTypeTargetHeadroom"),
          value(hagcMap(/* payload= */ valid, /* targetLog2Headroom= */ -0.5f))
              .withName("negativeTargetHeadroom"),
          value(hagcMap(/* payload= */ valid, /* targetLog2Headroom= */ Float.NaN))
              .withName("nanTargetHeadroom"),
          value(hagcMap(/* payload= */ valid, /* targetLog2Headroom= */ Float.POSITIVE_INFINITY))
              .withName("infiniteTargetHeadroom"));
    }
  }

  private static final class UnsupportedColorInfoProvider extends TestParameterValuesProvider {
    private static final ColorInfo BT2020_PQ =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build();
    private static final ColorInfo DISPLAY_P3_LINEAR =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_DISPLAY_P3)
            .setColorTransfer(C.COLOR_TRANSFER_LINEAR)
            .build();

    @Override
    protected ImmutableList<?> provideValues(TestParameterValuesProvider.Context context) {
      return ImmutableList.of(
          value(BT2020_HLG).withName("BT2020_HLG"),
          value(BT2020_PQ).withName("BT2020_PQ"),
          value(DISPLAY_P3_LINEAR).withName("DISPLAY_P3_LINEAR"),
          value(BT709_SRGB).withName("BT709_SRGB"),
          value(BT709_LINEAR).withName("BT709_LINEAR"),
          value(null).withName("nullColorInfo"));
    }
  }
}
