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
package androidx.media3.exoplayer.source;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExternallyLoadedMediaPeriod}. */
@RunWith(AndroidJUnit4.class)
public final class ExternallyLoadedMediaPeriodTest {

  private static final Uri TEST_URI = Uri.parse("http://example.com/image.jpg");

  @Test
  public void setEndPositionUs_positive_returnsEndPositionAndReadsSample() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();
    SampleStream stream = selectSingleTrack(mediaPeriod);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(/* endPositionUs= */ 1_000_000L);
    int formatResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    int sampleResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    long sampleTimeUs = buffer.timeUs;
    boolean sampleIsEndOfStream = buffer.isEndOfStream();
    buffer.clear();
    int eosResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    boolean eosFlagSet = buffer.isEndOfStream();
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(1_000_000L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(formatHolder.format.sampleMimeType)
        .isEqualTo(MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_zero_returnsZeroAndReadsSample() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();
    SampleStream stream = selectSingleTrack(mediaPeriod);
    FormatHolder formatHolder = new FormatHolder();
    DecoderInputBuffer buffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(/* endPositionUs= */ 0L);
    int formatResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    int sampleResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    long sampleTimeUs = buffer.timeUs;
    boolean sampleIsEndOfStream = buffer.isEndOfStream();
    buffer.clear();
    int eosResult = stream.readData(formatHolder, buffer, /* readFlags= */ 0);
    boolean eosFlagSet = buffer.isEndOfStream();
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(0L);
    assertThat(formatResult).isEqualTo(C.RESULT_FORMAT_READ);
    assertThat(sampleResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(sampleTimeUs).isEqualTo(0L);
    assertThat(sampleIsEndOfStream).isFalse();
    assertThat(eosResult).isEqualTo(C.RESULT_BUFFER_READ);
    assertThat(eosFlagSet).isTrue();
  }

  @Test
  public void setEndPositionUs_endOfSource_returnsEndOfSource() {
    ExternallyLoadedMediaPeriod mediaPeriod = createPreparedLoadedMediaPeriod();

    long actualEndPositionUs = mediaPeriod.setEndPositionUs(C.TIME_END_OF_SOURCE);
    mediaPeriod.releasePeriod();

    assertThat(actualEndPositionUs).isEqualTo(C.TIME_END_OF_SOURCE);
  }

  private static ExternallyLoadedMediaPeriod createPreparedLoadedMediaPeriod() {
    ExternallyLoadedMediaPeriod mediaPeriod =
        new ExternallyLoadedMediaPeriod(
            TEST_URI,
            MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE,
            loadRequest -> Futures.immediateVoidFuture());
    mediaPeriod.prepare(
        new MediaPeriod.Callback() {
          @Override
          public void onPrepared(MediaPeriod period) {}

          @Override
          public void onContinueLoadingRequested(MediaPeriod source) {}
        },
        /* positionUs= */ 0L);
    return mediaPeriod;
  }

  private static SampleStream selectSingleTrack(ExternallyLoadedMediaPeriod mediaPeriod) {
    SampleStream[] streams = new SampleStream[1];
    assertThat(
            mediaPeriod.selectTracks(
                new ExoTrackSelection[] {
                  new FixedTrackSelection(mediaPeriod.getTrackGroups().get(0), /* track= */ 0)
                },
                /* mayRetainStreamFlags= */ new boolean[] {false},
                streams,
                /* streamResetFlags= */ new boolean[] {false},
                /* positionUs= */ 0L))
        .isEqualTo(0L);
    return streams[0];
  }
}
