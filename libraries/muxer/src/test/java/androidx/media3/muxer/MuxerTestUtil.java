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
package androidx.media3.muxer;

import static androidx.media3.common.MimeTypes.AUDIO_AAC;
import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.common.util.Util.getBufferFlagsFromMediaCodecFlags;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.exoplayer.MediaExtractorCompat;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Utilities for muxer test cases. */
/* package */ class MuxerTestUtil {

  public static final byte[] FAKE_CSD_0 =
      BaseEncoding.base16().decode("0000000167F4000A919B2BF3CB3640000003004000000C83C4896580");
  public static final byte[] FAKE_CSD_1 = BaseEncoding.base16().decode("0000000168EBE3C448");
  public static final Format FAKE_AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(AUDIO_AAC)
          .setCodecs("mp4a.40.2")
          .setSampleRate(40000)
          .setChannelCount(2)
          .build();
  public static final Format FAKE_VIDEO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(VIDEO_H264)
          .setWidth(12)
          .setHeight(10)
          .setInitializationData(ImmutableList.of(FAKE_CSD_0, FAKE_CSD_1))
          .build();

  public static final String XMP_SAMPLE_DATA = "media/xmp/sample_datetime_xmp.xmp";
  public static final String MP4_FILE_ASSET_DIRECTORY = "asset:///media/mp4/";

  private static final byte[] FAKE_H264_SAMPLE =
      BaseEncoding.base16()
          .decode(
              "0000000167F4000A919B2BF3CB3640000003004000000C83C48965800000000168EBE3C448000001658884002BFFFEF5DBF32CAE4A43FF");

  private static final String DUMP_FILE_OUTPUT_DIRECTORY = "muxerdumps";
  private static final String DUMP_FILE_EXTENSION = "dump";

  public static String getExpectedDumpFilePath(String originalFileName) {
    return DUMP_FILE_OUTPUT_DIRECTORY + '/' + originalFileName + '.' + DUMP_FILE_EXTENSION;
  }

  public static Pair<ByteBuffer, BufferInfo> getFakeSampleAndSampleInfo(long presentationTimeUs) {
    ByteBuffer sampleDirectBuffer = ByteBuffer.allocateDirect(FAKE_H264_SAMPLE.length);
    sampleDirectBuffer.put(FAKE_H264_SAMPLE);
    sampleDirectBuffer.rewind();

    BufferInfo bufferInfo =
        new BufferInfo(presentationTimeUs, FAKE_H264_SAMPLE.length, C.BUFFER_FLAG_KEY_FRAME);

    return new Pair<>(sampleDirectBuffer, bufferInfo);
  }

  public static void feedInputDataToMuxer(Context context, Muxer muxer, String inputFileName)
      throws IOException, MuxerException {
    feedInputDataToMuxer(
        context,
        muxer,
        inputFileName,
        /* removeInitializationData= */ false,
        /* removeAudioSampleFlags= */ false);
  }

  public static void feedInputDataToMuxer(
      Context context,
      Muxer muxer,
      String inputFileName,
      boolean removeInitializationData,
      boolean removeAudioSampleFlags)
      throws IOException, MuxerException {
    MediaExtractorCompat extractor = new MediaExtractorCompat(context);
    Uri fileUri = Uri.parse(MP4_FILE_ASSET_DIRECTORY + inputFileName);
    extractor.setDataSource(fileUri, /* offset= */ 0);

    List<Integer> trackMapping = new ArrayList<>();
    int audioTrackIndex = C.INDEX_UNSET;
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      Format format = MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(i));
      if (removeInitializationData && MimeTypes.isVideo(format.sampleMimeType)) {
        format = format.buildUpon().setInitializationData(null).build();
      }
      // Skip ID3 tracks as AacMuxer does not handle them.
      if (Objects.equals(format.sampleMimeType, MimeTypes.APPLICATION_ID3)) {
        trackMapping.add(-1); // We are not adding this track, mapping to -1.
        continue;
      }
      if (MimeTypes.isAudio(format.sampleMimeType)) {
        audioTrackIndex = i;
      }
      trackMapping.add(muxer.addTrack(format));
      extractor.selectTrack(i);
    }

    do {
      int sampleSize = (int) extractor.getSampleSize();
      int sampleTrackIndex = extractor.getSampleTrackIndex();
      @C.BufferFlags
      int sampleFlags =
          (sampleTrackIndex == audioTrackIndex && removeAudioSampleFlags)
              ? 0
              : getBufferFlagsFromMediaCodecFlags(extractor.getSampleFlags());
      BufferInfo bufferInfo = new BufferInfo(extractor.getSampleTime(), sampleSize, sampleFlags);

      ByteBuffer sampleBuffer = ByteBuffer.allocateDirect(sampleSize);
      extractor.readSampleData(sampleBuffer, /* offset= */ 0);
      sampleBuffer.rewind();
      Format format =
          MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(sampleTrackIndex));
      if (Objects.equals(format.sampleMimeType, MimeTypes.APPLICATION_ID3)) {
        // Skip sample data for ID3 tracks.
        continue;
      }
      muxer.writeSampleData(trackMapping.get(sampleTrackIndex), sampleBuffer, bufferInfo);
    } while (extractor.advance());

    extractor.release();
  }

  private MuxerTestUtil() {}
}
