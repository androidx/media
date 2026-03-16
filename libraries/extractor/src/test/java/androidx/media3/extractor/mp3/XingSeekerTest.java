/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.extractor.mp3;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.SeekMap.SeekPoints;
import androidx.media3.extractor.SeekPoint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link XingSeeker}. */
@RunWith(AndroidJUnit4.class)
public final class XingSeekerTest {

  // Xing header/payload from http://storage.googleapis.com/exoplayer-test-media-0/play.mp3.
  private static final int XING_FRAME_HEADER_DATA = 0xFFFB3000;
  private static final byte[] XING_FRAME_PAYLOAD =
      Util.getBytesFromHexString(
          "00000007000008dd000e7919000205080a0d0f1214171a1c1e212426292c2e303336383b3d404245484a4c4f5254"
              + "575a5c5e616466696b6e707376787a7d808285878a8c8f929496999c9ea1a4a6a8abaeb0b3b5b8babdc0c2c4c7"
              + "cacccfd2d4d6d9dcdee1e3e6e8ebeef0f2f5f8fafd");
  private static final XingFrame XING_FRAME =
      createXingFrame(XING_FRAME_HEADER_DATA, XING_FRAME_PAYLOAD);

  private static final int XING_FRAME_POSITION = 157;

  /**
   * Duration of the audio stream in microseconds, encoded as a frame count in {@link
   * #XING_FRAME_PAYLOAD}.
   */
  private static final int XING_STREAM_DURATION_US = 59271814;

  /**
   * The position of the start of the audio data in the stream, as encoded by {@link #XING_FRAME}.
   */
  private static final long XING_AUDIO_START_POSITION =
      XING_FRAME_POSITION + XING_FRAME.header.frameSize;

  /** The position of the end of the audio data in the stream, as encoded by {@link #XING_FRAME}. */
  private static final long XING_AUDIO_END_POSITION = XING_FRAME_POSITION + XING_FRAME.dataSize;

  private XingSeeker seeker;

  @Before
  public void setUp() throws Exception {
    seeker =
        XingSeeker.create(
            XING_FRAME,
            XING_FRAME_POSITION,
            // Simulate 1000 bytes of non-MP3 junk at the end of the stream.
            /* streamLength= */ XING_AUDIO_END_POSITION + 1000);
  }

  @Test
  public void getTimeUsBeforeFirstAudioFrame() {
    assertThat(seeker.getTimeUs(XING_AUDIO_START_POSITION - 1)).isEqualTo(0);
  }

  @Test
  public void getTimeUsAtFirstAudioFrame() {
    assertThat(seeker.getTimeUs(XING_AUDIO_START_POSITION)).isEqualTo(0);
  }

  @Test
  public void getTimeUsAtEndOfStream() {
    assertThat(seeker.getTimeUs(XING_AUDIO_END_POSITION)).isEqualTo(XING_STREAM_DURATION_US);
  }

  // https://github.com/androidx/media/issues/3117#issuecomment-4046538506
  @Test
  public void getTimeUsAtEndOfStream_xingLengthLongerThanStream() {
    long streamLength = XING_AUDIO_END_POSITION - 1000;
    XingSeeker seeker = XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, streamLength);
    assertThat(seeker.getTimeUs(streamLength)).isEqualTo(XING_STREAM_DURATION_US);
  }

  @Test
  public void getTimeUsAtEndOfStream_streamLengthNotKnown() {
    XingSeeker seeker =
        XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, /* streamLength= */ C.LENGTH_UNSET);
    assertThat(seeker.getTimeUs(XING_AUDIO_END_POSITION)).isEqualTo(XING_STREAM_DURATION_US);
  }

  @Test
  public void getSeekPointsAtStartOfStream() {
    SeekPoints seekPoints = seeker.getSeekPoints(0);
    SeekPoint seekPoint = seekPoints.first;
    assertThat(seekPoint).isEqualTo(seekPoints.second);
    assertThat(seekPoint.timeUs).isEqualTo(0);
    assertThat(seekPoint.position).isEqualTo(XING_AUDIO_START_POSITION);
  }

  @Test
  public void getSeekPointsAtEndOfStream() {
    SeekPoints seekPoints = seeker.getSeekPoints(XING_STREAM_DURATION_US);
    SeekPoint seekPoint = seekPoints.first;
    assertThat(seekPoint).isEqualTo(seekPoints.second);
    assertThat(seekPoint.timeUs).isEqualTo(XING_STREAM_DURATION_US);
    assertThat(seekPoint.position).isEqualTo(XING_AUDIO_END_POSITION - 1);
  }

  // https://github.com/androidx/media/issues/3117#issuecomment-4046538506
  @Test
  public void getSeekPointsAtEndOfStream_xingLengthLongerThanStream() {
    long streamLength = XING_AUDIO_END_POSITION - 1000;
    XingSeeker seeker = XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, streamLength);
    SeekPoints seekPoints = seeker.getSeekPoints(XING_STREAM_DURATION_US);
    SeekPoint seekPoint = seekPoints.first;
    assertThat(seekPoint).isEqualTo(seekPoints.second);
    assertThat(seekPoint.timeUs).isEqualTo(XING_STREAM_DURATION_US);
    assertThat(seekPoint.position).isEqualTo(streamLength - 1);
  }

  @Test
  public void getSeekPointsAtEndOfStream_streamLengthNotKnown() {
    XingSeeker seeker =
        XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, /* streamLength= */ C.LENGTH_UNSET);
    SeekPoints seekPoints = seeker.getSeekPoints(XING_STREAM_DURATION_US);
    SeekPoint seekPoint = seekPoints.first;
    assertThat(seekPoint).isEqualTo(seekPoints.second);
    assertThat(seekPoint.timeUs).isEqualTo(XING_STREAM_DURATION_US);
    assertThat(seekPoint.position).isEqualTo(XING_AUDIO_END_POSITION - 1);
  }

  @Test
  public void getTimeForAllPositions() {
    for (long position = XING_AUDIO_START_POSITION;
        position < XING_AUDIO_END_POSITION;
        position++) {
      long timeUs = seeker.getTimeUs(position);
      SeekPoints seekPoints = seeker.getSeekPoints(timeUs);
      SeekPoint seekPoint = seekPoints.first;
      assertThat(seekPoint).isEqualTo(seekPoints.second);
      assertThat(seekPoint.position).isEqualTo(position);
    }
  }

  // https://github.com/androidx/media/issues/3117#issuecomment-4046538506
  @Test
  public void getTimeForAllPositions_xingLengthLongerThanStream() {
    long streamLength = XING_AUDIO_END_POSITION - 1000;
    XingSeeker seeker = XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, streamLength);
    for (long position = XING_AUDIO_START_POSITION; position < streamLength; position++) {
      long timeUs = seeker.getTimeUs(position);
      SeekPoints seekPoints = seeker.getSeekPoints(timeUs);
      SeekPoint seekPoint = seekPoints.first;
      assertThat(seekPoint).isEqualTo(seekPoints.second);
      assertThat(seekPoint.position).isEqualTo(position);
    }
  }

  @Test
  public void getTimeForAllPositions_streamLengthNotKnown() {
    XingSeeker seeker =
        XingSeeker.create(XING_FRAME, XING_FRAME_POSITION, /* streamLength= */ C.LENGTH_UNSET);
    for (long position = XING_AUDIO_START_POSITION;
        position < XING_AUDIO_END_POSITION;
        position++) {
      long timeUs = seeker.getTimeUs(position);
      SeekPoints seekPoints = seeker.getSeekPoints(timeUs);
      SeekPoint seekPoint = seekPoints.first;
      assertThat(seekPoint).isEqualTo(seekPoints.second);
      assertThat(seekPoint.position).isEqualTo(position);
    }
  }

  private static XingFrame createXingFrame(int header, byte[] payload) {
    MpegAudioUtil.Header xingFrameHeader = new MpegAudioUtil.Header();
    xingFrameHeader.setForHeaderData(header);
    return XingFrame.parse(xingFrameHeader, new ParsableByteArray(payload));
  }
}
