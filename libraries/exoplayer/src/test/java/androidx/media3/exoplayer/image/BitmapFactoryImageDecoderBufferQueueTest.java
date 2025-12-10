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
package androidx.media3.exoplayer.image;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link BitmapFactoryImageDecoder} ensuring the buffer queue system operates
 * correctly.
 */
@RunWith(AndroidJUnit4.class)
public class BitmapFactoryImageDecoderBufferQueueTest {

  private static final long TIMEOUT_MS = 5 * C.MICROS_PER_SECOND;

  private byte[] whiteJpegBytes;
  private byte[] londonJpegBytes;

  private BitmapFactoryImageDecoder imageDecoder;

  @Before
  public void setUp() throws Exception {
    whiteJpegBytes = readJpegAsset("white-1x1.jpg");
    londonJpegBytes = readJpegAsset("london-512.jpg");

    imageDecoder =
        new BitmapFactoryImageDecoder.Factory(ApplicationProvider.getApplicationContext())
            .createImageDecoder();
  }

  private byte[] readJpegAsset(String jpegName) throws IOException {
    AssetDataSource dataSource = new AssetDataSource(ApplicationProvider.getApplicationContext());
    try {
      dataSource.open(new DataSpec(Uri.parse("asset:///media/jpeg/" + jpegName)));
      return DataSourceUtil.readToEnd(dataSource);
    } finally {
      DataSourceUtil.closeQuietly(dataSource);
    }
  }

  @After
  public void tearDown() throws Exception {
    imageDecoder.release();
  }

  @Test
  public void decodeIndirectly_returnBitmapAtTheCorrectTimestamp() throws Exception {
    DecoderInputBuffer inputBuffer = checkNotNull(imageDecoder.dequeueInputBuffer());
    inputBuffer.timeUs = 2 * C.MILLIS_PER_SECOND;
    inputBuffer.data = ByteBuffer.wrap(whiteJpegBytes);
    imageDecoder.queueInputBuffer(inputBuffer);
    ImageOutputBuffer outputBuffer = getDecodedOutput();

    assertThat(outputBuffer.timeUs).isEqualTo(inputBuffer.timeUs);
    assertThat(outputBuffer.bitmap.getWidth()).isEqualTo(1);
  }

  @Test
  public void decodeIndirectlyTwice_returnsSecondBitmapAtTheCorrectTimestamp() throws Exception {
    DecoderInputBuffer inputBuffer1 = checkNotNull(imageDecoder.dequeueInputBuffer());
    inputBuffer1.timeUs = 0;
    inputBuffer1.data = ByteBuffer.wrap(whiteJpegBytes);
    imageDecoder.queueInputBuffer(inputBuffer1);
    checkNotNull(getDecodedOutput()).release();
    DecoderInputBuffer inputBuffer2 = checkNotNull(imageDecoder.dequeueInputBuffer());
    inputBuffer2.timeUs = C.MICROS_PER_SECOND;
    inputBuffer2.data = ByteBuffer.wrap(londonJpegBytes);
    imageDecoder.queueInputBuffer(inputBuffer2);

    ImageOutputBuffer outputBuffer2 = checkNotNull(getDecodedOutput());

    assertThat(outputBuffer2.timeUs).isEqualTo(inputBuffer2.timeUs);
    assertThat(outputBuffer2.bitmap.getWidth()).isEqualTo(512);
  }

  // Polling to see whether the output is available yet since the decode thread doesn't finish
  // decoding immediately.
  private ImageOutputBuffer getDecodedOutput() throws Exception {
    @Nullable ImageOutputBuffer outputBuffer;
    // Use System.currentTimeMillis() to calculate the wait duration more accurately.
    long deadlineMs = System.currentTimeMillis() + TIMEOUT_MS;
    long remainingMs = TIMEOUT_MS;
    while (remainingMs > 0) {
      outputBuffer = imageDecoder.dequeueOutputBuffer();
      if (outputBuffer != null) {
        return outputBuffer;
      }
      Thread.sleep(/* millis= */ 5);
      remainingMs = deadlineMs - System.currentTimeMillis();
    }
    throw new TimeoutException();
  }
}
