/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runLooperUntil;
import static androidx.media3.transformer.SequenceFrameReader.CAPACITY;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.getIt35SampleDataByTimeUs;
import static androidx.media3.transformer.TransformerUtil.releaseIfNeeded;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeHardwareBufferJniWrapper;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.transformer.AssetLoader.CompositionSettings;
import androidx.media3.transformer.TestUtil.FakeImageReaderAdapterFactory;
import androidx.media3.transformer.TestUtil.MetadataRecordingAssetLoaderListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link SequenceAssetLoader}. */
@RunWith(AndroidJUnit4.class)
public class SequenceAssetLoaderTest {

  @Rule
  public final ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(
              ShadowMediaCodecConfig.CODEC_INFO_AV1, ShadowMediaCodecConfig.CODEC_INFO_AVC),
          /* encoders= */ ImmutableList.of());

  private @Nullable SequenceAssetLoader sequenceAssetLoader;
  private @Nullable HardwareBufferSampleConsumer sampleConsumer;

  @After
  public void tearDown() {
    if (sequenceAssetLoader != null) {
      sequenceAssetLoader.release();
    }
    if (sampleConsumer != null) {
      sampleConsumer.release();
    }
  }

  @Test
  @Config(minSdk = 37) // HAGC tracks are only merged into the video samples from API 37.
  public void sequenceAssetLoader_withTwoIt35Items_forwardsHagcPayloadsToSampleConsumer()
      throws Exception {
    String it35FilePath = "mp4/sample_with_it35_track.mp4";
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + it35FilePath)).build();
    Context context = ApplicationProvider.getApplicationContext();
    AssetLoader.Factory assetLoaderFactory =
        new ExoPlayerAssetLoader.Factory(
            context, new DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT);
    MetadataRecordingAssetLoaderListener listener = new MetadataRecordingAssetLoaderListener();
    SequenceAssetLoader sequenceAssetLoader =
        new SequenceAssetLoader(
            EditedMediaItemSequence.withVideoFrom(
                ImmutableList.of(editedMediaItem, editedMediaItem)),
            assetLoaderFactory,
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false),
            listener,
            Clock.DEFAULT,
            Looper.myLooper());

    sequenceAssetLoader.start();
    runLooperUntil(
        Looper.myLooper(),
        () -> {
          ShadowSystemClock.advanceBy(Duration.ofMillis(10));
          return listener.videoInputEnded.get() || listener.exception.get() != null;
        });

    assertThat(listener.exception.get()).isNull();
    assertThat(listener.metadataByFrameTimeUs).isNotEmpty();
    ImmutableMap<Long, byte[]> it35SampleDataByTimeUs =
        getIt35SampleDataByTimeUs("media/" + it35FilePath);
    // The frames of each item are registered with their presentation times in the item, so the
    // frames of the second item replace those of the first item in the map.
    for (Map.Entry<Long, ImmutableMap<String, Object>> entry :
        listener.metadataByFrameTimeUs.entrySet()) {
      assertWithMessage("HAGC payload of the frame at %s us mismatches", entry.getKey())
          .that(entry.getValue().get(Frame.KEY_HDR_ST2094_50_INFO))
          .isEqualTo(it35SampleDataByTimeUs.get(entry.getKey()));
    }
  }

  @Test
  public void start_consumerAtCapacity_wakesUpOnFrameRelease() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Looper looper = checkNotNull(Looper.myLooper());
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    HandlerWrapper listenerHandler = clock.createHandler(looper, /* callback= */ null);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri)).build();
    EditedMediaItemSequence sequence =
        EditedMediaItemSequence.withVideoFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    List<AsyncFrame> receivedFrames = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<ExportException> exceptionRef = new AtomicReference<>();
    AtomicBoolean stalledAtCapacity = new AtomicBoolean();
    sampleConsumer =
        new HardwareBufferSampleConsumer(
            composition,
            /* sequenceIndex= */ 0,
            /* initialTimestampOffsetUs= */ 0,
            /* playbackLooper= */ looper,
            listenerHandler,
            /* frameConsumer= */ receivedFrames::add,
            /* errorConsumer= */ exceptionRef::set,
            new FakeHardwareBufferJniWrapper(),
            new FakeImageReaderAdapterFactory());
    SampleConsumer stallTrackingConsumer =
        new ForwardingVideoSampleConsumer(
            sampleConsumer,
            /* onFrameRejected= */ () -> {
              if (receivedFrames.size() >= CAPACITY) {
                listenerHandler.post(() -> stalledAtCapacity.set(true));
              }
            });
    sequenceAssetLoader =
        new SequenceAssetLoader(
            sequence,
            new ExoPlayerAssetLoader.Factory(
                context, new DefaultDecoderFactory.Builder(context).build(), clock),
            new CompositionSettings(
                Composition.HDR_MODE_KEEP_HDR, /* retainHdrFromUltraHdrImage= */ false),
            new SingleConsumerAssetLoaderListener(stallTrackingConsumer, exceptionRef),
            clock,
            looper);
    sequenceAssetLoader.addOnMediaItemChangedListener(sampleConsumer, C.TRACK_TYPE_VIDEO);
    sequenceAssetLoader.start();
    advanceClockUntil(
        clock,
        looper,
        listenerHandler,
        () -> stalledAtCapacity.get() || exceptionRef.get() != null);
    int frameCountAtCapacity = receivedFrames.size();

    releaseIfNeeded(receivedFrames.get(0).frame, /* releaseFence= */ null);
    runLooperUntil(looper, () -> receivedFrames.size() > CAPACITY || exceptionRef.get() != null);

    assertThat(exceptionRef.get()).isNull();
    assertThat(frameCountAtCapacity).isEqualTo(CAPACITY);
    // When consumer capacity is full, ExoPlayer stops until either the clock advances or
    // wakeupListener is called. Since clock was not advanced after releasing frame 0, receiving
    // one more frame proves wakeupListener was called.
    assertThat(receivedFrames).hasSize(CAPACITY + 1);
  }

  private static void advanceClockUntil(
      FakeClock clock, Looper looper, HandlerWrapper handler, Supplier<Boolean> condition)
      throws Exception {
    AtomicBoolean isClockIdle = new AtomicBoolean(true);
    runLooperUntil(
        looper,
        () -> {
          if (!condition.get() && isClockIdle.compareAndSet(true, false)) {
            clock.advanceTime(/* timeDiffMs= */ 2);
            handler.post(() -> isClockIdle.set(true));
          }
          return condition.get() && isClockIdle.get();
        });
  }

  private static final class ForwardingVideoSampleConsumer implements SampleConsumer {

    private final SampleConsumer sampleConsumer;
    private final Runnable onFrameRejected;

    ForwardingVideoSampleConsumer(SampleConsumer sampleConsumer, Runnable onFrameRejected) {
      this.sampleConsumer = sampleConsumer;
      this.onFrameRejected = onFrameRejected;
    }

    @Override
    public Surface getInputSurface() {
      return sampleConsumer.getInputSurface();
    }

    @Override
    public int getPendingVideoFrameCount() {
      return sampleConsumer.getPendingVideoFrameCount();
    }

    @Override
    public boolean registerVideoFrame(
        long presentationTimeUs, ImmutableMap<String, Object> additionalMetadata) {
      boolean registered =
          sampleConsumer.registerVideoFrame(presentationTimeUs, additionalMetadata);
      if (!registered) {
        onFrameRejected.run();
      }
      return registered;
    }

    @Override
    public void signalEndOfVideoInput() {
      sampleConsumer.signalEndOfVideoInput();
    }

    @Override
    public boolean setWakeupListener(@Nullable Runnable wakeupListener) {
      return sampleConsumer.setWakeupListener(wakeupListener);
    }
  }

  private static final class SingleConsumerAssetLoaderListener implements AssetLoader.Listener {

    private final SampleConsumer sampleConsumer;
    private final AtomicReference<ExportException> exceptionRef;

    private SingleConsumerAssetLoaderListener(
        SampleConsumer sampleConsumer, AtomicReference<ExportException> exceptionRef) {
      this.sampleConsumer = sampleConsumer;
      this.exceptionRef = exceptionRef;
    }

    @Override
    public void onDurationUs(long durationUs) {}

    @Override
    public void onTrackCount(int trackCount) {}

    @Override
    public boolean onTrackAdded(Format inputFormat, int supportedOutputTypes) {
      return true;
    }

    @Override
    public SampleConsumer onOutputFormat(Format format) {
      return sampleConsumer;
    }

    @Override
    public void onError(ExportException exportException) {
      exceptionRef.set(exportException);
    }
  }
}
