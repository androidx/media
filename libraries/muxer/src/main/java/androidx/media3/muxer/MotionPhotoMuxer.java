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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A muxer for creating a motion photo file. */
@UnstableApi
public final class MotionPhotoMuxer implements AutoCloseable {
  private static final int SEGMENT_MARKER_LENGTH = 2;
  private static final int SEGMENT_SIZE_LENGTH = 2;
  private static final short SOI_MARKER = (short) 0xFFD8;
  private static final short APP1_MARKER = (short) 0xFFE1;
  private static final short SOS_MARKER = (short) 0xFFDA;
  private static final short EOI_MARKER = (short) 0xFFD9;
  private static final String JPEG_XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000";

  private final SeekableMuxerOutput muxerOutput;

  private @MonotonicNonNull ByteBuffer imageData;
  private int imageDataStartIndex;
  private int imageDataEndIndex;
  private @MonotonicNonNull String imageMimeType;
  private long imagePresentationTimestampUs;
  private boolean addedImageData;
  private @MonotonicNonNull FileInputStream videoInputStream;
  private @MonotonicNonNull String videoContainerMimeType;
  private boolean addedVideoData;

  /**
   * Creates a new instance.
   *
   * @param muxerOutputFactory A {@link MuxerOutputFactory} to provide output destinations.
   */
  public MotionPhotoMuxer(MuxerOutputFactory muxerOutputFactory) {
    muxerOutput = muxerOutputFactory.getSeekableMuxerOutput();
    imageDataStartIndex = C.INDEX_UNSET;
    imageDataEndIndex = C.INDEX_UNSET;
    imagePresentationTimestampUs = C.TIME_UNSET;
  }

  /**
   * Adds the image to the muxer.
   *
   * @param byteBuffer The image data.
   * @param mimeType The mime type of the image. Must be {@link MimeTypes#IMAGE_JPEG}.
   * @param presentationTimestampUs The presentation timestamp of the image in the video (in
   *     microseconds).
   */
  public void addImageData(ByteBuffer byteBuffer, String mimeType, long presentationTimestampUs) {
    checkState(!addedImageData, "Image data already added");
    checkArgument(mimeType.equals(MimeTypes.IMAGE_JPEG), "Only JPEG mime type is supported");
    imageData = byteBuffer.asReadOnlyBuffer();
    imageDataStartIndex = imageData.position();
    imageDataEndIndex = imageData.limit();
    imageMimeType = mimeType;
    imagePresentationTimestampUs = presentationTimestampUs;
    addedImageData = true;
  }

  /**
   * Adds the video to the muxer.
   *
   * @param inputStream A {@link FileInputStream} containing the video data. The stream will be
   *     automatically closed by the muxer when {@link MotionPhotoMuxer#close()} is called.
   * @param containerMimeType The container mime type of the video. Must be {@link
   *     MimeTypes#VIDEO_MP4} or {@link MimeTypes#VIDEO_QUICK_TIME}.
   */
  public void addVideoData(FileInputStream inputStream, String containerMimeType) {
    checkState(!addedVideoData, "Video data already added");
    checkArgument(
        containerMimeType.equals(MimeTypes.VIDEO_MP4)
            || containerMimeType.equals(MimeTypes.VIDEO_QUICK_TIME), "Only MP4 and QUICKTIME container mime types are supported");
    videoInputStream = inputStream;
    videoContainerMimeType = containerMimeType;
    addedVideoData = true;
  }

  /**
   * Closes the file.
   *
   * <p>The muxer cannot be used anymore once this method returns.
   *
   * @throws MuxerException If the muxer fails to finish writing the output.
   */
  @Override
  public void close() throws MuxerException {
    try {
      writeImageDataToMuxerOutput();
    } catch (IOException e) {
      throw new MuxerException("Error writing image data", e);
    }
    try {
      writeVideoDataToMuxerOutput();
    } catch (IOException e) {
      throw new MuxerException("Error writing video data", e);
    }
    try {
      checkNotNull(videoInputStream).close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close video input stream", e);
    }
    try {
      muxerOutput.close();
    } catch (IOException e) {
      throw new MuxerException("Failed to close muxer output", e);
    }
  }

  private static ByteBuffer getApp1SegmentWithMotionPhotoXmpDate(byte[] motionPhotoXmp) {
    short totalSegmentLength =
        (short) (SEGMENT_SIZE_LENGTH + JPEG_XMP_IDENTIFIER.length() + motionPhotoXmp.length);
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(SEGMENT_MARKER_LENGTH + totalSegmentLength);
    byteBuffer.putShort(APP1_MARKER);
    byteBuffer.putShort(totalSegmentLength);
    byteBuffer.put(Util.getUtf8Bytes(JPEG_XMP_IDENTIFIER));
    byteBuffer.put(motionPhotoXmp);
    byteBuffer.flip();
    return byteBuffer;
  }

  private static byte[] generateMotionPhotoXmp(
      long imagePresentationTimestampUs,
      String imageMimeType,
      String videoContainerMimeType,
      long videoSize) {
    String motionPhotoXmp =
        String.format(
            Locale.US,
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n"
                + "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "    <rdf:Description rdf:about=\"\"\n"
                + "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n"
                + "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
                + "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n"
                + "      GCamera:MotionPhoto=\"1\"\n"
                + "      GCamera:MotionPhotoVersion=\"1\"\n"
                + "      GCamera:MotionPhotoPresentationTimestampUs=\"%d\">\n"
                + "        <Container:Directory>\n"
                + "          <rdf:Seq>\n"
                + "            <rdf:li rdf:parseType=\"Resource\">\n"
                + "              <Container:Item\n"
                + "                Item:Mime=\"%s\"\n"
                + "                Item:Semantic=\"Primary\"\n"
                + "                Item:Length=\"0\"\n"
                + "                Item:Padding=\"0\"/>\n"
                + "            </rdf:li>\n"
                + "            <rdf:li rdf:parseType=\"Resource\">\n"
                + "              <Container:Item\n"
                + "                Item:Mime=\"%s\"\n"
                + "                Item:Semantic=\"MotionPhoto\"\n"
                + "                Item:Length=\"%d\"\n"
                + "                Item:Padding=\"0\"/>\n"
                + "            </rdf:li>\n"
                + "          </rdf:Seq>\n"
                + "        </Container:Directory>\n"
                + "      </rdf:Description>\n"
                + "    </rdf:RDF>\n"
                + "  </x:xmpmeta>\n",
            imagePresentationTimestampUs,
            imageMimeType,
            videoContainerMimeType,
            videoSize);
    return Util.getUtf8Bytes(motionPhotoXmp);
  }

  private void writeImageDataToMuxerOutput() throws IOException {
    checkNotNull(imageData);
    int lastApp1SegmentEndIndex = findLastApp1SegmentEndIndexFromImageData();
    // Write image data till end of last APP1 segment.
    imageData.limit(lastApp1SegmentEndIndex);
    muxerOutput.write(imageData);
    resetImageData();
    long videoSize = checkNotNull(videoInputStream).getChannel().size();
    byte[] motionPhotoXmp =
        generateMotionPhotoXmp(
            imagePresentationTimestampUs,
            checkNotNull(imageMimeType),
            checkNotNull(videoContainerMimeType),
            videoSize);
    ByteBuffer app1SegmentWithMotionPhotoXmp = getApp1SegmentWithMotionPhotoXmpDate(motionPhotoXmp);
    muxerOutput.write(app1SegmentWithMotionPhotoXmp);
    // Write image data after the last APP1 segment.
    imageData.position(lastApp1SegmentEndIndex);
    muxerOutput.write(imageData);
  }

  private void writeVideoDataToMuxerOutput() throws IOException {
    checkNotNull(videoInputStream);
    FileChannel videoChannel = checkNotNull(videoInputStream).getChannel();
    videoChannel.transferTo(0, videoChannel.size(), muxerOutput);
  }

  private int findLastApp1SegmentEndIndexFromImageData() {
    int lastApp1SegmentEndIndex = -1;
    short marker = checkNotNull(imageData).getShort();
    checkArgument(marker == SOI_MARKER, "SOI marker not found");
    while (imageData.remaining() > SEGMENT_MARKER_LENGTH) {
      marker = imageData.getShort();
      // Segment length includes the 2 bytes for the length itself, so we need to subtract it to
      // get the length of the segment data.
      int segmentLength = imageData.getShort() - SEGMENT_SIZE_LENGTH;
      if (marker == SOS_MARKER || marker == EOI_MARKER) {
        break;
      }
      if (marker == APP1_MARKER) {
        lastApp1SegmentEndIndex = imageData.position() + segmentLength;
      }
      // Move to the end of the current segment, to read the next marker.
      imageData.position(imageData.position() + segmentLength);
    }
    resetImageData();
    checkState(lastApp1SegmentEndIndex != -1, "Existing APP1 segment not found");
    return lastApp1SegmentEndIndex;
  }

  private void resetImageData() {
    checkNotNull(imageData).position(imageDataStartIndex);
    imageData.limit(imageDataEndIndex);
  }
}
