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

import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.common.MimeTypes.VIDEO_H265;
import static androidx.media3.transformer.MuxerWrapper.MUXER_MODE_DEFAULT;
import static androidx.media3.transformer.MuxerWrapper.MUXER_MODE_MUX_PARTIAL;
import static androidx.media3.transformer.TestUtil.FAKE_AUDIO_TRACK_FORMAT;
import static androidx.media3.transformer.TestUtil.FAKE_VIDEO_TRACK_FORMAT;
import static androidx.media3.transformer.TestUtil.createFakeVideoSample;
import static androidx.media3.transformer.TestUtil.getDumpFileName;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.container.Mp4TimestampData;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Unit tests for {@link MuxerWrapper}. */
@RunWith(AndroidJUnit4.class)
public class MuxerWrapperTest {
  private static final ByteBuffer FAKE_SAMPLE = createFakeVideoSample();

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule public final TestName testName = new TestName();

  private @Nullable MuxerWrapper muxerWrapper;

  @After
  public void tearDown() throws MuxerException {
    if (muxerWrapper != null) {
      // Release with reason cancellation so that underlying resources are always released.
      muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);
    }
  }

  @Test
  public void setAdditionalRotationDegrees_sameRotationSetAfterTracksAdded_doesNotThrow()
      throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setAdditionalRotationDegrees(90);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.setAdditionalRotationDegrees(180);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    muxerWrapper.setAdditionalRotationDegrees(180);
  }

  @Test
  public void setAdditionalRotationDegrees_differentRotationSetAfterTracksAdded_throws()
      throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setAdditionalRotationDegrees(90);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.setAdditionalRotationDegrees(180);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);

    assertThrows(IllegalStateException.class, () -> muxerWrapper.setAdditionalRotationDegrees(90));
  }

  @Test
  public void changeToAppendMode_afterDefaultMode_throws() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);

    assertThrows(IllegalStateException.class, muxerWrapper::changeToAppendMode);
  }

  @Test
  public void constructor_withAppendVideoFormatMissingInPartialMode_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            muxerWrapper =
                new MuxerWrapper(
                    temporaryFolder.newFile().getPath(),
                    new DefaultMuxer.Factory(),
                    new NoOpMuxerListenerImpl(),
                    MUXER_MODE_MUX_PARTIAL,
                    /* dropSamplesBeforeFirstVideoSample= */ false,
                    /* appendVideoFormat= */ null));
  }

  @Test
  public void addTrackFormat_withSameVideoFormatInAppendMode_doesNotThrow() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);

    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);

    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
  }

  @Test
  public void addTrackFormat_withSameAudioFormatInAppendMode_doesNotThrow() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_AUDIO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);

    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
  }

  @Test
  public void addTrackFormat_withDifferentVideoFormatInAppendMode_throws() throws Exception {
    Format differentVideoFormat = FAKE_VIDEO_TRACK_FORMAT.buildUpon().setHeight(5000).build();
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ differentVideoFormat);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);

    assertThrows(
        MuxerWrapper.AppendTrackFormatException.class,
        () -> muxerWrapper.addTrackFormat(differentVideoFormat));
  }

  @Test
  public void addTrackFormat_withCompatibleVideoFormatInAppendMode_savesTheMostCompatibleInitData()
      throws Exception {
    byte[] newSpsTestData = FAKE_VIDEO_TRACK_FORMAT.initializationData.get(0).clone();
    int spsLevelIndex = 7;
    byte lowSpsLevel = 11;
    newSpsTestData[spsLevelIndex] = lowSpsLevel;
    Format differentVideoFormat =
        FAKE_VIDEO_TRACK_FORMAT
            .buildUpon()
            .setInitializationData(
                ImmutableList.of(newSpsTestData, FAKE_VIDEO_TRACK_FORMAT.initializationData.get(1)))
            .build();
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ differentVideoFormat);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(differentVideoFormat);

    assertThat(
            muxerWrapper
                .getTrackFormat(C.TRACK_TYPE_VIDEO)
                .initializationDataEquals(FAKE_VIDEO_TRACK_FORMAT))
        .isTrue();
  }

  @Test
  public void addTrackFormat_withDifferentAudioFormatInAppendMode_throws() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_AUDIO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);
    Format differentAudioFormat = FAKE_AUDIO_TRACK_FORMAT.buildUpon().setSampleRate(48000).build();

    assertThrows(
        MuxerWrapper.AppendTrackFormatException.class,
        () -> muxerWrapper.addTrackFormat(differentAudioFormat));
  }

  @Test
  public void
      writeSample_dropSamplesBeforeFirstVideoSampleEnabled_rejectsAudioSamplesReceivedBeforeFirstVideoSample()
          throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ true,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(2);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);

    assertThat(
            writeSample(
                muxerWrapper,
                C.TRACK_TYPE_AUDIO,
                FAKE_SAMPLE.duplicate(),
                /* isKeyFrame= */ true,
                /* presentationTimeUs= */ 0))
        .isFalse();
  }

  @Test
  public void
      writeSample_dropSamplesBeforeFirstVideoSampleEnabled_dropsAudioSamplesTimedBeforeFirstVideoSample()
          throws Exception {
    String testId = testName.getMethodName();
    Context context = ApplicationProvider.getApplicationContext();
    CapturingMuxer.Factory muxerFactory = new CapturingMuxer.Factory(/* handleAudioAsPcm= */ false);
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            muxerFactory,
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ true,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(2);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);

    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 10);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 5);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 10);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 12);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 15);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 17);
    muxerWrapper.endTrack(C.TRACK_TYPE_AUDIO);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper = null;

    DumpFileAsserts.assertOutput(
        context,
        muxerFactory.getCreatedMuxer(),
        getDumpFileName(/* originalFileName= */ "testspecificdumps/" + testId));
  }

  @Test
  public void isEnded_afterPartialVideoMuxed_returnsTrue() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);

    assertThat(muxerWrapper.isEnded()).isTrue();
    muxerWrapper = null;
  }

  @Test
  public void isEnded_afterPartialAudioAndVideoMuxed_returnsTrue() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);

    muxerWrapper.setTrackCount(2);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.addTrackFormat(FAKE_AUDIO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);

    assertThat(muxerWrapper.isEnded()).isFalse();

    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_AUDIO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_AUDIO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);

    assertThat(muxerWrapper.isEnded()).isTrue();
    muxerWrapper = null;
  }

  @Test
  public void isEnded_afterEnteringAppendMode_returnsFalse() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper.changeToAppendMode();
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);

    assertThat(muxerWrapper.isEnded()).isFalse();
  }

  @Test
  public void getMostCompatibleFormat_h265_differentCSD_returnsNull() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H265)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 0}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H265)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}))
            .build();

    assertThat(MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat))
        .isNull();
  }

  @Test
  public void getMostCompatibleFormat_h265_matchingCSD_returnsFormat() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H265)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H265)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}))
            .build();

    List<byte[]> initializationData =
        MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat);

    assertThat(initializationData).hasSize(1);
    Byte[] expectedInitializationData = new Byte[] {1, 2, 3, 4};
    assertThat(initializationData.get(0))
        .asList()
        .containsExactlyElementsIn(expectedInitializationData)
        .inOrder();
  }

  @Test
  public void getMostCompatibleFormat_h264_matchingCSD_returnsFormat() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3, 4}))
            .build();

    List<byte[]> initializationData =
        MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat);

    assertThat(initializationData).hasSize(1);
    Byte[] expectedInitializationData = new Byte[] {1, 2, 3, 4};
    assertThat(initializationData.get(0))
        .asList()
        .containsExactlyElementsIn(expectedInitializationData)
        .inOrder();
  }

  @Test
  public void getMostCompatibleFormat_h264_differentLevel_returnsFormat() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 100, 0, 40}, new byte[] {0, 0, 0, 1}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 100, 0, 41}, new byte[] {0, 0, 0, 1}))
            .build();

    List<byte[]> initializationData =
        MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat);

    assertThat(initializationData).hasSize(2);
    Byte[] expectedInitializationDataSps = new Byte[] {0, 0, 0, 1, 103, 100, 0, 41};
    assertThat(initializationData.get(0))
        .asList()
        .containsExactlyElementsIn(expectedInitializationDataSps)
        .inOrder();
    Byte[] expectedInitializationDataPps = new Byte[] {0, 0, 0, 1};
    assertThat(initializationData.get(1))
        .asList()
        .containsExactlyElementsIn(expectedInitializationDataPps)
        .inOrder();
  }

  @Test
  public void getMostCompatibleFormat_h264_mismatchProfile_returnsNull() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 110, 0, 41}, new byte[] {0, 0, 0, 1}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 100, 0, 41}, new byte[] {0, 0, 0, 1}))
            .build();

    assertThat(MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat))
        .isNull();
  }

  @Test
  public void getMostCompatibleFormat_h264_missingMimeType_returnsNull() {
    Format existingFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 100, 0, 40}, new byte[] {0, 0, 0, 1}))
            .build();
    Format otherFormat =
        new Format.Builder()
            .setInitializationData(
                ImmutableList.of(new byte[] {0, 0, 0, 1, 103, 100, 0, 41}, new byte[] {0, 0, 0, 1}))
            .build();

    assertThat(MuxerWrapper.getMostCompatibleInitializationData(existingFormat, otherFormat))
        .isNull();
  }

  @Test
  public void release_withReasonCompletedInMuxPartialMode_doesNotReleaseResources()
      throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);

    // Resources are not released and samples can be written in the append mode.
    muxerWrapper.changeToAppendMode();
    boolean sampleWritten =
        writeSample(
            muxerWrapper,
            C.TRACK_TYPE_VIDEO,
            FAKE_SAMPLE.duplicate(),
            /* isKeyFrame= */ true,
            /* presentationTimeUs= */ 100);
    assertThat(sampleWritten).isTrue();
  }

  @Test
  public void release_withReleaseReasonCancelledInMuxPartialMode_releasesResources()
      throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);

    // Resources are released and samples can not be written in the append mode.
    muxerWrapper.changeToAppendMode();
    boolean sampleWritten =
        writeSample(
            muxerWrapper,
            C.TRACK_TYPE_VIDEO,
            FAKE_SAMPLE.duplicate(),
            /* isKeyFrame= */ true,
            /* presentationTimeUs= */ 100);
    assertThat(sampleWritten).isFalse();
    // Set muxerWrapper to null so that its not released again in tear down.
    muxerWrapper = null;
  }

  @Test
  public void release_withReleaseReasonErrorInMuxPartialMode_releasesResources() throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new DefaultMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_MUX_PARTIAL,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ FAKE_VIDEO_TRACK_FORMAT);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_ERROR);

    // Resources are released and samples can not be written in the append mode.
    muxerWrapper.changeToAppendMode();
    boolean sampleWritten =
        writeSample(
            muxerWrapper,
            C.TRACK_TYPE_VIDEO,
            FAKE_SAMPLE.duplicate(),
            /* isKeyFrame= */ true,
            /* presentationTimeUs= */ 100);
    assertThat(sampleWritten).isFalse();
    // Set muxerWrapper to null so that its not released again in tear down.
    muxerWrapper = null;
  }

  @Test
  public void finishWritingAndMaybeRelease_cancelledOnNonFrameworkMuxer_throwsException()
      throws Exception {
    Muxer.Factory throwingMuxerFactory =
        new Muxer.Factory() {
          @Override
          public Muxer create(String path) {
            return new Muxer() {
              @Override
              public int addTrack(Format format) {
                return 0;
              }

              @Override
              public void writeSampleData(
                  int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo) {}

              @Override
              public void addMetadataEntry(Metadata.Entry metadataEntry) {}

              @Override
              public void close() throws MuxerException {
                throw new MuxerException(
                    FrameworkMuxer.MUXER_STOPPING_FAILED_ERROR_MESSAGE, new Exception());
              }
            };
          }

          @Override
          public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
            return ImmutableList.of(VIDEO_H264);
          }
        };
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            throwingMuxerFactory,
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);

    assertThrows(
        MuxerException.class,
        () ->
            muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED));
    muxerWrapper = null;
  }

  @Test
  public void finishWritingAndMaybeRelease_cancelledOnFrameworkMuxer_doesNotThrow()
      throws Exception {
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            new FrameworkMuxer.Factory(),
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);
    muxerWrapper = null;
  }

  @Test
  public void finishWritingAndMaybeRelease_afterResourcesReleased_doesNotCloseMuxerAgain()
      throws Exception {
    AtomicInteger closeCount = new AtomicInteger();
    Muxer.Factory closeCountingMuxerFactory =
        new Muxer.Factory() {
          @Override
          public Muxer create(String path) {
            return new Muxer() {
              @Override
              public int addTrack(Format format) {
                return 0;
              }

              @Override
              public void writeSampleData(
                  int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo) {}

              @Override
              public void addMetadataEntry(Metadata.Entry metadataEntry) {}

              @Override
              public void close() {
                closeCount.incrementAndGet();
              }
            };
          }

          @Override
          public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
            return ImmutableList.of(VIDEO_H264);
          }
        };
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            closeCountingMuxerFactory,
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_ERROR);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_CANCELLED);

    assertThat(closeCount.get()).isEqualTo(1);
  }

  @Test
  public void addTrackFormat_withMetadataAndUnsupportedMuxer_doesNotThrow() throws Exception {
    Muxer.Factory unsupportedMetadataMuxerFactory =
        new Muxer.Factory() {
          @Override
          public Muxer create(String path) {
            return new Muxer() {
              @Override
              public int addTrack(Format format) {
                return 0;
              }

              @Override
              public void writeSampleData(
                  int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo) {}

              @Override
              public void addMetadataEntry(Metadata.Entry metadataEntry) {
                throw new UnsupportedOperationException();
              }

              @Override
              public void close() {}
            };
          }

          @Override
          public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
            return ImmutableList.of(AUDIO_AAC);
          }
        };
    Format formatWithMetadata =
        FAKE_AUDIO_TRACK_FORMAT
            .buildUpon()
            .setMetadata(
                new Metadata(
                    new Mp4TimestampData(
                        /* creationTimestampSeconds= */ 100L,
                        /* modificationTimestampSeconds= */ 200L)))
            .build();
    muxerWrapper =
        new MuxerWrapper(
            temporaryFolder.newFile().getPath(),
            unsupportedMetadataMuxerFactory,
            new NoOpMuxerListenerImpl(),
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(1);

    muxerWrapper.addTrackFormat(formatWithMetadata);

    assertThat(muxerWrapper.getTrackFormat(C.TRACK_TYPE_AUDIO)).isEqualTo(formatWithMetadata);
  }

  @Test
  public void
      finishWritingAndMaybeRelease_completedWithParcelFileDescriptor_reportsFileSizeAndClosesPfd()
          throws Exception {
    File outputFile = temporaryFolder.newFile("output.mp4");
    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE);
    NoOpMuxerListenerImpl listener = new NoOpMuxerListenerImpl();
    muxerWrapper =
        new MuxerWrapper(
            pfd,
            new DefaultMuxer.Factory(),
            listener,
            MUXER_MODE_DEFAULT,
            /* dropSamplesBeforeFirstVideoSample= */ false,
            /* appendVideoFormat= */ null);
    muxerWrapper.setTrackCount(1);
    muxerWrapper.addTrackFormat(FAKE_VIDEO_TRACK_FORMAT);
    writeSample(
        muxerWrapper,
        C.TRACK_TYPE_VIDEO,
        FAKE_SAMPLE.duplicate(),
        /* isKeyFrame= */ true,
        /* presentationTimeUs= */ 0);
    muxerWrapper.endTrack(C.TRACK_TYPE_VIDEO);

    muxerWrapper.finishWritingAndMaybeRelease(MuxerWrapper.MUXER_RELEASE_REASON_COMPLETED);
    muxerWrapper = null;

    assertThat(listener.fileSizeBytes).isEqualTo(outputFile.length());
    assertThat(pfd.getFileDescriptor().valid()).isFalse();
  }

  @CanIgnoreReturnValue
  private static boolean writeSample(
      MuxerWrapper muxerWrapper,
      @C.TrackType int trackType,
      ByteBuffer data,
      boolean isKeyFrame,
      long presentationTimeUs)
      throws MuxerException {
    DecoderInputBuffer sample =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
    sample.data = data;
    if (isKeyFrame) {
      sample.setFlags(C.BUFFER_FLAG_KEY_FRAME);
    }
    sample.timeUs = presentationTimeUs;
    return muxerWrapper.writeSample(trackType, sample);
  }

  private static final class NoOpMuxerListenerImpl implements MuxerWrapper.Listener {

    long fileSizeBytes = C.LENGTH_UNSET;

    @Override
    public void onTrackEnded(
        @C.TrackType int trackType, Format format, int averageBitrate, int sampleCount) {}

    @Override
    public void onSampleWrittenOrDropped() {}

    @Override
    public void onEnded(long approximateDurationMs) {}

    @Override
    public void onFileSizeBytesAvailable(long fileSizeBytes) {
      this.fileSizeBytes = fileSizeBytes;
    }

    @Override
    public void onError(ExportException exportException) {}
  }
}
