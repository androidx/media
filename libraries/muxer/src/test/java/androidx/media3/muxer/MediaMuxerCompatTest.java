/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import static android.media.MediaFormat.KEY_CAPTURE_RATE;
import static android.media.MediaFormat.KEY_MIME;
import static androidx.media3.muxer.MediaMuxerCompat.OUTPUT_FORMAT_MP4;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.exoplayer.MediaExtractorCompat;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End to end tests for {@link MediaMuxerCompat}. */
@RunWith(AndroidJUnit4.class)
public final class MediaMuxerCompatTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void createMp4File_withCaptureRateKey_writesCorrectCaptureFps() throws Exception {
    String outputFilePath = tempFolder.newFile().getAbsolutePath();
    float captureFps = 60.0f;

    MediaMuxerCompat mediaMuxerCompat = new MediaMuxerCompat(outputFilePath, OUTPUT_FORMAT_MP4);
    try {
      feedDataToMuxer(
          context, mediaMuxerCompat, MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri, captureFps);
    } finally {
      mediaMuxerCompat.release();
    }

    FakeExtractorOutput fakeExtractorOutput =
        TestUtil.extractAllSamplesFromFilePath(
            new Mp4Extractor(new DefaultSubtitleParserFactory()), checkNotNull(outputFilePath));
    Format videoTrackFormat =
        Iterables.getOnlyElement(fakeExtractorOutput.getTrackOutputsForType(C.TRACK_TYPE_VIDEO))
            .lastFormat;
    MdtaMetadataEntry expectedCaptureFps =
        new MdtaMetadataEntry(
            MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS,
            /* value= */ Util.toByteArray(captureFps),
            MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32);
    @Nullable MdtaMetadataEntry actualCaptureFps = findCaptureFpsMetadata(videoTrackFormat);
    assertThat(actualCaptureFps).isEqualTo(expectedCaptureFps);
  }

  private static MdtaMetadataEntry findCaptureFpsMetadata(Format format) {
    if (format.metadata == null) {
      return null;
    }
    for (int i = 0; i < format.metadata.length(); i++) {
      Metadata.Entry metadataEntry = format.metadata.get(i);
      if (metadataEntry instanceof MdtaMetadataEntry
          && ((MdtaMetadataEntry) metadataEntry)
              .key.equals(MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS)) {
        return (MdtaMetadataEntry) metadataEntry;
      }
    }
    return null;
  }

  private static void feedDataToMuxer(
      Context context, MediaMuxerCompat muxer, String inputFilePath, float captureFps)
      throws IOException {
    MediaExtractorCompat extractor = new MediaExtractorCompat(context);
    Uri fileUri = Uri.parse(inputFilePath);
    extractor.setDataSource(fileUri, /* offset= */ 0);
    List<Integer> trackIndexes = new ArrayList<>();
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      if (captureFps != C.RATE_UNSET && MimeTypes.isVideo(format.getString(KEY_MIME))) {
        format.setFloat(KEY_CAPTURE_RATE, captureFps);
      }
      extractor.selectTrack(i);
      trackIndexes.add(muxer.addTrack(format));
    }
    muxer.start();
    do {
      int sampleSize = (int) extractor.getSampleSize();
      ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(sampleSize);
      extractor.readSampleData(sampleBuffer, /* offset= */ 0);
      sampleBuffer.rewind();
      int sampleTrackIndex = extractor.getSampleTrackIndex();
      MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
      bufferInfo.set(
          /* newOffset= */ 0, sampleSize, extractor.getSampleTime(), extractor.getSampleFlags());
      muxer.writeSampleData(trackIndexes.get(sampleTrackIndex), sampleBuffer, bufferInfo);
    } while (extractor.advance());
    muxer.stop();
    extractor.release();
  }
}
