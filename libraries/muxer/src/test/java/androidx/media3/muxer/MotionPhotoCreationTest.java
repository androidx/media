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

import static androidx.media3.extractor.jpeg.JpegExtractor.FLAG_READ_IMAGE;

import android.content.Context;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.jpeg.JpegExtractor;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Tests for {@link MuxerUtil#createMotionPhotoFromJpegImageAndBmffVideo}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class MotionPhotoCreationTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final String IMAGE_ASSET_DIRECTORY = "media/jpeg/";
  private static final String VIDEO_ASSET_DIRECTORY = "media/mp4/";
  private static final String JPEG_IMAGE = "london.jpg";
  private static final String JPEG_IMAGE_WITHOUT_APP1_SEGMENT = "london_without_app1_segment.jpg";
  private static final String MP4_VIDEO = "sample_no_bframes.mp4";
  private static final String QUICK_TIME_VIDEO = "sample_with_original_quicktime_specification.mov";

  private final Context context = ApplicationProvider.getApplicationContext();

  private enum ImageAndVideoEnum {
    JPEG_IMAGE_AND_MP4_VIDEO(JPEG_IMAGE, MP4_VIDEO, MimeTypes.VIDEO_MP4),
    JPEG_IMAGE_AND_QUICK_TIME_VIDEO(JPEG_IMAGE, QUICK_TIME_VIDEO, MimeTypes.VIDEO_QUICK_TIME),
    JPEG_IMAGE_WITHOUT_APP1_SEGMENT_AND_MP4_VIDEO(
        JPEG_IMAGE_WITHOUT_APP1_SEGMENT, MP4_VIDEO, MimeTypes.VIDEO_MP4);

    private final String imageName;
    private final String videoName;
    private final String videoContainerMimeType;

    ImageAndVideoEnum(String imageName, String videoName, String videoContainerMimeType) {
      this.imageName = imageName;
      this.videoName = videoName;
      this.videoContainerMimeType = videoContainerMimeType;
    }
  }

  @TestParameter private ImageAndVideoEnum imageAndVideoEnum;

  @Test
  public void createMotionPhoto_withImageAndVideo_writesExpectedFile() throws Exception {
    String outputFilePath = temporaryFolder.newFile().getPath();

    try (FileInputStream imageInputStream =
            getFileInputStreamFromAssetFile(IMAGE_ASSET_DIRECTORY + imageAndVideoEnum.imageName);
        FileInputStream videoInputStream =
            getFileInputStreamFromAssetFile(VIDEO_ASSET_DIRECTORY + imageAndVideoEnum.videoName);
        FileOutputStream outputStream = new FileOutputStream(outputFilePath)) {
      MuxerUtil.createMotionPhotoFromJpegImageAndBmffVideo(
          imageInputStream,
          /* imagePresentationTimestampUs= */ 500_500L,
          videoInputStream,
          imageAndVideoEnum.videoContainerMimeType,
          outputStream.getChannel());
    }

    FakeExtractorOutput imageOutput =
        TestUtil.extractAllSamplesFromFilePath(new JpegExtractor(FLAG_READ_IMAGE), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        imageOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            getMotionPhotoDumpFileName(
                imageAndVideoEnum.imageName, imageAndVideoEnum.videoName, "image")));
    FakeExtractorOutput videoOutput =
        TestUtil.extractAllSamplesFromFilePath(new JpegExtractor(), outputFilePath);
    DumpFileAsserts.assertOutput(
        context,
        videoOutput,
        MuxerTestUtil.getExpectedDumpFilePath(
            getMotionPhotoDumpFileName(
                imageAndVideoEnum.imageName, imageAndVideoEnum.videoName, "video")));
  }

  private static String getMotionPhotoDumpFileName(
      String imageName, String videoName, String suffix) {
    return imageName + "+" + videoName + "_" + "motion_photo_" + suffix;
  }

  private FileInputStream getFileInputStreamFromAssetFile(String assetFileName) throws IOException {
    String tempFilePath = temporaryFolder.newFile().getPath();
    byte[] assetFileBytes = TestUtil.getByteArray(context, assetFileName);
    try (FileOutputStream tempFileOutputStream = new FileOutputStream(tempFilePath)) {
      tempFileOutputStream.write(assetFileBytes);
    }
    return new FileInputStream(tempFilePath);
  }
}
