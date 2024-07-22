/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static androidx.media3.common.C.BUFFER_FLAG_ENCRYPTED;
import static androidx.media3.common.C.BUFFER_FLAG_KEY_FRAME;
import static androidx.media3.common.C.RESULT_BUFFER_READ;
import static androidx.media3.common.C.RESULT_FORMAT_READ;
import static androidx.media3.common.C.RESULT_NOTHING_READ;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_OMIT_SAMPLE_DATA;
import static androidx.media3.exoplayer.source.SampleStream.FLAG_PEEK;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Long.MIN_VALUE;
import static java.util.Arrays.copyOfRange;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.when;

import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.FakeCryptoConfig;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Test for {@link SampleQueue}. */
@RunWith(AndroidJUnit4.class)
public final class SampleQueueTest {

  private static final int ALLOCATION_SIZE = 16;

  private static final Format FORMAT_1 = buildFormat(/* id= */ "1");
  private static final Format FORMAT_2 = buildFormat(/* id= */ "2");
  private static final Format FORMAT_1_COPY = buildFormat(/* id= */ "1");
  private static final Format FORMAT_SPLICED = buildFormat(/* id= */ "spliced");
  private static final Format FORMAT_ENCRYPTED =
      new Format.Builder().setId(/* id= */ "encrypted").setDrmInitData(new DrmInitData()).build();
  private static final Format FORMAT_ENCRYPTED_WITH_EXO_MEDIA_CRYPTO_TYPE =
      FORMAT_ENCRYPTED.copyWithCryptoType(FakeCryptoConfig.TYPE);
  private static final Format FORMAT_SYNC_SAMPLE_ONLY_1 =
      new Format.Builder().setId("sync1").setSampleMimeType(MimeTypes.AUDIO_RAW).build();
  private static final Format FORMAT_SYNC_SAMPLE_ONLY_2 =
      new Format.Builder().setId("sync2").setSampleMimeType(MimeTypes.AUDIO_RAW).build();
  private static final byte[] DATA = TestUtil.buildTestData(ALLOCATION_SIZE * 10);

  /*
   * SAMPLE_SIZES and SAMPLE_OFFSETS are intended to test various boundary cases (with
   * respect to the allocation size). SAMPLE_OFFSETS values are defined as the backward offsets
   * (as expected by SampleQueue.sampleMetadata) assuming that DATA has been written to the
   * sampleQueue in full. The allocations are filled as follows, where | indicates a boundary
   * between allocations and x indicates a byte that doesn't belong to a sample:
   *
   * x<s1>|x<s2>x|x<s3>|<s4>x|<s5>|<s6|s6>|x<s7|s7>x|<s8>
   */
  private static final int[] SAMPLE_SIZES =
      new int[] {
        ALLOCATION_SIZE - 1,
        ALLOCATION_SIZE - 2,
        ALLOCATION_SIZE - 1,
        ALLOCATION_SIZE - 1,
        ALLOCATION_SIZE,
        ALLOCATION_SIZE * 2,
        ALLOCATION_SIZE * 2 - 2,
        ALLOCATION_SIZE
      };
  private static final int[] SAMPLE_OFFSETS =
      new int[] {
        ALLOCATION_SIZE * 9,
        ALLOCATION_SIZE * 8 + 1,
        ALLOCATION_SIZE * 7,
        ALLOCATION_SIZE * 6 + 1,
        ALLOCATION_SIZE * 5,
        ALLOCATION_SIZE * 3,
        ALLOCATION_SIZE + 1,
        0
      };
  private static final long[] SAMPLE_TIMESTAMPS =
      new long[] {0, 1000, 2000, 3000, 4000, 5000, 6000, 7000};
  private static final long LAST_SAMPLE_TIMESTAMP = SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 1];
  private static final int[] SAMPLE_FLAGS =
      new int[] {C.BUFFER_FLAG_KEY_FRAME, 0, 0, 0, C.BUFFER_FLAG_KEY_FRAME, 0, 0, 0};
  private static final int[] SAMPLE_FLAGS_SYNC_SAMPLES_ONLY =
      new int[] {
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME,
        C.BUFFER_FLAG_KEY_FRAME
      };
  private static final Format[] SAMPLE_FORMATS =
      new Format[] {FORMAT_1, FORMAT_1, FORMAT_1, FORMAT_1, FORMAT_2, FORMAT_2, FORMAT_2, FORMAT_2};
  private static final int DATA_SECOND_KEYFRAME_INDEX = 4;
  private static final Format[] SAMPLE_FORMATS_SYNC_SAMPLES_ONLY =
      new Format[] {
        FORMAT_SYNC_SAMPLE_ONLY_1,
        FORMAT_SYNC_SAMPLE_ONLY_1,
        FORMAT_SYNC_SAMPLE_ONLY_1,
        FORMAT_SYNC_SAMPLE_ONLY_1,
        FORMAT_SYNC_SAMPLE_ONLY_2,
        FORMAT_SYNC_SAMPLE_ONLY_2,
        FORMAT_SYNC_SAMPLE_ONLY_2,
        FORMAT_SYNC_SAMPLE_ONLY_2
      };

  private static final int[] ENCRYPTED_SAMPLES_FLAGS =
      new int[] {
        C.BUFFER_FLAG_KEY_FRAME, C.BUFFER_FLAG_ENCRYPTED, 0, C.BUFFER_FLAG_ENCRYPTED,
      };
  private static final long[] ENCRYPTED_SAMPLE_TIMESTAMPS = new long[] {0, 1000, 2000, 3000};
  private static final Format[] ENCRYPTED_SAMPLE_FORMATS =
      new Format[] {FORMAT_ENCRYPTED, FORMAT_ENCRYPTED, FORMAT_1, FORMAT_ENCRYPTED};

  /** Encrypted samples require the encryption preamble. */
  private static final int[] ENCRYPTED_SAMPLE_SIZES = new int[] {1, 3, 1, 3};

  private static final int[] ENCRYPTED_SAMPLE_OFFSETS = new int[] {7, 4, 3, 0};
  private static final byte[] ENCRYPTED_SAMPLE_DATA = new byte[] {1, 1, 1, 1, 1, 1, 1, 1};

  private static final TrackOutput.CryptoData CRYPTO_DATA =
      new TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, new byte[16], 0, 0);

  private static final int CLOSE_TO_CAPACITY_SIZE = SampleQueue.SAMPLE_CAPACITY_INCREMENT - 1;

  private Allocator allocator;
  private MockDrmSessionManager mockDrmSessionManager;
  private DrmSession mockDrmSession;
  private DrmSessionEventListener.EventDispatcher eventDispatcher;
  private SampleQueue sampleQueue;
  private FormatHolder formatHolder;
  private DecoderInputBuffer inputBuffer;

  @Before
  public void setUp() {
    allocator = new DefaultAllocator(false, ALLOCATION_SIZE);
    mockDrmSession = Mockito.mock(DrmSession.class);
    mockDrmSessionManager = new MockDrmSessionManager(mockDrmSession);
    eventDispatcher = new DrmSessionEventListener.EventDispatcher();
    sampleQueue = new SampleQueue(allocator, mockDrmSessionManager, eventDispatcher);
    formatHolder = new FormatHolder();
    inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  }

  @After
  public void tearDown() {
    allocator = null;
    sampleQueue = null;
    formatHolder = null;
    inputBuffer = null;
  }

  @Test
  public void capacityIncreases() {
    int numberOfSamplesToInput = 3 * SampleQueue.SAMPLE_CAPACITY_INCREMENT + 1;
    sampleQueue.format(FORMAT_1);
    sampleQueue.sampleData(
        new ParsableByteArray(numberOfSamplesToInput), /* length= */ numberOfSamplesToInput);
    for (int i = 0; i < numberOfSamplesToInput; i++) {
      sampleQueue.sampleMetadata(
          /* timeUs= */ i * 1000,
          /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
          /* size= */ 1,
          /* offset= */ numberOfSamplesToInput - i - 1,
          /* cryptoData= */ null);
    }

    assertReadFormat(/* formatRequired= */ false, FORMAT_1);
    for (int i = 0; i < numberOfSamplesToInput; i++) {
      assertReadSample(
          /* timeUs= */ i * 1000,
          /* isKeyFrame= */ true,
          /* isDecodeOnly= */ false,
          /* isEncrypted= */ false,
          /* sampleData= */ new byte[1],
          /* offset= */ 0,
          /* length= */ 1);
    }
    assertReadNothing(/* formatRequired= */ false);
  }

  @Test
  public void resetReleasesAllocations() {
    writeTestData();
    assertAllocationCount(10);
    sampleQueue.reset();
    assertAllocationCount(0);
  }

  @Test
  public void readWithoutWrite() {
    assertNoSamplesToRead(null);
  }

  @Test
  public void peekConsumesDownstreamFormat() {
    sampleQueue.format(FORMAT_1);
    clearFormatHolderAndInputBuffer();
    int result =
        sampleQueue.read(formatHolder, inputBuffer, FLAG_PEEK, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    // formatHolder should be populated.
    assertThat(formatHolder.format).isEqualTo(FORMAT_1);
    result = sampleQueue.read(formatHolder, inputBuffer, FLAG_PEEK, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_NOTHING_READ);
  }

  @Test
  public void equalFormatsDeduplicated() {
    sampleQueue.format(FORMAT_1);
    assertReadFormat(false, FORMAT_1);
    // If the same format is written then it should not cause a format change on the read side.
    sampleQueue.format(FORMAT_1);
    assertNoSamplesToRead(FORMAT_1);
    // The same applies for a format that's equal (but a different object).
    sampleQueue.format(FORMAT_1_COPY);
    assertNoSamplesToRead(FORMAT_1);
  }

  @Test
  public void multipleFormatsDeduplicated() {
    sampleQueue.format(FORMAT_1);
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);
    // Writing multiple formats should not cause a format change on the read side, provided the last
    // format to be written is equal to the format of the previous sample.
    sampleQueue.format(FORMAT_2);
    sampleQueue.format(FORMAT_1_COPY);
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.sampleMetadata(1000, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);

    assertReadFormat(false, FORMAT_1);
    assertReadSample(
        0,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);
    // Assert the second sample is read without a format change.
    assertReadSample(
        1000,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);

    // The same applies if the queue is empty when the formats are written.
    sampleQueue.format(FORMAT_2);
    sampleQueue.format(FORMAT_1);
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.sampleMetadata(2000, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);

    // Assert the third sample is read without a format change.
    assertReadSample(
        2000,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);
  }

  @Test
  public void readSingleSamples() {
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);

    assertAllocationCount(1);
    // Nothing to read.
    assertNoSamplesToRead(null);

    sampleQueue.format(FORMAT_1);

    // Read the format.
    assertReadFormat(false, FORMAT_1);
    // Nothing to read.
    assertNoSamplesToRead(FORMAT_1);

    sampleQueue.sampleMetadata(1000, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);

    // If formatRequired, should read the format rather than the sample.
    assertReadFormat(true, FORMAT_1);
    // Otherwise should read the sample.
    assertReadSample(
        1000,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);
    // Allocation should still be held.
    assertAllocationCount(1);
    sampleQueue.discardToRead();
    // The allocation should have been released.
    assertAllocationCount(0);

    // Nothing to read.
    assertNoSamplesToRead(FORMAT_1);

    // Write a second sample followed by one byte that does not belong to it.
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.sampleMetadata(2000, 0, ALLOCATION_SIZE - 1, 1, null);

    // If formatRequired, should read the format rather than the sample.
    assertReadFormat(true, FORMAT_1);
    // Read the sample.
    assertReadSample(
        2000,
        /* isKeyFrame= */ false,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE - 1);
    // Allocation should still be held.
    assertAllocationCount(1);
    sampleQueue.discardToRead();
    // The last byte written to the sample queue may belong to a sample whose metadata has yet to be
    // written, so an allocation should still be held.
    assertAllocationCount(1);

    // Write metadata for a third sample containing the remaining byte.
    sampleQueue.sampleMetadata(3000, 0, 1, 0, null);

    // If formatRequired, should read the format rather than the sample.
    assertReadFormat(true, FORMAT_1);
    // Read the sample.
    assertReadSample(
        3000,
        /* isKeyFrame= */ false,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        ALLOCATION_SIZE - 1,
        1);
    // Allocation should still be held.
    assertAllocationCount(1);
    sampleQueue.discardToRead();
    // The allocation should have been released.
    assertAllocationCount(0);
  }

  @Test
  public void readSingleSampleWithLoadingFinished() {
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.format(FORMAT_1);
    sampleQueue.sampleMetadata(1000, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);

    assertAllocationCount(1);
    // If formatRequired, should read the format rather than the sample.
    assertReadFormat(true, FORMAT_1);
    // Otherwise should read the sample with loading finished.
    assertReadLastSample(
        1000,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);
    // Allocation should still be held.
    assertAllocationCount(1);

    sampleQueue.discardToRead();
    // The allocation should have been released.
    assertAllocationCount(0);
  }

  @Test
  public void readMultiSamples() {
    writeTestData();
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
    assertAllocationCount(10);
    assertReadTestData();
    assertAllocationCount(10);
    sampleQueue.discardToRead();
    assertAllocationCount(0);
  }

  @Test
  public void readMultiSamplesTwice() {
    writeTestData();
    writeTestData();
    assertAllocationCount(20);
    assertReadTestData(FORMAT_2);
    assertReadTestData(FORMAT_2);
    assertAllocationCount(20);
    sampleQueue.discardToRead();
    assertAllocationCount(0);
  }

  @Test
  public void readMultiWithSeek() {
    writeTestData();
    assertReadTestData();
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(0);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertAllocationCount(10);

    sampleQueue.seekTo(0);
    assertAllocationCount(10);
    // Read again.
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(0);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(0);
    assertReadTestData();
  }

  @Test
  public void emptyQueueReturnsLoadingFinished() {
    sampleQueue.sampleData(new ParsableByteArray(DATA), DATA.length);
    assertThat(sampleQueue.isReady(/* loadingFinished= */ false)).isFalse();
    assertThat(sampleQueue.isReady(/* loadingFinished= */ true)).isTrue();
  }

  @Test
  public void isReadyWithUpstreamFormatOnlyReturnsTrue() {
    sampleQueue.format(FORMAT_ENCRYPTED);
    assertThat(sampleQueue.isReady(/* loadingFinished= */ false)).isTrue();
  }

  @Test
  public void isReadyReturnsTrueForValidDrmSession() {
    writeTestDataWithEncryptedSections();
    assertReadFormat(/* formatRequired= */ false, FORMAT_ENCRYPTED_WITH_EXO_MEDIA_CRYPTO_TYPE);
    assertThat(sampleQueue.isReady(/* loadingFinished= */ false)).isFalse();
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    assertThat(sampleQueue.isReady(/* loadingFinished= */ false)).isTrue();
  }

  @Test
  public void isReadyReturnsTrueForClearSampleAndPlayClearSamplesWithoutKeysIsTrue() {
    when(mockDrmSession.playClearSamplesWithoutKeys()).thenReturn(true);
    // We recreate the queue to ensure the mock DRM session manager flags are taken into account.
    sampleQueue = new SampleQueue(allocator, mockDrmSessionManager, eventDispatcher);
    writeTestDataWithEncryptedSections();
    assertThat(sampleQueue.isReady(/* loadingFinished= */ false)).isTrue();
  }

  @Test
  public void readEncryptedSectionsWaitsForKeys() {
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED);
    writeTestDataWithEncryptedSections();

    assertReadFormat(/* formatRequired= */ false, FORMAT_ENCRYPTED_WITH_EXO_MEDIA_CRYPTO_TYPE);
    assertReadNothing(/* formatRequired= */ false);
    assertThat(inputBuffer.waitingForKeys).isTrue();
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    assertReadEncryptedSample(/* sampleIndex= */ 0);
    assertThat(inputBuffer.waitingForKeys).isFalse();
  }

  @Test
  public void readEncryptedSectionsPopulatesDrmSession() {
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    writeTestDataWithEncryptedSections();

    int result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isSameInstanceAs(mockDrmSession);
    assertReadEncryptedSample(/* sampleIndex= */ 0);
    assertReadEncryptedSample(/* sampleIndex= */ 1);
    formatHolder.clear();
    assertThat(formatHolder.drmSession).isNull();
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isNull();
    assertReadEncryptedSample(/* sampleIndex= */ 2);
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isSameInstanceAs(mockDrmSession);
  }

  @Test
  public void allowPlaceholderSessionPopulatesDrmSession() {
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    DrmSession mockPlaceholderDrmSession = Mockito.mock(DrmSession.class);
    when(mockPlaceholderDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    mockDrmSessionManager.mockPlaceholderDrmSession = mockPlaceholderDrmSession;
    writeTestDataWithEncryptedSections();

    int result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isSameInstanceAs(mockDrmSession);
    assertReadEncryptedSample(/* sampleIndex= */ 0);
    assertReadEncryptedSample(/* sampleIndex= */ 1);
    formatHolder.clear();
    assertThat(formatHolder.drmSession).isNull();
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isSameInstanceAs(mockPlaceholderDrmSession);
    assertReadEncryptedSample(/* sampleIndex= */ 2);
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    assertThat(formatHolder.drmSession).isSameInstanceAs(mockDrmSession);
    assertReadEncryptedSample(/* sampleIndex= */ 3);
  }

  @Test
  public void trailingCryptoInfoInitializationVectorBytesZeroed() {
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    DrmSession mockPlaceholderDrmSession = Mockito.mock(DrmSession.class);
    when(mockPlaceholderDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    mockDrmSessionManager.mockPlaceholderDrmSession = mockPlaceholderDrmSession;

    writeFormat(ENCRYPTED_SAMPLE_FORMATS[0]);
    byte[] sampleData = new byte[] {0, 1, 2};
    byte[] initializationVector = new byte[] {7, 6, 5, 4, 3, 2, 1, 0};
    byte[] encryptedSampleData =
        Bytes.concat(
            new byte[] {
              0x08, // subsampleEncryption = false (1 bit), ivSize = 8 (7 bits).
            },
            initializationVector,
            sampleData);
    writeSample(
        encryptedSampleData, /* timestampUs= */ 0, BUFFER_FLAG_KEY_FRAME | BUFFER_FLAG_ENCRYPTED);

    int result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);

    // Fill cryptoInfo.iv with non-zero data. When the 8 byte initialization vector is written into
    // it, we expect the trailing 8 bytes to be zeroed.
    inputBuffer.cryptoInfo.iv = new byte[16];
    Arrays.fill(inputBuffer.cryptoInfo.iv, (byte) 1);

    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_BUFFER_READ);

    // Assert cryptoInfo.iv contains the 8-byte initialization vector and that the trailing 8 bytes
    // have been zeroed.
    byte[] expectedInitializationVector = Arrays.copyOf(initializationVector, 16);
    assertArrayEquals(expectedInitializationVector, inputBuffer.cryptoInfo.iv);
  }

  @Test
  public void readWithErrorSessionReadsNothingAndThrows() throws IOException {
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED);
    writeTestDataWithEncryptedSections();

    assertReadFormat(/* formatRequired= */ false, FORMAT_ENCRYPTED_WITH_EXO_MEDIA_CRYPTO_TYPE);
    assertReadNothing(/* formatRequired= */ false);
    sampleQueue.maybeThrowError();
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_ERROR);
    when(mockDrmSession.getError())
        .thenReturn(
            new DrmSession.DrmSessionException(
                new Exception(), PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR));
    assertReadNothing(/* formatRequired= */ false);
    try {
      sampleQueue.maybeThrowError();
      Assert.fail();
    } catch (IOException e) {
      // Expected.
    }
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED_WITH_KEYS);
    assertReadEncryptedSample(/* sampleIndex= */ 0);
  }

  @Test
  public void allowPlayClearSamplesWithoutKeysReadsClearSamples() {
    when(mockDrmSession.playClearSamplesWithoutKeys()).thenReturn(true);
    // We recreate the queue to ensure the mock DRM session manager flags are taken into account.
    sampleQueue = new SampleQueue(allocator, mockDrmSessionManager, eventDispatcher);
    when(mockDrmSession.getState()).thenReturn(DrmSession.STATE_OPENED);
    writeTestDataWithEncryptedSections();

    assertReadFormat(/* formatRequired= */ false, FORMAT_ENCRYPTED_WITH_EXO_MEDIA_CRYPTO_TYPE);
    assertReadEncryptedSample(/* sampleIndex= */ 0);
  }

  @Test
  public void seekAfterDiscard() {
    writeTestData();
    assertReadTestData();
    sampleQueue.discardToRead();
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(8);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertAllocationCount(0);

    sampleQueue.seekTo(0);
    assertAllocationCount(0);
    // Can't read again.
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(8);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertReadEndOfStream(false);
  }

  @Test
  public void skipToEnd() {
    writeTestData();
    sampleQueue.skip(
        sampleQueue.getSkipCount(/* timeUs= */ MAX_VALUE, /* allowEndOfQueue= */ true));
    assertAllocationCount(10);
    sampleQueue.discardToRead();
    assertAllocationCount(0);
    // Despite skipping all samples, we should still read the last format, since this is the
    // expected format for a subsequent sample.
    assertReadFormat(false, FORMAT_2);
    // Once the format has been read, there's nothing else to read.
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void skipToEndRetainsUnassignedData() {
    sampleQueue.format(FORMAT_1);
    sampleQueue.sampleData(new ParsableByteArray(DATA), ALLOCATION_SIZE);
    sampleQueue.skip(
        sampleQueue.getSkipCount(/* timeUs= */ MAX_VALUE, /* allowEndOfQueue= */ true));
    assertAllocationCount(1);
    sampleQueue.discardToRead();
    // Skipping shouldn't discard data that may belong to a sample whose metadata has yet to be
    // written.
    assertAllocationCount(1);
    // We should be able to read the format.
    assertReadFormat(false, FORMAT_1);
    // Once the format has been read, there's nothing else to read.
    assertNoSamplesToRead(FORMAT_1);

    sampleQueue.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, ALLOCATION_SIZE, 0, null);
    // Once the metadata has been written, check the sample can be read as expected.
    assertReadSample(
        /* timeUs= */ 0,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        ALLOCATION_SIZE);
    assertNoSamplesToRead(FORMAT_1);
    assertAllocationCount(1);
    sampleQueue.discardToRead();
    assertAllocationCount(0);
  }

  @Test
  public void skipToBeforeBuffer() {
    writeTestData();
    int skipCount =
        sampleQueue.getSkipCount(SAMPLE_TIMESTAMPS[0] - 1, /* allowEndOfQueue= */ false);
    // Should have no effect (we're already at the first frame).
    assertThat(skipCount).isEqualTo(0);
    sampleQueue.skip(skipCount);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void skipToStartOfBuffer() {
    writeTestData();
    int skipCount = sampleQueue.getSkipCount(SAMPLE_TIMESTAMPS[0], /* allowEndOfQueue= */ false);
    // Should have no effect (we're already at the first frame).
    assertThat(skipCount).isEqualTo(0);
    sampleQueue.skip(skipCount);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void skipToEndOfBuffer() {
    writeTestData();
    int skipCount = sampleQueue.getSkipCount(LAST_SAMPLE_TIMESTAMP, /* allowEndOfQueue= */ false);
    // Should advance to 2nd keyframe (the 4th frame).
    assertThat(skipCount).isEqualTo(4);
    sampleQueue.skip(skipCount);
    assertReadTestData(/* startFormat= */ null, DATA_SECOND_KEYFRAME_INDEX);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void skipToAfterBuffer() {
    writeTestData();
    int skipCount =
        sampleQueue.getSkipCount(LAST_SAMPLE_TIMESTAMP + 1, /* allowEndOfQueue= */ false);
    // Should advance to 2nd keyframe (the 4th frame).
    assertThat(skipCount).isEqualTo(4);
    sampleQueue.skip(skipCount);
    assertReadTestData(/* startFormat= */ null, DATA_SECOND_KEYFRAME_INDEX);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToBeforeBuffer_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success =
        sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0] - 1, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isFalse();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToStartOfBuffer_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success = sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0], /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToEndOfBuffer_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success = sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE + 4);
    assertReadTestData(
        /* startFormat= */ null,
        DATA_SECOND_KEYFRAME_INDEX,
        /* sampleCount= */ SAMPLE_TIMESTAMPS.length - DATA_SECOND_KEYFRAME_INDEX,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ LAST_SAMPLE_TIMESTAMP);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToAfterBuffer_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success =
        sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP + 1, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isFalse();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToAfterBufferAllowed_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success =
        sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP + 1, /* allowTimeBeyondBuffer= */ true);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE + 4);
    assertReadTestData(
        /* startFormat= */ null,
        DATA_SECOND_KEYFRAME_INDEX,
        /* sampleCount= */ SAMPLE_TIMESTAMPS.length - DATA_SECOND_KEYFRAME_INDEX,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ LAST_SAMPLE_TIMESTAMP + 1);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToEndAndBackToStart_notAllSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeTestData();

    boolean success = sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE + 4);
    assertReadTestData(
        /* startFormat= */ null,
        DATA_SECOND_KEYFRAME_INDEX,
        /* sampleCount= */ SAMPLE_TIMESTAMPS.length - DATA_SECOND_KEYFRAME_INDEX,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ LAST_SAMPLE_TIMESTAMP);
    assertNoSamplesToRead(FORMAT_2);

    // Seek back to the start.
    success = sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0], /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadTestData();
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void seekToBeforeBuffer_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success =
        sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0] - 1, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isFalse();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadSyncSampleOnlyTestData();
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void seekToStartOfBuffer_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success = sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0], /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadSyncSampleOnlyTestData();
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void seekToEndOfBuffer_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success = sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex())
        .isEqualTo(CLOSE_TO_CAPACITY_SIZE + SAMPLE_TIMESTAMPS.length - 1);
    assertReadSyncSampleOnlyTestData(
        /* firstSampleIndex= */ SAMPLE_TIMESTAMPS.length - 1, /* sampleCount= */ 1);
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void seekToAfterBuffer_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success =
        sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP + 1, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isFalse();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadSyncSampleOnlyTestData();
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void seekToAfterBufferAllowed_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success =
        sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP + 1, /* allowTimeBeyondBuffer= */ true);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex())
        .isEqualTo(CLOSE_TO_CAPACITY_SIZE + SAMPLE_TIMESTAMPS.length);
    assertReadFormat(/* formatRequired= */ false, FORMAT_SYNC_SAMPLE_ONLY_2);
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void seekToEndAndBackToStart_allSamplesAreSyncSamples() {
    writeAndDiscardPlaceholderSamples(CLOSE_TO_CAPACITY_SIZE);
    writeSyncSamplesOnlyTestData();

    boolean success = sampleQueue.seekTo(LAST_SAMPLE_TIMESTAMP, /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex())
        .isEqualTo(CLOSE_TO_CAPACITY_SIZE + SAMPLE_TIMESTAMPS.length - 1);
    assertReadSyncSampleOnlyTestData(
        /* firstSampleIndex= */ SAMPLE_TIMESTAMPS.length - 1, /* sampleCount= */ 1);
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);

    // Seek back to the start.
    success = sampleQueue.seekTo(SAMPLE_TIMESTAMPS[0], /* allowTimeBeyondBuffer= */ false);

    assertThat(success).isTrue();
    assertThat(sampleQueue.getReadIndex()).isEqualTo(CLOSE_TO_CAPACITY_SIZE);
    assertReadSyncSampleOnlyTestData();
    assertNoSamplesToRead(FORMAT_SYNC_SAMPLE_ONLY_2);
  }

  @Test
  public void setStartTimeUs_allSamplesAreSyncSamples_discardsOnWriteSide() {
    sampleQueue.setStartTimeUs(LAST_SAMPLE_TIMESTAMP);
    writeSyncSamplesOnlyTestData();

    assertThat(sampleQueue.getReadIndex()).isEqualTo(0);

    assertReadFormat(/* formatRequired= */ false, FORMAT_SYNC_SAMPLE_ONLY_2);
    assertReadSample(
        SAMPLE_TIMESTAMPS[7],
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        DATA.length - SAMPLE_OFFSETS[7] - SAMPLE_SIZES[7],
        SAMPLE_SIZES[7]);
  }

  @Test
  public void setStartTimeUs_notAllSamplesAreSyncSamples_discardsOnReadSide() {
    sampleQueue.setStartTimeUs(LAST_SAMPLE_TIMESTAMP);
    writeTestData();

    assertThat(sampleQueue.getReadIndex()).isEqualTo(0);
    assertReadTestData(
        /* startFormat= */ null,
        /* firstSampleIndex= */ 0,
        /* sampleCount= */ SAMPLE_TIMESTAMPS.length,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ LAST_SAMPLE_TIMESTAMP);
  }

  @Test
  public void discardToEnd() {
    writeTestData();
    // Should discard everything.
    sampleQueue.discardToEnd();
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(8);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertAllocationCount(0);
    // We should still be able to read the upstream format.
    assertReadFormat(false, FORMAT_2);
    // We should be able to write and read subsequent samples.
    writeTestData();
    assertReadTestData(FORMAT_2);
  }

  @Test
  public void discardToStopAtReadPosition() {
    writeTestData();
    // Shouldn't discard anything.
    sampleQueue.discardTo(LAST_SAMPLE_TIMESTAMP, false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(0);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(0);
    assertAllocationCount(10);
    // Read the first sample.
    assertReadTestData(/* startFormat= */ null, 0, 1);
    // Shouldn't discard anything.
    sampleQueue.discardTo(SAMPLE_TIMESTAMPS[1] - 1, false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(0);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(1);
    assertAllocationCount(10);
    // Should discard the read sample.
    sampleQueue.discardTo(SAMPLE_TIMESTAMPS[1], false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(1);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(1);
    assertAllocationCount(9);
    // Shouldn't discard anything.
    sampleQueue.discardTo(LAST_SAMPLE_TIMESTAMP, false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(1);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(1);
    assertAllocationCount(9);
    // Should be able to read the remaining samples.
    assertReadTestData(FORMAT_1, 1, 7);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(1);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    // Should discard up to the second last sample
    sampleQueue.discardTo(LAST_SAMPLE_TIMESTAMP - 1, false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(6);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertAllocationCount(3);
    // Should discard up the last sample
    sampleQueue.discardTo(LAST_SAMPLE_TIMESTAMP, false, true);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(7);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(8);
    assertAllocationCount(1);
  }

  @Test
  public void discardTo_withDuplicateTimestamps_discardsOnlyToFirstMatch() {
    writeTestData(
        DATA,
        SAMPLE_SIZES,
        SAMPLE_OFFSETS,
        /* sampleTimestamps= */ new long[] {0, 1000, 1000, 1000, 2000, 2000, 2000, 2000},
        SAMPLE_FORMATS,
        /* sampleFlags= */ new int[] {
          BUFFER_FLAG_KEY_FRAME,
          0,
          BUFFER_FLAG_KEY_FRAME,
          BUFFER_FLAG_KEY_FRAME,
          0,
          0,
          BUFFER_FLAG_KEY_FRAME,
          BUFFER_FLAG_KEY_FRAME
        });

    // Discard to first keyframe exactly matching the specified time.
    sampleQueue.discardTo(
        /* timeUs= */ 1000, /* toKeyframe= */ true, /* stopAtReadPosition= */ false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(2);

    // Do nothing when trying again.
    sampleQueue.discardTo(
        /* timeUs= */ 1000, /* toKeyframe= */ true, /* stopAtReadPosition= */ false);
    sampleQueue.discardTo(
        /* timeUs= */ 1000, /* toKeyframe= */ false, /* stopAtReadPosition= */ false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(2);

    // Discard to first frame exactly matching the specified time.
    sampleQueue.discardTo(
        /* timeUs= */ 2000, /* toKeyframe= */ false, /* stopAtReadPosition= */ false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(4);

    // Do nothing when trying again.
    sampleQueue.discardTo(
        /* timeUs= */ 2000, /* toKeyframe= */ false, /* stopAtReadPosition= */ false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(4);

    // Discard to first keyframe at same timestamp.
    sampleQueue.discardTo(
        /* timeUs= */ 2000, /* toKeyframe= */ true, /* stopAtReadPosition= */ false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(6);
  }

  @Test
  public void discardToDontStopAtReadPosition() {
    writeTestData();
    // Shouldn't discard anything.
    sampleQueue.discardTo(SAMPLE_TIMESTAMPS[1] - 1, false, false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(0);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(0);
    assertAllocationCount(10);
    // Should discard the first sample.
    sampleQueue.discardTo(SAMPLE_TIMESTAMPS[1], false, false);
    assertThat(sampleQueue.getFirstIndex()).isEqualTo(1);
    assertThat(sampleQueue.getReadIndex()).isEqualTo(1);
    assertAllocationCount(9);
    // Should be able to read the remaining samples.
    assertReadTestData(FORMAT_1, 1, 7);
  }

  @Test
  public void discardUpstreamFrom() {
    writeTestData();
    sampleQueue.discardUpstreamFrom(8000);
    assertAllocationCount(10);
    sampleQueue.discardUpstreamFrom(7000);
    assertAllocationCount(9);
    sampleQueue.discardUpstreamFrom(6000);
    assertAllocationCount(7);
    sampleQueue.discardUpstreamFrom(5000);
    assertAllocationCount(5);
    sampleQueue.discardUpstreamFrom(4000);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamFrom(3000);
    assertAllocationCount(3);
    sampleQueue.discardUpstreamFrom(2000);
    assertAllocationCount(2);
    sampleQueue.discardUpstreamFrom(1000);
    assertAllocationCount(1);
    sampleQueue.discardUpstreamFrom(0);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamFromMulti() {
    writeTestData();
    sampleQueue.discardUpstreamFrom(4000);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamFrom(0);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamFromNonSampleTimestamps() {
    writeTestData();
    sampleQueue.discardUpstreamFrom(3500);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamFrom(500);
    assertAllocationCount(1);
    sampleQueue.discardUpstreamFrom(0);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamFromBeforeRead() {
    writeTestData();
    sampleQueue.discardUpstreamFrom(4000);
    assertAllocationCount(4);
    assertReadTestData(null, 0, 4);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamFromAfterRead() {
    writeTestData();
    assertReadTestData(null, 0, 3);
    sampleQueue.discardUpstreamFrom(8000);
    assertAllocationCount(10);
    sampleQueue.discardToRead();
    assertAllocationCount(7);
    sampleQueue.discardUpstreamFrom(7000);
    assertAllocationCount(6);
    sampleQueue.discardUpstreamFrom(6000);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamFrom(5000);
    assertAllocationCount(2);
    sampleQueue.discardUpstreamFrom(4000);
    assertAllocationCount(1);
    sampleQueue.discardUpstreamFrom(3000);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void largestQueuedTimestampWithDiscardUpstreamFrom() {
    writeTestData();
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
    sampleQueue.discardUpstreamFrom(SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 1]);
    // Discarding from upstream should reduce the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs())
        .isEqualTo(SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 2]);
    sampleQueue.discardUpstreamFrom(0);
    // Discarding everything from upstream without reading should unset the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(MIN_VALUE);
  }

  @Test
  public void largestQueuedTimestampWithDiscardUpstreamFromDecodeOrder() {
    long[] decodeOrderTimestamps = new long[] {0, 3000, 2000, 1000, 4000, 7000, 6000, 5000};
    writeTestData(
        DATA, SAMPLE_SIZES, SAMPLE_OFFSETS, decodeOrderTimestamps, SAMPLE_FORMATS, SAMPLE_FLAGS);
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(7000);
    sampleQueue.discardUpstreamFrom(SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 2]);
    // Discarding the last two samples should not change the largest timestamp, due to the decode
    // ordering of the timestamps.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(7000);
    sampleQueue.discardUpstreamFrom(SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 3]);
    // Once a third sample is discarded, the largest timestamp should have changed.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(4000);
    sampleQueue.discardUpstreamFrom(0);
    // Discarding everything from upstream without reading should unset the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(MIN_VALUE);
  }

  @Test
  public void discardUpstream() {
    writeTestData();
    sampleQueue.discardUpstreamSamples(8);
    assertAllocationCount(10);
    sampleQueue.discardUpstreamSamples(7);
    assertAllocationCount(9);
    sampleQueue.discardUpstreamSamples(6);
    assertAllocationCount(7);
    sampleQueue.discardUpstreamSamples(5);
    assertAllocationCount(5);
    sampleQueue.discardUpstreamSamples(4);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamSamples(3);
    assertAllocationCount(3);
    sampleQueue.discardUpstreamSamples(2);
    assertAllocationCount(2);
    sampleQueue.discardUpstreamSamples(1);
    assertAllocationCount(1);
    sampleQueue.discardUpstreamSamples(0);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamMulti() {
    writeTestData();
    sampleQueue.discardUpstreamSamples(4);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamSamples(0);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamBeforeRead() {
    writeTestData();
    sampleQueue.discardUpstreamSamples(4);
    assertAllocationCount(4);
    assertReadTestData(/* startFormat= */ null, 0, 4);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void discardUpstreamAfterRead() {
    writeTestData();
    assertReadTestData(/* startFormat= */ null, 0, 3);
    sampleQueue.discardUpstreamSamples(8);
    assertAllocationCount(10);
    sampleQueue.discardToRead();
    assertAllocationCount(7);
    sampleQueue.discardUpstreamSamples(7);
    assertAllocationCount(6);
    sampleQueue.discardUpstreamSamples(6);
    assertAllocationCount(4);
    sampleQueue.discardUpstreamSamples(5);
    assertAllocationCount(2);
    sampleQueue.discardUpstreamSamples(4);
    assertAllocationCount(1);
    sampleQueue.discardUpstreamSamples(3);
    assertAllocationCount(0);
    assertReadFormat(false, FORMAT_2);
    assertNoSamplesToRead(FORMAT_2);
  }

  @Test
  public void largestQueuedTimestampWithDiscardUpstream() {
    writeTestData();
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
    sampleQueue.discardUpstreamSamples(SAMPLE_TIMESTAMPS.length - 1);
    // Discarding from upstream should reduce the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs())
        .isEqualTo(SAMPLE_TIMESTAMPS[SAMPLE_TIMESTAMPS.length - 2]);
    sampleQueue.discardUpstreamSamples(0);
    // Discarding everything from upstream without reading should unset the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(MIN_VALUE);
  }

  @Test
  public void largestQueuedTimestampWithDiscardUpstreamDecodeOrder() {
    long[] decodeOrderTimestamps = new long[] {0, 3000, 2000, 1000, 4000, 7000, 6000, 5000};
    writeTestData(
        DATA, SAMPLE_SIZES, SAMPLE_OFFSETS, decodeOrderTimestamps, SAMPLE_FORMATS, SAMPLE_FLAGS);
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(7000);
    sampleQueue.discardUpstreamSamples(SAMPLE_TIMESTAMPS.length - 2);
    // Discarding the last two samples should not change the largest timestamp, due to the decode
    // ordering of the timestamps.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(7000);
    sampleQueue.discardUpstreamSamples(SAMPLE_TIMESTAMPS.length - 3);
    // Once a third sample is discarded, the largest timestamp should have changed.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(4000);
    sampleQueue.discardUpstreamSamples(0);
    // Discarding everything from upstream without reading should unset the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(MIN_VALUE);
  }

  @Test
  public void largestQueuedTimestampWithRead() {
    writeTestData();
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
    assertReadTestData();
    // Reading everything should not reduce the largest timestamp.
    assertThat(sampleQueue.getLargestQueuedTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
  }

  @Test
  public void largestReadTimestampWithReadAll() {
    writeTestData();
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(MIN_VALUE);
    assertReadTestData();
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(LAST_SAMPLE_TIMESTAMP);
  }

  @Test
  public void largestReadTimestampWithReads() {
    writeTestData();
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(MIN_VALUE);

    assertReadTestData(/* startFormat= */ null, 0, 2);
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(SAMPLE_TIMESTAMPS[1]);

    assertReadTestData(SAMPLE_FORMATS[1], 2, 3);
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(SAMPLE_TIMESTAMPS[4]);
  }

  @Test
  public void largestReadTimestampWithDiscard() {
    // Discarding shouldn't change the read timestamp.
    writeTestData();
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(MIN_VALUE);
    sampleQueue.discardUpstreamSamples(5);
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(MIN_VALUE);

    assertReadTestData(/* startFormat= */ null, 0, 3);
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(SAMPLE_TIMESTAMPS[2]);

    sampleQueue.discardUpstreamSamples(3);
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(SAMPLE_TIMESTAMPS[2]);
    sampleQueue.discardToRead();
    assertThat(sampleQueue.getLargestReadTimestampUs()).isEqualTo(SAMPLE_TIMESTAMPS[2]);
  }

  @Test
  public void setSampleOffsetBeforeData() {
    long sampleOffsetUs = 1000;
    sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    writeTestData();
    assertReadTestData(
        /* startFormat= */ null,
        /* firstSampleIndex= */ 0,
        /* sampleCount= */ 8,
        sampleOffsetUs,
        /* decodeOnlyUntilUs= */ 0);
    assertReadEndOfStream(/* formatRequired= */ false);
  }

  @Test
  public void setSampleOffsetBetweenSamples() {
    writeTestData();
    long sampleOffsetUs = 1000;
    sampleQueue.setSampleOffsetUs(sampleOffsetUs);

    // Write a final sample now the offset is set.
    long unadjustedTimestampUs = LAST_SAMPLE_TIMESTAMP + 1234;
    writeSample(DATA, unadjustedTimestampUs, /* sampleFlags= */ 0);

    assertReadTestData();
    // We expect to read the format adjusted to account for the sample offset, followed by the final
    // sample and then the end of stream.
    assertReadFormat(
        /* formatRequired= */ false,
        FORMAT_2.buildUpon().setSubsampleOffsetUs(sampleOffsetUs).build());
    assertReadSample(
        unadjustedTimestampUs + sampleOffsetUs,
        /* isKeyFrame= */ false,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadEndOfStream(/* formatRequired= */ false);
  }

  @Test
  public void adjustUpstreamFormat() {
    String label = "label";
    sampleQueue =
        new SampleQueue(allocator, mockDrmSessionManager, eventDispatcher) {
          @Override
          public Format getAdjustedUpstreamFormat(Format format) {
            return super.getAdjustedUpstreamFormat(copyWithLabel(format, label));
          }
        };

    writeFormat(FORMAT_1);
    assertReadFormat(/* formatRequired= */ false, copyWithLabel(FORMAT_1, label));
    assertReadEndOfStream(/* formatRequired= */ false);
  }

  @Test
  public void invalidateUpstreamFormatAdjustment() {
    AtomicReference<String> label = new AtomicReference<>("label1");
    sampleQueue =
        new SampleQueue(allocator, mockDrmSessionManager, eventDispatcher) {
          @Override
          public Format getAdjustedUpstreamFormat(Format format) {
            return super.getAdjustedUpstreamFormat(copyWithLabel(format, label.get()));
          }
        };

    writeFormat(FORMAT_1);
    writeSample(DATA, /* timestampUs= */ 0, BUFFER_FLAG_KEY_FRAME);

    // Make a change that'll affect the SampleQueue's format adjustment, and invalidate it.
    label.set("label2");
    sampleQueue.invalidateUpstreamFormatAdjustment();

    writeSample(DATA, /* timestampUs= */ 1, /* sampleFlags= */ 0);

    assertReadFormat(/* formatRequired= */ false, copyWithLabel(FORMAT_1, "label1"));
    assertReadSample(
        /* timeUs= */ 0,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadFormat(/* formatRequired= */ false, copyWithLabel(FORMAT_1, "label2"));
    assertReadSample(
        /* timeUs= */ 1,
        /* isKeyFrame= */ false,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadEndOfStream(/* formatRequired= */ false);
  }

  @Test
  public void splice() {
    writeTestData();
    sampleQueue.splice();
    // Splice should succeed, replacing the last 4 samples with the sample being written.
    long spliceSampleTimeUs = SAMPLE_TIMESTAMPS[4];
    writeFormat(FORMAT_SPLICED);
    writeSample(DATA, spliceSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME);
    assertReadTestData(/* startFormat= */ null, 0, 4);
    assertReadFormat(false, FORMAT_SPLICED);
    assertReadSample(
        spliceSampleTimeUs,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadEndOfStream(false);
  }

  @Test
  public void spliceAfterRead() {
    writeTestData();
    assertReadTestData(/* startFormat= */ null, 0, 4);
    sampleQueue.splice();
    // Splice should fail, leaving the last 4 samples unchanged.
    long spliceSampleTimeUs = SAMPLE_TIMESTAMPS[3];
    writeFormat(FORMAT_SPLICED);
    writeSample(DATA, spliceSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME);
    assertReadTestData(SAMPLE_FORMATS[3], 4, 4);
    assertReadEndOfStream(false);

    sampleQueue.seekTo(0);
    assertReadTestData(/* startFormat= */ null, 0, 4);
    sampleQueue.splice();
    // Splice should succeed, replacing the last 4 samples with the sample being written
    spliceSampleTimeUs = SAMPLE_TIMESTAMPS[3] + 1;
    writeFormat(FORMAT_SPLICED);
    writeSample(DATA, spliceSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME);
    assertReadFormat(false, FORMAT_SPLICED);
    assertReadSample(
        spliceSampleTimeUs,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadEndOfStream(false);
  }

  @Test
  public void spliceWithSampleOffset() {
    long sampleOffsetUs = 30000;
    sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    writeTestData();
    sampleQueue.splice();
    // Splice should succeed, replacing the last 4 samples with the sample being written.
    long spliceSampleTimeUs = SAMPLE_TIMESTAMPS[4];
    writeFormat(FORMAT_SPLICED);
    writeSample(DATA, spliceSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME);
    assertReadTestData(/* startFormat= */ null, 0, 4, sampleOffsetUs, /* decodeOnlyUntilUs= */ 0);
    assertReadFormat(
        false, FORMAT_SPLICED.buildUpon().setSubsampleOffsetUs(sampleOffsetUs).build());
    assertReadSample(
        spliceSampleTimeUs + sampleOffsetUs,
        /* isKeyFrame= */ true,
        /* isDecodeOnly= */ false,
        /* isEncrypted= */ false,
        DATA,
        /* offset= */ 0,
        DATA.length);
    assertReadEndOfStream(false);
  }

  @Test
  public void setStartTime() {}

  // Internal methods.

  /** Writes standard test data to {@code sampleQueue}. */
  private void writeTestData() {
    writeTestData(
        DATA, SAMPLE_SIZES, SAMPLE_OFFSETS, SAMPLE_TIMESTAMPS, SAMPLE_FORMATS, SAMPLE_FLAGS);
  }

  /** Writes test data to {@code sampleQueue} with sync-sample-only formats. */
  private void writeSyncSamplesOnlyTestData() {
    writeTestData(
        DATA,
        SAMPLE_SIZES,
        SAMPLE_OFFSETS,
        SAMPLE_TIMESTAMPS,
        SAMPLE_FORMATS_SYNC_SAMPLES_ONLY,
        SAMPLE_FLAGS_SYNC_SAMPLES_ONLY);
  }

  /** Writes the specified test data to {@code sampleQueue}. */
  @SuppressWarnings("ReferenceEquality")
  private void writeTestData(
      byte[] data,
      int[] sampleSizes,
      int[] sampleOffsets,
      long[] sampleTimestamps,
      Format[] sampleFormats,
      int[] sampleFlags) {
    sampleQueue.sampleData(new ParsableByteArray(data), data.length);
    Format format = null;
    for (int i = 0; i < sampleTimestamps.length; i++) {
      if (sampleFormats[i] != format) {
        sampleQueue.format(sampleFormats[i]);
        format = sampleFormats[i];
      }
      sampleQueue.sampleMetadata(
          sampleTimestamps[i],
          sampleFlags[i],
          sampleSizes[i],
          sampleOffsets[i],
          (sampleFlags[i] & C.BUFFER_FLAG_ENCRYPTED) != 0 ? CRYPTO_DATA : null);
    }
  }

  private void writeTestDataWithEncryptedSections() {
    writeTestData(
        ENCRYPTED_SAMPLE_DATA,
        ENCRYPTED_SAMPLE_SIZES,
        ENCRYPTED_SAMPLE_OFFSETS,
        ENCRYPTED_SAMPLE_TIMESTAMPS,
        ENCRYPTED_SAMPLE_FORMATS,
        ENCRYPTED_SAMPLES_FLAGS);
  }

  /** Writes a {@link Format} to the {@code sampleQueue}. */
  private void writeFormat(Format format) {
    sampleQueue.format(format);
  }

  /** Writes a single sample to {@code sampleQueue}. */
  private void writeSample(byte[] data, long timestampUs, int sampleFlags) {
    sampleQueue.sampleData(new ParsableByteArray(data), data.length);
    sampleQueue.sampleMetadata(
        timestampUs,
        sampleFlags,
        data.length,
        /* offset= */ 0,
        (sampleFlags & C.BUFFER_FLAG_ENCRYPTED) != 0 ? CRYPTO_DATA : null);
  }

  private void writeAndDiscardPlaceholderSamples(int sampleCount) {
    writeFormat(FORMAT_SYNC_SAMPLE_ONLY_1);
    for (int i = 0; i < sampleCount; i++) {
      writeSample(
          Util.EMPTY_BYTE_ARRAY, /* timestampUs= */ 0, /* sampleFlags= */ C.BUFFER_FLAG_KEY_FRAME);
    }
    sampleQueue.discardToEnd();
  }

  /** Asserts correct reading of the sync-sample-only test data from {@code sampleQueue}. */
  private void assertReadSyncSampleOnlyTestData() {
    assertReadSyncSampleOnlyTestData(
        /* firstSampleIndex= */ 0, /* sampleCount= */ SAMPLE_TIMESTAMPS.length);
  }

  /**
   * Asserts correct reading of the sync-sample-only test data from {@code sampleQueue}.
   *
   * @param firstSampleIndex The index of the first sample that's expected to be read.
   * @param sampleCount The number of samples to read.
   */
  private void assertReadSyncSampleOnlyTestData(int firstSampleIndex, int sampleCount) {
    assertReadTestData(
        /* startFormat= */ null,
        firstSampleIndex,
        sampleCount,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ 0,
        SAMPLE_FORMATS_SYNC_SAMPLES_ONLY,
        SAMPLE_FLAGS_SYNC_SAMPLES_ONLY);
  }

  /** Asserts correct reading of standard test data from {@code sampleQueue}. */
  private void assertReadTestData() {
    assertReadTestData(/* startFormat= */ null, 0);
  }

  /**
   * Asserts correct reading of standard test data from {@code sampleQueue}.
   *
   * @param startFormat The format of the last sample previously read from {@code sampleQueue}.
   */
  private void assertReadTestData(Format startFormat) {
    assertReadTestData(startFormat, 0);
  }

  /**
   * Asserts correct reading of standard test data from {@code sampleQueue}.
   *
   * @param startFormat The format of the last sample previously read from {@code sampleQueue}.
   * @param firstSampleIndex The index of the first sample that's expected to be read.
   */
  private void assertReadTestData(Format startFormat, int firstSampleIndex) {
    assertReadTestData(startFormat, firstSampleIndex, SAMPLE_TIMESTAMPS.length - firstSampleIndex);
  }

  /**
   * Asserts correct reading of standard test data from {@code sampleQueue}.
   *
   * @param startFormat The format of the last sample previously read from {@code sampleQueue}.
   * @param firstSampleIndex The index of the first sample that's expected to be read.
   * @param sampleCount The number of samples to read.
   */
  private void assertReadTestData(Format startFormat, int firstSampleIndex, int sampleCount) {
    assertReadTestData(
        startFormat,
        firstSampleIndex,
        sampleCount,
        /* sampleOffsetUs= */ 0,
        /* decodeOnlyUntilUs= */ 0);
  }

  /**
   * Asserts correct reading of standard test data from {@code sampleQueue}.
   *
   * @param startFormat The format of the last sample previously read from {@code sampleQueue}.
   * @param firstSampleIndex The index of the first sample that's expected to be read.
   * @param sampleCount The number of samples to read.
   * @param sampleOffsetUs The expected sample offset.
   */
  private void assertReadTestData(
      Format startFormat,
      int firstSampleIndex,
      int sampleCount,
      long sampleOffsetUs,
      long decodeOnlyUntilUs) {
    assertReadTestData(
        startFormat,
        firstSampleIndex,
        sampleCount,
        sampleOffsetUs,
        decodeOnlyUntilUs,
        SAMPLE_FORMATS,
        SAMPLE_FLAGS);
  }

  /**
   * Asserts correct reading of standard test data from {@code sampleQueue}.
   *
   * @param startFormat The format of the last sample previously read from {@code sampleQueue}.
   * @param firstSampleIndex The index of the first sample that's expected to be read.
   * @param sampleCount The number of samples to read.
   * @param sampleOffsetUs The expected sample offset.
   */
  private void assertReadTestData(
      @Nullable Format startFormat,
      int firstSampleIndex,
      int sampleCount,
      long sampleOffsetUs,
      long decodeOnlyUntilUs,
      Format[] sampleFormats,
      int[] sampleFlags) {
    Format format = adjustFormat(startFormat, sampleOffsetUs);
    for (int i = firstSampleIndex; i < firstSampleIndex + sampleCount; i++) {
      // Use equals() on the read side despite using referential equality on the write side, since
      // sampleQueue de-duplicates written formats using equals().
      Format testSampleFormat = adjustFormat(sampleFormats[i], sampleOffsetUs);
      if (!testSampleFormat.equals(format)) {
        // If the format has changed, we should read it.
        assertReadFormat(false, testSampleFormat);
        format = testSampleFormat;
      }
      // If we require the format, we should always read it.
      assertReadFormat(true, testSampleFormat);
      // Assert the sample is as expected.
      long expectedTimeUs = SAMPLE_TIMESTAMPS[i] + sampleOffsetUs;
      assertReadSample(
          expectedTimeUs,
          (sampleFlags[i] & C.BUFFER_FLAG_KEY_FRAME) != 0,
          /* isDecodeOnly= */ expectedTimeUs < decodeOnlyUntilUs,
          /* isEncrypted= */ false,
          DATA,
          DATA.length - SAMPLE_OFFSETS[i] - SAMPLE_SIZES[i],
          SAMPLE_SIZES[i]);
    }
  }

  /**
   * Asserts {@link SampleQueue#read} is behaving correctly, given there are no samples to read and
   * the last format to be written to the sample queue is {@code endFormat}.
   *
   * @param endFormat The last format to be written to the sample queue, or null of no format has
   *     been written.
   */
  private void assertNoSamplesToRead(Format endFormat) {
    // If not formatRequired or loadingFinished, should read nothing.
    assertReadNothing(false);
    // If formatRequired, should read the end format if set, else read nothing.
    if (endFormat == null) {
      assertReadNothing(true);
    } else {
      assertReadFormat(true, endFormat);
    }
    // If loadingFinished, should read end of stream.
    assertReadEndOfStream(false);
    assertReadEndOfStream(true);
    // Having read end of stream should not affect other cases.
    assertReadNothing(false);
    if (endFormat == null) {
      assertReadNothing(true);
    } else {
      assertReadFormat(true, endFormat);
    }
  }

  /**
   * Asserts {@link SampleQueue#read} returns {@link C#RESULT_NOTHING_READ}.
   *
   * @param formatRequired The value of {@code formatRequired} passed to {@link SampleQueue#read}.
   */
  private void assertReadNothing(boolean formatRequired) {
    clearFormatHolderAndInputBuffer();
    int result =
        sampleQueue.read(
            formatHolder,
            inputBuffer,
            formatRequired ? SampleStream.FLAG_REQUIRE_FORMAT : 0,
            /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_NOTHING_READ);
    // formatHolder should not be populated.
    assertThat(formatHolder.format).isNull();
    // inputBuffer should not be populated.
    assertInputBufferContainsNoSampleData();
    assertInputBufferHasNoDefaultFlagsSet();
  }

  /**
   * Asserts {@link SampleQueue#read} returns {@link C#RESULT_BUFFER_READ} and that the {@link
   * DecoderInputBuffer#isEndOfStream()} is set.
   *
   * @param formatRequired The value of {@code formatRequired} passed to {@link SampleQueue#read}.
   */
  private void assertReadEndOfStream(boolean formatRequired) {
    clearFormatHolderAndInputBuffer();
    int result =
        sampleQueue.read(
            formatHolder,
            inputBuffer,
            formatRequired ? SampleStream.FLAG_REQUIRE_FORMAT : 0,
            /* loadingFinished= */ true);
    assertThat(result).isEqualTo(RESULT_BUFFER_READ);
    // formatHolder should not be populated.
    assertThat(formatHolder.format).isNull();
    // inputBuffer should not contain sample data, but end of stream flag should be set.
    assertInputBufferContainsNoSampleData();
    assertThat(inputBuffer.isEndOfStream()).isTrue();
    assertThat(inputBuffer.isDecodeOnly()).isFalse();
    assertThat(inputBuffer.isEncrypted()).isFalse();
  }

  /**
   * Asserts {@link SampleQueue#read} returns {@link C#RESULT_FORMAT_READ} and that the format
   * holder is filled with a {@link Format} that equals {@code format}.
   *
   * @param formatRequired The value of {@code formatRequired} passed to {@link SampleQueue#read}.
   * @param format The expected format.
   */
  private void assertReadFormat(boolean formatRequired, Format format) {
    clearFormatHolderAndInputBuffer();
    int result =
        sampleQueue.read(
            formatHolder,
            inputBuffer,
            formatRequired ? SampleStream.FLAG_REQUIRE_FORMAT : 0,
            /* loadingFinished= */ false);
    assertThat(result).isEqualTo(RESULT_FORMAT_READ);
    // formatHolder should be populated.
    assertThat(formatHolder.format).isEqualTo(format);
    // inputBuffer should not be populated.
    assertInputBufferContainsNoSampleData();
    assertInputBufferHasNoDefaultFlagsSet();
  }

  private void assertReadEncryptedSample(int sampleIndex) {
    byte[] sampleData = new byte[ENCRYPTED_SAMPLE_SIZES[sampleIndex]];
    Arrays.fill(sampleData, (byte) 1);
    boolean isKeyFrame = (ENCRYPTED_SAMPLES_FLAGS[sampleIndex] & C.BUFFER_FLAG_KEY_FRAME) != 0;
    boolean isEncrypted = (ENCRYPTED_SAMPLES_FLAGS[sampleIndex] & C.BUFFER_FLAG_ENCRYPTED) != 0;
    assertReadSample(
        ENCRYPTED_SAMPLE_TIMESTAMPS[sampleIndex],
        isKeyFrame,
        /* isDecodeOnly= */ false,
        isEncrypted,
        sampleData,
        /* offset= */ 0,
        ENCRYPTED_SAMPLE_SIZES[sampleIndex] - (isEncrypted ? 2 : 0));
  }

  /**
   * Asserts {@link SampleQueue#read} returns {@link C#RESULT_BUFFER_READ} and that the buffer is
   * filled with the specified sample data.
   *
   * @param timeUs The expected buffer timestamp.
   * @param isKeyFrame The expected keyframe flag.
   * @param isDecodeOnly The expected decodeOnly flag.
   * @param isEncrypted The expected encrypted flag.
   * @param sampleData An array containing the expected sample data.
   * @param offset The offset in {@code sampleData} of the expected sample data.
   * @param length The length of the expected sample data.
   */
  private void assertReadSample(
      long timeUs,
      boolean isKeyFrame,
      boolean isDecodeOnly,
      boolean isEncrypted,
      byte[] sampleData,
      int offset,
      int length) {
    // Check that peek whilst omitting data yields the expected values.
    formatHolder.format = null;
    DecoderInputBuffer flagsOnlyBuffer = DecoderInputBuffer.newNoDataInstance();
    int result =
        sampleQueue.read(
            formatHolder,
            flagsOnlyBuffer,
            FLAG_OMIT_SAMPLE_DATA | FLAG_PEEK,
            /* loadingFinished= */ false);
    assertSampleBufferReadResult(
        flagsOnlyBuffer,
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ false);

    // Check that peek yields the expected values.
    clearFormatHolderAndInputBuffer();
    result = sampleQueue.read(formatHolder, inputBuffer, FLAG_PEEK, /* loadingFinished= */ false);
    assertSampleBufferReadResult(
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ false,
        sampleData,
        offset,
        length);

    // Check that read yields the expected values.
    clearFormatHolderAndInputBuffer();
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ false);
    assertSampleBufferReadResult(
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ false,
        sampleData,
        offset,
        length);
  }

  /**
   * Asserts {@link SampleQueue#read} returns {@link C#RESULT_BUFFER_READ} and that the buffer is
   * filled with the specified sample data. Also asserts that being the last sample and loading is
   * finished, that the {@link C#BUFFER_FLAG_LAST_SAMPLE} flag is set.
   *
   * @param timeUs The expected buffer timestamp.
   * @param isKeyFrame The expected keyframe flag.
   * @param isDecodeOnly The expected decodeOnly flag.
   * @param isEncrypted The expected encrypted flag.
   * @param sampleData An array containing the expected sample data.
   * @param offset The offset in {@code sampleData} of the expected sample data.
   * @param length The length of the expected sample data.
   */
  private void assertReadLastSample(
      long timeUs,
      boolean isKeyFrame,
      boolean isDecodeOnly,
      boolean isEncrypted,
      byte[] sampleData,
      int offset,
      int length) {
    // Check that peek whilst omitting data yields the expected values.
    formatHolder.format = null;
    DecoderInputBuffer flagsOnlyBuffer = DecoderInputBuffer.newNoDataInstance();
    int result =
        sampleQueue.read(
            formatHolder,
            flagsOnlyBuffer,
            FLAG_OMIT_SAMPLE_DATA | FLAG_PEEK,
            /* loadingFinished= */ true);
    assertSampleBufferReadResult(
        flagsOnlyBuffer,
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ true);

    // Check that peek yields the expected values.
    clearFormatHolderAndInputBuffer();
    result = sampleQueue.read(formatHolder, inputBuffer, FLAG_PEEK, /* loadingFinished= */ true);
    assertSampleBufferReadResult(
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ true,
        sampleData,
        offset,
        length);

    // Check that read yields the expected values.
    clearFormatHolderAndInputBuffer();
    result =
        sampleQueue.read(
            formatHolder, inputBuffer, /* readFlags= */ 0, /* loadingFinished= */ true);
    assertSampleBufferReadResult(
        result,
        timeUs,
        isKeyFrame,
        isDecodeOnly,
        isEncrypted,
        /* isLastSample= */ true,
        sampleData,
        offset,
        length);
  }

  private void assertSampleBufferReadResult(
      DecoderInputBuffer inputBuffer,
      int result,
      long timeUs,
      boolean isKeyFrame,
      boolean isDecodeOnly,
      boolean isEncrypted,
      boolean isLastSample) {
    assertThat(result).isEqualTo(RESULT_BUFFER_READ);
    // formatHolder should not be populated.
    assertThat(formatHolder.format).isNull();
    // inputBuffer should be populated with metadata.
    assertThat(inputBuffer.timeUs).isEqualTo(timeUs);
    assertThat(inputBuffer.isKeyFrame()).isEqualTo(isKeyFrame);
    assertThat(inputBuffer.isDecodeOnly()).isEqualTo(isDecodeOnly);
    assertThat(inputBuffer.isEncrypted()).isEqualTo(isEncrypted);
    assertThat(inputBuffer.isLastSample()).isEqualTo(isLastSample);
  }

  private void assertSampleBufferReadResult(
      int result,
      long timeUs,
      boolean isKeyFrame,
      boolean isDecodeOnly,
      boolean isEncrypted,
      boolean isLastSample,
      byte[] sampleData,
      int offset,
      int length) {
    assertSampleBufferReadResult(
        inputBuffer, result, timeUs, isKeyFrame, isDecodeOnly, isEncrypted, isLastSample);
    // inputBuffer should be populated with data.
    inputBuffer.flip();
    assertThat(inputBuffer.data.limit()).isEqualTo(length);
    byte[] readData = new byte[length];
    inputBuffer.data.get(readData);
    assertThat(readData).isEqualTo(copyOfRange(sampleData, offset, offset + length));
  }

  /**
   * Asserts the number of allocations currently in use by {@code sampleQueue}.
   *
   * @param count The expected number of allocations.
   */
  private void assertAllocationCount(int count) {
    assertThat(allocator.getTotalBytesAllocated()).isEqualTo(ALLOCATION_SIZE * count);
  }

  /** Asserts {@code inputBuffer} does not contain any sample data. */
  private void assertInputBufferContainsNoSampleData() {
    if (inputBuffer.data == null) {
      return;
    }
    inputBuffer.flip();
    assertThat(inputBuffer.data.limit()).isEqualTo(0);
  }

  private void assertInputBufferHasNoDefaultFlagsSet() {
    assertThat(inputBuffer.isEndOfStream()).isFalse();
    assertThat(inputBuffer.isDecodeOnly()).isFalse();
    assertThat(inputBuffer.isEncrypted()).isFalse();
  }

  private void clearFormatHolderAndInputBuffer() {
    formatHolder.format = null;
    inputBuffer.clear();
  }

  private static Format adjustFormat(@Nullable Format format, long sampleOffsetUs) {
    return format == null || sampleOffsetUs == 0
        ? format
        : format.buildUpon().setSubsampleOffsetUs(sampleOffsetUs).build();
  }

  private static Format buildFormat(String id) {
    return new Format.Builder().setId(id).setSubsampleOffsetUs(0).build();
  }

  private static Format copyWithLabel(Format format, String label) {
    return format.buildUpon().setLabel(label).build();
  }

  private static final class MockDrmSessionManager implements DrmSessionManager {

    private final DrmSession mockDrmSession;
    @Nullable private DrmSession mockPlaceholderDrmSession;

    private MockDrmSessionManager(DrmSession mockDrmSession) {
      this.mockDrmSession = mockDrmSession;
    }

    @Override
    public void setPlayer(Looper playbackLooper, PlayerId playerId) {}

    @Override
    @Nullable
    public DrmSession acquireSession(
        @Nullable DrmSessionEventListener.EventDispatcher eventDispatcher, Format format) {
      return format.drmInitData != null ? mockDrmSession : mockPlaceholderDrmSession;
    }

    @Override
    public @C.CryptoType int getCryptoType(Format format) {
      return mockPlaceholderDrmSession != null || format.drmInitData != null
          ? FakeCryptoConfig.TYPE
          : C.CRYPTO_TYPE_NONE;
    }
  }
}
