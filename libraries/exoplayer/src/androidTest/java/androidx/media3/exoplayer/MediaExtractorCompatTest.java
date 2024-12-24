/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekMap.SeekPoints;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Tests for {@link MediaExtractorCompat}. */
@RunWith(AndroidJUnit4.class)
public class MediaExtractorCompatTest {

  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  /**
   * Placeholder data URI which saves us from mocking the data source which MediaExtractorCompat
   * uses.
   *
   * <p>Note: The created data source will be opened, but no data will be read from it, so the
   * contents are irrelevant.
   */
  private static final Uri PLACEHOLDER_URI = Uri.parse("data:,0123456789");

  private static final Format PLACEHOLDER_FORMAT_AUDIO =
      new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build();
  private static final Format PLACEHOLDER_FORMAT_VIDEO =
      new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

  private FakeExtractor fakeExtractor;
  private SeekMap fakeSeekMap;
  private MediaExtractorCompat mediaExtractorCompat;
  private ExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    fakeExtractor = new FakeExtractor();
    fakeSeekMap = new FakeSeekMap();
    ExtractorsFactory factory = () -> new Extractor[] {fakeExtractor};
    mediaExtractorCompat =
        new MediaExtractorCompat(
            factory, new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()));
  }

  @Test
  public void setDataSource_forEmptyContainerFile_producesZeroTracks() throws IOException {
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          extractorOutput.endTracks();
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(0);
  }

  @Test
  public void setDataSource_doesNotPerformMoreReadsThanNecessary() throws IOException {
    TrackOutput[] trackOutputs = new TrackOutput[2];
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          trackOutputs[0] = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          trackOutputs[1] = extractorOutput.track(/* id= */ 1, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          // Should be ignored. Tracks have ended and the id doesn't exist.
          extractorOutput.track(/* id= */ 2, C.TRACK_TYPE_TEXT);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          trackOutputs[0].format(PLACEHOLDER_FORMAT_AUDIO);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          // After this read call, the extractor should have enough to finish preparation:
          // formats, seek map, and the tracks have ended.
          trackOutputs[1].format(PLACEHOLDER_FORMAT_VIDEO);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(MediaExtractorCompatTest::assertionFailureReadAction);

    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);

    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
    assertThat(mediaExtractorCompat.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        .isEqualTo(PLACEHOLDER_FORMAT_AUDIO.sampleMimeType);
    assertThat(mediaExtractorCompat.getTrackFormat(1).getString(MediaFormat.KEY_MIME))
        .isEqualTo(PLACEHOLDER_FORMAT_VIDEO.sampleMimeType);
  }

  @Test
  public void setDataSource_withTrackIdReuse_reusesTrackOutput() throws IOException {
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput trackOutput1 = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
          TrackOutput trackOutput2 = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_AUDIO);
          assertThat(trackOutput1).isSameInstanceAs(trackOutput2);
          trackOutput1.format(PLACEHOLDER_FORMAT_AUDIO);
          extractorOutput.endTracks();
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
  }

  @Test
  public void readSampleData_forInterleavedInputSamples_producesExpectedSamples()
      throws IOException {
    TrackOutput[] outputs = new TrackOutput[2];
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputs[0] = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          outputs[0].format(PLACEHOLDER_FORMAT_VIDEO);
          outputs[1] = extractorOutput.track(/* id= */ 1, C.TRACK_TYPE_AUDIO);
          outputs[1].format(PLACEHOLDER_FORMAT_AUDIO);
          extractorOutput.endTracks();
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSampleData(outputs[0], /* sampleData...= */ (byte) 1, (byte) 2, (byte) 3);
          outputSampleData(outputs[1], /* sampleData...= */ (byte) 4, (byte) 5, (byte) 6);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSample(outputs[0], /* timeUs= */ 4, /* size= */ 1, /* offset= */ 2);
          outputSample(outputs[1], /* timeUs= */ 3, /* size= */ 1, /* offset= */ 2);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSample(outputs[0], /* timeUs= */ 2, /* size= */ 2, /* offset= */ 0);
          outputSample(outputs[1], /* timeUs= */ 1, /* size= */ 2, /* offset= */ 0);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(MediaExtractorCompatTest::assertionFailureReadAction);

    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);

    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
    mediaExtractorCompat.selectTrack(0);
    mediaExtractorCompat.selectTrack(1);
    assertReadSample(
        /* trackIndex= */ 0, /* timeUs= */ 4, /* size= */ 1, /* sampleData...= */ (byte) 1);
    mediaExtractorCompat.advance();
    assertReadSample(
        /* trackIndex= */ 1, /* timeUs= */ 3, /* size= */ 1, /* sampleData...= */ (byte) 4);
    mediaExtractorCompat.advance();
    assertReadSample(
        /* trackIndex= */ 0,
        /* timeUs= */ 2,
        /* size= */ 2,
        /* sampleData...= */ (byte) 2,
        (byte) 3);
    mediaExtractorCompat.advance();
    assertReadSample(
        /* trackIndex= */ 1,
        /* timeUs= */ 1,
        /* size= */ 2,
        /* sampleData...= */ (byte) 5,
        (byte) 6);
  }

  @Test
  public void getTrackFormat_atEndOfStreamWithFlagLastSample_producesATrackFormat()
      throws IOException {
    // This is a regression test for b/191518632.
    TrackOutput[] outputs = new TrackOutput[1];
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputs[0] = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          outputs[0].format(PLACEHOLDER_FORMAT_VIDEO);
          extractorOutput.endTracks();
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSampleData(outputs[0], /* sampleData...= */ (byte) 1, (byte) 2, (byte) 3);
          outputs[0].sampleMetadata(
              /* timeUs= */ 0,
              /* flags= */ C.BUFFER_FLAG_KEY_FRAME | C.BUFFER_FLAG_LAST_SAMPLE,
              /* size= */ 3,
              /* offset= */ 0,
              /* cryptoData= */ null);
          return Extractor.RESULT_END_OF_INPUT;
        });

    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);

    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(1);
    mediaExtractorCompat.selectTrack(0);
    mediaExtractorCompat.advance();
    // After skipping the only sample, there should be none left, and getSampleTime and
    // getSampleSize should return -1.
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        .isEqualTo(PLACEHOLDER_FORMAT_VIDEO.sampleMimeType);
  }

  @Test
  public void setDataSource_withOutOfMemoryError_wrapsError() throws IOException {
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          throw new OutOfMemoryError();
        });
    ParserException exception =
        assertThrows(
            ParserException.class,
            () -> mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0));
    assertThat(exception).hasCauseThat().isInstanceOf(OutOfMemoryError.class);
  }

  @Test
  public void getSampleTimeAndSize_withOutOfMemoryError_producesEndOfInput() throws IOException {
    // This boolean guarantees that this test remains useful. The throwing read action is being
    // called as a result of an implementation detail (trying to parse a container file with no
    // tracks) that could change in the future. Counting on this implementation detail simplifies
    // the test.
    AtomicBoolean outOfMemoryErrorWasThrown = new AtomicBoolean(false);
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          extractorOutput.endTracks();
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outOfMemoryErrorWasThrown.set(true);
          throw new OutOfMemoryError();
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(-1);
    assertThat(outOfMemoryErrorWasThrown.get()).isTrue();
  }

  @Test
  public void setDataSource_withDolbyVision_generatesCompatibilityTrack() throws IOException {
    TrackOutput[] outputs = new TrackOutput[1];
    byte[] sampleData = new byte[] {(byte) 1, (byte) 2, (byte) 3};
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputs[0] = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          outputs[0].format(
              new Format.Builder()
                  .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                  .setCodecs("hev1.08.10")
                  .build());
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSampleData(outputs[0], sampleData);
          outputSample(outputs[0], /* timeUs= */ 7, /* size= */ 3, /* offset= */ 0);
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
    assertThat(mediaExtractorCompat.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        .isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(mediaExtractorCompat.getTrackFormat(1).getString(MediaFormat.KEY_MIME))
        .isEqualTo(MimeTypes.VIDEO_H265);
    ByteBuffer scratchBuffer = ByteBuffer.allocate(3);

    mediaExtractorCompat.selectTrack(0);
    mediaExtractorCompat.selectTrack(1);

    assertThat(mediaExtractorCompat.getSampleTrackIndex()).isEqualTo(1);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(3);
    assertThat(mediaExtractorCompat.readSampleData(scratchBuffer, /* offset= */ 0)).isEqualTo(3);
    assertThat(scratchBuffer.array()).isEqualTo(sampleData);

    assertThat(mediaExtractorCompat.advance()).isTrue();

    assertThat(mediaExtractorCompat.getSampleTrackIndex()).isEqualTo(0);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(3);
    assertThat(mediaExtractorCompat.readSampleData(scratchBuffer, /* offset= */ 0)).isEqualTo(3);
    assertThat(scratchBuffer.array()).isEqualTo(sampleData);

    assertThat(mediaExtractorCompat.advance()).isFalse();
  }

  @Test
  public void seekTo_resetsSampleQueues() throws IOException {
    fakeExtractor.setSeekStrategy(fakeExtractor::rewindReadActions);
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput output = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          output.format(PLACEHOLDER_FORMAT_VIDEO);
          outputSampleData(output, /* sampleData...= */ (byte) 0);
          outputSample(output, /* timeUs= */ 7, /* size= */ 1, /* offset= */ 0);
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    mediaExtractorCompat.selectTrack(/* trackIndex= */ 0);
    // Calling getSampleTime forces the extractor to populate the sample queues with the first
    // sample. As a result, to pass this test, the tested implementation must clear the sample
    // queues when seekTo is called.
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
    mediaExtractorCompat.seekTo(/* timeUs= */ 0, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC);
    // Test the same sample (and only that sample) is read after the seek to the start.
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
    assertThat(mediaExtractorCompat.advance()).isFalse();
  }

  @Test
  public void readSampleData_usesOffsetArgumentInsteadOfBufferPosition() throws IOException {
    byte[] sampleData =
        new byte[] {
          (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7, (byte) 8, (byte) 9
        };
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput output = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          output.format(PLACEHOLDER_FORMAT_VIDEO);
          outputSampleData(output, sampleData);
          outputSample(output, /* timeUs= */ 1, /* size= */ 2, /* offset= */ 7);
          outputSample(output, /* timeUs= */ 2, /* size= */ 3, /* offset= */ 4);
          outputSample(output, /* timeUs= */ 3, /* size= */ 4, /* offset= */ 0);
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    mediaExtractorCompat.selectTrack(/* trackIndex= */ 0);
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    // Set the position to the limit to test that the position is ignored, like the platform media
    // extractor implementation does.
    byteBuffer.position(byteBuffer.limit());

    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(2);
    assertThat(mediaExtractorCompat.readSampleData(byteBuffer, /* offset= */ 0)).isEqualTo(2);
    assertThat(byteBuffer.position()).isEqualTo(0);
    assertThat(byteBuffer.limit()).isEqualTo(2);

    mediaExtractorCompat.advance();
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(2);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(3);
    assertThat(mediaExtractorCompat.readSampleData(byteBuffer, /* offset= */ 2)).isEqualTo(3);
    assertThat(byteBuffer.position()).isEqualTo(2);
    assertThat(byteBuffer.limit()).isEqualTo(5);

    mediaExtractorCompat.advance();
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(3);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(4);
    assertThat(mediaExtractorCompat.readSampleData(byteBuffer, /* offset= */ 5)).isEqualTo(4);
    assertThat(byteBuffer.position()).isEqualTo(5);
    assertThat(byteBuffer.limit()).isEqualTo(9);

    assertThat(byteBuffer.array()).isEqualTo(sampleData);
    assertThat(mediaExtractorCompat.advance()).isFalse();
  }

  @Test
  public void advance_releasesMemoryOfSkippedSamples() throws IOException {
    // This is a regression test for b/209801945.
    Allocator allocator = mediaExtractorCompat.getAllocator();
    int individualAllocationSize = allocator.getIndividualAllocationLength();

    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput output = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          output.format(PLACEHOLDER_FORMAT_VIDEO);
          outputSampleData(output, new byte[individualAllocationSize]);
          outputSample(
              output, /* timeUs= */ 1, /* size= */ individualAllocationSize, /* offset= */ 0);
          return Extractor.RESULT_CONTINUE;
        });
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput output = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          outputSampleData(output, new byte[individualAllocationSize * 2]);
          outputSample(
              output,
              /* timeUs= */ 2,
              /* size= */ individualAllocationSize,
              /* offset= */ individualAllocationSize);
          outputSample(
              output, /* timeUs= */ 3, /* size= */ individualAllocationSize, /* offset= */ 0);
          return Extractor.RESULT_END_OF_INPUT;
        });
    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(0);

    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    mediaExtractorCompat.selectTrack(/* trackIndex= */ 0);

    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(individualAllocationSize);
    mediaExtractorCompat.advance();
    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(individualAllocationSize * 2);
    mediaExtractorCompat.advance();
    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(individualAllocationSize);
    mediaExtractorCompat.advance();
    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(0);
  }

  // Test for b/223910395.
  @Test
  public void seek_withUninterleavedFile_seeksToTheRightPosition() throws IOException {
    // We don't use the global mediaExtractorCompat because we want to use a real extractor in this
    // case, which is the Mp4 extractor.
    MediaExtractorCompat mediaExtractorCompat =
        new MediaExtractorCompat(ApplicationProvider.getApplicationContext());
    // The asset is an uninterleaved mp4 file.
    mediaExtractorCompat.setDataSource(
        Uri.parse("asset:///media/mp4/mv_with_2_top_shots.mp4"), /* offset= */ 0);
    mediaExtractorCompat.selectTrack(1);
    mediaExtractorCompat.seekTo(1773911, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(1773911);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(101040);
  }

  // Test for b/233756471.
  @Test
  public void seek_withException_producesEndOfInput() throws IOException {
    Function<Long, SeekPoints> seekPointsFunction =
        (timesUs) -> {
          // For the mid seek point we use an invalid position which will cause an IOException. We
          // expect that exception to be treated as the end of input.
          SeekPoint midSeekPoint = new SeekPoint(/* timeUs= */ 14, /* position= */ 1000);
          return timesUs < 14
              ? new SeekPoints(SeekPoint.START, midSeekPoint)
              : new SeekPoints(midSeekPoint);
        };

    fakeExtractor.setSeekStrategy(fakeExtractor::rewindReadActions);
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          TrackOutput output = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          output.format(PLACEHOLDER_FORMAT_VIDEO);
          extractorOutput.seekMap(new FakeSeekMap(/* durationUs= */ 28, seekPointsFunction));
          outputSampleData(output, /* sampleData...= */ (byte) 0, (byte) 1, (byte) 2);
          outputSample(output, /* timeUs= */ 7, /* size= */ 1, /* offset= */ 2);
          outputSample(output, /* timeUs= */ 14, /* size= */ 1, /* offset= */ 1);
          outputSample(output, /* timeUs= */ 21, /* size= */ 1, /* offset= */ 0);
          return Extractor.RESULT_END_OF_INPUT;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    mediaExtractorCompat.selectTrack(/* trackIndex= */ 0);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
    mediaExtractorCompat.advance();
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(14);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
    mediaExtractorCompat.advance();
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(21);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
    mediaExtractorCompat.advance();
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(-1);

    // This seek will cause the target position to be invalid, causing an IOException which should
    // be treated as the end of input.
    mediaExtractorCompat.seekTo(/* timeUs= */ 14, MediaExtractorCompat.SEEK_TO_CLOSEST_SYNC);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(-1);

    // This seek should go to position 0, which should be handled correctly again.
    mediaExtractorCompat.seekTo(/* timeUs= */ 0, MediaExtractorCompat.SEEK_TO_CLOSEST_SYNC);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(7);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(1);
  }

  @Test
  public void
      setDataSourceUsingMethodExpectingContentUri_useAbsoluteFilePathAsUri_setsTrackCountCorrectly()
          throws IOException {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
    File file = tempFolder.newFile();
    Files.write(Paths.get(file.getAbsolutePath()), fileData);
    MediaExtractorCompat mediaExtractorCompat = new MediaExtractorCompat(context);

    mediaExtractorCompat.setDataSource(
        context, Uri.parse(file.getAbsolutePath()), /* headers= */ null);

    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void
      setDataSourceUsingMethodExpectingContentUri_useHttpUri_setsTrackCountAndHeadersCorrectly()
          throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileData = TestUtil.getByteArray(context, /* fileName= */ "media/mp4/sample.mp4");
    try (MockWebServer mockWebServer = new MockWebServer()) {
      mockWebServer.enqueue(new MockResponse().setBody(new Buffer().write(fileData)));
      Map<String, String> headers = new HashMap<>();
      headers.put("k", "v");
      MediaExtractorCompat mediaExtractorCompat = new MediaExtractorCompat(context);

      mediaExtractorCompat.setDataSource(
          context, Uri.parse(mockWebServer.url("/test-path").toString()), headers);

      assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
      assertThat(mockWebServer.takeRequest().getHeaders().get("k")).isEqualTo("v");
    }
  }

  @Test
  public void setDataSourceUsingMethodExpectingContentUri_useContentUri_setsTrackCountCorrectly()
      throws IOException {
    Context context = ApplicationProvider.getApplicationContext();
    Uri contentUri = Uri.parse("asset:///media/mp4/sample.mp4");
    MediaExtractorCompat mediaExtractorCompat = new MediaExtractorCompat(context);

    mediaExtractorCompat.setDataSource(context, contentUri, /* headers= */ null);

    assertThat(mediaExtractorCompat.getTrackCount()).isEqualTo(2);
  }

  @Test
  public void readNonSyncSample_whenSyncSampleIsExpected_noSampleIsQueued() throws IOException {
    TrackOutput[] outputs = new TrackOutput[1];
    byte[] sampleData = new byte[] {(byte) 1, (byte) 2, (byte) 3};
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputs[0] = extractorOutput.track(/* id= */ 0, C.TRACK_TYPE_VIDEO);
          extractorOutput.endTracks();
          outputs[0].format(
              new Format.Builder()
                  .setSampleMimeType(MimeTypes.VIDEO_H264)
                  .setCodecs("avc.123")
                  .build());
          return Extractor.RESULT_CONTINUE;
        });
    // Add a non-sync sample. This sample should be ignored as a sync sample is expected
    // at the start of the video.
    fakeExtractor.addReadAction(
        (input, seekPosition) -> {
          outputSampleData(outputs[0], sampleData);
          outputs[0].sampleMetadata(
              /* timeUs= */ 7,
              /* flags= */ 0,
              /* size= */ 3,
              /* offset= */ 0,
              /* cryptoData= */ null);
          return Extractor.RESULT_CONTINUE;
        });
    mediaExtractorCompat.setDataSource(PLACEHOLDER_URI, /* offset= */ 0);
    mediaExtractorCompat.selectTrack(0);

    // Assert that when a keyframe is expected, no sample is queued if a non-keyframe sample is
    // read.
    assertThat(mediaExtractorCompat.getSampleTrackIndex()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(-1);
    assertThat(mediaExtractorCompat.readSampleData(ByteBuffer.allocate(0), /* offset= */ 0))
        .isEqualTo(-1);
  }

  // Internal methods.

  private void assertReadSample(int trackIndex, long timeUs, int size, byte... sampleData) {
    assertThat(mediaExtractorCompat.getSampleTrackIndex()).isEqualTo(trackIndex);
    assertThat(mediaExtractorCompat.getSampleTime()).isEqualTo(timeUs);
    assertThat(mediaExtractorCompat.getSampleSize()).isEqualTo(size);
    ByteBuffer buffer = ByteBuffer.allocate(100);
    assertThat(mediaExtractorCompat.readSampleData(buffer, /* offset= */ 0))
        .isEqualTo(sampleData.length);
    for (int i = 0; i < buffer.remaining(); i++) {
      assertThat(buffer.get()).isEqualTo(sampleData[i]);
    }
  }

  private static void outputSampleData(TrackOutput trackOutput, byte... sampleData) {
    trackOutput.sampleData(new ParsableByteArray(sampleData), sampleData.length);
  }

  private static void outputSample(TrackOutput trackOutput, long timeUs, int size, int offset) {
    trackOutput.sampleMetadata(
        timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset, /* cryptoData= */ null);
  }

  /**
   * Read action to verify that {@link MediaExtractorCompat} does not read more data than expected.
   */
  private static int assertionFailureReadAction(ExtractorInput input, PositionHolder holder) {
    throw new AssertionError("MediaExtractorCompat read more data than needed.");
  }

  // Internal classes.

  private static class FakeSeekMap implements SeekMap {

    private final long durationUs;
    private final Function<Long, SeekPoints> seekPointsFunction;

    public FakeSeekMap() {
      this(C.TIME_UNSET, (timeUs) -> new SeekPoints(SeekPoint.START));
    }

    public FakeSeekMap(long durationUs, Function<Long, SeekPoints> seekPointsFunction) {
      this.durationUs = durationUs;
      this.seekPointsFunction = seekPointsFunction;
    }

    @Override
    public boolean isSeekable() {
      return true;
    }

    @Override
    public long getDurationUs() {
      return durationUs;
    }

    @Override
    public SeekPoints getSeekPoints(long timeUs) {
      return seekPointsFunction.apply(timeUs);
    }
  }

  private class FakeExtractor implements Extractor {

    private final ArrayList<BiFunction<ExtractorInput, PositionHolder, Integer>> readActions;
    private BiConsumer<Long, Long> seekStrategy;
    private int nextReadActionIndex;

    public FakeExtractor() {
      readActions = new ArrayList<>();
      nextReadActionIndex = 0;
      seekStrategy = (arg1, arg2) -> {};
    }

    public void addReadAction(BiFunction<ExtractorInput, PositionHolder, Integer> readAction) {
      readActions.add(readAction);
    }

    public void setSeekStrategy(BiConsumer<Long, Long> seekStrategy) {
      this.seekStrategy = seekStrategy;
    }

    public void rewindReadActions(long position, long timeUs) {
      nextReadActionIndex = 0;
    }

    // Extractor implementation.

    @Override
    public boolean sniff(ExtractorInput input) {
      return true;
    }

    @Override
    public void init(ExtractorOutput extractorOutput) {
      MediaExtractorCompatTest.this.extractorOutput = extractorOutput;
      extractorOutput.seekMap(fakeSeekMap);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
      if (nextReadActionIndex >= readActions.size()) {
        return C.RESULT_END_OF_INPUT;
      } else {
        return readActions.get(nextReadActionIndex++).apply(input, seekPosition);
      }
    }

    @Override
    public void seek(long position, long timeUs) {
      seekStrategy.accept(position, timeUs);
    }

    @Override
    public void release() {}
  }
}
