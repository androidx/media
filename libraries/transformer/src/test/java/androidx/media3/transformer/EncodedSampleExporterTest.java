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
package androidx.media3.transformer;

import static androidx.media3.common.C.BUFFER_FLAG_END_OF_STREAM;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.EncodedSampleExporter.ALLOCATION_SIZE_TARGET_BYTES;
import static androidx.media3.transformer.EncodedSampleExporter.MAX_INPUT_BUFFER_COUNT;
import static androidx.media3.transformer.EncodedSampleExporter.MIN_INPUT_BUFFER_COUNT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Unit tests for {@link EncodedSampleExporter}. */
@RunWith(AndroidJUnit4.class)
public final class EncodedSampleExporterTest {

  private EditedMediaItem emptyEditedMediaItem;
  private Format format;
  private EncodedSampleExporter encodedSampleExporter;

  @Mock private ListenerSet.IterationFinishedEvent<Transformer.Listener> mockIterationFinishedEvent;
  @Mock private HandlerWrapper mockHandlerWrapper;

  @Before
  public void setUp() {
    emptyEditedMediaItem = new EditedMediaItem.Builder(MediaItem.EMPTY).build();
    format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
    Looper looper = checkNotNull(Looper.myLooper());
    FallbackListener fallbackListener =
        new FallbackListener(
            new Composition.Builder(withAudioFrom(ImmutableList.of(emptyEditedMediaItem))).build(),
            new ListenerSet<>(looper, Clock.DEFAULT, mockIterationFinishedEvent),
            mockHandlerWrapper,
            new TransformationRequest.Builder().build());
    fallbackListener.setTrackCount(1);
    encodedSampleExporter =
        new EncodedSampleExporter(
            format,
            new TransformationRequest.Builder().build(),
            new MuxerWrapper(
                /* outputPath= */ "unused",
                new InAppMp4Muxer.Factory(),
                mock(MuxerWrapper.Listener.class),
                MuxerWrapper.MUXER_MODE_DEFAULT,
                /* dropSamplesBeforeFirstVideoSample= */ false,
                /* appendVideoFormat= */ null),
            fallbackListener,
            /* initialTimestampOffsetUs= */ 0);
  }

  @Test
  public void queueInput_withEmptyBuffers_allocatesMaxBufferCount() {
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    for (int i = 0; i < MAX_INPUT_BUFFER_COUNT; i++) {
      @Nullable DecoderInputBuffer decoderInputBuffer = encodedSampleExporter.getInputBuffer();
      assertThat(decoderInputBuffer).isNotNull();
      decoderInputBuffer.ensureSpaceForWrite(/* length= */ 0);
      encodedSampleExporter.queueInputBuffer();
    }
    assertThat(encodedSampleExporter.getInputBuffer()).isNull();
  }

  @Test
  public void queueInput_withSmallBuffers_allocatesMaxBufferCount() {
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 1))
        .isEqualTo(MAX_INPUT_BUFFER_COUNT);
  }

  @Test
  public void queueInput_withMediumBuffers_reachesBufferSizeTarget() {
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 16 * 1024))
        .isEqualTo(ALLOCATION_SIZE_TARGET_BYTES);
  }

  @Test
  public void queueInput_withLargeBuffers_allocatesMinBufferCount() {
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 1024 * 1024))
        .isEqualTo(MIN_INPUT_BUFFER_COUNT * 1024 * 1024);
  }

  @Test
  public void queueInputToLimitThenProcessOutput_queueInputSucceeds() {
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    // Queue input until no more input is accepted.
    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 16 * 1024))
        .isEqualTo(ALLOCATION_SIZE_TARGET_BYTES);
    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 1024 * 1024)).isEqualTo(0);

    // Simulate draining to the muxer.
    while (encodedSampleExporter.getMuxerInputBuffer() != null) {
      encodedSampleExporter.releaseMuxerInputBuffer();
    }

    // It's possible to queue input again.
    assertThat(fillInputAndGetTotalInputSize(/* inputBufferSizeBytes= */ 16 * 1024))
        .isEqualTo(ALLOCATION_SIZE_TARGET_BYTES);
  }

  @Test
  public void queueInput_withMultipleMediaItems_skipsBuffersExceedingDuration() {
    // Initialize with the first media item, not the last.
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ false,
        /* positionOffsetUs= */ 0);

    // Buffer within duration
    DecoderInputBuffer buffer1 = encodedSampleExporter.getInputBuffer();
    buffer1.timeUs = 50;
    buffer1.data = ByteBuffer.allocate(10);
    encodedSampleExporter.queueInputBuffer();

    // Buffer exceeding duration - This buffer and all upcoming buffer from the same MediaItem
    // should be skipped (in case they reference this buffer).
    DecoderInputBuffer buffer2 = encodedSampleExporter.getInputBuffer();
    buffer2.timeUs = 150;
    buffer2.data = ByteBuffer.allocate(10);
    encodedSampleExporter.queueInputBuffer();

    // Buffer within duration - Should be skipped
    DecoderInputBuffer buffer3 = encodedSampleExporter.getInputBuffer();
    buffer3.timeUs = 75;
    buffer3.data = ByteBuffer.allocate(10);
    encodedSampleExporter.queueInputBuffer();

    // Drain and check timestamps
    DecoderInputBuffer outputBuffer = encodedSampleExporter.getMuxerInputBuffer();
    assertThat(outputBuffer).isSameInstanceAs(buffer1);
    assertThat(outputBuffer.timeUs).isEqualTo(50);
    encodedSampleExporter.releaseMuxerInputBuffer();

    assertThat(encodedSampleExporter.getMuxerInputBuffer()).isNull();

    // Now add the second media item (which is the last). nextMediaItemOffsetUs becomes 100.
    encodedSampleExporter.onMediaItemChanged(
        emptyEditedMediaItem,
        /* durationUs= */ 100,
        format,
        /* isLast= */ true,
        /* positionOffsetUs= */ 0);

    // Buffer time 50, so final time is 100 + 50 = 150
    DecoderInputBuffer buffer4 = encodedSampleExporter.getInputBuffer();
    buffer4.timeUs = 50;
    buffer4.data = ByteBuffer.allocate(10);
    encodedSampleExporter.queueInputBuffer();

    // Buffer time 150, so final time is 100 + 150 = 250. Not skipped as it's the last item.
    DecoderInputBuffer buffer5 = encodedSampleExporter.getInputBuffer();
    buffer5.timeUs = 150;
    buffer5.data = ByteBuffer.allocate(10);
    encodedSampleExporter.queueInputBuffer();

    // End-of-stream buffer
    DecoderInputBuffer buffer6 = encodedSampleExporter.getInputBuffer();
    buffer6.addFlag(BUFFER_FLAG_END_OF_STREAM);
    encodedSampleExporter.queueInputBuffer();

    // Drain and check timestamps
    outputBuffer = encodedSampleExporter.getMuxerInputBuffer();
    assertThat(outputBuffer).isSameInstanceAs(buffer4);
    assertThat(outputBuffer.timeUs).isEqualTo(150);
    encodedSampleExporter.releaseMuxerInputBuffer();

    outputBuffer = encodedSampleExporter.getMuxerInputBuffer();
    assertThat(outputBuffer).isSameInstanceAs(buffer5);
    assertThat(outputBuffer.timeUs).isEqualTo(250);
    encodedSampleExporter.releaseMuxerInputBuffer();

    assertThat(encodedSampleExporter.getMuxerInputBuffer()).isNull();
    assertThat(encodedSampleExporter.isMuxerInputEnded()).isTrue();
  }

  private long fillInputAndGetTotalInputSize(int inputBufferSizeBytes) {
    int totalAllocatedSize = 0;
    for (int i = 0; i < MAX_INPUT_BUFFER_COUNT + 1; i++) {
      @Nullable DecoderInputBuffer decoderInputBuffer = encodedSampleExporter.getInputBuffer();
      if (decoderInputBuffer == null) {
        return totalAllocatedSize;
      }
      decoderInputBuffer.ensureSpaceForWrite(inputBufferSizeBytes);
      encodedSampleExporter.queueInputBuffer();
      totalAllocatedSize += inputBufferSizeBytes;
    }
    throw new IllegalStateException("Unexpectedly allocated more than MAX_INPUT_BUFFER_COUNT");
  }
}
